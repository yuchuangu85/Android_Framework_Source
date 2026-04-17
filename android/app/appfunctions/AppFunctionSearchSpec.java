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

import static android.app.appfunctions.AppFunctionMetadata.PROPERTY_SCOPE;
import static android.app.appfunctions.AppFunctionMetadata.scopeToScopeXmlValue;
import static android.app.appfunctions.AppFunctionStaticMetadataHelper.getDocumentIdForAppFunction;
import static android.app.appfunctions.flags.Flags.FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.ArraySet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Filter criteria for {@link AppFunctionManager#searchAppFunctions}.
 *
 * <p>A search will be performed using a logical AND operation across all provided criteria.
 */
@FlaggedApi(FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
public final class AppFunctionSearchSpec implements Parcelable {
    @NonNull
    public static final Creator<AppFunctionSearchSpec> CREATOR =
            new Creator<AppFunctionSearchSpec>() {
                @Override
                public AppFunctionSearchSpec createFromParcel(Parcel in) {
                    return new AppFunctionSearchSpec(in);
                }

                @Override
                public AppFunctionSearchSpec[] newArray(int size) {
                    return new AppFunctionSearchSpec[size];
                }
            };

    @Nullable private final ArraySet<String> mPackageNames;
    @Nullable private final ArraySet<AppFunctionName> mFunctionNames;
    @Nullable private final String mSchemaCategory;
    @Nullable private final String mSchemaName;
    private final long mMinSchemaVersion;
    @Nullable @AppFunctionMetadata.Scope private final ArraySet<Integer> mScopes;

    /** Creates an instance of {@link AppFunctionSearchSpec}. */
    private AppFunctionSearchSpec(
            @Nullable Set<String> packageNames,
            @Nullable Set<AppFunctionName> functionNames,
            @Nullable String schemaCategory,
            @Nullable String schemaName,
            long minSchemaVersion,
            @Nullable Set<Integer> scopes) {
        mPackageNames = packageNames != null ? new ArraySet<>(packageNames) : null;
        mFunctionNames = functionNames != null ? new ArraySet<>(functionNames) : null;
        mSchemaCategory = schemaCategory;
        mSchemaName = schemaName;
        mMinSchemaVersion = minSchemaVersion;
        mScopes = scopes != null ? new ArraySet<>(scopes) : null;
    }

    private AppFunctionSearchSpec(Parcel in) {
        String[] packageArray = in.createString8Array();
        mPackageNames = (packageArray == null) ? null : new ArraySet<>(packageArray);
        mFunctionNames =
                (ArraySet<AppFunctionName>) in.readArraySet(AppFunctionName.class.getClassLoader());
        mSchemaCategory = in.readString8();
        mSchemaName = in.readString8();
        mMinSchemaVersion = in.readLong();
        mScopes = (ArraySet<Integer>) in.readArraySet(Integer.class.getClassLoader());
    }

    /** Returns the set of package names to filter by, or null if this filter is skipped. */
    @SuppressLint("NullableCollection")
    @Nullable
    public Set<String> getPackageNames() {
        return mPackageNames;
    }

    /**
     * Returns the set of {@link AppFunctionName} to filter by, or null if this filter is skipped.
     */
    @SuppressLint("NullableCollection")
    @Nullable
    public Set<AppFunctionName> getFunctionNames() {
        return mFunctionNames;
    }

    /**
     * Returns the schema category to filter by, or null if this filter is skipped.
     *
     * @see AppFunctionSchemaMetadata#getCategory
     */
    @Nullable
    public String getSchemaCategory() {
        return mSchemaCategory;
    }

    /**
     * Returns the schema name to filter by, or null if this filter is skipped.
     *
     * @see AppFunctionSchemaMetadata#getName
     */
    @Nullable
    public String getSchemaName() {
        return mSchemaName;
    }

    /**
     * Returns the minimum schema category to filter by, or 0 if this filter is skipped.
     *
     * @see AppFunctionSchemaMetadata#getVersion
     */
    public long getMinSchemaVersion() {
        return mMinSchemaVersion;
    }

    /**
     * Returns the set of scope type to filter by, or null if this filter is skipped.
     *
     * @see AppFunctionMetadata#getScope
     */
    @Nullable
    @SuppressLint("NullableCollection")
    public Set<Integer> getScopes() {
        return mScopes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AppFunctionSearchSpec that = (AppFunctionSearchSpec) o;

        return Objects.equals(mPackageNames, that.mPackageNames)
                && Objects.equals(mFunctionNames, that.mFunctionNames)
                && Objects.equals(mSchemaCategory, that.mSchemaCategory)
                && Objects.equals(mSchemaName, that.mSchemaName)
                && mMinSchemaVersion == that.mMinSchemaVersion
                && Objects.equals(mScopes, that.mScopes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mPackageNames,
                mFunctionNames,
                mSchemaCategory,
                mSchemaName,
                mMinSchemaVersion,
                mScopes);
    }

    @Override
    public String toString() {
        return "AppFunctionSearchSpec("
                + "packageNames="
                + mPackageNames
                + ", functionNames="
                + mFunctionNames
                + ", schemaCategory="
                + mSchemaCategory
                + ", schemaName="
                + mSchemaName
                + ", minSchemaVersion="
                + mMinSchemaVersion
                + ", scopes="
                + mScopes
                + ")";
    }

    /**
     * Returns a query expression to use when searching for app functions based on this {@link
     * AppFunctionSearchSpec}.
     *
     * @hide
     */
    @NonNull
    public String getStaticMetadataAppSearchQuery() {
        ArrayList<String> query = new ArrayList<>();
        if (mPackageNames != null) {
            query.add(
                    TextUtils.formatSimple(
                            "packageName:(%s)", getOrStringQueryExpression(mPackageNames)));
        }
        if (mSchemaCategory != null) {
            query.add(TextUtils.formatSimple("schemaCategory:\"%s\"", mSchemaCategory));
        }
        if (mSchemaName != null) {
            query.add(TextUtils.formatSimple("schemaName:\"%s\"", mSchemaName));
        }
        if (mMinSchemaVersion >= 1) {
            query.add(TextUtils.formatSimple("schemaVersion>=%d", mMinSchemaVersion));
        }
        if (mScopes != null && !mScopes.isEmpty()) {
            ArraySet<String> scopeXmlValues = new ArraySet<>();
            for (int scope : mScopes) {
                scopeXmlValues.add(scopeToScopeXmlValue(scope));
            }
            query.add(
                    TextUtils.formatSimple(
                            "%s:(%s)", PROPERTY_SCOPE, getOrStringQueryExpression(scopeXmlValues)));
        }
        return String.join(" ", query);
    }

    /**
     * Returns the list of qualified app function ids to filter for based on this {@link
     * AppFunctionSearchSpec}. Returns an empty list if the AppFunctionNames filter was not set.
     *
     * @hide
     */
    @NonNull
    public List<String> getQualifiedIdsFilter() {
        List<String> qualifiedIds = new ArrayList<>();
        if (mFunctionNames == null) {
            return qualifiedIds;
        }

        for (var functionName : mFunctionNames) {
            String qualifiedId =
                    getDocumentIdForAppFunction(
                            functionName.getPackageName(), functionName.getFunctionIdentifier());
            qualifiedIds.add(qualifiedId);
        }

        return qualifiedIds;
    }

    /**
     * Returns the set of package names to observe based on both {@link #mPackageNames} and {@link
     * #mFunctionNames} filters. Returns null if both filters are unset.
     *
     * @hide
     */
    @Nullable
    public Set<String> getObservedPackageNames() {
        Set<AppFunctionName> observedAppFunctions = getObservedAppFunctions();
        if (observedAppFunctions == null) {
            if (mPackageNames == null) {
                return null;
            } else {
                return Set.copyOf(mPackageNames);
            }
        }

        Set<String> observedPackages = new HashSet<>();
        for (AppFunctionName functionName : observedAppFunctions) {
            observedPackages.add(functionName.getPackageName());
        }

        return observedPackages;
    }

    /**
     * Returns the set of {@link AppFunctionName} to observe based on both {@link #mPackageNames}
     * and {@link #mFunctionNames} filters. Returns null if the {@link #mFunctionNames} filter is
     * unset.
     *
     * @hide
     */
    @Nullable
    public Set<AppFunctionName> getObservedAppFunctions() {
        if (mFunctionNames == null) {
            return null;
        }
        if (mPackageNames == null) {
            return Set.copyOf(mFunctionNames);
        }

        Set<String> packageNamesSet = Set.copyOf(mPackageNames);
        Set<AppFunctionName> result = new HashSet<>();

        for (AppFunctionName functionName : mFunctionNames) {
            if (packageNamesSet.contains(functionName.getPackageName())) {
                result.add(functionName);
            }
        }

        return result;
    }

    private String getOrStringQueryExpression(@NonNull ArraySet<String> elements) {
        String[] quotedElements = new String[elements.size()];
        for (int i = 0; i < elements.size(); i++) {
            quotedElements[i] = TextUtils.formatSimple("\"%s\"", elements.valueAt(i));
        }
        return String.join(" OR ", quotedElements);
    }

    /** Builder for constructing {@link AppFunctionSearchSpec}. */
    public static final class Builder {
        @Nullable private Set<String> mPackageNames = null;
        @Nullable private Set<AppFunctionName> mFunctionNames = null;
        @Nullable private String mSchemaCategory = null;
        @Nullable private String mSchemaName = null;
        private long mMinSchemaVersion = 0;
        @Nullable @AppFunctionMetadata.Scope private Set<Integer> mScopes = null;

        /** Constructs a {@link AppFunctionSearchSpec.Builder}. */
        public Builder() {}

        /**
         * Creates a new instance of {@link AppFunctionSearchSpec.Builder} based on {@code
         * searchSpec}.
         *
         * @hide
         */
        public Builder(@NonNull AppFunctionSearchSpec searchSpec) {
            this.mPackageNames = searchSpec.mPackageNames;
            this.mFunctionNames = searchSpec.mFunctionNames;
            this.mSchemaCategory = searchSpec.mSchemaCategory;
            this.mSchemaName = searchSpec.mSchemaName;
            this.mMinSchemaVersion = searchSpec.mMinSchemaVersion;
            this.mScopes = searchSpec.mScopes;
        }

        /** Constructs an {@link AppFunctionSearchSpec}. */
        @NonNull
        public AppFunctionSearchSpec build() {
            return new AppFunctionSearchSpec(
                    mPackageNames,
                    mFunctionNames,
                    mSchemaCategory,
                    mSchemaName,
                    mMinSchemaVersion,
                    mScopes);
        }

        /**
         * Sets the list of package names to filter by, or null to skip this filter.
         *
         * @param packageNames the list of package names to filter by.
         * @throws IllegalArgumentException if {@code packageNames} is an empty list.
         */
        @NonNull
        public Builder setPackageNames(@Nullable Set<String> packageNames) {
            if (packageNames != null && packageNames.isEmpty()) {
                throw new IllegalArgumentException("Package names can not be an empty list.");
            }
            this.mPackageNames = packageNames;
            return this;
        }

        /**
         * Sets the list of {@link AppFunctionName} to filter by, or null to skip this filter.
         *
         * @param functionNames the list of {@link AppFunctionName} to filter by.
         * @throws IllegalArgumentException if {@code functionNames} is an empty list.
         */
        @NonNull
        public Builder setFunctionNames(@Nullable Set<AppFunctionName> functionNames) {
            if (functionNames != null && functionNames.isEmpty()) {
                throw new IllegalArgumentException("AppFunction names can not be an empty list.");
            }
            this.mFunctionNames = functionNames;
            return this;
        }

        /**
         * Sets the schema category to filter by, or null to skip this filter.
         *
         * @param schemaCategory the schema category to filter by.
         * @see AppFunctionSchemaMetadata#getCategory
         */
        @NonNull
        public Builder setSchemaCategory(@Nullable String schemaCategory) {
            this.mSchemaCategory = schemaCategory;
            return this;
        }

        /**
         * Sets the schema name to filter by, or null to skip this filter.
         *
         * @param schemaName the schema name to filter by.
         * @see AppFunctionSchemaMetadata#getName
         */
        @NonNull
        public Builder setSchemaName(@Nullable String schemaName) {
            this.mSchemaName = schemaName;
            return this;
        }

        /**
         * Sets the minimum schema version to filter by, or 0 to skip this filter.
         *
         * @param minSchemaVersion the minimum schema version to filter by.
         * @see AppFunctionSchemaMetadata#getVersion
         */
        @NonNull
        public Builder setMinSchemaVersion(long minSchemaVersion) {
            this.mMinSchemaVersion = minSchemaVersion;
            return this;
        }

        /**
         * Sets the scope types to filter by, or null to skip this filter.
         *
         * @param scopes the set of scope types to filter by.
         * @see AppFunctionMetadata#getScope()
         */
        @NonNull
        public Builder setScopes(@Nullable Set<Integer> scopes) {
            this.mScopes = scopes;
            return this;
        }
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8Array(
                (mPackageNames == null) ? null : mPackageNames.toArray(new String[0]));
        dest.writeArraySet(mFunctionNames);
        dest.writeString8(mSchemaCategory);
        dest.writeString8(mSchemaName);
        dest.writeLong(mMinSchemaVersion);
        dest.writeArraySet(mScopes);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
