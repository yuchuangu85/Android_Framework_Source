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

package android.app;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.util.AndroidRuntimeException;

/**
 * This exception is thrown when a call to {@link android.content.Context#startActivity} or one of
 * its variants fails because it is not possible to satisfy requirements specified in the {@link
 * android.app.ActivityOptions} passed in the {@link android.content.Context#startActivity} call.
 */
@FlaggedApi(com.android.window.flags.Flags.FLAG_ENABLE_REQUIRE_MOVABLE_TASK_API)
public class InfeasibleActivityOptionsException extends AndroidRuntimeException {

    /** @hide */
    public InfeasibleActivityOptionsException(@NonNull String name) {
        super(name);
    }
}
