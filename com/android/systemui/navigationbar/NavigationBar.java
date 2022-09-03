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

package com.android.systemui.navigationbar;

import static android.app.StatusBarManager.NAVIGATION_HINT_BACK_ALT;
import static android.app.StatusBarManager.NAVIGATION_HINT_IME_SHOWN;
import static android.app.StatusBarManager.WINDOW_STATE_HIDDEN;
import static android.app.StatusBarManager.WINDOW_STATE_SHOWING;
import static android.app.StatusBarManager.WindowType;
import static android.app.StatusBarManager.WindowVisibleState;
import static android.app.StatusBarManager.windowStateToString;
import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.containsType;
import static android.view.ViewRootImpl.INSETS_LAYOUT_GENERALIZATION;
import static android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_SEMI_TRANSPARENT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;

import static com.android.internal.accessibility.common.ShortcutConstants.CHOOSER_PACKAGE_NAME;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.HOME_BUTTON_LONG_PRESS_DURATION_MS;
import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.NAV_BAR_HANDLE_FORCE_OPAQUE;
import static com.android.systemui.recents.OverviewProxyService.OverviewProxyListener;
import static com.android.systemui.shared.recents.utilities.Utilities.isTablet;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_SWITCHER_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NAV_BAR_HIDDEN;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_LIGHTS_OUT_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_OPAQUE;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_SEMI_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.MODE_TRANSPARENT;
import static com.android.systemui.statusbar.phone.BarTransitions.TransitionMode;
import static com.android.systemui.statusbar.phone.StatusBar.DEBUG_WINDOW_STATE;
import static com.android.systemui.statusbar.phone.StatusBar.dumpBarTransitions;

import android.annotation.IdRes;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InsetsState.InternalInsetsType;
import android.view.InsetsVisibilities;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowInsetsController.Behavior;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.VisibleForTesting;

import com.android.internal.accessibility.dialog.AccessibilityButtonChooserActivity;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.LatencyTracker;
import com.android.internal.view.AppearanceRegion;
import com.android.systemui.R;
import com.android.systemui.accessibility.AccessibilityButtonModeObserver;
import com.android.systemui.accessibility.SystemActions;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.buttons.ButtonDispatcher;
import com.android.systemui.navigationbar.buttons.KeyButtonView;
import com.android.systemui.navigationbar.buttons.RotationContextButton;
import com.android.systemui.navigationbar.gestural.QuickswitchOrientedNavHandle;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.recents.Recents;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.recents.utilities.Utilities;
import com.android.systemui.shared.rotation.RotationButton;
import com.android.systemui.shared.rotation.RotationButtonController;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.AutoHideUiElement;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.BarTransitions;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.pip.Pip;

import java.io.PrintWriter;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

import javax.inject.Inject;

import dagger.Lazy;

/**
 * Contains logic for a navigation bar view.
 */
public class NavigationBar implements View.OnAttachStateChangeListener,
        Callbacks, NavigationModeController.ModeChangedListener {

    public static final String TAG = "NavigationBar";
    private static final boolean DEBUG = false;
    private static final String EXTRA_DISABLE_STATE = "disabled_state";
    private static final String EXTRA_DISABLE2_STATE = "disabled2_state";
    private static final String EXTRA_APPEARANCE = "appearance";
    private static final String EXTRA_BEHAVIOR = "behavior";
    private static final String EXTRA_TRANSIENT_STATE = "transient_state";

    /** Allow some time inbetween the long press for back and recents. */
    private static final int LOCK_TO_APP_GESTURE_TOLERENCE = 200;
    private static final long AUTODIM_TIMEOUT_MS = 2250;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final AccessibilityManager mAccessibilityManager;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final StatusBarStateController mStatusBarStateController;
    private final MetricsLogger mMetricsLogger;
    private final Lazy<AssistManager> mAssistManagerLazy;
    private final SysUiState mSysUiFlagsContainer;
    private final Lazy<Optional<StatusBar>> mStatusBarOptionalLazy;
    private final ShadeController mShadeController;
    private final NotificationRemoteInputManager mNotificationRemoteInputManager;
    private final OverviewProxyService mOverviewProxyService;
    private final NavigationModeController mNavigationModeController;
    private final AccessibilityButtonModeObserver mAccessibilityButtonModeObserver;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final CommandQueue mCommandQueue;
    private final Optional<Pip> mPipOptional;
    private final Optional<LegacySplitScreen> mSplitScreenOptional;
    private final Optional<Recents> mRecentsOptional;
    private final SystemActions mSystemActions;
    private final Handler mHandler;
    private final NavigationBarOverlayController mNavbarOverlayController;
    private final UiEventLogger mUiEventLogger;
    private final NavBarHelper mNavBarHelper;
    private final NotificationShadeDepthController mNotificationShadeDepthController;

    private Bundle mSavedState;
    private NavigationBarView mNavigationBarView;
    private NavigationBarFrame mFrame;

    private @WindowVisibleState int mNavigationBarWindowState = WINDOW_STATE_SHOWING;

    private int mNavigationIconHints = 0;
    private @TransitionMode int mNavigationBarMode;
    private ContentResolver mContentResolver;
    private boolean mLongPressHomeEnabled;

    private int mDisabledFlags1;
    private int mDisabledFlags2;
    private long mLastLockToAppLongPress;

    private Locale mLocale;
    private int mLayoutDirection;

    private boolean mAllowForceNavBarHandleOpaque;
    private boolean mForceNavBarHandleOpaque;
    private Optional<Long> mHomeButtonLongPressDurationMs;
    private boolean mIsCurrentUserSetup;

    /** @see android.view.WindowInsetsController#setSystemBarsAppearance(int, int) */
    private @Appearance int mAppearance;

    /** @see android.view.WindowInsetsController#setSystemBarsBehavior(int) */
    private @Behavior int mBehavior;

    private boolean mTransientShown;
    private boolean mTransientShownFromGestureOnSystemBar;
    private int mNavBarMode = NAV_BAR_MODE_3BUTTON;
    private LightBarController mLightBarController;
    private final LightBarController mMainLightBarController;
    private final LightBarController.Factory mLightBarControllerFactory;
    private AutoHideController mAutoHideController;
    private final AutoHideController mMainAutoHideController;
    private final AutoHideController.Factory mAutoHideControllerFactory;
    private final Optional<TelecomManager> mTelecomManagerOptional;
    private final InputMethodManager mInputMethodManager;

    @VisibleForTesting
    public int mDisplayId;
    private boolean mIsOnDefaultDisplay;
    public boolean mHomeBlockedThisTouch;

    /**
     * When user is QuickSwitching between apps of different orientations, we'll draw a fake
     * home handle on the orientation they originally touched down to start their swipe
     * gesture to indicate to them that they can continue in that orientation without having to
     * rotate the phone
     * The secondary handle will show when we get
     * {@link OverviewProxyListener#notifyPrioritizedRotation(int)} callback with the
     * original handle hidden and we'll flip the visibilities once the
     * {@link #mTasksFrozenListener} fires
     */
    private QuickswitchOrientedNavHandle mOrientationHandle;
    private WindowManager.LayoutParams mOrientationParams;
    private int mStartingQuickSwitchRotation = -1;
    private int mCurrentRotation;
    private ViewTreeObserver.OnGlobalLayoutListener mOrientationHandleGlobalLayoutListener;
    private boolean mShowOrientedHandleForImmersiveMode;


    @com.android.internal.annotations.VisibleForTesting
    public enum NavBarActionEvent implements UiEventLogger.UiEventEnum {

        @UiEvent(doc = "Assistant invoked via home button long press.")
        NAVBAR_ASSIST_LONGPRESS(550);

        private final int mId;

        NavBarActionEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    private final AutoHideUiElement mAutoHideUiElement = new AutoHideUiElement() {
        @Override
        public void synchronizeState() {
            checkNavBarModes();
        }

        @Override
        public boolean shouldHideOnTouch() {
            return !mNotificationRemoteInputManager.isRemoteInputActive();
        }

        @Override
        public boolean isVisible() {
            return isTransientShown();
        }

        @Override
        public void hide() {
            clearTransient();
        }
    };

    private final NavBarHelper.NavbarTaskbarStateUpdater mNavbarTaskbarStateUpdater =
            new NavBarHelper.NavbarTaskbarStateUpdater() {
                @Override
                public void updateAccessibilityServicesState() {
                    updateAcessibilityStateFlags();
                }

                @Override
                public void updateAssistantAvailable(boolean available) {
                    // TODO(b/198002034): Content observers currently can still be called back after
                    //  being unregistered, and in this case we can ignore the change if the nav bar
                    //  has been destroyed already
                    if (mNavigationBarView == null) {
                        return;
                    }
                    mLongPressHomeEnabled = mNavBarHelper.getLongPressHomeEnabled();
                    updateAssistantEntrypoints(available);
                }
            };

    private final OverviewProxyListener mOverviewProxyListener = new OverviewProxyListener() {
        @Override
        public void onConnectionChanged(boolean isConnected) {
            mNavigationBarView.updateStates();
            updateScreenPinningGestures();
        }

        @Override
        public void onQuickStepStarted() {
            // Use navbar dragging as a signal to hide the rotate button
            mNavigationBarView.getRotationButtonController().setRotateSuggestionButtonState(false);

            // Hide the notifications panel when quick step starts
            mShadeController.collapsePanel(true /* animate */);
        }

        @Override
        public void onPrioritizedRotation(@Surface.Rotation int rotation) {
            mStartingQuickSwitchRotation = rotation;
            if (rotation == -1) {
                mShowOrientedHandleForImmersiveMode = false;
            }
            orientSecondaryHomeHandle();
        }

        @Override
        public void startAssistant(Bundle bundle) {
            mAssistManagerLazy.get().startAssist(bundle);
        }

        @Override
        public void onNavBarButtonAlphaChanged(float alpha, boolean animate) {
            if (!mIsCurrentUserSetup) {
                // If the current user is not yet setup, then don't update any button alphas
                return;
            }
            if (QuickStepContract.isLegacyMode(mNavBarMode)) {
                // Don't allow the bar buttons to be affected by the alpha
                return;
            }

            ButtonDispatcher buttonDispatcher = null;
            boolean forceVisible = false;
            if (QuickStepContract.isGesturalMode(mNavBarMode)) {
                // Disallow home handle animations when in gestural
                animate = false;
                forceVisible = mAllowForceNavBarHandleOpaque && mForceNavBarHandleOpaque;
                buttonDispatcher = mNavigationBarView.getHomeHandle();
                if (getBarTransitions() != null) {
                    getBarTransitions().setBackgroundOverrideAlpha(alpha);
                }
            } else if (QuickStepContract.isSwipeUpMode(mNavBarMode)) {
                buttonDispatcher = mNavigationBarView.getBackButton();
            }
            if (buttonDispatcher != null) {
                buttonDispatcher.setVisibility(
                        (forceVisible || alpha > 0) ? View.VISIBLE : View.INVISIBLE);
                buttonDispatcher.setAlpha(forceVisible ? 1f : alpha, animate);
            }
        }

        @Override
        public void onHomeRotationEnabled(boolean enabled) {
            mNavigationBarView.getRotationButtonController().setHomeRotationEnabled(enabled);
        }

        @Override
        public void onOverviewShown(boolean fromHome) {
            // If the overview has fixed orientation that may change display to natural rotation,
            // we don't want the user rotation to be reset. So after user returns to application,
            // it can keep in the original rotation.
            mNavigationBarView.getRotationButtonController().setSkipOverrideUserLockPrefsOnce();
        }

        @Override
        public void onTaskbarStatusUpdated(boolean visible, boolean stashed) {
            mNavigationBarView
                    .getFloatingRotationButton()
                    .onTaskbarStateChanged(visible, stashed);
        }

        @Override
        public void onToggleRecentApps() {
            // The same case as onOverviewShown but only for 3-button navigation.
            mNavigationBarView.getRotationButtonController().setSkipOverrideUserLockPrefsOnce();
        }
    };

    private NavigationBarTransitions.DarkIntensityListener mOrientationHandleIntensityListener =
            new NavigationBarTransitions.DarkIntensityListener() {
                @Override
                public void onDarkIntensity(float darkIntensity) {
                    mOrientationHandle.setDarkIntensity(darkIntensity);
                }
            };

    private final Runnable mAutoDim = () -> getBarTransitions().setAutoDim(true);
    private final Runnable mEnableLayoutTransitions = () ->
            mNavigationBarView.setLayoutTransitionsEnabled(true);
    private final Runnable mOnVariableDurationHomeLongClick = () -> {
        if (onHomeLongClick(mNavigationBarView.getHomeButton().getCurrentView())) {
            mNavigationBarView.getHomeButton().getCurrentView().performHapticFeedback(
                    HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
    };

    private final DeviceConfig.OnPropertiesChangedListener mOnPropertiesChangedListener =
            new DeviceConfig.OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(DeviceConfig.Properties properties) {
                    if (properties.getKeyset().contains(NAV_BAR_HANDLE_FORCE_OPAQUE)) {
                        mForceNavBarHandleOpaque = properties.getBoolean(
                                NAV_BAR_HANDLE_FORCE_OPAQUE, /* defaultValue = */ true);
                    }

                    if (properties.getKeyset().contains(HOME_BUTTON_LONG_PRESS_DURATION_MS)) {
                        mHomeButtonLongPressDurationMs = Optional.of(
                            properties.getLong(HOME_BUTTON_LONG_PRESS_DURATION_MS, 0)
                        ).filter(duration -> duration != 0);
                        reconfigureHomeLongClick();
                    }
                }
            };

    private final DeviceProvisionedController.DeviceProvisionedListener mUserSetupListener =
            new DeviceProvisionedController.DeviceProvisionedListener() {
                @Override
                public void onUserSetupChanged() {
                    mIsCurrentUserSetup = mDeviceProvisionedController.isCurrentUserSetup();
                }
            };

    private final NotificationShadeDepthController.DepthListener mDepthListener =
            new NotificationShadeDepthController.DepthListener() {
                boolean mHasBlurs;

                @Override
                public void onWallpaperZoomOutChanged(float zoomOut) {
                }

                @Override
                public void onBlurRadiusChanged(int radius) {
                    boolean hasBlurs = radius != 0;
                    if (hasBlurs == mHasBlurs) {
                        return;
                    }
                    mHasBlurs = hasBlurs;
                    mNavigationBarView.setWindowHasBlurs(hasBlurs);
                }
            };

    private NavigationBar(Context context,
            WindowManager windowManager,
            Lazy<AssistManager> assistManagerLazy,
            AccessibilityManager accessibilityManager,
            DeviceProvisionedController deviceProvisionedController,
            MetricsLogger metricsLogger,
            OverviewProxyService overviewProxyService,
            NavigationModeController navigationModeController,
            AccessibilityButtonModeObserver accessibilityButtonModeObserver,
            StatusBarStateController statusBarStateController,
            SysUiState sysUiFlagsContainer,
            BroadcastDispatcher broadcastDispatcher,
            CommandQueue commandQueue,
            Optional<Pip> pipOptional,
            Optional<LegacySplitScreen> splitScreenOptional,
            Optional<Recents> recentsOptional,
            Lazy<Optional<StatusBar>> statusBarOptionalLazy,
            ShadeController shadeController,
            NotificationRemoteInputManager notificationRemoteInputManager,
            NotificationShadeDepthController notificationShadeDepthController,
            SystemActions systemActions,
            @Main Handler mainHandler,
            NavigationBarOverlayController navbarOverlayController,
            UiEventLogger uiEventLogger,
            NavBarHelper navBarHelper,
            UserTracker userTracker,
            LightBarController mainLightBarController,
            LightBarController.Factory lightBarControllerFactory,
            AutoHideController mainAutoHideController,
            AutoHideController.Factory autoHideControllerFactory,
            Optional<TelecomManager> telecomManagerOptional,
            InputMethodManager inputMethodManager) {
        mContext = context;
        mWindowManager = windowManager;
        mAccessibilityManager = accessibilityManager;
        mDeviceProvisionedController = deviceProvisionedController;
        mStatusBarStateController = statusBarStateController;
        mMetricsLogger = metricsLogger;
        mAssistManagerLazy = assistManagerLazy;
        mSysUiFlagsContainer = sysUiFlagsContainer;
        mStatusBarOptionalLazy = statusBarOptionalLazy;
        mShadeController = shadeController;
        mNotificationRemoteInputManager = notificationRemoteInputManager;
        mOverviewProxyService = overviewProxyService;
        mNavigationModeController = navigationModeController;
        mAccessibilityButtonModeObserver = accessibilityButtonModeObserver;
        mBroadcastDispatcher = broadcastDispatcher;
        mCommandQueue = commandQueue;
        mPipOptional = pipOptional;
        mSplitScreenOptional = splitScreenOptional;
        mRecentsOptional = recentsOptional;
        mSystemActions = systemActions;
        mHandler = mainHandler;
        mNavbarOverlayController = navbarOverlayController;
        mUiEventLogger = uiEventLogger;
        mNavBarHelper = navBarHelper;
        mNotificationShadeDepthController = notificationShadeDepthController;
        mMainLightBarController = mainLightBarController;
        mLightBarControllerFactory = lightBarControllerFactory;
        mMainAutoHideController = mainAutoHideController;
        mAutoHideControllerFactory = autoHideControllerFactory;
        mTelecomManagerOptional = telecomManagerOptional;
        mInputMethodManager = inputMethodManager;

        mNavBarMode = mNavigationModeController.addListener(this);
    }

    public NavigationBarView getView() {
        return mNavigationBarView;
    }

    public View createView(Bundle savedState) {
        mFrame = (NavigationBarFrame) LayoutInflater.from(mContext).inflate(
                R.layout.navigation_bar_window, null);
        View barView = LayoutInflater.from(mFrame.getContext()).inflate(
                R.layout.navigation_bar, mFrame);
        barView.addOnAttachStateChangeListener(this);
        mNavigationBarView = barView.findViewById(R.id.navigation_bar_view);

        if (DEBUG) Log.v(TAG, "addNavigationBar: about to add " + barView);
        mWindowManager.addView(mFrame,
                getBarLayoutParams(mContext.getResources().getConfiguration().windowConfiguration
                        .getRotation()));
        mDisplayId = mContext.getDisplayId();
        mIsOnDefaultDisplay = mDisplayId == DEFAULT_DISPLAY;

        mCommandQueue.addCallback(this);
        mLongPressHomeEnabled = mNavBarHelper.getLongPressHomeEnabled();
        mContentResolver = mContext.getContentResolver();
        mNavBarHelper.init();
        mAllowForceNavBarHandleOpaque = mContext.getResources().getBoolean(
                R.bool.allow_force_nav_bar_handle_opaque);
        mForceNavBarHandleOpaque = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                NAV_BAR_HANDLE_FORCE_OPAQUE,
                /* defaultValue = */ true);
        mHomeButtonLongPressDurationMs = Optional.of(DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                HOME_BUTTON_LONG_PRESS_DURATION_MS,
                /* defaultValue = */ 0
        )).filter(duration -> duration != 0);
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_SYSTEMUI, mHandler::post, mOnPropertiesChangedListener);

        if (savedState != null) {
            mDisabledFlags1 = savedState.getInt(EXTRA_DISABLE_STATE, 0);
            mDisabledFlags2 = savedState.getInt(EXTRA_DISABLE2_STATE, 0);
            mAppearance = savedState.getInt(EXTRA_APPEARANCE, 0);
            mBehavior = savedState.getInt(EXTRA_BEHAVIOR, 0);
            mTransientShown = savedState.getBoolean(EXTRA_TRANSIENT_STATE, false);
        }
        mSavedState = savedState;

        // Respect the latest disabled-flags.
        mCommandQueue.recomputeDisableFlags(mDisplayId, false);

        mIsCurrentUserSetup = mDeviceProvisionedController.isCurrentUserSetup();
        mDeviceProvisionedController.addCallback(mUserSetupListener);
        mNotificationShadeDepthController.addListener(mDepthListener);

        return barView;
    }

    public void destroyView() {
        setAutoHideController(/* autoHideController */ null);
        mCommandQueue.removeCallback(this);
        mWindowManager.removeViewImmediate(mNavigationBarView.getRootView());
        mNavigationModeController.removeListener(this);

        mNavBarHelper.removeNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
        mNavBarHelper.destroy();
        mDeviceProvisionedController.removeCallback(mUserSetupListener);
        mNotificationShadeDepthController.removeListener(mDepthListener);

        DeviceConfig.removeOnPropertiesChangedListener(mOnPropertiesChangedListener);
    }

    @Override
    public void onViewAttachedToWindow(View v) {
        final Display display = v.getDisplay();
        mNavigationBarView.setComponents(mRecentsOptional);
        mNavigationBarView.setComponents(mStatusBarOptionalLazy.get().get().getPanelController());
        mNavigationBarView.setDisabledFlags(mDisabledFlags1);
        mNavigationBarView.setOnVerticalChangedListener(this::onVerticalChanged);
        mNavigationBarView.setOnTouchListener(this::onNavigationTouch);
        if (mSavedState != null) {
            mNavigationBarView.getLightTransitionsController().restoreState(mSavedState);
        }
        mNavigationBarView.setNavigationIconHints(mNavigationIconHints);
        mNavigationBarView.setWindowVisible(isNavBarWindowVisible());
        mNavigationBarView.setBehavior(mBehavior);

        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);

        mSplitScreenOptional.ifPresent(mNavigationBarView::registerDockedListener);
        mPipOptional.ifPresent(mNavigationBarView::addPipExclusionBoundsChangeListener);

        prepareNavigationBarView();
        checkNavBarModes();

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        mBroadcastDispatcher.registerReceiverWithHandler(mBroadcastReceiver, filter,
                Handler.getMain(), UserHandle.ALL);
        notifyNavigationBarScreenOn();

        mOverviewProxyService.addCallback(mOverviewProxyListener);
        updateSystemUiStateFlags(-1);

        // Currently there is no accelerometer sensor on non-default display.
        if (mIsOnDefaultDisplay) {
            final RotationButtonController rotationButtonController =
                    mNavigationBarView.getRotationButtonController();
            rotationButtonController.setRotationCallback(mRotationWatcher);

            // Reset user rotation pref to match that of the WindowManager if starting in locked
            // mode. This will automatically happen when switching from auto-rotate to locked mode.
            if (display != null && rotationButtonController.isRotationLocked()) {
                rotationButtonController.setRotationLockedAtAngle(display.getRotation());
            }
        } else {
            mDisabledFlags2 |= StatusBarManager.DISABLE2_ROTATE_SUGGESTIONS;
        }
        setDisabled2Flags(mDisabledFlags2);

        initSecondaryHomeHandleForRotation();

        // Unfortunately, we still need it because status bar needs LightBarController
        // before notifications creation. We cannot directly use getLightBarController()
        // from NavigationBarFragment directly.
        LightBarController lightBarController = mIsOnDefaultDisplay
                ? mMainLightBarController : mLightBarControllerFactory.create(mContext);
        setLightBarController(lightBarController);

        // TODO(b/118592525): to support multi-display, we start to add something which is
        //                    per-display, while others may be global. I think it's time to
        //                    add a new class maybe named DisplayDependency to solve
        //                    per-display Dependency problem.
        // Alternative: this is a good case for a Dagger subcomponent. Same with LightBarController.
        AutoHideController autoHideController = mIsOnDefaultDisplay
                ? mMainAutoHideController : mAutoHideControllerFactory.create(mContext);
        setAutoHideController(autoHideController);
        restoreAppearanceAndTransientState();
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        final RotationButtonController rotationButtonController =
                mNavigationBarView.getRotationButtonController();
        rotationButtonController.setRotationCallback(null);
        mNavigationBarView.getBarTransitions().destroy();
        mNavigationBarView.getLightTransitionsController().destroy(mContext);
        mOverviewProxyService.removeCallback(mOverviewProxyListener);
        mBroadcastDispatcher.unregisterReceiver(mBroadcastReceiver);
        if (mOrientationHandle != null) {
            resetSecondaryHandle();
            getBarTransitions().removeDarkIntensityListener(mOrientationHandleIntensityListener);
            mWindowManager.removeView(mOrientationHandle);
            mOrientationHandle.getViewTreeObserver().removeOnGlobalLayoutListener(
                    mOrientationHandleGlobalLayoutListener);
        }
        mHandler.removeCallbacks(mAutoDim);
        mHandler.removeCallbacks(mOnVariableDurationHomeLongClick);
        mHandler.removeCallbacks(mEnableLayoutTransitions);
        mNavBarHelper.removeNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
        mPipOptional.ifPresent(mNavigationBarView::removePipExclusionBoundsChangeListener);
        mFrame = null;
        mNavigationBarView = null;
        mOrientationHandle = null;
    }

    // TODO: Remove this when we update nav bar recreation
    public void onSaveInstanceState(Bundle outState) {
        outState.putInt(EXTRA_DISABLE_STATE, mDisabledFlags1);
        outState.putInt(EXTRA_DISABLE2_STATE, mDisabledFlags2);
        outState.putInt(EXTRA_APPEARANCE, mAppearance);
        outState.putInt(EXTRA_BEHAVIOR, mBehavior);
        outState.putBoolean(EXTRA_TRANSIENT_STATE, mTransientShown);
        mNavigationBarView.getLightTransitionsController().saveState(outState);
    }

    /**
     * Called when a non-reloading configuration change happens and we need to update.
     */
    public void onConfigurationChanged(Configuration newConfig) {
        final int rotation = newConfig.windowConfiguration.getRotation();
        final Locale locale = mContext.getResources().getConfiguration().locale;
        final int ld = TextUtils.getLayoutDirectionFromLocale(locale);
        if (!locale.equals(mLocale) || ld != mLayoutDirection) {
            if (DEBUG) {
                Log.v(TAG, String.format(
                        "config changed locale/LD: %s (%d) -> %s (%d)", mLocale, mLayoutDirection,
                        locale, ld));
            }
            mLocale = locale;
            mLayoutDirection = ld;
            refreshLayout(ld);
        }

        repositionNavigationBar(rotation);
        if (canShowSecondaryHandle()) {
            if (rotation != mCurrentRotation) {
                mCurrentRotation = rotation;
                orientSecondaryHomeHandle();
            }
        }
    }

    private void initSecondaryHomeHandleForRotation() {
        if (mNavBarMode != NAV_BAR_MODE_GESTURAL) {
            return;
        }

        mOrientationHandle = new QuickswitchOrientedNavHandle(mContext);
        mOrientationHandle.setId(R.id.secondary_home_handle);

        getBarTransitions().addDarkIntensityListener(mOrientationHandleIntensityListener);
        mOrientationParams = new WindowManager.LayoutParams(0, 0,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_SLIPPERY,
                PixelFormat.TRANSLUCENT);
        mOrientationParams.setTitle("SecondaryHomeHandle" + mContext.getDisplayId());
        mOrientationParams.privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION;
        mWindowManager.addView(mOrientationHandle, mOrientationParams);
        mOrientationHandle.setVisibility(View.GONE);
        mOrientationParams.setFitInsetsTypes(0 /* types*/);
        mOrientationHandleGlobalLayoutListener =
                () -> {
                    if (mStartingQuickSwitchRotation == -1) {
                        return;
                    }

                    RectF boundsOnScreen = mOrientationHandle.computeHomeHandleBounds();
                    mOrientationHandle.mapRectFromViewToScreenCoords(boundsOnScreen, true);
                    Rect boundsRounded = new Rect();
                    boundsOnScreen.roundOut(boundsRounded);
                    mNavigationBarView.setOrientedHandleSamplingRegion(boundsRounded);
                };
        mOrientationHandle.getViewTreeObserver().addOnGlobalLayoutListener(
                mOrientationHandleGlobalLayoutListener);
    }

    private void orientSecondaryHomeHandle() {
        if (!canShowSecondaryHandle()) {
            return;
        }

        if (mStartingQuickSwitchRotation == -1 || mSplitScreenOptional
                .map(LegacySplitScreen::isDividerVisible).orElse(false)) {
            // Hide the secondary home handle if we are in multiwindow since apps in multiwindow
            // aren't allowed to set the display orientation
            resetSecondaryHandle();
        } else {
            int deltaRotation = deltaRotation(mCurrentRotation, mStartingQuickSwitchRotation);
            if (mStartingQuickSwitchRotation == -1 || deltaRotation == -1) {
                // Curious if starting quickswitch can change between the if check and our delta
                Log.d(TAG, "secondary nav delta rotation: " + deltaRotation
                        + " current: " + mCurrentRotation
                        + " starting: " + mStartingQuickSwitchRotation);
            }
            int height = 0;
            int width = 0;
            Rect dispSize = mWindowManager.getCurrentWindowMetrics().getBounds();
            mOrientationHandle.setDeltaRotation(deltaRotation);
            switch (deltaRotation) {
                case Surface.ROTATION_90:
                case Surface.ROTATION_270:
                    height = dispSize.height();
                    width = mNavigationBarView.getHeight();
                    break;
                case Surface.ROTATION_180:
                case Surface.ROTATION_0:
                    // TODO(b/152683657): Need to determine best UX for this
                    if (!mShowOrientedHandleForImmersiveMode) {
                        resetSecondaryHandle();
                        return;
                    }
                    width = dispSize.width();
                    height = mNavigationBarView.getHeight();
                    break;
            }

            mOrientationParams.gravity =
                    deltaRotation == Surface.ROTATION_0 ? Gravity.BOTTOM :
                            (deltaRotation == Surface.ROTATION_90 ? Gravity.LEFT : Gravity.RIGHT);
            mOrientationParams.height = height;
            mOrientationParams.width = width;
            mWindowManager.updateViewLayout(mOrientationHandle, mOrientationParams);
            mNavigationBarView.setVisibility(View.GONE);
            mOrientationHandle.setVisibility(View.VISIBLE);
        }
    }

    private void resetSecondaryHandle() {
        if (mOrientationHandle != null) {
            // Case where nav mode is changed w/o ever invoking a quickstep
            // mOrientedHandle is initialized lazily
            mOrientationHandle.setVisibility(View.GONE);
        }
        mNavigationBarView.setVisibility(View.VISIBLE);
        mNavigationBarView.setOrientedHandleSamplingRegion(null);
    }

    private void reconfigureHomeLongClick() {
        if (mNavigationBarView.getHomeButton().getCurrentView() == null) {
            return;
        }
        if (mHomeButtonLongPressDurationMs.isPresent() || !mLongPressHomeEnabled) {
            mNavigationBarView.getHomeButton().getCurrentView().setLongClickable(false);
            mNavigationBarView.getHomeButton().getCurrentView().setHapticFeedbackEnabled(false);
            mNavigationBarView.getHomeButton().setOnLongClickListener(null);
        } else {
            mNavigationBarView.getHomeButton().getCurrentView().setLongClickable(true);
            mNavigationBarView.getHomeButton().getCurrentView().setHapticFeedbackEnabled(true);
            mNavigationBarView.getHomeButton().setOnLongClickListener(this::onHomeLongClick);
        }
    }

    private int deltaRotation(int oldRotation, int newRotation) {
        int delta = newRotation - oldRotation;
        if (delta < 0) delta += 4;
        return delta;
    }

    public void dump(PrintWriter pw) {
        pw.println("NavigationBar (displayId=" + mDisplayId + "):");
        pw.println("  mStartingQuickSwitchRotation=" + mStartingQuickSwitchRotation);
        pw.println("  mCurrentRotation=" + mCurrentRotation);
        pw.println("  mHomeButtonLongPressDurationMs=" + mHomeButtonLongPressDurationMs);
        pw.println("  mLongPressHomeEnabled=" + mLongPressHomeEnabled);
        pw.println("  mNavigationBarWindowState="
                + windowStateToString(mNavigationBarWindowState));
        pw.println("  mNavigationBarMode="
                + BarTransitions.modeToString(mNavigationBarMode));
        pw.println("  mTransientShown=" + mTransientShown);
        pw.println("  mTransientShownFromGestureOnSystemBar="
                + mTransientShownFromGestureOnSystemBar);
        dumpBarTransitions(pw, "mNavigationBarView", mNavigationBarView.getBarTransitions());
        mNavigationBarView.dump(pw);
    }

    // ----- CommandQueue Callbacks -----

    @Override
    public void setImeWindowStatus(int displayId, IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
        if (displayId != mDisplayId) {
            return;
        }
        boolean imeShown = mNavBarHelper.isImeShown(vis);
        showImeSwitcher = imeShown && showImeSwitcher;
        int hints = Utilities.calculateBackDispositionHints(mNavigationIconHints, backDisposition,
                imeShown, showImeSwitcher);
        if (hints == mNavigationIconHints) return;

        mNavigationIconHints = hints;
        if (!isTablet(mContext)) {
            // All IME functions handled by launcher via Sysui flags for large screen
            mNavigationBarView.setNavigationIconHints(hints);
        }
        checkBarModes();
        updateSystemUiStateFlags(-1);
    }

    @Override
    public void setWindowState(
            int displayId, @WindowType int window, @WindowVisibleState int state) {
        if (displayId == mDisplayId
                && window == StatusBarManager.WINDOW_NAVIGATION_BAR
                && mNavigationBarWindowState != state) {
            mNavigationBarWindowState = state;
            updateSystemUiStateFlags(-1);
            mShowOrientedHandleForImmersiveMode = state == WINDOW_STATE_HIDDEN;
            if (mOrientationHandle != null
                    && mStartingQuickSwitchRotation != -1) {
                orientSecondaryHomeHandle();
            }
            if (DEBUG_WINDOW_STATE) Log.d(TAG, "Navigation bar " + windowStateToString(state));
            mNavigationBarView.setWindowVisible(isNavBarWindowVisible());
        }
    }

    @Override
    public void onRotationProposal(final int rotation, boolean isValid) {
        // The CommandQueue callbacks are added when the view is created to ensure we track other
        // states, but until the view is attached (at the next traversal), the view's display is
        // not valid.  Just ignore the rotation in this case.
        if (!mNavigationBarView.isAttachedToWindow()) return;

        final boolean rotateSuggestionsDisabled = RotationButtonController
                .hasDisable2RotateSuggestionFlag(mDisabledFlags2);
        final RotationButtonController rotationButtonController =
                mNavigationBarView.getRotationButtonController();
        final RotationButton rotationButton = rotationButtonController.getRotationButton();

        if (RotationContextButton.DEBUG_ROTATION) {
            Log.v(TAG, "onRotationProposal proposedRotation=" + Surface.rotationToString(rotation)
                    + ", isValid=" + isValid + ", mNavBarWindowState="
                    + StatusBarManager.windowStateToString(mNavigationBarWindowState)
                    + ", rotateSuggestionsDisabled=" + rotateSuggestionsDisabled
                    + ", isRotateButtonVisible=" + rotationButton.isVisible());
        }

        // Respect the disabled flag, no need for action as flag change callback will handle hiding
        if (rotateSuggestionsDisabled) return;

        rotationButtonController.onRotationProposal(rotation, isValid);
    }

    @Override
    public void onRecentsAnimationStateChanged(boolean running) {
        if (running) {
            mNavbarOverlayController.setButtonState(/* visible */false, /* force */true);
        }
        mNavigationBarView.getRotationButtonController().setRecentsAnimationRunning(running);
    }

    /** Restores the appearance and the transient saved state to {@link NavigationBar}. */
    public void restoreAppearanceAndTransientState() {
        final int barMode = barMode(mTransientShown, mAppearance);
        mNavigationBarMode = barMode;
        checkNavBarModes();
        if (mAutoHideController != null) {
            mAutoHideController.touchAutoHide();
        }
        if (mLightBarController != null) {
            mLightBarController.onNavigationBarAppearanceChanged(mAppearance,
                    true /* nbModeChanged */, barMode, false /* navbarColorManagedByIme */);
        }
    }

    @Override
    public void onSystemBarAttributesChanged(int displayId, @Appearance int appearance,
            AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme,
            @Behavior int behavior, InsetsVisibilities requestedVisibilities, String packageName) {
        if (displayId != mDisplayId) {
            return;
        }
        boolean nbModeChanged = false;
        if (mAppearance != appearance) {
            mAppearance = appearance;
            nbModeChanged = updateBarMode(barMode(mTransientShown, appearance));
        }
        if (mLightBarController != null) {
            mLightBarController.onNavigationBarAppearanceChanged(appearance, nbModeChanged,
                    mNavigationBarMode, navbarColorManagedByIme);
        }
        if (mBehavior != behavior) {
            mBehavior = behavior;
            mNavigationBarView.setBehavior(behavior);
            updateSystemUiStateFlags(-1);
        }
    }

    @Override
    public void showTransient(int displayId, @InternalInsetsType int[] types,
            boolean isGestureOnSystemBar) {
        if (displayId != mDisplayId) {
            return;
        }
        if (!containsType(types, ITYPE_NAVIGATION_BAR)) {
            return;
        }
        if (!mTransientShown) {
            mTransientShown = true;
            mTransientShownFromGestureOnSystemBar = isGestureOnSystemBar;
            handleTransientChanged();
        }
    }

    @Override
    public void abortTransient(int displayId, @InternalInsetsType int[] types) {
        if (displayId != mDisplayId) {
            return;
        }
        if (!containsType(types, ITYPE_NAVIGATION_BAR)) {
            return;
        }
        clearTransient();
    }

    private void clearTransient() {
        if (mTransientShown) {
            mTransientShown = false;
            mTransientShownFromGestureOnSystemBar = false;
            handleTransientChanged();
        }
    }

    private void handleTransientChanged() {
        mNavigationBarView.onTransientStateChanged(mTransientShown,
                mTransientShownFromGestureOnSystemBar);
        final int barMode = barMode(mTransientShown, mAppearance);
        if (updateBarMode(barMode) && mLightBarController != null) {
            mLightBarController.onNavigationBarModeChanged(barMode);
        }
    }

    // Returns true if the bar mode is changed.
    private boolean updateBarMode(int barMode) {
        if (mNavigationBarMode != barMode) {
            mNavigationBarMode = barMode;
            checkNavBarModes();
            if (mAutoHideController != null) {
                mAutoHideController.touchAutoHide();
            }
            return true;
        }
        return false;
    }

    private static @TransitionMode int barMode(boolean isTransient, int appearance) {
        final int lightsOutOpaque = APPEARANCE_LOW_PROFILE_BARS | APPEARANCE_OPAQUE_NAVIGATION_BARS;
        if (isTransient) {
            return MODE_SEMI_TRANSPARENT;
        } else if ((appearance & lightsOutOpaque) == lightsOutOpaque) {
            return MODE_LIGHTS_OUT;
        } else if ((appearance & APPEARANCE_LOW_PROFILE_BARS) != 0) {
            return MODE_LIGHTS_OUT_TRANSPARENT;
        } else if ((appearance & APPEARANCE_OPAQUE_NAVIGATION_BARS) != 0) {
            return MODE_OPAQUE;
        } else if ((appearance & APPEARANCE_SEMI_TRANSPARENT_NAVIGATION_BARS) != 0) {
            return MODE_SEMI_TRANSPARENT;
        } else {
            return MODE_TRANSPARENT;
        }
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId != mDisplayId) {
            return;
        }
        // Navigation bar flags are in both state1 and state2.
        final int masked = state1 & (StatusBarManager.DISABLE_HOME
                | StatusBarManager.DISABLE_RECENT
                | StatusBarManager.DISABLE_BACK
                | StatusBarManager.DISABLE_SEARCH);
        if (masked != mDisabledFlags1) {
            mDisabledFlags1 = masked;
            mNavigationBarView.setDisabledFlags(state1);
            updateScreenPinningGestures();
        }

        // Only default display supports rotation suggestions.
        if (mIsOnDefaultDisplay) {
            final int masked2 = state2 & (StatusBarManager.DISABLE2_ROTATE_SUGGESTIONS);
            if (masked2 != mDisabledFlags2) {
                mDisabledFlags2 = masked2;
                setDisabled2Flags(masked2);
            }
        }
    }

    private void setDisabled2Flags(int state2) {
        // Method only called on change of disable2 flags
        mNavigationBarView.getRotationButtonController().onDisable2FlagChanged(state2);
    }

    // ----- Internal stuff -----

    private void refreshLayout(int layoutDirection) {
        mNavigationBarView.setLayoutDirection(layoutDirection);
    }

    private boolean shouldDisableNavbarGestures() {
        return !mDeviceProvisionedController.isDeviceProvisioned()
                || (mDisabledFlags1 & StatusBarManager.DISABLE_SEARCH) != 0;
    }

    private void repositionNavigationBar(int rotation) {
        if (mNavigationBarView == null || !mNavigationBarView.isAttachedToWindow()) return;

        prepareNavigationBarView();

        mWindowManager.updateViewLayout(mFrame, getBarLayoutParams(rotation));
    }

    private void updateScreenPinningGestures() {
        // Change the cancel pin gesture to home and back if recents button is invisible
        boolean pinningActive = ActivityManagerWrapper.getInstance().isScreenPinningActive();
        ButtonDispatcher backButton = mNavigationBarView.getBackButton();
        ButtonDispatcher recentsButton = mNavigationBarView.getRecentsButton();
        if (pinningActive) {
            boolean recentsVisible = mNavigationBarView.isRecentsButtonVisible();
            backButton.setOnLongClickListener(recentsVisible
                    ? this::onLongPressBackRecents
                    : this::onLongPressBackHome);
            recentsButton.setOnLongClickListener(this::onLongPressBackRecents);
        } else {
            backButton.setOnLongClickListener(null);
            recentsButton.setOnLongClickListener(null);
        }
        // Note, this needs to be set after even if we're setting the listener to null
        backButton.setLongClickable(pinningActive);
        recentsButton.setLongClickable(pinningActive);
    }

    private void notifyNavigationBarScreenOn() {
        mNavigationBarView.updateNavButtonIcons();
    }

    private void prepareNavigationBarView() {
        mNavigationBarView.reorient();

        ButtonDispatcher recentsButton = mNavigationBarView.getRecentsButton();
        recentsButton.setOnClickListener(this::onRecentsClick);
        recentsButton.setOnTouchListener(this::onRecentsTouch);

        ButtonDispatcher homeButton = mNavigationBarView.getHomeButton();
        homeButton.setOnTouchListener(this::onHomeTouch);

        reconfigureHomeLongClick();

        ButtonDispatcher accessibilityButton = mNavigationBarView.getAccessibilityButton();
        accessibilityButton.setOnClickListener(this::onAccessibilityClick);
        accessibilityButton.setOnLongClickListener(this::onAccessibilityLongClick);
        updateAcessibilityStateFlags();

        ButtonDispatcher imeSwitcherButton = mNavigationBarView.getImeSwitchButton();
        imeSwitcherButton.setOnClickListener(this::onImeSwitcherClick);

        updateScreenPinningGestures();
    }

    @VisibleForTesting
    boolean onHomeTouch(View v, MotionEvent event) {
        if (mHomeBlockedThisTouch && event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return true;
        }
        // If an incoming call is ringing, HOME is totally disabled.
        // (The user is already on the InCallUI at this point,
        // and their ONLY options are to answer or reject the call.)
        final Optional<StatusBar> statusBarOptional = mStatusBarOptionalLazy.get();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mHomeBlockedThisTouch = false;
                if (mTelecomManagerOptional.isPresent()
                        && mTelecomManagerOptional.get().isRinging()) {
                    if (statusBarOptional.map(StatusBar::isKeyguardShowing).orElse(false)) {
                        Log.i(TAG, "Ignoring HOME; there's a ringing incoming call. " +
                                "No heads up");
                        mHomeBlockedThisTouch = true;
                        return true;
                    }
                }
                if (mLongPressHomeEnabled) {
                    mHomeButtonLongPressDurationMs.ifPresent(longPressDuration -> {
                        mHandler.postDelayed(mOnVariableDurationHomeLongClick, longPressDuration);
                    });
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mHandler.removeCallbacks(mOnVariableDurationHomeLongClick);
                statusBarOptional.ifPresent(StatusBar::awakenDreams);
                break;
        }
        return false;
    }

    private void onVerticalChanged(boolean isVertical) {
        mStatusBarOptionalLazy.get().ifPresent(
                statusBar -> statusBar.setQsScrimEnabled(!isVertical));
    }

    private boolean onNavigationTouch(View v, MotionEvent event) {
        if (mAutoHideController != null) {
            mAutoHideController.checkUserAutoHide(event);
        }
        return false;
    }

    @VisibleForTesting
    boolean onHomeLongClick(View v) {
        if (!mNavigationBarView.isRecentsButtonVisible()
                && ActivityManagerWrapper.getInstance().isScreenPinningActive()) {
            return onLongPressBackHome(v);
        }
        if (shouldDisableNavbarGestures()) {
            return false;
        }
        mMetricsLogger.action(MetricsEvent.ACTION_ASSIST_LONG_PRESS);
        mUiEventLogger.log(NavBarActionEvent.NAVBAR_ASSIST_LONGPRESS);
        Bundle args = new Bundle();
        args.putInt(
                AssistManager.INVOCATION_TYPE_KEY,
                AssistManager.INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS);
        mAssistManagerLazy.get().startAssist(args);
        mStatusBarOptionalLazy.get().ifPresent(StatusBar::awakenDreams);
        mNavigationBarView.abortCurrentGesture();
        return true;
    }

    // additional optimization when we have software system buttons - start loading the recent
    // tasks on touch down
    private boolean onRecentsTouch(View v, MotionEvent event) {
        int action = event.getAction() & MotionEvent.ACTION_MASK;
        if (action == MotionEvent.ACTION_DOWN) {
            mCommandQueue.preloadRecentApps();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            mCommandQueue.cancelPreloadRecentApps();
        } else if (action == MotionEvent.ACTION_UP) {
            if (!v.isPressed()) {
                mCommandQueue.cancelPreloadRecentApps();
            }
        }
        return false;
    }

    private void onRecentsClick(View v) {
        if (LatencyTracker.isEnabled(mContext)) {
            LatencyTracker.getInstance(mContext).onActionStart(
                    LatencyTracker.ACTION_TOGGLE_RECENTS);
        }
        mStatusBarOptionalLazy.get().ifPresent(StatusBar::awakenDreams);
        mCommandQueue.toggleRecentApps();
    }

    private void onImeSwitcherClick(View v) {
        mInputMethodManager.showInputMethodPickerFromSystem(
                true /* showAuxiliarySubtypes */, mDisplayId);
        mUiEventLogger.log(KeyButtonView.NavBarButtonEvent.NAVBAR_IME_SWITCHER_BUTTON_TAP);
    };

    private boolean onLongPressBackHome(View v) {
        return onLongPressNavigationButtons(v, R.id.back, R.id.home);
    }

    private boolean onLongPressBackRecents(View v) {
        return onLongPressNavigationButtons(v, R.id.back, R.id.recent_apps);
    }

    /**
     * This handles long-press of both back and recents/home. Back is the common button with
     * combination of recents if it is visible or home if recents is invisible.
     * They are handled together to capture them both being long-pressed
     * at the same time to exit screen pinning (lock task).
     *
     * When accessibility mode is on, only a long-press from recents/home
     * is required to exit.
     *
     * In all other circumstances we try to pass through long-press events
     * for Back, so that apps can still use it.  Which can be from two things.
     * 1) Not currently in screen pinning (lock task).
     * 2) Back is long-pressed without recents/home.
     */
    private boolean onLongPressNavigationButtons(View v, @IdRes int btnId1, @IdRes int btnId2) {
        try {
            boolean sendBackLongPress = false;
            IActivityTaskManager activityManager = ActivityTaskManager.getService();
            boolean touchExplorationEnabled = mAccessibilityManager.isTouchExplorationEnabled();
            boolean inLockTaskMode = activityManager.isInLockTaskMode();
            boolean stopLockTaskMode = false;
            try {
                if (inLockTaskMode && !touchExplorationEnabled) {
                    long time = System.currentTimeMillis();

                    // If we recently long-pressed the other button then they were
                    // long-pressed 'together'
                    if ((time - mLastLockToAppLongPress) < LOCK_TO_APP_GESTURE_TOLERENCE) {
                        stopLockTaskMode = true;
                        return true;
                    } else if (v.getId() == btnId1) {
                        ButtonDispatcher button = btnId2 == R.id.recent_apps
                                ? mNavigationBarView.getRecentsButton()
                                : mNavigationBarView.getHomeButton();
                        if (!button.getCurrentView().isPressed()) {
                            // If we aren't pressing recents/home right now then they presses
                            // won't be together, so send the standard long-press action.
                            sendBackLongPress = true;
                        }
                    }
                    mLastLockToAppLongPress = time;
                } else {
                    // If this is back still need to handle sending the long-press event.
                    if (v.getId() == btnId1) {
                        sendBackLongPress = true;
                    } else if (touchExplorationEnabled && inLockTaskMode) {
                        // When in accessibility mode a long press that is recents/home (not back)
                        // should stop lock task.
                        stopLockTaskMode = true;
                        return true;
                    } else if (v.getId() == btnId2) {
                        return btnId2 == R.id.recent_apps
                                ? onLongPressRecents()
                                : onHomeLongClick(
                                        mNavigationBarView.getHomeButton().getCurrentView());
                    }
                }
            } finally {
                if (stopLockTaskMode) {
                    activityManager.stopSystemLockTaskMode();
                    // When exiting refresh disabled flags.
                    mNavigationBarView.updateNavButtonIcons();
                }
            }

            if (sendBackLongPress) {
                KeyButtonView keyButtonView = (KeyButtonView) v;
                keyButtonView.sendEvent(KeyEvent.ACTION_DOWN, KeyEvent.FLAG_LONG_PRESS);
                keyButtonView.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                return true;
            }
        } catch (RemoteException e) {
            Log.d(TAG, "Unable to reach activity manager", e);
        }
        return false;
    }

    private boolean onLongPressRecents() {
        if (mRecentsOptional.isPresent() || !ActivityTaskManager.supportsMultiWindow(mContext)
                || ActivityManager.isLowRamDeviceStatic()
                // If we are connected to the overview service, then disable the recents button
                || mOverviewProxyService.getProxy() != null
                || !mSplitScreenOptional.map(splitScreen ->
                splitScreen.getDividerView().getSnapAlgorithm().isSplitScreenFeasible())
                .orElse(false)) {
            return false;
        }

        return mStatusBarOptionalLazy.get().map(
                statusBar -> statusBar.toggleSplitScreenMode(
                        MetricsEvent.ACTION_WINDOW_DOCK_LONGPRESS,
                        MetricsEvent.ACTION_WINDOW_UNDOCK_LONGPRESS))
            .orElse(false);
    }

    private void onAccessibilityClick(View v) {
        final Display display = v.getDisplay();
        mAccessibilityManager.notifyAccessibilityButtonClicked(
                display != null ? display.getDisplayId() : DEFAULT_DISPLAY);
    }

    private boolean onAccessibilityLongClick(View v) {
        final Intent intent = new Intent(AccessibilityManager.ACTION_CHOOSE_ACCESSIBILITY_BUTTON);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        final String chooserClassName = AccessibilityButtonChooserActivity.class.getName();
        intent.setClassName(CHOOSER_PACKAGE_NAME, chooserClassName);
        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        return true;
    }

    void updateAcessibilityStateFlags() {
        int a11yFlags = mNavBarHelper.getA11yButtonState();

        if (mNavigationBarView != null) {
            boolean clickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_CLICKABLE) != 0;
            boolean longClickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE) != 0;
            mNavigationBarView.setAccessibilityButtonState(clickable, longClickable);
        }
        updateSystemUiStateFlags(a11yFlags);
    }

    public void updateSystemUiStateFlags(int a11yFlags) {
        if (a11yFlags < 0) {
            a11yFlags = mNavBarHelper.getA11yButtonState();
        }
        boolean clickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_CLICKABLE) != 0;
        boolean longClickable = (a11yFlags & SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE) != 0;

        mSysUiFlagsContainer.setFlag(SYSUI_STATE_A11Y_BUTTON_CLICKABLE, clickable)
                .setFlag(SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE, longClickable)
                .setFlag(SYSUI_STATE_NAV_BAR_HIDDEN, !isNavBarWindowVisible())
                .setFlag(SYSUI_STATE_IME_SHOWING,
                        (mNavigationIconHints & NAVIGATION_HINT_BACK_ALT) != 0)
                .setFlag(SYSUI_STATE_IME_SWITCHER_SHOWING,
                        (mNavigationIconHints & NAVIGATION_HINT_IME_SHOWN) != 0)
                .setFlag(SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY,
                        allowSystemGestureIgnoringBarVisibility())
                .commitUpdate(mDisplayId);
        registerAction(clickable, SystemActions.SYSTEM_ACTION_ID_ACCESSIBILITY_BUTTON);
        registerAction(longClickable, SystemActions.SYSTEM_ACTION_ID_ACCESSIBILITY_BUTTON_CHOOSER);
    }

    private void registerAction(boolean register, int actionId) {
        if (register) {
            mSystemActions.register(actionId);
        } else {
            mSystemActions.unregister(actionId);
        }
    }

    private void updateAssistantEntrypoints(boolean assistantAvailable) {
        if (mOverviewProxyService.getProxy() != null) {
            try {
                mOverviewProxyService.getProxy().onAssistantAvailable(assistantAvailable);
            } catch (RemoteException e) {
                Log.w(TAG, "Unable to send assistant availability data to launcher");
            }
        }
        reconfigureHomeLongClick();
    }

    // ----- Methods that DisplayNavigationBarController talks to -----

    /** Applies auto dimming animation on navigation bar when touched. */
    public void touchAutoDim() {
        getBarTransitions().setAutoDim(false);
        mHandler.removeCallbacks(mAutoDim);
        int state = mStatusBarStateController.getState();
        if (state != StatusBarState.KEYGUARD && state != StatusBarState.SHADE_LOCKED) {
            mHandler.postDelayed(mAutoDim, AUTODIM_TIMEOUT_MS);
        }
    }

    public void setLightBarController(LightBarController lightBarController) {
        mLightBarController = lightBarController;
        if (mLightBarController != null) {
            mLightBarController.setNavigationBar(
                    mNavigationBarView.getLightTransitionsController());
        }
    }

    /** Sets {@link AutoHideController} to the navigation bar. */
    private void setAutoHideController(AutoHideController autoHideController) {
        mAutoHideController = autoHideController;
        if (mAutoHideController != null) {
            mAutoHideController.setNavigationBar(mAutoHideUiElement);
        }
        mNavigationBarView.setAutoHideController(autoHideController);
    }

    private boolean isTransientShown() {
        return mTransientShown;
    }

    private void checkBarModes() {
        // We only have status bar on default display now.
        if (mIsOnDefaultDisplay) {
            mStatusBarOptionalLazy.get().ifPresent(StatusBar::checkBarModes);
        } else {
            checkNavBarModes();
        }
    }

    public boolean isNavBarWindowVisible() {
        return mNavigationBarWindowState == WINDOW_STATE_SHOWING;
    }

    private boolean allowSystemGestureIgnoringBarVisibility() {
        return mBehavior != BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
    }

    /**
     * Checks current navigation bar mode and make transitions.
     */
    public void checkNavBarModes() {
        final boolean anim =
                mStatusBarOptionalLazy.get().map(StatusBar::isDeviceInteractive).orElse(false)
                && mNavigationBarWindowState != WINDOW_STATE_HIDDEN;
        mNavigationBarView.getBarTransitions().transitionTo(mNavigationBarMode, anim);
    }

    @Override
    public void onNavigationModeChanged(int mode) {
        mNavBarMode = mode;

        if (!QuickStepContract.isGesturalMode(mode)) {
            // Reset the override alpha
            if (getBarTransitions() != null) {
                getBarTransitions().setBackgroundOverrideAlpha(1f);
            }
        }
        updateScreenPinningGestures();

        if (!canShowSecondaryHandle()) {
            resetSecondaryHandle();
        }
    }

    public void disableAnimationsDuringHide(long delay) {
        mNavigationBarView.setLayoutTransitionsEnabled(false);
        mHandler.postDelayed(mEnableLayoutTransitions,
                delay + StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE);
    }

    /**
     * Performs transitions on navigation bar.
     *
     * @param barMode transition bar mode.
     * @param animate shows animations if {@code true}.
     */
    public void transitionTo(@TransitionMode int barMode, boolean animate) {
        getBarTransitions().transitionTo(barMode, animate);
    }

    public NavigationBarTransitions getBarTransitions() {
        return mNavigationBarView.getBarTransitions();
    }

    public void finishBarAnimations() {
        mNavigationBarView.getBarTransitions().finishAnimations();
    }

    private WindowManager.LayoutParams getBarLayoutParams(int rotation) {
        WindowManager.LayoutParams lp = getBarLayoutParamsForRotation(rotation);
        lp.paramsForRotation = new WindowManager.LayoutParams[4];
        for (int rot = Surface.ROTATION_0; rot <= Surface.ROTATION_270; rot++) {
            lp.paramsForRotation[rot] = getBarLayoutParamsForRotation(rot);
        }
        return lp;
    }

    private WindowManager.LayoutParams getBarLayoutParamsForRotation(int rotation) {
        int width = WindowManager.LayoutParams.MATCH_PARENT;
        int height = WindowManager.LayoutParams.MATCH_PARENT;
        int insetsHeight = -1;
        int gravity = Gravity.BOTTOM;
        if (INSETS_LAYOUT_GENERALIZATION) {
            boolean navBarCanMove = true;
            if (mWindowManager != null && mWindowManager.getCurrentWindowMetrics() != null) {
                Rect displaySize = mWindowManager.getCurrentWindowMetrics().getBounds();
                navBarCanMove = displaySize.width() != displaySize.height()
                        && mContext.getResources().getBoolean(
                        com.android.internal.R.bool.config_navBarCanMove);
            }
            if (!navBarCanMove) {
                height = mContext.getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.navigation_bar_frame_height);
                insetsHeight = mContext.getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.navigation_bar_height);
            } else {
                switch (rotation) {
                    case ROTATION_UNDEFINED:
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        height = mContext.getResources().getDimensionPixelSize(
                                com.android.internal.R.dimen.navigation_bar_frame_height);
                        insetsHeight = mContext.getResources().getDimensionPixelSize(
                                com.android.internal.R.dimen.navigation_bar_height);
                        break;
                    case Surface.ROTATION_90:
                        gravity = Gravity.RIGHT;
                        width = mContext.getResources().getDimensionPixelSize(
                                com.android.internal.R.dimen.navigation_bar_width);
                        break;
                    case Surface.ROTATION_270:
                        gravity = Gravity.LEFT;
                        width = mContext.getResources().getDimensionPixelSize(
                                com.android.internal.R.dimen.navigation_bar_width);
                        break;
                }
            }
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
                WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_SLIPPERY,
                PixelFormat.TRANSLUCENT);
        if (INSETS_LAYOUT_GENERALIZATION) {
            lp.gravity = gravity;
            if (insetsHeight != -1) {
                lp.providedInternalInsets = Insets.of(0, height - insetsHeight, 0, 0);
            } else {
                lp.providedInternalInsets = Insets.NONE;
            }
        }
        lp.token = new Binder();
        lp.accessibilityTitle = mContext.getString(R.string.nav_bar);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.windowAnimations = 0;
        lp.setTitle("NavigationBar" + mContext.getDisplayId());
        lp.setFitInsetsTypes(0 /* types */);
        lp.setTrustedOverlay();
        return lp;
    }

    private boolean canShowSecondaryHandle() {
        return mNavBarMode == NAV_BAR_MODE_GESTURAL && mOrientationHandle != null;
    }

    private final Consumer<Integer> mRotationWatcher = rotation -> {
        if (mNavigationBarView != null
                && mNavigationBarView.needsReorient(rotation)) {
            repositionNavigationBar(rotation);
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // TODO(193941146): Currently unregistering a receiver through BroadcastDispatcher is
            // async, but we've already cleared the fields. Just return early in this case.
            if (mNavigationBarView == null) {
                return;
            }
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)
                    || Intent.ACTION_SCREEN_ON.equals(action)) {
                notifyNavigationBarScreenOn();
                mNavigationBarView.onScreenStateChanged(Intent.ACTION_SCREEN_ON.equals(action));
            }
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                // The accessibility settings may be different for the new user
                updateAcessibilityStateFlags();
            }
        }
    };

    @VisibleForTesting
    int getNavigationIconHints() {
        return mNavigationIconHints;
    }

    /**
     * Injectable factory for construction a {@link NavigationBar}.
     */
    public static class Factory {
        private final Lazy<AssistManager> mAssistManagerLazy;
        private final AccessibilityManager mAccessibilityManager;
        private final DeviceProvisionedController mDeviceProvisionedController;
        private final MetricsLogger mMetricsLogger;
        private final OverviewProxyService mOverviewProxyService;
        private final NavigationModeController mNavigationModeController;
        private final AccessibilityButtonModeObserver mAccessibilityButtonModeObserver;
        private final StatusBarStateController mStatusBarStateController;
        private final SysUiState mSysUiFlagsContainer;
        private final BroadcastDispatcher mBroadcastDispatcher;
        private final CommandQueue mCommandQueue;
        private final Optional<Pip> mPipOptional;
        private final Optional<LegacySplitScreen> mSplitScreenOptional;
        private final Optional<Recents> mRecentsOptional;
        private final Lazy<Optional<StatusBar>> mStatusBarOptionalLazy;
        private final ShadeController mShadeController;
        private final NotificationRemoteInputManager mNotificationRemoteInputManager;
        private final NotificationShadeDepthController mNotificationShadeDepthController;
        private final SystemActions mSystemActions;
        private final Handler mMainHandler;
        private final NavigationBarOverlayController mNavbarOverlayController;
        private final UiEventLogger mUiEventLogger;
        private final NavBarHelper mNavBarHelper;
        private final UserTracker mUserTracker;
        private final LightBarController mMainLightBarController;
        private final LightBarController.Factory mLightBarControllerFactory;
        private final AutoHideController mMainAutoHideController;
        private final AutoHideController.Factory mAutoHideControllerFactory;
        private final Optional<TelecomManager> mTelecomManagerOptional;
        private final InputMethodManager mInputMethodManager;

        @Inject
        public Factory(
                Lazy<AssistManager> assistManagerLazy,
                AccessibilityManager accessibilityManager,
                DeviceProvisionedController deviceProvisionedController,
                MetricsLogger metricsLogger,
                OverviewProxyService overviewProxyService,
                NavigationModeController navigationModeController,
                AccessibilityButtonModeObserver accessibilityButtonModeObserver,
                StatusBarStateController statusBarStateController,
                SysUiState sysUiFlagsContainer,
                BroadcastDispatcher broadcastDispatcher,
                CommandQueue commandQueue,
                Optional<Pip> pipOptional,
                Optional<LegacySplitScreen> splitScreenOptional,
                Optional<Recents> recentsOptional,
                Lazy<Optional<StatusBar>> statusBarOptionalLazy,
                ShadeController shadeController,
                NotificationRemoteInputManager notificationRemoteInputManager,
                NotificationShadeDepthController notificationShadeDepthController,
                SystemActions systemActions,
                @Main Handler mainHandler,
                NavigationBarOverlayController navbarOverlayController,
                UiEventLogger uiEventLogger,
                NavBarHelper navBarHelper,
                UserTracker userTracker,
                LightBarController mainLightBarController,
                LightBarController.Factory lightBarControllerFactory,
                AutoHideController mainAutoHideController,
                AutoHideController.Factory autoHideControllerFactory,
                Optional<TelecomManager> telecomManagerOptional,
                InputMethodManager inputMethodManager) {
            mAssistManagerLazy = assistManagerLazy;
            mAccessibilityManager = accessibilityManager;
            mDeviceProvisionedController = deviceProvisionedController;
            mMetricsLogger = metricsLogger;
            mOverviewProxyService = overviewProxyService;
            mNavigationModeController = navigationModeController;
            mAccessibilityButtonModeObserver = accessibilityButtonModeObserver;
            mStatusBarStateController = statusBarStateController;
            mSysUiFlagsContainer = sysUiFlagsContainer;
            mBroadcastDispatcher = broadcastDispatcher;
            mCommandQueue = commandQueue;
            mPipOptional = pipOptional;
            mSplitScreenOptional = splitScreenOptional;
            mRecentsOptional = recentsOptional;
            mStatusBarOptionalLazy = statusBarOptionalLazy;
            mShadeController = shadeController;
            mNotificationRemoteInputManager = notificationRemoteInputManager;
            mNotificationShadeDepthController = notificationShadeDepthController;
            mSystemActions = systemActions;
            mMainHandler = mainHandler;
            mNavbarOverlayController = navbarOverlayController;
            mUiEventLogger = uiEventLogger;
            mNavBarHelper = navBarHelper;
            mUserTracker = userTracker;
            mMainLightBarController = mainLightBarController;
            mLightBarControllerFactory = lightBarControllerFactory;
            mMainAutoHideController = mainAutoHideController;
            mAutoHideControllerFactory = autoHideControllerFactory;
            mTelecomManagerOptional = telecomManagerOptional;
            mInputMethodManager = inputMethodManager;
        }

        /** Construct a {@link NavigationBar} */
        public NavigationBar create(Context context) {
            final WindowManager wm = context.getSystemService(WindowManager.class);
            return new NavigationBar(context, wm, mAssistManagerLazy,
                    mAccessibilityManager, mDeviceProvisionedController, mMetricsLogger,
                    mOverviewProxyService, mNavigationModeController,
                    mAccessibilityButtonModeObserver, mStatusBarStateController,
                    mSysUiFlagsContainer, mBroadcastDispatcher, mCommandQueue, mPipOptional,
                    mSplitScreenOptional, mRecentsOptional, mStatusBarOptionalLazy,
                    mShadeController, mNotificationRemoteInputManager,
                    mNotificationShadeDepthController, mSystemActions, mMainHandler,
                    mNavbarOverlayController, mUiEventLogger, mNavBarHelper,
                    mUserTracker, mMainLightBarController, mLightBarControllerFactory,
                    mMainAutoHideController, mAutoHideControllerFactory, mTelecomManagerOptional,
                    mInputMethodManager);
        }
    }
}
