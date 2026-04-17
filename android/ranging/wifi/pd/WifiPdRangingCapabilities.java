/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.net.MacAddress;
import android.net.wifi.WifiAnnotations;
import android.os.Parcel;
import android.os.Parcelable;
import android.ranging.RangingCapabilities;
import android.ranging.RangingManager;

import androidx.annotation.NonNull;

import com.android.ranging.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The ranging capabilities for Wi-Fi Proximity Detection (PD).
 *
 * <p>This class defines the ranging capabilities for Wi-Fi PD, which allows devices to measure the
 * distance to each other using Wi-Fi signals. These capabilities include supported PASN modes,
 * channel widths, preamble types, and discovery channels.
 *
 * @see RangingManager#getRangingCapabilities()
 */
@FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
public final class WifiPdRangingCapabilities
        implements Parcelable, RangingCapabilities.TechnologyCapabilities {

    /**
     * Defines the possible PASN modes.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            UNAUTHENTICATED_PASN_MODE,
            AUTHENTICATED_PASN_MODE,
    })
    public @interface PasnMode {
    }

    /**
     * Unauthenticated PASN mode.
     * In this mode, ranging is performed without establishing a secure link.
     */
    public static final int UNAUTHENTICATED_PASN_MODE = 0;

    /**
     * Authenticated PASN mode.
     * In this mode, ranging is performed over a secure link established using a password.
     */
    public static final int AUTHENTICATED_PASN_MODE = 1;
    @NonNull
    private final List<Integer> mSupportedPasnModes;
    @NonNull
    private final MacAddress mProximityDetectionMacAddress;
    private final boolean mIs80211mcSupported;
    private final boolean mIs80211azNtbSupported;
    private final int mMaxChannelWidth;
    private final int mMaxPreamble;
    @NonNull
    private final List<Integer> mSupportedDiscoveryChannelFrequenciesMhz;
    private final int m80211mcMinRangingIntervalMillis;
    private final int m80211azNtbMinRangingIntervalMillis;

    private WifiPdRangingCapabilities(Builder builder) {
        mSupportedPasnModes = builder.mSupportedPasnModes;
        mProximityDetectionMacAddress = builder.mProximityDetectionMacAddress;
        mIs80211mcSupported = builder.mIs80211mcSupported;
        mIs80211azNtbSupported = builder.mIs80211azNtbSupported;
        mMaxChannelWidth = builder.mMaxChannelWidth;
        mMaxPreamble = builder.mMaxPreamble;
        mSupportedDiscoveryChannelFrequenciesMhz = builder.mSupportedDiscoveryChannelFrequenciesMhz;
        m80211mcMinRangingIntervalMillis = builder.m80211mcMinRangingIntervalMillis;
        m80211azNtbMinRangingIntervalMillis = builder.m80211azNtbMinRangingIntervalMillis;
    }

    private WifiPdRangingCapabilities(Parcel in) {
        mSupportedPasnModes = new ArrayList<>();
        in.readList(mSupportedPasnModes, Integer.class.getClassLoader(), Integer.class);
        mProximityDetectionMacAddress = in.readParcelable(
                MacAddress.class.getClassLoader(), MacAddress.class);
        mIs80211mcSupported = in.readBoolean();
        mIs80211azNtbSupported = in.readBoolean();
        mMaxChannelWidth = in.readInt();
        mMaxPreamble = in.readInt();
        mSupportedDiscoveryChannelFrequenciesMhz = new ArrayList<>();
        in.readList(mSupportedDiscoveryChannelFrequenciesMhz, Integer.class.getClassLoader(),
                Integer.class);
        m80211mcMinRangingIntervalMillis = in.readInt();
        m80211azNtbMinRangingIntervalMillis = in.readInt();
    }

    /**
     * A creator for creating {@link WifiPdRangingCapabilities} from a {@link Parcel}.
     */
    @NonNull
    public static final Creator<WifiPdRangingCapabilities> CREATOR =
            new Creator<WifiPdRangingCapabilities>() {
                @Override
                public WifiPdRangingCapabilities createFromParcel(Parcel in) {
                    return new WifiPdRangingCapabilities(in);
                }

                @Override
                public WifiPdRangingCapabilities[] newArray(int size) {
                    return new WifiPdRangingCapabilities[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeList(mSupportedPasnModes);
        dest.writeParcelable(mProximityDetectionMacAddress, flags);
        dest.writeBoolean(mIs80211mcSupported);
        dest.writeBoolean(mIs80211azNtbSupported);
        dest.writeInt(mMaxChannelWidth);
        dest.writeInt(mMaxPreamble);
        dest.writeList(mSupportedDiscoveryChannelFrequenciesMhz);
        dest.writeInt(m80211mcMinRangingIntervalMillis);
        dest.writeInt(m80211azNtbMinRangingIntervalMillis);
    }

    @Override
    public @RangingManager.RangingTechnology int getTechnology() {
        return RangingManager.WIFI_PD;
    }

    /**
     * Gets a set of supported PASN modes.
     *
     * @return an unmodifiable set of supported {@link PasnMode}.
     */
    @NonNull
    @PasnMode
    public Set<Integer> getSupportedPasnModes() {
        return Collections.unmodifiableSet(new TreeSet<>(mSupportedPasnModes));
    }

    /**
     * Gets the proximity detection MAC address.
     *
     * @return a byte array representing the MAC address.
     */
    @NonNull
    public MacAddress getProximityDetectionMacAddress() {
        return mProximityDetectionMacAddress;
    }

    /**
     * Returns whether 802.11mc (Wi-Fi Round Trip Time) is supported.
     *
     * @return {@code true} if 802.11mc is supported, {@code false} otherwise.
     */
    public boolean is80211mcSupported() {
        return mIs80211mcSupported;
    }

    /**
     * Returns whether 802.11az NTB (Next Generation Trigger-Based) is supported.
     *
     * @return {@code true} if 802.11az NTB is supported, {@code false} otherwise.
     */
    public boolean is80211azNtbSupported() {
        return mIs80211azNtbSupported;
    }

    /**
     * Gets the maximum supported channel width.
     *
     * @return The maximum supported {@link WifiAnnotations.ChannelWidth}.
     */
    @WifiAnnotations.ChannelWidth
    public int getMaxChannelWidth() {
        return mMaxChannelWidth;
    }

    /**
     * Gets the maximum supported preamble type.
     *
     * @return The maximum supported {@link WifiAnnotations.PreambleType}.
     */
    @WifiAnnotations.PreambleType
    public int getMaxPreamble() {
        return mMaxPreamble;
    }

    /**
     * Gets a set of supported discovery channel frequencies in Mhz.
     *
     * @return a set of supported discovery channel frequencies in Mhz.
     */
    @NonNull
    @IntRange(from = 2400, to = 7125)
    public Set<Integer> getSupportedDiscoveryChannelFrequenciesMhz() {
        return Collections.unmodifiableSet(new TreeSet<>(mSupportedDiscoveryChannelFrequenciesMhz));
    }

    /**
     * Gets the minimum ranging interval for 802.11mc based ranging.
     *
     * @return {@link Duration} The minimum ranging interval for 802.11mc.
     */
    @NonNull
    public Duration get80211mcMinRangingInterval() {
        return Duration.ofMillis(m80211mcMinRangingIntervalMillis);
    }

    /**
     * Gets the minimum ranging interval for 802.11az NTB based ranging.
     *
     * @return {@link Duration} The ranging interval for 802.11az NTB.
     */
    @NonNull
    public Duration get80211azNtbMinRangingInterval() {
        return Duration.ofMillis(m80211azNtbMinRangingIntervalMillis);
    }

    /**
     * A builder for creating {@link WifiPdRangingCapabilities}.
     *
     * @hide
     */
    public static final class Builder {
        private List<Integer> mSupportedPasnModes = new ArrayList<>();

        private MacAddress mProximityDetectionMacAddress;
        private boolean mIs80211mcSupported = false;
        private boolean mIs80211azNtbSupported = false;
        private int mMaxChannelWidth = 0;
        private int mMaxPreamble = 0;
        private List<Integer> mSupportedDiscoveryChannelFrequenciesMhz = new ArrayList<>();
        private int m80211mcMinRangingIntervalMillis = 0;
        private int m80211azNtbMinRangingIntervalMillis = 0;

        /**
         * Sets the supported PASN modes.
         *
         * @param supportedPasnModes a set of supported {@link PasnMode}.
         * @return the builder to facilitate chaining.
         */
        @NonNull
        public Builder setSupportedPasnModes(
                @NonNull @PasnMode Set<Integer> supportedPasnModes) {
            mSupportedPasnModes = new ArrayList<>(supportedPasnModes);
            return this;
        }

        /**
         * Sets the proximity detection MAC address.
         *
         * @param proximityDetectionMacAddress a byte array representing the MAC address.
         * @return the builder to facilitate chaining.
         */
        @NonNull
        public Builder setProximityDetectionMacAddress(
                @NonNull MacAddress proximityDetectionMacAddress) {
            Objects.requireNonNull(proximityDetectionMacAddress);
            mProximityDetectionMacAddress = proximityDetectionMacAddress;
            return this;
        }

        /**
         * Sets whether 802.11mc is supported.
         *
         * @param is80211mcSupported {@code true} if 802.11mc is supported, {@code false}
         *                           otherwise.
         * @return the builder to facilitate chaining.
         */
        @NonNull
        public Builder set80211mcSupported(boolean is80211mcSupported) {
            mIs80211mcSupported = is80211mcSupported;
            return this;
        }

        /**
         * Sets whether 802.11az NTB is supported.
         *
         * @param is80211azNtbSupported {@code true} if 802.11az NTB is supported, {@code false}
         *                              otherwise.
         * @return the builder to facilitate chaining.
         */
        @NonNull
        public Builder set80211azNtbSupported(boolean is80211azNtbSupported) {
            mIs80211azNtbSupported = is80211azNtbSupported;
            return this;
        }

        /**
         * Sets the maximum supported channel width.
         *
         * @param maxChannelWidth The maximum supported {@link WifiAnnotations.ChannelWidth}.
         * @return the builder to facilitate chaining.
         */
        @NonNull
        public Builder setMaxChannelWidth(
                @WifiAnnotations.ChannelWidth int maxChannelWidth) {
            mMaxChannelWidth = maxChannelWidth;
            return this;
        }

        /**
         * Sets the maximum supported preamble type.
         *
         * @param maxPreamble The maximum supported {@link WifiAnnotations.PreambleType}.
         * @return the builder to facilitate chaining.
         */
        @NonNull
        public Builder setMaxPreamble(
                @WifiAnnotations.PreambleType int maxPreamble) {
            mMaxPreamble = maxPreamble;
            return this;
        }

        /**
         * Sets the supported discovery channels.
         *
         * @param supportedDiscoveryChannels a set of supported discovery channels.
         * @return the builder to facilitate chaining.
         */
        @NonNull
        public Builder setSupportedDiscoveryChannelFrequenciesMhz(
                @IntRange(from = 2400, to = 7125) Set<Integer> supportedDiscoveryChannels) {
            mSupportedDiscoveryChannelFrequenciesMhz = new ArrayList<>(supportedDiscoveryChannels);
            return this;
        }

        /**
         * Sets the minimum ranging interval for 802.11mc.
         *
         * @param minRangingIntervalMillis The minimum ranging interval in milliseconds.
         * @return the builder to facilitate chaining.
         */
        @NonNull
        public Builder set80211mcMinRangingIntervalMillis(int minRangingIntervalMillis) {
            this.m80211mcMinRangingIntervalMillis = minRangingIntervalMillis;
            return this;
        }

        /**
         * Sets the ranging interval for 802.11az NTB.
         *
         * @param minRangingIntervalMillis The ranging interval in milliseconds.
         * @return the builder to facilitate chaining.
         */
        @NonNull
        public Builder set80211azNtbMinRangingIntervalMillis(int minRangingIntervalMillis) {
            this.m80211azNtbMinRangingIntervalMillis = minRangingIntervalMillis;
            return this;
        }

        /**
         * Builds a {@link WifiPdRangingCapabilities} instance.
         *
         * @return a new {@link WifiPdRangingCapabilities} instance.
         */
        @NonNull
        public WifiPdRangingCapabilities build() {
            Objects.requireNonNull(mProximityDetectionMacAddress);
            return new WifiPdRangingCapabilities(this);
        }
    }

    /**
     * @hide
     */
    @Override
    public String toString() {
        return "WifiPdRangingCapabilities{" +
                "mSupportedPasnModes=" + mSupportedPasnModes +
                ", mProximityDetectionMacAddress=" + mProximityDetectionMacAddress +
                ", mIs80211mcSupported=" + mIs80211mcSupported +
                ", mIs80211azNtbSupported=" + mIs80211azNtbSupported +
                ", mMaxChannelWidth=" + mMaxChannelWidth +
                ", mMaxPreamble=" + mMaxPreamble +
                ", mSupportedDiscoveryChannelFrequenciesMhz="
                + mSupportedDiscoveryChannelFrequenciesMhz
                +
                ", m80211mcMinRangingIntervalMillis=" + m80211mcMinRangingIntervalMillis +
                ", m80211azNtbMinRangingIntervalMillis=" + m80211azNtbMinRangingIntervalMillis +
                '}';
    }
}
