/**
 * Copyright (C) 2009 The Android Open Source Project
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
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.telephony.Annotation.ApnType;
import android.telephony.CarrierConfigManager;
import android.telephony.data.ApnSetting;
import android.telephony.data.DataCallResponse;
import android.text.TextUtils;
import android.util.Pair;

import com.android.internal.telephony.dataconnection.DataThrottler;
import com.android.internal.telephony.util.TelephonyUtils;
import com.android.telephony.Rlog;

import java.util.ArrayList;
import java.util.Random;

/**
 * Retry manager allows a simple way to declare a series of
 * retry timeouts. After creating a RetryManager the configure
 * method is used to define the sequence. A simple linear series
 * may be initialized using configure with three integer parameters
 * The other configure method allows a series to be declared using
 * a string.
 *<p>
 * The format of the configuration string is the apn type followed by a series of parameters
 * separated by a comma. There are two name value pair parameters plus a series
 * of delay times. The units of of these delay times is unspecified.
 * The name value pairs which may be specified are:
 *<ul>
 *<li>max_retries=<value>
 *<li>default_randomizationTime=<value>
 *</ul>
 *<p>
 * apn type specifies the APN type that the retry pattern will apply for. "others" is for all other
 * APN types not specified in the config.
 *
 * max_retries is the number of times that incrementRetryCount
 * maybe called before isRetryNeeded will return false. if value
 * is infinite then isRetryNeeded will always return true.
 *
 * default_randomizationTime will be used as the randomizationTime
 * for delay times which have no supplied randomizationTime. If
 * default_randomizationTime is not defined it defaults to 0.
 *<p>
 * The other parameters define The series of delay times and each
 * may have an optional randomization value separated from the
 * delay time by a colon.
 *<p>
 * Examples:
 * <ul>
 * <li>3 retries for mms with no randomization value which means its 0:
 * <ul><li><code>"mms:1000, 2000, 3000"</code></ul>
 *
 * <li>10 retries for default APN with a 500 default randomization value for each and
 * the 4..10 retries all using 3000 as the delay:
 * <ul><li><code>"default:max_retries=10, default_randomization=500, 1000, 2000, 3000"</code></ul>
 *
 * <li>4 retries for supl APN with a 100 as the default randomization value for the first 2 values
 * and the other two having specified values of 500:
 * <ul><li><code>"supl:default_randomization=100, 1000, 2000, 4000:500, 5000:500"</code></ul>
 *
 * <li>Infinite number of retries for all other APNs with the first one at 1000, the second at 2000
 * all others will be at 3000.
 * <ul><li><code>"others:max_retries=infinite,1000,2000,3000</code></ul>
 * </ul>
 *
 * {@hide}
 */
public class RetryManager {
    public static final String LOG_TAG = "RetryManager";
    public static final boolean DBG = true;
    public static final boolean VDBG = false; // STOPSHIP if true

    /**
     * The default retry configuration for APNs. See above for the syntax.
     */
    private static final String DEFAULT_DATA_RETRY_CONFIG = "max_retries=3, 5000, 5000, 5000";

    /**
     * The APN type used for all other APNs retry configuration.
     */
    private static final String OTHERS_APN_TYPE = "others";

    /**
     * The default value (in milliseconds) for delay between APN trying (mInterApnDelay)
     * within the same round
     */
    private static final long DEFAULT_INTER_APN_DELAY = 20000;

    /**
     * The default value (in milliseconds) for delay between APN trying (mFailFastInterApnDelay)
     * within the same round when we are in fail fast mode
     */
    private static final long DEFAULT_INTER_APN_DELAY_FOR_PROVISIONING = 3000;

    /**
     * The default value (in milliseconds) for retrying APN after disconnect
     */
    private static final long DEFAULT_APN_RETRY_AFTER_DISCONNECT_DELAY = 10000;

    /**
     * The value indicating retry should not occur.
     */
    public static final long NO_RETRY = Long.MAX_VALUE;

    /**
     * The value indicating network did not suggest any retry delay
     */
    public static final long NO_SUGGESTED_RETRY_DELAY = DataCallResponse.RETRY_DURATION_UNDEFINED;

    /**
     * If the network suggests a retry delay in the data call setup response, we will retry
     * the current APN setting again. The maximum retry count is to prevent that network
     * keeps asking device to retry data setup forever and causes power consumption issue.
     */
    private static final int DEFAULT_MAX_SAME_APN_RETRY = 3;

    /**
     * The delay (in milliseconds) between APN trying within the same round
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private long mInterApnDelay;

    /**
     * The delay (in milliseconds) between APN trying within the same round when we are in
     * fail fast mode
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private long mFailFastInterApnDelay;

    /**
     * The delay (in milliseconds) for APN retrying after disconnect (e.g. Modem suddenly reports
     * data call lost)
     */
    private long mApnRetryAfterDisconnectDelay;

    /**
     * The counter for same APN retrying. See {@link #DEFAULT_MAX_SAME_APN_RETRY} for the details.
     */
    private int mSameApnRetryCount = 0;

    /**
     * The maximum times that frameworks retries data setup with the same APN. This value could be
     * changed via carrier config. See {@link #DEFAULT_MAX_SAME_APN_RETRY} for the details.
     */
    private int mMaxSameApnRetry = DEFAULT_MAX_SAME_APN_RETRY;

    /**
     * Retry record with times in milli-seconds
     */
    private static class RetryRec {
        long mDelayTime;
        long mRandomizationTime;

        RetryRec(long delayTime, long randomizationTime) {
            mDelayTime = delayTime;
            mRandomizationTime = randomizationTime;
        }
    }

    /**
     * The array of retry records
     */
    private ArrayList<RetryRec> mRetryArray = new ArrayList<RetryRec>();

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private Phone mPhone;

    private final DataThrottler mDataThrottler;

    /**
     * Flag indicating whether retrying forever regardless the maximum retry count mMaxRetryCount
     */
    private boolean mRetryForever = false;

    /**
     * The maximum number of retries to attempt
     */
    private int mMaxRetryCount;

    /**
     * The current number of retries
     */
    private int mRetryCount = 0;

    /**
     * Random number generator. The random delay will be added into retry timer to avoid all devices
     * around retrying the APN at the same time.
     */
    private Random mRng = new Random();

    /**
     * Retry manager configuration string. See top of the detailed explanation.
     */
    private String mConfig;

    /**
     * The list to store APN setting candidates for data call setup. Most of the carriers only have
     * one APN, but few carriers have more than one.
     */
    private ArrayList<ApnSetting> mWaitingApns = new ArrayList<>();

    /**
     * Index pointing to the current trying APN from mWaitingApns
     */
    private int mCurrentApnIndex = -1;

    /**
     * Apn context type.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private String mApnType;

    private final @ApnType int apnType;

    /**
     * Retry manager constructor
     * @param phone Phone object
     * @param dataThrottler Data throttler
     * @param apnType APN type
     */
    public RetryManager(@NonNull Phone phone, @NonNull DataThrottler dataThrottler,
            @ApnType int apnType) {
        mPhone = phone;
        mDataThrottler = dataThrottler;
        this.apnType = apnType;
    }

    /**
     * Configure for using string which allow arbitrary
     * sequences of times. See class comments for the
     * string format.
     *
     * @return true if successful
     */
    @UnsupportedAppUsage
    private boolean configure(String configStr) {
        // Strip quotes if present.
        if ((configStr.startsWith("\"") && configStr.endsWith("\""))) {
            configStr = configStr.substring(1, configStr.length() - 1);
        }

        // Reset the retry manager since delay, max retry count, etc...will be reset.
        reset();

        if (DBG) log("configure: '" + configStr + "'");
        mConfig = configStr;

        if (!TextUtils.isEmpty(configStr)) {
            long defaultRandomization = 0;

            if (VDBG) log("configure: not empty");

            String strArray[] = configStr.split(",");
            for (int i = 0; i < strArray.length; i++) {
                if (VDBG) log("configure: strArray[" + i + "]='" + strArray[i] + "'");
                Pair<Boolean, Integer> value;
                String splitStr[] = strArray[i].split("=", 2);
                splitStr[0] = splitStr[0].trim();
                if (VDBG) log("configure: splitStr[0]='" + splitStr[0] + "'");
                if (splitStr.length > 1) {
                    splitStr[1] = splitStr[1].trim();
                    if (VDBG) log("configure: splitStr[1]='" + splitStr[1] + "'");
                    if (TextUtils.equals(splitStr[0], "default_randomization")) {
                        value = parseNonNegativeInt(splitStr[0], splitStr[1]);
                        if (!value.first) return false;
                        defaultRandomization = value.second;
                    } else if (TextUtils.equals(splitStr[0], "max_retries")) {
                        if (TextUtils.equals("infinite", splitStr[1])) {
                            mRetryForever = true;
                        } else {
                            value = parseNonNegativeInt(splitStr[0], splitStr[1]);
                            if (!value.first) return false;
                            mMaxRetryCount = value.second;
                        }
                    } else {
                        Rlog.e(LOG_TAG, "Unrecognized configuration name value pair: "
                                        + strArray[i]);
                        return false;
                    }
                } else {
                    /**
                     * Assume a retry time with an optional randomization value
                     * following a ":"
                     */
                    splitStr = strArray[i].split(":", 2);
                    splitStr[0] = splitStr[0].trim();
                    RetryRec rr = new RetryRec(0, 0);
                    value = parseNonNegativeInt("delayTime", splitStr[0]);
                    if (!value.first) return false;
                    rr.mDelayTime = value.second;

                    // Check if optional randomization value present
                    if (splitStr.length > 1) {
                        splitStr[1] = splitStr[1].trim();
                        if (VDBG) log("configure: splitStr[1]='" + splitStr[1] + "'");
                        value = parseNonNegativeInt("randomizationTime", splitStr[1]);
                        if (!value.first) return false;
                        rr.mRandomizationTime = value.second;
                    } else {
                        rr.mRandomizationTime = defaultRandomization;
                    }
                    mRetryArray.add(rr);
                }
            }
            if (mRetryArray.size() > mMaxRetryCount) {
                mMaxRetryCount = mRetryArray.size();
                if (VDBG) log("configure: setting mMaxRetryCount=" + mMaxRetryCount);
            }
        } else {
            log("configure: cleared");
        }

        if (VDBG) log("configure: true");
        return true;
    }

    /**
     * Configure the retry manager
     */
    private void configureRetry() {
        String configString = null;
        String otherConfigString = null;

        try {
            if (TelephonyUtils.IS_DEBUGGABLE) {
                // Using system properties is easier for testing from command line.
                String config = SystemProperties.get("test.data_retry_config");
                if (!TextUtils.isEmpty(config)) {
                    configure(config);
                    return;
                }
            }

            CarrierConfigManager configManager = (CarrierConfigManager)
                    mPhone.getContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
            PersistableBundle b = configManager.getConfigForSubId(mPhone.getSubId());

            mInterApnDelay = b.getLong(
                    CarrierConfigManager.KEY_CARRIER_DATA_CALL_APN_DELAY_DEFAULT_LONG,
                    DEFAULT_INTER_APN_DELAY);
            mFailFastInterApnDelay = b.getLong(
                    CarrierConfigManager.KEY_CARRIER_DATA_CALL_APN_DELAY_FASTER_LONG,
                    DEFAULT_INTER_APN_DELAY_FOR_PROVISIONING);
            mApnRetryAfterDisconnectDelay = b.getLong(
                    CarrierConfigManager.KEY_CARRIER_DATA_CALL_APN_RETRY_AFTER_DISCONNECT_LONG,
                    DEFAULT_APN_RETRY_AFTER_DISCONNECT_DELAY);
            mMaxSameApnRetry = b.getInt(
                    CarrierConfigManager
                            .KEY_CARRIER_DATA_CALL_RETRY_NETWORK_REQUESTED_MAX_COUNT_INT,
                    DEFAULT_MAX_SAME_APN_RETRY);

            // Load all retry patterns for all different APNs.
            String[] allConfigStrings = b.getStringArray(
                    CarrierConfigManager.KEY_CARRIER_DATA_CALL_RETRY_CONFIG_STRINGS);
            if (allConfigStrings != null) {
                for (String s : allConfigStrings) {
                    if (!TextUtils.isEmpty(s)) {
                        String splitStr[] = s.split(":", 2);
                        if (splitStr.length == 2) {
                            String apnTypeStr = splitStr[0].trim();
                            // Check if this retry pattern is for the APN we want.
                            if (apnTypeStr.equals(ApnSetting.getApnTypeString(apnType))) {
                                // Extract the config string. Note that an empty string is valid
                                // here, meaning no retry for the specified APN.
                                configString = splitStr[1];
                                break;
                            } else if (apnTypeStr.equals(OTHERS_APN_TYPE)) {
                                // Extract the config string. Note that an empty string is valid
                                // here, meaning no retry for all other APNs.
                                otherConfigString = splitStr[1];
                            }
                        }
                    }
                }
            }

            if (configString == null) {
                if (otherConfigString != null) {
                    configString = otherConfigString;
                } else {
                    // We should never reach here. If we reach here, it must be a configuration
                    // error bug.
                    log("Invalid APN retry configuration!. Use the default one now.");
                    configString = DEFAULT_DATA_RETRY_CONFIG;
                }
            }
        } catch (NullPointerException ex) {
            // We should never reach here unless there is a bug
            log("Failed to read configuration! Use the hardcoded default value.");

            mInterApnDelay = DEFAULT_INTER_APN_DELAY;
            mFailFastInterApnDelay = DEFAULT_INTER_APN_DELAY_FOR_PROVISIONING;
            configString = DEFAULT_DATA_RETRY_CONFIG;
        }

        if (VDBG) {
            log("mInterApnDelay = " + mInterApnDelay + ", mFailFastInterApnDelay = " +
                    mFailFastInterApnDelay);
        }

        configure(configString);
    }

    /**
     * Return the timer that should be used to trigger the data reconnection
     */
    @UnsupportedAppUsage
    private long getRetryTimer() {
        int index;
        if (mRetryCount < mRetryArray.size()) {
            index = mRetryCount;
        } else {
            index = mRetryArray.size() - 1;
        }

        long retVal;
        if ((index >= 0) && (index < mRetryArray.size())) {
            retVal = mRetryArray.get(index).mDelayTime + nextRandomizationTime(index);
        } else {
            retVal = 0;
        }

        if (DBG) log("getRetryTimer: " + retVal);
        return retVal;
    }

    /**
     * Parse an integer validating the value is not negative.
     * @param name Name
     * @param stringValue Value
     * @return Pair.first == true if stringValue an integer >= 0
     */
    private Pair<Boolean, Integer> parseNonNegativeInt(String name, String stringValue) {
        int value;
        Pair<Boolean, Integer> retVal;
        try {
            value = Integer.parseInt(stringValue);
            retVal = new Pair<>(validateNonNegativeInt(name, value), value);
        } catch (NumberFormatException e) {
            Rlog.e(LOG_TAG, name + " bad value: " + stringValue, e);
            retVal = new Pair<>(false, 0);
        }
        if (VDBG) {
            log("parseNonNegativeInt: " + name + ", " + stringValue + ", "
                    + retVal.first + ", " + retVal.second);
        }
        return retVal;
    }

    /**
     * Validate an integer is >= 0 and logs an error if not
     * @param name Name
     * @param value Value
     * @return Pair.first
     */
    private boolean validateNonNegativeInt(String name, long value) {
        boolean retVal;
        if (value < 0) {
            Rlog.e(LOG_TAG, name + " bad value: is < 0");
            retVal = false;
        } else {
            retVal = true;
        }
        if (VDBG) log("validateNonNegative: " + name + ", " + value + ", " + retVal);
        return retVal;
    }

    /**
     * Return next random number for the index
     * @param index Retry index
     */
    private long nextRandomizationTime(int index) {
        long randomTime = mRetryArray.get(index).mRandomizationTime;
        if (randomTime == 0) {
            return 0;
        } else {
            return mRng.nextInt((int) randomTime);
        }
    }

    private long getNetworkSuggestedRetryDelay() {
        long retryElapseTime = mDataThrottler.getRetryTime(apnType);
        if (retryElapseTime == NO_RETRY || retryElapseTime == NO_SUGGESTED_RETRY_DELAY) {
            return retryElapseTime;
        }

        // The time from data throttler is system's elapsed time. We need to return the delta. If
        // less than 0, then return 0 (i.e. retry immediately).
        return Math.max(0, retryElapseTime - SystemClock.elapsedRealtime());
    }

    /**
     * Get the next APN setting for data call setup.
     * @return APN setting to try. {@code null} if cannot find any APN,
     */
    public @Nullable ApnSetting getNextApnSetting() {
        if (mWaitingApns == null || mWaitingApns.size() == 0) {
            log("Waiting APN list is null or empty.");
            return null;
        }

        long networkSuggestedRetryDelay = getNetworkSuggestedRetryDelay();
        if (networkSuggestedRetryDelay == NO_RETRY) {
            log("Network suggested no retry.");
            return null;
        }

        // If the network had suggested a retry delay, we should retry the current APN again
        // (up to mMaxSameApnRetry times) instead of getting the next APN setting from
        // our own list. If the APN waiting list has been reset before a setup data responses
        // arrive (i.e. mCurrentApnIndex=-1), then ignore the network suggested retry.
        if (mCurrentApnIndex != -1 && networkSuggestedRetryDelay != NO_SUGGESTED_RETRY_DELAY
                && mSameApnRetryCount < mMaxSameApnRetry) {
            mSameApnRetryCount++;
            return mWaitingApns.get(mCurrentApnIndex);
        }

        mSameApnRetryCount = 0;

        int index = mCurrentApnIndex;
        // Loop through the APN list to find out the index of next non-permanent failed APN.
        while (true) {
            if (++index == mWaitingApns.size()) index = 0;

            // Stop if we find the non-failed APN.
            if (!mWaitingApns.get(index).getPermanentFailed()) {
                break;
            }

            // If all APNs have permanently failed, bail out.
            if (mWaitingApns.stream().allMatch(ApnSetting::getPermanentFailed)) {
                return null;
            }
        }

        mCurrentApnIndex = index;
        return mWaitingApns.get(mCurrentApnIndex);
    }

    /**
     * Get the delay for trying the next waiting APN from the list.
     * @param failFastEnabled True if fail fast mode enabled. In this case we'll use a shorter
     *                        delay.
     * @return delay in milliseconds
     */
    public long getDelayForNextApn(boolean failFastEnabled) {

        if (mWaitingApns == null || mWaitingApns.size() == 0) {
            log("Waiting APN list is null or empty.");
            return NO_RETRY;
        }

        long networkSuggestedDelay = getNetworkSuggestedRetryDelay();
        log("Network suggested delay=" + networkSuggestedDelay + "ms");

        if (networkSuggestedDelay == NO_RETRY) {
            log("Network suggested not retrying.");
            return NO_RETRY;
        }

        if (networkSuggestedDelay != NO_SUGGESTED_RETRY_DELAY
                && mSameApnRetryCount < mMaxSameApnRetry) {
            // If the network explicitly suggests a retry delay, we should use it, even in fail fast
            // mode.
            log("Network suggested retry in " + networkSuggestedDelay + " ms.");
            return networkSuggestedDelay;
        }

        // In order to determine the delay to try next APN, we need to peek the next available APN.
        // Case 1 - If we will start the next round of APN trying,
        //    we use the exponential-growth delay. (e.g. 5s, 10s, 30s...etc.)
        // Case 2 - If we are still within the same round of APN trying,
        //    we use the fixed standard delay between APNs. (e.g. 20s)

        int index = mCurrentApnIndex;
        while (true) {
            if (++index >= mWaitingApns.size()) index = 0;

            // Stop if we find the non-failed APN.
            if (!mWaitingApns.get(index).getPermanentFailed()) {
                break;
            }

            // If all APNs have permanently failed, bail out.
            if (mWaitingApns.stream().allMatch(ApnSetting::getPermanentFailed)) {
                log("All APNs have permanently failed.");
                return NO_RETRY;
            }
        }

        long delay;
        if (index <= mCurrentApnIndex) {
            // Case 1, if the next APN is in the next round.
            if (!mRetryForever && mRetryCount + 1 > mMaxRetryCount) {
                log("Reached maximum retry count " + mMaxRetryCount + ".");
                return NO_RETRY;
            }
            delay = getRetryTimer();
            ++mRetryCount;
        } else {
            // Case 2, if the next APN is still in the same round.
            delay = mInterApnDelay;
        }

        if (failFastEnabled && delay > mFailFastInterApnDelay) {
            // If we enable fail fast mode, and the delay we got is longer than
            // fail-fast delay (mFailFastInterApnDelay), use the fail-fast delay.
            // If the delay we calculated is already shorter than fail-fast delay,
            // then ignore fail-fast delay.
            delay = mFailFastInterApnDelay;
        }

        return delay;
    }

    /**
     * Mark the APN setting permanently failed.
     * @param apn APN setting to be marked as permanently failed
     * */
    public void markApnPermanentFailed(ApnSetting apn) {
        if (apn != null) {
            apn.setPermanentFailed(true);
        }
    }

    /**
     * Reset the retry manager.
     */
    private void reset() {
        mMaxRetryCount = 0;
        mRetryCount = 0;
        mCurrentApnIndex = -1;
        mSameApnRetryCount = 0;
        mRetryArray.clear();
    }

    /**
     * Set waiting APNs for retrying in case needed.
     * @param waitingApns Waiting APN list
     */
    public void setWaitingApns(ArrayList<ApnSetting> waitingApns) {

        if (waitingApns == null) {
            log("No waiting APNs provided");
            return;
        }

        mWaitingApns = waitingApns;

        // Since we replace the entire waiting APN list, we need to re-config this retry manager.
        configureRetry();

        for (ApnSetting apn : mWaitingApns) {
            apn.setPermanentFailed(false);
        }

        log("Setting " + mWaitingApns.size() + " waiting APNs.");

        if (VDBG) {
            for (int i = 0; i < mWaitingApns.size(); i++) {
                log("  [" + i + "]:" + mWaitingApns.get(i));
            }
        }
    }

    /**
     * Get the list of waiting APNs.
     * @return the list of waiting APNs
     */
    public @NonNull ArrayList<ApnSetting> getWaitingApns() {
        return mWaitingApns;
    }

    /**
     * Get the delay in milliseconds for APN retry after disconnect
     * @return The delay in milliseconds
     */
    public long getRetryAfterDisconnectDelay() {
        return mApnRetryAfterDisconnectDelay;
    }

    public String toString() {
        if (mConfig == null) return "";
        return "RetryManager: apnType=" + ApnSetting.getApnTypeString(apnType)
                + " mRetryCount="
                + mRetryCount + " mMaxRetryCount=" + mMaxRetryCount + " mCurrentApnIndex="
                + mCurrentApnIndex + " mSameApnRtryCount=" + mSameApnRetryCount
                + " networkSuggestedDelay=" + getNetworkSuggestedRetryDelay() + " mRetryForever="
                + mRetryForever + " mInterApnDelay=" + mInterApnDelay
                + " mApnRetryAfterDisconnectDelay=" + mApnRetryAfterDisconnectDelay
                + " mConfig={" + mConfig + "}";
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void log(String s) {
        Rlog.d(LOG_TAG, "[" + ApnSetting.getApnTypeString(apnType) + "] " + s);
    }
}
