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

package android.timezone;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;

import android.timezone.flags.Flags;

import com.android.internal.annotations.GuardedBy;

import java.util.Objects;

/**
 * A class that can find telephony network information loaded via {@link TelephonyLookup}.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_EXPOSE_TIME_ZONE_SYSTEM_API)
@SystemApi(client = MODULE_LIBRARIES)
public final class TelephonyNetworkFinder {

    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static TelephonyNetworkFinder sInstance;

    /**
     * Returns an object capable of querying telephony network information. This method can return
     * {@code null} in the event of an error while reading the underlying data files.
     */
    @Nullable
    public static TelephonyNetworkFinder getInstance() {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance =
                        new TelephonyNetworkFinder(
                                com.android.i18n.timezone.TelephonyLookup.getInstance()
                                        .getTelephonyNetworkFinder());
            }
            return sInstance;
        }
    }

    @NonNull
    private final com.android.i18n.timezone.TelephonyNetworkFinder mDelegate;

    TelephonyNetworkFinder(com.android.i18n.timezone.TelephonyNetworkFinder delegate) {
        mDelegate = Objects.requireNonNull(delegate);
    }

    /**
     * Returns information held about a specific MCC + MNC combination. It is expected for this
     * method to return {@code null}. Only known, unusual networks will typically have information
     * returned, e.g. if they operate in countries other than the one suggested by their MCC.
     */
    @Nullable
    public MobileCountries findCountriesByMccMnc(@NonNull String mcc, @NonNull String mnc) {
        Objects.requireNonNull(mcc);
        Objects.requireNonNull(mnc);

        com.android.i18n.timezone.MobileCountries telephonyNetworkDelegate =
                mDelegate.findCountriesByMccMnc(mcc, mnc);
        return telephonyNetworkDelegate != null
                ? new MobileCountries(telephonyNetworkDelegate) : null;
    }

    /** Returns the countries associated with a mobile country code (MCC). */
    @Nullable
    public MobileCountries findCountriesByMcc(@NonNull String mcc) {
        Objects.requireNonNull(mcc);

        com.android.i18n.timezone.MobileCountries countriesByMcc =
                mDelegate.findCountriesByMcc(mcc);
        return countriesByMcc != null ? new MobileCountries(countriesByMcc) : null;
    }
}
