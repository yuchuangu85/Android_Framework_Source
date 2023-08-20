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

import android.annotation.NonNull;
import android.app.admin.Authority;
import android.app.admin.PolicyValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class TopPriority<V> extends ResolutionMechanism<V> {

    private final List<String> mHighestToLowestPriorityAuthorities;

    TopPriority(@NonNull List<String> highestToLowestPriorityAuthorities) {
        Objects.requireNonNull(highestToLowestPriorityAuthorities);
        mHighestToLowestPriorityAuthorities = highestToLowestPriorityAuthorities;
    }

    @Override
    PolicyValue<V> resolve(@NonNull LinkedHashMap<EnforcingAdmin, PolicyValue<V>> adminPolicies) {
        if (adminPolicies.isEmpty()) {
            return null;
        }
        for (String authority : mHighestToLowestPriorityAuthorities) {
            Optional<EnforcingAdmin> admin = adminPolicies.keySet().stream()
                    .filter(a -> a.hasAuthority(authority)).findFirst();
            if (admin.isPresent()) {
                return adminPolicies.get(admin.get());
            }
        }
        // Return first set policy if no known authority is found
        Map.Entry<EnforcingAdmin, PolicyValue<V>> policy =
                adminPolicies.entrySet().stream().findFirst().get();
        return policy.getValue();
    }

    @Override
    android.app.admin.TopPriority<V> getParcelableResolutionMechanism() {
        return new android.app.admin.TopPriority<>(getParcelableAuthorities());
    }

    private List<Authority> getParcelableAuthorities() {
        List<Authority> authorities = new ArrayList<>();
        for (String authority : mHighestToLowestPriorityAuthorities) {
            authorities.add(EnforcingAdmin.getParcelableAuthority(authority));
        }
        return authorities;
    }

    @Override
    public String toString() {
        return "TopPriority { mHighestToLowestPriorityAuthorities= "
                + mHighestToLowestPriorityAuthorities + " }";
    }
}
