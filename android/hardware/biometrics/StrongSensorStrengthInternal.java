/*
 * Copyright (C) 2026 The Android Open Source Project
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
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * This class keeps track of the biometric modality (e.g., {@link BiometricManager#TYPE_FACE} for
 * face or {@link BiometricManager#TYPE_FINGERPRINT} for fingerprint) and its corresponding
 * sensor security strength (e.g., {@link BiometricManager.Authenticators#BIOMETRIC_STRONG} for
 * Class-3 or {@link BiometricManager.Authenticators#LESS_THAN_STRONG} for unknown/unexposed
 * cases).
 *
 * <p>This is intended for internal use in {@link com.android.server.biometrics.AuthService}, so
 * it has to be parcelable.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GET_BIOMETRIC_SENSOR_STRENGTHS)
public final class StrongSensorStrengthInternal implements Parcelable {
    @BiometricManager.BiometricModality
    private final int mModality;

    @BiometricManager.Authenticators.StrongTypes
    private final int mStrength;

    public StrongSensorStrengthInternal(@BiometricManager.BiometricModality int modality,
            @BiometricManager.Authenticators.StrongTypes int strength) {
        mModality = modality;
        mStrength = strength;
    }

    /**
     * Returns the biometric modality for the associated sensor security strength.
     *
     * @return The int value representing the biometric modality, e.g.,
     * {@link BiometricManager#TYPE_FACE} for face or {@link BiometricManager#TYPE_FINGERPRINT} for
     * fingerprint.
     */
    @BiometricManager.BiometricModality
    public int getModality() {
        return mModality;
    }

    /**
     * Returns the sensor security strength for the associated biometric modality.
     *
     * @return The int value representing the sensor security strength, e.g.,
     * {@link BiometricManager.Authenticators#BIOMETRIC_STRONG} for Class-3 or
     * {@link BiometricManager.Authenticators#LESS_THAN_STRONG} for unknown/unexposed cases.
     */
    @BiometricManager.Authenticators.StrongTypes
    public int getStrength() {
        return mStrength;
    }

    private StrongSensorStrengthInternal(Parcel in) {
        this(in.readInt(), in.readInt());
    }

    @NonNull
    public static final Creator<StrongSensorStrengthInternal> CREATOR = new Creator<>() {
        @Override
        public StrongSensorStrengthInternal createFromParcel(Parcel in) {
            return new StrongSensorStrengthInternal(in);
        }

        @Override
        public StrongSensorStrengthInternal[] newArray(int size) {
            return new StrongSensorStrengthInternal[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mModality);
        dest.writeInt(mStrength);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        String modality = "";
        if (mModality == BiometricManager.TYPE_FINGERPRINT) {
            modality = "Fingerprint";
        } else if (mModality == BiometricManager.TYPE_FACE) {
            modality = "Face";
        }
        return "Modality: " + modality + ", Strength: " + mStrength;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mModality, mStrength);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        StrongSensorStrengthInternal other = (StrongSensorStrengthInternal) obj;
        return mModality == other.mModality && mStrength == other.mStrength;
    }
}
