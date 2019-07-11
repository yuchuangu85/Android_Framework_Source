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
import java.util.Locale;

/**
 * Use this helper anywhere there is a time picker to manage. This helper
 * will set time specified in a Calendar object.
 */
public class TimePickerHelper {

    public static final int HOUR = 0;
    public static final int MINUTE = 1;
    public static final int MERIDIEM = 2;

    public static String getCurrentHour() throws UiObjectNotFoundException {
        return getNumberPickerField(HOUR).getText();
    }

    public static String getCurrentMinute() throws UiObjectNotFoundException {
        return getNumberPickerField(MINUTE).getText();
    }

    public static String getCurrentMeridiem() throws UiObjectNotFoundException {
        return getNumberPickerField(MERIDIEM).getText();
    }


    public static void incrementHour() throws UiObjectNotFoundException {
        incrementHour(1);
    }

    public static void incrementHour(int count) throws UiObjectNotFoundException {
        for (int x = 0; x < count; x++)
            getNumberPickerIncrementButton(HOUR).click();
    }

    public static void decrementHour() throws UiObjectNotFoundException {
        decrementHour(1);
    }

    public static void decrementHour(int count) throws UiObjectNotFoundException {
        for (int x = 0; x < count; x++)
            getNumberPickerDecrementButton(HOUR).click();
    }

    public static void incrementMinute() throws UiObjectNotFoundException {
        incrementMinute(1);
    }

    public static void incrementMinute(int count) throws UiObjectNotFoundException {
        for (int x = 0; x < count; x++)
            getNumberPickerIncrementButton(MINUTE).click();
    }

    public static void decrementMinute() throws UiObjectNotFoundException {
        decrementMinute(1);
    }

    public static void decrementMinute(int count) throws UiObjectNotFoundException {
        for (int x = 0; x < count; x++)
            getNumberPickerDecrementButton(MINUTE).click();
    }

    public static void selectPM() throws UiObjectNotFoundException {
        getNumberPicker(MERIDIEM).getChild(new UiSelector().text("PM")).click();
    }

    public static void selectAM() throws UiObjectNotFoundException {
        getNumberPicker(MERIDIEM).getChild(new UiSelector().text("AM")).click();
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

    public static void setTime(Calendar cal) throws UiObjectNotFoundException {
        // Adjust minutes - increment or decrement using the shortest path
        int tpMinute = Integer.parseInt(getCurrentMinute());
        int calMinute = cal.get(Calendar.MINUTE);
        if (calMinute > tpMinute) {
            if (calMinute - tpMinute < 30)
                incrementMinute(calMinute - tpMinute);
            else
                decrementMinute(tpMinute - calMinute + 60);
        } else if (tpMinute > calMinute) {
            if (tpMinute - calMinute < 30)
                decrementMinute(tpMinute - calMinute);
            else
                incrementMinute(calMinute - tpMinute + 60);
        }

        // Adjust hour - increment or decrement using the shortest path
        int tpHour = Integer.parseInt(getCurrentHour());
        int calHour = cal.get(Calendar.HOUR);
        if (calHour > tpHour) {
            if (calHour - tpHour < 6)
                incrementHour(calHour - tpHour);
            else
                decrementHour(tpHour - calHour + 12);
        } else if (tpHour > calHour) {
            if (tpHour - calHour < 6)
                decrementHour(tpHour - calHour);
            else
                incrementHour(calHour - tpHour + 12);
        }

        // Adjust meridiem
        String calMer = cal.getDisplayName(Calendar.AM_PM, Calendar.SHORT, Locale.US);
        String tpMer = getCurrentMeridiem();
        if (tpMer.equalsIgnoreCase(calMer))
            return;

        if (!calMer.equalsIgnoreCase("AM")) {
            selectPM();
        } else {
            selectAM();
        }
    }
}
