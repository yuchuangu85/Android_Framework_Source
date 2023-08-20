/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.util;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.provider.Settings;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.CoreStartable;
import com.android.systemui.R;
import com.android.wm.shell.pip.tv.TvPipNotificationController;

import java.util.Arrays;

import javax.inject.Inject;

// NOT Singleton. Started per-user.
/** */
public class NotificationChannels implements CoreStartable {
    public static String ALERTS      = "ALR";
    public static String SCREENSHOTS_HEADSUP = "SCN_HEADSUP";
    // Deprecated. Please use or create a more specific channel that users will better understand
    @Deprecated
    static String GENERAL     = "GEN";
    public static String STORAGE     = "DSK";
    public static String BATTERY     = "BAT";
    public static String TVPIP       = TvPipNotificationController.NOTIFICATION_CHANNEL; // "TVPIP"
    public static String HINTS       = "HNT";
    public static String INSTANT     = "INS";
    public static String SETUP       = "STP";

    private final Context mContext;

    @Inject
    public NotificationChannels(Context context) {
        mContext = context;
    }

    public static void createAll(Context context) {
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        final NotificationChannel batteryChannel = new NotificationChannel(BATTERY,
                context.getString(R.string.notification_channel_battery),
                NotificationManager.IMPORTANCE_MAX);
        final String soundPath = Settings.Global.getString(context.getContentResolver(),
                Settings.Global.LOW_BATTERY_SOUND);
        batteryChannel.setSound(Uri.parse("file://" + soundPath), new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .build());
        batteryChannel.setBlockable(true);

        final NotificationChannel alerts = new NotificationChannel(
                ALERTS,
                context.getString(R.string.notification_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH);

        final NotificationChannel instant = new NotificationChannel(
                INSTANT,
                context.getString(R.string.notification_channel_instant),
                NotificationManager.IMPORTANCE_MIN);

        final NotificationChannel setup = new NotificationChannel(
                SETUP,
                context.getString(R.string.notification_channel_setup),
                NotificationManager.IMPORTANCE_DEFAULT);
        setup.setSound(null, null);

        final NotificationChannel storage = new NotificationChannel(
                STORAGE,
                context.getString(R.string.notification_channel_storage),
                isTv(context)
                        ? NotificationManager.IMPORTANCE_DEFAULT
                        : NotificationManager.IMPORTANCE_LOW);

        final NotificationChannel hint = new NotificationChannel(
                HINTS,
                context.getString(R.string.notification_channel_hints),
                NotificationManager.IMPORTANCE_DEFAULT);
        // No need to bypass DND.

        nm.createNotificationChannels(Arrays.asList(
                alerts,
                instant,
                setup,
                storage,
                createScreenshotChannel(
                        context.getString(R.string.notification_channel_screenshot)),
                batteryChannel,
                hint
        ));

        if (isTv(context)) {
            // TV specific notification channel for TV PIP controls.
            // Importance should be {@link NotificationManager#IMPORTANCE_MAX} to have the highest
            // priority, so it can be shown in all times.
            nm.createNotificationChannel(new NotificationChannel(
                    TVPIP,
                    context.getString(R.string.notification_channel_tv_pip),
                    NotificationManager.IMPORTANCE_MAX));
        }
    }

    /**
     * Set up screenshot channel, respecting any previously committed user settings on legacy
     * channel.
     * @return
     */
    @VisibleForTesting static NotificationChannel createScreenshotChannel(
            String name) {
        NotificationChannel screenshotChannel = new NotificationChannel(SCREENSHOTS_HEADSUP,
                name, NotificationManager.IMPORTANCE_HIGH); // pop on screen

        screenshotChannel.setSound(null, // silent
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
        screenshotChannel.setBlockable(true);

        return screenshotChannel;
    }

    @Override
    public void start() {
        createAll(mContext);
        cleanUp();
    }

    private void cleanUp() {
        mContext.getSystemService(NotificationManager.class).deleteNotificationChannel(GENERAL);
    }

    private static boolean isTv(Context context) {
        PackageManager packageManager = context.getPackageManager();
        return packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }
}
