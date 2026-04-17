/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.app.Flags;

/**
 * Helper for reading or using the update ranking time flag state.
 *
 * @hide
 */
public class SetNotificationBackgroundColorRefactor {
    /** The aconfig flag name */
    public static final String FLAG_NAME = Flags.FLAG_NOTIFICATION_TRANSPARENT_BADGE_RING;

    /**
     * Is the flag enabled.
     *
     * @hide
     */
    public static boolean isEnabled() {
        return Flags.notificationTransparentBadgeRing();
    }

    /**
     * Called to ensure code is only run when the flag is disabled. This will throw an exception if
     * the flag is enabled to ensure that the refactor author catches issues in testing.
     *
     * @hide
     */
    public static void assertInLegacyMode() {
        if (isEnabled()) {
            throw new IllegalStateException(
                    "Legacy code path not supported when " + FLAG_NAME + " is enabled.");
        }
    }

    private SetNotificationBackgroundColorRefactor() {}
}