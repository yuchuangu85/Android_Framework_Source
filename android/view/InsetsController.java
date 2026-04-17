/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view;

import static android.internal.perfetto.protos.Insetscontroller.InsetsControllerProto.CONTROL;
import static android.internal.perfetto.protos.Insetscontroller.InsetsControllerProto.STATE;
import static android.os.Trace.TRACE_TAG_VIEW;
import static android.view.InsetsSource.ID_IME;
import static android.view.InsetsSource.ID_IME_CAPTION_BAR;
import static android.view.ViewProtoLogGroups.IME_INSETS_CONTROLLER;
import static android.view.ViewProtoLogGroups.INSETS_CONTROLLER_DEBUG;
import static android.view.WindowInsets.Type.TYPES;
import static android.view.WindowInsets.Type.all;
import static android.view.WindowInsets.Type.captionBar;
import static android.view.WindowInsets.Type.ime;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.WindowConfiguration;
import android.content.Context;
import android.content.res.CompatibilityInfo;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Trace;
import android.os.UserHandle;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.WindowInsets.Type;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsAnimation.Bounds;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.Flags;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.ImeTracker.InputMethodJankContext;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.inputmethod.ImeTracing;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.protolog.ProtoLog;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Implements {@link WindowInsetsController} on the client.
 *
 * @hide
 */
public class InsetsController implements WindowInsetsController, InsetsAnimationControlCallbacks,
        InsetsAnimationControlRunner.SurfaceParamsApplier {

    private int mTypesBeingCancelled;

    public interface Host {

        @NonNull
        Handler getHandler();

        /**
         * Notifies host that {@link InsetsController#getState()} has changed.
         */
        void notifyInsetsChanged();

        void dispatchWindowInsetsAnimationPrepare(@NonNull WindowInsetsAnimation animation,
                boolean isUserAnimation, boolean isResizeAnimation, boolean hasAnimationCallback);

        @Nullable
        Bounds dispatchWindowInsetsAnimationStart(
                @NonNull WindowInsetsAnimation animation, @NonNull Bounds bounds,
                boolean isUserAnimation, boolean isResizeAnimation, boolean hasAnimationCallback);

        @Nullable
        WindowInsets dispatchWindowInsetsAnimationProgress(@NonNull WindowInsets insets,
                @NonNull InsetsState state, @NonNull List<WindowInsetsAnimation> runningAnimations,
                boolean hasUserAnimation, boolean hasResizeAnimation, boolean hasAnimationCallback,
                @InsetsType int hidingTypes);

        void dispatchWindowInsetsAnimationEnd(@NonNull WindowInsetsAnimation animation,
                boolean isUserAnimation, boolean isResizeAnimation, boolean hasAnimationCallback);

        /**
         * Requests host to apply surface params in synchronized manner.
         */
        void applySurfaceParams(@NonNull SyncRtSurfaceTransactionApplier.SurfaceParams... params);

        /**
         * @see ViewRootImpl#updateCompatSysUiVisibility(int, int, int)
         */
        default void updateCompatSysUiVisibility(@InsetsType int visibleTypes,
                @InsetsType int requestedVisibleTypes, @InsetsType int controllableTypes) {
        }

        /**
         * Called when the requested visibilities of insets have been modified by the client.
         * The visibilities should be reported back to WM.
         *
         * @param types      Bitwise flags of types requested visible.
         * @param statsToken the token tracking the current IME request or {@code null} otherwise.
         */
        void updateRequestedVisibleTypes(@InsetsType int types,
                @Nullable ImeTracker.Token statsToken);

        /**
         * @return Whether the host has any callbacks it wants to synchronize the animations with.
         *         If there are no callbacks, the animation will be off-loaded to another thread and
         *         slightly different animation curves are picked.
         */
        boolean hasAnimationCallbacks();

        /**
         * @see WindowInsetsController#setSystemBarsAppearance
         */
        void setSystemBarsAppearance(@Appearance int appearance, @Appearance int mask);

        /**
         * @see WindowInsetsController#getSystemBarsAppearance()
         */
        @Appearance
        int getSystemBarsAppearance();

        /**
         * @see WindowInsetsController#setSystemBarsBehavior
         */
        void setSystemBarsBehavior(@Behavior int behavior);

        /**
         * @see WindowInsetsController#getSystemBarsBehavior
         */
        @Behavior
        int getSystemBarsBehavior();

        /**
         * Releases a surface and ensure that this is done after {@link #applySurfaceParams} has
         * finished applying params.
         */
        void releaseSurfaceControlFromRt(@NonNull SurfaceControl surfaceControl);

        /**
         * If this host is a view hierarchy, adds a pre-draw runnable to ensure proper ordering as
         * described in {@link WindowInsetsAnimation.Callback#onPrepare}. If this host isn't a
         * view hierarchy, the runnable can be executed immediately.
         */
        void addOnPreDrawRunnable(@NonNull Runnable r);

        /**
         * Adds a runnable to be executed during {@link Choreographer#CALLBACK_INSETS_ANIMATION}
         * phase.
         */
        void postInsetsAnimationCallback(@NonNull Runnable r);

        /**
         * Obtains {@link InputMethodManager} instance from host.
         */
        @Nullable
        InputMethodManager getInputMethodManager();

        /**
         * @return title of the rootView, if it has one.
         * Note: this method is for debugging purposes only.
         */
        @Nullable
        String getRootViewTitle();

        /**
         * @return the context related to the rootView.
         */
        @Nullable
        default Context getRootViewContext() {
            return null;
        }

        /** @see ViewRootImpl#dipToPx */
        int dipToPx(int dips);

        /**
         * @return token associated with the host, if it has one.
         */
        @Nullable
        IBinder getWindowToken();

        /**
         * @return Translator associated with the host, if it has one.
         */
        @Nullable
        default CompatibilityInfo.Translator getTranslator() {
            return null;
        }

        /**
         * Notifies when the insets types of running animation have changed. The animatingTypes
         * contain all types, which have an ongoing animation.
         *
         * @param animatingTypes the {@link InsetsType}s that are currently animating
         * @param statsToken     the token tracking the current IME request or {@code null}
         *                       otherwise.
         */
        default void updateAnimatingTypes(@InsetsType int animatingTypes,
                @Nullable ImeTracker.Token statsToken) {
        }

        /**
         * @return {@code true} if the synchronized insets animation is allowed for this host,
         *         {@code false} otherwise.
         */
        default boolean allowsSyncedInsetsAnimation() {
            return false;
        }

        /** @see ViewRootImpl#isHandlingPointerEvent */
        default boolean isHandlingPointerEvent() {
            return false;
        }
    }

    private static final String TAG = "InsetsController";
    private static final int ANIMATION_DURATION_MOVE_IN_MS = 275;
    private static final int ANIMATION_DURATION_MOVE_OUT_MS = 340;
    private static final int ANIMATION_DURATION_FADE_IN_MS = 500;
    private static final int ANIMATION_DURATION_FADE_OUT_MS = 1500;

    /** Visible for WindowManagerWrapper */
    public static final int ANIMATION_DURATION_RESIZE = 300;

    private static final int ANIMATION_DELAY_DIM_MS = 500;

    static final int ANIMATION_DURATION_SYNC_IME_MS = 285;
    static final int ANIMATION_DURATION_UNSYNC_IME_MS = 200;

    private static final int PENDING_CONTROL_TIMEOUT_MS = 2000;

    private static final Interpolator SYSTEM_BARS_INSETS_INTERPOLATOR =
            new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final Interpolator SYSTEM_BARS_ALPHA_INTERPOLATOR =
            new PathInterpolator(0.3f, 0f, 1f, 1f);
    private static final Interpolator SYSTEM_BARS_DIM_INTERPOLATOR = alphaFraction -> {
        // While playing dim animation, alphaFraction is changed from 1f to 0f. Here changes it to
        // time-based fraction for computing delay and interpolation.
        float fraction = 1 - alphaFraction;
        final float fractionDelay = (float) ANIMATION_DELAY_DIM_MS / ANIMATION_DURATION_FADE_OUT_MS;
        if (fraction <= fractionDelay) {
            return 1f;
        } else {
            float innerFraction = (fraction - fractionDelay) / (1f - fractionDelay);
            return 1f - SYSTEM_BARS_ALPHA_INTERPOLATOR.getInterpolation(innerFraction);
        }
    };
    static final Interpolator SYNC_IME_INTERPOLATOR =
            new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final Interpolator LINEAR_OUT_SLOW_IN_INTERPOLATOR =
            new PathInterpolator(0, 0, 0.2f, 1f);
    static final Interpolator FAST_OUT_LINEAR_IN_INTERPOLATOR =
            new PathInterpolator(0.4f, 0f, 1f, 1f);

    /** Visible for WindowManagerWrapper */
    public static final Interpolator RESIZE_INTERPOLATOR = new LinearInterpolator();

    /** The amount IME will move up/down when animating in floating mode. */
    private static final int FLOATING_IME_BOTTOM_INSET_DP = -80;

    static final boolean DEBUG = false;
    static final boolean WARN = false;

    /**
     * Layout mode during insets animation: The views should be laid out as if the changing inset
     * types are fully shown. Before starting the animation, {@link View#onApplyWindowInsets} will
     * be called as if the changing insets types are shown, which will result in the views being
     * laid out as if the insets are fully shown.
     */
    public static final int LAYOUT_INSETS_DURING_ANIMATION_SHOWN = 0;

    /**
     * Layout mode during insets animation: The views should be laid out as if the changing inset
     * types are fully hidden. Before starting the animation, {@link View#onApplyWindowInsets} will
     * be called as if the changing insets types are hidden, which will result in the views being
     * laid out as if the insets are fully hidden.
     */
    public static final int LAYOUT_INSETS_DURING_ANIMATION_HIDDEN = 1;

    /**
     * Determines the behavior of how the views should be laid out during an insets animation that
     * is controlled by the application by calling {@link #controlWindowInsetsAnimation}.
     * <p>
     * When the animation is system-initiated, the layout mode is always chosen such that the
     * pre-animation layout will represent the opposite of the starting state, i.e. when insets
     * are appearing, {@link #LAYOUT_INSETS_DURING_ANIMATION_SHOWN} will be used. When insets
     * are disappearing, {@link #LAYOUT_INSETS_DURING_ANIMATION_HIDDEN} will be used.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            LAYOUT_INSETS_DURING_ANIMATION_SHOWN,
            LAYOUT_INSETS_DURING_ANIMATION_HIDDEN,
    })
    @interface LayoutInsetsDuringAnimation {
    }

    /** Not running an animation. */
    public static final int ANIMATION_TYPE_NONE = -1;

    /** Running animation will show insets */
    public static final int ANIMATION_TYPE_SHOW = 0;

    /** Running animation will hide insets */
    public static final int ANIMATION_TYPE_HIDE = 1;

    /** Running animation is controlled by user via {@link #controlWindowInsetsAnimation} */
    public static final int ANIMATION_TYPE_USER = 2;

    /** Running animation will resize insets */
    public static final int ANIMATION_TYPE_RESIZE = 3;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            ANIMATION_TYPE_NONE,
            ANIMATION_TYPE_SHOW,
            ANIMATION_TYPE_HIDE,
            ANIMATION_TYPE_USER,
            ANIMATION_TYPE_RESIZE,
    })
    public @interface AnimationType {
    }

    /**
     * Translation animation evaluator.
     */
    private static final TypeEvaluator<Insets> sEvaluator =
            (fraction, startValue, endValue) -> Insets.of(
                    (int) (startValue.left + fraction * (endValue.left - startValue.left)),
                    (int) (startValue.top + fraction * (endValue.top - startValue.top)),
                    (int) (startValue.right + fraction * (endValue.right - startValue.right)),
                    (int) (startValue.bottom + fraction * (endValue.bottom - startValue.bottom)));

    /** Logging listener. */
    @Nullable
    private WindowInsetsAnimationControlListener mLoggingListener;

    /** Context for {@link android.view.inputmethod.ImeTracker.ImeJankTracker} to monitor jank. */
    private final InputMethodJankContext mJankContext = new InputMethodJankContext() {
        @Override
        public Context getDisplayContext() {
            return mHost.getRootViewContext();
        }

        @Override
        public SurfaceControl getTargetSurfaceControl() {
            final InsetsSourceControl imeSourceControl = getImeSourceConsumer().getControl();
            return imeSourceControl != null ? imeSourceControl.getLeash() : null;
        }

        @Override
        public String getHostPackageName() {
            return mHost.getRootViewContext().getPackageName();
        }
    };

    /**
     * The default implementation of listener, to be used by InsetsController and InsetsPolicy to
     * animate insets.
     */
    public static final class InternalAnimationControlListener
            implements WindowInsetsAnimationControlListener, InsetsAnimationSpec {

        @Nullable
        private WindowInsetsAnimationController mController;
        @Nullable
        private ValueAnimator mAnimator;
        private final boolean mShow;
        private final boolean mHasAnimationCallbacks;
        @InsetsType
        private final int mRequestedTypes;
        @Behavior
        private final int mBehavior;
        private final boolean mDisable;
        private final int mFloatingImeBottomInset;
        @Nullable
        private final WindowInsetsAnimationControlListener mLoggingListener;
        @Nullable
        private final InputMethodJankContext mInputMethodJankContext;

        /* The handler of the thread used for running inset animations. */
        @Nullable
        private final Handler mHandler;

        public InternalAnimationControlListener(boolean show, boolean hasAnimationCallbacks,
                @InsetsType int requestedTypes, @Behavior int behavior, boolean disable,
                int floatingImeBottomInset,
                @Nullable WindowInsetsAnimationControlListener loggingListener,
                @Nullable InputMethodJankContext jankContext, @Nullable Handler handler) {
            mShow = show;
            mHasAnimationCallbacks = hasAnimationCallbacks;
            mRequestedTypes = requestedTypes;
            mBehavior = behavior;
            mDisable = disable;
            mFloatingImeBottomInset = floatingImeBottomInset;
            mLoggingListener = loggingListener;
            mInputMethodJankContext = jankContext;
            mHandler = handler;
        }

        @Override
        public void onReady(@NonNull WindowInsetsAnimationController controller,
                @InsetsType int types) {
            mController = controller;
            ProtoLog.d(INSETS_CONTROLLER_DEBUG, "default animation onReady types: %s",
                    Type.toString(types));
            if (mLoggingListener != null) {
                mLoggingListener.onReady(controller, types);
            }

            if (mDisable) {
                onAnimationFinish();
                return;
            }
            final boolean hasZeroInsetsIme = controller.hasZeroInsetsIme();
            mAnimator = ValueAnimator.ofFloat(0f, 1f);
            mAnimator.setDuration(controller.getDurationMs());
            mAnimator.setInterpolator(new LinearInterpolator());
            Insets hiddenInsets = controller.getHiddenStateInsets();
            // IME with zero insets is a special case: it will animate-in from offscreen and end
            // with final insets of zero and vice-versa.
            hiddenInsets = hasZeroInsetsIme
                    ? Insets.of(hiddenInsets.left, hiddenInsets.top, hiddenInsets.right,
                            mFloatingImeBottomInset)
                    : hiddenInsets;
            Insets start = mShow
                    ? hiddenInsets
                    : controller.getShownStateInsets();
            Insets end = mShow
                    ? controller.getShownStateInsets()
                    : hiddenInsets;
            Interpolator insetsInterpolator = controller.getInsetsInterpolator();
            Interpolator alphaInterpolator = getAlphaInterpolator();
            mAnimator.addUpdateListener(animation -> {
                float rawFraction = animation.getAnimatedFraction();
                float alphaFraction = mShow
                        ? rawFraction
                        : 1 - rawFraction;
                float insetsFraction = insetsInterpolator.getInterpolation(rawFraction);
                controller.setInsetsAndAlpha(
                        sEvaluator.evaluate(insetsFraction, start, end),
                        alphaInterpolator.getInterpolation(alphaFraction),
                        rawFraction);
                if (DEBUG) {
                    Log.d(TAG, "Default animation setInsetsAndAlpha fraction: " + insetsFraction);
                }
            });
            mAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    if (mInputMethodJankContext == null) return;
                    ImeTracker.forJank().onRequestAnimation(
                            mInputMethodJankContext,
                            getAnimationType(),
                            !mHasAnimationCallbacks,
                            mHandler);
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    if (mInputMethodJankContext == null) return;
                    ImeTracker.forJank().onCancelAnimation(getAnimationType());
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    onAnimationFinish();
                    if (mInputMethodJankContext == null) return;
                    ImeTracker.forJank().onFinishAnimation(getAnimationType());
                }
            });
            mAnimator.start();
        }

        @Override
        public void onFinished(@NonNull WindowInsetsAnimationController controller) {
            ProtoLog.d(INSETS_CONTROLLER_DEBUG,
                    "InternalAnimationControlListener onFinished types: %s",
                    Type.toString(mRequestedTypes));
            if (mLoggingListener != null) {
                mLoggingListener.onFinished(controller);
            }
        }

        @Override
        public void onCancelled(@Nullable WindowInsetsAnimationController controller) {
            // Animator can be null when it is cancelled before onReady() completes.
            if (mAnimator != null) {
                mAnimator.cancel();
            }
            if (DEBUG) {
                Log.d(TAG, "InternalAnimationControlListener onCancelled types:"
                        + Type.toString(mRequestedTypes));
            }
            if (mLoggingListener != null) {
                mLoggingListener.onCancelled(controller);
            }
        }

        @Override
        @NonNull
        public Interpolator getInsetsInterpolator(boolean hasZeroInsetsIme) {
            if ((mRequestedTypes & ime()) != 0) {
                if (mHasAnimationCallbacks && !hasZeroInsetsIme) {
                    return SYNC_IME_INTERPOLATOR;
                } else if (mShow) {
                    return LINEAR_OUT_SLOW_IN_INTERPOLATOR;
                } else {
                    return FAST_OUT_LINEAR_IN_INTERPOLATOR;
                }
            } else {
                if (mBehavior == BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) {
                    return SYSTEM_BARS_INSETS_INTERPOLATOR;
                } else {
                    // Makes insets stay at the shown position.
                    return input -> mShow ? 1f : 0f;
                }
            }
        }

        @NonNull
        Interpolator getAlphaInterpolator() {
            if ((mRequestedTypes & ime()) != 0) {
                if ((mHasAnimationCallbacks
                        || com.android.window.flags.Flags.syncedInsetsAnimation())
                        && !mController.hasZeroInsetsIme()) {
                    return input -> 1f;
                } else if (mShow) {
                    // Alpha animation takes half the time with linear interpolation;
                    return input -> Math.min(1f, 2 * input);
                } else {
                    return FAST_OUT_LINEAR_IN_INTERPOLATOR;
                }
            } else {
                if (mBehavior == BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) {
                    return input -> 1f;
                } else {
                    if (mShow) {
                        return SYSTEM_BARS_ALPHA_INTERPOLATOR;
                    } else {
                        return SYSTEM_BARS_DIM_INTERPOLATOR;
                    }
                }
            }
        }

        void onAnimationFinish() {
            mController.finish(mShow);
            ProtoLog.d(INSETS_CONTROLLER_DEBUG, "onAnimationFinish showOnFinish: %b", mShow);
        }

        @Override
        public long getDurationMs(boolean hasZeroInsetsIme) {
            if ((mRequestedTypes & ime()) != 0) {
                if (mHasAnimationCallbacks && !hasZeroInsetsIme) {
                    return ANIMATION_DURATION_SYNC_IME_MS;
                } else {
                    return ANIMATION_DURATION_UNSYNC_IME_MS;
                }
            } else {
                if (mBehavior == BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) {
                    return mShow ? ANIMATION_DURATION_MOVE_IN_MS : ANIMATION_DURATION_MOVE_OUT_MS;
                } else {
                    return mShow ? ANIMATION_DURATION_FADE_IN_MS : ANIMATION_DURATION_FADE_OUT_MS;
                }
            }
        }

        /**
         * Returns the current animation type.
         */
        @AnimationType
        private int getAnimationType() {
            return mShow ? ANIMATION_TYPE_SHOW : ANIMATION_TYPE_HIDE;
        }

        public boolean hasAnimationCallbacks() {
            return mHasAnimationCallbacks;
        }
    }

    /**
     * Represents a running animation
     */
    private static final class RunningAnimation {

        @NonNull
        final InsetsAnimationControlRunner mRunner;
        @AnimationType
        final int mType;

        /**
         * Whether {@link WindowInsetsAnimation.Callback#onStart(WindowInsetsAnimation, Bounds)} has
         * been dispatched already for this animation.
         */
        boolean mStartDispatched;

        RunningAnimation(@NonNull InsetsAnimationControlRunner runner, @AnimationType int type) {
            mRunner = runner;
            this.mType = type;
        }
    }

    /**
     * Represents a control request that we had to defer because we are waiting for the IME to
     * process our show request.
     */
    private static final class PendingControlRequest {

        @InsetsType
        int mTypes;
        @NonNull
        final WindowInsetsAnimationControlListener mListener;
        @NonNull
        final InsetsAnimationSpec mInsetsAnimationSpec;
        @AnimationType
        final int mAnimationType;
        @Nullable
        final CancellationSignal mCancellationSignal;

        PendingControlRequest(@InsetsType int types,
                @NonNull WindowInsetsAnimationControlListener listener,
                @NonNull InsetsAnimationSpec insetsAnimationSpec, @AnimationType int animationType,
                @Nullable CancellationSignal cancellationSignal) {
            mTypes = types;
            mListener = listener;
            mInsetsAnimationSpec = insetsAnimationSpec;
            mAnimationType = animationType;
            mCancellationSignal = cancellationSignal;
        }
    }

    /** The local state */
    @NonNull
    private final InsetsState mState = new InsetsState();

    /** The state dispatched from server */
    @NonNull
    private final InsetsState mLastDispatchedState = new InsetsState();

    @NonNull
    private final Rect mFrame = new Rect();
    @NonNull
    private final Rect mBounds = new Rect();

    private final SparseArray<InsetsSourceConsumer> mSourceConsumers = new SparseArray<>();
    @NonNull
    private final InsetsSourceConsumer mImeSourceConsumer;
    @NonNull
    private final Host mHost;
    @NonNull
    private final Handler mHandler;

    @NonNull
    private final SparseArray<InsetsSourceControl> mTmpControlArray = new SparseArray<>();
    @NonNull
    private final ArrayList<RunningAnimation> mRunningAnimations = new ArrayList<>();

    private boolean mAnimCallbackScheduled;

    @NonNull
    private final Runnable mAnimCallback;

    /** Pending control request that is waiting on IME to be ready to be shown */
    @Nullable
    private PendingControlRequest mPendingImeControlRequest;

    @LayoutParams.WindowType
    private int mWindowType;
    private int mLastLegacySoftInputMode;
    private int mLastLegacyWindowFlags;
    private int mLastLegacySystemUiFlags;
    private boolean mLastScreenRound;
    @WindowConfiguration.ActivityType
    private int mLastActivityType;
    private boolean mStartingAnimation;
    private int mImeCaptionBarInsetsHeight = 0;
    private boolean mAnimationsDisabled;
    private boolean mCompatSysUiVisibilityStaled;
    @Appearance
    private int mAppearanceControlled;
    @Appearance
    private int mAppearanceFromResource;
    private boolean mBehaviorControlled;
    private boolean mIsPredictiveBackImeHideAnimInProgress;

    @NonNull
    private final Runnable mPendingControlTimeout = this::abortPendingImeControlRequest;
    @NonNull
    private final ArrayList<OnControllableInsetsChangedListener> mControllableInsetsChangedListeners
            = new ArrayList<>();

    /** Set of inset types for which an animation was started since last resetting this field */
    @InsetsType
    private int mLastStartedAnimTypes;

    /** Set of inset types which are existing */
    @InsetsType
    private int mExistingTypes = 0;

    /** Set of inset types which are visible */
    @InsetsType
    private int mVisibleTypes = WindowInsets.Type.defaultVisible();

    /** Set of inset types which are requested visible */
    @InsetsType
    private int mRequestedVisibleTypes = WindowInsets.Type.defaultVisible();

    /** Set of inset types which are requested visible which are reported to the host */
    @InsetsType
    private int mReportedRequestedVisibleTypes = WindowInsets.Type.defaultVisible();

    /** Set of insets types which are currently animating */
    @InsetsType
    private int mAnimatingTypes = 0;

    /** Set of inset types that we have controls of */
    @InsetsType
    private int mControllableTypes;

    /**
     * Set of inset types that are about to be cancelled.
     * Used in {@link InsetsSourceConsumer#onAnimationStateChanged}
     */
    @InsetsType
    private int mCancelledForNewAnimationTypes;

    @NonNull
    private final InsetsState.OnTraverseCallbacks mRemoveGoneSources =
            new InsetsState.OnTraverseCallbacks() {

                @NonNull
                private final IntArray mPendingRemoveIndexes = new IntArray();

                @Override
                public void onIdNotFoundInState2(int index1, @NonNull InsetsSource source1) {
                    if (source1.getId() == ID_IME_CAPTION_BAR) {
                        return;
                    }

                    // Don't change the indexes of the sources while traversing. Remove it later.
                    mPendingRemoveIndexes.add(index1);
                }

                @Override
                public void onFinish(@NonNull InsetsState state1, @NonNull InsetsState state2) {
                    for (int i = mPendingRemoveIndexes.size() - 1; i >= 0; i--) {
                        state1.removeSourceAt(mPendingRemoveIndexes.get(i));
                    }
                    mPendingRemoveIndexes.clear();
                }
            };

    @NonNull
    private final InsetsState.OnTraverseCallbacks mStartResizingAnimationIfNeeded =
            new InsetsState.OnTraverseCallbacks() {

                @InsetsType
                private int mTypes;
                @Nullable
                private InsetsState mFromState;
                @Nullable
                private InsetsState mToState;

                @Override
                public void onStart(@NonNull InsetsState state1, @NonNull InsetsState state2) {
                    mTypes = 0;
                    mFromState = null;
                    mToState = null;
                }

                @Override
                public void onIdMatch(@NonNull InsetsSource source1,
                        @NonNull InsetsSource source2) {
                    final Rect frame1 = source1.getFrame();
                    final Rect frame2 = source2.getFrame();
                    if (!source1.hasFlags(InsetsSource.FLAG_ANIMATE_RESIZING)
                            || !source2.hasFlags(InsetsSource.FLAG_ANIMATE_RESIZING)
                            || !source1.isVisible() || !source2.isVisible()
                            || frame1.equals(frame2) || frame1.isEmpty() || frame2.isEmpty()
                            || !(Rect.intersects(mFrame, source1.getFrame())
                                    || Rect.intersects(mFrame, source2.getFrame()))) {
                        return;
                    }
                    mTypes |= source1.getType();
                    if (mFromState == null) {
                        mFromState = new InsetsState();
                    }
                    if (mToState == null) {
                        mToState = new InsetsState();
                    }
                    mFromState.addSource(new InsetsSource(source1));
                    mToState.addSource(new InsetsSource(source2));
                }

                @Override
                public void onFinish(@NonNull InsetsState state1, @NonNull InsetsState state2) {
                    if (mTypes == 0) {
                        return;
                    }
                    cancelExistingControllers(mTypes);
                    final InsetsAnimationControlRunner runner = new InsetsResizeAnimationRunner(
                            mFrame, mBounds, mFromState, mToState, RESIZE_INTERPOLATOR,
                            ANIMATION_DURATION_RESIZE, mTypes, InsetsController.this);
                    if (mRunningAnimations.isEmpty()) {
                        mHost.updateAnimatingTypes(runner.getTypes(),
                                runner.getAnimationType() == ANIMATION_TYPE_HIDE
                                        ? runner.getStatsToken() : null);
                    }
                    mRunningAnimations.add(new RunningAnimation(runner, runner.getAnimationType()));
                    mAnimatingTypes |= runner.getTypes();
                }
            };

    public InsetsController(@NonNull Host host) {
        this(host, host.getHandler());
    }

    @VisibleForTesting
    public InsetsController(@NonNull Host host, @NonNull Handler handler) {
        mHost = host;
        mHandler = handler;
        mAnimCallback = () -> {
            mAnimCallbackScheduled = false;
            if (mRunningAnimations.isEmpty()) {
                return;
            }

            final var runningAnimations = new ArrayList<WindowInsetsAnimation>();
            final var finishedRunners = new ArrayList<InsetsAnimationControlRunner>();
            final var state = new InsetsState(mState, true /* copySources */);
            boolean hasUserAnimation = false;
            boolean hasResizeAnimation = false;
            boolean hasAnimationCallback = false;
            @InsetsType int hidingTypes = 0;
            for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
                RunningAnimation runningAnimation = mRunningAnimations.get(i);
                if (DEBUG) {
                    Log.d(TAG, "Running animation type: " + runningAnimation.mType);
                }
                final InsetsAnimationControlRunner runner = runningAnimation.mRunner;
                if (com.android.window.flags.Flags.syncedInsetsAnimation()
                        && runningAnimation.mType == ANIMATION_TYPE_HIDE) {
                    hidingTypes |= runner.getControllingTypes();
                }
                hasUserAnimation |= runner.getAnimationType() == ANIMATION_TYPE_USER;
                hasResizeAnimation |= runner.getAnimationType() == ANIMATION_TYPE_RESIZE;
                hasAnimationCallback |= runner.hasAnimationCallback();
                if (runner instanceof WindowInsetsAnimationController) {

                    // Keep track of running animation to be dispatched. Aggregate it here such that
                    // if it gets finished within applyChangeInsets we still dispatch it to
                    // onProgress.
                    if (runningAnimation.mStartDispatched) {
                        runningAnimations.add(runner.getAnimation());
                    }

                    if (((InternalInsetsAnimationController) runner).applyChangeInsets(state)) {
                        finishedRunners.add(runner);
                    }
                }
            }

            final WindowInsets insets = state.calculateInsets(mFrame,
                    mBounds, mState /* ignoringVisibilityState */, mLastScreenRound,
                    mLastLegacySoftInputMode, mLastLegacyWindowFlags, mLastLegacySystemUiFlags,
                    mWindowType, mLastActivityType, null /* idSideMap */);
            mHost.dispatchWindowInsetsAnimationProgress(insets, state,
                    Collections.unmodifiableList(runningAnimations), hasUserAnimation,
                    hasResizeAnimation, hasAnimationCallback, hidingTypes);
            if (DEBUG) {
                for (WindowInsetsAnimation anim : runningAnimations) {
                    Log.d(TAG, String.format("Running animation on insets type: %s, progress: %f",
                            Type.toString(anim.getTypeMask()), anim.getInterpolatedFraction()));
                }
            }

            for (int i = finishedRunners.size() - 1; i >= 0; i--) {
                final var runner = finishedRunners.get(i);
                dispatchAnimationEnd(
                        runner.getAnimation(),
                        runner.getAnimationType() == ANIMATION_TYPE_USER,
                        runner.getAnimationType() == ANIMATION_TYPE_RESIZE,
                        runner.hasAnimationCallback());
            }
        };

        // Make mImeSourceConsumer always non-null.
        mImeSourceConsumer = getSourceConsumer(ID_IME, ime());
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void onFrameChanged(Rect frame) {
        if (mFrame.equals(frame)) {
            return;
        }
        if (mImeCaptionBarInsetsHeight != 0) {
            setImeCaptionBarInsetsHeight(mImeCaptionBarInsetsHeight);
        }
        mHost.notifyInsetsChanged();
        mFrame.set(frame);
    }

    public void onBoundsChanged(@NonNull Rect bounds) {
        if (mBounds.equals(bounds)) {
            return;
        }
        mBounds.set(bounds);
        mHost.notifyInsetsChanged();
    }

    @Override
    @NonNull
    public InsetsState getState() {
        return mState;
    }

    @Override
    @InsetsType
    public int getRequestedVisibleTypes() {
        return mRequestedVisibleTypes;
    }

    @NonNull
    public InsetsState getLastDispatchedState() {
        return mLastDispatchedState;
    }

    public boolean onStateChanged(@NonNull InsetsState state) {
        if (mState.equals(state) && mLastDispatchedState.equals(state)) {
            return false;
        }
        if (DEBUG) {
            Log.d(TAG, "onStateChanged: " + state);
        }

        final InsetsState lastState = new InsetsState(mState, true /* copySources */);
        updateState(state);
        applyLocalVisibilityOverride();
        updateCompatSysUiVisibility();

        if (!mState.equals(lastState, false /* excludesCaptionBar */,
                true /* excludesInvisibleIme */, true /* excludesInvalidSource */)) {
            if (DEBUG) {
                Log.d(TAG, "onStateChanged, notifyInsetsChanged");
            }
            mHost.notifyInsetsChanged();
            if (mLastDispatchedState.getDisplayFrame().equals(state.getDisplayFrame())) {
                // Here compares the raw states instead of the overridden ones because we don't want
                // to animate an insets source that its mServerVisible is false.
                InsetsState.traverse(mLastDispatchedState, state, mStartResizingAnimationIfNeeded);
            }
        }
        mLastDispatchedState.set(state, true /* copySources */);
        return true;
    }

    private void updateState(@NonNull InsetsState newState) {
        mState.set(newState, 0 /* types */);
        @InsetsType int existingTypes = 0;
        @InsetsType int visibleTypes = 0;
        for (int i = 0, size = newState.sourceSize(); i < size; i++) {
            final InsetsSource source = new InsetsSource(newState.sourceAt(i));
            @InsetsType int type = source.getType();
            @AnimationType int animationType = getAnimationType(type);
            final InsetsSourceConsumer consumer = mSourceConsumers.get(source.getId());
            if (consumer != null) {
                consumer.updateSource(source, animationType);
            } else {
                mState.addSource(source);
            }
            existingTypes |= type;
            if (source.isVisible()) {
                visibleTypes |= type;
            }
        }

        // If a type doesn't have a source, treat it as visible if it is visible by default.
        visibleTypes |= WindowInsets.Type.defaultVisible() & ~existingTypes;

        if (mVisibleTypes != visibleTypes) {
            if (WindowInsets.Type.hasCompatSystemBars(mVisibleTypes ^ visibleTypes)) {
                mCompatSysUiVisibilityStaled = true;
            }
            mVisibleTypes = visibleTypes;
        }
        if (mExistingTypes != existingTypes) {
            if (WindowInsets.Type.hasCompatSystemBars(mExistingTypes ^ existingTypes)) {
                mCompatSysUiVisibilityStaled = true;
            }
            mExistingTypes = existingTypes;
        }
        InsetsState.traverse(mState, newState, mRemoveGoneSources);
    }

    /**
     * @see InsetsState#calculateInsets(Rect, Rect, InsetsState, boolean, int, int, int, int, int,
     * android.util.SparseIntArray)
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    @NonNull
    public WindowInsets calculateInsets(boolean isScreenRound,
            @LayoutParams.WindowType int windowType,
            @WindowConfiguration.ActivityType int activityType,
            int legacySoftInputMode, int legacyWindowFlags, int legacySystemUiFlags) {
        mWindowType = windowType;
        mLastActivityType = activityType;
        mLastLegacySoftInputMode = legacySoftInputMode;
        mLastLegacyWindowFlags = legacyWindowFlags;
        mLastLegacySystemUiFlags = legacySystemUiFlags;
        mLastScreenRound = isScreenRound;
        return mState.calculateInsets(mFrame, mBounds, null /* ignoringVisibilityState */,
                isScreenRound, legacySoftInputMode, legacyWindowFlags,
                legacySystemUiFlags, windowType, activityType, null /* idSideMap */);
    }

    /**
     * @see InsetsState#calculateVisibleInsets(Rect, Rect, int, int, int, int, int)
     */
    public Insets calculateVisibleInsets(@NonNull InsetsState state,
            @LayoutParams.WindowType int windowType,
            @WindowConfiguration.ActivityType int activityType,
            @SoftInputModeFlags int softInputMode, @LayoutParams.Flags int windowFlags,
            @InsetsType int ignoringTypes) {
        return state.calculateVisibleInsets(mFrame, mBounds, windowType, activityType,
                softInputMode, windowFlags, ignoringTypes);
    }

    /**
     * Called when the server has dispatched us a new set of inset controls.
     */
    public void onControlsChanged(@Nullable InsetsSourceControl[] activeControls) {
        if (activeControls != null) {
            for (InsetsSourceControl activeControl : activeControls) {
                if (activeControl != null) {
                    // TODO(b/122982984): Figure out why it can be null.
                    mTmpControlArray.put(activeControl.getId(), activeControl);
                }
            }
        }

        if (mTmpControlArray.size() > 0) {
            // Update surface positions for animations.
            for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
                mRunningAnimations.get(i).mRunner.updateSurfacePosition(mTmpControlArray);
            }
        }

        @InsetsType
        int controllableTypes = 0;
        int consumedControlCount = 0;
        @InsetsType
        final int[] showTypes = new int[1];
        @InsetsType
        final int[] hideTypes = new int[1];
        @InsetsType
        final int[] cancelTypes = new int[1];
        @InsetsType
        final int[] transientTypes = new int[1];
        ImeTracker.Token statsToken = null;

        // Ensure to update all existing source consumers
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            if (consumer.getId() == ID_IME_CAPTION_BAR) {
                // The inset control for the IME caption bar will never be dispatched
                // by the server.
                continue;
            }

            final InsetsSourceControl control = mTmpControlArray.get(consumer.getId());
            if (control != null) {
                controllableTypes |= control.getType();
                consumedControlCount++;

                if (control.getId() == ID_IME) {
                    statsToken = control.getImeStatsToken();
                }
            }

            // control may be null, but we still need to update the control to null if it got
            // revoked.
            consumer.setControl(control, showTypes, hideTypes, cancelTypes, transientTypes);
        }

        // Ensure to create source consumers if not available yet.
        if (consumedControlCount != mTmpControlArray.size()) {
            for (int i = mTmpControlArray.size() - 1; i >= 0; i--) {
                final InsetsSourceControl control = mTmpControlArray.valueAt(i);
                controllableTypes |= control.getType();
                getSourceConsumer(control.getId(), control.getType())
                        .setControl(control, showTypes, hideTypes, cancelTypes, transientTypes);
            }
        }
        mTmpControlArray.clear();

        if (cancelTypes[0] != 0) {
            cancelExistingControllers(cancelTypes[0]);
        }

        // Do not override any animations that the app started in the OnControllableInsetsChanged
        // listeners.
        @InsetsType
        final int animatingTypes = invokeControllableInsetsChangedListeners();
        showTypes[0] &= ~animatingTypes;
        hideTypes[0] &= ~animatingTypes;

        if (mPendingImeControlRequest != null && getImeSourceConsumer().getControl() != null
                && getImeSourceConsumer().getControl().getLeash() != null) {
            handlePendingControlRequest(mPendingImeControlRequest, statsToken);
        } else {
            if (showTypes[0] != 0) {
                if ((showTypes[0] & ime()) != 0) {
                    ImeTracker.forLogging().onProgress(statsToken,
                            ImeTracker.PHASE_CLIENT_ON_CONTROLS_CHANGED);
                }
                applyAnimation(showTypes[0], true /* show */, false /* skipsCallbacks */,
                        statsToken);
            }
            if (hideTypes[0] != 0) {
                if ((hideTypes[0] & ime()) != 0) {
                    ImeTracker.forLogging().onProgress(statsToken,
                            ImeTracker.PHASE_CLIENT_ON_CONTROLS_CHANGED);
                }
                applyAnimation(hideTypes[0], false /* show */,
                        // The animation of hiding transient types shouldn't be detected by the
                        // app. Otherwise, it might be able to react to the callbacks and cause
                        // flickering.
                        (hideTypes[0] & ~transientTypes[0]) == 0 /* skipsCallbacks */,
                        statsToken);
            }
            if ((showTypes[0] & ime()) == 0 && (hideTypes[0] & ime()) == 0) {
                ImeTracker.forLogging().onCancelled(statsToken,
                        ImeTracker.PHASE_CLIENT_ON_CONTROLS_CHANGED);
            }
        }

        if (mControllableTypes != controllableTypes) {
            if (WindowInsets.Type.hasCompatSystemBars(mControllableTypes ^ controllableTypes)) {
                mCompatSysUiVisibilityStaled = true;
            }
            mControllableTypes = controllableTypes;
        }

        // The local visibility override takes into account whether we have control.
        applyLocalVisibilityOverride();

        // InsetsSourceConsumer#setControl might change the requested visibility.
        // TODO(b/353463205) check this: if the requestedVisibleTypes for the IME were already
        //  sent, the request would fail. Therefore, don't send the statsToken here.
        reportRequestedVisibleTypes(null /* statsToken */);
    }

    @VisibleForTesting(visibility = PACKAGE)
    public void setPredictiveBackImeHideAnimInProgress(boolean isInProgress) {
        mIsPredictiveBackImeHideAnimInProgress = isInProgress;
        if (isInProgress) {
            // The InsetsAnimationControlRunner has layoutInsetsDuringAnimation set to SHOWN during
            // predictive back. Let's set it to HIDDEN once the predictive back animation enters the
            // post-commit phase.
            // That prevents flickers in case the animation is cancelled by an incoming show request
            // during the hide animation.
            for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
                final InsetsAnimationControlRunner runner = mRunningAnimations.get(i).mRunner;
                if ((runner.getTypes() & ime()) != 0) {
                    runner.updateLayoutInsetsDuringAnimation(LAYOUT_INSETS_DURING_ANIMATION_HIDDEN);
                    break;
                }
            }
        }
    }

    public boolean isPredictiveBackImeHideAnimInProgress() {
        return mIsPredictiveBackImeHideAnimInProgress;
    }

    @Override
    public void show(@InsetsType int types) {
        show(types, null /* statsToken */);
    }

    public void show(@InsetsType int types, @Nullable ImeTracker.Token statsToken) {
        if ((types & ime()) != 0) {
            ProtoLog.d(IME_INSETS_CONTROLLER, "show(ime())");

            if (statsToken == null) {
                statsToken = ImeTracker.forLogging().onStart(ImeTracker.TYPE_SHOW,
                        ImeTracker.ORIGIN_CLIENT,
                        SoftInputShowHideReason.SHOW_SOFT_INPUT_BY_INSETS_API,
                        mHost.isHandlingPointerEvent() /* fromUser */, UserHandle.myUserId(),
                        mHost.getRootViewContext().getDisplayId());
            }
        }

        Trace.asyncTraceBegin(TRACE_TAG_VIEW, "IC.showRequestFromApi", 0);
        Trace.asyncTraceBegin(TRACE_TAG_VIEW, "IC.showRequestFromApiToImeReady", 0);

        // TODO: Support a ResultReceiver for IME.
        // TODO(b/123718661): Make show() work for multi-session IME.
        @InsetsType
        int typesReady = 0;
        final boolean imeVisible = mState.isSourceOrDefaultVisible(
                mImeSourceConsumer.getId(), ime());
        for (@InsetsType int type : TYPES) {
            if ((types & type) == 0) {
                continue;
            }
            @AnimationType
            final int animationType = getAnimationType(type);
            final boolean requestedVisible = (type & mRequestedVisibleTypes) != 0;
            final boolean isIme = type == ime();
            var alreadyVisible = requestedVisible && (!isIme || imeVisible)
                    && animationType == ANIMATION_TYPE_NONE;
            var alreadyAnimatingShow = animationType == ANIMATION_TYPE_SHOW;
            if (alreadyVisible || alreadyAnimatingShow) {
                // no-op: already shown or animating in (because window visibility is
                // applied before starting animation).
                if (DEBUG) {
                    Log.d(TAG, String.format(
                            "show ignored for type: %s animType: %d requestedVisible: %s",
                            Type.toString(type), animationType, requestedVisible));
                }
                if (isIme) {
                    ImeTracker.forLogging().onCancelled(statsToken,
                            ImeTracker.PHASE_CLIENT_APPLY_ANIMATION);
                }
                continue;
            }
            if (isIme) {
                if (requestedVisible && (mReportedRequestedVisibleTypes & ime()) != 0) {
                    // If the IME is requested, but has been reported to the server already, the
                    // request would not go through. Call into IMM to check whether the session was
                    // reset and in that case, request focus again.
                    if (mHost.getInputMethodManager() != null) {
                        mHost.getInputMethodManager().requestFocusAfterSessionReset();
                    }
                }
                ImeTracker.forLogging().onProgress(
                        statsToken, ImeTracker.PHASE_CLIENT_APPLY_ANIMATION);
            }
            typesReady |= type;
        }
        if (DEBUG) {
            Log.d(TAG, "show typesReady: [" + Type.toString(typesReady) + "]");
        }
        if ((typesReady & ime()) != 0) {
            // TODO(b/353463205) check if this is needed here
            ImeTracker.forLatency().onShown(statsToken, ActivityThread::currentApplication);
        }
        applyAnimation(typesReady, true /* show */, false /* skipsCallbacks */, statsToken);
    }

    /**
     * Handle the {@link #mPendingImeControlRequest} after a controlled show animation was
     * requested and the IME control with leash is not available.
     */
    private void handlePendingControlRequest(@NonNull PendingControlRequest pendingRequest,
            @Nullable ImeTracker.Token statsToken) {
        mPendingImeControlRequest = null;
        mHandler.removeCallbacks(mPendingControlTimeout);

        // We are about to playing the default animation. Passing a null frame indicates the
        // controlled types should be animated regardless of the frame.
        controlAnimationUnchecked(pendingRequest.mTypes, pendingRequest.mCancellationSignal,
                pendingRequest.mListener, null /* frame */, null /* bounds */,
                pendingRequest.mInsetsAnimationSpec, pendingRequest.mAnimationType,
                LAYOUT_INSETS_DURING_ANIMATION_SHOWN, false /* useInsetsAnimationThread */,
                statsToken, false /* fromPredictiveBack */);
    }

    @Override
    public void hide(@InsetsType int types) {
        hide(types, null /* statsToken */);
    }

    public void hide(@InsetsType int types, @Nullable ImeTracker.Token statsToken) {
        if ((types & ime()) != 0) {
            ProtoLog.d(IME_INSETS_CONTROLLER, "hide(ime())");

            if (statsToken == null) {
                statsToken = ImeTracker.forLogging().onStart(ImeTracker.TYPE_HIDE,
                        ImeTracker.ORIGIN_CLIENT,
                        SoftInputShowHideReason.HIDE_SOFT_INPUT_BY_INSETS_API,
                        mHost.isHandlingPointerEvent() /* fromUser */,  UserHandle.myUserId(),
                        mHost.getRootViewContext().getDisplayId());
            }
        }
        Trace.asyncTraceBegin(TRACE_TAG_VIEW, "IC.hideRequestFromApi", 0);

        @InsetsType
        int typesReady = 0;
        for (@InsetsType int type : TYPES) {
            if ((types & type) == 0) {
                continue;
            }
            final boolean isImeAnimation = type == ime();
            @AnimationType
            final int animationType = getAnimationType(type);
            if (isImeAnimation) {
                // When the IME is requested to be hidden, but already hidden, we don't show
                // an animation again (mRequestedVisibleTypes are reported at the end of the IME
                // hide animation but set at the beginning)
                if ((mRequestedVisibleTypes & ime()) == 0 && animationType != ANIMATION_TYPE_USER) {
                    ImeTracker.forLogging().onCancelled(statsToken,
                            ImeTracker.PHASE_CLIENT_ALREADY_HIDDEN);
                    continue;
                }
            }
            final boolean requestedVisible = (type & mRequestedVisibleTypes) != 0;
            if (mPendingImeControlRequest != null && !requestedVisible) {
                // Remove the hide insets type from the pending show request.
                mPendingImeControlRequest.mTypes &= ~type;
                if (mPendingImeControlRequest.mTypes == 0) {
                    abortPendingImeControlRequest();
                }
            }
            if (!requestedVisible && animationType == ANIMATION_TYPE_NONE
                    || animationType == ANIMATION_TYPE_HIDE || (isImeAnimation && animationType
                    == ANIMATION_TYPE_USER && mIsPredictiveBackImeHideAnimInProgress)) {
                // no-op: already hidden or animating out (because window visibility is
                // applied before starting animation).
                if (isImeAnimation) {
                    ImeTracker.forLogging().onCancelled(statsToken,
                            ImeTracker.PHASE_CLIENT_APPLY_ANIMATION);
                }
                continue;
            }
            if (isImeAnimation) {
                ImeTracker.forLogging().onProgress(
                        statsToken, ImeTracker.PHASE_CLIENT_APPLY_ANIMATION);
            }
            typesReady |= type;
        }
        applyAnimation(typesReady, false /* show */, false /* skipsCallbacks */, statsToken);
    }

    @Override
    public void controlWindowInsetsAnimation(@InsetsType int types, long durationMillis,
            @Nullable Interpolator interpolator,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull WindowInsetsAnimationControlListener listener) {
        controlWindowInsetsAnimation(types, cancellationSignal, listener, durationMillis,
                interpolator, ANIMATION_TYPE_USER, false /* fromPredictiveBack */);
    }

    @VisibleForTesting(visibility = PACKAGE)
    public void controlWindowInsetsAnimation(@InsetsType int types,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull WindowInsetsAnimationControlListener listener,
            long durationMs, @Nullable Interpolator interpolator,
            @AnimationType int animationType, boolean fromPredictiveBack) {
        if ((mState.calculateUncontrollableInsetsFromFrame(mFrame, mBounds) & types) != 0
                || (fromPredictiveBack && ((mRequestedVisibleTypes & ime()) == 0))) {
            // abort if insets are uncontrollable or if control request is from predictive back but
            // there is already a hide anim in progress
            listener.onCancelled(null);
            return;
        }

        final var spec = new InsetsAnimationSpec() {
            @Override
            public long getDurationMs(boolean hasZeroInsetsIme) {
                return durationMs;
            }

            @Override
            @Nullable
            public Interpolator getInsetsInterpolator(boolean hasZeroInsetsIme) {
                return interpolator;
            }
        };
        final var statsToken = (types & ime()) == 0
                ? null
                : ImeTracker.forLogging().onStart(ImeTracker.TYPE_USER,
                        ImeTracker.ORIGIN_CLIENT,
                        SoftInputShowHideReason.CONTROL_WINDOW_INSETS_ANIMATION,
                        mHost.isHandlingPointerEvent() /* fromUser */,  UserHandle.myUserId(),
                        mHost.getRootViewContext().getDisplayId()
                );
        controlAnimationUnchecked(types, cancellationSignal, listener, mFrame, mBounds, spec,
                animationType, getLayoutInsetsDuringAnimationMode(types, fromPredictiveBack),
                false /* useInsetsAnimationThread */, statsToken, fromPredictiveBack);
    }

    private void controlAnimationUnchecked(@InsetsType int types,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull WindowInsetsAnimationControlListener listener, @Nullable Rect frame,
            @Nullable Rect bounds, @NonNull InsetsAnimationSpec insetsAnimationSpec,
            @AnimationType int animationType,
            @LayoutInsetsDuringAnimation int layoutInsetsDuringAnimation,
            boolean useInsetsAnimationThread, @Nullable ImeTracker.Token statsToken,
            boolean fromPredictiveBack) {
        final boolean visible = layoutInsetsDuringAnimation == LAYOUT_INSETS_DURING_ANIMATION_SHOWN;

        if (!fromPredictiveBack && !visible && (types & ime()) != 0
                && (mRequestedVisibleTypes & ime()) != 0) {
            // Clear IME back callbacks if a IME hide animation is requested
            mHost.getInputMethodManager().getImeBackCallbackProxy().preliminaryClear();
        }
        // Basically, we accept the requested visibilities from the upstream callers...
        setRequestedVisibleTypes(visible ? types : 0, types);

        // However, we might reject the request in some cases, such as delaying showing IME or
        // rejecting showing IME.
        controlAnimationUncheckedInner(types, cancellationSignal, listener, frame, bounds,
                insetsAnimationSpec, animationType, layoutInsetsDuringAnimation,
                useInsetsAnimationThread, statsToken);

        // We are finishing setting the requested visible types. Report them to the server
        // and/or the app.
        reportRequestedVisibleTypes(statsToken);
    }

    private void controlAnimationUncheckedInner(@InsetsType int types,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull WindowInsetsAnimationControlListener listener, @Nullable Rect frame,
            @Nullable Rect bounds, @NonNull InsetsAnimationSpec insetsAnimationSpec,
            @AnimationType int animationType,
            @LayoutInsetsDuringAnimation int layoutInsetsDuringAnimation,
            boolean useInsetsAnimationThread, @Nullable ImeTracker.Token statsToken) {
        if ((types & mTypesBeingCancelled) != 0) {
            final boolean monitoredAnimation =
                    animationType == ANIMATION_TYPE_SHOW || animationType == ANIMATION_TYPE_HIDE;
            if (monitoredAnimation && (types & ime()) != 0) {
                if (animationType == ANIMATION_TYPE_SHOW) {
                    ImeTracker.forLatency().onShowCancelled(statsToken,
                            ImeTracker.PHASE_CLIENT_ANIMATION_CANCEL,
                            ActivityThread::currentApplication);
                } else {
                    ImeTracker.forLatency().onHideCancelled(statsToken,
                            ImeTracker.PHASE_CLIENT_ANIMATION_CANCEL,
                            ActivityThread::currentApplication);
                }
                ImeTracker.forLogging().onCancelled(statsToken,
                        ImeTracker.PHASE_CLIENT_CONTROL_ANIMATION);
            }
            throw new IllegalStateException("Cannot start a new insets animation of "
                    + Type.toString(types)
                    + " while an existing " + Type.toString(mTypesBeingCancelled)
                    + " is being cancelled.");
        }
        ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_CLIENT_CONTROL_ANIMATION);
        if (types == 0) {
            // nothing to animate.
            listener.onCancelled(null);
            ProtoLog.d(INSETS_CONTROLLER_DEBUG, "no types to animate in controlAnimationUnchecked");
            Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.showRequestFromApi", 0);
            Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.showRequestFromApiToImeReady", 0);
            ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_CLIENT_CONTROL_ANIMATION);
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "controlAnimation types: " + Type.toString(types));
        }
        mLastStartedAnimTypes |= types;

        final var controls = new SparseArray<InsetsSourceControl>();

        // Ime will not be contained in typesReady nor in controls, if we don't have a leash
        final Pair<Integer, Integer> typesReadyPair = collectSourceControls(types, controls);
        @InsetsType
        final int typesReady = typesReadyPair.first;
        if (animationType == ANIMATION_TYPE_USER) {
            @InsetsType
            final int typesWithoutLeash = typesReadyPair.second;
            // When using an app-driven animation, the IME won't have a leash (because the
            // window isn't created yet). If we have a control, but no leash, defers the
            // request until the leash gets created.
            // The mRequestedVisibleTypes were set just before, so we check the currently
            // visible types
            if ((types & ime()) != 0 && (types & typesWithoutLeash) != 0) {
                // If we have control but no leash for any of the controlling sources, we
                // wait until the leashes are ready. Thus, creating a PendingControlRequest
                // is always for showing, not hiding.
                final PendingControlRequest request = new PendingControlRequest(types,
                        listener, insetsAnimationSpec, animationType, cancellationSignal);
                mPendingImeControlRequest = request;
                // only add a timeout when the control is not currently showing
                mHandler.postDelayed(mPendingControlTimeout, PENDING_CONTROL_TIMEOUT_MS);

                ProtoLog.d(INSETS_CONTROLLER_DEBUG,
                        "Ime not ready. Create pending request");
                if (cancellationSignal != null) {
                    cancellationSignal.setOnCancelListener(() -> {
                        if (mPendingImeControlRequest == request) {
                            ProtoLog.d(INSETS_CONTROLLER_DEBUG,
                                    "Cancellation signal abortPendingImeControlRequest");
                            abortPendingImeControlRequest();
                        }
                    });
                }
            }
            // We need to wait until all types are ready
            if (typesReady != types) {
                ProtoLog.d(INSETS_CONTROLLER_DEBUG,
                        "not all types are ready yet, waiting. typesReady=[%s], types=[%s]",
                        Type.toString(typesReady), Type.toString(types));
                return;
            }
        }

        if (typesReady == 0) {
            // if no types are ready, we need to wait for receiving new controls
            if (DEBUG) {
                Log.d(TAG, "No types ready. onCancelled()");
            }
            Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.showRequestFromApi", 0);
            listener.onCancelled(null);
            return;
        }

        mCancelledForNewAnimationTypes = typesReady;
        cancelExistingControllers(typesReady);
        mCancelledForNewAnimationTypes = 0;

        final InsetsAnimationControlRunner runner = useInsetsAnimationThread
                ? new InsetsAnimationThreadControlRunner(controls,
                        frame, bounds, mState, listener, typesReady, this,
                        insetsAnimationSpec, animationType, layoutInsetsDuringAnimation,
                        mHost.getTranslator(), mHost.getHandler(), statsToken)
                : new InsetsAnimationControlImpl(controls,
                        frame, bounds, mState, listener, typesReady, this, this,
                        insetsAnimationSpec, animationType, layoutInsetsDuringAnimation,
                        mHost.getTranslator(), statsToken);
        for (int i = controls.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer consumer = mSourceConsumers.get(controls.keyAt(i));
            if (consumer != null) {
                consumer.setSurfaceParamsApplier(runner.getSurfaceParamsApplier());
            }
        }
        if ((typesReady & ime()) != 0) {
            ImeTracing.getInstance().triggerClientDump("InsetsAnimationControlImpl",
                    mHost.getInputMethodManager(), null /* icProto */);
            if (animationType == ANIMATION_TYPE_HIDE) {
                ImeTracker.forLatency().onHidden(statsToken, ActivityThread::currentApplication);
            }
        }
        ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_CLIENT_ANIMATION_RUNNING);
        mAnimatingTypes |= runner.getTypes();
        mHost.updateAnimatingTypes(mAnimatingTypes, null /* statsToken */);
        mRunningAnimations.add(new RunningAnimation(runner, animationType));
        ProtoLog.d(INSETS_CONTROLLER_DEBUG,
                "Animation added to runner. useInsetsAnimationThread: %b",
                useInsetsAnimationThread);
        if (cancellationSignal != null) {
            cancellationSignal.setOnCancelListener(() ->
                    cancelAnimation(runner, true /* invokeCallback */));
        } else {
            Trace.asyncTraceBegin(TRACE_TAG_VIEW, "IC.pendingAnim", 0);
        }

        onAnimationStateChanged(types, true /* running */);

        if (animationType == ANIMATION_TYPE_HIDE) {
            Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.hideRequestFromApi", 0);
        }
    }

    static void releaseControls(@NonNull SparseArray<InsetsSourceControl> controls) {
        for (int i = controls.size() - 1; i >= 0; i--) {
            controls.valueAt(i).release(SurfaceControl::release);
        }
    }

    // TODO(b/242962223): Make this setter restrictive.
    @Override
    public void setSystemDrivenInsetsAnimationLoggingListener(
            @Nullable WindowInsetsAnimationControlListener listener) {
        mLoggingListener = listener;
    }

    /**
     * @return Pair of (types ready to animate, types that we have control for, but no leash).
     */
    @NonNull
    private Pair<Integer, Integer> collectSourceControls(@InsetsType int types,
            @NonNull SparseArray<InsetsSourceControl> controls) {
        @InsetsType
        int typesReady = 0;
        @InsetsType
        int typesWithoutLeash = 0;

        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            if ((consumer.getType() & types) == 0) {
                continue;
            }

            final InsetsSourceControl control = consumer.getControl();
            if (control != null) {
                if (control.getLeash() != null || control.getId() == ID_IME_CAPTION_BAR) {
                    controls.put(control.getId(), new InsetsSourceControl(control));
                    typesReady |= consumer.getType();
                } else {
                    typesWithoutLeash |= consumer.getType();
                }
            }
        }
        return new Pair<>(typesReady, typesWithoutLeash);
    }

    @LayoutInsetsDuringAnimation
    private int getLayoutInsetsDuringAnimationMode(@InsetsType int types,
            boolean fromPredictiveBack) {
        if (fromPredictiveBack && !mHost.hasAnimationCallbacks()) {
            // When insets are animated by predictive back and the app does not have an animation
            // callback, we want insets to be shown to prevent a jump cut from shown to hidden at
            // the start of the predictive back animation
            return LAYOUT_INSETS_DURING_ANIMATION_SHOWN;
        }
        // Generally, we want to layout the opposite of the current state. This is to make animation
        // callbacks easy to use: The can capture the layout values and then treat that as end-state
        // during the animation.
        //
        // However, if controlling multiple sources, we want to treat it as shown if any of the
        // types is currently hidden.
        return (mRequestedVisibleTypes & types) != types
                ? LAYOUT_INSETS_DURING_ANIMATION_SHOWN
                : LAYOUT_INSETS_DURING_ANIMATION_HIDDEN;
    }

    private void cancelExistingControllers(@InsetsType int types) {
        final int originalTypesBeingCancelled = mTypesBeingCancelled;
        mTypesBeingCancelled |= types;
        try {
            for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
                final InsetsAnimationControlRunner runner = mRunningAnimations.get(i).mRunner;
                if ((runner.getTypes() & types) != 0) {
                    cancelAnimation(runner, true /* invokeCallback */);
                }
            }
            if ((types & ime()) != 0) {
                abortPendingImeControlRequest();
            }
        } finally {
            mTypesBeingCancelled = originalTypesBeingCancelled;
        }
    }

    private void abortPendingImeControlRequest() {
        if (mPendingImeControlRequest != null) {
            mPendingImeControlRequest.mListener.onCancelled(null);
            mPendingImeControlRequest = null;
            mHandler.removeCallbacks(mPendingControlTimeout);
            ProtoLog.d(INSETS_CONTROLLER_DEBUG, "abortPendingImeControlRequest");
        }
    }

    @VisibleForTesting
    @Override
    public void notifyFinished(@NonNull InsetsAnimationControlRunner runner, boolean shown) {
        setRequestedVisibleTypes(shown ? runner.getTypes() : 0, runner.getTypes());
        if (runner.getAnimationType() != ANIMATION_TYPE_RESIZE) {
            // We update and send the requestedVisibleTypes first to the server to avoid an
            // unwanted show/hide request.
            // This could happen if a controlled (hide) animation is finished, but the IME is
            // still shown: In this case, it is crucial that the server knows about the IME's
            // requested visibility to avoid flickering (by first hiding due to being not
            // requested and not animating, then showing because it becomes requested again).
            reportRequestedVisibleTypes(null /* statsToken */);
        }
        cancelAnimation(runner, false /* invokeCallback */);
        ProtoLog.d(INSETS_CONTROLLER_DEBUG, "notifyFinished. shown: %b", shown);
        if (runner.getAnimationType() == ANIMATION_TYPE_RESIZE) {
            // The resize animation doesn't show or hide the insets. We shouldn't change the
            // requested visibility.
            return;
        }
        final ImeTracker.Token statsToken = runner.getStatsToken();
        if (runner.getAnimationType() == ANIMATION_TYPE_USER) {
            ImeTracker.forLogging().onUserFinished(statsToken, shown);
        } else if (shown) {
            ImeTracker.forLogging().onProgress(statsToken,
                    ImeTracker.PHASE_CLIENT_ANIMATION_FINISHED_SHOW);
            ImeTracker.forLogging().onShown(statsToken);
        }
    }

    @Override
    public void applySurfaceParams(
            @NonNull SyncRtSurfaceTransactionApplier.SurfaceParams... params) {
        mHost.applySurfaceParams(params);
    }

    void notifyControlRevoked(@NonNull InsetsSourceConsumer consumer) {
        @InsetsType
        final int type = consumer.getType();
        for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
            final InsetsAnimationControlRunner runner = mRunningAnimations.get(i).mRunner;
            runner.notifyControlRevoked(type);
            if (runner.getControllingTypes() == 0) {
                cancelAnimation(runner, true /* invokeCallback */);
            }
        }
        if (type != ime()) {
            // IME consumer should always be there since we need to communicate with
            // InputMethodManager no matter if we have the control or not.
            mSourceConsumers.remove(consumer.getId());
        } else {
            abortPendingImeControlRequest();
        }
    }

    private void cancelAnimation(@NonNull InsetsAnimationControlRunner runner,
            boolean invokeCallback) {
        if (invokeCallback) {
            ImeTracker.forLogging().onCancelled(runner.getStatsToken(),
                    ImeTracker.PHASE_CLIENT_ANIMATION_CANCEL);
            runner.cancel();
        } else {
            // Succeeds if invokeCallback is false (i.e. when called from notifyFinished).
            ImeTracker.forLogging().onProgress(runner.getStatsToken(),
                    ImeTracker.PHASE_CLIENT_ANIMATION_CANCEL);
        }
        ProtoLog.d(INSETS_CONTROLLER_DEBUG,
                "cancelAnimation of types: %s, animType: %d, host: %s",
                Type.toString(runner.getTypes()), runner.getAnimationType(),
                mHost.getRootViewTitle());
        @InsetsType
        int removedTypes = 0;
        for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
            RunningAnimation runningAnimation = mRunningAnimations.get(i);
            if (runningAnimation.mRunner == runner) {
                mRunningAnimations.remove(i);
                removedTypes = runner.getTypes();
                if (invokeCallback) {
                    dispatchAnimationEnd(runner.getAnimation(),
                            runner.getAnimationType() == ANIMATION_TYPE_USER,
                            runner.getAnimationType() == ANIMATION_TYPE_RESIZE,
                            runner.hasAnimationCallback());
                } else {
                    if ((removedTypes & ime()) != 0
                            && runner.getAnimationType() == ANIMATION_TYPE_HIDE) {
                        // if the (hide) animation is cancelled, the requestedVisibleTypes
                        // should be reported at this point.
                        reportRequestedVisibleTypes(null /* statsToken */);
                        mHost.getInputMethodManager()
                                .removeImeSurfaceFromWindow(mHost.getWindowToken());
                    }
                }
                break;
            }
        }
        if (removedTypes > 0) {
            mAnimatingTypes &= ~removedTypes;
            final boolean dispatchStatsToken =
                    (removedTypes & ime()) != 0 && runner.getAnimationType() == ANIMATION_TYPE_HIDE;
            mHost.updateAnimatingTypes(mAnimatingTypes,
                    dispatchStatsToken ? runner.getStatsToken() : null);
        }

        onAnimationStateChanged(removedTypes, false /* running */);
    }

    void onAnimationStateChanged(@InsetsType int types, boolean running) {
        boolean insetsChanged = false;
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            if ((consumer.getType() & types) != 0) {
                insetsChanged |= consumer.onAnimationStateChanged(running);
            }
        }
        if (insetsChanged) {
            notifyVisibilityChanged();
        }
    }

    private void applyLocalVisibilityOverride() {
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            consumer.applyLocalVisibilityOverride();
        }
    }

    @InsetsType
    int getCancelledForNewAnimationTypes() {
        return mCancelledForNewAnimationTypes;
    }

    @VisibleForTesting
    @NonNull
    public final InsetsSourceConsumer getSourceConsumer(int id, @InsetsType int type) {
        InsetsSourceConsumer consumer = mSourceConsumers.get(id);
        if (consumer != null) {
            return consumer;
        }
        consumer = new InsetsSourceConsumer(id, type, mState, this);
        mSourceConsumers.put(id, consumer);
        return consumer;
    }

    @VisibleForTesting
    @NonNull
    public InsetsSourceConsumer getImeSourceConsumer() {
        return mImeSourceConsumer;
    }

    void notifyVisibilityChanged() {
        mHost.notifyInsetsChanged();
    }

    /**
     * @see ViewRootImpl#updateCompatSysUiVisibility(int, int, int)
     */
    public void updateCompatSysUiVisibility() {
        if (mCompatSysUiVisibilityStaled) {
            mCompatSysUiVisibilityStaled = false;
            mHost.updateCompatSysUiVisibility(
                    // Treat non-existing types as controllable types for compatibility.
                    mVisibleTypes, mRequestedVisibleTypes, mControllableTypes | ~mExistingTypes);
        }
    }

    /**
     * Called when current window gains focus.
     */
    public void onWindowFocusGained(boolean hasViewFocused) {
        mImeSourceConsumer.onWindowFocusGained(hasViewFocused);
    }

    /**
     * Called when current window loses focus.
     */
    public void onWindowFocusLost() {
        mImeSourceConsumer.onWindowFocusLost();
    }

    /** Returns the current {@link AnimationType} of an {@link InsetsType}. */
    @VisibleForTesting(visibility = PACKAGE)
    @AnimationType
    public int getAnimationType(@InsetsType int type) {
        for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
            final InsetsAnimationControlRunner runner = mRunningAnimations.get(i).mRunner;
            if (runner.controlsType(type)) {
                return mRunningAnimations.get(i).mType;
            }
        }
        return ANIMATION_TYPE_NONE;
    }

    /**
     * Returns {@code true} if there is an animation which controls the given {@link InsetsType} and
     * the runner is still playing the surface animation.
     *
     * @see InsetsAnimationControlRunner#willUpdateSurface()
     */
    boolean hasSurfaceAnimation(@InsetsType int type) {
        for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
            final InsetsAnimationControlRunner runner = mRunningAnimations.get(i).mRunner;
            if (runner.controlsType(type) && runner.willUpdateSurface()) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting(visibility = PACKAGE)
    public void setRequestedVisibleTypes(@InsetsType int visibleTypes, @InsetsType int mask) {
        @InsetsType
        final int requestedVisibleTypes = (mRequestedVisibleTypes & ~mask) | (visibleTypes & mask);
        if (mRequestedVisibleTypes != requestedVisibleTypes) {
            if ((mRequestedVisibleTypes & ime()) == 0 && (requestedVisibleTypes & ime()) != 0) {
                // In case the IME back callbacks have been preliminarily cleared before, let's
                // reregister them. This can happen if an IME hide animation was interrupted and the
                // IME is requested to be shown again.
                getHost().getInputMethodManager().getImeBackCallbackProxy()
                        .undoPreliminaryClear();
            }
            ProtoLog.d(IME_INSETS_CONTROLLER, "Setting requestedVisibleTypes to %d (was %d)",
                    requestedVisibleTypes, mRequestedVisibleTypes);
            mRequestedVisibleTypes = requestedVisibleTypes;
        }
    }

    /**
     * @return Types of currently running animations that are controlled by the user.
     */
    @InsetsType
    public int computeUserAnimatingTypes() {
        @InsetsType
        int animatingTypes = 0;
        for (int i = 0; i < mRunningAnimations.size(); i++) {
            if (mRunningAnimations.get(i).mRunner.getAnimationType() == ANIMATION_TYPE_USER) {
                animatingTypes |= mRunningAnimations.get(i).mRunner.getTypes();
            }
        }
        return animatingTypes;
    }

    /**
     * Called when finishing setting requested visible types or finishing setting controls.
     *
     * @param statsToken the token tracking the current IME request or {@code null} otherwise.
     */
    private void reportRequestedVisibleTypes(@Nullable ImeTracker.Token statsToken) {
        // If the IME is currently animating out, it is still visible, therefore we only
        // report its requested visibility at the end of the animation, otherwise we would
        // lose the leash, and it would disappear during the animation
        // TODO(b/326377046) revisit this part and see if we can make it more general
        if (mRequestedVisibleTypes != mReportedRequestedVisibleTypes) {
            @InsetsType
            final int diff = mRequestedVisibleTypes ^ mReportedRequestedVisibleTypes;
            if (WindowInsets.Type.hasCompatSystemBars(diff)) {
                mCompatSysUiVisibilityStaled = true;
            }
            ImeTracker.forLogging().onProgress(statsToken,
                    ImeTracker.PHASE_CLIENT_REPORT_REQUESTED_VISIBLE_TYPES);
            if ((mRequestedVisibleTypes & ime()) == 0) {
                // The IME hide animating flow should not be followed from here, but after
                // the hide animation has finished and Host.updateAnimatingTypes is called.
                statsToken = null;
            }
            mReportedRequestedVisibleTypes = mRequestedVisibleTypes;
            mHost.updateRequestedVisibleTypes(mReportedRequestedVisibleTypes, statsToken);
        } else {
            if ((mRequestedVisibleTypes & ime()) != 0) {
                InsetsSourceControl control = mImeSourceConsumer.getControl();
                if (control == null || control.getLeash() == null) {
                    // If the IME was requested to show twice, and we didn't receive the controls
                    // yet, this request will not continue. It should be cancelled here, as
                    // it would time out otherwise.
                    ImeTracker.forLogging().onCancelled(statsToken,
                            ImeTracker.PHASE_CLIENT_REPORT_REQUESTED_VISIBLE_TYPES);
                }
            }
        }
        updateCompatSysUiVisibility();
    }

    @VisibleForTesting
    public void applyAnimation(@InsetsType final int types, boolean show, boolean skipsCallbacks,
            @Nullable ImeTracker.Token statsToken) {
        // TODO(b/166736352): We should only skip the animation of specific types, not all types.
        boolean skipsAnim = false;
        if (!Flags.unifySkipAnimationOnceWithInitiallyVisible() && (types & ime()) != 0) {
            final InsetsSourceControl imeControl = mImeSourceConsumer.getControl();
            // Skip showing animation once that made by system for some reason.
            // (e.g. starting window with hasImeSurface)
            if (imeControl != null) {
                skipsAnim = imeControl.getAndClearSkipAnimationOnce() && show
                        && mImeSourceConsumer.hasViewFocusWhenWindowFocusGain();
            }
        }
        applyAnimation(types, show, skipsAnim, skipsCallbacks, statsToken);
    }

    @VisibleForTesting
    public void applyAnimation(@InsetsType final int types, boolean show,
            boolean skipsAnim, boolean skipsCallbacks, @Nullable ImeTracker.Token statsToken) {
        if (types == 0) {
            // nothing to animate.
            if (DEBUG) {
                Log.d(TAG, "applyAnimation, nothing to animate. Stopping here");
            }
            Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.showRequestFromApi", 0);
            return;
        }

        final boolean hasAnimationCallbacks = mHost.hasAnimationCallbacks();
        final boolean useInsetsAnimationThread =
                (!hasAnimationCallbacks && (!com.android.window.flags.Flags.syncedInsetsAnimation()
                        || !mHost.allowsSyncedInsetsAnimation())) || skipsCallbacks;

        Handler handler = null;
        if (Flags.fixJankTrackerImeAnimationDelay() && useInsetsAnimationThread) {
            handler = android.view.InsetsAnimationThread.getHandler();
        } else if (mHost.getRootViewContext() != null) {
            handler = mHost.getRootViewContext().getMainThreadHandler();
        }

        final var listener = new InternalAnimationControlListener(
                show, hasAnimationCallbacks, types, mHost.getSystemBarsBehavior(),
                skipsAnim || mAnimationsDisabled, mHost.dipToPx(FLOATING_IME_BOTTOM_INSET_DP),
                mLoggingListener, mJankContext, handler);

        // We are about to playing the default animation (show/hide). Passing a null frame indicates
        // the controlled types should be animated regardless of the frame.
        controlAnimationUnchecked(
                types, null /* cancellationSignal */, listener, null /* frame */, null /* bounds */,
                listener /* insetsAnimationSpec */,
                show ? ANIMATION_TYPE_SHOW : ANIMATION_TYPE_HIDE,
                show ? LAYOUT_INSETS_DURING_ANIMATION_SHOWN : LAYOUT_INSETS_DURING_ANIMATION_HIDDEN,
                useInsetsAnimationThread, statsToken, false /* fromPredictiveBack */);
    }

    /**
     * Cancel on-going animation to show/hide {@link InsetsType}.
     */
    @VisibleForTesting
    public void cancelExistingAnimations() {
        cancelExistingControllers(all());
    }

    void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        final String innerPrefix = prefix + "    ";
        pw.println(prefix + "InsetsController:");
        mState.dump(innerPrefix, pw);
        pw.println(innerPrefix + "mIsPredictiveBackImeHideAnimInProgress="
                + mIsPredictiveBackImeHideAnimInProgress);
    }

    void dumpDebug(@NonNull ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        mState.dumpDebug(proto, STATE);
        for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
            InsetsAnimationControlRunner runner = mRunningAnimations.get(i).mRunner;
            runner.dumpDebug(proto, CONTROL);
        }
        proto.end(token);
    }

    @VisibleForTesting
    @Override
    public <T extends InsetsAnimationControlRunner & InternalInsetsAnimationController>
    void startAnimation(@NonNull T runner, @NonNull WindowInsetsAnimationControlListener listener,
            @InsetsType int types, @NonNull WindowInsetsAnimation animation,
            @NonNull Bounds bounds) {
        mHost.dispatchWindowInsetsAnimationPrepare(animation,
                runner.getAnimationType() == ANIMATION_TYPE_USER,
                runner.getAnimationType() == ANIMATION_TYPE_RESIZE,
                runner.hasAnimationCallback());
        mHost.addOnPreDrawRunnable(() -> {
            if (runner.isCancelled()) {
                if (WARN) {
                    Log.w(TAG, "startAnimation canceled before preDraw");
                }
                return;
            }
            Trace.asyncTraceBegin(TRACE_TAG_VIEW,
                    "InsetsAnimation: " + Type.toString(types), types);
            for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
                RunningAnimation runningAnimation = mRunningAnimations.get(i);
                if (runningAnimation.mRunner == runner) {
                    runningAnimation.mStartDispatched = true;
                }
            }
            Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.pendingAnim", 0);
            mHost.dispatchWindowInsetsAnimationStart(animation, bounds,
                    runner.getAnimationType() == ANIMATION_TYPE_USER,
                    runner.getAnimationType() == ANIMATION_TYPE_RESIZE,
                    runner.hasAnimationCallback());
            mStartingAnimation = true;
            if (runner.getAnimationType() == ANIMATION_TYPE_USER) {
                ImeTracker.forLogging().onDispatched(runner.getStatsToken());
            }
            runner.setReadyDispatched(true);
            listener.onReady(runner, types);
            mStartingAnimation = false;
        });
    }

    private void dispatchAnimationEnd(@NonNull WindowInsetsAnimation animation,
            boolean isUserAnimation, boolean isResizeAnimation, boolean hasAnimationCallback) {
        Trace.asyncTraceEnd(TRACE_TAG_VIEW,
                "InsetsAnimation: " + Type.toString(animation.getTypeMask()),
                animation.getTypeMask());
        mHost.dispatchWindowInsetsAnimationEnd(animation, isUserAnimation, isResizeAnimation,
                hasAnimationCallback);
    }

    @VisibleForTesting
    @Override
    public void scheduleApplyChangeInsets(@NonNull InsetsAnimationControlRunner runner) {
        if (mStartingAnimation || runner.getAnimationType() == ANIMATION_TYPE_USER) {
            mAnimCallback.run();
            mAnimCallbackScheduled = false;
            return;
        }
        if (!mAnimCallbackScheduled) {
            mHost.postInsetsAnimationCallback(mAnimCallback);
            mAnimCallbackScheduled = true;
        }
    }

    @Override
    public void setSystemBarsAppearance(@Appearance int appearance, @Appearance int mask) {
        mAppearanceControlled |= mask;
        mHost.setSystemBarsAppearance(appearance, mask);
    }

    @Override
    public void setSystemBarsAppearanceFromResource(@Appearance int appearance,
            @Appearance int mask) {
        mAppearanceFromResource = (mAppearanceFromResource & ~mask) | (appearance & mask);

        // Don't change the flags which are already controlled by setSystemBarsAppearance.
        mHost.setSystemBarsAppearance(appearance, mask & ~mAppearanceControlled);
    }

    @Override
    @Appearance
    public int getSystemBarsAppearance() {
        // We only return the requested appearance, not the implied one.
        return (mHost.getSystemBarsAppearance() & mAppearanceControlled)
                | (mAppearanceFromResource & ~mAppearanceControlled);
    }

    @Appearance
    public int getAppearanceControlled() {
        return mAppearanceControlled;
    }

    @Override
    public void setImeCaptionBarInsetsHeight(int height) {
        Rect newFrame = new Rect(mFrame.left, mFrame.bottom - height, mFrame.right, mFrame.bottom);
        InsetsSource source = mState.peekSource(ID_IME_CAPTION_BAR);
        if (mImeCaptionBarInsetsHeight != height
                || (source != null && !newFrame.equals(source.getFrame()))) {
            mImeCaptionBarInsetsHeight = height;
            if (mImeCaptionBarInsetsHeight != 0) {
                mState.getOrCreateSource(ID_IME_CAPTION_BAR, captionBar())
                        .setFrame(newFrame);
                getSourceConsumer(ID_IME_CAPTION_BAR, captionBar()).setControl(
                        new InsetsSourceControl(ID_IME_CAPTION_BAR, captionBar(),
                                null /* leash */, false /* initialVisible */,
                                new Point(), Insets.NONE),
                        new int[1], new int[1], new int[1], new int[1]);
            } else {
                mState.removeSource(ID_IME_CAPTION_BAR);
                InsetsSourceConsumer sourceConsumer = mSourceConsumers.get(ID_IME_CAPTION_BAR);
                if (sourceConsumer != null) {
                    sourceConsumer.setControl(null, new int[1], new int[1], new int[1], new int[1]);
                }
            }
            mHost.notifyInsetsChanged();
        }
    }

    @Override
    public void setSystemBarsBehavior(@Behavior int behavior) {
        mBehaviorControlled = true;
        mHost.setSystemBarsBehavior(behavior);
    }

    @Override
    @Behavior
    public int getSystemBarsBehavior() {
        if (!mBehaviorControlled) {
            // We only return the requested behavior, not the implied one.
            return BEHAVIOR_DEFAULT;
        }
        return mHost.getSystemBarsBehavior();
    }

    public boolean isBehaviorControlled() {
        return mBehaviorControlled;
    }

    @Override
    public void setAnimationsDisabled(boolean disable) {
        mAnimationsDisabled = disable;
    }

    @InsetsType
    private int calculateControllableTypes() {
        @InsetsType
        int result = 0;
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            InsetsSource source = mState.peekSource(consumer.getId());
            if (consumer.getControl() != null && source != null) {
                result |= consumer.getType();
            }
        }
        return result & ~mState.calculateUncontrollableInsetsFromFrame(mFrame, mBounds);
    }

    /**
     * @return The types that are now animating due to a listener invoking control/show/hide
     */
    @InsetsType
    private int invokeControllableInsetsChangedListeners() {
        mLastStartedAnimTypes = 0;
        @InsetsType
        int types = calculateControllableTypes();
        int size = mControllableInsetsChangedListeners.size();
        for (int i = 0; i < size; i++) {
            mControllableInsetsChangedListeners.get(i).onControllableInsetsChanged(this, types);
        }
        return mLastStartedAnimTypes;
    }

    @Override
    public void addOnControllableInsetsChangedListener(
            @NonNull OnControllableInsetsChangedListener listener) {
        Objects.requireNonNull(listener);
        mControllableInsetsChangedListeners.add(listener);
        listener.onControllableInsetsChanged(this, calculateControllableTypes());
    }

    @Override
    public void removeOnControllableInsetsChangedListener(
            @NonNull OnControllableInsetsChangedListener listener) {
        Objects.requireNonNull(listener);
        mControllableInsetsChangedListeners.remove(listener);
    }

    @Override
    public void releaseSurfaceControlFromRt(@NonNull SurfaceControl sc) {
        mHost.releaseSurfaceControlFromRt(sc);
    }

    @Override
    public void reportPerceptible(@InsetsType int types, boolean perceptible) {
        final int size = mSourceConsumers.size();
        for (int i = 0; i < size; i++) {
            final InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            if ((consumer.getType() & types) != 0) {
                consumer.onPerceptible(perceptible);
            }
        }
    }

    @VisibleForTesting(visibility = PACKAGE)
    @NonNull
    public Host getHost() {
        return mHost;
    }
}
