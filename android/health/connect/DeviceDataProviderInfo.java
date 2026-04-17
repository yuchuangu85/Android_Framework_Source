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

package android.health.connect;

import static com.android.healthfitness.flags.Flags.FLAG_DEVICE_DATA_PROVIDERS_API;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.health.connect.device.DeviceDataTypeAdvertisement;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Represents the information for a specific Device Data Provider application that manages a device
 * and its supported data types.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
public final class DeviceDataProviderInfo implements Parcelable {
    // The package name of the DDP application.
    @NonNull private final String mPackageName;

    // The ID the provider uses to identify this device.
    @NonNull private final String mDeviceId;

    // Label for ACTION_SHOW_DEVICE_ONBOARDING activity.
    @NonNull private final String mOnboardingActivityLabel;

    // Label for ACTION_SHOW_DEVICE_MANAGEMENT activity.
    @NonNull private final String mManagementActivityLabel;

    // The specific data types this provider handles for this device.
    @NonNull private final Set<DeviceDataTypeAdvertisement> mDeviceDataTypeAdvertisements;

    /**
     * Creates an instance of {@link DeviceDataProviderInfo}.
     *
     * @param packageName The package name of the DDP application.
     * @param deviceId The ID the provider uses to identify this device.
     * @param onboardingActivityLabel Label for the onboarding activity.
     * @param managementActivityLabel Label for the management activity.
     * @param deviceDataTypeAdvertisements The specific data types this provider handles for this
     *     device.
     */
    public DeviceDataProviderInfo(
            @NonNull String packageName,
            @NonNull String deviceId,
            @NonNull String onboardingActivityLabel,
            @NonNull String managementActivityLabel,
            @NonNull Set<DeviceDataTypeAdvertisement> deviceDataTypeAdvertisements) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(onboardingActivityLabel);
        Objects.requireNonNull(managementActivityLabel);
        Objects.requireNonNull(deviceDataTypeAdvertisements);
        mPackageName = packageName;
        mDeviceId = deviceId;
        mOnboardingActivityLabel = onboardingActivityLabel;
        mManagementActivityLabel = managementActivityLabel;
        mDeviceDataTypeAdvertisements = new HashSet<>(deviceDataTypeAdvertisements);
    }

    /** Returns the package name of the Device Data Provider application. */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Returns the ID the provider uses to identify this device. For the current device, this would
     * be the value returned by {@link HealthConnectManager#getCurrentDeviceId} when called by this
     * device data provider.
     */
    @NonNull
    public String getDeviceId() {
        return mDeviceId;
    }

    /** Returns the label for the onboarding activity. */
    @NonNull
    public String getOnboardingActivityLabel() {
        return mOnboardingActivityLabel;
    }

    /** Returns the label for the management activity. */
    @NonNull
    public String getManagementActivityLabel() {
        return mManagementActivityLabel;
    }

    /** Returns the specific data types this provider handles for this device. */
    @NonNull
    public Set<DeviceDataTypeAdvertisement> getDeviceDataTypeAdvertisements() {
        return mDeviceDataTypeAdvertisements;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeviceDataProviderInfo)) return false;
        DeviceDataProviderInfo that = (DeviceDataProviderInfo) o;
        return Objects.equals(mPackageName, that.mPackageName)
                && Objects.equals(mDeviceId, that.mDeviceId)
                && Objects.equals(mOnboardingActivityLabel, that.mOnboardingActivityLabel)
                && Objects.equals(mManagementActivityLabel, that.mManagementActivityLabel)
                && Objects.equals(
                        mDeviceDataTypeAdvertisements, that.mDeviceDataTypeAdvertisements);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mPackageName,
                mDeviceId,
                mOnboardingActivityLabel,
                mManagementActivityLabel,
                mDeviceDataTypeAdvertisements);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mPackageName);
        dest.writeString(mDeviceId);
        dest.writeString(mOnboardingActivityLabel);
        dest.writeString(mManagementActivityLabel);
        dest.writeTypedList(mDeviceDataTypeAdvertisements.stream().toList());
    }

    @NonNull
    public static final Creator<DeviceDataProviderInfo> CREATOR =
            new Creator<>() {
                @Override
                public DeviceDataProviderInfo createFromParcel(Parcel in) {
                    return new DeviceDataProviderInfo(in);
                }

                @Override
                public DeviceDataProviderInfo[] newArray(int size) {
                    return new DeviceDataProviderInfo[size];
                }
            };

    /** @hide */
    @NonNull
    public DeviceDataProviderInfo toMasked(@NonNull Function<String, String> packageMasker) {
        return new DeviceDataProviderInfo(
                mPackageName,
                packageMasker.apply(mDeviceId),
                mOnboardingActivityLabel,
                mManagementActivityLabel,
                mDeviceDataTypeAdvertisements);
    }

    private DeviceDataProviderInfo(Parcel in) {
        mPackageName = in.readString();
        mDeviceId = in.readString();
        mOnboardingActivityLabel = in.readString();
        mManagementActivityLabel = in.readString();
        mDeviceDataTypeAdvertisements =
                new HashSet<>(in.createTypedArrayList(DeviceDataTypeAdvertisement.CREATOR));
    }
}
