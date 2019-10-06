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
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.SystemSensorManager;
import android.net.IpMemoryStore;
import android.net.NetworkCapabilities;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.wifi.IWifiScanner;
import android.net.wifi.IWificond;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkScoreCache;
import android.net.wifi.WifiScanner;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserManager;
import android.provider.Settings.Secure;
import android.security.KeyStore;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.LocalLog;

import com.android.internal.R;
import com.android.internal.app.IBatteryStats;
import com.android.internal.os.PowerProfile;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.BatteryStatsService;
import com.android.server.wifi.aware.WifiAwareMetrics;
import com.android.server.wifi.hotspot2.PasspointManager;
import com.android.server.wifi.hotspot2.PasspointNetworkEvaluator;
import com.android.server.wifi.hotspot2.PasspointObjectFactory;
import com.android.server.wifi.p2p.SupplicantP2pIfaceHal;
import com.android.server.wifi.p2p.WifiP2pMetrics;
import com.android.server.wifi.p2p.WifiP2pMonitor;
import com.android.server.wifi.p2p.WifiP2pNative;
import com.android.server.wifi.rtt.RttMetrics;
import com.android.server.wifi.util.WifiPermissionsUtil;
import com.android.server.wifi.util.WifiPermissionsWrapper;

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
    private static final String BOOT_DEFAULT_WIFI_COUNTRY_CODE = "ro.boot.wificountrycode";
    private static final String WIFICOND_SERVICE_NAME = "wificond";

    static WifiInjector sWifiInjector = null;

    private final Context mContext;
    private final FrameworkFacade mFrameworkFacade = new FrameworkFacade();
    private final HandlerThread mWifiServiceHandlerThread;
    private final HandlerThread mWifiCoreHandlerThread;
    private final HandlerThread mWifiP2pServiceHandlerThread;
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
    private CarrierNetworkNotifier mCarrierNetworkNotifier;
    private final CarrierNetworkConfig mCarrierNetworkConfig;
    private final WifiLockManager mLockManager;
    private final WifiController mWifiController;
    private final WificondControl mWificondControl;
    private final Clock mClock = new Clock();
    private final WifiMetrics mWifiMetrics;
    private final WifiP2pMetrics mWifiP2pMetrics;
    private WifiLastResortWatchdog mWifiLastResortWatchdog;
    private final PropertyService mPropertyService = new SystemPropertyService();
    private final BuildProperties mBuildProperties = new SystemBuildProperties();
    private final KeyStore mKeyStore = KeyStore.getInstance();
    private final WifiBackupRestore mWifiBackupRestore;
    private final WifiMulticastLockManager mWifiMulticastLockManager;
    private final WifiConfigStore mWifiConfigStore;
    private final WifiKeyStore mWifiKeyStore;
    private final WifiConfigManager mWifiConfigManager;
    private final WifiConnectivityHelper mWifiConnectivityHelper;
    private final LocalLog mConnectivityLocalLog;
    private final WifiNetworkSelector mWifiNetworkSelector;
    private final SavedNetworkEvaluator mSavedNetworkEvaluator;
    private final NetworkSuggestionEvaluator mNetworkSuggestionEvaluator;
    private final PasspointNetworkEvaluator mPasspointNetworkEvaluator;
    private final ScoredNetworkEvaluator mScoredNetworkEvaluator;
    private final CarrierNetworkEvaluator mCarrierNetworkEvaluator;
    private final WifiNetworkScoreCache mWifiNetworkScoreCache;
    private final NetworkScoreManager mNetworkScoreManager;
    private WifiScanner mWifiScanner;
    private final WifiPermissionsWrapper mWifiPermissionsWrapper;
    private final WifiPermissionsUtil mWifiPermissionsUtil;
    private final PasspointManager mPasspointManager;
    private final SIMAccessor mSimAccessor;
    private HandlerThread mWifiAwareHandlerThread;
    private HandlerThread mRttHandlerThread;
    private HalDeviceManager mHalDeviceManager;
    private final IBatteryStats mBatteryStats;
    private final WifiStateTracker mWifiStateTracker;
    private final SelfRecovery mSelfRecovery;
    private final WakeupController mWakeupController;
    private final INetworkManagementService mNwManagementService;
    private final ScanRequestProxy mScanRequestProxy;
    private final SarManager mSarManager;
    private final BaseWifiDiagnostics mWifiDiagnostics;
    private final WifiDataStall mWifiDataStall;
    private final WifiScoreCard mWifiScoreCard;
    private final WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    private final DppMetrics mDppMetrics;
    private final DppManager mDppManager;
    private final LinkProbeManager mLinkProbeManager;
    private final IpMemoryStore mIpMemoryStore;
    private final CellularLinkLayerStatsCollector mCellularLinkLayerStatsCollector;

    public WifiInjector(Context context) {
        if (context == null) {
            throw new IllegalStateException(
                    "WifiInjector should not be initialized with a null Context.");
        }

        if (sWifiInjector != null) {
            throw new IllegalStateException(
                    "WifiInjector was already created, use getInstance instead.");
        }

        sWifiInjector = this;

        mContext = context;
        mWifiScoreCard = new WifiScoreCard(mClock,
                Secure.getString(mContext.getContentResolver(), Secure.ANDROID_ID));
        mSettingsStore = new WifiSettingsStore(mContext);
        mWifiPermissionsWrapper = new WifiPermissionsWrapper(mContext);
        mNetworkScoreManager = mContext.getSystemService(NetworkScoreManager.class);
        mWifiNetworkScoreCache = new WifiNetworkScoreCache(mContext);
        mNetworkScoreManager.registerNetworkScoreCache(NetworkKey.TYPE_WIFI,
                mWifiNetworkScoreCache, NetworkScoreManager.CACHE_FILTER_NONE);
        mWifiPermissionsUtil = new WifiPermissionsUtil(mWifiPermissionsWrapper, mContext,
                UserManager.get(mContext), this);
        mWifiBackupRestore = new WifiBackupRestore(mWifiPermissionsUtil);
        mBatteryStats = IBatteryStats.Stub.asInterface(mFrameworkFacade.getService(
                BatteryStats.SERVICE_NAME));
        mWifiStateTracker = new WifiStateTracker(mBatteryStats);
        // Now create and start handler threads
        mWifiServiceHandlerThread = new HandlerThread("WifiService");
        mWifiServiceHandlerThread.start();
        mWifiCoreHandlerThread = new HandlerThread("ClientModeImpl");
        mWifiCoreHandlerThread.start();
        mWifiP2pServiceHandlerThread = new HandlerThread("WifiP2pService");
        mWifiP2pServiceHandlerThread.start();
        Looper clientModeImplLooper = mWifiCoreHandlerThread.getLooper();
        mCarrierNetworkConfig = new CarrierNetworkConfig(mContext,
                clientModeImplLooper, mFrameworkFacade);
        WifiAwareMetrics awareMetrics = new WifiAwareMetrics(mClock);
        RttMetrics rttMetrics = new RttMetrics(mClock);
        mWifiP2pMetrics = new WifiP2pMetrics(mClock);
        mDppMetrics = new DppMetrics();
        mCellularLinkLayerStatsCollector = new CellularLinkLayerStatsCollector(mContext);
        mWifiMetrics = new WifiMetrics(mContext, mFrameworkFacade, mClock, clientModeImplLooper,
                awareMetrics, rttMetrics, new WifiPowerMetrics(), mWifiP2pMetrics, mDppMetrics,
                mCellularLinkLayerStatsCollector);
        // Modules interacting with Native.
        mWifiMonitor = new WifiMonitor(this);
        mHalDeviceManager = new HalDeviceManager(mClock);
        mWifiVendorHal =
                new WifiVendorHal(mHalDeviceManager, mWifiCoreHandlerThread.getLooper());
        mSupplicantStaIfaceHal =
                new SupplicantStaIfaceHal(mContext, mWifiMonitor, mPropertyService,
                        clientModeImplLooper);
        mHostapdHal = new HostapdHal(mContext, clientModeImplLooper);
        mWificondControl = new WificondControl(this, mWifiMonitor, mCarrierNetworkConfig,
                (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE),
                clientModeImplLooper, mClock);
        mNwManagementService = INetworkManagementService.Stub.asInterface(
                ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE));
        mWifiNative = new WifiNative(
                mWifiVendorHal, mSupplicantStaIfaceHal, mHostapdHal, mWificondControl,
                mWifiMonitor, mNwManagementService, mPropertyService, mWifiMetrics,
                new Handler(mWifiCoreHandlerThread.getLooper()), new Random());
        mWifiP2pMonitor = new WifiP2pMonitor(this);
        mSupplicantP2pIfaceHal = new SupplicantP2pIfaceHal(mWifiP2pMonitor);
        mWifiP2pNative = new WifiP2pNative(
                mWifiVendorHal, mSupplicantP2pIfaceHal, mHalDeviceManager,
                mPropertyService);

        // Now get instances of all the objects that depend on the HandlerThreads
        mWifiTrafficPoller = new WifiTrafficPoller(clientModeImplLooper);
        mCountryCode = new WifiCountryCode(mWifiNative,
                SystemProperties.get(BOOT_DEFAULT_WIFI_COUNTRY_CODE),
                mContext.getResources()
                        .getBoolean(R.bool.config_wifi_revert_country_code_on_cellular_loss));
        mWifiApConfigStore = new WifiApConfigStore(
                mContext, mWifiCoreHandlerThread.getLooper(), mBackupManagerProxy,
                mFrameworkFacade);

        // WifiConfigManager/Store objects and their dependencies.
        // New config store
        mWifiKeyStore = new WifiKeyStore(mKeyStore);
        mWifiConfigStore = new WifiConfigStore(
                mContext, clientModeImplLooper, mClock, mWifiMetrics,
                WifiConfigStore.createSharedFile());
        SubscriptionManager subscriptionManager =
                mContext.getSystemService(SubscriptionManager.class);
        // Config Manager
        mWifiConfigManager = new WifiConfigManager(mContext, mClock,
                UserManager.get(mContext), makeTelephonyManager(),
                mWifiKeyStore, mWifiConfigStore, mWifiPermissionsUtil,
                mWifiPermissionsWrapper, this, new NetworkListSharedStoreData(mContext),
                new NetworkListUserStoreData(mContext),
                new DeletedEphemeralSsidsStoreData(mClock), new RandomizedMacStoreData(),
                mFrameworkFacade, mWifiCoreHandlerThread.getLooper());
        mWifiMetrics.setWifiConfigManager(mWifiConfigManager);
        mWifiConnectivityHelper = new WifiConnectivityHelper(mWifiNative);
        mConnectivityLocalLog = new LocalLog(ActivityManager.isLowRamDeviceStatic() ? 256 : 512);
        mScoringParams = new ScoringParams(mContext, mFrameworkFacade,
                new Handler(clientModeImplLooper));
        mWifiMetrics.setScoringParams(mScoringParams);
        mWifiNetworkSelector = new WifiNetworkSelector(mContext, mWifiScoreCard, mScoringParams,
                mWifiConfigManager, mClock, mConnectivityLocalLog, mWifiMetrics, mWifiNative);
        CompatibilityScorer compatibilityScorer = new CompatibilityScorer(mScoringParams);
        mWifiNetworkSelector.registerCandidateScorer(compatibilityScorer);
        ScoreCardBasedScorer scoreCardBasedScorer = new ScoreCardBasedScorer(mScoringParams);
        mWifiNetworkSelector.registerCandidateScorer(scoreCardBasedScorer);
        BubbleFunScorer bubbleFunScorer = new BubbleFunScorer(mScoringParams);
        mWifiNetworkSelector.registerCandidateScorer(bubbleFunScorer);
        mWifiMetrics.setWifiNetworkSelector(mWifiNetworkSelector);
        mSavedNetworkEvaluator = new SavedNetworkEvaluator(mContext, mScoringParams,
                mWifiConfigManager, mClock, mConnectivityLocalLog, mWifiConnectivityHelper,
                subscriptionManager);
        mWifiNetworkSuggestionsManager = new WifiNetworkSuggestionsManager(mContext,
                new Handler(mWifiCoreHandlerThread.getLooper()), this,
                mWifiPermissionsUtil, mWifiConfigManager, mWifiConfigStore, mWifiMetrics);
        mNetworkSuggestionEvaluator = new NetworkSuggestionEvaluator(mWifiNetworkSuggestionsManager,
                mWifiConfigManager, mConnectivityLocalLog);
        mScoredNetworkEvaluator = new ScoredNetworkEvaluator(context, clientModeImplLooper,
                mFrameworkFacade, mNetworkScoreManager, mWifiConfigManager, mConnectivityLocalLog,
                mWifiNetworkScoreCache, mWifiPermissionsUtil);
        mCarrierNetworkEvaluator = new CarrierNetworkEvaluator(mWifiConfigManager,
                mCarrierNetworkConfig, mConnectivityLocalLog, this);
        mSimAccessor = new SIMAccessor(mContext);
        mPasspointManager = new PasspointManager(mContext, this,
                new Handler(mWifiCoreHandlerThread.getLooper()), mWifiNative, mWifiKeyStore, mClock,
                mSimAccessor, new PasspointObjectFactory(), mWifiConfigManager, mWifiConfigStore,
                mWifiMetrics, makeTelephonyManager(), subscriptionManager);
        mPasspointNetworkEvaluator = new PasspointNetworkEvaluator(
                mPasspointManager, mWifiConfigManager, mConnectivityLocalLog,
                mCarrierNetworkConfig, this, subscriptionManager);
        mWifiMetrics.setPasspointManager(mPasspointManager);
        mScanRequestProxy = new ScanRequestProxy(mContext,
                (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE),
                (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE),
                this, mWifiConfigManager,
                mWifiPermissionsUtil, mWifiMetrics, mClock, mFrameworkFacade,
                new Handler(clientModeImplLooper));
        mSarManager = new SarManager(mContext, makeTelephonyManager(), clientModeImplLooper,
                mWifiNative, new SystemSensorManager(mContext, clientModeImplLooper),
                mWifiMetrics);
        mWifiDiagnostics = new WifiDiagnostics(
                mContext, this, mWifiNative, mBuildProperties,
                new LastMileLogger(this), mClock);
        mWifiDataStall = new WifiDataStall(mContext, mFrameworkFacade, mWifiMetrics);
        mWifiMetrics.setWifiDataStall(mWifiDataStall);
        mLinkProbeManager = new LinkProbeManager(mClock, mWifiNative, mWifiMetrics,
                mFrameworkFacade, mWifiCoreHandlerThread.getLooper(), mContext);
        mClientModeImpl = new ClientModeImpl(mContext, mFrameworkFacade,
                clientModeImplLooper, UserManager.get(mContext),
                this, mBackupManagerProxy, mCountryCode, mWifiNative,
                new WrongPasswordNotifier(mContext, mFrameworkFacade),
                mSarManager, mWifiTrafficPoller, mLinkProbeManager);
        mActiveModeWarden = new ActiveModeWarden(this, mContext, clientModeImplLooper,
                mWifiNative, new DefaultModeManager(mContext, clientModeImplLooper),
                mBatteryStats);

        WakeupNotificationFactory wakeupNotificationFactory =
                new WakeupNotificationFactory(mContext, mFrameworkFacade);
        WakeupOnboarding wakeupOnboarding = new WakeupOnboarding(mContext, mWifiConfigManager,
                mWifiCoreHandlerThread.getLooper(), mFrameworkFacade,
                wakeupNotificationFactory);
        mWakeupController = new WakeupController(mContext,
                mWifiCoreHandlerThread.getLooper(),
                new WakeupLock(mWifiConfigManager, mWifiMetrics.getWakeupMetrics(), mClock),
                new WakeupEvaluator(mScoringParams), wakeupOnboarding, mWifiConfigManager,
                mWifiConfigStore, mWifiNetworkSuggestionsManager, mWifiMetrics.getWakeupMetrics(),
                this, mFrameworkFacade, mClock);
        mLockManager = new WifiLockManager(mContext, BatteryStatsService.getService(),
                mClientModeImpl, mFrameworkFacade, new Handler(clientModeImplLooper), mWifiNative,
                mClock, mWifiMetrics);
        mWifiController = new WifiController(mContext, mClientModeImpl, clientModeImplLooper,
                mSettingsStore, mWifiServiceHandlerThread.getLooper(), mFrameworkFacade,
                mActiveModeWarden, mWifiPermissionsUtil);
        mSelfRecovery = new SelfRecovery(mWifiController, mClock);
        mWifiMulticastLockManager = new WifiMulticastLockManager(
                mClientModeImpl.getMcastLockManagerFilterController(),
                BatteryStatsService.getService());
        mDppManager = new DppManager(mWifiCoreHandlerThread.getLooper(), mWifiNative,
                mWifiConfigManager, mContext, mDppMetrics);
        mIpMemoryStore = IpMemoryStore.getMemoryStore(mContext);

        // Register the various network evaluators with the network selector.
        mWifiNetworkSelector.registerNetworkEvaluator(mSavedNetworkEvaluator);
        mWifiNetworkSelector.registerNetworkEvaluator(mNetworkSuggestionEvaluator);
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_PASSPOINT)) {
            mWifiNetworkSelector.registerNetworkEvaluator(mPasspointNetworkEvaluator);
        }
        mWifiNetworkSelector.registerNetworkEvaluator(mCarrierNetworkEvaluator);
        mWifiNetworkSelector.registerNetworkEvaluator(mScoredNetworkEvaluator);

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
        mCarrierNetworkConfig.enableVerboseLogging(verbose);
        mWifiNetworkSuggestionsManager.enableVerboseLogging(verbose);
        LogcatLog.enableVerboseLogging(verbose);
        mDppManager.enableVerboseLogging(verbose);
    }

    public UserManager getUserManager() {
        return UserManager.get(mContext);
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

    public HandlerThread getWifiServiceHandlerThread() {
        return mWifiServiceHandlerThread;
    }

    public HandlerThread getWifiP2pServiceHandlerThread() {
        return mWifiP2pServiceHandlerThread;
    }

    public HandlerThread getWifiCoreHandlerThread() {
        return mWifiCoreHandlerThread;
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

    public Handler getClientModeImplHandler() {
        return mClientModeImpl.getHandler();
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

    public WifiController getWifiController() {
        return mWifiController;
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

    public KeyStore getKeyStore() {
        return mKeyStore;
    }

    public WifiBackupRestore getWifiBackupRestore() {
        return mWifiBackupRestore;
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

    public CarrierNetworkConfig getCarrierNetworkConfig() {
        return mCarrierNetworkConfig;
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

    public WifiStateTracker getWifiStateTracker() {
        return mWifiStateTracker;
    }

    public DppManager getDppManager() {
        return mDppManager;
    }

    /** Gets IWificond without caching. */
    public IWificond makeWificond() {
        // We depend on being able to refresh our binder in ClientModeImpl, so don't cache it.
        IBinder binder = ServiceManager.getService(WIFICOND_SERVICE_NAME);
        return IWificond.Stub.asInterface(binder);
    }

    /**
     * Create a SoftApManager.
     * @param config SoftApModeConfiguration object holding the config and mode
     * @return an instance of SoftApManager
     */
    public SoftApManager makeSoftApManager(@NonNull WifiManager.SoftApCallback callback,
                                           @NonNull SoftApModeConfiguration config) {
        return new SoftApManager(mContext, mWifiCoreHandlerThread.getLooper(),
                mFrameworkFacade, mWifiNative, mCountryCode.getCountryCode(), callback,
                mWifiApConfigStore, config, mWifiMetrics, mSarManager);
    }

    /**
     * Create a ScanOnlyModeManager
     *
     * @param listener listener for ScanOnlyModeManager state changes
     * @return a new instance of ScanOnlyModeManager
     */
    public ScanOnlyModeManager makeScanOnlyModeManager(
            @NonNull ScanOnlyModeManager.Listener listener) {
        return new ScanOnlyModeManager(mContext, mWifiCoreHandlerThread.getLooper(),
                mWifiNative, listener, mWifiMetrics, mWakeupController,
                mSarManager);
    }

    /**
     * Create a ClientModeManager
     *
     * @param listener listener for ClientModeManager state changes
     * @return a new instance of ClientModeManager
     */
    public ClientModeManager makeClientModeManager(ClientModeManager.Listener listener) {
        return new ClientModeManager(mContext, mWifiCoreHandlerThread.getLooper(),
                mWifiNative, listener, mWifiMetrics, mClientModeImpl);
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
            mWifiScanner = new WifiScanner(mContext,
                    IWifiScanner.Stub.asInterface(ServiceManager.getService(
                            Context.WIFI_SCANNING_SERVICE)),
                    mWifiCoreHandlerThread.getLooper());
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
                mWifiCoreHandlerThread.getLooper(), mFrameworkFacade, mClock, mWifiMetrics,
                mWifiConfigManager, mWifiConfigStore, clientModeImpl,
                new ConnectToNetworkNotificationBuilder(mContext, mFrameworkFacade));
        mCarrierNetworkNotifier = new CarrierNetworkNotifier(mContext,
                mWifiCoreHandlerThread.getLooper(), mFrameworkFacade, mClock, mWifiMetrics,
                mWifiConfigManager, mWifiConfigStore, clientModeImpl,
                new ConnectToNetworkNotificationBuilder(mContext, mFrameworkFacade));
        mWifiLastResortWatchdog = new WifiLastResortWatchdog(this, mClock,
                mWifiMetrics, clientModeImpl, clientModeImpl.getHandler().getLooper());
        return new WifiConnectivityManager(mContext, getScoringParams(),
                clientModeImpl, this,
                mWifiConfigManager, clientModeImpl.getWifiInfo(),
                mWifiNetworkSelector, mWifiConnectivityHelper,
                mWifiLastResortWatchdog, mOpenNetworkNotifier, mCarrierNetworkNotifier,
                mCarrierNetworkConfig, mWifiMetrics, mWifiCoreHandlerThread.getLooper(),
                mClock, mConnectivityLocalLog);
    }

    /**
     * Construct a new instance of {@link WifiNetworkFactory}.
     * TODO(b/116233964): Remove cyclic dependency between WifiConnectivityManager & ClientModeImpl.
     */
    public WifiNetworkFactory makeWifiNetworkFactory(
            NetworkCapabilities nc, WifiConnectivityManager wifiConnectivityManager) {
        return new WifiNetworkFactory(
                mWifiCoreHandlerThread.getLooper(), mContext, nc,
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
                mWifiCoreHandlerThread.getLooper(), mContext, nc, wifiConnectivityManager);
    }

    /**
     * Construct an instance of {@link NetworkSuggestionStoreData}.
     */
    public NetworkSuggestionStoreData makeNetworkSuggestionStoreData(
            NetworkSuggestionStoreData.DataSource dataSource) {
        return new NetworkSuggestionStoreData(dataSource);
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

    public PowerProfile getPowerProfile() {
        return new PowerProfile(mContext, false);
    }

    public ScanRequestProxy getScanRequestProxy() {
        return mScanRequestProxy;
    }

    public Runtime getJavaRuntime() {
        return Runtime.getRuntime();
    }

    public ActivityManagerService getActivityManagerService() {
        return (ActivityManagerService) ActivityManager.getService();
    }

    public WifiDataStall getWifiDataStall() {
        return mWifiDataStall;
    }

    public WifiNetworkSuggestionsManager getWifiNetworkSuggestionsManager() {
        return mWifiNetworkSuggestionsManager;
    }

    public IpMemoryStore getIpMemoryStore() {
        return mIpMemoryStore;
    }
}
