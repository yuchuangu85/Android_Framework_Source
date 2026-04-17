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

package android.net.wifi.p2p;

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.net.wifi.WifiAnnotations;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.RequiresApi;

import com.android.wifi.flags.Flags;

/**
 * A class representing the connection capabilities of a Wi-Fi P2P link.
 * <p>
 * This class provides information about the physical layer (PHY) of the connection,
 * including the Wi-Fi standard in use (e.g. 802.11ax), the channel width, and the
 * number of transmit and receive spatial streams (NSS). This data can be used to
 * understand the performance characteristics of the P2P connection.
 * <p>
 * An instance of this class can be obtained from {@link WifiP2pGroup} or
 * {@link WifiP2pDevice}.
 *
 * @see WifiP2pGroup#getWifiP2pGroupClientConnectionInfo()
 * @see WifiP2pDevice#getWifiP2pConnectionInfo()
 */
@RequiresApi(37)
@FlaggedApi(Flags.FLAG_WIFI_P2P_CONNECTION_INFO)
public final class WifiP2pConnectionInfo implements Parcelable {
    /**
     * Indicates that the value of a field is not specified.
     */
    public static final int UNSPECIFIED = 0;

    private final int mWifiStandard;
    private final int mChannelWidth;
    private final int mTxNss;
    private final int mRxNss;

    private WifiP2pConnectionInfo(int wifiStandard, int channelWidth, int txNss, int rxNss) {
        this.mWifiStandard = wifiStandard;
        this.mChannelWidth = channelWidth;
        this.mTxNss = txNss;
        this.mRxNss = rxNss;
    }

    /** copy constructor */
    /** @hide */
    public WifiP2pConnectionInfo(@NonNull WifiP2pConnectionInfo source) {
        this.mWifiStandard = source.mWifiStandard;
        this.mChannelWidth = source.mChannelWidth;
        this.mTxNss = source.mTxNss;
        this.mRxNss = source.mRxNss;
    }

    private WifiP2pConnectionInfo(@NonNull Parcel in) {
        mWifiStandard = in.readInt();
        mChannelWidth = in.readInt();
        mTxNss = in.readInt();
        mRxNss = in.readInt();
    }

    @NonNull
    public static final Creator<WifiP2pConnectionInfo> CREATOR =
            new Creator<WifiP2pConnectionInfo>() {
                @Override
                public WifiP2pConnectionInfo createFromParcel(@NonNull Parcel in) {
                    return new WifiP2pConnectionInfo(in);
                }

                @Override
                public WifiP2pConnectionInfo[] newArray(int size) {
                    return new WifiP2pConnectionInfo[size];
                }
            };

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mWifiStandard);
        parcel.writeInt(mChannelWidth);
        parcel.writeInt(mTxNss);
        parcel.writeInt(mRxNss);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Gets the Wi-Fi standard of the current P2P connection.
     *
     * @return The Wi-Fi standard, as defined by the {@code WIFI_STANDARD_*} constants in
     *         {@link android.net.wifi.ScanResult}.
     */
    public @WifiAnnotations.WifiStandard int getWifiStandard() {
        return mWifiStandard;
    }

    /**
     * Gets the channel width of the current P2P connection.
     *
     * @return The channel width, as defined by the {@code CHANNEL_WIDTH_*} constants in
     *         {@link android.net.wifi.ScanResult}.
     */
    public @WifiAnnotations.ChannelWidth int getChannelWidth() {
        return mChannelWidth;
    }

    /**
     * Gets the maximum number of transmit spatial streams (NSS) for the current P2P connection.
     *
     * @return The maximum number of spatial streams used for transmitting data, or
     *         {@link #UNSPECIFIED} if the value is not available.
     */
    @IntRange(from = 0, to = 4)
    public int getTxNss() {
        return mTxNss;
    }

    /**
     * Gets the maximum number of receive spatial streams (NSS) for the current P2P connection.
     *
     * @return The maximum number of spatial streams used for receiving data, or
     *         {@link #UNSPECIFIED} if the value is not available.
     */
    @IntRange(from = 0, to = 4)
    public int getRxNss() {
        return mRxNss;
    }

    @Override
    public String toString() {
        StringBuilder sbuf = new StringBuilder("WifiP2pConnectionInfo:");
        sbuf.append("\n wifiStandard: ").append(mWifiStandard);
        sbuf.append("\n channelWidth: ").append(mChannelWidth);
        sbuf.append("\n txNss: ").append(mTxNss);
        sbuf.append("\n rxNss: ").append(mRxNss);
        return sbuf.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WifiP2pConnectionInfo)) return false;
        WifiP2pConnectionInfo that = (WifiP2pConnectionInfo) o;
        return mWifiStandard == that.mWifiStandard
                && mChannelWidth == that.mChannelWidth
                && mTxNss == that.mTxNss
                && mRxNss == that.mRxNss;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(mWifiStandard, mChannelWidth, mTxNss, mRxNss);
    }

    /**
     * Builder for {@link WifiP2pConnectionInfo}.
     * @hide
     */
    public static final class Builder {
        private final int mWifiStandard;
        private final int mChannelWidth;
        private int mTxNss = UNSPECIFIED;
        private int mRxNss = UNSPECIFIED;

        public Builder(@WifiAnnotations.WifiStandard int wifiStandard,
                @WifiAnnotations.ChannelWidth int channelWidth) {
            mWifiStandard = wifiStandard;
            mChannelWidth = channelWidth;
        }

        /**
         * Sets the maximum number of transmit spatial streams.
         * If not set, the default value is {@link WifiP2pConnectionInfo#UNSPECIFIED}.
         *
         * @param txNss The number of transmit spatial streams.
         * @return This builder.
         */
        @NonNull
        public Builder setTxNss(@IntRange(from = 0, to = 4) int txNss) {
            if (txNss < 0 || txNss > 4) {
                throw new IllegalArgumentException("txNss must be between 0 and 4");
            }
            mTxNss = txNss;
            return this;
        }

        /**
         * Sets the maximum number of receive spatial streams.
         * If not set, the default value is {@link WifiP2pConnectionInfo#UNSPECIFIED}.
         *
         * @param rxNss The number of receive spatial streams.
         * @return This builder.
         */
        @NonNull
        public Builder setRxNss(@IntRange(from = 0, to = 4) int rxNss) {
            if (rxNss < 0  || rxNss > 4) {
                throw new IllegalArgumentException("rxNss must be between 0 and 4");
            }
            mRxNss = rxNss;
            return this;
        }

        /**
         * Builds the {@link WifiP2pConnectionInfo} object.
         *
         * @return The built {@link WifiP2pConnectionInfo} object.
         */
        @NonNull
        public WifiP2pConnectionInfo build() {
            return new WifiP2pConnectionInfo(mWifiStandard, mChannelWidth, mTxNss, mRxNss);
        }
    }
}
