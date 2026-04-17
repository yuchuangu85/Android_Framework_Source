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
 * A trust token and the challenge response.
 *
 * <p>Instances of this class are obtained from {@link TrustTokenManager}.
 *
 * @hide
 */
public final class TrustTokenWithChallenge implements Parcelable {

    private final @NonNull TrustToken mToken;
    private final @NonNull byte[] mChallengeResponse;

    public TrustTokenWithChallenge(@NonNull TrustToken token, @NonNull byte[] challengeResponse) {
        this.mToken = token;
        this.mChallengeResponse = challengeResponse;
    }

    private TrustTokenWithChallenge(@NonNull Parcel in) {
        mToken = in.readTypedObject(TrustToken.CREATOR);
        mChallengeResponse = in.createByteArray();
    }

    /** Returns the {@link TrustToken}. */
    public @NonNull TrustToken getToken() {
        return mToken;
    }

    /** Returns the challenge response bytes. */
    public @NonNull byte[] getChallengeResponse() {
        return mChallengeResponse;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mToken, flags);
        dest.writeByteArray(mChallengeResponse);
    }

    public static final @NonNull Parcelable.Creator<TrustTokenWithChallenge> CREATOR =
            new Parcelable.Creator<TrustTokenWithChallenge>() {
                @Override
                public TrustTokenWithChallenge createFromParcel(@NonNull Parcel in) {
                    return new TrustTokenWithChallenge(in);
                }

                @Override
                public TrustTokenWithChallenge[] newArray(int size) {
                    return new TrustTokenWithChallenge[size];
                }
            };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrustTokenWithChallenge that = (TrustTokenWithChallenge) o;
        return mToken.equals(that.mToken)
                && Arrays.equals(mChallengeResponse, that.mChallengeResponse);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mToken, Arrays.hashCode(mChallengeResponse));
    }
}
