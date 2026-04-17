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

package android.npumanager;

import android.annotation.FlaggedApi;
import android.annotation.SystemApi;

/**
 * Callback interface for model load requests.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
public interface ModelLoadRequestCallback {
    /**
     * Callback delivering the status of the model load request.
     *
     * <p>When the status is {@link NpuManager#NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW}, the app can load
     * the model. The app should call {@link
     * ModelLoadStatusListener#notifyModelLoaded(ModelLoadRequest)} on {@code listener} to notify
     * the Model Manager that the model has been loaded.
     *
     * <p>When the status is {@link NpuManager#NPU_MODEL_LOAD_STATUS_WAIT_FOR_UNLOAD}, the system is
     * in the process of unloading a model to free up memory such that this model load can proceed.
     * The app should receive a call to {@link #onCanLoadModel(ModelLoadRequest)} soon and should
     * wait for that to load the model.
     *
     * <p>When the status is {@link NpuManager#NPU_MODEL_LOAD_STATUS_NOT_PRIORITIZED}, the app is
     * not currently a high enough priority to load the model. There may be higher priority jobs
     * that need to complete before the model load may proceed.
     *
     * @param status The status of the model load request.
     * @param listener A listener that should be called to notify the Model Manager that the model
     *     has been loaded.
     */
    public void onCanLoadModel(
            ModelLoadRequest request,
            @NpuManager.NpuModelLoadStatus int status,
            NpuManager.ModelLoadStatusListener listener);

    /**
     * The app should unload the model to free up memory.
     *
     * @param request The model load request that resulted in the model being loaded and is now has
     *     been unloaded.
     */
    public void onRequestUnloadModel(ModelLoadRequest request);

    /**
     * The model load request has completed and there will be no further updates to the request.
     *
     * @param status The status of the model load request.
     */
    public void onModelLoadRequestComplete(
            ModelLoadRequest request, @NpuManager.NpuModelLoadStatus int status);
}
