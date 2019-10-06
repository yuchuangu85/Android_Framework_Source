/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.OPSTR_CHANGE_WIFI_STATE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Handler;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.server.wifi.util.WifiPermissionsUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Network Suggestions Manager.
 * NOTE: This class should always be invoked from the main wifi service thread.
 */
@NotThreadSafe
public class WifiNetworkSuggestionsManager {
    private static final String TAG = "WifiNetworkSuggestionsManager";

    /** Intent when user tapped action button to allow the app. */
    @VisibleForTesting
    public static final String NOTIFICATION_USER_ALLOWED_APP_INTENT_ACTION =
            "com.android.server.wifi.action.NetworkSuggestion.USER_ALLOWED_APP";
    /** Intent when user tapped action button to disallow the app. */
    @VisibleForTesting
    public static final String NOTIFICATION_USER_DISALLOWED_APP_INTENT_ACTION =
            "com.android.server.wifi.action.NetworkSuggestion.USER_DISALLOWED_APP";
    /** Intent when user dismissed the notification. */
    @VisibleForTesting
    public static final String NOTIFICATION_USER_DISMISSED_INTENT_ACTION =
            "com.android.server.wifi.action.NetworkSuggestion.USER_DISMISSED";
    @VisibleForTesting
    public static final String EXTRA_PACKAGE_NAME =
            "com.android.server.wifi.extra.NetworkSuggestion.PACKAGE_NAME";
    @VisibleForTesting
    public static final String EXTRA_UID =
            "com.android.server.wifi.extra.NetworkSuggestion.UID";

    private final Context mContext;
    private final Resources mResources;
    private final Handler mHandler;
    private final AppOpsManager mAppOps;
    private final NotificationManager mNotificationManager;
    private final PackageManager mPackageManager;
    private final WifiPermissionsUtil mWifiPermissionsUtil;
    private final WifiConfigManager mWifiConfigManager;
    private final WifiMetrics mWifiMetrics;
    private final WifiInjector mWifiInjector;
    private final FrameworkFacade mFrameworkFacade;

    /**
     * Per app meta data to store network suggestions, status, etc for each app providing network
     * suggestions on the device.
     */
    public static class PerAppInfo {
        /**
         * Package Name of the app.
         */
        public final String packageName;
        /**
         * Set of active network suggestions provided by the app.
         */
        public final Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions = new HashSet<>();
        /**
         * Whether we have shown the user a notification for this app.
         */
        public boolean hasUserApproved = false;

        /** Stores the max size of the {@link #extNetworkSuggestions} list ever for this app */
        public int maxSize = 0;

        public PerAppInfo(@NonNull String packageName) {
            this.packageName = packageName;
        }

        // This is only needed for comparison in unit tests.
        @Override
        public boolean equals(Object other) {
            if (other == null) return false;
            if (!(other instanceof PerAppInfo)) return false;
            PerAppInfo otherPerAppInfo = (PerAppInfo) other;
            return TextUtils.equals(packageName, otherPerAppInfo.packageName)
                    && Objects.equals(extNetworkSuggestions, otherPerAppInfo.extNetworkSuggestions)
                    && hasUserApproved == otherPerAppInfo.hasUserApproved;
        }

        // This is only needed for comparison in unit tests.
        @Override
        public int hashCode() {
            return Objects.hash(packageName, extNetworkSuggestions, hasUserApproved);
        }
    }

    /**
     * Internal container class which holds a network suggestion and a pointer to the
     * {@link PerAppInfo} entry from {@link #mActiveNetworkSuggestionsPerApp} corresponding to the
     * app that made the suggestion.
     */
    public static class ExtendedWifiNetworkSuggestion {
        public final WifiNetworkSuggestion wns;
        // Store the pointer to the corresponding app's meta data.
        public final PerAppInfo perAppInfo;

        public ExtendedWifiNetworkSuggestion(@NonNull WifiNetworkSuggestion wns,
                                             @NonNull PerAppInfo perAppInfo) {
            this.wns = wns;
            this.perAppInfo = perAppInfo;
        }

        @Override
        public int hashCode() {
            return Objects.hash(wns); // perAppInfo not used for equals.
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ExtendedWifiNetworkSuggestion)) {
                return false;
            }
            ExtendedWifiNetworkSuggestion other = (ExtendedWifiNetworkSuggestion) obj;
            return wns.equals(other.wns); // perAppInfo not used for equals.
        }

        @Override
        public String toString() {
            return "Extended" + wns.toString();
        }

        /**
         * Convert from {@link WifiNetworkSuggestion} to a new instance of
         * {@link ExtendedWifiNetworkSuggestion}.
         */
        public static ExtendedWifiNetworkSuggestion fromWns(
                @NonNull WifiNetworkSuggestion wns, @NonNull PerAppInfo perAppInfo) {
            return new ExtendedWifiNetworkSuggestion(wns, perAppInfo);
        }
    }

    /**
     * Map of package name of an app to the set of active network suggestions provided by the app.
     */
    private final Map<String, PerAppInfo> mActiveNetworkSuggestionsPerApp = new HashMap<>();
    /**
     * Map of package name of an app to the app ops changed listener for the app.
     */
    private final Map<String, AppOpsChangedListener> mAppOpsChangedListenerPerApp = new HashMap<>();
    /**
     * Map maintained to help lookup all the network suggestions (with no bssid) that match a
     * provided scan result.
     * Note:
     * <li>There could be multiple suggestions (provided by different apps) that match a single
     * scan result.</li>
     * <li>Adding/Removing to this set for scan result lookup is expensive. But, we expect scan
     * result lookup to happen much more often than apps modifying network suggestions.</li>
     */
    private final Map<ScanResultMatchInfo, Set<ExtendedWifiNetworkSuggestion>>
            mActiveScanResultMatchInfoWithNoBssid = new HashMap<>();
    /**
     * Map maintained to help lookup all the network suggestions (with bssid) that match a provided
     * scan result.
     * Note:
     * <li>There could be multiple suggestions (provided by different apps) that match a single
     * scan result.</li>
     * <li>Adding/Removing to this set for scan result lookup is expensive. But, we expect scan
     * result lookup to happen much more often than apps modifying network suggestions.</li>
     */
    private final Map<Pair<ScanResultMatchInfo, MacAddress>, Set<ExtendedWifiNetworkSuggestion>>
            mActiveScanResultMatchInfoWithBssid = new HashMap<>();
    /**
     * List of {@link WifiNetworkSuggestion} matching the current connected network.
     */
    private Set<ExtendedWifiNetworkSuggestion> mActiveNetworkSuggestionsMatchingConnection;

    /**
     * Intent filter for processing notification actions.
     */
    private final IntentFilter mIntentFilter;

    /**
     * Verbose logging flag.
     */
    private boolean mVerboseLoggingEnabled = false;
    /**
     * Indicates that we have new data to serialize.
     */
    private boolean mHasNewDataToSerialize = false;
    /**
     * Indicates if the user approval notification is active.
     */
    private boolean mUserApprovalNotificationActive = false;
    /**
     * Stores the name of the user approval notification that is active.
     */
    private String mUserApprovalNotificationPackageName;

    /**
     * Listener for app-ops changes for active suggestor apps.
     */
    private final class AppOpsChangedListener implements AppOpsManager.OnOpChangedListener {
        private final String mPackageName;
        private final int mUid;

        AppOpsChangedListener(@NonNull String packageName, int uid) {
            mPackageName = packageName;
            mUid = uid;
        }

        @Override
        public void onOpChanged(String op, String packageName) {
            mHandler.post(() -> {
                if (!mPackageName.equals(packageName)) return;
                if (!OPSTR_CHANGE_WIFI_STATE.equals(op)) return;

                // Ensure the uid to package mapping is still correct.
                try {
                    mAppOps.checkPackage(mUid, mPackageName);
                } catch (SecurityException e) {
                    Log.wtf(TAG, "Invalid uid/package" + packageName);
                    return;
                }

                if (mAppOps.unsafeCheckOpNoThrow(OPSTR_CHANGE_WIFI_STATE, mUid, mPackageName)
                        == AppOpsManager.MODE_IGNORED) {
                    Log.i(TAG, "User disallowed change wifi state for " + packageName);
                    // User disabled the app, remove app from database. We want the notification
                    // again if the user enabled the app-op back.
                    removeApp(mPackageName);
                }
            });
        }
    };

    /**
     * Module to interact with the wifi config store.
     */
    private class NetworkSuggestionDataSource implements NetworkSuggestionStoreData.DataSource {
        @Override
        public Map<String, PerAppInfo> toSerialize() {
            // Clear the flag after writing to disk.
            // TODO(b/115504887): Don't reset the flag on write failure.
            mHasNewDataToSerialize = false;
            return mActiveNetworkSuggestionsPerApp;
        }

        @Override

        public void fromDeserialized(Map<String, PerAppInfo> networkSuggestionsMap) {
            mActiveNetworkSuggestionsPerApp.putAll(networkSuggestionsMap);
            // Build the scan cache.
            for (Map.Entry<String, PerAppInfo> entry : networkSuggestionsMap.entrySet()) {
                String packageName = entry.getKey();
                Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions =
                        entry.getValue().extNetworkSuggestions;
                if (!extNetworkSuggestions.isEmpty()) {
                    // Start tracking app-op changes from the app if they have active suggestions.
                    startTrackingAppOpsChange(packageName,
                            extNetworkSuggestions.iterator().next().wns.suggestorUid);
                }
                addToScanResultMatchInfoMap(extNetworkSuggestions);
            }
        }

        @Override
        public void reset() {
            mActiveNetworkSuggestionsPerApp.clear();
            mActiveScanResultMatchInfoWithBssid.clear();
            mActiveScanResultMatchInfoWithNoBssid.clear();
        }

        @Override
        public boolean hasNewDataToSerialize() {
            return mHasNewDataToSerialize;
        }
    }

    private final BroadcastReceiver mBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);
                    if (packageName == null) {
                        Log.e(TAG, "No package name found in intent");
                        return;
                    }
                    int uid = intent.getIntExtra(EXTRA_UID, -1);
                    if (uid == -1) {
                        Log.e(TAG, "No uid found in intent");
                        return;
                    }
                    switch (intent.getAction()) {
                        case NOTIFICATION_USER_ALLOWED_APP_INTENT_ACTION:
                            Log.i(TAG, "User clicked to allow app");
                            // Set the user approved flag.
                            setHasUserApprovedForApp(true, packageName);
                            break;
                        case NOTIFICATION_USER_DISALLOWED_APP_INTENT_ACTION:
                            Log.i(TAG, "User clicked to disallow app");
                            // Set the user approved flag.
                            setHasUserApprovedForApp(false, packageName);
                            // Take away CHANGE_WIFI_STATE app-ops from the app.
                            mAppOps.setMode(AppOpsManager.OP_CHANGE_WIFI_STATE, uid, packageName,
                                    MODE_IGNORED);
                            break;
                        case NOTIFICATION_USER_DISMISSED_INTENT_ACTION:
                            Log.i(TAG, "User dismissed the notification");
                            mUserApprovalNotificationActive = false;
                            return; // no need to cancel a dismissed notification, return.
                        default:
                            Log.e(TAG, "Unknown action " + intent.getAction());
                            return;
                    }
                    // Clear notification once the user interacts with it.
                    mUserApprovalNotificationActive = false;
                    mNotificationManager.cancel(SystemMessage.NOTE_NETWORK_SUGGESTION_AVAILABLE);
                }
            };

    public WifiNetworkSuggestionsManager(Context context, Handler handler,
                                         WifiInjector wifiInjector,
                                         WifiPermissionsUtil wifiPermissionsUtil,
                                         WifiConfigManager wifiConfigManager,
                                         WifiConfigStore wifiConfigStore,
                                         WifiMetrics wifiMetrics) {
        mContext = context;
        mResources = context.getResources();
        mHandler = handler;
        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        mNotificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        mPackageManager = context.getPackageManager();
        mWifiInjector = wifiInjector;
        mFrameworkFacade = mWifiInjector.getFrameworkFacade();
        mWifiPermissionsUtil = wifiPermissionsUtil;
        mWifiConfigManager = wifiConfigManager;
        mWifiMetrics = wifiMetrics;

        // register the data store for serializing/deserializing data.
        wifiConfigStore.registerStoreData(
                wifiInjector.makeNetworkSuggestionStoreData(new NetworkSuggestionDataSource()));

        // Register broadcast receiver for UI interactions.
        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(NOTIFICATION_USER_ALLOWED_APP_INTENT_ACTION);
        mIntentFilter.addAction(NOTIFICATION_USER_DISALLOWED_APP_INTENT_ACTION);
        mIntentFilter.addAction(NOTIFICATION_USER_DISMISSED_INTENT_ACTION);
        mContext.registerReceiver(mBroadcastReceiver, mIntentFilter);
    }

    /**
     * Enable verbose logging.
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = verbose > 0;
    }

    private void saveToStore() {
        // Set the flag to let WifiConfigStore that we have new data to write.
        mHasNewDataToSerialize = true;
        if (!mWifiConfigManager.saveToStore(true)) {
            Log.w(TAG, "Failed to save to store");
        }
    }

    private void addToScanResultMatchInfoMap(
            @NonNull Collection<ExtendedWifiNetworkSuggestion> extNetworkSuggestions) {
        for (ExtendedWifiNetworkSuggestion extNetworkSuggestion : extNetworkSuggestions) {
            ScanResultMatchInfo scanResultMatchInfo =
                    ScanResultMatchInfo.fromWifiConfiguration(
                            extNetworkSuggestion.wns.wifiConfiguration);
            Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestionsForScanResultMatchInfo;
            if (!TextUtils.isEmpty(extNetworkSuggestion.wns.wifiConfiguration.BSSID)) {
                Pair<ScanResultMatchInfo, MacAddress> lookupPair =
                        Pair.create(scanResultMatchInfo,
                                MacAddress.fromString(
                                        extNetworkSuggestion.wns.wifiConfiguration.BSSID));
                extNetworkSuggestionsForScanResultMatchInfo =
                        mActiveScanResultMatchInfoWithBssid.get(lookupPair);
                if (extNetworkSuggestionsForScanResultMatchInfo == null) {
                    extNetworkSuggestionsForScanResultMatchInfo = new HashSet<>();
                    mActiveScanResultMatchInfoWithBssid.put(
                            lookupPair, extNetworkSuggestionsForScanResultMatchInfo);
                }
            } else {
                extNetworkSuggestionsForScanResultMatchInfo =
                        mActiveScanResultMatchInfoWithNoBssid.get(scanResultMatchInfo);
                if (extNetworkSuggestionsForScanResultMatchInfo == null) {
                    extNetworkSuggestionsForScanResultMatchInfo = new HashSet<>();
                    mActiveScanResultMatchInfoWithNoBssid.put(
                            scanResultMatchInfo, extNetworkSuggestionsForScanResultMatchInfo);
                }
            }
            extNetworkSuggestionsForScanResultMatchInfo.add(extNetworkSuggestion);
        }
    }

    private void removeFromScanResultMatchInfoMap(
            @NonNull Collection<ExtendedWifiNetworkSuggestion> extNetworkSuggestions) {
        for (ExtendedWifiNetworkSuggestion extNetworkSuggestion : extNetworkSuggestions) {
            ScanResultMatchInfo scanResultMatchInfo =
                    ScanResultMatchInfo.fromWifiConfiguration(
                            extNetworkSuggestion.wns.wifiConfiguration);
            Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestionsForScanResultMatchInfo;
            if (!TextUtils.isEmpty(extNetworkSuggestion.wns.wifiConfiguration.BSSID)) {
                Pair<ScanResultMatchInfo, MacAddress> lookupPair =
                        Pair.create(scanResultMatchInfo,
                                MacAddress.fromString(
                                        extNetworkSuggestion.wns.wifiConfiguration.BSSID));
                extNetworkSuggestionsForScanResultMatchInfo =
                        mActiveScanResultMatchInfoWithBssid.get(lookupPair);
                // This should never happen because we should have done necessary error checks in
                // the parent method.
                if (extNetworkSuggestionsForScanResultMatchInfo == null) {
                    Log.wtf(TAG, "No scan result match info found.");
                }
                extNetworkSuggestionsForScanResultMatchInfo.remove(extNetworkSuggestion);
                // Remove the set from map if empty.
                if (extNetworkSuggestionsForScanResultMatchInfo.isEmpty()) {
                    mActiveScanResultMatchInfoWithBssid.remove(lookupPair);
                }
            } else {
                extNetworkSuggestionsForScanResultMatchInfo =
                        mActiveScanResultMatchInfoWithNoBssid.get(scanResultMatchInfo);
                // This should never happen because we should have done necessary error checks in
                // the parent method.
                if (extNetworkSuggestionsForScanResultMatchInfo == null) {
                    Log.wtf(TAG, "No scan result match info found.");
                }
                extNetworkSuggestionsForScanResultMatchInfo.remove(extNetworkSuggestion);
                // Remove the set from map if empty.
                if (extNetworkSuggestionsForScanResultMatchInfo.isEmpty()) {
                    mActiveScanResultMatchInfoWithNoBssid.remove(scanResultMatchInfo);
                }
            }
        }
    }

    // Issues a disconnect if the only serving network suggestion is removed.
    // TODO (b/115504887): What if there is also a saved network with the same credentials?
    private void triggerDisconnectIfServingNetworkSuggestionRemoved(
            Collection<ExtendedWifiNetworkSuggestion> extNetworkSuggestionsRemoved) {
        if (mActiveNetworkSuggestionsMatchingConnection == null
                || mActiveNetworkSuggestionsMatchingConnection.isEmpty()) {
            return;
        }
        if (mActiveNetworkSuggestionsMatchingConnection.removeAll(extNetworkSuggestionsRemoved)) {
            if (mActiveNetworkSuggestionsMatchingConnection.isEmpty()) {
                Log.i(TAG, "Only network suggestion matching the connected network removed. "
                        + "Disconnecting...");
                mWifiInjector.getClientModeImpl().disconnectCommand();
            }
        }
    }

    private void startTrackingAppOpsChange(@NonNull String packageName, int uid) {
        AppOpsChangedListener appOpsChangedListener =
                new AppOpsChangedListener(packageName, uid);
        mAppOps.startWatchingMode(OPSTR_CHANGE_WIFI_STATE, packageName, appOpsChangedListener);
        mAppOpsChangedListenerPerApp.put(packageName, appOpsChangedListener);
    }

    /**
     * Helper method to convert the incoming collection of public {@link WifiNetworkSuggestion}
     * objects to a set of corresponding internal wrapper
     * {@link ExtendedWifiNetworkSuggestion} objects.
     */
    private Set<ExtendedWifiNetworkSuggestion> convertToExtendedWnsSet(
            final Collection<WifiNetworkSuggestion> networkSuggestions,
            final PerAppInfo perAppInfo) {
        return networkSuggestions
                .stream()
                .collect(Collectors.mapping(
                        n -> ExtendedWifiNetworkSuggestion.fromWns(n, perAppInfo),
                        Collectors.toSet()));
    }

    /**
     * Helper method to convert the incoming collection of internal wrapper
     * {@link ExtendedWifiNetworkSuggestion} objects to a set of corresponding public
     * {@link WifiNetworkSuggestion} objects.
     */
    private Set<WifiNetworkSuggestion> convertToWnsSet(
            final Collection<ExtendedWifiNetworkSuggestion> extNetworkSuggestions) {
        return extNetworkSuggestions
                .stream()
                .collect(Collectors.mapping(
                        n -> n.wns,
                        Collectors.toSet()));
    }

    /**
     * Add the provided list of network suggestions from the corresponding app's active list.
     */
    public @WifiManager.NetworkSuggestionsStatusCode int add(
            List<WifiNetworkSuggestion> networkSuggestions, int uid, String packageName) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Adding " + networkSuggestions.size() + " networks from " + packageName);
        }
        if (networkSuggestions.isEmpty()) {
            Log.w(TAG, "Empty list of network suggestions for " + packageName + ". Ignoring");
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
        }
        PerAppInfo perAppInfo = mActiveNetworkSuggestionsPerApp.get(packageName);
        if (perAppInfo == null) {
            perAppInfo = new PerAppInfo(packageName);
            mActiveNetworkSuggestionsPerApp.put(packageName, perAppInfo);
            if (mWifiPermissionsUtil.checkNetworkCarrierProvisioningPermission(uid)) {
                Log.i(TAG, "Setting the carrier provisioning app approved");
                perAppInfo.hasUserApproved = true;
            }
        }
        Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions =
                convertToExtendedWnsSet(networkSuggestions, perAppInfo);
        // check if the app is trying to in-place modify network suggestions.
        if (!Collections.disjoint(perAppInfo.extNetworkSuggestions, extNetworkSuggestions)) {
            Log.e(TAG, "Failed to add network suggestions for " + packageName
                    + ". Modification of active network suggestions disallowed");
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE;
        }
        if (perAppInfo.extNetworkSuggestions.size() + extNetworkSuggestions.size()
                > WifiManager.NETWORK_SUGGESTIONS_MAX_PER_APP) {
            Log.e(TAG, "Failed to add network suggestions for " + packageName
                    + ". Exceeds max per app, current list size: "
                    + perAppInfo.extNetworkSuggestions.size()
                    + ", new list size: "
                    + extNetworkSuggestions.size());
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP;
        }
        if (perAppInfo.extNetworkSuggestions.isEmpty()) {
            // Start tracking app-op changes from the app if they have active suggestions.
            startTrackingAppOpsChange(packageName, uid);
        }
        perAppInfo.extNetworkSuggestions.addAll(extNetworkSuggestions);
        // Update the max size for this app.
        perAppInfo.maxSize = Math.max(perAppInfo.extNetworkSuggestions.size(), perAppInfo.maxSize);
        addToScanResultMatchInfoMap(extNetworkSuggestions);
        saveToStore();
        mWifiMetrics.incrementNetworkSuggestionApiNumModification();
        mWifiMetrics.noteNetworkSuggestionApiListSizeHistogram(getAllMaxSizes());
        return WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
    }

    private void stopTrackingAppOpsChange(@NonNull String packageName) {
        AppOpsChangedListener appOpsChangedListener =
                mAppOpsChangedListenerPerApp.remove(packageName);
        if (appOpsChangedListener == null) {
            Log.wtf(TAG, "No app ops listener found for " + packageName);
            return;
        }
        mAppOps.stopWatchingMode(appOpsChangedListener);
    }

    private void removeInternal(
            @NonNull Collection<ExtendedWifiNetworkSuggestion> extNetworkSuggestions,
            @NonNull String packageName,
            @NonNull PerAppInfo perAppInfo) {
        if (!extNetworkSuggestions.isEmpty()) {
            perAppInfo.extNetworkSuggestions.removeAll(extNetworkSuggestions);
        } else {
            // empty list is used to clear everything for the app. Store a copy for use below.
            extNetworkSuggestions = new HashSet<>(perAppInfo.extNetworkSuggestions);
            perAppInfo.extNetworkSuggestions.clear();
        }
        if (perAppInfo.extNetworkSuggestions.isEmpty()) {
            // Note: We don't remove the app entry even if there is no active suggestions because
            // we want to keep the notification state for all apps that have ever provided
            // suggestions.
            if (mVerboseLoggingEnabled) Log.v(TAG, "No active suggestions for " + packageName);
            // Stop tracking app-op changes from the app if they don't have active suggestions.
            stopTrackingAppOpsChange(packageName);
        }
        // Clear the scan cache.
        removeFromScanResultMatchInfoMap(extNetworkSuggestions);
    }

    /**
     * Remove the provided list of network suggestions from the corresponding app's active list.
     */
    public @WifiManager.NetworkSuggestionsStatusCode int remove(
            List<WifiNetworkSuggestion> networkSuggestions, String packageName) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Removing " + networkSuggestions.size() + " networks from " + packageName);
        }
        PerAppInfo perAppInfo = mActiveNetworkSuggestionsPerApp.get(packageName);
        if (perAppInfo == null) {
            Log.e(TAG, "Failed to remove network suggestions for " + packageName
                    + ". No network suggestions found");
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID;
        }
        Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions =
                convertToExtendedWnsSet(networkSuggestions, perAppInfo);
        // check if all the request network suggestions are present in the active list.
        if (!extNetworkSuggestions.isEmpty()
                && !perAppInfo.extNetworkSuggestions.containsAll(extNetworkSuggestions)) {
            Log.e(TAG, "Failed to remove network suggestions for " + packageName
                    + ". Network suggestions not found in active network suggestions");
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID;
        }
        removeInternal(extNetworkSuggestions, packageName, perAppInfo);
        saveToStore();
        mWifiMetrics.incrementNetworkSuggestionApiNumModification();
        mWifiMetrics.noteNetworkSuggestionApiListSizeHistogram(getAllMaxSizes());
        return WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
    }

    /**
     * Remove all tracking of the app that has been uninstalled.
     */
    public void removeApp(@NonNull String packageName) {
        PerAppInfo perAppInfo = mActiveNetworkSuggestionsPerApp.get(packageName);
        if (perAppInfo == null) return;
        // Disconnect from the current network, if the only suggestion for it was removed.
        triggerDisconnectIfServingNetworkSuggestionRemoved(perAppInfo.extNetworkSuggestions);
        removeInternal(Collections.EMPTY_LIST, packageName, perAppInfo);
        // Remove the package fully from the internal database.
        mActiveNetworkSuggestionsPerApp.remove(packageName);
        saveToStore();
        Log.i(TAG, "Removed " + packageName);
    }

    /**
     * Clear all internal state (for network settings reset).
     */
    public void clear() {
        Iterator<Map.Entry<String, PerAppInfo>> iter =
                mActiveNetworkSuggestionsPerApp.entrySet().iterator();
        // Disconnect if we're connected to one of the suggestions.
        triggerDisconnectIfServingNetworkSuggestionRemoved(
                mActiveNetworkSuggestionsMatchingConnection);
        while (iter.hasNext()) {
            Map.Entry<String, PerAppInfo> entry = iter.next();
            removeInternal(Collections.EMPTY_LIST, entry.getKey(), entry.getValue());
            iter.remove();
        }
        saveToStore();
        Log.i(TAG, "Cleared all internal state");
    }

    /**
     * Check if network suggestions are enabled or disabled for the app.
     */
    public boolean hasUserApprovedForApp(String packageName) {
        PerAppInfo perAppInfo = mActiveNetworkSuggestionsPerApp.get(packageName);
        if (perAppInfo == null) return false;

        return perAppInfo.hasUserApproved;
    }

    /**
     * Enable or Disable network suggestions for the app.
     */
    public void setHasUserApprovedForApp(boolean approved, String packageName) {
        PerAppInfo perAppInfo = mActiveNetworkSuggestionsPerApp.get(packageName);
        if (perAppInfo == null) return;

        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Setting the app " + (approved ? "approved" : "not approved"));
        }
        perAppInfo.hasUserApproved = approved;
        saveToStore();
    }

    /**
     * Returns a set of all network suggestions across all apps.
     */
    @VisibleForTesting
    public Set<WifiNetworkSuggestion> getAllNetworkSuggestions() {
        return mActiveNetworkSuggestionsPerApp.values()
                .stream()
                .flatMap(e -> convertToWnsSet(e.extNetworkSuggestions)
                        .stream())
                .collect(Collectors.toSet());
    }

    private List<Integer> getAllMaxSizes() {
        return mActiveNetworkSuggestionsPerApp.values()
                .stream()
                .map(e -> e.maxSize)
                .collect(Collectors.toList());
    }

    private PendingIntent getPrivateBroadcast(@NonNull String action, @NonNull String packageName,
                                              int uid) {
        Intent intent = new Intent(action)
                .setPackage("android")
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
                .putExtra(EXTRA_UID, uid);
        return mFrameworkFacade.getBroadcast(mContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private @NonNull CharSequence getAppName(@NonNull String packageName) {
        ApplicationInfo applicationInfo = null;
        try {
            applicationInfo = mPackageManager.getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to find app name for " + packageName);
            return "";
        }
        CharSequence appName = mPackageManager.getApplicationLabel(applicationInfo);
        return (appName != null) ? appName : "";
    }

    private void sendUserApprovalNotification(@NonNull String packageName, int uid) {
        Notification.Action userAllowAppNotificationAction =
                new Notification.Action.Builder(null,
                        mResources.getText(R.string.wifi_suggestion_action_allow_app),
                        getPrivateBroadcast(NOTIFICATION_USER_ALLOWED_APP_INTENT_ACTION,
                                packageName, uid))
                        .build();
        Notification.Action userDisallowAppNotificationAction =
                new Notification.Action.Builder(null,
                        mResources.getText(R.string.wifi_suggestion_action_disallow_app),
                        getPrivateBroadcast(NOTIFICATION_USER_DISALLOWED_APP_INTENT_ACTION,
                                packageName, uid))
                        .build();

        CharSequence appName = getAppName(packageName);
        Notification notification = new Notification.Builder(
                mContext, SystemNotificationChannels.NETWORK_STATUS)
                .setSmallIcon(R.drawable.stat_notify_wifi_in_range)
                .setTicker(mResources.getString(R.string.wifi_suggestion_title))
                .setContentTitle(mResources.getString(R.string.wifi_suggestion_title))
                .setContentText(mResources.getString(R.string.wifi_suggestion_content, appName))
                .setDeleteIntent(getPrivateBroadcast(NOTIFICATION_USER_DISMISSED_INTENT_ACTION,
                        packageName, uid))
                .setShowWhen(false)
                .setLocalOnly(true)
                .setColor(mResources.getColor(R.color.system_notification_accent_color,
                        mContext.getTheme()))
                .addAction(userAllowAppNotificationAction)
                .addAction(userDisallowAppNotificationAction)
                .build();

        // Post the notification.
        mNotificationManager.notify(
                SystemMessage.NOTE_NETWORK_SUGGESTION_AVAILABLE, notification);
        mUserApprovalNotificationActive = true;
        mUserApprovalNotificationPackageName = packageName;
    }

    private boolean sendUserApprovalNotificationIfNotApproved(
            @NonNull PerAppInfo perAppInfo,
            @NonNull WifiNetworkSuggestion matchingSuggestion) {
        if (perAppInfo.hasUserApproved) {
            return false; // already approved.
        }

        Log.i(TAG, "Sending user approval notification for " + perAppInfo.packageName);
        sendUserApprovalNotification(perAppInfo.packageName, matchingSuggestion.suggestorUid);
        return true;
    }

    private @Nullable Set<ExtendedWifiNetworkSuggestion>
            getNetworkSuggestionsForScanResultMatchInfo(
            @NonNull ScanResultMatchInfo scanResultMatchInfo, @Nullable MacAddress bssid) {
        Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions = new HashSet<>();
        if (bssid != null) {
            Set<ExtendedWifiNetworkSuggestion> matchingExtNetworkSuggestionsWithBssid =
                    mActiveScanResultMatchInfoWithBssid.get(
                            Pair.create(scanResultMatchInfo, bssid));
            if (matchingExtNetworkSuggestionsWithBssid != null) {
                extNetworkSuggestions.addAll(matchingExtNetworkSuggestionsWithBssid);
            }
        }
        Set<ExtendedWifiNetworkSuggestion> matchingNetworkSuggestionsWithNoBssid =
                mActiveScanResultMatchInfoWithNoBssid.get(scanResultMatchInfo);
        if (matchingNetworkSuggestionsWithNoBssid != null) {
            extNetworkSuggestions.addAll(matchingNetworkSuggestionsWithNoBssid);
        }
        if (extNetworkSuggestions.isEmpty()) {
            return null;
        }
        return extNetworkSuggestions;
    }

    /**
     * Returns a set of all network suggestions matching the provided scan detail.
     */
    public @Nullable Set<WifiNetworkSuggestion> getNetworkSuggestionsForScanDetail(
            @NonNull ScanDetail scanDetail) {
        ScanResult scanResult = scanDetail.getScanResult();
        if (scanResult == null) {
            Log.e(TAG, "No scan result found in scan detail");
            return null;
        }
        Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions = null;
        try {
            ScanResultMatchInfo scanResultMatchInfo =
                    ScanResultMatchInfo.fromScanResult(scanResult);
            extNetworkSuggestions = getNetworkSuggestionsForScanResultMatchInfo(
                    scanResultMatchInfo,  MacAddress.fromString(scanResult.BSSID));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to lookup network from scan result match info map", e);
        }
        if (extNetworkSuggestions == null) {
            return null;
        }
        Set<ExtendedWifiNetworkSuggestion> approvedExtNetworkSuggestions =
                extNetworkSuggestions
                        .stream()
                        .filter(n -> n.perAppInfo.hasUserApproved)
                        .collect(Collectors.toSet());
        // If there is no active notification, check if we need to get approval for any of the apps
        // & send a notification for one of them. If there are multiple packages awaiting approval,
        // we end up picking the first one. The others will be reconsidered in the next iteration.
        if (!mUserApprovalNotificationActive
                && approvedExtNetworkSuggestions.size() != extNetworkSuggestions.size()) {
            for (ExtendedWifiNetworkSuggestion extNetworkSuggestion : extNetworkSuggestions) {
                if (sendUserApprovalNotificationIfNotApproved(
                        extNetworkSuggestion.perAppInfo, extNetworkSuggestion.wns)) {
                    break;
                }
            }
        }
        if (approvedExtNetworkSuggestions.isEmpty()) {
            return null;
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "getNetworkSuggestionsForScanDetail Found "
                    + approvedExtNetworkSuggestions + " for " + scanResult.SSID
                    + "[" + scanResult.capabilities + "]");
        }
        return convertToWnsSet(approvedExtNetworkSuggestions);
    }

    /**
     * Returns a set of all network suggestions matching the provided the WifiConfiguration.
     */
    private @Nullable Set<ExtendedWifiNetworkSuggestion> getNetworkSuggestionsForWifiConfiguration(
            @NonNull WifiConfiguration wifiConfiguration, @Nullable String bssid) {
        Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions = null;
        try {
            ScanResultMatchInfo scanResultMatchInfo =
                    ScanResultMatchInfo.fromWifiConfiguration(wifiConfiguration);
            extNetworkSuggestions = getNetworkSuggestionsForScanResultMatchInfo(
                    scanResultMatchInfo,  bssid == null ? null : MacAddress.fromString(bssid));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to lookup network from scan result match info map", e);
        }
        if (extNetworkSuggestions == null) {
            return null;
        }
        Set<ExtendedWifiNetworkSuggestion> approvedExtNetworkSuggestions =
                extNetworkSuggestions
                        .stream()
                        .filter(n -> n.perAppInfo.hasUserApproved)
                        .collect(Collectors.toSet());
        if (approvedExtNetworkSuggestions.isEmpty()) {
            return null;
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "getNetworkSuggestionsFoWifiConfiguration Found "
                    + approvedExtNetworkSuggestions + " for " + wifiConfiguration.SSID
                    + "[" + wifiConfiguration.allowedKeyManagement + "]");
        }
        return approvedExtNetworkSuggestions;
    }

    /**
     * Helper method to send the post connection broadcast to specified package.
     */
    private void sendPostConnectionBroadcast(
            String packageName, WifiNetworkSuggestion networkSuggestion) {
        Intent intent = new Intent(WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION);
        intent.putExtra(WifiManager.EXTRA_NETWORK_SUGGESTION, networkSuggestion);
        // Intended to wakeup the receiving app so set the specific package name.
        intent.setPackage(packageName);
        mContext.sendBroadcastAsUser(
                intent, UserHandle.getUserHandleForUid(networkSuggestion.suggestorUid));
    }

    /**
     * Helper method to send the post connection broadcast to specified package.
     */
    private void sendPostConnectionBroadcastIfAllowed(
            String packageName, WifiNetworkSuggestion matchingSuggestion) {
        try {
            mWifiPermissionsUtil.enforceCanAccessScanResults(
                    packageName, matchingSuggestion.suggestorUid);
        } catch (SecurityException se) {
            return;
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Sending post connection broadcast to " + packageName);
        }
        sendPostConnectionBroadcast(packageName, matchingSuggestion);
    }

    /**
     * Send out the {@link WifiManager#ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION} to all the
     * network suggestion credentials that match the current connection network.
     *
     * @param connectedNetwork {@link WifiConfiguration} representing the network connected to.
     * @param connectedBssid BSSID of the network connected to.
     */
    private void handleConnectionSuccess(
            @NonNull WifiConfiguration connectedNetwork, @NonNull String connectedBssid) {
        Set<ExtendedWifiNetworkSuggestion> matchingExtNetworkSuggestions =
                getNetworkSuggestionsForWifiConfiguration(connectedNetwork, connectedBssid);
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Network suggestions matching the connection "
                    + matchingExtNetworkSuggestions);
        }
        if (matchingExtNetworkSuggestions == null
                || matchingExtNetworkSuggestions.isEmpty()) return;

        mWifiMetrics.incrementNetworkSuggestionApiNumConnectSuccess();

        // Store the set of matching network suggestions.
        mActiveNetworkSuggestionsMatchingConnection = new HashSet<>(matchingExtNetworkSuggestions);

        // Find subset of network suggestions which have set |isAppInteractionRequired|.
        Set<ExtendedWifiNetworkSuggestion> matchingExtNetworkSuggestionsWithReqAppInteraction =
                matchingExtNetworkSuggestions.stream()
                        .filter(x -> x.wns.isAppInteractionRequired)
                        .collect(Collectors.toSet());
        if (matchingExtNetworkSuggestionsWithReqAppInteraction.size() == 0) return;

        // Iterate over the matching network suggestions list:
        // a) Ensure that these apps have the necessary location permissions.
        // b) Send directed broadcast to the app with their corresponding network suggestion.
        for (ExtendedWifiNetworkSuggestion matchingExtNetworkSuggestion
                : matchingExtNetworkSuggestionsWithReqAppInteraction) {
            sendPostConnectionBroadcastIfAllowed(
                    matchingExtNetworkSuggestion.perAppInfo.packageName,
                    matchingExtNetworkSuggestion.wns);
        }
    }

    /**
     * Handle connection failure.
     *
     * @param network {@link WifiConfiguration} representing the network that connection failed to.
     * @param bssid BSSID of the network connection failed to if known, else null.
     */
    private void handleConnectionFailure(@NonNull WifiConfiguration network,
                                         @Nullable String bssid) {
        Set<ExtendedWifiNetworkSuggestion> matchingExtNetworkSuggestions =
                getNetworkSuggestionsForWifiConfiguration(network, bssid);
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Network suggestions matching the connection failure "
                    + matchingExtNetworkSuggestions);
        }
        if (matchingExtNetworkSuggestions == null
                || matchingExtNetworkSuggestions.isEmpty()) return;

        mWifiMetrics.incrementNetworkSuggestionApiNumConnectFailure();
        // TODO (b/115504887, b/112196799): Blacklist the corresponding network suggestion if
        // the connection failed.
    }

    private void resetConnectionState() {
        mActiveNetworkSuggestionsMatchingConnection = null;
    }

    /**
     * Invoked by {@link ClientModeImpl} on end of connection attempt to a network.
     *
     * @param failureCode Failure codes representing {@link WifiMetrics.ConnectionEvent} codes.
     * @param network WifiConfiguration corresponding to the current network.
     * @param bssid BSSID of the current network.
     */
    public void handleConnectionAttemptEnded(
            int failureCode, @NonNull WifiConfiguration network, @Nullable String bssid) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "handleConnectionAttemptEnded " + failureCode + ", " + network);
        }
        resetConnectionState();
        if (failureCode == WifiMetrics.ConnectionEvent.FAILURE_NONE) {
            handleConnectionSuccess(network, bssid);
        } else {
            handleConnectionFailure(network, bssid);
        }
    }

    /**
     * Invoked by {@link ClientModeImpl} on disconnect from network.
     */
    public void handleDisconnect(@NonNull WifiConfiguration network, @NonNull String bssid) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "handleDisconnect " + network);
        }
        resetConnectionState();
    }

    /**
     * Dump of {@link WifiNetworkSuggestionsManager}.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiNetworkSuggestionsManager");
        pw.println("WifiNetworkSuggestionsManager - Networks Begin ----");
        for (Map.Entry<String, PerAppInfo> networkSuggestionsEntry
                : mActiveNetworkSuggestionsPerApp.entrySet()) {
            pw.println("Package Name: " + networkSuggestionsEntry.getKey());
            PerAppInfo appInfo = networkSuggestionsEntry.getValue();
            pw.println("Has user approved: " + appInfo.hasUserApproved);
            for (ExtendedWifiNetworkSuggestion extNetworkSuggestion
                    : appInfo.extNetworkSuggestions) {
                pw.println("Network: " + extNetworkSuggestion);
            }
        }
        pw.println("WifiNetworkSuggestionsManager - Networks End ----");
        pw.println("WifiNetworkSuggestionsManager - Network Suggestions matching connection: "
                + mActiveNetworkSuggestionsMatchingConnection);
    }
}

