/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.dataconnection;

import android.content.Context;
import android.hardware.radio.V1_0.ApnTypes;
import android.os.PersistableBundle;
import android.provider.Telephony.Carriers;
import android.telephony.CarrierConfigManager;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.uicc.IccRecords;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * This class represents a apn setting for create PDP link
 */
public class ApnSetting {

    static final String LOG_TAG = "ApnSetting";

    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    static final String V2_FORMAT_REGEX = "^\\[ApnSettingV2\\]\\s*";
    static final String V3_FORMAT_REGEX = "^\\[ApnSettingV3\\]\\s*";
    static final String V4_FORMAT_REGEX = "^\\[ApnSettingV4\\]\\s*";
    static final String V5_FORMAT_REGEX = "^\\[ApnSettingV5\\]\\s*";
    static final String TAG = "ApnSetting";

    public final String carrier;
    public final String apn;
    public final String proxy;
    public final String port;
    public final String mmsc;
    public final String mmsProxy;
    public final String mmsPort;
    public final String user;
    public final String password;
    public final int authType;
    public final String[] types;
    public final int typesBitmap;
    public final int id;
    public final String numeric;
    public final String protocol;
    public final String roamingProtocol;
    public final int mtu;

    /**
      * Current status of APN
      * true : enabled APN, false : disabled APN.
      */
    public final boolean carrierEnabled;
    /**
     * Radio Access Technology info
     * To check what values can hold, refer to ServiceState.java.
     * This should be spread to other technologies,
     * but currently only used for LTE(14) and EHRPD(13).
     *
     * @deprecated use {@code networkTypeBitmask} instead
     */
    @Deprecated
    private final int bearer;
    /**
      * Radio Access Technology info
      * To check what values can hold, refer to ServiceState.java. This is a bitmask of radio
      * technologies in ServiceState.
      * This should be spread to other technologies,
      * but currently only used for LTE(14) and EHRPD(13).
      *
      * @deprecated use {@code networkTypeBitmask} instead
      */
    @Deprecated
    public final int bearerBitmask;

    /**
     * Radio Technology (Network Type) info
     * To check what values can hold, refer to TelephonyManager.java. This is a bitmask of radio
     * technologies ({@code NETWORK_TYPE_} constants) in {@link TelephonyManager}.
     */
    public final int networkTypeBitmask;

    /* ID of the profile in the modem */
    public final int profileId;
    public final boolean modemCognitive;
    public final int maxConns;
    public final int waitTime;
    public final int maxConnsTime;

    /**
      * MVNO match type. Possible values:
      *   "spn": Service provider name.
      *   "imsi": IMSI.
      *   "gid": Group identifier level 1.
      *   "iccid": ICCID
      */
    public final String mvnoType;
    /**
      * MVNO data. Examples:
      *   "spn": A MOBILE, BEN NL
      *   "imsi": 302720x94, 2060188
      *   "gid": 4E, 33
      *   "iccid": 898603 etc.
      */
    public final String mvnoMatchData;

    /**
     * The APN set id.
     *
     * APNs that are part of the same set should be preferred together, e.g. if the
     * user selects a default APN with apnSetId=1, then we will prefer all APNs with apnSetId=1.
     *
     * If the apnSetId=Carriers.NO_SET_SET (=0) then the APN is not part of a set.
     */
    public final int apnSetId;

    /**
     * Indicates this APN setting is permanently failed and cannot be
     * retried by the retry manager anymore.
     * */
    public boolean permanentFailed = false;

    /**
     * @deprecated this constructor is no longer supported. Use the other constructor which takes
     * a network type bitmask instead of the deprecated bearer bitmask and bearer field.
     * */
    @Deprecated
    public ApnSetting(int id, String numeric, String carrier, String apn,
                      String proxy, String port,
                      String mmsc, String mmsProxy, String mmsPort,
                      String user, String password, int authType, String[] types,
                      String protocol, String roamingProtocol, boolean carrierEnabled, int bearer,
                      int bearerBitmask, int profileId, boolean modemCognitive, int maxConns,
                      int waitTime, int maxConnsTime, int mtu, String mvnoType,
                      String mvnoMatchData) {
        this.id = id;
        this.numeric = numeric;
        this.carrier = carrier;
        this.apn = apn;
        this.proxy = proxy;
        this.port = port;
        this.mmsc = mmsc;
        this.mmsProxy = mmsProxy;
        this.mmsPort = mmsPort;
        this.user = user;
        this.password = password;
        this.authType = authType;
        this.types = new String[types.length];
        int apnBitmap = 0;
        for (int i = 0; i < types.length; i++) {
            this.types[i] = types[i].toLowerCase();
            apnBitmap |= getApnBitmask(this.types[i]);
        }
        this.typesBitmap = apnBitmap;
        this.protocol = protocol;
        this.roamingProtocol = roamingProtocol;
        this.carrierEnabled = carrierEnabled;
        this.bearer = bearer;
        this.bearerBitmask = (bearerBitmask | ServiceState.getBitmaskForTech(bearer));
        this.profileId = profileId;
        this.modemCognitive = modemCognitive;
        this.maxConns = maxConns;
        this.waitTime = waitTime;
        this.maxConnsTime = maxConnsTime;
        this.mtu = mtu;
        this.mvnoType = mvnoType;
        this.mvnoMatchData = mvnoMatchData;
        this.apnSetId = Carriers.NO_SET_SET;
        this.networkTypeBitmask = ServiceState.convertBearerBitmaskToNetworkTypeBitmask(
                this.bearerBitmask);
    }

    // Constructor with default apn set id
    public ApnSetting(int id, String numeric, String carrier, String apn,
                      String proxy, String port,
                      String mmsc, String mmsProxy, String mmsPort,
                      String user, String password, int authType, String[] types,
                      String protocol, String roamingProtocol, boolean carrierEnabled,
                      int networkTypeBitmask, int profileId, boolean modemCognitive, int maxConns,
                      int waitTime, int maxConnsTime, int mtu, String mvnoType,
                      String mvnoMatchData) {
        this(id, numeric, carrier, apn, proxy, port, mmsc, mmsProxy, mmsPort, user, password,
                authType, types, protocol, roamingProtocol, carrierEnabled, networkTypeBitmask,
                profileId, modemCognitive, maxConns, waitTime, maxConnsTime, mtu, mvnoType,
                mvnoMatchData, Carriers.NO_SET_SET);
    }

    public ApnSetting(int id, String numeric, String carrier, String apn,
                      String proxy, String port,
                      String mmsc, String mmsProxy, String mmsPort,
                      String user, String password, int authType, String[] types,
                      String protocol, String roamingProtocol, boolean carrierEnabled,
                      int networkTypeBitmask, int profileId, boolean modemCognitive, int maxConns,
                      int waitTime, int maxConnsTime, int mtu, String mvnoType,
                      String mvnoMatchData, int apnSetId) {
        this.id = id;
        this.numeric = numeric;
        this.carrier = carrier;
        this.apn = apn;
        this.proxy = proxy;
        this.port = port;
        this.mmsc = mmsc;
        this.mmsProxy = mmsProxy;
        this.mmsPort = mmsPort;
        this.user = user;
        this.password = password;
        this.authType = authType;
        this.types = new String[types.length];
        int apnBitmap = 0;
        for (int i = 0; i < types.length; i++) {
            this.types[i] = types[i].toLowerCase();
            apnBitmap |= getApnBitmask(this.types[i]);
        }
        this.typesBitmap = apnBitmap;
        this.protocol = protocol;
        this.roamingProtocol = roamingProtocol;
        this.carrierEnabled = carrierEnabled;
        this.bearer = 0;
        this.bearerBitmask =
                ServiceState.convertNetworkTypeBitmaskToBearerBitmask(networkTypeBitmask);
        this.networkTypeBitmask = networkTypeBitmask;
        this.profileId = profileId;
        this.modemCognitive = modemCognitive;
        this.maxConns = maxConns;
        this.waitTime = waitTime;
        this.maxConnsTime = maxConnsTime;
        this.mtu = mtu;
        this.mvnoType = mvnoType;
        this.mvnoMatchData = mvnoMatchData;
        this.apnSetId = apnSetId;
    }

    public ApnSetting(ApnSetting apn) {
        this(apn.id, apn.numeric, apn.carrier, apn.apn, apn.proxy, apn.port, apn.mmsc, apn.mmsProxy,
                apn.mmsPort, apn.user, apn.password, apn.authType, apn.types, apn.protocol,
                apn.roamingProtocol, apn.carrierEnabled, apn.networkTypeBitmask, apn.profileId,
                apn.modemCognitive, apn.maxConns, apn.waitTime, apn.maxConnsTime,
                apn.mtu, apn.mvnoType, apn.mvnoMatchData, apn.apnSetId);
    }

    /**
     * Creates an ApnSetting object from a string.
     *
     * @param data the string to read.
     *
     * The string must be in one of two formats (newlines added for clarity,
     * spaces are optional):
     *
     * v1 format:
     *   <carrier>, <apn>, <proxy>, <port>, <user>, <password>, <server>,
     *   <mmsc>, <mmsproxy>, <mmsport>, <mcc>, <mnc>, <authtype>,
     *   <type>[| <type>...],
     *
     * v2 format:
     *   [ApnSettingV2] <carrier>, <apn>, <proxy>, <port>, <user>, <password>, <server>,
     *   <mmsc>, <mmsproxy>, <mmsport>, <mcc>, <mnc>, <authtype>,
     *   <type>[| <type>...], <protocol>, <roaming_protocol>, <carrierEnabled>, <bearerBitmask>,
     *
     * v3 format:
     *   [ApnSettingV3] <carrier>, <apn>, <proxy>, <port>, <user>, <password>, <server>,
     *   <mmsc>, <mmsproxy>, <mmsport>, <mcc>, <mnc>, <authtype>,
     *   <type>[| <type>...], <protocol>, <roaming_protocol>, <carrierEnabled>, <bearerBitmask>,
     *   <profileId>, <modemCognitive>, <maxConns>, <waitTime>, <maxConnsTime>, <mtu>,
     *   <mvnoType>, <mvnoMatchData>
     *
     * v4 format:
     *   [ApnSettingV4] <carrier>, <apn>, <proxy>, <port>, <user>, <password>, <server>,
     *   <mmsc>, <mmsproxy>, <mmsport>, <mcc>, <mnc>, <authtype>,
     *   <type>[| <type>...], <protocol>, <roaming_protocol>, <carrierEnabled>, <bearerBitmask>,
     *   <profileId>, <modemCognitive>, <maxConns>, <waitTime>, <maxConnsTime>, <mtu>,
     *   <mvnoType>, <mvnoMatchData>, <networkTypeBitmask>
     *
     * v5 format:
     *   [ApnSettingV5] <carrier>, <apn>, <proxy>, <port>, <user>, <password>, <server>,
     *   <mmsc>, <mmsproxy>, <mmsport>, <mcc>, <mnc>, <authtype>,
     *   <type>[| <type>...], <protocol>, <roaming_protocol>, <carrierEnabled>, <bearerBitmask>,
     *   <profileId>, <modemCognitive>, <maxConns>, <waitTime>, <maxConnsTime>, <mtu>,
     *   <mvnoType>, <mvnoMatchData>, <networkTypeBitmask>, <apnSetId>
     *
     * Note that the strings generated by toString() do not contain the username
     * and password and thus cannot be read by this method.
     */
    public static ApnSetting fromString(String data) {
        if (data == null) return null;

        int version;
        // matches() operates on the whole string, so append .* to the regex.
        if (data.matches(V5_FORMAT_REGEX + ".*")) {
            version = 5;
            data = data.replaceFirst(V5_FORMAT_REGEX, "");
        } else if (data.matches(V4_FORMAT_REGEX + ".*")) {
            version = 4;
            data = data.replaceFirst(V4_FORMAT_REGEX, "");
        } else if (data.matches(V3_FORMAT_REGEX + ".*")) {
            version = 3;
            data = data.replaceFirst(V3_FORMAT_REGEX, "");
        } else if (data.matches(V2_FORMAT_REGEX + ".*")) {
            version = 2;
            data = data.replaceFirst(V2_FORMAT_REGEX, "");
        } else {
            version = 1;
        }

        String[] a = data.split("\\s*,\\s*");
        if (a.length < 14) {
            return null;
        }

        int authType;
        try {
            authType = Integer.parseInt(a[12]);
        } catch (NumberFormatException e) {
            authType = 0;
        }

        String[] typeArray;
        String protocol, roamingProtocol;
        boolean carrierEnabled;
        int bearerBitmask = 0;
        int networkTypeBitmask = 0;
        int profileId = 0;
        boolean modemCognitive = false;
        int maxConns = 0;
        int waitTime = 0;
        int maxConnsTime = 0;
        int mtu = PhoneConstants.UNSET_MTU;
        String mvnoType = "";
        String mvnoMatchData = "";
        int apnSetId = Carriers.NO_SET_SET;
        if (version == 1) {
            typeArray = new String[a.length - 13];
            System.arraycopy(a, 13, typeArray, 0, a.length - 13);
            protocol = RILConstants.SETUP_DATA_PROTOCOL_IP;
            roamingProtocol = RILConstants.SETUP_DATA_PROTOCOL_IP;
            carrierEnabled = true;
        } else {
            if (a.length < 18) {
                return null;
            }
            typeArray = a[13].split("\\s*\\|\\s*");
            protocol = a[14];
            roamingProtocol = a[15];
            carrierEnabled = Boolean.parseBoolean(a[16]);

            bearerBitmask = ServiceState.getBitmaskFromString(a[17]);

            if (a.length > 22) {
                modemCognitive = Boolean.parseBoolean(a[19]);
                try {
                    profileId = Integer.parseInt(a[18]);
                    maxConns = Integer.parseInt(a[20]);
                    waitTime = Integer.parseInt(a[21]);
                    maxConnsTime = Integer.parseInt(a[22]);
                } catch (NumberFormatException e) {
                }
            }
            if (a.length > 23) {
                try {
                    mtu = Integer.parseInt(a[23]);
                } catch (NumberFormatException e) {
                }
            }
            if (a.length > 25) {
                mvnoType = a[24];
                mvnoMatchData = a[25];
            }
            if (a.length > 26) {
                networkTypeBitmask = ServiceState.getBitmaskFromString(a[26]);
            }
            if (a.length > 27) {
                apnSetId = Integer.parseInt(a[27]);
            }
        }

        // If both bearerBitmask and networkTypeBitmask were specified, bearerBitmask would be
        // ignored.
        if (networkTypeBitmask == 0) {
            networkTypeBitmask =
                    ServiceState.convertBearerBitmaskToNetworkTypeBitmask(bearerBitmask);
        }
        return new ApnSetting(-1, a[10] + a[11], a[0], a[1], a[2], a[3], a[7], a[8], a[9], a[4],
                a[5], authType, typeArray, protocol, roamingProtocol, carrierEnabled,
                networkTypeBitmask, profileId, modemCognitive, maxConns, waitTime, maxConnsTime,
                mtu, mvnoType, mvnoMatchData, apnSetId);
    }

    /**
     * Creates an array of ApnSetting objects from a string.
     *
     * @param data the string to read.
     *
     * Builds on top of the same format used by fromString, but allows for multiple entries
     * separated by "; ".
     */
    public static List<ApnSetting> arrayFromString(String data) {
        List<ApnSetting> retVal = new ArrayList<ApnSetting>();
        if (TextUtils.isEmpty(data)) {
            return retVal;
        }
        String[] apnStrings = data.split("\\s*;\\s*");
        for (String apnString : apnStrings) {
            ApnSetting apn = fromString(apnString);
            if (apn != null) {
                retVal.add(apn);
            }
        }
        return retVal;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[ApnSettingV5] ")
        .append(carrier)
        .append(", ").append(id)
        .append(", ").append(numeric)
        .append(", ").append(apn)
        .append(", ").append(proxy)
        .append(", ").append(mmsc)
        .append(", ").append(mmsProxy)
        .append(", ").append(mmsPort)
        .append(", ").append(port)
        .append(", ").append(authType).append(", ");
        for (int i = 0; i < types.length; i++) {
            sb.append(types[i]);
            if (i < types.length - 1) {
                sb.append(" | ");
            }
        }
        sb.append(", ").append(protocol);
        sb.append(", ").append(roamingProtocol);
        sb.append(", ").append(carrierEnabled);
        sb.append(", ").append(bearer);
        sb.append(", ").append(bearerBitmask);
        sb.append(", ").append(profileId);
        sb.append(", ").append(modemCognitive);
        sb.append(", ").append(maxConns);
        sb.append(", ").append(waitTime);
        sb.append(", ").append(maxConnsTime);
        sb.append(", ").append(mtu);
        sb.append(", ").append(mvnoType);
        sb.append(", ").append(mvnoMatchData);
        sb.append(", ").append(permanentFailed);
        sb.append(", ").append(networkTypeBitmask);
        sb.append(", ").append(apnSetId);
        return sb.toString();
    }

    /**
     * Returns true if there are MVNO params specified.
     */
    public boolean hasMvnoParams() {
        return !TextUtils.isEmpty(mvnoType) && !TextUtils.isEmpty(mvnoMatchData);
    }

    public boolean canHandleType(String type) {
        if (!carrierEnabled) return false;
        boolean wildcardable = true;
        if (PhoneConstants.APN_TYPE_IA.equalsIgnoreCase(type)) wildcardable = false;
        for (String t : types) {
            // DEFAULT handles all, and HIPRI is handled by DEFAULT
            if (t.equalsIgnoreCase(type) ||
                    (wildcardable && t.equalsIgnoreCase(PhoneConstants.APN_TYPE_ALL)) ||
                    (t.equalsIgnoreCase(PhoneConstants.APN_TYPE_DEFAULT) &&
                    type.equalsIgnoreCase(PhoneConstants.APN_TYPE_HIPRI))) {
                return true;
            }
        }
        return false;
    }

    private static boolean iccidMatches(String mvnoData, String iccId) {
        String[] mvnoIccidList = mvnoData.split(",");
        for (String mvnoIccid : mvnoIccidList) {
            if (iccId.startsWith(mvnoIccid)) {
                Log.d(TAG, "mvno icc id match found");
                return true;
            }
        }
        return false;
    }

    private static boolean imsiMatches(String imsiDB, String imsiSIM) {
        // Note: imsiDB value has digit number or 'x' character for seperating USIM information
        // for MVNO operator. And then digit number is matched at same order and 'x' character
        // could replace by any digit number.
        // ex) if imsiDB inserted '310260x10xxxxxx' for GG Operator,
        //     that means first 6 digits, 8th and 9th digit
        //     should be set in USIM for GG Operator.
        int len = imsiDB.length();
        int idxCompare = 0;

        if (len <= 0) return false;
        if (len > imsiSIM.length()) return false;

        for (int idx=0; idx<len; idx++) {
            char c = imsiDB.charAt(idx);
            if ((c == 'x') || (c == 'X') || (c == imsiSIM.charAt(idx))) {
                continue;
            } else {
                return false;
            }
        }
        return true;
    }

    public static boolean mvnoMatches(IccRecords r, String mvnoType, String mvnoMatchData) {
        if (mvnoType.equalsIgnoreCase("spn")) {
            if ((r.getServiceProviderName() != null) &&
                    r.getServiceProviderName().equalsIgnoreCase(mvnoMatchData)) {
                return true;
            }
        } else if (mvnoType.equalsIgnoreCase("imsi")) {
            String imsiSIM = r.getIMSI();
            if ((imsiSIM != null) && imsiMatches(mvnoMatchData, imsiSIM)) {
                return true;
            }
        } else if (mvnoType.equalsIgnoreCase("gid")) {
            String gid1 = r.getGid1();
            int mvno_match_data_length = mvnoMatchData.length();
            if ((gid1 != null) && (gid1.length() >= mvno_match_data_length) &&
                    gid1.substring(0, mvno_match_data_length).equalsIgnoreCase(mvnoMatchData)) {
                return true;
            }
        } else if (mvnoType.equalsIgnoreCase("iccid")) {
            String iccId = r.getIccId();
            if ((iccId != null) && iccidMatches(mvnoMatchData, iccId)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if this APN type is metered.
     *
     * @param type The APN type
     * @param phone The phone object
     * @return True if the APN type is metered, otherwise false.
     */
    public static boolean isMeteredApnType(String type, Phone phone) {
        if (phone == null) {
            return true;
        }

        boolean isRoaming = phone.getServiceState().getDataRoaming();
        boolean isIwlan = phone.getServiceState().getRilDataRadioTechnology()
                == ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN;
        int subId = phone.getSubId();

        String carrierConfig;
        // First check if the device is in IWLAN mode. If yes, use the IWLAN metered APN list. Then
        // check if the device is roaming. If yes, use the roaming metered APN list. Otherwise, use
        // the normal metered APN list.
        if (isIwlan) {
            carrierConfig = CarrierConfigManager.KEY_CARRIER_METERED_IWLAN_APN_TYPES_STRINGS;
        } else if (isRoaming) {
            carrierConfig = CarrierConfigManager.KEY_CARRIER_METERED_ROAMING_APN_TYPES_STRINGS;
        } else {
            carrierConfig = CarrierConfigManager.KEY_CARRIER_METERED_APN_TYPES_STRINGS;
        }

        if (DBG) {
            Rlog.d(LOG_TAG, "isMeteredApnType: isRoaming=" + isRoaming + ", isIwlan=" + isIwlan);
        }

        CarrierConfigManager configManager = (CarrierConfigManager)
                phone.getContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager == null) {
            Rlog.e(LOG_TAG, "Carrier config service is not available");
            return true;
        }

        PersistableBundle b = configManager.getConfigForSubId(subId);
        if (b == null) {
            Rlog.e(LOG_TAG, "Can't get the config. subId = " + subId);
            return true;
        }

        String[] meteredApnTypes = b.getStringArray(carrierConfig);
        if (meteredApnTypes == null) {
            Rlog.e(LOG_TAG, carrierConfig +  " is not available. " + "subId = " + subId);
            return true;
        }

        HashSet<String> meteredApnSet = new HashSet<>(Arrays.asList(meteredApnTypes));
        if (DBG) {
            Rlog.d(LOG_TAG, "For subId = " + subId + ", metered APN types are "
                    + Arrays.toString(meteredApnSet.toArray()));
        }

        // If all types of APN are metered, then this APN setting must be metered.
        if (meteredApnSet.contains(PhoneConstants.APN_TYPE_ALL)) {
            if (DBG) Rlog.d(LOG_TAG, "All APN types are metered.");
            return true;
        }

        if (meteredApnSet.contains(type)) {
            if (DBG) Rlog.d(LOG_TAG, type + " is metered.");
            return true;
        } else if (type.equals(PhoneConstants.APN_TYPE_ALL)) {
            // Assuming no configuration error, if at least one APN type is
            // metered, then this APN setting is metered.
            if (meteredApnSet.size() > 0) {
                if (DBG) Rlog.d(LOG_TAG, "APN_TYPE_ALL APN is metered.");
                return true;
            }
        }

        if (DBG) Rlog.d(LOG_TAG, type + " is not metered.");
        return false;
    }

    /**
     * Check if this APN setting is metered.
     *
     * @param phone The phone object
     * @return True if this APN setting is metered, otherwise false.
     */
    public boolean isMetered(Phone phone) {
        if (phone == null) {
            return true;
        }

        for (String type : types) {
            // If one of the APN type is metered, then this APN setting is metered.
            if (isMeteredApnType(type, phone)) {
                return true;
            }
        }
        return false;
    }

    // TODO - if we have this function we should also have hashCode.
    // Also should handle changes in type order and perhaps case-insensitivity
    @Override
    public boolean equals(Object o) {
        if (o instanceof ApnSetting == false) {
            return false;
        }

        ApnSetting other = (ApnSetting) o;

        return carrier.equals(other.carrier)
                && id == other.id
                && numeric.equals(other.numeric)
                && apn.equals(other.apn)
                && proxy.equals(other.proxy)
                && mmsc.equals(other.mmsc)
                && mmsProxy.equals(other.mmsProxy)
                && TextUtils.equals(mmsPort, other.mmsPort)
                && port.equals(other.port)
                && TextUtils.equals(user, other.user)
                && TextUtils.equals(password, other.password)
                && authType == other.authType
                && Arrays.deepEquals(types, other.types)
                && typesBitmap == other.typesBitmap
                && protocol.equals(other.protocol)
                && roamingProtocol.equals(other.roamingProtocol)
                && carrierEnabled == other.carrierEnabled
                && bearer == other.bearer
                && bearerBitmask == other.bearerBitmask
                && profileId == other.profileId
                && modemCognitive == other.modemCognitive
                && maxConns == other.maxConns
                && waitTime == other.waitTime
                && maxConnsTime == other.maxConnsTime
                && mtu == other.mtu
                && mvnoType.equals(other.mvnoType)
                && mvnoMatchData.equals(other.mvnoMatchData)
                && networkTypeBitmask == other.networkTypeBitmask
                && apnSetId == other.apnSetId;
    }

    /**
     * Compare two APN settings
     *
     * Note: This method does not compare 'id', 'bearer', 'bearerBitmask', 'networkTypeBitmask'.
     * We only use this for determining if tearing a data call is needed when conditions change. See
     * cleanUpConnectionsOnUpdatedApns in DcTracker.
     *
     * @param o the other object to compare
     * @param isDataRoaming True if the device is on data roaming
     * @return True if the two APN settings are same
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean equals(Object o, boolean isDataRoaming) {
        if (!(o instanceof ApnSetting)) {
            return false;
        }

        ApnSetting other = (ApnSetting) o;

        return carrier.equals(other.carrier)
                && numeric.equals(other.numeric)
                && apn.equals(other.apn)
                && proxy.equals(other.proxy)
                && mmsc.equals(other.mmsc)
                && mmsProxy.equals(other.mmsProxy)
                && TextUtils.equals(mmsPort, other.mmsPort)
                && port.equals(other.port)
                && TextUtils.equals(user, other.user)
                && TextUtils.equals(password, other.password)
                && authType == other.authType
                && Arrays.deepEquals(types, other.types)
                && typesBitmap == other.typesBitmap
                && (isDataRoaming || protocol.equals(other.protocol))
                && (!isDataRoaming || roamingProtocol.equals(other.roamingProtocol))
                && carrierEnabled == other.carrierEnabled
                && profileId == other.profileId
                && modemCognitive == other.modemCognitive
                && maxConns == other.maxConns
                && waitTime == other.waitTime
                && maxConnsTime == other.maxConnsTime
                && mtu == other.mtu
                && mvnoType.equals(other.mvnoType)
                && mvnoMatchData.equals(other.mvnoMatchData)
                && apnSetId == other.apnSetId;
    }

    /**
     * Check if neither mention DUN and are substantially similar
     *
     * @param other The other APN settings to compare
     * @return True if two APN settings are similar
     */
    public boolean similar(ApnSetting other) {
        return (!this.canHandleType(PhoneConstants.APN_TYPE_DUN)
                && !other.canHandleType(PhoneConstants.APN_TYPE_DUN)
                && Objects.equals(this.apn, other.apn)
                && !typeSameAny(this, other)
                && xorEquals(this.proxy, other.proxy)
                && xorEquals(this.port, other.port)
                && xorEquals(this.protocol, other.protocol)
                && xorEquals(this.roamingProtocol, other.roamingProtocol)
                && this.carrierEnabled == other.carrierEnabled
                && this.bearerBitmask == other.bearerBitmask
                && this.profileId == other.profileId
                && Objects.equals(this.mvnoType, other.mvnoType)
                && Objects.equals(this.mvnoMatchData, other.mvnoMatchData)
                && xorEquals(this.mmsc, other.mmsc)
                && xorEquals(this.mmsProxy, other.mmsProxy)
                && xorEquals(this.mmsPort, other.mmsPort))
                && this.networkTypeBitmask == other.networkTypeBitmask
                && this.apnSetId == other.apnSetId;
    }

    // check whether the types of two APN same (even only one type of each APN is same)
    private boolean typeSameAny(ApnSetting first, ApnSetting second) {
        if (VDBG) {
            StringBuilder apnType1 = new StringBuilder(first.apn + ": ");
            for (int index1 = 0; index1 < first.types.length; index1++) {
                apnType1.append(first.types[index1]);
                apnType1.append(",");
            }

            StringBuilder apnType2 = new StringBuilder(second.apn + ": ");
            for (int index1 = 0; index1 < second.types.length; index1++) {
                apnType2.append(second.types[index1]);
                apnType2.append(",");
            }
            Rlog.d(LOG_TAG, "APN1: is " + apnType1);
            Rlog.d(LOG_TAG, "APN2: is " + apnType2);
        }

        for (int index1 = 0; index1 < first.types.length; index1++) {
            for (int index2 = 0; index2 < second.types.length; index2++) {
                if (first.types[index1].equals(PhoneConstants.APN_TYPE_ALL)
                        || second.types[index2].equals(PhoneConstants.APN_TYPE_ALL)
                        || first.types[index1].equals(second.types[index2])) {
                    if (VDBG) Rlog.d(LOG_TAG, "typeSameAny: return true");
                    return true;
                }
            }
        }

        if (VDBG) Rlog.d(LOG_TAG, "typeSameAny: return false");
        return false;
    }

    // equal or one is not specified
    private boolean xorEquals(String first, String second) {
        return (Objects.equals(first, second)
                || TextUtils.isEmpty(first)
                || TextUtils.isEmpty(second));
    }

    // Helper function to convert APN string into a 32-bit bitmask.
    private static int getApnBitmask(String apn) {
        switch (apn) {
            case PhoneConstants.APN_TYPE_DEFAULT: return ApnTypes.DEFAULT;
            case PhoneConstants.APN_TYPE_MMS: return ApnTypes.MMS;
            case PhoneConstants.APN_TYPE_SUPL: return ApnTypes.SUPL;
            case PhoneConstants.APN_TYPE_DUN: return ApnTypes.DUN;
            case PhoneConstants.APN_TYPE_HIPRI: return ApnTypes.HIPRI;
            case PhoneConstants.APN_TYPE_FOTA: return ApnTypes.FOTA;
            case PhoneConstants.APN_TYPE_IMS: return ApnTypes.IMS;
            case PhoneConstants.APN_TYPE_CBS: return ApnTypes.CBS;
            case PhoneConstants.APN_TYPE_IA: return ApnTypes.IA;
            case PhoneConstants.APN_TYPE_EMERGENCY: return ApnTypes.EMERGENCY;
            case PhoneConstants.APN_TYPE_ALL: return ApnTypes.ALL;
            default: return ApnTypes.NONE;
        }
    }
}
