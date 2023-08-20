/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.car.setupwizardlib.util;

import static android.app.Activity.RESULT_FIRST_USER;

/**
 * Wizard result codes that map to the appropriate Activity/FragmentActivity result codes
 */
public final class ResultCodes {
    public static final int RESULT_SKIP = RESULT_FIRST_USER;
    public static final int RESULT_RETRY = RESULT_FIRST_USER + 1;
    public static final int RESULT_ACTIVITY_NOT_FOUND = RESULT_FIRST_USER + 2;
    public static final int RESULT_LIFECYCLE_NOT_MATCHED = RESULT_FIRST_USER + 3;
    public static final int RESULT_FLOW_NOT_MATCHED = RESULT_FIRST_USER + 4;

    public static final int RESULT_FIRST_SETUP_USER = RESULT_FIRST_USER + 100;

    private ResultCodes() {
    }
}
