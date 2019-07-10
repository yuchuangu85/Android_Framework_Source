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

package com.android.server.wifi;

import android.content.Context;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.WifiKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConnectionStatistics;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * AutoJoin controller is responsible for WiFi Connect decision
 *
 * It runs in the thread context of WifiStateMachine
 *
 */
public class WifiAutoJoinController {

    private Context mContext;
    private WifiStateMachine mWifiStateMachine;
    private WifiConfigStore mWifiConfigStore;
    private WifiNative mWifiNative;

    private NetworkScoreManager scoreManager;
    private WifiNetworkScoreCache mNetworkScoreCache;

    private static final String TAG = "WifiAutoJoinController ";
    private static boolean DBG = false;
    private static boolean VDBG = false;
    private static final boolean mStaStaSupported = false;

    public static int mScanResultMaximumAge = 40000; /* milliseconds unit */
    public static int mScanResultAutoJoinAge = 5000; /* milliseconds unit */

    private String mCurrentConfigurationKey = null; //used by autojoin

    private final HashMap<String, ScanDetail> scanResultCache = new HashMap<>();

    private WifiConnectionStatistics mWifiConnectionStatistics;

    /**
     * Whether to allow connections to untrusted networks.
     */
    private boolean mAllowUntrustedConnections = false;

    /* For debug purpose only: if the scored override a score */
    boolean didOverride = false;

    // Lose the non-auth failure blacklisting after 8 hours
    private final static long loseBlackListHardMilli = 1000 * 60 * 60 * 8;
    // Lose some temporary blacklisting after 30 minutes
    private final static long loseBlackListSoftMilli = 1000 * 60 * 30;

    /**
     * @see android.provider.Settings.Global#WIFI_EPHEMERAL_OUT_OF_RANGE_TIMEOUT_MS
     */
    private static final long DEFAULT_EPHEMERAL_OUT_OF_RANGE_TIMEOUT_MS = 1000 * 60; // 1 minute

    public static final int AUTO_JOIN_IDLE = 0;
    public static final int AUTO_JOIN_ROAMING = 1;
    public static final int AUTO_JOIN_EXTENDED_ROAMING = 2;
    public static final int AUTO_JOIN_OUT_OF_NETWORK_ROAMING = 3;

    public static final int HIGH_THRESHOLD_MODIFIER = 5;

    public static final int MAX_RSSI_DELTA = 50;

    // Below are AutoJoin wide parameters indicating if we should be aggressive before joining
    // weak network. Note that we cannot join weak network that are going to be marked as unanted by
    // ConnectivityService because this will trigger link flapping.
    /**
     * There was a non-blacklisted configuration that we bailed from because of a weak signal
     */
    boolean didBailDueToWeakRssi = false;
    /**
     * number of time we consecutively bailed out of an eligible network because its signal
     * was too weak
     */
    int weakRssiBailCount = 0;

    WifiAutoJoinController(Context c, WifiStateMachine w, WifiConfigStore s,
                           WifiConnectionStatistics st, WifiNative n) {
        mContext = c;
        mWifiStateMachine = w;
        mWifiConfigStore = s;
        mWifiNative = n;
        mNetworkScoreCache = null;
        mWifiConnectionStatistics = st;
        scoreManager =
                (NetworkScoreManager) mContext.getSystemService(Context.NETWORK_SCORE_SERVICE);
        if (scoreManager == null)
            logDbg("Registered scoreManager NULL " + " service " + Context.NETWORK_SCORE_SERVICE);

        if (scoreManager != null) {
            mNetworkScoreCache = new WifiNetworkScoreCache(mContext);
            scoreManager.registerNetworkScoreCache(NetworkKey.TYPE_WIFI, mNetworkScoreCache);
        } else {
            logDbg("No network score service: Couldnt register as a WiFi score Manager, type="
                    + Integer.toString(NetworkKey.TYPE_WIFI)
                    + " service " + Context.NETWORK_SCORE_SERVICE);
            mNetworkScoreCache = null;
        }
    }

    void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            DBG = true;
            VDBG = true;
        } else {
            DBG = false;
            VDBG = false;
        }
    }

    /**
     * Flush out scan results older than mScanResultMaximumAge
     */
    private void ageScanResultsOut(int delay) {
        if (delay <= 0) {
            delay = mScanResultMaximumAge; // Something sane
        }
        long milli = System.currentTimeMillis();
        if (VDBG) {
            logDbg("ageScanResultsOut delay " + Integer.valueOf(delay) + " size "
                    + Integer.valueOf(scanResultCache.size()) + " now " + Long.valueOf(milli));
        }

        Iterator<HashMap.Entry<String, ScanDetail>> iter = scanResultCache.entrySet().iterator();
        while (iter.hasNext()) {
            HashMap.Entry<String, ScanDetail> entry = iter.next();
            ScanDetail scanDetail = entry.getValue();
            if ((scanDetail.getSeen() + delay) < milli) {
                iter.remove();
            }
        }
    }


    void averageRssiAndRemoveFromCache(ScanResult result) {
        // Fetch the previous instance for this result
        ScanDetail sd = scanResultCache.get(result.BSSID);
        if (sd != null) {
            ScanResult sr = sd.getScanResult();
            if (mWifiConfigStore.scanResultRssiLevelPatchUp != 0
                    && result.level == 0
                    && sr.level < -20) {
                // A 'zero' RSSI reading is most likely a chip problem which returns
                // an unknown RSSI, hence ignore it
                result.level = sr.level;
            }

            // If there was a previous cache result for this BSSID, average the RSSI values
            result.averageRssi(sr.level, sr.seen, mScanResultMaximumAge);

            // Remove the previous Scan Result - this is not necessary
            scanResultCache.remove(result.BSSID);
        } else if (mWifiConfigStore.scanResultRssiLevelPatchUp != 0 && result.level == 0) {
            // A 'zero' RSSI reading is most likely a chip problem which returns
            // an unknown RSSI, hence initialize it to a sane value
            result.level = mWifiConfigStore.scanResultRssiLevelPatchUp;
        }
    }

    void addToUnscoredNetworks(ScanResult result, List<NetworkKey> unknownScanResults) {
        WifiKey wkey;
        // Quoted SSIDs are the only one valid at this stage
        try {
            wkey = new WifiKey("\"" + result.SSID + "\"", result.BSSID);
        } catch (IllegalArgumentException e) {
            logDbg("AutoJoinController: received badly encoded SSID=[" + result.SSID +
                    "] ->skipping this network");
            wkey = null;
        }
        if (wkey != null) {
            NetworkKey nkey = new NetworkKey(wkey);
            //if we don't know this scan result then request a score from the scorer
            unknownScanResults.add(nkey);
        }
        if (VDBG) {
            String cap = "";
            if (result.capabilities != null)
                cap = result.capabilities;
            logDbg(result.SSID + " " + result.BSSID + " rssi="
                    + result.level + " cap " + cap + " tsf " + result.timestamp + " is not scored");
        }
    }

    int addToScanCache(List<ScanDetail> scanList) {
        int numScanResultsKnown = 0; // Record number of scan results we knew about
        WifiConfiguration associatedConfig = null;
        boolean didAssociate = false;
        long now = System.currentTimeMillis();

        ArrayList<NetworkKey> unknownScanResults = new ArrayList<NetworkKey>();

        for (ScanDetail scanDetail : scanList) {
            ScanResult result = scanDetail.getScanResult();
            if (result.SSID == null) continue;

            if (VDBG) {
                logDbg(" addToScanCache " + result.SSID + " " + result.BSSID
                        + " tsf=" + result.timestamp
                        + " now= " + now + " uptime=" + SystemClock.uptimeMillis()
                        + " elapsed=" + SystemClock.elapsedRealtime());
            }

            // Make sure we record the last time we saw this result
            scanDetail.setSeen();

            averageRssiAndRemoveFromCache(result);

            if (!mNetworkScoreCache.isScoredNetwork(result)) {
                addToUnscoredNetworks(result, unknownScanResults);
            } else {
                if (VDBG) {
                    String cap = "";
                    if (result.capabilities != null)
                        cap = result.capabilities;
                    int score = mNetworkScoreCache.getNetworkScore(result);
                    logDbg(result.SSID + " " + result.BSSID + " rssi="
                            + result.level + " cap " + cap + " is scored : " + score);
                }
            }

            // scanResultCache.put(result.BSSID, new ScanResult(result));
            scanResultCache.put(result.BSSID, scanDetail);
            // Add this BSSID to the scanResultCache of a Saved WifiConfiguration
            didAssociate = mWifiConfigStore.updateSavedNetworkHistory(scanDetail);

            // If not successful, try to associate this BSSID to an existing Saved WifiConfiguration
            if (!didAssociate) {
                // We couldn't associate the scan result to a Saved WifiConfiguration
                // Hence it is untrusted
                result.untrusted = true;
            } else {
                // If the scan result has been blacklisted fir 18 hours -> unblacklist
                if ((now - result.blackListTimestamp) > loseBlackListHardMilli) {
                    result.setAutoJoinStatus(ScanResult.ENABLED);
                }
            }
            if (didAssociate) {
                numScanResultsKnown++;
                result.isAutoJoinCandidate++;
            } else {
                result.isAutoJoinCandidate = 0;
            }
        }

        if (unknownScanResults.size() != 0) {
            NetworkKey[] newKeys =
                    unknownScanResults.toArray(new NetworkKey[unknownScanResults.size()]);
            // Kick the score manager, we will get updated scores asynchronously
            scoreManager.requestScores(newKeys);
        }
        return numScanResultsKnown;
    }

    void logDbg(String message) {
        logDbg(message, false);
    }

    void logDbg(String message, boolean stackTrace) {
        if (stackTrace) {
            Log.d(TAG, message + " stack:"
                    + Thread.currentThread().getStackTrace()[2].getMethodName() + " - "
                    + Thread.currentThread().getStackTrace()[3].getMethodName() + " - "
                    + Thread.currentThread().getStackTrace()[4].getMethodName() + " - "
                    + Thread.currentThread().getStackTrace()[5].getMethodName());
        } else {
            Log.d(TAG, message);
        }
    }

    // Called directly from WifiStateMachine
    int newSupplicantResults(boolean doAutoJoin) {
        int numScanResultsKnown;
        List<ScanDetail> scanList = mWifiStateMachine.getScanResultsListNoCopyUnsync();
        numScanResultsKnown = addToScanCache(scanList);
        ageScanResultsOut(mScanResultMaximumAge);
        if (DBG) {
            logDbg("newSupplicantResults size=" + Integer.valueOf(scanResultCache.size())
                    + " known=" + numScanResultsKnown + " "
                    + doAutoJoin);
        }
        if (doAutoJoin) {
            attemptAutoJoin();
        }
        mWifiConfigStore.writeKnownNetworkHistory(false);
        return numScanResultsKnown;
    }


    /**
     * Not used at the moment
     * should be a call back from WifiScanner HAL ??
     * this function is not hooked and working yet, it will receive scan results from WifiScanners
     * with the list of IEs,then populate the capabilities by parsing the IEs and inject the scan
     * results as normal.
     */
    void newHalScanResults() {
        List<ScanDetail> scanList = null;//mWifiScanner.syncGetScanResultsList();
        String akm = WifiParser.parse_akm(null, null);
        logDbg(akm);
        addToScanCache(scanList);
        ageScanResultsOut(0);
        attemptAutoJoin();
        mWifiConfigStore.writeKnownNetworkHistory(false);
    }

    /**
     * network link quality changed, called directly from WifiTrafficPoller,
     * or by listening to Link Quality intent
     */
    void linkQualitySignificantChange() {
        attemptAutoJoin();
    }

    /**
     * compare a WifiConfiguration against the current network, return a delta score
     * If not associated, and the candidate will always be better
     * For instance if the candidate is a home network versus an unknown public wifi,
     * the delta will be infinite, else compare Kepler scores etc…
     * Negatve return values from this functions are meaningless per se, just trying to
     * keep them distinct for debug purpose (i.e. -1, -2 etc...)
     */
    private int compareNetwork(WifiConfiguration candidate,
                               String lastSelectedConfiguration) {
        if (candidate == null)
            return -3;

        WifiConfiguration currentNetwork = mWifiStateMachine.getCurrentWifiConfiguration();
        if (currentNetwork == null) {
            // Return any absurdly high score, if we are not connected there is no current
            // network to...
            return 1000;
        }

        if (candidate.configKey(true).equals(currentNetwork.configKey(true))) {
            return -2;
        }

        if (DBG) {
            logDbg("compareNetwork will compare " + candidate.configKey()
                    + " with current " + currentNetwork.configKey());
        }
        int order = compareWifiConfigurations(currentNetwork, candidate);

        // The lastSelectedConfiguration is the configuration the user has manually selected
        // thru WifiPicker, or that a 3rd party app asked us to connect to via the
        // enableNetwork with disableOthers=true WifiManager API
        // As this is a direct user choice, we strongly prefer this configuration,
        // hence give +/-100
        if ((lastSelectedConfiguration != null)
                && currentNetwork.configKey().equals(lastSelectedConfiguration)) {
            // currentNetwork is the last selected configuration,
            // so keep it above connect choices (+/-60) and
            // above RSSI/scorer based selection of linked configuration (+/- 50)
            // by reducing order by -100
            order = order - 100;
            if (VDBG) {
                logDbg("     ...and prefers -100 " + currentNetwork.configKey()
                        + " over " + candidate.configKey()
                        + " because it is the last selected -> "
                        + Integer.toString(order));
            }
        } else if ((lastSelectedConfiguration != null)
                && candidate.configKey().equals(lastSelectedConfiguration)) {
            // candidate is the last selected configuration,
            // so keep it above connect choices (+/-60) and
            // above RSSI/scorer based selection of linked configuration (+/- 50)
            // by increasing order by +100
            order = order + 100;
            if (VDBG) {
                logDbg("     ...and prefers +100 " + candidate.configKey()
                        + " over " + currentNetwork.configKey()
                        + " because it is the last selected -> "
                        + Integer.toString(order));
            }
        }

        return order;
    }

    /**
     * update the network history fields fo that configuration
     * - if userTriggered, we mark the configuration as "non selfAdded" since the user has seen it
     * and took over management
     * - if it is a "connect", remember which network were there at the point of the connect, so
     * as those networks get a relative lower score than the selected configuration
     *
     * @param netId
     * @param userTriggered : if the update come from WiFiManager
     * @param connect       : if the update includes a connect
     */
    public void updateConfigurationHistory(int netId, boolean userTriggered, boolean connect) {
        WifiConfiguration selected = mWifiConfigStore.getWifiConfiguration(netId);
        if (selected == null) {
            logDbg("updateConfigurationHistory nid=" + netId + " no selected configuration!");
            return;
        }

        if (selected.SSID == null) {
            logDbg("updateConfigurationHistory nid=" + netId +
                    " no SSID in selected configuration!");
            return;
        }

        if (userTriggered) {
            // Reenable autojoin for this network,
            // since the user want to connect to this configuration
            selected.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
            selected.selfAdded = false;
            selected.dirty = true;
        }

        if (DBG && userTriggered) {
            if (selected.connectChoices != null) {
                logDbg("updateConfigurationHistory will update "
                        + Integer.toString(netId) + " now: "
                        + Integer.toString(selected.connectChoices.size())
                        + " uid=" + Integer.toString(selected.creatorUid), true);
            } else {
                logDbg("updateConfigurationHistory will update "
                        + Integer.toString(netId)
                        + " uid=" + Integer.toString(selected.creatorUid), true);
            }
        }

        if (connect && userTriggered) {
            boolean found = false;
            int choice = 0;
            int size = 0;

            // Reset the triggered disabled count, because user wanted to connect to this
            // configuration, and we were not.
            selected.numUserTriggeredWifiDisableLowRSSI = 0;
            selected.numUserTriggeredWifiDisableBadRSSI = 0;
            selected.numUserTriggeredWifiDisableNotHighRSSI = 0;
            selected.numUserTriggeredJoinAttempts++;

            List<WifiConfiguration> networks =
                    mWifiConfigStore.getRecentConfiguredNetworks(12000, false);
            if (networks != null) size = networks.size();
            logDbg("updateConfigurationHistory found " + size + " networks");
            if (networks != null) {
                for (WifiConfiguration config : networks) {
                    if (DBG) {
                        logDbg("updateConfigurationHistory got " + config.SSID + " nid="
                                + Integer.toString(config.networkId));
                    }

                    if (selected.configKey(true).equals(config.configKey(true))) {
                        found = true;
                        continue;
                    }

                    // If the selection was made while config was visible with reasonably good RSSI
                    // then register the user preference, else ignore
                    if (config.visibility == null ||
                            (config.visibility.rssi24 < mWifiConfigStore.thresholdBadRssi24.get()
                            && config.visibility.rssi5 < mWifiConfigStore.thresholdBadRssi5.get())
                    ) {
                        continue;
                    }

                    choice = MAX_RSSI_DELTA + 10; // Make sure the choice overrides the RSSI diff

                    // The selected configuration was preferred over a recently seen config
                    // hence remember the user's choice:
                    // add the recently seen config to the selected's connectChoices array

                    if (selected.connectChoices == null) {
                        selected.connectChoices = new HashMap<String, Integer>();
                    }

                    logDbg("updateConfigurationHistory add a choice " + selected.configKey(true)
                            + " over " + config.configKey(true)
                            + " choice " + Integer.toString(choice));

                    // Add the visible config to the selected's connect choice list
                    selected.connectChoices.put(config.configKey(true), choice);

                    if (config.connectChoices != null) {
                        if (VDBG) {
                            logDbg("updateConfigurationHistory will remove "
                                    + selected.configKey(true) + " from " + config.configKey(true));
                        }
                        // Remove the selected from the recently seen config's connectChoice list
                        config.connectChoices.remove(selected.configKey(true));

                        if (selected.linkedConfigurations != null) {
                            // Remove the selected's linked configuration from the
                            // recently seen config's connectChoice list
                            for (String key : selected.linkedConfigurations.keySet()) {
                                config.connectChoices.remove(key);
                            }
                        }
                    }
                }
                if (found == false) {
                    // We haven't found the configuration that the user just selected in our
                    // scan cache.
                    // In that case we will need a new scan before attempting to connect to this
                    // configuration anyhow and thus we can process the scan results then.
                    logDbg("updateConfigurationHistory try to connect to an old network!! : "
                            + selected.configKey());
                }

                if (selected.connectChoices != null) {
                    if (VDBG)
                        logDbg("updateConfigurationHistory " + Integer.toString(netId)
                                + " now: " + Integer.toString(selected.connectChoices.size()));
                }
            }
        }

        // TODO: write only if something changed
        if (userTriggered || connect) {
            mWifiConfigStore.writeKnownNetworkHistory(false);
        }
    }

    int getConnectChoice(WifiConfiguration source, WifiConfiguration target, boolean strict) {
        int choice = 0;
        if (source == null || target == null) {
            return 0;
        }

        if (source.connectChoices != null
                && source.connectChoices.containsKey(target.configKey(true))) {
            Integer val = source.connectChoices.get(target.configKey(true));
            if (val != null) {
                choice = val;
            }
        } else if (source.linkedConfigurations != null) {
            for (String key : source.linkedConfigurations.keySet()) {
                WifiConfiguration config = mWifiConfigStore.getWifiConfiguration(key);
                if (config != null) {
                    if (config.connectChoices != null) {
                        Integer val = config.connectChoices.get(target.configKey(true));
                        if (val != null) {
                            choice = val;
                        }
                    }
                }
            }
        }

        if (!strict && choice == 0) {
            // We didn't find the connect choice; fallback to some default choices
            int sourceScore = getSecurityScore(source);
            int targetScore = getSecurityScore(target);
            choice = sourceScore - targetScore;
        }

        return choice;
    }

    int compareWifiConfigurationsFromVisibility(WifiConfiguration.Visibility a, int aRssiBoost,
                                                String dbgA, WifiConfiguration.Visibility b, int bRssiBoost, String dbgB) {

        int aRssiBoost5 = 0; // 5GHz RSSI boost to apply for purpose band selection (5GHz pref)
        int bRssiBoost5 = 0; // 5GHz RSSI boost to apply for purpose band selection (5GHz pref)

        int aScore = 0;
        int bScore = 0;

        boolean aPrefers5GHz = false;
        boolean bPrefers5GHz = false;

        /**
         * Calculate a boost to apply to RSSI value of configuration we want to join on 5GHz:
         * Boost RSSI value of 5GHz bands iff the base value is better than threshold,
         * penalize the RSSI value of 5GHz band iff the base value is lower than threshold
         * This implements band preference where we prefer 5GHz if RSSI5 is good enough, whereas
         * we prefer 2.4GHz otherwise.
         */
        aRssiBoost5 = rssiBoostFrom5GHzRssi(a.rssi5, dbgA + "->");
        bRssiBoost5 = rssiBoostFrom5GHzRssi(b.rssi5, dbgB + "->");

        // Select which band to use for a
        if (a.rssi5 + aRssiBoost5 > a.rssi24) {
            // Prefer a's 5GHz
            aPrefers5GHz = true;
        }

        // Select which band to use for b
        if (b.rssi5 + bRssiBoost5 > b.rssi24) {
            // Prefer b's 5GHz
            bPrefers5GHz = true;
        }

        if (aPrefers5GHz) {
            if (bPrefers5GHz) {
                // If both a and b are on 5GHz then we don't apply the 5GHz RSSI boost to either
                // one, but directly compare the RSSI values, this improves stability,
                // since the 5GHz RSSI boost can introduce large fluctuations
                aScore = a.rssi5 + aRssiBoost;
            } else {
                // If only a is on 5GHz, then apply the 5GHz preference boost to a
                aScore = a.rssi5 + aRssiBoost + aRssiBoost5;
            }
        } else {
            aScore = a.rssi24 + aRssiBoost;
        }

        if (bPrefers5GHz) {
            if (aPrefers5GHz) {
                // If both a and b are on 5GHz then we don't apply the 5GHz RSSI boost to either
                // one, but directly compare the RSSI values, this improves stability,
                // since the 5GHz RSSI boost can introduce large fluctuations
                bScore = b.rssi5 + bRssiBoost;
            } else {
                // If only b is on 5GHz, then apply the 5GHz preference boost to b
                bScore = b.rssi5 + bRssiBoost + bRssiBoost5;
            }
        } else {
            bScore = b.rssi24 + bRssiBoost;
        }

        if (VDBG) {
            logDbg("        " + dbgA + " is5=" + aPrefers5GHz + " score=" + aScore
                    + " " + dbgB + " is5=" + bPrefers5GHz + " score=" + bScore);
        }

        // Debug only, record RSSI comparison parameters
        if (a != null) {
            a.score = aScore;
            a.currentNetworkBoost = aRssiBoost;
            a.bandPreferenceBoost = aRssiBoost5;
        }
        if (b != null) {
            b.score = bScore;
            b.currentNetworkBoost = bRssiBoost;
            b.bandPreferenceBoost = bRssiBoost5;
        }

        // Compare a and b
        // If a score is higher then a > b and the order is descending (negative)
        // If b score is higher then a < b and the order is ascending (positive)
        return bScore - aScore;
    }

    // Compare WifiConfiguration by RSSI, and return a comparison value in the range [-50, +50]
    // The result represents "approximately" an RSSI difference measured in dBM
    // Adjusted with various parameters:
    // +) current network gets a +15 boost
    // +) 5GHz signal, if they are strong enough, get a +15 or +25 boost, representing the
    // fact that at short range we prefer 5GHz band as it is cleaner of interference and
    // provides for wider channels
    int compareWifiConfigurationsRSSI(WifiConfiguration a, WifiConfiguration b,
                                      String currentConfiguration) {
        int order = 0;

        // Boost used so as to favor current config
        int aRssiBoost = 0;
        int bRssiBoost = 0;

        // Retrieve the visibility
        WifiConfiguration.Visibility astatus = a.visibility;
        WifiConfiguration.Visibility bstatus = b.visibility;
        if (astatus == null || bstatus == null) {
            // Error visibility wasn't set
            logDbg("    compareWifiConfigurations NULL band status!");
            return 0;
        }

        // Apply Hysteresis, boost RSSI of current configuration
        if (null != currentConfiguration) {
            if (a.configKey().equals(currentConfiguration)) {
                aRssiBoost = mWifiConfigStore.currentNetworkBoost;
            } else if (b.configKey().equals(currentConfiguration)) {
                bRssiBoost = mWifiConfigStore.currentNetworkBoost;
            }
        }

        if (VDBG) {
            logDbg("    compareWifiConfigurationsRSSI: " + a.configKey()
                            + " rssi=" + Integer.toString(astatus.rssi24)
                            + "," + Integer.toString(astatus.rssi5)
                            + " boost=" + Integer.toString(aRssiBoost)
                            + " " + b.configKey() + " rssi="
                            + Integer.toString(bstatus.rssi24) + ","
                            + Integer.toString(bstatus.rssi5)
                            + " boost=" + Integer.toString(bRssiBoost)
            );
        }

        order = compareWifiConfigurationsFromVisibility(
                a.visibility, aRssiBoost, a.configKey(),
                b.visibility, bRssiBoost, b.configKey());

        // Normalize the order to [-50, +50] = [ -MAX_RSSI_DELTA, MAX_RSSI_DELTA]
        if (order > MAX_RSSI_DELTA) order = MAX_RSSI_DELTA;
        else if (order < -MAX_RSSI_DELTA) order = -MAX_RSSI_DELTA;

        if (VDBG) {
            String prefer = " = ";
            if (order > 0) {
                prefer = " < "; // Ascending
            } else if (order < 0) {
                prefer = " > "; // Descending
            }
            logDbg("    compareWifiConfigurationsRSSI " + a.configKey()
                    + " rssi=(" + a.visibility.rssi24
                    + "," + a.visibility.rssi5
                    + ") num=(" + a.visibility.num24
                    + "," + a.visibility.num5 + ")"
                    + prefer + b.configKey()
                    + " rssi=(" + b.visibility.rssi24
                    + "," + b.visibility.rssi5
                    + ") num=(" + b.visibility.num24
                    + "," + b.visibility.num5 + ")"
                    + " -> " + order);
        }

        return order;
    }

    /**
     * b/18490330 only use scorer for untrusted networks
     * <p/>
     * int compareWifiConfigurationsWithScorer(WifiConfiguration a, WifiConfiguration b) {
     * <p/>
     * boolean aIsActive = false;
     * boolean bIsActive = false;
     * <p/>
     * // Apply Hysteresis : boost RSSI of current configuration before
     * // looking up the score
     * if (null != mCurrentConfigurationKey) {
     * if (a.configKey().equals(mCurrentConfigurationKey)) {
     * aIsActive = true;
     * } else if (b.configKey().equals(mCurrentConfigurationKey)) {
     * bIsActive = true;
     * }
     * }
     * int scoreA = getConfigNetworkScore(a, mScanResultAutoJoinAge, aIsActive);
     * int scoreB = getConfigNetworkScore(b, mScanResultAutoJoinAge, bIsActive);
     * <p/>
     * // Both configurations need to have a score for the scorer to be used
     * // ...and the scores need to be different:-)
     * if (scoreA == WifiNetworkScoreCache.INVALID_NETWORK_SCORE
     * || scoreB == WifiNetworkScoreCache.INVALID_NETWORK_SCORE) {
     * if (VDBG)  {
     * logDbg("    compareWifiConfigurationsWithScorer no-scores: "
     * + a.configKey()
     * + " "
     * + b.configKey());
     * }
     * return 0;
     * }
     * <p/>
     * if (VDBG) {
     * String prefer = " = ";
     * if (scoreA < scoreB) {
     * prefer = " < ";
     * } if (scoreA > scoreB) {
     * prefer = " > ";
     * }
     * logDbg("    compareWifiConfigurationsWithScorer " + a.configKey()
     * + " rssi=(" + a.visibility.rssi24
     * + "," + a.visibility.rssi5
     * + ") num=(" + a.visibility.num24
     * + "," + a.visibility.num5 + ")"
     * + " sc=" + scoreA
     * + prefer + b.configKey()
     * + " rssi=(" + b.visibility.rssi24
     * + "," + b.visibility.rssi5
     * + ") num=(" + b.visibility.num24
     * + "," + b.visibility.num5 + ")"
     * + " sc=" + scoreB
     * + " -> " + Integer.toString(scoreB - scoreA));
     * }
     * <p/>
     * // If scoreA > scoreB, the comparison is descending hence the return value is negative
     * return scoreB - scoreA;
     * }
     */

    int getSecurityScore(WifiConfiguration config) {

        if (TextUtils.isEmpty(config.SSID) == false) {
            if (config.allowedKeyManagement.get(KeyMgmt.WPA_EAP)
                    || config.allowedKeyManagement.get(KeyMgmt.WPA_PSK)
                    || config.allowedKeyManagement.get(KeyMgmt.WPA2_PSK)) {
                /* enterprise or PSK networks get highest score */
                return 100;
            } else if (config.allowedKeyManagement.get(KeyMgmt.NONE)) {
                /* open networks have lowest score */
                return 33;
            }
        } else if (TextUtils.isEmpty(config.FQDN) == false) {
            /* passpoint networks have medium preference */
            return 66;
        }

        /* bad network */
        return 0;
    }

    int compareWifiConfigurations(WifiConfiguration a, WifiConfiguration b) {
        int order = 0;
        boolean linked = false;

        if ((a.linkedConfigurations != null) && (b.linkedConfigurations != null)
                && (a.autoJoinStatus == WifiConfiguration.AUTO_JOIN_ENABLED)
                && (b.autoJoinStatus == WifiConfiguration.AUTO_JOIN_ENABLED)) {
            if ((a.linkedConfigurations.get(b.configKey(true)) != null)
                    && (b.linkedConfigurations.get(a.configKey(true)) != null)) {
                linked = true;
            }
        }

        if (a.ephemeral && b.ephemeral == false) {
            if (VDBG) {
                logDbg("    compareWifiConfigurations ephemeral and prefers " + b.configKey()
                        + " over " + a.configKey());
            }
            return 1; // b is of higher priority - ascending
        }
        if (b.ephemeral && a.ephemeral == false) {
            if (VDBG) {
                logDbg("    compareWifiConfigurations ephemeral and prefers " + a.configKey()
                        + " over " + b.configKey());
            }
            return -1; // a is of higher priority - descending
        }

        // Apply RSSI, in the range [-5, +5]
        // after band adjustment, +n difference roughly corresponds to +10xn dBm
        order = order + compareWifiConfigurationsRSSI(a, b, mCurrentConfigurationKey);

        // If the configurations are not linked, compare by user's choice, only a
        // very high RSSI difference can then override the choice
        if (!linked) {
            int choice;

            choice = getConnectChoice(a, b, false);
            if (choice > 0) {
                // a is of higher priority - descending
                order = order - choice;
                if (VDBG) {
                    logDbg("    compareWifiConfigurations prefers " + a.configKey()
                            + " over " + b.configKey()
                            + " due to user choice of " + choice
                            + " order -> " + Integer.toString(order));
                }
                if (a.visibility != null) {
                    a.visibility.lastChoiceBoost = choice;
                    a.visibility.lastChoiceConfig = b.configKey();
                }
            }

            choice = getConnectChoice(b, a, false);
            if (choice > 0) {
                // a is of lower priority - ascending
                order = order + choice;
                if (VDBG) {
                    logDbg("    compareWifiConfigurations prefers " + b.configKey() + " over "
                            + a.configKey() + " due to user choice of " + choice
                            + " order ->" + Integer.toString(order));
                }
                if (b.visibility != null) {
                    b.visibility.lastChoiceBoost = choice;
                    b.visibility.lastChoiceConfig = a.configKey();
                }
            }
        }

        if (order == 0) {
            // We don't know anything - pick the last seen i.e. K behavior
            // we should do this only for recently picked configurations
            if (a.priority > b.priority) {
                // a is of higher priority - descending
                if (VDBG) {
                    logDbg("    compareWifiConfigurations prefers -1 " + a.configKey() + " over "
                            + b.configKey() + " due to priority");
                }

                order = -1;
            } else if (a.priority < b.priority) {
                // a is of lower priority - ascending
                if (VDBG) {
                    logDbg("    compareWifiConfigurations prefers +1 " + b.configKey() + " over "
                            + a.configKey() + " due to priority");
                }
                order = 1;
            }
        }

        String sorder = " == ";
        if (order > 0) {
            sorder = " < ";
        } else if (order < 0) {
            sorder = " > ";
        }

        if (VDBG) {
            logDbg("compareWifiConfigurations: " + a.configKey() + sorder
                    + b.configKey() + " order " + Integer.toString(order));
        }

        return order;
    }

    boolean isBadCandidate(int rssi5, int rssi24) {
        return (rssi5 < -80 && rssi24 < -90);
    }

    /*
    int compareWifiConfigurationsTop(WifiConfiguration a, WifiConfiguration b) {
        int scorerOrder = compareWifiConfigurationsWithScorer(a, b);
        int order = compareWifiConfigurations(a, b);

        if (scorerOrder * order < 0) {
            if (VDBG) {
                logDbg("    -> compareWifiConfigurationsTop: " +
                        "scorer override " + scorerOrder + " " + order);
            }
            // For debugging purpose, remember that an override happened
            // during that autojoin Attempt
            didOverride = true;
            a.numScorerOverride++;
            b.numScorerOverride++;
        }

        if (scorerOrder != 0) {
            // If the scorer came up with a result then use the scorer's result, else use
            // the order provided by the base comparison function
            order = scorerOrder;
        }
        return order;
    }
    */

    public int rssiBoostFrom5GHzRssi(int rssi, String dbg) {
        if (!mWifiConfigStore.enable5GHzPreference) {
            return 0;
        }
        if (rssi
                > mWifiConfigStore.bandPreferenceBoostThreshold5.get()) {
            // Boost by 2 dB for each point
            //    Start boosting at -65
            //    Boost by 20 if above -55
            //    Boost by 40 if abore -45
            int boost = mWifiConfigStore.bandPreferenceBoostFactor5
                    * (rssi - mWifiConfigStore.bandPreferenceBoostThreshold5.get());
            if (boost > 50) {
                // 50 dB boost allows jumping from 2.4 to 5GHz
                // consistently
                boost = 50;
            }
            if (VDBG && dbg != null) {
                logDbg("        " + dbg + ":    rssi5 " + rssi + " 5GHz-boost " + boost);
            }
            return boost;
        }

        if (rssi
                < mWifiConfigStore.bandPreferencePenaltyThreshold5.get()) {
            // penalize if < -75
            int boost = mWifiConfigStore.bandPreferencePenaltyFactor5
                    * (rssi - mWifiConfigStore.bandPreferencePenaltyThreshold5.get());
            return boost;
        }
        return 0;
    }

    /**
     * attemptRoam() function implements the core of the same SSID switching algorithm
     * <p/>
     * Run thru all recent scan result of a WifiConfiguration and select the
     * best one.
     */
    public ScanResult attemptRoam(ScanResult a,
                                  WifiConfiguration current, int age, String currentBSSID) {
        if (current == null) {
            if (VDBG) {
                logDbg("attemptRoam not associated");
            }
            return a;
        }

        ScanDetailCache scanDetailCache =
                mWifiConfigStore.getScanDetailCache(current);

        if (scanDetailCache == null) {
            if (VDBG) {
                logDbg("attemptRoam no scan cache");
            }
            return a;
        }
        if (scanDetailCache.size() > 6) {
            if (VDBG) {
                logDbg("attemptRoam scan cache size "
                        + scanDetailCache.size() + " --> bail");
            }
            // Implement same SSID roaming only for configurations
            // that have less than 4 BSSIDs
            return a;
        }

        if (current.BSSID != null && !current.BSSID.equals("any")) {
            if (DBG) {
                logDbg("attemptRoam() BSSID is set "
                        + current.BSSID + " -> bail");
            }
            return a;
        }

        // Determine which BSSID we want to associate to, taking account
        // relative strength of 5 and 2.4 GHz BSSIDs
        long nowMs = System.currentTimeMillis();

        for (ScanDetail sd : scanDetailCache.values()) {
            ScanResult b = sd.getScanResult();
            int bRssiBoost5 = 0;
            int aRssiBoost5 = 0;
            int bRssiBoost = 0;
            int aRssiBoost = 0;
            if ((sd.getSeen() == 0) || (b.BSSID == null)
                    || ((nowMs - sd.getSeen()) > age)
                    || b.autoJoinStatus != ScanResult.ENABLED
                    || b.numIpConfigFailures > 8) {
                continue;
            }

            // Pick first one
            if (a == null) {
                a = b;
                continue;
            }

            if (b.numIpConfigFailures < (a.numIpConfigFailures - 1)) {
                // Prefer a BSSID that doesn't have less number of Ip config failures
                logDbg("attemptRoam: "
                        + b.BSSID + " rssi=" + b.level + " ipfail=" + b.numIpConfigFailures
                        + " freq=" + b.frequency
                        + " > "
                        + a.BSSID + " rssi=" + a.level + " ipfail=" + a.numIpConfigFailures
                        + " freq=" + a.frequency);
                a = b;
                continue;
            }

            // Apply hysteresis: we favor the currentBSSID by giving it a boost
            if (currentBSSID != null && currentBSSID.equals(b.BSSID)) {
                // Reduce the benefit of hysteresis if RSSI <= -75
                if (b.level <= mWifiConfigStore.bandPreferencePenaltyThreshold5.get()) {
                    bRssiBoost = mWifiConfigStore.associatedHysteresisLow;
                } else {
                    bRssiBoost = mWifiConfigStore.associatedHysteresisHigh;
                }
            }
            if (currentBSSID != null && currentBSSID.equals(a.BSSID)) {
                if (a.level <= mWifiConfigStore.bandPreferencePenaltyThreshold5.get()) {
                    // Reduce the benefit of hysteresis if RSSI <= -75
                    aRssiBoost = mWifiConfigStore.associatedHysteresisLow;
                } else {
                    aRssiBoost = mWifiConfigStore.associatedHysteresisHigh;
                }
            }

            // Favor 5GHz: give a boost to 5GHz BSSIDs, with a slightly progressive curve
            //   Boost the BSSID if it is on 5GHz, above a threshold
            //   But penalize it if it is on 5GHz and below threshold
            //
            //   With he current threshold values, 5GHz network with RSSI above -55
            //   Are given a boost of 30DB which is enough to overcome the current BSSID
            //   hysteresis (+14) plus 2.4/5 GHz signal strength difference on most cases
            //
            // The "current BSSID" Boost must be added to the BSSID's level so as to introduce\
            // soem amount of hysteresis
            if (b.is5GHz()) {
                bRssiBoost5 = rssiBoostFrom5GHzRssi(b.level + bRssiBoost, b.BSSID);
            }
            if (a.is5GHz()) {
                aRssiBoost5 = rssiBoostFrom5GHzRssi(a.level + aRssiBoost, a.BSSID);
            }

            if (VDBG) {
                String comp = " < ";
                if (b.level + bRssiBoost + bRssiBoost5 > a.level + aRssiBoost + aRssiBoost5) {
                    comp = " > ";
                }
                logDbg("attemptRoam: "
                        + b.BSSID + " rssi=" + b.level + " boost=" + Integer.toString(bRssiBoost)
                        + "/" + Integer.toString(bRssiBoost5) + " freq=" + b.frequency
                        + comp
                        + a.BSSID + " rssi=" + a.level + " boost=" + Integer.toString(aRssiBoost)
                        + "/" + Integer.toString(aRssiBoost5) + " freq=" + a.frequency);
            }

            // Compare the RSSIs after applying the hysteresis boost and the 5GHz
            // boost if applicable
            if (b.level + bRssiBoost + bRssiBoost5 > a.level + aRssiBoost + aRssiBoost5) {
                // b is the better BSSID
                a = b;
            }
        }
        if (a != null) {
            if (VDBG) {
                StringBuilder sb = new StringBuilder();
                sb.append("attemptRoam: " + current.configKey() +
                        " Found " + a.BSSID + " rssi=" + a.level + " freq=" + a.frequency);
                if (currentBSSID != null) {
                    sb.append(" Current: " + currentBSSID);
                }
                sb.append("\n");
                logDbg(sb.toString());
            }
        }
        return a;
    }

    /**
     * getNetworkScore()
     * <p/>
     * if scorer is present, get the network score of a WifiConfiguration
     * <p/>
     * Note: this should be merge with setVisibility
     *
     * @param config
     * @return score
     */
    int getConfigNetworkScore(WifiConfiguration config, int age, boolean isActive) {

        if (mNetworkScoreCache == null) {
            if (VDBG) {
                logDbg("       getConfigNetworkScore for " + config.configKey()
                        + "  -> no scorer, hence no scores");
            }
            return WifiNetworkScoreCache.INVALID_NETWORK_SCORE;
        }

        if (mWifiConfigStore.getScanDetailCache(config) == null) {
            if (VDBG) {
                logDbg("       getConfigNetworkScore for " + config.configKey()
                        + " -> no scan cache");
            }
            return WifiNetworkScoreCache.INVALID_NETWORK_SCORE;
        }

        // Get current date
        long nowMs = System.currentTimeMillis();

        int startScore = -10000;

        // Run thru all cached scan results
        for (ScanDetail sd : mWifiConfigStore.getScanDetailCache(config).values()) {
            ScanResult result = sd.getScanResult();
            if ((nowMs - sd.getSeen()) < age) {
                int sc = mNetworkScoreCache.getNetworkScore(result, isActive);
                if (sc > startScore) {
                    startScore = sc;
                }
            }
        }
        if (startScore == -10000) {
            startScore = WifiNetworkScoreCache.INVALID_NETWORK_SCORE;
        }
        if (VDBG) {
            if (startScore == WifiNetworkScoreCache.INVALID_NETWORK_SCORE) {
                logDbg("    getConfigNetworkScore for " + config.configKey()
                        + " -> no available score");
            } else {
                logDbg("    getConfigNetworkScore for " + config.configKey()
                        + " isActive=" + isActive
                        + " score = " + Integer.toString(startScore));
            }
        }

        return startScore;
    }

    /**
     * Set whether connections to untrusted connections are allowed.
     */
    void setAllowUntrustedConnections(boolean allow) {
        boolean changed = mAllowUntrustedConnections != allow;
        mAllowUntrustedConnections = allow;
        if (changed) {
            // Trigger a scan so as to reattempt autojoin
            mWifiStateMachine.startScanForUntrustedSettingChange();
        }
    }

    private boolean isOpenNetwork(ScanResult result) {
        return !result.capabilities.contains("WEP") &&
                !result.capabilities.contains("PSK") &&
                !result.capabilities.contains("EAP");
    }

    private boolean haveRecentlySeenScoredBssid(WifiConfiguration config) {
        long ephemeralOutOfRangeTimeoutMs = Settings.Global.getLong(
                mContext.getContentResolver(),
                Settings.Global.WIFI_EPHEMERAL_OUT_OF_RANGE_TIMEOUT_MS,
                DEFAULT_EPHEMERAL_OUT_OF_RANGE_TIMEOUT_MS);

        // Check whether the currently selected network has a score curve. If
        // ephemeralOutOfRangeTimeoutMs is <= 0, then this is all we check, and we stop here.
        // Otherwise, we stop here if the currently selected network has a score. If it doesn't, we
        // keep going - it could be that another BSSID is in range (has been seen recently) which
        // has a score, even if the one we're immediately connected to doesn't.
        ScanResult currentScanResult = mWifiStateMachine.getCurrentScanResult();
        boolean currentNetworkHasScoreCurve = currentScanResult != null
                && mNetworkScoreCache.hasScoreCurve(currentScanResult);
        if (ephemeralOutOfRangeTimeoutMs <= 0 || currentNetworkHasScoreCurve) {
            if (DBG) {
                if (currentNetworkHasScoreCurve) {
                    logDbg("Current network has a score curve, keeping network: "
                            + currentScanResult);
                } else {
                    logDbg("Current network has no score curve, giving up: " + config.SSID);
                }
            }
            return currentNetworkHasScoreCurve;
        }

        if (mWifiConfigStore.getScanDetailCache(config) == null
                || mWifiConfigStore.getScanDetailCache(config).isEmpty()) {
            return false;
        }

        long currentTimeMs = System.currentTimeMillis();
        for (ScanDetail sd : mWifiConfigStore.getScanDetailCache(config).values()) {
            ScanResult result = sd.getScanResult();
            if (currentTimeMs > sd.getSeen()
                    && currentTimeMs - sd.getSeen() < ephemeralOutOfRangeTimeoutMs
                    && mNetworkScoreCache.hasScoreCurve(result)) {
                if (DBG) {
                    logDbg("Found scored BSSID, keeping network: " + result.BSSID);
                }
                return true;
            }
        }

        if (DBG) {
            logDbg("No recently scored BSSID found, giving up connection: " + config.SSID);
        }
        return false;
    }

    // After WifiStateMachine ask the supplicant to associate or reconnect
    // we might still obtain scan results from supplicant
    // however the supplicant state in the mWifiInfo and supplicant state tracker
    // are updated when we get the supplicant state change message which can be
    // processed after the SCAN_RESULT message, so at this point the framework doesn't
    // know that supplicant is ASSOCIATING.
    // A good fix for this race condition would be for the WifiStateMachine to add
    // a new transient state where it expects to get the supplicant message indicating
    // that it started the association process and within which critical operations
    // like autojoin should be deleted.

    // This transient state would remove the need for the roam Wathchdog which
    // basically does that.

    // At the moment, we just query the supplicant state synchronously with the
    // mWifiNative.status() command, which allow us to know that
    // supplicant has started association process, even though we didnt yet get the
    // SUPPLICANT_STATE_CHANGE message.

    private static final List<String> ASSOC_STATES = Arrays.asList(
            "ASSOCIATING",
            "ASSOCIATED",
            "FOUR_WAY_HANDSHAKE",
            "GROUP_KEY_HANDSHAKE");

    private int getNetID(String wpaStatus) {
        if (VDBG) {
            logDbg("attemptAutoJoin() status=" + wpaStatus);
        }

        try {
            int id = WifiConfiguration.INVALID_NETWORK_ID;
            String state = null;
            BufferedReader br = new BufferedReader(new StringReader(wpaStatus));
            String line;
            while ((line = br.readLine()) != null) {
                int split = line.indexOf('=');
                if (split < 0) {
                    continue;
                }

                String name = line.substring(0, split);
                if (name.equals("id")) {
                    try {
                        id = Integer.parseInt(line.substring(split + 1));
                        if (state != null) {
                            break;
                        }
                    } catch (NumberFormatException nfe) {
                        return WifiConfiguration.INVALID_NETWORK_ID;
                    }
                } else if (name.equals("wpa_state")) {
                    state = line.substring(split + 1);
                    if (ASSOC_STATES.contains(state)) {
                        return WifiConfiguration.INVALID_NETWORK_ID;
                    } else if (id >= 0) {
                        break;
                    }
                }
            }
            return id;
        } catch (IOException ioe) {
            return WifiConfiguration.INVALID_NETWORK_ID;    // Won't happen
        }
    }

    private boolean setCurrentConfigurationKey(WifiConfiguration currentConfig,
                                               int supplicantNetId) {
        if (currentConfig != null) {
            if (supplicantNetId != currentConfig.networkId
                    // https://b.corp.google.com/issue?id=16484607
                    // mark this condition as an error only if the mismatched networkId are valid
                    && supplicantNetId != WifiConfiguration.INVALID_NETWORK_ID
                    && currentConfig.networkId != WifiConfiguration.INVALID_NETWORK_ID) {
                logDbg("attemptAutoJoin() ERROR wpa_supplicant out of sync nid="
                        + Integer.toString(supplicantNetId) + " WifiStateMachine="
                        + Integer.toString(currentConfig.networkId));
                mWifiStateMachine.disconnectCommand();
                return false;
            } else if (currentConfig.ephemeral && (!mAllowUntrustedConnections ||
                    !haveRecentlySeenScoredBssid(currentConfig))) {
                // The current connection is untrusted (the framework added it), but we're either
                // no longer allowed to connect to such networks, the score has been nullified
                // since we connected, or the scored BSSID has gone out of range.
                // Drop the current connection and perform the rest of autojoin.
                logDbg("attemptAutoJoin() disconnecting from unwanted ephemeral network");
                mWifiStateMachine.disconnectCommand(Process.WIFI_UID,
                        mAllowUntrustedConnections ? 1 : 0);
                return false;
            } else {
                mCurrentConfigurationKey = currentConfig.configKey();
                return true;
            }
        } else {
            // If not invalid, then maybe in the process of associating, skip this attempt
            return supplicantNetId == WifiConfiguration.INVALID_NETWORK_ID;
        }
    }

    private void updateBlackListStatus(WifiConfiguration config, long now) {
        // Wait for 5 minutes before reenabling config that have known,
        // repeated connection or DHCP failures
        if (config.disableReason == WifiConfiguration.DISABLED_DHCP_FAILURE
                || config.disableReason
                == WifiConfiguration.DISABLED_ASSOCIATION_REJECT
                || config.disableReason
                == WifiConfiguration.DISABLED_AUTH_FAILURE) {
            if (config.blackListTimestamp == 0
                    || (config.blackListTimestamp > now)) {
                // Sanitize the timestamp
                config.blackListTimestamp = now;
            }
            if ((now - config.blackListTimestamp) >
                    mWifiConfigStore.wifiConfigBlacklistMinTimeMilli) {
                // Re-enable the WifiConfiguration
                config.status = WifiConfiguration.Status.ENABLED;

                // Reset the blacklist condition
                config.numConnectionFailures = 0;
                config.numIpConfigFailures = 0;
                config.numAuthFailures = 0;
                config.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);

                config.dirty = true;
            } else {
                if (VDBG) {
                    long delay = mWifiConfigStore.wifiConfigBlacklistMinTimeMilli
                            - (now - config.blackListTimestamp);
                    logDbg("attemptautoJoin " + config.configKey()
                            + " dont unblacklist yet, waiting for "
                            + delay + " ms");
                }
            }
        }
        // Avoid networks disabled because of AUTH failure altogether
        if (DBG) {
            logDbg("attemptAutoJoin skip candidate due to auto join status "
                    + Integer.toString(config.autoJoinStatus) + " key "
                    + config.configKey(true)
                    + " reason " + config.disableReason);
        }
    }

    boolean underSoftThreshold(WifiConfiguration config) {
        return config.visibility.rssi24 < mWifiConfigStore.thresholdUnblacklistThreshold24Soft.get()
                && config.visibility.rssi5 < mWifiConfigStore.thresholdUnblacklistThreshold5Soft.get();
    }

    boolean underHardThreshold(WifiConfiguration config) {
        return config.visibility.rssi24 < mWifiConfigStore.thresholdUnblacklistThreshold24Hard.get()
                && config.visibility.rssi5 < mWifiConfigStore.thresholdUnblacklistThreshold5Hard.get();
    }

    boolean underThreshold(WifiConfiguration config, int rssi24, int rssi5) {
        return config.visibility.rssi24 < rssi24 && config.visibility.rssi5 < rssi5;
    }

    /**
     * attemptAutoJoin() function implements the core of the a network switching algorithm
     * Return false if no acceptable networks were found.
     */
    boolean attemptAutoJoin() {
        boolean found = false;
        didOverride = false;
        didBailDueToWeakRssi = false;
        int networkSwitchType = AUTO_JOIN_IDLE;
        int age = mScanResultAutoJoinAge;

        long now = System.currentTimeMillis();

        String lastSelectedConfiguration = mWifiConfigStore.getLastSelectedConfiguration();
        if (lastSelectedConfiguration != null) {
            age = 14000;
        }
        // Reset the currentConfiguration Key, and set it only if WifiStateMachine and
        // supplicant agree
        mCurrentConfigurationKey = null;
        WifiConfiguration currentConfiguration = mWifiStateMachine.getCurrentWifiConfiguration();

        WifiConfiguration candidate = null;
        // Obtain the subset of recently seen networks
        List<WifiConfiguration> list =
                mWifiConfigStore.getRecentConfiguredNetworks(age, false);
        if (list == null) {
            if (VDBG) logDbg("attemptAutoJoin nothing known=" +
                    mWifiConfigStore.getConfiguredNetworksSize());
            return false;
        }

        // Find the currently connected network: ask the supplicant directly
        int supplicantNetId = getNetID(mWifiNative.status(true));

        if (DBG) {
            String conf = "";
            String last = "";
            if (currentConfiguration != null) {
                conf = " current=" + currentConfiguration.configKey();
            }
            if (lastSelectedConfiguration != null) {
                last = " last=" + lastSelectedConfiguration;
            }
            logDbg("attemptAutoJoin() num recent config " + Integer.toString(list.size())
                    + conf + last
                    + " ---> suppNetId=" + Integer.toString(supplicantNetId));
        }

        if (!setCurrentConfigurationKey(currentConfiguration, supplicantNetId)) {
            return false;
        }

        int currentNetId = -1;
        if (currentConfiguration != null) {
            // If we are associated to a configuration, it will
            // be compared thru the compareNetwork function
            currentNetId = currentConfiguration.networkId;
        }

        /**
         * Run thru all visible configurations without looking at the one we
         * are currently associated to
         * select Best Network candidate from known WifiConfigurations
         */
        for (WifiConfiguration config : list) {
            if (config.SSID == null) {
                continue;
            }

            if (config.autoJoinStatus >= WifiConfiguration.AUTO_JOIN_DISABLED_ON_AUTH_FAILURE) {
                updateBlackListStatus(config, now);
                continue;
            }

            if (config.userApproved == WifiConfiguration.USER_PENDING ||
                    config.userApproved == WifiConfiguration.USER_BANNED) {
                if (DBG) {
                    logDbg("attemptAutoJoin skip candidate due to user approval status "
                            + WifiConfiguration.userApprovedAsString(config.userApproved) + " key "
                            + config.configKey(true));
                }
                continue;
            }

            // Try to un-blacklist based on elapsed time
            if (config.blackListTimestamp > 0) {
                if (now < config.blackListTimestamp) {
                    /**
                     * looks like there was a change in the system clock since we black listed, and
                     * timestamp is not meaningful anymore, hence lose it.
                     * this event should be rare enough so that we still want to lose the black list
                     */
                    config.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
                } else {
                    if ((now - config.blackListTimestamp) > loseBlackListHardMilli) {
                        // Reenable it after 18 hours, i.e. next day
                        config.setAutoJoinStatus(WifiConfiguration.AUTO_JOIN_ENABLED);
                    } else if ((now - config.blackListTimestamp) > loseBlackListSoftMilli) {
                        // Lose blacklisting due to bad link
                        config.setAutoJoinStatus(config.autoJoinStatus - 8);
                    }
                }
            }

            if (config.visibility == null) {
                continue;
            }

            // Try to unblacklist based on good visibility
            if (underSoftThreshold(config)) {
                if (DBG) {
                    logDbg("attemptAutoJoin do not unblacklist due to low visibility " +
                            config.configKey() + " status=" + config.autoJoinStatus);
                }
            } else if (underHardThreshold(config)) {
                // If the network is simply temporary disabled, don't allow reconnect until
                // RSSI becomes good enough
                config.setAutoJoinStatus(config.autoJoinStatus - 1);
                if (DBG) {
                    logDbg("attemptAutoJoin good candidate seen, bumped soft -> status=" +
                            config.configKey() + " status=" + config.autoJoinStatus);
                }
            } else {
                config.setAutoJoinStatus(config.autoJoinStatus - 3);
                if (DBG) {
                    logDbg("attemptAutoJoin good candidate seen, bumped hard -> status=" +
                            config.configKey() + " status=" + config.autoJoinStatus);
                }
            }

            if (config.autoJoinStatus >=
                    WifiConfiguration.AUTO_JOIN_TEMPORARY_DISABLED) {
                // Network is blacklisted, skip
                if (DBG) {
                    logDbg("attemptAutoJoin skip blacklisted -> status=" +
                            config.configKey() + " status=" + config.autoJoinStatus);
                }
                continue;
            }
            if (config.networkId == currentNetId) {
                if (DBG) {
                    logDbg("attemptAutoJoin skip current candidate  "
                            + Integer.toString(currentNetId)
                            + " key " + config.configKey(true));
                }
                continue;
            }

            boolean isLastSelected = false;
            if (lastSelectedConfiguration != null &&
                    config.configKey().equals(lastSelectedConfiguration)) {
                isLastSelected = true;
            }

            if (config.lastRoamingFailure != 0
                    && currentConfiguration != null
                    && (lastSelectedConfiguration == null
                    || !config.configKey().equals(lastSelectedConfiguration))) {
                // Apply blacklisting for roaming to this config if:
                //   - the target config had a recent roaming failure
                //   - we are currently associated
                //   - the target config is not the last selected
                if (now > config.lastRoamingFailure
                        && (now - config.lastRoamingFailure)
                        < config.roamingFailureBlackListTimeMilli) {
                    if (DBG) {
                        logDbg("compareNetwork not switching to " + config.configKey()
                                + " from current " + currentConfiguration.configKey()
                                + " because it is blacklisted due to roam failure, "
                                + " blacklist remain time = "
                                + (now - config.lastRoamingFailure) + " ms");
                    }
                    continue;
                }
            }

            int boost = config.autoJoinUseAggressiveJoinAttemptThreshold + weakRssiBailCount;
            if (underThreshold(config,
                    mWifiConfigStore.thresholdInitialAutoJoinAttemptMin24RSSI.get() - boost,
                    mWifiConfigStore.thresholdInitialAutoJoinAttemptMin5RSSI.get() - boost)) {

                if (DBG) {
                    logDbg("attemptAutoJoin skip due to low visibility " + config.configKey());
                }

                // Don't try to autojoin a network that is too far but
                // If that configuration is a user's choice however, try anyway
                if (!isLastSelected) {
                    config.autoJoinBailedDueToLowRssi = true;
                    didBailDueToWeakRssi = true;
                    continue;
                } else {
                    // Next time, try to be a bit more aggressive in auto-joining
                    if (config.autoJoinUseAggressiveJoinAttemptThreshold
                            < WifiConfiguration.MAX_INITIAL_AUTO_JOIN_RSSI_BOOST
                            && config.autoJoinBailedDueToLowRssi) {
                        config.autoJoinUseAggressiveJoinAttemptThreshold += 4;
                    }
                }
            }
            // NOTE: If this condition is updated, update NETWORK_STATUS_UNWANTED_DISABLE_AUTOJOIN.
            if (config.numNoInternetAccessReports > 0
                    && !isLastSelected
                    && !config.validatedInternetAccess) {
                // Avoid autoJoining this network because last time we used it, it didn't
                // have internet access, and we never manage to validate internet access on this
                // network configuration
                if (DBG) {
                    logDbg("attemptAutoJoin skip candidate due to no InternetAccess  "
                            + config.configKey(true)
                            + " num reports " + config.numNoInternetAccessReports);
                }
                continue;
            }

            if (DBG) {
                String cur = "";
                if (candidate != null) {
                    cur = " current candidate " + candidate.configKey();
                }
                logDbg("attemptAutoJoin trying id="
                        + Integer.toString(config.networkId) + " "
                        + config.configKey(true)
                        + " status=" + config.autoJoinStatus
                        + cur);
            }

            if (candidate == null) {
                candidate = config;
            } else {
                if (VDBG) {
                    logDbg("attemptAutoJoin will compare candidate  " + candidate.configKey()
                            + " with " + config.configKey());
                }

                int order = compareWifiConfigurations(candidate, config);
                if (VDBG) {
                    logDbg("attemptAutoJoin compareWifiConfigurations returned " + order);
                }

                // The lastSelectedConfiguration is the configuration the user has manually selected
                // thru WifiPicker, or that a 3rd party app asked us to connect to via the
                // enableNetwork with disableOthers=true WifiManager API
                // As this is a direct user choice, we strongly prefer this configuration,
                // hence give +/-100
                if ((lastSelectedConfiguration != null)
                        && candidate.configKey().equals(lastSelectedConfiguration)) {
                    // candidate is the last selected configuration,
                    // so keep it above connect choices (+/-60) and
                    // above RSSI/scorer based selection of linked configuration (+/- 50)
                    // by reducing order by -100
                    order = order - 100;
                    if (VDBG) {
                        logDbg("     ...and prefers -100 " + candidate.configKey()
                                + " over " + config.configKey()
                                + " because it is the last selected -> "
                                + Integer.toString(order));
                    }
                } else if ((lastSelectedConfiguration != null)
                        && config.configKey().equals(lastSelectedConfiguration)) {
                    // config is the last selected configuration,
                    // so keep it above connect choices (+/-60) and
                    // above RSSI/scorer based selection of linked configuration (+/- 50)
                    // by increasing order by +100
                    order = order + 100;
                    if (VDBG) {
                        logDbg("     ...and prefers +100 " + config.configKey()
                                + " over " + candidate.configKey()
                                + " because it is the last selected -> "
                                + Integer.toString(order));
                    }
                }

                if (order > 0) {
                    // Ascending : candidate < config
                    candidate = config;
                }
            }
        }

        // Now, go thru scan result to try finding a better untrusted network
        if (mNetworkScoreCache != null && mAllowUntrustedConnections) {
            int rssi5 = WifiConfiguration.INVALID_RSSI;
            int rssi24 = WifiConfiguration.INVALID_RSSI;
            if (candidate != null) {
                rssi5 = candidate.visibility.rssi5;
                rssi24 = candidate.visibility.rssi24;
            }

            // Get current date
            long nowMs = System.currentTimeMillis();
            int currentScore = -10000;
            // The untrusted network with highest score
            ScanDetail untrustedCandidate = null;
            // Look for untrusted scored network only if the current candidate is bad
            if (isBadCandidate(rssi24, rssi5)) {
                for (ScanDetail scanDetail : scanResultCache.values()) {
                    ScanResult result = scanDetail.getScanResult();
                    // We look only at untrusted networks with a valid SSID
                    // A trusted result would have been looked at thru it's Wificonfiguration
                    if (TextUtils.isEmpty(result.SSID) || !result.untrusted ||
                            !isOpenNetwork(result)) {
                        continue;
                    }
                    String quotedSSID = "\"" + result.SSID + "\"";
                    if (mWifiConfigStore.mDeletedEphemeralSSIDs.contains(quotedSSID)) {
                        // SSID had been Forgotten by user, then don't score it
                        continue;
                    }
                    if ((nowMs - result.seen) < mScanResultAutoJoinAge) {
                        // Increment usage count for the network
                        mWifiConnectionStatistics.incrementOrAddUntrusted(quotedSSID, 0, 1);

                        boolean isActiveNetwork = currentConfiguration != null
                                && currentConfiguration.SSID.equals(quotedSSID);
                        int score = mNetworkScoreCache.getNetworkScore(result, isActiveNetwork);
                        if (score != WifiNetworkScoreCache.INVALID_NETWORK_SCORE
                                && score > currentScore) {
                            // Highest score: Select this candidate
                            currentScore = score;
                            untrustedCandidate = scanDetail;
                            if (VDBG) {
                                logDbg("AutoJoinController: found untrusted candidate "
                                        + result.SSID
                                        + " RSSI=" + result.level
                                        + " freq=" + result.frequency
                                        + " score=" + score);
                            }
                        }
                    }
                }
            }
            if (untrustedCandidate != null) {
                // At this point, we have an untrusted network candidate.
                // Create the new ephemeral configuration and see if we should switch over
                candidate =
                        mWifiConfigStore.wifiConfigurationFromScanResult(untrustedCandidate);
                candidate.allowedKeyManagement.set(KeyMgmt.NONE);
                candidate.ephemeral = true;
                candidate.dirty = true;
            }
        }

        long lastUnwanted =
                System.currentTimeMillis()
                        - mWifiConfigStore.lastUnwantedNetworkDisconnectTimestamp;
        if (candidate == null
                && lastSelectedConfiguration == null
                && currentConfiguration == null
                && didBailDueToWeakRssi
                && (mWifiConfigStore.lastUnwantedNetworkDisconnectTimestamp == 0
                || lastUnwanted > (1000 * 60 * 60 * 24 * 7))
                ) {
            // We are bailing out of autojoin although we are seeing a weak configuration, and
            // - we didn't find another valid candidate
            // - we are not connected
            // - without a user network selection choice
            // - ConnectivityService has not triggered an unwanted network disconnect
            //       on this device for a week (hence most likely there is no SIM card or cellular)
            // If all those conditions are met, then boost the RSSI of the weak networks
            // that we are seeing so as we will eventually pick one
            if (weakRssiBailCount < 10)
                weakRssiBailCount += 1;
        } else {
            if (weakRssiBailCount > 0)
                weakRssiBailCount -= 1;
        }

        /**
         *  If candidate is found, check the state of the connection so as
         *  to decide if we should be acting on this candidate and switching over
         */
        int networkDelta = compareNetwork(candidate, lastSelectedConfiguration);
        if (DBG && candidate != null) {
            String doSwitch = "";
            String current = "";
            if (networkDelta < 0) {
                doSwitch = " -> not switching";
            }
            if (currentConfiguration != null) {
                current = " with current " + currentConfiguration.configKey();
            }
            logDbg("attemptAutoJoin networkSwitching candidate "
                    + candidate.configKey()
                    + current
                    + " linked=" + (currentConfiguration != null
                    && currentConfiguration.isLinked(candidate))
                    + " : delta="
                    + Integer.toString(networkDelta) + " "
                    + doSwitch);
        }

        /**
         * Ask WifiStateMachine permission to switch :
         * if user is currently streaming voice traffic,
         * then we should not be allowed to switch regardless of the delta
         */
        if (mWifiStateMachine.shouldSwitchNetwork(networkDelta)) {      // !!! JNo: Here!
            if (mStaStaSupported) {
                logDbg("mStaStaSupported --> error do nothing now ");
            } else {
                if (currentConfiguration != null && currentConfiguration.isLinked(candidate)) {
                    networkSwitchType = AUTO_JOIN_EXTENDED_ROAMING;
                } else {
                    networkSwitchType = AUTO_JOIN_OUT_OF_NETWORK_ROAMING;
                }
                if (DBG) {
                    logDbg("AutoJoin auto connect with netId "
                            + Integer.toString(candidate.networkId)
                            + " to " + candidate.configKey());
                }
                if (didOverride) {
                    candidate.numScorerOverrideAndSwitchedNetwork++;
                }
                candidate.numAssociation++;
                mWifiConnectionStatistics.numAutoJoinAttempt++;

                if (candidate.ephemeral) {
                    // We found a new candidate that we are going to connect to, then
                    // increase its connection count
                    mWifiConnectionStatistics.
                            incrementOrAddUntrusted(candidate.SSID, 1, 0);
                }

                if (candidate.BSSID == null || candidate.BSSID.equals("any")) {
                    // First step we selected the configuration we want to connect to
                    // Second step: Look for the best Scan result for this configuration
                    // TODO this algorithm should really be done in one step
                    String currentBSSID = mWifiStateMachine.getCurrentBSSID();
                    ScanResult roamCandidate =
                            attemptRoam(null, candidate, mScanResultAutoJoinAge, null);
                    if (roamCandidate != null && currentBSSID != null
                            && currentBSSID.equals(roamCandidate.BSSID)) {
                        // Sanity, we were already asociated to that candidate
                        roamCandidate = null;
                    }
                    if (roamCandidate != null && roamCandidate.is5GHz()) {
                        // If the configuration hasn't a default BSSID selected, and the best
                        // candidate is 5GHZ, then select this candidate so as WifiStateMachine and
                        // supplicant will pick it first
                        candidate.autoJoinBSSID = roamCandidate.BSSID;
                        if (VDBG) {
                            logDbg("AutoJoinController: lock to 5GHz "
                                    + candidate.autoJoinBSSID
                                    + " RSSI=" + roamCandidate.level
                                    + " freq=" + roamCandidate.frequency);
                        }
                    } else {
                        // We couldnt find a roam candidate
                        candidate.autoJoinBSSID = "any";
                    }
                }
                mWifiStateMachine.sendMessage(WifiStateMachine.CMD_AUTO_CONNECT,
                        candidate.networkId, networkSwitchType, candidate);
                found = true;
            }
        }

        if (networkSwitchType == AUTO_JOIN_IDLE && !mWifiConfigStore.enableHalBasedPno.get()) {
            String currentBSSID = mWifiStateMachine.getCurrentBSSID();
            // Attempt same WifiConfiguration roaming
            ScanResult roamCandidate =
                    attemptRoam(null, currentConfiguration, mScanResultAutoJoinAge, currentBSSID);
            if (roamCandidate != null && currentBSSID != null
                    && currentBSSID.equals(roamCandidate.BSSID)) {
                roamCandidate = null;
            }
            if (roamCandidate != null && mWifiStateMachine.shouldSwitchNetwork(999)) {
                if (DBG) {
                    logDbg("AutoJoin auto roam with netId "
                            + Integer.toString(currentConfiguration.networkId)
                            + " " + currentConfiguration.configKey() + " to BSSID="
                            + roamCandidate.BSSID + " freq=" + roamCandidate.frequency
                            + " RSSI=" + roamCandidate.level);
                }
                networkSwitchType = AUTO_JOIN_ROAMING;
                mWifiConnectionStatistics.numAutoRoamAttempt++;

                mWifiStateMachine.sendMessage(WifiStateMachine.CMD_AUTO_ROAM,
                        currentConfiguration.networkId, 1, roamCandidate);
                found = true;
            }
        }
        if (VDBG) logDbg("Done attemptAutoJoin status=" + Integer.toString(networkSwitchType));
        return found;
    }

    private void logDenial(String reason, WifiConfiguration config) {
        if (!DBG) {
            return;
        }
        logDbg(reason + config.toString());
    }

    WifiConfiguration getWifiConfiguration(WifiNative.WifiPnoNetwork network) {
        if (network.configKey != null) {
            return mWifiConfigStore.getWifiConfiguration(network.configKey);
        }
        return null;
    }

    ArrayList<WifiNative.WifiPnoNetwork> getPnoList(WifiConfiguration current) {
        int size = -1;
        ArrayList<WifiNative.WifiPnoNetwork> list = new ArrayList<WifiNative.WifiPnoNetwork>();

        if (mWifiConfigStore.mCachedPnoList != null) {
            size = mWifiConfigStore.mCachedPnoList.size();
        }

        if (DBG) {
            String s = "";
            if (current != null) {
                s = " for: " + current.configKey();
            }
            Log.e(TAG, " get Pno List total size:" + size + s);
        }
        if (current != null) {
            String configKey = current.configKey();
            /**
             * If we are currently associated to a WifiConfiguration then include
             * only those networks that have a higher priority
             */
            for (WifiNative.WifiPnoNetwork network : mWifiConfigStore.mCachedPnoList) {
                WifiConfiguration config = getWifiConfiguration(network);
                if (config == null) {
                    continue;
                }
                if (config.autoJoinStatus
                        >= WifiConfiguration.AUTO_JOIN_DISABLED_NO_CREDENTIALS) {
                     continue;
                }

                if (!configKey.equals(network.configKey)) {
                    int choice = getConnectChoice(config, current, true);
                    if (choice > 0) {
                        // config is of higher priority
                        if (DBG) {
                            Log.e(TAG, " Pno List adding:" + network.configKey
                                    + " choice " + choice);
                        }
                        list.add(network);
                        network.rssi_threshold = mWifiConfigStore.thresholdGoodRssi24.get();
                    }
                }
            }
        } else {
            for (WifiNative.WifiPnoNetwork network : mWifiConfigStore.mCachedPnoList) {
                WifiConfiguration config = getWifiConfiguration(network);
                if (config == null) {
                    continue;
                }
                if (config.autoJoinStatus
                        >= WifiConfiguration.AUTO_JOIN_DISABLED_NO_CREDENTIALS) {
                    continue;
                }
                list.add(network);
                network.rssi_threshold = mWifiConfigStore.thresholdGoodRssi24.get();
            }
        }
        return list;
    }
}

