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

package android.hardware.serial;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class containing Serial port info.
 *
 * @hide
 */
public class SerialPortInfo implements Parcelable {

    private final @NonNull String mName;
    private final int mVendorId;
    private final int mProductId;

    public SerialPortInfo(@NonNull String name, int vendorId, int productId) {
        mName = name;
        mVendorId = vendorId;
        mProductId = productId;
    }

    /**
     * Get the device name. It is the dev node name under /dev, e.g. ttyUSB0, ttyACM1.
     */
    public @NonNull String getName() {
        return mName;
    }

    /**
     * Return the vendor ID of this serial port if it is a USB device. Otherwise, it
     * returns {@link SerialPort#INVALID_ID}.
     */
    public int getVendorId() {
        return mVendorId;
    }

    /**
     * Return the product ID of this serial port if it is a USB device. Otherwise, it
     * returns {@link SerialPort#INVALID_ID}.
     */
    public int getProductId() {
        return mProductId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeString8(mName);
        parcel.writeInt(mVendorId);
        parcel.writeInt(mProductId);
    }

    public static final @NonNull Creator<SerialPortInfo> CREATOR =
            new Creator<>() {
                @Override
                public SerialPortInfo createFromParcel(Parcel in) {
                    String name = in.readString8();
                    int vendorId = in.readInt();
                    int productId = in.readInt();
                    return new SerialPortInfo(name, vendorId, productId);
                }

                @Override
                public SerialPortInfo[] newArray(int size) {
                    return new SerialPortInfo[size];
                }
            };

}
