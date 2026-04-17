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

package android.adservices.common;

import static android.adservices.common.AdServicesStatusUtils.StatusCode;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseIntArray;

import com.android.adservices.flags.Flags;
import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Response parcel of the getAdServicesModuleUserChoices API. {@link #getUserChoices()} returns a
 * {@link SparseIntArray} that maps {@link AdServicesCommonManager.Module} to {@link
 * AdServicesCommonManager.ModuleUserChoice}
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ADSERVICES_ENABLE_PER_MODULE_OVERRIDES_API)
public final class AdServicesUserChoicesResponse implements Parcelable {

    @StatusCode private final int mStatusCode;

    private final String mErrorMessage;
    private final SparseIntArray mUserChoices;

    private AdServicesUserChoicesResponse(
            @StatusCode int statusCode, @Nullable String errorMessage, SparseIntArray userChoices) {
        mStatusCode = statusCode;
        mErrorMessage = errorMessage;
        mUserChoices = userChoices;
    }

    private AdServicesUserChoicesResponse(@NonNull Parcel in) {
        mStatusCode = in.readInt();
        mErrorMessage = in.readString();
        int userChoicesSize = in.readInt();
        mUserChoices = new SparseIntArray(userChoicesSize);
        for (int i = 0; i < userChoicesSize; i++) {
            mUserChoices.put(in.readInt(), in.readInt());
        }
    }

    /** Returns the response status code. */
    @StatusCode
    int getStatusCode() {
        return mStatusCode;
    }

    @NonNull
    public SparseIntArray getUserChoices() {
        return mUserChoices;
    }

    @NonNull
    public static final Creator<AdServicesUserChoicesResponse> CREATOR =
            new Creator<>() {
                @Override
                public AdServicesUserChoicesResponse createFromParcel(@NonNull Parcel in) {
                    Objects.requireNonNull(in);
                    return new AdServicesUserChoicesResponse(in);
                }

                @Override
                public AdServicesUserChoicesResponse[] newArray(int size) {
                    return new AdServicesUserChoicesResponse[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        Objects.requireNonNull(out);

        out.writeInt(mStatusCode);
        out.writeString(mErrorMessage);
        int size = mUserChoices.size();
        out.writeInt(size);
        for (int i = 0; i < size; i++) {
            out.writeInt(mUserChoices.keyAt(i));
            out.writeInt(mUserChoices.valueAt(i));
        }
    }

    @Override
    public String toString() {
        return "EnableAdServicesResponse{"
                + "mStatusCode="
                + mStatusCode
                + ", mErrorMessage="
                + mErrorMessage
                + ", mUserChoices="
                + mUserChoices
                + "'}";
    }

    /**
     * Builder for {@link AdServicesUserChoicesResponse} objects.
     *
     * @hide
     */
    public static final class Builder {
        @StatusCode private int mStatusCode = AdServicesStatusUtils.STATUS_UNSET;

        @Nullable private String mErrorMessage;

        private SparseIntArray mUserChoices;

        public Builder() {}

        /** Set the enableAdServices API response status Code. */
        @NonNull
        public AdServicesUserChoicesResponse.Builder setStatusCode(@StatusCode int statusCode) {
            mStatusCode = statusCode;
            return this;
        }

        /** Set the error messaged passed by the enableAdServices API. */
        @NonNull
        public AdServicesUserChoicesResponse.Builder setErrorMessage(
                @Nullable String errorMessage) {
            mErrorMessage = errorMessage;
            return this;
        }

        /** Set the user choices. */
        @NonNull
        public AdServicesUserChoicesResponse.Builder setUserChoices(
                @Nullable SparseIntArray userChoices) {
            mUserChoices = userChoices;
            return this;
        }

        /**
         * Builds a {@link AdServicesUserChoicesResponse} instance.
         *
         * <p>throws IllegalArgumentException if any of the status code is null or error message is
         * not set for an unsuccessful status.
         */
        @NonNull
        public AdServicesUserChoicesResponse build() {
            Preconditions.checkArgument(
                    mStatusCode != AdServicesStatusUtils.STATUS_UNSET,
                    "Status code has not been set!");

            return new AdServicesUserChoicesResponse(mStatusCode, mErrorMessage, mUserChoices);
        }
    }
}
