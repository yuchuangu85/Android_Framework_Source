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

package com.android.systemui.log.dagger;

import android.content.ContentResolver;
import android.os.Build;
import android.os.Looper;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.log.LogBufferFactory;
import com.android.systemui.log.LogcatEchoTracker;
import com.android.systemui.log.LogcatEchoTrackerDebug;
import com.android.systemui.log.LogcatEchoTrackerProd;
import com.android.systemui.log.table.TableLogBuffer;
import com.android.systemui.log.table.TableLogBufferFactory;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.util.Compile;
import com.android.systemui.util.wakelock.WakeLockLog;

import dagger.Module;
import dagger.Provides;

/**
 * Dagger module for providing instances of {@link LogBuffer}.
 */
@Module
public class LogModule {
    /** Provides a logging buffer for doze-related logs. */
    @Provides
    @SysUISingleton
    @DozeLog
    public static LogBuffer provideDozeLogBuffer(LogBufferFactory factory) {
        return factory.create("DozeLog", 150);
    }

    /** Provides a logging buffer for all logs related to the data layer of notifications. */
    @Provides
    @SysUISingleton
    @NotificationLog
    public static LogBuffer provideNotificationsLogBuffer(
            LogBufferFactory factory,
            NotifPipelineFlags notifPipelineFlags) {
        int maxSize = 1000;
        if (Compile.IS_DEBUG && notifPipelineFlags.isDevLoggingEnabled()) {
            maxSize *= 10;
        }
        return factory.create("NotifLog", maxSize, Compile.IS_DEBUG /* systrace */);
    }

    /** Provides a logging buffer for all logs related to notifications on the lockscreen. */
    @Provides
    @SysUISingleton
    @NotificationLockscreenLog
    public static LogBuffer provideNotificationLockScreenLogBuffer(
            LogBufferFactory factory) {
        return factory.create("NotifLockscreenLog", 50, false /* systrace */);
    }

    /** Provides a logging buffer for logs related to heads up presentation of notifications. */
    @Provides
    @SysUISingleton
    @NotificationHeadsUpLog
    public static LogBuffer provideNotificationHeadsUpLogBuffer(LogBufferFactory factory) {
        return factory.create("NotifHeadsUpLog", 1000);
    }

    /** Provides a logging buffer for notification interruption calculations. */
    @Provides
    @SysUISingleton
    @NotificationInterruptLog
    public static LogBuffer provideNotificationInterruptLogBuffer(LogBufferFactory factory) {
        return factory.create("NotifInterruptLog", 100);
    }

    /** Provides a logging buffer for notification rendering events. */
    @Provides
    @SysUISingleton
    @NotificationRenderLog
    public static LogBuffer provideNotificationRenderLogBuffer(LogBufferFactory factory) {
        return factory.create("NotifRenderLog", 100);
    }

    /** Provides a logging buffer for all logs for lockscreen to shade transition events. */
    @Provides
    @SysUISingleton
    @LSShadeTransitionLog
    public static LogBuffer provideLSShadeTransitionControllerBuffer(LogBufferFactory factory) {
        return factory.create("LSShadeTransitionLog", 50);
    }

    /** Provides a logging buffer for shade window messages. */
    @Provides
    @SysUISingleton
    @ShadeWindowLog
    public static LogBuffer provideShadeWindowLogBuffer(LogBufferFactory factory) {
        return factory.create("ShadeWindowLog", 600, false);
    }

    /** Provides a logging buffer for Shade messages. */
    @Provides
    @SysUISingleton
    @ShadeLog
    public static LogBuffer provideShadeLogBuffer(LogBufferFactory factory) {
        return factory.create("ShadeLog", 500, false);
    }

    /** Provides a logging buffer for all logs related to managing notification sections. */
    @Provides
    @SysUISingleton
    @NotificationSectionLog
    public static LogBuffer provideNotificationSectionLogBuffer(LogBufferFactory factory) {
        return factory.create("NotifSectionLog", 1000 /* maxSize */, false /* systrace */);
    }

    /** Provides a logging buffer for all logs related to remote input controller. */
    @Provides
    @SysUISingleton
    @NotificationRemoteInputLog
    public static LogBuffer provideNotificationRemoteInputLogBuffer(LogBufferFactory factory) {
        return factory.create("NotifRemoteInputLog", 50 /* maxSize */, false /* systrace */);
    }

    /** Provides a logging buffer for all logs related to unseen notifications. */
    @Provides
    @SysUISingleton
    @UnseenNotificationLog
    public static LogBuffer provideUnseenNotificationLogBuffer(LogBufferFactory factory) {
        return factory.create("UnseenNotifLog", 20 /* maxSize */, false /* systrace */);
    }

    /** Provides a logging buffer for all logs related to the data layer of notifications. */
    @Provides
    @SysUISingleton
    @NotifInteractionLog
    public static LogBuffer provideNotifInteractionLogBuffer(LogBufferFactory factory) {
        return factory.create("NotifInteractionLog", 50);
    }

    /** Provides a logging buffer for all logs related to Quick Settings. */
    @Provides
    @SysUISingleton
    @QSLog
    public static LogBuffer provideQuickSettingsLogBuffer(LogBufferFactory factory) {
        return factory.create("QSLog", 700 /* maxSize */, false /* systrace */);
    }

    /** Provides a logging buffer for logs related to Quick Settings configuration. */
    @Provides
    @SysUISingleton
    @QSConfigLog
    public static LogBuffer provideQSConfigLogBuffer(LogBufferFactory factory) {
        return factory.create("QSConfigLog", 100 /* maxSize */, true /* systrace */);
    }

    /** Provides a logging buffer for {@link com.android.systemui.broadcast.BroadcastDispatcher} */
    @Provides
    @SysUISingleton
    @BroadcastDispatcherLog
    public static LogBuffer provideBroadcastDispatcherLogBuffer(LogBufferFactory factory) {
        return factory.create("BroadcastDispatcherLog", 500 /* maxSize */,
                false /* systrace */);
    }

    /** Provides a logging buffer for {@link com.android.systemui.broadcast.BroadcastSender} */
    @Provides
    @SysUISingleton
    @WakeLockLog
    public static LogBuffer provideWakeLockLog(LogBufferFactory factory) {
        return factory.create("WakeLockLog", 500 /* maxSize */, false /* systrace */);
    }

    /** Provides a logging buffer for all logs related to Toasts shown by SystemUI. */
    @Provides
    @SysUISingleton
    @ToastLog
    public static LogBuffer provideToastLogBuffer(LogBufferFactory factory) {
        return factory.create("ToastLog", 50);
    }

    /** Provides a logging buffer for all logs related to privacy indicators in SystemUI. */
    @Provides
    @SysUISingleton
    @PrivacyLog
    public static LogBuffer providePrivacyLogBuffer(LogBufferFactory factory) {
        return factory.create("PrivacyLog", 100);
    }

    /**
     * Provides a logging buffer for
     * {@link com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment}.
     */
    @Provides
    @SysUISingleton
    @CollapsedSbFragmentLog
    public static LogBuffer provideCollapsedSbFragmentLogBuffer(LogBufferFactory factory) {
        return factory.create("CollapsedSbFragmentLog", 40);
    }

    /**
     * Provides a logging buffer for logs related to {@link com.android.systemui.qs.QSFragment}'s
     * disable flag adjustments.
     */
    @Provides
    @SysUISingleton
    @QSFragmentDisableLog
    public static LogBuffer provideQSFragmentDisableLogBuffer(LogBufferFactory factory) {
        return factory.create("QSFragmentDisableFlagsLog", 10 /* maxSize */,
                false /* systrace */);
    }

    /** Provides a logging buffer for logs related to swipe up gestures. */
    @Provides
    @SysUISingleton
    @SwipeUpLog
    public static LogBuffer provideSwipeUpLogBuffer(LogBufferFactory factory) {
        return factory.create("SwipeUpLog", 30);
    }

    /**
     * Provides a logging buffer for logs related to the media mute-await connections. See
     * {@link com.android.systemui.media.muteawait.MediaMuteAwaitConnectionManager}.
     */
    @Provides
    @SysUISingleton
    @MediaMuteAwaitLog
    public static LogBuffer provideMediaMuteAwaitLogBuffer(LogBufferFactory factory) {
        return factory.create("MediaMuteAwaitLog", 20);
    }

    /**
     * Provides a logging buffer for logs related to the media mute-await connections. See
     * {@link com.android.systemui.media.nearby.NearbyMediaDevicesManager}.
     */
    @Provides
    @SysUISingleton
    @NearbyMediaDevicesLog
    public static LogBuffer provideNearbyMediaDevicesLogBuffer(LogBufferFactory factory) {
        return factory.create("NearbyMediaDevicesLog", 20);
    }

    /**
     * Provides a buffer for logs related to media view events
     */
    @Provides
    @SysUISingleton
    @MediaViewLog
    public static LogBuffer provideMediaViewLogBuffer(LogBufferFactory factory) {
        return factory.create("MediaView", 100);
    }

    /**
     * Provides a buffer for media playback state changes
     */
    @Provides
    @SysUISingleton
    @MediaTimeoutListenerLog
    public static LogBuffer providesMediaTimeoutListenerLogBuffer(LogBufferFactory factory) {
        return factory.create("MediaTimeout", 100);
    }

    /**
     * Provides a buffer for our connections and disconnections to MediaBrowserService.
     *
     * See {@link com.android.systemui.media.controls.resume.ResumeMediaBrowser}.
     */
    @Provides
    @SysUISingleton
    @MediaBrowserLog
    public static LogBuffer provideMediaBrowserBuffer(LogBufferFactory factory) {
        return factory.create("MediaBrowser", 100);
    }

    /**
     * Provides a buffer for updates to the media carousel.
     *
     * See {@link com.android.systemui.media.controls.ui.MediaCarouselController}.
     */
    @Provides
    @SysUISingleton
    @MediaCarouselControllerLog
    public static LogBuffer provideMediaCarouselControllerBuffer(LogBufferFactory factory) {
        return factory.create("MediaCarouselCtlrLog", 20);
    }

    /** Allows logging buffers to be tweaked via adb on debug builds but not on prod builds. */
    @Provides
    @SysUISingleton
    public static LogcatEchoTracker provideLogcatEchoTracker(
            ContentResolver contentResolver,
            @Main Looper looper) {
        if (Build.isDebuggable()) {
            return LogcatEchoTrackerDebug.create(contentResolver, looper);
        } else {
            return new LogcatEchoTrackerProd();
        }
    }

    /**
     * Provides a {@link LogBuffer} for use by
     * {@link com.android.systemui.biometrics.FaceHelpMessageDeferral}.
     */
    @Provides
    @SysUISingleton
    @BiometricLog
    public static LogBuffer provideBiometricLogBuffer(LogBufferFactory factory) {
        return factory.create("BiometricLog", 200);
    }

    /**
     * Provides a {@link LogBuffer} for use by the status bar network controller.
     */
    @Provides
    @SysUISingleton
    @StatusBarNetworkControllerLog
    public static LogBuffer provideStatusBarNetworkControllerBuffer(LogBufferFactory factory) {
        return factory.create("StatusBarNetworkControllerLog", 20);
    }

    /**
     * Provides a {@link LogBuffer} for general keyguard clock logs.
     */
    @Provides
    @SysUISingleton
    @KeyguardClockLog
    public static LogBuffer provideKeyguardClockLog(LogBufferFactory factory) {
        return factory.create("KeyguardClockLog", 100);
    }

    /**
     * Provides a {@link LogBuffer} for keyguard small clock logs.
     */
    @Provides
    @SysUISingleton
    @KeyguardSmallClockLog
    public static LogBuffer provideKeyguardSmallClockLog(LogBufferFactory factory) {
        return factory.create("KeyguardSmallClockLog", 100);
    }

    /**
     * Provides a {@link LogBuffer} for keyguard large clock logs.
     */
    @Provides
    @SysUISingleton
    @KeyguardLargeClockLog
    public static LogBuffer provideKeyguardLargeClockLog(LogBufferFactory factory) {
        return factory.create("KeyguardLargeClockLog", 100);
    }

    /**
     * Provides a {@link LogBuffer} for use by {@link com.android.keyguard.KeyguardUpdateMonitor}.
     */
    @Provides
    @SysUISingleton
    @KeyguardUpdateMonitorLog
    public static LogBuffer provideKeyguardUpdateMonitorLogBuffer(LogBufferFactory factory) {
        return factory.create("KeyguardUpdateMonitorLog", 400);
    }

    /**
     * Provides a {@link LogBuffer} for use by {@link com.android.keyguard.KeyguardUpdateMonitor}.
     */
    @Provides
    @SysUISingleton
    @CarrierTextManagerLog
    public static LogBuffer provideCarrierTextManagerLog(LogBufferFactory factory) {
        return factory.create("CarrierTextManagerLog", 100);
    }

    /**
     * Provides a {@link LogBuffer} for use by {@link com.android.systemui.ScreenDecorations}.
     */
    @Provides
    @SysUISingleton
    @ScreenDecorationsLog
    public static LogBuffer provideScreenDecorationsLog(LogBufferFactory factory) {
        return factory.create("ScreenDecorationsLog", 200);
    }

    /**
     * Provides a {@link LogBuffer} for use by
     *  {@link com.android.systemui.keyguard.data.repository.DeviceEntryFaceAuthRepositoryImpl}.
     */
    @Provides
    @SysUISingleton
    @FaceAuthLog
    public static LogBuffer provideFaceAuthLog(LogBufferFactory factory) {
        return factory.create("DeviceEntryFaceAuthRepositoryLog", 300);
    }

    /**
     * Provides a {@link LogBuffer} for use by classes in the
     *  {@link com.android.systemui.keyguard.bouncer} package.
     */
    @Provides
    @SysUISingleton
    @BouncerLog
    public static LogBuffer provideBouncerLog(LogBufferFactory factory) {
        return factory.create("BouncerLog", 100);
    }

    /**
     * Provides a {@link LogBuffer} for Device State Auto-Rotation logs.
     */
    @Provides
    @SysUISingleton
    @DeviceStateAutoRotationLog
    public static LogBuffer provideDeviceStateAutoRotationLogBuffer(LogBufferFactory factory) {
        return factory.create("DeviceStateAutoRotationLog", 100);
    }

    /**
     * Provides a {@link LogBuffer} for bluetooth-related logs.
     */
    @Provides
    @SysUISingleton
    @BluetoothLog
    public static LogBuffer providerBluetoothLogBuffer(LogBufferFactory factory) {
        return factory.create("BluetoothLog", 50);
    }

    /** Provides a logging buffer for the primary bouncer. */
    @Provides
    @SysUISingleton
    @BouncerTableLog
    public static TableLogBuffer provideBouncerLogBuffer(TableLogBufferFactory factory) {
        return factory.create("BouncerTableLog", 250);
    }

    /** Provides a table logging buffer for the Monitor. */
    @Provides
    @SysUISingleton
    @MonitorLog
    public static TableLogBuffer provideMonitorTableLogBuffer(TableLogBufferFactory factory) {
        return factory.create("MonitorLog", 250);
    }

    /**
     * Provides a {@link LogBuffer} for Udfps logs.
     */
    @Provides
    @SysUISingleton
    @UdfpsLog
    public static LogBuffer provideUdfpsLogBuffer(LogBufferFactory factory) {
        return factory.create("UdfpsLog", 1000);
    }

    /**
     * Provides a {@link LogBuffer} for general keyguard-related logs.
     */
    @Provides
    @SysUISingleton
    @KeyguardLog
    public static LogBuffer provideKeyguardLogBuffer(LogBufferFactory factory) {
        return factory.create("KeyguardLog", 250);
    }

    /**
     * Provides a {@link LogBuffer} for dream-related logs.
     */
    @Provides
    @SysUISingleton
    @DreamLog
    public static LogBuffer provideDreamLogBuffer(LogBufferFactory factory) {
        return factory.create("DreamLog", 250);
    }
}
