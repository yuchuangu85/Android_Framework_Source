/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import static java.time.temporal.ChronoUnit.SECONDS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.i18n.phonenumbers.NumberParseException;
import com.android.i18n.phonenumbers.PhoneNumberUtil;
import com.android.i18n.phonenumbers.Phonenumber;
import com.android.internal.annotations.VisibleForTesting;
import com.android.libraries.entitlement.ServiceEntitlementException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Manages the logic for fetching a phone number via TS.43 entitlement server.
 * This class handles CarrierConfig changes, network availability, and the retry logic
 * for the TS.43 phone number retrieval process.
 * @hide
 */
public class Ts43PhoneNumberController extends Handler {
    private static final String TAG = "Ts43PhoneNumberController";

    // Event for starting the initial phone number fetch
    private static final int EVENT_PROCESS_TS43_FETCH = 1;
    // Event for retrying the fetch after a failure
    private static final int EVENT_RETRY_TS43_QUERY = 2;

    private static final int HTTP_RESPONSE_500 = 500;
    private static final int HTTP_RESPONSE_503 = 503;

    @NonNull
    private final Context mContext;
    @Nullable
    private final SubscriptionManager mSubscriptionManager;
    @Nullable
    private final ConnectivityManager mConnectivityManager;
    @Nullable
    private final CarrierConfigManager mCarrierConfigManager;
    @NonNull
    private final Ts43PhoneNumberRetriever mTs43PhoneNumberRetriever;
    private boolean mIsInternetConnected = false;

    // Manages the query-in-progress state for each subId.
    @NonNull
    private final SparseArray<Boolean> mIsTs43QueryInProgressPerSub = new SparseArray<>();
    // A set to hold subIds waiting for network.
    @NonNull
    private final Set<Integer> mSubIdsPendingNetwork = new ArraySet<>();
    @Nullable
    private ConnectivityManager.NetworkCallback mNetworkCallback;

    private static final long INITIAL_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(2);
    private static final long MAX_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final int MULTIPLIER = 2;
    private static final int MAX_RETRY_COUNT = 5;

    @NonNull
    private final SparseArray<ExponentialBackoffRetry>
            mExponentialBackoffRetryPerSub = new SparseArray<>();
    @NonNull
    private final SparseIntArray mRetryCountPerSub = new SparseIntArray();


    public Ts43PhoneNumberController(@NonNull Context context) {
        this(context, new Ts43PhoneNumberRetriever(context));
    }

    @VisibleForTesting
    public Ts43PhoneNumberController(@NonNull Context context,
            @NonNull Ts43PhoneNumberRetriever retriever) {
        this(context, null, retriever);
    }

    @VisibleForTesting
    public Ts43PhoneNumberController(@NonNull Context context, @Nullable Looper looper,
            @NonNull Ts43PhoneNumberRetriever retriever) {
        super(looper != null ? looper : createLooper());
        Rlogger.d(TAG, "Ts43PhoneNumberController created.");
        mContext = context;
        mTs43PhoneNumberRetriever = retriever;

        mSubscriptionManager = mContext.getSystemService(SubscriptionManager.class);
        mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
        mCarrierConfigManager = mContext.getSystemService(CarrierConfigManager.class);

        registerCarrierConfigChangeListener();
        initializeNetworkCallback();
    }

    private static Looper createLooper() {
        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        return handlerThread.getLooper();
    }

    @Override
    public void handleMessage(@NonNull Message msg) {
        int subId = msg.arg1;
        boolean isRetry = msg.what == EVENT_RETRY_TS43_QUERY;

        switch (msg.what) {
            case EVENT_PROCESS_TS43_FETCH:
            case EVENT_RETRY_TS43_QUERY:
                processPhoneNumberFetch(subId, isRetry);
                break;
            default:
                Rlogger.d(TAG, "Unknown message: " + msg.what);
        }
    }

    private void registerCarrierConfigChangeListener() {
        if (mCarrierConfigManager == null) {
            Rlogger.d(TAG, "CarrierConfigManager is not available.");
            return;
        }
        CarrierConfigManager.CarrierConfigChangeListener listener =
                (slotIndex, subId, carrierId, specificCarrierId) -> {
                    Rlogger.d(TAG, "CarrierConfig changed for slot: "
                            + slotIndex + ", subId: " + subId);
                    handleCarrierConfigChange(subId);
                };
        mCarrierConfigManager.registerCarrierConfigChangeListener(this::post, listener);
    }

    private void handleCarrierConfigChange(int subId) {
        if (mSubscriptionManager == null || !mSubscriptionManager.isActiveSubscriptionId(subId)) {
            Rlogger.d(TAG, "handleCarrierConfigChange: subId "
                    + subId + " is not active. Ignoring.");
            cleanupAllStatesForSubId(subId);
            return;
        }

        if (!isGetPhoneNumberViaTs43Supported(subId)) {
            cleanupAllStatesForSubId(subId);
            Rlogger.d(TAG, "TS.43 support disabled for subId " + subId
                    + ". No action will be taken.");
            return;
        }

        Rlogger.d(TAG, "Carrier config changed for subId " + subId
                + ". Scheduling fetch process.");
        // Just schedule the task. All logic will be handled on the handler thread.
        sendMessage(obtainMessage(EVENT_PROCESS_TS43_FETCH, subId, 0));
    }

    @SuppressLint("NewApi")
    private void processPhoneNumberFetch(int subId, boolean isRetry) {
        // Check internet connection first.
        if (!isInternetConnected()) {
            Rlogger.d(TAG, "Fetch for " + subId + " paused: no internet. Waiting for network.");
            mSubIdsPendingNetwork.add(subId);
            // If this was a retry, the lock was held, so we must release it.
            if (isRetry) {
                queryCompleted(subId);
            }
            return;
        }

        // new request (not a retry)
        if (!isRetry) {
            if (mIsTs43QueryInProgressPerSub.get(subId, false)) {
                Rlogger.d(TAG, "Fetch process for " + subId + " skipped: already in progress.");
                return;
            }
            mIsTs43QueryInProgressPerSub.put(subId, true);
        }

        // Handle retry-specific logic
        if (isRetry) {
            if (!isRetryAvailable(subId)) {
                Rlogger.d(TAG, "Retry limit reached for " + subId + ".");
                queryCompleted(subId);
                return;
            }
            int currentRetries = mRetryCountPerSub.get(subId, 0);
            mRetryCountPerSub.put(subId, currentRetries + 1);
        }

        Rlogger.d(TAG, "Fetching phone number for subId: " + subId + ", isRetry: " + isRetry);
        try {
            String phoneNumber = mTs43PhoneNumberRetriever.fetchPhoneNumber(subId);
            if (phoneNumber != null && !phoneNumber.isEmpty()) {
                Rlogger.d(TAG, "Successfully retrieved phone number for subId: " + subId);
                String formattedNumber = formatNumber(phoneNumber, subId);
                try {
                    mSubscriptionManager.setTs43PhoneNumber(subId, formattedNumber);
                } catch (java.lang.NoSuchMethodError e) {
                    Rlogger.e(TAG, "setTs43PhoneNumber API does not exist");
                }
            }
            // On success, release the lock and reset all states.
            queryCompleted(subId);
        } catch (ServiceEntitlementException e) {
            Rlogger.e(TAG, "TS.43 fetch failed for subId " + subId + ": " + e);
            handleFetchFailure(subId, e);
        }
    }

    private String formatNumber(@NonNull String number, int subId) {
        if (mSubscriptionManager == null) {
            Rlogger.e(TAG, "SubscriptionManager is not available.");
            return number;
        }
        SubscriptionInfo subInfo = mSubscriptionManager.getActiveSubscriptionInfo(subId);
        if (subInfo == null) {
            Rlogger.e(TAG, "Unable to find active SubscriptionInfo for subId: " + subId);
            return number;
        }
        String countryIso = subInfo.getCountryIso();
        if (TextUtils.isEmpty(countryIso)) {
            Rlogger.e(TAG, "Country ISO is null or empty for subId: " + subId);
            return number;
        }

        try {
            PhoneNumberUtil util = PhoneNumberUtil.getInstance();
            Phonenumber.PhoneNumber phoneNumber = util.parse(number,
                    countryIso.toUpperCase(Locale.ENGLISH));
            return util.format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException e) {
            Rlogger.e(TAG, "Failed to parse phone number for country: " + countryIso
                    + ". Exception: " + e);
            return number;
        }
    }

    private void handleFetchFailure(int subId, @NonNull ServiceEntitlementException e) {
        // Re-check internet in case it dropped during the fetch attempt.
        if (!isInternetConnected()) {
            Rlogger.d(TAG, "Internet disconnected during fetch for subId " + subId);
            mSubIdsPendingNetwork.add(subId);
            queryCompleted(subId); // Release lock.
            return;
        }

        // Handle different error types for retry logic.
        if (isPermanentError(e)) {
            Rlogger.d(TAG, "Permanent error for subId " + subId + ". No more retries.");
            queryCompleted(subId);
        } else if (isRetryAfterError(e)) {
            long retryAfterSeconds = parseSecondsFromRetryAfter(e.getRetryAfter());
            Rlogger.d(TAG, "Retry-After error for subId " + subId
                    + ". Retrying in " + retryAfterSeconds + "s");
            // A retry is scheduled, so we hold the lock.
            stopExponentialBackoffRetry(subId); // Stop any existing backoff.
            sendMessageDelayed(obtainMessage(EVENT_RETRY_TS43_QUERY, subId, 0),
                    TimeUnit.SECONDS.toMillis(retryAfterSeconds));
        } else {
            Rlogger.d(TAG, "Transient error for subId " + subId
                    + ". Scheduling exponential backoff retry.");
            // A retry is scheduled, so we hold the lock.
            startExponentialBackoffRetry(subId);
        }
    }

    private void queryCompleted(int subId) {
        Rlogger.d(TAG, "Query completed for subId: " + subId);
        resetRetryState(subId);
        mIsTs43QueryInProgressPerSub.remove(subId);
    }

    private void resetRetryState(int subId) {
        stopExponentialBackoffRetry(subId);
        mRetryCountPerSub.delete(subId);
    }

    private void cleanupAllStatesForSubId(int subId) {
        // Clean up all states associated with the subId
        mSubIdsPendingNetwork.remove(subId);
        mIsTs43QueryInProgressPerSub.remove(subId);
        resetRetryState(subId);
    }

    private boolean isPermanentError(@NonNull ServiceEntitlementException e) {
        return e.getHttpStatus() == HTTP_RESPONSE_500;
    }

    private boolean isRetryAfterError(@NonNull ServiceEntitlementException e) {
        int responseCode = e.getHttpStatus();
        if (responseCode == HTTP_RESPONSE_503 && e.getRetryAfter() != null
                && !e.getRetryAfter().isEmpty()) {
            return parseSecondsFromRetryAfter(e.getRetryAfter()) > 0;
        }
        return false;
    }

    private long parseSecondsFromRetryAfter(@Nullable String retryAfter) {
        if (retryAfter == null) {
            return -1;
        }
        try {
            return Long.parseLong(retryAfter);
        } catch (NumberFormatException ignored) {
        }
        try {
            return SECONDS.between(Instant.now(),
                    RFC_1123_DATE_TIME.parse(retryAfter, Instant::from));
        } catch (DateTimeParseException ignored) {
        }
        return -1;
    }

    private void startExponentialBackoffRetry(int subId) {
        ExponentialBackoffRetry existingRetryer = mExponentialBackoffRetryPerSub.get(subId);

        if (existingRetryer != null) {
            existingRetryer.notifyFailed();
        } else {
            ExponentialBackoffRetry newRetryer = new ExponentialBackoffRetry(
                    INITIAL_DELAY_MILLIS, MAX_DELAY_MILLIS, MULTIPLIER, getLooper(),
                    () -> sendMessage(obtainMessage(EVENT_RETRY_TS43_QUERY, subId, 0)));
            mExponentialBackoffRetryPerSub.put(subId, newRetryer);
            newRetryer.start();
        }
    }

    private void stopExponentialBackoffRetry(int subId) {
        ExponentialBackoffRetry retryer = mExponentialBackoffRetryPerSub.get(subId);
        if (retryer != null) {
            mExponentialBackoffRetryPerSub.remove(subId);
            retryer.stop();
        }
    }

    private boolean isRetryAvailable(int subId) {
        return mRetryCountPerSub.get(subId, 0) < MAX_RETRY_COUNT;
    }

    private void onNetworkAvailable() {
        Iterator<Integer> it = mSubIdsPendingNetwork.iterator();
        if (!it.hasNext()) {
            return;
        }

        Rlogger.d(TAG, "Network is now available. Processing pending subIds.");
        while (it.hasNext()) {
            Integer subId = it.next();
            it.remove();
            sendMessage(obtainMessage(EVENT_PROCESS_TS43_FETCH, subId, 0));
        }
    }

    private void initializeNetworkCallback() {
        if (mConnectivityManager == null) {
            Rlogger.w(TAG, "ConnectivityManager is not available.");
            return;
        }
        if (mNetworkCallback == null) {
            Rlogger.d(TAG, "Registering network callback.");
            mNetworkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(@NonNull Network network,
                        @NonNull NetworkCapabilities networkCapabilities) {
                    Rlogger.d(TAG, "onCapabilitiesChanged: " + networkCapabilities);
                    boolean wasConnected = mIsInternetConnected;
                    boolean isConnected = networkCapabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                            && networkCapabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
                    mIsInternetConnected = isConnected;

                    if (isConnected && !wasConnected) {
                        onNetworkAvailable();
                    }
                }

                @Override
                public void onLost(@NonNull Network network) {
                    Rlogger.d(TAG, "onLost: " + network);
                    mIsInternetConnected = false;
                }
            };
            mConnectivityManager.registerDefaultNetworkCallback(mNetworkCallback, this);
        }
    }

    private boolean isInternetConnected() {
        return mIsInternetConnected;
    }

    private boolean isGetPhoneNumberViaTs43Supported(int subId) {
        if (mCarrierConfigManager == null) {
            return false;
        }
        PersistableBundle config = mCarrierConfigManager.getConfigForSubId(subId,
                CarrierConfigManager.KEY_SUPPORT_PHONE_NUMBER_SOURCE_TS43_BOOL);
        if (config == null || config.isEmpty()) {
            return false;
        }
        return config.getBoolean(
                CarrierConfigManager.KEY_SUPPORT_PHONE_NUMBER_SOURCE_TS43_BOOL, false);
    }
}
