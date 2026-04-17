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

import android.ravenwood.annotation.RavenwoodIgnore;
import android.ravenwood.annotation.RavenwoodReplace;

import com.android.internal.ravenwood.RavenwoodHelperBridge;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

import libcore.util.NativeAllocationRegistry;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes trace events to the perfetto trace buffer. These trace events can be collected and
 * visualized using the Perfetto UI.
 *
 * <p>This tracing mechanism is independent of the method tracing mechanism offered by {@link
 * Debug#startMethodTracing} or {@link Trace}.
 *
 * @hide
 *
 * TODO(b/449768154) We don't really use this class on Ravenwood. Figure out a cleaner way
 * to disable this class, rather than the scattered @RavenwoodIgnore's.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass(
        comment = "Most features are no-op on Ravenwood")
public final class PerfettoTrace {
    private static final String TAG = "PerfettoTrace";

    // Keep in sync with C++
    private static final int PERFETTO_TE_TYPE_SLICE_BEGIN = 1;
    private static final int PERFETTO_TE_TYPE_SLICE_END = 2;
    private static final int PERFETTO_TE_TYPE_INSTANT = 3;
    private static final int PERFETTO_TE_TYPE_COUNTER = 4;

    // To simplify migration to the newer API we use this flag both when invoke trace methods and
    // in this class to chose what API to initialize.
    public static final boolean IS_USE_SDK_TRACING_API_V3 = isPerfettoSdkTracingV3();


    @RavenwoodIgnore // Flag always false on Ravenwood
    private static boolean isPerfettoSdkTracingV3() {
        return android.os.Flags.perfettoSdkTracingV3();
    }

    private static final AtomicBoolean sAttemptedSystemRegistration = new AtomicBoolean(false);

    /**
     * For fetching the next flow event id in a process.
     */
    private static final AtomicInteger sFlowEventId = new AtomicInteger();

    public static final com.android.internal.dev.perfetto.sdk.PerfettoTrace
            .Category PROC_STATE_CATEGORY = getProcStateCategory();

    @RavenwoodIgnore // Just use null on Ravenwood.
    private static com.android.internal.dev.perfetto.sdk.PerfettoTrace
            .Category getProcStateCategory() {
        return new com.android.internal.dev.perfetto.sdk.PerfettoTrace.Category("proc_state");
    }

    // For tracing coroutine execution (coroutine creation and coroutine continuations)
    public static final PerfettoTrace.Category CC_CATEGORY = new PerfettoTrace.Category("cc");

    // The same as a previous CC_CATEGORY, but to be used with a V3 API.
    public static final com.android.internal.dev.perfetto.sdk.PerfettoTrace.Category
            CC_CATEGORY_V3 = getCcCategoryV3();

    @RavenwoodIgnore // Just use null on Ravenwood.
    private static com.android.internal.dev.perfetto.sdk.PerfettoTrace.Category getCcCategoryV3() {
        return new com.android.internal.dev.perfetto.sdk.PerfettoTrace.Category("cc");
    }

    public static final com.android.internal.dev.perfetto.sdk.PerfettoTrace.Category
            BIG_LOCKS_V3 = getBigLocksV3();

    @RavenwoodIgnore // Just use null on Ravenwood.
    private static com.android.internal.dev.perfetto.sdk.PerfettoTrace.Category getBigLocksV3() {
        return new com.android.internal.dev.perfetto.sdk.PerfettoTrace.Category("big_locks");
    }

    public static final com.android.internal.dev.perfetto.sdk.PerfettoTrace.Category BROADCAST_V3 =
            getBroadcastV3();

    @RavenwoodIgnore // Just use null on Ravenwood.
    private static com.android.internal.dev.perfetto.sdk.PerfettoTrace.Category getBroadcastV3() {
        return new com.android.internal.dev.perfetto.sdk.PerfettoTrace.Category("broadcast");
    }

    public static final com.android.internal.dev.perfetto.sdk.PerfettoTrace.Category FREEZER_V3 =
            getFreezerV3();

    @RavenwoodIgnore // Just use null on Ravenwood.
    private static com.android.internal.dev.perfetto.sdk.PerfettoTrace.Category getFreezerV3() {
        return new com.android.internal.dev.perfetto.sdk.PerfettoTrace.Category("freezer");
    }

    /**
     * This is temporary wrapper to check if "mq" category is enabled, should
     * be called only from the MessageQueue.java and Looper.java.
     */
    // Tracing currently completely disabled under Ravenwood, just return false.
    @RavenwoodIgnore
    public static boolean isMQCategoryEnabled() {
        if (PerfettoTrace.IS_USE_SDK_TRACING_API_V3) {
            return PerfettoCategories.MQ_CATEGORY.isEnabled();
        }
        return false;
    }

    /**
     * This is temporary wrapper to check if either new or old APIs "jobscheduler" category is
     * enabled, should be called only from the JobSchedulerService.java.
     */
    // Tracing currently completely disabled under Ravenwood, just return false.
    @RavenwoodIgnore
    public static boolean isJobSchedulerCategoryEnabled() {
        if (PerfettoTrace.IS_USE_SDK_TRACING_API_V3) {
            return PerfettoCategories.JOB_SCHEDULER_CATEGORY.isEnabled();
        }
        return false;
    }

    /**
     * This is temporary wrapper to check if either new or old APIs "cc" category is enabled, should
     * be called only from TraceContextElement.kt in frameworks/libs/systemui/tracinglib
     */
    // Tracing currently completely disabled under Ravenwood, just return false.
    @RavenwoodIgnore
    public static boolean isCcCategoryEnabled() {
        if (PerfettoTrace.IS_USE_SDK_TRACING_API_V3) {
            return PerfettoTrace.CC_CATEGORY_V3.isEnabled();
        }
        return PerfettoTrace.CC_CATEGORY.isEnabled();
    }

    /**
     * Perfetto category a trace event belongs to.
     * Registering a category is not sufficient to capture events within the category, it must
     * also be enabled in the trace config.
     */
    @android.ravenwood.annotation.RavenwoodKeepWholeClass
    public static final class Category implements PerfettoTrackEventExtra.PerfettoPointer {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        Category.class.getClassLoader(), native_delete());

        private final long mPtr;
        private final long mExtraPtr;
        private final String mName;
        private final String mTag;
        private final String mSeverity;
        private boolean mIsRegistered;

        /**
         * Category ctor.
         *
         * @param name The category name.
         */
        public Category(String name) {
            this(name, "", "");
        }

        /**
         * Category ctor.
         *
         * @param name The category name.
         * @param tag An atrace tag name that this category maps to.
         */
        public Category(String name, String tag) {
            this(name, tag, "");
        }

        /**
         * Category ctor.
         *
         * @param name The category name.
         * @param tag An atrace tag name that this category maps to.
         * @param severity A Log style severity string for the category.
         */
        public Category(String name, String tag, String severity) {
            mName = name;
            mTag = tag;
            mSeverity = severity;
            mPtr = native_init(name, tag, severity);
            mExtraPtr = native_get_extra_ptr(mPtr);
            if (!RavenwoodHelperBridge.getInstance().isRunningOnRavenwood()) {
                sRegistry.registerNativeAllocation(this, mPtr);
            }
        }

        @FastNative
        @RavenwoodIgnore
        private static native long native_init(String name, String tag, String severity);

        @CriticalNative
        @RavenwoodIgnore
        private static native long native_delete();

        @CriticalNative
        @RavenwoodIgnore
        private static native void native_register(long ptr);

        @CriticalNative
        @RavenwoodIgnore
        private static native void native_unregister(long ptr);

        @CriticalNative
        @RavenwoodIgnore
        private static native boolean native_is_enabled(long ptr);

        @CriticalNative
        @RavenwoodIgnore
        private static native long native_get_extra_ptr(long ptr);

        /**
         * Register the category.
         */
        public Category register() {
            native_register(mPtr);
            mIsRegistered = true;
            return this;
        }

        /**
         * Unregister the category.
         */
        public Category unregister() {
            native_unregister(mPtr);
            mIsRegistered = false;
            return this;
        }

        /**
         * Whether the category is enabled or not.
         */
        @android.ravenwood.annotation.RavenwoodReplace
        public boolean isEnabled() {
            return native_is_enabled(mPtr);
        }

        public boolean isEnabled$ravenwood() {
            // Tracing currently completely disabled under Ravenwood
            return false;
        }

        /**
         * Whether the category is registered or not.
         */
        public boolean isRegistered() {
            return mIsRegistered;
        }

        /**
         * Returns the native pointer for the category.
         */
        @Override
        public long getPtr() {
            return mExtraPtr;
        }
    }

    /**
     * Manages a perfetto tracing session.
     * Constructing this object with a config automatically starts a tracing session. Each session
     * must be closed after use and then the resulting trace bytes can be read.
     *
     * The session could be in process or system wide, depending on {@code isBackendInProcess}.
     * This functionality is intended for testing.
     */
    public static final class Session {
        private final long mPtr;

        /**
         * Session ctor.
         */
        public Session(boolean isBackendInProcess, byte[] config) {
            mPtr = native_start_session(isBackendInProcess, config);
        }

        /**
         * Closes the session and returns the trace.
         */
        public byte[] close() {
            return native_stop_session(mPtr);
        }
    }

    @CriticalNative
    @RavenwoodIgnore
    private static native long native_get_process_track_uuid();

    @CriticalNative
    @RavenwoodIgnore
    private static native long native_get_thread_track_uuid(long tid);

    @FastNative
    @RavenwoodIgnore
    private static native void native_activate_trigger(String name, int ttlMs);

    @FastNative
    @RavenwoodIgnore
    private static native void native_register(boolean isBackendInProcess);

    @RavenwoodIgnore
    private static native long native_start_session(boolean isBackendInProcess, byte[] config);

    @RavenwoodReplace
    private static native byte[] native_stop_session(long ptr);

    private static byte[] native_stop_session$ravenwood(long ptr) {
        return new byte[1]; // Just return something to avoid confusing callers.
    }

    /**
     * Writes a trace message to indicate a given section of code was invoked.
     *
     * @param category The perfetto category.
     * @param eventName The event name to appear in the trace.
     */
    public static PerfettoTrackEventExtra.Builder instant(Category category, String eventName) {
        return PerfettoTrackEventExtra.builder(category.isEnabled())
            .init(PERFETTO_TE_TYPE_INSTANT, category)
            .setEventName(eventName);
    }

    /**
     * Writes a trace message to indicate the start of a given section of code.
     *
     * @param category The perfetto category.
     * @param eventName The event name to appear in the trace.
     */
    public static PerfettoTrackEventExtra.Builder begin(Category category, String eventName) {
        return PerfettoTrackEventExtra.builder(category.isEnabled())
            .init(PERFETTO_TE_TYPE_SLICE_BEGIN, category)
            .setEventName(eventName);
    }

    /**
     * Writes a trace message to indicate the end of a given section of code.
     *
     * @param category The perfetto category.
     */
    public static PerfettoTrackEventExtra.Builder end(Category category) {
        return PerfettoTrackEventExtra.builder(category.isEnabled())
            .init(PERFETTO_TE_TYPE_SLICE_END, category);
    }

    /**
     * Writes a trace message to indicate the value of a given section of code.
     *
     * @param category The perfetto category.
     * @param value The value of the counter.
     */
    public static PerfettoTrackEventExtra.Builder counter(Category category, long value) {
        return PerfettoTrackEventExtra.builder(category.isEnabled())
            .init(PERFETTO_TE_TYPE_COUNTER, category)
            .setCounter(value);
    }

    /**
     * Writes a trace message to indicate the value of a given section of code.
     *
     * @param category The perfetto category.
     * @param value The value of the counter.
     * @param trackName The trackName for the event.
     */
    public static PerfettoTrackEventExtra.Builder counter(
            Category category, long value, String trackName) {
        return counter(category, value).usingProcessCounterTrack(trackName);
    }

    /**
     * Writes a trace message to indicate the value of a given section of code.
     *
     * @param category The perfetto category.
     * @param value The value of the counter.
     */
    public static PerfettoTrackEventExtra.Builder counter(Category category, double value) {
        return PerfettoTrackEventExtra.builder(category.isEnabled())
            .init(PERFETTO_TE_TYPE_COUNTER, category)
            .setCounter(value);
    }

    /**
     * Writes a trace message to indicate the value of a given section of code.
     *
     * @param category The perfetto category.
     * @param value The value of the counter.
     * @param trackName The trackName for the event.
     */
    public static PerfettoTrackEventExtra.Builder counter(
            Category category, double value, String trackName) {
        return counter(category, value).usingProcessCounterTrack(trackName);
    }

    /**
     * Returns the next flow id to be used.
     */
    public static int getFlowId() {
        return sFlowEventId.incrementAndGet();
    }

    /**
     * Returns the global track uuid that can be used as a parent track uuid.
     */
    public static long getGlobalTrackUuid() {
        return 0;
    }

    /**
     * Returns the process track uuid that can be used as a parent track uuid.
     */
    public static long getProcessTrackUuid() {
        return native_get_process_track_uuid();
    }

    /**
     * Given a thread tid, returns the thread track uuid that can be used as a parent track uuid.
     */
    public static long getThreadTrackUuid(long tid) {
        return native_get_thread_track_uuid(tid);
    }

    /**
     * Activates a trigger by name {@code triggerName} with expiry in {@code ttlMs}.
     */
    public static void activateTrigger(String triggerName, int ttlMs) {
        native_activate_trigger(triggerName, ttlMs);
    }

    /** Registers the process with Perfetto. */
    @RavenwoodIgnore
    public static void register(boolean isBackendInProcess) {
        if (!isBackendInProcess) {
            sAttemptedSystemRegistration.set(true);
        }
        if (IS_USE_SDK_TRACING_API_V3) {
            com.android.internal.dev.perfetto.sdk.PerfettoTrace.register(isBackendInProcess);
        } else {
            native_register(isBackendInProcess);
        }
    }

    /** Registers categories with Perfetto. */
    @RavenwoodIgnore
    public static void registerCategories() {
        if (IS_USE_SDK_TRACING_API_V3) {
            CC_CATEGORY_V3.register();
            BIG_LOCKS_V3.register();
        } else {
            CC_CATEGORY.register();
        }
        if (android.os.Flags.perfettoSdkTracingV3()) {
            PROC_STATE_CATEGORY.register();
            BROADCAST_V3.register();
            FREEZER_V3.register();
        }
    }

    /**
     * Returns whether the calling process attempted to register with the system backend of perfetto
     * by calling {@code register(false)}. A true return does not mean that the registration is
     * already completed, as that is an asynchronous operation.
     */
    public static boolean getAttempedSystemRegistration() {
        return sAttemptedSystemRegistration.get();
    }
}
