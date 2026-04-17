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

package android.ranging.wifi.rtt;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.ranging.RangingCapabilities.TechnologyCapabilities;
import android.ranging.RangingManager;

import com.android.ranging.flags.Flags;

/**
 * Represents the capabilities of the WiFi 802.11MC Round Trip Time (STA-AP ranging)
 * ranging.
 * @hide
 */
@FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_25Q4)
public final class RttStationRangingCapabilities implements Parcelable, TechnologyCapabilities {
    private final int mNumSupportedBands;
    private final int mSupportedSecurity;

    private RttStationRangingCapabilities(Builder builder) {
        mNumSupportedBands = builder.mNumSupportedBands;
        mSupportedSecurity = builder.mSupportedSecurity;
    }

    private RttStationRangingCapabilities(Parcel in) {
        mNumSupportedBands = in.readInt();
        mSupportedSecurity = in.readInt();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mNumSupportedBands);
        dest.writeInt(mSupportedSecurity);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<RttStationRangingCapabilities> CREATOR =
            new Creator<RttStationRangingCapabilities>() {
                @Override
                public RttStationRangingCapabilities createFromParcel(Parcel in) {
                    return new RttStationRangingCapabilities(in);
                }

                @Override
                public RttStationRangingCapabilities[] newArray(int size) {
                    return new RttStationRangingCapabilities[size];
                }
            };

    /**
     * @hide
     */
    @Override
    public @RangingManager.RangingTechnology int getTechnology() {
        return RangingManager.WIFI_STA_RTT;
    }

    /**
     * @hide
     */
    public int getNumSupportedBands() {
        return mNumSupportedBands;
    }

    /**
     * @hide
     */
    public int getSupportedSecurity() {
        return mSupportedSecurity;
    }

    /**
     * Builder for {@link RttStationRangingCapabilities}
     *
     * @hide
     */
    public static class Builder {
        private int mNumSupportedBands = 2;
        private int mSupportedSecurity = 0;

        @NonNull
        public Builder setNumSupportedBands(int numBands) {
            mNumSupportedBands = numBands;
            return this;
        }

        @NonNull
        public Builder setSupportedSecurity(int security) {
            mSupportedSecurity = security;
            return this;
        }

        /**
         * Builds and returns an {@link RttStationRangingCapabilities} instance configured with
         * the provided settings.
         *
         * @return a new {@link RttStationRangingCapabilities} instance.
         */
        @NonNull
        public RttStationRangingCapabilities build() {
            return new RttStationRangingCapabilities(this);
        }
    }

    @Override
    public String toString() {
        return "RttStationRangingCapabilities{ "
                + "mNumSupportedBands="
                + mNumSupportedBands
                + "mSupportedSecurity="
                + mSupportedSecurity
                + " }";
    }

}
