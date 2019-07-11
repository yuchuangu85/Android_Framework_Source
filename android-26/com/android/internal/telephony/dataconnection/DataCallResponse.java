/*
 * Copyright (C) 2009 Qualcomm Innovation Center, Inc.  All Rights Reserved.
 * Copyright (C) 2009 The Android Open Source Project
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

import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.os.SystemProperties;
import android.telephony.Rlog;
import android.text.TextUtils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * This is RIL_Data_Call_Response_v5 from ril.h
 */
public class DataCallResponse {
    private final boolean DBG = true;
    private final String LOG_TAG = "DataCallResponse";

    public final int status;
    public final int suggestedRetryTime;
    public final int cid;
    public final int active;
    public final String type;
    public final String ifname;
    public final String [] addresses;
    public final String [] dnses;
    // TODO: Change this to final if possible.
    public String[] gateways;
    public final String [] pcscf;
    public final int mtu;

    /**
     * Class returned by onSetupConnectionCompleted.
     */
    public enum SetupResult {
        SUCCESS,
        ERR_BadCommand,
        ERR_UnacceptableParameter,
        ERR_GetLastErrorFromRil,
        ERR_Stale,
        ERR_RilError;

        public DcFailCause mFailCause;

        SetupResult() {
            mFailCause = DcFailCause.fromInt(0);
        }

        @Override
        public String toString() {
            return name() + "  SetupResult.mFailCause=" + mFailCause;
        }
    }

    public DataCallResponse(int status, int suggestedRetryTime, int cid, int active, String type,
                            String ifname, String addresses, String dnses, String gateways,
                            String pcscf, int mtu) {
        this.status = status;
        this.suggestedRetryTime = suggestedRetryTime;
        this.cid = cid;
        this.active = active;
        this.type = (type == null) ? "" : type;
        this.ifname = (ifname == null) ? "" : ifname;
        if ((status == DcFailCause.NONE.getErrorCode()) && TextUtils.isEmpty(ifname)) {
            throw new RuntimeException("DataCallResponse, no ifname");
        }
        this.addresses = TextUtils.isEmpty(addresses) ? new String[0] : addresses.split(" ");
        this.dnses = TextUtils.isEmpty(dnses) ? new String[0] : dnses.split(" ");
        this.gateways = TextUtils.isEmpty(gateways) ? new String[0] : gateways.split(" ");
        this.pcscf = TextUtils.isEmpty(pcscf) ? new String[0] : pcscf.split(" ");
        this.mtu = mtu;
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("DataCallResponse: {")
           .append(" status=").append(status)
           .append(" retry=").append(suggestedRetryTime)
           .append(" cid=").append(cid)
           .append(" active=").append(active)
           .append(" type=").append(type)
           .append(" ifname=").append(ifname)
           .append(" mtu=").append(mtu)
           .append(" addresses=[");
        for (String addr : addresses) {
            sb.append(addr);
            sb.append(",");
        }
        if (addresses.length > 0) sb.deleteCharAt(sb.length()-1);
        sb.append("] dnses=[");
        for (String addr : dnses) {
            sb.append(addr);
            sb.append(",");
        }
        if (dnses.length > 0) sb.deleteCharAt(sb.length()-1);
        sb.append("] gateways=[");
        for (String addr : gateways) {
            sb.append(addr);
            sb.append(",");
        }
        if (gateways.length > 0) sb.deleteCharAt(sb.length()-1);
        sb.append("] pcscf=[");
        for (String addr : pcscf) {
            sb.append(addr);
            sb.append(",");
        }
        if (pcscf.length > 0) sb.deleteCharAt(sb.length()-1);
        sb.append("]}");
        return sb.toString();
    }

    public SetupResult setLinkProperties(LinkProperties linkProperties,
            boolean okToUseSystemPropertyDns) {
        SetupResult result;

        // Start with clean network properties and if we have
        // a failure we'll clear again at the bottom of this code.
        if (linkProperties == null)
            linkProperties = new LinkProperties();
        else
            linkProperties.clear();

        if (status == DcFailCause.NONE.getErrorCode()) {
            String propertyPrefix = "net." + ifname + ".";

            try {
                // set interface name
                linkProperties.setInterfaceName(ifname);

                // set link addresses
                if (addresses != null && addresses.length > 0) {
                    for (String addr : addresses) {
                        addr = addr.trim();
                        if (addr.isEmpty()) continue;
                        LinkAddress la;
                        int addrPrefixLen;

                        String [] ap = addr.split("/");
                        if (ap.length == 2) {
                            addr = ap[0];
                            addrPrefixLen = Integer.parseInt(ap[1]);
                        } else {
                            addrPrefixLen = 0;
                        }
                        InetAddress ia;
                        try {
                            ia = NetworkUtils.numericToInetAddress(addr);
                        } catch (IllegalArgumentException e) {
                            throw new UnknownHostException("Non-numeric ip addr=" + addr);
                        }
                        if (! ia.isAnyLocalAddress()) {
                            if (addrPrefixLen == 0) {
                                // Assume point to point
                                addrPrefixLen = (ia instanceof Inet4Address) ? 32 : 128;
                            }
                            if (DBG) Rlog.d(LOG_TAG, "addr/pl=" + addr + "/" + addrPrefixLen);
                            try {
                                la = new LinkAddress(ia, addrPrefixLen);
                            } catch (IllegalArgumentException e) {
                                throw new UnknownHostException("Bad parameter for LinkAddress, ia="
                                        + ia.getHostAddress() + "/" + addrPrefixLen);
                            }

                            linkProperties.addLinkAddress(la);
                        }
                    }
                } else {
                    throw new UnknownHostException("no address for ifname=" + ifname);
                }

                // set dns servers
                if (dnses != null && dnses.length > 0) {
                    for (String addr : dnses) {
                        addr = addr.trim();
                        if (addr.isEmpty()) continue;
                        InetAddress ia;
                        try {
                            ia = NetworkUtils.numericToInetAddress(addr);
                        } catch (IllegalArgumentException e) {
                            throw new UnknownHostException("Non-numeric dns addr=" + addr);
                        }
                        if (! ia.isAnyLocalAddress()) {
                            linkProperties.addDnsServer(ia);
                        }
                    }
                } else if (okToUseSystemPropertyDns){
                    String dnsServers[] = new String[2];
                    dnsServers[0] = SystemProperties.get(propertyPrefix + "dns1");
                    dnsServers[1] = SystemProperties.get(propertyPrefix + "dns2");
                    for (String dnsAddr : dnsServers) {
                        dnsAddr = dnsAddr.trim();
                        if (dnsAddr.isEmpty()) continue;
                        InetAddress ia;
                        try {
                            ia = NetworkUtils.numericToInetAddress(dnsAddr);
                        } catch (IllegalArgumentException e) {
                            throw new UnknownHostException("Non-numeric dns addr=" + dnsAddr);
                        }
                        if (! ia.isAnyLocalAddress()) {
                            linkProperties.addDnsServer(ia);
                        }
                    }
                } else {
                    throw new UnknownHostException("Empty dns response and no system default dns");
                }

                // set gateways
                if ((gateways == null) || (gateways.length == 0)) {
                    String sysGateways = SystemProperties.get(propertyPrefix + "gw");
                    if (sysGateways != null) {
                        gateways = sysGateways.split(" ");
                    } else {
                        gateways = new String[0];
                    }
                }
                for (String addr : gateways) {
                    addr = addr.trim();
                    if (addr.isEmpty()) continue;
                    InetAddress ia;
                    try {
                        ia = NetworkUtils.numericToInetAddress(addr);
                    } catch (IllegalArgumentException e) {
                        throw new UnknownHostException("Non-numeric gateway addr=" + addr);
                    }
                    // Allow 0.0.0.0 or :: as a gateway; this indicates a point-to-point interface.
                    linkProperties.addRoute(new RouteInfo(ia));
                }

                // set interface MTU
                // this may clobber the setting read from the APN db, but that's ok
                linkProperties.setMtu(mtu);

                result = SetupResult.SUCCESS;
            } catch (UnknownHostException e) {
                Rlog.d(LOG_TAG, "setLinkProperties: UnknownHostException " + e);
                e.printStackTrace();
                result = SetupResult.ERR_UnacceptableParameter;
            }
        } else {
            result = SetupResult.ERR_RilError;
        }

        // An error occurred so clear properties
        if (result != SetupResult.SUCCESS) {
            if(DBG) {
                Rlog.d(LOG_TAG, "setLinkProperties: error clearing LinkProperties " +
                        "status=" + status + " result=" + result);
            }
            linkProperties.clear();
        }

        return result;
    }
}
