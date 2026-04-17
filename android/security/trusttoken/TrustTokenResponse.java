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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.security.Flags;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Response to a {@link TrustTokenRequest}.
 *
 * <p>A response contains a list of encoded trust tokens, the root authority keys, and
 * intermediate certificates.
 *
 * <p>Instances of this class are constructed using the {@link Builder}.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_TALISMAN_SERVICE_API)
@SystemApi
public final class TrustTokenResponse implements Parcelable {
    private final List<byte[]> mEncodedTokens;
    private final List<byte[]> mRootAuthorityKeys;
    private final List<byte[]> mIntermediateCertificates;
    private Instant mUpdateTime;

    private TrustTokenResponse(
            @NonNull List<byte[]> encodedTokens,
            @NonNull List<byte[]> rootAuthorityKeys,
            @NonNull List<byte[]> intermediateCertificates,
            @NonNull Instant updateTime) {
        mEncodedTokens = encodedTokens;
        mRootAuthorityKeys = rootAuthorityKeys;
        mIntermediateCertificates = intermediateCertificates;
        mUpdateTime = updateTime;
    }

    private TrustTokenResponse(Parcel in) {
        byte[] blob = in.readBlob();
        if (blob == null) {
            throw new NullPointerException("Bad response blob");
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(blob);
                DataInputStream dis = new DataInputStream(bais)) {
            int size = dis.readInt();
            if (size < 0) {
                throw new SecurityException("Bad token encoded tokens size");
            }
            mEncodedTokens = new ArrayList<>(size);
            for (int i = 0; i < size; ++i) {
                final int tokenLength = dis.readInt();
                if (tokenLength < 0) {
                    throw new SecurityException("Bad token length");
                }
                byte[] token = new byte[tokenLength];
                dis.readFully(token);
                mEncodedTokens.add(token);
            }

            size = dis.readInt();
            if (size < 0) {
                throw new SecurityException("Bad root authority keys size");
            }
            mRootAuthorityKeys = new ArrayList<>(size);
            for (int i = 0; i < size; ++i) {
                final int keyLength = dis.readInt();
                if (keyLength < 0) {
                    throw new SecurityException("Bad root authority key length");
                }
                byte[] key = new byte[keyLength];
                dis.readFully(key);
                mRootAuthorityKeys.add(key);
            }

            size = dis.readInt();
            if (size < 0) {
                throw new SecurityException("Bad intermediate certificates size");
            }
            mIntermediateCertificates = new ArrayList<>(size);
            for (int i = 0; i < size; ++i) {
                final int certLength = dis.readInt();
                if (certLength < 0) {
                    throw new SecurityException("Bad intermediate certificate length");
                }
                byte[] cert = new byte[certLength];
                dis.readFully(cert);
                mIntermediateCertificates.add(cert);
            }
            mUpdateTime = Instant.ofEpochMilli(dis.readLong());
        } catch (IOException e) {
            throw new RuntimeException("Should not happen on BAIS", e);
        }
    }

    /**
     * Returns the list of encoded trust tokens.
     *
     * @return the list of encoded trust tokens, or an empty list if none were provided.
     */
    @NonNull
    public List<byte[]> getEncodedTokens() {
        return mEncodedTokens;
    }

    /**
     * Returns the list of root authority keys.
     *
     * @return the list of root authority keys, or an empty list if none were provided.
     */
    @NonNull
    public List<byte[]> getRootAuthorityKeys() {
        return mRootAuthorityKeys;
    }

    /**
     * Returns the list of intermediate certificates.
     *
     * @return the list of intermediate certificates, or an empty list if none were provided.
     */
    @NonNull
    public List<byte[]> getIntermediateCertificates() {
        return mIntermediateCertificates;
    }

    /**
     * Returns the update time of the response.
     *
     * @return the update time of the response.
     */
    @NonNull
    public Instant getUpdateTime() {
        return mUpdateTime;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // NOTE: Because the lists can be very large, and would exceed the Binder transaction size
        // limit. Thus, we first serialize the lists into an in-memory Parcel, then use writeBlob()
        // to write the serialized blob, since the writeBlob() function internally uses an anonymous
        // shared memory for transferring data when the blob size is large.
        // See https://developer.android.com/reference/android/os/Parcel.html#writeBlob(byte[]) for
        // the details of the writeBlob() function.
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(mEncodedTokens.size());
            for (byte[] token : mEncodedTokens) {
                dos.writeInt(token.length);
                dos.write(token);
            }

            dos.writeInt(mRootAuthorityKeys.size());
            for (byte[] key : mRootAuthorityKeys) {
                dos.writeInt(key.length);
                dos.write(key);
            }

            dos.writeInt(mIntermediateCertificates.size());
            for (byte[] cert : mIntermediateCertificates) {
                dos.writeInt(cert.length);
                dos.write(cert);
            }
            dos.writeLong(mUpdateTime.toEpochMilli());
            dos.flush();
            dest.writeBlob(baos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Should not happen on BAOS", e);
        }
    }

    public static final @NonNull Parcelable.Creator<TrustTokenResponse> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public TrustTokenResponse createFromParcel(Parcel in) {
                    return new TrustTokenResponse(in);
                }

                @Override
                public TrustTokenResponse[] newArray(int size) {
                    return new TrustTokenResponse[size];
                }
            };

    /** Builder for {@link TrustTokenResponse}. */
    public static final class Builder {
        private final List<byte[]> mEncodedTokens;
        private final List<byte[]> mRootAuthorityKeys;
        private final List<byte[]> mIntermediateCertificates;
        private Instant mUpdateTime;

        /** Creates a new builder instance. */
        public Builder() {
            mEncodedTokens = new ArrayList<>();
            mRootAuthorityKeys = new ArrayList<>();
            mIntermediateCertificates = new ArrayList<>();
            mUpdateTime = Instant.now();
        }

        /**
         * Appends an encoded trust token, which is received from the remote server.
         *
         * @param encodedToken The encoded token, must not be null.
         */
        @NonNull
        public Builder addEncodedToken(@NonNull byte[] encodedToken) {
            Objects.requireNonNull(encodedToken);
            mEncodedTokens.add(encodedToken);
            return this;
        }

        /**
         * Appends a root authority key to the response.
         *
         * @param rootAuthorityKey The key, must not be null.
         */
        @NonNull
        public Builder addRootAuthorityKey(@NonNull byte[] rootAuthorityKey) {
            Objects.requireNonNull(rootAuthorityKey);
            mRootAuthorityKeys.add(rootAuthorityKey);
            return this;
        }

        /**
         * Appends an intermediate certificate to the response.
         *
         * @param intermediateCertificate The certificate, must not be null.
         */
        @NonNull
        public Builder addIntermediateCertificate(@NonNull byte[] intermediateCertificate) {
            Objects.requireNonNull(intermediateCertificate);
            mIntermediateCertificates.add(intermediateCertificate);
            return this;
        }

        /**
         * Sets the update time of the response.
         *
         * @param updateTime The update time, must not be null.
         */
        @NonNull
        public Builder setUpdateTime(@NonNull Instant updateTime) {
            mUpdateTime = Objects.requireNonNull(updateTime);
            return this;
        }

        /**
         * Builds the {@link TrustTokenResponse}.
         *
         * @return The built {@link TrustTokenResponse}.
         */
        @NonNull
        public TrustTokenResponse build() {
            return new TrustTokenResponse(
                    mEncodedTokens, mRootAuthorityKeys, mIntermediateCertificates, mUpdateTime);
        }
    }
}
