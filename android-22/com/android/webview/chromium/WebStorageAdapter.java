/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.webkit.ValueCallback;
import android.webkit.WebStorage;

import java.util.HashMap;
import java.util.Map;

import org.chromium.android_webview.AwQuotaManagerBridge;

/**
 * Chromium implementation of WebStorage -- forwards calls to the
 * chromium internal implementation.
 */
final class WebStorageAdapter extends WebStorage {
    private final AwQuotaManagerBridge mQuotaManagerBridge;
    WebStorageAdapter(AwQuotaManagerBridge quotaManagerBridge) {
        mQuotaManagerBridge = quotaManagerBridge;
    }

    @Override
    public void getOrigins(final ValueCallback<Map> callback) {
        mQuotaManagerBridge.getOrigins(new ValueCallback<AwQuotaManagerBridge.Origins>() {
            @Override
            public void onReceiveValue(AwQuotaManagerBridge.Origins origins) {
                Map<String, Origin> originsMap = new HashMap<String, Origin>();
                for (int i = 0; i < origins.mOrigins.length; ++i) {
                    Origin origin = new Origin(origins.mOrigins[i], origins.mQuotas[i],
                            origins.mUsages[i]) {
                        // Intentionally empty to work around cross-package protected visibility
                        // of Origin constructor.
                    };
                    originsMap.put(origins.mOrigins[i], origin);
                }
                callback.onReceiveValue(originsMap);
            }
        });
    }

    @Override
    public void getUsageForOrigin(String origin, ValueCallback<Long> callback) {
        mQuotaManagerBridge.getUsageForOrigin(origin, callback);
    }

    @Override
    public void getQuotaForOrigin(String origin, ValueCallback<Long> callback) {
        mQuotaManagerBridge.getQuotaForOrigin(origin, callback);
    }

    @Override
    public void setQuotaForOrigin(String origin, long quota) {
        // Intentional no-op for deprecated method.
    }

    @Override
    public void deleteOrigin(String origin) {
        mQuotaManagerBridge.deleteOrigin(origin);
    }

    @Override
    public void deleteAllData() {
        mQuotaManagerBridge.deleteAllData();
    }
}
