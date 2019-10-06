/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.telephony.SubscriptionManager;
import android.util.Log;

import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.telephony.nano.TelephonyProto.TelephonyEvent;

/**
 * This class will validate whether cellular network verified by Connectivity's
 * validation process. It listens request on a specific subId, sends a network request
 * to Connectivity and listens to its callback or timeout.
 */
public class CellularNetworkValidator {
    private static final String LOG_TAG = "NetworkValidator";

    // States of validator. Only one validation can happen at once.
    // IDLE: no validation going on.
    private static final int STATE_IDLE                = 0;
    // VALIDATING: validation going on.
    private static final int STATE_VALIDATING          = 1;
    // VALIDATED: validation is done and successful.
    // Waiting for stopValidation() to release
    // validationg NetworkRequest.
    private static final int STATE_VALIDATED           = 2;

    // Singleton instance.
    private static CellularNetworkValidator sInstance;

    private int mState = STATE_IDLE;
    private int mSubId;
    private int mTimeoutInMs;
    private boolean mReleaseAfterValidation;

    private NetworkRequest mNetworkRequest;
    private ValidationCallback mValidationCallback;
    private Context mContext;
    private ConnectivityManager mConnectivityManager;
    private Handler mHandler = new Handler();
    private ConnectivityNetworkCallback mNetworkCallback;

    /**
     * Callback to pass in when starting validation.
     */
    public interface ValidationCallback {
        /**
         * Validation failed, passed or timed out.
         */
        void onValidationResult(boolean validated, int subId);
    }

    /**
     * Create instance.
     */
    public static CellularNetworkValidator make(Context context) {
        if (sInstance != null) {
            logd("createCellularNetworkValidator failed. Instance already exists.");
        } else {
            sInstance = new CellularNetworkValidator(context);
        }

        return sInstance;
    }

    /**
     * Get instance.
     */
    public static CellularNetworkValidator getInstance() {
        return sInstance;
    }

    /**
     * Check whether this feature is supported or not.
     */
    public boolean isValidationFeatureSupported() {
        return PhoneConfigurationManager.getInstance().getCurrentPhoneCapability()
                .validationBeforeSwitchSupported;
    }

    private CellularNetworkValidator(Context context) {
        mContext = context;
        mConnectivityManager = (ConnectivityManager)
                mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /**
     * API to start a validation
     */
    public synchronized void validate(int subId, int timeoutInMs,
            boolean releaseAfterValidation, ValidationCallback callback) {
        // If it's already validating the same subscription, do nothing.
        if (subId == mSubId) return;

        Phone phone = PhoneFactory.getPhone(SubscriptionManager.getPhoneId(subId));
        if (phone == null) {
            logd("Failed to start validation. Inactive subId " + subId);
            callback.onValidationResult(false, subId);
            return;
        }

        if (isValidating()) {
            stopValidation();
        }

        mState = STATE_VALIDATING;
        mSubId = subId;
        mTimeoutInMs = timeoutInMs;
        mValidationCallback = callback;
        mReleaseAfterValidation = releaseAfterValidation;
        mNetworkRequest = createNetworkRequest();

        logd("Start validating subId " + mSubId + " mTimeoutInMs " + mTimeoutInMs
                + " mReleaseAfterValidation " + mReleaseAfterValidation);

        mNetworkCallback = new ConnectivityNetworkCallback(subId);

        mConnectivityManager.requestNetwork(
                mNetworkRequest, mNetworkCallback, mHandler, mTimeoutInMs);
    }

    /**
     * API to stop the current validation.
     */
    public synchronized void stopValidation() {
        if (!isValidating()) {
            logd("No need to stop validation.");
        } else {
            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
            mState = STATE_IDLE;
        }
        mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    /**
     * Return which subscription is under validating.
     */
    public synchronized int getSubIdInValidation() {
        return mSubId;
    }

    /**
     * Return whether there's an ongoing validation.
     */
    public synchronized boolean isValidating() {
        return mState != STATE_IDLE;
    }

    private NetworkRequest createNetworkRequest() {
        return new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .setNetworkSpecifier(String.valueOf(mSubId))
                .build();
    }

    private synchronized void reportValidationResult(boolean passed, int subId) {
        // If the validation result is not for current subId, do nothing.
        if (mSubId != subId) return;

        // Deal with the result only when state is still VALIDATING. This is to avoid
        // receiving multiple callbacks in queue.
        if (mState == STATE_VALIDATING) {
            mValidationCallback.onValidationResult(passed, mSubId);
            if (!mReleaseAfterValidation && passed) {
                mState = STATE_VALIDATED;
            } else {
                mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
                mState = STATE_IDLE;
            }

            TelephonyMetrics.getInstance().writeNetworkValidate(passed
                    ? TelephonyEvent.NetworkValidationState.NETWORK_VALIDATION_STATE_PASSED
                    : TelephonyEvent.NetworkValidationState.NETWORK_VALIDATION_STATE_FAILED);
        }

        mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    class ConnectivityNetworkCallback extends ConnectivityManager.NetworkCallback {
        private final int mSubId;

        ConnectivityNetworkCallback(int subId) {
            mSubId = subId;
        }
        /**
         * ConnectivityManager.NetworkCallback implementation
         */
        @Override
        public void onAvailable(Network network) {
            logd("network onAvailable " + network);
            if (ConnectivityNetworkCallback.this.mSubId == CellularNetworkValidator.this.mSubId) {
                TelephonyMetrics.getInstance().writeNetworkValidate(
                        TelephonyEvent.NetworkValidationState.NETWORK_VALIDATION_STATE_AVAILABLE);
            }
        }

        @Override
        public void onLosing(Network network, int maxMsToLive) {
            logd("network onLosing " + network + " maxMsToLive " + maxMsToLive);
            reportValidationResult(false, ConnectivityNetworkCallback.this.mSubId);
        }

        @Override
        public void onLost(Network network) {
            logd("network onLost " + network);
            reportValidationResult(false, ConnectivityNetworkCallback.this.mSubId);
        }

        @Override
        public void onUnavailable() {
            logd("onUnavailable");
            reportValidationResult(false, ConnectivityNetworkCallback.this.mSubId);
        }

        @Override
        public void onCapabilitiesChanged(Network network,
                NetworkCapabilities networkCapabilities) {
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                logd("onValidated");
                reportValidationResult(true, ConnectivityNetworkCallback.this.mSubId);
            }
        }
    }

    private static void logd(String log) {
        Log.d(LOG_TAG, log);
    }
}
