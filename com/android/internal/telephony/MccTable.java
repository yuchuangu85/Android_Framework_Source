/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.Build;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.timezone.MobileCountries;
import android.timezone.TelephonyNetworkFinder;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.util.TelephonyUtils;
import com.android.telephony.Rlog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Mobile Country Code
 *
 * @hide
 */
public final class MccTable {
    static final String LOG_TAG = "MccTable";

    @GuardedBy("MccTable.class")
    private static TelephonyNetworkFinder sTelephonyNetworkFinder;

    static ArrayList<MccEntry> sTable;

    @VisibleForTesting
    public static List<MccEntry> getAllMccEntries() {
        return new ArrayList<>(sTable);
    }

    /**
     * Container class for mcc and mSmallestDigitsMnc. This class implements compareTo so that it
     * can be sorted by mcc.
     */
    public static class MccEntry implements Comparable<MccEntry> {
        @VisibleForTesting
        public final int mMcc;
        final int mSmallestDigitsMnc;

        MccEntry(int mcc, int smallestDigitsMCC) {
            mMcc = mcc;
            mSmallestDigitsMnc = smallestDigitsMCC;
        }

        @Override
        public int compareTo(MccEntry o) {
            return mMcc - o.mMcc;
        }
    }

    /**
     * A combination of MCC and MNC. The MNC is optional and may be null.
     *
     * @hide
     */
    public static class MccMnc {
        @NonNull
        public final String mcc;

        @Nullable
        public final String mnc;

        /**
         * Splits the supplied String in two: the first three characters are treated as the MCC,
         * the remaining characters are treated as the MNC.
         */
        @Nullable
        public static MccMnc fromOperatorNumeric(@NonNull String operatorNumeric) {
            Objects.requireNonNull(operatorNumeric);
            String mcc;
            try {
                mcc = operatorNumeric.substring(0, 3);
            } catch (StringIndexOutOfBoundsException e) {
                return null;
            }

            String mnc;
            try {
                mnc = operatorNumeric.substring(3);
            } catch (StringIndexOutOfBoundsException e) {
                mnc = null;
            }
            return new MccMnc(mcc, mnc);
        }

        /**
         * Creates an MccMnc using the supplied values.
         */
        public MccMnc(@NonNull String mcc, @Nullable String mnc) {
            this.mcc = Objects.requireNonNull(mcc);
            this.mnc = mnc;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            MccMnc mccMnc = (MccMnc) o;
            return mcc.equals(mccMnc.mcc)
                    && Objects.equals(mnc, mccMnc.mnc);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mcc, mnc);
        }

        @Override
        public String toString() {
            return "MccMnc{"
                    + "mcc='" + mcc + '\''
                    + ", mnc='" + mnc + '\''
                    + '}';
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q,
            publicAlternatives = "There is no alternative for {@code MccTable.entryForMcc}, "
                    + "but it was included in hidden APIs due to a static analysis false positive "
                    + "and has been made greylist-max-q. Please file a bug if you still require "
                    + "this API.")
    public static MccEntry entryForMcc(int mcc) {
        MccEntry m = new MccEntry(mcc, 0);

        int index = Collections.binarySearch(sTable, m);

        if (index < 0) {
            return null;
        } else {
            return sTable.get(index);
        }
    }

    /**
     * Given a GSM Mobile Country Code, returns a lower-case ISO 3166 alpha-2 country code if
     * available. Returns empty string if unavailable.
     */
    @NonNull
    public static String countryCodeForMcc(@NonNull String mcc) {
        if (mcc == null) {
            return "";
        }

        TelephonyNetworkFinder telephonyNetworkFinder;

        synchronized (MccTable.class) {
            if ((telephonyNetworkFinder = sTelephonyNetworkFinder) == null) {
                sTelephonyNetworkFinder =
                        telephonyNetworkFinder = TelephonyNetworkFinder.getInstance();
            }
        }

        if (telephonyNetworkFinder == null) {
            // This should not happen under normal circumstances, only when the data is missing.
            return "";
        }

        MobileCountries mobileCountries = telephonyNetworkFinder.findCountriesByMcc(mcc);
        if (mobileCountries == null) {
            return "";
        }

        return mobileCountries.getDefaultCountryIsoCode();
    }

    /**
     * Given a combination of MCC and MNC, returns a lower case ISO 3166 alpha-2 country code for
     * the device's geographical location.
     *
     * <p>This can give a better geographical result than {@link #countryCodeForMcc(String)}
     * (which provides the official "which country is the MCC assigned to?" answer) for cases when
     * MNC is also available: Sometimes an MCC can be used by multiple countries and the MNC can
     * help distinguish, or the MCC assigned to a country isn't used for geopolitical reasons.
     * When the geographical country is needed  (e.g. time zone detection) this version can provide
     * more pragmatic results than the official MCC-only answer. This method falls back to calling
     * {@link #countryCodeForMcc(String)} if no special MCC+MNC cases are found.
     * Returns empty string if no code can be determined.
     */
    @NonNull
    public static String geoCountryCodeForMccMnc(@NonNull MccMnc mccMnc) {
        String countryCode = null;
        if (mccMnc.mnc != null) {
            countryCode = countryCodeForMccMncNoFallback(mccMnc);
        }
        if (TextUtils.isEmpty(countryCode)) {
            // Try the MCC-only fallback.
            countryCode = countryCodeForMcc(mccMnc.mcc);
        }
        return countryCode;
    }

    @Nullable
    private static String countryCodeForMccMncNoFallback(MccMnc mccMnc) {
        synchronized (MccTable.class) {
            if (sTelephonyNetworkFinder == null) {
                sTelephonyNetworkFinder = TelephonyNetworkFinder.getInstance();
            }
        }
        if (sTelephonyNetworkFinder == null) {
            // This should not happen under normal circumstances, only when the data is missing.
            return null;
        }
        MobileCountries mobileCountries = sTelephonyNetworkFinder.findCountriesByMccMnc(
                mccMnc.mcc, mccMnc.mnc);
        if (mobileCountries == null) {
            return null;
        }
        return mobileCountries.getDefaultCountryIsoCode();
    }

    /**
     * Given a GSM Mobile Country Code, returns
     * the smallest number of digits that M if available.
     * Returns 2 if unavailable.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q,
            publicAlternatives = "There is no alternative for {@code MccTable"
                    + ".smallestDigitsMccForMnc}, but it was included in hidden APIs due to a "
                    + "static analysis false positive and has been made max Q. Please "
                    + "file a bug if you still require this API.")
    public static int smallestDigitsMccForMnc(int mcc) {
        MccEntry entry = entryForMcc(mcc);

        if (entry == null) {
            return 2;
        } else {
            return entry.mSmallestDigitsMnc;
        }
    }

    /**
     * Updates MCC and MNC device configuration information for application retrieving
     * correct version of resources.  If MCC is 0, MCC and MNC will be ignored (not set).
     * @param context Context to act on.
     * @param mccmnc truncated imsi with just the MCC and MNC - MNC assumed to be from 4th to end
     */
    public static void updateMccMncConfiguration(Context context, String mccmnc) {
        Rlog.d(LOG_TAG, "updateMccMncConfiguration mccmnc='" + mccmnc);

        if (TelephonyUtils.IS_DEBUGGABLE) {
            String overrideMcc = SystemProperties.get("persist.sys.override_mcc");
            if (!TextUtils.isEmpty(overrideMcc)) {
                mccmnc = overrideMcc;
                Rlog.d(LOG_TAG, "updateMccMncConfiguration overriding mccmnc='" + mccmnc + "'");
            }
        }

        if (!TextUtils.isEmpty(mccmnc)) {
            int mccInt;
            try {
                mccInt = Integer.parseInt(mccmnc.substring(0, 3));
            } catch (NumberFormatException | StringIndexOutOfBoundsException ex) {
                Rlog.e(LOG_TAG, "Error parsing mccmnc: " + mccmnc + ". ex=" + ex);
                return;
            }
            if (mccInt != 0) {
                ActivityManager activityManager = (ActivityManager) context.getSystemService(
                        Context.ACTIVITY_SERVICE);
                if (!activityManager.updateMccMncConfiguration(
                        mccmnc.substring(0, 3), mccmnc.substring(3))) {
                    Rlog.d(LOG_TAG, "updateMccMncConfiguration: update mccmnc="
                            + mccmnc + " failure");
                } else {
                    Rlog.d(LOG_TAG, "updateMccMncConfiguration: update mccmnc="
                            + mccmnc + " success");
                }
            } else {
                Rlog.d(LOG_TAG, "updateMccMncConfiguration nothing to update");
            }
        }
    }

    /**
     * Maps a given locale to a fallback locale that approximates it. This is a hack.
     */
    public static final Map<Locale, Locale> FALLBACKS = new HashMap<Locale, Locale>();

    static {
        // If we have English (without a country) explicitly prioritize en_US. http://b/28998094
        FALLBACKS.put(Locale.ENGLISH, Locale.US);
    }

    static {
        sTable = new ArrayList<MccEntry>(20);

        /*
         * The table below contains MCCs that have 3-digit MNCs.
         * For all other MCCs, 2-digit MNCs are assumed by default.
         */

        sTable.add(new MccEntry(302, 3)); // Canada
        sTable.add(new MccEntry(310, 3)); // United States of America
        sTable.add(new MccEntry(311, 3)); // United States of America
        sTable.add(new MccEntry(312, 3)); // United States of America
        sTable.add(new MccEntry(313, 3)); // United States of America
        sTable.add(new MccEntry(314, 3)); // United States of America
        sTable.add(new MccEntry(315, 3)); // United States of America
        sTable.add(new MccEntry(316, 3)); // United States of America
        sTable.add(new MccEntry(334, 3)); // Mexico
        sTable.add(new MccEntry(338, 3)); // Jamaica
        sTable.add(new MccEntry(342, 3)); // Barbados
        sTable.add(new MccEntry(344, 3)); // Antigua and Barbuda
        sTable.add(new MccEntry(346, 3)); // Cayman Islands
        sTable.add(new MccEntry(348, 3)); // British Virgin Islands
        sTable.add(new MccEntry(365, 3)); // Anguilla
        sTable.add(new MccEntry(708, 3)); // Honduras (Republic of)
        sTable.add(new MccEntry(722, 3)); // Argentine Republic
        sTable.add(new MccEntry(732, 3)); // Colombia (Republic of)

        Collections.sort(sTable);
    }
}
