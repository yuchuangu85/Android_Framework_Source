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

package android.app.lskfreset;

import static android.app.lskfreset.flags.Flags.FLAG_ENABLE_LSKF_RESET_MANAGER;

import android.annotation.FlaggedApi;
import android.annotation.SystemService;
import android.content.Context;

/**
 * Provides LSKF reset related functionalities.
 *
 * @hide
 */
@FlaggedApi(FLAG_ENABLE_LSKF_RESET_MANAGER)
@SystemService(Context.LSKF_RESET_SERVICE)
public final class LskfResetManager {
    @SuppressWarnings("unused")
    private final ILskfResetManager mService;
    @SuppressWarnings("unused")
    private final Context mContext;

    /**
     * TODO(b/415960504): add comments when implementing this class
     *
     * @hide
     */
    public LskfResetManager(ILskfResetManager mService, Context context) {
        this.mService = mService;
        this.mContext = context;
    }
}
