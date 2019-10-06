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

package com.android.internal.telephony;

import static android.provider.Telephony.ServiceStateTable.getContentValuesForServiceState;
import static android.provider.Telephony.ServiceStateTable.getUriForSubscriptionId;

import static com.android.internal.telephony.CarrierActionAgent.CARRIER_ACTION_SET_RADIO_ENABLED;
import static com.android.internal.telephony.uicc.IccRecords.CARRIER_NAME_DISPLAY_CONDITION_BITMASK_PLMN;
import static com.android.internal.telephony.uicc.IccRecords.CARRIER_NAME_DISPLAY_CONDITION_BITMASK_SPN;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.UnsupportedAppUsage;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.hardware.radio.V1_0.CellInfoType;
import android.net.NetworkCapabilities;
import android.os.AsyncResult;
import android.os.BaseBundle;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.WorkSource;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.telephony.AccessNetworkConstants;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.AccessNetworkConstants.TransportType;
import android.telephony.CarrierConfigManager;
import android.telephony.CellIdentity;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityTdscdma;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellLocation;
import android.telephony.DataSpecificRegistrationInfo;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.PhysicalChannelConfig;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyManager;
import android.telephony.VoiceSpecificRegistrationInfo;
import android.telephony.cdma.CdmaCellLocation;
import android.telephony.gsm.GsmCellLocation;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.LocalLog;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.StatsLog;
import android.util.TimestampedValue;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.cdma.CdmaSubscriptionSourceManager;
import com.android.internal.telephony.cdma.EriInfo;
import com.android.internal.telephony.cdma.EriManager;
import com.android.internal.telephony.cdnr.CarrierDisplayNameData;
import com.android.internal.telephony.cdnr.CarrierDisplayNameResolver;
import com.android.internal.telephony.dataconnection.DataConnection;
import com.android.internal.telephony.dataconnection.DcTracker;
import com.android.internal.telephony.dataconnection.TransportManager;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppState;
import com.android.internal.telephony.uicc.IccCardStatus.CardState;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.RuimRecords;
import com.android.internal.telephony.uicc.SIMRecords;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.UiccProfile;
import com.android.internal.telephony.util.NotificationChannelController;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@hide}
 */
public class ServiceStateTracker extends Handler {
    static final String LOG_TAG = "SST";
    static final boolean DBG = true;
    private static final boolean VDBG = false;  // STOPSHIP if true

    private static final String PROP_FORCE_ROAMING = "telephony.test.forceRoaming";

    @UnsupportedAppUsage
    private CommandsInterface mCi;
    @UnsupportedAppUsage
    private UiccController mUiccController = null;
    @UnsupportedAppUsage
    private UiccCardApplication mUiccApplcation = null;
    @UnsupportedAppUsage
    private IccRecords mIccRecords = null;

    private boolean mVoiceCapable;

    @UnsupportedAppUsage
    public ServiceState mSS;
    @UnsupportedAppUsage
    private ServiceState mNewSS;

    // This is the minimum interval at which CellInfo requests will be serviced by the modem.
    // Any requests that arrive within MinInterval of the previous reuqest will simply receive the
    // cached result. This is a power-saving feature, because requests to the modem may require
    // wakeup of a separate chip and bus communication. Because the cost of wakeups is
    // architecture dependent, it would be preferable if this sort of optimization could be
    // handled in SoC-specific code, but for now, keep it here to ensure that in case further
    // optimizations are not present elsewhere, there is a power-management scheme of last resort.
    private int mCellInfoMinIntervalMs =  2000;

    // Maximum time to wait for a CellInfo request before assuming it won't arrive and returning
    // null to callers. Note, that if a CellInfo response does arrive later, then it will be
    // treated as an UNSOL, which means it will be cached as well as sent to registrants; thus,
    // this only impacts the behavior of one-shot requests (be they blocking or non-blocking).
    private static final long CELL_INFO_LIST_QUERY_TIMEOUT = 2000;

    private long mLastCellInfoReqTime;
    private List<CellInfo> mLastCellInfoList = null;
    private List<PhysicalChannelConfig> mLastPhysicalChannelConfigList = null;

    @UnsupportedAppUsage
    private SignalStrength mSignalStrength;

    // TODO - this should not be public, right now used externally GsmConnetion.
    public RestrictedState mRestrictedState;

    /**
     * A unique identifier to track requests associated with a poll
     * and ignore stale responses.  The value is a count-down of
     * expected responses in this pollingContext.
     */
    @VisibleForTesting
    public int[] mPollingContext;
    @UnsupportedAppUsage
    private boolean mDesiredPowerState;

    /**
     * By default, strength polling is enabled.  However, if we're
     * getting unsolicited signal strength updates from the radio, set
     * value to true and don't bother polling any more.
     */
    private boolean mDontPollSignalStrength = false;

    @UnsupportedAppUsage
    private RegistrantList mVoiceRoamingOnRegistrants = new RegistrantList();
    @UnsupportedAppUsage
    private RegistrantList mVoiceRoamingOffRegistrants = new RegistrantList();
    @UnsupportedAppUsage
    private RegistrantList mDataRoamingOnRegistrants = new RegistrantList();
    @UnsupportedAppUsage
    private RegistrantList mDataRoamingOffRegistrants = new RegistrantList();
    protected SparseArray<RegistrantList> mAttachedRegistrants = new SparseArray<>();
    protected SparseArray<RegistrantList> mDetachedRegistrants = new SparseArray();
    private RegistrantList mVoiceRegStateOrRatChangedRegistrants = new RegistrantList();
    private SparseArray<RegistrantList> mDataRegStateOrRatChangedRegistrants = new SparseArray<>();
    @UnsupportedAppUsage
    private RegistrantList mNetworkAttachedRegistrants = new RegistrantList();
    private RegistrantList mNetworkDetachedRegistrants = new RegistrantList();
    private RegistrantList mPsRestrictEnabledRegistrants = new RegistrantList();
    private RegistrantList mPsRestrictDisabledRegistrants = new RegistrantList();
    private RegistrantList mImsCapabilityChangedRegistrants = new RegistrantList();

    /* Radio power off pending flag and tag counter */
    private boolean mPendingRadioPowerOffAfterDataOff = false;
    private int mPendingRadioPowerOffAfterDataOffTag = 0;

    /** Signal strength poll rate. */
    private static final int POLL_PERIOD_MILLIS = 20 * 1000;

    /** Waiting period before recheck gprs and voice registration. */
    public static final int DEFAULT_GPRS_CHECK_PERIOD_MILLIS = 60 * 1000;

    /** GSM events */
    protected static final int EVENT_RADIO_STATE_CHANGED                    = 1;
    protected static final int EVENT_NETWORK_STATE_CHANGED                  = 2;
    protected static final int EVENT_GET_SIGNAL_STRENGTH                    = 3;
    protected static final int EVENT_POLL_STATE_CS_CELLULAR_REGISTRATION    = 4;
    protected static final int EVENT_POLL_STATE_PS_CELLULAR_REGISTRATION    = 5;
    protected static final int EVENT_POLL_STATE_PS_IWLAN_REGISTRATION       = 6;
    protected static final int EVENT_POLL_STATE_OPERATOR                    = 7;
    protected static final int EVENT_POLL_SIGNAL_STRENGTH                   = 10;
    protected static final int EVENT_NITZ_TIME                              = 11;
    protected static final int EVENT_SIGNAL_STRENGTH_UPDATE                 = 12;
    protected static final int EVENT_POLL_STATE_NETWORK_SELECTION_MODE      = 14;
    protected static final int EVENT_GET_LOC_DONE                           = 15;
    protected static final int EVENT_SIM_RECORDS_LOADED                     = 16;
    protected static final int EVENT_SIM_READY                              = 17;
    protected static final int EVENT_LOCATION_UPDATES_ENABLED               = 18;
    protected static final int EVENT_GET_PREFERRED_NETWORK_TYPE             = 19;
    protected static final int EVENT_SET_PREFERRED_NETWORK_TYPE             = 20;
    protected static final int EVENT_RESET_PREFERRED_NETWORK_TYPE           = 21;
    protected static final int EVENT_CHECK_REPORT_GPRS                      = 22;
    protected static final int EVENT_RESTRICTED_STATE_CHANGED               = 23;

    /** CDMA events */
    protected static final int EVENT_RUIM_READY                        = 26;
    protected static final int EVENT_RUIM_RECORDS_LOADED               = 27;
    protected static final int EVENT_POLL_STATE_CDMA_SUBSCRIPTION      = 34;
    protected static final int EVENT_NV_READY                          = 35;
    protected static final int EVENT_OTA_PROVISION_STATUS_CHANGE       = 37;
    protected static final int EVENT_SET_RADIO_POWER_OFF               = 38;
    protected static final int EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED  = 39;
    protected static final int EVENT_CDMA_PRL_VERSION_CHANGED          = 40;

    protected static final int EVENT_RADIO_ON                          = 41;
    public    static final int EVENT_ICC_CHANGED                       = 42;
    protected static final int EVENT_GET_CELL_INFO_LIST                = 43;
    protected static final int EVENT_UNSOL_CELL_INFO_LIST              = 44;
    protected static final int EVENT_CHANGE_IMS_STATE                  = 45;
    protected static final int EVENT_IMS_STATE_CHANGED                 = 46;
    protected static final int EVENT_IMS_STATE_DONE                    = 47;
    protected static final int EVENT_IMS_CAPABILITY_CHANGED            = 48;
    protected static final int EVENT_ALL_DATA_DISCONNECTED             = 49;
    protected static final int EVENT_PHONE_TYPE_SWITCHED               = 50;
    protected static final int EVENT_RADIO_POWER_FROM_CARRIER          = 51;
    protected static final int EVENT_IMS_SERVICE_STATE_CHANGED         = 53;
    protected static final int EVENT_RADIO_POWER_OFF_DONE              = 54;
    protected static final int EVENT_PHYSICAL_CHANNEL_CONFIG           = 55;
    protected static final int EVENT_CELL_LOCATION_RESPONSE            = 56;
    protected static final int EVENT_CARRIER_CONFIG_CHANGED            = 57;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"CARRIER_NAME_DISPLAY_BITMASK"},
            value = {CARRIER_NAME_DISPLAY_BITMASK_SHOW_PLMN,
                    CARRIER_NAME_DISPLAY_BITMASK_SHOW_SPN},
            flag = true)
    public @interface CarrierNameDisplayBitmask {}

    // Show SPN only and only if this bit is set.
    public static final int CARRIER_NAME_DISPLAY_BITMASK_SHOW_SPN = 1 << 0;

    // Show PLMN only and only if this bit is set.
    public static final int CARRIER_NAME_DISPLAY_BITMASK_SHOW_PLMN = 1 << 1;

    private List<Message> mPendingCellInfoRequests = new LinkedList<Message>();
    // @GuardedBy("mPendingCellInfoRequests")
    private boolean mIsPendingCellInfoRequest = false;

    /** Reason for registration denial. */
    protected static final String REGISTRATION_DENIED_GEN  = "General";
    protected static final String REGISTRATION_DENIED_AUTH = "Authentication Failure";

    private CarrierDisplayNameResolver mCdnr;

    private boolean mImsRegistrationOnOff = false;
    private boolean mAlarmSwitch = false;
    /** Radio is disabled by carrier. Radio power will not be override if this field is set */
    private boolean mRadioDisabledByCarrier = false;
    private PendingIntent mRadioOffIntent = null;
    private static final String ACTION_RADIO_OFF = "android.intent.action.ACTION_RADIO_OFF";
    private boolean mPowerOffDelayNeed = true;
    @UnsupportedAppUsage
    private boolean mDeviceShuttingDown = false;
    /** Keep track of SPN display rules, so we only broadcast intent if something changes. */
    @UnsupportedAppUsage
    private boolean mSpnUpdatePending = false;
    @UnsupportedAppUsage
    private String mCurSpn = null;
    @UnsupportedAppUsage
    private String mCurDataSpn = null;
    @UnsupportedAppUsage
    private String mCurPlmn = null;
    @UnsupportedAppUsage
    private boolean mCurShowPlmn = false;
    @UnsupportedAppUsage
    private boolean mCurShowSpn = false;
    @UnsupportedAppUsage
    @VisibleForTesting
    public int mSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private int mPrevSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    private boolean mImsRegistered = false;

    @UnsupportedAppUsage
    private SubscriptionManager mSubscriptionManager;
    @UnsupportedAppUsage
    private SubscriptionController mSubscriptionController;
    @UnsupportedAppUsage
    private final SstSubscriptionsChangedListener mOnSubscriptionsChangedListener =
        new SstSubscriptionsChangedListener();


    private final RatRatcheter mRatRatcheter;

    private final LocaleTracker mLocaleTracker;

    private final LocalLog mRoamingLog = new LocalLog(10);
    private final LocalLog mAttachLog = new LocalLog(10);
    private final LocalLog mPhoneTypeLog = new LocalLog(10);
    private final LocalLog mRatLog = new LocalLog(20);
    private final LocalLog mRadioPowerLog = new LocalLog(20);
    private final LocalLog mCdnrLogs = new LocalLog(64);

    private Pattern mOperatorNameStringPattern;

    private class SstSubscriptionsChangedListener extends OnSubscriptionsChangedListener {
        public final AtomicInteger mPreviousSubId =
                new AtomicInteger(SubscriptionManager.INVALID_SUBSCRIPTION_ID);

        /**
         * Callback invoked when there is any change to any SubscriptionInfo. Typically
         * this method would invoke {@link SubscriptionManager#getActiveSubscriptionInfoList}
         */
        @Override
        public void onSubscriptionsChanged() {
            if (DBG) log("SubscriptionListener.onSubscriptionInfoChanged");
            // Set the network type, in case the radio does not restore it.
            int subId = mPhone.getSubId();
            ServiceStateTracker.this.mPrevSubId = mPreviousSubId.get();
            if (mPreviousSubId.getAndSet(subId) != subId) {
                if (SubscriptionManager.isValidSubscriptionId(subId)) {
                    Context context = mPhone.getContext();

                    mPhone.notifyPhoneStateChanged();
                    mPhone.notifyCallForwardingIndicator();

                    boolean restoreSelection = !context.getResources().getBoolean(
                            com.android.internal.R.bool.skip_restoring_network_selection);
                    mPhone.sendSubscriptionSettings(restoreSelection);

                    mPhone.setSystemProperty(TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE,
                            ServiceState.rilRadioTechnologyToString(
                                    mSS.getRilDataRadioTechnology()));

                    if (mSpnUpdatePending) {
                        mSubscriptionController.setPlmnSpn(mPhone.getPhoneId(), mCurShowPlmn,
                                mCurPlmn, mCurShowSpn, mCurSpn);
                        mSpnUpdatePending = false;
                    }

                    // Remove old network selection sharedPreferences since SP key names are now
                    // changed to include subId. This will be done only once when upgrading from an
                    // older build that did not include subId in the names.
                    SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                            context);
                    String oldNetworkSelection = sp.getString(
                            Phone.NETWORK_SELECTION_KEY, "");
                    String oldNetworkSelectionName = sp.getString(
                            Phone.NETWORK_SELECTION_NAME_KEY, "");
                    String oldNetworkSelectionShort = sp.getString(
                            Phone.NETWORK_SELECTION_SHORT_KEY, "");
                    if (!TextUtils.isEmpty(oldNetworkSelection) ||
                            !TextUtils.isEmpty(oldNetworkSelectionName) ||
                            !TextUtils.isEmpty(oldNetworkSelectionShort)) {
                        SharedPreferences.Editor editor = sp.edit();
                        editor.putString(Phone.NETWORK_SELECTION_KEY + subId,
                                oldNetworkSelection);
                        editor.putString(Phone.NETWORK_SELECTION_NAME_KEY + subId,
                                oldNetworkSelectionName);
                        editor.putString(Phone.NETWORK_SELECTION_SHORT_KEY + subId,
                                oldNetworkSelectionShort);
                        editor.remove(Phone.NETWORK_SELECTION_KEY);
                        editor.remove(Phone.NETWORK_SELECTION_NAME_KEY);
                        editor.remove(Phone.NETWORK_SELECTION_SHORT_KEY);
                        editor.commit();
                    }

                    // Once sub id becomes valid, we need to update the service provider name
                    // displayed on the UI again. The old SPN update intents sent to
                    // MobileSignalController earlier were actually ignored due to invalid sub id.
                    updateSpnDisplay();
                }
                // update voicemail count and notify message waiting changed
                mPhone.updateVoiceMail();
            }
        }
    };

    //Common
    @UnsupportedAppUsage
    private final GsmCdmaPhone mPhone;

    private CellIdentity mCellIdentity;
    private CellIdentity mNewCellIdentity;
    private static final int MS_PER_HOUR = 60 * 60 * 1000;
    private final NitzStateMachine mNitzState;
    private final EriManager mEriManager;
    @UnsupportedAppUsage
    private final ContentResolver mCr;

    //GSM
    @UnsupportedAppUsage
    private int mPreferredNetworkType;
    @UnsupportedAppUsage
    private int mMaxDataCalls = 1;
    @UnsupportedAppUsage
    private int mNewMaxDataCalls = 1;
    @UnsupportedAppUsage
    private int mReasonDataDenied = -1;
    @UnsupportedAppUsage
    private int mNewReasonDataDenied = -1;

    /**
     * The code of the rejection cause that is sent by network when the CS
     * registration is rejected. It should be shown to the user as a notification.
     */
    private int mRejectCode;
    private int mNewRejectCode;

    /**
     * GSM roaming status solely based on TS 27.007 7.2 CREG. Only used by
     * handlePollStateResult to store CREG roaming result.
     */
    private boolean mGsmRoaming = false;
    /**
     * Data roaming status solely based on TS 27.007 10.1.19 CGREG. Only used by
     * handlePollStateResult to store CGREG roaming result.
     */
    private boolean mDataRoaming = false;
    /**
     * Mark when service state is in emergency call only mode
     */
    @UnsupportedAppUsage
    private boolean mEmergencyOnly = false;
    /** Started the recheck process after finding gprs should registered but not. */
    @UnsupportedAppUsage
    private boolean mStartedGprsRegCheck;
    /** Already sent the event-log for no gprs register. */
    @UnsupportedAppUsage
    private boolean mReportedGprsNoReg;

    private CarrierServiceStateTracker mCSST;
    /**
     * The Notification object given to the NotificationManager.
     */
    private Notification mNotification;
    /** Notification type. */
    public static final int PS_ENABLED = 1001;            // Access Control blocks data service
    public static final int PS_DISABLED = 1002;           // Access Control enables data service
    public static final int CS_ENABLED = 1003;            // Access Control blocks all voice/sms service
    public static final int CS_DISABLED = 1004;           // Access Control enables all voice/sms service
    public static final int CS_NORMAL_ENABLED = 1005;     // Access Control blocks normal voice/sms service
    public static final int CS_EMERGENCY_ENABLED = 1006;  // Access Control blocks emergency call service
    public static final int CS_REJECT_CAUSE_ENABLED = 2001;     // Notify MM rejection cause
    public static final int CS_REJECT_CAUSE_DISABLED = 2002;    // Cancel MM rejection cause
    /** Notification id. */
    public static final int PS_NOTIFICATION = 888;  // Id to update and cancel PS restricted
    public static final int CS_NOTIFICATION = 999;  // Id to update and cancel CS restricted
    public static final int CS_REJECT_CAUSE_NOTIFICATION = 111; // Id to update and cancel MM
                                                                // rejection cause

    /** To identify whether EVENT_SIM_READY is received or not */
    private boolean mIsSimReady = false;

    @UnsupportedAppUsage
    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED)) {
                int phoneId = intent.getExtras().getInt(CarrierConfigManager.EXTRA_SLOT_INDEX);
                // Ignore the carrier config changed if the phoneId is not matched.
                if (phoneId == mPhone.getPhoneId()) {
                    sendEmptyMessage(EVENT_CARRIER_CONFIG_CHANGED);
                }
                return;
            }

            // TODO: Remove this weird check left over from CDMA/GSM service state tracker merge.
            if (!mPhone.isPhoneTypeGsm()) {
                loge("Ignoring intent " + intent + " received on CDMA phone");
                return;
            }

            if (intent.getAction().equals(Intent.ACTION_LOCALE_CHANGED)) {
                // update emergency string whenever locale changed
                updateSpnDisplay();
            } else if (intent.getAction().equals(ACTION_RADIO_OFF)) {
                mAlarmSwitch = false;
                powerOffRadioSafely();
            }
        }
    };

    //CDMA
    // Min values used to by getOtasp()
    public static final String UNACTIVATED_MIN2_VALUE = "000000";
    public static final String UNACTIVATED_MIN_VALUE = "1111110111";
    // Current Otasp value
    private int mCurrentOtaspMode = TelephonyManager.OTASP_UNINITIALIZED;
    @UnsupportedAppUsage
    private int mRoamingIndicator;
    private boolean mIsInPrl;
    @UnsupportedAppUsage
    private int mDefaultRoamingIndicator;
    /**
     * Initially assume no data connection.
     */
    private int mRegistrationState = -1;
    private RegistrantList mCdmaForSubscriptionInfoReadyRegistrants = new RegistrantList();
    private String mMdn;
    private int mHomeSystemId[] = null;
    private int mHomeNetworkId[] = null;
    private String mMin;
    private String mPrlVersion;
    private boolean mIsMinInfoReady = false;
    private boolean mIsEriTextLoaded = false;
    @UnsupportedAppUsage
    private boolean mIsSubscriptionFromRuim = false;
    private CdmaSubscriptionSourceManager mCdmaSSM;
    public static final String INVALID_MCC = "000";
    public static final String DEFAULT_MNC = "00";
    private HbpcdUtils mHbpcdUtils = null;
    /* Used only for debugging purposes. */
    private String mRegistrationDeniedReason;
    private String mCurrentCarrier = null;

    private final TransportManager mTransportManager;
    private final SparseArray<NetworkRegistrationManager> mRegStateManagers = new SparseArray<>();

    /* list of LTE EARFCNs (E-UTRA Absolute Radio Frequency Channel Number,
     * Reference: 3GPP TS 36.104 5.4.3)
     * inclusive ranges for which the lte rsrp boost is applied */
    private ArrayList<Pair<Integer, Integer>> mEarfcnPairListForRsrpBoost = null;

    private int mLteRsrpBoost = 0; // offset which is reduced from the rsrp threshold
                                   // while calculating signal strength level.
    private final Object mLteRsrpBoostLock = new Object();
    private static final int INVALID_LTE_EARFCN = -1;

    public ServiceStateTracker(GsmCdmaPhone phone, CommandsInterface ci) {
        mNitzState = TelephonyComponentFactory.getInstance()
                .inject(NitzStateMachine.class.getName())
                .makeNitzStateMachine(phone);
        mPhone = phone;
        mCi = ci;

        mCdnr = new CarrierDisplayNameResolver(mPhone);

        mEriManager = TelephonyComponentFactory.getInstance().inject(EriManager.class.getName())
                .makeEriManager(mPhone, EriManager.ERI_FROM_XML);

        mRatRatcheter = new RatRatcheter(mPhone);
        mVoiceCapable = mPhone.getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_voice_capable);
        mUiccController = UiccController.getInstance();

        mUiccController.registerForIccChanged(this, EVENT_ICC_CHANGED, null);
        mCi.setOnSignalStrengthUpdate(this, EVENT_SIGNAL_STRENGTH_UPDATE, null);
        mCi.registerForCellInfoList(this, EVENT_UNSOL_CELL_INFO_LIST, null);
        mCi.registerForPhysicalChannelConfiguration(this, EVENT_PHYSICAL_CHANNEL_CONFIG, null);

        mSubscriptionController = SubscriptionController.getInstance();
        mSubscriptionManager = SubscriptionManager.from(phone.getContext());
        mSubscriptionManager
                .addOnSubscriptionsChangedListener(mOnSubscriptionsChangedListener);
        mRestrictedState = new RestrictedState();

        mTransportManager = mPhone.getTransportManager();

        for (int transportType : mTransportManager.getAvailableTransports()) {
            mRegStateManagers.append(transportType, new NetworkRegistrationManager(
                    transportType, phone));
            mRegStateManagers.get(transportType).registerForNetworkRegistrationInfoChanged(
                    this, EVENT_NETWORK_STATE_CHANGED, null);
        }
        mLocaleTracker = TelephonyComponentFactory.getInstance()
                .inject(LocaleTracker.class.getName())
                .makeLocaleTracker(mPhone, mNitzState, getLooper());

        mCi.registerForImsNetworkStateChanged(this, EVENT_IMS_STATE_CHANGED, null);
        mCi.registerForRadioStateChanged(this, EVENT_RADIO_STATE_CHANGED, null);
        mCi.setOnNITZTime(this, EVENT_NITZ_TIME, null);

        mCr = phone.getContext().getContentResolver();
        // system setting property AIRPLANE_MODE_ON is set in Settings.
        int airplaneMode = Settings.Global.getInt(mCr, Settings.Global.AIRPLANE_MODE_ON, 0);
        int enableCellularOnBoot = Settings.Global.getInt(mCr,
                Settings.Global.ENABLE_CELLULAR_ON_BOOT, 1);
        mDesiredPowerState = (enableCellularOnBoot > 0) && ! (airplaneMode > 0);
        mRadioPowerLog.log("init : airplane mode = " + airplaneMode + " enableCellularOnBoot = " +
                enableCellularOnBoot);


        setSignalStrengthDefaultValues();
        mPhone.getCarrierActionAgent().registerForCarrierAction(CARRIER_ACTION_SET_RADIO_ENABLED,
                this, EVENT_RADIO_POWER_FROM_CARRIER, null, false);

        // Monitor locale change
        Context context = mPhone.getContext();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        context.registerReceiver(mIntentReceiver, filter);
        filter = new IntentFilter();
        filter.addAction(ACTION_RADIO_OFF);
        context.registerReceiver(mIntentReceiver, filter);
        filter = new IntentFilter();
        filter.addAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        context.registerReceiver(mIntentReceiver, filter);

        mPhone.notifyOtaspChanged(TelephonyManager.OTASP_UNINITIALIZED);

        mCi.setOnRestrictedStateChanged(this, EVENT_RESTRICTED_STATE_CHANGED, null);
        updatePhoneType();

        mCSST = new CarrierServiceStateTracker(phone, this);

        registerForNetworkAttached(mCSST,
                CarrierServiceStateTracker.CARRIER_EVENT_VOICE_REGISTRATION, null);
        registerForNetworkDetached(mCSST,
                CarrierServiceStateTracker.CARRIER_EVENT_VOICE_DEREGISTRATION, null);
        registerForDataConnectionAttached(AccessNetworkConstants.TRANSPORT_TYPE_WWAN, mCSST,
                CarrierServiceStateTracker.CARRIER_EVENT_DATA_REGISTRATION, null);
        registerForDataConnectionDetached(AccessNetworkConstants.TRANSPORT_TYPE_WWAN, mCSST,
                CarrierServiceStateTracker.CARRIER_EVENT_DATA_DEREGISTRATION, null);
        registerForImsCapabilityChanged(mCSST,
                CarrierServiceStateTracker.CARRIER_EVENT_IMS_CAPABILITIES_CHANGED, null);
    }

    @VisibleForTesting
    public void updatePhoneType() {

        // If we are previously voice roaming, we need to notify that roaming status changed before
        // we change back to non-roaming.
        if (mSS != null && mSS.getVoiceRoaming()) {
            mVoiceRoamingOffRegistrants.notifyRegistrants();
        }

        // If we are previously data roaming, we need to notify that roaming status changed before
        // we change back to non-roaming.
        if (mSS != null && mSS.getDataRoaming()) {
            mDataRoamingOffRegistrants.notifyRegistrants();
        }

        // If we are previously in service, we need to notify that we are out of service now.
        if (mSS != null && mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE) {
            mNetworkDetachedRegistrants.notifyRegistrants();
        }

        // If we are previously in service, we need to notify that we are out of service now.
        for (int transport : mTransportManager.getAvailableTransports()) {
            if (mSS != null) {
                NetworkRegistrationInfo nrs = mSS.getNetworkRegistrationInfo(
                        NetworkRegistrationInfo.DOMAIN_PS, transport);
                if (nrs != null && nrs.isInService()
                        && mDetachedRegistrants.get(transport) != null) {
                    mDetachedRegistrants.get(transport).notifyRegistrants();
                }
            }
        }

        mSS = new ServiceState();
        mSS.setStateOutOfService();
        mNewSS = new ServiceState();
        mNewSS.setStateOutOfService();
        mLastCellInfoReqTime = 0;
        mLastCellInfoList = null;
        mSignalStrength = new SignalStrength();
        mStartedGprsRegCheck = false;
        mReportedGprsNoReg = false;
        mMdn = null;
        mMin = null;
        mPrlVersion = null;
        mIsMinInfoReady = false;
        mNitzState.handleNetworkCountryCodeUnavailable();
        mCellIdentity = null;
        mNewCellIdentity = null;

        //cancel any pending pollstate request on voice tech switching
        cancelPollState();

        if (mPhone.isPhoneTypeGsm()) {
            //clear CDMA registrations first
            if (mCdmaSSM != null) {
                mCdmaSSM.dispose(this);
            }

            mCi.unregisterForCdmaPrlChanged(this);
            mCi.unregisterForCdmaOtaProvision(this);
            mPhone.unregisterForSimRecordsLoaded(this);

        } else {
            mPhone.registerForSimRecordsLoaded(this, EVENT_SIM_RECORDS_LOADED, null);
            mCdmaSSM = CdmaSubscriptionSourceManager.getInstance(mPhone.getContext(), mCi, this,
                    EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED, null);
            mIsSubscriptionFromRuim = (mCdmaSSM.getCdmaSubscriptionSource() ==
                    CdmaSubscriptionSourceManager.SUBSCRIPTION_FROM_RUIM);

            mCi.registerForCdmaPrlChanged(this, EVENT_CDMA_PRL_VERSION_CHANGED, null);
            mCi.registerForCdmaOtaProvision(this, EVENT_OTA_PROVISION_STATUS_CHANGE, null);

            mHbpcdUtils = new HbpcdUtils(mPhone.getContext());
            // update OTASP state in case previously set by another service
            updateOtaspState();
        }

        // This should be done after the technology specific initializations above since it relies
        // on fields like mIsSubscriptionFromRuim (which is updated above)
        onUpdateIccAvailability();

        mPhone.setSystemProperty(TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE,
                ServiceState.rilRadioTechnologyToString(ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN));
        // Query signal strength from the modem after service tracker is created (i.e. boot up,
        // switching between GSM and CDMA phone), because the unsolicited signal strength
        // information might come late or even never come. This will get the accurate signal
        // strength information displayed on the UI.
        mCi.getSignalStrength(obtainMessage(EVENT_GET_SIGNAL_STRENGTH));
        sendMessage(obtainMessage(EVENT_PHONE_TYPE_SWITCHED));

        logPhoneTypeChange();

        // Tell everybody that the registration state and RAT have changed.
        notifyVoiceRegStateRilRadioTechnologyChanged();
        for (int transport : mTransportManager.getAvailableTransports()) {
            notifyDataRegStateRilRadioTechnologyChanged(transport);
        }
    }

    @VisibleForTesting
    public void requestShutdown() {
        if (mDeviceShuttingDown == true) return;
        mDeviceShuttingDown = true;
        mDesiredPowerState = false;
        setPowerStateToDesired();
    }

    public void dispose() {
        mCi.unSetOnSignalStrengthUpdate(this);
        mUiccController.unregisterForIccChanged(this);
        mCi.unregisterForCellInfoList(this);
        mCi.unregisterForPhysicalChannelConfiguration(this);
        mSubscriptionManager
            .removeOnSubscriptionsChangedListener(mOnSubscriptionsChangedListener);
        mCi.unregisterForImsNetworkStateChanged(this);
        mPhone.getCarrierActionAgent().unregisterForCarrierAction(this,
                CARRIER_ACTION_SET_RADIO_ENABLED);
        if (mCSST != null) {
            mCSST.dispose();
            mCSST = null;
        }
    }

    @UnsupportedAppUsage
    public boolean getDesiredPowerState() {
        return mDesiredPowerState;
    }
    public boolean getPowerStateFromCarrier() { return !mRadioDisabledByCarrier; }

    private SignalStrength mLastSignalStrength = null;
    @UnsupportedAppUsage
    protected boolean notifySignalStrength() {
        boolean notified = false;
        if (!mSignalStrength.equals(mLastSignalStrength)) {
            try {
                mPhone.notifySignalStrength();
                notified = true;
                mLastSignalStrength = mSignalStrength;
            } catch (NullPointerException ex) {
                loge("updateSignalStrength() Phone already destroyed: " + ex
                        + "SignalStrength not notified");
            }
        }
        return notified;
    }

    /**
     * Notify all mVoiceRegStateOrRatChangedRegistrants using an
     * AsyncResult in msg.obj where AsyncResult#result contains the
     * new RAT as an Integer Object.
     */
    protected void notifyVoiceRegStateRilRadioTechnologyChanged() {
        int rat = mSS.getRilVoiceRadioTechnology();
        int vrs = mSS.getVoiceRegState();
        if (DBG) log("notifyVoiceRegStateRilRadioTechnologyChanged: vrs=" + vrs + " rat=" + rat);

        mVoiceRegStateOrRatChangedRegistrants.notifyResult(new Pair<Integer, Integer>(vrs, rat));
    }

    /**
     * Notify all mDataConnectionRatChangeRegistrants using an
     * AsyncResult in msg.obj where AsyncResult#result contains the
     * new RAT as an Integer Object.
     */
    protected void notifyDataRegStateRilRadioTechnologyChanged(int transport) {
        NetworkRegistrationInfo nrs = mSS.getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_PS, transport);
        if (nrs != null) {
            int rat = ServiceState.networkTypeToRilRadioTechnology(
                    nrs.getAccessNetworkTechnology());
            int drs = regCodeToServiceState(nrs.getRegistrationState());
            if (DBG) {
                log("notifyDataRegStateRilRadioTechnologyChanged: drs=" + drs + " rat=" + rat);
            }

            RegistrantList registrantList = mDataRegStateOrRatChangedRegistrants.get(transport);
            if (registrantList != null) {
                registrantList.notifyResult(new Pair<>(drs, rat));
            }
        }
        mPhone.setSystemProperty(TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE,
                ServiceState.rilRadioTechnologyToString(mSS.getRilDataRadioTechnology()));
    }

    /**
     * Some operators have been known to report registration failure
     * data only devices, to fix that use DataRegState.
     */
    @UnsupportedAppUsage
    protected void useDataRegStateForDataOnlyDevices() {
        if (mVoiceCapable == false) {
            if (DBG) {
                log("useDataRegStateForDataOnlyDevice: VoiceRegState=" + mNewSS.getVoiceRegState()
                    + " DataRegState=" + mNewSS.getDataRegState());
            }
            // TODO: Consider not lying and instead have callers know the difference.
            mNewSS.setVoiceRegState(mNewSS.getDataRegState());
        }
    }

    @UnsupportedAppUsage
    protected void updatePhoneObject() {
        if (mPhone.getContext().getResources().
                getBoolean(com.android.internal.R.bool.config_switch_phone_on_voice_reg_state_change)) {
            // If the phone is not registered on a network, no need to update.
            boolean isRegistered = mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE ||
                    mSS.getVoiceRegState() == ServiceState.STATE_EMERGENCY_ONLY;
            if (!isRegistered) {
                log("updatePhoneObject: Ignore update");
                return;
            }
            mPhone.updatePhoneObject(mSS.getRilVoiceRadioTechnology());
        }
    }

    /**
     * Registration point for combined roaming on of mobile voice
     * combined roaming is true when roaming is true and ONS differs SPN
     *
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForVoiceRoamingOn(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mVoiceRoamingOnRegistrants.add(r);

        if (mSS.getVoiceRoaming()) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForVoiceRoamingOn(Handler h) {
        mVoiceRoamingOnRegistrants.remove(h);
    }

    /**
     * Registration point for roaming off of mobile voice
     * combined roaming is true when roaming is true and ONS differs SPN
     *
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForVoiceRoamingOff(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mVoiceRoamingOffRegistrants.add(r);

        if (!mSS.getVoiceRoaming()) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForVoiceRoamingOff(Handler h) {
        mVoiceRoamingOffRegistrants.remove(h);
    }

    /**
     * Registration point for combined roaming on of mobile data
     * combined roaming is true when roaming is true and ONS differs SPN
     *
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForDataRoamingOn(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mDataRoamingOnRegistrants.add(r);

        if (mSS.getDataRoaming()) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForDataRoamingOn(Handler h) {
        mDataRoamingOnRegistrants.remove(h);
    }

    /**
     * Registration point for roaming off of mobile data
     * combined roaming is true when roaming is true and ONS differs SPN
     *
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     * @param notifyNow notify upon registration if data roaming is off
     */
    public void registerForDataRoamingOff(Handler h, int what, Object obj, boolean notifyNow) {
        Registrant r = new Registrant(h, what, obj);
        mDataRoamingOffRegistrants.add(r);

        if (notifyNow && !mSS.getDataRoaming()) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForDataRoamingOff(Handler h) {
        mDataRoamingOffRegistrants.remove(h);
    }

    /**
     * Re-register network by toggling preferred network type.
     * This is a work-around to deregister and register network since there is
     * no ril api to set COPS=2 (deregister) only.
     *
     * @param onComplete is dispatched when this is complete.  it will be
     * an AsyncResult, and onComplete.obj.exception will be non-null
     * on failure.
     */
    @UnsupportedAppUsage
    public void reRegisterNetwork(Message onComplete) {
        mCi.getPreferredNetworkType(
                obtainMessage(EVENT_GET_PREFERRED_NETWORK_TYPE, onComplete));
    }

    public void
    setRadioPower(boolean power) {
        mDesiredPowerState = power;

        setPowerStateToDesired();
    }

    /**
     * Radio power set from carrier action. if set to false means carrier desire to turn radio off
     * and radio wont be re-enabled unless carrier explicitly turn it back on.
     * @param enable indicate if radio power is enabled or disabled from carrier action.
     */
    public void setRadioPowerFromCarrier(boolean enable) {
        mRadioDisabledByCarrier = !enable;
        setPowerStateToDesired();
    }

    /**
     * These two flags manage the behavior of the cell lock -- the
     * lock should be held if either flag is true.  The intention is
     * to allow temporary acquisition of the lock to get a single
     * update.  Such a lock grab and release can thus be made to not
     * interfere with more permanent lock holds -- in other words, the
     * lock will only be released if both flags are false, and so
     * releases by temporary users will only affect the lock state if
     * there is no continuous user.
     */
    private boolean mWantContinuousLocationUpdates;
    private boolean mWantSingleLocationUpdate;

    public void enableSingleLocationUpdate() {
        if (mWantSingleLocationUpdate || mWantContinuousLocationUpdates) return;
        mWantSingleLocationUpdate = true;
        mCi.setLocationUpdates(true, obtainMessage(EVENT_LOCATION_UPDATES_ENABLED));
    }

    public void enableLocationUpdates() {
        if (mWantSingleLocationUpdate || mWantContinuousLocationUpdates) return;
        mWantContinuousLocationUpdates = true;
        mCi.setLocationUpdates(true, obtainMessage(EVENT_LOCATION_UPDATES_ENABLED));
    }

    protected void disableSingleLocationUpdate() {
        mWantSingleLocationUpdate = false;
        if (!mWantSingleLocationUpdate && !mWantContinuousLocationUpdates) {
            mCi.setLocationUpdates(false, null);
        }
    }

    public void disableLocationUpdates() {
        mWantContinuousLocationUpdates = false;
        if (!mWantSingleLocationUpdate && !mWantContinuousLocationUpdates) {
            mCi.setLocationUpdates(false, null);
        }
    }

    private int getLteEarfcn(CellIdentity cellIdentity) {
        int lteEarfcn = INVALID_LTE_EARFCN;
        if (cellIdentity != null) {
            switch (cellIdentity.getType()) {
                case CellInfoType.LTE: {
                    lteEarfcn = ((CellIdentityLte) cellIdentity).getEarfcn();
                    break;
                }
                default: {
                    break;
                }
            }
        }

        return lteEarfcn;
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        int[] ints;
        Message message;

        if (VDBG) log("received event " + msg.what);
        switch (msg.what) {
            case EVENT_SET_RADIO_POWER_OFF:
                synchronized(this) {
                    if (mPendingRadioPowerOffAfterDataOff &&
                            (msg.arg1 == mPendingRadioPowerOffAfterDataOffTag)) {
                        if (DBG) log("EVENT_SET_RADIO_OFF, turn radio off now.");
                        hangupAndPowerOff();
                        mPendingRadioPowerOffAfterDataOffTag += 1;
                        mPendingRadioPowerOffAfterDataOff = false;
                    } else {
                        log("EVENT_SET_RADIO_OFF is stale arg1=" + msg.arg1 +
                                "!= tag=" + mPendingRadioPowerOffAfterDataOffTag);
                    }
                }
                break;

            case EVENT_ICC_CHANGED:
                if (isSimAbsent()) {
                    if (DBG) log("EVENT_ICC_CHANGED: SIM absent");
                    // cancel notifications if SIM is removed/absent
                    cancelAllNotifications();
                    // clear cached values on SIM removal
                    mMdn = null;
                    mMin = null;
                    mIsMinInfoReady = false;

                    // Remove the EF records that come from UICC.
                    mCdnr.updateEfFromRuim(null /* ruim */);
                    mCdnr.updateEfFromUsim(null /* Usim */);
                }
                onUpdateIccAvailability();
                if (mUiccApplcation != null
                        && mUiccApplcation.getState() != AppState.APPSTATE_READY) {
                    mIsSimReady = false;
                    updateSpnDisplay();
                }
                break;

            case EVENT_GET_CELL_INFO_LIST: // fallthrough
            case EVENT_UNSOL_CELL_INFO_LIST: {
                List<CellInfo> cellInfo = null;
                Throwable ex = null;
                if (msg.obj != null) {
                    ar = (AsyncResult) msg.obj;
                    if (ar.exception != null) {
                        log("EVENT_GET_CELL_INFO_LIST: error ret null, e=" + ar.exception);
                        ex = ar.exception;
                    } else if (ar.result == null) {
                        loge("Invalid CellInfo result");
                    } else {
                        cellInfo = (List<CellInfo>) ar.result;
                        updateOperatorNameForCellInfo(cellInfo);
                        mLastCellInfoList = cellInfo;
                        mPhone.notifyCellInfo(cellInfo);
                        if (VDBG) {
                            log("CELL_INFO_LIST: size=" + cellInfo.size() + " list=" + cellInfo);
                        }
                    }
                } else {
                    // If we receive an empty message, it's probably a timeout; if there is no
                    // pending request, drop it.
                    if (!mIsPendingCellInfoRequest) break;
                    // If there is a request pending, we still need to check whether it's a timeout
                    // for the current request of whether it's leftover from a previous request.
                    final long curTime = SystemClock.elapsedRealtime();
                    if ((curTime - mLastCellInfoReqTime) <  CELL_INFO_LIST_QUERY_TIMEOUT) {
                        break;
                    }
                    // We've received a legitimate timeout, so something has gone terribly wrong.
                    loge("Timeout waiting for CellInfo; (everybody panic)!");
                    mLastCellInfoList = null;
                    // Since the timeout is applicable, fall through and update all synchronous
                    // callers with the failure.
                }
                synchronized (mPendingCellInfoRequests) {
                    // If we have pending requests, then service them. Note that in case of a
                    // timeout, we send null responses back to the callers.
                    if (mIsPendingCellInfoRequest) {
                        // regardless of timeout or valid response, when something arrives,
                        mIsPendingCellInfoRequest = false;
                        for (Message m : mPendingCellInfoRequests) {
                            AsyncResult.forMessage(m, cellInfo, ex);
                            m.sendToTarget();
                        }
                        mPendingCellInfoRequests.clear();
                    }
                }
                break;
            }

            case  EVENT_IMS_STATE_CHANGED: // received unsol
                mCi.getImsRegistrationState(this.obtainMessage(EVENT_IMS_STATE_DONE));
                break;

            case EVENT_IMS_STATE_DONE:
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    int[] responseArray = (int[])ar.result;
                    mImsRegistered = (responseArray[0] == 1) ? true : false;
                }
                break;

            case EVENT_RADIO_POWER_OFF_DONE:
                if (DBG) log("EVENT_RADIO_POWER_OFF_DONE");
                if (mDeviceShuttingDown && mCi.getRadioState()
                        != TelephonyManager.RADIO_POWER_UNAVAILABLE) {
                    // during shutdown the modem may not send radio state changed event
                    // as a result of radio power request
                    // Hence, issuing shut down regardless of radio power response
                    mCi.requestShutdown(null);
                }
                break;

            // GSM
            case EVENT_SIM_READY:
                // Reset the mPreviousSubId so we treat a SIM power bounce
                // as a first boot.  See b/19194287
                mOnSubscriptionsChangedListener.mPreviousSubId.set(
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                mPrevSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
                mIsSimReady = true;
                pollState();
                // Signal strength polling stops when radio is off
                queueNextSignalStrengthPoll();
                break;

            case EVENT_RADIO_STATE_CHANGED:
            case EVENT_PHONE_TYPE_SWITCHED:
                if(!mPhone.isPhoneTypeGsm() &&
                        mCi.getRadioState() == TelephonyManager.RADIO_POWER_ON) {
                    handleCdmaSubscriptionSource(mCdmaSSM.getCdmaSubscriptionSource());

                    // Signal strength polling stops when radio is off.
                    queueNextSignalStrengthPoll();
                }
                // This will do nothing in the 'radio not available' case
                setPowerStateToDesired();
                // These events are modem triggered, so pollState() needs to be forced
                modemTriggeredPollState();
                break;

            case EVENT_NETWORK_STATE_CHANGED:
                modemTriggeredPollState();
                break;

            case EVENT_GET_SIGNAL_STRENGTH:
                // This callback is called when signal strength is polled
                // all by itself

                if (!(mCi.getRadioState() == TelephonyManager.RADIO_POWER_ON)) {
                    // Polling will continue when radio turns back on
                    return;
                }
                ar = (AsyncResult) msg.obj;
                onSignalStrengthResult(ar);
                queueNextSignalStrengthPoll();

                break;

            case EVENT_GET_LOC_DONE:
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    CellIdentity cellIdentity = ((NetworkRegistrationInfo) ar.result)
                            .getCellIdentity();
                    updateOperatorNameForCellIdentity(cellIdentity);
                    mCellIdentity = cellIdentity;
                    mPhone.notifyLocationChanged(getCellLocation());
                }

                // Release any temporary cell lock, which could have been
                // acquired to allow a single-shot location update.
                disableSingleLocationUpdate();
                break;

            case EVENT_POLL_STATE_CS_CELLULAR_REGISTRATION:
            case EVENT_POLL_STATE_PS_CELLULAR_REGISTRATION:
            case EVENT_POLL_STATE_PS_IWLAN_REGISTRATION:
            case EVENT_POLL_STATE_OPERATOR:
                ar = (AsyncResult) msg.obj;
                handlePollStateResult(msg.what, ar);
                break;

            case EVENT_POLL_STATE_NETWORK_SELECTION_MODE:
                if (DBG) log("EVENT_POLL_STATE_NETWORK_SELECTION_MODE");
                ar = (AsyncResult) msg.obj;
                if (mPhone.isPhoneTypeGsm()) {
                    handlePollStateResult(msg.what, ar);
                } else {
                    if (ar.exception == null && ar.result != null) {
                        ints = (int[])ar.result;
                        if (ints[0] == 1) {  // Manual selection.
                            mPhone.setNetworkSelectionModeAutomatic(null);
                        }
                    } else {
                        log("Unable to getNetworkSelectionMode");
                    }
                }
                break;

            case EVENT_POLL_SIGNAL_STRENGTH:
                // Just poll signal strength...not part of pollState()

                mCi.getSignalStrength(obtainMessage(EVENT_GET_SIGNAL_STRENGTH));
                break;

            case EVENT_NITZ_TIME:
                ar = (AsyncResult) msg.obj;

                String nitzString = (String)((Object[])ar.result)[0];
                long nitzReceiveTime = ((Long)((Object[])ar.result)[1]).longValue();

                setTimeFromNITZString(nitzString, nitzReceiveTime);
                break;

            case EVENT_SIGNAL_STRENGTH_UPDATE:
                // This is a notification from CommandsInterface.setOnSignalStrengthUpdate

                ar = (AsyncResult) msg.obj;

                // The radio is telling us about signal strength changes
                // we don't have to ask it
                mDontPollSignalStrength = true;

                onSignalStrengthResult(ar);
                break;

            case EVENT_SIM_RECORDS_LOADED:
                log("EVENT_SIM_RECORDS_LOADED: what=" + msg.what);
                updatePhoneObject();
                updateOtaspState();
                if (mPhone.isPhoneTypeGsm()) {
                    mCdnr.updateEfFromUsim((SIMRecords) mIccRecords);
                    updateSpnDisplay();
                }
                break;

            case EVENT_LOCATION_UPDATES_ENABLED:
                ar = (AsyncResult) msg.obj;

                if (ar.exception == null) {
                    mRegStateManagers.get(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                            .requestNetworkRegistrationInfo(NetworkRegistrationInfo.DOMAIN_CS,
                            obtainMessage(EVENT_GET_LOC_DONE, null));
                }
                break;

            case EVENT_SET_PREFERRED_NETWORK_TYPE:
                ar = (AsyncResult) msg.obj;
                // Don't care the result, only use for dereg network (COPS=2)
                message = obtainMessage(EVENT_RESET_PREFERRED_NETWORK_TYPE, ar.userObj);
                mCi.setPreferredNetworkType(mPreferredNetworkType, message);
                break;

            case EVENT_RESET_PREFERRED_NETWORK_TYPE:
                ar = (AsyncResult) msg.obj;
                if (ar.userObj != null) {
                    AsyncResult.forMessage(((Message) ar.userObj)).exception
                            = ar.exception;
                    ((Message) ar.userObj).sendToTarget();
                }
                break;

            case EVENT_GET_PREFERRED_NETWORK_TYPE:
                ar = (AsyncResult) msg.obj;

                if (ar.exception == null) {
                    mPreferredNetworkType = ((int[])ar.result)[0];
                } else {
                    mPreferredNetworkType = RILConstants.NETWORK_MODE_GLOBAL;
                }

                message = obtainMessage(EVENT_SET_PREFERRED_NETWORK_TYPE, ar.userObj);
                int toggledNetworkType = RILConstants.NETWORK_MODE_GLOBAL;

                mCi.setPreferredNetworkType(toggledNetworkType, message);
                break;

            case EVENT_CHECK_REPORT_GPRS:
                if (mPhone.isPhoneTypeGsm() && mSS != null &&
                        !isGprsConsistent(mSS.getDataRegState(), mSS.getVoiceRegState())) {

                    // Can't register data service while voice service is ok
                    // i.e. CREG is ok while CGREG is not
                    // possible a network or baseband side error
                    EventLog.writeEvent(EventLogTags.DATA_NETWORK_REGISTRATION_FAIL,
                            mSS.getOperatorNumeric(), getCidFromCellIdentity(mCellIdentity));
                    mReportedGprsNoReg = true;
                }
                mStartedGprsRegCheck = false;
                break;

            case EVENT_RESTRICTED_STATE_CHANGED:
                if (mPhone.isPhoneTypeGsm()) {
                    // This is a notification from
                    // CommandsInterface.setOnRestrictedStateChanged

                    if (DBG) log("EVENT_RESTRICTED_STATE_CHANGED");

                    ar = (AsyncResult) msg.obj;

                    onRestrictedStateChanged(ar);
                }
                break;

            case EVENT_ALL_DATA_DISCONNECTED:
                int dds = SubscriptionManager.getDefaultDataSubscriptionId();
                ProxyController.getInstance().unregisterForAllDataDisconnected(dds, this);
                synchronized(this) {
                    if (mPendingRadioPowerOffAfterDataOff) {
                        if (DBG) log("EVENT_ALL_DATA_DISCONNECTED, turn radio off now.");
                        hangupAndPowerOff();
                        mPendingRadioPowerOffAfterDataOff = false;
                    } else {
                        log("EVENT_ALL_DATA_DISCONNECTED is stale");
                    }
                }
                break;

            case EVENT_CHANGE_IMS_STATE:
                if (DBG) log("EVENT_CHANGE_IMS_STATE:");

                setPowerStateToDesired();
                break;

            case EVENT_IMS_CAPABILITY_CHANGED:
                if (DBG) log("EVENT_IMS_CAPABILITY_CHANGED");
                updateSpnDisplay();
                mImsCapabilityChangedRegistrants.notifyRegistrants();
                break;

            case EVENT_IMS_SERVICE_STATE_CHANGED:
                if (DBG) log("EVENT_IMS_SERVICE_STATE_CHANGED");
                // IMS state will only affect the merged service state if the service state of
                // GsmCdma phone is not STATE_IN_SERVICE.
                if (mSS.getState() != ServiceState.STATE_IN_SERVICE) {
                    mPhone.notifyServiceStateChanged(mPhone.getServiceState());
                }
                break;

            //CDMA
            case EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED:
                handleCdmaSubscriptionSource(mCdmaSSM.getCdmaSubscriptionSource());
                break;

            case EVENT_RUIM_READY:
                if (mPhone.getLteOnCdmaMode() == PhoneConstants.LTE_ON_CDMA_TRUE) {
                    // Subscription will be read from SIM I/O
                    if (DBG) log("Receive EVENT_RUIM_READY");
                    pollState();
                } else {
                    if (DBG) log("Receive EVENT_RUIM_READY and Send Request getCDMASubscription.");
                    getSubscriptionInfoAndStartPollingThreads();
                }

                // Only support automatic selection mode in CDMA.
                mCi.getNetworkSelectionMode(obtainMessage(EVENT_POLL_STATE_NETWORK_SELECTION_MODE));

                break;

            case EVENT_NV_READY:
                updatePhoneObject();

                // Only support automatic selection mode in CDMA.
                mCi.getNetworkSelectionMode(obtainMessage(EVENT_POLL_STATE_NETWORK_SELECTION_MODE));

                // For Non-RUIM phones, the subscription information is stored in
                // Non Volatile. Here when Non-Volatile is ready, we can poll the CDMA
                // subscription info.
                getSubscriptionInfoAndStartPollingThreads();
                break;

            case EVENT_POLL_STATE_CDMA_SUBSCRIPTION: // Handle RIL_CDMA_SUBSCRIPTION
                if (!mPhone.isPhoneTypeGsm()) {
                    ar = (AsyncResult) msg.obj;

                    if (ar.exception == null) {
                        String cdmaSubscription[] = (String[]) ar.result;
                        if (cdmaSubscription != null && cdmaSubscription.length >= 5) {
                            mMdn = cdmaSubscription[0];
                            parseSidNid(cdmaSubscription[1], cdmaSubscription[2]);

                            mMin = cdmaSubscription[3];
                            mPrlVersion = cdmaSubscription[4];
                            if (DBG) log("GET_CDMA_SUBSCRIPTION: MDN=" + mMdn);

                            mIsMinInfoReady = true;

                            updateOtaspState();
                            // Notify apps subscription info is ready
                            notifyCdmaSubscriptionInfoReady();

                            if (!mIsSubscriptionFromRuim && mIccRecords != null) {
                                if (DBG) {
                                    log("GET_CDMA_SUBSCRIPTION set imsi in mIccRecords");
                                }
                                mIccRecords.setImsi(getImsi());
                            } else {
                                if (DBG) {
                                    log("GET_CDMA_SUBSCRIPTION either mIccRecords is null or NV " +
                                            "type device - not setting Imsi in mIccRecords");
                                }
                            }
                        } else {
                            if (DBG) {
                                log("GET_CDMA_SUBSCRIPTION: error parsing cdmaSubscription " +
                                        "params num=" + cdmaSubscription.length);
                            }
                        }
                    }
                }
                break;

            case EVENT_RUIM_RECORDS_LOADED:
                if (!mPhone.isPhoneTypeGsm()) {
                    log("EVENT_RUIM_RECORDS_LOADED: what=" + msg.what);
                    mCdnr.updateEfFromRuim((RuimRecords) mIccRecords);
                    updatePhoneObject();
                    if (mPhone.isPhoneTypeCdma()) {
                        updateSpnDisplay();
                    } else {
                        RuimRecords ruim = (RuimRecords) mIccRecords;
                        if (ruim != null) {
                            // Do not wait for RUIM to be provisioned before using mdn. Line1Number
                            // can be queried before that and mdn may still be available.
                            // Also note that any special casing is not done in getMdnNumber() as it
                            // may be called on another thread, so simply doing a read operation
                            // there.
                            mMdn = ruim.getMdn();
                            if (ruim.isProvisioned()) {
                                mMin = ruim.getMin();
                                parseSidNid(ruim.getSid(), ruim.getNid());
                                mPrlVersion = ruim.getPrlVersion();
                                mIsMinInfoReady = true;
                            }
                            updateOtaspState();
                            // Notify apps subscription info is ready
                            notifyCdmaSubscriptionInfoReady();
                        }
                        // SID/NID/PRL is loaded. Poll service state
                        // again to update to the roaming state with
                        // the latest variables.
                        pollState();
                    }
                }
                break;
            case EVENT_OTA_PROVISION_STATUS_CHANGE:
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    ints = (int[]) ar.result;
                    int otaStatus = ints[0];
                    if (otaStatus == Phone.CDMA_OTA_PROVISION_STATUS_COMMITTED
                            || otaStatus == Phone.CDMA_OTA_PROVISION_STATUS_OTAPA_STOPPED) {
                        if (DBG) log("EVENT_OTA_PROVISION_STATUS_CHANGE: Complete, Reload MDN");
                        mCi.getCDMASubscription( obtainMessage(EVENT_POLL_STATE_CDMA_SUBSCRIPTION));
                    }
                }
                break;

            case EVENT_CDMA_PRL_VERSION_CHANGED:
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    ints = (int[]) ar.result;
                    mPrlVersion = Integer.toString(ints[0]);
                }
                break;

            case EVENT_RADIO_POWER_FROM_CARRIER:
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    boolean enable = (boolean) ar.result;
                    if (DBG) log("EVENT_RADIO_POWER_FROM_CARRIER: " + enable);
                    setRadioPowerFromCarrier(enable);
                }
                break;

            case EVENT_PHYSICAL_CHANNEL_CONFIG:
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    List<PhysicalChannelConfig> list = (List<PhysicalChannelConfig>) ar.result;
                    if (VDBG) {
                        log("EVENT_PHYSICAL_CHANNEL_CONFIG: size=" + list.size() + " list="
                                + list);
                    }
                    mPhone.notifyPhysicalChannelConfiguration(list);
                    mLastPhysicalChannelConfigList = list;
                    boolean hasChanged =
                            updateNrFrequencyRangeFromPhysicalChannelConfigs(list, mSS);
                    hasChanged |= updateNrStateFromPhysicalChannelConfigs(
                            list, mSS);

                    // Notify NR frequency, NR connection status or bandwidths changed.
                    if (hasChanged
                            || RatRatcheter.updateBandwidths(getBandwidthsFromConfigs(list), mSS)) {
                        mPhone.notifyServiceStateChanged(mSS);
                    }
                }
                break;

            case EVENT_CELL_LOCATION_RESPONSE:
                ar = (AsyncResult) msg.obj;
                if (ar == null) {
                    loge("Invalid null response to getCellLocation!");
                    break;
                }
                // This response means that the correct CellInfo is already cached; thus we
                // can rely on the last cell info to already contain any cell info that is
                // available, which means that we can return the result of the existing
                // getCellLocation() function without any additional processing here.
                Message rspRspMsg = (Message) ar.userObj;
                AsyncResult.forMessage(rspRspMsg, getCellLocation(), ar.exception);
                rspRspMsg.sendToTarget();
                break;

            case EVENT_CARRIER_CONFIG_CHANGED:
                onCarrierConfigChanged();
                break;

            default:
                log("Unhandled message with number: " + msg.what);
                break;
        }
    }

    private boolean isSimAbsent() {
        boolean simAbsent;
        if (mUiccController == null) {
            simAbsent = true;
        } else {
            UiccCard uiccCard = mUiccController.getUiccCard(mPhone.getPhoneId());
            if (uiccCard == null) {
                simAbsent = true;
            } else {
                simAbsent = (uiccCard.getCardState() == CardState.CARDSTATE_ABSENT);
            }
        }
        return simAbsent;
    }

    private int[] getBandwidthsFromConfigs(List<PhysicalChannelConfig> list) {
        return list.stream()
                .map(PhysicalChannelConfig::getCellBandwidthDownlink)
                .mapToInt(Integer::intValue)
                .toArray();
    }

    protected boolean isSidsAllZeros() {
        if (mHomeSystemId != null) {
            for (int i=0; i < mHomeSystemId.length; i++) {
                if (mHomeSystemId[i] != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * @return a copy of the current service state.
     */
    public ServiceState getServiceState() {
        return new ServiceState(mSS);
    }

    /**
     * Check whether a specified system ID that matches one of the home system IDs.
     */
    private boolean isHomeSid(int sid) {
        if (mHomeSystemId != null) {
            for (int i=0; i < mHomeSystemId.length; i++) {
                if (sid == mHomeSystemId[i]) {
                    return true;
                }
            }
        }
        return false;
    }

    public String getMdnNumber() {
        return mMdn;
    }

    public String getCdmaMin() {
        return mMin;
    }

    /** Returns null if NV is not yet ready */
    public String getPrlVersion() {
        return mPrlVersion;
    }

    /**
     * Returns IMSI as MCC + MNC + MIN
     */
    public String getImsi() {
        // TODO: When RUIM is enabled, IMSI will come from RUIM not build-time props.
        String operatorNumeric = ((TelephonyManager) mPhone.getContext().
                getSystemService(Context.TELEPHONY_SERVICE)).
                getSimOperatorNumericForPhone(mPhone.getPhoneId());

        if (!TextUtils.isEmpty(operatorNumeric) && getCdmaMin() != null) {
            return (operatorNumeric + getCdmaMin());
        } else {
            return null;
        }
    }

    /**
     * Check if subscription data has been assigned to mMin
     *
     * return true if MIN info is ready; false otherwise.
     */
    public boolean isMinInfoReady() {
        return mIsMinInfoReady;
    }

    /**
     * Returns OTASP_UNKNOWN, OTASP_UNINITIALIZED, OTASP_NEEDED or OTASP_NOT_NEEDED
     */
    public int getOtasp() {
        int provisioningState;
        // if sim is not loaded, return otasp uninitialized
        if(!mPhone.getIccRecordsLoaded()) {
            if(DBG) log("getOtasp: otasp uninitialized due to sim not loaded");
            return TelephonyManager.OTASP_UNINITIALIZED;
        }
        // if voice tech is Gsm, return otasp not needed
        if(mPhone.isPhoneTypeGsm()) {
            if(DBG) log("getOtasp: otasp not needed for GSM");
            return TelephonyManager.OTASP_NOT_NEEDED;
        }
        // for ruim, min is null means require otasp.
        if (mIsSubscriptionFromRuim && mMin == null) {
            return TelephonyManager.OTASP_NEEDED;
        }
        if (mMin == null || (mMin.length() < 6)) {
            if (DBG) log("getOtasp: bad mMin='" + mMin + "'");
            provisioningState = TelephonyManager.OTASP_UNKNOWN;
        } else {
            if ((mMin.equals(UNACTIVATED_MIN_VALUE)
                    || mMin.substring(0,6).equals(UNACTIVATED_MIN2_VALUE))
                    || SystemProperties.getBoolean("test_cdma_setup", false)) {
                provisioningState = TelephonyManager.OTASP_NEEDED;
            } else {
                provisioningState = TelephonyManager.OTASP_NOT_NEEDED;
            }
        }
        if (DBG) log("getOtasp: state=" + provisioningState);
        return provisioningState;
    }

    protected void parseSidNid (String sidStr, String nidStr) {
        if (sidStr != null) {
            String[] sid = sidStr.split(",");
            mHomeSystemId = new int[sid.length];
            for (int i = 0; i < sid.length; i++) {
                try {
                    mHomeSystemId[i] = Integer.parseInt(sid[i]);
                } catch (NumberFormatException ex) {
                    loge("error parsing system id: " + ex);
                }
            }
        }
        if (DBG) log("CDMA_SUBSCRIPTION: SID=" + sidStr);

        if (nidStr != null) {
            String[] nid = nidStr.split(",");
            mHomeNetworkId = new int[nid.length];
            for (int i = 0; i < nid.length; i++) {
                try {
                    mHomeNetworkId[i] = Integer.parseInt(nid[i]);
                } catch (NumberFormatException ex) {
                    loge("CDMA_SUBSCRIPTION: error parsing network id: " + ex);
                }
            }
        }
        if (DBG) log("CDMA_SUBSCRIPTION: NID=" + nidStr);
    }

    @UnsupportedAppUsage
    protected void updateOtaspState() {
        int otaspMode = getOtasp();
        int oldOtaspMode = mCurrentOtaspMode;
        mCurrentOtaspMode = otaspMode;

        if (oldOtaspMode != mCurrentOtaspMode) {
            if (DBG) {
                log("updateOtaspState: call notifyOtaspChanged old otaspMode=" +
                        oldOtaspMode + " new otaspMode=" + mCurrentOtaspMode);
            }
            mPhone.notifyOtaspChanged(mCurrentOtaspMode);
        }
    }

    protected Phone getPhone() {
        return mPhone;
    }

    protected void handlePollStateResult(int what, AsyncResult ar) {
        // Ignore stale requests from last poll
        if (ar.userObj != mPollingContext) return;

        if (ar.exception != null) {
            CommandException.Error err=null;

            if (ar.exception instanceof IllegalStateException) {
                log("handlePollStateResult exception " + ar.exception);
            }

            if (ar.exception instanceof CommandException) {
                err = ((CommandException)(ar.exception)).getCommandError();
            }

            if (err == CommandException.Error.RADIO_NOT_AVAILABLE) {
                // Radio has crashed or turned off
                cancelPollState();
                return;
            }

            if (err != CommandException.Error.OP_NOT_ALLOWED_BEFORE_REG_NW) {
                loge("RIL implementation has returned an error where it must succeed" +
                        ar.exception);
            }
        } else try {
            handlePollStateResultMessage(what, ar);
        } catch (RuntimeException ex) {
            loge("Exception while polling service state. Probably malformed RIL response." + ex);
        }

        mPollingContext[0]--;

        if (mPollingContext[0] == 0) {
            mNewSS.setEmergencyOnly(mEmergencyOnly);
            combinePsRegistrationStates(mNewSS);
            updateOperatorNameForServiceState(mNewSS);
            if (mPhone.isPhoneTypeGsm()) {
                updateRoamingState();
            } else {
                boolean namMatch = false;
                if (!isSidsAllZeros() && isHomeSid(mNewSS.getCdmaSystemId())) {
                    namMatch = true;
                }

                // Setting SS Roaming (general)
                if (mIsSubscriptionFromRuim) {
                    boolean isRoamingBetweenOperators = isRoamingBetweenOperators(
                            mNewSS.getVoiceRoaming(), mNewSS);
                    if (isRoamingBetweenOperators != mNewSS.getVoiceRoaming()) {
                        log("isRoamingBetweenOperators=" + isRoamingBetweenOperators
                                + ". Override CDMA voice roaming to " + isRoamingBetweenOperators);
                        mNewSS.setVoiceRoaming(isRoamingBetweenOperators);
                    }
                }
                /**
                 * For CDMA, voice and data should have the same roaming status.
                 * If voice is not in service, use TSB58 roaming indicator to set
                 * data roaming status. If TSB58 roaming indicator is not in the
                 * carrier-specified list of ERIs for home system then set roaming.
                 */
                final int dataRat = mNewSS.getRilDataRadioTechnology();
                if (ServiceState.isCdma(dataRat)) {
                    final boolean isVoiceInService =
                            (mNewSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE);
                    if (isVoiceInService) {
                        boolean isVoiceRoaming = mNewSS.getVoiceRoaming();
                        if (mNewSS.getDataRoaming() != isVoiceRoaming) {
                            log("Data roaming != Voice roaming. Override data roaming to "
                                    + isVoiceRoaming);
                            mNewSS.setDataRoaming(isVoiceRoaming);
                        }
                    } else {
                        /**
                         * As per VoiceRegStateResult from radio types.hal the TSB58
                         * Roaming Indicator shall be sent if device is registered
                         * on a CDMA or EVDO system.
                         */
                        boolean isRoamIndForHomeSystem = isRoamIndForHomeSystem(mRoamingIndicator);
                        if (mNewSS.getDataRoaming() == isRoamIndForHomeSystem) {
                            log("isRoamIndForHomeSystem=" + isRoamIndForHomeSystem
                                    + ", override data roaming to " + !isRoamIndForHomeSystem);
                            mNewSS.setDataRoaming(!isRoamIndForHomeSystem);
                        }
                    }
                }

                // Setting SS CdmaRoamingIndicator and CdmaDefaultRoamingIndicator
                mNewSS.setCdmaDefaultRoamingIndicator(mDefaultRoamingIndicator);
                mNewSS.setCdmaRoamingIndicator(mRoamingIndicator);
                boolean isPrlLoaded = true;
                if (TextUtils.isEmpty(mPrlVersion)) {
                    isPrlLoaded = false;
                }
                if (!isPrlLoaded || (mNewSS.getRilVoiceRadioTechnology()
                        == ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN)) {
                    log("Turn off roaming indicator if !isPrlLoaded or voice RAT is unknown");
                    mNewSS.setCdmaRoamingIndicator(EriInfo.ROAMING_INDICATOR_OFF);
                } else if (!isSidsAllZeros()) {
                    if (!namMatch && !mIsInPrl) {
                        // Use default
                        mNewSS.setCdmaRoamingIndicator(mDefaultRoamingIndicator);
                    } else if (namMatch && !mIsInPrl) {
                        // TODO this will be removed when we handle roaming on LTE on CDMA+LTE phones
                        if (ServiceState.isLte(mNewSS.getRilVoiceRadioTechnology())) {
                            log("Turn off roaming indicator as voice is LTE");
                            mNewSS.setCdmaRoamingIndicator(EriInfo.ROAMING_INDICATOR_OFF);
                        } else {
                            mNewSS.setCdmaRoamingIndicator(EriInfo.ROAMING_INDICATOR_FLASH);
                        }
                    } else if (!namMatch && mIsInPrl) {
                        // Use the one from PRL/ERI
                        mNewSS.setCdmaRoamingIndicator(mRoamingIndicator);
                    } else {
                        // It means namMatch && mIsInPrl
                        if ((mRoamingIndicator <= 2)) {
                            mNewSS.setCdmaRoamingIndicator(EriInfo.ROAMING_INDICATOR_OFF);
                        } else {
                            // Use the one from PRL/ERI
                            mNewSS.setCdmaRoamingIndicator(mRoamingIndicator);
                        }
                    }
                }

                int roamingIndicator = mNewSS.getCdmaRoamingIndicator();
                mNewSS.setCdmaEriIconIndex(mEriManager.getCdmaEriIconIndex(roamingIndicator,
                        mDefaultRoamingIndicator));
                mNewSS.setCdmaEriIconMode(mEriManager.getCdmaEriIconMode(roamingIndicator,
                        mDefaultRoamingIndicator));

                // NOTE: Some operator may require overriding mCdmaRoaming
                // (set by the modem), depending on the mRoamingIndicator.

                if (DBG) {
                    log("Set CDMA Roaming Indicator to: " + mNewSS.getCdmaRoamingIndicator()
                            + ". voiceRoaming = " + mNewSS.getVoiceRoaming()
                            + ". dataRoaming = " + mNewSS.getDataRoaming()
                            + ", isPrlLoaded = " + isPrlLoaded
                            + ". namMatch = " + namMatch + " , mIsInPrl = " + mIsInPrl
                            + ", mRoamingIndicator = " + mRoamingIndicator
                            + ", mDefaultRoamingIndicator= " + mDefaultRoamingIndicator);
                }
            }
            pollStateDone();
        }

    }

    /**
     * Set roaming state when cdmaRoaming is true and ons is different from spn
     * @param cdmaRoaming TS 27.007 7.2 CREG registered roaming
     * @param s ServiceState hold current ons
     * @return true for roaming state set
     */
    private boolean isRoamingBetweenOperators(boolean cdmaRoaming, ServiceState s) {
        return cdmaRoaming && !isSameOperatorNameFromSimAndSS(s);
    }

    private boolean isNrStateChanged(
            NetworkRegistrationInfo oldRegState, NetworkRegistrationInfo newRegState) {
        if (oldRegState == null || newRegState == null) {
            return oldRegState != newRegState;
        }

        return oldRegState.getNrState() != newRegState.getNrState();
    }

    private boolean updateNrFrequencyRangeFromPhysicalChannelConfigs(
            List<PhysicalChannelConfig> physicalChannelConfigs, ServiceState ss) {
        int newFrequencyRange = ServiceState.FREQUENCY_RANGE_UNKNOWN;

        if (physicalChannelConfigs != null) {
            DcTracker dcTracker = mPhone.getDcTracker(AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
            for (PhysicalChannelConfig config : physicalChannelConfigs) {
                if (isNrPhysicalChannelConfig(config)) {
                    // Update the frequency range of the NR parameters if there is an internet data
                    // connection associate to this NR physical channel channel config.
                    int[] contextIds = config.getContextIds();
                    for (int cid : contextIds) {
                        DataConnection dc = dcTracker.getDataConnectionByContextId(cid);
                        if (dc != null && dc.getNetworkCapabilities().hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                            newFrequencyRange = ServiceState.getBetterNRFrequencyRange(
                                    newFrequencyRange, config.getFrequencyRange());
                            break;
                        }
                    }
                }
            }
        }

        boolean hasChanged = newFrequencyRange != ss.getNrFrequencyRange();
        ss.setNrFrequencyRange(newFrequencyRange);
        return hasChanged;
    }

    private boolean updateNrStateFromPhysicalChannelConfigs(
            List<PhysicalChannelConfig> configs, ServiceState ss) {
        NetworkRegistrationInfo regInfo = ss.getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        if (regInfo == null || configs == null) return false;

        boolean hasNrSecondaryServingCell = false;
        for (PhysicalChannelConfig config : configs) {
            if (isNrPhysicalChannelConfig(config) && config.getConnectionStatus()
                    == PhysicalChannelConfig.CONNECTION_SECONDARY_SERVING) {
                hasNrSecondaryServingCell = true;
                break;
            }
        }

        int newNrState = regInfo.getNrState();
        if (hasNrSecondaryServingCell) {
            if (regInfo.getNrState() == NetworkRegistrationInfo.NR_STATE_NOT_RESTRICTED) {
                newNrState = NetworkRegistrationInfo.NR_STATE_CONNECTED;
            }
        } else {
            if (regInfo.getNrState() == NetworkRegistrationInfo.NR_STATE_CONNECTED) {
                newNrState = NetworkRegistrationInfo.NR_STATE_NOT_RESTRICTED;
            }
        }

        boolean hasChanged = newNrState != regInfo.getNrState();
        regInfo.setNrState(newNrState);
        ss.addNetworkRegistrationInfo(regInfo);
        return hasChanged;
    }

    private boolean isNrPhysicalChannelConfig(PhysicalChannelConfig config) {
        return config.getRat() == TelephonyManager.NETWORK_TYPE_NR;
    }

    /**
     * This combine PS registration states from cellular and IWLAN and generates the final data
     * reg state and rat for backward compatibility purpose. In reality there should be two separate
     * registration states for cellular and IWLAN, but in legacy mode, if the device camps on IWLAN,
     * the IWLAN registration states overwrites the service states. This method is to simulate that
     * behavior.
     *
     * @param serviceState The service state having combined registration states.
     */
    private void combinePsRegistrationStates(ServiceState serviceState) {
        NetworkRegistrationInfo wlanPsRegState = serviceState.getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        NetworkRegistrationInfo wwanPsRegState = serviceState.getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);

        // Check if any APN is preferred on IWLAN.
        boolean isIwlanPreferred = mTransportManager.isAnyApnPreferredOnIwlan();
        serviceState.setIwlanPreferred(isIwlanPreferred);
        if (wlanPsRegState != null
                && wlanPsRegState.getAccessNetworkTechnology()
                == TelephonyManager.NETWORK_TYPE_IWLAN
                && wlanPsRegState.getRegistrationState()
                == NetworkRegistrationInfo.REGISTRATION_STATE_HOME
                && isIwlanPreferred) {
            serviceState.setDataRegState(ServiceState.STATE_IN_SERVICE);
        } else if (wwanPsRegState != null) {
            // If the device is not camped on IWLAN, then we use cellular PS registration state
            // to compute reg state and rat.
            int regState = wwanPsRegState.getRegistrationState();
            serviceState.setDataRegState(regCodeToServiceState(regState));
        }
        if (DBG) {
            log("combinePsRegistrationStates: " + serviceState);
        }
    }

    void handlePollStateResultMessage(int what, AsyncResult ar) {
        int ints[];
        switch (what) {
            case EVENT_POLL_STATE_CS_CELLULAR_REGISTRATION: {
                NetworkRegistrationInfo networkRegState = (NetworkRegistrationInfo) ar.result;
                VoiceSpecificRegistrationInfo voiceSpecificStates =
                        networkRegState.getVoiceSpecificInfo();

                int registrationState = networkRegState.getRegistrationState();
                int cssIndicator = voiceSpecificStates.cssSupported ? 1 : 0;
                int newVoiceRat = ServiceState.networkTypeToRilRadioTechnology(
                        networkRegState.getAccessNetworkTechnology());

                mNewSS.setVoiceRegState(regCodeToServiceState(registrationState));
                mNewSS.setCssIndicator(cssIndicator);
                mNewSS.addNetworkRegistrationInfo(networkRegState);
                setPhyCellInfoFromCellIdentity(mNewSS, networkRegState.getCellIdentity());

                //Denial reason if registrationState = 3
                int reasonForDenial = networkRegState.getRejectCause();
                mEmergencyOnly = networkRegState.isEmergencyEnabled();
                if (mPhone.isPhoneTypeGsm()) {

                    mGsmRoaming = regCodeIsRoaming(registrationState);
                    mNewRejectCode = reasonForDenial;

                    boolean isVoiceCapable = mPhone.getContext().getResources()
                            .getBoolean(com.android.internal.R.bool.config_voice_capable);
                } else {
                    int roamingIndicator = voiceSpecificStates.roamingIndicator;

                    //Indicates if current system is in PR
                    int systemIsInPrl = voiceSpecificStates.systemIsInPrl;

                    //Is default roaming indicator from PRL
                    int defaultRoamingIndicator = voiceSpecificStates.defaultRoamingIndicator;

                    mRegistrationState = registrationState;
                    // When registration state is roaming and TSB58
                    // roaming indicator is not in the carrier-specified
                    // list of ERIs for home system, mCdmaRoaming is true.
                    boolean cdmaRoaming =
                            regCodeIsRoaming(registrationState)
                                    && !isRoamIndForHomeSystem(roamingIndicator);
                    mNewSS.setVoiceRoaming(cdmaRoaming);
                    mRoamingIndicator = roamingIndicator;
                    mIsInPrl = (systemIsInPrl == 0) ? false : true;
                    mDefaultRoamingIndicator = defaultRoamingIndicator;

                    int systemId = 0;
                    int networkId = 0;
                    CellIdentity cellIdentity = networkRegState.getCellIdentity();
                    if (cellIdentity != null && cellIdentity.getType() == CellInfoType.CDMA) {
                        systemId = ((CellIdentityCdma) cellIdentity).getSystemId();
                        networkId = ((CellIdentityCdma) cellIdentity).getNetworkId();
                    }
                    mNewSS.setCdmaSystemAndNetworkId(systemId, networkId);

                    if (reasonForDenial == 0) {
                        mRegistrationDeniedReason = ServiceStateTracker.REGISTRATION_DENIED_GEN;
                    } else if (reasonForDenial == 1) {
                        mRegistrationDeniedReason = ServiceStateTracker.REGISTRATION_DENIED_AUTH;
                    } else {
                        mRegistrationDeniedReason = "";
                    }

                    if (mRegistrationState == 3) {
                        if (DBG) log("Registration denied, " + mRegistrationDeniedReason);
                    }
                }

                mNewCellIdentity = networkRegState.getCellIdentity();

                if (DBG) {
                    log("handlePollStateResultMessage: CS cellular. " + networkRegState);
                }
                break;
            }

            case EVENT_POLL_STATE_PS_IWLAN_REGISTRATION: {
                NetworkRegistrationInfo networkRegState = (NetworkRegistrationInfo) ar.result;
                mNewSS.addNetworkRegistrationInfo(networkRegState);

                if (DBG) {
                    log("handlePollStateResultMessage: PS IWLAN. " + networkRegState);
                }
                break;
            }

            case EVENT_POLL_STATE_PS_CELLULAR_REGISTRATION: {
                NetworkRegistrationInfo networkRegState = (NetworkRegistrationInfo) ar.result;
                mNewSS.addNetworkRegistrationInfo(networkRegState);
                DataSpecificRegistrationInfo dataSpecificStates =
                        networkRegState.getDataSpecificInfo();
                int registrationState = networkRegState.getRegistrationState();
                int serviceState = regCodeToServiceState(registrationState);
                int newDataRat = ServiceState.networkTypeToRilRadioTechnology(
                        networkRegState.getAccessNetworkTechnology());

                if (DBG) {
                    log("handlePollStateResultMessage: PS cellular. " + networkRegState);
                }

                // When we receive OOS reset the PhyChanConfig list so that non-return-to-idle
                // implementers of PhyChanConfig unsol will not carry forward a CA report
                // (2 or more cells) to a new cell if they camp for emergency service only.
                if (serviceState == ServiceState.STATE_OUT_OF_SERVICE) {
                    mLastPhysicalChannelConfigList = null;
                    updateNrFrequencyRangeFromPhysicalChannelConfigs(null, mNewSS);
                }
                updateNrStateFromPhysicalChannelConfigs(mLastPhysicalChannelConfigList, mNewSS);
                setPhyCellInfoFromCellIdentity(mNewSS, networkRegState.getCellIdentity());

                if (mPhone.isPhoneTypeGsm()) {

                    mNewReasonDataDenied = networkRegState.getRejectCause();
                    mNewMaxDataCalls = dataSpecificStates.maxDataCalls;
                    mDataRoaming = regCodeIsRoaming(registrationState);
                } else if (mPhone.isPhoneTypeCdma()) {

                    boolean isDataRoaming = regCodeIsRoaming(registrationState);
                    mNewSS.setDataRoaming(isDataRoaming);
                } else {

                    // If the unsolicited signal strength comes just before data RAT family changes
                    // (i.e. from UNKNOWN to LTE, CDMA to LTE, LTE to CDMA), the signal bar might
                    // display the wrong information until the next unsolicited signal strength
                    // information coming from the modem, which might take a long time to come or
                    // even not come at all.  In order to provide the best user experience, we
                    // query the latest signal information so it will show up on the UI on time.
                    int oldDataRAT = mSS.getRilDataRadioTechnology();
                    if (((oldDataRAT == ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN)
                            && (newDataRat != ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN))
                            || (ServiceState.isCdma(oldDataRAT) && ServiceState.isLte(newDataRat))
                            || (ServiceState.isLte(oldDataRAT)
                            && ServiceState.isCdma(newDataRat))) {
                        mCi.getSignalStrength(obtainMessage(EVENT_GET_SIGNAL_STRENGTH));
                    }

                    // voice roaming state in done while handling EVENT_POLL_STATE_REGISTRATION_CDMA
                    boolean isDataRoaming = regCodeIsRoaming(registrationState);
                    mNewSS.setDataRoaming(isDataRoaming);
                }

                updateServiceStateLteEarfcnBoost(mNewSS,
                        getLteEarfcn(networkRegState.getCellIdentity()));
                break;
            }

            case EVENT_POLL_STATE_OPERATOR: {
                String brandOverride = getOperatorBrandOverride();
                mCdnr.updateEfForBrandOverride(brandOverride);
                if (mPhone.isPhoneTypeGsm()) {
                    String opNames[] = (String[]) ar.result;

                    if (opNames != null && opNames.length >= 3) {
                        mNewSS.setOperatorAlphaLongRaw(opNames[0]);
                        mNewSS.setOperatorAlphaShortRaw(opNames[1]);
                        // FIXME: Giving brandOverride higher precedence, is this desired?
                        if (brandOverride != null) {
                            log("EVENT_POLL_STATE_OPERATOR: use brandOverride=" + brandOverride);
                            mNewSS.setOperatorName(brandOverride, brandOverride, opNames[2]);
                        } else {
                            mNewSS.setOperatorName(opNames[0], opNames[1], opNames[2]);
                        }
                    }
                } else {
                    String opNames[] = (String[])ar.result;

                    if (opNames != null && opNames.length >= 3) {
                        // TODO: Do we care about overriding in this case.
                        // If the NUMERIC field isn't valid use PROPERTY_CDMA_HOME_OPERATOR_NUMERIC
                        if ((opNames[2] == null) || (opNames[2].length() < 5)
                                || ("00000".equals(opNames[2]))) {
                            opNames[2] = SystemProperties.get(
                                    GsmCdmaPhone.PROPERTY_CDMA_HOME_OPERATOR_NUMERIC, "00000");
                            if (DBG) {
                                log("RIL_REQUEST_OPERATOR.response[2], the numeric, " +
                                        " is bad. Using SystemProperties '" +
                                        GsmCdmaPhone.PROPERTY_CDMA_HOME_OPERATOR_NUMERIC +
                                        "'= " + opNames[2]);
                            }
                        }

                        if (!mIsSubscriptionFromRuim) {
                            // NV device (as opposed to CSIM)
                            mNewSS.setOperatorName(opNames[0], opNames[1], opNames[2]);
                        } else {
                            if (brandOverride != null) {
                                mNewSS.setOperatorName(brandOverride, brandOverride, opNames[2]);
                            } else {
                                mNewSS.setOperatorName(opNames[0], opNames[1], opNames[2]);
                            }
                        }
                    } else {
                        if (DBG) log("EVENT_POLL_STATE_OPERATOR_CDMA: error parsing opNames");
                    }
                }
                break;
            }

            case EVENT_POLL_STATE_NETWORK_SELECTION_MODE: {
                ints = (int[])ar.result;
                mNewSS.setIsManualSelection(ints[0] == 1);
                if ((ints[0] == 1) && (mPhone.shouldForceAutoNetworkSelect())) {
                        /*
                         * modem is currently in manual selection but manual
                         * selection is not allowed in the current mode so
                         * switch to automatic registration
                         */
                    mPhone.setNetworkSelectionModeAutomatic (null);
                    log(" Forcing Automatic Network Selection, " +
                            "manual selection is not allowed");
                }
                break;
            }

            default:
                loge("handlePollStateResultMessage: Unexpected RIL response received: " + what);
        }
    }

    private static boolean isValidLteBandwidthKhz(int bandwidth) {
        // Valid bandwidths, see 3gpp 36.101 sec. 5.6
        switch (bandwidth) {
            case 1400:
            case 3000:
            case 5000:
            case 10000:
            case 15000:
            case 20000:
                return true;
            default:
                return false;
        }
    }

    /**
     * Extract the CID/CI for GSM/UTRA/EUTRA
     *
     * @returns the cell ID (unique within a PLMN for a given tech) or -1 if invalid
     */
    private static int getCidFromCellIdentity(CellIdentity id) {
        if (id == null) return -1;
        int cid = -1;
        switch(id.getType()) {
            case CellInfo.TYPE_GSM: cid = ((CellIdentityGsm) id).getCid(); break;
            case CellInfo.TYPE_WCDMA: cid = ((CellIdentityWcdma) id).getCid(); break;
            case CellInfo.TYPE_TDSCDMA: cid = ((CellIdentityTdscdma) id).getCid(); break;
            case CellInfo.TYPE_LTE: cid = ((CellIdentityLte) id).getCi(); break;
            default: break;
        }
        // If the CID is unreported
        if (cid == Integer.MAX_VALUE) cid = -1;

        return cid;
    }

    private void setPhyCellInfoFromCellIdentity(ServiceState ss, CellIdentity cellIdentity) {
        if (cellIdentity == null) {
            if (DBG) {
                log("Could not set ServiceState channel number. CellIdentity null");
            }
            return;
        }

        ss.setChannelNumber(cellIdentity.getChannelNumber());
        if (VDBG) {
            log("Setting channel number: " + cellIdentity.getChannelNumber());
        }

        if (cellIdentity instanceof CellIdentityLte) {
            CellIdentityLte cl = (CellIdentityLte) cellIdentity;
            int[] bandwidths = null;
            // Prioritize the PhysicalChannelConfig list because we might already be in carrier
            // aggregation by the time poll state is performed.
            if (!ArrayUtils.isEmpty(mLastPhysicalChannelConfigList)) {
                bandwidths = getBandwidthsFromConfigs(mLastPhysicalChannelConfigList);
                for (int bw : bandwidths) {
                    if (!isValidLteBandwidthKhz(bw)) {
                        loge("Invalid LTE Bandwidth in RegistrationState, " + bw);
                        bandwidths = null;
                        break;
                    }
                }
            }
            // If we don't have a PhysicalChannelConfig[] list, then pull from CellIdentityLte.
            // This is normal if we're in idle mode and the PhysicalChannelConfig[] has already
            // been updated. This is also a fallback in case the PhysicalChannelConfig info
            // is invalid (ie, broken).
            // Also, for vendor implementations that do not report return-to-idle, we should
            // prioritize the bandwidth report in the CellIdentity, because the physical channel
            // config report may be stale in the case where a single carrier was used previously
            // and we transition to camped-for-emergency (since we never have a physical
            // channel active). In the normal case of single-carrier non-return-to-idle, the
            // values *must* be the same, so it doesn't matter which is chosen.
            if (bandwidths == null || bandwidths.length == 1) {
                final int cbw = cl.getBandwidth();
                if (isValidLteBandwidthKhz(cbw)) {
                    bandwidths = new int[] {cbw};
                } else if (cbw == Integer.MAX_VALUE) {
                    // Bandwidth is unreported; c'est la vie. This is not an error because
                    // pre-1.2 HAL implementations do not support bandwidth reporting.
                } else {
                    loge("Invalid LTE Bandwidth in RegistrationState, " + cbw);
                }
            }
            if (bandwidths != null) {
                ss.setCellBandwidths(bandwidths);
            }
        } else {
            if (VDBG) log("Skipping bandwidth update for Non-LTE cell.");
        }
    }

    /**
     * Determine whether a roaming indicator is in the carrier-specified list of ERIs for
     * home system
     *
     * @param roamInd roaming indicator
     * @return true if the roamInd is in the carrier-specified list of ERIs for home network
     */
    private boolean isRoamIndForHomeSystem(int roamInd) {
        // retrieve the carrier-specified list of ERIs for home system
        final PersistableBundle config = getCarrierConfig();
        int[] homeRoamIndicators = config.getIntArray(CarrierConfigManager
                    .KEY_CDMA_ENHANCED_ROAMING_INDICATOR_FOR_HOME_NETWORK_INT_ARRAY);

        log("isRoamIndForHomeSystem: homeRoamIndicators=" + Arrays.toString(homeRoamIndicators));

        if (homeRoamIndicators != null) {
            // searches through the comma-separated list for a match,
            // return true if one is found.
            for (int homeRoamInd : homeRoamIndicators) {
                if (homeRoamInd == roamInd) {
                    return true;
                }
            }
            // no matches found against the list!
            log("isRoamIndForHomeSystem: No match found against list for roamInd=" + roamInd);
            return false;
        }

        // no system property found for the roaming indicators for home system
        log("isRoamIndForHomeSystem: No list found");
        return false;
    }

    /**
     * Query the carrier configuration to determine if there any network overrides
     * for roaming or not roaming for the current service state.
     */
    @UnsupportedAppUsage
    protected void updateRoamingState() {
        PersistableBundle bundle = getCarrierConfig();

        if (mPhone.isPhoneTypeGsm()) {
            /**
             * Since the roaming state of gsm service (from +CREG) and
             * data service (from +CGREG) could be different, the new SS
             * is set to roaming when either is true.
             *
             * There are exceptions for the above rule.
             * The new SS is not set as roaming while gsm service reports
             * roaming but indeed it is same operator.
             * And the operator is considered non roaming.
             *
             * The test for the operators is to handle special roaming
             * agreements and MVNO's.
             */
            boolean roaming = (mGsmRoaming || mDataRoaming);

            if (mGsmRoaming && !isOperatorConsideredRoaming(mNewSS)
                    && (isSameNamedOperators(mNewSS) || isOperatorConsideredNonRoaming(mNewSS))) {
                log("updateRoamingState: resource override set non roaming.isSameNamedOperators="
                        + isSameNamedOperators(mNewSS) + ",isOperatorConsideredNonRoaming="
                        + isOperatorConsideredNonRoaming(mNewSS));
                roaming = false;
            }

            if (alwaysOnHomeNetwork(bundle)) {
                log("updateRoamingState: carrier config override always on home network");
                roaming = false;
            } else if (isNonRoamingInGsmNetwork(bundle, mNewSS.getOperatorNumeric())) {
                log("updateRoamingState: carrier config override set non roaming:"
                        + mNewSS.getOperatorNumeric());
                roaming = false;
            } else if (isRoamingInGsmNetwork(bundle, mNewSS.getOperatorNumeric())) {
                log("updateRoamingState: carrier config override set roaming:"
                        + mNewSS.getOperatorNumeric());
                roaming = true;
            }

            mNewSS.setVoiceRoaming(roaming);
            mNewSS.setDataRoaming(roaming);
        } else {
            String systemId = Integer.toString(mNewSS.getCdmaSystemId());

            if (alwaysOnHomeNetwork(bundle)) {
                log("updateRoamingState: carrier config override always on home network");
                setRoamingOff();
            } else if (isNonRoamingInGsmNetwork(bundle, mNewSS.getOperatorNumeric())
                    || isNonRoamingInCdmaNetwork(bundle, systemId)) {
                log("updateRoamingState: carrier config override set non-roaming:"
                        + mNewSS.getOperatorNumeric() + ", " + systemId);
                setRoamingOff();
            } else if (isRoamingInGsmNetwork(bundle, mNewSS.getOperatorNumeric())
                    || isRoamingInCdmaNetwork(bundle, systemId)) {
                log("updateRoamingState: carrier config override set roaming:"
                        + mNewSS.getOperatorNumeric() + ", " + systemId);
                setRoamingOn();
            }

            if (Build.IS_DEBUGGABLE && SystemProperties.getBoolean(PROP_FORCE_ROAMING, false)) {
                mNewSS.setVoiceRoaming(true);
                mNewSS.setDataRoaming(true);
            }
        }
    }

    private void setRoamingOn() {
        mNewSS.setVoiceRoaming(true);
        mNewSS.setDataRoaming(true);
        mNewSS.setCdmaEriIconIndex(EriInfo.ROAMING_INDICATOR_ON);
        mNewSS.setCdmaEriIconMode(EriInfo.ROAMING_ICON_MODE_NORMAL);
    }

    private void setRoamingOff() {
        mNewSS.setVoiceRoaming(false);
        mNewSS.setDataRoaming(false);
        mNewSS.setCdmaEriIconIndex(EriInfo.ROAMING_INDICATOR_OFF);
    }

    private void updateOperatorNameFromCarrierConfig() {
        // Brand override gets a priority over carrier config. If brand override is not available,
        // override the operator name in home network. Also do this only for CDMA. This is temporary
        // and should be fixed in a proper way in a later release.
        if (!mPhone.isPhoneTypeGsm() && !mSS.getRoaming()) {
            boolean hasBrandOverride = mUiccController.getUiccCard(getPhoneId()) != null
                    && mUiccController.getUiccCard(getPhoneId()).getOperatorBrandOverride() != null;
            if (!hasBrandOverride) {
                PersistableBundle config = getCarrierConfig();
                if (config.getBoolean(
                        CarrierConfigManager.KEY_CDMA_HOME_REGISTERED_PLMN_NAME_OVERRIDE_BOOL)) {
                    String operator = config.getString(
                            CarrierConfigManager.KEY_CDMA_HOME_REGISTERED_PLMN_NAME_STRING);
                    log("updateOperatorNameFromCarrierConfig: changing from "
                            + mSS.getOperatorAlpha() + " to " + operator);
                    // override long and short operator name, keeping numeric the same
                    mSS.setOperatorName(operator, operator, mSS.getOperatorNumeric());
                }
            }
        }
    }

    private void notifySpnDisplayUpdate(CarrierDisplayNameData data) {
        int subId = mPhone.getSubId();
        // Update SPN_STRINGS_UPDATED_ACTION IFF any value changes
        if (mSubId != subId
                || data.shouldShowPlmn() != mCurShowPlmn
                || data.shouldShowSpn() != mCurShowSpn
                || !TextUtils.equals(data.getSpn(), mCurSpn)
                || !TextUtils.equals(data.getDataSpn(), mCurDataSpn)
                || !TextUtils.equals(data.getPlmn(), mCurPlmn)) {

            final String log = String.format("updateSpnDisplay: changed sending intent, "
                            + "rule=%d, showPlmn='%b', plmn='%s', showSpn='%b', spn='%s', "
                            + "dataSpn='%s', subId='%d'",
                    getCarrierNameDisplayBitmask(mSS),
                    data.shouldShowPlmn(),
                    data.getPlmn(),
                    data.shouldShowSpn(),
                    data.getSpn(),
                    data.getDataSpn(),
                    subId);
            mCdnrLogs.log(log);
            if (DBG) log("updateSpnDisplay: " + log);

            Intent intent = new Intent(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION);
            intent.putExtra(TelephonyIntents.EXTRA_SHOW_SPN, data.shouldShowSpn());
            intent.putExtra(TelephonyIntents.EXTRA_SPN, data.getSpn());
            intent.putExtra(TelephonyIntents.EXTRA_DATA_SPN, data.getDataSpn());
            intent.putExtra(TelephonyIntents.EXTRA_SHOW_PLMN, data.shouldShowPlmn());
            intent.putExtra(TelephonyIntents.EXTRA_PLMN, data.getPlmn());
            SubscriptionManager.putPhoneIdAndSubIdExtra(intent, mPhone.getPhoneId());
            mPhone.getContext().sendStickyBroadcastAsUser(intent, UserHandle.ALL);

            if (!mSubscriptionController.setPlmnSpn(mPhone.getPhoneId(),
                    data.shouldShowPlmn(), data.getPlmn(), data.shouldShowSpn(), data.getSpn())) {
                mSpnUpdatePending = true;
            }
        }

        mSubId = subId;
        mCurShowSpn = data.shouldShowSpn();
        mCurShowPlmn = data.shouldShowPlmn();
        mCurSpn = data.getSpn();
        mCurDataSpn = data.getDataSpn();
        mCurPlmn = data.getPlmn();
    }

    private void updateSpnDisplayCdnr() {
        log("updateSpnDisplayCdnr+");
        CarrierDisplayNameData data = mCdnr.getCarrierDisplayNameData();
        notifySpnDisplayUpdate(data);
        log("updateSpnDisplayCdnr-");
    }

    @UnsupportedAppUsage
    @VisibleForTesting
    public void updateSpnDisplay() {
        PersistableBundle config = getCarrierConfig();
        if (config.getBoolean(CarrierConfigManager.KEY_ENABLE_CARRIER_DISPLAY_NAME_RESOLVER_BOOL)) {
            updateSpnDisplayCdnr();
        } else {
            updateSpnDisplayLegacy();
        }
    }

    private void updateSpnDisplayLegacy() {
        log("updateSpnDisplayLegacy+");

        String spn = null;
        String dataSpn = null;
        boolean showSpn = false;
        String plmn = null;
        boolean showPlmn = false;

        String wfcVoiceSpnFormat = null;
        String wfcDataSpnFormat = null;
        String wfcFlightSpnFormat = null;
        int combinedRegState = getCombinedRegState(mSS);
        if (mPhone.getImsPhone() != null && mPhone.getImsPhone().isWifiCallingEnabled()
                && (combinedRegState == ServiceState.STATE_IN_SERVICE)) {
            // In Wi-Fi Calling mode show SPN or PLMN + WiFi Calling
            //
            // 1) Show SPN + Wi-Fi Calling If SIM has SPN and SPN display condition
            //    is satisfied or SPN override is enabled for this carrier
            //
            // 2) Show PLMN + Wi-Fi Calling if there is no valid SPN in case 1

            int voiceIdx = 0;
            int dataIdx = 0;
            int flightModeIdx = -1;
            boolean useRootLocale = false;

            PersistableBundle bundle = getCarrierConfig();

            voiceIdx = bundle.getInt(CarrierConfigManager.KEY_WFC_SPN_FORMAT_IDX_INT);
            dataIdx = bundle.getInt(
                    CarrierConfigManager.KEY_WFC_DATA_SPN_FORMAT_IDX_INT);
            flightModeIdx = bundle.getInt(
                    CarrierConfigManager.KEY_WFC_FLIGHT_MODE_SPN_FORMAT_IDX_INT);
            useRootLocale =
                    bundle.getBoolean(CarrierConfigManager.KEY_WFC_SPN_USE_ROOT_LOCALE);

            String[] wfcSpnFormats = SubscriptionManager.getResourcesForSubId(mPhone.getContext(),
                    mPhone.getSubId(), useRootLocale)
                    .getStringArray(com.android.internal.R.array.wfcSpnFormats);

            if (voiceIdx < 0 || voiceIdx >= wfcSpnFormats.length) {
                loge("updateSpnDisplay: KEY_WFC_SPN_FORMAT_IDX_INT out of bounds: " + voiceIdx);
                voiceIdx = 0;
            }
            if (dataIdx < 0 || dataIdx >= wfcSpnFormats.length) {
                loge("updateSpnDisplay: KEY_WFC_DATA_SPN_FORMAT_IDX_INT out of bounds: "
                        + dataIdx);
                dataIdx = 0;
            }
            if (flightModeIdx < 0 || flightModeIdx >= wfcSpnFormats.length) {
                // KEY_WFC_FLIGHT_MODE_SPN_FORMAT_IDX_INT out of bounds. Use the value from
                // voiceIdx.
                flightModeIdx = voiceIdx;
            }

            wfcVoiceSpnFormat = wfcSpnFormats[voiceIdx];
            wfcDataSpnFormat = wfcSpnFormats[dataIdx];
            wfcFlightSpnFormat = wfcSpnFormats[flightModeIdx];
        }

        if (mPhone.isPhoneTypeGsm()) {
            // The values of plmn/showPlmn change in different scenarios.
            // 1) No service but emergency call allowed -> expected
            //    to show "Emergency call only"
            //    EXTRA_SHOW_PLMN = true
            //    EXTRA_PLMN = "Emergency call only"

            // 2) No service at all --> expected to show "No service"
            //    EXTRA_SHOW_PLMN = true
            //    EXTRA_PLMN = "No service"

            // 3) Normal operation in either home or roaming service
            //    EXTRA_SHOW_PLMN = depending on IccRecords rule
            //    EXTRA_PLMN = plmn

            // 4) No service due to power off, aka airplane mode
            //    EXTRA_SHOW_PLMN = false
            //    EXTRA_PLMN = null

            IccRecords iccRecords = mIccRecords;
            int rule = getCarrierNameDisplayBitmask(mSS);
            boolean noService = false;
            if (combinedRegState == ServiceState.STATE_OUT_OF_SERVICE
                    || combinedRegState == ServiceState.STATE_EMERGENCY_ONLY) {
                showPlmn = true;

                // Force display no service
                final boolean forceDisplayNoService = mPhone.getContext().getResources().getBoolean(
                        com.android.internal.R.bool.config_display_no_service_when_sim_unready)
                        && !mIsSimReady;
                if (mEmergencyOnly && !forceDisplayNoService) {
                    // No service but emergency call allowed
                    plmn = Resources.getSystem().
                            getText(com.android.internal.R.string.emergency_calls_only).toString();
                } else {
                    // No service at all
                    plmn = Resources.getSystem().
                            getText(com.android.internal.R.string.lockscreen_carrier_default).toString();
                    noService = true;
                }
                if (DBG) log("updateSpnDisplay: radio is on but out " +
                        "of service, set plmn='" + plmn + "'");
            } else if (combinedRegState == ServiceState.STATE_IN_SERVICE) {
                // In either home or roaming service
                plmn = mSS.getOperatorAlpha();
                showPlmn = !TextUtils.isEmpty(plmn) &&
                        ((rule & CARRIER_NAME_DISPLAY_BITMASK_SHOW_PLMN)
                                == CARRIER_NAME_DISPLAY_BITMASK_SHOW_PLMN);
                if (DBG) log("updateSpnDisplay: rawPlmn = " + plmn);
            } else {
                // Power off state, such as airplane mode, show plmn as "No service"
                showPlmn = true;
                plmn = Resources.getSystem().
                        getText(com.android.internal.R.string.lockscreen_carrier_default).toString();
                if (DBG) log("updateSpnDisplay: radio is off w/ showPlmn="
                        + showPlmn + " plmn=" + plmn);
            }

            // The value of spn/showSpn are same in different scenarios.
            //    EXTRA_SHOW_SPN = depending on IccRecords rule and radio/IMS state
            //    EXTRA_SPN = spn
            //    EXTRA_DATA_SPN = dataSpn
            spn = getServiceProviderName();
            dataSpn = spn;
            showSpn = !noService && !TextUtils.isEmpty(spn)
                    && ((rule & CARRIER_NAME_DISPLAY_BITMASK_SHOW_SPN)
                    == CARRIER_NAME_DISPLAY_BITMASK_SHOW_SPN);
            if (DBG) log("updateSpnDisplay: rawSpn = " + spn);

            if (!TextUtils.isEmpty(spn) && !TextUtils.isEmpty(wfcVoiceSpnFormat) &&
                    !TextUtils.isEmpty(wfcDataSpnFormat)) {
                // Show SPN + Wi-Fi Calling If SIM has SPN and SPN display condition
                // is satisfied or SPN override is enabled for this carrier.

                // Handle Flight Mode
                if (mSS.getVoiceRegState() == ServiceState.STATE_POWER_OFF) {
                    wfcVoiceSpnFormat = wfcFlightSpnFormat;
                }

                String originalSpn = spn.trim();
                spn = String.format(wfcVoiceSpnFormat, originalSpn);
                dataSpn = String.format(wfcDataSpnFormat, originalSpn);
                showSpn = true;
                showPlmn = false;
            } else if (!TextUtils.isEmpty(plmn) && !TextUtils.isEmpty(wfcVoiceSpnFormat)) {
                // Show PLMN + Wi-Fi Calling if there is no valid SPN in the above case
                String originalPlmn = plmn.trim();
                plmn = String.format(wfcVoiceSpnFormat, originalPlmn);
            } else if (mSS.getVoiceRegState() == ServiceState.STATE_POWER_OFF
                    || (showPlmn && TextUtils.equals(spn, plmn))) {
                // airplane mode or spn equals plmn, do not show spn
                spn = null;
                showSpn = false;
            }
        } else {
            String eriText = getOperatorNameFromEri();
            if (eriText != null) mSS.setOperatorAlphaLong(eriText);

            // carrier config gets a priority over ERI
            updateOperatorNameFromCarrierConfig();

            // mOperatorAlpha contains the ERI text
            plmn = mSS.getOperatorAlpha();
            if (DBG) log("updateSpnDisplay: cdma rawPlmn = " + plmn);

            showPlmn = plmn != null;

            if (!TextUtils.isEmpty(plmn) && !TextUtils.isEmpty(wfcVoiceSpnFormat)) {
                // In Wi-Fi Calling mode show SPN+WiFi
                String originalPlmn = plmn.trim();
                plmn = String.format(wfcVoiceSpnFormat, originalPlmn);
            } else if (mCi.getRadioState() == TelephonyManager.RADIO_POWER_OFF) {
                // todo: temporary hack; should have a better fix. This is to avoid using operator
                // name from ServiceState (populated in processIwlanRegistrationInfo()) until
                // wifi calling is actually enabled
                log("updateSpnDisplay: overwriting plmn from " + plmn + " to null as radio " +
                        "state is off");
                plmn = null;
            }

            if (combinedRegState == ServiceState.STATE_OUT_OF_SERVICE) {
                plmn = Resources.getSystem().getText(com.android.internal.R.string
                        .lockscreen_carrier_default).toString();
                if (DBG) {
                    log("updateSpnDisplay: radio is on but out of svc, set plmn='" + plmn + "'");
                }
            }

        }

        notifySpnDisplayUpdate(new CarrierDisplayNameData.Builder()
                .setSpn(spn)
                .setDataSpn(dataSpn)
                .setShowSpn(showSpn)
                .setPlmn(plmn)
                .setShowPlmn(showPlmn)
                .build());
        log("updateSpnDisplayLegacy-");
    }

    protected void setPowerStateToDesired() {
        if (DBG) {
            String tmpLog = "mDeviceShuttingDown=" + mDeviceShuttingDown +
                    ", mDesiredPowerState=" + mDesiredPowerState +
                    ", getRadioState=" + mCi.getRadioState() +
                    ", mPowerOffDelayNeed=" + mPowerOffDelayNeed +
                    ", mAlarmSwitch=" + mAlarmSwitch +
                    ", mRadioDisabledByCarrier=" + mRadioDisabledByCarrier;
            log(tmpLog);
            mRadioPowerLog.log(tmpLog);
        }

        if (mPhone.isPhoneTypeGsm() && mAlarmSwitch) {
            if(DBG) log("mAlarmSwitch == true");
            Context context = mPhone.getContext();
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            am.cancel(mRadioOffIntent);
            mAlarmSwitch = false;
        }

        // If we want it on and it's off, turn it on
        if (mDesiredPowerState && !mRadioDisabledByCarrier
                && mCi.getRadioState() == TelephonyManager.RADIO_POWER_OFF) {
            mCi.setRadioPower(true, null);
        } else if ((!mDesiredPowerState || mRadioDisabledByCarrier) && mCi.getRadioState()
                == TelephonyManager.RADIO_POWER_ON) {
            // If it's on and available and we want it off gracefully
            if (mPhone.isPhoneTypeGsm() && mPowerOffDelayNeed) {
                if (mImsRegistrationOnOff && !mAlarmSwitch) {
                    if(DBG) log("mImsRegistrationOnOff == true");
                    Context context = mPhone.getContext();
                    AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

                    Intent intent = new Intent(ACTION_RADIO_OFF);
                    mRadioOffIntent = PendingIntent.getBroadcast(context, 0, intent, 0);

                    mAlarmSwitch = true;
                    if (DBG) log("Alarm setting");
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            SystemClock.elapsedRealtime() + 3000, mRadioOffIntent);
                } else {
                    powerOffRadioSafely();
                }
            } else {
                powerOffRadioSafely();
            }
        } else if (mDeviceShuttingDown
                && (mCi.getRadioState() != TelephonyManager.RADIO_POWER_UNAVAILABLE)) {
            mCi.requestShutdown(null);
        }
    }

    protected void onUpdateIccAvailability() {
        if (mUiccController == null ) {
            return;
        }

        UiccCardApplication newUiccApplication = getUiccCardApplication();

        if (mUiccApplcation != newUiccApplication) {

            // Remove the EF records that come from UICC
            if (mIccRecords instanceof SIMRecords) {
                mCdnr.updateEfFromUsim(null /* usim */);
            } else if (mIccRecords instanceof RuimRecords) {
                mCdnr.updateEfFromRuim(null /* ruim */);
            }

            if (mUiccApplcation != null) {
                log("Removing stale icc objects.");
                mUiccApplcation.unregisterForReady(this);
                if (mIccRecords != null) {
                    mIccRecords.unregisterForRecordsLoaded(this);
                }
                mIccRecords = null;
                mUiccApplcation = null;
            }
            if (newUiccApplication != null) {
                log("New card found");
                mUiccApplcation = newUiccApplication;
                mIccRecords = mUiccApplcation.getIccRecords();
                if (mPhone.isPhoneTypeGsm()) {
                    mUiccApplcation.registerForReady(this, EVENT_SIM_READY, null);
                    if (mIccRecords != null) {
                        mIccRecords.registerForRecordsLoaded(this, EVENT_SIM_RECORDS_LOADED, null);
                    }
                } else if (mIsSubscriptionFromRuim) {
                    mUiccApplcation.registerForReady(this, EVENT_RUIM_READY, null);
                    if (mIccRecords != null) {
                        mIccRecords.registerForRecordsLoaded(this, EVENT_RUIM_RECORDS_LOADED, null);
                    }
                }
            }
        }
    }

    private void logRoamingChange() {
        mRoamingLog.log(mSS.toString());
    }

    private void logAttachChange() {
        mAttachLog.log(mSS.toString());
    }

    private void logPhoneTypeChange() {
        mPhoneTypeLog.log(Integer.toString(mPhone.getPhoneType()));
    }

    private void logRatChange() {
        mRatLog.log(mSS.toString());
    }

    @UnsupportedAppUsage
    protected final void log(String s) {
        Rlog.d(LOG_TAG, "[" + mPhone.getPhoneId() + "] " + s);
    }

    @UnsupportedAppUsage
    protected final void loge(String s) {
        Rlog.e(LOG_TAG, "[" + mPhone.getPhoneId() + "] " + s);
    }

    /**
     * @return The current GPRS state. IN_SERVICE is the same as "attached"
     * and OUT_OF_SERVICE is the same as detached.
     */
    @UnsupportedAppUsage
    public int getCurrentDataConnectionState() {
        return mSS.getDataRegState();
    }

    /**
     * @return true if phone is camping on a technology (eg UMTS)
     * that could support voice and data simultaneously.
     */
    @UnsupportedAppUsage
    public boolean isConcurrentVoiceAndDataAllowed() {
        if (mSS.getCssIndicator() == 1) {
            // Checking the Concurrent Service Supported flag first for all phone types.
            return true;
        } else if (mPhone.isPhoneTypeGsm()) {
            return (mSS.getRilDataRadioTechnology() >= ServiceState.RIL_RADIO_TECHNOLOGY_UMTS);
        } else {
            return false;
        }
    }

    /** Called when the service state of ImsPhone is changed. */
    public void onImsServiceStateChanged() {
        sendMessage(obtainMessage(EVENT_IMS_SERVICE_STATE_CHANGED));
    }

    public void setImsRegistrationState(boolean registered) {
        log("ImsRegistrationState - registered : " + registered);

        if (mImsRegistrationOnOff && !registered) {
            if (mAlarmSwitch) {
                mImsRegistrationOnOff = registered;

                Context context = mPhone.getContext();
                AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                am.cancel(mRadioOffIntent);
                mAlarmSwitch = false;

                sendMessage(obtainMessage(EVENT_CHANGE_IMS_STATE));
                return;
            }
        }
        mImsRegistrationOnOff = registered;
    }

    public void onImsCapabilityChanged() {
        sendMessage(obtainMessage(EVENT_IMS_CAPABILITY_CHANGED));
    }

    public boolean isRadioOn() {
        return mCi.getRadioState() == TelephonyManager.RADIO_POWER_ON;
    }

    /**
     * A complete "service state" from our perspective is
     * composed of a handful of separate requests to the radio.
     *
     * We make all of these requests at once, but then abandon them
     * and start over again if the radio notifies us that some
     * event has changed
     */
    @UnsupportedAppUsage
    public void pollState() {
        pollState(false);
    }
    /**
     * We insist on polling even if the radio says its off.
     * Used when we get a network changed notification
     * but the radio is off - part of iwlan hack
     */
    private void modemTriggeredPollState() {
        pollState(true);
    }

    public void pollState(boolean modemTriggered) {
        mPollingContext = new int[1];
        mPollingContext[0] = 0;

        log("pollState: modemTriggered=" + modemTriggered);

        switch (mCi.getRadioState()) {
            case TelephonyManager.RADIO_POWER_UNAVAILABLE:
                mNewSS.setStateOutOfService();
                mNewCellIdentity = null;
                setSignalStrengthDefaultValues();
                mNitzState.handleNetworkCountryCodeUnavailable();
                pollStateDone();
                break;

            case TelephonyManager.RADIO_POWER_OFF:
                mNewSS.setStateOff();
                mNewCellIdentity = null;
                setSignalStrengthDefaultValues();
                mNitzState.handleNetworkCountryCodeUnavailable();
                // don't poll when device is shutting down or the poll was not modemTrigged
                // (they sent us new radio data) and current network is not IWLAN
                if (mDeviceShuttingDown ||
                        (!modemTriggered && ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN
                        != mSS.getRilDataRadioTechnology())) {
                    pollStateDone();
                    break;
                }

            default:
                // Issue all poll-related commands at once then count down the responses, which
                // are allowed to arrive out-of-order
                mPollingContext[0]++;
                mCi.getOperator(obtainMessage(EVENT_POLL_STATE_OPERATOR, mPollingContext));

                mPollingContext[0]++;
                mRegStateManagers.get(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                        .requestNetworkRegistrationInfo(NetworkRegistrationInfo.DOMAIN_PS,
                                obtainMessage(EVENT_POLL_STATE_PS_CELLULAR_REGISTRATION,
                                        mPollingContext));

                mPollingContext[0]++;
                mRegStateManagers.get(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                        .requestNetworkRegistrationInfo(NetworkRegistrationInfo.DOMAIN_CS,
                        obtainMessage(EVENT_POLL_STATE_CS_CELLULAR_REGISTRATION, mPollingContext));

                if (mRegStateManagers.get(AccessNetworkConstants.TRANSPORT_TYPE_WLAN) != null) {
                    mPollingContext[0]++;
                    mRegStateManagers.get(AccessNetworkConstants.TRANSPORT_TYPE_WLAN)
                            .requestNetworkRegistrationInfo(NetworkRegistrationInfo.DOMAIN_PS,
                                    obtainMessage(EVENT_POLL_STATE_PS_IWLAN_REGISTRATION,
                                            mPollingContext));
                }

                if (mPhone.isPhoneTypeGsm()) {
                    mPollingContext[0]++;
                    mCi.getNetworkSelectionMode(obtainMessage(
                            EVENT_POLL_STATE_NETWORK_SELECTION_MODE, mPollingContext));
                }
                break;
        }
    }

    private void pollStateDone() {
        if (!mPhone.isPhoneTypeGsm()) {
            updateRoamingState();
        }

        if (Build.IS_DEBUGGABLE && SystemProperties.getBoolean(PROP_FORCE_ROAMING, false)) {
            mNewSS.setVoiceRoaming(true);
            mNewSS.setDataRoaming(true);
        }
        useDataRegStateForDataOnlyDevices();
        processIwlanRegistrationInfo();

        if (Build.IS_DEBUGGABLE && mPhone.mTelephonyTester != null) {
            mPhone.mTelephonyTester.overrideServiceState(mNewSS);
        }

        if (DBG) {
            log("Poll ServiceState done: "
                    + " oldSS=[" + mSS + "] newSS=[" + mNewSS + "]"
                    + " oldMaxDataCalls=" + mMaxDataCalls
                    + " mNewMaxDataCalls=" + mNewMaxDataCalls
                    + " oldReasonDataDenied=" + mReasonDataDenied
                    + " mNewReasonDataDenied=" + mNewReasonDataDenied);
        }

        boolean hasRegistered =
                mSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE
                        && mNewSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE;

        boolean hasDeregistered =
                mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE
                        && mNewSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE;

        boolean hasAirplaneModeOnChanged =
                mSS.getVoiceRegState() != ServiceState.STATE_POWER_OFF
                        && mNewSS.getVoiceRegState() == ServiceState.STATE_POWER_OFF;

        SparseBooleanArray hasDataAttached = new SparseBooleanArray(
                mTransportManager.getAvailableTransports().length);
        SparseBooleanArray hasDataDetached = new SparseBooleanArray(
                mTransportManager.getAvailableTransports().length);
        SparseBooleanArray hasRilDataRadioTechnologyChanged = new SparseBooleanArray(
                mTransportManager.getAvailableTransports().length);
        SparseBooleanArray hasDataRegStateChanged = new SparseBooleanArray(
                mTransportManager.getAvailableTransports().length);
        boolean anyDataRegChanged = false;
        boolean anyDataRatChanged = false;
        for (int transport : mTransportManager.getAvailableTransports()) {
            NetworkRegistrationInfo oldNrs = mSS.getNetworkRegistrationInfo(
                    NetworkRegistrationInfo.DOMAIN_PS, transport);
            NetworkRegistrationInfo newNrs = mNewSS.getNetworkRegistrationInfo(
                    NetworkRegistrationInfo.DOMAIN_PS, transport);

            // If the previously it was not in service, and now it's in service, trigger the
            // attached event. Also if airplane mode was just turned on, and data is already in
            // service, we need to trigger the attached event again so that DcTracker can setup
            // data on all connectable APNs again (because we've already torn down all data
            // connections just before airplane mode turned on)
            boolean changed = (oldNrs == null || !oldNrs.isInService() || hasAirplaneModeOnChanged)
                    && (newNrs != null && newNrs.isInService());
            hasDataAttached.put(transport, changed);

            changed = (oldNrs != null && oldNrs.isInService())
                    && (newNrs == null || !newNrs.isInService());
            hasDataDetached.put(transport, changed);

            int oldRAT = oldNrs != null ? oldNrs.getAccessNetworkTechnology()
                    : TelephonyManager.NETWORK_TYPE_UNKNOWN;
            int newRAT = newNrs != null ? newNrs.getAccessNetworkTechnology()
                    : TelephonyManager.NETWORK_TYPE_UNKNOWN;
            hasRilDataRadioTechnologyChanged.put(transport, oldRAT != newRAT);
            if (oldRAT != newRAT) {
                anyDataRatChanged = true;
            }

            int oldRegState = oldNrs != null ? oldNrs.getRegistrationState()
                    : NetworkRegistrationInfo.REGISTRATION_STATE_UNKNOWN;
            int newRegState = newNrs != null ? newNrs.getRegistrationState()
                    : NetworkRegistrationInfo.REGISTRATION_STATE_UNKNOWN;
            hasDataRegStateChanged.put(transport, oldRegState != newRegState);
            if (oldRegState != newRegState) {
                anyDataRegChanged = true;
            }
        }

        boolean hasVoiceRegStateChanged =
                mSS.getVoiceRegState() != mNewSS.getVoiceRegState();

        boolean hasNrFrequencyRangeChanged =
                mSS.getNrFrequencyRange() != mNewSS.getNrFrequencyRange();

        boolean hasNrStateChanged = isNrStateChanged(
                mSS.getNetworkRegistrationInfo(
                        NetworkRegistrationInfo.DOMAIN_PS, AccessNetworkType.EUTRAN),
                mNewSS.getNetworkRegistrationInfo(
                        NetworkRegistrationInfo.DOMAIN_PS, AccessNetworkType.EUTRAN));

        // TODO: loosen this restriction to exempt fields that are provided through system
        // information; otherwise, we will get false positives when things like the operator
        // alphas are provided later - that's better than missing location changes, but
        // still not ideal.
        boolean hasLocationChanged = !Objects.equals(mNewCellIdentity, mCellIdentity);

        // ratchet the new tech up through its rat family but don't drop back down
        // until cell change or device is OOS
        boolean isDataInService = mNewSS.getDataRegState() == ServiceState.STATE_IN_SERVICE;
        if (isDataInService) {
            mRatRatcheter.ratchet(mSS, mNewSS, hasLocationChanged);
        }

        boolean hasRilVoiceRadioTechnologyChanged =
                mSS.getRilVoiceRadioTechnology() != mNewSS.getRilVoiceRadioTechnology();

        boolean hasChanged = !mNewSS.equals(mSS);

        boolean hasVoiceRoamingOn = !mSS.getVoiceRoaming() && mNewSS.getVoiceRoaming();

        boolean hasVoiceRoamingOff = mSS.getVoiceRoaming() && !mNewSS.getVoiceRoaming();

        boolean hasDataRoamingOn = !mSS.getDataRoaming() && mNewSS.getDataRoaming();

        boolean hasDataRoamingOff = mSS.getDataRoaming() && !mNewSS.getDataRoaming();

        boolean hasRejectCauseChanged = mRejectCode != mNewRejectCode;

        boolean hasCssIndicatorChanged = (mSS.getCssIndicator() != mNewSS.getCssIndicator());

        boolean has4gHandoff = false;
        boolean hasMultiApnSupport = false;
        boolean hasLostMultiApnSupport = false;
        if (mPhone.isPhoneTypeCdmaLte()) {
            has4gHandoff = mNewSS.getDataRegState() == ServiceState.STATE_IN_SERVICE
                    && ((ServiceState.isLte(mSS.getRilDataRadioTechnology())
                    && (mNewSS.getRilDataRadioTechnology()
                    == ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD))
                    ||
                    ((mSS.getRilDataRadioTechnology()
                            == ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD)
                            && ServiceState.isLte(mNewSS.getRilDataRadioTechnology())));

            hasMultiApnSupport = ((ServiceState.isLte(mNewSS.getRilDataRadioTechnology())
                    || (mNewSS.getRilDataRadioTechnology()
                    == ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD))
                    &&
                    (!ServiceState.isLte(mSS.getRilDataRadioTechnology())
                            && (mSS.getRilDataRadioTechnology()
                            != ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD)));

            hasLostMultiApnSupport =
                    ((mNewSS.getRilDataRadioTechnology()
                            >= ServiceState.RIL_RADIO_TECHNOLOGY_IS95A)
                            && (mNewSS.getRilDataRadioTechnology()
                            <= ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A));
        }

        if (DBG) {
            log("pollStateDone:"
                    + " hasRegistered = " + hasRegistered
                    + " hasDeregistered = " + hasDeregistered
                    + " hasDataAttached = " + hasDataAttached
                    + " hasDataDetached = " + hasDataDetached
                    + " hasDataRegStateChanged = " + hasDataRegStateChanged
                    + " hasRilVoiceRadioTechnologyChanged = " + hasRilVoiceRadioTechnologyChanged
                    + " hasRilDataRadioTechnologyChanged = " + hasRilDataRadioTechnologyChanged
                    + " hasChanged = " + hasChanged
                    + " hasVoiceRoamingOn = " + hasVoiceRoamingOn
                    + " hasVoiceRoamingOff = " + hasVoiceRoamingOff
                    + " hasDataRoamingOn =" + hasDataRoamingOn
                    + " hasDataRoamingOff = " + hasDataRoamingOff
                    + " hasLocationChanged = " + hasLocationChanged
                    + " has4gHandoff = " + has4gHandoff
                    + " hasMultiApnSupport = " + hasMultiApnSupport
                    + " hasLostMultiApnSupport = " + hasLostMultiApnSupport
                    + " hasCssIndicatorChanged = " + hasCssIndicatorChanged
                    + " hasNrFrequencyRangeChanged = " + hasNrFrequencyRangeChanged
                    + " hasNrStateChanged = " + hasNrStateChanged
                    + " hasAirplaneModeOnlChanged = " + hasAirplaneModeOnChanged);
        }

        // Add an event log when connection state changes
        if (hasVoiceRegStateChanged || anyDataRegChanged) {
            EventLog.writeEvent(mPhone.isPhoneTypeGsm() ? EventLogTags.GSM_SERVICE_STATE_CHANGE :
                            EventLogTags.CDMA_SERVICE_STATE_CHANGE,
                    mSS.getVoiceRegState(), mSS.getDataRegState(),
                    mNewSS.getVoiceRegState(), mNewSS.getDataRegState());
        }

        if (mPhone.isPhoneTypeGsm()) {
            // Add an event log when network type switched
            // TODO: we may add filtering to reduce the event logged,
            // i.e. check preferred network setting, only switch to 2G, etc
            if (hasRilVoiceRadioTechnologyChanged) {
                int cid = getCidFromCellIdentity(mNewCellIdentity);
                // NOTE: this code was previously located after mSS and mNewSS are swapped, so
                // existing logs were incorrectly using the new state for "network_from"
                // and STATE_OUT_OF_SERVICE for "network_to". To avoid confusion, use a new log tag
                // to record the correct states.
                EventLog.writeEvent(EventLogTags.GSM_RAT_SWITCHED_NEW, cid,
                        mSS.getRilVoiceRadioTechnology(),
                        mNewSS.getRilVoiceRadioTechnology());
                if (DBG) {
                    log("RAT switched "
                            + ServiceState.rilRadioTechnologyToString(
                            mSS.getRilVoiceRadioTechnology())
                            + " -> "
                            + ServiceState.rilRadioTechnologyToString(
                            mNewSS.getRilVoiceRadioTechnology()) + " at cell " + cid);
                }
            }

            if (hasCssIndicatorChanged) {
                mPhone.notifyDataConnection();
            }

            mReasonDataDenied = mNewReasonDataDenied;
            mMaxDataCalls = mNewMaxDataCalls;
            mRejectCode = mNewRejectCode;
        }

        ServiceState oldMergedSS = new ServiceState(mPhone.getServiceState());

        // swap mSS and mNewSS to put new state in mSS
        ServiceState tss = mSS;
        mSS = mNewSS;
        mNewSS = tss;
        // clean slate for next time
        mNewSS.setStateOutOfService();

        // swap mCellIdentity and mNewCellIdentity to put new state in mCellIdentity
        CellIdentity tempCellId = mCellIdentity;
        mCellIdentity = mNewCellIdentity;
        mNewCellIdentity = tempCellId;

        if (hasRilVoiceRadioTechnologyChanged) {
            updatePhoneObject();
        }

        TelephonyManager tm = (TelephonyManager) mPhone.getContext().getSystemService(
                Context.TELEPHONY_SERVICE);
        if (anyDataRatChanged) {
            tm.setDataNetworkTypeForPhone(mPhone.getPhoneId(), mSS.getRilDataRadioTechnology());
            StatsLog.write(StatsLog.MOBILE_RADIO_TECHNOLOGY_CHANGED,
                    ServiceState.rilRadioTechnologyToNetworkType(
                            mSS.getRilDataRadioTechnology()), mPhone.getPhoneId());
        }

        if (hasRegistered) {
            mNetworkAttachedRegistrants.notifyRegistrants();
            mNitzState.handleNetworkAvailable();
        }

        if (hasDeregistered) {
            mNetworkDetachedRegistrants.notifyRegistrants();
        }

        if (hasRejectCauseChanged) {
            setNotification(CS_REJECT_CAUSE_ENABLED);
        }

        if (hasChanged) {
            updateSpnDisplay();

            tm.setNetworkOperatorNameForPhone(mPhone.getPhoneId(), mSS.getOperatorAlpha());
            String operatorNumeric = mSS.getOperatorNumeric();

            if (!mPhone.isPhoneTypeGsm()) {
                // try to fix the invalid Operator Numeric
                if (isInvalidOperatorNumeric(operatorNumeric)) {
                    int sid = mSS.getCdmaSystemId();
                    operatorNumeric = fixUnknownMcc(operatorNumeric, sid);
                }
            }

            tm.setNetworkOperatorNumericForPhone(mPhone.getPhoneId(), operatorNumeric);

            if (isInvalidOperatorNumeric(operatorNumeric)) {
                if (DBG) log("operatorNumeric " + operatorNumeric + " is invalid");
                // Passing empty string is important for the first update. The initial value of
                // operator numeric in locale tracker is null. The async update will allow getting
                // cell info from the modem instead of using the cached one.
                mLocaleTracker.updateOperatorNumeric("");
            } else if (mSS.getRilDataRadioTechnology() != ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN) {
                // If the device is on IWLAN, modems manufacture a ServiceState with the MCC/MNC of
                // the SIM as if we were talking to towers. Telephony code then uses that with
                // mccTable to suggest a timezone. We shouldn't do that if the MCC/MNC is from IWLAN

                // Update IDD.
                if (!mPhone.isPhoneTypeGsm()) {
                    setOperatorIdd(operatorNumeric);
                }

                mLocaleTracker.updateOperatorNumeric(operatorNumeric);
            }

            tm.setNetworkRoamingForPhone(mPhone.getPhoneId(),
                    mPhone.isPhoneTypeGsm() ? mSS.getVoiceRoaming() :
                            (mSS.getVoiceRoaming() || mSS.getDataRoaming()));

            setRoamingType(mSS);
            log("Broadcasting ServiceState : " + mSS);
            // notify using PhoneStateListener and the legacy intent ACTION_SERVICE_STATE_CHANGED
            // notify service state changed only if the merged service state is changed.
            if (!oldMergedSS.equals(mPhone.getServiceState())) {
                mPhone.notifyServiceStateChanged(mPhone.getServiceState());
            }

            // insert into ServiceStateProvider. This will trigger apps to wake through JobScheduler
            mPhone.getContext().getContentResolver()
                    .insert(getUriForSubscriptionId(mPhone.getSubId()),
                            getContentValuesForServiceState(mSS));

            TelephonyMetrics.getInstance().writeServiceStateChanged(mPhone.getPhoneId(), mSS);
        }

        boolean shouldLogAttachedChange = false;
        boolean shouldLogRatChange = false;

        if (hasRegistered || hasDeregistered) {
            shouldLogAttachedChange = true;
        }

        if (has4gHandoff) {
            mAttachedRegistrants.get(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                    .notifyRegistrants();
            shouldLogAttachedChange = true;
        }

        if (hasRilVoiceRadioTechnologyChanged) {
            shouldLogRatChange = true;
            notifySignalStrength();
        }

        for (int transport : mTransportManager.getAvailableTransports()) {
            if (hasRilDataRadioTechnologyChanged.get(transport)) {
                shouldLogRatChange = true;
                notifySignalStrength();
            }

            if (hasDataRegStateChanged.get(transport)
                    || hasRilDataRadioTechnologyChanged.get(transport)) {
                notifyDataRegStateRilRadioTechnologyChanged(transport);
                mPhone.notifyDataConnection();
            }

            if (hasDataAttached.get(transport)) {
                shouldLogAttachedChange = true;
                if (mAttachedRegistrants.get(transport) != null) {
                    mAttachedRegistrants.get(transport).notifyRegistrants();
                }
            }
            if (hasDataDetached.get(transport)) {
                shouldLogAttachedChange = true;
                if (mDetachedRegistrants.get(transport) != null) {
                    mDetachedRegistrants.get(transport).notifyRegistrants();
                }
            }
        }

        if (shouldLogAttachedChange) {
            logAttachChange();
        }
        if (shouldLogRatChange) {
            logRatChange();
        }

        if (hasVoiceRegStateChanged || hasRilVoiceRadioTechnologyChanged) {
            notifyVoiceRegStateRilRadioTechnologyChanged();
        }

        if (hasVoiceRoamingOn || hasVoiceRoamingOff || hasDataRoamingOn || hasDataRoamingOff) {
            logRoamingChange();
        }

        if (hasVoiceRoamingOn) {
            mVoiceRoamingOnRegistrants.notifyRegistrants();
        }

        if (hasVoiceRoamingOff) {
            mVoiceRoamingOffRegistrants.notifyRegistrants();
        }

        if (hasDataRoamingOn) {
            mDataRoamingOnRegistrants.notifyRegistrants();
        }

        if (hasDataRoamingOff) {
            mDataRoamingOffRegistrants.notifyRegistrants();
        }

        if (hasLocationChanged) {
            mPhone.notifyLocationChanged(getCellLocation());
        }

        if (mPhone.isPhoneTypeGsm()) {
            if (!isGprsConsistent(mSS.getDataRegState(), mSS.getVoiceRegState())) {
                if (!mStartedGprsRegCheck && !mReportedGprsNoReg) {
                    mStartedGprsRegCheck = true;

                    int check_period = Settings.Global.getInt(
                            mPhone.getContext().getContentResolver(),
                            Settings.Global.GPRS_REGISTER_CHECK_PERIOD_MS,
                            DEFAULT_GPRS_CHECK_PERIOD_MILLIS);
                    sendMessageDelayed(obtainMessage(EVENT_CHECK_REPORT_GPRS),
                            check_period);
                }
            } else {
                mReportedGprsNoReg = false;
            }
        }
    }

    private String getOperatorNameFromEri() {
        String eriText = null;
        if (mPhone.isPhoneTypeCdma()) {
            if ((mCi.getRadioState() == TelephonyManager.RADIO_POWER_ON)
                    && (!mIsSubscriptionFromRuim)) {
                // Now the Phone sees the new ServiceState so it can get the new ERI text
                if (mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE) {
                    eriText = mPhone.getCdmaEriText();
                } else {
                    // Note that ServiceState.STATE_OUT_OF_SERVICE is valid used for
                    // mRegistrationState 0,2,3 and 4
                    eriText = mPhone.getContext().getText(
                            com.android.internal.R.string.roamingTextSearching).toString();
                }
            }
        } else if (mPhone.isPhoneTypeCdmaLte()) {
            boolean hasBrandOverride = mUiccController.getUiccCard(getPhoneId()) != null &&
                    mUiccController.getUiccCard(getPhoneId()).getOperatorBrandOverride() != null;
            if (!hasBrandOverride && (mCi.getRadioState() == TelephonyManager.RADIO_POWER_ON)
                    && (mEriManager.isEriFileLoaded())
                    && (!ServiceState.isLte(mSS.getRilVoiceRadioTechnology())
                    || mPhone.getContext().getResources().getBoolean(com.android.internal.R
                    .bool.config_LTE_eri_for_network_name))) {
                // Only when CDMA is in service, ERI will take effect
                eriText = mSS.getOperatorAlpha();
                // Now the Phone sees the new ServiceState so it can get the new ERI text
                if (mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE) {
                    eriText = mPhone.getCdmaEriText();
                } else if (mSS.getVoiceRegState() == ServiceState.STATE_POWER_OFF) {
                    eriText = getServiceProviderName();
                    if (TextUtils.isEmpty(eriText)) {
                        // Sets operator alpha property by retrieving from
                        // build-time system property
                        eriText = SystemProperties.get("ro.cdma.home.operator.alpha");
                    }
                } else if (mSS.getDataRegState() != ServiceState.STATE_IN_SERVICE) {
                    // Note that ServiceState.STATE_OUT_OF_SERVICE is valid used
                    // for mRegistrationState 0,2,3 and 4
                    eriText = mPhone.getContext()
                            .getText(com.android.internal.R.string.roamingTextSearching).toString();
                }
            }

            if (mUiccApplcation != null && mUiccApplcation.getState() == AppState.APPSTATE_READY &&
                    mIccRecords != null && getCombinedRegState(mSS) == ServiceState.STATE_IN_SERVICE
                    && !ServiceState.isLte(mSS.getRilVoiceRadioTechnology())) {
                // SIM is found on the device. If ERI roaming is OFF, and SID/NID matches
                // one configured in SIM, use operator name from CSIM record. Note that ERI, SID,
                // and NID are CDMA only, not applicable to LTE.
                boolean showSpn =
                        ((RuimRecords) mIccRecords).getCsimSpnDisplayCondition();
                int iconIndex = mSS.getCdmaEriIconIndex();

                if (showSpn && (iconIndex == EriInfo.ROAMING_INDICATOR_OFF)
                        && isInHomeSidNid(mSS.getCdmaSystemId(), mSS.getCdmaNetworkId())
                        && mIccRecords != null) {
                    eriText = getServiceProviderName();
                }
            }
        }
        return eriText;
    }

    /**
     * Get the service provider name with highest priority among various source.
     * @return service provider name.
     */
    public String getServiceProviderName() {
        // BrandOverride has higher priority than the carrier config
        String operatorBrandOverride = getOperatorBrandOverride();
        if (!TextUtils.isEmpty(operatorBrandOverride)) {
            return operatorBrandOverride;
        }

        String carrierName = mIccRecords != null ? mIccRecords.getServiceProviderName() : "";
        PersistableBundle config = getCarrierConfig();
        if (config.getBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL)
                || TextUtils.isEmpty(carrierName)) {
            return config.getString(CarrierConfigManager.KEY_CARRIER_NAME_STRING);
        }

        return carrierName;
    }

    /**
     * Get the resolved carrier name display condition bitmask.
     *
     * <p> Show service provider name if only if {@link #CARRIER_NAME_DISPLAY_BITMASK_SHOW_SPN}
     * is set.
     *
     * <p> Show PLMN network name if only if {@link #CARRIER_NAME_DISPLAY_BITMASK_SHOW_PLMN} is set.
     *
     * @param ss service state
     * @return carrier name display bitmask.
     */
    @CarrierNameDisplayBitmask
    public int getCarrierNameDisplayBitmask(ServiceState ss) {
        PersistableBundle config = getCarrierConfig();
        if (!TextUtils.isEmpty(getOperatorBrandOverride())) {
            // If the operator has been overridden, all PLMNs will be considered HOME PLMNs, only
            // show SPN.
            return CARRIER_NAME_DISPLAY_BITMASK_SHOW_SPN;
        } else if (TextUtils.isEmpty(getServiceProviderName())) {
            // If SPN is null or empty, we should show plmn.
            // This is a hack from IccRecords#getServiceProviderName().
            return CARRIER_NAME_DISPLAY_BITMASK_SHOW_PLMN;
        } else {
            boolean useRoamingFromServiceState = config.getBoolean(
                    CarrierConfigManager.KEY_SPN_DISPLAY_RULE_USE_ROAMING_FROM_SERVICE_STATE_BOOL);
            int carrierDisplayNameConditionFromSim =
                    mIccRecords == null ? 0 : mIccRecords.getCarrierNameDisplayCondition();

            boolean isRoaming;
            if (useRoamingFromServiceState) {
                isRoaming = ss.getRoaming();
            } else {
                String[] hplmns = mIccRecords != null ? mIccRecords.getHomePlmns() : null;
                isRoaming = !ArrayUtils.contains(hplmns, ss.getOperatorNumeric());
            }
            int rule;
            if (isRoaming) {
                // Show PLMN when roaming.
                rule = CARRIER_NAME_DISPLAY_BITMASK_SHOW_PLMN;

                // Check if show SPN is required when roaming.
                if ((carrierDisplayNameConditionFromSim
                        & CARRIER_NAME_DISPLAY_CONDITION_BITMASK_SPN)
                        == CARRIER_NAME_DISPLAY_CONDITION_BITMASK_SPN) {
                    rule |= CARRIER_NAME_DISPLAY_BITMASK_SHOW_SPN;
                }
            } else {
                // Show SPN when not roaming.
                rule = CARRIER_NAME_DISPLAY_BITMASK_SHOW_SPN;

                // Check if show PLMN is required when not roaming.
                if ((carrierDisplayNameConditionFromSim
                        & CARRIER_NAME_DISPLAY_CONDITION_BITMASK_PLMN)
                        == CARRIER_NAME_DISPLAY_CONDITION_BITMASK_PLMN) {
                    rule |= CARRIER_NAME_DISPLAY_BITMASK_SHOW_PLMN;
                }
            }
            return rule;
        }
    }

    private String getOperatorBrandOverride() {
        UiccCard card = mPhone.getUiccCard();
        if (card == null) return null;
        UiccProfile profile = card.getUiccProfile();
        if (profile == null) return null;
        return profile.getOperatorBrandOverride();
    }

    /**
     * Check whether the specified SID and NID pair appears in the HOME SID/NID list
     * read from NV or SIM.
     *
     * @return true if provided sid/nid pair belongs to operator's home network.
     */
    @UnsupportedAppUsage
    private boolean isInHomeSidNid(int sid, int nid) {
        // if SID/NID is not available, assume this is home network.
        if (isSidsAllZeros()) return true;

        // length of SID/NID shold be same
        if (mHomeSystemId.length != mHomeNetworkId.length) return true;

        if (sid == 0) return true;

        for (int i = 0; i < mHomeSystemId.length; i++) {
            // Use SID only if NID is a reserved value.
            // SID 0 and NID 0 and 65535 are reserved. (C.0005 2.6.5.2)
            if ((mHomeSystemId[i] == sid) &&
                    ((mHomeNetworkId[i] == 0) || (mHomeNetworkId[i] == 65535) ||
                            (nid == 0) || (nid == 65535) || (mHomeNetworkId[i] == nid))) {
                return true;
            }
        }
        // SID/NID are not in the list. So device is not in home network
        return false;
    }

    @UnsupportedAppUsage
    protected void setOperatorIdd(String operatorNumeric) {
        // Retrieve the current country information
        // with the MCC got from opeatorNumeric.
        String idd = mHbpcdUtils.getIddByMcc(
                Integer.parseInt(operatorNumeric.substring(0,3)));
        if (idd != null && !idd.isEmpty()) {
            mPhone.setGlobalSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_IDP_STRING,
                    idd);
        } else {
            // use default "+", since we don't know the current IDP
            mPhone.setGlobalSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_IDP_STRING, "+");
        }
    }

    @UnsupportedAppUsage
    private boolean isInvalidOperatorNumeric(String operatorNumeric) {
        return operatorNumeric == null || operatorNumeric.length() < 5 ||
                operatorNumeric.startsWith(INVALID_MCC);
    }

    @UnsupportedAppUsage
    private String fixUnknownMcc(String operatorNumeric, int sid) {
        if (sid <= 0) {
            // no cdma information is available, do nothing
            return operatorNumeric;
        }

        // resolve the mcc from sid;
        // if mNitzState.getSavedTimeZoneId() is null, TimeZone would get the default timeZone,
        // and the mNitzState.fixTimeZone() couldn't help, because it depends on operator Numeric;
        // if the sid is conflict and timezone is unavailable, the mcc may be not right.
        boolean isNitzTimeZone;
        TimeZone tzone;
        if (mNitzState.getSavedTimeZoneId() != null) {
            tzone = TimeZone.getTimeZone(mNitzState.getSavedTimeZoneId());
            isNitzTimeZone = true;
        } else {
            NitzData lastNitzData = mNitzState.getCachedNitzData();
            if (lastNitzData == null) {
                tzone = null;
            } else {
                tzone = TimeZoneLookupHelper.guessZoneByNitzStatic(lastNitzData);
                if (ServiceStateTracker.DBG) {
                    log("fixUnknownMcc(): guessNitzTimeZone returned "
                            + (tzone == null ? tzone : tzone.getID()));
                }
            }
            isNitzTimeZone = false;
        }

        int utcOffsetHours = 0;
        if (tzone != null) {
            utcOffsetHours = tzone.getRawOffset() / MS_PER_HOUR;
        }

        NitzData nitzData = mNitzState.getCachedNitzData();
        boolean isDst = nitzData != null && nitzData.isDst();
        int mcc = mHbpcdUtils.getMcc(sid, utcOffsetHours, (isDst ? 1 : 0), isNitzTimeZone);
        if (mcc > 0) {
            operatorNumeric = Integer.toString(mcc) + DEFAULT_MNC;
        }
        return operatorNumeric;
    }

    /**
     * Check if GPRS got registered while voice is registered.
     *
     * @param dataRegState i.e. CGREG in GSM
     * @param voiceRegState i.e. CREG in GSM
     * @return false if device only register to voice but not gprs
     */
    @UnsupportedAppUsage
    private boolean isGprsConsistent(int dataRegState, int voiceRegState) {
        return !((voiceRegState == ServiceState.STATE_IN_SERVICE) &&
                (dataRegState != ServiceState.STATE_IN_SERVICE));
    }

    /** convert ServiceState registration code
     * to service state */
    private int regCodeToServiceState(int code) {
        switch (code) {
            case NetworkRegistrationInfo.REGISTRATION_STATE_HOME:
            case NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING:
                return ServiceState.STATE_IN_SERVICE;
            default:
                return ServiceState.STATE_OUT_OF_SERVICE;
        }
    }

    /**
     * code is registration state 0-5 from TS 27.007 7.2
     * returns true if registered roam, false otherwise
     */
    private boolean regCodeIsRoaming (int code) {
        return NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING == code;
    }

    private boolean isSameOperatorNameFromSimAndSS(ServiceState s) {
        String spn = ((TelephonyManager) mPhone.getContext().
                getSystemService(Context.TELEPHONY_SERVICE)).
                getSimOperatorNameForPhone(getPhoneId());

        // NOTE: in case of RUIM we should completely ignore the ERI data file and
        // mOperatorAlphaLong is set from RIL_REQUEST_OPERATOR response 0 (alpha ONS)
        String onsl = s.getOperatorAlphaLong();
        String onss = s.getOperatorAlphaShort();

        boolean equalsOnsl = !TextUtils.isEmpty(spn) && spn.equalsIgnoreCase(onsl);
        boolean equalsOnss = !TextUtils.isEmpty(spn) && spn.equalsIgnoreCase(onss);

        return (equalsOnsl || equalsOnss);
    }

    /**
     * Set roaming state if operator mcc is the same as sim mcc
     * and ons is not different from spn
     *
     * @param s ServiceState hold current ons
     * @return true if same operator
     */
    private boolean isSameNamedOperators(ServiceState s) {
        return currentMccEqualsSimMcc(s) && isSameOperatorNameFromSimAndSS(s);
    }

    /**
     * Compare SIM MCC with Operator MCC
     *
     * @param s ServiceState hold current ons
     * @return true if both are same
     */
    private boolean currentMccEqualsSimMcc(ServiceState s) {
        String simNumeric = ((TelephonyManager) mPhone.getContext().
                getSystemService(Context.TELEPHONY_SERVICE)).
                getSimOperatorNumericForPhone(getPhoneId());
        String operatorNumeric = s.getOperatorNumeric();
        boolean equalsMcc = true;

        try {
            equalsMcc = simNumeric.substring(0, 3).
                    equals(operatorNumeric.substring(0, 3));
        } catch (Exception e){
        }
        return equalsMcc;
    }

    /**
     * Do not set roaming state in case of oprators considered non-roaming.
     *
     * Can use mcc or mcc+mnc as item of
     * {@link CarrierConfigManager#KEY_NON_ROAMING_OPERATOR_STRING_ARRAY}.
     * For example, 302 or 21407. If mcc or mcc+mnc match with operator,
     * don't set roaming state.
     *
     * @param s ServiceState hold current ons
     * @return false for roaming state set
     */
    private boolean isOperatorConsideredNonRoaming(ServiceState s) {
        String operatorNumeric = s.getOperatorNumeric();

        PersistableBundle config = getCarrierConfig();
        String[] numericArray = config.getStringArray(
                CarrierConfigManager.KEY_NON_ROAMING_OPERATOR_STRING_ARRAY);

        if (ArrayUtils.isEmpty(numericArray) || operatorNumeric == null) {
            return false;
        }

        for (String numeric : numericArray) {
            if (!TextUtils.isEmpty(numeric) && operatorNumeric.startsWith(numeric)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOperatorConsideredRoaming(ServiceState s) {
        String operatorNumeric = s.getOperatorNumeric();
        PersistableBundle config = getCarrierConfig();
        String[] numericArray = config.getStringArray(
                CarrierConfigManager.KEY_ROAMING_OPERATOR_STRING_ARRAY);
        if (ArrayUtils.isEmpty(numericArray) || operatorNumeric == null) {
            return false;
        }

        for (String numeric : numericArray) {
            if (!TextUtils.isEmpty(numeric) && operatorNumeric.startsWith(numeric)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Set restricted state based on the OnRestrictedStateChanged notification
     * If any voice or packet restricted state changes, trigger a UI
     * notification and notify registrants when sim is ready.
     *
     * @param ar an int value of RIL_RESTRICTED_STATE_*
     */
    private void onRestrictedStateChanged(AsyncResult ar) {
        RestrictedState newRs = new RestrictedState();

        if (DBG) log("onRestrictedStateChanged: E rs "+ mRestrictedState);

        if (ar.exception == null && ar.result != null) {
            int state = (int)ar.result;

            newRs.setCsEmergencyRestricted(
                    ((state & RILConstants.RIL_RESTRICTED_STATE_CS_EMERGENCY) != 0) ||
                            ((state & RILConstants.RIL_RESTRICTED_STATE_CS_ALL) != 0) );
            //ignore the normal call and data restricted state before SIM READY
            if (mUiccApplcation != null && mUiccApplcation.getState() == AppState.APPSTATE_READY) {
                newRs.setCsNormalRestricted(
                        ((state & RILConstants.RIL_RESTRICTED_STATE_CS_NORMAL) != 0) ||
                                ((state & RILConstants.RIL_RESTRICTED_STATE_CS_ALL) != 0) );
                newRs.setPsRestricted(
                        (state & RILConstants.RIL_RESTRICTED_STATE_PS_ALL)!= 0);
            }

            if (DBG) log("onRestrictedStateChanged: new rs "+ newRs);

            if (!mRestrictedState.isPsRestricted() && newRs.isPsRestricted()) {
                mPsRestrictEnabledRegistrants.notifyRegistrants();
                setNotification(PS_ENABLED);
            } else if (mRestrictedState.isPsRestricted() && !newRs.isPsRestricted()) {
                mPsRestrictDisabledRegistrants.notifyRegistrants();
                setNotification(PS_DISABLED);
            }

            /**
             * There are two kind of cs restriction, normal and emergency. So
             * there are 4 x 4 combinations in current and new restricted states
             * and we only need to notify when state is changed.
             */
            if (mRestrictedState.isCsRestricted()) {
                if (!newRs.isAnyCsRestricted()) {
                    // remove all restriction
                    setNotification(CS_DISABLED);
                } else if (!newRs.isCsNormalRestricted()) {
                    // remove normal restriction
                    setNotification(CS_EMERGENCY_ENABLED);
                } else if (!newRs.isCsEmergencyRestricted()) {
                    // remove emergency restriction
                    setNotification(CS_NORMAL_ENABLED);
                }
            } else if (mRestrictedState.isCsEmergencyRestricted() &&
                    !mRestrictedState.isCsNormalRestricted()) {
                if (!newRs.isAnyCsRestricted()) {
                    // remove all restriction
                    setNotification(CS_DISABLED);
                } else if (newRs.isCsRestricted()) {
                    // enable all restriction
                    setNotification(CS_ENABLED);
                } else if (newRs.isCsNormalRestricted()) {
                    // remove emergency restriction and enable normal restriction
                    setNotification(CS_NORMAL_ENABLED);
                }
            } else if (!mRestrictedState.isCsEmergencyRestricted() &&
                    mRestrictedState.isCsNormalRestricted()) {
                if (!newRs.isAnyCsRestricted()) {
                    // remove all restriction
                    setNotification(CS_DISABLED);
                } else if (newRs.isCsRestricted()) {
                    // enable all restriction
                    setNotification(CS_ENABLED);
                } else if (newRs.isCsEmergencyRestricted()) {
                    // remove normal restriction and enable emergency restriction
                    setNotification(CS_EMERGENCY_ENABLED);
                }
            } else {
                if (newRs.isCsRestricted()) {
                    // enable all restriction
                    setNotification(CS_ENABLED);
                } else if (newRs.isCsEmergencyRestricted()) {
                    // enable emergency restriction
                    setNotification(CS_EMERGENCY_ENABLED);
                } else if (newRs.isCsNormalRestricted()) {
                    // enable normal restriction
                    setNotification(CS_NORMAL_ENABLED);
                }
            }

            mRestrictedState = newRs;
        }
        log("onRestrictedStateChanged: X rs "+ mRestrictedState);
    }

    /**
     * Get CellLocation from the ServiceState if available or guess from cached CellInfo
     *
     * Get the CellLocation by first checking if ServiceState has a current CID. If so
     * then return that info. Otherwise, check the latest List<CellInfo> and return the first GSM or
     * WCDMA result that appears. If no GSM or WCDMA results, then return an LTE result. The
     * behavior is kept consistent for backwards compatibility; (do not apply logic to determine
     * why the behavior is this way).
     *
     * @return the current cell location if known or a non-null "empty" cell location
     */
    public CellLocation getCellLocation() {
        if (mCellIdentity != null) return mCellIdentity.asCellLocation();

        CellLocation cl = getCellLocationFromCellInfo(getAllCellInfo());
        if (cl != null) return cl;

        return mPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA
                ? new CdmaCellLocation() : new GsmCellLocation();
    }

    /**
     * Get CellLocation from the ServiceState if available or guess from CellInfo
     *
     * Get the CellLocation by first checking if ServiceState has a current CID. If so
     * then return that info. Otherwise, query AllCellInfo and return the first GSM or
     * WCDMA result that appears. If no GSM or WCDMA results, then return an LTE result.
     * The behavior is kept consistent for backwards compatibility; (do not apply logic
     * to determine why the behavior is this way).
     *
     * @param workSource calling WorkSource
     * @param rspMsg the response message which must be non-null
     */
    public void requestCellLocation(WorkSource workSource, Message rspMsg) {
        if (mCellIdentity != null) {
            AsyncResult.forMessage(rspMsg, mCellIdentity.asCellLocation(), null);
            rspMsg.sendToTarget();
            return;
        }

        Message cellLocRsp = obtainMessage(EVENT_CELL_LOCATION_RESPONSE, rspMsg);
        requestAllCellInfo(workSource, cellLocRsp);
    }

    /* Find and return a CellLocation from CellInfo
     *
     * This method returns the first GSM or WCDMA result that appears in List<CellInfo>. If no GSM
     * or  WCDMA results are found, then it returns an LTE result. The behavior is kept consistent
     * for backwards compatibility; (do not apply logic to determine why the behavior is this way).
     *
     * @return the current cell location from CellInfo or null
     */
    private static CellLocation getCellLocationFromCellInfo(List<CellInfo> info) {
        CellLocation cl = null;
        if (info != null && info.size() > 0) {
            CellIdentity fallbackLteCid = null; // We prefer not to use LTE
            for (CellInfo ci : info) {
                CellIdentity c = ci.getCellIdentity();
                if (c instanceof CellIdentityLte && fallbackLteCid == null) {
                    if (getCidFromCellIdentity(c) != -1) fallbackLteCid = c;
                    continue;
                }
                if (getCidFromCellIdentity(c) != -1) {
                    cl = c.asCellLocation();
                    break;
                }
            }
            if (cl == null && fallbackLteCid != null) {
                cl = fallbackLteCid.asCellLocation();
            }
        }
        return cl;
    }

    /**
     * nitzReceiveTime is time_t that the NITZ time was posted
     */
    private void setTimeFromNITZString(String nitzString, long nitzReceiveTime) {
        long start = SystemClock.elapsedRealtime();
        if (DBG) {
            Rlog.d(LOG_TAG, "NITZ: " + nitzString + "," + nitzReceiveTime
                    + " start=" + start + " delay=" + (start - nitzReceiveTime));
        }
        NitzData newNitzData = NitzData.parse(nitzString);
        if (newNitzData != null) {
            try {
                TimestampedValue<NitzData> nitzSignal =
                        new TimestampedValue<>(nitzReceiveTime, newNitzData);
                mNitzState.handleNitzReceived(nitzSignal);
            } finally {
                if (DBG) {
                    long end = SystemClock.elapsedRealtime();
                    Rlog.d(LOG_TAG, "NITZ: end=" + end + " dur=" + (end - start));
                }
            }
        }
    }

    /**
     * Cancels all notifications posted to NotificationManager for this subId. These notifications
     * for restricted state and rejection cause for cs registration are no longer valid after the
     * SIM has been removed.
     */
    private void cancelAllNotifications() {
        if (DBG) log("cancelAllNotifications: mPrevSubId=" + mPrevSubId);
        NotificationManager notificationManager = (NotificationManager)
                mPhone.getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (SubscriptionManager.isValidSubscriptionId(mPrevSubId)) {
            notificationManager.cancel(Integer.toString(mPrevSubId), PS_NOTIFICATION);
            notificationManager.cancel(Integer.toString(mPrevSubId), CS_NOTIFICATION);
            notificationManager.cancel(Integer.toString(mPrevSubId), CS_REJECT_CAUSE_NOTIFICATION);
        }
    }

    /**
     * Post a notification to NotificationManager for restricted state and
     * rejection cause for cs registration
     *
     * @param notifyType is one state of PS/CS_*_ENABLE/DISABLE
     */
    @VisibleForTesting
    public void setNotification(int notifyType) {
        if (DBG) log("setNotification: create notification " + notifyType);

        if (!SubscriptionManager.isValidSubscriptionId(mSubId)) {
            // notifications are posted per-sub-id, so return if current sub-id is invalid
            loge("cannot setNotification on invalid subid mSubId=" + mSubId);
            return;
        }
        Context context = mPhone.getContext();

        SubscriptionInfo info = mSubscriptionController
                .getActiveSubscriptionInfo(mPhone.getSubId(), context.getOpPackageName());

        //if subscription is part of a group and non-primary, suppress all notifications
        if (info == null || (info.isOpportunistic() && info.getGroupUuid() != null)) {
            log("cannot setNotification on invisible subid mSubId=" + mSubId);
            return;
        }

        // Needed because sprout RIL sends these when they shouldn't?
        boolean isSetNotification = context.getResources().getBoolean(
                com.android.internal.R.bool.config_user_notification_of_restrictied_mobile_access);
        if (!isSetNotification) {
            if (DBG) log("Ignore all the notifications");
            return;
        }

        boolean autoCancelCsRejectNotification = false;

        PersistableBundle bundle = getCarrierConfig();
        boolean disableVoiceBarringNotification = bundle.getBoolean(
                CarrierConfigManager.KEY_DISABLE_VOICE_BARRING_NOTIFICATION_BOOL, false);
        if (disableVoiceBarringNotification && (notifyType == CS_ENABLED
                || notifyType == CS_NORMAL_ENABLED
                || notifyType == CS_EMERGENCY_ENABLED)) {
            if (DBG) log("Voice/emergency call barred notification disabled");
            return;
        }
        autoCancelCsRejectNotification = bundle.getBoolean(
                CarrierConfigManager.KEY_AUTO_CANCEL_CS_REJECT_NOTIFICATION, false);

        CharSequence details = "";
        CharSequence title = "";
        int notificationId = CS_NOTIFICATION;
        int icon = com.android.internal.R.drawable.stat_sys_warning;

        final boolean multipleSubscriptions = (((TelephonyManager) mPhone.getContext()
                  .getSystemService(Context.TELEPHONY_SERVICE)).getPhoneCount() > 1);
        final int simNumber = mSubscriptionController.getSlotIndex(mSubId) + 1;

        switch (notifyType) {
            case PS_ENABLED:
                long dataSubId = SubscriptionManager.getDefaultDataSubscriptionId();
                if (dataSubId != mPhone.getSubId()) {
                    return;
                }
                notificationId = PS_NOTIFICATION;
                title = context.getText(com.android.internal.R.string.RestrictedOnDataTitle);
                details = multipleSubscriptions
                        ? context.getString(
                                com.android.internal.R.string.RestrictedStateContentMsimTemplate,
                                simNumber) :
                        context.getText(com.android.internal.R.string.RestrictedStateContent);
                break;
            case PS_DISABLED:
                notificationId = PS_NOTIFICATION;
                break;
            case CS_ENABLED:
                title = context.getText(com.android.internal.R.string.RestrictedOnAllVoiceTitle);
                details = multipleSubscriptions
                        ? context.getString(
                                com.android.internal.R.string.RestrictedStateContentMsimTemplate,
                                simNumber) :
                        context.getText(com.android.internal.R.string.RestrictedStateContent);
                break;
            case CS_NORMAL_ENABLED:
                title = context.getText(com.android.internal.R.string.RestrictedOnNormalTitle);
                details = multipleSubscriptions
                        ? context.getString(
                                com.android.internal.R.string.RestrictedStateContentMsimTemplate,
                                simNumber) :
                        context.getText(com.android.internal.R.string.RestrictedStateContent);
                break;
            case CS_EMERGENCY_ENABLED:
                title = context.getText(com.android.internal.R.string.RestrictedOnEmergencyTitle);
                details = multipleSubscriptions
                        ? context.getString(
                                com.android.internal.R.string.RestrictedStateContentMsimTemplate,
                                simNumber) :
                        context.getText(com.android.internal.R.string.RestrictedStateContent);
                break;
            case CS_DISABLED:
                // do nothing and cancel the notification later
                break;
            case CS_REJECT_CAUSE_ENABLED:
                notificationId = CS_REJECT_CAUSE_NOTIFICATION;
                int resId = selectResourceForRejectCode(mRejectCode, multipleSubscriptions);
                if (0 == resId) {
                    if (autoCancelCsRejectNotification) {
                        notifyType = CS_REJECT_CAUSE_DISABLED;
                    } else {
                        loge("setNotification: mRejectCode=" + mRejectCode + " is not handled.");
                        return;
                    }
                } else {
                    icon = com.android.internal.R.drawable.stat_notify_mmcc_indication_icn;
                    // if using the single SIM resource, simNumber will be ignored
                    title = context.getString(resId, simNumber);
                    details = null;
                }
                break;
        }

        if (DBG) {
            log("setNotification, create notification, notifyType: " + notifyType
                    + ", title: " + title + ", details: " + details + ", subId: " + mSubId);
        }

        mNotification = new Notification.Builder(context)
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true)
                .setSmallIcon(icon)
                .setTicker(title)
                .setColor(context.getResources().getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setContentTitle(title)
                .setStyle(new Notification.BigTextStyle().bigText(details))
                .setContentText(details)
                .setChannel(NotificationChannelController.CHANNEL_ID_ALERT)
                .build();

        NotificationManager notificationManager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (notifyType == PS_DISABLED || notifyType == CS_DISABLED
                || notifyType == CS_REJECT_CAUSE_DISABLED) {
            // cancel previous post notification
            notificationManager.cancel(Integer.toString(mSubId), notificationId);
        } else {
            boolean show = false;
            if (mSS.isEmergencyOnly() && notifyType == CS_EMERGENCY_ENABLED) {
                // if reg state is emergency only, always show restricted emergency notification.
                show = true;
            } else if (notifyType == CS_REJECT_CAUSE_ENABLED) {
                // always show notification due to CS reject irrespective of service state.
                show = true;
            } else if (mSS.getState() == ServiceState.STATE_IN_SERVICE) {
                // for non in service states, we have system UI and signal bar to indicate limited
                // service. No need to show notification again. This also helps to mitigate the
                // issue if phone go to OOS and camp to other networks and received restricted ind.
                show = true;
            }
            // update restricted state notification for this subId
            if (show) {
                notificationManager.notify(Integer.toString(mSubId), notificationId, mNotification);
            }
        }
    }

    /**
     * Selects the resource ID, which depends on rejection cause that is sent by the network when CS
     * registration is rejected.
     *
     * @param rejCode should be compatible with TS 24.008.
     */
    private int selectResourceForRejectCode(int rejCode, boolean multipleSubscriptions) {
        int rejResourceId = 0;
        switch (rejCode) {
            case 1:// Authentication reject
                rejResourceId = multipleSubscriptions
                        ? com.android.internal.R.string.mmcc_authentication_reject_msim_template :
                        com.android.internal.R.string.mmcc_authentication_reject;
                break;
            case 2:// IMSI unknown in HLR
                rejResourceId = multipleSubscriptions
                        ? com.android.internal.R.string.mmcc_imsi_unknown_in_hlr_msim_template :
                        com.android.internal.R.string.mmcc_imsi_unknown_in_hlr;
                break;
            case 3:// Illegal MS
                rejResourceId = multipleSubscriptions
                        ? com.android.internal.R.string.mmcc_illegal_ms_msim_template :
                        com.android.internal.R.string.mmcc_illegal_ms;
                break;
            case 6:// Illegal ME
                rejResourceId = multipleSubscriptions
                        ? com.android.internal.R.string.mmcc_illegal_me_msim_template :
                        com.android.internal.R.string.mmcc_illegal_me;
                break;
            default:
                // The other codes are not defined or not required by operators till now.
                break;
        }
        return rejResourceId;
    }

    private UiccCardApplication getUiccCardApplication() {
        if (mPhone.isPhoneTypeGsm()) {
            return mUiccController.getUiccCardApplication(mPhone.getPhoneId(),
                    UiccController.APP_FAM_3GPP);
        } else {
            return mUiccController.getUiccCardApplication(mPhone.getPhoneId(),
                    UiccController.APP_FAM_3GPP2);
        }
    }

    private void queueNextSignalStrengthPoll() {
        if (mDontPollSignalStrength) {
            // The radio is telling us about signal strength changes
            // we don't have to ask it
            return;
        }

        // if there is no SIM present, do not poll signal strength
        UiccCard uiccCard = UiccController.getInstance().getUiccCard(getPhoneId());
        if (uiccCard == null || uiccCard.getCardState() == CardState.CARDSTATE_ABSENT) {
            log("Not polling signal strength due to absence of SIM");
            return;
        }

        Message msg;

        msg = obtainMessage();
        msg.what = EVENT_POLL_SIGNAL_STRENGTH;

        long nextTime;

        // TODO Don't poll signal strength if screen is off
        sendMessageDelayed(msg, POLL_PERIOD_MILLIS);
    }

    private void notifyCdmaSubscriptionInfoReady() {
        if (mCdmaForSubscriptionInfoReadyRegistrants != null) {
            if (DBG) log("CDMA_SUBSCRIPTION: call notifyRegistrants()");
            mCdmaForSubscriptionInfoReadyRegistrants.notifyRegistrants();
        }
    }

    /**
     * Registration point for transition into DataConnection attached.
     * @param transport Transport type
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForDataConnectionAttached(@TransportType int transport, Handler h, int what,
                                                  Object obj) {
        Registrant r = new Registrant(h, what, obj);
        if (mAttachedRegistrants.get(transport) == null) {
            mAttachedRegistrants.put(transport, new RegistrantList());
        }
        mAttachedRegistrants.get(transport).add(r);

        if (mSS != null) {
            NetworkRegistrationInfo netRegState = mSS.getNetworkRegistrationInfo(
                    NetworkRegistrationInfo.DOMAIN_PS, transport);
            if (netRegState == null || netRegState.isInService()) {
                r.notifyRegistrant();
            }
        }
    }

    /**
     * Unregister for data attached event
     *
     * @param transport Transport type
     * @param h Handler to notify
     */
    public void unregisterForDataConnectionAttached(@TransportType int transport, Handler h) {
        if (mAttachedRegistrants.get(transport) != null) {
            mAttachedRegistrants.get(transport).remove(h);
        }
    }

    /**
     * Registration point for transition into DataConnection detached.
     * @param transport Transport type
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForDataConnectionDetached(@TransportType int transport, Handler h, int what,
                                                  Object obj) {
        Registrant r = new Registrant(h, what, obj);
        if (mDetachedRegistrants.get(transport) == null) {
            mDetachedRegistrants.put(transport, new RegistrantList());
        }
        mDetachedRegistrants.get(transport).add(r);

        if (mSS != null) {
            NetworkRegistrationInfo netRegState = mSS.getNetworkRegistrationInfo(
                    NetworkRegistrationInfo.DOMAIN_PS, transport);
            if (netRegState != null && !netRegState.isInService()) {
                r.notifyRegistrant();
            }
        }
    }

    /**
     * Unregister for data detatched event
     *
     * @param transport Transport type
     * @param h Handler to notify
     */
    public void unregisterForDataConnectionDetached(@TransportType int transport, Handler h) {
        if (mDetachedRegistrants.get(transport) != null) {
            mDetachedRegistrants.get(transport).remove(h);
        }
    }

    /**
     * Registration for RIL Voice Radio Technology changing. The
     * new radio technology will be returned AsyncResult#result as an Integer Object.
     * The AsyncResult will be in the notification Message#obj.
     *
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForVoiceRegStateOrRatChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mVoiceRegStateOrRatChangedRegistrants.add(r);
        notifyVoiceRegStateRilRadioTechnologyChanged();
    }

    public void unregisterForVoiceRegStateOrRatChanged(Handler h) {
        mVoiceRegStateOrRatChangedRegistrants.remove(h);
    }

    /**
     * Registration for DataConnection RIL Data Radio Technology changing. The
     * new radio technology will be returned AsyncResult#result as an Integer Object.
     * The AsyncResult will be in the notification Message#obj.
     *
     * @param transport Transport
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForDataRegStateOrRatChanged(@TransportType int transport, Handler h,
                                                    int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        if (mDataRegStateOrRatChangedRegistrants.get(transport) == null) {
            mDataRegStateOrRatChangedRegistrants.put(transport, new RegistrantList());
        }
        mDataRegStateOrRatChangedRegistrants.get(transport).add(r);
        notifyDataRegStateRilRadioTechnologyChanged(transport);
    }

    /**
     * Unregister for data registration state changed or RAT changed event
     *
     * @param transport Transport
     * @param h The handler
     */
    public void unregisterForDataRegStateOrRatChanged(@TransportType int transport, Handler h) {
        if (mDataRegStateOrRatChangedRegistrants.get(transport) != null) {
            mDataRegStateOrRatChangedRegistrants.get(transport).remove(h);
        }
    }

    /**
     * Registration point for transition into network attached.
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj in Message.obj
     */
    public void registerForNetworkAttached(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);

        mNetworkAttachedRegistrants.add(r);
        if (mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForNetworkAttached(Handler h) {
        mNetworkAttachedRegistrants.remove(h);
    }

    /**
     * Registration point for transition into network detached.
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj in Message.obj
     */
    public void registerForNetworkDetached(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);

        mNetworkDetachedRegistrants.add(r);
        if (mSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForNetworkDetached(Handler h) {
        mNetworkDetachedRegistrants.remove(h);
    }

    /**
     * Registration point for transition into packet service restricted zone.
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForPsRestrictedEnabled(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mPsRestrictEnabledRegistrants.add(r);

        if (mRestrictedState.isPsRestricted()) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForPsRestrictedEnabled(Handler h) {
        mPsRestrictEnabledRegistrants.remove(h);
    }

    /**
     * Registration point for transition out of packet service restricted zone.
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForPsRestrictedDisabled(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mPsRestrictDisabledRegistrants.add(r);

        if (mRestrictedState.isPsRestricted()) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForPsRestrictedDisabled(Handler h) {
        mPsRestrictDisabledRegistrants.remove(h);
    }

    /**
     * Registers for IMS capability changed.
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForImsCapabilityChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mImsCapabilityChangedRegistrants.add(r);
    }

    /**
     * Unregisters for IMS capability changed.
     * @param h handler to notify
     */
    public void unregisterForImsCapabilityChanged(Handler h) {
        mImsCapabilityChangedRegistrants.remove(h);
    }

    /**
     * Clean up existing voice and data connection then turn off radio power.
     *
     * Hang up the existing voice calls to decrease call drop rate.
     */
    public void powerOffRadioSafely() {
        synchronized (this) {
            if (!mPendingRadioPowerOffAfterDataOff) {
                int dds = SubscriptionManager.getDefaultDataSubscriptionId();
                // To minimize race conditions we call cleanUpAllConnections on
                // both if else paths instead of before this isDisconnected test.
                if (mPhone.areAllDataDisconnected()
                        && (dds == mPhone.getSubId()
                        || (dds != mPhone.getSubId()
                        && ProxyController.getInstance().areAllDataDisconnected(dds)))) {
                    // To minimize race conditions we do this after isDisconnected
                    for (int transport : mTransportManager.getAvailableTransports()) {
                        if (mPhone.getDcTracker(transport) != null) {
                            mPhone.getDcTracker(transport).cleanUpAllConnections(
                                    Phone.REASON_RADIO_TURNED_OFF);
                        }
                    }
                    if (DBG) log("Data disconnected, turn off radio right away.");
                    hangupAndPowerOff();
                } else {
                    // hang up all active voice calls first
                    if (mPhone.isPhoneTypeGsm() && mPhone.isInCall()) {
                        mPhone.mCT.mRingingCall.hangupIfAlive();
                        mPhone.mCT.mBackgroundCall.hangupIfAlive();
                        mPhone.mCT.mForegroundCall.hangupIfAlive();
                    }
                    for (int transport : mTransportManager.getAvailableTransports()) {
                        if (mPhone.getDcTracker(transport) != null) {
                            mPhone.getDcTracker(transport).cleanUpAllConnections(
                                    Phone.REASON_RADIO_TURNED_OFF);
                        }
                    }

                    if (dds != mPhone.getSubId()
                            && !ProxyController.getInstance().areAllDataDisconnected(dds)) {
                        if (DBG) log("Data is active on DDS.  Wait for all data disconnect");
                        // Data is not disconnected on DDS. Wait for the data disconnect complete
                        // before sending the RADIO_POWER off.
                        ProxyController.getInstance().registerForAllDataDisconnected(dds, this,
                                EVENT_ALL_DATA_DISCONNECTED);
                        mPendingRadioPowerOffAfterDataOff = true;
                    }
                    Message msg = Message.obtain(this);
                    msg.what = EVENT_SET_RADIO_POWER_OFF;
                    msg.arg1 = ++mPendingRadioPowerOffAfterDataOffTag;
                    if (sendMessageDelayed(msg, 30000)) {
                        if (DBG) log("Wait upto 30s for data to disconnect, then turn off radio.");
                        mPendingRadioPowerOffAfterDataOff = true;
                    } else {
                        log("Cannot send delayed Msg, turn off radio right away.");
                        hangupAndPowerOff();
                        mPendingRadioPowerOffAfterDataOff = false;
                    }
                }
            }
        }
    }

    /**
     * process the pending request to turn radio off after data is disconnected
     *
     * return true if there is pending request to process; false otherwise.
     */
    public boolean processPendingRadioPowerOffAfterDataOff() {
        synchronized(this) {
            if (mPendingRadioPowerOffAfterDataOff) {
                if (DBG) log("Process pending request to turn radio off.");
                mPendingRadioPowerOffAfterDataOffTag += 1;
                hangupAndPowerOff();
                mPendingRadioPowerOffAfterDataOff = false;
                return true;
            }
            return false;
        }
    }

    /**
     * Checks if the provided earfcn falls withing the range of earfcns.
     *
     * return true if earfcn falls within the provided range; false otherwise.
     */
    private boolean containsEarfcnInEarfcnRange(ArrayList<Pair<Integer, Integer>> earfcnPairList,
            int earfcn) {
        if (earfcnPairList != null) {
            for (Pair<Integer, Integer> earfcnPair : earfcnPairList) {
                if ((earfcn >= earfcnPair.first) && (earfcn <= earfcnPair.second)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Convert the earfcnStringArray to list of pairs.
     *
     * Format of the earfcnsList is expected to be {"erafcn1_start-earfcn1_end",
     * "earfcn2_start-earfcn2_end" ... }
     */
    ArrayList<Pair<Integer, Integer>> convertEarfcnStringArrayToPairList(String[] earfcnsList) {
        ArrayList<Pair<Integer, Integer>> earfcnPairList = new ArrayList<Pair<Integer, Integer>>();

        if (earfcnsList != null) {
            int earfcnStart;
            int earfcnEnd;
            for (int i = 0; i < earfcnsList.length; i++) {
                try {
                    String[] earfcns = earfcnsList[i].split("-");
                    if (earfcns.length != 2) {
                        if (VDBG) {
                            log("Invalid earfcn range format");
                        }
                        return null;
                    }

                    earfcnStart = Integer.parseInt(earfcns[0]);
                    earfcnEnd = Integer.parseInt(earfcns[1]);

                    if (earfcnStart > earfcnEnd) {
                        if (VDBG) {
                            log("Invalid earfcn range format");
                        }
                        return null;
                    }

                    earfcnPairList.add(new Pair<Integer, Integer>(earfcnStart, earfcnEnd));
                } catch (PatternSyntaxException pse) {
                    if (VDBG) {
                        log("Invalid earfcn range format");
                    }
                    return null;
                } catch (NumberFormatException nfe) {
                    if (VDBG) {
                        log("Invalid earfcn number format");
                    }
                    return null;
                }
            }
        }

        return earfcnPairList;
    }

    private void onCarrierConfigChanged() {
        PersistableBundle config = getCarrierConfig();
        log("CarrierConfigChange " + config);

        // Load the ERI based on carrier config. Carrier might have their specific ERI.
        mEriManager.loadEriFile();
        mCdnr.updateEfForEri(getOperatorNameFromEri());

        updateLteEarfcnLists(config);
        updateReportingCriteria(config);
        updateOperatorNamePattern(config);
        mCdnr.updateEfFromCarrierConfig(config);

        // Sometimes the network registration information comes before carrier config is ready.
        // For some cases like roaming/non-roaming overriding, we need carrier config. So it's
        // important to poll state again when carrier config is ready.
        pollState();
    }

    private void updateLteEarfcnLists(PersistableBundle config) {
        synchronized (mLteRsrpBoostLock) {
            mLteRsrpBoost = config.getInt(CarrierConfigManager.KEY_LTE_EARFCNS_RSRP_BOOST_INT, 0);
            String[] earfcnsStringArrayForRsrpBoost = config.getStringArray(
                    CarrierConfigManager.KEY_BOOSTED_LTE_EARFCNS_STRING_ARRAY);
            mEarfcnPairListForRsrpBoost = convertEarfcnStringArrayToPairList(
                    earfcnsStringArrayForRsrpBoost);
        }
    }

    private void updateReportingCriteria(PersistableBundle config) {
        mPhone.setSignalStrengthReportingCriteria(
                config.getIntArray(CarrierConfigManager.KEY_LTE_RSRP_THRESHOLDS_INT_ARRAY),
                AccessNetworkType.EUTRAN);
        mPhone.setSignalStrengthReportingCriteria(
                config.getIntArray(CarrierConfigManager.KEY_WCDMA_RSCP_THRESHOLDS_INT_ARRAY),
                AccessNetworkType.UTRAN);
    }

    private void updateServiceStateLteEarfcnBoost(ServiceState serviceState, int lteEarfcn) {
        synchronized (mLteRsrpBoostLock) {
            if ((lteEarfcn != INVALID_LTE_EARFCN)
                    && containsEarfcnInEarfcnRange(mEarfcnPairListForRsrpBoost, lteEarfcn)) {
                serviceState.setLteEarfcnRsrpBoost(mLteRsrpBoost);
            } else {
                serviceState.setLteEarfcnRsrpBoost(0);
            }
        }
    }

    /**
     * send signal-strength-changed notification if changed Called both for
     * solicited and unsolicited signal strength updates
     *
     * @return true if the signal strength changed and a notification was sent.
     */
    protected boolean onSignalStrengthResult(AsyncResult ar) {

        // This signal is used for both voice and data radio signal so parse
        // all fields

        if ((ar.exception == null) && (ar.result != null)) {
            mSignalStrength = (SignalStrength) ar.result;

            PersistableBundle config = getCarrierConfig();
            mSignalStrength.updateLevel(config, mSS);
        } else {
            log("onSignalStrengthResult() Exception from RIL : " + ar.exception);
            mSignalStrength = new SignalStrength();
        }

        boolean ssChanged = notifySignalStrength();

        return ssChanged;
    }

    /**
     * Hang up all voice call and turn off radio. Implemented by derived class.
     */
    protected void hangupAndPowerOff() {
        // hang up all active voice calls
        if (!mPhone.isPhoneTypeGsm() || mPhone.isInCall()) {
            mPhone.mCT.mRingingCall.hangupIfAlive();
            mPhone.mCT.mBackgroundCall.hangupIfAlive();
            mPhone.mCT.mForegroundCall.hangupIfAlive();
        }

        mCi.setRadioPower(false, obtainMessage(EVENT_RADIO_POWER_OFF_DONE));

    }

    /** Cancel a pending (if any) pollState() operation */
    protected void cancelPollState() {
        // This will effectively cancel the rest of the poll requests.
        mPollingContext = new int[1];
    }

    /**
     * Return true if the network operator's country code changed.
     */
    private boolean networkCountryIsoChanged(String newCountryIsoCode, String prevCountryIsoCode) {
        // Return false if the new ISO code isn't valid as we don't know where we are.
        // Return true if the previous ISO code wasn't valid, or if it was and the new one differs.

        // If newCountryIsoCode is invalid then we'll return false
        if (TextUtils.isEmpty(newCountryIsoCode)) {
            if (DBG) {
                log("countryIsoChanged: no new country ISO code");
            }
            return false;
        }

        if (TextUtils.isEmpty(prevCountryIsoCode)) {
            if (DBG) {
                log("countryIsoChanged: no previous country ISO code");
            }
            return true;
        }
        return !newCountryIsoCode.equals(prevCountryIsoCode);
    }

    // Determine if the Icc card exists
    private boolean iccCardExists() {
        boolean iccCardExist = false;
        if (mUiccApplcation != null) {
            iccCardExist = mUiccApplcation.getState() != AppState.APPSTATE_UNKNOWN;
        }
        return iccCardExist;
    }

    @UnsupportedAppUsage
    public String getSystemProperty(String property, String defValue) {
        return TelephonyManager.getTelephonyProperty(mPhone.getPhoneId(), property, defValue);
    }

    public List<CellInfo> getAllCellInfo() {
        return mLastCellInfoList;
    }

    /** Set the minimum time between CellInfo requests to the modem, in milliseconds */
    public void setCellInfoMinInterval(int interval) {
        mCellInfoMinIntervalMs = interval;
    }

    /**
     * Request the latest CellInfo from the modem.
     *
     * If sufficient time has elapsed, then this request will be sent to the modem. Otherwise
     * the latest cached List<CellInfo> will be returned.
     *
     * @param workSource of the caller for power accounting
     * @param rspMsg an optional response message to get the response to the CellInfo request. If
     *     the rspMsg is not provided, then CellInfo will still be requested from the modem and
     *     cached locally for future lookup.
     */
    public void requestAllCellInfo(WorkSource workSource, Message rspMsg) {
        if (VDBG) log("SST.requestAllCellInfo(): E");
        if (mCi.getRilVersion() < 8) {
            AsyncResult.forMessage(rspMsg);
            rspMsg.sendToTarget();
            if (DBG) log("SST.requestAllCellInfo(): not implemented");
            return;
        }
        synchronized (mPendingCellInfoRequests) {
            // If there are pending requests, then we already have a request active, so add this
            // request to the response queue without initiating a new request.
            if (mIsPendingCellInfoRequest) {
                if (rspMsg != null) mPendingCellInfoRequests.add(rspMsg);
                return;
            }
            // Check to see whether the elapsed time is sufficient for a new request; if not, then
            // return the result of the last request (if expected).
            final long curTime = SystemClock.elapsedRealtime();
            if ((curTime - mLastCellInfoReqTime) < mCellInfoMinIntervalMs) {
                if (rspMsg != null) {
                    if (DBG) log("SST.requestAllCellInfo(): return last, back to back calls");
                    AsyncResult.forMessage(rspMsg, mLastCellInfoList, null);
                    rspMsg.sendToTarget();
                }
                return;
            }
            // If this request needs an explicit response (it's a synchronous request), then queue
            // the response message.
            if (rspMsg != null) mPendingCellInfoRequests.add(rspMsg);
            // Update the timeout window so that we don't delay based on slow responses
            mLastCellInfoReqTime = curTime;
            // Set a flag to remember that we have a pending cell info request
            mIsPendingCellInfoRequest = true;
            // Send a cell info request and also chase it with a timeout message
            Message msg = obtainMessage(EVENT_GET_CELL_INFO_LIST);
            mCi.getCellInfoList(msg, workSource);
            // This message will arrive TIMEOUT ms later and ensure that we don't wait forever for
            // a CELL_INFO response.
            sendMessageDelayed(
                    obtainMessage(EVENT_GET_CELL_INFO_LIST), CELL_INFO_LIST_QUERY_TIMEOUT);
        }
    }

    /**
     * @return signal strength
     */
    public SignalStrength getSignalStrength() {
        return mSignalStrength;
    }

    /**
     * Registration point for subscription info ready
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForSubscriptionInfoReady(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mCdmaForSubscriptionInfoReadyRegistrants.add(r);

        if (isMinInfoReady()) {
            r.notifyRegistrant();
        }
    }

    public void unregisterForSubscriptionInfoReady(Handler h) {
        mCdmaForSubscriptionInfoReadyRegistrants.remove(h);
    }

    /**
     * Save current source of cdma subscription
     * @param source - 1 for NV, 0 for RUIM
     */
    private void saveCdmaSubscriptionSource(int source) {
        log("Storing cdma subscription source: " + source);
        Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                Settings.Global.CDMA_SUBSCRIPTION_MODE,
                source);
        log("Read from settings: " + Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                Settings.Global.CDMA_SUBSCRIPTION_MODE, -1));
    }

    private void getSubscriptionInfoAndStartPollingThreads() {
        mCi.getCDMASubscription(obtainMessage(EVENT_POLL_STATE_CDMA_SUBSCRIPTION));

        // Get Registration Information
        pollState();
    }

    private void handleCdmaSubscriptionSource(int newSubscriptionSource) {
        log("Subscription Source : " + newSubscriptionSource);
        mIsSubscriptionFromRuim =
                (newSubscriptionSource == CdmaSubscriptionSourceManager.SUBSCRIPTION_FROM_RUIM);
        log("isFromRuim: " + mIsSubscriptionFromRuim);
        saveCdmaSubscriptionSource(newSubscriptionSource);
        if (!mIsSubscriptionFromRuim) {
            // NV is ready when subscription source is NV
            sendMessage(obtainMessage(EVENT_NV_READY));
        }
    }

    private void dumpEarfcnPairList(PrintWriter pw) {
        pw.print(" mEarfcnPairListForRsrpBoost={");
        if (mEarfcnPairListForRsrpBoost != null) {
            int i = mEarfcnPairListForRsrpBoost.size();
            for (Pair<Integer, Integer> earfcnPair : mEarfcnPairListForRsrpBoost) {
                pw.print("(");
                pw.print(earfcnPair.first);
                pw.print(",");
                pw.print(earfcnPair.second);
                pw.print(")");
                if ((--i) != 0) {
                    pw.print(",");
                }
            }
        }
        pw.println("}");
    }

    private void dumpCellInfoList(PrintWriter pw) {
        pw.print(" mLastCellInfoList={");
        if(mLastCellInfoList != null) {
            boolean first = true;
            for(CellInfo info : mLastCellInfoList) {
               if(first == false) {
                   pw.print(",");
               }
               first = false;
               pw.print(info.toString());
            }
        }
        pw.println("}");
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("ServiceStateTracker:");
        pw.println(" mSubId=" + mSubId);
        pw.println(" mSS=" + mSS);
        pw.println(" mNewSS=" + mNewSS);
        pw.println(" mVoiceCapable=" + mVoiceCapable);
        pw.println(" mRestrictedState=" + mRestrictedState);
        pw.println(" mPollingContext=" + mPollingContext + " - " +
                (mPollingContext != null ? mPollingContext[0] : ""));
        pw.println(" mDesiredPowerState=" + mDesiredPowerState);
        pw.println(" mDontPollSignalStrength=" + mDontPollSignalStrength);
        pw.println(" mSignalStrength=" + mSignalStrength);
        pw.println(" mLastSignalStrength=" + mLastSignalStrength);
        pw.println(" mRestrictedState=" + mRestrictedState);
        pw.println(" mPendingRadioPowerOffAfterDataOff=" + mPendingRadioPowerOffAfterDataOff);
        pw.println(" mPendingRadioPowerOffAfterDataOffTag=" + mPendingRadioPowerOffAfterDataOffTag);
        pw.println(" mCellIdentity=" + Rlog.pii(VDBG, mCellIdentity));
        pw.println(" mNewCellIdentity=" + Rlog.pii(VDBG, mNewCellIdentity));
        pw.println(" mLastCellInfoReqTime=" + mLastCellInfoReqTime);
        dumpCellInfoList(pw);
        pw.flush();
        pw.println(" mPreferredNetworkType=" + mPreferredNetworkType);
        pw.println(" mMaxDataCalls=" + mMaxDataCalls);
        pw.println(" mNewMaxDataCalls=" + mNewMaxDataCalls);
        pw.println(" mReasonDataDenied=" + mReasonDataDenied);
        pw.println(" mNewReasonDataDenied=" + mNewReasonDataDenied);
        pw.println(" mGsmRoaming=" + mGsmRoaming);
        pw.println(" mDataRoaming=" + mDataRoaming);
        pw.println(" mEmergencyOnly=" + mEmergencyOnly);
        pw.flush();
        mNitzState.dumpState(pw);
        pw.flush();
        pw.println(" mStartedGprsRegCheck=" + mStartedGprsRegCheck);
        pw.println(" mReportedGprsNoReg=" + mReportedGprsNoReg);
        pw.println(" mNotification=" + mNotification);
        pw.println(" mCurSpn=" + mCurSpn);
        pw.println(" mCurDataSpn=" + mCurDataSpn);
        pw.println(" mCurShowSpn=" + mCurShowSpn);
        pw.println(" mCurPlmn=" + mCurPlmn);
        pw.println(" mCurShowPlmn=" + mCurShowPlmn);
        pw.flush();
        pw.println(" mCurrentOtaspMode=" + mCurrentOtaspMode);
        pw.println(" mRoamingIndicator=" + mRoamingIndicator);
        pw.println(" mIsInPrl=" + mIsInPrl);
        pw.println(" mDefaultRoamingIndicator=" + mDefaultRoamingIndicator);
        pw.println(" mRegistrationState=" + mRegistrationState);
        pw.println(" mMdn=" + mMdn);
        pw.println(" mHomeSystemId=" + mHomeSystemId);
        pw.println(" mHomeNetworkId=" + mHomeNetworkId);
        pw.println(" mMin=" + mMin);
        pw.println(" mPrlVersion=" + mPrlVersion);
        pw.println(" mIsMinInfoReady=" + mIsMinInfoReady);
        pw.println(" mIsEriTextLoaded=" + mIsEriTextLoaded);
        pw.println(" mIsSubscriptionFromRuim=" + mIsSubscriptionFromRuim);
        pw.println(" mCdmaSSM=" + mCdmaSSM);
        pw.println(" mRegistrationDeniedReason=" + mRegistrationDeniedReason);
        pw.println(" mCurrentCarrier=" + mCurrentCarrier);
        pw.flush();
        pw.println(" mImsRegistered=" + mImsRegistered);
        pw.println(" mImsRegistrationOnOff=" + mImsRegistrationOnOff);
        pw.println(" mAlarmSwitch=" + mAlarmSwitch);
        pw.println(" mRadioDisabledByCarrier" + mRadioDisabledByCarrier);
        pw.println(" mPowerOffDelayNeed=" + mPowerOffDelayNeed);
        pw.println(" mDeviceShuttingDown=" + mDeviceShuttingDown);
        pw.println(" mSpnUpdatePending=" + mSpnUpdatePending);
        pw.println(" mLteRsrpBoost=" + mLteRsrpBoost);
        pw.println(" mCellInfoMinIntervalMs=" + mCellInfoMinIntervalMs);
        pw.println(" mEriManager=" + mEriManager);
        dumpEarfcnPairList(pw);

        mLocaleTracker.dump(fd, pw, args);
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "    ");

        mCdnr.dump(ipw);

        ipw.println(" Carrier Display Name update records:");
        ipw.increaseIndent();
        mCdnrLogs.dump(fd, ipw, args);
        ipw.decreaseIndent();

        ipw.println(" Roaming Log:");
        ipw.increaseIndent();
        mRoamingLog.dump(fd, ipw, args);
        ipw.decreaseIndent();

        ipw.println(" Attach Log:");
        ipw.increaseIndent();
        mAttachLog.dump(fd, ipw, args);
        ipw.decreaseIndent();

        ipw.println(" Phone Change Log:");
        ipw.increaseIndent();
        mPhoneTypeLog.dump(fd, ipw, args);
        ipw.decreaseIndent();

        ipw.println(" Rat Change Log:");
        ipw.increaseIndent();
        mRatLog.dump(fd, ipw, args);
        ipw.decreaseIndent();

        ipw.println(" Radio power Log:");
        ipw.increaseIndent();
        mRadioPowerLog.dump(fd, ipw, args);
        ipw.decreaseIndent();

        mNitzState.dumpLogs(fd, ipw, args);

        ipw.flush();
    }

    @UnsupportedAppUsage
    public boolean isImsRegistered() {
        return mImsRegistered;
    }
    /**
     * Verifies the current thread is the same as the thread originally
     * used in the initialization of this instance. Throws RuntimeException
     * if not.
     *
     * @exception RuntimeException if the current thread is not
     * the thread that originally obtained this Phone instance.
     */
    protected void checkCorrectThread() {
        if (Thread.currentThread() != getLooper().getThread()) {
            throw new RuntimeException(
                    "ServiceStateTracker must be used from within one thread");
        }
    }

    protected boolean isCallerOnDifferentThread() {
        boolean value = Thread.currentThread() != getLooper().getThread();
        if (VDBG) log("isCallerOnDifferentThread: " + value);
        return value;
    }

    /**
     * Check ISO country by MCC to see if phone is roaming in same registered country
     */
    protected boolean inSameCountry(String operatorNumeric) {
        if (TextUtils.isEmpty(operatorNumeric) || (operatorNumeric.length() < 5)) {
            // Not a valid network
            return false;
        }
        final String homeNumeric = getHomeOperatorNumeric();
        if (TextUtils.isEmpty(homeNumeric) || (homeNumeric.length() < 5)) {
            // Not a valid SIM MCC
            return false;
        }
        boolean inSameCountry = true;
        final String networkMCC = operatorNumeric.substring(0, 3);
        final String homeMCC = homeNumeric.substring(0, 3);
        final String networkCountry = MccTable.countryCodeForMcc(networkMCC);
        final String homeCountry = MccTable.countryCodeForMcc(homeMCC);
        if (networkCountry.isEmpty() || homeCountry.isEmpty()) {
            // Not a valid country
            return false;
        }
        inSameCountry = homeCountry.equals(networkCountry);
        if (inSameCountry) {
            return inSameCountry;
        }
        // special same country cases
        if ("us".equals(homeCountry) && "vi".equals(networkCountry)) {
            inSameCountry = true;
        } else if ("vi".equals(homeCountry) && "us".equals(networkCountry)) {
            inSameCountry = true;
        }
        return inSameCountry;
    }

    /**
     * Set both voice and data roaming type,
     * judging from the ISO country of SIM VS network.
     */
    @UnsupportedAppUsage
    protected void setRoamingType(ServiceState currentServiceState) {
        final boolean isVoiceInService =
                (currentServiceState.getVoiceRegState() == ServiceState.STATE_IN_SERVICE);
        if (isVoiceInService) {
            if (currentServiceState.getVoiceRoaming()) {
                if (mPhone.isPhoneTypeGsm()) {
                    // check roaming type by MCC
                    if (inSameCountry(currentServiceState.getVoiceOperatorNumeric())) {
                        currentServiceState.setVoiceRoamingType(
                                ServiceState.ROAMING_TYPE_DOMESTIC);
                    } else {
                        currentServiceState.setVoiceRoamingType(
                                ServiceState.ROAMING_TYPE_INTERNATIONAL);
                    }
                } else {
                    // some carrier defines international roaming by indicator
                    int[] intRoamingIndicators = mPhone.getContext().getResources().getIntArray(
                            com.android.internal.R.array.config_cdma_international_roaming_indicators);
                    if ((intRoamingIndicators != null) && (intRoamingIndicators.length > 0)) {
                        // It's domestic roaming at least now
                        currentServiceState.setVoiceRoamingType(ServiceState.ROAMING_TYPE_DOMESTIC);
                        int curRoamingIndicator = currentServiceState.getCdmaRoamingIndicator();
                        for (int i = 0; i < intRoamingIndicators.length; i++) {
                            if (curRoamingIndicator == intRoamingIndicators[i]) {
                                currentServiceState.setVoiceRoamingType(
                                        ServiceState.ROAMING_TYPE_INTERNATIONAL);
                                break;
                            }
                        }
                    } else {
                        // check roaming type by MCC
                        if (inSameCountry(currentServiceState.getVoiceOperatorNumeric())) {
                            currentServiceState.setVoiceRoamingType(
                                    ServiceState.ROAMING_TYPE_DOMESTIC);
                        } else {
                            currentServiceState.setVoiceRoamingType(
                                    ServiceState.ROAMING_TYPE_INTERNATIONAL);
                        }
                    }
                }
            } else {
                currentServiceState.setVoiceRoamingType(ServiceState.ROAMING_TYPE_NOT_ROAMING);
            }
        }
        final boolean isDataInService =
                (currentServiceState.getDataRegState() == ServiceState.STATE_IN_SERVICE);
        final int dataRegType = currentServiceState.getRilDataRadioTechnology();
        if (isDataInService) {
            if (!currentServiceState.getDataRoaming()) {
                currentServiceState.setDataRoamingType(ServiceState.ROAMING_TYPE_NOT_ROAMING);
            } else {
                if (mPhone.isPhoneTypeGsm()) {
                    if (ServiceState.isGsm(dataRegType)) {
                        if (isVoiceInService) {
                            // GSM data should have the same state as voice
                            currentServiceState.setDataRoamingType(currentServiceState
                                    .getVoiceRoamingType());
                        } else {
                            // we can not decide GSM data roaming type without voice
                            currentServiceState.setDataRoamingType(ServiceState.ROAMING_TYPE_UNKNOWN);
                        }
                    } else {
                        // we can not decide 3gpp2 roaming state here
                        currentServiceState.setDataRoamingType(ServiceState.ROAMING_TYPE_UNKNOWN);
                    }
                } else {
                    if (ServiceState.isCdma(dataRegType)) {
                        if (isVoiceInService) {
                            // CDMA data should have the same state as voice
                            currentServiceState.setDataRoamingType(currentServiceState
                                    .getVoiceRoamingType());
                        } else {
                            // we can not decide CDMA data roaming type without voice
                            // set it as same as last time
                            currentServiceState.setDataRoamingType(ServiceState.ROAMING_TYPE_UNKNOWN);
                        }
                    } else {
                        // take it as 3GPP roaming
                        if (inSameCountry(currentServiceState.getDataOperatorNumeric())) {
                            currentServiceState.setDataRoamingType(ServiceState.ROAMING_TYPE_DOMESTIC);
                        } else {
                            currentServiceState.setDataRoamingType(
                                    ServiceState.ROAMING_TYPE_INTERNATIONAL);
                        }
                    }
                }
            }
        }
    }

    @UnsupportedAppUsage
    private void setSignalStrengthDefaultValues() {
        mSignalStrength = new SignalStrength();
    }

    protected String getHomeOperatorNumeric() {
        String numeric = ((TelephonyManager) mPhone.getContext().
                getSystemService(Context.TELEPHONY_SERVICE)).
                getSimOperatorNumericForPhone(mPhone.getPhoneId());
        if (!mPhone.isPhoneTypeGsm() && TextUtils.isEmpty(numeric)) {
            numeric = SystemProperties.get(GsmCdmaPhone.PROPERTY_CDMA_HOME_OPERATOR_NUMERIC, "");
        }
        return numeric;
    }

    @UnsupportedAppUsage
    protected int getPhoneId() {
        return mPhone.getPhoneId();
    }

    /* Reset Service state when IWLAN is enabled as polling in airplane mode
     * causes state to go to OUT_OF_SERVICE state instead of STATE_OFF
     */


    /**
     * This method adds IWLAN registration info for legacy mode devices camped on IWLAN. It also
     * makes some adjustments when the device camps on IWLAN in airplane mode.
     */
    private void processIwlanRegistrationInfo() {
        if (mCi.getRadioState() == TelephonyManager.RADIO_POWER_OFF) {
            boolean resetIwlanRatVal = false;
            log("set service state as POWER_OFF");
            if (ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN
                    == mNewSS.getRilDataRadioTechnology()) {
                log("pollStateDone: mNewSS = " + mNewSS);
                log("pollStateDone: reset iwlan RAT value");
                resetIwlanRatVal = true;
            }
            // operator info should be kept in SS
            String operator = mNewSS.getOperatorAlphaLong();
            mNewSS.setStateOff();
            if (resetIwlanRatVal) {
                mNewSS.setDataRegState(ServiceState.STATE_IN_SERVICE);
                NetworkRegistrationInfo nri = new NetworkRegistrationInfo.Builder()
                        .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WLAN)
                        .setDomain(NetworkRegistrationInfo.DOMAIN_PS)
                        .setAccessNetworkTechnology(TelephonyManager.NETWORK_TYPE_IWLAN)
                        .setRegistrationState(NetworkRegistrationInfo.REGISTRATION_STATE_HOME)
                        .build();
                mNewSS.addNetworkRegistrationInfo(nri);
                if (mTransportManager.isInLegacyMode()) {
                    // If in legacy mode, simulate the behavior that IWLAN registration info
                    // is reported through WWAN transport.
                    nri = new NetworkRegistrationInfo.Builder()
                            .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                            .setDomain(NetworkRegistrationInfo.DOMAIN_PS)
                            .setAccessNetworkTechnology(TelephonyManager.NETWORK_TYPE_IWLAN)
                            .setRegistrationState(NetworkRegistrationInfo.REGISTRATION_STATE_HOME)
                            .build();
                    mNewSS.addNetworkRegistrationInfo(nri);
                }
                mNewSS.setOperatorAlphaLong(operator);
                log("pollStateDone: mNewSS = " + mNewSS);
            }
            return;
        }

        // If the device operates in legacy mode and camps on IWLAN, modem reports IWLAN as a RAT
        // through WWAN registration info. To be consistent with the behavior with AP-assisted mode,
        // we manually make a WLAN registration info for clients to consume. In this scenario,
        // both WWAN and WLAN registration info are the IWLAN registration info and that's the
        // unfortunate limitation we have when the device operates in legacy mode. In AP-assisted
        // mode, the WWAN registration will correctly report the actual cellular registration info
        // when the device camps on IWLAN.
        if (mTransportManager.isInLegacyMode()) {
            NetworkRegistrationInfo wwanNri = mNewSS.getNetworkRegistrationInfo(
                    NetworkRegistrationInfo.DOMAIN_PS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
            if (wwanNri != null && wwanNri.getAccessNetworkTechnology()
                    == TelephonyManager.NETWORK_TYPE_IWLAN) {
                NetworkRegistrationInfo wlanNri = new NetworkRegistrationInfo.Builder()
                        .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WLAN)
                        .setDomain(NetworkRegistrationInfo.DOMAIN_PS)
                        .setRegistrationState(wwanNri.getRegistrationState())
                        .setAccessNetworkTechnology(TelephonyManager.NETWORK_TYPE_IWLAN)
                        .setRejectCause(wwanNri.getRejectCause())
                        .setEmergencyOnly(wwanNri.isEmergencyEnabled())
                        .setAvailableServices(wwanNri.getAvailableServices())
                        .build();
                mNewSS.addNetworkRegistrationInfo(wlanNri);
            }
        }
    }

    /**
     * Check if device is non-roaming and always on home network.
     *
     * @param b carrier config bundle obtained from CarrierConfigManager
     * @return true if network is always on home network, false otherwise
     * @see CarrierConfigManager
     */
    protected final boolean alwaysOnHomeNetwork(BaseBundle b) {
        return b.getBoolean(CarrierConfigManager.KEY_FORCE_HOME_NETWORK_BOOL);
    }

    /**
     * Check if the network identifier has membership in the set of
     * network identifiers stored in the carrier config bundle.
     *
     * @param b carrier config bundle obtained from CarrierConfigManager
     * @param network The network identifier to check network existence in bundle
     * @param key The key to index into the bundle presenting a string array of
     *            networks to check membership
     * @return true if network has membership in bundle networks, false otherwise
     * @see CarrierConfigManager
     */
    private boolean isInNetwork(BaseBundle b, String network, String key) {
        String[] networks = b.getStringArray(key);

        if (networks != null && Arrays.asList(networks).contains(network)) {
            return true;
        }
        return false;
    }

    protected final boolean isRoamingInGsmNetwork(BaseBundle b, String network) {
        return isInNetwork(b, network, CarrierConfigManager.KEY_GSM_ROAMING_NETWORKS_STRING_ARRAY);
    }

    protected final boolean isNonRoamingInGsmNetwork(BaseBundle b, String network) {
        return isInNetwork(b, network, CarrierConfigManager.KEY_GSM_NONROAMING_NETWORKS_STRING_ARRAY);
    }

    protected final boolean isRoamingInCdmaNetwork(BaseBundle b, String network) {
        return isInNetwork(b, network, CarrierConfigManager.KEY_CDMA_ROAMING_NETWORKS_STRING_ARRAY);
    }

    protected final boolean isNonRoamingInCdmaNetwork(BaseBundle b, String network) {
        return isInNetwork(b, network, CarrierConfigManager.KEY_CDMA_NONROAMING_NETWORKS_STRING_ARRAY);
    }

    /** Check if the device is shutting down. */
    public boolean isDeviceShuttingDown() {
        return mDeviceShuttingDown;
    }

    /**
     * Consider dataRegState if voiceRegState is OOS to determine SPN to be displayed
     * @param ss service state.
     */
    protected int getCombinedRegState(ServiceState ss) {
        int regState = ss.getVoiceRegState();
        int dataRegState = ss.getDataRegState();
        if ((regState == ServiceState.STATE_OUT_OF_SERVICE
                || regState == ServiceState.STATE_POWER_OFF)
                && (dataRegState == ServiceState.STATE_IN_SERVICE)) {
            log("getCombinedRegState: return STATE_IN_SERVICE as Data is in service");
            regState = dataRegState;
        }
        return regState;
    }

    /**
     * Gets the carrier configuration values for a particular subscription.
     *
     * @return A {@link PersistableBundle} containing the config for the given subId,
     *         or default values for an invalid subId.
     */
    @NonNull
    private PersistableBundle getCarrierConfig() {
        CarrierConfigManager configManager = (CarrierConfigManager) mPhone.getContext()
                .getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager != null) {
            // If an invalid subId is used, this bundle will contain default values.
            PersistableBundle config = configManager.getConfigForSubId(mPhone.getSubId());
            if (config != null) {
                return config;
            }
        }
        // Return static default defined in CarrierConfigManager.
        return CarrierConfigManager.getDefaultConfig();
    }

    public LocaleTracker getLocaleTracker() {
        return mLocaleTracker;
    }

    String getCdmaEriText(int roamInd, int defRoamInd) {
        return mEriManager.getCdmaEriText(roamInd, defRoamInd);
    }

    private void updateOperatorNamePattern(PersistableBundle config) {
        String operatorNamePattern = config.getString(
                CarrierConfigManager.KEY_OPERATOR_NAME_FILTER_PATTERN_STRING);
        if (!TextUtils.isEmpty(operatorNamePattern)) {
            mOperatorNameStringPattern = Pattern.compile(operatorNamePattern);
            if (DBG) {
                log("mOperatorNameStringPattern: " + mOperatorNameStringPattern.toString());
            }
        }
    }

    private void updateOperatorNameForServiceState(ServiceState servicestate) {
        if (servicestate == null) {
            return;
        }

        servicestate.setOperatorName(
                filterOperatorNameByPattern(servicestate.getOperatorAlphaLong()),
                filterOperatorNameByPattern(servicestate.getOperatorAlphaShort()),
                servicestate.getOperatorNumeric());

        List<NetworkRegistrationInfo> networkRegistrationInfos =
                servicestate.getNetworkRegistrationInfoList();

        for (int i = 0; i < networkRegistrationInfos.size(); i++) {
            if (networkRegistrationInfos.get(i) != null) {
                updateOperatorNameForCellIdentity(
                        networkRegistrationInfos.get(i).getCellIdentity());
            }
        }
    }

    private void updateOperatorNameForCellIdentity(CellIdentity cellIdentity) {
        if (cellIdentity == null) {
            return;
        }
        cellIdentity.setOperatorAlphaLong(
                filterOperatorNameByPattern((String) cellIdentity.getOperatorAlphaLong()));
        cellIdentity.setOperatorAlphaShort(
                filterOperatorNameByPattern((String) cellIdentity.getOperatorAlphaShort()));
    }

    /**
     * To modify the operator name of CellInfo by pattern.
     *
     * @param cellInfos List of CellInfo{@link CellInfo}.
     */
    public void updateOperatorNameForCellInfo(List<CellInfo> cellInfos) {
        if (cellInfos == null || cellInfos.isEmpty()) {
            return;
        }
        for (CellInfo cellInfo : cellInfos) {
            if (cellInfo.isRegistered()) {
                updateOperatorNameForCellIdentity(cellInfo.getCellIdentity());
            }
        }
    }

    /**
     * To modify the operator name by pattern.
     *
     * @param operatorName Registered operator name
     * @return An operator name.
     */
    public String filterOperatorNameByPattern(String operatorName) {
        if (mOperatorNameStringPattern == null || TextUtils.isEmpty(operatorName)) {
            return operatorName;
        }
        Matcher matcher = mOperatorNameStringPattern.matcher(operatorName);
        if (matcher.find()) {
            if (matcher.groupCount() > 0) {
                operatorName = matcher.group(1);
            } else {
                log("filterOperatorNameByPattern: pattern no group");
            }
        }
        return operatorName;
    }
}
