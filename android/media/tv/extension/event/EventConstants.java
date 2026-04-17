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
package android.media.tv.extension.event;

import android.annotation.IntDef;
import android.annotation.StringDef;
import android.media.tv.extension.servicedb.ServicedbConstants;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @hide
 */
public final class EventConstants {
    @IntDef({
            RESULT_SUCCESS,
            RESULT_CANCELED,
            RESULT_ERROR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventResult {}
    public static final int RESULT_SUCCESS = 0;
    public static final int RESULT_CANCELED = 1;
    public static final int RESULT_ERROR = -1;

    @IntDef({
            FLOW_BARKER,
            FLOW_SEQUENTIAL,
            FLOW_NULL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FlowType {}
    public static final int FLOW_BARKER = 1;
    public static final int FLOW_SEQUENTIAL = 2;
    public static final int FLOW_NULL = 0;  // Indicate event failed
    /************************************** Event Download ****************************************/
    @StringDef({
            KEY_PRIORITY_HINT_USE_CASE_TYPE,
            KEY_SERVICE_INFO_ID,
            KEY_SERVICE_LIST_IDS,
            KEY_SERVICE_LIST_TYPES
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventDownloadKeys {}
    // Value should be one of the {@link TvInputService.PriorityHintUseCaseType}
    public static final String KEY_PRIORITY_HINT_USE_CASE_TYPE = "KEY_PRIORITY_HINT_USE_CASE_TYPE";
    // The row id for a service record.
    public static final String KEY_SERVICE_INFO_ID = ServicedbConstants.KEY_SERVICE_INFO_ID;
    // A list of service list ids, values from {@link TvContract.Channels#COLUMN_CHANNEL_LIST_ID}
    public static final String KEY_SERVICE_LIST_IDS = "KEY_SERVICE_LIST_IDS";
    // A list of service list types, values from {@link ServicedbConstants.ServiceListType}
    public static final String KEY_SERVICE_LIST_TYPES = "KEY_SERVICE_LIST_TYPES";

    /************************************* Event Information **************************************/
    @StringDef({
            KEY_EVENT_ID,
            KEY_START_TIME_UTC_MILLIS,
            KEY_END_TIME_UTC_MILLIS,
            KEY_SHORT_DESCRIPTION,
            KEY_LONG_DESCRIPTION,
            KEY_CONTENT_RATING,
            KEY_BROADCAST_GENRE,
            KEY_GUIDANCE_TEXT,
            KEY_TITLE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PresentEventInfoKeys {}
    // EventId should follow {@link ScanConstants.EventId}
    public static final String KEY_EVENT_ID = "KEY_EVENT_ID";
    public static final String KEY_START_TIME_UTC_MILLIS = "KEY_START_TIME_UTC_MILLIS";
    public static final String KEY_END_TIME_UTC_MILLIS = "KEY_END_TIME_UTC_MILLIS";
    public static final String KEY_SHORT_DESCRIPTION = "KEY_SHORT_DESCRIPTION";
    public static final String KEY_LONG_DESCRIPTION = "KEY_LONG_DESCRIPTION";
    public static final String KEY_CONTENT_RATING = "KEY_CONTENT_RATING";
    public static final String KEY_BROADCAST_GENRE = "KEY_BROADCAST_GENRE";
    public static final String KEY_GUIDANCE_TEXT = "KEY_GUIDANCE_TEXT";
    public static final String KEY_TITLE = "KEY_TITLE";

    /*************************************** SDT Guidance *****************************************/
    @StringDef({
            KEY_GUIDANCE_TEXT,
            KEY_IS_RUNNING,
            KEY_IS_SCRAMBLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SdtGuidanceKeys {}
    // boolean to indicate if the service is currently broadcasting.
    public static final String KEY_IS_RUNNING = "KEY_IS_RUNNING";
    // boolean to indicate if the service is encrypted/scrambled.
    public static final String KEY_IS_SCRAMBLED = "KEY_IS_SCRAMBLED";
}
