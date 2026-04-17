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

package android.service.chooser;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.Executor;

/**
 * A client for interacting with a {@link TapToShareService} implementation.
 *
 * <p><b>This client is designed specifically for use by the system sharesheet and for CTS testing.
 * </b> It is not a general-purpose API for third-party applications. The system sharesheet is
 * configured to bind to a single, specific {@link TapToShareService} implementation, so other
 * uses of this client would have no effect on the system UI. Use of this client also requires
 * the {@link android.Manifest.permission#BIND_TO_TAP_TO_SHARE_SERVICE} permission.
 *
 * <p>This client simplifies integration with a {@link TapToShareService} by managing the
 * binding, connection lifecycle, and callbacks. It provides a clean interface for the system
 * sharesheet to start and end tap-to-share sessions without needing to handle the underlying
 * AIDL and {@link ServiceConnection} details directly.
 *
 * <p>A tap-to-share session begins when {@link #startSession} is called, which triggers
 * {@link TapToShareService#onSessionStart()} on the service side. The session ends when
 * {@link #endSession} is called, which triggers {@link TapToShareService#onSessionEnd()}.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_TAP_TO_SHARE)
@TestApi
public final class TapToShareClient {
    private static final String TAG = TapToShareClient.class.getSimpleName();

    /**
     * A listener for receiving notifications about the tap-to-share session.
     */
    public interface SessionListener {
       /**
         * Called when a peer device is tapped and ready for sharing.
         *
         * <p>This callback is invoked on the executor provided in
         * {@link #startSession}.
         */
        void onDeviceTapped();

        /**
         * Called when there is an error establishing or maintaining the connection to the
         * service. This can happen if:
         * <ul>
         *     <li>Binding to the service fails (e.g., service not found, {@code bindService}
         *         returns false).</li>
         *     <li>A {@code SecurityException} occurs during binding.</li>
         *     <li>The connection to the service is lost unexpectedly (e.g., the service process
         *         dies or is killed).</li>
         *     <li>An error occurs during the initial setup after connection (e.g., callback
         *         registration fails).</li>
         * </ul>
         *
         * <p>Note: The listener is not currently notified if the service returns a null binding
         * from its {@code onBind()} method.
         *
         * <p>This callback is invoked on the executor provided in
         * {@link #startSession}.
         *
         * <p>When this callback is invoked, the client is responsible for calling
         * {@link #endSession()} to clean up resources and terminate the session. Failure to do so
         * will prevent new sessions from being started.
         *
         * @param e The exception or cause of the failure.
         */
        void onConnectionFailed(@NonNull Exception e);
    }

    private final Context mContext;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private ITapToShareService mService;
    @GuardedBy("mLock")
    private SessionListener mSessionListener;
    @GuardedBy("mLock")
    private Executor mExecutor;

    private final ITapToShareCallback mCallback = new ITapToShareCallback.Stub() {
        @Override
        public void performTapToShare() {
            Executor executor;
            SessionListener listener;
            synchronized (mLock) {
                executor = mExecutor;
                listener = mSessionListener;
            }
            if (executor != null && listener != null) {
                executor.execute(listener::onDeviceTapped);
            }
        }
    };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ITapToShareService serviceInterface = ITapToShareService.Stub.asInterface(service);
            try {
                serviceInterface.registerCallback(mCallback);

                boolean sessionActive;
                synchronized (mLock) {
                    sessionActive = mSessionListener != null;
                    if (sessionActive) {
                        mService = serviceInterface;
                    }
                }

                // Unregister the callback if the session was ended while we were connecting.
                // This handles the race condition where endSession() is called after bindService()
                // but before onServiceConnected() completes.
                if (!sessionActive) {
                    try {
                        serviceInterface.unregisterCallback(mCallback);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to unregister stale callback", e);
                    }
                }
            } catch (RemoteException e) {
                reportConnectionFailed(e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                mService = null;
            }
            reportConnectionFailed(new RuntimeException("Service disconnected."));
        }
    };

    /**
     * Constructs a new TapToShareClient.
     *
     * @param context The {@link Context} to use for binding to the service.
     */
    public TapToShareClient(@NonNull Context context) {
        mContext = context;
    }

    /**
     * Binds to the {@link TapToShareService}, starting a tap-to-share session and registering a
     * listener for session events.
     *
     * <p>Calling this method triggers {@link TapToShareService#onSessionStart()} on the service
     * side.
     *
     * <p>If a session is already in progress when this method is called, an
     * {@link IllegalStateException} will be thrown. The client must call {@link #endSession()} to
     * terminate the existing session before starting a new one.
     *
     * <p>Any failures in binding to the service or during the session will be reported
     * asynchronously through {@link SessionListener#onConnectionFailed(Exception)} on the provided
     * {@link Executor}.
     *
     * @param serviceComponent The component name of the {@link TapToShareService} to bind to.
     * @param referrer The referrer URI of the app that initiated the share.
     * @param executor The executor on which the listener callbacks will be invoked.
     * @param listener The listener to be notified of session events.
     * @throws IllegalStateException if a session is already in progress.
     */
    public void startSession(
            @NonNull ComponentName serviceComponent,
            @Nullable Uri referrer,
            @NonNull Executor executor,
            @NonNull SessionListener listener) {
        synchronized (mLock) {
            if (mSessionListener != null) {
                throw new IllegalStateException("A session is already in progress. "
                        + "Call endSession() before starting a new one.");
            }
            mExecutor = executor;
            mSessionListener = listener;
        }

        Intent intent = new Intent(TapToShareService.TAP_TO_SHARE_SERVICE_INTERFACE);
        intent.setComponent(serviceComponent);
        intent.putExtra(Intent.EXTRA_REFERRER, referrer);
        try {
            if (!mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE)) {
                reportConnectionFailed(new RuntimeException("Failed to bind to the service."));
            }
        } catch (SecurityException e) {
            reportConnectionFailed(e);
        }
    }

    /**
     * Unregisters the listener and unbinds from the {@link TapToShareService}, ending the
     * tap-to-share session.
     *
     * <p>Calling this method triggers {@link TapToShareService#onSessionEnd()} on the service
     * side.
     * <p>After this method is called, the {@link SessionListener} provided to
     * {@link #startSession} will no longer receive callbacks.
     */
    public void endSession() {
        ITapToShareService serviceToUnregister;
        synchronized (mLock) {
            serviceToUnregister = mService;
            mService = null;
            mSessionListener = null;
            mExecutor = null;
        }
        if (serviceToUnregister != null) {
            try {
                serviceToUnregister.unregisterCallback(mCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to unregister callback", e);
            }
        }
        try {
            mContext.unbindService(mConnection);
        } catch (IllegalArgumentException e) {
            // Ignore the exception if the service is not bound.
        }
    }

    private void reportConnectionFailed(@NonNull Exception e) {
        Executor executor;
        SessionListener listener;
        synchronized (mLock) {
            executor = mExecutor;
            listener = mSessionListener;
        }
        if (executor != null && listener != null) {
            executor.execute(() -> listener.onConnectionFailed(e));
        }
    }
}
