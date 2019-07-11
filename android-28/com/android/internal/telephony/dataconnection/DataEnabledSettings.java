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

package com.android.internal.telephony.dataconnection;


import android.content.ContentResolver;
import android.os.Handler;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.LocalLog;
import android.util.Pair;

import com.android.internal.telephony.Phone;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * The class to hold different data enabled/disabled settings. Also it allows clients to register
 * for overall data enabled setting changed event.
 * @hide
 */
public class DataEnabledSettings {

    private static final String LOG_TAG = "DataEnabledSettings";

    public static final int REASON_REGISTERED = 0;

    public static final int REASON_INTERNAL_DATA_ENABLED = 1;

    public static final int REASON_USER_DATA_ENABLED = 2;

    public static final int REASON_POLICY_DATA_ENABLED = 3;

    public static final int REASON_DATA_ENABLED_BY_CARRIER = 4;

    /**
     * responds to the setInternalDataEnabled call - used internally to turn off data.
     * For example during emergency calls
     */
    private boolean mInternalDataEnabled = true;

    /**
     * Flag indicating data allowed by network policy manager or not.
     */
    private boolean mPolicyDataEnabled = true;

    /**
     * Indicate if metered APNs are enabled by the carrier. set false to block all the metered APNs
     * from continuously sending requests, which causes undesired network load.
     */
    private boolean mCarrierDataEnabled = true;

    private Phone mPhone = null;
    private ContentResolver mResolver = null;

    private final RegistrantList mDataEnabledChangedRegistrants = new RegistrantList();

    private final LocalLog mSettingChangeLocalLog = new LocalLog(50);

    @Override
    public String toString() {
        return "[mInternalDataEnabled=" + mInternalDataEnabled
                + ", isUserDataEnabled=" + isUserDataEnabled()
                + ", isProvisioningDataEnabled=" + isProvisioningDataEnabled()
                + ", mPolicyDataEnabled=" + mPolicyDataEnabled
                + ", mCarrierDataEnabled=" + mCarrierDataEnabled + "]";
    }

    public DataEnabledSettings(Phone phone) {
        mPhone = phone;
        mResolver = mPhone.getContext().getContentResolver();
    }

    public synchronized void setInternalDataEnabled(boolean enabled) {
        localLog("InternalDataEnabled", enabled);
        boolean prevDataEnabled = isDataEnabled();
        mInternalDataEnabled = enabled;
        if (prevDataEnabled != isDataEnabled()) {
            notifyDataEnabledChanged(!prevDataEnabled, REASON_INTERNAL_DATA_ENABLED);
        }
    }
    public synchronized boolean isInternalDataEnabled() {
        return mInternalDataEnabled;
    }

    public synchronized void setUserDataEnabled(boolean enabled) {
        localLog("UserDataEnabled", enabled);
        boolean prevDataEnabled = isDataEnabled();

        Settings.Global.putInt(mResolver, getMobileDataSettingName(), enabled ? 1 : 0);

        if (prevDataEnabled != isDataEnabled()) {
            notifyDataEnabledChanged(!prevDataEnabled, REASON_USER_DATA_ENABLED);
        }
    }
    public synchronized boolean isUserDataEnabled() {
        boolean defaultVal = "true".equalsIgnoreCase(SystemProperties.get(
                "ro.com.android.mobiledata", "true"));

        return (Settings.Global.getInt(mResolver, getMobileDataSettingName(),
                defaultVal ? 1 : 0) != 0);
    }

    private String getMobileDataSettingName() {
        // For single SIM phones, this is a per phone property. Or if it's invalid subId, we
        // read default setting.
        int subId = mPhone.getSubId();
        if (TelephonyManager.getDefault().getSimCount() == 1
                || !SubscriptionManager.isValidSubscriptionId(subId)) {
            return Settings.Global.MOBILE_DATA;
        } else {
            return Settings.Global.MOBILE_DATA + mPhone.getSubId();
        }
    }

    public synchronized void setPolicyDataEnabled(boolean enabled) {
        localLog("PolicyDataEnabled", enabled);
        boolean prevDataEnabled = isDataEnabled();
        mPolicyDataEnabled = enabled;
        if (prevDataEnabled != isDataEnabled()) {
            notifyDataEnabledChanged(!prevDataEnabled, REASON_POLICY_DATA_ENABLED);
        }
    }
    public synchronized boolean isPolicyDataEnabled() {
        return mPolicyDataEnabled;
    }

    public synchronized void setCarrierDataEnabled(boolean enabled) {
        localLog("CarrierDataEnabled", enabled);
        boolean prevDataEnabled = isDataEnabled();
        mCarrierDataEnabled = enabled;
        if (prevDataEnabled != isDataEnabled()) {
            notifyDataEnabledChanged(!prevDataEnabled, REASON_DATA_ENABLED_BY_CARRIER);
        }
    }
    public synchronized boolean isCarrierDataEnabled() {
        return mCarrierDataEnabled;
    }

    public synchronized boolean isDataEnabled() {
        if (isProvisioning()) {
            return isProvisioningDataEnabled();
        } else {
            return mInternalDataEnabled && isUserDataEnabled()
                    && mPolicyDataEnabled && mCarrierDataEnabled;
        }
    }

    public boolean isProvisioning() {
        return Settings.Global.getInt(mResolver, Settings.Global.DEVICE_PROVISIONED, 0) == 0;
    }
    /**
     * In provisioning, we might want to have enable mobile data during provisioning. It depends
     * on value of Settings.Global.DEVICE_PROVISIONING_MOBILE_DATA_ENABLED which is set by
     * setupwizard. It only matters if it's in provisioning stage.
     * @return whether we are enabling userData during provisioning stage.
     */
    public boolean isProvisioningDataEnabled() {
        final String prov_property = SystemProperties.get("ro.com.android.prov_mobiledata",
                "false");
        boolean retVal = "true".equalsIgnoreCase(prov_property);

        final int prov_mobile_data = Settings.Global.getInt(mResolver,
                Settings.Global.DEVICE_PROVISIONING_MOBILE_DATA_ENABLED,
                retVal ? 1 : 0);
        retVal = prov_mobile_data != 0;
        log("getDataEnabled during provisioning retVal=" + retVal + " - (" + prov_property
                + ", " + prov_mobile_data + ")");

        return retVal;
    }

    private void notifyDataEnabledChanged(boolean enabled, int reason) {
        mDataEnabledChangedRegistrants.notifyResult(new Pair<>(enabled, reason));
    }

    public void registerForDataEnabledChanged(Handler h, int what, Object obj) {
        mDataEnabledChangedRegistrants.addUnique(h, what, obj);
        notifyDataEnabledChanged(isDataEnabled(), REASON_REGISTERED);
    }

    public void unregisterForDataEnabledChanged(Handler h) {
        mDataEnabledChangedRegistrants.remove(h);
    }

    private void log(String s) {
        Rlog.d(LOG_TAG, "[" + mPhone.getPhoneId() + "]" + s);
    }

    private void localLog(String name, boolean value) {
        mSettingChangeLocalLog.log(name + " change to " + value);
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(" DataEnabledSettings=");
        mSettingChangeLocalLog.dump(fd, pw, args);
    }
}
