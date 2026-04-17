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


import android.annotation.NonNull;
import android.health.connect.datatypes.Device;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A {@link Parcelable} that reads and writes a {@link Device}.
 *
 * @hide
 */
public final class DeviceParcel implements Parcelable {

    @NonNull private final Device mDevice;

    /** @hide */
    public DeviceParcel(@NonNull Device device) {
        mDevice = device;
    }

    /** @hide */
    @NonNull
    public Device getDevice() {
        return mDevice;
    }

    /** @hide */
    @NonNull
    public static final Creator<DeviceParcel> CREATOR =
            new Creator<DeviceParcel>() {
                @Override
                public DeviceParcel createFromParcel(Parcel in) {
                    return new DeviceParcel(in);
                }

                @Override
                public DeviceParcel[] newArray(int size) {
                    return new DeviceParcel[size];
                }
            };

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mDevice.getManufacturer());
        dest.writeString(mDevice.getModel());
        dest.writeInt(mDevice.getType());
        dest.writeString(mDevice.getDisplayName());
    }

    private DeviceParcel(Parcel in) {
        String manufacturer = in.readString();
        String model = in.readString();
        int type = in.readInt();
        String displayName = in.readString();
        mDevice =
                new Device.Builder()
                        .setManufacturer(manufacturer)
                        .setModel(model)
                        .setType(type)
                        .setDisplayName(displayName)
                        .build();
    }
}
