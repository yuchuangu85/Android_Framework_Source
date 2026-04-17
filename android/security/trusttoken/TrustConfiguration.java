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

package android.security.trusttoken;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** @hide */
public final class TrustConfiguration implements Parcelable {
    private final List<byte[]> mRootKeys;
    private final List<byte[]> mIntermediateCertificates;
    private final Instant mUpdatedAt;

    /**
     * Serializes the {@link TrustConfiguration} to a byte array.
     *
     * @return a byte array representing the serialized configuration.
     * @throws IOException if an error occurs during serialization.
     */
    public byte[] serialize() throws IOException {
        PersistableBundle bundle = toPersistableBundle();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bundle.writeToStream(bos);
        return bos.toByteArray();
    }

    /**
     * Deserializes a {@link TrustConfiguration} from a byte array.
     *
     * @param data The byte array to deserialize.
     * @return A new {@link TrustConfiguration} instance.
     * @throws IOException if an error occurs during deserialization.
     */
    public static TrustConfiguration deserialize(byte[] data) throws IOException {
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        PersistableBundle bundle = PersistableBundle.readFromStream(bis);
        return fromPersistableBundle(bundle);
    }

    public List<byte[]> getRootKeys() {
        return Collections.unmodifiableList(mRootKeys);
    }

    public List<byte[]> getIntermediateCertificates() {
        return Collections.unmodifiableList(mIntermediateCertificates);
    }

    public Instant getUpdatedAt() {
        return mUpdatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TrustConfiguration)) return false;
        TrustConfiguration that = (TrustConfiguration) o;
        if (!mUpdatedAt.equals(that.mUpdatedAt)) return false;

        if (mRootKeys.size() != that.mRootKeys.size()) return false;
        for (int i = 0; i < mRootKeys.size(); i++) {
            if (!Arrays.equals(mRootKeys.get(i), that.mRootKeys.get(i))) {
                return false;
            }
        }

        if (mIntermediateCertificates.size() != that.mIntermediateCertificates.size()) return false;
        for (int i = 0; i < mIntermediateCertificates.size(); i++) {
            if (!Arrays.equals(
                    mIntermediateCertificates.get(i), that.mIntermediateCertificates.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int rootKeysHash = 1;
        for (byte[] key : mRootKeys) {
            rootKeysHash = 31 * rootKeysHash + Arrays.hashCode(key);
        }

        int intermediateCertsHash = 1;
        for (byte[] cert : mIntermediateCertificates) {
            intermediateCertsHash = 31 * intermediateCertsHash + Arrays.hashCode(cert);
        }

        return Objects.hash(rootKeysHash, intermediateCertsHash, mUpdatedAt);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TrustConfiguration{");
        sb.append("rootKeys=[");
        for (int i = 0; i < mRootKeys.size(); i++) {
            sb.append(Arrays.toString(mRootKeys.get(i)));
            if (i < mRootKeys.size() - 1) sb.append(", ");
        }
        sb.append("], intermediateCertificates=[");
        for (int i = 0; i < mIntermediateCertificates.size(); i++) {
            sb.append(Arrays.toString(mIntermediateCertificates.get(i)));
            if (i < mIntermediateCertificates.size() - 1) sb.append(", ");
        }
        sb.append("], updatedAt=").append(mUpdatedAt);
        sb.append('}');
        return sb.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        PersistableBundle bundle = toPersistableBundle();
        dest.writePersistableBundle(bundle);
    }

    private TrustConfiguration(
            @NonNull List<byte[]> rootKeys,
            @NonNull List<byte[]> intermediateCertificates,
            @NonNull Instant updatedAt) {
        this.mRootKeys = rootKeys;
        this.mIntermediateCertificates = intermediateCertificates;
        this.mUpdatedAt = updatedAt;
    }

    private PersistableBundle toPersistableBundle() {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putLong("updatedAt", mUpdatedAt.toEpochMilli());

        String[] encodedRootKeys = new String[mRootKeys.size()];
        for (int i = 0; i < mRootKeys.size(); i++) {
            encodedRootKeys[i] = Base64.encodeToString(mRootKeys.get(i), Base64.DEFAULT);
        }
        bundle.putStringArray("rootKeys", encodedRootKeys);

        String[] encodedIntermediateCertificates = new String[mIntermediateCertificates.size()];
        for (int i = 0; i < mIntermediateCertificates.size(); i++) {
            encodedIntermediateCertificates[i] =
                    Base64.encodeToString(mIntermediateCertificates.get(i), Base64.DEFAULT);
        }
        bundle.putStringArray("intermediateCertificates", encodedIntermediateCertificates);
        return bundle;
    }

    private static TrustConfiguration fromPersistableBundle(PersistableBundle bundle) {
        Instant updatedAt = Instant.ofEpochMilli(bundle.getLong("updatedAt"));

        String[] encodedRootKeys = bundle.getStringArray("rootKeys");
        List<byte[]> rootKeys = new ArrayList<>(encodedRootKeys.length);
        for (String encodedKey : encodedRootKeys) {
            rootKeys.add(Base64.decode(encodedKey, Base64.DEFAULT));
        }

        String[] encodedIntermediateCertificates =
                bundle.getStringArray("intermediateCertificates");
        List<byte[]> intermediateCertificates =
                new ArrayList<>(encodedIntermediateCertificates.length);
        for (String encodedCert : encodedIntermediateCertificates) {
            intermediateCertificates.add(Base64.decode(encodedCert, Base64.DEFAULT));
        }

        return new TrustConfiguration(rootKeys, intermediateCertificates, updatedAt);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<TrustConfiguration> CREATOR =
            new Parcelable.Creator<TrustConfiguration>() {
                @Override
                public TrustConfiguration createFromParcel(Parcel in) {
                    PersistableBundle bundle = in.readPersistableBundle();
                    if (bundle == null) {
                        throw new IllegalArgumentException("Bundle cannot be null");
                    }
                    return TrustConfiguration.fromPersistableBundle(bundle);
                }

                @Override
                public TrustConfiguration[] newArray(int size) {
                    return new TrustConfiguration[size];
                }
            };

    /** @hide */
    public static final class Builder {
        private List<byte[]> mRootKeys = new ArrayList<>();
        private List<byte[]> mIntermediateCertificates = new ArrayList<>();
        private Instant mUpdatedAt;

        /** Adds a root key. */
        public Builder addRootKey(byte[] rootKey) {
            this.mRootKeys.add(Arrays.copyOf(rootKey, rootKey.length));
            return this;
        }

        /** Adds an intermediate certificate. */
        public Builder addIntermediateCertificate(byte[] intermediateCertificate) {
            this.mIntermediateCertificates.add(
                    Arrays.copyOf(intermediateCertificate, intermediateCertificate.length));
            return this;
        }

        /** Sets the update time of the configuration. */
        public Builder setUpdatedAt(Instant updatedAt) {
            this.mUpdatedAt = updatedAt;
            return this;
        }

        /** Builds the {@link TrustConfiguration} object. */
        public TrustConfiguration build() {
            if (mUpdatedAt == null) {
                throw new IllegalStateException("updatedAt must be set");
            }
            return new TrustConfiguration(mRootKeys, mIntermediateCertificates, mUpdatedAt);
        }
    }
}
