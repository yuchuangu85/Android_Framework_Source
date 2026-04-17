/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.protolog;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.ravenwood.annotation.RavenwoodReplace;
import android.tracing.perfetto.DataSourceParams;
import android.tracing.perfetto.InitArguments;
import android.tracing.perfetto.Producer;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.IProtoLog;
import com.android.internal.protolog.common.IProtoLogGroup;
import com.android.internal.protolog.common.LogLevel;

import java.util.UUID;

/**
 * ProtoLog API - exposes static logging methods. Usage of this API is similar
 * to {@code android.utils.Log} class. Instead of plain text log messages each call consists of
 * a messageString, which is a format string for the log message (has to be a string literal or
 * a concatenation of string literals) and a vararg array of parameters for the formatter.
 * <p>
 * The syntax for the message string depends on
 * {@link android.text.TextUtils#formatSimple(String, Object...)}}.
 * Supported conversions:
 * %b - boolean
 * %d %x - integral type (Short, Integer or Long)
 * %f - floating point type (Float or Double)
 * %s - string
 * %% - a literal percent character
 * The width and precision modifiers are supported, argument_index and flags are not.
 * <p>
 * Methods in this class are stubs, that are replaced by optimised versions by the ProtoLogTool
 * during build.
 */
@RavenwoodKeepWholeClass
// LINT.IfChange
public class ProtoLog {
// LINT.ThenChange(frameworks/base/tools/protologtool/src/com/android/protolog/tool/ProtoLogTool.kt)

    private static final String LOG_TAG = "ProtoLog";

    // Needs to be set directly otherwise the protologtool tries to transform the method call
    @Deprecated
    public static boolean REQUIRE_PROTOLOGTOOL = true;

    @NonNull
    private static final Object sDataSourceLock = new Object();

    private static ProtoLogDataSource sDataSource;

    /**
     * The central controller for ProtoLog's state and logic.
     * This instance manages log groups, the underlying logging implementation, and the data source.
     * It is declared {@code volatile} to ensure that changes to its reference are visible across
     * threads, particularly when a new controller is set for testing purposes via
     * {@link #setControllerInstanceForTest(ProtoLogController)}.
     * In production, this is initialized once to a default controller and remains unchanged.
     * For testing, {@link #setControllerInstanceForTest(ProtoLogController)} allows swapping this
     * out to isolate test environments.
     */
    @NonNull
    private static volatile ProtoLogController sController = new ProtoLogController();

    /**
     * Registers ProtoLog groups in the current process.
     * This method allows for registering {@link IProtoLogGroup} that will be used for logging
     * within the current process.
     *
     * @param groups A varargs array of {@link IProtoLogGroup} instances to be registered.
     */
    public static void registerLogGroupInProcess(@NonNull IProtoLogGroup... groups) {
        sController.registerLogGroupInProcess(groups);
    }

    /**
     * Synchronously initialize ProtoLog in this process.
     * <p>
     * This method MUST be called before any protologging is performed in this process.
     * Ensure that all groups that will be used for protologging are registered.
     * <p>
     * This method will block until the Perfetto producer and data source are initialized.
     * For a non-blocking alternative, see {@link #initAsync(IProtoLogGroup...)}.
     */
    public static void init(@NonNull IProtoLogGroup... groups) {
        sController.init(groups);
    }

    /**
     * Asynchronously initialize ProtoLog in this process.
     * <p>
     * This method is the non-blocking equivalent of {@link #init(IProtoLogGroup...)}.
     * Initialization is performed on a background thread.
     */
    public static void initAsync(@NonNull IProtoLogGroup... groups) {
        sController.initAsync(groups);
    }

    /**
     * DEBUG level log.
     * <p>
     * NOTE: If source code is pre-processed by ProtoLogTool this is not the function call that is
     *       executed. Check generated code for actual call.
     *
     * @param group         {@code IProtoLogGroup} controlling this log call.
     * @param messageString constant format string for the logged message.
     * @param args          parameters to be used with the format string.
     */
    public static void d(@NonNull IProtoLogGroup group, @NonNull String messageString,
            @NonNull Object... args) {
        logStringMessage(LogLevel.DEBUG, group, messageString, args);
    }

    /**
     * VERBOSE level log.
     * <p>
     * NOTE: If source code is pre-processed by ProtoLogTool this is not the function call that is
     *       executed. Check generated code for actual call.
     *
     * @param group         {@code IProtoLogGroup} controlling this log call.
     * @param messageString constant format string for the logged message.
     * @param args          parameters to be used with the format string.
     */
    public static void v(@NonNull IProtoLogGroup group, @NonNull String messageString,
            @NonNull Object... args) {
        logStringMessage(LogLevel.VERBOSE, group, messageString, args);
    }

    /**
     * INFO level log.
     * <p>
     * NOTE: If source code is pre-processed by ProtoLogTool this is not the function call that is
     *       executed. Check generated code for actual call.
     *
     * @param group         {@code IProtoLogGroup} controlling this log call.
     * @param messageString constant format string for the logged message.
     * @param args          parameters to be used with the format string.

     */
    public static void i(@NonNull IProtoLogGroup group, @NonNull String messageString,
            @NonNull Object... args) {
        logStringMessage(LogLevel.INFO, group, messageString, args);
    }

    /**
     * WARNING level log.
     * <p>
     * NOTE: If source code is pre-processed by ProtoLogTool this is not the function call that is
     *       executed. Check generated code for actual call.
     *
     * @param group         {@code IProtoLogGroup} controlling this log call.
     * @param messageString constant format string for the logged message.
     * @param args          parameters to be used with the format string.
     */
    public static void w(@NonNull IProtoLogGroup group, @NonNull String messageString,
            @NonNull Object... args) {
        logStringMessage(LogLevel.WARN, group, messageString, args);
    }

    /**
     * ERROR level log.
     * <p>
     * NOTE: If source code is pre-processed by ProtoLogTool this is not the function call that is
     *       executed. Check generated code for actual call.
     *
     * @param group         {@code IProtoLogGroup} controlling this log call.
     * @param messageString constant format string for the logged message.
     * @param args          parameters to be used with the format string.

     */
    public static void e(@NonNull IProtoLogGroup group, @NonNull String messageString,
            @NonNull Object... args) {
        logStringMessage(LogLevel.ERROR, group, messageString, args);
    }

    /**
     * WHAT A TERRIBLE FAILURE level log
     * <p>
     * NOTE: If source code is pre-processed by ProtoLogTool this is not the function call that is
     *       executed. Check generated code for actual call.
     *
     * @param group         {@code IProtoLogGroup} controlling this log call.
     * @param messageString constant format string for the logged message.
     * @param args          parameters to be used with the format string.
     */
    public static void wtf(@NonNull IProtoLogGroup group, @NonNull String messageString,
            @NonNull Object... args) {
        logStringMessage(LogLevel.WTF, group, messageString, args);
    }

    /**
     * Check if ProtoLog isEnabled for a target group.
     * @param group Group to check enable status of.
     * @return true iff this is being logged.
     */
    public static boolean isEnabled(@NonNull IProtoLogGroup group, @NonNull LogLevel level) {
        return sController.mProtoLogInstance.isEnabled(group, level);
    }

    /**
     * Get the single ProtoLog instance.
     * @return A singleton instance of ProtoLog.
     */
    @Nullable
    public static IProtoLog getSingleInstance() {
        return sController.mProtoLogInstance;
    }

    @NonNull
    public static ProtoLogDataSource getSharedSingleInstanceDataSource() {
        return getSharedSingleInstanceDataSource(true);
    }

    /**
     * Gets or creates if it doesn't exist yet the protolog datasource to use in this process.
     * We should re-use the same datasource to avoid registering the datasource multiple times in
     * the same process, since there is no way to unregister the datasource after registration.
     *
     * @param register if true, the datasource will be registered within this call. Otherwise,
     *                 the caller is expected to register the returned datasource.
     * @return The single ProtoLog datasource instance to be shared across all ProtoLog tracing
     *         objects.
     */
    @NonNull
    public static ProtoLogDataSource getSharedSingleInstanceDataSource(boolean register) {
        synchronized (sDataSourceLock) {
            if (sDataSource == null) {
                sDataSource = new ProtoLogDataSource();

                if (register) {
                    Producer.init(InitArguments.DEFAULTS);
                    var policy = DataSourceParams
                            .PERFETTO_DS_BUFFER_EXHAUSTED_POLICY_STALL_AND_DROP;
                    DataSourceParams params =
                            new DataSourceParams.Builder()
                                    .setBufferExhaustedPolicy(policy)
                                    .build();
                    sDataSource.register(params);
                }
            }

            return sDataSource;
        }
    }

    private static void logStringMessage(@NonNull LogLevel logLevel, @NonNull IProtoLogGroup group,
            @NonNull String stringMessage, @NonNull Object... args) {
        var instance = sController.mProtoLogInstance;
        if (instance == null) {
            if (android.tracing.Flags.protologAsyncInit()) {
                initAsync(group);
                instance = sController.mProtoLogInstance;
                Log.w(LOG_TAG, "ProtoLog used before initialization, calling initAsync");
            } else {
                Log.wtfStack(LOG_TAG,
                        "Trying to use ProtoLog before it is initialized in this process.");
                return;
            }
        }

        if (instance.isEnabled(group, logLevel)) {
            instance.log(logLevel, group, stringMessage, args);
        }
    }

    /**
     * For testing purposes only. Sets the controller instance, allowing tests to
     * provide a fresh or mocked controller to isolate test state.
     *
     * @param controller The controller instance to use. If null, a new default
     *                   controller will be created on next access.
     */
    @VisibleForTesting
    public static void setControllerInstanceForTest(@NonNull ProtoLogController controller) {
        sController = controller;
    }

    @VisibleForTesting
    @NonNull
    public static ProtoLogController getControllerInstanceForTest() {
        return sController;
    }

    @RavenwoodReplace(reason = "Always use the Log backend on ravenwood, not Perfetto")
    static boolean logOnlyToLogcat() {
        return false;
    }

    static boolean logOnlyToLogcat$ravenwood() {
        // We don't want to initialize Perfetto data sources and have to deal with Perfetto
        // when running tests on the host side, instead just log everything to logcat which has
        // already been made compatible with ravenwood.
        return true;
    }

    private static final IProtoLogGroup PROTOLOG_GROUP = new IProtoLogGroup() {
        private volatile boolean mLogToLogcat = true;

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public boolean isLogToLogcat() {
            return mLogToLogcat;
        }

        @Override
        public String getTag() {
            return "ProtoLog";
        }

        @Override
        public void setLogToLogcat(boolean logToLogcat) {
            mLogToLogcat = logToLogcat;
        }

        @Override
        public String name() {
            return "ProtoLog";
        }

        @Override
        public int getId() {
            return (int) UUID.nameUUIDFromBytes(ProtoLog.class.getName().getBytes())
                    .getMostSignificantBits() % Integer.MAX_VALUE;
        }
    };
}
