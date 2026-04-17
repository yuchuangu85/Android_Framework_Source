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
package android.app.appfunctions;

import static android.app.appfunctions.flags.Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS;

import static java.util.Objects.requireNonNull;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Contains identifying metadata for a predefined schema, which can describe well-known function
 * signatures.
 *
 * <p>Returned from {@link AppFunctionMetadata#getSchemaMetadata}.
 */
@FlaggedApi(FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
public final class AppFunctionSchemaMetadata implements Parcelable {
    @NonNull
    public static final Creator<AppFunctionSchemaMetadata> CREATOR =
            new Creator<AppFunctionSchemaMetadata>() {
                @Override
                public AppFunctionSchemaMetadata createFromParcel(Parcel in) {
                    return new AppFunctionSchemaMetadata(in);
                }

                @Override
                public AppFunctionSchemaMetadata[] newArray(int size) {
                    return new AppFunctionSchemaMetadata[size];
                }
            };

    @NonNull private final String mCategory;
    @NonNull private final String mName;
    private final long mVersion;

    /**
     * Constructs an {@link AppFunctionSchemaMetadata}.
     *
     * @param schemaName The unique name of the schema within its category.
     * @param schemaVersion The version of the schema. This is used to track the changes to the
     *     schema over time.
     * @param schemaCategory The category of the schema, used to group related schemas.
     */
    public AppFunctionSchemaMetadata(
            @NonNull String schemaCategory, @NonNull String schemaName, long schemaVersion) {
        mCategory = requireNonNull(schemaCategory);
        mName = requireNonNull(schemaName);
        mVersion = schemaVersion;
    }

    private AppFunctionSchemaMetadata(Parcel in) {
        mCategory = requireNonNull(in.readString8());
        mName = requireNonNull(in.readString8());
        mVersion = in.readLong();
    }

    /**
     * Returns the category of the schema used by this function.
     *
     * <p>This allows for logical grouping of schemas. For instance, all schemas related to email
     * functionality would be categorized as 'email'.
     *
     * <p>This is defined by the {@link AppFunctionMetadata#PROPERTY_SCHEMA_CATEGORY} tag in the
     * XML.
     */
    @NonNull
    public String getCategory() {
        return mCategory;
    }

    /**
     * Returns the unique name of the schema within its category.
     *
     * <p>This is defined by the {@link AppFunctionMetadata#PROPERTY_SCHEMA_NAME} tag in the XML.
     */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Returns the version of the schema.
     *
     * <p>This is used to track the changes to the schema over time.
     *
     * <p>This is defined by the {@link AppFunctionMetadata#PROPERTY_SCHEMA_VERSION} tag in the XML.
     */
    public long getVersion() {
        return mVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppFunctionSchemaMetadata that)) return false;
        return mCategory.equals(that.mCategory)
                && mName.equals(that.mName)
                && mVersion == that.mVersion;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCategory, mName, mVersion);
    }

    @Override
    public String toString() {
        return "AppFunctionSchemaMetadata("
                + "schemaCategory="
                + mCategory
                + ", "
                + "schemaName="
                + mName
                + ", "
                + "schemaVersion="
                + mVersion
                + ")";
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mCategory);
        dest.writeString8(mName);
        dest.writeLong(mVersion);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
