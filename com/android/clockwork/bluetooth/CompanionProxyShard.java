package com.android.clockwork.bluetooth;

import static com.android.clockwork.bluetooth.proxy.WearProxyConstants.PROXY_UUID;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothManagerCallback;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import com.android.clockwork.bluetooth.proxy.ProxyNetworkAgent;
import com.android.clockwork.bluetooth.proxy.ProxyServiceHelper;
import com.android.clockwork.bluetooth.proxy.WearProxyConstants.Reason;
import com.android.clockwork.common.DebugAssert;
import com.android.clockwork.common.Util;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import java.io.Closeable;
import java.lang.reflect.Method;

/**
 * Manages connection to the companion sysproxy network
 *
 * This class handles connecting to the remote device using the
 * bluetooth network and configuring the sysproxy to setup the
 * proper network to allow IP traffic to be utilized by Android.
 *
 * Steps to connect to the companion sysproxy.
 *
 * 1. Get a bluetooth rfcomm socket.
 *      This will actually establish a bluetooth connection from the device to the companion.
 * 2. Pass this rfcomm socket to the sysproxy module.
 *      The sysproxy module will formulate the necessary network configuration to allow
 *      IP traffic to flow over the bluetooth socket connection.
 * 3. Get acknowledgement that the sysproxy module initialized.
 *      This may or may not be completed successfully as indicated by the jni callback
 *      indicating connection or failure.
 */
public class CompanionProxyShard implements Closeable {
    private static final String TAG = WearBluetoothConstants.LOG_TAG;
    private static final int WHAT_START_SYSPROXY = 1;
    private static final int WHAT_JNI_ACTIVE_NETWORK_STATE = 2;
    private static final int WHAT_JNI_DISCONNECTED = 3;
    private static final int WHAT_RESET_CONNECTION = 4;

    private static final int TYPE_RFCOMM = 1;
    private static final int SEC_FLAG_ENCRYPT = 1 << 0;
    private static final int SEC_FLAG_AUTH = 1 << 1;
    // Relative unitless network retry values
    private static final int BACKOFF_BASE_INTERVAL = 2;
    private static final int BACKOFF_BASE_PERIOD = 5;
    private static final int BACKOFF_MAX_INTERVAL = 300;

    private static final int IS_NOT_METERED = 0;
    private static final int IS_METERED = 1;
    private static final boolean IS_CONNECTED = true;
    private static final boolean IS_DISCONNECTED = !IS_CONNECTED;
    private static final boolean PHONE_WITH_INTERNET = true;
    private static final boolean PHONE_NO_INTERNET = !PHONE_WITH_INTERNET;

    private static int sInstance;
    /** An async disconnect request is outstanding and waiting for a JNI response */
    private static boolean sWaitingForAsyncDisconnectResponse;

    static native void classInitNative();
    @VisibleForTesting native boolean connectNative(int fd);
    @VisibleForTesting native boolean disconnectNative();

    static {
        try {
            System.loadLibrary("wear-bluetooth-jni");
            classInitNative();
        } catch (UnsatisfiedLinkError e) {
            // Invoked during testing
            Log.e(TAG, "Unable to load wear bluetooth sysproxy jni native"
                    + " libraries");
        }
    }

    private final int mInstance;
    @VisibleForTesting int mStartAttempts;
    /**
     * Current active value of {@link ConnectivityManager#TYPE} from
     * {@link NetworkInfo#getType} on phone.
     **/
    @interface NetworkType { }
    @NetworkType
    @VisibleForTesting int mNetworkType;
    private boolean mIsSysproxyConnected;
    private boolean mIsMetered;
    private boolean mIsConnected;
    private boolean mPhoneNoInternet;

    /** The sysproxy network state
     *
     * The sysproxy network may be in one of the following states:
     *
     * 1. Disconnected from phone.
     *      There is no bluetooth rfcomm socket connected to the phone.
     * 2. Connected with Internet access via phone.
     *      There is a valid rfcomm socket connected to phone and the phone has a default
     *      network that had validated access to the Internet.
     * 3. Connected without Internet access via phone.
     *      There is a vaid rfcomm socket connected to phone but the phone has no validated
     *      network to the Internet.
     */
    private enum SysproxyNetworkState {
        DISCONNECTED,
        CONNECTED_NO_INTERNET,
        CONNECTED_WITH_INTERNET,
    }

    @NonNull private final Context mContext;
    @NonNull private final ProxyServiceHelper mProxyServiceHelper;
    @NonNull private final BluetoothDevice mCompanionDevice;
    @NonNull private final Listener mListener;

    private final MultistageExponentialBackoff mReconnectBackoff;
    @VisibleForTesting boolean mIsClosed;

    /**
     * Callback executed when the sysproxy connection changes state.
     *
     * This may send duplicate disconnect events, because failed reconnect
     * attempts are indistinguishable from actual disconnects.
     * Listeners should appropriately deduplicate these disconnect events.
     */
    public interface Listener {
        void onProxyConnectionChange(boolean isConnected, int proxyScore, boolean phoneNoInternet);
    }

    public CompanionProxyShard(
            @NonNull final Context context,
            @NonNull final ProxyServiceHelper proxyServiceHelper,
            @NonNull final BluetoothDevice companionDevice,
            @NonNull final Listener listener,
            final int networkScore) {
        DebugAssert.isMainThread();

        mContext = context;
        mProxyServiceHelper = proxyServiceHelper;
        mCompanionDevice = companionDevice;
        mListener = listener;

        mInstance = sInstance++;

        mReconnectBackoff = new MultistageExponentialBackoff(BACKOFF_BASE_INTERVAL,
                BACKOFF_BASE_PERIOD, BACKOFF_MAX_INTERVAL);

        maybeLogDebug("Created companion proxy shard");

        mProxyServiceHelper.setCompanionName(companionDevice.getName());
        mProxyServiceHelper.setNetworkScore(networkScore);
        startNetwork();
    }

    /** Completely shuts down companion proxy network */
    @MainThread
    @Override  // Closable
    public void close() {
        DebugAssert.isMainThread();
        if (mIsClosed) {
            Log.w(TAG, logInstance() + "Already closed");
            return;
        }
        updateAndNotify(SysproxyNetworkState.DISCONNECTED, Reason.CLOSABLE);
        // notify mListener of our intended disconnect before setting mIsClosed to true
        mIsClosed = true;
        disconnectNativeInBackground();
        mHandler.removeMessages(WHAT_START_SYSPROXY);
        maybeLogDebug("Closed companion proxy shard");
    }

    @MainThread
    public void startNetwork() {
        DebugAssert.isMainThread();
        maybeLogDebug("startNetwork()");
        mHandler.sendEmptyMessage(WHAT_START_SYSPROXY);
    }

    @MainThread
    public void updateNetwork(final int networkScore) {
        DebugAssert.isMainThread();
        mProxyServiceHelper.setNetworkScore(networkScore);
        notifyConnectionChange(mIsConnected, mPhoneNoInternet);
    }

    /** Serialize state change requests here */
    @VisibleForTesting
    final Handler mHandler = new Handler() {
        @MainThread
        @Override
        public void handleMessage(Message msg) {
            DebugAssert.isMainThread();
            switch (msg.what) {
                case WHAT_START_SYSPROXY:
                    mHandler.removeMessages(WHAT_START_SYSPROXY);
                    if (mIsClosed) {
                        maybeLogDebug("start sysproxy but shard closed...will bail");
                        return;
                    } else if (sWaitingForAsyncDisconnectResponse) {
                        maybeLogDebug("waiting for sysproxy to disconnect...will retry");
                        mHandler.sendEmptyMessage(WHAT_RESET_CONNECTION);
                        return;
                    }
                    mStartAttempts++;
                    if (connectedWithInternet()) {
                        maybeLogDebug("start sysproxy already running set connected");
                        updateAndNotify(SysproxyNetworkState.CONNECTED_WITH_INTERNET,
                                Reason.SYSPROXY_WAS_CONNECTED);
                    } else if (connectedNoInternet()) {
                        maybeLogDebug("start sysproxy already running but with no internet access");
                        updateAndNotify(SysproxyNetworkState.CONNECTED_NO_INTERNET,
                                Reason.SYSPROXY_NO_INTERNET);
                    } else {
                        maybeLogDebug("start up new sysproxy connection");
                        connectSysproxyInBackground();
                    }
                    break;
                case WHAT_JNI_ACTIVE_NETWORK_STATE:
                    if (mIsClosed) {
                        maybeLogDebug("JNI onActiveNetworkState shard closed...will bail");
                        return;
                    }
                    mNetworkType = msg.arg1;
                    mIsMetered = msg.arg2 == IS_METERED;
                    mIsSysproxyConnected = true;

                    if (connectedWithInternet()) {
                        updateAndNotify(SysproxyNetworkState.CONNECTED_WITH_INTERNET,
                                Reason.SYSPROXY_CONNECTED);
                        mProxyServiceHelper.setMetered(mIsMetered);
                    } else if (connectedNoInternet()) {
                        updateAndNotify(SysproxyNetworkState.CONNECTED_NO_INTERNET,
                                Reason.SYSPROXY_NO_INTERNET);
                    }
                    mReconnectBackoff.reset();
                    maybeLogDebug("JNI sysproxy process complete networkType:" + mNetworkType
                            + " metered:" + mIsMetered);
                    break;
                case WHAT_JNI_DISCONNECTED:
                    final int status = msg.arg1;
                    mIsSysproxyConnected = false;
                    sWaitingForAsyncDisconnectResponse = false;
                    maybeLogDebug("JNI onDisconnect isClosed:" + mIsClosed + " status:" + status);
                    updateAndNotify(SysproxyNetworkState.DISCONNECTED,
                            Reason.SYSPROXY_DISCONNECTED);
                    setUpRetryIfNotClosed();
                    break;
                case WHAT_RESET_CONNECTION:
                    // Take a hammer to reset everything on sysproxy side to initial state.
                    maybeLogDebug("Reset companion proxy network connection isClosed:" + mIsClosed);
                    mHandler.removeMessages(WHAT_START_SYSPROXY);
                    mHandler.removeMessages(WHAT_RESET_CONNECTION);
                    disconnectNativeInBackground();
                    setUpRetryIfNotClosed();
                    break;
            }
        }
    };

    private void setUpRetryIfNotClosed() {
        if (!mIsClosed) {
            final int nextRetry = mReconnectBackoff.getNextBackoff();
            mHandler.sendEmptyMessageDelayed(WHAT_START_SYSPROXY, nextRetry * 1000);
            Log.w(TAG, logInstance() + "Attempting reconnect in " + nextRetry + " seconds");
        }
    }

    @MainThread
    private void updateAndNotify(final SysproxyNetworkState state, final String reason) {
        DebugAssert.isMainThread();
        if (state == SysproxyNetworkState.CONNECTED_WITH_INTERNET) {
            mProxyServiceHelper.startNetworkSession(reason,
                    /**
                     * Called when the current network agent is no longer wanted
                     * by {@link ConnectivityService}.  Try to restart the network.
                     */
                    new ProxyNetworkAgent.Listener() {
                        @Override
                        public void onNetworkAgentUnwanted(int netId) {
                            Log.d(TAG, "Network agent unwanted netId:" + netId);
                            startNetwork();
                        }
                    });
            notifyConnectionChange(IS_CONNECTED, PHONE_WITH_INTERNET);
        } else if (state == SysproxyNetworkState.CONNECTED_NO_INTERNET) {
            mProxyServiceHelper.stopNetworkSession(reason);
            notifyConnectionChange(IS_CONNECTED, PHONE_NO_INTERNET);
        } else {
            mProxyServiceHelper.stopNetworkSession(reason);
            notifyConnectionChange(IS_DISCONNECTED);
        }
    }

    /**
     * Request an rfcomm socket to the companion device.
     *
     * Connect to the companion device with a bluetooth rfcomm socket.
     * The integer filedescriptor portion of the {@link ParcelFileDescriptor}
     * is used to pass to the sysproxy native code via
     * {@link CompanionProxyShard#connectNativeInBackground}
     *
     * Failures in any of these steps enter into a delayed retry mode.
     */
    @MainThread
    private void connectSysproxyInBackground() {
        DebugAssert.isMainThread();

        maybeLogDebug("Retrieving bluetooth network socket");

        new DefaultPriorityAsyncTask<Void, Void, ParcelFileDescriptor>() {
            @Override
            protected ParcelFileDescriptor doInBackgroundDefaultPriority() {
                try {
                    final IBluetooth bluetoothProxy = getBluetoothService(mInstance);
                    if (bluetoothProxy == null) {
                        Log.e(TAG, logInstance() + "Unable to get binder proxy to IBluetooth");
                        return null;
                    }
                    ParcelFileDescriptor parcelFd = bluetoothProxy.getSocketManager().connectSocket(
                            mCompanionDevice,
                            TYPE_RFCOMM,
                            PROXY_UUID,
                            0 /* port */,
                            SEC_FLAG_AUTH | SEC_FLAG_ENCRYPT
                            );
                    maybeLogDebug("parcelFd:" + parcelFd);
                    return parcelFd;
                } catch (RemoteException e) {
                    Log.e(TAG, logInstance() + "Unable to get bluetooth service", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(@Nullable ParcelFileDescriptor parcelFd) {
                DebugAssert.isMainThread();

                if (mIsClosed) {
                    maybeLogDebug("Shard closed after retrieving bluetooth socket");
                    Util.close(parcelFd);
                    return;
                } else if (parcelFd != null) {
                    final int fd = parcelFd.detachFd();
                    maybeLogDebug("Retrieved bluetooth network socket parcelFd:" + parcelFd
                            + " fd:" + fd);
                    connectNativeInBackground(fd);
                } else {
                    Log.e(TAG, logInstance() + "Unable to request bluetooth network socket");
                    mHandler.sendEmptyMessage(WHAT_RESET_CONNECTION);
                }
                Util.close(parcelFd);
            }
        }.execute();
    }

    /**
     * Pass connected socket to sysproxy module.
     *
     * Hand off a connected socket to the native sysproxy code to
     * provide bidirectional network connectivity for the system.
     */
    @MainThread
    private void connectNativeInBackground(Integer fd) {
        DebugAssert.isMainThread();

        new PassSocketAsyncTask() {
            @Override
            protected Boolean doInBackgroundDefaultPriority(Integer fileDescriptor) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                final int fd = fileDescriptor.intValue();
                maybeLogDebug("connectNativeInBackground fd:" + fd);
                final boolean rc = connectNative(fd);
                return new Boolean(rc);
            }

            @Override
            protected void onPostExecute(Boolean result) {
                DebugAssert.isMainThread();
                if (mIsClosed) {
                    maybeLogDebug("Shard closed after sending bluetooth socket");
                    return;
                }
                if (result) {
                    maybeLogDebug("proxy socket delivered fd:" + fd);
                } else {
                    Log.w(TAG, logInstance() + "Unable to deliver socket to sysproxy module");
                    mHandler.sendEmptyMessage(WHAT_RESET_CONNECTION);
                }
            }
        }.execute(fd);
    }

    /**
     * Disconnect the current sysproxy network session.
     *
     * Inform the native sysproxy module to teardown the current sysproxy session.
     */
    @MainThread
    private void disconnectNativeInBackground() {
        DebugAssert.isMainThread();
        if (!mIsSysproxyConnected) {
            maybeLogDebug("JNI has already disconnected");
            return;
        }

        new DefaultPriorityAsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackgroundDefaultPriority() {
                maybeLogDebug("JNI Disconnect request to sysproxy module");
                return disconnectNative();
            }

            @MainThread
            @Override
            protected void onPostExecute(Boolean result) {
                DebugAssert.isMainThread();
                // Double check if sysproxy is still connected and did not
                // initiate a disconnect during this async operation.
                // see: bug 111653688
                if (mIsSysproxyConnected) {
                    sWaitingForAsyncDisconnectResponse = result;
                }
                maybeLogDebug("JNI Disconnect response result:" + result
                        + " mIsSysproxyConnected:" + mIsSysproxyConnected
                        + " isClosed:" + mIsClosed);
            }
        }.execute();
    }

    /**
     * This method is called from JNI in a background thread when the companion proxy
     * network state changes on the phone.
     */
    @WorkerThread
    protected void onActiveNetworkState(@NetworkType final int networkType,
            final boolean isMetered) {
        mHandler.sendMessage(mHandler.obtainMessage(WHAT_JNI_ACTIVE_NETWORK_STATE, networkType,
                    isMetered ? IS_METERED : IS_NOT_METERED));
    }

    /** This method is called from JNI in a background thread when the proxy has disconnected. */
    @WorkerThread
    protected void onDisconnect(final int status) {
        mHandler.sendMessage(mHandler.obtainMessage(WHAT_JNI_DISCONNECTED, status, 0));
    }

    /**
     * This method notifies the listener about the state of the sysproxy network.
     *
     *  NOTE: CompanionProxyShard should never call onProxyConnectionChange directly!
     *       Use the notifyConnectionChange method instead.
     */
    @MainThread
    private void notifyConnectionChange(final boolean isConnected) {
        DebugAssert.isMainThread();
        notifyConnectionChange(isConnected, false);
    }

    @MainThread
    private void notifyConnectionChange(final boolean isConnected, final boolean phoneNoInternet) {
        DebugAssert.isMainThread();
        mIsConnected = isConnected;
        mPhoneNoInternet = phoneNoInternet;
        doNotifyConnectionChange(mProxyServiceHelper.getNetworkScore());
    }

    @MainThread
    private void doNotifyConnectionChange(final int networkScore) {
        DebugAssert.isMainThread();
        if (!mIsClosed) {
            mListener.onProxyConnectionChange(mIsConnected, networkScore, mPhoneNoInternet);
        }
    }

    private boolean connectedWithInternet() {
        return mIsSysproxyConnected && mNetworkType != ConnectivityManager.TYPE_NONE;
    }

    private boolean connectedNoInternet() {
        return mIsSysproxyConnected && mNetworkType == ConnectivityManager.TYPE_NONE;
    }

    private abstract static class DefaultPriorityAsyncTask<Params, Progress, Result>
            extends AsyncTask<Params, Progress, Result> {

            @Override
            protected Result doInBackground(Params... params) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                return doInBackgroundDefaultPriority();
            }

            protected abstract Result doInBackgroundDefaultPriority();
    }

    private abstract static class PassSocketAsyncTask extends AsyncTask<Integer, Void, Boolean> {
        @Override
        protected Boolean doInBackground(Integer... params) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            final Integer fileDescriptor = params[0];
            return doInBackgroundDefaultPriority(fileDescriptor);
        }

        protected abstract Boolean doInBackgroundDefaultPriority(Integer fd);
    }

    /** Returns the shared instance of IBluetooth using reflection (method is package private). */
    private static IBluetooth getBluetoothService(final int instance) {
        try {
            final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            final Method getBluetoothService = adapter.getClass()
                .getDeclaredMethod("getBluetoothService", IBluetoothManagerCallback.class);
            getBluetoothService.setAccessible(true);
            return (IBluetooth) getBluetoothService.invoke(adapter, new Object[] { null });
        } catch (Exception e) {
            Log.e(TAG, "CompanionProxyShard [ " + instance + " ] Error retrieving IBluetooth: ", e);
            return null;
        }
    }

    private void maybeLogDebug(final String msg) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, logInstance() + msg);
        }
    }

    private String logInstance() {
        return "CompanionProxyShard [ " + mInstance + " ] ";
    }

    public void dump(@NonNull final IndentingPrintWriter ipw) {
        ipw.printf("Companion proxy [ %s ] %s", mCompanionDevice, mCompanionDevice.getName());
        ipw.println();
        ipw.increaseIndent();
        ipw.printPair("isClosed", mIsClosed);
        ipw.printPair("Start attempts", mStartAttempts);
        ipw.printPair("Start connection scheduled", mHandler.hasMessages(WHAT_START_SYSPROXY));
        ipw.printPair("Instance", mInstance);
        ipw.printPair("networkTypeName", ConnectivityManager.getNetworkTypeName(mNetworkType));
        ipw.printPair("isMetered", mIsMetered);
        ipw.printPair("isSysproxyConnected", mIsSysproxyConnected);
        ipw.println();
        mProxyServiceHelper.dump(ipw);
        ipw.decreaseIndent();
    }
}
