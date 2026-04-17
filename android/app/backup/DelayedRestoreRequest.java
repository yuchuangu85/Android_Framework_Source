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
package android.app.backup;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.server.backup.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Request for scheduling a restore to be triggered by a future condition.
 *
 * <p>The populated DelayedRestoreRequest is registered with the {@link BackupManager} via {@link
 * BackupManager#scheduleDelayedRestore(DelayedRestoreRequest)}, which will trigger the delayed
 * restore flow once the specified condition is met.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_DELAYED_RESTORE_API)
public final class DelayedRestoreRequest implements Parcelable {
    /** Restore that depends on an app being installed. */
    public static final int TYPE_APP_INSTALL = 1;

    /** Restore that depends on an app being updated. */
    public static final int TYPE_APP_UPDATE = 2;

    /**
     * Restore that depends on the user finishing setup wizard as indicated by {@link
     * android.provider.Settings.Secure#USER_SETUP_COMPLETE} setting.
     */
    public static final int TYPE_SETUP_COMPLETE = 3;

    /**
     * Restore that depends on the managed profile being provisioned indicated by the broadcast
     * action {@link android.app.admin.DeviceAdminReceiver#ACTION_PROFILE_PROVISIONING_COMPLETE}.
     *
     * Note: Work provisioning dependency is not supported yet.
     */
    public static final int TYPE_MANAGED_PROFILE_PROVISIONED = 4;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"TYPE_"},
            value = {
                TYPE_APP_INSTALL,
                TYPE_APP_UPDATE,
                TYPE_SETUP_COMPLETE,
                TYPE_MANAGED_PROFILE_PROVISIONED
            })
    public @interface Type {}

    private static final Set<Integer> VALID_TYPES =
            new HashSet<>(
                    Arrays.asList(
                            TYPE_APP_UPDATE,
                            TYPE_APP_INSTALL,
                            TYPE_SETUP_COMPLETE,
                            TYPE_MANAGED_PROFILE_PROVISIONED));

    private static final Set<Integer> TYPES_REQUIRING_PACKAGE_NAME =
            new HashSet<>(Arrays.asList(TYPE_APP_UPDATE, TYPE_APP_INSTALL));

    /**
     * The package name of the app that the restore depends on. This is only required for
     * TYPE_APP_INSTALL and TYPE_APP_UPDATE.
     */
    private final @Type int mType;

    private final @Nullable String mPackageName;

    private DelayedRestoreRequest(@Type int type, @Nullable String packageName) {
        mType = type;
        mPackageName = packageName;
    }

    private DelayedRestoreRequest(@NonNull Parcel in) {
        mType = in.readInt();
        mPackageName = in.readString8();
    }

    /**
     * Returns the package name for this {@link DelayedRestoreRequest}.
     *
     * @return the package name, or {@code null} if there is no package name for this request.
     */
    public @Nullable String getPackageName() {
        return mPackageName;
    }

    /** Returns the request type that this {@link DelayedRestoreRequest} is pending on. */
    public @Type int getType() {
        return mType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DelayedRestoreRequest that = (DelayedRestoreRequest) o;
        return mType == that.mType && Objects.equals(mPackageName, that.mPackageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mPackageName);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeString8(mPackageName);
    }

    /** Returns 0 since this object does not require special handling during parceling. */
    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<DelayedRestoreRequest> CREATOR =
            new Creator<DelayedRestoreRequest>() {
                @Override
                public DelayedRestoreRequest createFromParcel(Parcel in) {
                    return new DelayedRestoreRequest(in);
                }

                @Override
                public DelayedRestoreRequest[] newArray(int size) {
                    return new DelayedRestoreRequest[size];
                }
            };

    @FlaggedApi(Flags.FLAG_ENABLE_DELAYED_RESTORE_API)
    public static final class Builder {
        private final @Type int mType;
        private @Nullable String mPackageName;

        /**
         * Creates a new Builder for a {@link DelayedRestoreRequest}
         *
         * @param type the type of delayed restore to schedule
         */
        public Builder(@Type int type) {
            mType = type;
        }

        /**
         * Sets the dependent for an install condition. Basically, this is the package name of the
         * app that is being installed or updated. You don't need to set this for other types.
         *
         * @param packageName the package name on which the delayed restore depends
         */
        public @NonNull Builder setPackageName(@NonNull String packageName) {
            if (!TYPES_REQUIRING_PACKAGE_NAME.contains(mType) && packageName != null) {
                throw new IllegalStateException(
                        "Package name should not be set for type: " + mType);
            }
            mPackageName = packageName;
            return this;
        }

        /**
         * Creates a new instance of {@link DelayedRestoreRequest}.
         *
         * @return the new instance.
         * @throws IllegalStateException when a mandatory attribute was not yet set.
         */
        public @NonNull DelayedRestoreRequest build() {
            if (!VALID_TYPES.contains(mType)) {
                throw new IllegalArgumentException("Invalid type: " + mType);
            }
            if (TYPES_REQUIRING_PACKAGE_NAME.contains(mType) && mPackageName == null) {
                throw new IllegalStateException("Package name is required for type: " + mType);
            }

            return new DelayedRestoreRequest(mType, mPackageName);
        }
    }
}
