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

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static com.android.keyguard.KeyguardClockSwitch.LARGE;

import android.app.WallpaperManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.keyguard.clock.ClockManager;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.system.smartspace.SmartspaceTransitionController;
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.NotificationIconContainer;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.settings.SecureSettings;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Injectable controller for {@link KeyguardClockSwitch}.
 */
public class KeyguardClockSwitchController extends ViewController<KeyguardClockSwitch> {
    private static final boolean CUSTOM_CLOCKS_ENABLED = true;

    private final StatusBarStateController mStatusBarStateController;
    private final SysuiColorExtractor mColorExtractor;
    private final ClockManager mClockManager;
    private final KeyguardSliceViewController mKeyguardSliceViewController;
    private final NotificationIconAreaController mNotificationIconAreaController;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final BatteryController mBatteryController;
    private final LockscreenSmartspaceController mSmartspaceController;
    private final Resources mResources;
    private final SecureSettings mSecureSettings;

    /**
     * Clock for both small and large sizes
     */
    private AnimatableClockController mClockViewController;
    private FrameLayout mClockFrame; // top aligned clock
    private AnimatableClockController mLargeClockViewController;
    private FrameLayout mLargeClockFrame; // centered clock

    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final KeyguardBypassController mBypassController;

    private int mKeyguardClockTopMargin = 0;

    /**
     * Listener for changes to the color palette.
     *
     * The color palette changes when the wallpaper is changed.
     */
    private final ColorExtractor.OnColorsChangedListener mColorsListener =
            (extractor, which) -> {
                if ((which & WallpaperManager.FLAG_LOCK) != 0) {
                    mView.updateColors(getGradientColors());
                }
            };

    private final ClockManager.ClockChangedListener mClockChangedListener = this::setClockPlugin;

    private ViewGroup mStatusArea;
    // If set will replace keyguard_slice_view
    private View mSmartspaceView;

    private final KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    private SmartspaceTransitionController mSmartspaceTransitionController;

    private boolean mOnlyClock = false;
    private Executor mUiExecutor;
    private boolean mCanShowDoubleLineClock = true;
    private ContentObserver mDoubleLineClockObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean change) {
            updateDoubleLineClock();
        }
    };

    @Inject
    public KeyguardClockSwitchController(
            KeyguardClockSwitch keyguardClockSwitch,
            StatusBarStateController statusBarStateController,
            SysuiColorExtractor colorExtractor,
            ClockManager clockManager,
            KeyguardSliceViewController keyguardSliceViewController,
            NotificationIconAreaController notificationIconAreaController,
            BroadcastDispatcher broadcastDispatcher,
            BatteryController batteryController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            KeyguardBypassController bypassController,
            LockscreenSmartspaceController smartspaceController,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            SmartspaceTransitionController smartspaceTransitionController,
            SecureSettings secureSettings,
            @Main Executor uiExecutor,
            @Main Resources resources) {
        super(keyguardClockSwitch);
        mStatusBarStateController = statusBarStateController;
        mColorExtractor = colorExtractor;
        mClockManager = clockManager;
        mKeyguardSliceViewController = keyguardSliceViewController;
        mNotificationIconAreaController = notificationIconAreaController;
        mBroadcastDispatcher = broadcastDispatcher;
        mBatteryController = batteryController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mBypassController = bypassController;
        mSmartspaceController = smartspaceController;
        mResources = resources;
        mSecureSettings = secureSettings;
        mUiExecutor = uiExecutor;
        mKeyguardUnlockAnimationController = keyguardUnlockAnimationController;
        mSmartspaceTransitionController = smartspaceTransitionController;
    }

    /**
     * Mostly used for alternate displays, limit the information shown
     */
    public void setOnlyClock(boolean onlyClock) {
        mOnlyClock = onlyClock;
    }

    /**
     * Attach the controller to the view it relates to.
     */
    @Override
    public void onInit() {
        mKeyguardSliceViewController.init();

        mClockFrame = mView.findViewById(R.id.lockscreen_clock_view);
        mLargeClockFrame = mView.findViewById(R.id.lockscreen_clock_view_large);

        mClockViewController =
                new AnimatableClockController(
                        mView.findViewById(R.id.animatable_clock_view),
                        mStatusBarStateController,
                        mBroadcastDispatcher,
                        mBatteryController,
                        mKeyguardUpdateMonitor,
                        mResources);
        mClockViewController.init();

        mLargeClockViewController =
                new AnimatableClockController(
                        mView.findViewById(R.id.animatable_clock_view_large),
                        mStatusBarStateController,
                        mBroadcastDispatcher,
                        mBatteryController,
                        mKeyguardUpdateMonitor,
                        mResources);
        mLargeClockViewController.init();
    }

    @Override
    protected void onViewAttached() {
        if (CUSTOM_CLOCKS_ENABLED) {
            mClockManager.addOnClockChangedListener(mClockChangedListener);
        }
        mColorExtractor.addOnColorsChangedListener(mColorsListener);
        mView.updateColors(getGradientColors());
        mKeyguardClockTopMargin =
                mView.getResources().getDimensionPixelSize(R.dimen.keyguard_clock_top_margin);

        if (mOnlyClock) {
            View ksv = mView.findViewById(R.id.keyguard_slice_view);
            ksv.setVisibility(View.GONE);

            View nic = mView.findViewById(
                    R.id.left_aligned_notification_icon_container);
            nic.setVisibility(View.GONE);
            return;
        }
        updateAodIcons();

        mStatusArea = mView.findViewById(R.id.keyguard_status_area);

        if (mSmartspaceController.isEnabled()) {
            mSmartspaceView = mSmartspaceController.buildAndConnectView(mView);
            View ksv = mView.findViewById(R.id.keyguard_slice_view);
            int ksvIndex = mStatusArea.indexOfChild(ksv);
            ksv.setVisibility(View.GONE);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    MATCH_PARENT, WRAP_CONTENT);

            mStatusArea.addView(mSmartspaceView, ksvIndex, lp);
            int startPadding = getContext().getResources()
                    .getDimensionPixelSize(R.dimen.below_clock_padding_start);
            int endPadding = getContext().getResources()
                    .getDimensionPixelSize(R.dimen.below_clock_padding_end);
            mSmartspaceView.setPaddingRelative(startPadding, 0, endPadding, 0);

            updateClockLayout();
            mSmartspaceTransitionController.setLockscreenSmartspace(mSmartspaceView);
        }

        mSecureSettings.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK),
                false, /* notifyForDescendants */
                mDoubleLineClockObserver
        );

        updateDoubleLineClock();
    }

    int getNotificationIconAreaHeight() {
        return mNotificationIconAreaController.getHeight();
    }

    @Override
    protected void onViewDetached() {
        if (CUSTOM_CLOCKS_ENABLED) {
            mClockManager.removeOnClockChangedListener(mClockChangedListener);
        }
        mColorExtractor.removeOnColorsChangedListener(mColorsListener);
        mView.setClockPlugin(null, mStatusBarStateController.getState());

        mSecureSettings.unregisterContentObserver(mDoubleLineClockObserver);
    }

    /**
     * Apply dp changes on font/scale change
     */
    public void onDensityOrFontScaleChanged() {
        mView.onDensityOrFontScaleChanged();
        mKeyguardClockTopMargin =
                mView.getResources().getDimensionPixelSize(R.dimen.keyguard_clock_top_margin);

        updateClockLayout();
    }

    private void updateClockLayout() {
        int largeClockTopMargin = getContext().getResources().getDimensionPixelSize(
                R.dimen.keyguard_large_clock_top_margin);

        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(MATCH_PARENT,
                MATCH_PARENT);
        lp.topMargin = largeClockTopMargin;
        mLargeClockFrame.setLayoutParams(lp);
    }

    /**
     * Set which clock should be displayed on the keyguard. The other one will be automatically
     * hidden.
     */
    public void displayClock(@KeyguardClockSwitch.ClockSize int clockSize) {
        if (!mCanShowDoubleLineClock && clockSize == KeyguardClockSwitch.LARGE) {
            return;
        }

        boolean appeared = mView.switchToClock(clockSize);
        if (appeared && clockSize == LARGE) {
            mLargeClockViewController.animateAppear();
        }
    }

    /**
     * If we're presenting a custom clock of just the default one.
     */
    public boolean hasCustomClock() {
        return mView.hasCustomClock();
    }

    /**
     * Get the clock text size.
     */
    public float getClockTextSize() {
        return mView.getTextSize();
    }

    /**
     * Refresh clock. Called in response to TIME_TICK broadcasts.
     */
    void refresh() {
        if (mClockViewController != null) {
            mClockViewController.refreshTime();
            mLargeClockViewController.refreshTime();
        }
        if (mSmartspaceController != null) {
            mSmartspaceController.requestSmartspaceUpdate();
        }

        mView.refresh();
    }

    /**
     * Update position of the view, with optional animation. Move the slice view and the clock
     * slightly towards the center in order to prevent burn-in. Y positioning occurs at the
     * view parent level. The large clock view will scale instead of using x position offsets, to
     * keep the clock centered.
     */
    void updatePosition(int x, float scale, AnimationProperties props, boolean animate) {
        x = getCurrentLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -x : x;

        PropertyAnimator.setProperty(mClockFrame, AnimatableProperty.TRANSLATION_X,
                x, props, animate);
        PropertyAnimator.setProperty(mLargeClockFrame, AnimatableProperty.SCALE_X,
                scale, props, animate);
        PropertyAnimator.setProperty(mLargeClockFrame, AnimatableProperty.SCALE_Y,
                scale, props, animate);

        if (mStatusArea != null) {
            PropertyAnimator.setProperty(mStatusArea, AnimatableProperty.TRANSLATION_X,
                    x, props, animate);

            // If we're unlocking with the SmartSpace shared element transition, let the controller
            // know that it should re-position our SmartSpace.
            if (mKeyguardUnlockAnimationController.isUnlockingWithSmartSpaceTransition()) {
                mKeyguardUnlockAnimationController.updateLockscreenSmartSpacePosition();
            }
        }
    }

    /** Sets an alpha value on every child view except for the smartspace. */
    public void setChildrenAlphaExcludingSmartspace(float alpha) {
        final Set<View> excludedViews = new HashSet<>();

        if (mSmartspaceView != null) {
            excludedViews.add(mSmartspaceView);
        }

        setChildrenAlphaExcluding(alpha, excludedViews);
    }

    /** Sets an alpha value on every child view except for the views in the provided set. */
    public void setChildrenAlphaExcluding(float alpha, Set<View> excludedViews) {
        for (int i = 0; i < mView.getChildCount(); i++) {
            final View child = mView.getChildAt(i);

            if (!excludedViews.contains(child)) {
                child.setAlpha(alpha);
            }
        }
    }

    void updateTimeZone(TimeZone timeZone) {
        mView.onTimeZoneChanged(timeZone);
        if (mClockViewController != null) {
            mClockViewController.onTimeZoneChanged(timeZone);
            mLargeClockViewController.onTimeZoneChanged(timeZone);
        }
    }

    void refreshFormat() {
        if (mClockViewController != null) {
            mClockViewController.refreshFormat();
            mLargeClockViewController.refreshFormat();
        }
    }

    /**
     * Get y-bottom position of the currently visible clock on the keyguard.
     * We can't directly getBottom() because clock changes positions in AOD for burn-in
     */
    int getClockBottom(int statusBarHeaderHeight) {
        if (mLargeClockFrame.getVisibility() == View.VISIBLE) {
            View clock = mLargeClockFrame.findViewById(
                    com.android.systemui.R.id.animatable_clock_view_large);
            int frameHeight = mLargeClockFrame.getHeight();
            int clockHeight = clock.getHeight();
            return frameHeight / 2 + clockHeight / 2;
        } else {
            return mClockFrame.findViewById(
                    com.android.systemui.R.id.animatable_clock_view).getHeight()
                    + statusBarHeaderHeight + mKeyguardClockTopMargin;
        }
    }

    boolean isClockTopAligned() {
        return mLargeClockFrame.getVisibility() != View.VISIBLE;
    }

    private void updateAodIcons() {
        NotificationIconContainer nic = (NotificationIconContainer)
                mView.findViewById(
                        com.android.systemui.R.id.left_aligned_notification_icon_container);
        mNotificationIconAreaController.setupAodIcons(nic);
    }

    private void setClockPlugin(ClockPlugin plugin) {
        mView.setClockPlugin(plugin, mStatusBarStateController.getState());
    }

    private ColorExtractor.GradientColors getGradientColors() {
        return mColorExtractor.getColors(WallpaperManager.FLAG_LOCK);
    }

    private int getCurrentLayoutDirection() {
        return TextUtils.getLayoutDirectionFromLocale(Locale.getDefault());
    }

    private void updateDoubleLineClock() {
        mCanShowDoubleLineClock = mSecureSettings.getInt(
            Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK, 1) != 0;

        if (!mCanShowDoubleLineClock) {
            mUiExecutor.execute(() -> displayClock(KeyguardClockSwitch.SMALL));
        }
    }
}
