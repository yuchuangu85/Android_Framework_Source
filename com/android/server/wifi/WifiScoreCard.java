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

package com.android.server.wifi;

import static android.net.wifi.WifiInfo.DEFAULT_MAC_ADDRESS;
import static android.net.wifi.WifiInfo.INVALID_RSSI;
import static android.net.wifi.WifiInfo.LINK_SPEED_UNKNOWN;

import static com.android.server.wifi.WifiHealthMonitor.HEALTH_MONITOR_COUNT_TX_SPEED_MIN_MBPS;
import static com.android.server.wifi.WifiHealthMonitor.HEALTH_MONITOR_MIN_TX_PACKET_PER_SEC;
import static com.android.server.wifi.WifiHealthMonitor.REASON_ASSOC_REJECTION;
import static com.android.server.wifi.WifiHealthMonitor.REASON_ASSOC_TIMEOUT;
import static com.android.server.wifi.WifiHealthMonitor.REASON_AUTH_FAILURE;
import static com.android.server.wifi.WifiHealthMonitor.REASON_CONNECTION_FAILURE;
import static com.android.server.wifi.WifiHealthMonitor.REASON_DISCONNECTION_NONLOCAL;
import static com.android.server.wifi.WifiHealthMonitor.REASON_NO_FAILURE;
import static com.android.server.wifi.WifiHealthMonitor.REASON_SHORT_CONNECTION_NONLOCAL;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.MacAddress;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiManager;
import android.util.ArrayMap;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;
import android.util.SparseLongArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.wifi.BssidBlocklistMonitor.FailureReason;
import com.android.server.wifi.WifiHealthMonitor.FailureStats;
import com.android.server.wifi.proto.WifiScoreCardProto;
import com.android.server.wifi.proto.WifiScoreCardProto.AccessPoint;
import com.android.server.wifi.proto.WifiScoreCardProto.ConnectionStats;
import com.android.server.wifi.proto.WifiScoreCardProto.Event;
import com.android.server.wifi.proto.WifiScoreCardProto.HistogramBucket;
import com.android.server.wifi.proto.WifiScoreCardProto.Network;
import com.android.server.wifi.proto.WifiScoreCardProto.NetworkList;
import com.android.server.wifi.proto.WifiScoreCardProto.NetworkStats;
import com.android.server.wifi.proto.WifiScoreCardProto.SecurityType;
import com.android.server.wifi.proto.WifiScoreCardProto.Signal;
import com.android.server.wifi.proto.WifiScoreCardProto.UnivariateStatistic;
import com.android.server.wifi.util.IntHistogram;
import com.android.server.wifi.util.LruList;
import com.android.server.wifi.util.NativeUtil;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Retains statistical information about the performance of various
 * access points and networks, as experienced by this device.
 *
 * The purpose is to better inform future network selection and switching
 * by this device and help health monitor detect network issues.
 */
@NotThreadSafe
public class WifiScoreCard {

    public static final String DUMP_ARG = "WifiScoreCard";

    private static final String TAG = "WifiScoreCard";
    private boolean mVerboseLoggingEnabled = false;

    @VisibleForTesting
    boolean mPersistentHistograms = true;

    private static final int TARGET_IN_MEMORY_ENTRIES = 50;
    private static final int UNKNOWN_REASON = -1;

    public static final String PER_BSSID_DATA_NAME = "scorecard.proto";
    public static final String PER_NETWORK_DATA_NAME = "perNetworkData";

    static final int INSUFFICIENT_RECENT_STATS = 0;
    static final int SUFFICIENT_RECENT_STATS_ONLY = 1;
    static final int SUFFICIENT_RECENT_PREV_STATS = 2;

    private static final int MAX_FREQUENCIES_PER_SSID = 10;

    private final Clock mClock;
    private final String mL2KeySeed;
    private MemoryStore mMemoryStore;
    private final DeviceConfigFacade mDeviceConfigFacade;

    @VisibleForTesting
    static final int[] RSSI_BUCKETS = intsInRange(-100, -20);

    private static int[] intsInRange(int min, int max) {
        int[] a = new int[max - min + 1];
        for (int i = 0; i < a.length; i++) {
            a[i] = min + i;
        }
        return a;
    }

    /** Our view of the memory store */
    public interface MemoryStore {
        /** Requests a read, with asynchronous reply */
        void read(String key, String name, BlobListener blobListener);
        /** Requests a write, does not wait for completion */
        void write(String key, String name, byte[] value);
        /** Sets the cluster identifier */
        void setCluster(String key, String cluster);
        /** Requests removal of all entries matching the cluster */
        void removeCluster(String cluster);
    }
    /** Asynchronous response to a read request */
    public interface BlobListener {
        /** Provides the previously stored value, or null if none */
        void onBlobRetrieved(@Nullable byte[] value);
    }

    /**
     * Installs a memory store.
     *
     * Normally this happens just once, shortly after we start. But wifi can
     * come up before the disk is ready, and we might not yet have a valid wall
     * clock when we start up, so we need to be prepared to begin recording data
     * even if the MemoryStore is not yet available.
     *
     * When the store is installed for the first time, we want to merge any
     * recently recorded data together with data already in the store. But if
     * the store restarts and has to be reinstalled, we don't want to do
     * this merge, because that would risk double-counting the old data.
     *
     */
    public void installMemoryStore(@NonNull MemoryStore memoryStore) {
        Preconditions.checkNotNull(memoryStore);
        if (mMemoryStore == null) {
            mMemoryStore = memoryStore;
            Log.i(TAG, "Installing MemoryStore");
            requestReadForAllChanged();
        } else {
            mMemoryStore = memoryStore;
            Log.e(TAG, "Reinstalling MemoryStore");
            // Our caller will call doWrites() eventually, so nothing more to do here.
        }
    }

    /**
     * Enable/Disable verbose logging.
     *
     * @param verbose true to enable and false to disable.
     */
    public void enableVerboseLogging(boolean verbose) {
        mVerboseLoggingEnabled = verbose;
    }

    /**
     * Timestamp of the start of the most recent connection attempt.
     *
     * Based on mClock.getElapsedSinceBootMillis().
     *
     * This is for calculating the time to connect and the duration of the connection.
     * Any negative value means we are not currently connected.
     */
    private long mTsConnectionAttemptStart = TS_NONE;
    @VisibleForTesting
    static final long TS_NONE = -1;

    /**
     * Timestamp captured when we find out about a firmware roam
     */
    private long mTsRoam = TS_NONE;

    /**
     * Becomes true the first time we see a poll with a valid RSSI in a connection
     */
    private boolean mPolled = false;

    /**
     * Records validation success for the current connection.
     *
     * We want to gather statistics only on the first success.
     */
    private boolean mValidatedThisConnectionAtLeastOnce = false;

    /**
     * A note to ourself that we are attempting a network switch
     */
    private boolean mAttemptingSwitch = false;

    /**
     *  SSID of currently connected or connecting network. Used during disconnection
     */
    private String mSsidCurr = "";
    /**
     *  SSID of previously connected network. Used during disconnection when connection attempt
     *  of current network is issued before the disconnection of previous network.
     */
    private String mSsidPrev = "";
    /**
     * A flag that notes that current disconnection is not generated by wpa_supplicant
     * which may indicate abnormal disconnection.
     */
    private boolean mNonlocalDisconnection = false;
    private int mDisconnectionReason;

    private long mFirmwareAlertTimeMs = TS_NONE;

    /**
     * @param clock is the time source
     * @param l2KeySeed is for making our L2Keys usable only on this device
     */
    public WifiScoreCard(Clock clock, String l2KeySeed, DeviceConfigFacade deviceConfigFacade) {
        mClock = clock;
        mL2KeySeed = l2KeySeed;
        mDummyPerBssid = new PerBssid("", MacAddress.fromString(DEFAULT_MAC_ADDRESS));
        mDummyPerNetwork = new PerNetwork("");
        mDeviceConfigFacade = deviceConfigFacade;
    }

    /**
     * Gets the L2Key and GroupHint associated with the connection.
     */
    public @NonNull Pair<String, String> getL2KeyAndGroupHint(ExtendedWifiInfo wifiInfo) {
        PerBssid perBssid = lookupBssid(wifiInfo.getSSID(), wifiInfo.getBSSID());
        if (perBssid == mDummyPerBssid) {
            return new Pair<>(null, null);
        }
        return new Pair<>(perBssid.getL2Key(), groupHintFromSsid(perBssid.ssid));
    }

    /**
     * Computes the GroupHint associated with the given ssid.
     */
    public @NonNull String groupHintFromSsid(String ssid) {
        final long groupIdHash = computeHashLong(ssid, mDummyPerBssid.bssid, mL2KeySeed);
        return groupHintFromLong(groupIdHash);
    }

    /**
     * Handle network disconnection or shutdown event
     */
    public void resetConnectionState() {
        String ssidDisconnected = mAttemptingSwitch ? mSsidPrev : mSsidCurr;
        updatePerNetwork(Event.DISCONNECTION, ssidDisconnected, INVALID_RSSI, LINK_SPEED_UNKNOWN,
                UNKNOWN_REASON);
        if (mVerboseLoggingEnabled && mTsConnectionAttemptStart > TS_NONE && !mAttemptingSwitch) {
            Log.v(TAG, "handleNetworkDisconnect", new Exception());
        }
        resetConnectionStateInternal(true);
    }

    /**
     * @param calledFromResetConnectionState says the call is from outside the class,
     *        indicating that we need to respect the value of mAttemptingSwitch.
     */
    private void resetConnectionStateInternal(boolean calledFromResetConnectionState) {
        if (!calledFromResetConnectionState) {
            mAttemptingSwitch = false;
        }
        if (!mAttemptingSwitch) {
            mTsConnectionAttemptStart = TS_NONE;
        }
        mTsRoam = TS_NONE;
        mPolled = false;
        mValidatedThisConnectionAtLeastOnce = false;
        mNonlocalDisconnection = false;
        mFirmwareAlertTimeMs = TS_NONE;
    }

    /**
     * Updates perBssid using relevant parts of WifiInfo
     *
     * @param wifiInfo object holding relevant values.
     */
    private void updatePerBssid(WifiScoreCardProto.Event event, ExtendedWifiInfo wifiInfo) {
        PerBssid perBssid = lookupBssid(wifiInfo.getSSID(), wifiInfo.getBSSID());
        perBssid.updateEventStats(event,
                wifiInfo.getFrequency(),
                wifiInfo.getRssi(),
                wifiInfo.getLinkSpeed());
        perBssid.setNetworkConfigId(wifiInfo.getNetworkId());
        logd("BSSID update " + event + " ID: " + perBssid.id + " " + wifiInfo);
    }

    /**
     * Updates perNetwork with SSID, current RSSI and failureReason. failureReason is  meaningful
     * only during connection failure.
     */
    private void updatePerNetwork(WifiScoreCardProto.Event event, String ssid, int rssi,
            int txSpeed, int failureReason) {
        PerNetwork perNetwork = lookupNetwork(ssid);
        logd("network update " + event + ((ssid == null) ? " " : " "
                    + ssid) + " ID: " + perNetwork.id + " RSSI " + rssi + " txSpeed " + txSpeed);
        perNetwork.updateEventStats(event, rssi, txSpeed, failureReason);
    }

    /**
     * Updates the score card after a signal poll
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteSignalPoll(@NonNull ExtendedWifiInfo wifiInfo) {
        if (!mPolled && wifiInfo.getRssi() != INVALID_RSSI) {
            updatePerBssid(Event.FIRST_POLL_AFTER_CONNECTION, wifiInfo);
            mPolled = true;
        }
        updatePerBssid(Event.SIGNAL_POLL, wifiInfo);
        int validTxSpeed = geTxLinkSpeedWithSufficientTxRate(wifiInfo);
        updatePerNetwork(Event.SIGNAL_POLL, wifiInfo.getSSID(), wifiInfo.getRssi(),
                validTxSpeed, UNKNOWN_REASON);
        if (mTsRoam > TS_NONE && wifiInfo.getRssi() != INVALID_RSSI) {
            long duration = mClock.getElapsedSinceBootMillis() - mTsRoam;
            if (duration >= SUCCESS_MILLIS_SINCE_ROAM) {
                updatePerBssid(Event.ROAM_SUCCESS, wifiInfo);
                mTsRoam = TS_NONE;
                doWritesBssid();
            }
        }
    }

    private int geTxLinkSpeedWithSufficientTxRate(@NonNull ExtendedWifiInfo wifiInfo) {
        int txRate = (int) Math.ceil(wifiInfo.getSuccessfulTxPacketsPerSecond()
                + wifiInfo.getLostTxPacketsPerSecond()
                + wifiInfo.getRetriedTxPacketsPerSecond());
        int txSpeed = wifiInfo.getTxLinkSpeedMbps();
        logd("txRate: " + txRate + " txSpeed: " + txSpeed);
        return (txRate >= HEALTH_MONITOR_MIN_TX_PACKET_PER_SEC) ? txSpeed : LINK_SPEED_UNKNOWN;
    }

    /** Wait a few seconds before considering the roam successful */
    private static final long SUCCESS_MILLIS_SINCE_ROAM = 4_000;

    /**
     * Updates the score card after IP configuration
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteIpConfiguration(@NonNull ExtendedWifiInfo wifiInfo) {
        updatePerBssid(Event.IP_CONFIGURATION_SUCCESS, wifiInfo);
        mAttemptingSwitch = false;
        doWrites();
    }

    /**
     * Updates the score card after network validation success.
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteValidationSuccess(@NonNull ExtendedWifiInfo wifiInfo) {
        if (mValidatedThisConnectionAtLeastOnce) return; // Only once per connection
        updatePerBssid(Event.VALIDATION_SUCCESS, wifiInfo);
        mValidatedThisConnectionAtLeastOnce = true;
        doWrites();
    }

    /**
     * Updates the score card after network validation failure
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteValidationFailure(@NonNull ExtendedWifiInfo wifiInfo) {
        // VALIDATION_FAILURE is not currently recorded.
    }

    /**
     * Records the start of a connection attempt
     *
     * @param wifiInfo may have state about an existing connection
     * @param scanRssi is the highest RSSI of recent scan found from scanDetailCache
     * @param ssid is the network SSID of connection attempt
     */
    public void noteConnectionAttempt(@NonNull ExtendedWifiInfo wifiInfo,
            int scanRssi, String ssid) {
        // We may or may not be currently connected. If not, simply record the start.
        // But if we are connected, wrap up the old one first.
        if (mTsConnectionAttemptStart > TS_NONE) {
            if (mPolled) {
                updatePerBssid(Event.LAST_POLL_BEFORE_SWITCH, wifiInfo);
            }
            mAttemptingSwitch = true;
        }
        mTsConnectionAttemptStart = mClock.getElapsedSinceBootMillis();
        mPolled = false;
        mSsidPrev = mSsidCurr;
        mSsidCurr = ssid;
        mFirmwareAlertTimeMs = TS_NONE;

        updatePerNetwork(Event.CONNECTION_ATTEMPT, ssid, scanRssi, LINK_SPEED_UNKNOWN,
                UNKNOWN_REASON);
        logd("CONNECTION_ATTEMPT" + (mAttemptingSwitch ? " X " : " ") + wifiInfo);
    }

    /**
     * Records a newly assigned NetworkAgent netId.
     */
    public void noteNetworkAgentCreated(@NonNull ExtendedWifiInfo wifiInfo, int networkAgentId) {
        PerBssid perBssid = lookupBssid(wifiInfo.getSSID(), wifiInfo.getBSSID());
        logd("NETWORK_AGENT_ID: " + networkAgentId + " ID: " + perBssid.id);
        perBssid.mNetworkAgentId = networkAgentId;
    }

    /**
     * Record disconnection not initiated by wpa_supplicant in connected mode
     * @param reason is detailed disconnection reason code
     */
    public void noteNonlocalDisconnect(int reason) {
        mNonlocalDisconnection = true;
        mDisconnectionReason = reason;
        logd("nonlocal disconnection with reason: " + reason);
    }

    /**
     * Record firmware alert timestamp and error code
     */
    public void noteFirmwareAlert(int errorCode) {
        mFirmwareAlertTimeMs = mClock.getElapsedSinceBootMillis();
        logd("firmware alert with error code: " + errorCode);
    }

    /**
     * Updates the score card after a failed connection attempt
     *
     * @param wifiInfo object holding relevant values.
     * @param scanRssi is the highest RSSI of recent scan found from scanDetailCache
     * @param ssid is the network SSID.
     * @param failureReason is connection failure reason
     */
    public void noteConnectionFailure(@NonNull ExtendedWifiInfo wifiInfo,
            int scanRssi, String ssid, @FailureReason int failureReason) {
        // TODO: add the breakdown of level2FailureReason
        updatePerBssid(Event.CONNECTION_FAILURE, wifiInfo);

        updatePerNetwork(Event.CONNECTION_FAILURE, ssid, scanRssi, LINK_SPEED_UNKNOWN,
                failureReason);
        resetConnectionStateInternal(false);
    }

    /**
     * Updates the score card after network reachability failure
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteIpReachabilityLost(@NonNull ExtendedWifiInfo wifiInfo) {
        if (mTsRoam > TS_NONE) {
            mTsConnectionAttemptStart = mTsRoam; // just to update elapsed
            updatePerBssid(Event.ROAM_FAILURE, wifiInfo);
        } else {
            updatePerBssid(Event.IP_REACHABILITY_LOST, wifiInfo);
        }
        // No need to call resetConnectionStateInternal() because
        // resetConnectionState() will be called after WifiNative.disconnect() in ClientModeImpl
        doWrites();
    }

    /**
     * Updates the score card before a roam
     *
     * We may have already done a firmware roam, but wifiInfo has not yet
     * been updated, so we still have the old state.
     *
     * @param wifiInfo object holding relevant values
     */
    private void noteRoam(@NonNull ExtendedWifiInfo wifiInfo) {
        updatePerBssid(Event.LAST_POLL_BEFORE_ROAM, wifiInfo);
        mTsRoam = mClock.getElapsedSinceBootMillis();
    }

    /**
     * Called when the supplicant state is about to change, before wifiInfo is updated
     *
     * @param wifiInfo object holding old values
     * @param state the new supplicant state
     */
    public void noteSupplicantStateChanging(@NonNull ExtendedWifiInfo wifiInfo,
            SupplicantState state) {
        if (state == SupplicantState.COMPLETED && wifiInfo.getSupplicantState() == state) {
            // Our signal that a firmware roam has occurred
            noteRoam(wifiInfo);
        }
        logd("Changing state to " + state + " " + wifiInfo);
    }

    /**
     * Called after the supplicant state changed
     *
     * @param wifiInfo object holding old values
     */
    public void noteSupplicantStateChanged(ExtendedWifiInfo wifiInfo) {
        logd("STATE " + wifiInfo);
    }

    /**
     * Updates the score card after wifi is disabled
     *
     * @param wifiInfo object holding relevant values
     */
    public void noteWifiDisabled(@NonNull ExtendedWifiInfo wifiInfo) {
        updatePerBssid(Event.WIFI_DISABLED, wifiInfo);
        resetConnectionStateInternal(false);
        doWrites();
    }

    /**
     * Records the last successful L2 connection timestamp for a BSSID.
     * @return the previous BSSID connection time.
     */
    public long setBssidConnectionTimestampMs(String ssid, String bssid, long timeMs) {
        PerBssid perBssid = lookupBssid(ssid, bssid);
        long prev = perBssid.lastConnectionTimestampMs;
        perBssid.lastConnectionTimestampMs = timeMs;
        return prev;
    }

    /**
     * Returns the last successful L2 connection time for this BSSID.
     */
    public long getBssidConnectionTimestampMs(String ssid, String bssid) {
        return lookupBssid(ssid, bssid).lastConnectionTimestampMs;
    }

    /**
     * Increment the blocklist streak count for a failure reason on an AP.
     * @return the updated count
     */
    public int incrementBssidBlocklistStreak(String ssid, String bssid,
            @BssidBlocklistMonitor.FailureReason int reason) {
        PerBssid perBssid = lookupBssid(ssid, bssid);
        return ++perBssid.blocklistStreakCount[reason];
    }

    /**
     * Get the blocklist streak count for a failure reason on an AP.
     * @return the blocklist streak count
     */
    public int getBssidBlocklistStreak(String ssid, String bssid,
            @BssidBlocklistMonitor.FailureReason int reason) {
        return lookupBssid(ssid, bssid).blocklistStreakCount[reason];
    }

    /**
     * Clear the blocklist streak count for a failure reason on an AP.
     */
    public void resetBssidBlocklistStreak(String ssid, String bssid,
            @BssidBlocklistMonitor.FailureReason int reason) {
        lookupBssid(ssid, bssid).blocklistStreakCount[reason] = 0;
    }

    /**
     * Clear the blocklist streak count for all APs that belong to this SSID.
     */
    public void resetBssidBlocklistStreakForSsid(@NonNull String ssid) {
        Iterator<Map.Entry<MacAddress, PerBssid>> it = mApForBssid.entrySet().iterator();
        while (it.hasNext()) {
            PerBssid perBssid = it.next().getValue();
            if (!ssid.equals(perBssid.ssid)) {
                continue;
            }
            for (int i = 0; i < perBssid.blocklistStreakCount.length; i++) {
                perBssid.blocklistStreakCount[i] = 0;
            }
        }
    }

    /**
     * Detect abnormal disconnection at high RSSI with a high rate
     */
    public int detectAbnormalDisconnection() {
        String ssid = mAttemptingSwitch ? mSsidPrev : mSsidCurr;
        PerNetwork perNetwork = lookupNetwork(ssid);
        NetworkConnectionStats recentStats = perNetwork.getRecentStats();
        if (recentStats.getRecentCountCode() == CNT_SHORT_CONNECTION_NONLOCAL) {
            return detectAbnormalFailureReason(recentStats, CNT_SHORT_CONNECTION_NONLOCAL,
                    REASON_SHORT_CONNECTION_NONLOCAL,
                    mDeviceConfigFacade.getShortConnectionNonlocalHighThrPercent(),
                    mDeviceConfigFacade.getShortConnectionNonlocalCountMin(),
                    CNT_DISCONNECTION);
        } else if (recentStats.getRecentCountCode() == CNT_DISCONNECTION_NONLOCAL) {
            return detectAbnormalFailureReason(recentStats, CNT_DISCONNECTION_NONLOCAL,
                    REASON_DISCONNECTION_NONLOCAL,
                    mDeviceConfigFacade.getDisconnectionNonlocalHighThrPercent(),
                    mDeviceConfigFacade.getDisconnectionNonlocalCountMin(),
                    CNT_DISCONNECTION);
        } else {
            return REASON_NO_FAILURE;
        }
    }

    /**
     * Detect abnormal connection failure at high RSSI with a high rate
     */
    public int detectAbnormalConnectionFailure(String ssid) {
        PerNetwork perNetwork = lookupNetwork(ssid);
        NetworkConnectionStats recentStats = perNetwork.getRecentStats();
        int recentCountCode = recentStats.getRecentCountCode();
        if (recentCountCode == CNT_AUTHENTICATION_FAILURE) {
            return detectAbnormalFailureReason(recentStats, CNT_AUTHENTICATION_FAILURE,
                    REASON_AUTH_FAILURE,
                    mDeviceConfigFacade.getAuthFailureHighThrPercent(),
                    mDeviceConfigFacade.getAuthFailureCountMin(),
                    CNT_CONNECTION_ATTEMPT);
        } else if (recentCountCode == CNT_ASSOCIATION_REJECTION) {
            return detectAbnormalFailureReason(recentStats, CNT_ASSOCIATION_REJECTION,
                    REASON_ASSOC_REJECTION,
                    mDeviceConfigFacade.getAssocRejectionHighThrPercent(),
                    mDeviceConfigFacade.getAssocRejectionCountMin(),
                    CNT_CONNECTION_ATTEMPT);
        } else if (recentCountCode == CNT_ASSOCIATION_TIMEOUT) {
            return detectAbnormalFailureReason(recentStats, CNT_ASSOCIATION_TIMEOUT,
                    REASON_ASSOC_TIMEOUT,
                    mDeviceConfigFacade.getAssocTimeoutHighThrPercent(),
                    mDeviceConfigFacade.getAssocTimeoutCountMin(),
                    CNT_CONNECTION_ATTEMPT);
        } else if (recentCountCode == CNT_CONNECTION_FAILURE) {
            return detectAbnormalFailureReason(recentStats, CNT_CONNECTION_FAILURE,
                    REASON_CONNECTION_FAILURE,
                    mDeviceConfigFacade.getConnectionFailureHighThrPercent(),
                    mDeviceConfigFacade.getConnectionFailureCountMin(),
                    CNT_CONNECTION_ATTEMPT);
        } else {
            return REASON_NO_FAILURE;
        }
    }

    private int detectAbnormalFailureReason(NetworkConnectionStats stats, int countCode,
            int reasonCode, int highThresholdPercent, int minCount, int refCountCode) {
        // To detect abnormal failure which may trigger bugReport,
        // increase the detection threshold by thresholdRatio
        int thresholdRatio =
                mDeviceConfigFacade.getBugReportThresholdExtraRatio();
        if (isHighPercentageAndEnoughCount(stats, countCode, reasonCode,
                highThresholdPercent * thresholdRatio,
                minCount * thresholdRatio,
                refCountCode)) {
            return reasonCode;
        } else {
            return REASON_NO_FAILURE;
        }
    }

    private boolean isHighPercentageAndEnoughCount(NetworkConnectionStats stats, int countCode,
            int reasonCode, int highThresholdPercent, int minCount, int refCountCode) {
        highThresholdPercent = Math.min(highThresholdPercent, 100);
        // Use Laplace's rule of succession, useful especially for a small
        // connection attempt count
        // R = (f+1)/(n+2) with a pseudo count of 2 (one for f and one for s)
        return ((stats.getCount(countCode) >= minCount)
                && ((stats.getCount(countCode) + 1) * 100)
                >= (highThresholdPercent * (stats.getCount(refCountCode) + 2)));
    }

    final class PerBssid extends MemoryStoreAccessBase {
        public int id;
        public final String ssid;
        public final MacAddress bssid;
        public final int[] blocklistStreakCount =
                new int[BssidBlocklistMonitor.NUMBER_REASON_CODES];
        // The wall clock time in milliseconds for the last successful l2 connection.
        public long lastConnectionTimestampMs;
        public boolean changed;
        public boolean referenced;

        private SecurityType mSecurityType = null;
        private int mNetworkAgentId = Integer.MIN_VALUE;
        private int mNetworkConfigId = Integer.MIN_VALUE;
        private final Map<Pair<Event, Integer>, PerSignal>
                mSignalForEventAndFrequency = new ArrayMap<>();
        PerBssid(String ssid, MacAddress bssid) {
            super(computeHashLong(ssid, bssid, mL2KeySeed));
            this.ssid = ssid;
            this.bssid = bssid;
            this.id = idFromLong();
            this.changed = false;
            this.referenced = false;
        }
        void updateEventStats(Event event, int frequency, int rssi, int linkspeed) {
            PerSignal perSignal = lookupSignal(event, frequency);
            if (rssi != INVALID_RSSI) {
                perSignal.rssi.update(rssi);
                changed = true;
            }
            if (linkspeed > 0) {
                perSignal.linkspeed.update(linkspeed);
                changed = true;
            }
            if (perSignal.elapsedMs != null && mTsConnectionAttemptStart > TS_NONE) {
                long millis = mClock.getElapsedSinceBootMillis() - mTsConnectionAttemptStart;
                if (millis >= 0) {
                    perSignal.elapsedMs.update(millis);
                    changed = true;
                }
            }
        }
        PerSignal lookupSignal(Event event, int frequency) {
            finishPendingRead();
            Pair<Event, Integer> key = new Pair<>(event, frequency);
            PerSignal ans = mSignalForEventAndFrequency.get(key);
            if (ans == null) {
                ans = new PerSignal(event, frequency);
                mSignalForEventAndFrequency.put(key, ans);
            }
            return ans;
        }
        SecurityType getSecurityType() {
            finishPendingRead();
            return mSecurityType;
        }
        void setSecurityType(SecurityType securityType) {
            finishPendingRead();
            if (!Objects.equals(securityType, mSecurityType)) {
                mSecurityType = securityType;
                changed = true;
            }
        }
        void setNetworkConfigId(int networkConfigId) {
            // Not serialized, so don't need to set changed, etc.
            if (networkConfigId >= 0) {
                mNetworkConfigId = networkConfigId;
            }
        }
        AccessPoint toAccessPoint() {
            return toAccessPoint(false);
        }
        AccessPoint toAccessPoint(boolean obfuscate) {
            finishPendingRead();
            AccessPoint.Builder builder = AccessPoint.newBuilder();
            builder.setId(id);
            if (!obfuscate) {
                builder.setBssid(ByteString.copyFrom(bssid.toByteArray()));
            }
            if (mSecurityType != null) {
                builder.setSecurityType(mSecurityType);
            }
            for (PerSignal sig: mSignalForEventAndFrequency.values()) {
                builder.addEventStats(sig.toSignal());
            }
            return builder.build();
        }
        PerBssid merge(AccessPoint ap) {
            if (ap.hasId() && this.id != ap.getId()) {
                return this;
            }
            if (ap.hasSecurityType()) {
                SecurityType prev = ap.getSecurityType();
                if (mSecurityType == null) {
                    mSecurityType = prev;
                } else if (!mSecurityType.equals(prev)) {
                    if (mVerboseLoggingEnabled) {
                        Log.i(TAG, "ID: " + id
                                + "SecurityType changed: " + prev + " to " + mSecurityType);
                    }
                    changed = true;
                }
            }
            for (Signal signal: ap.getEventStatsList()) {
                Pair<Event, Integer> key = new Pair<>(signal.getEvent(), signal.getFrequency());
                PerSignal perSignal = mSignalForEventAndFrequency.get(key);
                if (perSignal == null) {
                    mSignalForEventAndFrequency.put(key,
                            new PerSignal(key.first, key.second).merge(signal));
                    // No need to set changed for this, since we are in sync with what's stored
                } else {
                    perSignal.merge(signal);
                    changed = true;
                }
            }
            return this;
        }

        /**
         * Handles (when convenient) the arrival of previously stored data.
         *
         * The response from IpMemoryStore arrives on a different thread, so we
         * defer handling it until here, when we're on our favorite thread and
         * in a good position to deal with it. We may have already collected some
         * data before now, so we need to be prepared to merge the new and old together.
         */
        void finishPendingRead() {
            final byte[] serialized = finishPendingReadBytes();
            if (serialized == null) return;
            AccessPoint ap;
            try {
                ap = AccessPoint.parseFrom(serialized);
            } catch (InvalidProtocolBufferException e) {
                Log.e(TAG, "Failed to deserialize", e);
                return;
            }
            merge(ap);
        }

        /**
         * Estimates the probability of getting internet access, based on the
         * device experience.
         *
         * @return a probability, expressed as a percentage in the range 0 to 100
         */
        public int estimatePercentInternetAvailability() {
            // Initialize counts accoring to Laplace's rule of succession
            int trials = 2;
            int successes = 1;
            // Aggregate over all of the frequencies
            for (PerSignal s : mSignalForEventAndFrequency.values()) {
                switch (s.event) {
                    case IP_CONFIGURATION_SUCCESS:
                        if (s.elapsedMs != null) {
                            trials += s.elapsedMs.count;
                        }
                        break;
                    case VALIDATION_SUCCESS:
                        if (s.elapsedMs != null) {
                            successes += s.elapsedMs.count;
                        }
                        break;
                    default:
                        break;
                }
            }
            // Note that because of roaming it is possible to count successes
            // without corresponding trials.
            return Math.min(Math.max(Math.round(successes * 100.0f / trials), 0), 100);
        }
    }

    /**
     * A class collecting the connection stats of one network or SSID.
     */
    final class PerNetwork extends MemoryStoreAccessBase {
        public int id;
        public final String ssid;
        public boolean changed;
        private int mLastRssiPoll = INVALID_RSSI;
        private int mLastTxSpeedPoll = LINK_SPEED_UNKNOWN;
        private long mLastRssiPollTimeMs = TS_NONE;
        private long mConnectionSessionStartTimeMs = TS_NONE;
        private NetworkConnectionStats mRecentStats;
        private NetworkConnectionStats mStatsCurrBuild;
        private NetworkConnectionStats mStatsPrevBuild;
        private LruList<Integer> mFrequencyList;
        // In memory keep frequency with timestamp last time available, the elapsed time since boot.
        private SparseLongArray mFreqTimestamp;

        PerNetwork(String ssid) {
            super(computeHashLong(ssid, MacAddress.fromString(DEFAULT_MAC_ADDRESS), mL2KeySeed));
            this.ssid = ssid;
            this.id = idFromLong();
            this.changed = false;
            mRecentStats = new NetworkConnectionStats();
            mStatsCurrBuild = new NetworkConnectionStats();
            mStatsPrevBuild = new NetworkConnectionStats();
            mFrequencyList = new LruList<>(MAX_FREQUENCIES_PER_SSID);
            mFreqTimestamp = new SparseLongArray();
        }

        void updateEventStats(Event event, int rssi, int txSpeed, int failureReason) {
            finishPendingRead();
            long currTimeMs = mClock.getElapsedSinceBootMillis();
            switch (event) {
                case SIGNAL_POLL:
                    mLastRssiPoll = rssi;
                    mLastRssiPollTimeMs = currTimeMs;
                    mLastTxSpeedPoll = txSpeed;
                    changed = true;
                    break;
                case CONNECTION_ATTEMPT:
                    logd(" scan rssi: " + rssi);
                    if (rssi >= mDeviceConfigFacade.getHealthMonitorMinRssiThrDbm()) {
                        mRecentStats.incrementCount(CNT_CONNECTION_ATTEMPT);
                    }
                    mConnectionSessionStartTimeMs = currTimeMs;
                    changed = true;
                    break;
                case CONNECTION_FAILURE:
                    mConnectionSessionStartTimeMs = TS_NONE;
                    if (rssi >= mDeviceConfigFacade.getHealthMonitorMinRssiThrDbm()) {
                        if (failureReason != BssidBlocklistMonitor.REASON_WRONG_PASSWORD) {
                            mRecentStats.incrementCount(CNT_CONNECTION_FAILURE);
                            mRecentStats.incrementCount(CNT_CONSECUTIVE_CONNECTION_FAILURE);
                        }
                        switch (failureReason) {
                            case BssidBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA:
                            case BssidBlocklistMonitor.REASON_ASSOCIATION_REJECTION:
                                mRecentStats.incrementCount(CNT_ASSOCIATION_REJECTION);
                                break;
                            case BssidBlocklistMonitor.REASON_ASSOCIATION_TIMEOUT:
                                mRecentStats.incrementCount(CNT_ASSOCIATION_TIMEOUT);
                                break;
                            case BssidBlocklistMonitor.REASON_AUTHENTICATION_FAILURE:
                            case BssidBlocklistMonitor.REASON_EAP_FAILURE:
                                mRecentStats.incrementCount(CNT_AUTHENTICATION_FAILURE);
                                break;
                            case BssidBlocklistMonitor.REASON_WRONG_PASSWORD:
                            case BssidBlocklistMonitor.REASON_DHCP_FAILURE:
                            default:
                                break;
                        }
                    }
                    changed = true;
                    break;
                case WIFI_DISABLED:
                case DISCONNECTION:
                    handleDisconnection();
                    changed = true;
                    break;
                default:
                    break;
            }
            logd(this.toString());
        }
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("SSID: ").append(ssid).append("\n");
            if (mLastRssiPollTimeMs != TS_NONE) {
                sb.append(" LastRssiPollTime: ");
                sb.append(mLastRssiPollTimeMs);
            }
            sb.append(" LastRssiPoll: " + mLastRssiPoll);
            sb.append(" LastTxSpeedPoll: " + mLastTxSpeedPoll);
            sb.append("\n");
            sb.append(" StatsRecent: ").append(mRecentStats).append("\n");
            sb.append(" StatsCurr: ").append(mStatsCurrBuild).append("\n");
            sb.append(" StatsPrev: ").append(mStatsPrevBuild);
            return sb.toString();
        }
        private void handleDisconnection() {
            if (mConnectionSessionStartTimeMs > TS_NONE) {
                long currTimeMs = mClock.getElapsedSinceBootMillis();
                int currSessionDurationMs = (int) (currTimeMs - mConnectionSessionStartTimeMs);
                int currSessionDurationSec = currSessionDurationMs / 1000;
                mRecentStats.accumulate(CNT_CONNECTION_DURATION_SEC, currSessionDurationSec);
                long timeSinceLastRssiPollMs = currTimeMs - mLastRssiPollTimeMs;
                boolean hasRecentRssiPoll = mLastRssiPollTimeMs > TS_NONE
                        && timeSinceLastRssiPollMs <= mDeviceConfigFacade
                        .getHealthMonitorRssiPollValidTimeMs();
                if (hasRecentRssiPoll) {
                    mRecentStats.incrementCount(CNT_DISCONNECTION);
                }
                int fwAlertValidTimeMs = mDeviceConfigFacade.getHealthMonitorFwAlertValidTimeMs();
                long timeSinceLastFirmAlert = currTimeMs - mFirmwareAlertTimeMs;
                boolean isInvalidFwAlertTime = mFirmwareAlertTimeMs == TS_NONE;
                boolean disableFwAlertCheck = fwAlertValidTimeMs == -1;
                boolean passFirmwareAlertCheck = disableFwAlertCheck ? true : (isInvalidFwAlertTime
                        ? false : timeSinceLastFirmAlert < fwAlertValidTimeMs);
                boolean hasHighRssiOrHighTxSpeed =
                        mLastRssiPoll >= mDeviceConfigFacade.getHealthMonitorMinRssiThrDbm()
                        || mLastTxSpeedPoll >= HEALTH_MONITOR_COUNT_TX_SPEED_MIN_MBPS;
                if (mNonlocalDisconnection && hasRecentRssiPoll
                        && isAbnormalDisconnectionReason(mDisconnectionReason)
                        && passFirmwareAlertCheck
                        && hasHighRssiOrHighTxSpeed) {
                    mRecentStats.incrementCount(CNT_DISCONNECTION_NONLOCAL);
                    if (currSessionDurationMs <= mDeviceConfigFacade
                            .getHealthMonitorShortConnectionDurationThrMs()) {
                        mRecentStats.incrementCount(CNT_SHORT_CONNECTION_NONLOCAL);
                    }
                }
            }
            // Reset CNT_CONSECUTIVE_CONNECTION_FAILURE here so that it can report the correct
            // failure count after a connection success
            mRecentStats.clearCount(CNT_CONSECUTIVE_CONNECTION_FAILURE);
            mConnectionSessionStartTimeMs = TS_NONE;
            mLastRssiPollTimeMs = TS_NONE;
        }

        private boolean isAbnormalDisconnectionReason(int disconnectionReason) {
            long mask = mDeviceConfigFacade.getAbnormalDisconnectionReasonCodeMask();
            return disconnectionReason >= 0 && disconnectionReason <= 63
                    && ((mask >> disconnectionReason) & 0x1) == 0x1;
        }

        @NonNull NetworkConnectionStats getRecentStats() {
            return mRecentStats;
        }
        @NonNull NetworkConnectionStats getStatsCurrBuild() {
            return mStatsCurrBuild;
        }
        @NonNull NetworkConnectionStats getStatsPrevBuild() {
            return mStatsPrevBuild;
        }

        /**
         * Retrieve the list of frequencies seen for this network, with the most recent first.
         * @param ageInMills Max age to filter the channels.
         * @return a list of frequencies
         */
        List<Integer> getFrequencies(Long ageInMills) {
            List<Integer> results = new ArrayList<>();
            Long nowInMills = mClock.getElapsedSinceBootMillis();
            for (Integer freq : mFrequencyList.getEntries()) {
                if (nowInMills - mFreqTimestamp.get(freq, 0L) > ageInMills) {
                    continue;
                }
                results.add(freq);
            }
            return results;
        }

        /**
         * Add a frequency to the list of frequencies for this network.
         * Will evict the least recently added frequency if the cache is full.
         */
        void addFrequency(int frequency) {
            mFrequencyList.add(frequency);
            mFreqTimestamp.put(frequency, mClock.getElapsedSinceBootMillis());
        }

        /**
        /* Detect a significant failure stats change with historical data
        /* or high failure stats without historical data.
        /* @return 0 if recentStats doesn't have sufficient data
         *         1 if recentStats has sufficient data while statsPrevBuild doesn't
         *         2 if recentStats and statsPrevBuild have sufficient data
         */
        int dailyDetection(FailureStats statsDec, FailureStats statsInc, FailureStats statsHigh) {
            finishPendingRead();
            dailyDetectionDisconnectionEvent(statsDec, statsInc, statsHigh);
            return dailyDetectionConnectionEvent(statsDec, statsInc, statsHigh);
        }

        private int dailyDetectionConnectionEvent(FailureStats statsDec, FailureStats statsInc,
                FailureStats statsHigh) {
            // Skip daily detection if recentStats is not sufficient
            if (!isRecentConnectionStatsSufficient()) return INSUFFICIENT_RECENT_STATS;
            if (mStatsPrevBuild.getCount(CNT_CONNECTION_ATTEMPT)
                    < mDeviceConfigFacade.getHealthMonitorMinNumConnectionAttempt()) {
                // don't have enough historical data,
                // so only detect high failure stats without relying on mStatsPrevBuild.
                recentStatsHighDetectionConnection(statsHigh);
                return SUFFICIENT_RECENT_STATS_ONLY;
            } else {
                // mStatsPrevBuild has enough updates,
                // detect improvement or degradation
                statsDeltaDetectionConnection(statsDec, statsInc);
                return SUFFICIENT_RECENT_PREV_STATS;
            }
        }

        private void dailyDetectionDisconnectionEvent(FailureStats statsDec, FailureStats statsInc,
                FailureStats statsHigh) {
            // Skip daily detection if recentStats is not sufficient
            int minConnectAttempt = mDeviceConfigFacade.getHealthMonitorMinNumConnectionAttempt();
            if (mRecentStats.getCount(CNT_CONNECTION_ATTEMPT) < minConnectAttempt) {
                return;
            }
            if (mStatsPrevBuild.getCount(CNT_CONNECTION_ATTEMPT) < minConnectAttempt) {
                recentStatsHighDetectionDisconnection(statsHigh);
            } else {
                statsDeltaDetectionDisconnection(statsDec, statsInc);
            }
        }

        private void statsDeltaDetectionConnection(FailureStats statsDec,
                FailureStats statsInc) {
            statsDeltaDetection(statsDec, statsInc, CNT_CONNECTION_FAILURE,
                    REASON_CONNECTION_FAILURE,
                    mDeviceConfigFacade.getConnectionFailureCountMin(),
                    CNT_CONNECTION_ATTEMPT);
            statsDeltaDetection(statsDec, statsInc, CNT_AUTHENTICATION_FAILURE,
                    REASON_AUTH_FAILURE,
                    mDeviceConfigFacade.getAuthFailureCountMin(),
                    CNT_CONNECTION_ATTEMPT);
            statsDeltaDetection(statsDec, statsInc, CNT_ASSOCIATION_REJECTION,
                    REASON_ASSOC_REJECTION,
                    mDeviceConfigFacade.getAssocRejectionCountMin(),
                    CNT_CONNECTION_ATTEMPT);
            statsDeltaDetection(statsDec, statsInc, CNT_ASSOCIATION_TIMEOUT,
                    REASON_ASSOC_TIMEOUT,
                    mDeviceConfigFacade.getAssocTimeoutCountMin(),
                    CNT_CONNECTION_ATTEMPT);
        }

        private void recentStatsHighDetectionConnection(FailureStats statsHigh) {
            recentStatsHighDetection(statsHigh, CNT_CONNECTION_FAILURE,
                    REASON_CONNECTION_FAILURE,
                    mDeviceConfigFacade.getConnectionFailureHighThrPercent(),
                    mDeviceConfigFacade.getConnectionFailureCountMin(),
                    CNT_CONNECTION_ATTEMPT);
            recentStatsHighDetection(statsHigh, CNT_AUTHENTICATION_FAILURE,
                    REASON_AUTH_FAILURE,
                    mDeviceConfigFacade.getAuthFailureHighThrPercent(),
                    mDeviceConfigFacade.getAuthFailureCountMin(),
                    CNT_CONNECTION_ATTEMPT);
            recentStatsHighDetection(statsHigh, CNT_ASSOCIATION_REJECTION,
                    REASON_ASSOC_REJECTION,
                    mDeviceConfigFacade.getAssocRejectionHighThrPercent(),
                    mDeviceConfigFacade.getAssocRejectionCountMin(),
                    CNT_CONNECTION_ATTEMPT);
            recentStatsHighDetection(statsHigh, CNT_ASSOCIATION_TIMEOUT,
                    REASON_ASSOC_TIMEOUT,
                    mDeviceConfigFacade.getAssocTimeoutHighThrPercent(),
                    mDeviceConfigFacade.getAssocTimeoutCountMin(),
                    CNT_CONNECTION_ATTEMPT);
        }

        private void statsDeltaDetectionDisconnection(FailureStats statsDec,
                FailureStats statsInc) {
            statsDeltaDetection(statsDec, statsInc, CNT_SHORT_CONNECTION_NONLOCAL,
                    REASON_SHORT_CONNECTION_NONLOCAL,
                    mDeviceConfigFacade.getShortConnectionNonlocalCountMin(),
                    CNT_CONNECTION_ATTEMPT);
            statsDeltaDetection(statsDec, statsInc, CNT_DISCONNECTION_NONLOCAL,
                    REASON_DISCONNECTION_NONLOCAL,
                    mDeviceConfigFacade.getDisconnectionNonlocalCountMin(),
                    CNT_CONNECTION_ATTEMPT);
        }

        private void recentStatsHighDetectionDisconnection(FailureStats statsHigh) {
            recentStatsHighDetection(statsHigh, CNT_SHORT_CONNECTION_NONLOCAL,
                    REASON_SHORT_CONNECTION_NONLOCAL,
                    mDeviceConfigFacade.getShortConnectionNonlocalHighThrPercent(),
                    mDeviceConfigFacade.getShortConnectionNonlocalCountMin(),
                    CNT_DISCONNECTION);
            recentStatsHighDetection(statsHigh, CNT_DISCONNECTION_NONLOCAL,
                    REASON_DISCONNECTION_NONLOCAL,
                    mDeviceConfigFacade.getDisconnectionNonlocalHighThrPercent(),
                    mDeviceConfigFacade.getDisconnectionNonlocalCountMin(),
                    CNT_DISCONNECTION);
        }

        private boolean statsDeltaDetection(FailureStats statsDec,
                FailureStats statsInc, int countCode, int reasonCode,
                int minCount, int refCountCode) {
            if (isRatioAboveThreshold(mRecentStats, mStatsPrevBuild, countCode, refCountCode)
                    && mRecentStats.getCount(countCode) >= minCount) {
                statsInc.incrementCount(reasonCode);
                return true;
            }

            if (isRatioAboveThreshold(mStatsPrevBuild, mRecentStats, countCode, refCountCode)
                    && mStatsPrevBuild.getCount(countCode) >= minCount) {
                statsDec.incrementCount(reasonCode);
                return true;
            }
            return false;
        }

        private boolean recentStatsHighDetection(FailureStats statsHigh, int countCode,
                int reasonCode, int highThresholdPercent, int minCount, int refCountCode) {
            if (isHighPercentageAndEnoughCount(mRecentStats, countCode, reasonCode,
                    highThresholdPercent, minCount, refCountCode)) {
                statsHigh.incrementCount(reasonCode);
                return true;
            }
            return false;
        }

        private boolean isRatioAboveThreshold(NetworkConnectionStats stats1,
                NetworkConnectionStats stats2,
                @ConnectionCountCode int countCode, int refCountCode) {
            // Also with Laplace's rule of succession discussed above
            // R1 = (stats1(countCode) + 1) / (stats1(refCountCode) + 2)
            // R2 = (stats2(countCode) + 1) / (stats2(refCountCode) + 2)
            // Check R1 / R2 >= ratioThr
            return ((stats1.getCount(countCode) + 1) * (stats2.getCount(refCountCode) + 2)
                    * mDeviceConfigFacade.HEALTH_MONITOR_RATIO_THR_DENOMINATOR)
                    >= ((stats1.getCount(refCountCode) + 2) * (stats2.getCount(countCode) + 1)
                    * mDeviceConfigFacade.getHealthMonitorRatioThrNumerator());
        }

        private boolean isRecentConnectionStatsSufficient() {
            return (mRecentStats.getCount(CNT_CONNECTION_ATTEMPT)
                >= mDeviceConfigFacade.getHealthMonitorMinNumConnectionAttempt());
        }

        // Update StatsCurrBuild with recentStats and clear recentStats
        void updateAfterDailyDetection() {
            // Skip update if recentStats is not sufficient since daily detection is also skipped
            if (!isRecentConnectionStatsSufficient()) return;
            mStatsCurrBuild.accumulateAll(mRecentStats);
            mRecentStats.clear();
            changed = true;
        }

        // Refresh StatsPrevBuild with StatsCurrBuild which is cleared afterwards
        void updateAfterSwBuildChange() {
            finishPendingRead();
            mStatsPrevBuild.copy(mStatsCurrBuild);
            mRecentStats.clear();
            mStatsCurrBuild.clear();
            changed = true;
        }

        NetworkStats toNetworkStats() {
            finishPendingRead();
            NetworkStats.Builder builder = NetworkStats.newBuilder();
            builder.setId(id);
            builder.setRecentStats(toConnectionStats(mRecentStats));
            builder.setStatsCurrBuild(toConnectionStats(mStatsCurrBuild));
            builder.setStatsPrevBuild(toConnectionStats(mStatsPrevBuild));
            if (mFrequencyList.size() > 0) {
                builder.addAllFrequencies(mFrequencyList.getEntries());
            }
            return builder.build();
        }

        private ConnectionStats toConnectionStats(NetworkConnectionStats stats) {
            ConnectionStats.Builder builder = ConnectionStats.newBuilder();
            builder.setNumConnectionAttempt(stats.getCount(CNT_CONNECTION_ATTEMPT));
            builder.setNumConnectionFailure(stats.getCount(CNT_CONNECTION_FAILURE));
            builder.setConnectionDurationSec(stats.getCount(CNT_CONNECTION_DURATION_SEC));
            builder.setNumDisconnectionNonlocal(stats.getCount(CNT_DISCONNECTION_NONLOCAL));
            builder.setNumDisconnection(stats.getCount(CNT_DISCONNECTION));
            builder.setNumShortConnectionNonlocal(stats.getCount(CNT_SHORT_CONNECTION_NONLOCAL));
            builder.setNumAssociationRejection(stats.getCount(CNT_ASSOCIATION_REJECTION));
            builder.setNumAssociationTimeout(stats.getCount(CNT_ASSOCIATION_TIMEOUT));
            builder.setNumAuthenticationFailure(stats.getCount(CNT_AUTHENTICATION_FAILURE));
            return builder.build();
        }

        void finishPendingRead() {
            final byte[] serialized = finishPendingReadBytes();
            if (serialized == null) return;
            NetworkStats ns;
            try {
                ns = NetworkStats.parseFrom(serialized);
            } catch (InvalidProtocolBufferException e) {
                Log.e(TAG, "Failed to deserialize", e);
                return;
            }
            mergeNetworkStatsFromMemory(ns);
            changed = true;
        }

        PerNetwork mergeNetworkStatsFromMemory(@NonNull NetworkStats ns) {
            if (ns.hasId() && this.id != ns.getId()) {
                return this;
            }
            if (ns.hasRecentStats()) {
                ConnectionStats recentStats = ns.getRecentStats();
                mergeConnectionStats(recentStats, mRecentStats);
            }
            if (ns.hasStatsCurrBuild()) {
                ConnectionStats statsCurr = ns.getStatsCurrBuild();
                mStatsCurrBuild.clear();
                mergeConnectionStats(statsCurr, mStatsCurrBuild);
            }
            if (ns.hasStatsPrevBuild()) {
                ConnectionStats statsPrev = ns.getStatsPrevBuild();
                mStatsPrevBuild.clear();
                mergeConnectionStats(statsPrev, mStatsPrevBuild);
            }
            if (ns.getFrequenciesList().size() > 0) {
                // This merge assumes that whatever data is in memory is more recent that what's
                // in store
                List<Integer> mergedFrequencyList = mFrequencyList.getEntries();
                mergedFrequencyList.addAll(ns.getFrequenciesList());
                mFrequencyList = new LruList<>(MAX_FREQUENCIES_PER_SSID);
                for (int i = mergedFrequencyList.size() - 1; i >= 0; i--) {
                    mFrequencyList.add(mergedFrequencyList.get(i));
                }
            }
            return this;
        }

        private void mergeConnectionStats(ConnectionStats source, NetworkConnectionStats target) {
            if (source.hasNumConnectionAttempt()) {
                target.accumulate(CNT_CONNECTION_ATTEMPT, source.getNumConnectionAttempt());
            }
            if (source.hasNumConnectionFailure()) {
                target.accumulate(CNT_CONNECTION_ATTEMPT, source.getNumConnectionFailure());
            }
            if (source.hasConnectionDurationSec()) {
                target.accumulate(CNT_CONNECTION_DURATION_SEC, source.getConnectionDurationSec());
            }
            if (source.hasNumDisconnectionNonlocal()) {
                target.accumulate(CNT_DISCONNECTION_NONLOCAL, source.getNumDisconnectionNonlocal());
            }
            if (source.hasNumDisconnection()) {
                target.accumulate(CNT_DISCONNECTION, source.getNumDisconnection());
            }
            if (source.hasNumShortConnectionNonlocal()) {
                target.accumulate(CNT_SHORT_CONNECTION_NONLOCAL,
                        source.getNumShortConnectionNonlocal());
            }
            if (source.hasNumAssociationRejection()) {
                target.accumulate(CNT_ASSOCIATION_REJECTION, source.getNumAssociationRejection());
            }
            if (source.hasNumAssociationTimeout()) {
                target.accumulate(CNT_ASSOCIATION_TIMEOUT, source.getNumAssociationTimeout());
            }
            if (source.hasNumAuthenticationFailure()) {
                target.accumulate(CNT_AUTHENTICATION_FAILURE, source.getNumAuthenticationFailure());
            }
        }
    }

    // Codes for various connection related counts
    public static final int CNT_INVALID = -1;
    public static final int CNT_CONNECTION_ATTEMPT = 0;
    public static final int CNT_CONNECTION_FAILURE = 1;
    public static final int CNT_CONNECTION_DURATION_SEC = 2;
    public static final int CNT_ASSOCIATION_REJECTION = 3;
    public static final int CNT_ASSOCIATION_TIMEOUT = 4;
    public static final int CNT_AUTHENTICATION_FAILURE = 5;
    public static final int CNT_SHORT_CONNECTION_NONLOCAL = 6;
    public static final int CNT_DISCONNECTION_NONLOCAL = 7;
    public static final int CNT_DISCONNECTION = 8;
    public static final int CNT_CONSECUTIVE_CONNECTION_FAILURE = 9;
    // Constant being used to keep track of how many counter there are.
    public static final int NUMBER_CONNECTION_CNT_CODE = 10;
    private static final String[] CONNECTION_CNT_NAME = {
        " ConnectAttempt: ",
        " ConnectFailure: ",
        " ConnectDurSec: ",
        " AssocRej: ",
        " AssocTimeout: ",
        " AuthFailure: ",
        " ShortDiscNonlocal: ",
        " DisconnectNonlocal: ",
        " Disconnect: ",
        " ConsecutiveConnectFailure: "
    };

    @IntDef(prefix = { "CNT_" }, value = {
        CNT_CONNECTION_ATTEMPT,
        CNT_CONNECTION_FAILURE,
        CNT_CONNECTION_DURATION_SEC,
        CNT_ASSOCIATION_REJECTION,
        CNT_ASSOCIATION_TIMEOUT,
        CNT_AUTHENTICATION_FAILURE,
        CNT_SHORT_CONNECTION_NONLOCAL,
        CNT_DISCONNECTION_NONLOCAL,
        CNT_DISCONNECTION,
        CNT_CONSECUTIVE_CONNECTION_FAILURE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ConnectionCountCode {}

    /**
     * A class maintaining the connection related statistics of a Wifi network.
     */
    public static class NetworkConnectionStats {
        private final int[] mCount = new int[NUMBER_CONNECTION_CNT_CODE];
        private int mRecentCountCode = CNT_INVALID;
        /**
         * Copy all values
         * @param src is the source of copy
         */
        public void copy(NetworkConnectionStats src) {
            for (int i = 0; i < NUMBER_CONNECTION_CNT_CODE; i++) {
                mCount[i] = src.getCount(i);
            }
            mRecentCountCode = src.mRecentCountCode;
        }

        /**
         * Clear all counters
         */
        public void clear() {
            for (int i = 0; i < NUMBER_CONNECTION_CNT_CODE; i++) {
                mCount[i] = 0;
            }
            mRecentCountCode = CNT_INVALID;
        }

        /**
         * Get counter value
         * @param countCode is the selected counter
         * @return the value of selected counter
         */
        public int getCount(@ConnectionCountCode int countCode) {
            return mCount[countCode];
        }

        /**
         * Clear counter value
         * @param countCode is the selected counter to be cleared
         */
        public void clearCount(@ConnectionCountCode int countCode) {
            mCount[countCode] = 0;
        }

        /**
         * Increment count value by 1
         * @param countCode is the selected counter
         */
        public void incrementCount(@ConnectionCountCode int countCode) {
            mCount[countCode]++;
            mRecentCountCode = countCode;
        }

        /**
         * Got the recent incremented count code
         */
        public int getRecentCountCode() {
            return mRecentCountCode;
        }

        /**
         * Decrement count value by 1
         * @param countCode is the selected counter
         */
        public void decrementCount(@ConnectionCountCode int countCode) {
            mCount[countCode]--;
        }

        /**
         * Add and accumulate the selected counter
         * @param countCode is the selected counter
         * @param cnt is the value to be added to the counter
         */
        public void accumulate(@ConnectionCountCode int countCode, int cnt) {
            mCount[countCode] += cnt;
        }

        /**
         * Accumulate daily stats to historical data
         * @param recentStats are the raw daily counts
         */
        public void accumulateAll(NetworkConnectionStats recentStats) {
            // 32-bit counter in second can support connection duration up to 68 years.
            // Similarly 32-bit counter can support up to continuous connection attempt
            // up to 68 years with one attempt per second.
            for (int i = 0; i < NUMBER_CONNECTION_CNT_CODE; i++) {
                mCount[i] += recentStats.getCount(i);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < NUMBER_CONNECTION_CNT_CODE; i++) {
                sb.append(CONNECTION_CNT_NAME[i]);
                sb.append(mCount[i]);
            }
            return sb.toString();
        }
    }
    /**
     * A base class dealing with common operations of MemoryStore.
     */
    public static class MemoryStoreAccessBase {
        private final String mL2Key;
        private final long mHash;
        private static final String TAG = "WifiMemoryStoreAccessBase";
        private final AtomicReference<byte[]> mPendingReadFromStore = new AtomicReference<>();
        MemoryStoreAccessBase(long hash) {
            mHash = hash;
            mL2Key = l2KeyFromLong();
        }
        String getL2Key() {
            return mL2Key;
        }

        private String l2KeyFromLong() {
            return "W" + Long.toHexString(mHash);
        }

        /**
         * Callback function when MemoryStore read is done
         * @param serialized is the readback value
         */
        void readBackListener(byte[] serialized) {
            if (serialized == null) return;
            byte[] old = mPendingReadFromStore.getAndSet(serialized);
            if (old != null) {
                Log.e(TAG, "More answers than we expected!");
            }
        }

        /**
         * Handles (when convenient) the arrival of previously stored data.
         *
         * The response from IpMemoryStore arrives on a different thread, so we
         * defer handling it until here, when we're on our favorite thread and
         * in a good position to deal with it. We may have already collected some
         * data before now, so we need to be prepared to merge the new and old together.
         */
        byte[] finishPendingReadBytes() {
            return mPendingReadFromStore.getAndSet(null);
        }

        int idFromLong() {
            return (int) mHash & 0x7fffffff;
        }
    }

    private void logd(String string) {
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, string);
        }
    }
    // Returned by lookupBssid when the BSSID is not available,
    // for instance when we are not associated.
    private final PerBssid mDummyPerBssid;

    private final Map<MacAddress, PerBssid> mApForBssid = new ArrayMap<>();
    private int mApForBssidTargetSize = TARGET_IN_MEMORY_ENTRIES;
    private int mApForBssidReferenced = 0;

    // TODO should be private, but WifiCandidates needs it
    @NonNull PerBssid lookupBssid(String ssid, String bssid) {
        MacAddress mac;
        if (ssid == null || WifiManager.UNKNOWN_SSID.equals(ssid) || bssid == null) {
            return mDummyPerBssid;
        }
        try {
            mac = MacAddress.fromString(bssid);
        } catch (IllegalArgumentException e) {
            return mDummyPerBssid;
        }
        if (mac.equals(mDummyPerBssid.bssid)) {
            return mDummyPerBssid;
        }
        PerBssid ans = mApForBssid.get(mac);
        if (ans == null || !ans.ssid.equals(ssid)) {
            ans = new PerBssid(ssid, mac);
            PerBssid old = mApForBssid.put(mac, ans);
            if (old != null) {
                Log.i(TAG, "Discarding stats for score card (ssid changed) ID: " + old.id);
                if (old.referenced) mApForBssidReferenced--;
            }
            requestReadBssid(ans);
        }
        if (!ans.referenced) {
            ans.referenced = true;
            mApForBssidReferenced++;
            clean();
        }
        return ans;
    }

    private void requestReadBssid(final PerBssid perBssid) {
        if (mMemoryStore != null) {
            mMemoryStore.read(perBssid.getL2Key(), PER_BSSID_DATA_NAME,
                    (value) -> perBssid.readBackListener(value));
        }
    }

    private void requestReadForAllChanged() {
        for (PerBssid perBssid : mApForBssid.values()) {
            if (perBssid.changed) {
                requestReadBssid(perBssid);
            }
        }
    }

    // Returned by lookupNetwork when the network is not available,
    // for instance when we are not associated.
    private final PerNetwork mDummyPerNetwork;
    private final Map<String, PerNetwork> mApForNetwork = new ArrayMap<>();
    PerNetwork lookupNetwork(String ssid) {
        if (ssid == null || WifiManager.UNKNOWN_SSID.equals(ssid)) {
            return mDummyPerNetwork;
        }

        PerNetwork ans = mApForNetwork.get(ssid);
        if (ans == null) {
            ans = new PerNetwork(ssid);
            mApForNetwork.put(ssid, ans);
            requestReadNetwork(ans);
        }
        return ans;
    }

    /**
     * Remove network from cache and memory store
     * @param ssid is the network SSID
     */
    public void removeNetwork(String ssid) {
        if (ssid == null || WifiManager.UNKNOWN_SSID.equals(ssid)) {
            return;
        }
        mApForNetwork.remove(ssid);
        mApForBssid.entrySet().removeIf(entry -> ssid.equals(entry.getValue().ssid));
        if (mMemoryStore == null) return;
        mMemoryStore.removeCluster(groupHintFromSsid(ssid));
    }

    void requestReadNetwork(final PerNetwork perNetwork) {
        if (mMemoryStore != null) {
            mMemoryStore.read(perNetwork.getL2Key(), PER_NETWORK_DATA_NAME,
                    (value) -> perNetwork.readBackListener(value));
        }
    }

    /**
     * Issues write requests for all changed entries.
     *
     * This should be called from time to time to save the state to persistent
     * storage. Since we always check internal state first, this does not need
     * to be called very often, but it should be called before shutdown.
     *
     * @returns number of writes issued.
     */
    public int doWrites() {
        return doWritesBssid() + doWritesNetwork();
    }

    private int doWritesBssid() {
        if (mMemoryStore == null) return 0;
        int count = 0;
        int bytes = 0;
        for (PerBssid perBssid : mApForBssid.values()) {
            if (perBssid.changed) {
                perBssid.finishPendingRead();
                byte[] serialized = perBssid.toAccessPoint(/* No BSSID */ true).toByteArray();
                mMemoryStore.setCluster(perBssid.getL2Key(), groupHintFromSsid(perBssid.ssid));
                mMemoryStore.write(perBssid.getL2Key(), PER_BSSID_DATA_NAME, serialized);

                perBssid.changed = false;
                count++;
                bytes += serialized.length;
            }
        }
        if (mVerboseLoggingEnabled && count > 0) {
            Log.v(TAG, "Write count: " + count + ", bytes: " + bytes);
        }
        return count;
    }

    private int doWritesNetwork() {
        if (mMemoryStore == null) return 0;
        int count = 0;
        int bytes = 0;
        for (PerNetwork perNetwork : mApForNetwork.values()) {
            if (perNetwork.changed) {
                perNetwork.finishPendingRead();
                byte[] serialized = perNetwork.toNetworkStats().toByteArray();
                mMemoryStore.setCluster(perNetwork.getL2Key(), groupHintFromSsid(perNetwork.ssid));
                mMemoryStore.write(perNetwork.getL2Key(), PER_NETWORK_DATA_NAME, serialized);
                perNetwork.changed = false;
                count++;
                bytes += serialized.length;
            }
        }
        if (mVerboseLoggingEnabled && count > 0) {
            Log.v(TAG, "Write count: " + count + ", bytes: " + bytes);
        }
        return count;
    }

    /**
     * Evicts older entries from memory.
     *
     * This uses an approximate least-recently-used method. When the number of
     * referenced entries exceeds the target value, any items that have not been
     * referenced since the last round are evicted, and the remaining entries
     * are marked as unreferenced. The total count varies between the target
     * value and twice the target value.
     */
    private void clean() {
        if (mMemoryStore == null) return;
        if (mApForBssidReferenced >= mApForBssidTargetSize) {
            doWritesBssid(); // Do not want to evict changed items
            // Evict the unreferenced ones, and clear all the referenced bits for the next round.
            Iterator<Map.Entry<MacAddress, PerBssid>> it = mApForBssid.entrySet().iterator();
            while (it.hasNext()) {
                PerBssid perBssid = it.next().getValue();
                if (perBssid.referenced) {
                    perBssid.referenced = false;
                } else {
                    it.remove();
                    if (mVerboseLoggingEnabled) Log.v(TAG, "Evict " + perBssid.id);
                }
            }
            mApForBssidReferenced = 0;
        }
    }

    /**
     * Compute a hash value with the given SSID and MAC address
     * @param ssid is the network SSID
     * @param mac is the network MAC address
     * @param l2KeySeed is the seed for hash generation
     * @return
     */
    public static long computeHashLong(String ssid, MacAddress mac, String l2KeySeed) {
        byte[][] parts = {
                // Our seed keeps the L2Keys specific to this device
                l2KeySeed.getBytes(),
                // ssid is either quoted utf8 or hex-encoded bytes; turn it into plain bytes.
                NativeUtil.byteArrayFromArrayList(NativeUtil.decodeSsid(ssid)),
                // And the BSSID
                mac.toByteArray()
        };
        // Assemble the parts into one, with single-byte lengths before each.
        int n = 0;
        for (int i = 0; i < parts.length; i++) {
            n += 1 + parts[i].length;
        }
        byte[] mashed = new byte[n];
        int p = 0;
        for (int i = 0; i < parts.length; i++) {
            byte[] part = parts[i];
            mashed[p++] = (byte) part.length;
            for (int j = 0; j < part.length; j++) {
                mashed[p++] = part[j];
            }
        }
        // Finally, turn that into a long
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "SHA-256 not supported.");
            return 0;
        }
        ByteBuffer buffer = ByteBuffer.wrap(md.digest(mashed));
        return buffer.getLong();
    }

    private static String groupHintFromLong(long hash) {
        return "G" + Long.toHexString(hash);
    }

    @VisibleForTesting
    PerBssid fetchByBssid(MacAddress mac) {
        return mApForBssid.get(mac);
    }

    @VisibleForTesting
    PerNetwork fetchByNetwork(String ssid) {
        return mApForNetwork.get(ssid);
    }

    @VisibleForTesting
    PerBssid perBssidFromAccessPoint(String ssid, AccessPoint ap) {
        MacAddress bssid = MacAddress.fromBytes(ap.getBssid().toByteArray());
        return new PerBssid(ssid, bssid).merge(ap);
    }

    @VisibleForTesting
    PerNetwork perNetworkFromNetworkStats(String ssid, NetworkStats ns) {
        return new PerNetwork(ssid).mergeNetworkStatsFromMemory(ns);
    }

    final class PerSignal {
        public final Event event;
        public final int frequency;
        public final PerUnivariateStatistic rssi;
        public final PerUnivariateStatistic linkspeed;
        @Nullable public final PerUnivariateStatistic elapsedMs;
        PerSignal(Event event, int frequency) {
            this.event = event;
            this.frequency = frequency;
            switch (event) {
                case SIGNAL_POLL:
                case IP_CONFIGURATION_SUCCESS:
                case IP_REACHABILITY_LOST:
                    this.rssi = new PerUnivariateStatistic(RSSI_BUCKETS);
                    break;
                default:
                    this.rssi = new PerUnivariateStatistic();
                    break;
            }
            this.linkspeed = new PerUnivariateStatistic();
            switch (event) {
                case FIRST_POLL_AFTER_CONNECTION:
                case IP_CONFIGURATION_SUCCESS:
                case VALIDATION_SUCCESS:
                case CONNECTION_FAILURE:
                case DISCONNECTION:
                case WIFI_DISABLED:
                case ROAM_FAILURE:
                    this.elapsedMs = new PerUnivariateStatistic();
                    break;
                default:
                    this.elapsedMs = null;
                    break;
            }
        }
        PerSignal merge(Signal signal) {
            Preconditions.checkArgument(event == signal.getEvent());
            Preconditions.checkArgument(frequency == signal.getFrequency());
            rssi.merge(signal.getRssi());
            linkspeed.merge(signal.getLinkspeed());
            if (elapsedMs != null && signal.hasElapsedMs()) {
                elapsedMs.merge(signal.getElapsedMs());
            }
            return this;
        }
        Signal toSignal() {
            Signal.Builder builder = Signal.newBuilder();
            builder.setEvent(event)
                    .setFrequency(frequency)
                    .setRssi(rssi.toUnivariateStatistic())
                    .setLinkspeed(linkspeed.toUnivariateStatistic());
            if (elapsedMs != null) {
                builder.setElapsedMs(elapsedMs.toUnivariateStatistic());
            }
            if (rssi.intHistogram != null
                    && rssi.intHistogram.numNonEmptyBuckets() > 0) {
                logd("Histogram " + event + " RSSI" + rssi.intHistogram);
            }
            return builder.build();
        }
    }

    final class PerUnivariateStatistic {
        public long count = 0;
        public double sum = 0.0;
        public double sumOfSquares = 0.0;
        public double minValue = Double.POSITIVE_INFINITY;
        public double maxValue = Double.NEGATIVE_INFINITY;
        public double historicalMean = 0.0;
        public double historicalVariance = Double.POSITIVE_INFINITY;
        public IntHistogram intHistogram = null;
        PerUnivariateStatistic() {}
        PerUnivariateStatistic(int[] bucketBoundaries) {
            intHistogram = new IntHistogram(bucketBoundaries);
        }
        void update(double value) {
            count++;
            sum += value;
            sumOfSquares += value * value;
            minValue = Math.min(minValue, value);
            maxValue = Math.max(maxValue, value);
            if (intHistogram != null) {
                intHistogram.add(Math.round((float) value), 1);
            }
        }
        void age() {
            //TODO  Fold the current stats into the historical stats
        }
        void merge(UnivariateStatistic stats) {
            if (stats.hasCount()) {
                count += stats.getCount();
                sum += stats.getSum();
                sumOfSquares += stats.getSumOfSquares();
            }
            if (stats.hasMinValue()) {
                minValue = Math.min(minValue, stats.getMinValue());
            }
            if (stats.hasMaxValue()) {
                maxValue = Math.max(maxValue, stats.getMaxValue());
            }
            if (stats.hasHistoricalVariance()) {
                if (historicalVariance < Double.POSITIVE_INFINITY) {
                    // Combine the estimates; c.f.
                    // Maybeck, Stochasic Models, Estimation, and Control, Vol. 1
                    // equations (1-3) and (1-4)
                    double numer1 = stats.getHistoricalVariance();
                    double numer2 = historicalVariance;
                    double denom = numer1 + numer2;
                    historicalMean = (numer1 * historicalMean
                                    + numer2 * stats.getHistoricalMean())
                                    / denom;
                    historicalVariance = numer1 * numer2 / denom;
                } else {
                    historicalMean = stats.getHistoricalMean();
                    historicalVariance = stats.getHistoricalVariance();
                }
            }
            if (intHistogram != null) {
                for (HistogramBucket bucket : stats.getBucketsList()) {
                    long low = bucket.getLow();
                    long count = bucket.getNumber();
                    if (low != (int) low || count != (int) count || count < 0) {
                        Log.e(TAG, "Found corrupted histogram! Clearing.");
                        intHistogram.clear();
                        break;
                    }
                    intHistogram.add((int) low, (int) count);
                }
            }
        }
        UnivariateStatistic toUnivariateStatistic() {
            UnivariateStatistic.Builder builder = UnivariateStatistic.newBuilder();
            if (count != 0) {
                builder.setCount(count)
                        .setSum(sum)
                        .setSumOfSquares(sumOfSquares)
                        .setMinValue(minValue)
                        .setMaxValue(maxValue);
            }
            if (historicalVariance < Double.POSITIVE_INFINITY) {
                builder.setHistoricalMean(historicalMean)
                        .setHistoricalVariance(historicalVariance);
            }
            if (mPersistentHistograms
                    && intHistogram != null && intHistogram.numNonEmptyBuckets() > 0) {
                for (IntHistogram.Bucket b : intHistogram) {
                    if (b.count == 0) continue;
                    builder.addBuckets(
                            HistogramBucket.newBuilder().setLow(b.start).setNumber(b.count));
                }
            }
            return builder.build();
        }
    }

    /**
     * Returns the current scorecard in the form of a protobuf com_android_server_wifi.NetworkList
     *
     * Synchronization is the caller's responsibility.
     *
     * @param obfuscate - if true, ssids and bssids are omitted (short id only)
     */
    public byte[] getNetworkListByteArray(boolean obfuscate) {
        // These are really grouped by ssid, ignoring the security type.
        Map<String, Network.Builder> networks = new ArrayMap<>();
        for (PerBssid perBssid: mApForBssid.values()) {
            String key = perBssid.ssid;
            Network.Builder network = networks.get(key);
            if (network == null) {
                network = Network.newBuilder();
                networks.put(key, network);
                if (!obfuscate) {
                    network.setSsid(perBssid.ssid);
                }
            }
            if (perBssid.mNetworkAgentId >= network.getNetworkAgentId()) {
                network.setNetworkAgentId(perBssid.mNetworkAgentId);
            }
            if (perBssid.mNetworkConfigId >= network.getNetworkConfigId()) {
                network.setNetworkConfigId(perBssid.mNetworkConfigId);
            }
            network.addAccessPoints(perBssid.toAccessPoint(obfuscate));
        }
        for (PerNetwork perNetwork: mApForNetwork.values()) {
            String key = perNetwork.ssid;
            Network.Builder network = networks.get(key);
            if (network != null) {
                network.setNetworkStats(perNetwork.toNetworkStats());
            }
        }
        NetworkList.Builder builder = NetworkList.newBuilder();
        for (Network.Builder network: networks.values()) {
            builder.addNetworks(network);
        }
        return builder.build().toByteArray();
    }

    /**
     * Returns the current scorecard as a base64-encoded protobuf
     *
     * Synchronization is the caller's responsibility.
     *
     * @param obfuscate - if true, bssids are omitted (short id only)
     */
    public String getNetworkListBase64(boolean obfuscate) {
        byte[] raw = getNetworkListByteArray(obfuscate);
        return Base64.encodeToString(raw, Base64.DEFAULT);
    }

    /**
     * Clears the internal state.
     *
     * This is called in response to a factoryReset call from Settings.
     * The memory store will be called after we are called, to wipe the stable
     * storage as well. Since we will have just removed all of our networks,
     * it is very unlikely that we're connected, or will connect immediately.
     * Any in-flight reads will land in the objects we are dropping here, and
     * the memory store should drop the in-flight writes. Ideally we would
     * avoid issuing reads until we were sure that the memory store had
     * received the factoryReset.
     */
    public void clear() {
        mApForBssid.clear();
        mApForNetwork.clear();
        resetConnectionStateInternal(false);
    }
}
