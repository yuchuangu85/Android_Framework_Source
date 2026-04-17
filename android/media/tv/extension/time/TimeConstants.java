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
package android.media.tv.extension.time;

import android.annotation.StringDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @hide
 */
public final class TimeConstants {
    @StringDef({
            KEY_LOCAL_TIME_OFFSET,
            KEY_TIME_OF_CHANGE,
            KEY_NEXT_TIME_OFFSET
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TimeZoneInfoBundleKey{}
    // The UTC offset for the local time in milliseconds.
    public static final String KEY_LOCAL_TIME_OFFSET = "LOCAL_TIME_OFFSET";
    // The time at which the next offset change will occur.
    public static final String KEY_TIME_OF_CHANGE = "TIME_OF_CHANGE";
    // The next time offset after the time of change.
    public static final String KEY_NEXT_TIME_OFFSET = "NEXT_TIME_OFFSET";
}
