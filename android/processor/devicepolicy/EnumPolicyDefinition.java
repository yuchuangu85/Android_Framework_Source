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

package android.processor.devicepolicy;

/**
 * Metadata for a enum policy. Represented by an Integer.
 */
public @interface EnumPolicyDefinition {
    /**
     * Base data for all policies.
     */
    PolicyDefinition base();

    /**
     * Indicates which IntDef represents this enum.
     */
    Class<?> intDef();

    /**
     * Indicates the conflict resolution mechanism used by this enum.
     */
    EnumResolutionMechanism resolutionMechanism();

    /**
     * Indicates the default value of this policy when unset or cleared.
     */
    int defaultValue();
}
