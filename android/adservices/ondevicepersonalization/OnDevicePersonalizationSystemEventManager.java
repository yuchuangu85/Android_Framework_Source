/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.adservices.ondevicepersonalization;

import static android.adservices.ondevicepersonalization.OnDevicePersonalizationPermissions.NOTIFY_MEASUREMENT_EVENT;

import android.adservices.ondevicepersonalization.aidl.IOnDevicePersonalizationManagingService;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.OutcomeReceiver;
import android.os.SystemClock;

import com.android.adservices.ondevicepersonalization.flags.Flags;
import com.android.federatedcompute.internal.util.AbstractServiceBinder;
import com.android.internal.annotations.VisibleForTesting;
import com.android.ondevicepersonalization.internal.util.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Provides APIs for the platform to signal events that are to be handled by the ODP service.
 *
 * @deprecated The ODP APIs are deprecated and will not be supported in future Android releases.
 * There is no direct replacement API available. Developers currently integrated with these APIs
 * must cease further integration efforts. For comprehensive details regarding this deprecation and
 * the future roadmap of Privacy Sandbox on Android, please consult the official Privacy Sandbox
 * developer documentation and announcements:
 * <a href="https://privacysandbox.google.com">https://privacysandbox.google.com</a>
 * @hide
 */
@SystemApi
@Deprecated
@FlaggedApi(Flags.FLAG_ODP_DEPRECIATION_ENABLED)
public class OnDevicePersonalizationSystemEventManager {
    /** @hide */
    public static final String ON_DEVICE_PERSONALIZATION_SYSTEM_EVENT_SERVICE =
            "on_device_personalization_system_event_service";
    private static final String INTENT_FILTER_ACTION =
            "android.OnDevicePersonalizationService";
    private static final String ODP_MANAGING_SERVICE_PACKAGE_SUFFIX =
            "com.android.ondevicepersonalization.services";
    private static final String ALT_ODP_MANAGING_SERVICE_PACKAGE_SUFFIX =
            "com.google.android.ondevicepersonalization.services";
    private static final String TAG =
            OnDevicePersonalizationSystemEventManager.class.getSimpleName();
    private static final LoggerFactory.Logger sLogger = LoggerFactory.getLogger();

    // TODO(b/301732670): Define a new service for this manager and bind to it.
    private final AbstractServiceBinder<IOnDevicePersonalizationManagingService> mServiceBinder;
    private final Context mContext;

    /** @hide */
    public OnDevicePersonalizationSystemEventManager(Context context) {
        this(
                context,
                AbstractServiceBinder.getServiceBinderByIntent(
                        context,
                        INTENT_FILTER_ACTION,
                        List.of(
                                ODP_MANAGING_SERVICE_PACKAGE_SUFFIX,
                                ALT_ODP_MANAGING_SERVICE_PACKAGE_SUFFIX),
                        0,
                        IOnDevicePersonalizationManagingService.Stub::asInterface));
    }

    /** @hide */
    @VisibleForTesting
    public OnDevicePersonalizationSystemEventManager(
            Context context,
            AbstractServiceBinder<IOnDevicePersonalizationManagingService> serviceBinder) {
        mContext = context;
        mServiceBinder = serviceBinder;
    }

    /**
     * Receives a web trigger event from the Measurement API. This is intended to be called by the
     * <a href="https://developer.android.com/design-for-safety/privacy-sandbox/guides/attribution">
     * Measurement Service</a> when a browser registers an attribution event using the
     * <a href="https://github.com/WICG/attribution-reporting-api">Attribution and Reporting API</a>
     * with a payload that should be processed by an {@link IsolatedService}.
     *
     * @param measurementWebTriggerEvent the web trigger payload to be processed.
     * @param executor the {@link Executor} on which to invoke the callback.
     * @param receiver This either returns a {@code null} on success, or an exception on failure.
     */
    @RequiresPermission(NOTIFY_MEASUREMENT_EVENT)
    public void notifyMeasurementEvent(
            @NonNull MeasurementWebTriggerEventParams measurementWebTriggerEvent,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Void, Exception> receiver) {
        Objects.requireNonNull(measurementWebTriggerEvent);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(receiver);
        long startTimeMillis = SystemClock.elapsedRealtime();

        try {
            final IOnDevicePersonalizationManagingService service =
                    mServiceBinder.getService(executor);

            logApiCallStats(
                    service,
                    "",
                    Constants.API_NAME_NOTIFY_MEASUREMENT_EVENT,
                    SystemClock.elapsedRealtime() - startTimeMillis,
                    0,
                    0,
                    Constants.STATUS_API_DISABLED);

            receiver.onError(
                    new IllegalStateException("This API is deprecated and no longer functional."));
        } catch (Exception e) {
            receiver.onError(e);
        }
    }

    private void logApiCallStats(
            IOnDevicePersonalizationManagingService service,
            String sdkPackageName,
            int apiName,
            long latencyMillis,
            long rpcCallLatencyMillis,
            long rpcReturnLatencyMillis,
            int responseCode) {
        try {
            if (service != null) {
                service.logApiCallStats(sdkPackageName, apiName, latencyMillis,
                        rpcCallLatencyMillis, rpcReturnLatencyMillis, responseCode);
            }
        } catch (Exception e) {
            sLogger.e(e, TAG + ": Error logging API call stats");
        }
    }
}
