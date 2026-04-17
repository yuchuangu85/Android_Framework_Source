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
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A set of trust tokens that share a public key.
 *
 * <p>A trust token identity set contains a "verified device" trust token and a collection of
 * identity-bound trust tokens. All trust tokens in the set are guaranteed to have the same public
 * key. This allows a device to present the verified device trust token to a peer, and then later
 * provide an identity trust token to prove an identity once more trust has been established.
 *
 * @see TrustTokenManager#getTrustTokenIdentitySet(String...)
 * @hide
 */
public final class TrustTokenIdentitySet implements Parcelable {

    private final TrustTokenWithChallenge mVerifiedDeviceToken;
    private final Map<String, TrustToken> mIdentityTokens;
    private final byte[] mSecretKey;

    /**
     * Creates a new trust token identity set.
     *
     * @param verifiedDeviceToken the "verified device" trust token.
     * @param identityTokens a map of identity strings to their corresponding trust tokens.
     * @param secretKey the secret key for identity-related cryptographic operations.
     */
    public TrustTokenIdentitySet(
            @NonNull TrustTokenWithChallenge verifiedDeviceToken,
            @NonNull Map<String, TrustToken> identityTokens,
            @NonNull byte[] secretKey) {
        mVerifiedDeviceToken = Objects.requireNonNull(verifiedDeviceToken);
        mIdentityTokens = new HashMap<>(Objects.requireNonNull(identityTokens));
        mSecretKey = Objects.requireNonNull(secretKey).clone();

        // TODO(b/418280383): Verify that all trust tokens have the same public key.
    }

    private TrustTokenIdentitySet(Parcel in) {
        mVerifiedDeviceToken = in.readTypedObject(TrustTokenWithChallenge.CREATOR);
        int size = in.readInt();
        mIdentityTokens = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            String key = in.readString();
            TrustToken value = in.readTypedObject(TrustToken.CREATOR);
            mIdentityTokens.put(key, value);
        }
        mSecretKey = in.createByteArray();
    }

    /** Returns the "verified device" trust token. */
    @NonNull
    public TrustTokenWithChallenge getVerifiedDeviceToken() {
        return mVerifiedDeviceToken;
    }

    /**
     * Returns the identity trust token for the given identity.
     *
     * @param identity the identity to look up.
     * @return the trust token, or null if no trust token is present for the given identity.
     */
    @Nullable
    public TrustToken getIdentityToken(@NonNull String identity) {
        return mIdentityTokens.get(identity);
    }

    /**
     * Returns the secret key used to generate the {@code encrypted_identity_hash} in the identity
     * trust tokens.
     *
     * <p>TODO: Figure the right way to represent the key in this API. We either need to set a
     * stable encryption algorithm (and therefore key type) in TrustToken & Transformer, or we need
     * to expose the algorithm in the identity trust token along with the encrypted identity hash.
     *
     * @return a copy of the secret key.
     */
    @NonNull
    public byte[] getSecretKey() {
        return mSecretKey.clone();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mVerifiedDeviceToken, flags);
        dest.writeInt(mIdentityTokens.size());
        for (Map.Entry<String, TrustToken> entry : mIdentityTokens.entrySet()) {
            dest.writeString(entry.getKey());
            dest.writeTypedObject(entry.getValue(), flags);
        }
        dest.writeByteArray(mSecretKey);
    }

    @NonNull
    public static final Creator<TrustTokenIdentitySet> CREATOR =
            new Creator<TrustTokenIdentitySet>() {
                @Override
                public TrustTokenIdentitySet createFromParcel(Parcel in) {
                    return new TrustTokenIdentitySet(in);
                }

                @Override
                public TrustTokenIdentitySet[] newArray(int size) {
                    return new TrustTokenIdentitySet[size];
                }
            };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TrustTokenIdentitySet)) return false;
        TrustTokenIdentitySet that = (TrustTokenIdentitySet) o;
        return mVerifiedDeviceToken.equals(that.mVerifiedDeviceToken)
                && mIdentityTokens.equals(that.mIdentityTokens)
                && Arrays.equals(mSecretKey, that.mSecretKey);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mVerifiedDeviceToken, mIdentityTokens);
        return 31 * result + Arrays.hashCode(mSecretKey);
    }
}
