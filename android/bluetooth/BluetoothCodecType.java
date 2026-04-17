/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.bluetooth.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents a supported source codec type for a Bluetooth A2DP device. See {@link
 * BluetoothA2dp#getSupportedCodecTypes}. The codec type is uniquely identified by its name and
 * codec identifier.
 */
public final class BluetoothCodecType implements Parcelable {
    private final int mNativeCodecType;
    private final long mCodecId;
    private final @NonNull String mCodecName;

    private BluetoothCodecType(Parcel in) {
        mNativeCodecType = in.readInt();
        if (Flags.a2dpLdacApi()) {
            mCodecId = in.readLong();
        } else {
            mCodecId = in.readLong() & 0xFFFFFFFFL;
        }
        mCodecName = in.readString();
    }

    /** SBC codec identifier. See {@link BluetoothCodecType#getCodecId}. */
    public static final long CODEC_ID_SBC = 0x0000000000;

    /** AAC codec identifier. See {@link BluetoothCodecType#getCodecId}. */
    public static final long CODEC_ID_AAC = 0x0000000002;

    /** AptX codec identifier. See {@link BluetoothCodecType#getCodecId}. */
    public static final long CODEC_ID_APTX = 0x0001004fff;

    /** Aptx HD codec identifier. See {@link BluetoothCodecType#getCodecId}. */
    public static final long CODEC_ID_APTX_HD = 0x002400d7ff;

    /**
     * LDAC codec identifier. See {@link BluetoothCodecType#getCodecId}.
     *
     * @deprecated This codec value is invalid. Please use {@link #CODEC_ID_SONY_LDAC} instead.
     */
    @FlaggedApi(Flags.FLAG_A2DP_LDAC_API)
    @Deprecated
    public static final long CODEC_ID_LDAC = 0x00aa012dff;

    /** LDAC codec identifier. See {@link BluetoothCodecType#getCodecId}. */
    @FlaggedApi(Flags.FLAG_A2DP_LDAC_API)
    public static final long CODEC_ID_SONY_LDAC = 0x00aa_012d_ffL;

    /** Opus codec identifier. See {@link BluetoothCodecType#getCodecId}. */
    public static final long CODEC_ID_OPUS = 0x000100e0ff;

    /** LHDC codec identifier. See {@link BluetoothCodecType#getCodecId}. */
    public static final long CODEC_ID_LHDCV5 = 0x4c35_053a_ffL;

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @LongDef({
        CODEC_ID_SBC,
        CODEC_ID_AAC,
        CODEC_ID_APTX,
        CODEC_ID_APTX_HD,
        CODEC_ID_LDAC,
        CODEC_ID_SONY_LDAC,
        CODEC_ID_OPUS,
        CODEC_ID_LHDCV5,
    })
    public @interface CodecId {}

    /**
     * Create the bluetooth codec type from the static codec type index.
     *
     * @param codecType the static codec type
     * @param codecId the unique codec id
     */
    private BluetoothCodecType(@BluetoothCodecConfig.SourceCodecType int codecType, long codecId) {
        mNativeCodecType = codecType;
        if (Flags.a2dpLdacApi()) {
            mCodecId = codecId;
        } else {
            mCodecId = codecId & 0xFFFFFFFFL;
        }
        mCodecName = BluetoothCodecConfig.getCodecName(codecType);
    }

    /**
     * Create the bluetooth codec type from the static codec type index.
     *
     * @param codecType the static codec type
     * @param codecId the unique codec id
     * @param codecName the codec name
     */
    @Hide
    @SystemApi
    public BluetoothCodecType(int codecType, long codecId, @NonNull String codecName) {
        mNativeCodecType = codecType;
        if (Flags.a2dpLdacApi()) {
            mCodecId = codecId;
        } else {
            mCodecId = codecId & 0xFFFFFFFFL;
        }
        mCodecName = codecName;
    }

    /** Returns if the codec type is mandatory in the Bluetooth specification. */
    @RequiresNoPermission
    public boolean isMandatoryCodec() {
        return mNativeCodecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC;
    }

    /**
     * Returns the codec unique identifier.
     *
     * <p>The codec identifier is 40 bits:
     *
     * <ul>
     *   <li>Bits 0-7: Audio Codec ID, as defined by [ID 6.5.1]
     *       <ul>
     *         <li>0x00: SBC
     *         <li>0x02: AAC
     *         <li>0xFF: Vendor
     *       </ul>
     *   <li>Bits 8-23: Company ID, set to 0, if octet 0 is not 0xFF.
     *   <li>Bits 24-39: Vendor-defined codec ID, set to 0, if octet 0 is not 0xFF.
     * </ul>
     */
    @RequiresNoPermission
    public long getCodecId() {
        return mCodecId;
    }

    /** Returns the codec name. */
    @RequiresNoPermission
    public @NonNull String getCodecName() {
        return mCodecName;
    }

    /**
     * Returns the native codec type. The native codec type is arbitrarily assigned to the codec.
     * Prefer {@link BluetoothCodecType#getCodecId}.
     */
    @Hide
    @RequiresNoPermission
    public int getNativeCodecType() {
        return mNativeCodecType;
    }

    @Override
    public String toString() {
        return mCodecName;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(mCodecId);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o instanceof BluetoothCodecType) {
            BluetoothCodecType other = (BluetoothCodecType) o;
            return other.mCodecId == mCodecId;
        }
        return false;
    }

    @Hide
    public static @NonNull BluetoothCodecType createFromParcel(Parcel in) {
        return new BluetoothCodecType(in);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mNativeCodecType);
        dest.writeLong(mCodecId);
        BluetoothUtils.writeStringToParcel(dest, mCodecName);
    }

    /**
     * Create the bluetooth codec type from the static codec type index.
     *
     * @param codecType the static codec type
     * @return the codec type if valid
     */
    @Hide
    @SystemApi
    @SuppressWarnings("FlaggedApi") // Due to deprecated CODEC_ID_LDAC
    public static @Nullable BluetoothCodecType createFromType(
            @BluetoothCodecConfig.SourceCodecType int codecType) {
        long codecId =
                switch (codecType) {
                    case BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC -> CODEC_ID_SBC;
                    case BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC -> CODEC_ID_AAC;
                    case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX -> CODEC_ID_APTX;
                    case BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD -> CODEC_ID_APTX_HD;
                    case BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC -> {
                        if (Flags.a2dpLdacApi()) {
                            yield CODEC_ID_SONY_LDAC;
                        } else {
                            yield CODEC_ID_LDAC;
                        }
                    }
                    case BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS -> CODEC_ID_OPUS;
                    case BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3,
                            BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID ->
                            -1;
                    default -> -1;
                };
        if (codecId == -1) {
            return null;
        }
        return new BluetoothCodecType(codecType, codecId);
    }

    /**
     * Create the bluetooth codec type from the codec ID.
     *
     * @param codecId the codec ID
     * @return the codec type if valid
     */
    @Hide
    @FlaggedApi(Flags.FLAG_A2DP_CREATE_CODEC_TYPE_FROM_ID_API)
    @SuppressWarnings("FlaggedApi") // Due to deprecated CODEC_ID_LDAC
    @SystemApi
    public static @Nullable BluetoothCodecType createFromId(@CodecId long codecId) {
        if (codecId == CODEC_ID_SBC) {
            return new BluetoothCodecType(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC, CODEC_ID_SBC, "SBC");
        }
        if (codecId == CODEC_ID_AAC) {
            return new BluetoothCodecType(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC, CODEC_ID_AAC, "AAC");
        }
        if (codecId == CODEC_ID_APTX) {
            return new BluetoothCodecType(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX, CODEC_ID_APTX, "AptX");
        }
        if (codecId == CODEC_ID_APTX_HD) {
            return new BluetoothCodecType(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD, CODEC_ID_APTX_HD, "AptX HD");
        }
        if (Flags.a2dpLdacApi()) {
            if (codecId == CODEC_ID_SONY_LDAC) {
                return new BluetoothCodecType(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC, CODEC_ID_SONY_LDAC, "LDAC");
            }
        } else if (codecId == CODEC_ID_LDAC) {
            return new BluetoothCodecType(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC, CODEC_ID_LDAC, "LDAC");
        }
        if (codecId == CODEC_ID_OPUS) {
            return new BluetoothCodecType(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS, CODEC_ID_OPUS, "Opus");
        }
        if (codecId == CODEC_ID_LHDCV5) {
            return new BluetoothCodecType(
                    BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID, CODEC_ID_LHDCV5, "LHDCV5");
        }
        return null;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<BluetoothCodecType> CREATOR =
            new Creator<>() {
                public BluetoothCodecType createFromParcel(Parcel in) {
                    return new BluetoothCodecType(in);
                }

                public BluetoothCodecType[] newArray(int size) {
                    return new BluetoothCodecType[size];
                }
            };
}
