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

package com.android.internal.app;

import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.annotation.IntDef;
import android.annotation.UserIdInt;
import android.content.Intent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Contract class for building the {@code SET_SCREEN_LOCK_PROMPT_ACTION} intent.
 *
 * <p>This class defines the intent action, extras, and launch reasons for handling the {@code
 * SET_SCREEN_LOCK_PROMPT_ACTION} action which is intended to launch a dialog prompting the user to
 * set a screen lock.
 */
public final class SetScreenLockDialogContract {

    public static final String EXTRA_LAUNCH_REASON = "launch_reason";

    /**
     * User id associated with the workflow that wants to launch the prompt to set up the screen
     * lock
     */
    public static final String EXTRA_ORIGIN_USER_ID = "origin_user_id";

    private static final String SET_SCREEN_LOCK_PROMPT_ACTION =
            "com.android.internal.app.ScreenLockDialogContract.SET_SCREEN_LOCK_PROMPT_ACTION";
    private static final String SETTINGS_PACKAGE = "com.android.settings";

    @IntDef(
            prefix = "LAUNCH_REASON_",
            value = {
                LAUNCH_REASON_UNKNOWN,
                LAUNCH_REASON_DISABLE_QUIET_MODE,
                LAUNCH_REASON_PRIVATE_SPACE_SETTINGS_ACCESS,
                LAUNCH_REASON_RESET_PRIVATE_SPACE_SETTINGS_ACCESS,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LaunchReason {}

    public static final int LAUNCH_REASON_UNKNOWN = -1;
    public static final int LAUNCH_REASON_DISABLE_QUIET_MODE = 1;
    public static final int LAUNCH_REASON_PRIVATE_SPACE_SETTINGS_ACCESS = 2;
    public static final int LAUNCH_REASON_RESET_PRIVATE_SPACE_SETTINGS_ACCESS = 3;

    /** Returns an intent to display the screen lock dialog */
    public static Intent createDialogIntent(@LaunchReason int launchReason) {
        Intent intent = new Intent(SET_SCREEN_LOCK_PROMPT_ACTION);
        // Allow only the settings app to receive the intent.
        intent.setPackage(SETTINGS_PACKAGE);
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        intent.putExtra(EXTRA_LAUNCH_REASON, launchReason);
        return intent;
    }

    /** Returns an intent to display the screen lock dialog with a user specific message. */
    public static Intent createUserSpecificDialogIntent(@LaunchReason int launchReason,
            @UserIdInt int originUserId) {
        Intent intent = createDialogIntent(launchReason);
        intent.putExtra(EXTRA_ORIGIN_USER_ID, originUserId);
        return intent;
    }
}
