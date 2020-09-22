/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.Context;
import android.net.IpMemoryStore;
import android.net.NetworkCapabilities;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.net.wifi.nl80211.WifiNl80211Manager;
import android.os.BatteryStatsManager;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserManager;
import android.provider.Settings.Secure;
import android.security.keystore.AndroidKeyStoreProvider;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.LocalLog;
import android.util.Log;

import com.android.server.wifi.aware.WifiAwareMetrics;
import com.android.server.wifi.hotspot2.PasspointManager;
import com.android.server.wifi.hotspot2.PasspointNetworkNominateHelper;
import com.android.server.wifi.hotspot2.PasspointObjectFactory;
import com.android.server.wifi.p2p.SupplicantP2pIfaceHal;
import com.android.server.wifi.p2p.WifiP2pMetrics;
import com.android.server.wifi.p2p.WifiP2pMonitor;
import com.android.server.wifi.p2p.WifiP2pNative;
import com.android.server.wifi.rtt.RttMetrics;
import com.android.server.wifi.util.LruConnectionTracker;
import com.android.server.wifi.util.NetdWrapper;
import com.android.server.wifi.util.SettingsMigrationDataHolder;
import com.android.server.wifi.util.WifiPermissionsUtil;
import com.android.server.wifi.util.WifiPermissionsWrapper;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchProviderException;
import java.util.Random;

/**
 *  WiFi dependency injector. To be used for accessing various WiFi class instances and as a
 *  handle for mock injection.
 *
 *  Some WiFi class instances currently depend on having a Looper from a HandlerThread that has
 *  been started. To accommodate this, we have a two-phased approach to initialize and retrieve
 *  an instance of the WifiInjector.
 */
public class WifiInjector {
    private static final String TAG = "WifiInjector";
    private static final String BOOT_DEFAULT_WIFI_COUNTRY_CODE = "ro.boot.wificountrycode";
    /**
     * Maximum number in-memory store network connection order;
     */
    private static final int MAX_RECENTLY_CONNECTED_NETWORK = 100;

    static WifiInjector sWifiInjector = null;

    private final WifiContext mContext;
    private final BatteryStatsManager mBatteryStats;
    private final FrameworkFacade mFrameworkFacade;
    private final DeviceConfigFacade mDeviceConfigFacade;
    private final UserManager mUserManager;
    private final HandlerThread mAsyncChannelHandlerThread;
    private final HandlerThread mWifiHandlerThread;
    private final HandlerThread mWifiP2pServiceHandlerThread;
    private final HandlerThread mPasspointProvisionerHandlerThread;
    private final WifiTrafficPoller mWifiTrafficPoller;
    private final WifiCountryCode mCountryCode;
    private final BackupManagerProxy mBackupManagerProxy = new BackupManagerProxy();
    private final WifiApConfigStore mWifiApConfigStore;
    private final WifiNative mWifiNative;
    private final WifiMonitor mWifiMonitor;
    private final WifiP2pNative mWifiP2pNative;
    private final WifiP2pMonitor mWifiP2pMonitor;
    private final SupplicantStaIfaceHal mSupplicantStaIfaceHal;
    private final SupplicantP2pIfaceHal mSupplicantP2pIfaceHal;
    private final HostapdHal mHostapdHal;
    private final WifiVendorHal mWifiVendorHal;
    private final ScoringParams mScoringParams;
    private final ClientModeImpl mClientModeImpl;
    private final ActiveModeWarden mActiveModeWarden;
    private final WifiSettingsStore mSettingsStore;
    private OpenNetworkNotifier mOpenNetworkNotifier;
    private final WifiLockManager mLockManager;
    private final WifiNl80211Manager mWifiCondManager;
    private final Clock mClock = new Clock();
    private final WifiMetrics mWifiMetrics;
    private final WifiP2pMetrics mWifiP2pMetrics;
    private WifiLastResortWatchdog mWifiLastResortWatchdog;
    private final PropertyService mPropertyService = new SystemPropertyService();
    private final BuildProperties mBuildProperties = new SystemBuildProperties();
    private final WifiBackupRestore mWifiBackupRestore;
    private final SoftApBackupRestore mSoftApBackupRestore;
    private final WifiMulticastLockManager mWifiMulticastLockManager;
    private final WifiConfigStore mWifiConfigStore;
    private final WifiKeyStore mWifiKeyStore;
    private final WifiConfigManager mWifiConfigManager;
    private final WifiConnectivityHelper mWifiConnectivityHelper;
    private final LocalLog mConnectivityLocalLog;
    private final WifiNetworkSelector mWifiNetworkSelector;
    private final SavedNetworkNominator mSavedNetworkNominator;
    private final NetworkSuggestionNominator mNetworkSuggestionNominator;
    private final ScoredNetworkNominator mScoredNetworkNominator;
    private final WifiNetworkScoreCache mWifiNetworkScoreCache;
    private final NetworkScoreManager mNetworkScoreManager;
    private WifiScanner mWifiScanner;
    private final WifiPermissionsWrapper mWifiPermissionsWrapper;
    private final WifiPermissionsUtil mWifiPermissionsUtil;
    private final PasspointManager mPasspointManager;
    private HandlerThread mWifiAwareHandlerThread;
    private HandlerThread mRttHandlerThread;
    private HalDeviceManager mHalDeviceManager;
    private final WifiStateTracker mWifiStateTracker;
    private final SelfRecovery mSelfRecovery;
    private final WakeupController mWakeupController;
    private final ScanRequestProxy mScanRequestProxy;
    private final SarManager mSarManager;
    private final BaseWifiDiagnostics mWifiDiagnostics;
    private final WifiDataStall mWifiDataStall;
    private final WifiScoreCard mWifiScoreCard;
    private final WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    private final DppMetrics mDppMetrics;
    private final DppManager mDppManager;
    private final LinkProbeManager mLinkProbeManager;
    private IpMemoryStore mIpMemoryStore;
    private final WifiThreadRunner mWifiThreadRunner;
    private BssidBlocklistMonitor mBssidBlocklistMonitor;
    private final MacAddressUtil mMacAddressUtil;
    private final MboOceController mMboOceController;
    private final WifiCarrierInfoManager mWifiCarrierInfoManager;
    private WifiChannelUtilization mWifiChannelUtilizationScan;
    private WifiChannelUtilization mWifiChannelUtilizationConnected;
    private final KeyStore mKeyStore;
    private final ConnectionFailureNotificationBuilder mConnectionFailureNotificationBuilder;
    private final ThroughputPredictor mThroughputPredictor;
    private NetdWrapper mNetdWrapper;
    private final WifiHealthMonitor mWifiHealthMonitor;
    private final WifiSettingsConfigStore mSettingsConfigStore;
    private final WifiScanAlwaysAvailableSettingsCompatibility
            mWifiScanAlwaysAvailableSettingsCompatibility;
    private final SettingsMigrationDataHolder mSettingsMigrationDataHolder;
    private final LruConnectionTracker mLruConnectionTracker;

    public WifiInjector(WifiContext context) {
        if (context == null) {
            throw new IllegalStateException(
                    "WifiInjector should not be initialized with a null Context.");
        }

        if (sWifiInjector != null) {
            throw new IllegalStateException(
                    "WifiInjector was already created, use getInstance instead.");
        }

        sWifiInjector = this;

        // Now create and start handler threads
        mAsyncChannelHandlerThread = new HandlerThread("AsyncChannelHandlerThread");
        mAsyncChannelHandlerThread.start();
        mWifiHandlerThread = new HandlerThread("WifiHandlerThread");
        mWifiHandlerThread.start();
        Looper wifiLooper = mWifiHandlerThread.getLooper();
        Handler wifiHandler = new Handler(wifiLooper);

        mFrameworkFacade = new FrameworkFacade();
        mMacAddressUtil = new MacAddressUtil();
        mContext = context;
        mSettingsMigrationDataHolder = new SettingsMigrationDataHolder(mContext);
        mConnectionFailureNotificationBuilder = new ConnectionFailureNotificationBuilder(
                mContext, getWifiStackPackageName(), mFrameworkFacade);
        mBatteryStats = context.getSystemService(BatteryStatsManager.class);
        mWifiPermissionsWrapper = new WifiPermissionsWrapper(mContext);
        mNetworkScoreManager = mContext.getSystemService(NetworkScoreManager.class);
        mWifiNetworkScoreCache = new WifiNetworkScoreCache(mContext);
        mNetworkScoreManager.registerNetworkScoreCallback(NetworkKey.TYPE_WIFI,
                NetworkScoreManager.SCORE_FILTER_NONE,
                new HandlerExecutor(wifiHandler), mWifiNetworkScoreCache);
        mUserManager = mContext.getSystemService(UserManager.class);
        mWifiPermissionsUtil = new WifiPermissionsUtil(mWifiPermissionsWrapper, mContext,
                mUserManager, this);
        mWifiBackupRestore = new WifiBackupRestore(mWifiPermissionsUtil);
        mSoftApBackupRestore = new SoftApBackupRestore(mContext, mSettingsMigrationDataHolder);
        mWifiStateTracker = new WifiStateTracker(mBatteryStats);
        mWifiThreadRunner = new WifiThreadRunner(wifiHandler);
        mWifiP2pServiceHandlerThread = new HandlerThread("WifiP2pService");
        mWifiP2pServiceHandlerThread.start();
        mPasspointProvisionerHandlerThread =
                new HandlerThread("PasspointProvisionerHandlerThread");
        mPasspointProvisionerHandlerThread.start();
        WifiAwareMetrics awareMetrics = new WifiAwareMetrics(mClock);
        RttMetrics rttMetrics = new RttMetrics(mClock);
        mWifiP2pMetrics = new WifiP2pMetrics(mClock);
        mDppMetrics = new DppMetrics();
        mWifiMetrics = new WifiMetrics(mContext, mFrameworkFacade, mClock, wifiLooper,
                awareMetrics, rttMetrics, new WifiPowerMetrics(mBatteryStats), mWifiP2pMetrics,
                mDppMetrics);
        mDeviceConfigFacade = new DeviceConfigFacade(mContext, wifiHandler, mWifiMetrics);
        // Modules interacting with Native.
        mWifiMonitor = new WifiMonitor(this);
        mHalDeviceManager = new HalDeviceManager(mClock, wifiHandler);
        mWifiVendorHal = new WifiVendorHal(mHalDeviceManager, wifiHandler);
        mSupplicantStaIfaceHal = new SupplicantStaIfaceHal(
                mContext, mWifiMonitor, mFrameworkFacade, wifiHandler, mClock, mWifiMetrics);
        mHostapdHal = new HostapdHal(mContext, wifiHandler);
        mWifiCondManager = (WifiNl80211Manager) mContext.getSystemService(
                Context.WIFI_NL80211_SERVICE);
        mWifiNative = new WifiNative(
                mWifiVendorHal, mSupplicantStaIfaceHal, mHostapdHal, mWifiCondManager,
                mWifiMonitor, mPropertyService, mWifiMetrics,
                wifiHandler, new Random(), this);
        mWifiP2pMonitor = new WifiP2pMonitor(this);
        mSupplicantP2pIfaceHal = new SupplicantP2pIfaceHal(mWifiP2pMonitor);
        mWifiP2pNative = new WifiP2pNative(this,
                mWifiVendorHal, mSupplicantP2pIfaceHal, mHalDeviceManager,
                mPropertyService);

        // Now get instances of all the objects that depend on the HandlerThreads
        mWifiTrafficPoller = new WifiTrafficPoller(wifiHandler);
        mCountryCode = new WifiCountryCode(mContext, wifiHandler, mWifiNative,
                SystemProperties.get(BOOT_DEFAULT_WIFI_COUNTRY_CODE));
        // WifiConfigManager/Store objects and their dependencies.
        KeyStore keyStore = null;
        try {
            keyStore = AndroidKeyStoreProvider.getKeyStoreForUid(Process.WIFI_UID);
        } catch (KeyStoreException | NoSuchProviderException e) {
            Log.wtf(TAG, "Failed to load keystore", e);
        }
        mKeyStore = keyStore;
        mWifiKeyStore = new WifiKeyStore(mKeyStore);
        // New config store
        mWifiConfigStore = new WifiConfigStore(mContext, wifiHandler, mClock, mWifiMetrics,
                WifiConfigStore.createSharedFiles(mFrameworkFacade.isNiapModeOn(mContext)));
        SubscriptionManager subscriptionManager =
                mContext.getSystemService(SubscriptionManager.class);
        mWifiCarrierInfoManager = new WifiCarrierInfoManager(makeTelephonyManager(),
                subscriptionManager, this, mFrameworkFacade, mContext,
                mWifiConfigStore, wifiHandler, mWifiMetrics);
        String l2KeySeed = Secure.getString(mContext.getContentResolver(), Secure.ANDROID_ID);
        mWifiScoreCard = new WifiScoreCard(mClock, l2KeySeed, mDeviceConfigFacade);
        mWifiMetrics.setWifiScoreCard(mWifiScoreCard);
        mLruConnectionTracker = new LruConnectionTracker(MAX_RECENTLY_CONNECTED_NETWORK,
                mContext);
        // Config Manager
        mWifiConfigManager = new WifiConfigManager(mContext, mClock,
                mUserManager, mWifiCarrierInfoManager,
                mWifiKeyStore, mWifiConfigStore, mWifiPermissionsUtil,
                mWifiPermissionsWrapper, this,
                new NetworkListSharedStoreData(mContext),
                new NetworkListUserStoreData(mContext),
                new RandomizedMacStoreData(), mFrameworkFacade, wifiHandler, mDeviceConfigFacade,
                mWifiScoreCard, mLruConnectionTracker);
        mSettingsConfigStore = new WifiSettingsConfigStore(context, wifiHandler,
                mSettingsMigrationDataHolder, mWifiConfigManager, mWifiConfigStore);
        mSettingsStore = new WifiSettingsStore(mContext, mSettingsConfigStore);
        mWifiMetrics.setWifiConfigManager(mWifiConfigManager);

        mWifiConnectivityHelper = new WifiConnectivityHelper(mWifiNative);
        mConnectivityLocalLog = new LocalLog(
                mContext.getSystemService(ActivityManager.class).isLowRamDevice() ? 256 : 512);
        mScoringParams = new ScoringParams(mContext);
        mWifiMetrics.setScoringParams(mScoringParams);
        mThroughputPredictor = new ThroughputPredictor(mContext);
        mWifiNetworkSelector = new WifiNetworkSelector(mContext, mWifiScoreCard, mScoringParams,
                mWifiConfigManager, mClock, mConnectivityLocalLog, mWifiMetrics, mWifiNative,
                mThroughputPredictor);
        CompatibilityScorer compatibilityScorer = new CompatibilityScorer(mScoringParams);
        mWifiNetworkSelector.registerCandidateScorer(compatibilityScorer);
        ScoreCardBasedScorer scoreCardBasedScorer = new ScoreCardBasedScorer(mScoringParams);
        mWifiNetworkSelector.registerCandidateScorer(scoreCardBasedScorer);
        BubbleFunScorer bubbleFunScorer = new BubbleFunScorer(mScoringParams);
        mWifiNetworkSelector.registerCandidateScorer(bubbleFunScorer);
        ThroughputScorer throughputScorer = new ThroughputScorer(mScoringParams);
        mWifiNetworkSelector.registerCandidateScorer(throughputScorer);
        mWifiMetrics.setWifiNetworkSelector(mWifiNetworkSelector);
        mWifiNetworkSuggestionsManager = new WifiNetworkSuggestionsManager(mContext, wifiHandler,
                this, mWifiPermissionsUtil, mWifiConfigManager, mWifiConfigStore, mWifiMetrics,
                mWifiCarrierInfoManager, mWifiKeyStore, mLruConnectionTracker);
        mPasspointManager = new PasspointManager(mContext, this,
                wifiHandler, mWifiNative, mWifiKeyStore, mClock, new PasspointObjectFactory(),
                mWifiConfigManager, mWifiConfigStore, mWifiMetrics, mWifiCarrierInfoManager);
        PasspointNetworkNominateHelper nominateHelper =
                new PasspointNetworkNominateHelper(mPasspointManager, mWifiConfigManager,
                        mConnectivityLocalLog);
        mSavedNetworkNominator = new SavedNetworkNominator(
                mWifiConfigManager, nominateHelper, mConnectivityLocalLog, mWifiCarrierInfoManager,
                mWifiPermissionsUtil, mWifiNetworkSuggestionsManager);
        mNetworkSuggestionNominator = new NetworkSuggestionNominator(mWifiNetworkSuggestionsManager,
                mWifiConfigManager, nominateHelper, mConnectivityLocalLog, mWifiCarrierInfoManager);
        mScoredNetworkNominator = new ScoredNetworkNominator(mContext, wifiHandler,
                mFrameworkFacade, mNetworkScoreManager, mContext.getPackageManager(),
                mWifiConfigManager, mConnectivityLocalLog,
                mWifiNetworkScoreCache, mWifiPermissionsUtil);

        mWifiMetrics.setPasspointManager(mPasspointManager);
        mScanRequestProxy = new ScanRequestProxy(mContext,
                (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE),
                (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE),
                this, mWifiConfigManager,
                mWifiPermissionsUtil, mWifiMetrics, mClock, wifiHandler, mSettingsConfigStore);
        mSarManager = new SarManager(mContext, makeTelephonyManager(), wifiLooper,
                mWifiNative);
        mWifiDiagnostics = new WifiDiagnostics(
                mContext, this, mWifiNative, mBuildProperties,
                new LastMileLogger(this), mClock);
        mWifiChannelUtilizationConnected = new WifiChannelUtilization(mClock, mContext);
        mWifiDataStall = new WifiDataStall(mFrameworkFacade, mWifiMetrics, mContext,
                mDeviceConfigFacade, mWifiChannelUtilizationConnected, mClock, wifiHandler,
                mThroughputPredictor);
        mWifiMetrics.setWifiDataStall(mWifiDataStall);
        mLinkProbeManager = new LinkProbeManager(mClock, mWifiNative, mWifiMetrics,
                mFrameworkFacade, wifiHandler, mContext);
        SupplicantStateTracker supplicantStateTracker = new SupplicantStateTracker(
                mContext, mWifiConfigManager, mBatteryStats, wifiHandler);
        mMboOceController = new MboOceController(makeTelephonyManager(), mWifiNative);
        mWifiHealthMonitor = new WifiHealthMonitor(mContext, this, mClock, mWifiConfigManager,
                mWifiScoreCard, wifiHandler, mWifiNative, l2KeySeed, mDeviceConfigFacade);
        mWifiMetrics.setWifiHealthMonitor(mWifiHealthMonitor);
        mClientModeImpl = new ClientModeImpl(mContext, mFrameworkFacade,
                wifiLooper, mUserManager,
                this, mBackupManagerProxy, mCountryCode, mWifiNative,
                new WrongPasswordNotifier(mContext, mFrameworkFacade),
                mSarManager, mWifiTrafficPoller, mLinkProbeManager, mBatteryStats,
                supplicantStateTracker, mMboOceController, mWifiCarrierInfoManager,
                new EapFailureNotifier(mContext, mFrameworkFacade, mWifiCarrierInfoManager),
                new SimRequiredNotifier(mContext, mFrameworkFacade));
        mActiveModeWarden = new ActiveModeWarden(this, wifiLooper,
                mWifiNative, new DefaultModeManager(mContext), mBatteryStats, mWifiDiagnostics,
                mContext, mClientModeImpl, mSettingsStore, mFrameworkFacade, mWifiPermissionsUtil);
        mWifiScanAlwaysAvailableSettingsCompatibility =
                new WifiScanAlwaysAvailableSettingsCompatibility(mContext, wifiHandler,
                        mSettingsStore, mActiveModeWarden, mFrameworkFacade);
        mWifiApConfigStore = new WifiApConfigStore(
                mContext, this, wifiHandler, mBackupManagerProxy,
                mWifiConfigStore, mWifiConfigManager, mActiveModeWarden, mWifiMetrics);
        WakeupNotificationFactory wakeupNotificationFactory =
                new WakeupNotificationFactory(mContext, this, mFrameworkFacade);
        WakeupOnboarding wakeupOnboarding = new WakeupOnboarding(mContext, mWifiConfigManager,
                wifiHandler, mFrameworkFacade, wakeupNotificationFactory);
        mWakeupController = new WakeupController(mContext, wifiHandler,
                new WakeupLock(mWifiConfigManager, mWifiMetrics.getWakeupMetrics(), mClock),
                new WakeupEvaluator(mScoringParams), wakeupOnboarding, mWifiConfigManager,
                mWifiConfigStore, mWifiNetworkSuggestionsManager, mWifiMetrics.getWakeupMetrics(),
                this, mFrameworkFacade, mClock);
        mLockManager = new WifiLockManager(mContext, mBatteryStats,
                mClientModeImpl, mFrameworkFacade, wifiHandler, mWifiNative, mClock, mWifiMetrics);
        mSelfRecovery = new SelfRecovery(mContext, mActiveModeWarden, mClock);
        mWifiMulticastLockManager = new WifiMulticastLockManager(
                mClientModeImpl.getMcastLockManagerFilterController(), mBatteryStats);
        mDppManager = new DppManager(wifiHandler, mWifiNative,
                mWifiConfigManager, mContext, mDppMetrics, mScanRequestProxy);

        // Register the various network Nominators with the network selector.
        mWifiNetworkSelector.registerNetworkNominator(mSavedNetworkNominator);
        mWifiNetworkSelector.registerNetworkNominator(mNetworkSuggestionNominator);
        mWifiNetworkSelector.registerNetworkNominator(mScoredNetworkNominator);

        mClientModeImpl.start();
    }

    /**
     *  Obtain an instance of the WifiInjector class.
     *
     *  This is the generic method to get an instance of the class. The first instance should be
     *  retrieved using the getInstanceWithContext method.
     */
    public static WifiInjector getInstance() {
        if (sWifiInjector == null) {
            throw new IllegalStateException(
                    "Attempted to retrieve a WifiInjector instance before constructor was called.");
        }
        return sWifiInjector;
    }

    /**
     * Enable verbose logging in Injector objects. Called from the WifiServiceImpl (based on
     * binder call).
     */
    public void enableVerboseLogging(int verbose) {
        mWifiLastResortWatchdog.enableVerboseLogging(verbose);
        mWifiBackupRestore.enableVerboseLogging(verbose);
        mHalDeviceManager.enableVerboseLogging(verbose);
        mScanRequestProxy.enableVerboseLogging(verbose);
        mWakeupController.enableVerboseLogging(verbose);
        mWifiNetworkSuggestionsManager.enableVerboseLogging(verbose);
        LogcatLog.enableVerboseLogging(verbose);
        mDppManager.enableVerboseLogging(verbose);
        mWifiCarrierInfoManager.enableVerboseLogging(verbose);
    }

    public UserManager getUserManager() {
        return mUserManager;
    }

    public WifiMetrics getWifiMetrics() {
        return mWifiMetrics;
    }

    public WifiP2pMetrics getWifiP2pMetrics() {
        return mWifiP2pMetrics;
    }

    public SupplicantStaIfaceHal getSupplicantStaIfaceHal() {
        return mSupplicantStaIfaceHal;
    }

    public BackupManagerProxy getBackupManagerProxy() {
        return mBackupManagerProxy;
    }

    public FrameworkFacade getFrameworkFacade() {
        return mFrameworkFacade;
    }

    public HandlerThread getAsyncChannelHandlerThread() {
        return mAsyncChannelHandlerThread;
    }

    public HandlerThread getWifiP2pServiceHandlerThread() {
        return mWifiP2pServiceHandlerThread;
    }

    public HandlerThread getPasspointProvisionerHandlerThread() {
        return mPasspointProvisionerHandlerThread;
    }

    public HandlerThread getWifiHandlerThread() {
        return mWifiHandlerThread;
    }

    public WifiTrafficPoller getWifiTrafficPoller() {
        return mWifiTrafficPoller;
    }

    public WifiCountryCode getWifiCountryCode() {
        return mCountryCode;
    }

    public WifiApConfigStore getWifiApConfigStore() {
        return mWifiApConfigStore;
    }

    public SarManager getSarManager() {
        return mSarManager;
    }

    public ClientModeImpl getClientModeImpl() {
        return mClientModeImpl;
    }

    public ActiveModeWarden getActiveModeWarden() {
        return mActiveModeWarden;
    }

    public WifiSettingsStore getWifiSettingsStore() {
        return mSettingsStore;
    }

    public WifiLockManager getWifiLockManager() {
        return mLockManager;
    }

    public WifiLastResortWatchdog getWifiLastResortWatchdog() {
        return mWifiLastResortWatchdog;
    }

    public Clock getClock() {
        return mClock;
    }

    public PropertyService getPropertyService() {
        return mPropertyService;
    }

    public BuildProperties getBuildProperties() {
        return mBuildProperties;
    }

    public WifiBackupRestore getWifiBackupRestore() {
        return mWifiBackupRestore;
    }

    public SoftApBackupRestore getSoftApBackupRestore() {
        return mSoftApBackupRestore;
    }

    public WifiMulticastLockManager getWifiMulticastLockManager() {
        return mWifiMulticastLockManager;
    }

    public WifiConfigManager getWifiConfigManager() {
        return mWifiConfigManager;
    }

    public PasspointManager getPasspointManager() {
        return mPasspointManager;
    }

    public WakeupController getWakeupController() {
        return mWakeupController;
    }

    public ScoringParams getScoringParams() {
        return mScoringParams;
    }

    public WifiScoreCard getWifiScoreCard() {
        return mWifiScoreCard;
    }

    public TelephonyManager makeTelephonyManager() {
        return (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
    }

    public WifiCarrierInfoManager getWifiCarrierInfoManager() {
        return mWifiCarrierInfoManager;
    }

    public WifiStateTracker getWifiStateTracker() {
        return mWifiStateTracker;
    }

    public DppManager getDppManager() {
        return mDppManager;
    }

    /**
     * Create a SoftApManager.
     * @param config SoftApModeConfiguration object holding the config and mode
     * @return an instance of SoftApManager
     */
    public SoftApManager makeSoftApManager(@NonNull ActiveModeManager.Listener listener,
                                           @NonNull WifiManager.SoftApCallback callback,
                                           @NonNull SoftApModeConfiguration config) {
        return new SoftApManager(mContext, mWifiHandlerThread.getLooper(),
                mFrameworkFacade, mWifiNative, mCountryCode.getCountryCode(), listener, callback,
                mWifiApConfigStore, config, mWifiMetrics, mSarManager, mWifiDiagnostics);
    }

    /**
     * Create a ClientModeManager
     *
     * @param listener listener for ClientModeManager state changes
     * @return a new instance of ClientModeManager
     */
    public ClientModeManager makeClientModeManager(ClientModeManager.Listener listener) {
        return new ClientModeManager(mContext, mWifiHandlerThread.getLooper(), mClock,
                mWifiNative, listener, mWifiMetrics, mSarManager, mWakeupController,
                mClientModeImpl);
    }

    /**
     * Create a WifiLog instance.
     * @param tag module name to include in all log messages
     */
    public WifiLog makeLog(String tag) {
        return new LogcatLog(tag);
    }

    public BaseWifiDiagnostics getWifiDiagnostics() {
        return mWifiDiagnostics;
    }

    /**
     * Obtain an instance of WifiScanner.
     * If it was not already created, then obtain an instance.  Note, this must be done lazily since
     * WifiScannerService is separate and created later.
     */
    public synchronized WifiScanner getWifiScanner() {
        if (mWifiScanner == null) {
            mWifiScanner = mContext.getSystemService(WifiScanner.class);
        }
        return mWifiScanner;
    }

    /**
     * Construct a new instance of WifiConnectivityManager & its dependencies.
     *
     * Create and return a new WifiConnectivityManager.
     * @param clientModeImpl Instance of client mode impl.
     * TODO(b/116233964): Remove cyclic dependency between WifiConnectivityManager & ClientModeImpl.
     */
    public WifiConnectivityManager makeWifiConnectivityManager(ClientModeImpl clientModeImpl) {
        mOpenNetworkNotifier = new OpenNetworkNotifier(mContext,
                mWifiHandlerThread.getLooper(), mFrameworkFacade, mClock, mWifiMetrics,
                mWifiConfigManager, mWifiConfigStore, clientModeImpl,
                new ConnectToNetworkNotificationBuilder(mContext, this, mFrameworkFacade));
        mWifiLastResortWatchdog = new WifiLastResortWatchdog(this, mContext, mClock,
                mWifiMetrics, clientModeImpl, mWifiHandlerThread.getLooper(), mDeviceConfigFacade,
                mWifiThreadRunner);
        mBssidBlocklistMonitor = new BssidBlocklistMonitor(mContext, mWifiConnectivityHelper,
                mWifiLastResortWatchdog, mClock, mConnectivityLocalLog, mWifiScoreCard);
        mWifiMetrics.setBssidBlocklistMonitor(mBssidBlocklistMonitor);
        mWifiChannelUtilizationScan = new WifiChannelUtilization(mClock, mContext);
        return new WifiConnectivityManager(mContext, getScoringParams(),
                clientModeImpl, this,
                mWifiConfigManager, mWifiNetworkSuggestionsManager, clientModeImpl.getWifiInfo(),
                mWifiNetworkSelector, mWifiConnectivityHelper,
                mWifiLastResortWatchdog, mOpenNetworkNotifier,
                mWifiMetrics, new Handler(mWifiHandlerThread.getLooper()),
                mClock, mConnectivityLocalLog, mWifiScoreCard);
    }

    /**
     * Construct a new instance of ConnectionFailureNotifier.
     * @param wifiConnectivityManager
     * @return the created instance
     */
    public ConnectionFailureNotifier makeConnectionFailureNotifier(
            WifiConnectivityManager wifiConnectivityManager) {
        return new ConnectionFailureNotifier(mContext, this, mFrameworkFacade, mWifiConfigManager,
                wifiConnectivityManager, new Handler(mWifiHandlerThread.getLooper()));
    }

    /**
     * Construct a new instance of {@link WifiNetworkFactory}.
     * TODO(b/116233964): Remove cyclic dependency between WifiConnectivityManager & ClientModeImpl.
     */
    public WifiNetworkFactory makeWifiNetworkFactory(
            NetworkCapabilities nc, WifiConnectivityManager wifiConnectivityManager) {
        return new WifiNetworkFactory(
                mWifiHandlerThread.getLooper(), mContext, nc,
                (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE),
                (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE),
                (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE),
                mClock, this, wifiConnectivityManager, mWifiConfigManager,
                mWifiConfigStore, mWifiPermissionsUtil, mWifiMetrics);
    }

    /**
     * Construct an instance of {@link NetworkRequestStoreData}.
     */
    public NetworkRequestStoreData makeNetworkRequestStoreData(
            NetworkRequestStoreData.DataSource dataSource) {
        return new NetworkRequestStoreData(dataSource);
    }

    /**
     * Construct a new instance of {@link UntrustedWifiNetworkFactory}.
     * TODO(b/116233964): Remove cyclic dependency between WifiConnectivityManager & ClientModeImpl.
     */
    public UntrustedWifiNetworkFactory makeUntrustedWifiNetworkFactory(
            NetworkCapabilities nc, WifiConnectivityManager wifiConnectivityManager) {
        return new UntrustedWifiNetworkFactory(
                mWifiHandlerThread.getLooper(), mContext, nc, wifiConnectivityManager);
    }

    /**
     * Construct an instance of {@link NetworkSuggestionStoreData}.
     */
    public NetworkSuggestionStoreData makeNetworkSuggestionStoreData(
            NetworkSuggestionStoreData.DataSource dataSource) {
        return new NetworkSuggestionStoreData(dataSource);
    }

    /**
     *
     */
    public ImsiPrivacyProtectionExemptionStoreData makeImsiProtectionExemptionStoreData(
            ImsiPrivacyProtectionExemptionStoreData.DataSource dataSource) {
        return new ImsiPrivacyProtectionExemptionStoreData(dataSource);
    }

    /**
     * Construct an instance of {@link SoftApStoreData}.
     */
    public SoftApStoreData makeSoftApStoreData(
            SoftApStoreData.DataSource dataSource) {
        return new SoftApStoreData(mContext, mSettingsMigrationDataHolder, dataSource);
    }

    public WifiPermissionsUtil getWifiPermissionsUtil() {
        return mWifiPermissionsUtil;
    }

    public WifiPermissionsWrapper getWifiPermissionsWrapper() {
        return mWifiPermissionsWrapper;
    }

    /**
     * Returns a singleton instance of a HandlerThread for injection. Uses lazy initialization.
     *
     * TODO: share worker thread with other Wi-Fi handlers (b/27924886)
     */
    public HandlerThread getWifiAwareHandlerThread() {
        if (mWifiAwareHandlerThread == null) { // lazy initialization
            mWifiAwareHandlerThread = new HandlerThread("wifiAwareService");
            mWifiAwareHandlerThread.start();
        }
        return mWifiAwareHandlerThread;
    }

    /**
     * Returns a singleton instance of a HandlerThread for injection. Uses lazy initialization.
     *
     * TODO: share worker thread with other Wi-Fi handlers (b/27924886)
     */
    public HandlerThread getRttHandlerThread() {
        if (mRttHandlerThread == null) { // lazy initialization
            mRttHandlerThread = new HandlerThread("wifiRttService");
            mRttHandlerThread.start();
        }
        return mRttHandlerThread;
    }

    public MacAddressUtil getMacAddressUtil() {
        return mMacAddressUtil;
    }

    public NotificationManager getNotificationManager() {
        return (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    public ConnectionFailureNotificationBuilder getConnectionFailureNotificationBuilder() {
        return mConnectionFailureNotificationBuilder;
    }

    /**
     * Returns a single instance of HalDeviceManager for injection.
     */
    public HalDeviceManager getHalDeviceManager() {
        return mHalDeviceManager;
    }

    public WifiNative getWifiNative() {
        return mWifiNative;
    }

    public WifiMonitor getWifiMonitor() {
        return mWifiMonitor;
    }

    public WifiP2pNative getWifiP2pNative() {
        return mWifiP2pNative;
    }

    public WifiP2pMonitor getWifiP2pMonitor() {
        return mWifiP2pMonitor;
    }

    public SelfRecovery getSelfRecovery() {
        return mSelfRecovery;
    }

    public ScanRequestProxy getScanRequestProxy() {
        return mScanRequestProxy;
    }

    public Runtime getJavaRuntime() {
        return Runtime.getRuntime();
    }

    public WifiDataStall getWifiDataStall() {
        return mWifiDataStall;
    }

    public WifiNetworkSuggestionsManager getWifiNetworkSuggestionsManager() {
        return mWifiNetworkSuggestionsManager;
    }

    public IpMemoryStore getIpMemoryStore() {
        if (mIpMemoryStore == null) {
            mIpMemoryStore = IpMemoryStore.getMemoryStore(mContext);
        }
        return mIpMemoryStore;
    }

    public BssidBlocklistMonitor getBssidBlocklistMonitor() {
        return mBssidBlocklistMonitor;
    }

    public HostapdHal getHostapdHal() {
        return mHostapdHal;
    }

    public String getWifiStackPackageName() {
       return mContext.getPackageName();
    }

    public WifiThreadRunner getWifiThreadRunner() {
        return mWifiThreadRunner;
    }

    public WifiChannelUtilization getWifiChannelUtilizationScan() {
        return mWifiChannelUtilizationScan;
    }

    public WifiNetworkScoreCache getWifiNetworkScoreCache() {
        return mWifiNetworkScoreCache;
    }

    public NetdWrapper makeNetdWrapper() {
        if (mNetdWrapper == null) {
            mNetdWrapper = new NetdWrapper(mContext, new Handler(mWifiHandlerThread.getLooper()));
        }
        return mNetdWrapper;
    }

    public WifiNl80211Manager getWifiCondManager() {
        return mWifiCondManager;
    }

    public WifiHealthMonitor getWifiHealthMonitor() {
        return mWifiHealthMonitor;
    }

    public ThroughputPredictor getThroughputPredictor() {
        return mThroughputPredictor;
    }

    public WifiSettingsConfigStore getSettingsConfigStore() {
        return mSettingsConfigStore;
    }

    public WifiScanAlwaysAvailableSettingsCompatibility
            getWifiScanAlwaysAvailableSettingsCompatibility() {
        return mWifiScanAlwaysAvailableSettingsCompatibility;
    }

    public DeviceConfigFacade getDeviceConfigFacade() {
        return mDeviceConfigFacade;
    }
}
