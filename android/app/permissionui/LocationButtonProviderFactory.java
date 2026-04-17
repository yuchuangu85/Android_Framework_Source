/*
 * Copyright (C) 2025 The Android Open Source Project
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
package android.app.permissionui;

import static java.util.Objects.requireNonNull;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.Trace;
import android.permission.flags.Flags;
import android.util.Log;

import com.android.internal.R;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * A factory for creating {@link LocationButtonProvider} instances. This is the
 * primary entry point for jetpack widget to interact with the Location Button APIs.
 *
 * @see LocationButtonProvider
 */
@FlaggedApi(Flags.FLAG_LOCATION_BUTTON_ENABLED)
public class LocationButtonProviderFactory {
    private static final String TAG = "LocationButtonProvider";

    private LocationButtonProviderFactory() {
    }

    /**
     * Creates a new instance of the {@link LocationButtonProvider}.
     *
     * <p>This method should be called to obtain a provider object, which can then be used to
     * create and manage Location Button sessions.
     *
     * @param context The {@link Context} of the calling application. This is used to access
     *                system services and resources required by the Location Button.
     * @return A new {@link LocationButtonProvider} instance that can be used to open location
     * button sessions.
     */
    @NonNull
    public static LocationButtonProvider create(@NonNull Context context) {
        return new LocationButtonProviderImpl(context);
    }

    static final class LocationButtonProviderImpl implements LocationButtonProvider {
        // Retry attempts when binding is successful but the service is not able to open a session.
        private static final int OPEN_SESSION_ATTEMPTS_LIMIT = 5;
        private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
        private static final long UNBIND_SERVICE_DELAY_MILLIS = 5000;

        private final Context mContext;
        private final String mPackageName;
        private ServiceConnectionHandler mConnection;
        private ILocationButtonService mService;
        private final Handler mHandler = new Handler(requireNonNull(Looper.myLooper()));

        private final SerialExecutor mSerialExecutor =
                new SerialExecutor(Runnable::run);

        private final Runnable mUnbindRunnable = () -> mSerialExecutor.execute(
                this::unbindServiceInternalSerialized);

        private final Queue<OpenSessionRequest> mPendingOpenSessionRequests = new ArrayDeque<>();
        // TODO align with photo picker, just in case we want to abstract out this pattern
        //  for other services (Selection Toolbar).
        // A map of active location button sessions, where the key is the client callback and
        // the value is the opened session record.
        private final Map<LocationButtonClient, LocationButtonSessionRecord> mOpenSessions =
                new HashMap<>();

        LocationButtonProviderImpl(@NonNull Context context) {
            mContext = context;
            mPackageName = context.getPackageName();
            mConnection = new ServiceConnectionHandler(mContext, this);
        }

        @Override
        public void openSession(@NonNull Activity activity, @NonNull IBinder hostToken,
                int displayId, @NonNull LocationButtonRequest request,
                @NonNull Executor clientExecutor, @NonNull LocationButtonClient client
        ) {
            if (DEBUG) {
                Log.d(TAG, "openSession received for client " + client);
            }
            requireNonNull(activity, "activity must not be null");
            requireNonNull(hostToken, "hostToken must not be null");
            requireNonNull(request, "request must not be null");
            requireNonNull(clientExecutor, "clientExecutor must not be null");
            requireNonNull(client, "client must not be null");

            mSerialExecutor.execute(
                    () -> openSessionSerialized(activity, hostToken, displayId, request,
                            clientExecutor, client));
        }

        private void openSessionSerialized(@NonNull Activity activity, @NonNull IBinder hostToken,
                int displayId, @NonNull LocationButtonRequest request,
                @NonNull Executor clientExecutor, @NonNull LocationButtonClient client) {
            mHandler.removeCallbacks(mUnbindRunnable);
            Trace.asyncTraceBegin(Trace.TRACE_TAG_APP, "LocationButton#openSessionAsync",
                    System.identityHashCode(client));
            LocationButtonClientWrapper clientWrapper = new LocationButtonClientWrapper(activity,
                    this, client, new SerialExecutor(clientExecutor));
            OpenSessionRequest sessionRequest = new OpenSessionRequest(hostToken, displayId,
                    request, client, clientWrapper, clientExecutor);
            if (DEBUG) {
                Log.d(TAG, "openSession request received with params = " + sessionRequest);
            }
            ensureValidServiceConnectionExistsSerialized();
            if (mConnection.isConnected()) {
                openSessionInternalSerialized(sessionRequest);
            } else {
                bindServiceAndSaveRequestSerialized(sessionRequest);
            }
        }

        private void ensureValidServiceConnectionExistsSerialized() {
            if (!mConnection.isConnectionValid()) {
                mConnection = new ServiceConnectionHandler(mContext, this);
            }
        }

        private void bindServiceAndSaveRequestSerialized(OpenSessionRequest sessionRequest) {
            ensureValidServiceConnectionExistsSerialized();
            mConnection.bindServiceSerialized();
            if (mConnection.isBindRequested()) {
                sessionRequest.mTotalOpenSessionAttempts++;
                mPendingOpenSessionRequests.add(sessionRequest);
            } else {
                reportSessionErrorSerialized(sessionRequest,
                        new RuntimeException("Unable to bind location button service."));
            }
        }

        private void openSessionInternalSerialized(OpenSessionRequest sessionRequest) {
            try {
                mService.openSession(mPackageName, sessionRequest.mHostToken,
                        sessionRequest.mDisplayId, sessionRequest.mRequest,
                        sessionRequest.mClientCallbackWrapper);
            } catch (DeadObjectException e) {
                Log.e(TAG, "Couldn't make call to remote delegate. Retrying.", e);

                mConnection.disposeSerialized();
                mService = null;
                if (sessionRequest.mTotalOpenSessionAttempts < OPEN_SESSION_ATTEMPTS_LIMIT) {
                    bindServiceAndSaveRequestSerialized(sessionRequest);
                } else {
                    reportSessionErrorSerialized(sessionRequest,
                            new RuntimeException("Unable to get valid remote delegate."));
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Remote delegate is Invalid! Failed to open session.", e);
                reportSessionErrorSerialized(sessionRequest,
                        new RemoteException("Remote delegate is Invalid! Failed to open session"));
            }
        }

        private boolean performBindServiceSerialized(Intent intent) {
            Trace.asyncTraceBegin(Trace.TRACE_TAG_APP, "LocationButton#bindService",
                    System.identityHashCode(mConnection));
            boolean bindRequested = true;
            try {
                bindRequested = mContext.bindService(intent, Context.BIND_AUTO_CREATE,
                        mSerialExecutor, mConnection);

                if (!bindRequested) {
                    removePendingRequestsAndNotifyClients(
                            new RuntimeException("Unable to bind location button service."));
                }
            } catch (SecurityException e) {
                bindRequested = false;
                removePendingRequestsAndNotifyClients(
                        new SecurityException("Unable to bind location button service."));
            } finally {
                Trace.asyncTraceEnd(Trace.TRACE_TAG_APP, "LocationButton#bindService",
                        System.identityHashCode(mConnection));
                if (!bindRequested) {
                    mConnection.unbindAndDisposeConnectionSerialized();
                }
            }
            return bindRequested;
        }

        private void onServiceConnectedSerialized(IBinder service) {
            mService = ILocationButtonService.Stub.asInterface(service);

            while (!mPendingOpenSessionRequests.isEmpty()) {
                openSessionInternalSerialized(mPendingOpenSessionRequests.remove());
            }
        }

        private void onServiceDisconnectedSerialized() {
            mService = null;
            closeActiveSessionsAndNotifyClients(
                    new RuntimeException("Service disconnected, Session closed."));
            mConnection.bindServiceSerialized();
        }

        private void onBindingDiedSerialized() {
            closeActiveSessionsAndNotifyClients(
                    new RuntimeException("Service died, Session closed."));
            mConnection.unbindAndDisposeConnectionSerialized();
            ensureValidServiceConnectionExistsSerialized();
            mConnection.bindServiceSerialized();
        }

        private void onServiceNullBindingSerialized() {
            mService = null;
            removePendingRequestsAndNotifyClients(
                    new RuntimeException("Unable to bind location button service (null)."));
        }

        /**
         * Report all pending clients that the service binding failed.
         */
        private void removePendingRequestsAndNotifyClients(Throwable cause) {
            while (!mPendingOpenSessionRequests.isEmpty()) {
                reportSessionErrorSerialized(mPendingOpenSessionRequests.remove(), cause);
            }
        }

        private void reportSessionErrorSerialized(OpenSessionRequest request,
                Throwable cause) {
            request.mClientExecutor.execute(() -> request.mClientCallback.onSessionError(cause));
        }

        /**
         * Clean up resources when client close a session. This helps unbind location button service
         * when all sessions are closed.
         *
         * @hide
         */
        void onSessionClosed(@NonNull LocationButtonClient client) {
            if (DEBUG) {
                Log.d(TAG, "onSessionClosed received for client " + client);
            }
            mSerialExecutor.execute(() -> onSessionClosedSerialized(client));
        }

        /**
         * Track opened sessions, we may need to clean up these sessions when button rendering
         * process dies or service becomes disconnected.
         *
         * @hide
         */
        void addActiveSessionRecord(LocationButtonClient client,
                LocationButtonSessionRecord session) {
            if (DEBUG) {
                Log.d(TAG, "addActiveSessionRecord received for client " + client);
            }
            mSerialExecutor.execute(() -> mOpenSessions.put(client, session));
            Trace.asyncTraceEnd(Trace.TRACE_TAG_APP, "LocationButton#openSessionAsync",
                    System.identityHashCode(client));
        }

        private void onSessionClosedSerialized(@NonNull LocationButtonClient client) {
            if (DEBUG) {
                Log.d(TAG, "onSessionClosedSerialized received for client " + client);
            }
            if (mOpenSessions.remove(client) == null) {
                return;
            }

            if (mOpenSessions.isEmpty() && mPendingOpenSessionRequests.isEmpty()) {
                mHandler.removeCallbacks(mUnbindRunnable);
                mHandler.postDelayed(mUnbindRunnable, UNBIND_SERVICE_DELAY_MILLIS);
            }
        }

        private void unbindServiceInternalSerialized() {
            if (mOpenSessions.isEmpty() && mPendingOpenSessionRequests.isEmpty()) {
                if (DEBUG) {
                    Log.d(TAG, "Unbinding service as no active session open & pending request.");
                }
                mService = null;
                mConnection.unbindAndDisposeConnectionSerialized();
            }
        }

        private void closeActiveSessionsAndNotifyClients(Throwable cause) {
            if (DEBUG) {
                Log.d(TAG, "Closing all active sessions with cause", cause);
            }
            for (Map.Entry<LocationButtonClient, LocationButtonSessionRecord> session :
                    mOpenSessions.entrySet()) {
                LocationButtonClient client = session.getKey();
                LocationButtonSessionRecord sessionWrapper = session.getValue();
                try {
                    sessionWrapper.locationButtonSession.close();
                } catch (RemoteException e) {
                    // ignore the error.
                }
                sessionWrapper.clientExecutor.execute(() -> client.onSessionError(cause));
            }
            mOpenSessions.clear();
        }

        private static class ServiceConnectionHandler implements ServiceConnection {
            private boolean mIsConnected;
            private boolean mIsBindRequested;
            private boolean mIsConnectionValid = true;
            private Context mContext;
            private LocationButtonProviderImpl mProvider;
            private final Intent mIntent;

            ServiceConnectionHandler(Context context, LocationButtonProviderImpl provider) {
                mContext = context;
                mProvider = provider;
                mIntent = new Intent();
                mIntent.setComponent(ComponentName.unflattenFromString(
                        context.getResources().getString(
                                R.string.config_locationButtonRenderService)));
            }

            private void disposeSerialized() {
                if (DEBUG) {
                    Log.d(TAG, "ServiceConnection disposing previous states");
                }
                mProvider = null;
                mIsConnected = false;
                mIsBindRequested = false;
                mIsConnectionValid = false;
                mContext = null;
            }

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (DEBUG) {
                    Log.d(TAG, "onServiceConnected: " + name);
                }
                if (mProvider != null) {
                    mIsConnected = true;
                    mIsBindRequested = false;
                    mProvider.onServiceConnectedSerialized(service);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (DEBUG) {
                    Log.d(TAG, "onServiceDisconnected: " + name);
                }
                if (mProvider != null) {
                    mIsConnected = false;
                    mIsBindRequested = false;
                    mProvider.onServiceDisconnectedSerialized();
                }
            }

            @Override
            public void onNullBinding(ComponentName name) {
                if (DEBUG) {
                    Log.d(TAG, "onNullBinding: " + name);
                }
                if (mProvider != null) {
                    mIsConnected = false;
                    mIsBindRequested = false;
                    unbindAndDisposeConnectionSerialized();
                    mProvider.onServiceNullBindingSerialized();
                }
            }

            @Override
            public void onBindingDied(ComponentName name) {
                if (DEBUG) {
                    Log.d(TAG, "onBindingDied: " + name);
                }
                if (mProvider != null) {
                    mIsConnected = false;
                    mIsBindRequested = false;
                    mProvider.onBindingDiedSerialized();
                }
            }

            private void bindServiceSerialized() {
                if (DEBUG) {
                    Log.d(TAG, "bindServiceSerialized");
                }
                if (mProvider != null && !mIsBindRequested) {
                    mIsBindRequested = mProvider.performBindServiceSerialized(mIntent);
                }
            }

            private void unbindAndDisposeConnectionSerialized() {
                if (DEBUG) {
                    Log.d(TAG, "unbindAndDisposeConnectionSerialized");
                }
                try {
                    mContext.unbindService(this);
                } catch (IllegalArgumentException ex) {
                    // Ignore if the service was already unbound.
                }
                disposeSerialized();
            }

            private boolean isConnected() {
                return mIsConnected;
            }

            private boolean isBindRequested() {
                return mIsBindRequested;
            }

            private boolean isConnectionValid() {
                return mIsConnectionValid;
            }
        }

        private static class OpenSessionRequest {
            private final IBinder mHostToken;
            private final int mDisplayId;
            private final LocationButtonRequest mRequest;
            private final Executor mClientExecutor;
            private final LocationButtonClient mClientCallback;
            private final LocationButtonClientWrapper mClientCallbackWrapper;
            private int mTotalOpenSessionAttempts = 0;

            private OpenSessionRequest(IBinder hostToken, int displayId,
                    LocationButtonRequest request, LocationButtonClient mClientCallback,
                    LocationButtonClientWrapper clientCallbackWrapper, Executor clientExecutor) {
                this.mHostToken = hostToken;
                this.mDisplayId = displayId;
                this.mRequest = request;
                this.mClientCallback = mClientCallback;
                this.mClientCallbackWrapper = clientCallbackWrapper;
                this.mClientExecutor = clientExecutor;
            }

            @Override
            public String toString() {
                return "OpenSessionRequest{" + "mHostToken=" + mHostToken + ", mDisplayId="
                        + mDisplayId + ", mRequest=" + mRequest + ", mClientCallbackWrapper="
                        + mClientCallbackWrapper + ", mClientCallback=" + mClientCallback
                        + ", mClientExecutor=" + mClientExecutor + '}';
            }
        }
    }

    /**
     * Executes tasks serially, delegating execution to another base {@link Executor}.
     *
     * <p> Regardless of whether base executor is single-threaded or not,
     * this ensures that all incoming tasks are enqueued and executed one after another.
     *
     * <p>Implementation copied from {@link Executor} javadoc.
     */
    private static class SerialExecutor implements Executor {
        final Queue<Runnable> mTasks = new ArrayDeque<>();
        final Executor mExecutor;
        Runnable mActive;

        SerialExecutor(Executor executor) {
            this.mExecutor = executor;
        }

        @Override
        public synchronized void execute(Runnable r) {
            mTasks.add(() -> {
                try {
                    r.run();
                } finally {
                    scheduleNext();
                }
            });
            if (mActive == null) {
                scheduleNext();
            }
        }

        private synchronized void scheduleNext() {
            if ((mActive = mTasks.poll()) != null) {
                mExecutor.execute(mActive);
            }
        }
    }

    /**
     * @hide
     */
    record LocationButtonSessionRecord(ILocationButtonSession locationButtonSession,
                                       Executor clientExecutor) {
    }
}
