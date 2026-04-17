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

import java.util.Arrays;
import java.util.Objects;

/**
 * A trust token, which is a cryptographically-verifiable claim about the device or user.
 *
 * <p>A trust token is a CBOR Web Token (CWT) that contains claims about the device, and is signed
 * by a key trusted by the system. It can be used to prove properties of the device to a remote
 * party.
 *
 * <p>Instances of this class are obtained from {@link TrustTokenManager}.
 *
 * @hide
 */
public final class TrustToken implements Parcelable {

    private final byte[] mEncodedToken;

    public TrustToken(@NonNull byte[] encodedToken) {
        mEncodedToken = Objects.requireNonNull(encodedToken).clone();
    }

    private TrustToken(Parcel in) {
        mEncodedToken = in.createByteArray();
    }

    /** Returns a copy of the encoded form of the trust token. */
    @NonNull
    public byte[] encoded() {
        return mEncodedToken.clone();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeByteArray(mEncodedToken);
    }

    @NonNull
    public static final Creator<TrustToken> CREATOR =
            new Creator<TrustToken>() {
                @Override
                public TrustToken createFromParcel(Parcel in) {
                    return new TrustToken(in);
                }

                @Override
                public TrustToken[] newArray(int size) {
                    return new TrustToken[size];
                }
            };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TrustToken)) return false;
        TrustToken that = (TrustToken) o;
        return Arrays.equals(mEncodedToken, that.mEncodedToken);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(mEncodedToken);
    }
}
