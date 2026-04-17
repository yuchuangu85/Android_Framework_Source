/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import java.io.FileNotFoundException;

/**
 * Canonicalizes and uncanonicalizes notification sounds for backup and restore.
 * @hide
 */
public class NotificationSoundCanonicalizer {
    private static final String TAG = "SoundCanonicalizer";

    /**
     * Converts an on-device content:// Uri (e.g. a notification sound) to a Uri that can be used
     * for backup/restore.
     */
    public static @Nullable Uri getSoundForBackup(Context context, Uri sound) {
        if (sound == null || Uri.EMPTY.equals(sound)) {
            return null;
        }
        try {
            Uri canonicalSound = NotificationSoundCanonicalizer.getCanonicalizedSoundUri(
                    context.getContentResolver(), sound);
            if (canonicalSound == null) {
                // The content provider does not support canonical uris so we backup the default
                return Settings.System.DEFAULT_NOTIFICATION_URI;
            }
            return canonicalSound;
        } catch (Exception e) {
            Slog.e(TAG, "Cannot find file for sound " + sound + " using default");
            return Settings.System.DEFAULT_NOTIFICATION_URI;
        }
    }

    @Nullable
    private static Uri getCanonicalizedSoundUri(ContentResolver contentResolver, @NonNull Uri uri) {
        if (Settings.System.DEFAULT_NOTIFICATION_URI.equals(uri)) {
            return uri;
        }

        if (ContentResolver.SCHEME_ANDROID_RESOURCE.equals(uri.getScheme())) {
            try {
                contentResolver.getResourceId(uri);
                return uri;
            } catch (FileNotFoundException e) {
                return null;
            }
        }

        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            return uri;
        }
        return contentResolver.canonicalize(uri);
    }

    @Nullable
    private static Uri getUncanonicalizedSoundUri(
            ContentResolver contentResolver, @NonNull Uri uri, int usage) {
        if (Settings.System.DEFAULT_NOTIFICATION_URI.equals(uri)
                || ContentResolver.SCHEME_ANDROID_RESOURCE.equals(uri.getScheme())
                || ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            return uri;
        }
        int ringtoneType = 0;

        // Consistent with UI(SoundPreferenceController.handlePreferenceTreeClick).
        if (AudioAttributes.USAGE_ALARM == usage) {
            ringtoneType = RingtoneManager.TYPE_ALARM;
        } else if (AudioAttributes.USAGE_NOTIFICATION_RINGTONE == usage) {
            ringtoneType = RingtoneManager.TYPE_RINGTONE;
        } else {
            ringtoneType = RingtoneManager.TYPE_NOTIFICATION;
        }
        try {
            return RingtoneManager.getRingtoneUriForRestore(
                    contentResolver, uri.toString(), ringtoneType);
        } catch (Exception e) {
            Log.e(TAG, "Failed to uncanonicalize sound uri for " + uri + " " + e);
            return Settings.System.DEFAULT_NOTIFICATION_URI;
        }
    }

    /**
     * Restore/validate sound Uri from backup
     * @param context The Context
     * @param uri The sound Uri to restore
     * @param pkgInstalled If the parent package is installed
     * @param usage the usage of the sound (e.g. notification, ringtone, alarm)
     * @param alreadyRestored whether we've already tried to restore, but are trying again because
     *                        the requesting package is now installed
     * @return restored and validated Uri and whether the restore was successful
     */
    @NonNull
    public static Pair<Uri, Boolean> restoreSoundUri(Context context, @Nullable Uri uri,
            boolean pkgInstalled, @AudioAttributes.AttributeUsage int usage,
            boolean alreadyRestored) {
        if (uri == null || Uri.EMPTY.equals(uri)) {
            return Pair.create(null, false);
        }
        ContentResolver contentResolver = context.getContentResolver();
        // There are backups out there with uncanonical uris (because we fixed this after
        // shipping). If uncanonical uris are given to MediaProvider.uncanonicalize it won't
        // verify the uri against device storage and we'll possibly end up with a broken uri.
        // We then canonicalize the uri to uncanonicalize it back, which means we properly check
        // the uri and in the case of not having the resource we end up with the default - better
        // than broken. As a side effect we'll canonicalize already canonicalized uris, this is fine
        // according to the docs because canonicalize method has to handle canonical uris as well.
        Uri canonicalizedUri =
                NotificationSoundCanonicalizer.getCanonicalizedSoundUri(contentResolver, uri);
        if (canonicalizedUri == null) {
            // Uri failed to restore with package installed
            if (!alreadyRestored && pkgInstalled) {
                // We got a null because the uri in the backup does not exist here, so we return
                // default
                return Pair.create(Settings.System.DEFAULT_NOTIFICATION_URI, true);
            } else {
                // Flag as unrestored and try again later (on package install)
                return Pair.create(uri, false);
            }
        }
        return Pair.create(getUncanonicalizedSoundUri(contentResolver, canonicalizedUri, usage),
                true);
    }
}
