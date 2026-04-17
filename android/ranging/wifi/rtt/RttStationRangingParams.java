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

package android.ranging.wifi.rtt;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.net.wifi.WifiAnnotations;
import android.os.Parcel;
import android.os.Parcelable;
import android.ranging.raw.RawRangingDevice;
import android.ranging.raw.RawRangingDevice.RangingUpdateRate;

import com.android.ranging.flags.Flags;

import java.util.Objects;

/**
 * Represents the parameters required to perform Wi-Fi Station-AP
 * Round Trip Time (RTT) ranging.
 */

@FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_25Q4)
public final class RttStationRangingParams implements Parcelable {
    private final String mBssid;
    private final int mChannelWidth;
    @RangingUpdateRate
    private final int mRangingUpdateRate;
    public static final int CHANNEL_WIDTH_DEFAULT = 0xFF;

    private RttStationRangingParams(Builder builder) {
        mBssid = builder.mBssid;
        mChannelWidth = builder.mChannelWidth;
        mRangingUpdateRate = builder.mRangingUpdateRate;
    }

    private RttStationRangingParams(Parcel in) {
        mBssid = in.readString();
        mChannelWidth = in.readInt();
        mRangingUpdateRate = in.readInt();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mBssid);
        dest.writeInt(mChannelWidth);
        dest.writeInt(mRangingUpdateRate);
    }

    @NonNull
    public static final Creator<RttStationRangingParams> CREATOR =
            new Creator<RttStationRangingParams>() {
                @Override
                public RttStationRangingParams createFromParcel(Parcel in) {
                    return new RttStationRangingParams(in);
                }

                @Override
                public RttStationRangingParams[] newArray(int size) {
                    return new RttStationRangingParams[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Returns the BSSID associated with this RTT ranging session.
     *
     * @return the BSSID {@link String}.
     * @see android.net.wifi.ScanResult.Builder#setBssid(String bssid)
     */
    @NonNull
    public String getBssid() {
        return mBssid;
    }

    /**
     * Returns the channel width associated with this RTT ranging session.
     *
     * @return the channel width as a {@link Integer}.
     * @see android.net.wifi.ScanResult.Builder#setChannelWidth
     */
    @WifiAnnotations.ChannelWidth
    public int getChannelWidth() {
        return mChannelWidth;
    }

    /**
     * Returns the ranging update rate.
     *
     * @return ranging update rate.
     * <p>Possible values:
     * {@link RangingUpdateRate#UPDATE_RATE_NORMAL}
     * {@link RangingUpdateRate#UPDATE_RATE_INFREQUENT}
     * {@link RangingUpdateRate#UPDATE_RATE_FREQUENT}
     */
    @RangingUpdateRate
    public int getRangingUpdateRate() {
        return mRangingUpdateRate;
    }

    /**
     * Builder class for {@link RttStationRangingParams}.
     */
    public static final class Builder {
        private String mBssid = "";
        private int mChannelWidth = CHANNEL_WIDTH_DEFAULT;
        @RawRangingDevice.RangingUpdateRate
        private int mRangingUpdateRate = RawRangingDevice.UPDATE_RATE_NORMAL;

        /**
         * Constructs a new {@link Builder} for creating a Wifi Station ranging session.
         *
         * @throws IllegalArgumentException if {@code bssid} is null.
         * @param bssid  address of the remote AP.
         */
        public Builder(@NonNull String bssid) {
            Objects.requireNonNull(bssid);
            mBssid = bssid;
        }

        /**
         * set channel width for this ranging session
         *
         * @param channelWidth . Set channel width if ranging have to be started
         *                with lower bandwidth than supported higher bandwidth.
         *                <p>Possible values:
         *                {@link android.net.wifi.rtt.ResponderConfig#CHANNEL_WIDTH_20MHZ}
         *                {@link android.net.wifi.rtt.ResponderConfig#CHANNEL_WIDTH_40MHZ}
         *                {@link android.net.wifi.rtt.ResponderConfig#CHANNEL_WIDTH_80MHZ}
         *                {@link android.net.wifi.rtt.ResponderConfig#CHANNEL_WIDTH_160MHZ}
         *                {@link android.net.wifi.rtt.ResponderConfig#CHANNEL_WIDTH_80MHZ_PLUS_MHZ}
         *                {@link android.net.wifi.rtt.ResponderConfig#CHANNEL_WIDTH_320MHZ}
         * @throws IllegalArgumentException if {@code channelWidth} is null.
         */
        @NonNull
        public Builder setChannelWidth(@WifiAnnotations.ChannelWidth int channelWidth) {
            this.mChannelWidth = channelWidth;
            return this;
        }

        /**
         * Sets the update rate for the STA-AP ranging session.
         * <p>Defaults to {@link RangingUpdateRate#UPDATE_RATE_NORMAL}
         *
         * @param updateRate the reporting frequency.
         *                   <p>Possible values:
         *                   {@link RangingUpdateRate#UPDATE_RATE_NORMAL}
         *                   {@link RangingUpdateRate#UPDATE_RATE_INFREQUENT}
         *                   {@link RangingUpdateRate#UPDATE_RATE_FREQUENT}
         * @return this {@link Builder} instance.
         */
        @NonNull
        public Builder setRangingUpdateRate(@RawRangingDevice.RangingUpdateRate int updateRate) {
            mRangingUpdateRate = updateRate;
            return this;
        }

        /**
         * Builds and returns a new {@link RttStationRangingParams} instance.
         *
         * @return a new {@link RttStationRangingParams} object configured with the
         * provided parameters.
         */
        @NonNull
        public RttStationRangingParams build() {
            return new RttStationRangingParams(this);
        }
    }

    @Override
    public String toString() {
        return "RttStationRangingParams{ "
                + "BSS_ID= "
                + mBssid
                + ", Channel Width="
                + mChannelWidth
                + ", mRangingUpdateRate="
                + mRangingUpdateRate
                + " }";
    }

    /**
     * @hide
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RttStationRangingParams that)) return false;
        return Objects.equals(mBssid, that.mBssid)
                && mChannelWidth == that.mChannelWidth
                && mRangingUpdateRate == that.mRangingUpdateRate;
    }

    /**
     * @hide
     */
    @Override
    public int hashCode() {
        return Objects.hash(mBssid, mChannelWidth, mRangingUpdateRate);
    }

}
