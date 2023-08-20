/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.devicepolicy;

import static android.app.admin.PolicyUpdateReceiver.EXTRA_POLICY_TARGET_USER_ID;
import static android.app.admin.PolicyUpdateReceiver.EXTRA_POLICY_UPDATE_RESULT_KEY;
import static android.app.admin.PolicyUpdateResult.RESULT_FAILURE_CONFLICTING_ADMIN_POLICY;
import static android.app.admin.PolicyUpdateResult.RESULT_FAILURE_HARDWARE_LIMITATION;
import static android.app.admin.PolicyUpdateResult.RESULT_POLICY_CLEARED;
import static android.app.admin.PolicyUpdateResult.RESULT_POLICY_SET;
import static android.content.pm.UserProperties.INHERIT_DEVICE_POLICY_FROM_PARENT;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppGlobals;
import android.app.BroadcastOptions;
import android.app.admin.DevicePolicyIdentifiers;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyState;
import android.app.admin.IntentFilterPolicyKey;
import android.app.admin.PolicyKey;
import android.app.admin.PolicyUpdateReceiver;
import android.app.admin.PolicyValue;
import android.app.admin.TargetUser;
import android.app.admin.UserRestrictionPolicyKey;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.pm.UserProperties;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.telephony.TelephonyManager;
import android.util.AtomicFile;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.utils.Slogf;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Class responsible for setting, resolving, and enforcing policies set by multiple management
 * admins on the device.
 */
final class DevicePolicyEngine {
    static final String TAG = "DevicePolicyEngine";

    // TODO(b/281701062): reference role name from role manager once its exposed.
    static final String DEVICE_LOCK_CONTROLLER_ROLE =
            "android.app.role.SYSTEM_FINANCED_DEVICE_CONTROLLER";

    private static final String CELLULAR_2G_USER_RESTRICTION_ID =
            DevicePolicyIdentifiers.getIdentifierForUserRestriction(
                    UserManager.DISALLOW_CELLULAR_2G);

    private final Context mContext;
    private final UserManager mUserManager;

    // TODO(b/256849338): add more granular locks
    private final Object mLock = new Object();

    /**
     * Map of <userId, Map<policyKey, policyState>>
     */
    private final SparseArray<Map<PolicyKey, PolicyState<?>>> mLocalPolicies;

    /**
     * Map of <policyKey, policyState>
     */
    private final Map<PolicyKey, PolicyState<?>> mGlobalPolicies;

    /**
     * Map containing the current set of admins in each user with active policies.
     */
    private final SparseArray<Set<EnforcingAdmin>> mEnforcingAdmins;

    private final DeviceAdminServiceController mDeviceAdminServiceController;

    DevicePolicyEngine(
            @NonNull Context context,
            @NonNull DeviceAdminServiceController deviceAdminServiceController) {
        mContext = Objects.requireNonNull(context);
        mDeviceAdminServiceController = Objects.requireNonNull(deviceAdminServiceController);
        mUserManager = mContext.getSystemService(UserManager.class);
        mLocalPolicies = new SparseArray<>();
        mGlobalPolicies = new HashMap<>();
        mEnforcingAdmins = new SparseArray<>();
    }

    /**
     * Set the policy for the provided {@code policyDefinition} (see {@link PolicyDefinition}) and
     * {@code enforcingAdmin} to the provided {@code value}.
     *
     * <p>If {@code skipEnforcePolicy} is true, it sets the policies in the internal data structure
     * but doesn't call the enforcing logic.
     *
     */
    <V> void setLocalPolicy(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            @Nullable PolicyValue<V> value,
            int userId,
            boolean skipEnforcePolicy) {
        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);

        synchronized (mLock) {
            PolicyState<V> localPolicyState = getLocalPolicyStateLocked(policyDefinition, userId);

            if (policyDefinition.isNonCoexistablePolicy()) {
                setNonCoexistableLocalPolicy(policyDefinition, localPolicyState, enforcingAdmin,
                        value, userId, skipEnforcePolicy);
                return;
            }

            boolean hasGlobalPolicies = hasGlobalPolicyLocked(policyDefinition);
            boolean policyChanged;
            if (hasGlobalPolicies) {
                PolicyState<V> globalPolicyState = getGlobalPolicyStateLocked(policyDefinition);
                policyChanged = localPolicyState.addPolicy(
                        enforcingAdmin,
                        value,
                        globalPolicyState.getPoliciesSetByAdmins());
            } else {
                policyChanged = localPolicyState.addPolicy(enforcingAdmin, value);
            }

            // No need to notify admins as no new policy is actually enforced, we're just filling in
            // the data structures.
            if (!skipEnforcePolicy) {
                if (policyChanged) {
                    onLocalPolicyChanged(policyDefinition, enforcingAdmin, userId);
                }
                boolean policyEnforced = Objects.equals(
                        localPolicyState.getCurrentResolvedPolicy(), value);
                sendPolicyResultToAdmin(
                        enforcingAdmin,
                        policyDefinition,
                        // TODO: we're always sending this for now, should properly handle errors.
                        policyEnforced
                                ? RESULT_POLICY_SET : RESULT_FAILURE_CONFLICTING_ADMIN_POLICY,
                        userId);
            }

            updateDeviceAdminServiceOnPolicyAddLocked(enforcingAdmin);

            write();

            applyToInheritableProfiles(policyDefinition, enforcingAdmin, value, userId);
        }
    }

    /**
     * Sets a non-coexistable policy, meaning it doesn't get resolved against other policies set
     * by other admins, and no callbacks are sent to admins, this is just storing and
     * enforcing the policy.
     *
     * <p>Passing a {@code null} value means the policy set by this admin should be removed.
     */
    private <V> void setNonCoexistableLocalPolicy(
            PolicyDefinition<V> policyDefinition,
            PolicyState<V> localPolicyState,
            EnforcingAdmin enforcingAdmin,
            @Nullable PolicyValue<V> value,
            int userId,
            boolean skipEnforcePolicy) {
        if (value == null) {
            localPolicyState.removePolicy(enforcingAdmin);
        } else {
            localPolicyState.addPolicy(enforcingAdmin, value);
        }
        if (!skipEnforcePolicy) {
            enforcePolicy(policyDefinition, value, userId);
        }
        if (localPolicyState.getPoliciesSetByAdmins().isEmpty()) {
            removeLocalPolicyStateLocked(policyDefinition, userId);
        }
        updateDeviceAdminServiceOnPolicyAddLocked(enforcingAdmin);
        write();
    }

    // TODO: add more documentation on broadcasts/callbacks to use to get current enforced values
    /**
     * Set the policy for the provided {@code policyDefinition}
     * (see {@link PolicyDefinition}) and {@code enforcingAdmin} to the provided {@code value}.
     */
    <V> void setLocalPolicy(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            @NonNull PolicyValue<V> value,
            int userId) {
        setLocalPolicy(
                policyDefinition, enforcingAdmin, value, userId, /* skipEnforcePolicy= */ false);
    }

    // TODO: add more documentation on broadcasts/callbacks to use to get current enforced values
    /**
     * Removes any previously set policy for the provided {@code policyDefinition}
     * (see {@link PolicyDefinition}) and {@code enforcingAdmin}.
     */
    <V> void removeLocalPolicy(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            int userId) {
        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);

        synchronized (mLock) {
            if (!hasLocalPolicyLocked(policyDefinition, userId)) {
                return;
            }
            PolicyState<V> localPolicyState = getLocalPolicyStateLocked(policyDefinition, userId);

            if (policyDefinition.isNonCoexistablePolicy()) {
                setNonCoexistableLocalPolicy(policyDefinition, localPolicyState, enforcingAdmin,
                        /* value= */ null, userId, /* skipEnforcePolicy= */ false);
                return;
            }

            boolean policyChanged;
            if (hasGlobalPolicyLocked(policyDefinition)) {
                PolicyState<V> globalPolicyState = getGlobalPolicyStateLocked(policyDefinition);
                policyChanged = localPolicyState.removePolicy(
                        enforcingAdmin,
                        globalPolicyState.getPoliciesSetByAdmins());
            } else {
                policyChanged = localPolicyState.removePolicy(enforcingAdmin);
            }

            if (policyChanged) {
                onLocalPolicyChanged(policyDefinition, enforcingAdmin, userId);
            }

            // For a removePolicy to be enforced, it means no current policy exists
            sendPolicyResultToAdmin(
                    enforcingAdmin,
                    policyDefinition,
                    // TODO: we're always sending this for now, should properly handle errors.
                    RESULT_POLICY_CLEARED,
                    userId);

            if (localPolicyState.getPoliciesSetByAdmins().isEmpty()) {
                removeLocalPolicyStateLocked(policyDefinition, userId);
            }

            updateDeviceAdminServiceOnPolicyRemoveLocked(enforcingAdmin);

            write();

            applyToInheritableProfiles(policyDefinition, enforcingAdmin, /*value */ null, userId);
        }
    }

    /**
     * If any of child user has property {@link UserProperties#INHERIT_DEVICE_POLICY_FROM_PARENT}
     * set then propagate the policy to it if value is not null
     * else remove the policy from child.
     */
    private <V> void applyToInheritableProfiles(PolicyDefinition<V> policyDefinition,
            EnforcingAdmin enforcingAdmin, PolicyValue<V> value, int userId) {
        if (policyDefinition.isInheritable()) {
            Binder.withCleanCallingIdentity(() -> {
                List<UserInfo> userInfos = mUserManager.getProfiles(userId);
                for (UserInfo childUserInfo : userInfos) {
                    int childUserId = childUserInfo.getUserHandle().getIdentifier();
                    if (isProfileOfUser(childUserId, userId)
                            && isInheritDevicePolicyFromParent(childUserInfo)) {
                        if (value != null) {
                            setLocalPolicy(policyDefinition, enforcingAdmin, value, childUserId);
                        } else {
                            removeLocalPolicy(policyDefinition, enforcingAdmin, childUserId);
                        }
                    }
                }
            });
        }
    }

    /**
     * Checks if given parentUserId is direct parent of childUserId.
     */
    private boolean isProfileOfUser(int childUserId, int parentUserId) {
        UserInfo parentInfo = mUserManager.getProfileParent(childUserId);
        return childUserId != parentUserId && parentInfo != null
                && parentInfo.getUserHandle().getIdentifier() == parentUserId;
    }

    private boolean isInheritDevicePolicyFromParent(UserInfo userInfo) {
        UserProperties userProperties = mUserManager.getUserProperties(userInfo.getUserHandle());
        return userProperties != null && mUserManager.getUserProperties(userInfo.getUserHandle())
                .getInheritDevicePolicy() == INHERIT_DEVICE_POLICY_FROM_PARENT;
    }

    /**
     * Enforces the new policy and notifies relevant admins.
     */
    private <V> void onLocalPolicyChanged(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            int userId) {

        PolicyState<V> localPolicyState = getLocalPolicyStateLocked(policyDefinition, userId);
        enforcePolicy(
                policyDefinition, localPolicyState.getCurrentResolvedPolicy(), userId);

        // Send policy updates to admins who've set it locally
        sendPolicyChangedToAdmins(
                localPolicyState,
                enforcingAdmin,
                policyDefinition,
                // This policy change is only relevant to a single user, not the global
                // policy value,
                userId);

        // Send policy updates to admins who've set it globally
        if (hasGlobalPolicyLocked(policyDefinition)) {
            PolicyState<V> globalPolicyState = getGlobalPolicyStateLocked(policyDefinition);
            sendPolicyChangedToAdmins(
                    globalPolicyState,
                    enforcingAdmin,
                    policyDefinition,
                    userId);
        }
        sendDevicePolicyChangedToSystem(userId);
    }

    /**
     * Set the policy for the provided {@code policyDefinition}
     * (see {@link PolicyDefinition}) and {@code enforcingAdmin} to the provided {@code value}.
     */
    <V> void setGlobalPolicy(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            @NonNull PolicyValue<V> value) {
        setGlobalPolicy(policyDefinition, enforcingAdmin, value, /* skipEnforcePolicy= */ false);
    }

    // TODO: add more documentation on broadcasts/callbacks to use to get current enforced values
    /**
     * Set the policy for the provided {@code policyDefinition}
     * (see {@link PolicyDefinition}) and {@code enforcingAdmin} to the provided {@code value}.
     */
    <V> void setGlobalPolicy(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            @NonNull PolicyValue<V> value,
            boolean skipEnforcePolicy) {

        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);
        Objects.requireNonNull(value);

        synchronized (mLock) {
            // TODO(b/270999567): Move error handling for DISALLOW_CELLULAR_2G into the code
            //  that honors the restriction once there's an API available
            if (checkFor2gFailure(policyDefinition, enforcingAdmin)) {
                Log.i(TAG,
                        "Device does not support capabilities required to disable 2g. Not setting"
                                + " global policy state.");
                return;
            }

            PolicyState<V> globalPolicyState = getGlobalPolicyStateLocked(policyDefinition);

            boolean policyChanged = globalPolicyState.addPolicy(enforcingAdmin, value);
            boolean policyAppliedOnAllUsers = applyGlobalPolicyOnUsersWithLocalPoliciesLocked(
                    policyDefinition, enforcingAdmin, value, skipEnforcePolicy);

            // No need to notify admins as no new policy is actually enforced, we're just filling in
            // the data structures.
            if (!skipEnforcePolicy) {
                if (policyChanged) {
                    onGlobalPolicyChanged(policyDefinition, enforcingAdmin);
                }

                boolean policyAppliedGlobally = Objects.equals(
                        globalPolicyState.getCurrentResolvedPolicy(), value);
                boolean policyApplied = policyAppliedGlobally && policyAppliedOnAllUsers;

                sendPolicyResultToAdmin(
                        enforcingAdmin,
                        policyDefinition,
                        // TODO: we're always sending this for now, should properly handle errors.
                        policyApplied ? RESULT_POLICY_SET : RESULT_FAILURE_CONFLICTING_ADMIN_POLICY,
                        UserHandle.USER_ALL);
            }

            updateDeviceAdminServiceOnPolicyAddLocked(enforcingAdmin);

            write();
        }
    }

    // TODO: add more documentation on broadcasts/callbacks to use to get current enforced values
    /**
     * Removes any previously set policy for the provided {@code policyDefinition}
     * (see {@link PolicyDefinition}) and {@code enforcingAdmin}.
     */
    <V> void removeGlobalPolicy(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin) {

        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);

        synchronized (mLock) {
            PolicyState<V> policyState = getGlobalPolicyStateLocked(policyDefinition);
            boolean policyChanged = policyState.removePolicy(enforcingAdmin);

            if (policyChanged) {
                onGlobalPolicyChanged(policyDefinition, enforcingAdmin);
            }

            applyGlobalPolicyOnUsersWithLocalPoliciesLocked(
                    policyDefinition, enforcingAdmin, /* value= */ null, /* enforcePolicy= */ true);

            sendPolicyResultToAdmin(
                    enforcingAdmin,
                    policyDefinition,
                    // TODO: we're always sending this for now, should properly handle errors.
                    RESULT_POLICY_CLEARED,
                    UserHandle.USER_ALL);

            if (policyState.getPoliciesSetByAdmins().isEmpty()) {
                removeGlobalPolicyStateLocked(policyDefinition);
            }

            updateDeviceAdminServiceOnPolicyRemoveLocked(enforcingAdmin);

            write();
        }
    }

    /**
     * Enforces the new policy globally and notifies relevant admins.
     */
    private <V> void onGlobalPolicyChanged(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin) {
        PolicyState<V> policyState = getGlobalPolicyStateLocked(policyDefinition);

        enforcePolicy(policyDefinition, policyState.getCurrentResolvedPolicy(),
                UserHandle.USER_ALL);

        sendPolicyChangedToAdmins(
                policyState,
                enforcingAdmin,
                policyDefinition,
                UserHandle.USER_ALL);

        sendDevicePolicyChangedToSystem(UserHandle.USER_ALL);
    }

    /**
     * Tries to enforce the global policy locally on all users that have the same policy set
     * locally, this is only applicable to policies that can be set locally or globally
     * (e.g. setCameraDisabled, setScreenCaptureDisabled) rather than
     * policies that are global by nature (e.g. setting Wifi enabled/disabled).
     *
     * <p> A {@code null} policy value means the policy was removed
     *
     * <p>Returns {@code true} if the policy is enforced successfully on all users.
     */
    private <V> boolean applyGlobalPolicyOnUsersWithLocalPoliciesLocked(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            @Nullable PolicyValue<V> value,
            boolean skipEnforcePolicy) {
        // Global only policies can't be applied locally, return early.
        if (policyDefinition.isGlobalOnlyPolicy()) {
            return true;
        }
        boolean isAdminPolicyApplied = true;
        for (int i = 0; i < mLocalPolicies.size(); i++) {
            int userId = mLocalPolicies.keyAt(i);
            if (!hasLocalPolicyLocked(policyDefinition, userId)) {
                continue;
            }

            PolicyState<V> localPolicyState = getLocalPolicyStateLocked(policyDefinition, userId);
            PolicyState<V> globalPolicyState = getGlobalPolicyStateLocked(policyDefinition);

            boolean policyChanged = localPolicyState.resolvePolicy(
                    globalPolicyState.getPoliciesSetByAdmins());
            if (policyChanged && !skipEnforcePolicy) {
                enforcePolicy(
                        policyDefinition,
                        localPolicyState.getCurrentResolvedPolicy(),
                        userId);
                sendPolicyChangedToAdmins(
                        localPolicyState,
                        enforcingAdmin,
                        policyDefinition,
                        // Even though this is caused by a global policy change, admins who've set
                        // it locally should only care about the local user state.
                        userId);

            }
            isAdminPolicyApplied &= Objects.equals(
                    value, localPolicyState.getCurrentResolvedPolicy());
        }
        return isAdminPolicyApplied;
    }

    /**
     * Retrieves the resolved policy for the provided {@code policyDefinition} and {@code userId}.
     */
    @Nullable
    <V> V getResolvedPolicy(@NonNull PolicyDefinition<V> policyDefinition, int userId) {
        Objects.requireNonNull(policyDefinition);

        synchronized (mLock) {
            PolicyValue<V> resolvedValue = null;
            if (hasLocalPolicyLocked(policyDefinition, userId)) {
                resolvedValue = getLocalPolicyStateLocked(
                        policyDefinition, userId).getCurrentResolvedPolicy();
            } else if (hasGlobalPolicyLocked(policyDefinition)) {
                resolvedValue = getGlobalPolicyStateLocked(
                        policyDefinition).getCurrentResolvedPolicy();
            }
            return resolvedValue == null ? null : resolvedValue.getValue();
        }
    }

    /**
     * Retrieves the policy set by the admin for the provided {@code policyDefinition} and
     * {@code userId} if one was set, otherwise returns {@code null}.
     */
    @Nullable
    <V> V getLocalPolicySetByAdmin(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            int userId) {
        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);

        synchronized (mLock) {
            if (!hasLocalPolicyLocked(policyDefinition, userId)) {
                return null;
            }
            PolicyValue<V> value = getLocalPolicyStateLocked(policyDefinition, userId)
                    .getPoliciesSetByAdmins().get(enforcingAdmin);
            return value == null ? null : value.getValue();
        }
    }

    /**
     * Retrieves the values set for the provided {@code policyDefinition} by each admin.
     */
    @NonNull
    <V> LinkedHashMap<EnforcingAdmin, PolicyValue<V>> getLocalPoliciesSetByAdmins(
            @NonNull PolicyDefinition<V> policyDefinition,
            int userId) {
        Objects.requireNonNull(policyDefinition);

        synchronized (mLock) {
            if (!hasLocalPolicyLocked(policyDefinition, userId)) {
                return new LinkedHashMap<>();
            }
            return getLocalPolicyStateLocked(policyDefinition, userId).getPoliciesSetByAdmins();
        }
    }

    /**
     * Retrieves the values set for the provided {@code policyDefinition} by each admin.
     */
    @NonNull
    <V> LinkedHashMap<EnforcingAdmin, PolicyValue<V>> getGlobalPoliciesSetByAdmins(
            @NonNull PolicyDefinition<V> policyDefinition) {
        Objects.requireNonNull(policyDefinition);

        synchronized (mLock) {
            if (!hasGlobalPolicyLocked(policyDefinition)) {
                return new LinkedHashMap<>();
            }
            return getGlobalPolicyStateLocked(policyDefinition).getPoliciesSetByAdmins();
        }
    }

    /**
     * Returns the policies set by the given admin that share the same
     * {@link PolicyKey#getIdentifier()} as the provided {@code policyDefinition}.
     *
     * <p>For example, getLocalPolicyKeysSetByAdmin(PERMISSION_GRANT, admin) returns all permission
     * grants set by the given admin.
     *
     * <p>Note that this will always return at most one item for policies that do not require
     * additional params (e.g. {@link PolicyDefinition#LOCK_TASK} vs
     * {@link PolicyDefinition#PERMISSION_GRANT(String, String)}).
     *
     */
    @NonNull
    <V> Set<PolicyKey> getLocalPolicyKeysSetByAdmin(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            int userId) {
        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);

        synchronized (mLock) {
            if (policyDefinition.isGlobalOnlyPolicy() || !mLocalPolicies.contains(userId)) {
                return Set.of();
            }
            Set<PolicyKey> keys = new HashSet<>();
            for (PolicyKey key : mLocalPolicies.get(userId).keySet()) {
                if (key.hasSameIdentifierAs(policyDefinition.getPolicyKey())
                        && mLocalPolicies.get(userId).get(key).getPoliciesSetByAdmins()
                        .containsKey(enforcingAdmin)) {
                    keys.add(key);
                }
            }
            return keys;
        }
    }

    /**
     * Returns all the {@code policyKeys} set by any admin that share the same
     * {@link PolicyKey#getIdentifier()} as the provided {@code policyDefinition}.
     *
     * <p>For example, getLocalPolicyKeysSetByAllAdmins(PERMISSION_GRANT) returns all permission
     * grants set by any admin.
     *
     * <p>Note that this will always return at most one item for policies that do not require
     * additional params (e.g. {@link PolicyDefinition#LOCK_TASK} vs
     * {@link PolicyDefinition#PERMISSION_GRANT(String, String)}).
     *
     */
    @NonNull
    <V> Set<PolicyKey> getLocalPolicyKeysSetByAllAdmins(
            @NonNull PolicyDefinition<V> policyDefinition,
            int userId) {
        Objects.requireNonNull(policyDefinition);

        synchronized (mLock) {
            if (policyDefinition.isGlobalOnlyPolicy() || !mLocalPolicies.contains(userId)) {
                return Set.of();
            }
            Set<PolicyKey> keys = new HashSet<>();
            for (PolicyKey key : mLocalPolicies.get(userId).keySet()) {
                if (key.hasSameIdentifierAs(policyDefinition.getPolicyKey())) {
                    keys.add(key);
                }
            }
            return keys;
        }
    }

    /**
     * Returns all user restriction policies set by the given admin.
     *
     * <p>Pass in {@link UserHandle#USER_ALL} for {@code userId} to get global restrictions set by
     * the admin
     */
    @NonNull
    Set<UserRestrictionPolicyKey> getUserRestrictionPolicyKeysForAdmin(
            @NonNull EnforcingAdmin admin,
            int userId) {
        Objects.requireNonNull(admin);
        synchronized (mLock) {
            if (userId == UserHandle.USER_ALL) {
                return getUserRestrictionPolicyKeysForAdminLocked(mGlobalPolicies, admin);
            }
            if (!mLocalPolicies.contains(userId)) {
                return Set.of();
            }
            return getUserRestrictionPolicyKeysForAdminLocked(mLocalPolicies.get(userId), admin);
        }
    }

    <V> void transferPolicies(EnforcingAdmin oldAdmin, EnforcingAdmin newAdmin) {
        Set<PolicyKey> globalPolicies = new HashSet<>(mGlobalPolicies.keySet());
        for (PolicyKey policy : globalPolicies) {
            PolicyState<?> policyState = mGlobalPolicies.get(policy);
            if (policyState.getPoliciesSetByAdmins().containsKey(oldAdmin)) {
                PolicyDefinition<V> policyDefinition =
                        (PolicyDefinition<V>) policyState.getPolicyDefinition();
                PolicyValue<V> policyValue =
                        (PolicyValue<V>) policyState.getPoliciesSetByAdmins().get(oldAdmin);
                setGlobalPolicy(policyDefinition, newAdmin, policyValue);
            }
        }

        for (int i = 0; i < mLocalPolicies.size(); i++) {
            int userId = mLocalPolicies.keyAt(i);
            Set<PolicyKey> localPolicies = new HashSet<>(
                    mLocalPolicies.get(userId).keySet());
            for (PolicyKey policy : localPolicies) {
                PolicyState<?> policyState = mLocalPolicies.get(userId).get(policy);
                if (policyState.getPoliciesSetByAdmins().containsKey(oldAdmin)) {
                    PolicyDefinition<V> policyDefinition =
                            (PolicyDefinition<V>) policyState.getPolicyDefinition();
                    PolicyValue<V> policyValue =
                            (PolicyValue<V>) policyState.getPoliciesSetByAdmins().get(oldAdmin);
                    setLocalPolicy(policyDefinition, newAdmin, policyValue, userId);
                }
            }
        }

        removePoliciesForAdmin(oldAdmin);
    }

    private Set<UserRestrictionPolicyKey> getUserRestrictionPolicyKeysForAdminLocked(
            Map<PolicyKey, PolicyState<?>> policies,
            EnforcingAdmin admin) {
        Set<UserRestrictionPolicyKey> keys = new HashSet<>();
        for (PolicyKey key : policies.keySet()) {
            if (!policies.get(key).getPolicyDefinition().isUserRestrictionPolicy()) {
                continue;
            }
            // User restriction policies are always boolean
            PolicyValue<Boolean> value = (PolicyValue<Boolean>) policies.get(key)
                    .getPoliciesSetByAdmins().get(admin);
            if (value == null || !value.getValue()) {
                continue;
            }
            keys.add((UserRestrictionPolicyKey) key);
        }
        return keys;
    }

    private <V> boolean hasLocalPolicyLocked(PolicyDefinition<V> policyDefinition, int userId) {
        if (policyDefinition.isGlobalOnlyPolicy()) {
            return false;
        }
        if (!mLocalPolicies.contains(userId)) {
            return false;
        }
        if (!mLocalPolicies.get(userId).containsKey(policyDefinition.getPolicyKey())) {
            return false;
        }
        return !mLocalPolicies.get(userId).get(policyDefinition.getPolicyKey())
                .getPoliciesSetByAdmins().isEmpty();
    }

    private <V> boolean hasGlobalPolicyLocked(PolicyDefinition<V> policyDefinition) {
        if (policyDefinition.isLocalOnlyPolicy()) {
            return false;
        }
        if (!mGlobalPolicies.containsKey(policyDefinition.getPolicyKey())) {
            return false;
        }
        return !mGlobalPolicies.get(policyDefinition.getPolicyKey()).getPoliciesSetByAdmins()
                .isEmpty();
    }

    @NonNull
    private <V> PolicyState<V> getLocalPolicyStateLocked(
            PolicyDefinition<V> policyDefinition, int userId) {

        if (policyDefinition.isGlobalOnlyPolicy()) {
            throw new IllegalArgumentException(policyDefinition.getPolicyKey() + " is a global only"
                    + " policy.");
        }

        if (!mLocalPolicies.contains(userId)) {
            mLocalPolicies.put(userId, new HashMap<>());
        }
        if (!mLocalPolicies.get(userId).containsKey(policyDefinition.getPolicyKey())) {
            mLocalPolicies.get(userId).put(
                    policyDefinition.getPolicyKey(), new PolicyState<>(policyDefinition));
        }
        return getPolicyState(mLocalPolicies.get(userId), policyDefinition);
    }

    private <V> void removeLocalPolicyStateLocked(
            PolicyDefinition<V> policyDefinition, int userId) {
        if (!mLocalPolicies.contains(userId)) {
            return;
        }
        mLocalPolicies.get(userId).remove(policyDefinition.getPolicyKey());
    }

    @NonNull
    private <V> PolicyState<V> getGlobalPolicyStateLocked(PolicyDefinition<V> policyDefinition) {
        if (policyDefinition.isLocalOnlyPolicy()) {
            throw new IllegalArgumentException(policyDefinition.getPolicyKey() + " is a local only"
                    + " policy.");
        }

        if (!mGlobalPolicies.containsKey(policyDefinition.getPolicyKey())) {
            mGlobalPolicies.put(
                    policyDefinition.getPolicyKey(), new PolicyState<>(policyDefinition));
        }
        return getPolicyState(mGlobalPolicies, policyDefinition);
    }

    private <V> void removeGlobalPolicyStateLocked(PolicyDefinition<V> policyDefinition) {
        mGlobalPolicies.remove(policyDefinition.getPolicyKey());
    }

    private static <V> PolicyState<V> getPolicyState(
            Map<PolicyKey, PolicyState<?>> policies, PolicyDefinition<V> policyDefinition) {
        try {
            // This will not throw an exception because policyDefinition is of type V, so unless
            // we've created two policies with the same key but different types - we can only have
            // stored a PolicyState of the right type.
            PolicyState<V> policyState = (PolicyState<V>) policies.get(
                    policyDefinition.getPolicyKey());
            return policyState;
        } catch (ClassCastException exception) {
            // TODO: handle exception properly
            throw new IllegalArgumentException();
        }
    }

    private <V> void enforcePolicy(PolicyDefinition<V> policyDefinition,
            @Nullable PolicyValue<V> policyValue, int userId) {
        // null policyValue means remove any enforced policies, ensure callbacks handle this
        // properly
        policyDefinition.enforcePolicy(
                policyValue == null ? null : policyValue.getValue(), mContext, userId);
    }

    private void sendDevicePolicyChangedToSystem(int userId) {
        Intent intent = new Intent(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        Bundle options = new BroadcastOptions()
                .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
                .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE)
                .toBundle();
        Binder.withCleanCallingIdentity(() -> mContext.sendBroadcastAsUser(
                intent,
                new UserHandle(userId),
                /* receiverPermissions= */ null,
                options));
    }

    private <V> void sendPolicyResultToAdmin(
            EnforcingAdmin admin, PolicyDefinition<V> policyDefinition, int result, int userId) {
        Intent intent = new Intent(PolicyUpdateReceiver.ACTION_DEVICE_POLICY_SET_RESULT);
        intent.setPackage(admin.getPackageName());

        Binder.withCleanCallingIdentity(() -> {
            List<ResolveInfo> receivers =
                    mContext.getPackageManager().queryBroadcastReceiversAsUser(
                            intent,
                            PackageManager.ResolveInfoFlags.of(PackageManager.GET_RECEIVERS),
                            admin.getUserId());
            if (receivers.isEmpty()) {
                Log.i(TAG, "Couldn't find any receivers that handle ACTION_DEVICE_POLICY_SET_RESULT"
                        + " in package " + admin.getPackageName());
                return;
            }

            Bundle extras = new Bundle();
            policyDefinition.getPolicyKey().writeToBundle(extras);
            extras.putInt(
                    EXTRA_POLICY_TARGET_USER_ID,
                    getTargetUser(admin.getUserId(), userId));
            extras.putInt(
                    EXTRA_POLICY_UPDATE_RESULT_KEY,
                    result);

            intent.putExtras(extras);

            maybeSendIntentToAdminReceivers(intent, UserHandle.of(admin.getUserId()), receivers);
        });
    }

    // TODO(b/261430877): Finalise the decision on which admins to send the updates to.
    private <V> void sendPolicyChangedToAdmins(
            PolicyState<V> policyState,
            EnforcingAdmin callingAdmin,
            PolicyDefinition<V> policyDefinition,
            int userId) {
        for (EnforcingAdmin admin: policyState.getPoliciesSetByAdmins().keySet()) {
            // We're sending a separate broadcast for the calling admin with the result.
            if (admin.equals(callingAdmin)) {
                continue;
            }
            int result = Objects.equals(
                    policyState.getPoliciesSetByAdmins().get(admin),
                    policyState.getCurrentResolvedPolicy())
                    ? RESULT_POLICY_SET : RESULT_FAILURE_CONFLICTING_ADMIN_POLICY;
            maybeSendOnPolicyChanged(
                    admin, policyDefinition, result, userId);
        }
    }

    private <V> void maybeSendOnPolicyChanged(
            EnforcingAdmin admin, PolicyDefinition<V> policyDefinition, int reason,
            int userId) {
        Intent intent = new Intent(PolicyUpdateReceiver.ACTION_DEVICE_POLICY_CHANGED);
        intent.setPackage(admin.getPackageName());

        Binder.withCleanCallingIdentity(() -> {
            List<ResolveInfo> receivers =
                    mContext.getPackageManager().queryBroadcastReceiversAsUser(
                            intent,
                            PackageManager.ResolveInfoFlags.of(PackageManager.GET_RECEIVERS),
                            admin.getUserId());
            if (receivers.isEmpty()) {
                Log.i(TAG, "Couldn't find any receivers that handle ACTION_DEVICE_POLICY_CHANGED"
                        + " in package " + admin.getPackageName());
                return;
            }

            Bundle extras = new Bundle();
            policyDefinition.getPolicyKey().writeToBundle(extras);
            extras.putInt(
                    EXTRA_POLICY_TARGET_USER_ID,
                    getTargetUser(admin.getUserId(), userId));
            extras.putInt(EXTRA_POLICY_UPDATE_RESULT_KEY, reason);
            intent.putExtras(extras);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

            maybeSendIntentToAdminReceivers(
                    intent, UserHandle.of(admin.getUserId()), receivers);
        });
    }

    private void maybeSendIntentToAdminReceivers(
            Intent intent, UserHandle userHandle, List<ResolveInfo> receivers) {
        for (ResolveInfo resolveInfo : receivers) {
            if (!Manifest.permission.BIND_DEVICE_ADMIN.equals(
                    resolveInfo.activityInfo.permission)) {
                Log.w(TAG, "Receiver " + resolveInfo.activityInfo + " is not protected by "
                        + "BIND_DEVICE_ADMIN permission!");
                continue;
            }
            // TODO: If admins are always bound to, do I still need to set
            //  "BroadcastOptions.setBackgroundActivityStartsAllowed"?
            // TODO: maybe protect it with a permission that is granted to the role so that we
            //  don't accidentally send a broadcast to an admin that no longer holds the role.
            mContext.sendBroadcastAsUser(intent, userHandle);
        }
    }

    private int getTargetUser(int adminUserId, int targetUserId) {
        if (targetUserId == UserHandle.USER_ALL) {
            return TargetUser.GLOBAL_USER_ID;
        }
        if (adminUserId == targetUserId) {
            return TargetUser.LOCAL_USER_ID;
        }
        if (getProfileParentId(adminUserId) == targetUserId) {
            return TargetUser.PARENT_USER_ID;
        }
        return TargetUser.UNKNOWN_USER_ID;
    }

    private int getProfileParentId(int userId) {
        return Binder.withCleanCallingIdentity(() -> {
            UserInfo parentUser = mUserManager.getProfileParent(userId);
            return parentUser != null ? parentUser.id : userId;
        });
    }

    /**
     * Starts/Stops the services that handle {@link DevicePolicyManager#ACTION_DEVICE_ADMIN_SERVICE}
     * in the enforcing admins for the given {@code userId}.
     */
    private void updateDeviceAdminsServicesForUser(
            int userId, boolean enable, @NonNull String actionForLog) {
        if (!enable) {
            mDeviceAdminServiceController.stopServicesForUser(
                    userId, actionForLog);
        } else {
            for (EnforcingAdmin admin : getEnforcingAdminsOnUser(userId)) {
                // DPCs are handled separately in DPMS, no need to reestablish the connection here.
                if (admin.hasAuthority(EnforcingAdmin.DPC_AUTHORITY)) {
                    continue;
                }
                mDeviceAdminServiceController.startServiceForAdmin(
                        admin.getPackageName(), userId, actionForLog);
            }
        }
    }

    /**
     * Handles internal state related to a user getting started.
     */
    void handleStartUser(int userId) {
        updateDeviceAdminsServicesForUser(
                userId, /* enable= */ true, /* actionForLog= */ "start-user");
    }

    /**
     * Handles internal state related to a user getting started.
     */
    void handleUnlockUser(int userId) {
        updateDeviceAdminsServicesForUser(
                userId, /* enable= */ true, /* actionForLog= */ "unlock-user");
    }

    /**
     * Handles internal state related to a user getting stopped.
     */
    void handleStopUser(int userId) {
        updateDeviceAdminsServicesForUser(
                userId, /* enable= */ false, /* actionForLog= */ "stop-user");
    }

    /**
     * Handles internal state related to packages getting updated.
     */
    void handlePackageChanged(
            @Nullable String updatedPackage, int userId, @Nullable String removedDpcPackage) {
        Binder.withCleanCallingIdentity(() -> {
            Set<EnforcingAdmin> admins = getEnforcingAdminsOnUser(userId);
            if (removedDpcPackage != null) {
                for (EnforcingAdmin admin : admins) {
                    if (removedDpcPackage.equals(admin.getPackageName())) {
                        removePoliciesForAdmin(admin);
                        return;
                    }
                }
            }
            for (EnforcingAdmin admin : admins) {
                if (updatedPackage == null || updatedPackage.equals(admin.getPackageName())) {
                    if (!isPackageInstalled(admin.getPackageName(), userId)) {
                        Slogf.i(TAG, String.format(
                                "Admin package %s not found for user %d, removing admin policies",
                                admin.getPackageName(), userId));
                        // remove policies for the uninstalled package
                        removePoliciesForAdmin(admin);
                        return;
                    }
                }
            }
            if (updatedPackage != null) {
                updateDeviceAdminServiceOnPackageChanged(updatedPackage, userId);
                removePersistentPreferredActivityPoliciesForPackage(updatedPackage, userId);
            }
        });
    }

    private void removePersistentPreferredActivityPoliciesForPackage(
            @NonNull String packageName, int userId) {
        Set<PolicyKey> policyKeys = getLocalPolicyKeysSetByAllAdmins(
                PolicyDefinition.GENERIC_PERSISTENT_PREFERRED_ACTIVITY, userId);
        for (PolicyKey key : policyKeys) {
            if (!(key instanceof IntentFilterPolicyKey)) {
                throw new IllegalStateException("PolicyKey for "
                        + "PERSISTENT_PREFERRED_ACTIVITY is not of type "
                        + "IntentFilterPolicyKey");
            }
            IntentFilterPolicyKey parsedKey =
                    (IntentFilterPolicyKey) key;
            IntentFilter intentFilter = Objects.requireNonNull(parsedKey.getIntentFilter());
            PolicyDefinition<ComponentName> policyDefinition =
                    PolicyDefinition.PERSISTENT_PREFERRED_ACTIVITY(intentFilter);
            LinkedHashMap<EnforcingAdmin, PolicyValue<ComponentName>> policies =
                    getLocalPoliciesSetByAdmins(
                            policyDefinition,
                            userId);
            IPackageManager packageManager = AppGlobals.getPackageManager();
            for (EnforcingAdmin admin : policies.keySet()) {
                if (policies.get(admin).getValue() != null
                        && policies.get(admin).getValue().getPackageName().equals(packageName)) {
                    try {
                        if (packageManager.getPackageInfo(packageName, 0, userId) == null
                                || packageManager.getActivityInfo(
                                        policies.get(admin).getValue(), 0, userId) == null) {
                            Slogf.e(TAG, String.format(
                                    "Persistent preferred activity in package %s not found for "
                                            + "user %d, removing policy for admin",
                                    packageName, userId));
                            removeLocalPolicy(policyDefinition, admin, userId);
                        }
                    } catch (RemoteException re) {
                        // Shouldn't happen.
                        Slogf.wtf(TAG, "Error handling package changes", re);
                    }
                }
            }
        }
    }

    private boolean isPackageInstalled(String packageName, int userId) {
        try {
            return AppGlobals.getPackageManager().getPackageInfo(
                    packageName, 0, userId) != null;
        } catch (RemoteException re) {
            // Shouldn't happen.
            Slogf.wtf(TAG, "Error handling package changes", re);
            return true;
        }
    }

    /**
     * Handles internal state related to a user getting removed.
     */
    void handleUserRemoved(int userId) {
        removeLocalPoliciesForUser(userId);
        removePoliciesForAdminsOnUser(userId);
    }

    /**
     * Handles internal state related to a user getting created.
     */
    void handleUserCreated(UserInfo user) {
        enforcePoliciesOnInheritableProfilesIfApplicable(user);
    }

    /**
     * Handles internal state related to roles getting updated.
     */
    void handleRoleChanged(@NonNull String roleName, int userId) {
        // TODO(b/256852787): handle all roles changing.
        if (!DEVICE_LOCK_CONTROLLER_ROLE.equals(roleName)) {
            // We only support device lock controller role for now.
            return;
        }
        String roleAuthority = EnforcingAdmin.getRoleAuthorityOf(roleName);
        Set<EnforcingAdmin> admins = getEnforcingAdminsOnUser(userId);
        for (EnforcingAdmin admin : admins) {
            if (admin.hasAuthority(roleAuthority)) {
                admin.reloadRoleAuthorities();
                // remove admin policies if role was lost
                if (!admin.hasAuthority(roleAuthority)) {
                    removePoliciesForAdmin(admin);
                }
            }
        }
    }

    private void enforcePoliciesOnInheritableProfilesIfApplicable(UserInfo user) {
        if (!user.isProfile()) {
            return;
        }

        Binder.withCleanCallingIdentity(() -> {
            UserProperties userProperties = mUserManager.getUserProperties(user.getUserHandle());
            if (userProperties == null || userProperties.getInheritDevicePolicy()
                    != INHERIT_DEVICE_POLICY_FROM_PARENT) {
                return;
            }

            int userId = user.id;
            // Apply local policies present on parent to newly created child profile.
            UserInfo parentInfo = mUserManager.getProfileParent(userId);
            if (parentInfo == null || parentInfo.getUserHandle().getIdentifier() == userId) {
                return;
            }
            if (!mLocalPolicies.contains(parentInfo.getUserHandle().getIdentifier())) {
                return;
            }
            for (Map.Entry<PolicyKey, PolicyState<?>> entry : mLocalPolicies.get(
                    parentInfo.getUserHandle().getIdentifier()).entrySet()) {
                enforcePolicyOnUser(userId, entry.getValue());
            }
        });
    }

    private <V> void enforcePolicyOnUser(int userId, PolicyState<V> policyState) {
        if (!policyState.getPolicyDefinition().isInheritable()) {
            return;
        }
        for (Map.Entry<EnforcingAdmin, PolicyValue<V>> enforcingAdminEntry :
                policyState.getPoliciesSetByAdmins().entrySet()) {
            setLocalPolicy(policyState.getPolicyDefinition(),
                    enforcingAdminEntry.getKey(),
                    enforcingAdminEntry.getValue(),
                    userId);
        }
    }

    /**
     * Returns all current enforced policies set on the device, and the individual values set by
     * each admin. Global policies are returned under {@link UserHandle#ALL}.
     */
    @NonNull
    DevicePolicyState getDevicePolicyState() {
        Map<UserHandle, Map<PolicyKey, android.app.admin.PolicyState<?>>> policies =
                new HashMap<>();
        for (int i = 0; i < mLocalPolicies.size(); i++) {
            UserHandle user = UserHandle.of(mLocalPolicies.keyAt(i));
            policies.put(user, new HashMap<>());
            for (PolicyKey policyKey : mLocalPolicies.valueAt(i).keySet()) {
                policies.get(user).put(
                        policyKey,
                        mLocalPolicies.valueAt(i).get(policyKey).getParcelablePolicyState());
            }
        }
        if (!mGlobalPolicies.isEmpty()) {
            policies.put(UserHandle.ALL, new HashMap<>());
            for (PolicyKey policyKey : mGlobalPolicies.keySet()) {
                policies.get(UserHandle.ALL).put(
                        policyKey,
                        mGlobalPolicies.get(policyKey).getParcelablePolicyState());
            }
        }
        return new DevicePolicyState(policies);
    }


    /**
     * Removes all local and global policies set by that admin.
     */
    void removePoliciesForAdmin(EnforcingAdmin admin) {
        Set<PolicyKey> globalPolicies = new HashSet<>(mGlobalPolicies.keySet());
        for (PolicyKey policy : globalPolicies) {
            PolicyState<?> policyState = mGlobalPolicies.get(policy);
            if (policyState.getPoliciesSetByAdmins().containsKey(admin)) {
                removeGlobalPolicy(policyState.getPolicyDefinition(), admin);
            }
        }

        for (int i = 0; i < mLocalPolicies.size(); i++) {
            Set<PolicyKey> localPolicies = new HashSet<>(
                    mLocalPolicies.get(mLocalPolicies.keyAt(i)).keySet());
            for (PolicyKey policy : localPolicies) {
                PolicyState<?> policyState = mLocalPolicies.get(
                        mLocalPolicies.keyAt(i)).get(policy);
                if (policyState.getPoliciesSetByAdmins().containsKey(admin)) {
                    removeLocalPolicy(
                            policyState.getPolicyDefinition(), admin, mLocalPolicies.keyAt(i));
                }
            }
        }
    }

    /**
     * Removes all local policies for the provided {@code userId}.
     */
    private void removeLocalPoliciesForUser(int userId) {
        if (!mLocalPolicies.contains(userId)) {
            // No policies on user
            return;
        }

        Set<PolicyKey> localPolicies = new HashSet<>(mLocalPolicies.get(userId).keySet());
        for (PolicyKey policy : localPolicies) {
            PolicyState<?> policyState = mLocalPolicies.get(userId).get(policy);
            Set<EnforcingAdmin> admins = new HashSet<>(
                    policyState.getPoliciesSetByAdmins().keySet());
            for (EnforcingAdmin admin : admins) {
                removeLocalPolicy(
                        policyState.getPolicyDefinition(), admin, userId);
            }
        }

        mLocalPolicies.remove(userId);
    }

    /**
     * Removes all local and global policies for admins installed in the provided
     * {@code userId}.
     */
    private void removePoliciesForAdminsOnUser(int userId) {
        Set<EnforcingAdmin> admins = getEnforcingAdminsOnUser(userId);

        for (EnforcingAdmin admin : admins) {
            removePoliciesForAdmin(admin);
        }
    }

    /**
     * Reestablishes the service that handles
     * {@link DevicePolicyManager#ACTION_DEVICE_ADMIN_SERVICE} in the enforcing admin if the package
     * was updated, as a package update results in the persistent connection getting reset.
     */
    private void updateDeviceAdminServiceOnPackageChanged(
            @NonNull String updatedPackage, int userId) {
        for (EnforcingAdmin admin : getEnforcingAdminsOnUser(userId)) {
            // DPCs are handled separately in DPMS, no need to reestablish the connection here.
            if (admin.hasAuthority(EnforcingAdmin.DPC_AUTHORITY)) {
                continue;
            }
            if (updatedPackage.equals(admin.getPackageName())) {
                mDeviceAdminServiceController.startServiceForAdmin(
                        updatedPackage, userId, /* actionForLog= */ "package-broadcast");
            }
        }
    }

    /**
     * Called after an admin policy has been added to start binding to the admin if a connection
     * was not already established.
     */
    private void updateDeviceAdminServiceOnPolicyAddLocked(@NonNull EnforcingAdmin enforcingAdmin) {
        int userId = enforcingAdmin.getUserId();

        if (mEnforcingAdmins.contains(userId)
                && mEnforcingAdmins.get(userId).contains(enforcingAdmin)) {
            return;
        }

        if (!mEnforcingAdmins.contains(enforcingAdmin.getUserId())) {
            mEnforcingAdmins.put(enforcingAdmin.getUserId(), new HashSet<>());
        }
        mEnforcingAdmins.get(enforcingAdmin.getUserId()).add(enforcingAdmin);

        // A connection is established with DPCs as soon as they are provisioned, so no need to
        // connect when a policy is set.
        if (enforcingAdmin.hasAuthority(EnforcingAdmin.DPC_AUTHORITY)) {
            return;
        }
        mDeviceAdminServiceController.startServiceForAdmin(
                enforcingAdmin.getPackageName(),
                userId,
                /* actionForLog= */ "policy-added");
    }

    /**
     * Called after an admin policy has been removed to stop binding to the admin if they no longer
     * have any policies set.
     */
    private void updateDeviceAdminServiceOnPolicyRemoveLocked(
            @NonNull EnforcingAdmin enforcingAdmin) {
        if (doesAdminHavePolicies(enforcingAdmin)) {
            return;
        }
        int userId = enforcingAdmin.getUserId();
        if (mEnforcingAdmins.contains(userId)) {
            mEnforcingAdmins.get(userId).remove(enforcingAdmin);
            if (mEnforcingAdmins.get(userId).isEmpty()) {
                mEnforcingAdmins.remove(enforcingAdmin.getUserId());
            }
        }

        // TODO(b/263364434): centralise handling in one place.
        // DPCs rely on a constant connection being established as soon as they are provisioned,
        // so we shouldn't disconnect it even if they no longer have policies set.
        if (enforcingAdmin.hasAuthority(EnforcingAdmin.DPC_AUTHORITY)) {
            return;
        }
        mDeviceAdminServiceController.stopServiceForAdmin(
                enforcingAdmin.getPackageName(),
                userId,
                /* actionForLog= */ "policy-removed");
    }

    private boolean doesAdminHavePolicies(@NonNull EnforcingAdmin enforcingAdmin) {
        for (PolicyKey policy : mGlobalPolicies.keySet()) {
            PolicyState<?> policyState = mGlobalPolicies.get(policy);
            if (policyState.getPoliciesSetByAdmins().containsKey(enforcingAdmin)) {
                return true;
            }
        }
        for (int i = 0; i < mLocalPolicies.size(); i++) {
            for (PolicyKey policy : mLocalPolicies.get(mLocalPolicies.keyAt(i)).keySet()) {
                PolicyState<?> policyState = mLocalPolicies.get(
                        mLocalPolicies.keyAt(i)).get(policy);
                if (policyState.getPoliciesSetByAdmins().containsKey(enforcingAdmin)) {
                    return true;
                }
            }
        }
        return false;
    }

    @NonNull
    private Set<EnforcingAdmin> getEnforcingAdminsOnUser(int userId) {
        return mEnforcingAdmins.contains(userId)
                ? mEnforcingAdmins.get(userId) : Collections.emptySet();
    }

    private void write() {
        Log.d(TAG, "Writing device policies to file.");
        new DevicePoliciesReaderWriter().writeToFileLocked();
    }

    // TODO(b/256852787): trigger resolving logic after loading policies as roles are recalculated
    //  and could result in a different enforced policy
    void load() {
        Log.d(TAG, "Reading device policies from file.");
        synchronized (mLock) {
            clear();
            new DevicePoliciesReaderWriter().readFromFileLocked();
            reapplyAllPolicies();
        }
    }

    private <V> void reapplyAllPolicies() {
        for (PolicyKey policy : mGlobalPolicies.keySet()) {
            PolicyState<?> policyState = mGlobalPolicies.get(policy);
            // Policy definition and value will always be of the same type
            PolicyDefinition<V> policyDefinition =
                    (PolicyDefinition<V>) policyState.getPolicyDefinition();
            PolicyValue<V> policyValue = (PolicyValue<V>) policyState.getCurrentResolvedPolicy();
            enforcePolicy(policyDefinition, policyValue, UserHandle.USER_ALL);
        }
        for (int i = 0; i < mLocalPolicies.size(); i++) {
            int userId = mLocalPolicies.keyAt(i);
            for (PolicyKey policy : mLocalPolicies.get(userId).keySet()) {
                PolicyState<?> policyState = mLocalPolicies.get(userId).get(policy);
                // Policy definition and value will always be of the same type
                PolicyDefinition<V> policyDefinition =
                        (PolicyDefinition<V>) policyState.getPolicyDefinition();
                PolicyValue<V> policyValue =
                        (PolicyValue<V>) policyState.getCurrentResolvedPolicy();
                enforcePolicy(policyDefinition, policyValue, userId);

            }
        }
    }

    /**
     * Clear all policies set in the policy engine.
     *
     * <p>Note that this doesn't clear any enforcements, it only clears the data structures.
     */
    void clearAllPolicies() {
        synchronized (mLock) {
            clear();
            write();
        }
    }
    private void clear() {
        synchronized (mLock) {
            mGlobalPolicies.clear();
            mLocalPolicies.clear();
            mEnforcingAdmins.clear();
        }
    }

    private <V> boolean checkFor2gFailure(@NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin) {
        if (!policyDefinition.getPolicyKey().getIdentifier().equals(
                CELLULAR_2G_USER_RESTRICTION_ID)) {
            return false;
        }

        boolean isCapabilitySupported;
        try {
            isCapabilitySupported = mContext.getSystemService(
                    TelephonyManager.class).isRadioInterfaceCapabilitySupported(
                    TelephonyManager.CAPABILITY_USES_ALLOWED_NETWORK_TYPES_BITMASK);
        } catch (IllegalStateException e) {
            // isRadioInterfaceCapabilitySupported can throw if there is no Telephony
            // service initialized.
            isCapabilitySupported = false;
        }

        if (!isCapabilitySupported) {
            sendPolicyResultToAdmin(
                    enforcingAdmin,
                    policyDefinition,
                    RESULT_FAILURE_HARDWARE_LIMITATION,
                    UserHandle.USER_ALL);
            return true;
        }

        return false;
    }

    private class DevicePoliciesReaderWriter {
        private static final String DEVICE_POLICIES_XML = "device_policy_state.xml";
        private static final String TAG_LOCAL_POLICY_ENTRY = "local-policy-entry";
        private static final String TAG_GLOBAL_POLICY_ENTRY = "global-policy-entry";
        private static final String TAG_POLICY_STATE_ENTRY = "policy-state-entry";
        private static final String TAG_POLICY_KEY_ENTRY = "policy-key-entry";
        private static final String TAG_ENFORCING_ADMINS_ENTRY = "enforcing-admins-entry";
        private static final String ATTR_USER_ID = "user-id";

        private final File mFile;

        private DevicePoliciesReaderWriter() {
            mFile = new File(Environment.getDataSystemDirectory(), DEVICE_POLICIES_XML);
        }

        void writeToFileLocked() {
            Log.d(TAG, "Writing to " + mFile);

            AtomicFile f = new AtomicFile(mFile);
            FileOutputStream outputStream = null;
            try {
                outputStream = f.startWrite();
                TypedXmlSerializer out = Xml.resolveSerializer(outputStream);

                out.startDocument(null, true);

                // Actual content
                writeInner(out);

                out.endDocument();
                out.flush();

                // Commit the content.
                f.finishWrite(outputStream);
                outputStream = null;

            } catch (IOException e) {
                Log.e(TAG, "Exception when writing", e);
                if (outputStream != null) {
                    f.failWrite(outputStream);
                }
            }
        }

        // TODO(b/256846294): Add versioning to read/write
        void writeInner(TypedXmlSerializer serializer) throws IOException {
            writeLocalPoliciesInner(serializer);
            writeGlobalPoliciesInner(serializer);
            writeEnforcingAdminsInner(serializer);
        }

        private void writeLocalPoliciesInner(TypedXmlSerializer serializer) throws IOException {
            if (mLocalPolicies != null) {
                for (int i = 0; i < mLocalPolicies.size(); i++) {
                    int userId = mLocalPolicies.keyAt(i);
                    for (Map.Entry<PolicyKey, PolicyState<?>> policy : mLocalPolicies.get(
                            userId).entrySet()) {
                        serializer.startTag(/* namespace= */ null, TAG_LOCAL_POLICY_ENTRY);

                        serializer.attributeInt(/* namespace= */ null, ATTR_USER_ID, userId);

                        serializer.startTag(/* namespace= */ null, TAG_POLICY_KEY_ENTRY);
                        policy.getKey().saveToXml(serializer);
                        serializer.endTag(/* namespace= */ null, TAG_POLICY_KEY_ENTRY);

                        serializer.startTag(/* namespace= */ null, TAG_POLICY_STATE_ENTRY);
                        policy.getValue().saveToXml(serializer);
                        serializer.endTag(/* namespace= */ null, TAG_POLICY_STATE_ENTRY);

                        serializer.endTag(/* namespace= */ null, TAG_LOCAL_POLICY_ENTRY);
                    }
                }
            }
        }

        private void writeGlobalPoliciesInner(TypedXmlSerializer serializer) throws IOException {
            if (mGlobalPolicies != null) {
                for (Map.Entry<PolicyKey, PolicyState<?>> policy : mGlobalPolicies.entrySet()) {
                    serializer.startTag(/* namespace= */ null, TAG_GLOBAL_POLICY_ENTRY);

                    serializer.startTag(/* namespace= */ null, TAG_POLICY_KEY_ENTRY);
                    policy.getKey().saveToXml(serializer);
                    serializer.endTag(/* namespace= */ null, TAG_POLICY_KEY_ENTRY);

                    serializer.startTag(/* namespace= */ null, TAG_POLICY_STATE_ENTRY);
                    policy.getValue().saveToXml(serializer);
                    serializer.endTag(/* namespace= */ null, TAG_POLICY_STATE_ENTRY);

                    serializer.endTag(/* namespace= */ null, TAG_GLOBAL_POLICY_ENTRY);
                }
            }
        }

        private void writeEnforcingAdminsInner(TypedXmlSerializer serializer) throws IOException {
            if (mEnforcingAdmins != null) {
                for (int i = 0; i < mEnforcingAdmins.size(); i++) {
                    int userId = mEnforcingAdmins.keyAt(i);
                    for (EnforcingAdmin admin : mEnforcingAdmins.get(userId)) {
                        serializer.startTag(/* namespace= */ null, TAG_ENFORCING_ADMINS_ENTRY);
                        admin.saveToXml(serializer);
                        serializer.endTag(/* namespace= */ null, TAG_ENFORCING_ADMINS_ENTRY);
                    }
                }
            }
        }

        void readFromFileLocked() {
            if (!mFile.exists()) {
                Log.d(TAG, "" + mFile + " doesn't exist");
                return;
            }

            Log.d(TAG, "Reading from " + mFile);
            AtomicFile f = new AtomicFile(mFile);
            InputStream input = null;
            try {
                input = f.openRead();
                TypedXmlPullParser parser = Xml.resolvePullParser(input);

                readInner(parser);

            } catch (XmlPullParserException | IOException | ClassNotFoundException e) {
                Slogf.wtf(TAG, "Error parsing resources file", e);
            } finally {
                IoUtils.closeQuietly(input);
            }
        }

        private void readInner(TypedXmlPullParser parser)
                throws IOException, XmlPullParserException, ClassNotFoundException {
            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                String tag = parser.getName();
                switch (tag) {
                    case TAG_LOCAL_POLICY_ENTRY:
                        readLocalPoliciesInner(parser);
                        break;
                    case TAG_GLOBAL_POLICY_ENTRY:
                        readGlobalPoliciesInner(parser);
                        break;
                    case TAG_ENFORCING_ADMINS_ENTRY:
                        readEnforcingAdminsInner(parser);
                        break;
                    default:
                        Slogf.wtf(TAG, "Unknown tag " + tag);
                }
            }
        }

        private void readLocalPoliciesInner(TypedXmlPullParser parser)
                throws XmlPullParserException, IOException {
            int userId = parser.getAttributeInt(/* namespace= */ null, ATTR_USER_ID);
            PolicyKey policyKey = null;
            PolicyState<?> policyState = null;
            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                String tag = parser.getName();
                switch (tag) {
                    case TAG_POLICY_KEY_ENTRY:
                        policyKey = PolicyDefinition.readPolicyKeyFromXml(parser);
                        break;
                    case TAG_POLICY_STATE_ENTRY:
                        policyState = PolicyState.readFromXml(parser);
                        break;
                    default:
                        Slogf.wtf(TAG, "Unknown tag for local policy entry" + tag);
                }
            }

            if (policyKey != null && policyState != null) {
                if (!mLocalPolicies.contains(userId)) {
                    mLocalPolicies.put(userId, new HashMap<>());
                }
                mLocalPolicies.get(userId).put(policyKey, policyState);
            } else {
                Slogf.wtf(TAG, "Error parsing local policy, policyKey is "
                        + (policyKey == null ? "null" : policyKey) + ", and policyState is "
                        + (policyState == null ? "null" : policyState) + ".");
            }
        }

        private void readGlobalPoliciesInner(TypedXmlPullParser parser)
                throws IOException, XmlPullParserException {
            PolicyKey policyKey = null;
            PolicyState<?> policyState = null;
            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                String tag = parser.getName();
                switch (tag) {
                    case TAG_POLICY_KEY_ENTRY:
                        policyKey = PolicyDefinition.readPolicyKeyFromXml(parser);
                        break;
                    case TAG_POLICY_STATE_ENTRY:
                        policyState = PolicyState.readFromXml(parser);
                        break;
                    default:
                        Slogf.wtf(TAG, "Unknown tag for local policy entry" + tag);
                }
            }

            if (policyKey != null && policyState != null) {
                mGlobalPolicies.put(policyKey, policyState);
            } else {
                Slogf.wtf(TAG, "Error parsing global policy, policyKey is "
                        + (policyKey == null ? "null" : policyKey) + ", and policyState is "
                        + (policyState == null ? "null" : policyState) + ".");
            }
        }

        private void readEnforcingAdminsInner(TypedXmlPullParser parser)
                throws XmlPullParserException {
            EnforcingAdmin admin = EnforcingAdmin.readFromXml(parser);
            if (admin == null) {
                Slogf.wtf(TAG, "Error parsing enforcingAdmins, EnforcingAdmin is null.");
                return;
            }
            if (!mEnforcingAdmins.contains(admin.getUserId())) {
                mEnforcingAdmins.put(admin.getUserId(), new HashSet<>());
            }
            mEnforcingAdmins.get(admin.getUserId()).add(admin);
        }
    }
}
