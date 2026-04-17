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

package android.health.connect;

import static com.android.healthfitness.flags.Flags.FLAG_DEVICE_DATA_PROVIDERS_API;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Response containing the list of {@link DeviceDataSource}.
 *
 * @see HealthConnectManager#getDeviceDataSources
 */
@FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
public final class GetDeviceDataSourcesResponse implements Parcelable {
    @NonNull private final List<DeviceDataSource> mDataSources;

    /**
     * @param dataSources The list of {@link DeviceDataSource}.
     */
    public GetDeviceDataSourcesResponse(@NonNull List<DeviceDataSource> dataSources) {
        Objects.requireNonNull(dataSources);
        mDataSources = List.copyOf(dataSources);
    }

    /** Returns the list of {@link DeviceDataSource}. */
    @NonNull
    public List<DeviceDataSource> getDeviceDataSources() {
        return mDataSources;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mDataSources);
    }

    @NonNull
    public static final Creator<GetDeviceDataSourcesResponse> CREATOR =
            new Creator<>() {
                @Override
                public GetDeviceDataSourcesResponse createFromParcel(Parcel in) {
                    return new GetDeviceDataSourcesResponse(in);
                }

                @Override
                public GetDeviceDataSourcesResponse[] newArray(int size) {
                    return new GetDeviceDataSourcesResponse[size];
                }
            };

    private GetDeviceDataSourcesResponse(Parcel in) {
        List<DeviceDataSource> dataSources = new ArrayList<>();
        in.readTypedList(dataSources, DeviceDataSource.CREATOR);
        mDataSources = List.copyOf(dataSources);
    }
}
