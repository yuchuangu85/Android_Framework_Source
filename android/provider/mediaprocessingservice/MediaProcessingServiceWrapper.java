/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.provider.mediaprocessingservice;

import android.annotation.NonNull;
import android.os.RemoteException;
import android.util.Log;

import com.android.providers.media.flags.Flags;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Wrapper defined to handle async calls to MediaProcessingService.
 * @hide
 */
public final class MediaProcessingServiceWrapper {

    private static final String TAG = "MPServiceWrapper";

    private final IMediaProcessingService mMediaProcessingService;

    private final ExecutorService mExecutorService;

    public MediaProcessingServiceWrapper(@NonNull IMediaProcessingService mediaProcessingService) {
        this.mMediaProcessingService = mediaProcessingService;
        mExecutorService = Executors.newFixedThreadPool(3);
    }

    /**
     * Shuts down the executor service. Wait up to 30 seconds for existing tasks to complete
     * before terminating.
     */
    public void shutdown() {
        if (!mExecutorService.isShutdown()) {
            mExecutorService.shutdown();
            try {
                if (!mExecutorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    mExecutorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                mExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Gets the map of processing types requested per media type from MediaProcessingService.
     */
    public Map<Integer, Integer> getProcessingRequestedPerMediaType(int serviceTimeoutInSeconds)
            throws Exception {
        if (!Flags.enableMediaProcessingService()) {
            throw new UnsupportedOperationException("MediaProcessingService is not enabled");
        }

        return mExecutorService.submit(() -> {
            try {
                return mMediaProcessingService.getProcessingRequestedPerMediaType();
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in getProcessingRequestedPerMediaType", e);
                throw new RuntimeException(e);
            }
        }).get(serviceTimeoutInSeconds, TimeUnit.SECONDS);
    }

    /**
     * Gets the processing limit (batch size) from MediaProcessingService.
     */
    public int getProcessingLimit(int serviceTimeoutInSeconds) throws Exception {
        if (!Flags.enableMediaProcessingService()) {
            throw new UnsupportedOperationException("MediaProcessingService is not enabled");
        }

        return mExecutorService.submit(() -> {
            try {
                return mMediaProcessingService.getProcessingLimit();
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in getProcessingLimit", e);
                throw new RuntimeException(e);
            }
        }).get(serviceTimeoutInSeconds, TimeUnit.SECONDS);
    }

    /**
     * Processes a batch of media requests asynchronously via MediaProcessingService.
     */
    public List<MediaProcessingResponse> processMedia(
            @NonNull List<MediaProcessingRequest> requests, int serviceTimeoutInSeconds)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (!Flags.enableMediaProcessingService()) {
            throw new UnsupportedOperationException("MediaProcessingService is not enabled");
        }

        Future<List<MediaProcessingResponse>> task = mExecutorService.submit(() -> {
            CompletableFuture<List<MediaProcessingResponse>> future = new CompletableFuture<>();

            IMediaProcessingCallback callback = new IMediaProcessingCallback.Stub() {
                @Override
                public void onResult(List<MediaProcessingResponse> result) {
                    future.complete(result);
                }

                @Override
                public void onError(ErrorMessage error) {
                    // Propagate the error message as an exception to the caller
                    Log.e(TAG, "Error in processMedia: " + error);
                    future.completeExceptionally(
                            new RuntimeException("MediaProcessingService Error: " + error));
                }
            };

            try {
                mMediaProcessingService.processMedia(requests, callback);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException calling processMedia", e);
                future.completeExceptionally(e);
            }

            return future.get();
        });

        try {
            return task.get(serviceTimeoutInSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            Log.w(TAG, "Media processing timed out. Task cancelled.");
            throw e;
        }
    }

    /**
     * Gets an embedding vector for a search query text asynchronously.
     */
    public QueryProcessingResponse getEmbeddingVectorForSearchText(@NonNull String searchQuery,
            int serviceTimeoutInSeconds) throws Exception {
        if (!Flags.enableMediaProcessingService()) {
            throw new UnsupportedOperationException("MediaProcessingService is not enabled");
        }

        return mExecutorService.submit(() -> {
            CompletableFuture<QueryProcessingResponse> future = new CompletableFuture<>();

            IQueryProcessingCallback callback = new IQueryProcessingCallback.Stub() {
                @Override
                public void onResult(QueryProcessingResponse result) {
                    future.complete(result);
                }

                @Override
                public void onError(ErrorMessage error) {
                    Log.e(TAG, "Error in getEmbeddingVectorForSearchText: " + error);
                    future.completeExceptionally(
                            new RuntimeException("MediaProcessingService Error: " + error));
                }
            };

            try {
                mMediaProcessingService.getEmbeddingVectorForSearchText(searchQuery, callback);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException calling getEmbeddingVectorForSearchText", e);
                future.completeExceptionally(e);
            }

            return future.get();
        }).get(serviceTimeoutInSeconds, TimeUnit.SECONDS);
    }
}
