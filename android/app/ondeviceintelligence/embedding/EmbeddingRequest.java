/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.ondeviceintelligence.embedding;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.ondeviceintelligence.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import android.app.ondeviceintelligence.Content;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a request to generate embeddings for a specific input.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ON_DEVICE_INTELLIGENCE_26Q2)
public final class EmbeddingRequest implements Parcelable, AutoCloseable {
    private final List<Content> mContent;

    /**
     * Constructs a new {@link EmbeddingRequest} with the given payload.
     *
     * @param content The input list of content to generate embeddings for.
     */
    public EmbeddingRequest(@NonNull List<Content> content) {
        mContent = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(content)));
    }

    /**
     * Returns the list of content for which embeddings are requested.
     */
    @NonNull
    public List<Content> getContent() {
        return mContent;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mContent);
    }

    public static final @NonNull Creator<EmbeddingRequest> CREATOR =
            new Creator<EmbeddingRequest>() {
                @Override
                public EmbeddingRequest createFromParcel(Parcel in) {
                    return new EmbeddingRequest(in);
                }

                @Override
                public EmbeddingRequest[] newArray(int size) {
                    return new EmbeddingRequest[size];
                }
            };

    private EmbeddingRequest(Parcel in) {
        mContent = in.createTypedArrayList(Content.CREATOR);
    }

    @Override
    public void close() throws IOException {
        for (Content content : mContent) {
            content.close();
        }
    }
}
