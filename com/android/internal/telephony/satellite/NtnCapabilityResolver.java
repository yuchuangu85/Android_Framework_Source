/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.telephony.TelephonyManager.NETWORK_TYPE_LTE;
import static android.telephony.TelephonyManager.NETWORK_TYPE_NR;
import static android.telephony.satellite.SatelliteManager.NT_RADIO_TECHNOLOGY_LTE_DTC;
import static android.telephony.satellite.SatelliteManager.NT_RADIO_TECHNOLOGY_NB_IOT_NTN;
import static android.telephony.satellite.SatelliteManager.NT_RADIO_TECHNOLOGY_NR_DTC;
import static android.telephony.satellite.SatelliteManager.NT_RADIO_TECHNOLOGY_UNKNOWN;

import android.annotation.NonNull;
import android.telephony.NetworkRegistrationInfo;
import android.text.TextUtils;
import android.util.Log;

import java.util.List;
import java.util.Set;

/**
 * This utility class is responsible for resolving NTN capabilities of a
 * {@link NetworkRegistrationInfo}.
 */
public class NtnCapabilityResolver {
    private static final String TAG = "NtnCapabilityResolver";

    /**
     * Resolve NTN capability by updating the input NetworkRegistrationInfo to indicate whether
     * connecting to a non-terrestrial network and the available services supported by the network.
     *
     * @param networkRegistrationInfo The NetworkRegistrationInfo of a network.
     * @param subId                   The subscription ID associated with a phone.
     */
    public static void resolveNtnCapability(
            @NonNull NetworkRegistrationInfo networkRegistrationInfo, int subId) {
        String registeredPlmn = networkRegistrationInfo.getRegisteredPlmn();
        if (TextUtils.isEmpty(registeredPlmn)) {
            logd("Registered PLMN is empty, skip NTN capability resolution");
            return;
        }

        SatelliteController satelliteController = SatelliteController.getInstance();
        Set<String> allSatellitePlmns = satelliteController.getAllPlmnSet();
        boolean isNtn = networkRegistrationInfo.isNonTerrestrialNetwork();
        boolean isDtcSupported =
                satelliteController.isDtcSatelliteTechnologySupported(subId, registeredPlmn);
        logd("isNtn=" + isNtn);
        logd("isDtcSupported=" + isDtcSupported);

        for (String satellitePlmn : allSatellitePlmns) {
            if (isNtn || (TextUtils.equals(satellitePlmn, registeredPlmn) && isDtcSupported)) {
                networkRegistrationInfo.setIsNonTerrestrialNetwork(true);
                List<Integer> supportedServices =
                        satelliteController.getSupportedSatelliteServicesForPlmn(
                                subId, registeredPlmn);
                networkRegistrationInfo.setAvailableServices(supportedServices);
                logd("Registered to satellite PLMN " + registeredPlmn
                        + ", supportedServices = " + supportedServices);
                if (networkRegistrationInfo.getSatelliteTechnology()
                        == NT_RADIO_TECHNOLOGY_UNKNOWN) {
                    networkRegistrationInfo.setSatelliteTechnology(
                            resolveSatelliteTechnology(
                                    networkRegistrationInfo, subId, registeredPlmn));
                }
                return;
            }
        }
    }

    private static int resolveSatelliteTechnology(
            @NonNull NetworkRegistrationInfo nri, int subId, @NonNull String plmn) {
        logd("resolveSatelliteTechnology");
        SatelliteController satelliteController = SatelliteController.getInstance();

        if (satelliteController.isSatelliteEnabledOrBeingEnabled()) {
            logd("resolveSatelliteTechnology: return NT_RADIO_TECHNOLOGY_NB_IOT_NTN");
            return NT_RADIO_TECHNOLOGY_NB_IOT_NTN;
        } else {
            int rat = nri.getAccessNetworkTechnology();
            List<Integer> supportedSatelliteTechList =
                    satelliteController.getSupportedSatelliteTechnologies(subId, plmn);
            logd("resolveSatelliteTechnology: supportedSatelliteTechList="
                    + supportedSatelliteTechList);
            if (rat == NETWORK_TYPE_LTE) {
                logd("resolveSatelliteTechnology: return NT_RADIO_TECHNOLOGY_LTE_DTC");
                return NT_RADIO_TECHNOLOGY_LTE_DTC;
            } else if (rat == NETWORK_TYPE_NR) {
                logd("resolveSatelliteTechnology: return NT_RADIO_TECHNOLOGY_NR_DTC");
                return NT_RADIO_TECHNOLOGY_NR_DTC;
            }
        }
        logd("resolveSatelliteTechnology: return NT_RADIO_TECHNOLOGY_UNKNOWN");
        return NT_RADIO_TECHNOLOGY_UNKNOWN;
    }

    private static void logd(@NonNull String log) {
        Log.d(TAG, log);
    }
}
