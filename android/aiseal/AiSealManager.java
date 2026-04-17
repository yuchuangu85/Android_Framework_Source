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
import android.annotation.NonNull;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.WorkerThread;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.system.virtualmachine.VirtualMachine;
import android.util.Log;

/**
 * Manages AiSeal service that hosts AI applications processing personal data inside protected
 * virtual machine.
 *
 * <p>AiSeal is a system service that runs a single protected virtual machine ({@link
 * android.system.virtualmachine.VirtualMachine}) with multiple applications:
 *
 * <ul>
 *   <li>AppSearch database for personal data that should not be accessible from the host OS.
 *   <li>On-device AI inference service to be able to process personal data with large AI models.
 *   <li>AI agents that resolves user requests using personal data.
 * </ul>
 *
 * <p>AiSealManager provides host-side APIs to interact with the VM and services running inside it.
 * These APIs are only available if the AiSeal system feature is present and enabled.
 *
 * <p>The AiSeal is configured using the following system properties:
 *
 * <ul>
 *   <li>{@code service.aiseal.enable} - enables or disables AiSeal service.
 *   <li>{@code service.aiseal.tenant_config_package} - name of the package with AiSeal tenant
 *       configuration.
 *   <li>{@code service.aiseal.tenant_config_path} - name of the file in the tenant configuration
 *       package that contains payload configuration, see {@link
 *       android.system.virtualmachine.VirtualMachineConfig.Builder#setPayloadConfigPath(String)}.
 *   <li>{@code service.aiseal.aiseal_config_path} - name of the file in the tenant configuration
 *       package that contains AiSeal specific configuration, see {@code
 *       frameworks/native/services/aisealhostservice/src/config.rs} for the format.
 * </ul>
 *
 * See {@code AiSealTestCases} for an example of how to configure and use AiSeal.
 *
 * <p><b>Note: </b> AiSealManager is accessible only by the primary user, because the AiSeal doesn't
 * support Android profile separation. Tenants have to route secondary users requests through the
 * primary user (with {@link android.Manifest.permission#INTERACT_ACROSS_USERS} permission) and
 * manage profile separation inside the AiSeal VM themselves.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_AISEAL_HOST_APIS)
@SystemApi
@RequiresFeature(PackageManager.FEATURE_AISEAL)
@SystemService(Context.AISEAL_HOST_SERVICE)
public final class AiSealManager {
    private static final String AISEAL_HOST_SERVICE_NAME = Context.AISEAL_HOST_SERVICE;
    private static final String SYSTEM_PROPERTY_AISEAL_ENABLED = "service.aiseal.enable";

    private static final String TAG = "AiSealManager";

    private final Context mContext;

    /** @hide */
    public AiSealManager(Context context) {
        mContext = context;
    }

    /**
     * Connect to a service hosted by the payload running inside the AiSeal VM.
     *
     * <p>The service should be declared in the AiSeal configuration file, see {@code
     * frameworks/native/services/aisealhostservice/src/config.rs} for the format.
     *
     * <p>Communication between the host and the payload is done through the RPC binder over vsock
     * connection. Tenant should use {@code AVmPayload_runVsockRpcServer} to export their service on
     * the vsock port defined in the AiSeal configuration file and then host application owning the
     * tenant can call {@link #connectService(String)} method to connect to the service using its
     * name.
     *
     * <p><b>Note: </b> Only the application running in the AiSeal VM can connect to the service and
     * they can only connect to services that they own.
     *
     * <p><b>Warning: </b> This method may block and should not be called on the main thread.
     *
     * @param name The name of the service to connect to. This name should be declared in the AiSeal
     *     configuration file and belong to the calling application.
     * @return The binder of the connected service.
     * @throws AiSealException if the AiSeal is not running or the connection failed.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_AISEAL_HOST_APIS)
    @WorkerThread
    @SystemApi
    @NonNull
    @RequiresPermission("android.permission.MANAGE_AISEAL_VIRTUAL_MACHINE")
    public IBinder connectService(@NonNull String name) throws AiSealException {
        if (!isEnabled()) {
            throw new AiSealException("AiSeal is not enabled on this device");
        }
        try {
            return VirtualMachine.binderFromPreconnectedClient(
                    () -> {
                        try {
                            IAiSealHostService hostService = connectToHostService();
                            Log.i(TAG, "Connecting to a service " + name);
                            return hostService.connectService(name);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "binderFromPreconnectedClient failed", e);
            throw new AiSealException("Failed to connect to sealed service", e);
        }
    }

    private boolean isEnabled() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AISEAL)
                && SystemProperties.getBoolean(SYSTEM_PROPERTY_AISEAL_ENABLED, /* def= */ false);
    }

    @NonNull
    private static IAiSealHostService connectToHostService() {
        Log.i(TAG, "Connecting to a host service");
        IBinder binder = ServiceManager.waitForService(AISEAL_HOST_SERVICE_NAME);
        IAiSealHostService service = IAiSealHostService.Stub.asInterface(binder);
        if (service == null) {
            Log.e(TAG, "Failed to connect to " + AISEAL_HOST_SERVICE_NAME);
            throw new RuntimeException("Failed to connect to " + AISEAL_HOST_SERVICE_NAME);
        }
        return service;
    }
}
