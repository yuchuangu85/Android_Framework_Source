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

package android.net.wifi.rtt;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresApi;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.wifi.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Proximity Detection configuration.
 * <p>
 * The Proximity Detection configuration assists the devices to run the proximity ranging
 * protocol to find the distance between peer-to-peer devices.
 * <p>
 * The Discovery stage uses USD or OOB discovery, which may be used to initiate
 * the exchange of USD equivalent information carrying the service discovery
 * information, Wi-Fi ranging parameters and security information.During the
 * Discovery stage each device takes a pre-defined Seeker or Advertiser role.
 *
 * Once the Discovery stage is completed, the two devices execute the Security
 * stage that is PASN (Pre-Association Security Negotiation) based and is
 * piggybacked with additional information for negotiation including Ranging
 * Channel and FTM Role negotiation.
 * The PASN protocol sets Security Association facilitating key generation to
 * allow protection of the FTM procedure which occurs in subsequent stages.
 * The negotiation for ranging channel allows the STAs to range using a channel
 * which satisfies the bandwidth, regulatory and capability needs as well as
 * other considerations. The PASN protocol is always initiated by the Active
 * Seeker. If USD was used to perform service discovery, the Active Seeker and
 * Advertiser perform the PASN negotiation over the USD discovery channel.
 * If service discovery was executed over OOB, the Active
 * Seeker and Advertiser exchange the Wi-Fi discovery channel over OOB channel
 * and transition to Wi-Fi channel after discovery for running PASN negotiation.
 * The service layer should make sure that Ranging Advertiser is present on the
 * Wi-Fi discovery channel to respond to Ranging Seeker PASN M1 frame.
 *
 * In the last stage of the P2P Proximity Ranging protocol, the STAs negotiate
 * and perform measurement exchange to measure range in a protected and secure
 * manner, based on the service requirements and security association
 * established during the previous stages
 *
 * @hide
 */
@SystemApi
@RequiresApi(37)
@FlaggedApi(Flags.FLAG_PROXIMITY_RANGING)
public final class ProximityDetectionConfig implements Parcelable {
    /**
     * The default interval for continuous ranging, in milliseconds.
     */
    private static final int DEFAULT_CONTINUOUS_RANGING_INTERVAL_MS = 1000;
    /**
     *  The device which is looking for ranging service
     */
    public static final int RANGING_SERVICE_ROLE_SEEKER = 1;
    /**
     * The device which is advertising ranging service
     */
    public static final int RANGING_SERVICE_ROLE_ADVERTISER = 2;
    /**
     * Ranging Service role
     * @hide
     */
    @IntDef(prefix = {"RANGING_SERVICE_ROLE_"}, value = {
            RANGING_SERVICE_ROLE_SEEKER,
            RANGING_SERVICE_ROLE_ADVERTISER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RangingServiceRole {
    }

    /**
     *  The device prefer to take Ranging role as ISTA
     */
    public static final int RANGING_MEASUREMENT_ROLE_ISTA = 1;
    /**
     * The device prefer to take Ranging role as RSTA
     */
    public static final int RANGING_MEASUREMENT_ROLE_RSTA = 2;
    /**
     * Ranging measurement role
     * @hide
     */
    @IntDef(prefix = {"RANGING_MEASUREMENT_ROLE"}, value = {
            RANGING_MEASUREMENT_ROLE_ISTA,
            RANGING_MEASUREMENT_ROLE_RSTA})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RangingMeasurementRole {
    }

    @RangingServiceRole
    private final int mRangingServiceRole;
    private final int mDiscoveryChannelFrequencyMhz;
    private final int mPreferredRangingChannelFrequencyMhz;
    private final boolean mIsAdvertiserRequireRangeReport;
    @RangingMeasurementRole
    private final int mPreferredRangingMeasurementRole;
    private final int mContinuousRangingIntervalMs;
    private final boolean mEgressDistanceMmSet;
    private final int mEgressDistanceMm;
    private final boolean mIngressDistanceMmSet;
    private final int mIngressDistanceMm;

    private ProximityDetectionConfig(
            @RangingServiceRole int rangingServiceRole,
            int discoveryChannelFrequencyMhz,
            int preferredRangingChannelFrequencyMhz,
            boolean isAdvertiserRequireRangeReport,
            @RangingMeasurementRole int preferredRangingMeasurementRole,
            int continuousRangingIntervalMs,
            boolean egressDistanceMmSet,
            int egressDistanceMm,
            boolean ingressDistanceMmSet,
            int ingressDistanceMm) {
        mRangingServiceRole = rangingServiceRole;
        mDiscoveryChannelFrequencyMhz = discoveryChannelFrequencyMhz;
        mPreferredRangingChannelFrequencyMhz = preferredRangingChannelFrequencyMhz;
        mIsAdvertiserRequireRangeReport = isAdvertiserRequireRangeReport;
        mPreferredRangingMeasurementRole = preferredRangingMeasurementRole;
        mContinuousRangingIntervalMs = continuousRangingIntervalMs;
        mEgressDistanceMmSet = egressDistanceMmSet;
        mEgressDistanceMm = egressDistanceMm;
        mIngressDistanceMmSet = ingressDistanceMmSet;
        mIngressDistanceMm = ingressDistanceMm;
    }

    /**
     * Get the ranging service role.
     * See {@link Builder#Builder(int)}.
     */
    @RangingServiceRole
    public int getRangingServiceRole() {
        return mRangingServiceRole;
    }

    /**
     * Get the discovery channel frequency in MHz.
     * See {@link Builder#setDiscoveryChannelFrequencyMhz(int)}.
     */
    public int getDiscoveryChannelFrequencyMhz() {
        return mDiscoveryChannelFrequencyMhz;
    }

    /**
     * Get the preferred ranging channel frequency in MHz.
     * See {@link Builder#setPreferredRangingChannelFrequencyMhz(int)}.
     */
    public int getPreferredRangingChannelFrequencyMhz() {
        return mPreferredRangingChannelFrequencyMhz;
    }

    /**
     * Returns whether the advertiser requires a range result.
     * See {@link Builder#setAdvertiserRequireRangeResult(boolean)}.
     */
    public boolean isAdvertiserRequireRangeResult() {
        return mIsAdvertiserRequireRangeReport;
    }

    /**
     * Get the preferred ranging measurement role.
     * See {@link Builder#setPreferredRangingMeasurementRole(int)}.
     */
    @RangingMeasurementRole
    public int getPreferredRangingMeasurementRole() {
        return mPreferredRangingMeasurementRole;
    }

    /**
     * Get the default ranging interval set in case of continuous ranging.
     * See {@link Builder#setContinuousRangingIntervalMillis(int)}.
     */
    public int getContinuousRangingIntervalMillis() {
        return mContinuousRangingIntervalMs;
    }

    /**
     * Get the ingress distance in mm.
     * See {@link Builder#setIngressDistanceMm(int)}.
     *
     * @return The ingress distance in mm.
     * @throws IllegalStateException if the value was not set.
     */
    public int getIngressDistanceMm() {
        if (!mIngressDistanceMmSet) {
            throw new IllegalStateException("Ingress distance was not set.");
        }
        return mIngressDistanceMm;
    }

    /**
     * Get the egress distance in mm.
     * See {@link Builder#setEgressDistanceMm(int)}.
     *
     * @return The egress distance in mm.
     * @throws IllegalStateException if the value was not set.
     */
    public int getEgressDistanceMm() {
        if (!mEgressDistanceMmSet) {
            throw new IllegalStateException("Egress distance was not set.");
        }
        return mEgressDistanceMm;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mRangingServiceRole);
        dest.writeInt(mDiscoveryChannelFrequencyMhz);
        dest.writeInt(mPreferredRangingChannelFrequencyMhz);
        dest.writeBoolean(mIsAdvertiserRequireRangeReport);
        dest.writeInt(mPreferredRangingMeasurementRole);
        dest.writeInt(mContinuousRangingIntervalMs);
        dest.writeBoolean(mEgressDistanceMmSet);
        dest.writeInt(mEgressDistanceMm);
        dest.writeBoolean(mIngressDistanceMmSet);
        dest.writeInt(mIngressDistanceMm);
    }

    public static final @NonNull Creator<ProximityDetectionConfig> CREATOR =
            new Creator<ProximityDetectionConfig>() {
                @Override
                public ProximityDetectionConfig createFromParcel(Parcel in) {
                    return new ProximityDetectionConfig(in);
                }

                @Override
                public ProximityDetectionConfig[] newArray(int size) {
                    return new ProximityDetectionConfig[size];
                }
            };

    private ProximityDetectionConfig(@NonNull Parcel in) {
        mRangingServiceRole = in.readInt();
        mDiscoveryChannelFrequencyMhz = in.readInt();
        mPreferredRangingChannelFrequencyMhz = in.readInt();
        mIsAdvertiserRequireRangeReport = in.readBoolean();
        mPreferredRangingMeasurementRole = in.readInt();
        mContinuousRangingIntervalMs = in.readInt();
        mEgressDistanceMmSet = in.readBoolean();
        mEgressDistanceMm = in.readInt();
        mIngressDistanceMmSet = in.readBoolean();
        mIngressDistanceMm = in.readInt();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProximityDetectionConfig)) {
            return false;
        }
        ProximityDetectionConfig that = (ProximityDetectionConfig) o;
        return mRangingServiceRole == that.mRangingServiceRole
                && mDiscoveryChannelFrequencyMhz == that.mDiscoveryChannelFrequencyMhz
                && mPreferredRangingChannelFrequencyMhz == that.mPreferredRangingChannelFrequencyMhz
                && mIsAdvertiserRequireRangeReport == that.mIsAdvertiserRequireRangeReport
                && mPreferredRangingMeasurementRole == that.mPreferredRangingMeasurementRole
                && mContinuousRangingIntervalMs == that.mContinuousRangingIntervalMs
                && mEgressDistanceMmSet == that.mEgressDistanceMmSet
                && mEgressDistanceMm == that.mEgressDistanceMm
                && mIngressDistanceMmSet == that.mIngressDistanceMmSet
                && mIngressDistanceMm == that.mIngressDistanceMm;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRangingServiceRole, mDiscoveryChannelFrequencyMhz,
                mPreferredRangingChannelFrequencyMhz, mIsAdvertiserRequireRangeReport,
                mPreferredRangingMeasurementRole, mContinuousRangingIntervalMs,
                mEgressDistanceMmSet, mEgressDistanceMm, mIngressDistanceMmSet, mIngressDistanceMm);
    }

    @Override
    public String toString() {
        return "ProximityDetectionConfig{"
                + "mRangingServiceRole=" + mRangingServiceRole
                + ", mDiscoveryChannelFrequencyMhz=" + mDiscoveryChannelFrequencyMhz
                + ", mPreferredRangingChannelFrequencyMhz=" + mPreferredRangingChannelFrequencyMhz
                + ", mIsAdvertiserRequireRangeReport=" + mIsAdvertiserRequireRangeReport
                + ", mPreferredRangingMeasurementRole=" + mPreferredRangingMeasurementRole
                + ", mContinuousRangingIntervalMs=" + mContinuousRangingIntervalMs
                + ", mEgressDistanceMmSet=" + mEgressDistanceMmSet
                + ", mEgressDistanceMm=" + mEgressDistanceMm
                + ", mIngressDistanceMmSet=" + mIngressDistanceMmSet
                + ", mIngressDistanceMm=" + mIngressDistanceMm
                + '}';
    }

    /**
     * Builder for {@link ProximityDetectionConfig}
     */
    public static final class Builder {
        private final int mRangingServiceRole;
        private int mDiscoveryChannelFrequencyMhz = 0;
        private int mPreferredRangingChannelFrequencyMhz = 0;
        private boolean mIsAdvertiserRequireRangeReport = false;
        private int mPreferredRangingMeasurementRole = 0;
        private int mContinuousRangingIntervalMs = DEFAULT_CONTINUOUS_RANGING_INTERVAL_MS;
        private boolean mEgressDistanceMmSet = false;
        private int mEgressDistanceMm = 0;
        private boolean mIngressDistanceMmSet = false;
        private int mIngressDistanceMm = 0;


        /**
         * Builder
         *
         * @param serviceRole The ranging service role. The device which receives
         *     {@link WifiRttManager#startContinuousRanging} with ranging service role as
         *     RANGING_SERVICE_ROLE_ADVERTISER goes to the discovery channel and wait for the
         *     security negotiation frame (PASN M1 frame).
         *     The device which receives {@link WifiRttManager#startContinuousRanging} with ranging
         *     service role as RANGING_SERVICE_ROLE_SEEKER goes to the discovery channel
         *     start the security negotiation by sending PASN M1 frame.
         */
        public Builder(@RangingServiceRole int serviceRole) {
            mRangingServiceRole = serviceRole;
        }

        /**
         * Sets the discovery channel on which security negotiation
         * runs.
         * Note: If USD was used to perform service discovery,
         * this field is optional. Active Seeker and Advertiser
         * perform the PASN negotiation over the USD discovery
         * channel.
         * If OOB medium (BLE, cloud, etc) was used to perform
         * service discovery, this field is mandatory.
         *
         * @param frequencyMhz The channel frequency on which security
         *     negotiation is conducted.
         * @return a reference to this Builder
         */
        @NonNull
        public Builder setDiscoveryChannelFrequencyMhz(int frequencyMhz) {
            mDiscoveryChannelFrequencyMhz = frequencyMhz;
            return this;
        }

        /**
         * This field is optional.
         * Sets the preferred ranging channel for measurement exchange.
         * Note: The lower layer may use this information to derive the ranging
         * channel
         *
         * @param frequencyMhz The preferred ranging channel frequency.
         * @return a reference to this Builder
         */
        @NonNull
        public Builder setPreferredRangingChannelFrequencyMhz(int frequencyMhz) {
            mPreferredRangingChannelFrequencyMhz = frequencyMhz;
            return this;
        }

        /**
         * Specify that the advertiser require range report after each measurement.
         * Optional. false by default. The default configuration is to drop reporting the
         * range result to avoid unnecessary wake ups.
         * @param enabled true to receive range result after
         *     measurement.
         * @return a reference to this Builder
         */
        @NonNull
        public Builder setAdvertiserRequireRangeResult(boolean enabled) {
            mIsAdvertiserRequireRangeReport = enabled;
            return this;
        }

        /**
         * Sets the measurement role
         * Optional. By default seeker assigns the ISTA role and advertiser takes the
         * RSTA role during ranging role negotiation.
         *
         * @param measurementRole FTM initiating STA or responding STA
         * @return a reference to this Builder
         */
        @NonNull
        public Builder setPreferredRangingMeasurementRole(
                @RangingMeasurementRole int measurementRole) {
            mPreferredRangingMeasurementRole = measurementRole;
            return this;
        }

        /**
         * Sets the desired ranging interval for a continuous Ranging session.
         * The system uses this value as a hint for the desired ranging
         * interval, but the actual value will be negotiated with the peer
         * device and may be adjusted to ensure system stability.
         * The number of peers in a single request and the chip's capabilities
         * influence the final interval decision.
         * <p>
         * The platform will enforce a minimum interval to prevent excessive
         * resource consumption.
         * <p>
         * The application can look at the chip supported minimum ranging interval through
         * {@link WifiRttManager#getProximityDetectionCharacteristics()}
         * @param rangingIntervalMs The desired ranging interval in milliseconds.
         *
         * @return This {@link Builder} object.
         */
        @NonNull
        public Builder setContinuousRangingIntervalMillis(int rangingIntervalMs) {
            mContinuousRangingIntervalMs = rangingIntervalMs;
            return this;
        }

        /**
         * An ingress distance is configured to detect when the device enters a defined range. A
         * discovery result with range will be reported when the device moves into the range of
         * the ingress distance (inner threshold) to a matching publisher (based on the other
         * matching criteria in this configuration). This can be used in conjunction with
         * {@link #setEgressDistanceMm(int)} to specify a geofence.
         * <p>
         * When both ingress (inner threshold) and egress (outer threshold) distances are set
         * for geofence, the ranging result will be reported when the device moves either into
         * the range of the inner threshold or out of the range of the outer threshold.
         *
         * @param ingressDistanceMm Ingress distance, in mm, to the publisher below which to trigger
         *                      discovery.
         *
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setIngressDistanceMm(int ingressDistanceMm) {
            mIngressDistanceMm = ingressDistanceMm;
            mIngressDistanceMmSet = true;
            return this;
        }

        /**
         * An egress distance is configured to detect when the device exits a defined range. A
         * discovery result with range will be reported when the device moves out of the range of
         * the egress distance (outer threshold) to a matching publisher (based on the other
         * matching criteria in this configuration). This can be used in conjunction with
         * {@link #setIngressDistanceMm(int)} to specify a geofence.
         * <p>
         * When both ingress (inner threshold) and egress (outer threshold) distances are set
         * for geofence, the ranging result will be reported when the device moves either into
         * the range of the inner threshold or out of the range of the outer threshold.
         *
         * @param egressDistanceMm Egress distance, in mm, to the publisher above which to trigger
         *                      discovery.
         *
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        @NonNull
        public Builder setEgressDistanceMm(int egressDistanceMm) {
            mEgressDistanceMm = egressDistanceMm;
            mEgressDistanceMmSet = true;
            return this;
        }

        /**
         * Build the {@link ProximityDetectionConfig} object.
         *
         * @return a new {@link ProximityDetectionConfig} object.
         */
        @NonNull
        public ProximityDetectionConfig build() {
            return new ProximityDetectionConfig(
                    mRangingServiceRole,
                    mDiscoveryChannelFrequencyMhz,
                    mPreferredRangingChannelFrequencyMhz,
                    mIsAdvertiserRequireRangeReport,
                    mPreferredRangingMeasurementRole,
                    mContinuousRangingIntervalMs,
                    mEgressDistanceMmSet,
                    mEgressDistanceMm,
                    mIngressDistanceMmSet,
                    mIngressDistanceMm);
        }
    }
}
