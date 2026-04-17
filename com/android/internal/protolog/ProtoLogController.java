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

package com.android.internal.protolog;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.ServiceManager;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.IProtoLog;
import com.android.internal.protolog.common.IProtoLogGroup;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Controller for managing ProtoLog state and core logic.
 * This class is not thread-safe for concurrent modifications from multiple controllers
 * if shared, but instances are intended to be managed by the ProtoLog class or tests.
 * Internal state is synchronized via mInitLock.
 */
@RavenwoodKeepWholeClass
public class ProtoLogController {

    @Nullable
    IProtoLog mProtoLogInstance;

    @NonNull
    private final Object mInitLock = new Object();

    @GuardedBy("mInitLock")
    private final Set<IProtoLogGroup> mGroups = new HashSet<>();

    /**
     * Registers a log group in the process.
     * @param groups The groups to register.
     */
    public void registerLogGroupInProcess(@NonNull IProtoLogGroup... groups) {
        synchronized (mInitLock) {
            var newGroups = Arrays.stream(groups)
                    .filter(Objects::nonNull)
                    .filter(group -> !mGroups.contains(group))
                    .collect(Collectors.toUnmodifiableSet());
            if (newGroups.isEmpty()) {
                return;
            }

            assertForCollisions(newGroups);

            mGroups.addAll(newGroups);

            if (mProtoLogInstance != null) {
                mProtoLogInstance.registerGroups(newGroups.toArray(new IProtoLogGroup[0]));
            }
        }
    }

    /**
     * Initializes the ProtoLog instance.
     * @param groups The groups to register.
     */
    public void init(@NonNull IProtoLogGroup... groups) {
        init(false, groups);
    }

    /**
     * Asynchronously initializes the ProtoLog instance.
     * @param groups The groups to register.
     */
    public void initAsync(@NonNull IProtoLogGroup... groups) {
        init(true, groups);
    }

    private void init(boolean async, @NonNull IProtoLogGroup... groups) {
        registerLogGroupInProcess(groups);

        synchronized (mInitLock) {
            if (mProtoLogInstance != null) {
                return;
            }

            // These tracing instances are only used when we cannot or do not preprocess the source
            // files to extract out the log strings. Otherwise, the trace calls are replaced with
            // calls directly to the generated tracing implementations.
            if (shouldLogOnlyToLogcat()) {
                mProtoLogInstance = createLogcatOnlyInstance();
            } else {
                var datasource = ProtoLog.getSharedSingleInstanceDataSource(!async);

                mProtoLogInstance = createAndEnableNewPerfettoProtoLogImpl(
                        datasource, mGroups.toArray(new IProtoLogGroup[0]), async);
            }
        }
    }

    @Nullable
    @VisibleForTesting
    public IProtoLog getProtoLogInstance() {
        return mProtoLogInstance;
    }

    /**
     * Tear down the ProtoLog instance. This should probably only be called from testing.
     * Otherwise there is no reason to teardown a ProtoLogController as it should exist for the
     * entire life of a process and be the same for the entire duration.
     */
    @VisibleForTesting
    public void tearDown() {
        synchronized (mInitLock) {
            if (mProtoLogInstance == null) {
                return;
            }

            if (mProtoLogInstance instanceof PerfettoProtoLogImpl) {
                ((PerfettoProtoLogImpl) mProtoLogInstance).disable();
            }
        }
    }

    @VisibleForTesting
    @NonNull
    public Set<IProtoLogGroup> getRegisteredGroups() {
        return Set.copyOf(mGroups);
    }

    /**
     * Decides if logging should only go to Logcat.
     * Protected for testability.
     */
    protected boolean shouldLogOnlyToLogcat() {
        return ProtoLog.logOnlyToLogcat();
    }

    /**
     * Creates an instance of LogcatOnlyProtoLogImpl.
     * Protected for testability.
     */
    @NonNull
    protected IProtoLog createLogcatOnlyInstance() {
        return new LogcatOnlyProtoLogImpl();
    }

    /**
     * Creates and enables a new PerfettoProtoLogImpl.
     * Protected for testability.
     */
    @NonNull
    protected PerfettoProtoLogImpl createAndEnableNewPerfettoProtoLogImpl(
            @NonNull ProtoLogDataSource datasource, @NonNull IProtoLogGroup[] groups,
            boolean async) {
        try {
            var unprocessedPerfettoProtoLogImpl =
                    new UnprocessedPerfettoProtoLogImpl(datasource, groups);
            unprocessedPerfettoProtoLogImpl.enable(async);

            return unprocessedPerfettoProtoLogImpl;
        } catch (ServiceManager.ServiceNotFoundException e) {
            throw new RuntimeException("Failed to create PerfettoProtoLogImpl", e);
        }
    }

    private void assertForCollisions(Set<IProtoLogGroup> newGroups) {
        // Check for ID collisions within the new groups first
        Set<Integer> newIds = new HashSet<>();
        for (IProtoLogGroup group : newGroups) {
            if (group == null) {
                continue;
            }
            if (!newIds.add(group.getId())) {
                throw new RuntimeException("ProtoLog group ID collision for ID " + group.getId()
                        + " within the same registration call.");
            }
        }

        // Check for collisions with already registered groups
        for (IProtoLogGroup group : newGroups) {
            for (IProtoLogGroup existingGroup : mGroups) {
                if (existingGroup.getId() == group.getId() && !existingGroup.equals(group)) {
                    throw new RuntimeException("ProtoLog group ID collision for ID "
                            + group.getId() + ". Group " + group.name()
                            + " conflicts with already registered group "
                            + existingGroup.name());
                }
            }
        }
    }
}
