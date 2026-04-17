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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresApi;
import android.annotation.SystemApi;
import android.net.wifi.flags.Flags;
import android.net.wifi.util.Environment;

import java.util.Arrays;
import java.util.Objects;

/**
 * A class providing information about a USD discovery session with a specific peer.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_USD)
public class DiscoveryResult {
    private final int mPeerId;
    private final byte[] mServiceSpecificInfo;
    @Config.ServiceProtoType
    private final int mServiceProtoType;
    private final boolean mIsFsdEnabled;
    @Nullable
    private final ProximityRangingInfo mProximityRangingInfo;
    @Nullable
    private final byte[] mDevIk;

    private DiscoveryResult(Builder builder) {
        mPeerId = builder.mPeerId;
        mServiceSpecificInfo = builder.mServiceSpecificInfo;
        mServiceProtoType = builder.mServiceProtoType;
        mIsFsdEnabled = builder.mIsFsdEnabled;
        mProximityRangingInfo = builder.mProximityRangingInfo;
        mDevIk = builder.mDevIk;
    }

    /**
     * Get the peer id.
     */
    public int getPeerId() {
        return mPeerId;
    }

    /**
     * Get the service specific info from the peer. If null, service discovery is without service
     * specific info.
     */
    @Nullable
    public byte[] getServiceSpecificInfo() {
        return mServiceSpecificInfo;
    }

    /**
     * Get service specific protocol type {@code (SERVICE_PROTO_TYPE_*)}.
     */
    @Config.ServiceProtoType
    public int getServiceProtoType() {
        return mServiceProtoType;
    }

    /**
     * Return whether Further Service Discovery (FSD) is enabled or not.
     */
    public boolean isFsdEnabled() {
        return mIsFsdEnabled;
    }

    /**
     * Get the proximity ranging information from the peer.
     *
     * @return Proximity ranging info, or {@code null} if not available.
     */
    @RequiresApi(37)
    @FlaggedApi(com.android.wifi.flags.Flags.FLAG_PROXIMITY_RANGING)
    @Nullable
    public ProximityRangingInfo getProximityRangingInfo() {
        if (!Environment.isSdkNewerThanB()) {
            throw new UnsupportedOperationException();
        }
        return mProximityRangingInfo;
    }

    /**
     * Get the Device Identity Key (DevIK) of the proximity ranging peer.
     * <p>
     * This key is derived by the USD protocol engine from the DIRA
     * (Device Identity Resolution Attribute) in the discovery frame, using the list of peer
     * DevIKs provided in the subscribe or publish configuration.
     * A non-null value indicates that the discovered peer is a known device.
     *
     * @return a 16 byte device identity key or null
     */
    @RequiresApi(37)
    @FlaggedApi(com.android.wifi.flags.Flags.FLAG_PROXIMITY_RANGING)
    @Nullable
    public byte[] getDeviceIdentityKey() {
        if (!Environment.isSdkNewerThanB()) {
            throw new UnsupportedOperationException();
        }
        return mDevIk;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DiscoveryResult that)) return false;
        return mPeerId == that.mPeerId
                && mServiceProtoType == that.mServiceProtoType
                && mIsFsdEnabled == that.mIsFsdEnabled
                && Arrays.equals(mServiceSpecificInfo, that.mServiceSpecificInfo)
                && Objects.equals(mProximityRangingInfo, that.mProximityRangingInfo)
                && Arrays.equals(mDevIk, that.mDevIk);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mPeerId, mServiceProtoType, mIsFsdEnabled,
                mProximityRangingInfo);
        result = 31 * result + Arrays.hashCode(mServiceSpecificInfo);
        result = 31 * result + Arrays.hashCode(mDevIk);
        return result;
    }

    @Override
    public String toString() {
        return "DiscoveryResult{"
                + "mPeerId=" + mPeerId
                + ", mServiceSpecificInfo=" + Arrays.toString(mServiceSpecificInfo)
                + ", mServiceProtoType=" + mServiceProtoType
                + ", mIsFsdEnabled=" + mIsFsdEnabled
                + ", mProximityRangingInfo=" + mProximityRangingInfo
                + ", mDevIk=" + Arrays.toString(mDevIk)
                + '}';
    }


    /**
     * {@code DiscoveryResult} builder static inner class.
     */
    @FlaggedApi(Flags.FLAG_USD)
    public static final class Builder {
        private final int mPeerId;
        private byte[] mServiceSpecificInfo;
        private int mServiceProtoType;
        private boolean mIsFsdEnabled;
        private ProximityRangingInfo mProximityRangingInfo = null;
        private byte[] mDevIk = null;

        /**
         * Builder constructor.
         *
         * @param peerId an id of the peer
         */
        public Builder(int peerId) {
            mPeerId = peerId;
        }


        /**
         * Sets the service specific information and returns a reference to this Builder enabling
         * method chaining.
         *
         * @param serviceSpecificInfo the {@code serviceSpecificInfo} to set
         * @return a reference to this Builder
         */
        @NonNull
        public Builder setServiceSpecificInfo(@NonNull byte[] serviceSpecificInfo) {
            this.mServiceSpecificInfo = serviceSpecificInfo;
            return this;
        }

        /**
         * Sets the service protocol type and returns a reference to this Builder enabling method
         * chaining.
         *
         * @param serviceProtoType the {@code serviceProtoType} to set
         * @return a reference to this Builder
         */
        @NonNull
        public Builder setServiceProtoType(@Config.ServiceProtoType int serviceProtoType) {
            this.mServiceProtoType = serviceProtoType;
            return this;
        }

        /**
         * Sets whether Further Service Discovery (FSD) is enabled or not and returns a reference
         * to this Builder enabling method chaining.
         *
         * @param isFsdEnabled the {@code isFsdEnabled} to set
         * @return a reference to this Builder
         */
        @NonNull
        public Builder setFsdEnabled(boolean isFsdEnabled) {
            this.mIsFsdEnabled = isFsdEnabled;
            return this;
        }

        /**
         * Sets the proximity ranging information and returns a reference
         * to this Builder enabling method chaining.
         *
         * @param proximityRangingInfo the {@code proximityRangingInfo} to set
         * @return a reference to this Builder
         */
        @RequiresApi(37)
        @FlaggedApi(com.android.wifi.flags.Flags.FLAG_PROXIMITY_RANGING)
        @NonNull
        public Builder setProximityRangingInfo(
                @Nullable ProximityRangingInfo proximityRangingInfo) {
            if (!Environment.isSdkNewerThanB()) {
                throw new UnsupportedOperationException();
            }
            this.mProximityRangingInfo = proximityRangingInfo;
            return this;
        }

        /**
         * Sets the device identity key of the peer.
         *
         * @param devIk The 16-byte device identity key.
         * @return A reference to this Builder.
         */
        @RequiresApi(37)
        @FlaggedApi(com.android.wifi.flags.Flags.FLAG_PROXIMITY_RANGING)
        @NonNull
        public Builder setDeviceIdentityKey(@Nullable byte[] devIk) {
            if (!Environment.isSdkNewerThanB()) {
                throw new UnsupportedOperationException();
            }
            if (devIk != null && devIk.length != 16) {
                throw new IllegalArgumentException(
                        "Device Identity Key must be 16 bytes long.");
            }
            this.mDevIk = devIk;
            return this;
        }


        /**
         * Returns a {@code DiscoveryResult} built from the parameters previously set.
         *
         * @return a {@code DiscoveryResult} built with parameters of this {@code DiscoveryResult
         * .Builder}
         */
        @NonNull
        public DiscoveryResult build() {
            return new DiscoveryResult(this);
        }
    }
}
