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

package com.android.internal.telephony;

import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;

import com.android.telephony.Rlog;

import java.util.List;

/**
 * This is a basic utility class for common Carrier SMS Functions
 */
public class CarrierSmsUtils {
    protected static final boolean VDBG = false;
    protected static final String TAG = CarrierSmsUtils.class.getSimpleName();

    private static final String CARRIER_IMS_PACKAGE_KEY =
            CarrierConfigManager.KEY_CONFIG_IMS_PACKAGE_OVERRIDE_STRING;

    /** Return a Carrier-overridden IMS package, if it exists and is a CarrierSmsFilter
     *
     * @param context calling context
     * @param phone object from telephony
     * @param intent that should match a CarrierSmsFilter
     * @return the name of the IMS CarrierService package
     */
    @Nullable
    public static String getCarrierImsPackageForIntent(
            Context context, Phone phone, Intent intent) {

        String carrierImsPackage = getCarrierImsPackage(context, phone);
        if (carrierImsPackage == null) {
            if (VDBG) Rlog.v(TAG, "No CarrierImsPackage override found");
            return null;
        }

        PackageManager packageManager = context.getPackageManager();
        List<ResolveInfo> receivers = packageManager.queryIntentServices(intent, 0);
        for (ResolveInfo info : receivers) {
            if (info.serviceInfo == null) {
                Rlog.e(TAG, "Can't get service information from " + info);
                continue;
            }

            if (carrierImsPackage.equals(info.serviceInfo.packageName)) {
                return carrierImsPackage;
            }
        }
        return null;
    }

    @Nullable
    private static String getCarrierImsPackage(Context context, Phone phone) {
        CarrierConfigManager cm = (CarrierConfigManager) context.getSystemService(
                Context.CARRIER_CONFIG_SERVICE);
        if (cm == null) {
            Rlog.e(TAG, "Failed to retrieve CarrierConfigManager");
            return null;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            PersistableBundle config = cm.getConfigForSubId(phone.getSubId());
            if (config == null) {
                if (VDBG) Rlog.v(TAG, "No CarrierConfig for subId:" + phone.getSubId());
                return null;
            }
            return config.getString(CARRIER_IMS_PACKAGE_KEY, null);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private CarrierSmsUtils() {}
}
