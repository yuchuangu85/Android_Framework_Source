/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.telephony.satellite;

import android.annotation.FlaggedApi;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.android.internal.telephony.flags.Flags;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SatelliteAccessConfiguration is used to store satellite access configuration
 * that will be applied to the satellite communication at the corresponding region.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
public final class SatelliteAccessConfiguration implements Parcelable {
    /**
     * The list of satellites available at the current location.
     */
    @NonNull
    private List<SatelliteInfo> mSatelliteInfoList;

    /**
     * The list of tag IDs associated with the current location
     */
    @NonNull
    private List<Integer> mTagIdList;

    /**
     * The list of carrier IDs associated with the current location
     */
    @NonNull
    private List<Integer> mCarrierIdList;

    /**
     * Constructor for {@link SatelliteAccessConfiguration}.
     *
     * @param satelliteInfos The list of {@link SatelliteInfo} objects representing the satellites
     *                       accessible with this configuration.
     * @param tagIdList      The list of tag IDs associated with this configuration.
     * @hide
     */
    public SatelliteAccessConfiguration(@NonNull List<SatelliteInfo> satelliteInfos,
            @NonNull List<Integer> tagIdList) {
        this(satelliteInfos, tagIdList, new ArrayList<>());
    }

    /**
     * Constructor for {@link SatelliteAccessConfiguration}.
     *
     * @param satelliteInfos The list of {@link SatelliteInfo} objects representing the satellites
     *                       accessible with this configuration.
     * @param tagIdList      The list of tag IDs associated with this configuration.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_SATELLITE_26Q2_APIS)
    public SatelliteAccessConfiguration(@NonNull List<SatelliteInfo> satelliteInfos,
            @NonNull List<Integer> tagIdList, @NonNull List<Integer> carrierIdList) {
        mSatelliteInfoList = satelliteInfos;
        mTagIdList = tagIdList;
        mCarrierIdList = carrierIdList;
    }

    /**
     * Constructor for {@link SatelliteAccessConfiguration}.
     * @param in parcel used to create {@link SatelliteAccessConfiguration} object
     * @hide
     */
    public SatelliteAccessConfiguration(Parcel in) {
        mSatelliteInfoList = in.createTypedArrayList(SatelliteInfo.CREATOR);
        mTagIdList = new ArrayList<>();
        in.readList(mTagIdList, Integer.class.getClassLoader(), Integer.class);
        mCarrierIdList = new ArrayList<>();
        in.readList(mCarrierIdList, Integer.class.getClassLoader(), Integer.class);
    }

    @NonNull
    public static final Creator<SatelliteAccessConfiguration> CREATOR =
            new Creator<SatelliteAccessConfiguration>() {
                @Override
                public SatelliteAccessConfiguration createFromParcel(Parcel in) {
                    return new SatelliteAccessConfiguration(in);
                }

                @Override
                public SatelliteAccessConfiguration[] newArray(int size) {
                    return new SatelliteAccessConfiguration[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @param dest  The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     *              May be 0 or {@link #PARCELABLE_WRITE_RETURN_VALUE}.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mSatelliteInfoList);
        dest.writeList(mTagIdList);
        dest.writeList(mCarrierIdList);
    }

    /**
     * Returns a list of {@link SatelliteInfo} objects representing the satellites
     * associated with this object.
     *
     * @return The list of {@link SatelliteInfo} objects.
     */
    @NonNull
    public List<SatelliteInfo> getSatelliteInfos() {
        return mSatelliteInfoList;
    }

    /**
     * Returns a list of tag IDs associated with this object.
     *
     * @return The list of tag IDs.
     */
    @NonNull
    public List<Integer> getTagIds() {
        return mTagIdList;
    }

    /**
     * Returns a list of carrier IDs associated with this object.
     *
     * @return The list of carrier IDs.
     * @hide
     */
    @NonNull
    @FlaggedApi(Flags.FLAG_SATELLITE_26Q2_APIS)
    public List<Integer> getCarrierIds() {
        return mCarrierIdList;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SatelliteAccessConfiguration that)) return false;

        return mSatelliteInfoList.equals(that.mSatelliteInfoList)
                && Objects.equals(mTagIdList, that.mTagIdList)
                && Objects.equals(mCarrierIdList, that.mCarrierIdList);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mSatelliteInfoList);
        result = 31 * result + Objects.hashCode(mTagIdList)
                + Objects.hashCode(mCarrierIdList);
        return result;
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SatelliteAccessConfiguration{");
        sb.append("mSatelliteInfoList=").append(mSatelliteInfoList);
        sb.append(", mTagIds=").append(mTagIdList);
        sb.append(", mCarrierIds=").append(mCarrierIdList);
        sb.append('}');
        return sb.toString();
    }
}
