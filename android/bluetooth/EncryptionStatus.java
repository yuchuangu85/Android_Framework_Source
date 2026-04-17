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

import android.annotation.Hide;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.bluetooth.BluetoothDevice.EncryptionAlgorithm;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents the encryption status of a Bluetooth device.
 *
 * <p>This class is used to hold the encryption status details like key size and algorithm of a
 * Bluetooth device.
 */
public final class EncryptionStatus {
    private final InnerParcel mParcel;

    public EncryptionStatus(
            @IntRange(from = 1, to = 16) int keySize, @EncryptionAlgorithm int algorithm) {
        mParcel = new InnerParcel(keySize, algorithm);
    }

    private EncryptionStatus(InnerParcel p) {
        mParcel = p;
    }

    @Hide
    @RequiresNoPermission
    public InnerParcel getParcel() {
        return mParcel;
    }

    /**
     * @return the {@link EncryptionStatus} associated with this parcel
     */
    @RequiresNoPermission
    static @Nullable EncryptionStatus fromParcel(InnerParcel parcel) {
        if (parcel == null) {
            return null;
        }
        return new EncryptionStatus(parcel);
    }

    /**
     * @return the size of the encryption key, in number of bytes. i.e. value of 16 means 16-octets,
     *     or 128 bit key size.
     */
    @RequiresNoPermission
    public @IntRange(from = 1, to = 16) int getKeySize() {
        return mParcel.mKeySize;
    }

    /**
     * @return the encryption algorithm used for the encrypting the link.
     */
    @RequiresNoPermission
    public @EncryptionAlgorithm int getAlgorithm() {
        return mParcel.mAlgorithm;
    }

    @Override
    public String toString() {
        return ("EncryptionStatus{keySize=" + mParcel.mKeySize)
                + (", algorithm=" + mParcel.mAlgorithm + "]");
    }

    @Hide
    public static final class InnerParcel implements Parcelable {
        private final int mKeySize;
        private final int mAlgorithm;

        private InnerParcel(@NonNull Parcel in) {
            this(in.readInt(), in.readInt());
        }

        public InnerParcel(int keySize, int algorithm) {
            mKeySize = keySize;
            mAlgorithm = algorithm;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeInt(mKeySize);
            out.writeInt(mAlgorithm);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        /** {@link Parcelable.Creator} interface implementation. */
        public static final @NonNull Parcelable.Creator<InnerParcel> CREATOR =
                new Parcelable.Creator<InnerParcel>() {
                    public @NonNull InnerParcel createFromParcel(Parcel in) {
                        return new InnerParcel(in);
                    }

                    public @NonNull InnerParcel[] newArray(int size) {
                        return new InnerParcel[size];
                    }
                };
    }
}
