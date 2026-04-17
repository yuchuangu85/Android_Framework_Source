/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.content.pm.verify.developer;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.pm.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class is used by the developer verifier to describe the status of the verification request,
 * whether it's successful or it has failed along with any relevant details.
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_VERIFICATION_SERVICE)
public final class DeveloperVerificationStatus implements Parcelable {
    /**
     * The verification status of the App Metadata associated with the app has not been
     * determined.
     * <p>This happens in situations where the verification
     * service is not monitoring App Metadata for the app, and means the App Metadata of the app is
     * not necessarily bad but can't be trusted.
     * </p>
     */
    public static final int APP_METADATA_VERIFICATION_STATUS_UNDEFINED = 0;

    /**
     * The app's App Metadata is considered to be in a good state. This can be used by
     * the system to inform the user that the App Metadata of the app can be trusted.
     */
    public static final int APP_METADATA_VERIFICATION_STATUS_GOOD = 1;

    /**
     * There is something bad in the app's App Metadata. This can be used by the system to warn
     * the user or make appropriate decisions about the app.
     */
    public static final int APP_METADATA_VERIFICATION_STATUS_BAD = 2;

    /** @hide */
    @IntDef(prefix = {"APP_METADATA_VERIFICATION_STATUS_"}, value = {
            APP_METADATA_VERIFICATION_STATUS_UNDEFINED,
            APP_METADATA_VERIFICATION_STATUS_GOOD,
            APP_METADATA_VERIFICATION_STATUS_BAD,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AppMetadataVerificationStatus {}

    private final boolean mIsVerified;
    private final boolean mIsLiteVerification;
    private final @AppMetadataVerificationStatus int mAppMetadataVerificationStatus;
    @Nullable
    private final String mFailuresMessage;

    private DeveloperVerificationStatus(boolean isVerified, boolean isLiteVerification,
            @AppMetadataVerificationStatus int appMetadataVerificationStatus,
            @Nullable String failuresMessage) {
        mIsVerified = isVerified;
        mIsLiteVerification = isLiteVerification;
        mAppMetadataVerificationStatus = appMetadataVerificationStatus;
        mFailuresMessage = failuresMessage;
    }

    /**
     * @return whether the status is set to verified or not.
     */
    public boolean isVerified() {
        return mIsVerified;
    }

    /**
     * @return true when the only the lite variation of the verification was conducted.
     */
    public boolean isLiteVerification() {
        return mIsLiteVerification;
    }

    /**
     * @return the developer-facing failure message associated with the failure status.
     * Null if there is no failure.
     */
    @Nullable
    public String getFailureMessage() {
        return mFailuresMessage;
    }

    /**
     * @return the verification status of the App Metadata associated with the app.
     */
    public @AppMetadataVerificationStatus int getAppMetadataVerificationStatus() {
        return mAppMetadataVerificationStatus;
    }

    /**
     * Builder to construct a {@link DeveloperVerificationStatus} object.
     */
    public static final class Builder {
        private boolean mIsVerified = false;
        private boolean mIsLiteVerification = false;
        private @AppMetadataVerificationStatus int mAppMetadataVerificationStatus =
                APP_METADATA_VERIFICATION_STATUS_UNDEFINED;
        private @Nullable String mFailuresMessage = null;

        /**
         * Set in the status whether the verification has succeeded or failed.
         */
        @NonNull
        public Builder setVerified(boolean isVerified) {
            mIsVerified = isVerified;
            return this;
        }

        /**
         * Set in the status whether the lite variation of the verification was conducted
         * instead of the full verification.
         */
        @NonNull
        public Builder setLiteVerification(boolean isLiteVerification) {
            mIsLiteVerification = isLiteVerification;
            return this;
        }

        /**
         * Set a developer-facing failure message to include in the verification failure status.
         */
        @NonNull
        public Builder setFailureMessage(@Nullable String failureMessage) {
            mFailuresMessage = failureMessage;
            return this;
        }

        /**
         * Set the verification status of the App Metadata associated with the app, which can be
         * one of {@link #APP_METADATA_VERIFICATION_STATUS_UNDEFINED},
         * {@link #APP_METADATA_VERIFICATION_STATUS_GOOD},
         * or {@link #APP_METADATA_VERIFICATION_STATUS_BAD}.
         * @see <a href="https://developer.android.com/about/versions/14/features/app-metadata">
         * the Android Developer Site</a> for more information on App Metadata.
         */
        @NonNull
        public Builder setAppMetadataVerificationStatus(
                @AppMetadataVerificationStatus int appMetadataVerificationStatus) {
            this.mAppMetadataVerificationStatus = appMetadataVerificationStatus;
            return this;
        }

        /**
         * Build the status object.
         */
        @NonNull
        public DeveloperVerificationStatus build() {
            return new DeveloperVerificationStatus(mIsVerified, mIsLiteVerification,
                    mAppMetadataVerificationStatus,
                    mFailuresMessage);
        }
    }

    private DeveloperVerificationStatus(Parcel in) {
        mIsVerified = in.readBoolean();
        mIsLiteVerification = in.readBoolean();
        mAppMetadataVerificationStatus = in.readInt();
        mFailuresMessage = in.readString8();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mIsVerified);
        dest.writeBoolean(mIsLiteVerification);
        dest.writeInt(mAppMetadataVerificationStatus);
        dest.writeString8(mFailuresMessage);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<DeveloperVerificationStatus> CREATOR = new Creator<>() {
        @Override
        public DeveloperVerificationStatus createFromParcel(@NonNull Parcel in) {
            return new DeveloperVerificationStatus(in);
        }

        @Override
        public DeveloperVerificationStatus[] newArray(int size) {
            return new DeveloperVerificationStatus[size];
        }
    };
}
