/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.os.vibrator;

import static android.os.VibrationAttributes.USAGE_ACCESSIBILITY;
import static android.os.VibrationAttributes.USAGE_ALARM;
import static android.os.VibrationAttributes.USAGE_COMMUNICATION_REQUEST;
import static android.os.VibrationAttributes.USAGE_GESTURE_INPUT;
import static android.os.VibrationAttributes.USAGE_HARDWARE_FEEDBACK;
import static android.os.VibrationAttributes.USAGE_IME_FEEDBACK;
import static android.os.VibrationAttributes.USAGE_MEDIA;
import static android.os.VibrationAttributes.USAGE_NOTIFICATION;
import static android.os.VibrationAttributes.USAGE_PHYSICAL_EMULATION;
import static android.os.VibrationAttributes.USAGE_RINGTONE;
import static android.os.VibrationAttributes.USAGE_TOUCH;
import static android.os.VibrationAttributes.USAGE_UNKNOWN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.os.VibrationAttributes;
import android.os.Vibrator;
import android.os.Vibrator.VibrationIntensity;
import android.util.IndentingPrintWriter;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * List of device-specific internal vibration configuration loaded from platform config.xml.
 *
 * <p>This should not be public, but some individual values are exposed by {@link Vibrator} by
 * hidden methods, made available to Settings, SysUI and other platform client code. They can also
 * be individually exposed with the necessary permissions by the {@link Vibrator} service.
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class VibrationConfig {

    /**
     * Hardcoded default scale level gain to be applied between each scale level to define their
     * scale factor value.
     *
     * <p>Default gain defined as 3 dBs.
     */
    private static final float DEFAULT_SCALE_LEVEL_GAIN = 1.4f;

    /**
     * Hardcoded default amplitude to be used when device config is invalid, i.e. not in [1,255].
     */
    private static final int DEFAULT_AMPLITUDE = 255;

    private final float mHapticChannelMaxVibrationAmplitude;
    private final int mDefaultVibrationAmplitude;
    private final int mRampDownDurationMs;
    private final int mRequestVibrationParamsTimeoutMs;
    private final int[] mRequestVibrationParamsForUsages;
    private final float mDefaultVibrationScaleLevelGain;
    private final float[] mVibrationScaleFactors;
    private final float[] mExternalVibrationScaleFactors;
    private final boolean mIgnoreVibrationsOnWirelessCharger;
    private final boolean mKeyboardVibrationSettingsSupported;
    private final int mVibrationPipelineMaxDurationMs;

    @VibrationIntensity
    private final int mDefaultAlarmVibrationIntensity;
    @VibrationIntensity
    private final int mDefaultHapticFeedbackIntensity;
    @VibrationIntensity
    private final int mDefaultMediaVibrationIntensity;
    @VibrationIntensity
    private final int mDefaultNotificationVibrationIntensity;
    @VibrationIntensity
    private final int mDefaultRingVibrationIntensity;
    @VibrationIntensity
    private final int mDefaultKeyboardVibrationIntensity;

    /** @hide */
    public VibrationConfig(@Nullable Resources resources) {
        this(new Builder(resources));
    }

    /** @hide */
    public VibrationConfig(@NonNull Builder builder) {
        mDefaultVibrationAmplitude = builder.mDefaultVibrationAmplitude;
        mHapticChannelMaxVibrationAmplitude = builder.mHapticChannelMaxVibrationAmplitude;
        mRampDownDurationMs = builder.mRampDownDurationMs;
        mRequestVibrationParamsTimeoutMs = builder.mRequestVibrationParamsTimeoutMs;
        mRequestVibrationParamsForUsages = builder.mRequestVibrationParamsForUsages;
        mIgnoreVibrationsOnWirelessCharger = builder.mIgnoreVibrationsOnWirelessCharger;
        mKeyboardVibrationSettingsSupported = builder.mKeyboardVibrationSettingsSupported;
        mVibrationPipelineMaxDurationMs = builder.mVibrationPipelineMaxDurationMs;
        mDefaultVibrationScaleLevelGain = builder.mDefaultVibrationScaleLevelGain;
        mVibrationScaleFactors = builder.mVibrationScaleFactors;
        mExternalVibrationScaleFactors = builder.mExternalVibrationScaleFactors;
        mDefaultAlarmVibrationIntensity = builder.mDefaultAlarmVibrationIntensity;
        mDefaultHapticFeedbackIntensity = builder.mDefaultHapticFeedbackIntensity;
        mDefaultMediaVibrationIntensity = builder.mDefaultMediaVibrationIntensity;
        mDefaultNotificationVibrationIntensity = builder.mDefaultNotificationVibrationIntensity;
        mDefaultRingVibrationIntensity = builder.mDefaultRingVibrationIntensity;
        mDefaultKeyboardVibrationIntensity = builder.mDefaultKeyboardVibrationIntensity;
    }

    @VibrationIntensity
    private static int loadDefaultIntensity(@Nullable Resources res, int resId) {
        int defaultIntensity = Vibrator.VIBRATION_INTENSITY_MEDIUM;
        int value = loadInteger(res, resId, defaultIntensity);
        if (value < Vibrator.VIBRATION_INTENSITY_OFF || value > Vibrator.VIBRATION_INTENSITY_HIGH) {
            return defaultIntensity;
        }
        return value;
    }

    @Nullable
    private static float[] loadIntensityScaleFactors(@Nullable Resources res, int resId) {
        float[] scales = loadFloatArray(res, resId);
        if (scales == null || scales.length != Vibrator.VIBRATION_INTENSITY_HIGH) {
            // Missing or bad config, ignore it.
            return null;
        }
        for (float scale : scales) {
            if (scale <= 0) {
                // Bad scale value, ignore config.
                return null;
            }
        }
        return scales;
    }

    private static float loadFloat(@Nullable Resources res, int resId) {
        return res != null ? res.getFloat(resId) : 0f;
    }

    private static int loadInteger(@Nullable Resources res, int resId) {
        return loadInteger(res, resId, 0);
    }

    private static int loadInteger(@Nullable Resources res, int resId, int defaultValue) {
        return res != null ? res.getInteger(resId) : defaultValue;
    }

    private static boolean loadBoolean(@Nullable Resources res, int resId) {
        return res != null && res.getBoolean(resId);
    }

    private static int[] loadIntArray(@Nullable Resources res, int resId) {
        return res != null ? res.getIntArray(resId) : new int[0];
    }

    private static float[] loadFloatArray(@Nullable Resources res, int resId) {
        String[] values = res != null ? res.getStringArray(resId) : null;
        if (values == null) {
            return null;
        }
        float[] parsedValues = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            try {
                parsedValues[i] = Float.parseFloat(values[i]);
            } catch (Exception e) {
                return null;
            }
        }
        return parsedValues;
    }

    /**
     * Return the maximum amplitude the vibrator can play using the audio haptic channels.
     *
     * @return a positive value representing the maximum absolute value the device can play signals
     * from audio haptic channels, or {@link Float#NaN NaN} if it's unknown.
     */
    public float getHapticChannelMaximumAmplitude() {
        if (mHapticChannelMaxVibrationAmplitude <= 0) {
            return Float.NaN;
        }
        return mHapticChannelMaxVibrationAmplitude;
    }

    /**
     * Return the device default vibration amplitude value to replace the
     * {@link android.os.VibrationEffect#DEFAULT_AMPLITUDE} constant.
     */
    public int getDefaultVibrationAmplitude() {
        if (mDefaultVibrationAmplitude < 1 || mDefaultVibrationAmplitude > 255) {
            return DEFAULT_AMPLITUDE;
        }
        return mDefaultVibrationAmplitude;
    }

    /**
     * Return the device default gain to be applied between scale levels to define the scale factor
     * for each level.
     */
    public float getDefaultVibrationScaleLevelGain() {
        if (mDefaultVibrationScaleLevelGain <= 1) {
            return DEFAULT_SCALE_LEVEL_GAIN;
        }
        return mDefaultVibrationScaleLevelGain;
    }

    /** Return true if device has vibration scale factors config. */
    public boolean hasVibrationScaleFactors() {
        return mVibrationScaleFactors != null;
    }

    /**
     * Return the scale factor configured for given intensity, or the default value if no
     * configuration is in place.
     */
    public float getVibrationScaleFactor(@VibrationIntensity int intensity, float defaultValue) {
        if (mVibrationScaleFactors == null || mVibrationScaleFactors.length < intensity) {
            return defaultValue;
        }
        return mVibrationScaleFactors[intensity - 1];
    }

    /** Return true if device has vibration scale factors config. */
    public boolean hasExternalVibrationScaleFactors() {
        return mExternalVibrationScaleFactors != null;
    }

    /**
     * Return the scale factor configured for given intensity for external vibrations.
     *
     * <p>This will return {@link #getVibrationScaleFactor} if no configuration is in place
     * specifically for external vibrations.
     */
    public float getExternalVibrationScaleFactor(@VibrationIntensity int intensity,
            float defaultValue) {
        if (mExternalVibrationScaleFactors == null
                || mExternalVibrationScaleFactors.length < intensity) {
            return getVibrationScaleFactor(intensity, defaultValue);
        }
        return mExternalVibrationScaleFactors[intensity - 1];
    }

    /**
     * The duration, in milliseconds, that should be applied to the ramp to turn off the vibrator
     * when a vibration is cancelled or finished at non-zero amplitude.
     */
    public int getRampDownDurationMs() {
        if (mRampDownDurationMs < 0) {
            return 0;
        }
        return mRampDownDurationMs;
    }

    /**
     * The duration, in milliseconds, that the vibrator control service will wait for new
     * vibration params.
     */
    public int getRequestVibrationParamsTimeoutMs() {
        return Math.max(mRequestVibrationParamsTimeoutMs, 0);
    }

    /**
     * The list of usages that should request vibration params before they are played. These
     * usages don't have strong latency requirements, e.g. ringtone and notification, and can be
     * slightly delayed.
     */
    public int[] getRequestVibrationParamsForUsages() {
        return mRequestVibrationParamsForUsages;
    }

    /**
     * The max duration, in milliseconds, allowed for pipelining vibration requests.
     *
     * <p>If the ongoing vibration duration is shorter than this threshold then it should be allowed
     * to finish before the next vibration can start. If the ongoing vibration is longer than this
     * then it should be cancelled when it's superseded for the new one.
     *
     * @return the max duration allowed for vibration effect to finish before the next request, or
     * zero to disable effect pipelining.
     */
    public int getVibrationPipelineMaxDurationMs() {
        if (mVibrationPipelineMaxDurationMs < 0) {
            return 0;
        }
        return mVibrationPipelineMaxDurationMs;
    }

    /**
     * Whether or not vibrations are ignored if the device is on a wireless charger.
     *
     * <p>This may be the case if vibration during wireless charging causes unwanted results, like
     * moving the device out of alignment with the charging pad.
     */
    public boolean ignoreVibrationsOnWirelessCharger() {
        return mIgnoreVibrationsOnWirelessCharger;
    }

    /**
     * Whether the device support keyboard vibration settings.
     * @hide
     */
    public boolean isKeyboardVibrationSettingsSupported() {
        return mKeyboardVibrationSettingsSupported;
    }

    /** Get the default vibration intensity for given usage. */
    @VibrationIntensity
    public int getDefaultVibrationIntensity(@VibrationAttributes.Usage int usage) {
        switch (usage) {
            case USAGE_ALARM:
                return mDefaultAlarmVibrationIntensity;
            case USAGE_NOTIFICATION:
            case USAGE_COMMUNICATION_REQUEST:
                return mDefaultNotificationVibrationIntensity;
            case USAGE_RINGTONE:
                return mDefaultRingVibrationIntensity;
            case USAGE_TOUCH:
            case USAGE_HARDWARE_FEEDBACK:
            case USAGE_PHYSICAL_EMULATION:
            case USAGE_ACCESSIBILITY:
            case USAGE_GESTURE_INPUT:
                return mDefaultHapticFeedbackIntensity;
            case USAGE_IME_FEEDBACK:
                return isKeyboardVibrationSettingsSupported()
                        ? mDefaultKeyboardVibrationIntensity : mDefaultHapticFeedbackIntensity;
            case USAGE_MEDIA:
            case USAGE_UNKNOWN:
                // fall through
            default:
                return mDefaultMediaVibrationIntensity;
        }
    }

    @Override
    public String toString() {
        return "VibrationConfig{"
                + "mIgnoreVibrationsOnWirelessCharger=" + mIgnoreVibrationsOnWirelessCharger
                + ", mDefaultVibrationAmplitude=" + mDefaultVibrationAmplitude
                + ", mHapticChannelMaxVibrationAmplitude=" + mHapticChannelMaxVibrationAmplitude
                + ", mRampDownDurationMs=" + mRampDownDurationMs
                + ", mRequestVibrationParamsForUsages="
                + Arrays.toString(getRequestVibrationParamsForUsagesNames())
                + ", mRequestVibrationParamsTimeoutMs=" + mRequestVibrationParamsTimeoutMs
                + ", mDefaultAlarmIntensity=" + mDefaultAlarmVibrationIntensity
                + ", mDefaultHapticFeedbackIntensity=" + mDefaultHapticFeedbackIntensity
                + ", mDefaultMediaIntensity=" + mDefaultMediaVibrationIntensity
                + ", mDefaultNotificationIntensity=" + mDefaultNotificationVibrationIntensity
                + ", mDefaultRingIntensity=" + mDefaultRingVibrationIntensity
                + ", mDefaultKeyboardIntensity=" + mDefaultKeyboardVibrationIntensity
                + ", mKeyboardVibrationSettingsSupported=" + mKeyboardVibrationSettingsSupported
                + ", mVibrationScaleFactors=" + Arrays.toString(mVibrationScaleFactors)
                + ", mExternalVibrationScaleFactors="
                + Arrays.toString(mExternalVibrationScaleFactors)
                + "}";
    }

    /**
     * Write current settings into given {@link PrintWriter}, skipping the default settings.
     *
     * @hide
     */
    public void dumpWithoutDefaultSettings(IndentingPrintWriter pw) {
        pw.println("VibrationConfig:");
        pw.increaseIndent();
        pw.println("ignoreVibrationsOnWirelessCharger = " + mIgnoreVibrationsOnWirelessCharger);
        pw.println("defaultVibrationAmplitude = " + mDefaultVibrationAmplitude);
        pw.println("hapticChannelMaxAmplitude = " + mHapticChannelMaxVibrationAmplitude);
        pw.println("vibrationScaleFactors = " + Arrays.toString(mVibrationScaleFactors));
        pw.println("externalVibrationScaleFactors = "
                + Arrays.toString(mExternalVibrationScaleFactors));
        pw.println("rampDownDurationMs = " + mRampDownDurationMs);
        pw.println("requestVibrationParamsForUsages = "
                + Arrays.toString(getRequestVibrationParamsForUsagesNames()));
        pw.println("requestVibrationParamsTimeoutMs = " + mRequestVibrationParamsTimeoutMs);
        pw.decreaseIndent();
    }

    private String[] getRequestVibrationParamsForUsagesNames() {
        int usagesCount = mRequestVibrationParamsForUsages.length;
        String[] names = new String[usagesCount];
        for (int i = 0; i < usagesCount; i++) {
            names[i] = VibrationAttributes.usageToString(mRequestVibrationParamsForUsages[i]);
        }

        return names;
    }

    /**
     * Builder for {@link VibrationConfig}.
     *
     * @hide
     */
    public static final class Builder {
        private float mHapticChannelMaxVibrationAmplitude;
        private int mDefaultVibrationAmplitude;
        private int mRampDownDurationMs;
        private int mRequestVibrationParamsTimeoutMs;
        private int[] mRequestVibrationParamsForUsages;
        private float mDefaultVibrationScaleLevelGain;
        private float[] mVibrationScaleFactors;
        private float[] mExternalVibrationScaleFactors;
        private boolean mIgnoreVibrationsOnWirelessCharger;
        private boolean mKeyboardVibrationSettingsSupported;
        private int mVibrationPipelineMaxDurationMs;

        @VibrationIntensity
        private int mDefaultAlarmVibrationIntensity;
        @VibrationIntensity
        private int mDefaultHapticFeedbackIntensity;
        @VibrationIntensity
        private int mDefaultMediaVibrationIntensity;
        @VibrationIntensity
        private int mDefaultNotificationVibrationIntensity;
        @VibrationIntensity
        private int mDefaultRingVibrationIntensity;
        @VibrationIntensity
        private int mDefaultKeyboardVibrationIntensity;

        public Builder(@Nullable Resources resources) {
            mDefaultVibrationAmplitude = loadInteger(resources,
                    com.android.internal.R.integer.config_defaultVibrationAmplitude,
                    DEFAULT_AMPLITUDE);
            mHapticChannelMaxVibrationAmplitude = loadFloat(resources,
                    com.android.internal.R.dimen.config_hapticChannelMaxVibrationAmplitude);
            mRampDownDurationMs = loadInteger(resources,
                    com.android.internal.R.integer.config_vibrationWaveformRampDownDuration);
            mRequestVibrationParamsTimeoutMs = loadInteger(resources,
                    com.android.internal.R.integer.config_requestVibrationParamsTimeout);
            mRequestVibrationParamsForUsages = loadIntArray(resources,
                    com.android.internal.R.array.config_requestVibrationParamsForUsages);
            mIgnoreVibrationsOnWirelessCharger = loadBoolean(resources,
                    com.android.internal.R.bool.config_ignoreVibrationsOnWirelessCharger);
            mKeyboardVibrationSettingsSupported = loadBoolean(resources,
                    com.android.internal.R.bool.config_keyboardVibrationSettingsSupported);
            mVibrationPipelineMaxDurationMs = loadInteger(resources,
                    com.android.internal.R.integer.config_vibrationPipelineMaxDuration);
            mVibrationScaleFactors = loadIntensityScaleFactors(resources,
                    com.android.internal.R.array.config_vibrationIntensityScaleFactors);
            mExternalVibrationScaleFactors = loadIntensityScaleFactors(resources,
                    com.android.internal.R.array.config_externalVibrationIntensityScaleFactors);
            mDefaultAlarmVibrationIntensity = loadDefaultIntensity(resources,
                    com.android.internal.R.integer.config_defaultAlarmVibrationIntensity);
            mDefaultHapticFeedbackIntensity = loadDefaultIntensity(resources,
                    com.android.internal.R.integer.config_defaultHapticFeedbackIntensity);
            mDefaultMediaVibrationIntensity = loadDefaultIntensity(resources,
                    com.android.internal.R.integer.config_defaultMediaVibrationIntensity);
            mDefaultNotificationVibrationIntensity = loadDefaultIntensity(resources,
                    com.android.internal.R.integer.config_defaultNotificationVibrationIntensity);
            mDefaultRingVibrationIntensity = loadDefaultIntensity(resources,
                    com.android.internal.R.integer.config_defaultRingVibrationIntensity);
            mDefaultKeyboardVibrationIntensity = loadDefaultIntensity(resources,
                    com.android.internal.R.integer.config_defaultKeyboardVibrationIntensity);
            mDefaultVibrationScaleLevelGain = DEFAULT_SCALE_LEVEL_GAIN;
        }

        public void setDefaultVibrationAmplitude(int amplitude) {
            mDefaultVibrationAmplitude = amplitude;
        }

        public void setHapticChannelMaxVibrationAmplitude(float amplitude) {
            mHapticChannelMaxVibrationAmplitude = amplitude;
        }

        public void setRampDownDurationMs(int durationMs) {
            mRampDownDurationMs = durationMs;
        }

        public void setRequestVibrationParamsTimeoutMs(int timeoutMs) {
            mRequestVibrationParamsTimeoutMs = timeoutMs;
        }

        public void setVibrationPipelineMaxDurationMs(int durationMs) {
            mVibrationPipelineMaxDurationMs = durationMs;
        }

        public void setIgnoreVibrationsOnWirelessCharger(boolean shouldIgnore) {
            mIgnoreVibrationsOnWirelessCharger = shouldIgnore;
        }

        public void setKeyboardVibrationSettingsSupported(boolean isSupported) {
            mKeyboardVibrationSettingsSupported = isSupported;
        }

        public void setDefaultAlarmVibrationIntensity(int intensity) {
            mDefaultAlarmVibrationIntensity = intensity;
        }

        public void setDefaultNotificationVibrationIntensity(int intensity) {
            mDefaultNotificationVibrationIntensity = intensity;
        }

        public void setDefaultRingVibrationIntensity(int intensity) {
            mDefaultRingVibrationIntensity = intensity;
        }

        public void setDefaultHapticFeedbackIntensity(int intensity) {
            mDefaultHapticFeedbackIntensity = intensity;
        }

        public void setDefaultKeyboardVibrationIntensity(int intensity) {
            mDefaultKeyboardVibrationIntensity = intensity;
        }

        public void setDefaultMediaVibrationIntensity(int intensity) {
            mDefaultMediaVibrationIntensity = intensity;
        }

        public void setVibrationScaleFactors(float[] scaleFactors) {
            mVibrationScaleFactors = scaleFactors;
        }

        public void setDefaultVibrationScaleLevelGain(float scaleLevelGain) {
            mDefaultVibrationScaleLevelGain = scaleLevelGain;
        }

        public void setExternalVibrationScaleFactors(float[] scaleFactors) {
            mExternalVibrationScaleFactors = scaleFactors;
        }

        public void setRequestVibrationParamsForUsages(int[] usages) {
            mRequestVibrationParamsForUsages = usages;
        }

        /** Create {@link VibrationConfig} with data from this builder. */
        public VibrationConfig build() {
            return new VibrationConfig(this);
        }
    }
}
