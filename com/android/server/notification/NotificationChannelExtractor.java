/**
* Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.notification;

import android.app.NotificationChannel;
import android.content.Context;
import android.util.Slog;

/**
 * Stores the latest notification channel information for this notification
 */
public class NotificationChannelExtractor implements NotificationSignalExtractor {
    private static final String TAG = "ChannelExtractor";
    private static final boolean DBG = false;

    private RankingConfig mConfig;
    private Context mContext;

    public void initialize(Context ctx, NotificationUsageStats usageStats) {
        mContext = ctx;
        if (DBG) Slog.d(TAG, "Initializing  " + getClass().getSimpleName() + ".");
    }

    public RankingReconsideration process(NotificationRecord record) {
        if (record == null || record.getNotification() == null) {
            if (DBG) Slog.d(TAG, "skipping empty notification");
            return null;
        }

        if (mConfig == null) {
            if (DBG) Slog.d(TAG, "missing config");
            return null;
        }
        NotificationChannel updatedChannel = mConfig.getConversationNotificationChannel(
                record.getSbn().getPackageName(),
                record.getSbn().getUid(), record.getChannel().getId(),
                record.getSbn().getShortcutId(), true, false);
        record.updateNotificationChannel(updatedChannel);

        return null;
    }

    @Override
    public void setConfig(RankingConfig config) {
        mConfig = config;
    }

    @Override
    public void setZenHelper(ZenModeHelper helper) {

    }
}
