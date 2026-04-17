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

package android.widget;

import android.icu.text.MeasureFormat;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;

import androidx.annotation.NonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adaptive formatter for {@link Chronometer}.
 * @hide
 */
public class ChronometerAdaptiveFormat {

    /**
     * In adaptive format, when displaying a duration greater than or equal to this number of
     * minutes, seconds will not be shown.
     */
    private static final int ADAPTIVE_MINUTES_WITHOUT_SECONDS = 3;

    /**
     * Formats a <em>positive</em> {@link Duration} as an "adaptive time description".
     *
     * <ul>
     *     <li>Shows time split into units: days, hours, minutes, seconds, in that order.
     *     <li>Uses short time unit strings (e.g. "m" instead of "minute" or "minutes").
     *     <li>Rounds the duration to the nearest second, and then shows all other units with
     *     truncation (e.g. 1h 45m 55s is shown as "1h 45m", NOT "1h 46m").
     *     <li>At most two units are displayed (e.g. "2h 15m" but not "2h 15m 10s").
     *     <li>The most significant and least significant unit must be adjacent (e.g. no "3 days
     *     12 minutes").
     *     <li>Units with value zero are not displayed (with an exception for seconds).
     *     <li>Seconds are always displayed if minutes <
     *     {@value ADAPTIVE_MINUTES_WITHOUT_SECONDS} and never otherwise.
     * </ul>
     */
    @NonNull
    public static String format(@NonNull Duration duration) {
        List<Measure> partsList = toDurationParts(duration);
        MeasureFormat formatter = MeasureFormat.getInstance(Locale.getDefault(),
                MeasureFormat.FormatWidth.NARROW);
        return formatter.formatMeasures(partsList.toArray(new Measure[0]));
    }

    /**
     * Variant of {@link #format(Duration)} that will produce several variants for the text
     * representation of the duration in different levels of precision. Follows all the rules of
     * {@link #format(Duration)} as they relate to truncation, etc.
     *
     * <p>For example, a duration of 100 seconds will produce "1m 40s" and "1m".
     */
    @NonNull
    public static List<String> formatVariants(@NonNull Duration duration) {
        ArrayList<String> variants = new ArrayList<>();
        List<Measure> partsList = toDurationParts(duration);
        MeasureFormat formatter = MeasureFormat.getInstance(Locale.getDefault(),
                MeasureFormat.FormatWidth.NARROW);

        for (int lastPartIndex = partsList.size(); lastPartIndex > 0; lastPartIndex--) {
            variants.add(formatter.formatMeasures(
                    partsList.subList(0, lastPartIndex).toArray(new Measure[0])));
        }

        return variants;
    }

    private static List<Measure> toDurationParts(@NonNull Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Duration must be positive; got: " + duration);
        }

        final Measure days = new Measure(duration.toDaysPart(), MeasureUnit.DAY);
        final Measure hours = new Measure(duration.toHoursPart(), MeasureUnit.HOUR);
        final Measure minutes = new Measure(duration.toMinutesPart(), MeasureUnit.MINUTE);
        final Measure seconds = new Measure(duration.toSecondsPart(), MeasureUnit.SECOND);

        final ArrayList<Measure> partsList = new ArrayList<>();
        if (days.getNumber().intValue() != 0) {
            partsList.add(days);
            if (hours.getNumber().intValue() != 0) {
                partsList.add(hours);
            }
        } else if (hours.getNumber().intValue() != 0) {
            partsList.add(hours);
            if (minutes.getNumber().intValue() != 0) {
                partsList.add(minutes);
            }
        } else if (minutes.getNumber().intValue() != 0) {
            partsList.add(minutes);
            if (minutes.getNumber().intValue() < ADAPTIVE_MINUTES_WITHOUT_SECONDS) {
                partsList.add(seconds);
            }
        }

        if (partsList.isEmpty()) {
            partsList.add(seconds);
        }

        return partsList;
    }

    /**
     * In adaptive format, when displaying an elapsed/remaining duration greater than or equal to
     * this number of minutes, seconds will not be shown (which also means the chronometer can
     * safely tick on the minute, rather than on the second).
     */
    @NonNull
    public static Duration getTickPeriod(@NonNull Duration value) {
        return value.abs().toMillis()
                > Duration.ofMinutes(ADAPTIVE_MINUTES_WITHOUT_SECONDS).toMillis()
                ? Duration.ofMinutes(1)
                : Duration.ofSeconds(1);
    }

    private ChronometerAdaptiveFormat() {}
}
