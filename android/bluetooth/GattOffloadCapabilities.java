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

package android.bluetooth;

import android.annotation.FlaggedApi;
import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresNoPermission;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.bluetooth.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * GATT offload capabilities. This class will be returned by {@link
 * BluetoothAdapter#getSupportedGattOffloadCapabilities()}.
 */
@Hide
@FlaggedApi(Flags.FLAG_GATT_OFFLOAD_API)
@SystemApi
public final class GattOffloadCapabilities {

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            flag = true,
            value = {
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PROPERTY_INDICATE,
            })
    @interface Properties {}

    private final InnerParcel mParcel;

    @Hide
    public GattOffloadCapabilities(@NonNull InnerParcel p) {
        mParcel = p;
    }

    /**
     * Checks whether the GATT client offload is supported.
     *
     * @return {@code true} if GATT client offload is supported, {@code false} otherwise
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public boolean isClientOffloadSupported() {
        return mParcel.mSupportedClientProperties != 0;
    }

    /**
     * Checks whether the GATT server offload is supported.
     *
     * @return {@code true} if GATT server offload is supported, {@code false} otherwise
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public boolean isServerOffloadSupported() {
        return mParcel.mSupportedServerProperties != 0;
    }

    /**
     * Get the supported offloaded GATT client properties.
     *
     * @return supported GATT client properties
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public @Properties int getSupportedClientProperties() {
        return mParcel.mSupportedClientProperties;
    }

    /**
     * Get the supported offloaded GATT server properties.
     *
     * @return supported GATT server properties
     */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public @Properties int getSupportedServerProperties() {
        return mParcel.mSupportedServerProperties;
    }

    @Override
    public String toString() {
        return "GattOffloadCapabilities{supportedClientProperties= "
                + mParcel.mSupportedClientProperties
                + ", supportedServerProperties= "
                + mParcel.mSupportedServerProperties
                + '}';
    }

    @Hide
    public static final class InnerParcel implements Parcelable {
        private final @Properties int mSupportedClientProperties;
        private final @Properties int mSupportedServerProperties;

        public InnerParcel(@Properties int clientProperties, @Properties int serverProperties) {
            mSupportedClientProperties = clientProperties;
            mSupportedServerProperties = serverProperties;
        }

        /**
         * @return the {@link GattOffloadCapabilities} associated with this parcel
         */
        @RequiresNoPermission
        public @NonNull GattOffloadCapabilities toGattOffloadCapabilities() {
            return new GattOffloadCapabilities(this);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeInt(mSupportedClientProperties);
            out.writeInt(mSupportedServerProperties);
        }

        public static final @NonNull Parcelable.Creator<InnerParcel> CREATOR =
                new Creator<InnerParcel>() {
                    @Override
                    public InnerParcel createFromParcel(Parcel in) {
                        return new InnerParcel(in.readInt(), in.readInt());
                    }

                    @Override
                    public InnerParcel[] newArray(int size) {
                        return new InnerParcel[size];
                    }
                };
    }
}
