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
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.net.Uri;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaType;

import androidx.annotation.RequiresApi;

import com.android.providers.media.flags.Flags;

import java.util.Objects;

/**
 * Represents a request to process a single media file received by a {@link MediaProcessingService}.
 *
 * @hide
 */
@SystemApi
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@FlaggedApi(Flags.FLAG_ENABLE_MEDIA_PROCESSING_SERVICE)
public final class MediaProcessingRequest implements Parcelable {
    @NonNull
    private final Uri mUri;
    private final @MediaType int mMediaType;
    private final long mProcessingGenerationNumber;

    /**
     * @param uri                        The URI of the media file to be processed.
     * @param mediaType                  The type of the media, {@code MediaStore.MEDIA_TYPE}
     * @param processingGenerationNumber Generation modified ID at which media processing request
     *                                   was initiated
     */
    public MediaProcessingRequest(@NonNull Uri uri, @MediaType int mediaType,
            long processingGenerationNumber) {
        Objects.requireNonNull(uri);

        if (!Objects.equals(uri.getAuthority(), MediaStore.AUTHORITY)) {
            throw new IllegalArgumentException("Invalid URI authority");
        }
        mUri = uri;
        mMediaType = mediaType;
        mProcessingGenerationNumber = processingGenerationNumber;
    }

    private MediaProcessingRequest(Parcel in) {
        mUri = Objects.requireNonNull(Uri.parse(in.readString()));
        if (!Objects.equals(mUri.getAuthority(), MediaStore.AUTHORITY)) {
            throw new IllegalArgumentException("Invalid URI authority");
        }
        mMediaType = in.readInt();
        mProcessingGenerationNumber = in.readLong();
    }

    /**
     * Write this object in to a Parcel.
     *
     * @param dest  The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        Objects.requireNonNull(dest);
        dest.writeString(mUri.toString());
        dest.writeInt(mMediaType);
        dest.writeLong(mProcessingGenerationNumber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<MediaProcessingRequest> CREATOR =
            new Creator<MediaProcessingRequest>() {
                @Override
                public MediaProcessingRequest createFromParcel(Parcel in) {
                    return new MediaProcessingRequest(in);
                }

                @Override
                public MediaProcessingRequest[] newArray(int size) {
                    return new MediaProcessingRequest[size];
                }
            };

    /**
     * Returns the {@link Uri} of the media file to be processed.
     *
     * @return The content URI of the media item.
     */
    @NonNull
    public Uri getUri() {
        return mUri;
    }

    /**
     * Returns the specific media type of the file.
     *
     * @return An integer constant corresponding to {@code MediaStore.MediaType} IntDef values
     */
    public @MediaType int getMediaType() {
        return mMediaType;
    }

    /**
     * Returns the generation number of the media file at the time this processing request was
     * created.
     * <p>
     * This value corresponds to {@link MediaStore.MediaColumns#GENERATION_MODIFIED} and is used
     * to ensure that processing results are associated with the correct version of the file.
     *
     * @return The generation modified ID.
     */
    public long getProcessingGenerationNumber() {
        return mProcessingGenerationNumber;
    }
}
