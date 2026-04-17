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
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * This class contains enrollment information. It keeps track of the modality type (e.g.
 * fingerprint, face) and corresponding {@link BiometricEnrollmentStatus}. This is for
 * internal use in {@link com.android.server.biometrics.AuthService}, so it has to be parcelable.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_MOVE_FM_API_TO_BM)
public final class BiometricEnrollmentStatusInternal implements Parcelable {
    @BiometricManager.BiometricModality
    private final int mModality;
    private final BiometricEnrollmentStatus mStatus;

    public BiometricEnrollmentStatusInternal(
            @BiometricManager.BiometricModality int modality, BiometricEnrollmentStatus status) {
        mModality = modality;
        mStatus = status;
    }

    /**
     * Returns the modality associated with this enrollment status.
     *
     * @return The int value representing the biometric sensor type, e.g.
     * {@link BiometricManager#TYPE_FACE} or
     * {@link BiometricManager#TYPE_FINGERPRINT}.
     */
    @BiometricManager.BiometricModality
    public int getModality() {
        return mModality;
    }

    /**
     * Returns the {@link BiometricEnrollmentStatus} for the associated modality.
     */
    public BiometricEnrollmentStatus getStatus() {
        return mStatus;
    }

    private BiometricEnrollmentStatusInternal(Parcel in) {
        this(in.readInt(), new BiometricEnrollmentStatus(in.readInt(), in.readInt()));
    }

    @NonNull
    public static final Creator<BiometricEnrollmentStatusInternal> CREATOR = new Creator<>() {
        @Override
        public BiometricEnrollmentStatusInternal createFromParcel(Parcel in) {
            return new BiometricEnrollmentStatusInternal(in);
        }

        @Override
        public BiometricEnrollmentStatusInternal[] newArray(int size) {
            return new BiometricEnrollmentStatusInternal[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mModality);
        dest.writeInt(mStatus.getStrength());
        dest.writeInt(mStatus.getEnrollmentCount());
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
        return "Modality: " + modality + ", Strength: " + mStatus.getStrength()
                + ", Enrolled Count: " + mStatus.getEnrollmentCount();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mModality, mStatus.getStrength(), mStatus.getEnrollmentCount());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        BiometricEnrollmentStatusInternal other = (BiometricEnrollmentStatusInternal) obj;
        return mModality == other.mModality
                && mStatus.getStrength() == other.mStatus.getStrength()
                && mStatus.getEnrollmentCount() == other.mStatus.getEnrollmentCount();
    }
}
