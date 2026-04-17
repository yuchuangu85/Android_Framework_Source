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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;

import androidx.annotation.RequiresApi;

import com.android.internal.annotations.VisibleForTesting;
import com.android.libraries.entitlement.ServiceEntitlementException;
import com.android.libraries.entitlement.ServiceEntitlementRequest;
import com.android.libraries.entitlement.Ts43Authentication;
import com.android.libraries.entitlement.Ts43Authentication.Ts43AuthToken;
import com.android.libraries.entitlement.Ts43Operation;
import com.android.libraries.entitlement.odsa.GetPhoneNumberOperation.GetPhoneNumberRequest;
import com.android.libraries.entitlement.odsa.GetPhoneNumberOperation.GetPhoneNumberResponse;
import com.android.libraries.entitlement.utils.Ts43Constants;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * A class that demonstrates the entire phone number retrieval flow,
 * strictly based on the APIs provided by Ts43Authentication.java and
 * Ts43Operation.java within the service_entitlement library. This version
 * has been corrected for accuracy.
 *
 * @hide
 */
public class Ts43PhoneNumberRetriever {
    private static final String TAG = "Ts43PhoneNumberRetriever";
    private static final String TS43_ENTITLEMENT_VERSION = "11.0";

    @NonNull
    private final Context mContext;
    @NonNull
    private final Ts43Factory mTs43Factory;
    @Nullable
    private final SubscriptionManager mSubscriptionManager;
    @Nullable
    private final CarrierConfigManager mCarrierConfigManager;

    /**
     * Constructor for TS43PhoneNumberRetriever.
     *
     * @param context The application context.
     */
    public Ts43PhoneNumberRetriever(@NonNull Context context) {
        this(context, new Ts43Factory());
    }

    @VisibleForTesting
    public Ts43PhoneNumberRetriever(@NonNull Context context, @NonNull Ts43Factory factory) {
        this.mContext = context;
        this.mSubscriptionManager = context.getSystemService(SubscriptionManager.class);
        this.mCarrierConfigManager = context.getSystemService(CarrierConfigManager.class);
        mTs43Factory = factory;
    }

    /**
     * Factory class for creating TS.43 related objects.
     * This allows for dependency injection during tests.
     */
    @VisibleForTesting
    public static class Ts43Factory {
        /**
         * create createTs43Authentication instance
         */
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        @NonNull
        public Ts43Authentication createTs43Authentication(@NonNull Context context,
                @NonNull URL serverUrl) {
            return new Ts43Authentication(context, serverUrl, TS43_ENTITLEMENT_VERSION);
        }

        /**
         * create createTs43Operation instance
         */
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        @NonNull
        public Ts43Operation createTs43Operation(@NonNull Context context, @NonNull URL serverUrl,
                int slotIndex, @NonNull String token) {
            return Ts43Operation.builder()
                    .setContext(context)
                    .setSlotIndex(slotIndex)
                    .setEntitlementServerAddress(serverUrl)
                    .setInitialAuthToken(token)
                    .setEntitlementVersion(TS43_ENTITLEMENT_VERSION)
                    .build();
        }
    }

    /**
     * Starts the entire phone number retrieval flow.
     *
     * @return The retrieved phone number (MSISDN), or null if it fails.
     * @throws ServiceEntitlementException if a specific, recoverable error occurs that the caller
     *         (PhoneNumberManagerService) can handle with retry logic.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Nullable
    public String fetchPhoneNumber(int subId) throws ServiceEntitlementException {
        try {
            // Step 1: Get serverUrl from CarrierConfig
            URL serverUrl = getServerUrl(subId);
            if (serverUrl == null) {
                Rlogger.e(TAG, "Step 1 FAILED: Could not determine server URL.");
                return null;
            }
            Rlogger.d(TAG, "Step 1 SUCCESS: Server URL is " + serverUrl);

            // Step 2: Perform EAP-AKA authentication
            Ts43Authentication ts43Authenticator = mTs43Factory.createTs43Authentication(mContext,
                    serverUrl);

            SubscriptionInfo subInfo = mSubscriptionManager.getActiveSubscriptionInfo(subId);
            if (subInfo == null) {
                Rlogger.e(TAG, "Failed to get active SubscriptionInfo for subId: " + subId);
                return null;
            }

            Rlogger.d(TAG, "Step 2: Calling getAuthToken to start EAP-AKA authentication...");
            Ts43AuthToken initialAuthToken = ts43Authenticator.getAuthToken(
                    subInfo.getSimSlotIndex(),
                    Ts43Constants.APP_PHONE_NUMBER_INFORMATION, null, null,
                    ServiceEntitlementRequest.ACCEPT_CONTENT_TYPE_XML
            );

            if (initialAuthToken == null || TextUtils.isEmpty(initialAuthToken.token())) {
                Rlogger.e(TAG, "Step 2 FAILED: Could not get initial auth token.");
                return null;
            }
            Rlogger.d(TAG, "Step 2 SUCCESS: Got initial auth token.");

            // Step 3: Use the initial token to get the phone number
            String msisdn = getPhoneNumberWithToken(serverUrl,
                    subInfo.getSimSlotIndex(), initialAuthToken);
            if (msisdn == null) {
                Rlogger.e(TAG, "Step 3 FAILED: Could not get phone number using the token.");
                return null;
            }

            Rlogger.d(TAG, "Step 3 SUCCESS: Phone Number");
            return msisdn;

        } catch (ServiceEntitlementException e) {
            Rlogger.w(TAG, "Propagating ServiceEntitlementException." + e.getErrorCode());
            throw e;
        } catch (Exception e) {
            Rlogger.e(TAG, "An unexpected exception occurred during phone number retrieval." + e);
            return null;
        }
    }

    @Nullable
    private URL getServerUrl(int subId) {
        PersistableBundle config = mCarrierConfigManager.getConfigForSubId(subId,
                CarrierConfigManager.ImsServiceEntitlement.KEY_ENTITLEMENT_SERVER_URL_STRING);
        if (config == null) return null;
        String urlString = config.getString(
                CarrierConfigManager.ImsServiceEntitlement.KEY_ENTITLEMENT_SERVER_URL_STRING);
        Rlogger.d(TAG, "getServerUrl  urlString " + urlString);

        if (TextUtils.isEmpty(urlString)) {
            return null;
        }
        try {
            return new URL(urlString);
        } catch (MalformedURLException e) {
            Rlogger.e(TAG, "Invalid URL string: " + urlString);
            return null;
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Nullable
    private String getPhoneNumberWithToken(URL serverUrl, int slotIndex,
            @NonNull Ts43AuthToken initialAuthToken)
            throws ServiceEntitlementException {
        Ts43Operation ts43Operation = mTs43Factory.createTs43Operation(mContext, serverUrl,
                slotIndex, initialAuthToken.token());

        GetPhoneNumberRequest getPhoneNumberRequest = GetPhoneNumberRequest.builder().build();

        // The getPhoneNumber method itself does not require the request object
        // as all necessary info is in the Ts43Operation object.
        GetPhoneNumberResponse phoneNumberResponse =
                ts43Operation.getPhoneNumber(getPhoneNumberRequest);
        return phoneNumberResponse.msisdn();
    }
}
