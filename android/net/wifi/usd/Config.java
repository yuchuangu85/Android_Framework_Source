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
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.wifi.aware.TlvBufferUtils;
import android.net.wifi.flags.Flags;
import android.net.wifi.util.Environment;
import android.os.Parcel;

import androidx.annotation.RequiresApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * USD configuration for publish and subscribe operation. This is the base class and not intended
 * to be created directly.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_USD)
public abstract class Config {
    /** @hide */
    public static final int MAX_NUM_OF_OPERATING_FREQUENCIES = 32;

    /**
     * Transmission type.
     *
     * @hide
     */
    @IntDef({TRANSMISSION_TYPE_UNICAST, TRANSMISSION_TYPE_MULTICAST})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TransmissionType {
    }

    /**
     * A unicast transmission sends data from one device to a single, specific destination device.
     */
    public static final int TRANSMISSION_TYPE_UNICAST = 0;

    /**
     * A multicast transmission sends data from one device to a group of devices on the network
     * simultaneously.
     */
    public static final int TRANSMISSION_TYPE_MULTICAST = 1;

    /**
     * Subscribe type.
     *
     * @hide
     */
    @IntDef({SUBSCRIBE_TYPE_PASSIVE, SUBSCRIBE_TYPE_ACTIVE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SubscribeType {
    }

    /**
     * Defines a passive subscribe session - a subscribe session where subscribe packets are not
     * transmitted over-the-air and the device listens and matches to received publish packets.
     */
    public static final int SUBSCRIBE_TYPE_PASSIVE = 0;

    /**
     * Defines an active subscribe session - a subscribe session where subscribe packets are
     * transmitted over-the-air.
     */
    public static final int SUBSCRIBE_TYPE_ACTIVE = 1;

    /**
     * Publish type.
     *
     * @hide
     */
    @IntDef({PUBLISH_TYPE_DEFAULT_SOLICITED_AND_UNSOLICITED,
            PUBLISH_TYPE_SOLICITED_AND_UNSOLICITED,
            PUBLISH_TYPE_UNSOLICITED,
            PUBLISH_TYPE_SOLICITED })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PublishType {
    }

    /**
     * Default publish type used internally.
     *
     * @hide
     */
    public static final int PUBLISH_TYPE_DEFAULT_SOLICITED_AND_UNSOLICITED = 0;

    /**
     * Defines a solicited and unsolicited publish session. In this mode, the device periodically
     * broadcasts "publish" packets to advertise its service. This allows passive subscribers,
     * which only listen for advertisements, to discover it.
     * And the device also listens for "subscribe" packets from active subscribers. When it
     * receives a matching service, it will respond directly to that subscriber, typically with a
     * unicast packet.
     * This is the default behavior for a publish session if no other type is specified.
     */
    @FlaggedApi(com.android.wifi.flags.Flags.FLAG_PROXIMITY_RANGING)
    public static final int PUBLISH_TYPE_SOLICITED_AND_UNSOLICITED =
            PUBLISH_TYPE_DEFAULT_SOLICITED_AND_UNSOLICITED;

    /**
     * Defines an unsolicited publish session - a publish session where the publisher is
     * advertising itself by broadcasting on-the-air.
     */
    @FlaggedApi(com.android.wifi.flags.Flags.FLAG_PROXIMITY_RANGING)
    public static final int PUBLISH_TYPE_UNSOLICITED = 1;

    /**
     * Defines a solicited publish session - a publish session which is silent, waiting for a
     * matching active subscribe session - and responding to it in unicast.
     */
    @FlaggedApi(com.android.wifi.flags.Flags.FLAG_PROXIMITY_RANGING)
    public static final int PUBLISH_TYPE_SOLICITED = 2;

    /**
     * Service Protocol Type.
     *
     * @hide
     */
    @IntDef({SERVICE_PROTO_TYPE_GENERIC, SERVICE_PROTO_TYPE_CSA_MATTER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ServiceProtoType {
    }

    /**
     * Generic type.
     */
    public static final int SERVICE_PROTO_TYPE_GENERIC = 0;

    /**
     * CSA (Connectivity Standards Alliance) Matter.
     * Note: CSA Matter is an open-source standard for smart home technology that allows devices to
     * work with any Matter-certified ecosystem.
     */
    public static final int SERVICE_PROTO_TYPE_CSA_MATTER = 1;

    /**
     * A special service name used to subscribe to any and all services that have enabled Proximity
     * Detection.
     * <p>
     * When a subscriber uses this service name, it will discover any publisher that has enabled
     * ranging via {@link PublishConfig.Builder#setProximityRangingEnabled(boolean)},
     * regardless of the publisher's actual service name.
     * <p>
     * This constant should be passed to the {@link SubscribeConfig.Builder} constructor to
     * initiate a discovery session for all nearby proximity-aware devices.
     *
     * <pre>{@code
     * SubscribeConfig config = new SubscribeConfig.Builder(Config.SERVICE_NAME_ANY)
     *     .setProximityRangingEnabled(true)
     *     .build();
     * }</pre>
     *
     * @see SubscribeConfig.Builder#Builder(String)
     */
    @FlaggedApi(com.android.wifi.flags.Flags.FLAG_PROXIMITY_RANGING)
    public static final String SERVICE_NAME_ANY =
            "android.net.wifi.usd.ANY_SERVICE";

    private final byte[] mServiceName;
    private final int mTtlSeconds;
    @ServiceProtoType
    private final int mServiceProtoType;
    private final byte[] mTxMatchFilterTlv;
    private final byte[] mRxMatchFilterTlv;
    private final byte[] mServiceSpecificInfo;
    private final int[] mOperatingFrequencies;
    private final boolean mEnableRanging;
    private final byte[] mSelfDevIk;
    private final List<byte[]> mPeerDevIks;

    /**
     * @hide
     */
    public Config(@NonNull byte[] serviceName, int ttlSeconds, int serviceProtoType,
            @Nullable byte[] txMatchFilterTlv, @Nullable byte[] rxMatchFilterTlv,
            @Nullable byte[] serviceSpecificInfo, @Nullable int[] operatingFrequencies,
            boolean enableRanging, @Nullable byte[] selfDevIk,
            @Nullable List<byte[]> peerDevIks) {
        mServiceName = serviceName;
        mTtlSeconds = ttlSeconds;
        mServiceProtoType = serviceProtoType;
        mTxMatchFilterTlv = txMatchFilterTlv;
        mRxMatchFilterTlv = rxMatchFilterTlv;
        mServiceSpecificInfo = serviceSpecificInfo;
        mOperatingFrequencies = operatingFrequencies;
        mEnableRanging = enableRanging;
        mSelfDevIk = selfDevIk;
        mPeerDevIks = peerDevIks;
    }

    /**
     * Gets the service name of the USD session.
     * <p>
     * The Service Name is a UTF-8 encoded string from 1 to 255 bytes in length.
     * The only acceptable single-byte UTF-8 symbols for a Service Name are alphanumeric
     * values (A-Z, a-z, 0-9), the hyphen ('-'), the period ('.') and the underscore ('_'). All
     * valid multi-byte UTF-8 characters are acceptable in a Service Name.
     *
     * @return service name
     */
    @NonNull
    public byte[] getServiceName() {
        return mServiceName;
    }

    /**
     * Gets the time interval (in seconds) a USD session will be alive. When the TTL is reached the
     * session will be terminated with an event.
     *
     * @return ttl value in seconds
     */
    @IntRange(from = 0)
    public int getTtlSeconds() {
        return mTtlSeconds;
    }

    /**
     * Get the Service protocol type for the USD session.
     *
     * @return service protocol type as defined in {@code SERVICE_PROTOCOL_TYPE_*}
     */
    @ServiceProtoType
    public int getServiceProtoType() {
        return mServiceProtoType;
    }

    /**
     * Gets the Tx filter which is an ordered sequence of (length, value) pairs to be included in
     * the USD discovery frame.
     *
     * @return tx match filter or empty list
     */
    @NonNull
    public List<byte[]> getTxMatchFilter() {
        return new TlvBufferUtils.TlvIterable(0, 1, mTxMatchFilterTlv).toList();
    }

    /**
     * @return tx match filter in TLV format
     * @hide
     */
    @Nullable
    public byte[] getTxMatchFilterTlv() {
        return mTxMatchFilterTlv;
    }

    /**
     * Gets the Rx match filter, which is an ordered sequence of (length, value) pairs that specify
     * further the response conditions beyond the service name used to filter subscribe messages.
     *
     * @return rx match filter or empty list
     */
    @NonNull
    public List<byte[]> getRxMatchFilter() {
        return new TlvBufferUtils.TlvIterable(0, 1, mRxMatchFilterTlv).toList();
    }

    /**
     * @return receive match filter in TLV format.
     * @hide
     */
    @Nullable
    public byte[] getRxMatchFilterTlv() {
        return mRxMatchFilterTlv;
    }

    /**
     * Get the service specific information set for the USD session.
     *
     * @return byte array or null
     */
    @Nullable
    public byte[] getServiceSpecificInfo() {
        return mServiceSpecificInfo;
    }

    /**
     * Get the frequencies where the USD session operates if overridden by {@code
     * setOperatingFrequenciesMhz(int[])}. If null, the application has not set the operating
     * frequencies using {@link PublishConfig.Builder#setOperatingFrequenciesMhz(int[])} for the
     * publisher or {@link SubscribeConfig.Builder#setOperatingFrequenciesMhz(int[])} for the
     * subscriber.
     *
     * <p>If the operating frequencies are not set the default behavior for the publisher and
     * subscriber is,
     * <ul>
     * <li>The publisher defaults to channel 6 (in the 2.4 GHz band) and a list of allowed channels
     * in the 2.4 GHz and 5 GHz bands for multichannel publishing. Publisher may prioritize the
     * channel with Access Points having best RSSI.
     * <li>The subscriber defaults to either channel 6 (in the 2.4 Ghz band) or Station channel or
     * pick a channel from
     * {@link SubscribeConfig.Builder#setRecommendedOperatingFrequenciesMhz(int[])} in given order
     * of preference.
     * </ul>
     *
     * @return an array of frequencies or null
     */
    @Nullable
    public int[] getOperatingFrequenciesMhz() {
        return mOperatingFrequencies;
    }

    /**
     * Returns whether ranging is enabled for this publish session.
     * See {@link PublishConfig.Builder#setProximityRangingEnabled(boolean)}.
     */
    @RequiresApi(37)
    @FlaggedApi(com.android.wifi.flags.Flags.FLAG_PROXIMITY_RANGING)
    public boolean isProximityRangingEnabled() {
        if (!Environment.isSdkNewerThanB()) {
            throw new UnsupportedOperationException();
        }
        return mEnableRanging;
    }

    /**
     * This Proximity Ranging device's identity key (devIK) required for authenticated PASN mode in
     * proximity ranging.
     * <p>
     * As per the specification, a device seeking proximity ranging with Authenticated mode PASN
     * security setup (section 4.2) shall use the configured Device Identity-Key (DevIK) as a long
     * term device identity to create a DIRA attribute (PR Device Identity Resolution attribute
     * section 3.2.8) and include it in the USD service discovery frames the device sends. When a
     * Device receives a DIRA from another P2P Device, it derives a set of Tag values based on the
     * cached DevIKs of all known peers for proximity ranging. If a derived Tag value matches the
     * Tag value in the received DIRA, the Device identifies the transmitter of the DIRA as a known
     * peer.
     *
     * @return The 16-byte device identity key, or {@code null} if not set.
     *
     */
    @RequiresApi(37)
    @FlaggedApi(com.android.wifi.flags.Flags.FLAG_PROXIMITY_RANGING)
    @Nullable
    public byte[] getSelfDeviceIdentityKey() {
        if (!Environment.isSdkNewerThanB()) {
            throw new UnsupportedOperationException();
        }
        return mSelfDevIk;
    }

    /**
     * List of peer device's device identity key
     * <p>
     * When the USD protocol engine receives the DIRA attribute, it goes through this list of
     * DevIKs and verify if it's a known peer. If it's a known peer, the devIk will be added in the
     * discovery result.
     *
     * @return a list of 16 byte device identity key array or empty list if not set.
     */
    @RequiresApi(37)
    @FlaggedApi(com.android.wifi.flags.Flags.FLAG_PROXIMITY_RANGING)
    @NonNull
    public List<byte[]> getPeerDeviceIdentityKeys() {
        if (!Environment.isSdkNewerThanB()) {
            throw new UnsupportedOperationException();
        }
        return mPeerDevIks == null ? java.util.Collections.emptyList() : mPeerDevIks;
    }

    /**
     * @return The list of peer device identity keys, which may be null.
     * @hide
     */
    @Nullable
    List<byte[]> getPeerDeviceIdentityKeysInternal() {
        return mPeerDevIks;
    }

    /** @hide */
    protected static void writePeerDevIksToParcel(@NonNull Parcel dest,
            @Nullable List<byte[]> peerDevIks) {
        if (peerDevIks == null) {
            dest.writeInt(-1); // Write -1 to signify a null list
            return;
        }
        dest.writeInt(peerDevIks.size());
        for (byte[] key : peerDevIks) {
            dest.writeByteArray(key);
        }
    }

    /** @hide */
    protected static List<byte[]> readPeerDevIksFromParcel(@NonNull Parcel in) {
        int size = in.readInt();
        if (size < 0) {
            return null;
        }
        List<byte[]> list = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(in.createByteArray());
        }
        return list;
    }

    @Override
    public String toString() {
        return "Config{" + "mServiceName=" + Arrays.toString(mServiceName) + ", mTtlSeconds="
                + mTtlSeconds + ", mServiceProtoType=" + mServiceProtoType + ", mTxMatchFilterTlv="
                + Arrays.toString(mTxMatchFilterTlv) + ", mRxMatchFilterTlv=" + Arrays.toString(
                mRxMatchFilterTlv) + ", mServiceSpecificInfo=" + Arrays.toString(
                mServiceSpecificInfo) + ", mOperatingFrequencies="
                + Arrays.toString(mOperatingFrequencies)
                + ", mEnableRanging=" + mEnableRanging
                + ", mSelfDevIk=" + Arrays.toString(mSelfDevIk)
                + ", mPeerDevIks=" + mPeerDevIks + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Config config)) return false;

        // Compare all primitive and single-array fields first
        if (mTtlSeconds != config.mTtlSeconds
                || mServiceProtoType != config.mServiceProtoType
                || mEnableRanging != config.mEnableRanging
                || !Arrays.equals(mServiceName, config.mServiceName)
                || !Arrays.equals(mTxMatchFilterTlv, config.mTxMatchFilterTlv)
                || !Arrays.equals(mRxMatchFilterTlv, config.mRxMatchFilterTlv)
                || !Arrays.equals(mServiceSpecificInfo, config.mServiceSpecificInfo)
                || !Arrays.equals(mOperatingFrequencies, config.mOperatingFrequencies)
                || !Arrays.equals(mSelfDevIk, config.mSelfDevIk)) {
            return false;
        }

        // Perform a deep comparison for the list of byte arrays
        if (mPeerDevIks == null && config.mPeerDevIks == null) {
            return true;
        }
        if (mPeerDevIks == null || config.mPeerDevIks == null
                || mPeerDevIks.size() != config.mPeerDevIks.size()) {
            return false;
        }
        for (int i = 0; i < mPeerDevIks.size(); i++) {
            if (!Arrays.equals(mPeerDevIks.get(i), config.mPeerDevIks.get(i))) {
                return false;
            }
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mTtlSeconds, mServiceProtoType, mEnableRanging);
        result = 31 * result + Arrays.hashCode(mServiceName);
        result = 31 * result + Arrays.hashCode(mTxMatchFilterTlv);
        result = 31 * result + Arrays.hashCode(mRxMatchFilterTlv);
        result = 31 * result + Arrays.hashCode(mServiceSpecificInfo);
        result = 31 * result + Arrays.hashCode(mOperatingFrequencies);
        result = 31 * result + Arrays.hashCode(mSelfDevIk);

        // Manually calculate a deep hash code for the List<byte[]>
        if (mPeerDevIks != null) {
            for (byte[] peerIk : mPeerDevIks) {
                result = 31 * result + Arrays.hashCode(peerIk);
            }
        }

        return result;
    }
}
