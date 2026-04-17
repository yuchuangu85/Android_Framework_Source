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

package android.security.net.config;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.security.Provider;
import java.security.Security;

/**
 * This class is used to set the {@link NetworkSecurityPolicy} according to the app's {@link
 * NetworkSecurityConfig}. The {@link #install} method is invoked at app startup, and the {@link
 * NetworkSecurityConfigProvider} will add itself on the top of the list of security provider. The
 * {@link #handleNewApplication} is used to handle apps inside shared processes.
 *
 * @hide
 */
// TODO(b/451602565): remove this class from System APIs.
@FlaggedApi(com.android.org.conscrypt.net.flags.Flags.FLAG_NETWORK_SECURITY_CONFIG)
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public final class NetworkSecurityConfigProvider extends Provider {
    private static final String LOG_TAG = "nsconfig";
    private static final String PREFIX =
            NetworkSecurityConfigProvider.class.getPackage().getName() + ".";

    /**
     * @hide
     */
    public NetworkSecurityConfigProvider() {
        // TODO: More clever name than this
        super("AndroidNSSP", 1.0, "Android Network Security Policy Provider");
        put("TrustManagerFactory.PKIX", PREFIX + "RootTrustManagerFactorySpi");
        put("Alg.Alias.TrustManagerFactory.X509", "PKIX");
    }

    /**
     * Installs the {@link NetworkSecurityConfigProvider} as the highest priority
     * {@link java.security.Provider} and initializes the default
     * {@link ApplicationConfig} based on the app's network security config.
     *
     * @param context The {@link Context} to use for loading the network security config.
     */
    public static void install(@NonNull Context context) {
        ApplicationConfig config = new ApplicationConfig(new ManifestConfigSource(context));
        ApplicationConfig.setDefaultInstance(config);
        int pos = Security.insertProviderAt(new NetworkSecurityConfigProvider(), 1);
        if (pos != 1) {
            throw new RuntimeException("Failed to install provider as highest priority provider."
                                       + " Provider was installed at position " + pos);
        }
        libcore.net.NetworkSecurityPolicy.setInstance(new ConfigNetworkSecurityPolicy(config));
    }

    /**
     * For a shared process, resolves conflicting values of usesCleartextTraffic.
     * 1. Throws a RuntimeException if the shared process with conflicting
     * usesCleartextTraffic values have per domain rules.
     * 2. Sets the default instance to the least strict config.
     */
    public static void handleNewApplication(@NonNull Context context) {
        ApplicationConfig config = new ApplicationConfig(new ManifestConfigSource(context));
        ApplicationConfig defaultConfig = ApplicationConfig.getDefaultInstance();
        String mProcessName = context.getApplicationInfo().processName;
        if (defaultConfig != null) {
            if (defaultConfig.isCleartextTrafficPermitted()
                != config.isCleartextTrafficPermitted()) {
                Log.w(LOG_TAG,
                      mProcessName + ": New config does not match the previously set config.");

                if (defaultConfig.hasPerDomainConfigs() || config.hasPerDomainConfigs()) {
                    throw new RuntimeException("Found multiple conflicting per-domain rules");
                }
                config = defaultConfig.isCleartextTrafficPermitted() ? defaultConfig : config;
            }
        }
        ApplicationConfig.setDefaultInstance(config);
    }
}
