package com.android.clockwork.bluetooth.proxy;

import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.RouteInfo;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Companion proxy link properties configuration
 *
 * This wrapper around {@link LinkProperties} with some
 * additional wear specific configuration for the bluetooth
 * sysproxy.
 */
public class ProxyLinkProperties {
    private static final String TAG = WearProxyConstants.LOG_TAG;

    private static final int MTU = 1500;
    private static final String INTERFACE_NAME = "lo";
    private static final String LOCAL_ADDRESS = "127.0.0.1";
    private static final String GOOGLE_DNS1 = "8.8.8.8";
    private static final String GOOGLE_DNS2 = "8.8.4.4";
    // TODO(qyma): This is a temporary solution for resolving DNS problems. We should fix this
    // by resolving DNS on the phone side, see b/35445861.
    private static final String LE_PRIMARY_DNS = "202.106.0.20";

    private final LinkProperties mLinkProperties;

    public ProxyLinkProperties(final LinkProperties linkProperties, final boolean isLocalEdition) {
        mLinkProperties = linkProperties;
        mLinkProperties.setInterfaceName(INTERFACE_NAME);
        mLinkProperties.setMtu(MTU);
        try {
            addLocalRoute();
            if (isLocalEdition) {
                /*****************************************************************
                 * This Code Path and its dependency must not be merged to AOSP! *
                 *****************************************************************/
                mLinkProperties.addDnsServer(InetAddress.getByName(LE_PRIMARY_DNS));
            }
            mLinkProperties.addDnsServer(InetAddress.getByName(GOOGLE_DNS1));
            mLinkProperties.addDnsServer(InetAddress.getByName(GOOGLE_DNS2));
        } catch (UnknownHostException e) {
            // Rethrow an unchecked exception; we can't proceed if adding DNS servers fails.
            throw new RuntimeException("Could not add DNS address", e);
        }
    }

    public LinkProperties getLinkProperties() {
        return mLinkProperties;
    }

    protected void addLocalRoute() throws UnknownHostException {
        final InetAddress gateway = InetAddress.getByName(LOCAL_ADDRESS);
        mLinkProperties.addRoute(new RouteInfo((LinkAddress) null, gateway, INTERFACE_NAME));
    }
}
