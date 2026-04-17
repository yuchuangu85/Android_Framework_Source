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

package android.app.admin.metadata;

import static android.app.admin.PolicyIdentifier.AUTO_TIME;
import static android.app.admin.PolicyIdentifier.AUTO_TIME_ZONE;
import static android.app.admin.PolicyIdentifier.CONTENT_RESTRICTION_APPS;
import static android.app.admin.PolicyIdentifier.EASTER_EGGS;
import static android.app.admin.PolicyIdentifier.LOCKSCREEN_MESSAGE;
import static android.app.admin.PolicyIdentifier.MANAGED_ESIM_OUTGOING_TRANSFER_POLICY;
import static android.app.admin.PolicyIdentifier.SCREEN_CAPTURE;

import android.app.admin.PolicyIdentifier;
import java.lang.Integer;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Generated class that contains metadata on all known policies.
 *
 * @hide
 */
public class Policies {
    /**
     * Generated method that returns a list of all policy metadata
     */
    public static List<PolicyMetadata<?>> loadPolicyMetadata() {
        List<PolicyMetadata<?>> policies = new ArrayList<PolicyMetadata<?>>();
        policies.add(new EnumPolicyMetadata(
            /* id= */ SCREEN_CAPTURE,
            /* allowedScopes= */ Set.of(
                1,
                2
            ),
            /* affectedResource= */ 2,
            /* requiredPermission= */ "android.permission.MANAGE_DEVICE_POLICY_SCREEN_CAPTURE",
            /* requiredCrossUserPermission= */ "android.permission.MANAGE_DEVICE_POLICY_ACROSS_USERS",
            /* allowedDpcTypes= */ Set.of(
                1, // DEVICE_OWNER
                3, // MANAGED_PROFILE_OWNER_OF_ORGANIZATION_OWNED_DEVICE
                4, // PROFILE_OWNER_ON_USER0
                5, // MANAGED_PROFILE_OWNER_OF_PERSONAL_OWNED_DEVICE
                6, // UNAFFILIATED_FULL_USER_PROFILE_OWNER
                7  // AFFILIATED_FULL_USER_PROFILE_OWNER
            ),
            /* resolutionMechanism= */ null,
            /* allowedValues= */ Set.of(
                1,
                2
            )
        ));
        policies.add(new EnumPolicyMetadata(
            /* id= */ AUTO_TIME,
            /* allowedScopes= */ Set.of(
                2
            ),
            /* affectedResource= */ 1,
            /* requiredPermission= */ "android.permission.SET_TIME",
            /* requiredCrossUserPermission= */ "android.permission.MANAGE_DEVICE_POLICY_ACROSS_USERS",
            /* allowedDpcTypes= */ Set.of(
                1, // DEVICE_OWNER
                3, // MANAGED_PROFILE_OWNER_OF_ORGANIZATION_OWNED_DEVICE
                4  // PROFILE_OWNER_ON_USER0
            ),
            /* resolutionMechanism= */ null,
            /* allowedValues= */ Set.of(
                0,
                1,
                2,
                3,
                4
            )
        ));
        policies.add(new EnumPolicyMetadata(
            /* id= */ MANAGED_ESIM_OUTGOING_TRANSFER_POLICY,
            /* allowedScopes= */ Set.of(
                2
            ),
            /* affectedResource= */ 1,
            /* requiredPermission= */ "android.permission.MANAGE_DEVICE_POLICY_MANAGED_SUBSCRIPTIONS",
            /* requiredCrossUserPermission= */ null,
            /* allowedDpcTypes= */ Set.of(
                1, // DEVICE_OWNER
                3, // MANAGED_PROFILE_OWNER_OF_ORGANIZATION_OWNED_DEVICE
                4, // PROFILE_OWNER_ON_USER0
                5, // MANAGED_PROFILE_OWNER_OF_PERSONAL_OWNED_DEVICE
                6, // UNAFFILIATED_FULL_USER_PROFILE_OWNER
                7  // AFFILIATED_FULL_USER_PROFILE_OWNER
            ),
            /* resolutionMechanism= */ new ResolutionMechanismMetadata.MostRestrictive<Integer>(
                List.of(
                    new Integer(2),
                    new Integer(1)
                )
            ),
            /* allowedValues= */ Set.of(
                1,
                2
            )
        ));
        policies.add(new EnumPolicyMetadata(
            /* id= */ AUTO_TIME_ZONE,
            /* allowedScopes= */ Set.of(
                2
            ),
            /* affectedResource= */ 1,
            /* requiredPermission= */ "android.permission.SET_TIME_ZONE",
            /* requiredCrossUserPermission= */ "android.permission.MANAGE_DEVICE_POLICY_ACROSS_USERS",
            /* allowedDpcTypes= */ Set.of(
                1, // DEVICE_OWNER
                3, // MANAGED_PROFILE_OWNER_OF_ORGANIZATION_OWNED_DEVICE
                4  // PROFILE_OWNER_ON_USER0
            ),
            /* resolutionMechanism= */ null,
            /* allowedValues= */ Set.of(
                0,
                1,
                2,
                3,
                4
            )
        ));
        policies.add(new EnumPolicyMetadata(
            /* id= */ EASTER_EGGS,
            /* allowedScopes= */ Set.of(
                1,
                2
            ),
            /* affectedResource= */ 2,
            /* requiredPermission= */ "android.permission.MANAGE_DEVICE_POLICY_FUN",
            /* requiredCrossUserPermission= */ "android.permission.MANAGE_DEVICE_POLICY_ACROSS_USERS",
            /* allowedDpcTypes= */ Set.of(
                1, // DEVICE_OWNER
                4, // PROFILE_OWNER_ON_USER0
                6, // UNAFFILIATED_FULL_USER_PROFILE_OWNER
                7  // AFFILIATED_FULL_USER_PROFILE_OWNER
            ),
            /* resolutionMechanism= */ null,
            /* allowedValues= */ Set.of(
                1,
                2
            )
        ));
        policies.add(new StringPolicyMetadata(
            /* id= */ LOCKSCREEN_MESSAGE,
            /* allowedScopes= */ Set.of(
                2
            ),
            /* affectedResource= */ 1,
            /* requiredPermission= */ "android.permission.MANAGE_DEVICE_POLICY_LOCKSCREEN_MESSAGE",
            /* requiredCrossUserPermission= */ "android.permission.MANAGE_DEVICE_POLICY_ACROSS_USERS",
            /* allowedDpcTypes= */ Set.of(
                1, // DEVICE_OWNER
                3  // MANAGED_PROFILE_OWNER_OF_ORGANIZATION_OWNED_DEVICE
            ),
            /* emptyStringAllowed= */ false
        ));
        policies.add(new ListPolicyMetadata<String>(
            /* id= */ CONTENT_RESTRICTION_APPS,
            /* elementMetadata= */ new StringPolicyMetadata(
                /* id= */ new PolicyIdentifier<String>(CONTENT_RESTRICTION_APPS.getId() + "#elements"),
                /* allowedScopes= */ Set.of(
                    1
                ),
                /* affectedResource= */ 2,
                /* requiredPermission= */ "android.permission.MANAGE_DEVICE_POLICY_CONTENT_RESTRICTION_APPS",
                /* requiredCrossUserPermission= */ "android.permission.MANAGE_DEVICE_POLICY_ACROSS_USERS_FULL",
                /* allowedDpcTypes= */ Set.of(
                    1, // DEVICE_OWNER
                    3  // MANAGED_PROFILE_OWNER_OF_ORGANIZATION_OWNED_DEVICE
                ),
                /* emptyStringAllowed= */ false
            ),
            /* emptyListAllowed= */ false
        ));
        return policies;
    }
}
