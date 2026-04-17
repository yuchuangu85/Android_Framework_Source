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
 * limitations under the License
 */

package android.view;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.internal.perfetto.protos.Insetsanimationcontrolimpl.InsetsAnimationControlImplProto.CURRENT_ALPHA;
import static android.internal.perfetto.protos.Insetsanimationcontrolimpl.InsetsAnimationControlImplProto.IS_CANCELLED;
import static android.internal.perfetto.protos.Insetsanimationcontrolimpl.InsetsAnimationControlImplProto.IS_FINISHED;
import static android.internal.perfetto.protos.Insetsanimationcontrolimpl.InsetsAnimationControlImplProto.PENDING_ALPHA;
import static android.internal.perfetto.protos.Insetsanimationcontrolimpl.InsetsAnimationControlImplProto.PENDING_FRACTION;
import static android.internal.perfetto.protos.Insetsanimationcontrolimpl.InsetsAnimationControlImplProto.PENDING_INSETS;
import static android.internal.perfetto.protos.Insetsanimationcontrolimpl.InsetsAnimationControlImplProto.SHOWN_ON_FINISH;
import static android.internal.perfetto.protos.Insetsanimationcontrolimpl.InsetsAnimationControlImplProto.TMP_MATRIX;
import static android.view.EventLogTags.IMF_IME_ANIM_CANCEL;
import static android.view.EventLogTags.IMF_IME_ANIM_FINISH;
import static android.view.EventLogTags.IMF_IME_ANIM_START;
import static android.view.InsetsController.ANIMATION_TYPE_SHOW;
import static android.view.InsetsController.AnimationType;
import static android.view.InsetsController.LAYOUT_INSETS_DURING_ANIMATION_SHOWN;
import static android.view.InsetsController.LayoutInsetsDuringAnimation;
import static android.view.InsetsSource.ID_IME;
import static android.view.InsetsSource.SIDE_BOTTOM;
import static android.view.InsetsSource.SIDE_LEFT;
import static android.view.InsetsSource.SIDE_NONE;
import static android.view.InsetsSource.SIDE_RIGHT;
import static android.view.InsetsSource.SIDE_TOP;
import static android.view.ViewProtoLogGroups.INSETS_ANIMATION_CONTROLLER;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.inputmethod.ImeTracker.DEBUG_IME_VISIBILITY;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.CompatibilityInfo;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.SparseSetArray;
import android.util.proto.ProtoOutputStream;
import android.view.InsetsSource.InternalInsetsSide;
import android.view.SyncRtSurfaceTransactionApplier.SurfaceParams;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsAnimation.Bounds;
import android.view.animation.Interpolator;
import android.view.inputmethod.ImeTracker;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Implements {@link WindowInsetsAnimationController}
 *
 * This class coordinates the leashes for the upstream caller by managing the translation and the
 * alpha during the insets animation. It also updates the {@link InsetsState} if requested.
 *
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class InsetsAnimationControlImpl implements InternalInsetsAnimationController,
        InsetsAnimationControlRunner {

    /**
     * A temporary rectangle used to store and manipulate the frame of an insets source.
     */
    @NonNull
    private final Rect mTmpFrame = new Rect();

    /**
     * A temporary rectangle used to store and manipulate the visible frame of an insets source.
     */
    @NonNull
    private final Rect mTmpVisibleFrame = new Rect();

    /**
     * The listener that receives callbacks about the progress and lifecycle of the animation.
     */
    @NonNull
    private final WindowInsetsAnimationControlListener mListener;

    /**
     * A collection of controls for each insets source being animated, keyed by their ID.
     */
    @NonNull
    private final SparseArray<InsetsSourceControl> mControls;

    /**
     * A mapping from sides (left, top, right, bottom) to the controls affecting that side.
     */
    @NonNull
    private final SparseSetArray<InsetsSourceControl> mSideControlsMap = new SparseSetArray<>();

    /** @see WindowInsetsAnimationController#getHiddenStateInsets */
    @NonNull
    private final Insets mHiddenInsets;

    /** @see WindowInsetsAnimationController#getShownStateInsets */
    @NonNull
    private final Insets mShownInsets;

    /**
     * A temporary matrix used for calculating surface transformations during animation frames.
     */
    @NonNull
    private final Matrix mTmpMatrix = new Matrix();

    /**
     * The state of insets at the beginning of the animation.
     */
    @NonNull
    private final InsetsState mInitialInsetsState;

    /**
     * The type of animation (e.g., show or hide).
     */
    @AnimationType
    private final int mAnimationType;

    /**
     * Determines which insets state is used for layout during the animation.
     */
    @LayoutInsetsDuringAnimation
    private int mLayoutInsetsDuringAnimation;

    /**
     * The bitmask of insets types that this controller was originally requested to animate.
     */
    @InsetsType
    private final int mTypes;

    /**
     * The bitmask of insets types that this controller is currently managing.
     */
    @InsetsType
    private int mControllingTypes;

    /**
     * The callback interface used to communicate with the {@link InsetsController}.
     */
    @NonNull
    private final InsetsAnimationControlCallbacks mController;

    /**
     * Applier for surface parameters, used to update leashes in the RenderThread.
     */
    @NonNull
    private final SurfaceParamsApplier mSurfaceParamsApplier;

    /**
     * The underlying animation object representing this insets transition.
     */
    @NonNull
    private final WindowInsetsAnimation mAnimation;

    /**
     * The total duration of the animation in milliseconds.
     */
    private final long mDurationMs;

    /**
     * The interpolator used to calculate intermediate progress.
     */
    private final Interpolator mInterpolator;

    /** @see WindowInsetsAnimationController#hasZeroInsetsIme */
    private final boolean mHasZeroInsetsIme;

    /**
     * Translator used to handle coordinate transformations for different display scales.
     */
    @Nullable
    private final CompatibilityInfo.Translator mTranslator;

    /**
     * Token used for tracking IME visibility requests through {@link ImeTracker}.
     */
    @Nullable
    private final ImeTracker.Token mStatsToken;

    /**
     * The current insets values applied to the window.
     */
    @NonNull
    private Insets mCurrentInsets;

    /**
     * The insets values that will be applied in the next animation frame.
     */
    @NonNull
    private Insets mPendingInsets;

    /**
     * The fraction of the animation that will be applied in the next animation frame.
     */
    @FloatRange(from = 0f, to = 1f)
    private float mPendingFraction;

    /**
     * Flag indicating if the animation has successfully finished.
     */
    private boolean mFinished;

    /**
     * Flag indicating if the animation is in the process of being cancelled.
     */
    private boolean mCancelling;

    /**
     * Flag indicating if the animation has been cancelled.
     */
    private boolean mCancelled;

    /**
     * Flag indicating if the insets should be shown once the animation finishes.
     */
    private boolean mShownOnFinish;

    /**
     * The current alpha value (transparency) of the animating insets.
     */
    @FloatRange(from = 0f, to = 1f)
    private float mCurrentAlpha = 1.0f;

    /**
     * The alpha value that will be applied in the next animation frame.
     */
    @FloatRange(from = 0f, to = 1f)
    private float mPendingAlpha = 1.0f;

    /**
     * Flag indicating if the 'ready' state of the animation has been dispatched to the listener.
     */
    private boolean mReadyDispatched;

    /**
     * Tracks whether the animating insets are considered perceptible to the user.
     */
    @Nullable
    private Boolean mPerceptible;

    @VisibleForTesting(visibility = PACKAGE)
    public InsetsAnimationControlImpl(@NonNull SparseArray<InsetsSourceControl> controls,
            @Nullable Rect frame, @Nullable Rect bounds, @NonNull InsetsState state,
            @NonNull WindowInsetsAnimationControlListener listener, @InsetsType int types,
            @NonNull InsetsAnimationControlCallbacks controller,
            @NonNull SurfaceParamsApplier surfaceParamsApplier,
            @NonNull InsetsAnimationSpec insetsAnimationSpec, @AnimationType int animationType,
            @LayoutInsetsDuringAnimation int layoutInsetsDuringAnimation,
            @Nullable CompatibilityInfo.Translator translator,
            @Nullable ImeTracker.Token statsToken) {
        mControls = controls;
        mListener = listener;
        mTypes = types;
        mControllingTypes = types;
        mController = controller;
        mSurfaceParamsApplier = surfaceParamsApplier;
        mInitialInsetsState = new InsetsState(state, true /* copySources */);
        if (frame != null && bounds != null) {
            final SparseIntArray idSideMap = new SparseIntArray();
            mCurrentInsets = getInsetsFromState(mInitialInsetsState, frame, bounds,
                    null /* idSideMap */);
            mHiddenInsets = calculateInsets(mInitialInsetsState, frame, bounds, controls,
                    false /* shown */, null /* idSideMap */);
            mShownInsets = calculateInsets(mInitialInsetsState, frame, bounds, controls,
                    true /* shown */, idSideMap);
            mHasZeroInsetsIme = mShownInsets.bottom == 0 && controlsType(WindowInsets.Type.ime());
            if (mHasZeroInsetsIme) {
                // IME has shownInsets of ZERO, and can't map to a side by default.
                // Map zero insets IME to bottom, making it a special case of bottom insets.
                idSideMap.put(ID_IME, SIDE_BOTTOM);
            }
            buildSideControlsMap(idSideMap, mSideControlsMap, controls);
        } else {
            // Passing a null frame or bounds indicates the caller wants to play the insets
            // animation anyway, no matter the source provides insets to the frame or not.
            mCurrentInsets = calculateInsets(mInitialInsetsState, controls, true /* shown */);
            mHiddenInsets = calculateInsets(null, controls, false /* shown */);
            mShownInsets = calculateInsets(null, controls, true /* shown */);
            mHasZeroInsetsIme = mShownInsets.bottom == 0 && controlsType(WindowInsets.Type.ime());
            buildSideControlsMap(mSideControlsMap, controls);
        }
        mPendingInsets = mCurrentInsets;

        mDurationMs = insetsAnimationSpec.getDurationMs(mHasZeroInsetsIme);
        mInterpolator = insetsAnimationSpec.getInsetsInterpolator(mHasZeroInsetsIme);

        mAnimation = new WindowInsetsAnimation(mTypes, mInterpolator, mDurationMs);
        mAnimation.setAlpha(getCurrentAlpha());
        mAnimationType = animationType;
        mLayoutInsetsDuringAnimation = layoutInsetsDuringAnimation;
        mTranslator = translator;
        mStatsToken = statsToken;
        if (DEBUG_IME_VISIBILITY && (types & ime()) != 0) {
            EventLog.writeEvent(IMF_IME_ANIM_START,
                    mStatsToken != null ? mStatsToken.getTag() : ImeTracker.TOKEN_NONE,
                    mAnimationType, mCurrentAlpha, "Current:" + mCurrentInsets,
                    "Shown:" + mShownInsets, "Hidden:" + mHiddenInsets);
        }
        mController.startAnimation(this, listener, types, mAnimation,
                new Bounds(mHiddenInsets, mShownInsets));
    }

    private boolean calculatePerceptible(@NonNull Insets currentInsets, float currentAlpha) {
        return 100 * currentInsets.left >= 5 * (mShownInsets.left - mHiddenInsets.left)
                && 100 * currentInsets.top >= 5 * (mShownInsets.top - mHiddenInsets.top)
                && 100 * currentInsets.right >= 5 * (mShownInsets.right - mHiddenInsets.right)
                && 100 * currentInsets.bottom >= 5 * (mShownInsets.bottom - mHiddenInsets.bottom)
                && currentAlpha >= 0.5f;
    }

    @Override
    public boolean hasZeroInsetsIme() {
        return mHasZeroInsetsIme;
    }

    @Override
    public long getDurationMs() {
        return mDurationMs;
    }

    @Override
    public Interpolator getInsetsInterpolator() {
        return mInterpolator;
    }

    @Override
    public void setReadyDispatched(boolean dispatched) {
        mReadyDispatched = dispatched;
    }

    @Override
    @NonNull
    public Insets getHiddenStateInsets() {
        return mHiddenInsets;
    }

    @Override
    @NonNull
    public Insets getShownStateInsets() {
        return mShownInsets;
    }

    @Override
    @NonNull
    public Insets getCurrentInsets() {
        return mCurrentInsets;
    }

    @Override
    @FloatRange(from = 0f, to = 1f)
    public float getCurrentAlpha() {
        return mCurrentAlpha;
    }

    @Override
    @InsetsType
    public int getTypes() {
        return mTypes;
    }

    @Override
    public int getControllingTypes() {
        return mControllingTypes;
    }

    @Override
    public void notifyControlRevoked(@InsetsType int types) {
        mControllingTypes &= ~types;
    }

    @Override
    public void updateSurfacePosition(@NonNull SparseArray<InsetsSourceControl> controls) {
        for (int i = controls.size() - 1; i >= 0; i--) {
            final InsetsSourceControl control = controls.valueAt(i);
            final InsetsSourceControl c = mControls.get(control.getId());
            if (c == null) {
                continue;
            }
            final Point position = control.getSurfacePosition();
            c.setSurfacePosition(position.x, position.y);
        }
    }

    @Override
    public boolean willUpdateSurface() {
        return !mFinished && !mCancelled;
    }

    @Override
    @AnimationType
    public int getAnimationType() {
        return mAnimationType;
    }

    @Override
    public boolean hasAnimationCallback() {
        if (mListener instanceof InsetsController.InternalAnimationControlListener listener) {
            return listener.hasAnimationCallbacks();
        }
        return false;
    }

    @Override
    @NonNull
    public SurfaceParamsApplier getSurfaceParamsApplier() {
        return mSurfaceParamsApplier;
    }

    @Override
    @Nullable
    public ImeTracker.Token getStatsToken() {
        return mStatsToken;
    }

    @Override
    public void setInsetsAndAlpha(@Nullable Insets insets,
            @FloatRange(from = 0f, to = 1f) float alpha,
            @FloatRange(from = 0f, to = 1f) float fraction) {
        setInsetsAndAlpha(insets, alpha, fraction, false /* allowWhenFinished */);
    }

    private void setInsetsAndAlpha(@Nullable Insets insets,
            @FloatRange(from = 0f, to = 1f) float alpha,
            @FloatRange(from = 0f, to = 1f) float fraction, boolean allowWhenFinished) {
        if (!allowWhenFinished && mFinished) {
            throw new IllegalStateException(
                    "Can't change insets on an animation that is finished.");
        }
        if (mCancelled) {
            throw new IllegalStateException(
                    "Can't change insets on an animation that is cancelled.");
        }
        mPendingFraction = sanitize(fraction);
        mPendingInsets = sanitize(insets);
        mPendingAlpha = sanitize(alpha);
        mController.scheduleApplyChangeInsets(this);
        boolean perceptible = calculatePerceptible(mPendingInsets, mPendingAlpha);
        if (mPerceptible == null || perceptible != mPerceptible) {
            mController.reportPerceptible(mTypes, perceptible);
            mPerceptible = perceptible;
        }
    }

    /**
     * Applies the pending insets and alpha by updating the surface leashes.
     *
     * <p>This method is typically called from the {@link Choreographer} callback scheduled by
     * {@link #setInsetsAndAlpha}. It calculates the necessary translations for each surface
     * leash based on the difference between shown insets and pending insets.
     *
     * @param outState If not null, the {@link InsetsState} will be updated with the new
     *                 source frames and visibility.
     * @return {@code true} if the animation is finished and this was the final update.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean applyChangeInsets(@Nullable InsetsState outState) {
        if (mCancelled) {
            ProtoLog.d(INSETS_ANIMATION_CONTROLLER, "applyChangeInsets canceled");
            return false;
        }
        final Insets offset = Insets.subtract(mShownInsets, mPendingInsets);
        final var params = new ArrayList<SurfaceParams>();
        updateLeashesForSide(SIDE_LEFT, offset.left, params, outState, mPendingAlpha);
        updateLeashesForSide(SIDE_TOP, offset.top, params, outState, mPendingAlpha);
        updateLeashesForSide(SIDE_RIGHT, offset.right, params, outState, mPendingAlpha);
        updateLeashesForSide(SIDE_BOTTOM, offset.bottom, params, outState, mPendingAlpha);

        mSurfaceParamsApplier.applySurfaceParams(params.toArray(new SurfaceParams[0]));
        mCurrentInsets = mPendingInsets;
        mAnimation.setFraction(mPendingFraction);
        mCurrentAlpha = mPendingAlpha;
        mAnimation.setAlpha(mPendingAlpha);
        if (mFinished) {
            ProtoLog.d(INSETS_ANIMATION_CONTROLLER,
                    "notifyFinished shown: %b, currentAlpha: %f, currentInsets: %s", mShownOnFinish,
                    mCurrentAlpha, mCurrentInsets);
            mController.notifyFinished(this, mShownOnFinish);
            releaseLeashes();
            ProtoLog.d(INSETS_ANIMATION_CONTROLLER, "Animation finished abruptly.");
        }
        return mFinished;
    }

    private void releaseLeashes() {
        for (int i = mControls.size() - 1; i >= 0; i--) {
            final InsetsSourceControl c = mControls.valueAt(i);
            if (c == null) continue;
            c.release(mController::releaseSurfaceControlFromRt);
        }
    }

    @Override
    public void finish(boolean shown) {
        if (mCancelled || mFinished) {
            ProtoLog.d(INSETS_ANIMATION_CONTROLLER,
                    "Animation already canceled or finished, not notifying.");
            return;
        }
        mShownOnFinish = shown;
        mFinished = true;
        final Insets insets = shown ? mShownInsets : mHiddenInsets;
        setInsetsAndAlpha(insets, mPendingAlpha, 1f /* fraction */, true /* allowWhenFinished */);

        ProtoLog.d(INSETS_ANIMATION_CONTROLLER, "notify control request finished for types: %s",
                WindowInsets.Type.toString(mTypes));
        mListener.onFinished(this);
        if (DEBUG_IME_VISIBILITY && (mTypes & ime()) != 0) {
            EventLog.writeEvent(IMF_IME_ANIM_FINISH,
                    mStatsToken != null ? mStatsToken.getTag() : ImeTracker.TOKEN_NONE,
                    mAnimationType, mCurrentAlpha, shown ? 1 : 0, Objects.toString(insets));
        }
    }

    @Override
    @VisibleForTesting
    @FloatRange(from = 0f, to = 1f)
    public float getCurrentFraction() {
        return mAnimation.getFraction();
    }

    @Override
    public void cancel() {
        if (mFinished) {
            return;
        }
        mPendingInsets = mLayoutInsetsDuringAnimation == LAYOUT_INSETS_DURING_ANIMATION_SHOWN
                ? mShownInsets : mHiddenInsets;
        mPendingAlpha = 1f;
        mCancelling = true;
        applyChangeInsets(null);
        mCancelled = true;
        mListener.onCancelled(mReadyDispatched ? this : null);
        ProtoLog.d(INSETS_ANIMATION_CONTROLLER, "notify Control request cancelled for types: %s",
                WindowInsets.Type.toString(mTypes));
        if (DEBUG_IME_VISIBILITY && (mTypes & ime()) != 0) {
            EventLog.writeEvent(IMF_IME_ANIM_CANCEL,
                    mStatsToken != null ? mStatsToken.getTag() : ImeTracker.TOKEN_NONE,
                    mAnimationType, Objects.toString(mPendingInsets));
        }
        releaseLeashes();
    }

    @Override
    public boolean isFinished() {
        return mFinished;
    }

    @Override
    public boolean isCancelled() {
        return mCancelled;
    }

    @Override
    @NonNull
    public WindowInsetsAnimation getAnimation() {
        return mAnimation;
    }

    @Override
    public void updateLayoutInsetsDuringAnimation(
            @LayoutInsetsDuringAnimation int layoutInsetsDuringAnimation) {
        mLayoutInsetsDuringAnimation = layoutInsetsDuringAnimation;
    }

    @Override
    public void dumpDebug(@NonNull ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(IS_CANCELLED, mCancelled);
        proto.write(IS_FINISHED, mFinished);
        proto.write(TMP_MATRIX, Objects.toString(mTmpMatrix));
        proto.write(PENDING_INSETS, Objects.toString(mPendingInsets));
        proto.write(PENDING_FRACTION, mPendingFraction);
        proto.write(SHOWN_ON_FINISH, mShownOnFinish);
        proto.write(CURRENT_ALPHA, mCurrentAlpha);
        proto.write(PENDING_ALPHA, mPendingAlpha);
        proto.end(token);
    }

    @NonNull
    SparseArray<InsetsSourceControl> getControls() {
        return mControls;
    }

    @NonNull
    private Insets getInsetsFromState(@NonNull InsetsState state, @NonNull Rect frame,
            @NonNull Rect bounds, @Nullable @InternalInsetsSide SparseIntArray idSideMap) {
        return state.calculateInsets(frame, bounds, null /* ignoringVisibilityState */,
                false /* isScreenRound */, SOFT_INPUT_ADJUST_RESIZE /* legacySoftInputMode */,
                0 /* legacyWindowFlags */, 0 /* legacySystemUiFlags */, TYPE_APPLICATION,
                ACTIVITY_TYPE_UNDEFINED, idSideMap).getInsets(mTypes);
    }

    /** Computes the insets relative to the given frame. */
    @NonNull
    private Insets calculateInsets(@NonNull InsetsState state, @NonNull Rect frame,
            @NonNull Rect bounds, @NonNull SparseArray<InsetsSourceControl> controls, boolean shown,
            @Nullable @InternalInsetsSide SparseIntArray idSideMap) {
        for (int i = controls.size() - 1; i >= 0; i--) {
            final InsetsSourceControl control = controls.valueAt(i);
            if (control == null) {
                // control may be null if it got revoked.
                continue;
            }
            state.setSourceVisible(control.getId(), shown);
        }
        return getInsetsFromState(state, frame, bounds, idSideMap);
    }

    /** Computes the insets from the insets hints of controls. */
    @NonNull
    private Insets calculateInsets(@Nullable InsetsState state,
            @NonNull SparseArray<InsetsSourceControl> controls, boolean shownOrCurrent) {
        Insets insets = Insets.NONE;
        if (!shownOrCurrent) {
            return insets;
        }
        for (int i = controls.size() - 1; i >= 0; i--) {
            final InsetsSourceControl control = controls.valueAt(i);
            if (control == null) {
                // control may be null if it got revoked.
                continue;
            }
            if (state == null
                    || state.isSourceOrDefaultVisible(control.getId(), control.getType())) {
                insets = Insets.max(insets, control.getInsetsHint());
            }
        }
        return insets;
    }

    @NonNull
    private Insets sanitize(@Nullable Insets insets) {
        if (insets == null) {
            insets = getCurrentInsets();
        }
        if (hasZeroInsetsIme()) {
            return insets;
        }
        return Insets.max(Insets.min(insets, mShownInsets), mHiddenInsets);
    }

    @FloatRange(from = 0f, to = 1f)
    private static float sanitize(@FloatRange(from = 0f, to = 1f) float alpha) {
        return alpha >= 1 ? 1 : (alpha <= 0 ? 0 : alpha);
    }

    /**
     * Updates the surface leashes for a specific side of the window.
     *
     * @param side          The side to update (left, top, right, or bottom).
     * @param offset        The amount of translation to apply to the surfaces on this side.
     * @param surfaceParams A list where new {@link SurfaceParams} will be added for each control.
     * @param outState      If not null, the animated {@link InsetsSource}s will be updated
     * @param alpha         The alpha value to apply to the surfaces.
     */
    private void updateLeashesForSide(@InternalInsetsSide int side, int offset,
            @NonNull List<SurfaceParams> surfaceParams, @Nullable InsetsState outState,
            @FloatRange(from = 0f, to = 1f) float alpha) {
        final ArraySet<InsetsSourceControl> controls = mSideControlsMap.get(side);
        if (controls == null) {
            return;
        }

        final boolean visible = mFinished
                ? mShownOnFinish
                : (mCancelling
                        // If the animation is being cancelled, mShownOnFinish is not valid.
                        // Here uses mLayoutInsetsDuringAnimation to decide if it should be visible.
                        ? mLayoutInsetsDuringAnimation == LAYOUT_INSETS_DURING_ANIMATION_SHOWN
                        // The first frame of ANIMATION_TYPE_SHOW should be invisible since it is
                        // animated from the hidden state.
                        : (mAnimationType != ANIMATION_TYPE_SHOW || mPendingFraction != 0));

        // TODO: Implement behavior when inset spans over multiple types
        for (int i = controls.size() - 1; i >= 0; i--) {
            final InsetsSourceControl control = controls.valueAt(i);
            final InsetsSource source = mInitialInsetsState.peekSource(control.getId());
            final SurfaceControl leash = control.getLeash();

            mTmpMatrix.setTranslate(control.getSurfacePosition().x, control.getSurfacePosition().y);
            Rect tmpVisibleFrame = null;
            if (source != null) {
                mTmpFrame.set(source.getFrame());
                if (source.getVisibleFrame() != null) {
                    mTmpVisibleFrame.set(source.getVisibleFrame());
                    tmpVisibleFrame = mTmpVisibleFrame;
                }
            }
            addTranslationToMatrix(side, offset, mTmpMatrix, mTmpFrame, tmpVisibleFrame);

            if (outState != null && source != null) {
                outState.addSource(new InsetsSource(source)
                        .setVisible(visible)
                        .setFrame(mTmpFrame)
                        .setVisibleFrame(tmpVisibleFrame));
            }

            // If the system is controlling the insets source, the leash can be null.
            if (leash != null) {
                SurfaceParams params = new SurfaceParams.Builder(leash)
                        .withAlpha(alpha)
                        .withMatrix(mTmpMatrix)
                        .withVisibility(visible)
                        .build();
                surfaceParams.add(params);
            }
        }
    }

    /**
     * Translates a transformation matrix and a set of frames based on the specified side and
     * offset.
     *
     * @param side         The side from which the translation originates.
     * @param offset       The pixel offset to translate.
     * @param m            The matrix to update with the translation.
     * @param frame        The frame rectangle to offset.
     * @param visibleFrame The visible frame rectangle to offset.
     */
    private void addTranslationToMatrix(@InternalInsetsSide int side, int offset, @NonNull Matrix m,
            @NonNull Rect frame, @Nullable Rect visibleFrame) {
        final float surfaceOffset = mTranslator != null
                ? mTranslator.translateLengthInAppWindowToScreen(offset) : offset;
        int offsetX = 0;
        int offsetY = 0;
        switch (side) {
            case SIDE_LEFT:
                m.postTranslate(-surfaceOffset, 0);
                offsetX = -offset;
                break;
            case SIDE_TOP:
                m.postTranslate(0, -surfaceOffset);
                offsetY = -offset;
                break;
            case SIDE_RIGHT:
                m.postTranslate(surfaceOffset, 0);
                offsetX = offset;
                break;
            case SIDE_BOTTOM:
                m.postTranslate(0, surfaceOffset);
                offsetY = offset;
                break;
        }
        frame.offset(offsetX, offsetY);
        if (visibleFrame != null) {
            visibleFrame.offset(offsetX, offsetY);
        }
    }

    /**
     * Builds a map from window sides to the controls associated with that side.
     *
     * @param idSideMap       A mapping of source IDs to their corresponding logical sides.
     * @param sideControlsMap The map to populate with controls grouped by side.
     * @param controls        The set of all controls being managed.
     */
    private static void buildSideControlsMap(@NonNull SparseIntArray idSideMap,
            @NonNull SparseSetArray<InsetsSourceControl> sideControlsMap,
            @NonNull SparseArray<InsetsSourceControl> controls) {
        for (int i = idSideMap.size() - 1; i >= 0; i--) {
            final int type = idSideMap.keyAt(i);
            final int side = idSideMap.valueAt(i);
            final InsetsSourceControl control = controls.get(type);
            if (control == null) {
                // If the types that we are controlling are less than the types that the system has,
                // there can be some null controllers.
                continue;
            }
            sideControlsMap.add(side, control);
        }
    }

    /**
     * Builds a map from window sides to the controls associated with that side,
     * using the insets hint from each control to determine the side.
     *
     * @param sideControlsMap The map to populate with controls grouped by side.
     * @param controls        The set of all controls being managed.
     */
    private static void buildSideControlsMap(
            @NonNull SparseSetArray<InsetsSourceControl> sideControlsMap,
            @NonNull SparseArray<InsetsSourceControl> controls) {
        for (int i = controls.size() - 1; i >= 0; i--) {
            final InsetsSourceControl control = controls.valueAt(i);
            if (control == null) {
                // control may be null if it got revoked.
                continue;
            }
            @InternalInsetsSide int side = InsetsSource.getInsetSide(control.getInsetsHint());
            if (side == SIDE_NONE && control.getType() == WindowInsets.Type.ime()) {
                // IME might not provide insets when it is fullscreen or floating.
                side = SIDE_BOTTOM;
            }
            sideControlsMap.add(side, control);
        }
    }
}
