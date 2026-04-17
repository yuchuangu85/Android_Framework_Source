/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.app.admin;

import static java.util.Objects.requireNonNull;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.admin.flags.Flags;
import android.content.ComponentName;
import android.os.PersistableBundle;
import android.stats.devicepolicy.DevicePolicyEnums;

/**
 * Params required to provision a managed full user on a multi-user device. See
 * {@link DevicePolicyManager#provisionMultiuserManagedUser}.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_MULTI_USER_MANAGEMENT_USER_PROVISIONING)
public final class MultiuserManagedUserProvisioningParams {
    private static final String LEAVE_ALL_SYSTEM_APPS_ENABLED_PARAM =
            "LEAVE_ALL_SYSTEM_APPS_ENABLED";

    @NonNull
    private final MultiuserManagedUserProvisioningParamsTransport mTransport;

    /**
     * Constructs a new {@link MultiuserManagedUserProvisioningParams} object.
     *
     * @param transport The transport representation of the params.
     * @hide
     */
    public MultiuserManagedUserProvisioningParams(
            @NonNull MultiuserManagedUserProvisioningParamsTransport transport) {
        this.mTransport = transport;
    }

    /**
     * Returns the transport representation of the params.
     *
     * @hide
     */
    public @NonNull MultiuserManagedUserProvisioningParamsTransport getTransportParams() {
        return mTransport;
    }

    /** Returns the profile owner's {@link ComponentName}. */
    @NonNull
    public ComponentName getProfileAdminComponentName() {
        return mTransport.profileAdminComponentName;
    }

    /** Returns {@code true} if system apps should be left enabled after provisioning. */
    public boolean isLeaveAllSystemAppsEnabled() {
        return mTransport.leaveAllSystemAppsEnabled;
    }

    /**
     * Logs the provisioning params using {@link DevicePolicyEventLogger}.
     *
     * @hide
     */
    public void logParams(@NonNull String callerPackage) {
        requireNonNull(callerPackage);

        logParam(callerPackage, LEAVE_ALL_SYSTEM_APPS_ENABLED_PARAM,
                mTransport.leaveAllSystemAppsEnabled);
    }

    private void logParam(String callerPackage, String param, boolean value) {
        DevicePolicyEventLogger
                .createEvent(DevicePolicyEnums.PLATFORM_PROVISIONING_PARAM)
                .setStrings(callerPackage)
                .setAdmin(mTransport.profileAdminComponentName)
                .setStrings(param)
                .setBoolean(value)
                .write();
    }

    /**
     * Builder class for {@link MultiuserManagedUserProvisioningParams} objects.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_MULTI_USER_MANAGEMENT_USER_PROVISIONING)
    public static final class Builder {
        @NonNull
        private final MultiuserManagedUserProvisioningParamsTransport mTransport =
                new MultiuserManagedUserProvisioningParamsTransport();

        /**
         * Initialize a new {@link Builder} to construct a
         * {@link MultiuserManagedUserProvisioningParams}.
         * <p>
         * See {@link DevicePolicyManager#provisionMultiuserManagedUser}
         *
         * @param profileAdminComponentName The admin {@link ComponentName} to be set as the profile
         *                                  owner.
         * @throws NullPointerException if {@code profileAdminComponentName} or
         *                              {@code ownerName} are null.
         */
        public Builder(@NonNull ComponentName profileAdminComponentName) {
            requireNonNull(profileAdminComponentName);
            this.mTransport.profileAdminComponentName = profileAdminComponentName;
        }

        /**
         * Sets whether non-required system apps should be installed on the created user when
         * {@link DevicePolicyManager#provisionMultiuserManagedUser} is called. Defaults to
         * {@code false} if not set.
         */
        @NonNull
        public Builder setLeaveAllSystemAppsEnabled(boolean leaveAllSystemAppsEnabled) {
            this.mTransport.leaveAllSystemAppsEnabled = leaveAllSystemAppsEnabled;
            return this;
        }

        /**
         * Combines all of the attributes that have been set on this {@code Builder}.
         *
         * @return a new {@link MultiuserManagedUserProvisioningParams} object.
         */
        @NonNull
        public MultiuserManagedUserProvisioningParams build() {
            return new MultiuserManagedUserProvisioningParams(mTransport);
        }
    }
}
