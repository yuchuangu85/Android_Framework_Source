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

package android.app.supervision;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.Service;
import android.app.supervision.flags.Flags;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Base class for a service that the holders of the {@link
 * android.app.role.RoleManager#ROLE_SYSTEM_SUPERVISION} or {@link
 * android.app.role.RoleManager#ROLE_SUPERVISION} roles must extend.
 *
 * <p>When supervision is enabled, the system searches for this service from each supervision role
 * holder using an intent filter for the {@link #ACTION_SUPERVISION_APP_SERVICE} action. The system
 * attempts to maintain a bound connection to the service, keeping it in the foreground.
 *
 * <p>If a supervision role holder's process crashes, the system will restart it and automatically
 * rebind to the service after a backoff period.
 *
 * <p>The service must be protected with the permission {@link
 * android.Manifest.permission#BIND_SUPERVISION_APP_SERVICE}.
 *
 * @hide
 */
@SystemApi
public class SupervisionAppService extends Service {
    /**
     * Service Action: Action for a service that a supervision role holder must extend.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String ACTION_SUPERVISION_APP_SERVICE =
            "android.app.action.SUPERVISION_APP_SERVICE";

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {};

    private final ISupervisionListener mBinder =
            new ISupervisionListener.Stub() {
                @Override
                public void onSetSupervisionEnabled(int userId, boolean enabled) {
                    mHandler.post(
                            () -> {
                                if (enabled) {
                                    SupervisionAppService.this.onSupervisionEnabled();
                                } else {
                                    SupervisionAppService.this.onSupervisionDisabled();
                                }
                            });
                }

                @Override
                public void onPolicyChanged(Policy policy) {
                    mHandler.post(
                            () -> {
                                SupervisionAppService.this.onPolicyChanged(policy);
                            });
                }
            };

    @Nullable
    @Override
    public final IBinder onBind(@Nullable Intent intent) {
        onServiceBound(intent);
        return mBinder.asBinder();
    }

    /**
     * Called when the service is bound.
     *
     * <p>Used for testing since {@code onBind} is final.
     *
     * @hide
     */
    @TestApi
    @SuppressWarnings("UnflaggedApi")
    public void onServiceBound(@Nullable Intent intent) {}

    /**
     * Called when supervision is enabled.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_SUPERVISION_APP_SERVICE)
    public void onSupervisionEnabled() {}

    /**
     * Called when supervision is disabled.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_SUPERVISION_APP_SERVICE)
    public void onSupervisionDisabled() {}

    /**
     * Called when a policy is changed.
     *
     * @param policy the {@link Policy} that was changed
     * @see Policy
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_SUPERVISION_MANAGER_POLICY_APIS)
    public void onPolicyChanged(@NonNull Policy policy) {}
}
