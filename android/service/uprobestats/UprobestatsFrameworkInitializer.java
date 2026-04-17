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

package android.service.uprobestats;

import android.annotation.FlaggedApi;
import android.annotation.SystemApi;
import android.app.SystemServiceRegistry;
import android.content.Context;
import android.uprobestats.mainline.flags.Flags;

import com.android.uprobestats.IUprobeStatsBridgeService;

/**
 * Class for performing registration for uprobestats service.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_DYNAMIC_INSTRUMENTATION_EVENT_SERVICE)
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public class UprobestatsFrameworkInitializer {
    private static final String TAG = UprobestatsFrameworkInitializer.class.getName();

    private UprobestatsFrameworkInitializer() {}

    /**
     * Called by {@link SystemServiceRegistry}'s static initializer and registers the uprobestats
     * service to {@link Context}, so that {@link Context#getSystemService} can return it.
     *
     * @throws IllegalStateException if this is called from anywhere beside {@link
     *     SystemServiceRegistry}
     */
    public static void registerServiceWrappers() {
        SystemServiceRegistry.registerContextAwareService(
                Context.UPROBESTATS_BRIDGE_SERVICE,
                DynamicInstrumentationEventSender.class,
                (context, service) ->
                        new DynamicInstrumentationEventSender(
                                context, IUprobeStatsBridgeService.Stub.asInterface(service)));
    }
}
