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

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DhcpResults;
import android.net.EthernetManager;
import android.net.IEthernetServiceListener;
import android.net.InterfaceConfiguration;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.StaticIpConfiguration;
import android.net.ip.IpManager;
import android.net.ip.IpManager.ProvisioningConfiguration;
import android.os.Handler;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.net.BaseNetworkObserver;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;


/**
 * Manages connectivity for an Ethernet interface.
 *
 * Ethernet Interfaces may be present at boot time or appear after boot (e.g.,
 * for Ethernet adapters connected over USB). This class currently supports
 * only one interface. When an interface appears on the system (or is present
 * at boot time) this class will start tracking it and bring it up, and will
 * attempt to connect when requested. Any other interfaces that subsequently
 * appear will be ignored until the tracked interface disappears. Only
 * interfaces whose names match the <code>config_ethernet_iface_regex</code>
 * regular expression are tracked.
 *
 * This class reports a static network score of 70 when it is tracking an
 * interface and that interface's link is up, and a score of 0 otherwise.
 *
 * @hide
 */
class EthernetNetworkFactory {
    private static final String NETWORK_TYPE = "Ethernet";
    private static final String TAG = "EthernetNetworkFactory";
    private static final int NETWORK_SCORE = 70;
    private static final boolean DBG = true;

    /** Tracks interface changes. Called from NetworkManagementService. */
    private InterfaceObserver mInterfaceObserver;

    /** For static IP configuration */
    private EthernetManager mEthernetManager;

    /** To set link state and configure IP addresses. */
    private INetworkManagementService mNMService;

    /** All code runs here, including start(). */
    private Handler mHandler;

    /* To communicate with ConnectivityManager */
    private NetworkCapabilities mNetworkCapabilities;
    private NetworkAgent mNetworkAgent;
    private LocalNetworkFactory mFactory;
    private Context mContext;

    /** Product-dependent regular expression of interface names we track. */
    private static String mIfaceMatch = "";

    /** To notify Ethernet status. */
    private final RemoteCallbackList<IEthernetServiceListener> mListeners;

    /** Data members. All accesses to these must be on the handler thread. */
    private String mIface = "";
    private String mHwAddr;
    private boolean mLinkUp;
    private NetworkInfo mNetworkInfo;
    private LinkProperties mLinkProperties;
    private IpManager mIpManager;
    private boolean mNetworkRequested = false;

    EthernetNetworkFactory(RemoteCallbackList<IEthernetServiceListener> listeners) {
        initNetworkCapabilities();
        clearInfo();
        mListeners = listeners;
    }

    private class LocalNetworkFactory extends NetworkFactory {
        LocalNetworkFactory(String name, Context context, Looper looper) {
            super(looper, context, name, new NetworkCapabilities());
        }

        protected void startNetwork() {
            if (!mNetworkRequested) {
                mNetworkRequested = true;
                maybeStartIpManager();
            }
        }

        protected void stopNetwork() {
            mNetworkRequested = false;
            stopIpManager();
        }
    }

    private void clearInfo() {
        mLinkProperties = new LinkProperties();
        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_ETHERNET, 0, NETWORK_TYPE, "");
        mNetworkInfo.setExtraInfo(mHwAddr);
        mNetworkInfo.setIsAvailable(isTrackingInterface());
    }

    private void stopIpManager() {
        if (mIpManager != null) {
            mIpManager.shutdown();
            mIpManager = null;
        }
        // ConnectivityService will only forget our NetworkAgent if we send it a NetworkInfo object
        // with a state of DISCONNECTED or SUSPENDED. So we can't simply clear our NetworkInfo here:
        // that sets the state to IDLE, and ConnectivityService will still think we're connected.
        //
        mNetworkInfo.setDetailedState(DetailedState.DISCONNECTED, null, mHwAddr);
        if (mNetworkAgent != null) {
            updateAgent();
            mNetworkAgent = null;
        }
        clearInfo();
    }

    /**
     * Updates interface state variables.
     * Called on link state changes or on startup.
     */
    private void updateInterfaceState(String iface, boolean up) {
        if (!mIface.equals(iface)) {
            return;
        }
        Log.d(TAG, "updateInterface: " + iface + " link " + (up ? "up" : "down"));

        mLinkUp = up;
        if (up) {
            maybeStartIpManager();
        } else {
            stopIpManager();
        }
    }

    private class InterfaceObserver extends BaseNetworkObserver {
        @Override
        public void interfaceLinkStateChanged(String iface, boolean up) {
            mHandler.post(() -> {
                updateInterfaceState(iface, up);
            });
        }

        @Override
        public void interfaceAdded(String iface) {
            mHandler.post(() -> {
                maybeTrackInterface(iface);
            });
        }

        @Override
        public void interfaceRemoved(String iface) {
            mHandler.post(() -> {
                if (stopTrackingInterface(iface)) {
                    trackFirstAvailableInterface();
                }
            });
        }
    }

    private void setInterfaceUp(String iface) {
        // Bring up the interface so we get link status indications.
        try {
            mNMService.setInterfaceUp(iface);
            String hwAddr = null;
            InterfaceConfiguration config = mNMService.getInterfaceConfig(iface);

            if (config == null) {
                Log.e(TAG, "Null interface config for " + iface + ". Bailing out.");
                return;
            }

            if (!isTrackingInterface()) {
                setInterfaceInfo(iface, config.getHardwareAddress());
                mNetworkInfo.setIsAvailable(true);
                mNetworkInfo.setExtraInfo(mHwAddr);
            } else {
                Log.e(TAG, "Interface unexpectedly changed from " + iface + " to " + mIface);
                mNMService.setInterfaceDown(iface);
            }
        } catch (RemoteException | IllegalStateException e) {
            // Either the system is crashing or the interface has disappeared. Just ignore the
            // error; we haven't modified any state because we only do that if our calls succeed.
            Log.e(TAG, "Error upping interface " + mIface + ": " + e);
        }
    }

    private boolean maybeTrackInterface(String iface) {
        // If we don't already have an interface, and if this interface matches
        // our regex, start tracking it.
        if (!iface.matches(mIfaceMatch) || isTrackingInterface())
            return false;

        Log.d(TAG, "Started tracking interface " + iface);
        setInterfaceUp(iface);
        return true;
    }

    private boolean stopTrackingInterface(String iface) {
        if (!iface.equals(mIface))
            return false;

        Log.d(TAG, "Stopped tracking interface " + iface);
        setInterfaceInfo("", null);
        stopIpManager();
        return true;
    }

    private boolean setStaticIpAddress(StaticIpConfiguration staticConfig) {
        if (staticConfig.ipAddress != null &&
                staticConfig.gateway != null &&
                staticConfig.dnsServers.size() > 0) {
            try {
                Log.i(TAG, "Applying static IPv4 configuration to " + mIface + ": " + staticConfig);
                InterfaceConfiguration config = mNMService.getInterfaceConfig(mIface);
                config.setLinkAddress(staticConfig.ipAddress);
                mNMService.setInterfaceConfig(mIface, config);
                return true;
            } catch(RemoteException|IllegalStateException e) {
               Log.e(TAG, "Setting static IP address failed: " + e.getMessage());
            }
        } else {
            Log.e(TAG, "Invalid static IP configuration.");
        }
        return false;
    }

    public void updateAgent() {
        if (mNetworkAgent == null) return;
        if (DBG) {
            Log.i(TAG, "Updating mNetworkAgent with: " +
                  mNetworkCapabilities + ", " +
                  mNetworkInfo + ", " +
                  mLinkProperties);
        }
        mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
        mNetworkAgent.sendNetworkInfo(mNetworkInfo);
        mNetworkAgent.sendLinkProperties(mLinkProperties);
        // never set the network score below 0.
        mNetworkAgent.sendNetworkScore(mLinkUp? NETWORK_SCORE : 0);
    }

    void onIpLayerStarted(LinkProperties linkProperties) {
        if (mNetworkAgent != null) {
            Log.e(TAG, "Already have a NetworkAgent - aborting new request");
            stopIpManager();
            return;
        }
        mLinkProperties = linkProperties;
        mNetworkInfo.setDetailedState(DetailedState.CONNECTED, null, mHwAddr);

        // Create our NetworkAgent.
        mNetworkAgent = new NetworkAgent(mHandler.getLooper(), mContext,
                NETWORK_TYPE, mNetworkInfo, mNetworkCapabilities, mLinkProperties,
                NETWORK_SCORE) {
            public void unwanted() {
                if (this == mNetworkAgent) {
                    stopIpManager();
                } else if (mNetworkAgent != null) {
                    Log.d(TAG, "Ignoring unwanted as we have a more modern " +
                            "instance");
                }  // Otherwise, we've already called stopIpManager.
            }
        };
    }

    void onIpLayerStopped(LinkProperties linkProperties) {
        // This cannot happen due to provisioning timeout, because our timeout is 0. It can only
        // happen if we're provisioned and we lose provisioning.
        stopIpManager();
        maybeStartIpManager();
    }

    void updateLinkProperties(LinkProperties linkProperties) {
        mLinkProperties = linkProperties;
        if (mNetworkAgent != null) {
            mNetworkAgent.sendLinkProperties(linkProperties);
        }
    }

    public void maybeStartIpManager() {
        if (mNetworkRequested && mIpManager == null && isTrackingInterface()) {
            startIpManager();
        }
    }

    public void startIpManager() {
        if (DBG) {
            Log.d(TAG, String.format("starting IpManager(%s): mNetworkInfo=%s", mIface,
                    mNetworkInfo));
        }

        LinkProperties linkProperties;

        IpConfiguration config = mEthernetManager.getConfiguration();

        if (config.getIpAssignment() == IpAssignment.STATIC) {
            if (!setStaticIpAddress(config.getStaticIpConfiguration())) {
                // We've already logged an error.
                return;
            }
            linkProperties = config.getStaticIpConfiguration().toLinkProperties(mIface);
        } else {
            mNetworkInfo.setDetailedState(DetailedState.OBTAINING_IPADDR, null, mHwAddr);
            IpManager.Callback ipmCallback = new IpManager.Callback() {
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

            stopIpManager();
            mIpManager = new IpManager(mContext, mIface, ipmCallback);

            if (config.getProxySettings() == ProxySettings.STATIC ||
                    config.getProxySettings() == ProxySettings.PAC) {
                mIpManager.setHttpProxy(config.getHttpProxy());
            }

            final String tcpBufferSizes = mContext.getResources().getString(
                    com.android.internal.R.string.config_ethernet_tcp_buffers);
            if (!TextUtils.isEmpty(tcpBufferSizes)) {
                mIpManager.setTcpBufferSizes(tcpBufferSizes);
            }

            final ProvisioningConfiguration provisioningConfiguration =
                    mIpManager.buildProvisioningConfiguration()
                            .withProvisioningTimeoutMs(0)
                            .build();
            mIpManager.startProvisioning(provisioningConfiguration);
        }
    }

    /**
     * Begin monitoring connectivity
     */
    public void start(Context context, Handler handler) {
        mHandler = handler;

        // The services we use.
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNMService = INetworkManagementService.Stub.asInterface(b);
        mEthernetManager = (EthernetManager) context.getSystemService(Context.ETHERNET_SERVICE);

        // Interface match regex.
        mIfaceMatch = context.getResources().getString(
                com.android.internal.R.string.config_ethernet_iface_regex);

        // Create and register our NetworkFactory.
        mFactory = new LocalNetworkFactory(NETWORK_TYPE, context, mHandler.getLooper());
        mFactory.setCapabilityFilter(mNetworkCapabilities);
        mFactory.setScoreFilter(NETWORK_SCORE);
        mFactory.register();

        mContext = context;

        // Start tracking interface change events.
        mInterfaceObserver = new InterfaceObserver();
        try {
            mNMService.registerObserver(mInterfaceObserver);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not register InterfaceObserver " + e);
        }

        // If an Ethernet interface is already connected, start tracking that.
        // Otherwise, the first Ethernet interface to appear will be tracked.
        mHandler.post(() -> trackFirstAvailableInterface());
    }

    public void trackFirstAvailableInterface() {
        try {
            final String[] ifaces = mNMService.listInterfaces();
            for (String iface : ifaces) {
                if (maybeTrackInterface(iface)) {
                    // We have our interface. Track it.
                    // Note: if the interface already has link (e.g., if we crashed and got
                    // restarted while it was running), we need to fake a link up notification so we
                    // start configuring it.
                    if (mNMService.getInterfaceConfig(iface).hasFlag("running")) {
                        updateInterfaceState(iface, true);
                    }
                    break;
                }
            }
        } catch (RemoteException|IllegalStateException e) {
            Log.e(TAG, "Could not get list of interfaces " + e);
        }
    }

    public void stop() {
        stopIpManager();
        setInterfaceInfo("", null);
        mFactory.unregister();
    }

    private void initNetworkCapabilities() {
        mNetworkCapabilities = new NetworkCapabilities();
        mNetworkCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET);
        mNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        mNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        // We have no useful data on bandwidth. Say 100M up and 100M down. :-(
        mNetworkCapabilities.setLinkUpstreamBandwidthKbps(100 * 1000);
        mNetworkCapabilities.setLinkDownstreamBandwidthKbps(100 * 1000);
    }

    public boolean isTrackingInterface() {
        return !TextUtils.isEmpty(mIface);
    }

    /**
     * Set interface information and notify listeners if availability is changed.
     */
    private void setInterfaceInfo(String iface, String hwAddr) {
        boolean oldAvailable = isTrackingInterface();
        mIface = iface;
        mHwAddr = hwAddr;
        boolean available = isTrackingInterface();

        mNetworkInfo.setExtraInfo(mHwAddr);
        mNetworkInfo.setIsAvailable(available);

        if (oldAvailable != available) {
            int n = mListeners.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    mListeners.getBroadcastItem(i).onAvailabilityChanged(available);
                } catch (RemoteException e) {
                    // Do nothing here.
                }
            }
            mListeners.finishBroadcast();
        }
    }

    private void postAndWaitForRunnable(Runnable r) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        mHandler.post(() -> {
            try {
                r.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await();
    }


    void dump(FileDescriptor fd, IndentingPrintWriter pw, String[] args) {
        try {
            postAndWaitForRunnable(() -> {
                pw.println("Network Requested: " + mNetworkRequested);
                if (isTrackingInterface()) {
                    pw.println("Tracking interface: " + mIface);
                    pw.increaseIndent();
                    pw.println("MAC address: " + mHwAddr);
                    pw.println("Link state: " + (mLinkUp ? "up" : "down"));
                    pw.decreaseIndent();
                } else {
                    pw.println("Not tracking any interface");
                }

                pw.println();
                pw.println("NetworkInfo: " + mNetworkInfo);
                pw.println("LinkProperties: " + mLinkProperties);
                pw.println("NetworkAgent: " + mNetworkAgent);
                if (mIpManager != null) {
                    pw.println("IpManager:");
                    pw.increaseIndent();
                    mIpManager.dump(fd, pw, args);
                    pw.decreaseIndent();
                }
            });
        } catch (InterruptedException e) {
            throw new IllegalStateException("dump() interrupted");
        }
    }
}
