/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.telephony.data;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A traffic descriptor, as defined in 3GPP TS 24.526 Section 5.2. It is used for UE Route Selection
 * Policy(URSP) traffic matching as described in 3GPP TS 24.526 Section 4.2.2. It includes
 * optional components like Data Network Name (DNN), an OS/App ID, and/or Connection Capabilities,
 * which, if present, must be used for traffic matching. This descriptor does not specify the
 * end point to be used for the data call.
 */
public final class TrafficDescriptor implements Parcelable {

    /**
     * @hide
     * Constants defining the connection capabilities of the traffic.
     * These are defined in 3GPP TS 124.526 table 5.2.1.
     *   Bits
     *   8 7 6 5 4 3 2 1
     *   0 0 0 0 0 0 0 1 IMS
     *   0 0 0 0 0 0 1 0 MMS
     *   0 0 0 0 0 1 0 0 SUPL
     *   0 0 0 0 1 0 0 0 Internet
     *   0 0 0 1 0 0 0 0 LCS user plane positioning
     *   1 0 1 0 0 0 0 1 IoT delay-tolerant
     *   1 0 1 0 0 0 1 0 IoT non-delay-tolerant
     *   1 0 1 0 0 0 1 1 Downlink streaming
     *   1 0 1 0 0 1 0 0 Uplink streaming
     *   1 0 1 0 0 1 0 1 Vehicular communications
     *   1 0 1 0 0 1 1 0 Real time interactive
     *   1 0 1 0 0 1 1 1 Unified communications
     *   1 0 1 0 1 0 0 0 Background
     *   1 0 1 0 1 0 0 1 Mission critical communications
     *   1 0 1 0 1 0 1 0 Time critical communications
     *   1 0 1 0 1 0 1 1 Low latency loss tolerant communications in unacknowledged mode
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "CONNECTION_CAPABILITY_" }, value = {
            CONNECTION_CAPABILITY_UNKNOWN,
            CONNECTION_CAPABILITY_IMS,
            CONNECTION_CAPABILITY_MMS,
            CONNECTION_CAPABILITY_SUPL,
            CONNECTION_CAPABILITY_INTERNET,
            CONNECTION_CAPABILITY_LCS_USER_PLANE_POSITIONING,
            CONNECTION_CAPABILITY_IOT_DELAY_TOLERANT,
            CONNECTION_CAPABILITY_IOT_NON_DELAY_TOLERANT,
            CONNECTION_CAPABILITY_DOWNLINK_STREAMING,
            CONNECTION_CAPABILITY_UPLINK_STREAMING,
            CONNECTION_CAPABILITY_VEHICULAR_COMMUNICATIONS,
            CONNECTION_CAPABILITY_REAL_TIME_INTERACTIVE,
            CONNECTION_CAPABILITY_UNIFIED_COMMUNICATIONS,
            CONNECTION_CAPABILITY_BACKGROUND,
            CONNECTION_CAPABILITY_MISSION_CRITICAL_COMMUNICATIONS,
            CONNECTION_CAPABILITY_TIME_CRITICAL_COMMUNICATIONS,
            CONNECTION_CAPABILITY_LOW_LATENCY_LOSS_TOLERANT_UNACK
    })
    public @interface ConnectionCapability {}

    /** Unknown connection capability. */
    @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public static final int CONNECTION_CAPABILITY_UNKNOWN = 0b00000000;

    /**
     * IMS Voice + Video comprising voice, video telephony and multimedia communications over IP
     * networks. Voice, Video and SMS over IMS DNN, as well as RCS (Rich Communication Services)
     * are included in this traffic category.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public static final int CONNECTION_CAPABILITY_IMS = 0b00000001;

    /** MMS (Multimedia Messaging Service) */
    @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public static final int CONNECTION_CAPABILITY_MMS = 0b00000010;

    /** SUPL (Secure User Plane Location) */
    @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public static final int CONNECTION_CAPABILITY_SUPL = 0b00000100;

    /**
     * Internet data traffic with wide availability but no critical requirements on latency or
     * data rates.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public static final int CONNECTION_CAPABILITY_INTERNET = 0b00001000;

    /** LCS user plane positioning */
    @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public static final int CONNECTION_CAPABILITY_LCS_USER_PLANE_POSITIONING = 0b00010000;

    /** Delay-tolerant, low sustained data rate IoT traffic. */
    @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public static final int CONNECTION_CAPABILITY_IOT_DELAY_TOLERANT = 0b10100001;

    /** Non-delay-tolerant, low sustained data rate IoT traffic */
    @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public static final int CONNECTION_CAPABILITY_IOT_NON_DELAY_TOLERANT = 0b10100010;

    /** Downlink streaming, characterized as downlink high data rates content and low latency. */
    @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public static final int CONNECTION_CAPABILITY_DOWNLINK_STREAMING = 0b10100011;

    /** Uplink streaming, characterized as uplink high data rates content and low latency */
    @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public static final int CONNECTION_CAPABILITY_UPLINK_STREAMING = 0b10100100;

    /**
     * Vehicle-to-Everything (V2X) traffic comprising V2X messages,
     * characterized by low latency, high reliability, and high availability.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public static final int CONNECTION_CAPABILITY_VEHICULAR_COMMUNICATIONS = 0b10100101;

    /** Real time interactive traffic, for example, for gaming or AR/VR. */
    @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public static final int CONNECTION_CAPABILITY_REAL_TIME_INTERACTIVE = 0b10100110;

    /**
     * Unified communications traffic, which comprise communications through a single user
     * interface at the UE, for instance instant messaging, VoIP, and video collaboration through
     * the same application.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public static final int CONNECTION_CAPABILITY_UNIFIED_COMMUNICATIONS = 0b10100111;

    /**
     * Any traffic that is not time-sensitive, e.g., firmware/software updates over the air. This
     * traffic has no critical requirements from latency or data rates perspective. This traffic
     * should/can be subject of scheduling (e.g., at specific time of day) by the
     * applications/networks.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public static final int CONNECTION_CAPABILITY_BACKGROUND = 0b10101000;

    /**
     * Mission-critical communications, may include MC-PTT, MC
     * video, and MC data.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public static final int CONNECTION_CAPABILITY_MISSION_CRITICAL_COMMUNICATIONS = 0b10101001;

    /**
     * Time Critical Communications, with bounded, low to very low
     * latency requirements, and high availability.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public static final int CONNECTION_CAPABILITY_TIME_CRITICAL_COMMUNICATIONS = 0b10101010;

    /**
     * Traffic which has low latency requirements and is tolerant to some loss, hence using
     * un-acknowledged mode at the Radio Link Control (RLC) layer. E.g., for certain real time
     * voice or video traffic.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public static final int CONNECTION_CAPABILITY_LOW_LATENCY_LOSS_TOLERANT_UNACK = 0b10101011;

    /**
     * The OS/App id
     *
     * @hide
     */
    public static final class OsAppId {
        /**
         * OSId for "Android", using UUID version 5 with namespace ISO OSI.
         * Prepended to the OsAppId in TrafficDescriptor to use for URSP matching.
         */
        public static final UUID ANDROID_OS_ID =
                UUID.fromString("97a498e3-fc92-5c94-8986-0333d06e4e47");

        /**
         * Allowed app ids.
         */
        // The following app ids are the only apps id Android supports. OEMs or vendors are
        // prohibited to modify/extend the allowed list, especially passing the real package name to
        // the network.
        private static final Set<String> ALLOWED_APP_IDS = Set.of(
                "ENTERPRISE", "PRIORITIZE_LATENCY", "PRIORITIZE_BANDWIDTH", "CBS",
                "PRIORITIZE_UNIFIED_COMMUNICATIONS"
        );

        /** OS id in UUID format. */
        private final @NonNull UUID mOsId;

        /**
         * App id in string format. Note that Android will not allow use specific app id. This must
         * be a category/capability identifier.
         */
        private final @NonNull String mAppId;

        /**
         * The differentiator when multiple traffic descriptor has the same OS and app id. Must be
         * greater than 1.
         */
        private final int mDifferentiator;

        /**
         * Constructor
         *
         * @param osId OS id in UUID format.
         * @param appId App id in string format. Note that Android will not allow use specific app
         * id. This must be a category/capability identifier.
         */
        public OsAppId(@NonNull UUID osId, @NonNull String appId) {
            this(osId, appId, 1);
        }

        /**
         * Constructor
         *
         * @param osId OS id in UUID format.
         * @param appId App id in string format. Note that Android will not allow use specific app
         * id. This must be a category/capability identifier.
         * @param differentiator The differentiator when multiple traffic descriptor has the same
         * OS and app id. Must be greater than 0.
         */
        public OsAppId(@NonNull UUID osId, @NonNull String appId, int differentiator) {
            Objects.requireNonNull(osId);
            Objects.requireNonNull(appId);
            if (differentiator < 1) {
                throw new IllegalArgumentException("Invalid differentiator " + differentiator);
            }

            mOsId = osId;
            mAppId = appId;
            mDifferentiator = differentiator;
        }

        /**
         * Constructor from raw byte array.
         *
         * @param rawOsAppId The raw OS/App id.
         */
        public OsAppId(@NonNull byte[] rawOsAppId) {
            try {
                ByteBuffer bb = ByteBuffer.wrap(rawOsAppId);
                // OS id is the first 16 bytes.
                mOsId = new UUID(bb.getLong(), bb.getLong());
                // App id length is 1 byte.
                int appIdLen = bb.get();
                // The remaining is the app id + differentiator.
                byte[] appIdAndDifferentiator = new byte[appIdLen];
                bb.get(appIdAndDifferentiator, 0, appIdLen);
                // Extract trailing numbers, for example, "ENTERPRISE", "ENTERPRISE3".
                String appIdAndDifferentiatorStr = new String(appIdAndDifferentiator);
                Pattern pattern = Pattern.compile("[^0-9]+([0-9]+)$");
                Matcher matcher = pattern.matcher(new String(appIdAndDifferentiator));
                if (matcher.find()) {
                    mDifferentiator = Integer.parseInt(matcher.group(1));
                    mAppId = appIdAndDifferentiatorStr.replace(matcher.group(1), "");
                } else {
                    mDifferentiator = 1;
                    mAppId = appIdAndDifferentiatorStr;
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to decode " + (rawOsAppId != null
                        ? new BigInteger(1, rawOsAppId).toString(16) : null));
            }
        }

        /**
         * @return The OS id in UUID format.
         */
        public @NonNull UUID getOsId() {
            return mOsId;
        }

        /**
         * @return App id in string format. Note that Android will not allow use specific app id.
         * This must be a category/capability identifier.
         */
        public @NonNull String getAppId() {
            return mAppId;
        }

        /**
         * @return The differentiator when multiple traffic descriptor has the same OS and app id.
         * Must be greater than 1.
         */
        public int getDifferentiator() {
            return mDifferentiator;
        }

        /**
         * @return OS/App id in raw byte format.
         */
        public @NonNull byte[] getBytes() {
            byte[] osAppId = (mAppId + (mDifferentiator > 1 ? mDifferentiator : "")).getBytes();
            // 16 bytes for UUID, 1 byte for length of osAppId, and up to 255 bytes for osAppId
            ByteBuffer bb = ByteBuffer.allocate(16 + 1 + osAppId.length);
            bb.putLong(mOsId.getMostSignificantBits());
            bb.putLong(mOsId.getLeastSignificantBits());
            bb.put((byte) osAppId.length);
            bb.put(osAppId);
            return bb.array();
        }

        @Override
        public String toString() {
            return "[OsAppId: OS=" + mOsId + ", App=" + mAppId + ", differentiator="
                    + mDifferentiator + ", raw="
                    + new BigInteger(1, getBytes()).toString(16) + "]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OsAppId osAppId = (OsAppId) o;
            return mDifferentiator == osAppId.mDifferentiator && mOsId.equals(osAppId.mOsId)
                    && mAppId.equals(osAppId.mAppId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mOsId, mAppId, mDifferentiator);
        }
    }

    private final String mDnn;
    private final OsAppId mOsAppId;
    private final @ConnectionCapability int mConnectionCapability;

    private TrafficDescriptor(@NonNull Parcel in) {
        mDnn = in.readString();
        byte[] osAppIdBytes = in.createByteArray();
        OsAppId osAppId = null;
        if (osAppIdBytes != null) {
            osAppId = new OsAppId(osAppIdBytes);
        }
        mOsAppId = osAppId;
        if (Flags.enableTrafficDescriptorConnectionCapability()) {
            mConnectionCapability = in.readInt();
        } else {
            mConnectionCapability = CONNECTION_CAPABILITY_UNKNOWN;
        }

        enforceAllowedIds();
    }

    /**
     * Create a traffic descriptor, as defined in 3GPP TS 24.526 Section 5.2
     * @param dnn optional DNN, which must be used for traffic matching, if present
     * @param osAppIdRawBytes Raw bytes of OsId + osAppId of the traffic descriptor
     *
     * @hide
     */
    public TrafficDescriptor(String dnn, @Nullable byte[] osAppIdRawBytes) {
        this(dnn, osAppIdRawBytes, CONNECTION_CAPABILITY_UNKNOWN);
    }

    /**
     * Create a traffic descriptor, as defined in 3GPP TS 24.526 Section 5.2
     * @param dnn optional DNN, which must be used for traffic matching, if present
     * @param osAppIdRawBytes Raw bytes of OsId + osAppId of the traffic descriptor
     * @param connectionCapability The connection capability of the traffic.
     *
     * @hide
     */
    private TrafficDescriptor(String dnn, @Nullable byte[] osAppIdRawBytes,
            @ConnectionCapability int connectionCapability) {
        mDnn = dnn;
        OsAppId osAppId = null;
        if (osAppIdRawBytes != null) {
            osAppId = new OsAppId(osAppIdRawBytes);
        }
        mOsAppId = osAppId;
        if (Flags.enableTrafficDescriptorConnectionCapability()) {
            mConnectionCapability = connectionCapability;
        } else {
            mConnectionCapability = CONNECTION_CAPABILITY_UNKNOWN;
        }

        enforceAllowedIds();
    }

    /**
     * Enforce the OS id and app id are in the allowed list.
     *
     * @throws IllegalArgumentException if ids are not allowed.
     */
    private void enforceAllowedIds() {
        if (mOsAppId != null && !mOsAppId.getOsId().equals(OsAppId.ANDROID_OS_ID)) {
            throw new IllegalArgumentException("OS id " + mOsAppId.getOsId() + " does not match "
                    + OsAppId.ANDROID_OS_ID);
        }

        if (mOsAppId != null && !OsAppId.ALLOWED_APP_IDS.contains(mOsAppId.getAppId())) {
            throw new IllegalArgumentException("Illegal app id " + mOsAppId.getAppId()
                    + ". Only allowing one of the following " + OsAppId.ALLOWED_APP_IDS);
        }
    }

    /**
     * DNN stands for Data Network Name and represents an APN as defined in 3GPP TS 23.003.
     * @return the DNN of this traffic descriptor if one is included by the network, null
     * otherwise.
     */
    public @Nullable String getDataNetworkName() {
        return mDnn;
    }

    /**
     * OsAppId identifies a broader traffic category. Although it names Os/App id, it only includes
     * OS version with a general/broader category id used as app id.
     *
     * @return The id in byte format. {@code null} if not available.
     */
    public @Nullable byte[] getOsAppId() {
        return mOsAppId != null ? mOsAppId.getBytes() : null;
    }

    /**
     * Get the connection capability of the traffic.
     *
     * <p>The connection capability indicates the type of traffic. The values are defined in
     * 3GPP TS 24.526, Table 5.2.1. This is used by the network for UE Route Selection
     * Policy (URSP) traffic matching.
     *
     * <p>When used to request a data connection, the framework may only use one specific
     * connection capability to match a network capability, even if the underlying APN supports
     * multiple (e.g., both internet and MMS). In such cases, only the single matched
     * connection capability from the network request will be sent to the modem.
     *
     * @return The connection capability, as one of the {@code CONNECTION_CAPABILITY_*} constants.
     *         Returns {@link #CONNECTION_CAPABILITY_UNKNOWN} if not specified.
     * @see Builder#setConnectionCapability(int)
     */
    @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public @ConnectionCapability int getConnectionCapability() {
        return mConnectionCapability;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TrafficDescriptor={mDnn=").append(mDnn);
        sb.append(", mOsAppId=").append(mOsAppId);
        if (Flags.enableTrafficDescriptorConnectionCapability()) {
            sb.append(", mConnectionCapability=").append(mConnectionCapability);
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mDnn);
        dest.writeByteArray(mOsAppId != null ? mOsAppId.getBytes() : null);
        if (Flags.enableTrafficDescriptorConnectionCapability()) {
            dest.writeInt(mConnectionCapability);
        }
    }

    public static final @NonNull Parcelable.Creator<TrafficDescriptor> CREATOR =
            new Parcelable.Creator<TrafficDescriptor>() {
                @Override
                public @NonNull TrafficDescriptor createFromParcel(@NonNull Parcel source) {
                    return new TrafficDescriptor(source);
                }

                @Override
                public @NonNull TrafficDescriptor[] newArray(int size) {
                    return new TrafficDescriptor[size];
                }
            };

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrafficDescriptor that = (TrafficDescriptor) o;
        if (Flags.enableTrafficDescriptorConnectionCapability()) {
            return Objects.equals(mDnn, that.mDnn)
                    && Objects.equals(mOsAppId, that.mOsAppId)
                    && mConnectionCapability == that.mConnectionCapability;
        } else {
            return Objects.equals(mDnn, that.mDnn) && Objects.equals(mOsAppId, that.mOsAppId);
        }
    }

    @Override
    public int hashCode() {
        if (Flags.enableTrafficDescriptorConnectionCapability()) {
            return Objects.hash(mDnn, mOsAppId, mConnectionCapability);
        } else {
            return Objects.hash(mDnn, mOsAppId);
        }
    }

    /**
     * Provides a convenient way to set the fields of a {@link TrafficDescriptor} when creating a
     * new instance.
     *
     * <p>The example below shows how you might create a new {@code TrafficDescriptor}:
     *
     * <pre><code>
     *
     * TrafficDescriptor response = new TrafficDescriptor.Builder()
     *     .setDataNetworkName("example_dnn")
     *     .setConnectionCapability(TrafficDescriptor.CONNECTION_CAPABILITY_***)
     *     .build();
     * </code></pre>
     *
     */
    public static final class Builder {
        private String mDnn = null;
        private byte[] mOsAppId = null;
        private int mConnectionCapability = CONNECTION_CAPABILITY_UNKNOWN;

        /**
         * Default constructor for Builder.
         */
        public Builder() {
        }

        /**
         * Set the Data Network Name(DNN).
         *
         * @return The same instance of the builder.
         */
        @NonNull
        public Builder setDataNetworkName(@NonNull String dnn) {
            this.mDnn = dnn;
            return this;
        }

        /**
         * Set the OS App ID (including OS Id as defined in the specs).
         *
         * @return The same instance of the builder.
         */
        @NonNull
        public Builder setOsAppId(@NonNull byte[] osAppId) {
            this.mOsAppId = osAppId;
            return this;
        }

        /**
         * Set the connection capability of the traffic.
         *
         * @param connectionCapability The connection capability constant to set.
         * @return The same instance of the builder.
         */
        @FlaggedApi(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
        @NonNull
        public Builder setConnectionCapability(@ConnectionCapability int connectionCapability) {
            this.mConnectionCapability = connectionCapability;
            return this;
        }

        /**
         * Build the {@link TrafficDescriptor}.
         *
         * @throws IllegalArgumentException if DNN, OS App ID are null and Connection Capability
         *         is UNKNOWN.
         *
         * @return the {@link TrafficDescriptor} object.
         */
        @NonNull
        public TrafficDescriptor build() {
            if (Flags.enableTrafficDescriptorConnectionCapability()) {
                if (this.mDnn == null
                        && this.mOsAppId == null
                        && this.mConnectionCapability == CONNECTION_CAPABILITY_UNKNOWN) {
                    throw new IllegalArgumentException(
                            "DNN, OS App ID are null and Connection Capability is not set");
                }
                return new TrafficDescriptor(this.mDnn, this.mOsAppId, this.mConnectionCapability);
            }
            if (this.mDnn == null && this.mOsAppId == null) {
                throw new IllegalArgumentException("DNN and OS App ID are null");
            }
            return new TrafficDescriptor(this.mDnn, this.mOsAppId);
        }
    }
}
