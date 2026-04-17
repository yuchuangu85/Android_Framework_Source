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

package android.app.contentrestriction;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.contentrestriction.flags.Flags;
import android.content.LocusId;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Parcelable.Creator;
import android.os.ParcelFileDescriptor;

/**
 * A class representing content to be classified.
 */
@FlaggedApi(Flags.FLAG_CONTENT_RESTRICTION_API)
public final class ClassifiableContent implements Parcelable {
    /**
     * The LocusId of the content.
     */
    private final LocusId mLocusId;

    /**
     * The MIME type of the content.
     */
    private final String mMimeType;

    /**
     * The URI of the content.
     */
    private final @Nullable Uri mUri;

    /**
     * The data of the content.
     */
    private final @Nullable ParcelFileDescriptor mData;

    private ClassifiableContent(LocusId locusId, String mimeType, @Nullable Uri uri,
            @Nullable ParcelFileDescriptor data) {
        mLocusId = locusId;
        mMimeType = mimeType;
        mUri = uri;
        mData = data;
    }

    /**
     * Creates a new instance from a Parcel.
     *
     * @param in the Parcel to read from
     */
    private ClassifiableContent(@NonNull Parcel in) {
        mMimeType = in.readString8();
        mLocusId = in.readTypedObject(LocusId.CREATOR);
        mUri = in.readTypedObject(Uri.CREATOR);
        mData = in.readTypedObject(ParcelFileDescriptor.CREATOR);
    }

    /**
     * Returns the {@link LocusId} associated with this content.
     *
     * @return the {@link LocusId} of the content
     */
    @NonNull
    public LocusId getId() {
        return mLocusId;
    }

    /**
     * Returns the MIME type of the content.
     *
     * @return the MIME type
     */
    @NonNull
    public String getMimeType() {
        return mMimeType;
    }

    /**
     * Returns the {@link Uri} that can be used to refer to this content.
     *
     * @return the content {@link Uri}, or {@code null} if this content is not referred to by a Uri
     */
    @Nullable
    public Uri getUri() {
        return mUri;
    }

    /**
     * Returns a {@link ParcelFileDescriptor} containing the raw data of this content.
     *
     * @return the {@link ParcelFileDescriptor} containing the content data, or {@code null} if the
     *         raw content isn't provided
     */
    @Nullable
    public ParcelFileDescriptor getData() {
        return mData;
    }

    @NonNull
    public static final Creator<ClassifiableContent> CREATOR = new Creator<ClassifiableContent>() {
        @Override
        public ClassifiableContent createFromParcel(@NonNull Parcel in) {
            return new ClassifiableContent(in);
        }

        @Override
        public ClassifiableContent[] newArray(int size) {
            return new ClassifiableContent[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, @WriteFlags int flags) {
        parcel.writeString8(mMimeType);
        parcel.writeTypedObject(mLocusId, flags);
        parcel.writeTypedObject(mUri, flags);
        parcel.writeTypedObject(mData, flags);
    }

    /**
     * Builder for {@link ClassifiableContent}.
     */
    public static final class Builder {
        private LocusId mLocusId;
        private String mMimeType;
        private Uri mUri;
        private ParcelFileDescriptor mData;

        /**
         * Creates a new builder.
         *
         * @param locusId the {@link LocusId} to set
         * @param mimeType the MIME type of the content
         */
        public Builder(@NonNull LocusId locusId, @NonNull String mimeType) {
            mLocusId = locusId;
            mMimeType = mimeType;
        }

        /**
         * Sets the {@link Uri} of the content.
         *
         * @param uri the {@link Uri} to set
         * @return this builder
         */
        @NonNull
        public Builder setUri(@NonNull Uri uri) {
            mUri = uri;
            return this;
        }

        /**
         * Sets the {@link ParcelFileDescriptor} containing the raw data of this content.
         *
         * @param data the {@link ParcelFileDescriptor} to set
         * @return this builder
         */
        @NonNull
        public Builder setData(@NonNull ParcelFileDescriptor data) {
            mData = data;
            return this;
        }

        /**
         * Builds the {@link ClassifiableContent} object.
         *
         * @return the built {@link ClassifiableContent}
         */
        @NonNull
        public ClassifiableContent build() {
            return new ClassifiableContent(mLocusId, mMimeType, mUri, mData);
        }
    }
}
