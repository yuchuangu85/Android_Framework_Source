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

package android.ranging.uwb;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.ranging.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Represents the {@link android.ranging.RangingManager.RangingTechnology#UWB} specific data.
 * This is generally used by algorithms for fine tuning ranging data.
 *
 */
@FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
public final class UwbSpecificData implements Parcelable {

    /**
     * The interface for Line of sight values
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            LOS,
            NLOS,
            LOS_UNDETERMINED})
    public @interface LineOfSight {
    }

    /**
     * If measurement was in line of sight.
     */
    public static final int LOS = 0;

    /**
     * If measurement was not in line of sight.
     */
    public static final int NLOS = 1;

    /**
     * Unable to determine whether the measurement was in line of sight or not.
     */
    public static final int LOS_UNDETERMINED = 0xFF;
    private final @LineOfSight int mNonLineOfSight;

    private UwbSpecificData(Builder builder) {
        mNonLineOfSight = builder.mNonLineOfSight;
    }

    private UwbSpecificData(Parcel in) {
        mNonLineOfSight = in.readInt();
    }

    @NonNull
    public static final Creator<UwbSpecificData> CREATOR =
            new Creator<>() {
                @Override
                public UwbSpecificData createFromParcel(Parcel in) {
                    return new UwbSpecificData(in);
                }

                @Override
                public UwbSpecificData[] newArray(int size) {
                    return new UwbSpecificData[size];
                }
            };

    /**
     * Gets the non-line-of-sight value.
     *
     * @return the non-line-of-sight value
     * @throws IllegalStateException if non-line-of-sight is not set.
     */
    @LineOfSight
    public int getNonLineOfSight() {
        return mNonLineOfSight;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mNonLineOfSight);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UwbSpecificData)) return false;
        UwbSpecificData that = (UwbSpecificData) o;
        return mNonLineOfSight == that.mNonLineOfSight;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNonLineOfSight);
    }

    @Override
    public String toString() {
        return "UwbSpecificData: "
                + "NonLineOfSight=" + mNonLineOfSight;
    }

    /**
     * Builder class for creating instances of {@link UwbSpecificData}.
     *
     * @hide
     */
    public static final class Builder {
        private @LineOfSight int mNonLineOfSight = LOS_UNDETERMINED;

        /**
         * Sets non-line-of-sight.
         *
         * @param nonLineOfSight the non-line-of-sight
         * @return the Builder instance
         */
        @NonNull
        public Builder setNonLineOfSight(@LineOfSight int nonLineOfSight) {
            mNonLineOfSight = nonLineOfSight;
            return this;
        }

        /**
         * Build additional ranging data.
         *
         * @return the additional ranging data
         */
        @NonNull
        public UwbSpecificData build() {
            return new UwbSpecificData(this);
        }
    }
}
