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

package android.provider.mediaprocessingservice;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.android.providers.media.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Represents an error message from the media processing service, indicating
 * whether the operation is suitable for a retry.
 * @hide
 */

@SystemApi
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@FlaggedApi(Flags.FLAG_ENABLE_MEDIA_PROCESSING_SERVICE)
public final class ErrorMessage extends Throwable implements Parcelable {
    @ErrorCode
    private final int mErrorCode;
    private final String mErrorMessage;
    private final boolean mRetryable;

    /**
     * Defines the possible error codes.
     *
     * @hide
     **/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {ErrorCode.ERROR_UNKNOWN, ErrorCode.ERROR_INVALID_ARGUMENTS, ErrorCode.ERROR_IO,
            ErrorCode.ERROR_SECURITY})
    public @interface ErrorCode {
        /** An unknown error occurred. */
        int ERROR_UNKNOWN = 0;

        /** The search was called with invalid or malformed arguments. */
        int ERROR_INVALID_ARGUMENTS = 1;

        /** An error occurred during an I/O operation (e.g., network or disk). */
        int ERROR_IO = 2;

        /** A security or permission-related error occurred. */
        int ERROR_SECURITY = 3;
    }

    /**
     * Constructs a new {@link ErrorMessage}.
     *
     * @param errorCode    The specific error code from {@link ErrorCode} associated with the
     *                     failure.
     * @param errorMessage A human-readable description of the error.
     * @param isRetryable  Whether the operation that caused this error is transient and can be
     *                     retried.
     */
    public ErrorMessage(@ErrorCode int errorCode, @NonNull String errorMessage,
            boolean isRetryable) {
        Objects.requireNonNull(errorMessage);

        this.mErrorCode = errorCode;
        this.mErrorMessage = errorMessage;
        this.mRetryable = isRetryable;
    }

    private ErrorMessage(Parcel in) {
        @ErrorCode int errorCode = in.readInt();
        this.mErrorCode = errorCode;
        this.mErrorMessage = Objects.requireNonNull(in.readString());
        this.mRetryable = in.readBoolean();
    }

    /** Returns the error code. */
    @ErrorCode
    public int getErrorCode() {
        return mErrorCode;
    }

    /** Returns the human-readable string describing the error. */
    @NonNull
    @Override
    public String getMessage() {
        return mErrorMessage;
    }

    /** Returns whether the failed operation should be retried. */
    public boolean isRetryable() {
        return mRetryable;
    }

    @NonNull
    public static final Creator<ErrorMessage> CREATOR = new Creator<ErrorMessage>() {
        @Override
        public ErrorMessage createFromParcel(Parcel in) {
            return new ErrorMessage(in);
        }

        @Override
        public ErrorMessage[] newArray(int size) {
            return new ErrorMessage[size];
        }
    };

    /**
     * {@inheritDoc}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @param dest  The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        Objects.requireNonNull(dest);
        dest.writeInt(mErrorCode);
        dest.writeString(mErrorMessage);
        dest.writeBoolean(mRetryable);
    }
}
