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

package android.net.wifi.aware;

import static com.android.wifi.flags.Flags.FLAG_MULTI_PEER_AWARE_DATAPATH;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A request to set up a data path for a Wi-Fi Aware network. This is used to set up a data path
 * with a peer device.
 * This is used for
 * {@link PublishDiscoverySession#acceptDataPathRequest(PeerHandle, AwareDataPathRequest)}
 * and {@link SubscribeDiscoverySession#initiateDataPathRequest(PeerHandle, AwareDataPathRequest)}
 */
@FlaggedApi(FLAG_MULTI_PEER_AWARE_DATAPATH)
public final class AwareDataPathRequest implements Parcelable {

    /**
     * The reason for the data path connection failure, when device doesn't have enough data path
     * resources or data interfaces.
     */
    public static final int DATA_PATH_CONNECTION_FAILURE_REASON_NO_RESOURCE = 1;
    /**
     * The reason for the data path connection failure, when the peer is not found.
     */
    public static final int DATA_PATH_CONNECTION_FAILURE_REASON_PEER_NOT_FOUND = 2;
    /**
     * The reason for the data path connection failure, when the peer rejects the data path
     * connection request.
     */
    public static final int DATA_PATH_CONNECTION_FAILURE_REASON_REJECT_BY_PEER = 3;
    /**
     * The reason for the data path connection failure, when the data path connection times out.
     * Peer doesn't respond to the data path connection request within the timeout period.
     */
    public static final int DATA_PATH_CONNECTION_FAILURE_REASON_TIME_OUT = 4;
    /**
     * The reason for the data path connection failure, when the data path connection fails due to
     * internal failure. This can be caused by the framework or the firmware issues.
     */
    public static final int DATA_PATH_CONNECTION_FAILURE_REASON_INTERNAL_FAILURE = 5;

    /** @hide */
    @IntDef({
            DATA_PATH_CONNECTION_FAILURE_REASON_NO_RESOURCE,
            DATA_PATH_CONNECTION_FAILURE_REASON_PEER_NOT_FOUND,
            DATA_PATH_CONNECTION_FAILURE_REASON_REJECT_BY_PEER,
            DATA_PATH_CONNECTION_FAILURE_REASON_TIME_OUT,
            DATA_PATH_CONNECTION_FAILURE_REASON_INTERNAL_FAILURE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface DataPathConnectionFailureReason {
    }


    /**
     * The port number which will be used to create a connection over this link. This
     * configuration should only be done on the server device, e.g. the device creating the
     * {@link java.net.ServerSocket}.
     */
    private final int mPort;

    /**
     * The transport protocol which will be used to create a connection over this link. This
     * configuration should only be done on the server device, e.g. the device creating the
     * {@link java.net.ServerSocket} for TCP.
     */
    private final int mTransportProtocol;

    private final WifiAwareDataPathSecurityConfig mSecurityConfig;

    /**
     * @hide
     */
    public AwareDataPathRequest(int port, int transportProtocol,
            WifiAwareDataPathSecurityConfig securityConfig) {
        mPort = port;
        mTransportProtocol = transportProtocol;
        mSecurityConfig = securityConfig;
    }

    private AwareDataPathRequest(Parcel in) {
        mPort = in.readInt();
        mTransportProtocol = in.readInt();
        mSecurityConfig = in.readParcelable(WifiAwareDataPathSecurityConfig.class.getClassLoader());
    }

    public @NonNull static final Creator<AwareDataPathRequest> CREATOR =
            new Creator<AwareDataPathRequest>() {
                @Override
                public AwareDataPathRequest createFromParcel(Parcel in) {
                    return new AwareDataPathRequest(in);
                }

                @Override
                public AwareDataPathRequest[] newArray(int size) {
                    return new AwareDataPathRequest[size];
                }
            };

    /**
     * Get the security config specified in this Network Specifier to encrypt Wi-Fi Aware data-path
     * @return {@link WifiAwareDataPathSecurityConfig} used to encrypt the data-path
     */
    public @Nullable WifiAwareDataPathSecurityConfig getDataPathSecurityConfig() {
        return mSecurityConfig;
    }

    /**
     * Get the port number which will be used to create a connection over this link.
     * @see AwareDataPathRequest.Builder#setPort(int)
     * @return The port number. A value of 0 indicates that no port was specified.
     */
    @IntRange(from = 0, to = 65535)
    public int getPort() {
        return mPort;
    }

    /**
     * Get the transport protocol which will be used to create a connection over this link.
     * @see AwareDataPathRequest.Builder#setTransportProtocol(int)
     * @return The transport protocol. A value of -1 indicates that no transport protocol was
     *         specified.
     */
    @IntRange(from = -1, to = 255)
    public int getTransportProtocol() {
        return mTransportProtocol;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mPort);
        dest.writeInt(mTransportProtocol);
        dest.writeParcelable(mSecurityConfig, flags);
    }

    @Override
    public String toString() {
        return "AwareDataPathRequest{"
                + "mPort=" + mPort
                + ", mTransportProtocol=" + mTransportProtocol
                + ", mSecurityConfig=" + mSecurityConfig + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPort, mTransportProtocol, mSecurityConfig);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AwareDataPathRequest that = (AwareDataPathRequest) o;
        return mPort == that.mPort
                && mTransportProtocol == that.mTransportProtocol

                && Objects.equals(mSecurityConfig, that.mSecurityConfig);
    }

    /**
     * A builder class for a Wi-Fi Aware data path request to set up a data path with a peer
     * device.
     */
    @FlaggedApi(FLAG_MULTI_PEER_AWARE_DATAPATH)
    public static final class Builder {
        private static final int INVALID_PORT = 0;
        private static final int INVALID_TRANSPORT_PROTOCOL = -1;
        private static final int INVALID_CHANNEL = 0;

        private int mPort = INVALID_PORT;
        private int mTransportProtocol = INVALID_TRANSPORT_PROTOCOL;
        private WifiAwareDataPathSecurityConfig mSecurityConfig;

        /**
         * Configure the port number which will be used to create a connection over this link. This
         * configuration should only be done on the server device, e.g. the device creating the
         * {@link java.net.ServerSocket}.
         * <p>Notes:
         * <ul>
         *     <li>The server device must be the Publisher device!
         *     <li>The port information can only be specified on secure links, specified using
         *     {@link #setDataPathSecurityConfig(WifiAwareDataPathSecurityConfig)}
         * </ul>
         *
         * @param port A positive integer indicating the port to be used for communication.
         * @return the current {@link Builder} builder, enabling chaining of builder
         *         methods.
         */
        public @NonNull Builder setPort(@IntRange(from = 1, to = 65535) int port) {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("The port must be a positive integer"
                        + " in range [1, 65535]");
            }
            mPort = port;
            return this;
        }

        /**
         * Configure the transport protocol which will be used to create a connection over this
         * link. This configuration should only be done on the server device, e.g. the device
         * creating the {@link java.net.ServerSocket} for TCP.
         * <p>Notes:
         * <ul>
         *     <li>The server device must be the Publisher device!
         *     <li>The transport protocol information can only be specified on secure links,
         *     specified using
         *     {@link #setDataPathSecurityConfig(WifiAwareDataPathSecurityConfig)}.
         * </ul>
         * The transport protocol number is assigned by the Internet Assigned Numbers Authority
         * (IANA) https://www.iana.org/assignments/protocol-numbers/protocol-numbers.xhtml.
         *
         * @param transportProtocol The transport protocol to be used for communication.
         * @return the current {@link Builder} builder, enabling chaining of builder
         *         methods.
         */
        public @NonNull Builder setTransportProtocol(@IntRange(from = 0, to = 255)
                int transportProtocol) {
            if (transportProtocol < 0 || transportProtocol > 255) {
                throw new IllegalArgumentException(
                        "The transport protocol must be in range [0, 255]");
            }
            mTransportProtocol = transportProtocol;
            return this;
        }

        /**
         * Configure security config for the Wi-Fi Aware connection being requested. This method
         * is optional - if not called, then an Open (unencrypted) connection will be created.
         *
         * @param securityConfig The (optional) security config to be used to encrypt the link.
         * @return the current {@link Builder} builder, enabling chaining of builder
         *         methods.
         */
        public @NonNull Builder setDataPathSecurityConfig(
                @NonNull WifiAwareDataPathSecurityConfig securityConfig) {
            if (securityConfig == null) {
                throw new IllegalArgumentException("The WifiAwareDataPathSecurityConfig "
                        + "should be non-null");
            }

            if (!securityConfig.isValid()) {
                throw new IllegalArgumentException("The WifiAwareDataPathSecurityConfig "
                        + "is invalid");
            }
            mSecurityConfig = securityConfig;
            return this;
        }

        /**
         * Build the {@link AwareDataPathRequest} object.
         * @return the {@link AwareDatapathRequest} object.
         */
        public @android.annotation.NonNull AwareDataPathRequest build() {
            return new AwareDataPathRequest(mPort, mTransportProtocol, mSecurityConfig);
        }
    }
}
