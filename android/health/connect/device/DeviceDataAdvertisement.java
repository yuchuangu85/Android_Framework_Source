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
package android.health.connect.device;

import static com.android.healthfitness.flags.Flags.FLAG_DEVICE_DATA_PROVIDERS_API;

import static java.util.Objects.hash;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.health.connect.HealthConnectManager;
import android.health.connect.datatypes.Device;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * A device oriented source of data. This contains metadata about the device and information about
 * each data type supported by the device.
 *
 * @hide
 */
@FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
@SystemApi
public final class DeviceDataAdvertisement implements Parcelable {

    private final Device mDevice;
    private final String mDeviceId;
    private final Set<DeviceDataTypeAdvertisement> mDeviceDataTypeAdvertisements;

    /**
     * @param device The source of the data.
     * @param deviceId The identifier for this device. Examples include a stable identifier for a
     *     physical device or shared identifier if the specific physical device does not matter
     *     (e.g. where the source of data is one of the many identical treadmills in a gym). For a
     *     unique identifier of the device that Health Connect is currently running on, use {@link
     *     HealthConnectManager#getCurrentDeviceId()}.
     * @param deviceDataTypeAdvertisements Information about each data type provided by the device.
     */
    public DeviceDataAdvertisement(
            @NonNull Device device,
            @NonNull String deviceId,
            @NonNull Set<DeviceDataTypeAdvertisement> deviceDataTypeAdvertisements) {
        Objects.requireNonNull(device);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(deviceDataTypeAdvertisements);

        mDevice = device;
        mDeviceId = deviceId;
        mDeviceDataTypeAdvertisements = Set.copyOf(deviceDataTypeAdvertisements);
    }

    /** The source of the data. */
    @NonNull
    public Device getDevice() {
        return mDevice;
    }

    /** The identifier for this device. */
    @NonNull
    public String getDeviceId() {
        return mDeviceId;
    }

    /** Information about each data type provided by the device. */
    @NonNull
    public Set<DeviceDataTypeAdvertisement> getDeviceDataTypeAdvertisements() {
        return mDeviceDataTypeAdvertisements;
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(new DeviceParcel(mDevice), flags);
        dest.writeString(mDeviceId);
        dest.writeParcelableList(mDeviceDataTypeAdvertisements.stream().toList(), flags);
    }

    @NonNull
    public static final Creator<DeviceDataAdvertisement> CREATOR =
            new Creator<DeviceDataAdvertisement>() {
                @Override
                public DeviceDataAdvertisement createFromParcel(Parcel in) {
                    return new DeviceDataAdvertisement(in);
                }

                @Override
                public DeviceDataAdvertisement[] newArray(int size) {
                    return new DeviceDataAdvertisement[size];
                }
            };

    /**
     * Creates a new copy of the advertisement with the unmasker applied to {@link #mDeviceId}
     *
     * @hide
     */
    @NonNull
    public DeviceDataAdvertisement toUnmasked(@NonNull Function<String, String> deviceIdUnmasker) {
        return new DeviceDataAdvertisement(
                mDevice, deviceIdUnmasker.apply(mDeviceId), mDeviceDataTypeAdvertisements);
    }

    private DeviceDataAdvertisement(Parcel in) {
        mDevice =
                in.readParcelable(DeviceParcel.class.getClassLoader(), DeviceParcel.class)
                        .getDevice();
        mDeviceId = in.readString();
        mDeviceDataTypeAdvertisements =
                new HashSet<>(
                        in.readParcelableList(
                                new ArrayList<>(),
                                DeviceDataTypeAdvertisement.class.getClassLoader(),
                                DeviceDataTypeAdvertisement.class));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeviceDataAdvertisement)) return false;
        DeviceDataAdvertisement that = (DeviceDataAdvertisement) o;
        return mDevice.equals(that.mDevice)
                && mDeviceId.equals(that.mDeviceId)
                && mDeviceDataTypeAdvertisements.equals(that.mDeviceDataTypeAdvertisements);
    }

    @Override
    public int hashCode() {
        return hash(mDevice, mDeviceId, mDeviceDataTypeAdvertisements);
    }
}
