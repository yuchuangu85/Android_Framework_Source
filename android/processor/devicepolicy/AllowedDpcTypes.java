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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Describes the DPC types that are allowed to set this policy.
 */
@Retention(RetentionPolicy.SOURCE)
public @interface AllowedDpcTypes {
    /**
     * Value used indicate that DPCs of the given type are allowed to set the policy.
     */
    public static final int ALLOWED = 1;
    /**
     * Value used indicate that DPCs of the given type are not allowed to set the policy.
     */
    public static final int DISALLOWED = 2;

    /**
     * Only applicable to `affiliatedFullUserProfileOwner`. Indicates that the value for this field
     * will be inherited from `unaffiliatedFullUserProfileOwner`. As the default, it should not be
     * specified explicitly.
     */
    public static final int SAME_AS_UNAFFILIATED = 3;

    public int deviceOwner();

    public int managedProfileOwnerOfOrganizationOwnedDevice();

    public int managedProfileOwnerOfPersonalOwnedDevice();

    public int unaffiliatedFullUserProfileOwner();

    // Niche DPC types. You most likely don't need to use these.
    public int financedDeviceOwner() default DISALLOWED;

    public int profileOwnerOnUser0() default DISALLOWED;

    public int affiliatedFullUserProfileOwner() default SAME_AS_UNAFFILIATED;
}
