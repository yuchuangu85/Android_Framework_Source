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

package android.companion;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.MacAddress;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

//TODO(b/371198526): Update Javadoc: calling createAndSetDeviceId() assigns a key.
// If the DeviceId is shared, other apps can fetch this app's associationInfo.
/**
 *  A device id represents a device identifier managed by the companion app.
 */
public final class DeviceId implements Parcelable {
    /**
     * The length limit of custom id.
     */
    private static final int CUSTOM_ID_LENGTH_LIMIT = 1024;

    private final byte[] mKey;
    private final String mCustomId;
    private final MacAddress mMacAddress;

    /**
     * @hide
     */
    private DeviceId(Builder builder) {
        mCustomId = builder.mCustomId;
        mMacAddress = builder.mMacAddress;
        mKey = builder.mKey;
    }

    /** @hide */
    @Nullable
    public String getMacAddressAsString() {
        return mMacAddress != null ? mMacAddress.toString().toUpperCase(Locale.US) : null;
    }

    /**
     * @return the custom id that managed by the companion app.
     */
    @Nullable
    public String getCustomId() {
        return mCustomId;
    }

    /**
     * @return the mac address that managed by the companion app.
     */
    @Nullable
    public MacAddress getMacAddress() {
        return mMacAddress;
    }

    /**
     * @return A system generated 128-bit key, or {@code null} if no key is assigned.
     *
     * To assign a 128-bit key to this device id, you must call
     * {@link CompanionDeviceManager#setDeviceId(int, DeviceId)}.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_ASSOCIATION_VERIFICATION)
    @Nullable
    public byte[] getKey() {
        return mKey;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        if (mCustomId != null) {
            dest.writeInt(1);
            dest.writeString8(mCustomId);
        } else {
            dest.writeInt(0);
        }
        dest.writeTypedObject(mMacAddress, 0);
        if (mKey != null) {
            dest.writeInt(1);
            dest.writeByteArray(mKey);
        } else {
            dest.writeInt(0);
        }
    }

    private DeviceId(@NonNull Parcel in) {
        int flg = in.readInt();
        if (flg == 1) {
            mCustomId = in.readString8();
        } else {
            mCustomId = null;
        }
        mMacAddress = in.readTypedObject(MacAddress.CREATOR);
        if (in.readInt() == 1) {
            mKey = in.createByteArray();
        } else {
            mKey = null;
        }
    }

    @NonNull
    public static final Parcelable.Creator<DeviceId> CREATOR =
            new Parcelable.Creator<DeviceId>() {
                @Override
                public DeviceId[] newArray(int size) {
                    return new DeviceId[size];
                }

                @Override
                public DeviceId createFromParcel(@android.annotation.NonNull Parcel in) {
                    return new DeviceId(in);
                }
            };

    @Override
    public int hashCode() {
        int result = Objects.hash(mCustomId, mMacAddress);
        result = 31 * result + Arrays.hashCode(mKey);
        return result;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof DeviceId that)) return false;

        return Objects.equals(mCustomId, that.mCustomId)
                && Objects.equals(mMacAddress, that.mMacAddress)
                && Arrays.equals(mKey, that.mKey);
    }

    @Override
    public String toString() {
        return "DeviceId{"
                + "," + "mCustomId= " + mCustomId
                + "," + "mMacAddress= " + mMacAddress
                + "}";
    }

    /**
     * A builder for {@link DeviceId}
     *
     * <p>Calling apps must provide at least one of the following to identify
     * the device: a custom ID using {@link #setCustomId(String)}, or a MAC address using
     * {@link #setMacAddress(MacAddress)}.</p>
     */
    public static final class Builder {
        private String mCustomId;
        private MacAddress mMacAddress;
        private byte[] mKey;

        public Builder() {}

        /**
         * Sets the custom device id. This id is used by the Companion app to
         * identify a specific device.
         *
         * @param customId the custom device id
         * @throws IllegalArgumentException length of the custom id must more than 1024
         * characters to save disk space.
         */
        @NonNull
        public Builder setCustomId(@Nullable String customId) {
            if (customId != null
                    && customId.length() > CUSTOM_ID_LENGTH_LIMIT) {
                throw new IllegalArgumentException("Length of the custom id must be at most "
                        + CUSTOM_ID_LENGTH_LIMIT + " characters");
            }
            mCustomId = customId;
            return this;
        }

        /**
         * Sets the mac address. This mac address is used by the Companion app to
         * identify a specific device.
         *
         * @param macAddress the remote device mac address
         * @throws IllegalArgumentException length of the custom id must more than 1024
         * characters to save disk space.
         */
        @NonNull
        public Builder setMacAddress(@Nullable MacAddress macAddress) {
            mMacAddress = macAddress;
            return this;
        }

        /** @hide */
        @NonNull
        public Builder setKey(@Nullable byte[] key) {
            mKey = key;
            return this;
        }

        @NonNull
        public DeviceId build() {
            if (mCustomId == null && mMacAddress == null) {
                throw new IllegalArgumentException("At least one device id property must be"
                        + "non-null to build a DeviceId.");
            }
            return new DeviceId(this);
        }
    }
}
