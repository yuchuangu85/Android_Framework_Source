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
package android.media.tv.extension.signal;

import android.annotation.IntDef;
import android.annotation.StringDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Constants for Signal Extension Package.
 *
 * @hide
 */
public final class SignalConstant {
    @IntDef({FRONTEND_STATUS_UNTUNED, FRONTEND_STATUS_TUNING,
            FRONTEND_STATUS_LOCKED, FRONTEND_STATUS_UNLOCKED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendStatus {}
    public static final int FRONTEND_STATUS_UNTUNED = 0;
    public static final int FRONTEND_STATUS_TUNING = 1;
    public static final int FRONTEND_STATUS_LOCKED = 2;
    public static final int FRONTEND_STATUS_UNLOCKED = 3;

    @StringDef({
            KEY_TUNER_SIGNAL_QUALITY_IN_PERCENT,
            KEY_TUNER_SIGNAL_STRENGTH_IN_PERCENT,
            KEY_TUNER_SIGNAL_BER,
            KEY_TUNER_SIGNAL_UEC,
            KEY_TUNER_SIGNAL_SNR,
            KEY_TUNER_SIGNAL_AGC,
            KEY_TUNER_SIGNAL_BANDWIDTH,
            KEY_TUNER_SIGNAL_MODULATION
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrontendSignalInfoKeys {}
    /**
     * Tuner signal quality percentage, 0 - 100 (Percentage), returns the same value as
     * {@link android.media.tv.tuner.frontend.FrontendStatus#getSignalQuality()}
     */
    public static final String KEY_TUNER_SIGNAL_QUALITY_IN_PERCENT = "SIGNAL_QUALITY_IN_PERCENT";
    /**
     * Tuner signal strength percentage, 0 - 100 (Percentage), returns the same value as
     * {@link android.media.tv.tuner.frontend.FrontendStatus#getSignalStrength()}
     */
    public static final String KEY_TUNER_SIGNAL_STRENGTH_IN_PERCENT = "SIGNAL_STRENGTH_IN_PERCENT";
    /**
     * Bit Error Rate, int, returns the same value as
     * {@link android.media.tv.tuner.frontend.FrontendStatus#getBer()}
     */
    public static final String KEY_TUNER_SIGNAL_BER = "BER";
    /**
     * Uncorrected Error Count, int, returns the same value as
     * {@link android.media.tv.tuner.frontend.FrontendStatus#getUec()}
     */
    public static final String KEY_TUNER_SIGNAL_UEC = "UEC";
    /**
     * Signal-to-Noise Ratio, db measures in int, returns the same value as
     * {@link android.media.tv.tuner.frontend.FrontendStatus#getSnr()}
     */
    public static final String KEY_TUNER_SIGNAL_SNR = "SNR";
    /**
     * Automatic Gain Control, hardware specific gain level in int, returns the same value as
     * {@link android.media.tv.tuner.frontend.FrontendStatus#getAgc()}
     */
    public static final String KEY_TUNER_SIGNAL_AGC = "AGC";
    /**
     * Bandwidth, please refer to
     * {@link android.media.tv.tuner.frontend.FrontendStatus.FrontendBandwidth} for available
     * values. This key should return the same value as
     * {@link android.media.tv.tuner.frontend.FrontendStatus#getBandwidth()}
     */
    public static final String KEY_TUNER_SIGNAL_BANDWIDTH = "BANDWIDTH";
    /**
     * Modulation type, please refer to
     * {@link android.media.tv.tuner.frontend.FrontendStatus.FrontendModulation} for available
     * values. This key should return the same value as
     * {@link android.media.tv.tuner.frontend.FrontendStatus#getModulation()}
     */
    public static final String KEY_TUNER_SIGNAL_MODULATION = "MODULATION";

    @StringDef({
            KEY_AUDIO_FRONT_CH_NUM,
            KEY_AUDIO_REAR_CH_NUM,
            KEY_AUDIO_DOLBY_ICON
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioSignalInfoKeys {}
    /** The number of front audio channel, int */
    public static final String KEY_AUDIO_FRONT_CH_NUM = "FRONT_CH_NUM";
    /** The number of rear audio channel, int */
    public static final String KEY_AUDIO_REAR_CH_NUM = "REAR_CH_NUM";
    /** The dolby icon to display, int value based on {@link android.media.AudioFormat} */
    public static final String KEY_AUDIO_DOLBY_ICON = "DOLBY_ICON";

}
