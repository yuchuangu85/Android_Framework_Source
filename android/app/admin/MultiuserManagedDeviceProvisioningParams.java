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
import android.annotation.SystemApi;
import android.app.admin.flags.Flags;

import java.util.Objects;

/**
 * Params required to provision a multi-user managed device, see {@link
 * DevicePolicyManager#provisionMultiuserManagedDevice}.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_MULTI_USER_MANAGEMENT_DEVICE_PROVISIONING)
public final class MultiuserManagedDeviceProvisioningParams {
    private final MultiuserManagedDeviceProvisioningParamsTransport mTransportParams;

    /** Returns the device controller's package name. */
    @NonNull
    public String getDeviceControllerPackageName() {
        return mTransportParams.deviceControllerPackageName;
    }

    MultiuserManagedDeviceProvisioningParamsTransport getTransportParams() {
        return mTransportParams;
    }

    /**
     * Construct instance from the transport representation.
     */
    private MultiuserManagedDeviceProvisioningParams(
            @NonNull MultiuserManagedDeviceProvisioningParamsTransport transportParams) {
        mTransportParams = transportParams;
    }


    /**
     * Builder class for {@link MultiuserManagedDeviceProvisioningParams} objects.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_MULTI_USER_MANAGEMENT_DEVICE_PROVISIONING)
    public static final class Builder {
        private final String mDeviceControllerPackageName;

        /**
         * Initialize a new {@link Builder} to construct a {@link
         * MultiuserManagedDeviceProvisioningParams}.
         *
         * <p>See {@link DevicePolicyManager#provisionMultiuserManagedDevice}
         *
         * @param deviceControllerPackageName The package name of the device controller.
         * @throws NullPointerException if {@code deviceControllerPackageName} is null.
         */
        public Builder(@NonNull String deviceControllerPackageName) {
            requireNonNull(deviceControllerPackageName);
            this.mDeviceControllerPackageName = deviceControllerPackageName;
        }

        /**
         * Combines all of the attributes that have been set on this {@code Builder}
         *
         * @return a new {@link MultiuserManagedDeviceProvisioningParams} object.
         */
        @NonNull
        public MultiuserManagedDeviceProvisioningParams build() {
            var transportParams = new MultiuserManagedDeviceProvisioningParamsTransport();
            transportParams.deviceControllerPackageName = mDeviceControllerPackageName;
            return new MultiuserManagedDeviceProvisioningParams(transportParams);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MultiuserManagedDeviceProvisioningParams other)) {
            return false;
        }
        return getDeviceControllerPackageName().equals(other.getDeviceControllerPackageName());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getDeviceControllerPackageName());
    }
}
