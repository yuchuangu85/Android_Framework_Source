/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.net.connectivity;

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.LocalNetworkConfig;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkScore;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

/**
 * Utility providing limited access to module-internal APIs which are only available on Android S+,
 * as this class is only in the bootclasspath on S+ as part of framework-connectivity.
 *
 * R+ module components like Tethering cannot depend on all hidden symbols from
 * framework-connectivity. They only have access to stable API stubs where newer APIs can be
 * accessed after an API level check (enforced by the linter), or to limited hidden symbols in this
 * class which is also annotated with @RequiresApi (so API level checks are also enforced by the
 * linter).
 * @hide
 */
@RequiresApi(Build.VERSION_CODES.S)
public class ConnectivityInternalApiUtil {

    /**
     * Get a service binder token for
     * {@link com.android.server.connectivity.wear.CompanionDeviceManagerProxyService}.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public static IBinder getCompanionDeviceManagerProxyService(Context ctx) {
        final ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        return cm.getCompanionDeviceManagerProxyService();
    }

    /**
     * Obtain a routing coordinator manager from a context, possibly cross-module.
     * @param ctx the context
     * @return an instance of the coordinator manager
     */
    @RequiresApi(Build.VERSION_CODES.S)
    public static IBinder getRoutingCoordinator(Context ctx) {
        final ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        return cm.getRoutingCoordinatorService();
    }

    /**
     * Create a NetworkAgent instance to be used by Tethering.
     * @param ctx the context
     * @return an instance of the {@code NetworkAgent}
     */
    // TODO: Expose LocalNetworkConfig related APIs and delete this method. This method is
    //  only here because on R Tethering is installed and not Connectivity, requiring all
    //  shared classes to be public API. LocalNetworkConfig is not public yet, but it will
    //  only be used by Tethering on V+ so it's fine.
    @SuppressLint("WrongConstant")
    @NonNull
    public static NetworkAgent buildTetheringNetworkAgent(@NonNull Context ctx,
            @NonNull Looper looper, @NonNull String logTag, int transportType,
            @NonNull LinkProperties lp) {
        // LINT.IfChange
        final NetworkCapabilities.Builder builder = new NetworkCapabilities.Builder()
                .addCapability(NET_CAPABILITY_NOT_METERED)
                .addCapability(NET_CAPABILITY_NOT_ROAMING)
                .addCapability(NET_CAPABILITY_NOT_CONGESTED)
                .addCapability(NET_CAPABILITY_NOT_SUSPENDED)
                .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                .addTransportType(transportType);
        // LINT.ThenChange(../../../../../service/src/com/android/metrics/SatisfiedByLocalNetworkMetrics.java)
        // TODO: Change to use the constant definition. Flags.netCapabilityLocalNetwork() was not
        //  fully rolled out but the service will still process this capability, set it anyway.
        builder.addCapability(36 /* NET_CAPABILITY_LOCAL_NETWORK */);
        final NetworkCapabilities caps = builder.build();
        final NetworkAgentConfig nac = new NetworkAgentConfig.Builder().build();
        return new NetworkAgent(ctx, looper, logTag, caps, lp,
                new LocalNetworkConfig.Builder().build(), new NetworkScore.Builder()
                .setKeepConnectedReason(NetworkScore.KEEP_CONNECTED_LOCAL_NETWORK)
                .build(), nac, null /* provider */) {
        };
    }
}
