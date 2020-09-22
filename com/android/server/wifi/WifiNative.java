/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server.wifi;

import static android.net.wifi.WifiManager.WIFI_FEATURE_OWE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.net.MacAddress;
import android.net.TrafficStats;
import android.net.apf.ApfCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiAnnotations;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiSsid;
import android.net.wifi.nl80211.DeviceWiphyCapabilities;
import android.net.wifi.nl80211.NativeScanResult;
import android.net.wifi.nl80211.RadioChainInfo;
import android.net.wifi.nl80211.WifiNl80211Manager;
import android.os.Handler;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.Immutable;
import com.android.internal.util.HexDump;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.util.FrameParser;
import com.android.server.wifi.util.InformationElementUtil;
import com.android.server.wifi.util.NativeUtil;
import com.android.server.wifi.util.NetdWrapper;
import com.android.server.wifi.util.NetdWrapper.NetdEventObserver;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;

/**
 * Native calls for bring up/shut down of the supplicant daemon and for
 * sending requests to the supplicant daemon
 *
 * {@hide}
 */
public class WifiNative {
    private static final String TAG = "WifiNative";

    private final SupplicantStaIfaceHal mSupplicantStaIfaceHal;
    private final HostapdHal mHostapdHal;
    private final WifiVendorHal mWifiVendorHal;
    private final WifiNl80211Manager mWifiCondManager;
    private final WifiMonitor mWifiMonitor;
    private final PropertyService mPropertyService;
    private final WifiMetrics mWifiMetrics;
    private final Handler mHandler;
    private final Random mRandom;
    private final WifiInjector mWifiInjector;
    private NetdWrapper mNetdWrapper;
    private boolean mVerboseLoggingEnabled = false;

    public WifiNative(WifiVendorHal vendorHal,
                      SupplicantStaIfaceHal staIfaceHal, HostapdHal hostapdHal,
                      WifiNl80211Manager condManager, WifiMonitor wifiMonitor,
                      PropertyService propertyService, WifiMetrics wifiMetrics,
                      Handler handler, Random random,
                      WifiInjector wifiInjector) {
        mWifiVendorHal = vendorHal;
        mSupplicantStaIfaceHal = staIfaceHal;
        mHostapdHal = hostapdHal;
        mWifiCondManager = condManager;
        mWifiMonitor = wifiMonitor;
        mPropertyService = propertyService;
        mWifiMetrics = wifiMetrics;
        mHandler = handler;
        mRandom = random;
        mWifiInjector = wifiInjector;
    }

    /**
     * Enable verbose logging for all sub modules.
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = verbose > 0 ? true : false;
        mWifiCondManager.enableVerboseLogging(mVerboseLoggingEnabled);
        mSupplicantStaIfaceHal.enableVerboseLogging(mVerboseLoggingEnabled);
        mHostapdHal.enableVerboseLogging(mVerboseLoggingEnabled);
        mWifiVendorHal.enableVerboseLogging(mVerboseLoggingEnabled);
    }

    /**
     * Callbacks for SoftAp interface.
     */
    public interface SoftApListener extends WifiNl80211Manager.SoftApCallback {
        // dummy for now - provide a shell so that clients don't use a
        // WifiNl80211Manager-specific API.
    }

    /********************************************************
     * Interface management related methods.
     ********************************************************/
    /**
     * Meta-info about every iface that is active.
     */
    private static class Iface {
        /** Type of ifaces possible */
        public static final int IFACE_TYPE_AP = 0;
        public static final int IFACE_TYPE_STA_FOR_CONNECTIVITY = 1;
        public static final int IFACE_TYPE_STA_FOR_SCAN = 2;

        @IntDef({IFACE_TYPE_AP, IFACE_TYPE_STA_FOR_CONNECTIVITY, IFACE_TYPE_STA_FOR_SCAN})
        @Retention(RetentionPolicy.SOURCE)
        public @interface IfaceType{}

        /** Identifier allocated for the interface */
        public final int id;
        /** Type of the iface: STA (for Connectivity or Scan) or AP */
        public @IfaceType int type;
        /** Name of the interface */
        public String name;
        /** Is the interface up? This is used to mask up/down notifications to external clients. */
        public boolean isUp;
        /** External iface destroyed listener for the iface */
        public InterfaceCallback externalListener;
        /** Network observer registered for this interface */
        public NetworkObserverInternal networkObserver;
        /** Interface feature set / capabilities */
        public long featureSet;
        public DeviceWiphyCapabilities phyCapabilities;

        Iface(int id, @Iface.IfaceType int type) {
            this.id = id;
            this.type = type;
        }

        @Override
        public String toString() {
            StringBuffer sb = new StringBuffer();
            String typeString;
            switch(type) {
                case IFACE_TYPE_STA_FOR_CONNECTIVITY:
                    typeString = "STA_CONNECTIVITY";
                    break;
                case IFACE_TYPE_STA_FOR_SCAN:
                    typeString = "STA_SCAN";
                    break;
                case IFACE_TYPE_AP:
                    typeString = "AP";
                    break;
                default:
                    typeString = "<UNKNOWN>";
                    break;
            }
            sb.append("Iface:")
                .append("{")
                .append("Name=").append(name)
                .append(",")
                .append("Id=").append(id)
                .append(",")
                .append("Type=").append(typeString)
                .append("}");
            return sb.toString();
        }
    }

    /**
     * Iface Management entity. This class maintains list of all the active ifaces.
     */
    private static class IfaceManager {
        /** Integer to allocate for the next iface being created */
        private int mNextId;
        /** Map of the id to the iface structure */
        private HashMap<Integer, Iface> mIfaces = new HashMap<>();

        /** Allocate a new iface for the given type */
        private Iface allocateIface(@Iface.IfaceType  int type) {
            Iface iface = new Iface(mNextId, type);
            mIfaces.put(mNextId, iface);
            mNextId++;
            return iface;
        }

        /** Remove the iface using the provided id */
        private Iface removeIface(int id) {
            return mIfaces.remove(id);
        }

        /** Lookup the iface using the provided id */
        private Iface getIface(int id) {
            return mIfaces.get(id);
        }

        /** Lookup the iface using the provided name */
        private Iface getIface(@NonNull String ifaceName) {
            for (Iface iface : mIfaces.values()) {
                if (TextUtils.equals(iface.name, ifaceName)) {
                    return iface;
                }
            }
            return null;
        }

        /** Iterator to use for deleting all the ifaces while performing teardown on each of them */
        private Iterator<Integer> getIfaceIdIter() {
            return mIfaces.keySet().iterator();
        }

        /** Checks if there are any iface active. */
        private boolean hasAnyIface() {
            return !mIfaces.isEmpty();
        }

        /** Checks if there are any iface of the given type active. */
        private boolean hasAnyIfaceOfType(@Iface.IfaceType int type) {
            for (Iface iface : mIfaces.values()) {
                if (iface.type == type) {
                    return true;
                }
            }
            return false;
        }

        /** Checks if there are any iface of the given type active. */
        private Iface findAnyIfaceOfType(@Iface.IfaceType int type) {
            for (Iface iface : mIfaces.values()) {
                if (iface.type == type) {
                    return iface;
                }
            }
            return null;
        }

        /** Checks if there are any STA (for connectivity) iface active. */
        private boolean hasAnyStaIfaceForConnectivity() {
            return hasAnyIfaceOfType(Iface.IFACE_TYPE_STA_FOR_CONNECTIVITY);
        }

        /** Checks if there are any STA (for scan) iface active. */
        private boolean hasAnyStaIfaceForScan() {
            return hasAnyIfaceOfType(Iface.IFACE_TYPE_STA_FOR_SCAN);
        }

        /** Checks if there are any AP iface active. */
        private boolean hasAnyApIface() {
            return hasAnyIfaceOfType(Iface.IFACE_TYPE_AP);
        }

        /** Finds the name of any STA iface active. */
        private String findAnyStaIfaceName() {
            Iface iface = findAnyIfaceOfType(Iface.IFACE_TYPE_STA_FOR_CONNECTIVITY);
            if (iface == null) {
                iface = findAnyIfaceOfType(Iface.IFACE_TYPE_STA_FOR_SCAN);
            }
            if (iface == null) {
                return null;
            }
            return iface.name;
        }

        /** Finds the name of any AP iface active. */
        private String findAnyApIfaceName() {
            Iface iface = findAnyIfaceOfType(Iface.IFACE_TYPE_AP);
            if (iface == null) {
                return null;
            }
            return iface.name;
        }

        private @NonNull Set<String> findAllStaIfaceNames() {
            Set<String> ifaceNames = new ArraySet<>();
            for (Iface iface : mIfaces.values()) {
                if (iface.type == Iface.IFACE_TYPE_STA_FOR_CONNECTIVITY
                        || iface.type == Iface.IFACE_TYPE_STA_FOR_SCAN) {
                    ifaceNames.add(iface.name);
                }
            }
            return ifaceNames;
        }

        /** Removes the existing iface that does not match the provided id. */
        public Iface removeExistingIface(int newIfaceId) {
            Iface removedIface = null;
            // The number of ifaces in the database could be 1 existing & 1 new at the max.
            if (mIfaces.size() > 2) {
                Log.wtf(TAG, "More than 1 existing interface found");
            }
            Iterator<Map.Entry<Integer, Iface>> iter = mIfaces.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<Integer, Iface> entry = iter.next();
                if (entry.getKey() != newIfaceId) {
                    removedIface = entry.getValue();
                    iter.remove();
                }
            }
            return removedIface;
        }
    }

    private class NormalScanEventCallback implements WifiNl80211Manager.ScanEventCallback {
        private String mIfaceName;

        NormalScanEventCallback(String ifaceName) {
            mIfaceName = ifaceName;
        }

        @Override
        public void onScanResultReady() {
            Log.d(TAG, "Scan result ready event");
            mWifiMonitor.broadcastScanResultEvent(mIfaceName);
        }

        @Override
        public void onScanFailed() {
            Log.d(TAG, "Scan failed event");
            mWifiMonitor.broadcastScanFailedEvent(mIfaceName);
        }
    }

    private class PnoScanEventCallback implements WifiNl80211Manager.ScanEventCallback {
        private String mIfaceName;

        PnoScanEventCallback(String ifaceName) {
            mIfaceName = ifaceName;
        }

        @Override
        public void onScanResultReady() {
            Log.d(TAG, "Pno scan result event");
            mWifiMonitor.broadcastPnoScanResultEvent(mIfaceName);
            mWifiMetrics.incrementPnoFoundNetworkEventCount();
        }

        @Override
        public void onScanFailed() {
            Log.d(TAG, "Pno Scan failed event");
            mWifiMetrics.incrementPnoScanFailedCount();
        }
    }

    private final Object mLock = new Object();
    private final IfaceManager mIfaceMgr = new IfaceManager();
    private HashSet<StatusListener> mStatusListeners = new HashSet<>();

    /** Helper method invoked to start supplicant if there were no ifaces */
    private boolean startHal() {
        synchronized (mLock) {
            if (!mIfaceMgr.hasAnyIface()) {
                if (mWifiVendorHal.isVendorHalSupported()) {
                    if (!mWifiVendorHal.startVendorHal()) {
                        Log.e(TAG, "Failed to start vendor HAL");
                        return false;
                    }
                } else {
                    Log.i(TAG, "Vendor Hal not supported, ignoring start.");
                }
            }
            return true;
        }
    }

    /** Helper method invoked to stop HAL if there are no more ifaces */
    private void stopHalAndWificondIfNecessary() {
        synchronized (mLock) {
            if (!mIfaceMgr.hasAnyIface()) {
                if (!mWifiCondManager.tearDownInterfaces()) {
                    Log.e(TAG, "Failed to teardown ifaces from wificond");
                }
                if (mWifiVendorHal.isVendorHalSupported()) {
                    mWifiVendorHal.stopVendorHal();
                } else {
                    Log.i(TAG, "Vendor Hal not supported, ignoring stop.");
                }
            }
        }
    }

    private static final int CONNECT_TO_SUPPLICANT_RETRY_INTERVAL_MS = 100;
    private static final int CONNECT_TO_SUPPLICANT_RETRY_TIMES = 50;
    /**
     * This method is called to wait for establishing connection to wpa_supplicant.
     *
     * @return true if connection is established, false otherwise.
     */
    private boolean startAndWaitForSupplicantConnection() {
        // Start initialization if not already started.
        if (!mSupplicantStaIfaceHal.isInitializationStarted()
                && !mSupplicantStaIfaceHal.initialize()) {
            return false;
        }
        if (!mSupplicantStaIfaceHal.startDaemon()) {
            Log.e(TAG, "Failed to startup supplicant");
            return false;
        }
        boolean connected = false;
        int connectTries = 0;
        while (!connected && connectTries++ < CONNECT_TO_SUPPLICANT_RETRY_TIMES) {
            // Check if the initialization is complete.
            connected = mSupplicantStaIfaceHal.isInitializationComplete();
            if (connected) {
                break;
            }
            try {
                Thread.sleep(CONNECT_TO_SUPPLICANT_RETRY_INTERVAL_MS);
            } catch (InterruptedException ignore) {
            }
        }
        return connected;
    }

    /** Helper method invoked to start supplicant if there were no STA ifaces */
    private boolean startSupplicant() {
        synchronized (mLock) {
            if (!mIfaceMgr.hasAnyStaIfaceForConnectivity()) {
                if (!startAndWaitForSupplicantConnection()) {
                    Log.e(TAG, "Failed to connect to supplicant");
                    return false;
                }
                if (!mSupplicantStaIfaceHal.registerDeathHandler(
                        new SupplicantDeathHandlerInternal())) {
                    Log.e(TAG, "Failed to register supplicant death handler");
                    return false;
                }
            }
            return true;
        }
    }

    /** Helper method invoked to stop supplicant if there are no more STA ifaces */
    private void stopSupplicantIfNecessary() {
        synchronized (mLock) {
            if (!mIfaceMgr.hasAnyStaIfaceForConnectivity()) {
                if (!mSupplicantStaIfaceHal.deregisterDeathHandler()) {
                    Log.e(TAG, "Failed to deregister supplicant death handler");
                }
                mSupplicantStaIfaceHal.terminate();
            }
        }
    }

    /** Helper method invoked to start hostapd if there were no AP ifaces */
    private boolean startHostapd() {
        synchronized (mLock) {
            if (!mIfaceMgr.hasAnyApIface()) {
                if (!startAndWaitForHostapdConnection()) {
                    Log.e(TAG, "Failed to connect to hostapd");
                    return false;
                }
                if (!mHostapdHal.registerDeathHandler(
                        new HostapdDeathHandlerInternal())) {
                    Log.e(TAG, "Failed to register hostapd death handler");
                    return false;
                }
            }
            return true;
        }
    }

    /** Helper method invoked to stop hostapd if there are no more AP ifaces */
    private void stopHostapdIfNecessary() {
        synchronized (mLock) {
            if (!mIfaceMgr.hasAnyApIface()) {
                if (!mHostapdHal.deregisterDeathHandler()) {
                    Log.e(TAG, "Failed to deregister hostapd death handler");
                }
                mHostapdHal.terminate();
            }
        }
    }

    /** Helper method to register a network observer and return it */
    private boolean registerNetworkObserver(NetworkObserverInternal observer) {
        if (observer == null) return false;
        mNetdWrapper.registerObserver(observer);
        return true;
    }

    /** Helper method to unregister a network observer */
    private boolean unregisterNetworkObserver(NetworkObserverInternal observer) {
        if (observer == null) return false;
        mNetdWrapper.unregisterObserver(observer);
        return true;
    }

    /**
     * Helper method invoked to teardown client iface (for connectivity) and perform
     * necessary cleanup
     */
    private void onClientInterfaceForConnectivityDestroyed(@NonNull Iface iface) {
        synchronized (mLock) {
            mWifiMonitor.stopMonitoring(iface.name);
            if (!unregisterNetworkObserver(iface.networkObserver)) {
                Log.e(TAG, "Failed to unregister network observer on " + iface);
            }
            if (!mSupplicantStaIfaceHal.teardownIface(iface.name)) {
                Log.e(TAG, "Failed to teardown iface in supplicant on " + iface);
            }
            if (!mWifiCondManager.tearDownClientInterface(iface.name)) {
                Log.e(TAG, "Failed to teardown iface in wificond on " + iface);
            }
            stopSupplicantIfNecessary();
            stopHalAndWificondIfNecessary();
        }
    }

    /** Helper method invoked to teardown client iface (for scan) and perform necessary cleanup */
    private void onClientInterfaceForScanDestroyed(@NonNull Iface iface) {
        synchronized (mLock) {
            mWifiMonitor.stopMonitoring(iface.name);
            if (!unregisterNetworkObserver(iface.networkObserver)) {
                Log.e(TAG, "Failed to unregister network observer on " + iface);
            }
            if (!mWifiCondManager.tearDownClientInterface(iface.name)) {
                Log.e(TAG, "Failed to teardown iface in wificond on " + iface);
            }
            stopHalAndWificondIfNecessary();
        }
    }

    /** Helper method invoked to teardown softAp iface and perform necessary cleanup */
    private void onSoftApInterfaceDestroyed(@NonNull Iface iface) {
        synchronized (mLock) {
            if (!unregisterNetworkObserver(iface.networkObserver)) {
                Log.e(TAG, "Failed to unregister network observer on " + iface);
            }
            if (!mHostapdHal.removeAccessPoint(iface.name)) {
                Log.e(TAG, "Failed to remove access point on " + iface);
            }
            if (!mWifiCondManager.tearDownSoftApInterface(iface.name)) {
                Log.e(TAG, "Failed to teardown iface in wificond on " + iface);
            }
            stopHostapdIfNecessary();
            stopHalAndWificondIfNecessary();
        }
    }

    /** Helper method invoked to teardown iface and perform necessary cleanup */
    private void onInterfaceDestroyed(@NonNull Iface iface) {
        synchronized (mLock) {
            if (iface.type == Iface.IFACE_TYPE_STA_FOR_CONNECTIVITY) {
                onClientInterfaceForConnectivityDestroyed(iface);
            } else if (iface.type == Iface.IFACE_TYPE_STA_FOR_SCAN) {
                onClientInterfaceForScanDestroyed(iface);
            } else if (iface.type == Iface.IFACE_TYPE_AP) {
                onSoftApInterfaceDestroyed(iface);
            }
            // Invoke the external callback.
            iface.externalListener.onDestroyed(iface.name);
        }
    }

    /**
     * Callback to be invoked by HalDeviceManager when an interface is destroyed.
     */
    private class InterfaceDestoyedListenerInternal
            implements HalDeviceManager.InterfaceDestroyedListener {
        /** Identifier allocated for the interface */
        private final int mInterfaceId;

        InterfaceDestoyedListenerInternal(int ifaceId) {
            mInterfaceId = ifaceId;
        }

        @Override
        public void onDestroyed(@NonNull String ifaceName) {
            synchronized (mLock) {
                final Iface iface = mIfaceMgr.removeIface(mInterfaceId);
                if (iface == null) {
                    if (mVerboseLoggingEnabled) {
                        Log.v(TAG, "Received iface destroyed notification on an invalid iface="
                                + ifaceName);
                    }
                    return;
                }
                onInterfaceDestroyed(iface);
                Log.i(TAG, "Successfully torn down " + iface);
            }
        }
    }

    /**
     * Helper method invoked to trigger the status changed callback after one of the native
     * daemon's death.
     */
    private void onNativeDaemonDeath() {
        synchronized (mLock) {
            for (StatusListener listener : mStatusListeners) {
                listener.onStatusChanged(false);
            }
            for (StatusListener listener : mStatusListeners) {
                listener.onStatusChanged(true);
            }
        }
    }

    /**
     * Death handler for the Vendor HAL daemon.
     */
    private class VendorHalDeathHandlerInternal implements VendorHalDeathEventHandler {
        @Override
        public void onDeath() {
            synchronized (mLock) {
                Log.i(TAG, "Vendor HAL died. Cleaning up internal state.");
                onNativeDaemonDeath();
                mWifiMetrics.incrementNumHalCrashes();
            }
        }
    }

    /**
     * Death handler for the wificond daemon.
     */
    private class WificondDeathHandlerInternal implements Runnable {
        @Override
        public void run() {
            synchronized (mLock) {
                Log.i(TAG, "wificond died. Cleaning up internal state.");
                onNativeDaemonDeath();
                mWifiMetrics.incrementNumWificondCrashes();
            }
        }
    }

    /**
     * Death handler for the supplicant daemon.
     */
    private class SupplicantDeathHandlerInternal implements SupplicantDeathEventHandler {
        @Override
        public void onDeath() {
            synchronized (mLock) {
                Log.i(TAG, "wpa_supplicant died. Cleaning up internal state.");
                onNativeDaemonDeath();
                mWifiMetrics.incrementNumSupplicantCrashes();
            }
        }
    }

    /**
     * Death handler for the hostapd daemon.
     */
    private class HostapdDeathHandlerInternal implements HostapdDeathEventHandler {
        @Override
        public void onDeath() {
            synchronized (mLock) {
                Log.i(TAG, "hostapd died. Cleaning up internal state.");
                onNativeDaemonDeath();
                mWifiMetrics.incrementNumHostapdCrashes();
            }
        }
    }

    /** Helper method invoked to handle interface change. */
    private void onInterfaceStateChanged(Iface iface, boolean isUp) {
        synchronized (mLock) {
            // Mask multiple notifications with the same state.
            if (isUp == iface.isUp) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Interface status unchanged on " + iface + " from " + isUp
                            + ", Ignoring...");
                }
                return;
            }
            Log.i(TAG, "Interface state changed on " + iface + ", isUp=" + isUp);
            if (isUp) {
                iface.externalListener.onUp(iface.name);
            } else {
                iface.externalListener.onDown(iface.name);
                if (iface.type == Iface.IFACE_TYPE_STA_FOR_CONNECTIVITY
                        || iface.type == Iface.IFACE_TYPE_STA_FOR_SCAN) {
                    mWifiMetrics.incrementNumClientInterfaceDown();
                } else if (iface.type == Iface.IFACE_TYPE_AP) {
                    mWifiMetrics.incrementNumSoftApInterfaceDown();
                }
            }
            iface.isUp = isUp;
        }
    }

    /**
     * Network observer to use for all interface up/down notifications.
     */
    private class NetworkObserverInternal implements NetdEventObserver {
        /** Identifier allocated for the interface */
        private final int mInterfaceId;

        NetworkObserverInternal(int id) {
            mInterfaceId = id;
        }

        /**
         * Note: We should ideally listen to
         * {@link NetdEventObserver#interfaceStatusChanged(String, boolean)} here. But, that
         * callback is not working currently (broken in netd). So, instead listen to link state
         * change callbacks as triggers to query the real interface state. We should get rid of
         * this workaround if we get the |interfaceStatusChanged| callback to work in netd.
         * Also, this workaround will not detect an interface up event, if the link state is
         * still down.
         */
        @Override
        public void interfaceLinkStateChanged(String ifaceName, boolean unusedIsLinkUp) {
            // This is invoked from the main system_server thread. Post to our handler.
            mHandler.post(() -> {
                synchronized (mLock) {
                    final Iface ifaceWithId = mIfaceMgr.getIface(mInterfaceId);
                    if (ifaceWithId == null) {
                        if (mVerboseLoggingEnabled) {
                            Log.v(TAG, "Received iface link up/down notification on an invalid"
                                    + " iface=" + mInterfaceId);
                        }
                        return;
                    }
                    final Iface ifaceWithName = mIfaceMgr.getIface(ifaceName);
                    if (ifaceWithName == null || ifaceWithName != ifaceWithId) {
                        if (mVerboseLoggingEnabled) {
                            Log.v(TAG, "Received iface link up/down notification on an invalid"
                                    + " iface=" + ifaceName);
                        }
                        return;
                    }
                    onInterfaceStateChanged(ifaceWithName, isInterfaceUp(ifaceName));
                }
            });
        }

        @Override
        public void interfaceStatusChanged(String ifaceName, boolean unusedIsLinkUp) {
            // unused currently. Look at note above.
        }
    }

    /**
     * Radio mode change handler for the Vendor HAL daemon.
     */
    private class VendorHalRadioModeChangeHandlerInternal
            implements VendorHalRadioModeChangeEventHandler {
        @Override
        public void onMcc(int band) {
            synchronized (mLock) {
                Log.i(TAG, "Device is in MCC mode now");
                mWifiMetrics.incrementNumRadioModeChangeToMcc();
            }
        }
        @Override
        public void onScc(int band) {
            synchronized (mLock) {
                Log.i(TAG, "Device is in SCC mode now");
                mWifiMetrics.incrementNumRadioModeChangeToScc();
            }
        }
        @Override
        public void onSbs(int band) {
            synchronized (mLock) {
                Log.i(TAG, "Device is in SBS mode now");
                mWifiMetrics.incrementNumRadioModeChangeToSbs();
            }
        }
        @Override
        public void onDbs() {
            synchronized (mLock) {
                Log.i(TAG, "Device is in DBS mode now");
                mWifiMetrics.incrementNumRadioModeChangeToDbs();
            }
        }
    }

    // For devices that don't support the vendor HAL, we will not support any concurrency.
    // So simulate the HalDeviceManager behavior by triggering the destroy listener for
    // any active interface.
    private String handleIfaceCreationWhenVendorHalNotSupported(@NonNull Iface newIface) {
        synchronized (mLock) {
            Iface existingIface = mIfaceMgr.removeExistingIface(newIface.id);
            if (existingIface != null) {
                onInterfaceDestroyed(existingIface);
                Log.i(TAG, "Successfully torn down " + existingIface);
            }
            // Return the interface name directly from the system property.
            return mPropertyService.getString("wifi.interface", "wlan0");
        }
    }

    /**
     * Helper function to handle creation of STA iface.
     * For devices which do not the support the HAL, this will bypass HalDeviceManager &
     * teardown any existing iface.
     */
    private String createStaIface(@NonNull Iface iface) {
        synchronized (mLock) {
            if (mWifiVendorHal.isVendorHalSupported()) {
                return mWifiVendorHal.createStaIface(
                        new InterfaceDestoyedListenerInternal(iface.id));
            } else {
                Log.i(TAG, "Vendor Hal not supported, ignoring createStaIface.");
                return handleIfaceCreationWhenVendorHalNotSupported(iface);
            }
        }
    }

    /**
     * Helper function to handle creation of AP iface.
     * For devices which do not the support the HAL, this will bypass HalDeviceManager &
     * teardown any existing iface.
     */
    private String createApIface(@NonNull Iface iface) {
        synchronized (mLock) {
            if (mWifiVendorHal.isVendorHalSupported()) {
                return mWifiVendorHal.createApIface(
                        new InterfaceDestoyedListenerInternal(iface.id));
            } else {
                Log.i(TAG, "Vendor Hal not supported, ignoring createApIface.");
                return handleIfaceCreationWhenVendorHalNotSupported(iface);
            }
        }
    }

    // For devices that don't support the vendor HAL, we will not support any concurrency.
    // So simulate the HalDeviceManager behavior by triggering the destroy listener for
    // the interface.
    private boolean handleIfaceRemovalWhenVendorHalNotSupported(@NonNull Iface iface) {
        synchronized (mLock) {
            mIfaceMgr.removeIface(iface.id);
            onInterfaceDestroyed(iface);
            Log.i(TAG, "Successfully torn down " + iface);
            return true;
        }
    }

    /**
     * Helper function to handle removal of STA iface.
     * For devices which do not the support the HAL, this will bypass HalDeviceManager &
     * teardown any existing iface.
     */
    private boolean removeStaIface(@NonNull Iface iface) {
        synchronized (mLock) {
            if (mWifiVendorHal.isVendorHalSupported()) {
                return mWifiVendorHal.removeStaIface(iface.name);
            } else {
                Log.i(TAG, "Vendor Hal not supported, ignoring removeStaIface.");
                return handleIfaceRemovalWhenVendorHalNotSupported(iface);
            }
        }
    }

    /**
     * Helper function to handle removal of STA iface.
     */
    private boolean removeApIface(@NonNull Iface iface) {
        synchronized (mLock) {
            if (mWifiVendorHal.isVendorHalSupported()) {
                return mWifiVendorHal.removeApIface(iface.name);
            } else {
                Log.i(TAG, "Vendor Hal not supported, ignoring removeApIface.");
                return handleIfaceRemovalWhenVendorHalNotSupported(iface);
            }
        }
    }

    /**
     * Initialize the native modules.
     *
     * @return true on success, false otherwise.
     */
    public boolean initialize() {
        synchronized (mLock) {
            if (!mWifiVendorHal.initialize(new VendorHalDeathHandlerInternal())) {
                Log.e(TAG, "Failed to initialize vendor HAL");
                return false;
            }
            mWifiCondManager.setOnServiceDeadCallback(new WificondDeathHandlerInternal());
            mWifiCondManager.tearDownInterfaces();
            mWifiVendorHal.registerRadioModeChangeHandler(
                    new VendorHalRadioModeChangeHandlerInternal());
            mNetdWrapper = mWifiInjector.makeNetdWrapper();
            return true;
        }
    }

    /**
     * Callback to notify when the status of one of the native daemons
     * (wificond, wpa_supplicant & vendor HAL) changes.
     */
    public interface StatusListener {
        /**
         * @param allReady Indicates if all the native daemons are ready for operation or not.
         */
        void onStatusChanged(boolean allReady);
    }

    /**
     * Register a StatusListener to get notified about any status changes from the native daemons.
     *
     * It is safe to re-register the same callback object - duplicates are detected and only a
     * single copy kept.
     *
     * @param listener StatusListener listener object.
     */
    public void registerStatusListener(@NonNull StatusListener listener) {
        mStatusListeners.add(listener);
    }

    /**
     * Callback to notify when the availability of an interface has changed.
     */
    public interface InterfaceAvailableForRequestListener {
        /**
         * @param isAvailable Whether it is possible to create an iface of the specified type or
         *                    not.
         */
        void onAvailabilityChanged(boolean isAvailable);
    }

    /**
     * Register a callback to notify when the availability of Client interface has changed.
     *
     * It is safe to re-register the same callback object - duplicates are detected and only a
     * single copy kept.
     *
     * @param listener Instance of {@link InterfaceAvailableForRequestListener}.
     */
    public void registerClientInterfaceAvailabilityListener(
            @NonNull InterfaceAvailableForRequestListener listener) {
        mWifiVendorHal.registerStaIfaceAvailabilityListener(listener);
    }

    /**
     * Register a callback to notify when the availability of SoftAp interface has changed.
     *
     * It is safe to re-register the same callback object - duplicates are detected and only a
     * single copy kept.
     *
     * @param listener Instance of {@link InterfaceAvailableForRequestListener}.
     */
    public void registerSoftApInterfaceAvailabilityListener(
            @NonNull InterfaceAvailableForRequestListener listener) {
        mWifiVendorHal.registerApIfaceAvailabilityListener(listener);
    }

    /**
     * Callback to notify when the associated interface is destroyed, up or down.
     */
    public interface InterfaceCallback {
        /**
         * Interface destroyed by HalDeviceManager.
         *
         * @param ifaceName Name of the iface.
         */
        void onDestroyed(String ifaceName);

        /**
         * Interface is up.
         *
         * @param ifaceName Name of the iface.
         */
        void onUp(String ifaceName);

        /**
         * Interface is down.
         *
         * @param ifaceName Name of the iface.
         */
        void onDown(String ifaceName);
    }

    private void initializeNwParamsForClientInterface(@NonNull String ifaceName) {
        try {
            // A runtime crash or shutting down AP mode can leave
            // IP addresses configured, and this affects
            // connectivity when supplicant starts up.
            // Ensure we have no IP addresses before a supplicant start.
            mNetdWrapper.clearInterfaceAddresses(ifaceName);

            // Set privacy extensions
            mNetdWrapper.setInterfaceIpv6PrivacyExtensions(ifaceName, true);

            // IPv6 is enabled only as long as access point is connected since:
            // - IPv6 addresses and routes stick around after disconnection
            // - kernel is unaware when connected and fails to start IPv6 negotiation
            // - kernel can start autoconfiguration when 802.1x is not complete
            mNetdWrapper.disableIpv6(ifaceName);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Unable to change interface settings", e);
        }
    }

    /**
     * Setup an interface for client mode (for connectivity) operations.
     *
     * This method configures an interface in STA mode in all the native daemons
     * (wificond, wpa_supplicant & vendor HAL).
     *
     * @param interfaceCallback Associated callback for notifying status changes for the iface.
     * @return Returns the name of the allocated interface, will be null on failure.
     */
    public String setupInterfaceForClientInConnectivityMode(
            @NonNull InterfaceCallback interfaceCallback) {
        synchronized (mLock) {
            if (!startHal()) {
                Log.e(TAG, "Failed to start Hal");
                mWifiMetrics.incrementNumSetupClientInterfaceFailureDueToHal();
                return null;
            }
            if (!startSupplicant()) {
                Log.e(TAG, "Failed to start supplicant");
                mWifiMetrics.incrementNumSetupClientInterfaceFailureDueToSupplicant();
                return null;
            }
            Iface iface = mIfaceMgr.allocateIface(Iface.IFACE_TYPE_STA_FOR_CONNECTIVITY);
            if (iface == null) {
                Log.e(TAG, "Failed to allocate new STA iface");
                return null;
            }
            iface.externalListener = interfaceCallback;
            iface.name = createStaIface(iface);
            if (TextUtils.isEmpty(iface.name)) {
                Log.e(TAG, "Failed to create STA iface in vendor HAL");
                mIfaceMgr.removeIface(iface.id);
                mWifiMetrics.incrementNumSetupClientInterfaceFailureDueToHal();
                return null;
            }
            if (!mWifiCondManager.setupInterfaceForClientMode(iface.name, Runnable::run,
                    new NormalScanEventCallback(iface.name),
                    new PnoScanEventCallback(iface.name))) {
                Log.e(TAG, "Failed to setup iface in wificond on " + iface);
                teardownInterface(iface.name);
                mWifiMetrics.incrementNumSetupClientInterfaceFailureDueToWificond();
                return null;
            }
            if (!mSupplicantStaIfaceHal.setupIface(iface.name)) {
                Log.e(TAG, "Failed to setup iface in supplicant on " + iface);
                teardownInterface(iface.name);
                mWifiMetrics.incrementNumSetupClientInterfaceFailureDueToSupplicant();
                return null;
            }
            iface.networkObserver = new NetworkObserverInternal(iface.id);
            if (!registerNetworkObserver(iface.networkObserver)) {
                Log.e(TAG, "Failed to register network observer on " + iface);
                teardownInterface(iface.name);
                return null;
            }
            mWifiMonitor.startMonitoring(iface.name);
            // Just to avoid any race conditions with interface state change callbacks,
            // update the interface state before we exit.
            onInterfaceStateChanged(iface, isInterfaceUp(iface.name));
            initializeNwParamsForClientInterface(iface.name);
            Log.i(TAG, "Successfully setup " + iface);

            iface.featureSet = getSupportedFeatureSetInternal(iface.name);
            return iface.name;
        }
    }

    /**
     * Setup an interface for client mode (for scan) operations.
     *
     * This method configures an interface in STA mode in the native daemons
     * (wificond, vendor HAL).
     *
     * @param interfaceCallback Associated callback for notifying status changes for the iface.
     * @return Returns the name of the allocated interface, will be null on failure.
     */
    public String setupInterfaceForClientInScanMode(
            @NonNull InterfaceCallback interfaceCallback) {
        synchronized (mLock) {
            if (!startHal()) {
                Log.e(TAG, "Failed to start Hal");
                mWifiMetrics.incrementNumSetupClientInterfaceFailureDueToHal();
                return null;
            }
            Iface iface = mIfaceMgr.allocateIface(Iface.IFACE_TYPE_STA_FOR_SCAN);
            if (iface == null) {
                Log.e(TAG, "Failed to allocate new STA iface");
                return null;
            }
            iface.externalListener = interfaceCallback;
            iface.name = createStaIface(iface);
            if (TextUtils.isEmpty(iface.name)) {
                Log.e(TAG, "Failed to create iface in vendor HAL");
                mIfaceMgr.removeIface(iface.id);
                mWifiMetrics.incrementNumSetupClientInterfaceFailureDueToHal();
                return null;
            }
            if (!mWifiCondManager.setupInterfaceForClientMode(iface.name, Runnable::run,
                    new NormalScanEventCallback(iface.name),
                    new PnoScanEventCallback(iface.name))) {
                Log.e(TAG, "Failed to setup iface in wificond=" + iface.name);
                teardownInterface(iface.name);
                mWifiMetrics.incrementNumSetupClientInterfaceFailureDueToWificond();
                return null;
            }
            iface.networkObserver = new NetworkObserverInternal(iface.id);
            if (!registerNetworkObserver(iface.networkObserver)) {
                Log.e(TAG, "Failed to register network observer for iface=" + iface.name);
                teardownInterface(iface.name);
                return null;
            }
            mWifiMonitor.startMonitoring(iface.name);
            // Just to avoid any race conditions with interface state change callbacks,
            // update the interface state before we exit.
            onInterfaceStateChanged(iface, isInterfaceUp(iface.name));
            Log.i(TAG, "Successfully setup " + iface);

            iface.featureSet = getSupportedFeatureSetInternal(iface.name);
            return iface.name;
        }
    }

    /**
     * Setup an interface for Soft AP mode operations.
     *
     * This method configures an interface in AP mode in all the native daemons
     * (wificond, wpa_supplicant & vendor HAL).
     *
     * @param interfaceCallback Associated callback for notifying status changes for the iface.
     * @return Returns the name of the allocated interface, will be null on failure.
     */
    public String setupInterfaceForSoftApMode(@NonNull InterfaceCallback interfaceCallback) {
        synchronized (mLock) {
            if (!startHal()) {
                Log.e(TAG, "Failed to start Hal");
                mWifiMetrics.incrementNumSetupSoftApInterfaceFailureDueToHal();
                return null;
            }
            if (!startHostapd()) {
                Log.e(TAG, "Failed to start hostapd");
                mWifiMetrics.incrementNumSetupSoftApInterfaceFailureDueToHostapd();
                return null;
            }
            Iface iface = mIfaceMgr.allocateIface(Iface.IFACE_TYPE_AP);
            if (iface == null) {
                Log.e(TAG, "Failed to allocate new AP iface");
                return null;
            }
            iface.externalListener = interfaceCallback;
            iface.name = createApIface(iface);
            if (TextUtils.isEmpty(iface.name)) {
                Log.e(TAG, "Failed to create AP iface in vendor HAL");
                mIfaceMgr.removeIface(iface.id);
                mWifiMetrics.incrementNumSetupSoftApInterfaceFailureDueToHal();
                return null;
            }
            if (!mWifiCondManager.setupInterfaceForSoftApMode(iface.name)) {
                Log.e(TAG, "Failed to setup iface in wificond on " + iface);
                teardownInterface(iface.name);
                mWifiMetrics.incrementNumSetupSoftApInterfaceFailureDueToWificond();
                return null;
            }
            iface.networkObserver = new NetworkObserverInternal(iface.id);
            if (!registerNetworkObserver(iface.networkObserver)) {
                Log.e(TAG, "Failed to register network observer on " + iface);
                teardownInterface(iface.name);
                return null;
            }
            // Just to avoid any race conditions with interface state change callbacks,
            // update the interface state before we exit.
            onInterfaceStateChanged(iface, isInterfaceUp(iface.name));
            Log.i(TAG, "Successfully setup " + iface);

            iface.featureSet = getSupportedFeatureSetInternal(iface.name);
            return iface.name;
        }
    }

    /**
     * Switches an existing Client mode interface from connectivity
     * {@link Iface#IFACE_TYPE_STA_FOR_CONNECTIVITY} to scan mode
     * {@link Iface#IFACE_TYPE_STA_FOR_SCAN}.
     *
     * @param ifaceName Name of the interface.
     * @return true if the operation succeeded, false if there is an error or the iface is already
     * in scan mode.
     */
    public boolean switchClientInterfaceToScanMode(@NonNull String ifaceName) {
        synchronized (mLock) {
            final Iface iface = mIfaceMgr.getIface(ifaceName);
            if (iface == null) {
                Log.e(TAG, "Trying to switch to scan mode on an invalid iface=" + ifaceName);
                return false;
            }
            if (iface.type == Iface.IFACE_TYPE_STA_FOR_SCAN) {
                Log.e(TAG, "Already in scan mode on iface=" + ifaceName);
                return true;
            }
            if (!mSupplicantStaIfaceHal.teardownIface(iface.name)) {
                Log.e(TAG, "Failed to teardown iface in supplicant on " + iface);
                teardownInterface(iface.name);
                return false;
            }
            iface.type = Iface.IFACE_TYPE_STA_FOR_SCAN;
            stopSupplicantIfNecessary();
            iface.featureSet = getSupportedFeatureSetInternal(iface.name);
            iface.phyCapabilities = null;
            Log.i(TAG, "Successfully switched to scan mode on iface=" + iface);
            return true;
        }
    }

    /**
     * Switches an existing Client mode interface from scan mode
     * {@link Iface#IFACE_TYPE_STA_FOR_SCAN} to connectivity mode
     * {@link Iface#IFACE_TYPE_STA_FOR_CONNECTIVITY}.
     *
     * @param ifaceName Name of the interface.
     * @return true if the operation succeeded, false if there is an error or the iface is already
     * in scan mode.
     */
    public boolean switchClientInterfaceToConnectivityMode(@NonNull String ifaceName) {
        synchronized (mLock) {
            final Iface iface = mIfaceMgr.getIface(ifaceName);
            if (iface == null) {
                Log.e(TAG, "Trying to switch to connectivity mode on an invalid iface="
                        + ifaceName);
                return false;
            }
            if (iface.type == Iface.IFACE_TYPE_STA_FOR_CONNECTIVITY) {
                Log.e(TAG, "Already in connectivity mode on iface=" + ifaceName);
                return true;
            }
            if (!startSupplicant()) {
                Log.e(TAG, "Failed to start supplicant");
                teardownInterface(iface.name);
                mWifiMetrics.incrementNumSetupClientInterfaceFailureDueToSupplicant();
                return false;
            }
            if (!mSupplicantStaIfaceHal.setupIface(iface.name)) {
                Log.e(TAG, "Failed to setup iface in supplicant on " + iface);
                teardownInterface(iface.name);
                mWifiMetrics.incrementNumSetupClientInterfaceFailureDueToSupplicant();
                return false;
            }
            iface.type = Iface.IFACE_TYPE_STA_FOR_CONNECTIVITY;
            iface.featureSet = getSupportedFeatureSetInternal(iface.name);
            Log.i(TAG, "Successfully switched to connectivity mode on iface=" + iface);
            return true;
        }
    }

    /**
     *
     * Check if the interface is up or down.
     *
     * @param ifaceName Name of the interface.
     * @return true if iface is up, false if it's down or on error.
     */
    public boolean isInterfaceUp(@NonNull String ifaceName) {
        synchronized (mLock) {
            final Iface iface = mIfaceMgr.getIface(ifaceName);
            if (iface == null) {
                Log.e(TAG, "Trying to get iface state on invalid iface=" + ifaceName);
                return false;
            }
            try {
                return mNetdWrapper.isInterfaceUp(ifaceName);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Unable to get interface config", e);
                return false;
            }
        }
    }

    /**
     * Teardown an interface in Client/AP mode.
     *
     * This method tears down the associated interface from all the native daemons
     * (wificond, wpa_supplicant & vendor HAL).
     * Also, brings down the HAL, supplicant or hostapd as necessary.
     *
     * @param ifaceName Name of the interface.
     */
    public void teardownInterface(@NonNull String ifaceName) {
        synchronized (mLock) {
            final Iface iface = mIfaceMgr.getIface(ifaceName);
            if (iface == null) {
                Log.e(TAG, "Trying to teardown an invalid iface=" + ifaceName);
                return;
            }
            // Trigger the iface removal from HAL. The rest of the cleanup will be triggered
            // from the interface destroyed callback.
            if (iface.type == Iface.IFACE_TYPE_STA_FOR_CONNECTIVITY
                    || iface.type == Iface.IFACE_TYPE_STA_FOR_SCAN) {
                if (!removeStaIface(iface)) {
                    Log.e(TAG, "Failed to remove iface in vendor HAL=" + ifaceName);
                    return;
                }
            } else if (iface.type == Iface.IFACE_TYPE_AP) {
                if (!removeApIface(iface)) {
                    Log.e(TAG, "Failed to remove iface in vendor HAL=" + ifaceName);
                    return;
                }
            }
            Log.i(TAG, "Successfully initiated teardown for iface=" + ifaceName);
        }
    }

    /**
     * Teardown all the active interfaces.
     *
     * This method tears down the associated interfaces from all the native daemons
     * (wificond, wpa_supplicant & vendor HAL).
     * Also, brings down the HAL, supplicant or hostapd as necessary.
     */
    public void teardownAllInterfaces() {
        synchronized (mLock) {
            Iterator<Integer> ifaceIdIter = mIfaceMgr.getIfaceIdIter();
            while (ifaceIdIter.hasNext()) {
                Iface iface = mIfaceMgr.getIface(ifaceIdIter.next());
                ifaceIdIter.remove();
                onInterfaceDestroyed(iface);
                Log.i(TAG, "Successfully torn down " + iface);
            }
            Log.i(TAG, "Successfully torn down all ifaces");
        }
    }

    /**
     * Get name of the client interface.
     *
     * This is mainly used by external modules that needs to perform some
     * client operations on the STA interface.
     *
     * TODO(b/70932231): This may need to be reworked once we start supporting STA + STA.
     *
     * @return Interface name of any active client interface, null if no active client interface
     * exist.
     * Return Values for the different scenarios are listed below:
     * a) When there are no client interfaces, returns null.
     * b) when there is 1 client interface, returns the name of that interface.
     * c) When there are 2 or more client interface, returns the name of any client interface.
     */
    public String getClientInterfaceName() {
        synchronized (mLock) {
            return mIfaceMgr.findAnyStaIfaceName();
        }
    }

    /**
     * Get names of all the client interfaces.
     *
     * @return List of interface name of all active client interfaces.
     */
    public Set<String> getClientInterfaceNames() {
        synchronized (mLock) {
            return mIfaceMgr.findAllStaIfaceNames();
        }
    }

    /**
     * Get name of the softap interface.
     *
     * This is mainly used by external modules that needs to perform some
     * operations on the AP interface.
     *
     * TODO(b/70932231): This may need to be reworked once we start supporting AP + AP.
     *
     * @return Interface name of any active softap interface, null if no active softap interface
     * exist.
     * Return Values for the different scenarios are listed below:
     * a) When there are no softap interfaces, returns null.
     * b) when there is 1 softap interface, returns the name of that interface.
     * c) When there are 2 or more softap interface, returns the name of any softap interface.
     */
    public String getSoftApInterfaceName() {
        synchronized (mLock) {
            return mIfaceMgr.findAnyApIfaceName();
        }
    }

    /********************************************************
     * Wificond operations
     ********************************************************/

    /**
     * Request signal polling to wificond.
     *
     * @param ifaceName Name of the interface.
     * Returns an SignalPollResult object.
     * Returns null on failure.
     */
    public WifiNl80211Manager.SignalPollResult signalPoll(@NonNull String ifaceName) {
        return mWifiCondManager.signalPoll(ifaceName);
    }

    /**
     * Query the list of valid frequencies for the provided band.
     * The result depends on the on the country code that has been set.
     *
     * @param band as specified by one of the WifiScanner.WIFI_BAND_* constants.
     * The following bands are supported {@link WifiAnnotations.WifiBandBasic}:
     * WifiScanner.WIFI_BAND_24_GHZ
     * WifiScanner.WIFI_BAND_5_GHZ
     * WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY
     * WifiScanner.WIFI_BAND_6_GHZ
     * @return frequencies vector of valid frequencies (MHz), or null for error.
     * @throws IllegalArgumentException if band is not recognized.
     */
    public int [] getChannelsForBand(@WifiAnnotations.WifiBandBasic int band) {
        return mWifiCondManager.getChannelsMhzForBand(band);
    }

    /**
     * Start a scan using wificond for the given parameters.
     * @param ifaceName Name of the interface.
     * @param scanType Type of scan to perform. One of {@link WifiScanner#SCAN_TYPE_LOW_LATENCY},
     * {@link WifiScanner#SCAN_TYPE_LOW_POWER} or {@link WifiScanner#SCAN_TYPE_HIGH_ACCURACY}.
     * @param freqs list of frequencies to scan for, if null scan all supported channels.
     * @param hiddenNetworkSSIDs List of hidden networks to be scanned for.
     * @return Returns true on success.
     */
    public boolean scan(
            @NonNull String ifaceName, @WifiAnnotations.ScanType int scanType, Set<Integer> freqs,
            List<String> hiddenNetworkSSIDs) {
        List<byte[]> hiddenNetworkSsidsArrays = new ArrayList<>();
        for (String hiddenNetworkSsid : hiddenNetworkSSIDs) {
            try {
                hiddenNetworkSsidsArrays.add(
                        NativeUtil.byteArrayFromArrayList(
                                NativeUtil.decodeSsid(hiddenNetworkSsid)));
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Illegal argument " + hiddenNetworkSsid, e);
                continue;
            }
        }
        return mWifiCondManager.startScan(ifaceName, scanType, freqs, hiddenNetworkSsidsArrays);
    }

    /**
     * Fetch the latest scan result from kernel via wificond.
     * @param ifaceName Name of the interface.
     * @return Returns an ArrayList of ScanDetail.
     * Returns an empty ArrayList on failure.
     */
    public ArrayList<ScanDetail> getScanResults(@NonNull String ifaceName) {
        return convertNativeScanResults(mWifiCondManager.getScanResults(
                ifaceName, WifiNl80211Manager.SCAN_TYPE_SINGLE_SCAN));
    }

    /**
     * Fetch the latest scan result from kernel via wificond.
     * @param ifaceName Name of the interface.
     * @return Returns an ArrayList of ScanDetail.
     * Returns an empty ArrayList on failure.
     */
    public ArrayList<ScanDetail> getPnoScanResults(@NonNull String ifaceName) {
        return convertNativeScanResults(mWifiCondManager.getScanResults(ifaceName,
                WifiNl80211Manager.SCAN_TYPE_PNO_SCAN));
    }

    private ArrayList<ScanDetail> convertNativeScanResults(List<NativeScanResult> nativeResults) {
        ArrayList<ScanDetail> results = new ArrayList<>();
        for (NativeScanResult result : nativeResults) {
            WifiSsid wifiSsid = WifiSsid.createFromByteArray(result.getSsid());
            MacAddress bssidMac = result.getBssid();
            if (bssidMac == null) {
                Log.e(TAG, "Invalid MAC (BSSID) for SSID " + wifiSsid);
                continue;
            }
            String bssid = bssidMac.toString();
            ScanResult.InformationElement[] ies =
                    InformationElementUtil.parseInformationElements(result.getInformationElements());
            InformationElementUtil.Capabilities capabilities =
                    new InformationElementUtil.Capabilities();
            capabilities.from(ies, result.getCapabilities(), isEnhancedOpenSupported());
            String flags = capabilities.generateCapabilitiesString();
            NetworkDetail networkDetail;
            try {
                networkDetail = new NetworkDetail(bssid, ies, null, result.getFrequencyMhz());
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Illegal argument for scan result with bssid: " + bssid, e);
                continue;
            }

            ScanDetail scanDetail = new ScanDetail(networkDetail, wifiSsid, bssid, flags,
                    result.getSignalMbm() / 100, result.getFrequencyMhz(), result.getTsf(), ies,
                    null, result.getInformationElements());
            ScanResult scanResult = scanDetail.getScanResult();
            scanResult.setWifiStandard(wifiModeToWifiStandard(networkDetail.getWifiMode()));

            // Fill up the radio chain info.
            scanResult.radioChainInfos =
                    new ScanResult.RadioChainInfo[result.getRadioChainInfos().size()];
            int idx = 0;
            for (RadioChainInfo nativeRadioChainInfo : result.getRadioChainInfos()) {
                scanResult.radioChainInfos[idx] = new ScanResult.RadioChainInfo();
                scanResult.radioChainInfos[idx].id = nativeRadioChainInfo.getChainId();
                scanResult.radioChainInfos[idx].level = nativeRadioChainInfo.getLevelDbm();
                idx++;
            }
            results.add(scanDetail);
        }
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "get " + results.size() + " scan results from wificond");
        }

        return results;
    }

    @WifiAnnotations.WifiStandard
    private static int wifiModeToWifiStandard(int wifiMode) {
        switch (wifiMode) {
            case InformationElementUtil.WifiMode.MODE_11A:
            case InformationElementUtil.WifiMode.MODE_11B:
            case InformationElementUtil.WifiMode.MODE_11G:
                return ScanResult.WIFI_STANDARD_LEGACY;
            case InformationElementUtil.WifiMode.MODE_11N:
                return ScanResult.WIFI_STANDARD_11N;
            case InformationElementUtil.WifiMode.MODE_11AC:
                return ScanResult.WIFI_STANDARD_11AC;
            case InformationElementUtil.WifiMode.MODE_11AX:
                return ScanResult.WIFI_STANDARD_11AX;
            case InformationElementUtil.WifiMode.MODE_UNDEFINED:
            default:
                return ScanResult.WIFI_STANDARD_UNKNOWN;
        }
    }

    private boolean mIsEnhancedOpenSupportedInitialized = false;
    private boolean mIsEnhancedOpenSupported;

    /**
     * Check if OWE (Enhanced Open) is supported on the device
     *
     * @return true if OWE is supported
     */
    private boolean isEnhancedOpenSupported() {
        if (mIsEnhancedOpenSupportedInitialized) {
            return mIsEnhancedOpenSupported;
        }

        String iface = getClientInterfaceName();
        if (iface == null) {
            // Client interface might not be initialized during boot or Wi-Fi off
            return false;
        }

        mIsEnhancedOpenSupportedInitialized = true;
        mIsEnhancedOpenSupported = (getSupportedFeatureSet(iface) & WIFI_FEATURE_OWE) != 0;
        return mIsEnhancedOpenSupported;
    }

    /**
     * Start PNO scan.
     * @param ifaceName Name of the interface.
     * @param pnoSettings Pno scan configuration.
     * @return true on success.
     */
    public boolean startPnoScan(@NonNull String ifaceName, PnoSettings pnoSettings) {
        return mWifiCondManager.startPnoScan(ifaceName, pnoSettings.toNativePnoSettings(),
                Runnable::run,
                new WifiNl80211Manager.PnoScanRequestCallback() {
                    @Override
                    public void onPnoRequestSucceeded() {
                        mWifiMetrics.incrementPnoScanStartAttemptCount();
                    }

                    @Override
                    public void onPnoRequestFailed() {
                        mWifiMetrics.incrementPnoScanStartAttemptCount();
                        mWifiMetrics.incrementPnoScanFailedCount();
                    }
                });
    }

    /**
     * Stop PNO scan.
     * @param ifaceName Name of the interface.
     * @return true on success.
     */
    public boolean stopPnoScan(@NonNull String ifaceName) {
        return mWifiCondManager.stopPnoScan(ifaceName);
    }

    /**
     * Sends an arbitrary 802.11 management frame on the current channel.
     *
     * @param ifaceName Name of the interface.
     * @param frame Bytes of the 802.11 management frame to be sent, including the header, but not
     *              including the frame check sequence (FCS).
     * @param callback A callback triggered when the transmitted frame is ACKed or the transmission
     *                 fails.
     * @param mcs The MCS index that the frame will be sent at. If mcs < 0, the driver will select
     *            the rate automatically. If the device does not support sending the frame at a
     *            specified MCS rate, the transmission will be aborted and
     *            {@link WifiNl80211Manager.SendMgmtFrameCallback#onFailure(int)} will be called
     *            with reason {@link WifiNl80211Manager#SEND_MGMT_FRAME_ERROR_MCS_UNSUPPORTED}.
     */
    public void sendMgmtFrame(@NonNull String ifaceName, @NonNull byte[] frame,
            @NonNull WifiNl80211Manager.SendMgmtFrameCallback callback, int mcs) {
        mWifiCondManager.sendMgmtFrame(ifaceName, frame, mcs, Runnable::run, callback);
    }

    /**
     * Sends a probe request to the AP and waits for a response in order to determine whether
     * there is connectivity between the device and AP.
     *
     * @param ifaceName Name of the interface.
     * @param receiverMac the MAC address of the AP that the probe request will be sent to.
     * @param callback callback triggered when the probe was ACKed by the AP, or when
     *                an error occurs after the link probe was started.
     * @param mcs The MCS index that this probe will be sent at. If mcs < 0, the driver will select
     *            the rate automatically. If the device does not support sending the frame at a
     *            specified MCS rate, the transmission will be aborted and
     *            {@link WifiNl80211Manager.SendMgmtFrameCallback#onFailure(int)} will be called
     *            with reason {@link WifiNl80211Manager#SEND_MGMT_FRAME_ERROR_MCS_UNSUPPORTED}.
     */
    public void probeLink(@NonNull String ifaceName, @NonNull MacAddress receiverMac,
            @NonNull WifiNl80211Manager.SendMgmtFrameCallback callback, int mcs) {
        if (callback == null) {
            Log.e(TAG, "callback cannot be null!");
            return;
        }

        if (receiverMac == null) {
            Log.e(TAG, "Receiver MAC address cannot be null!");
            callback.onFailure(WifiNl80211Manager.SEND_MGMT_FRAME_ERROR_UNKNOWN);
            return;
        }

        String senderMacStr = getMacAddress(ifaceName);
        if (senderMacStr == null) {
            Log.e(TAG, "Failed to get this device's MAC Address");
            callback.onFailure(WifiNl80211Manager.SEND_MGMT_FRAME_ERROR_UNKNOWN);
            return;
        }

        byte[] frame = buildProbeRequestFrame(
                receiverMac.toByteArray(),
                NativeUtil.macAddressToByteArray(senderMacStr));
        sendMgmtFrame(ifaceName, frame, callback, mcs);
    }

    // header = 24 bytes, minimal body = 2 bytes, no FCS (will be added by driver)
    private static final int BASIC_PROBE_REQUEST_FRAME_SIZE = 24 + 2;

    private byte[] buildProbeRequestFrame(byte[] receiverMac, byte[] transmitterMac) {
        ByteBuffer frame = ByteBuffer.allocate(BASIC_PROBE_REQUEST_FRAME_SIZE);
        // ByteBuffer is big endian by default, switch to little endian
        frame.order(ByteOrder.LITTLE_ENDIAN);

        // Protocol version = 0, Type = management, Subtype = Probe Request
        frame.put((byte) 0x40);

        // no flags set
        frame.put((byte) 0x00);

        // duration = 60 microseconds. Note: this is little endian
        // Note: driver should calculate the duration and replace it before sending, putting a
        // reasonable default value here just in case.
        frame.putShort((short) 0x3c);

        // receiver/destination MAC address byte array
        frame.put(receiverMac);
        // sender MAC address byte array
        frame.put(transmitterMac);
        // BSSID (same as receiver address since we are sending to the AP)
        frame.put(receiverMac);

        // Generate random sequence number, fragment number = 0
        // Note: driver should replace the sequence number with the correct number that is
        // incremented from the last used sequence number. Putting a random sequence number as a
        // default here just in case.
        // bit 0 is least significant bit, bit 15 is most significant bit
        // bits [0, 7] go in byte 0
        // bits [8, 15] go in byte 1
        // bits [0, 3] represent the fragment number (which is 0)
        // bits [4, 15] represent the sequence number (which is random)
        // clear bits [0, 3] to set fragment number = 0
        short sequenceAndFragmentNumber = (short) (mRandom.nextInt() & 0xfff0);
        frame.putShort(sequenceAndFragmentNumber);

        // NL80211 rejects frames with an empty body, so we just need to put a placeholder
        // information element.
        // Tag for SSID
        frame.put((byte) 0x00);
        // Represents broadcast SSID. Not accurate, but works as placeholder.
        frame.put((byte) 0x00);

        return frame.array();
    }

    private static final int CONNECT_TO_HOSTAPD_RETRY_INTERVAL_MS = 100;
    private static final int CONNECT_TO_HOSTAPD_RETRY_TIMES = 50;
    /**
     * This method is called to wait for establishing connection to hostapd.
     *
     * @return true if connection is established, false otherwise.
     */
    private boolean startAndWaitForHostapdConnection() {
        // Start initialization if not already started.
        if (!mHostapdHal.isInitializationStarted()
                && !mHostapdHal.initialize()) {
            return false;
        }
        if (!mHostapdHal.startDaemon()) {
            Log.e(TAG, "Failed to startup hostapd");
            return false;
        }
        boolean connected = false;
        int connectTries = 0;
        while (!connected && connectTries++ < CONNECT_TO_HOSTAPD_RETRY_TIMES) {
            // Check if the initialization is complete.
            connected = mHostapdHal.isInitializationComplete();
            if (connected) {
                break;
            }
            try {
                Thread.sleep(CONNECT_TO_HOSTAPD_RETRY_INTERVAL_MS);
            } catch (InterruptedException ignore) {
            }
        }
        return connected;
    }

    /**
     * Start Soft AP operation using the provided configuration.
     *
     * @param ifaceName Name of the interface.
     * @param config Configuration to use for the soft ap created.
     * @param listener Callback for AP events.
     * @return true on success, false otherwise.
     */
    public boolean startSoftAp(
            @NonNull String ifaceName, SoftApConfiguration config, SoftApListener listener) {
        if (!mWifiCondManager.registerApCallback(ifaceName, Runnable::run, listener)) {
            Log.e(TAG, "Failed to register ap listener");
            return false;
        }
        if (!mHostapdHal.addAccessPoint(ifaceName, config, listener::onFailure)) {
            Log.e(TAG, "Failed to add acccess point");
            mWifiMetrics.incrementNumSetupSoftApInterfaceFailureDueToHostapd();
            return false;
        }
        return true;
    }

    /**
     * Force a softap client disconnect with specific reason code.
     *
     * @param ifaceName Name of the interface.
     * @param client Mac address to force disconnect in clients of the SoftAp.
     * @param reasonCode One of disconnect reason code which defined in {@link ApConfigUtil}.
     * @return true on success, false otherwise.
     */
    public boolean forceClientDisconnect(@NonNull String ifaceName,
            @NonNull MacAddress client, int reasonCode) {
        return mHostapdHal.forceClientDisconnect(ifaceName, client, reasonCode);
    }

    /**
     * Set MAC address of the given interface
     * @param interfaceName Name of the interface
     * @param mac Mac address to change into
     * @return true on success
     */
    public boolean setMacAddress(String interfaceName, MacAddress mac) {
        // TODO(b/72459123): Suppress interface down/up events from this call
        // Trigger an explicit disconnect to avoid losing the disconnect event reason (if currently
        // connected) from supplicant if the interface is brought down for MAC address change.
        disconnect(interfaceName);
        return mWifiVendorHal.setMacAddress(interfaceName, mac);
    }

    /**
     * Returns true if Hal version supports setMacAddress, otherwise false.
     *
     * @param interfaceName Name of the interface
     */
    public boolean isSetMacAddressSupported(@NonNull String interfaceName) {
        return mWifiVendorHal.isSetMacAddressSupported(interfaceName);
    }

    /**
     * Get the factory MAC address of the given interface
     * @param interfaceName Name of the interface.
     * @return factory MAC address, or null on a failed call or if feature is unavailable.
     */
    public MacAddress getFactoryMacAddress(@NonNull String interfaceName) {
        return mWifiVendorHal.getFactoryMacAddress(interfaceName);
    }

    /********************************************************
     * Hostapd operations
     ********************************************************/

    /**
     * Callback to notify hostapd death.
     */
    public interface HostapdDeathEventHandler {
        /**
         * Invoked when the supplicant dies.
         */
        void onDeath();
    }

    /********************************************************
     * Supplicant operations
     ********************************************************/

    /**
     * Callback to notify supplicant death.
     */
    public interface SupplicantDeathEventHandler {
        /**
         * Invoked when the supplicant dies.
         */
        void onDeath();
    }

    /**
     * Set supplicant log level
     *
     * @param turnOnVerbose Whether to turn on verbose logging or not.
     */
    public void setSupplicantLogLevel(boolean turnOnVerbose) {
        mSupplicantStaIfaceHal.setLogLevel(turnOnVerbose);
    }

    /**
     * Trigger a reconnection if the iface is disconnected.
     *
     * @param ifaceName Name of the interface.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean reconnect(@NonNull String ifaceName) {
        return mSupplicantStaIfaceHal.reconnect(ifaceName);
    }

    /**
     * Trigger a reassociation even if the iface is currently connected.
     *
     * @param ifaceName Name of the interface.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean reassociate(@NonNull String ifaceName) {
        return mSupplicantStaIfaceHal.reassociate(ifaceName);
    }

    /**
     * Trigger a disconnection from the currently connected network.
     *
     * @param ifaceName Name of the interface.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean disconnect(@NonNull String ifaceName) {
        return mSupplicantStaIfaceHal.disconnect(ifaceName);
    }

    /**
     * Makes a callback to HIDL to getMacAddress from supplicant
     *
     * @param ifaceName Name of the interface.
     * @return string containing the MAC address, or null on a failed call
     */
    public String getMacAddress(@NonNull String ifaceName) {
        return mSupplicantStaIfaceHal.getMacAddress(ifaceName);
    }

    public static final int RX_FILTER_TYPE_V4_MULTICAST = 0;
    public static final int RX_FILTER_TYPE_V6_MULTICAST = 1;
    /**
     * Start filtering out Multicast V4 packets
     * @param ifaceName Name of the interface.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     *
     * Multicast filtering rules work as follows:
     *
     * The driver can filter multicast (v4 and/or v6) and broadcast packets when in
     * a power optimized mode (typically when screen goes off).
     *
     * In order to prevent the driver from filtering the multicast/broadcast packets, we have to
     * add a DRIVER RXFILTER-ADD rule followed by DRIVER RXFILTER-START to make the rule effective
     *
     * DRIVER RXFILTER-ADD Num
     *   where Num = 0 - Unicast, 1 - Broadcast, 2 - Mutil4 or 3 - Multi6
     *
     * and DRIVER RXFILTER-START
     * In order to stop the usage of these rules, we do
     *
     * DRIVER RXFILTER-STOP
     * DRIVER RXFILTER-REMOVE Num
     *   where Num is as described for RXFILTER-ADD
     *
     * The  SETSUSPENDOPT driver command overrides the filtering rules
     */
    public boolean startFilteringMulticastV4Packets(@NonNull String ifaceName) {
        return mSupplicantStaIfaceHal.stopRxFilter(ifaceName)
                && mSupplicantStaIfaceHal.removeRxFilter(
                        ifaceName, RX_FILTER_TYPE_V4_MULTICAST)
                && mSupplicantStaIfaceHal.startRxFilter(ifaceName);
    }

    /**
     * Stop filtering out Multicast V4 packets.
     * @param ifaceName Name of the interface.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean stopFilteringMulticastV4Packets(@NonNull String ifaceName) {
        return mSupplicantStaIfaceHal.stopRxFilter(ifaceName)
                && mSupplicantStaIfaceHal.addRxFilter(
                        ifaceName, RX_FILTER_TYPE_V4_MULTICAST)
                && mSupplicantStaIfaceHal.startRxFilter(ifaceName);
    }

    /**
     * Start filtering out Multicast V6 packets
     * @param ifaceName Name of the interface.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean startFilteringMulticastV6Packets(@NonNull String ifaceName) {
        return mSupplicantStaIfaceHal.stopRxFilter(ifaceName)
                && mSupplicantStaIfaceHal.removeRxFilter(
                        ifaceName, RX_FILTER_TYPE_V6_MULTICAST)
                && mSupplicantStaIfaceHal.startRxFilter(ifaceName);
    }

    /**
     * Stop filtering out Multicast V6 packets.
     * @param ifaceName Name of the interface.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean stopFilteringMulticastV6Packets(@NonNull String ifaceName) {
        return mSupplicantStaIfaceHal.stopRxFilter(ifaceName)
                && mSupplicantStaIfaceHal.addRxFilter(
                        ifaceName, RX_FILTER_TYPE_V6_MULTICAST)
                && mSupplicantStaIfaceHal.startRxFilter(ifaceName);
    }

    public static final int BLUETOOTH_COEXISTENCE_MODE_ENABLED  = 0;
    public static final int BLUETOOTH_COEXISTENCE_MODE_DISABLED = 1;
    public static final int BLUETOOTH_COEXISTENCE_MODE_SENSE    = 2;
    /**
     * Sets the bluetooth coexistence mode.
     *
     * @param ifaceName Name of the interface.
     * @param mode One of {@link #BLUETOOTH_COEXISTENCE_MODE_DISABLED},
     *            {@link #BLUETOOTH_COEXISTENCE_MODE_ENABLED}, or
     *            {@link #BLUETOOTH_COEXISTENCE_MODE_SENSE}.
     * @return Whether the mode was successfully set.
     */
    public boolean setBluetoothCoexistenceMode(@NonNull String ifaceName, int mode) {
        return mSupplicantStaIfaceHal.setBtCoexistenceMode(ifaceName, mode);
    }

    /**
     * Enable or disable Bluetooth coexistence scan mode. When this mode is on,
     * some of the low-level scan parameters used by the driver are changed to
     * reduce interference with A2DP streaming.
     *
     * @param ifaceName Name of the interface.
     * @param setCoexScanMode whether to enable or disable this mode
     * @return {@code true} if the command succeeded, {@code false} otherwise.
     */
    public boolean setBluetoothCoexistenceScanMode(
            @NonNull String ifaceName, boolean setCoexScanMode) {
        return mSupplicantStaIfaceHal.setBtCoexistenceScanModeEnabled(
                ifaceName, setCoexScanMode);
    }

    /**
     * Enable or disable suspend mode optimizations.
     *
     * @param ifaceName Name of the interface.
     * @param enabled true to enable, false otherwise.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setSuspendOptimizations(@NonNull String ifaceName, boolean enabled) {
        return mSupplicantStaIfaceHal.setSuspendModeEnabled(ifaceName, enabled);
    }

    /**
     * Set country code.
     *
     * @param ifaceName Name of the interface.
     * @param countryCode 2 byte ASCII string. For ex: US, CA.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setCountryCode(@NonNull String ifaceName, String countryCode) {
        return mSupplicantStaIfaceHal.setCountryCode(ifaceName, countryCode);
    }

    /**
     * Flush all previously configured HLPs.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean flushAllHlp(@NonNull String ifaceName) {
        return mSupplicantStaIfaceHal.flushAllHlp(ifaceName);
    }

    /**
     * Set FILS HLP packet.
     *
     * @param dst Destination MAC address.
     * @param hlpPacket Hlp Packet data in hex.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean addHlpReq(@NonNull String ifaceName, MacAddress dst, byte [] hlpPacket) {
        return mSupplicantStaIfaceHal.addHlpReq(ifaceName, dst.toByteArray(), hlpPacket);
    }

    /**
     * Initiate TDLS discover and setup or teardown with the specified peer.
     *
     * @param ifaceName Name of the interface.
     * @param macAddr MAC Address of the peer.
     * @param enable true to start discovery and setup, false to teardown.
     */
    public void startTdls(@NonNull String ifaceName, String macAddr, boolean enable) {
        if (enable) {
            mSupplicantStaIfaceHal.initiateTdlsDiscover(ifaceName, macAddr);
            mSupplicantStaIfaceHal.initiateTdlsSetup(ifaceName, macAddr);
        } else {
            mSupplicantStaIfaceHal.initiateTdlsTeardown(ifaceName, macAddr);
        }
    }

    /**
     * Start WPS pin display operation with the specified peer.
     *
     * @param ifaceName Name of the interface.
     * @param bssid BSSID of the peer.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean startWpsPbc(@NonNull String ifaceName, String bssid) {
        return mSupplicantStaIfaceHal.startWpsPbc(ifaceName, bssid);
    }

    /**
     * Start WPS pin keypad operation with the specified pin.
     *
     * @param ifaceName Name of the interface.
     * @param pin Pin to be used.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean startWpsPinKeypad(@NonNull String ifaceName, String pin) {
        return mSupplicantStaIfaceHal.startWpsPinKeypad(ifaceName, pin);
    }

    /**
     * Start WPS pin display operation with the specified peer.
     *
     * @param ifaceName Name of the interface.
     * @param bssid BSSID of the peer.
     * @return new pin generated on success, null otherwise.
     */
    public String startWpsPinDisplay(@NonNull String ifaceName, String bssid) {
        return mSupplicantStaIfaceHal.startWpsPinDisplay(ifaceName, bssid);
    }

    /**
     * Sets whether to use external sim for SIM/USIM processing.
     *
     * @param ifaceName Name of the interface.
     * @param external true to enable, false otherwise.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setExternalSim(@NonNull String ifaceName, boolean external) {
        return mSupplicantStaIfaceHal.setExternalSim(ifaceName, external);
    }

    /**
     * Sim auth response types.
     */
    public static final String SIM_AUTH_RESP_TYPE_GSM_AUTH = "GSM-AUTH";
    public static final String SIM_AUTH_RESP_TYPE_UMTS_AUTH = "UMTS-AUTH";
    public static final String SIM_AUTH_RESP_TYPE_UMTS_AUTS = "UMTS-AUTS";

    /**
     * EAP-SIM Error Codes
     */
    public static final int EAP_SIM_NOT_SUBSCRIBED = 1031;
    public static final int EAP_SIM_VENDOR_SPECIFIC_CERT_EXPIRED = 16385;

    /**
     * Send the sim auth response for the currently configured network.
     *
     * @param ifaceName Name of the interface.
     * @param type |GSM-AUTH|, |UMTS-AUTH| or |UMTS-AUTS|.
     * @param response Response params.
     * @return true if succeeds, false otherwise.
     */
    public boolean simAuthResponse(
            @NonNull String ifaceName, String type, String response) {
        if (SIM_AUTH_RESP_TYPE_GSM_AUTH.equals(type)) {
            return mSupplicantStaIfaceHal.sendCurrentNetworkEapSimGsmAuthResponse(
                    ifaceName, response);
        } else if (SIM_AUTH_RESP_TYPE_UMTS_AUTH.equals(type)) {
            return mSupplicantStaIfaceHal.sendCurrentNetworkEapSimUmtsAuthResponse(
                    ifaceName, response);
        } else if (SIM_AUTH_RESP_TYPE_UMTS_AUTS.equals(type)) {
            return mSupplicantStaIfaceHal.sendCurrentNetworkEapSimUmtsAutsResponse(
                    ifaceName, response);
        } else {
            return false;
        }
    }

    /**
     * Send the eap sim gsm auth failure for the currently configured network.
     *
     * @param ifaceName Name of the interface.
     * @return true if succeeds, false otherwise.
     */
    public boolean simAuthFailedResponse(@NonNull String ifaceName) {
        return mSupplicantStaIfaceHal.sendCurrentNetworkEapSimGsmAuthFailure(ifaceName);
    }

    /**
     * Send the eap sim umts auth failure for the currently configured network.
     *
     * @param ifaceName Name of the interface.
     * @return true if succeeds, false otherwise.
     */
    public boolean umtsAuthFailedResponse(@NonNull String ifaceName) {
        return mSupplicantStaIfaceHal.sendCurrentNetworkEapSimUmtsAuthFailure(ifaceName);
    }

    /**
     * Send the eap identity response for the currently configured network.
     *
     * @param ifaceName Name of the interface.
     * @param unencryptedResponse String to send.
     * @param encryptedResponse String to send.
     * @return true if succeeds, false otherwise.
     */
    public boolean simIdentityResponse(@NonNull String ifaceName, String unencryptedResponse,
                                       String encryptedResponse) {
        return mSupplicantStaIfaceHal.sendCurrentNetworkEapIdentityResponse(ifaceName,
                unencryptedResponse, encryptedResponse);
    }

    /**
     * This get anonymous identity from supplicant and returns it as a string.
     *
     * @param ifaceName Name of the interface.
     * @return anonymous identity string if succeeds, null otherwise.
     */
    public String getEapAnonymousIdentity(@NonNull String ifaceName) {
        return mSupplicantStaIfaceHal.getCurrentNetworkEapAnonymousIdentity(ifaceName);
    }

    /**
     * Start WPS pin registrar operation with the specified peer and pin.
     *
     * @param ifaceName Name of the interface.
     * @param bssid BSSID of the peer.
     * @param pin Pin to be used.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean startWpsRegistrar(@NonNull String ifaceName, String bssid, String pin) {
        return mSupplicantStaIfaceHal.startWpsRegistrar(ifaceName, bssid, pin);
    }

    /**
     * Cancels any ongoing WPS requests.
     *
     * @param ifaceName Name of the interface.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean cancelWps(@NonNull String ifaceName) {
        return mSupplicantStaIfaceHal.cancelWps(ifaceName);
    }

    /**
     * Set WPS device name.
     *
     * @param ifaceName Name of the interface.
     * @param name String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setDeviceName(@NonNull String ifaceName, String name) {
        return mSupplicantStaIfaceHal.setWpsDeviceName(ifaceName, name);
    }

    /**
     * Set WPS device type.
     *
     * @param ifaceName Name of the interface.
     * @param type Type specified as a string. Used format: <categ>-<OUI>-<subcateg>
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setDeviceType(@NonNull String ifaceName, String type) {
        return mSupplicantStaIfaceHal.setWpsDeviceType(ifaceName, type);
    }

    /**
     * Set WPS config methods
     *
     * @param cfg List of config methods.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setConfigMethods(@NonNull String ifaceName, String cfg) {
        return mSupplicantStaIfaceHal.setWpsConfigMethods(ifaceName, cfg);
    }

    /**
     * Set WPS manufacturer.
     *
     * @param ifaceName Name of the interface.
     * @param value String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setManufacturer(@NonNull String ifaceName, String value) {
        return mSupplicantStaIfaceHal.setWpsManufacturer(ifaceName, value);
    }

    /**
     * Set WPS model name.
     *
     * @param ifaceName Name of the interface.
     * @param value String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setModelName(@NonNull String ifaceName, String value) {
        return mSupplicantStaIfaceHal.setWpsModelName(ifaceName, value);
    }

    /**
     * Set WPS model number.
     *
     * @param ifaceName Name of the interface.
     * @param value String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setModelNumber(@NonNull String ifaceName, String value) {
        return mSupplicantStaIfaceHal.setWpsModelNumber(ifaceName, value);
    }

    /**
     * Set WPS serial number.
     *
     * @param ifaceName Name of the interface.
     * @param value String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setSerialNumber(@NonNull String ifaceName, String value) {
        return mSupplicantStaIfaceHal.setWpsSerialNumber(ifaceName, value);
    }

    /**
     * Enable or disable power save mode.
     *
     * @param ifaceName Name of the interface.
     * @param enabled true to enable, false to disable.
     */
    public void setPowerSave(@NonNull String ifaceName, boolean enabled) {
        mSupplicantStaIfaceHal.setPowerSave(ifaceName, enabled);
    }

    /**
     * Enable or disable low latency mode.
     *
     * @param enabled true to enable, false to disable.
     * @return true on success, false on failure
     */
    public boolean setLowLatencyMode(boolean enabled) {
        return mWifiVendorHal.setLowLatencyMode(enabled);
    }

    /**
     * Set concurrency priority between P2P & STA operations.
     *
     * @param isStaHigherPriority Set to true to prefer STA over P2P during concurrency operations,
     *                            false otherwise.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setConcurrencyPriority(boolean isStaHigherPriority) {
        return mSupplicantStaIfaceHal.setConcurrencyPriority(isStaHigherPriority);
    }

    /**
     * Enable/Disable auto reconnect functionality in wpa_supplicant.
     *
     * @param ifaceName Name of the interface.
     * @param enable true to enable auto reconnecting, false to disable.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean enableStaAutoReconnect(@NonNull String ifaceName, boolean enable) {
        return mSupplicantStaIfaceHal.enableAutoReconnect(ifaceName, enable);
    }

    /**
     * Add the provided network configuration to wpa_supplicant and initiate connection to it.
     * This method does the following:
     * 1. Abort any ongoing scan to unblock the connection request.
     * 2. Remove any existing network in wpa_supplicant(This implicitly triggers disconnect).
     * 3. Add a new network to wpa_supplicant.
     * 4. Save the provided configuration to wpa_supplicant.
     * 5. Select the new network in wpa_supplicant.
     * 6. Triggers reconnect command to wpa_supplicant.
     *
     * @param ifaceName Name of the interface.
     * @param configuration WifiConfiguration parameters for the provided network.
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean connectToNetwork(@NonNull String ifaceName, WifiConfiguration configuration) {
        // Abort ongoing scan before connect() to unblock connection request.
        mWifiCondManager.abortScan(ifaceName);
        return mSupplicantStaIfaceHal.connectToNetwork(ifaceName, configuration);
    }

    /**
     * Initiates roaming to the already configured network in wpa_supplicant. If the network
     * configuration provided does not match the already configured network, then this triggers
     * a new connection attempt (instead of roam).
     * 1. Abort any ongoing scan to unblock the roam request.
     * 2. First check if we're attempting to connect to the same network as we currently have
     * configured.
     * 3. Set the new bssid for the network in wpa_supplicant.
     * 4. Triggers reassociate command to wpa_supplicant.
     *
     * @param ifaceName Name of the interface.
     * @param configuration WifiConfiguration parameters for the provided network.
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean roamToNetwork(@NonNull String ifaceName, WifiConfiguration configuration) {
        // Abort ongoing scan before connect() to unblock roaming request.
        mWifiCondManager.abortScan(ifaceName);
        return mSupplicantStaIfaceHal.roamToNetwork(ifaceName, configuration);
    }

    /**
     * Remove all the networks.
     *
     * @param ifaceName Name of the interface.
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean removeAllNetworks(@NonNull String ifaceName) {
        return mSupplicantStaIfaceHal.removeAllNetworks(ifaceName);
    }

    /**
     * Set the BSSID for the currently configured network in wpa_supplicant.
     *
     * @param ifaceName Name of the interface.
     * @return true if successful, false otherwise.
     */
    public boolean setConfiguredNetworkBSSID(@NonNull String ifaceName, String bssid) {
        return mSupplicantStaIfaceHal.setCurrentNetworkBssid(ifaceName, bssid);
    }

    /**
     * Initiate ANQP query.
     *
     * @param ifaceName Name of the interface.
     * @param bssid BSSID of the AP to be queried
     * @param anqpIds Set of anqp IDs.
     * @param hs20Subtypes Set of HS20 subtypes.
     * @return true on success, false otherwise.
     */
    public boolean requestAnqp(
            @NonNull String ifaceName, String bssid, Set<Integer> anqpIds,
            Set<Integer> hs20Subtypes) {
        if (bssid == null || ((anqpIds == null || anqpIds.isEmpty())
                && (hs20Subtypes == null || hs20Subtypes.isEmpty()))) {
            Log.e(TAG, "Invalid arguments for ANQP request.");
            return false;
        }
        ArrayList<Short> anqpIdList = new ArrayList<>();
        for (Integer anqpId : anqpIds) {
            anqpIdList.add(anqpId.shortValue());
        }
        ArrayList<Integer> hs20SubtypeList = new ArrayList<>();
        hs20SubtypeList.addAll(hs20Subtypes);
        return mSupplicantStaIfaceHal.initiateAnqpQuery(
                ifaceName, bssid, anqpIdList, hs20SubtypeList);
    }

    /**
     * Request a passpoint icon file |filename| from the specified AP |bssid|.
     *
     * @param ifaceName Name of the interface.
     * @param bssid BSSID of the AP
     * @param fileName name of the icon file
     * @return true if request is sent successfully, false otherwise
     */
    public boolean requestIcon(@NonNull String ifaceName, String  bssid, String fileName) {
        if (bssid == null || fileName == null) {
            Log.e(TAG, "Invalid arguments for Icon request.");
            return false;
        }
        return mSupplicantStaIfaceHal.initiateHs20IconQuery(ifaceName, bssid, fileName);
    }

    /**
     * Get the currently configured network's WPS NFC token.
     *
     * @param ifaceName Name of the interface.
     * @return Hex string corresponding to the WPS NFC token.
     */
    public String getCurrentNetworkWpsNfcConfigurationToken(@NonNull String ifaceName) {
        return mSupplicantStaIfaceHal.getCurrentNetworkWpsNfcConfigurationToken(ifaceName);
    }

    /**
     * Clean HAL cached data for |networkId|.
     *
     * @param networkId network id of the network to be removed from supplicant.
     */
    public void removeNetworkCachedData(int networkId) {
        mSupplicantStaIfaceHal.removeNetworkCachedData(networkId);
    }

    /** Clear HAL cached data for |networkId| if MAC address is changed.
     *
     * @param networkId network id of the network to be checked.
     * @param curMacAddress current MAC address
     */
    public void removeNetworkCachedDataIfNeeded(int networkId, MacAddress curMacAddress) {
        mSupplicantStaIfaceHal.removeNetworkCachedDataIfNeeded(networkId, curMacAddress);
    }

    /*
     * DPP
     */

    /**
     * Adds a DPP peer URI to the URI list.
     *
     * @param ifaceName Interface name
     * @param uri Bootstrap (URI) string (e.g. DPP:....)
     * @return ID, or -1 for failure
     */
    public int addDppPeerUri(@NonNull String ifaceName, @NonNull String uri) {
        return mSupplicantStaIfaceHal.addDppPeerUri(ifaceName, uri);
    }

    /**
     * Removes a DPP URI to the URI list given an ID.
     *
     * @param ifaceName Interface name
     * @param bootstrapId Bootstrap (URI) ID
     * @return true when operation is successful, or false for failure
     */
    public boolean removeDppUri(@NonNull String ifaceName, int bootstrapId)  {
        return mSupplicantStaIfaceHal.removeDppUri(ifaceName, bootstrapId);
    }

    /**
     * Stops/aborts DPP Initiator request
     *
     * @param ifaceName Interface name
     * @return true when operation is successful, or false for failure
     */
    public boolean stopDppInitiator(@NonNull String ifaceName)  {
        return mSupplicantStaIfaceHal.stopDppInitiator(ifaceName);
    }

    /**
     * Starts DPP Configurator-Initiator request
     *
     * @param ifaceName Interface name
     * @param peerBootstrapId Peer's bootstrap (URI) ID
     * @param ownBootstrapId Own bootstrap (URI) ID - Optional, 0 for none
     * @param ssid SSID of the selected network
     * @param password Password of the selected network, or
     * @param psk PSK of the selected network in hexadecimal representation
     * @param netRole The network role of the enrollee (STA or AP)
     * @param securityAkm Security AKM to use: PSK, SAE
     * @return true when operation is successful, or false for failure
     */
    public boolean startDppConfiguratorInitiator(@NonNull String ifaceName, int peerBootstrapId,
            int ownBootstrapId, @NonNull String ssid, String password, String psk,
            int netRole, int securityAkm)  {
        return mSupplicantStaIfaceHal.startDppConfiguratorInitiator(ifaceName, peerBootstrapId,
                ownBootstrapId, ssid, password, psk, netRole, securityAkm);
    }

    /**
     * Starts DPP Enrollee-Initiator request
     *
     * @param ifaceName Interface name
     * @param peerBootstrapId Peer's bootstrap (URI) ID
     * @param ownBootstrapId Own bootstrap (URI) ID - Optional, 0 for none
     * @return true when operation is successful, or false for failure
     */
    public boolean startDppEnrolleeInitiator(@NonNull String ifaceName, int peerBootstrapId,
            int ownBootstrapId)  {
        return mSupplicantStaIfaceHal.startDppEnrolleeInitiator(ifaceName, peerBootstrapId,
                ownBootstrapId);
    }

    /**
     * Callback to notify about DPP success, failure and progress events.
     */
    public interface DppEventCallback {
        /**
         * Called when local DPP Enrollee successfully receives a new Wi-Fi configuration from the
         * peer DPP configurator.
         *
         * @param newWifiConfiguration New Wi-Fi configuration received from the configurator
         */
        void onSuccessConfigReceived(WifiConfiguration newWifiConfiguration);

        /**
         * DPP Success event.
         *
         * @param dppStatusCode Status code of the success event.
         */
        void onSuccess(int dppStatusCode);

        /**
         * DPP Progress event.
         *
         * @param dppStatusCode Status code of the progress event.
         */
        void onProgress(int dppStatusCode);

        /**
         * DPP Failure event.
         *
         * @param dppStatusCode Status code of the failure event.
         * @param ssid SSID of the network the Enrollee tried to connect to.
         * @param channelList List of channels the Enrollee scanned for the network.
         * @param bandList List of bands the Enrollee supports.
         */
        void onFailure(int dppStatusCode, String ssid, String channelList, int[] bandList);
    }

    /**
     * Registers DPP event callbacks.
     *
     * @param dppEventCallback Callback object.
     */
    public void registerDppEventCallback(DppEventCallback dppEventCallback) {
        mSupplicantStaIfaceHal.registerDppCallback(dppEventCallback);
    }

    /********************************************************
     * Vendor HAL operations
     ********************************************************/
    /**
     * Callback to notify vendor HAL death.
     */
    public interface VendorHalDeathEventHandler {
        /**
         * Invoked when the vendor HAL dies.
         */
        void onDeath();
    }

    /**
     * Callback to notify when vendor HAL detects that a change in radio mode.
     */
    public interface VendorHalRadioModeChangeEventHandler {
        /**
         * Invoked when the vendor HAL detects a change to MCC mode.
         * MCC (Multi channel concurrency) = Multiple interfaces are active on the same band,
         * different channels, same radios.
         *
         * @param band Band on which MCC is detected (specified by one of the
         *             WifiScanner.WIFI_BAND_* constants)
         */
        void onMcc(int band);
        /**
         * Invoked when the vendor HAL detects a change to SCC mode.
         * SCC (Single channel concurrency) = Multiple interfaces are active on the same band, same
         * channels, same radios.
         *
         * @param band Band on which SCC is detected (specified by one of the
         *             WifiScanner.WIFI_BAND_* constants)
         */
        void onScc(int band);
        /**
         * Invoked when the vendor HAL detects a change to SBS mode.
         * SBS (Single Band Simultaneous) = Multiple interfaces are active on the same band,
         * different channels, different radios.
         *
         * @param band Band on which SBS is detected (specified by one of the
         *             WifiScanner.WIFI_BAND_* constants)
         */
        void onSbs(int band);
        /**
         * Invoked when the vendor HAL detects a change to DBS mode.
         * DBS (Dual Band Simultaneous) = Multiple interfaces are active on the different bands,
         * different channels, different radios.
         */
        void onDbs();
    }

    /**
     * Tests whether the HAL is running or not
     */
    public boolean isHalStarted() {
        return mWifiVendorHal.isHalStarted();
    }

    // TODO: Change variable names to camel style.
    public static class ScanCapabilities {
        public int  max_scan_cache_size;
        public int  max_scan_buckets;
        public int  max_ap_cache_per_scan;
        public int  max_rssi_sample_size;
        public int  max_scan_reporting_threshold;
    }

    /**
     * Gets the scan capabilities
     *
     * @param ifaceName Name of the interface.
     * @param capabilities object to be filled in
     * @return true for success. false for failure
     */
    public boolean getBgScanCapabilities(
            @NonNull String ifaceName, ScanCapabilities capabilities) {
        return mWifiVendorHal.getBgScanCapabilities(ifaceName, capabilities);
    }

    public static class ChannelSettings {
        public int frequency;
        public int dwell_time_ms;
        public boolean passive;
    }

    public static class BucketSettings {
        public int bucket;
        public int band;
        public int period_ms;
        public int max_period_ms;
        public int step_count;
        public int report_events;
        public int num_channels;
        public ChannelSettings[] channels;
    }

    /**
     * Network parameters for hidden networks to be scanned for.
     */
    public static class HiddenNetwork {
        public String ssid;

        @Override
        public boolean equals(Object otherObj) {
            if (this == otherObj) {
                return true;
            } else if (otherObj == null || getClass() != otherObj.getClass()) {
                return false;
            }
            HiddenNetwork other = (HiddenNetwork) otherObj;
            return Objects.equals(ssid, other.ssid);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ssid);
        }
    }

    public static class ScanSettings {
        /**
         * Type of scan to perform. One of {@link WifiScanner#SCAN_TYPE_LOW_LATENCY},
         * {@link WifiScanner#SCAN_TYPE_LOW_POWER} or {@link WifiScanner#SCAN_TYPE_HIGH_ACCURACY}.
         */
        @WifiAnnotations.ScanType
        public int scanType;
        public int base_period_ms;
        public int max_ap_per_scan;
        public int report_threshold_percent;
        public int report_threshold_num_scans;
        public int num_buckets;
        /* Not used for bg scans. Only works for single scans. */
        public HiddenNetwork[] hiddenNetworks;
        public BucketSettings[] buckets;
    }

    /**
     * Network parameters to start PNO scan.
     */
    public static class PnoNetwork {
        public String ssid;
        public byte flags;
        public byte auth_bit_field;
        public int[] frequencies;

        @Override
        public boolean equals(Object otherObj) {
            if (this == otherObj) {
                return true;
            } else if (otherObj == null || getClass() != otherObj.getClass()) {
                return false;
            }
            PnoNetwork other = (PnoNetwork) otherObj;
            return ((Objects.equals(ssid, other.ssid)) && (flags == other.flags)
                    && (auth_bit_field == other.auth_bit_field))
                    && Arrays.equals(frequencies, other.frequencies);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ssid, flags, auth_bit_field, frequencies);
        }

        android.net.wifi.nl80211.PnoNetwork toNativePnoNetwork() {
            android.net.wifi.nl80211.PnoNetwork nativePnoNetwork =
                    new android.net.wifi.nl80211.PnoNetwork();
            nativePnoNetwork.setHidden(
                    (flags & WifiScanner.PnoSettings.PnoNetwork.FLAG_DIRECTED_SCAN) != 0);
            try {
                nativePnoNetwork.setSsid(
                        NativeUtil.byteArrayFromArrayList(NativeUtil.decodeSsid(ssid)));
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Illegal argument " + ssid, e);
                return null;
            }
            nativePnoNetwork.setFrequenciesMhz(frequencies);
            return nativePnoNetwork;
        }
    }

    /**
     * Parameters to start PNO scan. This holds the list of networks which are going to used for
     * PNO scan.
     */
    public static class PnoSettings {
        public int min5GHzRssi;
        public int min24GHzRssi;
        public int min6GHzRssi;
        public int periodInMs;
        public boolean isConnected;
        public PnoNetwork[] networkList;

        android.net.wifi.nl80211.PnoSettings toNativePnoSettings() {
            android.net.wifi.nl80211.PnoSettings nativePnoSettings =
                    new android.net.wifi.nl80211.PnoSettings();
            nativePnoSettings.setIntervalMillis(periodInMs);
            nativePnoSettings.setMin2gRssiDbm(min24GHzRssi);
            nativePnoSettings.setMin5gRssiDbm(min5GHzRssi);
            nativePnoSettings.setMin6gRssiDbm(min6GHzRssi);

            List<android.net.wifi.nl80211.PnoNetwork> pnoNetworks = new ArrayList<>();
            if (networkList != null) {
                for (PnoNetwork network : networkList) {
                    android.net.wifi.nl80211.PnoNetwork nativeNetwork =
                            network.toNativePnoNetwork();
                    if (nativeNetwork != null) {
                        pnoNetworks.add(nativeNetwork);
                    }
                }
            }
            nativePnoSettings.setPnoNetworks(pnoNetworks);
            return nativePnoSettings;
        }
    }

    public static interface ScanEventHandler {
        /**
         * Called for each AP as it is found with the entire contents of the beacon/probe response.
         * Only called when WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT is specified.
         */
        void onFullScanResult(ScanResult fullScanResult, int bucketsScanned);
        /**
         * Callback on an event during a gscan scan.
         * See WifiNative.WIFI_SCAN_* for possible values.
         */
        void onScanStatus(int event);
        /**
         * Called with the current cached scan results when gscan is paused.
         */
        void onScanPaused(WifiScanner.ScanData[] data);
        /**
         * Called with the current cached scan results when gscan is resumed.
         */
        void onScanRestarted();
    }

    /**
     * Handler to notify the occurrence of various events during PNO scan.
     */
    public interface PnoEventHandler {
        /**
         * Callback to notify when one of the shortlisted networks is found during PNO scan.
         * @param results List of Scan results received.
         */
        void onPnoNetworkFound(ScanResult[] results);

        /**
         * Callback to notify when the PNO scan schedule fails.
         */
        void onPnoScanFailed();
    }

    public static final int WIFI_SCAN_RESULTS_AVAILABLE = 0;
    public static final int WIFI_SCAN_THRESHOLD_NUM_SCANS = 1;
    public static final int WIFI_SCAN_THRESHOLD_PERCENT = 2;
    public static final int WIFI_SCAN_FAILED = 3;

    /**
     * Starts a background scan.
     * Any ongoing scan will be stopped first
     *
     * @param ifaceName Name of the interface.
     * @param settings     to control the scan
     * @param eventHandler to call with the results
     * @return true for success
     */
    public boolean startBgScan(
            @NonNull String ifaceName, ScanSettings settings, ScanEventHandler eventHandler) {
        return mWifiVendorHal.startBgScan(ifaceName, settings, eventHandler);
    }

    /**
     * Stops any ongoing backgound scan
     * @param ifaceName Name of the interface.
     */
    public void stopBgScan(@NonNull String ifaceName) {
        mWifiVendorHal.stopBgScan(ifaceName);
    }

    /**
     * Pauses an ongoing backgound scan
     * @param ifaceName Name of the interface.
     */
    public void pauseBgScan(@NonNull String ifaceName) {
        mWifiVendorHal.pauseBgScan(ifaceName);
    }

    /**
     * Restarts a paused scan
     * @param ifaceName Name of the interface.
     */
    public void restartBgScan(@NonNull String ifaceName) {
        mWifiVendorHal.restartBgScan(ifaceName);
    }

    /**
     * Gets the latest scan results received.
     * @param ifaceName Name of the interface.
     */
    public WifiScanner.ScanData[] getBgScanResults(@NonNull String ifaceName) {
        return mWifiVendorHal.getBgScanResults(ifaceName);
    }

    /**
     * Gets the latest link layer stats
     * @param ifaceName Name of the interface.
     */
    public WifiLinkLayerStats getWifiLinkLayerStats(@NonNull String ifaceName) {
        return mWifiVendorHal.getWifiLinkLayerStats(ifaceName);
    }

    /**
     * Returns whether STA/AP concurrency is supported or not.
     */
    public boolean isStaApConcurrencySupported() {
        synchronized (mLock) {
            return mWifiVendorHal.isStaApConcurrencySupported();
        }
    }

    /**
     * Get the supported features
     *
     * @param ifaceName Name of the interface.
     * @return bitmask defined by WifiManager.WIFI_FEATURE_*
     */
    public long getSupportedFeatureSet(@NonNull String ifaceName) {
        synchronized (mLock) {
            Iface iface = mIfaceMgr.getIface(ifaceName);
            if (iface == null) {
                Log.e(TAG, "Could not get Iface object for interface " + ifaceName);
                return 0;
            }

            return iface.featureSet;
        }
    }

    /**
     * Get the supported features
     *
     * @param ifaceName Name of the interface.
     * @return bitmask defined by WifiManager.WIFI_FEATURE_*
     */
    private long getSupportedFeatureSetInternal(@NonNull String ifaceName) {
        return mSupplicantStaIfaceHal.getAdvancedKeyMgmtCapabilities(ifaceName)
                | mWifiVendorHal.getSupportedFeatureSet(ifaceName)
                | mSupplicantStaIfaceHal.getWpaDriverFeatureSet(ifaceName);
    }

    /**
     * Class to retrieve connection capability parameters after association
     */
    public static class ConnectionCapabilities {
        public @WifiAnnotations.WifiStandard int wifiStandard;
        public int channelBandwidth;
        public int maxNumberTxSpatialStreams;
        public int maxNumberRxSpatialStreams;
        ConnectionCapabilities() {
            wifiStandard = ScanResult.WIFI_STANDARD_UNKNOWN;
            channelBandwidth = ScanResult.CHANNEL_WIDTH_20MHZ;
            maxNumberTxSpatialStreams = 1;
            maxNumberRxSpatialStreams = 1;
        }
    }

    /**
     * Returns connection capabilities of the current network
     *
     * @param ifaceName Name of the interface.
     * @return connection capabilities of the current network
     */
    public ConnectionCapabilities getConnectionCapabilities(@NonNull String ifaceName) {
        return mSupplicantStaIfaceHal.getConnectionCapabilities(ifaceName);
    }

    /**
     * Get the APF (Android Packet Filter) capabilities of the device
     * @param ifaceName Name of the interface.
     */
    public ApfCapabilities getApfCapabilities(@NonNull String ifaceName) {
        return mWifiVendorHal.getApfCapabilities(ifaceName);
    }

    /**
     * Installs an APF program on this iface, replacing any existing program.
     *
     * @param ifaceName Name of the interface
     * @param filter is the android packet filter program
     * @return true for success
     */
    public boolean installPacketFilter(@NonNull String ifaceName, byte[] filter) {
        return mWifiVendorHal.installPacketFilter(ifaceName, filter);
    }

    /**
     * Reads the APF program and data buffer for this iface.
     *
     * @param ifaceName Name of the interface
     * @return the buffer returned by the driver, or null in case of an error
     */
    public byte[] readPacketFilter(@NonNull String ifaceName) {
        return mWifiVendorHal.readPacketFilter(ifaceName);
    }

    /**
     * Set country code for this AP iface.
     * @param ifaceName Name of the interface.
     * @param countryCode - two-letter country code (as ISO 3166)
     * @return true for success
     */
    public boolean setCountryCodeHal(@NonNull String ifaceName, String countryCode) {
        return mWifiVendorHal.setCountryCodeHal(ifaceName, countryCode);
    }

    //---------------------------------------------------------------------------------
    /* Wifi Logger commands/events */
    public static interface WifiLoggerEventHandler {
        void onRingBufferData(RingBufferStatus status, byte[] buffer);
        void onWifiAlert(int errorCode, byte[] buffer);
    }

    /**
     * Registers the logger callback and enables alerts.
     * Ring buffer data collection is only triggered when |startLoggingRingBuffer| is invoked.
     *
     * @param handler Callback to be invoked.
     * @return true on success, false otherwise.
     */
    public boolean setLoggingEventHandler(WifiLoggerEventHandler handler) {
        return mWifiVendorHal.setLoggingEventHandler(handler);
    }

    /**
     * Control debug data collection
     *
     * @param verboseLevel 0 to 3, inclusive. 0 stops logging.
     * @param flags        Ignored.
     * @param maxInterval  Maximum interval between reports; ignore if 0.
     * @param minDataSize  Minimum data size in buffer for report; ignore if 0.
     * @param ringName     Name of the ring for which data collection is to start.
     * @return true for success, false otherwise.
     */
    public boolean startLoggingRingBuffer(int verboseLevel, int flags, int maxInterval,
            int minDataSize, String ringName){
        return mWifiVendorHal.startLoggingRingBuffer(
                verboseLevel, flags, maxInterval, minDataSize, ringName);
    }

    /**
     * Logger features exposed.
     * This is a no-op now, will always return -1.
     *
     * @return true on success, false otherwise.
     */
    public int getSupportedLoggerFeatureSet() {
        return mWifiVendorHal.getSupportedLoggerFeatureSet();
    }

    /**
     * Stops all logging and resets the logger callback.
     * This stops both the alerts and ring buffer data collection.
     * @return true on success, false otherwise.
     */
    public boolean resetLogHandler() {
        return mWifiVendorHal.resetLogHandler();
    }

    /**
     * Vendor-provided wifi driver version string
     *
     * @return String returned from the HAL.
     */
    public String getDriverVersion() {
        return mWifiVendorHal.getDriverVersion();
    }

    /**
     * Vendor-provided wifi firmware version string
     *
     * @return String returned from the HAL.
     */
    public String getFirmwareVersion() {
        return mWifiVendorHal.getFirmwareVersion();
    }

    public static class RingBufferStatus{
        String name;
        int flag;
        int ringBufferId;
        int ringBufferByteSize;
        int verboseLevel;
        int writtenBytes;
        int readBytes;
        int writtenRecords;

        // Bit masks for interpreting |flag|
        public static final int HAS_BINARY_ENTRIES = (1 << 0);
        public static final int HAS_ASCII_ENTRIES = (1 << 1);
        public static final int HAS_PER_PACKET_ENTRIES = (1 << 2);

        @Override
        public String toString() {
            return "name: " + name + " flag: " + flag + " ringBufferId: " + ringBufferId +
                    " ringBufferByteSize: " +ringBufferByteSize + " verboseLevel: " +verboseLevel +
                    " writtenBytes: " + writtenBytes + " readBytes: " + readBytes +
                    " writtenRecords: " + writtenRecords;
        }
    }

    /**
     * API to get the status of all ring buffers supported by driver
     */
    public RingBufferStatus[] getRingBufferStatus() {
        return mWifiVendorHal.getRingBufferStatus();
    }

    /**
     * Indicates to driver that all the data has to be uploaded urgently
     *
     * @param ringName Name of the ring buffer requested.
     * @return true on success, false otherwise.
     */
    public boolean getRingBufferData(String ringName) {
        return mWifiVendorHal.getRingBufferData(ringName);
    }

    /**
     * Request hal to flush ring buffers to files
     *
     * @return true on success, false otherwise.
     */
    public boolean flushRingBufferData() {
        return mWifiVendorHal.flushRingBufferData();
    }

    /**
     * Request vendor debug info from the firmware
     *
     * @return Raw data obtained from the HAL.
     */
    public byte[] getFwMemoryDump() {
        return mWifiVendorHal.getFwMemoryDump();
    }

    /**
     * Request vendor debug info from the driver
     *
     * @return Raw data obtained from the HAL.
     */
    public byte[] getDriverStateDump() {
        return mWifiVendorHal.getDriverStateDump();
    }

    //---------------------------------------------------------------------------------
    /* Packet fate API */

    @Immutable
    abstract static class FateReport {
        final static int USEC_PER_MSEC = 1000;
        // The driver timestamp is a 32-bit counter, in microseconds. This field holds the
        // maximal value of a driver timestamp in milliseconds.
        final static int MAX_DRIVER_TIMESTAMP_MSEC = (int) (0xffffffffL / 1000);
        final static SimpleDateFormat dateFormatter = new SimpleDateFormat("HH:mm:ss.SSS");

        final byte mFate;
        final long mDriverTimestampUSec;
        final byte mFrameType;
        final byte[] mFrameBytes;
        final long mEstimatedWallclockMSec;

        FateReport(byte fate, long driverTimestampUSec, byte frameType, byte[] frameBytes) {
            mFate = fate;
            mDriverTimestampUSec = driverTimestampUSec;
            mEstimatedWallclockMSec =
                    convertDriverTimestampUSecToWallclockMSec(mDriverTimestampUSec);
            mFrameType = frameType;
            mFrameBytes = frameBytes;
        }

        public String toTableRowString() {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            FrameParser parser = new FrameParser(mFrameType, mFrameBytes);
            dateFormatter.setTimeZone(TimeZone.getDefault());
            pw.format("%-15s  %12s  %-9s  %-32s  %-12s  %-23s  %s\n",
                    mDriverTimestampUSec,
                    dateFormatter.format(new Date(mEstimatedWallclockMSec)),
                    directionToString(), fateToString(), parser.mMostSpecificProtocolString,
                    parser.mTypeString, parser.mResultString);
            return sw.toString();
        }

        public String toVerboseStringWithPiiAllowed() {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            FrameParser parser = new FrameParser(mFrameType, mFrameBytes);
            pw.format("Frame direction: %s\n", directionToString());
            pw.format("Frame timestamp: %d\n", mDriverTimestampUSec);
            pw.format("Frame fate: %s\n", fateToString());
            pw.format("Frame type: %s\n", frameTypeToString(mFrameType));
            pw.format("Frame protocol: %s\n", parser.mMostSpecificProtocolString);
            pw.format("Frame protocol type: %s\n", parser.mTypeString);
            pw.format("Frame length: %d\n", mFrameBytes.length);
            pw.append("Frame bytes");
            pw.append(HexDump.dumpHexString(mFrameBytes));  // potentially contains PII
            pw.append("\n");
            return sw.toString();
        }

        /* Returns a header to match the output of toTableRowString(). */
        public static String getTableHeader() {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.format("\n%-15s  %-12s  %-9s  %-32s  %-12s  %-23s  %s\n",
                    "Time usec", "Walltime", "Direction", "Fate", "Protocol", "Type", "Result");
            pw.format("%-15s  %-12s  %-9s  %-32s  %-12s  %-23s  %s\n",
                    "---------", "--------", "---------", "----", "--------", "----", "------");
            return sw.toString();
        }

        protected abstract String directionToString();

        protected abstract String fateToString();

        private static String frameTypeToString(byte frameType) {
            switch (frameType) {
                case WifiLoggerHal.FRAME_TYPE_UNKNOWN:
                    return "unknown";
                case WifiLoggerHal.FRAME_TYPE_ETHERNET_II:
                    return "data";
                case WifiLoggerHal.FRAME_TYPE_80211_MGMT:
                    return "802.11 management";
                default:
                    return Byte.toString(frameType);
            }
        }

        /**
         * Converts a driver timestamp to a wallclock time, based on the current
         * BOOTTIME to wallclock mapping. The driver timestamp is a 32-bit counter of
         * microseconds, with the same base as BOOTTIME.
         */
        private static long convertDriverTimestampUSecToWallclockMSec(long driverTimestampUSec) {
            final long wallclockMillisNow = System.currentTimeMillis();
            final long boottimeMillisNow = SystemClock.elapsedRealtime();
            final long driverTimestampMillis = driverTimestampUSec / USEC_PER_MSEC;

            long boottimeTimestampMillis = boottimeMillisNow % MAX_DRIVER_TIMESTAMP_MSEC;
            if (boottimeTimestampMillis < driverTimestampMillis) {
                // The 32-bit microsecond count has wrapped between the time that the driver
                // recorded the packet, and the call to this function. Adjust the BOOTTIME
                // timestamp, to compensate.
                //
                // Note that overflow is not a concern here, since the result is less than
                // 2 * MAX_DRIVER_TIMESTAMP_MSEC. (Given the modulus operation above,
                // boottimeTimestampMillis must be less than MAX_DRIVER_TIMESTAMP_MSEC.) And, since
                // MAX_DRIVER_TIMESTAMP_MSEC is an int, 2 * MAX_DRIVER_TIMESTAMP_MSEC must fit
                // within a long.
                boottimeTimestampMillis += MAX_DRIVER_TIMESTAMP_MSEC;
            }

            final long millisSincePacketTimestamp = boottimeTimestampMillis - driverTimestampMillis;
            return wallclockMillisNow - millisSincePacketTimestamp;
        }
    }

    /**
     * Represents the fate information for one outbound packet.
     */
    @Immutable
    public static final class TxFateReport extends FateReport {
        TxFateReport(byte fate, long driverTimestampUSec, byte frameType, byte[] frameBytes) {
            super(fate, driverTimestampUSec, frameType, frameBytes);
        }

        @Override
        protected String directionToString() {
            return "TX";
        }

        @Override
        protected String fateToString() {
            switch (mFate) {
                case WifiLoggerHal.TX_PKT_FATE_ACKED:
                    return "acked";
                case WifiLoggerHal.TX_PKT_FATE_SENT:
                    return "sent";
                case WifiLoggerHal.TX_PKT_FATE_FW_QUEUED:
                    return "firmware queued";
                case WifiLoggerHal.TX_PKT_FATE_FW_DROP_INVALID:
                    return "firmware dropped (invalid frame)";
                case WifiLoggerHal.TX_PKT_FATE_FW_DROP_NOBUFS:
                    return "firmware dropped (no bufs)";
                case WifiLoggerHal.TX_PKT_FATE_FW_DROP_OTHER:
                    return "firmware dropped (other)";
                case WifiLoggerHal.TX_PKT_FATE_DRV_QUEUED:
                    return "driver queued";
                case WifiLoggerHal.TX_PKT_FATE_DRV_DROP_INVALID:
                    return "driver dropped (invalid frame)";
                case WifiLoggerHal.TX_PKT_FATE_DRV_DROP_NOBUFS:
                    return "driver dropped (no bufs)";
                case WifiLoggerHal.TX_PKT_FATE_DRV_DROP_OTHER:
                    return "driver dropped (other)";
                default:
                    return Byte.toString(mFate);
            }
        }
    }

    /**
     * Represents the fate information for one inbound packet.
     */
    @Immutable
    public static final class RxFateReport extends FateReport {
        RxFateReport(byte fate, long driverTimestampUSec, byte frameType, byte[] frameBytes) {
            super(fate, driverTimestampUSec, frameType, frameBytes);
        }

        @Override
        protected String directionToString() {
            return "RX";
        }

        @Override
        protected String fateToString() {
            switch (mFate) {
                case WifiLoggerHal.RX_PKT_FATE_SUCCESS:
                    return "success";
                case WifiLoggerHal.RX_PKT_FATE_FW_QUEUED:
                    return "firmware queued";
                case WifiLoggerHal.RX_PKT_FATE_FW_DROP_FILTER:
                    return "firmware dropped (filter)";
                case WifiLoggerHal.RX_PKT_FATE_FW_DROP_INVALID:
                    return "firmware dropped (invalid frame)";
                case WifiLoggerHal.RX_PKT_FATE_FW_DROP_NOBUFS:
                    return "firmware dropped (no bufs)";
                case WifiLoggerHal.RX_PKT_FATE_FW_DROP_OTHER:
                    return "firmware dropped (other)";
                case WifiLoggerHal.RX_PKT_FATE_DRV_QUEUED:
                    return "driver queued";
                case WifiLoggerHal.RX_PKT_FATE_DRV_DROP_FILTER:
                    return "driver dropped (filter)";
                case WifiLoggerHal.RX_PKT_FATE_DRV_DROP_INVALID:
                    return "driver dropped (invalid frame)";
                case WifiLoggerHal.RX_PKT_FATE_DRV_DROP_NOBUFS:
                    return "driver dropped (no bufs)";
                case WifiLoggerHal.RX_PKT_FATE_DRV_DROP_OTHER:
                    return "driver dropped (other)";
                default:
                    return Byte.toString(mFate);
            }
        }
    }

    /**
     * Ask the HAL to enable packet fate monitoring. Fails unless HAL is started.
     *
     * @param ifaceName Name of the interface.
     * @return true for success, false otherwise.
     */
    public boolean startPktFateMonitoring(@NonNull String ifaceName) {
        return mWifiVendorHal.startPktFateMonitoring(ifaceName);
    }

    /**
     * Fetch the most recent TX packet fates from the HAL. Fails unless HAL is started.
     *
     * @param ifaceName Name of the interface.
     * @return true for success, false otherwise.
     */
    public boolean getTxPktFates(@NonNull String ifaceName, TxFateReport[] reportBufs) {
        return mWifiVendorHal.getTxPktFates(ifaceName, reportBufs);
    }

    /**
     * Fetch the most recent RX packet fates from the HAL. Fails unless HAL is started.
     * @param ifaceName Name of the interface.
     */
    public boolean getRxPktFates(@NonNull String ifaceName, RxFateReport[] reportBufs) {
        return mWifiVendorHal.getRxPktFates(ifaceName, reportBufs);
    }

    /**
     * Get the tx packet counts for the interface.
     *
     * @param ifaceName Name of the interface.
     * @return tx packet counts
     */
    public long getTxPackets(@NonNull String ifaceName) {
        return TrafficStats.getTxPackets(ifaceName);
    }

    /**
     * Get the rx packet counts for the interface.
     *
     * @param ifaceName Name of the interface
     * @return rx packet counts
     */
    public long getRxPackets(@NonNull String ifaceName) {
        return TrafficStats.getRxPackets(ifaceName);
    }

    /**
     * Start sending the specified keep alive packets periodically.
     *
     * @param ifaceName Name of the interface.
     * @param slot Integer used to identify each request.
     * @param dstMac Destination MAC Address
     * @param packet Raw packet contents to send.
     * @param protocol The ethernet protocol type
     * @param period Period to use for sending these packets.
     * @return 0 for success, -1 for error
     */
    public int startSendingOffloadedPacket(@NonNull String ifaceName, int slot,
            byte[] dstMac, byte[] packet, int protocol, int period) {
        byte[] srcMac = NativeUtil.macAddressToByteArray(getMacAddress(ifaceName));
        return mWifiVendorHal.startSendingOffloadedPacket(
                ifaceName, slot, srcMac, dstMac, packet, protocol, period);
    }

    /**
     * Stop sending the specified keep alive packets.
     *
     * @param ifaceName Name of the interface.
     * @param slot id - same as startSendingOffloadedPacket call.
     * @return 0 for success, -1 for error
     */
    public int stopSendingOffloadedPacket(@NonNull String ifaceName, int slot) {
        return mWifiVendorHal.stopSendingOffloadedPacket(ifaceName, slot);
    }

    public static interface WifiRssiEventHandler {
        void onRssiThresholdBreached(byte curRssi);
    }

    /**
     * Start RSSI monitoring on the currently connected access point.
     *
     * @param ifaceName        Name of the interface.
     * @param maxRssi          Maximum RSSI threshold.
     * @param minRssi          Minimum RSSI threshold.
     * @param rssiEventHandler Called when RSSI goes above maxRssi or below minRssi
     * @return 0 for success, -1 for failure
     */
    public int startRssiMonitoring(
            @NonNull String ifaceName, byte maxRssi, byte minRssi,
            WifiRssiEventHandler rssiEventHandler) {
        return mWifiVendorHal.startRssiMonitoring(
                ifaceName, maxRssi, minRssi, rssiEventHandler);
    }

    /**
     * Stop RSSI monitoring on the currently connected access point.
     *
     * @param ifaceName Name of the interface.
     * @return 0 for success, -1 for failure
     */
    public int stopRssiMonitoring(@NonNull String ifaceName) {
        return mWifiVendorHal.stopRssiMonitoring(ifaceName);
    }

    /**
     * Fetch the host wakeup reasons stats from wlan driver.
     *
     * @return the |WlanWakeReasonAndCounts| object retrieved from the wlan driver.
     */
    public WlanWakeReasonAndCounts getWlanWakeReasonCount() {
        return mWifiVendorHal.getWlanWakeReasonCount();
    }

    /**
     * Enable/Disable Neighbour discovery offload functionality in the firmware.
     *
     * @param ifaceName Name of the interface.
     * @param enabled true to enable, false to disable.
     * @return true for success, false otherwise.
     */
    public boolean configureNeighborDiscoveryOffload(@NonNull String ifaceName, boolean enabled) {
        return mWifiVendorHal.configureNeighborDiscoveryOffload(ifaceName, enabled);
    }

    // Firmware roaming control.

    /**
     * Class to retrieve firmware roaming capability parameters.
     */
    public static class RoamingCapabilities {
        public int  maxBlacklistSize;
        public int  maxWhitelistSize;
    }

    /**
     * Query the firmware roaming capabilities.
     * @param ifaceName Name of the interface.
     * @return true for success, false otherwise.
     */
    public boolean getRoamingCapabilities(
            @NonNull String ifaceName, RoamingCapabilities capabilities) {
        return mWifiVendorHal.getRoamingCapabilities(ifaceName, capabilities);
    }

    /**
     * Macros for controlling firmware roaming.
     */
    public static final int DISABLE_FIRMWARE_ROAMING = 0;
    public static final int ENABLE_FIRMWARE_ROAMING = 1;

    /**
     * Indicates success for enableFirmwareRoaming
     */
    public static final int SET_FIRMWARE_ROAMING_SUCCESS = 0;

    /**
     * Indicates failure for enableFirmwareRoaming
     */
    public static final int SET_FIRMWARE_ROAMING_FAILURE = 1;

    /**
     * Indicates temporary failure for enableFirmwareRoaming - try again later
     */
    public static final int SET_FIRMWARE_ROAMING_BUSY = 2;

    /**
     * Enable/disable firmware roaming.
     *
     * @param ifaceName Name of the interface.
     * @return SET_FIRMWARE_ROAMING_SUCCESS, SET_FIRMWARE_ROAMING_FAILURE,
     *         or SET_FIRMWARE_ROAMING_BUSY
     */
    public int enableFirmwareRoaming(@NonNull String ifaceName, int state) {
        return mWifiVendorHal.enableFirmwareRoaming(ifaceName, state);
    }

    /**
     * Class for specifying the roaming configurations.
     */
    public static class RoamingConfig {
        public ArrayList<String> blacklistBssids;
        public ArrayList<String> whitelistSsids;
    }

    /**
     * Set firmware roaming configurations.
     * @param ifaceName Name of the interface.
     */
    public boolean configureRoaming(@NonNull String ifaceName, RoamingConfig config) {
        return mWifiVendorHal.configureRoaming(ifaceName, config);
    }

    /**
     * Reset firmware roaming configuration.
     * @param ifaceName Name of the interface.
     */
    public boolean resetRoamingConfiguration(@NonNull String ifaceName) {
        // Pass in an empty RoamingConfig object which translates to zero size
        // blacklist and whitelist to reset the firmware roaming configuration.
        return mWifiVendorHal.configureRoaming(ifaceName, new RoamingConfig());
    }

    /**
     * Select one of the pre-configured transmit power level scenarios or reset it back to normal.
     * Primarily used for meeting SAR requirements.
     *
     * @param sarInfo The collection of inputs used to select the SAR scenario.
     * @return true for success; false for failure or if the HAL version does not support this API.
     */
    public boolean selectTxPowerScenario(SarInfo sarInfo) {
        return mWifiVendorHal.selectTxPowerScenario(sarInfo);
    }

    /**
     * Set MBO cellular data status
     *
     * @param ifaceName Name of the interface.
     * @param available cellular data status,
     *        true means cellular data available, false otherwise.
     */
    public void setMboCellularDataStatus(@NonNull String ifaceName, boolean available) {
        mSupplicantStaIfaceHal.setMboCellularDataStatus(ifaceName, available);
        return;
    }

    /**
     * Query of support of Wi-Fi standard
     *
     * @param ifaceName name of the interface to check support on
     * @param standard the wifi standard to check on
     * @return true if the wifi standard is supported on this interface, false otherwise.
     */
    public boolean isWifiStandardSupported(@NonNull String ifaceName,
            @WifiAnnotations.WifiStandard int standard) {
        synchronized (mLock) {
            Iface iface = mIfaceMgr.getIface(ifaceName);
            if (iface == null || iface.phyCapabilities == null) {
                return false;
            }
            return iface.phyCapabilities.isWifiStandardSupported(standard);
        }
    }

    /**
     * Get the Wiphy capabilities of a device for a given interface
     * If the interface is not associated with one,
     * it will be read from the device through wificond
     *
     * @param ifaceName name of the interface
     * @return the device capabilities for this interface
     */
    public DeviceWiphyCapabilities getDeviceWiphyCapabilities(@NonNull String ifaceName) {
        synchronized (mLock) {
            Iface iface = mIfaceMgr.getIface(ifaceName);
            if (iface == null) {
                Log.e(TAG, "Failed to get device capabilities, interface not found: " + ifaceName);
                return null;
            }
            if (iface.phyCapabilities == null) {
                iface.phyCapabilities = mWifiCondManager.getDeviceWiphyCapabilities(ifaceName);
            }
            return iface.phyCapabilities;
        }
    }

    /**
     * Set the Wiphy capabilities of a device for a given interface
     *
     * @param ifaceName name of the interface
     * @param capabilities the wiphy capabilities to set for this interface
     */
    public void setDeviceWiphyCapabilities(@NonNull String ifaceName,
            DeviceWiphyCapabilities capabilities) {
        synchronized (mLock) {
            Iface iface = mIfaceMgr.getIface(ifaceName);
            if (iface == null) {
                Log.e(TAG, "Failed to set device capabilities, interface not found: " + ifaceName);
                return;
            }
            iface.phyCapabilities = capabilities;
        }
    }
}
