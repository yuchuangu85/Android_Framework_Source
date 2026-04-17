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

package com.android.internal.policy;

import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE;
import static android.view.WindowManager.TRANSIT_OPEN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ResourceId;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Slog;
import android.view.InflateException;
import android.view.SurfaceControl;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManager.TransitionType;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.animation.ClipRectAnimation;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import android.window.ScreenCaptureInternal;

import com.android.internal.R;

import java.nio.ByteBuffer;
import java.util.List;

/** @hide */
public class TransitionAnimation {
    public static final int WALLPAPER_TRANSITION_NONE = 0;
    /** Wallpaper keeps visible in a CHANGE transition. */
    public static final int WALLPAPER_TRANSITION_CHANGE = 1;
    /** Wallpaper becomes visible. */
    public static final int WALLPAPER_TRANSITION_OPEN = 2;
    /** Wallpaper becomes invisible. */
    public static final int WALLPAPER_TRANSITION_CLOSE = 3;
    /** Wallpaper keeps visible in a OPEN transition. */
    public static final int WALLPAPER_TRANSITION_INTRA_OPEN = 4;
    /** Wallpaper keeps visible in a CLOSE transition. */
    public static final int WALLPAPER_TRANSITION_INTRA_CLOSE = 5;

    // These are the possible states for the enter/exit activities during a thumbnail transition
    private static final int THUMBNAIL_TRANSITION_ENTER_SCALE_UP = 0;
    private static final int THUMBNAIL_TRANSITION_EXIT_SCALE_UP = 1;
    private static final int THUMBNAIL_TRANSITION_ENTER_SCALE_DOWN = 2;
    private static final int THUMBNAIL_TRANSITION_EXIT_SCALE_DOWN = 3;

    /**
     * Maximum duration for the clip reveal animation. This is used when there is a lot of movement
     * involved, to make it more understandable.
     */
    private static final int MAX_CLIP_REVEAL_TRANSITION_DURATION = 420;
    private static final int CLIP_REVEAL_TRANSLATION_Y_DP = 8;
    private static final int THUMBNAIL_APP_TRANSITION_DURATION = 336;

    public static final int DEFAULT_APP_TRANSITION_DURATION = 336;

    /**
     * Maximum allowed animation duration (in milliseconds).
     * Used to guard against excessively long or malformed animations.
     */
    public static final int MAX_ANIMATION_DURATION = 1500;

    /** Fraction of animation at which the recents thumbnail stays completely transparent */
    private static final float RECENTS_THUMBNAIL_FADEIN_FRACTION = 0.5f;
    /** Fraction of animation at which the recents thumbnail becomes completely transparent */
    private static final float RECENTS_THUMBNAIL_FADEOUT_FRACTION = 0.5f;

    /** Interpolator to be used for animations that respond directly to a touch */
    static final Interpolator TOUCH_RESPONSE_INTERPOLATOR =
            new PathInterpolator(0.3f, 0f, 0.1f, 1f);

    private static final String DEFAULT_PACKAGE = "android";

    private final Context mContext;
    private final String mTag;

    private final LogDecelerateInterpolator mInterpolator = new LogDecelerateInterpolator(100, 0);
    /** Interpolator to be used for animations that respond directly to a touch */
    private final Interpolator mTouchResponseInterpolator =
            new PathInterpolator(0.3f, 0f, 0.1f, 1f);
    private final Interpolator mClipHorizontalInterpolator = new PathInterpolator(0, 0, 0.4f, 1f);
    private final Interpolator mDecelerateInterpolator;
    private final Interpolator mFastOutLinearInInterpolator;
    private final Interpolator mLinearOutSlowInInterpolator;
    private final Interpolator mThumbnailFadeInInterpolator;
    private final Interpolator mThumbnailFadeOutInterpolator;
    private final Rect mTmpFromClipRect = new Rect();
    private final Rect mTmpToClipRect = new Rect();
    private final Rect mTmpRect = new Rect();

    private final int mClipRevealTranslationY;
    private final int mConfigShortAnimTime;
    private final int mDefaultWindowAnimationStyleResId;

    private final boolean mDebug;

    public TransitionAnimation(Context context, boolean debug, String tag) {
        mContext = context;
        mDebug = debug;
        mTag = tag;

        mDecelerateInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.decelerate_cubic);
        mFastOutLinearInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_linear_in);
        mLinearOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.linear_out_slow_in);
        mThumbnailFadeInInterpolator = input -> {
            // Linear response for first fraction, then complete after that.
            if (input < RECENTS_THUMBNAIL_FADEIN_FRACTION) {
                return 0f;
            }
            float t = (input - RECENTS_THUMBNAIL_FADEIN_FRACTION)
                    / (1f - RECENTS_THUMBNAIL_FADEIN_FRACTION);
            return mFastOutLinearInInterpolator.getInterpolation(t);
        };
        mThumbnailFadeOutInterpolator = input -> {
            // Linear response for first fraction, then complete after that.
            if (input < RECENTS_THUMBNAIL_FADEOUT_FRACTION) {
                float t = input / RECENTS_THUMBNAIL_FADEOUT_FRACTION;
                return mLinearOutSlowInInterpolator.getInterpolation(t);
            }
            return 1f;
        };

        mClipRevealTranslationY = (int) (CLIP_REVEAL_TRANSLATION_Y_DP
                * mContext.getResources().getDisplayMetrics().density);
        mConfigShortAnimTime = context.getResources().getInteger(
                com.android.internal.R.integer.config_shortAnimTime);

        final TypedArray windowStyle = mContext.getTheme().obtainStyledAttributes(
                com.android.internal.R.styleable.Window);
        mDefaultWindowAnimationStyleResId = windowStyle.getResourceId(
                com.android.internal.R.styleable.Window_windowAnimationStyle, 0);
        windowStyle.recycle();
    }

    /** Loads keyguard animation by transition flags and check it is on wallpaper or not. */
    public Animation loadKeyguardExitAnimation(int transitionFlags, boolean onWallpaper) {
        if ((transitionFlags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION) != 0) {
            return null;
        }
        final boolean toShade =
                (transitionFlags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE) != 0;
        final boolean subtle =
                (transitionFlags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION) != 0;
        return createHiddenByKeyguardExit(mContext, mInterpolator, onWallpaper, toShade, subtle);
    }

    /** Load keyguard unocclude animation for user. */
    @Nullable
    public Animation loadKeyguardUnoccludeAnimation(int userId) {
        return loadDefaultAnimationRes(com.android.internal.R.anim.wallpaper_open_exit, userId);
    }

    /** Load voice activity open animation for user. */
    @Nullable
    public Animation loadVoiceActivityOpenAnimation(boolean enter, int userId) {
        return loadDefaultAnimationRes(enter
                ? com.android.internal.R.anim.voice_activity_open_enter
                : com.android.internal.R.anim.voice_activity_open_exit, userId);
    }

    /** Load voice activity exit animation for user. */
    @Nullable
    public Animation loadVoiceActivityExitAnimation(boolean enter, int userId) {
        return loadDefaultAnimationRes(enter
                ? com.android.internal.R.anim.voice_activity_close_enter
                : com.android.internal.R.anim.voice_activity_close_exit, userId);
    }

    @Nullable
    public Animation loadAppTransitionAnimation(String packageName, int resId) {
        return loadAnimationRes(packageName, resId);
    }

    /** Load cross profile app enter animation for user. */
    @Nullable
    public Animation loadCrossProfileAppEnterAnimation(int userId) {
        return loadAnimationRes(DEFAULT_PACKAGE,
                com.android.internal.R.anim.task_open_enter_cross_profile_apps, userId);
    }

    @Nullable
    public Animation loadCrossProfileAppThumbnailEnterAnimation() {
        return loadAnimationRes(
                DEFAULT_PACKAGE, com.android.internal.R.anim.cross_profile_apps_thumbnail_enter);
    }

    @Nullable
    public Animation createCrossProfileAppsThumbnailAnimationLocked(Rect appRect) {
        final Animation animation = loadCrossProfileAppThumbnailEnterAnimation();
        return prepareThumbnailAnimationWithDuration(animation, appRect.width(),
                appRect.height(), 0, null);
    }

    /** Load animation by resource Id from specific package for user. */
    @Nullable
    public Animation loadAnimationRes(String packageName, int resId, int userId) {
        if (ResourceId.isValid(resId)) {
            AttributeCache.Entry ent = getCachedAnimations(packageName, resId, userId);
            if (ent != null) {
                return loadAnimationSafely(ent.context, resId, mTag);
            }
        }
        return null;
    }

    /** Same as {@code loadAnimationRes} for current user. */
    @Nullable
    public Animation loadAnimationRes(String packageName, int resId) {
        return loadAnimationRes(packageName, resId, UserHandle.USER_CURRENT);
    }

    /** Load animation by resource Id from android package for user. */
    @Nullable
    public Animation loadDefaultAnimationRes(int resId, int userId) {
        return loadAnimationRes(DEFAULT_PACKAGE, resId, userId);
    }

    /** Same as {@code loadDefaultAnimationRes} for current user. */
    @Nullable
    public Animation loadDefaultAnimationRes(int resId) {
        return loadAnimationRes(DEFAULT_PACKAGE, resId, UserHandle.USER_CURRENT);
    }

    /** Load animation by attribute Id from specific LayoutParams */
    @Nullable
    public Animation loadAnimationAttr(LayoutParams lp, int animAttr) {
        int resId = Resources.ID_NULL;
        Context context = mContext;
        if (animAttr >= 0) {
            AttributeCache.Entry ent = getCachedAnimations(lp);
            if (ent != null) {
                context = ent.context;
                resId = ent.array.getResourceId(animAttr, 0);
            }
        }
        if (ResourceId.isValid(resId)) {
            return loadAnimationSafely(context, resId, mTag);
        }
        return null;
    }

    /** Get animation resId by attribute Id from specific LayoutParams */
    public int getAnimationResId(LayoutParams lp, int animAttr) {
        int resId = Resources.ID_NULL;
        if (animAttr >= 0) {
            AttributeCache.Entry ent = getCachedAnimations(lp);
            if (ent != null) {
                resId = ent.array.getResourceId(animAttr, 0);
            }
        }
        return resId;
    }

    /** Get default animation resId */
    public int getDefaultAnimationResId(int animAttr) {
        int resId = Resources.ID_NULL;
        if (animAttr >= 0) {
            AttributeCache.Entry ent = getCachedAnimations(DEFAULT_PACKAGE,
                    mDefaultWindowAnimationStyleResId);
            if (ent != null) {
                resId = ent.array.getResourceId(animAttr, 0);
            }
        }
        return resId;
    }

    /**
     * Load animation by attribute Id from a specific AnimationStyle resource.
     *
     * @param translucent {@code true} if we're sure that the animation is applied on a translucent
     *                    window container, {@code false} otherwise.
     */
    @Nullable
    public Animation loadAnimationAttr(String packageName, int animStyleResId, int animAttr,
            boolean translucent) {
        if (animStyleResId == 0) {
            return null;
        }
        int resId = Resources.ID_NULL;
        Context context = mContext;
        if (animAttr >= 0) {
            packageName = packageName != null ? packageName : DEFAULT_PACKAGE;
            AttributeCache.Entry ent = getCachedAnimations(packageName, animStyleResId);
            if (ent != null) {
                context = ent.context;
                resId = ent.array.getResourceId(animAttr, 0);
            }
        }
        if (translucent) {
            resId = updateToTranslucentAnimIfNeeded(resId);
        }
        if (ResourceId.isValid(resId)) {
            return loadAnimationSafely(context, resId, mTag);
        }
        return null;
    }

    /** Load animation by attribute Id from android package. */
    @Nullable
    public Animation loadDefaultAnimationAttr(int animAttr, boolean translucent) {
        return loadAnimationAttr(DEFAULT_PACKAGE, mDefaultWindowAnimationStyleResId, animAttr,
                translucent);
    }

    @Nullable
    private AttributeCache.Entry getCachedAnimations(LayoutParams lp) {
        if (mDebug) {
            Slog.v(mTag, "Loading animations: layout params pkg="
                    + (lp != null ? lp.packageName : null)
                    + " resId=0x" + (lp != null ? Integer.toHexString(lp.windowAnimations) : null));
        }
        if (lp != null && lp.windowAnimations != 0) {
            // If this is a system resource, don't try to load it from the
            // application resources.  It is nice to avoid loading application
            // resources if we can.
            String packageName = lp.packageName != null ? lp.packageName : DEFAULT_PACKAGE;
            int resId = getAnimationStyleResId(lp);
            if ((resId & 0xFF000000) == 0x01000000) {
                packageName = DEFAULT_PACKAGE;
            }
            if (mDebug) {
                Slog.v(mTag, "Loading animations: picked package=" + packageName);
            }
            return AttributeCache.instance().get(packageName, resId,
                    com.android.internal.R.styleable.WindowAnimation);
        }
        return null;
    }

    @Nullable
    private AttributeCache.Entry getCachedAnimations(String packageName, int resId, int userId) {
        if (mDebug) {
            Slog.v(mTag, "Loading animations: package=" + packageName + " resId=0x"
                    + Integer.toHexString(resId) + " for user=" + userId);
        }
        if (packageName != null) {
            if ((resId & 0xFF000000) == 0x01000000) {
                packageName = DEFAULT_PACKAGE;
            }
            if (mDebug) {
                Slog.v(mTag, "Loading animations: picked package="
                        + packageName);
            }
            return AttributeCache.instance().get(packageName, resId,
                    com.android.internal.R.styleable.WindowAnimation, userId);
        }
        return null;
    }

    @Nullable
    private AttributeCache.Entry getCachedAnimations(String packageName, int resId) {
        return getCachedAnimations(packageName, resId, UserHandle.USER_CURRENT);
    }

    /** Returns window animation style ID from {@link LayoutParams} or from system in some cases */
    public int getAnimationStyleResId(@NonNull LayoutParams lp) {
        int resId = lp.windowAnimations;
        if (lp.type == LayoutParams.TYPE_APPLICATION_STARTING) {
            // Note that we don't want application to customize starting window animation.
            // Since this window is specific for displaying while app starting,
            // application should not change its animation directly.
            // In this case, it will use system resource to get default animation.
            resId = mDefaultWindowAnimationStyleResId;
        }
        return resId;
    }

    public Animation createRelaunchAnimation(Rect containingFrame, Rect contentInsets,
            Rect startRect) {
        setupDefaultNextAppTransitionStartRect(startRect, mTmpFromClipRect);
        final int left = mTmpFromClipRect.left;
        final int top = mTmpFromClipRect.top;
        mTmpFromClipRect.offset(-left, -top);
        // TODO: Isn't that strange that we ignore exact position of the containingFrame?
        mTmpToClipRect.set(0, 0, containingFrame.width(), containingFrame.height());
        AnimationSet set = new AnimationSet(true);
        float fromWidth = mTmpFromClipRect.width();
        float toWidth = mTmpToClipRect.width();
        float fromHeight = mTmpFromClipRect.height();
        // While the window might span the whole display, the actual content will be cropped to the
        // system decoration frame, for example when the window is docked. We need to take into
        // account the visible height when constructing the animation.
        float toHeight = mTmpToClipRect.height() - contentInsets.top - contentInsets.bottom;
        int translateAdjustment = 0;
        if (fromWidth <= toWidth && fromHeight <= toHeight) {
            // The final window is larger in both dimensions than current window (e.g. we are
            // maximizing), so we can simply unclip the new window and there will be no disappearing
            // frame.
            set.addAnimation(new ClipRectAnimation(mTmpFromClipRect, mTmpToClipRect));
        } else {
            // The disappearing window has one larger dimension. We need to apply scaling, so the
            // first frame of the entry animation matches the old window.
            set.addAnimation(new ScaleAnimation(fromWidth / toWidth, 1, fromHeight / toHeight, 1));
            // We might not be going exactly full screen, but instead be aligned under the status
            // bar using cropping. We still need to account for the cropped part, which will also
            // be scaled.
            translateAdjustment = (int) (contentInsets.top * fromHeight / toHeight);
        }

        // We animate the translation from the old position of the removed window, to the new
        // position of the added window. The latter might not be full screen, for example docked for
        // docked windows.
        TranslateAnimation translate = new TranslateAnimation(left - containingFrame.left,
                0, top - containingFrame.top - translateAdjustment, 0);
        set.addAnimation(translate);
        set.setDuration(DEFAULT_APP_TRANSITION_DURATION);
        set.setZAdjustment(Animation.ZORDER_TOP);
        return set;
    }

    private void setupDefaultNextAppTransitionStartRect(Rect startRect, Rect rect) {
        if (startRect == null) {
            Slog.e(mTag, "Starting rect for app requested, but none available", new Throwable());
            rect.setEmpty();
        } else {
            rect.set(startRect);
        }
    }

    public Animation createClipRevealAnimationLocked(@TransitionType int transit,
            int wallpaperTransit, boolean enter, Rect appFrame, Rect displayFrame, Rect startRect) {
        final Animation anim;
        if (enter) {
            final int appWidth = appFrame.width();
            final int appHeight = appFrame.height();

            // mTmpRect will contain an area around the launcher icon that was pressed. We will
            // clip reveal from that area in the final area of the app.
            setupDefaultNextAppTransitionStartRect(startRect, mTmpRect);

            float t = 0f;
            if (appHeight > 0) {
                t = (float) mTmpRect.top / displayFrame.height();
            }
            int translationY = mClipRevealTranslationY + (int) (displayFrame.height() / 7f * t);
            int translationX = 0;
            int translationYCorrection = translationY;
            int centerX = mTmpRect.centerX();
            int centerY = mTmpRect.centerY();
            int halfWidth = mTmpRect.width() / 2;
            int halfHeight = mTmpRect.height() / 2;
            int clipStartX = centerX - halfWidth - appFrame.left;
            int clipStartY = centerY - halfHeight - appFrame.top;
            boolean cutOff = false;

            // If the starting rectangle is fully or partially outside of the target rectangle, we
            // need to start the clipping at the edge and then achieve the rest with translation
            // and extending the clip rect from that edge.
            if (appFrame.top > centerY - halfHeight) {
                translationY = (centerY - halfHeight) - appFrame.top;
                translationYCorrection = 0;
                clipStartY = 0;
                cutOff = true;
            }
            if (appFrame.left > centerX - halfWidth) {
                translationX = (centerX - halfWidth) - appFrame.left;
                clipStartX = 0;
                cutOff = true;
            }
            if (appFrame.right < centerX + halfWidth) {
                translationX = (centerX + halfWidth) - appFrame.right;
                clipStartX = appWidth - mTmpRect.width();
                cutOff = true;
            }
            final long duration = calculateClipRevealTransitionDuration(cutOff, translationX,
                    translationY, displayFrame);

            // Clip third of the from size of launch icon, expand to full width/height
            Animation clipAnimLR = new ClipRectLRAnimation(
                    clipStartX, clipStartX + mTmpRect.width(), 0, appWidth);
            clipAnimLR.setInterpolator(mClipHorizontalInterpolator);
            clipAnimLR.setDuration((long) (duration / 2.5f));

            TranslateAnimation translate = new TranslateAnimation(translationX, 0, translationY, 0);
            translate.setInterpolator(cutOff ? mTouchResponseInterpolator
                    : mLinearOutSlowInInterpolator);
            translate.setDuration(duration);

            Animation clipAnimTB = new ClipRectTBAnimation(
                    clipStartY, clipStartY + mTmpRect.height(),
                    0, appHeight,
                    translationYCorrection, 0,
                    mLinearOutSlowInInterpolator);
            clipAnimTB.setInterpolator(mTouchResponseInterpolator);
            clipAnimTB.setDuration(duration);

            // Quick fade-in from icon to app window
            final long alphaDuration = duration / 4;
            AlphaAnimation alpha = new AlphaAnimation(0.5f, 1);
            alpha.setDuration(alphaDuration);
            alpha.setInterpolator(mLinearOutSlowInInterpolator);

            AnimationSet set = new AnimationSet(false);
            set.addAnimation(clipAnimLR);
            set.addAnimation(clipAnimTB);
            set.addAnimation(translate);
            set.addAnimation(alpha);
            set.setZAdjustment(Animation.ZORDER_TOP);
            set.initialize(appWidth, appHeight, appWidth, appHeight);
            anim = set;
        } else {
            final long duration;
            if (transit == TRANSIT_OPEN || transit == TRANSIT_CLOSE) {
                duration = mConfigShortAnimTime;
            } else {
                duration = DEFAULT_APP_TRANSITION_DURATION;
            }
            if (wallpaperTransit == WALLPAPER_TRANSITION_INTRA_OPEN
                    || wallpaperTransit == WALLPAPER_TRANSITION_INTRA_CLOSE) {
                // If we are on top of the wallpaper, we need an animation that
                // correctly handles the wallpaper staying static behind all of
                // the animated elements.  To do this, will just have the existing
                // element fade out.
                anim = new AlphaAnimation(1, 0);
            } else {
                // For normal animations, the exiting element just holds in place.
                anim = new AlphaAnimation(1, 1);
            }
            anim.setInterpolator(mDecelerateInterpolator);
            anim.setDuration(duration);
            anim.setFillAfter(true);
        }
        return anim;
    }

    public Animation createScaleUpAnimationLocked(@TransitionType int transit, int wallpaperTransit,
            boolean enter, Rect containingFrame, Rect startRect) {
        Animation a;
        setupDefaultNextAppTransitionStartRect(startRect, mTmpRect);
        final int appWidth = containingFrame.width();
        final int appHeight = containingFrame.height();
        if (enter) {
            // Entering app zooms out from the center of the initial rect.
            float scaleW = mTmpRect.width() / (float) appWidth;
            float scaleH = mTmpRect.height() / (float) appHeight;
            Animation scale = new ScaleAnimation(scaleW, 1, scaleH, 1,
                    computePivot(mTmpRect.left, scaleW),
                    computePivot(mTmpRect.top, scaleH));
            scale.setInterpolator(mDecelerateInterpolator);

            Animation alpha = new AlphaAnimation(0, 1);
            alpha.setInterpolator(mThumbnailFadeOutInterpolator);

            AnimationSet set = new AnimationSet(false);
            set.addAnimation(scale);
            set.addAnimation(alpha);
            a = set;
        } else  if (wallpaperTransit == WALLPAPER_TRANSITION_INTRA_OPEN
                || wallpaperTransit == WALLPAPER_TRANSITION_INTRA_CLOSE) {
            // If we are on top of the wallpaper, we need an animation that
            // correctly handles the wallpaper staying static behind all of
            // the animated elements.  To do this, will just have the existing
            // element fade out.
            a = new AlphaAnimation(1, 0);
        } else {
            // For normal animations, the exiting element just holds in place.
            a = new AlphaAnimation(1, 1);
        }

        // Pick the desired duration.  If this is an inter-activity transition,
        // it  is the standard duration for that.  Otherwise we use the longer
        // task transition duration.
        final long duration;
        if (transit == TRANSIT_OPEN || transit == TRANSIT_CLOSE) {
            duration = mConfigShortAnimTime;
        } else {
            duration = DEFAULT_APP_TRANSITION_DURATION;
        }
        a.setDuration(duration);
        a.setFillAfter(true);
        a.setInterpolator(mDecelerateInterpolator);
        a.initialize(appWidth, appHeight, appWidth, appHeight);
        return a;
    }

    /**
     * This animation is created when we are doing a thumbnail transition, for the activity that is
     * leaving, and the activity that is entering.
     */
    public Animation createThumbnailEnterExitAnimationLocked(boolean enter, boolean scaleUp,
            Rect containingFrame, @TransitionType int transit, int wallpaperTransit,
            HardwareBuffer thumbnailHeader, Rect startRect) {
        final int appWidth = containingFrame.width();
        final int appHeight = containingFrame.height();
        Animation a;
        setupDefaultNextAppTransitionStartRect(startRect, mTmpRect);
        final int thumbWidthI = thumbnailHeader != null ? thumbnailHeader.getWidth() : appWidth;
        final float thumbWidth = thumbWidthI > 0 ? thumbWidthI : 1;
        final int thumbHeightI = thumbnailHeader != null ? thumbnailHeader.getHeight() : appHeight;
        final float thumbHeight = thumbHeightI > 0 ? thumbHeightI : 1;
        final int thumbTransitState = getThumbnailTransitionState(enter, scaleUp);

        switch (thumbTransitState) {
            case THUMBNAIL_TRANSITION_ENTER_SCALE_UP: {
                // Entering app scales up with the thumbnail
                float scaleW = thumbWidth / appWidth;
                float scaleH = thumbHeight / appHeight;
                a = new ScaleAnimation(scaleW, 1, scaleH, 1,
                        computePivot(mTmpRect.left, scaleW),
                        computePivot(mTmpRect.top, scaleH));
                break;
            }
            case THUMBNAIL_TRANSITION_EXIT_SCALE_UP: {
                // Exiting app while the thumbnail is scaling up should fade or stay in place
                if (wallpaperTransit == WALLPAPER_TRANSITION_INTRA_OPEN) {
                    // Fade out while bringing up selected activity. This keeps the
                    // current activity from showing through a launching wallpaper
                    // activity.
                    a = new AlphaAnimation(1, 0);
                } else {
                    // noop animation
                    a = new AlphaAnimation(1, 1);
                }
                break;
            }
            case THUMBNAIL_TRANSITION_ENTER_SCALE_DOWN: {
                // Entering the other app, it should just be visible while we scale the thumbnail
                // down above it
                a = new AlphaAnimation(1, 1);
                break;
            }
            case THUMBNAIL_TRANSITION_EXIT_SCALE_DOWN: {
                // Exiting the current app, the app should scale down with the thumbnail
                float scaleW = thumbWidth / appWidth;
                float scaleH = thumbHeight / appHeight;
                Animation scale = new ScaleAnimation(1, scaleW, 1, scaleH,
                        computePivot(mTmpRect.left, scaleW),
                        computePivot(mTmpRect.top, scaleH));

                Animation alpha = new AlphaAnimation(1, 0);

                AnimationSet set = new AnimationSet(true);
                set.addAnimation(scale);
                set.addAnimation(alpha);
                set.setZAdjustment(Animation.ZORDER_TOP);
                a = set;
                break;
            }
            default:
                throw new RuntimeException("Invalid thumbnail transition state");
        }

        // Pick the desired duration. If this is an inter-activity transition, it is the standard
        // duration for that. Otherwise, use the longer task transition duration.
        final int duration;
        if (transit == TRANSIT_OPEN || transit == TRANSIT_CLOSE) {
            duration = mConfigShortAnimTime;
        } else {
            duration = DEFAULT_APP_TRANSITION_DURATION;
        }
        return prepareThumbnailAnimationWithDuration(a, appWidth, appHeight, duration,
                mDecelerateInterpolator);
    }

    /**
     * This animation runs for the thumbnail that gets cross faded with the enter/exit activity
     * when a thumbnail is specified with the pending animation override.
     */
    public Animation createThumbnailAspectScaleAnimationLocked(Rect appRect,
            @Nullable Rect contentInsets, HardwareBuffer thumbnailHeader, int orientation,
            Rect startRect, Rect defaultStartRect, boolean scaleUp) {
        Animation a;
        final int thumbWidthI = thumbnailHeader.getWidth();
        final float thumbWidth = thumbWidthI > 0 ? thumbWidthI : 1;
        final int thumbHeightI = thumbnailHeader.getHeight();
        final int appWidth = appRect.width();

        float scaleW = appWidth / thumbWidth;
        getNextAppTransitionStartRect(startRect, defaultStartRect, mTmpRect);
        final float fromX;
        float fromY;
        final float toX;
        float toY;
        final float pivotX;
        final float pivotY;
        if (shouldScaleDownThumbnailTransition(orientation)) {
            fromX = mTmpRect.left;
            fromY = mTmpRect.top;

            // For the curved translate animation to work, the pivot points needs to be at the
            // same absolute position as the one from the real surface.
            toX = mTmpRect.width() / 2 * (scaleW - 1f) + appRect.left;
            toY = appRect.height() / 2 * (1 - 1 / scaleW) + appRect.top;
            pivotX = mTmpRect.width() / 2;
            pivotY = appRect.height() / 2 / scaleW;
        } else {
            pivotX = 0;
            pivotY = 0;
            fromX = mTmpRect.left;
            fromY = mTmpRect.top;
            toX = appRect.left;
            toY = appRect.top;
        }
        if (scaleUp) {
            // Animation up from the thumbnail to the full screen
            Animation scale = new ScaleAnimation(1f, scaleW, 1f, scaleW, pivotX, pivotY);
            scale.setInterpolator(TOUCH_RESPONSE_INTERPOLATOR);
            scale.setDuration(THUMBNAIL_APP_TRANSITION_DURATION);
            Animation alpha = new AlphaAnimation(1f, 0f);
            alpha.setInterpolator(mThumbnailFadeOutInterpolator);
            alpha.setDuration(THUMBNAIL_APP_TRANSITION_DURATION);
            Animation translate = createCurvedMotion(fromX, toX, fromY, toY);
            translate.setInterpolator(TOUCH_RESPONSE_INTERPOLATOR);
            translate.setDuration(THUMBNAIL_APP_TRANSITION_DURATION);

            mTmpFromClipRect.set(0, 0, thumbWidthI, thumbHeightI);
            mTmpToClipRect.set(appRect);

            // Containing frame is in screen space, but we need the clip rect in the
            // app space.
            mTmpToClipRect.offsetTo(0, 0);
            mTmpToClipRect.right = (int) (mTmpToClipRect.right / scaleW);
            mTmpToClipRect.bottom = (int) (mTmpToClipRect.bottom / scaleW);

            if (contentInsets != null) {
                mTmpToClipRect.inset((int) (-contentInsets.left * scaleW),
                        (int) (-contentInsets.top * scaleW),
                        (int) (-contentInsets.right * scaleW),
                        (int) (-contentInsets.bottom * scaleW));
            }

            Animation clipAnim = new ClipRectAnimation(mTmpFromClipRect, mTmpToClipRect);
            clipAnim.setInterpolator(TOUCH_RESPONSE_INTERPOLATOR);
            clipAnim.setDuration(THUMBNAIL_APP_TRANSITION_DURATION);

            // This AnimationSet uses the Interpolators assigned above.
            AnimationSet set = new AnimationSet(false);
            set.addAnimation(scale);
            set.addAnimation(alpha);
            set.addAnimation(translate);
            set.addAnimation(clipAnim);
            a = set;
        } else {
            // Animation down from the full screen to the thumbnail
            Animation scale = new ScaleAnimation(scaleW, 1f, scaleW, 1f, pivotX, pivotY);
            scale.setInterpolator(TOUCH_RESPONSE_INTERPOLATOR);
            scale.setDuration(THUMBNAIL_APP_TRANSITION_DURATION);
            Animation alpha = new AlphaAnimation(0f, 1f);
            alpha.setInterpolator(mThumbnailFadeInInterpolator);
            alpha.setDuration(THUMBNAIL_APP_TRANSITION_DURATION);
            Animation translate = createCurvedMotion(toX, fromX, toY, fromY);
            translate.setInterpolator(TOUCH_RESPONSE_INTERPOLATOR);
            translate.setDuration(THUMBNAIL_APP_TRANSITION_DURATION);

            // This AnimationSet uses the Interpolators assigned above.
            AnimationSet set = new AnimationSet(false);
            set.addAnimation(scale);
            set.addAnimation(alpha);
            set.addAnimation(translate);
            a = set;

        }
        return prepareThumbnailAnimationWithDuration(a, appWidth, appRect.height(), 0,
                null);
    }

    /**
     * Creates an overlay with a background color and a thumbnail for the cross profile apps
     * animation.
     */
    public HardwareBuffer createCrossProfileAppsThumbnail(
            Drawable thumbnailDrawable, Rect frame) {
        final int width = frame.width();
        final int height = frame.height();

        final Picture picture = new Picture();
        final Canvas canvas = picture.beginRecording(width, height);
        canvas.drawColor(Color.argb(0.6f, 0, 0, 0));
        final int thumbnailSize = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.cross_profile_apps_thumbnail_size);
        thumbnailDrawable.setBounds(
                (width - thumbnailSize) / 2,
                (height - thumbnailSize) / 2,
                (width + thumbnailSize) / 2,
                (height + thumbnailSize) / 2);
        thumbnailDrawable.setTint(mContext.getColor(android.R.color.white));
        thumbnailDrawable.draw(canvas);
        picture.endRecording();

        return Bitmap.createBitmap(picture).getHardwareBuffer();
    }

    private void getNextAppTransitionStartRect(Rect startRect, Rect defaultStartRect, Rect rect) {
        if (startRect == null && defaultStartRect == null) {
            Slog.e(mTag, "Starting rect for container not available", new Throwable());
            rect.setEmpty();
        } else {
            rect.set(startRect != null ? startRect : defaultStartRect);
        }
    }

    /**
     * @return whether the transition should show the thumbnail being scaled down.
     */
    private boolean shouldScaleDownThumbnailTransition(int orientation) {
        return orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    private static int updateToTranslucentAnimIfNeeded(int anim) {
        if (anim == R.anim.activity_open_enter) {
            return R.anim.activity_translucent_open_enter;
        }
        if (anim == R.anim.activity_close_exit) {
            return R.anim.activity_translucent_close_exit;
        }
        return anim;
    }

    /**
     * Calculates the duration for the clip reveal animation. If the clip is "cut off", meaning that
     * the start rect is outside of the target rect, and there is a lot of movement going on.
     *
     * @param cutOff whether the start rect was not fully contained by the end rect
     * @param translationX the total translation the surface moves in x direction
     * @param translationY the total translation the surfaces moves in y direction
     * @param displayFrame our display frame
     *
     * @return the duration of the clip reveal animation, in milliseconds
     */
    private static long calculateClipRevealTransitionDuration(boolean cutOff, float translationX,
            float translationY, Rect displayFrame) {
        if (!cutOff) {
            return DEFAULT_APP_TRANSITION_DURATION;
        }
        final float fraction = Math.max(Math.abs(translationX) / displayFrame.width(),
                Math.abs(translationY) / displayFrame.height());
        return (long) (DEFAULT_APP_TRANSITION_DURATION + fraction
                * (MAX_CLIP_REVEAL_TRANSITION_DURATION - DEFAULT_APP_TRANSITION_DURATION));
    }

    /**
     * Return the current thumbnail transition state.
     */
    private int getThumbnailTransitionState(boolean enter, boolean scaleUp) {
        if (enter) {
            if (scaleUp) {
                return THUMBNAIL_TRANSITION_ENTER_SCALE_UP;
            } else {
                return THUMBNAIL_TRANSITION_ENTER_SCALE_DOWN;
            }
        } else {
            if (scaleUp) {
                return THUMBNAIL_TRANSITION_EXIT_SCALE_UP;
            } else {
                return THUMBNAIL_TRANSITION_EXIT_SCALE_DOWN;
            }
        }
    }

    /**
     * Prepares the specified animation with a standard duration, interpolator, etc.
     */
    public static Animation prepareThumbnailAnimationWithDuration(Animation a, int appWidth,
            int appHeight, long duration, Interpolator interpolator) {
        if (a == null) {
            return null;
        }

        if (duration > 0) {
            a.setDuration(duration);
        }
        a.setFillAfter(true);
        if (interpolator != null) {
            a.setInterpolator(interpolator);
        }
        a.initialize(appWidth, appHeight, appWidth, appHeight);
        return a;
    }

    private static Animation createCurvedMotion(float fromX, float toX, float fromY, float toY) {
        return new TranslateAnimation(fromX, toX, fromY, toY);
    }

    /**
     * Compute the pivot point for an animation that is scaling from a small
     * rect on screen to a larger rect.  The pivot point varies depending on
     * the distance between the inner and outer edges on both sides.  This
     * function computes the pivot point for one dimension.
     * @param startPos  Offset from left/top edge of outer rectangle to
     * left/top edge of inner rectangle.
     * @param finalScale The scaling factor between the size of the outer
     * and inner rectangles.
     */
    public static float computePivot(int startPos, float finalScale) {

        /*
        Theorem of intercepting lines:

          +      +   +-----------------------------------------------+
          |      |   |                                               |
          |      |   |                                               |
          |      |   |                                               |
          |      |   |                                               |
        x |    y |   |                                               |
          |      |   |                                               |
          |      |   |                                               |
          |      |   |                                               |
          |      |   |                                               |
          |      +   |             +--------------------+            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             |                    |            |
          |          |             +--------------------+            |
          |          |                                               |
          |          |                                               |
          |          |                                               |
          |          |                                               |
          |          |                                               |
          |          |                                               |
          |          |                                               |
          |          +-----------------------------------------------+
          |
          |
          |
          |
          |
          |
          |
          |
          |
          +                                 ++
                                         p  ++

        scale = (x - y) / x
        <=> x = -y / (scale - 1)
        */
        final float denom = finalScale - 1;
        if (Math.abs(denom) < .0001f) {
            return startPos;
        }
        return -startPos / denom;
    }

    @Nullable
    public static Animation loadAnimationSafely(Context context, int resId, String tag) {
        try {
            return AnimationUtils.loadAnimation(context, resId);
        } catch (Resources.NotFoundException | InflateException e) {
            Slog.w(tag, "Unable to load animation resource", e);
            return null;
        }
    }

    public static Animation createHiddenByKeyguardExit(Context context,
            LogDecelerateInterpolator interpolator, boolean onWallpaper,
            boolean goingToNotificationShade, boolean subtleAnimation) {
        if (goingToNotificationShade) {
            return AnimationUtils.loadAnimation(context, R.anim.lock_screen_behind_enter_fade_in);
        }

        final int resource;
        if (subtleAnimation) {
            resource = R.anim.lock_screen_behind_enter_subtle;
        } else if (onWallpaper) {
            resource = R.anim.lock_screen_behind_enter_wallpaper;
        } else  {
            resource = R.anim.lock_screen_behind_enter;
        }

        AnimationSet set = (AnimationSet) AnimationUtils.loadAnimation(context, resource);

        // TODO: Use XML interpolators when we have log interpolators available in XML.
        final List<Animation> animations = set.getAnimations();
        for (int i = animations.size() - 1; i >= 0; --i) {
            animations.get(i).setInterpolator(interpolator);
        }

        return set;
    }

    /** Sets the default attributes of the screenshot layer used for animation. */
    public static void configureScreenshotLayer(
            SurfaceControl.Transaction t,
            SurfaceControl layer,
            ScreenCaptureInternal.ScreenshotHardwareBuffer buffer) {
        t.setBuffer(layer, buffer.getHardwareBuffer());
        t.setDataSpace(layer, buffer.getColorSpace().getDataSpace());
        // Avoid showing dimming effect for HDR content when running animations.
        if (buffer.containsHdrLayers()) {
            t.setDimmingEnabled(layer, false);
        }
    }

    /** Returns whether the hardware buffer passed in is marked as protected. */
    public static boolean hasProtectedContent(HardwareBuffer hardwareBuffer) {
        return (hardwareBuffer.getUsage() & HardwareBuffer.USAGE_PROTECTED_CONTENT)
                == HardwareBuffer.USAGE_PROTECTED_CONTENT;
    }

    /**
     * Returns the luminance in 0~1. The surface control is the source of the hardware buffer,
     * which will be used if the buffer is protected from reading.
     */
    public static float getBorderLuma(@NonNull HardwareBuffer hwBuffer,
            @NonNull ColorSpace colorSpace, @NonNull SurfaceControl sourceSurfaceControl) {
        if (hasProtectedContent(hwBuffer)) {
            // The buffer cannot be read. Capture another buffer which excludes protected content
            // from the source surface.
            return getBorderLuma(sourceSurfaceControl, hwBuffer.getWidth(), hwBuffer.getHeight());
        }
        // Use the existing buffer directly.
        return getBorderLuma(hwBuffer, colorSpace);
    }

    /** Returns the luminance in 0~1. */
    public static float getBorderLuma(SurfaceControl surfaceControl, int w, int h) {
        final ScreenCaptureInternal.ScreenshotHardwareBuffer buffer =
                ScreenCaptureInternal.captureLayers(surfaceControl, new Rect(0, 0, w, h), 1);
        if (buffer == null) {
            return 0;
        }
        final HardwareBuffer hwBuffer = buffer.getHardwareBuffer();
        final float luma = getBorderLuma(hwBuffer, buffer.getColorSpace());
        if (hwBuffer != null) {
            hwBuffer.close();
        }
        return luma;
    }

    /** Returns the luminance in 0~1. */
    public static float getBorderLuma(HardwareBuffer hwBuffer, ColorSpace colorSpace) {
        if (hwBuffer == null) {
            return 0;
        }
        final int format = hwBuffer.getFormat();
        // Only support RGB format in 4 bytes. And protected buffer is not readable.
        if (format != HardwareBuffer.RGBA_8888 || hasProtectedContent(hwBuffer)) {
            return 0;
        }

        final ImageReader ir = ImageReader.newInstance(hwBuffer.getWidth(), hwBuffer.getHeight(),
                format, 1 /* maxImages */);
        ir.getSurface().attachAndQueueBufferWithColorSpace(hwBuffer, colorSpace);
        final Image image = ir.acquireLatestImage();
        if (image == null || image.getPlaneCount() < 1) {
            return 0;
        }

        final Image.Plane plane = image.getPlanes()[0];
        final ByteBuffer buffer = plane.getBuffer();
        final int width = image.getWidth();
        final int height = image.getHeight();
        final int pixelStride = plane.getPixelStride();
        final int rowStride = plane.getRowStride();
        final int sampling = 10;
        final int[] histogram = new int[256];

        // Grab the top and bottom borders.
        int i = 0;
        for (int x = 0, size = width - sampling; x < size; x += sampling) {
            final int topLm = getPixelLuminance(buffer, x, 0, pixelStride, rowStride);
            final int bottomLm = getPixelLuminance(buffer, x, height - 1, pixelStride, rowStride);
            histogram[topLm]++;
            histogram[bottomLm]++;
        }

        // Grab the left and right borders.
        for (int y = 0, size = height - sampling; y < size; y += sampling) {
            final int leftLm = getPixelLuminance(buffer, 0, y, pixelStride, rowStride);
            final int rightLm = getPixelLuminance(buffer, width - 1, y, pixelStride, rowStride);
            histogram[leftLm]++;
            histogram[rightLm]++;
        }

        ir.close();

        // Find the median from histogram.
        final int halfNum = (width + height) / sampling;
        int sum = 0;
        int medianLuminance = 0;
        for (i = 0; i < histogram.length; i++) {
            sum += histogram[i];
            if (sum >= halfNum) {
                medianLuminance = i;
                break;
            }
        }
        return medianLuminance / 255f;
    }

    /** Returns the luminance of the pixel in 0~255. */
    private static int getPixelLuminance(ByteBuffer buffer, int x, int y, int pixelStride,
            int rowStride) {
        final int color = buffer.getInt(y * rowStride + x * pixelStride);
        // The buffer from ImageReader is always in native order (little-endian), so extract the
        // color components in reversed order.
        final int r = color & 0xff;
        final int g = (color >> 8) & 0xff;
        final int b = (color >> 16) & 0xff;
        // Approximation of WCAG 2.0 relative luminance.
        return ((r * 8) + (g * 22) + (b * 2)) >> 5;
    }

    /**
     * For non-system server process, it must call this method to initialize the AttributeCache and
     * start monitor package change, so the resources can be loaded correctly.
     */
    public static void initAttributeCache(Context context, Handler handler) {
        AttributeCache.init(context);
        AttributeCache.instance().monitorPackageRemove(handler);
    }

}
