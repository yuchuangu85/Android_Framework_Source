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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.ondeviceintelligence.Content;
import android.app.ondeviceintelligence.flags.Flags;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Common interface for all on-device models.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ON_DEVICE_INTELLIGENCE_26Q2)
public interface OnDeviceModel {
    /** Model is unavailable. */
    public static final int MODEL_STATUS_UNAVAILABLE = 0;

    /** Model can be downloaded. */
    public static final int MODEL_STATUS_DOWNLOADABLE = 1;

    /** Model is being downloaded. */
    public static final int MODEL_STATUS_DOWNLOADING = 2;

    /** Model is available for use. */
    public static final int MODEL_STATUS_AVAILABLE = 3;

    /** @hide */
    @IntDef(
            value = {
                MODEL_STATUS_UNAVAILABLE,
                MODEL_STATUS_DOWNLOADABLE,
                MODEL_STATUS_DOWNLOADING,
                MODEL_STATUS_AVAILABLE,
            })
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_USE, ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
    @interface Status {}

    /**
     * Returns the unique signature of this model.
     *
     * <p>This can be used for example in the {@link android.app.appsearch.EmbeddingVector}.
     *
     * <p>The signature should typically include the hosting package name, model name, and model
     * version, such that the tuple is unique globally across time.
     *
     * @return The unique signature of this model.
     */
    @NonNull
    String getModelSignature();

    /** Returns the user-friendly name of this model. */
    @NonNull
    String getName();

    /**
     * Checks the current availability status of the model (e.g., {@link
     * #MODEL_STATUS_DOWNLOADABLE}, {@link #MODEL_STATUS_DOWNLOADING}, {@link
     * #MODEL_STATUS_AVAILABLE}).
     *
     * @param executor The executor to run the callback on.
     * @param callback The callback to populate the status of the model.
     */
    void getStatus(
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<@Status Integer, OnDeviceIntelligenceException> callback);

    /**
     * Triggers the download of the model assets.
     *
     * @param signal The signal to invoke cancellation on the operation in the remote
     *     implementation.
     * @param executor The executor to run the callback on.
     * @param callback The callback to populate updates about download status.
     */
    void download(
            @Nullable CancellationSignal signal,
            @NonNull Executor executor,
            @NonNull ModelDownloadCallback callback);

    /**
     * Returns the maximum context length (in tokens) supported by the model.
     *
     * @return The maximum context length.
     */
    long getMaxTokenLimit();

    /**
     * Counts the number of tokens in the given content using the model's tokenizer. This is useful
     * for ensuring that the input content fits within the model's maximum token limit.
     *
     * @param requestContent The content payload to tokenize.
     * @param executor The executor to run the callback on.
     * @param callback The callback to receive the token count.
     */
    void countTokens(
            @NonNull Content requestContent,
            @NonNull Executor executor,
            @NonNull OutcomeReceiver<Long, OnDeviceIntelligenceException> callback);
}
