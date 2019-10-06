/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wifi.hotspot2;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Build;
import android.os.SystemProperties;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.TelephonyProperties;
import com.android.server.wifi.WifiNative;

import java.util.Locale;

/**
 * Provide APIs for retrieving system information, so that they can be mocked for unit tests.
 */
public class SystemInfo {
    public static final String TAG = "SystemInfo";
    public static final String UNKNOWN_INFO = "Unknown";

    private final TelephonyManager mTelephonyManager;
    private final WifiNative mWifiNative;
    private static SystemInfo sSystemInfo = null;

    @VisibleForTesting
    SystemInfo(Context context, WifiNative wifiNative) {
        // TODO(b/132188983): inject this using WifiInjector
        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mWifiNative = wifiNative;
    }

    public static SystemInfo getInstance(@NonNull Context context, @NonNull WifiNative wifiNative) {
        if (sSystemInfo == null) {
            sSystemInfo = new SystemInfo(context, wifiNative);
        }
        return sSystemInfo;
    }

    /**
     * Get the system language.
     *
     * @return current language code
     */
    public String getLanguage() {
        return Locale.getDefault().getLanguage();
    }

    /**
     * Get the device manufacturer info
     *
     * @return the device manufacturer info or {@link Build#UNKNOWN} if not set
     */
    public String getDeviceManufacturer() {
        return Build.MANUFACTURER;
    }

    /**
     * Get the device model info.
     *
     * @return the device model info or {@link Build#UNKNOWN} if not set
     */
    public String getDeviceModel() {
        return Build.MODEL;
    }

    /**
     * Get the Wifi Mac address for primary interface.
     *
     * TODO(b/80092273): need to check if this privacy information is required for Passpoint R2.
     * @param ifaceName Name of the interface.
     * @return string containing the MAC address or null on a failed call
     */
    public String getMacAddress(@NonNull String ifaceName) {
        return mWifiNative.getMacAddress(ifaceName);
    }

    /**
     * Get the device ID.  Either IMEI or MEID will be returned based on the installed SIM.
     * {@link #UNKNOWN_INFO} will be returned if no SIM is installed.
     *
     * @return String representing device ID
     */
    public String getDeviceId() {
        TelephonyManager defaultDataTm = mTelephonyManager.createForSubscriptionId(
                SubscriptionManager.getDefaultDataSubscriptionId());
        // IMEI will be provided for GSM SIM.
        String imei = defaultDataTm.getImei();
        if (!TextUtils.isEmpty(imei)) {
            return imei;
        }

        // MEID will be provided for CMDA SIM.
        String meid = defaultDataTm.getMeid();
        if (!TextUtils.isEmpty(meid)) {
            return meid;
        }
        return UNKNOWN_INFO;
    }

    /**
     * Get the hardware version.
     *
     * TODO(b/80092273): need to check if this privacy information is required for Passpoint R2.
     * @return the version that consists of hardware name and revision number.
     */
    public String getHwVersion() {
        return Build.HARDWARE + "." + SystemProperties.get("ro.revision", "0");
    }

    /**
     * Get the software version.
     *
     * @return the build release version.
     */
    public String getSoftwareVersion() {
         return new StringBuffer("Android ").append(Build.VERSION.RELEASE).toString();
    }

    /**
     * Get the firmware version.
     *
     * @return the version that consists of build id and baseband version.
     */
    public String getFirmwareVersion() {
        return new StringBuffer(Build.ID)
                .append("/")
                .append(SystemProperties.get(TelephonyProperties.PROPERTY_BASEBAND_VERSION,
                        UNKNOWN_INFO)).toString();
    }
}
