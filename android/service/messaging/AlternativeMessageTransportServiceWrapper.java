/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.service.messaging;

import static android.service.messaging.AlternativeMessageTransportService.UPGRADE_STATUS_REJECTED;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Provides basic structure for platform to connect to the
 * {@link AlternativeMessageTransportService}.
 *
 * <p>
 * Example:
 * <code>
 * AlternativeMessageTransportServiceWrapper serviceWrapper =
 *     new AlternativeMessageTransportServiceWrapper();
 * if (serviceWrapper.bindToService(context, smsAppPackageName)) {
 *   // wait for onServiceReady callback
 * } else {
 *   // Unable to bind: handle error.
 * }
 * </code>
 * <p> Upon completion {@link #close} should be called to unbind the AMTS service.
 * @hide
 */
// TODO(b/474304887): Make this class thread-safe
public final class AlternativeMessageTransportServiceWrapper implements AutoCloseable {
    private static final String TAG = "AMTSWrapper";
    private static final int SERVICE_BIND_TIMEOUT = 10; // Seconds

    private final Context mContext;
    private final ScheduledExecutorService mScheduler;
    private final Object mServiceTimeoutLock = new Object();
    // Populated by bindToService. bindToService must complete
    // prior to calling close so that mServiceConnection is initialized.
    private volatile MessageUpgradeServiceConnection
            mServiceConnection;

    private volatile IAlternativeMessageTransportService mAlternativeMessageTransportService;
    private Runnable mOnServiceReadyCallback;
    private Executor mOnServiceReadyCallbackExecutor;
    @GuardedBy("mServiceTimeoutLock")
    @Nullable
    private ScheduledFuture<?> mServiceCloseFuture;
    /** @hide */
    public AlternativeMessageTransportServiceWrapper(
            Context context, ScheduledExecutorService scheduler) {
        mContext = Objects.requireNonNull(context);
        mScheduler = scheduler;
    }

    /**
     * Binds to the {@link AlternativeMessageTransportService} under package
     * {@code smsAppPackageName}. This method should be called exactly once.
     *
     * @param defaultSmsPackage the default SMS app's package name
     * @param executor the executor to run the callback.
     * @param onServiceReadyCallback the callback when service becomes ready.
     * @return true upon successfully binding to a message upgrade service, false otherwise
     * @hide
     */
    private boolean bindToService(
            String defaultSmsPackage,
            @CallbackExecutor Executor executor,
            Runnable onServiceReadyCallback) {
        Intent intent = new Intent(AlternativeMessageTransportService.SERVICE_INTERFACE);
        intent.setPackage(defaultSmsPackage);
        mServiceConnection = new MessageUpgradeServiceConnection();
        mOnServiceReadyCallback = onServiceReadyCallback;
        mOnServiceReadyCallbackExecutor = executor;
        boolean bindResult;
        final long identity = Binder.clearCallingIdentity();
        try {
            bindResult = mContext.bindServiceAsUser(intent, mServiceConnection,
                    Context.BIND_AUTO_CREATE, mContext.getUser());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return bindResult;
    }

    /**
     * Unbinds the {@link AlternativeMessageTransportService}. This method should be called exactly
     * once.
     *
     * @hide
     */
    private void disconnect() {
        if (mServiceConnection != null) {
            mContext.unbindService(mServiceConnection);
            mServiceConnection = null;
        }
        mAlternativeMessageTransportService = null;
        mOnServiceReadyCallback = null;
        mOnServiceReadyCallbackExecutor = null;
    }

    /**
     * Upgrades SMS/MMS message to the default SMS app supported transport.
     *
     * @param messageUri the URI of the SMS/MMS message.
     * @param defaultSmsPackage the default SMS app's package name.
     * @param clientCallbackExecutor the executor to run the callback on.
     * @param clientCallback the callback to notify about the upgrade status.
     *
     * @hide
     */
    public void upgradeMessage(
            @NonNull Uri messageUri,
            @NonNull String defaultSmsPackage,
            @NonNull @CallbackExecutor Executor clientCallbackExecutor,
            @NonNull Consumer<Integer> clientCallback) {
        Objects.requireNonNull(messageUri, "messageUri cannot be null");
        Objects.requireNonNull(clientCallbackExecutor, "clientCallbackExecutor cannot be null");
        Objects.requireNonNull(clientCallback, "clientCallback cannot be null");
        if (TextUtils.isEmpty(defaultSmsPackage)) {
            throw new IllegalArgumentException("defaultSmsPackage cannot be null or empty");
        }

        if (bindToService(defaultSmsPackage, Runnable::run,
                () -> upgradeMessageInternal(messageUri, clientCallbackExecutor, clientCallback))) {
            Log.d(TAG, "bindService() to the message upgrade service: " + defaultSmsPackage
                    + " succeeded.");
            scheduleServiceClose();
        } else {
            Log.e(TAG, "bindService() to the message upgrade service: "
                    + defaultSmsPackage + " failed.");
            clientCallbackExecutor.execute(() -> clientCallback.accept(UPGRADE_STATUS_REJECTED));
        }
    }

    private void upgradeMessageInternal(
            Uri messageUri, Executor clientCallbackExecutor,
            Consumer<Integer> clientCallback) {
        Objects.requireNonNull(mAlternativeMessageTransportService);
        try {
            mAlternativeMessageTransportService.upgradeMessage(
                    messageUri,
                    new MessageUpgradeCallbackInternal(clientCallbackExecutor, clientCallback));
        } catch (RemoteException e) {
            Log.e(TAG, "Error while upgrading message", e);
            clientCallbackExecutor.execute(() -> clientCallback.accept(UPGRADE_STATUS_REJECTED));
        }
    }

    /** @hide */
    @Override
    public void close() {
        disconnect();
    }

    private void onServiceReady(
            IAlternativeMessageTransportService alternativeMessageTransportService) {
        mAlternativeMessageTransportService = alternativeMessageTransportService;
        if (mOnServiceReadyCallback != null && mOnServiceReadyCallbackExecutor != null) {
            Binder.withCleanCallingIdentity(() -> {
                mOnServiceReadyCallbackExecutor.execute(mOnServiceReadyCallback);
            });
        }
    }

    private void scheduleServiceClose() {
        synchronized (mServiceTimeoutLock) {
            if (mServiceCloseFuture != null) {
                Log.w(TAG, "Cancelling previous hard service close timer.");
                mServiceCloseFuture.cancel(false);
            }

            mServiceCloseFuture = mScheduler.schedule(
                    this::disconnect,
                    SERVICE_BIND_TIMEOUT,
                    TimeUnit.SECONDS);
            Log.d(TAG, "Scheduled hard service close in " + SERVICE_BIND_TIMEOUT + "s.");
        }
    }

    /**
     * A basic {@link ServiceConnection}.
     */
    private final class MessageUpgradeServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            onServiceReady(IAlternativeMessageTransportService.Stub.asInterface(service));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mAlternativeMessageTransportService = null;
        }
    }

    private static final class MessageUpgradeCallbackInternal
            extends IMessageUpgradeCallback.Stub {
        private final Executor mClientCallbackExecutor;
        private final Consumer<Integer> mClientCallback;

        private MessageUpgradeCallbackInternal(
                Executor executor, Consumer<Integer> callback) {
            mClientCallbackExecutor = executor;
            mClientCallback = callback;
        }

        @Override
        public void onUpgradeStatusAvailable(int status) throws RemoteException {
            Binder.withCleanCallingIdentity(
                    () -> mClientCallbackExecutor.execute(() -> mClientCallback.accept(status)));
        }
    }
}
