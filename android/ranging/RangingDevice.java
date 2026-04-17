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

package android.ranging;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.ranging.uwb.UwbAddress;

import com.android.ranging.flags.Flags;

import java.util.Objects;
import java.util.UUID;

/**
 * Represents a ranging device identified by a unique UUID.
 *
 * <p> This class is designed for ranging operations, where each device involved in the ranging
 * session is uniquely identified by a {@link UUID}.
 *
 */
@FlaggedApi(Flags.FLAG_RANGING_STACK_ENABLED)
public final class RangingDevice implements Parcelable {
    private final UUID mId;
    private final UwbAddress mDlTdoaUwbAddress;

    private RangingDevice(Builder builder) {
        mId = builder.mId;
        mDlTdoaUwbAddress = builder.mDlTdoaUwbAddress;
    }

    private RangingDevice(Parcel in) {
        // Read the UUID as two long values (UUID is stored as two longs)
        long mostSigBits = in.readLong();
        long leastSigBits = in.readLong();
        mId = new UUID(mostSigBits, leastSigBits);
        mDlTdoaUwbAddress = in.readParcelable(UwbAddress.class.getClassLoader());
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mId.getMostSignificantBits());
        dest.writeLong(mId.getLeastSignificantBits());
        dest.writeParcelable(mDlTdoaUwbAddress, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<RangingDevice> CREATOR = new Creator<RangingDevice>() {
        @Override
        public RangingDevice createFromParcel(Parcel in) {
            return new RangingDevice(in);
        }

        @Override
        public RangingDevice[] newArray(int size) {
            return new RangingDevice[size];
        }
    };

    /**
     * Returns the UUID that uniquely identifies the ranging device.
     *
     * @return The device's {@link UUID}.
     */
    @NonNull
    public UUID getUuid() {
        return mId;
    }

    /**
     * Returns the UWB address of the anchor device for DL-TDoA ranging.
     *
     * @return The device's {@link UwbAddress}. Returns null if the device is not an anchor device
     * for DL-TDoA ranging.
     */
    @FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    @Nullable
    public UwbAddress getDlTdoaUwbAddress() {
        return mDlTdoaUwbAddress;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mDlTdoaUwbAddress);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof RangingDevice)) return false;
        RangingDevice other = (RangingDevice) obj;
        return Objects.equals(mId, other.mId)
                && Objects.equals(mDlTdoaUwbAddress, other.mDlTdoaUwbAddress);
    }

    /**
     * A builder class for creating instances of {@link RangingDevice}.
     */
    public static final class Builder {
        private UUID mId = UUID.randomUUID();
        private UwbAddress mDlTdoaUwbAddress;

        /**
         * Sets the UUID for the device.
         *
         * @param id The {@link UUID} to assign to the device.
         * @return This {@link Builder} instance.
         *
         * @throws IllegalArgumentException if the provided UUID is null.
         */
        @NonNull
        public Builder setUuid(@NonNull UUID id) {
            mId = id;
            return this;
        }

        /**
         * Sets the UWB address of the anchor device for DL-TDoA ranging.
         *
         * @param dlTdoaUwbAddress The {@link UwbAddress} of the anchor device.
         * @return This {@link Builder} instance.
         *
         * @throws IllegalArgumentException if the provided UWB address is null.
         */
        @NonNull
        public Builder setDlTdoaUwbAddress(@NonNull UwbAddress dlTdoaUwbAddress) {
            mDlTdoaUwbAddress = dlTdoaUwbAddress;
            return this;
        }

        /**
         * Builds a new instance of {@link RangingDevice} with the provided configuration.
         *
         * @return A new {@link RangingDevice} instance.
         */
        @NonNull
        public RangingDevice build() {
            return new RangingDevice(this);
        }
    }

    @Override
    public String toString() {
        return "RangingDevice{ "
                + "mId=" + mId
                + ", mDlTdoaUwbAddress=" + mDlTdoaUwbAddress
                + " }";
    }
}
