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

package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Interface for {@link AnomalyProfilingManager} to allow for mocking in tests.
 *
 * @hide
 */
public interface AnomalyProfilingClient {
    /**
     * Register a callback to receive profiling results.
     *
     * <p>Note: This callback will receive all anomaly callbacks, even if they are from another
     * instance of this class.
     *
     * <p>Note: The callback registered here will replace any callback previously registered by
     * calling this method.
     */
    void registerCallback(@NonNull Consumer<AnomalyRequestResult> callback);

    /** Check whether a trigger is registered to specific process. */
    boolean isTriggerRegistered(int uid, @NonNull String packageName, int triggerType);

    /**
     * Send a system anomaly to the specified process.
     *
     * <p>The file must already be placed in Profiling's temporary directory
     * (/data/misc/perfetto-traces/profiling). {@link ProfilingService} will handle moving it to the
     * app's directory and then sending the result to the app.
     *
     * @param uid The uid of the process to send the result to.
     * @param packageName The package name of the process to send the result to.
     * @param triggerType The trigger type of this profile. Must be an Anomaly trigger type.
     * @param resultFileName The file name of the file to send. Include the name only, not the path.
     * @param tag An optional tag to include in the result sent to the app.
     * @return A key which can be used to associate a callback back to its request.
     */
    UUID sendAnomalyProfile(
            int uid,
            @NonNull String packageName,
            @AnomalyProfilingManager.AnomalyTriggerType int triggerType,
            @Nullable String tag,
            @NonNull String resultFileName);

    /**
     * Collect profiling for an anomaly, but return it to the requester and not to the process it
     * relates to.
     *
     * @param uid The uid of the process to collect the profile of.
     * @param packageName The package name of the process to collect the profile of.
     * @param profilingType The type of profiling which should be collected.
     * @param triggerType The trigger type of this profile. Must be an Anomaly trigger type.
     * @param tag An optional tag to include in the result sent to the app.
     * @param params An optional collection of parameters to apply to the profile configuration.
     * @return A key which can be used to associate a callback back to its request, as well as to
     *     stop the ongoing profiling.
     */
    UUID collectAnomalyProfile(
            int uid,
            @NonNull String packageName,
            @AnomalyProfilingManager.AnomalyProfilingType int profilingType,
            @AnomalyProfilingManager.AnomalyTriggerType int triggerType,
            @Nullable String tag,
            @Nullable Bundle params);

    /**
     * Collect profiling for an anomaly and send it to the relevant process.
     *
     * @param uid The uid of the process to collect the profile of and send the result to.
     * @param packageName The package name of the process to collect the profile of and send the
     *     result to.
     * @param profilingType The type of profiling which should be collected.
     * @param triggerType The trigger type of this profile. Must be an Anomaly trigger type.
     * @param tag An optional tag to include in the result sent to the app.
     * @param params An optional collection of parameters to apply to the profile configuration.
     * @return A key which can be used to associate a callback back to its request, as well as to
     *     stop the ongoing profiling.
     */
    UUID collectAndSendAnomalyProfile(
            int uid,
            @NonNull String packageName,
            @AnomalyProfilingManager.AnomalyProfilingType int profilingType,
            @AnomalyProfilingManager.AnomalyTriggerType int triggerType,
            @Nullable String tag,
            @Nullable Bundle params);

    /**
     * Stop an active profiling session.
     *
     * <p>Processing of the session will continue following the sessions original request, meaning
     * if a valid result is obtained it will be sent to either the app or anomaly detector as
     * defined by its original request.
     *
     * @param key Provided as a return type in each method that allows the request of profiling.
     */
    void stopProfiling(UUID key);
}
