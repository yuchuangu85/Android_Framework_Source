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

package com.android.keyguard;

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricSourceType.FINGERPRINT;

import static com.android.keyguard.LockIconView.ICON_FINGERPRINT;
import static com.android.keyguard.LockIconView.ICON_LOCK;
import static com.android.keyguard.LockIconView.ICON_UNLOCK;
import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInOffset;
import static com.android.systemui.flags.Flags.DOZING_MIGRATION_1;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedStateListDrawable;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricSourceType;
import android.os.Process;
import android.os.VibrationAttributes;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.MathUtils;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.android.settingslib.udfps.UdfpsOverlayParams;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.biometrics.AuthRippleController;
import com.android.systemui.biometrics.UdfpsController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.shared.model.TransitionStep;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * Controls when to show the LockIcon affordance (lock/unlocked icon or circle) on lock screen.
 *
 * For devices with UDFPS, the lock icon will show at the sensor location. Else, the lock
 * icon will show a set distance from the bottom of the device.
 */
@SysUISingleton
public class LockIconViewController extends ViewController<LockIconView> implements Dumpable {
    private static final String TAG = "LockIconViewController";
    private static final float sDefaultDensity =
            (float) DisplayMetrics.DENSITY_DEVICE_STABLE / (float) DisplayMetrics.DENSITY_DEFAULT;
    private static final int sLockIconRadiusPx = (int) (sDefaultDensity * 36);
    private static final VibrationAttributes TOUCH_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH);

    private final long mLongPressTimeout;
    @NonNull private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @NonNull private final KeyguardViewController mKeyguardViewController;
    @NonNull private final StatusBarStateController mStatusBarStateController;
    @NonNull private final KeyguardStateController mKeyguardStateController;
    @NonNull private final FalsingManager mFalsingManager;
    @NonNull private final AuthController mAuthController;
    @NonNull private final AccessibilityManager mAccessibilityManager;
    @NonNull private final ConfigurationController mConfigurationController;
    @NonNull private final DelayableExecutor mExecutor;
    private boolean mUdfpsEnrolled;

    @NonNull private final AnimatedStateListDrawable mIcon;

    @NonNull private CharSequence mUnlockedLabel;
    @NonNull private CharSequence mLockedLabel;
    @NonNull private final VibratorHelper mVibrator;
    @Nullable private final AuthRippleController mAuthRippleController;
    @NonNull private final FeatureFlags mFeatureFlags;
    @NonNull private final KeyguardTransitionInteractor mTransitionInteractor;
    @NonNull private final KeyguardInteractor mKeyguardInteractor;

    // Tracks the velocity of a touch to help filter out the touches that move too fast.
    private VelocityTracker mVelocityTracker;
    // The ID of the pointer for which ACTION_DOWN has occurred. -1 means no pointer is active.
    private int mActivePointerId = -1;

    private boolean mIsDozing;
    private boolean mIsBouncerShowing;
    private boolean mRunningFPS;
    private boolean mCanDismissLockScreen;
    private int mStatusBarState;
    private boolean mIsKeyguardShowing;
    private Runnable mOnGestureDetectedRunnable;
    private Runnable mLongPressCancelRunnable;

    private boolean mUdfpsSupported;
    private float mHeightPixels;
    private float mWidthPixels;
    private int mBottomPaddingPx;
    private int mDefaultPaddingPx;

    private boolean mShowUnlockIcon;
    private boolean mShowLockIcon;

    // for udfps when strong auth is required or unlocked on AOD
    private boolean mShowAodLockIcon;
    private boolean mShowAodUnlockedIcon;
    private final int mMaxBurnInOffsetX;
    private final int mMaxBurnInOffsetY;
    private float mInterpolatedDarkAmount;

    private boolean mDownDetected;
    private final Rect mSensorTouchLocation = new Rect();

    @VisibleForTesting
    final Consumer<TransitionStep> mDozeTransitionCallback = (TransitionStep step) -> {
        mInterpolatedDarkAmount = step.getValue();
        mView.setDozeAmount(step.getValue());
        updateBurnInOffsets();
    };

    @VisibleForTesting
    final Consumer<Boolean> mIsDozingCallback = (Boolean isDozing) -> {
        mIsDozing = isDozing;
        updateBurnInOffsets();
        updateVisibility();
    };

    @Inject
    public LockIconViewController(
            @Nullable LockIconView view,
            @NonNull StatusBarStateController statusBarStateController,
            @NonNull KeyguardUpdateMonitor keyguardUpdateMonitor,
            @NonNull KeyguardViewController keyguardViewController,
            @NonNull KeyguardStateController keyguardStateController,
            @NonNull FalsingManager falsingManager,
            @NonNull AuthController authController,
            @NonNull DumpManager dumpManager,
            @NonNull AccessibilityManager accessibilityManager,
            @NonNull ConfigurationController configurationController,
            @NonNull @Main DelayableExecutor executor,
            @NonNull VibratorHelper vibrator,
            @Nullable AuthRippleController authRippleController,
            @NonNull @Main Resources resources,
            @NonNull KeyguardTransitionInteractor transitionInteractor,
            @NonNull KeyguardInteractor keyguardInteractor,
            @NonNull FeatureFlags featureFlags
    ) {
        super(view);
        mStatusBarStateController = statusBarStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mAuthController = authController;
        mKeyguardViewController = keyguardViewController;
        mKeyguardStateController = keyguardStateController;
        mFalsingManager = falsingManager;
        mAccessibilityManager = accessibilityManager;
        mConfigurationController = configurationController;
        mExecutor = executor;
        mVibrator = vibrator;
        mAuthRippleController = authRippleController;
        mTransitionInteractor = transitionInteractor;
        mKeyguardInteractor = keyguardInteractor;
        mFeatureFlags = featureFlags;

        mMaxBurnInOffsetX = resources.getDimensionPixelSize(R.dimen.udfps_burn_in_offset_x);
        mMaxBurnInOffsetY = resources.getDimensionPixelSize(R.dimen.udfps_burn_in_offset_y);

        mIcon = (AnimatedStateListDrawable)
                resources.getDrawable(R.drawable.super_lock_icon, mView.getContext().getTheme());
        mView.setImageDrawable(mIcon);
        mUnlockedLabel = resources.getString(R.string.accessibility_unlock_button);
        mLockedLabel = resources.getString(R.string.accessibility_lock_icon);
        mLongPressTimeout = resources.getInteger(R.integer.config_lockIconLongPress);
        dumpManager.registerDumpable(TAG, this);
    }

    @Override
    protected void onInit() {
        mView.setAccessibilityDelegate(mAccessibilityDelegate);

        if (mFeatureFlags.isEnabled(DOZING_MIGRATION_1)) {
            collectFlow(mView, mTransitionInteractor.getDozeAmountTransition(),
                    mDozeTransitionCallback);
            collectFlow(mView, mKeyguardInteractor.isDozing(), mIsDozingCallback);
        }
    }

    @Override
    protected void onViewAttached() {
        updateIsUdfpsEnrolled();
        updateConfiguration();
        updateKeyguardShowing();

        mIsBouncerShowing = mKeyguardViewController.isBouncerShowing();
        mIsDozing = mStatusBarStateController.isDozing();
        mInterpolatedDarkAmount = mStatusBarStateController.getDozeAmount();
        mRunningFPS = mKeyguardUpdateMonitor.isFingerprintDetectionRunning();
        mCanDismissLockScreen = mKeyguardStateController.canDismissLockScreen();
        mStatusBarState = mStatusBarStateController.getState();

        updateColors();
        mConfigurationController.addCallback(mConfigurationListener);

        mAuthController.addCallback(mAuthControllerCallback);
        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mKeyguardStateController.addCallback(mKeyguardStateCallback);
        mDownDetected = false;
        updateBurnInOffsets();
        updateVisibility();

        mAccessibilityManager.addAccessibilityStateChangeListener(
                mAccessibilityStateChangeListener);
        updateAccessibility();
    }

    private void updateAccessibility() {
        if (mAccessibilityManager.isEnabled()) {
            mView.setOnClickListener(mA11yClickListener);
        } else {
            mView.setOnClickListener(null);
        }
    }

    @Override
    protected void onViewDetached() {
        mAuthController.removeCallback(mAuthControllerCallback);
        mConfigurationController.removeCallback(mConfigurationListener);
        mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateMonitorCallback);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mKeyguardStateController.removeCallback(mKeyguardStateCallback);

        mAccessibilityManager.removeAccessibilityStateChangeListener(
                mAccessibilityStateChangeListener);
    }

    public float getTop() {
        return mView.getLocationTop();
    }

    public float getBottom() {
        return mView.getLocationBottom();
    }

    private void updateVisibility() {
        if (!mIsKeyguardShowing && !mIsDozing) {
            mView.setVisibility(View.INVISIBLE);
            return;
        }

        boolean wasShowingFpIcon = mUdfpsEnrolled && !mShowUnlockIcon && !mShowLockIcon
                && !mShowAodUnlockedIcon && !mShowAodLockIcon;
        mShowLockIcon = !mCanDismissLockScreen && isLockScreen()
                && (!mUdfpsEnrolled || !mRunningFPS);
        mShowUnlockIcon = mCanDismissLockScreen && isLockScreen();
        mShowAodUnlockedIcon = mIsDozing && mUdfpsEnrolled && !mRunningFPS && mCanDismissLockScreen;
        mShowAodLockIcon = mIsDozing && mUdfpsEnrolled && !mRunningFPS && !mCanDismissLockScreen;

        final CharSequence prevContentDescription = mView.getContentDescription();
        if (mShowLockIcon) {
            if (wasShowingFpIcon) {
                // fp icon was shown by UdfpsView, and now we still want to animate the transition
                // in this drawable
                mView.updateIcon(ICON_FINGERPRINT, false);
            }
            mView.updateIcon(ICON_LOCK, false);
            mView.setContentDescription(mLockedLabel);
            mView.setVisibility(View.VISIBLE);
        } else if (mShowUnlockIcon) {
            if (wasShowingFpIcon) {
                // fp icon was shown by UdfpsView, and now we still want to animate the transition
                // in this drawable
                mView.updateIcon(ICON_FINGERPRINT, false);
            }
            mView.updateIcon(ICON_UNLOCK, false);
            mView.setContentDescription(mUnlockedLabel);
            mView.setVisibility(View.VISIBLE);
        } else if (mShowAodUnlockedIcon) {
            mView.updateIcon(ICON_UNLOCK, true);
            mView.setContentDescription(mUnlockedLabel);
            mView.setVisibility(View.VISIBLE);
        } else if (mShowAodLockIcon) {
            mView.updateIcon(ICON_LOCK, true);
            mView.setContentDescription(mLockedLabel);
            mView.setVisibility(View.VISIBLE);
        } else {
            mView.clearIcon();
            mView.setVisibility(View.INVISIBLE);
            mView.setContentDescription(null);
        }

        if (!Objects.equals(prevContentDescription, mView.getContentDescription())
                && mView.getContentDescription() != null) {
            mView.announceForAccessibility(mView.getContentDescription());
        }
    }

    private final View.AccessibilityDelegate mAccessibilityDelegate =
            new View.AccessibilityDelegate() {
        private final AccessibilityNodeInfo.AccessibilityAction mAccessibilityAuthenticateHint =
                new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfoCompat.ACTION_CLICK,
                        getResources().getString(R.string.accessibility_authenticate_hint));
        private final AccessibilityNodeInfo.AccessibilityAction mAccessibilityEnterHint =
                new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfoCompat.ACTION_CLICK,
                        getResources().getString(R.string.accessibility_enter_hint));
        public void onInitializeAccessibilityNodeInfo(View v, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(v, info);
            if (isActionable()) {
                if (mShowLockIcon) {
                    info.addAction(mAccessibilityAuthenticateHint);
                } else if (mShowUnlockIcon) {
                    info.addAction(mAccessibilityEnterHint);
                }
            }
        }
    };

    private boolean isLockScreen() {
        return !mIsDozing
                && !mIsBouncerShowing
                && mStatusBarState == StatusBarState.KEYGUARD;
    }

    private void updateKeyguardShowing() {
        mIsKeyguardShowing = mKeyguardStateController.isShowing()
                && !mKeyguardStateController.isKeyguardGoingAway();
    }

    private void updateColors() {
        mView.updateColorAndBackgroundVisibility();
    }

    private void updateConfiguration() {
        WindowManager windowManager = getContext().getSystemService(WindowManager.class);
        Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
        mWidthPixels = bounds.right;
        mHeightPixels = bounds.bottom;
        mBottomPaddingPx = getResources().getDimensionPixelSize(R.dimen.lock_icon_margin_bottom);
        mDefaultPaddingPx =
                getResources().getDimensionPixelSize(R.dimen.lock_icon_padding);

        mUnlockedLabel = mView.getContext().getResources().getString(
                R.string.accessibility_unlock_button);
        mLockedLabel = mView.getContext()
                .getResources().getString(R.string.accessibility_lock_icon);
        updateLockIconLocation();
    }

    private void updateLockIconLocation() {
        final float scaleFactor = mAuthController.getScaleFactor();
        final int scaledPadding = (int) (mDefaultPaddingPx * scaleFactor);
        if (mUdfpsSupported) {
            mView.setCenterLocation(mAuthController.getUdfpsLocation(),
                    mAuthController.getUdfpsRadius(), scaledPadding);
        } else {
            mView.setCenterLocation(
                    new Point((int) mWidthPixels / 2,
                            (int) (mHeightPixels
                                    - ((mBottomPaddingPx + sLockIconRadiusPx) * scaleFactor))),
                        sLockIconRadiusPx * scaleFactor, scaledPadding);
        }
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("mUdfpsSupported: " + mUdfpsSupported);
        pw.println("mUdfpsEnrolled: " + mUdfpsEnrolled);
        pw.println("mIsKeyguardShowing: " + mIsKeyguardShowing);
        pw.println(" mIcon: ");
        for (int state : mIcon.getState()) {
            pw.print(" " + state);
        }
        pw.println();
        pw.println(" mShowUnlockIcon: " + mShowUnlockIcon);
        pw.println(" mShowLockIcon: " + mShowLockIcon);
        pw.println(" mShowAodUnlockedIcon: " + mShowAodUnlockedIcon);
        pw.println();
        pw.println(" mIsDozing: " + mIsDozing);
        pw.println(" isFlagEnabled(DOZING_MIGRATION_1): "
                + mFeatureFlags.isEnabled(DOZING_MIGRATION_1));
        pw.println(" mIsBouncerShowing: " + mIsBouncerShowing);
        pw.println(" mRunningFPS: " + mRunningFPS);
        pw.println(" mCanDismissLockScreen: " + mCanDismissLockScreen);
        pw.println(" mStatusBarState: " + StatusBarState.toString(mStatusBarState));
        pw.println(" mInterpolatedDarkAmount: " + mInterpolatedDarkAmount);
        pw.println(" mSensorTouchLocation: " + mSensorTouchLocation);
        pw.println(" mDefaultPaddingPx: " + mDefaultPaddingPx);

        if (mView != null) {
            mView.dump(pw, args);
        }
    }

    /** Every minute, update the aod icon's burn in offset */
    public void dozeTimeTick() {
        updateBurnInOffsets();
    }

    private void updateBurnInOffsets() {
        float offsetX = MathUtils.lerp(0f,
                getBurnInOffset(mMaxBurnInOffsetX * 2, true /* xAxis */)
                        - mMaxBurnInOffsetX, mInterpolatedDarkAmount);
        float offsetY = MathUtils.lerp(0f,
                getBurnInOffset(mMaxBurnInOffsetY * 2, false /* xAxis */)
                        - mMaxBurnInOffsetY, mInterpolatedDarkAmount);

        mView.setTranslationX(offsetX);
        mView.setTranslationY(offsetY);
    }

    private void updateIsUdfpsEnrolled() {
        boolean wasUdfpsSupported = mUdfpsSupported;
        boolean wasUdfpsEnrolled = mUdfpsEnrolled;

        mUdfpsSupported = mKeyguardUpdateMonitor.isUdfpsSupported();
        mView.setUseBackground(mUdfpsSupported);

        mUdfpsEnrolled = mKeyguardUpdateMonitor.isUdfpsEnrolled();
        if (wasUdfpsSupported != mUdfpsSupported || wasUdfpsEnrolled != mUdfpsEnrolled) {
            updateVisibility();
        }
    }

    private StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onDozeAmountChanged(float linear, float eased) {
                    if (!mFeatureFlags.isEnabled(DOZING_MIGRATION_1)) {
                        mInterpolatedDarkAmount = eased;
                        mView.setDozeAmount(eased);
                        updateBurnInOffsets();
                    }
                }

                @Override
                public void onDozingChanged(boolean isDozing) {
                    if (!mFeatureFlags.isEnabled(DOZING_MIGRATION_1)) {
                        mIsDozing = isDozing;
                        updateBurnInOffsets();
                        updateVisibility();
                    }
                }

                @Override
                public void onStateChanged(int statusBarState) {
                    mStatusBarState = statusBarState;
                    updateVisibility();
                }
            };

    private final KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onKeyguardBouncerStateChanged(boolean bouncer) {
                    mIsBouncerShowing = bouncer;
                    updateVisibility();
                }

                @Override
                public void onBiometricRunningStateChanged(boolean running,
                        BiometricSourceType biometricSourceType) {
                    final boolean wasRunningFps = mRunningFPS;

                    if (biometricSourceType == FINGERPRINT) {
                        mRunningFPS = running;
                    }

                    if (wasRunningFps != mRunningFPS) {
                        updateVisibility();
                    }
                }
            };

    private final KeyguardStateController.Callback mKeyguardStateCallback =
            new KeyguardStateController.Callback() {
        @Override
        public void onUnlockedChanged() {
            mCanDismissLockScreen = mKeyguardStateController.canDismissLockScreen();
            updateKeyguardShowing();
            updateVisibility();
        }

        @Override
        public void onKeyguardShowingChanged() {
            // Reset values in case biometrics were removed (ie: pin/pattern/password => swipe).
            // If biometrics were removed, local vars mCanDismissLockScreen and
            // mUserUnlockedWithBiometric may not be updated.
            mCanDismissLockScreen = mKeyguardStateController.canDismissLockScreen();

            // reset mIsBouncerShowing state in case it was preemptively set
            // onLongPress
            mIsBouncerShowing = mKeyguardViewController.isBouncerShowing();

            updateKeyguardShowing();
            updateVisibility();
        }

        @Override
        public void onKeyguardFadingAwayChanged() {
            updateKeyguardShowing();
            updateVisibility();
        }
    };

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
        @Override
        public void onUiModeChanged() {
            updateColors();
        }

        @Override
        public void onThemeChanged() {
            updateColors();
        }

        @Override
        public void onConfigChanged(Configuration newConfig) {
            updateConfiguration();
            updateColors();
        }
    };

    /**
     * Handles the touch if it is within the lock icon view and {@link #isActionable()} is true.
     * Subsequently, will trigger {@link #onLongPress()} if a touch is continuously in the lock icon
     * area for {@link #mLongPressTimeout} ms.
     *
     * Touch speed debouncing mimics logic from the velocity tracker in {@link UdfpsController}.
     */
    public boolean onTouchEvent(MotionEvent event, Runnable onGestureDetectedRunnable) {
        if (!onInterceptTouchEvent(event)) {
            cancelTouches();
            return false;
        }

        mOnGestureDetectedRunnable = onGestureDetectedRunnable;
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_HOVER_ENTER:
                if (!mDownDetected && mAccessibilityManager.isTouchExplorationEnabled()) {
                    mVibrator.vibrate(
                            Process.myUid(),
                            getContext().getOpPackageName(),
                            UdfpsController.EFFECT_CLICK,
                            "lock-icon-down",
                            TOUCH_VIBRATION_ATTRIBUTES);
                }

                // The pointer that causes ACTION_DOWN is always at index 0.
                // We need to persist its ID to track it during ACTION_MOVE that could include
                // data for many other pointers because of multi-touch support.
                mActivePointerId = event.getPointerId(0);
                if (mVelocityTracker == null) {
                    // To simplify the lifecycle of the velocity tracker, make sure it's never null
                    // after ACTION_DOWN, and always null after ACTION_CANCEL or ACTION_UP.
                    mVelocityTracker = VelocityTracker.obtain();
                } else {
                    // ACTION_UP or ACTION_CANCEL is not guaranteed to be called before a new
                    // ACTION_DOWN, in that case we should just reuse the old instance.
                    mVelocityTracker.clear();
                }
                mVelocityTracker.addMovement(event);

                mDownDetected = true;
                mLongPressCancelRunnable = mExecutor.executeDelayed(
                        this::onLongPress, mLongPressTimeout);
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                mVelocityTracker.addMovement(event);
                // Compute pointer velocity in pixels per second.
                mVelocityTracker.computeCurrentVelocity(1000);
                float velocity = UdfpsController.computePointerSpeed(mVelocityTracker,
                        mActivePointerId);
                if (event.getClassification() != MotionEvent.CLASSIFICATION_DEEP_PRESS
                        && UdfpsController.exceedsVelocityThreshold(velocity)) {
                    Log.v(TAG, "lock icon long-press rescheduled due to "
                            + "high pointer velocity=" + velocity);
                    mLongPressCancelRunnable.run();
                    mLongPressCancelRunnable = mExecutor.executeDelayed(
                            this::onLongPress, mLongPressTimeout);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_HOVER_EXIT:
                cancelTouches();
                break;
        }

        return true;
    }

    /**
     * Intercepts the touch if the onDown event and current event are within this lock icon view's
     * bounds.
     */
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!inLockIconArea(event) || !isActionable()) {
            return false;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            return true;
        }

        return mDownDetected;
    }

    private void onLongPress() {
        cancelTouches();
        if (mFalsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)) {
            Log.v(TAG, "lock icon long-press rejected by the falsing manager.");
            return;
        }

        // pre-emptively set to true to hide view
        mIsBouncerShowing = true;
        if (mUdfpsSupported && mShowUnlockIcon && mAuthRippleController != null) {
            mAuthRippleController.showUnlockRipple(FINGERPRINT);
        }
        updateVisibility();
        if (mOnGestureDetectedRunnable != null) {
            mOnGestureDetectedRunnable.run();
        }

        // play device entry haptic (same as biometric success haptic)
        mVibrator.vibrate(
                Process.myUid(),
                getContext().getOpPackageName(),
                UdfpsController.EFFECT_CLICK,
                "lock-screen-lock-icon-longpress",
                TOUCH_VIBRATION_ATTRIBUTES);

        mKeyguardViewController.showPrimaryBouncer(/* scrim */ true);
    }


    private void cancelTouches() {
        mDownDetected = false;
        if (mLongPressCancelRunnable != null) {
            mLongPressCancelRunnable.run();
        }
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    private boolean inLockIconArea(MotionEvent event) {
        mView.getHitRect(mSensorTouchLocation);
        return mSensorTouchLocation.contains((int) event.getX(), (int) event.getY())
                && mView.getVisibility() == View.VISIBLE;
    }

    private boolean isActionable() {
        if (mIsBouncerShowing) {
            Log.v(TAG, "lock icon long-press ignored, bouncer already showing.");
            // a long press gestures from AOD may have already triggered the bouncer to show,
            // so this touch is no longer actionable
            return false;
        }
        return mUdfpsSupported || mShowUnlockIcon;
    }

    /**
     * Set the alpha of this view.
     */
    public void setAlpha(float alpha) {
        mView.setAlpha(alpha);
    }

    private void updateUdfpsConfig() {
        // must be called from the main thread since it may update the views
        mExecutor.execute(() -> {
            updateIsUdfpsEnrolled();
            updateConfiguration();
        });
    }

    private final AuthController.Callback mAuthControllerCallback = new AuthController.Callback() {
        @Override
        public void onAllAuthenticatorsRegistered(@BiometricAuthenticator.Modality int modality) {
            if (modality == TYPE_FINGERPRINT) {
                updateUdfpsConfig();
            }
        }

        @Override
        public void onEnrollmentsChanged(@BiometricAuthenticator.Modality int modality) {
            if (modality == TYPE_FINGERPRINT) {
                updateUdfpsConfig();
            }
        }

        @Override
        public void onUdfpsLocationChanged(UdfpsOverlayParams udfpsOverlayParams) {
            updateUdfpsConfig();
        }
    };

    private final View.OnClickListener mA11yClickListener = v -> onLongPress();

    private final AccessibilityManager.AccessibilityStateChangeListener
            mAccessibilityStateChangeListener = enabled -> updateAccessibility();
}
