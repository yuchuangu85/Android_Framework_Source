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

package android.app.contentsafety;

import static android.app.contentsafety.flags.Flags.FLAG_ENABLE_CONTENTSAFETY;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.OutcomeReceiver;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.infra.AndroidFuture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The ContentSafetyManager provides access to content safety features.
 *
 * <p>It allows granted apps to manage content safety service configured on the device. Typical
 * calling pattern will be to {@link #checkContentRequest} and {@link #isFeatureEnabledRequest}
 * checkFile against sensitive content warnings.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.CONTENT_SAFETY_SERVICE)
@FlaggedApi(FLAG_ENABLE_CONTENTSAFETY)
public final class ContentSafetyManager {
    private static final String TAG = "ContentSafety";
    private final IContentSafetyManager mService;
    private final Context mContext;

    /** @hide */
    public ContentSafetyManager(Context context, IContentSafetyManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Get package name configured for providing the remote implementation for the content safety
     * service.
     * @return empty string if the remote service is not configured or the package name.
     */
    @NonNull
    @RequiresPermission(Manifest.permission.CHECK_CONTENT_SAFETY)
    public String getRemoteServicePackageName() {
        String result;
        try {
            result = mService.getRemoteServicePackageName();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
        return result;
    }

    /**
     * Get package name configured for providing the remote implementation for the sandboxed
     * service.
     * @return empty string if the remote service is not configured or the package name.
     */
    @NonNull
    @RequiresPermission(Manifest.permission.CHECK_CONTENT_SAFETY)
    public String getRemoteSandboxedServicePackageName() {
        String result;
        try {
            result = mService.getRemoteSandboxedServicePackageName();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
        return result;
    }

    /**
     * Get package name configured for providing the remote implementation for the settings service.
     * @return empty string if the remote service is not configured or the package name.
     */
    @NonNull
    @RequiresPermission(Manifest.permission.CHECK_CONTENT_SAFETY)
    public String getRemoteSettingsServicePackageName() {
        String result;
        try {
            result = mService.getRemoteSettingsServicePackageName();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
        return result;
    }


    /** Sensitive Content Warnings for images.
     * @hide
     */
    public static final int SENSITIVE_IMAGE = 0;
    /** Sensitive Content Warnings for videos.
     * @hide
     */
    public static final int SENSITIVE_VIDEO = 1;
    /**
     * List of available Safety Features.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {SENSITIVE_IMAGE, SENSITIVE_VIDEO})
    public @interface FeatureType { }

    public static final int CONTENT_SAFETY_UNKNOWN = 0;
    /**
     * Indicates that the content check was successful and no sensitive content was detected.
     */
    public static final int CONTENT_SAFETY_SUCCESS_NONE = 1;
    /**
     * Indicates that the content check was successful and sensitive content was detected.
     */
    public static final int CONTENT_SAFETY_SUCCESS_SENSITIVE = 2;
    /**
     * Indicates that SANDBOXED service failed due to unspecified internal error.
     */
    public static final int CONTENT_SAFETY_SANDBOXED_SERVICE_ERROR_UNKNOWN = 3;
    /**
     * Indicates that sandboxed service is not available and fail to connect.
     */
    public static final int CONTENT_SAFETY_SANDBOXED_SERVICE_ERROR_NOT_AVAILABLE = 4;
    /**
     * Indicates that getFeature remote call is returning failure error code due to internal error.
     */
    public static final int CONTENT_SAFETY_GET_FEATURE_ERROR = 5;
    /**
     * Indicates that loadFeature remote call is returning failure error code due to internal error.
     */
    public static final int CONTENT_SAFETY_LOAD_FEATURE_ERROR = 6;
    /**
     * Indicates that downloaded feature from remote service is not opened with read-only mode.
     */
    public static final int CONTENT_SAFETY_FEATURE_NOT_READ_ONLY_ERROR = 7;
    /**
     * Indicates that the input file descriptors provided by the client is not opened with
     * read-only mode.
     */
    public static final int CONTENT_SAFETY_PAYLOAD_NOT_READ_ONLY_ERROR = 8;
    /**
     * Indicates that the remote content safety service received a cancelled signal.
     */
    public static final int CONTENT_SAFETY_CHECK_CONTENT_CANCELLED = 9;
    /**
     * Indicates that checkContent remote call failed due to internal error and return
     * an error code.
     */
    public static final int CONTENT_SAFETY_CHECK_CONTENT_ERROR = 10;
    /**
     * Indicates that trying to invoke the remote call checkContent failed.
     */
    public static final int CONTENT_SAFETY_CHECK_CONTENT_INVOKE_ERROR = 11;
    /**
     * Indicates that trying to invoke the remote call getFeature failed.
     */
    public static final int CONTENT_SAFETY_GET_FEATURE_INVOKE_ERROR = 12;
    /**
     * Indicates that to invoke the remote call loadFeature failed.
     */
    public static final int CONTENT_SAFETY_LOAD_FEATURE_INVOKE_ERROR = 13;
    /**
     * Indicates that the remote call getFeature failed due to internal error.
     */
    public static final int CONTENT_SAFETY_GET_FEATURE_INTERNAL_ERROR = 14;
    /**
     * Indicates that the remote call loadFeature failed due to internal error.
     */
    public static final int CONTENT_SAFETY_LOAD_FEATURE_INTERNAL_ERROR = 15;
    /**
     * @hide
     */
    @Target({ElementType.TYPE_USE, ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            value = {
                    CONTENT_SAFETY_UNKNOWN,
                    CONTENT_SAFETY_SUCCESS_NONE,
                    CONTENT_SAFETY_SUCCESS_SENSITIVE,
                    CONTENT_SAFETY_SANDBOXED_SERVICE_ERROR_UNKNOWN,
                    CONTENT_SAFETY_SANDBOXED_SERVICE_ERROR_NOT_AVAILABLE,
                    CONTENT_SAFETY_GET_FEATURE_ERROR,
                    CONTENT_SAFETY_LOAD_FEATURE_ERROR,
                    CONTENT_SAFETY_FEATURE_NOT_READ_ONLY_ERROR,
                    CONTENT_SAFETY_PAYLOAD_NOT_READ_ONLY_ERROR,
                    CONTENT_SAFETY_CHECK_CONTENT_CANCELLED,
                    CONTENT_SAFETY_CHECK_CONTENT_ERROR,
                    CONTENT_SAFETY_CHECK_CONTENT_INVOKE_ERROR,
                    CONTENT_SAFETY_GET_FEATURE_INVOKE_ERROR,
                    CONTENT_SAFETY_LOAD_FEATURE_INVOKE_ERROR,
                    CONTENT_SAFETY_GET_FEATURE_INTERNAL_ERROR,
                    CONTENT_SAFETY_LOAD_FEATURE_INTERNAL_ERROR
            })
    public @interface CheckContentStatus{ }


    /**
     * Checks an input asynchronously based on the provided params, and populate a response in
     * a Map. Each featureType maps to a list of
     * {@link CheckContentStatus} status codes. The order of the status codes is the same as
     * the order of the input file descriptors.
     *
     * @param featureType The safety feature type to run against the file.
     * @param input A map of list of ParcelFileDescriptors. keys should be a value of Feature Type.
     *             this input MUST have been opened for read-only access and if not, the system
     *              server will throw a {@link android.os.BadParcelableException}.
     * @param cancellationSignal signal to invoke cancellation or
     * @param callbackExecutor executor to run the callback on.
     * @param checkContentConsumer to consume the returning checkContent results. Keys should be
     *                            a value of Feature Type. Values should be a list of integer
     *                            values correspond to the execution status defined by final
     *                            integers {@code CONTENT_SAFETY_CHECK_CONTENT_*},
     *                            {@code CONTENT_SAFETY_GET_FEATURE_*}, and
     *                             {@code CONTENT_SAFETY_LOAD_FEATURE_*} which correspond to the
     *                            result of each given parcelFileDescriptor.
     *                             The order should be maintained the same as it is received.
     * @throws IllegalArgumentException If the provided feature is invalid or failed to
     *     check content.
     */
    @RequiresPermission(Manifest.permission.CHECK_CONTENT_SAFETY)
    public void requestCheckContent(
            @FeatureType int featureType,
            @NonNull @CheckContentParams Map<Integer, List<ParcelFileDescriptor>> input,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull
            Consumer<Map<Integer, List<@CheckContentStatus Integer>>>
                    checkContentConsumer) {
        try {
            ICheckContentCallback callback = new ICheckContentCallback.Stub() {
                @Override
                public void onResult(
                        Bundle resultBundle) {
                            Binder.withCleanCallingIdentity(() ->
                                    callbackExecutor.execute(() -> {
                                        Map<Integer, List<Integer>> resultMap =
                                                unpackMapFromBundle(resultBundle);
                                        checkContentConsumer.accept(resultMap);
                                    }));
                }};

            mService.requestCheckContent(
                    featureType,
                    packMapIntoBundle(input),
                    configureRemoteCancellationFuture(cancellationSignal, callbackExecutor),
                    callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private Map<Integer, List<Integer>> unpackMapFromBundle(Bundle input) {
        Map<Integer, List<Integer>> map = new HashMap<>();
        for (String key : input.keySet()) {
            try {
                ArrayList<Integer> statusCodes = input.getIntegerArrayList(key);
                if (statusCodes != null) {
                    map.putIfAbsent(Integer.parseInt(key),
                            new ArrayList<>(statusCodes));
                } else {
                    Log.w(TAG,
                            "Bundle retrieved from remote service contains invalid Integer "
                                    + "ArrayList");
                }
            } catch (NumberFormatException e) {
                // ignore invalid key
                Log.w(TAG, "Bundle retrieved from remote service contains invalid keys");
            }
        }
        return map;
    }

    private Bundle packMapIntoBundle(Map<Integer, List<ParcelFileDescriptor>> input) {
        Bundle bundle = new Bundle();
        for (Integer key: input.keySet()) {
            bundle.putParcelableArrayList(Integer.toString(key),
                    new ArrayList<>(input.get(key)));
        }
        return bundle;
    }

    @Nullable
    private static AndroidFuture<IBinder> configureRemoteCancellationFuture(
            @Nullable CancellationSignal cancellationSignal, @NonNull Executor callbackExecutor) {
        if (cancellationSignal == null) {
            return null;
        }
        AndroidFuture<IBinder> cancellationFuture = new AndroidFuture<>();
        return cancellationFuture.whenCompleteAsync(
                (cancellationTransport, error) -> {
                    if (error != null || cancellationTransport == null) {
                        Log.e(TAG, "Unable to receive the remote cancellation signal.", error);
                    } else {
                        cancellationSignal.setRemote(
                                ICancellationSignal.Stub.asInterface(cancellationTransport));
                    }
                },
                callbackExecutor);
    }

    /**
     * Checks if the given feature setting is enabled for the current user.
     *
     * @param featureType The safety {@link FeatureType} to check.
     * @param cancellationSignal signal to invoke cancellation or
     * @param callbackExecutor executor to run the callback on.
     * @param isFeatureEnabledOutcomeReceiver to populate either feature is enabled or failure
     *     status code .
     */
    @RequiresPermission(Manifest.permission.CHECK_CONTENT_SAFETY)
    public void requestIsFeatureEnabled(
            @FeatureType int featureType,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull OutcomeReceiver<Boolean, FeatureException>
                    isFeatureEnabledOutcomeReceiver) {
        try {
            IIsFeatureEnabledCallback callback = new IIsFeatureEnabledCallback.Stub() {
                @Override
                public void onSuccess(boolean isFeatureEnabledResult) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> isFeatureEnabledOutcomeReceiver
                                    .onResult(isFeatureEnabledResult)));
                }

                @Override
                public void onFailure(int errorCode) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> isFeatureEnabledOutcomeReceiver
                                    .onError(new FeatureException(errorCode))));
                }
            };

            mService.requestIsFeatureEnabled(
                    featureType,
                    configureRemoteCancellationFuture(cancellationSignal, callbackExecutor),
                    callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * ParcelFileDescriptors annotated with this type will be validated that they are
     * in-effect read-only when passed via Binder IPC for example
     *  {@link android.os.ParcelFileDescriptor#MODE_READ_ONLY}
     *
     * The system-server might throw a {@link android.os.BadParcelableException}
     * if the validation fails.
     *
     * @hide
     */
    @Target({ElementType.PARAMETER, ElementType.FIELD})
    public @interface CheckContentParams {}
}
