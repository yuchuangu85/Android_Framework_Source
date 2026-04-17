/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.wifi.sharedconnectivity.app;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.net.wifi.sharedconnectivity.service.ISharedConnectivityCallback;
import android.net.wifi.sharedconnectivity.service.ISharedConnectivityService;
import android.net.wifi.sharedconnectivity.service.SharedConnectivityService;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * This class is the library used by consumers of Shared Connectivity data to bind to the service,
 * receive callbacks from, and send user actions to the service.
 *
 * A client must register at least one callback so that the manager will bind to the service. Once
 * all callbacks are unregistered, the manager will unbind from the service. When the client no
 * longer needs Shared Connectivity data, the client must unregister.
 *
 * The methods {@link #connectHotspotNetwork}, {@link #disconnectHotspotNetwork},
 * {@link #connectKnownNetwork} and {@link #forgetKnownNetwork} are not valid and will return false
 * and getter methods will fail and return null if not called between
 * {@link SharedConnectivityClientCallback#onServiceConnected()}
 * and {@link SharedConnectivityClientCallback#onServiceDisconnected()} or if
 * {@link SharedConnectivityClientCallback#onRegisterCallbackFailed} was called.
 *
 * @hide
 */
@SystemApi
public class SharedConnectivityManager {
    private static final String TAG = SharedConnectivityManager.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final int RECOVER_MAXIMUM_RETRY_COUNT = 10;
    private static final long RECOVER_DELAYED_TIME_IN_MS = 10000L;

    private static final class SharedConnectivityCallbackProxy extends
            ISharedConnectivityCallback.Stub {
        private final Executor mExecutor;
        private final SharedConnectivityClientCallback mCallback;

        SharedConnectivityCallbackProxy(
                @NonNull @CallbackExecutor Executor executor,
                @NonNull SharedConnectivityClientCallback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onServiceConnected() {
            if (mCallback != null) {
                final long token = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mCallback.onServiceConnected());
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public void onServiceDisconnected() {
            if (mCallback != null) {
                final long token = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mCallback.onServiceDisconnected());
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public void onHotspotNetworksUpdated(@NonNull List<HotspotNetwork> networks) {
            if (mCallback != null) {
                final long token = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mCallback.onHotspotNetworksUpdated(networks));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public void onKnownNetworksUpdated(@NonNull List<KnownNetwork> networks) {
            if (mCallback != null) {
                final long token = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mCallback.onKnownNetworksUpdated(networks));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public void onSharedConnectivitySettingsChanged(
                @NonNull SharedConnectivitySettingsState state) {
            if (mCallback != null) {
                final long token = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mCallback.onSharedConnectivitySettingsChanged(state));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public void onHotspotNetworkConnectionStatusChanged(
                @NonNull HotspotNetworkConnectionStatus status) {
            if (mCallback != null) {
                final long token = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() ->
                            mCallback.onHotspotNetworkConnectionStatusChanged(status));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public void onKnownNetworkConnectionStatusChanged(
                @NonNull KnownNetworkConnectionStatus status) {
            if (mCallback != null) {
                final long token = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() ->
                            mCallback.onKnownNetworkConnectionStatusChanged(status));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }
    }

    private ISharedConnectivityService mService;
    @GuardedBy("mProxyDataLock")
    private final Map<SharedConnectivityClientCallback, SharedConnectivityCallbackProxy>
            mProxyMap = new HashMap<>();
    @GuardedBy("mProxyDataLock")
    private final Map<SharedConnectivityClientCallback, SharedConnectivityCallbackProxy>
            mCallbackProxyCache = new HashMap<>();
    // Makes sure mProxyMap and mCallbackProxyCache are locked together when one of them is used.
    private final Object mProxyDataLock = new Object();
    private final Context mContext;
    private final String mServicePackageName;
    private final String mIntentAction;
    private ServiceConnection mServiceConnection;
    private UserManager mUserManager;
    private Handler mMainHandler;

    private int mRetryCredit = RECOVER_MAXIMUM_RETRY_COUNT;
    // This field is used for reflection during testing, since creating an API needs approval.
    private long mDelayedTimeInMs = RECOVER_DELAYED_TIME_IN_MS;

    /**
     * Creates a new instance of {@link SharedConnectivityManager}.
     *
     * @return An instance of {@link SharedConnectivityManager} or null if the shared connectivity
     * service is not found.
     * @hide
     */
    @Nullable
    public static SharedConnectivityManager create(@NonNull Context context) {
        Resources resources = context.getResources();
        try {
            String servicePackageName = resources.getString(
                    R.string.config_sharedConnectivityServicePackage);
            String serviceIntentAction = resources.getString(
                    R.string.config_sharedConnectivityServiceIntentAction);
            if (TextUtils.isEmpty(servicePackageName) || TextUtils.isEmpty(serviceIntentAction)) {
                Log.e(TAG, "To support shared connectivity service on this device, the"
                        + " service's package name and intent action strings must not be empty");
                return null;
            }
            return new SharedConnectivityManager(context, servicePackageName, serviceIntentAction);
        } catch (Resources.NotFoundException e) {
            Log.e(TAG, "To support shared connectivity service on this device, the service's"
                    + " package name and intent action strings must be defined");
        }
        return null;
    }

    /**
     * @hide
     */
    @SuppressLint("ManagerLookup")
    @TestApi
    @Nullable
    public static SharedConnectivityManager create(@NonNull Context context,
            @NonNull String servicePackageName, @NonNull String serviceIntentAction) {
        return new SharedConnectivityManager(context, servicePackageName, serviceIntentAction);
    }

    private SharedConnectivityManager(@NonNull Context context, String servicePackageName,
            String serviceIntentAction) {
        mContext = context;
        mServicePackageName = servicePackageName;
        mIntentAction = serviceIntentAction;
        mUserManager = context.getSystemService(UserManager.class);
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    private void bind() {
        mServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.i(TAG, "onServiceConnected");
                mService = ISharedConnectivityService.Stub.asInterface(service);
                synchronized (mProxyDataLock) {
                    if (!mCallbackProxyCache.isEmpty()) {
                        mCallbackProxyCache.keySet().forEach(callback ->
                                registerCallbackInternal(
                                        callback, mCallbackProxyCache.get(callback)));
                        mCallbackProxyCache.clear();
                    }
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.i(TAG, "onServiceDisconnected");
                synchronized (mProxyDataLock) {
                    if (!mProxyMap.isEmpty()) {
                        mProxyMap.values().forEach(
                                SharedConnectivityCallbackProxy::onServiceDisconnected);
                        // Since service is disconnected, move callback from mProxyMap
                        // back to mCallbackProxyCache.
                        mCallbackProxyCache.putAll(mProxyMap);
                        mProxyMap.clear();
                    }
                }
                // When service disconnected, invoke unbind() to clear connection cache.
                unbind();
                // Schedule to reconnect to the remote service.
                scheduleConnect();
            }
        };

        boolean result = mContext.bindService(
                new Intent().setPackage(mServicePackageName).setAction(mIntentAction),
                mServiceConnection, Context.BIND_AUTO_CREATE);
        if (!result) {
            // No matter what the returned valus is, we should call unbind() to
            // release the connection.
            unbind();

            if (mUserManager != null && !mUserManager.isUserUnlocked()) {
                Log.i(TAG, "bindService failed, register user unlock");
                // In direct boot mode, register receiver as the trigger point.
                IntentFilter intentFilter = new IntentFilter();
                intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
                mContext.registerReceiver(mBroadcastReceiver, intentFilter);
            } else {
                if (mRetryCredit > 0) {
                    Log.i(TAG, "bindService failed, schedule to retry, mRetryCredit="
                               + mRetryCredit);
                    mRetryCredit--;
                    // Schedule to retry the connection attempt to the remote service.
                    scheduleConnect();
                    return;
                }
                Log.i(TAG, "bindService failed, no more retry credit");
                synchronized (mProxyDataLock) {
                    if (!mCallbackProxyCache.isEmpty()) {
                        mCallbackProxyCache.keySet().forEach(
                                callback -> callback.onRegisterCallbackFailed(
                                        new IllegalStateException(
                                                "Failed to bind after user unlock")));
                        mCallbackProxyCache.clear();
                    }
                }
            }
        } else {
            Log.i(TAG, "bindService success");
            // Sets to default value when bindService succeed.
            mRetryCredit = RECOVER_MAXIMUM_RETRY_COUNT;
        }
    }

    private void scheduleConnect() {
        if (DEBUG) Log.i(TAG, "scheduleConnect");
        mMainHandler.postDelayed(
                () -> {
                    boolean shouldRebind;
                    synchronized (mProxyDataLock) {
                        shouldRebind = !mCallbackProxyCache.isEmpty();
                    }
                    Log.i(TAG, "scheduleConnect: shouldRebind=" + shouldRebind);
                    if (shouldRebind) {
                        bind();
                    }
                },
                mDelayedTimeInMs);
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                Log.i(TAG, "onReceive: ACTION_USER_UNLOCKED");
                context.unregisterReceiver(mBroadcastReceiver);
                bind();
            } else {
                Log.w(TAG, "onReceive: unexpected action, value=" + intent.getAction());
            }
        }
    };

    /**
     * @hide
     */
    @TestApi
    @NonNull
    @SuppressLint("UnflaggedApi") // Exempt: Test API for already shipped feature
    public BroadcastReceiver getBroadcastReceiver() {
        return mBroadcastReceiver;
    }

    private void registerCallbackInternal(SharedConnectivityClientCallback callback,
            SharedConnectivityCallbackProxy proxy) {
        try {
            mService.registerCallback(proxy);
            synchronized (mProxyDataLock) {
                mProxyMap.put(callback, proxy);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in registerCallback", e);
            callback.onRegisterCallbackFailed(e);
        }
    }

    /**
     * @hide
     */
    @TestApi
    public void setService(@Nullable IInterface service) {
        mService = (ISharedConnectivityService) service;
    }

    /**
     * @hide
     */
    @TestApi
    @Nullable
    public ServiceConnection getServiceConnection() {
        return mServiceConnection;
    }

    private void unbind() {
        if (mServiceConnection != null) {
            Log.i(TAG, "unbind");
            mContext.unbindService(mServiceConnection);
            mServiceConnection = null;
            mService = null;
        }
    }

    /**
     * Registers a callback for receiving updates to the list of Hotspot Networks, Known Networks,
     * shared connectivity settings state, hotspot network connection status and known network
     * connection status.
     * Automatically binds to implementation of {@link SharedConnectivityService} specified in
     * the device overlay when the first callback is registered.
     * The {@link SharedConnectivityClientCallback#onRegisterCallbackFailed} will be called if the
     * registration failed.
     *
     * @param executor The Executor used to invoke the callback.
     * @param callback The callback of type {@link SharedConnectivityClientCallback} that is invoked
     *                 when the service updates its data.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD})
    public void registerCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull SharedConnectivityClientCallback callback) {
        Objects.requireNonNull(executor, "executor cannot be null");
        Objects.requireNonNull(callback, "callback cannot be null");

        if (mProxyMap.containsKey(callback) || mCallbackProxyCache.containsKey(callback)) {
            Log.e(TAG, "Callback already registered");
            callback.onRegisterCallbackFailed(new IllegalStateException(
                    "Callback already registered"));
            return;
        }

        Log.i(TAG, "registerCallback: callback=" + callback);
        SharedConnectivityCallbackProxy proxy =
                new SharedConnectivityCallbackProxy(executor, callback);
        if (mService == null) {
            boolean shouldBind;
            synchronized (mProxyDataLock) {
                // Size can be 1 in different cases of register/unregister sequences. If size is 0
                // Bind never happened or unbind was called.
                shouldBind = mCallbackProxyCache.size() == 0;
                mCallbackProxyCache.put(callback, proxy);
            }
            if (shouldBind) {
                bind();
            }
            return;
        }
        registerCallbackInternal(callback, proxy);
    }

    /**
     * Unregisters a callback.
     * Unbinds from {@link SharedConnectivityService} when no more callbacks are registered.
     *
     * @return Returns true if the callback was successfully unregistered, false otherwise.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD})
    public boolean unregisterCallback(
            @NonNull SharedConnectivityClientCallback callback) {
        Objects.requireNonNull(callback, "callback cannot be null");

        if (!mProxyMap.containsKey(callback) && !mCallbackProxyCache.containsKey(callback)) {
            Log.e(TAG, "Callback not found, cannot unregister");
            return false;
        }

        // Try to unregister the broadcast receiver to guard against memory leaks.
        try {
            mContext.unregisterReceiver(mBroadcastReceiver);
        } catch (IllegalArgumentException e) {
            // This is fine, it means the receiver was never registered or was already unregistered.
        }

        Log.i(TAG, "unregisterCallback: callback=" + callback);
        if (mService == null) {
            boolean shouldUnbind;
            synchronized (mProxyDataLock) {
                mCallbackProxyCache.remove(callback);
                // Connection was never established, so all registered callbacks are in the cache.
                shouldUnbind = mCallbackProxyCache.isEmpty();
            }
            if (shouldUnbind) {
                unbind();
            }
            return true;
        }

        try {
            boolean shouldUnbind;
            synchronized (mProxyDataLock) {
                mService.unregisterCallback(mProxyMap.get(callback));
                mProxyMap.remove(callback);
                shouldUnbind = mProxyMap.isEmpty();
            }
            if (shouldUnbind) {
                unbind();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in unregisterCallback", e);
            return false;
        }
        return true;
    }

    /**
     * Send command to the implementation of {@link SharedConnectivityService} requesting connection
     * to the specified Hotspot Network.
     *
     * @param network {@link HotspotNetwork} object representing the network the user has requested
     *                a connection to.
     * @return Returns true if the service received the command. Does not guarantee that the
     * connection was successful.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD})
    public boolean connectHotspotNetwork(@NonNull HotspotNetwork network) {
        Objects.requireNonNull(network, "Hotspot network cannot be null");

        ISharedConnectivityService service = mService;
        if (service == null) {
            return false;
        }

        try {
            if (DEBUG) Log.i(TAG, "connectHotspotNetwork");
            service.connectHotspotNetwork(network);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in connectHotspotNetwork", e);
            return false;
        }
        return true;
    }

    /**
     * Send command to the implementation of {@link SharedConnectivityService} requesting
     * disconnection from the active Hotspot Network.
     *
     * @param network {@link HotspotNetwork} object representing the network the user has requested
     *                to disconnect from.
     * @return Returns true if the service received the command. Does not guarantee that the
     * disconnection was successful.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD})
    public boolean disconnectHotspotNetwork(@NonNull HotspotNetwork network) {
        Objects.requireNonNull(network, "hotspot network cannot be null");

        ISharedConnectivityService service = mService;
        if (service == null) {
            return false;
        }

        try {
            if (DEBUG) Log.i(TAG, "disconnectHotspotNetwork");
            service.disconnectHotspotNetwork(network);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in disconnectHotspotNetwork", e);
            return false;
        }
        return true;
    }

    /**
     * Send command to the implementation of {@link SharedConnectivityService} requesting connection
     * to the specified Known Network.
     *
     * @param network {@link KnownNetwork} object representing the network the user has requested
     *                a connection to.
     * @return Returns true if the service received the command. Does not guarantee that the
     * connection was successful.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD})
    public boolean connectKnownNetwork(@NonNull KnownNetwork network) {
        Objects.requireNonNull(network, "Known network cannot be null");

        ISharedConnectivityService service = mService;
        if (service == null) {
            return false;
        }

        try {
            if (DEBUG) Log.i(TAG, "connectKnownNetwork");
            service.connectKnownNetwork(network);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in connectKnownNetwork", e);
            return false;
        }
        return true;
    }

    /**
     * Send command to the implementation of {@link SharedConnectivityService} requesting removal of
     * the specified Known Network from the list of Known Networks.
     *
     * @return Returns true if the service received the command. Does not guarantee that the
     * forget action was successful.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD})
    public boolean forgetKnownNetwork(@NonNull KnownNetwork network) {
        Objects.requireNonNull(network, "Known network cannot be null");

        ISharedConnectivityService service = mService;
        if (service == null) {
            return false;
        }

        try {
            if (DEBUG) Log.i(TAG, "forgetKnownNetwork");
            service.forgetKnownNetwork(network);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in forgetKnownNetwork", e);
            return false;
        }
        return true;
    }

    /**
     * Gets the list of hotspot networks the user can select to connect to.
     *
     * @return Returns a {@link List} of {@link HotspotNetwork} objects, null on failure.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD})
    @SuppressWarnings("NullableCollection")
    @Nullable
    public List<HotspotNetwork> getHotspotNetworks() {
        ISharedConnectivityService service = mService;
        if (service == null) {
            return null;
        }

        try {
            if (DEBUG) Log.i(TAG, "getHotspotNetworks");
            return service.getHotspotNetworks();
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in getHotspotNetworks", e);
        }
        return null;
    }

    /**
     * Gets the list of known networks the user can select to connect to.
     *
     * @return Returns a {@link List} of {@link KnownNetwork} objects, null on failure.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD})
    @SuppressWarnings("NullableCollection")
    @Nullable
    public List<KnownNetwork> getKnownNetworks() {
        ISharedConnectivityService service = mService;
        if (service == null) {
            return null;
        }

        try {
            if (DEBUG) Log.i(TAG, "getKnownNetworks");
            return service.getKnownNetworks();
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in getKnownNetworks", e);
        }
        return null;
    }

    /**
     * Gets the shared connectivity settings state.
     *
     * @return Returns a {@link SharedConnectivitySettingsState} object with the state, null on
     * failure.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD})
    @Nullable
    public SharedConnectivitySettingsState getSettingsState() {
        ISharedConnectivityService service = mService;
        if (service == null) {
            return null;
        }

        try {
            if (DEBUG) Log.i(TAG, "getSettingsState");
            return service.getSettingsState();
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in getSettingsState", e);
        }
        return null;
    }

    /**
     * Gets the connection status of the hotspot network the user selected to connect to.
     *
     * @return Returns a {@link HotspotNetworkConnectionStatus} object with the connection status,
     * null on failure. If no connection is active the status will be
     * {@link HotspotNetworkConnectionStatus#CONNECTION_STATUS_UNKNOWN}.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD})
    @Nullable
    public HotspotNetworkConnectionStatus getHotspotNetworkConnectionStatus() {
        ISharedConnectivityService service = mService;
        if (service == null) {
            return null;
        }

        try {
            if (DEBUG) Log.i(TAG, "getHotspotNetworkConnectionStatus");
            return service.getHotspotNetworkConnectionStatus();
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in getHotspotNetworkConnectionStatus", e);
        }
        return null;
    }

    /**
     * Gets the connection status of the known network the user selected to connect to.
     *
     * @return Returns a {@link KnownNetworkConnectionStatus} object with the connection status,
     * null on failure. If no connection is active the status will be
     * {@link KnownNetworkConnectionStatus#CONNECTION_STATUS_UNKNOWN}.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD})
    @Nullable
    public KnownNetworkConnectionStatus getKnownNetworkConnectionStatus() {
        ISharedConnectivityService service = mService;
        if (service == null) {
            return null;
        }

        try {
            if (DEBUG) Log.i(TAG, "getKnownNetworkConnectionStatus");
            return service.getKnownNetworkConnectionStatus();
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in getKnownNetworkConnectionStatus", e);
        }
        return null;
    }
}
