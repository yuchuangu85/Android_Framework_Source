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

import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.GROUPS;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.Group.ID;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.Group.NAME;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.Group.TAG;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MESSAGES;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.GROUP_ID;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.LEVEL;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.LOCATION;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.MESSAGE;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.MESSAGE_ID;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.util.ArraySet;
import android.util.Log;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The ProtoLog service is responsible for orchestrating centralized actions of the protolog tracing
 * system. Currently this service has the following roles:
 * - Handle shell commands to toggle logging ProtoLog messages for specified groups to logcat.
 * - Handle viewer config dumping (the mapping from message hash to message string) for all protolog
 *   clients. This is for two reasons: firstly, because client processes might be frozen so might
 *   not response to the request to dump their viewer config when the trace is stopped; secondly,
 *   multiple processes might be running the same code with the same viewer config, this centralized
 *   service ensures we don't dump the same viewer config multiple times across processes.
 * <p>
 * {@link com.android.internal.protolog.IProtoLogClient ProtoLog clients} register themselves to
 * this service on initialization.
 * <p>
 * This service is intended to run on the system server, such that it never gets frozen.
 */
@SystemService(Context.PROTOLOG_CONFIGURATION_SERVICE)
public class ProtoLogConfigurationServiceImpl extends IProtoLogConfigurationService.Stub
        implements ProtoLogConfigurationService, IBinder.DeathRecipient {
    private static final String LOG_TAG = "ProtoLogConfigurationService";

    private final ProtoLogDataSource mDataSource;

    /**
     * Lock for synchronizing access to {@link #mConfigFileCounts}, {@link #mRegisteredGroups},
     * {@link #mClientRecords}, {@link #mLogGroupToLogcatStatus}, and {@link ClientRecord#groups}.
     */
    private final Object mConfigLock = new Object();

    /**
     * Keeps track of how many of each viewer config file is currently registered.
     * Use to keep track of which viewer config files are actively being used in tracing and might
     * need to be dumped on flush.
     */
    @GuardedBy("mConfigLock")
    private final Map<String, Integer> mConfigFileCounts = new HashMap<>();

    /**
     * Container for data about a {@link IProtoLogClient} that needs to get cleaned up when the
     * client goes away.
     */
    private static final class ClientRecord {
        /** Immutable Binder.Stub for communication with the client. */
        @NonNull
        public final IProtoLogClient client;

        /** Immutable name of the viewer config file of each client if available. */
        @Nullable
        public final String configFile;

        /**
         * Mutable set of ProtoLog groups registered for this client to actively trace.
         */
        @GuardedBy("mConfigLock")
        @NonNull
        public final Set<String> groups = new ArraySet<>();

        public ClientRecord(@NonNull IProtoLogClient client, @Nullable String configFile) {
            this.client = client;
            this.configFile = configFile;
        }
    }

    /**
     * Keeps track of all the clients that are actively tracing.
     */
    @GuardedBy("mConfigLock")
    private final Map<IBinder, ClientRecord> mClientRecords = new HashMap<>();

    /**
     * Keeps track of all the protolog groups that have been registered by clients and are still
     * being actively traced.
     */
    @GuardedBy("mConfigLock")
    private final Set<String> mRegisteredGroups = new HashSet<>();

    /**
     * Keeps track of whether or not a given group should be logged to logcat.
     * True when logging to logcat, false otherwise.
     */
    @GuardedBy("mConfigLock")
    private final Map<String, Boolean> mLogGroupToLogcatStatus = new TreeMap<>();

    /**
     * Keeps track of all the tracing instance ids that are actively running for ProtoLog.
     */
    private final Set<Integer> mRunningInstances = new HashSet<>();

    private final ViewerConfigFileTracer mViewerConfigFileTracer;

    public ProtoLogConfigurationServiceImpl() {
        this(ProtoLog.getSharedSingleInstanceDataSource(),
                ProtoLogConfigurationServiceImpl::dumpViewerConfig);
    }

    @VisibleForTesting
    public ProtoLogConfigurationServiceImpl(@NonNull ProtoLogDataSource datasource) {
        this(datasource, ProtoLogConfigurationServiceImpl::dumpViewerConfig);
    }

    @VisibleForTesting
    public ProtoLogConfigurationServiceImpl(@NonNull ViewerConfigFileTracer tracer) {
        this(ProtoLog.getSharedSingleInstanceDataSource(), tracer);
    }

    @VisibleForTesting
    public ProtoLogConfigurationServiceImpl(
            @NonNull ProtoLogDataSource datasource,
            @NonNull ViewerConfigFileTracer tracer) {
        mViewerConfigFileTracer = tracer;

        datasource.registerOnStartCallback(this::onTracingInstanceStart);
        datasource.registerOnFlushCallback(this::onTracingInstanceFlush);
        datasource.registerOnStopCallback(this::onTracingInstanceStop);

        mDataSource = datasource;
    }

    @FunctionalInterface
    public interface ViewerConfigFileTracer {
        /**
         * Write the viewer config data to the trace buffer.
         *
         * @param dataSource The target datasource to write the viewer config to.
         * @param viewerConfigFilePath The path of the viewer config file which contains the data we
         *                             want to write to the trace buffer.
         * @throws FileNotFoundException if the viewerConfigFilePath is invalid.
         */
        void trace(@NonNull ProtoLogDataSource dataSource, @NonNull String viewerConfigFilePath);
    }

    @Override
    public void registerClient(@NonNull IProtoLogClient client, @NonNull RegisterClientArgs args)
            throws RemoteException {
        final IBinder clientBinder = client.asBinder();

        final String viewerConfigFile = args.viewerConfigFile;

        synchronized (mConfigLock) {
            mClientRecords.put(clientBinder, new ClientRecord(client, viewerConfigFile));

            if (viewerConfigFile != null) {
                mConfigFileCounts.put(viewerConfigFile,
                        mConfigFileCounts.getOrDefault(viewerConfigFile, 0) + 1);
            }

            registerGroupsLocked(client, args.groups, args.groupsDefaultLogcatStatus);
        }

        clientBinder.linkToDeath(this, /* flags= */ 0);
    }

    @Override
    public void registerGroups(@NonNull IProtoLogClient client, @NonNull RegisterGroupsArgs args)
            throws RemoteException {
        synchronized (mConfigLock) {
            registerGroupsLocked(client, args.groups, args.groupsDefaultLogcatStatus);
        }
    }

    /**
     * Unregister the {@code client}.
     */
    @Override
    public void unregisterClient(@Nullable IProtoLogClient client) {
        if (client == null) {
            return;
        }

        final IBinder clientBinder = client.asBinder();
        if (clientBinder != null) {
            clientBinder.unlinkToDeath(this, /* flags= */ 0);
        }

        // Retrieve the client record for cleanup.
        final ClientRecord clientRecord;
        boolean dumpViewerConfig = false;
        synchronized (mConfigLock) {
            clientRecord = mClientRecords.remove(clientBinder);
            if (clientRecord == null) {
                return;
            }

            if (clientRecord.configFile != null) {
                final var newCount = mConfigFileCounts.get(clientRecord.configFile) - 1;
                mConfigFileCounts.put(clientRecord.configFile, newCount);

                if (newCount == 0) {
                    mConfigFileCounts.remove(clientRecord.configFile);
                    dumpViewerConfig = true;
                }
            }
        }

        // Dump the tracing config now if no other client is going to dump the same config file.
        if (dumpViewerConfig) {
            mViewerConfigFileTracer.trace(mDataSource, clientRecord.configFile);
        }
    }

    @Override
    public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
            @Nullable FileDescriptor err, @NonNull String[] args, @Nullable ShellCallback callback,
            @NonNull ResultReceiver resultReceiver) throws RemoteException {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.ROOT_UID && callingUid != Process.SHELL_UID) {
            resultReceiver.send(-1, null);
            throw new SecurityException("Shell commands are only callable by ADB");
        }

        new ProtoLogCommandHandler(this)
                .exec(this, in, out, err, args, callback, resultReceiver);
    }

    /**
     * Get the list of groups clients have registered to the protolog service.
     * @return The list of ProtoLog groups registered with this service.
     */
    @Override
    @NonNull
    public String[] getGroups() {
        synchronized (mConfigLock) {
            return mRegisteredGroups.toArray(new String[0]);
        }
    }

    /**
     * Enable logging target groups to logcat.
     * @param groups we want to enable logging them to logcat for.
     */
    @Override
    public void enableProtoLogToLogcat(@NonNull PrintWriter pw, @NonNull String... groups) {
        toggleProtoLogToLogcat(pw, true, groups);
    }

    /**
     * Disable logging target groups to logcat.
     * @param groups we want to disable from being logged to logcat.
     */
    @Override
    public void disableProtoLogToLogcat(@NonNull PrintWriter pw, @NonNull String... groups) {
        toggleProtoLogToLogcat(pw, false, groups);
    }

    /**
     * Check if a group is logging to logcat
     * @param group The group we want to check for
     * @return True iff we are logging this group to logcat.
     */
    @Override
    public boolean isLoggingToLogcat(@NonNull String group) {
        final Boolean isLoggingToLogcat;
        synchronized (mConfigLock) {
            isLoggingToLogcat = mLogGroupToLogcatStatus.get(group);
        }

        if (isLoggingToLogcat == null) {
            throw new RuntimeException(
                    "Trying to get logcat logging status of non-registered group " + group);
        }

        return isLoggingToLogcat;
    }

    /**
     * Legacy method (no longer called) inherited from {@link IBinder.DeathRecipient}.
     *
     * Because the method is non-default, it has to be implemented, but the newer version taking an
     * IBinder will always be called instead.
     */
    public void binderDied() {
    }

    /**
     * Unregister client when its owner dies - inherited from {@link IBinder.DeathRecipient}
     */
    @Override
    public void binderDied(@NonNull IBinder clientBinder) {
        unregisterClient(IProtoLogClient.Stub.asInterface(clientBinder));
    }

    @GuardedBy("mConfigLock")
    private void registerGroupsLocked(@NonNull IProtoLogClient client, @NonNull String[] groups,
            @NonNull boolean[] logcatStatuses) throws RemoteException {
        if (groups.length != logcatStatuses.length) {
            throw new RuntimeException(
                    "Expected groups and logcatStatuses to have the same length, "
                        + "but groups has length " + groups.length
                        + " and logcatStatuses has length " + logcatStatuses.length);
        }

        final var clientRecord = mClientRecords.get(client.asBinder());
        if (clientRecord == null) {
            Log.wtfStack(LOG_TAG, "Trying to add groups to unregistered client: " + client);
            return;
        }

        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            boolean logcatStatus = logcatStatuses[i];

            final boolean requestedLogToLogcat;
            mRegisteredGroups.add(group);
            clientRecord.groups.add(group);

            mLogGroupToLogcatStatus.putIfAbsent(group, logcatStatus);
            requestedLogToLogcat = mLogGroupToLogcatStatus.get(group);

            if (requestedLogToLogcat != logcatStatus) {
                client.toggleLogcat(requestedLogToLogcat, new String[]{group});
            }
        }
    }

    private void toggleProtoLogToLogcat(
            @NonNull PrintWriter pw, boolean enabled, @NonNull String[] groups
    ) {
        // For each client, if its groups intersect the given list, send the command to toggle.
        synchronized (mConfigLock) {
            final String[] groupsToToggle;
            if (groups.length == 0) {
                groupsToToggle = mRegisteredGroups.toArray(new String[0]);
            } else {
                groupsToToggle = groups;
            }

            for (var clientRecord : mClientRecords.values()) {
                final ArraySet<String> affectedGroups;
                affectedGroups = new ArraySet<>(clientRecord.groups);
                affectedGroups.retainAll(Arrays.asList(groupsToToggle));

                if (!affectedGroups.isEmpty()) {
                    final var clientGroups = affectedGroups.toArray(new String[0]);
                    try {
                        pw.println("Toggling logcat logging for client " + clientRecord.client
                                + " to " + enabled + " for groups: ["
                                + String.join(", ", clientGroups) + "]");
                        clientRecord.client.toggleLogcat(enabled, clientGroups);
                        pw.println("- Done");
                    } catch (DeadObjectException e) {
                        pw.println("- Failed (client may have died)");
                        Log.w(LOG_TAG, "Failed to toggle logcat status for groups on client "
                                + clientRecord.client + ", it likely has died", e);
                    } catch (RemoteException e) {
                        pw.println("- Failed (unexpected RemoteException)");
                        throw new RuntimeException(
                                "Failed to toggle logcat status for groups on client", e);
                    }
                }
            }

            // Groups that actually have no clients associated indicate some kind of a bug.
            Set<String> noOpGroups = new ArraySet<>(Arrays.asList(groupsToToggle));
            mClientRecords.forEach((k, r) -> noOpGroups.removeAll(r.groups));

            // Send out a warning in logcat and the PrintWriter for unrecognized groups.
            for (String group : noOpGroups) {
                var warning = "Attempting to toggle log to logcat for group " + group
                        + " with no registered clients. This is a no-op.";
                Log.w(LOG_TAG, warning);
                pw.println("WARNING: " + warning);
            }

            // Flip the status of the groups in our record-keeping.
            for (String group : groupsToToggle) {
                mLogGroupToLogcatStatus.put(group, enabled);
            }
        }
    }

    private void onTracingInstanceStart(int instanceIdx, ProtoLogDataSource.ProtoLogConfig config) {
        mRunningInstances.add(instanceIdx);
    }

    private void onTracingInstanceFlush() {
        final var configFilesToDump = new HashSet<String>();
        synchronized (mConfigLock) {
            for (var entry : mConfigFileCounts.entrySet()) {
                if (entry.getValue() > 0) {
                    configFilesToDump.add(entry.getKey());
                }
            }
        }

        for (var configFileName : configFilesToDump) {
            mViewerConfigFileTracer.trace(mDataSource, configFileName);
        }
    }

    private void onTracingInstanceStop(int instanceIdx, ProtoLogDataSource.ProtoLogConfig config) {
        mRunningInstances.remove(instanceIdx);
    }

    private static void dumpViewerConfig(@NonNull ProtoLogDataSource dataSource,
            @NonNull String viewerConfigFilePath) {
        Utils.dumpViewerConfig(dataSource, () -> {
            try {
                return new AutoClosableProtoInputStream(new FileInputStream(viewerConfigFilePath));
            } catch (FileNotFoundException e) {
                throw new RuntimeException(
                        "Failed to load viewer config file " + viewerConfigFilePath, e);
            }
        });
    }

    private static void writeViewerConfigGroup(
            @NonNull ProtoInputStream pis, @NonNull ProtoOutputStream os) throws IOException {
        final long inGroupToken = pis.start(GROUPS);
        final long outGroupToken = os.start(GROUPS);

        while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pis.getFieldNumber()) {
                case (int) ID -> {
                    int id = pis.readInt(ID);
                    os.write(ID, id);
                }
                case (int) NAME -> {
                    String name = pis.readString(NAME);
                    os.write(NAME, name);
                }
                case (int) TAG -> {
                    String tag = pis.readString(TAG);
                    os.write(TAG, tag);
                }
                default ->
                    throw new RuntimeException(
                            "Unexpected field id " + pis.getFieldNumber());
            }
        }

        pis.end(inGroupToken);
        os.end(outGroupToken);
    }

    private static void writeViewerConfigMessage(
            @NonNull ProtoInputStream pis, @NonNull ProtoOutputStream os) throws IOException {
        final long inMessageToken = pis.start(MESSAGES);
        final long outMessagesToken = os.start(MESSAGES);

        while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pis.getFieldNumber()) {
                case (int) MESSAGE_ID -> os.write(MESSAGE_ID,
                        pis.readLong(MESSAGE_ID));
                case (int) MESSAGE -> os.write(MESSAGE, pis.readString(MESSAGE));
                case (int) LEVEL -> os.write(LEVEL, pis.readInt(LEVEL));
                case (int) GROUP_ID -> os.write(GROUP_ID, pis.readInt(GROUP_ID));
                case (int) LOCATION -> os.write(LOCATION, pis.readString(LOCATION));
                default ->
                    throw new RuntimeException(
                            "Unexpected field id " + pis.getFieldNumber());
            }
        }

        pis.end(inMessageToken);
        os.end(outMessagesToken);
    }
}
