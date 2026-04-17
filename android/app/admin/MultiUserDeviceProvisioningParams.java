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

package android.app.admin;

import static java.util.Objects.requireNonNull;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.admin.flags.Flags;
import android.content.ComponentName;
import android.stats.devicepolicy.DevicePolicyEnums;

import java.util.Locale;
import java.util.Objects;

/**
 * Params required to provision a multi-user managed device, see {@link
 * DevicePolicyManager#provisionMultiUserDevice}.
 *
 * <p>This will be removed soon. Please use
 * {@link #MultiuserManagedDeviceProvisioningParams} instead.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_MULTI_USER_MANAGEMENT_DEVICE_PROVISIONING)
public final class MultiUserDeviceProvisioningParams {
    private static final String LEAVE_ALL_SYSTEM_APPS_ENABLED_PARAM =
            "LEAVE_ALL_SYSTEM_APPS_ENABLED";
    private static final String TIME_ZONE_PROVIDED_PARAM = "TIME_ZONE_PROVIDED";
    private static final String LOCALE_PROVIDED_PARAM = "LOCALE_PROVIDED";

    private final MultiUserDeviceProvisioningParamsTransport mTransportParams;

    /**
     * Reconstruct params from the transport representation after receiving in the service.
     *
     * @hide
     */
    public MultiUserDeviceProvisioningParams(
            MultiUserDeviceProvisioningParamsTransport transportParams) {
        this.mTransportParams = transportParams;
    }

    /**
     * Generates a transport representation to be passed from DevicePolicyManager to its service.
     */
    MultiUserDeviceProvisioningParamsTransport getTransportParams() {
        return mTransportParams;
    }

    @Nullable
    private static Locale getLocale(@Nullable String localeStr) {
        return localeStr == null ? null : Locale.forLanguageTag(localeStr);
    }

    /** Returns the device controller's {@link ComponentName}. */
    @NonNull
    public ComponentName getDeviceAdminComponentName() {
        return mTransportParams.deviceAdminComponentName;
    }

    /** Returns {@code true} if system apps should be left enabled after provisioning. */
    public boolean isLeaveAllSystemAppsEnabled() {
        return mTransportParams.leaveAllSystemAppsEnabled;
    }

    /**
     * If set, it returns the time zone to set for the device after provisioning, otherwise returns
     * {@code null};
     */
    @Nullable
    public String getTimeZone() {
        return mTransportParams.timeZone;
    }

    /**
     * If set, it returns the local time to set for the device after provisioning, otherwise returns
     * 0.
     */
    public long getLocalTime() {
        return mTransportParams.localTime;
    }

    /**
     * If set, it returns the {@link Locale} to set for the device after provisioning, otherwise
     * returns {@code null}.
     */
    @Nullable
    public @SuppressLint("UseIcu") Locale getLocale() {
        return getLocale(mTransportParams.localeStr);
    }

    /**
     * Logs the provisioning params using {@link DevicePolicyEventLogger}.
     *
     * @hide
     */
    public void logParams(@NonNull String callerPackage) {
        requireNonNull(callerPackage);

        logParam(callerPackage, LEAVE_ALL_SYSTEM_APPS_ENABLED_PARAM,
                 mTransportParams.leaveAllSystemAppsEnabled);
        logParam(callerPackage, TIME_ZONE_PROVIDED_PARAM,
                 /* value= */ mTransportParams.timeZone != null);
        logParam(callerPackage, LOCALE_PROVIDED_PARAM,
                  /* value= */ mTransportParams.localeStr != null);
    }

    private void logParam(String callerPackage, String param, boolean value) {
        DevicePolicyEventLogger.createEvent(DevicePolicyEnums.PLATFORM_PROVISIONING_PARAM)
                .setStrings(callerPackage)
                .setAdmin(mTransportParams.deviceAdminComponentName)
                .setStrings(param)
                .setBoolean(value)
                .write();
    }

    /**
     * Builder class for {@link MultiUserDeviceProvisioningParams} objects.
     *
     * <p>This will be removed soon. Please use
     * {@link MultiuserManagedDeviceProvisioningParams.Builder} instead.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_MULTI_USER_MANAGEMENT_DEVICE_PROVISIONING)
    public static final class Builder {
        private final MultiUserDeviceProvisioningParamsTransport mTransportParams;

        /**
         * Initialize a new {@link Builder} to construct a {@link
         * MultiUserDeviceProvisioningParams}.
         *
         * <p>See {@link DevicePolicyManager#provisionMultiUserDevice}
         *
         * @param deviceAdminComponentName The admin {@link ComponentName} to be set as the device
         *     controller.
         * @throws NullPointerException if {@code deviceAdminComponentName} are null.
         */
        public Builder(@NonNull ComponentName deviceAdminComponentName) {
            requireNonNull(deviceAdminComponentName);
            this.mTransportParams = new MultiUserDeviceProvisioningParamsTransport();
            this.mTransportParams.deviceAdminComponentName = deviceAdminComponentName;

        }

        /**
         * Sets whether non-required system apps should be installed on the created profile when
         * {@link DevicePolicyManager#provisionMultiUserDevice} is called. Defaults to {@code
         * false} if not set.
         */
        @NonNull
        public Builder setLeaveAllSystemAppsEnabled(boolean leaveAllSystemAppsEnabled) {
            this.mTransportParams.leaveAllSystemAppsEnabled = leaveAllSystemAppsEnabled;
            return this;
        }

        /**
         * Sets {@code timeZone} on the device. If not set or set to {@code null}, {@link
         * DevicePolicyManager#provisionMultiUserDevice} will not set a timezone
         */
        @NonNull
        public Builder setTimeZone(@Nullable String timeZone) {
            this.mTransportParams.timeZone = timeZone;
            return this;
        }

        /**
         * Sets {@code localTime} on the device, If not set or set to {@code 0}, {@link
         * DevicePolicyManager#provisionMultiUserDevice} will not set a local time.
         */
        @NonNull
        public Builder setLocalTime(long localTime) {
            this.mTransportParams.localTime = localTime;
            return this;
        }

        /**
         * Sets {@link Locale} on the device, If not set or set to {@code null}, {@link
         * DevicePolicyManager#provisionMultiUserDevice} will not set a locale.
         */
        @NonNull
        public Builder setLocale(@SuppressLint("UseIcu") @Nullable Locale locale) {
            this.mTransportParams.localeStr = (locale != null) ? locale.toLanguageTag() : null;
            return this;
        }

        /**
         * Combines all of the attributes that have been set on this {@code Builder}
         *
         * @return a new {@link MultiUserDeviceProvisioningParams} object.
         */
        @NonNull
        public MultiUserDeviceProvisioningParams build() {
            return new MultiUserDeviceProvisioningParams(mTransportParams);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MultiUserDeviceProvisioningParams other)) {
            return false;
        }
        return getDeviceAdminComponentName().equals(other.getDeviceAdminComponentName())
                && isLeaveAllSystemAppsEnabled() == other.isLeaveAllSystemAppsEnabled()
                && Objects.equals(getTimeZone(), other.getTimeZone())
                && getLocalTime() == other.getLocalTime()
                && Objects.equals(getLocale(), other.getLocale());
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                getDeviceAdminComponentName(),
                isLeaveAllSystemAppsEnabled(),
                getTimeZone(),
                getLocalTime(),
                getLocale());
    }
}
