/*
 * Copyright 2018 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.telephony.AccessNetworkConstants;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.Annotation.ApnType;
import android.telephony.CarrierConfigManager;
import android.telephony.data.ApnSetting;
import android.util.LocalLog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.RIL;
import com.android.internal.telephony.dataconnection.AccessNetworksManager.QualifiedNetworks;
import com.android.internal.telephony.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.telephony.Rlog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * This class represents the transport manager which manages available transports (i.e. WWAN or
 * WLAN) and determine the correct transport for {@link TelephonyNetworkFactory} to handle the data
 * requests.
 *
 * The device can operate in the following modes, which is stored in the system properties
 * ro.telephony.iwlan_operation_mode. If the system properties is missing, then it's tied to
 * IRadio version. For 1.4 or above, it's AP-assisted mdoe. For 1.3 or below, it's legacy mode.
 *
 * Legacy mode:
 *      Frameworks send all data requests to the default data service, which is the cellular data
 *      service. IWLAN should be still reported as a RAT on cellular network service.
 *
 * AP-assisted mode:
 *      IWLAN is handled by IWLAN data service extending {@link android.telephony.data.DataService},
 *      IWLAN network service extending {@link android.telephony.NetworkService}, and qualified
 *      network service extending {@link android.telephony.data.QualifiedNetworksService}.
 *
 *      The following settings for service package name need to be configured properly for
 *      frameworks to bind.
 *
 *      Package name of data service:
 *          The resource overlay 'config_wlan_data_service_package' or,
 *          the carrier config
 *          {@link CarrierConfigManager#KEY_CARRIER_DATA_SERVICE_WLAN_PACKAGE_OVERRIDE_STRING}.
 *          The carrier config takes precedence over the resource overlay if both exist.
 *
 *      Package name of network service
 *          The resource overlay 'config_wlan_network_service_package' or
 *          the carrier config
 *          {@link CarrierConfigManager#KEY_CARRIER_NETWORK_SERVICE_WLAN_PACKAGE_OVERRIDE_STRING}.
 *          The carrier config takes precedence over the resource overlay if both exist.
 *
 *      Package name of qualified network service
 *          The resource overlay 'config_qualified_networks_service_package' or
 *          the carrier config
 *          {@link CarrierConfigManager#
 *          KEY_CARRIER_QUALIFIED_NETWORKS_SERVICE_PACKAGE_OVERRIDE_STRING}.
 *          The carrier config takes precedence over the resource overlay if both exist.
 */
public class TransportManager extends Handler {
    private static final String TAG = TransportManager.class.getSimpleName();

    // Key is the access network, value is the transport.
    private static final Map<Integer, Integer> ACCESS_NETWORK_TRANSPORT_TYPE_MAP;

    static {
        ACCESS_NETWORK_TRANSPORT_TYPE_MAP = new HashMap<>();
        ACCESS_NETWORK_TRANSPORT_TYPE_MAP.put(AccessNetworkType.GERAN,
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        ACCESS_NETWORK_TRANSPORT_TYPE_MAP.put(AccessNetworkType.UTRAN,
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        ACCESS_NETWORK_TRANSPORT_TYPE_MAP.put(AccessNetworkType.EUTRAN,
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        ACCESS_NETWORK_TRANSPORT_TYPE_MAP.put(AccessNetworkType.CDMA2000,
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        ACCESS_NETWORK_TRANSPORT_TYPE_MAP.put(AccessNetworkType.NGRAN,
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        ACCESS_NETWORK_TRANSPORT_TYPE_MAP.put(AccessNetworkType.IWLAN,
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
    }

    private static final int EVENT_QUALIFIED_NETWORKS_CHANGED = 1;

    private static final int EVENT_UPDATE_AVAILABLE_NETWORKS = 2;

    public static final String SYSTEM_PROPERTIES_IWLAN_OPERATION_MODE =
            "ro.telephony.iwlan_operation_mode";

    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = {"IWLAN_OPERATION_MODE_"},
            value = {
                    IWLAN_OPERATION_MODE_DEFAULT,
                    IWLAN_OPERATION_MODE_LEGACY,
                    IWLAN_OPERATION_MODE_AP_ASSISTED})
    public @interface IwlanOperationMode {}

    /**
     * IWLAN default mode. On device that has IRadio 1.4 or above, it means
     * {@link #IWLAN_OPERATION_MODE_AP_ASSISTED}. On device that has IRadio 1.3 or below, it means
     * {@link #IWLAN_OPERATION_MODE_LEGACY}.
     */
    public static final String IWLAN_OPERATION_MODE_DEFAULT = "default";

    /**
     * IWLAN legacy mode. IWLAN is completely handled by the modem, and when the device is on
     * IWLAN, modem reports IWLAN as a RAT.
     */
    public static final String IWLAN_OPERATION_MODE_LEGACY = "legacy";

    /**
     * IWLAN application processor assisted mode. IWLAN is handled by the bound IWLAN data service
     * and network service separately.
     */
    public static final String IWLAN_OPERATION_MODE_AP_ASSISTED = "AP-assisted";

    private final Phone mPhone;

    private final LocalLog mLocalLog = new LocalLog(100);

    /** The available transports. Must be one or more of AccessNetworkConstants.TransportType.XXX */
    private final int[] mAvailableTransports;

    @Nullable
    private AccessNetworksManager mAccessNetworksManager;

    /**
     * Current available networks. The key is the APN type, and the value is the available network
     * list in the preferred order.
     */
    private final SparseArray<int[]> mCurrentAvailableNetworks;

    /**
     * The queued available networks list.
     */
    private final LinkedList<List<QualifiedNetworks>> mAvailableNetworksList;

    /**
     * The current transport of the APN type. The key is the APN type, and the value is the
     * transport.
     */
    private final Map<Integer, Integer> mCurrentTransports;

    /**
     * The pending handover list. This is a list of APNs that are being handover to the new
     * transport. The entry will be removed once handover is completed. The key
     * is the APN type, and the value is the target transport that the APN is handovered to.
     */
    private final SparseIntArray mPendingHandoverApns;

    /**
     * The registrants for listening data handover needed events.
     */
    private final RegistrantList mHandoverNeededEventRegistrants;

    /**
     * Handover parameters
     */
    @VisibleForTesting
    public static final class HandoverParams {
        /**
         * The callback for handover complete.
         */
        public interface HandoverCallback {
            /**
             * Called when handover is completed.
             *
             * @param success {@true} if handover succeeded, otherwise failed.
             * @param fallback {@true} if handover failed, the data connection fallback to the
             * original transport
             */
            void onCompleted(boolean success, boolean fallback);
        }

        public final @ApnType int apnType;
        public final int targetTransport;
        public final HandoverCallback callback;

        @VisibleForTesting
        public HandoverParams(int apnType, int targetTransport, HandoverCallback callback) {
            this.apnType = apnType;
            this.targetTransport = targetTransport;
            this.callback = callback;
        }
    }

    public TransportManager(Phone phone) {
        mPhone = phone;
        mCurrentAvailableNetworks = new SparseArray<>();
        mCurrentTransports = new ConcurrentHashMap<>();
        mPendingHandoverApns = new SparseIntArray();
        mHandoverNeededEventRegistrants = new RegistrantList();
        mAvailableNetworksList = new LinkedList<>();

        if (isInLegacyMode()) {
            log("operates in legacy mode.");
            // For legacy mode, WWAN is the only transport to handle all data connections, even
            // the IWLAN ones.
            mAvailableTransports = new int[]{AccessNetworkConstants.TRANSPORT_TYPE_WWAN};
        } else {
            log("operates in AP-assisted mode.");
            mAccessNetworksManager = new AccessNetworksManager(phone);
            mAccessNetworksManager.registerForQualifiedNetworksChanged(this,
                    EVENT_QUALIFIED_NETWORKS_CHANGED);
            mAvailableTransports = new int[]{AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                    AccessNetworkConstants.TRANSPORT_TYPE_WLAN};
        }
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case EVENT_QUALIFIED_NETWORKS_CHANGED:
                AsyncResult ar = (AsyncResult) msg.obj;
                List<QualifiedNetworks> networks = (List<QualifiedNetworks>) ar.result;
                mAvailableNetworksList.add(networks);
                sendEmptyMessage(EVENT_UPDATE_AVAILABLE_NETWORKS);
                break;
            case EVENT_UPDATE_AVAILABLE_NETWORKS:
                updateAvailableNetworks();
                break;
            default:
                loge("Unexpected event " + msg.what);
                break;
        }
    }

    private boolean isHandoverNeeded(QualifiedNetworks newNetworks) {
        int apnType = newNetworks.apnType;
        int[] newNetworkList = newNetworks.qualifiedNetworks;
        int[] currentNetworkList = mCurrentAvailableNetworks.get(apnType);

        if (ArrayUtils.isEmpty(currentNetworkList)
                && ACCESS_NETWORK_TRANSPORT_TYPE_MAP.get(newNetworkList[0])
                == AccessNetworkConstants.TRANSPORT_TYPE_WLAN) {
            // This is a special case that when first time boot up in airplane mode with wifi on,
            // qualified network service reports IWLAN as the preferred network. Although there
            // is no live data connection on cellular, there might be network requests which were
            // already sent to cellular DCT. In this case, we still need to handover the network
            // request to the new transport.
            return true;
        }

        // If the current network list is empty, but the new network list is not, then we can
        // directly setup data on the new network. If the current network list is not empty, but
        // the new network is, then we can tear down the data directly. Therefore if one of the
        // list is empty, then we don't need to do handover.
        if (ArrayUtils.isEmpty(newNetworkList) || ArrayUtils.isEmpty(currentNetworkList)) {
            return false;
        }


        if (mPendingHandoverApns.get(newNetworks.apnType)
                == ACCESS_NETWORK_TRANSPORT_TYPE_MAP.get(newNetworkList[0])) {
            log("Handover not needed. There is already an ongoing handover.");
            return false;
        }

        // The list is networks in the preferred order. For now we only pick the first element
        // because it's the most preferred. In the future we should also consider the rest in the
        // list, for example, the first one violates carrier/user policy.
        return !ACCESS_NETWORK_TRANSPORT_TYPE_MAP.get(newNetworkList[0])
                .equals(getCurrentTransport(newNetworks.apnType));
    }

    private static boolean areNetworksValid(QualifiedNetworks networks) {
        if (networks.qualifiedNetworks == null || networks.qualifiedNetworks.length == 0) {
            return false;
        }
        for (int network : networks.qualifiedNetworks) {
            if (!ACCESS_NETWORK_TRANSPORT_TYPE_MAP.containsKey(network)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Set the current transport of apn type.
     *
     * @param apnType The APN type
     * @param transport The transport. Must be WWAN or WLAN.
     */
    private synchronized void setCurrentTransport(@ApnType int apnType, int transport) {
        mCurrentTransports.put(apnType, transport);
        logl("setCurrentTransport: apnType=" + ApnSetting.getApnTypeString(apnType)
                + ", transport=" + AccessNetworkConstants.transportTypeToString(transport));
    }

    private boolean isHandoverPending() {
        return mPendingHandoverApns.size() > 0;
    }

    private void updateAvailableNetworks() {
        if (isHandoverPending()) {
            log("There's ongoing handover. Will update networks once handover completed.");
            return;
        }

        if (mAvailableNetworksList.size() == 0) {
            log("Nothing in the available network list queue.");
            return;
        }

        List<QualifiedNetworks> networksList = mAvailableNetworksList.remove();
        logl("updateAvailableNetworks: " + networksList);
        for (QualifiedNetworks networks : networksList) {
            if (areNetworksValid(networks)) {
                if (isHandoverNeeded(networks)) {
                    // If handover is needed, perform the handover works. For now we only pick the
                    // first element because it's the most preferred. In the future we should also
                    // consider the rest in the list, for example, the first one violates
                    // carrier/user policy.
                    int targetTransport = ACCESS_NETWORK_TRANSPORT_TYPE_MAP.get(
                            networks.qualifiedNetworks[0]);
                    logl("Handover needed for APN type: "
                            + ApnSetting.getApnTypeString(networks.apnType) + ", target transport: "
                            + AccessNetworkConstants.transportTypeToString(targetTransport));
                    mPendingHandoverApns.put(networks.apnType, targetTransport);
                    mHandoverNeededEventRegistrants.notifyResult(
                            new HandoverParams(networks.apnType, targetTransport,
                                    (success, fallback) -> {
                                        // The callback for handover completed.
                                        if (success) {
                                            logl("Handover succeeded.");
                                        } else {
                                            logl("APN type "
                                                    + ApnSetting.getApnTypeString(networks.apnType)
                                                    + " handover to "
                                                    + AccessNetworkConstants.transportTypeToString(
                                                    targetTransport) + " failed."
                                                    + ", fallback=" + fallback);
                                        }
                                        if (success || !fallback) {
                                            // If handover succeeds or failed without falling back
                                            // to the original transport, we should move to the new
                                            // transport (even if it is failed).
                                            setCurrentTransport(networks.apnType, targetTransport);
                                        }
                                        mPendingHandoverApns.delete(networks.apnType);

                                        // If there are still pending available network changes, we
                                        // need to process the rest.
                                        if (mAvailableNetworksList.size() > 0) {
                                            sendEmptyMessage(EVENT_UPDATE_AVAILABLE_NETWORKS);
                                        }
                                    }));
                }
                mCurrentAvailableNetworks.put(networks.apnType, networks.qualifiedNetworks);
            } else {
                loge("Invalid networks received: " + networks);
            }
        }

        // If there are still pending available network changes, we need to process the rest.
        if (mAvailableNetworksList.size() > 0) {
            sendEmptyMessage(EVENT_UPDATE_AVAILABLE_NETWORKS);
        }
    }

    /**
     * @return The available transports. Note that on legacy devices, the only available transport
     * would be WWAN only. If the device is configured as AP-assisted mode, the available transport
     * will always be WWAN and WLAN (even if the device is not camped on IWLAN).
     * See {@link #isInLegacyMode()} for mode details.
     */
    public synchronized @NonNull int[] getAvailableTransports() {
        return mAvailableTransports;
    }

    /**
     * @return {@code true} if the device operates in legacy mode, otherwise {@code false}.
     */
    public boolean isInLegacyMode() {
        // Get IWLAN operation mode from the system property. If the system property is missing or
        // mis-configured the default behavior is tied to the IRadio version. For 1.4 or above, it's
        // AP-assisted mode, for 1.3 or below, it's legacy mode. For IRadio 1.3 or below, no matter
        // what the configuration is, it will always be legacy mode.
        String mode = SystemProperties.get(SYSTEM_PROPERTIES_IWLAN_OPERATION_MODE);

        return mode.equals(IWLAN_OPERATION_MODE_LEGACY)
                || mPhone.getHalVersion().less(RIL.RADIO_HAL_VERSION_1_4);
    }

    /**
     * Get the transport based on the APN type.
     *
     * @param apnType APN type
     * @return The transport type
     */
    public int getCurrentTransport(@ApnType int apnType) {
        // In legacy mode, always route to cellular.
        if (isInLegacyMode()) {
            return AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
        }

        // If we can't find the corresponding transport, always route to cellular.
        return mCurrentTransports.get(apnType) == null
                ? AccessNetworkConstants.TRANSPORT_TYPE_WWAN : mCurrentTransports.get(apnType);
    }

    /**
     * Check if there is any APN type of network preferred on IWLAN.
     *
     * @return {@code true} if there is any APN preferred on IWLAN, otherwise {@code false}.
     */
    public boolean isAnyApnPreferredOnIwlan() {
        for (int i = 0; i < mCurrentAvailableNetworks.size(); i++) {
            int[] networkList = mCurrentAvailableNetworks.valueAt(i);
            if (networkList.length > 0 && networkList[0] == AccessNetworkType.IWLAN) {
                return true;
            }
        }
        return false;
    }

    /**
     * Register for data handover needed event
     *
     * @param h The handler of the event
     * @param what The id of the event
     */
    public void registerForHandoverNeededEvent(Handler h, int what) {
        if (h != null) {
            mHandoverNeededEventRegistrants.addUnique(h, what, null);
        }
    }

    /**
     * Unregister for data handover needed event
     *
     * @param h The handler
     */
    public void unregisterForHandoverNeededEvent(Handler h) {
        mHandoverNeededEventRegistrants.remove(h);
    }

    /**
     * Dump the state of transport manager
     *
     * @param fd File descriptor
     * @param printwriter Print writer
     * @param args Arguments
     */
    public void dump(FileDescriptor fd, PrintWriter printwriter, String[] args) {
        IndentingPrintWriter pw = new IndentingPrintWriter(printwriter, "  ");
        pw.println("TransportManager:");
        pw.increaseIndent();
        pw.println("mAvailableTransports=[" + Arrays.stream(mAvailableTransports)
                .mapToObj(type -> AccessNetworkConstants.transportTypeToString(type))
                .collect(Collectors.joining(",")) + "]");
        pw.println("mCurrentAvailableNetworks=" + mCurrentAvailableNetworks);
        pw.println("mAvailableNetworksList=" + mAvailableNetworksList);
        pw.println("mPendingHandoverApns=" + mPendingHandoverApns);
        pw.println("mCurrentTransports=" + mCurrentTransports);
        pw.println("isInLegacy=" + isInLegacyMode());
        pw.println("IWLAN operation mode="
                + SystemProperties.get(SYSTEM_PROPERTIES_IWLAN_OPERATION_MODE));
        if (mAccessNetworksManager != null) {
            mAccessNetworksManager.dump(fd, pw, args);
        }
        pw.println("Local logs=");
        pw.increaseIndent();
        mLocalLog.dump(fd, pw, args);
        pw.decreaseIndent();
        pw.decreaseIndent();
        pw.flush();
    }

    private void logl(String s) {
        log(s);
        mLocalLog.log(s);
    }

    private void log(String s) {
        Rlog.d(TAG, s);
    }

    private void loge(String s) {
        Rlog.e(TAG, s);
    }
}
