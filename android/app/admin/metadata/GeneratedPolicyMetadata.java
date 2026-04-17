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

import android.annotation.NonNull;
import android.app.admin.PolicyIdentifier;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Provide access to the policy metadata generated at compile time using information from the
 * {@link android.app.admin.PolicyIdentifier}.
 *
 * @hide
 */
public class GeneratedPolicyMetadata {
    /**
     * Get the policy metadata for a policy.
     *
     * @param id The identifier for the policy
     * @return Policy metadata for the policy
     * @param <T> The type of the policy
     * @throws IllegalArgumentException if the policy can not be found
     */
    @SuppressWarnings("unchecked")
    @NonNull
    public static <T> PolicyMetadata<T> getPolicyMetadata(@NonNull PolicyIdentifier<T> id) {
        // This cast is safe since key and value used the same T during construction.
        PolicyMetadata<T> metadata = (PolicyMetadata<T>) PolicyHolder.POLICIES.get(id);

        if (metadata == null) {
            throw new IllegalArgumentException("Policy identifier " + id + " could not be found");
        }

        return metadata;
    }

    @NonNull
    public static Collection<PolicyMetadata<?>> getAllPolicyMetadata() {
        return PolicyHolder.POLICIES.values();
    }

    // Initialize policies as late as possible by relying on the class loader loading them late.
    private static class PolicyHolder {
        static final Map<PolicyIdentifier<?>, PolicyMetadata<?>> POLICIES;

        static {
            Map<PolicyIdentifier<?>, PolicyMetadata<?>> builder = new HashMap<>();
            for (PolicyMetadata<?> policy : Policies.loadPolicyMetadata()) {
                builder.put(policy.getId(), policy);
            }
            POLICIES = Collections.unmodifiableMap(builder);
        }
    }
}
