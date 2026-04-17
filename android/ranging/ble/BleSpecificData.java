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

package android.ranging.ble;


import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.bluetooth.le.DistanceMeasurementResult;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.ranging.flags.Flags;

import java.util.Objects;

/**
 * Represents the {@link android.ranging.RangingManager#BLE_CS} or
 * {@link android.ranging.RangingManager#BLE_RSSI} specific data.
 * This is generally used by algorithms for fine tuning ranging data.
 *
 */
@FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
public final class BleSpecificData implements Parcelable {
    /** Value for invalid TX Power */
    public static final int INVALID_TX_POWER_DBM = DistanceMeasurementResult.INVALID_TX_POWER_DBM;

    private final double mDelaySpreadMeters;
    private final int mRemoteTxPowerDbm;

    private BleSpecificData(Builder builder) {
        mDelaySpreadMeters = builder.mDelaySpreadMeters;
        mRemoteTxPowerDbm  = builder.mRemoteTxPowerDbm;
    }

    private BleSpecificData(Parcel in) {
        mDelaySpreadMeters = in.readDouble();
        mRemoteTxPowerDbm = in.readInt();
    }

    @NonNull
    public static final Creator<BleSpecificData> CREATOR =
            new Creator<>() {
                @Override
                public BleSpecificData createFromParcel(Parcel in) {
                    return new BleSpecificData(in);
                }

                @Override
                public BleSpecificData[] newArray(int size) {
                    return new BleSpecificData[size];
                }
            };

    /**
     * Gets the delay spread in meters.
     *
     * @return the delay spread in meters
     */
    @IntRange(from = 0)
    public double getDelaySpreadMeters() {
        return mDelaySpreadMeters;
    }

    /**
     * Get remote TX power. Will return {@link #INVALID_TX_POWER_DBM} if it does not exist.
     *
     * @return remote TX power in dBm
     */
    @IntRange(from = -127, to = 127)
    public int getRemoteTxPowerDbm() {
        return mRemoteTxPowerDbm;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeDouble(mDelaySpreadMeters);
        dest.writeInt(mRemoteTxPowerDbm);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BleSpecificData)) return false;
        BleSpecificData that = (BleSpecificData) o;
        return Double.compare(that.mDelaySpreadMeters, mDelaySpreadMeters) == 0
                && (that.mRemoteTxPowerDbm == mRemoteTxPowerDbm);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDelaySpreadMeters, mRemoteTxPowerDbm);
    }

    @Override
    public String toString() {
        return "BleSpecificData: "
                + "DelaySpreadMeters=" + mDelaySpreadMeters
                + "RemoteTxPowerDbm=" + mRemoteTxPowerDbm;
    }

    /**
     * Builder class for creating instances of {@link BleSpecificData}.
     *
     * @hide
     */
    public static final class Builder {
        private double mDelaySpreadMeters = Double.NaN;
        private int mRemoteTxPowerDbm = INVALID_TX_POWER_DBM;

        /**
         * Sets delay spread in meters.
         *
         * @param delaySpreadMeters the delay spread in meters
         * @return the Builder instance
         */
        @NonNull
        public Builder setDelaySpreadMeters(@IntRange(from = 0) double delaySpreadMeters) {
            mDelaySpreadMeters = delaySpreadMeters;
            return this;
        }

        /**
         * Set the remote TX power in dBM.
         *
         * @param remoteTxPowerDbm remote TX power in dBm
         */
        @NonNull
        public Builder setRemoteTxPowerDbm(@IntRange(from = -127, to = 127) int remoteTxPowerDbm) {
            mRemoteTxPowerDbm = remoteTxPowerDbm;
            return this;
        }

        /**
         * Build additional ranging data.
         *
         * @return the additional ranging data
         */
        @NonNull
        public BleSpecificData build() {
            return new BleSpecificData(this);
        }
    }
}
