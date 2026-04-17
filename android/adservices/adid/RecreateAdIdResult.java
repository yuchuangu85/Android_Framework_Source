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

package android.adservices.adid;

import static android.adservices.common.AdServicesStatusUtils.StatusCode;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Represents the result from the recreateAdId API.
 *
 * @hide
 */
public final class RecreateAdIdResult implements Parcelable {
    @StatusCode private final int mStatusCode;
    private final String mErrorMessage;
    @NonNull private final String mAdId;
    private final boolean mLimitAdTrackingEnabled;

    private RecreateAdIdResult(
            @StatusCode int statusCode,
            @Nullable String errorMessage,
            @NonNull String adId,
            boolean isLimitAdTrackingEnabled) {
        mStatusCode = statusCode;
        mErrorMessage = errorMessage;
        mAdId = adId;
        mLimitAdTrackingEnabled = isLimitAdTrackingEnabled;
    }

    private RecreateAdIdResult(@NonNull Parcel in) {
        mStatusCode = in.readInt();
        mErrorMessage = in.readString();
        mAdId = Objects.requireNonNull(in.readString());
        mLimitAdTrackingEnabled = in.readBoolean();
    }

    @NonNull
    public static final Creator<RecreateAdIdResult> CREATOR =
            new Creator<RecreateAdIdResult>() {
                @Override
                public RecreateAdIdResult createFromParcel(@NonNull Parcel in) {
                    Objects.requireNonNull(in);
                    return new RecreateAdIdResult(in);
                }

                @Override
                public RecreateAdIdResult[] newArray(int size) {
                    return new RecreateAdIdResult[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mStatusCode);
        out.writeString(mErrorMessage);
        out.writeString(mAdId);
        out.writeBoolean(mLimitAdTrackingEnabled);
    }

    /**
     * Returns the error message associated with this result.
     *
     * <p>If {@link #isSuccess} is {@code true}, the error message is always {@code null}. The error
     * message may be {@code null} even if {@link #isSuccess} is {@code false}.
     */
    @Nullable
    public String getErrorMessage() {
        return mErrorMessage;
    }

    /** Returns the advertising ID associated with this result. */
    @NonNull
    public String getAdId() {
        return mAdId;
    }

    /** Returns the Limited adtracking field associated with this result. */
    public boolean isLimitAdTrackingEnabled() {
        return mLimitAdTrackingEnabled;
    }

    @Override
    public String toString() {
        return "RecreateAdIdResult{"
                + "mResultCode="
                + mStatusCode
                + ", mErrorMessage='"
                + mErrorMessage
                + '\''
                + ", mAdId="
                + mAdId
                + ", mLimitAdTrackingEnabled="
                + mLimitAdTrackingEnabled
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof RecreateAdIdResult)) {
            return false;
        }

        RecreateAdIdResult that = (RecreateAdIdResult) o;

        return mStatusCode == that.mStatusCode
                && Objects.equals(mErrorMessage, that.mErrorMessage)
                && Objects.equals(mAdId, that.mAdId)
                && (mLimitAdTrackingEnabled == that.mLimitAdTrackingEnabled);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStatusCode, mErrorMessage, mAdId, mLimitAdTrackingEnabled);
    }

    /**
     * Builder for {@link RecreateAdIdResult} objects.
     *
     * @hide
     */
    public static final class Builder {
        @StatusCode private int mStatusCode;
        @Nullable private String mErrorMessage;
        @NonNull private String mAdId;
        private boolean mLimitAdTrackingEnabled;

        public Builder() {
            mAdId = "";
        }

        /** Set the Result Code. */
        @NonNull
        public Builder setStatusCode(@StatusCode int statusCode) {
            mStatusCode = statusCode;
            return this;
        }

        /** Set the Error Message. */
        @NonNull
        public Builder setErrorMessage(@Nullable String errorMessage) {
            mErrorMessage = errorMessage;
            return this;
        }

        /** Set the adid. */
        @NonNull
        public Builder setAdId(@NonNull String adId) {
            mAdId = adId;
            return this;
        }

        /** Set the Limited AdTracking enabled field. */
        @NonNull
        public Builder setLatEnabled(boolean isLimitAdTrackingEnabled) {
            mLimitAdTrackingEnabled = isLimitAdTrackingEnabled;
            return this;
        }

        /** Builds a {@link RecreateAdIdResult} instance. */
        @NonNull
        public RecreateAdIdResult build() {

            return new RecreateAdIdResult(
                    mStatusCode, mErrorMessage, mAdId, mLimitAdTrackingEnabled);
        }
    }
}
