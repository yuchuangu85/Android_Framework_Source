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

package com.android.internal.protolog;

import static android.content.Context.PROTOLOG_CONFIGURATION_SERVICE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.protolog.common.IProtoLogGroup;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A listener for ProtoLog configuration changes, responsible for communicating with the
 * ProtoLogConfigurationService to keep logcat settings for ProtoLog groups in sync.
 */
public class ProtoLogConfigurationClient extends IProtoLogClient.Stub {
    private static final String LOG_TAG = "ProtoLogConfigListener";

    @Nullable
    private IProtoLogConfigurationService mConfigService;

    @NonNull
    private final Map<String, IProtoLogGroup> mGroups = new TreeMap<>();
    @NonNull
    private final ExecutorService mExecutor;

    @Nullable
    private final String mViewerConfigFile;
    private boolean mStarted = false;

    private final CountDownLatch mRegisteredLatch = new CountDownLatch(1);

    @NonNull
    private final LogcatToggleListener mLogcatToggleListener;

    public ProtoLogConfigurationClient(@Nullable String viewerConfigFile,
            @NonNull LogcatToggleListener logcatToggleListener) {
        this(viewerConfigFile, logcatToggleListener,
                Executors.newSingleThreadExecutor((r) -> new Thread(r, "ProtoLogConfigListener")));
    }

    public ProtoLogConfigurationClient(@Nullable String viewerConfigFile,
            @NonNull LogcatToggleListener logcatToggleListener,
            @NonNull ExecutorService executorService) {
        mViewerConfigFile = viewerConfigFile;
        mLogcatToggleListener = logcatToggleListener;
        mExecutor = executorService;
    }

    /**
     * Starts the listener, connects to the configuration service, and registers the
     * client.
     *
     * @param initialGroups The initial set of groups to register.
     * @param async         If true, initialization will happen on a background
     *                      thread.
     * @param service       An optional service to use for testing.
     */
    public void start(@NonNull IProtoLogGroup[] initialGroups, boolean async,
            @Nullable IProtoLogConfigurationService service) {
        synchronized (this) {
            if (mStarted) {
                return;
            }
            mStarted = true;
        }

        addGroups(initialGroups);

        final Runnable backgroundTasks = () -> {
            if (service != null) {
                mConfigService = service;
            } else if (mConfigService == null) {
                mConfigService = getConfigurationService();
            }

            if (mConfigService != null) {
                try {
                    IProtoLogConfigurationService.RegisterClientArgs args =
                            new IProtoLogConfigurationService.RegisterClientArgs();
                    args.viewerConfigFile = mViewerConfigFile;
                    synchronized (mGroups) {
                        args.groups = mGroups.keySet().toArray(new String[0]);
                        args.groupsDefaultLogcatStatus = new boolean[mGroups.size()];
                        int i = 0;
                        for (IProtoLogGroup group : mGroups.values()) {
                            args.groupsDefaultLogcatStatus[i] = group.isLogToLogcat();
                            i++;
                        }
                    }

                    mConfigService.registerClient(this, args);
                    mRegisteredLatch.countDown();
                } catch (RemoteException e) {
                    Log.wtf(LOG_TAG, "Failed to register ProtoLog client", e);
                }
            }
        };

        if (async) {
            mExecutor.execute(backgroundTasks);
        } else {
            backgroundTasks.run();
        }
    }

    /**
     * Stops the listener and unregisters the client from the configuration service.
     */
    public void stop() {
        if (mConfigService != null) {
            mExecutor.execute(() -> {
                try {
                    mConfigService.unregisterClient(this);
                } catch (RemoteException e) {
                    Log.wtf(LOG_TAG, "Failed to unregister ProtoLog client", e);
                }
            });
        }
        mExecutor.shutdown();
    }

    /**
     * Waits for the client to be registered with the configuration service.
     */
    public void waitForRegistration() {
        try {
            mRegisteredLatch.await();
        } catch (InterruptedException e) {
            Log.wtf(LOG_TAG, "Interrupted while waiting for registration", e);
        }
    }

    /**
     * Adds and registers new ProtoLog groups.
     *
     * @param newGroups The groups to add.
     */
    public void addGroups(@NonNull IProtoLogGroup[] newGroups) {
        synchronized (mGroups) {
            for (IProtoLogGroup group : newGroups) {
                mGroups.put(group.name(), group);
            }
        }

        if (mConfigService != null) {
            registerGroupsWithConfigurationServiceAsync(newGroups);
        }
    }

    @Override
    public void toggleLogcat(boolean enabled, @NonNull String[] groups) {
        mLogcatToggleListener.onLogcatToggle(enabled, groups);
    }

    /**
     * A callback interface for receiving logcat toggle events.
     */
    public interface LogcatToggleListener {
        /**
         * Called when the logcat toggle state changes.
         *
         * @param enable True if logcat should be enabled, false otherwise.
         * @param groups The groups that should be toggled.
         */
        void onLogcatToggle(boolean enable, @NonNull String[] groups);
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
            return null;
        }
    }

    private void registerGroupsWithConfigurationServiceAsync(@NonNull IProtoLogGroup... groups) {
        Objects.requireNonNull(mConfigService,
                "A null ProtoLog Configuration Service was provided!");

        mExecutor.execute(() -> {
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
                mConfigService.registerGroups(this, args);
            } catch (RemoteException e) {
                Log.wtf(LOG_TAG, "Failed to register ProtoLog groups", e);
            }
        });
    }
}
