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

import static android.Manifest.permission.MANAGE_DEVICE_POLICY_ACROSS_USERS;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_ACROSS_USERS_FULL;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_CONTENT_RESTRICTION_APPS;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_FUN;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_LOCKSCREEN_MESSAGE;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_MANAGED_SUBSCRIPTIONS;
import static android.Manifest.permission.MANAGE_DEVICE_POLICY_SCREEN_CAPTURE;
import static android.Manifest.permission.SET_TIME;
import static android.Manifest.permission.SET_TIME_ZONE;
import static android.app.admin.DevicePolicyManager.POLICY_SCOPE_DEVICE;
import static android.app.admin.DevicePolicyManager.POLICY_SCOPE_USER;
import static android.app.admin.DevicePolicyManager.RESOURCE_DEVICE_WIDE;
import static android.app.admin.DevicePolicyManager.RESOURCE_PER_USER;
import static android.app.admin.flags.Flags.FLAG_POLICY_STREAMLINING;
import static android.app.admin.flags.Flags.FLAG_POLICY_STREAMLINING_AUTO_TIME;
import static android.app.admin.flags.Flags.FLAG_POLICY_STREAMLINING_AUTO_TIME_ZONE;
import static android.app.admin.flags.Flags.FLAG_POLICY_STREAMLINING_EASTER_EGGS;
import static android.processor.devicepolicy.AllowedDpcTypes.ALLOWED;
import static android.processor.devicepolicy.AllowedDpcTypes.DISALLOWED;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.admin.flags.Flags;
import android.content.Intent;
import android.os.UserManager;
import android.processor.devicepolicy.AllowedDpcTypes;
import android.processor.devicepolicy.EnumPolicyDefinition;
import android.processor.devicepolicy.EnumResolutionMechanism;
import android.processor.devicepolicy.ListOfStringPolicyDefinition;
import android.processor.devicepolicy.PolicyDefinition;
import android.processor.devicepolicy.StringPolicyDefinition;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Represents a type safe identifier for a policy. Use it as a key for {@link
 * DevicePolicyManager#setPolicy setPolicy} and related APIs.
 *
 * <p>Policies should be structured as:
 *
 * <pre>{@code
 * {@literal @}TypePolicyDefinition
 * private static final PolicyIdentifier<Type> POLICY_NAME =
 *     new PolicyIdentifier<>("POLICY_NAME");
 * }</pre>
 *
 * <p>Currently policy definitions are restricted to fields of {@link PolicyIdentifier}. This
 * restriction might be lifted in the future.
 *
 * @param <T> Represents the type of the value that is associated with this identifier.
 */
@FlaggedApi(FLAG_POLICY_STREAMLINING)
public final class PolicyIdentifier<T> {
    private final String mId;

    /**
     * Create an instance of PolicyIdentifier. Should only be used to create the static definitions
     * below.
     *
     * <b>This API is only public for testing purposes. Real applications should only use the static
     * instances defined below.
     */
    public PolicyIdentifier(@NonNull String id) {
        this.mId = id;
    }

    /**
     * Get the string representation of this identifier.
     *
     * @return The string representation of this identifier
     * @hide
     */
    @NonNull
    public String getId() {
        return mId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PolicyIdentifier)) return false;
        PolicyIdentifier<?> that = (PolicyIdentifier<?>) o;
        return mId.equals(that.mId);
    }

    @Override
    public int hashCode() {
        return mId.hashCode();
    }

    @Override
    @NonNull
    public String toString() {
        return mId;
    }

    // LINT.IfChange

    /**
     * Screen capture is disallowed. See {@link android.view.Display#FLAG_SECURE} for more details
     * on how blocking works.
     */
    @FlaggedApi(FLAG_POLICY_STREAMLINING)
    public static final int SCREEN_CAPTURE_DISALLOWED = 1;

    /** Screen capture is allowed. */
    @FlaggedApi(FLAG_POLICY_STREAMLINING)
    public static final int SCREEN_CAPTURE_ALLOWED = 2;

    /**
     * Possible values {@link SCREEN_CAPTURE}
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"SCREEN_CAPTURE_"},
            value = {
                SCREEN_CAPTURE_DISALLOWED,
                SCREEN_CAPTURE_ALLOWED,
            })
    public @interface ScreenCaptureValue {}

    /**
     * Policy that controls whether the screen capture is allowed or disallowed. Disallowing screen
     * capture also prevents the content from being shown on display devices that do not have a
     * secure video output. See {@link android.view.Display#FLAG_SECURE} for more details about
     * secure surfaces and secure displays. Throws SecurityException if the caller is not permitted
     * to control screen capture policy. If the scope is set to {@link
     * DevicePolicyManager#POLICY_SCOPE_DEVICE} and the caller is not a profile owner of an
     * organization-owned managed profile or a device owner, a security exception will be thrown.
     */
    @FlaggedApi(FLAG_POLICY_STREAMLINING)
    @NonNull
    @EnumPolicyDefinition(
            base =
                    @PolicyDefinition(
                            allowedScopes = {POLICY_SCOPE_USER, POLICY_SCOPE_DEVICE},
                            affectedResource = RESOURCE_PER_USER,
                            requiredPermission = MANAGE_DEVICE_POLICY_SCREEN_CAPTURE,
                            requiredCrossUserPermission = MANAGE_DEVICE_POLICY_ACROSS_USERS,
                            allowedDpcTypes =
                                    @AllowedDpcTypes(
                                            deviceOwner = ALLOWED,
                                            managedProfileOwnerOfOrganizationOwnedDevice = ALLOWED,
                                            managedProfileOwnerOfPersonalOwnedDevice = ALLOWED,
                                            unaffiliatedFullUserProfileOwner = ALLOWED,
                                            profileOwnerOnUser0 = ALLOWED,
                                            affiliatedFullUserProfileOwner = ALLOWED)),
            intDef = ScreenCaptureValue.class,
            defaultValue = SCREEN_CAPTURE_ALLOWED,
            resolutionMechanism = @EnumResolutionMechanism(custom = true))
    public static final PolicyIdentifier<Integer> SCREEN_CAPTURE =
            new PolicyIdentifier<>("SCREEN_CAPTURE");

    /** The user can choose whether the time is automatically obtained from the network or not. */
    @FlaggedApi(FLAG_POLICY_STREAMLINING_AUTO_TIME)
    public static final int AUTO_TIME_USER_CHOICE = 0;

    /**
     * The admin has disabled the time to be automatically obtained from the network. This is not
     * enforced and the user can still enable it.
     */
    @FlaggedApi(FLAG_POLICY_STREAMLINING_AUTO_TIME)
    public static final int AUTO_TIME_DISABLED_UNENFORCED = 1;

    /**
     * The admin has enabled the time to be automatically obtained from the network. This is not
     * enforced and the user can still disable it.
     */
    @FlaggedApi(FLAG_POLICY_STREAMLINING_AUTO_TIME)
    public static final int AUTO_TIME_ENABLED_UNENFORCED = 2;

    /**
     * The admin has disabled the time to be automatically obtained from the network. This is
     * enforced and the user cannot enable it.
     */
    @FlaggedApi(FLAG_POLICY_STREAMLINING_AUTO_TIME)
    public static final int AUTO_TIME_DISABLED = 3;

    /**
     * The admin has enabled the time to be automatically obtained from the network. This is
     * enforced and the user cannot disable it.
     */
    @FlaggedApi(FLAG_POLICY_STREAMLINING_AUTO_TIME)
    public static final int AUTO_TIME_ENABLED = 4;

    /**
     * Possible values {@link AUTO_TIME}
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"AUTO_TIME_"},
            value = {
                AUTO_TIME_USER_CHOICE,
                AUTO_TIME_DISABLED_UNENFORCED,
                AUTO_TIME_ENABLED_UNENFORCED,
                AUTO_TIME_DISABLED,
                AUTO_TIME_ENABLED,
            })
    public @interface AutoTimeValue {}

    /** Policy that controls whether the time is automatically obtained from the network or not. */
    @FlaggedApi(FLAG_POLICY_STREAMLINING_AUTO_TIME)
    @NonNull
    @EnumPolicyDefinition(
            base =
                    @PolicyDefinition(
                            allowedScopes = {POLICY_SCOPE_DEVICE},
                            affectedResource = RESOURCE_DEVICE_WIDE,
                            requiredPermission = SET_TIME,
                            requiredCrossUserPermission = MANAGE_DEVICE_POLICY_ACROSS_USERS,
                            allowedDpcTypes =
                                    @AllowedDpcTypes(
                                            deviceOwner = ALLOWED,
                                            managedProfileOwnerOfOrganizationOwnedDevice = ALLOWED,
                                            managedProfileOwnerOfPersonalOwnedDevice = DISALLOWED,
                                            unaffiliatedFullUserProfileOwner = DISALLOWED,
                                            profileOwnerOnUser0 = ALLOWED)),
            intDef = AutoTimeValue.class,
            defaultValue = AUTO_TIME_USER_CHOICE,
            resolutionMechanism = @EnumResolutionMechanism(custom = true))
    public static final PolicyIdentifier<Integer> AUTO_TIME = new PolicyIdentifier<>("AUTO_TIME");

    /**
     * Specifies that the user is allowed to transfer managed eSIMs from the device.
     */
    @FlaggedApi(Flags.FLAG_MANAGED_ESIM_OUTGOING_TRANSFER_POLICY)
    public static final int MANAGED_ESIM_OUTGOING_TRANSFER_ALLOWED = 1;

    /**
     * Specifies that the user is not allowed to transfer managed eSIMs from the device.
     */
    @FlaggedApi(Flags.FLAG_MANAGED_ESIM_OUTGOING_TRANSFER_POLICY)
    public static final int MANAGED_ESIM_OUTGOING_TRANSFER_DISALLOWED = 2;

    /** @hide */
    @IntDef(prefix = { "MANAGED_ESIM_OUTGOING_TRANSFER_" }, value = {
            MANAGED_ESIM_OUTGOING_TRANSFER_ALLOWED,
            MANAGED_ESIM_OUTGOING_TRANSFER_DISALLOWED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ManagedEsimOutgoingTransferPolicy {}

    /**
     * Policy that controls whether outgoing transfer is allowed for managed embedded subscriptions.
     */
    @FlaggedApi(Flags.FLAG_MANAGED_ESIM_OUTGOING_TRANSFER_POLICY)
    @NonNull
    @EnumPolicyDefinition(
            base =
            @PolicyDefinition(
                    allowedScopes = {POLICY_SCOPE_DEVICE},
                    affectedResource = RESOURCE_DEVICE_WIDE,
                    requiredPermission = MANAGE_DEVICE_POLICY_MANAGED_SUBSCRIPTIONS,
                    allowedDpcTypes =
                    @AllowedDpcTypes(
                            deviceOwner = ALLOWED,
                            managedProfileOwnerOfOrganizationOwnedDevice = ALLOWED,
                            managedProfileOwnerOfPersonalOwnedDevice = ALLOWED,
                            unaffiliatedFullUserProfileOwner = ALLOWED,
                            profileOwnerOnUser0 = ALLOWED)),
            intDef = ManagedEsimOutgoingTransferPolicy.class,
            defaultValue = MANAGED_ESIM_OUTGOING_TRANSFER_ALLOWED,
            resolutionMechanism = @EnumResolutionMechanism(mostRestrictive = {
                    MANAGED_ESIM_OUTGOING_TRANSFER_DISALLOWED,
                    MANAGED_ESIM_OUTGOING_TRANSFER_ALLOWED
            })
    )
    public static final PolicyIdentifier<Integer> MANAGED_ESIM_OUTGOING_TRANSFER_POLICY =
            new PolicyIdentifier<>("MANAGED_ESIM_OUTGOING_TRANSFER_POLICY");

    /**
     * Policy that sets a custom message to be shown on the lock screen. This message is displayed
     * on the device screen when locked, and is useful for a lost or stolen device.
     *
     * <p>The message set using this method overrides any owner information manually set by the user
     * and prevents the user from further changing it.
     *
     * <p>If the message is {@code null} then the device owner info is cleared and the user owner
     * info is shown on the lock screen if it is set.
     *
     * <p>If the message contains only whitespaces then the message on the lock screen will be blank
     * and the user will not be allowed to change it.
     *
     * <p>If the message needs to be localized, it is the responsibility of the {@link
     * DeviceAdminReceiver} to listen to the {@link Intent#ACTION_LOCALE_CHANGED} broadcast and set
     * a new version of this string accordingly.
     */
    @FlaggedApi(FLAG_POLICY_STREAMLINING)
    @NonNull
    @StringPolicyDefinition(
            base =
                    @PolicyDefinition(
                            allowedScopes = {POLICY_SCOPE_DEVICE},
                            affectedResource = RESOURCE_DEVICE_WIDE,
                            requiredPermission = MANAGE_DEVICE_POLICY_LOCKSCREEN_MESSAGE,
                            requiredCrossUserPermission = MANAGE_DEVICE_POLICY_ACROSS_USERS,
                            allowedDpcTypes =
                                    @AllowedDpcTypes(
                                            deviceOwner = ALLOWED,
                                            managedProfileOwnerOfOrganizationOwnedDevice = ALLOWED,
                                            managedProfileOwnerOfPersonalOwnedDevice = DISALLOWED,
                                            unaffiliatedFullUserProfileOwner = DISALLOWED)),
            emptyStringAllowed = false)
    public static final PolicyIdentifier<String> LOCKSCREEN_MESSAGE =
            new PolicyIdentifier<>("LOCKSCREEN_MESSAGE");

    /**
     * Policy that sets the list of packages as the holders of the {@link
     * android.app.role.RoleManager#ROLE_CONTENT_RESTRICTION} role.
     *
     * <p>If the value is {@code null}, any previously set role holder set through this policy will
     * be removed.
     */
    @FlaggedApi(FLAG_POLICY_STREAMLINING)
    @NonNull
    @ListOfStringPolicyDefinition(
            base =
                    @StringPolicyDefinition(
                            base =
                                    @PolicyDefinition(
                                            allowedScopes = {POLICY_SCOPE_USER},
                                            affectedResource = RESOURCE_PER_USER,
                                            requiredPermission =
                                                    MANAGE_DEVICE_POLICY_CONTENT_RESTRICTION_APPS,
                                            requiredCrossUserPermission =
                                                    MANAGE_DEVICE_POLICY_ACROSS_USERS_FULL,
                                            allowedDpcTypes =
                                                    @AllowedDpcTypes(
                                                            deviceOwner = ALLOWED,
                                                            managedProfileOwnerOfOrganizationOwnedDevice =
                                                                    ALLOWED,
                                                            managedProfileOwnerOfPersonalOwnedDevice =
                                                                    DISALLOWED,
                                                            unaffiliatedFullUserProfileOwner =
                                                                    DISALLOWED))))
    public static final PolicyIdentifier<List<String>> CONTENT_RESTRICTION_APPS =
            new PolicyIdentifier<>("CONTENT_RESTRICTION_APPS");

    /** The user can choose whether the device's time zone is set automatically or not. */
    @FlaggedApi(FLAG_POLICY_STREAMLINING_AUTO_TIME_ZONE)
    public static final int AUTO_TIME_ZONE_USER_CHOICE = 0;

    /**
     * The admin has disabled automatic time zone detection. This is not enforced and the user can
     * still enable it. Use {@link UserManager#DISALLOW_CONFIG_DATE_TIME} to enforce the policy.
     */
    @FlaggedApi(FLAG_POLICY_STREAMLINING_AUTO_TIME_ZONE)
    public static final int AUTO_TIME_ZONE_DISABLED_UNENFORCED = 1;

    /**
     * The admin has enabled the time zone to be automatically obtained from the network. This is
     * not enforced and the user can still disable it. Use {@link
     * UserManager#DISALLOW_CONFIG_DATE_TIME} to enforce the policy.
     */
    @FlaggedApi(FLAG_POLICY_STREAMLINING_AUTO_TIME_ZONE)
    public static final int AUTO_TIME_ZONE_ENABLED_UNENFORCED = 2;

    /**
     * The admin has disabled automatic time zone detection. This is enforced and the user cannot
     * enable it.
     */
    @FlaggedApi(FLAG_POLICY_STREAMLINING_AUTO_TIME_ZONE)
    public static final int AUTO_TIME_ZONE_DISABLED = 3;

    /**
     * The admin has enabled the time zone to be automatically obtained from the network. This is
     * enforced and the user cannot disable it.
     */
    @FlaggedApi(FLAG_POLICY_STREAMLINING_AUTO_TIME_ZONE)
    public static final int AUTO_TIME_ZONE_ENABLED = 4;

    /**
     * Possible values {@link #AUTO_TIME_ZONE}
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"AUTO_TIME_ZONE_"},
            value = {
                AUTO_TIME_ZONE_USER_CHOICE,
                AUTO_TIME_ZONE_DISABLED_UNENFORCED,
                AUTO_TIME_ZONE_ENABLED_UNENFORCED,
                AUTO_TIME_ZONE_DISABLED,
                AUTO_TIME_ZONE_ENABLED,
            })
    public @interface AutoTimeZoneValue {}

    /**
     * Policy that controls whether the device's time zone is set automatically, e.g. obtained from
     * network or location.
     */
    @FlaggedApi(FLAG_POLICY_STREAMLINING_AUTO_TIME_ZONE)
    @NonNull
    @EnumPolicyDefinition(
            base =
                    @PolicyDefinition(
                            allowedScopes = {POLICY_SCOPE_DEVICE},
                            affectedResource = RESOURCE_DEVICE_WIDE,
                            requiredPermission = SET_TIME_ZONE,
                            requiredCrossUserPermission = MANAGE_DEVICE_POLICY_ACROSS_USERS,
                            allowedDpcTypes =
                                    @AllowedDpcTypes(
                                            deviceOwner = ALLOWED,
                                            managedProfileOwnerOfOrganizationOwnedDevice = ALLOWED,
                                            managedProfileOwnerOfPersonalOwnedDevice = DISALLOWED,
                                            unaffiliatedFullUserProfileOwner = DISALLOWED,
                                            profileOwnerOnUser0 = ALLOWED)),
            intDef = AutoTimeZoneValue.class,
            defaultValue = AUTO_TIME_ZONE_USER_CHOICE,
            resolutionMechanism = @EnumResolutionMechanism(custom = true))
    public static final PolicyIdentifier<Integer> AUTO_TIME_ZONE =
            new PolicyIdentifier<>("AUTO_TIME_ZONE");

    /** Easter eggs are disallowed. */
    @FlaggedApi(FLAG_POLICY_STREAMLINING_EASTER_EGGS)
    public static final int EASTER_EGGS_DISALLOWED = 1;

    /** Easter eggs are allowed. */
    @FlaggedApi(FLAG_POLICY_STREAMLINING_EASTER_EGGS)
    public static final int EASTER_EGGS_ALLOWED = 2;

    /**
     * Possible values {@link EASTER_EGGS}
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"EASTER_EGGS_"},
            value = {
                EASTER_EGGS_DISALLOWED,
                EASTER_EGGS_ALLOWED,
            })
    public @interface EasterEggsValue {}

    /**
     * Policy that controls whether the user is allowed to access various Easter egg games across
     * the system (for instance, in settings).
     */
    @FlaggedApi(FLAG_POLICY_STREAMLINING_EASTER_EGGS)
    @NonNull
    @EnumPolicyDefinition(
            base =
                    @PolicyDefinition(
                            allowedScopes = {POLICY_SCOPE_USER, POLICY_SCOPE_DEVICE},
                            affectedResource = RESOURCE_PER_USER,
                            requiredPermission = MANAGE_DEVICE_POLICY_FUN,
                            requiredCrossUserPermission = MANAGE_DEVICE_POLICY_ACROSS_USERS,
                            allowedDpcTypes =
                                    @AllowedDpcTypes(
                                            deviceOwner = ALLOWED,
                                            managedProfileOwnerOfOrganizationOwnedDevice =
                                                    DISALLOWED,
                                            managedProfileOwnerOfPersonalOwnedDevice = DISALLOWED,
                                            profileOwnerOnUser0 = ALLOWED,
                                            unaffiliatedFullUserProfileOwner = ALLOWED)),
            intDef = EasterEggsValue.class,
            defaultValue = EASTER_EGGS_ALLOWED,
            resolutionMechanism = @EnumResolutionMechanism(custom = true))
    public static final PolicyIdentifier<Integer> EASTER_EGGS =
            new PolicyIdentifier<>("EASTER_EGGS");

    // LINT.ThenChange(/tools/policymetadata/policies.textproto)
}
