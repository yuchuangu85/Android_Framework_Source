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

package android.provider;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents a failure that occurred during a media search.
 * @hide
 */
@FlaggedApi(com.android.providers.media.flags.Flags.FLAG_ENABLE_MEDIA_SEARCH)
@SystemApi
public final class SearchMediaException extends Throwable implements Parcelable {

    /**
     * An unknown error occurred.
     */
    public static final int ERROR_UNKNOWN = 0;

    /**
     * The search was called with invalid or malformed arguments.
     */
    public static final int ERROR_INVALID_ARGUMENTS = 1;

    /**
     * An error occurred during an I/O operation (e.g., network or disk).
     */
    public static final int ERROR_IO = 2;

    /**
     * A security or permission-related error occurred.
     */
    public static final int ERROR_SECURITY = 3;

    /**
     * Defines the possible error codes.
     *
     * @hide
     **/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            ERROR_UNKNOWN,
            ERROR_INVALID_ARGUMENTS,
            ERROR_IO,
            ERROR_SECURITY
    })
    public @interface ErrorCode {}


    private final String mSearchId;
    private final String mErrorMessage;
    private final int mErrorCode;
    private final boolean mRetryable;

    public SearchMediaException(@NonNull String searchId, @NonNull String errorMessage,
            @ErrorCode int errorCode, boolean retryable) {
        super(errorMessage);
        mSearchId = searchId;
        mErrorMessage = errorMessage;
        mErrorCode = errorCode;
        mRetryable = retryable;
    }


    private SearchMediaException(Parcel in) {
        mSearchId = in.readString();
        mErrorMessage = in.readString();
        mErrorCode = in.readInt();
        mRetryable = in.readByte() != 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mSearchId);
        dest.writeString(mErrorMessage);
        dest.writeInt(mErrorCode);
        dest.writeByte((byte) (mRetryable ? 1 : 0));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<SearchMediaException> CREATOR =
            new Creator<SearchMediaException>() {
                @Override
                public SearchMediaException createFromParcel(Parcel in) {
                    return new SearchMediaException(in);
                }

                @Override
                public SearchMediaException[] newArray(int size) {
                    return new SearchMediaException[size];
                }
            };

    /** The machine-readable error code for this failure. */
    @NonNull public String getSearchId() {
        return mSearchId;
    }

    /** The machine-readable error code for this failure. */
    public @ErrorCode int getErrorCode() {
        return mErrorCode;
    }

    /** Returns {@code true} if the caller should retry the query. */
    public boolean isRetryable() {
        return mRetryable;
    }

    /** The human-readable error message. */
    @NonNull public String getErrorMessage() {
        return mErrorMessage;
    }
}
