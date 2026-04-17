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

package android.os.storage;

import android.annotation.BroadcastBehavior;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.os.storage.operations.FileOperationEnqueueResult;
import android.os.storage.operations.FileOperationRequest;
import android.os.storage.operations.FileOperationResult;

/**
 * System service manager for handling privileged, long-running file operations.
 *
 * <p>The {@code FileManager} provides an API for applications to request asynchronous file
 * operations such as moving or copying files. These operations are executed by the system service
 * (`FileService`) on a background thread to ensure application responsiveness and system stability.
 *
 * <h3>Usage Overview</h3>
 *
 * <p>Interacting with the file operation service generally involves three steps:
 *
 * <ol>
 *   <li><b>Enqueueing a Request:</b> Use {@link #enqueueOperation(FileOperationRequest)} to submit
 *       a new operation. This method returns immediately with a {@link FileOperationEnqueueResult},
 *       which contains a unique Request ID (if successful) or an error code (if the system is
 *       busy).
 *   <li><b>Tracking Progress (Optional):</b> Apps can poll the current status of an operation using
 *       {@link #fetchResult(String)} or simply wait for completion.
 *   <li><b>Handling Completion:</b> To receive a notification when the operation finishes (either
 *       successfully or with a failure), call {@link #registerCompletionListener(String)}. The
 *       system will send a broadcast {@link #ACTION_FILE_OPERATION_COMPLETED} to your package when
 *       the operation reaches a terminal state.
 * </ol>
 *
 * <h3>Concurrency and Limits</h3>
 *
 * <p>The system enforces limits on the number of concurrent pending operations to prevent resource
 * exhaustion. If the system is under heavy load, {@link #enqueueOperation(FileOperationRequest)}
 * may return a result with the error code {@link
 * android.os.storage.operations.FileOperationResult#ERROR_BUSY}. Applications should handle this
 * case gracefully, potentially by retrying later.
 *
 * <p>Additionally, the system caps the number of individual file failures reported in a {@link
 * android.os.storage.operations.FileOperationResult} to the value returned from {@link
 * getMaxReportedFailures}. If granular failure reporting is critical for your use case, avoid
 * enqueueing operations that involve more files than the failure reporting limit in a single
 * request.
 */
@SystemService(Context.FILE_SERVICE)
@FlaggedApi(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
public final class FileManager {
    private final Context mContext;
    private final IFileService mService;

    /**
     * Broadcast Action: Sent when a file operation has completed.
     *
     * <p>This broadcast is sent explicitly to the package that initiated the request and registered
     * for notifications via {@link #registerCompletionListener(String)}.
     *
     * <p>The intent will contain the following extras:
     *
     * <ul>
     *   <li>{@link #EXTRA_REQUEST_ID} - The unique ID of the request that completed.
     *   <li>{@link #EXTRA_RESULT} - The final {@link
     *       android.os.storage.operations.FileOperationResult} of the operation, including status
     *       (FINISHED/FAILED), items processed, and any error messages.
     * </ul>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @BroadcastBehavior(explicitOnly = true)
    public static final String ACTION_FILE_OPERATION_COMPLETED =
            "android.os.storage.action.FILE_OPERATION_COMPLETED";

    /**
     * Extra for {@link #ACTION_FILE_OPERATION_COMPLETED}: The Request ID (String).
     *
     * <p>This ID matches the one returned by {@link #enqueueOperation(FileOperationRequest)}.
     */
    public static final String EXTRA_REQUEST_ID = "android.os.storage.extra.REQUEST_ID";

    /**
     * Extra for {@link #ACTION_FILE_OPERATION_COMPLETED}: The result ({@link
     * android.os.storage.operations.FileOperationResult}).
     *
     * <p>This parcelable object contains detailed information about the final state of the
     * operation.
     */
    public static final String EXTRA_RESULT = "android.os.storage.extra.RESULT";

    /**
     * Due to binder transaction limits, the number of reported failures is limited to number
     * returned by this method. It is recommended that operations that require complete failure
     * reporting batch their operations to stay below the max reported failures limit.
     *
     * @return The maximum number of reported failures reported by File operations.
     */
    public static int getMaxReportedFailures() {
        return MAX_REPORTED_FAILURES;
    }

    /**
     * The maximum number of failures reported by file operations. This limit exists to prevent
     * binder transaction sizes from exceeding the maximum allowed size.
     */
    private static final int MAX_REPORTED_FAILURES = 200;

    /** @hide */
    public FileManager(Context context, IFileService service) {
        mContext = context;
        mService = service;
    }

    /**
     * Enqueues a new file operation request.
     *
     * <p>This method validates the request and submits it to the system service. It returns
     * immediately, without waiting for the operation to execute.
     *
     * <p>If the system is too busy to accept the request, the returned result will have an error
     * code of {@link android.os.storage.operations.FileOperationResult#ERROR_BUSY}.
     *
     * <p><b>Note:</b> Granular failure reporting is limited to the first 200 failures encountered.
     * For operations where reporting every individual failure is required, it is recommended to
     * break large tasks into multiple requests of 200 files or fewer.
     *
     * @param request The {@link FileOperationRequest} describing the operation (source, target,
     *     mode).
     * @return A {@link FileOperationEnqueueResult} containing the Request ID (on success) or an
     *     error code (on failure).
     * @throws RuntimeException if the system service is unreachable.
     */
    @NonNull
    public FileOperationEnqueueResult enqueueOperation(@NonNull FileOperationRequest request) {
        try {
            FileOperationEnqueueResult result =
                    mService.enqueueOperation(request, mContext.getOpPackageName());
            if (result.isSuccessful() && request.shouldRegisterCompletionListener()) {
                registerCompletionListener(result.getRequestId());
            }
            return result;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the current result of a file operation.
     *
     * <p>The system maintains result information for all active operations and a limited history of
     * recently completed ones.
     *
     * @param requestId The unique ID of the request to query, as returned by {@link
     *     #enqueueOperation}.
     * @return A {@link FileOperationResult} object containing the current status, or {@code null}
     *     if the request ID is unknown or has been culled from history.
     * @throws RuntimeException if the system service is unreachable.
     */
    @Nullable
    public FileOperationResult fetchResult(@NonNull String requestId) {
        try {
            return mService.fetchResult(requestId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a listener for the completion of a specific file operation.
     *
     * <p>When the operation identified by {@code requestId} finishes (successfully or with an
     * error), the system will send a {@link #ACTION_FILE_OPERATION_COMPLETED} broadcast to the
     * calling application.
     *
     * <p>This method should be called immediately after a successful {@link #enqueueOperation} if
     * completion notification is desired.
     *
     * @param requestId The unique ID of the request to monitor, as returned by {@link
     *     #enqueueOperation}.
     * @throws RuntimeException if the system service is unreachable.
     */
    public void registerCompletionListener(@NonNull String requestId) {
        try {
            mService.registerCompletionListener(requestId, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters a previously registered completion listener for a specific file operation.
     *
     * @param requestId The unique ID of the request to stop monitoring.
     * @throws RuntimeException if the system service is unreachable.
     */
    public void unregisterCompletionListener(@NonNull String requestId) {
        try {
            mService.unregisterCompletionListener(requestId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
