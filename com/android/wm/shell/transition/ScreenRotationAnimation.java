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

package com.android.wm.shell.transition;

import static android.hardware.HardwareBuffer.RGBA_8888;
import static android.hardware.HardwareBuffer.USAGE_PROTECTED_CONTENT;
import static android.util.RotationUtils.deltaRotation;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_CROSSFADE;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT;
import static android.view.WindowManagerPolicyConstants.SCREEN_FREEZE_LAYER_BASE;

import static com.android.wm.shell.transition.DefaultTransitionHandler.startSurfaceAnimation;
import static com.android.wm.shell.transition.Transitions.TAG;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.content.Context;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.GraphicBuffer;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.util.Slog;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.SurfaceSession;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.window.TransitionInfo;

import com.android.internal.R;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * This class handles the rotation animation when the device is rotated.
 *
 * <p>
 * The screen rotation animation is composed of 3 different part:
 * <ul>
 * <li> The screenshot: <p>
 *     A screenshot of the whole screen prior the change of orientation is taken to hide the
 *     element resizing below. The screenshot is then animated to rotate and cross-fade to
 *     the new orientation with the content in the new orientation.
 *
 * <li> The windows on the display: <p>y
 *      Once the device is rotated, the screen and its content are in the new orientation. The
 *      animation first rotate the new content into the old orientation to then be able to
 *      animate to the new orientation
 *
 * <li> The Background color frame: <p>
 *      To have the animation seem more seamless, we add a color transitioning background behind the
 *      exiting and entering layouts. We compute the brightness of the start and end
 *      layouts and transition from the two brightness values as grayscale underneath the animation
 * </ul>
 */
class ScreenRotationAnimation {
    static final int MAX_ANIMATION_DURATION = 10 * 1000;

    private final Context mContext;
    private final TransactionPool mTransactionPool;
    private final float[] mTmpFloats = new float[9];
    // Complete transformations being applied.
    private final Matrix mSnapshotInitialMatrix = new Matrix();
    /** The leash of display. */
    private final SurfaceControl mSurfaceControl;
    private final Rect mStartBounds = new Rect();
    private final Rect mEndBounds = new Rect();

    private final int mAnimHint;
    private final int mStartWidth;
    private final int mStartHeight;
    private final int mEndWidth;
    private final int mEndHeight;
    private final int mStartRotation;
    private final int mEndRotation;

    /** This layer contains the actual screenshot that is to be faded out. */
    private SurfaceControl mScreenshotLayer;
    /**
     * Only used for screen rotation and not custom animations. Layered behind all other layers
     * to avoid showing any "empty" spots
     */
    private SurfaceControl mBackColorSurface;
    /** The leash using to animate screenshot layer. */
    private SurfaceControl mAnimLeash;
    private Transaction mTransaction;

    // The current active animation to move from the old to the new rotated
    // state.  Which animation is run here will depend on the old and new
    // rotations.
    private Animation mRotateExitAnimation;
    private Animation mRotateEnterAnimation;
    private Animation mRotateAlphaAnimation;

    /** Intensity of light/whiteness of the layout before rotation occurs. */
    private float mStartLuma;
    /** Intensity of light/whiteness of the layout after rotation occurs. */
    private float mEndLuma;

    ScreenRotationAnimation(Context context, SurfaceSession session, TransactionPool pool,
            Transaction t, TransitionInfo.Change change, SurfaceControl rootLeash, int animHint) {
        mContext = context;
        mTransactionPool = pool;
        mAnimHint = animHint;

        mSurfaceControl = change.getLeash();
        mStartWidth = change.getStartAbsBounds().width();
        mStartHeight = change.getStartAbsBounds().height();
        mEndWidth = change.getEndAbsBounds().width();
        mEndHeight = change.getEndAbsBounds().height();
        mStartRotation = change.getStartRotation();
        mEndRotation = change.getEndRotation();

        mStartBounds.set(change.getStartAbsBounds());
        mEndBounds.set(change.getEndAbsBounds());

        mAnimLeash = new SurfaceControl.Builder(session)
                .setParent(rootLeash)
                .setEffectLayer()
                .setCallsite("ShellRotationAnimation")
                .setName("Animation leash of screenshot rotation")
                .build();

        try {
            SurfaceControl.LayerCaptureArgs args =
                    new SurfaceControl.LayerCaptureArgs.Builder(mSurfaceControl)
                            .setCaptureSecureLayers(true)
                            .setAllowProtected(true)
                            .setSourceCrop(new Rect(0, 0, mStartWidth, mStartHeight))
                            .build();
            SurfaceControl.ScreenshotHardwareBuffer screenshotBuffer =
                    SurfaceControl.captureLayers(args);
            if (screenshotBuffer == null) {
                Slog.w(TAG, "Unable to take screenshot of display");
                return;
            }

            mScreenshotLayer = new SurfaceControl.Builder(session)
                    .setParent(mAnimLeash)
                    .setBLASTLayer()
                    .setSecure(screenshotBuffer.containsSecureLayers())
                    .setCallsite("ShellRotationAnimation")
                    .setName("RotationLayer")
                    .build();

            GraphicBuffer buffer = GraphicBuffer.createFromHardwareBuffer(
                    screenshotBuffer.getHardwareBuffer());

            t.setLayer(mAnimLeash, SCREEN_FREEZE_LAYER_BASE);
            t.setPosition(mAnimLeash, 0, 0);
            t.setAlpha(mAnimLeash, 1);
            t.show(mAnimLeash);

            t.setBuffer(mScreenshotLayer, buffer);
            t.setColorSpace(mScreenshotLayer, screenshotBuffer.getColorSpace());
            t.show(mScreenshotLayer);

            if (!isCustomRotate()) {
                mBackColorSurface = new SurfaceControl.Builder(session)
                        .setParent(rootLeash)
                        .setColorLayer()
                        .setCallsite("ShellRotationAnimation")
                        .setName("BackColorSurface")
                        .build();

                HardwareBuffer hardwareBuffer = screenshotBuffer.getHardwareBuffer();
                mStartLuma = getMedianBorderLuma(hardwareBuffer, screenshotBuffer.getColorSpace());

                t.setLayer(mBackColorSurface, -1);
                t.setColor(mBackColorSurface, new float[]{mStartLuma, mStartLuma, mStartLuma});
                t.setAlpha(mBackColorSurface, 1);
                t.show(mBackColorSurface);
            }

        } catch (Surface.OutOfResourcesException e) {
            Slog.w(TAG, "Unable to allocate freeze surface", e);
        }

        setRotation(t);
        t.apply();
    }

    private boolean isCustomRotate() {
        return mAnimHint == ROTATION_ANIMATION_CROSSFADE || mAnimHint == ROTATION_ANIMATION_JUMPCUT;
    }

    private void setRotation(SurfaceControl.Transaction t) {
        // Compute the transformation matrix that must be applied
        // to the snapshot to make it stay in the same original position
        // with the current screen rotation.
        int delta = deltaRotation(mEndRotation, mStartRotation);
        createRotationMatrix(delta, mStartWidth, mStartHeight, mSnapshotInitialMatrix);
        setRotationTransform(t, mSnapshotInitialMatrix);
    }

    private void setRotationTransform(SurfaceControl.Transaction t, Matrix matrix) {
        if (mScreenshotLayer == null) {
            return;
        }
        matrix.getValues(mTmpFloats);
        float x = mTmpFloats[Matrix.MTRANS_X];
        float y = mTmpFloats[Matrix.MTRANS_Y];
        t.setPosition(mScreenshotLayer, x, y);
        t.setMatrix(mScreenshotLayer,
                mTmpFloats[Matrix.MSCALE_X], mTmpFloats[Matrix.MSKEW_Y],
                mTmpFloats[Matrix.MSKEW_X], mTmpFloats[Matrix.MSCALE_Y]);

        t.setAlpha(mScreenshotLayer, (float) 1.0);
        t.show(mScreenshotLayer);
    }

    /**
     * Returns true if animating.
     */
    public boolean startAnimation(@NonNull ArrayList<Animator> animations,
            @NonNull Runnable finishCallback, float animationScale,
            @NonNull ShellExecutor mainExecutor, @NonNull ShellExecutor animExecutor) {
        if (mScreenshotLayer == null) {
            // Can't do animation.
            return false;
        }

        // TODO : Found a way to get right end luma and re-enable color frame animation.
        // End luma value is very not stable so it will cause more flicker is we run background
        // color frame animation.
        //mEndLuma = getLumaOfSurfaceControl(mEndBounds, mSurfaceControl);

        final boolean customRotate = isCustomRotate();
        if (customRotate) {
            mRotateExitAnimation = AnimationUtils.loadAnimation(mContext,
                    mAnimHint == ROTATION_ANIMATION_JUMPCUT ? R.anim.rotation_animation_jump_exit
                            : R.anim.rotation_animation_xfade_exit);
            mRotateEnterAnimation = AnimationUtils.loadAnimation(mContext,
                    R.anim.rotation_animation_enter);
            mRotateAlphaAnimation = AnimationUtils.loadAnimation(mContext,
                    R.anim.screen_rotate_alpha);
        } else {
            // Figure out how the screen has moved from the original rotation.
            int delta = deltaRotation(mEndRotation, mStartRotation);
            switch (delta) { /* Counter-Clockwise Rotations */
                case Surface.ROTATION_0:
                    mRotateExitAnimation = AnimationUtils.loadAnimation(mContext,
                            R.anim.screen_rotate_0_exit);
                    mRotateEnterAnimation = AnimationUtils.loadAnimation(mContext,
                            R.anim.rotation_animation_enter);
                    break;
                case Surface.ROTATION_90:
                    mRotateExitAnimation = AnimationUtils.loadAnimation(mContext,
                            R.anim.screen_rotate_plus_90_exit);
                    mRotateEnterAnimation = AnimationUtils.loadAnimation(mContext,
                            R.anim.screen_rotate_plus_90_enter);
                    break;
                case Surface.ROTATION_180:
                    mRotateExitAnimation = AnimationUtils.loadAnimation(mContext,
                            R.anim.screen_rotate_180_exit);
                    mRotateEnterAnimation = AnimationUtils.loadAnimation(mContext,
                            R.anim.screen_rotate_180_enter);
                    break;
                case Surface.ROTATION_270:
                    mRotateExitAnimation = AnimationUtils.loadAnimation(mContext,
                            R.anim.screen_rotate_minus_90_exit);
                    mRotateEnterAnimation = AnimationUtils.loadAnimation(mContext,
                            R.anim.screen_rotate_minus_90_enter);
                    break;
            }
        }

        mRotateExitAnimation.initialize(mEndWidth, mEndHeight, mStartWidth, mStartHeight);
        mRotateExitAnimation.restrictDuration(MAX_ANIMATION_DURATION);
        mRotateExitAnimation.scaleCurrentDuration(animationScale);
        mRotateEnterAnimation.initialize(mEndWidth, mEndHeight, mStartWidth, mStartHeight);
        mRotateEnterAnimation.restrictDuration(MAX_ANIMATION_DURATION);
        mRotateEnterAnimation.scaleCurrentDuration(animationScale);

        mTransaction = mTransactionPool.acquire();
        if (customRotate) {
            mRotateAlphaAnimation.initialize(mEndWidth, mEndHeight, mStartWidth, mStartHeight);
            mRotateAlphaAnimation.restrictDuration(MAX_ANIMATION_DURATION);
            mRotateAlphaAnimation.scaleCurrentDuration(animationScale);

            startScreenshotAlphaAnimation(animations, finishCallback, mainExecutor,
                    animExecutor);
            startDisplayRotation(animations, finishCallback, mainExecutor, animExecutor);
        } else {
            startDisplayRotation(animations, finishCallback, mainExecutor, animExecutor);
            startScreenshotRotationAnimation(animations, finishCallback, mainExecutor,
                    animExecutor);
            //startColorAnimation(mTransaction, animationScale);
        }

        return true;
    }

    private void startDisplayRotation(@NonNull ArrayList<Animator> animations,
            @NonNull Runnable finishCallback, @NonNull ShellExecutor mainExecutor,
            @NonNull ShellExecutor animExecutor) {
        startSurfaceAnimation(animations, mRotateEnterAnimation, mSurfaceControl, finishCallback,
                mTransactionPool, mainExecutor, animExecutor, null /* position */,
                0 /* cornerRadius */, null /* clipRect */);
    }

    private void startScreenshotRotationAnimation(@NonNull ArrayList<Animator> animations,
            @NonNull Runnable finishCallback, @NonNull ShellExecutor mainExecutor,
            @NonNull ShellExecutor animExecutor) {
        startSurfaceAnimation(animations, mRotateExitAnimation, mAnimLeash, finishCallback,
                mTransactionPool, mainExecutor, animExecutor, null /* position */,
                0 /* cornerRadius */, null /* clipRect */);
    }

    private void startScreenshotAlphaAnimation(@NonNull ArrayList<Animator> animations,
            @NonNull Runnable finishCallback, @NonNull ShellExecutor mainExecutor,
            @NonNull ShellExecutor animExecutor) {
        startSurfaceAnimation(animations, mRotateAlphaAnimation, mAnimLeash, finishCallback,
                mTransactionPool, mainExecutor, animExecutor, null /* position */,
                0 /* cornerRadius */, null /* clipRect */);
    }

    private void startColorAnimation(float animationScale, @NonNull ShellExecutor animExecutor) {
        int colorTransitionMs = mContext.getResources().getInteger(
                R.integer.config_screen_rotation_color_transition);
        final float[] rgbTmpFloat = new float[3];
        final int startColor = Color.rgb(mStartLuma, mStartLuma, mStartLuma);
        final int endColor = Color.rgb(mEndLuma, mEndLuma, mEndLuma);
        final long duration = colorTransitionMs * (long) animationScale;
        final Transaction t = mTransactionPool.acquire();

        final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        // Animation length is already expected to be scaled.
        va.overrideDurationScale(1.0f);
        va.setDuration(duration);
        va.addUpdateListener(animation -> {
            final long currentPlayTime = Math.min(va.getDuration(), va.getCurrentPlayTime());
            final float fraction = currentPlayTime / va.getDuration();
            applyColor(startColor, endColor, rgbTmpFloat, fraction, mBackColorSurface, t);
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                applyColor(startColor, endColor, rgbTmpFloat, 1f /* fraction */, mBackColorSurface,
                        t);
                mTransactionPool.release(t);
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                applyColor(startColor, endColor, rgbTmpFloat, 1f /* fraction */, mBackColorSurface,
                        t);
                mTransactionPool.release(t);
            }
        });
        animExecutor.execute(va::start);
    }

    public void kill() {
        Transaction t = mTransaction != null ? mTransaction : mTransactionPool.acquire();
        if (mAnimLeash.isValid()) {
            t.remove(mAnimLeash);
        }

        if (mScreenshotLayer != null) {
            if (mScreenshotLayer.isValid()) {
                t.remove(mScreenshotLayer);
            }
            mScreenshotLayer = null;
        }
        if (mBackColorSurface != null) {
            if (mBackColorSurface.isValid()) {
                t.remove(mBackColorSurface);
            }
            mBackColorSurface = null;
        }
        t.apply();
        mTransactionPool.release(t);
    }

    /**
     * Converts the provided {@link HardwareBuffer} and converts it to a bitmap to then sample the
     * luminance at the borders of the bitmap
     * @return the average luminance of all the pixels at the borders of the bitmap
     */
    private static float getMedianBorderLuma(HardwareBuffer hardwareBuffer, ColorSpace colorSpace) {
        // Cannot read content from buffer with protected usage.
        if (hardwareBuffer == null || hardwareBuffer.getFormat() != RGBA_8888
                || hasProtectedContent(hardwareBuffer)) {
            return 0;
        }

        ImageReader ir = ImageReader.newInstance(hardwareBuffer.getWidth(),
                hardwareBuffer.getHeight(), hardwareBuffer.getFormat(), 1);
        ir.getSurface().attachAndQueueBufferWithColorSpace(hardwareBuffer, colorSpace);
        Image image = ir.acquireLatestImage();
        if (image == null || image.getPlanes().length == 0) {
            return 0;
        }

        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        float[] borderLumas = new float[2 * width + 2 * height];

        // Grab the top and bottom borders
        int l = 0;
        for (int x = 0; x < width; x++) {
            borderLumas[l++] = getPixelLuminance(buffer, x, 0, pixelStride, rowStride);
            borderLumas[l++] = getPixelLuminance(buffer, x, height - 1, pixelStride, rowStride);
        }

        // Grab the left and right borders
        for (int y = 0; y < height; y++) {
            borderLumas[l++] = getPixelLuminance(buffer, 0, y, pixelStride, rowStride);
            borderLumas[l++] = getPixelLuminance(buffer, width - 1, y, pixelStride, rowStride);
        }

        // Cleanup
        ir.close();

        // Oh, is this too simple and inefficient for you?
        // How about implementing a O(n) solution? https://en.wikipedia.org/wiki/Median_of_medians
        Arrays.sort(borderLumas);
        return borderLumas[borderLumas.length / 2];
    }

    /**
     * @return whether the hardwareBuffer passed in is marked as protected.
     */
    private static boolean hasProtectedContent(HardwareBuffer hardwareBuffer) {
        return (hardwareBuffer.getUsage() & USAGE_PROTECTED_CONTENT) == USAGE_PROTECTED_CONTENT;
    }

    private static float getPixelLuminance(ByteBuffer buffer, int x, int y,
            int pixelStride, int rowStride) {
        int offset = y * rowStride + x * pixelStride;
        int pixel = 0;
        pixel |= (buffer.get(offset) & 0xff) << 16;     // R
        pixel |= (buffer.get(offset + 1) & 0xff) << 8;  // G
        pixel |= (buffer.get(offset + 2) & 0xff);       // B
        pixel |= (buffer.get(offset + 3) & 0xff) << 24; // A
        return Color.valueOf(pixel).luminance();
    }

    /**
     * Gets the average border luma by taking a screenshot of the {@param surfaceControl}.
     * @see #getMedianBorderLuma(HardwareBuffer, ColorSpace)
     */
    private static float getLumaOfSurfaceControl(Rect bounds, SurfaceControl surfaceControl) {
        if (surfaceControl ==  null) {
            return 0;
        }

        Rect crop = new Rect(0, 0, bounds.width(), bounds.height());
        SurfaceControl.ScreenshotHardwareBuffer buffer =
                SurfaceControl.captureLayers(surfaceControl, crop, 1);
        if (buffer == null) {
            return 0;
        }

        return getMedianBorderLuma(buffer.getHardwareBuffer(), buffer.getColorSpace());
    }

    private static void createRotationMatrix(int rotation, int width, int height,
            Matrix outMatrix) {
        switch (rotation) {
            case Surface.ROTATION_0:
                outMatrix.reset();
                break;
            case Surface.ROTATION_90:
                outMatrix.setRotate(90, 0, 0);
                outMatrix.postTranslate(height, 0);
                break;
            case Surface.ROTATION_180:
                outMatrix.setRotate(180, 0, 0);
                outMatrix.postTranslate(width, height);
                break;
            case Surface.ROTATION_270:
                outMatrix.setRotate(270, 0, 0);
                outMatrix.postTranslate(0, width);
                break;
        }
    }

    private static void applyColor(int startColor, int endColor, float[] rgbFloat,
            float fraction, SurfaceControl surface, SurfaceControl.Transaction t) {
        final int color = (Integer) ArgbEvaluator.getInstance().evaluate(fraction, startColor,
                endColor);
        Color middleColor = Color.valueOf(color);
        rgbFloat[0] = middleColor.red();
        rgbFloat[1] = middleColor.green();
        rgbFloat[2] = middleColor.blue();
        if (surface.isValid()) {
            t.setColor(surface, rgbFloat);
        }
        t.apply();
    }
}
