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

package android.ranging.oob;

import android.annotation.FlaggedApi;
import android.bluetooth.BluetoothDevice;
import android.os.Parcel;
import android.os.Parcelable;
import android.ranging.RangingDevice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.ranging.flags.Flags;

import java.util.Objects;

/**
 * Represents a handle to a ranging device, containing information about the device
 * and a transport handle for out-of-band communication.
 *
 */
@FlaggedApi(Flags.FLAG_RANGING_STACK_ENABLED)
public final class DeviceHandle implements Parcelable {

    private final RangingDevice mRangingDevice;

    private final TransportHandle mTransportHandle;

    @Nullable
    private final BluetoothDevice mBluetoothDevice;

    private DeviceHandle(Builder builder) {
        mRangingDevice = builder.mRangingDevice;
        mTransportHandle = builder.mTransportHandle;
        mBluetoothDevice = builder.mBluetoothDevice;
    }

    private DeviceHandle(Parcel in) {
        mRangingDevice = in.readParcelable(RangingDevice.class.getClassLoader());
        mBluetoothDevice = in.readParcelable(BluetoothDevice.class.getClassLoader(),
                BluetoothDevice.class);
        // Not need in service layer.
        mTransportHandle = null;
    }

    @NonNull
    public static final Creator<DeviceHandle> CREATOR = new Creator<DeviceHandle>() {
        @Override
        public DeviceHandle createFromParcel(Parcel in) {
            return new DeviceHandle(in);
        }

        @Override
        public DeviceHandle[] newArray(int size) {
            return new DeviceHandle[size];
        }
    };

    /**
     * Returns the ranging device associated with this handle.
     *
     * @return The {@link RangingDevice} instance.
     */
    @NonNull
    public RangingDevice getRangingDevice() {
        return mRangingDevice;
    }

    /**
     * Returns the transport handle, if set, for communication.
     *
     * @return The {@link TransportHandle} instance.
     */
    @NonNull
    public TransportHandle getTransportHandle() {
        return mTransportHandle;
    }

    /**
     * Returns the {@link BluetoothDevice} object of the device associated with this
     * {@link DeviceHandle}. This Bluetooth Device is used for BLE RSSI and Channel Sounding
     * ranging.
     *
     * @return {@link BluetoothDevice}
     */
    @Nullable
    @FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public BluetoothDevice getBluetoothDevice() {
        return mBluetoothDevice;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mRangingDevice, flags);
        dest.writeParcelable(mBluetoothDevice, flags);
    }

    /**
     * Builder class for creating instances of {@link DeviceHandle}.
     */
    public static final class Builder {
        private RangingDevice mRangingDevice;
        private TransportHandle mTransportHandle;
        private BluetoothDevice mBluetoothDevice;

        /**
         * Constructs a new {@link Builder} with the required {@link RangingDevice}
         * and {@link TransportHandle}.
         *
         * @param rangingDevice the {@link RangingDevice}
         * @param transportHandle Implementation of {@link TransportHandle} for sending/receiving
         * OOB data from peer
         * @throws NullPointerException if either parameter is {@code null}.
         */
        public Builder(@NonNull RangingDevice rangingDevice,
                @NonNull TransportHandle transportHandle) {
            Objects.requireNonNull(rangingDevice);
            Objects.requireNonNull(transportHandle);
            mRangingDevice = rangingDevice;
            mTransportHandle = transportHandle;
            mBluetoothDevice = null;
        }

        /**
         * Sets the {@link BluetoothDevice} associated with the target device. If this is set,
         * the underlying ranging service will use the bluetooth device as the target for
         * BLE RSSI and Channel Sounding ranging.
         *
         * @param bluetoothDevice the {@link BluetoothDevice}
         * @return the same {@link DeviceHandle.Builder} instance.
         */
        @NonNull
        @FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
        public Builder setBluetoothDevice(@NonNull BluetoothDevice bluetoothDevice) {
            Objects.requireNonNull(bluetoothDevice, "bluetoothDevice cannot be null");
            mBluetoothDevice = bluetoothDevice;
            return this;
        }

        /**
         * Builds and returns a new {@link DeviceHandle} instance using the
         * parameters provided to this builder.
         *
         * @return a newly created {@link DeviceHandle} instance.
         */
        @NonNull
        public DeviceHandle build() {
            return new DeviceHandle(this);
        }
    }

    @Override
    public String toString() {
        return "DeviceHandle{ "
                + "mRangingDevice="
                + mRangingDevice
                + ", mTransportHandle="
                + mTransportHandle
                + ", mBluetoothDevice="
                + mBluetoothDevice
                + " }";
    }
}
