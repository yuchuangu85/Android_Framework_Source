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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.ondeviceintelligence.flags.Flags;

/**
 * Callback for monitoring the progress of a model download.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ON_DEVICE_INTELLIGENCE_26Q2)
public interface ModelDownloadCallback {
    /**
     * Called when the download has started.
     *
     * @param bytesToDownload The total number of bytes to be downloaded.
     */
    void onDownloadStarted(long bytesToDownload);

    /**
     * Called periodically to update the download progress.
     *
     * @param bytesDownloaded The number of bytes downloaded so far.
     */
    void onDownloadProgress(long bytesDownloaded);

    /**
     * Called when the download has failed.
     *
     * @param failureStatus The status code indicating the cause of the failure.
     * @param errorMessage An optional error message describing the failure.
     */
    void onDownloadFailed(int failureStatus, @Nullable String errorMessage);

    /**
     * Called when the download has completed successfully.
     */
    void onDownloadCompleted();
}
