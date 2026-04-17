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

package android.app;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 *  Defines the types of Application Not Responding (ANR) errors.
 */
@FlaggedApi(Flags.FLAG_INCLUDE_ANR_INFO)
public final class AnrTypes {

    private AnrTypes() {}

    /**
     * The ANR type is not one of the other defined types.
     *
     * <p>This is used as a default when the specific cause of the ANR is not known or does not fit
     * into a more specific category
     */
    public static final int ANR_TYPE_OTHER = 0;

    /**
     * The app took too long to respond to an input event because no window was focused.
     */
    public static final int ANR_TYPE_INPUT_DISPATCH_NO_FOCUSED_WINDOW = 1;

    /**
     * The app took too long to respond to an input event.
     */
    public static final int ANR_TYPE_INPUT_DISPATCH = 2;

    /**
     * The app's broadcast receiver took too long to process the message.
     */
    public static final int ANR_TYPE_BROADCAST_OF_INTENT = 3;

    /**
     * The foreground service took too long to start.
     */
    public static final int ANR_TYPE_START_FOREGROUND_SERVICE = 4;

    /**
     * The app's service took too long to finish {@link Service#onCreate} and {@link
     * Service#onStartCommand} / {@link Service#onBind}
     */
    public static final int ANR_TYPE_EXECUTE_SERVICE = 5;

    /**
     * The app's content provider took too long to respond.
     */
    public static final int ANR_TYPE_CONTENT_PROVIDER_NOT_RESPONDING = 6;

    /**
     * The app itself due to its own internal logic or behavior has triggered an ANR.
     */
    public static final int ANR_TYPE_APP_TRIGGERED = 7;

    /** A foreground short service took too long to respond to {@link Service#onTimeout}. */
    public static final int ANR_TYPE_FOREGROUND_SHORT_SERVICE_TIMEOUT = 8;

    /**
     * The job service took too long to start.
     */
    public static final int ANR_TYPE_JOB_SERVICE_START = 9;

    /**
     * The app took too long to start up.
     */
    public static final int ANR_TYPE_APPLICATION_START = 10;

    /** @hide */
    @IntDef(
            prefix = {"ANR_TYPE_"},
            value = {
                ANR_TYPE_OTHER,
                ANR_TYPE_INPUT_DISPATCH_NO_FOCUSED_WINDOW,
                ANR_TYPE_INPUT_DISPATCH,
                ANR_TYPE_BROADCAST_OF_INTENT,
                ANR_TYPE_START_FOREGROUND_SERVICE,
                ANR_TYPE_EXECUTE_SERVICE,
                ANR_TYPE_CONTENT_PROVIDER_NOT_RESPONDING,
                ANR_TYPE_APP_TRIGGERED,
                ANR_TYPE_FOREGROUND_SHORT_SERVICE_TIMEOUT,
                ANR_TYPE_JOB_SERVICE_START,
                ANR_TYPE_APPLICATION_START,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AnrType {}
}
