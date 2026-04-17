/*
 * Copyright 2025 The Android Open Source Project
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

package android.media.projection;

import android.annotation.FlaggedApi;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * Holds information about content an app can share via the MediaProjection APIs.
 * <p>
 * An application requesting a {@link MediaProjection session} can add its own content in the
 * list of available content along with the whole screen or a single application.
 * <p>
 * Each instance of {@link MediaProjectionAppContent} contains an {@link #getId() id} that is
 * used to identify the content chosen by the user back to the advertising application, thus the
 * meaning of the id is only relevant to that application and must uniquely identify a content to
 * be shared.
 *
 * @see Builder#Builder(int)
 * @see AppContentProjectionService
 */
@FlaggedApi(com.android.media.projection.flags.Flags.FLAG_APP_CONTENT_SHARING)
public final class MediaProjectionAppContent implements Parcelable {

    private final int mId;
    @Nullable
    private Bitmap mThumbnail;
    @Nullable
    private Icon mIcon;
    @NonNull
    private final CharSequence mTitle;

    // Private constructor used by the Builder
    private MediaProjectionAppContent(@NonNull Builder builder) {
        mId = builder.mId;
        mThumbnail = builder.mThumbnail;
        mTitle = builder.mTitle;
        mIcon = builder.mIcon;
    }

    // Private constructor for Parcelable
    private MediaProjectionAppContent(@NonNull Parcel in) {
        mId = in.readInt();
        mThumbnail = in.readParcelable(Bitmap.class.getClassLoader(), Bitmap.class);
        mTitle = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mIcon = in.readParcelable(Icon.class.getClassLoader(), Icon.class);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeParcelable(mThumbnail, flags);
        TextUtils.writeToParcel(mTitle, dest, flags);
        dest.writeParcelable(mIcon, flags);
    }


    /**
     * Returns the mandatory identifier for this content.
     */
    public int getId() {
        return mId;
    }

    /**
     * Returns the optional thumbnail representing the content. The thumbnail's goal is to give a
     * preview of the shared content.
     */
    @Nullable
    public Bitmap getThumbnail() {
        return mThumbnail;
    }

    /**
     * Returns the optional icon for the content to be displayed alongside the title.
     *
     * <p> The icon's goal is to represent the entity sharing the content such as the favicon of
     * the website.
     */
    @Nullable
    public Icon getIcon() {
        return mIcon;
    }

    /**
     * Returns the optional title for the content.
     */
    @NonNull
    public CharSequence getTitle() {
        return mTitle;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<MediaProjectionAppContent> CREATOR =
            new Creator<>() {
                @NonNull
                @Override
                public MediaProjectionAppContent createFromParcel(@NonNull Parcel in) {
                    return new MediaProjectionAppContent(in);
                }

                @NonNull
                @Override
                public MediaProjectionAppContent[] newArray(int size) {
                    return new MediaProjectionAppContent[size];
                }
            };

    /**
     * Optimize the resources in memory to fit the requested sizes.
     * This might modify this instance of {@link MediaProjectionAppContent}
     *
     * @hide
     */
    public void optimizeResources(Size thumbnailSize, Size iconSize) {
        if (mIcon != null) {
            if (iconSize.getWidth() <= 0 && iconSize.getHeight() <= 0) {
                mIcon = null;
            } else {
                mIcon.scaleDownIfNecessary(iconSize.getWidth(), iconSize.getHeight());
            }

        }
        if (mThumbnail != null) {
            if (thumbnailSize.getWidth() <= 0 && thumbnailSize.getHeight() <= 0) {
                mThumbnail = null;
            } else {
                mThumbnail = Icon.scaleDownIfNecessary(mThumbnail, thumbnailSize.getWidth(),
                        thumbnailSize.getHeight()).asShared();
            }
        }
    }

    /**
     * Builder for {@link MediaProjectionAppContent}.
     */
    public static final class Builder {

        private final int mId;
        @Nullable
        private Bitmap mThumbnail = null;
        @Nullable
        private Icon mIcon = null;
        @NonNull
        private CharSequence mTitle = "";

        /**
         * Creates a new Builder.
         *
         * @param id The mandatory identifier for the content. This must uniquely identify the
         *           content within the context of the calling application.
         */
        public Builder(int id) {
            mId = id;
        }

        /**
         * Sets the optional thumbnail for the content.
         *
         * @param thumbnail A Bitmap to visually represent the content.
         * @return This Builder instance for chaining.
         */
        @NonNull
        public Builder setThumbnail(@NonNull Bitmap thumbnail) {
            mThumbnail = Objects.requireNonNull(thumbnail);
            return this;
        }

        /**
         * Sets the optional title for the content.
         *
         * @param title A CharSequence to display as the title.
         * @return This Builder instance for chaining.
         */
        @NonNull
        public Builder setTitle(@NonNull CharSequence title) {
            mTitle = Objects.requireNonNull(title);
            return this;
        }

        /**
         * Sets the icon to be displayed alongside the title
         *
         * @return This Builder instance for chaining
         */
        @NonNull
        public Builder setIcon(@NonNull Icon icon) {
            mIcon = Objects.requireNonNull(icon);
            return this;
        }

        /**
         * Builds the {@link MediaProjectionAppContent} instance.
         *
         * @return A new {@link MediaProjectionAppContent}.
         */
        @NonNull
        public MediaProjectionAppContent build() {
            return new MediaProjectionAppContent(this);
        }
    }
}
