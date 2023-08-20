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

package com.android.wm.shell.pip.phone;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE;
import static android.view.WindowManager.INPUT_CONSUMER_PIP;

import static com.android.internal.jank.InteractionJankMonitor.CUJ_PIP_TRANSITION;
import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;
import static com.android.wm.shell.pip.PipAnimationController.ANIM_TYPE_ALPHA;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_EXPAND_OR_UNEXPAND;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_LEAVE_PIP;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_LEAVE_PIP_TO_SPLIT_SCREEN;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_REMOVE_STACK;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_SAME;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_SNAP_AFTER_RESIZE;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_TO_PIP;
import static com.android.wm.shell.pip.PipAnimationController.TRANSITION_DIRECTION_USER_RESIZE;
import static com.android.wm.shell.pip.PipAnimationController.isOutPipDirection;
import static com.android.wm.shell.sysui.ShellSharedConstants.KEY_EXTRA_SHELL_PIP;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Pair;
import android.util.Size;
import android.view.DisplayInfo;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.WindowManagerGlobal;
import android.window.WindowContainerTransaction;

import androidx.annotation.BinderThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.R;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.common.DisplayChangeController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ExternalInterfaceBinder;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SingleInstanceRemoteListener;
import com.android.wm.shell.common.TabletopModeController;
import com.android.wm.shell.common.TaskStackListenerCallback;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.onehanded.OneHandedController;
import com.android.wm.shell.onehanded.OneHandedTransitionCallback;
import com.android.wm.shell.pip.IPip;
import com.android.wm.shell.pip.IPipAnimationListener;
import com.android.wm.shell.pip.PinnedStackListenerForwarder;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.pip.PipAnimationController;
import com.android.wm.shell.pip.PipAppOpsListener;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipDisplayLayoutState;
import com.android.wm.shell.pip.PipKeepClearAlgorithmInterface;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipParamsChangedForwarder;
import com.android.wm.shell.pip.PipSnapAlgorithm;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipTransitionController;
import com.android.wm.shell.pip.PipTransitionState;
import com.android.wm.shell.pip.PipUtils;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.sysui.ConfigurationChangeListener;
import com.android.wm.shell.sysui.KeyguardChangeListener;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.sysui.UserChangeListener;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Manages the picture-in-picture (PIP) UI and states for Phones.
 */
public class PipController implements PipTransitionController.PipTransitionCallback,
        RemoteCallable<PipController>, ConfigurationChangeListener, KeyguardChangeListener,
        UserChangeListener {
    private static final String TAG = "PipController";

    private static final String LAUNCHER_KEEP_CLEAR_AREA_TAG = "hotseat";

    private static final long PIP_KEEP_CLEAR_AREAS_DELAY =
            SystemProperties.getLong("persist.wm.debug.pip_keep_clear_areas_delay", 200);

    private boolean mEnablePipKeepClearAlgorithm =
            SystemProperties.getBoolean("persist.wm.debug.enable_pip_keep_clear_algorithm", true);

    @VisibleForTesting
    void setEnablePipKeepClearAlgorithm(boolean value) {
        mEnablePipKeepClearAlgorithm = value;
    }

    private Context mContext;
    protected ShellExecutor mMainExecutor;
    private DisplayController mDisplayController;
    private PipInputConsumer mPipInputConsumer;
    private WindowManagerShellWrapper mWindowManagerShellWrapper;
    private PipAnimationController mPipAnimationController;
    private PipAppOpsListener mAppOpsListener;
    private PipMediaController mMediaController;
    private PipBoundsAlgorithm mPipBoundsAlgorithm;
    private PipKeepClearAlgorithmInterface mPipKeepClearAlgorithm;
    private PipBoundsState mPipBoundsState;
    private PipSizeSpecHandler mPipSizeSpecHandler;
    private PipDisplayLayoutState mPipDisplayLayoutState;
    private PipMotionHelper mPipMotionHelper;
    private PipTouchHandler mTouchHandler;
    private PipTransitionController mPipTransitionController;
    private TaskStackListenerImpl mTaskStackListener;
    private PipParamsChangedForwarder mPipParamsChangedForwarder;
    private DisplayInsetsController mDisplayInsetsController;
    private TabletopModeController mTabletopModeController;
    private Optional<OneHandedController> mOneHandedController;
    private final ShellCommandHandler mShellCommandHandler;
    private final ShellController mShellController;
    protected final PipImpl mImpl;

    private final Rect mTmpInsetBounds = new Rect();
    private final int mEnterAnimationDuration;

    private final Runnable mMovePipInResponseToKeepClearAreasChangeCallback =
            this::onKeepClearAreasChangedCallback;

    private void onKeepClearAreasChangedCallback() {
        if (mIsKeyguardShowingOrAnimating) {
            // early bail out if the change was caused by keyguard showing up
            return;
        }
        if (!mEnablePipKeepClearAlgorithm) {
            // early bail out if the keep clear areas feature is disabled
            return;
        }
        if (mPipBoundsState.isStashed()) {
            // don't move when stashed
            return;
        }
        // if there is another animation ongoing, wait for it to finish and try again
        if (mPipAnimationController.isAnimating()) {
            mMainExecutor.removeCallbacks(
                    mMovePipInResponseToKeepClearAreasChangeCallback);
            mMainExecutor.executeDelayed(
                    mMovePipInResponseToKeepClearAreasChangeCallback,
                    PIP_KEEP_CLEAR_AREAS_DELAY);
            return;
        }
        updatePipPositionForKeepClearAreas();
    }

    private void updatePipPositionForKeepClearAreas() {
        if (!mEnablePipKeepClearAlgorithm) {
            // early bail out if the keep clear areas feature is disabled
            return;
        }
        if (mIsKeyguardShowingOrAnimating) {
            // early bail out if the change was caused by keyguard showing up
            return;
        }
        // only move if we're in PiP or transitioning into PiP
        if (!mPipTransitionState.shouldBlockResizeRequest()) {
            Rect destBounds = mPipKeepClearAlgorithm.adjust(mPipBoundsState,
                    mPipBoundsAlgorithm);
            // only move if the bounds are actually different
            if (!destBounds.equals(mPipBoundsState.getBounds())) {
                if (mPipTransitionState.hasEnteredPip()) {
                    // if already in PiP, schedule separate animation
                    mPipTaskOrganizer.scheduleAnimateResizePip(destBounds,
                            mEnterAnimationDuration, null);
                } else if (mPipTransitionState.isEnteringPip()) {
                    // while entering PiP we just need to update animator bounds
                    mPipTaskOrganizer.updateAnimatorBounds(destBounds);
                }
            }
        }
    }

    private boolean mIsInFixedRotation;
    private PipAnimationListener mPinnedStackAnimationRecentsCallback;

    protected PhonePipMenuController mMenuController;
    protected PipTaskOrganizer mPipTaskOrganizer;
    private PipTransitionState mPipTransitionState;
    protected PinnedStackListenerForwarder.PinnedTaskListener mPinnedTaskListener =
            new PipControllerPinnedTaskListener();

    private boolean mIsKeyguardShowingOrAnimating;

    private Consumer<Boolean> mOnIsInPipStateChangedListener;

    @VisibleForTesting
    interface PipAnimationListener {
        /**
         * Notifies the listener that the Pip animation is started.
         */
        void onPipAnimationStarted();

        /**
         * Notifies the listener about PiP resource dimensions changed.
         * Listener can expect an immediate callback the first time they attach.
         *
         * @param cornerRadius the pixel value of the corner radius, zero means it's disabled.
         * @param shadowRadius the pixel value of the shadow radius, zero means it's disabled.
         */
        void onPipResourceDimensionsChanged(int cornerRadius, int shadowRadius);

        /**
         * Notifies the listener that user leaves PiP by tapping on the expand button.
         */
        void onExpandPip();
    }

    /**
     * Handler for display rotation changes.
     */
    private final DisplayChangeController.OnDisplayChangingListener mRotationController = (
            displayId, fromRotation, toRotation, newDisplayAreaInfo, t) -> {
        if (mPipTransitionController.handleRotateDisplay(fromRotation, toRotation, t)) {
            return;
        }
        if (mPipBoundsState.getDisplayLayout().rotation() == toRotation) {
            // The same rotation may have been set by auto PiP-able or fixed rotation. So notify
            // the change with fromRotation=false to apply the rotated destination bounds from
            // PipTaskOrganizer#onMovementBoundsChanged.
            updateMovementBounds(null, false /* fromRotation */,
                    false /* fromImeAdjustment */, false /* fromShelfAdjustment */, t);
            return;
        }
        if (!mPipTaskOrganizer.isInPip() || mPipTaskOrganizer.isEntryScheduled()) {
            // Update display layout and bounds handler if we aren't in PIP or haven't actually
            // entered PIP yet.
            onDisplayRotationChangedNotInPip(mContext, toRotation);
            // do not forget to update the movement bounds as well.
            updateMovementBounds(mPipBoundsState.getNormalBounds(), true /* fromRotation */,
                    false /* fromImeAdjustment */, false /* fromShelfAdjustment */, t);
            mPipTaskOrganizer.onDisplayRotationSkipped();
            return;
        }
        // If there is an animation running (ie. from a shelf offset), then ensure that we calculate
        // the bounds for the next orientation using the destination bounds of the animation
        // TODO: Technically this should account for movement animation bounds as well
        Rect currentBounds = mPipTaskOrganizer.getCurrentOrAnimatingBounds();
        final Rect outBounds = new Rect();
        final boolean changed = onDisplayRotationChanged(mContext, outBounds, currentBounds,
                mTmpInsetBounds, displayId, fromRotation, toRotation, t);
        if (changed) {
            // If the pip was in the offset zone earlier, adjust the new bounds to the bottom of the
            // movement bounds
            mTouchHandler.adjustBoundsForRotation(outBounds, mPipBoundsState.getBounds(),
                    mTmpInsetBounds);

            // The bounds are being applied to a specific snap fraction, so reset any known offsets
            // for the previous orientation before updating the movement bounds.
            // We perform the resets if and only if this callback is due to screen rotation but
            // not during the fixed rotation. In fixed rotation case, app is about to enter PiP
            // and we need the offsets preserved to calculate the destination bounds.
            if (!mIsInFixedRotation) {
                // Update the shelf visibility without updating the movement bounds. We're already
                // updating them below with the |fromRotation| flag set, which is more accurate
                // than using the |fromShelfAdjustment|.
                mPipBoundsState.setShelfVisibility(false /* showing */, 0 /* height */,
                        false /* updateMovementBounds */);
                mPipBoundsState.setImeVisibility(false /* showing */, 0 /* height */);
                mTouchHandler.onShelfVisibilityChanged(false, 0);
                mTouchHandler.onImeVisibilityChanged(false, 0);
            }

            updateMovementBounds(outBounds, true /* fromRotation */, false /* fromImeAdjustment */,
                    false /* fromShelfAdjustment */, t);
        }
    };

    @VisibleForTesting
    final DisplayController.OnDisplaysChangedListener mDisplaysChangedListener =
            new DisplayController.OnDisplaysChangedListener() {
                @Override
                public void onFixedRotationStarted(int displayId, int newRotation) {
                    mIsInFixedRotation = true;
                }

                @Override
                public void onFixedRotationFinished(int displayId) {
                    mIsInFixedRotation = false;
                }

                @Override
                public void onDisplayAdded(int displayId) {
                    if (displayId != mPipDisplayLayoutState.getDisplayId()) {
                        return;
                    }
                    onDisplayChanged(mDisplayController.getDisplayLayout(displayId),
                            false /* saveRestoreSnapFraction */);
                }

                @Override
                public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
                    if (displayId != mPipDisplayLayoutState.getDisplayId()) {
                        return;
                    }
                    onDisplayChanged(mDisplayController.getDisplayLayout(displayId),
                            true /* saveRestoreSnapFraction */);
                }

                @Override
                public void onKeepClearAreasChanged(int displayId, Set<Rect> restricted,
                        Set<Rect> unrestricted) {
                    if (mPipDisplayLayoutState.getDisplayId() == displayId) {
                        if (mEnablePipKeepClearAlgorithm) {
                            mPipBoundsState.setKeepClearAreas(restricted, unrestricted);

                            mMainExecutor.removeCallbacks(
                                    mMovePipInResponseToKeepClearAreasChangeCallback);
                            mMainExecutor.executeDelayed(
                                    mMovePipInResponseToKeepClearAreasChangeCallback,
                                    PIP_KEEP_CLEAR_AREAS_DELAY);
                        }
                    }
                }
            };

    /**
     * Handler for messages from the PIP controller.
     */
    private class PipControllerPinnedTaskListener extends
            PinnedStackListenerForwarder.PinnedTaskListener {
        @Override
        public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
            mPipBoundsState.setImeVisibility(imeVisible, imeHeight);
            mTouchHandler.onImeVisibilityChanged(imeVisible, imeHeight);
            if (imeVisible) {
                updatePipPositionForKeepClearAreas();
            }
        }

        @Override
        public void onMovementBoundsChanged(boolean fromImeAdjustment) {
            updateMovementBounds(null /* toBounds */,
                    false /* fromRotation */, fromImeAdjustment, false /* fromShelfAdjustment */,
                    null /* windowContainerTransaction */);
        }

        @Override
        public void onActivityHidden(ComponentName componentName) {
            if (componentName.equals(mPipBoundsState.getLastPipComponentName())) {
                // The activity was removed, we don't want to restore to the reentry state
                // saved for this component anymore.
                mPipBoundsState.setLastPipComponentName(null);
            }
        }
    }

    /**
     * Instantiates {@link PipController}, returns {@code null} if the feature not supported.
     */
    @Nullable
    public static Pip create(Context context,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            ShellController shellController,
            DisplayController displayController,
            PipAnimationController pipAnimationController,
            PipAppOpsListener pipAppOpsListener,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PipKeepClearAlgorithmInterface pipKeepClearAlgorithm,
            PipBoundsState pipBoundsState,
            PipSizeSpecHandler pipSizeSpecHandler,
            PipDisplayLayoutState pipDisplayLayoutState,
            PipMotionHelper pipMotionHelper,
            PipMediaController pipMediaController,
            PhonePipMenuController phonePipMenuController,
            PipTaskOrganizer pipTaskOrganizer,
            PipTransitionState pipTransitionState,
            PipTouchHandler pipTouchHandler,
            PipTransitionController pipTransitionController,
            WindowManagerShellWrapper windowManagerShellWrapper,
            TaskStackListenerImpl taskStackListener,
            PipParamsChangedForwarder pipParamsChangedForwarder,
            DisplayInsetsController displayInsetsController,
            TabletopModeController pipTabletopController,
            Optional<OneHandedController> oneHandedController,
            ShellExecutor mainExecutor) {
        if (!context.getPackageManager().hasSystemFeature(FEATURE_PICTURE_IN_PICTURE)) {
            ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Device doesn't support Pip feature", TAG);
            return null;
        }

        return new PipController(context, shellInit, shellCommandHandler, shellController,
                displayController, pipAnimationController, pipAppOpsListener,
                pipBoundsAlgorithm, pipKeepClearAlgorithm, pipBoundsState, pipSizeSpecHandler,
                pipDisplayLayoutState, pipMotionHelper, pipMediaController, phonePipMenuController,
                pipTaskOrganizer, pipTransitionState, pipTouchHandler, pipTransitionController,
                windowManagerShellWrapper, taskStackListener, pipParamsChangedForwarder,
                displayInsetsController, pipTabletopController, oneHandedController, mainExecutor)
                .mImpl;
    }

    protected PipController(Context context,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            ShellController shellController,
            DisplayController displayController,
            PipAnimationController pipAnimationController,
            PipAppOpsListener pipAppOpsListener,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PipKeepClearAlgorithmInterface pipKeepClearAlgorithm,
            @NonNull PipBoundsState pipBoundsState,
            PipSizeSpecHandler pipSizeSpecHandler,
            @NonNull PipDisplayLayoutState pipDisplayLayoutState,
            PipMotionHelper pipMotionHelper,
            PipMediaController pipMediaController,
            PhonePipMenuController phonePipMenuController,
            PipTaskOrganizer pipTaskOrganizer,
            PipTransitionState pipTransitionState,
            PipTouchHandler pipTouchHandler,
            PipTransitionController pipTransitionController,
            WindowManagerShellWrapper windowManagerShellWrapper,
            TaskStackListenerImpl taskStackListener,
            PipParamsChangedForwarder pipParamsChangedForwarder,
            DisplayInsetsController displayInsetsController,
            TabletopModeController tabletopModeController,
            Optional<OneHandedController> oneHandedController,
            ShellExecutor mainExecutor
    ) {


        mContext = context;
        mShellCommandHandler = shellCommandHandler;
        mShellController = shellController;
        mImpl = new PipImpl();
        mWindowManagerShellWrapper = windowManagerShellWrapper;
        mDisplayController = displayController;
        mPipBoundsAlgorithm = pipBoundsAlgorithm;
        mPipKeepClearAlgorithm = pipKeepClearAlgorithm;
        mPipBoundsState = pipBoundsState;
        mPipSizeSpecHandler = pipSizeSpecHandler;
        mPipDisplayLayoutState = pipDisplayLayoutState;
        mPipMotionHelper = pipMotionHelper;
        mPipTaskOrganizer = pipTaskOrganizer;
        mPipTransitionState = pipTransitionState;
        mMainExecutor = mainExecutor;
        mMediaController = pipMediaController;
        mMenuController = phonePipMenuController;
        mTouchHandler = pipTouchHandler;
        mPipAnimationController = pipAnimationController;
        mAppOpsListener = pipAppOpsListener;
        mOneHandedController = oneHandedController;
        mPipTransitionController = pipTransitionController;
        mTaskStackListener = taskStackListener;

        mEnterAnimationDuration = mContext.getResources()
                .getInteger(R.integer.config_pipEnterAnimationDuration);
        mPipParamsChangedForwarder = pipParamsChangedForwarder;
        mDisplayInsetsController = displayInsetsController;
        mTabletopModeController = tabletopModeController;

        shellInit.addInitCallback(this::onInit, this);
    }

    private void onInit() {
        mShellCommandHandler.addDumpCallback(this::dump, this);
        mPipInputConsumer = new PipInputConsumer(WindowManagerGlobal.getWindowManagerService(),
                INPUT_CONSUMER_PIP, mMainExecutor);
        mPipTransitionController.registerPipTransitionCallback(this);
        mPipTaskOrganizer.registerOnDisplayIdChangeCallback((int displayId) -> {
            mPipDisplayLayoutState.setDisplayId(displayId);
            onDisplayChanged(mDisplayController.getDisplayLayout(displayId),
                    false /* saveRestoreSnapFraction */);
        });
        mPipTransitionState.addOnPipTransitionStateChangedListener((oldState, newState) -> {
            if (mOnIsInPipStateChangedListener != null) {
                final boolean wasInPip = PipTransitionState.isInPip(oldState);
                final boolean nowInPip = PipTransitionState.isInPip(newState);
                if (nowInPip != wasInPip) {
                    mOnIsInPipStateChangedListener.accept(nowInPip);
                }
            }
        });
        mPipBoundsState.setOnMinimalSizeChangeCallback(
                () -> {
                    // The minimal size drives the normal bounds, so they need to be recalculated.
                    updateMovementBounds(null /* toBounds */, false /* fromRotation */,
                            false /* fromImeAdjustment */, false /* fromShelfAdjustment */,
                            null /* wct */);
                });
        mPipBoundsState.setOnShelfVisibilityChangeCallback(
                (isShowing, height, updateMovementBounds) -> {
                    mTouchHandler.onShelfVisibilityChanged(isShowing, height);
                    if (updateMovementBounds) {
                        updateMovementBounds(mPipBoundsState.getBounds(),
                                false /* fromRotation */, false /* fromImeAdjustment */,
                                true /* fromShelfAdjustment */,
                                null /* windowContainerTransaction */);
                    }
                });
        if (mTouchHandler != null) {
            // Register the listener for input consumer touch events. Only for Phone
            mPipInputConsumer.setInputListener(mTouchHandler::handleTouchEvent);
            mPipInputConsumer.setRegistrationListener(mTouchHandler::onRegistrationChanged);
        }
        mDisplayController.addDisplayChangingController(mRotationController);
        mDisplayController.addDisplayWindowListener(mDisplaysChangedListener);

        // Ensure that we have the display info in case we get calls to update the bounds before the
        // listener calls back
        mPipDisplayLayoutState.setDisplayId(mContext.getDisplayId());

        DisplayLayout layout = new DisplayLayout(mContext, mContext.getDisplay());
        mPipDisplayLayoutState.setDisplayLayout(layout);

        try {
            mWindowManagerShellWrapper.addPinnedStackListener(mPinnedTaskListener);
        } catch (RemoteException e) {
            ProtoLog.e(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Failed to register pinned stack listener, %s", TAG, e);
        }

        try {
            ActivityTaskManager.RootTaskInfo taskInfo = ActivityTaskManager.getService()
                    .getRootTaskInfo(WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
            if (taskInfo != null) {
                // If SystemUI restart, and it already existed a pinned stack,
                // register the pip input consumer to ensure touch can send to it.
                mPipInputConsumer.registerInputConsumer();
            }
        } catch (RemoteException | UnsupportedOperationException e) {
            ProtoLog.e(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Failed to register pinned stack listener, %s", TAG, e);
            e.printStackTrace();
        }

        // Handle for system task stack changes.
        mTaskStackListener.addListener(
                new TaskStackListenerCallback() {
                    @Override
                    public void onActivityPinned(String packageName, int userId, int taskId,
                            int stackId) {
                        mTouchHandler.onActivityPinned();
                        mMediaController.onActivityPinned();
                        mAppOpsListener.onActivityPinned(packageName);
                        mPipInputConsumer.registerInputConsumer();
                    }

                    @Override
                    public void onActivityUnpinned() {
                        final Pair<ComponentName, Integer> topPipActivityInfo =
                                PipUtils.getTopPipActivity(mContext);
                        final ComponentName topActivity = topPipActivityInfo.first;
                        mTouchHandler.onActivityUnpinned(topActivity);
                        mAppOpsListener.onActivityUnpinned();
                        mPipInputConsumer.unregisterInputConsumer();
                    }

                    @Override
                    public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                            boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
                        if (task.getWindowingMode() != WINDOWING_MODE_PINNED) {
                            return;
                        }
                        if (mPipTaskOrganizer.isLaunchToSplit(task)) {
                            mTouchHandler.getMotionHelper().expandIntoSplit();
                        } else {
                            mTouchHandler.getMotionHelper().expandLeavePip(
                                    clearedTask /* skipAnimation */);
                        }
                    }
                });

        mPipParamsChangedForwarder.addListener(
                new PipParamsChangedForwarder.PipParamsChangedCallback() {
                    @Override
                    public void onAspectRatioChanged(float ratio) {
                        mPipBoundsState.setAspectRatio(ratio);

                        final Rect destinationBounds =
                                mPipBoundsAlgorithm.getAdjustedDestinationBounds(
                                        mPipBoundsState.getBounds(),
                                        mPipBoundsState.getAspectRatio());
                        Objects.requireNonNull(destinationBounds, "Missing destination bounds");
                        if (!destinationBounds.equals(mPipBoundsState.getBounds())) {
                            mPipTaskOrganizer.scheduleAnimateResizePip(destinationBounds,
                                    mEnterAnimationDuration,
                                    null /* updateBoundsCallback */);
                            mTouchHandler.onAspectRatioChanged();
                            updateMovementBounds(null /* toBounds */, false /* fromRotation */,
                                    false /* fromImeAdjustment */, false /* fromShelfAdjustment */,
                                    null /* windowContainerTransaction */);
                        } else {
                            // when we enter pip for the first time, the destination bounds and pip
                            // bounds will already match, since they are calculated prior to
                            // starting the animation, so we only need to update the min/max size
                            // that is used for e.g. double tap to maximized state
                            mTouchHandler.updateMinMaxSize(ratio);
                        }
                    }

                    @Override
                    public void onActionsChanged(List<RemoteAction> actions,
                            RemoteAction closeAction) {
                        mMenuController.setAppActions(actions, closeAction);
                    }
                });

        mDisplayInsetsController.addInsetsChangedListener(mPipDisplayLayoutState.getDisplayId(),
                new DisplayInsetsController.OnInsetsChangedListener() {
                    @Override
                    public void insetsChanged(InsetsState insetsState) {
                        DisplayLayout pendingLayout = mDisplayController
                                .getDisplayLayout(mPipDisplayLayoutState.getDisplayId());
                        if (mIsInFixedRotation
                                || mIsKeyguardShowingOrAnimating
                                || pendingLayout.rotation()
                                != mPipBoundsState.getDisplayLayout().rotation()) {
                            // bail out if there is a pending rotation or fixed rotation change or
                            // there's a keyguard present
                            return;
                        }
                        int oldMaxMovementBound = mPipBoundsState.getMovementBounds().bottom;
                        onDisplayChangedUncheck(mDisplayController
                                        .getDisplayLayout(mPipDisplayLayoutState.getDisplayId()),
                                false /* saveRestoreSnapFraction */);
                        int newMaxMovementBound = mPipBoundsState.getMovementBounds().bottom;
                        if (!mEnablePipKeepClearAlgorithm) {
                            // offset PiP to adjust for bottom inset change
                            int pipTop = mPipBoundsState.getBounds().top;
                            int diff = newMaxMovementBound - oldMaxMovementBound;
                            if (diff < 0 && pipTop > newMaxMovementBound) {
                                // bottom inset has increased, move PiP up if it is too low
                                mPipMotionHelper.animateToOffset(mPipBoundsState.getBounds(),
                                        newMaxMovementBound - pipTop);
                            }
                            if (diff > 0 && oldMaxMovementBound == pipTop) {
                                // bottom inset has decreased, move PiP down if it was by the edge
                                mPipMotionHelper.animateToOffset(mPipBoundsState.getBounds(), diff);
                            }
                        }
                    }
                });

        mTabletopModeController.registerOnTabletopModeChangedListener((isInTabletopMode) -> {
            if (!mTabletopModeController.enableMoveFloatingWindowInTabletop()) return;
            final String tag = "tabletop-mode";
            if (!isInTabletopMode) {
                mPipBoundsState.removeNamedUnrestrictedKeepClearArea(tag);
                return;
            }

            // To prepare for the entry bounds.
            final Rect displayBounds = mPipBoundsState.getDisplayBounds();
            if (mTabletopModeController.getPreferredHalfInTabletopMode()
                    == TabletopModeController.PREFERRED_TABLETOP_HALF_TOP) {
                // Prefer top, avoid the bottom half of the display.
                mPipBoundsState.addNamedUnrestrictedKeepClearArea(tag, new Rect(
                        displayBounds.left, displayBounds.centerY(),
                        displayBounds.right, displayBounds.bottom));
            } else {
                // Prefer bottom, avoid the top half of the display.
                mPipBoundsState.addNamedUnrestrictedKeepClearArea(tag, new Rect(
                        displayBounds.left, displayBounds.top,
                        displayBounds.right, displayBounds.centerY()));
            }

            // Try to move the PiP window if we have entered PiP mode.
            if (mPipTransitionState.hasEnteredPip()) {
                final Rect pipBounds = mPipBoundsState.getBounds();
                final Point edgeInsets = mPipSizeSpecHandler.getScreenEdgeInsets();
                if ((pipBounds.height() + 2 * edgeInsets.y) > (displayBounds.height() / 2)) {
                    // PiP bounds is too big to fit either half, bail early.
                    return;
                }
                mMainExecutor.removeCallbacks(mMovePipInResponseToKeepClearAreasChangeCallback);
                mMainExecutor.execute(mMovePipInResponseToKeepClearAreasChangeCallback);
            }
        });

        mOneHandedController.ifPresent(controller -> {
            controller.registerTransitionCallback(
                    new OneHandedTransitionCallback() {
                        @Override
                        public void onStartFinished(Rect bounds) {
                            mTouchHandler.setOhmOffset(bounds.top);
                        }

                        @Override
                        public void onStopFinished(Rect bounds) {
                            mTouchHandler.setOhmOffset(bounds.top);
                        }
                    });
        });

        mMediaController.registerSessionListenerForCurrentUser();

        mShellController.addConfigurationChangeListener(this);
        mShellController.addKeyguardChangeListener(this);
        mShellController.addUserChangeListener(this);
        mShellController.addExternalInterface(KEY_EXTRA_SHELL_PIP,
                this::createExternalInterface, this);
    }

    private ExternalInterfaceBinder createExternalInterface() {
        return new IPipImpl(this);
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mMainExecutor;
    }

    @Override
    public void onUserChanged(int newUserId, @NonNull Context userContext) {
        // Re-register the media session listener when switching users
        mMediaController.registerSessionListenerForCurrentUser();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        mPipBoundsAlgorithm.onConfigurationChanged(mContext);
        mTouchHandler.onConfigurationChanged();
        mPipBoundsState.onConfigurationChanged();
        mPipSizeSpecHandler.onConfigurationChanged();
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        mPipTaskOrganizer.onDensityOrFontScaleChanged(mContext);
        onPipResourceDimensionsChanged();
    }

    @Override
    public void onThemeChanged() {
        mTouchHandler.onOverlayChanged();
        onDisplayChanged(new DisplayLayout(mContext, mContext.getDisplay()),
                false /* saveRestoreSnapFraction */);
    }

    private void onDisplayChanged(DisplayLayout layout, boolean saveRestoreSnapFraction) {
        if (!mPipDisplayLayoutState.getDisplayLayout().isSameGeometry(layout)) {
            PipAnimationController.PipTransitionAnimator animator =
                    mPipAnimationController.getCurrentAnimator();
            if (animator != null && animator.isRunning()) {
                // cancel any running animator, as it is using stale display layout information
                animator.cancel();
            }
            onDisplayChangedUncheck(layout, saveRestoreSnapFraction);
        }
    }

    private void onDisplayChangedUncheck(DisplayLayout layout, boolean saveRestoreSnapFraction) {
        Runnable updateDisplayLayout = () -> {
            final boolean fromRotation = Transitions.ENABLE_SHELL_TRANSITIONS
                    && mPipDisplayLayoutState.getDisplayLayout().rotation() != layout.rotation();

            // update the internal state of objects subscribed to display changes
            mPipDisplayLayoutState.setDisplayLayout(layout);

            final WindowContainerTransaction wct =
                    fromRotation ? new WindowContainerTransaction() : null;
            updateMovementBounds(null /* toBounds */,
                    fromRotation, false /* fromImeAdjustment */,
                    false /* fromShelfAdjustment */,
                    wct /* windowContainerTransaction */);
            if (wct != null) {
                mPipTaskOrganizer.applyFinishBoundsResize(wct, TRANSITION_DIRECTION_SAME,
                        false /* wasPipTopLeft */);
            }
        };

        if (mPipTaskOrganizer.isInPip() && saveRestoreSnapFraction) {
            mMenuController.attachPipMenuView();
            // Calculate the snap fraction of the current stack along the old movement bounds
            final PipSnapAlgorithm pipSnapAlgorithm = mPipBoundsAlgorithm.getSnapAlgorithm();
            final Rect postChangeBounds = new Rect(mPipBoundsState.getBounds());
            final float snapFraction = pipSnapAlgorithm.getSnapFraction(postChangeBounds,
                    mPipBoundsAlgorithm.getMovementBounds(postChangeBounds),
                    mPipBoundsState.getStashedState());

            // Scale PiP on density dpi change, so it appears to be the same size physically.
            final boolean densityDpiChanged =
                    mPipDisplayLayoutState.getDisplayLayout().densityDpi() != 0
                    && (mPipDisplayLayoutState.getDisplayLayout().densityDpi()
                            != layout.densityDpi());
            if (densityDpiChanged) {
                final float scale = (float) layout.densityDpi()
                        / mPipDisplayLayoutState.getDisplayLayout().densityDpi();
                postChangeBounds.set(0, 0,
                        (int) (postChangeBounds.width() * scale),
                        (int) (postChangeBounds.height() * scale));
            }

            updateDisplayLayout.run();

            // Calculate the PiP bounds in the new orientation based on same fraction along the
            // rotated movement bounds.
            final Rect postChangeMovementBounds = mPipBoundsAlgorithm.getMovementBounds(
                    postChangeBounds, false /* adjustForIme */);
            pipSnapAlgorithm.applySnapFraction(postChangeBounds, postChangeMovementBounds,
                    snapFraction, mPipBoundsState.getStashedState(),
                    mPipBoundsState.getStashOffset(),
                    mPipDisplayLayoutState.getDisplayBounds(),
                    mPipDisplayLayoutState.getDisplayLayout().stableInsets());

            if (densityDpiChanged) {
                // Using PipMotionHelper#movePip directly here may cause race condition since
                // the app content in PiP mode may or may not be updated for the new density dpi.
                final int duration = mContext.getResources().getInteger(
                        R.integer.config_pipEnterAnimationDuration);
                mPipTaskOrganizer.scheduleAnimateResizePip(
                        postChangeBounds, duration, null /* updateBoundsCallback */);
            } else {
                // Directly move PiP to its final destination bounds without animation.
                mPipTaskOrganizer.scheduleFinishResizePip(postChangeBounds);
            }

            // if the pip window size is beyond allowed bounds user resize to normal bounds
            if (mPipBoundsState.getBounds().width() < mPipBoundsState.getMinSize().x
                    || mPipBoundsState.getBounds().width() > mPipBoundsState.getMaxSize().x
                    || mPipBoundsState.getBounds().height() < mPipBoundsState.getMinSize().y
                    || mPipBoundsState.getBounds().height() > mPipBoundsState.getMaxSize().y) {
                mTouchHandler.userResizeTo(mPipBoundsState.getNormalBounds(), snapFraction);
            }

        } else {
            updateDisplayLayout.run();
        }
    }

    private void onSystemUiStateChanged(boolean isValidState, int flag) {
        mTouchHandler.onSystemUiStateChanged(isValidState);
    }

    /**
     * Expands the PIP.
     */
    public void expandPip() {
        mTouchHandler.getMotionHelper().expandLeavePip(false /* skipAnimation */);
    }

    /**
     * Hides the PIP menu.
     */
    public void hidePipMenu(Runnable onStartCallback, Runnable onEndCallback) {
        mMenuController.hideMenu(onStartCallback, onEndCallback);
    }

    /**
     * Sent from KEYCODE_WINDOW handler in PhoneWindowManager, to request the menu to be shown.
     */
    public void showPictureInPictureMenu() {
        mTouchHandler.showPictureInPictureMenu();
    }

    /**
     * If {@param keyguardShowing} is {@code false} and {@param animating} is {@code true},
     * we would wait till the dismissing animation of keyguard and surfaces behind to be
     * finished first to reset the visibility of PiP window.
     * See also {@link #onKeyguardDismissAnimationFinished()}
     */
    @Override
    public void onKeyguardVisibilityChanged(boolean visible, boolean occluded,
            boolean animatingDismiss) {
        if (!mPipTransitionState.hasEnteredPip()) {
            return;
        }
        if (visible) {
            mIsKeyguardShowingOrAnimating = true;
            hidePipMenu(null /* onStartCallback */, null /* onEndCallback */);
            mPipTaskOrganizer.setPipVisibility(false);
        } else if (!animatingDismiss) {
            mIsKeyguardShowingOrAnimating = false;
            mPipTaskOrganizer.setPipVisibility(true);
        }
    }

    @Override
    public void onKeyguardDismissAnimationFinished() {
        if (mPipTaskOrganizer.isInPip()) {
            mIsKeyguardShowingOrAnimating = false;
            mPipTaskOrganizer.setPipVisibility(true);
        }
    }

    /**
     * Sets a customized touch gesture that replaces the default one.
     */
    public void setTouchGesture(PipTouchGesture gesture) {
        mTouchHandler.setTouchGesture(gesture);
    }

    /**
     * Sets both shelf visibility and its height.
     */
    private void setShelfHeight(boolean visible, int height) {
        if (!mIsKeyguardShowingOrAnimating) {
            setShelfHeightLocked(visible, height);
        }
    }

    private void setLauncherKeepClearAreaHeight(boolean visible, int height) {
        if (visible) {
            Rect rect = new Rect(
                    0, mPipBoundsState.getDisplayBounds().bottom - height,
                    mPipBoundsState.getDisplayBounds().right,
                    mPipBoundsState.getDisplayBounds().bottom);
            mPipBoundsState.addNamedUnrestrictedKeepClearArea(LAUNCHER_KEEP_CLEAR_AREA_TAG, rect);
            updatePipPositionForKeepClearAreas();
        } else {
            mPipBoundsState.removeNamedUnrestrictedKeepClearArea(LAUNCHER_KEEP_CLEAR_AREA_TAG);
            // postpone moving in response to hide of Launcher in case there's another change
            mMainExecutor.removeCallbacks(mMovePipInResponseToKeepClearAreasChangeCallback);
            mMainExecutor.executeDelayed(
                    mMovePipInResponseToKeepClearAreasChangeCallback,
                    PIP_KEEP_CLEAR_AREAS_DELAY);
        }
    }

    private void setLauncherAppIconSize(int iconSizePx) {
        mPipBoundsState.getLauncherState().setAppIconSizePx(iconSizePx);
    }

    private void setOnIsInPipStateChangedListener(Consumer<Boolean> callback) {
        mOnIsInPipStateChangedListener = callback;
        if (mOnIsInPipStateChangedListener != null) {
            callback.accept(mPipTransitionState.isInPip());
        }
    }

    private void setShelfHeightLocked(boolean visible, int height) {
        final int shelfHeight = visible ? height : 0;
        mPipBoundsState.setShelfVisibility(visible, shelfHeight);
    }

    @VisibleForTesting
    void setPinnedStackAnimationListener(PipAnimationListener callback) {
        mPinnedStackAnimationRecentsCallback = callback;
        onPipResourceDimensionsChanged();
    }

    @VisibleForTesting
    boolean hasPinnedStackAnimationListener() {
        return mPinnedStackAnimationRecentsCallback != null;
    }

    private void onPipResourceDimensionsChanged() {
        if (mPinnedStackAnimationRecentsCallback != null) {
            mPinnedStackAnimationRecentsCallback.onPipResourceDimensionsChanged(
                    mContext.getResources().getDimensionPixelSize(R.dimen.pip_corner_radius),
                    mContext.getResources().getDimensionPixelSize(R.dimen.pip_shadow_radius));
        }
    }

    private Rect startSwipePipToHome(ComponentName componentName, ActivityInfo activityInfo,
            PictureInPictureParams pictureInPictureParams,
            int launcherRotation, Rect hotseatKeepClearArea) {

        if (mEnablePipKeepClearAlgorithm) {
            // pre-emptively add the keep clear area for Hotseat, so that it is taken into account
            // when calculating the entry destination bounds of PiP window
            mPipBoundsState.getRestrictedKeepClearAreas().add(hotseatKeepClearArea);
        } else {
            int shelfHeight = hotseatKeepClearArea.height();
            setShelfHeightLocked(shelfHeight > 0 /* visible */, shelfHeight);
        }
        onDisplayRotationChangedNotInPip(mContext, launcherRotation);
        final Rect entryBounds = mPipTaskOrganizer.startSwipePipToHome(componentName, activityInfo,
                pictureInPictureParams);
        // sync mPipBoundsState with the newly calculated bounds.
        mPipBoundsState.setNormalBounds(entryBounds);
        return entryBounds;
    }

    private void stopSwipePipToHome(int taskId, ComponentName componentName, Rect destinationBounds,
            SurfaceControl overlay) {
        mPipTaskOrganizer.stopSwipePipToHome(taskId, componentName, destinationBounds, overlay);
    }

    private String getTransitionTag(int direction) {
        switch (direction) {
            case TRANSITION_DIRECTION_TO_PIP:
                return "TRANSITION_TO_PIP";
            case TRANSITION_DIRECTION_LEAVE_PIP:
                return "TRANSITION_LEAVE_PIP";
            case TRANSITION_DIRECTION_LEAVE_PIP_TO_SPLIT_SCREEN:
                return "TRANSITION_LEAVE_PIP_TO_SPLIT_SCREEN";
            case TRANSITION_DIRECTION_REMOVE_STACK:
                return "TRANSITION_REMOVE_STACK";
            case TRANSITION_DIRECTION_SNAP_AFTER_RESIZE:
                return "TRANSITION_SNAP_AFTER_RESIZE";
            case TRANSITION_DIRECTION_USER_RESIZE:
                return "TRANSITION_USER_RESIZE";
            case TRANSITION_DIRECTION_EXPAND_OR_UNEXPAND:
                return "TRANSITION_EXPAND_OR_UNEXPAND";
            default:
                return "TRANSITION_LEAVE_UNKNOWN";
        }
    }

    @Override
    public void onPipTransitionStarted(int direction, Rect pipBounds) {
        // Begin InteractionJankMonitor with PIP transition CUJs
        final InteractionJankMonitor.Configuration.Builder builder =
                InteractionJankMonitor.Configuration.Builder.withSurface(
                        CUJ_PIP_TRANSITION, mContext, mPipTaskOrganizer.getSurfaceControl())
                .setTag(getTransitionTag(direction))
                .setTimeout(2000);
        InteractionJankMonitor.getInstance().begin(builder);

        if (isOutPipDirection(direction)) {
            // Exiting PIP, save the reentry state to restore to when re-entering.
            saveReentryState(pipBounds);
        }
        // Disable touches while the animation is running
        mTouchHandler.setTouchEnabled(false);
        if (mPinnedStackAnimationRecentsCallback != null) {
            mPinnedStackAnimationRecentsCallback.onPipAnimationStarted();
            if (direction == TRANSITION_DIRECTION_LEAVE_PIP) {
                mPinnedStackAnimationRecentsCallback.onExpandPip();
            }
        }
    }

    /** Save the state to restore to on re-entry. */
    public void saveReentryState(Rect pipBounds) {
        float snapFraction = mPipBoundsAlgorithm.getSnapFraction(pipBounds);

        if (!mPipBoundsState.hasUserResizedPip()) {
            mPipBoundsState.saveReentryState(null /* bounds */, snapFraction);
            return;
        }

        Size reentrySize = new Size(pipBounds.width(), pipBounds.height());

        // TODO: b/279937014 Investigate why userResizeBounds are empty with shell transitions on
        // fallback to using the userResizeBounds if userResizeBounds are not empty
        if (!mTouchHandler.getUserResizeBounds().isEmpty()) {
            Rect userResizeBounds = mTouchHandler.getUserResizeBounds();
            reentrySize = new Size(userResizeBounds.width(), userResizeBounds.height());
        }

        mPipBoundsState.saveReentryState(reentrySize, snapFraction);
    }

    @Override
    public void onPipTransitionFinished(int direction) {
        onPipTransitionFinishedOrCanceled(direction);
    }

    @Override
    public void onPipTransitionCanceled(int direction) {
        onPipTransitionFinishedOrCanceled(direction);
    }

    private void onPipTransitionFinishedOrCanceled(int direction) {
        // End InteractionJankMonitor with PIP transition by CUJs
        InteractionJankMonitor.getInstance().end(CUJ_PIP_TRANSITION);

        // Re-enable touches after the animation completes
        mTouchHandler.setTouchEnabled(true);
        mTouchHandler.onPinnedStackAnimationEnded(direction);
    }

    private void updateMovementBounds(@Nullable Rect toBounds, boolean fromRotation,
            boolean fromImeAdjustment, boolean fromShelfAdjustment,
            WindowContainerTransaction wct) {
        // Populate inset / normal bounds and DisplayInfo from mPipBoundsHandler before
        // passing to mTouchHandler/mPipTaskOrganizer
        final Rect outBounds = new Rect(toBounds);
        final int rotation = mPipDisplayLayoutState.getDisplayLayout().rotation();

        mPipBoundsAlgorithm.getInsetBounds(mTmpInsetBounds);
        mPipBoundsState.setNormalBounds(mPipBoundsAlgorithm.getNormalBounds());
        if (outBounds.isEmpty()) {
            outBounds.set(mPipBoundsAlgorithm.getDefaultBounds());
        }

        // mTouchHandler would rely on the bounds populated from mPipTaskOrganizer
        mPipTaskOrganizer.onMovementBoundsChanged(outBounds, fromRotation, fromImeAdjustment,
                fromShelfAdjustment, wct);
        mPipTaskOrganizer.finishResizeForMenu(outBounds);
        mTouchHandler.onMovementBoundsChanged(mTmpInsetBounds, mPipBoundsState.getNormalBounds(),
                outBounds, fromImeAdjustment, fromShelfAdjustment, rotation);
    }

    /**
     * Updates the display info and display layout on rotation change. This is needed even when we
     * aren't in PIP because the rotation layout is used to calculate the proper insets for the
     * next enter animation into PIP.
     */
    private void onDisplayRotationChangedNotInPip(Context context, int toRotation) {
        // Update the display layout, note that we have to do this on every rotation even if we
        // aren't in PIP since we need to update the display layout to get the right resources
        mPipDisplayLayoutState.rotateTo(toRotation);
    }

    /**
     * Updates the display info, calculating and returning the new stack and movement bounds in the
     * new orientation of the device if necessary.
     *
     * @return {@code true} if internal {@link DisplayInfo} is rotated, {@code false} otherwise.
     */
    private boolean onDisplayRotationChanged(Context context, Rect outBounds, Rect oldBounds,
            Rect outInsetBounds,
            int displayId, int fromRotation, int toRotation, WindowContainerTransaction t) {
        // Bail early if the event is not sent to current display
        if ((displayId != mPipDisplayLayoutState.getDisplayId()) || (fromRotation == toRotation)) {
            return false;
        }

        // Bail early if the pinned task is staled.
        final ActivityTaskManager.RootTaskInfo pinnedTaskInfo;
        try {
            pinnedTaskInfo = ActivityTaskManager.getService()
                    .getRootTaskInfo(WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
            if (pinnedTaskInfo == null) return false;
        } catch (RemoteException e) {
            ProtoLog.e(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Failed to get RootTaskInfo for pinned task, %s", TAG, e);
            return false;
        }
        final PipSnapAlgorithm pipSnapAlgorithm = mPipBoundsAlgorithm.getSnapAlgorithm();

        // Calculate the snap fraction of the current stack along the old movement bounds
        final Rect postChangeStackBounds = new Rect(oldBounds);
        final float snapFraction = pipSnapAlgorithm.getSnapFraction(postChangeStackBounds,
                        mPipBoundsAlgorithm.getMovementBounds(postChangeStackBounds),
                        mPipBoundsState.getStashedState());

        // Update the display layout
        mPipDisplayLayoutState.rotateTo(toRotation);

        // Calculate the stack bounds in the new orientation based on same fraction along the
        // rotated movement bounds.
        final Rect postChangeMovementBounds = mPipBoundsAlgorithm.getMovementBounds(
                postChangeStackBounds, false /* adjustForIme */);
        pipSnapAlgorithm.applySnapFraction(postChangeStackBounds, postChangeMovementBounds,
                snapFraction, mPipBoundsState.getStashedState(), mPipBoundsState.getStashOffset(),
                mPipDisplayLayoutState.getDisplayBounds(),
                mPipDisplayLayoutState.getDisplayLayout().stableInsets());

        mPipBoundsAlgorithm.getInsetBounds(outInsetBounds);
        outBounds.set(postChangeStackBounds);
        t.setBounds(pinnedTaskInfo.token, outBounds);
        return true;
    }

    private void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = "  ";
        pw.println(TAG);
        mMenuController.dump(pw, innerPrefix);
        mTouchHandler.dump(pw, innerPrefix);
        mPipBoundsAlgorithm.dump(pw, innerPrefix);
        mPipTaskOrganizer.dump(pw, innerPrefix);
        mPipBoundsState.dump(pw, innerPrefix);
        mPipInputConsumer.dump(pw, innerPrefix);
        mPipSizeSpecHandler.dump(pw, innerPrefix);
        mPipDisplayLayoutState.dump(pw, innerPrefix);
    }

    /**
     * The interface for calls from outside the Shell, within the host process.
     */
    private class PipImpl implements Pip {
        @Override
        public void expandPip() {
            mMainExecutor.execute(() -> {
                PipController.this.expandPip();
            });
        }

        @Override
        public void onSystemUiStateChanged(boolean isSysUiStateValid, int flag) {
            mMainExecutor.execute(() -> {
                PipController.this.onSystemUiStateChanged(isSysUiStateValid, flag);
            });
        }

        @Override
        public void setOnIsInPipStateChangedListener(Consumer<Boolean> callback) {
            mMainExecutor.execute(() -> {
                PipController.this.setOnIsInPipStateChangedListener(callback);
            });
        }

        @Override
        public void addPipExclusionBoundsChangeListener(Consumer<Rect> listener) {
            mMainExecutor.execute(() -> {
                mPipBoundsState.addPipExclusionBoundsChangeCallback(listener);
            });
        }

        @Override
        public void removePipExclusionBoundsChangeListener(Consumer<Rect> listener) {
            mMainExecutor.execute(() -> {
                mPipBoundsState.removePipExclusionBoundsChangeCallback(listener);
            });
        }

        @Override
        public void showPictureInPictureMenu() {
            mMainExecutor.execute(() -> {
                PipController.this.showPictureInPictureMenu();
            });
        }
    }

    /**
     * The interface for calls from outside the host process.
     */
    @BinderThread
    private static class IPipImpl extends IPip.Stub implements ExternalInterfaceBinder {
        private PipController mController;
        private final SingleInstanceRemoteListener<PipController,
                IPipAnimationListener> mListener;
        private final PipAnimationListener mPipAnimationListener = new PipAnimationListener() {
            @Override
            public void onPipAnimationStarted() {
                mListener.call(l -> l.onPipAnimationStarted());
            }

            @Override
            public void onPipResourceDimensionsChanged(int cornerRadius, int shadowRadius) {
                mListener.call(l -> l.onPipResourceDimensionsChanged(cornerRadius, shadowRadius));
            }

            @Override
            public void onExpandPip() {
                mListener.call(l -> l.onExpandPip());
            }
        };

        IPipImpl(PipController controller) {
            mController = controller;
            mListener = new SingleInstanceRemoteListener<>(mController,
                    c -> c.setPinnedStackAnimationListener(mPipAnimationListener),
                    c -> c.setPinnedStackAnimationListener(null));
        }

        /**
         * Invalidates this instance, preventing future calls from updating the controller.
         */
        @Override
        public void invalidate() {
            mController = null;
            // Unregister the listener to ensure any registered binder death recipients are unlinked
            mListener.unregister();
        }

        @Override
        public Rect startSwipePipToHome(ComponentName componentName, ActivityInfo activityInfo,
                PictureInPictureParams pictureInPictureParams, int launcherRotation,
                Rect keepClearArea) {
            Rect[] result = new Rect[1];
            executeRemoteCallWithTaskPermission(mController, "startSwipePipToHome",
                    (controller) -> {
                        result[0] = controller.startSwipePipToHome(componentName, activityInfo,
                                pictureInPictureParams, launcherRotation, keepClearArea);
                    }, true /* blocking */);
            return result[0];
        }

        @Override
        public void stopSwipePipToHome(int taskId, ComponentName componentName,
                Rect destinationBounds, SurfaceControl overlay) {
            if (overlay != null) {
                overlay.setUnreleasedWarningCallSite("PipController.stopSwipePipToHome");
            }
            executeRemoteCallWithTaskPermission(mController, "stopSwipePipToHome",
                    (controller) -> controller.stopSwipePipToHome(
                            taskId, componentName, destinationBounds, overlay));
        }

        @Override
        public void setShelfHeight(boolean visible, int height) {
            executeRemoteCallWithTaskPermission(mController, "setShelfHeight",
                    (controller) -> controller.setShelfHeight(visible, height));
        }

        @Override
        public void setLauncherKeepClearAreaHeight(boolean visible, int height) {
            executeRemoteCallWithTaskPermission(mController, "setLauncherKeepClearAreaHeight",
                    (controller) -> controller.setLauncherKeepClearAreaHeight(visible, height));
        }

        @Override
        public void setLauncherAppIconSize(int iconSizePx) {
            executeRemoteCallWithTaskPermission(mController, "setLauncherAppIconSize",
                    (controller) -> controller.setLauncherAppIconSize(iconSizePx));
        }

        @Override
        public void setPipAnimationListener(IPipAnimationListener listener) {
            executeRemoteCallWithTaskPermission(mController, "setPipAnimationListener",
                    (controller) -> {
                        if (listener != null) {
                            mListener.register(listener);
                        } else {
                            mListener.unregister();
                        }
                    });
        }

        @Override
        public void setPipAnimationTypeToAlpha() {
            executeRemoteCallWithTaskPermission(mController, "setPipAnimationTypeToAlpha",
                    (controller) -> controller.mPipAnimationController.setOneShotEnterAnimationType(
                            ANIM_TYPE_ALPHA));
        }
    }
}
