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

package com.android.server.wifi.rtt;

import android.content.Context;
import android.net.wifi.aware.IWifiAwareManager;
import android.os.HandlerThread;
import android.os.ServiceManager;
import android.util.Log;

import com.android.server.SystemService;
import com.android.server.wifi.HalDeviceManager;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.util.WifiPermissionsUtil;

/**
 * TBD.
 */
public class RttService extends SystemService {
    private static final String TAG = "RttService";
    private Context mContext;
    private RttServiceImpl mImpl;

    public RttService(Context context) {
        super(context);
        mContext = context;
        mImpl = new RttServiceImpl(context);
    }

    @Override
    public void onStart() {
        Log.i(TAG, "Registering " + Context.WIFI_RTT_RANGING_SERVICE);
        publishBinderService(Context.WIFI_RTT_RANGING_SERVICE, mImpl);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            Log.i(TAG, "Starting " + Context.WIFI_RTT_RANGING_SERVICE);

            WifiInjector wifiInjector = WifiInjector.getInstance();
            if (wifiInjector == null) {
                Log.e(TAG, "onBootPhase(PHASE_SYSTEM_SERVICES_READY): NULL injector!");
                return;
            }

            HalDeviceManager halDeviceManager = wifiInjector.getHalDeviceManager();
            HandlerThread handlerThread = wifiInjector.getRttHandlerThread();
            WifiPermissionsUtil wifiPermissionsUtil = wifiInjector.getWifiPermissionsUtil();
            RttMetrics rttMetrics = wifiInjector.getWifiMetrics().getRttMetrics();

            IWifiAwareManager awareBinder = (IWifiAwareManager) ServiceManager.getService(
                    Context.WIFI_AWARE_SERVICE);

            RttNative rttNative = new RttNative(mImpl, halDeviceManager);
            mImpl.start(handlerThread.getLooper(), wifiInjector.getClock(), awareBinder, rttNative,
                    rttMetrics, wifiPermissionsUtil, wifiInjector.getFrameworkFacade());
        }
    }
}
