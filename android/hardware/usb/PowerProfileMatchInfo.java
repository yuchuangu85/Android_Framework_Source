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

package android.hardware.usb;

import static android.hardware.usb.PowerProfileInfo.POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;

import android.annotation.CheckResult;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.hardware.usb.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Describes PowerProfileMatchInfo
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_POWER_PROFILE_REPORTING)
public final class PowerProfileMatchInfo implements Parcelable {
    private final int mPortIndex;
    private final int mPartnerIndex;
    private final int mMinVoltageMv;
    private final int mMaxVoltageMv;
    private final int mMinCurrentMa;
    private final int mMaxCurrentMa;
    private final int mMaxPowerMw;

    /** @hide */
    public PowerProfileMatchInfo(Builder builder) {
        mPortIndex = builder.mPortIndex;
        mPartnerIndex = builder.mPartnerIndex;
        mMinVoltageMv = builder.mMinVoltageMv;
        mMaxVoltageMv = builder.mMaxVoltageMv;
        mMinCurrentMa = builder.mMinCurrentMa;
        mMaxCurrentMa = builder.mMaxCurrentMa;
        mMaxPowerMw = builder.mMaxPowerMw;
    }

    /** @hide */
    public int getPortIndex() {
        return mPortIndex;
    }

    /** @hide */
    public int getPartnerIndex() {
        return mPartnerIndex;
    }

    /** @hide */
    public int getMinVoltageMv() {
        return mMinVoltageMv;
    }

    /** @hide */
    public int getMaxVoltageMv() {
        return mMaxVoltageMv;
    }

    /** @hide */
    public int getMinCurrentMa() {
        return mMinCurrentMa;
    }

    /** @hide */
    public int getMaxCurrentMa() {
        return mMaxCurrentMa;
    }

    /** @hide */
    public int getMaxPowerMw() {
        return mMaxPowerMw;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mPortIndex);
        dest.writeInt(mPartnerIndex);
        dest.writeInt(mMinVoltageMv);
        dest.writeInt(mMaxVoltageMv);
        dest.writeInt(mMinCurrentMa);
        dest.writeInt(mMaxCurrentMa);
        dest.writeInt(mMaxPowerMw);
    }

    public static final @NonNull Parcelable.Creator<PowerProfileMatchInfo> CREATOR =
            new Parcelable.Creator<PowerProfileMatchInfo>() {
        @Override
        public PowerProfileMatchInfo createFromParcel(Parcel in) {
            PowerProfileMatchInfo.Builder builder = new PowerProfileMatchInfo.Builder();
            builder.setPortIndex(in.readInt());
            builder.setPartnerIndex(in.readInt());
            builder.setMinVoltageMv(in.readInt());
            builder.setMaxVoltageMv(in.readInt());
            builder.setMinCurrentMa(in.readInt());
            builder.setMaxCurrentMa(in.readInt());
            builder.setMaxPowerMw(in.readInt());

            return new PowerProfileMatchInfo(builder);
        }

        @Override
        public PowerProfileMatchInfo[] newArray(int size) {
            return new PowerProfileMatchInfo[size];
        }
    };

    @NonNull
    @Override
    public String toString() {
        StringBuilder mString = new StringBuilder("PowerProfileMatchInfo{portIndex=" + mPortIndex
                + ", partnerIndex=" + mPartnerIndex);
        if (mMinVoltageMv != -1) {
            mString.append(", minVoltageMv=" + mMinVoltageMv);
        }
        if (mMaxVoltageMv != -1) {
            mString.append(", maxVoltageMv=" + mMaxVoltageMv);
        }
        if (mMinCurrentMa != -1) {
            mString.append(", minCurrentMa=" + mMinCurrentMa);
        }
        if (mMaxCurrentMa != -1) {
            mString.append(", maxCurrentMa=" + mMaxCurrentMa);
        }
        if (mMaxPowerMw != -1) {
            mString.append(", maxPowerMw=" + mMaxPowerMw);
        }
        mString.append("}");

        return mString.toString();
    }

    /** @hide */
    public static final class Builder {
        private int mPortIndex;
        private int mPartnerIndex;
        private int mMinVoltageMv;
        private int mMaxVoltageMv;
        private int mMinCurrentMa;
        private int mMaxCurrentMa;
        private int mMaxPowerMw;

        public Builder() {
            mPortIndex = -1;
            mPartnerIndex = -1;
            mMinVoltageMv = POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
            mMaxVoltageMv = POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
            mMinCurrentMa = POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
            mMaxCurrentMa = POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
            mMaxPowerMw = POWER_PROFILE_ERROR_FIELD_NOT_SUPPORTED;
        }

        /**
         * Sets the local port index of {@link PowerProfileMatchInfo}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setPortIndex(int idx) {
            mPortIndex = idx;
            return this;
        }

        /**
         * Sets the partner port index of {@link PowerProfileMatchInfo}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setPartnerIndex(int idx) {
            mPartnerIndex = idx;
            return this;
        }

        /**
         * Sets the min voltage of {@link PowerProfileMatchInfo}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setMinVoltageMv(int voltageMv) {
            mMinVoltageMv = voltageMv;
            return this;
        }

        /**
         * Sets the max voltage of {@link PowerProfileMatchInfo}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setMaxVoltageMv(int voltageMv) {
            mMaxVoltageMv = voltageMv;
            return this;
        }

        /**
         * Sets the min current of {@link PowerProfileMatchInfo}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setMinCurrentMa(int currentMa) {
            mMinCurrentMa = currentMa;
            return this;
        }

        /**
         * Sets the max current of {@link PowerProfileMatchInfo}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setMaxCurrentMa(int currentMa) {
            mMaxCurrentMa = currentMa;
            return this;
        }

        /**
         * Sets the max power of {@link PowerProfileMatchInfo}
         *
         * @return Instance of {@link Builder}
         */
        @NonNull
        public Builder setMaxPowerMw(int powerMw) {
            mMaxPowerMw = powerMw;
            return this;
        }

        /**
         * Creates the {@link PowerProfileMatchInfo} object.
         */
        @NonNull
        public PowerProfileMatchInfo build() {
            return new PowerProfileMatchInfo(this);
        }
    }
}
