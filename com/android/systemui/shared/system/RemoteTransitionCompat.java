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

package com.android.systemui.shared.system;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.window.TransitionFilter.CONTAINER_ORDER_TOP;

import static com.android.systemui.shared.system.RemoteAnimationTargetCompat.ACTIVITY_TYPE_RECENTS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;
import android.view.IRecentsAnimationController;
import android.view.SurfaceControl;
import android.window.IRemoteTransition;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.PictureInPictureSurfaceTransaction;
import android.window.RemoteTransition;
import android.window.TaskSnapshot;
import android.window.TransitionFilter;
import android.window.TransitionInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DataClass;
import com.android.systemui.shared.recents.model.ThumbnailData;

import java.util.ArrayList;
import java.util.concurrent.Executor;

/**
 * Wrapper to expose RemoteTransition (shell transitions) to Launcher.
 *
 * @see IRemoteTransition
 * @see TransitionFilter
 */
@DataClass
public class RemoteTransitionCompat implements Parcelable {
    private static final String TAG = "RemoteTransitionCompat";

    @NonNull final RemoteTransition mTransition;
    @Nullable TransitionFilter mFilter = null;

    RemoteTransitionCompat(RemoteTransition transition) {
        mTransition = transition;
    }

    public RemoteTransitionCompat(@NonNull RemoteTransitionRunner runner,
            @NonNull Executor executor, @Nullable IApplicationThread appThread) {
        IRemoteTransition remote = new IRemoteTransition.Stub() {
            @Override
            public void startAnimation(IBinder transition, TransitionInfo info,
                    SurfaceControl.Transaction t,
                    IRemoteTransitionFinishedCallback finishedCallback) {
                final Runnable finishAdapter = () ->  {
                    try {
                        finishedCallback.onTransitionFinished(null /* wct */, null /* sct */);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to call transition finished callback", e);
                    }
                };
                executor.execute(() -> runner.startAnimation(transition, info, t, finishAdapter));
            }

            @Override
            public void mergeAnimation(IBinder transition, TransitionInfo info,
                    SurfaceControl.Transaction t, IBinder mergeTarget,
                    IRemoteTransitionFinishedCallback finishedCallback) {
                final Runnable finishAdapter = () ->  {
                    try {
                        finishedCallback.onTransitionFinished(null /* wct */, null /* sct */);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to call transition finished callback", e);
                    }
                };
                executor.execute(() -> runner.mergeAnimation(transition, info, t, mergeTarget,
                        finishAdapter));
            }
        };
        mTransition = new RemoteTransition(remote, appThread);
    }

    /** Constructor specifically for recents animation */
    public RemoteTransitionCompat(RecentsAnimationListener recents,
            RecentsAnimationControllerCompat controller, IApplicationThread appThread) {
        IRemoteTransition remote = new IRemoteTransition.Stub() {
            final RecentsControllerWrap mRecentsSession = new RecentsControllerWrap();
            IBinder mToken = null;

            @Override
            public void startAnimation(IBinder transition, TransitionInfo info,
                    SurfaceControl.Transaction t,
                    IRemoteTransitionFinishedCallback finishedCallback) {
                final ArrayMap<SurfaceControl, SurfaceControl> leashMap = new ArrayMap<>();
                final RemoteAnimationTargetCompat[] apps =
                        RemoteAnimationTargetCompat.wrap(info, false /* wallpapers */, t, leashMap);
                final RemoteAnimationTargetCompat[] wallpapers =
                        RemoteAnimationTargetCompat.wrap(info, true /* wallpapers */, t, leashMap);
                // TODO(b/177438007): Move this set-up logic into launcher's animation impl.
                mToken = transition;
                // This transition is for opening recents, so recents is on-top. We want to draw
                // the current going-away task on top of recents, though, so move it to front
                final ArrayList<WindowContainerToken> pausingTasks = new ArrayList<>();
                WindowContainerToken pipTask = null;
                WindowContainerToken recentsTask = null;
                for (int i = info.getChanges().size() - 1; i >= 0; --i) {
                    final TransitionInfo.Change change = info.getChanges().get(i);
                    final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
                    if (change.getMode() == TRANSIT_CLOSE || change.getMode() == TRANSIT_TO_BACK) {
                        t.setLayer(leashMap.get(change.getLeash()),
                                info.getChanges().size() * 3 - i);
                        if (taskInfo == null) {
                            continue;
                        }
                        // Add to front since we are iterating backwards.
                        pausingTasks.add(0, taskInfo.token);
                        if (taskInfo.pictureInPictureParams != null
                                && taskInfo.pictureInPictureParams.isAutoEnterEnabled()) {
                            pipTask = taskInfo.token;
                        }
                    } else if (taskInfo != null
                            && taskInfo.topActivityType == ACTIVITY_TYPE_RECENTS) {
                        // This task is for recents, keep it on top.
                        t.setLayer(leashMap.get(change.getLeash()),
                                info.getChanges().size() * 3 - i);
                        recentsTask = taskInfo.token;
                    } else if (taskInfo != null && taskInfo.topActivityType == ACTIVITY_TYPE_HOME) {
                        recentsTask = taskInfo.token;
                    }
                }
                // Also make all the wallpapers opaque since we want the visible from the start
                for (int i = wallpapers.length - 1; i >= 0; --i) {
                    t.setAlpha(wallpapers[i].leash, 1);
                }
                t.apply();
                mRecentsSession.setup(controller, info, finishedCallback, pausingTasks, pipTask,
                        recentsTask, leashMap, mToken);
                recents.onAnimationStart(mRecentsSession, apps, wallpapers, new Rect(0, 0, 0, 0),
                        new Rect());
            }

            @Override
            public void mergeAnimation(IBinder transition, TransitionInfo info,
                    SurfaceControl.Transaction t, IBinder mergeTarget,
                    IRemoteTransitionFinishedCallback finishedCallback) {
                if (!mergeTarget.equals(mToken)) return;
                if (!mRecentsSession.merge(info, t, recents)) return;
                try {
                    finishedCallback.onTransitionFinished(null /* wct */, null /* sct */);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error merging transition.", e);
                }
            }
        };
        mTransition = new RemoteTransition(remote, appThread);
    }

    /** Adds a filter check that restricts this remote transition to home open transitions. */
    public void addHomeOpenCheck(ComponentName homeActivity) {
        if (mFilter == null) {
            mFilter = new TransitionFilter();
        }
        // No need to handle the transition that also dismisses keyguard.
        mFilter.mNotFlags = TRANSIT_FLAG_KEYGUARD_GOING_AWAY;
        mFilter.mRequirements =
                new TransitionFilter.Requirement[]{new TransitionFilter.Requirement(),
                        new TransitionFilter.Requirement()};
        mFilter.mRequirements[0].mActivityType = ACTIVITY_TYPE_HOME;
        mFilter.mRequirements[0].mTopActivity = homeActivity;
        mFilter.mRequirements[0].mModes = new int[]{TRANSIT_OPEN, TRANSIT_TO_FRONT};
        mFilter.mRequirements[0].mOrder = CONTAINER_ORDER_TOP;
        mFilter.mRequirements[1].mActivityType = ACTIVITY_TYPE_STANDARD;
        mFilter.mRequirements[1].mModes = new int[]{TRANSIT_CLOSE, TRANSIT_TO_BACK};
    }

    /**
     * Wrapper to hook up parts of recents animation to shell transition.
     * TODO(b/177438007): Remove this once Launcher handles shell transitions directly.
     */
    @VisibleForTesting
    static class RecentsControllerWrap extends RecentsAnimationControllerCompat {
        private RecentsAnimationControllerCompat mWrapped = null;
        private IRemoteTransitionFinishedCallback mFinishCB = null;
        private ArrayList<WindowContainerToken> mPausingTasks = null;
        private WindowContainerToken mPipTask = null;
        private WindowContainerToken mRecentsTask = null;
        private TransitionInfo mInfo = null;
        private ArrayList<SurfaceControl> mOpeningLeashes = null;
        private ArrayMap<SurfaceControl, SurfaceControl> mLeashMap = null;
        private PictureInPictureSurfaceTransaction mPipTransaction = null;
        private IBinder mTransition = null;

        void setup(RecentsAnimationControllerCompat wrapped, TransitionInfo info,
                IRemoteTransitionFinishedCallback finishCB,
                ArrayList<WindowContainerToken> pausingTasks, WindowContainerToken pipTask,
                WindowContainerToken recentsTask, ArrayMap<SurfaceControl, SurfaceControl> leashMap,
                IBinder transition) {
            if (mInfo != null) {
                throw new IllegalStateException("Trying to run a new recents animation while"
                        + " recents is already active.");
            }
            mWrapped = wrapped;
            mInfo = info;
            mFinishCB = finishCB;
            mPausingTasks = pausingTasks;
            mPipTask = pipTask;
            mRecentsTask = recentsTask;
            mLeashMap = leashMap;
            mTransition = transition;
        }

        @SuppressLint("NewApi")
        boolean merge(TransitionInfo info, SurfaceControl.Transaction t,
                RecentsAnimationListener recents) {
            SparseArray<TransitionInfo.Change> openingTasks = null;
            boolean cancelRecents = false;
            boolean homeGoingAway = false;
            boolean hasChangingApp = false;
            for (int i = info.getChanges().size() - 1; i >= 0; --i) {
                final TransitionInfo.Change change = info.getChanges().get(i);
                if (change.getMode() == TRANSIT_OPEN || change.getMode() == TRANSIT_TO_FRONT) {
                    final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
                    if (taskInfo != null) {
                        if (taskInfo.topActivityType == ACTIVITY_TYPE_HOME) {
                            // canceling recents animation
                            cancelRecents = true;
                        }
                        if (openingTasks == null) {
                            openingTasks = new SparseArray<>();
                        }
                        if (taskInfo.hasParentTask()) {
                            // Collects opening leaf tasks only since Launcher monitors leaf task
                            // ids to perform recents animation.
                            openingTasks.remove(taskInfo.parentTaskId);
                        }
                        openingTasks.put(taskInfo.taskId, change);
                    }
                } else if (change.getMode() == TRANSIT_CLOSE
                        || change.getMode() == TRANSIT_TO_BACK) {
                    if (mRecentsTask.equals(change.getContainer())) {
                        homeGoingAway = true;
                    }
                } else if (change.getMode() == TRANSIT_CHANGE) {
                    hasChangingApp = true;
                }
            }
            if (hasChangingApp && homeGoingAway) {
                // This happens when a visible app is expanding (usually PiP). In this case,
                // The transition probably has a special-purpose animation, so finish recents
                // now and let it do its animation (since recents is going to be occluded).
                if (!recents.onSwitchToScreenshot(() -> {
                    finish(true /* toHome */, false /* userLeaveHint */);
                })) {
                    Log.w(TAG, "Recents callback doesn't support support switching to screenshot"
                            + ", there might be a flicker.");
                    finish(true /* toHome */, false /* userLeaveHint */);
                }
                return false;
            }
            if (openingTasks == null) return false;
            int pauseMatches = 0;
            if (!cancelRecents) {
                for (int i = 0; i < openingTasks.size(); ++i) {
                    if (mPausingTasks.contains(openingTasks.valueAt(i).getContainer())) {
                        ++pauseMatches;
                    }
                }
            }
            if (pauseMatches > 0) {
                if (pauseMatches != mPausingTasks.size()) {
                    // We are not really "returning" properly... something went wrong.
                    throw new IllegalStateException("\"Concelling\" a recents transitions by "
                            + "unpausing " + pauseMatches + " apps after pausing "
                            + mPausingTasks.size() + " apps.");
                }
                // In this case, we are "returning" to an already running app, so just consume
                // the merge and do nothing.
                return true;
            }
            final int layer = mInfo.getChanges().size() * 3;
            mOpeningLeashes = new ArrayList<>();
            final RemoteAnimationTargetCompat[] targets =
                    new RemoteAnimationTargetCompat[openingTasks.size()];
            for (int i = 0; i < openingTasks.size(); ++i) {
                final TransitionInfo.Change change = openingTasks.valueAt(i);
                mOpeningLeashes.add(change.getLeash());
                // We are receiving new opening tasks, so convert to onTasksAppeared.
                final RemoteAnimationTargetCompat target = new RemoteAnimationTargetCompat(
                        change, layer, info, t);
                mLeashMap.put(mOpeningLeashes.get(i), target.leash);
                t.reparent(target.leash, mInfo.getRootLeash());
                t.setLayer(target.leash, layer);
                targets[i] = target;
            }
            t.apply();
            recents.onTasksAppeared(targets);
            return true;
        }

        @Override public ThumbnailData screenshotTask(int taskId) {
            try {
                final TaskSnapshot snapshot =
                        ActivityTaskManager.getService().takeTaskSnapshot(taskId);
                if (snapshot != null) {
                    return new ThumbnailData(snapshot);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to screenshot task", e);
            }
            return null;
        }

        @Override public void setInputConsumerEnabled(boolean enabled) {
            if (mWrapped != null) mWrapped.setInputConsumerEnabled(enabled);
        }

        @Override public void setAnimationTargetsBehindSystemBars(boolean behindSystemBars) {
            if (mWrapped != null) mWrapped.setAnimationTargetsBehindSystemBars(behindSystemBars);
        }

        @Override public void hideCurrentInputMethod() {
            mWrapped.hideCurrentInputMethod();
        }

        @Override public void setFinishTaskTransaction(int taskId,
                PictureInPictureSurfaceTransaction finishTransaction, SurfaceControl overlay) {
            mPipTransaction = finishTransaction;
            if (mWrapped != null) {
                mWrapped.setFinishTaskTransaction(taskId, finishTransaction, overlay);
            }
        }

        @Override
        @SuppressLint("NewApi")
        public void finish(boolean toHome, boolean sendUserLeaveHint) {
            if (mFinishCB == null) {
                Log.e(TAG, "Duplicate call to finish", new RuntimeException());
                return;
            }
            if (mWrapped != null) mWrapped.finish(toHome, sendUserLeaveHint);
            final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            final WindowContainerTransaction wct = new WindowContainerTransaction();

            if (!toHome && mPausingTasks != null && mOpeningLeashes == null) {
                // The gesture went back to opening the app rather than continuing with
                // recents, so end the transition by moving the app back to the top (and also
                // re-showing it's task).
                for (int i = mPausingTasks.size() - 1; i >= 0; --i) {
                    // reverse order so that index 0 ends up on top
                    wct.reorder(mPausingTasks.get(i), true /* onTop */);
                    t.show(mInfo.getChange(mPausingTasks.get(i)).getLeash());
                }
                if (mRecentsTask != null) {
                    wct.restoreTransientOrder(mRecentsTask);
                }
            } else {
                if (!sendUserLeaveHint) {
                    for (int i = 0; i < mPausingTasks.size(); ++i) {
                        // This means recents is not *actually* finishing, so of course we gotta
                        // do special stuff in WMCore to accommodate.
                        wct.setDoNotPip(mPausingTasks.get(i));
                    }
                }
                if (mPipTask != null && mPipTransaction != null && sendUserLeaveHint) {
                    t.show(mInfo.getChange(mPipTask).getLeash());
                    PictureInPictureSurfaceTransaction.apply(mPipTransaction,
                            mInfo.getChange(mPipTask).getLeash(), t);
                    mPipTask = null;
                    mPipTransaction = null;
                }
            }
            // Release surface references now. This is apparently to free GPU
            // memory while doing quick operations (eg. during CTS).
            for (int i = 0; i < mLeashMap.size(); ++i) {
                if (mLeashMap.keyAt(i) == mLeashMap.valueAt(i)) continue;
                t.remove(mLeashMap.valueAt(i));
            }
            try {
                mFinishCB.onTransitionFinished(wct.isEmpty() ? null : wct, t);
            } catch (RemoteException e) {
                Log.e("RemoteTransitionCompat", "Failed to call animation finish callback", e);
                t.apply();
            }
            for (int i = 0; i < mInfo.getChanges().size(); ++i) {
                mInfo.getChanges().get(i).getLeash().release();
            }
            // Reset all members.
            mWrapped = null;
            mFinishCB = null;
            mPausingTasks = null;
            mInfo = null;
            mOpeningLeashes = null;
            mLeashMap = null;
            mTransition = null;
        }

        @Override public void setDeferCancelUntilNextTransition(boolean defer, boolean screenshot) {
            if (mWrapped != null) mWrapped.setDeferCancelUntilNextTransition(defer, screenshot);
        }

        @Override public void cleanupScreenshot() {
            if (mWrapped != null) mWrapped.cleanupScreenshot();
        }

        @Override public void setWillFinishToHome(boolean willFinishToHome) {
            if (mWrapped != null) mWrapped.setWillFinishToHome(willFinishToHome);
        }

        /**
         * @see IRecentsAnimationController#removeTask
         */
        @Override public boolean removeTask(int taskId) {
            return mWrapped != null ? mWrapped.removeTask(taskId) : false;
        }

        /**
         * @see IRecentsAnimationController#detachNavigationBarFromApp
         */
        @Override public void detachNavigationBarFromApp(boolean moveHomeToTop) {
            try {
                ActivityTaskManager.getService().detachNavigationBarFromApp(mTransition);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to detach the navigation bar from app", e);
            }
        }

        /**
         * @see IRecentsAnimationController#animateNavigationBarToApp(long)
         */
        @Override public void animateNavigationBarToApp(long duration) {
        }
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/packages/SystemUI/shared/src/com/android/systemui/shared/system/RemoteTransitionCompat.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    /* package-private */ RemoteTransitionCompat(
            @NonNull RemoteTransition transition,
            @Nullable TransitionFilter filter) {
        this.mTransition = transition;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mTransition);
        this.mFilter = filter;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public @NonNull RemoteTransition getTransition() {
        return mTransition;
    }

    @DataClass.Generated.Member
    public @Nullable TransitionFilter getFilter() {
        return mFilter;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        byte flg = 0;
        if (mFilter != null) flg |= 0x2;
        dest.writeByte(flg);
        dest.writeTypedObject(mTransition, flags);
        if (mFilter != null) dest.writeTypedObject(mFilter, flags);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    protected RemoteTransitionCompat(@NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        RemoteTransition transition = (RemoteTransition) in.readTypedObject(RemoteTransition.CREATOR);
        TransitionFilter filter = (flg & 0x2) == 0 ? null : (TransitionFilter) in.readTypedObject(TransitionFilter.CREATOR);

        this.mTransition = transition;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mTransition);
        this.mFilter = filter;

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<RemoteTransitionCompat> CREATOR
            = new Parcelable.Creator<RemoteTransitionCompat>() {
        @Override
        public RemoteTransitionCompat[] newArray(int size) {
            return new RemoteTransitionCompat[size];
        }

        @Override
        public RemoteTransitionCompat createFromParcel(@NonNull android.os.Parcel in) {
            return new RemoteTransitionCompat(in);
        }
    };

    /**
     * A builder for {@link RemoteTransitionCompat}
     */
    @SuppressWarnings("WeakerAccess")
    @DataClass.Generated.Member
    public static class Builder {

        private @NonNull RemoteTransition mTransition;
        private @Nullable TransitionFilter mFilter;

        private long mBuilderFieldsSet = 0L;

        public Builder(
                @NonNull RemoteTransition transition) {
            mTransition = transition;
            com.android.internal.util.AnnotationValidations.validate(
                    NonNull.class, null, mTransition);
        }

        @DataClass.Generated.Member
        public @NonNull Builder setTransition(@NonNull RemoteTransition value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mTransition = value;
            return this;
        }

        @DataClass.Generated.Member
        public @NonNull Builder setFilter(@NonNull TransitionFilter value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mFilter = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull RemoteTransitionCompat build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4; // Mark builder used

            if ((mBuilderFieldsSet & 0x2) == 0) {
                mFilter = null;
            }
            RemoteTransitionCompat o = new RemoteTransitionCompat(
                    mTransition,
                    mFilter);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x4) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }

    @DataClass.Generated(
            time = 1629321609807L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/packages/SystemUI/shared/src/com/android/systemui/shared/system/RemoteTransitionCompat.java",
            inputSignatures = "private static final  java.lang.String TAG\nfinal @android.annotation.NonNull android.window.RemoteTransition mTransition\n @android.annotation.Nullable android.window.TransitionFilter mFilter\npublic  void addHomeOpenCheck(android.content.ComponentName)\nclass RemoteTransitionCompat extends java.lang.Object implements [android.os.Parcelable]\nprivate  com.android.systemui.shared.system.RecentsAnimationControllerCompat mWrapped\nprivate  android.window.IRemoteTransitionFinishedCallback mFinishCB\nprivate  android.window.WindowContainerToken mPausingTask\nprivate  android.window.WindowContainerToken mPipTask\nprivate  android.window.TransitionInfo mInfo\nprivate  android.view.SurfaceControl mOpeningLeash\nprivate  android.util.ArrayMap<android.view.SurfaceControl,android.view.SurfaceControl> mLeashMap\nprivate  android.window.PictureInPictureSurfaceTransaction mPipTransaction\nprivate  android.os.IBinder mTransition\n  void setup(com.android.systemui.shared.system.RecentsAnimationControllerCompat,android.window.TransitionInfo,android.window.IRemoteTransitionFinishedCallback,android.window.WindowContainerToken,android.window.WindowContainerToken,android.util.ArrayMap<android.view.SurfaceControl,android.view.SurfaceControl>,android.os.IBinder)\n @android.annotation.SuppressLint boolean merge(android.window.TransitionInfo,android.view.SurfaceControl.Transaction,com.android.systemui.shared.system.RecentsAnimationListener)\npublic @java.lang.Override com.android.systemui.shared.recents.model.ThumbnailData screenshotTask(int)\npublic @java.lang.Override void setInputConsumerEnabled(boolean)\npublic @java.lang.Override void setAnimationTargetsBehindSystemBars(boolean)\npublic @java.lang.Override void hideCurrentInputMethod()\npublic @java.lang.Override void setFinishTaskTransaction(int,android.window.PictureInPictureSurfaceTransaction,android.view.SurfaceControl)\npublic @java.lang.Override @android.annotation.SuppressLint void finish(boolean,boolean)\npublic @java.lang.Override void setDeferCancelUntilNextTransition(boolean,boolean)\npublic @java.lang.Override void cleanupScreenshot()\npublic @java.lang.Override void setWillFinishToHome(boolean)\npublic @java.lang.Override boolean removeTask(int)\npublic @java.lang.Override void detachNavigationBarFromApp(boolean)\npublic @java.lang.Override void animateNavigationBarToApp(long)\nclass RecentsControllerWrap extends com.android.systemui.shared.system.RecentsAnimationControllerCompat implements []\n@com.android.internal.util.DataClass")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
