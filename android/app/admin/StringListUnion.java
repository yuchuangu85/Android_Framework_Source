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

package android.app.admin;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;

/**
 * Class to identify a union resolution mechanism for {@code List<String>} policies, it's used to
 * resolve the enforced policy when being set by multiple admins (see
 * {@link PolicyState#getResolutionMechanism()}).
 *
 * @hide
 */
public final class StringListUnion extends ResolutionMechanism<List<String>> {

    /**
     * Union resolution for policies represented {@code List<String>} which resolves as the union of
     * all lists.
     */
    @NonNull
    public static final StringListUnion STRING_LIST_UNION = new StringListUnion();

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        return o != null && getClass() == o.getClass();
    }
    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public String toString() {
        return "StringListUnion {}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {}

    @NonNull
    public static final Parcelable.Creator<StringListUnion> CREATOR =
            new Parcelable.Creator<StringListUnion>() {
                @Override
                public StringListUnion createFromParcel(Parcel source) {
                    return new StringListUnion();
                }

                @Override
                public StringListUnion[] newArray(int size) {
                    return new StringListUnion[size];
                }
            };
}
