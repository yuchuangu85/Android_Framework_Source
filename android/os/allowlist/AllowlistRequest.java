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
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.allowlist.AllowlistManager.AllowlistId;

import java.util.Objects;
import java.util.TreeSet;

/**
 * A class representing a request to a specific allowlist. A request is identified by two fields:
 * <ul>
 * <li>An allowlist ID. This ID represents the allowlist that will be queried for.</li>
 * <li>A Bundle containing data for querying. Different lists may take different keys as a filter.
 * For example, the App Function allowlist can take a list of signed packages as the value of
 * {@link AllowlistManager#REQUEST_KEY_FILTER_TARGETS}, and the returned target apps will be
 * filtered down to only those specified. If an invalid key is given, or the type for the key is
 * incorrect, a {@link AllowlistManager#RESPONSE_STATUS_ERROR_INVALID_REQUEST} will be returned.
 * </li>
 * </ul>
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_APP_FUNCTION_PERMISSION_V2)
public final class AllowlistRequest implements Parcelable {
    private final int mAllowlistId;
    private final Bundle mData;

    /**
     * Create a new instance of AllowlistRequest
     * @param allowlistId The ID of the allowlist.
     * @param data A Bundle containing data for querying. Keys are defined in
     * {@link AllowlistManager} in the format of {@code REQUEST_DATA_}
     */
    public AllowlistRequest(@AllowlistId int allowlistId, @NonNull Bundle data) {
        mAllowlistId = allowlistId;
        mData = data;
    }

    private AllowlistRequest(Parcel in) {
        mAllowlistId = in.readInt();
        mData = in.readParcelable(Bundle.class.getClassLoader(), Bundle.class);
    }

    /**
     * Get allowlist ID.
     * @return The ID of the allowlist to query.
     */
    public @AllowlistId int getAllowlistId() {
        return mAllowlistId;
    }

    /**
     * Get data for querying.
     * @return A Bundle containing data for querying. Keys are defined in
     * {@link AllowlistManager} in the format of {@code REQUEST_DATA_}
     */
    public @NonNull Bundle getData() {
        return mData;
    }

    public static final @NonNull Creator<AllowlistRequest> CREATOR = new Creator<>() {
        @Override
        public AllowlistRequest createFromParcel(Parcel in) {
            return new AllowlistRequest(in);
        }

        @Override
        public AllowlistRequest[] newArray(int size) {
            return new AllowlistRequest[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mAllowlistId);
        dest.writeParcelable(mData, 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AllowlistRequest that)) {
            return false;
        }

        return mAllowlistId == that.mAllowlistId && bundleEquals(mData, that.mData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAllowlistId, bundleHashCode(mData));
    }

    @Override
    public String toString() {
        return "allowlistId=" + mAllowlistId + ", data=" + (mData == null ? "null"
                : mData.toString());
    }

    private boolean bundleEquals(Bundle a, Bundle b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;

        for (String key : a.keySet()) {
            if (!b.containsKey(key)) return false;

            Object valueOne = a.get(key);
            Object valueTwo = b.get(key);

            if (valueOne instanceof Bundle && valueTwo instanceof Bundle) {
                if (!bundleEquals((Bundle) valueOne, (Bundle) valueTwo)) return false;
            } else {
                if (!Objects.deepEquals(valueOne, valueTwo)) return false;
            }
        }
        return true;
    }

    private int bundleHashCode(Bundle bundle) {
        if (bundle == null) {
            return 0;
        }
        int hash = 0;
        TreeSet<String> treeSet = new TreeSet<>(bundle.keySet());
        for (String key : treeSet) {
            Object value = bundle.get(key);
            hash = 31 * hash + key.hashCode();
            hash = 31 * hash + Objects.hashCode(value);
        }

        return hash;
    }
}
