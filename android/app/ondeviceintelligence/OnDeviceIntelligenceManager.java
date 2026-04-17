/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.ondeviceintelligence;

import static android.app.ondeviceintelligence.flags.Flags.FLAG_ON_DEVICE_INTELLIGENCE_25Q4;
import static android.app.ondeviceintelligence.flags.Flags.FLAG_ON_DEVICE_INTELLIGENCE_26Q2;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.CurrentTimeMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.OutcomeReceiver;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.service.ondeviceintelligence.OnDeviceSandboxedInferenceService;
import android.system.OsConstants;
import android.util.Log;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import android.app.ondeviceintelligence.embedding.EmbeddingModel;
import android.app.ondeviceintelligence.embedding.EmbeddingRequest;
import android.app.ondeviceintelligence.embedding.EmbeddingResponse;
import android.app.ondeviceintelligence.embedding.IEmbeddingCallback;
import android.app.ondeviceintelligence.embedding.IEmbeddingModelCallback;
import android.app.ondeviceintelligence.embedding.IEmbeddingModelListCallback;
import android.app.ondeviceintelligence.imagedescription.ImageDescriptionModel;
import android.app.ondeviceintelligence.imagedescription.ImageDescriptionRequest;
import android.app.ondeviceintelligence.imagedescription.ImageDescriptionCallback;
import android.app.ondeviceintelligence.imagedescription.ImageDescriptionResponse;
import android.app.ondeviceintelligence.imagedescription.IImageDescriptionCallback;
import android.app.ondeviceintelligence.imagedescription.IImageDescriptionModelCallback;
import android.app.ondeviceintelligence.imagedescription.IImageDescriptionModelListCallback;
import android.app.ondeviceintelligence.utils.BinderUtils;

import com.android.internal.infra.AndroidFuture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongConsumer;

/**
 * Allows granted apps to manage on-device intelligence service configured on the device. Typical
 * calling pattern will be to query and setup a required feature before proceeding to request
 * processing.
 *
 * The contracts in this Manager class are designed to be open-ended in general, to allow
 * interoperability. Therefore, it is recommended that implementations of this system-service
 * expose this API to the clients via a separate sdk or library which has more defined contract.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.ON_DEVICE_INTELLIGENCE_SERVICE)
public final class OnDeviceIntelligenceManager {
    /**
     * @hide
     */
    public static final String API_VERSION_BUNDLE_KEY = "ApiVersionBundleKey";

    /**
     * @hide
     */
    public static final String AUGMENT_REQUEST_CONTENT_BUNDLE_KEY =
            "AugmentRequestContentBundleKey";

    /**
     * The key for a boolean extra in the request {@link Bundle} to indicate if the caller wants to
     * receive {@link InferenceInfo} in the {@link ProcessingCallback#onInferenceInfo} callback.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_ON_DEVICE_INTELLIGENCE_25Q4)
    public static final String KEY_REQUEST_INFERENCE_INFO = "request_inference_info";

    private static final String TAG = "OnDeviceIntelligence";
    private final Context mContext;
    private final IOnDeviceIntelligenceManager mService;

    private final Map<OnDeviceSandboxedInferenceService.LifecycleListener, ILifecycleListener.Stub>
            mLifecycleListeners = new ConcurrentHashMap<>();

    /**
     * @hide
     */
    public OnDeviceIntelligenceManager(Context context, IOnDeviceIntelligenceManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Asynchronously get the version of the underlying remote implementation.
     *
     * @param versionConsumer  consumer to populate the version of remote implementation.
     * @param callbackExecutor executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void getVersion(
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull LongConsumer versionConsumer) {
        try {
            RemoteCallback callback = new RemoteCallback(result -> {
                if (result == null) {
                    Binder.withCleanCallingIdentity(
                            () -> callbackExecutor.execute(() -> versionConsumer.accept(0)));
                }
                long version = result.getLong(API_VERSION_BUNDLE_KEY);
                Binder.withCleanCallingIdentity(
                        () -> callbackExecutor.execute(() -> versionConsumer.accept(version)));
            });
            mService.getVersion(callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Get package name configured for providing the remote implementation for this system service.
     */
    @Nullable
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public String getRemoteServicePackageName() {
        String result;
        try {
            result = mService.getRemoteServicePackageName();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return result;
    }

    /**
     * Asynchronously get feature for a given id.
     *
     * @param featureId        the identifier pointing to the feature.
     * @param featureReceiver  callback to populate the feature object for given identifier.
     * @param callbackExecutor executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void getFeature(
            int featureId,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull OutcomeReceiver<Feature, OnDeviceIntelligenceException> featureReceiver) {
        try {
            IFeatureCallback callback =
                    new IFeatureCallback.Stub() {
                        @Override
                        public void onSuccess(Feature result) {
                            Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                                    () -> featureReceiver.onResult(result)));
                        }

                        @Override
                        public void onFailure(int errorCode, String errorMessage,
                                PersistableBundle errorParams) {
                            Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                                    () -> featureReceiver.onError(
                                            new OnDeviceIntelligenceException(
                                                    errorCode, errorMessage, errorParams))));
                        }
                    };
            mService.getFeature(featureId, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Asynchronously get a list of features that are supported for the caller.
     *
     * @param featureListReceiver callback to populate the list of features.
     * @param callbackExecutor    executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void listFeatures(
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull OutcomeReceiver<List<Feature>, OnDeviceIntelligenceException> featureListReceiver) {
        try {
            IListFeaturesCallback callback =
                    new IListFeaturesCallback.Stub() {
                        @Override
                        public void onSuccess(List<Feature> result) {
                            Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                                    () -> featureListReceiver.onResult(result)));
                        }

                        @Override
                        public void onFailure(int errorCode, String errorMessage,
                                PersistableBundle errorParams) {
                            Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                                    () -> featureListReceiver.onError(
                                            new OnDeviceIntelligenceException(
                                                    errorCode, errorMessage, errorParams))));
                        }
                    };
            mService.listFeatures(callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Asynchronously get a list of features that are supported for the caller, with an option to
     * filter the features based on the provided params.
     *
     * @param featureParamsFilter params to be used for filtering the features.
     * @param callbackExecutor    executor to run the callback on.
     * @param featureListReceiver callback to populate the list of features.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    @FlaggedApi(FLAG_ON_DEVICE_INTELLIGENCE_25Q4)
    public void listFeatures(
            @NonNull PersistableBundle featureParamsFilter,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull OutcomeReceiver<List<Feature>,
                    OnDeviceIntelligenceException> featureListReceiver) {
        try {
            IListFeaturesCallback callback =
                    new IListFeaturesCallback.Stub() {
                        @Override
                        public void onSuccess(List<Feature> result) {
                            Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                                    () -> featureListReceiver.onResult(result)));
                        }

                        @Override
                        public void onFailure(int errorCode, String errorMessage,
                                PersistableBundle errorParams) {
                            Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                                    () -> featureListReceiver.onError(
                                            new OnDeviceIntelligenceException(
                                                    errorCode, errorMessage, errorParams))));
                        }
                    };
            mService.listFeaturesWithFilter(featureParamsFilter, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * This method should be used to fetch details about a feature which need some additional
     * computation, that can be inefficient to return in all calls to {@link #getFeature}. Callers
     * and implementation can utilize the {@link Feature#getFeatureParams()} to pass hint on what
     * details are expected by the caller.
     *
     * @param feature                the feature to check status for.
     * @param featureDetailsReceiver callback to populate the feature details to.
     * @param callbackExecutor       executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void getFeatureDetails(@NonNull Feature feature,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull OutcomeReceiver<FeatureDetails, OnDeviceIntelligenceException> featureDetailsReceiver) {
        try {
            IFeatureDetailsCallback callback = new IFeatureDetailsCallback.Stub() {

                @Override
                public void onSuccess(FeatureDetails result) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> featureDetailsReceiver.onResult(result)));
                }

                @Override
                public void onFailure(int errorCode, String errorMessage,
                        PersistableBundle errorParams) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> featureDetailsReceiver.onError(
                                    new OnDeviceIntelligenceException(errorCode,
                                            errorMessage, errorParams))));
                }
            };
            mService.getFeatureDetails(feature, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * This method handles downloading all model and config files required to process requests
     * sent against a given feature. The caller can listen to updates on the download status via
     * the callback.
     *
     * Note: If a feature was already requested for downloaded previously, the onDownloadFailed
     * callback would be invoked with {@link DownloadCallback#DOWNLOAD_FAILURE_STATUS_DOWNLOADING}.
     * In such cases, clients should query the feature status via {@link #getFeatureDetails} to
     * check on the feature's download status.
     *
     * @param feature            feature to request download for.
     * @param callback           callback to populate updates about download status.
     * @param cancellationSignal signal to invoke cancellation on the operation in the remote
     *                           implementation.
     * @param callbackExecutor   executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void requestFeatureDownload(@NonNull Feature feature,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull DownloadCallback callback) {
        try {
            IDownloadCallback downloadCallback = new IDownloadCallback.Stub() {

                @Override
                public void onDownloadStarted(long bytesToDownload) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> callback.onDownloadStarted(bytesToDownload)));
                }

                @Override
                public void onDownloadProgress(long bytesDownloaded) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> callback.onDownloadProgress(bytesDownloaded)));
                }

                @Override
                public void onDownloadFailed(int failureStatus, String errorMessage,
                        PersistableBundle errorParams) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> callback.onDownloadFailed(failureStatus, errorMessage,
                                    errorParams)));
                }

                @Override
                public void onDownloadCompleted(PersistableBundle downloadParams) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> callback.onDownloadCompleted(downloadParams)));
                }
            };

            mService.requestFeatureDownload(feature,
                    configureRemoteCancellationFuture(cancellationSignal, callbackExecutor),
                    downloadCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * The methods computes the token related information for a given request payload using the
     * provided {@link Feature}.
     *
     * @param feature            feature associated with the request.
     * @param request            request and associated params represented by the Bundle
     *                           data.
     * @param outcomeReceiver    callback to populate the token info or exception in case of
     *                           failure.
     * @param cancellationSignal signal to invoke cancellation on the operation in the remote
     *                           implementation.
     * @param callbackExecutor   executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void requestTokenInfo(@NonNull Feature feature, @NonNull @InferenceParams Bundle request,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull OutcomeReceiver<TokenInfo,
                    OnDeviceIntelligenceException> outcomeReceiver) {
        try {
            ITokenInfoCallback callback = new ITokenInfoCallback.Stub() {
                @Override
                public void onSuccess(TokenInfo tokenInfo) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> outcomeReceiver.onResult(tokenInfo)));
                }

                @Override
                public void onFailure(int errorCode, String errorMessage,
                        PersistableBundle errorParams) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> outcomeReceiver.onError(
                                    new OnDeviceIntelligenceException(
                                            errorCode, errorMessage, errorParams))));
                }
            };

            mService.requestTokenInfo(feature, request,
                    configureRemoteCancellationFuture(cancellationSignal, callbackExecutor),
                    callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * The methods computes the token related information for a given request payload using the
     * provided {@link Feature}.
     *
     * @param feature            feature associated with the request.
     * @param content            content payload to tokenize.
     * @param cancellationSignal signal to invoke cancellation on the operation in the remote
     *                           implementation.
     * @param callbackExecutor   executor to run the callback on.
     * @param outcomeReceiver    callback to populate the token info or exception in case of
     *                           failure.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    @FlaggedApi(FLAG_ON_DEVICE_INTELLIGENCE_26Q2)
    public void requestTokenInfo(@NonNull Feature feature, @NonNull Content content,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull OutcomeReceiver<TokenInfo,
                    OnDeviceIntelligenceException> outcomeReceiver) {
        try {
            ITokenInfoCallback callback = new ITokenInfoCallback.Stub() {
                @Override
                public void onSuccess(TokenInfo tokenInfo) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> outcomeReceiver.onResult(tokenInfo)));
                }

                @Override
                public void onFailure(int errorCode, String errorMessage,
                        PersistableBundle errorParams) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> outcomeReceiver.onError(
                                    new OnDeviceIntelligenceException(
                                            errorCode, errorMessage, errorParams))));
                }
            };

            mService.requestTokenInfoWithContent(feature, content,
                    configureRemoteCancellationFuture(cancellationSignal, callbackExecutor),
                    callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /**
     * Asynchronously Process a request based on the associated params, to populate a
     * response in
     * {@link OutcomeReceiver#onResult} callback or failure callback status code if there
     * was a
     * failure.
     *
     * @param feature            feature associated with the request.
     * @param request            request and associated params represented by the Bundle
     *                           data.
     * @param requestType        type of request being sent for processing the content.
     * @param cancellationSignal signal to invoke cancellation.
     * @param processingSignal   signal to send custom signals in the
     *                           remote implementation.
     * @param callbackExecutor   executor to run the callback on.
     * @param processingCallback callback to populate the response content and
     *                           associated params.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void processRequest(@NonNull Feature feature, @NonNull @InferenceParams Bundle request,
            @RequestType int requestType,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable ProcessingSignal processingSignal,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull ProcessingCallback processingCallback) {
        try {
            IResponseCallback callback = new IResponseCallback.Stub() {
                @Override
                public void onSuccess(@InferenceParams Bundle result) {
                    Binder.withCleanCallingIdentity(() -> {
                        callbackExecutor.execute(() -> processingCallback.onResult(result));
                    });
                }

                @Override
                public void onFailure(int errorCode, String errorMessage,
                        PersistableBundle errorParams) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> processingCallback.onError(
                                    new OnDeviceIntelligenceException(
                                            errorCode, errorMessage, errorParams))));
                }

                @Override
                public void onDataAugmentRequest(@NonNull @InferenceParams Bundle request,
                        @NonNull RemoteCallback contentCallback) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> processingCallback.onDataAugmentRequest(request, result -> {
                                Bundle bundle = new Bundle();
                                bundle.putParcelable(AUGMENT_REQUEST_CONTENT_BUNDLE_KEY, result);
                                callbackExecutor.execute(() -> contentCallback.sendResult(bundle));
                            })));
                }

                @Override
                public void onInferenceInfo(InferenceInfo info) {
                    Binder.withCleanCallingIdentity(
                            () -> callbackExecutor.execute(
                                    () -> processingCallback.onInferenceInfo(info)));
                }
            };


            mService.processRequest(feature, request, requestType,
                    configureRemoteCancellationFuture(cancellationSignal, callbackExecutor),
                    configureRemoteProcessingSignalFuture(processingSignal, callbackExecutor),
                    callback);

        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Variation of {@link #processRequest} that asynchronously processes a request in a
     * streaming
     * fashion, where new content is pushed to caller in chunks via the
     * {@link StreamingProcessingCallback#onPartialResult}. After the streaming is complete,
     * the service should call {@link StreamingProcessingCallback#onResult} and can optionally
     * populate the complete the full response {@link Bundle} as part of the callback in cases
     * when the final response contains an enhanced aggregation of the contents already
     * streamed.
     *
     * @param feature                     feature associated with the request.
     * @param request                     request and associated params represented by the Bundle
     *                                    data.
     * @param requestType                 type of request being sent for processing the content.
     * @param cancellationSignal          signal to invoke cancellation.
     * @param processingSignal            signal to send custom signals in the
     *                                    remote implementation.
     * @param streamingProcessingCallback streaming callback to populate the response content and
     *                                    associated params.
     * @param callbackExecutor            executor to run the callback on.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void processRequestStreaming(@NonNull Feature feature,
            @NonNull @InferenceParams Bundle request,
            @RequestType int requestType,
            @Nullable CancellationSignal cancellationSignal,
            @Nullable ProcessingSignal processingSignal,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull StreamingProcessingCallback streamingProcessingCallback) {
        try {
            IStreamingResponseCallback callback = new IStreamingResponseCallback.Stub() {
                @Override
                public void onPartialResult(@InferenceParams Bundle result) {
                    Binder.withCleanCallingIdentity(() -> {
                        callbackExecutor.execute(
                                () -> streamingProcessingCallback.onPartialResult(result));
                    });
                }

                @Override
                public void onSuccess(@InferenceParams Bundle result) {
                    Binder.withCleanCallingIdentity(() -> {
                        callbackExecutor.execute(
                                () -> streamingProcessingCallback.onResult(result));
                    });
                }

                @Override
                public void onFailure(int errorCode, String errorMessage,
                        PersistableBundle errorParams) {
                    Binder.withCleanCallingIdentity(() -> {
                        callbackExecutor.execute(
                                () -> streamingProcessingCallback.onError(
                                        new OnDeviceIntelligenceException(
                                                errorCode, errorMessage, errorParams)));
                    });
                }


                @Override
                public void onDataAugmentRequest(@NonNull @InferenceParams Bundle content,
                        @NonNull RemoteCallback contentCallback) {
                    Binder.withCleanCallingIdentity(() -> callbackExecutor.execute(
                            () -> streamingProcessingCallback.onDataAugmentRequest(content,
                                    contentResponse -> {
                                        Bundle bundle = new Bundle();
                                        bundle.putParcelable(AUGMENT_REQUEST_CONTENT_BUNDLE_KEY,
                                                contentResponse);
                                        callbackExecutor.execute(
                                                () -> contentCallback.sendResult(bundle));
                                    })));
                }

                @Override
                public void onInferenceInfo(InferenceInfo info) {
                    Binder.withCleanCallingIdentity(() -> {
                        callbackExecutor.execute(
                                () -> streamingProcessingCallback.onInferenceInfo(info));
                    });
                }
            };

            mService.processRequestStreaming(
                    feature, request, requestType,
                    configureRemoteCancellationFuture(cancellationSignal, callbackExecutor),
                    configureRemoteProcessingSignalFuture(processingSignal, callbackExecutor),
                    callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * This is primarily intended to be used to attribute/blame on-device intelligence power usage,
     * via the configured remote implementation, to its actual caller.
     *
     * @param startTimeEpochMillis epoch millis used to filter the InferenceInfo events.
     * @return InferenceInfo events since the passed in startTimeEpochMillis.
     */
    @RequiresPermission(Manifest.permission.DUMP)
    @FlaggedApi(FLAG_ON_DEVICE_INTELLIGENCE_25Q4)
    public @NonNull List<InferenceInfo> getLatestInferenceInfo(@CurrentTimeMillisLong long startTimeEpochMillis) {
        try {
            return mService.getLatestInferenceInfo(startTimeEpochMillis);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a listener for inference service lifecycle events of the
     * configured {@link OnDeviceSandboxedInferenceService}. This method is
     * thread-safe.
     *
     * @param executor The executor to run the listener callbacks on.
     * @param listener The listener to register.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    @FlaggedApi(FLAG_ON_DEVICE_INTELLIGENCE_25Q4)
    public void registerInferenceServiceLifecycleListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnDeviceSandboxedInferenceService.LifecycleListener listener) {
        Objects.requireNonNull(executor, "Executor must not be null");
        Objects.requireNonNull(listener, "Listener must not be null");
        ILifecycleListener.Stub stub = new ILifecycleListener.Stub() {
            @Override
            public void onLifecycleEvent(int event, @NonNull Feature feature) {
                Binder.withCleanCallingIdentity(
                        () -> executor.execute(() -> listener.onLifecycleEvent(event, feature)));
            }
        };

        mLifecycleListeners.put(listener, stub);
        try {
            mService.registerInferenceServiceLifecycleListener(stub);
        } catch (RemoteException e) {
            mLifecycleListeners.remove(listener);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters a {@link OnDeviceSandboxedInferenceService.LifecycleListener}
     * if previous registered with
     * {@link #registerInferenceServiceLifecycleListener}, otherwise no-op.
     * This method is thread-safe.
     *
     * @param listener The listener to unregister.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    @FlaggedApi(FLAG_ON_DEVICE_INTELLIGENCE_25Q4)
    public void unregisterInferenceServiceLifecycleListener(
            @NonNull OnDeviceSandboxedInferenceService.LifecycleListener listener) {
        Objects.requireNonNull(listener, "Listener must not be null");
        ILifecycleListener.Stub stub = mLifecycleListeners.remove(listener);
        if (stub == null) {
            // Listener not registered or already unregistered.
            return;
        }
        try {
            mService.unregisterInferenceServiceLifecycleListener(stub);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Asynchronously lists the available {@link EmbeddingModel embedding models}.
     *
     * <p>The caller can use this method to discover available models and their signatures.
     * The model signature can then be cached and used to fetch the model directly via
     * {@link #getEmbeddingModel(String, Executor, OutcomeReceiver)} in future calls.
     *
     * @param callbackExecutor Executor to run the callback on.
     * @param callback         Callback to receive the list of {@link EmbeddingModel embedding
     *                         models} or an error.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    @FlaggedApi(FLAG_ON_DEVICE_INTELLIGENCE_26Q2)
    public void listEmbeddingModels(
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull
                    OutcomeReceiver<List<EmbeddingModel>, OnDeviceIntelligenceException> callback) {
        try {
            IEmbeddingModelListCallback internalCallback =
                    new IEmbeddingModelListCallback.Stub() {
                        @Override
                        public void onSuccess(List<EmbeddingModel> result) {
                            BinderUtils.withCleanCallingIdentity(
                                    () ->
                                            callbackExecutor.execute(
                                                    () ->
                                                            onGetEmbeddingModelsSuccess(
                                                                    result, callback)));
                        }

                        @Override
                        public void onFailure(
                                int errorCode, String errorMessage, PersistableBundle errorParams) {
                            BinderUtils.withCleanCallingIdentity(
                                    () ->
                                            callbackExecutor.execute(
                                                    () ->
                                                            onGetEmbeddingModelsFailure(
                                                                    errorCode,
                                                                    errorMessage,
                                                                    errorParams,
                                                                    callback)));
                        }
                    };
            mService.listEmbeddingModels(internalCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (IllegalStateException e) {
            callback.onError(
                    new OnDeviceIntelligenceException(
                            OnDeviceIntelligenceException.PROCESSING_ERROR_SERVICE_NOT_CONFIGURED,
                            "Service is not configured.",
                            null));
        }
    }

    /**
     * Asynchronously fetches a specific {@link EmbeddingModel} by its signature.
     *
     * <p>This allows direct access to a model if its signature is already known (e.g., cached from
     * a previous {@link #getEmbeddingModels(Executor, OutcomeReceiver)} call).
     *
     * @param modelSignature   The unique signature of the embedding model.
     * @param callbackExecutor Executor to run the callback on.
     * @param callback         Callback to receive the {@link EmbeddingModel} or an error.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    @FlaggedApi(FLAG_ON_DEVICE_INTELLIGENCE_26Q2)
    public void fetchEmbeddingModel(
            @NonNull String modelSignature,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull OutcomeReceiver<EmbeddingModel, OnDeviceIntelligenceException> callback) {
        try {
            IEmbeddingModelCallback internalCallback =
                    new IEmbeddingModelCallback.Stub() {
                        @Override
                        public void onSuccess(EmbeddingModel result) {
                            BinderUtils.withCleanCallingIdentity(
                                    () ->
                                            callbackExecutor.execute(
                                                    () -> {
                                                        result.setOnDeviceIntelligenceManager(
                                                            OnDeviceIntelligenceManager.this);
                                                        callback.onResult(result);
                                                    }));
                        }

                        @Override
                        public void onFailure(
                                int errorCode, String errorMessage, PersistableBundle errorParams) {
                            BinderUtils.withCleanCallingIdentity(
                                    () ->
                                            callbackExecutor.execute(
                                                    () ->
                                                        callback.onError(
                                                            new OnDeviceIntelligenceException(
                                                                            errorCode,
                                                                            errorMessage,
                                                                            errorParams))));
                        }
                    };
            mService.fetchEmbeddingModel(modelSignature, internalCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }  catch (IllegalStateException e) {
            callback.onError(
                    new OnDeviceIntelligenceException(
                            OnDeviceIntelligenceException.PROCESSING_ERROR_SERVICE_NOT_CONFIGURED,
                            "Service is not configured.",
                            null));
        }
    }

    /**
     * Asynchronously gets a list of available {@link ImageDescriptionModel}s.
     *
     * <p>The caller can use this method to discover available models and their signatures.
     * The model signature can then be cached and used to fetch the model directly via
     * {@link #fetchImageDescriptionModel(String, Executor, OutcomeReceiver)} in future calls.
     *
     * @param callbackExecutor Executor to run the callback on.
     * @param callback         Callback to receive the list of {@link ImageDescriptionModel}s or
     *                         an error.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    @FlaggedApi(FLAG_ON_DEVICE_INTELLIGENCE_26Q2)
    public void listImageDescriptionModels(
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull
                    OutcomeReceiver<List<ImageDescriptionModel>, OnDeviceIntelligenceException>
                            callback) {
        try {
            IImageDescriptionModelListCallback internalCallback =
                    new IImageDescriptionModelListCallback.Stub() {
                        @Override
                        public void onSuccess(List<ImageDescriptionModel> result) {
                            BinderUtils.withCleanCallingIdentity(
                                    () ->
                                            callbackExecutor.execute(
                                                    () ->
                                                            onGetImageDescriptionModelsSuccess(
                                                                    result, callback)));
                        }

                        @Override
                        public void onFailure(
                                int errorCode, String errorMessage, PersistableBundle errorParams) {
                            BinderUtils.withCleanCallingIdentity(
                                    () ->
                                            callbackExecutor.execute(
                                                    () ->
                                                            onGetImageDescriptionModelsFailure(
                                                                    errorCode,
                                                                    errorMessage,
                                                                    errorParams,
                                                                    callback)));
                        }
                    };
            mService.listImageDescriptionModels(internalCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (IllegalStateException e) {
            callback.onError(
                    new OnDeviceIntelligenceException(
                            OnDeviceIntelligenceException.PROCESSING_ERROR_SERVICE_NOT_CONFIGURED,
                            "Service is not configured.",
                            null));
        }
    }

    /**
     * Asynchronously gets a specific {@link ImageDescriptionModel} by its signature.
     *
     * <p>This allows direct access to a model if its signature is already known (e.g., cached from
     * a previous {@link #getImageDescriptionModels(Executor, OutcomeReceiver)} call).
     *
     * @param modelSignature   The unique signature of the image description model.
     * @param callbackExecutor Executor to run the callback on.
     * @param callback         Callback to receive the {@link ImageDescriptionModel} or an error.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    @FlaggedApi(FLAG_ON_DEVICE_INTELLIGENCE_26Q2)
    public void fetchImageDescriptionModel(
            @NonNull String modelSignature,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull
                    OutcomeReceiver<ImageDescriptionModel, OnDeviceIntelligenceException>
                            callback) {
        try {
            IImageDescriptionModelCallback internalCallback =
                    new IImageDescriptionModelCallback.Stub() {
                        @Override
                        public void onSuccess(ImageDescriptionModel result) {
                            BinderUtils.withCleanCallingIdentity(
                                    () ->
                                            callbackExecutor.execute(
                                                    () -> {
                                                        result.setOnDeviceIntelligenceManager(
                                                            OnDeviceIntelligenceManager.this);
                                                        callback.onResult(result);
                                                    }));
                        }

                        @Override
                        public void onFailure(
                                int errorCode, String errorMessage, PersistableBundle errorParams) {
                            BinderUtils.withCleanCallingIdentity(
                                    () ->
                                            callbackExecutor.execute(
                                                    () ->
                                                        callback.onError(
                                                            new OnDeviceIntelligenceException(
                                                                            errorCode,
                                                                            errorMessage,
                                                                            errorParams))));
                        }
                    };
            mService.fetchImageDescriptionModel(modelSignature, internalCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (IllegalStateException e) {
            callback.onError(
                    new OnDeviceIntelligenceException(
                            OnDeviceIntelligenceException.PROCESSING_ERROR_SERVICE_NOT_CONFIGURED,
                            "Service is not configured.",
                            null));
        }
    }

    /** @hide */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    @FlaggedApi(FLAG_ON_DEVICE_INTELLIGENCE_26Q2)
    public void generateEmbeddings(
            @NonNull Feature feature,
            @NonNull EmbeddingRequest request,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull OutcomeReceiver<EmbeddingResponse, OnDeviceIntelligenceException> callback) {
        try {
            IEmbeddingCallback internalCallback =
                    new IEmbeddingCallback.Stub() {
                        @Override
                        public void onSuccess(EmbeddingResponse result) {
                            BinderUtils.withCleanCallingIdentity(
                                    () ->
                                            callbackExecutor.execute(
                                                    () -> callback.onResult(result)));
                        }

                        @Override
                        public void onFailure(
                                int errorCode, String errorMessage, PersistableBundle errorParams) {
                            BinderUtils.withCleanCallingIdentity(
                                    () ->
                                            callbackExecutor.execute(
                                                    () ->
                                                        callback.onError(
                                                            new OnDeviceIntelligenceException(
                                                                            errorCode,
                                                                            errorMessage,
                                                                            errorParams))));
                        }
                    };
            mService.generateEmbeddings(
                    feature,
                    request,
                    configureRemoteCancellationFuture(cancellationSignal, callbackExecutor),
                    internalCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    @FlaggedApi(FLAG_ON_DEVICE_INTELLIGENCE_26Q2)
    public void generateImageDescription(
            @NonNull Feature feature,
            @NonNull ImageDescriptionRequest request,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull ImageDescriptionCallback callback) {
        try {
            IImageDescriptionCallback internalCallback =
                    new IImageDescriptionCallback.Stub() {
                        @Override
                        public void onSuccess(ImageDescriptionResponse result) {
                            BinderUtils.withCleanCallingIdentity(
                                    () ->
                                            callbackExecutor.execute(
                                                    () -> callback.onResult(result)));
                        }

                        @Override
                        public void onFailure(
                                int errorCode, String errorMessage, PersistableBundle errorParams) {
                            BinderUtils.withCleanCallingIdentity(
                                    () ->
                                            callbackExecutor.execute(
                                                    () ->
                                                        callback.onError(
                                                                new OnDeviceIntelligenceException(
                                                                            errorCode,
                                                                            errorMessage,
                                                                            errorParams))));
                        }

                        @Override
                        public void onNewText(String text) {
                            BinderUtils.withCleanCallingIdentity(
                                    () ->
                                            callbackExecutor.execute(
                                                    () -> callback.onNewText(text)));
                        }
                    };
            mService.generateImageDescription(
                    feature,
                    request,
                    configureRemoteCancellationFuture(cancellationSignal, callbackExecutor),
                    internalCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Request inference with provided Bundle and Params. */
    public static final int REQUEST_TYPE_INFERENCE = 0;

    /**
     * Prepares the remote implementation environment for e.g.loading inference runtime etc
     * .which
     * are time consuming beforehand to remove overhead and allow quick processing of requests
     * thereof.
     */
    public static final int REQUEST_TYPE_PREPARE = 1;

    /** Request Embeddings of the passed-in Bundle. */
    public static final int REQUEST_TYPE_EMBEDDINGS = 2;

    /**
     * @hide
     */
    @IntDef(value = {
            REQUEST_TYPE_INFERENCE,
            REQUEST_TYPE_PREPARE,
            REQUEST_TYPE_EMBEDDINGS
    })
    @Target({ElementType.TYPE_USE, ElementType.METHOD, ElementType.PARAMETER,
            ElementType.FIELD})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RequestType {
    }

    /**
     * {@link Bundle}s annotated with this type will be validated that they are in-effect read-only
     * when passed via Binder IPC. Following restrictions apply :
     * <ul>
     * <li> {@link PersistableBundle}s are allowed.</li>
     * <li> Any primitive types or their collections can be added as usual.</li>
     * <li>IBinder objects should *not* be added.</li>
     * <li>Parcelable data which has no active-objects, should be added as
     * {@link Bundle#putByteArray}</li>
     * <li>Parcelables have active-objects, only following types will be allowed</li>
     * <ul>
     *  <li>{@link android.os.ParcelFileDescriptor} opened in
     *  {@link android.os.ParcelFileDescriptor#MODE_READ_ONLY}</li>
     * </ul>
     * </ul>
     *
     * In all other scenarios the system-server might throw a
     * {@link android.os.BadParcelableException} if the Bundle validation fails.
     *
     * @hide
     */
    @Target({ElementType.PARAMETER, ElementType.FIELD})
    public @interface StateParams {
    }

    /**
     * This is an extension of {@link StateParams} but for purpose of inference few other types are
     * also allowed as read-only, as listed below.
     *
     * <li>{@link Bitmap} set as immutable.</li>
     * <li>{@link android.database.CursorWindow}</li>
     * <li>{@link android.os.SharedMemory} set to {@link OsConstants#PROT_READ}</li>
     * </ul>
     * </ul>
     *
     * In all other scenarios the system-server might throw a
     * {@link android.os.BadParcelableException} if the Bundle validation fails.
     *
     * @hide
     */
    @Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE_USE})
    public @interface InferenceParams {
    }

    /**
     * This is an extension of {@link StateParams} with the exception that it allows writing
     * {@link Bitmap} as part of the response.
     *
     * In all other scenarios the system-server might throw a
     * {@link android.os.BadParcelableException} if the Bundle validation fails.
     *
     * @hide
     */
    @Target({ElementType.PARAMETER, ElementType.FIELD})
    public @interface ResponseParams {
    }

    private void onGetEmbeddingModelsSuccess(
            List<EmbeddingModel> result,
            OutcomeReceiver<List<EmbeddingModel>, OnDeviceIntelligenceException> callback) {
        for (EmbeddingModel model : result) {
            model.setOnDeviceIntelligenceManager(this);
        }
        callback.onResult(result);
    }

    private void onGetEmbeddingModelsFailure(
            int errorCode,
            String errorMessage,
            PersistableBundle errorParams,
            OutcomeReceiver<List<EmbeddingModel>, OnDeviceIntelligenceException> callback) {
        callback.onError(
                new OnDeviceIntelligenceException(errorCode, errorMessage, errorParams));
    }

    private void onGetImageDescriptionModelsSuccess(
            List<ImageDescriptionModel> result,
            OutcomeReceiver<List<ImageDescriptionModel>, OnDeviceIntelligenceException> callback) {
        for (ImageDescriptionModel model : result) {
            model.setOnDeviceIntelligenceManager(this);
        }
        callback.onResult(result);
    }

    private void onGetImageDescriptionModelsFailure(
            int errorCode,
            String errorMessage,
            PersistableBundle errorParams,
            OutcomeReceiver<List<ImageDescriptionModel>, OnDeviceIntelligenceException> callback) {
        callback.onError(
                new OnDeviceIntelligenceException(errorCode, errorMessage, errorParams));
    }

    @Nullable
    private static AndroidFuture<IBinder> configureRemoteCancellationFuture(
            @Nullable CancellationSignal cancellationSignal,
            @NonNull Executor callbackExecutor) {
        if (cancellationSignal == null) {
            return null;
        }
        AndroidFuture<IBinder> cancellationFuture = new AndroidFuture<>();
        cancellationFuture.whenCompleteAsync(
                (cancellationTransport, error) -> {
                    if (error != null || cancellationTransport == null) {
                        Log.e(TAG, "Unable to receive the remote cancellation signal.", error);
                    } else {
                        cancellationSignal.setRemote(
                                ICancellationSignal.Stub.asInterface(cancellationTransport));
                    }
                }, callbackExecutor);
        return cancellationFuture;
    }

    @Nullable
    private static AndroidFuture<IBinder> configureRemoteProcessingSignalFuture(
            ProcessingSignal processingSignal, Executor executor) {
        if (processingSignal == null) {
            return null;
        }
        AndroidFuture<IBinder> processingSignalFuture = new AndroidFuture<>();
        processingSignalFuture.whenCompleteAsync(
                (transport, error) -> {
                    if (error != null || transport == null) {
                        Log.e(TAG, "Unable to receive the remote processing signal.", error);
                    } else {
                        processingSignal.setRemote(IProcessingSignal.Stub.asInterface(transport));
                    }
                }, executor);
        return processingSignalFuture;
    }


}
