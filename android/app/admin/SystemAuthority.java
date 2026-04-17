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

package android.app.admin;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;

import java.util.Objects;

/**
 * Class used to identify the authority of the {@link EnforcingAdmin} for system.
 *
 * @hide
 */
public final class SystemAuthority extends Authority {

    private final String mSystemEntity;

    /**
     * Creates an authority that represents a system entity.
     *
     * @param systemEntity String that uniquely identifies the system entity. Package name is a
     *                     standard choice, if you need finer granularity, include a class name or
     *                     some other suffix.
     */
    public SystemAuthority(@NonNull String systemEntity) {
        Objects.requireNonNull(systemEntity, "systemEntity must not be null");
        mSystemEntity = systemEntity;
    }

    private SystemAuthority(@NonNull Parcel source) {
        Objects.requireNonNull(source);
        mSystemEntity = Objects.requireNonNull(source.readString8());
    }

    @NonNull
    public String getSystemEntity() {
        return mSystemEntity;
    }

    @Override
    public String toString() {
        return "SystemAuthority { mSystemEntity=" + mSystemEntity + " }";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SystemAuthority other = (SystemAuthority) o;
        return Objects.equals(mSystemEntity, other.mSystemEntity);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mSystemEntity);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mSystemEntity);
    }

    @NonNull
    public static final Creator<SystemAuthority> CREATOR =
            new Creator<SystemAuthority>() {
                @Override
                public SystemAuthority createFromParcel(Parcel source) {
                    return new SystemAuthority(source);
                }

                @Override
                public SystemAuthority[] newArray(int size) {
                    return new SystemAuthority[size];
                }
            };
}
