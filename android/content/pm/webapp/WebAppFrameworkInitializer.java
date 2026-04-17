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

package android.content.pm.webapp;

import android.annotation.FlaggedApi;
import android.annotation.SystemApi;
import android.app.SystemServiceRegistry;
import android.content.Context;

import com.android.webapp.flags.Flags;

/**
 * Class for performing registration for WebApp service.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_WEB_APP_SERVICE_V2)
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public class WebAppFrameworkInitializer {
    private WebAppFrameworkInitializer() {}

    /**
     * Called by the static initializer in the {@link SystemServiceRegistry}, and registers {@link
     * WebAppManager} to the {@link Context}, so that it's accessible from {@link
     * Context#getSystemService(String)}.
     */
    public static void registerServiceWrappers() {
        SystemServiceRegistry.registerContextAwareService(
                Context.WEB_APP_SERVICE, WebAppManager.class, WebAppManager::new);
    }
}
