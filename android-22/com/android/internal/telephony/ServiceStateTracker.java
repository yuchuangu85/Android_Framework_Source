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

import android.app.PendingIntent;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.telephony.CellInfo;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.TimeUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.android.internal.telephony.dataconnection.DcTrackerBase;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppState;
import com.android.internal.telephony.uicc.IccCardProxy;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccController;

/**
 * {@hide}
 */
public abstract class ServiceStateTracker extends Handler {
    private static final String LOG_TAG = "SST";
    protected  static final boolean DBG = true;
    protected static final boolean VDBG = false;

    protected static final String PROP_FORCE_ROAMING = "telephony.test.forceRoaming";

    protected CommandsInterface mCi;
    protected UiccController mUiccController = null;
    protected UiccCardApplication mUiccApplcation = null;
    protected IccRecords mIccRecords = null;

    protected PhoneBase mPhoneBase;

    protected boolean mVoiceCapable;

    public ServiceState mSS = new ServiceState();
    protected ServiceState mNewSS = new ServiceState();

    private static final long LAST_CELL_INFO_LIST_MAX_AGE_MS = 2000;
    protected long mLastCellInfoListTime;
    protected List<CellInfo> mLastCellInfoList = null;

    // This is final as subclasses alias to a more specific type
    // so we don't want the reference to change.
    protected final CellInfo mCellInfo;

    protected SignalStrength mSignalStrength = new SignalStrength();

    // TODO - this should not be public, right now used externally GsmConnetion.
    public RestrictedState mRestrictedState = new RestrictedState();

    /* The otaspMode passed to PhoneStateListener#onOtaspChanged */
    static public final int OTASP_UNINITIALIZED = 0;
    static public final int OTASP_UNKNOWN = 1;
    static public final int OTASP_NEEDED = 2;
    static public final int OTASP_NOT_NEEDED = 3;

    /**
     * A unique identifier to track requests associated with a poll
     * and ignore stale responses.  The value is a count-down of
     * expected responses in this pollingContext.
     */
    protected int[] mPollingContext;
    protected boolean mDesiredPowerState;

    /**
     * By default, strength polling is enabled.  However, if we're
     * getting unsolicited signal strength updates from the radio, set
     * value to true and don't bother polling any more.
     */
    protected boolean mDontPollSignalStrength = false;

    protected RegistrantList mVoiceRoamingOnRegistrants = new RegistrantList();
    protected RegistrantList mVoiceRoamingOffRegistrants = new RegistrantList();
    protected RegistrantList mDataRoamingOnRegistrants = new RegistrantList();
    protected RegistrantList mDataRoamingOffRegistrants = new RegistrantList();
    protected RegistrantList mAttachedRegistrants = new RegistrantList();
    protected RegistrantList mDetachedRegistrants = new RegistrantList();
    protected RegistrantList mDataRegStateOrRatChangedRegistrants = new RegistrantList();
    protected RegistrantList mNetworkAttachedRegistrants = new RegistrantList();
    protected RegistrantList mPsRestrictEnabledRegistrants = new RegistrantList();
    protected RegistrantList mPsRestrictDisabledRegistrants = new RegistrantList();

    /* Radio power off pending flag and tag counter */
    protected boolean mPendingRadioPowerOffAfterDataOff = false;
    protected int mPendingRadioPowerOffAfterDataOffTag = 0;

    /** Signal strength poll rate. */
    protected static final int POLL_PERIOD_MILLIS = 20 * 1000;

    /** Waiting period before recheck gprs and voice registration. */
    public static final int DEFAULT_GPRS_CHECK_PERIOD_MILLIS = 60 * 1000;

    /** GSM events */
    protected static final int EVENT_RADIO_STATE_CHANGED               = 1;
    protected static final int EVENT_NETWORK_STATE_CHANGED             = 2;
    protected static final int EVENT_GET_SIGNAL_STRENGTH               = 3;
    protected static final int EVENT_POLL_STATE_REGISTRATION           = 4;
    protected static final int EVENT_POLL_STATE_GPRS                   = 5;
    protected static final int EVENT_POLL_STATE_OPERATOR               = 6;
    protected static final int EVENT_POLL_SIGNAL_STRENGTH              = 10;
    protected static final int EVENT_NITZ_TIME                         = 11;
    protected static final int EVENT_SIGNAL_STRENGTH_UPDATE            = 12;
    protected static final int EVENT_RADIO_AVAILABLE                   = 13;
    protected static final int EVENT_POLL_STATE_NETWORK_SELECTION_MODE = 14;
    protected static final int EVENT_GET_LOC_DONE                      = 15;
    protected static final int EVENT_SIM_RECORDS_LOADED                = 16;
    protected static final int EVENT_SIM_READY                         = 17;
    protected static final int EVENT_LOCATION_UPDATES_ENABLED          = 18;
    protected static final int EVENT_GET_PREFERRED_NETWORK_TYPE        = 19;
    protected static final int EVENT_SET_PREFERRED_NETWORK_TYPE        = 20;
    protected static final int EVENT_RESET_PREFERRED_NETWORK_TYPE      = 21;
    protected static final int EVENT_CHECK_REPORT_GPRS                 = 22;
    protected static final int EVENT_RESTRICTED_STATE_CHANGED          = 23;

    /** CDMA events */
    protected static final int EVENT_POLL_STATE_REGISTRATION_CDMA      = 24;
    protected static final int EVENT_POLL_STATE_OPERATOR_CDMA          = 25;
    protected static final int EVENT_RUIM_READY                        = 26;
    protected static final int EVENT_RUIM_RECORDS_LOADED               = 27;
    protected static final int EVENT_POLL_SIGNAL_STRENGTH_CDMA         = 28;
    protected static final int EVENT_GET_SIGNAL_STRENGTH_CDMA          = 29;
    protected static final int EVENT_NETWORK_STATE_CHANGED_CDMA        = 30;
    protected static final int EVENT_GET_LOC_DONE_CDMA                 = 31;
    //protected static final int EVENT_UNUSED                            = 32;
    protected static final int EVENT_NV_LOADED                         = 33;
    protected static final int EVENT_POLL_STATE_CDMA_SUBSCRIPTION      = 34;
    protected static final int EVENT_NV_READY                          = 35;
    protected static final int EVENT_ERI_FILE_LOADED                   = 36;
    protected static final int EVENT_OTA_PROVISION_STATUS_CHANGE       = 37;
    protected static final int EVENT_SET_RADIO_POWER_OFF               = 38;
    protected static final int EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED  = 39;
    protected static final int EVENT_CDMA_PRL_VERSION_CHANGED          = 40;
    protected static final int EVENT_RADIO_ON                          = 41;
    public static final int EVENT_ICC_CHANGED                          = 42;
    protected static final int EVENT_GET_CELL_INFO_LIST                = 43;
    protected static final int EVENT_UNSOL_CELL_INFO_LIST              = 44;
    protected static final int EVENT_CHANGE_IMS_STATE                  = 45;
    protected static final int EVENT_IMS_STATE_CHANGED                 = 46;
    protected static final int EVENT_IMS_STATE_DONE                    = 47;

    protected static final String TIMEZONE_PROPERTY = "persist.sys.timezone";

    /**
     * List of ISO codes for countries that can have an offset of
     * GMT+0 when not in daylight savings time.  This ignores some
     * small places such as the Canary Islands (Spain) and
     * Danmarkshavn (Denmark).  The list must be sorted by code.
    */
    protected static final String[] GMT_COUNTRY_CODES = {
        "bf", // Burkina Faso
        "ci", // Cote d'Ivoire
        "eh", // Western Sahara
        "fo", // Faroe Islands, Denmark
        "gb", // United Kingdom of Great Britain and Northern Ireland
        "gh", // Ghana
        "gm", // Gambia
        "gn", // Guinea
        "gw", // Guinea Bissau
        "ie", // Ireland
        "lr", // Liberia
        "is", // Iceland
        "ma", // Morocco
        "ml", // Mali
        "mr", // Mauritania
        "pt", // Portugal
        "sl", // Sierra Leone
        "sn", // Senegal
        "st", // Sao Tome and Principe
        "tg", // Togo
    };

    private class CellInfoResult {
        List<CellInfo> list;
        Object lockObj = new Object();
    }

    /** Reason for registration denial. */
    protected static final String REGISTRATION_DENIED_GEN  = "General";
    protected static final String REGISTRATION_DENIED_AUTH = "Authentication Failure";

    protected boolean mImsRegistrationOnOff = false;
    protected boolean mAlarmSwitch = false;
    protected IntentFilter mIntentFilter = null;
    protected PendingIntent mRadioOffIntent = null;
    protected static final String ACTION_RADIO_OFF = "android.intent.action.ACTION_RADIO_OFF";
    protected boolean mPowerOffDelayNeed = true;
    protected boolean mDeviceShuttingDown = false;
    /** Keep track of SPN display rules, so we only broadcast intent if something changes. */
    protected boolean mSpnUpdatePending = false;
    protected String mCurSpn = null;
    protected String mCurPlmn = null;
    protected boolean mCurShowPlmn = false;
    protected boolean mCurShowSpn = false;


    private boolean mImsRegistered = false;

    protected SubscriptionManager mSubscriptionManager;
    protected SubscriptionController mSubscriptionController;
    protected final SstSubscriptionsChangedListener mOnSubscriptionsChangedListener =
        new SstSubscriptionsChangedListener();

    protected class SstSubscriptionsChangedListener extends OnSubscriptionsChangedListener {
        public final AtomicInteger mPreviousSubId = new AtomicInteger(-1); // < 0 is invalid subId
        /**
         * Callback invoked when there is any change to any SubscriptionInfo. Typically
         * this method would invoke {@link SubscriptionManager#getActiveSubscriptionInfoList}
         */
        @Override
        public void onSubscriptionsChanged() {
            if (DBG) log("SubscriptionListener.onSubscriptionInfoChanged");
            // Set the network type, in case the radio does not restore it.
            int subId = mPhoneBase.getSubId();
            if (mPreviousSubId.getAndSet(subId) != subId) {
                if (SubscriptionManager.isValidSubscriptionId(subId)) {
                    Context context = mPhoneBase.getContext();
                    int networkType = PhoneFactory.calculatePreferredNetworkType(context, subId);
                    mCi.setPreferredNetworkType(networkType, null);

                    mPhoneBase.notifyCallForwardingIndicator();

                    boolean skipRestoringSelection = context.getResources().getBoolean(
                            com.android.internal.R.bool.skip_restoring_network_selection);
                    if (!skipRestoringSelection) {
                        // restore the previous network selection.
                        mPhoneBase.restoreSavedNetworkSelection(null);
                    }

                    mPhoneBase.setSystemProperty(TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE,
                        ServiceState.rilRadioTechnologyToString(mSS.getRilDataRadioTechnology()));

                    if (mSpnUpdatePending) {
                        mSubscriptionController.setPlmnSpn(mPhoneBase.getPhoneId(), mCurShowPlmn,
                                mCurPlmn, mCurShowSpn, mCurSpn);
                        mSpnUpdatePending = false;
                    }

                    // Remove old network selection sharedPreferences since SP key names are now
                    // changed to include subId. This will be done only once when upgrading from an
                    // older build that did not include subId in the names.
                    SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                            context);
                    String oldNetworkSelectionName = sp.getString(PhoneBase.
                            NETWORK_SELECTION_NAME_KEY, "");
                    String oldNetworkSelection = sp.getString(PhoneBase.NETWORK_SELECTION_KEY,
                            "");
                    if (!TextUtils.isEmpty(oldNetworkSelectionName) ||
                            !TextUtils.isEmpty(oldNetworkSelection)) {
                        SharedPreferences.Editor editor = sp.edit();
                        editor.putString(PhoneBase.NETWORK_SELECTION_NAME_KEY + subId,
                                oldNetworkSelectionName);
                        editor.putString(PhoneBase.NETWORK_SELECTION_KEY + subId,
                                oldNetworkSelection);
                        editor.remove(PhoneBase.NETWORK_SELECTION_NAME_KEY);
                        editor.remove(PhoneBase.NETWORK_SELECTION_KEY);
                        editor.commit();
                    }
                }
            }
        }
    };

    protected ServiceStateTracker(PhoneBase phoneBase, CommandsInterface ci, CellInfo cellInfo) {
        mPhoneBase = phoneBase;
        mCellInfo = cellInfo;
        mCi = ci;
        mVoiceCapable = mPhoneBase.getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_voice_capable);
        mUiccController = UiccController.getInstance();
        mUiccController.registerForIccChanged(this, EVENT_ICC_CHANGED, null);
        mCi.setOnSignalStrengthUpdate(this, EVENT_SIGNAL_STRENGTH_UPDATE, null);
        mCi.registerForCellInfoList(this, EVENT_UNSOL_CELL_INFO_LIST, null);

        mSubscriptionController = SubscriptionController.getInstance();
        mSubscriptionManager = SubscriptionManager.from(phoneBase.getContext());
        mSubscriptionManager
            .addOnSubscriptionsChangedListener(mOnSubscriptionsChangedListener);

        mPhoneBase.setSystemProperty(TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE,
            ServiceState.rilRadioTechnologyToString(ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN));
        mCi.registerForImsNetworkStateChanged(this, EVENT_IMS_STATE_CHANGED, null);
    }

    void requestShutdown() {
        if (mDeviceShuttingDown == true) return;
        mDeviceShuttingDown = true;
        mDesiredPowerState = false;
        setPowerStateToDesired();
    }

    public void dispose() {
        mCi.unSetOnSignalStrengthUpdate(this);
        mUiccController.unregisterForIccChanged(this);
        mCi.unregisterForCellInfoList(this);
        mSubscriptionManager
            .removeOnSubscriptionsChangedListener(mOnSubscriptionsChangedListener);
    }

    public boolean getDesiredPowerState() {
        return mDesiredPowerState;
    }

    private SignalStrength mLastSignalStrength = null;
    protected boolean notifySignalStrength() {
        boolean notified = false;
        synchronized(mCellInfo) {
            if (!mSignalStrength.equals(mLastSignalStrength)) {
                try {
                    mPhoneBase.notifySignalStrength();
                    notified = true;
                } catch (NullPointerException ex) {
                    loge("updateSignalStrength() Phone already destroyed: " + ex
                            + "SignalStrength not notified");
                }
            }
        }
        return notified;
    }

    /**
     * Notify all mDataConnectionRatChangeRegistrants using an
     * AsyncResult in msg.obj where AsyncResult#result contains the
     * new RAT as an Integer Object.
     */
    protected void notifyDataRegStateRilRadioTechnologyChanged() {
        int rat = mSS.getRilDataRadioTechnology();
        int drs = mSS.getDataRegState();
        if (DBG) log("notifyDataRegStateRilRadioTechnologyChanged: drs=" + drs + " rat=" + rat);
        mPhoneBase.setSystemProperty(TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE,
                ServiceState.rilRadioTechnologyToString(rat));
        mDataRegStateOrRatChangedRegistrants.notifyResult(new Pair<Integer, Integer>(drs, rat));
    }

    /**
     * Some operators have been known to report registration failure
     * data only devices, to fix that use DataRegState.
     */
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

    protected void updatePhoneObject() {
        if (mPhoneBase.getContext().getResources().
                getBoolean(com.android.internal.R.bool.config_switch_phone_on_voice_reg_state_change)) {
            // If the phone is not registered on a network, no need to update.
            boolean isRegistered = mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE ||
                    mSS.getVoiceRegState() == ServiceState.STATE_EMERGENCY_ONLY;
            if (!isRegistered) {
                Rlog.d(LOG_TAG, "updatePhoneObject: Ignore update");
                return;
            }
            mPhoneBase.updatePhoneObject(mSS.getRilVoiceRadioTechnology());
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
     */
    public void registerForDataRoamingOff(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mDataRoamingOffRegistrants.add(r);

        if (!mSS.getDataRoaming()) {
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

    @Override
    public void handleMessage(Message msg) {
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
                onUpdateIccAvailability();
                break;

            case EVENT_GET_CELL_INFO_LIST: {
                AsyncResult ar = (AsyncResult) msg.obj;
                CellInfoResult result = (CellInfoResult) ar.userObj;
                synchronized(result.lockObj) {
                    if (ar.exception != null) {
                        log("EVENT_GET_CELL_INFO_LIST: error ret null, e=" + ar.exception);
                        result.list = null;
                    } else {
                        result.list = (List<CellInfo>) ar.result;

                        if (VDBG) {
                            log("EVENT_GET_CELL_INFO_LIST: size=" + result.list.size()
                                    + " list=" + result.list);
                        }
                    }
                    mLastCellInfoListTime = SystemClock.elapsedRealtime();
                    mLastCellInfoList = result.list;
                    result.lockObj.notify();
                }
                break;
            }

            case EVENT_UNSOL_CELL_INFO_LIST: {
                AsyncResult ar = (AsyncResult) msg.obj;
                if (ar.exception != null) {
                    log("EVENT_UNSOL_CELL_INFO_LIST: error ignoring, e=" + ar.exception);
                } else {
                    List<CellInfo> list = (List<CellInfo>) ar.result;
                    if (DBG) {
                        log("EVENT_UNSOL_CELL_INFO_LIST: size=" + list.size()
                                + " list=" + list);
                    }
                    mLastCellInfoListTime = SystemClock.elapsedRealtime();
                    mLastCellInfoList = list;
                    mPhoneBase.notifyCellInfo(list);
                }
                break;
            }

            case  EVENT_IMS_STATE_CHANGED: // received unsol
                mCi.getImsRegistrationState(this.obtainMessage(EVENT_IMS_STATE_DONE));
                break;

            case EVENT_IMS_STATE_DONE:
                AsyncResult ar = (AsyncResult) msg.obj;
                if (ar.exception == null) {
                    int[] responseArray = (int[])ar.result;
                    mImsRegistered = (responseArray[0] == 1) ? true : false;
                }
                break;

            default:
                log("Unhandled message with number: " + msg.what);
                break;
        }
    }

    protected abstract Phone getPhone();
    protected abstract void handlePollStateResult(int what, AsyncResult ar);
    protected abstract void updateSpnDisplay();
    protected abstract void setPowerStateToDesired();
    protected abstract void onUpdateIccAvailability();
    protected abstract void log(String s);
    protected abstract void loge(String s);

    public abstract int getCurrentDataConnectionState();
    public abstract boolean isConcurrentVoiceAndDataAllowed();

    public abstract void setImsRegistrationState(boolean registered);
    public abstract void pollState();

    /**
     * Registration point for transition into DataConnection attached.
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForDataConnectionAttached(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mAttachedRegistrants.add(r);

        if (getCurrentDataConnectionState() == ServiceState.STATE_IN_SERVICE) {
            r.notifyRegistrant();
        }
    }
    public void unregisterForDataConnectionAttached(Handler h) {
        mAttachedRegistrants.remove(h);
    }

    /**
     * Registration point for transition into DataConnection detached.
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForDataConnectionDetached(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mDetachedRegistrants.add(r);

        if (getCurrentDataConnectionState() != ServiceState.STATE_IN_SERVICE) {
            r.notifyRegistrant();
        }
    }
    public void unregisterForDataConnectionDetached(Handler h) {
        mDetachedRegistrants.remove(h);
    }

    /**
     * Registration for DataConnection RIL Data Radio Technology changing. The
     * new radio technology will be returned AsyncResult#result as an Integer Object.
     * The AsyncResult will be in the notification Message#obj.
     *
     * @param h handler to notify
     * @param what what code of message when delivered
     * @param obj placed in Message.obj
     */
    public void registerForDataRegStateOrRatChanged(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mDataRegStateOrRatChangedRegistrants.add(r);
        notifyDataRegStateRilRadioTechnologyChanged();
    }
    public void unregisterForDataRegStateOrRatChanged(Handler h) {
        mDataRegStateOrRatChangedRegistrants.remove(h);
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
     * Clean up existing voice and data connection then turn off radio power.
     *
     * Hang up the existing voice calls to decrease call drop rate.
     */
    public void powerOffRadioSafely(DcTrackerBase dcTracker) {
        synchronized (this) {
            if (!mPendingRadioPowerOffAfterDataOff) {
                // In some network, deactivate PDP connection cause releasing of RRC connection,
                // which MM/IMSI detaching request needs. Without this detaching, network can
                // not release the network resources previously attached.
                // So we are avoiding data detaching on these networks.
                String[] networkNotClearData = mPhoneBase.getContext().getResources()
                        .getStringArray(com.android.internal.R.array.networks_not_clear_data);
                String currentNetwork = mSS.getOperatorNumeric();
                if ((networkNotClearData != null) && (currentNetwork != null)) {
                    for (int i = 0; i < networkNotClearData.length; i++) {
                        if (currentNetwork.equals(networkNotClearData[i])) {
                            // Don't clear data connection for this carrier
                            if (DBG)
                                log("Not disconnecting data for " + currentNetwork);
                            hangupAndPowerOff();
                            return;
                        }
                    }
                }
                // To minimize race conditions we call cleanUpAllConnections on
                // both if else paths instead of before this isDisconnected test.
                if (dcTracker.isDisconnected()) {
                    // To minimize race conditions we do this after isDisconnected
                    dcTracker.cleanUpAllConnections(Phone.REASON_RADIO_TURNED_OFF);
                    if (DBG) log("Data disconnected, turn off radio right away.");
                    hangupAndPowerOff();
                } else {
                    dcTracker.cleanUpAllConnections(Phone.REASON_RADIO_TURNED_OFF);
                    Message msg = Message.obtain(this);
                    msg.what = EVENT_SET_RADIO_POWER_OFF;
                    msg.arg1 = ++mPendingRadioPowerOffAfterDataOffTag;
                    if (sendMessageDelayed(msg, 30000)) {
                        if (DBG) log("Wait upto 30s for data to disconnect, then turn off radio.");
                        mPendingRadioPowerOffAfterDataOff = true;
                    } else {
                        log("Cannot send delayed Msg, turn off radio right away.");
                        hangupAndPowerOff();
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
     * send signal-strength-changed notification if changed Called both for
     * solicited and unsolicited signal strength updates
     *
     * @return true if the signal strength changed and a notification was sent.
     */
    protected boolean onSignalStrengthResult(AsyncResult ar, boolean isGsm) {
        SignalStrength oldSignalStrength = mSignalStrength;

        // This signal is used for both voice and data radio signal so parse
        // all fields

        if ((ar.exception == null) && (ar.result != null)) {
            mSignalStrength = (SignalStrength) ar.result;
            mSignalStrength.validateInput();
            mSignalStrength.setGsm(isGsm);
        } else {
            log("onSignalStrengthResult() Exception from RIL : " + ar.exception);
            mSignalStrength = new SignalStrength(isGsm);
        }

        return notifySignalStrength();
    }

    /**
     * Hang up all voice call and turn off radio. Implemented by derived class.
     */
    protected abstract void hangupAndPowerOff();

    /** Cancel a pending (if any) pollState() operation */
    protected void cancelPollState() {
        // This will effectively cancel the rest of the poll requests.
        mPollingContext = new int[1];
    }

    /**
     * Return true if time zone needs fixing.
     *
     * @param phoneBase
     * @param operatorNumeric
     * @param prevOperatorNumeric
     * @param needToFixTimeZone
     * @return true if time zone needs to be fixed
     */
    protected boolean shouldFixTimeZoneNow(PhoneBase phoneBase, String operatorNumeric,
            String prevOperatorNumeric, boolean needToFixTimeZone) {
        // Return false if the mcc isn't valid as we don't know where we are.
        // Return true if we have an IccCard and the mcc changed or we
        // need to fix it because when the NITZ time came in we didn't
        // know the country code.

        // If mcc is invalid then we'll return false
        int mcc;
        try {
            mcc = Integer.parseInt(operatorNumeric.substring(0, 3));
        } catch (Exception e) {
            if (DBG) {
                log("shouldFixTimeZoneNow: no mcc, operatorNumeric=" + operatorNumeric +
                        " retVal=false");
            }
            return false;
        }

        // If prevMcc is invalid will make it different from mcc
        // so we'll return true if the card exists.
        int prevMcc;
        try {
            prevMcc = Integer.parseInt(prevOperatorNumeric.substring(0, 3));
        } catch (Exception e) {
            prevMcc = mcc + 1;
        }

        // Determine if the Icc card exists
        boolean iccCardExist = false;
        if (mUiccApplcation != null) {
            iccCardExist = mUiccApplcation.getState() != AppState.APPSTATE_UNKNOWN;
        }

        // Determine retVal
        boolean retVal = ((iccCardExist && (mcc != prevMcc)) || needToFixTimeZone);
        if (DBG) {
            long ctm = System.currentTimeMillis();
            log("shouldFixTimeZoneNow: retVal=" + retVal +
                    " iccCardExist=" + iccCardExist +
                    " operatorNumeric=" + operatorNumeric + " mcc=" + mcc +
                    " prevOperatorNumeric=" + prevOperatorNumeric + " prevMcc=" + prevMcc +
                    " needToFixTimeZone=" + needToFixTimeZone +
                    " ltod=" + TimeUtils.logTimeOfDay(ctm));
        }
        return retVal;
    }

    public String getSystemProperty(String property, String defValue) {
        return TelephonyManager.getTelephonyProperty(mPhoneBase.getPhoneId(), property, defValue);
    }

    /**
     * @return all available cell information or null if none.
     */
    public List<CellInfo> getAllCellInfo() {
        CellInfoResult result = new CellInfoResult();
        if (VDBG) log("SST.getAllCellInfo(): E");
        int ver = mCi.getRilVersion();
        if (ver >= 8) {
            if (isCallerOnDifferentThread()) {
                if ((SystemClock.elapsedRealtime() - mLastCellInfoListTime)
                        > LAST_CELL_INFO_LIST_MAX_AGE_MS) {
                    Message msg = obtainMessage(EVENT_GET_CELL_INFO_LIST, result);
                    synchronized(result.lockObj) {
                        result.list = null;
                        mCi.getCellInfoList(msg);
                        try {
                            result.lockObj.wait(5000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                } else {
                    if (DBG) log("SST.getAllCellInfo(): return last, back to back calls");
                    result.list = mLastCellInfoList;
                }
            } else {
                if (DBG) log("SST.getAllCellInfo(): return last, same thread can't block");
                result.list = mLastCellInfoList;
            }
        } else {
            if (DBG) log("SST.getAllCellInfo(): not implemented");
            result.list = null;
        }
        synchronized(result.lockObj) {
            if (result.list != null) {
                if (DBG) log("SST.getAllCellInfo(): X size=" + result.list.size()
                        + " list=" + result.list);
                return result.list;
            } else {
                if (DBG) log("SST.getAllCellInfo(): X size=0 list=null");
                return null;
            }
        }
    }

    /**
     * @return signal strength
     */
    public SignalStrength getSignalStrength() {
        synchronized(mCellInfo) {
            return mSignalStrength;
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("ServiceStateTracker:");
        pw.println(" mSS=" + mSS);
        pw.println(" mNewSS=" + mNewSS);
        pw.println(" mCellInfo=" + mCellInfo);
        pw.println(" mRestrictedState=" + mRestrictedState);
        pw.println(" mPollingContext=" + mPollingContext);
        pw.println(" mDesiredPowerState=" + mDesiredPowerState);
        pw.println(" mDontPollSignalStrength=" + mDontPollSignalStrength);
        pw.println(" mPendingRadioPowerOffAfterDataOff=" + mPendingRadioPowerOffAfterDataOff);
        pw.println(" mPendingRadioPowerOffAfterDataOffTag=" + mPendingRadioPowerOffAfterDataOffTag);
        pw.flush();
    }

    public boolean isImsRegistered() {
        return mImsRegistered;
    }
    /**
     * Verifies the current thread is the same as the thread originally
     * used in the initialization of this instance. Throws RuntimeException
     * if not.
     *
     * @exception RuntimeException if the current thread is not
     * the thread that originally obtained this PhoneBase instance.
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

    protected void updateCarrierMccMncConfiguration(String newOp, String oldOp, Context context) {
        // if we have a change in operator, notify wifi (even to/from none)
        if (((newOp == null) && (TextUtils.isEmpty(oldOp) == false)) ||
                ((newOp != null) && (newOp.equals(oldOp) == false))) {
            log("update mccmnc=" + newOp + " fromServiceState=true");
            MccTable.updateMccMncConfiguration(context, newOp, true);
        }
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
        final String networkCountry = MccTable.countryCodeForMcc(Integer.parseInt(networkMCC));
        final String homeCountry = MccTable.countryCodeForMcc(Integer.parseInt(homeMCC));
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

    protected abstract void setRoamingType(ServiceState currentServiceState);

    protected String getHomeOperatorNumeric() {
        return ((TelephonyManager) mPhoneBase.getContext().
                getSystemService(Context.TELEPHONY_SERVICE)).
                getSimOperatorNumericForPhone(mPhoneBase.getPhoneId());
    }

    protected int getPhoneId() {
        return mPhoneBase.getPhoneId();
    }
}
