/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi.util;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.WifiLog;

/**
 * This class subclasses Handler to log incoming messages
 */
public class WifiHandler extends Handler {
    private static final String LOG_TAG = "WifiHandler";
    private WifiLog mLog;
    private String mTag;

    public WifiHandler(String tag, Looper looper) {
        super(looper);
        mTag = LOG_TAG + "." + tag;
    }

    @NonNull
    private WifiLog getOrInitLog() {
        // Lazy initialization of mLog
        if (mLog == null) {
            mLog = WifiInjector.getInstance().makeLog(mTag);
        }
        return mLog;
    }

    @Override
    public void handleMessage(Message msg) {
        getOrInitLog().trace("Received message=% sendingUid=%")
                .c(msg.what)
                .c(msg.sendingUid)
                .flush();
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public void setWifiLog(WifiLog wifiLog) {
        // TODO WifiInjector should be passed as a variable in the constructor
        // b/33308811 tracks removing lazy initializations of mLog
        mLog = wifiLog;
    }
}
