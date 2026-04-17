/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.provider;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.flagging.AconfigdProtoStreamer;
import android.provider.flags.Flags;
import android.util.AndroidRuntimeException;
import android.util.Log;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;

/** @hide */
@SystemApi
@FlaggedApi(Flags.FLAG_STAGE_FLAGS_FOR_BUILD)
public final class StageOtaFlags {
    private static String LOG_TAG = "StageOtaFlags";

    /**
     * Aconfig storage is disabled and unavailable for writes.
     * @hide
     */
    @SystemApi public static final int STATUS_STORAGE_NOT_ENABLED = -1;

    /**
     * Stage request was successful.
     * @hide
     */
    @SystemApi public static final int STATUS_STAGE_SUCCESS = 0;

    /** @hide */
    @IntDef(
            prefix = {"STATUS_"},
            value = {
                STATUS_STORAGE_NOT_ENABLED,
                STATUS_STAGE_SUCCESS,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StageStatus {}

    private StageOtaFlags() {}

    /**
     * Stage aconfig flags to be applied when booting into {@code buildId}.
     *
     * <p>Only a single {@code buildId} and its corresponding flags are stored at once. Every
     * invocation of this method will overwrite whatever mapping was previously stored.
     *
     * <p>It is an implementation error to call this if the storage is not initialized and ready to
     * receive writes. Callers must ensure that it is available before invoking.
     *
     * <p>TODO(b/361783454): create an isStorageAvailable API and mention it in this docstring.
     *
     * @param flags a map from {@code <packagename>.<flagname>} to flag values
     * @param buildId when the device boots into buildId, it will apply {@code flags}
     * @throws AndroidRuntimeException if communication with aconfigd fails
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_STAGE_FLAGS_FOR_BUILD)
    @StageStatus
    public static int stageBooleanAconfigFlagsForBuild(
            @NonNull Map<String, Boolean> flags, @NonNull String buildId) {
        int flagCount = flags.size();
        Log.d(LOG_TAG, "stageFlagsForBuild invoked for " + flagCount + " flags");
        try {
            (new AconfigdProtoStreamer()).sendOtaFlagOverrideRequests(flags, buildId);
        } catch (IOException e) {
            throw new AndroidRuntimeException(e);
        }
        return STATUS_STAGE_SUCCESS;
    }
}
