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
import android.annotation.NonNull;
import android.annotation.StringDef;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.supervision.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Parcelable.Creator;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Base class for supervision policies.
 *
 * @hide
 */
@SystemApi
@SuppressLint({"ParcelNotFinal", "ParcelCreator"})
@FlaggedApi(Flags.FLAG_ENABLE_SUPERVISION_MANAGER_POLICY_APIS)
public abstract class Policy implements Parcelable {
    /**
     * The identifier of the package policy. This is used to identify the policy type when
     * reconstructing the policy from storage, parcel or deserializing.
     *
     * @hide
     */
    public static final String PACKAGE_POLICY_IDENTIFIER = "package";

    /**
     * The identifier of the policy. This is used to identify the policy type when reconstructing
     * the policy from storage, parcel or deserializing.
     *
     * @hide
     */
    @StringDef({PACKAGE_POLICY_IDENTIFIER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PolicyIdentifier {}

    private long mVersion;

    /**
     * Increments the version of this policy.
     *
     * @hide
     */
    public final void incrementVersion() {
        mVersion++;
    }

    /**
     * Returns the version of the policy.
     *
     * @return the policy version
     */
    public long getVersion() {
        return mVersion;
    }

    /**
     * Constructs a new policy.
     *
     * @param version the version of the policy
     * @hide
     */
    Policy(long version) {
        mVersion = version;
    }

    /**
     * Constructs a new policy from a parcel.
     *
     * <p>This method is used to create a policy from a parcel. It is called by the {@link Creator}
     * when unmarshalling a parcel.
     *
     * @param in the source parcel
     * @hide
     */
    Policy(@NonNull Parcel in) {
        mVersion = in.readLong();
    }

    /**
     * Returns the identifier of the policy to be used to identify the policy type.
     *
     * @hide
     */
    @NonNull
    public @PolicyIdentifier String getIdentifier() {
        return switch (this) {
            case PackageUsagePolicy pp -> Policy.PACKAGE_POLICY_IDENTIFIER;
            default ->
                    throw new IllegalArgumentException(
                            "Unsupported policy type: " + this.getClass().getSimpleName());
        };
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, @WriteFlags int flags) {
        parcel.writeString8(getIdentifier());
        parcel.writeLong(mVersion);
    }

    /**
     * Returns the key of the policy.
     *
     * @hide
     */
    @NonNull
    public PolicyKey getPolicyKey() {
        return PolicyKey.builder().setType(getIdentifier()).build();
    }

    @NonNull
    public static final Creator<Policy> CREATOR =
            new Creator<Policy>() {
                @Override
                public Policy createFromParcel(@NonNull Parcel in) {
                    String policyIdentifier = in.readString8();

                    return switch (policyIdentifier) {
                        case PACKAGE_POLICY_IDENTIFIER -> new PackageUsagePolicy(in);
                        default ->
                                throw new IllegalArgumentException(
                                        "Unsupported policy identifier value: " + policyIdentifier);
                    };
                }

                @Override
                public Policy[] newArray(int size) {
                    return new Policy[size];
                }
            };

    /**
     * Builder for {@link Policy} objects.
     *
     * @param <P> the type of {@link Policy} this builder builds
     * @param <B> the concrete builder implementation
     * @hide
     */
    @SuppressLint("StaticFinalBuilder")
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_SUPERVISION_MANAGER_POLICY_APIS)
    public abstract static class Builder<P extends Policy, B extends Builder<P, B>> {
        long mVersion = 0;

        /** Constructs a new builder. */
        Builder() {}

        /**
         * Constructs a new builder from an existing policy.
         *
         * @param policy the policy to copy from
         */
        Builder(@NonNull Policy policy) {
            setVersion(policy.mVersion);
        }

        /**
         * Sets the version of this policy.
         *
         * @param version the version to set
         * @return this builder
         */
        @NonNull
        public B setVersion(long version) {
            mVersion = version;
            return (B) this;
        }

        /**
         * Builds the {@link Policy} object.
         *
         * @return the built {@link Policy}
         * @throws IllegalStateException if the required fields have not been set
         */
        @NonNull
        public P build() {
            return performBuild();
        }

        /** @hide */
        abstract P performBuild();
    }
}
