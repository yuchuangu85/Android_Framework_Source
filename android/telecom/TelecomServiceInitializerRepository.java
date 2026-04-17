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

package android.telecom;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.IBinder;
import android.telecom.flags.Flags;
/**
 * Singleton Interface used to allow the TelecomShim app the ability
 * to load the telecom implementation residing in
 * systemserverclasspath.
 *
 * We need it because the TelecomShim apk runs on its own classpath, which only contains
 * BOOTCLASSPATH + the appp code, and can't directly see any classes in SYSTEMSERVERCLASSPATH.
 *
 * We put this class in BOOTCLASSPATH to allow the TelecomShim apk to indirectly
 * access code on SYSTEMSERVERCLASSPATH via the interface.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
@FlaggedApi(Flags.FLAG_TELECOM_MAINLINE_API)
public final class TelecomServiceInitializerRepository {

    private TelecomServiceInitializerRepository() {}

    /**
     * Initializer for Telecom's implementation
     */
    public interface Initializer {
        /**
         * Initialize telecom's implementation.
         *
         * @param context The Context of the Telecom Shim app
         * @return The IBinder containing Telecom's impl of
         * TelecomManager.
         */
        @Nullable IBinder initialize(@NonNull Context context);
    }

    private static Initializer sINITIALIZER;

    /**
     * Set the Initializer used to initialize Telecom's code.
     *
     * @param initializer The initializer that is part of systemserverclasspath
     * and contains telecom's impl.
     */
    public static void setInitializer(@Nullable Initializer initializer) {
        sINITIALIZER = initializer;
    }

    /**
     * Retrieve the Initializer implementing Telecom.
     *
     * @return the Initializer used to initialize Telecom's code.
     */
    public static @Nullable Initializer getInitializer() {
        return sINITIALIZER;
    }
}
