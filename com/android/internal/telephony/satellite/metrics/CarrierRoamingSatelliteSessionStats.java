/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.telephony.satellite.metrics;

import static android.telephony.TelephonyManager.ACTION_DATA_STALL_DETECTED;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;

import android.annotation.NonNull;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkTemplate;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.telephony.CellInfo;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthLte;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.PreciseDataConnectionState;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.metrics.SatelliteStats;
import com.android.internal.telephony.satellite.SatelliteConstants;
import com.android.internal.telephony.satellite.SatelliteServiceUtils;
import com.android.internal.telephony.subscription.SubscriptionInfoInternal;
import com.android.internal.telephony.subscription.SubscriptionManagerService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.Executor;

public class CarrierRoamingSatelliteSessionStats {
    private static final String TAG = CarrierRoamingSatelliteSessionStats.class.getSimpleName();
    private static final SparseArray<CarrierRoamingSatelliteSessionStats>
            sCarrierRoamingSatelliteSessionStats = new SparseArray<>();
    @NonNull private final SubscriptionManagerService mSubscriptionManagerService;
    private int mCarrierId;
    private boolean mIsNtnRoamingInHomeCountry;
    private int mCountOfIncomingSms;
    private int mCountOfOutgoingSms;
    private int mCountOfIncomingMms;
    private int mCountOfOutgoingMms;
    private long mIncomingMessageId;
    private int mSessionStartTimeSec;
    private SatelliteConnectionTimes mSatelliteConnectionTimes;
    private List<SatelliteConnectionTimes> mSatelliteConnectionTimesList;
    private List<Integer> mRsrpList;
    private List<Integer> mRssnrList;
    private int[] mSupportedSatelliteServices;
    private int mServiceDataPolicy;
    private Phone mPhone;
    private Context mContext;
    private long mSatelliteDataConsumedBytes = 0L;
    private long mDataUsageOnSessionStartBytes = 0L;
    private Map<String, Long> mPerAppDataUsageOnSessionStartMap = new HashMap<>();
    private Map<String, Integer> mSatelliteAppUidMap = new HashMap<>();
    private int[] mLastFailCauses = new int[5];
    private int mCountOfDataConnections = 0;
    private int mCountOfDataDisconnections = 0;
    private int mCurrentState = TelephonyManager.DATA_DISCONNECTED;
    private int mFailCauseIndex = 0;
    private int mCountOfDataStalls = 0;
    private int mSumOfDownlinkBandwidthKbps = 0;
    private int mSumOfUplinkBandwidthKbps = 0;
    private int mAverageUplinkBandwidthKbps = 0;
    private int mAverageDownlinkBandwidthKbps = 0;
    private int mMinUplinkBandwidthKbps = Integer.MAX_VALUE;
    private int mMaxUplinkBandwidthKbps = 0;
    private int mMaxDownlinkBandwidthKbps = 0;
    private int mMinDownlinkBandwidthKbps = Integer.MAX_VALUE;
    private final Executor mExecutor = Runnable::run;
    private DataStallIntentReceiver mDataStallIntentReceiver;
    private TelephonyListenerImpl mTelephonyListener;
    private ConnectivityManager mConnectivityManager;
    private NetworkCapabilities mNetworkcapabilities;
    @NonNull private FeatureFlags mFeatureFlags;
    String[] mSatelliteAppsPackageNameArray = null;
    private long[] mPerAppSatelliteDataConsumedBytesArray = new long[]{0L};
    private static final int MAX_SATELLITE_TOP_APPS_TRACKED = 5;
    private int[] mSatelliteAppsUidArray = new int[MAX_SATELLITE_TOP_APPS_TRACKED];
    private @SatelliteConstants.SatelliteGlobalConnectType int mSupportedConnectionMode;
    private @SatelliteConstants.SatelliteSessionConnectType int mSessionConnectionMode;
    private String mPlmn;
    private boolean mIsWifiConnected;
    private boolean mIsWifiEnabled;
    private boolean mIsWfcEnabled;
    private boolean mIsWfcRegistered;
    private int mAccumulatedScreenOnTimeSec;
    private long mScreenOnStartTimeMillis;

    private final ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    logd("On Available: " + network);
                    if (network != null) {
                        if (mConnectivityManager != null) {
                            mNetworkcapabilities =
                                    mConnectivityManager.getNetworkCapabilities(network);
                            if (mNetworkcapabilities != null
                                    && mNetworkcapabilities.hasTransport(
                                    NetworkCapabilities.TRANSPORT_SATELLITE)
                                    && mNetworkcapabilities.hasCapability(
                                    NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                                logd("found satellite data connection");
                                startDataConnectionTracker();
                            }
                        }
                    }
                }

                @Override
                public void onCapabilitiesChanged(Network network,
                        NetworkCapabilities networkCapabilities) {
                    logd("onCapabilitiesChanged: " + network);
                    mNetworkcapabilities = networkCapabilities;
                }
            };

    public class DataStallIntentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (intent.getAction().equals(ACTION_DATA_STALL_DETECTED)) {
                Bundle dsrsStatsBundle = intent.getBundleExtra("EXTRA_DSRS_STATS_BUNDLE");
                if (dsrsStatsBundle != null && dsrsStatsBundle.containsKey("IsRecovered")) {
                    boolean isRecovered = false;
                    if (dsrsStatsBundle.get("IsRecovered") instanceof Integer) {
                        isRecovered = dsrsStatsBundle.getInt("IsRecovered") == 1;
                    } else if (dsrsStatsBundle.get("IsRecovered") instanceof Boolean) {
                        isRecovered = dsrsStatsBundle.getBoolean("IsRecovered");
                    }
                    if (!isRecovered && mCurrentState == TelephonyManager.DATA_CONNECTED) {
                        mCountOfDataStalls++;
                        logd("data stall count: " + mCountOfDataStalls);
                    }
                }
            }
        }
    };

    private class TelephonyListenerImpl extends TelephonyCallback
            implements TelephonyCallback.PreciseDataConnectionStateListener {
        private final Executor mExecutor;
        private TelephonyManager mTelephonyManager = null;

        TelephonyListenerImpl(Executor executor) {
            mExecutor = executor;
        }

        public void register(TelephonyManager tm) {
            if (tm == null) {
                return;
            }
            mTelephonyManager = tm;
            mTelephonyManager.registerTelephonyCallback(mExecutor, this);
        }

        public void unregister() {
            if (mTelephonyManager != null) {
                mTelephonyManager.unregisterTelephonyCallback(this);
                mTelephonyManager = null;
            }
        }

        @Override
        public void onPreciseDataConnectionStateChanged(
                @NonNull PreciseDataConnectionState preciseDataConnectionState) {
            // For debugging purpose
            logd("Received PrecisionDataStateChange:" + preciseDataConnectionState);
            if (preciseDataConnectionState != null) {
                int apnTypeBitMask = preciseDataConnectionState.getApnSetting().getApnTypeBitmask();
                if ((apnTypeBitMask & ApnSetting.TYPE_DEFAULT) > 0) {
                    int newState = preciseDataConnectionState.getState();
                    logd("Internet Connection status: " + newState);
                    if (mCurrentState != newState) {
                        if (newState == TelephonyManager.DATA_CONNECTED) {
                            handleConnection();
                            updateLinkBandwidthForConnection();
                        } else if (newState == TelephonyManager.DATA_DISCONNECTED) {
                            handleDisconnection();
                            storeFailCause(preciseDataConnectionState.getLastCauseCode());
                        }
                    }
                }
            }
        }
    };

    private void updateLinkBandwidthForConnection() {
        if (mNetworkcapabilities != null) {
            int uplink = mNetworkcapabilities.getLinkUpstreamBandwidthKbps();
            int downlink = mNetworkcapabilities.getLinkDownstreamBandwidthKbps();

            mSumOfUplinkBandwidthKbps += uplink;
            mSumOfDownlinkBandwidthKbps += downlink;

            if (uplink > 0) {
                mMaxUplinkBandwidthKbps = Math.max(mMaxUplinkBandwidthKbps, uplink);
                mMinUplinkBandwidthKbps = Math.min(mMinUplinkBandwidthKbps, uplink);

            }
            if (downlink > 0) {
                mMaxDownlinkBandwidthKbps = Math.max(mMaxDownlinkBandwidthKbps, downlink);
                mMinDownlinkBandwidthKbps = Math.min(mMinDownlinkBandwidthKbps, downlink);
            }
        } else {
            loge("networkcapabilities found null");
        }
    }

    private boolean registerTelephonyListener() {
        int subId = mPhone.getSubId();
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return false;
        }

        TelephonyManager telephonyManager = mContext.getSystemService(TelephonyManager.class);
        if (telephonyManager != null) {
            mTelephonyListener = new TelephonyListenerImpl(mExecutor);
            mTelephonyListener.register(telephonyManager.createForSubscriptionId(subId));
        }

        return true;
    }

    private void handleConnection() {
        mCountOfDataConnections++;
        logd("Connection established. Total connections: " + mCountOfDataConnections);
        mCurrentState = TelephonyManager.DATA_CONNECTED;
    }

    private void handleDisconnection() {
        mCountOfDataDisconnections++;
        logd("Connection lost. Total disconnections: " + mCountOfDataDisconnections);
        mCurrentState = TelephonyManager.DATA_DISCONNECTED;
    }

    private void storeFailCause(int failCause) {
        mLastFailCauses[mFailCauseIndex] = failCause;
        mFailCauseIndex = (mFailCauseIndex + 1) % 5; // Circular buffer
        logd("current fail causes: " + Arrays.toString(mLastFailCauses));
    }

    public CarrierRoamingSatelliteSessionStats(int subId) {
        logd("Create new CarrierRoamingSatelliteSessionStats. subId=" + subId);
        initializeParams();
        mSubscriptionManagerService = SubscriptionManagerService.getInstance();
    }

    /** Gets a CarrierRoamingSatelliteSessionStats instance. */
    public static CarrierRoamingSatelliteSessionStats getInstance(int subId) {
        synchronized (sCarrierRoamingSatelliteSessionStats) {
            if (sCarrierRoamingSatelliteSessionStats.get(subId) == null) {
                sCarrierRoamingSatelliteSessionStats.put(
                        subId, new CarrierRoamingSatelliteSessionStats(subId));
            }
            return sCarrierRoamingSatelliteSessionStats.get(subId);
        }
    }

    /**
     * Clear all CarrierRoamingSatelliteSessionStats instances. This is used for testing only.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public static void clearInstancesForTest() {
        sCarrierRoamingSatelliteSessionStats.clear();
    }

    /** Log carrier roaming satellite session start */
    public void onSessionStart(
            int carrierId, Phone phone, int[] supportedServices, int serviceDataPolicy,
            List<String> satelliteApps, int supportedConnectionMode, int sessionConnectionMode,
            String plmn, @NonNull FeatureFlags featureFlags, boolean isScreenOn,
            boolean isWifiConnected) {
        mPhone = phone;
        mContext = mPhone.getContext();
        mCarrierId = carrierId;
        mSupportedSatelliteServices = supportedServices;
        mServiceDataPolicy = serviceDataPolicy;
        mSessionStartTimeSec = getElapsedRealtimeInSec();
        mIsNtnRoamingInHomeCountry = false;
        onConnectionStart(mPhone);
        mDataUsageOnSessionStartBytes = getDataUsage();
        logd("current data consumed: " + mDataUsageOnSessionStartBytes);
        mSupportedConnectionMode = supportedConnectionMode;
        mPlmn = plmn;
        mSessionConnectionMode = sessionConnectionMode;
        mFeatureFlags = featureFlags;
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            mIsWifiEnabled = wifiManager.isWifiEnabled();
        }
        mIsWfcEnabled = mPhone.isWifiCallingEnabled();
        mIsWfcRegistered = mIsWfcEnabled
                && mPhone.isImsRegistered()
                && mPhone.getImsRegistrationTech() == REGISTRATION_TECH_IWLAN;
        logd("mIsWifiEnabled: " + mIsWifiEnabled + ", mIsWfcEnabled: " + mIsWfcEnabled
                + ", mIsWfcRegistered: " + mIsWfcRegistered);
        registerForSatelliteDataNetworkCallback();
        mPerAppDataUsageOnSessionStartMap = getPerAppSatelliteDataUsage(satelliteApps);
        mAccumulatedScreenOnTimeSec = 0;
        if (mFeatureFlags.satelliteMetricsEnhancement()) {
            if (isScreenOn) {
                mScreenOnStartTimeMillis = getElapsedRealtime();
            } else {
                mScreenOnStartTimeMillis = 0;
            }
        }
        mIsWifiConnected = isWifiConnected;
    }

    /** Log carrier roaming satellite connection start */
    public void onConnectionStart(Phone phone) {
        mSatelliteConnectionTimes = new SatelliteConnectionTimes(getElapsedRealtime());
        updateNtnRoamingInHomeCountry(phone);
    }

    private void registerDataStallIntentReceiver() {
        if (mDataStallIntentReceiver == null) {
            logd("Track data stall status");
            mDataStallIntentReceiver = new DataStallIntentReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_DATA_STALL_DETECTED);
            mContext.registerReceiver(mDataStallIntentReceiver, filter, Context.RECEIVER_EXPORTED);
        }
    }

    private void registerForSatelliteDataNetworkCallback() {
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.addTransportType(NetworkCapabilities.TRANSPORT_SATELLITE);
        builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED);
        builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        mConnectivityManager = (ConnectivityManager) mPhone.getContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (mConnectivityManager != null) {
            logd("register for best matching");
            HandlerThread satelliteNetworkHandlerThread = new HandlerThread(
                    "SatelliteNetworkCallbackThread");
            satelliteNetworkHandlerThread.start(); // Start the thread
            Handler satelliteNetworkHandler =
                    new Handler(satelliteNetworkHandlerThread.getLooper());
            mConnectivityManager.registerBestMatchingNetworkCallback(
                    builder.build(), mNetworkCallback, satelliteNetworkHandler);
        } else {
            loge("network callback not registered");
        }
    }

    private void startDataConnectionTracker() {
        logd("Start tracking data disconnection for the cause");

        if (mTelephonyListener ==  null) {
            // track precision data state changes
            if (registerTelephonyListener()) {
                // track data stall status
                registerDataStallIntentReceiver();
            } else {
                loge("fails tor register for precision data connection state change");
            }
        } else {
            loge("telephony listener is registered");
        }
    }

    /** calculate total satellite data consumed at the session */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PROTECTED)
    protected long getDataUsage() {
        if (mContext == null) {
            return 0L;
        }

        NetworkStatsManager networkStatsManager =
                mContext.getSystemService(NetworkStatsManager.class);

        if (networkStatsManager != null) {
            final NetworkTemplate.Builder builder =
                    new NetworkTemplate.Builder(NetworkTemplate.MATCH_MOBILE);
            final String subscriberId = mPhone.getSubscriberId();
            logd("subscriber id for data consumed:" + subscriberId);

            if (!TextUtils.isEmpty(subscriberId)) {
                builder.setSubscriberIds(Set.of(subscriberId));
                // Consider data usage calculation of only metered capabilities / data network
                builder.setMeteredness(android.net.NetworkStats.METERED_YES);
                NetworkTemplate template = builder.build();
                final NetworkStats.Bucket ret =
                        networkStatsManager.querySummaryForDevice(
                                template, 0L, System.currentTimeMillis());
                return ret.getRxBytes() + ret.getTxBytes();
            }
        }
        return 0L;
    }

    private void stopDataConnectionTracker() {
        logd("deregister tracking data disconnection and cause");
        // unregister the data stall intent receiver
        if (mDataStallIntentReceiver != null) {
            mContext.unregisterReceiver(mDataStallIntentReceiver);
            mDataStallIntentReceiver = null;
        }

        // unregister the precise data connection state change listener
        if (mTelephonyListener != null) {
            mTelephonyListener.unregister();
            mTelephonyListener = null;
        }
    }

    private int updateAvgBandwidthForSession(int bandwidth, int numConnections) {
        double result = (double) bandwidth / numConnections; // Cast to double for accurate division
        return (int) Math.round(result); // Round to the nearest int
    }

    private void deregisterSatelliteDataNetworkCallback() {
        logd("unregister callbacks");
        // Session End can be received before onLost() at network callback. So stop the
        // listener and data stall intent receiver tracking if running
        stopDataConnectionTracker();
        if (mConnectivityManager != null) {
            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
            mConnectivityManager = null;
        }
    }

    private void logSatelliteAppData() {
        if (mSatelliteAppsPackageNameArray == null || mSatelliteAppsPackageNameArray.length == 0) {
            Log.d(TAG, "No satellite app data to log.");
            return;
        }

        for (int i = 0; i < mSatelliteAppsPackageNameArray.length; i++) {
            // Ensure array bounds are checked if arrays might not be perfectly synchronized,
            // though in this context they should be of the same length.
            if (i < mSatelliteAppsUidArray.length
                    && i < mPerAppSatelliteDataConsumedBytesArray.length) {
                Log.d(TAG, "Package: " + mSatelliteAppsPackageNameArray[i]
                        + ", UID: " + mSatelliteAppsUidArray[i]
                        + ", Bytes: " + mPerAppSatelliteDataConsumedBytesArray[i]);
            } else {
                Log.w(TAG, "Mismatched array lengths at index: "
                        + i + ". Skipping logging for this entry.");
                break; // Or continue, depending on desired behavior
            }
        }
    }

    private void updatePerAppDataConsumedMaptoArray(Map<String, Long> appDataUsageMap) {
        if (appDataUsageMap == null || appDataUsageMap.isEmpty()) {
            Log.w(TAG, "No satellite data usage found. The app data usage map is null or empty.");
            return;
        }

        // Extract package names
        mSatelliteAppsPackageNameArray = appDataUsageMap.keySet().toArray(new String[0]);

        // Extract UIDs using streams
        mSatelliteAppsUidArray = Arrays.stream(mSatelliteAppsPackageNameArray)
                .mapToInt(packageName -> mSatelliteAppUidMap.getOrDefault(packageName, -1))
                .toArray();

        // Extract data consumption bytes
        mPerAppSatelliteDataConsumedBytesArray = appDataUsageMap.values().stream()
                .mapToLong(Long::longValue) // Convert Long objects to primitive long values
                .toArray();

        // Log the processed data for verification
        logSatelliteAppData();
    }

    private Map<String, Long> computePerAppSatelliteDataUsageWithSession(Map<String, Long> map2) {
        // The satelliteSessionUsageMap is initialized as a HashMap, and now the method signature
        // explicitly states that a HashMap will be returned.
        HashMap<String, Long> satelliteSessionUsageMap = new HashMap<>();

        // Iterate through each entry in Map2
        for (Map.Entry<String, Long> entry : map2.entrySet()) {
            String key = entry.getKey();
            Long currentDataUsageBytes = entry.getValue();
            long dataUsageDuringSession;

            // Check if the package exists in the session-start map
            if (mPerAppDataUsageOnSessionStartMap.containsKey(key)) {
                long initialDataUsageBytes = mPerAppDataUsageOnSessionStartMap.get(key);
                dataUsageDuringSession = currentDataUsageBytes - initialDataUsageBytes;
            } else {
                // If not in the start map, usage is the current value
                dataUsageDuringSession = currentDataUsageBytes;
            }

            // Only store positive data usage values
            if (dataUsageDuringSession > 0) {
                satelliteSessionUsageMap.put(key, dataUsageDuringSession);
            }
        }
        return satelliteSessionUsageMap;
    }

    private Map<String, Long> findTopNPackagesWithMaxData(Map<String, Long> dataConsumptionMap) {

        // Handle null or empty input map
        if (dataConsumptionMap == null || dataConsumptionMap.isEmpty()) {
            return new HashMap<>(); // Return an empty HashMap
        }

        // Convert the HashMap to a List of Map.Entry for sorting
        List<Map.Entry<String, Long>> list =
                new LinkedList<>(dataConsumptionMap.entrySet());

        // Sort the list in descending order of data consumed
        Collections.sort(list, new Comparator<Map.Entry<String, Long>>() {
            public int compare(Map.Entry<String, Long> o1,
                    Map.Entry<String, Long> o2) {
                return (o2.getValue()).compareTo(o1.getValue());
            }
        });

        // Create a HashMap to store the top 5 entries (order is not guaranteed)
        HashMap<String, Long> result = new HashMap<>();
        int count = 0;
        for (Map.Entry<String, Long> entry : list) {
            if (count < MAX_SATELLITE_TOP_APPS_TRACKED) {
                result.put(entry.getKey(), entry.getValue());
                count++;
            } else {
                // We have found our top 5
                break;
            }
        }

        return result;
    }

    /** Log carrier roaming satellite session end */
    public void onSessionEnd(int subId, List<String> satelliteApps) {
        onConnectionEnd();
        long dataUsageOnSessionEndBytes = getDataUsage();
        logd("update data consumed: " + dataUsageOnSessionEndBytes);
        if (dataUsageOnSessionEndBytes > 0L
                && dataUsageOnSessionEndBytes > mDataUsageOnSessionStartBytes) {
            mSatelliteDataConsumedBytes =
                    dataUsageOnSessionEndBytes - mDataUsageOnSessionStartBytes;
        }
        logd("satellite data consumed at session: " + mSatelliteDataConsumedBytes);

        Map<String, Long> perAppDataUsageOnSessionEndMap = getPerAppSatelliteDataUsage(
                satelliteApps);
        if (!perAppDataUsageOnSessionEndMap.isEmpty()) {
            Map<String, Long> currSatelliteSessionPerAppDataUsageMap =
                    computePerAppSatelliteDataUsageWithSession(perAppDataUsageOnSessionEndMap);
            Map<String, Long> top5PackagesWithMaxDataMap =
                    findTopNPackagesWithMaxData(currSatelliteSessionPerAppDataUsageMap);
            logd("top 5 satellite data usage apps:" + top5PackagesWithMaxDataMap);
            updatePerAppDataConsumedMaptoArray(top5PackagesWithMaxDataMap);
        } else {
            loge("per app satellite consumed array is empty");
        }

        if (mSumOfDownlinkBandwidthKbps > 0 && mCountOfDataConnections > 0) {
            mAverageDownlinkBandwidthKbps = updateAvgBandwidthForSession(
                    mSumOfDownlinkBandwidthKbps, mCountOfDataConnections);
        }
        if (mSumOfUplinkBandwidthKbps > 0 && mCountOfDataConnections > 0) {
            mAverageUplinkBandwidthKbps = updateAvgBandwidthForSession(mSumOfUplinkBandwidthKbps,
                    mCountOfDataConnections);
        }
        if (mCurrentState == TelephonyManager.DATA_CONNECTED) {
            handleDisconnection();
            storeFailCause(65535 /*lost connection cause*/);
        }
        reportMetrics(subId);
        mIsNtnRoamingInHomeCountry = false;
        mSupportedSatelliteServices = new int[0];
        mServiceDataPolicy = SatelliteConstants.SATELLITE_ENTITLEMENT_SERVICE_POLICY_UNKNOWN;
        mSatelliteDataConsumedBytes = 0L;
        mSatelliteAppsPackageNameArray = null;
        Arrays.fill(mSatelliteAppsUidArray, 0);
        mPerAppSatelliteDataConsumedBytesArray = new long[]{0L};
        mDataUsageOnSessionStartBytes = 0L;
        resetSatelliteDataState();
    }

    private Map<String, Integer> updatePackageNameWithUids(List<String> satelliteApps) {
        HashMap<String, Integer> satelliteAppMap = new HashMap<>();

        try {
            if (mContext == null) {
                // Log an error or throw an exception if context is null
                // For example: Log.e("AppUidResolver", "Context cannot be null.");
                return satelliteAppMap;
            }

            if (satelliteApps == null || satelliteApps.isEmpty()) {
                return satelliteAppMap;
            }

            PackageManager packageManager = mContext.getPackageManager();
            for (String packageName : satelliteApps) {
                try {
                    // Get ApplicationInfo for the package.
                    // The flag 0 means no specific flags are requested.
                    ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
                    // Load the label (application name) using the PackageManager
                    satelliteAppMap.put(packageName, appInfo.uid);
                } catch (PackageManager.NameNotFoundException e) {
                    loge("Package Manager found exception");
                }
            }
        } catch (Exception e) {
            loge("found exception on finding uids:" + e);
        }
        return satelliteAppMap;
    }

    private String findKeysByValue(Map<String, Integer> map, Integer targetValue) {
        String key = null;

        if (map == null || map.isEmpty()) {
            logd("Found Packagename: " + null);
            return key;
        }

        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (entry.getValue().equals(targetValue)) {
                key = entry.getKey();
                logd("Found Packagename: " + key);
            }
        }
        return key;
    }

    private void updateDataUsageMap(NetworkStats networkStats, Map<String, Long> dataUsageMap) {
        long totalBytes;
        NetworkStats.Bucket bucket = new NetworkStats.Bucket();
        while (networkStats.hasNextBucket()) {
            totalBytes = 0L;
            networkStats.getNextBucket(bucket);
            if (bucket.getUid() != -1 && mSatelliteAppUidMap.containsValue(bucket.getUid())) {
                String packageName =
                        findKeysByValue(mSatelliteAppUidMap, bucket.getUid());
                if (dataUsageMap.containsKey(packageName)) {
                    totalBytes = dataUsageMap.getOrDefault(packageName, 0L);
                }
                totalBytes += bucket.getRxBytes() + bucket.getTxBytes();
                if (totalBytes > 0) {
                    dataUsageMap.put(packageName, totalBytes);
                }
            }
        }
    }

    private Map<String, Long> getPerAppSatelliteDataUsage(@NonNull List<String> satelliteApps) {
        Map<String, Long> dataUsageMap = new HashMap<>();

        // track satellite data usage of satellite constrained apps
        logd("satellite app List: " + satelliteApps);
        if (!satelliteApps.isEmpty()) {
            mSatelliteAppUidMap = updatePackageNameWithUids(satelliteApps);
            logd("satellite App Map: " + mSatelliteAppUidMap);
            if (!mSatelliteAppUidMap.isEmpty()) {
                NetworkStatsManager networkStatsManager =
                        mContext.getSystemService(NetworkStatsManager.class);
                final String subscriberId = mPhone.getSubscriberId();
                if (networkStatsManager != null && !TextUtils.isEmpty(subscriberId)) {
                    final NetworkTemplate.Builder builder =
                            new NetworkTemplate.Builder(NetworkTemplate.MATCH_MOBILE);
                    logd("subscriber id for data consumed: " + subscriberId);
                    try {
                        builder.setSubscriberIds(Set.of(subscriberId));
                        // Consider data usage calculation of only metered capabilities
                        // data network
                        builder.setMeteredness(android.net.NetworkStats.METERED_YES);
                        NetworkStats networkStats;
                        NetworkTemplate template = builder.build();
                        networkStats =
                                networkStatsManager.querySummary(template, 0,
                                        System.currentTimeMillis());
                        updateDataUsageMap(networkStats, dataUsageMap);
                    } catch (SecurityException e) {
                        loge("querying networkstats met with approach:" + e);
                    }
                }
            }
            logd("NetworkStats per apps: " + dataUsageMap);
        } else {
            loge("Satellite apps list is empty");
        }
        return dataUsageMap;
    }

    private void resetSatelliteDataState() {
        deregisterSatelliteDataNetworkCallback();
        Arrays.fill(mLastFailCauses, 0);
        mFailCauseIndex = 0;
        mCurrentState = TelephonyManager.DATA_DISCONNECTED;
        mCountOfDataConnections = 0;
        mCountOfDataDisconnections = 0;
        mCountOfDataStalls = 0;
        mAverageUplinkBandwidthKbps = 0;
        mAverageDownlinkBandwidthKbps = 0;
        mSumOfDownlinkBandwidthKbps = 0;
        mSumOfUplinkBandwidthKbps = 0;
        mMinUplinkBandwidthKbps = Integer.MAX_VALUE;
        mMaxUplinkBandwidthKbps = 0;
        mMinDownlinkBandwidthKbps = Integer.MAX_VALUE;
        mMaxDownlinkBandwidthKbps = 0;
    }

    /** Log carrier roaming satellite connection end */
    public void onConnectionEnd() {
        if (mSatelliteConnectionTimes != null) {
            mSatelliteConnectionTimes.setEndTime(getElapsedRealtime());
            mSatelliteConnectionTimesList.add(mSatelliteConnectionTimes);
            mSatelliteConnectionTimes = null;
        } else {
            loge("onConnectionEnd: mSatelliteConnectionTimes is null");
        }
    }

    /** Log rsrp and rssnr when occurred the service state change with NTN is connected. */
    public void onSignalStrength(Phone phone) {
        CellSignalStrengthLte cellSignalStrengthLte = getCellSignalStrengthLte(phone);
        int rsrp = cellSignalStrengthLte.getRsrp();
        int rssnr = cellSignalStrengthLte.getRssnr();
        if (rsrp == CellInfo.UNAVAILABLE) {
            logd("onSignalStrength: rsrp unavailable");
            return;
        }
        if (rssnr == CellInfo.UNAVAILABLE) {
            logd("onSignalStrength: rssnr unavailable");
            return;
        }
        mRsrpList.add(rsrp);
        mRssnrList.add(rssnr);
        logd("onSignalStrength : rsrp=" + rsrp + ", rssnr=" + rssnr);
    }

    /** Log incoming sms success case */
    public void onIncomingSms(int subId) {
        if (!isNtnConnected()) {
            return;
        }
        mCountOfIncomingSms += 1;
        logd("onIncomingSms: subId=" + subId + ", count=" + mCountOfIncomingSms);
    }

    /** Log outgoing sms success case */
    public void onOutgoingSms(int subId) {
        if (!isNtnConnected()) {
            return;
        }
        mCountOfOutgoingSms += 1;
        logd("onOutgoingSms: subId=" + subId + ", count=" + mCountOfOutgoingSms);
    }

    /** Log incoming or outgoing mms success case */
    public void onMms(boolean isIncomingMms, long messageId) {
        if (!isNtnConnected()) {
            return;
        }
        if (isIncomingMms) {
            mIncomingMessageId = messageId;
            mCountOfIncomingMms += 1;
            logd("onMms: messageId=" + messageId + ", countOfIncomingMms=" + mCountOfIncomingMms);
        } else {
            if (mIncomingMessageId == messageId) {
                logd("onMms: NotifyResponse ignore it.");
                mIncomingMessageId = 0;
                return;
            }
            mCountOfOutgoingMms += 1;
            logd("onMms: countOfOutgoingMms=" + mCountOfOutgoingMms);
        }
    }

    private void reportMetrics(int subId) {
        int totalSatelliteModeTimeSec =
                mSessionStartTimeSec > 0 ? getElapsedRealtimeInSec() - mSessionStartTimeSec : 0;
        int numberOfSatelliteConnections = getNumberOfSatelliteConnections();

        List<Integer> connectionGapList =
                getSatelliteConnectionGapList(numberOfSatelliteConnections);
        int satelliteConnectionGapMinSec = 0;
        int satelliteConnectionGapMaxSec = 0;
        if (!connectionGapList.isEmpty()) {
            satelliteConnectionGapMinSec = Collections.min(connectionGapList);
            satelliteConnectionGapMaxSec = Collections.max(connectionGapList);
        }
        boolean isMultiSim = mSubscriptionManagerService.getActiveSubIdList(true).length > 1;
        updateFinalScreenOnTime();

        SatelliteStats.CarrierRoamingSatelliteSessionParams params =
                new SatelliteStats.CarrierRoamingSatelliteSessionParams.Builder()
                        .setCarrierId(mCarrierId)
                        .setSupportedConnectionMode(mSupportedConnectionMode)
                        .setPlmn(mPlmn)
                        .setSessionConnectionMode(mSessionConnectionMode)
                        .setIsNtnRoamingInHomeCountry(mIsNtnRoamingInHomeCountry)
                        .setTotalSatelliteModeTimeSec(totalSatelliteModeTimeSec)
                        .setNumberOfSatelliteConnections(numberOfSatelliteConnections)
                        .setAvgDurationOfSatelliteConnectionSec(
                                getAvgDurationOfSatelliteConnection())
                        .setSatelliteConnectionGapMinSec(satelliteConnectionGapMinSec)
                        .setSatelliteConnectionGapAvgSec(getAvg(connectionGapList))
                        .setSatelliteConnectionGapMaxSec(satelliteConnectionGapMaxSec)
                        .setRsrpAvg(getAvg(mRsrpList))
                        .setRsrpMedian(getMedian(mRsrpList))
                        .setRssnrAvg(getAvg(mRssnrList))
                        .setRssnrMedian(getMedian(mRssnrList))
                        .setCountOfIncomingSms(mCountOfIncomingSms)
                        .setCountOfOutgoingSms(mCountOfOutgoingSms)
                        .setCountOfIncomingMms(mCountOfIncomingMms)
                        .setCountOfOutgoingMms(mCountOfOutgoingMms)
                        .setSupportedSatelliteServices(mSupportedSatelliteServices)
                        .setServiceDataPolicy(mServiceDataPolicy)
                        .setSatelliteDataConsumedBytes(mSatelliteDataConsumedBytes)
                        .setIsMultiSim(isMultiSim)
                        .setIsNbIotNtn(SatelliteServiceUtils.isNbIotNtn(subId))
                        .setCountOfDataConnections(mCountOfDataConnections)
                        .setLastFailCauses(mLastFailCauses)
                        .setCountOfDataDisconnections(mCountOfDataDisconnections)
                        .setCountOfDataStalls(mCountOfDataStalls)
                        .setAverageUplinkBandwidthKbps(mAverageUplinkBandwidthKbps)
                        .setAverageDownlinkBandwidthKbps(mAverageDownlinkBandwidthKbps)
                        .setMinimumUplinkBandwidthKbps(mMinUplinkBandwidthKbps)
                        .setMaximumUplinkBandwidthKbps(mMaxUplinkBandwidthKbps)
                        .setMinimumDownlinkBandwidthKbps(mMinDownlinkBandwidthKbps)
                        .setMaximumDownlinkBandwidthKbps(mMaxDownlinkBandwidthKbps)
                        .setSatelliteSupportedApps(mSatelliteAppsPackageNameArray)
                        .setSatelliteSupportedUids(mSatelliteAppsUidArray)
                        .setPerAppSatelliteDataConsumedBytes(mPerAppSatelliteDataConsumedBytesArray)
                        .setIsWifiEnabled(mIsWifiEnabled)
                        .setIsWifiConnected(mIsWifiConnected)
                        .setIsWfcEnabled(mIsWfcEnabled)
                        .setIsWfcRegistered(mIsWfcRegistered)
                        .setEligibilitySource(
                                SatelliteServiceUtils.getSatelliteEligibilitySource(subId))
                        .setScreenOnTimeSec(mAccumulatedScreenOnTimeSec)
                        .build();
        SatelliteStats.getInstance().onCarrierRoamingSatelliteSessionMetrics(params);
        // Add session duration time to session controller atom when session ends.
        CarrierRoamingSatelliteControllerStats.getOrCreateInstance().addSessionDurationSec(subId,
                totalSatelliteModeTimeSec);
        logd("Supported satellite services: " + Arrays.toString(mSupportedSatelliteServices));
        logd("last fail causes: " + Arrays.toString(mLastFailCauses));
        logd("reportMetrics: " + params);
        initializeParams();
    }

    private void initializeParams() {
        mCarrierId = TelephonyManager.UNKNOWN_CARRIER_ID;
        mSupportedConnectionMode = SatelliteConstants.GLOBAL_NTN_CONNECT_TYPE_UNKNOWN;
        mSessionConnectionMode = SatelliteConstants.SESSION_NTN_CONNECT_TYPE_UNKNOWN;
        mPlmn = SatelliteConstants.DEFAULT_PLMN;
        mIsNtnRoamingInHomeCountry = false;
        mCountOfIncomingSms = 0;
        mCountOfOutgoingSms = 0;
        mCountOfIncomingMms = 0;
        mCountOfOutgoingMms = 0;
        mIncomingMessageId = 0;

        mSessionStartTimeSec = 0;
        mSatelliteConnectionTimes = null;
        mSatelliteConnectionTimesList = new ArrayList<>();
        mRsrpList = new ArrayList<>();
        mRssnrList = new ArrayList<>();
        mIsWifiEnabled = false;
        mIsWifiConnected = false;
        mIsWfcEnabled = false;
        mIsWfcRegistered = false;
        mAccumulatedScreenOnTimeSec = 0;
        logd("initializeParams");
    }

    private CellSignalStrengthLte getCellSignalStrengthLte(Phone phone) {
        SignalStrength signalStrength = phone.getSignalStrength();
        List<CellSignalStrength> cellSignalStrengths = signalStrength.getCellSignalStrengths();
        for (CellSignalStrength cellSignalStrength : cellSignalStrengths) {
            if (cellSignalStrength instanceof CellSignalStrengthLte) {
                return (CellSignalStrengthLte) cellSignalStrength;
            }
        }

        return new CellSignalStrengthLte();
    }

    private int getNumberOfSatelliteConnections() {
        return mSatelliteConnectionTimesList.size();
    }

    private int getAvgDurationOfSatelliteConnection() {
        if (mSatelliteConnectionTimesList.isEmpty()) {
            return 0;
        }

        OptionalDouble averageDuration =
                mSatelliteConnectionTimesList.stream()
                        .filter(SatelliteConnectionTimes::isValid)
                        .mapToLong(SatelliteConnectionTimes::getDuration)
                        .average();

        return (int) (averageDuration.isPresent() ? averageDuration.getAsDouble() / 1000 : 0);
    }

    private List<Integer> getSatelliteConnectionGapList(int numberOfSatelliteConnections) {
        if (mSatelliteConnectionTimesList.size() < 2) {
            return new ArrayList<>();
        }

        List<Integer> connectionGapList = new ArrayList<>();
        for (int i = 1; i < mSatelliteConnectionTimesList.size(); i++) {
            SatelliteConnectionTimes prevConnection = mSatelliteConnectionTimesList.get(i - 1);
            SatelliteConnectionTimes currentConnection = mSatelliteConnectionTimesList.get(i);

            if (prevConnection.getEndTime() > 0
                    && currentConnection.getStartTime() > prevConnection.getEndTime()) {
                int gap =
                        (int)
                                ((currentConnection.getStartTime() - prevConnection.getEndTime())
                                        / 1000);
                connectionGapList.add(gap);
            }
        }
        return connectionGapList;
    }

    private int getAvg(@NonNull List<Integer> list) {
        if (list.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (int num : list) {
            total += num;
        }

        return total / list.size();
    }

    private int getMedian(@NonNull List<Integer> list) {
        if (list.isEmpty()) {
            return 0;
        }
        int size = list.size();
        if (size == 1) {
            return list.get(0);
        }

        Collections.sort(list);
        return size % 2 == 0
                ? (list.get(size / 2 - 1) + list.get(size / 2)) / 2
                : list.get(size / 2);
    }

    private int getElapsedRealtimeInSec() {
        return (int) (getElapsedRealtime() / 1000);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    protected long getElapsedRealtime() {
        return SystemClock.elapsedRealtime();
    }

    private boolean isNtnConnected() {
        return mSessionStartTimeSec != 0;
    }

    private void updateNtnRoamingInHomeCountry(Phone phone) {
        int subId = phone.getSubId();
        ServiceState serviceState = phone.getServiceState();
        if (serviceState == null) {
            logd("ServiceState is null");
            return;
        }

        String satelliteRegisteredPlmn = "";
        for (NetworkRegistrationInfo nri : serviceState.getNetworkRegistrationInfoList()) {
            if (nri.isNonTerrestrialNetwork()) {
                satelliteRegisteredPlmn = nri.getRegisteredPlmn();
            }
        }

        SubscriptionInfoInternal subscriptionInfoInternal =
                mSubscriptionManagerService.getSubscriptionInfoInternal(subId);
        if (subscriptionInfoInternal == null) {
            logd("SubscriptionInfoInternal is null");
            return;
        }
        String simCountry = MccTable.countryCodeForMcc(subscriptionInfoInternal.getMcc());
        mIsNtnRoamingInHomeCountry = true;
        if (satelliteRegisteredPlmn != null && satelliteRegisteredPlmn.length() >= 3) {
            String satelliteRegisteredCountry =
                    MccTable.countryCodeForMcc(satelliteRegisteredPlmn.substring(0, 3));
            if (simCountry.equalsIgnoreCase(satelliteRegisteredCountry)) {
                mIsNtnRoamingInHomeCountry = true;
            } else {
                // If device is connected to roaming non-terrestrial network, then marking as
                // roaming in external country
                mIsNtnRoamingInHomeCountry = false;
            }
        }
        logd(
                "updateNtnRoamingInHomeCountry: mIsNtnRoamingInHomeCountry="
                        + mIsNtnRoamingInHomeCountry);
    }

    private static class SatelliteConnectionTimes {
        private final long mStartTime;
        private long mEndTime;

        SatelliteConnectionTimes(long startTime) {
            this.mStartTime = startTime;
            this.mEndTime = 0;
        }

        public void setEndTime(long endTime) {
            this.mEndTime = endTime;
        }

        public long getStartTime() {
            return mStartTime;
        }

        public long getEndTime() {
            return mEndTime;
        }

        public long getDuration() {
            if (isValid()) {
                return mEndTime - mStartTime;
            }
            return 0;
        }

        public boolean isValid() {
            return mEndTime > mStartTime && mStartTime > 0;
        }
    }

    /**
     * Updates the wifi-connected state. If Wi-Fi has been connected at least once
     * since the current session start, the state remains true.
     */
    public void onWifiConnectivityStateChanged(boolean isWifiConnected) {
        if (isWifiConnected) {
            mIsWifiConnected = true;
        }
        logd("onWifiConnectivityStateChanged: isWifiConnected=" + isWifiConnected
                + ", mIsWifiConnected=" + mIsWifiConnected);
    }

    /**
     * Updates the screen-on time for this specific instance.
     */
    public void onScreenStateChanged(boolean isScreenOn) {
        if (!mFeatureFlags.satelliteMetricsEnhancement()) {
            logd("onScreenStateChanged: satelliteMetricsEnhancement is not enabled, ignore.");
            return;
        }

        if (isScreenOn && mScreenOnStartTimeMillis == 0) {
            mScreenOnStartTimeMillis = getElapsedRealtime();
        } else if (!isScreenOn && mScreenOnStartTimeMillis > 0) {
            long durationMillis = getElapsedRealtime() - mScreenOnStartTimeMillis;
            mAccumulatedScreenOnTimeSec += (int) (durationMillis / 1000);
            mScreenOnStartTimeMillis = 0;
        }
    }

    /**
     * Captures the final duration of screen-on time if the screen is still active
     * when the session ends.
     * <p>
     * If {@link #mScreenOnStartTimeMillis} is greater than 0, it indicates the screen is currently
     * ON. This method calculates the duration from the last screen-on event to the current time and
     * adds it to the total accumulated time to ensure the reported metrics are complete.
     */
    private void updateFinalScreenOnTime() {
        if (mScreenOnStartTimeMillis > 0) {
            long durationMillis = getElapsedRealtime() - mScreenOnStartTimeMillis;
            mAccumulatedScreenOnTimeSec += (int) (durationMillis / 1000);
            mScreenOnStartTimeMillis = 0;
        }
    }

    private void logd(@NonNull String log) {
        Log.d(TAG, log);
    }

    private void loge(@NonNull String log) {
        Log.e(TAG, log);
    }
}
