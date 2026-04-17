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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.ondeviceintelligence.InferenceInfo;
import android.os.Bundle;
import android.app.ondeviceintelligence.OnDeviceIntelligenceManager.InferenceParams;
import android.app.ondeviceintelligence.OnDeviceIntelligenceManager.ResponseParams;

import java.util.function.Consumer;

/**
 * Callback to populate the processed response or any error that occurred during the
 * request processing. This callback also provides a method to request additional data to be
 * augmented to the request-processing, using the partial response that was already
 * processed in the remote implementation.
 *
 * @hide
 */
@SystemApi
public interface ProcessingCallback {
    /**
     * Invoked when request has been processed and result is ready to be propagated to the
     * caller.
     *
     * @param result Response to be passed as a result.
     */
    void onResult(@NonNull @ResponseParams Bundle result);

    /**
     * Called when the request processing fails. The failure details are indicated by the
     * {@link OnDeviceIntelligenceException} passed as an argument to this method.
     *
     * @param error An exception with more details about the error that occurred.
     */
    void onError(@NonNull OnDeviceIntelligenceException error);

    /**
     * Callback to be invoked in cases where the remote service needs to perform retrieval or
     * transformation operations based on a partially processed request, in order to augment the
     * final response, by using the additional context sent via this callback.
     *
     * @param processedContent The content payload that should be used to augment ongoing request.
     * @param contentConsumer  The augmentation data that should be sent to remote
     *                         service for further processing a request. Bundle passed in here is
     *                         expected to be non-null or EMPTY when there is no response.
     */
    default void onDataAugmentRequest(
            @NonNull @ResponseParams Bundle processedContent,
            @NonNull Consumer<@InferenceParams Bundle> contentConsumer) {
        contentConsumer.accept(Bundle.EMPTY);
    }

    /**
     * Invoked when inference info is available for the given request.
     * This callback is invoked only when the
     * {@link OnDeviceIntelligenceManager#KEY_REQUEST_INFERENCE_INFO}
     * is set to true in the associated request {@link Bundle}.
     *
     * @param info the inference info associated with the request.
     * @see InferenceInfo
     */
    @FlaggedApi(FLAG_ON_DEVICE_INTELLIGENCE_25Q4)
    default void onInferenceInfo(@NonNull InferenceInfo info) {
    }
}
