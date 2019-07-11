/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.wifi.WifiManager;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.LocalLog;
import android.telephony.Rlog;

import com.android.internal.R;
import com.android.internal.telephony.DctConstants;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.ArrayUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.PriorityQueue;

/**
 * {@hide}
 */
public abstract class DcTrackerBase extends Handler {
    protected static final boolean DBG = true;
    protected static final boolean VDBG = false; // STOPSHIP if true
    protected static final boolean VDBG_STALL = false; // STOPSHIP if true
    protected static final boolean RADIO_TESTS = false;

    static boolean mIsCleanupRequired = false;
    /**
     * Constants for the data connection activity:
     * physical link down/up
     */
    protected static final int DATA_CONNECTION_ACTIVE_PH_LINK_INACTIVE = 0;
    protected static final int DATA_CONNECTION_ACTIVE_PH_LINK_DOWN = 1;
    protected static final int DATA_CONNECTION_ACTIVE_PH_LINK_UP = 2;

    /** Delay between APN attempts.
        Note the property override mechanism is there just for testing purpose only. */
    protected static final int APN_DELAY_DEFAULT_MILLIS = 20000;

    /** Delay between APN attempts when in fail fast mode */
    protected static final int APN_FAIL_FAST_DELAY_DEFAULT_MILLIS = 3000;

    AlarmManager mAlarmManager;

    protected Object mDataEnabledLock = new Object();

    // responds to the setInternalDataEnabled call - used internally to turn off data
    // for example during emergency calls
    protected boolean mInternalDataEnabled = true;

    // responds to public (user) API to enable/disable data use
    // independent of mInternalDataEnabled and requests for APN access
    // persisted
    protected boolean mUserDataEnabled = true;

    // TODO: move away from static state once 5587429 is fixed.
    protected static boolean sPolicyDataEnabled = true;

    private boolean[] mDataEnabled = new boolean[DctConstants.APN_NUM_TYPES];

    private int mEnabledCount = 0;

    /* Currently requested APN type (TODO: This should probably be a parameter not a member) */
    protected String mRequestedApnType = PhoneConstants.APN_TYPE_DEFAULT;

    /** Retry configuration: A doubling of retry times from 5secs to 30minutes */
    protected static final String DEFAULT_DATA_RETRY_CONFIG = "default_randomization=2000,"
        + "5000,10000,20000,40000,80000:5000,160000:5000,"
        + "320000:5000,640000:5000,1280000:5000,1800000:5000";

    /** Retry configuration for secondary networks: 4 tries in 20 sec */
    protected static final String SECONDARY_DATA_RETRY_CONFIG =
            "max_retries=3, 5000, 5000, 5000";

    /** Slow poll when attempting connection recovery. */
    protected static final int POLL_NETSTAT_SLOW_MILLIS = 5000;
    /** Default max failure count before attempting to network re-registration. */
    protected static final int DEFAULT_MAX_PDP_RESET_FAIL = 3;

    /**
     * After detecting a potential connection problem, this is the max number
     * of subsequent polls before attempting recovery.
     */
    protected static final int NO_RECV_POLL_LIMIT = 24;
    // 1 sec. default polling interval when screen is on.
    protected static final int POLL_NETSTAT_MILLIS = 1000;
    // 10 min. default polling interval when screen is off.
    protected static final int POLL_NETSTAT_SCREEN_OFF_MILLIS = 1000*60*10;
    // 2 min for round trip time
    protected static final int POLL_LONGEST_RTT = 120 * 1000;
    // Default sent packets without ack which triggers initial recovery steps
    protected static final int NUMBER_SENT_PACKETS_OF_HANG = 10;
    // how long to wait before switching back to default APN
    protected static final int RESTORE_DEFAULT_APN_DELAY = 1 * 60 * 1000;
    // system property that can override the above value
    protected static final String APN_RESTORE_DELAY_PROP_NAME = "android.telephony.apn-restore";
    // represents an invalid IP address
    protected static final String NULL_IP = "0.0.0.0";

    // Default for the data stall alarm while non-aggressive stall detection
    protected static final int DATA_STALL_ALARM_NON_AGGRESSIVE_DELAY_IN_MS_DEFAULT = 1000 * 60 * 6;
    // Default for the data stall alarm for aggressive stall detection
    protected static final int DATA_STALL_ALARM_AGGRESSIVE_DELAY_IN_MS_DEFAULT = 1000 * 60;
    // If attempt is less than this value we're doing first level recovery
    protected static final int DATA_STALL_NO_RECV_POLL_LIMIT = 1;
    // Tag for tracking stale alarms
    protected static final String DATA_STALL_ALARM_TAG_EXTRA = "data.stall.alram.tag";

    protected static final boolean DATA_STALL_SUSPECTED = true;
    protected static final boolean DATA_STALL_NOT_SUSPECTED = false;

    protected String RADIO_RESET_PROPERTY = "gsm.radioreset";

    protected static final String INTENT_RECONNECT_ALARM =
            "com.android.internal.telephony.data-reconnect";
    protected static final String INTENT_RECONNECT_ALARM_EXTRA_TYPE = "reconnect_alarm_extra_type";
    protected static final String INTENT_RECONNECT_ALARM_EXTRA_REASON =
            "reconnect_alarm_extra_reason";

    protected static final String INTENT_RESTART_TRYSETUP_ALARM =
            "com.android.internal.telephony.data-restart-trysetup";
    protected static final String INTENT_RESTART_TRYSETUP_ALARM_EXTRA_TYPE =
            "restart_trysetup_alarm_extra_type";

    protected static final String INTENT_DATA_STALL_ALARM =
            "com.android.internal.telephony.data-stall";



    protected static final String DEFALUT_DATA_ON_BOOT_PROP = "net.def_data_on_boot";

    protected DcTesterFailBringUpAll mDcTesterFailBringUpAll;
    protected DcController mDcc;

    // member variables
    protected PhoneBase mPhone;
    protected UiccController mUiccController;
    protected AtomicReference<IccRecords> mIccRecords = new AtomicReference<IccRecords>();
    protected DctConstants.Activity mActivity = DctConstants.Activity.NONE;
    protected DctConstants.State mState = DctConstants.State.IDLE;
    protected Handler mDataConnectionTracker = null;

    protected long mTxPkts;
    protected long mRxPkts;
    protected int mNetStatPollPeriod;
    protected boolean mNetStatPollEnabled = false;

    protected TxRxSum mDataStallTxRxSum = new TxRxSum(0, 0);
    // Used to track stale data stall alarms.
    protected int mDataStallAlarmTag = (int) SystemClock.elapsedRealtime();
    // The current data stall alarm intent
    protected PendingIntent mDataStallAlarmIntent = null;
    // Number of packets sent since the last received packet
    protected long mSentSinceLastRecv;
    // Controls when a simple recovery attempt it to be tried
    protected int mNoRecvPollCount = 0;
    // Refrence counter for enabling fail fast
    protected static int sEnableFailFastRefCounter = 0;
    // True if data stall detection is enabled
    protected volatile boolean mDataStallDetectionEnabled = true;

    protected volatile boolean mFailFast = false;

    // True when in voice call
    protected boolean mInVoiceCall = false;

    // wifi connection status will be updated by sticky intent
    protected boolean mIsWifiConnected = false;

    /** Intent sent when the reconnect alarm fires. */
    protected PendingIntent mReconnectIntent = null;

    /** CID of active data connection */
    protected int mCidActive;

    // When false we will not auto attach and manually attaching is required.
    protected boolean mAutoAttachOnCreationConfig = false;
    protected boolean mAutoAttachOnCreation = false;

    // State of screen
    // (TODO: Reconsider tying directly to screen, maybe this is
    //        really a lower power mode")
    protected boolean mIsScreenOn = true;

    /** Allows the generation of unique Id's for DataConnection objects */
    protected AtomicInteger mUniqueIdGenerator = new AtomicInteger(0);

    /** The data connections. */
    protected HashMap<Integer, DataConnection> mDataConnections =
        new HashMap<Integer, DataConnection>();

    /** The data connection async channels */
    protected HashMap<Integer, DcAsyncChannel> mDataConnectionAcHashMap =
        new HashMap<Integer, DcAsyncChannel>();

    /** Convert an ApnType string to Id (TODO: Use "enumeration" instead of String for ApnType) */
    protected HashMap<String, Integer> mApnToDataConnectionId =
                                    new HashMap<String, Integer>();

    /** Phone.APN_TYPE_* ===> ApnContext */
    protected final ConcurrentHashMap<String, ApnContext> mApnContexts =
                                    new ConcurrentHashMap<String, ApnContext>();

    /** kept in sync with mApnContexts
     * Higher numbers are higher priority and sorted so highest priority is first */
    protected final PriorityQueue<ApnContext>mPrioritySortedApnContexts =
            new PriorityQueue<ApnContext>(5,
            new Comparator<ApnContext>() {
                public int compare(ApnContext c1, ApnContext c2) {
                    return c2.priority - c1.priority;
                }
            } );

    /* Currently active APN */
    protected ApnSetting mActiveApn;

    /** allApns holds all apns */
    protected ArrayList<ApnSetting> mAllApnSettings = null;

    /** preferred apn */
    protected ApnSetting mPreferredApn = null;

    /** Is packet service restricted by network */
    protected boolean mIsPsRestricted = false;

    /** emergency apn Setting*/
    protected ApnSetting mEmergencyApn = null;

    /* Once disposed dont handle any messages */
    protected boolean mIsDisposed = false;

    protected ContentResolver mResolver;

    /* Set to true with CMD_ENABLE_MOBILE_PROVISIONING */
    protected boolean mIsProvisioning = false;

    /* The Url passed as object parameter in CMD_ENABLE_MOBILE_PROVISIONING */
    protected String mProvisioningUrl = null;

    /* Intent for the provisioning apn alarm */
    protected static final String INTENT_PROVISIONING_APN_ALARM =
            "com.android.internal.telephony.provisioning_apn_alarm";

    /* Tag for tracking stale alarms */
    protected static final String PROVISIONING_APN_ALARM_TAG_EXTRA = "provisioning.apn.alarm.tag";

    /* Debug property for overriding the PROVISIONING_APN_ALARM_DELAY_IN_MS */
    protected static final String DEBUG_PROV_APN_ALARM =
            "persist.debug.prov_apn_alarm";

    /* Default for the provisioning apn alarm timeout */
    protected static final int PROVISIONING_APN_ALARM_DELAY_IN_MS_DEFAULT = 1000 * 60 * 15;

    /* The provision apn alarm intent used to disable the provisioning apn */
    protected PendingIntent mProvisioningApnAlarmIntent = null;

    /* Used to track stale provisioning apn alarms */
    protected int mProvisioningApnAlarmTag = (int) SystemClock.elapsedRealtime();

    protected AsyncChannel mReplyAc = new AsyncChannel();

    protected BroadcastReceiver mIntentReceiver = new BroadcastReceiver ()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String action = intent.getAction();
            if (DBG) log("onReceive: action=" + action);
            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                mIsScreenOn = true;
                stopNetStatPoll();
                startNetStatPoll();
                restartDataStallAlarm();
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mIsScreenOn = false;
                stopNetStatPoll();
                startNetStatPoll();
                restartDataStallAlarm();
            } else if (action.startsWith(INTENT_RECONNECT_ALARM)) {
                if (DBG) log("Reconnect alarm. Previous state was " + mState);
                onActionIntentReconnectAlarm(intent);
            } else if (action.startsWith(INTENT_RESTART_TRYSETUP_ALARM)) {
                if (DBG) log("Restart trySetup alarm");
                onActionIntentRestartTrySetupAlarm(intent);
            } else if (action.equals(INTENT_DATA_STALL_ALARM)) {
                onActionIntentDataStallAlarm(intent);
            } else if (action.equals(INTENT_PROVISIONING_APN_ALARM)) {
                onActionIntentProvisioningApnAlarm(intent);
            } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                final android.net.NetworkInfo networkInfo = (NetworkInfo)
                        intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                mIsWifiConnected = (networkInfo != null && networkInfo.isConnected());
                if (DBG) log("NETWORK_STATE_CHANGED_ACTION: mIsWifiConnected=" + mIsWifiConnected);
            } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                final boolean enabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;

                if (!enabled) {
                    // when WiFi got disabled, the NETWORK_STATE_CHANGED_ACTION
                    // quit and won't report disconnected until next enabling.
                    mIsWifiConnected = false;
                }
                if (DBG) log("WIFI_STATE_CHANGED_ACTION: enabled=" + enabled
                        + " mIsWifiConnected=" + mIsWifiConnected);
            }
        }
    };

    private Runnable mPollNetStat = new Runnable()
    {
        @Override
        public void run() {
            updateDataActivity();

            if (mIsScreenOn) {
                mNetStatPollPeriod = Settings.Global.getInt(mResolver,
                        Settings.Global.PDP_WATCHDOG_POLL_INTERVAL_MS, POLL_NETSTAT_MILLIS);
            } else {
                mNetStatPollPeriod = Settings.Global.getInt(mResolver,
                        Settings.Global.PDP_WATCHDOG_LONG_POLL_INTERVAL_MS,
                        POLL_NETSTAT_SCREEN_OFF_MILLIS);
            }

            if (mNetStatPollEnabled) {
                mDataConnectionTracker.postDelayed(this, mNetStatPollPeriod);
            }
        }
    };

    private SubscriptionManager mSubscriptionManager;
    private final OnSubscriptionsChangedListener mOnSubscriptionsChangedListener =
            new OnSubscriptionsChangedListener() {
        /**
         * Callback invoked when there is any change to any SubscriptionInfo. Typically
         * this method would invoke {@link SubscriptionManager#getActiveSubscriptionInfoList}
         */
        @Override
        public void onSubscriptionsChanged() {
            if (DBG) log("SubscriptionListener.onSubscriptionInfoChanged");
            // Set the network type, in case the radio does not restore it.
            int subId = mPhone.getSubId();
            if (SubscriptionManager.isValidSubscriptionId(subId)) {
                if (mDataRoamingSettingObserver != null) {
                    mDataRoamingSettingObserver.unregister();
                }
                // Watch for changes to Settings.Global.DATA_ROAMING
                mDataRoamingSettingObserver = new DataRoamingSettingObserver(mPhone,
                        mPhone.getContext());
                mDataRoamingSettingObserver.register();
            }
        }
    };

    private class DataRoamingSettingObserver extends ContentObserver {

        public DataRoamingSettingObserver(Handler handler, Context context) {
            super(handler);
            mResolver = context.getContentResolver();
        }

        public void register() {
            String contentUri;
            if (TelephonyManager.getDefault().getSimCount() == 1) {
                contentUri = Settings.Global.DATA_ROAMING;
            } else {
                contentUri = Settings.Global.DATA_ROAMING + mPhone.getSubId();
            }

            mResolver.registerContentObserver(Settings.Global.getUriFor(contentUri), false, this);
        }

        public void unregister() {
            mResolver.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            // already running on mPhone handler thread
            if (mPhone.getServiceState().getDataRoaming()) {
                sendMessage(obtainMessage(DctConstants.EVENT_ROAMING_ON));
            }
        }
    }
    private DataRoamingSettingObserver mDataRoamingSettingObserver;

    /**
     * The Initial MaxRetry sent to a DataConnection as a parameter
     * to DataConnectionAc.bringUp. This value can be defined at compile
     * time using the SystemProperty Settings.Global.DCT_INITIAL_MAX_RETRY
     * and at runtime using gservices to change Settings.Global.DCT_INITIAL_MAX_RETRY.
     */
    private static final int DEFAULT_MDC_INITIAL_RETRY = 1;
    protected int getInitialMaxRetry() {
        if (mFailFast) {
            return 0;
        }
        // Get default value from system property or use DEFAULT_MDC_INITIAL_RETRY
        int value = SystemProperties.getInt(
                Settings.Global.MDC_INITIAL_MAX_RETRY, DEFAULT_MDC_INITIAL_RETRY);

        // Check if its been overridden
        return Settings.Global.getInt(mResolver,
                Settings.Global.MDC_INITIAL_MAX_RETRY, value);
    }

    /**
     * Maintain the sum of transmit and receive packets.
     *
     * The packet counts are initialized and reset to -1 and
     * remain -1 until they can be updated.
     */
    public class TxRxSum {
        public long txPkts;
        public long rxPkts;

        public TxRxSum() {
            reset();
        }

        public TxRxSum(long txPkts, long rxPkts) {
            this.txPkts = txPkts;
            this.rxPkts = rxPkts;
        }

        public TxRxSum(TxRxSum sum) {
            txPkts = sum.txPkts;
            rxPkts = sum.rxPkts;
        }

        public void reset() {
            txPkts = -1;
            rxPkts = -1;
        }

        @Override
        public String toString() {
            return "{txSum=" + txPkts + " rxSum=" + rxPkts + "}";
        }

        public void updateTxRxSum() {
            this.txPkts = TrafficStats.getMobileTcpTxPackets();
            this.rxPkts = TrafficStats.getMobileTcpRxPackets();
        }
    }

    protected void onActionIntentReconnectAlarm(Intent intent) {
        String reason = intent.getStringExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON);
        String apnType = intent.getStringExtra(INTENT_RECONNECT_ALARM_EXTRA_TYPE);

        int phoneSubId = mPhone.getSubId();
        int currSubId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        log("onActionIntentReconnectAlarm: currSubId = " + currSubId + " phoneSubId=" + phoneSubId);

        // Stop reconnect if not current subId is not correct.
        // FIXME STOPSHIP - phoneSubId is coming up as -1 way after boot and failing this?
        if (!SubscriptionManager.isValidSubscriptionId(currSubId) || (currSubId != phoneSubId)) {
            log("receive ReconnectAlarm but subId incorrect, ignore");
            return;
        }

        ApnContext apnContext = mApnContexts.get(apnType);

        if (DBG) {
            log("onActionIntentReconnectAlarm: mState=" + mState + " reason=" + reason +
                    " apnType=" + apnType + " apnContext=" + apnContext +
                    " mDataConnectionAsyncChannels=" + mDataConnectionAcHashMap);
        }

        if ((apnContext != null) && (apnContext.isEnabled())) {
            apnContext.setReason(reason);
            DctConstants.State apnContextState = apnContext.getState();
            if (DBG) {
                log("onActionIntentReconnectAlarm: apnContext state=" + apnContextState);
            }
            if ((apnContextState == DctConstants.State.FAILED)
                    || (apnContextState == DctConstants.State.IDLE)) {
                if (DBG) {
                    log("onActionIntentReconnectAlarm: state is FAILED|IDLE, disassociate");
                }
                DcAsyncChannel dcac = apnContext.getDcAc();
                if (dcac != null) {
                    if (DBG) {
                        log("onActionIntentReconnectAlarm: tearDown apnContext=" + apnContext);
                    }
                    dcac.tearDown(apnContext, "", null);
                }
                apnContext.setDataConnectionAc(null);
                apnContext.setState(DctConstants.State.IDLE);
            } else {
                if (DBG) log("onActionIntentReconnectAlarm: keep associated");
            }
            // TODO: IF already associated should we send the EVENT_TRY_SETUP_DATA???
            sendMessage(obtainMessage(DctConstants.EVENT_TRY_SETUP_DATA, apnContext));

            apnContext.setReconnectIntent(null);
        }
    }

    protected void onActionIntentRestartTrySetupAlarm(Intent intent) {
        String apnType = intent.getStringExtra(INTENT_RESTART_TRYSETUP_ALARM_EXTRA_TYPE);
        ApnContext apnContext = mApnContexts.get(apnType);
        if (DBG) {
            log("onActionIntentRestartTrySetupAlarm: mState=" + mState +
                    " apnType=" + apnType + " apnContext=" + apnContext +
                    " mDataConnectionAsyncChannels=" + mDataConnectionAcHashMap);
        }
        sendMessage(obtainMessage(DctConstants.EVENT_TRY_SETUP_DATA, apnContext));
    }

    protected void onActionIntentDataStallAlarm(Intent intent) {
        if (VDBG_STALL) log("onActionIntentDataStallAlarm: action=" + intent.getAction());
        Message msg = obtainMessage(DctConstants.EVENT_DATA_STALL_ALARM,
                intent.getAction());
        msg.arg1 = intent.getIntExtra(DATA_STALL_ALARM_TAG_EXTRA, 0);
        sendMessage(msg);
    }

    ConnectivityManager mCm;

    /**
     * Default constructor
     */
    protected DcTrackerBase(PhoneBase phone) {
        super();
        mPhone = phone;
        if (DBG) log("DCT.constructor");
        mResolver = mPhone.getContext().getContentResolver();
        mUiccController = UiccController.getInstance();
        mUiccController.registerForIccChanged(this, DctConstants.EVENT_ICC_CHANGED, null);
        mAlarmManager =
                (AlarmManager) mPhone.getContext().getSystemService(Context.ALARM_SERVICE);
        mCm = (ConnectivityManager) mPhone.getContext().getSystemService(
                Context.CONNECTIVITY_SERVICE);


        int phoneSubId = mPhone.getSubId();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(INTENT_DATA_STALL_ALARM);
        filter.addAction(INTENT_PROVISIONING_APN_ALARM);

        mUserDataEnabled = getDataEnabled();

        mPhone.getContext().registerReceiver(mIntentReceiver, filter, null, mPhone);

        // This preference tells us 1) initial condition for "dataEnabled",
        // and 2) whether the RIL will setup the baseband to auto-PS attach.

        mDataEnabled[DctConstants.APN_DEFAULT_ID] =
                SystemProperties.getBoolean(DEFALUT_DATA_ON_BOOT_PROP,true);
        if (mDataEnabled[DctConstants.APN_DEFAULT_ID]) {
            mEnabledCount++;
        }

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mPhone.getContext());
        mAutoAttachOnCreation = sp.getBoolean(PhoneBase.DATA_DISABLED_ON_BOOT_KEY, false);

        mSubscriptionManager = SubscriptionManager.from(mPhone.getContext());
        mSubscriptionManager
                .addOnSubscriptionsChangedListener(mOnSubscriptionsChangedListener);

        HandlerThread dcHandlerThread = new HandlerThread("DcHandlerThread");
        dcHandlerThread.start();
        Handler dcHandler = new Handler(dcHandlerThread.getLooper());
        mDcc = DcController.makeDcc(mPhone, this, dcHandler);
        mDcTesterFailBringUpAll = new DcTesterFailBringUpAll(mPhone, dcHandler);
    }

    public void dispose() {
        if (DBG) log("DCT.dispose");
        for (DcAsyncChannel dcac : mDataConnectionAcHashMap.values()) {
            dcac.disconnect();
        }
        mDataConnectionAcHashMap.clear();
        mIsDisposed = true;
        mPhone.getContext().unregisterReceiver(mIntentReceiver);
        mUiccController.unregisterForIccChanged(this);
        if (mDataRoamingSettingObserver != null) {
            mDataRoamingSettingObserver.unregister();
        }
        mSubscriptionManager
                .removeOnSubscriptionsChangedListener(mOnSubscriptionsChangedListener);
        mDcc.dispose();
        mDcTesterFailBringUpAll.dispose();
    }

    public long getSubId() {
        return mPhone.getSubId();
    }

    public DctConstants.Activity getActivity() {
        return mActivity;
    }

    void setActivity(DctConstants.Activity activity) {
        log("setActivity = " + activity);
        mActivity = activity;
        mPhone.notifyDataActivity();
    }

    abstract public void incApnRefCount(String name, LocalLog log);

    abstract public void decApnRefCount(String name, LocalLog log);

    public boolean isApnSupported(String name) {
        return false;
    }

    public int getApnPriority(String name) {
        return -1;
    }


    public boolean isApnTypeActive(String type) {
        // TODO: support simultaneous with List instead
        if (PhoneConstants.APN_TYPE_DUN.equals(type)) {
            ApnSetting dunApn = fetchDunApn();
            if (dunApn != null) {
                return ((mActiveApn != null) && (dunApn.toString().equals(mActiveApn.toString())));
            }
        }
        return mActiveApn != null && mActiveApn.canHandleType(type);
    }

    protected ApnSetting fetchDunApn() {
        if (SystemProperties.getBoolean("net.tethering.noprovisioning", false)) {
            log("fetchDunApn: net.tethering.noprovisioning=true ret: null");
            return null;
        }
        int bearer = mPhone.getServiceState().getRilDataRadioTechnology();
        ApnSetting retDunSetting = null;
        String apnData = Settings.Global.getString(mResolver, Settings.Global.TETHER_DUN_APN);
        List<ApnSetting> dunSettings = ApnSetting.arrayFromString(apnData);
        IccRecords r = mIccRecords.get();
        for (ApnSetting dunSetting : dunSettings) {
            String operator = (r != null) ? r.getOperatorNumeric() : "";
            if (!ServiceState.bitmaskHasTech(dunSetting.bearerBitmask, bearer)) continue;
            if (dunSetting.numeric.equals(operator)) {
                if (dunSetting.hasMvnoParams()) {
                    if (r != null && ApnSetting.mvnoMatches(r, dunSetting.mvnoType,
                            dunSetting.mvnoMatchData)) {
                        if (VDBG) {
                            log("fetchDunApn: global TETHER_DUN_APN dunSetting=" + dunSetting);
                        }
                        return dunSetting;
                    }
                } else {
                    if (VDBG) log("fetchDunApn: global TETHER_DUN_APN dunSetting=" + dunSetting);
                    return dunSetting;
                }
            }
        }

        Context c = mPhone.getContext();
        String[] apnArrayData = c.getResources().getStringArray(R.array.config_tether_apndata);
        for (String apn : apnArrayData) {
            ApnSetting dunSetting = ApnSetting.fromString(apn);
            if (dunSetting != null) {
                if (!ServiceState.bitmaskHasTech(dunSetting.bearerBitmask, bearer)) continue;
                if (dunSetting.hasMvnoParams()) {
                    if (r != null && ApnSetting.mvnoMatches(r, dunSetting.mvnoType,
                            dunSetting.mvnoMatchData)) {
                        if (VDBG) {
                            log("fetchDunApn: config_tether_apndata mvno dunSetting=" + dunSetting);
                        }
                        return dunSetting;
                    }
                } else {
                    retDunSetting = dunSetting;
                }
            }
        }

        if (VDBG) log("fetchDunApn: config_tether_apndata dunSetting=" + retDunSetting);
        return retDunSetting;
    }

    public boolean hasMatchedTetherApnSetting() {
        ApnSetting matched = fetchDunApn();
        log("hasMatchedTetherApnSetting: APN=" + matched);
        return matched != null;
    }

    public String[] getActiveApnTypes() {
        String[] result;
        if (mActiveApn != null) {
            result = mActiveApn.types;
        } else {
            result = new String[1];
            result[0] = PhoneConstants.APN_TYPE_DEFAULT;
        }
        return result;
    }

    /** TODO: See if we can remove */
    public String getActiveApnString(String apnType) {
        String result = null;
        if (mActiveApn != null) {
            result = mActiveApn.apn;
        }
        return result;
    }

    /**
     * Modify {@link android.provider.Settings.Global#DATA_ROAMING} value.
     */
    public void setDataOnRoamingEnabled(boolean enabled) {
        final int phoneSubId = mPhone.getSubId();
        if (getDataOnRoamingEnabled() != enabled) {
            int roaming = enabled ? 1 : 0;

            // For single SIM phones, this is a per phone property.
            if (TelephonyManager.getDefault().getSimCount() == 1) {
                Settings.Global.putInt(mResolver, Settings.Global.DATA_ROAMING, roaming);
            } else {
                Settings.Global.putInt(mResolver, Settings.Global.DATA_ROAMING + phoneSubId, roaming);
            }

            mSubscriptionManager.setDataRoaming(roaming, phoneSubId);
            // will trigger handleDataOnRoamingChange() through observer
            if (DBG) {
               log("setDataOnRoamingEnabled: set phoneSubId=" + phoneSubId
                       + " isRoaming=" + enabled);
            }
        } else {
            if (DBG) {
                log("setDataOnRoamingEnabled: unchanged phoneSubId=" + phoneSubId
                        + " isRoaming=" + enabled);
             }
        }
    }

    /**
     * Return current {@link android.provider.Settings.Global#DATA_ROAMING} value.
     */
    public boolean getDataOnRoamingEnabled() {
        boolean isDataRoamingEnabled = "true".equalsIgnoreCase(SystemProperties.get(
                "ro.com.android.dataroaming", "false"));
        final int phoneSubId = mPhone.getSubId();

        try {
            // For single SIM phones, this is a per phone property.
            if (TelephonyManager.getDefault().getSimCount() == 1) {
                isDataRoamingEnabled = Settings.Global.getInt(mResolver,
                        Settings.Global.DATA_ROAMING, isDataRoamingEnabled ? 1 : 0) != 0;
            } else {
                isDataRoamingEnabled = TelephonyManager.getIntWithSubId(mResolver,
                        Settings.Global.DATA_ROAMING, phoneSubId) != 0;
            }
        } catch (SettingNotFoundException snfe) {
            if (DBG) log("getDataOnRoamingEnabled: SettingNofFoundException snfe=" + snfe);
        }
        if (DBG) {
            log("getDataOnRoamingEnabled: phoneSubId=" + phoneSubId +
                    " isDataRoamingEnabled=" + isDataRoamingEnabled);
        }
        return isDataRoamingEnabled;
    }

    /**
     * Modify {@link android.provider.Settings.Global#MOBILE_DATA} value.
     */
    public void setDataEnabled(boolean enable) {
        Message msg = obtainMessage(DctConstants.CMD_SET_USER_DATA_ENABLE);
        msg.arg1 = enable ? 1 : 0;
        if (DBG) log("setDataEnabled: sendMessage: enable=" + enable);
        sendMessage(msg);
    }

    /**
     * Return current {@link android.provider.Settings.Global#MOBILE_DATA} value.
     */
    public boolean getDataEnabled() {
        boolean retVal = "true".equalsIgnoreCase(SystemProperties.get(
                "ro.com.android.mobiledata", "true"));
        try {
            if (TelephonyManager.getDefault().getSimCount() == 1) {
                retVal = Settings.Global.getInt(mResolver, Settings.Global.MOBILE_DATA,
                        retVal ? 1 : 0) != 0;
            } else {
                int phoneSubId = mPhone.getSubId();
                retVal = TelephonyManager.getIntWithSubId(mResolver, Settings.Global.MOBILE_DATA,
                        phoneSubId) != 0;
            }
            if (DBG) log("getDataEnabled: getIntWithSubId retVal=" + retVal);
        } catch (SettingNotFoundException snfe) {
            retVal = "true".equalsIgnoreCase(
                    SystemProperties.get("ro.com.android.mobiledata", "true"));
            if (DBG) {
                log("getDataEnabled: system property ro.com.android.mobiledata retVal=" + retVal);
            }
        }
        return retVal;
    }

    // abstract methods
    protected abstract void restartRadio();
    protected abstract void log(String s);
    protected abstract void loge(String s);
    protected abstract boolean isDataAllowed();
    protected abstract boolean isApnTypeAvailable(String type);
    public    abstract DctConstants.State getState(String apnType);
    protected abstract boolean isProvisioningApn(String apnType);
    protected abstract void setState(DctConstants.State s);
    protected abstract void gotoIdleAndNotifyDataConnection(String reason);

    protected abstract boolean onTrySetupData(String reason);
    protected abstract void onRoamingOff();
    protected abstract void onRoamingOn();
    protected abstract void onRadioAvailable();
    protected abstract void onRadioOffOrNotAvailable();
    protected abstract void onDataSetupComplete(AsyncResult ar);
    protected abstract void onDataSetupCompleteError(AsyncResult ar);
    protected abstract void onDisconnectDone(int connId, AsyncResult ar);
    protected abstract void onDisconnectDcRetrying(int connId, AsyncResult ar);
    protected abstract void onVoiceCallStarted();
    protected abstract void onVoiceCallEnded();
    protected abstract void onCleanUpConnection(boolean tearDown, int apnId, String reason);
    protected abstract void onCleanUpAllConnections(String cause);
    public abstract boolean isDataPossible(String apnType);
    protected abstract void onUpdateIcc();
    protected abstract void completeConnection(ApnContext apnContext);
    public abstract void setDataAllowed(boolean enable, Message response);
    public abstract String[] getPcscfAddress(String apnType);
    public abstract void setImsRegistrationState(boolean registered);
    protected abstract boolean isPermanentFail(DcFailCause dcFailCause);

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                log("DISCONNECTED_CONNECTED: msg=" + msg);
                DcAsyncChannel dcac = (DcAsyncChannel) msg.obj;
                mDataConnectionAcHashMap.remove(dcac.getDataConnectionIdSync());
                dcac.disconnected();
                break;
            }
            case DctConstants.EVENT_ENABLE_NEW_APN:
                onEnableApn(msg.arg1, msg.arg2);
                break;

            case DctConstants.EVENT_TRY_SETUP_DATA:
                String reason = null;
                if (msg.obj instanceof String) {
                    reason = (String) msg.obj;
                }
                onTrySetupData(reason);
                break;

            case DctConstants.EVENT_DATA_STALL_ALARM:
                onDataStallAlarm(msg.arg1);
                break;

            case DctConstants.EVENT_ROAMING_OFF:
                onRoamingOff();
                break;

            case DctConstants.EVENT_ROAMING_ON:
                onRoamingOn();
                break;

            case DctConstants.EVENT_RADIO_AVAILABLE:
                onRadioAvailable();
                break;

            case DctConstants.EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                onRadioOffOrNotAvailable();
                break;

            case DctConstants.EVENT_DATA_SETUP_COMPLETE:
                mCidActive = msg.arg1;
                onDataSetupComplete((AsyncResult) msg.obj);
                break;

            case DctConstants.EVENT_DATA_SETUP_COMPLETE_ERROR:
                onDataSetupCompleteError((AsyncResult) msg.obj);
                break;

            case DctConstants.EVENT_DISCONNECT_DONE:
                log("DataConnectionTracker.handleMessage: EVENT_DISCONNECT_DONE msg=" + msg);
                onDisconnectDone(msg.arg1, (AsyncResult) msg.obj);
                break;

            case DctConstants.EVENT_DISCONNECT_DC_RETRYING:
                log("DataConnectionTracker.handleMessage: EVENT_DISCONNECT_DC_RETRYING msg=" + msg);
                onDisconnectDcRetrying(msg.arg1, (AsyncResult) msg.obj);
                break;

            case DctConstants.EVENT_VOICE_CALL_STARTED:
                onVoiceCallStarted();
                break;

            case DctConstants.EVENT_VOICE_CALL_ENDED:
                onVoiceCallEnded();
                break;

            case DctConstants.EVENT_CLEAN_UP_ALL_CONNECTIONS: {
                onCleanUpAllConnections((String) msg.obj);
                break;
            }
            case DctConstants.EVENT_CLEAN_UP_CONNECTION: {
                boolean tearDown = (msg.arg1 == 0) ? false : true;
                onCleanUpConnection(tearDown, msg.arg2, (String) msg.obj);
                break;
            }
            case DctConstants.EVENT_SET_INTERNAL_DATA_ENABLE: {
                boolean enabled = (msg.arg1 == DctConstants.ENABLED) ? true : false;
                onSetInternalDataEnabled(enabled);
                break;
            }
            case DctConstants.EVENT_RESET_DONE: {
                if (DBG) log("EVENT_RESET_DONE");
                onResetDone((AsyncResult) msg.obj);
                break;
            }
            case DctConstants.CMD_SET_USER_DATA_ENABLE: {
                final boolean enabled = (msg.arg1 == DctConstants.ENABLED) ? true : false;
                if (DBG) log("CMD_SET_USER_DATA_ENABLE enabled=" + enabled);
                onSetUserDataEnabled(enabled);
                break;
            }
            case DctConstants.CMD_SET_DEPENDENCY_MET: {
                boolean met = (msg.arg1 == DctConstants.ENABLED) ? true : false;
                if (DBG) log("CMD_SET_DEPENDENCY_MET met=" + met);
                Bundle bundle = msg.getData();
                if (bundle != null) {
                    String apnType = (String)bundle.get(DctConstants.APN_TYPE_KEY);
                    if (apnType != null) {
                        onSetDependencyMet(apnType, met);
                    }
                }
                break;
            }
            case DctConstants.CMD_SET_POLICY_DATA_ENABLE: {
                final boolean enabled = (msg.arg1 == DctConstants.ENABLED) ? true : false;
                onSetPolicyDataEnabled(enabled);
                break;
            }
            case DctConstants.CMD_SET_ENABLE_FAIL_FAST_MOBILE_DATA: {
                sEnableFailFastRefCounter += (msg.arg1 == DctConstants.ENABLED) ? 1 : -1;
                if (DBG) {
                    log("CMD_SET_ENABLE_FAIL_FAST_MOBILE_DATA: "
                            + " sEnableFailFastRefCounter=" + sEnableFailFastRefCounter);
                }
                if (sEnableFailFastRefCounter < 0) {
                    final String s = "CMD_SET_ENABLE_FAIL_FAST_MOBILE_DATA: "
                            + "sEnableFailFastRefCounter:" + sEnableFailFastRefCounter + " < 0";
                    loge(s);
                    sEnableFailFastRefCounter = 0;
                }
                final boolean enabled = sEnableFailFastRefCounter > 0;
                if (DBG) {
                    log("CMD_SET_ENABLE_FAIL_FAST_MOBILE_DATA: enabled=" + enabled
                            + " sEnableFailFastRefCounter=" + sEnableFailFastRefCounter);
                }
                if (mFailFast != enabled) {
                    mFailFast = enabled;
                    mDataStallDetectionEnabled = !enabled;
                    if (mDataStallDetectionEnabled
                            && (getOverallState() == DctConstants.State.CONNECTED)
                            && (!mInVoiceCall ||
                                    mPhone.getServiceStateTracker()
                                        .isConcurrentVoiceAndDataAllowed())) {
                        if (DBG) log("CMD_SET_ENABLE_FAIL_FAST_MOBILE_DATA: start data stall");
                        stopDataStallAlarm();
                        startDataStallAlarm(DATA_STALL_NOT_SUSPECTED);
                    } else {
                        if (DBG) log("CMD_SET_ENABLE_FAIL_FAST_MOBILE_DATA: stop data stall");
                        stopDataStallAlarm();
                    }
                }

                break;
            }
            case DctConstants.CMD_ENABLE_MOBILE_PROVISIONING: {
                Bundle bundle = msg.getData();
                if (bundle != null) {
                    try {
                        mProvisioningUrl = (String)bundle.get(DctConstants.PROVISIONING_URL_KEY);
                    } catch(ClassCastException e) {
                        loge("CMD_ENABLE_MOBILE_PROVISIONING: provisioning url not a string" + e);
                        mProvisioningUrl = null;
                    }
                }
                if (TextUtils.isEmpty(mProvisioningUrl)) {
                    loge("CMD_ENABLE_MOBILE_PROVISIONING: provisioning url is empty, ignoring");
                    mIsProvisioning = false;
                    mProvisioningUrl = null;
                } else {
                    loge("CMD_ENABLE_MOBILE_PROVISIONING: provisioningUrl=" + mProvisioningUrl);
                    mIsProvisioning = true;
                    startProvisioningApnAlarm();
                }
                break;
            }
            case DctConstants.EVENT_PROVISIONING_APN_ALARM: {
                if (DBG) log("EVENT_PROVISIONING_APN_ALARM");
                ApnContext apnCtx = mApnContexts.get("default");
                if (apnCtx.isProvisioningApn() && apnCtx.isConnectedOrConnecting()) {
                    if (mProvisioningApnAlarmTag == msg.arg1) {
                        if (DBG) log("EVENT_PROVISIONING_APN_ALARM: Disconnecting");
                        mIsProvisioning = false;
                        mProvisioningUrl = null;
                        stopProvisioningApnAlarm();
                        sendCleanUpConnection(true, apnCtx);
                    } else {
                        if (DBG) {
                            log("EVENT_PROVISIONING_APN_ALARM: ignore stale tag,"
                                    + " mProvisioningApnAlarmTag:" + mProvisioningApnAlarmTag
                                    + " != arg1:" + msg.arg1);
                        }
                    }
                } else {
                    if (DBG) log("EVENT_PROVISIONING_APN_ALARM: Not connected ignore");
                }
                break;
            }
            case DctConstants.CMD_IS_PROVISIONING_APN: {
                if (DBG) log("CMD_IS_PROVISIONING_APN");
                boolean isProvApn;
                try {
                    String apnType = null;
                    Bundle bundle = msg.getData();
                    if (bundle != null) {
                        apnType = (String)bundle.get(DctConstants.APN_TYPE_KEY);
                    }
                    if (TextUtils.isEmpty(apnType)) {
                        loge("CMD_IS_PROVISIONING_APN: apnType is empty");
                        isProvApn = false;
                    } else {
                        isProvApn = isProvisioningApn(apnType);
                    }
                } catch (ClassCastException e) {
                    loge("CMD_IS_PROVISIONING_APN: NO provisioning url ignoring");
                    isProvApn = false;
                }
                if (DBG) log("CMD_IS_PROVISIONING_APN: ret=" + isProvApn);
                mReplyAc.replyToMessage(msg, DctConstants.CMD_IS_PROVISIONING_APN,
                        isProvApn ? DctConstants.ENABLED : DctConstants.DISABLED);
                break;
            }
            case DctConstants.EVENT_ICC_CHANGED: {
                onUpdateIcc();
                break;
            }
            case DctConstants.EVENT_RESTART_RADIO: {
                restartRadio();
                break;
            }
            case DctConstants.CMD_NET_STAT_POLL: {
                if (msg.arg1 == DctConstants.ENABLED) {
                    handleStartNetStatPoll((DctConstants.Activity)msg.obj);
                } else if (msg.arg1 == DctConstants.DISABLED) {
                    handleStopNetStatPoll((DctConstants.Activity)msg.obj);
                }
                break;
            }
            default:
                Rlog.e("DATA", "Unidentified event msg=" + msg);
                break;
        }
    }

    /**
     * Report on whether data connectivity is enabled
     *
     * @return {@code false} if data connectivity has been explicitly disabled,
     *         {@code true} otherwise.
     */
    public boolean getAnyDataEnabled() {
        final boolean result;
        synchronized (mDataEnabledLock) {
            result = (mInternalDataEnabled && mUserDataEnabled && sPolicyDataEnabled
                    && (mEnabledCount != 0));
        }
        if (!result && DBG) log("getAnyDataEnabled " + result);
        return result;
    }

    protected boolean isEmergency() {
        final boolean result;
        synchronized (mDataEnabledLock) {
            result = mPhone.isInEcm() || mPhone.isInEmergencyCall();
        }
        log("isEmergency: result=" + result);
        return result;
    }

    protected int apnTypeToId(String type) {
        if (TextUtils.equals(type, PhoneConstants.APN_TYPE_DEFAULT)) {
            return DctConstants.APN_DEFAULT_ID;
        } else if (TextUtils.equals(type, PhoneConstants.APN_TYPE_MMS)) {
            return DctConstants.APN_MMS_ID;
        } else if (TextUtils.equals(type, PhoneConstants.APN_TYPE_SUPL)) {
            return DctConstants.APN_SUPL_ID;
        } else if (TextUtils.equals(type, PhoneConstants.APN_TYPE_DUN)) {
            return DctConstants.APN_DUN_ID;
        } else if (TextUtils.equals(type, PhoneConstants.APN_TYPE_HIPRI)) {
            return DctConstants.APN_HIPRI_ID;
        } else if (TextUtils.equals(type, PhoneConstants.APN_TYPE_IMS)) {
            return DctConstants.APN_IMS_ID;
        } else if (TextUtils.equals(type, PhoneConstants.APN_TYPE_FOTA)) {
            return DctConstants.APN_FOTA_ID;
        } else if (TextUtils.equals(type, PhoneConstants.APN_TYPE_CBS)) {
            return DctConstants.APN_CBS_ID;
        } else if (TextUtils.equals(type, PhoneConstants.APN_TYPE_IA)) {
            return DctConstants.APN_IA_ID;
        } else if (TextUtils.equals(type, PhoneConstants.APN_TYPE_EMERGENCY)) {
            return DctConstants.APN_EMERGENCY_ID;
        } else {
            return DctConstants.APN_INVALID_ID;
        }
    }

    protected String apnIdToType(int id) {
        switch (id) {
        case DctConstants.APN_DEFAULT_ID:
            return PhoneConstants.APN_TYPE_DEFAULT;
        case DctConstants.APN_MMS_ID:
            return PhoneConstants.APN_TYPE_MMS;
        case DctConstants.APN_SUPL_ID:
            return PhoneConstants.APN_TYPE_SUPL;
        case DctConstants.APN_DUN_ID:
            return PhoneConstants.APN_TYPE_DUN;
        case DctConstants.APN_HIPRI_ID:
            return PhoneConstants.APN_TYPE_HIPRI;
        case DctConstants.APN_IMS_ID:
            return PhoneConstants.APN_TYPE_IMS;
        case DctConstants.APN_FOTA_ID:
            return PhoneConstants.APN_TYPE_FOTA;
        case DctConstants.APN_CBS_ID:
            return PhoneConstants.APN_TYPE_CBS;
        case DctConstants.APN_IA_ID:
            return PhoneConstants.APN_TYPE_IA;
        case DctConstants.APN_EMERGENCY_ID:
            return PhoneConstants.APN_TYPE_EMERGENCY;
        default:
            log("Unknown id (" + id + ") in apnIdToType");
            return PhoneConstants.APN_TYPE_DEFAULT;
        }
    }

    public LinkProperties getLinkProperties(String apnType) {
        int id = apnTypeToId(apnType);

        if (isApnIdEnabled(id)) {
            DcAsyncChannel dcac = mDataConnectionAcHashMap.get(0);
            return dcac.getLinkPropertiesSync();
        } else {
            return new LinkProperties();
        }
    }

    public NetworkCapabilities getNetworkCapabilities(String apnType) {
        int id = apnTypeToId(apnType);
        if (isApnIdEnabled(id)) {
            DcAsyncChannel dcac = mDataConnectionAcHashMap.get(0);
            return dcac.getNetworkCapabilitiesSync();
        } else {
            return new NetworkCapabilities();
        }
    }

    // tell all active apns of the current condition
    protected void notifyDataConnection(String reason) {
        for (int id = 0; id < DctConstants.APN_NUM_TYPES; id++) {
            if (mDataEnabled[id]) {
                mPhone.notifyDataConnection(reason, apnIdToType(id));
            }
        }
        notifyOffApnsOfAvailability(reason);
    }

    // a new APN has gone active and needs to send events to catch up with the
    // current condition
    private void notifyApnIdUpToCurrent(String reason, int apnId) {
        switch (mState) {
            case IDLE:
                break;
            case RETRYING:
            case CONNECTING:
            case SCANNING:
                mPhone.notifyDataConnection(reason, apnIdToType(apnId),
                        PhoneConstants.DataState.CONNECTING);
                break;
            case CONNECTED:
            case DISCONNECTING:
                mPhone.notifyDataConnection(reason, apnIdToType(apnId),
                        PhoneConstants.DataState.CONNECTING);
                mPhone.notifyDataConnection(reason, apnIdToType(apnId),
                        PhoneConstants.DataState.CONNECTED);
                break;
            default:
                // Ignore
                break;
        }
    }

    // since we normally don't send info to a disconnected APN, we need to do this specially
    private void notifyApnIdDisconnected(String reason, int apnId) {
        mPhone.notifyDataConnection(reason, apnIdToType(apnId),
                PhoneConstants.DataState.DISCONNECTED);
    }

    // disabled apn's still need avail/unavail notificiations - send them out
    protected void notifyOffApnsOfAvailability(String reason) {
        if (DBG) log("notifyOffApnsOfAvailability - reason= " + reason);
        for (int id = 0; id < DctConstants.APN_NUM_TYPES; id++) {
            if (!isApnIdEnabled(id)) {
                notifyApnIdDisconnected(reason, id);
            }
        }
    }

    public boolean isApnTypeEnabled(String apnType) {
        if (apnType == null) {
            return false;
        } else {
            return isApnIdEnabled(apnTypeToId(apnType));
        }
    }

    protected synchronized boolean isApnIdEnabled(int id) {
        if (id != DctConstants.APN_INVALID_ID) {
            return mDataEnabled[id];
        }
        return false;
    }

    protected void setEnabled(int id, boolean enable) {
        if (DBG) {
            log("setEnabled(" + id + ", " + enable + ") with old state = " + mDataEnabled[id]
                    + " and enabledCount = " + mEnabledCount);
        }
        Message msg = obtainMessage(DctConstants.EVENT_ENABLE_NEW_APN);
        msg.arg1 = id;
        msg.arg2 = (enable ? DctConstants.ENABLED : DctConstants.DISABLED);
        sendMessage(msg);
    }

    abstract void onEnableApn(int apnId, int enabled);

    /**
     * Called when we switch APNs.
     *
     * mRequestedApnType is set prior to call
     * To be overridden.
     */
    protected void onEnableNewApn() {
    }

    /**
     * Called when EVENT_RESET_DONE is received so goto
     * IDLE state and send notifications to those interested.
     *
     * TODO - currently unused.  Needs to be hooked into DataConnection cleanup
     * TODO - needs to pass some notion of which connection is reset..
     */
    protected void onResetDone(AsyncResult ar) {
        if (DBG) log("EVENT_RESET_DONE");
        String reason = null;
        if (ar.userObj instanceof String) {
            reason = (String) ar.userObj;
        }
        gotoIdleAndNotifyDataConnection(reason);
    }

    /**
     * Prevent mobile data connections from being established, or once again
     * allow mobile data connections. If the state toggles, then either tear
     * down or set up data, as appropriate to match the new state.
     *
     * @param enable indicates whether to enable ({@code true}) or disable (
     *            {@code false}) data
     * @return {@code true} if the operation succeeded
     */
    public boolean setInternalDataEnabled(boolean enable) {
        if (DBG)
            log("setInternalDataEnabled(" + enable + ")");

        Message msg = obtainMessage(DctConstants.EVENT_SET_INTERNAL_DATA_ENABLE);
        msg.arg1 = (enable ? DctConstants.ENABLED : DctConstants.DISABLED);
        sendMessage(msg);
        return true;
    }

    protected void onSetInternalDataEnabled(boolean enabled) {
        synchronized (mDataEnabledLock) {
            mInternalDataEnabled = enabled;
            if (enabled) {
                log("onSetInternalDataEnabled: changed to enabled, try to setup data call");
                onTrySetupData(Phone.REASON_DATA_ENABLED);
            } else {
                log("onSetInternalDataEnabled: changed to disabled, cleanUpAllConnections");
                cleanUpAllConnections(null);
            }
        }
    }

    public void cleanUpAllConnections(String cause) {
        Message msg = obtainMessage(DctConstants.EVENT_CLEAN_UP_ALL_CONNECTIONS);
        msg.obj = cause;
        sendMessage(msg);
    }

    public abstract boolean isDisconnected();

    protected void onSetUserDataEnabled(boolean enabled) {
        synchronized (mDataEnabledLock) {
            if (mUserDataEnabled != enabled) {
                mUserDataEnabled = enabled;

                // For single SIM phones, this is a per phone property.
                if (TelephonyManager.getDefault().getSimCount() == 1) {
                    Settings.Global.putInt(mResolver, Settings.Global.MOBILE_DATA, enabled ? 1 : 0);
                } else {
                    int phoneSubId = mPhone.getSubId();
                    Settings.Global.putInt(mResolver, Settings.Global.MOBILE_DATA + phoneSubId,
                            enabled ? 1 : 0);
                }
                if (getDataOnRoamingEnabled() == false &&
                        mPhone.getServiceState().getDataRoaming() == true) {
                    if (enabled) {
                        notifyOffApnsOfAvailability(Phone.REASON_ROAMING_ON);
                    } else {
                        notifyOffApnsOfAvailability(Phone.REASON_DATA_DISABLED);
                    }
                }

                if (enabled) {
                    onTrySetupData(Phone.REASON_DATA_ENABLED);
                } else {
                    onCleanUpAllConnections(Phone.REASON_DATA_SPECIFIC_DISABLED);
                }
            }
        }
    }

    protected void onSetDependencyMet(String apnType, boolean met) {
    }

    protected void onSetPolicyDataEnabled(boolean enabled) {
        synchronized (mDataEnabledLock) {
            final boolean prevEnabled = getAnyDataEnabled();
            if (sPolicyDataEnabled != enabled) {
                sPolicyDataEnabled = enabled;
                if (prevEnabled != getAnyDataEnabled()) {
                    if (!prevEnabled) {
                        onTrySetupData(Phone.REASON_DATA_ENABLED);
                    } else {
                        onCleanUpAllConnections(Phone.REASON_DATA_SPECIFIC_DISABLED);
                    }
                }
            }
        }
    }

    protected String getReryConfig(boolean forDefault) {
        int nt = mPhone.getServiceState().getNetworkType();

        if ((nt == TelephonyManager.NETWORK_TYPE_CDMA) ||
            (nt == TelephonyManager.NETWORK_TYPE_1xRTT) ||
            (nt == TelephonyManager.NETWORK_TYPE_EVDO_0) ||
            (nt == TelephonyManager.NETWORK_TYPE_EVDO_A) ||
            (nt == TelephonyManager.NETWORK_TYPE_EVDO_B) ||
            (nt == TelephonyManager.NETWORK_TYPE_EHRPD)) {
            // CDMA variant
            return SystemProperties.get("ro.cdma.data_retry_config");
        } else {
            // Use GSM varient for all others.
            if (forDefault) {
                return SystemProperties.get("ro.gsm.data_retry_config");
            } else {
                return SystemProperties.get("ro.gsm.2nd_data_retry_config");
            }
        }
    }

    protected void resetPollStats() {
        mTxPkts = -1;
        mRxPkts = -1;
        mNetStatPollPeriod = POLL_NETSTAT_MILLIS;
    }

    protected abstract DctConstants.State getOverallState();

    void startNetStatPoll() {
        if (getOverallState() == DctConstants.State.CONNECTED
                && mNetStatPollEnabled == false) {
            if (DBG) {
                log("startNetStatPoll");
            }
            resetPollStats();
            mNetStatPollEnabled = true;
            mPollNetStat.run();
        }
        if (mPhone != null) {
            mPhone.notifyDataActivity();
        }
    }

    void stopNetStatPoll() {
        mNetStatPollEnabled = false;
        removeCallbacks(mPollNetStat);
        if (DBG) {
            log("stopNetStatPoll");
        }

        // To sync data activity icon in the case of switching data connection to send MMS.
        if (mPhone != null) {
            mPhone.notifyDataActivity();
        }
    }

    public void sendStartNetStatPoll(DctConstants.Activity activity) {
        Message msg = obtainMessage(DctConstants.CMD_NET_STAT_POLL);
        msg.arg1 = DctConstants.ENABLED;
        msg.obj = activity;
        sendMessage(msg);
    }

    protected void handleStartNetStatPoll(DctConstants.Activity activity) {
        startNetStatPoll();
        startDataStallAlarm(DATA_STALL_NOT_SUSPECTED);
        setActivity(activity);
    }

    public void sendStopNetStatPoll(DctConstants.Activity activity) {
        Message msg = obtainMessage(DctConstants.CMD_NET_STAT_POLL);
        msg.arg1 = DctConstants.DISABLED;
        msg.obj = activity;
        sendMessage(msg);
    }

    protected void handleStopNetStatPoll(DctConstants.Activity activity) {
        stopNetStatPoll();
        stopDataStallAlarm();
        setActivity(activity);
    }

    public void updateDataActivity() {
        long sent, received;

        DctConstants.Activity newActivity;

        TxRxSum preTxRxSum = new TxRxSum(mTxPkts, mRxPkts);
        TxRxSum curTxRxSum = new TxRxSum();
        curTxRxSum.updateTxRxSum();
        mTxPkts = curTxRxSum.txPkts;
        mRxPkts = curTxRxSum.rxPkts;

        if (VDBG) {
            log("updateDataActivity: curTxRxSum=" + curTxRxSum + " preTxRxSum=" + preTxRxSum);
        }

        if (mNetStatPollEnabled && (preTxRxSum.txPkts > 0 || preTxRxSum.rxPkts > 0)) {
            sent = mTxPkts - preTxRxSum.txPkts;
            received = mRxPkts - preTxRxSum.rxPkts;

            if (VDBG)
                log("updateDataActivity: sent=" + sent + " received=" + received);
            if (sent > 0 && received > 0) {
                newActivity = DctConstants.Activity.DATAINANDOUT;
            } else if (sent > 0 && received == 0) {
                newActivity = DctConstants.Activity.DATAOUT;
            } else if (sent == 0 && received > 0) {
                newActivity = DctConstants.Activity.DATAIN;
            } else {
                newActivity = (mActivity == DctConstants.Activity.DORMANT) ?
                        mActivity : DctConstants.Activity.NONE;
            }

            if (mActivity != newActivity && mIsScreenOn) {
                if (VDBG)
                    log("updateDataActivity: newActivity=" + newActivity);
                mActivity = newActivity;
                mPhone.notifyDataActivity();
            }
        }
    }

    // Recovery action taken in case of data stall
    protected static class RecoveryAction {
        public static final int GET_DATA_CALL_LIST      = 0;
        public static final int CLEANUP                 = 1;
        public static final int REREGISTER              = 2;
        public static final int RADIO_RESTART           = 3;
        public static final int RADIO_RESTART_WITH_PROP = 4;

        private static boolean isAggressiveRecovery(int value) {
            return ((value == RecoveryAction.CLEANUP) ||
                    (value == RecoveryAction.REREGISTER) ||
                    (value == RecoveryAction.RADIO_RESTART) ||
                    (value == RecoveryAction.RADIO_RESTART_WITH_PROP));
        }
    }

    public int getRecoveryAction() {
        int action = Settings.System.getInt(mResolver,
                "radio.data.stall.recovery.action", RecoveryAction.GET_DATA_CALL_LIST);
        if (VDBG_STALL) log("getRecoveryAction: " + action);
        return action;
    }
    public void putRecoveryAction(int action) {
        Settings.System.putInt(mResolver, "radio.data.stall.recovery.action", action);
        if (VDBG_STALL) log("putRecoveryAction: " + action);
    }

    protected boolean isConnected() {
        return false;
    }

    protected void doRecovery() {
        if (getOverallState() == DctConstants.State.CONNECTED) {
            // Go through a series of recovery steps, each action transitions to the next action
            int recoveryAction = getRecoveryAction();
            switch (recoveryAction) {
            case RecoveryAction.GET_DATA_CALL_LIST:
                EventLog.writeEvent(EventLogTags.DATA_STALL_RECOVERY_GET_DATA_CALL_LIST,
                        mSentSinceLastRecv);
                if (DBG) log("doRecovery() get data call list");
                mPhone.mCi.getDataCallList(obtainMessage(DctConstants.EVENT_DATA_STATE_CHANGED));
                putRecoveryAction(RecoveryAction.CLEANUP);
                break;
            case RecoveryAction.CLEANUP:
                EventLog.writeEvent(EventLogTags.DATA_STALL_RECOVERY_CLEANUP, mSentSinceLastRecv);
                if (DBG) log("doRecovery() cleanup all connections");
                cleanUpAllConnections(Phone.REASON_PDP_RESET);
                putRecoveryAction(RecoveryAction.REREGISTER);
                break;
            case RecoveryAction.REREGISTER:
                EventLog.writeEvent(EventLogTags.DATA_STALL_RECOVERY_REREGISTER,
                        mSentSinceLastRecv);
                if (DBG) log("doRecovery() re-register");
                mPhone.getServiceStateTracker().reRegisterNetwork(null);
                putRecoveryAction(RecoveryAction.RADIO_RESTART);
                break;
            case RecoveryAction.RADIO_RESTART:
                EventLog.writeEvent(EventLogTags.DATA_STALL_RECOVERY_RADIO_RESTART,
                        mSentSinceLastRecv);
                if (DBG) log("restarting radio");
                putRecoveryAction(RecoveryAction.RADIO_RESTART_WITH_PROP);
                restartRadio();
                break;
            case RecoveryAction.RADIO_RESTART_WITH_PROP:
                // This is in case radio restart has not recovered the data.
                // It will set an additional "gsm.radioreset" property to tell
                // RIL or system to take further action.
                // The implementation of hard reset recovery action is up to OEM product.
                // Once RADIO_RESET property is consumed, it is expected to set back
                // to false by RIL.
                EventLog.writeEvent(EventLogTags.DATA_STALL_RECOVERY_RADIO_RESTART_WITH_PROP, -1);
                if (DBG) log("restarting radio with gsm.radioreset to true");
                SystemProperties.set(RADIO_RESET_PROPERTY, "true");
                // give 1 sec so property change can be notified.
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {}
                restartRadio();
                putRecoveryAction(RecoveryAction.GET_DATA_CALL_LIST);
                break;
            default:
                throw new RuntimeException("doRecovery: Invalid recoveryAction=" +
                    recoveryAction);
            }
            mSentSinceLastRecv = 0;
        }
    }

    private void updateDataStallInfo() {
        long sent, received;

        TxRxSum preTxRxSum = new TxRxSum(mDataStallTxRxSum);
        mDataStallTxRxSum.updateTxRxSum();

        if (VDBG_STALL) {
            log("updateDataStallInfo: mDataStallTxRxSum=" + mDataStallTxRxSum +
                    " preTxRxSum=" + preTxRxSum);
        }

        sent = mDataStallTxRxSum.txPkts - preTxRxSum.txPkts;
        received = mDataStallTxRxSum.rxPkts - preTxRxSum.rxPkts;

        if (RADIO_TESTS) {
            if (SystemProperties.getBoolean("radio.test.data.stall", false)) {
                log("updateDataStallInfo: radio.test.data.stall true received = 0;");
                received = 0;
            }
        }
        if ( sent > 0 && received > 0 ) {
            if (VDBG_STALL) log("updateDataStallInfo: IN/OUT");
            mSentSinceLastRecv = 0;
            putRecoveryAction(RecoveryAction.GET_DATA_CALL_LIST);
        } else if (sent > 0 && received == 0) {
            if (mPhone.getState() == PhoneConstants.State.IDLE) {
                mSentSinceLastRecv += sent;
            } else {
                mSentSinceLastRecv = 0;
            }
            if (DBG) {
                log("updateDataStallInfo: OUT sent=" + sent +
                        " mSentSinceLastRecv=" + mSentSinceLastRecv);
            }
        } else if (sent == 0 && received > 0) {
            if (VDBG_STALL) log("updateDataStallInfo: IN");
            mSentSinceLastRecv = 0;
            putRecoveryAction(RecoveryAction.GET_DATA_CALL_LIST);
        } else {
            if (VDBG_STALL) log("updateDataStallInfo: NONE");
        }
    }

    protected void onDataStallAlarm(int tag) {
        if (mDataStallAlarmTag != tag) {
            if (DBG) {
                log("onDataStallAlarm: ignore, tag=" + tag + " expecting " + mDataStallAlarmTag);
            }
            return;
        }
        updateDataStallInfo();

        int hangWatchdogTrigger = Settings.Global.getInt(mResolver,
                Settings.Global.PDP_WATCHDOG_TRIGGER_PACKET_COUNT,
                NUMBER_SENT_PACKETS_OF_HANG);

        boolean suspectedStall = DATA_STALL_NOT_SUSPECTED;
        if (mSentSinceLastRecv >= hangWatchdogTrigger) {
            if (DBG) {
                log("onDataStallAlarm: tag=" + tag + " do recovery action=" + getRecoveryAction());
            }
            suspectedStall = DATA_STALL_SUSPECTED;
            sendMessage(obtainMessage(DctConstants.EVENT_DO_RECOVERY));
        } else {
            if (VDBG_STALL) {
                log("onDataStallAlarm: tag=" + tag + " Sent " + String.valueOf(mSentSinceLastRecv) +
                    " pkts since last received, < watchdogTrigger=" + hangWatchdogTrigger);
            }
        }
        startDataStallAlarm(suspectedStall);
    }

    protected void startDataStallAlarm(boolean suspectedStall) {
        int nextAction = getRecoveryAction();
        int delayInMs;

        if (mDataStallDetectionEnabled && getOverallState() == DctConstants.State.CONNECTED) {
            // If screen is on or data stall is currently suspected, set the alarm
            // with an aggresive timeout.
            if (mIsScreenOn || suspectedStall || RecoveryAction.isAggressiveRecovery(nextAction)) {
                delayInMs = Settings.Global.getInt(mResolver,
                        Settings.Global.DATA_STALL_ALARM_AGGRESSIVE_DELAY_IN_MS,
                        DATA_STALL_ALARM_AGGRESSIVE_DELAY_IN_MS_DEFAULT);
            } else {
                delayInMs = Settings.Global.getInt(mResolver,
                        Settings.Global.DATA_STALL_ALARM_NON_AGGRESSIVE_DELAY_IN_MS,
                        DATA_STALL_ALARM_NON_AGGRESSIVE_DELAY_IN_MS_DEFAULT);
            }

            mDataStallAlarmTag += 1;
            if (VDBG_STALL) {
                log("startDataStallAlarm: tag=" + mDataStallAlarmTag +
                        " delay=" + (delayInMs / 1000) + "s");
            }
            Intent intent = new Intent(INTENT_DATA_STALL_ALARM);
            intent.putExtra(DATA_STALL_ALARM_TAG_EXTRA, mDataStallAlarmTag);
            mDataStallAlarmIntent = PendingIntent.getBroadcast(mPhone.getContext(), 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayInMs, mDataStallAlarmIntent);
        } else {
            if (VDBG_STALL) {
                log("startDataStallAlarm: NOT started, no connection tag=" + mDataStallAlarmTag);
            }
        }
    }

    protected void stopDataStallAlarm() {
        if (VDBG_STALL) {
            log("stopDataStallAlarm: current tag=" + mDataStallAlarmTag +
                    " mDataStallAlarmIntent=" + mDataStallAlarmIntent);
        }
        mDataStallAlarmTag += 1;
        if (mDataStallAlarmIntent != null) {
            mAlarmManager.cancel(mDataStallAlarmIntent);
            mDataStallAlarmIntent = null;
        }
    }

    protected void restartDataStallAlarm() {
        if (isConnected() == false) return;
        // To be called on screen status change.
        // Do not cancel the alarm if it is set with aggressive timeout.
        int nextAction = getRecoveryAction();

        if (RecoveryAction.isAggressiveRecovery(nextAction)) {
            if (DBG) log("restartDataStallAlarm: action is pending. not resetting the alarm.");
            return;
        }
        if (VDBG_STALL) log("restartDataStallAlarm: stop then start.");
        stopDataStallAlarm();
        startDataStallAlarm(DATA_STALL_NOT_SUSPECTED);
    }

    protected void setInitialAttachApn() {
        ApnSetting iaApnSetting = null;
        ApnSetting defaultApnSetting = null;
        ApnSetting firstApnSetting = null;

        log("setInitialApn: E mPreferredApn=" + mPreferredApn);

        if (mAllApnSettings != null && !mAllApnSettings.isEmpty()) {
            firstApnSetting = mAllApnSettings.get(0);
            log("setInitialApn: firstApnSetting=" + firstApnSetting);

            // Search for Initial APN setting and the first apn that can handle default
            for (ApnSetting apn : mAllApnSettings) {
                // Can't use apn.canHandleType(), as that returns true for APNs that have no type.
                if (ArrayUtils.contains(apn.types, PhoneConstants.APN_TYPE_IA) &&
                        apn.carrierEnabled) {
                    // The Initial Attach APN is highest priority so use it if there is one
                    log("setInitialApn: iaApnSetting=" + apn);
                    iaApnSetting = apn;
                    break;
                } else if ((defaultApnSetting == null)
                        && (apn.canHandleType(PhoneConstants.APN_TYPE_DEFAULT))) {
                    // Use the first default apn if no better choice
                    log("setInitialApn: defaultApnSetting=" + apn);
                    defaultApnSetting = apn;
                }
            }
        }

        // The priority of apn candidates from highest to lowest is:
        //   1) APN_TYPE_IA (Inital Attach)
        //   2) mPreferredApn, i.e. the current preferred apn
        //   3) The first apn that than handle APN_TYPE_DEFAULT
        //   4) The first APN we can find.

        ApnSetting initialAttachApnSetting = null;
        if (iaApnSetting != null) {
            if (DBG) log("setInitialAttachApn: using iaApnSetting");
            initialAttachApnSetting = iaApnSetting;
        } else if (mPreferredApn != null) {
            if (DBG) log("setInitialAttachApn: using mPreferredApn");
            initialAttachApnSetting = mPreferredApn;
        } else if (defaultApnSetting != null) {
            if (DBG) log("setInitialAttachApn: using defaultApnSetting");
            initialAttachApnSetting = defaultApnSetting;
        } else if (firstApnSetting != null) {
            if (DBG) log("setInitialAttachApn: using firstApnSetting");
            initialAttachApnSetting = firstApnSetting;
        }

        if (initialAttachApnSetting == null) {
            if (DBG) log("setInitialAttachApn: X There in no available apn");
        } else {
            if (DBG) log("setInitialAttachApn: X selected Apn=" + initialAttachApnSetting);

            mPhone.mCi.setInitialAttachApn(initialAttachApnSetting.apn,
                    initialAttachApnSetting.protocol, initialAttachApnSetting.authType,
                    initialAttachApnSetting.user, initialAttachApnSetting.password, null);
        }
    }

    protected void setDataProfilesAsNeeded() {
        if (DBG) log("setDataProfilesAsNeeded");
        if (mAllApnSettings != null && !mAllApnSettings.isEmpty()) {
            ArrayList<DataProfile> dps = new ArrayList<DataProfile>();
            for (ApnSetting apn : mAllApnSettings) {
                if (apn.modemCognitive) {
                    DataProfile dp = new DataProfile(apn,
                            mPhone.getServiceState().getDataRoaming());
                    boolean isDup = false;
                    for(DataProfile dpIn : dps) {
                        if (dp.equals(dpIn)) {
                            isDup = true;
                            break;
                        }
                    }
                    if (!isDup) {
                        dps.add(dp);
                    }
                }
            }
            if(dps.size() > 0) {
                mPhone.mCi.setDataProfile(dps.toArray(new DataProfile[0]), null);
            }
        }
    }

    protected void onActionIntentProvisioningApnAlarm(Intent intent) {
        if (DBG) log("onActionIntentProvisioningApnAlarm: action=" + intent.getAction());
        Message msg = obtainMessage(DctConstants.EVENT_PROVISIONING_APN_ALARM,
                intent.getAction());
        msg.arg1 = intent.getIntExtra(PROVISIONING_APN_ALARM_TAG_EXTRA, 0);
        sendMessage(msg);
    }

    protected void startProvisioningApnAlarm() {
        int delayInMs = Settings.Global.getInt(mResolver,
                                Settings.Global.PROVISIONING_APN_ALARM_DELAY_IN_MS,
                                PROVISIONING_APN_ALARM_DELAY_IN_MS_DEFAULT);
        if (Build.IS_DEBUGGABLE) {
            // Allow debug code to use a system property to provide another value
            String delayInMsStrg = Integer.toString(delayInMs);
            delayInMsStrg = System.getProperty(DEBUG_PROV_APN_ALARM, delayInMsStrg);
            try {
                delayInMs = Integer.parseInt(delayInMsStrg);
            } catch (NumberFormatException e) {
                loge("startProvisioningApnAlarm: e=" + e);
            }
        }
        mProvisioningApnAlarmTag += 1;
        if (DBG) {
            log("startProvisioningApnAlarm: tag=" + mProvisioningApnAlarmTag +
                    " delay=" + (delayInMs / 1000) + "s");
        }
        Intent intent = new Intent(INTENT_PROVISIONING_APN_ALARM);
        intent.putExtra(PROVISIONING_APN_ALARM_TAG_EXTRA, mProvisioningApnAlarmTag);
        mProvisioningApnAlarmIntent = PendingIntent.getBroadcast(mPhone.getContext(), 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayInMs, mProvisioningApnAlarmIntent);
    }

    protected void stopProvisioningApnAlarm() {
        if (DBG) {
            log("stopProvisioningApnAlarm: current tag=" + mProvisioningApnAlarmTag +
                    " mProvsioningApnAlarmIntent=" + mProvisioningApnAlarmIntent);
        }
        mProvisioningApnAlarmTag += 1;
        if (mProvisioningApnAlarmIntent != null) {
            mAlarmManager.cancel(mProvisioningApnAlarmIntent);
            mProvisioningApnAlarmIntent = null;
        }
    }

    void sendCleanUpConnection(boolean tearDown, ApnContext apnContext) {
        if (DBG)log("sendCleanUpConnection: tearDown=" + tearDown + " apnContext=" + apnContext);
        Message msg = obtainMessage(DctConstants.EVENT_CLEAN_UP_CONNECTION);
        msg.arg1 = tearDown ? 1 : 0;
        msg.arg2 = 0;
        msg.obj = apnContext;
        sendMessage(msg);
    }

    void sendRestartRadio() {
        if (DBG)log("sendRestartRadio:");
        Message msg = obtainMessage(DctConstants.EVENT_RESTART_RADIO);
        sendMessage(msg);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("DcTrackerBase:");
        pw.println(" RADIO_TESTS=" + RADIO_TESTS);
        pw.println(" mInternalDataEnabled=" + mInternalDataEnabled);
        pw.println(" mUserDataEnabled=" + mUserDataEnabled);
        pw.println(" sPolicyDataEnabed=" + sPolicyDataEnabled);
        pw.println(" mDataEnabled:");
        for(int i=0; i < mDataEnabled.length; i++) {
            pw.printf("  mDataEnabled[%d]=%b\n", i, mDataEnabled[i]);
        }
        pw.flush();
        pw.println(" mEnabledCount=" + mEnabledCount);
        pw.println(" mRequestedApnType=" + mRequestedApnType);
        pw.println(" mPhone=" + mPhone.getPhoneName());
        pw.println(" mActivity=" + mActivity);
        pw.println(" mState=" + mState);
        pw.println(" mTxPkts=" + mTxPkts);
        pw.println(" mRxPkts=" + mRxPkts);
        pw.println(" mNetStatPollPeriod=" + mNetStatPollPeriod);
        pw.println(" mNetStatPollEnabled=" + mNetStatPollEnabled);
        pw.println(" mDataStallTxRxSum=" + mDataStallTxRxSum);
        pw.println(" mDataStallAlarmTag=" + mDataStallAlarmTag);
        pw.println(" mDataStallDetectionEanbled=" + mDataStallDetectionEnabled);
        pw.println(" mSentSinceLastRecv=" + mSentSinceLastRecv);
        pw.println(" mNoRecvPollCount=" + mNoRecvPollCount);
        pw.println(" mResolver=" + mResolver);
        pw.println(" mIsWifiConnected=" + mIsWifiConnected);
        pw.println(" mReconnectIntent=" + mReconnectIntent);
        pw.println(" mCidActive=" + mCidActive);
        pw.println(" mAutoAttachOnCreation=" + mAutoAttachOnCreation);
        pw.println(" mIsScreenOn=" + mIsScreenOn);
        pw.println(" mUniqueIdGenerator=" + mUniqueIdGenerator);
        pw.flush();
        pw.println(" ***************************************");
        DcController dcc = mDcc;
        if (dcc != null) {
            dcc.dump(fd, pw, args);
        } else {
            pw.println(" mDcc=null");
        }
        pw.println(" ***************************************");
        HashMap<Integer, DataConnection> dcs = mDataConnections;
        if (dcs != null) {
            Set<Entry<Integer, DataConnection> > mDcSet = mDataConnections.entrySet();
            pw.println(" mDataConnections: count=" + mDcSet.size());
            for (Entry<Integer, DataConnection> entry : mDcSet) {
                pw.printf(" *** mDataConnection[%d] \n", entry.getKey());
                entry.getValue().dump(fd, pw, args);
            }
        } else {
            pw.println("mDataConnections=null");
        }
        pw.println(" ***************************************");
        pw.flush();
        HashMap<String, Integer> apnToDcId = mApnToDataConnectionId;
        if (apnToDcId != null) {
            Set<Entry<String, Integer>> apnToDcIdSet = apnToDcId.entrySet();
            pw.println(" mApnToDataConnectonId size=" + apnToDcIdSet.size());
            for (Entry<String, Integer> entry : apnToDcIdSet) {
                pw.printf(" mApnToDataConnectonId[%s]=%d\n", entry.getKey(), entry.getValue());
            }
        } else {
            pw.println("mApnToDataConnectionId=null");
        }
        pw.println(" ***************************************");
        pw.flush();
        ConcurrentHashMap<String, ApnContext> apnCtxs = mApnContexts;
        if (apnCtxs != null) {
            Set<Entry<String, ApnContext>> apnCtxsSet = apnCtxs.entrySet();
            pw.println(" mApnContexts size=" + apnCtxsSet.size());
            for (Entry<String, ApnContext> entry : apnCtxsSet) {
                entry.getValue().dump(fd, pw, args);
            }
            pw.println(" ***************************************");
        } else {
            pw.println(" mApnContexts=null");
        }
        pw.flush();
        pw.println(" mActiveApn=" + mActiveApn);
        ArrayList<ApnSetting> apnSettings = mAllApnSettings;
        if (apnSettings != null) {
            pw.println(" mAllApnSettings size=" + apnSettings.size());
            for (int i=0; i < apnSettings.size(); i++) {
                pw.printf(" mAllApnSettings[%d]: %s\n", i, apnSettings.get(i));
            }
            pw.flush();
        } else {
            pw.println(" mAllApnSettings=null");
        }
        pw.println(" mPreferredApn=" + mPreferredApn);
        pw.println(" mIsPsRestricted=" + mIsPsRestricted);
        pw.println(" mIsDisposed=" + mIsDisposed);
        pw.println(" mIntentReceiver=" + mIntentReceiver);
        pw.println(" mDataRoamingSettingObserver=" + mDataRoamingSettingObserver);
        pw.flush();
    }
}
