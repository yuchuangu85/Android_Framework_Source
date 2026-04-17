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

package com.android.internal.telephony.data;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.text.TextUtils;
import android.util.ArrayMap;

import com.android.internal.telephony.TelephonyConfigData;
import com.android.telephony.Rlog;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * DataConfig holds the dynamic configuration for Telephony data operations.
 *
 * <p>This class is responsible for parsing and storing the configuration data obtained from
 * the {@link TelephonyConfigData.DataConfigProto}, which is typically delivered via the
 * ConfigUpdater mechanism. This allows for dynamic updates to data policies.</p>
 */
public class DataConfig {
    private static final String TAG = "DataConfig";

    /**
     * The key used to store default configuration rules in the maps.
     * Carrier IDs are positive integers, so 0 is safe to use as a special key for defaults.
     */
    private static final int DEFAULT_CARRIER_ID = 0;

    private final int mVersion;

    /**
     * Stores the mapping from Network Capability to Connection Capability (Slice ID).
     * <p>Key: Carrier ID (or {@link #DEFAULT_CARRIER_ID} for default rules).
     * <br>Value: A Map where Key = {@link android.net.NetworkCapabilities} NetCapability,
     *            Value = {@link android.telephony.data.TrafficDescriptor.ConnectionCapability}.
     */
    private final Map<Integer, Map<Integer, Integer>> mConnectionCapabilities = new ArrayMap<>();

    /**
     * Stores the APN Requirement setting for specific Network Capabilities.
     * <p>Key: Carrier ID (or {@link #DEFAULT_CARRIER_ID} for default rules).
     * <br>Value: A Map where Key = {@link android.net.NetworkCapabilities} NetCapability,
     *            Value = Boolean (true if APN is required).
     */
    private final Map<Integer, Map<Integer, Boolean>> mApnRequiredMap = new ArrayMap<>();

    /**
     * Stores the set of Network Capabilities that should be treated as metered on the Home network.
     * <p>Key: Carrier ID (or {@link #DEFAULT_CARRIER_ID} for default rules).
     * <br>Value: Set of {@link android.net.NetworkCapabilities} IDs.
     */
    private final Map<Integer, Set<Integer>> mHomeMeteredCapabilities = new ArrayMap<>();

    /**
     * Stores the set of Network Capabilities that should be treated as metered when Roaming.
     * <p>Key: Carrier ID (or {@link #DEFAULT_CARRIER_ID} for default rules).
     * <br>Value: Set of {@link android.net.NetworkCapabilities} IDs.
     */
    private final Map<Integer, Set<Integer>> mRoamingMeteredCapabilities = new ArrayMap<>();

    /**
     * Stores all unique Network Capabilities defined in this configuration.
     * Populated during parsing to avoid recalculation.
     */
    private final Set<Integer> mAllNetworkCapabilities = new HashSet<>();

    private final TelephonyConfigData.DataConfigProto mConfigData;

    public DataConfig(@NonNull TelephonyConfigData.DataConfigProto configData) {
        logd("DataConfig: constructing with configData version: " + configData.getVersion());
        mVersion = configData.getVersion();
        mConfigData = configData;

        // 1. Parse Connection Capability Configs
        if (configData.hasConnectionCapabilityConfigs()) {
            TelephonyConfigData.ConnectionCapabilityConfig connConfig =
                    configData.getConnectionCapabilityConfigs();

            // Parse Default Rules (Store with DEFAULT_CARRIER_ID)
            if (connConfig.hasDefaultConnectionCapabilityConfig()) {
                parseAndStoreConnectionCapabilityRules(DEFAULT_CARRIER_ID,
                        connConfig.getDefaultConnectionCapabilityConfig().getRulesList());
            }

            // Parse Carrier Specific Rules
            for (TelephonyConfigData.ConnectionCapabilityMap map :
                    connConfig.getCarrierConnectionCapabilityConfigsList()) {
                if (map.hasCarrierId() && map.getCarrierId() > 0) {
                    parseAndStoreConnectionCapabilityRules(map.getCarrierId(), map.getRulesList());
                }
            }
        }

        // 2. Parse Home Metered Capability Configs
        if (configData.hasHomeMeteredCapabilityConfigs()) {
            TelephonyConfigData.MeteredCapabilityConfig homeConfig =
                    configData.getHomeMeteredCapabilityConfigs();

            if (homeConfig.hasDefaultMeteredCapabilityConfig()) {
                mHomeMeteredCapabilities.put(DEFAULT_CARRIER_ID, new HashSet<>(
                        homeConfig.getDefaultMeteredCapabilityConfig().getCapabilityIdsList()));
            }

            for (TelephonyConfigData.MeteredCapabilities caps :
                    homeConfig.getCarrierMeteredCapabilityConfigsList()) {
                if (caps.hasCarrierId() && caps.getCarrierId() > 0) {
                    mHomeMeteredCapabilities.put(caps.getCarrierId(),
                            new HashSet<>(caps.getCapabilityIdsList()));
                }
            }
        }

        // 3. Parse Roaming Metered Capability Configs
        if (configData.hasRoamMeteredCapabilityConfigs()) {
            TelephonyConfigData.MeteredCapabilityConfig roamConfig =
                    configData.getRoamMeteredCapabilityConfigs();

            if (roamConfig.hasDefaultMeteredCapabilityConfig()) {
                mRoamingMeteredCapabilities.put(DEFAULT_CARRIER_ID, new HashSet<>(
                        roamConfig.getDefaultMeteredCapabilityConfig().getCapabilityIdsList()));
            }

            for (TelephonyConfigData.MeteredCapabilities caps :
                    roamConfig.getCarrierMeteredCapabilityConfigsList()) {
                if (caps.hasCarrierId() && caps.getCarrierId() > 0) {
                    mRoamingMeteredCapabilities.put(caps.getCarrierId(),
                            new HashSet<>(caps.getCapabilityIdsList()));
                }
            }
        }
    }

    @NonNull
    public TelephonyConfigData.DataConfigProto getConfigData() {
        return mConfigData;
    }

    /**
     * Helper to parse string rules into maps.
     */
    private void parseAndStoreConnectionCapabilityRules(int carrierId, List<String> rules) {
        Map<Integer, Integer> capsMap = new ArrayMap<>();
        Map<Integer, Boolean> apnReqMap = new ArrayMap<>();

        for (String rule : rules) {
            // Rule Format: "NetworkCapability:ConnectionCapability:ApnRequired"
            if (TextUtils.isEmpty(rule)) continue;
            String[] parts = rule.split(":");
            if (parts.length == 3) {
                try {
                    int netCap = Integer.parseInt(parts[0]);
                    int connCap = Integer.parseInt(parts[1]);
                    boolean apnReq = Boolean.parseBoolean(parts[2]);
                    capsMap.put(netCap, connCap);
                    apnReqMap.put(netCap, apnReq);
                } catch (NumberFormatException e) {
                    Rlog.e(TAG, "Invalid format in rule: " + rule);
                }
            }
        }

        mConnectionCapabilities.put(carrierId, capsMap);
        mApnRequiredMap.put(carrierId, apnReqMap);
        mAllNetworkCapabilities.addAll(capsMap.keySet());
    }

    public int getVersion() {
        return mVersion;
    }

    /**
     * Returns the map of NetworkCapability to ConnectionCapability for the given carrier.
     *
     * <p>This method resolves the configuration by checking for carrier-specific rules first. If
     * no rules are found for the specific {@code carrierId}, it falls back to the default rules
     * defined in the configuration file.
     *
     * @param carrierId The carrier ID for which to retrieve the connection capability mappings.
     * @return A {@code Map} where the key is the {@link android.net.NetworkCapabilities}
     *         NetCapability and the value is the mapped
     *         {@link android.telephony.data.TrafficDescriptor.ConnectionCapability}.
     *         <p>Returns {@code null} if no configuration is found (neither for the specific
     *         carrier nor the default fallback).
     *         <p>Returns an empty map if a configuration exists but explicitly contains no rules.
     */
    @Nullable
    public Map<Integer, Integer> getConnectionCapabilities(int carrierId) {
        if (mConnectionCapabilities.containsKey(carrierId)) {
            return mConnectionCapabilities.get(carrierId);
        }
        return mConnectionCapabilities.getOrDefault(DEFAULT_CARRIER_ID, null);
    }

    /**
     * Returns the map of NetworkCapability to ApnRequired boolean for the given carrier.
     *
     * <p>This method determines whether a specific Network Capability requires a matching APN
     * setting to be present in the database to establish a data connection. It checks for
     * carrier-specific rules first, falling back to default rules if necessary.
     *
     * @param carrierId The carrier ID for which to retrieve the APN requirement settings.
     * @return A {@code Map} where the key is the {@link android.net.NetworkCapabilities}
     *         NetCapability and the value is a {@code Boolean} indicating if an APN match is
     *         required (true) or not (false).
     *         <p>Returns {@code null} if no configuration is found (neither for the specific
     *         carrier nor the default fallback).
     *         <p>Returns an empty map if a configuration exists but explicitly contains no rules.
     */
    @Nullable
    public Map<Integer, Boolean> getApnRequired(int carrierId) {
        if (mApnRequiredMap.containsKey(carrierId)) {
            return mApnRequiredMap.get(carrierId);
        }
        return mApnRequiredMap.getOrDefault(DEFAULT_CARRIER_ID, null);
    }

    /**
     * Retrieves the set of Network Capabilities that are considered metered for a given carrier
     * and roaming status.
     *
     * <p>This method resolves the configuration by checking for carrier-specific rules first, then
     * falling back to default rules defined in the configuration file.
     *
     * <p>Case 1: If a configuration exists for the provided {@code carrierId} and {@code isRoaming}
     * status, that specific set of capabilities is returned. Note that this set may be empty if the
     * carrier explicitly configures no capabilities to be metered.
     *
     * <p>Case 2: If no carrier-specific configuration is found, the method checks for a default
     * configuration (using {@link #DEFAULT_CARRIER_ID}) provided in the update file. If present,
     * this default set is returned.
     *
     * <p>Case 3: If neither a carrier-specific nor a default configuration is found in the
     * update file, {@code null} is returned. This signals the caller to fall back to hardcoded
     * system defaults.
     *
     * @param carrierId The carrier ID for which to retrieve the metered capabilities.
     * @param isRoaming {@code true} to retrieve capabilities for roaming network, {@code false}
     *                  for home network.
     * @return A {@code Set} of {@link android.net.NetworkCapabilities} IDs representing the
     *         metered capabilities, or {@code null} if no configuration is found.
     */
    @Nullable
    public Set<Integer> getMeteredNetworkCapabilities(int carrierId, boolean isRoaming) {
        Map<Integer, Set<Integer>> targetMap = isRoaming
                ? mRoamingMeteredCapabilities : mHomeMeteredCapabilities;

        if (targetMap.containsKey(carrierId)) {
            return targetMap.get(carrierId);
        }
        return targetMap.getOrDefault(DEFAULT_CARRIER_ID, null);
    }

    /**
     * @return A set of all unique Network Capabilities defined in the dynamic configuration.
     *         This includes capabilities from both default and carrier-specific rules.
     */
    @NonNull
    public Set<Integer> getAllNetworkCapabilities() {
        return mAllNetworkCapabilities;
    }

    private static void logd(String log) {
        Rlog.d(TAG, log);
    }

    /**
     * Compares this DataConfig with another object for equality.
     * Two DataConfigs are considered equal if all their underlying functional
     * configurations (connection capabilities, APN requirements, and metered capabilities)
     * are identical.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DataConfig that = (DataConfig) o;

        return mConnectionCapabilities.equals(that.mConnectionCapabilities)
                && mApnRequiredMap.equals(that.mApnRequiredMap)
                && mHomeMeteredCapabilities.equals(that.mHomeMeteredCapabilities)
                && mRoamingMeteredCapabilities.equals(that.mRoamingMeteredCapabilities);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mConnectionCapabilities, mApnRequiredMap,
                mHomeMeteredCapabilities, mRoamingMeteredCapabilities);
    }
}
