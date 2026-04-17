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
import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.ondeviceintelligence.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents the response from an image descriptions generation request.
 *
 * <p>This class contains the generated {@link ImageDescription} for the input image.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ON_DEVICE_INTELLIGENCE_26Q2)
public final class ImageDescriptionResponse implements Parcelable {
    private final List<ImageDescription> mImageDescriptions;

    /**
     * Constructs a new {@link ImageDescriptionResponse}.
     *
     * @param imageDescriptions The list of generated descriptions.
     */
    public ImageDescriptionResponse(@NonNull List<ImageDescription> imageDescriptions) {
        mImageDescriptions = Objects.requireNonNull(imageDescriptions);
    }

    /**
     * Returns the generated description.
     */
    @NonNull
    public List<ImageDescription> getImageDescriptions() {
        return mImageDescriptions;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mImageDescriptions);
    }

    public static final @NonNull Creator<ImageDescriptionResponse> CREATOR =
            new Creator<ImageDescriptionResponse>() {
                @Override
                public ImageDescriptionResponse createFromParcel(Parcel in) {
                    return new ImageDescriptionResponse(in);
                }

                @Override
                public ImageDescriptionResponse[] newArray(int size) {
                    return new ImageDescriptionResponse[size];
                }
            };

    private ImageDescriptionResponse(Parcel in) {
        mImageDescriptions = in.createTypedArrayList(ImageDescription.CREATOR);
    }

    /**
     * Represents a single image description with its confidence score.
     */
    public static final class ImageDescription implements Parcelable {
        private final CharSequence mDescription;
        private final float mScore;

        /**
         * Constructs a new {@link ImageDescription}.
         *
         * @param description The generated description of the image.
         * @param score The confidence score of the description. The value should be in the range of
         *     [0, 1].
         */
        public ImageDescription(@NonNull CharSequence description, float score) {
            mDescription = Objects.requireNonNull(description);
            mScore = score;
        }

        /**
         * Returns the generated description.
         */
        @NonNull
        public CharSequence getDescription() {
            return mDescription;
        }

        /**
         * Returns the model's confidence in the description that it provided, expressed as a score
         * in the range [0.0, 1.0].
         */
        @FloatRange(from = 0.0, to = 1.0, fromInclusive = true, toInclusive = true)
        public float getScore() {
            return mScore;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeCharSequence(mDescription);
            dest.writeFloat(mScore);
        }

        public static final @NonNull Creator<ImageDescription> CREATOR =
                new Creator<ImageDescription>() {
                    @Override
                    public ImageDescription createFromParcel(Parcel in) {
                        return new ImageDescription(in);
                    }

                    @Override
                    public ImageDescription[] newArray(int size) {
                        return new ImageDescription[size];
                    }
                };

        private ImageDescription(Parcel in) {
            mDescription = in.readCharSequence();
            mScore = in.readFloat();
        }
    }
}
