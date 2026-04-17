/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.internal.telephony.nitz;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.timezone.MobileCountries;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.NitzSignal;

/**
 * An interface for the stateless component that generates suggestions using country and/or NITZ
 * information. The use of an interface means the behavior can be tested independently.
 */
@VisibleForTesting
public interface TimeZoneSuggester {

    /**
     * Generates a {@link TelephonyTimeZoneSuggestion} given the information available. This method
     * must always return a non-null {@link TelephonyTimeZoneSuggestion} but that object does not
     * have to contain a time zone if the available information is not sufficient to determine one.
     * {@link TelephonyTimeZoneSuggestion#getDebugInfo()} provides debugging / logging information
     * explaining the choice.
     */
    @NonNull
    TelephonyTimeZoneSuggestion getTimeZoneSuggestion(
            int slotIndex, @Nullable String countryIsoCode, @Nullable NitzSignal nitzSignal);

    /**
     * Generates a {@link TelephonyTimeZoneSuggestion} given the information available. This method
     * must always return a non-null {@link TelephonyTimeZoneSuggestion} but that object does not
     * have to contain a time zone if the available information is not sufficient to determine one.
     * {@link TelephonyTimeZoneSuggestion#getDebugInfo()} provides debugging / logging information
     * explaining the choice.
     */
    @NonNull
    TelephonyTimeZoneSuggestion getTimeZoneSuggestion(
            int slotIndex,
            @Nullable MobileCountries mobileCountries,
            @Nullable NitzSignal nitzSignal);
}
