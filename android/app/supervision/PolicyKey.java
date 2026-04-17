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
import android.app.supervision.flags.Flags;

import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * Represents the key of a supervision policy.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_SUPERVISION_MANAGER_POLICY_APIS)
public final class PolicyKey {
    private final String mType;
    private final @Nullable String mPackageName; // optional

    private PolicyKey(String type, @Nullable String packageName) {
        this.mType = type;
        this.mPackageName = packageName;
    }

    /**
     * Returns the type of the policy.
     *
     * @return The policy type.
     * @hide
     */
    @NonNull
    public String getType() {
        return mType;
    }

    /**
     * Returns the package name of the policy.
     *
     * @return The package name of the policy or null if the policy is not package-specific.
     * @hide
     */
    public @Nullable String getPackageName() {
        return mPackageName;
    }

    /**
     * Returns a new PolicyKey builder instance.
     *
     * @hide
     */
    @NonNull
    public static Builder builder() {
        return new Builder();
    }

    /** @hide */
    public static final class Builder {
        private String type;
        private @Nullable String packageName;

        private Builder() {}

        /** @hide */
        @NonNull
        public Builder setType(@NonNull String type) {
            this.type = type;
            return this;
        }

        /** @hide */
        @NonNull
        public Builder setPackageName(@NonNull String packageName) {
            this.packageName = packageName;
            return this;
        }

        @NonNull
        public final PolicyKey build() {
            return new PolicyKey(type, packageName);
        }
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof PolicyKey other)) {
            return false;
        }
        return mType.equals(other.getType())
                && Objects.equals(mPackageName, other.getPackageName());
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mPackageName);
    }

    @Override
    public String toString() {
        return "PolicyKey{" + "type=" + mType + ", packageName=" + mPackageName + '}';
    }
}
