/*
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

package android.bluetooth;

import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents the codec status (configuration and capability) for a Bluetooth
 * A2DP source device.
 *
 * {@see BluetoothA2dp}
 *
 * {@hide}
 */
public final class BluetoothCodecStatus implements Parcelable {
    /**
     * Extra for the codec configuration intents of the individual profiles.
     *
     * This extra represents the current codec status of the A2DP
     * profile.
     */
    @UnsupportedAppUsage
    public static final String EXTRA_CODEC_STATUS =
            "android.bluetooth.extra.CODEC_STATUS";

    private final @Nullable BluetoothCodecConfig mCodecConfig;
    private final BluetoothCodecConfig[] mCodecsLocalCapabilities;
    private final BluetoothCodecConfig[] mCodecsSelectableCapabilities;

    public BluetoothCodecStatus(@Nullable BluetoothCodecConfig codecConfig,
            @Nullable BluetoothCodecConfig[] codecsLocalCapabilities,
            @Nullable BluetoothCodecConfig[] codecsSelectableCapabilities) {
        mCodecConfig = codecConfig;
        mCodecsLocalCapabilities = codecsLocalCapabilities;
        mCodecsSelectableCapabilities = codecsSelectableCapabilities;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o instanceof BluetoothCodecStatus) {
            BluetoothCodecStatus other = (BluetoothCodecStatus) o;
            return (Objects.equals(other.mCodecConfig, mCodecConfig)
                    && sameCapabilities(other.mCodecsLocalCapabilities, mCodecsLocalCapabilities)
                    && sameCapabilities(other.mCodecsSelectableCapabilities,
                    mCodecsSelectableCapabilities));
        }
        return false;
    }

    /**
     * Checks whether two arrays of capabilities contain same capabilities.
     * The order of the capabilities in each array is ignored.
     *
     * @param c1 the first array of capabilities to compare
     * @param c2 the second array of capabilities to compare
     * @return true if both arrays contain same capabilities
     * @hide
     */
    public static boolean sameCapabilities(BluetoothCodecConfig[] c1,
                                           BluetoothCodecConfig[] c2) {
        if (c1 == null) {
            return (c2 == null);
        }
        if (c2 == null) {
            return false;
        }
        if (c1.length != c2.length) {
            return false;
        }
        return Arrays.asList(c1).containsAll(Arrays.asList(c2));
    }

    /**
     * Checks whether the codec config matches the selectable capabilities.
     * Any parameters of the codec config with NONE value will be considered a wildcard matching.
     *
     * @param codecConfig the codec config to compare against
     * @return true if the codec config matches, otherwise false
     * @hide
     */
    public boolean isCodecConfigSelectable(BluetoothCodecConfig codecConfig) {
        if (codecConfig == null || !codecConfig.hasSingleSampleRate()
                || !codecConfig.hasSingleBitsPerSample() || !codecConfig.hasSingleChannelMode()) {
            return false;
        }
        for (BluetoothCodecConfig selectableConfig : mCodecsSelectableCapabilities) {
            if (codecConfig.getCodecType() != selectableConfig.getCodecType()) {
                continue;
            }
            int sampleRate = codecConfig.getSampleRate();
            if ((sampleRate & selectableConfig.getSampleRate()) == 0
                    && sampleRate != BluetoothCodecConfig.SAMPLE_RATE_NONE) {
                continue;
            }
            int bitsPerSample = codecConfig.getBitsPerSample();
            if ((bitsPerSample & selectableConfig.getBitsPerSample()) == 0
                    && bitsPerSample != BluetoothCodecConfig.BITS_PER_SAMPLE_NONE) {
                continue;
            }
            int channelMode = codecConfig.getChannelMode();
            if ((channelMode & selectableConfig.getChannelMode()) == 0
                    && channelMode != BluetoothCodecConfig.CHANNEL_MODE_NONE) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Returns a hash based on the codec config and local capabilities
     *
     * @return a hash based on the config values
     * @hide
     */
    @Override
    public int hashCode() {
        return Objects.hash(mCodecConfig, mCodecsLocalCapabilities,
                mCodecsLocalCapabilities);
    }

    @Override
    public String toString() {
        return "{mCodecConfig:" + mCodecConfig
                + ",mCodecsLocalCapabilities:" + Arrays.toString(mCodecsLocalCapabilities)
                + ",mCodecsSelectableCapabilities:" + Arrays.toString(mCodecsSelectableCapabilities)
                + "}";
    }

    /**
     * Always returns 0
     *
     * @return 0
     * @hide
     */
    @Override
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<BluetoothCodecStatus> CREATOR =
            new Parcelable.Creator<BluetoothCodecStatus>() {
                public BluetoothCodecStatus createFromParcel(Parcel in) {
                    final BluetoothCodecConfig codecConfig = in.readTypedObject(
                            BluetoothCodecConfig.CREATOR);
                    final BluetoothCodecConfig[] codecsLocalCapabilities = in.createTypedArray(
                            BluetoothCodecConfig.CREATOR);
                    final BluetoothCodecConfig[] codecsSelectableCapabilities = in.createTypedArray(
                            BluetoothCodecConfig.CREATOR);

                    return new BluetoothCodecStatus(codecConfig,
                            codecsLocalCapabilities,
                            codecsSelectableCapabilities);
                }

                public BluetoothCodecStatus[] newArray(int size) {
                    return new BluetoothCodecStatus[size];
                }
            };

    /**
     * Flattens the object to a parcel
     *
     * @param out The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     *
     * @hide
     */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeTypedObject(mCodecConfig, 0);
        out.writeTypedArray(mCodecsLocalCapabilities, 0);
        out.writeTypedArray(mCodecsSelectableCapabilities, 0);
    }

    /**
     * Gets the current codec configuration.
     *
     * @return the current codec configuration
     */
    @UnsupportedAppUsage
    public @Nullable BluetoothCodecConfig getCodecConfig() {
        return mCodecConfig;
    }

    /**
     * Gets the codecs local capabilities.
     *
     * @return an array with the codecs local capabilities
     */
    @UnsupportedAppUsage
    public @Nullable BluetoothCodecConfig[] getCodecsLocalCapabilities() {
        return mCodecsLocalCapabilities;
    }

    /**
     * Gets the codecs selectable capabilities.
     *
     * @return an array with the codecs selectable capabilities
     */
    @UnsupportedAppUsage
    public @Nullable BluetoothCodecConfig[] getCodecsSelectableCapabilities() {
        return mCodecsSelectableCapabilities;
    }
}
