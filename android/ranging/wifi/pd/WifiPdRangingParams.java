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

package android.ranging.wifi.pd;

import static android.net.wifi.rtt.ResponderConfig.CHANNEL_WIDTH_20MHZ;
import static android.net.wifi.rtt.ResponderConfig.PREAMBLE_LEGACY;
import static android.ranging.wifi.pd.WifiPdRangingCapabilities.AUTHENTICATED_PASN_MODE;
import static android.ranging.wifi.pd.WifiPdRangingCapabilities.UNAUTHENTICATED_PASN_MODE;

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.MacAddress;
import android.net.wifi.WifiAnnotations;
import android.os.Parcel;
import android.os.Parcelable;
import android.ranging.RangingConfig;
import android.ranging.RangingSession;
import android.ranging.raw.RawRangingDevice;
import android.ranging.raw.RawRangingDevice.RangingUpdateRate;

import com.android.ranging.flags.Flags;

import java.util.Objects;

/**
 * Defines the parameters for Wi-Fi Proximity Detection (PD) ranging.
 *
 * <p>These parameters are used to configure a Wi-Fi PD ranging session. This includes specifying
 * the peer device's MAC address, discovery channel, ranging role, and security credentials.
 *
 * @see RangingSession#start(RangingConfig, RangingSession.Callback)
 */
@FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
public final class WifiPdRangingParams implements Parcelable {


    private final MacAddress mPeerMacAddress;
    private final int mDiscoveryChannelFrequencyMhz;
    private final @RangingUpdateRate int mRangingUpdateRate;
    private final @WifiPdRangingCapabilities.PasnMode int mPasnMode;
    private final byte[] mDeviceIk;
    private final String mPassword;
    private final @WifiAnnotations.PreambleType int mPreambleType;
    private final boolean mIsResponder80211azNtbSupported;
    private final @WifiAnnotations.ChannelWidth int mChannelWidth;

    private WifiPdRangingParams(
            MacAddress peerMacAddress,
            int discoveryChannelFrequencyMhz,
            @RangingUpdateRate int rangingUpdateRate,
            @WifiPdRangingCapabilities.PasnMode int pasnMode,
            byte[] deviceIk,
            String password,
            @WifiAnnotations.PreambleType int preambleType,
            boolean isResponder80211azNtbSupported,
            @WifiAnnotations.ChannelWidth int channelWidth) {
        mPeerMacAddress = peerMacAddress;
        mDiscoveryChannelFrequencyMhz = discoveryChannelFrequencyMhz;
        mRangingUpdateRate = rangingUpdateRate;
        mPasnMode = pasnMode;
        mDeviceIk = deviceIk;
        mPassword = password;
        mPreambleType = preambleType;
        mIsResponder80211azNtbSupported = isResponder80211azNtbSupported;
        mChannelWidth = channelWidth;
    }

    /** Gets the MAC address of the peer device. */
    @NonNull
    public MacAddress getPeerMacAddress() {
        return mPeerMacAddress;
    }

    /** Gets the discovery channel frequency in MHz. */
    @IntRange(from = 2400, to = 7125)
    public int getDiscoveryChannelFrequencyMhz() {
        return mDiscoveryChannelFrequencyMhz;
    }

    /** Gets the ranging update rate. */
    @RangingUpdateRate
    public int getRangingUpdateRate() {
        return mRangingUpdateRate;
    }

    /** Gets the PASN mode. */
    @WifiPdRangingCapabilities.PasnMode
    public int getPasnMode() {
        return mPasnMode;
    }

    /**
     * Gets the device identity key (IK).
     *
     * <p>This is used for authenticated PASN mode.
     */
    @Nullable
    public byte[] getDeviceIk() {
        return mDeviceIk;
    }

    /**
     * Gets the password for the ranging session.
     *
     * <p>This is used for authenticated PASN mode.
     */
    @Nullable
    public String getPassword() {
        return mPassword;
    }

    /** Gets the preamble type. */
    @WifiAnnotations.PreambleType
    public int getPreambleType() {
        return mPreambleType;
    }

    /** Returns whether the responder supports 802.11az NTB (Next Generation Trigger-Based). */
    public boolean isResponder80211azNtbSupported() {
        return mIsResponder80211azNtbSupported;
    }

    /** Gets the channel width. */
    @WifiAnnotations.ChannelWidth
    public int getChannelWidth() {
        return mChannelWidth;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mPeerMacAddress, flags);
        dest.writeInt(mDiscoveryChannelFrequencyMhz);
        dest.writeInt(mRangingUpdateRate);
        dest.writeInt(mPasnMode);
        dest.writeByteArray(mDeviceIk);
        dest.writeString(mPassword);
        dest.writeInt(mPreambleType);
        dest.writeBoolean(mIsResponder80211azNtbSupported);
        dest.writeInt(mChannelWidth);
    }

    public static final @NonNull Creator<WifiPdRangingParams> CREATOR =
            new Creator<WifiPdRangingParams>() {
                @Override
                public WifiPdRangingParams createFromParcel(@NonNull Parcel in) {
                    return new WifiPdRangingParams(
                            in.readParcelable(MacAddress.class.getClassLoader(), MacAddress.class),
                            in.readInt(),
                            in.readInt(),
                            in.readInt(),
                            in.createByteArray(),
                            in.readString(),
                            in.readInt(),
                            in.readBoolean(),
                            in.readInt());
                }

                @Override
                public WifiPdRangingParams[] newArray(int size) {
                    return new WifiPdRangingParams[size];
                }
            };

    /**
     * Builder for {@link WifiPdRangingParams}.
     *
     * <p>This class provides a convenient way to construct {@link WifiPdRangingParams} objects.
     * The MAC address of the peer device and the ranging service role are mandatory parameters
     * and must be provided during the Builder's construction. Other parameters are optional
     * and can be set using the respective setter methods.
     *
     * <p>Example usage:
     * <pre>{@code
     * MacAddress peerMacAddress = MacAddress.fromString("01:02:03:04:05:06");
     * WifiPdRangingParams params = new WifiPdRangingParams.Builder(peerMacAddress)
     *         .setDiscoveryChannelFrequencyMhz(5180)
     *         .setPasnMode(WifiPdRangingCapabilities.AUTHENTICATED_PASN_MODE)
     *         .setPassword("myPassword")
     *         .setDeviceIk(new byte[] {0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C})
     *         .build();
     * }</pre>
     */
    public static final class Builder {
        @NonNull
        private MacAddress mPeerMacAddress;

        private int mDiscoveryChannelFrequencyMhz = 2437;
        private @RangingUpdateRate int mRangingUpdateRate = RawRangingDevice.UPDATE_RATE_NORMAL;
        private @WifiPdRangingCapabilities.PasnMode int mPasnMode = UNAUTHENTICATED_PASN_MODE;
        private byte[] mDeviceIk = null;
        private String mPassword = null;
        private @WifiAnnotations.PreambleType int mPreambleType = PREAMBLE_LEGACY;
        private boolean mIsResponder80211azNtbSupported = true;
        private @WifiAnnotations.ChannelWidth int mChannelWidth = CHANNEL_WIDTH_20MHZ;

        /**
         * Constructs a new {@link Builder} for creating a ranging session.
         *
         * @param peerMacAddress The MAC address of the peer device.
         */
        public Builder(@NonNull MacAddress peerMacAddress) {
            Objects.requireNonNull(peerMacAddress);
            mPeerMacAddress = peerMacAddress;
        }

        /**
         * Sets the discovery channel frequency in MHz.
         *
         * @param discoveryChannelFrequencyMhz The discovery channel frequency in MHz.
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setDiscoveryChannelFrequencyMhz(
                @IntRange(from = 2400, to = 7125) int discoveryChannelFrequencyMhz) {
            mDiscoveryChannelFrequencyMhz = discoveryChannelFrequencyMhz;
            return this;
        }

        /**
         * Sets the ranging update rate.
         *
         * @param rangingUpdateRate The ranging update rate.
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setRangingUpdateRate(@RangingUpdateRate int rangingUpdateRate) {
            mRangingUpdateRate = rangingUpdateRate;
            return this;
        }

        /**
         * Sets the PASN (Pre-association security negotiation) mode.
         *
         * @param pasnMode The PASN mode.
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setPasnMode(@WifiPdRangingCapabilities.PasnMode int pasnMode) {
            mPasnMode = pasnMode;
            return this;
        }

        /**
         * Sets the device identity key (IK).
         *
         * @param deviceIk The device identity key.
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setDeviceIk(@Nullable byte[] deviceIk) {
            mDeviceIk = deviceIk;
            return this;
        }

        /**
         * Sets the password for the ranging session.
         *
         * @param password The password for the ranging session.
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setPassword(@Nullable String password) {
            mPassword = password;
            return this;
        }

        /**
         * Sets the preamble type.
         *
         * @param preambleType The preamble type.
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setPreambleType(@WifiAnnotations.PreambleType int preambleType) {
            mPreambleType = preambleType;
            return this;
        }

        /**
         * Sets whether the responder supports 802.11az NTB.
         *
         * @param isResponder80211azNtbSupported Whether the responder supports 802.11az NTB.
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setResponder80211azNtbSupported(boolean isResponder80211azNtbSupported) {
            mIsResponder80211azNtbSupported = isResponder80211azNtbSupported;
            return this;
        }

        /**
         * Sets the channel width.
         *
         * @param channelWidth The channel width.
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setChannelWidth(@WifiAnnotations.ChannelWidth int channelWidth) {
            mChannelWidth = channelWidth;
            return this;
        }

        /**
         * Builds the {@link WifiPdRangingParams} object.
         *
         * @return The built {@link WifiPdRangingParams} object.
         * @throws IllegalStateException if required parameters are not set.
         */
        @NonNull
        public WifiPdRangingParams build() {
            if (mPasnMode == AUTHENTICATED_PASN_MODE) {
                Objects.requireNonNull(mPassword, "Password must be set for authenticated PASN.");
                Objects.requireNonNull(mDeviceIk, "Device IK must be set for authenticated PASN.");
            }
            return new WifiPdRangingParams(
                    mPeerMacAddress,
                    mDiscoveryChannelFrequencyMhz,
                    mRangingUpdateRate,
                    mPasnMode,
                    mDeviceIk,
                    mPassword,
                    mPreambleType,
                    mIsResponder80211azNtbSupported,
                    mChannelWidth);
        }
    }
}
