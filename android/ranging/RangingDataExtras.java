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

package android.ranging;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.ranging.ble.BleSpecificData;
import android.ranging.uwb.UwbSpecificData;
import android.ranging.wifi.rtt.WifiRttSpecificData;

import com.android.ranging.flags.Flags;

import java.util.Objects;

/**
 * Represents extra ranging data from various technologies.
 *
 */
@FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
public final class RangingDataExtras implements Parcelable {

    private final BleSpecificData mBleSpecificData;
    private final WifiRttSpecificData mWifiRttSpecificData;
    private final UwbSpecificData mUwbSpecificData;

    private RangingDataExtras(Builder builder) {
        mBleSpecificData = builder.mBleSpecificData;
        mWifiRttSpecificData = builder.mRttSpecificData;
        mUwbSpecificData = builder.mUwbSpecificData;
    }

    private RangingDataExtras(Parcel in) {
        mBleSpecificData = in.readParcelable(
                BleSpecificData.class.getClassLoader(), BleSpecificData.class);
        mWifiRttSpecificData = in.readParcelable(
                WifiRttSpecificData.class.getClassLoader(), WifiRttSpecificData.class);
        mUwbSpecificData = in.readParcelable(
                UwbSpecificData.class.getClassLoader(), UwbSpecificData.class);
    }

    public static final @NonNull Creator<RangingDataExtras> CREATOR = new Creator<>() {
        @Override
        public RangingDataExtras createFromParcel(Parcel in) {
            return new RangingDataExtras(in);
        }

        @Override
        public RangingDataExtras[] newArray(int size) {
            return new RangingDataExtras[size];
        }
    };

    /**
     * Returns the {@link BleSpecificData} ranging data extras, or {@code null} if not available.
     */
    @Nullable
    public BleSpecificData getBleSpecificData() {
        return mBleSpecificData;
    }

    /**
     * Returns the {@link WifiRttSpecificData} ranging data extras, or {@code null} if not
     * available.
     */
    @Nullable
    public WifiRttSpecificData getWifiRttSpecificData() {
        return mWifiRttSpecificData;
    }

    /**
     * Returns the {@link UwbSpecificData} ranging data extras, or {@code null} if not available.
     */
    @Nullable
    public UwbSpecificData getUwbSpecificData() {
        return mUwbSpecificData;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mBleSpecificData, flags);
        dest.writeParcelable(mWifiRttSpecificData, flags);
        dest.writeParcelable(mUwbSpecificData, flags);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RangingDataExtras)) return false;
        RangingDataExtras that = (RangingDataExtras) o;
        return Objects.equals(mBleSpecificData, that.mBleSpecificData)
                && Objects.equals(mWifiRttSpecificData, that.mWifiRttSpecificData)
                && Objects.equals(mUwbSpecificData, that.mUwbSpecificData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mBleSpecificData, mWifiRttSpecificData, mUwbSpecificData);
    }

    /**
     * Builder class for creating instances of {@link RangingDataExtras}.
     *
     * @hide
     */
    public static final class Builder {
        private BleSpecificData mBleSpecificData;
        private WifiRttSpecificData mRttSpecificData;
        private UwbSpecificData mUwbSpecificData;

        /**
         * Sets the BLE CS ranging data extras.
         *
         * @param bleCsSpecificData The BLE CS ranging data extras.
         * @return This {@link Builder} instance.
         */
        @NonNull
        public Builder setBleSpecificData(
                @NonNull BleSpecificData bleCsSpecificData) {
            mBleSpecificData = bleCsSpecificData;
            return this;
        }

        /**
         * Sets the RTT ranging data extras.
         *
         * @param wifiRttSpecificData The RTT ranging data extras.
         * @return This {@link Builder} instance.
         */
        @NonNull
        public Builder setRttSpecificData(
                @NonNull WifiRttSpecificData wifiRttSpecificData) {
            mRttSpecificData = wifiRttSpecificData;
            return this;
        }

        /**
         * Sets the UWB ranging data extras.
         *
         * @param uwbSpecificData The UWB ranging data extras.
         * @return This {@link Builder} instance.
         */
        @NonNull
        public Builder setUwbSpecificData(
                @NonNull UwbSpecificData uwbSpecificData) {
            mUwbSpecificData = uwbSpecificData;
            return this;
        }

        /**
         * Builds and returns a new instance of {@link RangingDataExtras}.
         *
         * @return A new {@link RangingDataExtras} instance.
         */
        @NonNull
        public RangingDataExtras build() {
            return new RangingDataExtras(this);
        }
    }

    @Override
    public String toString() {
        return "RangingDataExtras{"
                + "mBleSpecificData=" + mBleSpecificData
                + ", mWifiRttSpecificData=" + mWifiRttSpecificData
                + ", mUwbSpecificData=" + mUwbSpecificData
                + '}';
    }
}
