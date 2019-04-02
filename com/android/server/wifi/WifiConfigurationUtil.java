/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi;

import android.content.pm.UserInfo;
import android.net.IpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiScanner;
import android.os.UserHandle;

import com.android.internal.annotations.VisibleForTesting;

import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * WifiConfiguration utility for any {@link android.net.wifi.WifiConfiguration} related operations.
 * Currently contains:
 *   > Helper method to check if the WifiConfiguration object is visible to the provided users.
 *   > Helper methods to identify the encryption of a WifiConfiguration object.
 */
public class WifiConfigurationUtil {
    /**
     * Check whether a network configuration is visible to a user or any of its managed profiles.
     *
     * @param config   the network configuration whose visibility should be checked
     * @param profiles the user IDs of the user itself and all its managed profiles (can be obtained
     *                 via {@link android.os.UserManager#getProfiles})
     * @return whether the network configuration is visible to the user or any of its managed
     * profiles
     */
    public static boolean isVisibleToAnyProfile(WifiConfiguration config, List<UserInfo> profiles) {
        return (config.shared || doesUidBelongToAnyProfile(config.creatorUid, profiles));
    }

    /**
     * Check whether a uid belong to a user or any of its managed profiles.
     *
     * @param uid      uid of the app.
     * @param profiles the user IDs of the user itself and all its managed profiles (can be obtained
     *                 via {@link android.os.UserManager#getProfiles})
     * @return whether the uid belongs to the user or any of its managed profiles.
     */
    public static boolean doesUidBelongToAnyProfile(int uid, List<UserInfo> profiles) {
        final int userId = UserHandle.getUserId(uid);
        for (UserInfo profile : profiles) {
            if (profile.id == userId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the provided |wepKeys| array contains any non-null value;
     */
    public static boolean hasAnyValidWepKey(String[] wepKeys) {
        for (int i = 0; i < wepKeys.length; i++) {
            if (wepKeys[i] != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Helper method to check if the provided |config| corresponds to a PSK network or not.
     */
    public static boolean isConfigForPskNetwork(WifiConfiguration config) {
        return config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK);
    }

    /**
     * Helper method to check if the provided |config| corresponds to a EAP network or not.
     */
    public static boolean isConfigForEapNetwork(WifiConfiguration config) {
        return (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)
                || config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X));
    }

    /**
     * Helper method to check if the provided |config| corresponds to a WEP network or not.
     */
    public static boolean isConfigForWepNetwork(WifiConfiguration config) {
        return (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)
                && hasAnyValidWepKey(config.wepKeys));
    }

    /**
     * Helper method to check if the provided |config| corresponds to an open network or not.
     */
    public static boolean isConfigForOpenNetwork(WifiConfiguration config) {
        return !(isConfigForWepNetwork(config) || isConfigForPskNetwork(config)
                || isConfigForEapNetwork(config));
    }

    /**
     * Compare existing and new WifiConfiguration objects after a network update and return if
     * IP parameters have changed or not.
     *
     * @param existingConfig Existing WifiConfiguration object corresponding to the network.
     * @param newConfig      New WifiConfiguration object corresponding to the network.
     * @return true if IP parameters have changed, false otherwise.
     */
    public static boolean hasIpChanged(WifiConfiguration existingConfig,
            WifiConfiguration newConfig) {
        if (existingConfig.getIpAssignment() != newConfig.getIpAssignment()) {
            return true;
        }
        if (newConfig.getIpAssignment() == IpConfiguration.IpAssignment.STATIC) {
            return !Objects.equals(existingConfig.getStaticIpConfiguration(),
                    newConfig.getStaticIpConfiguration());
        }
        return false;
    }

    /**
     * Compare existing and new WifiConfiguration objects after a network update and return if
     * proxy parameters have changed or not.
     *
     * @param existingConfig Existing WifiConfiguration object corresponding to the network.
     * @param newConfig      New WifiConfiguration object corresponding to the network.
     * @return true if proxy parameters have changed, false if no existing config and proxy settings
     * are NONE, false otherwise.
     */
    public static boolean hasProxyChanged(WifiConfiguration existingConfig,
            WifiConfiguration newConfig) {
        if (existingConfig == null) {
            return newConfig.getProxySettings() != IpConfiguration.ProxySettings.NONE;
        }
        if (newConfig.getProxySettings() != existingConfig.getProxySettings()) {
            return true;
        }
        return !Objects.equals(existingConfig.getHttpProxy(), newConfig.getHttpProxy());
    }

    /**
     * Compare existing and new WifiEnterpriseConfig objects after a network update and return if
     * credential parameters have changed or not.
     *
     * @param existingEnterpriseConfig Existing WifiConfiguration object corresponding to the
     *                                 network.
     * @param newEnterpriseConfig      New WifiConfiguration object corresponding to the network.
     * @return true if credentials have changed, false otherwise.
     */
    @VisibleForTesting
    public static boolean hasEnterpriseConfigChanged(WifiEnterpriseConfig existingEnterpriseConfig,
            WifiEnterpriseConfig newEnterpriseConfig) {
        if (existingEnterpriseConfig != null && newEnterpriseConfig != null) {
            if (existingEnterpriseConfig.getEapMethod() != newEnterpriseConfig.getEapMethod()) {
                return true;
            }
            if (existingEnterpriseConfig.getPhase2Method()
                    != newEnterpriseConfig.getPhase2Method()) {
                return true;
            }
            X509Certificate[] existingCaCerts = existingEnterpriseConfig.getCaCertificates();
            X509Certificate[] newCaCerts = newEnterpriseConfig.getCaCertificates();
            if (!Arrays.equals(existingCaCerts, newCaCerts)) {
                return true;
            }
        } else {
            // One of the configs may have an enterpriseConfig
            if (existingEnterpriseConfig != null || newEnterpriseConfig != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compare existing and new WifiConfiguration objects after a network update and return if
     * credential parameters have changed or not.
     *
     * @param existingConfig Existing WifiConfiguration object corresponding to the network.
     * @param newConfig      New WifiConfiguration object corresponding to the network.
     * @return true if credentials have changed, false otherwise.
     */
    public static boolean hasCredentialChanged(WifiConfiguration existingConfig,
            WifiConfiguration newConfig) {
        if (!Objects.equals(existingConfig.allowedKeyManagement,
                newConfig.allowedKeyManagement)) {
            return true;
        }
        if (!Objects.equals(existingConfig.allowedProtocols, newConfig.allowedProtocols)) {
            return true;
        }
        if (!Objects.equals(existingConfig.allowedAuthAlgorithms,
                newConfig.allowedAuthAlgorithms)) {
            return true;
        }
        if (!Objects.equals(existingConfig.allowedPairwiseCiphers,
                newConfig.allowedPairwiseCiphers)) {
            return true;
        }
        if (!Objects.equals(existingConfig.allowedGroupCiphers,
                newConfig.allowedGroupCiphers)) {
            return true;
        }
        if (!Objects.equals(existingConfig.preSharedKey, newConfig.preSharedKey)) {
            return true;
        }
        if (!Arrays.equals(existingConfig.wepKeys, newConfig.wepKeys)) {
            return true;
        }
        if (existingConfig.wepTxKeyIndex != newConfig.wepTxKeyIndex) {
            return true;
        }
        if (existingConfig.hiddenSSID != newConfig.hiddenSSID) {
            return true;
        }
        if (hasEnterpriseConfigChanged(existingConfig.enterpriseConfig,
                newConfig.enterpriseConfig)) {
            return true;
        }
        return false;
    }

    /**
     * Check if the provided two networks are the same.
     *
     * @param config      Configuration corresponding to a network.
     * @param config1      Configuration corresponding to another network.
     *
     * @return true if |config| and |config1| are the same network.
     *         false otherwise.
     */
    public static boolean isSameNetwork(WifiConfiguration config, WifiConfiguration config1) {
        if (config == null && config1 == null) {
            return true;
        }
        if (config == null || config1 == null) {
            return false;
        }
        if (config.networkId != config1.networkId) {
            return false;
        }
        if (!Objects.equals(config.SSID, config1.SSID)) {
            return false;
        }
        String networkSelectionBSSID = config.getNetworkSelectionStatus()
                .getNetworkSelectionBSSID();
        String networkSelectionBSSID1 = config1.getNetworkSelectionStatus()
                .getNetworkSelectionBSSID();
        if (!Objects.equals(networkSelectionBSSID, networkSelectionBSSID1)) {
            return false;
        }
        if (WifiConfigurationUtil.hasCredentialChanged(config, config1)) {
            return false;
        }
        return true;
    }

    /**
     * Create a PnoNetwork object from the provided WifiConfiguration.
     *
     * @param config      Configuration corresponding to the network.
     * @param newPriority New priority to be assigned to the network.
     * @return PnoNetwork object corresponding to the network.
     */
    public static WifiScanner.PnoSettings.PnoNetwork createPnoNetwork(
            WifiConfiguration config, int newPriority) {
        WifiScanner.PnoSettings.PnoNetwork pnoNetwork =
                new WifiScanner.PnoSettings.PnoNetwork(config.SSID);
        if (config.hiddenSSID) {
            pnoNetwork.flags |= WifiScanner.PnoSettings.PnoNetwork.FLAG_DIRECTED_SCAN;
        }
        pnoNetwork.flags |= WifiScanner.PnoSettings.PnoNetwork.FLAG_A_BAND;
        pnoNetwork.flags |= WifiScanner.PnoSettings.PnoNetwork.FLAG_G_BAND;
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
            pnoNetwork.authBitField |= WifiScanner.PnoSettings.PnoNetwork.AUTH_CODE_PSK;
        } else if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)
                || config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X)) {
            pnoNetwork.authBitField |= WifiScanner.PnoSettings.PnoNetwork.AUTH_CODE_EAPOL;
        } else {
            pnoNetwork.authBitField |= WifiScanner.PnoSettings.PnoNetwork.AUTH_CODE_OPEN;
        }
        return pnoNetwork;
    }


    /**
     * General WifiConfiguration list sorting algorithm:
     * 1, Place the fully enabled networks first.
     * 2. Next place all the temporarily disabled networks.
     * 3. Place the permanently disabled networks last (Permanently disabled networks are removed
     * before WifiConfigManager uses this comparator today!).
     *
     * Among the networks with the same status, sort them in the order determined by the return of
     * {@link #compareNetworksWithSameStatus(WifiConfiguration, WifiConfiguration)} method
     * implementation.
     */
    public abstract static class WifiConfigurationComparator implements
            Comparator<WifiConfiguration> {
        private static final int ENABLED_NETWORK_SCORE = 3;
        private static final int TEMPORARY_DISABLED_NETWORK_SCORE = 2;
        private static final int PERMANENTLY_DISABLED_NETWORK_SCORE = 1;

        @Override
        public int compare(WifiConfiguration a, WifiConfiguration b) {
            int configAScore = getNetworkStatusScore(a);
            int configBScore = getNetworkStatusScore(b);
            if (configAScore == configBScore) {
                return compareNetworksWithSameStatus(a, b);
            } else {
                return Integer.compare(configBScore, configAScore);
            }
        }

        // This needs to be implemented by the connected/disconnected PNO list comparator.
        abstract int compareNetworksWithSameStatus(WifiConfiguration a, WifiConfiguration b);

        /**
         * Returns an integer representing a score for each configuration. The scores are assigned
         * based on the status of the configuration. The scores are assigned according to the order:
         * Fully enabled network > Temporarily disabled network > Permanently disabled network.
         */
        private int getNetworkStatusScore(WifiConfiguration config) {
            if (config.getNetworkSelectionStatus().isNetworkEnabled()) {
                return ENABLED_NETWORK_SCORE;
            } else if (config.getNetworkSelectionStatus().isNetworkTemporaryDisabled()) {
                return TEMPORARY_DISABLED_NETWORK_SCORE;
            } else {
                return PERMANENTLY_DISABLED_NETWORK_SCORE;
            }
        }
    }
}
