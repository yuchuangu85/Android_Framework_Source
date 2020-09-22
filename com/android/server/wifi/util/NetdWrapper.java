/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.Context;
import android.net.INetd;
import android.net.INetdUnsolicitedEventListener;
import android.net.InetAddresses;
import android.net.InterfaceConfiguration;
import android.net.InterfaceConfigurationParcel;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.RouteInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.text.TextUtils;
import android.util.Log;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This is a simple wrapper over INetd calls used by wifi stack.
 *
 * Based on {@link com.android.server.NetworkManagementService}
 */
public class NetdWrapper {
    private static final String TAG = "NetdWrapper";
    static final boolean MODIFY_OPERATION_ADD = true;
    static final boolean MODIFY_OPERATION_REMOVE = false;

    private final INetd mNetdService;
    private final NetdUnsolicitedEventListener mNetdUnsolicitedEventListener;
    private final Handler mHandler;
    private final Set<NetdEventObserver> mObservers = new HashSet<>();

    /**
     * Observer for iface events.
     */
    public interface NetdEventObserver {
        /**
         * Interface configuration status has changed.
         *
         * @param iface The interface.
         * @param up True if the interface has been enabled.
         */
        void interfaceStatusChanged(String iface, boolean up);
        /**
         * Interface physical-layer link state has changed.  For Ethernet,
         * this method is invoked when the cable is plugged in or unplugged.
         *
         * @param iface The interface.
         * @param up  True if the physical link-layer connection signal is valid.
         */
        void interfaceLinkStateChanged(String iface, boolean up);
    }

    private class NetdUnsolicitedEventListener extends INetdUnsolicitedEventListener.Stub {
        @Override
        public void onInterfaceClassActivityChanged(boolean isActive,
                int label, long timestamp, int uid) throws RemoteException {
            // Unused.
        }

        @Override
        public void onQuotaLimitReached(String alertName, String ifName)
                throws RemoteException {
            // Unused.
        }

        @Override
        public void onInterfaceDnsServerInfo(String ifName,
                long lifetime, String[] servers) throws RemoteException {
            // Unused.
        }

        @Override
        public void onInterfaceAddressUpdated(String addr,
                String ifName, int flags, int scope) throws RemoteException {
            // Unused.
        }

        @Override
        public void onInterfaceAddressRemoved(String addr,
                String ifName, int flags, int scope) throws RemoteException {
            // Unused.
        }

        @Override
        public void onInterfaceAdded(String ifName) throws RemoteException {
            // Unused.
        }

        @Override
        public void onInterfaceRemoved(String ifName) throws RemoteException {
            // Unused.
        }

        @Override
        public void onInterfaceChanged(String ifName, boolean up)
                throws RemoteException {
            mHandler.post(() -> notifyInterfaceStatusChanged(ifName, up));
        }

        @Override
        public void onInterfaceLinkStateChanged(String ifName, boolean up)
                throws RemoteException {
            mHandler.post(() -> notifyInterfaceLinkStateChanged(ifName, up));
        }

        @Override
        public void onRouteChanged(boolean updated,
                String route, String gateway, String ifName) throws RemoteException {
            // Unused.
        }

        @Override
        public void onStrictCleartextDetected(int uid, String hex) throws RemoteException {
            // Unused.
        }

        @Override
        public int getInterfaceVersion() {
            return INetdUnsolicitedEventListener.VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return INetdUnsolicitedEventListener.HASH;
        }
    }

    public NetdWrapper(Context context, Handler handler) {
        mNetdService = INetd.Stub.asInterface(
                (IBinder) context.getSystemService(Context.NETD_SERVICE));
        mNetdUnsolicitedEventListener = new NetdUnsolicitedEventListener();
        mHandler = handler;

        try {
            mNetdService.registerUnsolicitedEventListener(mNetdUnsolicitedEventListener);
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "Failed to set Netd unsolicited event listener " + e);
        }
    }

    private void modifyInterfaceInNetwork(boolean add, int netId, String iface) {
        try {
            if (add) {
                mNetdService.networkAddInterface(netId, iface);
            } else {
                mNetdService.networkRemoveInterface(netId, iface);
            }
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    private void modifyRoute(boolean add, int netId, RouteInfo route) {
        final String ifName = route.getInterface();
        final String dst = route.getDestination().toString();
        final String nextHop;

        switch (route.getType()) {
            case RouteInfo.RTN_UNICAST:
                if (route.hasGateway()) {
                    nextHop = route.getGateway().getHostAddress();
                } else {
                    nextHop = INetd.NEXTHOP_NONE;
                }
                break;
            case RouteInfo.RTN_UNREACHABLE:
                nextHop = INetd.NEXTHOP_UNREACHABLE;
                break;
            case RouteInfo.RTN_THROW:
                nextHop = INetd.NEXTHOP_THROW;
                break;
            default:
                nextHop = INetd.NEXTHOP_NONE;
                break;
        }
        try {
            if (add) {
                mNetdService.networkAddRoute(netId, ifName, dst, nextHop);
            } else {
                mNetdService.networkRemoveRoute(netId, ifName, dst, nextHop);
            }
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Add iface to local network.
     */
    public void addInterfaceToLocalNetwork(String iface, List<RouteInfo> routes) {
        modifyInterfaceInNetwork(MODIFY_OPERATION_ADD, INetd.LOCAL_NET_ID, iface);

        for (RouteInfo route : routes) {
            if (!route.isDefaultRoute()) {
                modifyRoute(MODIFY_OPERATION_ADD, INetd.LOCAL_NET_ID, route);
            }
        }

        // IPv6 link local should be activated always.
        modifyRoute(MODIFY_OPERATION_ADD, INetd.LOCAL_NET_ID,
                new RouteInfo(new IpPrefix("fe80::/64"), null, iface, RouteInfo.RTN_UNICAST));
    }

    /**
     * Remove iface from local network.
     */
    public void removeInterfaceFromLocalNetwork(String iface) {
        modifyInterfaceInNetwork(MODIFY_OPERATION_REMOVE, INetd.LOCAL_NET_ID, iface);
    }

    /**
     * Clear iface addresses.
     */
    public void clearInterfaceAddresses(String iface) {
        try {
            mNetdService.interfaceClearAddrs(iface);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Enable IPv6 on iface.
     */
    public void enableIpv6(String iface) {
        try {
            mNetdService.interfaceSetEnableIPv6(iface, true);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Disable IPv6 on iface.
     */
    public void disableIpv6(String iface) {
        try {
            mNetdService.interfaceSetEnableIPv6(iface, false);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Convert InterfaceConfiguration to InterfaceConfigurationParcel with given ifname.
     */
    private static InterfaceConfigurationParcel toStableParcel(InterfaceConfiguration cfg,
            String iface) {
        InterfaceConfigurationParcel cfgParcel = new InterfaceConfigurationParcel();
        cfgParcel.ifName = iface;
        String hwAddr = cfg.getHardwareAddress();
        if (!TextUtils.isEmpty(hwAddr)) {
            cfgParcel.hwAddr = hwAddr;
        } else {
            cfgParcel.hwAddr = "";
        }
        cfgParcel.ipv4Addr = cfg.getLinkAddress().getAddress().getHostAddress();
        cfgParcel.prefixLength = cfg.getLinkAddress().getPrefixLength();
        ArrayList<String> flags = new ArrayList<>();
        for (String flag : cfg.getFlags()) {
            flags.add(flag);
        }
        cfgParcel.flags = flags.toArray(new String[0]);

        return cfgParcel;
    }

    /**
     * Construct InterfaceConfiguration from InterfaceConfigurationParcel.
     */
    private static InterfaceConfiguration fromStableParcel(InterfaceConfigurationParcel p) {
        InterfaceConfiguration cfg = new InterfaceConfiguration();
        cfg.setHardwareAddress(p.hwAddr);

        final InetAddress addr = InetAddresses.parseNumericAddress(p.ipv4Addr);
        cfg.setLinkAddress(new LinkAddress(addr, p.prefixLength));
        for (String flag : p.flags) {
            cfg.setFlag(flag);
        }
        return cfg;
    }

    private InterfaceConfiguration getInterfaceConfig(String iface) {
        final InterfaceConfigurationParcel result;
        try {
            result = mNetdService.interfaceGetCfg(iface);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }

        try {
            final InterfaceConfiguration cfg = fromStableParcel(result);
            return cfg;
        } catch (IllegalArgumentException iae) {
            throw new IllegalStateException("Invalid InterfaceConfigurationParcel", iae);
        }
    }

    private void setInterfaceConfig(String iface, InterfaceConfiguration cfg) {
        LinkAddress linkAddr = cfg.getLinkAddress();
        if (linkAddr == null || linkAddr.getAddress() == null) {
            throw new IllegalStateException("Null LinkAddress given");
        }
        final InterfaceConfigurationParcel cfgParcel = toStableParcel(cfg, iface);

        try {
            mNetdService.interfaceSetCfg(cfgParcel);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * List all tethered interfaces.
     */
    public String[] listTetheredInterfaces() {
        try {
            return mNetdService.tetherInterfaceList();
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Register a new netd event observer.
     */
    public void registerObserver(NetdEventObserver observer) {
        mObservers.add(observer);
    }

    /**
     * Unregister a new netd event observer.
     */
    public void unregisterObserver(NetdEventObserver observer) {
        mObservers.remove(observer);
    }

    /**
     * Set iface down.
     */
    public void setInterfaceDown(String iface) {
        final InterfaceConfiguration ifcg = getInterfaceConfig(iface);
        ifcg.setInterfaceDown();
        setInterfaceConfig(iface, ifcg);
    }

    /**
     * Set iface up.
     */
    public void setInterfaceUp(String iface) {
        final InterfaceConfiguration ifcg = getInterfaceConfig(iface);
        ifcg.setInterfaceUp();
        setInterfaceConfig(iface, ifcg);
    }

    /**
     * Returns whether iface is up.
     */
    public boolean isInterfaceUp(String iface) {
        final InterfaceConfiguration ifcg = getInterfaceConfig(iface);
        return ifcg.isUp();
    }

    /**
     * Set iface link address.
     */
    public void setInterfaceLinkAddress(String iface, LinkAddress linkAddress) {
        final InterfaceConfiguration ifcg = getInterfaceConfig(iface);
        ifcg.setLinkAddress(linkAddress);
        ifcg.setInterfaceUp();
        setInterfaceConfig(iface, ifcg);
    }

    /**
     * Set iface IPv6 privacy extensions.
     */
    public void setInterfaceIpv6PrivacyExtensions(String iface, boolean enable) {
        try {
            mNetdService.interfaceSetIPv6PrivacyExtensions(iface, enable);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Start tethering.
     */
    public void startTethering(String[] dhcpRange) {
        // an odd number of addrs will fail
        try {
            mNetdService.tetherStart(dhcpRange);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Stop tethering.
     */
    public void stopTethering() {
        try {
            mNetdService.tetherStop();
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Add tethering on iface.
     */
    public void tetherInterface(String iface) {
        try {
            mNetdService.tetherInterfaceAdd(iface);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
        List<RouteInfo> routes = new ArrayList<>();
        // The RouteInfo constructor truncates the LinkAddress to a network prefix, thus making it
        // suitable to use as a route destination.
        LinkAddress dest = getInterfaceConfig(iface).getLinkAddress();
        RouteInfo route = new RouteInfo(
                new IpPrefix(dest.getAddress(), dest.getPrefixLength()),
                null, null, RouteInfo.RTN_UNICAST);
        routes.add(route);
        addInterfaceToLocalNetwork(iface, routes);
    }

    /**
     * Remove tethering on iface.
     */
    public void untetherInterface(String iface) {
        try {
            mNetdService.tetherInterfaceRemove(iface);
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        } finally {
            removeInterfaceFromLocalNetwork(iface);
        }
    }

    /**
     * Returns whether tethering has been started.
     */
    public boolean isTetheringStarted() {
        try {
            final boolean isEnabled = mNetdService.tetherIsEnabled();
            return isEnabled;
        } catch (RemoteException | ServiceSpecificException e) {
            throw new IllegalStateException(e);
        }
    }


    /**
     * Notify our observers of an interface status change
     */
    private void notifyInterfaceStatusChanged(String iface, boolean up) {
        for (NetdEventObserver observer : mObservers) {
            observer.interfaceStatusChanged(iface, up);
        }
    }

    /**
     * Notify our observers of an interface link state change
     * (typically, an Ethernet cable has been plugged-in or unplugged).
     */
    private void notifyInterfaceLinkStateChanged(String iface, boolean up) {
        for (NetdEventObserver observer : mObservers) {
            observer.interfaceLinkStateChanged(iface, up);
        }
    }
}

