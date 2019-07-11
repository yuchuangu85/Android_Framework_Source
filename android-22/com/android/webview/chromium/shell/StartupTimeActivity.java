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

package com.android.webview.chromium.shell;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.WebView;

/**
 * This activity is designed for startup time testing of the WebView.
 */
public class StartupTimeActivity extends Activity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setTitle(
                getResources().getString(R.string.title_activity_startup_time));

        long t1 = SystemClock.elapsedRealtime();
        WebView webView = new WebView(this);
        setContentView(webView);
        long t2 = SystemClock.elapsedRealtime();
        android.util.Log.i("WebViewShell", "WebViewStartupTimeMillis=" + (t2 - t1));
    }

}

