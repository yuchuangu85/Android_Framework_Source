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

package com.android.server.ethernet;

import static android.net.ConnectivityManager.TYPE_ETHERNET;

import android.content.Context;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.StringNetworkSpecifier;
import android.net.ip.IpClient;
import android.net.ip.IpClient.ProvisioningConfiguration;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link NetworkFactory} that represents Ethernet networks.
 *
 * This class reports a static network score of 70 when it is tracking an interface and that
 * interface's link is up, and a score of 0 otherwise.
 */
public class EthernetNetworkFactory extends NetworkFactory {
    private final static String TAG = EthernetNetworkFactory.class.getSimpleName();
    final static boolean DBG = true;

    private final static int NETWORK_SCORE = 70;
    private static final String NETWORK_TYPE = "Ethernet";

    private final ConcurrentHashMap<String, NetworkInterfaceState> mTrackingInterfaces =
            new ConcurrentHashMap<>();
    private final Handler mHandler;
    private final Context mContext;

    public EthernetNetworkFactory(Handler handler, Context context, NetworkCapabilities filter) {
        super(handler.getLooper(), context, NETWORK_TYPE, filter);

        mHandler = handler;
        mContext = context;

        setScoreFilter(NETWORK_SCORE);
    }

    @Override
    public boolean acceptRequest(NetworkRequest request, int score) {
        if (DBG) {
            Log.d(TAG, "acceptRequest, request: " + request + ", score: " + score);
        }

        return networkForRequest(request) != null;
    }

    @Override
    protected void needNetworkFor(NetworkRequest networkRequest, int score) {
        NetworkInterfaceState network = networkForRequest(networkRequest);

        if (network == null) {
            Log.e(TAG, "needNetworkFor, failed to get a network for " + networkRequest);
            return;
        }

        if (++network.refCount == 1) {
            network.start();
        }
    }

    @Override
    protected void releaseNetworkFor(NetworkRequest networkRequest) {
        NetworkInterfaceState network = networkForRequest(networkRequest);
        if (network == null) {
            Log.e(TAG, "needNetworkFor, failed to get a network for " + networkRequest);
            return;
        }

        if (--network.refCount == 1) {
            network.stop();
        }
    }

    /**
     * Returns an array of available interface names. The array is sorted: unrestricted interfaces
     * goes first, then sorted by name.
     */
    String[] getAvailableInterfaces(boolean includeRestricted) {
        return mTrackingInterfaces.values()
                .stream()
                .filter(iface -> !iface.isRestricted() || includeRestricted)
                .sorted((iface1, iface2) -> {
                    int r = Boolean.compare(iface1.isRestricted(), iface2.isRestricted());
                    return r == 0 ? iface1.name.compareTo(iface2.name) : r;
                })
                .map(iface -> iface.name)
                .toArray(String[]::new);
    }

    void addInterface(String ifaceName, String hwAddress, NetworkCapabilities capabilities,
             IpConfiguration ipConfiguration) {
        if (mTrackingInterfaces.containsKey(ifaceName)) {
            Log.e(TAG, "Interface with name " + ifaceName + " already exists.");
            return;
        }

        if (DBG) {
            Log.d(TAG, "addInterface, iface: " + ifaceName + ", capabilities: " + capabilities);
        }

        NetworkInterfaceState iface = new NetworkInterfaceState(
                ifaceName, hwAddress, mHandler, mContext, capabilities);
        iface.setIpConfig(ipConfiguration);
        mTrackingInterfaces.put(ifaceName, iface);

        updateCapabilityFilter();
    }

    private void updateCapabilityFilter() {
        NetworkCapabilities capabilitiesFilter = new NetworkCapabilities();
        capabilitiesFilter.clearAll();

        for (NetworkInterfaceState iface:  mTrackingInterfaces.values()) {
            capabilitiesFilter.combineCapabilities(iface.mCapabilities);
        }

        if (DBG) Log.d(TAG, "updateCapabilityFilter: " + capabilitiesFilter);
        setCapabilityFilter(capabilitiesFilter);
    }

    void removeInterface(String interfaceName) {
        NetworkInterfaceState iface = mTrackingInterfaces.remove(interfaceName);
        if (iface != null) {
            iface.stop();
        }

        updateCapabilityFilter();
    }

    /** Returns true if state has been modified */
    boolean updateInterfaceLinkState(String ifaceName, boolean up) {
        if (!mTrackingInterfaces.containsKey(ifaceName)) {
            return false;
        }

        if (DBG) {
            Log.d(TAG, "updateInterfaceLinkState, iface: " + ifaceName + ", up: " + up);
        }

        NetworkInterfaceState iface = mTrackingInterfaces.get(ifaceName);
        return iface.updateLinkState(up);
    }

    boolean hasInterface(String interfacName) {
        return mTrackingInterfaces.containsKey(interfacName);
    }

    void updateIpConfiguration(String iface, IpConfiguration ipConfiguration) {
        NetworkInterfaceState network = mTrackingInterfaces.get(iface);
        if (network != null) {
            network.setIpConfig(ipConfiguration);
        }
    }

    private NetworkInterfaceState networkForRequest(NetworkRequest request) {
        String requestedIface = null;

        NetworkSpecifier specifier = request.networkCapabilities.getNetworkSpecifier();
        if (specifier instanceof StringNetworkSpecifier) {
            requestedIface = ((StringNetworkSpecifier) specifier).specifier;
        }

        NetworkInterfaceState network = null;
        if (!TextUtils.isEmpty(requestedIface)) {
            NetworkInterfaceState n = mTrackingInterfaces.get(requestedIface);
            if (n != null && n.statisified(request.networkCapabilities)) {
                network = n;
            }
        } else {
            for (NetworkInterfaceState n : mTrackingInterfaces.values()) {
                if (n.statisified(request.networkCapabilities)) {
                    network = n;
                    break;
                }
            }
        }

        if (DBG) {
            Log.i(TAG, "networkForRequest, request: " + request + ", network: " + network);
        }

        return network;
    }

    private static class NetworkInterfaceState {
        final String name;

        private final String mHwAddress;
        private final NetworkCapabilities mCapabilities;
        private final Handler mHandler;
        private final Context mContext;
        private final NetworkInfo mNetworkInfo;

        private static String sTcpBufferSizes = null;  // Lazy initialized.

        private boolean mLinkUp;
        private LinkProperties mLinkProperties = new LinkProperties();

        private IpClient mIpClient;
        private NetworkAgent mNetworkAgent;
        private IpConfiguration mIpConfig;

        long refCount = 0;

        private final IpClient.Callback mIpClientCallback = new IpClient.Callback() {
            @Override
            public void onProvisioningSuccess(LinkProperties newLp) {
                mHandler.post(() -> onIpLayerStarted(newLp));
            }

            @Override
            public void onProvisioningFailure(LinkProperties newLp) {
                mHandler.post(() -> onIpLayerStopped(newLp));
            }

            @Override
            public void onLinkPropertiesChange(LinkProperties newLp) {
                mHandler.post(() -> updateLinkProperties(newLp));
            }
        };

        NetworkInterfaceState(String ifaceName, String hwAddress, Handler handler, Context context,
                NetworkCapabilities capabilities) {
            name = ifaceName;
            mCapabilities = capabilities;
            mHandler = handler;
            mContext = context;

            mHwAddress = hwAddress;
            mNetworkInfo = new NetworkInfo(TYPE_ETHERNET, 0, NETWORK_TYPE, "");
            mNetworkInfo.setExtraInfo(mHwAddress);
            mNetworkInfo.setIsAvailable(true);
        }

        void setIpConfig(IpConfiguration ipConfig) {

            this.mIpConfig = ipConfig;
        }

        boolean statisified(NetworkCapabilities requestedCapabilities) {
            return requestedCapabilities.satisfiedByNetworkCapabilities(mCapabilities);
        }

        boolean isRestricted() {
            return mCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        }

        private void start() {
            if (mIpClient != null) {
                if (DBG) Log.d(TAG, "IpClient already started");
                return;
            }
            if (DBG) {
                Log.d(TAG, String.format("starting IpClient(%s): mNetworkInfo=%s", name,
                        mNetworkInfo));
            }

            mNetworkInfo.setDetailedState(DetailedState.OBTAINING_IPADDR, null, mHwAddress);

            mIpClient = new IpClient(mContext, name, mIpClientCallback);

            if (sTcpBufferSizes == null) {
                sTcpBufferSizes = mContext.getResources().getString(
                        com.android.internal.R.string.config_ethernet_tcp_buffers);
            }
            provisionIpClient(mIpClient, mIpConfig, sTcpBufferSizes);
        }

        void onIpLayerStarted(LinkProperties linkProperties) {
            if (mNetworkAgent != null) {
                Log.e(TAG, "Already have a NetworkAgent - aborting new request");
                stop();
                return;
            }
            mLinkProperties = linkProperties;
            mNetworkInfo.setDetailedState(DetailedState.CONNECTED, null, mHwAddress);
            mNetworkInfo.setIsAvailable(true);

            // Create our NetworkAgent.
            mNetworkAgent = new NetworkAgent(mHandler.getLooper(), mContext,
                    NETWORK_TYPE, mNetworkInfo, mCapabilities, mLinkProperties,
                    NETWORK_SCORE) {
                public void unwanted() {
                    if (this == mNetworkAgent) {
                        stop();
                    } else if (mNetworkAgent != null) {
                        Log.d(TAG, "Ignoring unwanted as we have a more modern " +
                                "instance");
                    }  // Otherwise, we've already called stop.
                }
            };
        }

        void onIpLayerStopped(LinkProperties linkProperties) {
            // This cannot happen due to provisioning timeout, because our timeout is 0. It can only
            // happen if we're provisioned and we lose provisioning.
            stop();
            start();
        }

        void updateLinkProperties(LinkProperties linkProperties) {
            mLinkProperties = linkProperties;
            if (mNetworkAgent != null) {
                mNetworkAgent.sendLinkProperties(linkProperties);
            }
        }

        /** Returns true if state has been modified */
        boolean updateLinkState(boolean up) {
            if (mLinkUp == up) return false;
            mLinkUp = up;

            stop();
            if (up) {
                start();
            }

            return true;
        }

        void stop() {
            if (mIpClient != null) {
                mIpClient.shutdown();
                mIpClient.awaitShutdown();
                mIpClient = null;
            }

            // ConnectivityService will only forget our NetworkAgent if we send it a NetworkInfo object
            // with a state of DISCONNECTED or SUSPENDED. So we can't simply clear our NetworkInfo here:
            // that sets the state to IDLE, and ConnectivityService will still think we're connected.
            //
            mNetworkInfo.setDetailedState(DetailedState.DISCONNECTED, null, mHwAddress);
            if (mNetworkAgent != null) {
                updateAgent();
                mNetworkAgent = null;
            }
            clear();
        }

        private void updateAgent() {
            if (mNetworkAgent == null) return;
            if (DBG) {
                Log.i(TAG, "Updating mNetworkAgent with: " +
                        mCapabilities + ", " +
                        mNetworkInfo + ", " +
                        mLinkProperties);
            }
            mNetworkAgent.sendNetworkCapabilities(mCapabilities);
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
            mNetworkAgent.sendLinkProperties(mLinkProperties);
            // never set the network score below 0.
            mNetworkAgent.sendNetworkScore(mLinkUp? NETWORK_SCORE : 0);
        }

        private void clear() {
            mLinkProperties.clear();
            mNetworkInfo.setDetailedState(DetailedState.IDLE, null, null);
            mNetworkInfo.setIsAvailable(false);
        }

        private static void provisionIpClient(IpClient ipClient, IpConfiguration config,
                String tcpBufferSizes) {
            if (config.getProxySettings() == ProxySettings.STATIC ||
                    config.getProxySettings() == ProxySettings.PAC) {
                ipClient.setHttpProxy(config.getHttpProxy());
            }

            if (!TextUtils.isEmpty(tcpBufferSizes)) {
                ipClient.setTcpBufferSizes(tcpBufferSizes);
            }

            final ProvisioningConfiguration provisioningConfiguration;
            if (config.getIpAssignment() == IpAssignment.STATIC) {
                provisioningConfiguration = IpClient.buildProvisioningConfiguration()
                        .withStaticConfiguration(config.getStaticIpConfiguration())
                        .build();
            } else {
                provisioningConfiguration = IpClient.buildProvisioningConfiguration()
                        .withProvisioningTimeoutMs(0)
                        .build();
            }

            ipClient.startProvisioning(provisioningConfiguration);
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "{ "
                    + "iface: " + name + ", "
                    + "up: " + mLinkUp + ", "
                    + "hwAddress: " + mHwAddress + ", "
                    + "networkInfo: " + mNetworkInfo + ", "
                    + "networkAgent: " + mNetworkAgent + ", "
                    + "ipClient: " + mIpClient + ","
                    + "linkProperties: " + mLinkProperties
                    + "}";
        }
    }

    void dump(FileDescriptor fd, IndentingPrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        pw.println(getClass().getSimpleName());
        pw.println("Tracking interfaces:");
        pw.increaseIndent();
        for (String iface: mTrackingInterfaces.keySet()) {
            NetworkInterfaceState ifaceState = mTrackingInterfaces.get(iface);
            pw.println(iface + ":" + ifaceState);
            pw.increaseIndent();
            final IpClient ipClient = ifaceState.mIpClient;
            if (ipClient != null) {
                ipClient.dump(fd, pw, args);
            } else {
                pw.println("IpClient is null");
            }
            pw.decreaseIndent();
        }
        pw.decreaseIndent();
    }
}
