/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.security.trusttoken;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.security.Flags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The trust token requirements of the system, communicated to a {@link TrustTokenService}.
 *
 * <p>An instance of this class should contain the following data:
 *
 * <ul>
 *   <li><b>Attestation:</b> The chain of certificates from the device's KeyStore (i.e. Android
 *       Keystore).
 *   <li><b>PublicKeys:</b> A list of the device's public keys to request trust tokens for.
 *   <li><b>BatchHash:</b> The hash of the public keys.
 *   <li><b>Signature:</b> The signature of the batch hash.
 * </ul>
 *
 * <p>NOTE: An empty request (the public keys list is empty and other fields are null) is valid,
 * which indicates the system is asking the {@link TrustTokenService} to update the root keys and
 * intermediate keys only.
 *
 * <p>Instances of this class are constructed using the {@link Builder}.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_TALISMAN_SERVICE_API)
@SystemApi
public final class TrustTokenRequest implements Parcelable {
    private final List<byte[]> mAttestation;
    private final List<byte[]> mPublicKeys;
    private final byte[] mBatchHash;
    private final byte[] mSignature;

    private TrustTokenRequest(
            List<byte[]> attestation, List<byte[]> publicKeys, byte[] batchHash, byte[] signature) {
        mAttestation = attestation;
        mPublicKeys = publicKeys;
        mBatchHash = batchHash;
        mSignature = signature;
    }

    private TrustTokenRequest(Parcel in) {
        mAttestation = readByteArrayList(in);
        byte[] keysBytes = in.readBlob();
        if (keysBytes == null) {
            throw new NullPointerException("Bad keys blob");
        }
        Parcel keysParcel = Parcel.obtain();
        try {
            keysParcel.unmarshall(keysBytes, 0, keysBytes.length);
            keysParcel.setDataPosition(0);
            mPublicKeys = readByteArrayList(keysParcel);
        } finally {
            keysParcel.recycle();
        }
        mBatchHash = in.createByteArray();
        mSignature = in.createByteArray();
    }

    /**
     * Returns the chain of certificates from the device's KeyStore (i.e. Android Keystore) for
     * validating the device when requesting trust tokens.
     *
     * @return the chain of certificates from the device's KeyStore. Or an empty list if the device
     *     is not provisioned for trust tokens.
     */
    @NonNull
    public List<byte[]> getAttestation() {
        return mAttestation == null ? Collections.emptyList() : mAttestation;
    }

    /**
     * Returns the list of device's public keys to request trust tokens for.
     *
     * @return the list of device's public keys. Or an empty list if the device is not provisioned
     *     for trust tokens.
     */
    @NonNull
    public List<byte[]> getPublicKeys() {
        return mPublicKeys == null ? Collections.emptyList() : mPublicKeys;
    }

    /**
     * Returns the hash of the public keys.
     *
     * @return the hash of the public keys. Or {@code null} if the public keys list is empty.
     */
    @Nullable
    public byte[] getBatchHash() {
        return mBatchHash;
    }

    /**
     * Returns the signature of the batch hash.
     *
     * @return the signature of the batch hash. Or {@code null} if the public keys list is empty.
     */
    @Nullable
    public byte[] getSignature() {
        return mSignature;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        writeByteArrayList(dest, mAttestation);
        // NOTE: Because the list of public keys can be very large, and would exceed the Binder
        // transaction size limit. Thus, we first serialize the list into an in-memory Parcel, then
        // use writeBlob() to write the serialized blob, since the writeBlob() function internally
        // uses an anonymous shared memory for transferring data when the blob size is large.
        // See https://developer.android.com/reference/android/os/Parcel.html#writeBlob(byte[]) for
        // the details of the writeBlob() function.
        Parcel keysParcel = Parcel.obtain();
        try {
            writeByteArrayList(keysParcel, mPublicKeys);
            dest.writeBlob(keysParcel.marshall());
        } finally {
            keysParcel.recycle();
        }
        dest.writeByteArray(mBatchHash);
        dest.writeByteArray(mSignature);
    }

    private static void writeByteArrayList(Parcel dest, List<byte[]> list) {
        if (list == null) {
            dest.writeInt(0);
            return;
        }
        dest.writeInt(list.size());
        for (byte[] bytes : list) {
            dest.writeByteArray(bytes);
        }
    }

    private static List<byte[]> readByteArrayList(Parcel in) {
        int size = in.readInt();
        List<byte[]> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            byte[] b = in.createByteArray();
            if (b == null) {
                throw new NullPointerException("Bad input bytes at index [" + i + "]");
            }
            list.add(b);
        }
        return list;
    }

    public static final @NonNull Parcelable.Creator<TrustTokenRequest> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public TrustTokenRequest createFromParcel(Parcel in) {
                    return new TrustTokenRequest(in);
                }

                @Override
                public TrustTokenRequest[] newArray(int size) {
                    return new TrustTokenRequest[size];
                }
            };

    /** Builder for {@link TrustTokenRequest}. */
    public static final class Builder {
        private List<byte[]> mAttestation;
        private List<byte[]> mPublicKeys;
        private byte[] mBatchHash;
        private byte[] mSignature;

        /** Creates a new builder. */
        public Builder() {}

        /**
         * Sets the chain of certificates from the device's KeyStore (i.e. Android Keystore).
         *
         * @param attestation the chain of certificates from the device's KeyStore.
         */
        @NonNull
        public Builder setAttestation(@NonNull List<byte[]> attestation) {
            mAttestation = attestation;
            return this;
        }

        /**
         * Sets the list of device's public keys to request trust tokens for.
         *
         * @param keys the list of device's public keys.
         */
        @NonNull
        public Builder setPublicKeys(@NonNull List<byte[]> keys) {
            mPublicKeys = keys;
            return this;
        }

        /**
         * Sets the hash of the public keys.
         *
         * @param batchHash the hash of the public keys.
         */
        @FlaggedApi(Flags.FLAG_TALISMAN_SERVICE_API)
        @NonNull
        public Builder setBatchHash(@NonNull byte[] batchHash) {
            mBatchHash = batchHash;
            return this;
        }

        /**
         * Sets the signature of the batch hash.
         *
         * @param signature the signature of the batch hash.
         */
        @NonNull
        public Builder setSignature(@NonNull byte[] signature) {
            mSignature = signature;
            return this;
        }

        /**
         * Builds the {@link TrustTokenRequest} instance.
         *
         * @throws NullPointerException if any of the required fields are null.
         */
        @NonNull
        public TrustTokenRequest build() {
            if (mPublicKeys != null && !mPublicKeys.isEmpty()) {
                Objects.requireNonNull(mAttestation);
                Objects.requireNonNull(mBatchHash);
                Objects.requireNonNull(mSignature);
            }
            return new TrustTokenRequest(mAttestation, mPublicKeys, mBatchHash, mSignature);
        }
    }
}
