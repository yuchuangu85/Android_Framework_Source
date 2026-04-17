/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static android.telephony.NetworkRegistrationInfo.DOMAIN_PS;
import static android.telephony.TelephonyManager.ACTION_2G_DISABLED_BY_CARRIER;
import static android.telephony.TelephonyManager.EXTRA_SUBSCRIPTION_ID;

import static com.android.internal.telephony.CommandException.Error.GENERIC_FAILURE;
import static com.android.internal.telephony.CommandException.Error.SIM_BUSY;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_DISABLE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_ENABLE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_ERASURE;
import static com.android.internal.telephony.CommandsInterface.CF_ACTION_REGISTRATION;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_ALL;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_ALL_CONDITIONAL;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_BUSY;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_NOT_REACHABLE;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_NO_REPLY;
import static com.android.internal.telephony.CommandsInterface.CF_REASON_UNCONDITIONAL;
import static com.android.internal.telephony.CommandsInterface.SERVICE_CLASS_VOICE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.SQLException;
import android.hardware.radio.modem.ImeiInfo;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.ResultReceiver;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.WorkSource;
import android.preference.PreferenceManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.provider.Telephony;
import android.sysprop.TelephonyProperties;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.AccessNetworkConstants.TransportType;
import android.telephony.Annotation.DataActivityType;
import android.telephony.Annotation.RadioPowerState;
import android.telephony.BarringInfo;
import android.telephony.CarrierConfigManager;
import android.telephony.CellBroadcastIdRange;
import android.telephony.CellIdentity;
import android.telephony.CellularIdentifierDisclosure;
import android.telephony.ImsiEncryptionInfo;
import android.telephony.LinkCapacityEstimate;
import android.telephony.NetworkScanRequest;
import android.telephony.NetworkSecurityEvent;
import android.telephony.PhoneNumberUtils;
import android.telephony.RadioAccessFamily;
import android.telephony.SecurityAlgorithmUpdate;
import android.telephony.ServiceState;
import android.telephony.ServiceState.RilRadioTechnology;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.UiccAccessRule;
import android.telephony.UssdResponse;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Pair;

import com.android.ims.ImsManager;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.data.AccessNetworksManager;
import com.android.internal.telephony.data.DataNetworkController;
import com.android.internal.telephony.data.LinkBandwidthEstimator;
import com.android.internal.telephony.domainselection.DomainSelectionResolver;
import com.android.internal.telephony.emergency.EmergencyNumberTracker;
import com.android.internal.telephony.emergency.EmergencyStateTracker;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.flags.Flags;
import com.android.internal.telephony.gsm.GsmMmiCode;
import com.android.internal.telephony.gsm.SsData;
import com.android.internal.telephony.imsphone.ImsPhone;
import com.android.internal.telephony.imsphone.ImsPhoneCallTracker;
import com.android.internal.telephony.imsphone.ImsPhoneMmiCode;
import com.android.internal.telephony.metrics.VoiceCallSessionStats;
import com.android.internal.telephony.security.CellularIdentifierDisclosureNotifier;
import com.android.internal.telephony.security.CellularNetworkSecuritySafetySource;
import com.android.internal.telephony.security.NullCipherNotifier;
import com.android.internal.telephony.subscription.SubscriptionInfoInternal;
import com.android.internal.telephony.subscription.SubscriptionManagerService.SubscriptionManagerServiceCallback;
import com.android.internal.telephony.test.SimulatedRadioControl;
import com.android.internal.telephony.uicc.IccCardStatus;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.telephony.uicc.IccVmNotSupportedException;
import com.android.internal.telephony.uicc.IsimRecords;
import com.android.internal.telephony.uicc.IsimUiccRecords;
import com.android.internal.telephony.uicc.SIMRecords;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.UiccPort;
import com.android.internal.telephony.uicc.UiccProfile;
import com.android.internal.telephony.uicc.UiccSlot;
import com.android.internal.telephony.util.ArrayUtils;
import com.android.telephony.Rlog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * @hide
 */
public class GsmCdmaPhone extends Phone {
    // NOTE that LOG_TAG here is "GsmCdma", which means that log messages
    // from this file will go into the radio log rather than the main
    // log.  (Use "adb logcat -b radio" to see them.)
    public static final String LOG_TAG = "GsmCdmaPhone";
    private static final boolean DBG = true;

    /** Required throughput change between unsolicited LinkCapacityEstimate reports. */
    private static final int REPORTING_HYSTERESIS_KBPS = 50;
    /** Minimum time between unsolicited LinkCapacityEstimate reports. */
    private static final int REPORTING_HYSTERESIS_MILLIS = 3000;

    //GSM
    // Key used to read/write voice mail number
    private static final String VM_NUMBER = "vm_number_key";
    // Key used to read/write the SIM IMSI used for storing the voice mail
    private static final String VM_SIM_IMSI = "vm_sim_imsi_key";
    /** List of Registrants to receive Supplementary Service Notifications. */
    // Key used to read/write the current sub Id. Updated on SIM loaded.
    public static final String CURR_SUBID = "curr_subid";
    // Key used to read/write the carrier 2g protection default state.
    public static final String PREF_KEY_HAS_APPLIED_2G_PROTECTION_DEFAULT =
            "pref_key_has_applied_2g_protection_default";
    private RegistrantList mSsnRegistrants = new RegistrantList();

    //CDMA
    // Default Emergency Callback Mode exit timer
    private static final long DEFAULT_ECM_EXIT_TIMER_VALUE = 300000;
    public static final int RESTART_ECM_TIMER = 0; // restart Ecm timer
    public static final int CANCEL_ECM_TIMER = 1; // cancel Ecm timer
    private PowerManager.WakeLock mWakeLock;
    // mEcmExitRespRegistrant is informed after the phone has been exited
    @UnsupportedAppUsage
    private Registrant mEcmExitRespRegistrant;
    private String mEsn;
    private String mMeid;
    private Boolean mUiccApplicationsEnabled = null;
    // keeps track of when we have triggered an emergency call due to the ril.test.emergencynumber
    // param being set and we should generate a simulated exit from the modem upon exit of ECbM.
    private boolean mIsTestingEmergencyCallbackMode = false;
    @VisibleForTesting
    public static int ENABLE_UICC_APPS_MAX_RETRIES = 3;
    private static final int REAPPLY_UICC_APPS_SETTING_RETRY_TIME_GAP_IN_MS = 5000;

    // A runnable which is used to automatically exit from Ecm after a period of time.
    private Runnable mExitEcmRunnable = new Runnable() {
        @Override
        public void run() {
            exitEmergencyCallbackMode();
        }
    };

    /** PHONE_TYPE_CDMA_LTE in addition to RuimRecords needs access to SIMRecords and
     * IsimUiccRecords
     */
    private SIMRecords mSimRecords;

    // For non-persisted manual network selection
    private String mManualNetworkSelectionPlmn;

    //Common
    // Instance Variables
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private IsimUiccRecords mIsimUiccRecords;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public GsmCdmaCallTracker mCT;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public ServiceStateTracker mSST;
    public EmergencyNumberTracker mEmergencyNumberTracker;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private ArrayList <MmiCode> mPendingMMIs = new ArrayList<MmiCode>();
    private IccPhoneBookInterfaceManager mIccPhoneBookIntManager;

    private int mPrecisePhoneType;

    // mEcmTimerResetRegistrants are informed after Ecm timer is canceled or re-started
    private final RegistrantList mEcmTimerResetRegistrants = new RegistrantList();

    private final RegistrantList mVolteSilentRedialRegistrants = new RegistrantList();
    private DialArgs mDialArgs = null;
    private final RegistrantList mEmergencyDomainSelectedRegistrants = new RegistrantList();
    private String mImei;
    private String mImeiSv;
    private String mVmNumber;
    private int mImeiType = IMEI_TYPE_UNKNOWN;
    private int mSimState = TelephonyManager.SIM_STATE_UNKNOWN;

    @VisibleForTesting
    public CellBroadcastConfigTracker mCellBroadcastConfigTracker =
            CellBroadcastConfigTracker.make(this, null, true);

    private boolean mIsNullCipherAndIntegritySupported = false;
    private boolean mIsIdentifierDisclosureTransparencySupported = false;
    private boolean mIsNullCipherNotificationSupported = false;

    /**
     * Queue for holding cellular events that arrive before a valid subscription ID is available.
     * These messages are held until the SIM state is {@link TelephonyManager#SIM_STATE_LOADED},
     * at which point they are re-processed.
     */
    @VisibleForTesting
    public final List<Message> mCellularEventMessages =
            Collections.synchronizedList(new ArrayList<>());
    private static final Object sBlocker = new Object();

    // Create Cfu (Call forward unconditional) so that dialing number &
    // mOnComplete (Message object passed by client) can be packed &
    // given as a single Cfu object as user data to RIL.
    private static class Cfu {
        final String mSetCfNumber;
        final Message mOnComplete;

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        Cfu(String cfNumber, Message onComplete) {
            mSetCfNumber = cfNumber;
            mOnComplete = onComplete;
        }
    }

    /**
     * Used to create ImsManager instances, which may be injected during testing.
     */
    @VisibleForTesting
    public interface ImsManagerFactory {
        /**
         * Create a new instance of ImsManager for the specified phoneId.
         */
        ImsManager create(Context context, int phoneId);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private IccSmsInterfaceManager mIccSmsInterfaceManager;
    private @ServiceState.RegState int mTelecomVoiceServiceStateOverride =
            ServiceState.STATE_OUT_OF_SERVICE;

    private CarrierKeyDownloadManager mCDM;
    private CarrierInfoManager mCIM;

    private final ImsManagerFactory mImsManagerFactory;
    private final CarrierPrivilegesTracker mCarrierPrivilegesTracker;

    private final SubscriptionManager.OnSubscriptionsChangedListener mSubscriptionsChangedListener;
    private final CallWaitingController mCallWaitingController;

    private CellularNetworkSecuritySafetySource mSafetySource;
    private CellularIdentifierDisclosureNotifier mIdentifierDisclosureNotifier;
    private NullCipherNotifier mNullCipherNotifier;

    /**
     * Temporary placeholder variables until b/312788638 is resolved, whereupon these should be
     * ported to TelephonyManager.
     */
    // Set via Carrier Config
    private static final Integer N1_MODE_DISALLOWED_REASON_CARRIER = 1;
    // Set via a call to the method on Phone; the only caller is IMS, and all of this code will
    // need to be updated to a voting mechanism (...enabled for reason...) if additional callers
    // are desired.
    private static final Integer N1_MODE_DISALLOWED_REASON_IMS = 2;

    // Set of use callers/reasons why N1 Mode is disallowed. If the set is empty, it's allowed.
    private final Set<Integer> mN1ModeDisallowedReasons = new ArraySet<>();

    // If this value is null, then the modem value is unknown. If a caller explicitly sets the
    // N1 mode, this value will be initialized before any attempt to set the value in the modem.
    private Boolean mModemN1Mode = null;

    // Constructors

    public GsmCdmaPhone(Context context, CommandsInterface ci, PhoneNotifier notifier, int phoneId,
                        int precisePhoneType, TelephonyComponentFactory telephonyComponentFactory,
            @NonNull FeatureFlags featureFlags) {
        this(context, ci, notifier, false, phoneId, precisePhoneType, telephonyComponentFactory,
                featureFlags);
    }

    public GsmCdmaPhone(Context context, CommandsInterface ci, PhoneNotifier notifier,
                        boolean unitTestMode, int phoneId, int precisePhoneType,
                        TelephonyComponentFactory telephonyComponentFactory,
            @NonNull FeatureFlags featureFlags) {
        this(context, ci, notifier,
                unitTestMode, phoneId, precisePhoneType,
                telephonyComponentFactory,
                ImsManager::getInstance, featureFlags);
    }

    public GsmCdmaPhone(Context context, CommandsInterface ci, PhoneNotifier notifier,
            boolean unitTestMode, int phoneId, int precisePhoneType,
            TelephonyComponentFactory telephonyComponentFactory,
            ImsManagerFactory imsManagerFactory, @NonNull FeatureFlags featureFlags) {
        super(precisePhoneType == PhoneConstants.PHONE_TYPE_GSM ? "GSM" : "CDMA",
                notifier, context, ci, unitTestMode, phoneId, telephonyComponentFactory,
                featureFlags);
        // phone type needs to be set before other initialization as other objects rely on it
        mPrecisePhoneType = precisePhoneType;
        mVoiceCallSessionStats = new VoiceCallSessionStats(mPhoneId, this, featureFlags);
        mImsManagerFactory = imsManagerFactory;
        initOnce(ci);
        initRatSpecific(precisePhoneType);
        // CarrierSignalAgent uses CarrierActionAgent in construction so it needs to be created
        // after CarrierActionAgent.
        mCarrierActionAgent = mTelephonyComponentFactory.inject(CarrierActionAgent.class.getName())
                .makeCarrierActionAgent(this);
        mCarrierSignalAgent = mTelephonyComponentFactory.inject(CarrierSignalAgent.class.getName())
                .makeCarrierSignalAgent(this);
        mAccessNetworksManager = mTelephonyComponentFactory
                .inject(AccessNetworksManager.class.getName())
                .makeAccessNetworksManager(this, getLooper(), featureFlags);
        // SST/DSM depends on SSC, so SSC is instanced before SST/DSM
        mSignalStrengthController = mTelephonyComponentFactory.inject(
                SignalStrengthController.class.getName()).makeSignalStrengthController(this);
        mSST = mTelephonyComponentFactory.inject(ServiceStateTracker.class.getName())
                .makeServiceStateTracker(this, this.mCi, featureFlags);
        if (hasCalling() || hasMessaging()) {
            mEmergencyNumberTracker = mTelephonyComponentFactory
                    .inject(EmergencyNumberTracker.class.getName()).makeEmergencyNumberTracker(
                            this, this.mCi, mFeatureFlags);
        }
        mDeviceStateMonitor = mTelephonyComponentFactory.inject(DeviceStateMonitor.class.getName())
                .makeDeviceStateMonitor(this, mFeatureFlags);

        // DisplayInfoController creates an OverrideNetworkTypeController, which uses
        // DeviceStateMonitor so needs to be crated after it is instantiated.
        mDisplayInfoController = mTelephonyComponentFactory.inject(
                DisplayInfoController.class.getName())
                .makeDisplayInfoController(this, featureFlags);

        mDataNetworkController = mTelephonyComponentFactory.inject(
                DataNetworkController.class.getName())
                .makeDataNetworkController(this, getLooper(), featureFlags);

        mCarrierResolver = mTelephonyComponentFactory.inject(CarrierResolver.class.getName())
                .makeCarrierResolver(this, featureFlags);
        mCarrierPrivilegesTracker = new CarrierPrivilegesTracker(
                Looper.myLooper(), this, context, featureFlags);

        getCarrierActionAgent().registerForCarrierAction(
                CarrierActionAgent.CARRIER_ACTION_SET_METERED_APNS_ENABLED, this,
                EVENT_SET_CARRIER_DATA_ENABLED, null, false);

        mSST.registerForNetworkAttached(this, EVENT_REGISTERED_TO_NETWORK, null);
        mSST.registerForVoiceRegStateOrRatChanged(this, EVENT_VRS_OR_RAT_CHANGED, null);
        mSST.getServiceStateStats().registerDataNetworkControllerCallback();

        mSubscriptionManagerService.registerCallback(new SubscriptionManagerServiceCallback(
                this::post) {
            @Override
            public void onUiccApplicationsEnabledChanged(int subId) {
                reapplyUiccAppsEnablementIfNeeded(ENABLE_UICC_APPS_MAX_RETRIES);
            }
        });

        mLinkBandwidthEstimator = mTelephonyComponentFactory
                .inject(LinkBandwidthEstimator.class.getName())
                .makeLinkBandwidthEstimator(this, getLooper());

        mCallWaitingController = new CallWaitingController(this);

        if (hasCalling()) {
            loadTtyMode();

            CallManager.getInstance(context).registerPhone(this);
        }

        mSubscriptionsChangedListener =
                new SubscriptionManager.OnSubscriptionsChangedListener() {
            @Override
            public void onSubscriptionsChanged() {
                sendEmptyMessage(EVENT_SUBSCRIPTIONS_CHANGED);
            }
        };

        SubscriptionManager subMan = context.getSystemService(SubscriptionManager.class);
        subMan.addOnSubscriptionsChangedListener(
                new HandlerExecutor(this), mSubscriptionsChangedListener);

        logd("GsmCdmaPhone: constructor: sub = " + mPhoneId);
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Rlog.d(LOG_TAG, "mBroadcastReceiver: action " + intent.getAction());
            String action = intent.getAction();
            if (CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED.equals(action)) {
                // Only handle carrier config changes for this phone id.
                if (mPhoneId == intent.getIntExtra(CarrierConfigManager.EXTRA_SLOT_INDEX, -1)) {
                    sendMessage(obtainMessage(EVENT_CARRIER_CONFIG_CHANGED));
                }
            } else if (TelecomManager.ACTION_CURRENT_TTY_MODE_CHANGED.equals(action)) {
                int ttyMode = intent.getIntExtra(
                        TelecomManager.EXTRA_CURRENT_TTY_MODE, TelephonyManager.TTY_MODE_OFF);
                updateTtyMode(ttyMode);
            } else if (TelecomManager.ACTION_TTY_PREFERRED_MODE_CHANGED.equals(action)) {
                int newPreferredTtyMode = intent.getIntExtra(
                        TelecomManager.EXTRA_TTY_PREFERRED_MODE, TelephonyManager.TTY_MODE_OFF);
                updateUiTtyMode(newPreferredTtyMode);
            } else if (TelephonyManager.ACTION_SIM_APPLICATION_STATE_CHANGED.equals(action)
                           || TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED.equals(action)) {
                if (mPhoneId == intent.getIntExtra(
                        SubscriptionManager.EXTRA_SLOT_INDEX,
                        SubscriptionManager.INVALID_SIM_SLOT_INDEX)) {
                    mSimState = intent.getIntExtra(TelephonyManager.EXTRA_SIM_STATE,
                            TelephonyManager.SIM_STATE_UNKNOWN);
                    if (mSimState == TelephonyManager.SIM_STATE_LOADED
                            && !mCellularEventMessages.isEmpty()) {
                        synchronized (sBlocker) {
                            logd("Executing CellularEventMessages size: "
                                    + mCellularEventMessages.size());
                            Iterator<Message> iterator = mCellularEventMessages.iterator();
                            while (iterator.hasNext()) {
                                sendMessage(iterator.next());
                                iterator.remove();
                            }
                            sBlocker.notifyAll();
                        }
                    }
                    if (mSimState == TelephonyManager.SIM_STATE_LOADED
                            && currentSlotSubIdChanged()) {
                        setNetworkSelectionModeAutomatic(null);
                    } else if (mSimState == TelephonyManager.SIM_STATE_ABSENT
                            || mSimState == TelephonyManager.SIM_STATE_UNKNOWN) {
                        // Clear allowed services while SIM is absent or unknown e.g. modem crash.
                        // Telephony will update allowed services after SIM is loaded.
                        clearAllowedImsServices();
                    }
                }
            }
        }
    };

    private void initOnce(CommandsInterface ci) {
        if (ci instanceof SimulatedRadioControl) {
            mSimulatedRadioControl = (SimulatedRadioControl) ci;
        }

        if (hasCalling()) {
            mCT = mTelephonyComponentFactory.inject(GsmCdmaCallTracker.class.getName())
                    .makeGsmCdmaCallTracker(this, mFeatureFlags);
        }
        mIccPhoneBookIntManager = mTelephonyComponentFactory
                .inject(IccPhoneBookInterfaceManager.class.getName())
                .makeIccPhoneBookInterfaceManager(this);
        PowerManager pm
                = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOG_TAG);
        mIccSmsInterfaceManager = mTelephonyComponentFactory
                .inject(IccSmsInterfaceManager.class.getName())
                .makeIccSmsInterfaceManager(this, mFeatureFlags);

        mCi.registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);
        mCi.registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        mCi.registerForOn(this, EVENT_RADIO_ON, null);
        mCi.registerForRadioStateChanged(this, EVENT_RADIO_STATE_CHANGED, null);
        mCi.registerUiccApplicationEnablementChanged(this,
                EVENT_UICC_APPS_ENABLEMENT_STATUS_CHANGED,
                null);
        mCi.setOnSuppServiceNotification(this, EVENT_SSN, null);
        mCi.setOnRegistrationFailed(this, EVENT_REGISTRATION_FAILED, null);
        mCi.registerForBarringInfoChanged(this, EVENT_BARRING_INFO_CHANGED, null);

        //GSM
        mCi.setOnUSSD(this, EVENT_USSD, null);
        mCi.setOnSs(this, EVENT_SS, null);

        mCi.setEmergencyCallbackMode(this, EVENT_EMERGENCY_CALLBACK_MODE_ENTER, null);
        mCi.registerForExitEmergencyCallbackMode(this, EVENT_EXIT_EMERGENCY_CALLBACK_RESPONSE,
                null);
        mCi.registerForModemReset(this, EVENT_MODEM_RESET, null);

        mCi.registerForVoiceRadioTechChanged(this, EVENT_VOICE_RADIO_TECH_CHANGED, null);
        mCi.registerForLceInfo(this, EVENT_LINK_CAPACITY_CHANGED, null);
        mCi.registerForCarrierInfoForImsiEncryption(this,
                EVENT_RESET_CARRIER_KEY_IMSI_ENCRYPTION, null);
        mCi.registerForTriggerImsDeregistration(this, EVENT_IMS_DEREGISTRATION_TRIGGERED, null);
        mCi.registerForNotifyAnbr(this, EVENT_TRIGGER_NOTIFY_ANBR, null);
        IntentFilter filter = new IntentFilter(
                CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        filter.addAction(TelecomManager.ACTION_CURRENT_TTY_MODE_CHANGED);
        filter.addAction(TelecomManager.ACTION_TTY_PREFERRED_MODE_CHANGED);
        filter.addAction(TelephonyManager.ACTION_SIM_APPLICATION_STATE_CHANGED);
        if (mFeatureFlags.allowedServices()) {
            filter.addAction(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED);
        }
        mContext.registerReceiver(mBroadcastReceiver, filter,
                android.Manifest.permission.MODIFY_PHONE_STATE, null, Context.RECEIVER_EXPORTED);

        mCDM = new CarrierKeyDownloadManager(this);

        mCIM = new CarrierInfoManager();

        mCi.registerForImeiMappingChanged(this, EVENT_IMEI_MAPPING_CHANGED, null);

        mSafetySource = mTelephonyComponentFactory
                .makeCellularNetworkSecuritySafetySource(mContext);

        logi(
                "enable_identifier_disclosure_transparency_unsol_events is on. Registering for "
                        + "cellular identifier disclosures from phone "
                        + getPhoneId());
        mIdentifierDisclosureNotifier =
                mTelephonyComponentFactory
                        .inject(CellularIdentifierDisclosureNotifier.class.getName())
                        .makeIdentifierDisclosureNotifier(mSafetySource);
        mCi.registerForCellularIdentifierDisclosures(
                this, EVENT_CELL_IDENTIFIER_DISCLOSURE, null);

        logi(
                "enable_modem_cipher_transparency_unsol_events is on. Registering for security "
                        + "algorithm updates from phone "
                        + getPhoneId());
        mNullCipherNotifier =
                mTelephonyComponentFactory
                        .inject(NullCipherNotifier.class.getName())
                        .makeNullCipherNotifier(mSafetySource);
        mCi.registerForSecurityAlgorithmUpdates(
                this, EVENT_SECURITY_ALGORITHM_UPDATE, null);
        mCi.registerForNetworkSecurityEvents(this, EVENT_NETWORK_SECURITY_EVENTS, null);


        initializeCarrierApps();
    }

    private void initRatSpecific(int precisePhoneType) {
        mPendingMMIs.clear();
        mIccPhoneBookIntManager.updateIccRecords(null);

        mPrecisePhoneType = precisePhoneType;
        logd("Precise phone type " + mPrecisePhoneType);

        TelephonyManager tm = TelephonyManager.from(mContext);
        UiccProfile uiccProfile = getUiccProfile();
        mCi.setPhoneType(PhoneConstants.PHONE_TYPE_GSM);
        tm.setPhoneType(getPhoneId(), PhoneConstants.PHONE_TYPE_GSM);
        if (uiccProfile != null) {
            uiccProfile.setVoiceRadioTech(ServiceState.RIL_RADIO_TECHNOLOGY_UMTS);
        }
    }

    /**
     * Initialize the carrier apps.
     */
    private void initializeCarrierApps() {
        // Only perform on the default phone. There is no need to do it twice on the DSDS device.
        if (mPhoneId != 0) return;

        logd("initializeCarrierApps");
        mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Remove this line after testing
                if (Intent.ACTION_USER_FOREGROUND.equals(intent.getAction())) {
                    UserHandle userHandle = intent.getParcelableExtra(Intent.EXTRA_USER);
                    // If couldn't get current user ID, guess it's 0.
                    CarrierAppUtils.disableCarrierAppsUntilPrivileged(mContext.getOpPackageName(),
                            TelephonyManager.getDefault(),
                            userHandle != null ? userHandle.getIdentifier() : 0, mContext);
                }
            }
        }, new IntentFilter(Intent.ACTION_USER_FOREGROUND), null, null);
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(mContext.getOpPackageName(),
                TelephonyManager.getDefault(), ActivityManager.getCurrentUser(), mContext);
    }

    private void updateLinkCapacityEstimate(List<LinkCapacityEstimate> linkCapacityEstimateList) {
        if (DBG) logd("updateLinkCapacityEstimate: lce list=" + linkCapacityEstimateList);
        if (linkCapacityEstimateList == null) {
            return;
        }
        notifyLinkCapacityEstimateChanged(linkCapacityEstimateList);
    }

    @Override
    protected void finalize() {
        if(DBG) logd("GsmCdmaPhone finalized");
        if (mWakeLock != null && mWakeLock.isHeld()) {
            Rlog.e(LOG_TAG, "UNEXPECTED; mWakeLock is held when finalizing.");
            mWakeLock.release();
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @Override
    @NonNull
    public ServiceState getServiceState() {
        ServiceState baseSs = mSST != null ? mSST.getServiceState() : new ServiceState();
        ServiceState imsSs = mImsPhone != null ? mImsPhone.getServiceState() : new ServiceState();
        return mergeVoiceServiceStates(baseSs, imsSs, mTelecomVoiceServiceStateOverride);
    }

    @Override
    public void setVoiceServiceStateOverride(boolean hasService) {
        int newOverride =
                hasService ? ServiceState.STATE_IN_SERVICE : ServiceState.STATE_OUT_OF_SERVICE;
        boolean changed = newOverride != mTelecomVoiceServiceStateOverride;
        mTelecomVoiceServiceStateOverride = newOverride;
        if (changed && mSST != null) {
            mSST.onTelecomVoiceServiceStateOverrideChanged();
            mSST.getServiceStateStats().onVoiceServiceStateOverrideChanged(hasService);
        }
    }

    @Override
    public void getCellIdentity(WorkSource workSource, Message rspMsg) {
        mSST.requestCellIdentity(workSource, rspMsg);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @Override
    public PhoneConstants.State getState() {
        if (!hasCalling()) return PhoneConstants.State.IDLE;

        if (mImsPhone != null) {
            PhoneConstants.State imsState = mImsPhone.getState();
            if (imsState != PhoneConstants.State.IDLE) {
                return imsState;
            }
        }

        return mCT.mState;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @Override
    public int getPhoneType() {
        return PhoneConstants.PHONE_TYPE_GSM;
    }

    @Override
    public ServiceStateTracker getServiceStateTracker() {
        return mSST;
    }

    @Override
    public EmergencyNumberTracker getEmergencyNumberTracker() {
        return mEmergencyNumberTracker;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @Override
    public CallTracker getCallTracker() {
        return mCT;
    }

    @Override
    public AccessNetworksManager getAccessNetworksManager() {
        return mAccessNetworksManager;
    }

    @Override
    public DeviceStateMonitor getDeviceStateMonitor() {
        return mDeviceStateMonitor;
    }

    @Override
    public DisplayInfoController getDisplayInfoController() {
        return mDisplayInfoController;
    }

    @Override
    public SignalStrengthController getSignalStrengthController() {
        return mSignalStrengthController;
    }

    @Override
    public void updateVoiceMail() {
        int countVoiceMessages = 0;
        IccRecords r = mIccRecords.get();
        if (r != null) {
            // get voice mail count from SIM
            countVoiceMessages = r.getVoiceMessageCount();
        }
        if (countVoiceMessages == IccRecords.DEFAULT_VOICE_MESSAGE_COUNT) {
            countVoiceMessages = getStoredVoiceMessageCount();
        }
        logd("updateVoiceMail countVoiceMessages = " + countVoiceMessages
                + " subId " + getSubId());
        setVoiceMessageCount(countVoiceMessages);
    }

    @Override
    public List<? extends MmiCode>
    getPendingMmiCodes() {
        return mPendingMMIs;
    }

    @Override
    public @DataActivityType int getDataActivityState() {
        return getDataNetworkController().getDataActivity();
    }

    /**
     * Notify any interested party of a Phone state change
     * {@link com.android.internal.telephony.PhoneConstants.State}
     */
    public void notifyPhoneStateChanged() {
        mNotifier.notifyPhoneState(this);
    }

    /**
     * Notify registrants of a change in the call state. This notifies changes in
     * {@link com.android.internal.telephony.Call.State}. Use this when changes
     * in the precise call state are needed, else use notifyPhoneStateChanged.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void notifyPreciseCallStateChanged() {
        /* we'd love it if this was package-scoped*/
        AsyncResult ar = new AsyncResult(null, this, null);
        mPreciseCallStateRegistrants.notifyRegistrants(ar);

        mNotifier.notifyPreciseCallState(this, null, null, null);
    }

    public void notifyNewRingingConnection(Connection c) {
        super.notifyNewRingingConnectionP(c);
    }

    public void notifyDisconnect(Connection cn) {
        mDisconnectRegistrants.notifyResult(cn);

        mNotifier.notifyDisconnectCause(this, cn.getDisconnectCause(),
                cn.getPreciseDisconnectCause());
    }

    public void notifyUnknownConnection(Connection cn) {
        super.notifyUnknownConnectionP(cn);
    }

    @Override
    public boolean isInEmergencyCall() {
        if (!hasCalling()) {
            return false;
        } else {
            if (mFeatureFlags.deleteCdma()) {
                return mContext.getSystemService(TelecomManager.class).isInEmergencyCall();
            }
            return mCT.isInEmergencyCall();
        }
    }

    @Override
    public boolean isInEmergencySmsMode() {
        return super.isInEmergencySmsMode()
                || (mImsPhone != null && mImsPhone.isInEmergencySmsMode());
    }

    //CDMA
    private void sendEmergencyCallbackModeChange(){
        if (mFeatureFlags.deleteCdma()) return;
        //Send an Intent
        Intent intent = new Intent(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        intent.putExtra(TelephonyManager.EXTRA_PHONE_IN_ECM_STATE, isInEcm());
        SubscriptionManager.putPhoneIdAndSubIdExtra(intent, getPhoneId());
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        logi("sendEmergencyCallbackModeChange");
    }

    public void notifySuppServiceFailed(SuppService code) {
        mSuppServiceFailedRegistrants.notifyResult(code);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void notifyServiceStateChanged(ServiceState ss) {
        super.notifyServiceStateChangedP(ss);
    }

    void notifyServiceStateChangedForSubId(ServiceState ss, int subId) {
        super.notifyServiceStateChangedPForSubId(ss, subId);
    }

    /**
     * Notify that the cell location has changed.
     *
     * @param cellIdentity the new CellIdentity
     */
    public void notifyLocationChanged(CellIdentity cellIdentity) {
        mNotifier.notifyCellLocation(this, cellIdentity);
    }

    @Override
    public void notifyCallForwardingIndicator() {
        mNotifier.notifyCallForwardingChanged(this);
    }

    @Override
    public void registerForSuppServiceNotification(
            Handler h, int what, Object obj) {
        mSsnRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForSuppServiceNotification(Handler h) {
        mSsnRegistrants.remove(h);
    }

    @Override
    public void registerForSimRecordsLoaded(Handler h, int what, Object obj) {
        mSimRecordsLoadedRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForSimRecordsLoaded(Handler h) {
        mSimRecordsLoadedRegistrants.remove(h);
    }

    @Override
    public void acceptCall(int videoState) throws CallStateException {
        if (!hasCalling()) throw new CallStateException();
        Phone imsPhone = mImsPhone;
        if ( imsPhone != null && imsPhone.getRingingCall().isRinging() ) {
            imsPhone.acceptCall(videoState);
        } else {
            mCT.acceptCall();
        }
    }

    @Override
    public void rejectCall() throws CallStateException {
        if (!hasCalling()) throw new CallStateException();
        mCT.rejectCall();
    }

    @Override
    public void switchHoldingAndActive() throws CallStateException {
        mCT.switchWaitingOrHoldingAndActive();
    }

    @Override
    public String getIccSerialNumber() {
        IccRecords r = mIccRecords.get();
        return (r != null) ? r.getIccId() : null;
    }

    @Override
    public String getFullIccSerialNumber() {
        IccRecords r = mIccRecords.get();
        return (r != null) ? r.getFullIccId() : null;
    }

    @Override
    public boolean canConference() {
        if (!hasCalling()) return false;
        if (mImsPhone != null && mImsPhone.canConference()) {
            return true;
        }
        return mCT.canConference();
    }

    @Override
    public void conference() {
        if (mImsPhone != null && mImsPhone.canConference()) {
            logd("conference() - delegated to IMS phone");
            try {
                mImsPhone.conference();
            } catch (CallStateException e) {
                loge(e.toString());
            }
            return;
        }
        mCT.conference();
    }

    @Override
    public void enableEnhancedVoicePrivacy(boolean enable, Message onComplete) {
        loge("enableEnhancedVoicePrivacy: not expected on GSM");
    }

    @Override
    public void getEnhancedVoicePrivacy(Message onComplete) {
        loge("getEnhancedVoicePrivacy: not expected on GSM");
    }

    @Override
    public void clearDisconnected() {
        if (!hasCalling()) return;
        mCT.clearDisconnected();
    }

    @Override
    public boolean canTransfer() {
        if (hasCalling()) {
            return mCT.canTransfer();
        } else {
            loge("canTransfer: not possible in CDMA");
            return false;
        }
    }

    @Override
    public void explicitCallTransfer() {
        if (hasCalling()) {
            mCT.explicitCallTransfer();
        } else {
            loge("explicitCallTransfer: not possible in CDMA");
        }
    }

    @Override
    public GsmCdmaCall getForegroundCall() {
        if (!hasCalling()) return null;
        return mCT.mForegroundCall;
    }

    @Override
    public GsmCdmaCall getBackgroundCall() {
        if (!hasCalling()) return null;
        return mCT.mBackgroundCall;
    }

    @Override
    public Call getRingingCall() {
        if (!hasCalling()) return null;
        Phone imsPhone = mImsPhone;
        // It returns the ringing call of ImsPhone if the ringing call of GSMPhone isn't ringing.
        // In CallManager.registerPhone(), it always registers ringing call of ImsPhone, because
        // the ringing call of GSMPhone isn't ringing. Consequently, it can't answer GSM call
        // successfully by invoking TelephonyManager.answerRingingCall() since the implementation
        // in PhoneInterfaceManager.answerRingingCallInternal() could not get the correct ringing
        // call from CallManager. So we check the ringing call state of imsPhone first as
        // accpetCall() does.
        if ( imsPhone != null && imsPhone.getRingingCall().isRinging()) {
            return imsPhone.getRingingCall();
        }
        //It returns the ringing connections which during SRVCC handover
        if (!mCT.mRingingCall.isRinging()
                && mCT.getRingingHandoverConnection() != null
                && mCT.getRingingHandoverConnection().getCall() != null
                && mCT.getRingingHandoverConnection().getCall().isRinging()) {
            return mCT.getRingingHandoverConnection().getCall();
        }
        return mCT.mRingingCall;
    }

    @Override
    @NonNull
    public CarrierPrivilegesTracker getCarrierPrivilegesTracker() {
        return mCarrierPrivilegesTracker;
    }

    /**
     * Amends {@code baseSs} if its voice registration state is {@code OUT_OF_SERVICE}.
     *
     * <p>Even if the device has lost the CS link to the tower, there are two potential additional
     * sources of voice capability not directly saved inside ServiceStateTracker:
     *
     * <ul>
     *   <li>IMS voice registration state ({@code imsSs}) - if this is {@code IN_SERVICE} for voice,
     *       we substite {@code baseSs#getDataRegState} as the final voice service state (ImsService
     *       reports {@code IN_SERVICE} for its voice registration state even if the device has lost
     *       the physical link to the tower)
     *   <li>OTT voice capability provided through telecom ({@code telecomSs}) - if this is {@code
     *       IN_SERVICE}, we directly substitute it as the final voice service state
     * </ul>
     */
    private static ServiceState mergeVoiceServiceStates(
            ServiceState baseSs, ServiceState imsSs, @ServiceState.RegState int telecomSs) {
        if (baseSs.getState() == ServiceState.STATE_IN_SERVICE) {
            // No need to merge states if the baseSs is IN_SERVICE.
            return baseSs;
        }
        // If any of the following additional sources are IN_SERVICE, we use that since voice calls
        // can be routed through something other than the CS link.
        @ServiceState.RegState int finalVoiceSs = ServiceState.STATE_OUT_OF_SERVICE;
        if (telecomSs == ServiceState.STATE_IN_SERVICE) {
            // If telecom reports there's a PhoneAccount that can provide voice service
            // (CAPABILITY_VOICE_CALLING_AVAILABLE), then we trust that info as it may account for
            // external possibilities like wi-fi calling provided by the SIM call manager app. Note
            // that CAPABILITY_PLACE_EMERGENCY_CALLS is handled separately.
            finalVoiceSs = telecomSs;
        } else if (imsSs.getState() == ServiceState.STATE_IN_SERVICE) {
            // Voice override for IMS case. In this case, voice registration is OUT_OF_SERVICE, but
            // IMS is available, so use data registration state as a basis for determining
            // whether or not the physical link is available.
            finalVoiceSs = baseSs.getDataRegistrationState();
        }
        if (finalVoiceSs != ServiceState.STATE_IN_SERVICE) {
            // None of the additional sources provide a usable route, and they only use IN/OUT.
            return baseSs;
        }
        ServiceState newSs = new ServiceState(baseSs);
        newSs.setVoiceRegState(finalVoiceSs);
        newSs.setEmergencyOnly(false); // Must be IN_SERVICE if we're here
        return newSs;
    }

    private boolean handleCallDeflectionIncallSupplementaryService(
            String dialString) {
        if (!hasCalling() || dialString.length() > 1) {
            return false;
        }

        if (getRingingCall().getState() != GsmCdmaCall.State.IDLE) {
            if (DBG) logd("MmiCode 0: rejectCall");
            try {
                mCT.rejectCall();
            } catch (CallStateException e) {
                if (DBG) Rlog.d(LOG_TAG,
                        "reject failed", e);
                notifySuppServiceFailed(Phone.SuppService.REJECT);
            }
        } else if (getBackgroundCall().getState() != GsmCdmaCall.State.IDLE) {
            if (DBG) logd("MmiCode 0: hangupWaitingOrBackground");
            mCT.hangupWaitingOrBackground();
        }

        return true;
    }

    //GSM
    private boolean handleCallWaitingIncallSupplementaryService(String dialString) {
        int len = dialString.length();

        if (!hasCalling() || len > 2) {
            return false;
        }

        GsmCdmaCall call = getForegroundCall();

        try {
            if (len > 1) {
                char ch = dialString.charAt(1);
                int callIndex = ch - '0';

                if (callIndex >= 1 && callIndex <= GsmCdmaCallTracker.MAX_CONNECTIONS_GSM) {
                    if (DBG) logd("MmiCode 1: hangupConnectionByIndex " + callIndex);
                    mCT.hangupConnectionByIndex(call, callIndex);
                }
            } else {
                if (call.getState() != GsmCdmaCall.State.IDLE) {
                    if (DBG) logd("MmiCode 1: hangup foreground");
                    //mCT.hangupForegroundResumeBackground();
                    mCT.hangup(call);
                } else {
                    if (DBG) logd("MmiCode 1: switchWaitingOrHoldingAndActive");
                    mCT.switchWaitingOrHoldingAndActive();
                }
            }
        } catch (CallStateException e) {
            if (DBG) Rlog.d(LOG_TAG,
                    "hangup failed", e);
            notifySuppServiceFailed(Phone.SuppService.HANGUP);
        }

        return true;
    }

    private boolean handleCallHoldIncallSupplementaryService(String dialString) {
        int len = dialString.length();

        if (len > 2) {
            return false;
        }

        GsmCdmaCall call = getForegroundCall();

        if (len > 1) {
            try {
                char ch = dialString.charAt(1);
                int callIndex = ch - '0';
                GsmCdmaConnection conn = mCT.getConnectionByIndex(call, callIndex);

                // GsmCdma index starts at 1, up to 5 connections in a call,
                if (conn != null && callIndex >= 1 && callIndex <= GsmCdmaCallTracker.MAX_CONNECTIONS_GSM) {
                    if (DBG) logd("MmiCode 2: separate call " + callIndex);
                    mCT.separate(conn);
                } else {
                    if (DBG) logd("separate: invalid call index " + callIndex);
                    notifySuppServiceFailed(Phone.SuppService.SEPARATE);
                }
            } catch (CallStateException e) {
                if (DBG) Rlog.d(LOG_TAG, "separate failed", e);
                notifySuppServiceFailed(Phone.SuppService.SEPARATE);
            }
        } else {
            try {
                if (getRingingCall().getState() != GsmCdmaCall.State.IDLE) {
                    if (DBG) logd("MmiCode 2: accept ringing call");
                    mCT.acceptCall();
                } else {
                    if (DBG) logd("MmiCode 2: switchWaitingOrHoldingAndActive");
                    mCT.switchWaitingOrHoldingAndActive();
                }
            } catch (CallStateException e) {
                if (DBG) Rlog.d(LOG_TAG, "switch failed", e);
                notifySuppServiceFailed(Phone.SuppService.SWITCH);
            }
        }

        return true;
    }

    private boolean handleMultipartyIncallSupplementaryService(String dialString) {
        if (dialString.length() > 1) {
            return false;
        }

        if (DBG) logd("MmiCode 3: merge calls");
        conference();
        return true;
    }

    private boolean handleEctIncallSupplementaryService(String dialString) {

        int len = dialString.length();

        if (len != 1) {
            return false;
        }

        if (DBG) logd("MmiCode 4: explicit call transfer");
        explicitCallTransfer();
        return true;
    }

    private boolean handleCcbsIncallSupplementaryService(String dialString) {
        if (dialString.length() > 1) {
            return false;
        }

        Rlog.i(LOG_TAG, "MmiCode 5: CCBS not supported!");
        // Treat it as an "unknown" service.
        notifySuppServiceFailed(Phone.SuppService.UNKNOWN);
        return true;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @Override
    public boolean handleInCallMmiCommands(String dialString) throws CallStateException {
        Phone imsPhone = mImsPhone;
        if (imsPhone != null
                && imsPhone.getServiceState().getState() == ServiceState.STATE_IN_SERVICE) {
            return imsPhone.handleInCallMmiCommands(dialString);
        }

        if (!isInCall()) {
            return false;
        }

        if (TextUtils.isEmpty(dialString)) {
            return false;
        }

        boolean result = false;
        char ch = dialString.charAt(0);
        switch (ch) {
            case '0':
                result = handleCallDeflectionIncallSupplementaryService(dialString);
                break;
            case '1':
                result = handleCallWaitingIncallSupplementaryService(dialString);
                break;
            case '2':
                result = handleCallHoldIncallSupplementaryService(dialString);
                break;
            case '3':
                result = handleMultipartyIncallSupplementaryService(dialString);
                break;
            case '4':
                result = handleEctIncallSupplementaryService(dialString);
                break;
            case '5':
                result = handleCcbsIncallSupplementaryService(dialString);
                break;
            default:
                break;
        }

        return result;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isInCall() {
        if (!hasCalling()) return false;

        GsmCdmaCall.State foregroundCallState = getForegroundCall().getState();
        GsmCdmaCall.State backgroundCallState = getBackgroundCall().getState();
        GsmCdmaCall.State ringingCallState = getRingingCall().getState();

       return (foregroundCallState.isAlive() ||
                backgroundCallState.isAlive() ||
                ringingCallState.isAlive());
    }

    private boolean useImsForCall(DialArgs dialArgs) {
        return isImsUseEnabled()
                && mImsPhone != null
                && (mImsPhone.isVoiceOverCellularImsEnabled() || mImsPhone.isWifiCallingEnabled()
                || (mImsPhone.isVideoEnabled() && VideoProfile.isVideo(dialArgs.videoState)))
                && (mImsPhone.getServiceState().getState() == ServiceState.STATE_IN_SERVICE);
    }

    public boolean useImsForEmergency() {
        CarrierConfigManager configManager =
                (CarrierConfigManager) mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        boolean alwaysTryImsForEmergencyCarrierConfig = configManager.getConfigForSubId(getSubId())
                .getBoolean(CarrierConfigManager.KEY_CARRIER_USE_IMS_FIRST_FOR_EMERGENCY_BOOL);
        return mImsPhone != null
                && alwaysTryImsForEmergencyCarrierConfig
                && ImsManager.getInstance(mContext, mPhoneId).isNonTtyOrTtyOnVolteEnabled()
                && mImsPhone.isImsAvailable();
    }

    @Override
    public Connection startConference(String[] participantsToDial, DialArgs dialArgs)
            throws CallStateException {
        Phone imsPhone = mImsPhone;
        boolean useImsForCall = useImsForCall(dialArgs);
        logd("useImsForCall=" + useImsForCall);
        if (useImsForCall) {
            try {
                if (DBG) logd("Trying IMS PS Conference call");
                return imsPhone.startConference(participantsToDial, dialArgs);
            } catch (CallStateException e) {
                if (DBG) logd("IMS PS conference call exception " + e +
                        "useImsForCall =" + useImsForCall + ", imsPhone =" + imsPhone);
                 CallStateException ce = new CallStateException(e.getError(), e.getMessage());
                 ce.setStackTrace(e.getStackTrace());
                 throw ce;
            }
        } else {
            throw new CallStateException(
                CallStateException.ERROR_OUT_OF_SERVICE,
                "cannot dial conference call in out of service");
        }
    }

    @Override
    public Connection dial(String dialString, @NonNull DialArgs dialArgs,
            Consumer<Phone> chosenPhoneConsumer) throws CallStateException {
        if (!hasCalling()) {
            throw new CallStateException("Calling feature is not supported!");
        }
        String possibleEmergencyNumber = checkForTestEmergencyNumber(dialString);
        // Record if the dialed number was swapped for a test emergency number.
        boolean isDialedNumberSwapped = !TextUtils.equals(dialString, possibleEmergencyNumber);
        if (isDialedNumberSwapped) {
            logi("dialString replaced for possible emergency number: " + dialString + " -> "
                    + possibleEmergencyNumber);
            dialString = possibleEmergencyNumber;
        }

        CarrierConfigManager configManager =
                (CarrierConfigManager) mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        PersistableBundle carrierConfig = configManager.getConfigForSubId(getSubId());
        boolean allowWpsOverIms = carrierConfig.getBoolean(
                CarrierConfigManager.KEY_SUPPORT_WPS_OVER_IMS_BOOL);
        boolean useOnlyDialedSimEccList = carrierConfig.getBoolean(
                CarrierConfigManager.KEY_USE_ONLY_DIALED_SIM_ECC_LIST_BOOL);


        TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
        boolean isEmergency;
        // Check if the carrier wants to treat a call as an emergency call based on its own list of
        // known emergency numbers.
        // useOnlyDialedSimEccList is false for the vast majority of carriers.  There are, however,
        // some carriers which do not want to handle dial requests for numbers which are in the
        // emergency number list on another SIM, but is not on theirs.  In this case we will use the
        // emergency number list for this carrier's SIM only.
        if (useOnlyDialedSimEccList) {
            isEmergency = getEmergencyNumberTracker().isEmergencyNumber(dialString);
            logi("dial; isEmergency=" + isEmergency
                    + " (based on this phone only); globalIsEmergency="
                    + tm.isEmergencyNumber(dialString));
        } else {
            isEmergency = tm.isEmergencyNumber(dialString);
            logi("dial; isEmergency=" + isEmergency + " (based on all phones)");
        }

        // Undetectable emergeny number indicated by new domain selection service
        if (dialArgs.isEmergency) {
            logi("dial; isEmergency=" + isEmergency + " (domain selection module)");
            isEmergency = true;
        }

        /** Check if the call is Wireless Priority Service call */
        boolean isWpsCall = PhoneNumberUtils.isWpsCallNumber(dialString);

        ImsPhone.ImsDialArgs.Builder imsDialArgsBuilder;
        imsDialArgsBuilder = ImsPhone.ImsDialArgs.Builder.from(dialArgs)
                                                 .setIsEmergency(isEmergency)
                                                 .setIsWpsCall(isWpsCall);
        mDialArgs = dialArgs = imsDialArgsBuilder.build();

        Phone imsPhone = mImsPhone;

        boolean useImsForEmergency = isEmergency && useImsForEmergency();

        String dialPart = PhoneNumberUtils.extractNetworkPortionAlt(PhoneNumberUtils.
                stripSeparators(dialString));
        boolean isMmiCode = (dialPart.startsWith("*") || dialPart.startsWith("#"))
                && dialPart.endsWith("#");
        boolean isSuppServiceCode = ImsPhoneMmiCode.isSuppServiceCodes(dialPart, this);
        boolean isPotentialUssdCode = isMmiCode && !isSuppServiceCode;
        boolean useImsForUt = imsPhone != null && imsPhone.isUtEnabled();
        boolean useImsForCall = useImsForCall(dialArgs)
                && (isWpsCall ? allowWpsOverIms : true);

        Bundle extras = dialArgs.intentExtras;
        // Only when the domain selection service is supported, EXTRA_DIAL_DOMAIN extra shall exist.
        if (extras != null && extras.containsKey(PhoneConstants.EXTRA_DIAL_DOMAIN)) {
            int domain = extras.getInt(PhoneConstants.EXTRA_DIAL_DOMAIN);
            logi("dial domain=" + domain);
            useImsForCall = false;
            useImsForUt = false;
            useImsForEmergency = false;
            if (domain == DOMAIN_PS) {
                if (isEmergency) {
                    useImsForEmergency = true;
                } else if (!isMmiCode || isPotentialUssdCode) {
                    useImsForCall = true;
                } else {
                    // should not reach here
                    loge("dial unexpected Ut domain selection, ignored");
                }
            } else if (domain == PhoneConstants.DOMAIN_NON_3GPP_PS) {
                if (isEmergency) {
                    useImsForEmergency = true;
                    extras.putString(ImsCallProfile.EXTRA_CALL_RAT_TYPE,
                            String.valueOf(ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN));
                } else {
                    // should not reach here
                    loge("dial DOMAIN_NON_3GPP_PS should be used only for emergency calls");
                }
            }

            extras.remove(PhoneConstants.EXTRA_DIAL_DOMAIN);
        }

        if (DBG) {
            logi("useImsForCall=" + useImsForCall
                    + ", useOnlyDialedSimEccList=" + useOnlyDialedSimEccList
                    + ", isEmergency=" + isEmergency
                    + ", useImsForEmergency=" + useImsForEmergency
                    + ", useImsForUt=" + useImsForUt
                    + ", isUt=" + isMmiCode
                    + ", isSuppServiceCode=" + isSuppServiceCode
                    + ", isPotentialUssdCode=" + isPotentialUssdCode
                    + ", isWpsCall=" + isWpsCall
                    + ", allowWpsOverIms=" + allowWpsOverIms
                    + ", imsPhone=" + imsPhone
                    + ", imsPhone.isVoiceOverCellularImsEnabled()="
                    + ((imsPhone != null) ? imsPhone.isVoiceOverCellularImsEnabled() : "N/A")
                    + ", imsPhone.isVowifiEnabled()="
                    + ((imsPhone != null) ? imsPhone.isWifiCallingEnabled() : "N/A")
                    + ", imsPhone.isVideoEnabled()="
                    + ((imsPhone != null) ? imsPhone.isVideoEnabled() : "N/A")
                    + ", imsPhone.getServiceState().getState()="
                    + ((imsPhone != null) ? imsPhone.getServiceState().getState() : "N/A"));
        }

        // Perform FDN check for non-emergency calls - shouldn't dial if number is blocked by FDN
        if (!isEmergency
                && (!Flags.supportStkCommandUssdAndCall() || !dialArgs.skipFdnCheck)
                && FdnUtils.isNumberBlockedByFDN(mPhoneId, dialString, getCountryIso())) {
            throw new CallStateException(CallStateException.ERROR_FDN_BLOCKED,
                    "cannot dial number blocked by FDN");
        }

        // Bypass WiFi Only WFC check if this is an emergency call - we should still try to
        // place over cellular if possible.
        if (!isEmergency) {
            Phone.checkWfcWifiOnlyModeBeforeDial(mImsPhone, mPhoneId, mContext);
        }
        if (imsPhone != null && !allowWpsOverIms && !useImsForCall && isWpsCall
                && imsPhone.getCallTracker() instanceof ImsPhoneCallTracker) {
            logi("WPS call placed over CS; disconnecting all IMS calls..");
            ImsPhoneCallTracker tracker = (ImsPhoneCallTracker) imsPhone.getCallTracker();
            tracker.hangupAllConnections();
        }

        if ((useImsForCall && (!isMmiCode || isPotentialUssdCode))
                || (isMmiCode && useImsForUt)
                || useImsForEmergency) {
            try {
                if (DBG) logd("Trying IMS PS call");
                chosenPhoneConsumer.accept(imsPhone);
                return imsPhone.dial(dialString, dialArgs);
            } catch (CallStateException e) {
                if (DBG) logd("IMS PS call exception " + e +
                        "useImsForCall =" + useImsForCall + ", imsPhone =" + imsPhone);
                // Do not throw a CallStateException and instead fall back to Circuit switch
                // for emergency calls and MMI codes.
                if (Phone.CS_FALLBACK.equals(e.getMessage()) || isEmergency) {
                    logi("IMS call failed with Exception: " + e.getMessage() + ". Falling back "
                            + "to CS.");
                } else {
                    CallStateException ce = new CallStateException(e.getError(), e.getMessage());
                    ce.setStackTrace(e.getStackTrace());
                    throw ce;
                }
            }
        }

        if (mSST != null && mSST.mSS.getState() == ServiceState.STATE_OUT_OF_SERVICE
                && mSST.mSS.getDataRegistrationState() != ServiceState.STATE_IN_SERVICE
                && !isEmergency) {
            throw new CallStateException("cannot dial in current state");
        }
        // Check non-emergency voice CS call - shouldn't dial when POWER_OFF
        if (mSST != null && mSST.mSS.getState() == ServiceState.STATE_POWER_OFF /* CS POWER_OFF */
                && !VideoProfile.isVideo(dialArgs.videoState) /* voice call */
                && !isEmergency /* non-emergency call */
                && !(isMmiCode && useImsForUt) /* not UT */
                /* If config_allow_ussd_over_ims is false, USSD is sent over the CS pipe instead */
                && !isPotentialUssdCode) {
            throw new CallStateException(
                CallStateException.ERROR_POWER_OFF,
                "cannot dial voice call in airplane mode");
        }
        // Check for service before placing non emergency CS voice call.
        // Allow dial only if either CS is camped on any RAT (or) PS is in LTE/NR service.
        if (mSST != null
                && mSST.mSS.getState() == ServiceState.STATE_OUT_OF_SERVICE /* CS out of service */
                && !(mSST.mSS.getDataRegistrationState() == ServiceState.STATE_IN_SERVICE
                && ServiceState.isPsOnlyTech(
                        mSST.mSS.getRilDataRadioTechnology())) /* PS not in LTE/NR */
                && !VideoProfile.isVideo(dialArgs.videoState) /* voice call */
                && !isEmergency /* non-emergency call */
                /* If config_allow_ussd_over_ims is false, USSD is sent over the CS pipe instead */
                && !isPotentialUssdCode) {
            throw new CallStateException(
                CallStateException.ERROR_OUT_OF_SERVICE,
                "cannot dial voice call in out of service");
        }
        if (DBG) logd("Trying (non-IMS) CS call");
        if (isDialedNumberSwapped && isEmergency) {
            // If domain selection is enabled, ECM testing is handled in EmergencyStateTracker
            if (!DomainSelectionResolver.getInstance().isDomainSelectionSupported()) {
                // Triggers ECM when CS call ends only for test emergency calls using
                // ril.test.emergencynumber.
                mIsTestingEmergencyCallbackMode = true;
                mCi.testingEmergencyCall();
            }
        }

        chosenPhoneConsumer.accept(this);
        return dialInternal(dialString, dialArgs);
    }

    /**
     * @return {@code true} if the user should be informed of an attempt to dial an international
     * number while on WFC only, {@code false} otherwise.
     */
    public boolean isNotificationOfWfcCallRequired(String dialString) {
        CarrierConfigManager configManager =
                (CarrierConfigManager) mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        PersistableBundle config = configManager.getConfigForSubId(getSubId());

        // Determine if carrier config indicates that international calls over WFC should trigger a
        // notification to the user. This is controlled by carrier configuration and is off by
        // default.
        boolean shouldNotifyInternationalCallOnWfc = config != null
                && config.getBoolean(
                        CarrierConfigManager.KEY_NOTIFY_INTERNATIONAL_CALL_ON_WFC_BOOL);

        if (!shouldNotifyInternationalCallOnWfc) {
            return false;
        }

        Phone imsPhone = mImsPhone;
        TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
        boolean isEmergency = tm.isEmergencyNumber(dialString);
        boolean shouldConfirmCall =
                        // Using IMS
                        isImsUseEnabled()
                        && imsPhone != null
                        // VoLTE not available
                        && !imsPhone.isVoiceOverCellularImsEnabled()
                        // WFC is available
                        && imsPhone.isWifiCallingEnabled()
                        && !isEmergency
                        // Dialing international number
                        && PhoneNumberUtils.isInternationalNumber(dialString, getCountryIso());
        return shouldConfirmCall;
    }

    @Override
    protected Connection dialInternal(String dialString, DialArgs dialArgs)
            throws CallStateException {
        return dialInternal(dialString, dialArgs, null);
    }

    protected Connection dialInternal(String dialString, DialArgs dialArgs,
            ResultReceiver wrappedCallback)
            throws CallStateException {

        // Need to make sure dialString gets parsed properly
        String newDialString = PhoneNumberUtils.stripSeparators(dialString);

        // If not emergency number, handle in-call MMI first if applicable
        if (!dialArgs.isEmergency) {
            if (mFeatureFlags.ignoreIncallMmiForEmergency() && isInEmergencyCall()) {
                logd("dialInternal: ignore InCall MMI command during emergency call");
                return null;
            }
            if (handleInCallMmiCommands(newDialString)) {
                return null;
            }
        }

        // Only look at the Network portion for mmi
        String networkPortion = PhoneNumberUtils.extractNetworkPortionAlt(newDialString);
        GsmMmiCode mmi = GsmMmiCode.newFromDialString(networkPortion, this,
                mUiccApplication.get(), wrappedCallback);
        if (DBG) logd("dialInternal: dialing w/ mmi '" + mmi + "'...");

        if (mmi == null) {
            return mCT.dialGsm(newDialString, dialArgs);
        } else if (mmi.isTemporaryModeCLIR()) {
            return mCT.dialGsm(mmi.mDialingNumber, mmi.getCLIRMode(), dialArgs.uusInfo,
                    dialArgs.intentExtras);
        } else {
            mPendingMMIs.add(mmi);
            mMmiRegistrants.notifyRegistrants(new AsyncResult(null, mmi, null));
            mmi.processCode();
            return null;
        }
    }

   @Override
    public boolean handlePinMmi(String dialString) {
        MmiCode mmi;
        mmi = GsmMmiCode.newFromDialString(dialString, this, mUiccApplication.get());

        if (mmi != null && mmi.isPinPukCommand()) {
            mPendingMMIs.add(mmi);
            mMmiRegistrants.notifyRegistrants(new AsyncResult(null, mmi, null));
            try {
                mmi.processCode();
            } catch (CallStateException e) {
                //do nothing
            }
            return true;
        }

        loge("Mmi is null or unrecognized!");
        return false;
    }

    private void sendUssdResponse(String ussdRequest, CharSequence message, int returnCode,
                                   ResultReceiver wrappedCallback) {
        UssdResponse response = new UssdResponse(ussdRequest, message);
        Bundle returnData = new Bundle();
        returnData.putParcelable(TelephonyManager.USSD_RESPONSE, response);
        wrappedCallback.send(returnCode, returnData);
    }

    @Override
    public boolean handleUssdRequest(String ussdRequest, ResultReceiver wrappedCallback) {
        if (mPendingMMIs.size() > 0) {
            //todo: replace the generic failure with specific error code.
            sendUssdResponse(ussdRequest, null, TelephonyManager.USSD_RETURN_FAILURE,
                    wrappedCallback );
            return true;
        }

        // Perform FDN check
        if(FdnUtils.isNumberBlockedByFDN(mPhoneId, ussdRequest, getCountryIso())) {
            sendUssdResponse(ussdRequest, null, TelephonyManager.USSD_RETURN_FAILURE,
                    wrappedCallback );
            return true;
        }

        // Try over IMS if possible.
        Phone imsPhone = mImsPhone;
        if ((imsPhone != null)
                && ((imsPhone.getServiceState().getState() == ServiceState.STATE_IN_SERVICE)
                || imsPhone.isUtEnabled())) {
            try {
                logd("handleUssdRequest: attempting over IMS");
                return imsPhone.handleUssdRequest(ussdRequest, wrappedCallback);
            } catch (CallStateException cse) {
                if (!CS_FALLBACK.equals(cse.getMessage())) {
                    return false;
                }
                // At this point we've tried over IMS but have been informed we need to handover
                // back to GSM.
                logd("handleUssdRequest: fallback to CS required");
            }
        }

        // Try USSD over GSM.
        try {
            dialInternal(ussdRequest, new DialArgs.Builder<>().build(), wrappedCallback);
        } catch (Exception e) {
            logd("handleUssdRequest: exception" + e);
            return false;
        }
        return true;
    }

    @Override
    public void sendUssdResponse(String ussdMessage) {
        GsmMmiCode mmi = GsmMmiCode.newFromUssdUserInput(ussdMessage, this, mUiccApplication.get());
        mPendingMMIs.add(mmi);
        mMmiRegistrants.notifyRegistrants(new AsyncResult(null, mmi, null));
        mmi.sendUssd(ussdMessage);
    }

    @Override
    public void sendDtmf(char c) {
        if (!PhoneNumberUtils.is12Key(c)) {
            loge("sendDtmf called with invalid character '" + c + "'");
        } else {
            if (mCT.mState ==  PhoneConstants.State.OFFHOOK) {
                mCi.sendDtmf(c, null);
            }
        }
    }

    @Override
    public void startDtmf(char c) {
        if (!PhoneNumberUtils.is12Key(c)) {
            loge("startDtmf called with invalid character '" + c + "'");
        } else {
            mCi.startDtmf(c, null);
        }
    }

    @Override
    public void stopDtmf() {
        mCi.stopDtmf(null);
    }

    @Override
    public void sendBurstDtmf(String dtmfString, int on, int off, Message onComplete) {
        loge("[GsmCdmaPhone] sendBurstDtmf() is a CDMA method");
    }

    @Override
    public void setRadioPowerOnForTestEmergencyCall(boolean isSelectedPhoneForEmergencyCall) {
        mSST.clearAllRadioOffReasons();

        // We don't want to have forEmergency call be true to prevent radio emergencyDial command
        // from being called for a test emergency number because the network may not be able to
        // find emergency routing for it and dial it do the default emergency services line.
        setRadioPower(true, false, isSelectedPhoneForEmergencyCall, false);
    }

    @Override
    public void setRadioPower(boolean power, boolean forEmergencyCall,
            boolean isSelectedPhoneForEmergencyCall, boolean forceApply) {
        setRadioPowerForReason(power, forEmergencyCall, isSelectedPhoneForEmergencyCall, forceApply,
                TelephonyManager.RADIO_POWER_REASON_USER);
    }

    @Override
    public void setRadioPowerForReason(boolean power, boolean forEmergencyCall,
            boolean isSelectedPhoneForEmergencyCall, boolean forceApply, int reason) {
        if (mFeatureFlags.powerDownRaceFix()) {
            // setRadioPowerForReason can be called by the binder thread. We need to move that into
            // the main thread to prevent race condition.
            post(() -> mSST.setRadioPowerForReason(power, forEmergencyCall,
                    isSelectedPhoneForEmergencyCall, forceApply, reason));
        } else {
            mSST.setRadioPowerForReason(power, forEmergencyCall, isSelectedPhoneForEmergencyCall,
                    forceApply, reason);
        }
    }

    @Override
    public Set<Integer> getRadioPowerOffReasons() {
        return mSST.getRadioPowerOffReasons();
    }

    private void storeVoiceMailNumber(String number) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        SharedPreferences.Editor editor = sp.edit();
        setVmSimImsi(getSubscriberId());
        logd("storeVoiceMailNumber: mPrecisePhoneType=" + mPrecisePhoneType + " vmNumber="
                + Rlog.pii(LOG_TAG, number));
        editor.putString(VM_NUMBER + getPhoneId(), number);
        editor.apply();
    }

    @Override
    public String getVoiceMailNumber() {
        String number = null;
        // Read from the SIM. If its null, try reading from the shared preference area.
        IccRecords r = mIccRecords.get();
        number = (r != null) ? r.getVoiceMailNumber() : "";
        if (TextUtils.isEmpty(number)) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
            String spName = VM_NUMBER;
            number = sp.getString(spName + getPhoneId(), null);
            logd("getVoiceMailNumber: from " + spName + " number="
                    + Rlog.piiHandle(number));
        } else {
            logd("getVoiceMailNumber: from IccRecords number=" + Rlog.piiHandle(number));
        }

        if (TextUtils.isEmpty(number)) {
            CarrierConfigManager configManager = (CarrierConfigManager)
                    getContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
            PersistableBundle b = configManager.getConfigForSubId(getSubId());
            if (b != null) {
                String defaultVmNumber =
                        b.getString(CarrierConfigManager.KEY_DEFAULT_VM_NUMBER_STRING);
                String defaultVmNumberRoaming =
                        b.getString(CarrierConfigManager.KEY_DEFAULT_VM_NUMBER_ROAMING_STRING);
                String defaultVmNumberRoamingAndImsUnregistered = b.getString(
                        CarrierConfigManager
                                .KEY_DEFAULT_VM_NUMBER_ROAMING_AND_IMS_UNREGISTERED_STRING);

                if (!TextUtils.isEmpty(defaultVmNumber)) number = defaultVmNumber;
                if (mSST.mSS.getRoaming()) {
                    if (!TextUtils.isEmpty(defaultVmNumberRoamingAndImsUnregistered)
                            && !mSST.isImsRegistered()) {
                        // roaming and IMS unregistered case if CC configured
                        number = defaultVmNumberRoamingAndImsUnregistered;
                        logd("getVoiceMailNumber: from defaultVmNumberRoamingAndImsUnregistered "
                                + "number=" + Rlog.piiHandle(number));
                    } else if (!TextUtils.isEmpty(defaultVmNumberRoaming)) {
                        // roaming default case if CC configured
                        number = defaultVmNumberRoaming;
                        logd("getVoiceMailNumber: from defaultVmNumberRoaming number=" +
                                Rlog.piiHandle(number));
                    }
                }
            }
        }

        if (TextUtils.isEmpty(number)) {
            // Read platform settings for dynamic voicemail number
            CarrierConfigManager configManager = (CarrierConfigManager)
                    getContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
            PersistableBundle b = configManager.getConfigForSubId(getSubId());
            if (b != null && b.getBoolean(
                    CarrierConfigManager.KEY_CONFIG_TELEPHONY_USE_OWN_NUMBER_FOR_VOICEMAIL_BOOL)) {
                number = getLine1Number();
                logd("getVoiceMailNumber: from MSISDN number=" + Rlog.piiHandle(number));
            }
        }
        return number;
    }


    private String getVmSimImsi() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        return sp.getString(VM_SIM_IMSI + getPhoneId(), null);
    }

    private void setVmSimImsi(String imsi) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(VM_SIM_IMSI + getPhoneId(), imsi);
        editor.apply();
    }

    @Override
    public String getVoiceMailAlphaTag() {
        String ret = "";

        IccRecords r = mIccRecords.get();

        ret = (r != null) ? r.getVoiceMailAlphaTag() : "";

        if (ret == null || ret.length() == 0) {
            return mContext.getText(
                com.android.internal.R.string.defaultVoiceMailAlphaTag).toString();
        }

        return ret;
    }

    @Override
    public String getDeviceId() {
        return mImei;
    }

    @Override
    public String getDeviceSvn() {
        return mImeiSv;
    }

    @Override
    public IsimRecords getIsimRecords() {
        return mIsimUiccRecords;
    }

    @Override
    public String getImei() {
        return mImei;
    }

    @Override
    public int getImeiType() {
        return mImeiType;
    }

    @Override
    @Nullable
    public String getSubscriberId() {
        String subscriberId = null;
        // Both Gsm and CdmaLte get the IMSI from Usim.
        IccRecords iccRecords = mUiccController.getIccRecords(
                mPhoneId, UiccController.APP_FAM_3GPP);
        if (iccRecords != null) {
            subscriberId = iccRecords.getIMSI();
        }
        return subscriberId;
    }

    @Override
    public ImsiEncryptionInfo getCarrierInfoForImsiEncryption(int keyType, boolean fallback) {
        final TelephonyManager telephonyManager = mContext.getSystemService(TelephonyManager.class)
                .createForSubscriptionId(getSubId());
        String operatorNumeric = telephonyManager.getSimOperator();
        int carrierId = telephonyManager.getSimCarrierId();
        return CarrierInfoManager.getCarrierInfoForImsiEncryption(keyType,
                mContext, operatorNumeric, carrierId, fallback, getSubId());
    }

    @Override
    public void setCarrierInfoForImsiEncryption(ImsiEncryptionInfo imsiEncryptionInfo,
            boolean saveToDb) {
        if (saveToDb) {
            CarrierInfoManager.setCarrierInfoForImsiEncryption(imsiEncryptionInfo, mContext,
                    mPhoneId);
        }
        mCi.setCarrierInfoForImsiEncryption(imsiEncryptionInfo, null);
    }

    @Override
    public void deleteCarrierInfoForImsiEncryption(int carrierId) {
        CarrierInfoManager.deleteCarrierInfoForImsiEncryption(mContext, getSubId(), carrierId);
    }

    @Override
    public void deleteCarrierInfoForImsiEncryption(int carrierId, String simOperator) {
        CarrierInfoManager.deleteCarrierInfoForImsiEncryption(mContext, getSubId(),
                carrierId, simOperator);
    }

    @Override
    public int getCarrierId() {
        return mCarrierResolver != null
                ? mCarrierResolver.getCarrierId() : super.getCarrierId();
    }

    @Override
    public String getCarrierName() {
        return mCarrierResolver != null
                ? mCarrierResolver.getCarrierName() : super.getCarrierName();
    }

    @Override
    public int getMNOCarrierId() {
        return mCarrierResolver != null
                ? mCarrierResolver.getMnoCarrierId() : super.getMNOCarrierId();
    }

    @Override
    public int getSpecificCarrierId() {
        return mCarrierResolver != null
                ? mCarrierResolver.getSpecificCarrierId() : super.getSpecificCarrierId();
    }

    @Override
    public String getSpecificCarrierName() {
        return mCarrierResolver != null
                ? mCarrierResolver.getSpecificCarrierName() : super.getSpecificCarrierName();
    }

    @Override
    public void resolveSubscriptionCarrierId(String simState) {
        if (mCarrierResolver != null) {
            mCarrierResolver.resolveSubscriptionCarrierId(simState);
        }
    }

    @Override
    public int getCarrierIdListVersion() {
        return mCarrierResolver != null
                ? mCarrierResolver.getCarrierListVersion() : super.getCarrierIdListVersion();
    }

    @Override
    public int getEmergencyNumberDbVersion() {
        EmergencyNumberTracker tracker = getEmergencyNumberTracker();
        if (tracker == null) return -1;
        return tracker.getEmergencyNumberDbVersion();
    }

    @Override
    public void resetCarrierKeysForImsiEncryption() {
        mCIM.resetCarrierKeysForImsiEncryption(mContext, mPhoneId, false);
    }

    @Override
    public void resetCarrierKeysForImsiEncryption(boolean forceResetAll) {
        mCIM.resetCarrierKeysForImsiEncryption(mContext, mPhoneId, forceResetAll);
    }

    @Override
    public void setCarrierTestOverride(String mccmnc, String imsi, String iccid, String gid1,
            String gid2, String pnn, String spn, String carrierPrivilegeRules, String apn) {
        mCarrierResolver.setTestOverrideApn(apn);
        UiccProfile uiccProfile = mUiccController.getUiccProfileForPhone(getPhoneId());
        if (uiccProfile != null) {
            List<UiccAccessRule> testRules;
            if (carrierPrivilegeRules == null) {
                testRules = null;
            } else if (carrierPrivilegeRules.isEmpty()) {
                testRules = Collections.emptyList();
            } else {
                UiccAccessRule accessRule = new UiccAccessRule(
                        IccUtils.hexStringToBytes(carrierPrivilegeRules), null, 0);
                testRules = Collections.singletonList(accessRule);
            }
            uiccProfile.setTestOverrideCarrierPrivilegeRules(testRules);
        } else {
            // TODO: Fix "privilege" typo throughout telephony.
            mCarrierResolver.setTestOverrideCarrierPriviledgeRule(carrierPrivilegeRules); // NOTYPO
        }
        IccRecords r = null;
        r = mIccRecords.get();
        if (r != null) {
            r.setCarrierTestOverride(mccmnc, imsi, iccid, gid1, gid2, pnn, spn);
        }
    }

    @Override
    public String getGroupIdLevel1() {
        IccRecords r = mIccRecords.get();
        return (r != null) ? r.getGid1() : null;
    }

    @Override
    public String getGroupIdLevel2() {
        IccRecords r = mIccRecords.get();
        return (r != null) ? r.getGid2() : null;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @Override
    public String getLine1Number() {
        IccRecords r = mIccRecords.get();
        return (r != null) ? r.getMsisdnNumber() : null;
    }

    @Override
    public String getPnnHomeNetworkName() {
        IccRecords r = mIccRecords.get();
        return (r != null) ? r.getPnnHomeName() : null;
    }

    /**
     * Update non-persisited manual network selection.
     *
     * @param nsm contains Plmn info
     */
    @Override
    protected void updateManualNetworkSelection(NetworkSelectMessage nsm) {
        int subId = getSubId();
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            mManualNetworkSelectionPlmn = nsm.operatorNumeric;
        } else {
        //on Phone0 in emergency mode (no SIM), or in some races then clear the cache
            mManualNetworkSelectionPlmn = null;
            Rlog.e(LOG_TAG, "Cannot update network selection due to invalid subId "
                    + subId);
        }
    }

    @Override
    public String getManualNetworkSelectionPlmn() {
        return (mManualNetworkSelectionPlmn == null) ? "" : mManualNetworkSelectionPlmn;
    }

    @Override
    protected void onSetNetworkSelectionModeCompleted() {
        mSST.pollState();
    }

    @Override
    public String getMsisdn() {
        IccRecords r = mIccRecords.get();
        return (r != null) ? r.getMsisdnNumber() : null;
    }

    @Override
    public String getLine1AlphaTag() {
        IccRecords r = mIccRecords.get();
        return (r != null) ? r.getMsisdnAlphaTag() : null;
    }

    @Override
    public boolean setLine1Number(String alphaTag, String number, Message onComplete) {
        IccRecords r = mIccRecords.get();
        if (r != null) {
            r.setMsisdnNumber(alphaTag, number, onComplete);
            return true;
        }
        return false;
    }

    @Override
    public void setVoiceMailNumber(String alphaTag, String voiceMailNumber, Message onComplete) {
        Message resp;
        mVmNumber = voiceMailNumber;
        resp = obtainMessage(EVENT_SET_VM_NUMBER_DONE, 0, 0, onComplete);

        IccRecords r = mIccRecords.get();

        if (r != null) {
            r.setVoiceMailNumber(alphaTag, mVmNumber, resp);
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private boolean isValidCommandInterfaceCFReason (int commandInterfaceCFReason) {
        switch (commandInterfaceCFReason) {
            case CF_REASON_UNCONDITIONAL:
            case CF_REASON_BUSY:
            case CF_REASON_NO_REPLY:
            case CF_REASON_NOT_REACHABLE:
            case CF_REASON_ALL:
            case CF_REASON_ALL_CONDITIONAL:
                return true;
            default:
                return false;
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @Override
    public String getSystemProperty(String property, String defValue) {
        if (getUnitTestMode()) {
            return null;
        }
        return TelephonyManager.getTelephonyProperty(mPhoneId, property, defValue);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private boolean isValidCommandInterfaceCFAction (int commandInterfaceCFAction) {
        switch (commandInterfaceCFAction) {
            case CF_ACTION_DISABLE:
            case CF_ACTION_ENABLE:
            case CF_ACTION_REGISTRATION:
            case CF_ACTION_ERASURE:
                return true;
            default:
                return false;
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private boolean isCfEnable(int action) {
        return (action == CF_ACTION_ENABLE) || (action == CF_ACTION_REGISTRATION);
    }

    private boolean isCsRetry(Message onComplete) {
        if (onComplete != null) {
            return onComplete.getData().getBoolean(CS_FALLBACK_SS, false);
        }
        return false;
    }

    /**
     * Enables or disables N1 mode (access to 5G core network) in accordance with
     * 3GPP TS 24.501 4.9.
     *
     * <p> To prevent redundant calls down to the modem and to support a mechanism whereby
     * N1 mode is only on if both IMS and carrier config believe that it should be on, this
     * method will first sync the value from the modem prior to possibly setting it. In addition
     * N1 mode will not be set to enabled unless both IMS and Carrier want it, since the use
     * cases require all entities to agree lest it default to disabled.
     *
     * @param enable {@code true} to enable N1 mode, {@code false} to disable N1 mode.
     * @param result Callback message to receive the result or null.
     */
    @Override
    public void setN1ModeEnabled(boolean enable, @Nullable Message result) {
        // This might be called by IMS on another thread, so to avoid the requirement to
        // lock, post it through the handler.
        post(() -> {
            if (enable) {
                mN1ModeDisallowedReasons.remove(N1_MODE_DISALLOWED_REASON_IMS);
            } else {
                mN1ModeDisallowedReasons.add(N1_MODE_DISALLOWED_REASON_IMS);
            }
            if (mModemN1Mode == null) {
                mCi.isN1ModeEnabled(obtainMessage(EVENT_GET_N1_MODE_ENABLED_DONE, result));
            } else {
                maybeUpdateModemN1Mode(result);
            }
        });
    }

    /** Only called on the handler thread. */
    private void maybeUpdateModemN1Mode(@Nullable Message result) {
        final boolean wantN1Enabled = mN1ModeDisallowedReasons.isEmpty();

        logd("N1 Mode: isModemN1Enabled=" + mModemN1Mode + ", wantN1Enabled=" + wantN1Enabled);

        // mModemN1Mode is never null here
        if (mModemN1Mode != wantN1Enabled) {
            // Assume success pending a response, which avoids multiple concurrent requests
            // going down to the modem. If it fails, that is addressed in the response.
            mModemN1Mode = wantN1Enabled;
            super.setN1ModeEnabled(
                    wantN1Enabled, obtainMessage(EVENT_SET_N1_MODE_ENABLED_DONE, result));
        } else if (result != null) {
            AsyncResult.forMessage(result);
            result.sendToTarget();
        }
    }

    /** Only called on the handler thread. */
    private void updateCarrierN1ModeSupported(@NonNull PersistableBundle b) {
        if (!CarrierConfigManager.isConfigForIdentifiedCarrier(b)) return;

        final int[] supportedNrModes = b.getIntArray(
                CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY);


        if (ArrayUtils.contains(
                supportedNrModes,
                CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA)) {
            mN1ModeDisallowedReasons.remove(N1_MODE_DISALLOWED_REASON_CARRIER);
        } else {
            mN1ModeDisallowedReasons.add(N1_MODE_DISALLOWED_REASON_CARRIER);
        }

        if (mModemN1Mode == null) {
            mCi.isN1ModeEnabled(obtainMessage(EVENT_GET_N1_MODE_ENABLED_DONE));
        } else {
            maybeUpdateModemN1Mode(null);
        }
    }

    @Override
    public boolean useSsOverIms(Message onComplete) {
        boolean isUtEnabled = isUtEnabled();

        Rlog.d(LOG_TAG, "useSsOverIms: isUtEnabled()= " + isUtEnabled +
                " isCsRetry(onComplete))= " + isCsRetry(onComplete));

        if (isUtEnabled && !isCsRetry(onComplete)) {
            return true;
        }
        return false;
    }

    @Override
    public void getCallForwardingOption(int commandInterfaceCFReason, Message onComplete) {
        getCallForwardingOption(commandInterfaceCFReason,
                CommandsInterface.SERVICE_CLASS_VOICE, onComplete);
    }

    @Override
    public void getCallForwardingOption(int commandInterfaceCFReason, int serviceClass,
            Message onComplete) {
        // Perform FDN check
        SsData.ServiceType serviceType = GsmMmiCode.cfReasonToServiceType(commandInterfaceCFReason);
        if(isRequestBlockedByFDN(SsData.RequestType.SS_INTERROGATION, serviceType)) {
            AsyncResult.forMessage(onComplete, null,
                    new CommandException(CommandException.Error.FDN_CHECK_FAILURE));
            onComplete.sendToTarget();
            return;
        }

        Phone imsPhone = mImsPhone;
        if (useSsOverIms(onComplete)) {
            imsPhone.getCallForwardingOption(commandInterfaceCFReason, serviceClass, onComplete);
            return;
        }

        if (isValidCommandInterfaceCFReason(commandInterfaceCFReason)) {
            if (DBG) logd("requesting call forwarding query.");
            Message resp;
            if (commandInterfaceCFReason == CF_REASON_UNCONDITIONAL) {
                resp = obtainMessage(EVENT_GET_CALL_FORWARD_DONE, onComplete);
            } else {
                resp = onComplete;
            }
            mCi.queryCallForwardStatus(commandInterfaceCFReason, serviceClass, null, resp);
        }
    }

    @Override
    public void setCallForwardingOption(int commandInterfaceCFAction,
            int commandInterfaceCFReason,
            String dialingNumber,
            int timerSeconds,
            Message onComplete) {
        setCallForwardingOption(commandInterfaceCFAction, commandInterfaceCFReason,
                dialingNumber, CommandsInterface.SERVICE_CLASS_VOICE, timerSeconds, onComplete);
    }

    @Override
    public void setCallForwardingOption(int commandInterfaceCFAction,
            int commandInterfaceCFReason,
            String dialingNumber,
            int serviceClass,
            int timerSeconds,
            Message onComplete) {
        // Perform FDN check
        SsData.RequestType requestType = GsmMmiCode.cfActionToRequestType(commandInterfaceCFAction);
        SsData.ServiceType serviceType = GsmMmiCode.cfReasonToServiceType(commandInterfaceCFReason);
        if(isRequestBlockedByFDN(requestType, serviceType)) {
            AsyncResult.forMessage(onComplete, null,
                    new CommandException(CommandException.Error.FDN_CHECK_FAILURE));
            onComplete.sendToTarget();
            return;
        }

        Phone imsPhone = mImsPhone;
        if (useSsOverIms(onComplete)) {
            imsPhone.setCallForwardingOption(commandInterfaceCFAction, commandInterfaceCFReason,
                    dialingNumber, serviceClass, timerSeconds, onComplete);
            return;
        }

        if (isValidCommandInterfaceCFAction(commandInterfaceCFAction)
                && isValidCommandInterfaceCFReason(commandInterfaceCFReason)) {

            Message resp;
            if (commandInterfaceCFReason == CF_REASON_UNCONDITIONAL) {
                Cfu cfu = new Cfu(dialingNumber, onComplete);
                resp = obtainMessage(EVENT_SET_CALL_FORWARD_DONE,
                        isCfEnable(commandInterfaceCFAction) ? 1 : 0, 0, cfu);
            } else {
                resp = onComplete;
            }
            mCi.setCallForward(commandInterfaceCFAction,
                    commandInterfaceCFReason,
                    serviceClass,
                    dialingNumber,
                    timerSeconds,
                    resp);
        }
    }

    @Override
    public void getCallBarring(String facility, String password, Message onComplete,
            int serviceClass) {
        // Perform FDN check
        SsData.ServiceType serviceType = GsmMmiCode.cbFacilityToServiceType(facility);
        if (isRequestBlockedByFDN(SsData.RequestType.SS_INTERROGATION, serviceType)) {
            AsyncResult.forMessage(onComplete, null,
                    new CommandException(CommandException.Error.FDN_CHECK_FAILURE));
            onComplete.sendToTarget();
            return;
        }

        Phone imsPhone = mImsPhone;
        if (useSsOverIms(onComplete)) {
            imsPhone.getCallBarring(facility, password, onComplete, serviceClass);
            return;
        }
        mCi.queryFacilityLock(facility, password, serviceClass, onComplete);
    }

    @Override
    public void setCallBarring(String facility, boolean lockState, String password,
            Message onComplete, int serviceClass) {
        // Perform FDN check
        SsData.RequestType requestType = lockState ? SsData.RequestType.SS_ACTIVATION :
                SsData.RequestType.SS_DEACTIVATION;
        SsData.ServiceType serviceType = GsmMmiCode.cbFacilityToServiceType(facility);
        if (isRequestBlockedByFDN(requestType, serviceType)) {
            AsyncResult.forMessage(onComplete, null,
                    new CommandException(CommandException.Error.FDN_CHECK_FAILURE));
            onComplete.sendToTarget();
            return;
        }

        Phone imsPhone = mImsPhone;
        if (useSsOverIms(onComplete)) {
            imsPhone.setCallBarring(facility, lockState, password, onComplete, serviceClass);
            return;
        }

        mCi.setFacilityLock(facility, lockState, password, serviceClass, onComplete);
    }

    /**
     * Changes access code used for call barring
     *
     * @param facility is one of CB_FACILTY_*
     * @param oldPwd is old password
     * @param newPwd is new password
     * @param onComplete is callback message when the action is completed.
     */
    public void changeCallBarringPassword(String facility, String oldPwd, String newPwd,
            Message onComplete) {
        // Perform FDN check
        SsData.ServiceType serviceType = GsmMmiCode.cbFacilityToServiceType(facility);
        ArrayList<String> controlStrings = GsmMmiCode.getControlStringsForPwd(
                SsData.RequestType.SS_REGISTRATION,
                serviceType);
        if(FdnUtils.isSuppServiceRequestBlockedByFdn(mPhoneId, controlStrings, getCountryIso())) {
            AsyncResult.forMessage(onComplete, null,
                    new CommandException(CommandException.Error.FDN_CHECK_FAILURE));
            onComplete.sendToTarget();
            return;
        }

        mCi.changeBarringPassword(facility, oldPwd, newPwd, onComplete);
    }

    @Override
    public void getOutgoingCallerIdDisplay(Message onComplete) {
        // Perform FDN check
        if(isRequestBlockedByFDN(SsData.RequestType.SS_INTERROGATION, SsData.ServiceType.SS_CLIR)){
            AsyncResult.forMessage(onComplete, null,
                    new CommandException(CommandException.Error.FDN_CHECK_FAILURE));
            onComplete.sendToTarget();
            return;
        }

        Phone imsPhone = mImsPhone;

        if (useSsOverIms(onComplete)) {
            imsPhone.getOutgoingCallerIdDisplay(onComplete);
            return;
        }

        mCi.getCLIR(onComplete);
    }

    @Override
    public void setOutgoingCallerIdDisplay(int commandInterfaceCLIRMode, Message onComplete) {
        // Perform FDN check
        SsData.RequestType requestType = GsmMmiCode.clirModeToRequestType(commandInterfaceCLIRMode);
        if (isRequestBlockedByFDN(requestType, SsData.ServiceType.SS_CLIR)) {
            AsyncResult.forMessage(onComplete, null,
                    new CommandException(CommandException.Error.FDN_CHECK_FAILURE));
            onComplete.sendToTarget();
            return;
        }

        Phone imsPhone = mImsPhone;
        if (useSsOverIms(onComplete)) {
            imsPhone.setOutgoingCallerIdDisplay(commandInterfaceCLIRMode, onComplete);
            return;
        }

        // Packing CLIR value in the message. This will be required for
        // SharedPreference caching, if the message comes back as part of
        // a success response.
        mCi.setCLIR(commandInterfaceCLIRMode,
                obtainMessage(EVENT_SET_CLIR_COMPLETE, commandInterfaceCLIRMode, 0, onComplete));
    }

    @Override
    public void queryCLIP(Message onComplete) {
        // Perform FDN check
        if(isRequestBlockedByFDN(SsData.RequestType.SS_INTERROGATION, SsData.ServiceType.SS_CLIP)){
            AsyncResult.forMessage(onComplete, null,
                    new CommandException(CommandException.Error.FDN_CHECK_FAILURE));
            onComplete.sendToTarget();
            return;
        }

        Phone imsPhone = mImsPhone;
        if (useSsOverIms(onComplete)) {
            imsPhone.queryCLIP(onComplete);
            return;
        }

        mCi.queryCLIP(onComplete);
    }

    @Override
    public void getCallWaiting(Message onComplete) {
        // Perform FDN check
        if(isRequestBlockedByFDN(SsData.RequestType.SS_INTERROGATION, SsData.ServiceType.SS_WAIT)){
            AsyncResult.forMessage(onComplete, null,
                    new CommandException(CommandException.Error.FDN_CHECK_FAILURE));
            onComplete.sendToTarget();
            return;
        }

        if (mCallWaitingController.getCallWaiting(onComplete)) return;

        Phone imsPhone = mImsPhone;
        if (useSsOverIms(onComplete)) {
            imsPhone.getCallWaiting(onComplete);
            return;
        }

        //As per 3GPP TS 24.083, section 1.6 UE doesn't need to send service
        //class parameter in call waiting interrogation  to network
        mCi.queryCallWaiting(CommandsInterface.SERVICE_CLASS_NONE, onComplete);
    }

    @Override
    public void setCallWaiting(boolean enable, Message onComplete) {
        int serviceClass = CommandsInterface.SERVICE_CLASS_VOICE;
        CarrierConfigManager configManager = (CarrierConfigManager)
            getContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
        PersistableBundle b = configManager.getConfigForSubId(getSubId());
        if (b != null) {
            serviceClass = b.getInt(CarrierConfigManager.KEY_CALL_WAITING_SERVICE_CLASS_INT,
                    CommandsInterface.SERVICE_CLASS_VOICE);
        }
        setCallWaiting(enable, serviceClass, onComplete);
    }

    @Override
    public void setCallWaiting(boolean enable, int serviceClass, Message onComplete) {
        // Perform FDN check
        SsData.RequestType requestType = enable ? SsData.RequestType.SS_ACTIVATION :
                SsData.RequestType.SS_DEACTIVATION;
        if (isRequestBlockedByFDN(requestType, SsData.ServiceType.SS_WAIT)) {
            AsyncResult.forMessage(onComplete, null,
                    new CommandException(CommandException.Error.FDN_CHECK_FAILURE));
            onComplete.sendToTarget();
            return;
        }

        if (mCallWaitingController.setCallWaiting(enable, serviceClass, onComplete)) return;

        Phone imsPhone = mImsPhone;
        if (useSsOverIms(onComplete)) {
            imsPhone.setCallWaiting(enable, onComplete);
            return;
        }

        mCi.setCallWaiting(enable, serviceClass, onComplete);
    }

    @Override
    public int getTerminalBasedCallWaitingState(boolean forCsOnly) {
        return mCallWaitingController.getTerminalBasedCallWaitingState(forCsOnly);
    }

    @Override
    public void setTerminalBasedCallWaitingStatus(int state) {
        if (mImsPhone != null) {
            mImsPhone.setTerminalBasedCallWaitingStatus(state);
        }
    }

    @Override
    public void setTerminalBasedCallWaitingSupported(boolean supported) {
        mCallWaitingController.setTerminalBasedCallWaitingSupported(supported);
    }

    @Override
    public void getAvailableNetworks(Message response) {
        Message msg = obtainMessage(EVENT_GET_AVAILABLE_NETWORKS_DONE, response);
        mCi.getAvailableNetworks(msg);
    }

    @Override
    public void startNetworkScan(NetworkScanRequest nsr, Message response) {
        mCi.startNetworkScan(nsr, response);
    }

    @Override
    public void stopNetworkScan(Message response) {
        mCi.stopNetworkScan(response);
    }

    @Override
    public void setTTYMode(int ttyMode, Message onComplete) {
        // Send out the TTY Mode change over RIL as well
        super.setTTYMode(ttyMode, onComplete);
        if (mImsPhone != null) {
            mImsPhone.setTTYMode(ttyMode, onComplete);
        }
    }

    @Override
    public void setUiTTYMode(int uiTtyMode, Message onComplete) {
       if (mImsPhone != null) {
           mImsPhone.setUiTTYMode(uiTtyMode, onComplete);
       }
    }

    @Override
    public void setMute(boolean muted) {
        mCT.setMute(muted);
    }

    @Override
    public boolean getMute() {
        return mCT.getMute();
    }

    @Override
    public void updateServiceLocation(WorkSource workSource) {
        mSST.enableSingleLocationUpdate(workSource);
    }

    @Override
    public void enableLocationUpdates() {
        mSST.enableLocationUpdates();
    }

    @Override
    public void disableLocationUpdates() {
        mSST.disableLocationUpdates();
    }

    @Override
    public boolean getDataRoamingEnabled() {
        return getDataSettingsManager().isDataRoamingEnabled();
    }

    @Override
    public void setDataRoamingEnabled(boolean enable) {
        getDataSettingsManager().setDataRoamingEnabled(enable);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @Override
    public void setOnEcbModeExitResponse(Handler h, int what, Object obj) {
        mEcmExitRespRegistrant = new Registrant(h, what, obj);
    }

    @Override
    public void unsetOnEcbModeExitResponse(Handler h) {
        mEcmExitRespRegistrant.clear();
    }

    @Override
    public void registerForCallWaiting(Handler h, int what, Object obj) {
        if (mCT != null) mCT.registerForCallWaiting(h, what, obj);
    }

    @Override
    public void unregisterForCallWaiting(Handler h) {
        if (mCT != null) mCT.unregisterForCallWaiting(h);
    }

    /**
     * Whether data is enabled by user.
     */
    @Override
    public boolean isUserDataEnabled() {
        return getDataSettingsManager().isDataEnabledForReason(
                TelephonyManager.DATA_ENABLED_REASON_USER);
    }

    /**
     * Removes the given MMI from the pending list and notifies
     * registrants that it is complete.
     * @param mmi MMI that is done
     */
    public void onMMIDone(MmiCode mmi) {
        /* Only notify complete if it's on the pending list.
         * Otherwise, it's already been handled (eg, previously canceled).
         * The exception is cancellation of an incoming USSD-REQUEST, which is
         * not on the list.
         */
        if (mPendingMMIs.remove(mmi) || mmi.isUssdRequest() || ((GsmMmiCode) mmi).isSsInfo()) {
            ResultReceiver receiverCallback = mmi.getUssdCallbackReceiver();
            if (receiverCallback != null) {
                Rlog.i(LOG_TAG, "onMMIDone: invoking callback: " + mmi);
                int returnCode = (mmi.getState() ==  MmiCode.State.COMPLETE) ?
                    TelephonyManager.USSD_RETURN_SUCCESS : TelephonyManager.USSD_RETURN_FAILURE;
                sendUssdResponse(mmi.getDialString(), mmi.getMessage(), returnCode,
                        receiverCallback );
            } else {
                Rlog.i(LOG_TAG, "onMMIDone: notifying registrants: " + mmi);
                mMmiCompleteRegistrants.notifyRegistrants(new AsyncResult(null, mmi, null));
            }
        } else {
            Rlog.i(LOG_TAG, "onMMIDone: invalid response or already handled; ignoring: " + mmi);
        }
    }

    public boolean supports3gppCallForwardingWhileRoaming() {
        CarrierConfigManager configManager = (CarrierConfigManager)
                getContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
        PersistableBundle b = configManager.getConfigForSubId(getSubId());
        if (b != null) {
            return b.getBoolean(
                    CarrierConfigManager.KEY_SUPPORT_3GPP_CALL_FORWARDING_WHILE_ROAMING_BOOL, true);
        } else {
            // Default value set in CarrierConfigManager
            return true;
        }
    }

    private void onNetworkInitiatedUssd(MmiCode mmi) {
        Rlog.v(LOG_TAG, "onNetworkInitiatedUssd: mmi=" + mmi);
        mMmiCompleteRegistrants.notifyRegistrants(
            new AsyncResult(null, mmi, null));
    }

    /** ussdMode is one of CommandsInterface.USSD_MODE_* */
    private void onIncomingUSSD (int ussdMode, String ussdMessage) {
        boolean isUssdError;
        boolean isUssdRequest;
        boolean isUssdRelease;

        isUssdRequest
            = (ussdMode == CommandsInterface.USSD_MODE_REQUEST);

        isUssdError
            = (ussdMode != CommandsInterface.USSD_MODE_NOTIFY
                && ussdMode != CommandsInterface.USSD_MODE_REQUEST);

        isUssdRelease = (ussdMode == CommandsInterface.USSD_MODE_NW_RELEASE);


        // See comments in GsmMmiCode.java
        // USSD requests aren't finished until one
        // of these two events happen
        GsmMmiCode found = null;
        for (int i = 0, s = mPendingMMIs.size() ; i < s; i++) {
            if(((GsmMmiCode)mPendingMMIs.get(i)).isPendingUSSD()) {
                found = (GsmMmiCode)mPendingMMIs.get(i);
                break;
            }
        }

        if (found != null) {
            // Complete pending USSD
            if (isUssdRelease) {
                found.onUssdRelease();
            } else if (isUssdError) {
                found.onUssdFinishedError();
            } else {
                found.onUssdFinished(ussdMessage, isUssdRequest);
            }
        } else if (!isUssdError && !TextUtils.isEmpty(ussdMessage)) {
            // pending USSD not found
            // The network may initiate its own USSD request

            // ignore everything that isnt a Notify or a Request
            // also, discard if there is no message to present
            GsmMmiCode mmi;
            mmi = GsmMmiCode.newNetworkInitiatedUssd(ussdMessage,
                                                   isUssdRequest,
                                                   GsmCdmaPhone.this,
                                                   mUiccApplication.get());
            onNetworkInitiatedUssd(mmi);
        } else if (isUssdError && !isUssdRelease) {
            GsmMmiCode mmi;
            mmi = GsmMmiCode.newNetworkInitiatedUssd(ussdMessage,
                    true,
                    GsmCdmaPhone.this,
                    mUiccApplication.get());
            mmi.onUssdFinishedError();
        }
    }

    /**
     * Make sure the network knows our preferred setting.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void syncClirSetting() {
        if (!hasCalling()) return;

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        migrateClirSettingIfNeeded(sp);

        int clirSetting = sp.getInt(CLIR_KEY + getSubId(), -1);
        Rlog.i(LOG_TAG, "syncClirSetting: " + CLIR_KEY + getSubId() + "=" + clirSetting);
        if (clirSetting >= 0) {
            mCi.setCLIR(clirSetting, null);
        } else {
            // if there is no preference set, ensure the CLIR is updated to the default value in
            // order to ensure that CLIR values in the RIL are not carried over during SIM swap.
            mCi.setCLIR(CommandsInterface.CLIR_DEFAULT, null);
        }
    }

    /**
     * Migrate CLIR setting with sudId mapping once if there's CLIR setting mapped with phoneId.
     */
    private void migrateClirSettingIfNeeded(SharedPreferences sp) {
        // Get old CLIR setting mapped with phoneId
        int clirSetting = sp.getInt("clir_key" + getPhoneId(), -1);
        if (clirSetting >= 0) {
            // Migrate CLIR setting to new shared preference key with subId
            Rlog.i(LOG_TAG, "Migrate CLIR setting: value=" + clirSetting + ", clir_key"
                    + getPhoneId() + " -> " + CLIR_KEY + getSubId());
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(CLIR_KEY + getSubId(), clirSetting);

            // Remove old CLIR setting key
            editor.remove("clir_key" + getPhoneId()).commit();
        }
    }

    private void handleRadioAvailable() {
        mCi.getBasebandVersion(obtainMessage(EVENT_GET_BASEBAND_VERSION_DONE));
        mCi.getImei(obtainMessage(EVENT_GET_DEVICE_IMEI_DONE));
        mCi.getDeviceIdentity(obtainMessage(EVENT_GET_DEVICE_IDENTITY_DONE));
        mCi.getRadioCapability(obtainMessage(EVENT_GET_RADIO_CAPABILITY));
        mCi.areUiccApplicationsEnabled(obtainMessage(EVENT_GET_UICC_APPS_ENABLEMENT_DONE));

        handleNullCipherEnabledChange();
        handleIdentifierDisclosureNotificationPreferenceChange();
        handleNullCipherNotificationPreferenceChanged();
    }

    private void handleRadioOn() {
        /* Proactively query voice radio technologies */
        mCi.getVoiceRadioTechnology(obtainMessage(EVENT_REQUEST_VOICE_RADIO_TECH_DONE));
    }

    private void handleRadioOffOrNotAvailable() {
        // Some MMI requests (eg USSD) are not completed
        // within the course of a CommandsInterface request
        // If the radio shuts off or resets while one of these
        // is pending, we need to clean up.

        for (int i = mPendingMMIs.size() - 1; i >= 0; i--) {
            if (((GsmMmiCode) mPendingMMIs.get(i)).isPendingUSSD()) {
                ((GsmMmiCode) mPendingMMIs.get(i)).onUssdFinishedError();
            }
        }
        mRadioOffOrNotAvailableRegistrants.notifyRegistrants();
    }

    private void handleRadioPowerStateChange() {
        @RadioPowerState int newState = mCi.getRadioState();
        Rlog.d(LOG_TAG, "handleRadioPowerStateChange, state= " + newState);
        mNotifier.notifyRadioPowerStateChanged(this, newState);
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        Message onComplete;

        switch (msg.what) {
            case EVENT_RADIO_AVAILABLE: {
                handleRadioAvailable();
            }
            break;
            case EVENT_GET_DEVICE_IMEI_DONE :
                parseImeiInfo(msg);
                break;
            case EVENT_GET_DEVICE_IDENTITY_DONE:{
                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    break;
                }
                String[] respId = (String[])ar.result;
                if (TextUtils.isEmpty(mImei)) {
                    mImei = respId[0];
                    mImeiSv = respId[1];
                }
                mEsn  =  respId[2];
                mMeid =  respId[3];
                // some modems return all 0's instead of null/empty string when MEID is unavailable
                if (!TextUtils.isEmpty(mMeid) && mMeid.matches("^0*$")) {
                    logd("EVENT_GET_DEVICE_IDENTITY_DONE: set mMeid to null");
                    mMeid = null;
                }
            }
            break;

            case EVENT_EMERGENCY_CALLBACK_MODE_ENTER:{
                handleEnterEmergencyCallbackMode(msg);
            }
            break;

            case  EVENT_EXIT_EMERGENCY_CALLBACK_RESPONSE:{
                handleExitEmergencyCallbackMode(msg);
            }
            break;

            case EVENT_MODEM_RESET: {
                logd("Event EVENT_MODEM_RESET Received" + " isInEcm = " + isInEcm()
                        + " mImsPhone = " + mImsPhone);
                if (isInEcm()) {
                    if (DomainSelectionResolver.getInstance().isDomainSelectionSupported()) {
                        EmergencyStateTracker.getInstance().exitEmergencyCallbackMode();
                    } else {
                        if (mImsPhone != null) {
                            mImsPhone.handleExitEmergencyCallbackMode();
                        }
                    }
                }
            }
            break;

            case EVENT_RUIM_RECORDS_LOADED:
                logd("Event EVENT_RUIM_RECORDS_LOADED Received");
                updateCurrentCarrierInProvider();
                break;

            case EVENT_RADIO_ON:
                logd("Event EVENT_RADIO_ON Received");
                handleRadioOn();
                break;

            case EVENT_VOICE_RADIO_TECH_CHANGED:
            case EVENT_REQUEST_VOICE_RADIO_TECH_DONE:
                String what = (msg.what == EVENT_VOICE_RADIO_TECH_CHANGED) ?
                        "EVENT_VOICE_RADIO_TECH_CHANGED" : "EVENT_REQUEST_VOICE_RADIO_TECH_DONE";
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    if ((ar.result != null) && (((int[]) ar.result).length != 0)) {
                        int newVoiceTech = ((int[]) ar.result)[0];
                        logd(what + ": newVoiceTech=" + newVoiceTech);
                    } else {
                        loge(what + ": has no tech!");
                    }
                } else {
                    loge(what + ": exception=" + ar.exception);
                }
                break;

            case EVENT_LINK_CAPACITY_CHANGED:
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null && ar.result != null) {
                    updateLinkCapacityEstimate((List<LinkCapacityEstimate>) ar.result);
                } else {
                    logd("Unexpected exception on EVENT_LINK_CAPACITY_CHANGED");
                }
                break;

            case EVENT_CARRIER_CONFIG_CHANGED:
                // Only check for the voice radio tech if it not going to be updated by the voice
                // registration changes.
                if (!mContext.getResources().getBoolean(
                        com.android.internal.R.bool
                                .config_switch_phone_on_voice_reg_state_change)) {
                    mCi.getVoiceRadioTechnology(obtainMessage(EVENT_REQUEST_VOICE_RADIO_TECH_DONE));
                }

                CarrierConfigManager configMgr = (CarrierConfigManager)
                        getContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
                final PersistableBundle b = configMgr.getConfigForSubId(getSubId());
                if (b != null) {
                    updateNrSettingsAfterCarrierConfigChanged(b);
                    if (hasCalling()) {
                        updateVoNrSettings(b);
                    }
                    updateCarrierN1ModeSupported(b);
                } else {
                    loge("Failed to retrieve a carrier config bundle for subId=" + getSubId());
                }
                loadAllowedNetworksFromSubscriptionDatabase();

                if (b != null && mFeatureFlags.keyCarrier2gToggle()) {
                    updateDefaultEnable2gSettings(b);
                }
                // Obtain new radio capabilities from the modem, since some are SIM-dependent
                mCi.getRadioCapability(obtainMessage(EVENT_GET_RADIO_CAPABILITY));
                break;

            case EVENT_SET_ROAMING_PREFERENCE_DONE:
                logd("cdma_roaming_mode change is done");
                break;

            case EVENT_REGISTERED_TO_NETWORK:
                logd("Event EVENT_REGISTERED_TO_NETWORK Received");
                syncClirSetting();
                break;

            case EVENT_SIM_RECORDS_LOADED:
                updateCurrentCarrierInProvider();

                // Check if this is a different SIM than the previous one. If so unset the
                // voice mail number.
                String imsi = getVmSimImsi();
                String imsiFromSIM = getSubscriberId();
                if (imsi != null && imsiFromSIM != null && !imsiFromSIM.equals(imsi)) {
                    storeVoiceMailNumber(null);
                    setVmSimImsi(null);
                }

                updateVoiceMail();

                mSimRecordsLoadedRegistrants.notifyRegistrants();
                break;

            case EVENT_GET_BASEBAND_VERSION_DONE:
                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    break;
                }

                if (DBG) logd("Baseband version: " + ar.result);
                /* Android property value is limited to 91 characters, but low layer
                 could pass a larger version string. To avoid runtime exception,
                 truncate the string baseband version string to 45 characters at most
                 for this per sub property. Since the latter part of the version string
                 is meaningful, truncated the version string from the beginning and
                 keep the end of the version.
                */
                String version = (String)ar.result;
                if (version != null) {
                    int length = version.length();
                    final int MAX_VERSION_LEN = SystemProperties.PROP_VALUE_MAX/2;
                    TelephonyManager.from(mContext).setBasebandVersionForPhone(getPhoneId(),
                            length <= MAX_VERSION_LEN ? version
                                : version.substring(length - MAX_VERSION_LEN, length));
                }
            break;

            case EVENT_USSD:
                ar = (AsyncResult)msg.obj;

                String[] ussdResult = (String[]) ar.result;

                if (ussdResult.length > 1) {
                    try {
                        onIncomingUSSD(Integer.parseInt(ussdResult[0]), ussdResult[1]);
                    } catch (NumberFormatException e) {
                        Rlog.w(LOG_TAG, "error parsing USSD");
                    }
                }
            break;

            case EVENT_RADIO_OFF_OR_NOT_AVAILABLE: {
                logd("Event EVENT_RADIO_OFF_OR_NOT_AVAILABLE Received");
                handleRadioOffOrNotAvailable();
                break;
            }

            case EVENT_RADIO_STATE_CHANGED: {
                logd("EVENT EVENT_RADIO_STATE_CHANGED");
                handleRadioPowerStateChange();
                break;
            }

            case EVENT_SSN:
                logd("Event EVENT_SSN Received");
                ar = (AsyncResult) msg.obj;
                mSsnRegistrants.notifyRegistrants(ar);
                break;

            case EVENT_REGISTRATION_FAILED:
                logd("Event RegistrationFailed Received");
                ar = (AsyncResult) msg.obj;
                RegistrationFailedEvent rfe = (RegistrationFailedEvent) ar.result;
                mNotifier.notifyRegistrationFailed(this, rfe.cellIdentity, rfe.chosenPlmn,
                        rfe.domain, rfe.causeCode, rfe.additionalCauseCode);
                break;

            case EVENT_BARRING_INFO_CHANGED:
                logd("Event BarringInfoChanged Received");
                ar = (AsyncResult) msg.obj;
                BarringInfo barringInfo = (BarringInfo) ar.result;
                mNotifier.notifyBarringInfoChanged(this, barringInfo);
                break;

            case EVENT_SET_CALL_FORWARD_DONE:
                ar = (AsyncResult)msg.obj;
                Cfu cfu = (Cfu) ar.userObj;
                if (ar.exception == null) {
                    setVoiceCallForwardingFlag(1, msg.arg1 == 1, cfu.mSetCfNumber);
                }
                if (cfu.mOnComplete != null) {
                    AsyncResult.forMessage(cfu.mOnComplete, ar.result, ar.exception);
                    cfu.mOnComplete.sendToTarget();
                }
                break;

            case EVENT_SET_VM_NUMBER_DONE:
                ar = (AsyncResult)msg.obj;
                if (ar.exception instanceof IccVmNotSupportedException) {
                    storeVoiceMailNumber(mVmNumber);
                    ar.exception = null;
                }
                onComplete = (Message) ar.userObj;
                if (onComplete != null) {
                    AsyncResult.forMessage(onComplete, ar.result, ar.exception);
                    onComplete.sendToTarget();
                }
                break;

            case EVENT_GET_CALL_FORWARD_DONE:
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    handleCfuQueryResult((CallForwardInfo[])ar.result);
                }
                onComplete = (Message) ar.userObj;
                if (onComplete != null) {
                    AsyncResult.forMessage(onComplete, ar.result, ar.exception);
                    onComplete.sendToTarget();
                }
                break;

            case EVENT_SET_NETWORK_AUTOMATIC:
                // Automatic network selection from EF_CSP SIM record
                ar = (AsyncResult) msg.obj;
                if (mSST.mSS.getIsManualSelection()) {
                    setNetworkSelectionModeAutomatic((Message) ar.result);
                    logd("SET_NETWORK_SELECTION_AUTOMATIC: set to automatic");
                } else {
                    // prevent duplicate request which will push current PLMN to low priority
                    logd("SET_NETWORK_SELECTION_AUTOMATIC: already automatic, ignore");
                }
                break;

            case EVENT_ICC_RECORD_EVENTS:
                ar = (AsyncResult)msg.obj;
                processIccRecordEvents((Integer)ar.result);
                break;

            case EVENT_SET_CLIR_COMPLETE:
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    saveClirSetting(msg.arg1);
                }
                onComplete = (Message) ar.userObj;
                if (onComplete != null) {
                    AsyncResult.forMessage(onComplete, ar.result, ar.exception);
                    onComplete.sendToTarget();
                }
                break;

            case EVENT_SS:
                ar = (AsyncResult)msg.obj;
                logd("Event EVENT_SS received");
                // SS data is already being handled through MMI codes.
                // So, this result if processed as MMI response would help
                // in re-using the existing functionality.
                GsmMmiCode mmi = new GsmMmiCode(this, mUiccApplication.get());
                mmi.processSsData(ar);
                break;

            case EVENT_GET_RADIO_CAPABILITY:
                ar = (AsyncResult) msg.obj;
                RadioCapability rc = (RadioCapability) ar.result;
                if (ar.exception != null) {
                    Rlog.d(LOG_TAG, "get phone radio capability fail, no need to change " +
                            "mRadioCapability");
                } else {
                    radioCapabilityUpdated(rc, false);
                }
                Rlog.d(LOG_TAG, "EVENT_GET_RADIO_CAPABILITY: phone rc: " + rc);
                break;
            case EVENT_VRS_OR_RAT_CHANGED:
                ar = (AsyncResult) msg.obj;
                Pair<Integer, Integer> vrsRatPair = (Pair<Integer, Integer>) ar.result;
                onVoiceRegStateOrRatChanged(vrsRatPair.first, vrsRatPair.second);
                break;

            case EVENT_SET_CARRIER_DATA_ENABLED:
                ar = (AsyncResult) msg.obj;
                boolean enabled = (boolean) ar.result;
                getDataSettingsManager().setDataEnabled(
                        TelephonyManager.DATA_ENABLED_REASON_CARRIER, enabled,
                        mContext.getOpPackageName());
                break;
            case EVENT_GET_AVAILABLE_NETWORKS_DONE:
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null && ar.result != null && mSST != null) {
                    List<OperatorInfo> operatorInfoList = (List<OperatorInfo>) ar.result;
                    List<OperatorInfo> filteredInfoList = new ArrayList<>();
                    for (OperatorInfo operatorInfo : operatorInfoList) {
                        if (OperatorInfo.State.CURRENT == operatorInfo.getState()) {
                            filteredInfoList.add(new OperatorInfo(
                                    mSST.filterOperatorNameByPattern(
                                            operatorInfo.getOperatorAlphaLong()),
                                    mSST.filterOperatorNameByPattern(
                                            operatorInfo.getOperatorAlphaShort()),
                                    operatorInfo.getOperatorNumeric(),
                                    operatorInfo.getState()
                            ));
                        } else {
                            filteredInfoList.add(operatorInfo);
                        }
                    }
                    ar.result = filteredInfoList;
                }

                onComplete = (Message) ar.userObj;
                if (onComplete != null) {
                    AsyncResult.forMessage(onComplete, ar.result, ar.exception);
                    onComplete.sendToTarget();
                }
                break;
            case EVENT_GET_UICC_APPS_ENABLEMENT_DONE:
            case EVENT_UICC_APPS_ENABLEMENT_STATUS_CHANGED:
                ar = (AsyncResult) msg.obj;
                if (ar == null) return;
                if (ar.exception != null) {
                    logd("Received exception on event" + msg.what + " : " + ar.exception);
                    return;
                }

                mUiccApplicationsEnabled = (Boolean) ar.result;
            // Intentional falling through.
            case EVENT_UICC_APPS_ENABLEMENT_SETTING_CHANGED:
                reapplyUiccAppsEnablementIfNeeded(ENABLE_UICC_APPS_MAX_RETRIES);
                break;

            case EVENT_REAPPLY_UICC_APPS_ENABLEMENT_DONE: {
                ar = (AsyncResult) msg.obj;
                if (ar == null || ar.exception == null) return;
                Pair<Boolean, Integer> userObject = (Pair) ar.userObj;
                if (userObject == null) return;
                boolean expectedValue = userObject.first;
                int retries = userObject.second;
                CommandException.Error error = ((CommandException) ar.exception).getCommandError();
                loge("Error received when re-applying uicc application"
                        + " setting to " +  expectedValue + " on phone " + mPhoneId
                        + " Error code: " + error + " retry count left: " + retries);
                if (retries > 0 && (error == GENERIC_FAILURE || error == SIM_BUSY)) {
                    // Retry for certain errors, but not for others like RADIO_NOT_AVAILABLE or
                    // SIM_ABSENT, as they will trigger it whey they become available.
                    postDelayed(()->reapplyUiccAppsEnablementIfNeeded(retries - 1),
                            REAPPLY_UICC_APPS_SETTING_RETRY_TIME_GAP_IN_MS);
                }
                break;
            }
            case EVENT_RESET_CARRIER_KEY_IMSI_ENCRYPTION: {
                resetCarrierKeysForImsiEncryption();
                break;
            }
            case EVENT_SET_VONR_ENABLED_DONE:
                logd("EVENT_SET_VONR_ENABLED_DONE is done");
                break;
            case EVENT_SUBSCRIPTIONS_CHANGED:
                logd("EVENT_SUBSCRIPTIONS_CHANGED");
                updateUsageSetting();
                updateNullCipherNotifier();
                break;
            case EVENT_SET_NULL_CIPHER_AND_INTEGRITY_DONE:
                logd("EVENT_SET_NULL_CIPHER_AND_INTEGRITY_DONE");
                ar = (AsyncResult) msg.obj;
                mIsNullCipherAndIntegritySupported = doesResultIndicateModemSupport(ar);
                break;

            case EVENT_IMS_DEREGISTRATION_TRIGGERED:
                logd("EVENT_IMS_DEREGISTRATION_TRIGGERED");
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    mImsPhone.triggerImsDeregistration(((int[]) ar.result)[0]);
                } else {
                    Rlog.e(LOG_TAG, "Unexpected unsol with exception", ar.exception);
                }
                break;

            case EVENT_TRIGGER_NOTIFY_ANBR:
                logd("EVENT_TRIGGER_NOTIFY_ANBR");
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    if (mImsPhone != null) {
                        mImsPhone.triggerNotifyAnbr(((int[]) ar.result)[0], ((int[]) ar.result)[1],
                                ((int[]) ar.result)[2]);
                    }
                }
                break;

            case EVENT_GET_N1_MODE_ENABLED_DONE:
                logd("EVENT_GET_N1_MODE_ENABLED_DONE");
                ar = (AsyncResult) msg.obj;
                if (ar == null || ar.exception != null
                        || ar.result == null || !(ar.result instanceof Boolean)) {
                    Rlog.e(LOG_TAG, "Failed to Retrieve N1 Mode", ar.exception);
                    if (ar != null && ar.userObj instanceof Message) {
                        // original requester's message is stashed in the userObj
                        final Message rsp = (Message) ar.userObj;
                        AsyncResult.forMessage(rsp, null, ar.exception);
                        rsp.sendToTarget();
                    }
                    break;
                }

                mModemN1Mode = (Boolean) ar.result;
                maybeUpdateModemN1Mode((Message) ar.userObj);
                break;

            case EVENT_SET_N1_MODE_ENABLED_DONE:
                logd("EVENT_SET_N1_MODE_ENABLED_DONE");
                ar = (AsyncResult) msg.obj;
                if (ar == null || ar.exception != null) {
                    Rlog.e(LOG_TAG, "Failed to Set N1 Mode", ar.exception);
                    // Set failed, so we have no idea at this point.
                    mModemN1Mode = null;
                }
                if (ar != null && ar.userObj instanceof Message) {
                    // original requester's message is stashed in the userObj
                    final Message rsp = (Message) ar.userObj;
                    AsyncResult.forMessage(rsp, null, ar.exception);
                    rsp.sendToTarget();
                }
                break;

            case EVENT_IMEI_MAPPING_CHANGED:
                logd("EVENT_GET_DEVICE_IMEI_CHANGE_DONE phoneId = " + getPhoneId());
                parseImeiInfo(msg);
                break;

            case EVENT_CELL_IDENTIFIER_DISCLOSURE:
                logd("EVENT_CELL_IDENTIFIER_DISCLOSURE phoneId = " + getPhoneId());

                ar = (AsyncResult) msg.obj;
                if (ar == null || ar.result == null || ar.exception != null) {
                    Rlog.e(
                            LOG_TAG,
                            "Failed to process cellular identifier disclosure",
                            ar.exception);
                    break;
                }

                CellularIdentifierDisclosure disclosure = (CellularIdentifierDisclosure) ar.result;
                if (mIdentifierDisclosureNotifier == null ||  disclosure == null) {
                    logd("EVENT_CELL_IDENTIFIER_DISCLOSURE mIdentifierDisclosureNotifier or"
                            + " disclosure is null.");
                    return;
                }

                if (queueCellularEventIfSubIdInvalid(msg, "EVENT_CELL_IDENTIFIER_DISCLOSURE")) {
                    return;
                }

                mIdentifierDisclosureNotifier.addDisclosure(mContext, getSubId(), disclosure);
                logd("EVENT_CELL_IDENTIFIER_DISCLOSURE for non-Safety Center listeners "
                        + "phoneId = " + getPhoneId());
                mNotifier.notifyCellularIdentifierDisclosedChanged(this, disclosure);
                break;

            case EVENT_SET_IDENTIFIER_DISCLOSURE_ENABLED_DONE:
                logd("EVENT_SET_IDENTIFIER_DISCLOSURE_ENABLED_DONE");
                ar = (AsyncResult) msg.obj;
                mIsIdentifierDisclosureTransparencySupported = doesResultIndicateModemSupport(ar);
                break;

            case EVENT_SECURITY_ALGORITHM_UPDATE:
                logd("EVENT_SECURITY_ALGORITHM_UPDATE phoneId = " + getPhoneId());

                ar = (AsyncResult) msg.obj;
                SecurityAlgorithmUpdate update = (SecurityAlgorithmUpdate) ar.result;

                if (mNullCipherNotifier == null) {
                    logd("EVENT_SECURITY_ALGORITHM_UPDATE mNullCipherNotifier is null.");
                    return;
                }

                if (queueCellularEventIfSubIdInvalid(msg, "EVENT_SECURITY_ALGORITHM_UPDATE")) {
                    return;
                }

                mNullCipherNotifier.onSecurityAlgorithmUpdate(mContext, getPhoneId(), getSubId(),
                        update);
                logd("EVENT_SECURITY_ALGORITHM_UPDATE for non-Safety Center listeners "
                          + "phoneId = " + getPhoneId());
                mNotifier.notifySecurityAlgorithmsChanged(this, update);
                break;

            case EVENT_SET_SECURITY_ALGORITHMS_UPDATED_ENABLED_DONE:
                logd("EVENT_SET_SECURITY_ALGORITHMS_UPDATED_ENABLED_DONE");
                ar = (AsyncResult) msg.obj;
                mIsNullCipherNotificationSupported = doesResultIndicateModemSupport(ar);
                break;

            case EVENT_NETWORK_SECURITY_EVENTS:
                logd("EVENT_NETWORK_SECURITY_EVENTS phoneId = " + getPhoneId());
                ar = (AsyncResult) msg.obj;
                if (ar == null) {
                    Rlog.e(LOG_TAG, "EVENT_NETWORK_SECURITY_EVENTS: ar is null");
                    break;
                }

                if (ar.result == null || ar.exception != null) {
                    Rlog.e(
                            LOG_TAG,
                            "Failed to process network security events",
                            ar.exception);
                    break;
                }
                Set<NetworkSecurityEvent> events = (Set<NetworkSecurityEvent>) ar.result;

                if (queueCellularEventIfSubIdInvalid(msg, "EVENT_NETWORK_SECURITY_EVENTS")) {
                    return;
                }

                if (mFeatureFlags.networkSecurityEventIndications()) {
                    logd("EVENT_NETWORK_SECURITY_EVENTS for non-Safety Center listeners "
                            + "phoneId = " + getPhoneId());
                    mNotifier.notifyNetworkSecurityEvents(this, events);
                }
                break;
            case EVENT_SET_ALLOWED_NETWORK_TYPES_FOR_2G_DISABLED_DONE:
                logd("EVENT_SET_ALLOWED_NETWORK_TYPES_FOR_2G_DISABLED_DONE");
                ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    // Send a notification that 2G has been disabled by the carrier.
                    sendNotification2gDisabledByCarrier();
                } else {
                    set2gProtectionDefaultUpdated(false);
                    loge("Failed to execute setAllowedNetworkTypesForReason:" + ar.exception);
                }
                break;

            default:
                super.handleMessage(msg);
        }
    }

    private boolean queueCellularEventIfSubIdInvalid(Message msg, String eventName) {
        if (!SubscriptionManager.isValidSubscriptionId(getSubId())) {
            logd("Adding event to message queue with event name: " + eventName);
            mCellularEventMessages.add(msg.obtain(msg));
            return true;
        }
        return false;
    }

    private boolean doesResultIndicateModemSupport(AsyncResult ar) {
        // We can only say that the modem supports a call without ambiguity if there
        // is no exception set on the response.  Testing for REQUEST_NOT_SUPPORTED, is
        // insufficient because the modem or the RIL could still return exceptions for temporary
        // failures even when the feature is unsupported.
        return (ar == null || ar.exception == null);
    }

    private void parseImeiInfo(Message msg) {
        AsyncResult ar = (AsyncResult)msg.obj;
        if (ar.exception != null || ar.result == null) {
            loge("parseImeiInfo :: Exception received : " + ar.exception);
            return;
        }
        ImeiInfo imeiInfo = (ImeiInfo) ar.result;
        if (!TextUtils.isEmpty(imeiInfo.imei)) {
            mImeiType = imeiInfo.type;
            mImei = imeiInfo.imei;
            mImeiSv = imeiInfo.svn;
        } else {
            loge("parseImeiInfo :: IMEI value is empty");
        }
    }

    /**
     * Check if a different SIM is inserted at this slot from the last time. Storing last subId
     * in SharedPreference for now to detect SIM change.
     *
     * @return {@code true} if current slot mapping changed; {@code false} otherwise.
     */
    private boolean currentSlotSubIdChanged() {
        SharedPreferences sp =
                PreferenceManager.getDefaultSharedPreferences(mContext);
        int storedSubId = sp.getInt(CURR_SUBID + mPhoneId, -1);
        boolean changed = storedSubId != getSubId();
        if (changed) {
            // Update stored subId
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(CURR_SUBID + mPhoneId, getSubId());
            editor.apply();
        }
        Rlog.d(LOG_TAG, "currentSlotSubIdChanged: changed=" + changed);
        return changed;
    }

    public UiccCardApplication getUiccCardApplication() {
        return mUiccController.getUiccCardApplication(mPhoneId, UiccController.APP_FAM_3GPP);
    }

    // todo: check if ICC availability needs to be handled here. mSimRecords should not be needed
    // now because APIs can be called directly on UiccProfile, and that should handle the requests
    // correctly based on supported apps, voice RAT, etc.
    @Override
    protected void onUpdateIccAvailability() {
        if (mUiccController == null ) {
            return;
        }

        UiccCardApplication newUiccApplication = null;

        // Update mIsimUiccRecords
        newUiccApplication =
                mUiccController.getUiccCardApplication(mPhoneId, UiccController.APP_FAM_IMS);
        IsimUiccRecords newIsimUiccRecords = null;

        if (newUiccApplication != null) {
            newIsimUiccRecords = (IsimUiccRecords) newUiccApplication.getIccRecords();
            if (DBG) logd("New ISIM application found");
        }
        mIsimUiccRecords = newIsimUiccRecords;

        // Update mSimRecords
        if (mSimRecords != null) {
            mSimRecords.unregisterForRecordsLoaded(this);
        }
        mSimRecords = null;

        // Update mIccRecords, mUiccApplication, mIccPhoneBookIntManager
        newUiccApplication = getUiccCardApplication();

        UiccCardApplication app = mUiccApplication.get();
        if (app != newUiccApplication) {
            if (app != null) {
                if (DBG) logd("Removing stale icc objects.");
                if (mIccRecords.get() != null) {
                    unregisterForIccRecordEvents();
                    mIccPhoneBookIntManager.updateIccRecords(null);
                }
                mIccRecords.set(null);
                mUiccApplication.set(null);
            }
            if (newUiccApplication != null) {
                if (DBG) {
                    logd("New Uicc application found. type = " + newUiccApplication.getType());
                }
                final IccRecords iccRecords = newUiccApplication.getIccRecords();
                mUiccApplication.set(newUiccApplication);
                mIccRecords.set(iccRecords);
                registerForIccRecordEvents();
                mIccPhoneBookIntManager.updateIccRecords(iccRecords);
                if (iccRecords != null) {
                    final String simOperatorNumeric = iccRecords.getOperatorNumeric();
                    if (DBG) {
                        logd("New simOperatorNumeric = " + simOperatorNumeric);
                    }
                    if (!TextUtils.isEmpty(simOperatorNumeric)) {
                        TelephonyManager.from(mContext).setSimOperatorNumericForPhone(mPhoneId,
                                simOperatorNumeric);
                    }
                }
                updateCurrentCarrierInProvider();
            }
        }

        reapplyUiccAppsEnablementIfNeeded(ENABLE_UICC_APPS_MAX_RETRIES);
    }

    private void processIccRecordEvents(int eventCode) {
        switch (eventCode) {
            case IccRecords.EVENT_CFI:
                logi("processIccRecordEvents: EVENT_CFI");
                notifyCallForwardingIndicator();
                break;
        }
    }

    /**
     * Sets the "current" field in the telephony provider according to the SIM's operator
     *
     * @return true for success; false otherwise.
     */
    @Override
    public boolean updateCurrentCarrierInProvider() {
        long currentDds = SubscriptionManager.getDefaultDataSubscriptionId();
        String operatorNumeric = getOperatorNumeric();

        logd("updateCurrentCarrierInProvider: mSubId = " + getSubId()
                + " currentDds = " + currentDds + " operatorNumeric = " + operatorNumeric);

        if (!TextUtils.isEmpty(operatorNumeric) && (getSubId() == currentDds)) {
            try {
                Uri uri = Uri.withAppendedPath(Telephony.Carriers.CONTENT_URI, "current");
                ContentValues map = new ContentValues();
                map.put(Telephony.Carriers.NUMERIC, operatorNumeric);
                mContext.getContentResolver().insert(uri, map);
                return true;
            } catch (SQLException e) {
                Rlog.e(LOG_TAG, "Can't store current operator", e);
            }
        }
        return false;
    }

    private void handleCfuQueryResult(CallForwardInfo[] infos) {
        if (infos == null || infos.length == 0) {
            // Assume the default is not active
            // Set unconditional CFF in SIM to false
            setVoiceCallForwardingFlag(1, false, null);
        } else {
            for (int i = 0, s = infos.length; i < s; i++) {
                if ((infos[i].serviceClass & SERVICE_CLASS_VOICE) != 0) {
                    setVoiceCallForwardingFlag(1, (infos[i].status == 1),
                        infos[i].number);
                    // should only have the one
                    break;
                }
            }
        }
    }

    /**
     * Retrieves the IccPhoneBookInterfaceManager of the GsmCdmaPhone
     */
    @Override
    public IccPhoneBookInterfaceManager getIccPhoneBookInterfaceManager(){
        return mIccPhoneBookIntManager;
    }

    /**
     * Activate or deactivate cell broadcast SMS.
     *
     * @param activate 0 = activate, 1 = deactivate
     * @param response Callback message is empty on completion
     */
    @Override
    public void activateCellBroadcastSms(int activate, Message response) {
        loge("[GsmCdmaPhone] activateCellBroadcastSms() is obsolete; use SmsManager");
        response.sendToTarget();
    }

    /**
     * Query the current configuration of cdma cell broadcast SMS.
     *
     * @param response Callback message is empty on completion
     */
    @Override
    public void getCellBroadcastSmsConfig(Message response) {
        loge("[GsmCdmaPhone] getCellBroadcastSmsConfig() is obsolete; use SmsManager");
        response.sendToTarget();
    }

    /**
     * Configure cdma cell broadcast SMS.
     *
     * @param response Callback message is empty on completion
     */
    @Override
    public void setCellBroadcastSmsConfig(int[] configValuesArray, Message response) {
        loge("[GsmCdmaPhone] setCellBroadcastSmsConfig() is obsolete; use SmsManager");
        response.sendToTarget();
    }

    @Override
    public boolean isCspPlmnEnabled() {
        IccRecords r = mIccRecords.get();
        return (r != null) ? r.isCspPlmnEnabled() : false;
    }

    /**
     * Whether manual select is now allowed and we should set
     * to auto network select mode.
     */
    public boolean shouldForceAutoNetworkSelect() {

        int networkTypeBitmask = RadioAccessFamily.getRafFromNetworkType(
                RILConstants.PREFERRED_NETWORK_MODE);
        int subId = getSubId();

        // If it's invalid subId, we shouldn't force to auto network select mode.
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            return false;
        }

        networkTypeBitmask = (int) getAllowedNetworkTypes(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER);

        logd("shouldForceAutoNetworkSelect in mode = " + networkTypeBitmask);
        /*
         *  For multimode targets in global mode manual network
         *  selection is disallowed. So we should force auto select mode.
         */
        if (isManualSelProhibitedInGlobalMode()
                && ((networkTypeBitmask == RadioAccessFamily.getRafFromNetworkType(
                TelephonyManager.NETWORK_MODE_LTE_CDMA_EVDO_GSM_WCDMA))
                || (networkTypeBitmask == RadioAccessFamily.getRafFromNetworkType(
                TelephonyManager.NETWORK_MODE_GLOBAL)))) {
            logd("Should force auto network select mode = " + networkTypeBitmask);
            return true;
        } else {
            logd("Should not force auto network select mode = " + networkTypeBitmask);
        }

        /*
         *  Single mode phone with - GSM network modes/global mode
         *  LTE only for 3GPP
         *  LTE centric + 3GPP Legacy
         *  Note: the actual enabling/disabling manual selection for these
         *  cases will be controlled by csp
         */
        return false;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private boolean isManualSelProhibitedInGlobalMode() {
        boolean isProhibited = false;
        final String configString = getContext().getResources().getString(com.android.internal
                .R.string.prohibit_manual_network_selection_in_gobal_mode);

        if (!TextUtils.isEmpty(configString)) {
            String[] configArray = configString.split(";");

            if (configArray != null &&
                    ((configArray.length == 1 && configArray[0].equalsIgnoreCase("true")) ||
                        (configArray.length == 2 && !TextUtils.isEmpty(configArray[1]) &&
                            configArray[0].equalsIgnoreCase("true") &&
                            isMatchGid(configArray[1])))) {
                            isProhibited = true;
            }
        }
        logd("isManualNetSelAllowedInGlobal in current carrier is " + isProhibited);
        return isProhibited;
    }

    private void registerForIccRecordEvents() {
        IccRecords r = mIccRecords.get();
        if (r == null) {
            return;
        }
        r.registerForNetworkSelectionModeAutomatic(
                this, EVENT_SET_NETWORK_AUTOMATIC, null);
        r.registerForRecordsEvents(this, EVENT_ICC_RECORD_EVENTS, null);
        r.registerForRecordsLoaded(this, EVENT_SIM_RECORDS_LOADED, null);
    }

    private void unregisterForIccRecordEvents() {
        IccRecords r = mIccRecords.get();
        if (r == null) {
            return;
        }
        r.unregisterForNetworkSelectionModeAutomatic(this);
        r.unregisterForRecordsEvents(this);
        r.unregisterForRecordsLoaded(this);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @Override
    public void exitEmergencyCallbackMode() {
        if (DBG) {
            Rlog.d(LOG_TAG, "exitEmergencyCallbackMode: mImsPhone=" + mImsPhone);
        }
        if (DomainSelectionResolver.getInstance().isDomainSelectionSupported()) {
            EmergencyStateTracker.getInstance().exitEmergencyCallbackMode();
            return;
        }
        if (mImsPhone != null && mImsPhone.isInImsEcm()) {
            mImsPhone.exitEmergencyCallbackMode();
        } else {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
            Message msg = null;
            if (mIsTestingEmergencyCallbackMode) {
                // prevent duplicate exit messages from happening due to this message being handled
                // as well as an UNSOL when the modem exits ECbM. Instead, only register for this
                // message callback when this is a test and we will not be receiving the UNSOL from
                // the modem.
                msg = obtainMessage(EVENT_EXIT_EMERGENCY_CALLBACK_RESPONSE);
            }
            mCi.exitEmergencyCallbackMode(msg);
        }
    }

    //CDMA
    private void handleEnterEmergencyCallbackMode(Message msg) {
        if (mFeatureFlags.deleteCdma()) return;
        if (DomainSelectionResolver.getInstance().isDomainSelectionSupported()) {
            Rlog.d(LOG_TAG, "DomainSelection enabled: ignore ECBM enter event.");
            return;
        }
        if (DBG) {
            Rlog.d(LOG_TAG, "handleEnterEmergencyCallbackMode, isInEcm()="
                    + isInEcm());
        }
        // if phone is not in Ecm mode, and it's changed to Ecm mode
        if (!isInEcm()) {
            setIsInEcm(true);

            // notify change
            sendEmergencyCallbackModeChange();

            // Post this runnable so we will automatically exit
            // if no one invokes exitEmergencyCallbackMode() directly.
            long delayInMillis = TelephonyProperties.ecm_exit_timer()
                    .orElse(DEFAULT_ECM_EXIT_TIMER_VALUE);
            postDelayed(mExitEcmRunnable, delayInMillis);
            // We don't want to go to sleep while in Ecm
            mWakeLock.acquire();
        }
    }

    //CDMA
    private void handleExitEmergencyCallbackMode(Message msg) {
        if (mFeatureFlags.deleteCdma()) return;
        if (DomainSelectionResolver.getInstance().isDomainSelectionSupported()) {
            Rlog.d(LOG_TAG, "DomainSelection enabled: ignore ECBM exit event.");
            return;
        }
        AsyncResult ar = (AsyncResult)msg.obj;
        if (DBG) {
            Rlog.d(LOG_TAG, "handleExitEmergencyCallbackMode,ar.exception , isInEcm="
                    + ar.exception + isInEcm());
        }
        // Remove pending exit Ecm runnable, if any
        removeCallbacks(mExitEcmRunnable);

        if (mEcmExitRespRegistrant != null) {
            mEcmExitRespRegistrant.notifyRegistrant(ar);
        }
        // if exiting is successful or we are testing and the modem responded with an error upon
        // exit, which may occur in some IRadio implementations.
        if (ar.exception == null || mIsTestingEmergencyCallbackMode) {
            if (isInEcm()) {
                setIsInEcm(false);
            }

            // release wakeLock
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }

            // send an Intent
            sendEmergencyCallbackModeChange();
            notifyEmergencyCallRegistrants(false);
        }
        mIsTestingEmergencyCallbackMode = false;
    }

    //CDMA
    public void notifyEmergencyCallRegistrants(boolean started) {
        mEmergencyCallToggledRegistrants.notifyResult(started ? 1 : 0);
    }

    //CDMA
    /**
     * Handle to cancel or restart Ecm timer in emergency call back mode
     * if action is CANCEL_ECM_TIMER, cancel Ecm timer and notify apps the timer is canceled;
     * otherwise, restart Ecm timer and notify apps the timer is restarted.
     */
    public void handleTimerInEmergencyCallbackMode(int action) {
        if (mFeatureFlags.deleteCdma()) return;
        if (DomainSelectionResolver.getInstance().isDomainSelectionSupported()) return;
        switch(action) {
            case CANCEL_ECM_TIMER:
                removeCallbacks(mExitEcmRunnable);
                mEcmTimerResetRegistrants.notifyResult(Boolean.TRUE);
                setEcmCanceledForEmergency(true /*isCanceled*/);
                break;
            case RESTART_ECM_TIMER:
                long delayInMillis = TelephonyProperties.ecm_exit_timer()
                        .orElse(DEFAULT_ECM_EXIT_TIMER_VALUE);
                postDelayed(mExitEcmRunnable, delayInMillis);
                mEcmTimerResetRegistrants.notifyResult(Boolean.FALSE);
                setEcmCanceledForEmergency(false /*isCanceled*/);
                break;
            default:
                Rlog.e(LOG_TAG, "handleTimerInEmergencyCallbackMode, unsupported action " + action);
        }
    }

    @Override
    public void setLinkCapacityReportingCriteria(int[] dlThresholds, int[] ulThresholds, int ran) {
        mCi.setLinkCapacityReportingCriteria(REPORTING_HYSTERESIS_MILLIS, REPORTING_HYSTERESIS_KBPS,
                REPORTING_HYSTERESIS_KBPS, dlThresholds, ulThresholds, ran, null);
    }

    @Override
    public IccSmsInterfaceManager getIccSmsInterfaceManager(){
        return mIccSmsInterfaceManager;
    }

    @Override
    public void setImsRegistrationState(boolean registered) {
        mSST.setImsRegistrationState(registered);
        mCallWaitingController.setImsRegistrationState(registered);
    }

    @Override
    public boolean getIccRecordsLoaded() {
        UiccProfile uiccProfile = getUiccProfile();
        return uiccProfile != null && uiccProfile.getIccRecordsLoaded();
    }

    @Override
    public IccCard getIccCard() {
        // This function doesn't return null for backwards compatability purposes.
        // To differentiate between cases where SIM is absent vs. unknown we return a placeholder
        // IccCard with the sim state set.
        IccCard card = getUiccProfile();
        if (card != null) {
            return card;
        } else {
            UiccSlot slot = mUiccController.getUiccSlotForPhone(mPhoneId);
            if (slot == null || slot.isStateUnknown()) {
                return new IccCard(IccCardConstants.State.UNKNOWN);
            } else {
                return new IccCard(IccCardConstants.State.ABSENT);
            }
        }
    }

    private UiccProfile getUiccProfile() {
        return UiccController.getInstance().getUiccProfileForPhone(mPhoneId);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("GsmCdmaPhone extends:");
        super.dump(fd, pw, args);
        pw.println(" mPrecisePhoneType=" + mPrecisePhoneType);
        pw.println(" mCT=" + mCT);
        pw.println(" mSST=" + mSST);
        pw.println(" mPendingMMIs=" + mPendingMMIs);
        pw.println(" mIccPhoneBookIntManager=" + mIccPhoneBookIntManager);
        pw.println(" mImei=" + pii(mImei));
        pw.println(" mImeiSv=" + pii(mImeiSv));
        pw.println(" mVmNumber=" + pii(mVmNumber));
        pw.println(" mWakeLock=" + mWakeLock);
        pw.println(" isInEcm()=" + isInEcm());
        pw.println(" mEsn=" + pii(mEsn));
        pw.println(" mMeid=" + pii(mMeid));
        pw.println(" isCspPlmnEnabled()=" + isCspPlmnEnabled());
        pw.println(" mManualNetworkSelectionPlmn=" + mManualNetworkSelectionPlmn);
        pw.println(
                " mTelecomVoiceServiceStateOverride=" + mTelecomVoiceServiceStateOverride + "("
                        + ServiceState.rilServiceStateToString(mTelecomVoiceServiceStateOverride)
                        + ")");
        pw.println(" mUiccApplicationsEnabled=" + mUiccApplicationsEnabled);
        pw.flush();
        try {
            mCallWaitingController.dump(pw);
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        try {
            mCellBroadcastConfigTracker.dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
    }

    @Override
    public boolean setOperatorBrandOverride(String brand) {
        if (mUiccController == null) {
            return false;
        }

        UiccPort port = mUiccController.getUiccPort(getPhoneId());
        if (port == null) {
            return false;
        }

        boolean status = port.setOperatorBrandOverride(brand);

        // Refresh.
        if (status) {
            TelephonyManager.from(mContext).setSimOperatorNameForPhone(
                    getPhoneId(), mSST.getServiceProviderName());
            // TODO: check if pollState is need when set operator brand override.
            mSST.pollState();
        }
        return status;
    }

    /**
     * This allows a short number to be remapped to a test emergency number for testing how the
     * frameworks handles Emergency Callback Mode without actually calling an emergency number.
     *
     * This is not a full test and is not a substitute for testing real emergency
     * numbers but can be useful.
     *
     * To use this feature, first set a test emergency number using
     * adb shell cmd phone emergency-number-test-mode -a 1-555-555-1212
     *
     * and then set the system property ril.test.emergencynumber to a pair of
     * numbers separated by a colon. If the first number matches the number parameter
     * this routine returns the second number. Example:
     *
     * ril.test.emergencynumber=411:1-555-555-1212
     *
     * To test Dial 411 take call then hang up on MO device to enter ECM.
     *
     * @param dialString to test if it should be remapped
     * @return the same number or the remapped number.
     */
    private String checkForTestEmergencyNumber(String dialString) {
        String testEn = SystemProperties.get("ril.test.emergencynumber");
        if (!TextUtils.isEmpty(testEn)) {
            String[] values = testEn.split(":");
            logd("checkForTestEmergencyNumber: values.length=" + values.length);
            if (values.length == 2) {
                if (values[0].equals(PhoneNumberUtils.stripSeparators(dialString))) {
                    logd("checkForTestEmergencyNumber: remap " + dialString + " to " + values[1]);
                    dialString = values[1];
                }
            }
        }
        return dialString;
    }

    @Override
    @NonNull
    public String getOperatorNumeric() {
        String operatorNumeric = null;
        IccRecords r = mIccRecords.get();
        if (r != null) {
            operatorNumeric = r.getOperatorNumeric();
        }
        return TextUtils.emptyIfNull(operatorNumeric);
    }

    /**
     * @return The country ISO for the subscription associated with this phone.
     */
    public String getCountryIso() {
        int subId = getSubId();
        SubscriptionInfo subInfo = SubscriptionManager.from(getContext())
                .getActiveSubscriptionInfo(subId);
        if (subInfo == null || TextUtils.isEmpty(subInfo.getCountryIso())) {
            return null;
        }
        return subInfo.getCountryIso().toUpperCase(Locale.ROOT);
    }

    public void notifyEcbmTimerReset(Boolean flag) {
        mEcmTimerResetRegistrants.notifyResult(flag);
    }

    private static final int[] VOICE_PS_CALL_RADIO_TECHNOLOGY = {
            ServiceState.RIL_RADIO_TECHNOLOGY_LTE,
            ServiceState.RIL_RADIO_TECHNOLOGY_LTE_CA,
            ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN,
            ServiceState.RIL_RADIO_TECHNOLOGY_NR
    };

    /**
     * Calculates current RIL voice radio technology for CS calls.
     *
     * This function should only be used in {@link com.android.internal.telephony.GsmCdmaConnection}
     * to indicate current CS call radio technology.
     *
     * @return the RIL voice radio technology used for CS calls,
     *         see {@code RIL_RADIO_TECHNOLOGY_*} in {@link android.telephony.ServiceState}.
     */
    public @RilRadioTechnology int getCsCallRadioTech() {
        int calcVrat = ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN;
        if (mSST != null) {
            calcVrat = getCsCallRadioTech(mSST.mSS.getState(),
                    mSST.mSS.getRilVoiceRadioTechnology());
        }

        return calcVrat;
    }

    /**
     * Calculates current RIL voice radio technology for CS calls based on current voice
     * registration state and technology.
     *
     * Mark current RIL voice radio technology as unknow when any of below condtion is met:
     *  1) Current RIL voice registration state is not in-service.
     *  2) Current RIL voice radio technology is PS call technology, which means CSFB will
     *     happen later after call connection is established.
     *     It is inappropriate to notify upper layer the PS call technology while current call
     *     is CS call, so before CSFB happens, mark voice radio technology as unknow.
     *     After CSFB happens, {@link #onVoiceRegStateOrRatChanged} will update voice call radio
     *     technology with correct value.
     *
     * @param vrs the voice registration state
     * @param vrat the RIL voice radio technology
     *
     * @return the RIL voice radio technology used for CS calls,
     *         see {@code RIL_RADIO_TECHNOLOGY_*} in {@link android.telephony.ServiceState}.
     */
    private @RilRadioTechnology int getCsCallRadioTech(int vrs, int vrat) {
        logd("getCsCallRadioTech, current vrs=" + vrs + ", vrat=" + vrat);
        int calcVrat = vrat;
        if (vrs != ServiceState.STATE_IN_SERVICE
                || ArrayUtils.contains(VOICE_PS_CALL_RADIO_TECHNOLOGY, vrat)) {
            calcVrat = ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN;
        }

        logd("getCsCallRadioTech, result calcVrat=" + calcVrat);
        return calcVrat;
    }

    /**
     * Handler of RIL Voice Radio Technology changed event.
     */
    private void onVoiceRegStateOrRatChanged(int vrs, int vrat) {
        if (!hasCalling()) return;
        logd("onVoiceRegStateOrRatChanged");
        mCT.dispatchCsCallRadioTech(getCsCallRadioTech(vrs, vrat));
    }

    /**
     * Registration point for Ecm timer reset
     *
     * @param h handler to notify
     * @param what User-defined message code
     * @param obj placed in Message.obj
     */
    @Override
    public void registerForEcmTimerReset(Handler h, int what, Object obj) {
        mEcmTimerResetRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForEcmTimerReset(Handler h) {
        mEcmTimerResetRegistrants.remove(h);
    }

    @Override
    public void registerForVolteSilentRedial(Handler h, int what, Object obj) {
        mVolteSilentRedialRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForVolteSilentRedial(Handler h) {
        mVolteSilentRedialRegistrants.remove(h);
    }

    public void notifyVolteSilentRedial(String dialString, int causeCode) {
        logd("notifyVolteSilentRedial: dialString=" + dialString + " causeCode=" + causeCode);
        AsyncResult ar = new AsyncResult(null,
                new SilentRedialParam(dialString, causeCode, mDialArgs), null);
        mVolteSilentRedialRegistrants.notifyRegistrants(ar);
    }

    /** {@inheritDoc} */
    @Override
    public void registerForEmergencyDomainSelected(
            @NonNull Handler h, int what, @Nullable Object obj) {
        mEmergencyDomainSelectedRegistrants.addUnique(h, what, obj);
    }

    /** {@inheritDoc} */
    @Override
    public void unregisterForEmergencyDomainSelected(@NonNull Handler h) {
        mEmergencyDomainSelectedRegistrants.remove(h);
    }

    /** {@inheritDoc} */
    @Override
    public void notifyEmergencyDomainSelected(@TransportType int transportType) {
        logd("notifyEmergencyDomainSelected transportType=" + transportType);
        mEmergencyDomainSelectedRegistrants.notifyRegistrants(
                new AsyncResult(null, transportType, null));
    }

    /**
     * Sets the SIM voice message waiting indicator records.
     * @param line GSM Subscriber Profile Number, one-based. Only '1' is supported
     * @param countWaiting The number of messages waiting, if known. Use
     *                     -1 to indicate that an unknown number of
     *                      messages are waiting
     */
    @Override
    public void setVoiceMessageWaiting(int line, int countWaiting) {
        IccRecords r = mIccRecords.get();
        if (r != null) {
            r.setVoiceMessageWaiting(line, countWaiting);
        } else {
            logd("SIM Records not found, MWI not updated");
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void logd(String s) {
        Rlog.d(LOG_TAG, "[" + mPhoneId + "] " + s);
    }

    private void logi(String s) {
        Rlog.i(LOG_TAG, "[" + mPhoneId + "] " + s);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void loge(String s) {
        Rlog.e(LOG_TAG, "[" + mPhoneId + "] " + s);
    }

    private static String pii(String s) {
        return Rlog.pii(LOG_TAG, s);
    }

    @Override
    public boolean isUtEnabled() {
        Phone imsPhone = mImsPhone;
        if (imsPhone != null) {
            return imsPhone.isUtEnabled();
        } else {
            logd("isUtEnabled: called for GsmCdma");
            return false;
        }
    }

    public String getDtmfToneDelayKey() {
        return CarrierConfigManager.KEY_GSM_DTMF_TONE_DELAY_INT;
    }

    @VisibleForTesting
    public PowerManager.WakeLock getWakeLock() {
        return mWakeLock;
    }

    private void updateTtyMode(int ttyMode) {
        logi(String.format("updateTtyMode ttyMode=%d", ttyMode));
        setTTYMode(ttyModeToPhoneMode(ttyMode), null);
    }
    private void updateUiTtyMode(int ttyMode) {
        logi(String.format("updateUiTtyMode ttyMode=%d", ttyMode));
        setUiTTYMode(ttyModeToPhoneMode(ttyMode), null);
    }

    /**
     * Given a TTY mode, convert to a Telephony mode equivalent.
     * @param ttyMode TTY mode.
     * @return Telephony phone TTY mode.
     */
    private static int ttyModeToPhoneMode(int ttyMode) {
        switch (ttyMode) {
            // AT command only has 0 and 1, so mapping VCO
            // and HCO to FULL
            case TelephonyManager.TTY_MODE_FULL:
            case TelephonyManager.TTY_MODE_VCO:
            case TelephonyManager.TTY_MODE_HCO:
                return Phone.TTY_MODE_FULL;
            default:
                return Phone.TTY_MODE_OFF;
        }
    }

    /**
     * Load the current TTY mode in GsmCdmaPhone based on Telecom and UI settings.
     */
    private void loadTtyMode() {
        if (!hasCalling()) return;

        int ttyMode = TelephonyManager.TTY_MODE_OFF;
        TelephonyManager telephonyManager = mContext.getSystemService(TelephonyManager.class);
        if (telephonyManager != null) {
            ttyMode = telephonyManager.getCurrentTtyMode();
        }
        updateTtyMode(ttyMode);
        //Get preferred TTY mode from settings as UI Tty mode is always user preferred Tty mode.
        ttyMode = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.PREFERRED_TTY_MODE, TelephonyManager.TTY_MODE_OFF);
        updateUiTtyMode(ttyMode);
    }

    private void reapplyUiccAppsEnablementIfNeeded(int retries) {
        UiccSlot slot = mUiccController.getUiccSlotForPhone(mPhoneId);

        // If no card is present or we don't have mUiccApplicationsEnabled yet, do nothing.
        if (slot == null || slot.getCardState() != IccCardStatus.CardState.CARDSTATE_PRESENT
                || mUiccApplicationsEnabled == null) {
            loge("reapplyUiccAppsEnablementIfNeeded: slot state="
                    + (slot != null ? slot.getCardState() : null));
            return;
        }

        // Due to timing issue, sometimes UiccPort is coming null, so don't use UiccPort object
        // to retrieve the iccId here. Instead, depend on the UiccSlot API.
        String iccId = slot.getIccId(slot.getPortIndexFromPhoneId(mPhoneId));
        if (iccId == null) {
            loge("reapplyUiccAppsEnablementIfNeeded iccId is null, phoneId: " + mPhoneId
                    + " portIndex: " + slot.getPortIndexFromPhoneId(mPhoneId));
            return;
        }

        SubscriptionInfo info = mSubscriptionManagerService
                .getAllSubInfoList(mContext.getOpPackageName(), mContext.getAttributionTag())
                .stream()
                .filter(subInfo -> subInfo.getIccId().equals(IccUtils.stripTrailingFs(iccId)))
                .findFirst()
                .orElse(null);

        logd("reapplyUiccAppsEnablementIfNeeded: retries=" + retries + ", subInfo=" + info);

        // If info is null, it could be a new subscription. By default we enable it.
        boolean expectedValue = info == null || info.areUiccApplicationsEnabled();

        // If for any reason current state is different from configured state, re-apply the
        // configured state.
        if (expectedValue != mUiccApplicationsEnabled) {
            mCi.enableUiccApplications(expectedValue, Message.obtain(
                    this, EVENT_REAPPLY_UICC_APPS_ENABLEMENT_DONE,
                    new Pair<>(expectedValue, retries)));
        }
    }

    // Enable or disable uicc applications.
    @Override
    public void enableUiccApplications(boolean enable, Message onCompleteMessage) {
        // First check if card is present. Otherwise mUiccApplicationsDisabled doesn't make
        // any sense.
        UiccSlot slot = mUiccController.getUiccSlotForPhone(mPhoneId);
        if (slot == null || slot.getCardState() != IccCardStatus.CardState.CARDSTATE_PRESENT) {
            if (onCompleteMessage != null) {
                AsyncResult.forMessage(onCompleteMessage, null,
                        new IllegalStateException("No SIM card is present"));
                onCompleteMessage.sendToTarget();
            }
            return;
        }

        mCi.enableUiccApplications(enable, onCompleteMessage);
    }

    /**
     * Whether disabling a physical subscription is supported or not.
     */
    @Override
    public boolean canDisablePhysicalSubscription() {
        return mCi.canToggleUiccApplicationsEnablement();
    }

    @Override
    public @NonNull List<String> getEquivalentHomePlmns() {
        IccRecords r = mIccRecords.get();
        if (r != null && r.getEhplmns() != null) {
            return Arrays.asList(r.getEhplmns());
        }
        return Collections.emptyList();
    }

    /**
     * @return Currently bound data service package names.
     */
    public @NonNull List<String> getDataServicePackages() {
        return getDataNetworkController().getDataServicePackages();
    }

    private void updateNrSettingsAfterCarrierConfigChanged(@NonNull PersistableBundle config) {
        int[] nrAvailabilities = config.getIntArray(
                CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY);
        mIsCarrierNrSupported = !ArrayUtils.isEmpty(nrAvailabilities);
    }

    private void updateVoNrSettings(@NonNull PersistableBundle config) {
        if (!CarrierConfigManager.isConfigForIdentifiedCarrier(config)
                || getIccCard().getState() != IccCardConstants.State.LOADED) {
            return;
        }

        boolean mIsVonrEnabledByCarrier =
                config.getBoolean(CarrierConfigManager.KEY_VONR_ENABLED_BOOL);
        boolean mDefaultVonr =
                config.getBoolean(CarrierConfigManager.KEY_VONR_ON_BY_DEFAULT_BOOL);

        int setting = -1;
        SubscriptionInfoInternal subInfo = mSubscriptionManagerService
                .getSubscriptionInfoInternal(getSubId());
        if (subInfo != null) {
            setting = subInfo.getNrAdvancedCallingEnabled();
        }

        logd("VoNR setting from telephony.db:"
                + setting
                + " ,vonr_enabled_bool:"
                + mIsVonrEnabledByCarrier
                + " ,vonr_on_by_default_bool:"
                + mDefaultVonr);

        boolean enbleVonr = mIsVonrEnabledByCarrier
                && (setting == 1 || (setting == -1 && mDefaultVonr));
        mCi.setVoNrEnabled(enbleVonr, obtainMessage(EVENT_SET_VONR_ENABLED_DONE), null);
        super.setAllowedImsServicesForAny(ImsRegistrationImplBase.REGISTRATION_TECH_NR, enbleVonr);
    }

    private void updateDefaultEnable2gSettings(@NonNull PersistableBundle config) {
        if (!SubscriptionManager.isValidSubscriptionId(getSubId())) {
            logd("updateDefaultEnable2gSettings subId is invalid subId:" + getSubId());
            return;
        }

        if (RadioInterfaceCapabilityController.getInstance().getCapabilities().contains(
                TelephonyManager.CAPABILITY_USES_ALLOWED_NETWORK_TYPES_BITMASK)) {
            // Get the value for whether 2G is disabled by the carrier from carrier configuration.
            boolean is2gProtectionDefaultEnabled = config.getBoolean(
                    CarrierConfigManager.KEY_CARRIER_DEFAULT_2G_PROTECTION_ENABLED_BOOL);
            if (is2gProtectionDefaultEnabled && !is2gProtectionDefaultUpdated()) {
                // This code will be executed if the carrier has disabled 2G and it is the first
                // time, as we are validating if 2g disabled by carrier previously from
                // SharedPreferences 'is2gProtectionDefaultUpdated()'.
                // While rebooting, multiple EVENT_CARRIER_CONFIG_CHANGED events are received.
                // To handle this race condition, SharedPreferences is updated before updating the
                // network allowed type.
                set2gProtectionDefaultUpdated(true);
                long currentlyAllowedNetworkTypes = getAllowedNetworkTypes(
                        TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_ENABLE_2G);

                if ((currentlyAllowedNetworkTypes & TelephonyManager.NETWORK_CLASS_BITMASK_2G)
                        != 0) {
                    disable2gNetworkType(currentlyAllowedNetworkTypes);
                }
            }
        }
    }

    private void disable2gNetworkType(long currentlyAllowedNetworkTypes) {
        setAllowedNetworkTypes(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_ENABLE_2G,
                currentlyAllowedNetworkTypes & ~TelephonyManager.NETWORK_CLASS_BITMASK_2G,
                obtainMessage(EVENT_SET_ALLOWED_NETWORK_TYPES_FOR_2G_DISABLED_DONE));
        logi("2G is disabled by carrier.");
    }

    private void sendNotification2gDisabledByCarrier() {
        String componentString = getContext().getResources().getString(
                com.android.internal.R.string.config_network_change_notification);
        ComponentName componentName = ComponentName.unflattenFromString(componentString);
        if (componentName != null) {
            final Intent intent = new Intent();
            intent.setAction(ACTION_2G_DISABLED_BY_CARRIER);
            intent.setComponent(componentName);
            intent.putExtra(EXTRA_SUBSCRIPTION_ID, getSubId());
            if (mContext.getPackageManager().queryBroadcastReceivers(intent, 0).size() > 0) {
                mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            }
        }
    }

    private boolean is2gProtectionDefaultUpdated() {
        int subId = getSubId();
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
            return sp.getBoolean(PREF_KEY_HAS_APPLIED_2G_PROTECTION_DEFAULT + subId, false);
        }
        return false;
    }

    private void set2gProtectionDefaultUpdated(boolean is2gProtectionDefaultUpdated) {
        int subId = getSubId();
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean(PREF_KEY_HAS_APPLIED_2G_PROTECTION_DEFAULT + subId,
                    is2gProtectionDefaultUpdated);
            editor.apply();
        }
    }

    /**
     * Determines if IMS is enabled for call.
     *
     * @return {@code true} if IMS calling is enabled.
     */
    public boolean isImsUseEnabled() {
        ImsManager imsManager = mImsManagerFactory.create(mContext, mPhoneId);
        boolean imsUseEnabled = ((imsManager.isVolteEnabledByPlatform()
                && imsManager.isEnhanced4gLteModeSettingEnabledByUser())
                || (imsManager.isWfcEnabledByPlatform() && imsManager.isWfcEnabledByUser())
                && imsManager.isNonTtyOrTtyOnVolteEnabled());
        return imsUseEnabled;
    }

    @Override
    public InboundSmsHandler getInboundSmsHandler(boolean is3gpp2) {
        return mIccSmsInterfaceManager.getInboundSmsHandler(is3gpp2);
    }

    /**
     * Return current cell broadcast ranges.
     */
    public List<CellBroadcastIdRange> getCellBroadcastIdRanges() {
        return mCellBroadcastConfigTracker.getCellBroadcastIdRanges();
    }

    /**
     * Set reception of cell broadcast messages with the list of the given ranges.
     */
    @Override
    public void setCellBroadcastIdRanges(
            @NonNull List<CellBroadcastIdRange> ranges, Consumer<Integer> callback) {
        mCellBroadcastConfigTracker.setCellBroadcastIdRanges(ranges, callback);
    }

    /**
     * The following function checks if supplementary service request is blocked due to FDN.
     * @param requestType request type associated with the supplementary service
     * @param serviceType supplementary service type
     * @return {@code true} if request is blocked due to FDN.
     */
    private boolean isRequestBlockedByFDN(SsData.RequestType requestType,
            SsData.ServiceType serviceType) {
        ArrayList<String> controlStrings = GsmMmiCode.getControlStrings(requestType, serviceType);
        return FdnUtils.isSuppServiceRequestBlockedByFdn(mPhoneId, controlStrings, getCountryIso());
    }

    @Override
    public void handleNullCipherEnabledChange() {
        if (!DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_CELLULAR_SECURITY,
                TelephonyManager.PROPERTY_ENABLE_NULL_CIPHER_TOGGLE, true)) {
            logi("Not handling null cipher update. Feature disabled by DeviceConfig.");
            return;
        }
        mCi.setNullCipherAndIntegrityEnabled(
                getNullCipherAndIntegrityEnabledPreference(),
                obtainMessage(EVENT_SET_NULL_CIPHER_AND_INTEGRITY_DONE));
    }

    @Override
    public void handleIdentifierDisclosureNotificationPreferenceChange() {
        boolean prefEnabled = getIdentifierDisclosureNotificationsPreferenceEnabled();

        // The notifier is tied to handling unsolicited updates from the modem, not the
        // enable/disable API, so we only toggle the enable state if the unsol events feature
        // flag is enabled.
        if (prefEnabled) {
            mIdentifierDisclosureNotifier.enable(mContext);
        } else {
            mIdentifierDisclosureNotifier.disable(mContext);
        }

        mCi.setCellularIdentifierTransparencyEnabled(prefEnabled,
                obtainMessage(EVENT_SET_IDENTIFIER_DISCLOSURE_ENABLED_DONE));
    }

    @Override
    public void handleNullCipherNotificationPreferenceChanged() {
        boolean prefEnabled = getNullCipherNotificationsPreferenceEnabled();

        // The notifier is tied to handling unsolicited updates from the modem, not the
        // enable/disable API.
        if (prefEnabled) {
            mNullCipherNotifier.enable(mContext);
        } else {
            mNullCipherNotifier.disable(mContext);
        }

        mCi.setSecurityAlgorithmsUpdatedEnabled(prefEnabled,
                obtainMessage(EVENT_SET_SECURITY_ALGORITHMS_UPDATED_ENABLED_DONE));
    }

    /**
     * Update the phoneId -> subId mapping of the null cipher notifier.
     */
    @VisibleForTesting
    public void updateNullCipherNotifier() {
        SubscriptionInfoInternal subInfo = mSubscriptionManagerService
                .getSubscriptionInfoInternal(getSubId());
        boolean active = false;
        if (subInfo != null) {
            active = subInfo.isActive();
        }
        mNullCipherNotifier.setSubscriptionMapping(mContext, getPhoneId(),
                active ? subInfo.getSubscriptionId() : -1);
    }

    @Override
    public boolean isNullCipherAndIntegritySupported() {
        return mIsNullCipherAndIntegritySupported;
    }

    @Override
    public boolean isIdentifierDisclosureTransparencySupported() {
        return mIsIdentifierDisclosureTransparencySupported;
    }

    @Override
    public boolean isNullCipherNotificationSupported() {
        return mIsNullCipherNotificationSupported;
    }

    @Override
    public void refreshSafetySources(String refreshBroadcastId) {
        post(() -> mSafetySource.refresh(mContext, refreshBroadcastId));
    }

    /**
     * @return The sms dispatchers controller
     */
    @Override
    @Nullable
    public SmsDispatchersController getSmsDispatchersController() {
        return mIccSmsInterfaceManager.mDispatchersController;
    }
}
