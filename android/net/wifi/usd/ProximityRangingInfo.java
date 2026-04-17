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

package android.net.wifi.usd;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiAnnotations;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.RequiresApi;

import com.android.wifi.flags.Flags;

import java.util.Objects;

/**
 * A class providing information about Proximity Ranging info of a
 * specific peer.
 *
 * @hide
 */
@SystemApi
@RequiresApi(37)
@FlaggedApi(Flags.FLAG_PROXIMITY_RANGING)
public final class ProximityRangingInfo implements Parcelable {
    @NonNull
    private final String mDeviceName;
    private final boolean mIs80211mcBasedRangingSupported;
    private final boolean mIsNtbNonSecureLtfRangingSupported;
    private final boolean mIsNtbSecureLtfRangingSupported;
    private final boolean mIsUnauthenticatedPasnModeSupported;
    private final boolean mIsAuthenticatedPasnModeSupported;
    private final boolean mIs80211mcBasedIstaRoleSupported;
    private final boolean mIs80211mcBasedRstaRoleSupported;
    private final boolean mIsNtbIstaRoleSupported;
    private final boolean mIsNtbRstaRoleSupported;
    private final @WifiAnnotations.ChannelWidth int mMaxSupportedPacketWidth80211mcBased;
    private final @WifiAnnotations.PreambleType int mMaxSupportedPreamble80211mcBased;
    private final @WifiAnnotations.ChannelWidth int mMaxSupportedPacketWidthNtb;
    private final @WifiAnnotations.PreambleType int mMaxSupportedPreambleNtb;
    private final boolean mIs6GHzSupported;

    private ProximityRangingInfo(Builder builder) {
        mDeviceName = builder.mDeviceName;
        mIs80211mcBasedRangingSupported = builder.mIs80211mcBasedRangingSupported;
        mIsNtbNonSecureLtfRangingSupported = builder.mIsNtbNonSecureLtfRangingSupported;
        mIsNtbSecureLtfRangingSupported = builder.mIsNtbSecureLtfRangingSupported;
        mIsUnauthenticatedPasnModeSupported = builder.mIsUnauthenticatedPasnModeSupported;
        mIsAuthenticatedPasnModeSupported = builder.mIsAuthenticatedPasnModeSupported;
        mIs80211mcBasedIstaRoleSupported = builder.mIs80211mcBasedIstaRoleSupported;
        mIs80211mcBasedRstaRoleSupported = builder.mIs80211mcBasedRstaRoleSupported;
        mIsNtbIstaRoleSupported = builder.mIsNtbIstaRoleSupported;
        mIsNtbRstaRoleSupported = builder.mIsNtbRstaRoleSupported;
        mMaxSupportedPacketWidth80211mcBased = builder.mMaxSupportedPacketWidth80211mcBased;
        mMaxSupportedPreamble80211mcBased = builder.mMaxSupportedPreamble80211mcBased;
        mMaxSupportedPacketWidthNtb = builder.mMaxSupportedPacketWidthNtb;
        mMaxSupportedPreambleNtb = builder.mMaxSupportedPreambleNtb;
        mIs6GHzSupported = builder.mIs6GHzSupported;
    }

    /**
     * Get the friendly name of the Proximity Detection device.
     */
    @NonNull
    public String getDeviceName() {
        return mDeviceName;
    }

    /**
     * Returns true if the device supports IEEE80211MC based ranging.
     * @return true if supported, false otherwise.
     */
    public boolean is80211mcBasedRangingSupported() {
        return mIs80211mcBasedRangingSupported;
    }

    /**
     * Returns true if the device supports NTB (Non-Trigger-Based) ranging with secure
     * Long Training Field (LTF).
     */
    public boolean isNtbSecureLtfRangingSupported() {
        return mIsNtbSecureLtfRangingSupported;
    }

    /**
     * Returns true if the device supports NTB (Non-Trigger-Based) ranging with a non-secure
     * Long Training Field (LTF).
     * @return true if supported, false otherwise.
     */
    public boolean isNtbNonSecureLtfRangingSupported() {
        return mIsNtbNonSecureLtfRangingSupported;
    }

    /**
     * Returns true if the device supports the Initiating Station (iSTA) role for
     * IEEE80211MC based ranging. The iSTA role is the device that initiates the ranging
     * measurement.
     * @return true if supported, false otherwise.
     */
    public boolean is80211mcBasedIstaRoleSupported() {
        return mIs80211mcBasedIstaRoleSupported;
    }

    /**
     * Returns true if the device supports the Responding Station (rSTA) role for
     * IEEE80211 based ranging. The rSTA role is the device that responds to the ranging request.
     * @return true if supported, false otherwise.
     */
    public boolean is80211mcBasedRstaRoleSupported() {
        return mIs80211mcBasedRstaRoleSupported;
    }

    /**
     * Returns true if the device supports the Initiating Station (iSTA) role for
     * Non-Trigger-Based (NTB) ranging.
     * @return true if supported, false otherwise.
     */
    public boolean isNtbIstaRoleSupported() {
        return mIsNtbIstaRoleSupported;
    }

    /**
     * Returns true if the device supports the Responding Station (rSTA) role for
     * Non-Trigger-Based (NTB) ranging.
     * @return true if supported, false otherwise.
     */
    public boolean isNtbRstaRoleSupported() {
        return mIsNtbRstaRoleSupported;
    }

    /**
     * The maximum supported packet bandwidth for
     * IEEE80211MC based ranging.
     * @return the maximum supported packet bandwidth
     */
    public @WifiAnnotations.ChannelWidth int getMaxSupportedPacketWidth80211mcBased() {
        return mMaxSupportedPacketWidth80211mcBased;
    }

    /**
     * The maximum supported preamble or format for
     * IEEE80211MC based ranging.
     * @return the maximum supported preamble
     */
    public @WifiAnnotations.PreambleType int getMaxSupportedPreamble80211mcBased() {
        return mMaxSupportedPreamble80211mcBased;
    }

    /**
     * The maximum supported packet bandwidth for
     * NTB ranging.
     * @return the maximum supported packet bandwidth
     */
    public @WifiAnnotations.ChannelWidth int getMaxSupportedPacketWidthNtb() {
        return mMaxSupportedPacketWidthNtb;
    }

    /**
     * The maximum supported preamble or format for
     * NTB ranging.
     * @return the maximum supported preamble
     */
    public @WifiAnnotations.PreambleType int getMaxSupportedPreambleNtb() {
        return mMaxSupportedPreambleNtb;
    }

    /**
     * Returns true if the device supports unauthenticated PASN mode
     * i.e., when there are no authentication credentials
     * (no Password and no PMK)
     * @return true if supported, false otherwise.
     */
    public boolean isUnauthenticatedPasnModeSupported() {
        return mIsUnauthenticatedPasnModeSupported;
    }

    /**
     * Returns true if the device supports authenticated PASN mode
     * i.e., when both devices share a Password or PMK that is
     * coupled with DevIK are used as the authentication
     * credentials
     * @return true if supported, false otherwise.
     */
    public boolean isAuthenticatedPasnModeSupported() {
        return mIsAuthenticatedPasnModeSupported;
    }

    /**
     * Returns true if the device supports ranging in 6GHz band.
     * @return true if supported, false otherwise.
     */
    public boolean is6GHzSupported() {
        return mIs6GHzSupported;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mDeviceName);
        dest.writeBoolean(mIs80211mcBasedRangingSupported);
        dest.writeBoolean(mIsNtbNonSecureLtfRangingSupported);
        dest.writeBoolean(mIsNtbSecureLtfRangingSupported);
        dest.writeBoolean(mIsUnauthenticatedPasnModeSupported);
        dest.writeBoolean(mIsAuthenticatedPasnModeSupported);
        dest.writeBoolean(mIs80211mcBasedIstaRoleSupported);
        dest.writeBoolean(mIs80211mcBasedRstaRoleSupported);
        dest.writeBoolean(mIsNtbIstaRoleSupported);
        dest.writeBoolean(mIsNtbRstaRoleSupported);
        dest.writeInt(mMaxSupportedPacketWidth80211mcBased);
        dest.writeInt(mMaxSupportedPreamble80211mcBased);
        dest.writeInt(mMaxSupportedPacketWidthNtb);
        dest.writeInt(mMaxSupportedPreambleNtb);
        dest.writeBoolean(mIs6GHzSupported);
    }

    private ProximityRangingInfo(Parcel in) {
        mDeviceName = in.readString();
        mIs80211mcBasedRangingSupported = in.readBoolean();
        mIsNtbNonSecureLtfRangingSupported = in.readBoolean();
        mIsNtbSecureLtfRangingSupported = in.readBoolean();
        mIsUnauthenticatedPasnModeSupported = in.readBoolean();
        mIsAuthenticatedPasnModeSupported = in.readBoolean();
        mIs80211mcBasedIstaRoleSupported = in.readBoolean();
        mIs80211mcBasedRstaRoleSupported = in.readBoolean();
        mIsNtbIstaRoleSupported = in.readBoolean();
        mIsNtbRstaRoleSupported = in.readBoolean();
        mMaxSupportedPacketWidth80211mcBased = in.readInt();
        mMaxSupportedPreamble80211mcBased = in.readInt();
        mMaxSupportedPacketWidthNtb = in.readInt();
        mMaxSupportedPreambleNtb = in.readInt();
        mIs6GHzSupported = in.readBoolean();
    }

    public static final @NonNull Creator<ProximityRangingInfo> CREATOR =
            new Creator<ProximityRangingInfo>() {
                @Override
                public ProximityRangingInfo createFromParcel(Parcel in) {
                    return new ProximityRangingInfo(in);
                }

                @Override
                public ProximityRangingInfo[] newArray(int size) {
                    return new ProximityRangingInfo[size];
                }
            };

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProximityRangingInfo that)) {
            return false;
        }
        return mIs80211mcBasedRangingSupported == that.mIs80211mcBasedRangingSupported
                && mIsNtbNonSecureLtfRangingSupported == that.mIsNtbNonSecureLtfRangingSupported
                && mIsNtbSecureLtfRangingSupported == that.mIsNtbSecureLtfRangingSupported
                && mIsUnauthenticatedPasnModeSupported == that.mIsUnauthenticatedPasnModeSupported
                && mIsAuthenticatedPasnModeSupported == that.mIsAuthenticatedPasnModeSupported
                && mIs80211mcBasedIstaRoleSupported == that.mIs80211mcBasedIstaRoleSupported
                && mIs80211mcBasedRstaRoleSupported == that.mIs80211mcBasedRstaRoleSupported
                && mIsNtbIstaRoleSupported == that.mIsNtbIstaRoleSupported
                && mIsNtbRstaRoleSupported == that.mIsNtbRstaRoleSupported
                && mMaxSupportedPacketWidth80211mcBased == that.mMaxSupportedPacketWidth80211mcBased
                && mMaxSupportedPreamble80211mcBased == that.mMaxSupportedPreamble80211mcBased
                && mMaxSupportedPacketWidthNtb == that.mMaxSupportedPacketWidthNtb
                && mMaxSupportedPreambleNtb == that.mMaxSupportedPreambleNtb
                && mIs6GHzSupported == that.mIs6GHzSupported
                && Objects.equals(mDeviceName, that.mDeviceName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDeviceName, mIs80211mcBasedRangingSupported,
                mIsNtbNonSecureLtfRangingSupported, mIsNtbSecureLtfRangingSupported,
                mIsUnauthenticatedPasnModeSupported, mIsAuthenticatedPasnModeSupported,
                mIs80211mcBasedIstaRoleSupported, mIs80211mcBasedRstaRoleSupported,
                mIsNtbIstaRoleSupported, mIsNtbRstaRoleSupported,
                mMaxSupportedPacketWidth80211mcBased, mMaxSupportedPreamble80211mcBased,
                mMaxSupportedPacketWidthNtb, mMaxSupportedPreambleNtb, mIs6GHzSupported);
    }

    @Override
    public String toString() {
        return "ProximityRangingInfo{"
                + "mDeviceName='" + mDeviceName + '\''
                + ", mIs80211mcBasedRangingSupported=" + mIs80211mcBasedRangingSupported
                + ", mIsNtbNonSecureLtfRangingSupported=" + mIsNtbNonSecureLtfRangingSupported
                + ", mIsNtbSecureLtfRangingSupported=" + mIsNtbSecureLtfRangingSupported
                + ", mIsUnauthenticatedPasnModeSupported=" + mIsUnauthenticatedPasnModeSupported
                + ", mIsAuthenticatedPasnModeSupported=" + mIsAuthenticatedPasnModeSupported
                + ", mIs80211mcBasedIstaRoleSupported=" + mIs80211mcBasedIstaRoleSupported
                + ", mIs80211mcBasedRstaRoleSupported=" + mIs80211mcBasedRstaRoleSupported
                + ", mIsNtbIstaRoleSupported=" + mIsNtbIstaRoleSupported
                + ", mIsNtbRstaRoleSupported=" + mIsNtbRstaRoleSupported
                + ", mMaxSupportedPacketWidth80211mcBased=" + mMaxSupportedPacketWidth80211mcBased
                + ", mMaxSupportedPreamble80211mcBased=" + mMaxSupportedPreamble80211mcBased
                + ", mMaxSupportedPacketWidthNtb=" + mMaxSupportedPacketWidthNtb
                + ", mMaxSupportedPreambleNtb=" + mMaxSupportedPreambleNtb
                + ", mIs6GHzSupported=" + mIs6GHzSupported
                + '}';
    }

    /**
     * Builder for {@link ProximityRangingInfo}.
     */
    public static final class Builder {
        private String mDeviceName = "";
        private boolean mIs80211mcBasedRangingSupported = false;
        private boolean mIsNtbNonSecureLtfRangingSupported = false;
        private boolean mIsNtbSecureLtfRangingSupported = false;
        private boolean mIsUnauthenticatedPasnModeSupported = false;
        private boolean mIsAuthenticatedPasnModeSupported = false;
        private boolean mIs80211mcBasedIstaRoleSupported = false;
        private boolean mIs80211mcBasedRstaRoleSupported = false;
        private boolean mIsNtbIstaRoleSupported = false;
        private boolean mIsNtbRstaRoleSupported = false;
        private @WifiAnnotations.ChannelWidth int mMaxSupportedPacketWidth80211mcBased =
                ScanResult.CHANNEL_WIDTH_20MHZ;
        private @WifiAnnotations.PreambleType int mMaxSupportedPreamble80211mcBased =
                ScanResult.PREAMBLE_HT;
        private @WifiAnnotations.ChannelWidth int mMaxSupportedPacketWidthNtb =
                ScanResult.CHANNEL_WIDTH_20MHZ;
        private @WifiAnnotations.PreambleType int mMaxSupportedPreambleNtb =
                ScanResult.PREAMBLE_HT;
        private boolean mIs6GHzSupported = false;

        /**
         * Default constructor for the Builder.
         */
        public Builder() {
        }

        /** Sets the device name. */
        @NonNull
        public Builder setDeviceName(@NonNull String deviceName) {
            mDeviceName = deviceName;
            return this;
        }

        /** Sets whether IEEE80211MC based ranging is supported. */
        @NonNull
        public Builder set80211mcBasedRangingSupported(boolean supported) {
            mIs80211mcBasedRangingSupported = supported;
            return this;
        }

        /** Sets whether NTB non-secure LTF ranging is supported. */
        @NonNull
        public Builder setNtbNonSecureLtfRangingSupported(boolean supported) {
            mIsNtbNonSecureLtfRangingSupported = supported;
            return this;
        }

        /** Sets whether NTB secure LTF ranging is supported. */
        @NonNull
        public Builder setNtbSecureLtfRangingSupported(boolean supported) {
            mIsNtbSecureLtfRangingSupported = supported;
            return this;
        }

        /** Sets whether unauthenticated PASN mode is supported. */
        @NonNull
        public Builder setUnauthenticatedPasnModeSupported(boolean supported) {
            mIsUnauthenticatedPasnModeSupported = supported;
            return this;
        }

        /** Sets whether authenticated PASN mode is supported. */
        @NonNull
        public Builder setAuthenticatedPasnModeSupported(boolean supported) {
            mIsAuthenticatedPasnModeSupported = supported;
            return this;
        }

        /** Sets whether the IEEE80211MC based ISTA role is supported. */
        @NonNull
        public Builder set80211mcBasedIstaRoleSupported(boolean supported) {
            mIs80211mcBasedIstaRoleSupported = supported;
            return this;
        }

        /** Sets whether the IEEE80211MC based RSTA role is supported. */
        @NonNull
        public Builder set80211mcBasedRstaRoleSupported(boolean supported) {
            mIs80211mcBasedRstaRoleSupported = supported;
            return this;
        }

        /** Sets whether the NTB ISTA role is supported. */
        @NonNull
        public Builder setNtbIstaRoleSupported(boolean supported) {
            mIsNtbIstaRoleSupported = supported;
            return this;
        }

        /** Sets whether the NTB RSTA role is supported. */
        @NonNull
        public Builder setNtbRstaRoleSupported(boolean supported) {
            mIsNtbRstaRoleSupported = supported;
            return this;
        }

        /** Sets the max supported packet width for IEEE80211MC based ranging. */
        @NonNull
        public Builder setMaxSupportedPacketWidth80211mcBased(
                @WifiAnnotations.ChannelWidth int width) {
            mMaxSupportedPacketWidth80211mcBased = width;
            return this;
        }

        /** Sets the max supported preamble for IEEE80211MC based ranging. */
        @NonNull
        public Builder setMaxSupportedPreamble80211mcBased(
                @WifiAnnotations.PreambleType int preamble) {
            mMaxSupportedPreamble80211mcBased = preamble;
            return this;
        }

        /** Sets the max supported packet width for NTB ranging. */
        @NonNull
        public Builder setMaxSupportedPacketWidthNtb(@WifiAnnotations.ChannelWidth int width) {
            mMaxSupportedPacketWidthNtb = width;
            return this;
        }

        /** Sets the max supported preamble for NTB ranging. */
        @NonNull
        public Builder setMaxSupportedPreambleNtb(@WifiAnnotations.PreambleType int preamble) {
            mMaxSupportedPreambleNtb = preamble;
            return this;
        }

        /** Sets whether 6GHz band is supported. */
        @NonNull
        public Builder set6GHzSupported(boolean supported) {
            mIs6GHzSupported = supported;
            return this;
        }

        /**
         * Build {@link ProximityRangingInfo} given the current configurations made on the builder.
         * @return an instance of {@link ProximityRangingInfo}
         */
        @NonNull
        public ProximityRangingInfo build() {
            return new ProximityRangingInfo(this);
        }
    }
}
