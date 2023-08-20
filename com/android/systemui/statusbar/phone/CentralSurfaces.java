/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static com.android.wm.shell.transition.Transitions.ENABLE_SHELL_TRANSITIONS;

import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.view.KeyEvent;
import android.view.RemoteAnimationAdapter;
import android.view.View;
import android.view.ViewGroup;
import android.window.RemoteTransition;
import android.window.SplashScreen;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.keyguard.AuthKeyguardMessageArea;
import com.android.systemui.Dumpable;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.navigationbar.NavigationBarView;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper;
import com.android.systemui.qs.QSPanelController;
import com.android.systemui.shade.NotificationShadeWindowView;
import com.android.systemui.shade.NotificationShadeWindowViewController;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.shared.system.RemoteAnimationRunnerCompat;
import com.android.systemui.statusbar.LightRevealScrim;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.util.Compile;

import java.io.PrintWriter;

/** */
public interface CentralSurfaces extends Dumpable, LifecycleOwner {
    boolean MULTIUSER_DEBUG = false;
    // Should match the values in PhoneWindowManager
    String SYSTEM_DIALOG_REASON_KEY = "reason";
    String SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps";
    String SYSTEM_DIALOG_REASON_DREAM = "dream";
    String SYSTEM_DIALOG_REASON_SCREENSHOT = "screenshot";
    String TAG = "CentralSurfaces";
    boolean DEBUG = false;
    boolean SPEW = false;
    boolean DUMPTRUCK = true; // extra dumpsys info
    boolean DEBUG_GESTURES = false;
    boolean DEBUG_MEDIA_FAKE_ARTWORK = false;
    boolean DEBUG_CAMERA_LIFT = false;
    boolean DEBUG_WINDOW_STATE = false;
    boolean DEBUG_WAKEUP_DELAY = Compile.IS_DEBUG;
    // additional instrumentation for testing purposes; intended to be left on during development
    boolean CHATTY = DEBUG;
    boolean SHOW_LOCKSCREEN_MEDIA_ARTWORK = true;
    String ACTION_FAKE_ARTWORK = "fake_artwork";
    int FADE_KEYGUARD_START_DELAY = 100;
    int FADE_KEYGUARD_DURATION = 300;
    int FADE_KEYGUARD_DURATION_PULSING = 96;
    long[] CAMERA_LAUNCH_GESTURE_VIBRATION_TIMINGS =
            new long[]{20, 20, 20, 20, 100, 20};
    int[] CAMERA_LAUNCH_GESTURE_VIBRATION_AMPLITUDES =
            new int[]{39, 82, 139, 213, 0, 127};

    /** If true, the lockscreen will show a distinct wallpaper */
    boolean ENABLE_LOCKSCREEN_WALLPAPER = true;
    // Time after we abort the launch transition.
    long LAUNCH_TRANSITION_TIMEOUT_MS = 5000;
    int MSG_DISMISS_KEYBOARD_SHORTCUTS_MENU = 1027;

    static final boolean CLOSE_PANEL_WHEN_EMPTIED = true;

    static String viewInfo(View v) {
        return "[(" + v.getLeft() + "," + v.getTop() + ")(" + v.getRight() + "," + v.getBottom()
                + ") " + v.getWidth() + "x" + v.getHeight() + "]";
    }

    static void dumpBarTransitions(
            PrintWriter pw, String var, @Nullable BarTransitions transitions) {
        pw.print("  ");
        pw.print(var);
        pw.print(".BarTransitions.mMode=");
        if (transitions != null) {
            pw.println(BarTransitions.modeToString(transitions.getMode()));
        } else {
            pw.println("Unknown");
        }
    }

    /**
     * Returns an ActivityOptions bundle created using the given parameters.
     *
     * @param displayId The ID of the display to launch the activity in. Typically this would
     *                  be the display the status bar is on.
     * @param animationAdapter The animation adapter used to start this activity, or {@code null}
     *                         for the default animation.
     */
    static Bundle getActivityOptions(int displayId,
            @Nullable RemoteAnimationAdapter animationAdapter) {
        ActivityOptions options = getDefaultActivityOptions(animationAdapter);
        options.setLaunchDisplayId(displayId);
        options.setCallerDisplayId(displayId);
        options.setPendingIntentBackgroundActivityLaunchAllowed(true);
        return options.toBundle();
    }

    /**
     * Returns an ActivityOptions bundle created using the given parameters.
     *
     * @param displayId         The ID of the display to launch the activity in. Typically this
     *                          would be the
     *                          display the status bar is on.
     * @param animationAdapter  The animation adapter used to start this activity, or {@code null}
     *                          for the default animation.
     * @param isKeyguardShowing Whether keyguard is currently showing.
     * @param eventTime         The event time in milliseconds since boot, not including sleep. See
     *                          {@link ActivityOptions#setSourceInfo}.
     */
    static Bundle getActivityOptions(int displayId,
            @Nullable RemoteAnimationAdapter animationAdapter, boolean isKeyguardShowing,
            long eventTime) {
        ActivityOptions options = getDefaultActivityOptions(animationAdapter);
        options.setSourceInfo(isKeyguardShowing ? ActivityOptions.SourceInfo.TYPE_LOCKSCREEN
                : ActivityOptions.SourceInfo.TYPE_NOTIFICATION, eventTime);
        options.setLaunchDisplayId(displayId);
        options.setCallerDisplayId(displayId);
        options.setPendingIntentBackgroundActivityLaunchAllowed(true);
        return options.toBundle();
    }

    static ActivityOptions getDefaultActivityOptions(
            @Nullable RemoteAnimationAdapter animationAdapter) {
        ActivityOptions options;
        if (animationAdapter != null) {
            if (ENABLE_SHELL_TRANSITIONS) {
                options = ActivityOptions.makeRemoteTransition(
                        new RemoteTransition(
                                RemoteAnimationRunnerCompat.wrap(animationAdapter.getRunner()),
                                animationAdapter.getCallingApplication(), "SysUILaunch"));
            } else {
                options = ActivityOptions.makeRemoteAnimation(animationAdapter);
            }
        } else {
            options = ActivityOptions.makeBasic();
        }
        options.setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR);
        return options;
    }

    /**
     * @return a PackageManager for userId or if userId is < 0 (USER_ALL etc) then
     * return PackageManager for mContext
     */
    static PackageManager getPackageManagerForUser(Context context, int userId) {
        Context contextForUser = context;
        // UserHandle defines special userId as negative values, e.g. USER_ALL
        if (userId >= 0) {
            try {
                // Create a context for the correct user so if a package isn't installed
                // for user 0 we can still load information about the package.
                contextForUser =
                        context.createPackageContextAsUser(context.getPackageName(),
                                Context.CONTEXT_RESTRICTED,
                                new UserHandle(userId));
            } catch (PackageManager.NameNotFoundException e) {
                // Shouldn't fail to find the package name for system ui.
            }
        }
        return contextForUser.getPackageManager();
    }

    void animateExpandNotificationsPanel();

    void animateExpandSettingsPanel(@Nullable String subpanel);

    void collapsePanelOnMainThread();

    void togglePanel();

    void start();

    boolean updateIsKeyguard();

    boolean updateIsKeyguard(boolean forceStateChange);

    @NonNull
    @Override
    Lifecycle getLifecycle();

    /**
     * Wakes up the device if the device was dozing.
     */
    void wakeUpIfDozing(long time, String why, @PowerManager.WakeReason int wakeReason);

    NotificationShadeWindowView getNotificationShadeWindowView();

    NotificationShadeWindowViewController getNotificationShadeWindowViewController();

    /** */
    ShadeViewController getShadeViewController();

    /** Get the Keyguard Message Area that displays auth messages. */
    AuthKeyguardMessageArea getKeyguardMessageArea();

    int getStatusBarHeight();

    void updateQsExpansionEnabled();

    boolean isShadeDisabled();

    boolean isLaunchingActivityOverLockscreen();

    boolean isWakeUpComingFromTouch();

    void onKeyguardViewManagerStatesUpdated();

    ViewGroup getNotificationScrollLayout();

    boolean isPulsing();

    boolean isOccluded();

    //TODO: These can / should probably be moved to NotificationPresenter or ShadeController
    void onLaunchAnimationCancelled(boolean isLaunchForActivity);

    void onLaunchAnimationEnd(boolean launchIsFullScreen);

    boolean shouldAnimateLaunch(boolean isActivityIntent, boolean showOverLockscreen);

    boolean shouldAnimateLaunch(boolean isActivityIntent);

    boolean isDeviceInVrMode();

    NotificationPresenter getPresenter();

    void postAnimateCollapsePanels();

    void postAnimateForceCollapsePanels();

    void postAnimateOpenPanels();

    boolean isPanelExpanded();

    void onInputFocusTransfer(boolean start, boolean cancel, float velocity);

    void animateCollapseQuickSettings();

    /** */
    boolean getCommandQueuePanelsEnabled();

    /** */
    int getStatusBarWindowState();

    BiometricUnlockController getBiometricUnlockController();

    void showWirelessChargingAnimation(int batteryLevel);

    void checkBarModes();

    void updateBubblesVisibility();

    void setInteracting(int barWindow, boolean interacting);

    @Override
    void dump(PrintWriter pwOriginal, String[] args);

    void createAndAddWindows(@Nullable RegisterStatusBarResult result);

    float getDisplayWidth();

    float getDisplayHeight();

    void readyForKeyguardDone();

    void resetUserExpandedStates();

    void setLockscreenUser(int newUserId);

    void showKeyguard();

    boolean hideKeyguard();

    void showKeyguardImpl();

    void fadeKeyguardAfterLaunchTransition(Runnable beforeFading,
            Runnable endRunnable, Runnable cancelRunnable);

    void startLaunchTransitionTimeout();

    boolean hideKeyguardImpl(boolean forceStateChange);

    void keyguardGoingAway();

    void setKeyguardFadingAway(long startTime, long delay, long fadeoutDuration);

    void finishKeyguardFadingAway();

    void userActivity();

    boolean interceptMediaKey(KeyEvent event);

    boolean dispatchKeyEventPreIme(KeyEvent event);

    boolean onMenuPressed();

    void endAffordanceLaunch();

    /** Should the keyguard be hidden immediately in response to a back press/gesture. */
    boolean shouldKeyguardHideImmediately();

    boolean onBackPressed();

    boolean onSpacePressed();

    void showBouncerWithDimissAndCancelIfKeyguard(OnDismissAction performAction,
            Runnable cancelAction);

    LightRevealScrim getLightRevealScrim();

    // TODO: Figure out way to remove these.
    NavigationBarView getNavigationBarView();

    boolean isOverviewEnabled();

    void showPinningEnterExitToast(boolean entering);

    void showPinningEscapeToast();

    void setBouncerShowing(boolean bouncerShowing);

    void setBouncerShowingOverDream(boolean bouncerShowingOverDream);

    void collapseShade();

    int getWakefulnessState();

    boolean isScreenFullyOff();

    void showScreenPinningRequest(int taskId, boolean allowCancel);

    @Nullable
    Intent getEmergencyActionIntent();

    boolean isCameraAllowedByAdmin();

    boolean isGoingToSleep();

    void notifyBiometricAuthModeChanged();

    void setTransitionToFullShadeProgress(float transitionToFullShadeProgress);

    /**
     * Sets the amount of progress to the bouncer being fully hidden/visible. 1 means the bouncer
     * is fully hidden, while 0 means the bouncer is visible.
     */
    void setPrimaryBouncerHiddenFraction(float expansion);

    @VisibleForTesting
    void updateScrimController();

    boolean isKeyguardShowing();

    boolean shouldIgnoreTouch();

    boolean isDeviceInteractive();

    void setNotificationSnoozed(StatusBarNotification sbn,
            NotificationSwipeActionHelper.SnoozeOption snoozeOption);

    void awakenDreams();

    void clearNotificationEffects();

    boolean isBouncerShowing();

    boolean isBouncerShowingScrimmed();

    boolean isBouncerShowingOverDream();

    boolean isKeyguardSecure();

    void updateNotificationPanelTouchState();

    int getDisplayId();

    int getRotation();

    @VisibleForTesting
    void setBarStateForTest(int state);

    void wakeUpForFullScreenIntent();

    void showTransientUnchecked();

    void clearTransient();

    void acquireGestureWakeLock(long time);

    boolean setAppearance(int appearance);

    int getBarMode();

    void resendMessage(int msg);

    void resendMessage(Object msg);

    int getDisabled1();

    void setDisabled1(int disabled);

    int getDisabled2();

    void setDisabled2(int disabled);

    void setLastCameraLaunchSource(int source);

    void setLaunchCameraOnFinishedGoingToSleep(boolean launch);

    void setLaunchCameraOnFinishedWaking(boolean launch);

    void setLaunchEmergencyActionOnFinishedGoingToSleep(boolean launch);

    void setLaunchEmergencyActionOnFinishedWaking(boolean launch);

    QSPanelController getQSPanelController();

    boolean areNotificationAlertsDisabled();

    float getDisplayDensity();

    void extendDozePulse();

    boolean shouldDelayWakeUpAnimation();

    public static class KeyboardShortcutsMessage {
        final int mDeviceId;

        KeyboardShortcutsMessage(int deviceId) {
            mDeviceId = deviceId;
        }
    }

    /**
     * Sets launching activity over LS state in central surfaces.
     */
    void setIsLaunchingActivityOverLockscreen(boolean isLaunchingActivityOverLockscreen);

    /**
     * Gets an animation controller from a notification row.
     */
    ActivityLaunchAnimator.Controller getAnimatorControllerFromNotification(
            ExpandableNotificationRow associatedView);
}
