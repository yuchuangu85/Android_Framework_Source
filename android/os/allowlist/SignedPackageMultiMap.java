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

package android.os.allowlist;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.appfunctions.flags.Flags;
import android.content.pm.SignedPackage;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A Parcelable that is a map of {@link SignedPackage} to a list of {@link SignedPackage} in
 * allowlist. It can be used in {@link AllowlistManager#RESPONSE_KEY_ALLOWED_PACKAGE_MULTI_MAP}
 * response to represent agent to target mapping.
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_APP_FUNCTION_PERMISSION_V2)
public final class SignedPackageMultiMap implements Parcelable {

    private final Map<SignedPackage, List<SignedPackage>> mMap;

    /**
     * Constructor of SignedPackageMultiMap
     * @param map A map of SignedPackage to a list of SignedPackages
     */
    public SignedPackageMultiMap(
            @NonNull Map<SignedPackage, List<SignedPackage>> map) {
        this.mMap = map;
    }

    private SignedPackageMultiMap(Parcel in) {
        int size = in.readInt();
        mMap = new ArrayMap<>(size);
        for (int i = 0; i < size; i++) {
            SignedPackage key = in.readParcelable(SignedPackage.class.getClassLoader(),
                    SignedPackage.class);
            ArrayList<SignedPackage> value = new ArrayList<>();
            in.readTypedList(value, SignedPackage.CREATOR);
            mMap.put(key, value);
        }
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mMap.size());
        for (Map.Entry<SignedPackage, List<SignedPackage>> entry : mMap.entrySet()) {
            dest.writeParcelable(entry.getKey(), flags);
            dest.writeTypedList(entry.getValue());
        }
    }

    /**
     * Get the SignedPackage map
     * @return A map of SignedPackage to a list of SignedPackages
     */
    @NonNull
    public Map<SignedPackage, List<SignedPackage>> getMap() {
        return mMap;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SignedPackageMultiMap that = (SignedPackageMultiMap) o;

        return Objects.equals(mMap, that.mMap);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mMap);
    }

    public static final @NonNull Creator<SignedPackageMultiMap> CREATOR = new Creator<>() {
        @Override
        public SignedPackageMultiMap createFromParcel(Parcel in) {
            return new SignedPackageMultiMap(in);
        }

        @Override
        public SignedPackageMultiMap[] newArray(int size) {
            return new SignedPackageMultiMap[size];
        }
    };
}
