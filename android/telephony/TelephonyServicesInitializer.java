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
package android.telephony;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.SystemServiceRegistry;
import android.content.Context;
import android.util.Log;

import com.android.internal.telephony.PhoneNumberManagerService;

/**
 * Initializes framework telephony services.
 *
 * <p>This class provides methods to initialize and register telephony-related services.
 * It is primarily used during the system startup process.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
@FlaggedApi(com.android.telephony.flags.Flags.FLAG_PHONE_NUMBER_PARSING_API)
public final class TelephonyServicesInitializer {
    private static PhoneNumberManagerService sPhoneNumberManagerService;
    private static final String TAG = "TelephonyServicesInitializer";

    /** Prevent instantiation. */
    private TelephonyServicesInitializer() {}

    /**
     * Initializes telephony services. This method should be called during the phone process
     * startup.
     */
    public static void initialize() {
        try {
            if (sPhoneNumberManagerService == null) {
                sPhoneNumberManagerService = new PhoneNumberManagerService();
            } else {
                Log.w(TAG, "PhoneNumberManagerService is already initialized.");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error while initializing PhoneNumberManagerService: " + t);
        }
    }

    /**
     * Initializes telephony services with the given context. This method should be called
     * during the phone process startup.
     * @param context The context.
     */
    @FlaggedApi(com.android.telephony.flags.Flags.FLAG_SUPPORT_GET_PHONE_NUMBER_TS43)
    public static void initialize(@NonNull Context context) {
        try {
            if (sPhoneNumberManagerService == null) {
                sPhoneNumberManagerService = new PhoneNumberManagerService(context);
            } else {
                Log.w(TAG, "PhoneNumberManagerService is already initialized.");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error while initializing PhoneNumberManagerService: " + t);
        }
    }

    /**
     * Called by {@link SystemServiceRegistry}'s static initializer and registers
     * {@link PhoneNumberManager} to {@link Context}, so that {@link Context#getSystemService} can
     * return it.
     *
     * <p>If this is called from other places, it throws a {@link IllegalStateException}.
     *
     */
    public static void registerServiceWrappers() {
        SystemServiceRegistry.registerContextAwareService(
                Context.TELEPHONY_PHONE_NUMBER_SERVICE,
                PhoneNumberManager.class, PhoneNumberManager::new);
    }
}
