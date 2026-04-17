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

package com.android.adservices.shared.common.exception;

/** This class provides constants for the AdServices deprecation relevance */
public class AdServicesDeprecationConstants {
    public static final String TOPICS_SERVICE_DEPRECATION_MESSAGE =
            "TopicsService APIs are deprecated and no longer functional";
    public static final String AD_SELECTION_SERVICE_DEPRECATION_MESSAGE =
            "AdSelectionService APIs are deprecated and no longer functional";
    public static final String CUSTOM_AUDIENCE_SERVICE_DEPRECATION_MESSAGE =
            "CustomAudienceService APIs are deprecated and no longer functional";
    public static final String PROTECTED_SIGNALS_SERVICE_DEPRECATION_MESSAGE =
            "ProtectedSignalsService APIs are deprecated and no longer functional";

    private AdServicesDeprecationConstants() {}
}
