/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.internal.perfetto.protos.Insetsanimationcontrolimpl.InsetsAnimationControlImplProto.CURRENT_ALPHA;
import static android.internal.perfetto.protos.Insetsanimationcontrolimpl.InsetsAnimationControlImplProto.IS_CANCELLED;
import static android.internal.perfetto.protos.Insetsanimationcontrolimpl.InsetsAnimationControlImplProto.IS_FINISHED;
import static android.internal.perfetto.protos.Insetsanimationcontrolimpl.InsetsAnimationControlImplProto.PENDING_ALPHA;
import static android.internal.perfetto.protos.Insetsanimationcontrolimpl.InsetsAnimationControlImplProto.PENDING_FRACTION;
import static android.internal.perfetto.protos.Insetsanimationcontrolimpl.InsetsAnimationControlImplProto.PENDING_INSETS;
import static android.internal.perfetto.protos.Insetsanimationcontrolimpl.InsetsAnimationControlImplProto.SHOWN_ON_FINISH;
import static android.internal.perfetto.protos.Insetsanimationcontrolimpl.InsetsAnimationControlImplProto.TMP_MATRIX;
import static android.view.InsetsController.ANIMATION_TYPE_RESIZE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.graphics.Rect;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.InsetsController.AnimationType;
import android.view.InsetsController.LayoutInsetsDuringAnimation;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsAnimation.Bounds;
import android.view.animation.Interpolator;
import android.view.inputmethod.ImeTracker;

/**
 * Runs a fake animation of resizing insets to produce insets animation callbacks.
 *
 * @hide
 */
public class InsetsResizeAnimationRunner implements InsetsAnimationControlRunner,
        InternalInsetsAnimationController, WindowInsetsAnimationControlListener {

    @NonNull
    private final InsetsState mFromState;
    @NonNull
    private final InsetsState mToState;
    @InsetsType
    private final int mTypes;
    @NonNull
    private final WindowInsetsAnimation mAnimation;
    @NonNull
    private final InsetsAnimationControlCallbacks mController;
    @Nullable
    private ValueAnimator mAnimator;
    private boolean mCancelled;
    private boolean mFinished;

    public InsetsResizeAnimationRunner(@NonNull Rect frame, @NonNull Rect hostBounds,
            @NonNull InsetsState fromState, @NonNull InsetsState toState, Interpolator interpolator,
            long duration, @InsetsType int types,
            @NonNull InsetsAnimationControlCallbacks controller) {
        mFromState = fromState;
        mToState = toState;
        mTypes = types;
        mController = controller;
        mAnimation = new WindowInsetsAnimation(types, interpolator, duration);
        mAnimation.setAlpha(1f);
        final Insets fromInsets = fromState.calculateInsets(
                frame, hostBounds, types, false /* ignoreVisibility */);
        final Insets toInsets = toState.calculateInsets(
                frame, hostBounds, types, false /* ignoreVisibility */);
        controller.startAnimation(this, this, types, mAnimation,
                new Bounds(Insets.min(fromInsets, toInsets), Insets.max(fromInsets, toInsets)));
    }

    @InsetsType
    @Override
    public int getTypes() {
        return mTypes;
    }

    @InsetsType
    @Override
    public int getControllingTypes() {
        return mTypes;
    }

    @NonNull
    @Override
    public WindowInsetsAnimation getAnimation() {
        return mAnimation;
    }

    @AnimationType
    @Override
    public int getAnimationType() {
        return ANIMATION_TYPE_RESIZE;
    }

    @Override
    public boolean hasAnimationCallback() {
        return false;
    }

    @NonNull
    @Override
    public SurfaceParamsApplier getSurfaceParamsApplier() {
        return SurfaceParamsApplier.DEFAULT;
    }

    @Override
    @Nullable
    public ImeTracker.Token getStatsToken() {
        // Return null as resizing the IME view is not explicitly tracked.
        return null;
    }

    @Override
    public void cancel() {
        if (mCancelled || mFinished) {
            return;
        }
        mCancelled = true;
        if (mAnimator != null) {
            mAnimator.cancel();
        }
    }

    @Override
    public boolean isCancelled() {
        return mCancelled;
    }

    @Override
    public void onReady(@NonNull WindowInsetsAnimationController controller,
            @InsetsType int types) {
        if (mCancelled) {
            return;
        }
        mAnimator = ValueAnimator.ofFloat(0f, 1f);
        mAnimator.setDuration(mAnimation.getDurationMillis());
        mAnimator.addUpdateListener(animation -> {
            mAnimation.setFraction(animation.getAnimatedFraction());
            mController.scheduleApplyChangeInsets(InsetsResizeAnimationRunner.this);
        });
        mAnimator.addListener(new AnimatorListenerAdapter() {

            @Override
            public void onAnimationEnd(Animator animation) {
                mFinished = true;
                mController.scheduleApplyChangeInsets(InsetsResizeAnimationRunner.this);
            }
        });
        mAnimator.start();
    }

    @Override
    public boolean applyChangeInsets(@Nullable InsetsState outState) {
        if (mCancelled) {
            return false;
        }
        final float fraction = mAnimation.getInterpolatedFraction();
        InsetsState.traverse(mFromState, mToState, new InsetsState.OnTraverseCallbacks() {
            @Override
            public void onIdMatch(@NonNull InsetsSource fromSource,
                    @NonNull InsetsSource toSource) {
                final Rect fromFrame = fromSource.getFrame();
                final Rect toFrame = toSource.getFrame();
                final Rect frame = new Rect(
                        (int) (fromFrame.left + fraction * (toFrame.left - fromFrame.left)),
                        (int) (fromFrame.top + fraction * (toFrame.top - fromFrame.top)),
                        (int) (fromFrame.right + fraction * (toFrame.right - fromFrame.right)),
                        (int) (fromFrame.bottom + fraction * (toFrame.bottom - fromFrame.bottom)));
                final InsetsSource source =
                        new InsetsSource(fromSource.getId(), fromSource.getType());
                source.setFrame(frame);
                source.setVisible(toSource.isVisible());
                if (outState != null) {
                    outState.addSource(source);
                }
            }
        });
        if (mFinished) {
            mController.notifyFinished(this, true /* shown */);
        }
        return mFinished;
    }

    @Override
    public void dumpDebug(@NonNull ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(IS_CANCELLED, mCancelled);
        proto.write(IS_FINISHED, mFinished);
        proto.write(TMP_MATRIX, "null");
        proto.write(PENDING_INSETS, "null");
        proto.write(PENDING_FRACTION, mAnimation.getInterpolatedFraction());
        proto.write(SHOWN_ON_FINISH, true);
        proto.write(CURRENT_ALPHA, 1f);
        proto.write(PENDING_ALPHA, 1f);
        proto.end(token);
    }

    @NonNull
    @Override
    public Insets getHiddenStateInsets() {
        return Insets.NONE;
    }

    @NonNull
    @Override
    public Insets getShownStateInsets() {
        return Insets.NONE;
    }

    @NonNull
    @Override
    public Insets getCurrentInsets() {
        return Insets.NONE;
    }

    @FloatRange(from = 0f, to = 1f)
    @Override
    public float getCurrentFraction() {
        return 0;
    }

    @FloatRange(from = 0f, to = 1f)
    @Override
    public float getCurrentAlpha() {
        return 0;
    }

    @Override
    public void setInsetsAndAlpha(@Nullable Insets insets,
            @FloatRange(from = 0f, to = 1f) float alpha,
            @FloatRange(from = 0f, to = 1f) float fraction) {
    }

    @Override
    public void finish(boolean shown) {
    }

    @Override
    public boolean isFinished() {
        return false;
    }

    @Override
    public void notifyControlRevoked(@InsetsType int types) {
    }

    @Override
    public void updateSurfacePosition(@NonNull SparseArray<InsetsSourceControl> controls) {
    }

    @Override
    public boolean willUpdateSurface() {
        return false;
    }

    @Override
    public boolean hasZeroInsetsIme() {
        return false;
    }

    @Override
    public long getDurationMs() {
        return 0;
    }

    @Override
    public Interpolator getInsetsInterpolator() {
        return null;
    }

    @Override
    public void setReadyDispatched(boolean dispatched) {
    }

    @Override
    public void onFinished(@NonNull WindowInsetsAnimationController controller) {
    }

    @Override
    public void onCancelled(@Nullable WindowInsetsAnimationController controller) {
    }

    @Override
    public void updateLayoutInsetsDuringAnimation(
            @LayoutInsetsDuringAnimation int layoutInsetsDuringAnimation) {
    }
}
