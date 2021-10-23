/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WINDOW_ORGANIZER;
import static com.android.server.wm.ActivityTaskManagerService.enforceTaskPermission;
import static com.android.server.wm.DisplayContent.IME_TARGET_LAYERING;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_STARTING_REVEAL;
import static com.android.server.wm.WindowOrganizerController.CONTROLLABLE_CONFIGS;
import static com.android.server.wm.WindowOrganizerController.CONTROLLABLE_WINDOW_CONFIGS;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.WindowConfiguration;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ParceledListSlice;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;
import android.window.ITaskOrganizer;
import android.window.ITaskOrganizerController;
import android.window.SplashScreenView;
import android.window.StartingWindowInfo;
import android.window.TaskAppearedInfo;
import android.window.TaskSnapshot;
import android.window.WindowContainerToken;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.ArrayUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Stores the TaskOrganizers associated with a given windowing mode and
 * their associated state.
 */
class TaskOrganizerController extends ITaskOrganizerController.Stub {
    private static final String TAG = "TaskOrganizerController";

    /**
     * Masks specifying which configurations are important to report back to an organizer when
     * changed.
     */
    private static final int REPORT_CONFIGS = CONTROLLABLE_CONFIGS;
    private static final int REPORT_WINDOW_CONFIGS = CONTROLLABLE_WINDOW_CONFIGS;

    // The set of modes that are currently supports
    // TODO: Remove once the task organizer can support all modes
    @VisibleForTesting
    static final int[] UNSUPPORTED_WINDOWING_MODES = {
            WINDOWING_MODE_UNDEFINED,
            WINDOWING_MODE_FREEFORM
    };

    private class DeathRecipient implements IBinder.DeathRecipient {
        ITaskOrganizer mTaskOrganizer;

        DeathRecipient(ITaskOrganizer organizer) {
            mTaskOrganizer = organizer;
        }

        @Override
        public void binderDied() {
            synchronized (mGlobalLock) {
                final TaskOrganizerState state = mTaskOrganizerStates.remove(
                        mTaskOrganizer.asBinder());
                if (state != null) {
                    state.dispose();
                }
            }
        }
    }

    /**
     * A wrapper class around ITaskOrganizer to ensure that the calls are made in the right
     * lifecycle order since we may be updating the visibility of task surface controls in a pending
     * transaction before they are presented to the task org.
     */
    private class TaskOrganizerCallbacks {
        final ITaskOrganizer mTaskOrganizer;
        final Consumer<Runnable> mDeferTaskOrgCallbacksConsumer;

        TaskOrganizerCallbacks(ITaskOrganizer taskOrg,
                Consumer<Runnable> deferTaskOrgCallbacksConsumer) {
            mDeferTaskOrgCallbacksConsumer = deferTaskOrgCallbacksConsumer;
            mTaskOrganizer = taskOrg;
        }

        IBinder getBinder() {
            return mTaskOrganizer.asBinder();
        }

        void addStartingWindow(Task task, ActivityRecord activity, int launchTheme,
                TaskSnapshot taskSnapshot) {
            final StartingWindowInfo info = task.getStartingWindowInfo(activity);
            if (launchTheme != 0) {
                info.splashScreenThemeResId = launchTheme;
            }
            info.mTaskSnapshot = taskSnapshot;
            // make this happen prior than prepare surface
            try {
                mTaskOrganizer.addStartingWindow(info, activity.token);
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception sending onTaskStart callback", e);
            }
        }

        // Capture the animation surface control for activity's main window
        private class StartingWindowAnimationAdaptor implements AnimationAdapter {
            private SurfaceControl mAnimationLeash;
            @Override
            public boolean getShowWallpaper() {
                return false;
            }

            @Override
            public void startAnimation(SurfaceControl animationLeash, SurfaceControl.Transaction t,
                    int type, SurfaceAnimator.OnAnimationFinishedCallback finishCallback) {
                mAnimationLeash = animationLeash;
            }

            @Override
            public void onAnimationCancelled(SurfaceControl animationLeash) {
                if (mAnimationLeash == animationLeash) {
                    mAnimationLeash = null;
                }
            }

            @Override
            public long getDurationHint() {
                return 0;
            }

            @Override
            public long getStatusBarTransitionsStartTime() {
                return 0;
            }

            @Override
            public void dump(PrintWriter pw, String prefix) {
                pw.print(prefix + "StartingWindowAnimationAdaptor mCapturedLeash=");
                pw.print(mAnimationLeash);
                pw.println();
            }

            @Override
            public void dumpDebug(ProtoOutputStream proto) {
            }
        }

        void removeStartingWindow(Task task, boolean prepareAnimation) {
            SurfaceControl windowAnimationLeash = null;
            Rect mainFrame = null;
            final boolean playShiftUpAnimation = !task.inMultiWindowMode();
            if (prepareAnimation && playShiftUpAnimation) {
                final ActivityRecord topActivity = task.topActivityContainsStartingWindow();
                if (topActivity != null) {
                    final WindowState mainWindow =
                            topActivity.findMainWindow(false/* includeStartingApp */);
                    if (mainWindow != null) {
                        final StartingWindowAnimationAdaptor adaptor =
                                new StartingWindowAnimationAdaptor();
                        final SurfaceControl.Transaction t = mainWindow.getPendingTransaction();
                        mainWindow.startAnimation(t, adaptor, false,
                                ANIMATION_TYPE_STARTING_REVEAL);
                        windowAnimationLeash = adaptor.mAnimationLeash;
                        mainFrame = mainWindow.getRelativeFrame();
                        t.setPosition(windowAnimationLeash, mainFrame.left, mainFrame.top);
                    }
                }
            }
            try {
                mTaskOrganizer.removeStartingWindow(task.mTaskId, windowAnimationLeash,
                        mainFrame, prepareAnimation);
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception sending onStartTaskFinished callback", e);
            }
        }

        void copySplashScreenView(Task task) {
            try {
                mTaskOrganizer.copySplashScreenView(task.mTaskId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception sending copyStartingWindowView callback", e);
            }
        }

        void onAppSplashScreenViewRemoved(Task task) {
            try {
                mTaskOrganizer.onAppSplashScreenViewRemoved(task.mTaskId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception sending onAppSplashScreenViewRemoved callback", e);
            }
        }

        SurfaceControl prepareLeash(Task task, String reason) {
            return new SurfaceControl(task.getSurfaceControl(), reason);
        }

        void onTaskAppeared(Task task) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Task appeared taskId=%d", task.mTaskId);
            final RunningTaskInfo taskInfo = task.getTaskInfo();
            try {
                mTaskOrganizer.onTaskAppeared(taskInfo, prepareLeash(task,
                        "TaskOrganizerController.onTaskAppeared"));
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception sending onTaskAppeared callback", e);
            }
        }


        void onTaskVanished(Task task) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Task vanished taskId=%d", task.mTaskId);
            final RunningTaskInfo taskInfo = task.getTaskInfo();
            try {
                mTaskOrganizer.onTaskVanished(taskInfo);
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception sending onTaskVanished callback", e);
            }
        }

        void onTaskInfoChanged(Task task, ActivityManager.RunningTaskInfo taskInfo) {
            if (!task.mTaskAppearedSent) {
                // Skip if the task has not yet received taskAppeared().
                return;
            }
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Task info changed taskId=%d", task.mTaskId);
            if (!task.isOrganized()) {
                // This is safe to ignore if the task is no longer organized
                return;
            }
            try {
                // Purposely notify of task info change immediately instead of deferring (like
                // appear and vanish) to allow info changes (such as new PIP params) to flow
                // without waiting.
                mTaskOrganizer.onTaskInfoChanged(taskInfo);
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception sending onTaskInfoChanged callback", e);
            }
        }

        void onBackPressedOnTaskRoot(Task task) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Task back pressed on root taskId=%d",
                    task.mTaskId);
            if (!task.mTaskAppearedSent) {
                // Skip if the task has not yet received taskAppeared().
                return;
            }
            if (!task.isOrganized()) {
                // This is safe to ignore if the task is no longer organized
                return;
            }
            try {
                mTaskOrganizer.onBackPressedOnTaskRoot(task.getTaskInfo());
            } catch (Exception e) {
                Slog.e(TAG, "Exception sending onBackPressedOnTaskRoot callback", e);
            }
        }
    }

    private class TaskOrganizerState {
        private final TaskOrganizerCallbacks mOrganizer;
        private final DeathRecipient mDeathRecipient;
        private final ArrayList<Task> mOrganizedTasks = new ArrayList<>();
        private final int mUid;

        TaskOrganizerState(ITaskOrganizer organizer, int uid) {
            final Consumer<Runnable> deferTaskOrgCallbacksConsumer =
                    mDeferTaskOrgCallbacksConsumer != null
                            ? mDeferTaskOrgCallbacksConsumer
                            : mService.mWindowManager.mAnimator::addAfterPrepareSurfacesRunnable;
            mOrganizer = new TaskOrganizerCallbacks(organizer, deferTaskOrgCallbacksConsumer);
            mDeathRecipient = new DeathRecipient(organizer);
            try {
                organizer.asBinder().linkToDeath(mDeathRecipient, 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "TaskOrganizer failed to register death recipient");
            }
            mUid = uid;
        }

        void addStartingWindow(Task t, ActivityRecord activity, int launchTheme,
                TaskSnapshot taskSnapshot) {
            mOrganizer.addStartingWindow(t, activity, launchTheme, taskSnapshot);
        }

        void removeStartingWindow(Task t, boolean prepareAnimation) {
            mOrganizer.removeStartingWindow(t, prepareAnimation);
        }

        void copySplashScreenView(Task t) {
            mOrganizer.copySplashScreenView(t);
        }

        public void onAppSplashScreenViewRemoved(Task t) {
            mOrganizer.onAppSplashScreenViewRemoved(t);
        }

        /**
         * Register this task with this state, but doesn't trigger the task appeared callback to
         * the organizer.
         */
        SurfaceControl addTaskWithoutCallback(Task t, String reason) {
            t.mTaskAppearedSent = true;
            if (!mOrganizedTasks.contains(t)) {
                mOrganizedTasks.add(t);
            }
            return mOrganizer.prepareLeash(t, reason);
        }

        private boolean addTask(Task t) {
            if (t.mTaskAppearedSent) {
                return false;
            }

            if (!mOrganizedTasks.contains(t)) {
                mOrganizedTasks.add(t);
            }

            if (t.taskAppearedReady()) {
                t.mTaskAppearedSent = true;
                return true;
            }
            return false;
        }

        private boolean removeTask(Task t, boolean removeFromSystem) {
            mOrganizedTasks.remove(t);
            mInterceptBackPressedOnRootTasks.remove(t.mTaskId);
            boolean taskAppearedSent = t.mTaskAppearedSent;
            if (taskAppearedSent) {
                if (t.getSurfaceControl() != null) {
                    t.migrateToNewSurfaceControl(t.getSyncTransaction());
                }
                t.mTaskAppearedSent = false;
            }
            if (removeFromSystem) {
                mService.removeTask(t.mTaskId);
            }
            return taskAppearedSent;
        }

        void dispose() {
            // Move organizer from managing specific windowing modes
            mTaskOrganizers.remove(mOrganizer.mTaskOrganizer);

            // Update tasks currently managed by this organizer to the next one available if
            // possible.
            while (!mOrganizedTasks.isEmpty()) {
                final Task t = mOrganizedTasks.get(0);
                t.updateTaskOrganizerState(true /* forceUpdate */);
                if (mOrganizedTasks.contains(t)) {
                    // updateTaskOrganizerState should remove the task from the list, but still
                    // check it again to avoid while-loop isn't terminate.
                    if (removeTask(t, t.mRemoveWithTaskOrganizer)) {
                        TaskOrganizerController.this.onTaskVanishedInternal(
                                mOrganizer.mTaskOrganizer, t);
                    }
                }
            }

            // Remove organizer state after removing tasks so we get a chance to send
            // onTaskVanished.
            mTaskOrganizerStates.remove(asBinder());
        }

        void unlinkDeath() {
            mOrganizer.getBinder().unlinkToDeath(mDeathRecipient, 0);
        }
    }

    static class PendingTaskEvent {
        static final int EVENT_APPEARED = 0;
        static final int EVENT_VANISHED = 1;
        static final int EVENT_INFO_CHANGED = 2;
        static final int EVENT_ROOT_BACK_PRESSED = 3;

        final int mEventType;
        final Task mTask;
        final ITaskOrganizer mTaskOrg;
        boolean mForce;

        PendingTaskEvent(Task task, int event) {
            this(task, task.mTaskOrganizer, event);
        }

        PendingTaskEvent(Task task, ITaskOrganizer taskOrg, int eventType) {
            mTask = task;
            mTaskOrg = taskOrg;
            mEventType = eventType;
        }

        boolean isLifecycleEvent() {
            return mEventType == EVENT_APPEARED || mEventType == EVENT_VANISHED
                    || mEventType == EVENT_INFO_CHANGED;
        }
    }

    private final ActivityTaskManagerService mService;
    private final WindowManagerGlobalLock mGlobalLock;

    // List of task organizers by priority
    private final LinkedList<ITaskOrganizer> mTaskOrganizers = new LinkedList<>();
    private final HashMap<IBinder, TaskOrganizerState> mTaskOrganizerStates = new HashMap<>();
    private final WeakHashMap<Task, RunningTaskInfo> mLastSentTaskInfos = new WeakHashMap<>();
    // Pending task events due to layout deferred.
    private final ArrayList<PendingTaskEvent> mPendingTaskEvents = new ArrayList<>();
    // Set of organized tasks (by taskId) that dispatch back pressed to their organizers
    private final HashSet<Integer> mInterceptBackPressedOnRootTasks = new HashSet();

    private RunningTaskInfo mTmpTaskInfo;
    private Consumer<Runnable> mDeferTaskOrgCallbacksConsumer;

    TaskOrganizerController(ActivityTaskManagerService atm) {
        mService = atm;
        mGlobalLock = atm.mGlobalLock;
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            throw ActivityTaskManagerService.logAndRethrowRuntimeExceptionOnTransact(TAG, e);
        }
    }

    /**
     * Specifies the consumer to run to defer the task org callbacks. Can be overridden while
     * testing to allow the callbacks to be sent synchronously.
     */
    @VisibleForTesting
    public void setDeferTaskOrgCallbacksConsumer(Consumer<Runnable> consumer) {
        mDeferTaskOrgCallbacksConsumer = consumer;
    }

    @VisibleForTesting
    ArrayList<PendingTaskEvent> getPendingEventList() {
        return mPendingTaskEvents;
    }

    /**
     * Register a TaskOrganizer to manage tasks as they enter the a supported windowing mode.
     */
    @Override
    public ParceledListSlice<TaskAppearedInfo> registerTaskOrganizer(ITaskOrganizer organizer) {
        enforceTaskPermission("registerTaskOrganizer()");
        final int uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Register task organizer=%s uid=%d",
                        organizer.asBinder(), uid);
                if (!mTaskOrganizerStates.containsKey(organizer.asBinder())) {
                    mTaskOrganizers.add(organizer);
                    mTaskOrganizerStates.put(organizer.asBinder(),
                            new TaskOrganizerState(organizer, uid));
                }

                final ArrayList<TaskAppearedInfo> taskInfos = new ArrayList<>();
                final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
                mService.mRootWindowContainer.forAllTasks((task) -> {
                    if (ArrayUtils.contains(UNSUPPORTED_WINDOWING_MODES, task.getWindowingMode())) {
                        return;
                    }

                    boolean returnTask = !task.mCreatedByOrganizer;
                    task.updateTaskOrganizerState(true /* forceUpdate */,
                            returnTask /* skipTaskAppeared */);
                    if (returnTask) {
                        SurfaceControl outSurfaceControl = state.addTaskWithoutCallback(task,
                                "TaskOrganizerController.registerTaskOrganizer");
                        taskInfos.add(new TaskAppearedInfo(task.getTaskInfo(), outSurfaceControl));
                    }
                });
                return new ParceledListSlice<>(taskInfos);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void unregisterTaskOrganizer(ITaskOrganizer organizer) {
        enforceTaskPermission("unregisterTaskOrganizer()");
        final int uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
                if (state == null) {
                    return;
                }
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Unregister task organizer=%s uid=%d",
                        organizer.asBinder(), uid);
                state.unlinkDeath();
                state.dispose();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * @return the task organizer key for a given windowing mode.
     */
    ITaskOrganizer getTaskOrganizer(int windowingMode) {
        return isSupportedWindowingMode(windowingMode)
                ? mTaskOrganizers.peekLast()
                : null;
    }

    boolean isSupportedWindowingMode(int winMode) {
        return !ArrayUtils.contains(UNSUPPORTED_WINDOWING_MODES, winMode);
    }

    boolean addStartingWindow(Task task, ActivityRecord activity, int launchTheme,
            TaskSnapshot taskSnapshot) {
        final Task rootTask = task.getRootTask();
        if (rootTask == null || rootTask.mTaskOrganizer == null || activity.mStartingData == null) {
            return false;
        }
        final TaskOrganizerState state =
                mTaskOrganizerStates.get(rootTask.mTaskOrganizer.asBinder());
        state.addStartingWindow(task, activity, launchTheme, taskSnapshot);
        return true;
    }

    void removeStartingWindow(Task task, boolean prepareAnimation) {
        final Task rootTask = task.getRootTask();
        if (rootTask == null || rootTask.mTaskOrganizer == null) {
            return;
        }
        final TaskOrganizerState state =
                mTaskOrganizerStates.get(rootTask.mTaskOrganizer.asBinder());
        state.removeStartingWindow(task, prepareAnimation);
    }

    boolean copySplashScreenView(Task task) {
        final Task rootTask = task.getRootTask();
        if (rootTask == null || rootTask.mTaskOrganizer == null) {
            return false;
        }
        final TaskOrganizerState state =
                mTaskOrganizerStates.get(rootTask.mTaskOrganizer.asBinder());
        state.copySplashScreenView(task);
        return true;
    }

    /**
     * Notify the shell ({@link com.android.wm.shell.ShellTaskOrganizer} that the client has
     * removed the splash screen view.
     * @see com.android.wm.shell.ShellTaskOrganizer#onAppSplashScreenViewRemoved(int)
     * @see SplashScreenView#remove()
     */
    public void onAppSplashScreenViewRemoved(Task task) {
        final Task rootTask = task.getRootTask();
        if (rootTask == null || rootTask.mTaskOrganizer == null) {
            return;
        }
        final TaskOrganizerState state =
                mTaskOrganizerStates.get(rootTask.mTaskOrganizer.asBinder());
        state.onAppSplashScreenViewRemoved(task);
    }

    void onTaskAppeared(ITaskOrganizer organizer, Task task) {
        final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
        if (state != null && state.addTask(task)) {
            PendingTaskEvent pending = getPendingTaskEvent(task, PendingTaskEvent.EVENT_APPEARED);
            if (pending == null) {
                pending = new PendingTaskEvent(task, PendingTaskEvent.EVENT_APPEARED);
                mPendingTaskEvents.add(pending);
            }
        }
    }

    void onTaskVanished(ITaskOrganizer organizer, Task task) {
        final TaskOrganizerState state = mTaskOrganizerStates.get(organizer.asBinder());
        if (state != null && state.removeTask(task, false /* removeFromSystem */)) {
            onTaskVanishedInternal(organizer, task);
        }
    }

    private void onTaskVanishedInternal(ITaskOrganizer organizer, Task task) {
        for (int i = mPendingTaskEvents.size() - 1; i >= 0; i--) {
            PendingTaskEvent entry = mPendingTaskEvents.get(i);
            if (task.mTaskId == entry.mTask.mTaskId) {
                // This task is vanished so remove all pending event of it.
                mPendingTaskEvents.remove(i);
                if (entry.mEventType == PendingTaskEvent.EVENT_APPEARED) {
                    // If task appeared callback still pend, ignore this callback too.
                    return;
                }
            }
        }

        PendingTaskEvent pending =
                new PendingTaskEvent(task, organizer, PendingTaskEvent.EVENT_VANISHED);
        mPendingTaskEvents.add(pending);
    }

    @Override
    public void createRootTask(int displayId, int windowingMode, @Nullable IBinder launchCookie) {
        enforceTaskPermission("createRootTask()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                DisplayContent display = mService.mRootWindowContainer.getDisplayContent(displayId);
                if (display == null) {
                    ProtoLog.e(WM_DEBUG_WINDOW_ORGANIZER,
                            "createRootTask unknown displayId=%d", displayId);
                    return;
                }

                createRootTask(display, windowingMode, launchCookie);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @VisibleForTesting
    Task createRootTask(DisplayContent display, int windowingMode, @Nullable IBinder launchCookie) {
        ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Create root task displayId=%d winMode=%d",
                display.mDisplayId, windowingMode);
        // We want to defer the task appear signal until the task is fully created and attached to
        // to the hierarchy so that the complete starting configuration is in the task info we send
        // over to the organizer.
        final Task task = new Task.Builder(mService)
                .setWindowingMode(windowingMode)
                .setIntent(new Intent())
                .setCreatedByOrganizer(true)
                .setDeferTaskAppear(true)
                .setLaunchCookie(launchCookie)
                .setParent(display.getDefaultTaskDisplayArea())
                .build();
        task.setDeferTaskAppear(false /* deferTaskAppear */);
        return task;
    }

    @Override
    public boolean deleteRootTask(WindowContainerToken token) {
        enforceTaskPermission("deleteRootTask()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final WindowContainer wc = WindowContainer.fromBinder(token.asBinder());
                if (wc == null) return false;
                final Task task = wc.asTask();
                if (task == null) return false;
                if (!task.mCreatedByOrganizer) {
                    throw new IllegalArgumentException(
                            "Attempt to delete task not created by organizer task=" + task);
                }

                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Delete root task display=%d winMode=%d",
                        task.getDisplayId(), task.getWindowingMode());
                task.removeImmediately("deleteRootTask");
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void dispatchPendingEvents() {
        if (mService.mWindowManager.mWindowPlacerLocked.isLayoutDeferred()
                || mPendingTaskEvents.isEmpty()) {
            return;
        }

        for (int i = 0, n = mPendingTaskEvents.size(); i < n; i++) {
            PendingTaskEvent event = mPendingTaskEvents.get(i);
            final Task task = event.mTask;
            final TaskOrganizerState state;
            switch (event.mEventType) {
                case PendingTaskEvent.EVENT_APPEARED:
                    state = mTaskOrganizerStates.get(event.mTaskOrg.asBinder());
                    if (state != null && task.taskAppearedReady()) {
                        state.mOrganizer.onTaskAppeared(task);
                    }
                    break;
                case PendingTaskEvent.EVENT_VANISHED:
                    state = mTaskOrganizerStates.get(event.mTaskOrg.asBinder());
                    if (state != null) {
                        state.mOrganizer.onTaskVanished(task);
                    }
                    break;
                case PendingTaskEvent.EVENT_INFO_CHANGED:
                    dispatchTaskInfoChanged(event.mTask, event.mForce);
                    break;
                case PendingTaskEvent.EVENT_ROOT_BACK_PRESSED:
                    state = mTaskOrganizerStates.get(event.mTaskOrg.asBinder());
                    if (state != null) {
                        state.mOrganizer.onBackPressedOnTaskRoot(task);
                    }
                    break;
            }
        }
        mPendingTaskEvents.clear();
    }

    void onTaskInfoChanged(Task task, boolean force) {
        if (!task.mTaskAppearedSent) {
            // Skip if task still not appeared.
            return;
        }

        // Defer task info reporting while layout is deferred. This is because layout defer
        // blocks tend to do lots of re-ordering which can mess up animations in receivers.
        PendingTaskEvent pending = getPendingLifecycleTaskEvent(task);
        if (pending == null) {
            pending = new PendingTaskEvent(task, PendingTaskEvent.EVENT_INFO_CHANGED);
        } else {
            if (pending.mEventType != PendingTaskEvent.EVENT_INFO_CHANGED) {
                // If queued event is appeared, it means task still not appeared so ignore
                // this info changed. If queued event is vanished, it means task should
                // will vanished early so do not need this info changed.
                return;
            }
            // Remove and add for re-ordering.
            mPendingTaskEvents.remove(pending);
        }
        pending.mForce |= force;
        mPendingTaskEvents.add(pending);
    }

    private void dispatchTaskInfoChanged(Task task, boolean force) {
        RunningTaskInfo lastInfo = mLastSentTaskInfos.get(task);
        if (mTmpTaskInfo == null) {
            mTmpTaskInfo = new RunningTaskInfo();
        }
        mTmpTaskInfo.configuration.unset();
        task.fillTaskInfo(mTmpTaskInfo);

        boolean changed = !mTmpTaskInfo.equalsForTaskOrganizer(lastInfo);
        if (!changed) {
            int cfgChanges = mTmpTaskInfo.configuration.diff(lastInfo.configuration);
            final int winCfgChanges = (cfgChanges & ActivityInfo.CONFIG_WINDOW_CONFIGURATION) != 0
                    ? (int) mTmpTaskInfo.configuration.windowConfiguration.diff(
                            lastInfo.configuration.windowConfiguration,
                            true /* compareUndefined */) : 0;
            if ((winCfgChanges & REPORT_WINDOW_CONFIGS) == 0) {
                cfgChanges &= ~ActivityInfo.CONFIG_WINDOW_CONFIGURATION;
            }
            changed = (cfgChanges & REPORT_CONFIGS) != 0;
        }
        if (!(changed || force)) {
            // mTmpTaskInfo will be reused next time.
            return;
        }
        final RunningTaskInfo newInfo = mTmpTaskInfo;
        mLastSentTaskInfos.put(task, mTmpTaskInfo);
        // Since we've stored this, clean up the reference so a new one will be created next time.
        // Transferring it this way means we only have to construct new RunningTaskInfos when they
        // change.
        mTmpTaskInfo = null;

        if (task.isOrganized()) {
            // Because we defer sending taskAppeared() until the app has drawn, we may receive a
            // configuration change before the state actually has the task registered. As such we
            // should ignore these change events to the organizer until taskAppeared(). If the task
            // was created by the organizer, then we always send the info change.
            final TaskOrganizerState state = mTaskOrganizerStates.get(
                    task.mTaskOrganizer.asBinder());
            if (state != null) {
                state.mOrganizer.onTaskInfoChanged(task, newInfo);
            }
        }
    }

    @Override
    public WindowContainerToken getImeTarget(int displayId) {
        enforceTaskPermission("getImeTarget()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                DisplayContent dc = mService.mWindowManager.mRoot
                        .getDisplayContent(displayId);
                if (dc == null || dc.getImeTarget(IME_TARGET_LAYERING) == null) {
                    return null;
                }
                // Avoid WindowState#getRootTask() so we don't attribute system windows to a task.
                final Task task = dc.getImeTarget(IME_TARGET_LAYERING).getWindow().getTask();
                if (task == null) {
                    return null;
                }
                return task.getRootTask().mRemoteToken.toWindowContainerToken();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public List<RunningTaskInfo> getChildTasks(WindowContainerToken parent,
            @Nullable int[] activityTypes) {
        enforceTaskPermission("getChildTasks()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                if (parent == null) {
                    throw new IllegalArgumentException("Can't get children of null parent");
                }
                final WindowContainer container = WindowContainer.fromBinder(parent.asBinder());
                if (container == null) {
                    Slog.e(TAG, "Can't get children of " + parent + " because it is not valid.");
                    return null;
                }
                final Task task = container.asTask();
                if (task == null) {
                    Slog.e(TAG, container + " is not a task...");
                    return null;
                }
                // For now, only support returning children of tasks created by the organizer.
                if (!task.mCreatedByOrganizer) {
                    Slog.w(TAG, "Can only get children of root tasks created via createRootTask");
                    return null;
                }
                ArrayList<RunningTaskInfo> out = new ArrayList<>();
                for (int i = task.getChildCount() - 1; i >= 0; --i) {
                    final Task child = task.getChildAt(i).asTask();
                    if (child == null) continue;
                    if (activityTypes != null
                            && !ArrayUtils.contains(activityTypes, child.getActivityType())) {
                        continue;
                    }
                    out.add(child.getTaskInfo());
                }
                return out;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public List<RunningTaskInfo> getRootTasks(int displayId, @Nullable int[] activityTypes) {
        enforceTaskPermission("getRootTasks()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final DisplayContent dc =
                        mService.mRootWindowContainer.getDisplayContent(displayId);
                if (dc == null) {
                    throw new IllegalArgumentException("Display " + displayId + " doesn't exist");
                }
                final ArrayList<RunningTaskInfo> out = new ArrayList<>();
                dc.forAllRootTasks(task -> {
                    if (activityTypes != null
                            && !ArrayUtils.contains(activityTypes, task.getActivityType())) {
                        return;
                    }
                    out.add(task.getTaskInfo());
                });
                return out;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setInterceptBackPressedOnTaskRoot(WindowContainerToken token,
            boolean interceptBackPressed) {
        enforceTaskPermission("setInterceptBackPressedOnTaskRoot()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Set intercept back pressed on root=%b",
                        interceptBackPressed);
                final WindowContainer wc = WindowContainer.fromBinder(token.asBinder());
                if (wc == null) {
                    Slog.w(TAG, "Could not resolve window from token");
                    return;
                }
                final Task task = wc.asTask();
                if (task == null) {
                    Slog.w(TAG, "Could not resolve task from token");
                    return;
                }
                if (interceptBackPressed) {
                    mInterceptBackPressedOnRootTasks.add(task.mTaskId);
                } else {
                    mInterceptBackPressedOnRootTasks.remove(task.mTaskId);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void restartTaskTopActivityProcessIfVisible(WindowContainerToken token) {
        enforceTaskPermission("restartTopActivityProcessIfVisible()");
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                final WindowContainer wc = WindowContainer.fromBinder(token.asBinder());
                if (wc == null) {
                    Slog.w(TAG, "Could not resolve window from token");
                    return;
                }
                final Task task = wc.asTask();
                if (task == null) {
                    Slog.w(TAG, "Could not resolve task from token");
                    return;
                }
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                        "Restart top activity process of Task taskId=%d", task.mTaskId);
                final ActivityRecord activity = task.getTopNonFinishingActivity();
                if (activity != null) {
                    activity.restartProcessIfVisible();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public boolean handleInterceptBackPressedOnTaskRoot(Task task) {
        if (task == null || !task.isOrganized()
                || !mInterceptBackPressedOnRootTasks.contains(task.mTaskId)) {
            return false;
        }

        PendingTaskEvent pendingVanished =
                getPendingTaskEvent(task, PendingTaskEvent.EVENT_VANISHED);
        if (pendingVanished != null) {
            // This task will vanish before this callback so just ignore.
            return false;
        }

        PendingTaskEvent pending = getPendingTaskEvent(
                task, PendingTaskEvent.EVENT_ROOT_BACK_PRESSED);
        if (pending == null) {
            pending = new PendingTaskEvent(task, PendingTaskEvent.EVENT_ROOT_BACK_PRESSED);
        } else {
            // Pending already exist, remove and add for re-ordering.
            mPendingTaskEvents.remove(pending);
        }
        mPendingTaskEvents.add(pending);
        mService.mWindowManager.mWindowPlacerLocked.requestTraversal();
        return true;
    }

    @Nullable
    private PendingTaskEvent getPendingTaskEvent(Task task, int type) {
        for (int i = mPendingTaskEvents.size() - 1; i >= 0; i--) {
            PendingTaskEvent entry = mPendingTaskEvents.get(i);
            if (task.mTaskId == entry.mTask.mTaskId && type == entry.mEventType) {
                return entry;
            }
        }
        return null;
    }

    @VisibleForTesting
    @Nullable
    PendingTaskEvent getPendingLifecycleTaskEvent(Task task) {
        for (int i = mPendingTaskEvents.size() - 1; i >= 0; i--) {
            PendingTaskEvent entry = mPendingTaskEvents.get(i);
            if (task.mTaskId == entry.mTask.mTaskId && entry.isLifecycleEvent()) {
                return entry;
            }
        }
        return null;
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.print(prefix); pw.println("TaskOrganizerController:");
        for (final TaskOrganizerState state : mTaskOrganizerStates.values()) {
            final ArrayList<Task> tasks = state.mOrganizedTasks;
            pw.print(innerPrefix + "  ");
            pw.println(state.mOrganizer.mTaskOrganizer + " uid=" + state.mUid + ":");
            for (int k = 0; k < tasks.size(); k++) {
                final Task task = tasks.get(k);
                final int mode = task.getWindowingMode();
                if (ArrayUtils.contains(UNSUPPORTED_WINDOWING_MODES, mode)) {
                    continue;
                }
                pw.println(innerPrefix + "    ("
                        + WindowConfiguration.windowingModeToString(mode) + ") " + task);
            }

        }
        pw.println();
    }
}
