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

package android.ranging.raw;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.ranging.RangingDevice;
import android.ranging.RangingManager;
import android.ranging.ble.cs.BleCsConstants;
import android.ranging.ble.cs.BleCsRangingParams;
import android.ranging.ble.rssi.BleRssiConstants;
import android.ranging.ble.rssi.BleRssiRangingParams;
import android.ranging.uwb.DlTdoaRangingParams;
import android.ranging.uwb.UwbConstants;
import android.ranging.uwb.UwbRangingCapabilities;
import android.ranging.uwb.UwbRangingParams;
import android.ranging.wifi.pd.WifiPdConstants;
import android.ranging.wifi.pd.WifiPdRangingParams;
import android.ranging.wifi.rtt.RttRangingCapabilities;
import android.ranging.wifi.rtt.RttRangingParams;
import android.ranging.wifi.rtt.RttStationRangingParams;
import android.ranging.wifi.rtt.WifiRttConstants;
import android.util.ArrayMap;

import com.android.ranging.flags.Flags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Represents a device participating in ranging operations.
 * This class supports multiple ranging technologies, including UWB, BLE CS, BLE RSSI and Wi-Fi
 * NAN-RTT. The configuration for each technology is provided through corresponding parameter
 * objects.
 */
@FlaggedApi(Flags.FLAG_RANGING_STACK_ENABLED)
public final class RawRangingDevice implements Parcelable {

    /**
     * Defines the configuration IDs for different ranging scenarios.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_USE})
    @IntDef({
            UPDATE_RATE_NORMAL,
            UPDATE_RATE_INFREQUENT,
            UPDATE_RATE_FREQUENT,
    })
    public @interface RangingUpdateRate {
    }

    /**
     * Normal ranging interval (Default).
     * <ul>
     *     <li> UWB - 200 milliseconds for config ids {@link UwbRangingParams#getConfigId()} with
     *     multicast ranging, 240 milliseconds for unicast. </li>
     *     <li> BLE RSSI - 1 second. </li>
     *     <li> BLE CS - 200 ms. </li>
     *     <li> WiFi Rtt - 256 milliseconds if
     *     {@link RttRangingCapabilities#hasPeriodicRangingHardwareFeature()} is true, 512
     *     milliseconds otherwise. </li>
     * </ul>
     */
    public static final int UPDATE_RATE_NORMAL = 1;

    /**
     * Infrequent ranging interval.
     * <ul>
     *     <li> UWB - 600 milliseconds. </li>
     *     <li> BLE RSSI - 3 seconds. </li>
     *     <li> BLE CS - 5 seconds. </li>
     *     <li> WiFi Rtt - 8192 milliseconds otherwise. </li>
     * </ul>
     */
    public static final int UPDATE_RATE_INFREQUENT = 2;

    /**
     * Frequent ranging interval.
     * <ul>
     *     <li> UWB - 96 milliseconds for config id
     *     {@link UwbRangingParams#CONFIG_PROVISIONED_UNICAST_DS_TWR_VERY_FAST}, 120 milliseconds
     *     otherwise. See {@link UwbRangingCapabilities#getSupportedRangingUpdateRates()} to verify
     *     Frequent update rate is supported. </li>
     *     <li> BLE RSSI - 500 milliseconds. </li>
     *     <li> BLE CS - 100 milliseconds. </li>
     *     <li> WiFi Rtt - 128 milliseconds if
     *     {@link RttRangingCapabilities#hasPeriodicRangingHardwareFeature()} is true, 256
     *     milliseconds otherwise. </li>
     * </ul>
     */
    public static final int UPDATE_RATE_FREQUENT = 3;
    private final RangingDevice mRangingDevice;
    private final UwbRangingParams mUwbRangingParams;
    private final BleCsRangingParams mBleCsRangingParams;
    private final RttRangingParams mRttRangingParams;
    private final BleRssiRangingParams mBleRssiRangingParams;
    private final RttStationRangingParams mRttStationRangingParams;
    private final WifiPdRangingParams mWifiPdRangingParams;
    private final DlTdoaRangingParams mDlTdoaRangingParams;

    private RawRangingDevice(Builder builder) {
        mRangingDevice = builder.mRangingDevice;
        mUwbRangingParams = builder.mUwbRangingParams;
        mBleCsRangingParams = builder.mBleCsRangingParams;
        mRttRangingParams = builder.mRttRangingParams;
        mBleRssiRangingParams = builder.mBleRssiRangingParams;
        mRttStationRangingParams = builder.mRttStationRangingParams;
        mWifiPdRangingParams = builder.mWifiPdRangingParams;
        mDlTdoaRangingParams = builder.mDlTdoaRangingParams;
    }


    private RawRangingDevice(Parcel in) {
        mRangingDevice = in.readParcelable(RangingDevice.class.getClassLoader(), RangingDevice.class
        );
        mUwbRangingParams = in.readParcelable(UwbRangingParams.class.getClassLoader(),
                UwbRangingParams.class);
        mBleCsRangingParams = in.readParcelable(BleCsRangingParams.class.getClassLoader(),
                BleCsRangingParams.class
        );
        mRttRangingParams = in.readParcelable(RttRangingParams.class.getClassLoader(),
                RttRangingParams.class);
        mBleRssiRangingParams = in.readParcelable(BleRssiRangingParams.class.getClassLoader(),
                BleRssiRangingParams.class);
        mRttStationRangingParams = in.readParcelable(RttStationRangingParams.class.getClassLoader(),
                RttStationRangingParams.class);
        mWifiPdRangingParams = in.readParcelable(WifiPdRangingParams.class.getClassLoader(),
                WifiPdRangingParams.class);
        mDlTdoaRangingParams = in.readParcelable(DlTdoaRangingParams.class.getClassLoader(),
                DlTdoaRangingParams.class);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mRangingDevice, flags);
        dest.writeParcelable(mUwbRangingParams, flags);
        dest.writeParcelable(mBleCsRangingParams, flags);
        dest.writeParcelable(mRttRangingParams, flags);
        dest.writeParcelable(mBleRssiRangingParams, flags);
        dest.writeParcelable(mRttStationRangingParams, flags);
        dest.writeParcelable(mWifiPdRangingParams, flags);
        dest.writeParcelable(mDlTdoaRangingParams, flags);
    }

    @NonNull
    public static final Creator<RawRangingDevice> CREATOR = new Creator<RawRangingDevice>() {
        @Override
        public RawRangingDevice createFromParcel(Parcel in) {
            return new RawRangingDevice(in);
        }

        @Override
        public RawRangingDevice[] newArray(int size) {
            return new RawRangingDevice[size];
        }
    };

    /**
     * Returns the {@link RangingDevice} associated with this instance.
     *
     * @return the ranging device.
     */
    @NonNull
    public RangingDevice getRangingDevice() {
        return mRangingDevice;
    }

    /**
     * Returns the UWB ranging parameters, if applicable.
     *
     * @return the {@link UwbRangingParams}, or {@code null} if not set.
     */
    @Nullable
    public UwbRangingParams getUwbRangingParams() {
        return mUwbRangingParams;
    }

    /**
     * Returns the CS ranging parameters, if applicable.
     *
     * @return the {@link BleCsRangingParams}, or {@code null} if not set.
     */
    @Nullable
    public BleCsRangingParams getCsRangingParams() {
        return mBleCsRangingParams;
    }

    /**
     * Returns the RTT ranging parameters, if applicable.
     *
     * @return the {@link RttRangingParams}, or {@code null} if not set.
     */
    @Nullable
    public RttRangingParams getRttRangingParams() {
        return mRttRangingParams;
    }

    /**
     * Returns the BLE rssi ranging parameters, if applicable.
     *
     * @return the {@link BleRssiRangingParams}, or {@code null} if not set.
     */
    @Nullable
    public BleRssiRangingParams getBleRssiRangingParams() {
        return mBleRssiRangingParams;
    }

    /**
     * Returns the  Wi-Fi STA-AP ranging parameters, if applicable.
     *
     * @return the {@link RttStationRangingParams}, or {@code null} if not set.
     */
    @Nullable
    @FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_25Q4)
    public RttStationRangingParams getRttStationRangingParams() {
        return mRttStationRangingParams;
    }

    /**
     * Returns the Wi-Fi PD ranging parameters, if applicable.
     *
     * @return the {@link WifiPdRangingParams}, or {@code null} if not set.
     */
    @Nullable
    @FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public WifiPdRangingParams getWifiPdRangingParams() {
        return mWifiPdRangingParams;
    }

    /**
     * Returns the DL-TDOA ranging parameters, if applicable.
     *
     * @return the {@link DlTdoaRangingParams}, or {@code null} if not set.
     */
    @Nullable
    @FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public DlTdoaRangingParams getDlTdoaRangingParams() {
        return mDlTdoaRangingParams;
    }

    /**
     * Returns a map of ranging technologies to their corresponding ranging interval durations for
     * available ranging technologies parameters.
     *
     * <p>The duration for each technology is determined by its configured
     * {@link RangingUpdateRate} and, in some cases, technology-specific parameters like
     * {@link UwbRangingParams#getConfigId()} for UWB.
     *
     * @return A non-null, possibly empty, map where keys are RangingTechnology integers and
     * values are the calculated {@link Duration} for that technology's ranging interval.
     */
    @FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_25Q4)
    @NonNull
    public Map<@RangingManager.RangingTechnology Integer, Duration> getRangingIntervalValues() {
        ArrayMap<@RangingManager.RangingTechnology Integer, Duration> map = new ArrayMap<>();

        if (mUwbRangingParams != null) {
            int uwbUpdateRate = mUwbRangingParams.getRangingUpdateRate();
            int uwbConfigId = mUwbRangingParams.getConfigId();
            map.put(RangingManager.UWB, Duration.ofMillis(
                    UwbConstants.getIntervalMs((int) uwbConfigId, (int) uwbUpdateRate)));
        }

        if (mBleCsRangingParams != null) {
            int bleCsUpdateRate = mBleCsRangingParams.getRangingUpdateRate();
            map.put(RangingManager.BLE_CS,
                    Duration.ofMillis(BleCsConstants.getIntervalInMs(bleCsUpdateRate)));
        }

        if (mRttRangingParams != null) {
            int wifiNanRttUpdateRateMs = WifiRttConstants.getIntervalMs(
                    (int) mRttRangingParams.getRangingUpdateRate(),
                    mRttRangingParams.isPeriodicRangingHwFeatureEnabled());
            map.put(RangingManager.WIFI_NAN_RTT, Duration.ofMillis(wifiNanRttUpdateRateMs));
        }

        if (mBleRssiRangingParams != null) {
            int bleRssiUpdateRate = mBleRssiRangingParams.getRangingUpdateRate();
            map.put(RangingManager.BLE_RSSI,
                    Duration.ofMillis(BleRssiConstants.getIntervalInMs((int) bleRssiUpdateRate)));
        }

        if (mDlTdoaRangingParams != null) {
            int dlTdoaUpdateRate = mDlTdoaRangingParams.getRangingIntervalMillis();
            map.put(RangingManager.UWB,
                    Duration.ofMillis(dlTdoaUpdateRate));
        }

        if (mRttStationRangingParams != null) {
            int wifiStaRttUpdateRateMs = WifiRttConstants.getIntervalMs(
                    (int) mRttStationRangingParams.getRangingUpdateRate(), true);
            map.put(RangingManager.WIFI_STA_RTT, Duration.ofMillis(wifiStaRttUpdateRateMs));
        }

        if (mWifiPdRangingParams != null) {
            int wifiPdUpdateRateMs = WifiPdConstants.getIntervalInMs(
                    mWifiPdRangingParams.getRangingUpdateRate());
            map.put(RangingManager.WIFI_PD, Duration.ofMillis(wifiPdUpdateRateMs));

        }
        return map;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Builder class for creating instances of {@link RawRangingDevice}.
     */
    public static final class Builder {
        private RangingDevice mRangingDevice;
        private UwbRangingParams mUwbRangingParams;
        private BleCsRangingParams mBleCsRangingParams;
        private RttRangingParams mRttRangingParams;
        private BleRssiRangingParams mBleRssiRangingParams;
        private RttStationRangingParams mRttStationRangingParams;
        private WifiPdRangingParams mWifiPdRangingParams;
        private DlTdoaRangingParams mDlTdoaRangingParams;

        /**
         * Sets the ranging device.
         *
         * @param rangingDevice the {@link RangingDevice} to be set.
         * @return this {@link Builder} instance for chaining calls.
         */
        @NonNull
        public Builder setRangingDevice(@NonNull RangingDevice rangingDevice) {
            Objects.requireNonNull(rangingDevice);
            mRangingDevice = rangingDevice;
            return this;
        }

        /**
         * Sets the UWB ranging parameters.
         *
         * @param params the {@link UwbRangingParams} to be set.
         * @return this {@link Builder} instance for chaining calls.
         */
        @NonNull
        public Builder setUwbRangingParams(@NonNull UwbRangingParams params) {
            Objects.requireNonNull(params);
            mUwbRangingParams = params;
            return this;
        }

        /**
         * Sets the WiFi NAN-RTT ranging parameters.
         *
         * @param params the {@link RttRangingParams} to be set.
         * @return this {@link Builder} instance for chaining calls.
         */
        @NonNull
        public Builder setRttRangingParams(@NonNull RttRangingParams params) {
            Objects.requireNonNull(params);
            mRttRangingParams = params;
            return this;
        }

        /**
         * Sets the BLE channel sounding ranging parameters.
         *
         * @param params the {@link BleCsRangingParams} to be set.
         * @return this {@link Builder} instance for chaining calls.
         */
        @NonNull
        public Builder setCsRangingParams(@NonNull BleCsRangingParams params) {
            Objects.requireNonNull(params);
            mBleCsRangingParams = params;
            return this;
        }

        /**
         * Sets the BLE rssi ranging parameters.
         *
         * @param params the {@link BleCsRangingParams} to be set.
         * @return this {@link Builder} instance for chaining calls.
         */
        @NonNull
        public Builder setBleRssiRangingParams(@NonNull BleRssiRangingParams params) {
            Objects.requireNonNull(params);
            mBleRssiRangingParams = params;
            return this;
        }

        /**
         * Sets the WiFi STA-AP RTT ranging parameters.
         *
         * @param params the {@link RttStationRangingParams} to be set.
         * @return this {@link Builder} instance for chaining calls.
         */
        @FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_25Q4)
        @NonNull
        public Builder setRttStationRangingParams(@NonNull RttStationRangingParams params) {
            Objects.requireNonNull(params);
            mRttStationRangingParams = params;
            return this;
        }

        /**
         * Sets the Wi-Fi PD ranging parameters.
         *
         * @param params the {@link WifiPdRangingParams} to be set.
         * @return this {@link Builder} instance for chaining calls.
         */
        @FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
        @NonNull
        public Builder setWifiPdRangingParams(@NonNull WifiPdRangingParams params) {
            Objects.requireNonNull(params);
            mWifiPdRangingParams = params;
            return this;
        }


        /**
         * Sets the DL-TDOA ranging parameters.
         *
         * @param params the {@link DlTdoaRangingParams} to be set.
         * @return this {@link Builder} instance for chaining calls.
         */
        @NonNull
        @FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
        public Builder setDlTdoaRangingParams(@NonNull DlTdoaRangingParams params) {
            Objects.requireNonNull(params);
            mDlTdoaRangingParams = params;
            return this;
        }

        /**
         * Builds and returns a new {@link RawRangingDevice} instance.
         *
         * @return a new {@link RawRangingDevice} configured with the specified parameters.
         */
        @NonNull
        public RawRangingDevice build() {
            Objects.requireNonNull(this.mRangingDevice);
            if (Stream.of(mUwbRangingParams, mBleCsRangingParams, mBleRssiRangingParams,
                            mRttRangingParams, mRttStationRangingParams, mWifiPdRangingParams, mDlTdoaRangingParams)
                    .allMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "At least one ranging params should be configured");
            }
            return new RawRangingDevice(this);
        }
    }

    @Override
    public String toString() {
        return "RawRangingDevice{ "
                + "mRangingDevice="
                + mRangingDevice
                + ", mUwbRangingParams="
                + mUwbRangingParams
                + ", mBleCsRangingParams="
                + mBleCsRangingParams
                + ", mRttRangingParams="
                + mRttRangingParams
                + mBleRssiRangingParams
                + ", mRttStationRangingParams="
                + mRttStationRangingParams
                + ", mWifiPdRangingParams="
                + mWifiPdRangingParams
                + ", mDlTdoaRangingParams="
                + mDlTdoaRangingParams
                + " }";
    }
}

