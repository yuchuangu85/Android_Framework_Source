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

package com.android.internal.os;

import android.annotation.Nullable;
import android.app.job.JobParameters;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Process;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The DebugStore class provides methods for recording various debug events related to service
 * lifecycle, broadcast receivers and others.
 *
 * <p>The DebugStore class facilitates debugging ANR issues by recording time-stamped events related
 * to service lifecycles, broadcast receivers, and other framework operations. It logs the start and
 * end times of operations within the ANR timer scope called by framework, enabling pinpointing of
 * methods and events contributing to ANRs.
 *
 * <p>Usage currently includes recording service starts, binds, and asynchronous operations
 * initiated by broadcast receivers, providing a granular view of system behavior that facilitates
 * identifying performance bottlenecks and optimizing issue resolution.
 *
 * @hide
 */
public class DebugStore {
    private static final boolean DEBUG_EVENTS = false;
    private static final String TAG = "DebugStore";

    private static DebugStoreNative sDebugStoreNative = new DebugStoreNativeImpl();

    @UnsupportedAppUsage
    @VisibleForTesting
    public static void setDebugStoreNative(DebugStoreNative nativeImpl) {
        sDebugStoreNative = nativeImpl;
    }

    /**
     * Records the scheduling of a service start.
     *
     * @param msgId The object id of the looper message payload.
     * @param intent The Intent associated with the service start.
     */
    @UnsupportedAppUsage
    public static void recordScheduleServiceStart(int msgId, @Nullable Intent intent) {
        sDebugStoreNative.recordEvent(
                "SchSvcStart",
                List.of(
                        "tname",
                        Thread.currentThread().getName(),
                        "stid",
                        String.valueOf(Process.myTid()),
                        "act",
                        intentAct(intent),
                        "cmp",
                        intentCmp(intent),
                        "mid",
                        Integer.toHexString(msgId)));
    }

    /**
     * Records the start of a service.
     *
     * @param msgId The object id of the looper message payload.
     * @return A unique ID for the recorded event.
     */
    @UnsupportedAppUsage
    public static long recordServiceStart(int msgId) {
        return sDebugStoreNative.beginEvent("SvcStart", List.of("mid", Integer.toHexString(msgId)));
    }

    /**
     * Records the scheduling of a service creation.
     *
     * @param msgId The object id of the looper message payload.
     * @param serviceInfo Information about the service being created.
     */
    @UnsupportedAppUsage
    public static void recordScheduleServiceCreate(int msgId, @Nullable ServiceInfo serviceInfo) {
        sDebugStoreNative.recordEvent(
                "SchSvcCreate",
                List.of(
                        "tname",
                        Thread.currentThread().getName(),
                        "stid",
                        String.valueOf(Process.myTid()),
                        "name",
                        Objects.toString(serviceInfo != null ? serviceInfo.name : null),
                        "mid",
                        Integer.toHexString(msgId)));
    }

    /**
     * Records the creation of a service.
     *
     * @param msgId The object id of the looper message payload.
     * @return A unique ID for the recorded event.
     */
    @UnsupportedAppUsage
    public static long recordServiceCreate(int msgId) {
        return sDebugStoreNative.beginEvent(
                "SvcCreate", List.of("mid", Integer.toHexString(msgId)));
    }

    /**
     * Records the scheduling of a service bind.
     *
     * @param msgId The object id of the looper message payload.
     * @param intent The Intent associated with the service bind.
     */
    @UnsupportedAppUsage
    public static void recordScheduleServiceBind(int msgId, @Nullable Intent intent) {
        sDebugStoreNative.recordEvent(
                "SchSvcBind",
                List.of(
                        "tname",
                        Thread.currentThread().getName(),
                        "stid",
                        String.valueOf(Process.myTid()),
                        "act",
                        intentAct(intent),
                        "cmp",
                        intentCmp(intent),
                        "mid",
                        Integer.toHexString(msgId)));
    }

    /**
     * Records the binding of a service.
     *
     * @param msgId The object id of the looper message payload.
     * @return A unique identifier for the recorded event.
     */
    @UnsupportedAppUsage
    public static long recordServiceBind(int msgId) {
        return sDebugStoreNative.beginEvent("SvcBind", List.of("mid", Integer.toHexString(msgId)));
    }

    /**
     * Records the scheduling of a (manifest-declared) broadcast receiver.
     *
     * @param pendingResultId The object ID of the PendingResult associated with the broadcast.
     * @param intent The Intent associated with the broadcast.
     */
    @UnsupportedAppUsage
    public static void recordScheduleBroadcastReceive(
            int pendingResultId, @Nullable Intent intent) {
        sDebugStoreNative.recordEvent(
                "SchRcv",
                List.of(
                        "tname",
                        Thread.currentThread().getName(),
                        "stid",
                        String.valueOf(Process.myTid()),
                        "act",
                        Objects.toString(intent != null ? intent.getAction() : null),
                        "cmp",
                        intentCmp(intent),
                        "prid",
                        Integer.toHexString(pendingResultId)));
    }

    /**
     * Records the running of a (manifest-declared) broadcast receiver.
     *
     * @param pendingResultId The object ID of the PendingResult associated with the broadcast.
     * @param receiverClass The class name of the receiver.
     * @return A unique ID for the recorded event.
     */
    @UnsupportedAppUsage
    public static long recordBroadcastReceive(int pendingResultId, String receiverClass) {
        return sDebugStoreNative.beginEvent(
                "Rcv",
                List.of(
                        "tname",
                        Thread.currentThread().getName(),
                        "stid",
                        String.valueOf(Process.myTid()),
                        "cls",
                        Objects.toString(receiverClass),
                        "prid",
                        Integer.toHexString(pendingResultId)));
    }

    /**
     * Records the scheduling of a (context-registered) broadcast receiver.
     *
     * @param pendingResultId The object ID of the PendingResult associated with the broadcast.
     * @param intent The Intent associated with the broadcast.
     */
    @UnsupportedAppUsage
    public static void recordScheduleBroadcastReceiveReg(
            int pendingResultId, @Nullable Intent intent) {
        sDebugStoreNative.recordEvent(
                "SchRcvReg",
                List.of(
                        "tname",
                        Thread.currentThread().getName(),
                        "stid",
                        String.valueOf(Process.myTid()),
                        "act",
                        intentAct(intent),
                        "cmp",
                        intentCmp(intent),
                        "prid",
                        Integer.toHexString(pendingResultId)));
    }

    /**
     * Records the running of a (context-registered) broadcast receiver.
     *
     * @param pendingResultId The object ID of the PendingResult associated with the broadcast.
     * @param receiverClass The class name of the receiver.
     * @return A unique ID for the recorded event.
     */
    @UnsupportedAppUsage
    public static long recordBroadcastReceiveReg(int pendingResultId, String receiverClass) {
        return sDebugStoreNative.beginEvent(
                "RcvReg",
                List.of(
                        "tname",
                        Thread.currentThread().getName(),
                        "stid",
                        String.valueOf(Process.myTid()),
                        "cls",
                        Objects.toString(receiverClass),
                        "prid",
                        Integer.toHexString(pendingResultId)));
    }

    /**
     * Records an asynchronous operation initiated by a broadcast receiver through calling GoAsync.
     *
     * @param pendingResultId The object ID of the PendingResult associated with the broadcast.
     */
    @UnsupportedAppUsage
    public static void recordGoAsync(int pendingResultId) {
        sDebugStoreNative.recordEvent(
                "GoAsync",
                List.of(
                        "tname",
                        Thread.currentThread().getName(),
                        "stid",
                        String.valueOf(Process.myTid()),
                        "prid",
                        Integer.toHexString(pendingResultId)));
    }

    /**
     * Records the completion of a broadcast operation through calling Finish.
     *
     * @param pendingResultId The object ID of the PendingResult associated with the broadcast.
     */
    @UnsupportedAppUsage
    public static void recordFinish(int pendingResultId) {
        sDebugStoreNative.recordEvent(
                "Finish",
                List.of(
                        "tname",
                        Thread.currentThread().getName(),
                        "stid",
                        String.valueOf(Process.myTid()),
                        "prid",
                        Integer.toHexString(pendingResultId)));
    }

    /**
     * Records the calling of {@code sendFinished} on a BroadcastReceiver's PendingResult.
     *
     * @param pendingResultId The object ID of the PendingResult associated with the broadcast.
     */
    @UnsupportedAppUsage
    public static void recordSendFinished(int pendingResultId) {
        sDebugStoreNative.recordEvent(
                "SendFinished",
                List.of(
                        "prid",
                        Integer.toHexString(pendingResultId)));
    }

    /** Records the scheduling of an application bind. */
    @UnsupportedAppUsage
    public static void recordScheduleBindApplication() {
        sDebugStoreNative.recordEvent("SchBindApp", List.of());
    }

    /**
     * Records the binding of an application.
     *
     * @return A unique ID for the recorded event.
     */
    @UnsupportedAppUsage
    public static long recordBindApplication() {
        return sDebugStoreNative.beginEvent("BindApp", List.of());
    }

    /**
     * Records the scheduling of a job start.
     *
     * @param msgId The object id of the looper message payload.
     * @param jobId The ID of the job.
     * @param jobNamespace The namespace of the job.
     */
    @UnsupportedAppUsage
    public static void recordScheduleStartJob(int msgId, int jobId, @Nullable String jobNamespace) {
        sDebugStoreNative.recordEvent(
                "SchJobStart",
                List.of(
                        "tname",
                        Thread.currentThread().getName(),
                        "stid",
                        String.valueOf(Process.myTid()),
                        "jobid",
                        String.valueOf(jobId),
                        "jobns",
                        String.valueOf(jobNamespace),
                        "mid",
                        Integer.toHexString(msgId)));
    }

    /**
     * Records the execution of a job.
     *
     * @param msgId The object id of the looper message payload.
     * @return A unique ID for the recorded event.
     */
    @UnsupportedAppUsage
    public static long recordStartJob(int msgId) {
        return sDebugStoreNative.beginEvent("JobStart", List.of("mid", Integer.toHexString(msgId)));
    }

    /**
     * Records the scheduling of a job stop.
     *
     * @param msgId The object id of the looper message payload.
     * @param jobId The ID of the job.
     * @param jobNamespace The namespace of the job.
     */
    @UnsupportedAppUsage
    public static void recordScheduleStopJob(int msgId, int jobId, @Nullable String jobNamespace) {
        sDebugStoreNative.recordEvent(
                "SchJobStop",
                List.of(
                        "tname",
                        Thread.currentThread().getName(),
                        "stid",
                        String.valueOf(Process.myTid()),
                        "jobid",
                        String.valueOf(jobId),
                        "jobns",
                        String.valueOf(jobNamespace),
                        "mid",
                        Integer.toHexString(msgId)));
    }

    /**
     * Records the stopping of a job.
     *
     * @param msgId The object id of the looper message payload.
     * @return A unique ID for the recorded event.
     */
    @UnsupportedAppUsage
    public static long recordStopJob(int msgId) {
        return sDebugStoreNative.beginEvent("JobStop", List.of("mid", Integer.toHexString(msgId)));
    }

    /**
     * Records the completion of a long-running looper message.
     *
     * @param messageCode The code representing the type of the message.
     * @param targetClass The FQN of the class that handled the message.
     * @param elapsedTimeMs The time that was taken to process the message, in milliseconds.
     */
    @UnsupportedAppUsage
    public static void recordLongLooperMessage(
            int messageCode, String targetClass, long elapsedTimeMs) {
        sDebugStoreNative.recordEvent(
                "LooperMsg",
                List.of(
                        "code",
                        String.valueOf(messageCode),
                        "trgt",
                        Objects.toString(targetClass),
                        "elapsed",
                        String.valueOf(elapsedTimeMs)));
    }

    /**
     * Ends a previously recorded event.
     *
     * @param id The unique ID of the event to be ended.
     */
    @UnsupportedAppUsage
    public static void recordEventEnd(long id) {
        sDebugStoreNative.endEvent(id, Collections.emptyList());
    }

    private static String intentAct(@Nullable Intent intent) {
        return Objects.toString(intent != null ? intent.getAction() : null);
    }

    private static String intentCmp(@Nullable Intent intent) {
        return Objects.toString(
                (intent != null && intent.getComponent() != null)
                        ? intent.getComponent().flattenToShortString()
                        : null);
    }

    /**
     * An interface for a class that acts as a wrapper for the static native methods of the Debug
     * Store.
     *
     * <p>It allows us to mock static native methods in our tests and should be removed once mocking
     * static methods becomes easier.
     */
    @VisibleForTesting
    public interface DebugStoreNative {
        /** Begins an event with the given name and attributes. */
        long beginEvent(String eventName, List<String> attributes);

        /** Ends an event with the given ID and attributes. */
        void endEvent(long id, List<String> attributes);

        /** Records an event with the given name and attributes. */
        void recordEvent(String eventName, List<String> attributes);
    }

    private static class DebugStoreNativeImpl implements DebugStoreNative {
        @Override
        public long beginEvent(String eventName, List<String> attributes) {
            long id = DebugStore.beginEventNative(eventName, attributes);
            if (DEBUG_EVENTS) {
                Log.i(
                        TAG,
                        "beginEvent: " + id + " " + eventName + " " + attributeString(attributes));
            }
            return id;
        }

        @Override
        public void endEvent(long id, List<String> attributes) {
            if (DEBUG_EVENTS) {
                Log.i(TAG, "endEvent: " + id + " " + attributeString(attributes));
            }
            DebugStore.endEventNative(id, attributes);
        }

        @Override
        public void recordEvent(String eventName, List<String> attributes) {
            if (DEBUG_EVENTS) {
                Log.i(TAG, "recordEvent: " + eventName + " " + attributeString(attributes));
            }
            DebugStore.recordEventNative(eventName, attributes);
        }

        /** Returns a string like "[key1=foo, key2=bar]" */
        private String attributeString(List<String> attributes) {
            StringBuilder sb = new StringBuilder().append("[");

            for (int i = 0; i < attributes.size(); i++) {
                sb.append(attributes.get(i));

                if (i % 2 == 0) {
                    sb.append("=");
                } else if (i < attributes.size() - 1) {
                    sb.append(", ");
                }
            }
            return sb.append("]").toString();
        }
    }

    private static native long beginEventNative(String eventName, List<String> attributes);

    private static native void endEventNative(long id, List<String> attributes);

    private static native void recordEventNative(String eventName, List<String> attributes);
}
