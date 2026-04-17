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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.contentrestriction.flags.Flags;
import android.content.LocusId;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Parcelable.Creator;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A result reported by a Content Restriction app in response to a content classification request.
 */
@FlaggedApi(Flags.FLAG_CONTENT_RESTRICTION_API)
public final class ContentClassificationResult implements Parcelable {

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_ALLOWED,
            TYPE_BLOCKED,
            TYPE_UNCLASSIFIED
    })
    public @interface ClassificationType {}

    /**
     * Indicates that the content is allowed.
     */
    @FlaggedApi(Flags.FLAG_CONTENT_RESTRICTION_API)
    public static final int TYPE_ALLOWED = 0;

    /**
     * Indicates that the content is blocked.
     */
    @FlaggedApi(Flags.FLAG_CONTENT_RESTRICTION_API)
    public static final int TYPE_BLOCKED = 1;

    /**
     * Indicates that the content could not be classified by the content restriction app.
     */
    @FlaggedApi(Flags.FLAG_CONTENT_RESTRICTION_API)
    public static final int TYPE_UNCLASSIFIED = 2;

    /**
     * The LocusId of the content that was filtered.
     */
    private final LocusId mLocusId;

    /**
     * The classification type of the content.
     */
    private final @ClassificationType int mType;

    /**
     * Creates a new content classification result.
     *
     * @param locusId the {@link LocusId} of the content that was classified
     * @param type the classification type of the content
     */
    public ContentClassificationResult(@NonNull LocusId locusId, @ClassificationType int type) {
        mLocusId = locusId;
        mType = type;
    }

    /**
     * Creates a new instance from a Parcel.
     *
     * @param in the Parcel to read from
     */
    private ContentClassificationResult(Parcel in) {
        mLocusId = in.readTypedObject(LocusId.CREATOR);
        mType = in.readInt();
    }

    /**
     * Returns the {@link LocusId} of the content that was classified.
     *
     * @return the {@link LocusId}
     */
    @NonNull
    public LocusId getLocusId() {
        return mLocusId;
    }

    /**
     * Returns the classification type of the content.
     *
     * @return the classification type
     */
    public @ClassificationType int getType() {
        return mType;
    }

    /**
     * Returns whether the content is allowed.
     *
     * @return {@code true} if the content is allowed, {@code false} otherwise
     */
    public boolean isAllowed() {
        return mType == TYPE_ALLOWED;
    }

    @NonNull
    public static final Creator<ContentClassificationResult> CREATOR =
            new Creator<ContentClassificationResult>() {
        @Override
        public ContentClassificationResult createFromParcel(@NonNull Parcel in) {
            return new ContentClassificationResult(in);
        }

        @Override
        public ContentClassificationResult[] newArray(int size) {
            return new ContentClassificationResult[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeTypedObject(mLocusId, flags);
        parcel.writeInt(mType);
    }
}
