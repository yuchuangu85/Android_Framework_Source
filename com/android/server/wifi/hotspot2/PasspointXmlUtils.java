/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.net.wifi.hotspot2.pps.Policy;
import android.net.wifi.hotspot2.pps.UpdateParameter;

import com.android.internal.util.XmlUtils;
import com.android.server.wifi.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for serialize and deserialize Passpoint related configurations to/from XML string.
 */
public class PasspointXmlUtils {
    // XML section header tags.
    private static final String XML_TAG_SECTION_HEADER_HOMESP = "HomeSP";
    private static final String XML_TAG_SECTION_HEADER_CREDENTIAL = "Credential";
    private static final String XML_TAG_SECTION_HEADER_USER_CREDENTIAL = "UserCredential";
    private static final String XML_TAG_SECTION_HEADER_CERT_CREDENTIAL = "CertCredential";
    private static final String XML_TAG_SECTION_HEADER_SIM_CREDENTIAL = "SimCredential";
    private static final String XML_TAG_SECTION_HEADER_POLICY = "Policy";
    private static final String XML_TAG_SECTION_HEADER_PREFERRED_ROAMING_PARTNER_LIST =
            "RoamingPartnerList";
    private static final String XML_TAG_SECTION_HEADER_ROAMING_PARTNER = "RoamingPartner";
    private static final String XML_TAG_SECTION_HEADER_POLICY_UPDATE = "PolicyUpdate";
    private static final String XML_TAG_SECTION_HEADER_SUBSCRIPTION_UPDATE = "SubscriptionUpdate";
    private static final String XML_TAG_SECTION_HEADER_REQUIRED_PROTO_PORT_MAP =
            "RequiredProtoPortMap";
    private static final String XML_TAG_SECTION_HEADER_PROTO_PORT = "ProtoPort";

    // XML value tags.
    private static final String XML_TAG_FQDN = "FQDN";
    private static final String XML_TAG_FRIENDLY_NAME = "FriendlyName";
    private static final String XML_TAG_FRIENDLY_NAME_LIST = "FriendlyNameList";
    private static final String XML_TAG_ICON_URL = "IconURL";
    private static final String XML_TAG_HOME_NETWORK_IDS = "HomeNetworkIDs";
    private static final String XML_TAG_MATCH_ALL_OIS = "MatchAllOIs";
    private static final String XML_TAG_MATCH_ANY_OIS = "MatchAnyOIs";
    private static final String XML_TAG_OTHER_HOME_PARTNERS = "OtherHomePartners";
    private static final String XML_TAG_ROAMING_CONSORTIUM_OIS = "RoamingConsortiumOIs";
    private static final String XML_TAG_CREATION_TIME = "CreationTime";
    private static final String XML_TAG_EXPIRATION_TIME = "ExpirationTime";
    private static final String XML_TAG_REALM = "Realm";
    private static final String XML_TAG_CHECK_AAA_SERVER_CERT_STATUS = "CheckAAAServerCertStatus";
    private static final String XML_TAG_USERNAME = "Username";
    private static final String XML_TAG_PASSWORD = "Password";
    private static final String XML_TAG_MACHINE_MANAGED = "MachineManaged";
    private static final String XML_TAG_SOFT_TOKEN_APP = "SoftTokenApp";
    private static final String XML_TAG_ABLE_TO_SHARE = "AbleToShare";
    private static final String XML_TAG_EAP_TYPE = "EAPType";
    private static final String XML_TAG_NON_EAP_INNER_METHOD = "NonEAPInnerMethod";
    private static final String XML_TAG_CERT_TYPE = "CertType";
    private static final String XML_TAG_CERT_SHA256_FINGERPRINT = "CertSHA256Fingerprint";
    private static final String XML_TAG_IMSI = "IMSI";
    private static final String XML_TAG_MIN_HOME_DOWNLINK_BANDWIDTH = "MinHomeDownlinkBandwidth";
    private static final String XML_TAG_MIN_HOME_UPLINK_BANDWIDTH = "MinHomeUplinkBandwidth";
    private static final String XML_TAG_MIN_ROAMING_DOWNLINK_BANDWIDTH =
            "MinRoamingDownlinkBandwidth";
    private static final String XML_TAG_MIN_ROAMING_UPLINK_BANDWIDTH =
            "MinRoamingUplinkBandwidth";
    private static final String XML_TAG_EXCLUDED_SSID_LIST = "ExcludedSSIDList";
    private static final String XML_TAG_PROTO = "Proto";
    private static final String XML_TAG_PORTS = "Ports";
    private static final String XML_TAG_MAXIMUM_BSS_LOAD_VALUE = "MaximumBSSLoadValue";
    private static final String XML_TAG_FQDN_EXACT_MATCH = "FQDNExactMatch";
    private static final String XML_TAG_PRIORITY = "Priority";
    private static final String XML_TAG_COUNTRIES = "Countries";
    private static final String XML_TAG_UPDATE_INTERVAL = "UpdateInterval";
    private static final String XML_TAG_UPDATE_METHOD = "UpdateMethod";
    private static final String XML_TAG_RESTRICTION = "Restriction";
    private static final String XML_TAG_SERVER_URI = "ServerURI";
    private static final String XML_TAG_TRUST_ROOT_CERT_URL = "TrustRootCertURL";
    private static final String XML_TAG_TRUST_ROOT_CERT_SHA256_FINGERPRINT =
            "TrustRootCertSHA256Fingerprint";
    private static final String XML_TAG_TRUST_ROOT_CERT_LIST = "TrustRootCertList";
    private static final String XML_TAG_UPDATE_IDENTIFIER = "UpdateIdentifier";
    private static final String XML_TAG_CREDENTIAL_PRIORITY = "CredentialPriority";
    private static final String XML_TAG_SUBSCRIPTION_CREATION_TIME = "SubscriptionCreationTime";
    private static final String XML_TAG_SUBSCRIPTION_EXPIRATION_TIME =
            "SubscriptionExpirationTime";
    private static final String XML_TAG_SUBSCRIPTION_TYPE = "SubscriptionType";
    private static final String XML_TAG_USAGE_LIMIT_TIME_PERIOD = "UsageLimitTimePeriod";
    private static final String XML_TAG_USAGE_LIMIT_START_TIME = "UsageLimitStartTime";
    private static final String XML_TAG_USAGE_LIMIT_DATA_LIMIT = "UsageLimitDataLimit";
    private static final String XML_TAG_USAGE_LIMIT_TIME_LIMIT = "UsageLimitTimeLimit";

    /**
     * Serialize a {@link PasspointConfiguration} to the output stream as a XML block.
     *
     * @param out The output stream to serialize to
     * @param config The configuration to serialize
     * @throws XmlPullParserException
     * @throws IOException
     */
    public static void serializePasspointConfiguration(XmlSerializer out,
            PasspointConfiguration config) throws XmlPullParserException, IOException {
        XmlUtil.writeNextValue(out, XML_TAG_UPDATE_IDENTIFIER, config.getUpdateIdentifier());
        XmlUtil.writeNextValue(out, XML_TAG_CREDENTIAL_PRIORITY, config.getCredentialPriority());
        XmlUtil.writeNextValue(out, XML_TAG_TRUST_ROOT_CERT_LIST, config.getTrustRootCertList());
        XmlUtil.writeNextValue(out, XML_TAG_SUBSCRIPTION_CREATION_TIME,
                config.getSubscriptionCreationTimeInMillis());
        XmlUtil.writeNextValue(out, XML_TAG_SUBSCRIPTION_EXPIRATION_TIME,
                config.getSubscriptionExpirationTimeInMillis());
        XmlUtil.writeNextValue(out, XML_TAG_SUBSCRIPTION_TYPE, config.getSubscriptionType());
        XmlUtil.writeNextValue(out, XML_TAG_USAGE_LIMIT_TIME_PERIOD,
                config.getUsageLimitUsageTimePeriodInMinutes());
        XmlUtil.writeNextValue(out, XML_TAG_USAGE_LIMIT_START_TIME,
                config.getUsageLimitStartTimeInMillis());
        XmlUtil.writeNextValue(out, XML_TAG_USAGE_LIMIT_DATA_LIMIT,
                config.getUsageLimitDataLimit());
        XmlUtil.writeNextValue(out, XML_TAG_USAGE_LIMIT_TIME_LIMIT,
                config.getUsageLimitTimeLimitInMinutes());
        serializeHomeSp(out, config.getHomeSp());
        serializeCredential(out, config.getCredential());
        serializePolicy(out, config.getPolicy());
        serializeUpdateParameter(out, XML_TAG_SECTION_HEADER_SUBSCRIPTION_UPDATE,
                config.getSubscriptionUpdate());
        if (config.getServiceFriendlyNames() != null) {
            XmlUtil.writeNextValue(out, XML_TAG_FRIENDLY_NAME_LIST,
                    config.getServiceFriendlyNames());
        }
    }

    /**
     * Deserialize a {@link PasspointConfiguration} from an input stream containing XML block.
     *
     * @param in The input stream to read from
     * @param outerTagDepth The tag depth of the current XML section
     * @return {@link PasspointConfiguration}
     * @throws XmlPullParserException
     * @throws IOException
     */
    public static PasspointConfiguration deserializePasspointConfiguration(XmlPullParser in,
            int outerTagDepth) throws XmlPullParserException, IOException {
        PasspointConfiguration config = new PasspointConfiguration();
        while (XmlUtils.nextElementWithin(in, outerTagDepth)) {
            if (isValueElement(in)) {
                // Value elements.
                String[] name = new String[1];
                Object value = XmlUtil.readCurrentValue(in, name);
                switch (name[0]) {
                    case XML_TAG_UPDATE_IDENTIFIER:
                        config.setUpdateIdentifier((int) value);
                        break;
                    case XML_TAG_CREDENTIAL_PRIORITY:
                        config.setCredentialPriority((int) value);
                        break;
                    case XML_TAG_TRUST_ROOT_CERT_LIST:
                        config.setTrustRootCertList((Map<String, byte[]>) value);
                        break;
                    case XML_TAG_SUBSCRIPTION_CREATION_TIME:
                        config.setSubscriptionCreationTimeInMillis((long) value);
                        break;
                    case XML_TAG_SUBSCRIPTION_EXPIRATION_TIME:
                        config.setSubscriptionExpirationTimeInMillis((long) value);
                        break;
                    case XML_TAG_SUBSCRIPTION_TYPE:
                        config.setSubscriptionType((String) value);
                        break;
                    case XML_TAG_USAGE_LIMIT_TIME_PERIOD:
                        config.setUsageLimitUsageTimePeriodInMinutes((long) value);
                        break;
                    case XML_TAG_USAGE_LIMIT_START_TIME:
                        config.setUsageLimitStartTimeInMillis((long) value);
                        break;
                    case XML_TAG_USAGE_LIMIT_DATA_LIMIT:
                        config.setUsageLimitDataLimit((long) value);
                        break;
                    case XML_TAG_USAGE_LIMIT_TIME_LIMIT:
                        config.setUsageLimitTimeLimitInMinutes((long) value);
                        break;
                    case XML_TAG_FRIENDLY_NAME_LIST:
                        config.setServiceFriendlyNames((Map<String, String>) value);
                        break;
                    default:
                        throw new XmlPullParserException("Unknown value under "
                                + "PasspointConfiguration: " + in.getName());
                }
            } else {
                // Section elements.
                switch (in.getName()) {
                    case XML_TAG_SECTION_HEADER_HOMESP:
                        config.setHomeSp(deserializeHomeSP(in, outerTagDepth + 1));
                        break;
                    case XML_TAG_SECTION_HEADER_CREDENTIAL:
                        config.setCredential(deserializeCredential(in, outerTagDepth + 1));
                        break;
                    case XML_TAG_SECTION_HEADER_POLICY:
                        config.setPolicy(deserializePolicy(in, outerTagDepth + 1));
                        break;
                    case XML_TAG_SECTION_HEADER_SUBSCRIPTION_UPDATE:
                        config.setSubscriptionUpdate(
                                deserializeUpdateParameter(in, outerTagDepth + 1));
                        break;
                    default:
                        throw new XmlPullParserException("Unknown section under "
                                + "PasspointConfiguration: " + in.getName());
                }
            }
        }
        return config;
    }

    /**
     * Serialize a {@link HomeSp} to an output stream as a XML block.
     *
     * @param out The output stream to serialize data to
     * @param homeSp The {@link HomeSp} to serialize
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void serializeHomeSp(XmlSerializer out, HomeSp homeSp)
            throws XmlPullParserException, IOException {
        if (homeSp == null) {
            return;
        }
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_HOMESP);
        XmlUtil.writeNextValue(out, XML_TAG_FQDN, homeSp.getFqdn());
        XmlUtil.writeNextValue(out, XML_TAG_FRIENDLY_NAME, homeSp.getFriendlyName());
        XmlUtil.writeNextValue(out, XML_TAG_ICON_URL, homeSp.getIconUrl());
        XmlUtil.writeNextValue(out, XML_TAG_HOME_NETWORK_IDS, homeSp.getHomeNetworkIds());
        XmlUtil.writeNextValue(out, XML_TAG_MATCH_ALL_OIS, homeSp.getMatchAllOis());
        XmlUtil.writeNextValue(out, XML_TAG_MATCH_ANY_OIS, homeSp.getMatchAnyOis());
        XmlUtil.writeNextValue(out, XML_TAG_OTHER_HOME_PARTNERS, homeSp.getOtherHomePartners());
        XmlUtil.writeNextValue(out, XML_TAG_ROAMING_CONSORTIUM_OIS,
                homeSp.getRoamingConsortiumOis());
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_HOMESP);
    }

    /**
     * Serialize a {@link Credential} to an output stream as a XML block.
     *
     * @param out The output stream to serialize to
     * @param credential The {@link Credential} to serialize
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void serializeCredential(XmlSerializer out, Credential credential)
            throws XmlPullParserException, IOException {
        if (credential == null) {
            return;
        }
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_CREDENTIAL);
        XmlUtil.writeNextValue(out, XML_TAG_CREATION_TIME, credential.getCreationTimeInMillis());
        XmlUtil.writeNextValue(out, XML_TAG_EXPIRATION_TIME,
                credential.getExpirationTimeInMillis());
        XmlUtil.writeNextValue(out, XML_TAG_REALM, credential.getRealm());
        XmlUtil.writeNextValue(out, XML_TAG_CHECK_AAA_SERVER_CERT_STATUS,
                credential.getCheckAaaServerCertStatus());
        serializeUserCredential(out, credential.getUserCredential());
        serializeCertCredential(out, credential.getCertCredential());
        serializeSimCredential(out, credential.getSimCredential());
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_CREDENTIAL);
    }

    /**
     * Serialize a {@link Policy} to an output stream as a XML block.
     *
     * @param out The output stream to serialize to
     * @param policy The {@link Policy} to serialize
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void serializePolicy(XmlSerializer out, Policy policy)
            throws XmlPullParserException, IOException {
        if (policy == null) {
            return;
        }
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_POLICY);
        XmlUtil.writeNextValue(out, XML_TAG_MIN_HOME_DOWNLINK_BANDWIDTH,
                policy.getMinHomeDownlinkBandwidth());
        XmlUtil.writeNextValue(out, XML_TAG_MIN_HOME_UPLINK_BANDWIDTH,
                policy.getMinHomeUplinkBandwidth());
        XmlUtil.writeNextValue(out, XML_TAG_MIN_ROAMING_DOWNLINK_BANDWIDTH,
                policy.getMinRoamingDownlinkBandwidth());
        XmlUtil.writeNextValue(out, XML_TAG_MIN_ROAMING_UPLINK_BANDWIDTH,
                policy.getMinRoamingUplinkBandwidth());
        XmlUtil.writeNextValue(out, XML_TAG_EXCLUDED_SSID_LIST, policy.getExcludedSsidList());
        XmlUtil.writeNextValue(out, XML_TAG_MAXIMUM_BSS_LOAD_VALUE,
                policy.getMaximumBssLoadValue());
        serializeProtoPortMap(out, policy.getRequiredProtoPortMap());
        serializeUpdateParameter(out, XML_TAG_SECTION_HEADER_POLICY_UPDATE,
                policy.getPolicyUpdate());
        serializePreferredRoamingPartnerList(out, policy.getPreferredRoamingPartnerList());
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_POLICY);
    }

    /**
     * Serialize a {@link android.net.wifi.hotspot2.pps.Credential.UserCredential} to an output
     * stream as a XML block.
     *
     * @param out The output stream to serialize data to
     * @param userCredential The UserCredential to serialize
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void serializeUserCredential(XmlSerializer out,
            Credential.UserCredential userCredential) throws XmlPullParserException, IOException {
        if (userCredential == null) {
            return;
        }
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_USER_CREDENTIAL);
        XmlUtil.writeNextValue(out, XML_TAG_USERNAME, userCredential.getUsername());
        XmlUtil.writeNextValue(out, XML_TAG_PASSWORD, userCredential.getPassword());
        XmlUtil.writeNextValue(out, XML_TAG_MACHINE_MANAGED, userCredential.getMachineManaged());
        XmlUtil.writeNextValue(out, XML_TAG_SOFT_TOKEN_APP, userCredential.getSoftTokenApp());
        XmlUtil.writeNextValue(out, XML_TAG_ABLE_TO_SHARE, userCredential.getAbleToShare());
        XmlUtil.writeNextValue(out, XML_TAG_EAP_TYPE, userCredential.getEapType());
        XmlUtil.writeNextValue(out, XML_TAG_NON_EAP_INNER_METHOD,
                userCredential.getNonEapInnerMethod());
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_USER_CREDENTIAL);
    }

    /**
     * Serialize a {@link android.net.wifi.hotspot2.pps.Credential.CertificateCredential} to an
     * output stream as a XML block.
     *
     * @param out The output stream to serialize data to
     * @param certCredential The CertificateCredential to serialize
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void serializeCertCredential(XmlSerializer out,
            Credential.CertificateCredential certCredential)
                    throws XmlPullParserException, IOException {
        if (certCredential == null) {
            return;
        }
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_CERT_CREDENTIAL);
        XmlUtil.writeNextValue(out, XML_TAG_CERT_TYPE, certCredential.getCertType());
        XmlUtil.writeNextValue(out, XML_TAG_CERT_SHA256_FINGERPRINT,
                certCredential.getCertSha256Fingerprint());
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_CERT_CREDENTIAL);
    }

    /**
     * Serialize a {@link android.net.wifi.hotspot2.pps.Credential.SimCredential} to an
     * output stream as a XML block.
     *
     * @param out The output stream to serialize data to
     * @param simCredential The SimCredential to serialize
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void serializeSimCredential(XmlSerializer out,
            Credential.SimCredential simCredential) throws XmlPullParserException, IOException {
        if (simCredential == null) {
            return;
        }
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_SIM_CREDENTIAL);
        XmlUtil.writeNextValue(out, XML_TAG_IMSI, simCredential.getImsi());
        XmlUtil.writeNextValue(out, XML_TAG_EAP_TYPE, simCredential.getEapType());
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_SIM_CREDENTIAL);
    }

    /**
     * Serialize a preferred roaming partner list to an output stream as a XML block.
     *
     * @param out The output stream to serialize data to
     * @param preferredRoamingPartnerList The partner list to serialize
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void serializePreferredRoamingPartnerList(XmlSerializer out,
            List<Policy.RoamingPartner> preferredRoamingPartnerList)
                    throws XmlPullParserException, IOException {
        if (preferredRoamingPartnerList == null) {
            return;
        }
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_PREFERRED_ROAMING_PARTNER_LIST);
        for (Policy.RoamingPartner partner : preferredRoamingPartnerList) {
            XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_ROAMING_PARTNER);
            XmlUtil.writeNextValue(out, XML_TAG_FQDN, partner.getFqdn());
            XmlUtil.writeNextValue(out, XML_TAG_FQDN_EXACT_MATCH, partner.getFqdnExactMatch());
            XmlUtil.writeNextValue(out, XML_TAG_PRIORITY, partner.getPriority());
            XmlUtil.writeNextValue(out, XML_TAG_COUNTRIES, partner.getCountries());
            XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_ROAMING_PARTNER);
        }
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_PREFERRED_ROAMING_PARTNER_LIST);
    }

    /**
     * Serialize a {@link UpdateParameter} to an output stream as a XML block.  The
     * {@link UpdateParameter} are used for describing Subscription Update and Policy Update.
     *
     * @param out The output stream to serialize data to
     * @param type The type the {@link UpdateParameter} is used for
     * @param param The {@link UpdateParameter} to serialize
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void serializeUpdateParameter(XmlSerializer out, String type,
            UpdateParameter param) throws XmlPullParserException, IOException {
        if (param == null) {
            return;
        }
        XmlUtil.writeNextSectionStart(out, type);
        XmlUtil.writeNextValue(out, XML_TAG_UPDATE_INTERVAL, param.getUpdateIntervalInMinutes());
        XmlUtil.writeNextValue(out, XML_TAG_UPDATE_METHOD, param.getUpdateMethod());
        XmlUtil.writeNextValue(out, XML_TAG_RESTRICTION, param.getRestriction());
        XmlUtil.writeNextValue(out, XML_TAG_SERVER_URI, param.getServerUri());
        XmlUtil.writeNextValue(out, XML_TAG_USERNAME, param.getUsername());
        XmlUtil.writeNextValue(out, XML_TAG_PASSWORD, param.getBase64EncodedPassword());
        XmlUtil.writeNextValue(out, XML_TAG_TRUST_ROOT_CERT_URL, param.getTrustRootCertUrl());
        XmlUtil.writeNextValue(out, XML_TAG_TRUST_ROOT_CERT_SHA256_FINGERPRINT,
                param.getTrustRootCertSha256Fingerprint());
        XmlUtil.writeNextSectionEnd(out, type);
    }

    /**
     * Serialize a Protocol-to-Ports map to an output stream as a XML block.  We're not able
     * to use {@link XmlUtil#writeNextValue} to write this map, since that function only works for
     * maps with String key.
     *
     * @param out The output stream to serialize data to
     * @param protoPortMap The proto port map to serialize
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static void serializeProtoPortMap(XmlSerializer out, Map<Integer, String> protoPortMap)
            throws XmlPullParserException, IOException {
        if (protoPortMap == null) {
            return;
        }
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_REQUIRED_PROTO_PORT_MAP);
        for (Map.Entry<Integer, String> entry : protoPortMap.entrySet()) {
            XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_PROTO_PORT);
            XmlUtil.writeNextValue(out, XML_TAG_PROTO, entry.getKey());
            XmlUtil.writeNextValue(out, XML_TAG_PORTS, entry.getValue());
            XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_PROTO_PORT);
        }
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_REQUIRED_PROTO_PORT_MAP);
    }

    /**
     * Deserialize a {@link HomeSp} from an input stream.
     *
     * @param in The input stream to read data from
     * @param outerTagDepth The tag depth of the current XML section
     * @return {@link HomeSp}
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static HomeSp deserializeHomeSP(XmlPullParser in, int outerTagDepth)
            throws XmlPullParserException, IOException {
        HomeSp homeSp = new HomeSp();
        while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
            String[] valueName = new String[1];
            Object value = XmlUtil.readCurrentValue(in, valueName);
            if (valueName[0] == null) {
                throw new XmlPullParserException("Missing value name");
            }
            switch (valueName[0]) {
                case XML_TAG_FQDN:
                    homeSp.setFqdn((String) value);
                    break;
                case XML_TAG_FRIENDLY_NAME:
                    homeSp.setFriendlyName((String) value);
                    break;
                case XML_TAG_ICON_URL:
                    homeSp.setIconUrl((String) value);
                    break;
                case XML_TAG_HOME_NETWORK_IDS:
                    homeSp.setHomeNetworkIds((Map<String, Long>) value);
                    break;
                case XML_TAG_MATCH_ALL_OIS:
                    homeSp.setMatchAllOis((long[]) value);
                    break;
                case XML_TAG_MATCH_ANY_OIS:
                    homeSp.setMatchAnyOis((long[]) value);
                    break;
                case XML_TAG_ROAMING_CONSORTIUM_OIS:
                    homeSp.setRoamingConsortiumOis((long[]) value);
                    break;
                case XML_TAG_OTHER_HOME_PARTNERS:
                    homeSp.setOtherHomePartners((String[]) value);
                    break;
                default:
                    throw new XmlPullParserException("Unknown data under HomeSP: " + valueName[0]);
            }
        }
        return homeSp;
    }

    /**
     * Deserialize a {@link Credential} from an input stream.
     *
     * @param in The input stream to read data from
     * @param outerTagDepth The tag depth of the current XML section
     * @return {@link Credential}
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static Credential deserializeCredential(XmlPullParser in, int outerTagDepth)
            throws XmlPullParserException, IOException {
        Credential credential = new Credential();
        while (XmlUtils.nextElementWithin(in, outerTagDepth)) {
            if (isValueElement(in)) {
                // Value elements.
                String[] name = new String[1];
                Object value = XmlUtil.readCurrentValue(in, name);
                switch (name[0]) {
                    case XML_TAG_CREATION_TIME:
                        credential.setCreationTimeInMillis((long) value);
                        break;
                    case XML_TAG_EXPIRATION_TIME:
                        credential.setExpirationTimeInMillis((long) value);
                        break;
                    case XML_TAG_REALM:
                        credential.setRealm((String) value);
                        break;
                    case XML_TAG_CHECK_AAA_SERVER_CERT_STATUS:
                        credential.setCheckAaaServerCertStatus((boolean) value);
                        break;
                    default:
                        throw new XmlPullParserException("Unknown value under Credential: "
                            + name[0]);
                }
            } else {
                // Subsection elements.
                switch (in.getName()) {
                    case XML_TAG_SECTION_HEADER_USER_CREDENTIAL:
                        credential.setUserCredential(
                                deserializeUserCredential(in, outerTagDepth + 1));
                        break;
                    case XML_TAG_SECTION_HEADER_CERT_CREDENTIAL:
                        credential.setCertCredential(
                                deserializeCertCredential(in, outerTagDepth + 1));
                        break;
                    case XML_TAG_SECTION_HEADER_SIM_CREDENTIAL:
                        credential.setSimCredential(
                                deserializeSimCredential(in, outerTagDepth + 1));
                        break;
                    default:
                        throw new XmlPullParserException("Unknown section under Credential: "
                                + in.getName());
                }
            }
        }
        return credential;
    }

    /**
     * Deserialize a {@link Policy} from an input stream.
     *
     * @param in The input stream to read data from
     * @param outerTagDepth The tag depth of the current XML section
     * @return {@link Policy}
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static Policy deserializePolicy(XmlPullParser in, int outerTagDepth)
            throws XmlPullParserException, IOException {
        Policy policy = new Policy();
        while (XmlUtils.nextElementWithin(in, outerTagDepth)) {
            if (isValueElement(in)) {
                // Value elements.
                String[] name = new String[1];
                Object value = XmlUtil.readCurrentValue(in, name);
                switch (name[0]) {
                    case XML_TAG_MIN_HOME_DOWNLINK_BANDWIDTH:
                        policy.setMinHomeDownlinkBandwidth((long) value);
                        break;
                    case XML_TAG_MIN_HOME_UPLINK_BANDWIDTH:
                        policy.setMinHomeUplinkBandwidth((long) value);
                        break;
                    case XML_TAG_MIN_ROAMING_DOWNLINK_BANDWIDTH:
                        policy.setMinRoamingDownlinkBandwidth((long) value);
                        break;
                    case XML_TAG_MIN_ROAMING_UPLINK_BANDWIDTH:
                        policy.setMinRoamingUplinkBandwidth((long) value);
                        break;
                    case XML_TAG_EXCLUDED_SSID_LIST:
                        policy.setExcludedSsidList((String[]) value);
                        break;
                    case XML_TAG_MAXIMUM_BSS_LOAD_VALUE:
                        policy.setMaximumBssLoadValue((int) value);
                        break;
                }
            } else {
                // Subsection elements.
                switch (in.getName()) {
                    case XML_TAG_SECTION_HEADER_REQUIRED_PROTO_PORT_MAP:
                        policy.setRequiredProtoPortMap(
                                deserializeProtoPortMap(in, outerTagDepth + 1));
                        break;
                    case XML_TAG_SECTION_HEADER_POLICY_UPDATE:
                        policy.setPolicyUpdate(deserializeUpdateParameter(in, outerTagDepth + 1));
                        break;
                    case XML_TAG_SECTION_HEADER_PREFERRED_ROAMING_PARTNER_LIST:
                        policy.setPreferredRoamingPartnerList(
                                deserializePreferredRoamingPartnerList(in, outerTagDepth + 1));
                        break;
                    default:
                        throw new XmlPullParserException("Unknown section under Policy: "
                                + in.getName());
                }
            }
        }
        return policy;
    }

    /**
     * Deserialize a {@link android.net.wifi.hotspot2.pps.Credential.UserCredential} from an
     * input stream.
     *
     * @param in The input stream to read data from
     * @param outerTagDepth The tag depth of the current XML section
     * @return {@link android.net.wifi.hotspot2.pps.Credential.UserCredential}
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static Credential.UserCredential deserializeUserCredential(XmlPullParser in,
            int outerTagDepth) throws XmlPullParserException, IOException {
        Credential.UserCredential userCredential = new Credential.UserCredential();
        while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
            String[] valueName = new String[1];
            Object value = XmlUtil.readCurrentValue(in, valueName);
            if (valueName[0] == null) {
                throw new XmlPullParserException("Missing value name");
            }
            switch (valueName[0]) {
                case XML_TAG_USERNAME:
                    userCredential.setUsername((String) value);
                    break;
                case XML_TAG_PASSWORD:
                    userCredential.setPassword((String) value);
                    break;
                case XML_TAG_MACHINE_MANAGED:
                    userCredential.setMachineManaged((boolean) value);
                    break;
                case XML_TAG_SOFT_TOKEN_APP:
                    userCredential.setSoftTokenApp((String) value);
                    break;
                case XML_TAG_ABLE_TO_SHARE:
                    userCredential.setAbleToShare((boolean) value);
                    break;
                case XML_TAG_EAP_TYPE:
                    userCredential.setEapType((int) value);
                    break;
                case XML_TAG_NON_EAP_INNER_METHOD:
                    userCredential.setNonEapInnerMethod((String) value);
                    break;
                default:
                    throw new XmlPullParserException("Unknown value under UserCredential: "
                            + valueName[0]);
            }
        }
        return userCredential;
    }

    /**
     * Deserialize a {@link android.net.wifi.hotspot2.pps.Credential.CertificateCredential}
     * from an input stream.
     *
     * @param in The input stream to read data from
     * @param outerTagDepth The tag depth of the current XML section
     * @return {@link android.net.wifi.hotspot2.pps.Credential.CertificateCredential}
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static Credential.CertificateCredential deserializeCertCredential(XmlPullParser in,
            int outerTagDepth) throws XmlPullParserException, IOException {
        Credential.CertificateCredential certCredential = new Credential.CertificateCredential();
        while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
            String[] valueName = new String[1];
            Object value = XmlUtil.readCurrentValue(in, valueName);
            if (valueName[0] == null) {
                throw new XmlPullParserException("Missing value name");
            }
            switch (valueName[0]) {
                case XML_TAG_CERT_TYPE:
                    certCredential.setCertType((String) value);
                    break;
                case XML_TAG_CERT_SHA256_FINGERPRINT:
                    certCredential.setCertSha256Fingerprint((byte[]) value);
                    break;
                default:
                    throw new XmlPullParserException("Unknown value under CertCredential: "
                            + valueName[0]);
            }
        }
        return certCredential;
    }

    /**
     * Deserialize a {@link android.net.wifi.hotspot2.pps.Credential.SimCredential} from an
     * input stream.
     *
     * @param in The input stream to read data from
     * @param outerTagDepth The tag depth of the current XML section
     * @return {@link android.net.wifi.hotspot2.pps.Credential.SimCredential}
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static Credential.SimCredential deserializeSimCredential(XmlPullParser in,
            int outerTagDepth) throws XmlPullParserException, IOException {
        Credential.SimCredential simCredential = new Credential.SimCredential();
        while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
            String[] valueName = new String[1];
            Object value = XmlUtil.readCurrentValue(in, valueName);
            if (valueName[0] == null) {
                throw new XmlPullParserException("Missing value name");
            }
            switch (valueName[0]) {
                case XML_TAG_IMSI:
                    simCredential.setImsi((String) value);
                    break;
                case XML_TAG_EAP_TYPE:
                    simCredential.setEapType((int) value);
                    break;
                default:
                    throw new XmlPullParserException("Unknown value under CertCredential: "
                            + valueName[0]);
            }
        }
        return simCredential;
    }

    /**
     * Deserialize a list of {@link android.net.wifi.hotspot2.pps.Policy.RoamingPartner} from an
     * input stream.
     *
     * @param in The input stream to read data from
     * @param outerTagDepth The tag depth of the current XML section
     * @return List of {@link android.net.wifi.hotspot2.pps.Policy.RoamingPartner}
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static List<Policy.RoamingPartner> deserializePreferredRoamingPartnerList(
            XmlPullParser in, int outerTagDepth) throws XmlPullParserException, IOException {
        List<Policy.RoamingPartner> roamingPartnerList = new ArrayList<>();
        while (XmlUtil.gotoNextSectionWithNameOrEnd(in, XML_TAG_SECTION_HEADER_ROAMING_PARTNER,
                outerTagDepth)) {
            roamingPartnerList.add(deserializeRoamingPartner(in, outerTagDepth + 1));
        }
        return roamingPartnerList;
    }

    /**
     * Deserialize a {@link android.net.wifi.hotspot2.pps.Policy.RoamingPartner} from an input
     * stream.
     *
     * @param in The input stream to read data from
     * @param outerTagDepth The tag depth of the current XML section
     * @return {@link android.net.wifi.hotspot2.pps.Policy.RoamingPartner}
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static Policy.RoamingPartner deserializeRoamingPartner(XmlPullParser in,
            int outerTagDepth) throws XmlPullParserException, IOException {
        Policy.RoamingPartner partner = new Policy.RoamingPartner();
        while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
            String[] valueName = new String[1];
            Object value = XmlUtil.readCurrentValue(in, valueName);
            if (valueName[0] == null) {
                throw new XmlPullParserException("Missing value name");
            }
            switch (valueName[0]) {
                case XML_TAG_FQDN:
                    partner.setFqdn((String) value);
                    break;
                case XML_TAG_FQDN_EXACT_MATCH:
                    partner.setFqdnExactMatch((boolean) value);
                    break;
                case XML_TAG_PRIORITY:
                    partner.setPriority((int) value);
                    break;
                case XML_TAG_COUNTRIES:
                    partner.setCountries((String) value);
                    break;
                default:
                    throw new XmlPullParserException("Unknown value under RoamingPartner: "
                            + valueName[0]);
            }
        }
        return partner;
    }

    /**
     * Deserialize a {@link UpdateParameter} from an input stream.
     *
     * @param in The input stream to read data from
     * @param outerTagDepth The tag depth of the current XML section
     * @return {@link UpdateParameter}
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static UpdateParameter deserializeUpdateParameter(XmlPullParser in,
            int outerTagDepth) throws XmlPullParserException, IOException {
        UpdateParameter param = new UpdateParameter();
        while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
            String[] valueName = new String[1];
            Object value = XmlUtil.readCurrentValue(in, valueName);
            if (valueName[0] == null) {
                throw new XmlPullParserException("Missing value name");
            }
            switch (valueName[0]) {
                case XML_TAG_UPDATE_INTERVAL:
                    param.setUpdateIntervalInMinutes((long) value);
                    break;
                case XML_TAG_UPDATE_METHOD:
                    param.setUpdateMethod((String) value);
                    break;
                case XML_TAG_RESTRICTION:
                    param.setRestriction((String) value);
                    break;
                case XML_TAG_SERVER_URI:
                    param.setServerUri((String) value);
                    break;
                case XML_TAG_USERNAME:
                    param.setUsername((String) value);
                    break;
                case XML_TAG_PASSWORD:
                    param.setBase64EncodedPassword((String) value);
                    break;
                case XML_TAG_TRUST_ROOT_CERT_URL:
                    param.setTrustRootCertUrl((String) value);
                    break;
                case XML_TAG_TRUST_ROOT_CERT_SHA256_FINGERPRINT:
                    param.setTrustRootCertSha256Fingerprint((byte[]) value);
                    break;
                default:
                    throw new XmlPullParserException("Unknown value under UpdateParameter: "
                            + valueName[0]);
            }
        }
        return param;
    }

    /**
     * Deserialize a Protocol-Port map from an input stream.
     *
     * @param in The input stream to read data from
     * @param outerTagDepth The tag depth of the current XML section
     * @return Proocol-Port map
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static Map<Integer, String> deserializeProtoPortMap(XmlPullParser in,
            int outerTagDepth) throws XmlPullParserException, IOException {
        Map<Integer, String> protoPortMap = new HashMap<>();
        while (XmlUtil.gotoNextSectionWithNameOrEnd(in, XML_TAG_SECTION_HEADER_PROTO_PORT,
                outerTagDepth)) {
            int proto = (int) XmlUtil.readNextValueWithName(in, XML_TAG_PROTO);
            String ports = (String) XmlUtil.readNextValueWithName(in, XML_TAG_PORTS);
            protoPortMap.put(proto, ports);
        }
        return protoPortMap;
    }

    /**
     * Determine if the current element is a value or a section.  The "name" attribute of the
     * element is used as the indicator, when it is present, the element is considered a value
     * element.
     *
     * Value element:
     * <int name="test">12</int>
     *
     * Section element:
     * <Test>
     * ...
     * </Test>
     *
     * @param in XML input stream
     * @return true if the current element is a value
     */
    private static boolean isValueElement(XmlPullParser in) {
        return in.getAttributeValue(null, "name") != null;
    }
}
