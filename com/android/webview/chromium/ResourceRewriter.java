/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.webview.chromium;

import android.content.Context;

/**
 * Helper class used to fix up resource ids.
 * This is mostly a copy of the code in frameworks/base/core/java/android/app/LoadedApk.java.
 * TODO: Remove if a cleaner mechanism is provided (either public API or AAPT is changed to generate
 * this code).
 */
class ResourceRewriter {

    /**
     * Rewrite the R 'constants' for the WebView library apk.
     */
    public static void rewriteRValues(final int packageId) {
        // TODO: We should use jarjar to remove the redundant R classes here, but due
        // to a bug in jarjar it's not possible to rename classes with '$' in their name.
        // See b/15684775.
        com.android.webview.chromium.R.onResourcesLoaded(packageId);
        org.chromium.ui.R.onResourcesLoaded(packageId);
        org.chromium.content.R.onResourcesLoaded(packageId);
    }
}
