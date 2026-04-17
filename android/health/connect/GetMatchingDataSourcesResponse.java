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

import static java.util.Objects.hash;
import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.health.connect.internal.PackageNameMasker;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.healthfitness.flags.AconfigFlagHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Represents a response containing the matching data sources (applications and devices) for a given
 * set of record types.
 *
 * @hide
 */
public final class GetMatchingDataSourcesResponse
        implements Parcelable, PackageNameMasker<GetMatchingDataSourcesResponse> {
    private final Map<String, Set<String>> mMatchingApps;
    // TODO (b/466997475) return Set<DeviceDataSourceInfo> instead
    private final Map<String, Set<String>> mMatchingDevices;

    /**
     * Creates a response containing the map of matching apps. The {@link #hasMatchingApps()}
     * property is derived from whether the map is empty.
     *
     * @param matchingApps The map of matching apps to their matching permissions.
     * @deprecated Use {@link #GetMatchingDataSourcesResponse(Map, Map)} instead.
     */
    public GetMatchingDataSourcesResponse(@NonNull Map<String, Set<String>> matchingApps) {
        this(matchingApps, Map.of());
    }

    /**
     * Creates a response containing the map of matching data sources. The {@link
     * #hasMatchingDataSources()} property is derived from whether the maps are empty.
     *
     * @param matchingApps The map of matching apps to their matching permissions.
     * @param matchingDevices The map of matching devices to their matching permissions.
     */
    public GetMatchingDataSourcesResponse(
            @NonNull Map<String, Set<String>> matchingApps,
            @NonNull Map<String, Set<String>> matchingDevices) {
        mMatchingApps = Map.copyOf(requireNonNull(matchingApps));
        if (AconfigFlagHelper.isDeviceDataProvidersEnabled()) {
            mMatchingDevices = Map.copyOf(requireNonNull(matchingDevices));
        } else {
            mMatchingDevices = Map.of();
        }
    }

    /**
     * Private constructor to reconstruct a {@link GetMatchingDataSourcesResponse} from a {@link
     * Parcel}.
     *
     * @param in The Parcel from which to read the object data.
     */
    private GetMatchingDataSourcesResponse(@NonNull Parcel in) {
        ClassLoader classLoader = getClass().getClassLoader();

        Bundle appsBundle = requireNonNull(in).readBundle(classLoader);
        mMatchingApps = parseMapFromBundle(appsBundle);

        Bundle devicesBundle = in.readBundle(classLoader);
        mMatchingDevices = parseMapFromBundle(devicesBundle);
    }

    @NonNull
    public static final Creator<GetMatchingDataSourcesResponse> CREATOR =
            new Creator<>() {
                @Override
                public GetMatchingDataSourcesResponse createFromParcel(Parcel in) {
                    return new GetMatchingDataSourcesResponse(in);
                }

                @Override
                public GetMatchingDataSourcesResponse[] newArray(int size) {
                    return new GetMatchingDataSourcesResponse[size];
                }
            };

    /** Returns whether there are matching apps in the response. */
    public boolean hasMatchingApps() {
        return !mMatchingApps.isEmpty();
    }

    /** Returns whether there are matching devices in the response. */
    public boolean hasMatchingDevices() {
        return !mMatchingDevices.isEmpty();
    }

    /** Returns whether there are matching data sources in the response. */
    public boolean hasMatchingDataSources() {
        return !(mMatchingApps.isEmpty() && mMatchingDevices.isEmpty());
    }

    @NonNull
    public Map<String, Set<String>> getMatchingApps() {
        return mMatchingApps;
    }

    @NonNull
    public Map<String, Set<String>> getMatchingDevices() {
        return mMatchingDevices;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBundle(mapToBundle(mMatchingApps));
        dest.writeBundle(mapToBundle(mMatchingDevices));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GetMatchingDataSourcesResponse that)) return false;
        return Objects.equals(mMatchingApps, that.mMatchingApps)
                && Objects.equals(mMatchingDevices, that.mMatchingDevices);
    }

    @Override
    public int hashCode() {
        return hash(mMatchingApps, mMatchingDevices);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(this.getClass().getSimpleName()).append("{");
        sb.append("hasMatchingApps=").append(hasMatchingApps());
        sb.append(",matchingApps=").append(getMatchingApps());
        sb.append(",hasMatchingDevices=").append(hasMatchingDevices());
        sb.append(",matchingDevices=").append(getMatchingDevices());
        sb.append("}");
        return sb.toString();
    }

    /** @hide */
    @NonNull
    @Override
    public GetMatchingDataSourcesResponse toMasked(
            @NonNull Function<String, String> packageMasker) {
        Map<String, Set<String>> maskedAppsMap = toMaskedMap(mMatchingApps, packageMasker);
        Map<String, Set<String>> maskedDevicesMap = toMaskedMap(mMatchingDevices, packageMasker);
        return new GetMatchingDataSourcesResponse(maskedAppsMap, maskedDevicesMap);
    }

    private Map<String, Set<String>> toMaskedMap(
            Map<String, Set<String>> map, @NonNull Function<String, String> packageMasker) {
        Map<String, Set<String>> maskedMap = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : map.entrySet()) {
            String newKey = entry.getKey() == null ? null : packageMasker.apply(entry.getKey());
            maskedMap.put(newKey, entry.getValue());
        }
        return Map.copyOf(maskedMap);
    }

    private Map<String, Set<String>> parseMapFromBundle(@Nullable Bundle bundle) {
        if (bundle == null) {
            return Map.of();
        }
        Map<String, Set<String>> map = new HashMap<>();
        for (String key : bundle.keySet()) {
            ArrayList<String> values = bundle.getStringArrayList(key);
            if (values != null) {
                map.put(key, Set.copyOf(values));
            }
        }
        return Map.copyOf(map);
    }

    private Bundle mapToBundle(@NonNull Map<String, Set<String>> map) {
        Bundle bundle = new Bundle();
        for (Map.Entry<String, Set<String>> entry : map.entrySet()) {
            bundle.putStringArrayList(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return bundle;
    }
}
