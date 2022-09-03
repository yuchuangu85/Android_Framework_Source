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

package com.android.wm.shell.splitscreen;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.wm.shell.transition.Transitions.ENABLE_SHELL_TRANSITIONS;

import android.annotation.CallSuper;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.SparseArray;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;

import com.android.internal.R;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.SurfaceUtils;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.split.SplitDecorManager;
import com.android.wm.shell.splitscreen.SplitScreen.StageType;

import java.io.PrintWriter;

/**
 * Base class that handle common task org. related for split-screen stages.
 * Note that this class and its sub-class do not directly perform hierarchy operations.
 * They only serve to hold a collection of tasks and provide APIs like
 * {@link #setBounds(Rect, WindowContainerTransaction)} for the centralized {@link StageCoordinator}
 * to perform operations in-sync with other containers.
 *
 * @see StageCoordinator
 */
class StageTaskListener implements ShellTaskOrganizer.TaskListener {
    private static final String TAG = StageTaskListener.class.getSimpleName();

    protected static final int[] CONTROLLED_ACTIVITY_TYPES = {ACTIVITY_TYPE_STANDARD};
    protected static final int[] CONTROLLED_WINDOWING_MODES =
            {WINDOWING_MODE_FULLSCREEN, WINDOWING_MODE_UNDEFINED};
    protected static final int[] CONTROLLED_WINDOWING_MODES_WHEN_ACTIVE =
            {WINDOWING_MODE_FULLSCREEN, WINDOWING_MODE_UNDEFINED, WINDOWING_MODE_MULTI_WINDOW};

    /** Callback interface for listening to changes in a split-screen stage. */
    public interface StageListenerCallbacks {
        void onRootTaskAppeared();

        void onStatusChanged(boolean visible, boolean hasChildren);

        void onChildTaskStatusChanged(int taskId, boolean present, boolean visible);

        void onChildTaskEnterPip(int taskId);

        void onRootTaskVanished();

        void onNoLongerSupportMultiWindow();
    }

    private final Context mContext;
    private final StageListenerCallbacks mCallbacks;
    private final SurfaceSession mSurfaceSession;
    private final SyncTransactionQueue mSyncQueue;
    private final IconProvider mIconProvider;

    protected ActivityManager.RunningTaskInfo mRootTaskInfo;
    protected SurfaceControl mRootLeash;
    protected SurfaceControl mDimLayer;
    protected SparseArray<ActivityManager.RunningTaskInfo> mChildrenTaskInfo = new SparseArray<>();
    private final SparseArray<SurfaceControl> mChildrenLeashes = new SparseArray<>();
    // TODO(b/204308910): Extracts SplitDecorManager related code to common package.
    private SplitDecorManager mSplitDecorManager;

    private final StageTaskUnfoldController mStageTaskUnfoldController;

    StageTaskListener(Context context, ShellTaskOrganizer taskOrganizer, int displayId,
            StageListenerCallbacks callbacks, SyncTransactionQueue syncQueue,
            SurfaceSession surfaceSession, IconProvider iconProvider,
            @Nullable StageTaskUnfoldController stageTaskUnfoldController) {
        mContext = context;
        mCallbacks = callbacks;
        mSyncQueue = syncQueue;
        mSurfaceSession = surfaceSession;
        mIconProvider = iconProvider;
        mStageTaskUnfoldController = stageTaskUnfoldController;

        // No need to create root task if the device is using legacy split screen.
        // TODO(b/199236198): Remove this check after totally deprecated legacy split.
        if (!context.getResources().getBoolean(R.bool.config_useLegacySplit)) {
            taskOrganizer.createRootTask(displayId, WINDOWING_MODE_MULTI_WINDOW, this);
        }
    }

    int getChildCount() {
        return mChildrenTaskInfo.size();
    }

    boolean containsTask(int taskId) {
        return mChildrenTaskInfo.contains(taskId);
    }

    /**
     * Returns the top visible child task's id.
     */
    int getTopVisibleChildTaskId() {
        for (int i = mChildrenTaskInfo.size() - 1; i >= 0; --i) {
            final ActivityManager.RunningTaskInfo info = mChildrenTaskInfo.valueAt(i);
            if (info.isVisible) {
                return info.taskId;
            }
        }
        return INVALID_TASK_ID;
    }

    /**
     * Returns the top activity uid for the top child task.
     */
    int getTopChildTaskUid() {
        for (int i = mChildrenTaskInfo.size() - 1; i >= 0; --i) {
            final ActivityManager.RunningTaskInfo info = mChildrenTaskInfo.valueAt(i);
            if (info.topActivityInfo == null) {
                continue;
            }
            return info.topActivityInfo.applicationInfo.uid;
        }
        return 0;
    }

    /** @return {@code true} if this listener contains the currently focused task. */
    boolean isFocused() {
        if (mRootTaskInfo == null) {
            return false;
        }

        if (mRootTaskInfo.isFocused) {
            return true;
        }

        for (int i = mChildrenTaskInfo.size() - 1; i >= 0; --i) {
            if (mChildrenTaskInfo.valueAt(i).isFocused) {
                return true;
            }
        }

        return false;
    }

    @Override
    @CallSuper
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        if (mRootTaskInfo == null && !taskInfo.hasParentTask()) {
            mRootLeash = leash;
            mRootTaskInfo = taskInfo;
            mSplitDecorManager = new SplitDecorManager(
                    mRootTaskInfo.configuration,
                    mIconProvider,
                    mSurfaceSession);
            mCallbacks.onRootTaskAppeared();
            sendStatusChanged();
            mSyncQueue.runInSync(t -> mDimLayer =
                    SurfaceUtils.makeDimLayer(t, mRootLeash, "Dim layer", mSurfaceSession));
        } else if (taskInfo.parentTaskId == mRootTaskInfo.taskId) {
            final int taskId = taskInfo.taskId;
            mChildrenLeashes.put(taskId, leash);
            mChildrenTaskInfo.put(taskId, taskInfo);
            updateChildTaskSurface(taskInfo, leash, true /* firstAppeared */);
            mCallbacks.onChildTaskStatusChanged(taskId, true /* present */, taskInfo.isVisible);
            if (ENABLE_SHELL_TRANSITIONS) {
                // Status is managed/synchronized by the transition lifecycle.
                return;
            }
            sendStatusChanged();
        } else {
            throw new IllegalArgumentException(this + "\n Unknown task: " + taskInfo
                    + "\n mRootTaskInfo: " + mRootTaskInfo);
        }

        if (mStageTaskUnfoldController != null) {
            mStageTaskUnfoldController.onTaskAppeared(taskInfo, leash);
        }
    }

    @Override
    @CallSuper
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
        if (!taskInfo.supportsMultiWindow) {
            // Leave split screen if the task no longer supports multi window.
            mCallbacks.onNoLongerSupportMultiWindow();
            return;
        }
        if (mRootTaskInfo.taskId == taskInfo.taskId) {
            // Inflates split decor view only when the root task is visible.
            if (mRootTaskInfo.isVisible != taskInfo.isVisible) {
                mSyncQueue.runInSync(t -> {
                    if (taskInfo.isVisible) {
                        mSplitDecorManager.inflate(mContext, mRootLeash,
                                taskInfo.configuration.windowConfiguration.getBounds());
                    } else {
                        mSplitDecorManager.release(t);
                    }
                });
            }
            mRootTaskInfo = taskInfo;
        } else if (taskInfo.parentTaskId == mRootTaskInfo.taskId) {
            mChildrenTaskInfo.put(taskInfo.taskId, taskInfo);
            mCallbacks.onChildTaskStatusChanged(taskInfo.taskId, true /* present */,
                    taskInfo.isVisible);
            if (!ENABLE_SHELL_TRANSITIONS) {
                updateChildTaskSurface(
                        taskInfo, mChildrenLeashes.get(taskInfo.taskId), false /* firstAppeared */);
            }
        } else {
            throw new IllegalArgumentException(this + "\n Unknown task: " + taskInfo
                    + "\n mRootTaskInfo: " + mRootTaskInfo);
        }
        if (ENABLE_SHELL_TRANSITIONS) {
            // Status is managed/synchronized by the transition lifecycle.
            return;
        }
        sendStatusChanged();
    }

    @Override
    @CallSuper
    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        final int taskId = taskInfo.taskId;
        if (mRootTaskInfo.taskId == taskId) {
            mCallbacks.onRootTaskVanished();
            mRootTaskInfo = null;
            mSyncQueue.runInSync(t -> {
                t.remove(mDimLayer);
                mSplitDecorManager.release(t);
            });
        } else if (mChildrenTaskInfo.contains(taskId)) {
            mChildrenTaskInfo.remove(taskId);
            mChildrenLeashes.remove(taskId);
            mCallbacks.onChildTaskStatusChanged(taskId, false /* present */, taskInfo.isVisible);
            if (taskInfo.getWindowingMode() == WINDOWING_MODE_PINNED) {
                mCallbacks.onChildTaskEnterPip(taskId);
            }
            if (ENABLE_SHELL_TRANSITIONS) {
                // Status is managed/synchronized by the transition lifecycle.
                return;
            }
            sendStatusChanged();
        } else {
            throw new IllegalArgumentException(this + "\n Unknown task: " + taskInfo
                    + "\n mRootTaskInfo: " + mRootTaskInfo);
        }

        if (mStageTaskUnfoldController != null) {
            mStageTaskUnfoldController.onTaskVanished(taskInfo);
        }
    }

    @Override
    public void attachChildSurfaceToTask(int taskId, SurfaceControl.Builder b) {
        if (mRootTaskInfo.taskId == taskId) {
            b.setParent(mRootLeash);
        } else if (mChildrenLeashes.contains(taskId)) {
            b.setParent(mChildrenLeashes.get(taskId));
        } else {
            throw new IllegalArgumentException("There is no surface for taskId=" + taskId);
        }
    }

    void onResizing(Rect newBounds, SurfaceControl.Transaction t) {
        if (mSplitDecorManager != null && mRootTaskInfo != null) {
            mSplitDecorManager.onResizing(mRootTaskInfo, newBounds, t);
        }
    }

    void onResized(Rect newBounds, SurfaceControl.Transaction t) {
        if (mSplitDecorManager != null) {
            mSplitDecorManager.onResized(newBounds, t);
        }
    }

    void addTask(ActivityManager.RunningTaskInfo task, WindowContainerTransaction wct) {
        wct.reparent(task.token, mRootTaskInfo.token, true /* onTop*/);
    }

    void moveToTop(Rect rootBounds, WindowContainerTransaction wct) {
        final WindowContainerToken rootToken = mRootTaskInfo.token;
        wct.setBounds(rootToken, rootBounds).reorder(rootToken, true /* onTop */);
    }

    void setBounds(Rect bounds, WindowContainerTransaction wct) {
        wct.setBounds(mRootTaskInfo.token, bounds);
    }

    void reorderChild(int taskId, boolean onTop, WindowContainerTransaction wct) {
        if (!containsTask(taskId)) {
            return;
        }
        wct.reorder(mChildrenTaskInfo.get(taskId).token, onTop /* onTop */);
    }

    /** Collects all the current child tasks and prepares transaction to evict them to display. */
    void evictAllChildren(WindowContainerTransaction wct) {
        for (int i = mChildrenTaskInfo.size() - 1; i >= 0; i--) {
            final ActivityManager.RunningTaskInfo taskInfo = mChildrenTaskInfo.valueAt(i);
            wct.reparent(taskInfo.token, null /* parent */, false /* onTop */);
        }
    }

    void setVisibility(boolean visible, WindowContainerTransaction wct) {
        wct.reorder(mRootTaskInfo.token, visible /* onTop */);
    }

    void onSplitScreenListenerRegistered(SplitScreen.SplitScreenListener listener,
            @StageType int stage) {
        for (int i = mChildrenTaskInfo.size() - 1; i >= 0; --i) {
            int taskId = mChildrenTaskInfo.keyAt(i);
            listener.onTaskStageChanged(taskId, stage,
                    mChildrenTaskInfo.get(taskId).isVisible);
        }
    }

    private void updateChildTaskSurface(ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl leash, boolean firstAppeared) {
        final Point taskPositionInParent = taskInfo.positionInParent;
        mSyncQueue.runInSync(t -> {
            t.setWindowCrop(leash, null);
            t.setPosition(leash, taskPositionInParent.x, taskPositionInParent.y);
            if (firstAppeared && !ENABLE_SHELL_TRANSITIONS) {
                t.setAlpha(leash, 1f);
                t.setMatrix(leash, 1, 0, 0, 1);
                t.show(leash);
            }
        });
    }

    private void sendStatusChanged() {
        mCallbacks.onStatusChanged(mRootTaskInfo.isVisible, mChildrenTaskInfo.size() > 0);
    }

    @Override
    @CallSuper
    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        final String childPrefix = innerPrefix + "  ";
        pw.println(prefix + this);
    }
}
