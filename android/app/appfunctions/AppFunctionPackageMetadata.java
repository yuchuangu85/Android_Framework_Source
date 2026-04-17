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
import android.app.appsearch.GenericDocument;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;
import java.util.Objects;

/** Contains metadata about a package providing app functions. */
@FlaggedApi(FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
public final class AppFunctionPackageMetadata implements AbstractAppFunctionMetadata, Parcelable {

    @NonNull
    public static final Creator<AppFunctionPackageMetadata> CREATOR =
            new Creator<AppFunctionPackageMetadata>() {
                @Override
                public AppFunctionPackageMetadata createFromParcel(Parcel in) {
                    return in.readSquashed(AppFunctionPackageMetadata::new);
                }

                @Override
                public AppFunctionPackageMetadata[] newArray(int size) {
                    return new AppFunctionPackageMetadata[size];
                }
            };

    /**
     * The schema type for the package-level metadata used by AppFunction documents.
     *
     * @hide
     */
    public static final String SCHEMA_TYPE = "AppFunctionPackageData";

    /**
     * Property key within {@link #getMetadataDocument} that contains a list of top-level package
     * documents.
     */
    public static final String PROPERTY_TOP_LEVEL_DOCUMENTS = "topLevelMetadataDocuments";

    /**
     * Property name for the XML tag that defines the value of {@link #getPackageName}.
     *
     * @hide
     */
    public static final String PROPERTY_PACKAGE_NAME =
            AppFunctionStaticMetadataHelper.PROPERTY_PACKAGE_NAME;

    @NonNull private final String mPackageName;
    @NonNull private final GenericDocumentWrapper mMetadataDocumentWrapper;

    private AppFunctionPackageMetadata(
            @NonNull String packageName, @NonNull GenericDocument metadataDocument) {
        mPackageName = requireNonNull(packageName);
        mMetadataDocumentWrapper = new GenericDocumentWrapper(requireNonNull(metadataDocument));
    }

    private AppFunctionPackageMetadata(Parcel in) {
        mPackageName = requireNonNull(in.readString8());
        mMetadataDocumentWrapper =
                requireNonNull(GenericDocumentWrapper.CREATOR.createFromParcel(in));
    }

    /**
     * @param packageName The name of the package.
     * @param topLevelMetadataDocuments The list of GenericDocuments comprising package-level
     *     documents in the database, excluding app functions.
     * @hide
     */
    public static AppFunctionPackageMetadata create(
            @NonNull String packageName, @NonNull List<GenericDocument> topLevelMetadataDocuments) {
        requireNonNull(packageName);
        requireNonNull(topLevelMetadataDocuments);
        return new AppFunctionPackageMetadata(
                packageName,
                new GenericDocument.Builder<>("", "", "")
                        .setPropertyString(PROPERTY_PACKAGE_NAME, packageName)
                        .setPropertyDocument(
                                PROPERTY_TOP_LEVEL_DOCUMENTS,
                                topLevelMetadataDocuments.toArray(new GenericDocument[0]))
                        .setCreationTimestampMillis(0)
                        .build());
    }

    /** Returns the name of the package providing app functions. */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Returns the package's metadata as a {@link GenericDocument}.
     *
     * <p>Client-defined properties can be retrieved using the {@link GenericDocument} property
     * getters.
     *
     * <p>Properties that are not defined in this class (see {@code PROPERTY_*} constants) are not
     * guaranteed to be available or consistent across devices and versions.
     */
    @NonNull
    @Override
    public GenericDocument getMetadataDocument() {
        return mMetadataDocumentWrapper.getValue();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppFunctionPackageMetadata that)) return false;

        return getMetadataDocument().equals(that.getMetadataDocument());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getMetadataDocument());
    }

    @Override
    public String toString() {
        return "AppFunctionPackageMetadata("
                + "packageName="
                + getPackageName()
                + ", "
                + "metadataDocument"
                + mMetadataDocumentWrapper.getValue()
                + ")";
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        if (dest.maybeWriteSquashed(this)) {
            return;
        }
        dest.writeString8(mPackageName);
        mMetadataDocumentWrapper.writeToParcel(dest, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
