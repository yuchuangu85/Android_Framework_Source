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

package android.app.ondeviceintelligence;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.ondeviceintelligence.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Container for a multimodal request payload.
 *
 * <p>A {@code Content} object represents a single unit of data to be processed, which can contain
 * multiple parts of different modalities (e.g., text, images).
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ON_DEVICE_INTELLIGENCE_26Q2)
public final class Content implements Parcelable, AutoCloseable {
    private final List<Part> mParts;

    /**
     * Constructs a new {@link Content} object.
     *
     * @param parts The list of parts that make up this content.
     */
    public Content(@NonNull List<Part> parts) {
        mParts = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(parts)));
    }

    /**
     * Returns the list of {@link android.app.ondeviceintelligence.Part} parts.
     */
    @NonNull
    public List<Part> getParts() {
        return mParts;
    }

    @Override
    public void close() throws IOException {
        for (Part part : mParts) {
            part.close();
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mParts);
    }

    public static final @NonNull Creator<Content> CREATOR =
            new Creator<Content>() {
                @Override
                public Content createFromParcel(Parcel in) {
                    return new Content(in);
                }

                @Override
                public Content[] newArray(int size) {
                    return new Content[size];
                }
            };

    private Content(Parcel in) {
        mParts = in.createTypedArrayList(Part.CREATOR);
    }
}
