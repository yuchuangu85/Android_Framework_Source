/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.app.ondeviceintelligence.embedding;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.ondeviceintelligence.Content;
import android.app.ondeviceintelligence.DownloadCallback;
import android.app.ondeviceintelligence.ModelDownloadCallback;
import android.app.ondeviceintelligence.Feature;
import android.app.ondeviceintelligence.OnDeviceIntelligenceException;
import android.app.ondeviceintelligence.OnDeviceIntelligenceManager;
import android.app.ondeviceintelligence.OnDeviceModel;
import android.app.ondeviceintelligence.FeatureDetails;
import android.app.ondeviceintelligence.TokenInfo;
import android.app.ondeviceintelligence.flags.Flags;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.LocaleList;
import android.os.OutcomeReceiver;
import android.os.PersistableBundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Represents a specific on-device embedding model.
 *
 * <p>This class provides methods to query model information, check availability, download the
 * model, and perform inference to generate embeddings.
 *
 * <p>Embeddings are vector representations of data (e.g., text) that capture semantic meaning. They
 * are useful for applications such as semantic search, clustering, and similarity detection.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ON_DEVICE_INTELLIGENCE_26Q2)
public final class EmbeddingModel implements OnDeviceModel, Parcelable {
    /** @hide */
    @IntDef(value = {MODALITY_TEXT, MODALITY_IMAGE, MODALITY_AUDIO})
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_USE, ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
    public @interface Modality {}

    /** Text modality. */
    public static final int MODALITY_TEXT = 0;

    /** Image modality. */
    public static final int MODALITY_IMAGE = 1;

    /** Audio modality. */
    public static final int MODALITY_AUDIO = 2;

    private OnDeviceIntelligenceManager mManager;
    private final Feature mFeature;
    private final String mModelSignature;
    private final int mDimension;
    private final int[] mSupportedModalities;
    private final int mMaxTokenLimit;
    private final LocaleList mSupportedLocales;

    /**
     * Constructs a new {@link EmbeddingModel}.
     *
     * @param feature The internal feature.
     * @param modelSignature The signature of the model.
     * @param dimension The dimension of the embeddings.
     * @param supportedModalities The list of supported modalities.
     * @param maxTokenLimit The maximum token limit supported by the model.
     * @param supportedLocales The list of supported locales.
     */
    public EmbeddingModel(
            @NonNull Feature feature,
            @NonNull String modelSignature,
            @IntRange(from = 1) int dimension,
            @NonNull List<@Modality Integer> supportedModalities,
            @IntRange(from = 1) int maxTokenLimit,
            @NonNull LocaleList supportedLocales) {
        mFeature = Objects.requireNonNull(feature);
        mModelSignature = Objects.requireNonNull(modelSignature);
        mDimension = dimension;
        mSupportedModalities =
                Objects.requireNonNull(supportedModalities).stream().mapToInt(i -> i).toArray();
        mMaxTokenLimit = maxTokenLimit;
        mSupportedLocales = Objects.requireNonNull(supportedLocales);
    }

    /**
     * Sets the manager for this model.
     *
     * @hide
     */
    public void setOnDeviceIntelligenceManager(@NonNull OnDeviceIntelligenceManager manager) {
        mManager = manager;
    }

    /** Returns the unique signature of the model. */
    @Override
    @NonNull
    public String getModelSignature() {
        return mModelSignature;
    }

    /** Returns the name of the model. */
    @Override
    @NonNull
    public String getName() {
        String name = mFeature.getName();
        return name != null ? name : mModelSignature;
    }

    /** Returns the dimension of the embeddings generated by this model. */
    public int getDimension() {
        return mDimension;
    }

    /**
     * Returns the maximum token limit (number of tokens) supported by the model.
     */
    @Override
    @IntRange(from = 1)
    public long getMaxTokenLimit() {
        return mMaxTokenLimit;
    }

    /**
     * Returns the list of supported locales.
     */
    @NonNull
    public LocaleList getSupportedLocales() {
        return mSupportedLocales;
    }


    /** Returns the list of supported modalities for this model. */
    @NonNull
    public List<@Modality Integer> getSupportedModalities() {
        return Arrays.stream(mSupportedModalities).boxed().collect(Collectors.toList());
    }

    /**
     * Retrieves the current status of the model.
     *
     * @param executor The executor on which to invoke the callback.
     * @param callback The callback to receive the model status (e.g.,
     *     {@link OnDeviceModel#MODEL_STATUS_DOWNLOADABLE},
     *     {@link OnDeviceModel#MODEL_STATUS_AVAILABLE}).
     */
    @Override
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void getStatus(
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<@Status Integer, OnDeviceIntelligenceException> callback) {
        try {
            Objects.requireNonNull(
                    mManager, "OnDeviceIntelligenceManager instance not attached to this Model.");
            mManager.getFeatureDetails(
                    mFeature,
                    executor,
                    new OutcomeReceiver<>() {
                        @Override
                        public void onResult(FeatureDetails result) {
                            int status = result.getFeatureStatus();
                            int modelStatus = switch (status) {
                                case FeatureDetails.FEATURE_STATUS_DOWNLOADABLE ->
                                        MODEL_STATUS_DOWNLOADABLE;
                                case FeatureDetails.FEATURE_STATUS_DOWNLOADING ->
                                        MODEL_STATUS_DOWNLOADING;
                                case FeatureDetails.FEATURE_STATUS_AVAILABLE ->
                                        MODEL_STATUS_AVAILABLE;
                                default -> MODEL_STATUS_UNAVAILABLE;
                            };
                            callback.onResult(modelStatus);
                        }

                        @Override
                        public void onError(OnDeviceIntelligenceException error) {
                            callback.onError(error);
                        }
                    });
        } catch (IllegalStateException e) {
            callback.onError(
                    new OnDeviceIntelligenceException(
                            OnDeviceIntelligenceException
                                    .PROCESSING_ERROR_SERVICE_NOT_CONFIGURED));
        }
    }

    /**
     * Requests the download of the files associated with this model.
     *
     * @param signal A {@link CancellationSignal} to cancel the download operation.
     * @param executor The executor on which to invoke the callback.
     * @param callback The callback to receive download progress and status updates.
     */
    @Override
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void download(
            @Nullable CancellationSignal signal,
            @NonNull Executor executor,
            @NonNull ModelDownloadCallback callback) {
        Objects.requireNonNull(
                mManager, "OnDeviceIntelligenceManager instance not attached to this Model.");
        try {
            mManager.requestFeatureDownload(
                    mFeature,
                    signal,
                    executor,
                    new DownloadCallback() {
                        @Override
                        public void onDownloadStarted(long bytesToDownload) {
                            callback.onDownloadStarted(bytesToDownload);
                        }

                        @Override
                        public void onDownloadProgress(long totalBytesDownloaded) {
                            callback.onDownloadProgress(totalBytesDownloaded);
                        }

                        @Override
                        public void onDownloadFailed(
                                int failureStatus,
                                @Nullable String errorMessage,
                                @NonNull PersistableBundle errorParams) {
                            callback.onDownloadFailed(failureStatus, errorMessage);
                        }

                        @Override
                        public void onDownloadCompleted(@NonNull PersistableBundle downloadParams) {
                            callback.onDownloadCompleted();
                        }
                    });
        } catch (IllegalStateException e) {
            callback.onDownloadFailed(
                    DownloadCallback.DOWNLOAD_FAILURE_STATUS_UNAVAILABLE,
                    "Service not configured.");
        }
    }

    /**
     * Counts the number of tokens in the given content using the model's tokenizer. This is useful
     * for ensuring that the input content fits within the model's maximum token limit.
     *
     * @param requestContent The content payload to tokenize.
     * @param executor The executor to run the callback on.
     * @param callback The callback to receive the token count.
     */
    @Override
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void countTokens(
            @NonNull Content requestContent,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Long, OnDeviceIntelligenceException> callback) {
        try {
            mManager.requestTokenInfo(
                    mFeature,
                    requestContent,
                    null,
                    executor,
                    new OutcomeReceiver<TokenInfo, OnDeviceIntelligenceException>() {
                        @Override
                        public void onResult(TokenInfo result) {
                            callback.onResult(result.getCount());
                        }

                        @Override
                        public void onError(OnDeviceIntelligenceException error) {
                            callback.onError(error);
                        }
                    });
        } catch (IllegalStateException e) {
            callback.onError(
                    new OnDeviceIntelligenceException(
                            OnDeviceIntelligenceException
                                    .PROCESSING_ERROR_SERVICE_NOT_CONFIGURED));
        }
    }

    /**
     * Generates embeddings for the provided content in the request.
     *
     * @param request The embedding request containing the list of content.
     * @param signal A {@link CancellationSignal} to cancel the operation.
     * @param executor The executor on which to invoke the callback.
     * @param callback The callback to receive the generated {@link EmbeddingResponse} or an error.
     */
    @RequiresPermission(Manifest.permission.USE_ON_DEVICE_INTELLIGENCE)
    public void generateEmbeddings(
            @NonNull EmbeddingRequest request,
            @Nullable CancellationSignal signal,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<EmbeddingResponse, OnDeviceIntelligenceException> callback) {
        Objects.requireNonNull(
                mManager, "OnDeviceIntelligenceManager instance not attached to this Model.");
        try {
        mManager.generateEmbeddings(mFeature, request, signal, executor, callback);
        } catch (IllegalStateException e) {
            callback.onError(
                    new OnDeviceIntelligenceException(
                            OnDeviceIntelligenceException
                                    .PROCESSING_ERROR_SERVICE_NOT_CONFIGURED));
        } catch (RuntimeException e) {
            if (e.getCause() instanceof android.os.TransactionTooLargeException) {
                callback.onError(
                        new OnDeviceIntelligenceException(
                                OnDeviceIntelligenceException
                                        .PROCESSING_ERROR_REQUEST_TOO_LARGE));
            } else {
                throw e;
            }
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mFeature, flags);
        dest.writeString8(mModelSignature);
        dest.writeInt(mDimension);
        dest.writeIntArray(mSupportedModalities);
        dest.writeInt(mMaxTokenLimit);
        dest.writeTypedObject(mSupportedLocales, flags);
    }

    public static final @NonNull Creator<EmbeddingModel> CREATOR =
            new Creator<EmbeddingModel>() {
                @Override
                public EmbeddingModel createFromParcel(Parcel in) {
                    return new EmbeddingModel(in);
                }

                @Override
                public EmbeddingModel[] newArray(int size) {
                    return new EmbeddingModel[size];
                }
            };

    private EmbeddingModel(Parcel in) {
        mFeature = in.readTypedObject(Feature.CREATOR);
        mModelSignature = in.readString8();
        mDimension = in.readInt();
        mSupportedModalities = in.createIntArray();
        mMaxTokenLimit = in.readInt();
        mSupportedLocales = in.readTypedObject(LocaleList.CREATOR);
    }
}
