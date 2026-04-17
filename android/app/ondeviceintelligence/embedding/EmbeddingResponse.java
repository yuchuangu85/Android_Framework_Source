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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents the response for the corresponding {@link EmbeddingRequest}.
 *
 * <p>This class contains a list of {@link EmbeddingVector} objects, one for each {@link Content}
 * input supplied in the {@link EmbeddingRequest}.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ON_DEVICE_INTELLIGENCE_26Q2)
public final class EmbeddingResponse implements Parcelable {
    private final List<EmbeddingVector> mEmbeddings;

    /**
     * Constructs a new {@link EmbeddingResponse} with the given list of embeddings.
     *
     * @param embeddings The list of generated {@link EmbeddingVector}s.
     */
    public EmbeddingResponse(@NonNull List<EmbeddingVector> embeddings) {
        mEmbeddings = Objects.requireNonNull(embeddings);
    }

    /**
     * Returns the list of generated embeddings.
     */
    @NonNull
    public List<EmbeddingVector> getEmbeddings() {
        return mEmbeddings;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mEmbeddings);
    }

    public static final @NonNull Creator<EmbeddingResponse> CREATOR =
            new Creator<EmbeddingResponse>() {
                @Override
                public EmbeddingResponse createFromParcel(Parcel in) {
                    return new EmbeddingResponse(in);
                }

                @Override
                public EmbeddingResponse[] newArray(int size) {
                    return new EmbeddingResponse[size];
                }
            };

    private EmbeddingResponse(Parcel in) {
        mEmbeddings = new ArrayList<>();
        in.readTypedList(mEmbeddings, EmbeddingVector.CREATOR);
    }
}
