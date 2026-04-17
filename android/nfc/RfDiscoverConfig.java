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
package android.nfc;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents the configuration for NFC RF discovery parameters.
 *
 * <p>This class encapsulates specific settings such as technology mode and frequency
 * used during the NFC discovery process.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(com.android.nfc.module.flags.Flags.FLAG_NFCSTACK_26Q2_UPDATES)
public final class RfDiscoverConfig implements Parcelable {
    private final int mTechnologyMode;
    private final int mFrequency;

    /**
     * NFC-A (ISO 14443-3A) technology in passive poll mode.
     */
    @FlaggedApi(com.android.nfc.module.flags.Flags.FLAG_NFCSTACK_26Q2_UPDATES)
    public static final int NFC_A_PASSIVE_POLL_MODE = 0x0;

    /**
     * NFC-B (ISO 14443-3B) technology in passive poll mode.
     */
    @FlaggedApi(com.android.nfc.module.flags.Flags.FLAG_NFCSTACK_26Q2_UPDATES)
    public static final int NFC_B_PASSIVE_POLL_MODE = 0x1;

    /**
     * NFC-F (FeliCa) technology in passive poll mode.
     */
    @FlaggedApi(com.android.nfc.module.flags.Flags.FLAG_NFCSTACK_26Q2_UPDATES)
    public static final int NFC_F_PASSIVE_POLL_MODE = 0x2;

    /**
     * Active poll mode.
     */
    @FlaggedApi(com.android.nfc.module.flags.Flags.FLAG_NFCSTACK_26Q2_UPDATES)
    public static final int NFC_ACTIVE_POLL_MODE = 0x3;

    /**
     * NFC-V (ISO 15693) technology in passive poll mode.
     */
    @FlaggedApi(com.android.nfc.module.flags.Flags.FLAG_NFCSTACK_26Q2_UPDATES)
    public static final int NFC_V_PASSIVE_POLL_MODE = 0x6;

    /**
     * Kovio technology in poll mode.
     */
    @FlaggedApi(com.android.nfc.module.flags.Flags.FLAG_NFCSTACK_26Q2_UPDATES)
    public static final int NFC_A_KOVIO_POLL_MODE = 0x77;

    /**
     * NFC-A (ISO 14443-3A) technology in passive listen mode.
     */
    @FlaggedApi(com.android.nfc.module.flags.Flags.FLAG_NFCSTACK_26Q2_UPDATES)
    public static final int NFC_A_PASSIVE_LISTEN_MODE = 0x80;

    /**
     * NFC-B (ISO 14443-3B) technology in passive listen mode.
     */
    @FlaggedApi(com.android.nfc.module.flags.Flags.FLAG_NFCSTACK_26Q2_UPDATES)
    public static final int NFC_B_PASSIVE_LISTEN_MODE = 0x81;

    /**
     * NFC-F (FeliCa) technology in passive listen mode.
     */
    @FlaggedApi(com.android.nfc.module.flags.Flags.FLAG_NFCSTACK_26Q2_UPDATES)
    public static final int NFC_F_PASSIVE_LISTEN_MODE = 0x82;

    /**
     * Active listen mode.
     */
    @FlaggedApi(com.android.nfc.module.flags.Flags.FLAG_NFCSTACK_26Q2_UPDATES)
    public static final int NFC_ACTIVE_LISTEN_MODE = 0x83;


    /**
     * Possible rf discover technology modes for
     * {@link #RfDiscoverConfig(int techMode, int frequency)}.
     *
     * @hide
     */
    @IntDef(prefix = { "NFC_" }, value = {
        NFC_A_PASSIVE_POLL_MODE,
        NFC_B_PASSIVE_POLL_MODE,
        NFC_F_PASSIVE_POLL_MODE,
        NFC_ACTIVE_POLL_MODE,
        NFC_V_PASSIVE_POLL_MODE,
        NFC_A_KOVIO_POLL_MODE,
        NFC_A_PASSIVE_LISTEN_MODE,
        NFC_B_PASSIVE_LISTEN_MODE,
        NFC_F_PASSIVE_LISTEN_MODE,
        NFC_ACTIVE_LISTEN_MODE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RfDiscoverMode{}

    /**
     * Constructs a new {@link RfDiscoverConfig} with the specified parameters.
     *
     * @param technologyMode The technology mode to be used for discovery.
     * @param frequency The frequency to be used for discovery.
     */
    public RfDiscoverConfig(@RfDiscoverMode int technologyMode,
            @IntRange(from = 1, to = 10) int frequency) {
        mTechnologyMode = technologyMode;
        mFrequency = frequency;
    }

    /**
     * Gets the technology mode configured for this discovery instance.
     *
     * @return The technology mode integer value.
     */
    public @RfDiscoverMode int getTechnologyMode() {
        return mTechnologyMode;
    }

    /**
     * Gets the frequency configured for this discovery instance.
     *
     * The execution cadence. Must be a value between 1 and 10 inclusive.
     * A value of 1 indicates the technology is executed every cycle, while
     * a value of 10 indicates it is executed once every 10 cycles.
     *
     * @return The frequency integer value.
     */
    @IntRange(from = 1, to = 10)
    public int getFrequency() {
        return mFrequency;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private RfDiscoverConfig(Parcel in) {
        this.mTechnologyMode = in.readInt();
        this.mFrequency = in.readInt();
    }

    public static final @NonNull Parcelable.Creator<RfDiscoverConfig> CREATOR =
            new Parcelable.Creator<RfDiscoverConfig>() {
                @Override
                public RfDiscoverConfig createFromParcel(Parcel in) {
                    return new RfDiscoverConfig(in);
                }

                @Override
                public RfDiscoverConfig[] newArray(int size) {
                    return new RfDiscoverConfig[size];
                }
            };

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mTechnologyMode);
        dest.writeInt(mFrequency);
    }
}
