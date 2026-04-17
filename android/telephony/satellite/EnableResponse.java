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

package android.telephony.satellite;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.FlaggedApi;

import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.flags.Flags;

import java.util.Arrays;

/**
 * EnableResponse is used to store the result of the satellite enablement request
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_SATELLITE_UPSELL)
public final class EnableResponse implements Parcelable {
    /**
     * Whether satellite is enabled.
     */
    private boolean mIsEnabled;

    /**
     * Whether satellite is enabled for emergency mode.
     *
     * Note that emergency mode is supported only for {@link #CARRIER_ROAMING_NTN_CONNECT_MANUAL}
     * connect type.
     */
    private boolean mIsEmergencyMode;

    /**
     * Whether satellite is enabled in demo mode.
     *
     * Note that demo mode is supported only for {@link #CARRIER_ROAMING_NTN_CONNECT_MANUAL}
     * connect type.
     */
    private boolean mIsDemoMode;

    /**
     * The reasons for satellite enablement request.
     */
    @NonNull
    @SatelliteManager.SatelliteEnablementRequestReason
    private int[] mSatelliteEnablementRequestReasons;

    /**
     * Constructor for {@link EnableResponse}.
     *
     * @param isEnabled Whether satellite is enabled.
     * @param isEmergencyMode Whether satellite is enabled for emergency mode.
     * @param isDemoMode Whether satellite is enabled in demo mode.
     * @param requestReasons The reasons for satellite enablement request.
     *
     * @hide
     */
    public EnableResponse(boolean isEnabled, boolean isEmergencyMode,
            boolean isDemoMode, @NonNull @SatelliteManager.SatelliteEnablementRequestReason
            int[] requestReasons) {
        mIsEnabled = isEnabled;
        mIsEmergencyMode = isEmergencyMode;
        mIsDemoMode = isDemoMode;
        mSatelliteEnablementRequestReasons = requestReasons;
    }

    /**
     * Get whether satellite is enabled.
     *
     * @return Whether satellite is enabled.
     */
    public boolean isEnabled() {
        return mIsEnabled;
    }

    /**
     * Get whether satellite is enabled for emergency mode.
     *
     * @return Whether satellite is enabled for emergency mode.
     */
    public boolean isEmergencyMode() {
        return mIsEmergencyMode;
    }

    /**
     * Get whether satellite is enabled in demo mode.
     *
     * @return Whether satellite is enabled in demo mode.
     */
    public boolean isDemoMode() {
        return mIsDemoMode;
    }

    /**
     * Get the reasons for satellite enablement request.
     *
     * @return The reasons for satellite enablement request.
     */
    @NonNull
    @SatelliteManager.SatelliteEnablementRequestReason
    public int[] getSatelliteEnablementRequestReasons() {
        return mSatelliteEnablementRequestReasons;
    }

    @Override
    public String toString() {
        return "EnableResponse{"
                + ", mIsEnabled=" + mIsEnabled
                + ", mIsEmergencyMode=" + mIsEmergencyMode
                + ", mIsDemoMode=" + mIsDemoMode
                + ", mSatelliteEnablementRequestReasons="
                + Arrays.toString(mSatelliteEnablementRequestReasons)
                + '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mIsEnabled);
        dest.writeBoolean(mIsEmergencyMode);
        dest.writeBoolean(mIsDemoMode);
        dest.writeIntArray(mSatelliteEnablementRequestReasons);
    }

    private EnableResponse(Parcel in) {
        mIsEnabled = in.readBoolean();
        mIsEmergencyMode = in.readBoolean();
        mIsDemoMode = in.readBoolean();
        mSatelliteEnablementRequestReasons = in.createIntArray();
    }

    @NonNull
    public static final Creator<EnableResponse> CREATOR =
            new Creator<EnableResponse>() {
                @Override
                public EnableResponse createFromParcel(Parcel in) {
                    return new EnableResponse(in);
                }

                @Override
                public EnableResponse[] newArray(int size) {
                    return new EnableResponse[size];
                }
            };
}
