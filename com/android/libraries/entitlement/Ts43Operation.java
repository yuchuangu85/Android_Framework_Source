/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.libraries.entitlement;

import static java.util.Objects.requireNonNull;

import android.content.Context;
import android.os.Build;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

import com.android.libraries.entitlement.EsimOdsaOperation.OdsaServiceStatus;
import com.android.libraries.entitlement.http.HttpConstants;
import com.android.libraries.entitlement.odsa.AcquireConfigurationOperation.AcquireConfigurationRequest;
import com.android.libraries.entitlement.odsa.AcquireConfigurationOperation.AcquireConfigurationResponse;
import com.android.libraries.entitlement.odsa.AcquireTemporaryTokenOperation.AcquireTemporaryTokenRequest;
import com.android.libraries.entitlement.odsa.AcquireTemporaryTokenOperation.AcquireTemporaryTokenResponse;
import com.android.libraries.entitlement.odsa.CheckEligibilityOperation;
import com.android.libraries.entitlement.odsa.CheckEligibilityOperation.CheckEligibilityRequest;
import com.android.libraries.entitlement.odsa.CheckEligibilityOperation.CheckEligibilityResponse;
import com.android.libraries.entitlement.odsa.DownloadInfo;
import com.android.libraries.entitlement.odsa.GetPhoneNumberOperation.GetPhoneNumberRequest;
import com.android.libraries.entitlement.odsa.GetPhoneNumberOperation.GetPhoneNumberResponse;
import com.android.libraries.entitlement.odsa.ManageServiceOperation.ManageServiceRequest;
import com.android.libraries.entitlement.odsa.ManageServiceOperation.ManageServiceResponse;
import com.android.libraries.entitlement.odsa.ManageSubscriptionOperation.ManageSubscriptionRequest;
import com.android.libraries.entitlement.odsa.ManageSubscriptionOperation.ManageSubscriptionResponse;
import com.android.libraries.entitlement.odsa.MessageInfo;
import com.android.libraries.entitlement.odsa.OdsaResponse;
import com.android.libraries.entitlement.odsa.PlanOffer;
import com.android.libraries.entitlement.utils.StringUtils;
import com.android.libraries.entitlement.utils.Ts43Constants;
import com.android.libraries.entitlement.utils.Ts43XmlDoc;

import com.google.auto.value.AutoValue;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** TS43 operations described in GSMA Service Entitlement Configuration spec. */
@AutoValue
public abstract class Ts43Operation {
    private static final String TAG = "Ts43";

    /** The application context. */
    @NonNull
    protected abstract Context context();

    /**
     * The TS.43 entitlement version to use. For example, {@code "9.0"}. If {@code null}, version
     * {@code "2.0"} will be used by default.
     */
    @NonNull
    protected abstract String entitlementVersion();

    /** The entitlement server address. */
    @NonNull
    protected abstract URL entitlementServerAddress();

    /**
     * The initial authentication token used for TS.43 operation. This token might be only used for
     * the first time. Later if the server provides a new token in the operation's HTTP response,
     * the new token will be saved into {@link #mAuthToken}. Empty string if the initial token is
     * not available.
     */
    @NonNull
    protected abstract String initialAuthToken();

    /**
     * The temporary token retrieved from {@link
     * #acquireTemporaryToken(AcquireTemporaryTokenRequest)}. Empty string if it's not available.
     */
    @NonNull
    protected abstract String temporaryToken();

    /** The logical SIM slot index involved in ODSA operation. */
    protected abstract int slotIndex();

    /** The version of configuration currently stored on the client. */
    protected abstract int configurationVersion();

    /** The requesting application name. Empty string if it's not available. */
    @NonNull
    protected abstract String appName();

    /** The requesting application version. Empty string if it's not available. */
    @NonNull
    protected abstract String appVersion();

    /** Carrier configuration. */
    @Nullable
    protected abstract CarrierConfig carrierConfig();

    /** IMEI of the device. */
    @NonNull
    protected abstract String imei();

    @Nullable
    protected abstract ServiceEntitlement serviceEntitlement();

    @NonNull
    protected abstract String acceptContentType();

    /**
     * The auto token provided by the server in the operation's HTTP response. Empty string if it's
     * not available.
     */
    @NonNull
    private String mAuthToken = "";

    /**
     * Builder for {@link Ts43Operation}.
     *
     * <p>This class provides a fluent interface for constructing instances of
     * {@link Ts43Operation}. In order to build the {@link Ts43Operation} object,the following
     * mandatory methods must be called: {@link #setContext(Context)},
     * {@link #setEntitlementServerAddress(URL)}, and either {@link #setInitialAuthToken(String)}}
     * or {@link #setTemporaryToken(String)} must be called.
     */
    @AutoValue.Builder
    public abstract static class Builder {
        /**
         * Sets the application context to be used by the built object.
         * <p>
         * This context will be used for various operations, such as accessing resources,
         * starting activities, and interacting with system services.
         * </p>
         * <p>
         * It is crucial to provide a valid and appropriate context here. Typically,
         * this should be an {@link android.app.Application} context or an {@link Context}
         * associated with an activity that outlives the built object. Using an activity
         * context that might be destroyed before the built object can lead to memory
         * leaks or unexpected behavior.
         * </p>
         *
         * @param context The application context to use. Must not be null.
         *
         * @return This {@code Builder} object for method chaining.
         */
        @NonNull
        public abstract Builder setContext(@NonNull Context context);

        /**
         * Sets the TS.43 entitlement version to use.
         *
         * @param version The TS.43 entitlement version to use. For example, {@code "9.0"}.
         *
         * @return This {@code Builder} object for method chaining.
         */
        @NonNull
        public abstract Builder setEntitlementVersion(@NonNull String version);

        /**
         * Sets the entitlement server address.
         *
         * @param url The entitlement server address.
         *
         * @return This {@code Builder} object for method chaining.
         */
        @NonNull
        public abstract Builder setEntitlementServerAddress(@NonNull URL url);

        /**
         * Sets the initial authentication token used for TS.43 operation. This token might be only
         * used for the first time. Later if the server provides a new token in the operation's HTTP
         * response, the new token will be saved into {@link #mAuthToken}.
         *
         * @param token The initial authentication token.
         *
         * @return This {@code Builder} object for method chaining.
         */
        @NonNull
        public abstract Builder setInitialAuthToken(@NonNull String token);

        /**
         * Sets the temporary token retrieved from
         * {@link #acquireTemporaryToken(AcquireTemporaryTokenRequest)}
         *
         * @param token The temporary token.
         *
         * @return This {@code Builder} object for method chaining.
         */
        @NonNull
        public abstract Builder setTemporaryToken(@NonNull String token);

        /**
         * Sets the logical SIM slot index involved in ODSA operation.
         *
         * @param index The logical SIM slot index.
         *
         * @return This {@code Builder} object for method chaining.
         */
        @NonNull
        public abstract Builder setSlotIndex(int index);

        /**
         * Sets the version of configuration currently stored on the client.
         *
         * @return This {@code Builder} object for method chaining.
         */
        @NonNull
        public abstract Builder setConfigurationVersion(int configurationVersion);

        /**
         * Sets the name of the requesting application.
         *
         * @param name The name of the requesting application.
         *
         * @return This {@code Builder} object for method chaining.
         */
        @NonNull
        public abstract Builder setAppName(@NonNull String name);

        /**
         * Sets the version of the requesting application.
         *
         * @param version The version of the requesting application.
         *
         * @return This {@code Builder} object for method chaining.
         */
        @NonNull
        public abstract Builder setAppVersion(@NonNull String version);

        /**
         * Sets the carrier configuration.
         *
         * @param carrierConfig The carrier configuration.
         *
         * @return This {@code Builder} object for method chaining.
         */
        @NonNull
        public abstract Builder setCarrierConfig(@Nullable CarrierConfig carrierConfig);

        /**
         * Sets the IMEI of the device.
         *
         * @param imei The IMEI of the device.
         *
         * @return This {@code Builder} object for method chaining.
         */
        @NonNull
        protected abstract Builder setImei(@NonNull String imei);

        /**
         * Sets the service entitlement. This method is for testing only.
         *
         * @param serviceEntitlement The service entitlement.
         *
         * @return This {@code Builder} object for method chaining.
         */
        @VisibleForTesting
        @NonNull
        abstract Builder setServiceEntitlement(
                @Nullable ServiceEntitlement serviceEntitlement);

        /**
         * Sets the configuration document format the caller accepts, e.g. XML or JSON. Used by HTTP
         * request header "Accept".
         *
         * <p>If not set,
         * will use {@link ServiceEntitlementRequest#ACCEPT_CONTENT_TYPE_JSON_AND_XML}
         *
         * @see ServiceEntitlementRequest#ACCEPT_CONTENT_TYPE_XML
         * @see ServiceEntitlementRequest#ACCEPT_CONTENT_TYPE_JSON
         * @see ServiceEntitlementRequest#ACCEPT_CONTENT_TYPE_JSON_AND_XML
         */
        @NonNull
        public abstract Builder setAcceptContentType(
                @NonNull @ServiceEntitlementRequest.ContentType String acceptContentType);


        /**
         * @return The application context to use.
         */
        @NonNull
        protected abstract Context context();

        /**
         * @return The initial authentication token.
         */
        @NonNull
        protected abstract String initialAuthToken();

        /**
         * @return the temporary token retrieved from
         * {@link #acquireTemporaryToken(AcquireTemporaryTokenRequest)}.
         */
        @NonNull
        protected abstract String temporaryToken();

        /**
         * @return The carrier config.
         */
        @Nullable
        protected abstract CarrierConfig carrierConfig();

        /**
         * @return The logical SIM slot index involved in ODSA operation.
         */
        protected abstract int slotIndex();

        /**
         * @return The version of configuration currently stored on the client.
         */
        protected abstract int configurationVersion();

        /**
         * @return The service entitlement.
         */
        @Nullable
        protected abstract ServiceEntitlement serviceEntitlement();

        /** The entitlement server address. */
        @NonNull
        protected abstract URL entitlementServerAddress();

        /**
         * Returns the accepted content type of http response.
         *
         * @see ServiceEntitlementRequest#ACCEPT_CONTENT_TYPE_XML
         * @see ServiceEntitlementRequest#ACCEPT_CONTENT_TYPE_JSON
         * @see ServiceEntitlementRequest#ACCEPT_CONTENT_TYPE_JSON_AND_XML
         */
        @NonNull
        protected abstract String acceptContentType();



        /**
         * Builds the {@link Ts43Operation} object. (AutoValue generates its implementation).
         *
         * @return The built {@link Ts43Operation} object.
         */
        @NonNull
        protected abstract Ts43Operation autoBuild();

        /**
         * Builds the {@link Ts43Operation} object.
         *
         * @return The built {@link Ts43Operation} object.
         */
        @NonNull
        @RequiresApi(Build.VERSION_CODES.Q)
        public Ts43Operation build() {
            if (TextUtils.isEmpty(initialAuthToken()) && TextUtils.isEmpty(temporaryToken())) {
                throw new IllegalArgumentException("Either initialAuthToken or temporaryToken "
                        + "must be set.");
            }

            CarrierConfig carrierConfig = carrierConfig();
            if (carrierConfig == null) {
                carrierConfig = CarrierConfig.builder()
                        .setServerUrl(entitlementServerAddress().toString())
                        .build();
                setCarrierConfig(carrierConfig);
            }

            if (serviceEntitlement() == null) {
                int subscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    subscriptionId = SubscriptionManager.getSubscriptionId(slotIndex());
                } else {
                    // before Android 14 this method didn't exist.  Fall back to older method
                    SubscriptionManager subscriptionManager = context()
                            .getSystemService(SubscriptionManager.class);
                    int[] subscriptionIds =
                            subscriptionManager.getSubscriptionIds(slotIndex());
                    if (subscriptionIds == null || subscriptionIds.length < 1) {
                        throw new IllegalArgumentException(
                            "Ts43Operation: no valid subscription for slot index "
                            + slotIndex());
                    }
                    subscriptionId = subscriptionIds[0];
                }
                setServiceEntitlement(new ServiceEntitlement(context(),
                        carrierConfig, subscriptionId));
            }

            String imei = null;
            TelephonyManager telephonyManager = context().getSystemService(TelephonyManager.class);
            if (telephonyManager != null) {
                int modemCount = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    modemCount = telephonyManager.getActiveModemCount();
                } else {
                    modemCount = telephonyManager.getPhoneCount();
                }
                if (slotIndex() < 0 || slotIndex() >= modemCount) {
                    throw new IllegalArgumentException("Ts43Operation: invalid slot index "
                            + slotIndex());
                }
                imei = telephonyManager.getImei(slotIndex());
            }
            setImei(StringUtils.nullToEmpty(imei));

            // Auto generate the rest of the fields
            return autoBuild();
        }
    }

    /** Returns a new {@link Ts43Operation.Builder} object. */
    @RequiresApi(Build.VERSION_CODES.Q)
    public static Ts43Operation.Builder builder() {
        return new AutoValue_Ts43Operation.Builder()
                .setEntitlementVersion(Ts43Constants.DEFAULT_ENTITLEMENT_VERSION)
                .setInitialAuthToken("")
                .setTemporaryToken("")
                .setSlotIndex(SubscriptionManager.getSlotIndex(
                          SubscriptionManager.getDefaultSubscriptionId()))
                .setAppName("")
                .setAppVersion("")
                .setServiceEntitlement(null)
                .setCarrierConfig(null)
                .setAcceptContentType("")
                .setConfigurationVersion(ServiceEntitlementRequest.DEFAULT_CONFIGURATION_VERSION);
    }

    /**
     * @return The initial service entitlement request builder.
     */
    @NonNull
    private ServiceEntitlementRequest.Builder getServiceEntitlementRequestBuilder() {
        ServiceEntitlementRequest.Builder builder =
                ServiceEntitlementRequest.builder()
                        .setEntitlementVersion(entitlementVersion())
                        .setTerminalId(imei())
                        .setAppName(appName())
                        .setAppVersion(appVersion())
                        .setConfigurationVersion(configurationVersion());

        if (!TextUtils.isEmpty(acceptContentType())) {
            builder.setAcceptContentType(acceptContentType());
        }
        if (!TextUtils.isEmpty(temporaryToken())) {
            builder.setTemporaryToken(temporaryToken());
        } else if (!TextUtils.isEmpty(mAuthToken)) {
            builder.setAuthenticationToken(mAuthToken);
        } else if (!TextUtils.isEmpty(initialAuthToken())) {
            builder.setAuthenticationToken(initialAuthToken());
        }

        return builder;
    }

    /**
     * To verify if end-user is allowed to invoke the ODSA application as described in GSMA Service
     * Entitlement Configuration section 6.2 and 6.5.2.
     *
     * @return {@code true} if the end-user is allowed to perform ODSA operation.
     * @throws ServiceEntitlementException The exception for error case. If it's an HTTP response
     *                                     error from the server, the error code can be retrieved by
     *                                     {@link ServiceEntitlementException#getHttpStatus()}
     */
    @NonNull
    public CheckEligibilityResponse checkEligibility(
            @NonNull CheckEligibilityRequest checkEligibilityRequest)
            throws ServiceEntitlementException {
        requireNonNull(checkEligibilityRequest);

        ServiceEntitlementRequest.Builder builder = getServiceEntitlementRequestBuilder();

        String notificationToken = checkEligibilityRequest.notificationToken();
        if (!TextUtils.isEmpty(notificationToken)) {
            builder.setNotificationToken(notificationToken);
        }
        int notificationAction = checkEligibilityRequest.notificationAction();
        if (Ts43Constants.isValidNotificationAction(notificationAction)) {
            builder.setNotificationAction(notificationAction);
        }

        ServiceEntitlementRequest request = builder.build();

        EsimOdsaOperation operation =
                EsimOdsaOperation.builder()
                        .setOperation(EsimOdsaOperation.OPERATION_CHECK_ELIGIBILITY)
                        .setCompanionTerminalId(checkEligibilityRequest.companionTerminalId())
                        .setCompanionTerminalVendor(
                                checkEligibilityRequest.companionTerminalVendor())
                        .setCompanionTerminalModel(checkEligibilityRequest.companionTerminalModel())
                        .setCompanionTerminalSoftwareVersion(
                                checkEligibilityRequest.companionTerminalSoftwareVersion())
                        .setCompanionTerminalFriendlyName(
                                checkEligibilityRequest.companionTerminalFriendlyName())
                        .build();

        String rawXml;
        try {
            rawXml = requireNonNull(serviceEntitlement()).performEsimOdsa(
                    checkEligibilityRequest.appId(), request, operation);
        } catch (ServiceEntitlementException e) {
            Log.w(TAG, "manageSubscription: Failed to perform ODSA operation. e=" + e);
            throw e;
        }

        // Build the response of check eligibility operation. Refer to GSMA Service Entitlement
        // Configuration section 6.5.2.
        CheckEligibilityResponse.Builder responseBuilder = CheckEligibilityResponse.builder();

        Ts43XmlDoc ts43XmlDoc = new Ts43XmlDoc(rawXml);

        try {
            processGeneralResult(ts43XmlDoc, responseBuilder);
        } catch (MalformedURLException e) {
            throw new ServiceEntitlementException(
                    ServiceEntitlementException.ERROR_MALFORMED_HTTP_RESPONSE,
                    "checkEligibility: Malformed URL " + rawXml);
        }

        // Parse the eligibility
        String eligibilityString =
                ts43XmlDoc.get(
                        Collections.singletonList(Ts43XmlDoc.CharacteristicType.APPLICATION),
                        Ts43XmlDoc.Parm.PRIMARY_APP_ELIGIBILITY);
        if (TextUtils.isEmpty(eligibilityString)) {
            eligibilityString =
                    ts43XmlDoc.get(
                            Collections.singletonList(Ts43XmlDoc.CharacteristicType.APPLICATION),
                            Ts43XmlDoc.Parm.COMPANION_APP_ELIGIBILITY);
        }

        int eligibility = CheckEligibilityOperation.ELIGIBILITY_RESULT_UNKNOWN;
        if (!TextUtils.isEmpty(eligibilityString)) {
            switch (eligibilityString) {
                case Ts43XmlDoc.ParmValues.DISABLED:
                    eligibility = CheckEligibilityOperation.ELIGIBILITY_RESULT_DISABLED;
                    break;
                case Ts43XmlDoc.ParmValues.ENABLED:
                    eligibility = CheckEligibilityOperation.ELIGIBILITY_RESULT_ENABLED;
                    break;
                case Ts43XmlDoc.ParmValues.INCOMPATIBLE:
                    eligibility = CheckEligibilityOperation.ELIGIBILITY_RESULT_INCOMPATIBLE;
                    break;
            }
        }
        responseBuilder.setAppEligibility(eligibility);

        // Parse companion device services
        String companionDeviceServices =
                ts43XmlDoc.get(
                        Collections.singletonList(Ts43XmlDoc.CharacteristicType.APPLICATION),
                        Ts43XmlDoc.Parm.COMPANION_DEVICE_SERVICES);

        if (!TextUtils.isEmpty(companionDeviceServices)) {
            List<String> companionDeviceServicesList =
                    Arrays.asList(companionDeviceServices.split("\\s*,\\s*"));
            responseBuilder.setCompanionDeviceServices(
                    companionDeviceServicesList);
        }

        // Parse notEnabledURL
        URL notEnabledURL;
        String notEnabledURLString =
                ts43XmlDoc.get(
                        Collections.singletonList(Ts43XmlDoc.CharacteristicType.APPLICATION),
                        Ts43XmlDoc.Parm.NOT_ENABLED_URL);

        try {
            notEnabledURL = new URL(notEnabledURLString);
            responseBuilder.setNotEnabledUrl(notEnabledURL);
        } catch (MalformedURLException e) {
            Log.w(TAG, "checkEligibility: malformed URL " + notEnabledURLString);
        }

        // Parse notEnabledUserData
        String notEnabledUserData =
                ts43XmlDoc.get(
                        Collections.singletonList(Ts43XmlDoc.CharacteristicType.APPLICATION),
                        Ts43XmlDoc.Parm.NOT_ENABLED_USER_DATA);

        if (!TextUtils.isEmpty(notEnabledUserData)) {
            responseBuilder.setNotEnabledUserData(notEnabledUserData);
        }

        // Parse notEnabledContentsType
        String notEnabledContentsTypeString =
                ts43XmlDoc.get(
                        Collections.singletonList(Ts43XmlDoc.CharacteristicType.APPLICATION),
                        Ts43XmlDoc.Parm.NOT_ENABLED_CONTENTS_TYPE);

        int notEnabledContentsType = HttpConstants.ContentType.UNKNOWN;
        if (!TextUtils.isEmpty(notEnabledContentsTypeString)) {
            switch (notEnabledContentsTypeString) {
                case Ts43XmlDoc.ParmValues.CONTENTS_TYPE_XML:
                    notEnabledContentsType = HttpConstants.ContentType.XML;
                    break;
                case Ts43XmlDoc.ParmValues.CONTENTS_TYPE_JSON:
                    notEnabledContentsType = HttpConstants.ContentType.JSON;
                    break;
            }
        }
        responseBuilder.setNotEnabledContentsType(notEnabledContentsType);

        return responseBuilder.build();
    }

    /**
     * To request for subscription-related action on a primary or companion device as described in
     * GSMA Service Entitlement Configuration section 6.2 and 6.5.3.
     *
     * @param manageSubscriptionRequest The manage subscription request.
     * @return The response of manage subscription request.
     * @throws ServiceEntitlementException The exception for error case. If it's an HTTP response
     *                                     error from the server, the error code can be retrieved by
     *                                     {@link ServiceEntitlementException#getHttpStatus()}
     */
    @NonNull
    public ManageSubscriptionResponse manageSubscription(
            @NonNull ManageSubscriptionRequest manageSubscriptionRequest)
            throws ServiceEntitlementException {
        requireNonNull(manageSubscriptionRequest);

        ServiceEntitlementRequest.Builder builder = getServiceEntitlementRequestBuilder()
                .setAcceptContentType(ServiceEntitlementRequest.ACCEPT_CONTENT_TYPE_XML);

        String notificationToken = manageSubscriptionRequest.notificationToken();
        if (!TextUtils.isEmpty(notificationToken)) {
            builder.setNotificationToken(notificationToken);
        }
        int notificationAction = manageSubscriptionRequest.notificationAction();
        if (Ts43Constants.isValidNotificationAction(notificationAction)) {
            builder.setNotificationAction(notificationAction);
        }

        ServiceEntitlementRequest request = builder.build();

        EsimOdsaOperation operation =
                EsimOdsaOperation.builder()
                        .setOperation(EsimOdsaOperation.OPERATION_MANAGE_SUBSCRIPTION)
                        .setOperationType(manageSubscriptionRequest.operationType())
                        .setCompanionTerminalId(manageSubscriptionRequest.companionTerminalId())
                        .setCompanionTerminalVendor(
                                manageSubscriptionRequest.companionTerminalVendor())
                        .setCompanionTerminalModel(
                                manageSubscriptionRequest.companionTerminalModel())
                        .setCompanionTerminalSoftwareVersion(
                                manageSubscriptionRequest.companionTerminalSoftwareVersion())
                        .setCompanionTerminalFriendlyName(
                                manageSubscriptionRequest.companionTerminalFriendlyName())
                        .setCompanionTerminalService(
                                manageSubscriptionRequest.companionTerminalService())
                        .setCompanionTerminalIccid(
                                manageSubscriptionRequest.companionTerminalIccid())
                        .setCompanionTerminalEid(manageSubscriptionRequest.companionTerminalEid())
                        .setTerminalIccid(manageSubscriptionRequest.terminalIccid())
                        .setTerminalEid(manageSubscriptionRequest.terminalEid())
                        .setTargetTerminalId(manageSubscriptionRequest.targetTerminalId())
                        // non TS.43 standard support
                        .setTargetTerminalIds(manageSubscriptionRequest.targetTerminalIds())
                        .setTargetTerminalIccid(manageSubscriptionRequest.targetTerminalIccid())
                        .setTargetTerminalEid(manageSubscriptionRequest.targetTerminalEid())
                        // non TS.43 standard support
                        .setTargetTerminalSerialNumber(
                                manageSubscriptionRequest.targetTerminalSerialNumber())
                        // non TS.43 standard support
                        .setTargetTerminalModel(manageSubscriptionRequest.targetTerminalModel())
                        .setOldTerminalId(manageSubscriptionRequest.oldTerminalId())
                        .setOldTerminalIccid(manageSubscriptionRequest.oldTerminalIccid())
                        .setMessageResponse(manageSubscriptionRequest.messageResponse())
                        .setMessageButton(manageSubscriptionRequest.messageButton())
                        .build();

        String rawXml;
        try {
            rawXml = requireNonNull(serviceEntitlement()).performEsimOdsa(
                    manageSubscriptionRequest.appId(), request, operation);
        } catch (ServiceEntitlementException e) {
            Log.w(TAG, "manageSubscription: Failed to perform ODSA operation. e=" + e);
            throw e;
        }

        // Build the response of manage subscription operation. Refer to GSMA Service Entitlement
        // Configuration section 6.5.3.
        ManageSubscriptionResponse.Builder responseBuilder = ManageSubscriptionResponse.builder();

        Ts43XmlDoc ts43XmlDoc;
        try {
            ts43XmlDoc = new Ts43XmlDoc(rawXml);
            processGeneralResult(ts43XmlDoc, responseBuilder);
        } catch (MalformedURLException e) {
            throw new ServiceEntitlementException(
                    ServiceEntitlementException.ERROR_MALFORMED_HTTP_RESPONSE,
                    "manageSubscription: Malformed URL " + rawXml);
        }

        int subscriptionResult = ManageSubscriptionResponse.SUBSCRIPTION_RESULT_UNKNOWN;

        // Parse subscription result.
        String subscriptionResultString =
                ts43XmlDoc.get(
                        Collections.singletonList(Ts43XmlDoc.CharacteristicType.APPLICATION),
                        Ts43XmlDoc.Parm.SUBSCRIPTION_RESULT);

        if (!TextUtils.isEmpty(subscriptionResultString)) {
            switch (subscriptionResultString) {
                case Ts43XmlDoc.ParmValues.SUBSCRIPTION_RESULT_CONTINUE_TO_WEBSHEET:
                    subscriptionResult =
                            ManageSubscriptionResponse.SUBSCRIPTION_RESULT_CONTINUE_TO_WEBSHEET;

                    String subscriptionServiceURLString =
                            ts43XmlDoc.get(
                                    Collections.singletonList(
                                            Ts43XmlDoc.CharacteristicType.APPLICATION),
                                    Ts43XmlDoc.Parm.SUBSCRIPTION_SERVICE_URL);

                    if (!TextUtils.isEmpty(subscriptionServiceURLString)) {
                        try {
                            responseBuilder.setSubscriptionServiceUrl(
                                    new URL(subscriptionServiceURLString));

                            String subscriptionServiceUserDataString =
                                    ts43XmlDoc.get(
                                            Collections.singletonList(
                                                    Ts43XmlDoc.CharacteristicType.APPLICATION),
                                            Ts43XmlDoc.Parm.SUBSCRIPTION_SERVICE_USER_DATA);
                            if (!TextUtils.isEmpty(subscriptionServiceUserDataString)) {
                                responseBuilder.setSubscriptionServiceUserData(
                                        subscriptionServiceUserDataString);
                            }

                            String subscriptionServiceContentsTypeString =
                                    ts43XmlDoc.get(
                                            Collections.singletonList(
                                                    Ts43XmlDoc.CharacteristicType.APPLICATION),
                                            Ts43XmlDoc.Parm.SUBSCRIPTION_SERVICE_CONTENTS_TYPE);
                            if (!TextUtils.isEmpty(subscriptionServiceContentsTypeString)) {
                                int contentsType = HttpConstants.ContentType.UNKNOWN;
                                switch (subscriptionServiceContentsTypeString) {
                                    case Ts43XmlDoc.ParmValues.CONTENTS_TYPE_XML:
                                        contentsType = HttpConstants.ContentType.XML;
                                        break;
                                    case Ts43XmlDoc.ParmValues.CONTENTS_TYPE_JSON:
                                        contentsType = HttpConstants.ContentType.JSON;
                                        break;
                                }
                                responseBuilder.setSubscriptionServiceContentsType(contentsType);
                            }
                        } catch (MalformedURLException e) {
                            Log.w(TAG, "Malformed URL received. " + subscriptionServiceURLString);
                        }
                    }
                    break;
                case Ts43XmlDoc.ParmValues.SUBSCRIPTION_RESULT_DOWNLOAD_PROFILE:
                    subscriptionResult =
                            ManageSubscriptionResponse.SUBSCRIPTION_RESULT_DOWNLOAD_PROFILE;
                    DownloadInfo downloadInfo =
                            parseDownloadInfo(
                                    Arrays.asList(
                                            Ts43XmlDoc.CharacteristicType.APPLICATION,
                                            Ts43XmlDoc.CharacteristicType.DOWNLOAD_INFO),
                                    ts43XmlDoc);
                    if (downloadInfo != null) {
                        responseBuilder.setDownloadInfo(downloadInfo);
                    }
                    break;
                case Ts43XmlDoc.ParmValues.SUBSCRIPTION_RESULT_DONE:
                    subscriptionResult = ManageSubscriptionResponse.SUBSCRIPTION_RESULT_DONE;
                    break;
                case Ts43XmlDoc.ParmValues.SUBSCRIPTION_RESULT_DELAYED_DOWNLOAD:
                    subscriptionResult =
                            ManageSubscriptionResponse.SUBSCRIPTION_RESULT_DELAYED_DOWNLOAD;
                    break;
                case Ts43XmlDoc.ParmValues.SUBSCRIPTION_RESULT_DISMISS:
                    subscriptionResult = ManageSubscriptionResponse.SUBSCRIPTION_RESULT_DISMISS;
                    break;
                case Ts43XmlDoc.ParmValues.SUBSCRIPTION_RESULT_DELETE_PROFILE_IN_USE:
                    subscriptionResult =
                            ManageSubscriptionResponse.SUBSCRIPTION_RESULT_DELETE_PROFILE_IN_USE;
                    break;
                case Ts43XmlDoc.ParmValues.SUBSCRIPTION_RESULT_REDOWNLOADABLE_PROFILE_IS_MANDATORY:
                    subscriptionResult =
                            ManageSubscriptionResponse
                                    .SUBSCRIPTION_RESULT_REDOWNLOADABLE_PROFILE_IS_MANDATORY;
                    break;
                case Ts43XmlDoc.ParmValues.SUBSCRIPTION_RESULT_REQUIRES_USER_INPUT:
                    subscriptionResult =
                            ManageSubscriptionResponse.SUBSCRIPTION_RESULT_REQUIRES_USER_INPUT;
                    break;
            }
        }

        responseBuilder.setSubscriptionResult(subscriptionResult);
        return responseBuilder.build();
    }

    /**
     * To activate/deactivate the service on the primary or companion device as described in GSMA
     * Service Entitlement Configuration section 6.2 and 6.5.4. This is an optional operation.
     *
     * @param manageServiceRequest The manage service request.
     * @return The response of manage service request.
     * @throws ServiceEntitlementException The exception for error case. If it's an HTTP response
     *                                     error from the server, the error code can be retrieved by
     *                                     {@link ServiceEntitlementException#getHttpStatus()}
     */
    @NonNull
    public ManageServiceResponse manageService(@NonNull ManageServiceRequest manageServiceRequest)
            throws ServiceEntitlementException {
        requireNonNull(manageServiceRequest);

        ServiceEntitlementRequest request = getServiceEntitlementRequestBuilder().build();

        EsimOdsaOperation operation =
                EsimOdsaOperation.builder()
                        .setOperation(EsimOdsaOperation.OPERATION_MANAGE_SERVICE)
                        .setOperationType(manageServiceRequest.operationType())
                        .setCompanionTerminalId(manageServiceRequest.companionTerminalId())
                        .setCompanionTerminalVendor(manageServiceRequest.companionTerminalVendor())
                        .setCompanionTerminalModel(manageServiceRequest.companionTerminalModel())
                        .setCompanionTerminalSoftwareVersion(
                                manageServiceRequest.companionTerminalSoftwareVersion())
                        .setCompanionTerminalFriendlyName(
                                manageServiceRequest.companionTerminalFriendlyName())
                        .setCompanionTerminalService(
                                manageServiceRequest.companionTerminalService())
                        .setCompanionTerminalIccid(manageServiceRequest.companionTerminalIccid())
                        .build();

        String rawXml;
        try {
            rawXml = requireNonNull(serviceEntitlement()).performEsimOdsa(
                    manageServiceRequest.appId(), request, operation);
        } catch (ServiceEntitlementException e) {
            Log.w(TAG, "manageService: Failed to perform ODSA operation. e=" + e);
            throw e;
        }

        // Build the response of manage service operation. Refer to GSMA Service Entitlement
        // Configuration section 6.5.4.
        ManageServiceResponse.Builder responseBuilder = ManageServiceResponse.builder();

        Ts43XmlDoc ts43XmlDoc = new Ts43XmlDoc(rawXml);

        try {
            processGeneralResult(ts43XmlDoc, responseBuilder);
        } catch (MalformedURLException e) {
            throw new ServiceEntitlementException(
                    ServiceEntitlementException.ERROR_MALFORMED_HTTP_RESPONSE,
                    "manageService: Malformed URL " + rawXml);
        }

        // Parse service status.
        String serviceStatusString =
                ts43XmlDoc.get(
                        Collections.singletonList(Ts43XmlDoc.CharacteristicType.APPLICATION),
                        Ts43XmlDoc.Parm.SERVICE_STATUS);

        if (!TextUtils.isEmpty(serviceStatusString)) {
            responseBuilder.setServiceStatus(getServiceStatusFromString(serviceStatusString));
        }

        return responseBuilder.build();
    }

    /**
     * To provide service related data about a primary or companion device as described in GSMA
     * Service Entitlement Configuration section 6.2 and 6.5.5.
     *
     * @param acquireConfigurationRequest The acquire configuration request.
     * @return The response of acquire configuration request.
     * @throws ServiceEntitlementException The exception for error case. If it's an HTTP response
     *                                     error from the server, the error code can be retrieved by
     *                                     {@link ServiceEntitlementException#getHttpStatus()}
     */
    @NonNull
    public AcquireConfigurationResponse acquireConfiguration(
            @NonNull AcquireConfigurationRequest acquireConfigurationRequest)
            throws ServiceEntitlementException {
        requireNonNull(acquireConfigurationRequest);

        ServiceEntitlementRequest.Builder builder = getServiceEntitlementRequestBuilder();

        String notificationToken = acquireConfigurationRequest.notificationToken();
        if (!TextUtils.isEmpty(notificationToken)) {
            builder.setNotificationToken(notificationToken);
        }
        int notificationAction = acquireConfigurationRequest.notificationAction();
        if (Ts43Constants.isValidNotificationAction(notificationAction)) {
            builder.setNotificationAction(notificationAction);
        }

        ServiceEntitlementRequest request = builder.build();

        EsimOdsaOperation operation =
                EsimOdsaOperation.builder()
                        .setOperation(EsimOdsaOperation.OPERATION_ACQUIRE_CONFIGURATION)
                        .setCompanionTerminalId(acquireConfigurationRequest.companionTerminalId())
                        .setCompanionTerminalIccid(
                                acquireConfigurationRequest.companionTerminalIccid())
                        .setCompanionTerminalEid(acquireConfigurationRequest.companionTerminalEid())
                        .setTerminalIccid(acquireConfigurationRequest.terminalIccid())
                        .setTerminalEid(acquireConfigurationRequest.terminalEid())
                        .setTargetTerminalId(acquireConfigurationRequest.targetTerminalId())
                        .setTargetTerminalIccid(acquireConfigurationRequest.targetTerminalIccid())
                        .setTargetTerminalEid(acquireConfigurationRequest.targetTerminalEid())
                        .build();

        String rawXml;
        try {
            rawXml = requireNonNull(serviceEntitlement()).performEsimOdsa(
                    acquireConfigurationRequest.appId(), request, operation);
        } catch (ServiceEntitlementException e) {
            Log.w(TAG, "acquireConfiguration: Failed to perform ODSA operation. e=" + e);
            throw e;
        }

        AcquireConfigurationResponse.Builder responseBuilder =
                AcquireConfigurationResponse.builder();
        AcquireConfigurationResponse.Configuration.Builder configBuilder =
                AcquireConfigurationResponse.Configuration.builder();

        Ts43XmlDoc ts43XmlDoc = new Ts43XmlDoc(rawXml);

        try {
            processGeneralResult(ts43XmlDoc, responseBuilder);
        } catch (MalformedURLException e) {
            throw new ServiceEntitlementException(
                    ServiceEntitlementException.ERROR_MALFORMED_HTTP_RESPONSE,
                    "manageSubscription: Malformed URL " + rawXml);
        }

        // Parse service status.
        String serviceStatusString =
                ts43XmlDoc.get(
                        Arrays.asList(
                                Ts43XmlDoc.CharacteristicType.APPLICATION,
                                Ts43XmlDoc.CharacteristicType.PRIMARY_CONFIGURATION),
                        Ts43XmlDoc.Parm.SERVICE_STATUS);

        if (!TextUtils.isEmpty(serviceStatusString)) {
            configBuilder.setServiceStatus(getServiceStatusFromString(serviceStatusString));
        }

        // Parse ICCID
        String iccIdString =
                ts43XmlDoc.get(
                        Arrays.asList(
                                Ts43XmlDoc.CharacteristicType.APPLICATION,
                                Ts43XmlDoc.CharacteristicType.PRIMARY_CONFIGURATION),
                        Ts43XmlDoc.Parm.ICCID);

        if (!TextUtils.isEmpty(iccIdString)) {
            configBuilder.setIccid(iccIdString);
        }

        // Parse polling interval
        String pollingIntervalString =
                ts43XmlDoc.get(
                        Arrays.asList(
                                Ts43XmlDoc.CharacteristicType.APPLICATION,
                                Ts43XmlDoc.CharacteristicType.PRIMARY_CONFIGURATION),
                        Ts43XmlDoc.Parm.POLLING_INTERVAL);

        if (!TextUtils.isEmpty(pollingIntervalString)) {
            try {
                configBuilder.setPollingInterval(Integer.parseInt(pollingIntervalString));
            } catch (NumberFormatException e) {
                Log.w(
                        TAG, "acquireConfiguration: Failed to parse polling interval "
                                + pollingIntervalString);
            }
        }

        // Parse download info
        DownloadInfo downloadInfo =
                parseDownloadInfo(
                        Arrays.asList(
                                Ts43XmlDoc.CharacteristicType.APPLICATION,
                                Ts43XmlDoc.CharacteristicType.PRIMARY_CONFIGURATION,
                                Ts43XmlDoc.CharacteristicType.DOWNLOAD_INFO),
                        ts43XmlDoc);
        if (downloadInfo != null) {
            configBuilder.setDownloadInfo(downloadInfo);
        }

        // Parse message info
        MessageInfo messageInfo =
                parseMessageInfo(
                        Arrays.asList(
                                Ts43XmlDoc.CharacteristicType.APPLICATION,
                                Ts43XmlDoc.CharacteristicType.PRIMARY_CONFIGURATION,
                                Ts43XmlDoc.CharacteristicType.MSG),
                        ts43XmlDoc);
        if (messageInfo != null) {
            configBuilder.setMessageInfo(messageInfo);
        }

        // TODO: Support different type of configuration.
        configBuilder.setType(
                AcquireConfigurationResponse.Configuration.CONFIGURATION_TYPE_PRIMARY);

        // TODO: Support multiple configurations.
        return responseBuilder
                .setConfigurations(Collections.singletonList(configBuilder.build()))
                .build();
    }

    /**
     * Acquire available mobile plans to be offered by the MNO to a specific user or MDM as
     * described in GSMA Service Entitlement Configuration section 6.2 and 6.5.6.
     *
     * @return List of mobile plans. Empty list if not available.
     * @throws ServiceEntitlementException The exception for error case. If it's an HTTP response
     *                                     error from the server, the error code can be retrieved by
     *                                     {@link ServiceEntitlementException#getHttpStatus()}
     */
    @NonNull
    public List<PlanOffer> acquirePlans() throws ServiceEntitlementException {
        return Collections.emptyList();
    }

    /**
     * To request a temporary token used to establish trust between ECS and the client as described
     * in GSMA Service Entitlement Configuration section 6.2 and 6.5.7.
     *
     * @param acquireTemporaryTokenRequest The acquire temporary token request.
     * @return The temporary token response.
     * @throws ServiceEntitlementException The exception for error case. If it's an HTTP response
     *                                     error from the server, the error code can be retrieved by
     *                                     {@link ServiceEntitlementException#getHttpStatus()}
     */
    @NonNull
    @SuppressWarnings("AndroidJdkLibsChecker") // java.time.Instant
    public AcquireTemporaryTokenResponse acquireTemporaryToken(
            @NonNull AcquireTemporaryTokenRequest acquireTemporaryTokenRequest)
            throws ServiceEntitlementException {
        requireNonNull(acquireTemporaryTokenRequest);

        ServiceEntitlementRequest request = getServiceEntitlementRequestBuilder().build();

        EsimOdsaOperation operation =
                EsimOdsaOperation.builder()
                        .setOperation(EsimOdsaOperation.OPERATION_ACQUIRE_TEMPORARY_TOKEN)
                        .setOperationTargets(acquireTemporaryTokenRequest.operationTargets())
                        .setCompanionTerminalId(acquireTemporaryTokenRequest.companionTerminalId())
                        .build();

        String rawXml;
        try {
            rawXml = requireNonNull(serviceEntitlement()).performEsimOdsa(
                    acquireTemporaryTokenRequest.appId(), request, operation);
        } catch (ServiceEntitlementException e) {
            Log.w(TAG, "acquireTemporaryToken: Failed to perform ODSA operation. e=" + e);
            throw e;
        }

        Ts43XmlDoc ts43XmlDoc = new Ts43XmlDoc(rawXml);
        AcquireTemporaryTokenResponse.Builder responseBuilder =
                AcquireTemporaryTokenResponse.builder();

        try {
            processGeneralResult(ts43XmlDoc, responseBuilder);
        } catch (MalformedURLException e) {
            throw new ServiceEntitlementException(
                    ServiceEntitlementException.ERROR_MALFORMED_HTTP_RESPONSE,
                    "AcquireTemporaryTokenResponse: Malformed URL " + rawXml);
        }

        // Parse the operation targets.
        String operationTargets =
                StringUtils.nullToEmpty(
                        ts43XmlDoc.get(
                                Collections.singletonList(
                                        Ts43XmlDoc.CharacteristicType.APPLICATION),
                                Ts43XmlDoc.Parm.OPERATION_TARGETS));

        List<String> operationTargetsList = Arrays.asList(operationTargets.split("\\s*,\\s*"));
        responseBuilder.setOperationTargets(operationTargetsList);

        // Parse the temporary token
        String temporaryToken =
                ts43XmlDoc.get(
                        Collections.singletonList(Ts43XmlDoc.CharacteristicType.APPLICATION),
                        Ts43XmlDoc.Parm.TEMPORARY_TOKEN);

        if (temporaryToken == null) {
            throw new ServiceEntitlementException(
                    ServiceEntitlementException.ERROR_TOKEN_NOT_AVAILABLE,
                    "temporary token is not available.");
        }

        responseBuilder.setTemporaryToken(temporaryToken);

        String temporaryTokenExpiry =
                ts43XmlDoc.get(
                        Collections.singletonList(Ts43XmlDoc.CharacteristicType.APPLICATION),
                        Ts43XmlDoc.Parm.TEMPORARY_TOKEN_EXPIRY);

        if (temporaryTokenExpiry == null) {
            // safe to throw here as this was an uncaught NPE in the previous code
            Log.w(TAG, "Failed to find a temporaryTokenExpiry");
            throw new ServiceEntitlementException(
                    ServiceEntitlementException.ERROR_TOKEN_NOT_AVAILABLE,
                    "temporary token didn't have required expiry.");
        }

        // Parse the token expiration time.
        Instant expiry;
        try {
            expiry = OffsetDateTime.parse(temporaryTokenExpiry).toInstant();
            responseBuilder.setTemporaryTokenExpiry(expiry);
        } catch (DateTimeParseException e) {
            Log.w(TAG, "Failed to parse temporaryTokenExpiry: " + temporaryTokenExpiry);
            // this should likely throw an exception as well - the expiry is required
            // however, that may be a breaking change for production services so we'll
            // leave this just logging and returning the default EPOC (1970 date) expiry value
            // and anybody checking that should know this is not valid
        }

        return responseBuilder.build();
    }

    /**
     * Get the phone number as described in GSMA Service Entitlement Configuration section 6.2 and
     * 6.5.8.
     *
     * @param getPhoneNumberRequest The get phone number request.
     * @return The phone number response from the network.
     * @throws ServiceEntitlementException The exception for error case. If it's an HTTP response
     *                                     error from the server, the error code can be retrieved by
     *                                     {@link ServiceEntitlementException#getHttpStatus()}
     */
    @NonNull
    public GetPhoneNumberResponse getPhoneNumber(
            @NonNull GetPhoneNumberRequest getPhoneNumberRequest)
            throws ServiceEntitlementException {

        ServiceEntitlementRequest.Builder builder = getServiceEntitlementRequestBuilder();

        if (!TextUtils.isEmpty(getPhoneNumberRequest.terminalId())) {
            builder.setTerminalId(getPhoneNumberRequest.terminalId());
        }

        ServiceEntitlementRequest request = builder.build();

        EsimOdsaOperation operation =
                EsimOdsaOperation.builder()
                        .setOperation(EsimOdsaOperation.OPERATION_GET_PHONE_NUMBER)
                        .build();

        String rawXml;
        try {
            rawXml = requireNonNull(serviceEntitlement()).performEsimOdsa(
                    Ts43Constants.APP_PHONE_NUMBER_INFORMATION, request, operation);
        } catch (ServiceEntitlementException e) {
            Log.w(TAG, "getPhoneNumber: Failed to perform ODSA operation. e=" + e);
            throw e;
        }

        // Build the response of get phone number operation. Refer to GSMA Service Entitlement
        // Configuration section 6.5.8.
        GetPhoneNumberResponse.Builder responseBuilder = GetPhoneNumberResponse.builder();

        Ts43XmlDoc ts43XmlDoc = new Ts43XmlDoc(rawXml);

        try {
            processGeneralResult(ts43XmlDoc, responseBuilder);
        } catch (MalformedURLException e) {
            throw new ServiceEntitlementException(
                    ServiceEntitlementException.ERROR_MALFORMED_HTTP_RESPONSE,
                    "getPhoneNumber: Malformed URL " + rawXml);
        }

        // Parse msisdn.
        String msisdn =
                ts43XmlDoc.get(
                        Collections.singletonList(Ts43XmlDoc.CharacteristicType.APPLICATION),
                        Ts43XmlDoc.Parm.MSISDN);
        if (TextUtils.isEmpty(msisdn)) {
            // Retry with uppercase
            msisdn = ts43XmlDoc.get(
                    Collections.singletonList(Ts43XmlDoc.CharacteristicType.APPLICATION),
                    Ts43XmlDoc.Parm.MSISDN.toUpperCase(Locale.ROOT));
        }

        if (!TextUtils.isEmpty(msisdn)) {
            responseBuilder.setMsisdn(msisdn);
        }

        return responseBuilder.build();
    }

    /**
     * Parse the download info from {@link ManageSubscriptionResponse}.
     *
     * @param characteristics The XML nodes to search activation code.
     * @param ts43XmlDoc The XML format http response.
     * @return The download info.
     */
    @Nullable
    @SuppressWarnings("AndroidJdkLibsChecker") // java.util.Base64
    private DownloadInfo parseDownloadInfo(
            @NonNull List<String> characteristics, @NonNull Ts43XmlDoc ts43XmlDoc) {
        String activationCode =
                StringUtils.nullToEmpty(
                        ts43XmlDoc.get(characteristics, Ts43XmlDoc.Parm.PROFILE_ACTIVATION_CODE));
        String smdpAddress =
                StringUtils.nullToEmpty(
                        ts43XmlDoc.get(characteristics, Ts43XmlDoc.Parm.PROFILE_SMDP_ADDRESS));
        String iccid =
                StringUtils.nullToEmpty(
                        ts43XmlDoc.get(characteristics, Ts43XmlDoc.Parm.PROFILE_ICCID));

        // DownloadInfo should contain either activationCode or smdpAddress + iccid
        if (!activationCode.isEmpty()) {
            // decode the activation code, which is in base64 format
            try {
                activationCode = new String(Base64.getDecoder().decode(activationCode));
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Failed to decode the activation code " + activationCode);
                return null;
            }
            return DownloadInfo.builder()
                    .setProfileActivationCode(activationCode)
                    .setProfileIccid(iccid)
                    .build();
        } else if (!smdpAddress.isEmpty() && !iccid.isEmpty()) {
            return DownloadInfo.builder()
                    .setProfileIccid(iccid)
                    .setProfileSmdpAddresses(
                            Arrays.asList(smdpAddress.split("\\s*,\\s*")))
                    .build();
        } else {
            Log.w(
                    TAG,
                    "Failed to parse download info. activationCode="
                            + activationCode
                            + ", smdpAddress="
                            + smdpAddress
                            + ", iccid="
                            + iccid);
            return null;
        }
    }

    /**
     * Parse the MSG info from {@link AcquireConfigurationResponse}.
     *
     * @param characteristics The XML nodes to search.
     * @param ts43XmlDoc The XML format http response.
     * @return The MSG info.
     */
    @Nullable
    private MessageInfo parseMessageInfo(
            @NonNull List<String> characteristics, @NonNull Ts43XmlDoc ts43XmlDoc) {
        String message =
                StringUtils.nullToEmpty(ts43XmlDoc.get(characteristics, Ts43XmlDoc.Parm.MESSAGE));
        String acceptButton =
                StringUtils.nullToEmpty(
                        ts43XmlDoc.get(characteristics, Ts43XmlDoc.Parm.ACCEPT_BUTTON));
        String acceptButtonLabel =
                StringUtils.nullToEmpty(
                        ts43XmlDoc.get(characteristics, Ts43XmlDoc.Parm.ACCEPT_BUTTON_LABEL));
        String rejectButton =
                StringUtils.nullToEmpty(
                        ts43XmlDoc.get(characteristics, Ts43XmlDoc.Parm.REJECT_BUTTON));
        String rejectButtonLabel =
                StringUtils.nullToEmpty(
                        ts43XmlDoc.get(characteristics, Ts43XmlDoc.Parm.REJECT_BUTTON_LABEL));
        String acceptFreetext =
                StringUtils.nullToEmpty(
                        ts43XmlDoc.get(characteristics, Ts43XmlDoc.Parm.ACCEPT_FREETEXT));

        // MessageInfo should contain message, accept button, reject button, and accept freetext
        if (!message.isEmpty() && !acceptButton.isEmpty() && !rejectButton.isEmpty()
                && !acceptFreetext.isEmpty()) {
            return MessageInfo.builder()
                    .setMessage(message)
                    .setAcceptButton(acceptButton)
                    .setAcceptButtonLabel(acceptButtonLabel)
                    .setRejectButton(rejectButton)
                    .setRejectButtonLabel(rejectButtonLabel)
                    .setAcceptFreetext(acceptFreetext)
                    .build();
        } else {
            Log.w(
                    TAG,
                    "Failed to parse message info. message="
                            + message
                            + ", acceptButton="
                            + acceptButton
                            + ", acceptButtonLabel="
                            + acceptButtonLabel
                            + ", rejectButton="
                            + rejectButton
                            + ", rejectButtonLabel="
                            + rejectButtonLabel
                            + ", acceptFreetext="
                            + acceptFreetext);
            return null;
        }
    }

    /**
     * Process the common ODSA result from HTTP response.
     *
     * @param ts43XmlDoc The TS.43 ODSA operation response in XLM format.
     * @param builder The response builder.
     * @throws MalformedURLException when HTTP response is not well formatted.
     */
    private void processGeneralResult(
            @NonNull Ts43XmlDoc ts43XmlDoc, @NonNull OdsaResponse.Builder builder)
            throws MalformedURLException {
        // Now start to parse the result from HTTP response.
        // Parse the operation result.
        String operationResult =
                ts43XmlDoc.get(
                        Collections.singletonList(Ts43XmlDoc.CharacteristicType.APPLICATION),
                        Ts43XmlDoc.Parm.OPERATION_RESULT);

        builder.setOperationResult(EsimOdsaOperation.OPERATION_RESULT_UNKNOWN);
        if (!TextUtils.isEmpty(operationResult)) {
            switch (operationResult) {
                case Ts43XmlDoc.ParmValues.OPERATION_RESULT_SUCCESS:
                    builder.setOperationResult(EsimOdsaOperation.OPERATION_RESULT_SUCCESS);
                    break;
                case Ts43XmlDoc.ParmValues.OPERATION_RESULT_ERROR_GENERAL:
                    builder.setOperationResult(EsimOdsaOperation.OPERATION_RESULT_ERROR_GENERAL);
                    break;
                case Ts43XmlDoc.ParmValues.OPERATION_RESULT_ERROR_INVALID_OPERATION:
                    builder.setOperationResult(
                            EsimOdsaOperation.OPERATION_RESULT_ERROR_INVALID_OPERATION);
                    break;
                case Ts43XmlDoc.ParmValues.OPERATION_RESULT_ERROR_INVALID_PARAMETER:
                    builder.setOperationResult(
                            EsimOdsaOperation.OPERATION_RESULT_ERROR_INVALID_PARAMETER);
                    break;
                case Ts43XmlDoc.ParmValues.OPERATION_RESULT_WARNING_NOT_SUPPORTED_OPERATION:
                    builder.setOperationResult(
                            EsimOdsaOperation.OPERATION_RESULT_WARNING_NOT_SUPPORTED_OPERATION);
                    break;
                case Ts43XmlDoc.ParmValues.OPERATION_RESULT_ERROR_INVALID_MSG_RESPONSE:
                    builder.setOperationResult(
                            EsimOdsaOperation.OPERATION_RESULT_ERROR_INVALID_MSG_RESPONSE);
                    break;
            }
        }

        // Parse the general error URL
        String generalErrorUrl =
                ts43XmlDoc.get(
                        Collections.singletonList(Ts43XmlDoc.CharacteristicType.APPLICATION),
                        Ts43XmlDoc.Parm.GENERAL_ERROR_URL);
        if (!TextUtils.isEmpty(generalErrorUrl)) {
            builder.setGeneralErrorUrl(new URL(generalErrorUrl));
        }

        // Parse the general error URL user data
        String generalErrorUserData =
                ts43XmlDoc.get(
                        Collections.singletonList(Ts43XmlDoc.CharacteristicType.APPLICATION),
                        Ts43XmlDoc.Parm.GENERAL_ERROR_USER_DATA);
        if (!TextUtils.isEmpty(generalErrorUserData)) {
            builder.setGeneralErrorUserData(generalErrorUserData);
        }

        // Parse the general error text
        String generalErrorText =
                ts43XmlDoc.get(
                        Collections.singletonList(Ts43XmlDoc.CharacteristicType.APPLICATION),
                        Ts43XmlDoc.Parm.GENERAL_ERROR_TEXT);
        if (!TextUtils.isEmpty(generalErrorText)) {
            builder.setGeneralErrorText(generalErrorText);
        }

        // Parse the token for next operation.
        String token =
                ts43XmlDoc.get(
                        Collections.singletonList(Ts43XmlDoc.CharacteristicType.TOKEN),
                        Ts43XmlDoc.Parm.TOKEN);
        if (!TextUtils.isEmpty(token)) {
            // Some servers issue the new token in operation result for next operation to use.
            // We need to save it.
            mAuthToken = token;
            Log.d(TAG, "processGeneralResult: Token replaced.");
        }
    }

    /**
     * Get the service status from string as described in GSMA Service Entitlement Configuration
     * section 6.5.4.
     *
     * @param serviceStatusString Service status in string format defined in GSMA Service
     *                            Entitlement Configuration section 6.5.4.
     * @return The converted service status. {@link EsimOdsaOperation#SERVICE_STATUS_UNKNOWN} if not
     * able to convert.
     */
    @OdsaServiceStatus
    private int getServiceStatusFromString(@NonNull String serviceStatusString) {
        switch (serviceStatusString) {
            case Ts43XmlDoc.ParmValues.SERVICE_STATUS_ACTIVATED:
                return EsimOdsaOperation.SERVICE_STATUS_ACTIVATED;
            case Ts43XmlDoc.ParmValues.SERVICE_STATUS_ACTIVATING:
                return EsimOdsaOperation.SERVICE_STATUS_ACTIVATING;
            case Ts43XmlDoc.ParmValues.SERVICE_STATUS_DEACTIVATED:
                return EsimOdsaOperation.SERVICE_STATUS_DEACTIVATED;
            case Ts43XmlDoc.ParmValues.SERVICE_STATUS_DEACTIVATED_NO_REUSE:
                return EsimOdsaOperation.SERVICE_STATUS_DEACTIVATED_NO_REUSE;
        }
        return EsimOdsaOperation.SERVICE_STATUS_UNKNOWN;
    }
}
