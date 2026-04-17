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

import android.annotation.IntDef;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Defines failure codes for handoff task data requests.
 * @hide
 */
public final class HandoffFailureCode {

    private HandoffFailureCode() {}

    /** @hide */
    @IntDef(prefix = { "HANDOFF_FAILURE_" }, value = {
            HANDOFF_FAILURE_TIMEOUT,
            HANDOFF_FAILURE_UNSUPPORTED_DEVICE,
            HANDOFF_FAILURE_UNKNOWN_TASK,
            HANDOFF_FAILURE_INTERNAL_ERROR,
            HANDOFF_FAILURE_EMPTY_TASK,
            HANDOFF_FAILURE_UNSUPPORTED_TASK,
            HANDOFF_FAILURE_APP_DID_NOT_REPORT_HANDOFF_DATA,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FailureCode {}

    /** The handoff task data request timed out. */
    public static final int HANDOFF_FAILURE_TIMEOUT = 1;
    /** The device does not support handoff. */
    public static final int HANDOFF_FAILURE_UNSUPPORTED_DEVICE = 2;
    /** The handoff task data request was a task that no longer exists. */
    public static final int HANDOFF_FAILURE_UNKNOWN_TASK = 3;
    /** An internal error occurred while handling the handoff task data request. For example,
     * the app process died during a handoff request. */
    public static final int HANDOFF_FAILURE_INTERNAL_ERROR = 4;
    /** The handoff task data request was for an empty task. */
    public static final int HANDOFF_FAILURE_EMPTY_TASK = 5;
    /** The handoff task data request was for a task whose top activity does not support handoff. */
    public static final int HANDOFF_FAILURE_UNSUPPORTED_TASK = 6;
    /** Handoff task data was not reported by the app, despite the app supporting handoff. */
    public static final int HANDOFF_FAILURE_APP_DID_NOT_REPORT_HANDOFF_DATA = 7;
}