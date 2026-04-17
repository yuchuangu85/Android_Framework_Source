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

package android.net.wifi.usd;

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.aware.TlvBufferUtils;
import android.net.wifi.aware.WifiAwareUtils;
import android.net.wifi.flags.Flags;
import android.net.wifi.util.Environment;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.RequiresApi;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Defines the configuration of USD publish session
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_USD)
public final class PublishConfig extends Config implements Parcelable {

    @TransmissionType
    private final int mSolicitedTransmissionType;
    private final int mAnnouncementPeriodMillis;
    private final boolean mEnableEvents;
    @PublishType
    private final int mPublishType;

    private PublishConfig(Parcel in) {
        super(in.createByteArray(), in.readInt(), in.readInt(), in.createByteArray(),
                in.createByteArray(), in.createByteArray(), in.createIntArray(),
                Environment.isSdkNewerThanB() && in.readBoolean(),
                Environment.isSdkNewerThanB() ? in.createByteArray() : null,
                Environment.isSdkNewerThanB() ? readPeerDevIksFromParcel(in) : null);
        mSolicitedTransmissionType = in.readInt();
        mAnnouncementPeriodMillis = in.readInt();
        mEnableEvents = in.readBoolean();
        mPublishType = in.readInt();
    }

    @NonNull
    public static final Creator<PublishConfig> CREATOR = new Creator<PublishConfig>() {

        @Override
        public PublishConfig createFromParcel(Parcel in) {
            return new PublishConfig(in);
        }

        @Override
        public PublishConfig[] newArray(int size) {
            return new PublishConfig[size];
        }
    };

    private PublishConfig(Builder builder) {
        super(builder.mServiceName, builder.mTtlSeconds, builder.mServiceProtoType,
                builder.mTxMatchFilterTlv, builder.mRxMatchFilterTlv, builder.mServiceSpecificInfo,
                builder.mOperatingFrequencies, builder.mEnableRanging, builder.mSelfDevIk,
                builder.mPeerDevIks);
        mSolicitedTransmissionType = builder.mSolicitedTransmissionType;
        mAnnouncementPeriodMillis = builder.mAnnouncementPeriodMillis;
        mEnableEvents = builder.mEnableEvents;
        mPublishType = builder.mPublishType;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeByteArray(getServiceName());
        dest.writeInt(getTtlSeconds());
        dest.writeInt(getServiceProtoType());
        dest.writeByteArray(getTxMatchFilterTlv());
        dest.writeByteArray(getRxMatchFilterTlv());
        dest.writeByteArray(getServiceSpecificInfo());
        dest.writeIntArray(getOperatingFrequenciesMhz());
        if (Environment.isSdkNewerThanB()) {
            dest.writeBoolean(isProximityRangingEnabled());
            dest.writeByteArray(getSelfDeviceIdentityKey());
            writePeerDevIksToParcel(dest, getPeerDeviceIdentityKeysInternal());
        }
        dest.writeInt(mSolicitedTransmissionType);
        dest.writeInt(mAnnouncementPeriodMillis);
        dest.writeBoolean(mEnableEvents);
        dest.writeInt(mPublishType);
    }

    /**
     * @return whether a solicited transmission is an unicast or a multicast transmission
     */
    @TransmissionType
    public int getSolicitedTransmissionType() {
        return mSolicitedTransmissionType;
    }

    /**
     * @return announcement period in milliseconds, which is the Recommended periodicity of
     * unsolicited transmissions
     */
    @IntRange(from = 0)
    public int getAnnouncementPeriodMillis() {
        return mAnnouncementPeriodMillis;
    }

    /**
     * @return whether publish events are enabled or not. See
     * {@link Builder#setEventsEnabled(boolean)}.
     */
    public boolean isEventsEnabled() {
        return mEnableEvents;
    }

    /**
     * Returns the publish type for this session, which can be either
     * {@link #PUBLISH_TYPE_SOLICITED} or {@link #PUBLISH_TYPE_UNSOLICITED}.
     * See {@link Builder#setPublishType(int)}.
     */
    @RequiresApi(37)
    @FlaggedApi(com.android.wifi.flags.Flags.FLAG_PROXIMITY_RANGING)
    @PublishType
    public int getPublishType() {
        if (!Environment.isSdkNewerThanB()) {
            throw new UnsupportedOperationException();
        }
        return mPublishType;
    }

    @Override
    public String toString() {
        return super.toString() + " PublishConfig{" + "mSolicitedTransmissionType="
                + mSolicitedTransmissionType + ", mAnnouncementPeriodMillis="
                + mAnnouncementPeriodMillis + ", mEnableEvents=" + mEnableEvents
                + ", mPublishType=" + mPublishType + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PublishConfig that)) return false;
        if (!super.equals(o)) return false;
        return mSolicitedTransmissionType == that.mSolicitedTransmissionType
                && mAnnouncementPeriodMillis == that.mAnnouncementPeriodMillis
                && mEnableEvents == that.mEnableEvents
                && mPublishType == that.mPublishType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mSolicitedTransmissionType, mAnnouncementPeriodMillis,
                mEnableEvents, mPublishType);
    }

    /**
     * {@code PublishConfig} builder static inner class.
     */
    public static final class Builder {
        @TransmissionType
        private int mSolicitedTransmissionType = TRANSMISSION_TYPE_UNICAST;
        private int mAnnouncementPeriodMillis = 100;
        private boolean mEnableEvents = false;
        private final byte[] mServiceName;
        private int mTtlSeconds = 3000;
        @ServiceProtoType
        private int mServiceProtoType = SERVICE_PROTO_TYPE_GENERIC;
        private byte[] mTxMatchFilterTlv = null;
        private byte[] mRxMatchFilterTlv = null;
        private byte[] mServiceSpecificInfo = null;
        private int[] mOperatingFrequencies = null;
        private boolean mEnableRanging = false;
        @PublishType
        private int mPublishType = PUBLISH_TYPE_DEFAULT_SOLICITED_AND_UNSOLICITED;
        private byte[] mSelfDevIk = null;
        private List<byte[]> mPeerDevIks = null;

        /**
         * Builder for {@link PublishConfig}
         *
         * @param serviceName Specify the service name of the USD session. The Service Name is a
         *                    UTF-8 encoded string from 1 to
         *                    {@link Characteristics#getMaxServiceNameLength()} bytes in length.
         *                    The only acceptable single-byte UTF-8 symbols for a Service Name are
         *                    alphanumeric values (A-Z, a-z, 0-9), the hyphen ('-'), the period
         *                    ('.') and the underscore ('_'). All valid multi-byte UTF-8
         *                    characters are acceptable in a Service Name.
         */
        public Builder(@NonNull String serviceName) {
            Objects.requireNonNull(serviceName, "serviceName must not be null");
            mServiceName = serviceName.getBytes(StandardCharsets.UTF_8);
            WifiAwareUtils.validateServiceName(mServiceName);
        }

        /**
         * Sets the time to live for the USD session and returns a reference to this Builder
         * enabling method chaining. Default value is 3000 seconds.
         *
         * @param ttlSeconds Time to live in seconds. Value 0 indicating the session does not
         *                   terminate on its own.
         * @return a reference to this Builder
         */
        @NonNull
        public Builder setTtlSeconds(@IntRange(from = 0) int ttlSeconds) {
            if (ttlSeconds < 0) {
                throw new IllegalArgumentException("Invalid ttlSec - must be non-negative");
            }
            mTtlSeconds = ttlSeconds;
            return this;
        }

        /**
         * Sets the {@code serviceProtoType} and returns a reference to this Builder enabling method
         * chaining. Supported service protocol is defined as {@code SERVICE_PROTO_TYPE_*}. Default
         * value is {@link #SERVICE_PROTO_TYPE_GENERIC}.
         *
         * @param serviceProtoType the {@code serviceProtoType} to set
         * @return a reference to this Builder
         */
        @NonNull
        public Builder setServiceProtoType(@ServiceProtoType int serviceProtoType) {
            if (serviceProtoType < SERVICE_PROTO_TYPE_GENERIC
                    || serviceProtoType > SERVICE_PROTO_TYPE_CSA_MATTER) {
                throw new IllegalArgumentException("Invalid serviceProtoType - "
                        + serviceProtoType);
            }
            mServiceProtoType = serviceProtoType;
            return this;
        }

        /**
         * Sets the {@code txMatchFilter} and returns a reference to this Builder enabling method
         * chaining. The {@code txMatchFilter} is the ordered sequence of (length, value) pairs to
         * be included in the subscribe frame. If not set, empty by default.
         *
         * <p>See Wi-Fi Aware Specification Version 4.0, section: Appendix H (Informative) Matching
         * filter examples.
         *
         * @param txMatchFilter the {@code txMatchFilter} to set
         * @return a reference to this Builder
         */
        @NonNull
        public Builder setTxMatchFilter(@NonNull List<byte[]> txMatchFilter) {
            Objects.requireNonNull(txMatchFilter, "txMatchFilter must not be null");
            this.mTxMatchFilterTlv = new TlvBufferUtils.TlvConstructor(0, 1).allocateAndPut(
                    txMatchFilter).getArray();
            if (!TlvBufferUtils.isValid(mTxMatchFilterTlv, 0, 1)) {
                throw new IllegalArgumentException(
                        "Invalid txMatchFilter configuration - LV fields do not match up to "
                                + "length");
            }
            return this;
        }

        /**
         * Sets the {@code rxMatchFilter} and returns a reference to this Builder enabling method
         * chaining. The {@code rxMatchFilter} is the ordered sequence of (length, value) pairs
         * that specify further the matching conditions beyond the service name used to filter
         * the USD discovery messages. When a subscriber receives a publish message, it matches the
         * matching filter field in the publish message against its own matching_filter_rx. If not
         * set, empty by default.
         *
         * <p>See Wi-Fi Aware Specification Version 4.0, section: Appendix H (Informative) Matching
         * filter examples.
         *
         * @param rxMatchFilter the {@code rxMatchFilter} to set
         * @return a reference to this Builder
         */
        @NonNull
        public Builder setRxMatchFilter(@NonNull List<byte[]> rxMatchFilter) {
            Objects.requireNonNull(rxMatchFilter, "rxMatchFilter must not be null");
            this.mRxMatchFilterTlv = new TlvBufferUtils.TlvConstructor(0, 1).allocateAndPut(
                    rxMatchFilter).getArray();
            if (!TlvBufferUtils.isValid(mRxMatchFilterTlv, 0, 1)) {
                throw new IllegalArgumentException(
                        "Invalid rxMatchFilter configuration - LV fields do not match up to "
                                + "length");
            }
            return this;
        }

        /**
         * Sets the solicited transmission type and returns a reference to this Builder enabling
         * method chaining. The type determines whether the transmission is unicast or multicast.
         * Default is unicast.
         *
         * @param solicitedTransmissionType the transmission type {@code TRANSMISSION_TYPE_*} to
         *                                  set
         * @return a reference to this Builder
         */
        @NonNull
        public Builder setSolicitedTransmissionType(
                @TransmissionType int solicitedTransmissionType) {
            if (solicitedTransmissionType < TRANSMISSION_TYPE_UNICAST
                    || solicitedTransmissionType > TRANSMISSION_TYPE_MULTICAST) {
                throw new IllegalArgumentException("Invalid solicitedTransmissionType - "
                        + solicitedTransmissionType);
            }
            this.mSolicitedTransmissionType = solicitedTransmissionType;
            return this;
        }

        /**
         * Sets announcement period and returns a reference to this Builder enabling method
         * chaining. Announcement period is the recommended periodicity of unsolicited
         * transmissions. Default value is 100 ms.
         *
         * @param announcementPeriodMillis announcement period in milliseconds to set
         * @return a reference to this Builder
         */
        @NonNull
        public Builder setAnnouncementPeriodMillis(
                @IntRange(from = 0) int announcementPeriodMillis) {
            if (announcementPeriodMillis < 0) {
                throw new IllegalArgumentException(
                        "Invalid announcementPeriodMillis - must be non-negative");
            }
            this.mAnnouncementPeriodMillis = announcementPeriodMillis;
            return this;
        }

        /**
         * Enable or disable publish related events and returns a reference to this Builder
         * enabling method chaining. If enabled, publish replied events are generated on each
         * solicited transmission. By default, publish replied events are disabled.
         * See {@link PublishSessionCallback#onPublishReplied(DiscoveryResult)}.
         *
         * @param enableEvents the publish related events are enabled
         * @return a reference to this Builder
         */
        @NonNull
        public Builder setEventsEnabled(boolean enableEvents) {
            this.mEnableEvents = enableEvents;
            return this;
        }

        /**
         * Specify service specific information for the publish session. This is a free-form byte
         * array available to the application to send additional information as part of the
         * discovery operation - it will not be used to determine whether a publish/subscribe
         * match occurs. Default value is null;
         *
         * Note: Maximum length is limited by
         * {@link Characteristics#getMaxServiceSpecificInfoLength()}
         *
         * @param serviceSpecificInfo A byte-array for the service-specific
         *                            information field.
         * @return a reference to this Builder
         */
        @NonNull
        public Builder setServiceSpecificInfo(@NonNull byte[] serviceSpecificInfo) {
            Objects.requireNonNull(serviceSpecificInfo, "serviceSpecificInfo must not be null");
            mServiceSpecificInfo = serviceSpecificInfo.clone();
            return this;
        }

        /**
         * Sets the operating frequencies used for publish operation. This overrides the default
         * channel selection for publish. All frequencies have to be 20 Mhz channel in 2.4 Ghz or
         * 5 Ghz band per regulation in the geographical location. In {@code operatingFrequencies},
         * <ul>
         * <li>The first frequency is the channel used for single channel publish.
         * <li>Any additional frequencies enable multiple channel publish.
         * </ul>
         *
         * <p>If not set or an empty array is provided, the system defaults to 2437 MHz (channel 6
         * in the 2.4 GHz band) for single channel publish and a list of allowed channels in the 2.4
         * GHz and 5 GHz bands for multichannel publishing.
         *
         * <p>Note: the dwell time for the single and multi publish channels are defined in the
         * Wifi Aware Specification Version 4, section 4.5.1 Publisher behavior in USD.
         *
         * @param operatingFrequencies frequencies used for publish operation
         * @return a reference to this Builder
         * @throws IllegalArgumentException if frequencies are invalid or the number frequencies
         * are more than the number of 20 Mhz channels in 2.4 Ghz and 5 Ghz as per regulatory.
         */
        @NonNull
        public Builder setOperatingFrequenciesMhz(@NonNull int[] operatingFrequencies) {
            Objects.requireNonNull(operatingFrequencies, "operatingFrequencies must not be null");
            if ((operatingFrequencies.length > MAX_NUM_OF_OPERATING_FREQUENCIES)
                    || !WifiNetworkSpecifier.validateChannelFrequencyInMhz(operatingFrequencies)) {
                throw new IllegalArgumentException("Invalid operatingFrequencies");
            }
            mOperatingFrequencies = operatingFrequencies.clone();
            return this;
        }

        /**
         * Configures the publish session to be discoverable by peers seeking Proximity Ranging
         * service.
         * <p>
         * When this flag is enabled, this device will advertise its support for ranging, allowing
         * subscribers that have also enabled ranging to discover it.
         * <p>
         * This feature is only supported for solicited publish sessions
         * ({@link #PUBLISH_TYPE_SOLICITED}). This restriction exists because, according to the
         * Proximity Detection specification, the ranging device roles are mapped to USD roles
         * as follows:
         * The ranging Seeker device acts as an active USD Subscriber.
         * The ranging Advertiser device (this device) acts as a solicited USD Publisher.
         * <p>
         * The device must support proximity detection feature using USD discovery for this
         * feature to be used. The feature support is checked as described in
         * {@link Characteristics#isFindingProximityDetectionDevicesSupported()}
         * <p>
         * Optional. Disabled by default.
         *
         * @param enable {@code true} to advertise support for Proximity Ranging, {@code false}
         *               otherwise.
         * @return A reference to this Builder.
         */
        @RequiresApi(37)
        @FlaggedApi(com.android.wifi.flags.Flags.FLAG_PROXIMITY_RANGING)
        @NonNull
        public Builder setProximityRangingEnabled(boolean enable) {
            if (!Environment.isSdkNewerThanB()) {
                throw new UnsupportedOperationException();
            }
            mEnableRanging = enable;
            return this;
        }

        /**
         * This Proximity Ranging device's identity key (devIK) required for authenticated PASN
         * mode in proximity ranging.
         *
         * @param selfDevIk the device identity key of this device, which must be 16 bytes.
         * @return the builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        @RequiresApi(37)
        @FlaggedApi(com.android.wifi.flags.Flags.FLAG_PROXIMITY_RANGING)
        @NonNull
        public Builder setSelfDeviceIdentityKey(@NonNull byte[] selfDevIk) {
            if (!Environment.isSdkNewerThanB()) {
                throw new UnsupportedOperationException();
            }
            Objects.requireNonNull(selfDevIk, "Self Device Identity Key must not be null");
            if (selfDevIk.length != 16) {
                throw new IllegalArgumentException("Self Device Identity Key must"
                        + " be 16 bytes long.");
            }
            mSelfDevIk = selfDevIk;
            return this;
        }

        /**
         * Sets the list of peer device identity keys (DevIKs) for proximity detection.
         *
         * Each key must be a 16-byte array.
         *
         * @param peerDevIks A list of 16-byte device identity keys.
         * @return A reference to this Builder.
         * @throws IllegalArgumentException if any key in the list is not 16 bytes long.
         */
        @RequiresApi(37)
        @FlaggedApi(com.android.wifi.flags.Flags.FLAG_PROXIMITY_RANGING)
        @NonNull
        public Builder setPeerDeviceIdentityKeys(@NonNull List<byte[]> peerDevIks) {
            if (!Environment.isSdkNewerThanB()) {
                throw new UnsupportedOperationException();
            }
            Objects.requireNonNull(peerDevIks, "Peer device identity key list must not be null");
            // TODO Maximum number of peer device identity keys are limited by
            // {@link Characteristics#getMaxNumberOfPublishSessions()}
            for (byte[] key : peerDevIks) {
                if (key == null || key.length != 16) {
                    throw new IllegalArgumentException(
                            "All peer device identity keys must be 16 bytes long.");
                }
            }
            mPeerDevIks = peerDevIks;
            return this;
        }

        /**
         * Specify the type of the publish session: solicited (aka passive -
         * no publish packets are transmitted, a match is made against an active
         * subscribe session whose packets are transmitted over-the-air), or unsolicited
         * (aka active - publish packets are transmitted over-the-air) or both solicited and
         * unsolicited.
         * <p>
         * Optional. Default is solicited and unsolicited.
         *
         * @param publishType the publish type {@code PUBLISH_TYPE_*} to set
         *
         * @return a reference to this Builder
         */
        @RequiresApi(37)
        @FlaggedApi(com.android.wifi.flags.Flags.FLAG_PROXIMITY_RANGING)
        @NonNull
        public Builder setPublishType(@PublishType int publishType) {
            if (!Environment.isSdkNewerThanB()) {
                throw new UnsupportedOperationException();
            }
            if (publishType < PUBLISH_TYPE_SOLICITED_AND_UNSOLICITED
                    || publishType > PUBLISH_TYPE_SOLICITED) {
                throw new IllegalArgumentException("Invalid publishType - " + publishType);
            }
            mPublishType = publishType;
            return this;
        }

        /**
         * Returns a {@code PublishConfig} built from the parameters previously set.
         *
         * @return a {@code PublishConfig} built with parameters of this {@code PublishConfig
         * .Builder}
         */
        @NonNull
        public PublishConfig build() {
            if (Environment.isSdkNewerThanB()) {
                if (mEnableRanging && mPublishType != PUBLISH_TYPE_SOLICITED) {
                    throw new IllegalArgumentException(
                            "Proximity ranging is only supported for solicited publish sessions");
                }
            }
            return new PublishConfig(this);
        }
    }
}
