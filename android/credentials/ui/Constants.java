/*
 * Copyright 2022 The Android Open Source Project
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

package android.credentials.ui;

/**
 * Constants for the ui protocol that doesn't fit into other individual data structures.
 *
 * @hide
 */
public class Constants {

    /**
     * The intent extra key for the {@code ResultReceiver} object when launching the UX activities.
     */
    public static final String EXTRA_RESULT_RECEIVER =
            "android.credentials.ui.extra.RESULT_RECEIVER";

    /** The intent action for when the enabled Credential Manager providers has been updated. */
    public static final String CREDMAN_ENABLED_PROVIDERS_UPDATED =
            "android.credentials.ui.action.CREDMAN_ENABLED_PROVIDERS_UPDATED";
}
