/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.telephony.vendor;

import android.content.Context;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.SubscriptionInfo;
import android.util.Log;
import com.android.internal.telephony.GlobalSettingsHelper;
import com.android.internal.telephony.MultiSimSettingController;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

import java.util.List;
import java.util.stream.Collectors;

/*
 * Extending VendorMultiSimSettingController to override default
 * behavior for mobile data
 */
public class VendorMultiSimSettingController extends MultiSimSettingController {

    private static final String LOG_TAG = "VendorMultiSimSettingController";

    public static MultiSimSettingController init(Context context, SubscriptionController sc) {
        synchronized (VendorMultiSimSettingController.class) {
            if (sInstance == null) {
                sInstance = new VendorMultiSimSettingController(context,
                        SubscriptionController.getInstance());
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
        }
        return sInstance;
    }

    private VendorMultiSimSettingController(Context context, SubscriptionController sc) {
        super(context, sc);
    }

    public static VendorMultiSimSettingController getInstance() {
        return (VendorMultiSimSettingController)sInstance;
    }

    @Override
    protected void disableDataForNonDefaultNonOpportunisticSubscriptions() {
        log("disableDataForNonDefaultNonOpportunisticSubscriptions - do nothing");
    }

    protected synchronized void onUserDataEnabled(int subId, boolean enable) {
        log("onUserDataEnabled");
        // Make sure MOBILE_DATA of subscriptions in same group are synced.
        setUserDataEnabledForGroup(subId, enable);
    }

    /**
     * Make sure MOBILE_DATA of subscriptions in the same group with the subId
     * are synced.
     */
    @Override
    protected synchronized void setUserDataEnabledForGroup(int subId, boolean enable) {
        log("setUserDataEnabledForGroup subId " + subId + " enable " + enable);
        List<SubscriptionInfo> infoList = mSubController.getSubscriptionsInGroup(
                mSubController.getGroupUuid(subId), mContext.getOpPackageName(),
                null);

        if (infoList == null) return;

        for (SubscriptionInfo info : infoList) {
            int currentSubId = info.getSubscriptionId();
            if (currentSubId == subId) continue;
            // TODO: simplify when setUserDataEnabled becomes singleton
            if (mSubController.isActiveSubId(currentSubId)) {
                // For active subscription, call setUserDataEnabled through DataEnabledSettings.
                Phone phone = PhoneFactory.getPhone(mSubController.getPhoneId(currentSubId));
                if (phone != null) {
                    phone.getDataEnabledSettings().setUserDataEnabled(enable);
                }
            } else {
                // For inactive subscription, directly write into global settings.
                GlobalSettingsHelper.setBoolean(
                        mContext, Settings.Global.MOBILE_DATA, currentSubId, enable);
            }
        }
   }

   @Override
   protected void updateDefaults() {
        log("updateDefaults");

   }

    protected void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
