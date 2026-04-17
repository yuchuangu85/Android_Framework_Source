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

package android.provider.mediaprocessingservice;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.android.providers.media.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;

/**
 * Abstract base class for the service that processes media files to extract metadata and labels
 * for search.
 * <p>
 * OEMs can provide an implementation of this service. The implementation must be
 * protected with the com.android.providers.media.permission.BIND_MEDIA_PROCESSING_SERVICE
 * permission in its AndroidManifest.xml declaration, amd it must handle the
 * {@link #SERVICE_INTERFACE} intent action.
 * <pre>
 * {@literal
 * <service android:name=".example.MyMediaProcessingService"
 *          android:exported="true"
 *          android:permission="android.permission.BIND_MEDIA_PROCESSING_SERVICE">
 *     <intent-filter>
 *         <action android:name="android.provider.mediaprocessingservice.MediaProcessingService"/>
 *         <category android:name="android.intent.category.DEFAULT"/>
 *     </intent-filter>
 * </service>}
 * </pre>
 * </p>
 *
 * OEMs can specify the default behavior through runtime resource overlay, by setting value of the
 * resource {@code config_default_media_processing_service_package}.
 * The overlayable subset which has this resource is {@code MediaProviderConfig}
 *
 * If no OEM implementation is defined, the default MediaProcessingService implementation is used.
 *
 * @hide
 */
@SystemApi
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@FlaggedApi(Flags.FLAG_ENABLE_MEDIA_PROCESSING_SERVICE)
public abstract class MediaProcessingService extends Service {

    private static final String TAG = "MediaProcessingService";

    /**
     * The action for the {@link android.content.Intent} used to bind to this service.
     * To be supported, the service must also require the
     * {@link MediaProcessingService#BIND_MEDIA_PROCESSING_SERVICE_PERMISSION}.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.provider.mediaprocessingservice.MediaProcessingService";

    /**
     * Permission required to protect {@link MediaProcessingService} instance. Implementation should
     * require this in the {@code permission} attribute in their {@code <service>} tag.
     */
    public static final String BIND_MEDIA_PROCESSING_SERVICE_PERMISSION =
            "com.android.providers.media.permission.BIND_MEDIA_PROCESSING_SERVICE";

    /**
     * Default value for the number of media processing requests in a single onProcessMedia call.
     */
    private static final int DEFAULT_PROCESSING_LIMIT = 10;

    /**
     * Processing Types which can be requested for each Media type
     *
     * @hide
     **/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {ProcessingType.DEFAULT_LOCATION_PROCESSING,
            ProcessingType.DEFAULT_METADATA_PROCESSING,
            ProcessingType.DEFAULT_MEDIA_LABELS_PROCESSING, ProcessingType.CUSTOM_OEM_PROCESSING})
    public @interface ProcessingType {
        // AOSP default for reverse geocoding location data
        int DEFAULT_LOCATION_PROCESSING = 1 << 1;
        // AOSP default for extracting file metadata
        int DEFAULT_METADATA_PROCESSING = 1 << 2;
        // AOSP default for media label extraction
        int DEFAULT_MEDIA_LABELS_PROCESSING = 1 << 3;
        // OEM-defined custom solution for processing media type
        int CUSTOM_OEM_PROCESSING = 1 << 4;
    }

    private final IMediaProcessingService.Stub mInterface = new IMediaProcessingService.Stub() {
        @Override
        public Map<Integer, Integer> getProcessingRequestedPerMediaType() {
            return onProcessingRequestedPerMediaType();
        }

        @Override
        public int getProcessingLimit() {
            return onGetProcessingLimit();
        }

        @Override
        public void processMedia(List<MediaProcessingRequest> mediaProcessingRequests,
                IMediaProcessingCallback callback) {
            OutcomeReceiver<List<MediaProcessingResponse>, ErrorMessage> receiver =
                    new OutcomeReceiver<List<MediaProcessingResponse>, ErrorMessage>() {
                        @Override
                        public void onResult(@NonNull List<MediaProcessingResponse> result) {
                            try {
                                callback.onResult(result);
                            } catch (RemoteException e) {
                                Log.e(TAG, "Failed to send result", e);
                            }
                        }

                        @Override
                        public void onError(@NonNull ErrorMessage error) {
                            try {
                                callback.onError(error);
                            } catch (RemoteException e) {
                                Log.e(TAG, "Failed to send error", e);
                            }
                        }
                    };
            onProcessMedia(mediaProcessingRequests, receiver);
        }

        @Override
        public void getEmbeddingVectorForSearchText(String searchQuery,
                IQueryProcessingCallback callback) {
            OutcomeReceiver<QueryProcessingResponse, ErrorMessage> receiver =
                    new OutcomeReceiver<QueryProcessingResponse, ErrorMessage>() {
                        @Override
                        public void onResult(@NonNull QueryProcessingResponse result) {
                            try {
                                callback.onResult(result);
                            } catch (RemoteException e) {
                                Log.e(TAG, "Failed to send result", e);
                            }
                        }

                        @Override
                        public void onError(@NonNull ErrorMessage error) {
                            try {
                                callback.onError(error);
                            } catch (RemoteException e) {
                                Log.e(TAG, "Failed to send error", e);
                            }
                        }
                    };
            onGetEmbeddingVectorForSearchText(searchQuery, receiver);
        }
    };

    /**
     * {@inheritDoc}
     * <p>
     * Ensure that the binding action is {@link #SERVICE_INTERFACE}
     */
    @Override
    @Nullable
    public final IBinder onBind(@Nullable Intent intent) {
        if (intent == null) {
            Log.w(TAG, "Binding with a null intent");
            return null;
        }

        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface;
        }

        Log.w(TAG, "Binding with incorrect action : " + intent.getAction());
        return null;
    }

    /**
     * Called by the system to determine which processing types are supported
     * for different media types.
     *
     * Returns a map where the key is the {@code MediaStore.MEDIA_TYPE} (e.g.
     * {@code MediaStore.MEDIA_TYPE_IMAGE}, {@code MediaStore.MEDIA_TYPE_VIDEO}, etc) and the
     * value is an integer bitmask. This bitmask must be created by combining one or more
     * {@link ProcessingType} flags using the bitwise OR operator ({@code |}).
     *
     * @return Map for processing requested per media type
     */
    @NonNull
    public abstract Map<Integer, Integer> onProcessingRequestedPerMediaType();

    /**
     * Returns the maximum number of {@link MediaProcessingRequest} instances the service
     * can efficiently handle in a single call to {@link #onProcessMedia}.
     *
     * <p>MediaProvider uses this value to batch requests. The system caches this value
     * for the duration of the service connection.
     *
     * <p>The default implementation returns 10. Override this method if the service
     * should handle a different batch size.
     *
     * @return The maximum number of {@link MediaProcessingRequest} instances.
     */
    public int onGetProcessingLimit() {
        return DEFAULT_PROCESSING_LIMIT;
    }

    /**
     * Called by the system to process a batch of media files. The implementation
     * must invoke the provided {@link OutcomeReceiver} upon completion.
     * <p>
     * OEMs can specify the batch size for media processing requests through the
     * {@code onGetProcessingLimit} method.
     * The default value is set at 10 requests.
     * </p>
     *
     * @param mediaProcessingRequests A list of {@link MediaProcessingRequest} objects to be
     *                                processed.
     * @param outcomeReceiver         The callback for returning the result or an
     *                                {@link ErrorMessage}.
     */
    public abstract void onProcessMedia(
            @NonNull List<MediaProcessingRequest> mediaProcessingRequests,
            @NonNull OutcomeReceiver<List<MediaProcessingResponse>, ErrorMessage> outcomeReceiver);

    /**
     * Asynchronously generates an {@link EmbeddingVector} for the given text to support semantic
     * search.
     *
     * <p>Implementations must process the {@code searchQuery} string and invoke the provided
     * {@code outcomeReceiver} exactly once with the result. Use {@link OutcomeReceiver#onResult}
     * for a successful processing, or {@link OutcomeReceiver#onError} to report an
     * {@link ErrorMessage}.
     *
     * @param searchQuery     The input query string.
     * @param outcomeReceiver The callback for returning the result or an {@link ErrorMessage}.
     */
    public abstract void onGetEmbeddingVectorForSearchText(@NonNull String searchQuery,
            @NonNull OutcomeReceiver<QueryProcessingResponse, ErrorMessage> outcomeReceiver);
}
