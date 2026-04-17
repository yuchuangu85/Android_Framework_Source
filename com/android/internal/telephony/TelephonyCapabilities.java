/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemProperties;

import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.flags.Flags;
import com.android.telephony.Rlog;

/**
 * Utilities that check if the phone supports specified capabilities.
 */
public class TelephonyCapabilities {
    private static final String LOG_TAG = "TelephonyCapabilities";
    // Non-final: overwritten in TelephonyCapabilitiesTest.
    private static int VENDOR_API_LEVEL = SystemProperties.getInt(
            "ro.vendor.api_level", Build.VERSION.DEVICE_INITIAL_SDK_INT);
    private static int BOARD_API_LEVEL = SystemProperties.getInt(
            "ro.board.api_level", VENDOR_API_LEVEL);

    /** This class is never instantiated. */
    private TelephonyCapabilities() {
    }

    /**
     * Return true if the current phone supports ECM ("Emergency Callback
     * Mode"), which is a feature where the device goes into a special
     * state for a short period of time after making an outgoing emergency
     * call.
     *
     * (On current devices, that state lasts 5 minutes.  It prevents data
     * usage by other apps, to avoid conflicts with any possible incoming
     * calls.  It also puts up a notification in the status bar, showing a
     * countdown while ECM is active, and allowing the user to exit ECM.)
     *
     * Currently this is assumed to be true for CDMA phones, and false
     * otherwise.
     */
    public static boolean supportsEcm(Phone phone) {
        Rlog.d(LOG_TAG, "supportsEcm: Phone type = " + phone.getPhoneType() +
                  " Ims Phone = " + phone.getImsPhone());
        return ((!Flags.deleteCdma() && phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA)
                || phone.getImsPhone() != null);
    }

    /**
     * Return true if the current phone supports voice message count.
     * and the count is available
     * Both CDMA and GSM phones support voice message count
     */
    public static boolean supportsVoiceMessageCount(Phone phone) {
        return (phone.getVoiceMessageCount() != -1);
    }

    /**
     * Return true if this phone allows the user to select which
     * network to use.
     *
     * Currently this is assumed to be true only on GSM phones.
     *
     * TODO: Should CDMA phones allow this as well?
     */
    public static boolean supportsNetworkSelection(Phone phone) {
        return (Flags.deleteCdma() || phone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM);
    }

    /**
     * Returns a resource ID for a label to use when displaying the
     * "device id" of the current device.  (This is currently used as the
     * title of the "device id" dialog.)
     *
     * This is specific to the device's telephony technology: the device
     * id is called "IMEI" on GSM phones and "MEID" on CDMA phones.
     */
    public static int getDeviceIdLabel(Phone phone) {
        if (Flags.deleteCdma() || phone.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM) {
            return com.android.internal.R.string.imei;
        } else if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            return com.android.internal.R.string.meid;
        } else {
            Rlog.w(LOG_TAG, "getDeviceIdLabel: no known label for phone "
                  + phone.getPhoneName());
            return 0;
        }
    }

    /**
     * Returns true if Calling/Data/Messaging features should be checked on this device.
     */
    private static boolean minimalTelephonyCdmCheck(@NonNull FeatureFlags featureFlags) {
        // The check for calling/data/messaging features is done using the ro.board.api_level
        // value, which represents the API level of the current vendor partition. It is
        // therefore assumed that a vendor partition that has been upgraded from pre-VIC
        // to VIC must have also been updated to support the new C/D/M feature flags.
        return BOARD_API_LEVEL >= Build.VERSION_CODES.VANILLA_ICE_CREAM;
    }

    /**
     * @return true if this device supports telephony calling, false if it does not.
     */
    public static boolean supportsTelephonyCalling(@NonNull FeatureFlags featureFlags,
            Context context) {
        if (!TelephonyCapabilities.minimalTelephonyCdmCheck(featureFlags)) return true;
        return context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_TELEPHONY_CALLING);
    }

    /**
     * @return true if this device supports telephony messaging, false if it does not.
     */
    public static boolean supportsTelephonyMessaging(@NonNull FeatureFlags featureFlags,
            Context context) {
        if (!TelephonyCapabilities.minimalTelephonyCdmCheck(featureFlags)) return true;
        return context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_TELEPHONY_MESSAGING);
    }

    /**
     * @return true if this device supports telephony data, false if it does not.
     */
    public static boolean supportsTelephonyData(@NonNull FeatureFlags featureFlags,
            Context context) {
        if (!TelephonyCapabilities.minimalTelephonyCdmCheck(featureFlags)) return true;
        return context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_TELEPHONY_DATA);
    }
}
