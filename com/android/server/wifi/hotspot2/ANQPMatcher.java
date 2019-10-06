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

import static com.android.server.wifi.hotspot2.Utils.isCarrierEapMethod;

import com.android.server.wifi.IMSIParameter;
import com.android.server.wifi.hotspot2.anqp.CellularNetwork;
import com.android.server.wifi.hotspot2.anqp.DomainNameElement;
import com.android.server.wifi.hotspot2.anqp.NAIRealmData;
import com.android.server.wifi.hotspot2.anqp.NAIRealmElement;
import com.android.server.wifi.hotspot2.anqp.RoamingConsortiumElement;
import com.android.server.wifi.hotspot2.anqp.ThreeGPPNetworkElement;
import com.android.server.wifi.hotspot2.anqp.eap.AuthParam;
import com.android.server.wifi.hotspot2.anqp.eap.EAPMethod;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility class for providing matching functions against ANQP elements.
 */
public class ANQPMatcher {
    /**
     * Match the domain names in the ANQP element against the provider's FQDN and SIM credential.
     * The Domain Name ANQP element might contain domains for 3GPP network (e.g.
     * wlan.mnc*.mcc*.3gppnetwork.org), so we should match that against the provider's SIM
     * credential if one is provided.
     *
     * @param element The Domain Name ANQP element
     * @param fqdn The FQDN to compare against
     * @param imsiParam The IMSI parameter of the provider
     * @param simImsiList The list of IMSI from the installed SIM cards that matched provider's
     *                    IMSI parameter
     * @return true if a match is found
     */
    public static boolean matchDomainName(DomainNameElement element, String fqdn,
            IMSIParameter imsiParam, List<String> simImsiList) {
        if (element == null) {
            return false;
        }

        for (String domain : element.getDomains()) {
            if (DomainMatcher.arg2SubdomainOfArg1(fqdn, domain)) {
                return true;
            }

            // Try to retrieve the MCC-MNC string from the domain (for 3GPP network domain) and
            // match against the provider's SIM credential.
            if (matchMccMnc(Utils.getMccMnc(Utils.splitDomain(domain)), imsiParam, simImsiList)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Match the roaming consortium OIs in the ANQP element against the roaming consortium OIs
     * of a provider.
     *
     * @param element The Roaming Consortium ANQP element
     * @param providerOIs The roaming consortium OIs of the provider
     * @return true if a match is found
     */
    public static boolean matchRoamingConsortium(RoamingConsortiumElement element,
            long[] providerOIs) {
        if (element == null) {
            return false;
        }
        if (providerOIs == null) {
            return false;
        }
        List<Long> rcOIs = element.getOIs();
        for (long oi : providerOIs) {
            if (rcOIs.contains(oi)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Match the NAI realm in the ANQP element against the realm and authentication method of
     * a provider.
     *
     * @param element The NAI Realm ANQP element
     * @param realm The realm of the provider's credential
     * @param eapMethodID The EAP Method ID of the provider's credential
     * @param authParam The authentication parameter of the provider's credential
     * @return an integer indicating the match status
     */
    public static int matchNAIRealm(NAIRealmElement element, String realm, int eapMethodID,
            AuthParam authParam) {
        if (element == null || element.getRealmDataList().isEmpty()) {
            return AuthMatch.INDETERMINATE;
        }

        int bestMatch = AuthMatch.NONE;
        for (NAIRealmData realmData : element.getRealmDataList()) {
            int match = matchNAIRealmData(realmData, realm, eapMethodID, authParam);
            if (match > bestMatch) {
                bestMatch = match;
                if (bestMatch == AuthMatch.EXACT) {
                    break;
                }
            }
        }
        return bestMatch;
    }

    /**
     * Get a EAP-Method from a corresponding NAI realm that has one of them (EAP-SIM/AKA/AKA)'.
     *
     * @param realm a realm of the provider's credential.
     * @param element The NAI Realm ANQP element
     * @return a EAP Method (EAP-SIM/AKA/AKA') from matching NAI realm, {@code -1} otherwise.
     */
    public static int getCarrierEapMethodFromMatchingNAIRealm(String realm,
            NAIRealmElement element) {
        if (element == null || element.getRealmDataList().isEmpty()) {
            return -1;
        }

        for (NAIRealmData realmData : element.getRealmDataList()) {
            int eapMethodID = getEapMethodForNAIRealmWithCarrier(realm, realmData);
            if (eapMethodID != -1) {
                return eapMethodID;
            }
        }
        return -1;
    }

    /**
     * Match the 3GPP Network in the ANQP element against the SIM credential of a provider.
     *
     * @param element 3GPP Network ANQP element
     * @param imsiParam The IMSI parameter of the provider's SIM credential
     * @param simImsiList The list of IMSI from the installed SIM cards that matched provider's
     *                    IMSI parameter
     * @return true if a matched is found
     */
    public static  boolean matchThreeGPPNetwork(ThreeGPPNetworkElement element,
            IMSIParameter imsiParam, List<String> simImsiList) {
        if (element == null) {
            return false;
        }
        for (CellularNetwork network : element.getNetworks()) {
            if (matchCellularNetwork(network, imsiParam, simImsiList)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Match the given NAI Realm data against the realm and authentication method of a provider.
     *
     * @param realmData The NAI Realm data
     * @param realm The realm of the provider's credential
     * @param eapMethodID The EAP Method ID of the provider's credential
     * @param authParam The authentication parameter of the provider's credential
     * @return an integer indicating the match status
     */
    private static int matchNAIRealmData(NAIRealmData realmData, String realm, int eapMethodID,
            AuthParam authParam) {
        // Check for realm domain name match.
        int realmMatch = AuthMatch.NONE;
        for (String realmStr : realmData.getRealms()) {
            if (DomainMatcher.arg2SubdomainOfArg1(realm, realmStr)) {
                realmMatch = AuthMatch.REALM;
                break;
            }
        }

        if (realmData.getEAPMethods().isEmpty()) {
            return realmMatch;
        }

        // Check for EAP method match.
        int eapMethodMatch = AuthMatch.NONE;
        for (EAPMethod eapMethod : realmData.getEAPMethods()) {
            eapMethodMatch = matchEAPMethod(eapMethod, eapMethodID, authParam);
            if (eapMethodMatch != AuthMatch.NONE) {
                break;
            }
        }

        if (eapMethodMatch == AuthMatch.NONE) {
            return AuthMatch.NONE;
        }

        if (realmMatch == AuthMatch.NONE) {
            return eapMethodMatch;
        }
        return realmMatch | eapMethodMatch;
    }

    private static int getEapMethodForNAIRealmWithCarrier(String realm,
            NAIRealmData realmData) {
        int realmMatch = AuthMatch.NONE;

        for (String realmStr : realmData.getRealms()) {
            if (DomainMatcher.arg2SubdomainOfArg1(realm, realmStr)) {
                realmMatch = AuthMatch.REALM;
                break;
            }
        }

        if (realmMatch == AuthMatch.NONE) {
            return -1;
        }

        for (EAPMethod eapMethod : realmData.getEAPMethods()) {
            if (isCarrierEapMethod(eapMethod.getEAPMethodID())) {
                return eapMethod.getEAPMethodID();
            }
        }
        return -1;
    }

    /**
     * Match the given EAPMethod against the authentication method of a provider.
     *
     * @param method The EAP Method
     * @param eapMethodID The EAP Method ID of the provider's credential
     * @param authParam The authentication parameter of the provider's credential
     * @return an integer indicating the match status
     */
    private static int matchEAPMethod(EAPMethod method, int eapMethodID, AuthParam authParam) {
        if (method.getEAPMethodID() != eapMethodID) {
            return AuthMatch.NONE;
        }
        // Check for authentication parameter match.
        if (authParam != null) {
            Map<Integer, Set<AuthParam>> authParams = method.getAuthParams();
            if (authParams.isEmpty()) {
                // no auth methods to match
                return AuthMatch.METHOD;
            }
            Set<AuthParam> paramSet = authParams.get(authParam.getAuthTypeID());
            if (paramSet == null || !paramSet.contains(authParam)) {
                return AuthMatch.NONE;
            }
            return AuthMatch.METHOD_PARAM;
        }
        return AuthMatch.METHOD;
    }

    /**
     * Match a cellular network information in the 3GPP Network ANQP element against the SIM
     * credential of a provider.
     *
     * @param network The cellular network that contained list of PLMNs
     * @param imsiParam IMSI parameter of the provider
     * @param simImsiList The list of IMSI from the installed SIM cards that matched provider's
     *                    IMSI parameter
     * @return true if a match is found
     */
    private static boolean matchCellularNetwork(CellularNetwork network, IMSIParameter imsiParam,
            List<String> simImsiList) {
        for (String plmn : network.getPlmns()) {
            if (matchMccMnc(plmn, imsiParam, simImsiList)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Match a MCC-MNC against the SIM credential of a provider.
     *
     * @param mccMnc The string containing MCC-MNC
     * @param imsiParam The IMSI parameter of the provider
     * @param simImsiList The list of IMSI from the installed SIM cards that matched provider's
     *                    IMSI parameter
     * @return true if a match is found
     */
    private static boolean matchMccMnc(String mccMnc, IMSIParameter imsiParam,
            List<String> simImsiList) {
        if (imsiParam == null || simImsiList == null) {
            return false;
        }
        // Match against the IMSI parameter in the provider.
        if (!imsiParam.matchesMccMnc(mccMnc)) {
            return false;
        }
        // Additional check for verifying the match with IMSIs from the SIM cards, since the IMSI
        // parameter might not contain the full 6-digit MCC MNC (e.g. IMSI parameter is an IMSI
        // prefix that contained less than 6-digit of numbers "12345*").
        for (String imsi : simImsiList) {
            if (imsi.startsWith(mccMnc)) {
                return true;
            }
        }
        return false;
    }
}
