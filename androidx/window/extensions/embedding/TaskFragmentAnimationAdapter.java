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

package androidx.window.extensions.embedding;

import static android.graphics.Matrix.MSCALE_X;

import android.graphics.Rect;
import android.view.Choreographer;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.view.animation.Transformation;

import androidx.annotation.NonNull;

/**
 * Wrapper to handle the TaskFragment animation update in one {@link SurfaceControl.Transaction}.
 *
 * The base adapter can be used for {@link RemoteAnimationTarget} that is simple open/close.
 */
class TaskFragmentAnimationAdapter {
    final Animation mAnimation;
    final RemoteAnimationTarget mTarget;
    final SurfaceControl mLeash;

    final Transformation mTransformation = new Transformation();
    final float[] mMatrix = new float[9];
    final float[] mVecs = new float[4];
    final Rect mRect = new Rect();
    private boolean mIsFirstFrame = true;

    TaskFragmentAnimationAdapter(@NonNull Animation animation,
            @NonNull RemoteAnimationTarget target) {
        this(animation, target, target.leash);
    }

    /**
     * @param leash the surface to animate.
     */
    TaskFragmentAnimationAdapter(@NonNull Animation animation,
            @NonNull RemoteAnimationTarget target, @NonNull SurfaceControl leash) {
        mAnimation = animation;
        mTarget = target;
        mLeash = leash;
    }

    /** Called on frame update. */
    final void onAnimationUpdate(@NonNull SurfaceControl.Transaction t, long currentPlayTime) {
        if (mIsFirstFrame) {
            t.show(mLeash);
            mIsFirstFrame = false;
        }

        // Extract the transformation to the current time.
        mAnimation.getTransformation(Math.min(currentPlayTime, mAnimation.getDuration()),
                mTransformation);
        t.setFrameTimelineVsync(Choreographer.getInstance().getVsyncId());
        onAnimationUpdateInner(t);
    }

    /** To be overridden by subclasses to adjust the animation surface change. */
    void onAnimationUpdateInner(@NonNull SurfaceControl.Transaction t) {
        mTransformation.getMatrix().postTranslate(
                mTarget.localBounds.left, mTarget.localBounds.top);
        t.setMatrix(mLeash, mTransformation.getMatrix(), mMatrix);
        t.setAlpha(mLeash, mTransformation.getAlpha());

        // Open/close animation may scale up the surface. Apply an inverse scale to the window crop
        // so that it will not be covering other windows.
        mVecs[1] = mVecs[2] = 0;
        mVecs[0] = mVecs[3] = 1;
        mTransformation.getMatrix().mapVectors(mVecs);
        mVecs[0] = 1.f / mVecs[0];
        mVecs[3] = 1.f / mVecs[3];
        final Rect clipRect = mTarget.localBounds;
        mRect.left = (int) (clipRect.left * mVecs[0] + 0.5f);
        mRect.right = (int) (clipRect.right * mVecs[0] + 0.5f);
        mRect.top = (int) (clipRect.top * mVecs[3] + 0.5f);
        mRect.bottom = (int) (clipRect.bottom * mVecs[3] + 0.5f);
        mRect.offsetTo(Math.round(mTarget.localBounds.width() * (1 - mVecs[0]) / 2.f),
                Math.round(mTarget.localBounds.height() * (1 - mVecs[3]) / 2.f));
        t.setWindowCrop(mLeash, mRect);
    }

    /** Called after animation finished. */
    final void onAnimationEnd(@NonNull SurfaceControl.Transaction t) {
        onAnimationUpdate(t, mAnimation.getDuration());
    }

    final long getDurationHint() {
        return mAnimation.computeDurationHint();
    }

    /**
     * Should be used when the {@link RemoteAnimationTarget} is in split with others, and want to
     * animate together as one. This adapter will offset the animation leash to make the animate of
     * two windows look like a single window.
     */
    static class SplitAdapter extends TaskFragmentAnimationAdapter {
        private final boolean mIsLeftHalf;
        private final int mWholeAnimationWidth;

        /**
         * @param isLeftHalf whether this is the left half of the animation.
         * @param wholeAnimationWidth the whole animation windows width.
         */
        SplitAdapter(@NonNull Animation animation, @NonNull RemoteAnimationTarget target,
                boolean isLeftHalf, int wholeAnimationWidth) {
            super(animation, target);
            mIsLeftHalf = isLeftHalf;
            mWholeAnimationWidth = wholeAnimationWidth;
            if (wholeAnimationWidth == 0) {
                throw new IllegalArgumentException("SplitAdapter must provide wholeAnimationWidth");
            }
        }

        @Override
        void onAnimationUpdateInner(@NonNull SurfaceControl.Transaction t) {
            float posX = mTarget.localBounds.left;
            final float posY = mTarget.localBounds.top;
            // This window is half of the whole animation window. Offset left/right to make it
            // look as one with the other half.
            mTransformation.getMatrix().getValues(mMatrix);
            final int targetWidth = mTarget.localBounds.width();
            final float scaleX = mMatrix[MSCALE_X];
            final float totalOffset = mWholeAnimationWidth * (1 - scaleX) / 2;
            final float curOffset = targetWidth * (1 - scaleX) / 2;
            final float offsetDiff = totalOffset - curOffset;
            if (mIsLeftHalf) {
                posX += offsetDiff;
            } else {
                posX -= offsetDiff;
            }
            mTransformation.getMatrix().postTranslate(posX, posY);
            t.setMatrix(mLeash, mTransformation.getMatrix(), mMatrix);
            t.setAlpha(mLeash, mTransformation.getAlpha());
        }
    }

    /**
     * Should be used for the animation of the snapshot of a {@link RemoteAnimationTarget} that has
     * size change.
     */
    static class SnapshotAdapter extends TaskFragmentAnimationAdapter {

        SnapshotAdapter(@NonNull Animation animation, @NonNull RemoteAnimationTarget target) {
            // Start leash is the snapshot of the starting surface.
            super(animation, target, target.startLeash);
        }

        @Override
        void onAnimationUpdateInner(@NonNull SurfaceControl.Transaction t) {
            // Snapshot should always be placed at the top left of the animation leash.
            mTransformation.getMatrix().postTranslate(0, 0);
            t.setMatrix(mLeash, mTransformation.getMatrix(), mMatrix);
            t.setAlpha(mLeash, mTransformation.getAlpha());
        }
    }

    /**
     * Should be used for the animation of the {@link RemoteAnimationTarget} that has size change.
     */
    static class BoundsChangeAdapter extends TaskFragmentAnimationAdapter {

        BoundsChangeAdapter(@NonNull Animation animation, @NonNull RemoteAnimationTarget target) {
            super(animation, target);
        }

        @Override
        void onAnimationUpdateInner(@NonNull SurfaceControl.Transaction t) {
            mTransformation.getMatrix().postTranslate(
                    mTarget.localBounds.left, mTarget.localBounds.top);
            t.setMatrix(mLeash, mTransformation.getMatrix(), mMatrix);
            t.setAlpha(mLeash, mTransformation.getAlpha());

            // The following applies an inverse scale to the clip-rect so that it crops "after" the
            // scale instead of before.
            mVecs[1] = mVecs[2] = 0;
            mVecs[0] = mVecs[3] = 1;
            mTransformation.getMatrix().mapVectors(mVecs);
            mVecs[0] = 1.f / mVecs[0];
            mVecs[3] = 1.f / mVecs[3];
            final Rect clipRect = mTransformation.getClipRect();
            mRect.left = (int) (clipRect.left * mVecs[0] + 0.5f);
            mRect.right = (int) (clipRect.right * mVecs[0] + 0.5f);
            mRect.top = (int) (clipRect.top * mVecs[3] + 0.5f);
            mRect.bottom = (int) (clipRect.bottom * mVecs[3] + 0.5f);
            t.setWindowCrop(mLeash, mRect);
        }
    }
}
