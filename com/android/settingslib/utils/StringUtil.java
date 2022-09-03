/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.utils;

import android.content.Context;
import android.icu.text.MeasureFormat;
import android.icu.text.MeasureFormat.FormatWidth;
import android.icu.text.MessageFormat;
import android.icu.text.RelativeDateTimeFormatter;
import android.icu.text.RelativeDateTimeFormatter.RelativeUnit;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;
import android.icu.util.ULocale;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.TtsSpan;

import com.android.settingslib.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Utility class for generally useful string methods **/
public class StringUtil {

    public static final int SECONDS_PER_MINUTE = 60;
    public static final int SECONDS_PER_HOUR = 60 * 60;
    public static final int SECONDS_PER_DAY = 24 * 60 * 60;

    private static final int LIMITED_TIME_UNIT_COUNT = 2;

    /**
     * Returns elapsed time for the given millis, in the following format:
     * 2 days, 5 hr, 40 min, 29 sec
     *
     * @param context     the application context
     * @param millis      the elapsed time in milli seconds
     * @param withSeconds include seconds?
     * @param collapseTimeUnit limit the output to top 2 time unit
     *                         e.g 2 days, 5 hr, 40 min, 29 sec will convert to 2 days, 5 hr
     * @return the formatted elapsed time
     */
    public static CharSequence formatElapsedTime(Context context, double millis,
            boolean withSeconds, boolean collapseTimeUnit) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int seconds = (int) Math.floor(millis / 1000);
        if (!withSeconds) {
            // Round up.
            seconds += 30;
        }

        int days = 0, hours = 0, minutes = 0;
        if (seconds >= SECONDS_PER_DAY) {
            days = seconds / SECONDS_PER_DAY;
            seconds -= days * SECONDS_PER_DAY;
        }
        if (seconds >= SECONDS_PER_HOUR) {
            hours = seconds / SECONDS_PER_HOUR;
            seconds -= hours * SECONDS_PER_HOUR;
        }
        if (seconds >= SECONDS_PER_MINUTE) {
            minutes = seconds / SECONDS_PER_MINUTE;
            seconds -= minutes * SECONDS_PER_MINUTE;
        }

        final ArrayList<Measure> measureList = new ArrayList(4);
        if (days > 0) {
            measureList.add(new Measure(days, MeasureUnit.DAY));
        }
        if (hours > 0) {
            measureList.add(new Measure(hours, MeasureUnit.HOUR));
        }
        if (minutes > 0) {
            measureList.add(new Measure(minutes, MeasureUnit.MINUTE));
        }
        if (withSeconds && seconds > 0) {
            measureList.add(new Measure(seconds, MeasureUnit.SECOND));
        }
        if (measureList.size() == 0) {
            // Everything addable was zero, so nothing was added. We add a zero.
            measureList.add(new Measure(0, withSeconds ? MeasureUnit.SECOND : MeasureUnit.MINUTE));
        }

        if (collapseTimeUnit && measureList.size() > LIMITED_TIME_UNIT_COUNT) {
            // Limit the output to top 2 time unit.
            measureList.subList(LIMITED_TIME_UNIT_COUNT, measureList.size()).clear();
        }

        final Measure[] measureArray = measureList.toArray(new Measure[measureList.size()]);

        final Locale locale = context.getResources().getConfiguration().locale;
        final MeasureFormat measureFormat = MeasureFormat.getInstance(
                locale, FormatWidth.SHORT);
        sb.append(measureFormat.formatMeasures(measureArray));

        if (measureArray.length == 1 && MeasureUnit.MINUTE.equals(measureArray[0].getUnit())) {
            // Add ttsSpan if it only have minute value, because it will be read as "meters"
            final TtsSpan ttsSpan = new TtsSpan.MeasureBuilder().setNumber(minutes)
                    .setUnit("minute").build();
            sb.setSpan(ttsSpan, 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return sb;
    }

    /**
     * Returns relative time for the given millis in the past with different format style.
     * In a short format such as "2 days ago", "5 hr. ago", "40 min. ago", or "29 sec. ago".
     * In a long format such as "2 days ago", "5 hours ago",  "40 minutes ago" or "29 seconds ago".
     *
     * <p>The unit is chosen to have good information value while only using one unit. So 27 hours
     * and 50 minutes would be formatted as "28 hr. ago", while 50 hours would be formatted as
     * "2 days ago".
     *
     * @param context     the application context
     * @param millis      the elapsed time in milli seconds
     * @param withSeconds include seconds?
     * @param formatStyle format style
     * @return the formatted elapsed time
     */
    public static CharSequence formatRelativeTime(Context context, double millis,
            boolean withSeconds, RelativeDateTimeFormatter.Style formatStyle) {
        final int seconds = (int) Math.floor(millis / 1000);
        final RelativeUnit unit;
        final int value;
        if (withSeconds && seconds < 2 * SECONDS_PER_MINUTE) {
            return context.getResources().getString(R.string.time_unit_just_now);
        } else if (seconds < 2 * SECONDS_PER_HOUR) {
            unit = RelativeUnit.MINUTES;
            value = (seconds + SECONDS_PER_MINUTE / 2)
                    / SECONDS_PER_MINUTE;
        } else if (seconds < 2 * SECONDS_PER_DAY) {
            unit = RelativeUnit.HOURS;
            value = (seconds + SECONDS_PER_HOUR / 2)
                    / SECONDS_PER_HOUR;
        } else {
            unit = RelativeUnit.DAYS;
            value = (seconds + SECONDS_PER_DAY / 2)
                    / SECONDS_PER_DAY;
        }

        final Locale locale = context.getResources().getConfiguration().locale;
        final RelativeDateTimeFormatter formatter = RelativeDateTimeFormatter.getInstance(
                ULocale.forLocale(locale),
                null /* default NumberFormat */,
                formatStyle,
                android.icu.text.DisplayContext.CAPITALIZATION_FOR_MIDDLE_OF_SENTENCE);

        return formatter.format(value, RelativeDateTimeFormatter.Direction.LAST, unit);
    }

    /**
     * Returns relative time for the given millis in the past, in a long format such as "2 days
     * ago", "5 hours ago",  "40 minutes ago" or "29 seconds ago".
     *
     * <p>The unit is chosen to have good information value while only using one unit. So 27 hours
     * and 50 minutes would be formatted as "28 hr. ago", while 50 hours would be formatted as
     * "2 days ago".
     *
     * @param context     the application context
     * @param millis      the elapsed time in milli seconds
     * @param withSeconds include seconds?
     * @return the formatted elapsed time
     * @deprecated use {@link #formatRelativeTime(Context, double, boolean,
     * RelativeDateTimeFormatter.Style)} instead.
     */
    @Deprecated
    public static CharSequence formatRelativeTime(Context context, double millis,
            boolean withSeconds) {
        return formatRelativeTime(context, millis, withSeconds,
                RelativeDateTimeFormatter.Style.LONG);
    }

    /**
     * Get ICU plural string without additional arguments
     *
     * @param context Context used to get the string
     * @param count The number used to get the correct string for the current language's plural
     *              rules.
     * @param resId Resource id of the string
     *
     * @return Formatted plural string
     */
    public static String getIcuPluralsString(Context context, int count, int resId) {
        MessageFormat msgFormat = new MessageFormat(context.getResources().getString(resId),
                Locale.getDefault());
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("count", count);
        return msgFormat.format(arguments);
    }

    /**
     * Get ICU plural string with additional arguments
     *
     * @param context Context used to get the string
     * @param args String arguments
     * @param resId Resource id of the string
     *
     * @return Formatted plural string
     */
    public static String getIcuPluralsString(Context context, Map<String, Object> args, int resId) {
        MessageFormat msgFormat = new MessageFormat(context.getResources().getString(resId),
                Locale.getDefault());
        return msgFormat.format(args);
    }
}
