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
 * Response parcel of the getAdServicesModuleStates API. {@link #getModuleStates()} returns a {@link
 * SparseIntArray} that maps {@link AdServicesCommonManager.Module} to {@link
 * AdServicesCommonManager.ModuleState}
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ADSERVICES_ENABLE_PER_MODULE_OVERRIDES_API)
public final class AdServicesModuleStatesResponse implements Parcelable {

    @StatusCode private final int mStatusCode;

    private final String mErrorMessage;
    private final SparseIntArray mModuleStates;

    private AdServicesModuleStatesResponse(
            @StatusCode int statusCode,
            @Nullable String errorMessage,
            SparseIntArray moduleStates) {
        mStatusCode = statusCode;
        mErrorMessage = errorMessage;
        mModuleStates = moduleStates;
    }

    private AdServicesModuleStatesResponse(@NonNull Parcel in) {
        mStatusCode = in.readInt();
        mErrorMessage = in.readString();
        int moduleStatesSize = in.readInt();
        mModuleStates = new SparseIntArray(moduleStatesSize);
        for (int i = 0; i < moduleStatesSize; i++) {
            mModuleStates.put(in.readInt(), in.readInt());
        }
    }

    /** Returns the response status code. */
    @StatusCode
    int getStatusCode() {
        return mStatusCode;
    }

    @NonNull
    public SparseIntArray getModuleStates() {
        return mModuleStates;
    }

    @NonNull
    public static final Creator<AdServicesModuleStatesResponse> CREATOR =
            new Creator<>() {
                @Override
                public AdServicesModuleStatesResponse createFromParcel(@NonNull Parcel in) {
                    Objects.requireNonNull(in);
                    return new AdServicesModuleStatesResponse(in);
                }

                @Override
                public AdServicesModuleStatesResponse[] newArray(int size) {
                    return new AdServicesModuleStatesResponse[size];
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
        int size = mModuleStates.size();
        out.writeInt(size);
        for (int i = 0; i < size; i++) {
            out.writeInt(mModuleStates.keyAt(i));
            out.writeInt(mModuleStates.valueAt(i));
        }
    }

    @Override
    public String toString() {
        return "EnableAdServicesResponse{"
                + "mStatusCode="
                + mStatusCode
                + ", mErrorMessage="
                + mErrorMessage
                + ", mModuleStates="
                + mModuleStates
                + "'}";
    }

    /**
     * Builder for {@link AdServicesModuleStatesResponse} objects.
     *
     * @hide
     */
    public static final class Builder {
        @StatusCode private int mStatusCode = AdServicesStatusUtils.STATUS_UNSET;

        @Nullable private String mErrorMessage;

        private SparseIntArray mModuleStates;

        public Builder() {}

        /** Set the enableAdServices API response status Code. */
        @NonNull
        public AdServicesModuleStatesResponse.Builder setStatusCode(@StatusCode int statusCode) {
            mStatusCode = statusCode;
            return this;
        }

        /** Set the error messaged passed by the enableAdServices API. */
        @NonNull
        public AdServicesModuleStatesResponse.Builder setErrorMessage(
                @Nullable String errorMessage) {
            mErrorMessage = errorMessage;
            return this;
        }

        /** Set the module states. */
        @NonNull
        public AdServicesModuleStatesResponse.Builder setModuleStates(
                @Nullable SparseIntArray moduleStates) {
            mModuleStates = moduleStates;
            return this;
        }

        /**
         * Builds a {@link AdServicesModuleStatesResponse} instance.
         *
         * <p>throws IllegalArgumentException if any of the status code is null or error message is
         * not set for an unsuccessful status.
         */
        @NonNull
        public AdServicesModuleStatesResponse build() {
            Preconditions.checkArgument(
                    mStatusCode != AdServicesStatusUtils.STATUS_UNSET,
                    "Status code has not been set!");

            return new AdServicesModuleStatesResponse(mStatusCode, mErrorMessage, mModuleStates);
        }
    }
}
