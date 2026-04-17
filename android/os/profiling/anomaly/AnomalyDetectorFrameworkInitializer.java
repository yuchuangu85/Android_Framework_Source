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
package android.os.profiling.anomaly;

import android.annotation.FlaggedApi;
import android.annotation.SystemApi;
import android.annotation.SystemApi.Client;
import android.app.SystemServiceRegistry;
import android.content.Context;
import android.os.profiling.anomaly.flags.Flags;

/**
 * Class for performing registration for anomaly detector service.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ANOMALY_DETECTOR_CORE)
@SystemApi(client = Client.MODULE_LIBRARIES)
public final class AnomalyDetectorFrameworkInitializer {
    private AnomalyDetectorFrameworkInitializer() {}

    /**
     * Called by {@link SystemServiceRegistry}'s static initializer and registers the Anomaly
     * Detector service to {@link Context}, so that {@link Context#getSystemService} can return it.
     *
     * @throws IllegalStateException if this is called from anywhere beside {@link
     *     SystemServiceRegistry}
     */
    public static void registerServiceWrappers() {
        SystemServiceRegistry.registerContextAwareService(
                Context.ANOMALY_DETECTOR_SERVICE,
                AnomalyDetectorManager.class,
                (context, service) ->
                        new AnomalyDetectorManager(
                                context, IAnomalyDetectorService.Stub.asInterface(service)));
    }
}
