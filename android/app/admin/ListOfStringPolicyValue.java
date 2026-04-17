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

import android.annotation.Nullable;
import android.os.Parcel;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @hide
 */
public class ListOfStringPolicyValue extends PolicyValue<List<String>> {
    @NonNull
    public static final Creator<ListOfStringPolicyValue> CREATOR =
            new Creator<>() {
                @Override
                public ListOfStringPolicyValue createFromParcel(Parcel source) {
                    return new ListOfStringPolicyValue(source);
                }

                @Override
                public ListOfStringPolicyValue[] newArray(int size) {
                    return new ListOfStringPolicyValue[size];
                }
            };

    public ListOfStringPolicyValue(List<String> value) {
        super(value);
    }

    private ListOfStringPolicyValue(Parcel source) {
        super(parcelToList(source));
    }

    private static List<String> parcelToList(Parcel source) {
        List<String> value = new ArrayList<>();
        source.readStringList(value);
        return value;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof ListOfStringPolicyValue other
                && Objects.equals(this.getValue(), other.getValue());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getValue());
    }

    @Override
    public String toString() {
        return "ListOfStringPolicyValue { " + getValue() + " }";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStringList(getValue());
    }
}
