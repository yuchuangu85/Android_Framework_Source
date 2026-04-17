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
package android.media;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import android.annotation.FlaggedApi;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.media.audio.Flags;
import android.net.Uri;

/**
 * Utility methods of System APIs for Mainlines to be able to handle ringtone/vibration uri check.
 * @hide
 */
@FlaggedApi(Flags.FLAG_RINGTONE_VIBRATION_UTILS_API)
@SystemApi(client = MODULE_LIBRARIES)
public final class RingtoneVibrationUtils {
    // Not instantiable.
    private RingtoneVibrationUtils() {}

    /**
     * The vibration uri key parameter used to query its existence from the ringtone uri
     * @hide
     */
    @FlaggedApi(Flags.FLAG_RINGTONE_VIBRATION_UTILS_API)
    @SystemApi(client = MODULE_LIBRARIES)
    public static final String VIBRATION_URI_PARAM = Utils.VIBRATION_URI_PARAM;

    /**
     * The vibration uri path segment indicates the synchronized vibration
     * @hide
     */
    @FlaggedApi(Flags.FLAG_RINGTONE_VIBRATION_UTILS_API)
    @SystemApi(client = MODULE_LIBRARIES)
    public static final String SYNCHRONIZED_VIBRATION = Utils.SYNCHRONIZED_VIBRATION;

    /**
     * Whether the given ringtone Uri has vibration Uri parameter
     *
     * @param ringtoneUri the ringtone Uri
     * @return {@code true} if the Uri has vibration parameter
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_RINGTONE_VIBRATION_UTILS_API)
    @SystemApi(client = MODULE_LIBRARIES)
    public static boolean hasVibrationParameter(@Nullable Uri ringtoneUri) {
        return Utils.hasVibrationParameter(ringtoneUri);
    }
}
