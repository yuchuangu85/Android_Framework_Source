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

package android.app.privatecompute;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Exception thrown when a migration request fails.
 */
@FlaggedApi(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
public final class MigrationException extends Exception {
    /** The migration request failed due to an invocation error. */
    public static final int ERROR_INVOCATION_FAILED = 1;

    /** The migration request failed due to a timeout. */
    public static final int ERROR_TIMEOUT = 2;

    /**
     * Error codes for {@link #getErrorCode()}.
     * @hide
     */
    @IntDef(prefix = { "ERROR_" }, value = {
            ERROR_INVOCATION_FAILED,
            ERROR_TIMEOUT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MigrationError {}

    private final int mErrorCode;

    public MigrationException(@MigrationError int errorCode, @Nullable String message) {
        super(message);
        mErrorCode = errorCode;
    }

    /**
     * Returns the error code associated with this exception.
     */
    @MigrationError
    public int getErrorCode() {
        return mErrorCode;
    }
}
