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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.GenericDocument;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Contains an app function's metadata, essential for its invocation and discovery, retrieved using
 * {@link AppFunctionManager#searchAppFunctions}.
 *
 * <p>This metadata is defined in an XML asset file and does not change at runtime. The XML file is
 * referenced from the app's {@code AndroidManifest.xml}. How it's referenced depends on whether the
 * app function is implemented using {@link AppFunctionService} or {@link
 * AppFunctionManager#registerAppFunction}.
 *
 * <p>The XML schema consists of a root {@code <appfunctions>} element that can contain one or more
 * {@code <appfunction>} elements. The XML tags used within the {@code <appfunction>} element
 * directly correspond to the property names defined by the {@code PROPERTY_*} constants in this
 * class. All properties in the XML (including unknown properties) are made available through the
 * {@link #getMetadataDocument} method.
 *
 * <p><b>Example {@code assets/app_functions.xml} declaration:</b>
 *
 * <pre>{@code
 * <appfunctions>
 *     <appfunction>
 *         <id>createNote</id>
 *         <enabledByDefault>true</enabledByDefault>
 *         <scope>global</scope>
 *         ...
 *     </appfunction>
 * </appfunctions>
 * }</pre>
 *
 * <p>See {@link AppFunctionManager#getAppFunctionStates} for details on retrieving the runtime
 * state of the app functions.
 */
@FlaggedApi(FLAG_ENABLE_DYNAMIC_APP_FUNCTIONS)
public final class AppFunctionMetadata implements AbstractAppFunctionMetadata, Parcelable {

    /**
     * Property name for the XML tag that defines the value of {@link
     * AppFunctionName#getFunctionIdentifier} returned by {@link #getName}.
     */
    public static final String PROPERTY_FUNCTION_ID = "id";

    /**
     * Property name for the XML tag that defines the value of {@link
     * AppFunctionSchemaMetadata#getCategory} returned by {@link #getSchemaMetadata}.
     */
    public static final String PROPERTY_SCHEMA_CATEGORY = "schemaCategory";

    /**
     * Property name for the XML tag that defines the value of {@link
     * AppFunctionSchemaMetadata#getName} returned by {@link #getSchemaMetadata}.
     */
    public static final String PROPERTY_SCHEMA_NAME = "schemaName";

    /**
     * Property name for the XML tag that defines the value of {@link
     * AppFunctionSchemaMetadata#getVersion} returned by {@link #getSchemaMetadata}.
     */
    public static final String PROPERTY_SCHEMA_VERSION = "schemaVersion";

    /**
     * Property name for the XML tag that defines the default value of {@link
     * AppFunctionState#isEnabled}, before calling {@link AppFunctionManager#setAppFunctionEnabled}.
     */
    public static final String PROPERTY_ENABLED_BY_DEFAULT = "enabledByDefault";

    /**
     * Property name for the XML tag that defines the value of {@link #getScope}.
     *
     * <p>This property is should not be used for XML assets referenced by an {@link
     * AppFunctionService} declaration in the manifest, which are always {@link #SCOPE_GLOBAL}.
     *
     * @see #getScope()
     */
    public static final String PROPERTY_SCOPE = "scope";

    /** Property value for {@link #PROPERTY_SCOPE} in the XML representing {@link #SCOPE_GLOBAL}. */
    public static final String PROPERTY_VALUE_SCOPE_GLOBAL = "global";

    /**
     * Property value for {@link #PROPERTY_SCOPE} in the XML representing {@link #SCOPE_ACTIVITY}.
     */
    public static final String PROPERTY_VALUE_SCOPE_ACTIVITY = "activity";

    /**
     * A value returned from {@link #getScope} that indicates it is a globally-scoped app function.
     *
     * <p>There can be at most one app function implementation with the same {@link AppFunctionName}
     * available with this scope. This is useful for functions that are tied to a singleton
     * component, such as a foreground service.
     *
     * <p>When using {@link AppFunctionManager#registerAppFunction}, the function remains registered
     * until it is explicitly unregistered or the calling context is destroyed.
     *
     * <p>To execute a globally-scoped function, the caller of {@link
     * AppFunctionManager#executeAppFunction} must not use {@link
     * ExecuteAppFunctionRequest#setActivityId} (or set it to null), otherwise {@link
     * AppFunctionException#ERROR_FUNCTION_NOT_FOUND} will be returned.
     *
     * <p>This is always the scope for {@link AppFunctionService}-based functions.
     *
     * <p><b>IMPORTANT:</b> Functions provided with {@link AppFunctionManager#registerAppFunction}
     * called from an {@link android.app.Activity} context should prefer {@link #SCOPE_ACTIVITY}.
     * Only use {@link #SCOPE_GLOBAL} for such functions if you are absolutely sure there can be
     * only one instance of that activity.
     */
    public static final int SCOPE_GLOBAL = 0;

    /**
     * A value returned from {@link #getScope} that indicates it is an activity-scoped app function.
     *
     * <p>Multiple app function implementations with the same {@link AppFunctionName} can exist
     * simultaneously, each registered from a different {@link android.app.Activity} instance, which
     * is identified by an {@link AppFunctionActivityId}.
     *
     * <p>Functions with this scope must be registered using {@link
     * AppFunctionManager#registerAppFunction}, and must be called from an {@link
     * android.app.Activity} context. Calling it from any other context will result in an {@link
     * IllegalStateException}.
     *
     * <p>To execute an activity-scoped function, the caller of {@link
     * AppFunctionManager#executeAppFunction} must use {@link
     * ExecuteAppFunctionRequest#setActivityId}, otherwise {@link
     * AppFunctionException#ERROR_FUNCTION_NOT_FOUND} will be returned.
     *
     * <p>To discover the specific activities where an activity-scoped function is currently
     * registered, see {@link AppFunctionManager#getAppFunctionStates} and {@link
     * AppFunctionManager#getAppFunctionActivityStates}.
     *
     * <p>The function remains registered until it is explicitly unregistered or the activity is
     * destroyed.
     *
     * <p><b>IMPORTANT:</b> Functions provided with {@link AppFunctionManager#registerAppFunction}
     * called from an {@link android.app.Activity} context should prefer {@link #SCOPE_ACTIVITY}.
     * Only use {@link #SCOPE_GLOBAL} for such functions if you are absolutely sure there can be
     * only one instance of that activity.
     */
    public static final int SCOPE_ACTIVITY = 1;

    /**
     * Property name for the app function's package name hash.
     *
     * <p>This is preserved by platform and can only be populated by the indexer.
     *
     * @hide
     */
    public static final String PROPERTY_PACKAGE_NAME_HASH = "packageNameHash";

    @IntDef({SCOPE_GLOBAL, SCOPE_ACTIVITY})
    @Retention(RetentionPolicy.SOURCE)
    @interface Scope {}


    /**
     * A static app function executed using {@link android.app.appfunctions.AppFunctionService}.
     *
     * @hide
     */
    public static final int APP_FUNCTION_TYPE_STATIC = 0;
    /**
     * A dynamic app function registered globally.
     *
     * @hide
     */
    public static final int APP_FUNCTION_TYPE_DYNAMIC_GLOBAL = 1;
    /**
     * A dynamic app function registered within an activity's context.
     *
     * @hide
     */
    public static final int APP_FUNCTION_TYPE_DYNAMIC_ACTIVITY = 2;

    /**
     * The type of an app function.
     *
     * @hide
     */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    @android.annotation.IntDef({
        APP_FUNCTION_TYPE_STATIC,
        APP_FUNCTION_TYPE_DYNAMIC_GLOBAL,
        APP_FUNCTION_TYPE_DYNAMIC_ACTIVITY
    })
    public @interface AppFunctionType {}

    /**
     * Internal property which stores service name which should be used to execute App Function.
     * {@link #DYNAMIC_APP_FUNCTIONS_SERVICE_NAME} is set for app functions implemented using {@link
     * AppFunctionManager#registerAppFunction}.
     *
     * @hide
     */
    public static final String PROPERTY_SERVICE_NAME = "serviceName";

    /**
     * Expected value for {@link #PROPERTY_SERVICE_NAME} in case AppFunction is implemented using
     * {@link AppFunctionManager#registerAppFunction}.
     *
     * @hide
     */
    public static final String DYNAMIC_APP_FUNCTIONS_SERVICE_NAME = "@null";

    @NonNull
    public static final Creator<AppFunctionMetadata> CREATOR =
            new Creator<>() {
                @Override
                public AppFunctionMetadata createFromParcel(Parcel in) {
                    return new AppFunctionMetadata(in);
                }

                @Override
                public AppFunctionMetadata[] newArray(int size) {
                    return new AppFunctionMetadata[size];
                }
            };

    @NonNull private final GenericDocumentWrapper mAppFunctionMetadataDocumentWrapper;
    @NonNull private final AppFunctionName mAppFunctionName;
    @Nullable private final AppFunctionSchemaMetadata mAppFunctionSchemaMetadata;
    @NonNull private final AppFunctionPackageMetadata mAppFunctionPackageMetadata;

    private AppFunctionMetadata(
            @NonNull AppFunctionName appFunctionName,
            @Nullable AppFunctionSchemaMetadata appFunctionSchemaMetadata,
            @NonNull AppFunctionPackageMetadata appFunctionPackageMetadata,
            @NonNull GenericDocument appFunctionMetadataDocument) {
        mAppFunctionName = appFunctionName;
        mAppFunctionSchemaMetadata = appFunctionSchemaMetadata;
        mAppFunctionPackageMetadata = appFunctionPackageMetadata;
        mAppFunctionMetadataDocumentWrapper =
                new GenericDocumentWrapper(appFunctionMetadataDocument);
    }

    private AppFunctionMetadata(Parcel in) {
        mAppFunctionName = Objects.requireNonNull(AppFunctionName.CREATOR.createFromParcel(in));
        mAppFunctionSchemaMetadata = in.readTypedObject(AppFunctionSchemaMetadata.CREATOR);
        mAppFunctionPackageMetadata =
                Objects.requireNonNull(AppFunctionPackageMetadata.CREATOR.createFromParcel(in));
        mAppFunctionMetadataDocumentWrapper =
                Objects.requireNonNull(GenericDocumentWrapper.CREATOR.createFromParcel(in));
    }

    /**
     * Returns the qualified name of the app function.
     *
     * <p>The {@link AppFunctionName} is composed of the app's package name and the function's
     * identifier.
     *
     * <p>This is defined by the {@link #PROPERTY_FUNCTION_ID} tag in the XML.
     */
    @NonNull
    public AppFunctionName getName() {
        return mAppFunctionName;
    }

    /**
     * Returns the identifying metadata for a pre-defined schema which this app function implements.
     */
    @Nullable
    public AppFunctionSchemaMetadata getSchemaMetadata() {
        return mAppFunctionSchemaMetadata;
    }

    /** Returns the {@link AppFunctionPackageMetadata} of the enclosing package. */
    @NonNull
    public AppFunctionPackageMetadata getPackageMetadata() {
        return mAppFunctionPackageMetadata;
    }

    /**
     * Returns the scope of the app function.
     *
     * <p>The scope determines the function's lifecycle and uniqueness rules. Depending on the
     * scope, there could be at most one or multiple functions registered in the system with the
     * same {@link AppFunctionName}.
     *
     * <p>See values below for more details on each scope.
     */
    @Scope
    public int getScope() {
        String xmlValue = getMetadataDocument().getPropertyString(PROPERTY_SCOPE);
        return scopeXmlValueToScope(xmlValue);
    }

    @Scope
    static int scopeXmlValueToScope(@NonNull String xmlValue) {
        return switch (xmlValue) {
            case PROPERTY_VALUE_SCOPE_GLOBAL -> SCOPE_GLOBAL;
            case PROPERTY_VALUE_SCOPE_ACTIVITY -> SCOPE_ACTIVITY;
            default -> throw new IllegalStateException("Unexpected value: " + xmlValue);
        };
    }

    static String scopeToScopeXmlValue(@Scope int scope) {
        return switch (scope) {
            case SCOPE_GLOBAL -> PROPERTY_VALUE_SCOPE_GLOBAL;
            case SCOPE_ACTIVITY -> PROPERTY_VALUE_SCOPE_ACTIVITY;
            default -> throw new IllegalStateException("Unexpected value: " + scope);
        };
    }

    /**
     * Returns the app function's metadata as a {@link GenericDocument}.
     *
     * <p>Client-defined properties can be retrieved using the {@link GenericDocument} property
     * getters.
     *
     * <p>Properties that are not defined in this class (see {@code PROPERTY_*} constants) are not
     * guaranteed to be available or consistent across devices and versions.
     */
    @Override
    @NonNull
    public GenericDocument getMetadataDocument() {
        return mAppFunctionMetadataDocumentWrapper.getValue();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppFunctionMetadata that)) return false;
        return getMetadataDocument().equals(that.getMetadataDocument())
                && getPackageMetadata().equals(that.getPackageMetadata());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getMetadataDocument(), getPackageMetadata());
    }

    @Override
    public String toString() {
        return "AppFunctionMetadata("
                + "appFunctionName="
                + getName()
                + ", "
                + "schemaMetadata="
                + getSchemaMetadata()
                + ", "
                + "packageMetadata="
                + getPackageMetadata()
                + ", "
                + "metadataDocument="
                + mAppFunctionMetadataDocumentWrapper.getValue()
                + ")";
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mAppFunctionName.writeToParcel(dest, flags);
        dest.writeTypedObject(mAppFunctionSchemaMetadata, flags);
        final boolean prev = dest.allowSquashing();
        try {
            mAppFunctionPackageMetadata.writeToParcel(dest, flags);
        } finally {
            dest.restoreAllowSquashing(prev);
        }
        mAppFunctionMetadataDocumentWrapper.writeToParcel(dest, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Nullable
    private static AppFunctionSchemaMetadata getAppFunctionSchemaMetadataOrNull(
            GenericDocument appFunctionMetadataDocument) {
        String category = appFunctionMetadataDocument.getPropertyString(PROPERTY_SCHEMA_CATEGORY);
        String name = appFunctionMetadataDocument.getPropertyString(PROPERTY_SCHEMA_NAME);
        long version = appFunctionMetadataDocument.getPropertyLong(PROPERTY_SCHEMA_VERSION);

        if (category == null || name == null) {
            return null;
        }

        return new AppFunctionSchemaMetadata(category, name, version);
    }

    /** @hide */
    public static final class Builder {
        private final GenericDocument mStaticDocument;
        private final AppFunctionPackageMetadata mPackageMetadata;

        /** The builder to create {@link AppFunctionMetadata}. */
        public Builder(
                @NonNull GenericDocument staticDocument,
                @NonNull AppFunctionPackageMetadata packageMetadata) {
            mStaticDocument = Objects.requireNonNull(staticDocument);
            mPackageMetadata = Objects.requireNonNull(packageMetadata);
        }

        /**
         * Builds {@link AppFunctionPackageMetadata}.
         *
         * @throws IllegalArgumentException If unable to build metadata from the provided arguments.
         */
        public AppFunctionMetadata build() throws IllegalArgumentException {
            Objects.requireNonNull(mStaticDocument);
            Objects.requireNonNull(mPackageMetadata);

            AppFunctionName appFunctionName =
                    AppFunctionName.fromQualifiedId(mStaticDocument.getId());
            AppFunctionSchemaMetadata schemaMetadata =
                    getAppFunctionSchemaMetadataOrNull(mStaticDocument);
            return new AppFunctionMetadata(
                    appFunctionName, schemaMetadata, mPackageMetadata, mStaticDocument);
        }
    }
}
