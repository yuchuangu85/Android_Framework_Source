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

package android.telephony.satellite;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.FlaggedApi;

import com.android.internal.telephony.flags.Flags;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.SubscriptionManager;

import android.telephony.CarrierConfigManager;
import android.telephony.satellite.SatelliteManager.SatelliteEnablementRequestReason;

import static android.telephony.CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC;
import static android.telephony.CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_HYBRID;
import static android.telephony.CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_MANUAL;

import static android.telephony.satellite.SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_UNKNOWN;
import static android.telephony.satellite.SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_PURCHASE;
import static android.telephony.satellite.SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_USER;
import static android.telephony.satellite.SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_POWER;
import static android.telephony.satellite.SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_CARRIER_CONFIG_UPDATE;
import static android.telephony.satellite.SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_ENTITLEMENT;

/**
 * EnableRequestAttributes is used to store the attributes of the request
 * {@link SatelliteManager#requestEnabled(EnableRequestAttributes, Executor, Consumer)}
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_SATELLITE_UPSELL)
public class EnableRequestAttributes implements Parcelable {
    /** {@code true} to enable satellite and {@code false} to disable satellite */
    private boolean mIsEnabled;

    /**
     * {@code true} to enable demo mode and {@code false} to disable. When disabling satellite,
     * {@code mIsDemoMode} is always considered as {@code false} by Telephony.
     *
     * Note that demo mode is supported only for {@link #CARRIER_ROAMING_NTN_CONNECT_MANUAL}
     * connect type.
     */
    private boolean mIsDemoMode;

    /**
     * {@code true} means satellite is enabled for emergency mode, {@code false} otherwise. When
     * disabling satellite, {@code isEmergencyMode} is always considered as {@code false} by
     * Telephony.
     *
     * Note that emergency mode is supported only for {@link #CARRIER_ROAMING_NTN_CONNECT_MANUAL}
     * connect type.
     */
    private boolean mIsEmergencyMode;

    /**
     * The connect type to be used for satellite connection.
     *
     * Note that {@link #CARRIER_ROAMING_NTN_CONNECT_HYBRID} is not supported for satellite
     * enablement request.
     */
    @CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_TYPE
    private int mConnectType;

    /**
     * The reason for satellite connection.
     */
    @SatelliteManager.SatelliteEnablementRequestReason
    private int mSatelliteEnablementRequestReason;

    /**
     * Whether the satellite enablement request requires prioritized scanning.
     */
    private boolean mIsPrioritizedScanningRequired;

    /**
     * Constructor from builder.
     *
     * @param builder Builder of {@link EnableRequestAttributes}.
     */
    private EnableRequestAttributes(@NonNull Builder builder) {
        this.mIsEnabled = builder.mIsEnabled;
        this.mIsDemoMode = builder.mIsDemoMode;
        this.mIsEmergencyMode = builder.mIsEmergencyMode;
        this.mConnectType = builder.mConnectType;
        this.mSatelliteEnablementRequestReason = builder.mSatelliteEnablementRequestReason;
        this.mIsPrioritizedScanningRequired = builder.mIsPrioritizedScanningRequired;
    }

    /**
     * @return Whether satellite is to be enabled
     */
    public boolean isEnabled() {
        return mIsEnabled;
    }

    /**
     * @return Whether demo mode is to be enabled
     */
    public boolean isDemoMode() {
        return mIsDemoMode;
    }

    /**
     * @return Whether satellite is to be enabled for emergency mode
     */
    public boolean isEmergencyMode() {
        return mIsEmergencyMode;
    }

    /**
     * @return The connect type to be used for satellite connection.
     */
    @CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_TYPE
    public int getConnectType() {
        return mConnectType;
    }

    /**
     * @return The reason for satellite enablement request.
     */
    @SatelliteManager.SatelliteEnablementRequestReason
    public int getSatelliteEnablementRequestReason() {
        return mSatelliteEnablementRequestReason;
    }

    /**
     * @return Whether the satellite enablement request requires prioritized scanning.
     */
    public boolean isPrioritizedScanningRequired() {
        return mIsPrioritizedScanningRequired;
    }

    /**
     * The builder class of {@link EnableRequestAttributes}
     */
    public static final class Builder {
        private boolean mIsEnabled;
        private boolean mIsDemoMode = false;
        private boolean mIsEmergencyMode = false;
        @CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_TYPE
        private int mConnectType = CARRIER_ROAMING_NTN_CONNECT_MANUAL;
        private int mSatelliteEnablementRequestReason =
                SatelliteManager.SATELLITE_ENABLEMENT_REQUEST_REASON_UNKNOWN;
        private boolean mIsPrioritizedScanningRequired = false;

        public Builder(boolean isEnabled) {
            mIsEnabled = isEnabled;
        }

        /**
         * Set demo mode
         *
         * Note that demo mode is not supported for
         * {@link CarrierConfigManager#CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC}.
         *
         * @param isDemoMode {@code true} to enable demo mode and {@code false} to disable. When
         *                   disabling satellite, {@code isDemoMode} is always considered as
         *                   {@code false} by Telephony.
         * @return The builder object
         */
        @NonNull
        public Builder setDemoMode(boolean isDemoMode) {
            if (mIsEnabled) {
                mIsDemoMode = isDemoMode;
            }
            return this;
        }

        /**
         * Set emergency mode
         *
         * Note that emergency mode is not supported for
         * {@link CarrierConfigManager#CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC}.
         *
         * @param isEmergencyMode {@code true} means satellite is enabled for emergency mode,
         *                        {@code false} otherwise. When disabling satellite,
         *                        {@code isEmergencyMode} is always considered as {@code false} by
         *                        Telephony.
         * @return The builder object
         */
        @NonNull
        public Builder setEmergencyMode(boolean isEmergencyMode) {
            if (mIsEnabled) {
                mIsEmergencyMode = isEmergencyMode;
            }
            return this;
        }

        /**
         * Set the connect type to be used for satellite connection.
         *
         * @param connectType The connect type to be used for satellite connection.
         * @return The builder object
         */
        @NonNull
        public Builder setConnectType(
                @CarrierConfigManager.CARRIER_ROAMING_NTN_CONNECT_TYPE int connectType) {
            mConnectType = connectType;
            return this;
        }

        /**
         * Set the reason for satellite connection.
         *
         * @param satelliteEnablementRequestReason The reason for satellite enablement.
         * @return The builder object
         */
        @NonNull
        public Builder setSatelliteEnablementRequestReason(
                @SatelliteManager.SatelliteEnablementRequestReason
                int satelliteEnablementRequestReason) {
            mSatelliteEnablementRequestReason = satelliteEnablementRequestReason;
            return this;
        }

        /**
         * Set whether the satellite enablement request requires prioritized scanning. By default,
         * Modem will scan for Terrestrial Networks only. When there are no Terrestrial Networks in
         * the area, Modem will fallback to scan for Non-Terrestrial Networks with some delay.
         * Setting prioritized scanning will force the Modem to prioritize scanning for
         * Non-Terrestrial Networks faster.
         *
         * Note that prioritized scanning can only be used only in
         * {@link SatelliteManager#SATELLITE_ENABLEMENT_REQUEST_REASON_USER},
         * {@link SatelliteManager#SATELLITE_ENABLEMENT_REQUEST_REASON_PURCHASE} and
         * {@link SatelliteManager#SATELLITE_ENABLEMENT_REQUEST_REASON_POWER} cases.
         *
         * @param isPrioritizedScanningRequired Whether the satellite enablement request requires
         *                                prioritized scanning of Non-Terrestrial Networks.
         * @return The builder object
         */
        @NonNull
        public Builder setPrioritizedScanningRequired(boolean isPrioritizedScanningRequired) {
            mIsPrioritizedScanningRequired = isPrioritizedScanningRequired;
            return this;
        }

        /**
         * Build the {@link EnableRequestAttributes}
         *
         * @return The {@link EnableRequestAttributes} instance.
         * @throws IllegalStateException if the configuration is invalid.
         */
        @NonNull
        public EnableRequestAttributes build() {
            validate(this);
            return new EnableRequestAttributes(this);
        }

        private void validate(Builder builder) {
            if (builder.mConnectType == CARRIER_ROAMING_NTN_CONNECT_HYBRID) {
                throw new IllegalStateException("CARRIER_ROAMING_NTN_CONNECT_HYBRID is not "
                        + "supported.");
            }

            if (builder.mConnectType == CARRIER_ROAMING_NTN_CONNECT_MANUAL) {
                if (builder.mIsPrioritizedScanningRequired) {
                    throw new IllegalStateException(
                            "Prioritized scanning cannot be used with "
                                + "CARRIER_ROAMING_NTN_CONNECT_MANUAL.");
                }
                if (builder.mSatelliteEnablementRequestReason
                        == SATELLITE_ENABLEMENT_REQUEST_REASON_ENTITLEMENT) {
                    throw new IllegalStateException(
                            "Satellite enablement with reason"
                                    + " SATELLITE_ENABLEMENT_REQUEST_REASON_ENTITLEMENT is not"
                                    + " supported for CARRIER_ROAMING_NTN_CONNECT_MANUAL.");
                }
            }

            if (builder.mConnectType == CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC) {
                if (builder.mIsDemoMode) {
                    throw new IllegalStateException(
                            "Demo mode is not supported for "
                                    + "CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC.");
                }
                if (builder.mIsEmergencyMode) {
                    throw new IllegalStateException(
                            "Emergency mode is not supported for "
                                    + "CARRIER_ROAMING_NTN_CONNECT_AUTOMATIC.");
                }
            }
        }
    }

    @Override
    public String toString() {
        return "EnableRequestAttributes{"
                + "mIsEnabled=" + mIsEnabled
                + ", mIsDemoMode=" + mIsDemoMode
                + ", mIsEmergencyMode=" + mIsEmergencyMode
                + ", mConnectType=" + mConnectType
                + ", mSatelliteEnablementRequestReason=" + mSatelliteEnablementRequestReason
                + ", mIsPrioritizedScanningRequired=" + mIsPrioritizedScanningRequired
                + '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mIsEnabled);
        dest.writeBoolean(mIsDemoMode);
        dest.writeBoolean(mIsEmergencyMode);
        dest.writeInt(mConnectType);
        dest.writeInt(mSatelliteEnablementRequestReason);
        dest.writeBoolean(mIsPrioritizedScanningRequired);
    }

    private EnableRequestAttributes(Parcel in) {
        if (in == null) {
            throw new IllegalArgumentException("Parcel is null.");
        }
        mIsEnabled = in.readBoolean();
        mIsDemoMode = in.readBoolean();
        mIsEmergencyMode = in.readBoolean();
        mConnectType = in.readInt();
        mSatelliteEnablementRequestReason = in.readInt();
        mIsPrioritizedScanningRequired = in.readBoolean();
    }

    @NonNull
    public static final Creator<EnableRequestAttributes> CREATOR =
            new Creator<EnableRequestAttributes>() {
        @Override
        public EnableRequestAttributes createFromParcel(Parcel in) {
            return new EnableRequestAttributes(in);
        }

        @Override
        public EnableRequestAttributes[] newArray(int size) {
            return new EnableRequestAttributes[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EnableRequestAttributes)) {
            return false;
        }
        EnableRequestAttributes that = (EnableRequestAttributes) o;
        return mIsEnabled == that.mIsEnabled
                && mIsDemoMode == that.mIsDemoMode
                && mIsEmergencyMode == that.mIsEmergencyMode
                && mConnectType == that.mConnectType
                && mSatelliteEnablementRequestReason == that.mSatelliteEnablementRequestReason
                && mIsPrioritizedScanningRequired == that.mIsPrioritizedScanningRequired;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(mIsEnabled, mIsDemoMode, mIsEmergencyMode, mConnectType,
                mSatelliteEnablementRequestReason, mIsPrioritizedScanningRequired);
    }
}
