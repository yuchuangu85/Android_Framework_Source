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

package android.view;

import android.view.inputmethod.ImeTracker;

import com.android.internal.protolog.ProtoLogGroup;

/**
 * Defines logging groups for ProtoLog.
 *
 * This file is used by the ProtoLogTool to generate optimized logging code. All of its dependencies
 * must be included in services.core.wm.protologgroups build target.
 *
 * @hide
 */
public final class ViewProtoLogGroups {
    static final ProtoLogGroup IME_INSETS_CONTROLLER = new ProtoLogGroup(
            "IME_INSETS_CONTROLLER", "InsetsController", true /* logToLogcat */);
    static final ProtoLogGroup INSETS_CONTROLLER_DEBUG = new ProtoLogGroup(
            "INSETS_CONTROLLER_DEBUG", "InsetsController", false /* logToLogcat */);
    static final ProtoLogGroup INSETS_ANIMATION_CONTROLLER = new ProtoLogGroup(
            "INSETS_ANIMATION_CONTROLLER", "InsetsAnimationCtrlImpl", false /* logToLogcat */);
    public static final ProtoLogGroup IME_TRACKER = new ProtoLogGroup(
            "IME_TRACKER", ImeTracker.TAG, true /* logToLogcat */);
    public static final ProtoLogGroup INPUT_METHOD_MANAGER_DEBUG = new ProtoLogGroup(
            "INPUT_METHOD_MANAGER", "InputMethodManager", false /* logToLogcat */);
    public static final ProtoLogGroup INPUT_METHOD_MANAGER_WITH_LOGCAT = new ProtoLogGroup(
            "INPUT_METHOD_MANAGER_LOGCAT", "InputMethodManager", true /* logToLogcat */);
    public static final ProtoLogGroup SURFACE_CONTROL_REGISTRY = new ProtoLogGroup(
            "SURFACE_CONTROL_REGISTRY", "SurfaceControlRegistry", true /* logToLogcat */);

    static final ProtoLogGroup[] ALL_GROUPS = {
            IME_INSETS_CONTROLLER,
            INSETS_CONTROLLER_DEBUG,
            INSETS_ANIMATION_CONTROLLER,
            IME_TRACKER,
            INPUT_METHOD_MANAGER_WITH_LOGCAT,
            INPUT_METHOD_MANAGER_DEBUG,
            SURFACE_CONTROL_REGISTRY,
    };
}

