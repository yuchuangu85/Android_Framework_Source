/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.protolog.common;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.List;

/**
 * Interface for ProtoLog implementations.
 */
public interface IProtoLog {

    /**
     * Log a ProtoLog message
     * @param logLevel Log level of the proto message.
     * @param group The group this message belongs to.
     * @param messageHash The hash of the message.
     * @param paramsMask The parameters mask of the message.
     * @param args The arguments of the message.
     */
    void log(@NonNull LogLevel logLevel, @NonNull IProtoLogGroup group, long messageHash,
            long paramsMask, @Nullable Object[] args);

    /**
     * Log a ProtoLog message
     * @param logLevel Log level of the proto message.
     * @param group The group this message belongs to.
     * @param messageString The message string.
     * @param args The arguments of the message.
     */
    void log(@NonNull LogLevel logLevel, @NonNull IProtoLogGroup group,
            @NonNull String messageString, @NonNull Object... args);

    /**
     * Check if ProtoLog is tracing.
     * @return true iff a ProtoLog tracing session is active.
     */
    boolean isProtoEnabled();

    /**
     * Start logging log groups to logcat
     * @param groups Groups to start text logging for
     * @return status code
     */
    int startLoggingToLogcat(@NonNull String[] groups, @NonNull ILogger logger);

    /**
     * Stop logging log groups to logcat
     * @param groups Groups to start text logging for
     * @return status code
     */
    int stopLoggingToLogcat(@NonNull String[] groups, @NonNull ILogger logger);

    /**
     * Should return true iff logging is enabled to ProtoLog or to Logcat for this group and level.
     * @param group ProtoLog group to check for.
     * @param level ProtoLog level to check for.
     * @return If we need to log this group and level to either ProtoLog or Logcat.
     */
    boolean isEnabled(@NonNull IProtoLogGroup group, @NonNull LogLevel level);

    /**
     * @return an immutable list of the registered ProtoLog groups in this ProtoLog instance.
     */
    @NonNull
    List<IProtoLogGroup> getRegisteredGroups();

    /**
     * Register ProtoLog groups with this ProtoLog instance.
     * Only groups registered with this ProtoLog instance should be logged through this instance.
     * @param groups The groups to register.
     */
    void registerGroups(@NonNull IProtoLogGroup... groups);
}
