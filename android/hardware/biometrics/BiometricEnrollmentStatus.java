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

package android.hardware.biometrics;

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.SystemApi;

import java.util.Objects;

/**
 * This class contains enrollment information of one biometric modality.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_MOVE_FM_API_TO_BM)
public final class BiometricEnrollmentStatus {

    private final @BiometricManager.Authenticators.Types int mStrength;
    private final int mEnrollmentCount;

    /**
     * Constructs a new {@link BiometricEnrollmentStatus} object.
     *
     * @param strength        The biometric strength type, as defined by
     *                        {@link BiometricManager.Authenticators.Types}.
     * @param enrollmentCount The number of biometric currently enrolled.
     */
    public BiometricEnrollmentStatus(@BiometricManager.Authenticators.Types int strength,
            int enrollmentCount) {
        mStrength = strength;
        mEnrollmentCount = enrollmentCount;
    }

    /**
     * Returns the strength of enrolled biometric for the associated modality.
     *
     * @return The strength of enrolled biometric.
     */
    @BiometricManager.Authenticators.Types
    public int getStrength() {
        return mStrength;
    }

    /**
     * Returns the number of enrolled biometric for the associated modality.
     *
     * @return The number of enrolled biometric.
     */
    @IntRange(from = 0)
    public int getEnrollmentCount() {
        return mEnrollmentCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStrength, mEnrollmentCount);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        BiometricEnrollmentStatus other = (BiometricEnrollmentStatus) obj;
        return mStrength == other.mStrength && mEnrollmentCount == other.mEnrollmentCount;
    }
}
