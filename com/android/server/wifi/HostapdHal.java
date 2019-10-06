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


import android.annotation.NonNull;
import android.content.Context;
import android.hardware.wifi.hostapd.V1_0.HostapdStatus;
import android.hardware.wifi.hostapd.V1_0.HostapdStatusCode;
import android.hardware.wifi.hostapd.V1_0.IHostapd;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.net.wifi.WifiConfiguration;
import android.os.Handler;
import android.os.HwRemoteBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.WifiNative.HostapdDeathEventHandler;
import com.android.server.wifi.util.NativeUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.NoSuchElementException;

import javax.annotation.concurrent.ThreadSafe;

/**
 * To maintain thread-safety, the locking protocol is that every non-static method (regardless of
 * access level) acquires mLock.
 */
@ThreadSafe
public class HostapdHal {
    private static final String TAG = "HostapdHal";
    @VisibleForTesting
    public static final String HAL_INSTANCE_NAME = "default";

    private final Object mLock = new Object();
    private boolean mVerboseLoggingEnabled = false;
    private final Handler mEventHandler;
    private final boolean mEnableAcs;
    private final boolean mEnableIeee80211AC;
    private final List<android.hardware.wifi.hostapd.V1_1.IHostapd.AcsChannelRange>
            mAcsChannelRanges;

    // Hostapd HAL interface objects
    private IServiceManager mIServiceManager = null;
    private IHostapd mIHostapd;
    private HashMap<String, WifiNative.SoftApListener> mSoftApListeners = new HashMap<>();
    private HostapdDeathEventHandler mDeathEventHandler;
    private ServiceManagerDeathRecipient mServiceManagerDeathRecipient;
    private HostapdDeathRecipient mHostapdDeathRecipient;
    // Death recipient cookie registered for current supplicant instance.
    private long mDeathRecipientCookie = 0;

    private final IServiceNotification mServiceNotificationCallback =
            new IServiceNotification.Stub() {
        public void onRegistration(String fqName, String name, boolean preexisting) {
            synchronized (mLock) {
                if (mVerboseLoggingEnabled) {
                    Log.i(TAG, "IServiceNotification.onRegistration for: " + fqName
                            + ", " + name + " preexisting=" + preexisting);
                }
                if (!initHostapdService()) {
                    Log.e(TAG, "initalizing IHostapd failed.");
                    hostapdServiceDiedHandler(mDeathRecipientCookie);
                } else {
                    Log.i(TAG, "Completed initialization of IHostapd.");
                }
            }
        }
    };
    private class ServiceManagerDeathRecipient implements HwRemoteBinder.DeathRecipient {
        @Override
        public void serviceDied(long cookie) {
            mEventHandler.post(() -> {
                synchronized (mLock) {
                    Log.w(TAG, "IServiceManager died: cookie=" + cookie);
                    hostapdServiceDiedHandler(mDeathRecipientCookie);
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                }
            });
        }
    }
    private class HostapdDeathRecipient implements HwRemoteBinder.DeathRecipient {
        @Override
        public void serviceDied(long cookie) {
            mEventHandler.post(() -> {
                synchronized (mLock) {
                    Log.w(TAG, "IHostapd/IHostapd died: cookie=" + cookie);
                    hostapdServiceDiedHandler(cookie);
                }
            });
        }
    }

    public HostapdHal(Context context, Looper looper) {
        mEventHandler = new Handler(looper);
        mEnableAcs = context.getResources().getBoolean(R.bool.config_wifi_softap_acs_supported);
        mEnableIeee80211AC =
                context.getResources().getBoolean(R.bool.config_wifi_softap_ieee80211ac_supported);
        mAcsChannelRanges = toAcsChannelRanges(context.getResources().getString(
                R.string.config_wifi_softap_acs_supported_channel_list));

        mServiceManagerDeathRecipient = new ServiceManagerDeathRecipient();
        mHostapdDeathRecipient = new HostapdDeathRecipient();
    }

    /**
     * Enable/Disable verbose logging.
     *
     * @param enable true to enable, false to disable.
     */
    void enableVerboseLogging(boolean enable) {
        synchronized (mLock) {
            mVerboseLoggingEnabled = enable;
        }
    }

    /**
     * Uses the IServiceManager to check if the device is running V1_1 of the HAL from the VINTF for
     * the device.
     * @return true if supported, false otherwise.
     */
    private boolean isV1_1() {
        synchronized (mLock) {
            if (mIServiceManager == null) {
                Log.e(TAG, "isV1_1: called but mServiceManager is null!?");
                return false;
            }
            try {
                return (mIServiceManager.getTransport(
                        android.hardware.wifi.hostapd.V1_1.IHostapd.kInterfaceName,
                        HAL_INSTANCE_NAME)
                        != IServiceManager.Transport.EMPTY);
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while operating on IServiceManager: " + e);
                handleRemoteException(e, "getTransport");
                return false;
            }
        }
    }

    /**
     * Link to death for IServiceManager object.
     * @return true on success, false otherwise.
     */
    private boolean linkToServiceManagerDeath() {
        synchronized (mLock) {
            if (mIServiceManager == null) return false;
            try {
                if (!mIServiceManager.linkToDeath(mServiceManagerDeathRecipient, 0)) {
                    Log.wtf(TAG, "Error on linkToDeath on IServiceManager");
                    hostapdServiceDiedHandler(mDeathRecipientCookie);
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "IServiceManager.linkToDeath exception", e);
                mIServiceManager = null; // Will need to register a new ServiceNotification
                return false;
            }
            return true;
        }
    }

    /**
     * Registers a service notification for the IHostapd service, which triggers intialization of
     * the IHostapd
     * @return true if the service notification was successfully registered
     */
    public boolean initialize() {
        synchronized (mLock) {
            if (mVerboseLoggingEnabled) {
                Log.i(TAG, "Registering IHostapd service ready callback.");
            }
            mIHostapd = null;
            if (mIServiceManager != null) {
                // Already have an IServiceManager and serviceNotification registered, don't
                // don't register another.
                return true;
            }
            try {
                mIServiceManager = getServiceManagerMockable();
                if (mIServiceManager == null) {
                    Log.e(TAG, "Failed to get HIDL Service Manager");
                    return false;
                }
                if (!linkToServiceManagerDeath()) {
                    return false;
                }
                /* TODO(b/33639391) : Use the new IHostapd.registerForNotifications() once it
                   exists */
                if (!mIServiceManager.registerForNotifications(
                        IHostapd.kInterfaceName, "", mServiceNotificationCallback)) {
                    Log.e(TAG, "Failed to register for notifications to "
                            + IHostapd.kInterfaceName);
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while trying to register a listener for IHostapd service: "
                        + e);
                hostapdServiceDiedHandler(mDeathRecipientCookie);
                mIServiceManager = null; // Will need to register a new ServiceNotification
                return false;
            }
            return true;
        }
    }

    /**
     * Link to death for IHostapd object.
     * @return true on success, false otherwise.
     */
    private boolean linkToHostapdDeath() {
        synchronized (mLock) {
            if (mIHostapd == null) return false;
            try {
                if (!mIHostapd.linkToDeath(mHostapdDeathRecipient, ++mDeathRecipientCookie)) {
                    Log.wtf(TAG, "Error on linkToDeath on IHostapd");
                    hostapdServiceDiedHandler(mDeathRecipientCookie);
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "IHostapd.linkToDeath exception", e);
                return false;
            }
            return true;
        }
    }

    private boolean registerCallback(
            android.hardware.wifi.hostapd.V1_1.IHostapdCallback callback) {
        synchronized (mLock) {
            String methodStr = "registerCallback_1_1";
            try {
                android.hardware.wifi.hostapd.V1_1.IHostapd iHostapdV1_1 = getHostapdMockableV1_1();
                if (iHostapdV1_1 == null) return false;
                HostapdStatus status =  iHostapdV1_1.registerCallback(callback);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Initialize the IHostapd object.
     * @return true on success, false otherwise.
     */
    private boolean initHostapdService() {
        synchronized (mLock) {
            try {
                mIHostapd = getHostapdMockable();
            } catch (RemoteException e) {
                Log.e(TAG, "IHostapd.getService exception: " + e);
                return false;
            } catch (NoSuchElementException e) {
                Log.e(TAG, "IHostapd.getService exception: " + e);
                return false;
            }
            if (mIHostapd == null) {
                Log.e(TAG, "Got null IHostapd service. Stopping hostapd HIDL startup");
                return false;
            }
            if (!linkToHostapdDeath()) {
                mIHostapd = null;
                return false;
            }
            // Register for callbacks for 1.1 hostapd.
            if (isV1_1() && !registerCallback(new HostapdCallback())) {
                mIHostapd = null;
                return false;
            }
        }
        return true;
    }

    /**
     * Add and start a new access point.
     *
     * @param ifaceName Name of the interface.
     * @param config Configuration to use for the AP.
     * @param listener Callback for AP events.
     * @return true on success, false otherwise.
     */
    public boolean addAccessPoint(@NonNull String ifaceName, @NonNull WifiConfiguration config,
                                  @NonNull WifiNative.SoftApListener listener) {
        synchronized (mLock) {
            final String methodStr = "addAccessPoint";
            IHostapd.IfaceParams ifaceParams = new IHostapd.IfaceParams();
            ifaceParams.ifaceName = ifaceName;
            ifaceParams.hwModeParams.enable80211N = true;
            ifaceParams.hwModeParams.enable80211AC = mEnableIeee80211AC;
            try {
                ifaceParams.channelParams.band = getBand(config);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Unrecognized apBand " + config.apBand);
                return false;
            }
            if (mEnableAcs) {
                ifaceParams.channelParams.enableAcs = true;
                ifaceParams.channelParams.acsShouldExcludeDfs = true;
            } else {
                // Downgrade IHostapd.Band.BAND_ANY to IHostapd.Band.BAND_2_4_GHZ if ACS
                // is not supported.
                // We should remove this workaround once channel selection is moved from
                // ApConfigUtil to here.
                if (ifaceParams.channelParams.band == IHostapd.Band.BAND_ANY) {
                    Log.d(TAG, "ACS is not supported on this device, using 2.4 GHz band.");
                    ifaceParams.channelParams.band = IHostapd.Band.BAND_2_4_GHZ;
                }
                ifaceParams.channelParams.enableAcs = false;
                ifaceParams.channelParams.channel = config.apChannel;
            }

            IHostapd.NetworkParams nwParams = new IHostapd.NetworkParams();
            // TODO(b/67745880) Note that config.SSID is intended to be either a
            // hex string or "double quoted".
            // However, it seems that whatever is handing us these configurations does not obey
            // this convention.
            nwParams.ssid.addAll(NativeUtil.stringToByteArrayList(config.SSID));
            nwParams.isHidden = config.hiddenSSID;
            nwParams.encryptionType = getEncryptionType(config);
            nwParams.pskPassphrase = (config.preSharedKey != null) ? config.preSharedKey : "";
            if (!checkHostapdAndLogFailure(methodStr)) return false;
            try {
                HostapdStatus status;
                if (isV1_1()) {
                    android.hardware.wifi.hostapd.V1_1.IHostapd.IfaceParams ifaceParams1_1 =
                            new android.hardware.wifi.hostapd.V1_1.IHostapd.IfaceParams();
                    ifaceParams1_1.V1_0 = ifaceParams;
                    if (mEnableAcs) {
                        ifaceParams1_1.channelParams.acsChannelRanges.addAll(mAcsChannelRanges);
                    }
                    android.hardware.wifi.hostapd.V1_1.IHostapd iHostapdV1_1 =
                            getHostapdMockableV1_1();
                    if (iHostapdV1_1 == null) return false;
                    status = iHostapdV1_1.addAccessPoint_1_1(ifaceParams1_1, nwParams);
                } else {
                    status = mIHostapd.addAccessPoint(ifaceParams, nwParams);
                }
                if (!checkStatusAndLogFailure(status, methodStr)) {
                    return false;
                }
                mSoftApListeners.put(ifaceName, listener);
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Remove a previously started access point.
     *
     * @param ifaceName Name of the interface.
     * @return true on success, false otherwise.
     */
    public boolean removeAccessPoint(@NonNull String ifaceName) {
        synchronized (mLock) {
            final String methodStr = "removeAccessPoint";
            if (!checkHostapdAndLogFailure(methodStr)) return false;
            try {
                HostapdStatus status = mIHostapd.removeAccessPoint(ifaceName);
                if (!checkStatusAndLogFailure(status, methodStr)) {
                    return false;
                }
                mSoftApListeners.remove(ifaceName);
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Registers a death notification for hostapd.
     * @return Returns true on success.
     */
    public boolean registerDeathHandler(@NonNull HostapdDeathEventHandler handler) {
        if (mDeathEventHandler != null) {
            Log.e(TAG, "Death handler already present");
        }
        mDeathEventHandler = handler;
        return true;
    }

    /**
     * Deregisters a death notification for hostapd.
     * @return Returns true on success.
     */
    public boolean deregisterDeathHandler() {
        if (mDeathEventHandler == null) {
            Log.e(TAG, "No Death handler present");
        }
        mDeathEventHandler = null;
        return true;
    }

    /**
     * Clear internal state.
     */
    private void clearState() {
        synchronized (mLock) {
            mIHostapd = null;
        }
    }

    /**
     * Handle hostapd death.
     */
    private void hostapdServiceDiedHandler(long cookie) {
        synchronized (mLock) {
            if (mDeathRecipientCookie != cookie) {
                Log.i(TAG, "Ignoring stale death recipient notification");
                return;
            }
            clearState();
            if (mDeathEventHandler != null) {
                mDeathEventHandler.onDeath();
            }
        }
    }

    /**
     * Signals whether Initialization completed successfully.
     */
    public boolean isInitializationStarted() {
        synchronized (mLock) {
            return mIServiceManager != null;
        }
    }

    /**
     * Signals whether Initialization completed successfully.
     */
    public boolean isInitializationComplete() {
        synchronized (mLock) {
            return mIHostapd != null;
        }
    }

    /**
     * Start the hostapd daemon.
     *
     * @return true on success, false otherwise.
     */
    public boolean startDaemon() {
        synchronized (mLock) {
            try {
                // This should startup hostapd daemon using the lazy start HAL mechanism.
                getHostapdMockable();
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while trying to start hostapd: "
                        + e);
                hostapdServiceDiedHandler(mDeathRecipientCookie);
                return false;
            } catch (NoSuchElementException e) {
                // We're starting the daemon, so expect |NoSuchElementException|.
                Log.d(TAG, "Successfully triggered start of hostapd using HIDL");
            }
            return true;
        }
    }

    /**
     * Terminate the hostapd daemon.
     */
    public void terminate() {
        synchronized (mLock) {
            final String methodStr = "terminate";
            if (!checkHostapdAndLogFailure(methodStr)) return;
            try {
                mIHostapd.terminate();
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            }
        }
    }

    /**
     * Wrapper functions to access static HAL methods, created to be mockable in unit tests
     */
    @VisibleForTesting
    protected IServiceManager getServiceManagerMockable() throws RemoteException {
        synchronized (mLock) {
            return IServiceManager.getService();
        }
    }

    @VisibleForTesting
    protected IHostapd getHostapdMockable() throws RemoteException {
        synchronized (mLock) {
            return IHostapd.getService();
        }
    }

    @VisibleForTesting
    protected android.hardware.wifi.hostapd.V1_1.IHostapd getHostapdMockableV1_1()
            throws RemoteException {
        synchronized (mLock) {
            try {
                return android.hardware.wifi.hostapd.V1_1.IHostapd.castFrom(mIHostapd);
            } catch (NoSuchElementException e) {
                Log.e(TAG, "Failed to get IHostapd", e);
                return null;
            }
        }
    }

    private static int getEncryptionType(WifiConfiguration localConfig) {
        int encryptionType;
        switch (localConfig.getAuthType()) {
            case WifiConfiguration.KeyMgmt.NONE:
                encryptionType = IHostapd.EncryptionType.NONE;
                break;
            case WifiConfiguration.KeyMgmt.WPA_PSK:
                encryptionType = IHostapd.EncryptionType.WPA;
                break;
            case WifiConfiguration.KeyMgmt.WPA2_PSK:
                encryptionType = IHostapd.EncryptionType.WPA2;
                break;
            default:
                // We really shouldn't default to None, but this was how NetworkManagementService
                // used to do this.
                encryptionType = IHostapd.EncryptionType.NONE;
                break;
        }
        return encryptionType;
    }

    private static int getBand(WifiConfiguration localConfig) {
        int bandType;
        switch (localConfig.apBand) {
            case WifiConfiguration.AP_BAND_2GHZ:
                bandType = IHostapd.Band.BAND_2_4_GHZ;
                break;
            case WifiConfiguration.AP_BAND_5GHZ:
                bandType = IHostapd.Band.BAND_5_GHZ;
                break;
            case WifiConfiguration.AP_BAND_ANY:
                bandType = IHostapd.Band.BAND_ANY;
                break;
            default:
                throw new IllegalArgumentException();
        }
        return bandType;
    }

    /**
     * Convert channel list string like '1-6,11' to list of AcsChannelRanges
     */
    private List<android.hardware.wifi.hostapd.V1_1.IHostapd.AcsChannelRange>
            toAcsChannelRanges(String channelListStr) {
        ArrayList<android.hardware.wifi.hostapd.V1_1.IHostapd.AcsChannelRange> acsChannelRanges =
                new ArrayList<>();
        String[] channelRanges = channelListStr.split(",");
        for (String channelRange : channelRanges) {
            android.hardware.wifi.hostapd.V1_1.IHostapd.AcsChannelRange acsChannelRange =
                    new android.hardware.wifi.hostapd.V1_1.IHostapd.AcsChannelRange();
            try {
                if (channelRange.contains("-")) {
                    String[] channels  = channelRange.split("-");
                    if (channels.length != 2) {
                        Log.e(TAG, "Unrecognized channel range, length is " + channels.length);
                        continue;
                    }
                    int start = Integer.parseInt(channels[0]);
                    int end = Integer.parseInt(channels[1]);
                    if (start > end) {
                        Log.e(TAG, "Invalid channel range, from " + start + " to " + end);
                        continue;
                    }
                    acsChannelRange.start = start;
                    acsChannelRange.end = end;
                } else {
                    acsChannelRange.start = Integer.parseInt(channelRange);
                    acsChannelRange.end = acsChannelRange.start;
                }
            } catch (NumberFormatException e) {
                // Ignore malformed value
                Log.e(TAG, "Malformed channel value detected: " + e);
                continue;
            }
            acsChannelRanges.add(acsChannelRange);
        }
        return acsChannelRanges;
    }

    /**
     * Returns false if Hostapd is null, and logs failure to call methodStr
     */
    private boolean checkHostapdAndLogFailure(String methodStr) {
        synchronized (mLock) {
            if (mIHostapd == null) {
                Log.e(TAG, "Can't call " + methodStr + ", IHostapd is null");
                return false;
            }
            return true;
        }
    }

    /**
     * Returns true if provided status code is SUCCESS, logs debug message and returns false
     * otherwise
     */
    private boolean checkStatusAndLogFailure(HostapdStatus status,
            String methodStr) {
        synchronized (mLock) {
            if (status.code != HostapdStatusCode.SUCCESS) {
                Log.e(TAG, "IHostapd." + methodStr + " failed: " + status.code
                        + ", " + status.debugMessage);
                return false;
            } else {
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "IHostapd." + methodStr + " succeeded");
                }
                return true;
            }
        }
    }

    private void handleRemoteException(RemoteException e, String methodStr) {
        synchronized (mLock) {
            hostapdServiceDiedHandler(mDeathRecipientCookie);
            Log.e(TAG, "IHostapd." + methodStr + " failed with exception", e);
        }
    }

    private class HostapdCallback extends
            android.hardware.wifi.hostapd.V1_1.IHostapdCallback.Stub {
        @Override
        public void onFailure(String ifaceName) {
            Log.w(TAG, "Failure on iface " + ifaceName);
            WifiNative.SoftApListener listener = mSoftApListeners.get(ifaceName);
            if (listener != null) {
                listener.onFailure();
            }
        }
    }
}
