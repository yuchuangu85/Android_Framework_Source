/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.internal.accessibility.util;

import android.app.ActivityThread;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import com.android.internal.R;

/** Class to allow mocking of static framework calls */
public class FrameworkObjectProvider {

    /**
     * @param context A context for AccessibilityManager
     * @return AccessibilityManager instance
     */
    public AccessibilityManager getAccessibilityManagerInstance(Context context) {
        return AccessibilityManager.getInstance(context);
    }

    /**
     * @param context A context for the shortcut warning dialog
     * @return a dialog used in AccessibilityShortcutController
     */
    public AlertDialog.Builder getAlertDialogBuilder(Context context) {
        final boolean inNightMode =
                (context.getResources().getConfiguration().uiMode
                                & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;
        final int themeId =
                inNightMode
                        ? R.style.Theme_DeviceDefault_Dialog_Alert
                        : R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        return new AlertDialog.Builder(context, themeId);
    }

    /**
     * @param context A context for Toast
     * @param charSequence The toast message
     * @param duration The period of time for the toast existing
     * @return Toast instance
     */
    public Toast makeToastFromText(Context context, CharSequence charSequence, int duration) {
        return Toast.makeText(context, charSequence, duration);
    }

    /** get the SystemUi context. */
    public Context getSystemUiContext() {
        return ActivityThread.currentActivityThread().getSystemUiContext();
    }

    /**
     * @param ctx A context for TextToSpeech
     * @param listener TextToSpeech initialization callback
     * @return TextToSpeech instance
     */
    public TextToSpeech getTextToSpeech(Context ctx, TextToSpeech.OnInitListener listener) {
        return new TextToSpeech(ctx, listener);
    }

    /**
     * @param ctx context for ringtone
     * @param uri ringtone uri
     * @return Ringtone instance
     */
    public Ringtone getRingtone(Context ctx, Uri uri) {
        return RingtoneManager.getRingtone(ctx, uri);
    }

    /**
     * @param context context for ringtone
     * @return Ringtone instance
     */
    public Ringtone getDefaultAccessibilityNotificationRingtone(Context context) {
        // Use the default accessibility notification sound instead to avoid users confusing the new
        // notification received. Point to the default notification sound if the sound does not
        // exist.
        final Uri ringtoneUri =
                Uri.parse(
                        "file://"
                                + context.getString(
                                        R.string.config_defaultAccessibilityNotificationSound));
        Ringtone tone = getRingtone(context, ringtoneUri);
        if (tone == null) {
            tone = getRingtone(context, Settings.System.DEFAULT_NOTIFICATION_URI);
        }
        return tone;
    }
}
