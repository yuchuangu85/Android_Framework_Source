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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.role.RoleManager;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Class that contains information about the admins that are enforcing a specific policy.
 *
 * @hide
 */
public class PolicyEnforcementInfo {
    // Contains all admins who has enforced the policy. The admins will be ordered as
    // supervision, DPC admin then any other admin if they exist in the list.
    @NonNull
    private final List<EnforcingAdmin> mAllAdmins;

    /**
     * @hide
     */
    public PolicyEnforcementInfo(@NonNull List<EnforcingAdmin> enforcingAdmins) {
        Objects.requireNonNull(enforcingAdmins);
        mAllAdmins = enforcingAdmins.stream().sorted(Comparator.comparingInt(admin -> {
            if (isSupervisionRole(admin)) {
                return 0; // Supervision role holders have the highest priority.
            }
            if (isDpcAdmin(admin)) {
                return 1; // DPC are next.
            }
            return 2; // All other admins at the end.
        })).toList();
    }

    /**
     * @hide
     */
    public List<EnforcingAdmin> getAllAdmins() {
        return mAllAdmins;
    }


    /**
     * Returns true if the policy is only enforced by system authorities.
     * @hide
     */
    public boolean isOnlyEnforcedBySystem() {
        return !mAllAdmins.isEmpty() && mAllAdmins.stream().allMatch(
                PolicyEnforcementInfo::isSystemAuthority);
    }

    /**
     * Returns true if the policy is enforced by system authorities.
     * @hide
     */
    public boolean isEnforcedBySystem() {
        return mAllAdmins.stream().anyMatch(PolicyEnforcementInfo::isSystemAuthority);
    }

    /**
     * Returns true if the policy is enforced by any admin, including system authorities.
     * @hide
     */
    public boolean isEnforced() {
        return !mAllAdmins.isEmpty();
    }

    /**
     * Returns one EnforcingAdmin from all admins that enforced the policy. If there is a
     * supervision admin, returns that admin as supervision admins have higher priority due to
     * regulations (b/392057517). If there are no admins enforcing the particular policy on device,
     * will return null.
     *
     * @hide
     */
    @Nullable
    public EnforcingAdmin getMostImportantEnforcingAdmin() {
        // Returns the first admin if the list is not empty.
        return mAllAdmins.isEmpty() ? null : mAllAdmins.getFirst();
    }

    private static boolean isSystemAuthority(EnforcingAdmin enforcingAdmin) {
        return enforcingAdmin.getAuthority() instanceof SystemAuthority;
    }

    private static boolean isSupervisionRole(EnforcingAdmin enforcingAdmin) {
        if (!(enforcingAdmin.getAuthority() instanceof RoleAuthority)) {
            return false;
        }
        return ((RoleAuthority) enforcingAdmin.getAuthority()).getRoles().contains(
                RoleManager.ROLE_SYSTEM_SUPERVISION);
    }

    private static boolean isDpcAdmin(EnforcingAdmin enforcingAdmin) {
        return enforcingAdmin.getAuthority() instanceof DpcAuthority;
    }
}
