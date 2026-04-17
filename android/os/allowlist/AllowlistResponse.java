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
import android.os.allowlist.AllowlistManager.ResponseStatus;

import java.util.Objects;
import java.util.TreeSet;

/**
 * A class representing a response to a specific allowlist query. A response consists of two fields:
 * <ul>
 * <li>Response status. A status code is one of {@link AllowlistManager.ResponseStatus}. It
 * indicates whether the query request was successful.</li>
 * <li>A Bundle containing allowlist query response. The keys in the Bundle are dependent on the
 * {@link AllowlistRequest}.</li>
 * </ul>
 * For example, the App Function allowlist can take a list of signed packages as the value of
 * {@link AllowlistManager#REQUEST_KEY_FILTER_TARGETS} in the request, the returned targets after
 * filtering will be the value of {@link AllowlistManager#RESPONSE_KEY_ALLOWED_PACKAGE_MULTI_MAP}
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_APP_FUNCTION_PERMISSION_V2)
public final class AllowlistResponse implements Parcelable {
    private final @ResponseStatus int mStatus;
    private final @NonNull Bundle mData;

    /**
     * Create a new instance of AllowlistResponse
     * @param status A status code.
     * @param data  A bundle containing allowlist query response.
     * {@link AllowlistManager} in the format of {@code RESPONSE_DATA_}
     */
    public AllowlistResponse(@ResponseStatus int status, @NonNull Bundle data) {
        this.mStatus = status;
        this.mData = data;
    }

    private AllowlistResponse(Parcel in) {
        mStatus = in.readInt();
        mData = Objects.requireNonNull(
                in.readParcelable(Bundle.class.getClassLoader(), Bundle.class));
    }

    /**
     * Get response status.
     * @return A status code.
     */
    public @ResponseStatus int getStatus() {
        return mStatus;
    }

    /**
     * Get allowlist query response.
     * @return A Bundle containing query response. Keys are defined in
     * {@link AllowlistManager} in the format of {@code RESPONSE_DATA_}
     */
    public @NonNull Bundle getData() {
        return mData;
    }

    public static final @NonNull Creator<AllowlistResponse> CREATOR = new Creator<>() {
        @Override
        public AllowlistResponse createFromParcel(Parcel in) {
            return new AllowlistResponse(in);
        }

        @Override
        public AllowlistResponse[] newArray(int size) {
            return new AllowlistResponse[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mStatus);
        dest.writeParcelable(mData, 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AllowlistResponse that)) {
            return false;
        }

        return mStatus == that.mStatus && bundleEquals(mData, that.mData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStatus, bundleHashCode(mData));
    }

    @Override
    public String toString() {
        return "status=" + mStatus + ", data=" + (mData == null ? "null" : mData.toString());
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
