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

package android.health.connect;

import static com.android.healthfitness.flags.Flags.FLAG_DEVICE_DATA_PROVIDERS_API;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.datatypes.Device;
import android.health.connect.device.DeviceParcel;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Represents a physical device and the list of device data provider (DDP) applications contributing
 * to it.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
public final class DeviceDataSourceInfo implements Parcelable {
    @NonNull private final DataOrigin mDeviceDataOrigin;
    @NonNull private final Device mDevice;
    private final boolean mIsCurrentDevice;
    // One device may have multiple device data providers contributing data.
    @NonNull private final List<DeviceDataProviderInfo> mDeviceDataProviderInfos;

    /**
     * Creates an instance of {@link DeviceDataSourceInfo}.
     *
     * @param deviceDataOrigin The synthetic origin representing the physical device.
     * @param device The {@link Device} instance.
     * @param isCurrentDevice Whether this is the current device.
     * @param deviceDataProviderInfos List of DDPs contributing to this device.
     */
    public DeviceDataSourceInfo(
            @NonNull DataOrigin deviceDataOrigin,
            @NonNull Device device,
            boolean isCurrentDevice,
            @NonNull List<DeviceDataProviderInfo> deviceDataProviderInfos) {
        Objects.requireNonNull(deviceDataOrigin);
        Objects.requireNonNull(device);
        Objects.requireNonNull(deviceDataProviderInfos);
        mDeviceDataOrigin = deviceDataOrigin;
        mDevice = device;
        mIsCurrentDevice = isCurrentDevice;
        mDeviceDataProviderInfos = List.copyOf(deviceDataProviderInfos);
    }

    /**
     * Returns the synthetic {@link DataOrigin} representing the physical device.
     *
     * <p>The DataOrigin with a synthetic package name representing the physical device.
     */
    @NonNull
    public DataOrigin getDeviceDataOrigin() {
        return mDeviceDataOrigin;
    }

    /** Returns the {@link Device} information. */
    @NonNull
    public Device getDevice() {
        return mDevice;
    }

    /**
     * Returns whether this {@link DeviceDataSourceInfo} represents the device the code is currently
     * running on.
     */
    public boolean isCurrentDevice() {
        return mIsCurrentDevice;
    }

    /** Returns the list of Device Data Providers contributing to this device. */
    @NonNull
    public List<DeviceDataProviderInfo> getDeviceDataProviderInfos() {
        return mDeviceDataProviderInfos;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeviceDataSourceInfo)) return false;
        DeviceDataSourceInfo that = (DeviceDataSourceInfo) o;
        return mIsCurrentDevice == that.mIsCurrentDevice
                && Objects.equals(mDeviceDataOrigin, that.mDeviceDataOrigin)
                && Objects.equals(mDevice, that.mDevice)
                && Objects.equals(mDeviceDataProviderInfos, that.mDeviceDataProviderInfos);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDeviceDataOrigin, mDevice, mIsCurrentDevice, mDeviceDataProviderInfos);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mDeviceDataOrigin.getPackageName());
        dest.writeParcelable(new DeviceParcel(mDevice), flags);
        dest.writeBoolean(mIsCurrentDevice);
        dest.writeTypedList(mDeviceDataProviderInfos);
    }

    @NonNull
    public static final Creator<DeviceDataSourceInfo> CREATOR =
            new Creator<>() {
                @Override
                public DeviceDataSourceInfo createFromParcel(Parcel in) {
                    return new DeviceDataSourceInfo(in);
                }

                @Override
                public DeviceDataSourceInfo[] newArray(int size) {
                    return new DeviceDataSourceInfo[size];
                }
            };

    /** @hide */
    @NonNull
    public DeviceDataSourceInfo toMasked(@NonNull Function<String, String> packageMasker) {
        DataOrigin maskedOrigin =
                new DataOrigin.Builder()
                        .setPackageName(packageMasker.apply(mDeviceDataOrigin.getPackageName()))
                        .build();
        List<DeviceDataProviderInfo> maskedProviderInfos = new ArrayList<>();
        for (DeviceDataProviderInfo info : mDeviceDataProviderInfos) {
            maskedProviderInfos.add(info.toMasked(packageMasker));
        }
        return new DeviceDataSourceInfo(
                maskedOrigin, mDevice, mIsCurrentDevice, maskedProviderInfos);
    }

    private DeviceDataSourceInfo(Parcel in) {
        mDeviceDataOrigin = new DataOrigin.Builder().setPackageName(in.readString()).build();
        mDevice =
                ((DeviceParcel) in.readParcelable(DeviceParcel.class.getClassLoader())).getDevice();
        mIsCurrentDevice = in.readBoolean();
        mDeviceDataProviderInfos = in.createTypedArrayList(DeviceDataProviderInfo.CREATOR);
    }
}
