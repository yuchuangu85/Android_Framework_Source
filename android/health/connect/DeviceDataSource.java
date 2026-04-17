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
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.datatypes.Device;
import android.health.connect.device.DeviceParcel;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Represents a device that acts as a source of health and fitness data. */
@FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
public final class DeviceDataSource implements Parcelable {
    private final DataOrigin mDeviceDataOrigin;
    private final Device mDevice;
    private final Set<DeviceDataTypeSource> mDataTypes;

    /**
     * @param deviceDataOrigin The origin of the device data, i.e. the synthetic package name.
     * @param device The device metadata.
     * @param dataTypes The set of data types provided by this device.
     */
    public DeviceDataSource(
            @NonNull DataOrigin deviceDataOrigin,
            @NonNull Device device,
            @NonNull Set<DeviceDataTypeSource> dataTypes) {
        Objects.requireNonNull(deviceDataOrigin);
        Objects.requireNonNull(device);
        Objects.requireNonNull(dataTypes);
        mDeviceDataOrigin = deviceDataOrigin;
        mDevice = device;
        mDataTypes = Set.copyOf(dataTypes);
    }

    /** Returns the {@link DataOrigin} of the device, containing the synthetic package name. */
    @NonNull
    public DataOrigin getDeviceDataOrigin() {
        return mDeviceDataOrigin;
    }

    /** Returns the {@link Device} metadata. */
    @NonNull
    public Device getDevice() {
        return mDevice;
    }

    /** Returns the set of data types provided by this device. */
    @NonNull
    public Set<DeviceDataTypeSource> getDeviceDataTypeSources() {
        return mDataTypes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeviceDataSource that)) return false;
        return Objects.equals(mDeviceDataOrigin, that.mDeviceDataOrigin)
                && Objects.equals(mDevice, that.mDevice)
                && Objects.equals(mDataTypes, that.mDataTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDeviceDataOrigin, mDevice, mDataTypes);
    }

    /** @hide */
    @NonNull
    public DeviceDataSource toMasked(@NonNull Function<String, String> packageMasker) {
        DataOrigin maskedOrigin =
                new DataOrigin.Builder()
                        .setPackageName(packageMasker.apply(mDeviceDataOrigin.getPackageName()))
                        .build();
        return new DeviceDataSource(maskedOrigin, mDevice, mDataTypes);
    }

    @Override
    public String toString() {
        return "DeviceDataSource{"
                + "mDeviceDataOrigin="
                + mDeviceDataOrigin
                + ", mDevice="
                + mDevice
                + ", mDataTypes="
                + mDataTypes
                + '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mDeviceDataOrigin.getPackageName());
        dest.writeParcelable(new DeviceParcel(mDevice), flags);
        dest.writeTypedList(new ArrayList<>(mDataTypes));
    }

    @NonNull
    public static final Creator<DeviceDataSource> CREATOR =
            new Creator<>() {
                @Override
                public DeviceDataSource createFromParcel(Parcel in) {
                    return new DeviceDataSource(in);
                }

                @Override
                public DeviceDataSource[] newArray(int size) {
                    return new DeviceDataSource[size];
                }
            };

    private DeviceDataSource(Parcel in) {
        mDeviceDataOrigin = new DataOrigin.Builder().setPackageName(in.readString()).build();
        mDevice =
                ((DeviceParcel) in.readParcelable(DeviceParcel.class.getClassLoader())).getDevice();
        List<DeviceDataTypeSource> dataTypesList = new ArrayList<>();
        in.readTypedList(dataTypesList, DeviceDataTypeSource.CREATOR);
        mDataTypes = Collections.unmodifiableSet(new HashSet<>(dataTypesList));
    }
}
