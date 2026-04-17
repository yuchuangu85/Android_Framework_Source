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

package android.app.privatecompute;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.Hide;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemService;
import android.content.Context;
import android.os.OutcomeReceiver;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.sysprop.PccProperties;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.Executor;

/** Manager for interacting with the Private Compute Core (PCC) sandbox. */
@FlaggedApi(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
@SystemService(Context.PCC_SANDBOX_SERVICE)
public final class PccSandboxManager {

    private static final int DEFAULT_MAX_BATCH_SIZE = 100;
    private static final int DEFAULT_FLUSH_AGE_MS = 10000;
    // 1000000 nanoseconds in a millisecond.
    private static final long MS_TO_NS = 1000000L;
    // Audit logs have a system_server-provided timestamp. When batching is enabled, logs also have
    // a client-provided timestamp. This allows for ordering of logs when batching is enabled.
    private static final String CLIENT_PROVIDED_TIMESTAMP_KEY = "client_timestamp";

    private final IPccSandboxManager mService;
    private final Context mContext;
    private final Injector mInjector;
    private final Object mLock = new Object();
    // Lazily initialized.
    private @Nullable List<PersistableBundle> mAuditLogBatch;

    /**
     * Creates an instance.
     *
     * @param service An interface to the backing service.
     * @param context A {@link Context}.
     * @hide
     */
    public PccSandboxManager(IPccSandboxManager service, Context context) {
        mService = service;
        mContext = context;
        mInjector = new Injector();
    }

    /**
     * Creates an instance with an injector.
     *
     * @param service An interface to the backing service.
     * @param context A {@link Context}.
     * @param injector An injector for testing.
     * @hide
     */
    @Hide
    @VisibleForTesting
    public PccSandboxManager(IPccSandboxManager service, Context context, Injector injector) {
        mService = service;
        mContext = context;
        mInjector = injector;
    }

    /**
     * Injector for testing.
     *
     * @hide
     */
    @Hide
    @VisibleForTesting
    public static class Injector {
        public long clientTimestampNanos() {
            return SystemClock.elapsedRealtimeNanos();
        }

        public boolean auditModeBatchingEnabled() {
            return PccProperties.audit_mode_batching_enabled().orElse(false);
        }

        public int auditModeMaxBatchSize() {
            return PccProperties.audit_mode_max_batch_size().orElse(DEFAULT_MAX_BATCH_SIZE);
        }

        public long auditModeFlushAgeNanos() {
            return PccProperties.audit_mode_flush_time_ms().orElse(DEFAULT_FLUSH_AGE_MS) * MS_TO_NS;
        }
    }

    /**
     * Returns whether the given UID belongs to a Private Compute Services (PCS) package. These are
     * packages that hold the {@link android.Manifest.permission#PROVIDE_PRIVATE_COMPUTE_SERVICES}.
     *
     * @param uid The UID to check.
     * @return {@code true} if the UID belongs to a PCS package, {@code false} otherwise.
     */
    @SuppressLint("RequiresPermission") // Method call doesn't require permission.
    public boolean isPrivateComputeServicesUid(int uid) {
        try {
            return mService.isPrivateComputeServicesUid(uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the given package is considered a "Trusted System Component" by the
     * framework. This also includes Private Compute Services apps, which are an extension to the
     * framework's trust boundary. Trusted System components are allowed two-way communication with
     * the PCC components.
     *
     * @param uid The UID of the application.
     * @param packageName The package name of the application. This can be null when a single
     *     packagename isn't available, e.g. for SYSTEM_UID. If non-null, this API checks whether
     *     {@code uid} corresponds to {@code packageName}, and returns {@code false} if it doesn't.
     * @return {@code true} if the app is a trusted system component, {@code false} otherwise.
     */
    @RequiresNoPermission
    @FlaggedApi(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public boolean isPccTrustedSystemComponent(int uid, @Nullable String packageName) {
        try {
            return mService.isPccTrustedSystemComponent(uid, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Writes data to the audit log, if audit mode is enabled. Otherwise, does nothing.
     *
     * <p>Nested Bundles are supported up to a depth of 100.
     *
     * @param data The data to write to the audit log.
     */
    @RequiresNoPermission // Not gating sensitive functionality, and audit log is size-capped.
    public void writeToAuditLog(@NonNull PersistableBundle data) {
        if (mInjector.auditModeBatchingEnabled()) {
            addAuditEntryToBatchAndFlushIfNeeded(data);
            return;
        }
        try {
            mService.writeToAuditLog(data, mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests the framework to start the non-PCC migration service of the calling application.
     *
     * <p>This is intended for PCC components to trigger a process outside the PCC sandbox to
     * perform tasks like data migration. The system will look for a service extending {@link
     * DataMigrationToPccService} in the application's manifest that is <b>not</b> marked as a PCC
     * component. If found, the non-PCC process is started and the service is invoked.
     *
     * <p>If the non-PCC process is already running, this ensures the migration service is
     * triggered. System unbinds from the service either when the service indicates it has
     * accepted/rejected the request, or failing that, after a timeout of {@link
     * DataMigrationToPccService#MIGRATION_TIMEOUT_MS}.
     *
     * @param executor The executor on which the callback will be invoked.
     * @param callback The callback to receive the result of the migration request.
     */
    @FlaggedApi(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    @SuppressLint("RequiresPermission")
    public void startNonPccProcessForDataMigration(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<MigrationRequestResult, MigrationException> callback) {
        try {
            mService.startNonPccProcessForDataMigration(
                    new IMigrationRequestResultReceiver.Stub() {
                        @Override
                        public void onResult(MigrationRequestResult result) {
                            executor.execute(() -> callback.onResult(result));
                        }

                        @Override
                        public void onError(int errorCode, String errorMessage) {
                            executor.execute(
                                    () ->
                                            callback.onError(
                                                    new MigrationException(
                                                            errorCode, errorMessage)));
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Adds the data to the local buffer, and flushes the buffer to system_server if necessary.
     * Returns true if the data was flushed. Thread-safe.
     */
    @VisibleForTesting
    boolean addAuditEntryToBatchAndFlushIfNeeded(@NonNull PersistableBundle data) {
        long time = mInjector.clientTimestampNanos();
        data.putLong(CLIENT_PROVIDED_TIMESTAMP_KEY, time);
        ArrayList<PersistableBundle> batchToSend = null;
        synchronized (mLock) {
            if (mAuditLogBatch == null) {
                mAuditLogBatch = new ArrayList<>(mInjector.auditModeMaxBatchSize());
            }
            mAuditLogBatch.add(data);

            boolean shouldFlush = false;
            if (mAuditLogBatch.size() >= mInjector.auditModeMaxBatchSize()) {
                shouldFlush = true;
            } else {
                // items are added in sequence, the first timestamp is the oldest.
                long firstTimestamp = mAuditLogBatch.get(0).getLong(CLIENT_PROVIDED_TIMESTAMP_KEY);
                if (time - firstTimestamp > mInjector.auditModeFlushAgeNanos()) {
                    shouldFlush = true;
                }
            }

            if (shouldFlush) {
                batchToSend = new ArrayList<>(mAuditLogBatch);
                mAuditLogBatch.clear();
            }
        }

        if (batchToSend != null) {
            try {
                mService.batchWriteToAuditLog(batchToSend, mContext.getPackageName());
                return true;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }
}
