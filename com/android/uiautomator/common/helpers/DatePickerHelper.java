/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.uiautomator.common.helpers;

import com.android.uiautomator.core.UiObject;
import com.android.uiautomator.core.UiObjectNotFoundException;
import com.android.uiautomator.core.UiSelector;

import java.util.Calendar;

/**
 * Use this helper anywhere there is a date picker to manage. This helper
 * will set date specified in a Calendar object.
 */
public class DatePickerHelper {

    public static final int MONTH = 0;
    public static final int DAY = 1;
    public static final int YEAR = 2;

    public static String getCurrentMonth() throws UiObjectNotFoundException {
        return getNumberPickerField(MONTH).getText();
    }

    public static String getCurrentDay() throws UiObjectNotFoundException {
        return getNumberPickerField(DAY).getText();
    }

    public static String getCurrentYear() throws UiObjectNotFoundException {
        return getNumberPickerField(YEAR).getText();
    }

    public static void incrementMonth() throws UiObjectNotFoundException {
        incrementMonth(1);
    }

    public static void incrementMonth(int count) throws UiObjectNotFoundException {
        for (int x = 0; x < count; x++)
            getNumberPickerIncrementButton(MONTH).click();
    }

    public static void decrementMonth() throws UiObjectNotFoundException {
        decrementMonth(1);
    }

    public static void decrementMonth(int count) throws UiObjectNotFoundException {
        for (int x = 0; x < count; x++)
            getNumberPickerDecrementButton(MONTH).click();
    }

    public static void incrementDay() throws UiObjectNotFoundException {
        incrementDay(1);
    }

    public static void incrementDay(int count) throws UiObjectNotFoundException {
        for (int x = 0; x < count; x++)
            getNumberPickerIncrementButton(DAY).click();
    }

    public static void decrementDay() throws UiObjectNotFoundException {
        decrementDay(1);
    }

    public static void decrementDay(int count) throws UiObjectNotFoundException {
        for (int x = 0; x < count; x++)
            getNumberPickerDecrementButton(DAY).click();
    }

    public static void incrementYear() throws UiObjectNotFoundException {
        incrementYear(1);
    }

    public static void incrementYear(int count) throws UiObjectNotFoundException {
        for (int x = 0; x < count; x++)
            getNumberPickerIncrementButton(YEAR).click();
    }

    public static void decrementYear() throws UiObjectNotFoundException {
        decrementYear(1);
    }

    public static void decrementYear(int count) throws UiObjectNotFoundException {
        for (int x = 0; x < count; x++)
            getNumberPickerDecrementButton(YEAR).click();
    }

    public static UiObject getNumberPicker(int instance) {
        return new UiObject(new UiSelector().className(
                android.widget.NumberPicker.class.getName()).instance(instance));
    }

    public static UiObject getNumberPickerField(int instance)
            throws UiObjectNotFoundException {
        return getNumberPicker(instance).getChild(
                new UiSelector().className(android.widget.EditText.class.getName()));
    }

    public static UiObject getNumberPickerDecrementButton(int instance)
            throws UiObjectNotFoundException {
        return getNumberPicker(instance).getChild(
                new UiSelector().className(android.widget.Button.class.getName()).instance(0));
    }

    public static UiObject getNumberPickerIncrementButton(int instance)
            throws UiObjectNotFoundException {
        return getNumberPicker(instance).getChild(
                new UiSelector().className(android.widget.Button.class.getName()).instance(1));
    }

    public static void clickDone() throws UiObjectNotFoundException {
        new UiObject(new UiSelector().text("Done")).click();
    }

    public static void setDate(Calendar cal) throws UiObjectNotFoundException {
        int calYear = cal.get(Calendar.YEAR);
        int calMonth = cal.get(Calendar.MONTH);
        int calDay = cal.get(Calendar.DAY_OF_MONTH);

        // Adjust day - increment or decrement using the shortest path
        // while accounting for number of days in month and considering
        // special case for Feb and leap years.
        int dpDay = Integer.parseInt(getCurrentDay());
        if (calDay > dpDay) {
            if (calDay - dpDay < getDaysInMonth(calYear, calMonth) / 2)
                incrementDay(calDay - dpDay);
            else
                decrementDay(dpDay - calDay + getDaysInMonth(calYear, calMonth));
        } else if (dpDay > calDay) {
            if (dpDay - calDay < getDaysInMonth(calYear, calMonth) / 2)
                decrementDay(dpDay - calDay);
            else
                incrementDay(calDay - dpDay + getDaysInMonth(calYear, calMonth));
        }

        // Adjust month - increment or decrement using the shortest path
        int dpMonth = toMonthNumber(getCurrentMonth());
        if (calMonth > dpMonth) {
            if (calMonth - dpMonth < 6)
                incrementMonth(calMonth - dpMonth);
            else
                decrementMonth(dpMonth - calMonth + 12);
        } else if (dpMonth > calMonth) {
            if (dpMonth - calMonth < 6)
                decrementMonth(dpMonth - calMonth);
            else
                incrementMonth(calMonth - dpMonth + 12);
        }

        // Adjust year
        int dpYear = Integer.parseInt(getCurrentYear());
        if (calYear > dpYear) {
            incrementYear(calYear - dpYear);
        } else if (dpYear > calYear) {
            decrementYear(dpYear - calYear);
        }
    }

    private static int toMonthNumber(String monthName) {
        String months[] = new String[] {"January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December"};

        for (int x = 0; x < months.length; x++) {
            if (months[x].contains(monthName))
                return x;
        }

        return 0;
    }

    /**
     * Get the number of days in the month
     * @param year
     * @param month
     * @return
     */
    private static int getDaysInMonth(int year, int month) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.YEAR, year);
        cal.set(Calendar.MONTH, month);
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH);
    }
}
