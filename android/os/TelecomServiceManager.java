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

package android.os;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import android.annotation.FlaggedApi;
import android.annotation.SystemApi;
import android.content.Context;
import android.telecom.flags.Flags;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Provides a way to register and obtain the system service binder objects managed by the telecom
 * APEX service.
 *
 * <p>Only the telecom APEX module will be able to access an instance of this class.
 *
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
@FlaggedApi(Flags.FLAG_TELECOM_MAINLINE_API)
public class TelecomServiceManager {
    private TelecomServiceManager() {}

    /**
     * The class used to manage the registration of TELECOM_SERVICE to the system.
     */
    public static final class ServiceRegisterer {
        private final String mServiceName;

        private ServiceRegisterer(@NonNull String serviceName) {
            mServiceName = serviceName;
        }

        /**
         * Gets the IBinder registered to TELECOM_SERVICE.
         * @return the IBinder registered to TELECOM_SERVICE.
         */
        public @Nullable IBinder get() {
            return ServiceManager.getService(mServiceName);
        }
    }

    /**
     * Gets the ServiceRegisterer for TELECOM_SERVICE.
     * @return the ServiceRegisterer for TELECOM_SERVICE.
     */
    @NonNull
    public static ServiceRegisterer getTelecomServiceRegisterer() {
        return new ServiceRegisterer(Context.TELECOM_SERVICE);
    }
}
