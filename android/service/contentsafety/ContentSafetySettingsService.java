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

package android.service.contentsafety;

import static android.app.contentsafety.flags.Flags.FLAG_ENABLE_CONTENTSAFETY;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.contentsafety.ContentSafetyManager;
import android.app.contentsafety.FeatureException;
import android.app.contentsafety.IIsFeatureEnabledCallback;
import android.content.Intent;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.infra.AndroidFuture;

import java.util.Objects;

/**
 * Abstract base class for performing isFeatureEnabled in the remote Settings process.
 * The remote service should implement the function
 * {@link ContentSafetySettingsService#onIsFeatureEnabledRequest} that corresponds to the API
 * methods {@link ContentSafetyManager#onIsFeatureEnabledRequest}.
 *
 * <pre>
 *     {@literal
 *     <service android:name=".SampleSettingsService"
 *              android:permission="android.permission.BIND_SETTINGS_CONTENT_SAFETY_SERVICE">
 *              <intent-filter>
 *  *             <action
 *  android:name="android.service.contentsafety.ContentSafetySettingsService" />
 *  *         </intent-filter>
 *      </service>}
 * </pre>
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_ENABLE_CONTENTSAFETY)
public abstract class ContentSafetySettingsService extends Service {
    private static final String TAG = "ContentSafetySettingsService";

    /**
     * The {@link Intent} that must be declared as handled by the service. To be supported, the
     * service must also require the {@link
     * android.Manifest.permission#BIND_SETTINGS_CONTENT_SAFETY_SERVICE} permission so that other
     * applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.contentsafety.ContentSafetySettingsService";

    /** @hide */
    public static final String DEVICE_CONFIG_UPDATE_BUNDLE_KEY = "device_config_update";

    private Handler mHandler;

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper(), null /* callback */, true /* async */);
    }

    /** @hide */
    @Nullable
    @Override
    public final IBinder onBind(@Nullable Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return new IContentSafetySettingsService.Stub() {

                @Override
                public void requestIsFeatureEnabled(
                        int featureType,
                        UserHandle userId,
                        AndroidFuture cancellationSignalFuture,
                        IIsFeatureEnabledCallback callback) {
                    Objects.requireNonNull(callback);

                    ICancellationSignal transport = null;
                    if (cancellationSignalFuture != null) {
                        transport = CancellationSignal.createTransport();
                        cancellationSignalFuture.complete(transport);
                    }

                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    ContentSafetySettingsService::onIsFeatureEnabledRequest,
                                    ContentSafetySettingsService.this,
                                    featureType,
                                    userId,
                                    CancellationSignal.fromTransport(transport),
                                    wrapIsFeatureEnabledCallback(callback)));
                }
            };
        }
        Slog.w(
                TAG,
                "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": " + intent);
        return null;
    }

    private OutcomeReceiver<Boolean, FeatureException> wrapIsFeatureEnabledCallback(
            IIsFeatureEnabledCallback callback) {
        return new OutcomeReceiver<>() {
            @Override
            public void onResult(Boolean result) {
                try {
                    callback.onSuccess(result);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error Sending isFeatureEnabledCallback: "
                            + e);
                }
            }

            @Override
            public void onError(@NonNull FeatureException exception) {
                try {
                    callback.onFailure(exception.getErrorCode());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error Sending isFeatureEnabledCallback: "
                            + e);
                }
            }
        };
    }

    /**
     * Invoked when the caller wants to check if the feature is enabled on device before performing
     * actual check contents against featureType.
     *
     * @param featureType Feature type that decide which checks should be operated on the input.
     * @param userId user id on device, not related to any account.
     * @param cancellationSignal Cancellation Signal to receive cancellation events from client and
     *     configure a listener to.
     * @param isFeatureEnabledCallback to populate the result of the isFeatureEnabled at
     *     success case or failure status at Failure case.
     */
    @RequiresPermission(android.Manifest.permission.CHECK_CONTENT_SAFETY)
    public abstract void onIsFeatureEnabledRequest(
            int featureType,
            @NonNull UserHandle userId,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull OutcomeReceiver<Boolean, FeatureException> isFeatureEnabledCallback);
}

