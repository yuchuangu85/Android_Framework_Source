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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.ondeviceintelligence.flags.Flags;
import android.graphics.Bitmap;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A single component of content (Text, Image, or Blob).
 *
 * <p>A {@code Part} represents a specific piece of data within a {@link Content} object.
 * It encapsulates a single modality, such as a string of text, a bitmap image, or a binary blob.
 *
 * When dealing with large payloads, it is recommended to use a blob Part and stream the data to the
 * service using a {@link ParcelFileDescriptor} or a pipe. With this approach, the service can avoid
 * copying the data and directly operate on the underlying data. Without this approach, the caller
 * will encounter a {@link TransactionTooLargeException} if the payload exceeds the binder IPC data
 * size limit.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ON_DEVICE_INTELLIGENCE_26Q2)
public final class Part implements Parcelable, AutoCloseable {
    /** @hide */
    @IntDef(value = {TYPE_TEXT, TYPE_IMAGE, TYPE_AUDIO})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PartType {}

    /** Text part type. */
    public static final int TYPE_TEXT = 1;

    /** Image part type. */
    public static final int TYPE_IMAGE = 2;

    /** Audio part type. */
    public static final int TYPE_AUDIO = 3;

    private final int mType;
    private final String mText;
    private final Bitmap mImage;
    private final ParcelFileDescriptor mBlob;

    private Part(
            @PartType int type,
            @Nullable String text,
            @Nullable Bitmap image,
            @Nullable ParcelFileDescriptor blob) {
        mType = type;
        mText = text;
        mImage = image;
        mBlob = blob;
    }

    /**
     * Creates a text Part.
     *
     * @param text The text content.
     * @return A new Part containing the text.
     */
    @NonNull
    public static Part createText(@NonNull String text) {
        return new Part(TYPE_TEXT, Objects.requireNonNull(text), null, null);
    }

    /**
     * Creates an image Part.
     *
     * @param bitmap The bitmap content.
     * @return A new Part containing the image.
     */
    @NonNull
    public static Part createImage(@NonNull Bitmap bitmap) {
        return new Part(TYPE_IMAGE, null, Objects.requireNonNull(bitmap), null);
    }

    /**
     * Creates a Blob Part. Caller must provide a ParcelFileDescriptor that maps to a file
     * containing the blob or a read-side fd of a pipe that points to the blob.
     *
     * @param pfd File descriptor for reading the data (Audio etc.)
     * @param partType integer representing one of the PartType values
     * @return A new Part containing the blob.
     */
    @NonNull
    public static Part createBlob(@NonNull ParcelFileDescriptor pfd, @PartType int partType) {
        return new Part(partType, null, null, Objects.requireNonNull(pfd));
    }

    /**
     * Returns the type of this Part.
     *
     * @see TYPE_TEXT
     * @see TYPE_IMAGE
     * @see TYPE_AUDIO
     */
    @PartType
    public int getType() {
        return mType;
    }

    /**
     * Returns the text content if this is a text Part, otherwise null.
     */
    @Nullable
    public String getText() {
        return mText;
    }

    /**
     * Returns the image content if this is an image Part, otherwise null.
     */
    @Nullable
    public Bitmap getImage() {
        return mImage;
    }

    /**
     * Returns the blob content if this is a blob Part, otherwise null.
     */
    @Nullable
    public ParcelFileDescriptor getBlob() {
        return mBlob;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeString8(mText);
        dest.writeTypedObject(mImage, flags);
        dest.writeTypedObject(mBlob, flags);
    }

    public static final @NonNull Creator<Part> CREATOR =
            new Creator<Part>() {
                @Override
                public Part createFromParcel(Parcel in) {
                    return new Part(in);
                }

                @Override
                public Part[] newArray(int size) {
                    return new Part[size];
                }
            };

    private Part(Parcel in) {
        mType = in.readInt();
        mText = in.readString8();
        mImage = in.readTypedObject(Bitmap.CREATOR);
        mBlob = in.readTypedObject(ParcelFileDescriptor.CREATOR);
    }

    @Override
    public void close() throws IOException {
        if (mBlob != null) {
            mBlob.close();
        }
        if (mImage != null) {
            mImage.recycle();
        }
    }
}
