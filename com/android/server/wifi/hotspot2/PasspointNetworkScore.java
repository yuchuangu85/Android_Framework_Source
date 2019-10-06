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

package com.android.server.wifi.hotspot2;

import android.net.RssiCurve;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType;
import com.android.server.wifi.hotspot2.anqp.HSWanMetricsElement;
import com.android.server.wifi.hotspot2.anqp.IPAddressTypeAvailabilityElement;

import java.util.HashMap;
import java.util.Map;

/**
 * This is an utility class for calculating score for Passpoint networks.
 */
public class PasspointNetworkScore {
    /**
     * Award points for network that's a Passpoint home provider.
     */
    @VisibleForTesting
    public static final int HOME_PROVIDER_AWARD = 100;

    /**
     * Award points for network that provides Internet access.
     */
    @VisibleForTesting
    public static final int INTERNET_ACCESS_AWARD = 50;

    /**
     * Award points for public or private network.
     */
    @VisibleForTesting
    public static final int PUBLIC_OR_PRIVATE_NETWORK_AWARDS = 4;

    /**
     * Award points for personal or emergency network.
     */
    @VisibleForTesting
    public static final int PERSONAL_OR_EMERGENCY_NETWORK_AWARDS = 2;

    /**
     * Award points for network providing restricted or unknown IP address.
     */
    @VisibleForTesting
    public static final int RESTRICTED_OR_UNKNOWN_IP_AWARDS = 1;

    /**
     * Award points for network providing unrestricted IP address.
     */
    @VisibleForTesting
    public static final int UNRESTRICTED_IP_AWARDS = 2;

    /**
     * Penalty points for network with WAN port that's down or the load already reached the max.
     */
    @VisibleForTesting
    public static final int WAN_PORT_DOWN_OR_CAPPED_PENALTY = 50;

    // Award points for availability of IPv4 and IPv6 addresses.
    private static final Map<Integer, Integer> IPV4_SCORES = new HashMap<>();
    private static final Map<Integer, Integer> IPV6_SCORES = new HashMap<>();

    // Award points based on access network type.
    private static final Map<NetworkDetail.Ant, Integer> NETWORK_TYPE_SCORES = new HashMap<>();

    /**
     * Curve for calculating score for RSSI level.
     */
    @VisibleForTesting
    public static final RssiCurve RSSI_SCORE = new RssiCurve(-80 /* start */, 20 /* bucketWidth */,
            new byte[] {-10, 0, 10, 20, 30, 40} /* rssiBuckets */,
            20 /* activeNetworkRssiBoost */);

    static {
        // These are all arbitrarily chosen scores, subject to tuning.

        NETWORK_TYPE_SCORES.put(NetworkDetail.Ant.FreePublic, PUBLIC_OR_PRIVATE_NETWORK_AWARDS);
        NETWORK_TYPE_SCORES.put(NetworkDetail.Ant.ChargeablePublic,
                PUBLIC_OR_PRIVATE_NETWORK_AWARDS);
        NETWORK_TYPE_SCORES.put(NetworkDetail.Ant.PrivateWithGuest,
                PUBLIC_OR_PRIVATE_NETWORK_AWARDS);
        NETWORK_TYPE_SCORES.put(NetworkDetail.Ant.Private,
                PUBLIC_OR_PRIVATE_NETWORK_AWARDS);
        NETWORK_TYPE_SCORES.put(NetworkDetail.Ant.Personal, PERSONAL_OR_EMERGENCY_NETWORK_AWARDS);
        NETWORK_TYPE_SCORES.put(NetworkDetail.Ant.EmergencyOnly,
                PERSONAL_OR_EMERGENCY_NETWORK_AWARDS);
        NETWORK_TYPE_SCORES.put(NetworkDetail.Ant.Wildcard, 0);
        NETWORK_TYPE_SCORES.put(NetworkDetail.Ant.TestOrExperimental, 0);

        IPV4_SCORES.put(IPAddressTypeAvailabilityElement.IPV4_NOT_AVAILABLE, 0);
        IPV4_SCORES.put(IPAddressTypeAvailabilityElement.IPV4_PORT_RESTRICTED,
                RESTRICTED_OR_UNKNOWN_IP_AWARDS);
        IPV4_SCORES.put(IPAddressTypeAvailabilityElement.IPV4_PORT_RESTRICTED_AND_SINGLE_NAT,
                RESTRICTED_OR_UNKNOWN_IP_AWARDS);
        IPV4_SCORES.put(IPAddressTypeAvailabilityElement.IPV4_PORT_RESTRICTED_AND_DOUBLE_NAT,
                RESTRICTED_OR_UNKNOWN_IP_AWARDS);
        IPV4_SCORES.put(IPAddressTypeAvailabilityElement.IPV4_UNKNOWN,
                RESTRICTED_OR_UNKNOWN_IP_AWARDS);
        IPV4_SCORES.put(IPAddressTypeAvailabilityElement.IPV4_PUBLIC, UNRESTRICTED_IP_AWARDS);
        IPV4_SCORES.put(IPAddressTypeAvailabilityElement.IPV4_SINGLE_NAT, UNRESTRICTED_IP_AWARDS);
        IPV4_SCORES.put(IPAddressTypeAvailabilityElement.IPV4_DOUBLE_NAT, UNRESTRICTED_IP_AWARDS);

        IPV6_SCORES.put(IPAddressTypeAvailabilityElement.IPV6_NOT_AVAILABLE, 0);
        IPV6_SCORES.put(IPAddressTypeAvailabilityElement.IPV6_UNKNOWN,
                RESTRICTED_OR_UNKNOWN_IP_AWARDS);
        IPV6_SCORES.put(IPAddressTypeAvailabilityElement.IPV6_AVAILABLE,
                UNRESTRICTED_IP_AWARDS);
    }


    /**
     * Calculate and return a score associated with the given Passpoint network.
     * The score is calculated with the following preferences:
     * - Prefer home provider
     * - Prefer network that provides Internet access
     * - Prefer network with active WAN port with available load
     * - Prefer network that provides unrestricted IP address
     * - Prefer currently active network
     * - Prefer AP with higher RSSI
     *
     * This can be expanded for additional preference in the future (e.g. AP station count, link
     * speed, and etc).
     *
     * @param isHomeProvider Flag indicating home provider
     * @param scanDetail The ScanDetail associated with the AP
     * @param isActiveNetwork Flag indicating current active network
     * @return integer score
     */
    public static int calculateScore(boolean isHomeProvider, ScanDetail scanDetail,
            Map<ANQPElementType, ANQPElement> anqpElements, boolean isActiveNetwork) {
        NetworkDetail networkDetail = scanDetail.getNetworkDetail();
        int score = 0;
        if (isHomeProvider) {
            score += HOME_PROVIDER_AWARD;
        }

        // Adjust score based on Internet accessibility.
        score += (networkDetail.isInternet() ? 1 : -1) * INTERNET_ACCESS_AWARD;

        // Adjust score based on the network type.
        Integer ndScore = NETWORK_TYPE_SCORES.get(networkDetail.getAnt());
        if (ndScore != null) {
            score += ndScore;
        }

        if (anqpElements != null) {
            HSWanMetricsElement wm =
                    (HSWanMetricsElement) anqpElements.get(ANQPElementType.HSWANMetrics);
            if (wm != null) {
                if (wm.getStatus() != HSWanMetricsElement.LINK_STATUS_UP || wm.isCapped()) {
                    score -= WAN_PORT_DOWN_OR_CAPPED_PENALTY;
                }
            }

            IPAddressTypeAvailabilityElement ipa = (IPAddressTypeAvailabilityElement)
                    anqpElements.get(ANQPElementType.ANQPIPAddrAvailability);

            if (ipa != null) {
                Integer v4Score = IPV4_SCORES.get(ipa.getV4Availability());
                Integer v6Score = IPV6_SCORES.get(ipa.getV6Availability());
                v4Score = v4Score != null ? v4Score : 0;
                v6Score = v6Score != null ? v6Score : 0;
                score += (v4Score + v6Score);
            }
        }

        score += RSSI_SCORE.lookupScore(scanDetail.getScanResult().level, isActiveNetwork);
        return score;
    }
}
