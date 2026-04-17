/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.security.advancedprotection;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An advanced protection feature providing protections.
 *
 * <p>This class represents the state of a specific feature, including whether it is enabled and its
 * provisioning status.
 *
 * <p>Advanced Protection features are controlled by three concepts:
 *
 * <ul>
 *   <li><b>Availability</b>: Whether the feature is supported by the device's hardware and software
 *       configuration.
 *   <li><b>Provisioning</b>: Whether the feature is authorized for use. Features can be provisioned
 *       by default, by a Feature Admin, or for testing purposes.
 *   <li><b>Enabled</b>: Whether the feature is currently active and enforcing protections.
 * </ul>
 *
 * <p>A feature is considered <b>enabled</b> only if it is <b>available</b>, <b>provisioned</b>, and
 * Advanced Protection is globally enabled on the device.
 *
 * @hide
 */
@SystemApi
public final class AdvancedProtectionFeature implements Parcelable {
    /**
     * Provisioning mode for an advanced protection feature that has not had an explicit
     * provisioning mode set. This is the default value for features launched before the
     * introduction of provisioning modes.
     */
    @FlaggedApi(android.security.Flags.FLAG_AAPM_API_V2)
    public static final int PROVISIONING_MODE_PROVISIONED_BY_DEFAULT = 0;

    /**
     * Provisioning mode for an advanced protection feature that is provisioned by a feature admin.
     */
    @FlaggedApi(android.security.Flags.FLAG_AAPM_API_V2)
    public static final int PROVISIONING_MODE_PROVISIONED_BY_FEATURE_ADMIN = 1;

    /**
     * Provisioning mode for an advanced protection feature that is provisioned by ADB.
     *
     * <p>This overrides any provisioning mode set by a feature admin, and is intended for use only
     * for testing and debugging.
     */
    @FlaggedApi(android.security.Flags.FLAG_AAPM_API_V2)
    public static final int PROVISIONING_MODE_PROVISIONED_BY_ADB = 2;

    /**
     * Provisioning mode for an advanced protection feature that has not had an explicit
     * provisioning mode set. This is the default value for features launched after the introduction
     * of provisioning modes.
     */
    @FlaggedApi(android.security.Flags.FLAG_AAPM_API_V2)
    public static final int PROVISIONING_MODE_DEPROVISIONED_BY_DEFAULT = 100;

    /**
     * Provisioning mode for an advanced protection feature that is deprovisioned by a feature
     * admin.
     */
    @FlaggedApi(android.security.Flags.FLAG_AAPM_API_V2)
    public static final int PROVISIONING_MODE_DEPROVISIONED_BY_FEATURE_ADMIN = 101;

    /**
     * Provisioning mode for an advanced protection feature that is deprovisioned by ADB.
     *
     * <p>This overrides any provisioning mode set by a feature admin, and is intended for use only
     * for testing and debugging.
     */
    @FlaggedApi(android.security.Flags.FLAG_AAPM_API_V2)
    public static final int PROVISIONING_MODE_DEPROVISIONED_BY_ADB = 102;

    /**
     * Provisioning mode of an advanced protection feature.
     *
     * @hide
     */
    @IntDef(
            prefix = {"PROVISIONING_MODE_"},
            value = {
                PROVISIONING_MODE_PROVISIONED_BY_DEFAULT,
                PROVISIONING_MODE_PROVISIONED_BY_FEATURE_ADMIN,
                PROVISIONING_MODE_PROVISIONED_BY_ADB,
                PROVISIONING_MODE_DEPROVISIONED_BY_DEFAULT,
                PROVISIONING_MODE_DEPROVISIONED_BY_FEATURE_ADMIN,
                PROVISIONING_MODE_DEPROVISIONED_BY_ADB
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProvisioningMode {}

    private final int mId;
    private final boolean mEnabled;
    private final @ProvisioningMode int mProvisioningMode;

    /**
     * Create an object identifying an Advanced Protection feature for {@link
     * AdvancedProtectionManager}.
     *
     * @param id Feature identifier. It is used by Settings screens to display information about
     *     this feature.
     * @deprecated Use {@link #AdvancedProtectionFeature(int, boolean, int)} instead.
     */
    @FlaggedApi(android.security.Flags.FLAG_AAPM_API_V2)
    @Deprecated
    public AdvancedProtectionFeature(@AdvancedProtectionManager.FeatureId int id) {
        this(id, true, PROVISIONING_MODE_PROVISIONED_BY_DEFAULT);
    }

    /**
     * Create an object identifying an Advanced Protection feature for {@link
     * AdvancedProtectionManager}.
     *
     * @param id Feature identifier. It is used by Settings screens to display information about
     *     this feature.
     * @param enabled Whether the feature is enabled.
     * @param provisioningMode Provisioning mode of the feature.
     */
    @FlaggedApi(android.security.Flags.FLAG_AAPM_API_V2)
    public AdvancedProtectionFeature(
            @AdvancedProtectionManager.FeatureId int id,
            boolean enabled,
            @ProvisioningMode int provisioningMode) {
        mId = id;
        mEnabled = enabled;
        mProvisioningMode = provisioningMode;
    }

    private AdvancedProtectionFeature(Parcel in) {
        mId = in.readInt();
        mEnabled = in.readBoolean();
        mProvisioningMode = in.readInt();
    }

    /** Returns the unique ID representing this feature. */
    public int getId() {
        return mId;
    }

    /**
     * Returns whether this feature is currently enabled.
     *
     * <p>An enabled feature is actively enforcing protections. This requires the feature to be
     * available, provisioned, and for Advanced Protection to be globally enabled.
     */
    @FlaggedApi(android.security.Flags.FLAG_AAPM_API_V2)
    public boolean isEnabled() {
        return mEnabled;
    }

    /**
     * Returns whether this feature is provisioned.
     *
     * <p>Provisioning indicates that the feature is authorized for use on this device.
     *
     * <p>Note: A provisioned feature is not necessarily enabled. It will only be enabled if
     * Advanced Protection is active and the feature is available on the device.
     */
    @FlaggedApi(android.security.Flags.FLAG_AAPM_API_V2)
    public boolean isProvisioned() {
        return mProvisioningMode < PROVISIONING_MODE_DEPROVISIONED_BY_DEFAULT;
    }

    /**
     * Returns the provisioning mode of this feature.
     *
     * <p>This indicates whether the feature is provisioned or not, and which entity is responsible
     * for provisioning.
     */
    @FlaggedApi(android.security.Flags.FLAG_AAPM_API_V2)
    @ProvisioningMode
    public int getProvisioningMode() {
        return mProvisioningMode;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeBoolean(mEnabled);
        dest.writeInt(mProvisioningMode);
    }

    @NonNull
    public static final Parcelable.Creator<AdvancedProtectionFeature> CREATOR =
            new Parcelable.Creator<>() {
                public AdvancedProtectionFeature createFromParcel(Parcel in) {
                    return new AdvancedProtectionFeature(in);
                }

                public AdvancedProtectionFeature[] newArray(int size) {
                    return new AdvancedProtectionFeature[size];
                }
            };
}
