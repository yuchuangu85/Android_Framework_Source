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

package android.app.ondeviceintelligence.imagedescription;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.ondeviceintelligence.Content;
import android.app.ondeviceintelligence.Part;
import android.app.ondeviceintelligence.flags.Flags;
import android.graphics.Bitmap;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Locale;

/**
 * Represents a request to generate a description for an image.
 *
 * <p>This class encapsulates the input image and an optional prompt to guide
 * the description generation.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ON_DEVICE_INTELLIGENCE_26Q2)
public final class ImageDescriptionRequest implements Parcelable, AutoCloseable {
    private final Content mContent;
    @Nullable
    private final Locale mLocale;

    /**
     * Constructs a new {@link ImageDescriptionRequest}.
     *
     * @param image The image for which a description is requested.
     * @param prompt An optional prompt to guide the description generation.
     */
    public ImageDescriptionRequest(@NonNull Bitmap image, @Nullable String prompt) {
        this(image, prompt, null);
    }

    /**
     * Constructs a new {@link ImageDescriptionRequest}.
     *
     * @param image The input image for which a description is requested.
     * @param prompt An optional prompt to contextually guide the description generation.
     * @param locale An optional local with language tag for the description generation.
     */
    public ImageDescriptionRequest(@NonNull Bitmap image, @Nullable String prompt,
            @Nullable Locale locale) {
        Objects.requireNonNull(image);
        List<Part> parts = new ArrayList<>();
        parts.add(Part.createImage(image.asShared()));
        if (prompt != null) {
            parts.add(Part.createText(prompt));
        }
        mContent = new Content(parts);
        mLocale = locale;
    }

    /**
     * Returns the input content for which description is to be requested.
     */
    @NonNull
    public Content getContent() {
        return mContent;
    }

    /**
     * Returns the optional prompt.
     */
    @Nullable
    public Locale getLocale() {
        return mLocale;
    }

    @Override
    public void close() throws IOException {
        if (mContent != null) {
            mContent.close();
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mContent, flags);
        dest.writeString8(mLocale != null ? mLocale.toLanguageTag() : null);
    }

    public static final @NonNull Creator<ImageDescriptionRequest> CREATOR =
            new Creator<ImageDescriptionRequest>() {
                @Override
                public ImageDescriptionRequest createFromParcel(Parcel in) {
                    return new ImageDescriptionRequest(in);
                }

                @Override
                public ImageDescriptionRequest[] newArray(int size) {
                    return new ImageDescriptionRequest[size];
                }
            };

    private ImageDescriptionRequest(Parcel in) {
        mContent = in.readTypedObject(Content.CREATOR);
        String languageTag = in.readString8();
        mLocale = languageTag != null ? Locale.forLanguageTag(languageTag) : null;
    }
}
