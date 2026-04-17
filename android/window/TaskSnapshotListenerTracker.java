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

package android.window;

import android.annotation.NonNull;

import java.util.ArrayList;

/**
 * Help tracking task snapshot callback from system.
 * @hide
 */
class TaskSnapshotListenerTracker extends ITaskSnapshotListener.Stub {
    private final TaskSnapshotManager mManager;
    private final ArrayList<TaskSnapshotListener> mLocalListeners = new ArrayList<>();

    TaskSnapshotListenerTracker(TaskSnapshotManager manager) {
        mManager = manager;
    }

    void registerLocalListener(@NonNull TaskSnapshotListener listener) {
        synchronized (mLocalListeners) {
            if (mLocalListeners.contains(listener)) {
                return;
            }
            mLocalListeners.add(listener);
        }
    }

    void unregisterLocalListener(@NonNull TaskSnapshotListener listener) {
        synchronized (mLocalListeners) {
            mLocalListeners.remove(listener);
        }
    }

    boolean isEmpty() {
        synchronized (mLocalListeners) {
            return mLocalListeners.isEmpty();
        }
    }

    /**
     * Called when a task snapshot got updated.
     */
    @Override
    public void onTaskSnapshotChanged(int taskId, TaskSnapshot snapshot) {
        final ArrayList<TaskSnapshotListener> tempList;
        synchronized (mLocalListeners) {
            if (isEmpty()) {
                snapshot.closeBuffer();
                return;
            }
            tempList = new ArrayList<>(mLocalListeners);
        }
        mManager.createTrackerWithCount(taskId, snapshot, tempList.size());
        for (TaskSnapshotListener l : tempList) {
            l.onTaskSnapshotChanged(taskId, snapshot);
        }
    }

    /**
     * Called when a task snapshot become invalidated.
     */
    @Override
    public void onTaskSnapshotReleased(int taskId) {
        final ArrayList<TaskSnapshotListener> tempList;
        synchronized (mLocalListeners) {
            tempList = new ArrayList<>(mLocalListeners);
        }
        for (TaskSnapshotListener l : tempList) {
            l.onTaskSnapshotReleased(taskId);
        }
    }
}
