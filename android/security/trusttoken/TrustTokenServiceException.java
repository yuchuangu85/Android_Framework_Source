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

package android.security.trusttoken;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.security.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents an error encountered during a trust token operation.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_TALISMAN_SERVICE_API)
@SystemApi
public final class TrustTokenServiceException extends Exception {
    /**
     * Error codes returned by the remote Trust Anchor server.
     *
     * @hide
     */
    @IntDef(
            prefix = {"ERROR_"},
            value = {ERROR_UNKNOWN, ERROR_INVALID_ARGUMENT, ERROR_INTERNAL, ERROR_UNAVAILABLE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ErrorCode {}

    /** Unknown error. */
    public static final int ERROR_UNKNOWN = 0;

    /** The request parameters are invalid. */
    public static final int ERROR_INVALID_ARGUMENT = 1;

    /**
     * The request failed due to an internal error.
     * This is usually caused by a dependency failure or a bug. Callers might try to restart the
     * service or reboot the device, if the error persists, please report the issue to the service
     * provider.
     */
    public static final int ERROR_INTERNAL = 2;

    /**
     * The request failed due to an unavailable error (such as network unavailable).
     * This is a retryable error, callers should retry the request with a backoff time.
     */
    public static final int ERROR_UNAVAILABLE = 3;

    /** The request was cancelled by the caller. */
    public static final int ERROR_CANCELLED = 4;

    @ErrorCode
    private final int mErrorCode;

    /**
     * Constructs a {@link TrustTokenServiceException}.
     *
     * @param errorCode The error code.
     * @param message The error message.
     */
    public TrustTokenServiceException(@ErrorCode int errorCode, @NonNull String message) {
        super(message);
        mErrorCode = errorCode;
    }

    /** Returns the error code. */
    @ErrorCode
    public int getErrorCode() {
        return mErrorCode;
    }
}
