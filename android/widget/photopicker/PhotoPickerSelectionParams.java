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

package android.widget.photopicker;

import android.annotation.FlaggedApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.providers.media.flags.Flags;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An immutable parcel that carries constraints to be applied to media items displayed in the
 * Photo Picker.
 *
 * <p>Media items that fail to satisfy these constraints will be disabled for selection.
 *
 * <p>Callers should use {@link Builder} to construct an instance of this class.
 */
@FlaggedApi(Flags.FLAG_ENABLE_PHOTOPICKER_SELECTION_PARAMS_API)
public final class PhotoPickerSelectionParams implements Parcelable {
    private static final String TAG = "PhotoPickerSelectionParams";

    private final long mMaxMediaItemSizeInBytes;
    @Nullable
    private final Duration mMaxVideoDuration;
    @Nullable
    private final Duration mMinVideoDuration;
    private final long mMaxMediaItemResolutionInPixels;
    private final long mMinMediaItemResolutionInPixels;
    private final List<String> mMimeTypes;
    private final long mMaxSelectionBatchSizeInBytes;

    private PhotoPickerSelectionParams(
            long maxMediaItemSizeInBytes,
            @Nullable Duration maxVideoDuration,
            @Nullable Duration minVideoDuration,
            long maxMediaItemResolutionInPixels,
            long minMediaItemResolutionInPixels,
            List<String> mimeTypes,
            long maxSelectionBatchSizeInBytes
    ) {
        mMaxMediaItemSizeInBytes = maxMediaItemSizeInBytes;
        mMaxVideoDuration = maxVideoDuration;
        mMinVideoDuration = minVideoDuration;
        mMaxMediaItemResolutionInPixels = maxMediaItemResolutionInPixels;
        mMinMediaItemResolutionInPixels = minMediaItemResolutionInPixels;
        mMimeTypes = List.copyOf(mimeTypes);
        mMaxSelectionBatchSizeInBytes = maxSelectionBatchSizeInBytes;
    }

    /**
     * Reconstructs this object from a Parcel, maintaining the order in which fields
     * were written.
     */
    private PhotoPickerSelectionParams(Parcel in) {
        mMaxMediaItemSizeInBytes = in.readLong();
        long maxDurationSeconds = in.readLong();
        mMaxVideoDuration = maxDurationSeconds == -1
                ? null : Duration.ofSeconds(maxDurationSeconds);
        long minDurationSeconds = in.readLong();
        mMinVideoDuration = minDurationSeconds == -1
                ? null : Duration.ofSeconds(minDurationSeconds);
        mMaxMediaItemResolutionInPixels = in.readLong();
        mMinMediaItemResolutionInPixels = in.readLong();
        List<String> mimeTypes = new ArrayList<>();
        in.readStringList(mimeTypes);
        mMimeTypes = mimeTypes;
        mMaxSelectionBatchSizeInBytes = in.readLong();
    }


    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mMaxMediaItemSizeInBytes);
        dest.writeLong(mMaxVideoDuration != null
                ? mMaxVideoDuration.toSeconds() : -1);
        dest.writeLong(mMinVideoDuration != null
                ? mMinVideoDuration.toSeconds() : -1);
        dest.writeLong(mMaxMediaItemResolutionInPixels);
        dest.writeLong(mMinMediaItemResolutionInPixels);
        dest.writeStringList(mMimeTypes);
        dest.writeLong(mMaxSelectionBatchSizeInBytes);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<PhotoPickerSelectionParams> CREATOR =
            new Creator<PhotoPickerSelectionParams>() {
                @Override
                public PhotoPickerSelectionParams createFromParcel(Parcel in) {
                    return new PhotoPickerSelectionParams(in);
                }

                @Override
                public PhotoPickerSelectionParams[] newArray(int size) {
                    return new PhotoPickerSelectionParams[size];
                }
            };

    /**
     * Returns the maximum allowed size, in bytes, for a media item to be selectable.
     *
     * <p>If the maximum media item size is not set by the caller app using {@link
     * Builder#setMaxMediaItemSizeInBytes(long)}, this method returns -1, indicating that the
     * photo picker will not restrict selection based on the maximum media item size.
     */
    public long getMaxMediaItemSizeInBytes() {
        return mMaxMediaItemSizeInBytes;
    }

    /**
     * Returns the maximum allowed duration for a video to be selectable.
     *
     * <p>If the maximum video duration is not set by the caller app using {@link
     * Builder#setMaxVideoDuration(Duration)}, this method returns {@code null},
     * indicating that the photo picker will not restrict selection based on the maximum video
     * duration.
     */
    @Nullable
    public Duration getMaxVideoDuration() {
        return mMaxVideoDuration;
    }

    /**
     * Returns the minimum required duration for a video to be selectable.
     *
     * <p>If the minimum video duration is not set by the caller app using {@link
     * Builder#setMinVideoDuration(Duration)}, this method returns {@code null},
     * indicating that the photo picker will not restrict selection based on the minimum video
     * duration.
     */
    @Nullable
    public Duration getMinVideoDuration() {
        return mMinVideoDuration;
    }

    /**
     * Returns the maximum allowed resolution, in pixels, for a media item to be selectable.
     *
     * <p>If the maximum resolution is not set by the caller app using {@link
     * Builder#setMaxMediaItemResolutionInPixels(long)}, this method returns -1, indicating that the
     * photo picker will not restrict selection based on the maximum media item resolution.
     */
    public long getMaxMediaItemResolutionInPixels() {
        return mMaxMediaItemResolutionInPixels;
    }

    /**
     * Returns the minimum required resolution, in pixels, for a media item to be selectable.
     *
     * <p>If the minimum resolution is not set by the caller app using {@link
     * Builder#setMinMediaItemResolutionInPixels(long)}, this method returns -1, indicating that the
     * photo picker will not restrict selection based on the minimum media item resolution.
     */
    public long getMinMediaItemResolutionInPixels() {
        return mMinMediaItemResolutionInPixels;
    }

    /**
     * Returns the list of allowed MIME types that media items must match to be selectable.
     *
     * <p>If the allowed MIME types are not set by the caller app using {@link
     * Builder#setMimeTypes(List)}, this method returns an empty list, indicating that the
     * photo picker will not restrict selection based on the MIME type.
     */
    @NonNull
    public List<String> getMimeTypes() {
        return mMimeTypes;
    }

    /**
     * Returns the maximum allowed cumulative size, in bytes, for the entire batch of selected
     * media items.
     *
     * <p>If the maximum selection batch size is not set by the caller app using {@link
     * Builder#setMaxSelectionBatchSizeInBytes(long)}, this method returns -1, indicating that the
     * photo picker will not restrict selection based on the total batch size.
     */
    public long getMaxSelectionBatchSizeInBytes() {
        return mMaxSelectionBatchSizeInBytes;
    }

    /**
     * A builder class used to construct and validate an immutable
     * {@link PhotoPickerSelectionParams} object.
     */
    public static final class Builder {

        private long mMaxMediaItemSizeInBytes = -1;
        @Nullable private Duration mMaxVideoDuration = null;
        @Nullable private Duration mMinVideoDuration = null;
        private long mMaxMediaItemResolutionInPixels = -1;
        private long mMinMediaItemResolutionInPixels = -1;
        private List<String> mMimeTypes = new ArrayList<>();
        private long mMaxSelectionBatchSizeInBytes = -1;

        public Builder() {
        }

        /**
         * Sets the maximum allowed size, in bytes, for any individual media item to be selectable.
         *
         * <p>The calling application can set this to limit the size of media returned by the
         * PhotoPicker. Items exceeding this limit will be disabled for selection.
         *
         * <p>If it is not set, no maximum size constraint will be enforced on the media items that
         * the user can select.
         *
         * @param maxMediaItemSizeInBytes The maximum size in bytes.
         * @throws IllegalArgumentException if {@code maxMediaItemSizeInBytes} is negative or zero
         */
        @NonNull
        public Builder setMaxMediaItemSizeInBytes(long maxMediaItemSizeInBytes) {
            if (maxMediaItemSizeInBytes <= 0) {
                throw new IllegalArgumentException(
                        "Maximum media item size cannot be negative or zero.");
            }
            mMaxMediaItemSizeInBytes = maxMediaItemSizeInBytes;
            return this;
        }

        /**
         * Clears the maximum media item size constraint.
         *
         * <p>On calling this, the PhotoPicker will not enforce an upper limit on the size of
         * individual media items.
         *
         * @see #setMaxMediaItemSizeInBytes(long)
         */
        @NonNull
        public Builder clearMaxMediaItemSize() {
            mMaxMediaItemSizeInBytes = -1;
            return this;
        }

        /**
         * Sets the maximum allowed duration for a video media item to be selectable.
         *
         * <p>Videos exceeding this duration will be disabled for selection.
         *
         * <p>The maximum duration should not be less than minimum duration, or an
         * exception will be thrown during the {@link #build()} process.
         *
         * <p>If it is not set, no maximum video duration constraint will be enforced on the videos
         * that the user can select.
         *
         * @param maxVideoDuration The maximum video duration.
         * @throws IllegalArgumentException if {@code maxVideoDuration} is null, negative
         * or zero
         */
        @NonNull
        public Builder setMaxVideoDuration(@NonNull Duration maxVideoDuration) {
            if (maxVideoDuration == null) {
                throw new IllegalArgumentException("Maximum video duration cannot be null");
            }
            if (!maxVideoDuration.isPositive()) {
                throw new IllegalArgumentException(
                        "Maximum video duration cannot be negative or zero.");
            }
            mMaxVideoDuration = maxVideoDuration;
            return this;
        }

        /**
         * Clears the maximum video duration constraint.
         *
         * <p>On calling this, the PhotoPicker will not enforce an upper limit on the duration of
         * video media items.
         *
         * @see #setMaxVideoDuration(Duration)
         */
        @NonNull
        public Builder clearMaxVideoDuration() {
            mMaxVideoDuration = null;
            return this;
        }

        /**
         * Sets the minimum allowed duration for a video media item to be selectable.
         *
         * <p>Videos shorter than this duration will be disabled for selection.
         *
         * <p>The maximum duration should not be less than minimum duration, or an
         * exception will be thrown during the {@link #build()} process.
         *
         * <p>If it is not set, no minimum video duration constraint will be enforced on the videos
         * that the user can select.
         *
         * @param minVideoDuration The minimum video duration.
         * @throws IllegalArgumentException if {@code minVideoDuration} is negative
         */
        @NonNull
        public Builder setMinVideoDuration(@NonNull Duration minVideoDuration) {
            if (minVideoDuration == null) {
                throw new IllegalArgumentException("Minimum video duration cannot be null");
            }
            if (!minVideoDuration.isPositive()) {
                throw new IllegalArgumentException("Minimum video duration cannot be negative.");
            }
            mMinVideoDuration = minVideoDuration;
            return this;
        }

        /**
         * Clears the minimum video duration constraint.
         *
         * <p>On calling this, the PhotoPicker will not enforce a lower limit on the duration of
         * video media items.
         *
         * @see #setMinVideoDuration(Duration)
         */
        @NonNull
        public Builder clearMinVideoDuration() {
            mMinVideoDuration = null;
            return this;
        }

        /**
         * Sets the maximum allowed resolution constraint for a media item to be selectable.
         *
         * <p>Items exceeding the provided resolution will be disabled for selection.
         *
         * <p>The maximum resolution should not be less than the minimum resolution, or an
         * exception will be thrown during the {@link #build()} process.
         *
         * <p>If it is not set, no maximum resolution constraint will be enforced on the media items
         * that the user can select.
         *
         * @param maxMediaItemResolutionInPixels The maximum media item resolution in pixels
         * @throws IllegalArgumentException if {@code maxMediaItemResolutionInPixels} is negative or
         *                                  zero
         */
        @NonNull
        public Builder setMaxMediaItemResolutionInPixels(long maxMediaItemResolutionInPixels) {
            if (maxMediaItemResolutionInPixels <= 0) {
                throw new IllegalArgumentException(
                        "Maximum media item resolution cannot be negative or zero.");
            }
            mMaxMediaItemResolutionInPixels = maxMediaItemResolutionInPixels;
            return this;
        }

        /**
         * Clears the maximum media item resolution constraint.
         *
         * <p>On calling this, the PhotoPicker will not enforce an upper limit on the total
         * resolution of individual media items.
         *
         * @see #setMaxMediaItemResolutionInPixels(long)
         */
        @NonNull
        public Builder clearMaxMediaItemResolution() {
            mMaxMediaItemResolutionInPixels = -1;
            return this;
        }

        /**
         * Sets the minimum allowed resolution constraint for a media item to be selectable.
         *
         * <p>Media items with a resolution lower than the provided threshold will be disabled for
         * selection.
         *
         * <p>The minimum resolution should not be greater than the maximum resolution, or an
         * exception will be thrown during the {@link #build()} process.
         *
         * <p>If it is not set, no minimum resolution constraint will be enforced on the media items
         * that the user can select.
         *
         * @param minMediaItemResolutionInPixels The minimum allowed resolution in total pixels.
         * @throws IllegalArgumentException if {@code minMediaItemResolutionInPixels} is negative.
         */
        @NonNull
        public Builder setMinMediaItemResolutionInPixels(long minMediaItemResolutionInPixels) {
            if (minMediaItemResolutionInPixels < 0) {
                throw new IllegalArgumentException(
                        "Minimum media item resolution cannot be negative");
            }
            mMinMediaItemResolutionInPixels = minMediaItemResolutionInPixels;
            return this;
        }

        /**
         * Clears the minimum media item resolution constraint.
         *
         * <p>On calling this, the PhotoPicker will not enforce a lower limit on the total
         * resolution of individual media items.
         *
         * @see #setMinMediaItemResolutionInPixels(long)
         */
        @NonNull
        public Builder clearMinMediaItemResolution() {
            mMinMediaItemResolutionInPixels = -1;
            return this;
        }

        /**
         * Sets the list of MIME types that are allowed for selection.
         *
         * <p>Media items that violate this constraint will be disabled for selection.
         *
         * <p>This parameter is different from the MIME types which can be specified in {@link
         * android.content.Intent#setType(String)} extra or {@link
         * android.content.Intent#EXTRA_MIME_TYPES}, when those are used to launch the photo picker,
         * they will filter out any media items which has a MIME type not added to them. While the
         * MIME Types defined by this API will still exist in photo picker media grid, but disabled
         * from selection.
         *
         * <p>Filter media items using the MIME Types defined in {@link
         * android.content.Intent#setType(String)} extra or {@link
         * android.content.Intent#EXTRA_MIME_TYPES} will happen first, before disabling the media
         * items based on the MIME Types passed to this API.
         *
         * <p>Callers must indicate the acceptable document MIME types. For example, to select
         * photos, use {@code image/*}.
         *
         * <p>If it is not set, no MIME type constraint will be enforced on the media items the user
         * can select.
         *
         * @param mimeTypes The list of allowed MIME types.
         * @throws IllegalArgumentException if {@code mimeTypes} is null, empty, or contains
         *                                  non-media types (types not starting with "image/" or
         *                                  "video/").
         */
        @NonNull
        public Builder setMimeTypes(@NonNull List<String> mimeTypes) {
            if (!validateMimeType(mimeTypes)) {
                throw new IllegalArgumentException("MimeTypes list must not be null or empty, "
                        + "and must only contain valid 'image/' or 'video/' types.");
            }
            mMimeTypes = new ArrayList<>(mimeTypes);
            return this;
        }

        /**
         * Clears the MIME type constraint.
         *
         * <p>On calling this, the builder will revert to its default state of allowing all
         * supported media MIME types (images and videos) for selection.
         *
         * @see #setMimeTypes(List)
         */
        @NonNull
        public Builder clearMimeTypes() {
            mMimeTypes.clear();
            return this;
        }

        /**
         * Sets the maximum allowed cumulative size, in bytes, for the entire batch of selected
         * media items.
         *
         * <p>This limits the total aggregate size of the media selected by the user. If a new
         * selection causes the total size to exceed this limit, the user will be alerted that they
         * have reached the maximum selection size limit and must deselect items before proceeding.
         *
         * <p>If it is not set, no collective batch size constraint will be enforced on media items
         * that the user can select.
         *
         * @param maxSelectionBatchSizeInBytes The maximum batch size in bytes.
         * @throws IllegalArgumentException if {@code maxSelectionBatchSizeInBytes} is negative or
         *                                  zero
         */
        @NonNull
        public Builder setMaxSelectionBatchSizeInBytes(long maxSelectionBatchSizeInBytes) {
            if (maxSelectionBatchSizeInBytes <= 0) {
                throw new IllegalArgumentException(
                        "Maximum batch size limit cannot be negative or zero");
            }
            mMaxSelectionBatchSizeInBytes = maxSelectionBatchSizeInBytes;
            return this;
        }

        /**
         * Clears the maximum cumulative selection size constraint.
         *
         * <p>On calling this, the PhotoPicker will not enforce a limit on the total aggregate size
         * of the selected media items in a single session.
         *
         * @see #setMaxSelectionBatchSizeInBytes(long)
         */
        @NonNull
        public Builder clearMaxSelectionBatchSize() {
            mMaxSelectionBatchSizeInBytes = -1;
            return this;
        }

        /**
         * Builds a new immutable {@link PhotoPickerSelectionParams} object.
         *
         * @return A new {@link PhotoPickerSelectionParams} object with the configured properties.
         * @throws IllegalArgumentException if any of the minimum values are greater than their
         *                                  corresponding maximum values.
         */
        @NonNull
        public PhotoPickerSelectionParams build() {
            validateMinMaxDuration(mMinVideoDuration, mMaxVideoDuration, "video duration");
            validateMinMax(mMinMediaItemResolutionInPixels, mMaxMediaItemResolutionInPixels,
                    "media item resolution");

            return new PhotoPickerSelectionParams(
                    mMaxMediaItemSizeInBytes,
                    mMaxVideoDuration,
                    mMinVideoDuration,
                    mMaxMediaItemResolutionInPixels,
                    mMinMediaItemResolutionInPixels,
                    mMimeTypes,
                    mMaxSelectionBatchSizeInBytes);
        }

        /**
         * Internal helper to perform validation, ensuring that a minimum value does not
         * exceed its corresponding maximum value.
         *
         * @param minValue minimum value of a set of params
         * @param maxValue maximum value of a set of params
         * @param param the set of param whose minimum and maximum values are being validated
         */
        private void validateMinMax(long minValue, long maxValue, @NonNull String param) {
            if (minValue != -1 && maxValue != -1 && minValue > maxValue) {
                throw new IllegalArgumentException(String.format(
                        Locale.ROOT,
                        "Min %s cannot be greater than the max %s.",
                        param, param));
            }
        }

        /**
         * Internal helper to perform validation, ensuring that a minimum duration does not
         * exceed its corresponding maximum duration.
         *
         * @param minValue minimum duration value
         * @param maxValue maximum duration value
         * @param param the parameter name being validated
         */
        private void validateMinMaxDuration(Duration minValue, Duration maxValue,
                @NonNull String param) {
            if (minValue == null || maxValue == null) {
                return;
            }
            if (minValue.compareTo(maxValue) > 0) {
                throw new IllegalArgumentException(String.format(
                        Locale.ROOT,
                        "Min %s cannot be greater than the max %s.",
                        param, param));
            }
        }

        /**
         * Internal helper to perform validation, ensuring that the MIME types list is not null
         * or empty, and each entry is a valid media type.
         *
         * @param mimeTypes the list of MIME types to be validated
         * @return {@code true} if the list is valid, {@code false} otherwise
         */
        private boolean validateMimeType(List<String> mimeTypes) {
            if (mimeTypes == null) {
                Log.e(TAG,
                        "Mime type list must not be null. MIME type constraint will not be "
                                + "applied.");
                return false;
            }
            if (mimeTypes.isEmpty()) {
                Log.e(TAG, "Empty mime type list found. MIME type constraint will not be applied.");
                return false;
            }
            for (String mimeType : mimeTypes) {
                if (mimeType == null) {
                    Log.e(TAG, "Mime type must not be null. "
                            + "MIME type constraint will not be applied.");
                    return false;
                }
                if (!isMimeTypeMedia(mimeType)) {
                    Log.e(TAG, "Invalid mime type found. Only image/video mime types are "
                            + "supported. MIME type constraint will not be applied.");
                    return false;
                }
            }
            return true;
        }

        /**
         * Checks if the given string is an image or video mime type
         */
        private static boolean isMimeTypeMedia(@NonNull String mimeType) {
            return mimeType.toLowerCase(Locale.getDefault()).startsWith("image/")
                    || mimeType.toLowerCase(Locale.getDefault()).startsWith("video/");
        }
    }
}
