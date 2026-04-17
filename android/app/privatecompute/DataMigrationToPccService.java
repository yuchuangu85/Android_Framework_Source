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

package android.app.privatecompute;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.function.Consumer;

/**
 * Base class for a service that handles data migration to the Private Compute Core (PCC)
 * storage of an application.
 *
 * <p>Applications with PCC components that need to migrate data to PCC storage should extend this
 * class and implement {@link #onMigrationRequested()}. The service must require the caller to hold
 * the {@link android.permission.BIND_DATA_MIGRATION_FOR_PRIVATECOMPUTE} permission.
 */
@FlaggedApi(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
public abstract class DataMigrationToPccService extends Service {

    /**
     * The Intent action that must be declared as handled by the service.
     */
    public static final String SERVICE_INTERFACE =
            "android.app.privatecompute.DataMigrationToPccService";

    /**
     * The timeout in milliseconds for the migration service to complete its task.
     * The system will unbind from the service after this timeout.
     */
    public static final long MIGRATION_TIMEOUT_MS = 60 * 1000;

    private final IDataMigrationToPccService mInterface = new IDataMigrationToPccService.Stub() {
        @Override
        public void onMigrationRequested(IMigrationRequestResultSender callback) {
            DataMigrationToPccService.this.onMigrationRequested(result -> {
                try {
                    callback.sendResult(result);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            });
        }
    };

    @Override
    @Nullable
    public final IBinder onBind(@Nullable Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        return null;
    }

    /**
     * Called when the framework triggers a migration request on behalf of a PCC component.
     *
     * @param callback Use this to send a result back to the requesting PCC process
     * and signal to the system whether migration has been accepted.
     */
    public abstract void onMigrationRequested(@NonNull Consumer<MigrationRequestResult> callback);
}
