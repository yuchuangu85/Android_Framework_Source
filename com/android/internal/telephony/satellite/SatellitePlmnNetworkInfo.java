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

package com.android.internal.telephony.satellite;

import static android.telephony.satellite.SatelliteManager.NT_RADIO_TECHNOLOGY_LTE_DTC;
import static android.telephony.satellite.SatelliteManager.NT_RADIO_TECHNOLOGY_NB_IOT_NTN;
import static android.telephony.satellite.SatelliteManager.NT_RADIO_TECHNOLOGY_NR_DTC;
import static android.telephony.satellite.SatelliteManager.NT_RADIO_TECHNOLOGY_NR_NTN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.radio.network.SatelliteNetworkInfo;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.SubscriptionManager;
import android.telephony.satellite.SatelliteManager;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents the complete satellite plmn network information, including lists of
 * allowed and disallowed networks.
 * This class instance is used to populate the SatelliteNetworkInfo of HAL API.
 *
 * This class encapsulates the details of each network in the nested {@link NetworkInfo} class,
 * which can be constructed using a {@link NetworkInfo.Builder}.
 */
public final class SatellitePlmnNetworkInfo {
    private static final String TAG = "SatellitePlmnNetworkInfo";
    @NonNull
    private List<NetworkInfo> mAllowedPlmns = new ArrayList<>();
    @NonNull
    private List<NetworkInfo> mDisallowedPlmns = new ArrayList<>();

    /**
     * Constructs a new SatellitePlmnNetworkInfo.
     * @param allowedPlmns A list of networks the user is allowed to connect to.
     * @param disallowedPlmns A list of networks the user is not allowed to connect to.
     */
    public SatellitePlmnNetworkInfo(@NonNull List<NetworkInfo> allowedPlmns,
            @NonNull List<NetworkInfo> disallowedPlmns) {
        mAllowedPlmns = Collections.unmodifiableList(Objects.requireNonNull(allowedPlmns));
        mDisallowedPlmns = Collections.unmodifiableList(Objects.requireNonNull(disallowedPlmns));
    }

    /**
     * @return The unmodifiable list of allowed satellite networks.
     */
    @NonNull
    public List<NetworkInfo> getAllowedPlmns() {
        return mAllowedPlmns;
    }

    /**
     * @return The unmodifiable list of disallowed satellite networks.
     */
    @NonNull
    public List<NetworkInfo> getDisallowedPlmns() {
        return mDisallowedPlmns;
    }

    /**
     * @return A new array containing the allowed satellite networks.
     */
    @NonNull
    public NetworkInfo[] getAllowedPlmnsAsArray() {
        return mAllowedPlmns.toArray(new NetworkInfo[0]);
    }

    /**
     * @return A new array containing the disallowed satellite networks.
     */
    @NonNull
    public NetworkInfo[] getDisallowedPlmnsAsArray() {
        return mDisallowedPlmns.toArray(new NetworkInfo[0]);
    }

    /**
     * Configuration for a specific cellular or satellite network.
     * An instance of this class can be created using {@link Builder}.
     */
    public static final class NetworkInfo {

        /**
         * Public Land Mobile Network ID (MCC + MNC) as a 5 or 6 digit string.
         * This field is mandatory.
         */
        @NonNull
        private final String mPlmn;

        /**
         * List of Absolute Radio Frequency Channel Numbers. This list is optional
         * and thus might not be filled by framework.
         *
         * For GSM: List of ARFCN values.
         * For LTE: List of EARFCN values.
         * For NR: List of NRARFCN values.
         *
         * The default value is an empty array.
         */
        @NonNull
        private final int[] mArfcns;

        /**
         * Access network type. This field is optional and thus might not be filled by framework.
         */
        private final int mAccessNetwork;

        /**
         * Type of satellite technology if the target network is a satellite network.
         * This field is optional and thus might not be filled by framework.
         */
        private final int mSatelliteTechnology;

        /**
         * When satelliteTechnology is different from SatelliteTechnology#NONE,
         * the expected behavior is as follows:
         * {@code true} Modem shall treat the satellite network the same priority as other
         *  terrestrial networks in cell reselection.
         * {@code false} Modem shall treat the satellite network with less priority than terrestrial
         * networks in cell reselection.
         */
        private final boolean mHasSamePriorityAsTn;

        private NetworkInfo(@NonNull Builder builder) {
            this.mPlmn = builder.mPlmn;
            this.mArfcns = builder.mArfcns;
            this.mAccessNetwork = builder.mAccessNetwork;
            this.mSatelliteTechnology = builder.mSatelliteTechnology;
            this.mHasSamePriorityAsTn = builder.mHasSamePriorityAsTn;
        }

        @NonNull
        public String getPlmn() {
            return mPlmn;
        }

        @NonNull
        public int[] getArfcns() {
            return mArfcns;
        }

        public int getAccessNetwork() {
            return mAccessNetwork;
        }

        public int getSatelliteTechnology() {
            return mSatelliteTechnology;
        }

        /**
         * @return mHasSamePriorityAsTn
         */
        public boolean hasSamePriorityAsTn() {
            return mHasSamePriorityAsTn;
        }

        /**
         * Static factory method to create a NetworkInfo with default values for a given PLMN.
         * The default values are:
         * - arfcns: empty array
         * - accessNetwork: AccessNetworkType.UNKNOWN
         * - satelliteTechnology: SatelliteManager.NT_RADIO_TECHNOLOGY_UNKNOWN
         * - hasSamePriorityAsTn: false
         *
         * @param plmn The PLMN for the network.
         * @return A new NetworkInfo object with default settings.
         */
        @NonNull
        public static NetworkInfo createWithDefaults(@NonNull String plmn) {
            // Builder's fields are already set to the defaults.
            return new Builder(plmn).build();
        }

        /**
         * Builder for creating {@link NetworkInfo} instances.
         */
        public static final class Builder {
            // Mandatory parameter
            @NonNull
            private final String mPlmn;

            // Optional parameters with default values
            @NonNull
            private int[] mArfcns = new int[0];
            private int mAccessNetwork = AccessNetworkType.UNKNOWN;
            private int mSatelliteTechnology = SatelliteManager.NT_RADIO_TECHNOLOGY_UNKNOWN;
            private boolean mHasSamePriorityAsTn = false;

            /**
             * Creates a builder for NetworkInfo.
             * @param plmn The Public Land Mobile Network ID (MCC + MNC). This field is mandatory.
             */
            public Builder(@NonNull String plmn) {
                this.mPlmn = Objects.requireNonNull(plmn);
            }

            /** Sets the list of Absolute Radio Frequency Channel Numbers. */
            public Builder setArfcns(@NonNull int[] arfcns) {
                this.mArfcns = Objects.requireNonNull(arfcns);
                return this;
            }

            /** Sets the access network type. */
            public Builder setAccessNetwork(int accessNetwork) {
                this.mAccessNetwork = accessNetwork;
                return this;
            }

            /** Sets the satellite technology type. */
            public Builder setSatelliteTechnology(int satelliteTechnology) {
                this.mSatelliteTechnology = satelliteTechnology;
                return this;
            }

            /** Sets if the satellite network has the same priority as a terrestrial network. */
            public Builder setHasSamePriorityAsTn(boolean hasSamePriorityAsTn) {
                this.mHasSamePriorityAsTn = hasSamePriorityAsTn;
                return this;
            }

            /**
             * Builds the {@link NetworkInfo} object.
             * @return The constructed NetworkInfo object.
             */
            @NonNull
            public NetworkInfo build() {
                return new NetworkInfo(this);
            }

            /**
             * Creates a builder initialized with the values from an existing NetworkInfo object.
             */
            public Builder(@NonNull NetworkInfo info) {
                this.mPlmn = info.mPlmn;
                this.mArfcns = info.mArfcns;
                this.mAccessNetwork = info.mAccessNetwork;
                this.mSatelliteTechnology = info.mSatelliteTechnology;
                this.mHasSamePriorityAsTn = info.mHasSamePriorityAsTn;
            }
        }

        /**
         * Converts this object to its equivalent HAL parcelable representation.
         *
         * @return A new HAL NetworkInfo object.
         */
        @NonNull
        public android.hardware.radio.network.NetworkInfo toHalNetworkInfo() {
            android.hardware.radio.network.NetworkInfo halInfo =
                    new android.hardware.radio.network.NetworkInfo();
            halInfo.plmn = this.mPlmn;
            halInfo.arfcns = this.mArfcns;
            halInfo.accessNetwork = this.mAccessNetwork;
            halInfo.satelliteTechnology = toHalSatelliteTechnology(this.mSatelliteTechnology);
            halInfo.hasSamePriorityAsTn = this.mHasSamePriorityAsTn;
            return halInfo;
        }

        /**
         * Match SatelliteManager constants to HAL SatelliteTechnology constants.
         */
        private static int toHalSatelliteTechnology(int frameworkTech) {
            switch (frameworkTech) {
                case SatelliteManager.NT_RADIO_TECHNOLOGY_NB_IOT_NTN:
                    return android.hardware.radio.network.SatelliteTechnology.SAT_TECH_NB_IOT_NTN;

                case SatelliteManager.NT_RADIO_TECHNOLOGY_LTE_DTC:
                case SatelliteManager.NT_RADIO_TECHNOLOGY_NR_DTC:
                    return android.hardware.radio.network.SatelliteTechnology.SAT_TECH_DTC;

                case SatelliteManager.NT_RADIO_TECHNOLOGY_NR_NTN:
                    return android.hardware.radio.network.SatelliteTechnology.SAT_TECH_3GPP_NTN;

                default:
                    logd("Handle unsupported or unknown technologies as SAT_TECH_NONE or default");
                    return android.hardware.radio.network.SatelliteTechnology.SAT_TECH_NONE;
            }
        }

        @Override
        public String toString() {
            return "NetworkInfo{"
                    + "mPlmn='" + mPlmn + '\''
                    + ", mArfcns=" + Arrays.toString(mArfcns)
                    + ", mAccessNetwork=" + mAccessNetwork
                    + ", mSatelliteTechnology=" + mSatelliteTechnology
                    + ", mHasSamePriorityAsTn=" + mHasSamePriorityAsTn
                    + '}';
        }
    }

    /**
     * Static factory method to create a {@link SatellitePlmnNetworkInfo} from PLMN lists.
     *
     * For each PLMN in the allowed list, this method determines the satellite technology
     * and its corresponding access network type using the
     * {@link SatelliteController#getSatelliteTechnologyList}. For PLMNs in the disallowed list,
     * a {@link NetworkInfo} object with default values is created.
     *
     * @param subId The subscription ID to be used for querying satellite technology.
     * @param allowedPlmns A list of PLMN strings to be allowed. Can be null.
     * @param disallowedPlmns A list of PLMN strings to be disallowed. Can be null.
     * @return A new {@link SatellitePlmnNetworkInfo} object containing the processed lists.
     */
    @NonNull
    public static SatellitePlmnNetworkInfo fromPlmn(int subId, @Nullable List<String> allowedPlmns,
            @Nullable List<String> disallowedPlmns) {
        logd("fromPlmn: subId=" + subId + ", allowedPlmns=" + allowedPlmns + ", disallowedPlmns="
                + disallowedPlmns);

        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            logd("fromPlmn: Invalid subId");
            return new SatellitePlmnNetworkInfo(new ArrayList<>(), new ArrayList<>());
        }

        SatelliteController satelliteController = SatelliteController.getInstance();
        List<NetworkInfo> allowedList = new ArrayList<>();
        if (allowedPlmns != null) {
            for (String plmn : allowedPlmns) {
                if (!TextUtils.isEmpty(plmn)) {
                    List<Integer> supportedSatelliteTechList =
                            satelliteController.getSupportedSatelliteTechnologies(subId, plmn);
                    for (int satelliteTech : supportedSatelliteTechList) {
                        NetworkInfo.Builder networkInfobuilder = new NetworkInfo.Builder(plmn);
                        networkInfobuilder.setSatelliteTechnology(satelliteTech);
                        switch (satelliteTech) {
                            case NT_RADIO_TECHNOLOGY_NB_IOT_NTN:
                            case NT_RADIO_TECHNOLOGY_LTE_DTC:
                                networkInfobuilder.setAccessNetwork(AccessNetworkType.EUTRAN);
                                break;
                            case NT_RADIO_TECHNOLOGY_NR_NTN:
                            case NT_RADIO_TECHNOLOGY_NR_DTC:
                                networkInfobuilder.setAccessNetwork(AccessNetworkType.NGRAN);
                                break;
                            default:
                                networkInfobuilder.setAccessNetwork(AccessNetworkType.UNKNOWN);
                                break;
                        }
                        allowedList.add(networkInfobuilder.build());
                    }
                }
            }
        }

        List<NetworkInfo> disallowedList = new ArrayList<>();
        if (disallowedPlmns != null) {
            for (String plmn : disallowedPlmns) {
                if (!TextUtils.isEmpty(plmn)) {
                    disallowedList.add(NetworkInfo.createWithDefaults(plmn));
                }
            }
        }

        return new SatellitePlmnNetworkInfo(allowedList, disallowedList);
    }

    /**
     * Converts this Java object to its equivalent HAL parcelable representation,
     * which can be sent to the modem.
     *
     * @return A new HAL SatelliteNetworkInfo object.
     */
    @NonNull
    public android.hardware.radio.network.SatelliteNetworkInfo toHalSatelliteNetworkInfo() {
        android.hardware.radio.network.SatelliteNetworkInfo satelliteNetworkInfoForHal =
                new SatelliteNetworkInfo();

        satelliteNetworkInfoForHal.allowedPlmns = mAllowedPlmns.stream()
                .map(NetworkInfo::toHalNetworkInfo)
                .toArray(android.hardware.radio.network.NetworkInfo[]::new);

        satelliteNetworkInfoForHal.disallowedPlmns = mDisallowedPlmns.stream()
                .map(NetworkInfo::toHalNetworkInfo)
                .toArray(android.hardware.radio.network.NetworkInfo[]::new);

        return satelliteNetworkInfoForHal;
    }

    @Override
    public String toString() {
        return "SatellitePlmnNetworkInfo{"
                + "mAllowedPlmns=" + mAllowedPlmns
                + ", mDisallowedPlmns=" + mDisallowedPlmns
                + '}';
    }

    private static void logd(@NonNull String log) {
        Log.d(TAG, log);
    }
}
