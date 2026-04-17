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

package com.android.internal.util;

import android.annotation.ArrayRes;
import android.annotation.IntDef;
import android.content.Context;
import android.content.res.Resources;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.media.Utils;
import android.net.Uri;
import android.util.ArrayMap;
import android.util.Slog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.regex.PatternSyntaxException;

/**
 * An utility class for writing vibration stats event metrics with FrameworkStatsLog.
 *
 * @hide
 */
public class VibrationStatsWriter {
    private static final String TAG = "VibrationStatsWriter";

    /** Log the event when a custom vibration pattern is picked & saved from Settings. */
    public static final int VIBRATION_PATTERN_SAVED = 1;
    // CUSTOM_VIBRATION_PATTERN_REPORTED__EVENT_TYPE__VIBRATION_PATTERN_SAVED;

    /** Log the event when a custom vibration pattern is played by a ringtone or notifications. */
    public static final int VIBRATION_PATTERN_PLAYED = 2;
    // CUSTOM_VIBRATION_PATTERN_REPORTED__EVENT_TYPE__VIBRATION_PATTERN_PLAYED;

    /** @hide */
    @IntDef(prefix = {"VIBRATION_PATTERN_"}, value = {
            VIBRATION_PATTERN_SAVED,
            VIBRATION_PATTERN_PLAYED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface VibrationPatternEventType {
    }

    private final Context mContext;
    private final Map<String, Integer> mRingtoneVibrationPatternIdMap = new ArrayMap<>();
    private final Map<String, Integer> mNotificationVibrationPatternIdMap = new ArrayMap<>();

    private final boolean mInitialized;

    private static VibrationStatsWriter sVibrationStatsWriter;

    private VibrationStatsWriter(Context context) {
        Context appContext = context.getApplicationContext();
        mContext = appContext != null ? appContext : context;
        boolean initialized;
        try {
            loadVibrationPatternsIntoMap(mContext,
                    R.array.config_ringtoneVibrationPatternToMetricIdMapping,
                    mRingtoneVibrationPatternIdMap);
            loadVibrationPatternsIntoMap(mContext,
                    R.array.config_notificationVibrationPatternToMetricIdMapping,
                    mNotificationVibrationPatternIdMap);
            initialized = true;
        } catch (Resources.NotFoundException | PatternSyntaxException
                 | NumberFormatException | IllegalStateException e) {
            Slog.e(TAG, "Failed to init ringtone/notification vibration ID map", e);
            mRingtoneVibrationPatternIdMap.clear();
            mNotificationVibrationPatternIdMap.clear();
            initialized = false;
        }
        mInitialized = initialized;
    }

    /**
     * Get the singleton instance of {@link VibrationStatsWriter}
     *
     * @param context the caller of {@link Context}
     */
    public static synchronized VibrationStatsWriter getInstance(Context context) {
        if (sVibrationStatsWriter == null) {
            sVibrationStatsWriter = new VibrationStatsWriter(context);
        }
        return sVibrationStatsWriter;
    }

    @VisibleForTesting
    public static void setInstance(VibrationStatsWriter vibrationStatsWriter) {
        sVibrationStatsWriter = vibrationStatsWriter;
    }

    /**
     * Log the event metric when a custom vibration pattern event has changed.
     *
     * @param eventType    the type of event to log for this custom vibration pattern
     * @param ringtoneType the type of the ringtone for this custom vibration pattern from
     *                     {@code ringtoneUri}, see RingtoneManager.TYPE_*
     * @param ringtoneUri  the {@link Uri} of a ringtone includes a custom vibration pattern uri.
     *                     We use this parameter to retrieve and log necessary data related to
     *                     custom vibration pattern when it picked from sound picker and saved by
     *                     Settings through
     *                     {@link RingtoneManager#setActualDefaultRingtoneUri(Context, int, Uri)},
     *                     or when this custom vibration pattern played by a ringtone or
     *                     notifications through this ringtone uri.
     */
    public void logCustomVibrationPatternEventIfNeeded(@VibrationPatternEventType int eventType,
            int ringtoneType, Uri ringtoneUri) {
        if (!mInitialized) {
            return;
        }
        if (Utils.isRingtoneVibrationSettingsSupported(mContext) && ringtoneUri != null) {
            String patternName = null;
            Uri vibrationUri = null;
            if (ringtoneUri.equals(RingtoneManager.getDefaultUri(ringtoneType))) {
                // Get the actual default ringtoneUri if the uri is a symbolic one.
                ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(mContext, ringtoneType);
            }
            if (ringtoneUri != null) {
                Slog.d(TAG, "logCustomVibrationPatternEventIfNeeded, actual uri=" + ringtoneUri);
                try {
                    vibrationUri = Utils.getVibrationUri(ringtoneUri);
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to get vibration URI", e);
                }
                if (vibrationUri != null) {
                    patternName = vibrationUri.getLastPathSegment();
                }
                final int ringerMode = Objects.requireNonNull(
                        mContext.getSystemService(AudioManager.class)).getRingerMode();
                final boolean isAudioCoupleHaptics =
                        vibrationUri == null && RingtoneManager.hasHapticChannels(ringtoneUri);
                if (vibrationUri != null || isAudioCoupleHaptics) {
                    logCustomVibrationPatternEvent(eventType,
                            ringtoneType,
                            toVibrationPatternId(ringtoneType, patternName),
                            ringerMode,
                            isAudioCoupleHaptics);
                }
            }
        }
    }

    /**
     * Log the event metric when a custom vibration pattern event has changed.
     *
     * @param eventType            the type of event to log for this custom vibration pattern
     * @param ringtoneType         the type of the ringtone for this custom vibration pattern from
     *                             {@code ringtoneUri}, see
     *                             {@link #logCustomVibrationPatternEventIfNeeded}
     * @param patternId            the ID of the vibration patternId from the ringtone uri includes
     *                             a custom vibration pattern uri
     * @param ringerMode           the current ringer mode from {@link AudioManager#getRingerMode()}
     * @param isAudioCoupleHaptics whether the current ringtone includes both audio and haptics
     *                             channels
     */
    @VisibleForTesting
    public void logCustomVibrationPatternEvent(int eventType, int ringtoneType,
            int patternId, int ringerMode, boolean isAudioCoupleHaptics) {
        FrameworkStatsLog.write(FrameworkStatsLog.CUSTOM_VIBRATION_PATTERN_REPORTED,
                eventType, ringtoneType, patternId, ringerMode, isAudioCoupleHaptics);
    }

    private int toVibrationPatternId(int ringtoneType, @Nullable String patternName) {
        if (patternName == null) {
            return -1;
        }
        return switch (ringtoneType) {
            case RingtoneManager.TYPE_RINGTONE -> mRingtoneVibrationPatternIdMap.getOrDefault(
                    patternName, -1);
            case RingtoneManager.TYPE_NOTIFICATION ->
                    mNotificationVibrationPatternIdMap.getOrDefault(patternName, -1);
            default -> -1;
        };
    }

    private static void loadVibrationPatternsIntoMap(@NonNull Context context, @ArrayRes int resId,
            @NonNull Map<String, Integer> outMap) {
        String[] vibrationPatternsStringArray = context.getResources().getStringArray(resId);
        for (String patternIdPairString : vibrationPatternsStringArray) {
            String[] patternIdPair = patternIdPairString.split(",");
            if (patternIdPair == null || patternIdPair.length != 2) {
                throw new IllegalStateException("Missing pattern or ID info, patternIdPair: "
                        + Arrays.toString(patternIdPair));
            }
            outMap.put(patternIdPair[0], Integer.valueOf(patternIdPair[1]));
        }
    }
}
