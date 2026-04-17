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

package android.app.supervision;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.supervision.flags.Flags;
import android.os.Parcel;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.Objects;

/**
 * A policy that defines the usage type for a package.
 *
 * <p>The usage type can be one of the following:
 *
 * <ul>
 *   <li>{@link #TYPE_ALLOWED}: The package is allowed to be used, without any restrictions.
 *   <li>{@link #TYPE_BLOCKED}: The package is blocked from being used. It will be hidden from the
 *       user and cannot be launched.
 *   <li>{@link #TYPE_TIME_LIMIT}: The package can be used for a limited amount of time each day.
 *       This type requires a time limit to be set via {@link Builder#setTimeLimit(Duration)}. The
 *       time limit must be non-negative and cannot exceed 24 hours. When applied, if the previous
 *       type was {@link #TYPE_BLOCKED}, the package is unhidden, and its usage is tracked and
 *       enforced. The package will be unusable once the time limit is reached.
 * </ul>
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_SUPERVISION_MANAGER_POLICY_APIS)
public final class PackageUsagePolicy extends Policy {
    /** Policy type for allowed package usage. */
    public static final int TYPE_ALLOWED = 0;

    /** Policy type for blocked package usage. */
    public static final int TYPE_BLOCKED = 1;

    /** Policy type for time-limited package usage. */
    @FlaggedApi(Flags.FLAG_ENABLE_SUPERVISION_PACKAGE_USAGE_APIS)
    public static final int TYPE_TIME_LIMIT = 2;

    /** @hide */
    @IntDef(
            prefix = {"TYPE_"},
            value = {
                TYPE_ALLOWED,
                TYPE_BLOCKED,
                TYPE_TIME_LIMIT,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    private final String mPackageName;
    private final @Type int mType;
    private final @Nullable Duration mTimeLimit;

    /**
     * Constructs a new {@link PackageUsagePolicy} with the given version and package name.
     *
     * @param version version of the policy
     * @param packageName package name tor this policy
     */
    private PackageUsagePolicy(
            long version,
            @NonNull String packageName,
            @Type int type,
            @Nullable Duration timeLimit) {
        super(version);
        mPackageName = packageName;
        mType = type;
        mTimeLimit = timeLimit;
    }

    /**
     * Constructs a new {@link PackageUsagePolicy} from a parcel.
     *
     * <p>This method is used to create a policy from a parcel. It is called by the {@link Creator}
     * when unmarshalling a parcel.
     *
     * @param in the source parcel
     * @hide
     */
    PackageUsagePolicy(@NonNull Parcel in) {
        super(in);
        mPackageName = in.readString8();
        mType = in.readInt();
        Long timeLimitMillis = (Long) in.readValue(Long.class.getClassLoader());
        mTimeLimit = timeLimitMillis == null ? null : Duration.ofMillis(timeLimitMillis);
    }

    /**
     * Returns the type of this policy.
     *
     * @return the type of this policy
     */
    public @Type int getType() {
        return mType;
    }

    /**
     * Returns the name of the package to which this policy applies.
     *
     * @return the package name
     */
    public @NonNull String getPackageName() {
        return mPackageName;
    }

    /**
     * Returns the time limit for this policy.
     *
     * @return the time limit
     */
    @FlaggedApi(Flags.FLAG_ENABLE_SUPERVISION_PACKAGE_USAGE_APIS)
    public @Nullable Duration getTimeLimit() {
        return mTimeLimit;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, @WriteFlags int flags) {
        super.writeToParcel(parcel, flags);
        parcel.writeString8(mPackageName);
        parcel.writeInt(mType);
        parcel.writeValue((Long) (mTimeLimit == null ? null : mTimeLimit.toMillis()));
    }

    @NonNull
    public static final Creator<PackageUsagePolicy> CREATOR =
            new Creator<PackageUsagePolicy>() {
                @Override
                public PackageUsagePolicy createFromParcel(@NonNull Parcel in) {
                    // drop the identifier since we know it's a package policy
                    in.readString8();
                    return new PackageUsagePolicy(in);
                }

                @Override
                public PackageUsagePolicy[] newArray(int size) {
                    return new PackageUsagePolicy[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @Override
    public PolicyKey getPolicyKey() {
        return PolicyKey.builder().setType(getIdentifier()).setPackageName(mPackageName).build();
    }

    @Override
    public String toString() {
        return "PackageUsagePolicy{"
                + "packageName="
                + mPackageName
                + ", type="
                + mType
                + ", version="
                + getVersion()
                + ", timeLimit="
                + mTimeLimit
                + '}';
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PackageUsagePolicy that = (PackageUsagePolicy) o;
        return getVersion() == that.getVersion()
                && mPackageName.equals(that.mPackageName)
                && mType == that.mType
                && Objects.equals(mTimeLimit, that.mTimeLimit);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getVersion(), mPackageName, mType, mTimeLimit);
    }

    /** @hide */
    public static boolean isTypeValid(int type) {
        return type == TYPE_ALLOWED
                || type == TYPE_BLOCKED
                || (Flags.enableSupervisionPackageUsageApis() && type == TYPE_TIME_LIMIT);
    }

    /**
     * Builder for {@link PackageUsagePolicy}.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_SUPERVISION_MANAGER_POLICY_APIS)
    public static final class Builder extends Policy.Builder<PackageUsagePolicy, Builder> {
        private String mPackageName;
        private @Type int mType = -1;
        private @Nullable Duration mTimeLimit = null;

        /**
         * Constructs a new builder with the given package name and type.
         *
         * @param packageName the package name to set
         * @param type the type to set, must be one of {@link PackageUsagePolicy#TYPE_ALLOWED},
         *     {@link PackageUsagePolicy#TYPE_BLOCKED} or {@link PackageUsagePolicy#TYPE_TIME_LIMIT}
         */
        public Builder(@NonNull String packageName, @Type int type) {
            setPackageName(packageName);
            setType(type);
        }

        /**
         * Constructs a new builder from an existing policy.
         *
         * @param policy the {@link PackageUsagePolicy} to copy from
         */
        public Builder(@NonNull PackageUsagePolicy policy) {
            super(policy);
            mPackageName = policy.mPackageName;
            mType = policy.mType;
            mTimeLimit = policy.mTimeLimit;
        }

        /**
         * Sets the package name for this policy.
         *
         * @param packageName the package name to set
         * @return this builder
         */
        @NonNull
        public Builder setPackageName(@NonNull String packageName) {
            mPackageName = packageName;
            return this;
        }

        /**
         * Sets the type of this policy.
         *
         * @param type the type to set, must be one of {@link PackageUsagePolicy#TYPE_ALLOWED},
         *     {@link PackageUsagePolicy#TYPE_BLOCKED} or {@link PackageUsagePolicy#TYPE_TIME_LIMIT}
         * @return this builder
         */
        @NonNull
        public Builder setType(@Type int type) {
            mType = type;
            return this;
        }

        /**
         * Sets the time limit for this policy.
         *
         * <p>Required if type is {@link PackageUsagePolicy#TYPE_TIME_LIMIT}
         *
         * @param timeLimit the time limit to set
         * @return this builder
         */
        @NonNull
        @FlaggedApi(Flags.FLAG_ENABLE_SUPERVISION_PACKAGE_USAGE_APIS)
        public Builder setTimeLimit(@Nullable Duration timeLimit) {
            mTimeLimit = timeLimit;
            return this;
        }

        /**
         * Builds the {@link PackageUsagePolicy} object.
         *
         * @return the built {@link PackageUsagePolicy}
         * @throws IllegalStateException if the required fields have not been set
         * @hide
         */
        @NonNull
        @Override
        PackageUsagePolicy performBuild() {
            if (mPackageName == null) {
                throw new IllegalStateException("Package name must be set");
            }

            if (!isTypeValid(mType)) {
                throw new IllegalStateException("Invalid type: " + mType);
            }

            if (mType != TYPE_TIME_LIMIT && mTimeLimit != null) {
                throw new IllegalStateException(
                        "Time limit must not be set for non-time limit types");
            }

            if (mType == TYPE_TIME_LIMIT) {
                if (mTimeLimit == null) {
                    throw new IllegalStateException("Time limit must be set for TYPE_TIME_LIMIT");
                }

                if (mTimeLimit.compareTo(Duration.ofDays(1)) > 0) {
                    throw new IllegalStateException("Time limit cannot be longer than 24 hours");
                }

                if (mTimeLimit.isNegative()) {
                    throw new IllegalStateException("Invalid time limit");
                }
            }

            return new PackageUsagePolicy(mVersion, mPackageName, mType, mTimeLimit);
        }
    }
}
