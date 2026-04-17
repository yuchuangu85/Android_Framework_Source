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

package android.os.flagging;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.provider.flags.Flags;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Provides write access to aconfigd-backed flag storage.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_NEW_STORAGE_PUBLIC_API)
@SystemService(FlagManager.FLAG_SERVICE_NAME)
public final class FlagManager {
    /**
     * Create a new FlagManager.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_NEW_STORAGE_PUBLIC_API)
    public FlagManager(@NonNull Context unusedContext) {}

    /**
     * Use with {@link #getSystemService(String)} to retrieve a {@link
     * android.os.flagging.FlagManager} for pushing flag values to aconfig.
     *
     * @see Context#getSystemService(String)
     * @hide
     */
    @FlaggedApi(Flags.FLAG_NEW_STORAGE_PUBLIC_API)
    public static final String FLAG_SERVICE_NAME = "flag";

    /**
     * Stage flag values, to apply when the device boots into system build {@code buildFingerprint}.
     *
     * <p>The mapping persists across reboots, until the device finally boots into the system {@code
     * buildFingerprint}, when the mapping is cleared.
     *
     * <p>Only one {@code buildFingerprint} and map of flags can be stored at a time. Subsequent
     * calls will overwrite the existing mapping.
     *
     * <p>If overrides are staged for the next reboot, from {@link
     * WriteAconfig#setOverridesOnReboot}, and overrides are also staged for a {@code
     * buildFingerprint}, and the device boots into {@code buildFingerprint}, the {@code
     * buildFingerprint}-associated overrides will take precedence over the reboot-associated
     * overrides.
     *
     * @param buildFingerprint a system build fingerprint identifier.
     * @param flags map from flag qualified name to new value.
     * @throws AconfigStorageWriteException if the write fails.
     * @see android.os.Build#FINGERPRINT
     */
    @FlaggedApi(Flags.FLAG_NEW_STORAGE_PUBLIC_API)
    public void setBooleanOverridesOnSystemBuildFingerprint(
            @NonNull String buildFingerprint, @NonNull Map<String, Boolean> flags) {
        try {
            (new AconfigdProtoStreamer()).sendOtaFlagOverrideRequests(flags, buildFingerprint);
        } catch (IOException e) {
            throw new AconfigStorageWriteException(
                    "failed to set boolean overrides on system build fingerprint", e);
        }
    }

    /**
     * Stage flag values, to apply when the device reboots.
     *
     * <p>These flags will be cleared on the next reboot, regardless of whether they take effect.
     * See {@link setBooleanOverridesOnSystemBuildFingerprint} for a thorough description of how the
     * set of flags to take effect is determined on the next boot.
     *
     * @param flags map from flag qualified name to new value.
     * @throws AconfigStorageWriteException if the write fails.
     */
    @FlaggedApi(Flags.FLAG_NEW_STORAGE_PUBLIC_API)
    public void setBooleanOverridesOnReboot(@NonNull Map<String, Boolean> flags) {
        try {
            (new AconfigdProtoStreamer())
                    .sendFlagOverrideRequests(
                            flags,
                            android.internal.configinfra.aconfigd.x.Aconfigd.StorageRequestMessage
                                    .SERVER_ON_REBOOT);
        } catch (IOException e) {
            throw new AconfigStorageWriteException("failed to set boolean overrides on reboot", e);
        }
    }

    /**
     * Set local overrides, to apply on device reboot.
     *
     * <p>Local overrides take precedence over normal overrides. They must be cleared for normal
     * overrides to take effect again.
     *
     * @param flags map from flag qualified name to new value.
     * @see clearBooleanLocalOverridesOnReboot
     * @see clearBooleanLocalOverridesImmediately
     */
    @FlaggedApi(Flags.FLAG_NEW_STORAGE_PUBLIC_API)
    public void setBooleanLocalOverridesOnReboot(@NonNull Map<String, Boolean> flags) {
        try {
            (new AconfigdProtoStreamer())
                    .sendFlagOverrideRequests(
                            flags,
                            android.internal.configinfra.aconfigd.x.Aconfigd.StorageRequestMessage
                                    .LOCAL_ON_REBOOT);
        } catch (IOException e) {
            throw new AconfigStorageWriteException("failed to set boolean overrides on reboot", e);
        }
    }

    /**
     * Set local overrides, to apply immediately.
     *
     * <p>Local overrides take precedence over normal overrides. They must be cleared for normal
     * overrides to take effect again.
     *
     * <p>Note that processes cache flag values, so a process restart or reboot is still required to
     * get the latest flag value.
     *
     * @param flags map from flag qualified name to new value.
     * @see clearBooleanLocalOverridesOnReboot
     * @see clearBooleanLocalOverridesImmediately
     */
    @FlaggedApi(Flags.FLAG_NEW_STORAGE_PUBLIC_API)
    public void setBooleanLocalOverridesImmediately(@NonNull Map<String, Boolean> flags) {
        try {
            (new AconfigdProtoStreamer())
                    .sendFlagOverrideRequests(
                            flags,
                            android.internal.configinfra.aconfigd.x.Aconfigd.StorageRequestMessage
                                    .LOCAL_IMMEDIATE);
        } catch (IOException e) {
            throw new AconfigStorageWriteException("failed to set boolean overrides on reboot", e);
        }
    }

    /**
     * Clear local overrides, to take effect on reboot.
     *
     * <p>If {@code flags} is {@code null}, clear all local overrides.
     *
     * @param flags map from flag qualified name to new value.
     * @see setBooleanLocalOverridesOnReboot
     * @see setBooleanLocalOverridesImmediately
     */
    @FlaggedApi(Flags.FLAG_NEW_STORAGE_PUBLIC_API)
    public void clearBooleanLocalOverridesOnReboot(@Nullable Set<String> flags) {
        try {
            (new AconfigdProtoStreamer())
                    .sendClearFlagOverrideRequests(
                            flags,
                            android.internal.configinfra.aconfigd.x.Aconfigd.StorageRequestMessage
                                    .REMOVE_LOCAL_ON_REBOOT);
        } catch (IOException e) {
            throw new AconfigStorageWriteException("failed to set boolean overrides on reboot", e);
        }
    }

    /**
     * Clear local overrides, to take effect immediately.
     *
     * <p>Note that processes cache flag values, so a process restart or reboot is still required to
     * get the latest flag value.
     *
     * <p>If {@code flags} is {@code null}, clear all local overrides.
     *
     * @param flags map from flag qualified name to new value.
     * @see setBooleanLocalOverridesOnReboot
     * @see setBooleanLocalOverridesImmediately
     */
    @FlaggedApi(Flags.FLAG_NEW_STORAGE_PUBLIC_API)
    public void clearBooleanLocalOverridesImmediately(@Nullable Set<String> flags) {
        try {
            (new AconfigdProtoStreamer())
                    .sendClearFlagOverrideRequests(
                            flags,
                            android.internal.configinfra.aconfigd.x.Aconfigd.StorageRequestMessage
                                    .REMOVE_LOCAL_IMMEDIATE);
        } catch (IOException e) {
            throw new AconfigStorageWriteException("failed to set boolean overrides on reboot", e);
        }
    }
}
