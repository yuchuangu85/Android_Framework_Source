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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.bluetooth.BluetoothDevice.PairingAlgorithm;
import android.bluetooth.BluetoothDevice.PairingVariant;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.bluetooth.flags.Flags;

/**
 * Represents the bond status of a Bluetooth device.
 *
 * <p>This class is used to hold the bond status details like pairing algorithm and variant of a
 * Bluetooth device.
 */
@FlaggedApi(Flags.FLAG_ENABLE_GET_BOND_STATUS)
public final class BondStatus {
    private final InnerParcel mParcel;

    /**
     * Creates a new {@link BondStatus} object.
     *
     * @param pairingAlgorithm the pairing algorithm used to create the bond with the device.
     * @param pairingVariant the pairing variant used to create the bond with the device.
     */
    public BondStatus(@PairingAlgorithm int pairingAlgorithm, @PairingVariant int pairingVariant) {
        mParcel = new InnerParcel(pairingAlgorithm, pairingVariant);
    }

    private BondStatus(InnerParcel p) {
        mParcel = p;
    }

    @Hide
    @RequiresNoPermission
    public InnerParcel getParcel() {
        return mParcel;
    }

    /**
     * @return the {@link BondStatus} associated with this parcel
     */
    @FlaggedApi(Flags.FLAG_ENABLE_GET_BOND_STATUS)
    @RequiresNoPermission
    static @Nullable BondStatus fromParcel(InnerParcel parcel) {
        if (parcel == null) {
            return null;
        }
        return new BondStatus(parcel);
    }

    /**
     * @return the pairing algorithm used to create the bond with the device.
     */
    @RequiresNoPermission
    public @PairingAlgorithm int getPairingAlgorithm() {
        return mParcel.mPairingAlgorithm;
    }

    /**
     * @return the pairing variant used to create the bond with the device.
     */
    @RequiresNoPermission
    public @PairingVariant int getPairingVariant() {
        return mParcel.mPairingVariant;
    }

    @Override
    public String toString() {
        return ("BondStatus{pairingAlgorithm=" + mParcel.mPairingAlgorithm)
                + (", pairingVariant=" + mParcel.mPairingVariant + ")");
    }

    @Hide
    public static final class InnerParcel implements Parcelable {
        private final @PairingAlgorithm int mPairingAlgorithm;
        private final @PairingVariant int mPairingVariant;

        private InnerParcel(@NonNull Parcel in) {
            this(in.readInt(), in.readInt());
        }

        public InnerParcel(
                @PairingAlgorithm int pairingAlgorithm, @PairingVariant int pairingVariant) {
            mPairingAlgorithm = pairingAlgorithm;
            mPairingVariant = pairingVariant;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeInt(mPairingAlgorithm);
            out.writeInt(mPairingVariant);
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
