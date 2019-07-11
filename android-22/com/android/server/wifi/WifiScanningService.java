/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server.wifi;


import android.content.Context;
import android.util.Log;

import com.android.server.SystemService;

public class WifiScanningService extends SystemService {

    private static final String TAG = "WifiScanningService";
    WifiScanningServiceImpl mImpl;

    public WifiScanningService(Context context) {
        super(context);
        Log.i(TAG, "Creating " + Context.WIFI_SCANNING_SERVICE);
    }

    @Override
    public void onStart() {
        mImpl = new WifiScanningServiceImpl(getContext());

        Log.i(TAG, "Starting " + Context.WIFI_SCANNING_SERVICE);
        publishBinderService(Context.WIFI_SCANNING_SERVICE, mImpl);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            Log.i(TAG, "Registering " + Context.WIFI_SCANNING_SERVICE);
            if (mImpl == null) {
                mImpl = new WifiScanningServiceImpl(getContext());
            }
            mImpl.startService(getContext());
        }
    }
}
