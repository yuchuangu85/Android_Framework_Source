/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.android.bluetooth.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Represents the codec configuration for a Bluetooth LE Audio source device.
 *
 * <p>Contains the source codec type.
 *
 * <p>The source codec type values are the same as those supported by the device hardware.
 *
 * @see BluetoothLeAudioCodecConfig
 */
public final class BluetoothLeAudioCodecConfig implements Parcelable {
    // Add an entry for each source codec here.
    private static final String TAG = BluetoothLeAudioCodecConfig.class.getSimpleName();

    /**
     * Codec ID for LC3.
     *
     * <p>Bluetooth Assigned Numbers Section 2.11: Coding Format and Codec ID (HCI), LC3 is 0x06.
     */
    private static final long CODEC_ID_LC3 = 0x0000_0000_06L;

    /**
     * Codec ID for Opus. This is a Vendor Specific codec.
     *
     * <p>The format is: [Vendor Defined Codec ID (16)] [Company ID (16)] [Codec ID (8)]
     *
     * <ul>
     *   <li>Codec ID: 0xFF (Vendor Specific)
     *   <li>Company ID: 0x00E0 (Google)
     *   <li>Vendor Defined Codec ID: 0x0001 (Opus)
     * </ul>
     *
     * Bluetooth Assigned Numbers Section 2.11: Coding Format and Codec ID (HCI), Vendor Specific is
     * 0xFF.
     */
    private static final long CODEC_ID_OPUS = 0x0001_00e0_ffL;

    /**
     * The Codec ID value defined by Bluetooth Assigned Numbers to indicate a Vendor Specific codec.
     * This is the LSB (0xFF) of the 40-bit Codec ID.
     */
    private static final long CODEC_ID_VENDOR_SPECIFIC = 0xffL;

    private static final long CODEC_ID_INVALID = -1L;

    @Hide
    @IntDef(
            prefix = "SOURCE_CODEC_TYPE_",
            value = {
                SOURCE_CODEC_TYPE_LC3,
                SOURCE_CODEC_TYPE_OPUS,
                SOURCE_CODEC_TYPE_OPUS_HI_RES,
                SOURCE_CODEC_TYPE_VENDOR_SPECIFIC,
                SOURCE_CODEC_TYPE_INVALID
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SourceCodecType {};

    /** Source codec type for LC3. */
    public static final int SOURCE_CODEC_TYPE_LC3 = 0;

    /** Source codec type for Opus. */
    public static final int SOURCE_CODEC_TYPE_OPUS = 1;

    /** Source codec type for Opus High Resolution. */
    public static final int SOURCE_CODEC_TYPE_OPUS_HI_RES = 2;

    /**
     * Source codec type used for Vendor Specific codecs. When this type is used, {@link
     * #getCodecId()} should be used to get more information about the codec.
     */
    @FlaggedApi(Flags.FLAG_LEAUDIO_CODEC_ID_SUPPORT)
    public static final int SOURCE_CODEC_TYPE_VENDOR_SPECIFIC = 0xFF;

    public static final int SOURCE_CODEC_TYPE_INVALID = 1000 * 1000;

    @Hide
    @IntDef(
            prefix = "CODEC_PRIORITY_",
            value = {CODEC_PRIORITY_DISABLED, CODEC_PRIORITY_DEFAULT, CODEC_PRIORITY_HIGHEST})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CodecPriority {}

    /**
     * Codec priority disabled. Used to indicate that this codec is disabled and should not be used.
     */
    public static final int CODEC_PRIORITY_DISABLED = -1;

    /** Codec priority default. Default value used for codec priority. */
    public static final int CODEC_PRIORITY_DEFAULT = 0;

    /** Codec priority highest. Used to indicate the highest priority a codec can have. */
    public static final int CODEC_PRIORITY_HIGHEST = 1000 * 1000;

    @Hide
    @IntDef(
            flag = true,
            prefix = "SAMPLE_RATE_",
            value = {
                SAMPLE_RATE_NONE,
                SAMPLE_RATE_8000,
                SAMPLE_RATE_11025,
                SAMPLE_RATE_16000,
                SAMPLE_RATE_22050,
                SAMPLE_RATE_24000,
                SAMPLE_RATE_32000,
                SAMPLE_RATE_44100,
                SAMPLE_RATE_48000,
                SAMPLE_RATE_88200,
                SAMPLE_RATE_96000,
                SAMPLE_RATE_176400,
                SAMPLE_RATE_192000,
                SAMPLE_RATE_384000
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SampleRate {}

    /**
     * Codec sample rate 0 Hz. Default value used for codec sample rate. Values are the bit mask as
     * defined in the Bluetooth Assigned Numbers, Generic Audio, Supported_Sampling_Frequencies
     * table.
     */
    public static final int SAMPLE_RATE_NONE = 0;

    /** Codec sample rate 8000 Hz. */
    public static final int SAMPLE_RATE_8000 = 0x01 << 0;

    /** Codec sample rate 11025 Hz. */
    public static final int SAMPLE_RATE_11025 = 0x01 << 1;

    /** Codec sample rate 16000 Hz. */
    public static final int SAMPLE_RATE_16000 = 0x01 << 2;

    /** Codec sample rate 22050 Hz. */
    public static final int SAMPLE_RATE_22050 = 0x01 << 3;

    /** Codec sample rate 24000 Hz. */
    public static final int SAMPLE_RATE_24000 = 0x01 << 4;

    /** Codec sample rate 32000 Hz. */
    public static final int SAMPLE_RATE_32000 = 0x01 << 5;

    /** Codec sample rate 44100 Hz. */
    public static final int SAMPLE_RATE_44100 = 0x01 << 6;

    /** Codec sample rate 48000 Hz. */
    public static final int SAMPLE_RATE_48000 = 0x01 << 7;

    /** Codec sample rate 88200 Hz. */
    public static final int SAMPLE_RATE_88200 = 0x01 << 8;

    /** Codec sample rate 96000 Hz. */
    public static final int SAMPLE_RATE_96000 = 0x01 << 9;

    /** Codec sample rate 176400 Hz. */
    public static final int SAMPLE_RATE_176400 = 0x01 << 10;

    /** Codec sample rate 192000 Hz. */
    public static final int SAMPLE_RATE_192000 = 0x01 << 11;

    /** Codec sample rate 384000 Hz. */
    public static final int SAMPLE_RATE_384000 = 0x01 << 12;

    @Hide
    @IntDef(
            flag = true,
            prefix = "BITS_PER_SAMPLE_",
            value = {
                BITS_PER_SAMPLE_NONE,
                BITS_PER_SAMPLE_16,
                BITS_PER_SAMPLE_24,
                BITS_PER_SAMPLE_32
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BitsPerSample {}

    /** Codec bits per sample 0. Default value of the codec bits per sample. */
    public static final int BITS_PER_SAMPLE_NONE = 0;

    /** Codec bits per sample 16. */
    public static final int BITS_PER_SAMPLE_16 = 0x01 << 0;

    /** Codec bits per sample 24. */
    public static final int BITS_PER_SAMPLE_24 = 0x01 << 1;

    /** Codec bits per sample 32. */
    public static final int BITS_PER_SAMPLE_32 = 0x01 << 3;

    /**
     * Values are the bit mask as defined in the Bluetooth Assigned Numbers, Generic Audio,
     * Supported_Audio_Channel_Counts table Note: We use only part of it.
     */
    @Hide
    @IntDef(
            flag = true,
            prefix = "CHANNEL_COUNT_",
            value = {CHANNEL_COUNT_NONE, CHANNEL_COUNT_1, CHANNEL_COUNT_2})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ChannelCount {}

    /** Codec channel mode NONE. Default value of the codec channel mode. */
    public static final int CHANNEL_COUNT_NONE = 0;

    /** Codec channel mode MONO. */
    public static final int CHANNEL_COUNT_1 = 0x01 << 0;

    /** Codec channel mode STEREO. */
    public static final int CHANNEL_COUNT_2 = 0x01 << 1;

    /**
     * These values do not follow strictly the bit mask defined in the Bluetooth Assigned Numbers,
     * Generic Audio, Supported_Frame_Durations table, and may deviate or extend beyond what was
     * defined there.
     */
    @Hide
    @IntDef(
            flag = true,
            prefix = "FRAME_DURATION_",
            value = {
                FRAME_DURATION_NONE,
                FRAME_DURATION_7500,
                FRAME_DURATION_10000,
                FRAME_DURATION_20000
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrameDuration {}

    /** Frame duration 0. Default value of the frame duration. */
    public static final int FRAME_DURATION_NONE = 0;

    /** Frame duration 7500 us. */
    public static final int FRAME_DURATION_7500 = 0x01 << 0;

    /** Frame duration 10000 us. */
    public static final int FRAME_DURATION_10000 = 0x01 << 1;

    /** Frame duration 20000 us. */
    @Hide public static final int FRAME_DURATION_20000 = 0x01 << 2;

    private final long mCodecId;
    private final @SourceCodecType int mCodecType;
    private final @CodecPriority int mCodecPriority;
    private final @SampleRate int mSampleRate;
    private final @BitsPerSample int mBitsPerSample;
    private final @ChannelCount int mChannelCount;
    private final @FrameDuration int mFrameDuration;
    private final int mOctetsPerFrame;
    private final int mMinOctetsPerFrame;
    private final int mMaxOctetsPerFrame;

    /**
     * Creates a new BluetoothLeAudioCodecConfig.
     *
     * @param codecType the source codec type
     * @param codecPriority the priority of this codec
     * @param sampleRate the codec sample rate
     * @param bitsPerSample the bits per sample of this codec
     * @param channelCount the channel count of this codec
     * @param frameDuration the frame duration of this codec
     * @param octetsPerFrame the octets per frame of this codec
     * @param minOctetsPerFrame the minimum octets per frame of this codec
     * @param maxOctetsPerFrame the maximum octets per frame of this codec
     */
    private BluetoothLeAudioCodecConfig(
            @SourceCodecType int codecType,
            @CodecPriority int codecPriority,
            @SampleRate int sampleRate,
            @BitsPerSample int bitsPerSample,
            @ChannelCount int channelCount,
            @FrameDuration int frameDuration,
            int octetsPerFrame,
            int minOctetsPerFrame,
            int maxOctetsPerFrame) {
        mCodecType = codecType;

        if (Flags.leaudioCodecIdSupport()) {
            mCodecId =
                    switch (codecType) {
                        case SOURCE_CODEC_TYPE_LC3 -> CODEC_ID_LC3;
                        case SOURCE_CODEC_TYPE_OPUS -> CODEC_ID_OPUS;
                        case SOURCE_CODEC_TYPE_OPUS_HI_RES -> CODEC_ID_OPUS;
                        case SOURCE_CODEC_TYPE_VENDOR_SPECIFIC, SOURCE_CODEC_TYPE_INVALID ->
                                CODEC_ID_INVALID;
                        default -> CODEC_ID_INVALID;
                    };
        } else {
            mCodecId = CODEC_ID_INVALID;
        }

        mCodecPriority = codecPriority;
        mSampleRate = sampleRate;
        mBitsPerSample = bitsPerSample;
        mChannelCount = channelCount;
        mFrameDuration = frameDuration;
        mOctetsPerFrame = octetsPerFrame;
        mMinOctetsPerFrame = minOctetsPerFrame;
        mMaxOctetsPerFrame = maxOctetsPerFrame;
    }

    @FlaggedApi(Flags.FLAG_LEAUDIO_CODEC_ID_SUPPORT)
    private BluetoothLeAudioCodecConfig(
            @SourceCodecType int codecType,
            long codecId,
            @CodecPriority int codecPriority,
            @SampleRate int sampleRate,
            @BitsPerSample int bitsPerSample,
            @ChannelCount int channelCount,
            @FrameDuration int frameDuration,
            int octetsPerFrame,
            int minOctetsPerFrame,
            int maxOctetsPerFrame) {
        mCodecType = codecType;
        mCodecId = codecId;
        mCodecPriority = codecPriority;
        mSampleRate = sampleRate;
        mBitsPerSample = bitsPerSample;
        mChannelCount = channelCount;
        mFrameDuration = frameDuration;
        mOctetsPerFrame = octetsPerFrame;
        mMinOctetsPerFrame = minOctetsPerFrame;
        mMaxOctetsPerFrame = maxOctetsPerFrame;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** {@link Parcelable.Creator} interface implementation. */
    public static final @NonNull Parcelable.Creator<BluetoothLeAudioCodecConfig> CREATOR =
            new Parcelable.Creator<BluetoothLeAudioCodecConfig>() {
                public BluetoothLeAudioCodecConfig createFromParcel(Parcel in) {
                    int codecType = in.readInt();
                    int codecPriority = in.readInt();
                    int sampleRate = in.readInt();
                    int bitsPerSample = in.readInt();
                    int channelCount = in.readInt();
                    int frameDuration = in.readInt();
                    int octetsPerFrame = in.readInt();
                    int minOctetsPerFrame = in.readInt();
                    int maxOctetsPerFrame = in.readInt();
                    long codecId = CODEC_ID_INVALID;

                    if (Flags.leaudioCodecIdSupport()) {
                        codecId = in.readLong();
                        return new BluetoothLeAudioCodecConfig(
                                codecType,
                                codecId,
                                codecPriority,
                                sampleRate,
                                bitsPerSample,
                                channelCount,
                                frameDuration,
                                octetsPerFrame,
                                minOctetsPerFrame,
                                maxOctetsPerFrame);
                    }

                    return new BluetoothLeAudioCodecConfig(
                            codecType,
                            codecPriority,
                            sampleRate,
                            bitsPerSample,
                            channelCount,
                            frameDuration,
                            octetsPerFrame,
                            minOctetsPerFrame,
                            maxOctetsPerFrame);
                }

                public BluetoothLeAudioCodecConfig[] newArray(int size) {
                    return new BluetoothLeAudioCodecConfig[size];
                }
            };

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mCodecType);
        out.writeInt(mCodecPriority);
        out.writeInt(mSampleRate);
        out.writeInt(mBitsPerSample);
        out.writeInt(mChannelCount);
        out.writeInt(mFrameDuration);
        out.writeInt(mOctetsPerFrame);
        out.writeInt(mMinOctetsPerFrame);
        out.writeInt(mMaxOctetsPerFrame);
        if (Flags.leaudioCodecIdSupport()) {
            out.writeLong(mCodecId);
        }
    }

    private static String sampleRateToString(@SampleRate int sampleRateBit) {
        return switch (sampleRateBit) {
            case SAMPLE_RATE_NONE -> "None";
            case SAMPLE_RATE_8000 -> "8 kHz";
            case SAMPLE_RATE_11025 -> "11.025 kHz";
            case SAMPLE_RATE_16000 -> "16 kHz";
            case SAMPLE_RATE_22050 -> "22.05 kHz";
            case SAMPLE_RATE_24000 -> "24 kHz";
            case SAMPLE_RATE_32000 -> "32 kHz";
            case SAMPLE_RATE_44100 -> "44.1 kHz";
            case SAMPLE_RATE_48000 -> "48 kHz";
            case SAMPLE_RATE_88200 -> "88.2 kHz";
            case SAMPLE_RATE_96000 -> "96 kHz";
            case SAMPLE_RATE_176400 -> "176.4 kHz";
            case SAMPLE_RATE_192000 -> "192 kHz";
            case SAMPLE_RATE_384000 -> "384 kHz";
            default -> "Unknown bit " + sampleRateBit;
        };
    }

    private static String frameDurationToString(@FrameDuration int frameDurationBit) {
        return switch (frameDurationBit) {
            case FRAME_DURATION_NONE -> "None";
            case FRAME_DURATION_7500 -> "7.5 ms";
            case FRAME_DURATION_10000 -> "10 ms";
            case FRAME_DURATION_20000 -> "20 ms";
            default -> "Unknown bit " + frameDurationBit;
        };
    }

    @Override
    public String toString() {
        return ("BluetoothLeAudioCodecConfig [codecName=" + getCodecName())
                + (",mCodecType=" + mCodecType)
                + (",mCodecId=" + Long.toHexString(mCodecId))
                + (",mCodecPriority=" + mCodecPriority)
                + (",mSampleRate=" + sampleRateToString(mSampleRate))
                + (",mBitsPerSample=" + mBitsPerSample)
                + (",mChannelCountBitMask=" + mChannelCount)
                + (",mFrameDuration=" + frameDurationToString(mFrameDuration))
                + (",mOctetsPerFrame=" + mOctetsPerFrame)
                + (",mMinOctetsPerFrame=" + mMinOctetsPerFrame)
                + (",mMaxOctetsPerFrame=" + mMaxOctetsPerFrame + "]");
    }

    /**
     * Gets the codec type.
     *
     * @return the codec type
     */
    @RequiresNoPermission
    public @SourceCodecType int getCodecType() {
        return mCodecType;
    }

    /**
     * Returns the unique codec identifier.
     *
     * <p>The codec identifier is 40 bits as defined by Bluetooth ASCS v1.0.1 in chapter 4.1. Audio
     * Stream Endpoints
     *
     * <ul>
     *   <li>Bits 0-7: Codec ID, as defined by Bluetooth Assigned numbers 2.11 Coding_Format and
     *       Codec_ID (HCI)
     *       <ul>
     *         <li>0x06: LC3
     *         <li>0xFF: Vendor
     *       </ul>
     *   <li>Bits 8-23: Company ID, set to 0, if octet 0 is not 0xFF.
     *   <li>Bits 24-39: Vendor-defined codec ID, set to 0, if octet 0 is not 0xFF.
     * </ul>
     */
    @RequiresNoPermission
    @FlaggedApi(Flags.FLAG_LEAUDIO_CODEC_ID_SUPPORT)
    public long getCodecId() {
        return mCodecId;
    }

    /**
     * Gets the codec name.
     *
     * @return the codec name
     */
    @RequiresNoPermission
    public @NonNull String getCodecName() {
        return switch (mCodecType) {
            case SOURCE_CODEC_TYPE_LC3 -> "LC3";
            case SOURCE_CODEC_TYPE_OPUS -> "Opus";
            case SOURCE_CODEC_TYPE_OPUS_HI_RES -> "Opus Hi-Res";
            case SOURCE_CODEC_TYPE_INVALID -> "INVALID CODEC";
            default -> {
                if (Flags.leaudioCodecIdSupport()) {
                    if (mCodecType == SOURCE_CODEC_TYPE_VENDOR_SPECIFIC) {
                        yield "VENDOR SPECIFIC CODEC(" + mCodecId + ")";
                    }
                }
                yield "UNKNOWN CODEC(" + mCodecType + ", " + mCodecId + ")";
            }
        };
    }

    /**
     * Returns the codec selection priority.
     *
     * <p>The codec selection priority is relative to other codecs: larger value means higher
     * priority.
     */
    @RequiresNoPermission
    public @CodecPriority int getCodecPriority() {
        return mCodecPriority;
    }

    /** Returns the codec sample rate. */
    @RequiresNoPermission
    public @SampleRate int getSampleRate() {
        return mSampleRate;
    }

    /** Returns the codec bits per sample. */
    @RequiresNoPermission
    public @BitsPerSample int getBitsPerSample() {
        return mBitsPerSample;
    }

    /** Returns the codec channel mode. */
    @RequiresNoPermission
    public @ChannelCount int getChannelCount() {
        return mChannelCount;
    }

    /** Returns the frame duration. */
    @RequiresNoPermission
    public @FrameDuration int getFrameDuration() {
        return mFrameDuration;
    }

    /** Returns the octets per frame */
    @RequiresNoPermission
    public int getOctetsPerFrame() {
        return mOctetsPerFrame;
    }

    /** Returns the minimum octets per frame */
    @RequiresNoPermission
    public int getMinOctetsPerFrame() {
        return mMinOctetsPerFrame;
    }

    /** Returns the maximum octets per frame */
    @RequiresNoPermission
    public int getMaxOctetsPerFrame() {
        return mMaxOctetsPerFrame;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o instanceof BluetoothLeAudioCodecConfig) {
            BluetoothLeAudioCodecConfig other = (BluetoothLeAudioCodecConfig) o;
            if (Flags.leaudioCodecIdSupport()) {
                return (other.getCodecType() == mCodecType
                        && other.getCodecId() == mCodecId
                        && other.getCodecPriority() == mCodecPriority
                        && other.getSampleRate() == mSampleRate
                        && other.getBitsPerSample() == mBitsPerSample
                        && other.getChannelCount() == mChannelCount
                        && other.getFrameDuration() == mFrameDuration
                        && other.getOctetsPerFrame() == mOctetsPerFrame
                        && other.getMinOctetsPerFrame() == mMinOctetsPerFrame
                        && other.getMaxOctetsPerFrame() == mMaxOctetsPerFrame);
            }

            return (other.getCodecType() == mCodecType
                    && other.getCodecPriority() == mCodecPriority
                    && other.getSampleRate() == mSampleRate
                    && other.getBitsPerSample() == mBitsPerSample
                    && other.getChannelCount() == mChannelCount
                    && other.getFrameDuration() == mFrameDuration
                    && other.getOctetsPerFrame() == mOctetsPerFrame
                    && other.getMinOctetsPerFrame() == mMinOctetsPerFrame
                    && other.getMaxOctetsPerFrame() == mMaxOctetsPerFrame);
        }
        return false;
    }

    /**
     * Returns a hash representation of this BluetoothLeAudioCodecConfig based on all the config
     * values.
     */
    @Override
    public int hashCode() {
        return Objects.hash(
                mCodecType,
                mCodecId,
                mCodecPriority,
                mSampleRate,
                mBitsPerSample,
                mChannelCount,
                mFrameDuration,
                mOctetsPerFrame,
                mMinOctetsPerFrame,
                mMaxOctetsPerFrame);
    }

    /**
     * Builder for {@link BluetoothLeAudioCodecConfig}.
     *
     * <p>By default, the codec type will be set to {@link
     * BluetoothLeAudioCodecConfig#SOURCE_CODEC_TYPE_INVALID}
     */
    public static final class Builder {
        private int mCodecType = BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_INVALID;
        private int mCodecPriority = BluetoothLeAudioCodecConfig.CODEC_PRIORITY_DEFAULT;
        private int mSampleRate = BluetoothLeAudioCodecConfig.SAMPLE_RATE_NONE;
        private int mBitsPerSample = BluetoothLeAudioCodecConfig.BITS_PER_SAMPLE_NONE;
        private int mChannelCount = BluetoothLeAudioCodecConfig.CHANNEL_COUNT_NONE;
        private int mFrameDuration = BluetoothLeAudioCodecConfig.FRAME_DURATION_NONE;
        private int mOctetsPerFrame = 0;
        private int mMinOctetsPerFrame = 0;
        private int mMaxOctetsPerFrame = 0;
        private long mCodecId = CODEC_ID_INVALID;

        public Builder() {}

        public Builder(@NonNull BluetoothLeAudioCodecConfig config) {
            mCodecType = config.getCodecType();
            if (Flags.leaudioCodecIdSupport()) {
                mCodecId = config.getCodecId();
            }
            mCodecPriority = config.getCodecPriority();
            mSampleRate = config.getSampleRate();
            mBitsPerSample = config.getBitsPerSample();
            mChannelCount = config.getChannelCount();
            mFrameDuration = config.getFrameDuration();
            mOctetsPerFrame = config.getOctetsPerFrame();
            mMinOctetsPerFrame = config.getMinOctetsPerFrame();
            mMaxOctetsPerFrame = config.getMaxOctetsPerFrame();
        }

        /**
         * Set codec type for Bluetooth LE audio codec config.
         *
         * @param codecType of this codec
         * @return the same Builder instance
         */
        @RequiresNoPermission
        public @NonNull Builder setCodecType(@SourceCodecType int codecType) {
            mCodecType = codecType;
            if (Flags.leaudioCodecIdSupport()) {
                mCodecId =
                        switch (codecType) {
                            case SOURCE_CODEC_TYPE_LC3 -> CODEC_ID_LC3;
                            case SOURCE_CODEC_TYPE_OPUS -> CODEC_ID_OPUS;
                            case SOURCE_CODEC_TYPE_OPUS_HI_RES -> CODEC_ID_OPUS;
                            case SOURCE_CODEC_TYPE_VENDOR_SPECIFIC -> CODEC_ID_INVALID;
                            default -> CODEC_ID_INVALID;
                        };
            }

            return this;
        }

        /**
         * Sets the codec ID.
         *
         * <p>The codec identifier is 40 bits as defined by Bluetooth ASCS v1.0.1.
         *
         * <p>Currently, only the following Codec IDs are supported:
         *
         * <ul>
         *   <li><b>LC3:</b> 0x00_0000000006
         *   <li><b>Vendor Specific:</b> 0xNN_NNNNNNNNFF
         * </ul>
         *
         * @param codecId The 40-bit codec identifier.
         * @return the same Builder instance
         * @throws IllegalArgumentException if the codecId is greater than 40 bits, or not supported
         *     (not LC3 or Vendor Specific).
         */
        @RequiresNoPermission
        @FlaggedApi(Flags.FLAG_LEAUDIO_CODEC_ID_SUPPORT)
        public @NonNull Builder setCodecId(long codecId) {
            // Enforce that the input is at most 40 bits (top 24 bits must be zero)
            if ((codecId & ~0xFF_FFFFFFFFL) != 0) {
                throw new IllegalArgumentException(
                        "Codec ID must be 40 bits or less: " + String.format("0x%X", codecId));
            }

            // Validate the Codec ID structure
            //    - Must be LC3
            //    - OR the LSB must be the Vendor Specific ID (0xFF)
            if (codecId != CODEC_ID_LC3 && (codecId & 0xFF) != CODEC_ID_VENDOR_SPECIFIC) {
                throw new IllegalArgumentException(
                        "Unsupported Codec ID: " + String.format("0x%010X", codecId));
            }

            /* Check if mCodecType need to be set properly */
            if (mCodecType == BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_INVALID) {
                mCodecId = codecId;

                if (codecId == CODEC_ID_LC3) {
                    mCodecType = BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3;
                } else if (codecId == CODEC_ID_OPUS) {
                    mCodecType = BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_OPUS;
                } else {
                    mCodecType = BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_VENDOR_SPECIFIC;
                }
                return this;
            }

            /* Check if codecId is valid */
            if (mCodecType == BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3) {
                if (codecId != CODEC_ID_LC3) {
                    Log.w(TAG, "Invalid codecId for LC3 codec. Setting to proper value");
                }
                mCodecId = CODEC_ID_LC3;
                return this;
            }

            if (mCodecType == BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_OPUS
                    || mCodecType == BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_OPUS_HI_RES) {
                if (codecId != CODEC_ID_OPUS) {
                    Log.w(TAG, "Invalid codecId for Opus codec. Setting to proper value");
                }
                mCodecId = CODEC_ID_OPUS;
                return this;
            }

            mCodecId = codecId;
            return this;
        }

        /**
         * Set codec priority for Bluetooth LE audio codec config.
         *
         * @param codecPriority of this codec
         * @return the same Builder instance
         */
        @RequiresNoPermission
        public @NonNull Builder setCodecPriority(@CodecPriority int codecPriority) {
            mCodecPriority = codecPriority;
            return this;
        }

        /**
         * Set sample rate for Bluetooth LE audio codec config.
         *
         * @param sampleRate of this codec
         * @return the same Builder instance
         */
        @RequiresNoPermission
        public @NonNull Builder setSampleRate(@SampleRate int sampleRate) {
            mSampleRate = sampleRate;
            return this;
        }

        /**
         * Set the bits per sample for LE audio codec config.
         *
         * @param bitsPerSample of this codec
         * @return the same Builder instance
         */
        @RequiresNoPermission
        public @NonNull Builder setBitsPerSample(@BitsPerSample int bitsPerSample) {
            mBitsPerSample = bitsPerSample;
            return this;
        }

        /**
         * Set the channel count for Bluetooth LE audio codec config.
         *
         * @param channelCount of this codec
         * @return the same Builder instance
         */
        @RequiresNoPermission
        public @NonNull Builder setChannelCount(@ChannelCount int channelCount) {
            mChannelCount = channelCount;
            return this;
        }

        /**
         * Set the frame duration for Bluetooth LE audio codec config.
         *
         * @param frameDuration of this codec
         * @return the same Builder instance
         */
        @RequiresNoPermission
        public @NonNull Builder setFrameDuration(@FrameDuration int frameDuration) {
            mFrameDuration = frameDuration;
            return this;
        }

        /**
         * Set the octets per frame for Bluetooth LE audio codec config.
         *
         * @param octetsPerFrame of this codec
         * @return the same Builder instance
         */
        @RequiresNoPermission
        public @NonNull Builder setOctetsPerFrame(int octetsPerFrame) {
            mOctetsPerFrame = octetsPerFrame;
            return this;
        }

        /**
         * Set the minimum octets per frame for Bluetooth LE audio codec config.
         *
         * @param minOctetsPerFrame of this codec
         * @return the same Builder instance
         */
        @RequiresNoPermission
        public @NonNull Builder setMinOctetsPerFrame(int minOctetsPerFrame) {
            mMinOctetsPerFrame = minOctetsPerFrame;
            return this;
        }

        /**
         * Set the maximum octets per frame for Bluetooth LE audio codec config.
         *
         * @param maxOctetsPerFrame of this codec
         * @return the same Builder instance
         */
        @RequiresNoPermission
        public @NonNull Builder setMaxOctetsPerFrame(int maxOctetsPerFrame) {
            mMaxOctetsPerFrame = maxOctetsPerFrame;
            return this;
        }

        /**
         * Build {@link BluetoothLeAudioCodecConfig}.
         *
         * @return new BluetoothLeAudioCodecConfig built
         */
        @RequiresNoPermission
        public @NonNull BluetoothLeAudioCodecConfig build() {
            if (Flags.leaudioCodecIdSupport()) {
                return new BluetoothLeAudioCodecConfig(
                        mCodecType,
                        mCodecId,
                        mCodecPriority,
                        mSampleRate,
                        mBitsPerSample,
                        mChannelCount,
                        mFrameDuration,
                        mOctetsPerFrame,
                        mMinOctetsPerFrame,
                        mMaxOctetsPerFrame);
            }
            return new BluetoothLeAudioCodecConfig(
                    mCodecType,
                    mCodecPriority,
                    mSampleRate,
                    mBitsPerSample,
                    mChannelCount,
                    mFrameDuration,
                    mOctetsPerFrame,
                    mMinOctetsPerFrame,
                    mMaxOctetsPerFrame);
        }
    }
}
