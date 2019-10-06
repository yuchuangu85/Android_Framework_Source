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

import static android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE;
import static android.telephony.TelephonyManager.NETWORK_TYPE_LTE;
import static android.telephony.TelephonyManager.NETWORK_TYPE_NR;

import static com.android.internal.telephony.RILConstants.DATA_PROFILE_DEFAULT;
import static com.android.internal.telephony.RILConstants.DATA_PROFILE_INVALID;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkConfig;
import android.net.NetworkRequest;
import android.net.ProxyInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.RegistrantList;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.provider.Telephony;
import android.telephony.AccessNetworkConstants;
import android.telephony.AccessNetworkConstants.TransportType;
import android.telephony.CarrierConfigManager;
import android.telephony.CellLocation;
import android.telephony.DataFailCause;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.PcoData;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.ServiceState.RilRadioTechnology;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyManager;
import android.telephony.cdma.CdmaCellLocation;
import android.telephony.data.ApnSetting;
import android.telephony.data.ApnSetting.ApnType;
import android.telephony.data.DataProfile;
import android.telephony.gsm.GsmCellLocation;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.LocalLog;
import android.util.Pair;
import android.util.SparseArray;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.DctConstants;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.GsmCdmaPhone;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.PhoneSwitcher;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.SettingsObserver;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.dataconnection.DataConnectionReasons.DataAllowedReasonType;
import com.android.internal.telephony.dataconnection.DataConnectionReasons.DataDisallowedReasonType;
import com.android.internal.telephony.dataconnection.DataEnabledSettings.DataEnabledChangedReason;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.AsyncChannel;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
/**
 * {@hide}
 */
public class DcTracker extends Handler {
    private static final boolean DBG = true;
    private static final boolean VDBG = false; // STOPSHIP if true
    private static final boolean VDBG_STALL = false; // STOPSHIP if true
    private static final boolean RADIO_TESTS = false;

    /**
     * These constants exist here because ConnectivityManager.TYPE_xxx constants are deprecated and
     * new ones will not be added (for instance NETWORK_TYPE_MCX below).
     * For backward compatibility, the values here need to be the same as
     * ConnectivityManager.TYPE_xxx because networkAttributes overlay uses those values.
     */
    private static final int NETWORK_TYPE_DEFAULT = ConnectivityManager.TYPE_MOBILE;
    private static final int NETWORK_TYPE_MMS = ConnectivityManager.TYPE_MOBILE_MMS;
    private static final int NETWORK_TYPE_SUPL = ConnectivityManager.TYPE_MOBILE_SUPL;
    private static final int NETWORK_TYPE_DUN = ConnectivityManager.TYPE_MOBILE_DUN;
    private static final int NETWORK_TYPE_HIPRI = ConnectivityManager.TYPE_MOBILE_HIPRI;
    private static final int NETWORK_TYPE_FOTA = ConnectivityManager.TYPE_MOBILE_FOTA;
    private static final int NETWORK_TYPE_IMS = ConnectivityManager.TYPE_MOBILE_IMS;
    private static final int NETWORK_TYPE_CBS = ConnectivityManager.TYPE_MOBILE_CBS;
    private static final int NETWORK_TYPE_IA = ConnectivityManager.TYPE_MOBILE_IA;
    private static final int NETWORK_TYPE_EMERGENCY = ConnectivityManager.TYPE_MOBILE_EMERGENCY;
    private static final int NETWORK_TYPE_MCX = 1001;  // far away from ConnectivityManager.TYPE_xxx
                                                       // constants as MCX isn't defined there.

    @IntDef(value = {
            REQUEST_TYPE_NORMAL,
            REQUEST_TYPE_HANDOVER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RequestNetworkType {}

    /**
     * Normal request for {@link #requestNetwork(NetworkRequest, int, LocalLog)}. For request
     * network, this adds the request to the {@link ApnContext}. If there were no network request
     * attached to the {@link ApnContext} earlier, this request setups a data connection.
     */
    public static final int REQUEST_TYPE_NORMAL = 1;

    /**
     * Handover request for {@link #requestNetwork(NetworkRequest, int, LocalLog)} or
     * {@link #releaseNetwork(NetworkRequest, int, LocalLog)}. For request network, this
     * initiates the handover data setup process. The existing data connection will be seamlessly
     * handover to the new network. For release network, this performs a data connection softly
     * clean up at the underlying layer (versus normal data release).
     */
    public static final int REQUEST_TYPE_HANDOVER = 2;

    @IntDef(value = {
            RELEASE_TYPE_NORMAL,
            RELEASE_TYPE_DETACH,
            RELEASE_TYPE_HANDOVER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ReleaseNetworkType {}

    /**
     * For release network, this is just removing the network request from the {@link ApnContext}.
     * Note this does not tear down the physical data connection. Normally the data connection is
     * torn down by connectivity service directly calling {@link NetworkAgent#unwanted()}.
     */
    public static final int RELEASE_TYPE_NORMAL = 1;

    /**
     * Detach request for {@link #releaseNetwork(NetworkRequest, int, LocalLog)} only. This
     * forces the APN context detach from the data connection. If this {@link ApnContext} is the
     * last one attached to the data connection, the data connection will be torn down, otherwise
     * the data connection remains active.
     */
    public static final int RELEASE_TYPE_DETACH = 2;

    /**
     * Handover request for {@link #releaseNetwork(NetworkRequest, int, LocalLog)}. For release
     * network, this performs a data connection softly clean up at the underlying layer (versus
     * normal data release).
     */
    public static final int RELEASE_TYPE_HANDOVER = 3;

    /** The extras for request network completion message */
    static final String DATA_COMPLETE_MSG_EXTRA_NETWORK_REQUEST = "extra_network_request";
    static final String DATA_COMPLETE_MSG_EXTRA_TRANSPORT_TYPE = "extra_transport_type";
    static final String DATA_COMPLETE_MSG_EXTRA_REQUEST_TYPE = "extra_request_type";
    static final String DATA_COMPLETE_MSG_EXTRA_SUCCESS = "extra_success";

    private final String mLogTag;

    public AtomicBoolean isCleanupRequired = new AtomicBoolean(false);

    private final TelephonyManager mTelephonyManager;

    private final AlarmManager mAlarmManager;

    /* Currently requested APN type (TODO: This should probably be a parameter not a member) */
    private int mRequestedApnType = ApnSetting.TYPE_DEFAULT;

    // All data enabling/disabling related settings
    private final DataEnabledSettings mDataEnabledSettings;

    /**
     * After detecting a potential connection problem, this is the max number
     * of subsequent polls before attempting recovery.
     */
    // 1 sec. default polling interval when screen is on.
    private static final int POLL_NETSTAT_MILLIS = 1000;
    // 10 min. default polling interval when screen is off.
    private static final int POLL_NETSTAT_SCREEN_OFF_MILLIS = 1000*60*10;
    // Default sent packets without ack which triggers initial recovery steps
    private static final int NUMBER_SENT_PACKETS_OF_HANG = 10;

    // Default for the data stall alarm while non-aggressive stall detection
    private static final int DATA_STALL_ALARM_NON_AGGRESSIVE_DELAY_IN_MS_DEFAULT = 1000 * 60 * 6;
    // Default for the data stall alarm for aggressive stall detection
    private static final int DATA_STALL_ALARM_AGGRESSIVE_DELAY_IN_MS_DEFAULT = 1000 * 60;

    private static final boolean DATA_STALL_SUSPECTED = true;
    private static final boolean DATA_STALL_NOT_SUSPECTED = false;

    private static final String INTENT_RECONNECT_ALARM =
            "com.android.internal.telephony.data-reconnect";
    private static final String INTENT_RECONNECT_ALARM_EXTRA_TYPE = "reconnect_alarm_extra_type";
    private static final String INTENT_RECONNECT_ALARM_EXTRA_REASON =
            "reconnect_alarm_extra_reason";
    private static final String INTENT_RECONNECT_ALARM_EXTRA_TRANSPORT_TYPE =
            "reconnect_alarm_extra_transport_type";

    private static final String INTENT_DATA_STALL_ALARM =
            "com.android.internal.telephony.data-stall";
    // Tag for tracking stale alarms
    private static final String INTENT_DATA_STALL_ALARM_EXTRA_TAG = "data_stall_alarm_extra_tag";
    private static final String INTENT_DATA_STALL_ALARM_EXTRA_TRANSPORT_TYPE =
            "data_stall_alarm_extra_transport_type";

    private DcTesterFailBringUpAll mDcTesterFailBringUpAll;
    private DcController mDcc;

    /** kept in sync with mApnContexts
     * Higher numbers are higher priority and sorted so highest priority is first */
    private final PriorityQueue<ApnContext>mPrioritySortedApnContexts =
            new PriorityQueue<ApnContext>(5,
            new Comparator<ApnContext>() {
                public int compare(ApnContext c1, ApnContext c2) {
                    return c2.priority - c1.priority;
                }
            } );

    /** all APN settings applicable to the current carrier */
    private ArrayList<ApnSetting> mAllApnSettings = new ArrayList<>();

    /** preferred apn */
    private ApnSetting mPreferredApn = null;

    /** Is packet service restricted by network */
    private boolean mIsPsRestricted = false;

    /** emergency apn Setting*/
    private ApnSetting mEmergencyApn = null;

    /* Once disposed dont handle any messages */
    private boolean mIsDisposed = false;

    private ContentResolver mResolver;

    /* Set to true with CMD_ENABLE_MOBILE_PROVISIONING */
    private boolean mIsProvisioning = false;

    /* The Url passed as object parameter in CMD_ENABLE_MOBILE_PROVISIONING */
    private String mProvisioningUrl = null;

    /* Indicating data service is bound or not */
    private boolean mDataServiceBound = false;

    /* Intent for the provisioning apn alarm */
    private static final String INTENT_PROVISIONING_APN_ALARM =
            "com.android.internal.telephony.provisioning_apn_alarm";

    /* Tag for tracking stale alarms */
    private static final String PROVISIONING_APN_ALARM_TAG_EXTRA = "provisioning.apn.alarm.tag";

    /* Debug property for overriding the PROVISIONING_APN_ALARM_DELAY_IN_MS */
    private static final String DEBUG_PROV_APN_ALARM = "persist.debug.prov_apn_alarm";

    /* Default for the provisioning apn alarm timeout */
    private static final int PROVISIONING_APN_ALARM_DELAY_IN_MS_DEFAULT = 1000 * 60 * 15;

    /* The provision apn alarm intent used to disable the provisioning apn */
    private PendingIntent mProvisioningApnAlarmIntent = null;

    /* Used to track stale provisioning apn alarms */
    private int mProvisioningApnAlarmTag = (int) SystemClock.elapsedRealtime();

    private AsyncChannel mReplyAc = new AsyncChannel();

    private final LocalLog mDataRoamingLeakageLog = new LocalLog(50);
    private final LocalLog mApnSettingsInitializationLog = new LocalLog(50);

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver () {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                // TODO: Evaluate hooking this up with DeviceStateMonitor
                if (DBG) log("screen on");
                mIsScreenOn = true;
                stopNetStatPoll();
                startNetStatPoll();
                restartDataStallAlarm();
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                if (DBG) log("screen off");
                mIsScreenOn = false;
                stopNetStatPoll();
                startNetStatPoll();
                restartDataStallAlarm();
            } else if (action.startsWith(INTENT_RECONNECT_ALARM)) {
                onActionIntentReconnectAlarm(intent);
            } else if (action.equals(INTENT_DATA_STALL_ALARM)) {
                onActionIntentDataStallAlarm(intent);
            } else if (action.equals(INTENT_PROVISIONING_APN_ALARM)) {
                if (DBG) log("Provisioning apn alarm");
                onActionIntentProvisioningApnAlarm(intent);
            } else if (action.equals(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED)) {
                if (DBG) log("received carrier config change");
                if (mIccRecords.get() != null && mIccRecords.get().getRecordsLoaded()) {
                    setDefaultDataRoamingEnabled();
                }
            } else {
                if (DBG) log("onReceive: Unknown action=" + action);
            }
        }
    };

    private final Runnable mPollNetStat = new Runnable() {
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
    private final DctOnSubscriptionsChangedListener
            mOnSubscriptionsChangedListener = new DctOnSubscriptionsChangedListener();

    private class DctOnSubscriptionsChangedListener extends OnSubscriptionsChangedListener {
        public final AtomicInteger mPreviousSubId =
                new AtomicInteger(SubscriptionManager.INVALID_SUBSCRIPTION_ID);

        /**
         * Callback invoked when there is any change to any SubscriptionInfo. Typically
         * this method invokes {@link SubscriptionManager#getActiveSubscriptionInfoList}
         */
        @Override
        public void onSubscriptionsChanged() {
            if (DBG) log("SubscriptionListener.onSubscriptionInfoChanged");
            // Set the network type, in case the radio does not restore it.
            int subId = mPhone.getSubId();
            if (SubscriptionManager.isValidSubscriptionId(subId)) {
                registerSettingsObserver();
            }
            if (SubscriptionManager.isValidSubscriptionId(subId) &&
                    mPreviousSubId.getAndSet(subId) != subId) {
                onRecordsLoadedOrSubIdChanged();
            }
        }
    };

    private final SettingsObserver mSettingsObserver;

    private void registerSettingsObserver() {
        mSettingsObserver.unobserve();
        String simSuffix = "";
        if (TelephonyManager.getDefault().getSimCount() > 1) {
            simSuffix = Integer.toString(mPhone.getSubId());
        }

        mSettingsObserver.observe(
                Settings.Global.getUriFor(Settings.Global.DATA_ROAMING + simSuffix),
                DctConstants.EVENT_ROAMING_SETTING_CHANGE);
        mSettingsObserver.observe(
                Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                DctConstants.EVENT_DEVICE_PROVISIONED_CHANGE);
    }

    /**
     * Maintain the sum of transmit and receive packets.
     *
     * The packet counts are initialized and reset to -1 and
     * remain -1 until they can be updated.
     */
    public static class TxRxSum {
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

        /**
         * Get Tcp Tx/Rx packet count from TrafficStats
         */
        public void updateTcpTxRxSum() {
            this.txPkts = TrafficStats.getMobileTcpTxPackets();
            this.rxPkts = TrafficStats.getMobileTcpRxPackets();
        }

        /**
         * Get total Tx/Rx packet count from TrafficStats
         */
        public void updateTotalTxRxSum() {
            this.txPkts = TrafficStats.getMobileTxPackets();
            this.rxPkts = TrafficStats.getMobileRxPackets();
        }
    }

    private void onActionIntentReconnectAlarm(Intent intent) {
        Message msg = obtainMessage(DctConstants.EVENT_DATA_RECONNECT);
        msg.setData(intent.getExtras());
        sendMessage(msg);
    }

    private void onDataReconnect(Bundle bundle) {
        String reason = bundle.getString(INTENT_RECONNECT_ALARM_EXTRA_REASON);
        String apnType = bundle.getString(INTENT_RECONNECT_ALARM_EXTRA_TYPE);

        int phoneSubId = mPhone.getSubId();
        int currSubId = bundle.getInt(PhoneConstants.SUBSCRIPTION_KEY,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);

        // Stop reconnect if not current subId is not correct.
        // FIXME STOPSHIP - phoneSubId is coming up as -1 way after boot and failing this?
        if (!SubscriptionManager.isValidSubscriptionId(currSubId) || (currSubId != phoneSubId)) {
            return;
        }

        int transportType = bundle.getInt(INTENT_RECONNECT_ALARM_EXTRA_TRANSPORT_TYPE, 0);
        if (transportType != mTransportType) {
            return;
        }

        ApnContext apnContext = mApnContexts.get(apnType);

        if (DBG) {
            log("onDataReconnect: mState=" + mState + " reason=" + reason + " apnType=" + apnType
                    + " apnContext=" + apnContext);
        }

        if ((apnContext != null) && (apnContext.isEnabled())) {
            apnContext.setReason(reason);
            DctConstants.State apnContextState = apnContext.getState();
            if (DBG) {
                log("onDataReconnect: apnContext state=" + apnContextState);
            }
            if ((apnContextState == DctConstants.State.FAILED)
                    || (apnContextState == DctConstants.State.IDLE)) {
                if (DBG) {
                    log("onDataReconnect: state is FAILED|IDLE, disassociate");
                }
                apnContext.releaseDataConnection("");
            } else {
                if (DBG) log("onDataReconnect: keep associated");
            }
            // TODO: IF already associated should we send the EVENT_TRY_SETUP_DATA???
            sendMessage(obtainMessage(DctConstants.EVENT_TRY_SETUP_DATA, apnContext));

            apnContext.setReconnectIntent(null);
        }
    }

    private void onActionIntentDataStallAlarm(Intent intent) {
        if (VDBG_STALL) log("onActionIntentDataStallAlarm: action=" + intent.getAction());

        int subId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        if (!SubscriptionManager.isValidSubscriptionId(subId) || (subId != mPhone.getSubId())) {
            return;
        }

        int transportType = intent.getIntExtra(INTENT_DATA_STALL_ALARM_EXTRA_TRANSPORT_TYPE, 0);
        if (transportType != mTransportType) {
            return;
        }

        Message msg = obtainMessage(DctConstants.EVENT_DATA_STALL_ALARM,
                intent.getAction());
        msg.arg1 = intent.getIntExtra(INTENT_DATA_STALL_ALARM_EXTRA_TAG, 0);
        sendMessage(msg);
    }

    private RegistrantList mAllDataDisconnectedRegistrants = new RegistrantList();

    // member variables
    private final Phone mPhone;
    private final UiccController mUiccController;
    private final AtomicReference<IccRecords> mIccRecords = new AtomicReference<IccRecords>();
    private DctConstants.Activity mActivity = DctConstants.Activity.NONE;
    private DctConstants.State mState = DctConstants.State.IDLE;
    private final Handler mDataConnectionTracker;

    private long mTxPkts;
    private long mRxPkts;
    private int mNetStatPollPeriod;
    private boolean mNetStatPollEnabled = false;

    private TxRxSum mDataStallTxRxSum = new TxRxSum(0, 0);
    // Used to track stale data stall alarms.
    private int mDataStallAlarmTag = (int) SystemClock.elapsedRealtime();
    // The current data stall alarm intent
    private PendingIntent mDataStallAlarmIntent = null;
    // Number of packets sent since the last received packet
    private long mSentSinceLastRecv;
    // Controls when a simple recovery attempt it to be tried
    private int mNoRecvPollCount = 0;
    // Reference counter for enabling fail fast
    private static int sEnableFailFastRefCounter = 0;
    // True if data stall detection is enabled
    private volatile boolean mDataStallNoRxEnabled = true;

    private volatile boolean mFailFast = false;

    // True when in voice call
    private boolean mInVoiceCall = false;

    /** Intent sent when the reconnect alarm fires. */
    private PendingIntent mReconnectIntent = null;

    // When false we will not auto attach and manually attaching is required.
    private boolean mAutoAttachOnCreationConfig = false;
    private AtomicBoolean mAutoAttachEnabled = new AtomicBoolean(false);

    // State of screen
    // (TODO: Reconsider tying directly to screen, maybe this is
    //        really a lower power mode")
    private boolean mIsScreenOn = true;

    /** Allows the generation of unique Id's for DataConnection objects */
    private AtomicInteger mUniqueIdGenerator = new AtomicInteger(0);

    /** The data connections. */
    private HashMap<Integer, DataConnection> mDataConnections =
            new HashMap<Integer, DataConnection>();

    /** Convert an ApnType string to Id (TODO: Use "enumeration" instead of String for ApnType) */
    private HashMap<String, Integer> mApnToDataConnectionId = new HashMap<String, Integer>();

    /** Phone.APN_TYPE_* ===> ApnContext */
    private final ConcurrentHashMap<String, ApnContext> mApnContexts =
            new ConcurrentHashMap<String, ApnContext>();

    private final SparseArray<ApnContext> mApnContextsByType = new SparseArray<ApnContext>();

    private int mDisconnectPendingCount = 0;

    private ArrayList<DataProfile> mLastDataProfileList = new ArrayList<>();

    /**
     * Handles changes to the APN db.
     */
    private class ApnChangeObserver extends ContentObserver {
        public ApnChangeObserver () {
            super(mDataConnectionTracker);
        }

        @Override
        public void onChange(boolean selfChange) {
            sendMessage(obtainMessage(DctConstants.EVENT_APN_CHANGED));
        }
    }

    //***** Instance Variables

    private boolean mReregisterOnReconnectFailure = false;


    //***** Constants

    // Used by puppetmaster/*/radio_stress.py
    private static final String PUPPET_MASTER_RADIO_STRESS_TEST = "gsm.defaultpdpcontext.active";

    private static final int POLL_PDP_MILLIS = 5 * 1000;

    private static final int PROVISIONING_SPINNER_TIMEOUT_MILLIS = 120 * 1000;

    static final Uri PREFERAPN_NO_UPDATE_URI_USING_SUBID =
                        Uri.parse("content://telephony/carriers/preferapn_no_update/subId/");
    static final String APN_ID = "apn_id";

    private boolean mCanSetPreferApn = false;

    private AtomicBoolean mAttached = new AtomicBoolean(false);

    /** Watches for changes to the APN db. */
    private ApnChangeObserver mApnObserver;

    private final String mProvisionActionName;
    private BroadcastReceiver mProvisionBroadcastReceiver;
    private ProgressDialog mProvisioningSpinner;

    private final DataServiceManager mDataServiceManager;

    private final int mTransportType;

    private DataStallRecoveryHandler mDsRecoveryHandler;

    /**
     * Request network completion message map. Key is the APN type, value is the list of completion
     * messages to be sent. Using a list because there might be multiple network requests for
     * the same APN type.
     */
    private final Map<Integer, List<Message>> mRequestNetworkCompletionMsgs = new HashMap<>();

    //***** Constructor
    public DcTracker(Phone phone, @TransportType int transportType) {
        super();
        mPhone = phone;
        if (DBG) log("DCT.constructor");
        mTelephonyManager = TelephonyManager.from(phone.getContext())
                .createForSubscriptionId(phone.getSubId());
        // The 'C' in tag indicates cellular, and 'I' indicates IWLAN. This is to distinguish
        // between two DcTrackers, one for each.
        String tagSuffix = "-" + ((transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                ? "C" : "I");
        if (mTelephonyManager.getPhoneCount() > 1) {
            tagSuffix += "-" + mPhone.getPhoneId();
        }
        mLogTag = "DCT" + tagSuffix;

        mTransportType = transportType;
        mDataServiceManager = new DataServiceManager(phone, transportType, tagSuffix);

        mResolver = mPhone.getContext().getContentResolver();
        mUiccController = UiccController.getInstance();
        mUiccController.registerForIccChanged(this, DctConstants.EVENT_ICC_CHANGED, null);
        mAlarmManager =
                (AlarmManager) mPhone.getContext().getSystemService(Context.ALARM_SERVICE);

        mDsRecoveryHandler = new DataStallRecoveryHandler();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(INTENT_DATA_STALL_ALARM);
        filter.addAction(INTENT_PROVISIONING_APN_ALARM);
        filter.addAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);

        mDataEnabledSettings = mPhone.getDataEnabledSettings();

        mDataEnabledSettings.registerForDataEnabledChanged(this,
                DctConstants.EVENT_DATA_ENABLED_CHANGED, null);
        mDataEnabledSettings.registerForDataEnabledOverrideChanged(this,
                DctConstants.EVENT_DATA_ENABLED_OVERRIDE_RULES_CHANGED);

        mPhone.getContext().registerReceiver(mIntentReceiver, filter, null, mPhone);

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mPhone.getContext());
        mAutoAttachEnabled.set(sp.getBoolean(Phone.DATA_DISABLED_ON_BOOT_KEY, false));

        mSubscriptionManager = SubscriptionManager.from(mPhone.getContext());
        mSubscriptionManager.addOnSubscriptionsChangedListener(mOnSubscriptionsChangedListener);

        HandlerThread dcHandlerThread = new HandlerThread("DcHandlerThread");
        dcHandlerThread.start();
        Handler dcHandler = new Handler(dcHandlerThread.getLooper());
        mDcc = DcController.makeDcc(mPhone, this, mDataServiceManager, dcHandler, tagSuffix);
        mDcTesterFailBringUpAll = new DcTesterFailBringUpAll(mPhone, dcHandler);

        mDataConnectionTracker = this;
        registerForAllEvents();
        update();
        mApnObserver = new ApnChangeObserver();
        phone.getContext().getContentResolver().registerContentObserver(
                Telephony.Carriers.CONTENT_URI, true, mApnObserver);

        initApnContexts();

        for (ApnContext apnContext : mApnContexts.values()) {
            // Register the reconnect and restart actions.
            filter = new IntentFilter();
            filter.addAction(INTENT_RECONNECT_ALARM + '.' + apnContext.getApnType());
            mPhone.getContext().registerReceiver(mIntentReceiver, filter, null, mPhone);
        }

        initEmergencyApnSetting();
        addEmergencyApnSetting();

        mProvisionActionName = "com.android.internal.telephony.PROVISION" + phone.getPhoneId();

        mSettingsObserver = new SettingsObserver(mPhone.getContext(), this);
        registerSettingsObserver();
    }

    @VisibleForTesting
    public DcTracker() {
        mLogTag = "DCT";
        mTelephonyManager = null;
        mAlarmManager = null;
        mPhone = null;
        mUiccController = null;
        mDataConnectionTracker = null;
        mProvisionActionName = null;
        mSettingsObserver = new SettingsObserver(null, this);
        mDataEnabledSettings = null;
        mTransportType = 0;
        mDataServiceManager = null;
    }

    public void registerServiceStateTrackerEvents() {
        mPhone.getServiceStateTracker().registerForDataConnectionAttached(mTransportType, this,
                DctConstants.EVENT_DATA_CONNECTION_ATTACHED, null);
        mPhone.getServiceStateTracker().registerForDataConnectionDetached(mTransportType, this,
                DctConstants.EVENT_DATA_CONNECTION_DETACHED, null);
        mPhone.getServiceStateTracker().registerForDataRoamingOn(this,
                DctConstants.EVENT_ROAMING_ON, null);
        mPhone.getServiceStateTracker().registerForDataRoamingOff(this,
                DctConstants.EVENT_ROAMING_OFF, null, true);
        mPhone.getServiceStateTracker().registerForPsRestrictedEnabled(this,
                DctConstants.EVENT_PS_RESTRICT_ENABLED, null);
        mPhone.getServiceStateTracker().registerForPsRestrictedDisabled(this,
                DctConstants.EVENT_PS_RESTRICT_DISABLED, null);
        mPhone.getServiceStateTracker().registerForDataRegStateOrRatChanged(mTransportType, this,
                DctConstants.EVENT_DATA_RAT_CHANGED, null);
    }

    public void unregisterServiceStateTrackerEvents() {
        mPhone.getServiceStateTracker().unregisterForDataConnectionAttached(mTransportType, this);
        mPhone.getServiceStateTracker().unregisterForDataConnectionDetached(mTransportType, this);
        mPhone.getServiceStateTracker().unregisterForDataRoamingOn(this);
        mPhone.getServiceStateTracker().unregisterForDataRoamingOff(this);
        mPhone.getServiceStateTracker().unregisterForPsRestrictedEnabled(this);
        mPhone.getServiceStateTracker().unregisterForPsRestrictedDisabled(this);
        mPhone.getServiceStateTracker().unregisterForDataRegStateOrRatChanged(mTransportType,
                this);
    }

    private void registerForAllEvents() {
        if (mTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
            mPhone.mCi.registerForAvailable(this, DctConstants.EVENT_RADIO_AVAILABLE, null);
            mPhone.mCi.registerForOffOrNotAvailable(this,
                    DctConstants.EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
            mPhone.mCi.registerForPcoData(this, DctConstants.EVENT_PCO_DATA_RECEIVED, null);
        }

        // Note, this is fragile - the Phone is now presenting a merged picture
        // of PS (volte) & CS and by diving into its internals you're just seeing
        // the CS data.  This works well for the purposes this is currently used for
        // but that may not always be the case.  Should probably be redesigned to
        // accurately reflect what we're really interested in (registerForCSVoiceCallEnded).
        mPhone.getCallTracker().registerForVoiceCallEnded(this,
                DctConstants.EVENT_VOICE_CALL_ENDED, null);
        mPhone.getCallTracker().registerForVoiceCallStarted(this,
                DctConstants.EVENT_VOICE_CALL_STARTED, null);
        registerServiceStateTrackerEvents();
        mDataServiceManager.registerForServiceBindingChanged(this,
                DctConstants.EVENT_DATA_SERVICE_BINDING_CHANGED, null);
    }

    public void dispose() {
        if (DBG) log("DCT.dispose");

        if (mProvisionBroadcastReceiver != null) {
            mPhone.getContext().unregisterReceiver(mProvisionBroadcastReceiver);
            mProvisionBroadcastReceiver = null;
        }
        if (mProvisioningSpinner != null) {
            mProvisioningSpinner.dismiss();
            mProvisioningSpinner = null;
        }

        cleanUpAllConnectionsInternal(true, null);

        mIsDisposed = true;
        mPhone.getContext().unregisterReceiver(mIntentReceiver);
        mUiccController.unregisterForIccChanged(this);
        mSettingsObserver.unobserve();

        mSubscriptionManager
                .removeOnSubscriptionsChangedListener(mOnSubscriptionsChangedListener);
        mDcc.dispose();
        mDcTesterFailBringUpAll.dispose();

        mPhone.getContext().getContentResolver().unregisterContentObserver(mApnObserver);
        mApnContexts.clear();
        mApnContextsByType.clear();
        mPrioritySortedApnContexts.clear();
        unregisterForAllEvents();

        destroyDataConnections();
    }

    private void unregisterForAllEvents() {
         //Unregister for all events
        if (mTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
            mPhone.mCi.unregisterForAvailable(this);
            mPhone.mCi.unregisterForOffOrNotAvailable(this);
            mPhone.mCi.unregisterForPcoData(this);
        }

        IccRecords r = mIccRecords.get();
        if (r != null) {
            r.unregisterForRecordsLoaded(this);
            mIccRecords.set(null);
        }
        mPhone.getCallTracker().unregisterForVoiceCallEnded(this);
        mPhone.getCallTracker().unregisterForVoiceCallStarted(this);
        unregisterServiceStateTrackerEvents();
        mDataServiceManager.unregisterForServiceBindingChanged(this);

        mDataEnabledSettings.unregisterForDataEnabledChanged(this);
        mDataEnabledSettings.unregisterForDataEnabledOverrideChanged(this);
    }

    /**
     * Reevaluate existing data connections when conditions change.
     *
     * For example, handle reverting restricted networks back to unrestricted. If we're changing
     * user data to enabled and this makes data truly enabled (not disabled by other factors) we
     * need to reevaluate and possibly add NET_CAPABILITY_NOT_RESTRICTED capability to the data
     * connection. This allows non-privilege apps to use the network.
     *
     * Or when we brought up a unmetered data connection while data is off, we only limit this
     * data connection for unmetered use only. When data is turned back on, we need to tear that
     * down so a full capable data connection can be re-established.
     */
    private void reevaluateDataConnections() {
        for (DataConnection dataConnection : mDataConnections.values()) {
            dataConnection.reevaluateRestrictedState();
        }
    }

    public long getSubId() {
        return mPhone.getSubId();
    }

    public DctConstants.Activity getActivity() {
        return mActivity;
    }

    private void setActivity(DctConstants.Activity activity) {
        log("setActivity = " + activity);
        mActivity = activity;
        mPhone.notifyDataActivity();
    }

    public void requestNetwork(NetworkRequest networkRequest, @RequestNetworkType int type,
                               Message onCompleteMsg) {
        final int apnType = ApnContext.getApnTypeFromNetworkRequest(networkRequest);
        final ApnContext apnContext = mApnContextsByType.get(apnType);
        if (apnContext != null) {
            apnContext.requestNetwork(networkRequest, type, onCompleteMsg);
        }
    }

    public void releaseNetwork(NetworkRequest networkRequest, @ReleaseNetworkType int type) {
        final int apnType = ApnContext.getApnTypeFromNetworkRequest(networkRequest);
        final ApnContext apnContext = mApnContextsByType.get(apnType);
        if (apnContext != null) {
            apnContext.releaseNetwork(networkRequest, type);
        }
    }

    // Turn telephony radio on or off.
    private void setRadio(boolean on) {
        final ITelephony phone = ITelephony.Stub.asInterface(ServiceManager.checkService("phone"));
        try {
            phone.setRadio(on);
        } catch (Exception e) {
            // Ignore.
        }
    }

    // Class to handle Intent dispatched with user selects the "Sign-in to network"
    // notification.
    private class ProvisionNotificationBroadcastReceiver extends BroadcastReceiver {
        private final String mNetworkOperator;
        // Mobile provisioning URL.  Valid while provisioning notification is up.
        // Set prior to notification being posted as URL contains ICCID which
        // disappears when radio is off (which is the case when notification is up).
        private final String mProvisionUrl;

        public ProvisionNotificationBroadcastReceiver(String provisionUrl, String networkOperator) {
            mNetworkOperator = networkOperator;
            mProvisionUrl = provisionUrl;
        }

        private void setEnableFailFastMobileData(int enabled) {
            sendMessage(obtainMessage(DctConstants.CMD_SET_ENABLE_FAIL_FAST_MOBILE_DATA, enabled, 0));
        }

        private void enableMobileProvisioning() {
            final Message msg = obtainMessage(DctConstants.CMD_ENABLE_MOBILE_PROVISIONING);
            msg.setData(Bundle.forPair(DctConstants.PROVISIONING_URL_KEY, mProvisionUrl));
            sendMessage(msg);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            // Turning back on the radio can take time on the order of a minute, so show user a
            // spinner so they know something is going on.
            log("onReceive : ProvisionNotificationBroadcastReceiver");
            mProvisioningSpinner = new ProgressDialog(context);
            mProvisioningSpinner.setTitle(mNetworkOperator);
            mProvisioningSpinner.setMessage(
                    // TODO: Don't borrow "Connecting..." i18n string; give Telephony a version.
                    context.getText(com.android.internal.R.string.media_route_status_connecting));
            mProvisioningSpinner.setIndeterminate(true);
            mProvisioningSpinner.setCancelable(true);
            // Allow non-Activity Service Context to create a View.
            mProvisioningSpinner.getWindow().setType(
                    WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            mProvisioningSpinner.show();
            // After timeout, hide spinner so user can at least use their device.
            // TODO: Indicate to user that it is taking an unusually long time to connect?
            sendMessageDelayed(obtainMessage(DctConstants.CMD_CLEAR_PROVISIONING_SPINNER,
                    mProvisioningSpinner), PROVISIONING_SPINNER_TIMEOUT_MILLIS);
            // This code is almost identical to the old
            // ConnectivityService.handleMobileProvisioningAction code.
            setRadio(true);
            setEnableFailFastMobileData(DctConstants.ENABLED);
            enableMobileProvisioning();
        }
    }

    @Override
    protected void finalize() {
        if(DBG && mPhone != null) log("finalize");
    }

    private ApnContext addApnContext(String type, NetworkConfig networkConfig) {
        ApnContext apnContext = new ApnContext(mPhone, type, mLogTag, networkConfig, this);
        mApnContexts.put(type, apnContext);
        mApnContextsByType.put(ApnSetting.getApnTypesBitmaskFromString(type), apnContext);
        mPrioritySortedApnContexts.add(apnContext);
        return apnContext;
    }

    private void initApnContexts() {
        log("initApnContexts: E");
        // Load device network attributes from resources
        String[] networkConfigStrings = mPhone.getContext().getResources().getStringArray(
                com.android.internal.R.array.networkAttributes);
        for (String networkConfigString : networkConfigStrings) {
            NetworkConfig networkConfig = new NetworkConfig(networkConfigString);
            ApnContext apnContext;

            switch (networkConfig.type) {
                case NETWORK_TYPE_DEFAULT:
                    apnContext = addApnContext(PhoneConstants.APN_TYPE_DEFAULT, networkConfig);
                    break;
                case NETWORK_TYPE_MMS:
                    apnContext = addApnContext(PhoneConstants.APN_TYPE_MMS, networkConfig);
                    break;
                case NETWORK_TYPE_SUPL:
                    apnContext = addApnContext(PhoneConstants.APN_TYPE_SUPL, networkConfig);
                    break;
                case NETWORK_TYPE_DUN:
                    apnContext = addApnContext(PhoneConstants.APN_TYPE_DUN, networkConfig);
                    break;
                case NETWORK_TYPE_HIPRI:
                    apnContext = addApnContext(PhoneConstants.APN_TYPE_HIPRI, networkConfig);
                    break;
                case NETWORK_TYPE_FOTA:
                    apnContext = addApnContext(PhoneConstants.APN_TYPE_FOTA, networkConfig);
                    break;
                case NETWORK_TYPE_IMS:
                    apnContext = addApnContext(PhoneConstants.APN_TYPE_IMS, networkConfig);
                    break;
                case NETWORK_TYPE_CBS:
                    apnContext = addApnContext(PhoneConstants.APN_TYPE_CBS, networkConfig);
                    break;
                case NETWORK_TYPE_IA:
                    apnContext = addApnContext(PhoneConstants.APN_TYPE_IA, networkConfig);
                    break;
                case NETWORK_TYPE_EMERGENCY:
                    apnContext = addApnContext(PhoneConstants.APN_TYPE_EMERGENCY, networkConfig);
                    break;
                case NETWORK_TYPE_MCX:
                    apnContext = addApnContext(PhoneConstants.APN_TYPE_MCX, networkConfig);
                    break;
                default:
                    log("initApnContexts: skipping unknown type=" + networkConfig.type);
                    continue;
            }
            log("initApnContexts: apnContext=" + apnContext);
        }

        if (VDBG) log("initApnContexts: X mApnContexts=" + mApnContexts);
    }

    public LinkProperties getLinkProperties(String apnType) {
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext != null) {
            DataConnection dataConnection = apnContext.getDataConnection();
            if (dataConnection != null) {
                if (DBG) log("return link properties for " + apnType);
                return dataConnection.getLinkProperties();
            }
        }
        if (DBG) log("return new LinkProperties");
        return new LinkProperties();
    }

    public NetworkCapabilities getNetworkCapabilities(String apnType) {
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext!=null) {
            DataConnection dataConnection = apnContext.getDataConnection();
            if (dataConnection != null) {
                if (DBG) {
                    log("get active pdp is not null, return NetworkCapabilities for " + apnType);
                }
                return dataConnection.getNetworkCapabilities();
            }
        }
        if (DBG) log("return new NetworkCapabilities");
        return new NetworkCapabilities();
    }

    // Return all active apn types
    public String[] getActiveApnTypes() {
        if (DBG) log("get all active apn types");
        ArrayList<String> result = new ArrayList<String>();

        for (ApnContext apnContext : mApnContexts.values()) {
            if (mAttached.get() && apnContext.isReady()) {
                result.add(apnContext.getApnType());
            }
        }

        return result.toArray(new String[0]);
    }

    // Return active apn of specific apn type
    public String getActiveApnString(String apnType) {
        if (VDBG) log( "get active apn string for type:" + apnType);
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext != null) {
            ApnSetting apnSetting = apnContext.getApnSetting();
            if (apnSetting != null) {
                return apnSetting.getApnName();
            }
        }
        return null;
    }

    /**
     * Returns {@link DctConstants.State} based on the state of the {@link DataConnection} that
     * contains a {@link ApnSetting} that supported the given apn type {@code anpType}.
     *
     * <p>
     * Assumes there is less than one {@link ApnSetting} can support the given apn type.
     */
    public DctConstants.State getState(String apnType) {
        final int apnTypeBitmask = ApnSetting.getApnTypesBitmaskFromString(apnType);
        for (DataConnection dc : mDataConnections.values()) {
            ApnSetting apnSetting = dc.getApnSetting();
            if (apnSetting != null && apnSetting.canHandleType(apnTypeBitmask)) {
                if (dc.isActive()) {
                    return DctConstants.State.CONNECTED;
                } else if (dc.isActivating()) {
                    return DctConstants.State.CONNECTING;
                } else if (dc.isInactive()) {
                    return DctConstants.State.IDLE;
                } else if (dc.isDisconnecting()) {
                    return DctConstants.State.DISCONNECTING;
                }
            }
        }

        return DctConstants.State.IDLE;
    }

    // Return if apn type is a provisioning apn.
    private boolean isProvisioningApn(String apnType) {
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext != null) {
            return apnContext.isProvisioningApn();
        }
        return false;
    }

    // Return state of overall
    public DctConstants.State getOverallState() {
        boolean isConnecting = false;
        boolean isFailed = true; // All enabled Apns should be FAILED.
        boolean isAnyEnabled = false;

        for (ApnContext apnContext : mApnContexts.values()) {
            if (apnContext.isEnabled()) {
                isAnyEnabled = true;
                switch (apnContext.getState()) {
                    case CONNECTED:
                    case DISCONNECTING:
                        if (VDBG) log("overall state is CONNECTED");
                        return DctConstants.State.CONNECTED;
                    case CONNECTING:
                        isConnecting = true;
                        isFailed = false;
                        break;
                    case IDLE:
                    case RETRYING:
                        isFailed = false;
                        break;
                    default:
                        isAnyEnabled = true;
                        break;
                }
            }
        }

        if (!isAnyEnabled) { // Nothing enabled. return IDLE.
            if (VDBG) log( "overall state is IDLE");
            return DctConstants.State.IDLE;
        }

        if (isConnecting) {
            if (VDBG) log( "overall state is CONNECTING");
            return DctConstants.State.CONNECTING;
        } else if (!isFailed) {
            if (VDBG) log( "overall state is IDLE");
            return DctConstants.State.IDLE;
        } else {
            if (VDBG) log( "overall state is FAILED");
            return DctConstants.State.FAILED;
        }
    }

    //****** Called from ServiceStateTracker
    /**
     * Invoked when ServiceStateTracker observes a transition from GPRS
     * attach to detach.
     */
    private void onDataConnectionDetached() {
        /*
         * We presently believe it is unnecessary to tear down the PDP context
         * when GPRS detaches, but we should stop the network polling.
         */
        if (DBG) log ("onDataConnectionDetached: stop polling and notify detached");
        stopNetStatPoll();
        stopDataStallAlarm();
        mPhone.notifyDataConnection();
        mAttached.set(false);
    }

    private void onDataConnectionAttached() {
        if (DBG) log("onDataConnectionAttached");
        mAttached.set(true);
        if (getOverallState() == DctConstants.State.CONNECTED) {
            if (DBG) log("onDataConnectionAttached: start polling notify attached");
            startNetStatPoll();
            startDataStallAlarm(DATA_STALL_NOT_SUSPECTED);
            mPhone.notifyDataConnection();
        }
        if (mAutoAttachOnCreationConfig) {
            mAutoAttachEnabled.set(true);
        }
        setupDataOnAllConnectableApns(Phone.REASON_DATA_ATTACHED, RetryFailures.ALWAYS);
    }

    /**
     * Check if it is allowed to make a data connection (without checking APN context specific
     * conditions).
     *
     * @param dataConnectionReasons Data connection allowed or disallowed reasons as the output
     *                              param. It's okay to pass null here and no reasons will be
     *                              provided.
     * @return True if data connection is allowed, otherwise false.
     */
    public boolean isDataAllowed(DataConnectionReasons dataConnectionReasons) {
        return isDataAllowed(null, REQUEST_TYPE_NORMAL, dataConnectionReasons);
    }

    /**
     * Check if it is allowed to make a data connection for a given APN type.
     *
     * @param apnContext APN context. If passing null, then will only check general but not APN
     *                   specific conditions (e.g. APN state, metered/unmetered APN).
     * @param requestType Setup data request type.
     * @param dataConnectionReasons Data connection allowed or disallowed reasons as the output
     *                              param. It's okay to pass null here and no reasons will be
     *                              provided.
     * @return True if data connection is allowed, otherwise false.
     */
    public boolean isDataAllowed(ApnContext apnContext, @RequestNetworkType int requestType,
                                 DataConnectionReasons dataConnectionReasons) {
        // Step 1: Get all environment conditions.
        // Step 2: Special handling for emergency APN.
        // Step 3. Build disallowed reasons.
        // Step 4: Determine if data should be allowed in some special conditions.

        DataConnectionReasons reasons = new DataConnectionReasons();

        // Step 1: Get all environment conditions.
        final boolean internalDataEnabled = mDataEnabledSettings.isInternalDataEnabled();
        boolean attachedState = mAttached.get();
        boolean desiredPowerState = mPhone.getServiceStateTracker().getDesiredPowerState();
        boolean radioStateFromCarrier = mPhone.getServiceStateTracker().getPowerStateFromCarrier();
        // TODO: Remove this hack added by ag/641832.
        int dataRat = getDataRat();
        if (dataRat == ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN) {
            desiredPowerState = true;
            radioStateFromCarrier = true;
        }

        boolean recordsLoaded = mIccRecords.get() != null && mIccRecords.get().getRecordsLoaded();

        boolean defaultDataSelected = SubscriptionManager.isValidSubscriptionId(
                SubscriptionManager.getDefaultDataSubscriptionId());

        boolean isMeteredApnType = apnContext == null
                || ApnSettingUtils.isMeteredApnType(ApnSetting.getApnTypesBitmaskFromString(
                        apnContext.getApnType()) , mPhone);

        PhoneConstants.State phoneState = PhoneConstants.State.IDLE;
        // Note this is explicitly not using mPhone.getState.  See b/19090488.
        // mPhone.getState reports the merge of CS and PS (volte) voice call state
        // but we only care about CS calls here for data/voice concurrency issues.
        // Calling getCallTracker currently gives you just the CS side where the
        // ImsCallTracker is held internally where applicable.
        // This should be redesigned to ask explicitly what we want:
        // voiceCallStateAllowDataCall, or dataCallAllowed or something similar.
        if (mPhone.getCallTracker() != null) {
            phoneState = mPhone.getCallTracker().getState();
        }

        // Step 2: Special handling for emergency APN.
        if (apnContext != null
                && apnContext.getApnType().equals(PhoneConstants.APN_TYPE_EMERGENCY)
                && apnContext.isConnectable()) {
            // If this is an emergency APN, as long as the APN is connectable, we
            // should allow it.
            if (dataConnectionReasons != null) {
                dataConnectionReasons.add(DataAllowedReasonType.EMERGENCY_APN);
            }
            // Bail out without further checks.
            return true;
        }

        // Step 3. Build disallowed reasons.
        if (apnContext != null && !apnContext.isConnectable()) {
            reasons.add(DataDisallowedReasonType.APN_NOT_CONNECTABLE);
        }

        // In legacy mode, if RAT is IWLAN then don't allow default/IA PDP at all.
        // Rest of APN types can be evaluated for remaining conditions.
        if ((apnContext != null && (apnContext.getApnType().equals(PhoneConstants.APN_TYPE_DEFAULT)
                || apnContext.getApnType().equals(PhoneConstants.APN_TYPE_IA)))
                && mPhone.getTransportManager().isInLegacyMode()
                && dataRat == ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN) {
            reasons.add(DataDisallowedReasonType.ON_IWLAN);
        }

        if (isEmergency()) {
            reasons.add(DataDisallowedReasonType.IN_ECBM);
        }

        if (!attachedState && !shouldAutoAttach() && requestType != REQUEST_TYPE_HANDOVER) {
            reasons.add(DataDisallowedReasonType.NOT_ATTACHED);
        }
        if (!recordsLoaded) {
            reasons.add(DataDisallowedReasonType.RECORD_NOT_LOADED);
        }
        if (phoneState != PhoneConstants.State.IDLE
                && !mPhone.getServiceStateTracker().isConcurrentVoiceAndDataAllowed()) {
            reasons.add(DataDisallowedReasonType.INVALID_PHONE_STATE);
            reasons.add(DataDisallowedReasonType.CONCURRENT_VOICE_DATA_NOT_ALLOWED);
        }
        if (!internalDataEnabled) {
            reasons.add(DataDisallowedReasonType.INTERNAL_DATA_DISABLED);
        }
        if (!defaultDataSelected) {
            reasons.add(DataDisallowedReasonType.DEFAULT_DATA_UNSELECTED);
        }
        if (mPhone.getServiceState().getDataRoaming() && !getDataRoamingEnabled()) {
            reasons.add(DataDisallowedReasonType.ROAMING_DISABLED);
        }
        if (mIsPsRestricted) {
            reasons.add(DataDisallowedReasonType.PS_RESTRICTED);
        }
        if (!desiredPowerState) {
            reasons.add(DataDisallowedReasonType.UNDESIRED_POWER_STATE);
        }
        if (!radioStateFromCarrier) {
            reasons.add(DataDisallowedReasonType.RADIO_DISABLED_BY_CARRIER);
        }

        boolean isDataEnabled = apnContext == null ? mDataEnabledSettings.isDataEnabled()
                : mDataEnabledSettings.isDataEnabled(apnContext.getApnTypeBitmask());

        if (!isDataEnabled) {
            reasons.add(DataDisallowedReasonType.DATA_DISABLED);
        }

        // If there are hard disallowed reasons, we should not allow data connection no matter what.
        if (reasons.containsHardDisallowedReasons()) {
            if (dataConnectionReasons != null) {
                dataConnectionReasons.copyFrom(reasons);
            }
            return false;
        }

        // Step 4: Determine if data should be allowed in some special conditions.

        // At this point, if data is not allowed, it must be because of the soft reasons. We
        // should start to check some special conditions that data will be allowed.
        if (!reasons.allowed()) {
            // If the device is on IWLAN, then all data should be unmetered. Check if the transport
            // is WLAN (for AP-assisted mode devices), or RAT equals IWLAN (for legacy mode devices)
            if (mTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WLAN
                    || (mPhone.getTransportManager().isInLegacyMode()
                    && dataRat == ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN)) {
                reasons.add(DataAllowedReasonType.UNMETERED_APN);
            // Or if the data is on cellular, and the APN type is determined unmetered by the
            // configuration.
            } else if (mTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN
                    && !isMeteredApnType) {
                reasons.add(DataAllowedReasonType.UNMETERED_APN);
            }

            // If the request is restricted and there are only soft disallowed reasons (e.g. data
            // disabled, data roaming disabled) existing, we should allow the data.
            if (apnContext != null
                    && apnContext.hasRestrictedRequests(true)
                    && !reasons.allowed()) {
                reasons.add(DataAllowedReasonType.RESTRICTED_REQUEST);
            }
        } else {
            // If there is no disallowed reasons, then we should allow the data request with
            // normal reason.
            reasons.add(DataAllowedReasonType.NORMAL);
        }

        if (dataConnectionReasons != null) {
            dataConnectionReasons.copyFrom(reasons);
        }

        return reasons.allowed();
    }

    // arg for setupDataOnAllConnectableApns
    private enum RetryFailures {
        // retry failed networks always (the old default)
        ALWAYS,
        // retry only when a substantial change has occurred.  Either:
        // 1) we were restricted by voice/data concurrency and aren't anymore
        // 2) our apn list has change
        ONLY_ON_CHANGE
    };

    private void setupDataOnAllConnectableApns(String reason, RetryFailures retryFailures) {
        if (VDBG) log("setupDataOnAllConnectableApns: " + reason);

        if (DBG && !VDBG) {
            StringBuilder sb = new StringBuilder(120);
            for (ApnContext apnContext : mPrioritySortedApnContexts) {
                sb.append(apnContext.getApnType());
                sb.append(":[state=");
                sb.append(apnContext.getState());
                sb.append(",enabled=");
                sb.append(apnContext.isEnabled());
                sb.append("] ");
            }
            log("setupDataOnAllConnectableApns: " + reason + " " + sb);
        }

        for (ApnContext apnContext : mPrioritySortedApnContexts) {
            setupDataOnConnectableApn(apnContext, reason, retryFailures);
        }
    }

    private void setupDataOnConnectableApn(ApnContext apnContext, String reason,
            RetryFailures retryFailures) {
        if (VDBG) log("setupDataOnAllConnectableApns: apnContext " + apnContext);

        if (apnContext.getState() == DctConstants.State.FAILED
                || apnContext.getState() == DctConstants.State.RETRYING) {
            if (retryFailures == RetryFailures.ALWAYS) {
                apnContext.releaseDataConnection(reason);
            } else if (!apnContext.isConcurrentVoiceAndDataAllowed()
                    && mPhone.getServiceStateTracker().isConcurrentVoiceAndDataAllowed()) {
                // RetryFailures.ONLY_ON_CHANGE - check if voice concurrency has changed
                apnContext.releaseDataConnection(reason);
            }
        }
        if (apnContext.isConnectable()) {
            log("isConnectable() call trySetupData");
            apnContext.setReason(reason);
            trySetupData(apnContext, REQUEST_TYPE_NORMAL);
        }
    }

    boolean isEmergency() {
        final boolean result = mPhone.isInEcm() || mPhone.isInEmergencyCall();
        log("isEmergency: result=" + result);
        return result;
    }

    private boolean trySetupData(ApnContext apnContext, @RequestNetworkType int requestType) {

        if (mPhone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            apnContext.setState(DctConstants.State.CONNECTED);
            mPhone.notifyDataConnection(apnContext.getApnType());

            log("trySetupData: X We're on the simulator; assuming connected retValue=true");
            return true;
        }

        DataConnectionReasons dataConnectionReasons = new DataConnectionReasons();
        boolean isDataAllowed = isDataAllowed(apnContext, requestType, dataConnectionReasons);
        String logStr = "trySetupData for APN type " + apnContext.getApnType() + ", reason: "
                + apnContext.getReason() + ", requestType=" + requestTypeToString(requestType)
                + ". " + dataConnectionReasons.toString();
        if (DBG) log(logStr);
        apnContext.requestLog(logStr);
        if (isDataAllowed) {
            if (apnContext.getState() == DctConstants.State.FAILED) {
                String str = "trySetupData: make a FAILED ApnContext IDLE so its reusable";
                if (DBG) log(str);
                apnContext.requestLog(str);
                apnContext.setState(DctConstants.State.IDLE);
            }
            int radioTech = getDataRat();
            if (radioTech == ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN) {
                radioTech = getVoiceRat();
            }
            log("service state=" + mPhone.getServiceState());
            apnContext.setConcurrentVoiceAndDataAllowed(mPhone.getServiceStateTracker()
                    .isConcurrentVoiceAndDataAllowed());
            if (apnContext.getState() == DctConstants.State.IDLE) {
                ArrayList<ApnSetting> waitingApns =
                        buildWaitingApns(apnContext.getApnType(), radioTech);
                if (waitingApns.isEmpty()) {
                    notifyNoData(DataFailCause.MISSING_UNKNOWN_APN, apnContext);
                    String str = "trySetupData: X No APN found retValue=false";
                    if (DBG) log(str);
                    apnContext.requestLog(str);
                    return false;
                } else {
                    apnContext.setWaitingApns(waitingApns);
                    if (DBG) {
                        log ("trySetupData: Create from mAllApnSettings : "
                                    + apnListToString(mAllApnSettings));
                    }
                }
            }

            boolean retValue = setupData(apnContext, radioTech, requestType);

            if (DBG) log("trySetupData: X retValue=" + retValue);
            return retValue;
        } else {
            StringBuilder str = new StringBuilder();

            str.append("trySetupData failed. apnContext = [type=" + apnContext.getApnType()
                    + ", mState=" + apnContext.getState() + ", apnEnabled="
                    + apnContext.isEnabled() + ", mDependencyMet="
                    + apnContext.isDependencyMet() + "] ");

            if (!mDataEnabledSettings.isDataEnabled()) {
                str.append("isDataEnabled() = false. " + mDataEnabledSettings);
            }

            // If this is a data retry, we should set the APN state to FAILED so it won't stay
            // in RETRYING forever.
            if (apnContext.getState() == DctConstants.State.RETRYING) {
                apnContext.setState(DctConstants.State.FAILED);
                str.append(" Stop retrying.");
            }

            if (DBG) log(str.toString());
            apnContext.requestLog(str.toString());
            return false;
        }
    }

    /**
     * Clean up all data connections. Note this is just detach the APN context from the data
     * connection. After all APN contexts are detached from the data connection, the data
     * connection will be torn down.
     *
     * @param reason Reason for the clean up.
     */
    public void cleanUpAllConnections(String reason) {
        log("cleanUpAllConnections");
        Message msg = obtainMessage(DctConstants.EVENT_CLEAN_UP_ALL_CONNECTIONS);
        msg.obj = reason;
        sendMessage(msg);
    }

    /**
     * Clean up all data connections by detaching the APN contexts from the data connections, which
     * eventually tearing down all data connections after all APN contexts are detached from the
     * data connections.
     *
     * @param detach {@code true} if detaching APN context from the underlying data connection (when
     * no other APN context is attached to the data connection, the data connection will be torn
     * down.) {@code false} to only reset the data connection's state machine.
     *
     * @param reason reason for the clean up.
     * @return boolean - true if we did cleanup any connections, false if they
     *                   were already all disconnected.
     */
    private boolean cleanUpAllConnectionsInternal(boolean detach, String reason) {
        if (DBG) log("cleanUpAllConnectionsInternal: detach=" + detach + " reason=" + reason);
        boolean didDisconnect = false;
        boolean disableMeteredOnly = false;

        // reasons that only metered apn will be torn down
        if (!TextUtils.isEmpty(reason)) {
            disableMeteredOnly = reason.equals(Phone.REASON_DATA_SPECIFIC_DISABLED) ||
                    reason.equals(Phone.REASON_ROAMING_ON) ||
                    reason.equals(Phone.REASON_CARRIER_ACTION_DISABLE_METERED_APN);
        }

        for (ApnContext apnContext : mApnContexts.values()) {
            // Exclude the IMS APN from single data connection case.
            if (reason.equals(Phone.REASON_SINGLE_PDN_ARBITRATION)
                    && apnContext.getApnType().equals(PhoneConstants.APN_TYPE_IMS)) {
                continue;
            }

            if (shouldCleanUpConnection(apnContext, disableMeteredOnly)) {
                // TODO - only do cleanup if not disconnected
                if (apnContext.isDisconnected() == false) didDisconnect = true;
                apnContext.setReason(reason);
                cleanUpConnectionInternal(detach, RELEASE_TYPE_DETACH, apnContext);
            } else if (DBG) {
                log("cleanUpAllConnectionsInternal: APN type " + apnContext.getApnType()
                        + " shouldn't be cleaned up.");
            }
        }

        stopNetStatPoll();
        stopDataStallAlarm();

        // TODO: Do we need mRequestedApnType?
        mRequestedApnType = ApnSetting.TYPE_DEFAULT;

        log("cleanUpAllConnectionsInternal: mDisconnectPendingCount = "
                + mDisconnectPendingCount);
        if (detach && mDisconnectPendingCount == 0) {
            notifyAllDataDisconnected();
        }

        return didDisconnect;
    }

    boolean shouldCleanUpConnection(ApnContext apnContext, boolean disableMeteredOnly) {
        if (apnContext == null) return false;

        // If meteredOnly is false, clean up all connections.
        if (!disableMeteredOnly) return true;

        // If meteredOnly is true, and apnSetting is null or it's un-metered, no need to clean up.
        ApnSetting apnSetting = apnContext.getApnSetting();
        if (apnSetting == null || !ApnSettingUtils.isMetered(apnSetting, mPhone)) return false;

        boolean isRoaming = mPhone.getServiceState().getDataRoaming();
        boolean isDataRoamingDisabled = !getDataRoamingEnabled();
        boolean isDataDisabled = !mDataEnabledSettings.isDataEnabled(
                apnSetting.getApnTypeBitmask());

        // Should clean up if its data is disabled, or data roaming is disabled while roaming.
        return isDataDisabled || (isRoaming && isDataRoamingDisabled);
    }

    /**
     * Detach the APN context from the associated data connection. This data connection might be
     * torn down if no other APN context is attached to it.
     *
     * @param apnContext The APN context to be detached
     */
    void cleanUpConnection(ApnContext apnContext) {
        if (DBG) log("cleanUpConnection: apnContext=" + apnContext);
        Message msg = obtainMessage(DctConstants.EVENT_CLEAN_UP_CONNECTION);
        msg.arg2 = 0;
        msg.obj = apnContext;
        sendMessage(msg);
    }

    /**
     * Detach the APN context from the associated data connection. This data connection will be
     * torn down if no other APN context is attached to it.
     *
     * @param detach {@code true} if detaching APN context from the underlying data connection (when
     * no other APN context is attached to the data connection, the data connection will be torn
     * down.) {@code false} to only reset the data connection's state machine.
     * @param releaseType Data release type.
     * @param apnContext The APN context to be detached.
     */
    private void cleanUpConnectionInternal(boolean detach, @ReleaseNetworkType int releaseType,
                                           ApnContext apnContext) {
        if (apnContext == null) {
            if (DBG) log("cleanUpConnectionInternal: apn context is null");
            return;
        }

        DataConnection dataConnection = apnContext.getDataConnection();
        String str = "cleanUpConnectionInternal: detach=" + detach + " reason="
                + apnContext.getReason();
        if (VDBG) log(str + " apnContext=" + apnContext);
        apnContext.requestLog(str);
        if (detach) {
            if (apnContext.isDisconnected()) {
                // The request is detach and but ApnContext is not connected.
                // If apnContext is not enabled anymore, break the linkage to the data connection.
                apnContext.releaseDataConnection("");
            } else {
                // Connection is still there. Try to clean up.
                if (dataConnection != null) {
                    if (apnContext.getState() != DctConstants.State.DISCONNECTING) {
                        boolean disconnectAll = false;
                        if (PhoneConstants.APN_TYPE_DUN.equals(apnContext.getApnType())
                                && ServiceState.isCdma(getDataRat())) {
                            if (DBG) {
                                log("cleanUpConnectionInternal: disconnectAll DUN connection");
                            }
                            // For CDMA DUN, we need to tear it down immediately. A new data
                            // connection will be reestablished with correct profile id.
                            disconnectAll = true;
                        }
                        final int generation = apnContext.getConnectionGeneration();
                        str = "cleanUpConnectionInternal: tearing down"
                                + (disconnectAll ? " all" : "") + " using gen#" + generation;
                        if (DBG) log(str + "apnContext=" + apnContext);
                        apnContext.requestLog(str);
                        Pair<ApnContext, Integer> pair = new Pair<>(apnContext, generation);
                        Message msg = obtainMessage(DctConstants.EVENT_DISCONNECT_DONE, pair);

                        if (disconnectAll || releaseType == RELEASE_TYPE_HANDOVER) {
                            dataConnection.tearDownAll(apnContext.getReason(), releaseType, msg);
                        } else {
                            dataConnection.tearDown(apnContext, apnContext.getReason(), msg);
                        }

                        apnContext.setState(DctConstants.State.DISCONNECTING);
                        mDisconnectPendingCount++;
                    }
                } else {
                    // apn is connected but no reference to the data connection.
                    // Should not be happen, but reset the state in case.
                    apnContext.setState(DctConstants.State.IDLE);
                    apnContext.requestLog("cleanUpConnectionInternal: connected, bug no dc");
                    mPhone.notifyDataConnection(apnContext.getApnType());
                }
            }
        } else {
            // force clean up the data connection.
            if (dataConnection != null) dataConnection.reset();
            apnContext.setState(DctConstants.State.IDLE);
            mPhone.notifyDataConnection(apnContext.getApnType());
            apnContext.setDataConnection(null);
        }

        // Make sure reconnection alarm is cleaned up if there is no ApnContext
        // associated to the connection.
        if (dataConnection != null) {
            cancelReconnectAlarm(apnContext);
        }
        str = "cleanUpConnectionInternal: X detach=" + detach + " reason="
                + apnContext.getReason();
        if (DBG) log(str + " apnContext=" + apnContext + " dc=" + apnContext.getDataConnection());
    }

    /**
     * Fetch the DUN apns
     * @return a list of DUN ApnSetting objects
     */
    @VisibleForTesting
    public @NonNull ArrayList<ApnSetting> fetchDunApns() {
        if (SystemProperties.getBoolean("net.tethering.noprovisioning", false)) {
            log("fetchDunApns: net.tethering.noprovisioning=true ret: empty list");
            return new ArrayList<ApnSetting>(0);
        }
        int bearer = getDataRat();
        ArrayList<ApnSetting> dunCandidates = new ArrayList<ApnSetting>();
        ArrayList<ApnSetting> retDunSettings = new ArrayList<ApnSetting>();

        // Places to look for tether APN in order: TETHER_DUN_APN setting (to be deprecated soon),
        // APN database
        String apnData = Settings.Global.getString(mResolver, Settings.Global.TETHER_DUN_APN);
        if (!TextUtils.isEmpty(apnData)) {
            dunCandidates.addAll(ApnSetting.arrayFromString(apnData));
            if (VDBG) log("fetchDunApns: dunCandidates from Setting: " + dunCandidates);
        }

        if (dunCandidates.isEmpty()) {
            if (!ArrayUtils.isEmpty(mAllApnSettings)) {
                for (ApnSetting apn : mAllApnSettings) {
                    if (apn.canHandleType(ApnSetting.TYPE_DUN)) {
                        dunCandidates.add(apn);
                    }
                }
                if (VDBG) log("fetchDunApns: dunCandidates from database: " + dunCandidates);
            }
        }

        for (ApnSetting dunSetting : dunCandidates) {
            if (!dunSetting.canSupportNetworkType(
                    ServiceState.rilRadioTechnologyToNetworkType(bearer))) {
                continue;
            }
            retDunSettings.add(dunSetting);
        }

        if (VDBG) log("fetchDunApns: dunSettings=" + retDunSettings);
        return retDunSettings;
    }

    private int getPreferredApnSetId() {
        Cursor c = mPhone.getContext().getContentResolver()
                .query(Uri.withAppendedPath(Telephony.Carriers.CONTENT_URI,
                    "preferapnset/subId/" + mPhone.getSubId()),
                        new String[] {Telephony.Carriers.APN_SET_ID}, null, null, null);
        if (c == null) {
            loge("getPreferredApnSetId: cursor is null");
            return Telephony.Carriers.NO_APN_SET_ID;
        }

        int setId;
        if (c.getCount() < 1) {
            loge("getPreferredApnSetId: no APNs found");
            setId = Telephony.Carriers.NO_APN_SET_ID;
        } else {
            c.moveToFirst();
            setId = c.getInt(0 /* index of Telephony.Carriers.APN_SET_ID */);
        }

        if (!c.isClosed()) {
            c.close();
        }
        return setId;
    }

    public boolean hasMatchedTetherApnSetting() {
        ArrayList<ApnSetting> matches = fetchDunApns();
        log("hasMatchedTetherApnSetting: APNs=" + matches);
        return matches.size() > 0;
    }

    /**
     * @return the {@link DataConnection} with the given context id {@code cid}.
     */
    public DataConnection getDataConnectionByContextId(int cid) {
        return mDcc.getActiveDcByCid(cid);
    }

    /**
     * @return the {@link DataConnection} with the given APN context. Null if no data connection
     * is found.
     */
    public @Nullable DataConnection getDataConnectionByApnType(String apnType) {
        // TODO: Clean up all APN type in string usage
        ApnContext apnContext = mApnContexts.get(apnType);
        if (apnContext != null) {
            return apnContext.getDataConnection();
        }
        return null;
    }

    /**
     * Cancels the alarm associated with apnContext.
     *
     * @param apnContext on which the alarm should be stopped.
     */
    private void cancelReconnectAlarm(ApnContext apnContext) {
        if (apnContext == null) return;

        PendingIntent intent = apnContext.getReconnectIntent();

        if (intent != null) {
                AlarmManager am =
                    (AlarmManager) mPhone.getContext().getSystemService(Context.ALARM_SERVICE);
                am.cancel(intent);
                apnContext.setReconnectIntent(null);
        }
    }

    boolean isPermanentFailure(@DataFailCause.FailCause int dcFailCause) {
        return (DataFailCause.isPermanentFailure(mPhone.getContext(), dcFailCause,
                mPhone.getSubId())
                && (mAttached.get() == false || dcFailCause != DataFailCause.SIGNAL_LOST));
    }

    private DataConnection findFreeDataConnection() {
        for (DataConnection dataConnection : mDataConnections.values()) {
            boolean inUse = false;
            for (ApnContext apnContext : mApnContexts.values()) {
                if (apnContext.getDataConnection() == dataConnection) {
                    inUse = true;
                    break;
                }
            }
            if (!inUse) {
                if (DBG) {
                    log("findFreeDataConnection: found free DataConnection=" + dataConnection);
                }
                return dataConnection;
            }
        }
        log("findFreeDataConnection: NO free DataConnection");
        return null;
    }

    /**
     * Setup a data connection based on given APN type.
     *
     * @param apnContext APN context
     * @param radioTech RAT of the data connection
     * @param requestType Data request type
     * @return True if successful, otherwise false.
     */
    private boolean setupData(ApnContext apnContext, int radioTech,
                              @RequestNetworkType int requestType) {
        if (DBG) {
            log("setupData: apnContext=" + apnContext + ", requestType="
                    + requestTypeToString(requestType));
        }
        apnContext.requestLog("setupData. requestType=" + requestTypeToString(requestType));
        ApnSetting apnSetting;
        DataConnection dataConnection = null;

        apnSetting = apnContext.getNextApnSetting();

        if (apnSetting == null) {
            if (DBG) log("setupData: return for no apn found!");
            return false;
        }

        // profile id is only meaningful when the profile is persistent on the modem.
        int profileId = DATA_PROFILE_INVALID;
        if (apnSetting.isPersistent()) {
            profileId = apnSetting.getProfileId();
            if (profileId == DATA_PROFILE_DEFAULT) {
                profileId = getApnProfileID(apnContext.getApnType());
            }
        }

        // On CDMA, if we're explicitly asking for DUN, we need have
        // a dun-profiled connection so we can't share an existing one
        // On GSM/LTE we can share existing apn connections provided they support
        // this type.
        if (!apnContext.getApnType().equals(PhoneConstants.APN_TYPE_DUN)
                || ServiceState.isGsm(getDataRat())) {
            dataConnection = checkForCompatibleConnectedApnContext(apnContext);
            if (dataConnection != null) {
                // Get the apn setting used by the data connection
                ApnSetting dataConnectionApnSetting = dataConnection.getApnSetting();
                if (dataConnectionApnSetting != null) {
                    // Setting is good, so use it.
                    apnSetting = dataConnectionApnSetting;
                }
            }
        }
        if (dataConnection == null) {
            if (isOnlySingleDcAllowed(radioTech)) {
                if (isHigherPriorityApnContextActive(apnContext)) {
                    if (DBG) {
                        log("setupData: Higher priority ApnContext active.  Ignoring call");
                    }
                    return false;
                }

                if (!apnContext.getApnType().equals(PhoneConstants.APN_TYPE_IMS)) {
                    // Only lower priority calls left.  Disconnect them all in this single PDP case
                    // so that we can bring up the requested higher priority call (once we receive
                    // response for deactivate request for the calls we are about to disconnect
                    if (cleanUpAllConnectionsInternal(true, Phone.REASON_SINGLE_PDN_ARBITRATION)) {
                        // If any call actually requested to be disconnected, means we can't
                        // bring up this connection yet as we need to wait for those data calls
                        // to be disconnected.
                        if (DBG) log("setupData: Some calls are disconnecting first."
                                + " Wait and retry");
                        return false;
                    }
                }

                // No other calls are active, so proceed
                if (DBG) log("setupData: Single pdp. Continue setting up data call.");
            }

            dataConnection = findFreeDataConnection();

            if (dataConnection == null) {
                dataConnection = createDataConnection();
            }

            if (dataConnection == null) {
                if (DBG) log("setupData: No free DataConnection and couldn't create one, WEIRD");
                return false;
            }
        }
        final int generation = apnContext.incAndGetConnectionGeneration();
        if (DBG) {
            log("setupData: dc=" + dataConnection + " apnSetting=" + apnSetting + " gen#="
                    + generation);
        }

        apnContext.setDataConnection(dataConnection);
        apnContext.setApnSetting(apnSetting);
        apnContext.setState(DctConstants.State.CONNECTING);
        mPhone.notifyDataConnection(apnContext.getApnType());

        Message msg = obtainMessage();
        msg.what = DctConstants.EVENT_DATA_SETUP_COMPLETE;
        msg.obj = new Pair<ApnContext, Integer>(apnContext, generation);
        dataConnection.bringUp(apnContext, profileId, radioTech, msg, generation, requestType,
                mPhone.getSubId());

        if (DBG) log("setupData: initing!");
        return true;
    }

    private void setInitialAttachApn() {
        ApnSetting iaApnSetting = null;
        ApnSetting defaultApnSetting = null;
        ApnSetting firstNonEmergencyApnSetting = null;

        log("setInitialApn: E mPreferredApn=" + mPreferredApn);

        if (mPreferredApn != null && mPreferredApn.canHandleType(ApnSetting.TYPE_IA)) {
              iaApnSetting = mPreferredApn;
        } else if (!mAllApnSettings.isEmpty()) {
            // Search for Initial APN setting and the first apn that can handle default
            for (ApnSetting apn : mAllApnSettings) {
                if (firstNonEmergencyApnSetting == null
                        && !apn.canHandleType(ApnSetting.TYPE_EMERGENCY)) {
                    firstNonEmergencyApnSetting = apn;
                    log("setInitialApn: firstNonEmergencyApnSetting="
                            + firstNonEmergencyApnSetting);
                }
                if (apn.canHandleType(ApnSetting.TYPE_IA)) {
                    // The Initial Attach APN is highest priority so use it if there is one
                    log("setInitialApn: iaApnSetting=" + apn);
                    iaApnSetting = apn;
                    break;
                } else if ((defaultApnSetting == null)
                        && (apn.canHandleType(ApnSetting.TYPE_DEFAULT))) {
                    // Use the first default apn if no better choice
                    log("setInitialApn: defaultApnSetting=" + apn);
                    defaultApnSetting = apn;
                }
            }
        }

        // The priority of apn candidates from highest to lowest is:
        //   1) APN_TYPE_IA (Initial Attach)
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
        } else if (firstNonEmergencyApnSetting != null) {
            if (DBG) log("setInitialAttachApn: using firstNonEmergencyApnSetting");
            initialAttachApnSetting = firstNonEmergencyApnSetting;
        }

        if (initialAttachApnSetting == null) {
            if (DBG) log("setInitialAttachApn: X There in no available apn");
        } else {
            if (DBG) log("setInitialAttachApn: X selected Apn=" + initialAttachApnSetting);

            mDataServiceManager.setInitialAttachApn(createDataProfile(initialAttachApnSetting,
                            initialAttachApnSetting.equals(getPreferredApn())),
                    mPhone.getServiceState().getDataRoamingFromRegistration(), null);
        }
    }

    /**
     * Handles changes to the APN database.
     */
    private void onApnChanged() {
        DctConstants.State overallState = getOverallState();
        boolean isDisconnected = (overallState == DctConstants.State.IDLE ||
                overallState == DctConstants.State.FAILED);

        if (mPhone instanceof GsmCdmaPhone) {
            // The "current" may no longer be valid.  MMS depends on this to send properly. TBD
            ((GsmCdmaPhone)mPhone).updateCurrentCarrierInProvider();
        }

        // TODO: It'd be nice to only do this if the changed entrie(s)
        // match the current operator.
        if (DBG) log("onApnChanged: createAllApnList and cleanUpAllConnections");
        createAllApnList();
        setDataProfilesAsNeeded();
        setInitialAttachApn();
        cleanUpConnectionsOnUpdatedApns(!isDisconnected, Phone.REASON_APN_CHANGED);

        // FIXME: See bug 17426028 maybe no conditional is needed.
        if (mPhone.getSubId() == SubscriptionManager.getDefaultDataSubscriptionId()) {
            setupDataOnAllConnectableApns(Phone.REASON_APN_CHANGED, RetryFailures.ALWAYS);
        }
    }

    /**
     * "Active" here means ApnContext isEnabled() and not in FAILED state
     * @param apnContext to compare with
     * @return true if higher priority active apn found
     */
    private boolean isHigherPriorityApnContextActive(ApnContext apnContext) {
        if (apnContext.getApnType().equals(PhoneConstants.APN_TYPE_IMS)) {
            return false;
        }

        for (ApnContext otherContext : mPrioritySortedApnContexts) {
            if (otherContext.getApnType().equals(PhoneConstants.APN_TYPE_IMS)) {
                continue;
            }
            if (apnContext.getApnType().equalsIgnoreCase(otherContext.getApnType())) return false;
            if (otherContext.isEnabled() && otherContext.getState() != DctConstants.State.FAILED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports if we support multiple connections or not.
     * This is a combination of factors, based on carrier and RAT.
     * @param rilRadioTech the RIL Radio Tech currently in use
     * @return true if only single DataConnection is allowed
     */
    private boolean isOnlySingleDcAllowed(int rilRadioTech) {
        // Default single dc rats with no knowledge of carrier
        int[] singleDcRats = null;
        // get the carrier specific value, if it exists, from CarrierConfigManager.
        // generally configManager and bundle should not be null, but if they are it should be okay
        // to leave singleDcRats null as well
        CarrierConfigManager configManager = (CarrierConfigManager)
                mPhone.getContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager != null) {
            PersistableBundle bundle = configManager.getConfigForSubId(mPhone.getSubId());
            if (bundle != null) {
                singleDcRats = bundle.getIntArray(
                        CarrierConfigManager.KEY_ONLY_SINGLE_DC_ALLOWED_INT_ARRAY);
            }
        }
        boolean onlySingleDcAllowed = false;
        if (Build.IS_DEBUGGABLE &&
                SystemProperties.getBoolean("persist.telephony.test.singleDc", false)) {
            onlySingleDcAllowed = true;
        }
        if (singleDcRats != null) {
            for (int i=0; i < singleDcRats.length && onlySingleDcAllowed == false; i++) {
                if (rilRadioTech == singleDcRats[i]) onlySingleDcAllowed = true;
            }
        }

        if (DBG) log("isOnlySingleDcAllowed(" + rilRadioTech + "): " + onlySingleDcAllowed);
        return onlySingleDcAllowed;
    }

    void sendRestartRadio() {
        if (DBG)log("sendRestartRadio:");
        Message msg = obtainMessage(DctConstants.EVENT_RESTART_RADIO);
        sendMessage(msg);
    }

    private void restartRadio() {
        if (DBG) log("restartRadio: ************TURN OFF RADIO**************");
        cleanUpAllConnectionsInternal(true, Phone.REASON_RADIO_TURNED_OFF);
        mPhone.getServiceStateTracker().powerOffRadioSafely();
        /* Note: no need to call setRadioPower(true).  Assuming the desired
         * radio power state is still ON (as tracked by ServiceStateTracker),
         * ServiceStateTracker will call setRadioPower when it receives the
         * RADIO_STATE_CHANGED notification for the power off.  And if the
         * desired power state has changed in the interim, we don't want to
         * override it with an unconditional power on.
         */

        int reset = Integer.parseInt(SystemProperties.get("net.ppp.reset-by-timeout", "0"));
        try {
            SystemProperties.set("net.ppp.reset-by-timeout", String.valueOf(reset + 1));
        } catch (RuntimeException ex) {
            log("Failed to set net.ppp.reset-by-timeout");
        }
    }

    /**
     * Return true if data connection need to be setup after disconnected due to
     * reason.
     *
     * @param apnContext APN context
     * @return true if try setup data connection is need for this reason
     */
    private boolean retryAfterDisconnected(ApnContext apnContext) {
        boolean retry = true;
        String reason = apnContext.getReason();

        if (Phone.REASON_RADIO_TURNED_OFF.equals(reason) || (isOnlySingleDcAllowed(getDataRat())
                && isHigherPriorityApnContextActive(apnContext))) {
            retry = false;
        }
        return retry;
    }

    private void startAlarmForReconnect(long delay, ApnContext apnContext) {
        String apnType = apnContext.getApnType();

        Intent intent = new Intent(INTENT_RECONNECT_ALARM + "." + apnType);
        intent.putExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON, apnContext.getReason());
        intent.putExtra(INTENT_RECONNECT_ALARM_EXTRA_TYPE, apnType);
        intent.putExtra(INTENT_RECONNECT_ALARM_EXTRA_TRANSPORT_TYPE, mTransportType);
        SubscriptionManager.putPhoneIdAndSubIdExtra(intent, mPhone.getPhoneId());
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

        if (DBG) {
            log("startAlarmForReconnect: delay=" + delay + " action=" + intent.getAction()
                    + " apn=" + apnContext);
        }

        PendingIntent alarmIntent = PendingIntent.getBroadcast(mPhone.getContext(), 0,
                                        intent, PendingIntent.FLAG_UPDATE_CURRENT);
        apnContext.setReconnectIntent(alarmIntent);

        // Use the exact timer instead of the inexact one to provide better user experience.
        // In some extreme cases, we saw the retry was delayed for few minutes.
        // Note that if the stated trigger time is in the past, the alarm will be triggered
        // immediately.
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delay, alarmIntent);
    }

    private void notifyNoData(@DataFailCause.FailCause int lastFailCauseCode,
                              ApnContext apnContext) {
        if (DBG) log( "notifyNoData: type=" + apnContext.getApnType());
        if (isPermanentFailure(lastFailCauseCode)
            && (!apnContext.getApnType().equals(PhoneConstants.APN_TYPE_DEFAULT))) {
            mPhone.notifyDataConnectionFailed(apnContext.getApnType());
        }
    }

    private void onRecordsLoadedOrSubIdChanged() {
        if (DBG) log("onRecordsLoadedOrSubIdChanged: createAllApnList");
        if (mTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
            // Auto attach is for cellular only.
            mAutoAttachOnCreationConfig = mPhone.getContext().getResources()
                    .getBoolean(com.android.internal.R.bool.config_auto_attach_data_on_creation);
        }

        createAllApnList();
        setDataProfilesAsNeeded();
        setInitialAttachApn();
        mPhone.notifyDataConnection();
        setupDataOnAllConnectableApns(Phone.REASON_SIM_LOADED, RetryFailures.ALWAYS);
    }

    private void onSimNotReady() {
        if (DBG) log("onSimNotReady");

        cleanUpAllConnectionsInternal(true, Phone.REASON_SIM_NOT_READY);
        mAllApnSettings.clear();
        mAutoAttachOnCreationConfig = false;
        // Clear auto attach as modem is expected to do a new attach once SIM is ready
        mAutoAttachEnabled.set(false);
        mOnSubscriptionsChangedListener.mPreviousSubId.set(
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        // In no-sim case, we should still send the emergency APN to the modem, if there is any.
        createAllApnList();
        setDataProfilesAsNeeded();
    }

    private DataConnection checkForCompatibleConnectedApnContext(ApnContext apnContext) {
        int apnType = apnContext.getApnTypeBitmask();
        ArrayList<ApnSetting> dunSettings = null;

        if (ApnSetting.TYPE_DUN == apnType) {
            dunSettings = sortApnListByPreferred(fetchDunApns());
        }
        if (DBG) {
            log("checkForCompatibleConnectedApnContext: apnContext=" + apnContext );
        }

        DataConnection potentialDc = null;
        ApnContext potentialApnCtx = null;
        for (ApnContext curApnCtx : mApnContexts.values()) {
            DataConnection curDc = curApnCtx.getDataConnection();
            if (curDc != null) {
                ApnSetting apnSetting = curApnCtx.getApnSetting();
                log("apnSetting: " + apnSetting);
                if (dunSettings != null && dunSettings.size() > 0) {
                    for (ApnSetting dunSetting : dunSettings) {
                        if (dunSetting.equals(apnSetting)) {
                            switch (curApnCtx.getState()) {
                                case CONNECTED:
                                    if (DBG) {
                                        log("checkForCompatibleConnectedApnContext:"
                                                + " found dun conn=" + curDc
                                                + " curApnCtx=" + curApnCtx);
                                    }
                                    return curDc;
                                case CONNECTING:
                                    potentialDc = curDc;
                                    potentialApnCtx = curApnCtx;
                                    break;
                                default:
                                    // Not connected, potential unchanged
                                    break;
                            }
                        }
                    }
                } else if (apnSetting != null && apnSetting.canHandleType(apnType)) {
                    switch (curApnCtx.getState()) {
                        case CONNECTED:
                            if (DBG) {
                                log("checkForCompatibleConnectedApnContext:"
                                        + " found canHandle conn=" + curDc
                                        + " curApnCtx=" + curApnCtx);
                            }
                            return curDc;
                        case CONNECTING:
                            potentialDc = curDc;
                            potentialApnCtx = curApnCtx;
                            break;
                        default:
                            // Not connected, potential unchanged
                            break;
                    }
                }
            } else {
                if (VDBG) {
                    log("checkForCompatibleConnectedApnContext: not conn curApnCtx=" + curApnCtx);
                }
            }
        }
        if (potentialDc != null) {
            if (DBG) {
                log("checkForCompatibleConnectedApnContext: found potential conn=" + potentialDc
                        + " curApnCtx=" + potentialApnCtx);
            }
            return potentialDc;
        }

        if (DBG) log("checkForCompatibleConnectedApnContext: NO conn apnContext=" + apnContext);
        return null;
    }

    private void addRequestNetworkCompleteMsg(Message onCompleteMsg,
                                              @ApnType int apnType) {
        if (onCompleteMsg != null) {
            List<Message> messageList = mRequestNetworkCompletionMsgs.get(apnType);
            if (messageList == null) messageList = new ArrayList<>();
            messageList.add(onCompleteMsg);
            mRequestNetworkCompletionMsgs.put(apnType, messageList);
        }
    }

    private void sendRequestNetworkCompleteMsg(Message message, boolean success,
                                               @TransportType int transport,
                                               @RequestNetworkType int requestType) {
        if (message == null) return;

        Bundle b = message.getData();
        b.putBoolean(DATA_COMPLETE_MSG_EXTRA_SUCCESS, success);
        b.putInt(DATA_COMPLETE_MSG_EXTRA_REQUEST_TYPE, requestType);
        b.putInt(DATA_COMPLETE_MSG_EXTRA_TRANSPORT_TYPE, transport);
        message.sendToTarget();
    }

    public void enableApn(@ApnType int apnType, @RequestNetworkType int requestType,
                          Message onCompleteMsg) {
        sendMessage(obtainMessage(DctConstants.EVENT_ENABLE_APN, apnType, requestType,
                onCompleteMsg));
    }

    private void onEnableApn(@ApnType int apnType, @RequestNetworkType int requestType,
                             Message onCompleteMsg) {
        ApnContext apnContext = mApnContextsByType.get(apnType);
        if (apnContext == null) {
            loge("onEnableApn(" + apnType + "): NO ApnContext");
            sendRequestNetworkCompleteMsg(onCompleteMsg, false, mTransportType, requestType);
            return;
        }

        String str = "onEnableApn: apnType=" + ApnSetting.getApnTypeString(apnType)
                + ", request type=" + requestTypeToString(requestType);
        if (DBG) log(str);
        apnContext.requestLog(str);

        if (!apnContext.isDependencyMet()) {
            apnContext.setReason(Phone.REASON_DATA_DEPENDENCY_UNMET);
            apnContext.setEnabled(true);
            str = "onEnableApn: dependency is not met.";
            if (DBG) log(str);
            apnContext.requestLog(str);
            sendRequestNetworkCompleteMsg(onCompleteMsg, false, mTransportType, requestType);
            return;
        }

        if (apnContext.isReady()) {
            DctConstants.State state = apnContext.getState();
            switch(state) {
                case CONNECTING:
                    if (DBG) log("onEnableApn: 'CONNECTING' so return");
                    apnContext.requestLog("onEnableApn state=CONNECTING, so return");
                    addRequestNetworkCompleteMsg(onCompleteMsg, apnType);
                    return;
                case CONNECTED:
                    if (DBG) log("onEnableApn: 'CONNECTED' so return");
                    apnContext.requestLog("onEnableApn state=CONNECTED, so return");

                    sendRequestNetworkCompleteMsg(onCompleteMsg, true, mTransportType,
                            requestType);
                    return;
                case DISCONNECTING:
                    if (DBG) log("onEnableApn: 'DISCONNECTING' so return");
                    apnContext.requestLog("onEnableApn state=DISCONNECTING, so return");
                    sendRequestNetworkCompleteMsg(onCompleteMsg, false, mTransportType,
                            requestType);
                    return;
                case IDLE:
                    // fall through: this is unexpected but if it happens cleanup and try setup
                case FAILED:
                case RETRYING:
                    // We're "READY" but not active so disconnect (cleanup = true) and
                    // connect (trySetup = true) to be sure we retry the connection.
                    apnContext.setReason(Phone.REASON_DATA_ENABLED);
                    break;
            }
        } else {
            if (apnContext.isEnabled()) {
                apnContext.setReason(Phone.REASON_DATA_DEPENDENCY_MET);
            } else {
                apnContext.setReason(Phone.REASON_DATA_ENABLED);
            }
            if (apnContext.getState() == DctConstants.State.FAILED) {
                apnContext.setState(DctConstants.State.IDLE);
            }
        }
        apnContext.setEnabled(true);
        apnContext.resetErrorCodeRetries();
        if (trySetupData(apnContext, requestType)) {
            addRequestNetworkCompleteMsg(onCompleteMsg, apnType);
        } else {
            sendRequestNetworkCompleteMsg(onCompleteMsg, false, mTransportType,
                    requestType);
        }
    }

    public void disableApn(@ApnType int apnType, @ReleaseNetworkType int releaseType) {
        sendMessage(obtainMessage(DctConstants.EVENT_DISABLE_APN, apnType, releaseType));
    }

    private void onDisableApn(@ApnType int apnType,
                              @ReleaseNetworkType int releaseType) {
        ApnContext apnContext = mApnContextsByType.get(apnType);
        if (apnContext == null) {
            loge("disableApn(" + apnType + "): NO ApnContext");
            return;
        }

        boolean cleanup = false;
        String str = "onDisableApn: apnType=" + ApnSetting.getApnTypeString(apnType)
                + ", release type=" + releaseTypeToString(releaseType);
        if (DBG) log(str);
        apnContext.requestLog(str);

        if (apnContext.isReady()) {
            cleanup = (releaseType == RELEASE_TYPE_DETACH
                    || releaseType == RELEASE_TYPE_HANDOVER);
            if (apnContext.isDependencyMet()) {
                apnContext.setReason(Phone.REASON_DATA_DISABLED_INTERNAL);
                // If ConnectivityService has disabled this network, stop trying to bring
                // it up, but do not tear it down - ConnectivityService will do that
                // directly by talking with the DataConnection.
                //
                // This doesn't apply to DUN. When the user disable tethering, we would like to
                // detach the APN context from the data connection so the data connection can be
                // torn down if no other APN context attached to it.
                if (PhoneConstants.APN_TYPE_DUN.equals(apnContext.getApnType())
                        || apnContext.getState() != DctConstants.State.CONNECTED) {
                    str = "Clean up the connection. Apn type = " + apnContext.getApnType()
                            + ", state = " + apnContext.getState();
                    if (DBG) log(str);
                    apnContext.requestLog(str);
                    cleanup = true;
                }
            } else {
                apnContext.setReason(Phone.REASON_DATA_DEPENDENCY_UNMET);
            }
        }

        apnContext.setEnabled(false);
        if (cleanup) {
            cleanUpConnectionInternal(true, releaseType, apnContext);
        }

        if (isOnlySingleDcAllowed(getDataRat()) && !isHigherPriorityApnContextActive(apnContext)) {
            if (DBG) log("disableApn:isOnlySingleDcAllowed true & higher priority APN disabled");
            // If the highest priority APN is disabled and only single
            // data call is allowed, try to setup data call on other connectable APN.
            setupDataOnAllConnectableApns(Phone.REASON_SINGLE_PDN_ARBITRATION,
                    RetryFailures.ALWAYS);
        }
    }

    /**
     * Modify {@link android.provider.Settings.Global#DATA_ROAMING} value for user modification only
     */
    public void setDataRoamingEnabledByUser(boolean enabled) {
        mDataEnabledSettings.setDataRoamingEnabled(enabled);
        setDataRoamingFromUserAction(true);
        if (DBG) {
            log("setDataRoamingEnabledByUser: set phoneSubId=" + mPhone.getSubId()
                    + " isRoaming=" + enabled);
        }
    }

    /**
     * Return current {@link android.provider.Settings.Global#DATA_ROAMING} value.
     */
    public boolean getDataRoamingEnabled() {
        boolean isDataRoamingEnabled = mDataEnabledSettings.getDataRoamingEnabled();

        if (VDBG) {
            log("getDataRoamingEnabled: phoneSubId=" + mPhone.getSubId()
                    + " isDataRoamingEnabled=" + isDataRoamingEnabled);
        }
        return isDataRoamingEnabled;
    }

    /**
     * Set default value for {@link android.provider.Settings.Global#DATA_ROAMING}
     * if the setting is not from user actions. default value is based on carrier config and system
     * properties.
     */
    private void setDefaultDataRoamingEnabled() {
        // For single SIM phones, this is a per phone property.
        String setting = Settings.Global.DATA_ROAMING;
        boolean useCarrierSpecificDefault = false;
        if (mTelephonyManager.getSimCount() != 1) {
            setting = setting + mPhone.getSubId();
            try {
                Settings.Global.getInt(mResolver, setting);
            } catch (SettingNotFoundException ex) {
                // For msim, update to carrier default if uninitialized.
                useCarrierSpecificDefault = true;
            }
        } else if (!isDataRoamingFromUserAction()) {
            // for single sim device, update to carrier default if user action is not set
            useCarrierSpecificDefault = true;
        }
        log("setDefaultDataRoamingEnabled: useCarrierSpecificDefault "
                + useCarrierSpecificDefault);
        if (useCarrierSpecificDefault) {
            boolean defaultVal = mDataEnabledSettings.getDefaultDataRoamingEnabled();
            mDataEnabledSettings.setDataRoamingEnabled(defaultVal);
        }
    }

    private boolean isDataRoamingFromUserAction() {
        final SharedPreferences sp = PreferenceManager
                .getDefaultSharedPreferences(mPhone.getContext());
        // since we don't want to unset user preference from system update, pass true as the default
        // value if shared pref does not exist and set shared pref to false explicitly from factory
        // reset.
        if (!sp.contains(Phone.DATA_ROAMING_IS_USER_SETTING_KEY)
                && Settings.Global.getInt(mResolver, Settings.Global.DEVICE_PROVISIONED, 0) == 0) {
            sp.edit().putBoolean(Phone.DATA_ROAMING_IS_USER_SETTING_KEY, false).commit();
        }
        return sp.getBoolean(Phone.DATA_ROAMING_IS_USER_SETTING_KEY, true);
    }

    private void setDataRoamingFromUserAction(boolean isUserAction) {
        final SharedPreferences.Editor sp = PreferenceManager
                .getDefaultSharedPreferences(mPhone.getContext()).edit();
        sp.putBoolean(Phone.DATA_ROAMING_IS_USER_SETTING_KEY, isUserAction).commit();
    }

    // When the data roaming status changes from roaming to non-roaming.
    private void onDataRoamingOff() {
        if (DBG) log("onDataRoamingOff");

        reevaluateDataConnections();

        if (!getDataRoamingEnabled()) {
            // TODO: Remove this once all old vendor RILs are gone. We don't need to set initial apn
            // attach and send the data profile again as the modem should have both roaming and
            // non-roaming protocol in place. Modem should choose the right protocol based on the
            // roaming condition.
            setDataProfilesAsNeeded();
            setInitialAttachApn();

            // If the user did not enable data roaming, now when we transit from roaming to
            // non-roaming, we should try to reestablish the data connection.

            setupDataOnAllConnectableApns(Phone.REASON_ROAMING_OFF, RetryFailures.ALWAYS);
        } else {
            mPhone.notifyDataConnection();
        }
    }

    // This method is called
    // 1. When the data roaming status changes from non-roaming to roaming.
    // 2. When allowed data roaming settings is changed by the user.
    private void onDataRoamingOnOrSettingsChanged(int messageType) {
        if (DBG) log("onDataRoamingOnOrSettingsChanged");
        // Used to differentiate data roaming turned on vs settings changed.
        boolean settingChanged = (messageType == DctConstants.EVENT_ROAMING_SETTING_CHANGE);

        // Check if the device is actually data roaming
        if (!mPhone.getServiceState().getDataRoaming()) {
            if (DBG) log("device is not roaming. ignored the request.");
            return;
        }

        checkDataRoamingStatus(settingChanged);

        if (getDataRoamingEnabled()) {
            // If the restricted data was brought up when data roaming is disabled, and now users
            // enable data roaming, we need to re-evaluate the conditions and possibly change the
            // network's capability.
            if (settingChanged) {
                reevaluateDataConnections();
            }

            if (DBG) log("onDataRoamingOnOrSettingsChanged: setup data on roaming");

            setupDataOnAllConnectableApns(Phone.REASON_ROAMING_ON, RetryFailures.ALWAYS);
            mPhone.notifyDataConnection();
        } else {
            // If the user does not turn on data roaming, when we transit from non-roaming to
            // roaming, we need to tear down the data connection otherwise the user might be
            // charged for data roaming usage.
            if (DBG) log("onDataRoamingOnOrSettingsChanged: Tear down data connection on roaming.");
            cleanUpAllConnectionsInternal(true, Phone.REASON_ROAMING_ON);
        }
    }

    // We want to track possible roaming data leakage. Which is, if roaming setting
    // is disabled, yet we still setup a roaming data connection or have a connected ApnContext
    // switched to roaming. When this happens, we log it in a local log.
    private void checkDataRoamingStatus(boolean settingChanged) {
        if (!settingChanged && !getDataRoamingEnabled()
                && mPhone.getServiceState().getDataRoaming()) {
            for (ApnContext apnContext : mApnContexts.values()) {
                if (apnContext.getState() == DctConstants.State.CONNECTED) {
                    mDataRoamingLeakageLog.log("PossibleRoamingLeakage "
                            + " connection params: " + (apnContext.getDataConnection() != null
                            ? apnContext.getDataConnection().getConnectionParams() : ""));
                }
            }
        }
    }

    private void onRadioAvailable() {
        if (DBG) log("onRadioAvailable");
        if (mPhone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            // setState(DctConstants.State.CONNECTED);
            mPhone.notifyDataConnection();

            log("onRadioAvailable: We're on the simulator; assuming data is connected");
        }

        if (getOverallState() != DctConstants.State.IDLE) {
            cleanUpConnectionInternal(true, RELEASE_TYPE_DETACH, null);
        }
    }

    private void onRadioOffOrNotAvailable() {
        // Make sure our reconnect delay starts at the initial value
        // next time the radio comes on

        mReregisterOnReconnectFailure = false;

        // Clear auto attach as modem is expected to do a new attach
        mAutoAttachEnabled.set(false);

        if (mPhone.getSimulatedRadioControl() != null) {
            // Assume data is connected on the simulator
            // FIXME  this can be improved
            log("We're on the simulator; assuming radio off is meaningless");
        } else {
            if (DBG) log("onRadioOffOrNotAvailable: is off and clean up all connections");
            cleanUpAllConnectionsInternal(false, Phone.REASON_RADIO_TURNED_OFF);
        }
    }

    private void completeConnection(ApnContext apnContext, @RequestNetworkType int type) {

        if (DBG) log("completeConnection: successful, notify the world apnContext=" + apnContext);

        if (mIsProvisioning && !TextUtils.isEmpty(mProvisioningUrl)) {
            if (DBG) {
                log("completeConnection: MOBILE_PROVISIONING_ACTION url="
                        + mProvisioningUrl);
            }
            Intent newIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN,
                    Intent.CATEGORY_APP_BROWSER);
            newIntent.setData(Uri.parse(mProvisioningUrl));
            newIntent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT |
                    Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                mPhone.getContext().startActivity(newIntent);
            } catch (ActivityNotFoundException e) {
                loge("completeConnection: startActivityAsUser failed" + e);
            }
        }
        mIsProvisioning = false;
        mProvisioningUrl = null;
        if (mProvisioningSpinner != null) {
            sendMessage(obtainMessage(DctConstants.CMD_CLEAR_PROVISIONING_SPINNER,
                    mProvisioningSpinner));
        }

        // Notify data is connected except for handover case.
        if (type != REQUEST_TYPE_HANDOVER) {
            mPhone.notifyDataConnection(apnContext.getApnType());
        }
        startNetStatPoll();
        startDataStallAlarm(DATA_STALL_NOT_SUSPECTED);
    }

    /**
     * A SETUP (aka bringUp) has completed, possibly with an error. If
     * there is an error this method will call {@link #onDataSetupCompleteError}.
     */
    private void onDataSetupComplete(ApnContext apnContext, boolean success, int cause,
                                     @RequestNetworkType int requestType) {
        int apnType = ApnSetting.getApnTypesBitmaskFromString(apnContext.getApnType());
        List<Message> messageList = mRequestNetworkCompletionMsgs.get(apnType);
        if (messageList != null) {
            for (Message msg : messageList) {
                sendRequestNetworkCompleteMsg(msg, success, mTransportType, requestType);
            }
            messageList.clear();
        }

        if (success) {
            DataConnection dataConnection = apnContext.getDataConnection();

            if (RADIO_TESTS) {
                // Note: To change radio.test.onDSC.null.dcac from command line you need to
                // adb root and adb remount and from the command line you can only change the
                // value to 1 once. To change it a second time you can reboot or execute
                // adb shell stop and then adb shell start. The command line to set the value is:
                // adb shell sqlite3 /data/data/com.android.providers.settings/databases/settings.db "insert into system (name,value) values ('radio.test.onDSC.null.dcac', '1');"
                ContentResolver cr = mPhone.getContext().getContentResolver();
                String radioTestProperty = "radio.test.onDSC.null.dcac";
                if (Settings.System.getInt(cr, radioTestProperty, 0) == 1) {
                    log("onDataSetupComplete: " + radioTestProperty +
                            " is true, set dcac to null and reset property to false");
                    dataConnection = null;
                    Settings.System.putInt(cr, radioTestProperty, 0);
                    log("onDataSetupComplete: " + radioTestProperty + "=" +
                            Settings.System.getInt(mPhone.getContext().getContentResolver(),
                                    radioTestProperty, -1));
                }
            }
            if (dataConnection == null) {
                log("onDataSetupComplete: no connection to DC, handle as error");
                onDataSetupCompleteError(apnContext, requestType);
            } else {
                ApnSetting apn = apnContext.getApnSetting();
                if (DBG) {
                    log("onDataSetupComplete: success apn=" + (apn == null ? "unknown"
                            : apn.getApnName()));
                }
                if (apn != null && !TextUtils.isEmpty(apn.getProxyAddressAsString())) {
                    try {
                        int port = apn.getProxyPort();
                        if (port == -1) {
                            port = 8080;
                        }
                        ProxyInfo proxy = new ProxyInfo(apn.getProxyAddressAsString(), port, null);
                        dataConnection.setLinkPropertiesHttpProxy(proxy);
                    } catch (NumberFormatException e) {
                        loge("onDataSetupComplete: NumberFormatException making ProxyProperties ("
                                + apn.getProxyPort() + "): " + e);
                    }
                }

                // everything is setup
                if (TextUtils.equals(apnContext.getApnType(), PhoneConstants.APN_TYPE_DEFAULT)) {
                    try {
                        SystemProperties.set(PUPPET_MASTER_RADIO_STRESS_TEST, "true");
                    } catch (RuntimeException ex) {
                        log("Failed to set PUPPET_MASTER_RADIO_STRESS_TEST to true");
                    }
                    if (mCanSetPreferApn && mPreferredApn == null) {
                        if (DBG) log("onDataSetupComplete: PREFERRED APN is null");
                        mPreferredApn = apn;
                        if (mPreferredApn != null) {
                            setPreferredApn(mPreferredApn.getId());
                        }
                    }
                } else {
                    try {
                        SystemProperties.set(PUPPET_MASTER_RADIO_STRESS_TEST, "false");
                    } catch (RuntimeException ex) {
                        log("Failed to set PUPPET_MASTER_RADIO_STRESS_TEST to false");
                    }
                }

                // A connection is setup
                apnContext.setState(DctConstants.State.CONNECTED);

                checkDataRoamingStatus(false);

                boolean isProvApn = apnContext.isProvisioningApn();
                final ConnectivityManager cm = ConnectivityManager.from(mPhone.getContext());
                if (mProvisionBroadcastReceiver != null) {
                    mPhone.getContext().unregisterReceiver(mProvisionBroadcastReceiver);
                    mProvisionBroadcastReceiver = null;
                }
                if ((!isProvApn) || mIsProvisioning) {
                    // Hide any provisioning notification.
                    cm.setProvisioningNotificationVisible(false, ConnectivityManager.TYPE_MOBILE,
                            mProvisionActionName);
                    // Complete the connection normally notifying the world we're connected.
                    // We do this if this isn't a special provisioning apn or if we've been
                    // told its time to provision.
                    completeConnection(apnContext, requestType);
                } else {
                    // This is a provisioning APN that we're reporting as connected. Later
                    // when the user desires to upgrade this to a "default" connection,
                    // mIsProvisioning == true, we'll go through the code path above.
                    // mIsProvisioning becomes true when CMD_ENABLE_MOBILE_PROVISIONING
                    // is sent to the DCT.
                    if (DBG) {
                        log("onDataSetupComplete: successful, BUT send connected to prov apn as"
                                + " mIsProvisioning:" + mIsProvisioning + " == false"
                                + " && (isProvisioningApn:" + isProvApn + " == true");
                    }

                    // While radio is up, grab provisioning URL.  The URL contains ICCID which
                    // disappears when radio is off.
                    mProvisionBroadcastReceiver = new ProvisionNotificationBroadcastReceiver(
                            cm.getMobileProvisioningUrl(),
                            mTelephonyManager.getNetworkOperatorName());
                    mPhone.getContext().registerReceiver(mProvisionBroadcastReceiver,
                            new IntentFilter(mProvisionActionName));
                    // Put up user notification that sign-in is required.
                    cm.setProvisioningNotificationVisible(true, ConnectivityManager.TYPE_MOBILE,
                            mProvisionActionName);
                    // Turn off radio to save battery and avoid wasting carrier resources.
                    // The network isn't usable and network validation will just fail anyhow.
                    setRadio(false);
                }
                if (DBG) {
                    log("onDataSetupComplete: SETUP complete type=" + apnContext.getApnType());
                }
                if (Build.IS_DEBUGGABLE) {
                    // adb shell setprop persist.radio.test.pco [pco_val]
                    String radioTestProperty = "persist.radio.test.pco";
                    int pcoVal = SystemProperties.getInt(radioTestProperty, -1);
                    if (pcoVal != -1) {
                        log("PCO testing: read pco value from persist.radio.test.pco " + pcoVal);
                        final byte[] value = new byte[1];
                        value[0] = (byte) pcoVal;
                        final Intent intent =
                                new Intent(TelephonyIntents.ACTION_CARRIER_SIGNAL_PCO_VALUE);
                        intent.putExtra(TelephonyIntents.EXTRA_APN_TYPE_KEY, "default");
                        intent.putExtra(TelephonyIntents.EXTRA_APN_PROTO_KEY, "IPV4V6");
                        intent.putExtra(TelephonyIntents.EXTRA_PCO_ID_KEY, 0xFF00);
                        intent.putExtra(TelephonyIntents.EXTRA_PCO_VALUE_KEY, value);
                        mPhone.getCarrierSignalAgent().notifyCarrierSignalReceivers(intent);
                    }
                }
            }
        } else {
            if (DBG) {
                ApnSetting apn = apnContext.getApnSetting();
                log("onDataSetupComplete: error apn=" + apn.getApnName() + ", cause=" + cause
                        + ", requestType=" + requestTypeToString(requestType));
            }
            if (DataFailCause.isEventLoggable(cause)) {
                // Log this failure to the Event Logs.
                int cid = getCellLocationId();
                EventLog.writeEvent(EventLogTags.PDP_SETUP_FAIL,
                        cause, cid, mTelephonyManager.getNetworkType());
            }
            ApnSetting apn = apnContext.getApnSetting();
            mPhone.notifyPreciseDataConnectionFailed(apnContext.getApnType(),
                    apn != null ? apn.getApnName() : null, cause);

            // Compose broadcast intent send to the specific carrier signaling receivers
            Intent intent = new Intent(TelephonyIntents
                    .ACTION_CARRIER_SIGNAL_REQUEST_NETWORK_FAILED);
            intent.putExtra(TelephonyIntents.EXTRA_ERROR_CODE_KEY, cause);
            intent.putExtra(TelephonyIntents.EXTRA_APN_TYPE_KEY, apnContext.getApnType());
            mPhone.getCarrierSignalAgent().notifyCarrierSignalReceivers(intent);

            if (DataFailCause.isRadioRestartFailure(mPhone.getContext(), cause, mPhone.getSubId())
                    || apnContext.restartOnError(cause)) {
                if (DBG) log("Modem restarted.");
                sendRestartRadio();
            }

            // If the data call failure cause is a permanent failure, we mark the APN as permanent
            // failed.
            if (isPermanentFailure(cause)) {
                log("cause = " + cause + ", mark apn as permanent failed. apn = " + apn);
                apnContext.markApnPermanentFailed(apn);
            }
            onDataSetupCompleteError(apnContext, requestType);
        }
    }

    /**
     * Error has occurred during the SETUP {aka bringUP} request and the DCT
     * should either try the next waiting APN or start over from the
     * beginning if the list is empty. Between each SETUP request there will
     * be a delay defined by {@link #getApnDelay()}.
     */
    private void onDataSetupCompleteError(ApnContext apnContext,
                                          @RequestNetworkType int requestType) {
        long delay = apnContext.getDelayForNextApn(mFailFast);

        // Check if we need to retry or not.
        // TODO: We should support handover retry in the future.
        if (delay >= 0) {
            if (DBG) log("onDataSetupCompleteError: Try next APN. delay = " + delay);
            apnContext.setState(DctConstants.State.RETRYING);
            // Wait a bit before trying the next APN, so that
            // we're not tying up the RIL command channel

            startAlarmForReconnect(delay, apnContext);
        } else {
            // If we are not going to retry any APN, set this APN context to failed state.
            // This would be the final state of a data connection.
            apnContext.setState(DctConstants.State.FAILED);
            mPhone.notifyDataConnection(apnContext.getApnType());
            apnContext.setDataConnection(null);
            log("onDataSetupCompleteError: Stop retrying APNs. delay=" + delay
                    + ", requestType=" + requestTypeToString(requestType));
        }
    }

    /**
     * Called when EVENT_NETWORK_STATUS_CHANGED is received.
     *
     * @param status One of {@code NetworkAgent.VALID_NETWORK} or
     * {@code NetworkAgent.INVALID_NETWORK}.
     * @param redirectUrl If the Internet probe was redirected, this
     * is the destination it was redirected to, otherwise {@code null}
     */
    private void onNetworkStatusChanged(int status, String redirectUrl) {
        if (!TextUtils.isEmpty(redirectUrl)) {
            Intent intent = new Intent(TelephonyIntents.ACTION_CARRIER_SIGNAL_REDIRECTED);
            intent.putExtra(TelephonyIntents.EXTRA_REDIRECTION_URL_KEY, redirectUrl);
            mPhone.getCarrierSignalAgent().notifyCarrierSignalReceivers(intent);
            log("Notify carrier signal receivers with redirectUrl: " + redirectUrl);
        } else {
            final boolean isValid = status == NetworkAgent.VALID_NETWORK;
            if (!mDsRecoveryHandler.isRecoveryOnBadNetworkEnabled()) {
                if (DBG) log("Skip data stall recovery on network status change with in threshold");
                return;
            }
            if (mTransportType != AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
                if (DBG) log("Skip data stall recovery on non WWAN");
                return;
            }
            mDsRecoveryHandler.processNetworkStatusChanged(isValid);
        }
    }

    /**
     * Called when EVENT_DISCONNECT_DONE is received.
     */
    private void onDisconnectDone(ApnContext apnContext) {
        if(DBG) log("onDisconnectDone: EVENT_DISCONNECT_DONE apnContext=" + apnContext);
        apnContext.setState(DctConstants.State.IDLE);
        final DataConnection dc = apnContext.getDataConnection();
        // when data connection is gone and not for handover, notify all apn types which
        // this data connection can handle. Note, this might not work if one apn type served for
        // multiple data connection.
        if (dc != null && dc.isInactive() && !dc.hasBeenTransferred()) {
            String[] types = ApnSetting.getApnTypesStringFromBitmask(
                    apnContext.getApnSetting().getApnTypeBitmask()).split(",");
            for (String type : types) {
                mPhone.notifyDataConnection(type);
            }
        }
        // if all data connection are gone, check whether Airplane mode request was
        // pending.
        if (isDisconnected()) {
            if (mPhone.getServiceStateTracker().processPendingRadioPowerOffAfterDataOff()) {
                if (DBG) log("onDisconnectDone: radio will be turned off, no retries");
                // Radio will be turned off. No need to retry data setup
                apnContext.setApnSetting(null);
                apnContext.setDataConnection(null);

                // Need to notify disconnect as well, in the case of switching Airplane mode.
                // Otherwise, it would cause 30s delayed to turn on Airplane mode.
                if (mDisconnectPendingCount > 0) {
                    mDisconnectPendingCount--;
                }

                if (mDisconnectPendingCount == 0) {
                    notifyAllDataDisconnected();
                }
                return;
            }
        }
        // If APN is still enabled, try to bring it back up automatically
        if (mAttached.get() && apnContext.isReady() && retryAfterDisconnected(apnContext)) {
            try {
                SystemProperties.set(PUPPET_MASTER_RADIO_STRESS_TEST, "false");
            } catch (RuntimeException ex) {
                log("Failed to set PUPPET_MASTER_RADIO_STRESS_TEST to false");
            }
            // Wait a bit before trying the next APN, so that
            // we're not tying up the RIL command channel.
            // This also helps in any external dependency to turn off the context.
            if (DBG) log("onDisconnectDone: attached, ready and retry after disconnect");
            long delay = apnContext.getRetryAfterDisconnectDelay();
            if (delay > 0) {
                // Data connection is in IDLE state, so when we reconnect later, we'll rebuild
                // the waiting APN list, which will also reset/reconfigure the retry manager.
                startAlarmForReconnect(delay, apnContext);
            }
        } else {
            boolean restartRadioAfterProvisioning = mPhone.getContext().getResources().getBoolean(
                    com.android.internal.R.bool.config_restartRadioAfterProvisioning);

            if (apnContext.isProvisioningApn() && restartRadioAfterProvisioning) {
                log("onDisconnectDone: restartRadio after provisioning");
                restartRadio();
            }
            apnContext.setApnSetting(null);
            apnContext.setDataConnection(null);
            if (isOnlySingleDcAllowed(getDataRat())) {
                if(DBG) log("onDisconnectDone: isOnlySigneDcAllowed true so setup single apn");
                setupDataOnAllConnectableApns(Phone.REASON_SINGLE_PDN_ARBITRATION,
                        RetryFailures.ALWAYS);
            } else {
                if(DBG) log("onDisconnectDone: not retrying");
            }
        }

        if (mDisconnectPendingCount > 0)
            mDisconnectPendingCount--;

        if (mDisconnectPendingCount == 0) {
            apnContext.setConcurrentVoiceAndDataAllowed(
                    mPhone.getServiceStateTracker().isConcurrentVoiceAndDataAllowed());
            notifyAllDataDisconnected();
        }

    }

    private void onVoiceCallStarted() {
        if (DBG) log("onVoiceCallStarted");
        mInVoiceCall = true;
        if (isConnected() && ! mPhone.getServiceStateTracker().isConcurrentVoiceAndDataAllowed()) {
            if (DBG) log("onVoiceCallStarted stop polling");
            stopNetStatPoll();
            stopDataStallAlarm();
            mPhone.notifyDataConnection();
        }
    }

    private void onVoiceCallEnded() {
        if (DBG) log("onVoiceCallEnded");
        mInVoiceCall = false;
        if (isConnected()) {
            if (!mPhone.getServiceStateTracker().isConcurrentVoiceAndDataAllowed()) {
                startNetStatPoll();
                startDataStallAlarm(DATA_STALL_NOT_SUSPECTED);
                mPhone.notifyDataConnection();
            } else {
                // clean slate after call end.
                resetPollStats();
            }
        }
        // reset reconnect timer
        setupDataOnAllConnectableApns(Phone.REASON_VOICE_CALL_ENDED, RetryFailures.ALWAYS);
    }

    private boolean isConnected() {
        for (ApnContext apnContext : mApnContexts.values()) {
            if (apnContext.getState() == DctConstants.State.CONNECTED) {
                // At least one context is connected, return true
                return true;
            }
        }
        // There are not any contexts connected, return false
        return false;
    }

    public boolean isDisconnected() {
        for (ApnContext apnContext : mApnContexts.values()) {
            if (!apnContext.isDisconnected()) {
                // At least one context was not disconnected return false
                return false;
            }
        }
        // All contexts were disconnected so return true
        return true;
    }

    private void setDataProfilesAsNeeded() {
        if (DBG) log("setDataProfilesAsNeeded");

        ArrayList<DataProfile> dataProfileList = new ArrayList<>();

        for (ApnSetting apn : mAllApnSettings) {
            DataProfile dp = createDataProfile(apn, apn.equals(getPreferredApn()));
            if (!dataProfileList.contains(dp)) {
                dataProfileList.add(dp);
            }
        }

        // Check if the data profiles we are sending are same as we did last time. We don't want to
        // send the redundant profiles to the modem. Also if there the list is empty, we don't
        // send it to the modem.
        if (!dataProfileList.isEmpty()
                && (dataProfileList.size() != mLastDataProfileList.size()
                || !mLastDataProfileList.containsAll(dataProfileList))) {
            mDataServiceManager.setDataProfile(dataProfileList,
                    mPhone.getServiceState().getDataRoamingFromRegistration(), null);
        }
    }

    /**
     * Based on the sim operator numeric, create a list for all possible
     * Data Connections and setup the preferredApn.
     */
    private void createAllApnList() {
        mAllApnSettings.clear();
        IccRecords r = mIccRecords.get();
        String operator = (r != null) ? r.getOperatorNumeric() : "";

        // ORDER BY Telephony.Carriers._ID ("_id")
        Cursor cursor = mPhone.getContext().getContentResolver().query(
                Uri.withAppendedPath(Telephony.Carriers.SIM_APN_URI, "filtered/subId/"
                        + mPhone.getSubId()), null, null, null, Telephony.Carriers._ID);

        if (cursor != null) {
            while (cursor.moveToNext()) {
                ApnSetting apn = ApnSetting.makeApnSetting(cursor);
                if (apn == null) {
                    continue;
                }
                mAllApnSettings.add(apn);
            }
            cursor.close();
        } else {
            if (DBG) log("createAllApnList: cursor is null");
            mApnSettingsInitializationLog.log("cursor is null for carrier, operator: "
                    + operator);
        }

        addEmergencyApnSetting();

        dedupeApnSettings();

        if (mAllApnSettings.isEmpty()) {
            log("createAllApnList: No APN found for carrier, operator: " + operator);
            mApnSettingsInitializationLog.log("no APN found for carrier, operator: "
                    + operator);
            mPreferredApn = null;
            // TODO: What is the right behavior?
            //notifyNoData(DataConnection.FailCause.MISSING_UNKNOWN_APN);
        } else {
            mPreferredApn = getPreferredApn();
            if (mPreferredApn != null && !mPreferredApn.getOperatorNumeric().equals(operator)) {
                mPreferredApn = null;
                setPreferredApn(-1);
            }
            if (DBG) log("createAllApnList: mPreferredApn=" + mPreferredApn);
        }
        if (DBG) log("createAllApnList: X mAllApnSettings=" + mAllApnSettings);
    }

    private void dedupeApnSettings() {
        ArrayList<ApnSetting> resultApns = new ArrayList<ApnSetting>();

        // coalesce APNs if they are similar enough to prevent
        // us from bringing up two data calls with the same interface
        int i = 0;
        while (i < mAllApnSettings.size() - 1) {
            ApnSetting first = mAllApnSettings.get(i);
            ApnSetting second = null;
            int j = i + 1;
            while (j < mAllApnSettings.size()) {
                second = mAllApnSettings.get(j);
                if (first.similar(second)) {
                    ApnSetting newApn = mergeApns(first, second);
                    mAllApnSettings.set(i, newApn);
                    first = newApn;
                    mAllApnSettings.remove(j);
                } else {
                    j++;
                }
            }
            i++;
        }
    }

    private ApnSetting mergeApns(ApnSetting dest, ApnSetting src) {
        int id = dest.getId();
        if ((src.getApnTypeBitmask() & ApnSetting.TYPE_DEFAULT) == ApnSetting.TYPE_DEFAULT) {
            id = src.getId();
        }
        final int resultApnType = src.getApnTypeBitmask() | dest.getApnTypeBitmask();
        Uri mmsc = (dest.getMmsc() == null ? src.getMmsc() : dest.getMmsc());
        String mmsProxy = TextUtils.isEmpty(dest.getMmsProxyAddressAsString())
                ? src.getMmsProxyAddressAsString() : dest.getMmsProxyAddressAsString();
        int mmsPort = dest.getMmsProxyPort() == -1 ? src.getMmsProxyPort() : dest.getMmsProxyPort();
        String proxy = TextUtils.isEmpty(dest.getProxyAddressAsString())
                ? src.getProxyAddressAsString() : dest.getProxyAddressAsString();
        int port = dest.getProxyPort() == -1 ? src.getProxyPort() : dest.getProxyPort();
        int protocol = src.getProtocol() == ApnSetting.PROTOCOL_IPV4V6 ? src.getProtocol()
                : dest.getProtocol();
        int roamingProtocol = src.getRoamingProtocol() == ApnSetting.PROTOCOL_IPV4V6
                ? src.getRoamingProtocol() : dest.getRoamingProtocol();
        int networkTypeBitmask = (dest.getNetworkTypeBitmask() == 0
                || src.getNetworkTypeBitmask() == 0)
                ? 0 : (dest.getNetworkTypeBitmask() | src.getNetworkTypeBitmask());

        return ApnSetting.makeApnSetting(id, dest.getOperatorNumeric(), dest.getEntryName(),
            dest.getApnName(), proxy, port, mmsc, mmsProxy, mmsPort, dest.getUser(),
            dest.getPassword(), dest.getAuthType(), resultApnType, protocol, roamingProtocol,
            dest.isEnabled(), networkTypeBitmask, dest.getProfileId(),
            (dest.isPersistent() || src.isPersistent()), dest.getMaxConns(),
            dest.getWaitTime(), dest.getMaxConnsTime(), dest.getMtu(), dest.getMvnoType(),
            dest.getMvnoMatchData(), dest.getApnSetId(), dest.getCarrierId(),
            dest.getSkip464Xlat());
    }

    private DataConnection createDataConnection() {
        if (DBG) log("createDataConnection E");

        int id = mUniqueIdGenerator.getAndIncrement();
        DataConnection dataConnection = DataConnection.makeDataConnection(mPhone, id, this,
                mDataServiceManager, mDcTesterFailBringUpAll, mDcc);
        mDataConnections.put(id, dataConnection);
        if (DBG) log("createDataConnection() X id=" + id + " dc=" + dataConnection);
        return dataConnection;
    }

    private void destroyDataConnections() {
        if(mDataConnections != null) {
            if (DBG) log("destroyDataConnections: clear mDataConnectionList");
            mDataConnections.clear();
        } else {
            if (DBG) log("destroyDataConnections: mDataConnecitonList is empty, ignore");
        }
    }

    /**
     * Build a list of APNs to be used to create PDP's.
     *
     * @param requestedApnType
     * @return waitingApns list to be used to create PDP
     *          error when waitingApns.isEmpty()
     */
    private ArrayList<ApnSetting> buildWaitingApns(String requestedApnType, int radioTech) {
        if (DBG) log("buildWaitingApns: E requestedApnType=" + requestedApnType);
        ArrayList<ApnSetting> apnList = new ArrayList<ApnSetting>();

        int requestedApnTypeBitmask = ApnSetting.getApnTypesBitmaskFromString(requestedApnType);
        if (requestedApnTypeBitmask == ApnSetting.TYPE_DUN) {
            ArrayList<ApnSetting> dunApns = fetchDunApns();
            if (dunApns.size() > 0) {
                for (ApnSetting dun : dunApns) {
                    apnList.add(dun);
                    if (DBG) log("buildWaitingApns: X added APN_TYPE_DUN apnList=" + apnList);
                }
                return sortApnListByPreferred(apnList);
            }
        }

        IccRecords r = mIccRecords.get();
        String operator = (r != null) ? r.getOperatorNumeric() : "";

        // This is a workaround for a bug (7305641) where we don't failover to other
        // suitable APNs if our preferred APN fails.  On prepaid ATT sims we need to
        // failover to a provisioning APN, but once we've used their default data
        // connection we are locked to it for life.  This change allows ATT devices
        // to say they don't want to use preferred at all.
        boolean usePreferred = true;
        try {
            usePreferred = ! mPhone.getContext().getResources().getBoolean(com.android.
                    internal.R.bool.config_dontPreferApn);
        } catch (Resources.NotFoundException e) {
            if (DBG) log("buildWaitingApns: usePreferred NotFoundException set to true");
            usePreferred = true;
        }
        if (usePreferred) {
            mPreferredApn = getPreferredApn();
        }
        if (DBG) {
            log("buildWaitingApns: usePreferred=" + usePreferred
                    + " canSetPreferApn=" + mCanSetPreferApn
                    + " mPreferredApn=" + mPreferredApn
                    + " operator=" + operator + " radioTech=" + radioTech
                    + " IccRecords r=" + r);
        }

        if (usePreferred && mCanSetPreferApn && mPreferredApn != null &&
                mPreferredApn.canHandleType(requestedApnTypeBitmask)) {
            if (DBG) {
                log("buildWaitingApns: Preferred APN:" + operator + ":"
                        + mPreferredApn.getOperatorNumeric() + ":" + mPreferredApn);
            }
            if (mPreferredApn.getOperatorNumeric().equals(operator)) {
                if (mPreferredApn.canSupportNetworkType(
                        ServiceState.rilRadioTechnologyToNetworkType(radioTech))) {
                    apnList.add(mPreferredApn);
                    apnList = sortApnListByPreferred(apnList);
                    if (DBG) log("buildWaitingApns: X added preferred apnList=" + apnList);
                    return apnList;
                } else {
                    if (DBG) log("buildWaitingApns: no preferred APN");
                    setPreferredApn(-1);
                    mPreferredApn = null;
                }
            } else {
                if (DBG) log("buildWaitingApns: no preferred APN");
                setPreferredApn(-1);
                mPreferredApn = null;
            }
        }

        if (DBG) log("buildWaitingApns: mAllApnSettings=" + mAllApnSettings);
        for (ApnSetting apn : mAllApnSettings) {
            if (apn.canHandleType(requestedApnTypeBitmask)) {
                if (apn.canSupportNetworkType(
                        ServiceState.rilRadioTechnologyToNetworkType(radioTech))) {
                    if (VDBG) log("buildWaitingApns: adding apn=" + apn);
                    apnList.add(apn);
                } else {
                    if (DBG) {
                        log("buildWaitingApns: networkTypeBitmask:"
                                + apn.getNetworkTypeBitmask()
                                + " does not include radioTech:"
                                + ServiceState.rilRadioTechnologyToString(radioTech));
                    }
                }
            } else if (VDBG) {
                log("buildWaitingApns: couldn't handle requested ApnType="
                        + requestedApnType);
            }
        }

        apnList = sortApnListByPreferred(apnList);
        if (DBG) log("buildWaitingApns: " + apnList.size() + " APNs in the list: " + apnList);
        return apnList;
    }

    /**
     * Sort a list of ApnSetting objects, with the preferred APNs at the front of the list
     *
     * e.g. if the preferred APN set = 2 and we have
     *   1. APN with apn_set_id = 0 = Carriers.NO_SET_SET (no set is set)
     *   2. APN with apn_set_id = 1 (not preferred set)
     *   3. APN with apn_set_id = 2 (preferred set)
     * Then the return order should be (3, 1, 2) or (3, 2, 1)
     *
     * e.g. if the preferred APN set = Carriers.NO_SET_SET (no preferred set) then the
     * return order can be anything
     */
    @VisibleForTesting
    public ArrayList<ApnSetting> sortApnListByPreferred(ArrayList<ApnSetting> list) {
        if (list == null || list.size() <= 1) return list;
        int preferredApnSetId = getPreferredApnSetId();
        if (preferredApnSetId != Telephony.Carriers.NO_APN_SET_ID) {
            list.sort(new Comparator<ApnSetting>() {
                @Override
                public int compare(ApnSetting apn1, ApnSetting apn2) {
                    if (apn1.getApnSetId() == preferredApnSetId) {
                        return -1;
                    }
                    if (apn2.getApnSetId() == preferredApnSetId) {
                        return 1;
                    }
                    return 0;
                }
            });
        }
        return list;
    }

    private String apnListToString (ArrayList<ApnSetting> apns) {
        StringBuilder result = new StringBuilder();
        for (int i = 0, size = apns.size(); i < size; i++) {
            result.append('[')
                  .append(apns.get(i).toString())
                  .append(']');
        }
        return result.toString();
    }

    private void setPreferredApn(int pos) {
        if (!mCanSetPreferApn) {
            log("setPreferredApn: X !canSEtPreferApn");
            return;
        }

        String subId = Long.toString(mPhone.getSubId());
        Uri uri = Uri.withAppendedPath(PREFERAPN_NO_UPDATE_URI_USING_SUBID, subId);
        log("setPreferredApn: delete");
        ContentResolver resolver = mPhone.getContext().getContentResolver();
        resolver.delete(uri, null, null);

        if (pos >= 0) {
            log("setPreferredApn: insert");
            ContentValues values = new ContentValues();
            values.put(APN_ID, pos);
            resolver.insert(uri, values);
        }
    }

    ApnSetting getPreferredApn() {
        if (mAllApnSettings == null || mAllApnSettings.isEmpty()) {
            log("getPreferredApn: mAllApnSettings is empty");
            return null;
        }

        String subId = Long.toString(mPhone.getSubId());
        Uri uri = Uri.withAppendedPath(PREFERAPN_NO_UPDATE_URI_USING_SUBID, subId);
        Cursor cursor = mPhone.getContext().getContentResolver().query(
                uri, new String[] { "_id", "name", "apn" },
                null, null, Telephony.Carriers.DEFAULT_SORT_ORDER);

        if (cursor != null) {
            mCanSetPreferApn = true;
        } else {
            mCanSetPreferApn = false;
        }

        if (VDBG) {
            log("getPreferredApn: mRequestedApnType=" + mRequestedApnType + " cursor=" + cursor
                    + " cursor.count=" + ((cursor != null) ? cursor.getCount() : 0));
        }

        if (mCanSetPreferApn && cursor.getCount() > 0) {
            int pos;
            cursor.moveToFirst();
            pos = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers._ID));
            for(ApnSetting p : mAllApnSettings) {
                if (p.getId() == pos && p.canHandleType(mRequestedApnType)) {
                    log("getPreferredApn: For APN type "
                            + ApnSetting.getApnTypeString(mRequestedApnType) + " found apnSetting "
                            + p);
                    cursor.close();
                    return p;
                }
            }
        }

        if (cursor != null) {
            cursor.close();
        }

        log("getPreferredApn: X not found");
        return null;
    }

    @Override
    public void handleMessage (Message msg) {
        if (VDBG) log("handleMessage msg=" + msg);

        AsyncResult ar;
        Pair<ApnContext, Integer> pair;
        ApnContext apnContext;
        int generation;
        int requestType;
        switch (msg.what) {
            case DctConstants.EVENT_RECORDS_LOADED:
                // If onRecordsLoadedOrSubIdChanged() is not called here, it should be called on
                // onSubscriptionsChanged() when a valid subId is available.
                int subId = mPhone.getSubId();
                if (SubscriptionManager.isValidSubscriptionId(subId)) {
                    onRecordsLoadedOrSubIdChanged();
                } else {
                    log("Ignoring EVENT_RECORDS_LOADED as subId is not valid: " + subId);
                }
                break;

            case DctConstants.EVENT_DATA_CONNECTION_DETACHED:
                onDataConnectionDetached();
                break;

            case DctConstants.EVENT_DATA_CONNECTION_ATTACHED:
                onDataConnectionAttached();
                break;

            case DctConstants.EVENT_DO_RECOVERY:
                mDsRecoveryHandler.doRecovery();
                break;

            case DctConstants.EVENT_APN_CHANGED:
                onApnChanged();
                break;

            case DctConstants.EVENT_PS_RESTRICT_ENABLED:
                /**
                 * We don't need to explicitly to tear down the PDP context
                 * when PS restricted is enabled. The base band will deactive
                 * PDP context and notify us with PDP_CONTEXT_CHANGED.
                 * But we should stop the network polling and prevent reset PDP.
                 */
                if (DBG) log("EVENT_PS_RESTRICT_ENABLED " + mIsPsRestricted);
                stopNetStatPoll();
                stopDataStallAlarm();
                mIsPsRestricted = true;
                break;

            case DctConstants.EVENT_PS_RESTRICT_DISABLED:
                /**
                 * When PS restrict is removed, we need setup PDP connection if
                 * PDP connection is down.
                 */
                if (DBG) log("EVENT_PS_RESTRICT_DISABLED " + mIsPsRestricted);
                mIsPsRestricted  = false;
                if (isConnected()) {
                    startNetStatPoll();
                    startDataStallAlarm(DATA_STALL_NOT_SUSPECTED);
                } else {
                    // TODO: Should all PDN states be checked to fail?
                    if (mState == DctConstants.State.FAILED) {
                        cleanUpAllConnectionsInternal(false, Phone.REASON_PS_RESTRICT_ENABLED);
                        mReregisterOnReconnectFailure = false;
                    }
                    apnContext = mApnContextsByType.get(ApnSetting.TYPE_DEFAULT);
                    if (apnContext != null) {
                        apnContext.setReason(Phone.REASON_PS_RESTRICT_ENABLED);
                        trySetupData(apnContext, REQUEST_TYPE_NORMAL);
                    } else {
                        loge("**** Default ApnContext not found ****");
                        if (Build.IS_DEBUGGABLE) {
                            throw new RuntimeException("Default ApnContext not found");
                        }
                    }
                }
                break;

            case DctConstants.EVENT_TRY_SETUP_DATA:
                trySetupData((ApnContext) msg.obj, REQUEST_TYPE_NORMAL);
                break;

            case DctConstants.EVENT_CLEAN_UP_CONNECTION:
                if (DBG) log("EVENT_CLEAN_UP_CONNECTION");
                cleanUpConnectionInternal(true, RELEASE_TYPE_DETACH, (ApnContext) msg.obj);
                break;
            case DctConstants.EVENT_CLEAN_UP_ALL_CONNECTIONS:
                if ((msg.obj != null) && (msg.obj instanceof String == false)) {
                    msg.obj = null;
                }
                cleanUpAllConnectionsInternal(true, (String) msg.obj);
                break;

            case DctConstants.EVENT_DATA_RAT_CHANGED:
                if (getDataRat() == ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN) {
                    // unknown rat is an exception for data rat change. It's only received when out
                    // of service and is not applicable for apn bearer bitmask. We should bypass the
                    // check of waiting apn list and keep the data connection on, and no need to
                    // setup a new one.
                    break;
                }
                cleanUpConnectionsOnUpdatedApns(false, Phone.REASON_NW_TYPE_CHANGED);
                //May new Network allow setupData, so try it here
                setupDataOnAllConnectableApns(Phone.REASON_NW_TYPE_CHANGED,
                        RetryFailures.ONLY_ON_CHANGE);
                break;

            case DctConstants.CMD_CLEAR_PROVISIONING_SPINNER:
                // Check message sender intended to clear the current spinner.
                if (mProvisioningSpinner == msg.obj) {
                    mProvisioningSpinner.dismiss();
                    mProvisioningSpinner = null;
                }
                break;

            case DctConstants.EVENT_ENABLE_APN:
                onEnableApn(msg.arg1, msg.arg2, (Message) msg.obj);
                break;

            case DctConstants.EVENT_DISABLE_APN:
                onDisableApn(msg.arg1, msg.arg2);
                break;

            case DctConstants.EVENT_DATA_STALL_ALARM:
                onDataStallAlarm(msg.arg1);
                break;

            case DctConstants.EVENT_ROAMING_OFF:
                onDataRoamingOff();
                break;

            case DctConstants.EVENT_ROAMING_ON:
            case DctConstants.EVENT_ROAMING_SETTING_CHANGE:
                onDataRoamingOnOrSettingsChanged(msg.what);
                break;

            case DctConstants.EVENT_DEVICE_PROVISIONED_CHANGE:
                // Update sharedPreference to false when exits new device provisioning, indicating
                // no users modifications on the settings for new devices. Thus carrier specific
                // default roaming settings can be applied for new devices till user modification.
                final SharedPreferences sp = PreferenceManager
                        .getDefaultSharedPreferences(mPhone.getContext());
                if (!sp.contains(Phone.DATA_ROAMING_IS_USER_SETTING_KEY)) {
                    sp.edit().putBoolean(Phone.DATA_ROAMING_IS_USER_SETTING_KEY, false).commit();
                }
                break;

            case DctConstants.EVENT_NETWORK_STATUS_CHANGED:
                int status = msg.arg1;
                String url = (String) msg.obj;
                onNetworkStatusChanged(status, url);
                break;

            case DctConstants.EVENT_RADIO_AVAILABLE:
                onRadioAvailable();
                break;

            case DctConstants.EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                onRadioOffOrNotAvailable();
                break;

            case DctConstants.EVENT_DATA_SETUP_COMPLETE:
                ar = (AsyncResult) msg.obj;
                pair = (Pair<ApnContext, Integer>) ar.userObj;
                apnContext = pair.first;
                generation = pair.second;
                requestType = msg.arg2;
                if (apnContext.getConnectionGeneration() == generation) {
                    boolean success = true;
                    int cause = DataFailCause.UNKNOWN;
                    if (ar.exception != null) {
                        success = false;
                        cause = (int) ar.result;
                    }
                    onDataSetupComplete(apnContext, success, cause, requestType);
                } else {
                    loge("EVENT_DATA_SETUP_COMPLETE: Dropped the event because generation "
                            + "did not match.");
                }
                break;

            case DctConstants.EVENT_DATA_SETUP_COMPLETE_ERROR:
                ar = (AsyncResult) msg.obj;
                pair = (Pair<ApnContext, Integer>) ar.userObj;
                apnContext = pair.first;
                generation = pair.second;
                requestType = msg.arg2;
                if (apnContext.getConnectionGeneration() == generation) {
                    onDataSetupCompleteError(apnContext, requestType);
                } else {
                    loge("EVENT_DATA_SETUP_COMPLETE_ERROR: Dropped the event because generation "
                            + "did not match.");
                }
                break;

            case DctConstants.EVENT_DISCONNECT_DONE:
                log("EVENT_DISCONNECT_DONE msg=" + msg);
                ar = (AsyncResult) msg.obj;
                pair = (Pair<ApnContext, Integer>) ar.userObj;
                apnContext = pair.first;
                generation = pair.second;
                if (apnContext.getConnectionGeneration() == generation) {
                    onDisconnectDone(apnContext);
                } else {
                    loge("EVENT_DISCONNECT_DONE: Dropped the event because generation "
                            + "did not match.");
                }
                break;

            case DctConstants.EVENT_VOICE_CALL_STARTED:
                onVoiceCallStarted();
                break;

            case DctConstants.EVENT_VOICE_CALL_ENDED:
                onVoiceCallEnded();
                break;
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

                    mDataStallNoRxEnabled = !enabled;
                    if (mDsRecoveryHandler.isNoRxDataStallDetectionEnabled()
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
                ApnContext apnCtx = mApnContextsByType.get(ApnSetting.TYPE_DEFAULT);
                if (apnCtx.isProvisioningApn() && apnCtx.isConnectedOrConnecting()) {
                    if (mProvisioningApnAlarmTag == msg.arg1) {
                        if (DBG) log("EVENT_PROVISIONING_APN_ALARM: Disconnecting");
                        mIsProvisioning = false;
                        mProvisioningUrl = null;
                        stopProvisioningApnAlarm();
                        cleanUpConnectionInternal(true, RELEASE_TYPE_DETACH, apnCtx);
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
            case DctConstants.EVENT_PCO_DATA_RECEIVED: {
                handlePcoData((AsyncResult)msg.obj);
                break;
            }
            case DctConstants.EVENT_DATA_RECONNECT:
                onDataReconnect(msg.getData());
                break;
            case DctConstants.EVENT_DATA_SERVICE_BINDING_CHANGED:
                onDataServiceBindingChanged((Boolean) ((AsyncResult) msg.obj).result);
                break;
            case DctConstants.EVENT_DATA_ENABLED_CHANGED:
                ar = (AsyncResult) msg.obj;
                if (ar.result instanceof Pair) {
                    Pair<Boolean, Integer> p = (Pair<Boolean, Integer>) ar.result;
                    boolean enabled = p.first;
                    int reason = p.second;
                    onDataEnabledChanged(enabled, reason);
                }
                break;
            case DctConstants.EVENT_DATA_ENABLED_OVERRIDE_RULES_CHANGED:
                onDataEnabledOverrideRulesChanged();
                break;
            default:
                Rlog.e("DcTracker", "Unhandled event=" + msg);
                break;

        }
    }

    private int getApnProfileID(String apnType) {
        if (TextUtils.equals(apnType, PhoneConstants.APN_TYPE_IMS)) {
            return RILConstants.DATA_PROFILE_IMS;
        } else if (TextUtils.equals(apnType, PhoneConstants.APN_TYPE_FOTA)) {
            return RILConstants.DATA_PROFILE_FOTA;
        } else if (TextUtils.equals(apnType, PhoneConstants.APN_TYPE_CBS)) {
            return RILConstants.DATA_PROFILE_CBS;
        } else if (TextUtils.equals(apnType, PhoneConstants.APN_TYPE_IA)) {
            return RILConstants.DATA_PROFILE_DEFAULT; // DEFAULT for now
        } else if (TextUtils.equals(apnType, PhoneConstants.APN_TYPE_DUN)) {
            return RILConstants.DATA_PROFILE_TETHERED;
        } else {
            return RILConstants.DATA_PROFILE_DEFAULT;
        }
    }

    private int getCellLocationId() {
        int cid = -1;
        CellLocation loc = mPhone.getCellLocation();

        if (loc != null) {
            if (loc instanceof GsmCellLocation) {
                cid = ((GsmCellLocation)loc).getCid();
            } else if (loc instanceof CdmaCellLocation) {
                cid = ((CdmaCellLocation)loc).getBaseStationId();
            }
        }
        return cid;
    }

    private IccRecords getUiccRecords(int appFamily) {
        return mUiccController.getIccRecords(mPhone.getPhoneId(), appFamily);
    }


    private void onUpdateIcc() {
        if (mUiccController == null ) {
            return;
        }

        IccRecords newIccRecords = getUiccRecords(UiccController.APP_FAM_3GPP);

        IccRecords r = mIccRecords.get();
        if (r != newIccRecords) {
            if (r != null) {
                log("Removing stale icc objects.");
                r.unregisterForRecordsLoaded(this);
                mIccRecords.set(null);
            }
            if (newIccRecords != null) {
                if (SubscriptionManager.isValidSubscriptionId(mPhone.getSubId())) {
                    log("New records found.");
                    mIccRecords.set(newIccRecords);
                    newIccRecords.registerForRecordsLoaded(
                            this, DctConstants.EVENT_RECORDS_LOADED, null);
                }
            } else {
                onSimNotReady();
            }
        }
    }

    /**
     * Update DcTracker.
     *
     * TODO: This should be cleaned up. DcTracker should listen to those events.
     */
    public void update() {
        log("update sub = " + mPhone.getSubId());
        log("update(): Active DDS, register for all events now!");
        onUpdateIcc();

        mAutoAttachEnabled.set(false);

        mPhone.updateCurrentCarrierInProvider();
    }

    /**
     * For non DDS phone, mAutoAttachEnabled should be true because it may be detached
     * automatically from network only because it's idle for too long. In this case, we should
     * try setting up data call even if it's not attached for 2G or 3G networks. And doing so will
     * trigger PS attach if possible.
     */
    @VisibleForTesting
    public boolean shouldAutoAttach() {
        if (mAutoAttachEnabled.get()) return true;

        PhoneSwitcher phoneSwitcher = PhoneSwitcher.getInstance();
        ServiceState serviceState = mPhone.getServiceState();
        return phoneSwitcher != null && serviceState != null
                && mPhone.getPhoneId() != phoneSwitcher.getPreferredDataPhoneId()
                && serviceState.getVoiceRegState() == ServiceState.STATE_IN_SERVICE
                && serviceState.getVoiceNetworkType() != NETWORK_TYPE_LTE
                && serviceState.getVoiceNetworkType() != NETWORK_TYPE_NR;
    }

    private void notifyAllDataDisconnected() {
        sEnableFailFastRefCounter = 0;
        mFailFast = false;
        mAllDataDisconnectedRegistrants.notifyRegistrants();
    }

    public void registerForAllDataDisconnected(Handler h, int what) {
        mAllDataDisconnectedRegistrants.addUnique(h, what, null);

        if (isDisconnected()) {
            log("notify All Data Disconnected");
            notifyAllDataDisconnected();
        }
    }

    public void unregisterForAllDataDisconnected(Handler h) {
        mAllDataDisconnectedRegistrants.remove(h);
    }

    private void onDataEnabledChanged(boolean enable,
                                      @DataEnabledChangedReason int enabledChangedReason) {
        if (DBG) {
            log("onDataEnabledChanged: enable=" + enable + ", enabledChangedReason="
                    + enabledChangedReason);
        }

        if (enable) {
            reevaluateDataConnections();
            setupDataOnAllConnectableApns(Phone.REASON_DATA_ENABLED, RetryFailures.ALWAYS);
        } else {
            String cleanupReason;
            switch (enabledChangedReason) {
                case DataEnabledSettings.REASON_INTERNAL_DATA_ENABLED:
                    cleanupReason = Phone.REASON_DATA_DISABLED_INTERNAL;
                    break;
                case DataEnabledSettings.REASON_DATA_ENABLED_BY_CARRIER:
                    cleanupReason = Phone.REASON_CARRIER_ACTION_DISABLE_METERED_APN;
                    break;
                case DataEnabledSettings.REASON_USER_DATA_ENABLED:
                case DataEnabledSettings.REASON_POLICY_DATA_ENABLED:
                case DataEnabledSettings.REASON_PROVISIONED_CHANGED:
                case DataEnabledSettings.REASON_PROVISIONING_DATA_ENABLED_CHANGED:
                default:
                    cleanupReason = Phone.REASON_DATA_SPECIFIC_DISABLED;
                    break;

            }
            cleanUpAllConnectionsInternal(true, cleanupReason);
        }
    }

    private void log(String s) {
        Rlog.d(mLogTag, s);
    }

    private void loge(String s) {
        Rlog.e(mLogTag, s);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("DcTracker:");
        pw.println(" RADIO_TESTS=" + RADIO_TESTS);
        pw.println(" mDataEnabledSettings=" + mDataEnabledSettings);
        pw.println(" isDataAllowed=" + isDataAllowed(null));
        pw.flush();
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
        pw.println(" mDataStallNoRxEnabled=" + mDataStallNoRxEnabled);
        pw.println(" mEmergencyApn=" + mEmergencyApn);
        pw.println(" mSentSinceLastRecv=" + mSentSinceLastRecv);
        pw.println(" mNoRecvPollCount=" + mNoRecvPollCount);
        pw.println(" mResolver=" + mResolver);
        pw.println(" mReconnectIntent=" + mReconnectIntent);
        pw.println(" mAutoAttachEnabled=" + mAutoAttachEnabled.get());
        pw.println(" mIsScreenOn=" + mIsScreenOn);
        pw.println(" mUniqueIdGenerator=" + mUniqueIdGenerator);
        pw.println(" mDataServiceBound=" + mDataServiceBound);
        pw.println(" mDataRoamingLeakageLog= ");
        mDataRoamingLeakageLog.dump(fd, pw, args);
        pw.println(" mApnSettingsInitializationLog= ");
        mApnSettingsInitializationLog.dump(fd, pw, args);
        pw.flush();
        pw.println(" ***************************************");
        DcController dcc = mDcc;
        if (dcc != null) {
            if (mDataServiceBound) {
                dcc.dump(fd, pw, args);
            } else {
                pw.println(" Can't dump mDcc because data service is not bound.");
            }
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

        pw.println(" mAllApnSettings size=" + mAllApnSettings.size());
        for (int i = 0; i < mAllApnSettings.size(); i++) {
            pw.printf(" mAllApnSettings[%d]: %s\n", i, mAllApnSettings.get(i));
        }
        pw.flush();

        pw.println(" mPreferredApn=" + mPreferredApn);
        pw.println(" mIsPsRestricted=" + mIsPsRestricted);
        pw.println(" mIsDisposed=" + mIsDisposed);
        pw.println(" mIntentReceiver=" + mIntentReceiver);
        pw.println(" mReregisterOnReconnectFailure=" + mReregisterOnReconnectFailure);
        pw.println(" canSetPreferApn=" + mCanSetPreferApn);
        pw.println(" mApnObserver=" + mApnObserver);
        pw.println(" getOverallState=" + getOverallState());
        pw.println(" mAttached=" + mAttached.get());
        mDataEnabledSettings.dump(fd, pw, args);
        pw.flush();
    }

    public String[] getPcscfAddress(String apnType) {
        log("getPcscfAddress()");
        ApnContext apnContext = null;

        if(apnType == null){
            log("apnType is null, return null");
            return null;
        }

        if (TextUtils.equals(apnType, PhoneConstants.APN_TYPE_EMERGENCY)) {
            apnContext = mApnContextsByType.get(ApnSetting.TYPE_EMERGENCY);
        } else if (TextUtils.equals(apnType, PhoneConstants.APN_TYPE_IMS)) {
            apnContext = mApnContextsByType.get(ApnSetting.TYPE_IMS);
        } else {
            log("apnType is invalid, return null");
            return null;
        }

        if (apnContext == null) {
            log("apnContext is null, return null");
            return null;
        }

        DataConnection dataConnection = apnContext.getDataConnection();
        String[] result = null;

        if (dataConnection != null) {
            result = dataConnection.getPcscfAddresses();

            if (result != null) {
                for (int i = 0; i < result.length; i++) {
                    log("Pcscf[" + i + "]: " + result[i]);
                }
            }
            return result;
        }
        return null;
    }

    /**
     * Read APN configuration from Telephony.db for Emergency APN
     * All operators recognize the connection request for EPDN based on APN type
     * PLMN name,APN name are not mandatory parameters
     */
    private void initEmergencyApnSetting() {
        // Operator Numeric is not available when sim records are not loaded.
        // Query Telephony.db with APN type as EPDN request does not
        // require APN name, plmn and all operators support same APN config.
        // DB will contain only one entry for Emergency APN
        String selection = "type=\"emergency\"";
        Cursor cursor = mPhone.getContext().getContentResolver().query(
                Uri.withAppendedPath(Telephony.Carriers.CONTENT_URI, "filtered"),
                null, selection, null, null);

        if (cursor != null) {
            if (cursor.getCount() > 0) {
                if (cursor.moveToFirst()) {
                    mEmergencyApn = ApnSetting.makeApnSetting(cursor);
                }
            }
            cursor.close();
        }
        if (mEmergencyApn != null) return;

        // If no emergency APN setting has been found, make one using reasonable defaults
        mEmergencyApn = new ApnSetting.Builder()
                .setEntryName("Emergency")
                .setProtocol(ApnSetting.PROTOCOL_IPV4V6)
                .setApnName("sos")
                .setApnTypeBitmask(ApnSetting.TYPE_EMERGENCY)
                .build();
    }

    /**
     * Add the Emergency APN settings to APN settings list
     */
    private void addEmergencyApnSetting() {
        if(mEmergencyApn != null) {
            for (ApnSetting apn : mAllApnSettings) {
                if (apn.canHandleType(ApnSetting.TYPE_EMERGENCY)) {
                    log("addEmergencyApnSetting - E-APN setting is already present");
                    return;
                }
            }

            // If all of the APN settings cannot handle emergency, we add the emergency APN to the
            // list explicitly.
            if (!mAllApnSettings.contains(mEmergencyApn)) {
                mAllApnSettings.add(mEmergencyApn);
                log("Adding emergency APN : " + mEmergencyApn);
                return;
            }
        }
    }

    private boolean containsAllApns(ArrayList<ApnSetting> oldApnList,
                                    ArrayList<ApnSetting> newApnList) {
        for (ApnSetting newApnSetting : newApnList) {
            boolean canHandle = false;
            for (ApnSetting oldApnSetting : oldApnList) {
                // Make sure at least one of the APN from old list can cover the new APN
                if (oldApnSetting.equals(newApnSetting,
                        mPhone.getServiceState().getDataRoamingFromRegistration())) {
                    canHandle = true;
                    break;
                }
            }
            if (!canHandle) return false;
        }
        return true;
    }

    private void cleanUpConnectionsOnUpdatedApns(boolean detach, String reason) {
        if (DBG) log("cleanUpConnectionsOnUpdatedApns: detach=" + detach);
        if (mAllApnSettings.isEmpty()) {
            cleanUpAllConnectionsInternal(detach, Phone.REASON_APN_CHANGED);
        } else {
            if (getDataRat() == ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN) {
                // unknown rat is an exception for data rat change. Its only received when out of
                // service and is not applicable for apn bearer bitmask. We should bypass the check
                // of waiting apn list and keep the data connection on.
                return;
            }
            for (ApnContext apnContext : mApnContexts.values()) {
                ArrayList<ApnSetting> currentWaitingApns = apnContext.getWaitingApns();
                ArrayList<ApnSetting> waitingApns = buildWaitingApns(
                        apnContext.getApnType(), getDataRat());
                if (VDBG) log("new waitingApns:" + waitingApns);
                if ((currentWaitingApns != null)
                        && ((waitingApns.size() != currentWaitingApns.size())
                        // Check if the existing waiting APN list can cover the newly built APN
                        // list. If yes, then we don't need to tear down the existing data call.
                        // TODO: We probably need to rebuild APN list when roaming status changes.
                        || !containsAllApns(currentWaitingApns, waitingApns))) {
                    if (VDBG) log("new waiting apn is different for " + apnContext);
                    apnContext.setWaitingApns(waitingApns);
                    if (!apnContext.isDisconnected()) {
                        if (VDBG) log("cleanUpConnectionsOnUpdatedApns for " + apnContext);
                        apnContext.setReason(reason);
                        cleanUpConnectionInternal(true, RELEASE_TYPE_DETACH, apnContext);
                    }
                }
            }
        }

        if (!isConnected()) {
            stopNetStatPoll();
            stopDataStallAlarm();
        }

        mRequestedApnType = ApnSetting.TYPE_DEFAULT;

        if (DBG) log("mDisconnectPendingCount = " + mDisconnectPendingCount);
        if (detach && mDisconnectPendingCount == 0) {
            notifyAllDataDisconnected();
        }
    }

    /**
     * Polling stuff
     */
    private void resetPollStats() {
        mTxPkts = -1;
        mRxPkts = -1;
        mNetStatPollPeriod = POLL_NETSTAT_MILLIS;
    }

    private void startNetStatPoll() {
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

    private void stopNetStatPoll() {
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

    private void handleStartNetStatPoll(DctConstants.Activity activity) {
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

    private void handleStopNetStatPoll(DctConstants.Activity activity) {
        stopNetStatPoll();
        stopDataStallAlarm();
        setActivity(activity);
    }

    private void onDataEnabledOverrideRulesChanged() {
        if (DBG) {
            log("onDataEnabledOverrideRulesChanged");
        }

        for (ApnContext apnContext : mPrioritySortedApnContexts) {
            if (isDataAllowed(apnContext, REQUEST_TYPE_NORMAL, null)) {
                if (apnContext.getDataConnection() != null) {
                    apnContext.getDataConnection().reevaluateRestrictedState();
                }
                setupDataOnConnectableApn(apnContext, Phone.REASON_DATA_ENABLED_OVERRIDE,
                        RetryFailures.ALWAYS);
            } else if (shouldCleanUpConnection(apnContext, true)) {
                apnContext.setReason(Phone.REASON_DATA_ENABLED_OVERRIDE);
                cleanUpConnectionInternal(true, RELEASE_TYPE_DETACH, apnContext);
            }
        }
    }

    private void updateDataActivity() {
        long sent, received;

        DctConstants.Activity newActivity;

        TxRxSum preTxRxSum = new TxRxSum(mTxPkts, mRxPkts);
        TxRxSum curTxRxSum = new TxRxSum();
        curTxRxSum.updateTotalTxRxSum();
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

    private void handlePcoData(AsyncResult ar) {
        if (ar.exception != null) {
            loge("PCO_DATA exception: " + ar.exception);
            return;
        }
        PcoData pcoData = (PcoData)(ar.result);
        ArrayList<DataConnection> dcList = new ArrayList<>();
        DataConnection temp = mDcc.getActiveDcByCid(pcoData.cid);
        if (temp != null) {
            dcList.add(temp);
        }
        if (dcList.size() == 0) {
            loge("PCO_DATA for unknown cid: " + pcoData.cid + ", inferring");
            for (DataConnection dc : mDataConnections.values()) {
                final int cid = dc.getCid();
                if (cid == pcoData.cid) {
                    if (VDBG) log("  found " + dc);
                    dcList.clear();
                    dcList.add(dc);
                    break;
                }
                // check if this dc is still connecting
                if (cid == -1) {
                    for (ApnContext apnContext : dc.getApnContexts()) {
                        if (apnContext.getState() == DctConstants.State.CONNECTING) {
                            if (VDBG) log("  found potential " + dc);
                            dcList.add(dc);
                            break;
                        }
                    }
                }
            }
        }
        if (dcList.size() == 0) {
            loge("PCO_DATA - couldn't infer cid");
            return;
        }
        for (DataConnection dc : dcList) {
            List<ApnContext> apnContextList = dc.getApnContexts();
            if (apnContextList.size() == 0) {
                break;
            }
            // send one out for each apn type in play
            for (ApnContext apnContext : apnContextList) {
                String apnType = apnContext.getApnType();

                final Intent intent = new Intent(TelephonyIntents.ACTION_CARRIER_SIGNAL_PCO_VALUE);
                intent.putExtra(TelephonyIntents.EXTRA_APN_TYPE_KEY, apnType);
                intent.putExtra(TelephonyIntents.EXTRA_APN_PROTO_KEY, pcoData.bearerProto);
                intent.putExtra(TelephonyIntents.EXTRA_PCO_ID_KEY, pcoData.pcoId);
                intent.putExtra(TelephonyIntents.EXTRA_PCO_VALUE_KEY, pcoData.contents);
                mPhone.getCarrierSignalAgent().notifyCarrierSignalReceivers(intent);
            }
        }
    }

    /**
     * Data-Stall
     */

    // Recovery action taken in case of data stall
    @IntDef(
        value = {
            RECOVERY_ACTION_GET_DATA_CALL_LIST,
            RECOVERY_ACTION_CLEANUP,
            RECOVERY_ACTION_REREGISTER,
            RECOVERY_ACTION_RADIO_RESTART
        })
    @Retention(RetentionPolicy.SOURCE)
    private @interface RecoveryAction {};
    private static final int RECOVERY_ACTION_GET_DATA_CALL_LIST      = 0;
    private static final int RECOVERY_ACTION_CLEANUP                 = 1;
    private static final int RECOVERY_ACTION_REREGISTER              = 2;
    private static final int RECOVERY_ACTION_RADIO_RESTART           = 3;

    // Recovery handler class for cellular data stall
    private class DataStallRecoveryHandler {
        // Default minimum duration between each recovery steps
        private static final int
                DEFAULT_MIN_DURATION_BETWEEN_RECOVERY_STEPS_IN_MS = (3 * 60 * 1000); // 3 mins

        // The elapsed real time of last recovery attempted
        private long mTimeLastRecoveryStartMs;
        // Whether current network good or not
        private boolean mIsValidNetwork;

        public DataStallRecoveryHandler() {
            reset();
        }

        public void reset() {
            mTimeLastRecoveryStartMs = 0;
            putRecoveryAction(RECOVERY_ACTION_GET_DATA_CALL_LIST);
        }

        public boolean isAggressiveRecovery() {
            @RecoveryAction int action = getRecoveryAction();

            return ((action == RECOVERY_ACTION_CLEANUP)
                    || (action == RECOVERY_ACTION_REREGISTER)
                    || (action == RECOVERY_ACTION_RADIO_RESTART));
        }

        private long getMinDurationBetweenRecovery() {
            return Settings.Global.getLong(mResolver,
                Settings.Global.MIN_DURATION_BETWEEN_RECOVERY_STEPS_IN_MS,
                DEFAULT_MIN_DURATION_BETWEEN_RECOVERY_STEPS_IN_MS);
        }

        private long getElapsedTimeSinceRecoveryMs() {
            return (SystemClock.elapsedRealtime() - mTimeLastRecoveryStartMs);
        }

        @RecoveryAction
        private int getRecoveryAction() {
            @RecoveryAction int action = Settings.System.getInt(mResolver,
                    "radio.data.stall.recovery.action", RECOVERY_ACTION_GET_DATA_CALL_LIST);
            if (VDBG_STALL) log("getRecoveryAction: " + action);
            return action;
        }

        private void putRecoveryAction(@RecoveryAction int action) {
            Settings.System.putInt(mResolver, "radio.data.stall.recovery.action", action);
            if (VDBG_STALL) log("putRecoveryAction: " + action);
        }

        private void broadcastDataStallDetected(@RecoveryAction int recoveryAction) {
            Intent intent = new Intent(TelephonyManager.ACTION_DATA_STALL_DETECTED);
            SubscriptionManager.putPhoneIdAndSubIdExtra(intent, mPhone.getPhoneId());
            intent.putExtra(TelephonyManager.EXTRA_RECOVERY_ACTION, recoveryAction);
            mPhone.getContext().sendBroadcast(intent, READ_PRIVILEGED_PHONE_STATE);
        }

        private boolean isRecoveryAlreadyStarted() {
            return getRecoveryAction() != RECOVERY_ACTION_GET_DATA_CALL_LIST;
        }

        private boolean checkRecovery() {
            // To avoid back to back recovery wait for a grace period
            if (getElapsedTimeSinceRecoveryMs() < getMinDurationBetweenRecovery()) {
                if (VDBG_STALL) log("skip back to back data stall recovery");
                return false;
            }

            // Allow recovery if data is expected to work
            return mAttached.get() && isDataAllowed(null);
        }

        private void triggerRecovery() {
            sendMessage(obtainMessage(DctConstants.EVENT_DO_RECOVERY));
        }

        public void doRecovery() {
            if (getOverallState() == DctConstants.State.CONNECTED) {
                // Go through a series of recovery steps, each action transitions to the next action
                @RecoveryAction final int recoveryAction = getRecoveryAction();
                TelephonyMetrics.getInstance().writeDataStallEvent(
                        mPhone.getPhoneId(), recoveryAction);
                broadcastDataStallDetected(recoveryAction);

                switch (recoveryAction) {
                    case RECOVERY_ACTION_GET_DATA_CALL_LIST:
                        EventLog.writeEvent(EventLogTags.DATA_STALL_RECOVERY_GET_DATA_CALL_LIST,
                            mSentSinceLastRecv);
                        if (DBG) log("doRecovery() get data call list");
                        mDataServiceManager.requestDataCallList(obtainMessage());
                        putRecoveryAction(RECOVERY_ACTION_CLEANUP);
                        break;
                    case RECOVERY_ACTION_CLEANUP:
                        EventLog.writeEvent(EventLogTags.DATA_STALL_RECOVERY_CLEANUP,
                            mSentSinceLastRecv);
                        if (DBG) log("doRecovery() cleanup all connections");
                        cleanUpConnection(mApnContexts.get(ApnSetting.getApnTypeString(
                                ApnSetting.TYPE_DEFAULT)));
                        putRecoveryAction(RECOVERY_ACTION_REREGISTER);
                        break;
                    case RECOVERY_ACTION_REREGISTER:
                        EventLog.writeEvent(EventLogTags.DATA_STALL_RECOVERY_REREGISTER,
                            mSentSinceLastRecv);
                        if (DBG) log("doRecovery() re-register");
                        mPhone.getServiceStateTracker().reRegisterNetwork(null);
                        putRecoveryAction(RECOVERY_ACTION_RADIO_RESTART);
                        break;
                    case RECOVERY_ACTION_RADIO_RESTART:
                        EventLog.writeEvent(EventLogTags.DATA_STALL_RECOVERY_RADIO_RESTART,
                            mSentSinceLastRecv);
                        if (DBG) log("restarting radio");
                        restartRadio();
                        reset();
                        break;
                    default:
                        throw new RuntimeException("doRecovery: Invalid recoveryAction="
                            + recoveryAction);
                }
                mSentSinceLastRecv = 0;
                mTimeLastRecoveryStartMs = SystemClock.elapsedRealtime();
            }
        }

        public void processNetworkStatusChanged(boolean isValid) {
            if (isValid) {
                mIsValidNetwork = true;
                reset();
            } else {
                if (mIsValidNetwork || isRecoveryAlreadyStarted()) {
                    mIsValidNetwork = false;
                    // Check and trigger a recovery if network switched from good
                    // to bad or recovery is already started before.
                    if (checkRecovery()) {
                        if (DBG) log("trigger data stall recovery");
                        triggerRecovery();
                    }
                }
            }
        }

        public boolean isRecoveryOnBadNetworkEnabled() {
            return Settings.Global.getInt(mResolver,
                    Settings.Global.DATA_STALL_RECOVERY_ON_BAD_NETWORK, 1) == 1;
        }

        public boolean isNoRxDataStallDetectionEnabled() {
            return mDataStallNoRxEnabled && !isRecoveryOnBadNetworkEnabled();
        }
    }

    private void updateDataStallInfo() {
        long sent, received;

        TxRxSum preTxRxSum = new TxRxSum(mDataStallTxRxSum);
        mDataStallTxRxSum.updateTcpTxRxSum();

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
            mDsRecoveryHandler.reset();
        } else if (sent > 0 && received == 0) {
            if (isPhoneStateIdle()) {
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
            mDsRecoveryHandler.reset();
        } else {
            if (VDBG_STALL) log("updateDataStallInfo: NONE");
        }
    }

    private boolean isPhoneStateIdle() {
        for (int i = 0; i < mTelephonyManager.getPhoneCount(); i++) {
            Phone phone = PhoneFactory.getPhone(i);
            if (phone != null && phone.getState() != PhoneConstants.State.IDLE) {
                log("isPhoneStateIdle false: Voice call active on phone " + i);
                return false;
            }
        }
        return true;
    }

    private void onDataStallAlarm(int tag) {
        if (mDataStallAlarmTag != tag) {
            if (DBG) {
                log("onDataStallAlarm: ignore, tag=" + tag + " expecting " + mDataStallAlarmTag);
            }
            return;
        }

        if (DBG) log("Data stall alarm");
        updateDataStallInfo();

        int hangWatchdogTrigger = Settings.Global.getInt(mResolver,
                Settings.Global.PDP_WATCHDOG_TRIGGER_PACKET_COUNT,
                NUMBER_SENT_PACKETS_OF_HANG);

        boolean suspectedStall = DATA_STALL_NOT_SUSPECTED;
        if (mSentSinceLastRecv >= hangWatchdogTrigger) {
            if (DBG) {
                log("onDataStallAlarm: tag=" + tag + " do recovery action="
                        + mDsRecoveryHandler.getRecoveryAction());
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

    private void startDataStallAlarm(boolean suspectedStall) {
        int delayInMs;

        if (mDsRecoveryHandler.isNoRxDataStallDetectionEnabled()
                && getOverallState() == DctConstants.State.CONNECTED) {
            // If screen is on or data stall is currently suspected, set the alarm
            // with an aggressive timeout.
            if (mIsScreenOn || suspectedStall || mDsRecoveryHandler.isAggressiveRecovery()) {
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
            intent.putExtra(INTENT_DATA_STALL_ALARM_EXTRA_TAG, mDataStallAlarmTag);
            intent.putExtra(INTENT_DATA_STALL_ALARM_EXTRA_TRANSPORT_TYPE, mTransportType);
            SubscriptionManager.putPhoneIdAndSubIdExtra(intent, mPhone.getPhoneId());
            mDataStallAlarmIntent = PendingIntent.getBroadcast(mPhone.getContext(), 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + delayInMs, mDataStallAlarmIntent);
        } else {
            if (VDBG_STALL) {
                log("startDataStallAlarm: NOT started, no connection tag=" + mDataStallAlarmTag);
            }
        }
    }

    private void stopDataStallAlarm() {
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

    private void restartDataStallAlarm() {
        if (isConnected() == false) return;
        // To be called on screen status change.
        // Do not cancel the alarm if it is set with aggressive timeout.
        if (mDsRecoveryHandler.isAggressiveRecovery()) {
            if (DBG) log("restartDataStallAlarm: action is pending. not resetting the alarm.");
            return;
        }
        if (VDBG_STALL) log("restartDataStallAlarm: stop then start.");
        stopDataStallAlarm();
        startDataStallAlarm(DATA_STALL_NOT_SUSPECTED);
    }

    /**
     * Provisioning APN
     */
    private void onActionIntentProvisioningApnAlarm(Intent intent) {
        if (DBG) log("onActionIntentProvisioningApnAlarm: action=" + intent.getAction());
        Message msg = obtainMessage(DctConstants.EVENT_PROVISIONING_APN_ALARM,
                intent.getAction());
        msg.arg1 = intent.getIntExtra(PROVISIONING_APN_ALARM_TAG_EXTRA, 0);
        sendMessage(msg);
    }

    private void startProvisioningApnAlarm() {
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

    private void stopProvisioningApnAlarm() {
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

    private static DataProfile createDataProfile(ApnSetting apn, boolean isPreferred) {
        return createDataProfile(apn, apn.getProfileId(), isPreferred);
    }

    @VisibleForTesting
    public static DataProfile createDataProfile(ApnSetting apn, int profileId,
                                                boolean isPreferred) {
        int profileType;

        int networkTypeBitmask = apn.getNetworkTypeBitmask();

        if (networkTypeBitmask == 0) {
            profileType = DataProfile.TYPE_COMMON;
        } else if (ServiceState.bearerBitmapHasCdma(networkTypeBitmask)) {
            profileType = DataProfile.TYPE_3GPP2;
        } else {
            profileType = DataProfile.TYPE_3GPP;
        }

        return new DataProfile.Builder()
                .setProfileId(profileId)
                .setApn(apn.getApnName())
                .setProtocolType(apn.getProtocol())
                .setAuthType(apn.getAuthType())
                .setUserName(apn.getUser())
                .setPassword(apn.getPassword())
                .setType(profileType)
                .setMaxConnectionsTime(apn.getMaxConnsTime())
                .setMaxConnections(apn.getMaxConns())
                .setWaitTime(apn.getWaitTime())
                .enable(apn.isEnabled())
                .setSupportedApnTypesBitmask(apn.getApnTypeBitmask())
                .setRoamingProtocolType(apn.getRoamingProtocol())
                .setBearerBitmask(networkTypeBitmask)
                .setMtu(apn.getMtu())
                .setPersistent(apn.isPersistent())
                .setPreferred(isPreferred)
                .build();
    }

    private void onDataServiceBindingChanged(boolean bound) {
        if (bound) {
            mDcc.start();
        } else {
            mDcc.dispose();
        }
        mDataServiceBound = bound;
    }

    public static String requestTypeToString(@RequestNetworkType int type) {
        switch (type) {
            case REQUEST_TYPE_NORMAL: return "NORMAL";
            case REQUEST_TYPE_HANDOVER: return "HANDOVER";
        }
        return "UNKNOWN";
    }

    public static String releaseTypeToString(@ReleaseNetworkType int type) {
        switch (type) {
            case RELEASE_TYPE_NORMAL: return "NORMAL";
            case RELEASE_TYPE_DETACH: return "DETACH";
            case RELEASE_TYPE_HANDOVER: return "HANDOVER";
        }
        return "UNKNOWN";
    }

    @RilRadioTechnology
    private int getDataRat() {
        ServiceState ss = mPhone.getServiceState();
        NetworkRegistrationInfo nrs = ss.getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_PS, mTransportType);
        if (nrs != null) {
            return ServiceState.networkTypeToRilRadioTechnology(nrs.getAccessNetworkTechnology());
        }
        return ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN;
    }

    @RilRadioTechnology
    private int getVoiceRat() {
        ServiceState ss = mPhone.getServiceState();
        NetworkRegistrationInfo nrs = ss.getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_CS, mTransportType);
        if (nrs != null) {
            return ServiceState.networkTypeToRilRadioTechnology(nrs.getAccessNetworkTechnology());
        }
        return ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN;
    }
}
