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

import android.os.Bundle;
import android.view.View;
import android.view.ViewRootImpl;

import androidx.annotation.Nullable;

import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.KeyguardBypassController;

/**
 *  Interface to control Keyguard View. It should be implemented by KeyguardViewManagers, which
 *  should, in turn, be injected into {@link KeyguardViewMediator}.
 */
public interface KeyguardViewController {
    /**
     * Shows Keyguard.
     * @param options
     */
    void show(Bundle options);

    /**
     * Hides Keyguard with the fade-out animation as configured by the parameters provided.
     *
     * @param startTime
     * @param fadeoutDuration
     */
    void hide(long startTime, long fadeoutDuration);

    /**
     * Resets the state of Keyguard View.
     * @param hideBouncerWhenShowing when true, hides the primary and alternate bouncers if showing.
     */
    void reset(boolean hideBouncerWhenShowing);

    /**
     * Called when the device started going to sleep.
     */
    default void onStartedGoingToSleep() {};

    /**
     * Called when the device has finished going to sleep.
     */
    default void onFinishedGoingToSleep() {};

    /**
     * Called when the device started waking up.
     */
    default void onStartedWakingUp() {};

    /**
     * Sets whether the Keyguard needs input.
     * @param needsInput
     */
    void setNeedsInput(boolean needsInput);

    /**
     * Called when cancel button in bouncer is pressed.
     */
    void onCancelClicked();

    /**
     * Sets whether the keyguard is occluded by another window.
     *
     * @param occluded
     * @param animate
     */
    void setOccluded(boolean occluded, boolean animate);

    /**
     * Dismisses the keyguard by going to the next screen or making it gone.
     */
    void dismissAndCollapse();

    /**
     * Notifies that Keyguard is just about to go away.
     */
    void keyguardGoingAway();

    /**
     * Sets the system state depending on whether the keyguard is going away or not.
     */
    void setKeyguardGoingAwayState(boolean isKeyguardGoingAway);

    /**
     * @return Whether window animation for unlock should be disabled.
     */
    boolean shouldDisableWindowAnimationsForUnlock();

    /**
     * @return Whether the keyguard is going to notification shade.
     */
    boolean isGoingToNotificationShade();

    /**
     * @return Whether subtle animation should be used for unlocking the device.
     */
    boolean isUnlockWithWallpaper();

    /**
     * @return Whether subtle animation should be used for unlocking the device.
     */
    boolean shouldSubtleWindowAnimationsForUnlock();

    /**
     * Starts the animation before we dismiss Keyguard, i.e. an disappearing animation on the
     * security view of the bouncer.
     *
     * @param finishRunnable the runnable to be run after the animation finished, or {@code null} if
     *                       no action should be run
     */
    void startPreHideAnimation(Runnable finishRunnable);

    /**
     * Blocks the current touch gesture from affecting the expansion amount of the notification
     * panel. This is used after a completed unlock gesture to ignore further dragging before an
     * ACTION_UP.
     */
    void blockPanelExpansionFromCurrentTouch();

    /**
     * @return the ViewRootImpl of the View where the Keyguard is mounted.
     */
    ViewRootImpl getViewRootImpl();

    /**
     * Notifies that the user has authenticated by other means than using the bouncer, for example,
     * fingerprint.
     */
    void notifyKeyguardAuthenticated(boolean strongAuth);

    /**
     * Shows the primary bouncer.
     */
    void showPrimaryBouncer(boolean scrimmed);

    /**
     * When the primary bouncer is fully visible or is showing but animation didn't finish yet.
     */
    boolean primaryBouncerIsOrWillBeShowing();

    /**
     * Returns {@code true} when the primary bouncer or alternate bouncer is currently showing
     */
    boolean isBouncerShowing();

    /**
     * Stop showing the alternate bouncer, if showing.
     */
    void hideAlternateBouncer(boolean updateScrim);

    // TODO: Deprecate registerStatusBar in KeyguardViewController interface. It is currently
    //  only used for testing purposes in StatusBarKeyguardViewManager, and it prevents us from
    //  achieving complete abstraction away from where the Keyguard View is mounted.

    /**
     * Registers the CentralSurfaces to which this Keyguard View is mounted.
     */
    void registerCentralSurfaces(CentralSurfaces centralSurfaces,
            ShadeViewController shadeViewController,
            @Nullable ShadeExpansionStateManager shadeExpansionStateManager,
            BiometricUnlockController biometricUnlockController,
            View notificationContainer,
            KeyguardBypassController bypassController);
}
