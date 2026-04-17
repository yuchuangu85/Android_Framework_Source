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
import android.annotation.NonNull;
import android.media.tv.flags.Flags;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

import java.util.List;
import java.util.Map;

/**
 * Disk I/O overuse configuration for a component.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_TV_WATCHDOG_EMMC_PROTECTION)
public final class IoOveruseConfiguration implements Parcelable {
    /**
     * Component level thresholds.
     *
     * <p>These are applied to packages that are not covered by the package specific thresholds or
     * application category specific thresholds. For third-party component, only component level
     * thresholds must be provided and other thresholds are not applicable.
     */
    private @NonNull PerStateBytes mComponentLevelThresholds;

    /**
     * Package specific thresholds only for system and vendor packages.
     *
     * <p>NOTE: For packages that share a UID, the package name should be the shared package name
     * because the I/O usage is aggregated for all packages under the shared UID. The shared package
     * names should have the prefix 'shared:'.
     *
     * <p>System component must provide package specific thresholds only for system packages.
     *
     * <p>Vendor component must provide package specific thresholds only for vendor packages.
     */
    private @NonNull Map<String, PerStateBytes> mPackageSpecificThresholds;

    /**
     * Application category specific thresholds.
     *
     * <p>The key must be one of the {@link ResourceOveruseConfiguration#ApplicationCategoryType}
     * constants.
     *
     * <p>These are applied when package specific thresholds are not provided for a package and a
     * package is covered by one of the {@link
     * ResourceOveruseConfiguration#ApplicationCategoryType}. These thresholds must be provided only
     * by the vendor component.
     */
    private @NonNull Map<String, PerStateBytes> mAppCategorySpecificThresholds;

    /**
     * List of system-wide thresholds used to detect overall disk I/O overuse.
     *
     * <p>These thresholds must be provided only by the system component.
     */
    private @NonNull List<IoOveruseAlertThreshold> mSystemWideThresholds;

    /* package-private */ IoOveruseConfiguration(
            @NonNull PerStateBytes componentLevelThresholds,
            @NonNull Map<String, PerStateBytes> packageSpecificThresholds,
            @NonNull Map<String, PerStateBytes> appCategorySpecificThresholds,
            @NonNull List<IoOveruseAlertThreshold> systemWideThresholds) {
        this.mComponentLevelThresholds = componentLevelThresholds;
        AnnotationValidations.validate(NonNull.class, null, mComponentLevelThresholds);
        this.mPackageSpecificThresholds = packageSpecificThresholds;
        AnnotationValidations.validate(NonNull.class, null, mPackageSpecificThresholds);
        this.mAppCategorySpecificThresholds = appCategorySpecificThresholds;
        AnnotationValidations.validate(NonNull.class, null, mAppCategorySpecificThresholds);
        this.mSystemWideThresholds = systemWideThresholds;
        AnnotationValidations.validate(NonNull.class, null, mSystemWideThresholds);
    }

    /**
     * Component level thresholds.
     *
     * <p>These are applied to packages that are not covered by the package specific thresholds or
     * application category specific thresholds. For third-party component, only component level
     * thresholds must be provided and other thresholds are not applicable.
     */
    public @NonNull PerStateBytes getComponentLevelThresholds() {
        return mComponentLevelThresholds;
    }

    /**
     * Package specific thresholds only for system and vendor packages.
     *
     * <p>NOTE: For packages that share a UID, the package name should be the shared package name
     * because the I/O usage is aggregated for all packages under the shared UID. The shared package
     * names should have the prefix 'shared:'.
     *
     * <p>System component must provide package specific thresholds only for system packages.
     *
     * <p>Vendor component must provide package specific thresholds only for vendor packages.
     */
    public @NonNull Map<String, PerStateBytes> getPackageSpecificThresholds() {
        return mPackageSpecificThresholds;
    }

    /**
     * Application category specific thresholds.
     *
     * <p>The key must be one of the {@link ResourceOveruseConfiguration#ApplicationCategoryType}
     * constants.
     *
     * <p>These are applied when package specific thresholds are not provided for a package and a
     * package is covered by one of the {@link
     * ResourceOveruseConfiguration#ApplicationCategoryType}. These thresholds must be provided only
     * by the vendor component.
     */
    public @NonNull Map<String, PerStateBytes> getAppCategorySpecificThresholds() {
        return mAppCategorySpecificThresholds;
    }

    /**
     * List of system-wide thresholds used to detect overall disk I/O overuse.
     *
     * <p>These thresholds must be provided only by the system component.
     */
    public @NonNull List<IoOveruseAlertThreshold> getSystemWideThresholds() {
        return mSystemWideThresholds;
    }

    @Override
    public String toString() {
        return "IoOveruseConfiguration { "
                + "componentLevelThresholds = "
                + mComponentLevelThresholds
                + ", "
                + "packageSpecificThresholds = "
                + mPackageSpecificThresholds
                + ", "
                + "appCategorySpecificThresholds = "
                + mAppCategorySpecificThresholds
                + ", "
                + "systemWideThresholds = "
                + mSystemWideThresholds
                + " }";
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        dest.writeTypedObject(mComponentLevelThresholds, flags);
        dest.writeMap(mPackageSpecificThresholds);
        dest.writeMap(mAppCategorySpecificThresholds);
        dest.writeParcelableList(mSystemWideThresholds, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    /* package-private */ IoOveruseConfiguration(@NonNull android.os.Parcel in) {
        PerStateBytes componentLevelThresholds =
                (PerStateBytes) in.readTypedObject(PerStateBytes.CREATOR);
        Map<String, PerStateBytes> packageSpecificThresholds = new java.util.LinkedHashMap<>();
        in.readMap(packageSpecificThresholds, PerStateBytes.class.getClassLoader());
        Map<String, PerStateBytes> appCategorySpecificThresholds = new java.util.LinkedHashMap<>();
        in.readMap(appCategorySpecificThresholds, PerStateBytes.class.getClassLoader());
        List<IoOveruseAlertThreshold> systemWideThresholds = new java.util.ArrayList<>();
        in.readParcelableList(systemWideThresholds, IoOveruseAlertThreshold.class.getClassLoader());

        this.mComponentLevelThresholds = componentLevelThresholds;
        AnnotationValidations.validate(NonNull.class, null, mComponentLevelThresholds);
        this.mPackageSpecificThresholds = packageSpecificThresholds;
        AnnotationValidations.validate(NonNull.class, null, mPackageSpecificThresholds);
        this.mAppCategorySpecificThresholds = appCategorySpecificThresholds;
        AnnotationValidations.validate(NonNull.class, null, mAppCategorySpecificThresholds);
        this.mSystemWideThresholds = systemWideThresholds;
        AnnotationValidations.validate(NonNull.class, null, mSystemWideThresholds);
    }

    public static final @NonNull Parcelable.Creator<IoOveruseConfiguration> CREATOR =
            new Parcelable.Creator<IoOveruseConfiguration>() {
                @Override
                public IoOveruseConfiguration[] newArray(int size) {
                    return new IoOveruseConfiguration[size];
                }

                @Override
                public IoOveruseConfiguration createFromParcel(@NonNull android.os.Parcel in) {
                    return new IoOveruseConfiguration(in);
                }
            };

    /** A builder for {@link IoOveruseConfiguration} */
    @SuppressWarnings("WeakerAccess")
    public static final class Builder {

        private @NonNull PerStateBytes mComponentLevelThresholds;
        private @NonNull Map<String, PerStateBytes> mPackageSpecificThresholds;
        private @NonNull Map<String, PerStateBytes> mAppCategorySpecificThresholds;
        private @NonNull List<IoOveruseAlertThreshold> mSystemWideThresholds;

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @param componentLevelThresholds Component level thresholds.
         *     <p>These are applied to packages that are not covered by the package specific
         *     thresholds or application category specific thresholds. For third-party component,
         *     only component level thresholds must be provided and other thresholds are not
         *     applicable.
         * @param packageSpecificThresholds Package specific thresholds only for system and vendor
         *     packages.
         *     <p>NOTE: For packages that share a UID, the package name should be the shared package
         *     name because the I/O usage is aggregated for all packages under the shared UID. The
         *     shared package names should have the prefix 'shared:'.
         *     <p>System component must provide package specific thresholds only for system
         *     packages.
         *     <p>Vendor component must provide package specific thresholds only for vendor
         *     packages.
         * @param appCategorySpecificThresholds Application category specific thresholds.
         *     <p>The key must be one of the {@link
         *     ResourceOveruseConfiguration#ApplicationCategoryType} constants.
         *     <p>These are applied when package specific thresholds are not provided for a package
         *     and a package is covered by one of the {@link
         *     ResourceOveruseConfiguration#ApplicationCategoryType}. These thresholds must be
         *     provided only by the vendor component.
         * @param systemWideThresholds List of system-wide thresholds used to detect overall disk
         *     I/O overuse.
         *     <p>These thresholds must be provided only by the system component.
         */
        public Builder(
                @NonNull PerStateBytes componentLevelThresholds,
                @NonNull Map<String, PerStateBytes> packageSpecificThresholds,
                @NonNull Map<String, PerStateBytes> appCategorySpecificThresholds,
                @NonNull List<IoOveruseAlertThreshold> systemWideThresholds) {
            mComponentLevelThresholds = componentLevelThresholds;
            AnnotationValidations.validate(NonNull.class, null, mComponentLevelThresholds);
            mPackageSpecificThresholds = packageSpecificThresholds;
            AnnotationValidations.validate(NonNull.class, null, mPackageSpecificThresholds);
            mAppCategorySpecificThresholds = appCategorySpecificThresholds;
            AnnotationValidations.validate(NonNull.class, null, mAppCategorySpecificThresholds);
            mSystemWideThresholds = systemWideThresholds;
            AnnotationValidations.validate(NonNull.class, null, mSystemWideThresholds);
        }

        /**
         * Component level thresholds.
         *
         * <p>These are applied to packages that are not covered by the package specific thresholds
         * or application category specific thresholds. For third-party component, only component
         * level thresholds must be provided and other thresholds are not applicable.
         */
        public @NonNull Builder setComponentLevelThresholds(@NonNull PerStateBytes value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mComponentLevelThresholds = value;
            return this;
        }

        /**
         * Package specific thresholds only for system and vendor packages.
         *
         * <p>NOTE: For packages that share a UID, the package name should be the shared package
         * name because the I/O usage is aggregated for all packages under the shared UID. The
         * shared package names should have the prefix 'shared:'.
         *
         * <p>System component must provide package specific thresholds only for system packages.
         *
         * <p>Vendor component must provide package specific thresholds only for vendor packages.
         */
        public @NonNull Builder setPackageSpecificThresholds(
                @NonNull Map<String, PerStateBytes> value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mPackageSpecificThresholds = value;
            return this;
        }

        /**
         * @see #setPackageSpecificThresholds
         */
        public @NonNull Builder addPackageSpecificThresholds(
                @NonNull String key, @NonNull PerStateBytes value) {
            if (mPackageSpecificThresholds == null) {
                setPackageSpecificThresholds(new java.util.LinkedHashMap());
            }
            mPackageSpecificThresholds.put(key, value);
            return this;
        }

        /**
         * Application category specific thresholds.
         *
         * <p>The key must be one of the {@link
         * ResourceOveruseConfiguration#ApplicationCategoryType} constants.
         *
         * <p>These are applied when package specific thresholds are not provided for a package and
         * a package is covered by one of the {@link
         * ResourceOveruseConfiguration#ApplicationCategoryType}. These thresholds must be provided
         * only by the vendor component.
         */
        public @NonNull Builder setAppCategorySpecificThresholds(
                @NonNull Map<String, PerStateBytes> value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mAppCategorySpecificThresholds = value;
            return this;
        }

        /**
         * @see #setAppCategorySpecificThresholds
         */
        public @NonNull Builder addAppCategorySpecificThresholds(
                @NonNull String key, @NonNull PerStateBytes value) {
            if (mAppCategorySpecificThresholds == null) {
                setAppCategorySpecificThresholds(new java.util.LinkedHashMap());
            }
            mAppCategorySpecificThresholds.put(key, value);
            return this;
        }

        /**
         * List of system-wide thresholds used to detect overall disk I/O overuse.
         *
         * <p>These thresholds must be provided only by the system component.
         */
        public @NonNull Builder setSystemWideThresholds(
                @NonNull List<IoOveruseAlertThreshold> value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mSystemWideThresholds = value;
            return this;
        }

        /**
         * @see #setSystemWideThresholds
         */
        public @NonNull Builder addSystemWideThresholds(@NonNull IoOveruseAlertThreshold value) {
            if (mSystemWideThresholds == null) setSystemWideThresholds(new java.util.ArrayList<>());
            mSystemWideThresholds.add(value);
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull IoOveruseConfiguration build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10; // Mark builder used

            IoOveruseConfiguration o =
                    new IoOveruseConfiguration(
                            mComponentLevelThresholds,
                            mPackageSpecificThresholds,
                            mAppCategorySpecificThresholds,
                            mSystemWideThresholds);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x10) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
