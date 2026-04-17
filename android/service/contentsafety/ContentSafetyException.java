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

package android.service.contentsafety;


import static android.app.contentsafety.flags.Flags.FLAG_ENABLE_CONTENTSAFETY;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Exception type to wrap failure error codes thrown by remote services for the remote calls
 * {@link ContentSafetySandboxedService#onLoadFeature}  and
 * {@link ContentSafetyService#onGetFeature}
 *
 * @hide
 */
@SystemApi
@SystemService(Context.CONTENT_SAFETY_SERVICE)
@FlaggedApi(FLAG_ENABLE_CONTENTSAFETY)
public final class ContentSafetyException  extends Exception {

    /**
     * Sent when loadFeature remote call failed due to internal error.
     * @hide
     */
    public static final int CONTENT_SAFETY_LOAD_FEATURE_ERROR = 1;
    /**
     * Sent when getFeature remote call failed due to internal error.
     * @hide
     */
    public static final int CONTENT_SAFETY_GET_FEATURE_ERROR = 2;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            value = {
                    CONTENT_SAFETY_LOAD_FEATURE_ERROR,
                    CONTENT_SAFETY_GET_FEATURE_ERROR
            })
    public @interface ContentSafetyError {}

    private final int mErrorCode;

    /** Returns the error code of the exception. */
    public @ContentSafetyError int getErrorCode() {
        return mErrorCode;
    }

    /**
     * Creates a new ContentSafetyException with the specified error code.
     *
     * @param errorCode The error code.
     */
    public ContentSafetyException(@ContentSafetyError int errorCode) {
        this.mErrorCode = errorCode;
    }

}
