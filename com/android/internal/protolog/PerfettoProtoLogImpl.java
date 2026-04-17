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

package com.android.internal.protolog;

import static android.content.Context.PROTOLOG_CONFIGURATION_SERVICE;
import static android.internal.perfetto.protos.InternedDataOuterClass.InternedData.PROTOLOG_STACKTRACE;
import static android.internal.perfetto.protos.InternedDataOuterClass.InternedData.PROTOLOG_STRING_ARGS;
import static android.internal.perfetto.protos.ProfileCommon.InternedString.IID;
import static android.internal.perfetto.protos.ProfileCommon.InternedString.STR;
import static android.internal.perfetto.protos.Protolog.ProtoLogMessage.BOOLEAN_PARAMS;
import static android.internal.perfetto.protos.Protolog.ProtoLogMessage.DOUBLE_PARAMS;
import static android.internal.perfetto.protos.Protolog.ProtoLogMessage.MESSAGE_ID;
import static android.internal.perfetto.protos.Protolog.ProtoLogMessage.SINT64_PARAMS;
import static android.internal.perfetto.protos.Protolog.ProtoLogMessage.STACKTRACE_IID;
import static android.internal.perfetto.protos.Protolog.ProtoLogMessage.STR_PARAM_IIDS;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.GROUPS;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.Group.ID;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.Group.NAME;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.Group.TAG;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MESSAGES;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.GROUP_ID;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.LEVEL;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.MESSAGE;
import static android.internal.perfetto.protos.TracePacketOuterClass.TracePacket.INTERNED_DATA;
import static android.internal.perfetto.protos.TracePacketOuterClass.TracePacket.PROTOLOG_MESSAGE;
import static android.internal.perfetto.protos.TracePacketOuterClass.TracePacket.PROTOLOG_VIEWER_CONFIG;
import static android.internal.perfetto.protos.TracePacketOuterClass.TracePacket.SEQUENCE_FLAGS;
import static android.internal.perfetto.protos.TracePacketOuterClass.TracePacket.SEQ_INCREMENTAL_STATE_CLEARED;
import static android.internal.perfetto.protos.TracePacketOuterClass.TracePacket.SEQ_NEEDS_INCREMENTAL_STATE;
import static android.internal.perfetto.protos.TracePacketOuterClass.TracePacket.TIMESTAMP;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.text.TextUtils;
import android.tracing.perfetto.DataSource;
import android.tracing.perfetto.DataSourceParams;
import android.tracing.perfetto.InitArguments;
import android.tracing.perfetto.Producer;
import android.tracing.perfetto.TracingContext;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.IProtoLogConfigurationService.RegisterClientArgs;
import com.android.internal.protolog.ProtoLogDataSource.Instance.ProtoLogTracingInstanceStartCallback;
import com.android.internal.protolog.common.ILogger;
import com.android.internal.protolog.common.IProtoLog;
import com.android.internal.protolog.common.IProtoLogGroup;
import com.android.internal.protolog.common.InvalidFormatStringException;
import com.android.internal.protolog.common.LogDataType;
import com.android.internal.protolog.common.LogLevel;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * A service for the ProtoLog logging system.
 */
public abstract class PerfettoProtoLogImpl extends IProtoLogClient.Stub implements IProtoLog,
        ProtoLogTracingInstanceStartCallback,
        ProtoLogDataSource.Instance.ProtoLogTracingInstanceStopCallback,
        DataSource.TracingInstanceFlushCallback {
    private static final String LOG_TAG = "ProtoLog";
    public static final String NULL_STRING = "null";
    @VisibleForTesting
    public static int MAX_INTERNED_STRINGS_SIZE_BYTES_BEFORE_RESET = 4 * 1024 * 1024; // 4MB
    private final AtomicInteger mTracingInstances = new AtomicInteger();

    private final Object mAsyncInitLock = new Object();
    private volatile boolean mAsyncInitInProgress = false;
    private volatile boolean mEnabledCalled = false;
    private final Object mEnableLock = new Object();

    @NonNull
    protected final ProtoLogDataSource mDataSource;
    @Nullable
    @Deprecated
    private IProtoLogConfigurationService mConfigurationService;
    @Nullable
    private ProtoLogConfigurationClient mConfigurationClient;

    @NonNull
    private final Object mLogGroupsLock = new Object();

    @GuardedBy("mLogGroupsLock")
    @NonNull
    private final TreeMap<String, IProtoLogGroup> mLogGroups = new TreeMap<>();
    @NonNull
    private final ProtoLogCacheUpdater mCacheUpdater;

    @NonNull
    private final int[] mDefaultLogLevelCounts = new int[LogLevel.values().length];
    @NonNull
    private final Map<String, int[]> mLogLevelCounts = new ArrayMap<>();
    @NonNull
    private final Map<String, Integer> mCollectStackTraceGroupCounts = new ArrayMap<>();

    @NonNull
    private final ReadWriteLock mConfigUpdaterLock = new ReentrantReadWriteLock();

    /**
     * This set tracks active tracing instances from the perspective of the {@code
     * mSingleThreadedExecutor}. It contains instance indexes, added when a tracing session starts
     * and removed when it stops. This ensures that queued messages are traced only to the expected
     * tracing session.
     *
     * <p>Specifically, it prevents:
     * <ul>
     *   <li>Tracing messages logged before a session starts but still in the queue.</li>
     *   <li>Tracing messages logged after a session stops but still in the queue.</li>
     * </ul>
     *
     * <p>The set is modified on the single-threaded {@code mSingleThreadedExecutor}, ensuring
     * that the add/remove operations happen only after all messages in the queue at that point are
     * processed.
     */
    @NonNull
    private final Set<Integer> mActiveTracingInstances = new ArraySet<>();

    @NonNull
    private final CountDownLatch mFirstTracingInstanceStartLatch = new CountDownLatch(1);


    // A single-threaded executor to ensure that all background tasks are processed sequentially.
    // This is crucial for operations like connecting to the configuration service before other
    // logging activities, and synchronizing queued logging tasks on tracing start and stop.
    @VisibleForTesting
    public final ExecutorService mSingleThreadedExecutor;
    private final LinkedBlockingDeque<Runnable> mExecutorQueue = new LinkedBlockingDeque<>();

    // Set to true once this is ready to accept protolog to logcat requests.
    private boolean mLogcatReady = false;

    protected PerfettoProtoLogImpl(
            @NonNull ProtoLogDataSource dataSource,
            @NonNull ProtoLogCacheUpdater cacheUpdater,
            @NonNull IProtoLogGroup[] groups) {
        mSingleThreadedExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                mExecutorQueue, (r) -> new Thread(r, "ProtoLogBackground"));
        mDataSource = dataSource;
        mCacheUpdater = cacheUpdater;
        mConfigurationService = null;

        registerGroupsLocally(groups);
    }

    /**
     * To be called to enable the ProtoLogImpl to start tracing to ProtoLog and register with all
     * the expected ProtoLog components.
     */
    public void enable() {
        enable(false, null);
    }

    /**
     * To be called to enable the ProtoLogImpl to start tracing to ProtoLog and register with all
     * the expected ProtoLog components.
     * @param async if true, the initialization will happen on a background thread.
     */
    public void enable(boolean async) {
        enable(async, null);
    }

    /**
     * To be called to enable the ProtoLogImpl to start tracing to ProtoLog and register with all
     * the expected ProtoLog components.
     * @param async if true, the initialization will happen on a background thread.
     * @param service an optional service to use for testing.
     */
    public void enable(boolean async, @Nullable IProtoLogConfigurationService service) {
        synchronized (mEnableLock) {
            if (mEnabledCalled) {
                return;
            }
            mEnabledCalled = true;
        }

        if (async) {
            mAsyncInitInProgress = true;
        }

        final IProtoLogGroup[] groups;
        synchronized (mLogGroupsLock) {
            // Get the values on the caller thread before another task is posted
            // to the same executor. This is to ensure that we register all groups that
            // have been added so far.
            groups = mLogGroups.values().toArray(new IProtoLogGroup[0]);
        }

        final Runnable backgroundTasks = () -> {
            if (async) {
                Producer.init(InitArguments.DEFAULTS);
                DataSourceParams params =
                        new DataSourceParams.Builder()
                                .setBufferExhaustedPolicy(
                                        DataSourceParams
                                                .PERFETTO_DS_BUFFER_EXHAUSTED_POLICY_STALL_AND_DROP)
                                .build();
                mDataSource.register(params);
            }

            if (android.tracing.Flags.javaNativeProtolog()) {
                if (mConfigurationClient == null) {
                    mConfigurationClient = new ProtoLogConfigurationClient(getViewerConfigPath(),
                            (enabled, groupsToToggle) -> {
                                final ILogger logger = (message) -> Log.d(LOG_TAG, message);
                                if (enabled) {
                                    startLoggingToLogcat(groupsToToggle, logger);
                                } else {
                                    stopLoggingToLogcat(groupsToToggle, logger);
                                }
                            });
                }
                mConfigurationClient.start(groups, async, service);
            } else {
                if (service != null) {
                    mConfigurationService = service;
                } else if (mConfigurationService == null) {
                    mConfigurationService = getConfigurationService();
                }

                if (mConfigurationService != null) {
                    try {
                        var args = createConfigurationServiceRegisterClientArgs();
                        args.groups = new String[groups.length];
                        args.groupsDefaultLogcatStatus = new boolean[groups.length];

                        for (var i = 0; i < groups.length; i++) {
                            var group = groups[i];
                            args.groups[i] = group.name();
                            args.groupsDefaultLogcatStatus[i] = group.isLogToLogcat();
                        }

                        mConfigurationService.registerClient(this, args);
                    } catch (RemoteException e) {
                        Log.wtf(LOG_TAG, "Failed to register ProtoLog client", e);
                    }
                }
            }

            if (async) {
                // A datasource instance has 5 seconds to report starting before we process the
                // rest of the queue. We block here because it's possible that a datasource instance
                // is already running and takes a bit of time to report the onTracingInstanceStart,
                // in which case we want to block here before executed queued protolog messages in
                // the background thread, so that we don't miss reporting them because they execute
                // before the onTracingInstanceStart finishes executing.
                // This is not 100% guaranteed to work if for whatever reason the registration of
                // the datasource is slower to trigger the tracingInstanceStart callback.
                try {
                    var dsInstanceRunning =
                            mFirstTracingInstanceStartLatch.await(5, TimeUnit.SECONDS);
                    if (!dsInstanceRunning) {
                        Log.w(LOG_TAG, "Timed out waiting for first tracing instance start. "
                                + "This is expected if there we no active tracing sessions before "
                                + "the async initialization. If there was a tracing session "
                                + "running then this will likely lead to a loss of some ProtoLog "
                                + "messages.");
                    }
                } catch (InterruptedException e) {
                    Log.wtf(LOG_TAG,
                            "Interrupted while waiting for first tracing instance start", e);
                } finally {
                    synchronized (mAsyncInitLock) {
                        mAsyncInitInProgress = false;
                    }
                }
            }
        };

        if (async) {
            mSingleThreadedExecutor.execute(backgroundTasks);
        } else {
            backgroundTasks.run();
        }

        mDataSource.registerOnStartCallback(this::onTracingInstanceStart);
        mDataSource.registerOnFlushCallback(this::onTracingFlush);
        mDataSource.registerOnStopCallback(this::onTracingInstanceStop);
    }

    @Override
    public void registerGroups(@NonNull IProtoLogGroup... groups) {
        registerGroupsLocally(groups);

        if (android.tracing.Flags.javaNativeProtolog()) {
            if (mConfigurationClient != null) {
                mConfigurationClient.addGroups(groups);
            }
            return;
        }

        if (mConfigurationService != null) {
            // Because this will execute with a slight delay it means that we might be in sync with
            // the main log to logcat configuration immediately, but will be eventually.
            registerGroupsWithConfigurationServiceAsync(groups);
        } else {
            Log.w(LOG_TAG, "Missing configuration service... Not registering groups with it.");
        }
    }

    @Nullable
    private static IProtoLogConfigurationService getConfigurationService() {
        var service = ServiceManager.getService(PROTOLOG_CONFIGURATION_SERVICE);
        if (service != null) {
            return IProtoLogConfigurationService.Stub.asInterface(service);
        } else {
            Log.e(LOG_TAG, "Failed to get the ProtoLog Configuration Service! "
                    + "Protologging client will not be synced properly and will not be "
                    + "available for running configuration of which groups to log to logcat. "
                    + "We might also be missing viewer configs in the trace for decoding the "
                    + "messages.");

            // Will be null either because we are calling this before the service is ready and
            // registered with the service manager or because we are calling this from a service
            // that does not have access to the configuration service.
            return null;
        }
    }

    private void registerGroupsWithConfigurationServiceAsync(@NonNull IProtoLogGroup... groups) {
        Objects.requireNonNull(mConfigurationService,
                "A null ProtoLog Configuration Service was provided!");

        mSingleThreadedExecutor.execute(() -> {
            try {
                var args = new IProtoLogConfigurationService.RegisterGroupsArgs();
                args.groups = new String[groups.length];
                args.groupsDefaultLogcatStatus = new boolean[groups.length];
                var i = 0;
                for (var group : groups) {
                    args.groups[i] = group.name();
                    args.groupsDefaultLogcatStatus[i] = group.isLogToLogcat();
                    i++;
                }
                mConfigurationService.registerGroups(this, args);
            } catch (RemoteException e) {
                Log.wtf(LOG_TAG, "Failed to register ProtoLog groups", e);
            }
        });
    }

    /**
     * Should be called when we no longer want to use the ProtoLog logger to unlink ourselves from
     * the datasource and the configuration service to ensure we no longer receive the callback.
     */
    public void disable() {
        mDataSource.unregisterOnStartCallback(this);
        mDataSource.unregisterOnFlushCallback(this);
        mDataSource.unregisterOnStopCallback(this);

        if (mConfigurationService != null) {
            disconnectFromConfigurationServiceAsync();
        }

        mSingleThreadedExecutor.shutdown();
    }

    private void disconnectFromConfigurationServiceAsync() {
        if (android.tracing.Flags.javaNativeProtolog()) {
            if (mConfigurationClient != null) {
                mConfigurationClient.stop();
            }
            return;
        }

        Objects.requireNonNull(mConfigurationService,
                "A null ProtoLog Configuration Service was provided!");

        mSingleThreadedExecutor.execute(() -> {
            try {
                mConfigurationService.unregisterClient(this);
            } catch (RemoteException e) {
                Log.wtf(LOG_TAG, "Failed to unregister ProtoLog client", e);
            }
        });
    }

    @NonNull
    protected abstract RegisterClientArgs createConfigurationServiceRegisterClientArgs();

    /**
     * Main log method, do not call directly.
     */
    @VisibleForTesting
    @Override
    public void log(@NonNull LogLevel logLevel, @NonNull IProtoLogGroup group, long messageHash,
            long paramsMask, @Nullable Object[] args) {
        log(logLevel, group, new Message(messageHash, paramsMask), args);
    }

    @Override
    public void log(@NonNull LogLevel logLevel, @NonNull IProtoLogGroup group,
            @NonNull String messageString, @NonNull Object... args) {
        try {
            log(logLevel, group, new Message(messageString), args);
        } catch (InvalidFormatStringException e) {
            Slog.e(LOG_TAG, "Invalid protolog string format", e);
            log(logLevel, group, new Message("INVALID MESSAGE"), new Object[0]);
        }
    }

    /**
     * SLog wrapper.
     */
    @VisibleForTesting
    public void passToLogcat(
            @NonNull String tag, @NonNull LogLevel level, @NonNull String message) {
        switch (level) {
            case DEBUG:
                Slog.d(tag, message);
                break;
            case VERBOSE:
                Slog.v(tag, message);
                break;
            case INFO:
                Slog.i(tag, message);
                break;
            case WARN:
                Slog.w(tag, message);
                break;
            case ERROR:
                Slog.e(tag, message);
                break;
            case WTF:
                Slog.wtf(tag, message);
                break;
        }
    }

    /**
     * Returns {@code true} iff logging to proto is enabled.
     */
    public boolean isProtoEnabled() {
        return mTracingInstances.get() > 0;
    }

    @Override
    public void toggleLogcat(boolean enabled, @NonNull String[] groups) {
        if (android.tracing.Flags.javaNativeProtolog()) {
            throw new RuntimeException("toggleLogcat should not be called on PerfettoProtoLogImpl"
                    + " when javaNativeProtolog flag is enabled");
        }

        final ILogger logger = (message) -> Log.d(LOG_TAG, message);
        if (enabled) {
            startLoggingToLogcat(groups, logger);
        } else {
            stopLoggingToLogcat(groups, logger);
        }
    }

    /**
     * Start text logging
     * @param groups Groups to start text logging for
     * @param logger A logger to write status updates to
     * @return status code
     */
    public int startLoggingToLogcat(@NonNull String[] groups, @NonNull ILogger logger) {
        return setTextLogging(true, logger, groups);
    }

    /**
     * Stop text logging
     * @param groups Groups to start text logging for
     * @param logger A logger to write status updates to
     * @return status code
     */
    public int stopLoggingToLogcat(@NonNull String[] groups, @NonNull ILogger logger) {
        return setTextLogging(false, logger, groups);
    }

    @Override
    public boolean isEnabled(@NonNull IProtoLogGroup group, @NonNull LogLevel level) {
        if (mAsyncInitInProgress) {
            // Post everything to the background thread to be traced if we are in the
            // mAsyncInitInProgress state. This means we might capture more data than intended but
            // at least we will not lose anything. It's only the case we will have more data if we
            // start a tracing instance between the time we call ProtoLog.init() and the time the
            // datasource is registered in the background handler thread.
            return true;
        }

        var readLock = mConfigUpdaterLock.readLock();
        readLock.lock();
        try {
            final int[] groupLevelCount = mLogLevelCounts.get(group.name());
            return (groupLevelCount == null && mDefaultLogLevelCounts[level.ordinal()] > 0)
                    || (groupLevelCount != null && groupLevelCount[level.ordinal()] > 0)
                    || group.isLogToLogcat();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    @NonNull
    public List<IProtoLogGroup> getRegisteredGroups() {
        synchronized (mLogGroupsLock) {
            return List.copyOf(mLogGroups.values());
        }
    }

    private void registerGroupsLocally(@NonNull IProtoLogGroup[] protoLogGroups) {
        synchronized (mLogGroupsLock) {
            // Verify we don't have id collisions, if we do we want to know as soon as possible and
            // we might want to manually specify an id for the group with a collision
            IProtoLogGroup[] allGroups = Stream.concat(
                    mLogGroups.values().stream(),
                    Arrays.stream(protoLogGroups)
            ).toArray(IProtoLogGroup[]::new);
            verifyNoCollisionsOrDuplicates(allGroups);

            for (IProtoLogGroup protoLogGroup : protoLogGroups) {
                mLogGroups.put(protoLogGroup.name(), protoLogGroup);
            }
        }
    }

    private void verifyNoCollisionsOrDuplicates(@NonNull IProtoLogGroup[] protoLogGroups) {
        final var groupId = new ArraySet<Integer>();

        for (IProtoLogGroup protoLogGroup : protoLogGroups) {
            if (groupId.contains(protoLogGroup.getId())) {
                throw new RuntimeException(
                        "Group with same id (" + protoLogGroup.getId() + ") registered twice. "
                                + "Potential duplicate or hash id collision.");
            }
            groupId.add(protoLogGroup.getId());
        }
    }

    protected void readyToLogToLogcat() {
        mLogcatReady = true;
    }

    /**
     * Responds to a shell command.
     */
    @Deprecated
    public int onShellCommand(@NonNull ShellCommand shell) {
        PrintWriter pw = shell.getOutPrintWriter();
        pw.println("Command deprecated. Please use 'cmd protolog_configuration' instead.");
        return -1;
    }

    private void log(@NonNull LogLevel logLevel, @NonNull IProtoLogGroup group,
            @NonNull Message message, @Nullable Object[] args) {
        if (isProtoEnabled() || mAsyncInitInProgress) {
            long tsNanos = SystemClock.elapsedRealtimeNanos();
            final String stacktrace;
            if (logLevel == LogLevel.WTF
                    || mCollectStackTraceGroupCounts.getOrDefault(group.name(), 0) > 0) {
                stacktrace = collectStackTrace();
            } else {
                stacktrace = null;
            }

            // This is to avoid passing args that are mutable to the background thread, which might
            // cause issues with concurrent access to the same object.
            if (args != null) {
                snapshotMutableArgsToStringInPlace(args);
            }

            Runnable traceInBackground = () -> {
                try {
                    logToProto(logLevel, group, message, args, tsNanos, stacktrace);
                } catch (RuntimeException e) {
                    // An error occurred during the logging process itself.
                    // Log this error along with information about the original log call.
                    final var sb = new StringBuilder();
                    sb.append("Failed to log to ProtoLog for ");

                    if (message.mMessageString != null) {
                        sb.append("message: \"").append(message.mMessageString).append("\"");
                    } else if (message.mMessageHash != null) {
                        sb.append("message with hash: ").append(message.mMessageHash);
                    } else {
                        sb.append("message: (info unavailable)");
                    }

                    if (stacktrace != null && !stacktrace.isEmpty()) {
                        sb.append("\nOriginal Call Site Stack Trace:\n");
                        for (String line : stacktrace.split("\n")) {
                            sb.append("    ").append(line).append("\n");
                        }
                    }

                    Log.wtf(LOG_TAG, sb.toString(), e);
                }
            };

            mSingleThreadedExecutor.execute(traceInBackground);
        }
        if (group.isLogToLogcat()) {
            logToLogcat(group.getTag(), logLevel, message, args);
        }
    }

    private void snapshotMutableArgsToStringInPlace(@NonNull Object[] args) {
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg != null && !(arg instanceof Number) && !(arg instanceof Boolean)) {
                args[i] = arg.toString();
            }
        }
    }

    @Override
    public void onTracingFlush() {
        Log.d(LOG_TAG, "Executing onTracingFlush");
        waitForExistingBackgroundTasksToComplete();

        Log.d(LOG_TAG, "Finished onTracingFlush");
    }

    @Deprecated
    abstract void dumpViewerConfig();

    @Nullable
    protected String getViewerConfigPath() {
        return null;
    }

    @NonNull
    abstract String getLogcatMessageString(@NonNull Message message);

    private void logToLogcat(@NonNull String tag, @NonNull LogLevel level, @NonNull Message message,
            @Nullable Object[] args) {
        if (!mLogcatReady) {
            Log.w(LOG_TAG, "Trying to log a protolog message with hash "
                    + message.getMessageHash() + " to logcat before the service is ready to accept "
                    + "such requests.");
            return;
        }

        String messageString = getLogcatMessageString(message);
        logToLogcat(tag, level, messageString, args);
    }

    private void logToLogcat(@NonNull String tag, @NonNull LogLevel level,
            @NonNull String messageString, @Nullable Object[] args) {
        String message;
        if (args != null) {
            try {
                message = TextUtils.formatSimple(messageString, args);
            } catch (IllegalArgumentException e) {
                message = "FORMAT_ERROR \"" + messageString + "\", args=("
                        + String.join(
                        ", ", Arrays.stream(args).map(Object::toString).toList()) + ")";
            }
        } else {
            message = messageString;
        }
        passToLogcat(tag, level, message);
    }

    private void logToProto(@NonNull LogLevel level, @NonNull IProtoLogGroup logGroup,
            @NonNull Message message, @Nullable Object[] args, long tsNanos,
            @Nullable String stacktrace) {
        mDataSource.trace(ctx -> {
            // Ensures the message we are logging here was added to the execution queue after this
            // tracing instance was started and before it was stopped.
            if (!mActiveTracingInstances.contains(ctx.getInstanceIndex())) {
                return;
            }

            final ProtoLogDataSource.TlsState tlsState = ctx.getCustomTlsState();
            final LogLevel logFrom = tlsState.getLogFromLevel(logGroup.name());

            if (level.ordinal() < logFrom.ordinal()) {
                return;
            }

            boolean needsIncrementalState = false;

            if (args != null) {
                // Intern all string params before creating the trace packet for the proto
                // message so that the interned strings appear before in the trace to make the
                // trace processing easier.
                int argIndex = 0;
                for (Object o : args) {
                    long type =
                            LogDataType.bitmaskToLogDataType(
                                    message.getMessageMask(), argIndex);
                    if (type == LogDataType.STRING) {
                        needsIncrementalState = true;
                        if (o == null) {
                            internStringArg(ctx, NULL_STRING);
                        } else {
                            internStringArg(ctx, o.toString());
                        }
                    }
                    argIndex++;
                }
            }

            int internedStacktrace = 0;
            if (tlsState.getShouldCollectStacktrace(logGroup.name())) {
                // Intern stackstraces before creating the trace packet for the proto message so
                // that the interned stacktrace strings appear before in the trace to make the
                // trace processing easier.
                needsIncrementalState = true;
                internedStacktrace = internStacktraceString(ctx, stacktrace);
            }

            long messageHash = 0;
            if (message.mMessageHash != null) {
                messageHash = message.mMessageHash;
            }
            if (message.mMessageString != null) {
                needsIncrementalState = true;
                messageHash =
                        internProtoMessage(ctx, level, logGroup, message.mMessageString);
            }

            final ProtoOutputStream os = ctx.newTracePacket(32);
            os.write(TIMESTAMP, tsNanos);
            long token = os.start(PROTOLOG_MESSAGE);

            os.write(MESSAGE_ID, messageHash);

            if (args != null) {

                int argIndex = 0;
                for (Object o : args) {
                    int type = LogDataType.bitmaskToLogDataType(message.getMessageMask(), argIndex);
                    try {
                        switch (type) {
                            case LogDataType.STRING:
                                final int internedStringId;
                                if (o == null) {
                                    internedStringId = internStringArg(ctx, NULL_STRING);
                                } else {
                                    internedStringId = internStringArg(ctx, o.toString());
                                }
                                os.write(STR_PARAM_IIDS, internedStringId);
                                needsIncrementalState = true;
                                break;
                            case LogDataType.LONG:
                                if (o == null) {
                                    os.write(SINT64_PARAMS, 0);
                                } else {
                                    os.write(SINT64_PARAMS, ((Number) o).longValue());
                                }
                                break;
                            case LogDataType.DOUBLE:
                                if (o == null) {
                                    os.write(DOUBLE_PARAMS, 0d);
                                } else {
                                    os.write(DOUBLE_PARAMS, ((Number) o).doubleValue());
                                }
                                break;
                            case LogDataType.BOOLEAN:
                                // Converting booleans to int because Perfetto doesn't yet support
                                // repeated booleans, so we use a repeated integers instead.
                                // (b/313651412)
                                if (o == null) {
                                    os.write(BOOLEAN_PARAMS, 0);
                                } else {
                                    os.write(BOOLEAN_PARAMS, (boolean) o ? 1 : 0);
                                }
                                break;
                        }
                    } catch (ClassCastException ex) {
                        Slog.e(LOG_TAG, "Invalid ProtoLog paramsMask", ex);
                    }
                    argIndex++;
                }
            }

            if (tlsState.getShouldCollectStacktrace(logGroup.name())) {
                os.write(STACKTRACE_IID, internedStacktrace);
            }

            os.end(token);

            if (needsIncrementalState) {
                os.write(SEQUENCE_FLAGS, SEQ_NEEDS_INCREMENTAL_STATE);
            }

        });
    }

    private long internProtoMessage(
            @NonNull TracingContext<ProtoLogDataSource.Instance, ProtoLogDataSource.TlsState,
                    ProtoLogDataSource.IncrementalState> ctx, @NonNull LogLevel level,
            @NonNull IProtoLogGroup logGroup, @NonNull String message) {
        final ProtoLogDataSource.IncrementalState incrementalState = ctx.getIncrementalState();

        final Long messageHash = hash(level, logGroup.name(), message);

        if (android.tracing.Flags.protologAutoClearIncrementalState()
                && !incrementalState.protologMessageInterningSet.contains(messageHash)) {
            final boolean sizeThresholdReached =
                    incrementalState.internedStringsSizeBytes + message.length()
                            >= MAX_INTERNED_STRINGS_SIZE_BYTES_BEFORE_RESET;
            if (sizeThresholdReached) {
                incrementalState.reset();
            }
        }

        if (!incrementalState.clearReported) {
            final ProtoOutputStream os = ctx.newTracePacket(8);
            os.write(SEQUENCE_FLAGS, SEQ_INCREMENTAL_STATE_CLEARED);
            incrementalState.clearReported = true;
        }


        if (!incrementalState.protologGroupInterningSet.contains(logGroup.getId())) {
            incrementalState.protologGroupInterningSet.add(logGroup.getId());

            final ProtoOutputStream os = ctx.newTracePacket(64);
            final long protologViewerConfigToken = os.start(PROTOLOG_VIEWER_CONFIG);
            final long groupConfigToken = os.start(GROUPS);

            os.write(ID, logGroup.getId());
            os.write(NAME, logGroup.name());
            os.write(TAG, logGroup.getTag());

            os.end(groupConfigToken);
            os.end(protologViewerConfigToken);
        }

        if (!incrementalState.protologMessageInterningSet.contains(messageHash)) {
            incrementalState.protologMessageInterningSet.add(messageHash);
            incrementalState.internedStringsSizeBytes += message.length();

            final ProtoOutputStream os = ctx.newTracePacket(128);

            // Dependent on the ProtoLog viewer config packet that contains the group information.
            os.write(SEQUENCE_FLAGS, SEQ_NEEDS_INCREMENTAL_STATE);

            final long protologViewerConfigToken = os.start(PROTOLOG_VIEWER_CONFIG);
            final long messageConfigToken = os.start(MESSAGES);

            os.write(MessageData.MESSAGE_ID, messageHash);
            os.write(MESSAGE, message);
            os.write(LEVEL, level.protoMessageId);
            os.write(GROUP_ID, logGroup.getId());

            os.end(messageConfigToken);
            os.end(protologViewerConfigToken);
        }

        return messageHash;
    }

    private Long hash(
            @NonNull LogLevel logLevel,
            @NonNull String logGroup,
            @NonNull String messageString
    ) {
        final String fullStringIdentifier =  messageString + logLevel + logGroup;
        return UUID.nameUUIDFromBytes(fullStringIdentifier.getBytes()).getMostSignificantBits();
    }

    private static final int STACK_SIZE_TO_PROTO_LOG_ENTRY_CALL = 6;

    @NonNull
    private String collectStackTrace() {
        StackTraceElement[] stackTrace =  Thread.currentThread().getStackTrace();
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            for (int i = STACK_SIZE_TO_PROTO_LOG_ENTRY_CALL; i < stackTrace.length; ++i) {
                pw.println("\tat " + stackTrace[i]);
            }
        }

        return sw.toString();
    }

    private int internStacktraceString(@NonNull TracingContext<
            ProtoLogDataSource.Instance,
            ProtoLogDataSource.TlsState,
            ProtoLogDataSource.IncrementalState> ctx,
            @NonNull String stacktrace) {
        final ProtoLogDataSource.IncrementalState incrementalState = ctx.getIncrementalState();
        return internString(ctx, incrementalState.stacktraceInterningMap,
                PROTOLOG_STACKTRACE, stacktrace);
    }

    private int internStringArg(
            @NonNull TracingContext<ProtoLogDataSource.Instance, ProtoLogDataSource.TlsState,
                    ProtoLogDataSource.IncrementalState> ctx,
            @NonNull String string
    ) {
        final ProtoLogDataSource.IncrementalState incrementalState = ctx.getIncrementalState();
        return internString(ctx, incrementalState.argumentInterningMap,
                PROTOLOG_STRING_ARGS, string);
    }

    private int internString(
            @NonNull TracingContext<ProtoLogDataSource.Instance, ProtoLogDataSource.TlsState,
                    ProtoLogDataSource.IncrementalState> ctx,
            @NonNull Map<String, Integer> internMap,
            long fieldId,
            @NonNull String string
    ) {
        final ProtoLogDataSource.IncrementalState incrementalState = ctx.getIncrementalState();

        if (android.tracing.Flags.protologAutoClearIncrementalState()
                && !internMap.containsKey(string)) {
            final boolean sizeThresholdReached =
                    incrementalState.internedStringsSizeBytes + string.length()
                            >= MAX_INTERNED_STRINGS_SIZE_BYTES_BEFORE_RESET;
            if (sizeThresholdReached) {
                incrementalState.reset();
            }
        }

        if (!incrementalState.clearReported) {
            final ProtoOutputStream os = ctx.newTracePacket(8);
            os.write(SEQUENCE_FLAGS, SEQ_INCREMENTAL_STATE_CLEARED);
            incrementalState.clearReported = true;
        }

        if (!internMap.containsKey(string)) {
            final int internedIndex = internMap.size() + 1;
            internMap.put(string, internedIndex);
            incrementalState.internedStringsSizeBytes += string.length();

            final ProtoOutputStream os = ctx.newTracePacket(64);
            final long token = os.start(INTERNED_DATA);
            final long innerToken = os.start(fieldId);
            os.write(IID, internedIndex);
            os.write(STR, string.getBytes());
            os.end(innerToken);
            os.end(token);
        }

        return internMap.get(string);
    }

    protected boolean validateGroups(@NonNull ILogger logger, @NonNull String[] groups) {
        synchronized (mLogGroupsLock) {
            for (int i = 0; i < groups.length; i++) {
                String group = groups[i];
                IProtoLogGroup g = mLogGroups.get(group);
                if (g == null) {
                    logger.log("No IProtoLogGroup named " + group);
                    return false;
                }
            }
        }
        return true;
    }

    private int setTextLogging(boolean value, @NonNull ILogger logger, @NonNull String... groups) {
        if (!validateGroups(logger, groups)) {
            return -1;
        }

        synchronized (mLogGroupsLock) {
            for (int i = 0; i < groups.length; i++) {
                String group = groups[i];
                IProtoLogGroup g = mLogGroups.get(group);
                if (g != null) {
                    g.setLogToLogcat(value);
                } else {
                    throw new RuntimeException("No IProtoLogGroup named " + group);
                }
            }
        }

        mCacheUpdater.update(this);
        return 0;
    }

    private int unknownCommand(@NonNull PrintWriter pw) {
        pw.println("Unknown command");
        pw.println("Window manager logging options:");
        pw.println("  enable-text [group...]: Enable logcat logging for given groups");
        pw.println("  disable-text [group...]: Disable logcat logging for given groups");
        return -1;
    }

    @Override
    public void onTracingInstanceStart(
            int instanceIdx, @NonNull ProtoLogDataSource.ProtoLogConfig config) {
        Log.d(LOG_TAG, "Executing onTracingInstanceStart");

        var writeLock = mConfigUpdaterLock.writeLock();
        writeLock.lock();
        try {
            onTracingInstanceStartLocked(config);
        } finally {
            writeLock.unlock();
        }

        // It is crucial to add the instanceIdx to mActiveTracingInstances via the
        // mSingleThreadedExecutor. This ensures that this operation is enqueued
        // and executed *after* any log messages that were submitted *before* this
        // tracing instance started. The check for mActiveTracingInstances.contains(instanceIdx)
        // happens within the logToProto method (which also runs on mSingleThreadedExecutor).
        // If we added instanceIdx directly, log messages already in the queue could be
        // incorrectly attributed to this new tracing session.
        queueTracingInstanceAddition(instanceIdx);

        // Must be called after all setup is queued.
        mFirstTracingInstanceStartLatch.countDown();
        Log.d(LOG_TAG, "Finished onTracingInstanceStart");
    }

    private void queueTracingInstanceAddition(int instanceIdx) {
        final Runnable r = () -> mActiveTracingInstances.add(instanceIdx);
        if (mAsyncInitInProgress) {
            // Push to the front of the queue to make sure this is executes immediately
            // before any queued up tracing. To make sure we don't miss any logs.
            mExecutorQueue.addFirst(r);
        } else {
            mSingleThreadedExecutor.execute(r);
        }
    }

    private void onTracingInstanceStartLocked(@NonNull ProtoLogDataSource.ProtoLogConfig config) {
        final LogLevel defaultLogFrom = config.getDefaultGroupConfig().logFrom;
        for (int i = defaultLogFrom.ordinal(); i < LogLevel.values().length; i++) {
            mDefaultLogLevelCounts[i]++;
        }

        final Set<String> overriddenGroupTags = config.getGroupTagsWithOverriddenConfigs();

        for (String overriddenGroupTag : overriddenGroupTags) {
            mLogLevelCounts.putIfAbsent(overriddenGroupTag, new int[LogLevel.values().length]);
            final int[] logLevelsCountsForGroup = mLogLevelCounts.get(overriddenGroupTag);

            final LogLevel logFromLevel = config.getConfigFor(overriddenGroupTag).logFrom;
            for (int i = logFromLevel.ordinal(); i < LogLevel.values().length; i++) {
                logLevelsCountsForGroup[i]++;
            }

            if (config.getConfigFor(overriddenGroupTag).collectStackTrace) {
                mCollectStackTraceGroupCounts.put(overriddenGroupTag,
                        mCollectStackTraceGroupCounts.getOrDefault(overriddenGroupTag, 0) + 1);
            }
        }

        mCacheUpdater.update(this);

        this.mTracingInstances.incrementAndGet();
    }

    @Override
    public synchronized void onTracingInstanceStop(
            int instanceIdx, @NonNull ProtoLogDataSource.ProtoLogConfig config) {
        Log.d(LOG_TAG, "Executing onTracingInstanceStop");

        // Similar to onTracingInstanceStart, it's crucial to remove the instanceIdx
        // via the mSingleThreadedExecutor. This ensures that the removal happens
        // *after* all log messages enqueued *before* this tracing instance was stopped
        // have been processed and had a chance to be included in this trace.
        // If we removed instanceIdx directly, log messages still in the queue that
        // belong to this session might be dropped.
        queueTracingInstanceRemoval(instanceIdx);

        var writeLock = mConfigUpdaterLock.writeLock();
        writeLock.lock();
        try {
            onTracingInstanceStopLocked(config);
        } finally {
            writeLock.unlock();
        }

        Log.d(LOG_TAG, "Finished onTracingInstanceStop");
    }

    private void queueTracingInstanceRemoval(int instanceIdx) {
        mSingleThreadedExecutor.execute(() -> mActiveTracingInstances.remove(instanceIdx));
    }

    private void onTracingInstanceStopLocked(@NonNull ProtoLogDataSource.ProtoLogConfig config) {
        this.mTracingInstances.decrementAndGet();

        final LogLevel defaultLogFrom = config.getDefaultGroupConfig().logFrom;
        for (int i = defaultLogFrom.ordinal(); i < LogLevel.values().length; i++) {
            mDefaultLogLevelCounts[i]--;
        }

        final Set<String> overriddenGroupTags = config.getGroupTagsWithOverriddenConfigs();

        for (String overriddenGroupTag : overriddenGroupTags) {
            final int[] logLevelsCountsForGroup = mLogLevelCounts.get(overriddenGroupTag);

            final LogLevel logFromLevel = config.getConfigFor(overriddenGroupTag).logFrom;
            for (int i = logFromLevel.ordinal(); i < LogLevel.values().length; i++) {
                logLevelsCountsForGroup[i]--;
            }
            if (Arrays.stream(logLevelsCountsForGroup).allMatch(it -> it == 0)) {
                mLogLevelCounts.remove(overriddenGroupTag);
            }

            if (config.getConfigFor(overriddenGroupTag).collectStackTrace) {
                mCollectStackTraceGroupCounts.put(overriddenGroupTag,
                        mCollectStackTraceGroupCounts.get(overriddenGroupTag) - 1);

                if (mCollectStackTraceGroupCounts.get(overriddenGroupTag) == 0) {
                    mCollectStackTraceGroupCounts.remove(overriddenGroupTag);
                }
            }
        }

        mCacheUpdater.update(this);
    }

    private static void logAndPrintln(@Nullable PrintWriter pw, @NonNull String msg) {
        Slog.i(LOG_TAG, msg);
        if (pw != null) {
            pw.println(msg);
            pw.flush();
        }
    }

    protected static class Message {
        @Nullable
        private final Long mMessageHash;
        private final long mMessageMask;
        @Nullable
        private final String mMessageString;

        private Message(long messageHash, long messageMask) {
            this.mMessageHash = messageHash;
            this.mMessageMask = messageMask;
            this.mMessageString = null;
        }

        private Message(@NonNull String messageString) throws InvalidFormatStringException {
            this.mMessageHash = null;
            final List<Integer> argTypes = LogDataType.parseFormatString(messageString);
            this.mMessageMask = LogDataType.logDataTypesToBitMask(argTypes);
            this.mMessageString = messageString;
        }

        private long getMessageMask() {
            return mMessageMask;
        }

        @Nullable
        protected Long getMessageHash() {
            return mMessageHash;
        }

        @Nullable
        protected String getMessage() {
            return mMessageString;
        }

        @Nullable
        protected String getMessage(@NonNull ProtoLogViewerConfigReader viewerConfigReader) {
            if (mMessageString != null) {
                return mMessageString;
            }

            if (mMessageHash != null) {
                return viewerConfigReader.getViewerString(mMessageHash);
            }

            throw new RuntimeException("Both mMessageString and mMessageHash should never be null");
        }
    }

    private void waitForExistingBackgroundTasksToComplete() {
        try {
            this.mSingleThreadedExecutor.submit(() -> {
                Log.i(LOG_TAG, "Completed all pending background tasks");
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            Log.wtf(LOG_TAG, "Failed to wait for tracing service background tasks to complete", e);
        }
    }

    /**
     * This is only used by unit tests to wait until {@link #connectToConfigurationServiceAsync} is
     * done. Because unit tests are sensitive to concurrent accesses.
     */
    @VisibleForTesting
    public static void waitForInitialization() {
        final IProtoLog currentInstance = ProtoLog.getSingleInstance();
        if (!(currentInstance instanceof PerfettoProtoLogImpl protoLog)) {
            return;
        }

        protoLog.waitForExistingBackgroundTasksToComplete();
    }
}

