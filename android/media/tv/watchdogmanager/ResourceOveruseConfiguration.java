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

package android.media.tv.watchdogmanager;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.media.tv.flags.Flags;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;

/**
 * Resource overuse configuration for a component.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_TV_WATCHDOG_EMMC_PROTECTION)
public final class ResourceOveruseConfiguration implements Parcelable {
    /** System component. */
    @ComponentType public static final int COMPONENT_TYPE_SYSTEM = 1;

    /** Vendor component. */
    @ComponentType public static final int COMPONENT_TYPE_VENDOR = 2;

    /** Third party component. */
    @ComponentType public static final int COMPONENT_TYPE_THIRD_PARTY = 3;

    /** Map applications. */
    @ApplicationCategoryType
    public static final String APPLICATION_CATEGORY_TYPE_MAPS =
            "android.car.watchdog.app.category.MAPS";

    /** Media applications. */
    @ApplicationCategoryType
    public static final String APPLICATION_CATEGORY_TYPE_MEDIA =
            "android.car.watchdog.app.category.MEDIA";

    /** Component type of the I/O overuse configuration. */
    private @ComponentType int mComponentType;

    /**
     * List of system or vendor packages that are safe to be killed on resource overuse.
     *
     * <p>When specifying shared package names, the package names should contain the prefix
     * 'shared:'.
     *
     * <p>System components must provide only safe-to-kill system packages in this list.
     *
     * <p>Vendor components must provide only safe-to-kill vendor packages in this list.
     */
    private @NonNull List<String> mSafeToKillPackages;

    /**
     * List of vendor package prefixes.
     *
     * <p>Any pre-installed package name starting with one of the prefixes or any package from the
     * vendor partition is identified as a vendor package and vendor provided thresholds are applied
     * to these packages. This list must be provided only by the vendor component.
     *
     * <p>When specifying shared package name prefixes, the prefix should contain 'shared:' at the
     * beginning.
     */
    private @NonNull List<String> mVendorPackagePrefixes;

    /**
     * Mappings from package name to application category types.
     *
     * <p>This mapping must contain only packages that can be mapped to one of the {@link
     * ApplicationCategoryType} types. This mapping must be defined only by the system and vendor
     * components.
     *
     * <p>For packages under a shared UID, the application category type must be specified for the
     * shared package name and not for individual packages under the shared UID. When specifying
     * shared package names, the package names should contain the prefix 'shared:'.
     */
    private @NonNull Map<String, String> mPackagesToAppCategoryTypes;

    /**
     * I/O overuse configuration for the component specified by {@link
     * ResourceOveruseConfiguration#getComponentType}.
     */
    private @Nullable IoOveruseConfiguration mIoOveruseConfiguration = null;

    /** @hide */
    @IntDef(
            prefix = "COMPONENT_TYPE_",
            value = {COMPONENT_TYPE_SYSTEM, COMPONENT_TYPE_VENDOR, COMPONENT_TYPE_THIRD_PARTY})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ComponentType {}

    /** @hide */
    public static String componentTypeToString(@ComponentType int value) {
        switch (value) {
            case COMPONENT_TYPE_SYSTEM:
                return "COMPONENT_TYPE_SYSTEM";
            case COMPONENT_TYPE_VENDOR:
                return "COMPONENT_TYPE_VENDOR";
            case COMPONENT_TYPE_THIRD_PARTY:
                return "COMPONENT_TYPE_THIRD_PARTY";
            default:
                return Integer.toHexString(value);
        }
    }

    /** @hide */
    @StringDef(
            prefix = "APPLICATION_CATEGORY_TYPE_",
            value = {APPLICATION_CATEGORY_TYPE_MAPS, APPLICATION_CATEGORY_TYPE_MEDIA})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ApplicationCategoryType {}

    /* package-private */ ResourceOveruseConfiguration(
            @ComponentType int componentType,
            @NonNull List<String> safeToKillPackages,
            @NonNull List<String> vendorPackagePrefixes,
            @NonNull Map<String, String> packagesToAppCategoryTypes,
            @Nullable IoOveruseConfiguration ioOveruseConfiguration) {
        this.mComponentType = componentType;

        if (!(mComponentType == COMPONENT_TYPE_SYSTEM)
                && !(mComponentType == COMPONENT_TYPE_VENDOR)
                && !(mComponentType == COMPONENT_TYPE_THIRD_PARTY)) {
            throw new java.lang.IllegalArgumentException(
                    "componentType was "
                            + mComponentType
                            + " but must be one of: "
                            + "COMPONENT_TYPE_SYSTEM("
                            + COMPONENT_TYPE_SYSTEM
                            + "), "
                            + "COMPONENT_TYPE_VENDOR("
                            + COMPONENT_TYPE_VENDOR
                            + "), "
                            + "COMPONENT_TYPE_THIRD_PARTY("
                            + COMPONENT_TYPE_THIRD_PARTY
                            + ")");
        }

        this.mSafeToKillPackages = safeToKillPackages;
        AnnotationValidations.validate(NonNull.class, null, mSafeToKillPackages);
        this.mVendorPackagePrefixes = vendorPackagePrefixes;
        AnnotationValidations.validate(NonNull.class, null, mVendorPackagePrefixes);
        this.mPackagesToAppCategoryTypes = packagesToAppCategoryTypes;
        AnnotationValidations.validate(NonNull.class, null, mPackagesToAppCategoryTypes);
        this.mIoOveruseConfiguration = ioOveruseConfiguration;
    }

    /** Component type of the I/O overuse configuration. */
    public @ComponentType int getComponentType() {
        return mComponentType;
    }

    /**
     * List of system or vendor packages that are safe to be killed on resource overuse.
     *
     * <p>When specifying shared package names, the package names should contain the prefix
     * 'shared:'.
     *
     * <p>System components must provide only safe-to-kill system packages in this list.
     *
     * <p>Vendor components must provide only safe-to-kill vendor packages in this list.
     */
    public @NonNull List<String> getSafeToKillPackages() {
        return mSafeToKillPackages;
    }

    /**
     * List of vendor package prefixes.
     *
     * <p>Any pre-installed package name starting with one of the prefixes or any package from the
     * vendor partition is identified as a vendor package and vendor provided thresholds are applied
     * to these packages. This list must be provided only by the vendor component.
     *
     * <p>When specifying shared package name prefixes, the prefix should contain 'shared:' at the
     * beginning.
     */
    public @NonNull List<String> getVendorPackagePrefixes() {
        return mVendorPackagePrefixes;
    }

    /**
     * Mappings from package name to application category types.
     *
     * <p>This mapping must contain only packages that can be mapped to one of the {@link
     * ApplicationCategoryType} types. This mapping must be defined only by the system and vendor
     * components.
     *
     * <p>For packages under a shared UID, the application category type must be specified for the
     * shared package name and not for individual packages under the shared UID. When specifying
     * shared package names, the package names should contain the prefix 'shared:'.
     */
    public @NonNull Map<String, String> getPackagesToAppCategoryTypes() {
        return mPackagesToAppCategoryTypes;
    }

    /**
     * I/O overuse configuration for the component specified by {@link
     * ResourceOveruseConfiguration#getComponentType}.
     */
    public @Nullable IoOveruseConfiguration getIoOveruseConfiguration() {
        return mIoOveruseConfiguration;
    }

    @Override
    public String toString() {
        return "ResourceOveruseConfiguration { "
                + "componentType = "
                + componentTypeToString(mComponentType)
                + ", "
                + "safeToKillPackages = "
                + mSafeToKillPackages
                + ", "
                + "vendorPackagePrefixes = "
                + mVendorPackagePrefixes
                + ", "
                + "packagesToAppCategoryTypes = "
                + mPackagesToAppCategoryTypes
                + ", "
                + "ioOveruseConfiguration = "
                + mIoOveruseConfiguration
                + " }";
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        byte flg = 0;
        if (mIoOveruseConfiguration != null) flg |= 0x10;
        dest.writeByte(flg);
        dest.writeInt(mComponentType);
        dest.writeStringList(mSafeToKillPackages);
        dest.writeStringList(mVendorPackagePrefixes);
        dest.writeMap(mPackagesToAppCategoryTypes);
        if (mIoOveruseConfiguration != null) dest.writeTypedObject(mIoOveruseConfiguration, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    /* package-private */ ResourceOveruseConfiguration(@NonNull android.os.Parcel in) {
        byte flg = in.readByte();
        int componentType = in.readInt();
        List<String> safeToKillPackages = new java.util.ArrayList<>();
        in.readStringList(safeToKillPackages);
        List<String> vendorPackagePrefixes = new java.util.ArrayList<>();
        in.readStringList(vendorPackagePrefixes);
        Map<String, String> packagesToAppCategoryTypes = new java.util.LinkedHashMap<>();
        in.readMap(packagesToAppCategoryTypes, String.class.getClassLoader());
        IoOveruseConfiguration ioOveruseConfiguration =
                (flg & 0x10) == 0
                        ? null
                        : (IoOveruseConfiguration)
                                in.readTypedObject(IoOveruseConfiguration.CREATOR);

        this.mComponentType = componentType;

        if (!(mComponentType == COMPONENT_TYPE_SYSTEM)
                && !(mComponentType == COMPONENT_TYPE_VENDOR)
                && !(mComponentType == COMPONENT_TYPE_THIRD_PARTY)) {
            throw new java.lang.IllegalArgumentException(
                    "componentType was "
                            + mComponentType
                            + " but must be one of: "
                            + "COMPONENT_TYPE_SYSTEM("
                            + COMPONENT_TYPE_SYSTEM
                            + "), "
                            + "COMPONENT_TYPE_VENDOR("
                            + COMPONENT_TYPE_VENDOR
                            + "), "
                            + "COMPONENT_TYPE_THIRD_PARTY("
                            + COMPONENT_TYPE_THIRD_PARTY
                            + ")");
        }

        this.mSafeToKillPackages = safeToKillPackages;
        AnnotationValidations.validate(NonNull.class, null, mSafeToKillPackages);
        this.mVendorPackagePrefixes = vendorPackagePrefixes;
        AnnotationValidations.validate(NonNull.class, null, mVendorPackagePrefixes);
        this.mPackagesToAppCategoryTypes = packagesToAppCategoryTypes;
        AnnotationValidations.validate(NonNull.class, null, mPackagesToAppCategoryTypes);
        this.mIoOveruseConfiguration = ioOveruseConfiguration;
    }

    public static final @NonNull Parcelable.Creator<ResourceOveruseConfiguration> CREATOR =
            new Parcelable.Creator<ResourceOveruseConfiguration>() {
                @Override
                public ResourceOveruseConfiguration[] newArray(int size) {
                    return new ResourceOveruseConfiguration[size];
                }

                @Override
                public ResourceOveruseConfiguration createFromParcel(
                        @NonNull android.os.Parcel in) {
                    return new ResourceOveruseConfiguration(in);
                }
            };

    /** A builder for {@link ResourceOveruseConfiguration} */
    @SuppressWarnings("WeakerAccess")
    public static final class Builder {

        private @ComponentType int mComponentType;
        private @NonNull List<String> mSafeToKillPackages;
        private @NonNull List<String> mVendorPackagePrefixes;
        private @NonNull Map<String, String> mPackagesToAppCategoryTypes;
        private @Nullable IoOveruseConfiguration mIoOveruseConfiguration;

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @param componentType Component type of the I/O overuse configuration.
         * @param safeToKillPackages List of system or vendor packages that are safe to be killed on
         *     resource overuse.
         *     <p>When specifying shared package names, the package names should contain the prefix
         *     'shared:'.
         *     <p>System components must provide only safe-to-kill system packages in this list.
         *     <p>Vendor components must provide only safe-to-kill vendor packages in this list.
         * @param vendorPackagePrefixes List of vendor package prefixes.
         *     <p>Any pre-installed package name starting with one of the prefixes or any package
         *     from the vendor partition is identified as a vendor package and vendor provided
         *     thresholds are applied to these packages. This list must be provided only by the
         *     vendor component.
         *     <p>When specifying shared package name prefixes, the prefix should contain 'shared:'
         *     at the beginning.
         * @param packagesToAppCategoryTypes Mappings from package name to application category
         *     types.
         *     <p>This mapping must contain only packages that can be mapped to one of the {@link
         *     ApplicationCategoryType} types. This mapping must be defined only by the system and
         *     vendor components.
         *     <p>For packages under a shared UID, the application category type must be specified
         *     for the shared package name and not for individual packages under the shared UID.
         *     When specifying shared package names, the package names should contain the prefix
         *     'shared:'.
         */
        public Builder(
                @ComponentType int componentType,
                @NonNull List<String> safeToKillPackages,
                @NonNull List<String> vendorPackagePrefixes,
                @NonNull Map<String, String> packagesToAppCategoryTypes) {
            mComponentType = componentType;

            if (!(mComponentType == COMPONENT_TYPE_SYSTEM)
                    && !(mComponentType == COMPONENT_TYPE_VENDOR)
                    && !(mComponentType == COMPONENT_TYPE_THIRD_PARTY)) {
                throw new java.lang.IllegalArgumentException(
                        "componentType was "
                                + mComponentType
                                + " but must be one of: "
                                + "COMPONENT_TYPE_SYSTEM("
                                + COMPONENT_TYPE_SYSTEM
                                + "), "
                                + "COMPONENT_TYPE_VENDOR("
                                + COMPONENT_TYPE_VENDOR
                                + "), "
                                + "COMPONENT_TYPE_THIRD_PARTY("
                                + COMPONENT_TYPE_THIRD_PARTY
                                + ")");
            }

            mSafeToKillPackages = safeToKillPackages;
            AnnotationValidations.validate(NonNull.class, null, mSafeToKillPackages);
            mVendorPackagePrefixes = vendorPackagePrefixes;
            AnnotationValidations.validate(NonNull.class, null, mVendorPackagePrefixes);
            mPackagesToAppCategoryTypes = packagesToAppCategoryTypes;
            AnnotationValidations.validate(NonNull.class, null, mPackagesToAppCategoryTypes);
        }

        /** Component type of the I/O overuse configuration. */
        public @NonNull Builder setComponentType(@ComponentType int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mComponentType = value;
            return this;
        }

        /**
         * List of system or vendor packages that are safe to be killed on resource overuse.
         *
         * <p>When specifying shared package names, the package names should contain the prefix
         * 'shared:'.
         *
         * <p>System components must provide only safe-to-kill system packages in this list.
         *
         * <p>Vendor components must provide only safe-to-kill vendor packages in this list.
         */
        public @NonNull Builder setSafeToKillPackages(@NonNull List<String> value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mSafeToKillPackages = value;
            return this;
        }

        /**
         * @see #setSafeToKillPackages
         */
        public @NonNull Builder addSafeToKillPackages(@NonNull String value) {
            if (mSafeToKillPackages == null) setSafeToKillPackages(new java.util.ArrayList<>());
            mSafeToKillPackages.add(value);
            return this;
        }

        /**
         * List of vendor package prefixes.
         *
         * <p>Any pre-installed package name starting with one of the prefixes or any package from
         * the vendor partition is identified as a vendor package and vendor provided thresholds are
         * applied to these packages. This list must be provided only by the vendor component.
         *
         * <p>When specifying shared package name prefixes, the prefix should contain 'shared:' at
         * the beginning.
         */
        public @NonNull Builder setVendorPackagePrefixes(@NonNull List<String> value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mVendorPackagePrefixes = value;
            return this;
        }

        /**
         * @see #setVendorPackagePrefixes
         */
        public @NonNull Builder addVendorPackagePrefixes(@NonNull String value) {
            if (mVendorPackagePrefixes == null) {
                setVendorPackagePrefixes(new java.util.ArrayList<>());
            }
            mVendorPackagePrefixes.add(value);
            return this;
        }

        /**
         * Mappings from package name to application category types.
         *
         * <p>This mapping must contain only packages that can be mapped to one of the {@link
         * ApplicationCategoryType} types. This mapping must be defined only by the system and
         * vendor components.
         *
         * <p>For packages under a shared UID, the application category type must be specified for
         * the shared package name and not for individual packages under the shared UID. When
         * specifying shared package names, the package names should contain the prefix 'shared:'.
         */
        public @NonNull Builder setPackagesToAppCategoryTypes(@NonNull Map<String, String> value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mPackagesToAppCategoryTypes = value;
            return this;
        }

        /**
         * @see #setPackagesToAppCategoryTypes
         */
        public @NonNull Builder addPackagesToAppCategoryTypes(
                @NonNull String key, @NonNull String value) {
            if (mPackagesToAppCategoryTypes == null) {
                setPackagesToAppCategoryTypes(new java.util.LinkedHashMap());
            }
            mPackagesToAppCategoryTypes.put(key, value);
            return this;
        }

        /**
         * I/O overuse configuration for the component specified by {@link
         * ResourceOveruseConfiguration#getComponentType}.
         */
        public @NonNull Builder setIoOveruseConfiguration(@NonNull IoOveruseConfiguration value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10;
            mIoOveruseConfiguration = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull ResourceOveruseConfiguration build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x20; // Mark builder used

            if ((mBuilderFieldsSet & 0x10) == 0) {
                mIoOveruseConfiguration = null;
            }
            ResourceOveruseConfiguration o =
                    new ResourceOveruseConfiguration(
                            mComponentType,
                            mSafeToKillPackages,
                            mVendorPackagePrefixes,
                            mPackagesToAppCategoryTypes,
                            mIoOveruseConfiguration);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x20) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
