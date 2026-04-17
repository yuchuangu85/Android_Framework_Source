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

import static android.os.ProfilingTrigger.TriggerType;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.profiling.Flags;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class allows the caller to:
 *
 * <ul>
 *   <li>Request profiling and listen for results. Profiling types supported are: system traces,
 *       java heap dumps, heap profiles, and stack traces.
 *   <li>Register triggers for the system to capture profiling on the apps behalf.
 * </ul>
 *
 * <p>The {@link #requestProfiling} API can be used to begin profiling. Profiling may be ended
 * manually using the CancellationSignal provided in the request, or as a result of a timeout. The
 * timeout may be either the system default or caller defined in the parameter bundle for select
 * types.
 *
 * <p>The profiling results are delivered to the requesting app's data directory and a pointer to
 * the file will be received using the app provided listeners.
 *
 * <p>Apps can provide listeners in one or both of two ways:
 *
 * <ul>
 *   <li>A request-specific listener included with the request. This will trigger only with a result
 *       from the request it was provided with.
 *   <li>A global listener provided by {@link #registerForAllProfilingResults}. This will be
 *       triggered for all results belonging to your app. This listener is the only way to receive
 *       results from system triggered profiling instances set up with {@link
 *       #addProfilingTriggers}.
 * </ul>
 *
 * <p>Requests are rate limited and not guaranteed to be filled. Rate limiting can be disabled for
 * local testing of {@link #requestProfiling} using the shell command {@code device_config put
 * profiling_testing rate_limiter.disabled true}
 *
 * <p>For local testing, profiling results can be accessed more easily by enabling debug mode. This
 * will retain output files in a temporary system directory. The locations of the retained files
 * will be available in logcat. The behavior and command varies by version:
 *
 * <ul>
 *   <li>For Android versions 16 and above, debug mode will retain both unredacted (where
 *       applicable) and redacted results in the temporary directory. It can be enabled with the
 *       shell command {@code device_config put profiling_testing delete_temporary_results.disabled
 *       true} and disabled by setting that same value back to false. Retained results are
 *       accessible on all build types.
 *   <li>For Android version 15, debug mode will retain only the unredacted result (where
 *       applicable) in the temporary directory. It can be enabled with the shell command {@code
 *       device_config put profiling_testing delete_unredacted_trace.disabled true} and disabled by
 *       setting that same value back to false. The retained unredacted file can only be accessed on
 *       builds with root access. To access the redacted output file on an unrooted device, apps can
 *       copy the file from {@code /pkg/files/profiling/file.type} to {@code /pkg/cache/file.type}.
 * </ul>
 *
 * <p>In order to test profiling triggers, enable testing mode for your app with the shell command
 * {@code device_config put profiling_testing system_triggered_profiling.testing_package_name
 * com.your.app} which will:
 *
 * <ul>
 *   <li>Ensure that a background trace is running.
 *   <li>Allow all triggers for the provided package name to pass the system level rate limiter.
 *       This mode will continue until manually stopped with the shell command {@code device_config
 *       delete profiling_testing system_triggered_profiling.testing_package_name}.
 * </ul>
 *
 * <p>Results are redacted and contain specific information about the requesting process only.
 *
 * <p class="note">Note: Check out <a href=
 * "https://developer.android.com/topic/performance/tracing/profiling-manager/overview">Capture Real
 * User Data Using ProfilingManager</a> for a detailed guide on using this API, covering capturing
 * Profiling all they way through interpreting the results.
 */
@FlaggedApi(Flags.FLAG_TELEMETRY_APIS)
public final class ProfilingManager {
    private static final String TAG = ProfilingManager.class.getSimpleName();
    private static final boolean DEBUG = false;

    /** Cleanup old files 5 days after delivery. */
    private static final long OLD_FILE_CLEANUP_AFTER_TIME_MS = 5 * 24 * 60 * 60 * 1_000;

    /**
     * Perform the cleanup at most once per day. This is tied to object lifecycle and not persisted,
     * meaning if a new instance is instantiated there will be another cleanup.
     */
    private static final long CLEANUP_PERIOD_MS = 24 * 60 * 60 * 1000;

    /** Profiling type for {@link #requestProfiling} to request a java heap dump. */
    public static final int PROFILING_TYPE_JAVA_HEAP_DUMP = 1;

    /** Profiling type for {@link #requestProfiling} to request a heap profile. */
    public static final int PROFILING_TYPE_HEAP_PROFILE = 2;

    /** Profiling type for {@link #requestProfiling} to request a stack sample. */
    public static final int PROFILING_TYPE_STACK_SAMPLING = 3;

    /** Profiling type for {@link #requestProfiling} to request a system trace. */
    public static final int PROFILING_TYPE_SYSTEM_TRACE = 4;

    /* Values 100-199 are reserved for {@link AnomalyProfilingManager}, do not use. */

    /* Begin public API defined keys. */
    /* End public API defined keys. */

    /* Begin not-public API defined keys/values. */
    /**
     * Can be used with profiling type {@link #PROFILING_TYPE_HEAP_PROFILE}, {@link
     * #PROFILING_TYPE_STACK_SAMPLING}, or {@link #PROFILING_TYPE_SYSTEM_TRACE}. Value of type int.
     *
     * @hide
     */
    public static final String KEY_DURATION_MS = "KEY_DURATION_MS";

    /**
     * Can only be used with profiling type {@link #PROFILING_TYPE_HEAP_PROFILE}. Value of type
     * long.
     *
     * @hide
     */
    public static final String KEY_SAMPLING_INTERVAL_BYTES = "KEY_SAMPLING_INTERVAL_BYTES";

    /**
     * Can only be used with profiling type {@link #PROFILING_TYPE_HEAP_PROFILE}. Value of type
     * boolean.
     *
     * @hide
     */
    public static final String KEY_TRACK_JAVA_ALLOCATIONS = "KEY_TRACK_JAVA_ALLOCATIONS";

    /**
     * Can be used with profiling types {@link #PROFILING_TYPE_STACK_SAMPLING}, or {@link
     * #PROFILING_TYPE_SYSTEM_TRACE} if {@link #KEY_COLLECT_STACK_SAMPLING} is set to true. Value of
     * type int.
     *
     * @hide
     */
    public static final String KEY_FREQUENCY_HZ = "KEY_FREQUENCY_HZ";

    /**
     * Can be used with all profiling types. Value of type int.
     *
     * @hide
     */
    public static final String KEY_SIZE_KB = "KEY_SIZE_KB";

    /**
     * Can be used with profiling types {@link #PROFILING_TYPE_STACK_SAMPLING} or {@link
     * #PROFILING_TYPE_SYSTEM_TRACE}.
     *
     * <p>Value of type int must be one of: {@link VALUE_BUFFER_FILL_POLICY_DISCARD} {@link
     * VALUE_BUFFER_FILL_POLICY_RING_BUFFER}
     *
     * @hide
     */
    public static final String KEY_BUFFER_FILL_POLICY = "KEY_BUFFER_FILL_POLICY";

    /** @hide */
    public static final int VALUE_BUFFER_FILL_POLICY_DISCARD = 1;

    /** @hide */
    public static final int VALUE_BUFFER_FILL_POLICY_RING_BUFFER = 2;

    /**
     * Can be used with profiling types {@link #PROFILING_TYPE_STACK_SAMPLING}, or {@link
     * #PROFILING_TYPE_SYSTEM_TRACE} if {@link #KEY_COLLECT_STACK_SAMPLING} is set to true. Value of
     * type boolean.
     *
     * @hide
     */
    public static final String KEY_SAMPLE_BINDER_ONLY = "KEY_SAMPLE_BINDER_ONLY";

    /**
     * Can only be used with profiling type {@link #PROFILING_TYPE_SYSTEM_TRACE}. Value of type
     * boolean.
     *
     * @hide
     */
    public static final String KEY_COLLECT_STACK_SAMPLING = "KEY_COLLECT_STACK_SAMPLING";

    /**
     * Relative path from app files dir to location of profiling output files.
     *
     * @hide
     */
    public static final String OUTPUT_FILE_RELATIVE_PATH = "/profiling/";

    /* End not-public API defined keys/values. */

    /**
     * @hide *
     */
    @IntDef(
            prefix = {"PROFILING_TYPE_"},
            value = {
                PROFILING_TYPE_JAVA_HEAP_DUMP,
                PROFILING_TYPE_HEAP_PROFILE,
                PROFILING_TYPE_STACK_SAMPLING,
                PROFILING_TYPE_SYSTEM_TRACE,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProfilingType {}

    private final Object mLock = new Object();
    private final Context mContext;

    /** @hide **/
    @VisibleForTesting
    @GuardedBy("mLock")
    public long mLastCleanupMs = 0L;

    /** @hide */
    @VisibleForTesting
    @GuardedBy("mLock")
    public final ArrayList<ProfilingRequestCallbackWrapper> mCallbacks = new ArrayList<>();

    /** @hide */
    @VisibleForTesting
    @GuardedBy("mLock")
    public IProfilingService mProfilingService;

    /**
     * Constructor for ProfilingManager.
     *
     * @hide
     */
    public ProfilingManager(Context context) {
        mContext = context;
    }

    /**
     * Request system profiling.
     *
     * <p class="note">Note: use of this API directly is not recommended for most use cases.
     * Consider using the <a
     * href="https://developer.android.com/reference/androidx/core/os/Profiling">higher level
     * wrappers provided by AndroidX</a> that will construct the request correctly, supporting
     * available options with simplified request parameters.
     *
     * <p>Both a listener and an executor must be set at the time of the request for the request to
     * be considered for fulfillment. Listener/executor pairs can be set in this method, with {@link
     * #registerForAllProfilingResults}, or both. The listener and executor must be set together, in
     * the same call. If no listener and executor combination is set, the request will be discarded
     * and no callback will be received.
     *
     * <p>Requests will be rate limited and are not guaranteed to be filled.
     *
     * <p>There might be a delay before profiling begins. For continuous profiling types (system
     * tracing, stack sampling, and heap profiling), we recommend starting the collection early and
     * stopping it with {@code cancellationSignal} immediately after the area of interest to ensure
     * that the section you want profiled is captured. For heap dumps, we recommend testing locally
     * to ensure that the heap dump is collected at the proper time.
     *
     * <p>The provided executor may also be used to perform a cleanup of old delivered profiles, if
     * necessary.
     *
     * @param profilingType Type of profiling to collect.
     * @param parameters Bundle of request related parameters. If the bundle contains any
     *     unrecognized parameters, the request will be fail with {@link
     *     android.os.ProfilingResult#ERROR_FAILED_INVALID_REQUEST}. If the values for the
     *     parameters are out of supported range, the closest possible in range value will be
     *     chosen. Use of <a href=
     *     "https://developer.android.com/reference/androidx/core/os/Profiling"> androidx
     *     wrappers</a> is recommended over generating this directly.
     * @param tag Caller defined data to help identify the output. The first 20 alphanumeric
     *     characters, plus dashes, will be lowercased and included in the output filename.
     * @param cancellationSignal for caller requested cancellation. Results will be returned if
     *     available. If this is null, the requesting app will not be able to stop the collection.
     *     The collection will stop after timing out with either the provided configurations or with
     *     system defaults
     * @param executor The executor to call back with. Will only be used for the listener provided
     *     in this method. If this is null, and no global executor and listener combinations are
     *     registered at the time of the request, the request will be dropped.
     * @param listener Listener to be triggered with result. Any global listeners registered via
     *     {@link #registerForAllProfilingResults} will also be triggered. If this is null, and no
     *     global listener and executor combinations are registered at the time of the request, the
     *     request will be dropped.
     */
    public void requestProfiling(
            @ProfilingType int profilingType,
            @Nullable Bundle parameters,
            @Nullable String tag,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable Executor executor,
            @Nullable Consumer<ProfilingResult> listener) {
        synchronized (mLock) {
            try {
                final UUID key = UUID.randomUUID();

                if (executor != null && listener != null) {
                    // Listeners are provided, store them.
                    mCallbacks.add(new ProfilingRequestCallbackWrapper(executor, listener, key));
                } else if (mCallbacks.isEmpty()) {
                    // No listeners have been registered by any path, toss the request.
                    throw new IllegalArgumentException(
                            "No listeners have been registered. Request has been discarded.");
                }
                // If neither case above was hit, app wide listeners were provided. Continue.

                final IProfilingService service = getOrCreateIProfilingServiceLocked(false);
                if (service == null) {
                    executor.execute(
                            () ->
                                    listener.accept(
                                            new ProfilingResult(
                                                    ProfilingResult.ERROR_UNKNOWN,
                                                    null,
                                                    tag,
                                                    "ProfilingService is not available",
                                                    Flags.systemTriggeredProfilingNew()
                                                            ? ProfilingTrigger.TRIGGER_TYPE_NONE
                                                            : 0)));
                    if (DEBUG) Log.d(TAG, "ProfilingService is not available");
                    return;
                }

                String packageName = mContext.getPackageName();
                if (packageName == null) {
                    executor.execute(
                            () ->
                                    listener.accept(
                                            new ProfilingResult(
                                                    ProfilingResult.ERROR_UNKNOWN,
                                                    null,
                                                    tag,
                                                    "Failed to resolve package name",
                                                    Flags.systemTriggeredProfilingNew()
                                                            ? ProfilingTrigger.TRIGGER_TYPE_NONE
                                                            : 0)));
                    if (DEBUG) Log.d(TAG, "Failed to resolve package name.");
                    return;
                }

                // For key, use most and least significant bits so we can create an identical UUID
                // after passing over binder.
                service.requestProfiling(
                        profilingType,
                        parameters,
                        tag,
                        key.getMostSignificantBits(),
                        key.getLeastSignificantBits(),
                        packageName);
                if (cancellationSignal != null) {
                    cancellationSignal.setOnCancelListener(
                            () -> {
                                synchronized (mLock) {
                                    try {
                                        service.requestCancel(
                                                key.getMostSignificantBits(),
                                                key.getLeastSignificantBits());
                                    } catch (RemoteException e) {
                                        // Ignore, request in flight already and we can't stop it.
                                    }
                                }
                            });
                }
            } catch (RemoteException e) {
                if (DEBUG) Log.d(TAG, "Binder exception processing request", e);
                executor.execute(
                        () ->
                                listener.accept(
                                        new ProfilingResult(
                                                ProfilingResult.ERROR_UNKNOWN,
                                                null,
                                                tag,
                                                "Binder exception processing request",
                                                Flags.systemTriggeredProfilingNew()
                                                        ? ProfilingTrigger.TRIGGER_TYPE_NONE
                                                        : 0)));
                throw new RuntimeException("Unable to request profiling.");
            }
        }
    }

    /**
     * Register a listener to be called for all profiling results for this uid. Listeners set here
     * will be called in addition to any provided with the request.
     *
     * <p class="note">Note: If a callback attempt fails (for example, because your app is killed
     * while a trace is in progress) re-delivery may be attempted using a listener added via this
     * method.
     *
     * <p>The provided executor may also be used to perform a cleanup of old delivered profiles, if
     * necessary.
     *
     * @param executor The executor to call back with.
     * @param listener Listener to be triggered with result.
     */
    public void registerForAllProfilingResults(
            @NonNull Executor executor, @NonNull Consumer<ProfilingResult> listener) {
        synchronized (mLock) {
            // Only notify {@link mProfilingService} of a general listener being added if it already
            // exists as registering it also handles the notifying.
            boolean shouldNotifyService = mProfilingService != null;

            if (getOrCreateIProfilingServiceLocked(true) == null) {
                // If the binder object was not successfully registered then this listener will
                // not ever be triggered.
                executor.execute(
                        () ->
                                listener.accept(
                                        new ProfilingResult(
                                                ProfilingResult.ERROR_UNKNOWN,
                                                null,
                                                null,
                                                "Binder exception processing request",
                                                Flags.systemTriggeredProfilingNew()
                                                        ? ProfilingTrigger.TRIGGER_TYPE_NONE
                                                        : 0)));
                return;
            }
            mCallbacks.add(new ProfilingRequestCallbackWrapper(executor, listener, null));

            if (shouldNotifyService) {
                // Notify service that a general listener was added. General listeners are also used
                // for queued callbacks if any are waiting.
                try {
                    mProfilingService.generalListenerAdded();
                } catch (RemoteException e) {
                    // Do nothing. Binder callback is already registered, but service won't know
                    // there is a general listener so queued callbacks won't occur.
                    Log.d(
                            TAG,
                            "Exception notifying service of general callback,"
                                    + " queued callbacks will not occur.",
                            e);
                }
            }
        }

        maybeCleanupOldFiles(executor);
    }

    private void maybeCleanupOldFiles(final Executor executor) {
        if (Flags.oldFilesCleanup()) {
            if (System.currentTimeMillis() > mLastCleanupMs + CLEANUP_PERIOD_MS) {
                mLastCleanupMs = System.currentTimeMillis();

                executor.execute(
                        new Runnable() {
                            @Override
                            public void run() {
                                cleanupOldFiles();
                            }
                        });
            }
        }
    }

    private void cleanupOldFiles() {
        Trace.beginSection("ProfilingManager:maybeCleanupOldFiles");
        try {
            File dir = new File(getAppFileDir() + OUTPUT_FILE_RELATIVE_PATH);
            if (!dir.exists() || !dir.isDirectory()) {
                if (DEBUG) Log.d(TAG, "Directory does not exist, nothing to cleanup.");
                return;
            }

            // Delete files which were last updated more than specified time ago.
            final long deleteOlderThanMs =
                    System.currentTimeMillis() - OLD_FILE_CLEANUP_AFTER_TIME_MS;

            File[] oldFiles =
                    dir.listFiles(
                            new FileFilter() {
                                @Override
                                public boolean accept(File pathname) {
                                    // Include in list if last modified before the range we defined.
                                    return pathname.lastModified() < deleteOlderThanMs;
                                }
                            });

            if (oldFiles == null || oldFiles.length == 0) {
                if (DEBUG) {
                    Log.d(
                            TAG,
                            "No files returned, directory is either empty or all files are newer"
                                    + " than expire time range.");
                }
                return;
            }

            for (int i = 0; i < oldFiles.length; i++) {
                boolean success = oldFiles[i].delete();
                if (DEBUG) {
                    Log.d(
                            TAG,
                            String.format(
                                    "Cleanup old profiling file %s %s.",
                                    oldFiles[i].getName(), (success ? "succeeded" : "failed")));
                }
            }
        } catch (SecurityException e) {
            // Ignore and exit.
            Log.e(TAG, "Failed to cleanup profiling files.", e);
        }
        Trace.endSection();
    }

    /**
     * Unregister a listener that was to be called for all profiling results. If no listener is
     * provided, all listeners for this process that were not submitted with a profiling request
     * will be removed.
     *
     * @param listener Listener to unregister and no longer be triggered with the results. Null to
     *     remove all global listeners for this uid.
     */
    public void unregisterForAllProfilingResults(@Nullable Consumer<ProfilingResult> listener) {
        synchronized (mLock) {
            if (mCallbacks.isEmpty()) {
                // No callbacks, nothing to remove.
                return;
            }

            if (listener == null) {
                // Remove all global listeners.
                ArrayList<ProfilingRequestCallbackWrapper> listenersToRemove = new ArrayList<>();
                for (int i = 0; i < mCallbacks.size(); i++) {
                    ProfilingRequestCallbackWrapper wrapper = mCallbacks.get(i);
                    // Only remove global listeners which are not tied to a specific request. These
                    // can be identified by checking that they do not have an associated key.
                    if (wrapper.mKey == null) {
                        listenersToRemove.add(wrapper);
                    }
                }
                mCallbacks.removeAll(listenersToRemove);
            } else {
                // Remove the provided listener only.
                for (int i = 0; i < mCallbacks.size(); i++) {
                    ProfilingRequestCallbackWrapper wrapper = mCallbacks.get(i);
                    if (listener.equals(wrapper.mListener)) {
                        mCallbacks.remove(i);
                        return;
                    }
                }
            }
        }
    }

    /**
     * Register the provided list of triggers for this process.
     *
     * <p>Profiling triggers are system events that an app can register interest in, and then
     * receive profiling data when any of the registered triggers occur. There is no guarantee that
     * these triggers will be filled. Results, if available, will be delivered only to a global
     * listener added using {@link #registerForAllProfilingResults}.
     *
     * <p>Only one of each trigger type can be added at a time.
     *
     * <ul>
     *   <li>If the provided list contains a trigger type that is already registered then the new
     *       one will replace the existing one.
     *   <li>If the provided list contains more than one trigger object for a trigger type then only
     *       one will be kept.
     * </ul>
     *
     * <p>The filename of the file placed in app storage will contain the trigger type for which it
     * was collected in the form: "trigger-type-x", where x is equal to the API value of the trigger
     * type, found in {@link ProfilingTrigger}. Example filename for {@link
     * ProfilingTrigger#TRIGGER_TYPE_ANR}: profile_trigger-type-2_2025-01-23-04-56-12.perfetto-trace
     *
     * <p>Apps can define their own per-trigger rate limiting to help ensure they receive results
     * aligned with their needs. More details can be found at {@link
     * ProfilingTrigger.Builder#setRateLimitingPeriodHours}.
     */
    @FlaggedApi(Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
    public void addProfilingTriggers(@NonNull List<ProfilingTrigger> triggers) {
        synchronized (mLock) {
            if (triggers.isEmpty()) {
                // No triggers are being added, nothing to do.
                if (DEBUG) Log.d(TAG, "Trying to add an empty list of triggers.");
                return;
            }

            final IProfilingService service = getOrCreateIProfilingServiceLocked(false);
            if (service == null) {
                // If we can't access service then we can't do anything. Return.
                if (DEBUG) Log.d(TAG, "ProfilingService is not available, triggers will be lost.");
                return;
            }

            String packageName = mContext.getPackageName();
            if (packageName == null) {
                // This should never happen.
                if (DEBUG) Log.d(TAG, "Failed to resolve package name.");
                return;
            }

            try {
                service.addProfilingTriggers(toValueParcelList(triggers), packageName);
            } catch (RemoteException e) {
                if (DEBUG) Log.d(TAG, "Binder exception processing request", e);
                e.rethrowAsRuntimeException();
            }
        }
    }

    /**
     * Register this process for all triggers.
     *
     * <p>Registering for all triggers is in addition to any specific triggers registered. Any
     * triggers already registered when this is called, along with their parameters, will not be
     * impacted. Any triggers specifically registered after calling this, along with any parameters
     * set on them, will take precedence over what is set here.
     *
     * <p>See {@link #addProfilingTriggers} for more on triggers.
     */
    @FlaggedApi(Flags.FLAG_PROFILING_25Q4)
    public void addAllProfilingTriggers() {
        synchronized (mLock) {
            final IProfilingService service = getOrCreateIProfilingServiceLocked(false);
            if (service == null) {
                // If we can't access service then we can't do anything. Throw.
                if (DEBUG) Log.d(TAG, "ProfilingService is not available.");
                throw new RuntimeException("ProfilingService is not available");
            }

            String packageName = mContext.getPackageName();
            if (packageName == null) {
                // This should never happen.
                if (DEBUG) Log.d(TAG, "Failed to resolve package name.");
                throw new RuntimeException("Failed to resolve package name");
            }

            try {
                service.addAllProfilingTriggers(packageName);
            } catch (RemoteException e) {
                if (DEBUG) Log.d(TAG, "Binder exception processing request", e);
                e.rethrowAsRuntimeException();
            }
        }
    }

    @FlaggedApi(Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
    private List<ProfilingTriggerValueParcel> toValueParcelList(
            List<ProfilingTrigger> triggerList) {
        List<ProfilingTriggerValueParcel> triggerValueParcelList =
                new ArrayList<ProfilingTriggerValueParcel>();

        for (int i = 0; i < triggerList.size(); i++) {
            triggerValueParcelList.add(triggerList.get(i).toValueParcel());
        }

        return triggerValueParcelList;
    }

    /** Remove triggers for this process with trigger types in the provided list. */
    @FlaggedApi(Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
    public void removeProfilingTriggersByType(@NonNull @TriggerType int[] triggers) {
        synchronized (mLock) {
            if (triggers.length == 0) {
                // No triggers are being removed, nothing to do.
                if (DEBUG) Log.d(TAG, "Trying to remove an empty list of triggers.");
                return;
            }

            final IProfilingService service = getOrCreateIProfilingServiceLocked(false);
            if (service == null) {
                // If we can't access service then we can't do anything. Return.
                if (DEBUG) {
                    Log.d(TAG, "ProfilingService is not available, triggers will not be removed.");
                }
                return;
            }

            String packageName = mContext.getPackageName();
            if (packageName == null) {
                if (DEBUG) Log.d(TAG, "Failed to resolve package name.");
                return;
            }

            try {
                service.removeProfilingTriggers(triggers, packageName);
            } catch (RemoteException e) {
                if (DEBUG) Log.d(TAG, "Binder exception processing request", e);
                throw new RuntimeException("Unable to remove profiling triggers.");
            }
        }
    }

    /** Remove all triggers for this process. */
    @FlaggedApi(Flags.FLAG_SYSTEM_TRIGGERED_PROFILING_NEW)
    public void clearProfilingTriggers() {
        synchronized (mLock) {
            final IProfilingService service = getOrCreateIProfilingServiceLocked(false);
            if (service == null) {
                // If we can't access service then we can't do anything. Return.
                if (DEBUG) {
                    Log.d(TAG, "ProfilingService is not available, triggers will not be removed.");
                }
                return;
            }

            String packageName = mContext.getPackageName();
            if (packageName == null) {
                if (DEBUG) Log.d(TAG, "Failed to resolve package name.");
                return;
            }

            try {
                service.clearProfilingTriggers(packageName);
            } catch (RemoteException e) {
                if (DEBUG) Log.d(TAG, "Binder exception processing request", e);
                throw new RuntimeException("Unable to clear profiling triggers.");
            }
        }
    }

    /**
     * Request a snapshot of a background trace, if one is running.
     *
     * <p>This request sends a {@link ProfilingTrigger#TRIGGER_TYPE_APP_REQUEST_RUNNING_TRACE}
     * trigger. Apps must register interest in this trigger in order to receive the result using
     * either {@link #addProfilingTriggers} or {@link #addAllProfilingTriggers()}.
     */
    @FlaggedApi(Flags.FLAG_PROFILING_25Q4)
    public void requestRunningSystemTrace(@Nullable String tag) {
        synchronized (mLock) {
            final IProfilingService service = getOrCreateIProfilingServiceLocked(false);
            if (service == null) {
                // If we can't access service then we can't do anything. Return.
                if (DEBUG) {
                    Log.d(
                            TAG,
                            "ProfilingService is not available, requestRunningSystemTrace "
                                    + "ignored.");
                }
                return;
            }

            String packageName = mContext.getPackageName();
            if (packageName == null) {
                if (DEBUG) Log.d(TAG, "Failed to resolve package name.");
                return;
            }

            try {
                service.processTrigger(
                        Binder.getCallingUid(),
                        packageName,
                        ProfilingTrigger.TRIGGER_TYPE_APP_REQUEST_RUNNING_TRACE,
                        tag,
                        null);
            } catch (RemoteException e) {
                if (DEBUG) Log.d(TAG, "Binder exception processing request", e);
                e.rethrowAsRuntimeException();
            }
        }
    }

    /** @hide */
    @VisibleForTesting
    @GuardedBy("mLock")
    public @Nullable IProfilingService getOrCreateIProfilingServiceLocked(
            boolean isGeneralListener) {
        // We only register the callback with registerResultsCallback once per binder object, and we
        // only create one binder object per ProfilingManager instance. If the object already exists
        // then it was successfully created and registered previously so we can just return it.
        if (mProfilingService != null) {
            return mProfilingService;
        }

        mProfilingService =
                IProfilingService.Stub.asInterface(
                        ProfilingFrameworkInitializer.getProfilingServiceManager()
                                .getProfilingServiceRegisterer()
                                .get());
        if (mProfilingService == null) {
            // Service is not accessible, all requests will fail.
            return mProfilingService;
        }
        try {
            mProfilingService.registerResultsCallback(
                    isGeneralListener,
                    new IProfilingResultCallback.Stub() {

                        /**
                         * Called by {@link ProfilingService} when a result is ready, both for
                         * success and failure.
                         */
                        @Override
                        public void sendResult(
                                @Nullable String resultFile,
                                long keyMostSigBits,
                                long keyLeastSigBits,
                                int status,
                                @Nullable String tag,
                                @Nullable String error,
                                int triggerType) {
                            synchronized (mLock) {
                                if (mCallbacks.isEmpty()) {
                                    // This shouldn't happen - no callbacks, nowhere to report this
                                    // result.
                                    if (DEBUG) Log.d(TAG, "No callbacks");
                                    mProfilingService = null;
                                    return;
                                }

                                // This shouldn't be true, but if the file is null ensure the status
                                // represents a failure.
                                final boolean overrideStatusToError =
                                        resultFile == null && status == ProfilingResult.ERROR_NONE;

                                UUID key = new UUID(keyMostSigBits, keyLeastSigBits);
                                int removeListenerPos = -1;
                                boolean resultDelivered = false;
                                for (int i = 0; i < mCallbacks.size(); i++) {
                                    ProfilingRequestCallbackWrapper wrapper = mCallbacks.get(i);
                                    /*
                                    We want to proceed with the callback in 2 cases:
                                    1 - A request specific listener with the same key as the result,
                                        meaning this listener was provided with the request which
                                        resulted in this result.
                                    2 - A global listener, which is identified as a listener with
                                        null key.
                                    We only skip the callback if the listener key is non-null
                                    (meaning it belongs to a specific request) and the key does not
                                    match the one provided with the result (meaning it belongs to a
                                    different request).
                                    */
                                    if (key.equals(wrapper.mKey)) {
                                        // At most 1 listener can have a key matching this result:
                                        // the one registered with the request, remove that one
                                        // only.
                                        if (removeListenerPos == -1) {
                                            removeListenerPos = i;
                                        } else {
                                            // This should never happen.
                                            if (DEBUG) {
                                                Log.d(
                                                        TAG,
                                                        "More than 1 listener with the same key");
                                            }
                                        }
                                    } else if (wrapper.mKey != null) {
                                        // If the key is not null, and doesn't matched the result
                                        // key, then this key belongs to another request and should
                                        // not be triggered.
                                        continue;
                                    }

                                    // TODO: b/337017299 - check resultFile is valid before
                                    // returning Now trigger the callback for any listener that
                                    // doesn't belong to another request.
                                    wrapper.mExecutor.execute(
                                            () ->
                                                    wrapper.mListener.accept(
                                                            new ProfilingResult(
                                                                    overrideStatusToError
                                                                            ? ProfilingResult
                                                                                    .ERROR_UNKNOWN
                                                                            : status,
                                                                    getAppFileDir() + resultFile,
                                                                    tag,
                                                                    error,
                                                                    triggerType)));
                                    resultDelivered = true;

                                    if (removeListenerPos == i) {
                                        // removeListenerPos is set to i if we are in the iteration
                                        // belonging to a request specific listener which belongs to
                                        // this result. In this case, try to trigger the cleanup so
                                        // that the explicit request profiling case gets cleaned up
                                        // too.
                                        maybeCleanupOldFiles(wrapper.mExecutor);
                                    }
                                }

                                // Remove the single listener that was tied to the request, if
                                // applicable.
                                if (removeListenerPos != -1) {
                                    mCallbacks.remove(removeListenerPos);
                                }

                                if (Flags.notifyResultDelivered() && resultDelivered) {
                                    try {
                                        if (mProfilingService != null) {
                                            mProfilingService.notifyResultDelivered(
                                                    keyMostSigBits, keyLeastSigBits);
                                        }
                                    } catch (RemoteException e) {
                                        if (DEBUG) {
                                            Log.w(
                                                    TAG,
                                                    "Failed to notify service of result delivery"
                                                            + " for key "
                                                            + key,
                                                    e);
                                        }
                                    }
                                }
                            }
                        }

                        /**
                         * Called by {@link ProfilingService} when a trace is ready and needs to be
                         * copied to callers internal storage.
                         *
                         * <p>This method will open a new file and pass back the FileDescriptor for
                         * ProfilingService to write to via a new binder call.
                         *
                         * <p>Takes in key most/least significant bits which represent the key that
                         * will be used to associate this back to a profiling session which will
                         * write to the generated file.
                         */
                        @Override
                        public void generateFile(
                                String filePathRelative,
                                String fileName,
                                long keyMostSigBits,
                                long keyLeastSigBits) {
                            synchronized (mLock) {
                                String filePathAbsolute = getAppFileDir() + filePathRelative;
                                try {
                                    // Ensure the profiling directory exists. Create it if it
                                    // doesn't.
                                    final File profilingDir = new File(filePathAbsolute);
                                    if (!profilingDir.exists()) {
                                        profilingDir.mkdir();
                                    }

                                    // Create the profiling file for the output to be written to.
                                    final File profilingFile =
                                            new File(filePathAbsolute + fileName);
                                    profilingFile.createNewFile();
                                    if (!profilingFile.exists()) {
                                        // Failed to create output file. Result may be lost.
                                        if (DEBUG) Log.d(TAG, "Output file couldn't be created");
                                        return;
                                    }

                                    // Wrap the new output file in a {@link ParcelFileDescriptor} to
                                    // send back to {@link ProfilingService} to write to.
                                    ParcelFileDescriptor pfd =
                                            ParcelFileDescriptor.open(
                                                    profilingFile,
                                                    ParcelFileDescriptor.MODE_READ_WRITE);
                                    IProfilingService service =
                                            getOrCreateIProfilingServiceLocked(false);

                                    if (service == null) {
                                        // Unable to send file descriptor because we have nowhere to
                                        // send it to. Result may be lost. Close descriptor and
                                        // delete file.
                                        if (DEBUG) Log.d(TAG, "Unable to send file descriptor");
                                        tryToCleanupGeneratedFile(pfd, profilingFile);
                                        return;
                                    }

                                    try {
                                        // Send the file descriptor to service to write to.
                                        service.receiveFileDescriptor(
                                                pfd, keyMostSigBits, keyLeastSigBits);
                                    } catch (RemoteException e) {
                                        // If we failed to send it, try to clean it up as it won't
                                        // be used.
                                        if (DEBUG) {
                                            Log.d(
                                                    TAG,
                                                    "Failed sending file descriptor to service",
                                                    e);
                                        }
                                        tryToCleanupGeneratedFile(pfd, profilingFile);
                                    }
                                } catch (Exception e) {
                                    // Failure prepping output file. Result may be lost.
                                    if (DEBUG) Log.d(TAG, "Exception preparing file", e);
                                    return;
                                }
                            }
                        }

                        /**
                         * Attempt to clean up the files created for service by closing the file
                         * descriptor and deleting the file. This is intended for error cases where
                         * the descriptor could not be sent. If it was successfully sent, service
                         * will handle closing it and requesting a delete if necessary.
                         */
                        private void tryToCleanupGeneratedFile(
                                ParcelFileDescriptor fileDescriptor, File file) {
                            if (fileDescriptor != null) {
                                try {
                                    fileDescriptor.close();
                                } catch (IOException e) {
                                    // Nothing else we can do, ignore.
                                    if (DEBUG) Log.d(TAG, "Failed to cleanup file descriptor", e);
                                }
                            }

                            if (file != null) {
                                try {
                                    file.delete();
                                } catch (SecurityException e) {
                                    // Nothing else we can do, ignore.
                                    if (DEBUG) Log.d(TAG, "Failed to cleanup file", e);
                                }
                            }
                        }

                        /**
                         * Delete a file. To be used only for files created by {@link generateFile}.
                         */
                        @Override
                        public void deleteFile(String relativeFilePathAndName) {
                            try {
                                Files.delete(Path.of(getAppFileDir() + relativeFilePathAndName));
                            } catch (Exception exception) {
                                if (DEBUG) Log.e(TAG, "Failed to delete file.", exception);
                            }
                        }
                    });
        } catch (RemoteException e) {
            if (DEBUG) Log.d(TAG, "Exception registering service callback", e);
            throw new RuntimeException(
                    "Unable to register profiling result callback."
                            + " All Profiling requests will fail.");
        }
        return mProfilingService;
    }

    private String getAppFileDir() {
        return mContext.getFilesDir().getPath();
    }

    private static final class ProfilingRequestCallbackWrapper {
        /** executor provided with callback request */
        final @NonNull Executor mExecutor;

        /** listener provided with callback request */
        final @NonNull Consumer<ProfilingResult> mListener;

        /**
         * Unique key generated with each profiling request {@link #requestProfiling}, but not with
         * requests to register a listener only {@link #registerForAllProfilingResults}.
         *
         * <p>Key is used to match the result with the listener added with the request so that it
         * can removed after being triggered while the general registered callbacks remain active.
         */
        final @Nullable UUID mKey;

        ProfilingRequestCallbackWrapper(
                @NonNull Executor executor,
                @NonNull Consumer<ProfilingResult> listener,
                @Nullable UUID key) {
            mExecutor = executor;
            mListener = listener;
            mKey = key;
        }
    }
}

