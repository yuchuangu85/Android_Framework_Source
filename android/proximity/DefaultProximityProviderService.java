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

package android.proximity;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.ICancellationSignal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.R;

/**
 * Default implementation for {@link IProximityProviderService}.
 *
 * The implementation can be changed by providing a different service.
 *
 * <service android:name="{SERVICE_NAME}"
 *   android:exported="true">
 *   <intent-filter>
 *      <action android:name="android.proximity.ProximityProviderService" />
 *   </intent-filter>
 * </service>
 *
 * An overlay needs to overwrite the following strings in
 * frameworks/base/core/res/res/values/config.xml:
 *{@link R.string.proximity_provider_service_package_name} should represent the package name of
 * where the service is declared.
 * {@link R.string.proximity_provider_service_class_name} should represent the class name.
 *
 * @hide
 */
public class DefaultProximityProviderService extends Service {
    private static final IProximityProviderService.Stub sProximityProviderServiceImpl =
            new IProximityProviderService.Stub() {
                @Override
                public synchronized ICancellationSignal anyWatchNearby(RangingParams params,
                        IProximityResultCallback callback) {
                    return null;
                }

                @Override
                public boolean isProximityCheckingSupported() {
                    return false;
                }

                @Override
                public int isProximityCheckingAvailable() {
                    return ProximityResultCode.PRIMARY_DEVICE_RANGING_NOT_SUPPORTED;
                }
            };

    @Nullable
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        return sProximityProviderServiceImpl;
    }
}
