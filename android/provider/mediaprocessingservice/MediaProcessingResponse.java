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
import android.net.Uri;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SharedMemory;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.android.providers.media.flags.Flags;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Contains the results of the media processing operation of a single media file
 *
 * <p>
 * To handle potentially large data sizes, the list of {@link EmbeddingVector} objects is
 * transferred using {@link SharedMemory}. The embedding data is serialized and placed into
 * shared memory when the object is written to a Parcel ({@link #writeToParcel(Parcel, int)}),
 * and deserialized on the receiving side only when {@link #getEmbeddingVectorList()} is first
 * accessed.
 * </p>
 *
 * @hide
 */
@SystemApi
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@FlaggedApi(Flags.FLAG_ENABLE_MEDIA_PROCESSING_SERVICE)
public final class MediaProcessingResponse implements Parcelable {
    private static final String TAG = "MediaProcessingResponse";

    @NonNull
    private final Uri mUri;
    private final long mProcessingGenerationNumber;
    @Nullable
    private final String mExtractedLabels;

    // The list of embedding vectors. Marked transient as it's not directly parceled; instead,
    // it's serialized into SharedMemory. Used by the response sender.
    @Nullable
    private transient List<EmbeddingVector> mEmbeddingVectorList;

    // Fields for SharedMemory transfer
    @Nullable
    private SharedMemory mEmbeddingsSharedMemory;
    private int mEmbeddingsSize;

    // Cached deserialized embeddings accessed by response receiver
    @Nullable
    private transient List<EmbeddingVector> mCachedEmbeddings = null;
    private transient boolean mEmbeddingsDeserialized = false;
    private final transient Object mLock = new Object();

    /**
     * Private constructor used by the Builder.
     */
    private MediaProcessingResponse(@NonNull Builder builder) {
        mUri = builder.mUri;
        mProcessingGenerationNumber = builder.mProcessingGenerationNumber;
        mExtractedLabels = builder.mExtractedLabels;
        mEmbeddingVectorList = builder.mEmbeddingVectorList;
    }

    /**
     * Constructs a new {@link MediaProcessingResponse} while unparceling
     */
    private MediaProcessingResponse(@NonNull Parcel in) {
        Objects.requireNonNull(in);
        mUri = Objects.requireNonNull(in.readParcelable(Uri.class.getClassLoader()));
        mProcessingGenerationNumber = in.readLong();
        mExtractedLabels = in.readString();
        mEmbeddingsSize = in.readInt();
        mEmbeddingsSharedMemory = in.readParcelable(SharedMemory.class.getClassLoader());

        if (mEmbeddingsSharedMemory == null) {
            mCachedEmbeddings = new ArrayList<>();
            mEmbeddingsDeserialized = true;
        }
    }

    /**
     * Returns the {@link Uri} of the media file that was processed.
     *
     * @return The content URI of the media item.
     */
    @NonNull
    public Uri getUri() {
        return mUri;
    }

    /**
     * Returns the generation number of the media file associated with this response.
     * <p>
     * This should match the generation number provided in the original
     * {@link MediaProcessingRequest} to ensure data consistency.
     *
     * @return The generation modified ID.
     */
    public long getProcessingGenerationNumber() {
        return mProcessingGenerationNumber;
    }

    /**
     * @return A string with space separated labels extracted from the media file.
     */
    @Nullable
    public String getExtractedLabels() {
        return mExtractedLabels;
    }

    /**
     * Returns a list of {@link EmbeddingVector} objects for semantic search.
     *
     * <p>
     * The embedding vectors are loaded on demand from {@link SharedMemory} the first time
     * this method is called on an instance received from a Parcel. Subsequent calls return a
     * cached copy.
     *
     * <p>
     * If there was an error creating the shared memory on the sender side, or an error
     * reading or deserializing the data on the receiver side, an empty list will be returned.
     *
     * @return A list of {@link EmbeddingVector} objects. Returns an empty list if
     * no embeddings were generated, or if an error occurred during transfer or
     * deserialization.
     */
    @NonNull
    public List<EmbeddingVector> getEmbeddingVectorList() {
        // If called on the sending side before parceling, return the original list.
        if (mEmbeddingVectorList != null && !mEmbeddingsDeserialized) {
            return mEmbeddingVectorList;
        }

        synchronized (mLock) {
            if (mEmbeddingsDeserialized) {
                return mCachedEmbeddings != null ? mCachedEmbeddings : new ArrayList<>();
            }

            if (mEmbeddingsSharedMemory == null || mEmbeddingsSize == 0) {
                mCachedEmbeddings = new ArrayList<>();
                mEmbeddingsDeserialized = true; // Mark as deserialized
                return mCachedEmbeddings;
            }

            ByteBuffer buffer = null;
            try {
                buffer = mEmbeddingsSharedMemory.mapReadOnly();
                if (buffer.remaining() != mEmbeddingsSize) {
                    Log.e(TAG,
                            "SharedMemory size mismatch, expected " + mEmbeddingsSize + " but got "
                                    + buffer.remaining());
                    throw new IllegalStateException("SharedMemory size mismatch");
                }
                byte[] bytes = new byte[mEmbeddingsSize];
                buffer.get(bytes);
                mCachedEmbeddings = EmbeddingVectorSerializer.deserializeList(bytes);
            } catch (Exception e) {
                Log.e(TAG, "Failed to deserialize embeddings", e);
                throw new RuntimeException("Failed to retrieve embedding vector list", e);
            } finally {
                if (buffer != null) {
                    SharedMemory.unmap(buffer);
                }

                if (mEmbeddingsSharedMemory != null) {
                    mEmbeddingsSharedMemory.close();
                    mEmbeddingsSharedMemory = null;
                }
                mEmbeddingsDeserialized = true;
            }

            return mCachedEmbeddings != null ? mCachedEmbeddings : new ArrayList<>();
        }
    }

    @NonNull
    public static final Creator<MediaProcessingResponse> CREATOR =
            new Creator<MediaProcessingResponse>() {
                @Override
                public MediaProcessingResponse createFromParcel(Parcel in) {
                    return new MediaProcessingResponse(in);
                }

                @Override
                public MediaProcessingResponse[] newArray(int size) {
                    return new MediaProcessingResponse[size];
                }
            };

    /**
     * {@inheritDoc}
     */
    @Override
    public int describeContents() {
        // Contents include a FileDescriptor within the SharedMemory object.
        return mEmbeddingsSharedMemory != null ? CONTENTS_FILE_DESCRIPTOR : 0;
    }

    /**
     * @param dest  The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        Objects.requireNonNull(dest);
        dest.writeParcelable(mUri, flags);
        dest.writeLong(mProcessingGenerationNumber);
        dest.writeString(mExtractedLabels);

        SharedMemory sharedMem = null;
        int dataSize = 0;

        if (mEmbeddingVectorList != null && !mEmbeddingVectorList.isEmpty()) {
            byte[] data = null;
            try {
                data = EmbeddingVectorSerializer.serializeList(mEmbeddingVectorList);
            } catch (IOException e) {
                Log.e(TAG, "Failed to serialize embeddings for parceling", e);
            }

            if (data != null && data.length > 0) {
                dataSize = data.length;
                try {
                    sharedMem = SharedMemory.create("EmbeddingVectors", dataSize);
                    ByteBuffer buffer = sharedMem.mapReadWrite();
                    buffer.put(data);
                    SharedMemory.unmap(buffer);
                    // SharedMemory should not be writable by others after this.
                    sharedMem.setProtect(android.system.OsConstants.PROT_READ);
                } catch (ErrnoException e) {
                    Log.e(TAG, "Failed to create/map SharedMemory for parceling", e);
                    sharedMem = null; // Ensure sm is null if error occurs
                    dataSize = 0;
                }
            }
        }

        dest.writeInt(dataSize);
        dest.writeParcelable(sharedMem, flags);

        if (sharedMem != null) {
            sharedMem.close();
        }
    }

    /**
     * Returns a new Builder instance.
     *
     * @param uri                        The URI of the media file that was processed.
     * @param processingGenerationNumber The generation number for the processing request.
     */
    @NonNull
    public static Builder builder(@NonNull Uri uri, long processingGenerationNumber) {
        return new Builder(uri, processingGenerationNumber);
    }

    /**
     * Builder class for {@link MediaProcessingResponse}.
     */
    public static final class Builder {
        @NonNull
        private final Uri mUri;
        private final long mProcessingGenerationNumber;
        @Nullable
        private String mExtractedLabels;
        @Nullable
        private List<EmbeddingVector> mEmbeddingVectorList;

        /**
         * Constructor for the Builder.
         *
         * @param uri                        The URI of the media file that was processed.
         * @param processingGenerationNumber The generation number at the time which processing
         *                                   request was initiated. It should match with the
         *                                   originating  {@link
         *                                   MediaProcessingRequest#getProcessingGenerationNumber()}
         */
        public Builder(@NonNull Uri uri, long processingGenerationNumber) {
            Objects.requireNonNull(uri, "URI cannot be null");
            if (!Objects.equals(uri.getAuthority(), MediaStore.AUTHORITY)) {
                throw new IllegalArgumentException("Invalid URI authority: " + uri.getAuthority());
            }
            mUri = uri;
            mProcessingGenerationNumber = processingGenerationNumber;
        }

        /**
         * Sets the extracted text labels.
         *
         * @param extractedLabels A string of searchable text labels, typically comma-separated.
         *                        May be {@code null}.
         * @return This Builder instance.
         */
        @NonNull
        public Builder setExtractedLabels(@Nullable String extractedLabels) {
            this.mExtractedLabels = extractedLabels;
            return this;
        }

        /**
         * Sets the list of embedding vectors.
         *
         * @param embeddingVectorList A list of {@link EmbeddingVector} objects for semantic search.
         *                            May be {@code null} or empty.
         * @return This Builder instance.
         */
        @NonNull
        public Builder setEmbeddingVectorList(@NonNull List<EmbeddingVector> embeddingVectorList) {
            this.mEmbeddingVectorList = embeddingVectorList;
            return this;
        }

        /**
         * Builds the {@link MediaProcessingResponse} instance.
         *
         * @return A new {@link MediaProcessingResponse}.
         */
        @NonNull
        public MediaProcessingResponse build() {
            return new MediaProcessingResponse(this);
        }
    }
}
