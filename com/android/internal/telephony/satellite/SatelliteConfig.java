/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.telephony.satellite;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.FileUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.TelephonyConfigData;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SatelliteConfig is utility class for satellite.
 * It is obtained through the getConfig() at the SatelliteConfigParser.
 */
public class SatelliteConfig {

    private static final String TAG = "SatelliteConfig";
    private static final String SATELLITE_DIR_NAME = "satellite";
    private static final String S2_CELL_FILE_NAME = "s2_cell_file";
    private static final String SATELLITE_ACCESS_CONFIG_JSON_FILE_NAME =
            "satelltie_access_config.json";
    private int mVersion;
    private Map<Integer, Map<String, Set<Integer>>> mSupportedServicesPerCarrier;
    private Integer mCarrierRoamingMaxAllowedDataMode;
    private List<String> mSatelliteRegionCountryCodes;
    private Boolean mIsSatelliteRegionAllowed;
    private File mSatS2File;
    private File mSatelliteAccessConfigJsonFile;
    private List<String> mDeviceSatelliteProviders;
    private TelephonyConfigData.SatelliteConfigProto mConfigData;

    public SatelliteConfig() {
        logd("SatelliteConfig: constructing from scratch");
    }

    public SatelliteConfig(@NonNull SatelliteConfig satelliteConfig) {
        logd("SatelliteConfig: constructing through deep copy of: " + satelliteConfig);
        if (satelliteConfig.mConfigData == null) {
            loge("SatelliteConfig: satelliteConfig.mConfigData is null, return");
            return;
        }
        // Lite proto messages are immutable, so we can just share the reference or use copyFrom if
        // needed, but here the constructor logic expects to parse it again.
        // Actually the original code did 'new SatelliteConfig(satelliteConfig.mConfigData)'.
        // Since Lite objects are immutable, we can just call the other constructor.
        // However, we need to be careful. The original code:
        // new SatelliteConfig(satelliteConfig.mConfigData); -> calling the constructor below.
        // I will replicate that behavior.
        init(satelliteConfig.mConfigData);
    }

    public SatelliteConfig(@NonNull TelephonyConfigData.SatelliteConfigProto configData) {
        logd("SatelliteConfig: constructing with configData: " + configData);
        init(configData);
    }

    private void init(@NonNull TelephonyConfigData.SatelliteConfigProto configData) {
        mConfigData = configData;
        mVersion = mConfigData.getVersion();
        logd("mVersion: " + mVersion);
        buildCarrierSupportedServicesPerCarrier();
        buildCarrierRoamingConfig();
        buildDeviceSatelliteRegion();
    }

    private void buildCarrierSupportedServicesPerCarrier() {
        logd("buildCarrierSupportedServicesPerCarrier");
        if (mConfigData.getCarrierSupportedSatelliteServicesCount() == 0) {
            logd("mSupportedServicesPerCarrier: empty");
        } else {
            mSupportedServicesPerCarrier = getCarrierSupportedSatelliteServices();
            logd("mSupportedServicesPerCarrier: " + mSupportedServicesPerCarrier);
        }
    }

    private void buildCarrierRoamingConfig() {
        logd("buildCarrierRoamingConfig");
        if (!mConfigData.hasCarrierRoamingConfig()) {
            logd("buildCarrierRoamingConfig: carrierRoamingConfig empty");
        } else {
            mCarrierRoamingMaxAllowedDataMode =
                    mConfigData.getCarrierRoamingConfig().getMaxAllowedDataMode();
            logd("buildCarrierRoamingConfig: mCarrierRoamingMaxAllowedDataMode is "
                    + mCarrierRoamingMaxAllowedDataMode);

            if (mConfigData.getCarrierRoamingConfig().getDeviceSatellitePlmnCount() == 0) {
                logd("buildCarrierRoamingConfig: deviceSatellitePlmn is null, set empty list");
                mDeviceSatelliteProviders = new ArrayList<>();
            } else {
                mDeviceSatelliteProviders =
                        mConfigData.getCarrierRoamingConfig().getDeviceSatellitePlmnList();
                logd("buildCarrierRoamingConfig: mDeviceSatelliteProviders: "
                        + String.join(", ", mDeviceSatelliteProviders));
            }
        }
    }

    private void buildDeviceSatelliteRegion() {
        logd("buildDeviceSatelliteRegion");
        if (!mConfigData.hasDeviceSatelliteRegion()) {
            logd("mConfigData.deviceSatelliteRegion: empty");
        } else {
            if (mConfigData.getDeviceSatelliteRegion().getCountryCodesCount() == 0) {
                logd("mConfigData.deviceSatelliteRegion.countryCodes is null, set empty list");
                mSatelliteRegionCountryCodes = new ArrayList<>();
            } else {
                mSatelliteRegionCountryCodes =
                        mConfigData.getDeviceSatelliteRegion().getCountryCodesList();
                logd("mSatelliteRegionCountryCodes: "
                        + String.join(",", mSatelliteRegionCountryCodes));
            }

            mIsSatelliteRegionAllowed = mConfigData.getDeviceSatelliteRegion().getIsAllowed();
            logd("mIsSatelliteRegionAllowed: " + mIsSatelliteRegionAllowed);

            mSatS2File = null;
            if (mConfigData.getDeviceSatelliteRegion().hasS2CellFile()
                    && !mConfigData.getDeviceSatelliteRegion().getS2CellFile().isEmpty()) {
                logd("s2CellFile size: "
                        + mConfigData.getDeviceSatelliteRegion().getS2CellFile().size());
            } else {
                logd("s2CellFile: empty");
            }

            mSatelliteAccessConfigJsonFile = null;
            if (mConfigData.getDeviceSatelliteRegion().hasSatelliteAccessConfigFile()
                    && !mConfigData.getDeviceSatelliteRegion().getSatelliteAccessConfigFile()
                            .isEmpty()) {
                logd("satellite_access_config_json size: "
                        + mConfigData.getDeviceSatelliteRegion()
                                .getSatelliteAccessConfigFile().size());
            } else {
                logd("satellite_access_config_json: empty");
            }
        }
    }

    /**
     * @return a Map data with carrier_id, plmns and allowed_services.
     */
    private Map<Integer, Map<String, Set<Integer>>> getCarrierSupportedSatelliteServices() {
        List<TelephonyConfigData.CarrierSupportedSatelliteServicesProto> satelliteServices =
                mConfigData.getCarrierSupportedSatelliteServicesList();
        Map<Integer, Map<String, Set<Integer>>> carrierToServicesMap = new HashMap<>();
        for (TelephonyConfigData.CarrierSupportedSatelliteServicesProto carrierProto :
                satelliteServices) {
            List<TelephonyConfigData.SatelliteProviderCapabilityProto> satelliteCapabilities =
                    carrierProto.getSupportedSatelliteProviderCapabilitiesList();
            Map<String, Set<Integer>> satelliteCapabilityMap = new HashMap<>();
            for (TelephonyConfigData.SatelliteProviderCapabilityProto capabilityProto :
                    satelliteCapabilities) {
                String carrierPlmn = capabilityProto.getCarrierPlmn();
                Set<Integer> allowedServices = new HashSet<>();
                for (int service : capabilityProto.getAllowedServicesList()) {
                    allowedServices.add(service);
                }
                satelliteCapabilityMap.put(carrierPlmn, allowedServices);
            }
            carrierToServicesMap.put(carrierProto.getCarrierId(), satelliteCapabilityMap);
        }
        return carrierToServicesMap;
    }

    /**
     * @return An {@link Integer} representing the value of
     * {@code mCarrierRoamingMaxAllowedDataMode}. Returns {@code null} if it is not set,
     * which usually implies missing or incomplete configuration
     */
    @Nullable
    public Integer getSatelliteMaxAllowedDataMode() {
        if (mCarrierRoamingMaxAllowedDataMode != null) {
            return mCarrierRoamingMaxAllowedDataMode;
        }
        logd("mCarrierRoamingMaxAllowedDataMode : mConfigData is null or no config data");
        return null;
    }

    /**
     * Overrides the satellite max allowed data mode.
     *
     * @param maxAllowedDataMode the new max allowed data mode
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public void overrideSatelliteMaxAllowedDataMode(int maxAllowedDataMode) {
        logd("overrideSatelliteMaxAllowedDataMode: " + maxAllowedDataMode);
        mCarrierRoamingMaxAllowedDataMode = maxAllowedDataMode;
    }

    /**
     * Get satellite plmns for carrier
     *
     * @param carrierId the carrier identifier.
     * @return Plmns corresponding to carrier identifier.
     */
    @NonNull
    public List<String> getAllSatellitePlmnsForCarrier(int carrierId) {
        if (mSupportedServicesPerCarrier != null) {
            Map<String, Set<Integer>> satelliteCapabilitiesMap = mSupportedServicesPerCarrier.get(
                    carrierId);
            if (satelliteCapabilitiesMap != null) {
                return new ArrayList<>(satelliteCapabilitiesMap.keySet());
            }
        }
        logd("getAllSatellitePlmnsForCarrier : mConfigData is null or no config data");
        return new ArrayList<>();
    }

    /**
     * Get supported satellite services of all providers for a carrier.
     * The format of the return value - Key: PLMN, Value: Set of supported satellite services.
     *
     * @param carrierId the carrier identifier.
     * @return all supported satellite services for a carrier
     */
    @NonNull
    public Map<String, Set<Integer>> getSupportedSatelliteServices(int carrierId) {
        if (mSupportedServicesPerCarrier != null) {
            Map<String, Set<Integer>> satelliteCapaMap =
                    mSupportedServicesPerCarrier.get(carrierId);
            if (satelliteCapaMap != null) {
                return satelliteCapaMap;
            } else {
                logd("No supported services found for carrier=" + carrierId);
            }
        } else {
            logd("mSupportedServicesPerCarrier is null");
        }
        return new HashMap<>();
    }

    /**
     * Get carrier identifier set for the satellite
     *
     * @return carrier identifier set from the config data.
     */
    @NonNull
    public Set<Integer> getAllSatelliteCarrierIds() {
        if (mSupportedServicesPerCarrier != null) {
            return new ArraySet<>(mSupportedServicesPerCarrier.keySet());
        }
        return new ArraySet<>();
    }

    /**
     * @return satellite region country codes
     */
    @NonNull
    public List<String> getDeviceSatelliteCountryCodes() {
        if (mSatelliteRegionCountryCodes != null) {
            return mSatelliteRegionCountryCodes;
        }
        logd("getDeviceSatelliteCountryCodes : mConfigData is null or no config data");
        return new ArrayList<>();
    }

    /**
     * @return satellite access allow value, if there is no config data then it returns null.
     */
    @Nullable
    public Boolean isSatelliteDataForAllowedRegion() {
        if (mIsSatelliteRegionAllowed == null) {
            logd("getIsSatelliteRegionAllowed : mConfigData is null or no config data");
        }
        return mIsSatelliteRegionAllowed;
    }

    /**
     * @return satellite provider plmn list, if there is no config data then it returns empty.
     */
    @NonNull
    public List<String> getDeviceSatelliteProviderList() {
        if (mDeviceSatelliteProviders == null) {
            logd("getDeviceSatelliteProviderList : mDeviceSatelliteProviders is null");
            return new ArrayList<>();
        }
        return mDeviceSatelliteProviders;
    }

    /**
     * @param context the Context
     * @return satellite s2_cell_file path
     */
    @Nullable
    public File getSatelliteS2CellFile(@Nullable Context context) {
        if (context == null) {
            logd("getSatelliteS2CellFile : context is null");
            return null;
        }

        if (isFileExist(mSatS2File)) {
            logd("File mSatS2File is already exist");
            return mSatS2File;
        }

        if (mConfigData != null && mConfigData.hasDeviceSatelliteRegion()) {
            mSatS2File = copySatelliteFileToPhoneDirectory(
                    context, mConfigData.getDeviceSatelliteRegion().getS2CellFile().toByteArray(),
                    S2_CELL_FILE_NAME);
            return mSatS2File;
        }
        logd("getSatelliteS2CellFile: "
                + "mConfigData is null or mConfigData.deviceSatelliteRegion is null");
        return null;
    }

    /**
     * @param context the Context
     * @return satellite access config json path
     */
    @Nullable
    public File getSatelliteAccessConfigJsonFile(@Nullable Context context) {
        if (context == null) {
            logd("getSatelliteAccessConfigJsonFile : context is null");
            return null;
        }

        if (isFileExist(mSatelliteAccessConfigJsonFile)) {
            logd("File mSatelliteAccessConfigJsonFile is already exist");
            return mSatelliteAccessConfigJsonFile;
        }

        if (mConfigData != null && mConfigData.hasDeviceSatelliteRegion()) {
            mSatelliteAccessConfigJsonFile = copySatelliteFileToPhoneDirectory(context,
                    mConfigData.getDeviceSatelliteRegion().getSatelliteAccessConfigFile()
                            .toByteArray(),
                    SATELLITE_ACCESS_CONFIG_JSON_FILE_NAME);
            return mSatelliteAccessConfigJsonFile;
        }
        logd("mSatelliteAccessConfigJsonFile: "
                + "mConfigData is null or mConfigData.deviceSatelliteRegion is null");
        return null;
    }

    /**
     * Get the version of satellite config data
     *
     * @return version corresponding version number of satellite config data.
     */
    @NonNull
    public int getSatelliteConfigDataVersion() {
        logd("getSatelliteConfigDataVersion: mVersion: " + mVersion);
        return mVersion;
    }

    /**
     * @param context       the Context
     * @param byteArrayFile byte array type of protobuffer config data
     * @return the path for satellite_file in phone process
     */
    @Nullable
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public File copySatelliteFileToPhoneDirectory(@Nullable Context context,
            @Nullable byte[] byteArrayFile, String fileName) {

        if (context == null || byteArrayFile == null) {
            logd("copySatelliteFileToPhoneDirectory : context or byteArrayFile are null");
            return null;
        }

        File satelliteFileDir = context.getDir(SATELLITE_DIR_NAME, Context.MODE_PRIVATE);
        if (!satelliteFileDir.exists()) {
            satelliteFileDir.mkdirs();
        }

        Path targetSatelliteFilePath = satelliteFileDir.toPath().resolve(fileName);
        try {
            InputStream inputStream = new ByteArrayInputStream(byteArrayFile);
            if (inputStream == null) {
                logd("copySatelliteFileToPhoneDirectory: Resource=" + fileName
                        + " not found");
            } else {
                Files.copy(inputStream, targetSatelliteFilePath,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            loge("copySatelliteFileToPhoneDirectory: ex=" + ex);
        }
        logd("targetSatelliteFilePath's path: "
                + targetSatelliteFilePath.toAbsolutePath().toString());
        return targetSatelliteFilePath.toFile();
    }

    /**
     * This method cleans the Satellite Config OTA resources and it should be used only in CTS/Unit
     * tests
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public void cleanOtaResources(@Nullable Context context) {
        if (context == null) {
            logd("cleanOtaResources : context is null");
            return;
        }
        try {
            File satelliteFileDir = context.getDir(SATELLITE_DIR_NAME, Context.MODE_PRIVATE);
            if (!satelliteFileDir.exists()) {
                logd("cleanOtaResources: " + SATELLITE_DIR_NAME
                        + " does not exist. No need to clean.");
                return;
            }
            logd("cleanOtaResources: Deleting contents under " + SATELLITE_DIR_NAME);
            FileUtils.deleteContents(satelliteFileDir);
        } catch (Exception e) {
            loge("cleanOtaResources error : " + e);
        }
    }

    /**
     * This method cleans the Satellite Config OTA resources and it should be used only in CTS/Unit
     * tests
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public boolean hasSatelliteS2CellFile() {
        if (mConfigData != null && mConfigData.hasDeviceSatelliteRegion()) {
            if (mConfigData.getDeviceSatelliteRegion().hasS2CellFile()
                    && !mConfigData.getDeviceSatelliteRegion().getS2CellFile().isEmpty()) {
                logd("hasSatelliteS2CellFile: s2CellFile is exist");
                return true;
            }
        }
        logd("hasSatelliteS2CellFile: s2CellFile is not exist");
        return false;
    }

    /**
     * This method cleans the Satellite Config OTA resources and it should be used only in CTS/Unit
     * tests
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public boolean hasSatelliteAccessConfigFile() {
        if (mConfigData != null && mConfigData.hasDeviceSatelliteRegion()) {
            if (mConfigData.getDeviceSatelliteRegion().hasSatelliteAccessConfigFile()
                    && !mConfigData.getDeviceSatelliteRegion().getSatelliteAccessConfigFile()
                            .isEmpty()) {
                logd("hasSatelliteAccessConfigFile: satelliteAccessConfigFile is exist");
                return true;
            }
        }
        logd("hasSatelliteAccessConfigFile: satelliteAccessConfigFile is not exist");
        return false;
    }

    /**
     * @return {@code true} if the SatS2File is already existed and {@code false} otherwise.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public boolean isFileExist(@Nullable File file) {
        if (file == null) {
            logd("isFileExist : file is null");
            return false;
        }
        return file.exists();
    }

    private static void logd(@NonNull String log) {
        Log.d(TAG, log);
    }

    private static void loge(@NonNull String log) {
        Log.e(TAG, log);
    }

    @Override
    public String toString() {
        return "SatelliteConfig{"
                + "mVersion="
                + mVersion
                + ", mSupportedServicesPerCarrier="
                + mSupportedServicesPerCarrier
                + ", mCarrierRoamingMaxAllowedDataMode="
                + mCarrierRoamingMaxAllowedDataMode
                + ", mSatelliteRegionCountryCodes="
                + mSatelliteRegionCountryCodes
                + ", mIsSatelliteRegionAllowed="
                + mIsSatelliteRegionAllowed
                + ", mSatS2File="
                + mSatS2File
                + ", mSatelliteAccessConfigJsonFile="
                + mSatelliteAccessConfigJsonFile
                + ", mConfigData="
                + mConfigData
                + "}";
    }
}
