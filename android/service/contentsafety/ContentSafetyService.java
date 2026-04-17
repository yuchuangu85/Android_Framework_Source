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
import static android.service.contentsafety.BundleUtil.packMapIntoFeatureBundle;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Service;
import android.app.contentsafety.ContentSafetyManager;
import android.content.Intent;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.infra.AndroidFuture;

import java.util.Map;
import java.util.Objects;

/**
 * Abstract class for performing content safety checks. Basically implements
 * {@link ContentSafetyService#requestOnGetFeature} that should be called internally by the system service
 * before calling the remote API {@link ContentSafetySandboxedService#onCheckContentRequest}.
 *
 * <p>The system's default ContentSafetyService implementation is configured in {@code
 * config_defaultContentSafetyService}. If this config has no value, an error is returned.
 *
 * <pre>
 * {@literal
 * <service android:name=".SampleContentSafetyService"
 *          android:permission="android.permission.BIND_CONTENT_SAFETY_SERVICE">
 *          <intent-filter>
 *  *             <action android:name="android.service.contentsafety.ContentSafetyService" />
 *  *         </intent-filter>
 * </service>}
 * </pre>
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_ENABLE_CONTENTSAFETY)
public abstract class ContentSafetyService extends Service {
    private static final String TAG = "ContentSafetyService";
    private Handler mHandler;

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper(), null /* callback */, true /* async */);
    }

    /**
     * The {@link Intent} that must be declared as handled by the service. To be supported, the
     * service must also require the {@link android.Manifest.permission#BIND_CONTENT_SAFETY_SERVICE}
     * permission so that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.contentsafety.ContentSafetyService";

    /** @hide */
    @Nullable
    @Override
    public final IBinder onBind(@Nullable Intent intent) {
        if (SERVICE_INTERFACE.equals((intent.getAction()))) {
            return new IContentSafetyService.Stub() {
                /** {@inheritDoc} */
                @Override
                public void ready() {
                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    ContentSafetyService::onReady, ContentSafetyService.this));
                }

                @Override
                @RequiresPermission(android.Manifest.permission.CHECK_CONTENT_SAFETY)
                public void requestGetFeature(
                        int featureType,
                        AndroidFuture cancellationSignalFuture,
                        IGetFeatureCallback callback) {
                    Objects.requireNonNull(callback);

                    ICancellationSignal transport = null;
                    if (cancellationSignalFuture != null) {
                        transport = CancellationSignal.createTransport();
                        cancellationSignalFuture.complete(transport);
                    }

                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    ContentSafetyService::onGetFeatureRequest,
                                    ContentSafetyService.this,
                                    featureType,
                                    CancellationSignal.fromTransport(transport),
                                    wrapGetFeatureCallback(callback)));
                }

                @Override
                public void notifySandboxedServiceConnected() {
                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    ContentSafetyService::onNotifySandboxedServiceConnected,
                                    ContentSafetyService.this));
                }

                @Override
                public void notifySandboxedServiceDisconnected() {
                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    ContentSafetyService::onNotifySandboxedServiceDisconnected,
                                    ContentSafetyService.this));
                }

                @Override
                public void notifySettingsServiceConnected() {
                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    ContentSafetyService::onNotifySettingsServiceConnected,
                                    ContentSafetyService.this));
                }

                @Override
                public void notifySettingsServiceDisconnected() {
                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    ContentSafetyService::onNotifySettingsServiceDisconnected,
                                    ContentSafetyService.this));
                }
            };
        }
        Slog.w(
                TAG,
                "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": " + intent);
        return null;
    }

    /**
     * Wraps the AIDL {@link IGetFeatureCallback} in an {@link OutcomeReceiver} for use by the
     *      service implementation.
     */
    private OutcomeReceiver<
            Map<String, ParcelFileDescriptor>, ContentSafetyException> wrapGetFeatureCallback(
                    IGetFeatureCallback featureCallback) {
        return new OutcomeReceiver<>() {
                    @Override
                    public void onResult(Map<String, ParcelFileDescriptor> result) {
                        try {

                            featureCallback.onResult(packMapIntoFeatureBundle(result));
                        } catch (RemoteException ex) {
                            Slog.e(TAG, "Error sending getFeature result: ", ex);
                        }
                    }

                    @Override
                    public void onError(ContentSafetyException e) {
                        try {
                            featureCallback.onError(e.getErrorCode());
                        } catch (RemoteException ex) {
                            Slog.e(TAG, "Error sending getFeature: ", e);
                        }
                    }
                };
    }

    /**
     * Using this signal to assertively a signal each time service binds successfully, used only in
     * tests to get a signal that service instance is ready. This is needed because we cannot rely
     * on {@link #onCreate} or {@link #onBind} to be invoke on each binding.
     *
     * @hide
     */
    @TestApi
    public void onReady() {}

    /**
     * Provides feature files for a given feature type. These files are typically used by the
     * {@link ContentSafetySandboxedService} to perform content checks.
     *
     * @param featureType enum value defined in {@link ContentSafetyManager.FeatureType}
     * @param cancellationSignal Cancellation Signal to receive cancellation events from client and
     *     configure a listener to.
     * @param callback The callback to receive the result. The result is a map where keys are
     *     identifiers for the files (e.g., model names) and values are the file descriptors.
     *     On error, a {@link ContentSafetyException} is provided.
     */
    public abstract void onGetFeatureRequest(
            int featureType,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull OutcomeReceiver<Map<String, ParcelFileDescriptor>,
                    ContentSafetyException> callback);

    /**
     * Invoked when a new instance of the remote sandboxed service is created. This method should
     * be used as a signal to perform any initialization operations.
     * This method should be invoked before calling the API
     * {@link ContentSafetyManager#requestCheckContent}.
     */
    public abstract void onNotifySandboxedServiceConnected();

    /** Invoked when an instance of the remote sandboxed service is disconnected. */
    public abstract void onNotifySandboxedServiceDisconnected();

    /**
     * Invoked when a new instance of the remote settings service is created. This method should
     * be used as a signal of perform initialization operations.
     * This method should be invoked before calling the API
     * {@link ContentSafetyManager#requestIsFeatureEnabled}.
     */
    public abstract void onNotifySettingsServiceConnected();

    /** Invoked when an instance of the remote settings service is disconnected. */
    public abstract void onNotifySettingsServiceDisconnected();

}
