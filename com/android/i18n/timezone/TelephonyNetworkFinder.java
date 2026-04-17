/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.i18n.timezone;

import static com.android.i18n.timezone.XmlUtils.normalizeCountryIso;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.i18n.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A class that can find telephony network information loaded via {@link TelephonyLookup}.
 *
 * @hide
 */
@libcore.api.CorePlatformApi
public final class TelephonyNetworkFinder {

    private final Map<MccMnc, MobileCountries> networksMap;
    private final List<MobileCountries> networkOverrides;
    private final Map<String, MobileCountries> countriesByMcc;

    public static TelephonyNetworkFinder create(List<MobileCountries> networksList,
            List<MobileCountries> mobileCountriesList) {
        Set<String> validCountryIsoCodes = new HashSet<>();
        for (String validCountryIsoCode : Locale.getISOCountries()) {
            validCountryIsoCodes.add(normalizeCountryIso(validCountryIsoCode));
        }

        /* Generate network map */
        Map<MccMnc, MobileCountries> networksMap = new HashMap<>();
        for (MobileCountries network : networksList) {
            if (!validCountryIsoCodes.contains(network.getDefaultCountryIsoCode())) {
                Log.w("Unrecognized country code: " + network.getDefaultCountryIsoCode()
                        + " for telephony network=" + network);
            }

            MccMnc mccMnc = new MccMnc(network.getMcc(), network.getMnc());
            MobileCountries existingEntry = networksMap.put(mccMnc, network);
            if (existingEntry != null) {
                Log.w("Duplicate MccMnc detected for " + mccMnc
                        + ". New entry=" + network + " replacing previous entry.");
            }
        }
        /* ************************* */

        /* Generate countries map */
        Map<String, MobileCountries> countriesByMcc = new HashMap<>();
        for (MobileCountries mobileCountries : mobileCountriesList) {
            for (String countryIsoCode : mobileCountries.getCountryIsoCodes()) {
                if (!validCountryIsoCodes.contains(countryIsoCode)) {
                    Log.w("Unrecognized country code: " + countryIsoCode
                            + " for telephony MCC=" + mobileCountries.getMcc());
                }
            }

            if (!validCountryIsoCodes.contains(mobileCountries.getDefaultCountryIsoCode())) {
                Log.w("Unrecognized default country code: "
                        + mobileCountries.getDefaultCountryIsoCode()
                        + " for telephony MCC=" + mobileCountries.getMcc());
            }

            if (countriesByMcc.containsKey(mobileCountries.getMcc())) {
                Log.w("Duplicate MCC detected for " + mobileCountries.getMcc()
                        + ". New entry=" + mobileCountries
                        + " replacing previous entry.");
            }

            countriesByMcc.put(mobileCountries.getMcc(), mobileCountries);
        }
        /* ************************* */

        return new TelephonyNetworkFinder(
                Collections.unmodifiableList(new ArrayList<>(networksList)),
                networksMap,
                countriesByMcc);
    }

    private TelephonyNetworkFinder(List<MobileCountries> networkOverrides,
            Map<MccMnc, MobileCountries> networksMap,
            Map<String, MobileCountries> countriesByMcc) {
        this.networkOverrides = networkOverrides;
        this.networksMap = networksMap;
        this.countriesByMcc = countriesByMcc;
    }

    /**
     * Returns information held about a specific MCC + MNC combination. It is expected for this
     * method to return {@code null}. Only known, unusual networks will typically have information
     * returned, e.g. if they operate in countries other than the one suggested by their MCC.
     */
    @libcore.api.CorePlatformApi
    @Nullable
    public MobileCountries findCountriesByMccMnc(@NonNull String mcc, @Nullable String mnc) {
        return networksMap.get(new MccMnc(mcc, mnc));
    }

    /**
     * Returns the countries where a given MCC is in use. It is expected for this method to return
     * {@code null} if the MCC is not found.
     */
    @libcore.api.CorePlatformApi
    @Nullable
    public MobileCountries findCountriesByMcc(@NonNull String mcc) {
        return countriesByMcc.get(mcc);
    }

    // @VisibleForTesting
    public List<MobileCountries> getAllNetworks() {
        return networkOverrides;
    }

    // @VisibleForTesting
    public List<MobileCountries> getAllMobileCountries() {
        return countriesByMcc.values().stream().toList();
    }
}
