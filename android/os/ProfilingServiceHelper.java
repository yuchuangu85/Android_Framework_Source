/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.os;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.SystemApi;
import android.annotation.SystemApi.Client;
import android.app.ApplicationErrorReport;
import android.os.profiling.Flags;
import android.provider.DeviceConfig;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Class for system to interact with {@link ProfilingService} to notify of trigger occurrences.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
@SystemApi(client = Client.MODULE_LIBRARIES)
public class ProfilingServiceHelper {
    private static final String TAG = ProfilingServiceHelper.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final String CONFIG_NAMESPACE = "profiling";

    // LINT.IfChange(oom_device_configs)
    private static final String CONFIG_TIMEOUT_OOM = "trigger_timeout_oom";

    private static final int TIMEOUT_DEFAULT_JAVA_HEAP_DUMP_SECONDS = 5;
    // LINT.ThenChange(/tests/cts/src/android/profiling/cts/ProfilingFrameworkTests.java:oom_device_configs)

    private static final Object sLock = new Object();

    @Nullable
    @GuardedBy("sLock")
    private static ProfilingServiceHelper sInstance;

    private final Object mLock = new Object();

    @NonNull
    @GuardedBy("mLock")
    private final IProfilingService mProfilingService;

    private ProfilingServiceHelper(@NonNull IProfilingService service) {
        mProfilingService = service;
    }

    /**
     * Returns an instance of {@link ProfilingServiceHelper}.
     *
     * @throws IllegalStateException if called before ProfilingService is set up.
     */
    @NonNull
    public static ProfilingServiceHelper getInstance() {
        synchronized (sLock) {
            if (sInstance != null) {
                return sInstance;
            }

            IProfilingService service =
                    Flags.telemetryApis()
                            ? IProfilingService.Stub.asInterface(
                                    ProfilingFrameworkInitializer.getProfilingServiceManager()
                                            .getProfilingServiceRegisterer()
                                            .get())
                            : null;

            if (service == null) {
                throw new IllegalStateException("ProfilingService not yet set up.");
            }

            sInstance = new ProfilingServiceHelper(service);

            return sInstance;
        }
    }

    /** Send a trigger to {@link ProfilingService}. */
    public void onProfilingTriggerOccurred(int uid, @NonNull String packageName, int triggerType) {
        synchronized (mLock) {
            try {
                mProfilingService.processTrigger(uid, packageName, triggerType, null, null);
            } catch (RemoteException e) {
                // Exception sending trigger to service. Nothing to do here, trigger will be lost.
                if (DEBUG) Log.e(TAG, "Exception sending trigger", e);
            }
        }
    }

    /**
     * Stops all active profiling sessions for the given uid, package name and trigger type in
     * {@link ProfilingService}.
     *
     * @param uid The UID of the process that is being profiled.
     * @param packageName The package name of the process that is being profiled.
     * @param triggerType The trigger type of the profiling session to stop.
     */
    @FlaggedApi(Flags.FLAG_PROFILING_TRIGGER_COLD_START)
    public void stopActiveProfiling(int uid, @NonNull String packageName, int triggerType) {
        synchronized (mLock) {
            try {
                mProfilingService.stopActiveProfiling(uid, packageName, triggerType);
            } catch (RemoteException e) {
                if (DEBUG) Log.e(TAG, "Exception sending stop profiling request", e);
            }
        }
    }

    /**
     * Handle profiling for an application crash. This is done by determining whether this is a
     * crash type which profiling is collected for, mapping it to the appropriate trigger, and then
     * notifying {@link ProfilingService} of the trigger. Profiling will occur asynchronously.
     *
     * @param uid The UID of the process that is crashing.
     * @param packageName The package name of the process that is crashing.
     * @param crashInfo Description of the crash.
     * @param executor The executor on which to execute the onComplete runnable provided below.
     * @param onComplete Will run when profiling is complete, whether successful or not. Run
     *     immediately if no profiling will occur.
     * @return The recommended blocking timeout for profiling of the required type to complete. This
     *     timeout is an estimate for how long profiling will take and has no influence on the
     *     actual profiling collection. May be 0 indicating that no profiling will be collected, in
     *     which case blocking is not necessary and the provided runnable will be queued to the
     *     executor before the method completes.
     */
    @FlaggedApi(Flags.FLAG_PROFILING_TRIGGER_OOM)
    @NonNull
    public Duration profileApplicationCrash(
            int uid,
            @NonNull String packageName,
            @NonNull ApplicationErrorReport.CrashInfo crashInfo,
            @NonNull Executor executor,
            @NonNull Runnable onComplete) {
        int triggerType;
        int delay;

        if ("java.lang.OutOfMemoryError".equals(crashInfo.exceptionClassName)) {
            // For OOM type crashes, set trigger type appropriately and delay to 5 seconds, which
            triggerType = ProfilingTrigger.TRIGGER_TYPE_OOM;
            delay =
                    DeviceConfig.getInt(
                            CONFIG_NAMESPACE,
                            CONFIG_TIMEOUT_OOM,
                            TIMEOUT_DEFAULT_JAVA_HEAP_DUMP_SECONDS);
        } else {
            // If the error does not map to a type that we collect profiling for, immediately run
            // the provided runnable and return 0 to ensure that nothing is being blocked.
            executor.execute(() -> onComplete.run());
            return Duration.ZERO;
        }

        synchronized (mLock) {
            try {
                mProfilingService.processTrigger(
                        uid,
                        packageName,
                        triggerType,
                        null,
                        new IProfilingTriggerCallback.Stub() {
                            @Override
                            @RequiresNoPermission
                            public void onComplete() {
                                if (DEBUG) {
                                    Log.d(TAG, "Trigger onComplete received, counting down.");
                                }
                                executor.execute(() -> onComplete.run());
                            }
                        });
            } catch (RemoteException e) {
                // Exception sending trigger to service. Nothing to do here, trigger will be lost.
                if (DEBUG) Log.e(TAG, "Exception sending trigger", e);
                executor.execute(() -> onComplete.run());
            }
        }

        return Duration.ofSeconds(delay);
    }
}
