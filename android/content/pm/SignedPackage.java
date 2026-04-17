/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.content.pm;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.appfunctions.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;

/**
 * A data class representing a package and an optional SHA-256 hash of its signing certificate.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_APP_FUNCTION_PERMISSION_V2)
public final class SignedPackage implements Parcelable {

    @NonNull
    private final String mPackageName;
    @Nullable
    private final byte[] mCertificateDigest;

    /**
     * Create a new instance of SignedPackage
     * @param packageName The name of the package
     * @param certificateDigest The sha-256 hash of the package's signing certificate, or null if
     *                          none
     */
    public SignedPackage(@NonNull String packageName, @Nullable byte[] certificateDigest) {
        mPackageName = packageName;
        mCertificateDigest = certificateDigest;
    }

    /** @hide */
    public SignedPackage(@NonNull Parcel data) {
        mPackageName = Objects.requireNonNull(data.readString8());
        mCertificateDigest = data.createByteArray();
    }

    /** @hide */
    public SignedPackage(@NonNull SignedPackageParcel data) {
        mPackageName = data.packageName;
        mCertificateDigest = data.certificateDigest;
    }

    /** @hide */
    public SignedPackageParcel toSignedPackageParcel() {
        SignedPackageParcel parcel = new SignedPackageParcel();
        parcel.packageName = mPackageName;
        parcel.certificateDigest = mCertificateDigest;
        return parcel;
    }


    public static final @NonNull Creator<SignedPackage> CREATOR = new Creator<>() {
        @Override
        public SignedPackage createFromParcel(Parcel in) {
            return new SignedPackage(in);
        }

        @Override
        public SignedPackage[] newArray(int size) {
            return new SignedPackage[size];
        }
    };

    public @NonNull String getPackageName() {
        return mPackageName;
    }

    /** @return the certificate digest. If none was provided, the method will throw an Exception */
    public @NonNull byte[] getCertificateDigest() {
        return Objects.requireNonNull(mCertificateDigest);
    }

    /** @return true if this SignedPackage has a certificate attached, false otherwise */
    public boolean hasCertificateDigest() {
        return mCertificateDigest != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SignedPackage that)) return false;
        return mPackageName.equals(that.mPackageName) && Arrays.equals(
                mCertificateDigest, that.mCertificateDigest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPackageName, Arrays.hashCode(mCertificateDigest));
    }

    @Override
    public String toString() {
        return "SignedPackage{"
                + "packageName='" + mPackageName
                + ", certificateDigest=" + Arrays.toString(mCertificateDigest) + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@androidx.annotation.NonNull Parcel dest, int flags) {
        dest.writeString8(mPackageName);
        dest.writeByteArray(mCertificateDigest);
    }
}
