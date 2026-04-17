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
import android.annotation.SystemApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.icu.util.TimeZone;

import android.timezone.flags.Flags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Information about a country's time zones.
 *
 * <p>This class provides access to the time zones known to be used in a country, and methods to
 * find a time zone by UTC offset.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_EXPOSE_TIME_ZONE_SYSTEM_API)
@SystemApi(client = MODULE_LIBRARIES)
public final class CountryTimeZones {

    /**
     * Represents a time zone used in a country.
     *
     * <p>This class currently only exposes the time zone and its ID (e.g., "America/Los_Angeles").
     * It is structured to allow for additional metadata to be exposed in the future without
     * breaking the API.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_EXPOSE_TIME_ZONE_SYSTEM_API)
    @SystemApi(client = MODULE_LIBRARIES)
    public static final class TimeZoneMapping {

        @NonNull
        private com.android.i18n.timezone.CountryTimeZones.TimeZoneMapping mDelegate;

        TimeZoneMapping(com.android.i18n.timezone.CountryTimeZones.TimeZoneMapping delegate) {
            this.mDelegate = Objects.requireNonNull(delegate);
        }

        /**
         * Returns the ID for this mapping. The ID is a tzdb time zone identifier like
         * "America/Los_Angeles" that can be used with methods such as {@link
         * TimeZone#getFrozenTimeZone(String)} to obtain an immutable {@link TimeZone} instance. See
         * {@link #getTimeZone()}.
         */
        @NonNull
        public String getTimeZoneId() {
            return mDelegate.getTimeZoneId();
        }

        /**
         * Returns an immutable {@link TimeZone} object for this mapping.
         *
         * <p>This can be {@code null} if the time zone ID is not recognized.
         */
        @Nullable
        public TimeZone getTimeZone() {
            return mDelegate.getTimeZone();
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TimeZoneMapping that = (TimeZoneMapping) o;
            return this.mDelegate.equals(that.mDelegate);
        }

        @Override
        public int hashCode() {
            return this.mDelegate.hashCode();
        }

        @Override
        public String toString() {
            return mDelegate.toString();
        }
    }

    /**
     * The result of looking up a time zone using offset information.
     *
     * <p>It contains the matching time zone and information about the match.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_EXPOSE_TIME_ZONE_SYSTEM_API)
    @SystemApi(client = MODULE_LIBRARIES)
    public static final class OffsetResult {

        private final TimeZone mTimeZone;
        @Nullable private final String mCountryIsoCode;
        private final boolean mIsOnlyMatch;

        /** Creates an instance with the supplied information. */
        public OffsetResult(@NonNull TimeZone timeZone, @Nullable String countryIsoCode,
                boolean isOnlyMatch) {
            mTimeZone = Objects.requireNonNull(timeZone);
            mCountryIsoCode = countryIsoCode;
            mIsOnlyMatch = isOnlyMatch;
        }

        /**
         * Returns a time zone that matches the supplied criteria.
         */
        @NonNull
        public TimeZone getTimeZone() {
            return mTimeZone;
        }

        /**
         * Returns the ISO country code for the country where the time zone was matched.
         *
         * <p>This can be used to identify the country when a search for a time zone is performed
         * over multiple countries.
         */
        @Nullable
        public String getCountryIsoCode() {
            return mCountryIsoCode;
        }

        /**
         * Returns {@code true} if there is only one matching time zone for the supplied criteria.
         */
        public boolean isOnlyMatch() {
            return mIsOnlyMatch;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            OffsetResult that = (OffsetResult) o;
            return mIsOnlyMatch == that.mIsOnlyMatch
                    && mTimeZone.getID().equals(that.mTimeZone.getID())
                    && Objects.equals(mCountryIsoCode, that.mCountryIsoCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mTimeZone, mCountryIsoCode, mIsOnlyMatch);
        }

        @Override
        public String toString() {
            return "OffsetResult{"
                    + "mTimeZone(ID)=" + mTimeZone.getID()
                    + ", mCountryIsoCode=" + mCountryIsoCode
                    + ", mIsOnlyMatch=" + mIsOnlyMatch
                    + '}';
        }
    }

    @NonNull
    private final com.android.i18n.timezone.CountryTimeZones mDelegate;

    CountryTimeZones(com.android.i18n.timezone.CountryTimeZones delegate) {
        mDelegate = delegate;
    }

    /** Returns the ISO code for the country in lower case (e.g., "us"). */
    @NonNull
    public String getCountryIso() {
        return mDelegate.getCountryIso();
    }

    /**
     * Returns the default time zone ID for the country. Can return {@code null} in cases when no
     * data is available or the time zone ID was not recognized.
     */
    @Nullable
    public String getDefaultTimeZoneId() {
        return mDelegate.getDefaultTimeZoneId();
    }

    /**
     * Returns the default time zone for the country. Can return {@code null} in cases when no data
     * is available or the time zone ID was not recognized.
     */
    @Nullable
    public TimeZone getDefaultTimeZone() {
        return mDelegate.getDefaultTimeZone();
    }

    /**
     * Qualifier for a country's default time zone. {@code true} indicates that the country's
     * default time zone would be a good choice <em>generally</em> when there's no UTC offset
     * information available. This will only be {@code true} in countries with multiple zones where
     * a large majority of the population is covered by only one of them.
     */
    public boolean isDefaultTimeZoneBoosted() {
        return mDelegate.isDefaultTimeZoneBoosted();
    }

    /**
     * Returns {@code true} if the country has at least one time zone that uses UTC at the given
     * time. This is an efficient check when trying to validate received UTC offset information.
     * For example, there are situations when a detected zero UTC offset cannot be distinguished
     * from "no information available" or a corrupted signal. This method is useful because checking
     * offset information for large countries is relatively expensive but it is generally only the
     * countries close to the prime meridian that use UTC at <em>any</em> time of the year.
     *
     * @param whenMillis the time the offset information is for in milliseconds since the beginning
     *     of the Unix epoch
     */
    public boolean hasUtcZone(long whenMillis) {
        return mDelegate.hasUtcZone(whenMillis);
    }

    /**
     * Returns a time zone for the country, if there is one, that matches the supplied properties.
     * If there are multiple matches and the {@code bias} is one of them then it is returned,
     * otherwise an arbitrary match is returned based on the {@link
     * #getEffectiveTimeZoneMappingsAt(long)} ordering.
     *
     * @param whenMillis the UTC time to match against
     * @param bias the time zone to prefer, can be {@code null} to indicate there is no preference
     * @param totalOffsetMillis the offset from UTC at {@code whenMillis}
     * @param isDst the Daylight Savings Time state at {@code whenMillis}. {@code true} means DST,
     *     {@code false} means not DST
     * @return an {@link OffsetResult} with information about a matching zone, or {@code null} if
     *     there is no match
     */
    @Nullable
    public OffsetResult lookupByOffsetWithBias(
            long whenMillis, @NonNull TimeZone bias, int totalOffsetMillis, boolean isDst) {
        com.android.i18n.timezone.CountryTimeZones.OffsetResult delegateOffsetResult =
                mDelegate.lookupByOffsetWithBias(
                        whenMillis, bias, totalOffsetMillis, isDst);
        return delegateOffsetResult == null ? null :
                new OffsetResult(delegateOffsetResult.getTimeZone(), mDelegate.getCountryIso(),
                        delegateOffsetResult.isOnlyMatch());
    }

    /**
     * Returns a time zone for the country, if there is one, that matches the supplied properties.
     * If there are multiple matches and the {@code bias} is one of them then it is returned,
     * otherwise an arbitrary match is returned based on the {@link
     * #getEffectiveTimeZoneMappingsAt(long)} ordering.
     *
     * @param whenMillis the UTC time to match against
     * @param bias the time zone to prefer, can be {@code null} to indicate there is no preference
     * @param totalOffsetMillis the offset from UTC at {@code whenMillis}
     * @return an {@link OffsetResult} with information about a matching zone, or {@code null} if
     *     there is no match
     */
    @Nullable
    public OffsetResult lookupByOffsetWithBias(
            long whenMillis, @NonNull TimeZone bias, int totalOffsetMillis) {
        com.android.i18n.timezone.CountryTimeZones.OffsetResult delegateOffsetResult =
                mDelegate.lookupByOffsetWithBias(whenMillis, bias, totalOffsetMillis);
        return delegateOffsetResult == null ? null :
                new OffsetResult(delegateOffsetResult.getTimeZone(), mDelegate.getCountryIso(),
                        delegateOffsetResult.isOnlyMatch());
    }

    /**
     * Returns an immutable, ordered list of time zone mappings for the country in an undefined but
     * "priority" order, filtered so that only "effective" time zone IDs are returned. An
     * "effective" time zone is one that differs from another time zone used in the country after
     * {@code whenMillis}. The list can be empty if there were no zones configured or the configured
     * zone IDs were not recognized.
     */
    @NonNull
    public List<TimeZoneMapping> getEffectiveTimeZoneMappingsAt(long whenMillis) {
        List<com.android.i18n.timezone.CountryTimeZones.TimeZoneMapping> delegateList =
                mDelegate.getEffectiveTimeZoneMappingsAt(whenMillis);

        List<TimeZoneMapping> toReturn = new ArrayList<>(delegateList.size());
        for (com.android.i18n.timezone.CountryTimeZones.TimeZoneMapping delegateMapping
                : delegateList) {
            toReturn.add(new TimeZoneMapping(delegateMapping));
        }
        return Collections.unmodifiableList(toReturn);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CountryTimeZones that = (CountryTimeZones) o;
        return mDelegate.equals(that.mDelegate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDelegate);
    }

    @Override
    public String toString() {
        return mDelegate.toString();
    }
}
