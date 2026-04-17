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

package android.service.notification;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * A bundle dynamically created by the {@link NotificationAssistantService} for
 * {@link Adjustment#KEY_TYPE type adjustments}.
 * @hide
 */
@SuppressWarnings("UserHandleName")
@SystemApi
@FlaggedApi(Flags.FLAG_NM_CONTEXTUAL_DISPLAY)
public final class DynamicBundle implements Parcelable {

    /**
     * @hide
     */
    public static final int DYNAMIC_RANGE_START = 100;
    /**
     * @hide
     */
    public static final int DYNAMIC_RANGE_END = 200;

    private final int mDynamicBundleType;
    private final String mBundleName;

    public DynamicBundle(int dynamicBundleType, @NonNull String bundleName) {
        this.mDynamicBundleType = dynamicBundleType;
        this.mBundleName = bundleName;
    }

    private DynamicBundle(Parcel in) {
        mDynamicBundleType = in.readInt();
        mBundleName = in.readString8();
    }

    public int getDynamicBundleType() {
        return mDynamicBundleType;
    }

    public @NonNull String getBundleName() {
        return mBundleName;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DynamicBundle)) return false;
        DynamicBundle that = (DynamicBundle) o;
        return mDynamicBundleType == that.mDynamicBundleType && Objects.equals(mBundleName,
                that.mBundleName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDynamicBundleType, mBundleName);
    }

    @Override
    public String toString() {
        return "DynamicBundle{" +
                "dynamicBundleType=" + mDynamicBundleType +
                ", bundleName='" + mBundleName + '\'' +
                '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mDynamicBundleType);
        dest.writeString8(mBundleName);
    }

    public static final @NonNull Creator<DynamicBundle> CREATOR =
            new Creator<DynamicBundle>() {
                @Override
                public DynamicBundle createFromParcel(@NonNull Parcel in) {
                    return new DynamicBundle(in);
                }

                @Override
                public DynamicBundle[] newArray(int size) {
                    return new DynamicBundle[size];
                }
            };
}
