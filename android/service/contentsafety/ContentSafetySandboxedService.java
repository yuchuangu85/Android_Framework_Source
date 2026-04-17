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
import static android.service.contentsafety.BundleUtil.unpackMapFromFeatureBundle;
import static android.service.contentsafety.BundleUtil.unpackMapFromPayloadBundle;

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
import android.app.contentsafety.ContentSafetyManager.CheckContentParams;
import android.app.contentsafety.ContentSafetyManager.CheckContentStatus;
import android.app.contentsafety.ICheckContentCallback;
import android.content.Intent;
import android.os.Bundle;
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

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Abstract base class for performing check content in a sandboxed process. It should implement
 * the function {@link ContentSafetySandboxedService#onLoadFeatureRequest}, and
 * {@link ContentSafetySandboxedService#onCheckContentRequest}.
 *
 * When an app calls the API {@link  ContentSafetyManager#requestCheckContent}, the system service
 * will call the following methods respectively:
 *  {@link ContentSafetyService#requestOnGetFeature},
 *  {@link ContentSafetySandboxedService#onLoadFeatureRequest},
 *  and then {@link ContentSafetySandboxedService#onCheckContentRequest}.
 *
 * <pre>
 *     {@literal
 *     <service android:name=".SampleSandboxedService"
 *              android:permission="android.permission.BIND_CONTENT_SAFETY_SANDBOXED_SERVICE">
 *              <intent-filter>
 *  *             <action
 *  android:name="android.service.contentsafety.ContentSafetySandboxedService" />
 *  *         </intent-filter>
 *      </service>}
 * </pre>
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_ENABLE_CONTENTSAFETY)
public abstract class ContentSafetySandboxedService extends Service {
    private static final String TAG = "ContentSafetySandboxedService";

    /**
     * The {@link Intent} that must be declared as handled by the service. To be supported, the
     * service must also require the {@link
     * android.Manifest.permission#BIND_SANDBOXED_CONTENT_SAFETY_SERVICE} permission so that other
     * applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.contentsafety.ContentSafetySandboxedService";

    private Handler mHandler;

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper(), /* callback */ null, /* async */ true);
    }

    /** @hide */
    @Nullable
    @Override
    public final IBinder onBind(@Nullable Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return new IContentSafetySandboxedService.Stub() {
                @Override
                public void requestCheckContent(
                        int featureType,
                        Bundle input,
                        AndroidFuture cancellationSignalFuture,
                        ICheckContentCallback callback) {

                    Objects.requireNonNull(callback);
                    ICancellationSignal transport = null;
                    if (cancellationSignalFuture != null) {
                        transport = CancellationSignal.createTransport();
                        cancellationSignalFuture.complete(transport);
                    }

                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    ContentSafetySandboxedService::onCheckContentRequest,
                                    ContentSafetySandboxedService.this,
                                    featureType,
                                    unpackMapFromPayloadBundle(input),
                                    CancellationSignal.fromTransport(transport),
                                    callbackMap -> {
                                        Bundle resultBundle = serializeMapToBundle(callbackMap);
                                        try {
                                            callback.onResult(resultBundle);
                                        } catch (RemoteException e) {
                                            Slog.e(TAG, "Failed to send result back to client", e);
                                        }
                                    }));
                }

                @Override
                public void requestLoadFeature(
                        Bundle feature,
                        AndroidFuture cancellationSignalFuture,
                        ILoadFeatureCallback callback) {
                    Objects.requireNonNull(feature);
                    Objects.requireNonNull(callback);

                    ICancellationSignal transport = null;
                    if (cancellationSignalFuture != null) {
                        transport = CancellationSignal.createTransport();
                        cancellationSignalFuture.complete(transport);
                    }

                    mHandler.executeOrSendMessage(
                            obtainMessage(
                                    ContentSafetySandboxedService::onLoadFeatureRequest,
                                    ContentSafetySandboxedService.this,
                                    unpackMapFromFeatureBundle(feature),
                                    CancellationSignal.fromTransport(transport),
                                    wrapLoadFeatureCallback(callback)));
                }
            };
        }
        Slog.w(
                TAG,
                "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": " + intent);
        return null;
    }

    private Bundle serializeMapToBundle(Map<String, List<Integer>> map) {
        Bundle bundle = new Bundle();
        if (map == null) return bundle;
        for (Map.Entry<String, List<Integer>> entry : map.entrySet()) {
            bundle.putIntegerArrayList(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return bundle;
    }

    private OutcomeReceiver<Void, ContentSafetyException> wrapLoadFeatureCallback(
            ILoadFeatureCallback featureCallback) {
        return new OutcomeReceiver<>() {
            @Override
            public void onResult(Void result) {
                try {
                    featureCallback.onResult();
                } catch (RemoteException ex) {
                    Slog.e(TAG, "Error sending loadFeature onResult: ", ex);
                }
            }

            @Override
            public void onError(ContentSafetyException e) {
                try {
                    featureCallback.onError(e.getErrorCode());
                } catch (RemoteException ex) {
                    Slog.e(TAG, "Error sending loadFeature: ", e);
                }
            }
        };
    }


    /**
     * Invoked by {@link ContentSafetyManager#requestCheckContent}.
     * The caller wants to check the provided content input against
     * featureType to ensure content is safe.
     *
     * @param featureType Feature type that decide which checks should be operated on the input.
     * @param contentPayloadMap the content payload that require processing.
     *              It's a map where keys are featureType, and values
     *              are a list of parcelFileDescriptors.
     * @param cancellationSignal Cancellation Signal to receive cancellation events from client and
     *     configure a listener to.
     * @param callback to populate the result of the checkContent call. The callback consumes a map
     *      where keys are string representations of feature types, and values are a list of
     *      {@link CheckContentStatus} codes. The returned order of status codes should match
     *      the order of the input files.
     */
    @RequiresPermission(android.Manifest.permission.CHECK_CONTENT_SAFETY)
    public abstract void onCheckContentRequest(
            int featureType,
            @NonNull @CheckContentParams Map<Integer, List<ParcelFileDescriptor>> contentPayloadMap,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull Consumer<Map<String, List<@CheckContentStatus Integer>>> callback);

    /**
     * Invoked when loading feature files to the processor to perform checkContent.
     *
     * @param features a map of feature files to be applied to the checkContent environment.
     *      Keys are identifiers for the files (e.g., model names), and values are the corresponding
     *      file descriptors.
     * @param cancellationSignal Cancellation Signal to receive cancellation events from client and
     *     configure a listener to.
     * @param callback callback to populate if LoadFeature is succeeded or failed.
     */
    @RequiresPermission(android.Manifest.permission.CHECK_CONTENT_SAFETY)
    public abstract void onLoadFeatureRequest(
            @NonNull @CheckLoadFeatureParams Map<String, ParcelFileDescriptor> features,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull OutcomeReceiver<Void, ContentSafetyException> callback);

    /**
     * {@link Bundle}s annotated with this type will be validated that they are in-effect read-only
     * when passed via Binder IPC. Bundle included should be of the structure
     * key-value pairs to represent protections used by checkContent Api.
     * String keys in the bundle present the protection type, and value should be
     * {@link android.os.ParcelFileDescriptor} opened in {@link
     *      android.os.ParcelFileDescriptor#MODE_READ_ONLY}
     * IBinder objects should *not* be added.
     *
     * In all other scenarios the system-server might throw a
     * {@link android.os.BadParcelableException} if the Bundle validation fails.
     *
     * @hide
     */
    @Target({ElementType.PARAMETER, ElementType.FIELD})
    public @interface CheckLoadFeatureParams {}
}
