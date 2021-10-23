/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import android.annotation.Nullable;
import android.window.TaskSnapshot;
import android.util.ArrayMap;

import java.io.PrintWriter;

/**
 * Caches snapshots. See {@link TaskSnapshotController}.
 * <p>
 * Access to this class should be guarded by the global window manager lock.
 */
class TaskSnapshotCache {

    private final WindowManagerService mService;
    private final TaskSnapshotLoader mLoader;
    private final ArrayMap<ActivityRecord, Integer> mAppTaskMap = new ArrayMap<>();
    private final ArrayMap<Integer, CacheEntry> mRunningCache = new ArrayMap<>();

    TaskSnapshotCache(WindowManagerService service, TaskSnapshotLoader loader) {
        mService = service;
        mLoader = loader;
    }

    void clearRunningCache() {
        mRunningCache.clear();
    }

    void putSnapshot(Task task, TaskSnapshot snapshot) {
        final CacheEntry entry = mRunningCache.get(task.mTaskId);
        if (entry != null) {
            mAppTaskMap.remove(entry.topApp);
        }
        final ActivityRecord top = task.getTopMostActivity();
        mAppTaskMap.put(top, task.mTaskId);
        mRunningCache.put(task.mTaskId, new CacheEntry(snapshot, top));
    }

    /**
     * If {@param restoreFromDisk} equals {@code true}, DO NOT HOLD THE WINDOW MANAGER LOCK!
     */
    @Nullable TaskSnapshot getSnapshot(int taskId, int userId, boolean restoreFromDisk,
            boolean isLowResolution) {

        synchronized (mService.mGlobalLock) {
            // Try the running cache.
            final CacheEntry entry = mRunningCache.get(taskId);
            if (entry != null) {
                return entry.snapshot;
            }
        }

        // Try to restore from disk if asked.
        if (!restoreFromDisk) {
            return null;
        }
        return tryRestoreFromDisk(taskId, userId, isLowResolution);
    }

    /**
     * DO NOT HOLD THE WINDOW MANAGER LOCK WHEN CALLING THIS METHOD!
     */
    private TaskSnapshot tryRestoreFromDisk(int taskId, int userId, boolean isLowResolution) {
        final TaskSnapshot snapshot = mLoader.loadTask(taskId, userId, isLowResolution);
        if (snapshot == null) {
            return null;
        }
        return snapshot;
    }

    /**
     * Called when an app token has been removed
     */
    void onAppRemoved(ActivityRecord activity) {
        final Integer taskId = mAppTaskMap.get(activity);
        if (taskId != null) {
            removeRunningEntry(taskId);
        }
    }

    /**
     * Callend when an app window token's process died.
     */
    void onAppDied(ActivityRecord activity) {
        final Integer taskId = mAppTaskMap.get(activity);
        if (taskId != null) {
            removeRunningEntry(taskId);
        }
    }

    void onTaskRemoved(int taskId) {
        removeRunningEntry(taskId);
    }

    void removeRunningEntry(int taskId) {
        final CacheEntry entry = mRunningCache.get(taskId);
        if (entry != null) {
            mAppTaskMap.remove(entry.topApp);
            mRunningCache.remove(taskId);
        }
    }

    void dump(PrintWriter pw, String prefix) {
        final String doublePrefix = prefix + "  ";
        final String triplePrefix = doublePrefix + "  ";
        pw.println(prefix + "SnapshotCache");
        for (int i = mRunningCache.size() - 1; i >= 0; i--) {
            final CacheEntry entry = mRunningCache.valueAt(i);
            pw.println(doublePrefix + "Entry taskId=" + mRunningCache.keyAt(i));
            pw.println(triplePrefix + "topApp=" + entry.topApp);
            pw.println(triplePrefix + "snapshot=" + entry.snapshot);
        }
    }

    private static final class CacheEntry {

        /** The snapshot. */
        final TaskSnapshot snapshot;

        /** The app token that was on top of the task when the snapshot was taken */
        final ActivityRecord topApp;

        CacheEntry(TaskSnapshot snapshot, ActivityRecord topApp) {
            this.snapshot = snapshot;
            this.topApp = topApp;
        }
    }
}
