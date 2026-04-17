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

import android.annotation.NonNull;
import android.content.res.Resources;
import android.icu.text.MeasureFormat;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;

import com.android.internal.R;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;

/**
 * Helper class for formatting Chronometer display in low frequency mode.
 * @hide
 */
public final class ChronometerLowFrequencyFormat {
    private static final Object sLock = new Object();

    private static String sElapsedFormatHMMLowFrequency;
    private static String sElapsedFormatMMLowFrequency;
    private static String sAdaptiveLowFreqLessThanAMinute;
    private static String sAdaptiveLowFreqNegLessThanAMinute;

    private static void initFormatStrings() {
        synchronized (sLock) {
            Resources r = Resources.getSystem();
            sElapsedFormatMMLowFrequency = r.getString(
                    R.string.elapsed_time_low_frequency_short_format_mm);
            sElapsedFormatHMMLowFrequency = r.getString(
                    R.string.elapsed_time_low_frequency_short_format_h_mm);
            sAdaptiveLowFreqLessThanAMinute = r.getString(
                    R.string.adaptive_low_frequency_less_than_one_minute);
            sAdaptiveLowFreqNegLessThanAMinute = r.getString(
                    R.string.adaptive_low_frequency_neg_less_than_one_minute);
        }
    }

    /**
     * Formats the given duration in seconds for low-frequency display.
     *
     * @param elapsedTime the elapsed time in seconds. The elapsedTime must be non-negative
     * @param isAdaptive whether to use the adaptive format (e.g., "~ 1m", "5m", "2h 30m")
     *                   or the fixed chronometer format (e.g., "00:--", "05:--", "1:00:--").
     */
    @NonNull
    public static String format(Duration elapsedTime, boolean isAdaptive) {
        if (elapsedTime.isNegative()) {
            throw new IllegalArgumentException(
                    "elapsedTime must be non-negative; got: " + elapsedTime);
        }
        return isAdaptive ? formatAdaptiveLowFrequency(elapsedTime)
                : formatChronometerLowFrequency(elapsedTime);
    }

    /**
     * Variant of {@link #format(Duration, boolean)} that will produce several variants
     * for the text representation of the duration in different levels of precision, as long as the
     * chosen formatting supports it.
     *
     * <p>For example, a duration of 100 minutes in adaptive format will produce "1h 40m" and "1h".
     */
    @NonNull
    public static List<String> formatVariants(@NonNull Duration duration, boolean isAdaptive) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException(
                    "duration must be non-negative; got: " + duration);
        }
        return isAdaptive
                ? formatAdaptiveLowFrequencyVariants(duration)
                : List.of(formatChronometerLowFrequency(duration));
    }

    /**
     * Returns formatted duration between 0 and -1 minutes.
     */
    @NonNull
    public static String formatAdaptiveNegativeLessThanOneMinute() {
        initFormatStrings();
        final MeasureFormat formatter = MeasureFormat.getInstance(Locale.getDefault(),
                MeasureFormat.FormatWidth.NARROW);
        final Measure minute = new Measure(1, MeasureUnit.MINUTE);
        return String.format(sAdaptiveLowFreqNegLessThanAMinute, formatter.formatMeasures(minute));
    }

    private static String formatChronometerLowFrequency(Duration duration) {
        long elapsedTime = duration.toSeconds();
        // Break the elapsed seconds into hours, minutes, and seconds.
        long hours = 0;
        long minutes = 0;
        if (elapsedTime >= 3600) {
            hours = elapsedTime / 3600;
            elapsedTime -= hours * 3600;
        }
        if (elapsedTime >= 60) {
            minutes = elapsedTime / 60;
        }

        // Format the broken-down time in a locale-appropriate way.
        Formatter f = new Formatter(new StringBuilder(), Locale.getDefault());
        initFormatStrings();
        if (hours > 0) {
            return f.format(sElapsedFormatHMMLowFrequency, hours, minutes).toString();
        } else {
            return f.format(sElapsedFormatMMLowFrequency, minutes).toString();
        }
    }

    @NonNull
    private static String formatAdaptiveLowFrequency(Duration duration) {
        initFormatStrings();
        if (duration.compareTo(Duration.ofMinutes(1)) < 0) {
            final MeasureFormat formatter = MeasureFormat.getInstance(Locale.getDefault(),
                    MeasureFormat.FormatWidth.NARROW);
            final Measure minute = new Measure(1, MeasureUnit.MINUTE);
            return String.format(sAdaptiveLowFreqLessThanAMinute, formatter.formatMeasures(minute));
        }

        List<Measure> partsList = toDurationParts(duration);
        final MeasureFormat formatter = MeasureFormat.getInstance(Locale.getDefault(),
                MeasureFormat.FormatWidth.NARROW);

        return formatter.formatMeasures(partsList.toArray(new Measure[0]));
    }

    /**
     * Variant of {@link #formatAdaptiveLowFrequency(Duration)} that will produce several
     * variants for the text representation of the duration in different levels of precision.
     *
     * <p>For example, a duration of 100 minutes will produce "1h 40m" and "1h".
     */
    @NonNull
    private static List<String> formatAdaptiveLowFrequencyVariants(@NonNull Duration duration) {
        if (duration.compareTo(Duration.ofMinutes(1)) < 0) {
            return List.of(formatAdaptiveLowFrequency(duration));
        }

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
        final Measure days = new Measure(duration.toDaysPart(), MeasureUnit.DAY);
        final Measure hours = new Measure(duration.toHoursPart(), MeasureUnit.HOUR);
        final Measure minutes = new Measure(duration.toMinutesPart(), MeasureUnit.MINUTE);

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
        } else {
            partsList.add(minutes);
        }

        return partsList;
    }

    private ChronometerLowFrequencyFormat() {}
}
