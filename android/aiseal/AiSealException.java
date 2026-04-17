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

package android.aiseal;

import android.annotation.FlaggedApi;
import android.annotation.Nullable;
import android.annotation.SystemApi;

/**
 * An exception thrown by {@link android.aiseal.AiSealManager}.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_AISEAL_HOST_APIS)
@SystemApi
public class AiSealException extends Exception {
    AiSealException(@Nullable String message) {
        super(message);
    }

    AiSealException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    AiSealException(@Nullable Throwable cause) {
        super(cause);
    }
}
