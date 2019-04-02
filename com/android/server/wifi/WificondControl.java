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

package com.android.server.wifi;

import android.net.wifi.IApInterface;
import android.net.wifi.IClientInterface;
import android.net.wifi.IPnoScanEvent;
import android.net.wifi.IScanEvent;
import android.net.wifi.IWifiScannerImpl;
import android.net.wifi.IWificond;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiSsid;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.util.InformationElementUtil;
import com.android.server.wifi.util.NativeUtil;
import com.android.server.wifi.wificond.ChannelSettings;
import com.android.server.wifi.wificond.HiddenNetwork;
import com.android.server.wifi.wificond.NativeScanResult;
import com.android.server.wifi.wificond.PnoNetwork;
import com.android.server.wifi.wificond.PnoSettings;
import com.android.server.wifi.wificond.SingleScanSettings;

import java.util.ArrayList;
import java.util.Set;

/**
 * This class provides methods for WifiNative to send control commands to wificond.
 * NOTE: This class should only be used from WifiNative.
 */
public class WificondControl {
    private boolean mVerboseLoggingEnabled = false;

    private static final String TAG = "WificondControl";
    private WifiInjector mWifiInjector;
    private WifiMonitor mWifiMonitor;

    // Cached wificond binder handlers.
    private IWificond mWificond;
    private IClientInterface mClientInterface;
    private IApInterface mApInterface;
    private IWifiScannerImpl mWificondScanner;
    private IScanEvent mScanEventHandler;
    private IPnoScanEvent mPnoScanEventHandler;

    private String mClientInterfaceName;


    private class ScanEventHandler extends IScanEvent.Stub {
        @Override
        public void OnScanResultReady() {
            Log.d(TAG, "Scan result ready event");
            mWifiMonitor.broadcastScanResultEvent(mClientInterfaceName);
        }

        @Override
        public void OnScanFailed() {
            Log.d(TAG, "Scan failed event");
            mWifiMonitor.broadcastScanFailedEvent(mClientInterfaceName);
        }
    }

    WificondControl(WifiInjector wifiInjector, WifiMonitor wifiMonitor) {
        mWifiInjector = wifiInjector;
        mWifiMonitor = wifiMonitor;
    }

    private class PnoScanEventHandler extends IPnoScanEvent.Stub {
        @Override
        public void OnPnoNetworkFound() {
            Log.d(TAG, "Pno scan result event");
            mWifiMonitor.broadcastPnoScanResultEvent(mClientInterfaceName);
        }

        @Override
        public void OnPnoScanFailed() {
            Log.d(TAG, "Pno Scan failed event");
            // Nothing to do for now.
        }
    }

    /** Enable or disable verbose logging of WificondControl.
     *  @param enable True to enable verbose logging. False to disable verbose logging.
     */
    public void enableVerboseLogging(boolean enable) {
        mVerboseLoggingEnabled = enable;
    }

    /**
    * Setup driver for client mode via wificond.
    * @return An IClientInterface as wificond client interface binder handler.
    * Returns null on failure.
    */
    public IClientInterface setupDriverForClientMode() {
        Log.d(TAG, "Setting up driver for client mode");
        mWificond = mWifiInjector.makeWificond();
        if (mWificond == null) {
            Log.e(TAG, "Failed to get reference to wificond");
            return null;
        }

        IClientInterface clientInterface = null;
        try {
            clientInterface = mWificond.createClientInterface();
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to get IClientInterface due to remote exception");
            return null;
        }

        if (clientInterface == null) {
            Log.e(TAG, "Could not get IClientInterface instance from wificond");
            return null;
        }
        Binder.allowBlocking(clientInterface.asBinder());

        // Refresh Handlers
        mClientInterface = clientInterface;
        try {
            mClientInterfaceName = clientInterface.getInterfaceName();
            mWificondScanner = mClientInterface.getWifiScannerImpl();
            if (mWificondScanner == null) {
                Log.e(TAG, "Failed to get WificondScannerImpl");
                return null;
            }
            Binder.allowBlocking(mWificondScanner.asBinder());
            mScanEventHandler = new ScanEventHandler();
            mWificondScanner.subscribeScanEvents(mScanEventHandler);
            mPnoScanEventHandler = new PnoScanEventHandler();
            mWificondScanner.subscribePnoScanEvents(mPnoScanEventHandler);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to refresh wificond scanner due to remote exception");
        }

        return clientInterface;
    }

    /**
    * Setup driver for softAp mode via wificond.
    * @return An IApInterface as wificond Ap interface binder handler.
    * Returns null on failure.
    */
    public IApInterface setupDriverForSoftApMode() {
        Log.d(TAG, "Setting up driver for soft ap mode");
        mWificond = mWifiInjector.makeWificond();
        if (mWificond == null) {
            Log.e(TAG, "Failed to get reference to wificond");
            return null;
        }

        IApInterface apInterface = null;
        try {
            apInterface = mWificond.createApInterface();
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to get IApInterface due to remote exception");
            return null;
        }

        if (apInterface == null) {
            Log.e(TAG, "Could not get IApInterface instance from wificond");
            return null;
        }
        Binder.allowBlocking(apInterface.asBinder());

        // Refresh Handlers
        mApInterface = apInterface;

        return apInterface;
    }

    /**
    * Teardown all interfaces configured in wificond.
    * @return Returns true on success.
    */
    public boolean tearDownInterfaces() {
        Log.d(TAG, "tearing down interfaces in wificond");
        // Explicitly refresh the wificodn handler because |tearDownInterfaces()|
        // could be used to cleanup before we setup any interfaces.
        mWificond = mWifiInjector.makeWificond();
        if (mWificond == null) {
            Log.e(TAG, "Failed to get reference to wificond");
            return false;
        }

        try {
            if (mWificondScanner != null) {
                mWificondScanner.unsubscribeScanEvents();
                mWificondScanner.unsubscribePnoScanEvents();
            }
            mWificond.tearDownInterfaces();

            // Refresh handlers
            mClientInterface = null;
            mWificondScanner = null;
            mPnoScanEventHandler = null;
            mScanEventHandler = null;
            mApInterface = null;

            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to tear down interfaces due to remote exception");
        }

        return false;
    }

    /**
    * Disable wpa_supplicant via wificond.
    * @return Returns true on success.
    */
    public boolean disableSupplicant() {
        if (mClientInterface == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            return false;
        }
        try {
            return mClientInterface.disableSupplicant();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to disable supplicant due to remote exception");
        }
        return false;
    }

    /**
    * Enable wpa_supplicant via wificond.
    * @return Returns true on success.
    */
    public boolean enableSupplicant() {
        if (mClientInterface == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            return false;
        }

        try {
            return mClientInterface.enableSupplicant();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to enable supplicant due to remote exception");
        }
        return false;
    }

    /**
    * Request signal polling to wificond.
    * Returns an SignalPollResult object.
    * Returns null on failure.
    */
    public WifiNative.SignalPollResult signalPoll() {
        if (mClientInterface == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            return null;
        }

        int[] resultArray;
        try {
            resultArray = mClientInterface.signalPoll();
            if (resultArray == null || resultArray.length != 3) {
                Log.e(TAG, "Invalid signal poll result from wificond");
                return null;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to do signal polling due to remote exception");
            return null;
        }
        WifiNative.SignalPollResult pollResult = new WifiNative.SignalPollResult();
        pollResult.currentRssi = resultArray[0];
        pollResult.txBitrate = resultArray[1];
        pollResult.associationFrequency = resultArray[2];
        return pollResult;
    }

    /**
    * Fetch TX packet counters on current connection from wificond.
    * Returns an TxPacketCounters object.
    * Returns null on failure.
    */
    public WifiNative.TxPacketCounters getTxPacketCounters() {
        if (mClientInterface == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            return null;
        }

        int[] resultArray;
        try {
            resultArray = mClientInterface.getPacketCounters();
            if (resultArray == null || resultArray.length != 2) {
                Log.e(TAG, "Invalid signal poll result from wificond");
                return null;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to do signal polling due to remote exception");
            return null;
        }
        WifiNative.TxPacketCounters counters = new WifiNative.TxPacketCounters();
        counters.txSucceeded = resultArray[0];
        counters.txFailed = resultArray[1];
        return counters;
    }

    /**
    * Fetch the latest scan result from kernel via wificond.
    * @return Returns an ArrayList of ScanDetail.
    * Returns an empty ArrayList on failure.
    */
    public ArrayList<ScanDetail> getScanResults() {
        ArrayList<ScanDetail> results = new ArrayList<>();
        if (mWificondScanner == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return results;
        }
        try {
            NativeScanResult[] nativeResults = mWificondScanner.getScanResults();
            for (NativeScanResult result : nativeResults) {
                WifiSsid wifiSsid = WifiSsid.createFromByteArray(result.ssid);
                String bssid;
                try {
                    bssid = NativeUtil.macAddressFromByteArray(result.bssid);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Illegal argument " + result.bssid, e);
                    continue;
                }
                if (bssid == null) {
                    Log.e(TAG, "Illegal null bssid");
                    continue;
                }
                ScanResult.InformationElement[] ies =
                        InformationElementUtil.parseInformationElements(result.infoElement);
                InformationElementUtil.Capabilities capabilities =
                        new InformationElementUtil.Capabilities();
                capabilities.from(ies, result.capability);
                String flags = capabilities.generateCapabilitiesString();
                NetworkDetail networkDetail =
                        new NetworkDetail(bssid, ies, null, result.frequency);

                if (!wifiSsid.toString().equals(networkDetail.getTrimmedSSID())) {
                    Log.e(TAG, "Inconsistent SSID on BSSID: " + bssid);
                    continue;
                }
                ScanDetail scanDetail = new ScanDetail(networkDetail, wifiSsid, bssid, flags,
                        result.signalMbm / 100, result.frequency, result.tsf, ies, null);
                results.add(scanDetail);
            }
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to create ScanDetail ArrayList");
        }
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "get " + results.size() + " scan results from wificond");
        }

        return results;
    }

    /**
     * Start a scan using wificond for the given parameters.
     * @param freqs list of frequencies to scan for, if null scan all supported channels.
     * @param hiddenNetworkSSIDs List of hidden networks to be scanned for.
     * @return Returns true on success.
     */
    public boolean scan(Set<Integer> freqs, Set<String> hiddenNetworkSSIDs) {
        if (mWificondScanner == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return false;
        }
        SingleScanSettings settings = new SingleScanSettings();
        settings.channelSettings  = new ArrayList<>();
        settings.hiddenNetworks  = new ArrayList<>();

        if (freqs != null) {
            for (Integer freq : freqs) {
                ChannelSettings channel = new ChannelSettings();
                channel.frequency = freq;
                settings.channelSettings.add(channel);
            }
        }
        if (hiddenNetworkSSIDs != null) {
            for (String ssid : hiddenNetworkSSIDs) {
                HiddenNetwork network = new HiddenNetwork();
                try {
                    network.ssid = NativeUtil.byteArrayFromArrayList(NativeUtil.decodeSsid(ssid));
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Illegal argument " + ssid, e);
                    continue;
                }
                settings.hiddenNetworks.add(network);
            }
        }

        try {
            return mWificondScanner.scan(settings);
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to request scan due to remote exception");
        }
        return false;
    }

    /**
     * Start PNO scan.
     * @param pnoSettings Pno scan configuration.
     * @return true on success.
     */
    public boolean startPnoScan(WifiNative.PnoSettings pnoSettings) {
        if (mWificondScanner == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return false;
        }
        PnoSettings settings = new PnoSettings();
        settings.pnoNetworks  = new ArrayList<>();
        settings.intervalMs = pnoSettings.periodInMs;
        settings.min2gRssi = pnoSettings.min24GHzRssi;
        settings.min5gRssi = pnoSettings.min5GHzRssi;
        if (pnoSettings.networkList != null) {
            for (WifiNative.PnoNetwork network : pnoSettings.networkList) {
                PnoNetwork condNetwork = new PnoNetwork();
                condNetwork.isHidden = (network.flags
                        & WifiScanner.PnoSettings.PnoNetwork.FLAG_DIRECTED_SCAN) != 0;
                try {
                    condNetwork.ssid =
                            NativeUtil.byteArrayFromArrayList(NativeUtil.decodeSsid(network.ssid));
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Illegal argument " + network.ssid, e);
                    continue;
                }
                settings.pnoNetworks.add(condNetwork);
            }
        }

        try {
            return mWificondScanner.startPnoScan(settings);
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to stop pno scan due to remote exception");
        }
        return false;
    }

    /**
     * Stop PNO scan.
     * @return true on success.
     */
    public boolean stopPnoScan() {
        if (mWificondScanner == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return false;
        }
        try {
            return mWificondScanner.stopPnoScan();
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to stop pno scan due to remote exception");
        }
        return false;
    }

    /**
     * Abort ongoing single scan.
     */
    public void abortScan() {
        if (mWificondScanner == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return;
        }
        try {
            mWificondScanner.abortScan();
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to request abortScan due to remote exception");
        }
    }

}
