/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wifi.hotspot2.omadm;

import android.annotation.NonNull;
import android.content.Context;
import android.net.wifi.EAPConstants;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.hotspot2.SystemInfo;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;

/**
 * Provides serialization API for DevDetail MO (Management Object).
 */
public class DevDetailMo {
    private static final String TAG = "DevDetailMo";
    // Refer to 9.2 DevDetail MO vendor specific extensions
    // in the Hotspot2.0 R2 Technical Specification document in detail
    @VisibleForTesting
    public static final String URN = "urn:oma:mo:oma-dm-devdetail:1.0";
    @VisibleForTesting
    public static final String HS20_URN = "urn:wfa:mo-ext:hotspot2dot0-devdetail-ext:1.0";

    private static final String MO_NAME = "DevDetail";

    private static final String TAG_EXT = "ext";
    private static final String TAG_ORG_WIFI = "org.wi-fi";
    private static final String TAG_WIFI = "Wi-Fi";
    private static final String TAG_EAP_METHOD_LIST = "EAPMethodList"; //Required field
    private static final String TAG_EAP_METHOD = "EAPMethod"; //Required field
    private static final String TAG_EAP_TYPE = "EAPType"; //Required field
    private static final String TAG_VENDOR_ID = "VendorId";
    private static final String TAG_VENDOR_TYPE = "VendorType";
    private static final String TAG_INNER_EAP_TYPE = "InnerEAPType";
    private static final String TAG_INNER_VENDOR_ID = "InnerVendorID";
    private static final String TAG_INNER_VENDOR_TYPE = "InnerVendorType";
    private static final String TAG_INNER_METHOD = "InnerMethod"; //Required field

    // Mobile device information related to certificates provisioned by SPs
    private static final String TAG_SP_CERTIFICATE = "SPCertificate";
    private static final String TAG_CERTIFICATE_ISSUER_NAME = "CertificateIssuerName";

    // Required if the mobile device is in possession of an IEEE 802.1ar-compliant
    // manufacturing certificate and is authorized to use that certificate for
    // mobile device AAA authentication
    private static final String TAG_MANUFACTURING_CERT = "ManufacturingCertificate";
    // Required for a device having a SIM, but will not provide the IMSI to an SP that
    // did not issue the IMSI.
    private static final String TAG_IMSI = "IMSI";
    // Required for the device having a SIM.
    private static final String TAG_IMEI_MEID = "IMEI_MEID";

    private static final String TAG_WIFI_MAC_ADDR = "Wi-FiMACAddress"; // Required field

    // Required field
    private static final String TAG_CLIENT_TRIGGER_REDIRECT_URI = "ClientTriggerRedirectURI";

    private static final String TAG_OPS = "Ops";
    private static final String TAG_LAUNCH_BROWSER_TO_URI = "launchBrowserToURI";
    private static final String TAG_NEGOTIATE_CLIENT_CERT_TLS = "negotiateClientCertTLS";
    private static final String TAG_GET_CERTIFICATE = "getCertificate";

    private static final List<String> sSupportedOps = new ArrayList<>();
    private static final String TAG_URI = "URI";
    private static final String TAG_MAX_DEPTH = "MaxDepth";
    private static final String TAG_MAX_TOT_LEN = "MaxTotLen";
    private static final String TAG_MAX_SEG_LEN = "MaxSegLen";
    private static final String TAG_OEM = "OEM";
    private static final String TAG_SW_VER = "SwV";
    private static final String TAG_LRG_ORJ = "LrgOrj";

    private static final String INNER_METHOD_PAP = "PAP";
    private static final String INNER_METHOD_MS_CHAP = "MS-CHAP";
    private static final String INNER_METHOD_MS_CHAP_V2 = "MS-CHAP-V2";

    private static final String IFNAME = "wlan0";

    private static final List<Pair<Integer, String>> sEapMethods = new ArrayList<>();
    static {
        sEapMethods.add(Pair.create(EAPConstants.EAP_TTLS, INNER_METHOD_MS_CHAP_V2));
        sEapMethods.add(Pair.create(EAPConstants.EAP_TTLS, INNER_METHOD_MS_CHAP));
        sEapMethods.add(Pair.create(EAPConstants.EAP_TTLS, INNER_METHOD_PAP));

        sEapMethods.add(Pair.create(EAPConstants.EAP_TLS, null));
        sEapMethods.add(Pair.create(EAPConstants.EAP_SIM, null));
        sEapMethods.add(Pair.create(EAPConstants.EAP_AKA, null));
        sEapMethods.add(Pair.create(EAPConstants.EAP_AKA_PRIME, null));

        sSupportedOps.add(TAG_LAUNCH_BROWSER_TO_URI);
    }

    // Whether to send IMSI and IMEI information or not during OSU provisioning flow; Mandatory (as
    // per standard) for mobile devices possessing a SIM card. However, it is unclear why this is
    // needed. Default to false due to privacy concerns.
    private static boolean sAllowToSendImsiImeiInfo = false;

    /**
     * Allow or disallow to send IMSI and IMEI information during OSU provisioning flow.
     *
     * @param allowToSendImsiImeiInfo flag to allow/disallow to send IMSI and IMEI.
     */
    @VisibleForTesting
    public static void setAllowToSendImsiImeiInfo(boolean allowToSendImsiImeiInfo) {
        sAllowToSendImsiImeiInfo = allowToSendImsiImeiInfo;
    }

    /**
     * Make a format of XML based on the DDF(Data Definition Format) of DevDetail MO.
     *
     * expected_output : refer to Figure 73: example sppPostDevData SOAP message in Hotspot 2.0
     * Rel 2.0 Specification document.
     * @param context {@link Context}
     * @param info {@link SystemInfo}
     * @param redirectUri redirect uri that server uses as completion of subscription.
     * @return the XML that has format of OMA DM DevDetail Management Object, <code>null</code> in
     * case of any failure.
     */
    public static String serializeToXml(@NonNull Context context, @NonNull SystemInfo info,
            @NonNull String redirectUri) {
        String macAddress = info.getMacAddress(IFNAME);
        if (macAddress != null) {
            macAddress = macAddress.replace(":", "");
        }
        if (TextUtils.isEmpty(macAddress)) {
            Log.e(TAG, "mac address is empty");
            return null;
        }
        MoSerializer moSerializer;
        try {
            moSerializer = new MoSerializer();
        } catch (ParserConfigurationException e) {
            Log.e(TAG, "failed to create the MoSerializer: " + e);
            return null;
        }

        // Create the XML document for DevInfoMo
        Document doc = moSerializer.createNewDocument();
        Element rootElement = moSerializer.createMgmtTree(doc);
        rootElement.appendChild(moSerializer.writeVersion(doc));
        // <Node><NodeName>DevDetail</NodeName>
        Element moNode = moSerializer.createNode(doc, MO_NAME);


        moNode.appendChild(moSerializer.createNodeForUrn(doc, URN));
        // <Node><NodeName>Ext</NodeName>
        Element extNode = moSerializer.createNode(doc, TAG_EXT);
        // <Node><NodeName>org.wi-fi</NodeName>
        Element orgNode = moSerializer.createNode(doc, TAG_ORG_WIFI);
        orgNode.appendChild(moSerializer.createNodeForUrn(doc, HS20_URN));
        // <Node><NodeName>Wi-Fi</NodeName>
        Element wifiNode = moSerializer.createNode(doc, TAG_WIFI);
        // <Node><NodeName>EAPMethodList</NodeName>
        Element eapMethodListNode = moSerializer.createNode(doc, TAG_EAP_METHOD_LIST);

        String tagName;
        Element eapMethodNode;

        int i = 0;
        for (Pair<Integer, String> entry : sEapMethods) {
            tagName = String.format("%s%02d", TAG_EAP_METHOD, ++i);
            eapMethodNode = moSerializer.createNode(doc, tagName);
            eapMethodNode.appendChild(
                    moSerializer.createNodeForValue(doc, TAG_EAP_TYPE, entry.first.toString()));
            if (entry.second != null) {
                eapMethodNode.appendChild(
                        moSerializer.createNodeForValue(doc, TAG_INNER_METHOD, entry.second));
            }
            eapMethodListNode.appendChild(eapMethodNode);

        }
        wifiNode.appendChild(eapMethodListNode); // TAG_EAP_METHOD_LIST

        wifiNode.appendChild(moSerializer.createNodeForValue(doc, TAG_MANUFACTURING_CERT, "FALSE"));
        wifiNode.appendChild(moSerializer.createNodeForValue(doc, TAG_CLIENT_TRIGGER_REDIRECT_URI,
                redirectUri));
        wifiNode.appendChild(moSerializer.createNodeForValue(doc, TAG_WIFI_MAC_ADDR, macAddress));

        // TODO(b/132188983): Inject this using WifiInjector
        TelephonyManager telephonyManager = context.getSystemService(TelephonyManager.class);
        String imsi = telephonyManager
                .createForSubscriptionId(SubscriptionManager.getDefaultDataSubscriptionId())
                .getSubscriberId();
        if (imsi != null && sAllowToSendImsiImeiInfo) {
            // Don't provide the IMSI to an SP that did not issue the IMSI
            wifiNode.appendChild(moSerializer.createNodeForValue(doc, TAG_IMSI, imsi));
            wifiNode.appendChild(
                    moSerializer.createNodeForValue(doc, TAG_IMEI_MEID, info.getDeviceId()));
        }

        // <Node><NodeName>Ops</NodeName>
        Element opsNode = moSerializer.createNode(doc, TAG_OPS);
        for (String op: sSupportedOps) {
            opsNode.appendChild(moSerializer.createNodeForValue(doc, op, ""));
        }
        wifiNode.appendChild(opsNode); // TAG_OPS
        orgNode.appendChild(wifiNode); // TAG_WIFI
        extNode.appendChild(orgNode); // TAG_ORG_WIFI
        moNode.appendChild(extNode); // TAG_EXT
        // <Node><NodeName>URI</NodeName>
        Element uriNode = moSerializer.createNode(doc, TAG_URI);

        uriNode.appendChild(moSerializer.createNodeForValue(doc, TAG_MAX_DEPTH, "32"));
        uriNode.appendChild(moSerializer.createNodeForValue(doc, TAG_MAX_TOT_LEN, "2048"));
        uriNode.appendChild(moSerializer.createNodeForValue(doc, TAG_MAX_SEG_LEN, "64"));
        moNode.appendChild(uriNode); // TAG_URI

        moNode.appendChild(
                moSerializer.createNodeForValue(doc, TAG_OEM, info.getDeviceManufacturer()));
        moNode.appendChild(
                moSerializer.createNodeForValue(doc, TAG_SW_VER, info.getSoftwareVersion()));
        moNode.appendChild(moSerializer.createNodeForValue(doc, TAG_LRG_ORJ, "TRUE"));
        rootElement.appendChild(moNode); // TAG_DEVDETAIL

        return moSerializer.serialize(doc);
    }
}
