/*
 * Copyright 2025 The Android Open Source Project
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
package android.media;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import java.util.Objects;

/**
 * A pair of {@link UserHandle} and package name to uniquely identify apps.
 *
 * @hide
 */
public final class AppId implements Parcelable {

    public static final Creator<AppId> CREATOR =
            new Creator<>() {
                @Override
                public AppId createFromParcel(Parcel in) {
                    return new AppId(
                            in.readString8(),
                            in.readTypedObject(UserHandle.CREATOR));
                }

                @Override
                public AppId[] newArray(int size) {
                    return new AppId[size];
                }
            };

    public final String mPackageName;
    public final UserHandle mUserHandle;

    /** Constructor. */
    public AppId(String mPackageName, UserHandle mUserHandle) {
        this.mPackageName = mPackageName;
        this.mUserHandle = mUserHandle;
    }

    // Parcelable implementation.

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString8(mPackageName);
        dest.writeTypedObject(mUserHandle, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    // Object implementation.

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (AppId) obj;
        return Objects.equals(this.mPackageName, that.mPackageName)
                && Objects.equals(this.mUserHandle, that.mUserHandle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPackageName, mUserHandle);
    }

    @Override
    public String toString() {
        return "AppId["
                + "mPackageName='"
                + mPackageName
                + "', "
                + "mUserHandle="
                + mUserHandle
                + ']';
    }
}
