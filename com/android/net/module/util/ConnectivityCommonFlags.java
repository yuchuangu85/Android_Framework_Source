/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.net.module.util;

/**
 * Collection of flag constants shared across modules.
 * @hide
 */
public class ConnectivityCommonFlags {

    /**
     * A feature flag to use the network{Add,Remove}RouteParcel netd IPCs instead of the legacy
     * network{Add,Remote}Route IPCs. This flag is used by both the connectivity and wifi modules.
     */
    public static final String USE_ROUTE_PARCEL_IPCS = "use_route_parcel_ipcs";
}
