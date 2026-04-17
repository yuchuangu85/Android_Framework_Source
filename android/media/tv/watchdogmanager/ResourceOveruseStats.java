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
import android.annotation.Nullable;
import android.media.tv.flags.Flags;
import android.os.Parcelable;
import android.os.UserHandle;

import com.android.internal.util.AnnotationValidations;

/**
 * Resource overuse stats for a package.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_TV_WATCHDOG_EMMC_PROTECTION)
public final class ResourceOveruseStats implements Parcelable {
    /**
     * Name of the package, whose stats are recorded in the below fields.
     *
     * <p>NOTE: For packages that share a UID, the package name will be the shared package name
     * because the stats are aggregated for all packages under the shared UID.
     */
    private @NonNull String mPackageName;

    /** User handle, whose stats are recorded in the below fields. */
    private @NonNull UserHandle mUserHandle;

    /**
     * I/O overuse stats for the package.
     *
     * <p>If the package didn't opt-in to receive I/O overuse stats or the package doesn't have I/O
     * overuse stats, this value will be null.
     */
    private @Nullable IoOveruseStats mIoOveruseStats = null;

    /* package-private */ ResourceOveruseStats(
            @NonNull String packageName,
            @NonNull UserHandle userHandle,
            @Nullable IoOveruseStats ioOveruseStats) {
        this.mPackageName = packageName;
        AnnotationValidations.validate(NonNull.class, null, mPackageName);
        this.mUserHandle = userHandle;
        AnnotationValidations.validate(NonNull.class, null, mUserHandle);
        this.mIoOveruseStats = ioOveruseStats;
    }

    /**
     * Name of the package, whose stats are recorded in the below fields.
     *
     * <p>NOTE: For packages that share a UID, the package name will be the shared package name
     * because the stats are aggregated for all packages under the shared UID.
     */
    public @NonNull String getPackageName() {
        return mPackageName;
    }

    /** User handle, whose stats are recorded in the below fields. */
    public @NonNull UserHandle getUserHandle() {
        return mUserHandle;
    }

    /**
     * I/O overuse stats for the package.
     *
     * <p>If the package didn't opt-in to receive I/O overuse stats or the package doesn't have I/O
     * overuse stats, this value will be null.
     */
    public @Nullable IoOveruseStats getIoOveruseStats() {
        return mIoOveruseStats;
    }

    @Override
    public String toString() {
        return "ResourceOveruseStats { "
                + "packageName = "
                + mPackageName
                + ", "
                + "userHandle = "
                + mUserHandle
                + ", "
                + "ioOveruseStats = "
                + mIoOveruseStats
                + " }";
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        byte flg = 0;
        if (mIoOveruseStats != null) flg |= 0x4;
        dest.writeByte(flg);
        dest.writeString(mPackageName);
        dest.writeTypedObject(mUserHandle, flags);
        if (mIoOveruseStats != null) dest.writeTypedObject(mIoOveruseStats, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    /* package-private */ ResourceOveruseStats(@NonNull android.os.Parcel in) {
        byte flg = in.readByte();
        String packageName = in.readString();
        UserHandle userHandle = (UserHandle) in.readTypedObject(UserHandle.CREATOR);
        IoOveruseStats ioOveruseStats =
                (flg & 0x4) == 0
                        ? null
                        : (IoOveruseStats) in.readTypedObject(IoOveruseStats.CREATOR);

        this.mPackageName = packageName;
        AnnotationValidations.validate(NonNull.class, null, mPackageName);
        this.mUserHandle = userHandle;
        AnnotationValidations.validate(NonNull.class, null, mUserHandle);
        this.mIoOveruseStats = ioOveruseStats;
    }

    public static final @NonNull Parcelable.Creator<ResourceOveruseStats> CREATOR =
            new Parcelable.Creator<ResourceOveruseStats>() {
                @Override
                public ResourceOveruseStats[] newArray(int size) {
                    return new ResourceOveruseStats[size];
                }

                @Override
                public ResourceOveruseStats createFromParcel(@NonNull android.os.Parcel in) {
                    return new ResourceOveruseStats(in);
                }
            };

    /**
     * A builder for {@link ResourceOveruseStats}
     *
     * @hide
     */
    @SuppressWarnings("WeakerAccess")
    public static final class Builder {

        private @NonNull String mPackageName;
        private @NonNull UserHandle mUserHandle;
        private @Nullable IoOveruseStats mIoOveruseStats;

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @param packageName Name of the package, whose stats are recorded in the below fields.
         *     NOTE: For packages that share a UID, the package name will be the shared package name
         *     because the stats are aggregated for all packages under the shared UID.
         * @param userHandle User handle, whose stats are recorded in the below fields.
         */
        public Builder(@NonNull String packageName, @NonNull UserHandle userHandle) {
            mPackageName = packageName;
            AnnotationValidations.validate(NonNull.class, null, mPackageName);
            mUserHandle = userHandle;
            AnnotationValidations.validate(NonNull.class, null, mUserHandle);
        }

        /**
         * Name of the package, whose stats are recorded in the below fields.
         *
         * <p>NOTE: For packages that share a UID, the package name will be the shared package name
         * because the stats are aggregated for all packages under the shared UID.
         */
        public @NonNull Builder setPackageName(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mPackageName = value;
            return this;
        }

        /** User handle, whose stats are recorded in the below fields. */
        public @NonNull Builder setUserHandle(@NonNull UserHandle value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mUserHandle = value;
            return this;
        }

        /**
         * I/O overuse stats for the package.
         *
         * <p>If the package didn't opt-in to receive I/O overuse stats or the package doesn't have
         * I/O overuse stats, this value will be null.
         */
        public @NonNull Builder setIoOveruseStats(@NonNull IoOveruseStats value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mIoOveruseStats = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull ResourceOveruseStats build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8; // Mark builder used

            if ((mBuilderFieldsSet & 0x4) == 0) {
                mIoOveruseStats = null;
            }
            ResourceOveruseStats o =
                    new ResourceOveruseStats(mPackageName, mUserHandle, mIoOveruseStats);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x8) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
