/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.os.storage.operations;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

/** Result of an operation enqueue request. */
@FlaggedApi(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
public final class FileOperationEnqueueResult implements Parcelable {
    private final String mRequestId;
    private final int mErrorCode;
    private final boolean mIsSuccessful;

    /**
     * Creates a successful result.
     *
     * @param requestId The id that corresponds to the related {@link FileOperationRequest}.
     * @hide
     */
    @TestApi
    public FileOperationEnqueueResult(@NonNull String requestId) {
        mRequestId = requestId;
        mIsSuccessful = true;
        mErrorCode = FileOperationResult.ERROR_NONE;
    }

    /**
     * Creates a failure result.
     *
     * @param errorCode The error code explaining why the enqueue failed.
     * @hide
     */
    @TestApi
    public FileOperationEnqueueResult(int errorCode) {
        mRequestId = null;
        mIsSuccessful = false;
        mErrorCode = errorCode;
    }

    private FileOperationEnqueueResult(Parcel in) {
        mRequestId = in.readString8();
        mErrorCode = in.readInt();
        mIsSuccessful = in.readBoolean();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mRequestId);
        dest.writeInt(mErrorCode);
        dest.writeBoolean(mIsSuccessful);
    }

    @NonNull
    public static final Creator<FileOperationEnqueueResult> CREATOR =
            new Creator<FileOperationEnqueueResult>() {
                @Override
                public FileOperationEnqueueResult createFromParcel(Parcel in) {
                    return new FileOperationEnqueueResult(in);
                }

                @Override
                public FileOperationEnqueueResult[] newArray(int size) {
                    return new FileOperationEnqueueResult[size];
                }
            };

    /**
     * Returns the unique ID of the request if the operation was successfully enqueued.
     *
     * <p>This can be used to reference track the status of this request, or (un)register via
     * completion notifications.
     *
     * @see android.os.storage.FileManager#fetchResult
     * @see android.os.storage.FileManager#registerCompletionListener
     * @see android.os.storage.FileManager#unregisterCompletionListener
     */
    @Nullable
    public String getRequestId() {
        return mRequestId;
    }

    /** Returns the error code if the operation failed to enqueue. */
    @FileOperationResult.ErrorCode
    public int getErrorCode() {
        return mErrorCode;
    }

    /** Returns {@code true} if the operation was successfully enqueued. */
    public boolean isSuccessful() {
        return mIsSuccessful;
    }
}
