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

import android.app.ActivityManager;
import android.content.Context;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.wifi.IApInterface;
import android.net.wifi.IWifiScanner;
import android.net.wifi.IWificond;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiNetworkScoreCache;
import android.net.wifi.WifiScanner;
import android.os.BatteryStats;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserManager;
import android.security.KeyStore;
import android.telephony.TelephonyManager;
import android.util.LocalLog;

import com.android.internal.R;
import com.android.internal.app.IBatteryStats;
import com.android.server.am.BatteryStatsService;
import com.android.server.net.DelayedDiskWrite;
import com.android.server.net.IpConfigStore;
import com.android.server.wifi.aware.WifiAwareMetrics;
import com.android.server.wifi.hotspot2.PasspointManager;
import com.android.server.wifi.hotspot2.PasspointNetworkEvaluator;
import com.android.server.wifi.hotspot2.PasspointObjectFactory;
import com.android.server.wifi.p2p.SupplicantP2pIfaceHal;
import com.android.server.wifi.p2p.WifiP2pMonitor;
import com.android.server.wifi.p2p.WifiP2pNative;
import com.android.server.wifi.util.WifiPermissionsUtil;
import com.android.server.wifi.util.WifiPermissionsWrapper;

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
    private final HandlerThread mWifiStateMachineHandlerThread;
    private final WifiTrafficPoller mTrafficPoller;
    private final WifiCountryCode mCountryCode;
    private final BackupManagerProxy mBackupManagerProxy = new BackupManagerProxy();
    private final WifiApConfigStore mWifiApConfigStore;
    private final WifiNative mWifiNative;
    private final WifiMonitor mWifiMonitor;
    private final WifiP2pNative mWifiP2pNative;
    private final WifiP2pMonitor mWifiP2pMonitor;
    private final SupplicantStaIfaceHal mSupplicantStaIfaceHal;
    private final SupplicantP2pIfaceHal mSupplicantP2pIfaceHal;
    private final WifiVendorHal mWifiVendorHal;
    private final WifiStateMachine mWifiStateMachine;
    private final WifiSettingsStore mSettingsStore;
    private final WifiCertManager mCertManager;
    private final OpenNetworkNotifier mOpenNetworkNotifier;
    private final WifiLockManager mLockManager;
    private final WifiController mWifiController;
    private final WificondControl mWificondControl;
    private final Clock mClock = new Clock();
    private final WifiMetrics mWifiMetrics;
    private final WifiLastResortWatchdog mWifiLastResortWatchdog;
    private final PropertyService mPropertyService = new SystemPropertyService();
    private final BuildProperties mBuildProperties = new SystemBuildProperties();
    private final KeyStore mKeyStore = KeyStore.getInstance();
    private final WifiBackupRestore mWifiBackupRestore;
    private final WifiMulticastLockManager mWifiMulticastLockManager;
    private final WifiConfigStore mWifiConfigStore;
    private final WifiKeyStore mWifiKeyStore;
    private final IpConfigStore mIpConfigStore;
    private final WifiConfigManager mWifiConfigManager;
    private final WifiConnectivityHelper mWifiConnectivityHelper;
    private final LocalLog mConnectivityLocalLog;
    private final WifiNetworkSelector mWifiNetworkSelector;
    private final SavedNetworkEvaluator mSavedNetworkEvaluator;
    private final PasspointNetworkEvaluator mPasspointNetworkEvaluator;
    private final ScoredNetworkEvaluator mScoredNetworkEvaluator;
    private final WifiNetworkScoreCache mWifiNetworkScoreCache;
    private final NetworkScoreManager mNetworkScoreManager;
    private WifiScanner mWifiScanner;
    private final WifiPermissionsWrapper mWifiPermissionsWrapper;
    private final WifiPermissionsUtil mWifiPermissionsUtil;
    private final PasspointManager mPasspointManager;
    private final SIMAccessor mSimAccessor;
    private HandlerThread mWifiAwareHandlerThread;
    private HalDeviceManager mHalDeviceManager;
    private final IBatteryStats mBatteryStats;
    private final WifiStateTracker mWifiStateTracker;
    private final Runtime mJavaRuntime;
    private final SelfRecovery mSelfRecovery;

    private final boolean mUseRealLogger;

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
        mUseRealLogger = mContext.getResources().getBoolean(
                R.bool.config_wifi_enable_wifi_firmware_debugging);
        mSettingsStore = new WifiSettingsStore(mContext);
        mWifiPermissionsWrapper = new WifiPermissionsWrapper(mContext);
        mNetworkScoreManager = mContext.getSystemService(NetworkScoreManager.class);
        mWifiNetworkScoreCache = new WifiNetworkScoreCache(mContext);
        mNetworkScoreManager.registerNetworkScoreCache(NetworkKey.TYPE_WIFI,
                mWifiNetworkScoreCache, NetworkScoreManager.CACHE_FILTER_NONE);
        mWifiPermissionsUtil = new WifiPermissionsUtil(mWifiPermissionsWrapper, mContext,
                mSettingsStore, UserManager.get(mContext), mNetworkScoreManager, this);
        mWifiBackupRestore = new WifiBackupRestore(mWifiPermissionsUtil);
        mBatteryStats = IBatteryStats.Stub.asInterface(mFrameworkFacade.getService(
                BatteryStats.SERVICE_NAME));
        mWifiStateTracker = new WifiStateTracker(mBatteryStats);
        // Now create and start handler threads
        mWifiServiceHandlerThread = new HandlerThread("WifiService");
        mWifiServiceHandlerThread.start();
        mWifiStateMachineHandlerThread = new HandlerThread("WifiStateMachine");
        mWifiStateMachineHandlerThread.start();
        Looper wifiStateMachineLooper = mWifiStateMachineHandlerThread.getLooper();
        WifiAwareMetrics awareMetrics = new WifiAwareMetrics(mClock);
        mWifiMetrics = new WifiMetrics(mClock, wifiStateMachineLooper, awareMetrics);
        // Modules interacting with Native.
        mWifiMonitor = new WifiMonitor(this);
        mHalDeviceManager = new HalDeviceManager();
        mWifiVendorHal =
                new WifiVendorHal(mHalDeviceManager, mWifiStateMachineHandlerThread.getLooper());
        mSupplicantStaIfaceHal = new SupplicantStaIfaceHal(mContext, mWifiMonitor);
        mWificondControl = new WificondControl(this, mWifiMonitor,
                new CarrierNetworkConfig(mContext));
        mWifiNative = new WifiNative(SystemProperties.get("wifi.interface", "wlan0"),
                mWifiVendorHal, mSupplicantStaIfaceHal, mWificondControl);
        mWifiP2pMonitor = new WifiP2pMonitor(this);
        mSupplicantP2pIfaceHal = new SupplicantP2pIfaceHal(mWifiP2pMonitor);
        mWifiP2pNative = new WifiP2pNative(SystemProperties.get("wifi.direct.interface", "p2p0"),
                mSupplicantP2pIfaceHal);

        // Now get instances of all the objects that depend on the HandlerThreads
        mTrafficPoller =  new WifiTrafficPoller(mContext, mWifiServiceHandlerThread.getLooper(),
                mWifiNative.getInterfaceName());
        mCountryCode = new WifiCountryCode(mWifiNative,
                SystemProperties.get(BOOT_DEFAULT_WIFI_COUNTRY_CODE),
                mContext.getResources()
                        .getBoolean(R.bool.config_wifi_revert_country_code_on_cellular_loss));
        mWifiApConfigStore = new WifiApConfigStore(mContext, mBackupManagerProxy);

        // WifiConfigManager/Store objects and their dependencies.
        // New config store
        mWifiKeyStore = new WifiKeyStore(mKeyStore);
        mWifiConfigStore = new WifiConfigStore(
                mContext, wifiStateMachineLooper, mClock,
                WifiConfigStore.createSharedFile());
        // Legacy config store
        DelayedDiskWrite writer = new DelayedDiskWrite();
        mIpConfigStore = new IpConfigStore(writer);
        // Config Manager
        mWifiConfigManager = new WifiConfigManager(mContext, mClock,
                UserManager.get(mContext), TelephonyManager.from(mContext),
                mWifiKeyStore, mWifiConfigStore, mWifiPermissionsUtil,
                mWifiPermissionsWrapper, new NetworkListStoreData(mContext),
                new DeletedEphemeralSsidsStoreData());
        mWifiMetrics.setWifiConfigManager(mWifiConfigManager);
        mWifiConnectivityHelper = new WifiConnectivityHelper(mWifiNative);
        mConnectivityLocalLog = new LocalLog(ActivityManager.isLowRamDeviceStatic() ? 256 : 512);
        mWifiNetworkSelector = new WifiNetworkSelector(mContext, mWifiConfigManager, mClock,
                mConnectivityLocalLog);
        mWifiMetrics.setWifiNetworkSelector(mWifiNetworkSelector);
        mSavedNetworkEvaluator = new SavedNetworkEvaluator(mContext,
                mWifiConfigManager, mClock, mConnectivityLocalLog, mWifiConnectivityHelper);
        mScoredNetworkEvaluator = new ScoredNetworkEvaluator(context, wifiStateMachineLooper,
                mFrameworkFacade, mNetworkScoreManager, mWifiConfigManager, mConnectivityLocalLog,
                mWifiNetworkScoreCache);
        mSimAccessor = new SIMAccessor(mContext);
        mPasspointManager = new PasspointManager(mContext, mWifiNative, mWifiKeyStore, mClock,
                mSimAccessor, new PasspointObjectFactory(), mWifiConfigManager, mWifiConfigStore,
                mWifiMetrics);
        mPasspointNetworkEvaluator = new PasspointNetworkEvaluator(
                mPasspointManager, mWifiConfigManager, mConnectivityLocalLog);
        mWifiMetrics.setPasspointManager(mPasspointManager);
        // mWifiStateMachine has an implicit dependency on mJavaRuntime due to WifiDiagnostics.
        mJavaRuntime = Runtime.getRuntime();
        mWifiStateMachine = new WifiStateMachine(mContext, mFrameworkFacade,
                wifiStateMachineLooper, UserManager.get(mContext),
                this, mBackupManagerProxy, mCountryCode, mWifiNative,
                new WrongPasswordNotifier(mContext, mFrameworkFacade));
        mCertManager = new WifiCertManager(mContext);
        mOpenNetworkNotifier = new OpenNetworkNotifier(mContext,
                mWifiStateMachineHandlerThread.getLooper(), mFrameworkFacade, mClock, mWifiMetrics,
                mWifiConfigManager, mWifiConfigStore, mWifiStateMachine,
                new OpenNetworkRecommender(),
                new ConnectToNetworkNotificationBuilder(mContext, mFrameworkFacade));
        mLockManager = new WifiLockManager(mContext, BatteryStatsService.getService());
        mWifiController = new WifiController(mContext, mWifiStateMachine, mSettingsStore,
                mLockManager, mWifiServiceHandlerThread.getLooper(), mFrameworkFacade);
        mSelfRecovery = new SelfRecovery(mWifiController, mClock);
        mWifiLastResortWatchdog = new WifiLastResortWatchdog(mSelfRecovery, mWifiMetrics);
        mWifiMulticastLockManager = new WifiMulticastLockManager(mWifiStateMachine,
                BatteryStatsService.getService());
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

    public UserManager getUserManager() {
        return UserManager.get(mContext);
    }

    public WifiMetrics getWifiMetrics() {
        return mWifiMetrics;
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

    public HandlerThread getWifiStateMachineHandlerThread() {
        return mWifiStateMachineHandlerThread;
    }

    public WifiTrafficPoller getWifiTrafficPoller() {
        return mTrafficPoller;
    }

    public WifiCountryCode getWifiCountryCode() {
        return mCountryCode;
    }

    public WifiApConfigStore getWifiApConfigStore() {
        return mWifiApConfigStore;
    }

    public WifiStateMachine getWifiStateMachine() {
        return mWifiStateMachine;
    }

    public WifiSettingsStore getWifiSettingsStore() {
        return mSettingsStore;
    }

    public WifiCertManager getWifiCertManager() {
        return mCertManager;
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

    public TelephonyManager makeTelephonyManager() {
        // may not be available when WiFi starts
        return (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
    }

    public WifiStateTracker getWifiStateTracker() {
        return mWifiStateTracker;
    }

    public IWificond makeWificond() {
        // We depend on being able to refresh our binder in WifiStateMachine, so don't cache it.
        IBinder binder = ServiceManager.getService(WIFICOND_SERVICE_NAME);
        return IWificond.Stub.asInterface(binder);
    }

    /**
     * Create a SoftApManager.
     * @param nmService NetworkManagementService allowing SoftApManager to listen for interface
     * changes
     * @param listener listener for SoftApManager
     * @param apInterface network interface to start hostapd against
     * @param config softAp WifiConfiguration
     * @return an instance of SoftApManager
     */
    public SoftApManager makeSoftApManager(INetworkManagementService nmService,
                                           SoftApManager.Listener listener,
                                           IApInterface apInterface,
                                           WifiConfiguration config) {
        return new SoftApManager(mWifiServiceHandlerThread.getLooper(),
                                 mWifiNative, mCountryCode.getCountryCode(),
                                 listener, apInterface, nmService,
                                 mWifiApConfigStore, config, mWifiMetrics);
    }

    /**
     * Create a WifiLog instance.
     * @param tag module name to include in all log messages
     */
    public WifiLog makeLog(String tag) {
        return new LogcatLog(tag);
    }

    public BaseWifiDiagnostics makeWifiDiagnostics(WifiNative wifiNative) {
        if (mUseRealLogger) {
            return new WifiDiagnostics(
                    mContext, this, mWifiStateMachine, wifiNative, mBuildProperties,
                    new LastMileLogger(this));
        } else {
            return new BaseWifiDiagnostics(wifiNative);
        }
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
                    mWifiStateMachineHandlerThread.getLooper());
        }
        return mWifiScanner;
    }

    /**
     * Obtain a new instance of WifiConnectivityManager.
     *
     * Create and return a new WifiConnectivityManager.
     * @param wifiInfo WifiInfo object for updating wifi state.
     * @param hasConnectionRequests boolean indicating if WifiConnectivityManager to start
     * immediately based on connection requests.
     */
    public WifiConnectivityManager makeWifiConnectivityManager(WifiInfo wifiInfo,
                                                               boolean hasConnectionRequests) {
        return new WifiConnectivityManager(mContext, mWifiStateMachine, getWifiScanner(),
                mWifiConfigManager, wifiInfo, mWifiNetworkSelector, mWifiConnectivityHelper,
                mWifiLastResortWatchdog, mOpenNetworkNotifier, mWifiMetrics,
                mWifiStateMachineHandlerThread.getLooper(), mClock, mConnectivityLocalLog,
                hasConnectionRequests, mFrameworkFacade, mSavedNetworkEvaluator,
                mScoredNetworkEvaluator, mPasspointNetworkEvaluator);
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
     * Returns a single instance of HalDeviceManager for injection.
     */
    public HalDeviceManager getHalDeviceManager() {
        return mHalDeviceManager;
    }

    public Runtime getJavaRuntime() {
        return mJavaRuntime;
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
}
