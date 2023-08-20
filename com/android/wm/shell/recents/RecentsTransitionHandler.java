/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.recents;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_LOCKED;
import static android.view.WindowManager.TRANSIT_SLEEP;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IApplicationThread;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.Display;
import android.view.IRecentsAnimationController;
import android.view.IRecentsAnimationRunner;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.window.PictureInPictureSurfaceTransaction;
import android.window.TaskSnapshot;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.util.TransitionUtil;

import java.util.ArrayList;

/**
 * Handles the Recents (overview) animation. Only one of these can run at a time. A recents
 * transition must be created via {@link #startRecentsTransition}. Anything else will be ignored.
 */
public class RecentsTransitionHandler implements Transitions.TransitionHandler {
    private static final String TAG = "RecentsTransitionHandler";

    private final Transitions mTransitions;
    private final ShellExecutor mExecutor;
    private IApplicationThread mAnimApp = null;
    private final ArrayList<RecentsController> mControllers = new ArrayList<>();

    /**
     * List of other handlers which might need to mix recents with other things. These are checked
     * in the order they are added. Ideally there should only be one.
     */
    private final ArrayList<RecentsMixedHandler> mMixers = new ArrayList<>();

    public RecentsTransitionHandler(ShellInit shellInit, Transitions transitions,
            @Nullable RecentTasksController recentTasksController) {
        mTransitions = transitions;
        mExecutor = transitions.getMainExecutor();
        if (!Transitions.ENABLE_SHELL_TRANSITIONS) return;
        if (recentTasksController == null) return;
        shellInit.addInitCallback(() -> {
            recentTasksController.setTransitionHandler(this);
            transitions.addHandler(this);
        }, this);
    }

    /** Register a mixer handler. {@see RecentsMixedHandler}*/
    public void addMixer(RecentsMixedHandler mixer) {
        mMixers.add(mixer);
    }

    /** Unregister a Mixed Handler */
    public void removeMixer(RecentsMixedHandler mixer) {
        mMixers.remove(mixer);
    }

    @VisibleForTesting
    public IBinder startRecentsTransition(PendingIntent intent, Intent fillIn, Bundle options,
            IApplicationThread appThread, IRecentsAnimationRunner listener) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                "RecentsTransitionHandler.startRecentsTransition");

        // only care about latest one.
        mAnimApp = appThread;
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.sendPendingIntent(intent, fillIn, options);
        final RecentsController controller = new RecentsController(listener);
        RecentsMixedHandler mixer = null;
        Transitions.TransitionHandler mixedHandler = null;
        for (int i = 0; i < mMixers.size(); ++i) {
            mixedHandler = mMixers.get(i).handleRecentsRequest(wct);
            if (mixedHandler != null) {
                mixer = mMixers.get(i);
                break;
            }
        }
        final IBinder transition = mTransitions.startTransition(TRANSIT_TO_FRONT, wct,
                mixedHandler == null ? this : mixedHandler);
        if (mixer != null) {
            mixer.setRecentsTransition(transition);
        }
        if (transition != null) {
            controller.setTransition(transition);
            mControllers.add(controller);
        } else {
            controller.cancel("startRecentsTransition");
        }
        return transition;
    }

    @Override
    public WindowContainerTransaction handleRequest(IBinder transition,
            TransitionRequestInfo request) {
        // do not directly handle requests. Only entry point should be via startRecentsTransition
        // TODO: Only log an error if the transition is a recents transition
        return null;
    }

    private int findController(IBinder transition) {
        for (int i = mControllers.size() - 1; i >= 0; --i) {
            if (mControllers.get(i).mTransition == transition) return i;
        }
        return -1;
    }

    @Override
    public boolean startAnimation(IBinder transition, TransitionInfo info,
            SurfaceControl.Transaction startTransaction,
            SurfaceControl.Transaction finishTransaction,
            Transitions.TransitionFinishCallback finishCallback) {
        final int controllerIdx = findController(transition);
        if (controllerIdx < 0) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                    "RecentsTransitionHandler.startAnimation: no controller found");
            return false;
        }
        final RecentsController controller = mControllers.get(controllerIdx);
        Transitions.setRunningRemoteTransitionDelegate(mAnimApp);
        mAnimApp = null;
        if (!controller.start(info, startTransaction, finishTransaction, finishCallback)) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                    "RecentsTransitionHandler.startAnimation: failed to start animation");
            return false;
        }
        return true;
    }

    @Override
    public void mergeAnimation(IBinder transition, TransitionInfo info,
            SurfaceControl.Transaction t, IBinder mergeTarget,
            Transitions.TransitionFinishCallback finishCallback) {
        final int targetIdx = findController(mergeTarget);
        if (targetIdx < 0) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                    "RecentsTransitionHandler.mergeAnimation: no controller found");
            return;
        }
        final RecentsController controller = mControllers.get(targetIdx);
        controller.merge(info, t, finishCallback);
    }

    @Override
    public void onTransitionConsumed(IBinder transition, boolean aborted,
            SurfaceControl.Transaction finishTransaction) {
        // Only one recents transition can be handled at a time, but currently the first transition
        // will trigger a no-op in the second transition which holds the active recents animation
        // runner on the launcher side.  For now, cancel all existing animations to ensure we
        // don't get into a broken state with an orphaned animation runner, and later we can try to
        // merge the latest transition into the currently running one
        for (int i = mControllers.size() - 1; i >= 0; i--) {
            mControllers.get(i).cancel("onTransitionConsumed");
        }
    }

    /** There is only one of these and it gets reset on finish. */
    private class RecentsController extends IRecentsAnimationController.Stub {
        private final int mInstanceId;

        private IRecentsAnimationRunner mListener;
        private IBinder.DeathRecipient mDeathHandler;
        private Transitions.TransitionFinishCallback mFinishCB = null;
        private SurfaceControl.Transaction mFinishTransaction = null;

        /**
         * List of tasks that we are switching away from via this transition. Upon finish, these
         * pausing tasks will become invisible.
         * These need to be ordered since the order must be restored if there is no task-switch.
         */
        private ArrayList<TaskState> mPausingTasks = null;

        /**
         * List of tasks that we are switching to. Upon finish, these will remain visible and
         * on top.
         */
        private ArrayList<TaskState> mOpeningTasks = null;

        private WindowContainerToken mPipTask = null;
        private WindowContainerToken mRecentsTask = null;
        private int mRecentsTaskId = -1;
        private TransitionInfo mInfo = null;
        private boolean mOpeningSeparateHome = false;
        private boolean mPausingSeparateHome = false;
        private ArrayMap<SurfaceControl, SurfaceControl> mLeashMap = null;
        private PictureInPictureSurfaceTransaction mPipTransaction = null;
        private IBinder mTransition = null;
        private boolean mKeyguardLocked = false;
        private boolean mWillFinishToHome = false;

        /** The animation is idle, waiting for the user to choose a task to switch to. */
        private static final int STATE_NORMAL = 0;

        /** The user chose a new task to switch to and the animation is animating to it. */
        private static final int STATE_NEW_TASK = 1;

        /** The latest state that the recents animation is operating in. */
        private int mState = STATE_NORMAL;

        RecentsController(IRecentsAnimationRunner listener) {
            mInstanceId = System.identityHashCode(this);
            mListener = listener;
            mDeathHandler = () -> {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                        "[%d] RecentsController.DeathRecipient: binder died", mInstanceId);
                finish(mWillFinishToHome, false /* leaveHint */);
            };
            try {
                mListener.asBinder().linkToDeath(mDeathHandler, 0 /* flags */);
            } catch (RemoteException e) {
                Slog.e(TAG, "RecentsController: failed to link to death", e);
                mListener = null;
            }
        }

        void setTransition(IBinder transition) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                    "[%d] RecentsController.setTransition: id=%s", mInstanceId, transition);
            mTransition = transition;
        }

        void cancel(String reason) {
            // restoring (to-home = false) involves submitting more WM changes, so by default, use
            // toHome = true when canceling.
            cancel(true /* toHome */, reason);
        }

        void cancel(boolean toHome, String reason) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                    "[%d] RecentsController.cancel: toHome=%b reason=%s",
                    mInstanceId, toHome, reason);
            if (mListener != null) {
                try {
                    ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                            "[%d] RecentsController.cancel: calling onAnimationCanceled",
                            mInstanceId);
                    mListener.onAnimationCanceled(null, null);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error canceling recents animation", e);
                }
            }
            if (mFinishCB != null) {
                finishInner(toHome, false /* userLeave */);
            } else {
                cleanUp();
            }
        }

        /**
         * Sends a cancel message to the recents animation with snapshots. Used to trigger a
         * "replace-with-screenshot" like behavior.
         */
        private boolean sendCancelWithSnapshots() {
            int[] taskIds = null;
            TaskSnapshot[] snapshots = null;
            if (mPausingTasks.size() > 0) {
                taskIds = new int[mPausingTasks.size()];
                snapshots = new TaskSnapshot[mPausingTasks.size()];
                try {
                    for (int i = 0; i < mPausingTasks.size(); ++i) {
                        snapshots[i] = ActivityTaskManager.getService().takeTaskSnapshot(
                                mPausingTasks.get(0).mTaskInfo.taskId);
                    }
                } catch (RemoteException e) {
                    taskIds = null;
                    snapshots = null;
                }
            }
            try {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                        "[%d] RecentsController.cancel: calling onAnimationCanceled with snapshots",
                        mInstanceId);
                mListener.onAnimationCanceled(taskIds, snapshots);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error canceling recents animation", e);
                return false;
            }
            return true;
        }

        void cleanUp() {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                    "[%d] RecentsController.cleanup", mInstanceId);
            if (mListener != null && mDeathHandler != null) {
                mListener.asBinder().unlinkToDeath(mDeathHandler, 0 /* flags */);
                mDeathHandler = null;
            }
            mListener = null;
            mFinishCB = null;
            // clean-up leash surfacecontrols and anything that might reference them.
            if (mLeashMap != null) {
                for (int i = 0; i < mLeashMap.size(); ++i) {
                    mLeashMap.valueAt(i).release();
                }
                mLeashMap = null;
            }
            mFinishTransaction = null;
            mPausingTasks = null;
            mOpeningTasks = null;
            mInfo = null;
            mTransition = null;
            mControllers.remove(this);
        }

        boolean start(TransitionInfo info, SurfaceControl.Transaction t,
                SurfaceControl.Transaction finishT, Transitions.TransitionFinishCallback finishCB) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                    "[%d] RecentsController.start", mInstanceId);
            if (mListener == null || mTransition == null) {
                cleanUp();
                return false;
            }
            // First see if this is a valid recents transition.
            boolean hasPausingTasks = false;
            for (int i = 0; i < info.getChanges().size(); ++i) {
                final TransitionInfo.Change change = info.getChanges().get(i);
                if (TransitionUtil.isWallpaper(change)) continue;
                if (TransitionUtil.isClosingType(change.getMode())) {
                    hasPausingTasks = true;
                    continue;
                }
                final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
                if (taskInfo != null && taskInfo.topActivityType == ACTIVITY_TYPE_RECENTS) {
                    mRecentsTask = taskInfo.token;
                    mRecentsTaskId = taskInfo.taskId;
                } else if (taskInfo != null && taskInfo.topActivityType == ACTIVITY_TYPE_HOME) {
                    mRecentsTask = taskInfo.token;
                    mRecentsTaskId = taskInfo.taskId;
                }
            }
            if (mRecentsTask == null && !hasPausingTasks) {
                // Recents is already running apparently, so this is a no-op.
                Slog.e(TAG, "Tried to start recents while it is already running.");
                cleanUp();
                return false;
            }

            mInfo = info;
            mFinishCB = finishCB;
            mFinishTransaction = finishT;
            mPausingTasks = new ArrayList<>();
            mOpeningTasks = new ArrayList<>();
            mLeashMap = new ArrayMap<>();
            mKeyguardLocked = (info.getFlags() & TRANSIT_FLAG_KEYGUARD_LOCKED) != 0;

            final ArrayList<RemoteAnimationTarget> apps = new ArrayList<>();
            final ArrayList<RemoteAnimationTarget> wallpapers = new ArrayList<>();
            TransitionUtil.LeafTaskFilter leafTaskFilter = new TransitionUtil.LeafTaskFilter();
            // About layering: we divide up the "layer space" into 3 regions (each the size of
            // the change count). This lets us categorize things into above/below/between
            // while maintaining their relative ordering.
            for (int i = 0; i < info.getChanges().size(); ++i) {
                final TransitionInfo.Change change = info.getChanges().get(i);
                final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
                if (TransitionUtil.isWallpaper(change)) {
                    final RemoteAnimationTarget target = TransitionUtil.newTarget(change,
                            // wallpapers go into the "below" layer space
                            info.getChanges().size() - i, info, t, mLeashMap);
                    wallpapers.add(target);
                    // Make all the wallpapers opaque since we want them visible from the start
                    t.setAlpha(target.leash, 1);
                } else if (leafTaskFilter.test(change)) {
                    // start by putting everything into the "below" layer space.
                    final RemoteAnimationTarget target = TransitionUtil.newTarget(change,
                            info.getChanges().size() - i, info, t, mLeashMap);
                    apps.add(target);
                    if (TransitionUtil.isClosingType(change.getMode())) {
                        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                                "  adding pausing taskId=%d", taskInfo.taskId);
                        // raise closing (pausing) task to "above" layer so it isn't covered
                        t.setLayer(target.leash, info.getChanges().size() * 3 - i);
                        mPausingTasks.add(new TaskState(change, target.leash));
                        if (taskInfo.topActivityType == ACTIVITY_TYPE_HOME) {
                            // This can only happen if we have a separate recents/home (3p launcher)
                            mPausingSeparateHome = true;
                        }
                        if (taskInfo.pictureInPictureParams != null
                                && taskInfo.pictureInPictureParams.isAutoEnterEnabled()) {
                            mPipTask = taskInfo.token;
                        }
                    } else if (taskInfo != null
                            && taskInfo.topActivityType == ACTIVITY_TYPE_RECENTS) {
                        // There's a 3p launcher, so make sure recents goes above that.
                        t.setLayer(target.leash, info.getChanges().size() * 3 - i);
                    } else if (taskInfo != null && taskInfo.topActivityType == ACTIVITY_TYPE_HOME) {
                        // do nothing
                    } else if (TransitionUtil.isOpeningType(change.getMode())) {
                        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                                "  adding opening taskId=%d", taskInfo.taskId);
                        mOpeningTasks.add(new TaskState(change, target.leash));
                    }
                } else if (TransitionUtil.isDividerBar(change)) {
                    final RemoteAnimationTarget target = TransitionUtil.newTarget(change,
                            info.getChanges().size() - i, info, t, mLeashMap);
                    // Add this as a app and we will separate them on launcher side by window type.
                    apps.add(target);
                }
            }
            t.apply();
            try {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                        "[%d] RecentsController.start: calling onAnimationStart", mInstanceId);
                mListener.onAnimationStart(this,
                        apps.toArray(new RemoteAnimationTarget[apps.size()]),
                        wallpapers.toArray(new RemoteAnimationTarget[wallpapers.size()]),
                        new Rect(0, 0, 0, 0), new Rect());
            } catch (RemoteException e) {
                Slog.e(TAG, "Error starting recents animation", e);
                cancel("onAnimationStart() failed");
            }
            return true;
        }

        @SuppressLint("NewApi")
        void merge(TransitionInfo info, SurfaceControl.Transaction t,
                Transitions.TransitionFinishCallback finishCallback) {
            if (mFinishCB == null) {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                        "[%d] RecentsController.merge: skip, no finish callback",
                        mInstanceId);
                // This was no-op'd (likely a repeated start) and we've already sent finish.
                return;
            }
            if (info.getType() == TRANSIT_SLEEP) {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                        "[%d] RecentsController.merge: transit_sleep", mInstanceId);
                // A sleep event means we need to stop animations immediately, so cancel here.
                cancel("transit_sleep");
                return;
            }
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                    "[%d] RecentsController.merge", mInstanceId);
            ArrayList<TransitionInfo.Change> openingTasks = null;
            ArrayList<TransitionInfo.Change> closingTasks = null;
            mOpeningSeparateHome = false;
            TransitionInfo.Change recentsOpening = null;
            boolean foundRecentsClosing = false;
            boolean hasChangingApp = false;
            final TransitionUtil.LeafTaskFilter leafTaskFilter =
                    new TransitionUtil.LeafTaskFilter();
            boolean hasTaskChange = false;
            for (int i = 0; i < info.getChanges().size(); ++i) {
                final TransitionInfo.Change change = info.getChanges().get(i);
                final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
                if (taskInfo != null
                        && taskInfo.configuration.windowConfiguration.isAlwaysOnTop()) {
                    // Tasks that are always on top (e.g. bubbles), will handle their own transition
                    // as they are on top of everything else. So cancel the merge here.
                    cancel("task #" + taskInfo.taskId + " is always_on_top");
                    return;
                }
                hasTaskChange = hasTaskChange || taskInfo != null;
                final boolean isLeafTask = leafTaskFilter.test(change);
                if (TransitionUtil.isOpeningType(change.getMode())) {
                    if (mRecentsTask != null && mRecentsTask.equals(change.getContainer())) {
                        recentsOpening = change;
                    } else if (isLeafTask) {
                        if (taskInfo.topActivityType == ACTIVITY_TYPE_HOME) {
                            // This is usually a 3p launcher
                            mOpeningSeparateHome = true;
                        }
                        if (openingTasks == null) {
                            openingTasks = new ArrayList<>();
                        }
                        openingTasks.add(change);
                    }
                } else if (TransitionUtil.isClosingType(change.getMode())) {
                    if (mRecentsTask != null && mRecentsTask.equals(change.getContainer())) {
                        foundRecentsClosing = true;
                    } else if (isLeafTask) {
                        if (closingTasks == null) {
                            closingTasks = new ArrayList<>();
                        }
                        closingTasks.add(change);
                    }
                } else if (change.getMode() == TRANSIT_CHANGE) {
                    // Finish recents animation if the display is changed, so the default
                    // transition handler can play the animation such as rotation effect.
                    if (change.hasFlags(TransitionInfo.FLAG_IS_DISPLAY)) {
                        cancel(mWillFinishToHome, "display change");
                        return;
                    }
                    // Don't consider order-only changes as changing apps.
                    if (!TransitionUtil.isOrderOnly(change)) {
                        hasChangingApp = true;
                    }
                }
            }
            if (hasChangingApp && foundRecentsClosing) {
                // This happens when a visible app is expanding (usually PiP). In this case,
                // that transition probably has a special-purpose animation, so finish recents
                // now and let it do its animation (since recents is going to be occluded).
                sendCancelWithSnapshots();
                mExecutor.executeDelayed(
                        () -> finishInner(true /* toHome */, false /* userLeaveHint */), 0);
                return;
            }
            if (recentsOpening != null) {
                // the recents task re-appeared. This happens if the user gestures before the
                // task-switch (NEW_TASK) animation finishes.
                if (mState == STATE_NORMAL) {
                    Slog.e(TAG, "Returning to recents while recents is already idle.");
                }
                if (closingTasks == null || closingTasks.size() == 0) {
                    Slog.e(TAG, "Returning to recents without closing any opening tasks.");
                }
                // Setup may hide it initially since it doesn't know that overview was still active.
                t.show(recentsOpening.getLeash());
                t.setAlpha(recentsOpening.getLeash(), 1.f);
                mState = STATE_NORMAL;
            }
            boolean didMergeThings = false;
            if (closingTasks != null) {
                // Potentially cancelling a task-switch. Move the tasks back to mPausing if they
                // are in mOpening.
                for (int i = 0; i < closingTasks.size(); ++i) {
                    final TransitionInfo.Change change = closingTasks.get(i);
                    final int pausingIdx = TaskState.indexOf(mPausingTasks, change);
                    if (pausingIdx >= 0) {
                        mPausingTasks.remove(pausingIdx);
                        didMergeThings = true;
                        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                                "  closing pausing taskId=%d", change.getTaskInfo().taskId);
                        continue;
                    }
                    int openingIdx = TaskState.indexOf(mOpeningTasks, change);
                    if (openingIdx < 0) {
                        Slog.w(TAG, "Closing a task that wasn't opening, this may be split or"
                                + " something unexpected: " + change.getTaskInfo().taskId);
                        continue;
                    }
                    final TaskState openingTask = mOpeningTasks.remove(openingIdx);
                    ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                            "  pausing opening taskId=%d", openingTask.mTaskInfo.taskId);
                    mPausingTasks.add(openingTask);
                    didMergeThings = true;
                }
            }
            RemoteAnimationTarget[] appearedTargets = null;
            if (openingTasks != null && openingTasks.size() > 0) {
                // Switching to some new tasks, add to mOpening and remove from mPausing. Also,
                // enter NEW_TASK state since this will start the switch-to animation.
                final int layer = mInfo.getChanges().size() * 3;
                appearedTargets = new RemoteAnimationTarget[openingTasks.size()];
                for (int i = 0; i < openingTasks.size(); ++i) {
                    final TransitionInfo.Change change = openingTasks.get(i);
                    int pausingIdx = TaskState.indexOf(mPausingTasks, change);
                    if (pausingIdx >= 0) {
                        // Something is showing/opening a previously-pausing app.
                        appearedTargets[i] = TransitionUtil.newTarget(
                                change, layer, mPausingTasks.get(pausingIdx).mLeash);
                        final TaskState pausingTask = mPausingTasks.remove(pausingIdx);
                        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                                "  opening pausing taskId=%d", pausingTask.mTaskInfo.taskId);
                        mOpeningTasks.add(pausingTask);
                        // Setup hides opening tasks initially, so make it visible again (since we
                        // are already showing it).
                        t.show(change.getLeash());
                        t.setAlpha(change.getLeash(), 1.f);
                    } else {
                        // We are receiving new opening tasks, so convert to onTasksAppeared.
                        appearedTargets[i] = TransitionUtil.newTarget(
                                change, layer, info, t, mLeashMap);
                        // reparent into the original `mInfo` since that's where we are animating.
                        final int rootIdx = TransitionUtil.rootIndexFor(change, mInfo);
                        t.reparent(appearedTargets[i].leash, mInfo.getRoot(rootIdx).getLeash());
                        t.setLayer(appearedTargets[i].leash, layer);
                        // Hide the animation leash, let listener show it.
                        t.hide(appearedTargets[i].leash);
                        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                                "  opening new taskId=%d", appearedTargets[i].taskId);
                        mOpeningTasks.add(new TaskState(change, appearedTargets[i].leash));
                    }
                }
                didMergeThings = true;
                mState = STATE_NEW_TASK;
            }
            if (mPausingTasks.isEmpty()) {
                // The pausing tasks may be removed by the incoming closing tasks.
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                        "[%d] RecentsController.merge: empty pausing tasks", mInstanceId);
            }
            if (!hasTaskChange) {
                // Activity only transition, so consume the merge as it doesn't affect the rest of
                // recents.
                Slog.d(TAG, "Got an activity only transition during recents, so apply directly");
                mergeActivityOnly(info, t);
            } else if (!didMergeThings) {
                // Didn't recognize anything in incoming transition so don't merge it.
                Slog.w(TAG, "Don't know how to merge this transition, foundRecentsClosing="
                        + foundRecentsClosing);
                if (foundRecentsClosing) {
                    mWillFinishToHome = false;
                    cancel(false /* toHome */, "didn't merge");
                }
                return;
            }
            // At this point, we are accepting the merge.
            t.apply();
            // not using the incoming anim-only surfaces
            info.releaseAnimSurfaces();
            if (appearedTargets != null) {
                try {
                    ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                            "[%d] RecentsController.merge: calling onTasksAppeared", mInstanceId);
                    mListener.onTasksAppeared(appearedTargets);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error sending appeared tasks to recents animation", e);
                }
            }
            finishCallback.onTransitionFinished(null /* wct */, null /* wctCB */);
        }

        /** For now, just set-up a jump-cut to the new activity. */
        private void mergeActivityOnly(TransitionInfo info, SurfaceControl.Transaction t) {
            for (int i = 0; i < info.getChanges().size(); ++i) {
                final TransitionInfo.Change change = info.getChanges().get(i);
                if (TransitionUtil.isOpeningType(change.getMode())) {
                    t.show(change.getLeash());
                    t.setAlpha(change.getLeash(), 1.f);
                } else if (TransitionUtil.isClosingType(change.getMode())) {
                    t.hide(change.getLeash());
                }
            }
        }

        @Override
        public TaskSnapshot screenshotTask(int taskId) {
            try {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                        "[%d] RecentsController.screenshotTask: taskId=%d", mInstanceId, taskId);
                return ActivityTaskManager.getService().takeTaskSnapshot(taskId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to screenshot task", e);
            }
            return null;
        }

        @Override
        public void setInputConsumerEnabled(boolean enabled) {
            mExecutor.execute(() -> {
                if (mFinishCB == null || !enabled) {
                    ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                            "RecentsController.setInputConsumerEnabled: skip, cb?=%b enabled?=%b",
                            mFinishCB != null, enabled);
                    return;
                }
                final int displayId = mInfo.getRootCount() > 0 ? mInfo.getRoot(0).getDisplayId()
                        : Display.DEFAULT_DISPLAY;
                // transient launches don't receive focus automatically. Since we are taking over
                // the gesture now, take focus explicitly.
                // This also moves recents back to top if the user gestured before a switch
                // animation finished.
                try {
                    ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                            "[%d] RecentsController.setInputConsumerEnabled: set focus to recents",
                            mInstanceId);
                    ActivityTaskManager.getService().focusTopTask(displayId);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to set focused task", e);
                }
            });
        }

        @Override
        public void setAnimationTargetsBehindSystemBars(boolean behindSystemBars) {
        }

        @Override
        public void setFinishTaskTransaction(int taskId,
                PictureInPictureSurfaceTransaction finishTransaction, SurfaceControl overlay) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                    "[%d] RecentsController.setFinishTaskTransaction: taskId=%d",
                    mInstanceId, taskId);
            mExecutor.execute(() -> {
                if (mFinishCB == null) return;
                mPipTransaction = finishTransaction;
            });
        }

        @Override
        @SuppressLint("NewApi")
        public void finish(boolean toHome, boolean sendUserLeaveHint) {
            mExecutor.execute(() -> finishInner(toHome, sendUserLeaveHint));
        }

        private void finishInner(boolean toHome, boolean sendUserLeaveHint) {
            if (mFinishCB == null) {
                Slog.e(TAG, "Duplicate call to finish");
                return;
            }
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                    "[%d] RecentsController.finishInner: toHome=%b userLeave=%b "
                            + "willFinishToHome=%b state=%d",
                    mInstanceId, toHome, sendUserLeaveHint, mWillFinishToHome, mState);
            final Transitions.TransitionFinishCallback finishCB = mFinishCB;
            mFinishCB = null;

            final SurfaceControl.Transaction t = mFinishTransaction;
            final WindowContainerTransaction wct = new WindowContainerTransaction();

            if (mKeyguardLocked && mRecentsTask != null) {
                if (toHome) wct.reorder(mRecentsTask, true /* toTop */);
                else wct.restoreTransientOrder(mRecentsTask);
            }
            if (!toHome
                    // If a recents gesture starts on the 3p launcher, then the 3p launcher is the
                    // live tile (pausing app). If the gesture is "cancelled" we need to return to
                    // 3p launcher instead of "task-switching" away from it.
                    && (!mWillFinishToHome || mPausingSeparateHome)
                    && mPausingTasks != null && mState == STATE_NORMAL) {
                if (mPausingSeparateHome) {
                    ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                            "  returning to 3p home");
                } else {
                    ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                            "  returning to app");
                }
                // The gesture is returning to the pausing-task(s) rather than continuing with
                // recents, so end the transition by moving the app back to the top (and also
                // re-showing it's task).
                for (int i = mPausingTasks.size() - 1; i >= 0; --i) {
                    // reverse order so that index 0 ends up on top
                    wct.reorder(mPausingTasks.get(i).mToken, true /* onTop */);
                    t.show(mPausingTasks.get(i).mTaskSurface);
                }
                if (!mKeyguardLocked && mRecentsTask != null) {
                    wct.restoreTransientOrder(mRecentsTask);
                }
            } else if (toHome && mOpeningSeparateHome && mPausingTasks != null) {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION, "  3p launching home");
                // Special situation where 3p launcher was changed during recents (this happens
                // during tapltests...). Here we get both "return to home" AND "home opening".
                // This is basically going home, but we have to restore the recents and home order.
                for (int i = 0; i < mOpeningTasks.size(); ++i) {
                    final TaskState state = mOpeningTasks.get(i);
                    if (state.mTaskInfo.topActivityType == ACTIVITY_TYPE_HOME) {
                        // Make sure it is on top.
                        wct.reorder(state.mToken, true /* onTop */);
                    }
                    t.show(state.mTaskSurface);
                }
                for (int i = mPausingTasks.size() - 1; i >= 0; --i) {
                    t.hide(mPausingTasks.get(i).mTaskSurface);
                }
                if (!mKeyguardLocked && mRecentsTask != null) {
                    wct.restoreTransientOrder(mRecentsTask);
                }
            } else {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION, "  normal finish");
                // The general case: committing to recents, going home, or switching tasks.
                for (int i = 0; i < mOpeningTasks.size(); ++i) {
                    t.show(mOpeningTasks.get(i).mTaskSurface);
                }
                for (int i = 0; i < mPausingTasks.size(); ++i) {
                    if (!sendUserLeaveHint) {
                        // This means recents is not *actually* finishing, so of course we gotta
                        // do special stuff in WMCore to accommodate.
                        wct.setDoNotPip(mPausingTasks.get(i).mToken);
                    }
                    // Since we will reparent out of the leashes, pre-emptively hide the child
                    // surface to match the leash. Otherwise, there will be a flicker before the
                    // visibility gets committed in Core when using split-screen (in splitscreen,
                    // the leaf-tasks are not "independent" so aren't hidden by normal setup).
                    t.hide(mPausingTasks.get(i).mTaskSurface);
                }
                if (mPipTask != null && mPipTransaction != null && sendUserLeaveHint) {
                    t.show(mInfo.getChange(mPipTask).getLeash());
                    PictureInPictureSurfaceTransaction.apply(mPipTransaction,
                            mInfo.getChange(mPipTask).getLeash(), t);
                    mPipTask = null;
                    mPipTransaction = null;
                }
            }
            cleanUp();
            finishCB.onTransitionFinished(wct.isEmpty() ? null : wct, null /* wctCB */);
        }

        @Override
        public void setDeferCancelUntilNextTransition(boolean defer, boolean screenshot) {
        }

        @Override
        public void cleanupScreenshot() {
        }

        @Override
        public void setWillFinishToHome(boolean willFinishToHome) {
            mExecutor.execute(() -> {
                mWillFinishToHome = willFinishToHome;
            });
        }

        /**
         * @see IRecentsAnimationController#removeTask
         */
        @Override
        public boolean removeTask(int taskId) {
            return false;
        }

        /**
         * @see IRecentsAnimationController#detachNavigationBarFromApp
         */
        @Override
        public void detachNavigationBarFromApp(boolean moveHomeToTop) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENTS_TRANSITION,
                    "[%d] RecentsController.detachNavigationBarFromApp", mInstanceId);
            mExecutor.execute(() -> {
                if (mTransition == null) return;
                try {
                    ActivityTaskManager.getService().detachNavigationBarFromApp(mTransition);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to detach the navigation bar from app", e);
                }
            });
        }

        /**
         * @see IRecentsAnimationController#animateNavigationBarToApp(long)
         */
        @Override
        public void animateNavigationBarToApp(long duration) {
        }
    };

    /** Utility class to track the state of a task as-seen by recents. */
    private static class TaskState {
        WindowContainerToken mToken;
        ActivityManager.RunningTaskInfo mTaskInfo;

        /** The surface/leash of the task provided by Core. */
        SurfaceControl mTaskSurface;

        /** The (local) animation-leash created for this task. */
        SurfaceControl mLeash;

        TaskState(TransitionInfo.Change change, SurfaceControl leash) {
            mToken = change.getContainer();
            mTaskInfo = change.getTaskInfo();
            mTaskSurface = change.getLeash();
            mLeash = leash;
        }

        static int indexOf(ArrayList<TaskState> list, TransitionInfo.Change change) {
            for (int i = list.size() - 1; i >= 0; --i) {
                if (list.get(i).mToken.equals(change.getContainer())) {
                    return i;
                }
            }
            return -1;
        }

        public String toString() {
            return "" + mToken + " : " + mLeash;
        }
    }

    /**
     * An interface for a mixed handler to receive information about recents requests (since these
     * come into this handler directly vs from WMCore request).
     */
    public interface RecentsMixedHandler {
        /**
         * Called when a recents request comes in. The handler can add operations to outWCT. If
         * the handler wants to "accept" the transition, it should return itself; otherwise, it
         * should return `null`.
         *
         * If a mixed-handler accepts this recents, it will be the de-facto handler for this
         * transition and is required to call the associated {@link #startAnimation},
         * {@link #mergeAnimation}, and {@link #onTransitionConsumed} methods.
         */
        Transitions.TransitionHandler handleRecentsRequest(WindowContainerTransaction outWCT);

        /**
         * Reports the transition token associated with the accepted recents request. If there was
         * a problem starting the request, this will be called with `null`.
         */
        void setRecentsTransition(@Nullable IBinder transition);
    }
}
