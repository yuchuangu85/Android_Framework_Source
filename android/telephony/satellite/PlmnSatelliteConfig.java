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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.NetworkRegistrationInfo;

import com.android.internal.telephony.flags.Flags;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * PlmnSatelliteConfig contains per PLMN satellite configuration information.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_SATELLITE_26Q2_APIS)
public final class PlmnSatelliteConfig implements Parcelable {

    /** The set of services supported by the PLMN. */
    @NonNull @NetworkRegistrationInfo.ServiceType private Set<Integer> mSupportedServices;

    /** @hide */
    public PlmnSatelliteConfig(
            @NonNull @NetworkRegistrationInfo.ServiceType Set<Integer> supportedServices) {
        mSupportedServices = supportedServices;
    }

    private PlmnSatelliteConfig(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        if (mSupportedServices != null && !mSupportedServices.isEmpty()) {
            out.writeInt(mSupportedServices.size());
            for (int service : mSupportedServices) {
                out.writeInt(service);
            }
        } else {
            out.writeInt(0);
        }
    }

    @NonNull
    public static final Creator<PlmnSatelliteConfig> CREATOR =
            new Creator<>() {
                @Override
                public PlmnSatelliteConfig createFromParcel(Parcel in) {
                    return new PlmnSatelliteConfig(in);
                }

                @Override
                public PlmnSatelliteConfig[] newArray(int size) {
                    return new PlmnSatelliteConfig[size];
                }
            };

    @Override
    @NonNull
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("SupportedServices:");
        if (mSupportedServices != null && !mSupportedServices.isEmpty()) {
            for (int service : mSupportedServices) {
                sb.append(service);
                sb.append(",");
            }
        } else {
            sb.append("none,");
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlmnSatelliteConfig that = (PlmnSatelliteConfig) o;
        return Objects.equals(mSupportedServices, that.mSupportedServices);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSupportedServices);
    }

    /** Returns the set of services supported by the PLMN. */
    @NonNull
    @NetworkRegistrationInfo.ServiceType
    public Set<Integer> getSupportedServices() {
        return mSupportedServices;
    }

    private void readFromParcel(Parcel in) {
        mSupportedServices = new HashSet<>();
        int numSupportedServices = in.readInt();
        if (numSupportedServices > 0) {
            for (int i = 0; i < numSupportedServices; i++) {
                mSupportedServices.add(in.readInt());
            }
        }
    }
}
