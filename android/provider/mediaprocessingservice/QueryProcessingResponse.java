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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.android.providers.media.flags.Flags;

import java.util.Objects;

/**
 * Represents the result of processing a search query string.
 * <p>
 * This class contains the original search query and the resulting
 * {@link EmbeddingVector} generated from it.
 *
 * @hide
 */
@SystemApi
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@FlaggedApi(Flags.FLAG_ENABLE_MEDIA_PROCESSING_SERVICE)
public final class QueryProcessingResponse implements Parcelable {
    private static final String TAG = "QueryProcessingResponse";

    @NonNull
    private final String mSearchQuery;

    @Nullable
    private final EmbeddingVector mEmbeddingVector;

    /**
     * Constructs a new {@code QueryProcessingResponse}.
     *
     * @param searchQuery the original search query string that was processed.
     * @param embedding   the {@link EmbeddingVector} generated from the search query,
     *                    or {@code null} if no vector was generated.
     */
    public QueryProcessingResponse(@NonNull String searchQuery,
            @Nullable EmbeddingVector embedding) {
        Objects.requireNonNull(searchQuery, "searchQuery must not be null");
        mSearchQuery = searchQuery;
        mEmbeddingVector = embedding;
    }

    private QueryProcessingResponse(Parcel in) {
        mSearchQuery = Objects.requireNonNull(in.readString(), "searchQuery must not be null");
        mEmbeddingVector = in.readParcelable(EmbeddingVector.class.getClassLoader());
    }

    /**
     * Returns the original search query string.
     *
     * @return the non-null search query.
     */
    @NonNull
    public String getSearchQuery() {
        return mSearchQuery;
    }

    /**
     * Returns the {@link EmbeddingVector} generated from the search query.
     *
     * @return the generated {@link EmbeddingVector}, or {@code null} if one was not available.
     */
    @Nullable
    public EmbeddingVector getEmbeddingVector() {
        return mEmbeddingVector;
    }

    @NonNull
    public static final Creator<QueryProcessingResponse> CREATOR =
            new Creator<QueryProcessingResponse>() {
                @Override
                public QueryProcessingResponse createFromParcel(Parcel in) {
                    return new QueryProcessingResponse(in);
                }

                @Override
                public QueryProcessingResponse[] newArray(int size) {
                    return new QueryProcessingResponse[size];
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
     * {@inheritDoc}
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        Objects.requireNonNull(dest, "dest must not be null");
        dest.writeString(mSearchQuery);
        dest.writeParcelable(mEmbeddingVector, flags);
    }
}
