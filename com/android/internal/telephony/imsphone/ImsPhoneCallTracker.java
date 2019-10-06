/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.telephony.imsphone;

import static com.android.internal.telephony.Phone.CS_FALLBACK;

import android.annotation.NonNull;
import android.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.NetworkStats;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.telecom.ConferenceParticipant;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.CallQuality;
import android.telephony.CarrierConfigManager;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsStreamMediaProfile;
import android.telephony.ims.ImsSuppServiceNotification;
import android.telephony.ims.ProvisioningManager;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.ims.ImsCall;
import com.android.ims.ImsConfig;
import com.android.ims.ImsConfigListener;
import com.android.ims.ImsEcbm;
import com.android.ims.ImsException;
import com.android.ims.ImsManager;
import com.android.ims.ImsMultiEndpoint;
import com.android.ims.ImsUtInterface;
import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsVideoCallProvider;
import com.android.ims.internal.ImsVideoCallProviderWrapper;
import com.android.ims.internal.VideoPauseTracker;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CallTracker;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.LocaleTracker;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneInternalInterface;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.dataconnection.DataEnabledSettings;
import com.android.internal.telephony.dataconnection.DataEnabledSettings.DataEnabledChangedReason;
import com.android.internal.telephony.gsm.SuppServiceNotification;
import com.android.internal.telephony.metrics.CallQualityMetrics;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.telephony.nano.TelephonyProto.ImsConnectionState;
import com.android.internal.telephony.nano.TelephonyProto.TelephonyCallSession;
import com.android.internal.telephony.nano.TelephonyProto.TelephonyCallSession.Event.ImsCommand;
import com.android.server.net.NetworkStatsService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * {@hide}
 */
public class ImsPhoneCallTracker extends CallTracker implements ImsPullCall {
    static final String LOG_TAG = "ImsPhoneCallTracker";
    static final String VERBOSE_STATE_TAG = "IPCTState";

    public interface PhoneStateListener {
        void onPhoneStateChanged(PhoneConstants.State oldState, PhoneConstants.State newState);
    }

    public interface SharedPreferenceProxy {
        SharedPreferences getDefaultSharedPreferences(Context context);
    }

    public interface PhoneNumberUtilsProxy {
        boolean isEmergencyNumber(String number);
    }

    private static final boolean DBG = true;

    // When true, dumps the state of ImsPhoneCallTracker after changes to foreground and background
    // calls.  This is helpful for debugging.  It is also possible to enable this at runtime by
    // setting the IPCTState log tag to VERBOSE.
    private static final boolean FORCE_VERBOSE_STATE_LOGGING = false; /* stopship if true */
    private static final boolean VERBOSE_STATE_LOGGING = FORCE_VERBOSE_STATE_LOGGING ||
            Rlog.isLoggable(VERBOSE_STATE_TAG, Log.VERBOSE);

    private MmTelFeature.MmTelCapabilities mMmTelCapabilities =
            new MmTelFeature.MmTelCapabilities();

    private TelephonyMetrics mMetrics;
    private final Map<String, CallQualityMetrics> mCallQualityMetrics = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<CallQualityMetrics> mCallQualityMetricsHistory =
            new ConcurrentLinkedQueue<>();
    private boolean mCarrierConfigLoaded = false;

    private final MmTelFeatureListener mMmTelFeatureListener = new MmTelFeatureListener();
    private class MmTelFeatureListener extends MmTelFeature.Listener {
        @Override
        public void onIncomingCall(IImsCallSession c, Bundle extras) {
            if (DBG) log("onReceive : incoming call intent");

            if (mImsManager == null) return;

            try {
                // Network initiated USSD will be treated by mImsUssdListener
                boolean isUssd = extras.getBoolean(ImsManager.EXTRA_USSD, false);
                if (isUssd) {
                    if (DBG) log("onReceive : USSD");
                    mUssdSession = mImsManager.takeCall(c, extras, mImsUssdListener);
                    if (mUssdSession != null) {
                        mUssdSession.accept(ImsCallProfile.CALL_TYPE_VOICE);
                    }
                    return;
                }

                boolean isUnknown = extras.getBoolean(ImsManager.EXTRA_IS_UNKNOWN_CALL, false);
                if (DBG) {
                    log("onReceive : isUnknown = " + isUnknown
                            + " fg = " + mForegroundCall.getState()
                            + " bg = " + mBackgroundCall.getState());
                }

                // Normal MT/Unknown call
                ImsCall imsCall = mImsManager.takeCall(c, extras, mImsCallListener);
                ImsPhoneConnection conn = new ImsPhoneConnection(mPhone, imsCall,
                        ImsPhoneCallTracker.this,
                        (isUnknown ? mForegroundCall : mRingingCall), isUnknown);

                // If there is an active call.
                if (mForegroundCall.hasConnections()) {
                    ImsCall activeCall = mForegroundCall.getFirstConnection().getImsCall();
                    if (activeCall != null && imsCall != null) {
                        // activeCall could be null if the foreground call is in a disconnected
                        // state.  If either of the calls is null there is no need to check if
                        // one will be disconnected on answer.
                        boolean answeringWillDisconnect =
                                shouldDisconnectActiveCallOnAnswer(activeCall, imsCall);
                        conn.setActiveCallDisconnectedOnAnswer(answeringWillDisconnect);
                    }
                }
                conn.setAllowAddCallDuringVideoCall(mAllowAddCallDuringVideoCall);
                addConnection(conn);

                setVideoCallProvider(conn, imsCall);

                TelephonyMetrics.getInstance().writeOnImsCallReceive(mPhone.getPhoneId(),
                        imsCall.getSession());

                if (isUnknown) {
                    mPhone.notifyUnknownConnection(conn);
                } else {
                    if ((mForegroundCall.getState() != ImsPhoneCall.State.IDLE)
                            || (mBackgroundCall.getState() != ImsPhoneCall.State.IDLE)) {
                        conn.update(imsCall, ImsPhoneCall.State.WAITING);
                    }

                    mPhone.notifyNewRingingConnection(conn);
                    mPhone.notifyIncomingRing();
                }

                updatePhoneState();
                mPhone.notifyPreciseCallStateChanged();
            } catch (ImsException e) {
                loge("onReceive : exception " + e);
            } catch (RemoteException e) {
            }
        }

        @Override
        public void onVoiceMessageCountUpdate(int count) {
            if (mPhone != null && mPhone.mDefaultPhone != null) {
                if (DBG) log("onVoiceMessageCountChanged :: count=" + count);
                mPhone.mDefaultPhone.setVoiceMessageCount(count);
            } else {
                loge("onVoiceMessageCountUpdate: null phone");
            }
        }
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED)) {
                int subId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                if (subId == mPhone.getSubId()) {
                    cacheCarrierConfiguration(subId);
                    log("onReceive : Updating mAllowEmergencyVideoCalls = " +
                            mAllowEmergencyVideoCalls);
                }
            } else if (TelecomManager.ACTION_CHANGE_DEFAULT_DIALER.equals(intent.getAction())) {
                mDefaultDialerUid.set(getPackageUid(context, intent.getStringExtra(
                        TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME)));
            }
        }
    };

    /**
     * Tracks whether we are currently monitoring network connectivity for the purpose of warning
     * the user of an inability to handover from LTE to WIFI for video calls.
     */
    private boolean mIsMonitoringConnectivity = false;

    /**
     * Network callback used to schedule the handover check when a wireless network connects.
     */
    private ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Rlog.i(LOG_TAG, "Network available: " + network);
                    scheduleHandoverCheck();
                }
            };

    //***** Constants

    static final int MAX_CONNECTIONS = 7;
    static final int MAX_CONNECTIONS_PER_CALL = 5;

    // Max number of calls we will keep call quality history for (the history is saved in-memory and
    // included in bug reports).
    private static final int MAX_CALL_QUALITY_HISTORY = 10;

    private static final int EVENT_HANGUP_PENDINGMO = 18;
    private static final int EVENT_DIAL_PENDINGMO = 20;
    private static final int EVENT_EXIT_ECBM_BEFORE_PENDINGMO = 21;
    private static final int EVENT_VT_DATA_USAGE_UPDATE = 22;
    private static final int EVENT_DATA_ENABLED_CHANGED = 23;
    private static final int EVENT_CHECK_FOR_WIFI_HANDOVER = 25;
    private static final int EVENT_ON_FEATURE_CAPABILITY_CHANGED = 26;
    private static final int EVENT_SUPP_SERVICE_INDICATION = 27;
    private static final int EVENT_REDIAL_WIFI_E911_CALL = 28;
    private static final int EVENT_REDIAL_WIFI_E911_TIMEOUT = 29;
    private static final int EVENT_ANSWER_WAITING_CALL = 30;
    private static final int EVENT_RESUME_NOW_FOREGROUND_CALL = 31;

    private static final int TIMEOUT_HANGUP_PENDINGMO = 500;

    private static final int HANDOVER_TO_WIFI_TIMEOUT_MS = 60000; // ms

    private static final int TIMEOUT_REDIAL_WIFI_E911_MS = 10000;

    private static final int TIMEOUT_PARTICIPANT_CONNECT_TIME_CACHE_MS = 60000; //ms

    // Following values are for mHoldSwitchingState
    private enum HoldSwapState {
        // Not in the middle of a hold/swap operation
        INACTIVE,
        // Pending a single call getting held
        PENDING_SINGLE_CALL_HOLD,
        // Pending a single call getting unheld
        PENDING_SINGLE_CALL_UNHOLD,
        // Pending swapping a active and a held call
        SWAPPING_ACTIVE_AND_HELD,
        // Pending holding a call to answer a call-waiting call
        HOLDING_TO_ANSWER_INCOMING,
        // Pending resuming the foreground call after some kind of failure
        PENDING_RESUME_FOREGROUND_AFTER_FAILURE,
        // Pending holding a call to dial another outgoing call
        HOLDING_TO_DIAL_OUTGOING,
    }

    //***** Instance Variables
    @UnsupportedAppUsage
    private ArrayList<ImsPhoneConnection> mConnections = new ArrayList<ImsPhoneConnection>();
    private RegistrantList mVoiceCallEndedRegistrants = new RegistrantList();
    private RegistrantList mVoiceCallStartedRegistrants = new RegistrantList();

    @UnsupportedAppUsage
    public ImsPhoneCall mRingingCall = new ImsPhoneCall(this, ImsPhoneCall.CONTEXT_RINGING);
    @UnsupportedAppUsage
    public ImsPhoneCall mForegroundCall = new ImsPhoneCall(this,
            ImsPhoneCall.CONTEXT_FOREGROUND);
    @UnsupportedAppUsage
    public ImsPhoneCall mBackgroundCall = new ImsPhoneCall(this,
            ImsPhoneCall.CONTEXT_BACKGROUND);
    @UnsupportedAppUsage
    public ImsPhoneCall mHandoverCall = new ImsPhoneCall(this, ImsPhoneCall.CONTEXT_HANDOVER);

    // Hold aggregated video call data usage for each video call since boot.
    // The ImsCall's call id is the key of the map.
    private final HashMap<Integer, Long> mVtDataUsageMap = new HashMap<>();
    private final Map<String, CacheEntry> mPhoneNumAndConnTime = new ConcurrentHashMap<>();
    private final Queue<CacheEntry> mUnknownPeerConnTime = new LinkedBlockingQueue<>();

    private static class CacheEntry {
        private long mCachedTime;
        private long mConnectTime;
        private long mConnectElapsedTime;
        /**
         * The direction of the call;
         * {@link android.telecom.Call.Details#DIRECTION_INCOMING} for incoming calls, or
         * {@link android.telecom.Call.Details#DIRECTION_OUTGOING} for outgoing calls.
         */
        private int mCallDirection;

        CacheEntry(long cachedTime, long connectTime, long connectElapsedTime, int callDirection) {
            mCachedTime = cachedTime;
            mConnectTime = connectTime;
            mConnectElapsedTime = connectElapsedTime;
            mCallDirection = callDirection;
        }
    }

    private volatile NetworkStats mVtDataUsageSnapshot = null;
    private volatile NetworkStats mVtDataUsageUidSnapshot = null;

    private final AtomicInteger mDefaultDialerUid = new AtomicInteger(NetworkStats.UID_ALL);

    @UnsupportedAppUsage
    private ImsPhoneConnection mPendingMO;
    private int mClirMode = CommandsInterface.CLIR_DEFAULT;
    @UnsupportedAppUsage
    private Object mSyncHold = new Object();

    private ImsCall mUssdSession = null;
    @UnsupportedAppUsage
    private Message mPendingUssd = null;

    @UnsupportedAppUsage
    ImsPhone mPhone;

    private boolean mDesiredMute = false;    // false = mute off
    @UnsupportedAppUsage
    private boolean mOnHoldToneStarted = false;
    @UnsupportedAppUsage
    private int mOnHoldToneId = -1;

    private PhoneConstants.State mState = PhoneConstants.State.IDLE;

    private ImsManager mImsManager;
    private ImsUtInterface mUtInterface;

    private Call.SrvccState mSrvccState = Call.SrvccState.NONE;

    private boolean mIsInEmergencyCall = false;
    private boolean mIsDataEnabled = false;

    private int pendingCallClirMode;
    private int mPendingCallVideoState;
    private Bundle mPendingIntentExtras;
    private boolean pendingCallInEcm = false;
    @UnsupportedAppUsage
    private boolean mSwitchingFgAndBgCalls = false;
    private ImsCall mCallExpectedToResume = null;
    @UnsupportedAppUsage
    private boolean mAllowEmergencyVideoCalls = false;
    private boolean mIgnoreDataEnabledChangedForVideoCalls = false;
    private boolean mIsViLteDataMetered = false;
    private boolean mAlwaysPlayRemoteHoldTone = false;
    private boolean mAutoRetryFailedWifiEmergencyCall = false;
    // Tracks the state of our background/foreground calls while a call hold/swap operation is
    // in progress. Values listed above.
    private HoldSwapState mHoldSwitchingState = HoldSwapState.INACTIVE;

    private String mLastDialString = null;
    private PhoneInternalInterface.DialArgs mLastDialArgs = null;
    /**
     * Listeners to changes in the phone state.  Intended for use by other interested IMS components
     * without the need to register a full blown {@link android.telephony.PhoneStateListener}.
     */
    private List<PhoneStateListener> mPhoneStateListeners = new ArrayList<>();

    /**
     * Carrier configuration option which determines if video calls which have been downgraded to an
     * audio call should be treated as if they are still video calls.
     */
    private boolean mTreatDowngradedVideoCallsAsVideoCalls = false;

    /**
     * Carrier configuration option which determines if an ongoing video call over wifi should be
     * dropped when an audio call is answered.
     */
    private boolean mDropVideoCallWhenAnsweringAudioCall = false;

    /**
     * Carrier configuration option which determines whether adding a call during a video call
     * should be allowed.
     */
    private boolean mAllowAddCallDuringVideoCall = true;

    /**
     * Carrier configuration option which determines whether to notify the connection if a handover
     * to wifi fails.
     */
    private boolean mNotifyVtHandoverToWifiFail = false;

    /**
     * Carrier configuration option which determines whether the carrier supports downgrading a
     * TX/RX/TX-RX video call directly to an audio-only call.
     */
    private boolean mSupportDowngradeVtToAudio = false;

    /**
     * Carrier configuration option which determines whether the carrier wants to inform the user
     * when a video call is handed over from WIFI to LTE.
     * See {@link CarrierConfigManager#KEY_NOTIFY_HANDOVER_VIDEO_FROM_WIFI_TO_LTE_BOOL} for more
     * information.
     */
    private boolean mNotifyHandoverVideoFromWifiToLTE = false;

    /**
     * Carrier configuration option which determines whether the carrier wants to inform the user
     * when a video call is handed over from LTE to WIFI.
     * See {@link CarrierConfigManager#KEY_NOTIFY_HANDOVER_VIDEO_FROM_LTE_TO_WIFI_BOOL} for more
     * information.
     */
    private boolean mNotifyHandoverVideoFromLTEToWifi = false;

    /**
     * When {@code} false, indicates that no handover from LTE to WIFI has been attempted during the
     * start of the call.
     * When {@code true}, indicates that the start of call handover from LTE to WIFI has been
     * attempted (it may have succeeded or failed).
     */
    private boolean mHasAttemptedStartOfCallHandover = false;

    /**
     * Carrier configuration option which determines whether the carrier supports the
     * {@link VideoProfile#STATE_PAUSED} signalling.
     * See {@link CarrierConfigManager#KEY_SUPPORT_PAUSE_IMS_VIDEO_CALLS_BOOL} for more information.
     */
    private boolean mSupportPauseVideo = false;

    /**
     * Carrier configuration option which defines a mapping from pairs of
     * {@link ImsReasonInfo#getCode()} and {@link ImsReasonInfo#getExtraMessage()} values to a new
     * {@code ImsReasonInfo#CODE_*} value.
     *
     * See {@link CarrierConfigManager#KEY_IMS_REASONINFO_MAPPING_STRING_ARRAY}.
     */
    private Map<Pair<Integer, String>, Integer> mImsReasonCodeMap = new ArrayMap<>();


    /**
     * TODO: Remove this code; it is a workaround.
     * When {@code true}, forces {@link ImsManager#updateImsServiceConfig(boolean)} to
     * be called when an ongoing video call is disconnected.  In some cases, where video pause is
     * supported by the carrier, when {@link #onDataEnabledChanged(boolean, int)} reports that data
     * has been disabled we will pause the video rather than disconnecting the call.  When this
     * happens we need to prevent the IMS service config from being updated, as this will cause VT
     * to be disabled mid-call, resulting in an inability to un-pause the video.
     */
    private boolean mShouldUpdateImsConfigOnDisconnect = false;

    /**
     * Default implementation for retrieving shared preferences; uses the actual PreferencesManager.
     */
    private SharedPreferenceProxy mSharedPreferenceProxy = (Context context) -> {
        return PreferenceManager.getDefaultSharedPreferences(context);
    };

    /**
     * Default implementation for determining if a number is an emergency number.  Uses the real
     * PhoneNumberUtils.
     */
    private PhoneNumberUtilsProxy mPhoneNumberUtilsProxy = (String string) -> {
        return PhoneNumberUtils.isEmergencyNumber(string);
    };

    private final ImsManager.Connector mImsManagerConnector;

    //***** Events


    //***** Constructors
    public ImsPhoneCallTracker(ImsPhone phone) {
        this(phone, phone.getContext().getMainExecutor());
    }

    @VisibleForTesting
    public ImsPhoneCallTracker(ImsPhone phone, Executor executor) {
        this.mPhone = phone;

        mMetrics = TelephonyMetrics.getInstance();

        IntentFilter intentfilter = new IntentFilter();
        intentfilter.addAction(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED);
        intentfilter.addAction(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
        mPhone.getContext().registerReceiver(mReceiver, intentfilter);
        cacheCarrierConfiguration(mPhone.getSubId());

        mPhone.getDefaultPhone().getDataEnabledSettings().registerForDataEnabledChanged(
                this, EVENT_DATA_ENABLED_CHANGED, null);

        final TelecomManager telecomManager =
                (TelecomManager) mPhone.getContext().getSystemService(Context.TELECOM_SERVICE);
        mDefaultDialerUid.set(
                getPackageUid(mPhone.getContext(), telecomManager.getDefaultDialerPackage()));

        long currentTime = SystemClock.elapsedRealtime();
        mVtDataUsageSnapshot = new NetworkStats(currentTime, 1);
        mVtDataUsageUidSnapshot = new NetworkStats(currentTime, 1);

        // Allow the executor to be specified for testing.
        mImsManagerConnector = new ImsManager.Connector(phone.getContext(), phone.getPhoneId(),
                new ImsManager.Connector.Listener() {
                    @Override
                    public void connectionReady(ImsManager manager) throws ImsException {
                        mImsManager = manager;
                        startListeningForCalls();
                    }

                    @Override
                    public void connectionUnavailable() {
                        stopListeningForCalls();
                    }
                }, executor);
        mImsManagerConnector.connect();
    }

    /**
     * Test-only method used to mock out access to the shared preferences through the
     * {@link PreferenceManager}.
     * @param sharedPreferenceProxy
     */
    @VisibleForTesting
    public void setSharedPreferenceProxy(SharedPreferenceProxy sharedPreferenceProxy) {
        mSharedPreferenceProxy = sharedPreferenceProxy;
    }

    /**
     * Test-only method used to mock out access to the phone number utils class.
     * @param phoneNumberUtilsProxy
     */
    @VisibleForTesting
    public void setPhoneNumberUtilsProxy(PhoneNumberUtilsProxy phoneNumberUtilsProxy) {
        mPhoneNumberUtilsProxy = phoneNumberUtilsProxy;
    }

    /**
     * Test-only method used to set the ImsService retry timeout.
     */
    @VisibleForTesting
    public void setRetryTimeout(ImsManager.Connector.RetryTimeout retryTimeout) {
        mImsManagerConnector.mRetryTimeout = retryTimeout;
    }

    private int getPackageUid(Context context, String pkg) {
        if (pkg == null) {
            return NetworkStats.UID_ALL;
        }

        // Initialize to UID_ALL so at least it can be counted to overall data usage if
        // the dialer's package uid is not available.
        int uid = NetworkStats.UID_ALL;
        try {
            uid = context.getPackageManager().getPackageUid(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            loge("Cannot find package uid. pkg = " + pkg);
        }
        return uid;
    }

    private void startListeningForCalls() throws ImsException {
        log("startListeningForCalls");
        mImsManager.open(mMmTelFeatureListener);
        mImsManager.addRegistrationCallback(mImsRegistrationCallback);
        mImsManager.addCapabilitiesCallback(mImsCapabilityCallback);

        mImsManager.setConfigListener(mImsConfigListener);

        mImsManager.getConfigInterface().addConfigCallback(mConfigCallback);

        // Get the ECBM interface and set IMSPhone's listener object for notifications
        getEcbmInterface().setEcbmStateListener(mPhone.getImsEcbmStateListener());
        if (mPhone.isInEcm()) {
            // Call exit ECBM which will invoke onECBMExited
            mPhone.exitEmergencyCallbackMode();
        }
        int mPreferredTtyMode = Settings.Secure.getInt(
                mPhone.getContext().getContentResolver(),
                Settings.Secure.PREFERRED_TTY_MODE,
                Phone.TTY_MODE_OFF);
        mImsManager.setUiTTYMode(mPhone.getContext(), mPreferredTtyMode, null);

        ImsMultiEndpoint multiEndpoint = getMultiEndpointInterface();
        if (multiEndpoint != null) {
            multiEndpoint.setExternalCallStateListener(
                    mPhone.getExternalCallTracker().getExternalCallStateListener());
        }

        //Set UT interface listener to receive UT indications.
        mUtInterface = getUtInterface();
        if (mUtInterface != null) {
            mUtInterface.registerForSuppServiceIndication(this,
                    EVENT_SUPP_SERVICE_INDICATION, null);
        }

        if (mCarrierConfigLoaded) {
            mImsManager.updateImsServiceConfig(true);
        }
        // For compatibility with apps that still use deprecated intent
        sendImsServiceStateIntent(ImsManager.ACTION_IMS_SERVICE_UP);
    }

    private void stopListeningForCalls() {
        log("stopListeningForCalls");
        resetImsCapabilities();
        // Only close on valid session.
        if (mImsManager != null) {
            try {
                mImsManager.getConfigInterface().removeConfigCallback(mConfigCallback.getBinder());
            } catch (ImsException e) {
                Log.w(LOG_TAG, "stopListeningForCalls: unable to remove config callback.");
            }
            mImsManager.close();
        }
        // For compatibility with apps that still use deprecated intent
        sendImsServiceStateIntent(ImsManager.ACTION_IMS_SERVICE_DOWN);
    }

    private void sendImsServiceStateIntent(String intentAction) {
        Intent intent = new Intent(intentAction);
        intent.putExtra(ImsManager.EXTRA_PHONE_ID, mPhone.getPhoneId());
        if (mPhone != null && mPhone.getContext() != null) {
            mPhone.getContext().sendBroadcast(intent);
        }
    }

    public void dispose() {
        if (DBG) log("dispose");
        mRingingCall.dispose();
        mBackgroundCall.dispose();
        mForegroundCall.dispose();
        mHandoverCall.dispose();

        clearDisconnected();
        if (mUtInterface != null) {
            mUtInterface.unregisterForSuppServiceIndication(this);
        }
        mPhone.getContext().unregisterReceiver(mReceiver);
        mPhone.getDefaultPhone().getDataEnabledSettings().unregisterForDataEnabledChanged(this);
        mImsManagerConnector.disconnect();
    }

    @Override
    protected void finalize() {
        log("ImsPhoneCallTracker finalized");
    }

    //***** Instance Methods

    //***** Public Methods
    @Override
    public void registerForVoiceCallStarted(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mVoiceCallStartedRegistrants.add(r);
    }

    @Override
    public void unregisterForVoiceCallStarted(Handler h) {
        mVoiceCallStartedRegistrants.remove(h);
    }

    @Override
    public void registerForVoiceCallEnded(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mVoiceCallEndedRegistrants.add(r);
    }

    @Override
    public void unregisterForVoiceCallEnded(Handler h) {
        mVoiceCallEndedRegistrants.remove(h);
    }

    public int getClirMode() {
        if (mSharedPreferenceProxy != null && mPhone.getDefaultPhone() != null) {
            SharedPreferences sp = mSharedPreferenceProxy.getDefaultSharedPreferences(
                    mPhone.getContext());
            return sp.getInt(Phone.CLIR_KEY + mPhone.getDefaultPhone().getPhoneId(),
                    CommandsInterface.CLIR_DEFAULT);
        } else {
            loge("dial; could not get default CLIR mode.");
            return CommandsInterface.CLIR_DEFAULT;
        }
    }

    @UnsupportedAppUsage
    public Connection dial(String dialString, int videoState, Bundle intentExtras) throws
            CallStateException {
        ImsPhone.ImsDialArgs dialArgs =  new ImsPhone.ImsDialArgs.Builder()
                .setIntentExtras(intentExtras)
                .setVideoState(videoState)
                .setClirMode(getClirMode())
                .build();
        return dial(dialString, dialArgs);
    }

    public synchronized Connection dial(String dialString, ImsPhone.ImsDialArgs dialArgs)
            throws CallStateException {
        boolean isPhoneInEcmMode = isPhoneInEcbMode();
        boolean isEmergencyNumber = mPhoneNumberUtilsProxy.isEmergencyNumber(dialString);

        if (!shouldNumberBePlacedOnIms(isEmergencyNumber, dialString)) {
            Rlog.i(LOG_TAG, "dial: shouldNumberBePlacedOnIms = false");
            throw new CallStateException(CS_FALLBACK);
        }

        int clirMode = dialArgs.clirMode;
        int videoState = dialArgs.videoState;

        if (DBG) log("dial clirMode=" + clirMode);
        if (isEmergencyNumber) {
            clirMode = CommandsInterface.CLIR_SUPPRESSION;
            if (DBG) log("dial emergency call, set clirModIe=" + clirMode);
        }

        // note that this triggers call state changed notif
        clearDisconnected();

        if (mImsManager == null) {
            throw new CallStateException("service not available");
        }

        // See if there are any issues which preclude placing a call; throw a CallStateException
        // if there is.
        checkForDialIssues();

        if (isPhoneInEcmMode && isEmergencyNumber) {
            handleEcmTimer(ImsPhone.CANCEL_ECM_TIMER);
        }

        // If the call is to an emergency number and the carrier does not support video emergency
        // calls, dial as an audio-only call.
        if (isEmergencyNumber && VideoProfile.isVideo(videoState) &&
                !mAllowEmergencyVideoCalls) {
            loge("dial: carrier does not support video emergency calls; downgrade to audio-only");
            videoState = VideoProfile.STATE_AUDIO_ONLY;
        }

        boolean holdBeforeDial = false;

        // The new call must be assigned to the foreground call.
        // That call must be idle, so place anything that's
        // there on hold
        if (mForegroundCall.getState() == ImsPhoneCall.State.ACTIVE) {
            if (mBackgroundCall.getState() != ImsPhoneCall.State.IDLE) {
                //we should have failed in checkForDialIssues above before we get here
                throw new CallStateException(CallStateException.ERROR_TOO_MANY_CALLS,
                        "Already too many ongoing calls.");
            }
            // foreground call is empty for the newly dialed connection
            holdBeforeDial = true;
            // Cache the video state for pending MO call.
            mPendingCallVideoState = videoState;
            mPendingIntentExtras = dialArgs.intentExtras;
            holdActiveCallForPendingMo();
        }

        ImsPhoneCall.State fgState = ImsPhoneCall.State.IDLE;
        ImsPhoneCall.State bgState = ImsPhoneCall.State.IDLE;

        mClirMode = clirMode;

        synchronized (mSyncHold) {
            if (holdBeforeDial) {
                fgState = mForegroundCall.getState();
                bgState = mBackgroundCall.getState();

                //holding foreground call failed
                if (fgState == ImsPhoneCall.State.ACTIVE) {
                    throw new CallStateException("cannot dial in current state");
                }

                //holding foreground call succeeded
                if (bgState == ImsPhoneCall.State.HOLDING) {
                    holdBeforeDial = false;
                }
            }

            mLastDialString = dialString;
            mLastDialArgs = dialArgs;
            mPendingMO = new ImsPhoneConnection(mPhone,
                    checkForTestEmergencyNumber(dialString), this, mForegroundCall,
                    isEmergencyNumber);
            if (isEmergencyNumber && dialArgs != null && dialArgs.intentExtras != null) {
                Rlog.i(LOG_TAG, "dial ims emergency dialer: " + dialArgs.intentExtras.getBoolean(
                        TelecomManager.EXTRA_IS_USER_INTENT_EMERGENCY_CALL));
                mPendingMO.setHasKnownUserIntentEmergency(dialArgs.intentExtras.getBoolean(
                        TelecomManager.EXTRA_IS_USER_INTENT_EMERGENCY_CALL));
            }
            mPendingMO.setVideoState(videoState);
            if (dialArgs.rttTextStream != null) {
                log("dial: setting RTT stream on mPendingMO");
                mPendingMO.setCurrentRttTextStream(dialArgs.rttTextStream);
            }
        }
        addConnection(mPendingMO);

        if (!holdBeforeDial) {
            if ((!isPhoneInEcmMode) || (isPhoneInEcmMode && isEmergencyNumber)) {
                dialInternal(mPendingMO, clirMode, videoState, dialArgs.intentExtras);
            } else {
                try {
                    getEcbmInterface().exitEmergencyCallbackMode();
                } catch (ImsException e) {
                    e.printStackTrace();
                    throw new CallStateException("service not available");
                }
                mPhone.setOnEcbModeExitResponse(this, EVENT_EXIT_ECM_RESPONSE_CDMA, null);
                pendingCallClirMode = clirMode;
                mPendingCallVideoState = videoState;
                pendingCallInEcm = true;
            }
        }

        updatePhoneState();
        mPhone.notifyPreciseCallStateChanged();

        return mPendingMO;
    }

    boolean isImsServiceReady() {
        if (mImsManager == null) {
            return false;
        }

        return mImsManager.isServiceReady();
    }

    private boolean shouldNumberBePlacedOnIms(boolean isEmergency, String number) {
        int processCallResult;
        try {
            if (mImsManager != null) {
                processCallResult = mImsManager.shouldProcessCall(isEmergency,
                        new String[]{number});
                Rlog.i(LOG_TAG, "shouldProcessCall: number: " + Rlog.pii(LOG_TAG, number)
                        + ", result: " + processCallResult);
            } else {
                Rlog.w(LOG_TAG, "ImsManager unavailable, shouldProcessCall returning false.");
                return false;
            }
        } catch (ImsException e) {
            Rlog.w(LOG_TAG, "ImsService unavailable, shouldProcessCall returning false.");
            return false;
        }
        switch(processCallResult) {
            case MmTelFeature.PROCESS_CALL_IMS: {
                // The ImsService wishes to place the call over IMS
                return true;
            }
            case MmTelFeature.PROCESS_CALL_CSFB: {
                Rlog.i(LOG_TAG, "shouldProcessCall: place over CSFB instead.");
                return false;
            }
            default: {
                Rlog.w(LOG_TAG, "shouldProcessCall returned unknown result.");
                return false;
            }
        }
    }

    /**
     * Caches frequently used carrier configuration items locally.
     *
     * @param subId The sub id.
     */
    private void cacheCarrierConfiguration(int subId) {
        CarrierConfigManager carrierConfigManager = (CarrierConfigManager)
                mPhone.getContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (carrierConfigManager == null
                || !SubscriptionController.getInstance().isActiveSubId(subId)) {
            loge("cacheCarrierConfiguration: No carrier config service found" + " "
                    + "or not active subId = " + subId);
            mCarrierConfigLoaded = false;
            return;
        }

        PersistableBundle carrierConfig = carrierConfigManager.getConfigForSubId(subId);
        if (carrierConfig == null) {
            loge("cacheCarrierConfiguration: Empty carrier config.");
            mCarrierConfigLoaded = false;
            return;
        }
        mCarrierConfigLoaded = true;

        updateCarrierConfigCache(carrierConfig);
    }

    /**
     * Updates the local carrier config cache from a bundle obtained from the carrier config
     * manager.  Also supports unit testing by injecting configuration at test time.
     * @param carrierConfig The config bundle.
     */
    @VisibleForTesting
    public void updateCarrierConfigCache(PersistableBundle carrierConfig) {
        mAllowEmergencyVideoCalls =
                carrierConfig.getBoolean(CarrierConfigManager.KEY_ALLOW_EMERGENCY_VIDEO_CALLS_BOOL);
        mTreatDowngradedVideoCallsAsVideoCalls =
                carrierConfig.getBoolean(
                        CarrierConfigManager.KEY_TREAT_DOWNGRADED_VIDEO_CALLS_AS_VIDEO_CALLS_BOOL);
        mDropVideoCallWhenAnsweringAudioCall =
                carrierConfig.getBoolean(
                        CarrierConfigManager.KEY_DROP_VIDEO_CALL_WHEN_ANSWERING_AUDIO_CALL_BOOL);
        mAllowAddCallDuringVideoCall =
                carrierConfig.getBoolean(
                        CarrierConfigManager.KEY_ALLOW_ADD_CALL_DURING_VIDEO_CALL_BOOL);
        mNotifyVtHandoverToWifiFail = carrierConfig.getBoolean(
                CarrierConfigManager.KEY_NOTIFY_VT_HANDOVER_TO_WIFI_FAILURE_BOOL);
        mSupportDowngradeVtToAudio = carrierConfig.getBoolean(
                CarrierConfigManager.KEY_SUPPORT_DOWNGRADE_VT_TO_AUDIO_BOOL);
        mNotifyHandoverVideoFromWifiToLTE = carrierConfig.getBoolean(
                CarrierConfigManager.KEY_NOTIFY_HANDOVER_VIDEO_FROM_WIFI_TO_LTE_BOOL);
        mNotifyHandoverVideoFromLTEToWifi = carrierConfig.getBoolean(
                CarrierConfigManager.KEY_NOTIFY_HANDOVER_VIDEO_FROM_LTE_TO_WIFI_BOOL);
        mIgnoreDataEnabledChangedForVideoCalls = carrierConfig.getBoolean(
                CarrierConfigManager.KEY_IGNORE_DATA_ENABLED_CHANGED_FOR_VIDEO_CALLS);
        mIsViLteDataMetered = carrierConfig.getBoolean(
                CarrierConfigManager.KEY_VILTE_DATA_IS_METERED_BOOL);
        mSupportPauseVideo = carrierConfig.getBoolean(
                CarrierConfigManager.KEY_SUPPORT_PAUSE_IMS_VIDEO_CALLS_BOOL);
        mAlwaysPlayRemoteHoldTone = carrierConfig.getBoolean(
                CarrierConfigManager.KEY_ALWAYS_PLAY_REMOTE_HOLD_TONE_BOOL);
        mAutoRetryFailedWifiEmergencyCall = carrierConfig.getBoolean(
                CarrierConfigManager.KEY_AUTO_RETRY_FAILED_WIFI_EMERGENCY_CALL);

        String[] mappings = carrierConfig
                .getStringArray(CarrierConfigManager.KEY_IMS_REASONINFO_MAPPING_STRING_ARRAY);
        if (mappings != null && mappings.length > 0) {
            for (String mapping : mappings) {
                String[] values = mapping.split(Pattern.quote("|"));
                if (values.length != 3) {
                    continue;
                }

                try {
                    Integer fromCode;
                    if (values[0].equals("*")) {
                        fromCode = null;
                    } else {
                        fromCode = Integer.parseInt(values[0]);
                    }
                    String message = values[1];
                    if (message == null) {
                        message = "";
                    }
                    int toCode = Integer.parseInt(values[2]);

                    addReasonCodeRemapping(fromCode, message, toCode);
                    log("Loaded ImsReasonInfo mapping : fromCode = " +
                            fromCode == null ? "any" : fromCode + " ; message = " +
                            message + " ; toCode = " + toCode);
                } catch (NumberFormatException nfe) {
                    loge("Invalid ImsReasonInfo mapping found: " + mapping);
                }
            }
        } else {
            log("No carrier ImsReasonInfo mappings defined.");
        }
    }

    @UnsupportedAppUsage
    private void handleEcmTimer(int action) {
        mPhone.handleTimerInEmergencyCallbackMode(action);
        switch (action) {
            case ImsPhone.CANCEL_ECM_TIMER:
                break;
            case ImsPhone.RESTART_ECM_TIMER:
                break;
            default:
                log("handleEcmTimer, unsupported action " + action);
        }
    }

    private void dialInternal(ImsPhoneConnection conn, int clirMode, int videoState,
            Bundle intentExtras) {

        if (conn == null) {
            return;
        }

        if (conn.getAddress()== null || conn.getAddress().length() == 0
                || conn.getAddress().indexOf(PhoneNumberUtils.WILD) >= 0) {
            // Phone number is invalid
            conn.setDisconnectCause(DisconnectCause.INVALID_NUMBER);
            sendEmptyMessageDelayed(EVENT_HANGUP_PENDINGMO, TIMEOUT_HANGUP_PENDINGMO);
            return;
        }

        // Always unmute when initiating a new call
        setMute(false);
        boolean isEmergencyCall = mPhoneNumberUtilsProxy.isEmergencyNumber(conn.getAddress());
        int serviceType = isEmergencyCall
                ? ImsCallProfile.SERVICE_TYPE_EMERGENCY : ImsCallProfile.SERVICE_TYPE_NORMAL;
        int callType = ImsCallProfile.getCallTypeFromVideoState(videoState);
        //TODO(vt): Is this sufficient?  At what point do we know the video state of the call?
        conn.setVideoState(videoState);

        try {
            String[] callees = new String[] { conn.getAddress() };
            ImsCallProfile profile = mImsManager.createCallProfile(serviceType, callType);
            profile.setCallExtraInt(ImsCallProfile.EXTRA_OIR, clirMode);

            if (isEmergencyCall) {
                // Set emergency call information in ImsCallProfile
                setEmergencyCallInfo(profile, conn);
            }

            // Translate call subject intent-extra from Telecom-specific extra key to the
            // ImsCallProfile key.
            if (intentExtras != null) {
                if (intentExtras.containsKey(android.telecom.TelecomManager.EXTRA_CALL_SUBJECT)) {
                    intentExtras.putString(ImsCallProfile.EXTRA_DISPLAY_TEXT,
                            cleanseInstantLetteringMessage(intentExtras.getString(
                                    android.telecom.TelecomManager.EXTRA_CALL_SUBJECT))
                    );
                }

                if (conn.hasRttTextStream()) {
                    profile.mMediaProfile.mRttMode = ImsStreamMediaProfile.RTT_MODE_FULL;
                }

                if (intentExtras.containsKey(ImsCallProfile.EXTRA_IS_CALL_PULL)) {
                    profile.mCallExtras.putBoolean(ImsCallProfile.EXTRA_IS_CALL_PULL,
                            intentExtras.getBoolean(ImsCallProfile.EXTRA_IS_CALL_PULL));
                    int dialogId = intentExtras.getInt(
                            ImsExternalCallTracker.EXTRA_IMS_EXTERNAL_CALL_ID);
                    conn.setIsPulledCall(true);
                    conn.setPulledDialogId(dialogId);
                }

                // Pack the OEM-specific call extras.
                profile.mCallExtras.putBundle(ImsCallProfile.EXTRA_OEM_EXTRAS, intentExtras);

                // NOTE: Extras to be sent over the network are packed into the
                // intentExtras individually, with uniquely defined keys.
                // These key-value pairs are processed by IMS Service before
                // being sent to the lower layers/to the network.
            }

            ImsCall imsCall = mImsManager.makeCall(profile, callees, mImsCallListener);
            conn.setImsCall(imsCall);

            mMetrics.writeOnImsCallStart(mPhone.getPhoneId(),
                    imsCall.getSession());

            setVideoCallProvider(conn, imsCall);
            conn.setAllowAddCallDuringVideoCall(mAllowAddCallDuringVideoCall);
        } catch (ImsException e) {
            loge("dialInternal : " + e);
            conn.setDisconnectCause(DisconnectCause.ERROR_UNSPECIFIED);
            sendEmptyMessageDelayed(EVENT_HANGUP_PENDINGMO, TIMEOUT_HANGUP_PENDINGMO);
            retryGetImsService();
        } catch (RemoteException e) {
        }
    }

    /**
     * Accepts a call with the specified video state.  The video state is the video state that the
     * user has agreed upon in the InCall UI.
     *
     * @param videoState The video State
     * @throws CallStateException
     */
    public void acceptCall(int videoState) throws CallStateException {
        if (DBG) log("acceptCall");

        if (mForegroundCall.getState().isAlive()
                && mBackgroundCall.getState().isAlive()) {
            throw new CallStateException("cannot accept call");
        }

        if ((mRingingCall.getState() == ImsPhoneCall.State.WAITING)
                && mForegroundCall.getState().isAlive()) {
            setMute(false);

            boolean answeringWillDisconnect = false;
            ImsCall activeCall = mForegroundCall.getImsCall();
            ImsCall ringingCall = mRingingCall.getImsCall();
            if (mForegroundCall.hasConnections() && mRingingCall.hasConnections()) {
                answeringWillDisconnect =
                        shouldDisconnectActiveCallOnAnswer(activeCall, ringingCall);
            }

            // Cache video state for pending MT call.
            mPendingCallVideoState = videoState;

            if (answeringWillDisconnect) {
                // We need to disconnect the foreground call before answering the background call.
                mForegroundCall.hangup();
                try {
                    ringingCall.accept(ImsCallProfile.getCallTypeFromVideoState(videoState));
                } catch (ImsException e) {
                    throw new CallStateException("cannot accept call");
                }
            } else {
                holdActiveCallForWaitingCall();
            }
        } else if (mRingingCall.getState().isRinging()) {
            if (DBG) log("acceptCall: incoming...");
            // Always unmute when answering a new call
            setMute(false);
            try {
                ImsCall imsCall = mRingingCall.getImsCall();
                if (imsCall != null) {
                    imsCall.accept(ImsCallProfile.getCallTypeFromVideoState(videoState));
                    mMetrics.writeOnImsCommand(mPhone.getPhoneId(), imsCall.getSession(),
                            ImsCommand.IMS_CMD_ACCEPT);
                } else {
                    throw new CallStateException("no valid ims call");
                }
            } catch (ImsException e) {
                throw new CallStateException("cannot accept call");
            }
        } else {
            throw new CallStateException("phone not ringing");
        }
    }

    public void rejectCall () throws CallStateException {
        if (DBG) log("rejectCall");

        if (mRingingCall.getState().isRinging()) {
            hangup(mRingingCall);
        } else {
            throw new CallStateException("phone not ringing");
        }
    }

    /**
     * Set the emergency call information if it is an emergency call.
     */
    private void setEmergencyCallInfo(ImsCallProfile profile, Connection conn) {
        EmergencyNumber num = conn.getEmergencyNumberInfo();
        if (num != null) {
            profile.setEmergencyCallInfo(num, conn.hasKnownUserIntentEmergency());
        }
    }

    @UnsupportedAppUsage
    private void switchAfterConferenceSuccess() {
        if (DBG) log("switchAfterConferenceSuccess fg =" + mForegroundCall.getState() +
                ", bg = " + mBackgroundCall.getState());

        if (mBackgroundCall.getState() == ImsPhoneCall.State.HOLDING) {
            log("switchAfterConferenceSuccess");
            mForegroundCall.switchWith(mBackgroundCall);
        }
    }

    private void holdActiveCallForPendingMo() throws CallStateException {
        if (mHoldSwitchingState == HoldSwapState.PENDING_SINGLE_CALL_HOLD
                || mHoldSwitchingState == HoldSwapState.SWAPPING_ACTIVE_AND_HELD) {
            logi("Ignoring hold request while already holding or swapping");
            return;
        }
        ImsCall callToHold = mForegroundCall.getImsCall();

        mHoldSwitchingState = HoldSwapState.HOLDING_TO_DIAL_OUTGOING;
        logHoldSwapState("holdActiveCallForPendingMo");

        mForegroundCall.switchWith(mBackgroundCall);
        try {
            callToHold.hold();
            mMetrics.writeOnImsCommand(mPhone.getPhoneId(), callToHold.getSession(),
                    ImsCommand.IMS_CMD_HOLD);
        } catch (ImsException e) {
            mForegroundCall.switchWith(mBackgroundCall);
            throw new CallStateException(e.getMessage());
        }
    }

    /**
     * Holds the active call, possibly resuming the already-held background call if it exists.
     */
    public void holdActiveCall() throws CallStateException {
        if (mForegroundCall.getState() == ImsPhoneCall.State.ACTIVE) {
            if (mHoldSwitchingState == HoldSwapState.PENDING_SINGLE_CALL_HOLD
                    || mHoldSwitchingState == HoldSwapState.SWAPPING_ACTIVE_AND_HELD) {
                logi("Ignoring hold request while already holding or swapping");
                return;
            }
            ImsCall callToHold = mForegroundCall.getImsCall();
            if (mBackgroundCall.getState().isAlive()) {
                mCallExpectedToResume = mBackgroundCall.getImsCall();
                mHoldSwitchingState = HoldSwapState.SWAPPING_ACTIVE_AND_HELD;
            } else {
                mHoldSwitchingState = HoldSwapState.PENDING_SINGLE_CALL_HOLD;
            }
            logHoldSwapState("holdActiveCall");
            mForegroundCall.switchWith(mBackgroundCall);
            try {
                callToHold.hold();
                mMetrics.writeOnImsCommand(mPhone.getPhoneId(), callToHold.getSession(),
                        ImsCommand.IMS_CMD_HOLD);
            } catch (ImsException e) {
                mForegroundCall.switchWith(mBackgroundCall);
                throw new CallStateException(e.getMessage());
            }
        }
    }

    /**
     * Hold the currently active call in order to answer the waiting call.
     */
    public void holdActiveCallForWaitingCall() throws CallStateException {
        boolean switchingWithWaitingCall = !mBackgroundCall.getState().isAlive()
                && mRingingCall.getState() == ImsPhoneCall.State.WAITING;
        if (switchingWithWaitingCall) {
            ImsCall callToHold = mForegroundCall.getImsCall();
            mHoldSwitchingState = HoldSwapState.HOLDING_TO_ANSWER_INCOMING;
            mForegroundCall.switchWith(mBackgroundCall);
            logHoldSwapState("holdActiveCallForWaitingCall");
            try {
                callToHold.hold();
                mMetrics.writeOnImsCommand(mPhone.getPhoneId(), callToHold.getSession(),
                        ImsCommand.IMS_CMD_HOLD);
            } catch (ImsException e) {
                mForegroundCall.switchWith(mBackgroundCall);
                throw new CallStateException(e.getMessage());
            }
        }
    }

    /**
     * Unhold the currently held call.
     */
    void unholdHeldCall() throws CallStateException {
        try {
            ImsCall imsCall = mBackgroundCall.getImsCall();
            if (mHoldSwitchingState == HoldSwapState.PENDING_SINGLE_CALL_UNHOLD
                    || mHoldSwitchingState == HoldSwapState.SWAPPING_ACTIVE_AND_HELD) {
                logi("Ignoring unhold request while already unholding or swapping");
                return;
            }
            if (imsCall != null) {
                mCallExpectedToResume = imsCall;
                mHoldSwitchingState = HoldSwapState.PENDING_SINGLE_CALL_UNHOLD;
                mForegroundCall.switchWith(mBackgroundCall);
                logHoldSwapState("unholdCurrentCall");
                imsCall.resume();
                mMetrics.writeOnImsCommand(mPhone.getPhoneId(), imsCall.getSession(),
                        ImsCommand.IMS_CMD_RESUME);
            }
        } catch (ImsException e) {
            throw new CallStateException(e.getMessage());
        }
    }

    private void resumeForegroundCall() throws ImsException {
        //resume foreground call after holding background call
        //they were switched before holding
        ImsCall imsCall = mForegroundCall.getImsCall();
        if (imsCall != null) {
            imsCall.resume();
            mMetrics.writeOnImsCommand(mPhone.getPhoneId(), imsCall.getSession(),
                    ImsCommand.IMS_CMD_RESUME);
        }
    }

    private void answerWaitingCall() throws ImsException {
        //accept waiting call after holding background call
        ImsCall imsCall = mRingingCall.getImsCall();
        if (imsCall != null) {
            imsCall.accept(
                    ImsCallProfile.getCallTypeFromVideoState(mPendingCallVideoState));
            mMetrics.writeOnImsCommand(mPhone.getPhoneId(), imsCall.getSession(),
                    ImsCommand.IMS_CMD_ACCEPT);
        }
    }

    // Clean up expired cache entries.
    private void maintainConnectTimeCache() {
        long threshold = SystemClock.elapsedRealtime() - TIMEOUT_PARTICIPANT_CONNECT_TIME_CACHE_MS;
        // The cached time is the system elapsed millisecond when the CacheEntry is created.
        mPhoneNumAndConnTime.entrySet().removeIf(e -> e.getValue().mCachedTime < threshold);
        // Remove all the cached records which are older than current caching threshold. Since the
        // queue is FIFO, keep polling records until the queue is empty or the head of the queue is
        // fresh enough.
        while (!mUnknownPeerConnTime.isEmpty()
                && mUnknownPeerConnTime.peek().mCachedTime < threshold) {
            mUnknownPeerConnTime.poll();
        }
    }

    private void cacheConnectionTimeWithPhoneNumber(@NonNull ImsPhoneConnection connection) {
        int callDirection =
                connection.isIncoming() ? android.telecom.Call.Details.DIRECTION_INCOMING
                        : android.telecom.Call.Details.DIRECTION_OUTGOING;
        CacheEntry cachedConnectTime = new CacheEntry(SystemClock.elapsedRealtime(),
                connection.getConnectTime(), connection.getConnectTimeReal(), callDirection);
        maintainConnectTimeCache();
        if (PhoneConstants.PRESENTATION_ALLOWED == connection.getNumberPresentation()) {
            // In case of merging calls with the same number, use the latest connect time. Since
            // that call might be dropped and re-connected. So if the connectTime is earlier than
            // the cache, skip.
            String phoneNumber = getFormattedPhoneNumber(connection.getAddress());
            if (mPhoneNumAndConnTime.containsKey(phoneNumber)
                    && connection.getConnectTime()
                        <= mPhoneNumAndConnTime.get(phoneNumber).mConnectTime) {
                // Use the latest connect time.
                return;
            }
            mPhoneNumAndConnTime.put(phoneNumber, cachedConnectTime);
        } else {
            mUnknownPeerConnTime.add(cachedConnectTime);
        }
    }

    private CacheEntry findConnectionTimeUsePhoneNumber(
            @NonNull ConferenceParticipant participant) {
        maintainConnectTimeCache();
        if (PhoneConstants.PRESENTATION_ALLOWED == participant.getParticipantPresentation()) {
            if (participant.getHandle() == null
                    || participant.getHandle().getSchemeSpecificPart() == null) {
                return null;
            }

            String number = ConferenceParticipant.getParticipantAddress(participant.getHandle(),
                    getCountryIso()).getSchemeSpecificPart();
            if (TextUtils.isEmpty(number)) {
                return null;
            }
            String formattedNumber = getFormattedPhoneNumber(number);
            return mPhoneNumAndConnTime.get(formattedNumber);
        } else {
            return mUnknownPeerConnTime.poll();
        }
    }

    private String getFormattedPhoneNumber(String number) {
        String countryIso = getCountryIso();
        if (countryIso == null) {
            return number;
        }
        String phoneNumber = PhoneNumberUtils.formatNumberToE164(number, countryIso);
        return phoneNumber == null ? number : phoneNumber;
    }

    private String getCountryIso() {
        int subId = mPhone.getSubId();
        SubscriptionInfo info =
                SubscriptionManager.from(mPhone.getContext()).getActiveSubscriptionInfo(subId);
        return info == null ? null : info.getCountryIso();
    }

    public void
    conference() {
        ImsCall fgImsCall = mForegroundCall.getImsCall();
        if (fgImsCall == null) {
            log("conference no foreground ims call");
            return;
        }

        ImsCall bgImsCall = mBackgroundCall.getImsCall();
        if (bgImsCall == null) {
            log("conference no background ims call");
            return;
        }

        if (fgImsCall.isCallSessionMergePending()) {
            log("conference: skip; foreground call already in process of merging.");
            return;
        }

        if (bgImsCall.isCallSessionMergePending()) {
            log("conference: skip; background call already in process of merging.");
            return;
        }

        // Keep track of the connect time of the earliest call so that it can be set on the
        // {@code ImsConference} when it is created.
        long foregroundConnectTime = mForegroundCall.getEarliestConnectTime();
        long backgroundConnectTime = mBackgroundCall.getEarliestConnectTime();
        long conferenceConnectTime;
        if (foregroundConnectTime > 0 && backgroundConnectTime > 0) {
            conferenceConnectTime = Math.min(mForegroundCall.getEarliestConnectTime(),
                    mBackgroundCall.getEarliestConnectTime());
            log("conference - using connect time = " + conferenceConnectTime);
        } else if (foregroundConnectTime > 0) {
            log("conference - bg call connect time is 0; using fg = " + foregroundConnectTime);
            conferenceConnectTime = foregroundConnectTime;
        } else {
            log("conference - fg call connect time is 0; using bg = " + backgroundConnectTime);
            conferenceConnectTime = backgroundConnectTime;
        }

        String foregroundId = "";
        ImsPhoneConnection foregroundConnection = mForegroundCall.getFirstConnection();
        if (foregroundConnection != null) {
            foregroundConnection.setConferenceConnectTime(conferenceConnectTime);
            foregroundConnection.handleMergeStart();
            foregroundId = foregroundConnection.getTelecomCallId();
            cacheConnectionTimeWithPhoneNumber(foregroundConnection);
        }
        String backgroundId = "";
        ImsPhoneConnection backgroundConnection = findConnection(bgImsCall);
        if (backgroundConnection != null) {
            backgroundConnection.handleMergeStart();
            backgroundId = backgroundConnection.getTelecomCallId();
            cacheConnectionTimeWithPhoneNumber(backgroundConnection);
        }
        log("conference: fgCallId=" + foregroundId + ", bgCallId=" + backgroundId);

        try {
            fgImsCall.merge(bgImsCall);
        } catch (ImsException e) {
            log("conference " + e.getMessage());
        }
    }

    public void
    explicitCallTransfer() {
        //TODO : implement
    }

    @UnsupportedAppUsage
    public void
    clearDisconnected() {
        if (DBG) log("clearDisconnected");

        internalClearDisconnected();

        updatePhoneState();
        mPhone.notifyPreciseCallStateChanged();
    }

    public boolean
    canConference() {
        return mForegroundCall.getState() == ImsPhoneCall.State.ACTIVE
            && mBackgroundCall.getState() == ImsPhoneCall.State.HOLDING
            && !mBackgroundCall.isFull()
            && !mForegroundCall.isFull();
    }

    /**
     * Determines if there are issues which would preclude dialing an outgoing call.  Throws a
     * {@link CallStateException} if there is an issue.
     * @throws CallStateException
     */
    public void checkForDialIssues() throws CallStateException {
        String disableCall = SystemProperties.get(
                TelephonyProperties.PROPERTY_DISABLE_CALL, "false");
        if (disableCall.equals("true")) {
            throw new CallStateException(CallStateException.ERROR_CALLING_DISABLED,
                    "ro.telephony.disable-call has been used to disable calling.");
        }
        if (mPendingMO != null) {
            throw new CallStateException(CallStateException.ERROR_ALREADY_DIALING,
                    "Another outgoing call is already being dialed.");
        }
        if (mRingingCall.isRinging()) {
            throw new CallStateException(CallStateException.ERROR_CALL_RINGING,
                    "Can't place a call while another is ringing.");
        }
        if (mForegroundCall.getState().isAlive() & mBackgroundCall.getState().isAlive()) {
            throw new CallStateException(CallStateException.ERROR_TOO_MANY_CALLS,
                    "Already an active foreground and background call.");
        }
    }

    public boolean
    canTransfer() {
        return mForegroundCall.getState() == ImsPhoneCall.State.ACTIVE
            && mBackgroundCall.getState() == ImsPhoneCall.State.HOLDING;
    }

    //***** Private Instance Methods

    private void
    internalClearDisconnected() {
        mRingingCall.clearDisconnected();
        mForegroundCall.clearDisconnected();
        mBackgroundCall.clearDisconnected();
        mHandoverCall.clearDisconnected();
    }

    @UnsupportedAppUsage
    private void
    updatePhoneState() {
        PhoneConstants.State oldState = mState;

        boolean isPendingMOIdle = mPendingMO == null || !mPendingMO.getState().isAlive();

        if (mRingingCall.isRinging()) {
            mState = PhoneConstants.State.RINGING;
        } else if (!isPendingMOIdle || !mForegroundCall.isIdle() || !mBackgroundCall.isIdle()) {
            // There is a non-idle call, so we're off the hook.
            mState = PhoneConstants.State.OFFHOOK;
        } else {
            mState = PhoneConstants.State.IDLE;
        }

        if (mState == PhoneConstants.State.IDLE && oldState != mState) {
            mVoiceCallEndedRegistrants.notifyRegistrants(
                    new AsyncResult(null, null, null));
        } else if (oldState == PhoneConstants.State.IDLE && oldState != mState) {
            mVoiceCallStartedRegistrants.notifyRegistrants (
                    new AsyncResult(null, null, null));
        }

        if (DBG) {
            log("updatePhoneState pendingMo = " + (mPendingMO == null ? "null"
                    : mPendingMO.getState()) + ", fg= " + mForegroundCall.getState() + "("
                    + mForegroundCall.getConnections().size() + "), bg= " + mBackgroundCall
                    .getState() + "(" + mBackgroundCall.getConnections().size() + ")");
            log("updatePhoneState oldState=" + oldState + ", newState=" + mState);
        }

        if (mState != oldState) {
            mPhone.notifyPhoneStateChanged();
            mMetrics.writePhoneState(mPhone.getPhoneId(), mState);
            notifyPhoneStateChanged(oldState, mState);
        }
    }

    private void
    handleRadioNotAvailable() {
        // handlePollCalls will clear out its
        // call list when it gets the CommandException
        // error result from this
        pollCallsWhenSafe();
    }

    private void
    dumpState() {
        List l;

        log("Phone State:" + mState);

        log("Ringing call: " + mRingingCall.toString());

        l = mRingingCall.getConnections();
        for (int i = 0, s = l.size(); i < s; i++) {
            log(l.get(i).toString());
        }

        log("Foreground call: " + mForegroundCall.toString());

        l = mForegroundCall.getConnections();
        for (int i = 0, s = l.size(); i < s; i++) {
            log(l.get(i).toString());
        }

        log("Background call: " + mBackgroundCall.toString());

        l = mBackgroundCall.getConnections();
        for (int i = 0, s = l.size(); i < s; i++) {
            log(l.get(i).toString());
        }

    }

    //***** Called from ImsPhone
    /**
     * Set the TTY mode. This is the actual tty mode (varies depending on peripheral status)
     */
    public void setTtyMode(int ttyMode) {
        if (mImsManager == null) {
            Log.w(LOG_TAG, "ImsManager is null when setting TTY mode");
            return;
        }

        try {
            mImsManager.setTtyMode(ttyMode);
        } catch (ImsException e) {
            loge("setTtyMode : " + e);
            retryGetImsService();
        }
    }

    /**
     * Sets the UI TTY mode. This is the preferred TTY mode that the user sets in the call
     * settings screen.
     */
    public void setUiTTYMode(int uiTtyMode, Message onComplete) {
        if (mImsManager == null) {
            mPhone.sendErrorResponse(onComplete, getImsManagerIsNullException());
            return;
        }

        try {
            mImsManager.setUiTTYMode(mPhone.getContext(), uiTtyMode, onComplete);
        } catch (ImsException e) {
            loge("setUITTYMode : " + e);
            mPhone.sendErrorResponse(onComplete, e);
            retryGetImsService();
        }
    }

    public void setMute(boolean mute) {
        mDesiredMute = mute;
        mForegroundCall.setMute(mute);
    }

    public boolean getMute() {
        return mDesiredMute;
    }

    /**
     * Sends a DTMF code. According to <a href="http://tools.ietf.org/html/rfc2833">RFC 2833</a>,
     * event 0 ~ 9 maps to decimal value 0 ~ 9, '*' to 10, '#' to 11, event 'A' ~ 'D' to 12 ~ 15,
     * and event flash to 16. Currently, event flash is not supported.
     *
     * @param c that represents the DTMF to send. '0' ~ '9', 'A' ~ 'D', '*', '#' are valid inputs.
     * @param result the result message to send when done. If non-null, the {@link Message} must
     *         contain a valid {@link android.os.Messenger} in the {@link Message#replyTo} field,
     *         since this can be used across IPC boundaries.
     */
    public void sendDtmf(char c, Message result) {
        if (DBG) log("sendDtmf");

        ImsCall imscall = mForegroundCall.getImsCall();
        if (imscall != null) {
            imscall.sendDtmf(c, result);
        }
    }

    public void
    startDtmf(char c) {
        if (DBG) log("startDtmf");

        ImsCall imscall = mForegroundCall.getImsCall();
        if (imscall != null) {
            imscall.startDtmf(c);
        } else {
            loge("startDtmf : no foreground call");
        }
    }

    public void
    stopDtmf() {
        if (DBG) log("stopDtmf");

        ImsCall imscall = mForegroundCall.getImsCall();
        if (imscall != null) {
            imscall.stopDtmf();
        } else {
            loge("stopDtmf : no foreground call");
        }
    }

    //***** Called from ImsPhoneConnection

    public void hangup (ImsPhoneConnection conn) throws CallStateException {
        if (DBG) log("hangup connection");

        if (conn.getOwner() != this) {
            throw new CallStateException ("ImsPhoneConnection " + conn
                    + "does not belong to ImsPhoneCallTracker " + this);
        }

        hangup(conn.getCall());
    }

    //***** Called from ImsPhoneCall

    public void hangup (ImsPhoneCall call) throws CallStateException {
        if (DBG) log("hangup call");

        if (call.getConnections().size() == 0) {
            throw new CallStateException("no connections");
        }

        ImsCall imsCall = call.getImsCall();
        boolean rejectCall = false;

        if (call == mRingingCall) {
            if (Phone.DEBUG_PHONE) log("(ringing) hangup incoming");
            rejectCall = true;
        } else if (call == mForegroundCall) {
            if (call.isDialingOrAlerting()) {
                if (Phone.DEBUG_PHONE) {
                    log("(foregnd) hangup dialing or alerting...");
                }
            } else {
                if (Phone.DEBUG_PHONE) {
                    log("(foregnd) hangup foreground");
                }
                //held call will be resumed by onCallTerminated
            }
        } else if (call == mBackgroundCall) {
            if (Phone.DEBUG_PHONE) {
                log("(backgnd) hangup waiting or background");
            }
        } else {
            throw new CallStateException ("ImsPhoneCall " + call +
                    "does not belong to ImsPhoneCallTracker " + this);
        }

        call.onHangupLocal();

        try {
            if (imsCall != null) {
                if (rejectCall) {
                    imsCall.reject(ImsReasonInfo.CODE_USER_DECLINE);
                    mMetrics.writeOnImsCommand(mPhone.getPhoneId(), imsCall.getSession(),
                            ImsCommand.IMS_CMD_REJECT);
                } else {
                    imsCall.terminate(ImsReasonInfo.CODE_USER_TERMINATED);
                    mMetrics.writeOnImsCommand(mPhone.getPhoneId(), imsCall.getSession(),
                            ImsCommand.IMS_CMD_TERMINATE);
                }
            } else if (mPendingMO != null && call == mForegroundCall) {
                // is holding a foreground call
                mPendingMO.update(null, ImsPhoneCall.State.DISCONNECTED);
                mPendingMO.onDisconnect();
                removeConnection(mPendingMO);
                mPendingMO = null;
                updatePhoneState();
                removeMessages(EVENT_DIAL_PENDINGMO);
            }
        } catch (ImsException e) {
            throw new CallStateException(e.getMessage());
        }

        mPhone.notifyPreciseCallStateChanged();
    }

    void callEndCleanupHandOverCallIfAny() {
        if (mHandoverCall.mConnections.size() > 0) {
            if (DBG) log("callEndCleanupHandOverCallIfAny, mHandoverCall.mConnections="
                    + mHandoverCall.mConnections);
            mHandoverCall.mConnections.clear();
            mConnections.clear();
            mState = PhoneConstants.State.IDLE;
        }
    }


    public void sendUSSD (String ussdString, Message response) {
        if (DBG) log("sendUSSD");

        try {
            if (mUssdSession != null) {
                // Doesn't need mPendingUssd here. Listeners would use it if not null.
                mPendingUssd = null;
                mUssdSession.sendUssd(ussdString);
                AsyncResult.forMessage(response, null, null);
                response.sendToTarget();
                return;
            }

            if (mImsManager == null) {
                mPhone.sendErrorResponse(response, getImsManagerIsNullException());
                return;
            }

            String[] callees = new String[] { ussdString };
            ImsCallProfile profile = mImsManager.createCallProfile(
                    ImsCallProfile.SERVICE_TYPE_NORMAL, ImsCallProfile.CALL_TYPE_VOICE);
            profile.setCallExtraInt(ImsCallProfile.EXTRA_DIALSTRING,
                    ImsCallProfile.DIALSTRING_USSD);

            mUssdSession = mImsManager.makeCall(profile, callees, mImsUssdListener);
            mPendingUssd = response;
            if (DBG) log("pending ussd updated, " + mPendingUssd);
        } catch (ImsException e) {
            loge("sendUSSD : " + e);
            mPhone.sendErrorResponse(response, e);
            retryGetImsService();
        }
    }

    /**
     * Cancel USSD session.
     *
     * @param msg The message to dispatch when the USSD session terminated.
     */
    public void cancelUSSD(Message msg) {
        if (mUssdSession == null) return;
        mPendingUssd = msg;
        mUssdSession.terminate(ImsReasonInfo.CODE_USER_TERMINATED);
    }

    private synchronized ImsPhoneConnection findConnection(final ImsCall imsCall) {
        for (ImsPhoneConnection conn : mConnections) {
            if (conn.getImsCall() == imsCall) {
                return conn;
            }
        }
        return null;
    }

    @UnsupportedAppUsage
    private synchronized void removeConnection(ImsPhoneConnection conn) {
        mConnections.remove(conn);
        // If not emergency call is remaining, notify emergency call registrants
        if (mIsInEmergencyCall) {
            boolean isEmergencyCallInList = false;
            // if no emergency calls pending, set this to false
            for (ImsPhoneConnection imsPhoneConnection : mConnections) {
                if (imsPhoneConnection != null && imsPhoneConnection.isEmergency() == true) {
                    isEmergencyCallInList = true;
                    break;
                }
            }

            if (!isEmergencyCallInList) {
                mIsInEmergencyCall = false;
                mPhone.sendEmergencyCallStateChange(false);
            }
        }
    }

    @UnsupportedAppUsage
    private synchronized void addConnection(ImsPhoneConnection conn) {
        mConnections.add(conn);
        if (conn.isEmergency()) {
            mIsInEmergencyCall = true;
            mPhone.sendEmergencyCallStateChange(true);
        }
    }

    private void processCallStateChange(ImsCall imsCall, ImsPhoneCall.State state, int cause) {
        if (DBG) log("processCallStateChange " + imsCall + " state=" + state + " cause=" + cause);
        // This method is called on onCallUpdate() where there is not necessarily a call state
        // change. In these situations, we'll ignore the state related updates and only process
        // the change in media capabilities (as expected).  The default is to not ignore state
        // changes so we do not change existing behavior.
        processCallStateChange(imsCall, state, cause, false /* do not ignore state update */);
    }

    private void processCallStateChange(ImsCall imsCall, ImsPhoneCall.State state, int cause,
            boolean ignoreState) {
        if (DBG) {
            log("processCallStateChange state=" + state + " cause=" + cause
                    + " ignoreState=" + ignoreState);
        }

        if (imsCall == null) return;

        boolean changed = false;
        ImsPhoneConnection conn = findConnection(imsCall);

        if (conn == null) {
            // TODO : what should be done?
            return;
        }

        // processCallStateChange is triggered for onCallUpdated as well.
        // onCallUpdated should not modify the state of the call
        // It should modify only other capabilities of call through updateMediaCapabilities
        // State updates will be triggered through individual callbacks
        // i.e. onCallHeld, onCallResume, etc and conn.update will be responsible for the update
        conn.updateMediaCapabilities(imsCall);
        if (ignoreState) {
            conn.updateAddressDisplay(imsCall);
            conn.updateExtras(imsCall);

            maybeSetVideoCallProvider(conn, imsCall);
            return;
        }

        changed = conn.update(imsCall, state);
        if (state == ImsPhoneCall.State.DISCONNECTED) {
            changed = conn.onDisconnect(cause) || changed;
            //detach the disconnected connections
            conn.getCall().detach(conn);
            removeConnection(conn);
        }

        if (changed) {
            if (conn.getCall() == mHandoverCall) return;
            updatePhoneState();
            mPhone.notifyPreciseCallStateChanged();
        }
    }

    private void maybeSetVideoCallProvider(ImsPhoneConnection conn, ImsCall imsCall) {
        android.telecom.Connection.VideoProvider connVideoProvider = conn.getVideoProvider();
        if (connVideoProvider != null || imsCall.getCallSession().getVideoCallProvider() == null) {
            return;
        }

        try {
            setVideoCallProvider(conn, imsCall);
        } catch (RemoteException e) {
            loge("maybeSetVideoCallProvider: exception " + e);
        }
    }

    /**
     * Adds a reason code remapping, for test purposes.
     *
     * @param fromCode The from code, or {@code null} if all.
     * @param message The message to map.
     * @param toCode The code to remap to.
     */
    @VisibleForTesting
    public void addReasonCodeRemapping(Integer fromCode, String message, Integer toCode) {
        mImsReasonCodeMap.put(new Pair<>(fromCode, message), toCode);
    }

    /**
     * Returns the {@link ImsReasonInfo#getCode()}, potentially remapping to a new value based on
     * the {@link ImsReasonInfo#getCode()} and {@link ImsReasonInfo#getExtraMessage()}.
     *
     * See {@link #mImsReasonCodeMap}.
     *
     * @param reasonInfo The {@link ImsReasonInfo}.
     * @return The remapped code.
     */
    @VisibleForTesting
    public @ImsReasonInfo.ImsCode int maybeRemapReasonCode(ImsReasonInfo reasonInfo) {
        int code = reasonInfo.getCode();
        String reason = reasonInfo.getExtraMessage();
        if (reason == null) {
            reason = "";
        }
        log("maybeRemapReasonCode : fromCode = " + reasonInfo.getCode() + " ; message = "
                + reason);
        Pair<Integer, String> toCheck = new Pair<>(code, reason);
        Pair<Integer, String> wildcardToCheck = new Pair<>(null, reason);
        if (mImsReasonCodeMap.containsKey(toCheck)) {
            int toCode = mImsReasonCodeMap.get(toCheck);

            log("maybeRemapReasonCode : fromCode = " + reasonInfo.getCode() + " ; message = "
                    + reason + " ; toCode = " + toCode);
            return toCode;
        } else if (!reason.isEmpty() && mImsReasonCodeMap.containsKey(wildcardToCheck)) {
            // Handle the case where a wildcard is specified for the fromCode; in this case we will
            // match without caring about the fromCode.
            // If the reason is empty, we won't do wildcard remapping; otherwise we'd basically be
            // able to remap all ImsReasonInfo codes to a single code, which is not desirable.
            int toCode = mImsReasonCodeMap.get(wildcardToCheck);

            log("maybeRemapReasonCode : fromCode(wildcard) = " + reasonInfo.getCode() +
                    " ; message = " + reason + " ; toCode = " + toCode);
            return toCode;
        }
        return code;
    }

    /**
     * Maps an {@link ImsReasonInfo} reason code to a {@link DisconnectCause} cause code.
     * The {@link Call.State} provided is the state of the call prior to disconnection.
     * @param reasonInfo the {@link ImsReasonInfo} for the disconnection.
     * @param callState The {@link Call.State} prior to disconnection.
     * @return The {@link DisconnectCause} code.
     */
    @VisibleForTesting
    public int getDisconnectCauseFromReasonInfo(ImsReasonInfo reasonInfo, Call.State callState) {
        int cause = DisconnectCause.ERROR_UNSPECIFIED;

        int code = maybeRemapReasonCode(reasonInfo);
        switch (code) {
            case ImsReasonInfo.CODE_SIP_ALTERNATE_EMERGENCY_CALL:
                return DisconnectCause.IMS_SIP_ALTERNATE_EMERGENCY_CALL;
            case ImsReasonInfo.CODE_SIP_BAD_ADDRESS:
            case ImsReasonInfo.CODE_SIP_NOT_REACHABLE:
                return DisconnectCause.NUMBER_UNREACHABLE;

            case ImsReasonInfo.CODE_SIP_BUSY:
                return DisconnectCause.BUSY;

            case ImsReasonInfo.CODE_USER_TERMINATED:
                return DisconnectCause.LOCAL;

            case ImsReasonInfo.CODE_LOCAL_ENDED_BY_CONFERENCE_MERGE:
                return DisconnectCause.IMS_MERGED_SUCCESSFULLY;

            case ImsReasonInfo.CODE_LOCAL_CALL_DECLINE:
            case ImsReasonInfo.CODE_REMOTE_CALL_DECLINE:
                // If the call has been declined locally (on this device), or on remotely (on
                // another device using multiendpoint functionality), mark it as rejected.
                return DisconnectCause.INCOMING_REJECTED;

            case ImsReasonInfo.CODE_USER_TERMINATED_BY_REMOTE:
            case ImsReasonInfo.CODE_SIP_USER_REJECTED:
                return DisconnectCause.NORMAL;

            case ImsReasonInfo.CODE_SIP_FORBIDDEN:
                return DisconnectCause.SERVER_ERROR;

            case ImsReasonInfo.CODE_SIP_REDIRECTED:
            case ImsReasonInfo.CODE_SIP_BAD_REQUEST:
            case ImsReasonInfo.CODE_SIP_NOT_ACCEPTABLE:
            case ImsReasonInfo.CODE_SIP_GLOBAL_ERROR:
                return DisconnectCause.SERVER_ERROR;

            case ImsReasonInfo.CODE_SIP_SERVICE_UNAVAILABLE:
            case ImsReasonInfo.CODE_SIP_SERVER_ERROR:
                return DisconnectCause.SERVER_UNREACHABLE;

            case ImsReasonInfo.CODE_SIP_NOT_FOUND:
                return DisconnectCause.INVALID_NUMBER;

            case ImsReasonInfo.CODE_LOCAL_NETWORK_ROAMING:
            case ImsReasonInfo.CODE_LOCAL_NETWORK_IP_CHANGED:
            case ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN:
            case ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE:
            case ImsReasonInfo.CODE_LOCAL_NOT_REGISTERED:
            case ImsReasonInfo.CODE_LOCAL_NETWORK_NO_LTE_COVERAGE:
            case ImsReasonInfo.CODE_LOCAL_NETWORK_NO_SERVICE:
            case ImsReasonInfo.CODE_LOCAL_CALL_VCC_ON_PROGRESSING:
                return DisconnectCause.OUT_OF_SERVICE;

            case ImsReasonInfo.CODE_SIP_REQUEST_TIMEOUT:
            case ImsReasonInfo.CODE_TIMEOUT_1XX_WAITING:
            case ImsReasonInfo.CODE_TIMEOUT_NO_ANSWER:
            case ImsReasonInfo.CODE_TIMEOUT_NO_ANSWER_CALL_UPDATE:
                return DisconnectCause.TIMED_OUT;

            case ImsReasonInfo.CODE_LOCAL_POWER_OFF:
                return DisconnectCause.POWER_OFF;

            case ImsReasonInfo.CODE_LOCAL_LOW_BATTERY:
            case ImsReasonInfo.CODE_LOW_BATTERY: {
                if (callState == Call.State.DIALING) {
                    return DisconnectCause.DIAL_LOW_BATTERY;
                } else {
                    return DisconnectCause.LOW_BATTERY;
                }
            }

            case ImsReasonInfo.CODE_CALL_BARRED:
                return DisconnectCause.CALL_BARRED;

            case ImsReasonInfo.CODE_FDN_BLOCKED:
                return DisconnectCause.FDN_BLOCKED;

            case ImsReasonInfo.CODE_IMEI_NOT_ACCEPTED:
                return DisconnectCause.IMEI_NOT_ACCEPTED;

            case ImsReasonInfo.CODE_ANSWERED_ELSEWHERE:
                return DisconnectCause.ANSWERED_ELSEWHERE;

            case ImsReasonInfo.CODE_CALL_END_CAUSE_CALL_PULL:
                return DisconnectCause.CALL_PULLED;

            case ImsReasonInfo.CODE_MAXIMUM_NUMBER_OF_CALLS_REACHED:
                return DisconnectCause.MAXIMUM_NUMBER_OF_CALLS_REACHED;

            case ImsReasonInfo.CODE_DATA_DISABLED:
                return DisconnectCause.DATA_DISABLED;

            case ImsReasonInfo.CODE_DATA_LIMIT_REACHED:
                return DisconnectCause.DATA_LIMIT_REACHED;

            case ImsReasonInfo.CODE_WIFI_LOST:
                return DisconnectCause.WIFI_LOST;

            case ImsReasonInfo.CODE_ACCESS_CLASS_BLOCKED:
                return DisconnectCause.IMS_ACCESS_BLOCKED;

            case ImsReasonInfo.CODE_EMERGENCY_TEMP_FAILURE:
                return DisconnectCause.EMERGENCY_TEMP_FAILURE;

            case ImsReasonInfo.CODE_EMERGENCY_PERM_FAILURE:
                return DisconnectCause.EMERGENCY_PERM_FAILURE;

            case ImsReasonInfo.CODE_DIAL_MODIFIED_TO_USSD:
                return DisconnectCause.DIAL_MODIFIED_TO_USSD;

            case ImsReasonInfo.CODE_DIAL_MODIFIED_TO_SS:
                return DisconnectCause.DIAL_MODIFIED_TO_SS;

            case ImsReasonInfo.CODE_DIAL_MODIFIED_TO_DIAL:
                return DisconnectCause.DIAL_MODIFIED_TO_DIAL;

            case ImsReasonInfo.CODE_DIAL_MODIFIED_TO_DIAL_VIDEO:
                return DisconnectCause.DIAL_MODIFIED_TO_DIAL_VIDEO;

            case ImsReasonInfo.CODE_DIAL_VIDEO_MODIFIED_TO_DIAL:
                return DisconnectCause.DIAL_VIDEO_MODIFIED_TO_DIAL;

            case ImsReasonInfo.CODE_DIAL_VIDEO_MODIFIED_TO_DIAL_VIDEO:
                return DisconnectCause.DIAL_VIDEO_MODIFIED_TO_DIAL_VIDEO;

            case ImsReasonInfo.CODE_DIAL_VIDEO_MODIFIED_TO_SS:
                return DisconnectCause.DIAL_VIDEO_MODIFIED_TO_SS;

            case ImsReasonInfo.CODE_DIAL_VIDEO_MODIFIED_TO_USSD:
                return DisconnectCause.DIAL_VIDEO_MODIFIED_TO_USSD;

            case ImsReasonInfo.CODE_UNOBTAINABLE_NUMBER:
                return DisconnectCause.UNOBTAINABLE_NUMBER;

            case ImsReasonInfo.CODE_UNSPECIFIED:
                if (mPhone.getDefaultPhone().getServiceStateTracker().mRestrictedState
                        .isCsRestricted()) {
                    return DisconnectCause.CS_RESTRICTED;
                } else if (mPhone.getDefaultPhone().getServiceStateTracker().mRestrictedState
                        .isCsEmergencyRestricted()) {
                    return DisconnectCause.CS_RESTRICTED_EMERGENCY;
                } else if (mPhone.getDefaultPhone().getServiceStateTracker().mRestrictedState
                        .isCsNormalRestricted()) {
                    return DisconnectCause.CS_RESTRICTED_NORMAL;
                }
                break;

            default:
        }

        return cause;
    }

    /**
     * @return true if the phone is in Emergency Callback mode, otherwise false
     */
    private boolean isPhoneInEcbMode() {
        return mPhone != null && mPhone.isInEcm();
    }

    /**
     * Before dialing pending MO request, check for the Emergency Callback mode.
     * If device is in Emergency callback mode, then exit the mode before dialing pending MO.
     */
    @UnsupportedAppUsage
    private void dialPendingMO() {
        boolean isPhoneInEcmMode = isPhoneInEcbMode();
        boolean isEmergencyNumber = mPendingMO.isEmergency();
        if ((!isPhoneInEcmMode) || (isPhoneInEcmMode && isEmergencyNumber)) {
            sendEmptyMessage(EVENT_DIAL_PENDINGMO);
        } else {
            sendEmptyMessage(EVENT_EXIT_ECBM_BEFORE_PENDINGMO);
        }
    }

    /**
     * Listen to the IMS call state change
     */
    private ImsCall.Listener mImsCallListener = new ImsCall.Listener() {
        @Override
        public void onCallProgressing(ImsCall imsCall) {
            if (DBG) log("onCallProgressing");

            mPendingMO = null;
            processCallStateChange(imsCall, ImsPhoneCall.State.ALERTING,
                    DisconnectCause.NOT_DISCONNECTED);
            mMetrics.writeOnImsCallProgressing(mPhone.getPhoneId(), imsCall.getCallSession());
        }

        @Override
        public void onCallStarted(ImsCall imsCall) {
            if (DBG) log("onCallStarted");

            if (mHoldSwitchingState == HoldSwapState.HOLDING_TO_ANSWER_INCOMING) {
                // If we put a call on hold to answer an incoming call, we should reset the
                // variables that keep track of the switch here.
                if (mCallExpectedToResume != null && mCallExpectedToResume == imsCall) {
                    if (DBG) log("onCallStarted: starting a call as a result of a switch.");
                    mHoldSwitchingState = HoldSwapState.INACTIVE;
                    mCallExpectedToResume = null;
                    logHoldSwapState("onCallStarted");
                }
            }

            mPendingMO = null;
            processCallStateChange(imsCall, ImsPhoneCall.State.ACTIVE,
                    DisconnectCause.NOT_DISCONNECTED);

            if (mNotifyVtHandoverToWifiFail && imsCall.isVideoCall() && !imsCall.isWifiCall()) {
                if (isWifiConnected()) {
                    // Schedule check to see if handover succeeded.
                    sendMessageDelayed(obtainMessage(EVENT_CHECK_FOR_WIFI_HANDOVER, imsCall),
                            HANDOVER_TO_WIFI_TIMEOUT_MS);
                    mHasAttemptedStartOfCallHandover = false;
                } else {
                    // No wifi connectivity, so keep track of network availability for potential
                    // handover.
                    registerForConnectivityChanges();
                    // No WIFI, so assume we've already attempted a handover.
                    mHasAttemptedStartOfCallHandover = true;
                }
            }
            mMetrics.writeOnImsCallStarted(mPhone.getPhoneId(), imsCall.getCallSession());
        }

        @Override
        public void onCallUpdated(ImsCall imsCall) {
            if (DBG) log("onCallUpdated");
            if (imsCall == null) {
                return;
            }
            ImsPhoneConnection conn = findConnection(imsCall);
            if (conn != null) {
                if (DBG) log("onCallUpdated: profile is " + imsCall.getCallProfile());
                processCallStateChange(imsCall, conn.getCall().mState,
                        DisconnectCause.NOT_DISCONNECTED, true /*ignore state update*/);
                mMetrics.writeImsCallState(mPhone.getPhoneId(),
                        imsCall.getCallSession(), conn.getCall().mState);
            }
        }

        /**
         * onCallStartFailed will be invoked when:
         * case 1) Dialing fails
         * case 2) Ringing call is disconnected by local or remote user
         */
        @Override
        public void onCallStartFailed(ImsCall imsCall, ImsReasonInfo reasonInfo) {
            if (DBG) log("onCallStartFailed reasonCode=" + reasonInfo.getCode());

            if (mHoldSwitchingState == HoldSwapState.HOLDING_TO_ANSWER_INCOMING) {
                // If we put a call on hold to answer an incoming call, we should reset the
                // variables that keep track of the switch here.
                if (mCallExpectedToResume != null && mCallExpectedToResume == imsCall) {
                    if (DBG) log("onCallStarted: starting a call as a result of a switch.");
                    mHoldSwitchingState = HoldSwapState.INACTIVE;
                    mCallExpectedToResume = null;
                    logHoldSwapState("onCallStartFailed");
                }
            }

            if (mPendingMO != null) {
                // To initiate dialing circuit-switched call
                if (reasonInfo.getCode() == ImsReasonInfo.CODE_LOCAL_CALL_CS_RETRY_REQUIRED
                        && mBackgroundCall.getState() == ImsPhoneCall.State.IDLE
                        && mRingingCall.getState() == ImsPhoneCall.State.IDLE) {
                    mForegroundCall.detach(mPendingMO);
                    removeConnection(mPendingMO);
                    mPendingMO.finalize();
                    mPendingMO = null;
                    mPhone.initiateSilentRedial();
                    return;
                } else {
                    sendCallStartFailedDisconnect(imsCall, reasonInfo);
                }
                mMetrics.writeOnImsCallStartFailed(mPhone.getPhoneId(), imsCall.getCallSession(),
                        reasonInfo);
            }
        }

        @Override
        public void onCallTerminated(ImsCall imsCall, ImsReasonInfo reasonInfo) {
            if (DBG) log("onCallTerminated reasonCode=" + reasonInfo.getCode());

            ImsPhoneConnection conn = findConnection(imsCall);
            Call.State callState;
            if (conn != null) {
                callState = conn.getState();
            } else {
                // Connection shouldn't be null, but if it is, we can assume the call was active.
                // This call state is only used for determining which disconnect message to show in
                // the case of the device's battery being low resulting in a call drop.
                callState = Call.State.ACTIVE;
            }
            int cause = getDisconnectCauseFromReasonInfo(reasonInfo, callState);

            if (DBG) log("cause = " + cause + " conn = " + conn);

            if (conn != null) {
                android.telecom.Connection.VideoProvider videoProvider = conn.getVideoProvider();
                if (videoProvider instanceof ImsVideoCallProviderWrapper) {
                    ImsVideoCallProviderWrapper wrapper = (ImsVideoCallProviderWrapper)
                            videoProvider;
                    wrapper.unregisterForDataUsageUpdate(ImsPhoneCallTracker.this);
                    wrapper.removeImsVideoProviderCallback(conn);
                }
            }
            if (mOnHoldToneId == System.identityHashCode(conn)) {
                if (conn != null && mOnHoldToneStarted) {
                    mPhone.stopOnHoldTone(conn);
                }
                mOnHoldToneStarted = false;
                mOnHoldToneId = -1;
            }
            if (conn != null) {
                if (conn.isPulledCall() && (
                        reasonInfo.getCode() == ImsReasonInfo.CODE_CALL_PULL_OUT_OF_SYNC ||
                        reasonInfo.getCode() == ImsReasonInfo.CODE_SIP_TEMPRARILY_UNAVAILABLE ||
                        reasonInfo.getCode() == ImsReasonInfo.CODE_SIP_FORBIDDEN) &&
                        mPhone != null && mPhone.getExternalCallTracker() != null) {

                    log("Call pull failed.");
                    // Call was being pulled, but the call pull has failed -- inform the associated
                    // TelephonyConnection that the pull failed, and provide it with the original
                    // external connection which was pulled so that it can be swapped back.
                    conn.onCallPullFailed(mPhone.getExternalCallTracker()
                            .getConnectionById(conn.getPulledDialogId()));
                    // Do not mark as disconnected; the call will just change from being a regular
                    // call to being an external call again.
                    cause = DisconnectCause.NOT_DISCONNECTED;

                } else if (conn.isIncoming() && conn.getConnectTime() == 0
                        && cause != DisconnectCause.ANSWERED_ELSEWHERE) {
                    // Missed
                    if (cause == DisconnectCause.NORMAL) {
                        cause = DisconnectCause.INCOMING_MISSED;
                    } else {
                        cause = DisconnectCause.INCOMING_REJECTED;
                    }
                    if (DBG) log("Incoming connection of 0 connect time detected - translated " +
                            "cause = " + cause);
                }
            }

            if (cause == DisconnectCause.NORMAL && conn != null && conn.getImsCall().isMerged()) {
                // Call was terminated while it is merged instead of a remote disconnect.
                cause = DisconnectCause.IMS_MERGED_SUCCESSFULLY;
            }

            String callId = imsCall.getSession().getCallId();
            mMetrics.writeOnImsCallTerminated(mPhone.getPhoneId(), imsCall.getCallSession(),
                    reasonInfo, mCallQualityMetrics.get(callId), conn.getEmergencyNumberInfo(),
                    getNetworkCountryIso());
            // Remove info for the callId from the current calls and add it to the history
            CallQualityMetrics lastCallMetrics = mCallQualityMetrics.remove(callId);
            if (lastCallMetrics != null) {
                mCallQualityMetricsHistory.add(lastCallMetrics);
            }
            pruneCallQualityMetricsHistory();
            mPhone.notifyImsReason(reasonInfo);

            if (reasonInfo.getCode() == ImsReasonInfo.CODE_SIP_ALTERNATE_EMERGENCY_CALL
                    && mAutoRetryFailedWifiEmergencyCall) {
                Pair<ImsCall, ImsReasonInfo> callInfo = new Pair<>(imsCall, reasonInfo);
                mPhone.getDefaultPhone().getServiceStateTracker().registerForNetworkAttached(
                        ImsPhoneCallTracker.this, EVENT_REDIAL_WIFI_E911_CALL, callInfo);
                sendMessageDelayed(obtainMessage(EVENT_REDIAL_WIFI_E911_TIMEOUT, callInfo),
                        TIMEOUT_REDIAL_WIFI_E911_MS);
                final ConnectivityManager mgr = (ConnectivityManager) mPhone.getContext()
                        .getSystemService(Context.CONNECTIVITY_SERVICE);
                mgr.setAirplaneMode(false);
                return;
            } else {
                processCallStateChange(imsCall, ImsPhoneCall.State.DISCONNECTED, cause);
            }

            if (mForegroundCall.getState() != ImsPhoneCall.State.ACTIVE) {
                if (mRingingCall.getState().isRinging()) {
                    // Drop pending MO. We should address incoming call first
                    mPendingMO = null;
                }
            }

            if (mHoldSwitchingState == HoldSwapState.SWAPPING_ACTIVE_AND_HELD) {
                if (DBG) {
                    log("onCallTerminated: Call terminated in the midst of Switching " +
                            "Fg and Bg calls.");
                }
                // If we are the in midst of swapping FG and BG calls and the call that was
                // terminated was the one that we expected to resume, we need to swap the FG and
                // BG calls back.
                if (imsCall == mCallExpectedToResume) {
                    if (DBG) {
                        log("onCallTerminated: switching " + mForegroundCall + " with "
                                + mBackgroundCall);
                    }
                    mForegroundCall.switchWith(mBackgroundCall);
                }
                // This call terminated in the midst of a switch after the other call was held, so
                // resume it back to ACTIVE state since the switch failed.
                log("onCallTerminated: foreground call in state " + mForegroundCall.getState() +
                        " and ringing call in state " + (mRingingCall == null ? "null" :
                        mRingingCall.getState().toString()));

                sendEmptyMessage(EVENT_RESUME_NOW_FOREGROUND_CALL);
                mHoldSwitchingState = HoldSwapState.INACTIVE;
                mCallExpectedToResume = null;
                logHoldSwapState("onCallTerminated swap active and hold case");
            } else if (mHoldSwitchingState == HoldSwapState.PENDING_SINGLE_CALL_UNHOLD
                    || mHoldSwitchingState == HoldSwapState.PENDING_SINGLE_CALL_HOLD) {
                mCallExpectedToResume = null;
                mHoldSwitchingState = HoldSwapState.INACTIVE;
                logHoldSwapState("onCallTerminated single call case");
            } else if (mHoldSwitchingState == HoldSwapState.HOLDING_TO_ANSWER_INCOMING) {
                // Check to see which call got terminated. If it's the one that was gonna get held,
                // ignore it. If it's the one that was gonna get answered, restore the one that
                // possibly got held.
                if (imsCall == mCallExpectedToResume) {
                    mForegroundCall.switchWith(mBackgroundCall);
                    mCallExpectedToResume = null;
                    mHoldSwitchingState = HoldSwapState.INACTIVE;
                    logHoldSwapState("onCallTerminated hold to answer case");
                    sendEmptyMessage(EVENT_RESUME_NOW_FOREGROUND_CALL);
                }
            } else if (mHoldSwitchingState == HoldSwapState.HOLDING_TO_DIAL_OUTGOING) {
                // The call that we were gonna hold might've gotten terminated. If that's the case,
                // dial mPendingMo if present.
                if (mPendingMO == null
                        || mPendingMO.getDisconnectCause() != DisconnectCause.NOT_DISCONNECTED) {
                    mHoldSwitchingState = HoldSwapState.INACTIVE;
                    logHoldSwapState("onCallTerminated hold to dial but no pendingMo");
                } else if (imsCall != mPendingMO.getImsCall()) {
                    sendEmptyMessage(EVENT_DIAL_PENDINGMO);
                    mHoldSwitchingState = HoldSwapState.INACTIVE;
                    logHoldSwapState("onCallTerminated hold to dial, dial pendingMo");
                }
            }

            if (mShouldUpdateImsConfigOnDisconnect) {
                // Ensure we update the IMS config when the call is disconnected; we delayed this
                // because a video call was paused.
                if (mImsManager != null) {
                    mImsManager.updateImsServiceConfig(true);
                }
                mShouldUpdateImsConfigOnDisconnect = false;
            }
        }

        @Override
        public void onCallHeld(ImsCall imsCall) {
            if (DBG) {
                if (mForegroundCall.getImsCall() == imsCall) {
                    log("onCallHeld (fg) " + imsCall);
                } else if (mBackgroundCall.getImsCall() == imsCall) {
                    log("onCallHeld (bg) " + imsCall);
                }
            }

            synchronized (mSyncHold) {
                ImsPhoneCall.State oldState = mBackgroundCall.getState();
                processCallStateChange(imsCall, ImsPhoneCall.State.HOLDING,
                        DisconnectCause.NOT_DISCONNECTED);

                // Note: If we're performing a switchWaitingOrHoldingAndActive, the call to
                // processCallStateChange above may have caused the mBackgroundCall and
                // mForegroundCall references below to change meaning.  Watch out for this if you
                // are reading through this code.
                if (oldState == ImsPhoneCall.State.ACTIVE) {
                    // Note: This case comes up when we have just held a call in response to a
                    // switchWaitingOrHoldingAndActive.  We now need to resume the background call.
                    if (mForegroundCall.getState() == ImsPhoneCall.State.HOLDING
                            && mHoldSwitchingState == HoldSwapState.SWAPPING_ACTIVE_AND_HELD) {
                        sendEmptyMessage(EVENT_RESUME_NOW_FOREGROUND_CALL);
                    } else if (mRingingCall.getState() == ImsPhoneCall.State.WAITING
                            && mHoldSwitchingState == HoldSwapState.HOLDING_TO_ANSWER_INCOMING) {
                        sendEmptyMessage(EVENT_ANSWER_WAITING_CALL);
                    } else if (mPendingMO != null
                            && mHoldSwitchingState == HoldSwapState.HOLDING_TO_DIAL_OUTGOING) {
                        dialPendingMO();
                        mHoldSwitchingState = HoldSwapState.INACTIVE;
                        logHoldSwapState("onCallHeld hold to dial");
                    } else {
                        // In this case there will be no call resumed, so we can assume that we
                        // are done switching fg and bg calls now.
                        // This may happen if there is no BG call and we are holding a call so that
                        // we can dial another one.
                        mHoldSwitchingState = HoldSwapState.INACTIVE;
                        logHoldSwapState("onCallHeld normal case");
                    }
                } else if (oldState == ImsPhoneCall.State.IDLE
                        && (mHoldSwitchingState == HoldSwapState.SWAPPING_ACTIVE_AND_HELD
                                || mHoldSwitchingState
                                == HoldSwapState.HOLDING_TO_ANSWER_INCOMING)) {
                    // The other call terminated in the midst of a switch before this call was held,
                    // so resume the foreground call back to ACTIVE state since the switch failed.
                    if (mForegroundCall.getState() == ImsPhoneCall.State.HOLDING) {
                        sendEmptyMessage(EVENT_RESUME_NOW_FOREGROUND_CALL);
                        mHoldSwitchingState = HoldSwapState.INACTIVE;
                        mCallExpectedToResume = null;
                        logHoldSwapState("onCallHeld premature termination of other call");
                    }
                }
            }
            mMetrics.writeOnImsCallHeld(mPhone.getPhoneId(), imsCall.getCallSession());
        }

        @Override
        public void onCallHoldFailed(ImsCall imsCall, ImsReasonInfo reasonInfo) {
            if (DBG) log("onCallHoldFailed reasonCode=" + reasonInfo.getCode());

            synchronized (mSyncHold) {
                ImsPhoneCall.State bgState = mBackgroundCall.getState();
                if (reasonInfo.getCode() == ImsReasonInfo.CODE_LOCAL_CALL_TERMINATED) {
                    // disconnected while processing hold
                    if (mPendingMO != null) {
                        dialPendingMO();
                    } else if (mRingingCall.getState() == ImsPhoneCall.State.WAITING
                            && mHoldSwitchingState == HoldSwapState.HOLDING_TO_ANSWER_INCOMING) {
                        sendEmptyMessage(EVENT_ANSWER_WAITING_CALL);
                    }
                    mHoldSwitchingState = HoldSwapState.INACTIVE;
                } else if (mPendingMO != null && mPendingMO.isEmergency()) {
                    // If mPendingMO is an emergency call, disconnect the call that we tried to
                    // hold.
                    mBackgroundCall.getImsCall().terminate(ImsReasonInfo.CODE_UNSPECIFIED);
                    if (imsCall != mCallExpectedToResume) {
                        mCallExpectedToResume = null;
                    }
                    // Leave mHoldSwitchingState as is for now -- we'll reset it
                    // in onCallTerminated, which will also dial the outgoing emergency call.
                } else if (mRingingCall.getState() == ImsPhoneCall.State.WAITING
                        && mHoldSwitchingState == HoldSwapState.HOLDING_TO_ANSWER_INCOMING) {
                    // If we issued a hold request in order to answer an incoming call, we need
                    // to tell Telecom that we can't actually answer the incoming call.
                    mHoldSwitchingState = HoldSwapState.INACTIVE;
                    mForegroundCall.switchWith(mBackgroundCall);
                    logHoldSwapState("onCallHoldFailed unable to answer waiting call");
                } else if (bgState == ImsPhoneCall.State.ACTIVE) {
                    mForegroundCall.switchWith(mBackgroundCall);

                    if (mPendingMO != null) {
                        mPendingMO.setDisconnectCause(DisconnectCause.ERROR_UNSPECIFIED);
                        sendEmptyMessageDelayed(EVENT_HANGUP_PENDINGMO, TIMEOUT_HANGUP_PENDINGMO);
                    }
                    if (imsCall != mCallExpectedToResume) {
                        mCallExpectedToResume = null;
                    }
                    mHoldSwitchingState = HoldSwapState.INACTIVE;
                }
                ImsPhoneConnection conn = findConnection(imsCall);
                if (conn != null) {
                    conn.onConnectionEvent(android.telecom.Connection.EVENT_CALL_HOLD_FAILED, null);
                }
                mPhone.notifySuppServiceFailed(Phone.SuppService.HOLD);
            }
            mMetrics.writeOnImsCallHoldFailed(mPhone.getPhoneId(), imsCall.getCallSession(),
                    reasonInfo);
        }

        @Override
        public void onCallResumed(ImsCall imsCall) {
            if (DBG) log("onCallResumed");

            // If we are the in midst of swapping FG and BG calls and the call we end up resuming
            // is not the one we expected, we likely had a resume failure and we need to swap the
            // FG and BG calls back.
            if (mHoldSwitchingState == HoldSwapState.SWAPPING_ACTIVE_AND_HELD
                    || mHoldSwitchingState == HoldSwapState.PENDING_RESUME_FOREGROUND_AFTER_FAILURE
                    || mHoldSwitchingState == HoldSwapState.PENDING_SINGLE_CALL_UNHOLD) {
                if (imsCall != mCallExpectedToResume) {
                    // If the call which resumed isn't as expected, we need to swap back to the
                    // previous configuration; the swap has failed.
                    if (DBG) {
                        log("onCallResumed : switching " + mForegroundCall + " with "
                                + mBackgroundCall);
                    }
                    mForegroundCall.switchWith(mBackgroundCall);
                } else {
                    // The call which resumed is the one we expected to resume, so we can clear out
                    // the mSwitchingFgAndBgCalls flag.
                    if (DBG) {
                        log("onCallResumed : expected call resumed.");
                    }
                }
                mHoldSwitchingState = HoldSwapState.INACTIVE;
                mCallExpectedToResume = null;
                logHoldSwapState("onCallResumed");
            }
            processCallStateChange(imsCall, ImsPhoneCall.State.ACTIVE,
                    DisconnectCause.NOT_DISCONNECTED);
            mMetrics.writeOnImsCallResumed(mPhone.getPhoneId(), imsCall.getCallSession());
        }

        @Override
        public void onCallResumeFailed(ImsCall imsCall, ImsReasonInfo reasonInfo) {
            if (mHoldSwitchingState == HoldSwapState.SWAPPING_ACTIVE_AND_HELD
                    || mHoldSwitchingState
                    == HoldSwapState.PENDING_RESUME_FOREGROUND_AFTER_FAILURE) {
                // If we are in the midst of swapping the FG and BG calls and
                // we got a resume fail, we need to swap back the FG and BG calls.
                // Since the FG call was held, will also try to resume the same.
                if (imsCall == mCallExpectedToResume) {
                    if (DBG) {
                        log("onCallResumeFailed : switching " + mForegroundCall + " with "
                                + mBackgroundCall);
                    }
                    mForegroundCall.switchWith(mBackgroundCall);
                    if (mForegroundCall.getState() == ImsPhoneCall.State.HOLDING) {
                        sendEmptyMessage(EVENT_RESUME_NOW_FOREGROUND_CALL);
                    }
                }

                //Call swap is done, reset the relevant variables
                mCallExpectedToResume = null;
                mHoldSwitchingState = HoldSwapState.INACTIVE;
                logHoldSwapState("onCallResumeFailed: multi calls");
            } else if (mHoldSwitchingState == HoldSwapState.PENDING_SINGLE_CALL_UNHOLD) {
                if (imsCall == mCallExpectedToResume) {
                    if (DBG) {
                        log("onCallResumeFailed: single call unhold case");
                    }
                    mForegroundCall.switchWith(mBackgroundCall);

                    mCallExpectedToResume = null;
                    mHoldSwitchingState = HoldSwapState.INACTIVE;
                    logHoldSwapState("onCallResumeFailed: single call");
                } else {
                    Rlog.w(LOG_TAG, "onCallResumeFailed: got a resume failed for a different call"
                            + " in the single call unhold case");
                }
            }
            mPhone.notifySuppServiceFailed(Phone.SuppService.RESUME);
            mMetrics.writeOnImsCallResumeFailed(mPhone.getPhoneId(), imsCall.getCallSession(),
                    reasonInfo);
        }

        @Override
        public void onCallResumeReceived(ImsCall imsCall) {
            if (DBG) log("onCallResumeReceived");
            ImsPhoneConnection conn = findConnection(imsCall);
            if (conn != null) {
                if (mOnHoldToneStarted) {
                    mPhone.stopOnHoldTone(conn);
                    mOnHoldToneStarted = false;
                }
                conn.onConnectionEvent(android.telecom.Connection.EVENT_CALL_REMOTELY_UNHELD, null);
            }

            boolean useVideoPauseWorkaround = mPhone.getContext().getResources().getBoolean(
                    com.android.internal.R.bool.config_useVideoPauseWorkaround);
            if (useVideoPauseWorkaround && mSupportPauseVideo &&
                    VideoProfile.isVideo(conn.getVideoState())) {
                // If we are using the video pause workaround, the vendor IMS code has issues
                // with video pause signalling.  In this case, when a call is remotely
                // held, the modem does not reliably change the video state of the call to be
                // paused.
                // As a workaround, we will turn on that bit now.
                conn.changeToUnPausedState();
            }

            SuppServiceNotification supp = new SuppServiceNotification();
            // Type of notification: 0 = MO; 1 = MT
            // Refer SuppServiceNotification class documentation.
            supp.notificationType = 1;
            supp.code = SuppServiceNotification.CODE_2_CALL_RETRIEVED;
            mPhone.notifySuppSvcNotification(supp);
            mMetrics.writeOnImsCallResumeReceived(mPhone.getPhoneId(), imsCall.getCallSession());
        }

        @Override
        public void onCallHoldReceived(ImsCall imsCall) {
            ImsPhoneCallTracker.this.onCallHoldReceived(imsCall);
        }

        @Override
        public void onCallSuppServiceReceived(ImsCall call,
                ImsSuppServiceNotification suppServiceInfo) {
            if (DBG) log("onCallSuppServiceReceived: suppServiceInfo=" + suppServiceInfo);

            SuppServiceNotification supp = new SuppServiceNotification();
            supp.notificationType = suppServiceInfo.notificationType;
            supp.code = suppServiceInfo.code;
            supp.index = suppServiceInfo.index;
            supp.number = suppServiceInfo.number;
            supp.history = suppServiceInfo.history;

            mPhone.notifySuppSvcNotification(supp);
        }

        @Override
        public void onCallMerged(final ImsCall call, final ImsCall peerCall, boolean swapCalls) {
            if (DBG) log("onCallMerged");

            ImsPhoneCall foregroundImsPhoneCall = findConnection(call).getCall();
            ImsPhoneConnection peerConnection = findConnection(peerCall);
            ImsPhoneCall peerImsPhoneCall = peerConnection == null ? null
                    : peerConnection.getCall();

            if (swapCalls) {
                switchAfterConferenceSuccess();
            }
            foregroundImsPhoneCall.merge(peerImsPhoneCall, ImsPhoneCall.State.ACTIVE);

            try {
                final ImsPhoneConnection conn = findConnection(call);
                log("onCallMerged: ImsPhoneConnection=" + conn);
                log("onCallMerged: CurrentVideoProvider=" + conn.getVideoProvider());
                setVideoCallProvider(conn, call);
                log("onCallMerged: CurrentVideoProvider=" + conn.getVideoProvider());
            } catch (Exception e) {
                loge("onCallMerged: exception " + e);
            }

            // After merge complete, update foreground as Active
            // and background call as Held, if background call exists
            processCallStateChange(mForegroundCall.getImsCall(), ImsPhoneCall.State.ACTIVE,
                    DisconnectCause.NOT_DISCONNECTED);
            if (peerConnection != null) {
                processCallStateChange(mBackgroundCall.getImsCall(), ImsPhoneCall.State.HOLDING,
                    DisconnectCause.NOT_DISCONNECTED);
            }

            // Check if the merge was requested by an existing conference call. In that
            // case, no further action is required.
            if (!call.isMergeRequestedByConf()) {
                log("onCallMerged :: calling onMultipartyStateChanged()");
                onMultipartyStateChanged(call, true);
            } else {
                log("onCallMerged :: Merge requested by existing conference.");
                // Reset the flag.
                call.resetIsMergeRequestedByConf(false);
            }
            logState();
        }

        @Override
        public void onCallMergeFailed(ImsCall call, ImsReasonInfo reasonInfo) {
            if (DBG) log("onCallMergeFailed reasonInfo=" + reasonInfo);

            // TODO: the call to notifySuppServiceFailed throws up the "merge failed" dialog
            // We should move this into the InCallService so that it is handled appropriately
            // based on the user facing UI.
            mPhone.notifySuppServiceFailed(Phone.SuppService.CONFERENCE);

            call.resetIsMergeRequestedByConf(false);

            // Start plumbing this even through Telecom so other components can take
            // appropriate action.
            ImsPhoneConnection conn = findConnection(call);
            if (conn != null) {
                conn.onConferenceMergeFailed();
                conn.handleMergeComplete();
            }
        }

        private void updateConferenceParticipantsTiming(List<ConferenceParticipant> participants) {
            for (ConferenceParticipant participant : participants) {
                // Every time participants are newly created from parcel, update their connect time.
                CacheEntry cachedConnectTime = findConnectionTimeUsePhoneNumber(participant);
                if (cachedConnectTime != null) {
                    participant.setConnectTime(cachedConnectTime.mConnectTime);
                    participant.setConnectElapsedTime(cachedConnectTime.mConnectElapsedTime);
                    participant.setCallDirection(cachedConnectTime.mCallDirection);
                }
            }
        }

        /**
         * Called when the state of IMS conference participant(s) has changed.
         *
         * @param call the call object that carries out the IMS call.
         * @param participants the participant(s) and their new state information.
         */
        @Override
        public void onConferenceParticipantsStateChanged(ImsCall call,
                List<ConferenceParticipant> participants) {
            if (DBG) log("onConferenceParticipantsStateChanged");

            ImsPhoneConnection conn = findConnection(call);
            if (conn != null) {
                updateConferenceParticipantsTiming(participants);
                conn.updateConferenceParticipants(participants);
            }
        }

        @Override
        public void onCallSessionTtyModeReceived(ImsCall call, int mode) {
            mPhone.onTtyModeReceived(mode);
        }

        @Override
        public void onCallHandover(ImsCall imsCall, int srcAccessTech, int targetAccessTech,
            ImsReasonInfo reasonInfo) {
            // Check with the DCTracker to see if data is enabled; there may be a case when
            // ImsPhoneCallTracker isn't being informed of the right data enabled state via its
            // registration, so we'll refresh now.
            boolean isDataEnabled = mPhone.getDefaultPhone().getDataEnabledSettings()
                    .isDataEnabled();

            if (DBG) {
                log("onCallHandover ::  srcAccessTech=" + srcAccessTech + ", targetAccessTech="
                        + targetAccessTech + ", reasonInfo=" + reasonInfo + ", dataEnabled="
                        + mIsDataEnabled + "/" + isDataEnabled + ", dataMetered="
                        + mIsViLteDataMetered);
            }
            if (mIsDataEnabled != isDataEnabled) {
                loge("onCallHandover: data enabled state doesn't match! (was=" + mIsDataEnabled
                        + ", actually=" + isDataEnabled);
                mIsDataEnabled = isDataEnabled;
            }

            // Only consider it a valid handover to WIFI if the source radio tech is known.
            boolean isHandoverToWifi = srcAccessTech != ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN
                    && srcAccessTech != ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN
                    && targetAccessTech == ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN;
            // Only consider it a handover from WIFI if the source and target radio tech is known.
            boolean isHandoverFromWifi =
                    srcAccessTech == ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN
                            && targetAccessTech != ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN
                            && targetAccessTech != ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN;

            ImsPhoneConnection conn = findConnection(imsCall);
            if (conn != null) {
                ImsPhoneCall imsPhoneCall = conn.getCall();
                if (imsPhoneCall != null) {
                    // We might be playing ringback on the handover connection; we should stop
                    // playing it at this point (otherwise it could play indefinitely).
                    imsPhoneCall.maybeStopRingback();
                }
                if (conn.getDisconnectCause() == DisconnectCause.NOT_DISCONNECTED) {
                    if (isHandoverToWifi) {
                        removeMessages(EVENT_CHECK_FOR_WIFI_HANDOVER);

                        if (mNotifyHandoverVideoFromLTEToWifi && mHasAttemptedStartOfCallHandover) {
                            // This is a handover which happened mid-call (ie not the start of call
                            // handover from LTE to WIFI), so we'll notify the InCall UI.
                            conn.onConnectionEvent(
                                    TelephonyManager.EVENT_HANDOVER_VIDEO_FROM_LTE_TO_WIFI, null);
                        }

                        // We are on WIFI now so no need to get notified of network availability.
                        unregisterForConnectivityChanges();
                    } else if (isHandoverFromWifi && imsCall.isVideoCall()) {
                        // A video call just dropped from WIFI to LTE; we want to be informed if a
                        // new WIFI
                        // network comes into range.
                        registerForConnectivityChanges();
                    }
                }

                if (isHandoverToWifi && mIsViLteDataMetered) {
                    conn.setLocalVideoCapable(true);
                }

                if (isHandoverFromWifi && imsCall.isVideoCall()) {
                    if (mIsViLteDataMetered) {
                        conn.setLocalVideoCapable(mIsDataEnabled);
                    }

                    if (mNotifyHandoverVideoFromWifiToLTE && mIsDataEnabled) {
                        if (conn.getDisconnectCause() == DisconnectCause.NOT_DISCONNECTED) {
                            log("onCallHandover :: notifying of WIFI to LTE handover.");
                            conn.onConnectionEvent(
                                    TelephonyManager.EVENT_HANDOVER_VIDEO_FROM_WIFI_TO_LTE, null);
                        } else {
                            // Call has already had a disconnect request issued by the user or is
                            // in the process of disconnecting; do not inform the UI of this as it
                            // is not relevant.
                            log("onCallHandover :: skip notify of WIFI to LTE handover for "
                                    + "disconnected call.");
                        }
                    }

                    if (!mIsDataEnabled && mIsViLteDataMetered) {
                        // Call was downgraded from WIFI to LTE and data is metered; downgrade the
                        // call now.
                        log("onCallHandover :: data is not enabled; attempt to downgrade.");
                        downgradeVideoCall(ImsReasonInfo.CODE_WIFI_LOST, conn);
                    }
                }
            } else {
                loge("onCallHandover :: connection null.");
            }
            // If there's a handover, then we're not in the "start of call" handover phase.
            if (!mHasAttemptedStartOfCallHandover) {
                mHasAttemptedStartOfCallHandover = true;
            }
            mMetrics.writeOnImsCallHandoverEvent(mPhone.getPhoneId(),
                    TelephonyCallSession.Event.Type.IMS_CALL_HANDOVER, imsCall.getCallSession(),
                    srcAccessTech, targetAccessTech, reasonInfo);
        }

        @Override
        public void onCallHandoverFailed(ImsCall imsCall, int srcAccessTech, int targetAccessTech,
            ImsReasonInfo reasonInfo) {
            if (DBG) {
                log("onCallHandoverFailed :: srcAccessTech=" + srcAccessTech +
                    ", targetAccessTech=" + targetAccessTech + ", reasonInfo=" + reasonInfo);
            }
            mMetrics.writeOnImsCallHandoverEvent(mPhone.getPhoneId(),
                    TelephonyCallSession.Event.Type.IMS_CALL_HANDOVER_FAILED,
                    imsCall.getCallSession(), srcAccessTech, targetAccessTech, reasonInfo);

            boolean isHandoverToWifi = srcAccessTech != ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN &&
                    targetAccessTech == ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN;
            ImsPhoneConnection conn = findConnection(imsCall);
            if (conn != null && isHandoverToWifi) {
                log("onCallHandoverFailed - handover to WIFI Failed");

                // If we know we failed to handover, don't check for failure in the future.
                removeMessages(EVENT_CHECK_FOR_WIFI_HANDOVER);

                if (imsCall.isVideoCall()
                        && conn.getDisconnectCause() == DisconnectCause.NOT_DISCONNECTED) {
                    // Start listening for a WIFI network to come into range for potential handover.
                    registerForConnectivityChanges();
                }

                if (mNotifyVtHandoverToWifiFail) {
                    // Only notify others if carrier config indicates to do so.
                    conn.onHandoverToWifiFailed();
                }
            }
            if (!mHasAttemptedStartOfCallHandover) {
                mHasAttemptedStartOfCallHandover = true;
            }
        }

        @Override
        public void onRttModifyRequestReceived(ImsCall imsCall) {
            ImsPhoneConnection conn = findConnection(imsCall);
            if (conn != null) {
                conn.onRttModifyRequestReceived();
            }
        }

        @Override
        public void onRttModifyResponseReceived(ImsCall imsCall, int status) {
            ImsPhoneConnection conn = findConnection(imsCall);
            if (conn != null) {
                conn.onRttModifyResponseReceived(status);
            }
        }

        @Override
        public void onRttMessageReceived(ImsCall imsCall, String message) {
            ImsPhoneConnection conn = findConnection(imsCall);
            if (conn != null) {
                conn.onRttMessageReceived(message);
            }
        }

        @Override
        public void onRttAudioIndicatorChanged(ImsCall imsCall, ImsStreamMediaProfile profile) {
          ImsPhoneConnection conn = findConnection(imsCall);
            if (conn != null) {
                conn.onRttAudioIndicatorChanged(profile);
            }
        }

        /**
         * Handles a change to the multiparty state for an {@code ImsCall}.  Notifies the associated
         * {@link ImsPhoneConnection} of the change.
         *
         * @param imsCall The IMS call.
         * @param isMultiParty {@code true} if the call became multiparty, {@code false}
         *      otherwise.
         */
        @Override
        public void onMultipartyStateChanged(ImsCall imsCall, boolean isMultiParty) {
            if (DBG) log("onMultipartyStateChanged to " + (isMultiParty ? "Y" : "N"));

            ImsPhoneConnection conn = findConnection(imsCall);
            if (conn != null) {
                conn.updateMultipartyState(isMultiParty);
            }
        }

        /**
         * Handles a change to the call quality for an {@code ImsCall}.
         * Notifies apps through the System API {@link PhoneStateListener#onCallAttributesChanged}.
         */
        @Override
        public void onCallQualityChanged(ImsCall imsCall, CallQuality callQuality) {
            // convert ServiceState.radioTech to TelephonyManager.NetworkType constant
            mPhone.onCallQualityChanged(callQuality,
                    ServiceState.rilRadioTechnologyToNetworkType(imsCall.getRadioTechnology()));
            String callId = imsCall.getSession().getCallId();
            CallQualityMetrics cqm = mCallQualityMetrics.get(callId);
            if (cqm == null) {
                cqm = new CallQualityMetrics(mPhone);
            }
            cqm.saveCallQuality(callQuality);
            mCallQualityMetrics.put(callId, cqm);
        }
    };

    /**
     * Listen to the IMS call state change
     */
    private ImsCall.Listener mImsUssdListener = new ImsCall.Listener() {
        @Override
        public void onCallStarted(ImsCall imsCall) {
            if (DBG) log("mImsUssdListener onCallStarted");

            if (imsCall == mUssdSession) {
                if (mPendingUssd != null) {
                    AsyncResult.forMessage(mPendingUssd);
                    mPendingUssd.sendToTarget();
                    mPendingUssd = null;
                }
            }
        }

        @Override
        public void onCallStartFailed(ImsCall imsCall, ImsReasonInfo reasonInfo) {
            if (DBG) log("mImsUssdListener onCallStartFailed reasonCode=" + reasonInfo.getCode());

            onCallTerminated(imsCall, reasonInfo);
        }

        @Override
        public void onCallTerminated(ImsCall imsCall, ImsReasonInfo reasonInfo) {
            if (DBG) log("mImsUssdListener onCallTerminated reasonCode=" + reasonInfo.getCode());
            removeMessages(EVENT_CHECK_FOR_WIFI_HANDOVER);
            mHasAttemptedStartOfCallHandover = false;
            unregisterForConnectivityChanges();

            if (imsCall == mUssdSession) {
                mUssdSession = null;
                if (mPendingUssd != null) {
                    CommandException ex =
                            new CommandException(CommandException.Error.GENERIC_FAILURE);
                    AsyncResult.forMessage(mPendingUssd, null, ex);
                    mPendingUssd.sendToTarget();
                    mPendingUssd = null;
                }
            }
            imsCall.close();
        }

        @Override
        public void onCallUssdMessageReceived(ImsCall call,
                int mode, String ussdMessage) {
            if (DBG) log("mImsUssdListener onCallUssdMessageReceived mode=" + mode);

            int ussdMode = -1;

            switch(mode) {
                case ImsCall.USSD_MODE_REQUEST:
                    ussdMode = CommandsInterface.USSD_MODE_REQUEST;
                    break;

                case ImsCall.USSD_MODE_NOTIFY:
                    ussdMode = CommandsInterface.USSD_MODE_NOTIFY;
                    break;
            }

            mPhone.onIncomingUSSD(ussdMode, ussdMessage);
        }
    };

    private final ImsMmTelManager.RegistrationCallback mImsRegistrationCallback =
            new ImsMmTelManager.RegistrationCallback() {

                @Override
                public void onRegistered(
                        @ImsRegistrationImplBase.ImsRegistrationTech int imsRadioTech) {
                    if (DBG) log("onImsConnected imsRadioTech=" + imsRadioTech);
                    mPhone.setServiceState(ServiceState.STATE_IN_SERVICE);
                    mPhone.setImsRegistered(true);
                    mMetrics.writeOnImsConnectionState(mPhone.getPhoneId(),
                            ImsConnectionState.State.CONNECTED, null);
                }

                @Override
                public void onRegistering(
                        @ImsRegistrationImplBase.ImsRegistrationTech int imsRadioTech) {
                    if (DBG) log("onImsProgressing imsRadioTech=" + imsRadioTech);
                    mPhone.setServiceState(ServiceState.STATE_OUT_OF_SERVICE);
                    mPhone.setImsRegistered(false);
                    mMetrics.writeOnImsConnectionState(mPhone.getPhoneId(),
                            ImsConnectionState.State.PROGRESSING, null);
                }

                @Override
                public void onUnregistered(ImsReasonInfo imsReasonInfo) {
                    if (DBG) log("onImsDisconnected imsReasonInfo=" + imsReasonInfo);
                    mPhone.setServiceState(ServiceState.STATE_OUT_OF_SERVICE);
                    mPhone.setImsRegistered(false);
                    mPhone.processDisconnectReason(imsReasonInfo);
                    mMetrics.writeOnImsConnectionState(mPhone.getPhoneId(),
                            ImsConnectionState.State.DISCONNECTED, imsReasonInfo);
                }

                @Override
                public void onSubscriberAssociatedUriChanged(Uri[] uris) {
                    if (DBG) log("registrationAssociatedUriChanged");
                    mPhone.setCurrentSubscriberUris(uris);
                }
            };

    private final ImsMmTelManager.CapabilityCallback mImsCapabilityCallback =
            new ImsMmTelManager.CapabilityCallback() {
                @Override
                public void onCapabilitiesStatusChanged(
                        MmTelFeature.MmTelCapabilities capabilities) {
                    if (DBG) log("onCapabilitiesStatusChanged: " + capabilities);
                    SomeArgs args = SomeArgs.obtain();
                    args.arg1 = capabilities;
                    // Remove any pending updates; they're already stale, so no need to process
                    // them.
                    removeMessages(EVENT_ON_FEATURE_CAPABILITY_CHANGED);
                    obtainMessage(EVENT_ON_FEATURE_CAPABILITY_CHANGED, args).sendToTarget();
                }
            };

    private ImsConfigListener.Stub mImsConfigListener = new ImsConfigListener.Stub() {
        @Override
        public void onGetFeatureResponse(int feature, int network, int value, int status) {}

        @Override
        public void onSetFeatureResponse(int feature, int network, int value, int status) {
            mMetrics.writeImsSetFeatureValue(mPhone.getPhoneId(), feature, network, value);
        }

        @Override
        public void onGetVideoQuality(int status, int quality) {}

        @Override
        public void onSetVideoQuality(int status) {}

    };

    private final ProvisioningManager.Callback mConfigCallback =
            new ProvisioningManager.Callback() {
        @Override
        public void onProvisioningIntChanged(int item, int value) {
            sendConfigChangedIntent(item, Integer.toString(value));
        }

        @Override
        public void onProvisioningStringChanged(int item, String value) {
            sendConfigChangedIntent(item, value);
        }

        // send IMS_CONFIG_CHANGED intent for older services that do not implement the new callback
        // interface.
        private void sendConfigChangedIntent(int item, String value) {
            log("sendConfigChangedIntent - [" + item + ", " + value + "]");
            Intent configChangedIntent = new Intent(ImsConfig.ACTION_IMS_CONFIG_CHANGED);
            configChangedIntent.putExtra(ImsConfig.EXTRA_CHANGED_ITEM, item);
            configChangedIntent.putExtra(ImsConfig.EXTRA_NEW_VALUE, value);
            if (mPhone != null && mPhone.getContext() != null) {
                mPhone.getContext().sendBroadcast(configChangedIntent);
            }
        }
    };

    public void sendCallStartFailedDisconnect(ImsCall imsCall, ImsReasonInfo reasonInfo) {
        mPendingMO = null;
        ImsPhoneConnection conn = findConnection(imsCall);
        Call.State callState;
        if (conn != null) {
            callState = conn.getState();
        } else {
            // Need to fall back in case connection is null; it shouldn't be, but a sane
            // fallback is to assume we're dialing.  This state is only used to
            // determine which disconnect string to show in the case of a low battery
            // disconnect.
            callState = Call.State.DIALING;
        }
        int cause = getDisconnectCauseFromReasonInfo(reasonInfo, callState);

        processCallStateChange(imsCall, ImsPhoneCall.State.DISCONNECTED, cause);
        mPhone.notifyImsReason(reasonInfo);
    }

    @UnsupportedAppUsage
    public ImsUtInterface getUtInterface() throws ImsException {
        if (mImsManager == null) {
            throw getImsManagerIsNullException();
        }

        ImsUtInterface ut = mImsManager.getSupplementaryServiceConfiguration();
        return ut;
    }

    private void transferHandoverConnections(ImsPhoneCall call) {
        if (call.mConnections != null) {
            for (Connection c : call.mConnections) {
                c.mPreHandoverState = call.mState;
                log ("Connection state before handover is " + c.getStateBeforeHandover());
            }
        }
        if (mHandoverCall.mConnections == null ) {
            mHandoverCall.mConnections = call.mConnections;
        } else { // Multi-call SRVCC
            mHandoverCall.mConnections.addAll(call.mConnections);
        }
        if (mHandoverCall.mConnections != null) {
            if (call.getImsCall() != null) {
                call.getImsCall().close();
            }
            for (Connection c : mHandoverCall.mConnections) {
                ((ImsPhoneConnection)c).changeParent(mHandoverCall);
                ((ImsPhoneConnection)c).releaseWakeLock();
            }
        }
        if (call.getState().isAlive()) {
            log ("Call is alive and state is " + call.mState);
            mHandoverCall.mState = call.mState;
        }
        call.mConnections.clear();
        call.mState = ImsPhoneCall.State.IDLE;
        if (mPendingMO != null) {
            // If the call is handed over before moving to alerting (i.e. e911 CSFB redial), clear
            // pending MO here.
            logi("pending MO on handover, clearing...");
            mPendingMO = null;
        }
    }

    /* package */
    void notifySrvccState(Call.SrvccState state) {
        if (DBG) log("notifySrvccState state=" + state);

        mSrvccState = state;

        if (mSrvccState == Call.SrvccState.COMPLETED) {
            resetState();
            transferHandoverConnections(mForegroundCall);
            transferHandoverConnections(mBackgroundCall);
            transferHandoverConnections(mRingingCall);
        }
    }

    private void resetState() {
        mIsInEmergencyCall = false;
    }

    //****** Overridden from Handler

    @Override
    public void
    handleMessage (Message msg) {
        AsyncResult ar;
        if (DBG) log("handleMessage what=" + msg.what);

        switch (msg.what) {
            case EVENT_HANGUP_PENDINGMO:
                if (mPendingMO != null) {
                    mPendingMO.onDisconnect();
                    removeConnection(mPendingMO);
                    mPendingMO = null;
                }
                mPendingIntentExtras = null;
                updatePhoneState();
                mPhone.notifyPreciseCallStateChanged();
                break;
            case EVENT_RESUME_NOW_FOREGROUND_CALL:
                try {
                    resumeForegroundCall();
                } catch (ImsException e) {
                    if (Phone.DEBUG_PHONE) {
                        loge("handleMessage EVENT_RESUME_NOW_FOREGROUND_CALL exception=" + e);
                    }
                }
                break;
            case EVENT_ANSWER_WAITING_CALL:
                try {
                    answerWaitingCall();
                } catch (ImsException e) {
                    if (Phone.DEBUG_PHONE) {
                        loge("handleMessage EVENT_ANSWER_WAITING_CALL exception=" + e);
                    }
                }
                break;
            case EVENT_DIAL_PENDINGMO:
                dialInternal(mPendingMO, mClirMode, mPendingCallVideoState, mPendingIntentExtras);
                mPendingIntentExtras = null;
                break;

            case EVENT_EXIT_ECBM_BEFORE_PENDINGMO:
                if (mPendingMO != null) {
                    //Send ECBM exit request
                    try {
                        getEcbmInterface().exitEmergencyCallbackMode();
                        mPhone.setOnEcbModeExitResponse(this, EVENT_EXIT_ECM_RESPONSE_CDMA, null);
                        pendingCallClirMode = mClirMode;
                        pendingCallInEcm = true;
                    } catch (ImsException e) {
                        e.printStackTrace();
                        mPendingMO.setDisconnectCause(DisconnectCause.ERROR_UNSPECIFIED);
                        sendEmptyMessageDelayed(EVENT_HANGUP_PENDINGMO, TIMEOUT_HANGUP_PENDINGMO);
                    }
                }
                break;

            case EVENT_EXIT_ECM_RESPONSE_CDMA:
                // no matter the result, we still do the same here
                if (pendingCallInEcm) {
                    dialInternal(mPendingMO, pendingCallClirMode,
                            mPendingCallVideoState, mPendingIntentExtras);
                    mPendingIntentExtras = null;
                    pendingCallInEcm = false;
                }
                mPhone.unsetOnEcbModeExitResponse(this);
                break;
            case EVENT_VT_DATA_USAGE_UPDATE:
                ar = (AsyncResult) msg.obj;
                ImsCall call = (ImsCall) ar.userObj;
                Long usage = (long) ar.result;
                log("VT data usage update. usage = " + usage + ", imsCall = " + call);
                if (usage > 0) {
                    updateVtDataUsage(call, usage);
                }
                break;
            case EVENT_DATA_ENABLED_CHANGED:
                ar = (AsyncResult) msg.obj;
                if (ar.result instanceof Pair) {
                    Pair<Boolean, Integer> p = (Pair<Boolean, Integer>) ar.result;
                    onDataEnabledChanged(p.first, p.second);
                }
                break;
            case EVENT_CHECK_FOR_WIFI_HANDOVER:
                if (msg.obj instanceof ImsCall) {
                    ImsCall imsCall = (ImsCall) msg.obj;
                    if (imsCall != mForegroundCall.getImsCall()) {
                        Rlog.i(LOG_TAG, "handoverCheck: no longer FG; check skipped.");
                        unregisterForConnectivityChanges();
                        // Handover check and its not the foreground call any more.
                        return;
                    }
                    if (!mHasAttemptedStartOfCallHandover) {
                        mHasAttemptedStartOfCallHandover = true;
                    }
                    if (!imsCall.isWifiCall()) {
                        // Call did not handover to wifi, notify of handover failure.
                        ImsPhoneConnection conn = findConnection(imsCall);
                        if (conn != null) {
                            Rlog.i(LOG_TAG, "handoverCheck: handover failed.");
                            conn.onHandoverToWifiFailed();
                        }

                        if (imsCall.isVideoCall()
                                && conn.getDisconnectCause() == DisconnectCause.NOT_DISCONNECTED) {
                            registerForConnectivityChanges();
                        }
                    }
                }
                break;
            case EVENT_ON_FEATURE_CAPABILITY_CHANGED: {
                SomeArgs args = (SomeArgs) msg.obj;
                try {
                    ImsFeature.Capabilities capabilities = (ImsFeature.Capabilities) args.arg1;
                    handleFeatureCapabilityChanged(capabilities);
                } finally {
                    args.recycle();
                }
                break;
            }
            case EVENT_SUPP_SERVICE_INDICATION: {
                ar = (AsyncResult) msg.obj;
                ImsPhoneMmiCode mmiCode = new ImsPhoneMmiCode(mPhone);
                try {
                    mmiCode.setIsSsInfo(true);
                    mmiCode.processImsSsData(ar);
                } catch (ImsException e) {
                    Rlog.e(LOG_TAG, "Exception in parsing SS Data: " + e);
                }
                break;
            }
            case EVENT_REDIAL_WIFI_E911_CALL: {
                Pair<ImsCall, ImsReasonInfo> callInfo =
                        (Pair<ImsCall, ImsReasonInfo>) ((AsyncResult) msg.obj).userObj;
                removeMessages(EVENT_REDIAL_WIFI_E911_TIMEOUT);
                mPhone.getDefaultPhone().getServiceStateTracker()
                        .unregisterForNetworkAttached(this);
                ImsPhoneConnection oldConnection = findConnection(callInfo.first);
                if (oldConnection == null) {
                    sendCallStartFailedDisconnect(callInfo.first, callInfo.second);
                    break;
                }
                mForegroundCall.detach(oldConnection);
                removeConnection(oldConnection);
                try {
                    Connection newConnection =
                            mPhone.getDefaultPhone().dial(mLastDialString, mLastDialArgs);
                    oldConnection.onOriginalConnectionReplaced(newConnection);
                } catch (CallStateException e) {
                    sendCallStartFailedDisconnect(callInfo.first, callInfo.second);
                }
                break;
            }
            case EVENT_REDIAL_WIFI_E911_TIMEOUT: {
                Pair<ImsCall, ImsReasonInfo> callInfo = (Pair<ImsCall, ImsReasonInfo>) msg.obj;
                mPhone.getDefaultPhone().getServiceStateTracker()
                        .unregisterForNetworkAttached(this);
                removeMessages(EVENT_REDIAL_WIFI_E911_CALL);
                sendCallStartFailedDisconnect(callInfo.first, callInfo.second);
                break;
            }
        }
    }

    /**
     * Update video call data usage
     *
     * @param call The IMS call
     * @param dataUsage The aggregated data usage for the call
     */
    private void updateVtDataUsage(ImsCall call, long dataUsage) {
        long oldUsage = 0L;
        if (mVtDataUsageMap.containsKey(call.uniqueId)) {
            oldUsage = mVtDataUsageMap.get(call.uniqueId);
        }

        long delta = dataUsage - oldUsage;
        mVtDataUsageMap.put(call.uniqueId, dataUsage);

        log("updateVtDataUsage: call=" + call + ", delta=" + delta);

        long currentTime = SystemClock.elapsedRealtime();
        int isRoaming = mPhone.getServiceState().getDataRoaming() ? 1 : 0;

        // Create the snapshot of total video call data usage.
        NetworkStats vtDataUsageSnapshot = new NetworkStats(currentTime, 1);
        vtDataUsageSnapshot.combineAllValues(mVtDataUsageSnapshot);
        // Since the modem only reports the total vt data usage rather than rx/tx separately,
        // the only thing we can do here is splitting the usage into half rx and half tx.
        // Uid -1 indicates this is for the overall device data usage.
        vtDataUsageSnapshot.combineValues(new NetworkStats.Entry(
                NetworkStatsService.VT_INTERFACE, -1, NetworkStats.SET_FOREGROUND,
                NetworkStats.TAG_NONE, NetworkStats.METERED_YES, isRoaming,
                NetworkStats.DEFAULT_NETWORK_YES, delta / 2, 0, delta / 2, 0, 0));
        mVtDataUsageSnapshot = vtDataUsageSnapshot;

        // Create the snapshot of video call data usage per dialer. combineValues will create
        // a separate entry if uid is different from the previous snapshot.
        NetworkStats vtDataUsageUidSnapshot = new NetworkStats(currentTime, 1);
        vtDataUsageUidSnapshot.combineAllValues(mVtDataUsageUidSnapshot);

        // The dialer uid might not be initialized correctly during boot up due to telecom service
        // not ready or its default dialer cache not ready. So we double check again here to see if
        // default dialer uid is really not available.
        if (mDefaultDialerUid.get() == NetworkStats.UID_ALL) {
            final TelecomManager telecomManager =
                    (TelecomManager) mPhone.getContext().getSystemService(Context.TELECOM_SERVICE);
            mDefaultDialerUid.set(
                    getPackageUid(mPhone.getContext(), telecomManager.getDefaultDialerPackage()));
        }

        // Since the modem only reports the total vt data usage rather than rx/tx separately,
        // the only thing we can do here is splitting the usage into half rx and half tx.
        vtDataUsageUidSnapshot.combineValues(new NetworkStats.Entry(
                NetworkStatsService.VT_INTERFACE, mDefaultDialerUid.get(),
                NetworkStats.SET_FOREGROUND, NetworkStats.TAG_NONE, NetworkStats.METERED_YES,
                isRoaming, NetworkStats.DEFAULT_NETWORK_YES, delta / 2, 0, delta / 2, 0, 0));
        mVtDataUsageUidSnapshot = vtDataUsageUidSnapshot;
    }

    @UnsupportedAppUsage
    @Override
    protected void log(String msg) {
        Rlog.d(LOG_TAG, "[" + mPhone.getPhoneId() + "] " + msg);
    }

    @UnsupportedAppUsage
    protected void loge(String msg) {
        Rlog.e(LOG_TAG, "[" + mPhone.getPhoneId() + "] " + msg);
    }

    void logi(String msg) {
        Rlog.i(LOG_TAG, "[" + mPhone.getPhoneId() + "] " + msg);
    }

    void logHoldSwapState(String loc) {
        String holdSwapState = "???";
        switch (mHoldSwitchingState) {
            case INACTIVE:
                holdSwapState = "INACTIVE";
                break;
            case PENDING_SINGLE_CALL_HOLD:
                holdSwapState = "PENDING_SINGLE_CALL_HOLD";
                break;
            case PENDING_SINGLE_CALL_UNHOLD:
                holdSwapState = "PENDING_SINGLE_CALL_UNHOLD";
                break;
            case SWAPPING_ACTIVE_AND_HELD:
                holdSwapState = "SWAPPING_ACTIVE_AND_HELD";
                break;
            case HOLDING_TO_ANSWER_INCOMING:
                holdSwapState = "HOLDING_TO_ANSWER_INCOMING";
                break;
            case PENDING_RESUME_FOREGROUND_AFTER_FAILURE:
                holdSwapState = "PENDING_RESUME_FOREGROUND_AFTER_FAILURE";
                break;
            case HOLDING_TO_DIAL_OUTGOING:
                holdSwapState = "HOLDING_TO_DIAL_OUTGOING";
                break;
        }
        logi("holdSwapState set to " + holdSwapState + " at " + loc);
    }

    /**
     * Logs the current state of the ImsPhoneCallTracker.  Useful for debugging issues with
     * call tracking.
     */
    /* package */
    void logState() {
        if (!VERBOSE_STATE_LOGGING) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Current IMS PhoneCall State:\n");
        sb.append(" Foreground: ");
        sb.append(mForegroundCall);
        sb.append("\n");
        sb.append(" Background: ");
        sb.append(mBackgroundCall);
        sb.append("\n");
        sb.append(" Ringing: ");
        sb.append(mRingingCall);
        sb.append("\n");
        sb.append(" Handover: ");
        sb.append(mHandoverCall);
        sb.append("\n");
        Rlog.v(LOG_TAG, sb.toString());
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("ImsPhoneCallTracker extends:");
        super.dump(fd, pw, args);
        pw.println(" mVoiceCallEndedRegistrants=" + mVoiceCallEndedRegistrants);
        pw.println(" mVoiceCallStartedRegistrants=" + mVoiceCallStartedRegistrants);
        pw.println(" mRingingCall=" + mRingingCall);
        pw.println(" mForegroundCall=" + mForegroundCall);
        pw.println(" mBackgroundCall=" + mBackgroundCall);
        pw.println(" mHandoverCall=" + mHandoverCall);
        pw.println(" mPendingMO=" + mPendingMO);
        //pw.println(" mHangupPendingMO=" + mHangupPendingMO);
        pw.println(" mPhone=" + mPhone);
        pw.println(" mDesiredMute=" + mDesiredMute);
        pw.println(" mState=" + mState);
        pw.println(" mMmTelCapabilities=" + mMmTelCapabilities);
        pw.println(" mDefaultDialerUid=" + mDefaultDialerUid.get());
        pw.println(" mVtDataUsageSnapshot=" + mVtDataUsageSnapshot);
        pw.println(" mVtDataUsageUidSnapshot=" + mVtDataUsageUidSnapshot);
        pw.println(" mCallQualityMetrics=" + mCallQualityMetrics);
        pw.println(" mCallQualityMetricsHistory=" + mCallQualityMetricsHistory);

        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");

        try {
            if (mImsManager != null) {
                mImsManager.dump(fd, pw, args);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (mConnections != null && mConnections.size() > 0) {
            pw.println("mConnections:");
            for (int i = 0; i < mConnections.size(); i++) {
                pw.println("  [" + i + "]: " + mConnections.get(i));
            }
        }
    }

    @Override
    protected void handlePollCalls(AsyncResult ar) {
    }

    /* package */
    ImsEcbm getEcbmInterface() throws ImsException {
        if (mImsManager == null) {
            throw getImsManagerIsNullException();
        }

        ImsEcbm ecbm = mImsManager.getEcbmInterface();
        return ecbm;
    }

    /* package */
    ImsMultiEndpoint getMultiEndpointInterface() throws ImsException {
        if (mImsManager == null) {
            throw getImsManagerIsNullException();
        }

        try {
            return mImsManager.getMultiEndpointInterface();
        } catch (ImsException e) {
            if (e.getCode() == ImsReasonInfo.CODE_MULTIENDPOINT_NOT_SUPPORTED) {
                return null;
            } else {
                throw e;
            }

        }
    }

    public boolean isInEmergencyCall() {
        return mIsInEmergencyCall;
    }

    /**
     * @return true if the IMS capability for the specified registration technology is currently
     * available.
     */
    public boolean isImsCapabilityAvailable(int capability, int regTech) {
        return (getImsRegistrationTech() == regTech) && mMmTelCapabilities.isCapable(capability);
    }

    public boolean isVolteEnabled() {
        boolean isRadioTechLte = getImsRegistrationTech()
                == ImsRegistrationImplBase.REGISTRATION_TECH_LTE;
        return isRadioTechLte && mMmTelCapabilities.isCapable(
                MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE);
    }

    public boolean isVowifiEnabled() {
        boolean isRadioTechIwlan = getImsRegistrationTech()
                == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;
        return isRadioTechIwlan && mMmTelCapabilities.isCapable(
                MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE);
    }

    public boolean isVideoCallEnabled() {
        return mMmTelCapabilities.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO);
    }

    @Override
    public PhoneConstants.State getState() {
        return mState;
    }

    public int getImsRegistrationTech() {
        if (mImsManager != null) {
            return mImsManager.getRegistrationTech();
        }
        return ImsRegistrationImplBase.REGISTRATION_TECH_NONE;
    }

    private void retryGetImsService() {
        // The binder connection is already up. Do not try to get it again.
        if (mImsManager.isServiceAvailable()) {
            return;
        }

        mImsManagerConnector.connect();
    }

    private void setVideoCallProvider(ImsPhoneConnection conn, ImsCall imsCall)
            throws RemoteException {
        IImsVideoCallProvider imsVideoCallProvider =
                imsCall.getCallSession().getVideoCallProvider();
        if (imsVideoCallProvider != null) {
            // TODO: Remove this when we can better formalize the format of session modify requests.
            boolean useVideoPauseWorkaround = mPhone.getContext().getResources().getBoolean(
                    com.android.internal.R.bool.config_useVideoPauseWorkaround);

            ImsVideoCallProviderWrapper imsVideoCallProviderWrapper =
                    new ImsVideoCallProviderWrapper(imsVideoCallProvider);
            if (useVideoPauseWorkaround) {
                imsVideoCallProviderWrapper.setUseVideoPauseWorkaround(useVideoPauseWorkaround);
            }
            conn.setVideoProvider(imsVideoCallProviderWrapper);
            imsVideoCallProviderWrapper.registerForDataUsageUpdate
                    (this, EVENT_VT_DATA_USAGE_UPDATE, imsCall);
            imsVideoCallProviderWrapper.addImsVideoProviderCallback(conn);
        }
    }

    public boolean isUtEnabled() {
        return mMmTelCapabilities.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_UT);
    }

    /**
     * Given a call subject, removes any characters considered by the current carrier to be
     * invalid, as well as escaping (using \) any characters which the carrier requires to be
     * escaped.
     *
     * @param callSubject The call subject.
     * @return The call subject with invalid characters removed and escaping applied as required.
     */
    private String cleanseInstantLetteringMessage(String callSubject) {
        if (TextUtils.isEmpty(callSubject)) {
            return callSubject;
        }

        // Get the carrier config for the current sub.
        CarrierConfigManager configMgr = (CarrierConfigManager)
                mPhone.getContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
        // Bail if we can't find the carrier config service.
        if (configMgr == null) {
            return callSubject;
        }

        PersistableBundle carrierConfig = configMgr.getConfigForSubId(mPhone.getSubId());
        // Bail if no carrier config found.
        if (carrierConfig == null) {
            return callSubject;
        }

        // Try to replace invalid characters
        String invalidCharacters = carrierConfig.getString(
                CarrierConfigManager.KEY_CARRIER_INSTANT_LETTERING_INVALID_CHARS_STRING);
        if (!TextUtils.isEmpty(invalidCharacters)) {
            callSubject = callSubject.replaceAll(invalidCharacters, "");
        }

        // Try to escape characters which need to be escaped.
        String escapedCharacters = carrierConfig.getString(
                CarrierConfigManager.KEY_CARRIER_INSTANT_LETTERING_ESCAPED_CHARS_STRING);
        if (!TextUtils.isEmpty(escapedCharacters)) {
            callSubject = escapeChars(escapedCharacters, callSubject);
        }
        return callSubject;
    }

    /**
     * Given a source string, return a string where a set of characters are escaped using the
     * backslash character.
     *
     * @param toEscape The characters to escape with a backslash.
     * @param source The source string.
     * @return The source string with characters escaped.
     */
    private String escapeChars(String toEscape, String source) {
        StringBuilder escaped = new StringBuilder();
        for (char c : source.toCharArray()) {
            if (toEscape.contains(Character.toString(c))) {
                escaped.append("\\");
            }
            escaped.append(c);
        }

        return escaped.toString();
    }

    /**
     * Initiates a pull of an external call.
     *
     * Initiates a pull by making a dial request with the {@link ImsCallProfile#EXTRA_IS_CALL_PULL}
     * extra specified.  We call {@link ImsPhone#notifyUnknownConnection(Connection)} which notifies
     * Telecom of the new dialed connection.  The
     * {@code PstnIncomingCallNotifier#maybeSwapWithUnknownConnection} logic ensures that the new
     * {@link ImsPhoneConnection} resulting from the dial gets swapped with the
     * {@link ImsExternalConnection}, which effectively makes the external call become a regular
     * call.  Magic!
     *
     * @param number The phone number of the call to be pulled.
     * @param videoState The desired video state of the pulled call.
     * @param dialogId The {@link ImsExternalConnection#getCallId()} dialog id associated with the
     *                 call which is being pulled.
     */
    @Override
    public void pullExternalCall(String number, int videoState, int dialogId) {
        Bundle extras = new Bundle();
        extras.putBoolean(ImsCallProfile.EXTRA_IS_CALL_PULL, true);
        extras.putInt(ImsExternalCallTracker.EXTRA_IMS_EXTERNAL_CALL_ID, dialogId);
        try {
            Connection connection = dial(number, videoState, extras);
            mPhone.notifyUnknownConnection(connection);
        } catch (CallStateException e) {
            loge("pullExternalCall failed - " + e);
        }
    }

    private ImsException getImsManagerIsNullException() {
        return new ImsException("no ims manager", ImsReasonInfo.CODE_LOCAL_ILLEGAL_STATE);
    }

    /**
     * Determines if answering an incoming call will cause the active call to be disconnected.
     * <p>
     * This will be the case if
     * {@link CarrierConfigManager#KEY_DROP_VIDEO_CALL_WHEN_ANSWERING_AUDIO_CALL_BOOL} is
     * {@code true} for the carrier, the active call is a video call over WIFI, and the incoming
     * call is an audio call.
     *
     * @param activeCall The active call.
     * @param incomingCall The incoming call.
     * @return {@code true} if answering the incoming call will cause the active call to be
     *      disconnected, {@code false} otherwise.
     */
    private boolean shouldDisconnectActiveCallOnAnswer(ImsCall activeCall,
            ImsCall incomingCall) {

        if (activeCall == null || incomingCall == null) {
            return false;
        }

        if (!mDropVideoCallWhenAnsweringAudioCall) {
            return false;
        }

        boolean isActiveCallVideo = activeCall.isVideoCall() ||
                (mTreatDowngradedVideoCallsAsVideoCalls && activeCall.wasVideoCall());
        boolean isActiveCallOnWifi = activeCall.isWifiCall();
        boolean isVoWifiEnabled = mImsManager.isWfcEnabledByPlatform()
                && mImsManager.isWfcEnabledByUser();
        boolean isIncomingCallAudio = !incomingCall.isVideoCall();
        log("shouldDisconnectActiveCallOnAnswer : isActiveCallVideo=" + isActiveCallVideo +
                " isActiveCallOnWifi=" + isActiveCallOnWifi + " isIncomingCallAudio=" +
                isIncomingCallAudio + " isVowifiEnabled=" + isVoWifiEnabled);

        return isActiveCallVideo && isActiveCallOnWifi && isIncomingCallAudio && !isVoWifiEnabled;
    }

    /**
     * Get aggregated video call data usage since boot.
     *
     * @param perUidStats True if requesting data usage per uid, otherwise overall usage.
     * @return Snapshot of video call data usage
     */
    public NetworkStats getVtDataUsage(boolean perUidStats) {

        // If there is an ongoing VT call, request the latest VT usage from the modem. The latest
        // usage will return asynchronously so it won't be counted in this round, but it will be
        // eventually counted when next getVtDataUsage is called.
        if (mState != PhoneConstants.State.IDLE) {
            for (ImsPhoneConnection conn : mConnections) {
                android.telecom.Connection.VideoProvider videoProvider = conn.getVideoProvider();
                if (videoProvider != null) {
                    videoProvider.onRequestConnectionDataUsage();
                }
            }
        }

        return perUidStats ? mVtDataUsageUidSnapshot : mVtDataUsageSnapshot;
    }

    public void registerPhoneStateListener(PhoneStateListener listener) {
        mPhoneStateListeners.add(listener);
    }

    public void unregisterPhoneStateListener(PhoneStateListener listener) {
        mPhoneStateListeners.remove(listener);
    }

    /**
     * Notifies local telephony listeners of changes to the IMS phone state.
     *
     * @param oldState The old state.
     * @param newState The new state.
     */
    private void notifyPhoneStateChanged(PhoneConstants.State oldState,
            PhoneConstants.State newState) {

        for (PhoneStateListener listener : mPhoneStateListeners) {
            listener.onPhoneStateChanged(oldState, newState);
        }
    }

    /** Modify video call to a new video state.
     *
     * @param imsCall IMS call to be modified
     * @param newVideoState New video state. (Refer to VideoProfile)
     */
    private void modifyVideoCall(ImsCall imsCall, int newVideoState) {
        ImsPhoneConnection conn = findConnection(imsCall);
        if (conn != null) {
            int oldVideoState = conn.getVideoState();
            if (conn.getVideoProvider() != null) {
                conn.getVideoProvider().onSendSessionModifyRequest(
                        new VideoProfile(oldVideoState), new VideoProfile(newVideoState));
            }
        }
    }

    public boolean isViLteDataMetered() {
        return mIsViLteDataMetered;
    }

    /**
     * Handler of data enabled changed event
     * @param enabled True if data is enabled, otherwise disabled.
     * @param reason Reason for data enabled/disabled. See {@link DataEnabledChangedReason}.
     */
    private void onDataEnabledChanged(boolean enabled, @DataEnabledChangedReason int reason) {

        log("onDataEnabledChanged: enabled=" + enabled + ", reason=" + reason);

        mIsDataEnabled = enabled;

        if (!mIsViLteDataMetered) {
            log("Ignore data " + ((enabled) ? "enabled" : "disabled") + " - carrier policy "
                    + "indicates that data is not metered for ViLTE calls.");
            return;
        }

        // Inform connections that data has been disabled to ensure we turn off video capability
        // if this is an LTE call.
        for (ImsPhoneConnection conn : mConnections) {
            ImsCall imsCall = conn.getImsCall();
            boolean isLocalVideoCapable = enabled || (imsCall != null && imsCall.isWifiCall());
            conn.setLocalVideoCapable(isLocalVideoCapable);
        }

        int reasonCode;
        if (reason == DataEnabledSettings.REASON_POLICY_DATA_ENABLED) {
            reasonCode = ImsReasonInfo.CODE_DATA_LIMIT_REACHED;
        } else if (reason == DataEnabledSettings.REASON_USER_DATA_ENABLED) {
            reasonCode = ImsReasonInfo.CODE_DATA_DISABLED;
        } else {
            // Unexpected code, default to data disabled.
            reasonCode = ImsReasonInfo.CODE_DATA_DISABLED;
        }

        // Potentially send connection events so the InCall UI knows that video calls are being
        // downgraded due to data being enabled/disabled.
        maybeNotifyDataDisabled(enabled, reasonCode);
        // Handle video state changes required as a result of data being enabled/disabled.
        handleDataEnabledChange(enabled, reasonCode);

        // We do not want to update the ImsConfig for REASON_REGISTERED, since it can happen before
        // the carrier config has loaded and will deregister IMS.
        if (!mShouldUpdateImsConfigOnDisconnect
                && reason != DataEnabledSettings.REASON_REGISTERED && mCarrierConfigLoaded) {
            // This will call into updateVideoCallFeatureValue and eventually all clients will be
            // asynchronously notified that the availability of VT over LTE has changed.
            if (mImsManager != null) {
                mImsManager.updateImsServiceConfig(true);
            }
        }
    }

    private void maybeNotifyDataDisabled(boolean enabled, int reasonCode) {
        if (!enabled) {
            // If data is disabled while there are ongoing VT calls which are not taking place over
            // wifi, then they should be disconnected to prevent the user from incurring further
            // data charges.
            for (ImsPhoneConnection conn : mConnections) {
                ImsCall imsCall = conn.getImsCall();
                if (imsCall != null && imsCall.isVideoCall() && !imsCall.isWifiCall()) {
                    if (conn.hasCapabilities(
                            Connection.Capability.SUPPORTS_DOWNGRADE_TO_VOICE_LOCAL |
                                    Connection.Capability.SUPPORTS_DOWNGRADE_TO_VOICE_REMOTE)) {

                        // If the carrier supports downgrading to voice, then we can simply issue a
                        // downgrade to voice instead of terminating the call.
                        if (reasonCode == ImsReasonInfo.CODE_DATA_DISABLED) {
                            conn.onConnectionEvent(TelephonyManager.EVENT_DOWNGRADE_DATA_DISABLED,
                                    null);
                        } else if (reasonCode == ImsReasonInfo.CODE_DATA_LIMIT_REACHED) {
                            conn.onConnectionEvent(
                                    TelephonyManager.EVENT_DOWNGRADE_DATA_LIMIT_REACHED, null);
                        }
                    }
                }
            }
        }
    }

    /**
     * Handles changes to the enabled state of mobile data.
     * When data is disabled, handles auto-downgrade of video calls over LTE.
     * When data is enabled, handled resuming of video calls paused when data was disabled.
     * @param enabled {@code true} if mobile data is enabled, {@code false} if mobile data is
     *                            disabled.
     * @param reasonCode The {@link ImsReasonInfo} code for the data enabled state change.
     */
    private void handleDataEnabledChange(boolean enabled, int reasonCode) {
        if (!enabled) {
            // If data is disabled while there are ongoing VT calls which are not taking place over
            // wifi, then they should be disconnected to prevent the user from incurring further
            // data charges.
            for (ImsPhoneConnection conn : mConnections) {
                ImsCall imsCall = conn.getImsCall();
                if (imsCall != null && imsCall.isVideoCall() && !imsCall.isWifiCall()) {
                    log("handleDataEnabledChange - downgrading " + conn);
                    downgradeVideoCall(reasonCode, conn);
                }
            }
        } else if (mSupportPauseVideo) {
            // Data was re-enabled, so un-pause previously paused video calls.
            for (ImsPhoneConnection conn : mConnections) {
                // If video is paused, check to see if there are any pending pauses due to enabled
                // state of data changing.
                log("handleDataEnabledChange - resuming " + conn);
                if (VideoProfile.isPaused(conn.getVideoState()) &&
                        conn.wasVideoPausedFromSource(VideoPauseTracker.SOURCE_DATA_ENABLED)) {
                    // The data enabled state was a cause of a pending pause, so potentially
                    // resume the video now.
                    conn.resumeVideo(VideoPauseTracker.SOURCE_DATA_ENABLED);
                }
            }
            mShouldUpdateImsConfigOnDisconnect = false;
        }
    }

    /**
     * Handles downgrading a video call.  The behavior depends on carrier capabilities; we will
     * attempt to take one of the following actions (in order of precedence):
     * 1. If supported by the carrier, the call will be downgraded to an audio-only call.
     * 2. If the carrier supports video pause signalling, the video will be paused.
     * 3. The call will be disconnected.
     * @param reasonCode The {@link ImsReasonInfo} reason code for the downgrade.
     * @param conn The {@link ImsPhoneConnection} to downgrade.
     */
    private void downgradeVideoCall(int reasonCode, ImsPhoneConnection conn) {
        ImsCall imsCall = conn.getImsCall();
        if (imsCall != null) {
            if (conn.hasCapabilities(
                    Connection.Capability.SUPPORTS_DOWNGRADE_TO_VOICE_LOCAL |
                            Connection.Capability.SUPPORTS_DOWNGRADE_TO_VOICE_REMOTE)
                            && !mSupportPauseVideo) {
                log("downgradeVideoCall :: callId=" + conn.getTelecomCallId()
                        + " Downgrade to audio");
                // If the carrier supports downgrading to voice, then we can simply issue a
                // downgrade to voice instead of terminating the call.
                modifyVideoCall(imsCall, VideoProfile.STATE_AUDIO_ONLY);
            } else if (mSupportPauseVideo && reasonCode != ImsReasonInfo.CODE_WIFI_LOST) {
                // The carrier supports video pause signalling, so pause the video if we didn't just
                // lose wifi; in that case just disconnect.
                log("downgradeVideoCall :: callId=" + conn.getTelecomCallId()
                        + " Pause audio");
                mShouldUpdateImsConfigOnDisconnect = true;
                conn.pauseVideo(VideoPauseTracker.SOURCE_DATA_ENABLED);
            } else {
                log("downgradeVideoCall :: callId=" + conn.getTelecomCallId()
                        + " Disconnect call.");
                // At this point the only choice we have is to terminate the call.
                imsCall.terminate(ImsReasonInfo.CODE_USER_TERMINATED, reasonCode);
            }
        }
    }

    private void resetImsCapabilities() {
        log("Resetting Capabilities...");
        boolean tmpIsVideoCallEnabled = isVideoCallEnabled();
        mMmTelCapabilities = new MmTelFeature.MmTelCapabilities();

        boolean isVideoEnabled = isVideoCallEnabled();
        if (tmpIsVideoCallEnabled != isVideoEnabled) {
            mPhone.notifyForVideoCapabilityChanged(isVideoEnabled);
        }
    }

    /**
     * @return {@code true} if the device is connected to a WIFI network, {@code false} otherwise.
     */
    private boolean isWifiConnected() {
        ConnectivityManager cm = (ConnectivityManager) mPhone.getContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni != null && ni.isConnected()) {
                return ni.getType() == ConnectivityManager.TYPE_WIFI;
            }
        }
        return false;
    }

    /**
     * Registers for changes to network connectivity.  Specifically requests the availability of new
     * WIFI networks which an IMS video call could potentially hand over to.
     */
    private void registerForConnectivityChanges() {
        if (mIsMonitoringConnectivity || !mNotifyVtHandoverToWifiFail) {
            return;
        }
        ConnectivityManager cm = (ConnectivityManager) mPhone.getContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            Rlog.i(LOG_TAG, "registerForConnectivityChanges");
            NetworkCapabilities capabilities = new NetworkCapabilities();
            capabilities.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
            NetworkRequest.Builder builder = new NetworkRequest.Builder();
            builder.setCapabilities(capabilities);
            cm.registerNetworkCallback(builder.build(), mNetworkCallback);
            mIsMonitoringConnectivity = true;
        }
    }

    /**
     * Unregister for connectivity changes.  Will be called when a call disconnects or if the call
     * ends up handing over to WIFI.
     */
    private void unregisterForConnectivityChanges() {
        if (!mIsMonitoringConnectivity || !mNotifyVtHandoverToWifiFail) {
            return;
        }
        ConnectivityManager cm = (ConnectivityManager) mPhone.getContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            Rlog.i(LOG_TAG, "unregisterForConnectivityChanges");
            cm.unregisterNetworkCallback(mNetworkCallback);
            mIsMonitoringConnectivity = false;
        }
    }

    /**
     * If the foreground call is a video call, schedule a handover check if one is not already
     * scheduled.  This method is intended ONLY for use when scheduling to watch for mid-call
     * handovers.
     */
    private void scheduleHandoverCheck() {
        ImsCall fgCall = mForegroundCall.getImsCall();
        ImsPhoneConnection conn = mForegroundCall.getFirstConnection();
        if (!mNotifyVtHandoverToWifiFail || fgCall == null || !fgCall.isVideoCall() || conn == null
                || conn.getDisconnectCause() != DisconnectCause.NOT_DISCONNECTED) {
            return;
        }

        if (!hasMessages(EVENT_CHECK_FOR_WIFI_HANDOVER)) {
            Rlog.i(LOG_TAG, "scheduleHandoverCheck: schedule");
            sendMessageDelayed(obtainMessage(EVENT_CHECK_FOR_WIFI_HANDOVER, fgCall),
                    HANDOVER_TO_WIFI_TIMEOUT_MS);
        }
    }

    /**
     * @return {@code true} if downgrading of a video call to audio is supported.
         */
    public boolean isCarrierDowngradeOfVtCallSupported() {
        return mSupportDowngradeVtToAudio;
    }

    @VisibleForTesting
    public void setDataEnabled(boolean isDataEnabled) {
        mIsDataEnabled = isDataEnabled;
    }

    // Removes old call quality metrics if mCallQualityMetricsHistory exceeds its max size
    private void pruneCallQualityMetricsHistory() {
        if (mCallQualityMetricsHistory.size() > MAX_CALL_QUALITY_HISTORY) {
            mCallQualityMetricsHistory.poll();
        }
    }

    private void handleFeatureCapabilityChanged(ImsFeature.Capabilities capabilities) {
        boolean tmpIsVideoCallEnabled = isVideoCallEnabled();
        // Check enabledFeatures to determine capabilities. We ignore disabledFeatures.
        StringBuilder sb;
        if (DBG) {
            sb = new StringBuilder(120);
            sb.append("handleFeatureCapabilityChanged: ");
        }
        sb.append(capabilities);
        mMmTelCapabilities = new MmTelFeature.MmTelCapabilities(capabilities);

        boolean isVideoEnabled = isVideoCallEnabled();
        boolean isVideoEnabledStatechanged = tmpIsVideoCallEnabled != isVideoEnabled;
        if (DBG) {
            sb.append(" isVideoEnabledStateChanged=");
            sb.append(isVideoEnabledStatechanged);
        }

        if (isVideoEnabledStatechanged) {
            log("handleFeatureCapabilityChanged - notifyForVideoCapabilityChanged="
                    + isVideoEnabled);
            mPhone.notifyForVideoCapabilityChanged(isVideoEnabled);
        }

        if (DBG) log(sb.toString());

        if (DBG) {
            log("handleFeatureCapabilityChanged: isVolteEnabled=" + isVolteEnabled()
                    + ", isVideoCallEnabled=" + isVideoCallEnabled()
                    + ", isVowifiEnabled=" + isVowifiEnabled()
                    + ", isUtEnabled=" + isUtEnabled());
        }

        mPhone.onFeatureCapabilityChanged();

        mMetrics.writeOnImsCapabilities(mPhone.getPhoneId(), getImsRegistrationTech(),
                mMmTelCapabilities);
    }

    @VisibleForTesting
    public void onCallHoldReceived(ImsCall imsCall) {
        if (DBG) log("onCallHoldReceived");

        ImsPhoneConnection conn = findConnection(imsCall);
        if (conn != null) {
            if (!mOnHoldToneStarted && (ImsPhoneCall.isLocalTone(imsCall)
                    || mAlwaysPlayRemoteHoldTone) &&
                    conn.getState() == ImsPhoneCall.State.ACTIVE) {
                mPhone.startOnHoldTone(conn);
                mOnHoldToneStarted = true;
                mOnHoldToneId = System.identityHashCode(conn);
            }
            conn.onConnectionEvent(android.telecom.Connection.EVENT_CALL_REMOTELY_HELD, null);

            boolean useVideoPauseWorkaround = mPhone.getContext().getResources().getBoolean(
                    com.android.internal.R.bool.config_useVideoPauseWorkaround);
            if (useVideoPauseWorkaround && mSupportPauseVideo &&
                    VideoProfile.isVideo(conn.getVideoState())) {
                // If we are using the video pause workaround, the vendor IMS code has issues
                // with video pause signalling.  In this case, when a call is remotely
                // held, the modem does not reliably change the video state of the call to be
                // paused.
                // As a workaround, we will turn on that bit now.
                conn.changeToPausedState();
            }
        }

        SuppServiceNotification supp = new SuppServiceNotification();
        supp.notificationType = SuppServiceNotification.NOTIFICATION_TYPE_CODE_2;
        supp.code = SuppServiceNotification.CODE_2_CALL_ON_HOLD;
        mPhone.notifySuppSvcNotification(supp);
        mMetrics.writeOnImsCallHoldReceived(mPhone.getPhoneId(), imsCall.getCallSession());
    }

    @VisibleForTesting
    public void setAlwaysPlayRemoteHoldTone(boolean shouldPlayRemoteHoldTone) {
        mAlwaysPlayRemoteHoldTone = shouldPlayRemoteHoldTone;
    }

    private String getNetworkCountryIso() {
        String countryIso = "";
        if (mPhone != null) {
            ServiceStateTracker sst = mPhone.getServiceStateTracker();
            if (sst != null) {
                LocaleTracker lt = sst.getLocaleTracker();
                if (lt != null) {
                    countryIso = lt.getCurrentCountry();
                }
            }
        }
        return countryIso;
    }

    @Override
    public ImsPhone getPhone() {
        return mPhone;
    }
}
