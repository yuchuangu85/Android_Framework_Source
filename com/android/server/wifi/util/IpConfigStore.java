/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.wifi.util;

import android.annotation.Nullable;
import android.net.InetAddresses;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.StaticIpConfiguration;
import android.net.Uri;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Note: @hide class copied from com.android.server.net.
 */
public class IpConfigStore {
    private static final String TAG = "IpConfigStore";
    private static final boolean DBG = false;

    /* IP and proxy configuration keys */
    protected static final String ID_KEY = "id";
    protected static final String IP_ASSIGNMENT_KEY = "ipAssignment";
    protected static final String LINK_ADDRESS_KEY = "linkAddress";
    protected static final String GATEWAY_KEY = "gateway";
    protected static final String DNS_KEY = "dns";
    protected static final String PROXY_SETTINGS_KEY = "proxySettings";
    protected static final String PROXY_HOST_KEY = "proxyHost";
    protected static final String PROXY_PORT_KEY = "proxyPort";
    protected static final String PROXY_PAC_FILE = "proxyPac";
    protected static final String EXCLUSION_LIST_KEY = "exclusionList";
    protected static final String EOS = "eos";

    /**
     * Parses Ip configuration data from the bytestream provided.
     */
    public static SparseArray<IpConfiguration> readIpAndProxyConfigurations(
            InputStream inputStream) {
        ArrayMap<String, IpConfiguration> networks = readIpConfigurations(inputStream);
        if (networks == null) {
            return null;
        }

        SparseArray<IpConfiguration> networksById = new SparseArray<>();
        for (int i = 0; i < networks.size(); i++) {
            int id = Integer.valueOf(networks.keyAt(i));
            networksById.put(id, networks.valueAt(i));
        }

        return networksById;
    }

    /** Returns a map of network identity token and {@link IpConfiguration}. */
    public static ArrayMap<String, IpConfiguration> readIpConfigurations(
            InputStream inputStream) {
        ArrayMap<String, IpConfiguration> networks = new ArrayMap<>();
        DataInputStream in = null;
        try {
            in = new DataInputStream(inputStream);

            int version = in.readInt();
            if (version != 3 && version != 2 && version != 1) {
                loge("Bad version on IP configuration file, ignore read");
                return null;
            }

            while (true) {
                String uniqueToken = null;
                // Default is DHCP with no proxy
                IpAssignment ipAssignment = IpAssignment.DHCP;
                ProxySettings proxySettings = ProxySettings.NONE;
                StaticIpConfiguration.Builder staticIPBuilder = new StaticIpConfiguration.Builder();
                List<InetAddress> dnsServerAddresses = new ArrayList<>();
                String proxyHost = null;
                String pacFileUrl = null;
                int proxyPort = -1;
                String exclusionList = null;
                String key;

                do {
                    key = in.readUTF();
                    try {
                        if (key.equals(ID_KEY)) {
                            if (version < 3) {
                                int id = in.readInt();
                                uniqueToken = String.valueOf(id);
                            } else {
                                uniqueToken = in.readUTF();
                            }
                        } else if (key.equals(IP_ASSIGNMENT_KEY)) {
                            ipAssignment = IpAssignment.valueOf(in.readUTF());
                        } else if (key.equals(LINK_ADDRESS_KEY)) {
                            LinkAddress linkAddr = new LinkAddress(
                                    InetAddresses.parseNumericAddress(in.readUTF()), in.readInt());
                            if (linkAddr.getAddress() instanceof Inet4Address) {
                                staticIPBuilder.setIpAddress(linkAddr);
                            }
                        } else if (key.equals(GATEWAY_KEY)) {
                            LinkAddress dest = null;
                            InetAddress gateway = null;
                            if (version == 1) {
                                // only supported default gateways - leave the dest/prefix empty
                                gateway = InetAddresses.parseNumericAddress(in.readUTF());
                                staticIPBuilder.setGateway(gateway);
                            } else {
                                if (in.readInt() == 1) {
                                    dest = new LinkAddress(
                                            InetAddresses.parseNumericAddress(in.readUTF()),
                                            in.readInt());
                                }
                                if (in.readInt() == 1) {
                                    gateway = InetAddresses.parseNumericAddress(in.readUTF());
                                }
                                RouteInfo route = new RouteInfo(
                                        dest == null ? null : new IpPrefix(
                                                dest.getAddress(), dest.getPrefixLength()),
                                        gateway, null, RouteInfo.RTN_UNICAST);
                                if (route.isDefaultRoute()
                                        && route.getDestination().getAddress()
                                        instanceof Inet4Address) {
                                    staticIPBuilder.setGateway(gateway);
                                } else {
                                    loge("Non-IPv4 default or duplicate route: " + route);
                                }
                            }
                        } else if (key.equals(DNS_KEY)) {
                            dnsServerAddresses.add(InetAddresses.parseNumericAddress(in.readUTF()));
                        } else if (key.equals(PROXY_SETTINGS_KEY)) {
                            proxySettings = ProxySettings.valueOf(in.readUTF());
                        } else if (key.equals(PROXY_HOST_KEY)) {
                            proxyHost = in.readUTF();
                        } else if (key.equals(PROXY_PORT_KEY)) {
                            proxyPort = in.readInt();
                        } else if (key.equals(PROXY_PAC_FILE)) {
                            pacFileUrl = in.readUTF();
                        } else if (key.equals(EXCLUSION_LIST_KEY)) {
                            exclusionList = in.readUTF();
                        } else if (key.equals(EOS)) {
                            break;
                        } else {
                            loge("Ignore unknown key " + key + "while reading");
                        }
                    } catch (IllegalArgumentException e) {
                        loge("Ignore invalid address while reading" + e);
                    }
                } while (true);

                staticIPBuilder.setDnsServers(dnsServerAddresses);
                StaticIpConfiguration staticIpConfiguration = staticIPBuilder.build();
                if (uniqueToken != null) {
                    IpConfiguration config = new IpConfiguration();
                    networks.put(uniqueToken, config);

                    switch (ipAssignment) {
                        case STATIC:
                            config.setStaticIpConfiguration(staticIpConfiguration);
                            config.setIpAssignment(ipAssignment);
                            break;
                        case DHCP:
                            config.setIpAssignment(ipAssignment);
                            break;
                        case UNASSIGNED:
                            loge("BUG: Found UNASSIGNED IP on file, use DHCP");
                            config.setIpAssignment(IpAssignment.DHCP);
                            break;
                        default:
                            loge("Ignore invalid ip assignment while reading.");
                            config.setIpAssignment(IpAssignment.UNASSIGNED);
                            break;
                    }

                    switch (proxySettings) {
                        case STATIC:
                            ProxyInfo proxyInfo = ProxyInfo.buildDirectProxy(
                                    proxyHost, proxyPort,
                                    parseProxyExclusionListString(exclusionList));
                            config.setProxySettings(proxySettings);
                            config.setHttpProxy(proxyInfo);
                            break;
                        case PAC:
                            ProxyInfo proxyPacProperties =
                                    ProxyInfo.buildPacProxy(Uri.parse(pacFileUrl));
                            config.setProxySettings(proxySettings);
                            config.setHttpProxy(proxyPacProperties);
                            break;
                        case NONE:
                            config.setProxySettings(proxySettings);
                            break;
                        case UNASSIGNED:
                            loge("BUG: Found UNASSIGNED proxy on file, use NONE");
                            config.setProxySettings(ProxySettings.NONE);
                            break;
                        default:
                            loge("Ignore invalid proxy settings while reading");
                            config.setProxySettings(ProxySettings.UNASSIGNED);
                            break;
                    }
                } else {
                    if (DBG) log("Missing id while parsing configuration");
                }
            }
        } catch (EOFException ignore) {
        } catch (IOException e) {
            loge("Error parsing configuration: " + e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception e) { }
            }
        }

        return networks;
    }

    private static List<String> parseProxyExclusionListString(
            @Nullable String exclusionListString) {
        if (exclusionListString == null) {
            return Collections.emptyList();
        } else {
            return Arrays.asList(exclusionListString.toLowerCase(Locale.ROOT).split(","));
        }
    }

    protected static void loge(String s) {
        Log.e(TAG, s);
    }

    protected static void log(String s) {
        Log.d(TAG, s);
    }
}
