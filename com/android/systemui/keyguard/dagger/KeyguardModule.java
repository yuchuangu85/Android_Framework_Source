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

package com.android.systemui.keyguard.dagger;

import android.app.trust.TrustManager;
import android.content.Context;
import android.os.PowerManager;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardDisplayManager;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardViewController;
import com.android.keyguard.ViewMediatorCallback;
import com.android.keyguard.dagger.KeyguardQsUserSwitchComponent;
import com.android.keyguard.dagger.KeyguardStatusBarViewComponent;
import com.android.keyguard.dagger.KeyguardStatusViewComponent;
import com.android.keyguard.dagger.KeyguardUserSwitcherComponent;
import com.android.keyguard.mediator.ScreenOnCoordinator;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.classifier.FalsingModule;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.util.DeviceConfigProxy;

import java.util.concurrent.Executor;

import dagger.Lazy;
import dagger.Module;
import dagger.Provides;

/**
 * Dagger Module providing keyguard.
 */
@Module(subcomponents = {
        KeyguardQsUserSwitchComponent.class,
        KeyguardStatusBarViewComponent.class,
        KeyguardStatusViewComponent.class,
        KeyguardUserSwitcherComponent.class},
        includes = {FalsingModule.class})
public class KeyguardModule {
    /**
     * Provides our instance of KeyguardViewMediator which is considered optional.
     */
    @Provides
    @SysUISingleton
    public static KeyguardViewMediator newKeyguardViewMediator(
            Context context,
            FalsingCollector falsingCollector,
            LockPatternUtils lockPatternUtils,
            BroadcastDispatcher broadcastDispatcher,
            Lazy<KeyguardViewController> statusBarKeyguardViewManagerLazy,
            DismissCallbackRegistry dismissCallbackRegistry,
            KeyguardUpdateMonitor updateMonitor,
            DumpManager dumpManager,
            PowerManager powerManager,
            TrustManager trustManager,
            UserSwitcherController userSwitcherController,
            @UiBackground Executor uiBgExecutor,
            DeviceConfigProxy deviceConfig,
            NavigationModeController navigationModeController,
            KeyguardDisplayManager keyguardDisplayManager,
            DozeParameters dozeParameters,
            SysuiStatusBarStateController statusBarStateController,
            KeyguardStateController keyguardStateController,
            Lazy<KeyguardUnlockAnimationController> keyguardUnlockAnimationController,
            ScreenOffAnimationController screenOffAnimationController,
            Lazy<NotificationShadeDepthController> notificationShadeDepthController,
            ScreenOnCoordinator screenOnCoordinator,
            InteractionJankMonitor interactionJankMonitor,
            DreamOverlayStateController dreamOverlayStateController,
            Lazy<NotificationShadeWindowController> notificationShadeWindowController,
            Lazy<ActivityLaunchAnimator> activityLaunchAnimator) {
        return new KeyguardViewMediator(
                context,
                falsingCollector,
                lockPatternUtils,
                broadcastDispatcher,
                statusBarKeyguardViewManagerLazy,
                dismissCallbackRegistry,
                updateMonitor,
                dumpManager,
                uiBgExecutor,
                powerManager,
                trustManager,
                userSwitcherController,
                deviceConfig,
                navigationModeController,
                keyguardDisplayManager,
                dozeParameters,
                statusBarStateController,
                keyguardStateController,
                keyguardUnlockAnimationController,
                screenOffAnimationController,
                notificationShadeDepthController,
                screenOnCoordinator,
                interactionJankMonitor,
                dreamOverlayStateController,
                notificationShadeWindowController,
                activityLaunchAnimator);
    }

    /** */
    @Provides
    public ViewMediatorCallback providesViewMediatorCallback(KeyguardViewMediator viewMediator) {
        return viewMediator.getViewMediatorCallback();
    }
}
