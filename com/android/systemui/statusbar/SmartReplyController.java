/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.statusbar;

import android.app.Notification;
import android.os.RemoteException;
import android.util.ArraySet;

import androidx.annotation.NonNull;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.Dumpable;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.dagger.CentralSurfacesModule;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;

import java.io.PrintWriter;
import java.util.Set;

/**
 * Handles when smart replies are added to a notification
 * and clicked upon.
 */
public class SmartReplyController implements Dumpable {
    private final IStatusBarService mBarService;
    private final NotificationVisibilityProvider mVisibilityProvider;
    private final NotificationClickNotifier mClickNotifier;
    private final Set<String> mSendingKeys = new ArraySet<>();
    private Callback mCallback;

    /**
     * Injected constructor. See {@link CentralSurfacesModule}.
     */
    public SmartReplyController(
            DumpManager dumpManager,
            NotificationVisibilityProvider visibilityProvider,
            IStatusBarService statusBarService,
            NotificationClickNotifier clickNotifier) {
        mBarService = statusBarService;
        mVisibilityProvider = visibilityProvider;
        mClickNotifier = clickNotifier;
        dumpManager.registerDumpable(this);
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    /**
     * Notifies StatusBarService a smart reply is sent.
     */
    public void smartReplySent(NotificationEntry entry, int replyIndex, CharSequence reply,
            int notificationLocation, boolean modifiedBeforeSending) {
        mCallback.onSmartReplySent(entry, reply);
        mSendingKeys.add(entry.getKey());
        try {
            mBarService.onNotificationSmartReplySent(entry.getSbn().getKey(), replyIndex, reply,
                    notificationLocation, modifiedBeforeSending);
        } catch (RemoteException e) {
            // Nothing to do, system going down
        }
    }

    /**
     * Notifies StatusBarService a smart action is clicked.
     */
    public void smartActionClicked(
            NotificationEntry entry, int actionIndex, Notification.Action action,
            boolean generatedByAssistant) {
        final NotificationVisibility nv = mVisibilityProvider.obtain(entry, true);
        mClickNotifier.onNotificationActionClick(
                entry.getKey(), actionIndex, action, nv, generatedByAssistant);
    }

    /**
     * Have we posted an intent to an app about sending a smart reply from the
     * notification with this key.
     */
    public boolean isSendingSmartReply(String key) {
        return mSendingKeys.contains(key);
    }

    /**
     * Smart Replies and Actions have been added to the UI.
     */
    public void smartSuggestionsAdded(final NotificationEntry entry, int replyCount,
            int actionCount, boolean generatedByAssistant, boolean editBeforeSending) {
        try {
            mBarService.onNotificationSmartSuggestionsAdded(entry.getSbn().getKey(), replyCount,
                    actionCount, generatedByAssistant, editBeforeSending);
        } catch (RemoteException e) {
            // Nothing to do, system going down
        }
    }

    public void stopSending(final NotificationEntry entry) {
        if (entry != null) {
            mSendingKeys.remove(entry.getSbn().getKey());
        }
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("mSendingKeys: " + mSendingKeys.size());
        for (String key : mSendingKeys) {
            pw.println(" * " + key);
        }
    }

    /**
     * Callback for any class that needs to do something in response to a smart reply being sent.
     */
    public interface Callback {
        /**
         * A smart reply has just been sent for a notification
         *
         * @param entry the entry for the notification
         * @param reply the reply that was sent
         */
        void onSmartReplySent(NotificationEntry entry, CharSequence reply);
    }
}
