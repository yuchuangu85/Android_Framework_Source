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

package android.timezone;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;

import android.timezone.flags.Flags;

import com.android.internal.annotations.VisibleForTesting;
import java.util.Objects;
import java.util.Set;

/**
 * Represents information about the countries associated with a mobile network.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_EXPOSE_TIME_ZONE_SYSTEM_API)
@SystemApi(client = MODULE_LIBRARIES)
public final class MobileCountries {

    @NonNull
    private final com.android.i18n.timezone.MobileCountries mDelegate;

    /**
     * Create a {@link MobileCountries} entity. This can be used for test networks (i.e. integration
     * tests).
     */
    @NonNull
    public static MobileCountries createTestCell(@NonNull String mcc) {
        return new MobileCountries(
                com.android.i18n.timezone.MobileCountries.create(mcc, Set.of(""), ""));
    }

    /**
     * Create a {@link MobileCountries} entity for tests (i.e. unit tests).
     *
     * @hide
     */
    @VisibleForTesting
    @NonNull
    public static MobileCountries createForTest(
            @NonNull String mcc,
            @Nullable String mnc,
            @NonNull Set<String> countryIsoCodes,
            @NonNull String defaultCountryIsoCode) {
        return new MobileCountries(
                com.android.i18n.timezone.MobileCountries.create(
                        mcc, mnc, countryIsoCodes, defaultCountryIsoCode));
    }

    MobileCountries(@NonNull com.android.i18n.timezone.MobileCountries delegate) {
        mDelegate = Objects.requireNonNull(delegate);
    }

    /**
     * Returns the Mobile Country Code of the network.
     */
    @NonNull
    public String getMcc() {
        return mDelegate.getMcc();
    }

    /**
     * Returns the Mobile Network Code of the network.
     */
    @Nullable
    public String getMnc() {
        return mDelegate.getMnc();
    }

    /**
     * Returns the Mobile Country Code of the network.
     */
    @NonNull
    public Set<String> getCountryIsoCodes() {
        return mDelegate.getCountryIsoCodes();
    }

    /**
     * Returns the country in which the network operates as an ISO 3166 alpha-2 (lower case).
     */
    @NonNull
    public String getDefaultCountryIsoCode() {
        return mDelegate.getDefaultCountryIsoCode();
    }

    @Override
    public String toString() {
        return "MobileCountries{"
                + "mDelegate=" + mDelegate
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof MobileCountries that) {
            return mDelegate.equals(that.mDelegate);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDelegate);
    }
}
