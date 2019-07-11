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

package com.android.internal.telephony.gsm;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellLocation;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.gsm.GsmCellLocation;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.TimeUtils;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.ProxyController;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.RestrictedState;
import com.android.internal.telephony.ServiceStateTracker;
import android.telephony.SubscriptionManager;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.dataconnection.DcTrackerBase;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppState;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.SIMRecords;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * {@hide}
 */
final class GsmServiceStateTracker extends ServiceStateTracker {
    static final String LOG_TAG = "GsmSST";
    static final boolean VDBG = false;
    //CAF_MSIM make it private ??
    private static final int EVENT_ALL_DATA_DISCONNECTED = 1001;
    private GSMPhone mPhone;
    GsmCellLocation mCellLoc;
    GsmCellLocation mNewCellLoc;
    int mPreferredNetworkType;

    private int mMaxDataCalls = 1;
    private int mNewMaxDataCalls = 1;
    private int mReasonDataDenied = -1;
    private int mNewReasonDataDenied = -1;

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
    private boolean mEmergencyOnly = false;

    /**
     * Sometimes we get the NITZ time before we know what country we
     * are in. Keep the time zone information from the NITZ string so
     * we can fix the time zone once know the country.
     */
    private boolean mNeedFixZoneAfterNitz = false;
    private int mZoneOffset;
    private boolean mZoneDst;
    private long mZoneTime;
    private boolean mGotCountryCode = false;
    private ContentResolver mCr;

    /** Boolean is true is setTimeFromNITZString was called */
    private boolean mNitzUpdatedTime = false;

    String mSavedTimeZone;
    long mSavedTime;
    long mSavedAtTime;

    /** Started the recheck process after finding gprs should registered but not. */
    private boolean mStartedGprsRegCheck = false;

    /** Already sent the event-log for no gprs register. */
    private boolean mReportedGprsNoReg = false;

    /**
     * The Notification object given to the NotificationManager.
     */
    private Notification mNotification;

    /** Wake lock used while setting time of day. */
    private PowerManager.WakeLock mWakeLock;
    private static final String WAKELOCK_TAG = "ServiceStateTracker";

    /** Keep track of SPN display rules, so we only broadcast intent if something changes. */
    private String mCurSpn = null;
    private String mCurPlmn = null;
    private boolean mCurShowPlmn = false;
    private boolean mCurShowSpn = false;

    /** Notification type. */
    static final int PS_ENABLED = 1001;            // Access Control blocks data service
    static final int PS_DISABLED = 1002;           // Access Control enables data service
    static final int CS_ENABLED = 1003;            // Access Control blocks all voice/sms service
    static final int CS_DISABLED = 1004;           // Access Control enables all voice/sms service
    static final int CS_NORMAL_ENABLED = 1005;     // Access Control blocks normal voice/sms service
    static final int CS_EMERGENCY_ENABLED = 1006;  // Access Control blocks emergency call service

    /** Notification id. */
    static final int PS_NOTIFICATION = 888;  // Id to update and cancel PS restricted
    static final int CS_NOTIFICATION = 999;  // Id to update and cancel CS restricted

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mPhone.mIsTheCurrentActivePhone) {
                Rlog.e(LOG_TAG, "Received Intent " + intent +
                        " while being destroyed. Ignoring.");
                return;
            }

            if (intent.getAction().equals(Intent.ACTION_LOCALE_CHANGED)) {
                // update emergency string whenever locale changed
                updateSpnDisplay();
            } else if (intent.getAction().equals(ACTION_RADIO_OFF)) {
                mAlarmSwitch = false;
                DcTrackerBase dcTracker = mPhone.mDcTracker;
                powerOffRadioSafely(dcTracker);
            }
        }
    };

    private ContentObserver mAutoTimeObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            Rlog.i("GsmServiceStateTracker", "Auto time state changed");
            revertToNitzTime();
        }
    };

    private ContentObserver mAutoTimeZoneObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            Rlog.i("GsmServiceStateTracker", "Auto time zone state changed");
            revertToNitzTimeZone();
        }
    };

    public GsmServiceStateTracker(GSMPhone phone) {
        super(phone, phone.mCi, new CellInfoGsm());

        mPhone = phone;
        mCellLoc = new GsmCellLocation();
        mNewCellLoc = new GsmCellLocation();

        PowerManager powerManager =
                (PowerManager)phone.getContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);

        mCi.registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);
        mCi.registerForRadioStateChanged(this, EVENT_RADIO_STATE_CHANGED, null);

        mCi.registerForVoiceNetworkStateChanged(this, EVENT_NETWORK_STATE_CHANGED, null);
        mCi.setOnNITZTime(this, EVENT_NITZ_TIME, null);
        mCi.setOnRestrictedStateChanged(this, EVENT_RESTRICTED_STATE_CHANGED, null);

        // system setting property AIRPLANE_MODE_ON is set in Settings.
        int airplaneMode = Settings.Global.getInt(
                phone.getContext().getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0);
        mDesiredPowerState = ! (airplaneMode > 0);

        mCr = phone.getContext().getContentResolver();
        mCr.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AUTO_TIME), true,
                mAutoTimeObserver);
        mCr.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AUTO_TIME_ZONE), true,
                mAutoTimeZoneObserver);

        setSignalStrengthDefaultValues();

        // Monitor locale change
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        phone.getContext().registerReceiver(mIntentReceiver, filter);

        filter = new IntentFilter();
        Context context = phone.getContext();
        filter.addAction(ACTION_RADIO_OFF);
        context.registerReceiver(mIntentReceiver, filter);
    }

    @Override
    public void dispose() {
        checkCorrectThread();
        log("ServiceStateTracker dispose");

        // Unregister for all events.
        mCi.unregisterForAvailable(this);
        mCi.unregisterForRadioStateChanged(this);
        mCi.unregisterForVoiceNetworkStateChanged(this);
        if (mUiccApplcation != null) {mUiccApplcation.unregisterForReady(this);}
        if (mIccRecords != null) {mIccRecords.unregisterForRecordsLoaded(this);}
        mCi.unSetOnRestrictedStateChanged(this);
        mCi.unSetOnNITZTime(this);
        mCr.unregisterContentObserver(mAutoTimeObserver);
        mCr.unregisterContentObserver(mAutoTimeZoneObserver);
        mPhone.getContext().unregisterReceiver(mIntentReceiver);
        super.dispose();
    }

    @Override
    protected void finalize() {
        if(DBG) log("finalize");
    }

    @Override
    protected Phone getPhone() {
        return mPhone;
    }

    @Override
    public void handleMessage (Message msg) {
        AsyncResult ar;
        int[] ints;
        String[] strings;
        Message message;

        if (!mPhone.mIsTheCurrentActivePhone) {
            Rlog.e(LOG_TAG, "Received message " + msg +
                    "[" + msg.what + "] while being destroyed. Ignoring.");
            return;
        }
        switch (msg.what) {
            case EVENT_RADIO_AVAILABLE:
                //this is unnecessary
                //setPowerStateToDesired();
                break;

            case EVENT_SIM_READY:
                // Set the network type, in case the radio does not restore it.
                int networkType = PhoneFactory.calculatePreferredNetworkType(mPhone.getContext());
                mCi.setPreferredNetworkType(networkType, null);

                boolean skipRestoringSelection = mPhone.getContext().getResources().getBoolean(
                        com.android.internal.R.bool.skip_restoring_network_selection);

                if (!skipRestoringSelection) {
                    // restore the previous network selection.
                    mPhone.restoreSavedNetworkSelection(null);
                }
                pollState();
                // Signal strength polling stops when radio is off
                queueNextSignalStrengthPoll();
                break;

            case EVENT_RADIO_STATE_CHANGED:
                // This will do nothing in the radio not
                // available case
                setPowerStateToDesired();
                pollState();
                break;

            case EVENT_NETWORK_STATE_CHANGED:
                pollState();
                break;

            case EVENT_GET_SIGNAL_STRENGTH:
                // This callback is called when signal strength is polled
                // all by itself

                if (!(mCi.getRadioState().isOn())) {
                    // Polling will continue when radio turns back on
                    return;
                }
                ar = (AsyncResult) msg.obj;
                onSignalStrengthResult(ar, true);
                queueNextSignalStrengthPoll();

                break;

            case EVENT_GET_LOC_DONE:
                ar = (AsyncResult) msg.obj;

                if (ar.exception == null) {
                    String states[] = (String[])ar.result;
                    int lac = -1;
                    int cid = -1;
                    if (states.length >= 3) {
                        try {
                            if (states[1] != null && states[1].length() > 0) {
                                lac = Integer.parseInt(states[1], 16);
                            }
                            if (states[2] != null && states[2].length() > 0) {
                                cid = Integer.parseInt(states[2], 16);
                            }
                        } catch (NumberFormatException ex) {
                            Rlog.w(LOG_TAG, "error parsing location: " + ex);
                        }
                    }
                    mCellLoc.setLacAndCid(lac, cid);
                    mPhone.notifyLocationChanged();
                }

                // Release any temporary cell lock, which could have been
                // acquired to allow a single-shot location update.
                disableSingleLocationUpdate();
                break;

            case EVENT_POLL_STATE_REGISTRATION:
            case EVENT_POLL_STATE_GPRS:
            case EVENT_POLL_STATE_OPERATOR:
            case EVENT_POLL_STATE_NETWORK_SELECTION_MODE:
                ar = (AsyncResult) msg.obj;

                handlePollStateResult(msg.what, ar);
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
                // This is a notification from
                // CommandsInterface.setOnSignalStrengthUpdate

                ar = (AsyncResult) msg.obj;

                // The radio is telling us about signal strength changes
                // we don't have to ask it
                mDontPollSignalStrength = true;

                onSignalStrengthResult(ar, true);
                break;

            case EVENT_SIM_RECORDS_LOADED:
                log("EVENT_SIM_RECORDS_LOADED: what=" + msg.what);
                // Gsm doesn't support OTASP so its not needed
                mPhone.notifyOtaspChanged(OTASP_NOT_NEEDED);

                updatePhoneObject();
                updateSpnDisplay();
                break;

            case EVENT_LOCATION_UPDATES_ENABLED:
                ar = (AsyncResult) msg.obj;

                if (ar.exception == null) {
                    mCi.getVoiceRegistrationState(obtainMessage(EVENT_GET_LOC_DONE, null));
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
                if (mSS != null && !isGprsConsistent(mSS.getDataRegState(), mSS.getVoiceRegState())) {

                    // Can't register data service while voice service is ok
                    // i.e. CREG is ok while CGREG is not
                    // possible a network or baseband side error
                    GsmCellLocation loc = ((GsmCellLocation)mPhone.getCellLocation());
                    EventLog.writeEvent(EventLogTags.DATA_NETWORK_REGISTRATION_FAIL,
                            mSS.getOperatorNumeric(), loc != null ? loc.getCid() : -1);
                    mReportedGprsNoReg = true;
                }
                mStartedGprsRegCheck = false;
                break;

            case EVENT_RESTRICTED_STATE_CHANGED:
                // This is a notification from
                // CommandsInterface.setOnRestrictedStateChanged

                if (DBG) log("EVENT_RESTRICTED_STATE_CHANGED");

                ar = (AsyncResult) msg.obj;

                onRestrictedStateChanged(ar);
                break;

            case EVENT_ALL_DATA_DISCONNECTED:
                long dds = SubscriptionManager.getDefaultDataSubId();
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

            default:
                super.handleMessage(msg);
            break;
        }
    }

    @Override
    protected void setPowerStateToDesired() {

        if (DBG) {
            log("mDeviceShuttingDown = " + mDeviceShuttingDown);
            log("mDesiredPowerState = " + mDesiredPowerState);
            log("getRadioState = " + mCi.getRadioState());
            log("mPowerOffDelayNeed = " + mPowerOffDelayNeed);
            log("mAlarmSwitch = " + mAlarmSwitch);
        }

        if (mAlarmSwitch) {
            if(DBG) log("mAlarmSwitch == true");
            Context context = mPhone.getContext();
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            am.cancel(mRadioOffIntent);
            mAlarmSwitch = false;
        }

        // If we want it on and it's off, turn it on
        if (mDesiredPowerState
                && mCi.getRadioState() == CommandsInterface.RadioState.RADIO_OFF) {
            mCi.setRadioPower(true, null);
        } else if (!mDesiredPowerState && mCi.getRadioState().isOn()) {
            // If it's on and available and we want it off gracefully
            if (mPowerOffDelayNeed) {
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
                    DcTrackerBase dcTracker = mPhone.mDcTracker;
                    powerOffRadioSafely(dcTracker);
                }
            } else {
                DcTrackerBase dcTracker = mPhone.mDcTracker;
                powerOffRadioSafely(dcTracker);
            }
        } else if (mDeviceShuttingDown && mCi.getRadioState().isAvailable()) {
            mCi.requestShutdown(null);
        }
    }

    @Override
    protected void hangupAndPowerOff() {
        // hang up all active voice calls
        if (mPhone.isInCall()) {
            mPhone.mCT.mRingingCall.hangupIfAlive();
            mPhone.mCT.mBackgroundCall.hangupIfAlive();
            mPhone.mCT.mForegroundCall.hangupIfAlive();
        }

        mCi.setRadioPower(false, null);
    }

    @Override
    protected void updateSpnDisplay() {
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
        String plmn = null;
        boolean showPlmn = false;
        int rule = (iccRecords != null) ? iccRecords.getDisplayRule(mSS.getOperatorNumeric()) : 0;
        if (mSS.getVoiceRegState() == ServiceState.STATE_OUT_OF_SERVICE
                || mSS.getVoiceRegState() == ServiceState.STATE_EMERGENCY_ONLY) {
            showPlmn = true;
            if (mEmergencyOnly) {
                // No service but emergency call allowed
                plmn = Resources.getSystem().
                        getText(com.android.internal.R.string.emergency_calls_only).toString();
            } else {
                // No service at all
                plmn = Resources.getSystem().
                        getText(com.android.internal.R.string.lockscreen_carrier_default).toString();
            }
            if (DBG) log("updateSpnDisplay: radio is on but out " +
                    "of service, set plmn='" + plmn + "'");
        } else if (mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE) {
            // In either home or roaming service
            plmn = mSS.getOperatorAlphaLong();
            showPlmn = !TextUtils.isEmpty(plmn) &&
                    ((rule & SIMRecords.SPN_RULE_SHOW_PLMN)
                            == SIMRecords.SPN_RULE_SHOW_PLMN);
        } else {
            // Power off state, such as airplane mode
            if (DBG) log("updateSpnDisplay: radio is off w/ showPlmn="
                    + showPlmn + " plmn=" + plmn);
        }

        // The value of spn/showSpn are same in different scenarios.
        //    EXTRA_SHOW_SPN = depending on IccRecords rule
        //    EXTRA_SPN = spn
        String spn = (iccRecords != null) ? iccRecords.getServiceProviderName() : "";
        boolean showSpn = !TextUtils.isEmpty(spn)
                && ((rule & SIMRecords.SPN_RULE_SHOW_SPN)
                        == SIMRecords.SPN_RULE_SHOW_SPN);

        // Update SPN_STRINGS_UPDATED_ACTION IFF any value changes
        if (showPlmn != mCurShowPlmn
                || showSpn != mCurShowSpn
                || !TextUtils.equals(spn, mCurSpn)
                || !TextUtils.equals(plmn, mCurPlmn)) {
            if (DBG) {
                log(String.format("updateSpnDisplay: changed" +
                        " sending intent rule=" + rule +
                        " showPlmn='%b' plmn='%s' showSpn='%b' spn='%s'",
                        showPlmn, plmn, showSpn, spn));
            }
            Intent intent = new Intent(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            intent.putExtra(TelephonyIntents.EXTRA_SHOW_SPN, showSpn);
            intent.putExtra(TelephonyIntents.EXTRA_SPN, spn);
            intent.putExtra(TelephonyIntents.EXTRA_SHOW_PLMN, showPlmn);
            intent.putExtra(TelephonyIntents.EXTRA_PLMN, plmn);
            SubscriptionManager.putPhoneIdAndSubIdExtra(intent, mPhone.getPhoneId());
            mPhone.getContext().sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }

        mCurShowSpn = showSpn;
        mCurShowPlmn = showPlmn;
        mCurSpn = spn;
        mCurPlmn = plmn;
    }

    /**
     * Handle the result of one of the pollState()-related requests
     */
    @Override
    protected void handlePollStateResult (int what, AsyncResult ar) {
        int ints[];
        String states[];

        // Ignore stale requests from last poll
        if (ar.userObj != mPollingContext) return;

        if (ar.exception != null) {
            CommandException.Error err=null;

            if (ar.exception instanceof CommandException) {
                err = ((CommandException)(ar.exception)).getCommandError();
            }

            if (err == CommandException.Error.RADIO_NOT_AVAILABLE) {
                // Radio has crashed or turned off
                cancelPollState();
                return;
            }

            if (!mCi.getRadioState().isOn()) {
                // Radio has crashed or turned off
                cancelPollState();
                return;
            }

            if (err != CommandException.Error.OP_NOT_ALLOWED_BEFORE_REG_NW) {
                loge("RIL implementation has returned an error where it must succeed" +
                        ar.exception);
            }
        } else try {
            switch (what) {
                case EVENT_POLL_STATE_REGISTRATION: {
                    states = (String[])ar.result;
                    int lac = -1;
                    int cid = -1;
                    int type = ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN;
                    int regState = ServiceState.RIL_REG_STATE_UNKNOWN;
                    int reasonRegStateDenied = -1;
                    int psc = -1;
                    if (states.length > 0) {
                        try {
                            regState = Integer.parseInt(states[0]);
                            if (states.length >= 3) {
                                if (states[1] != null && states[1].length() > 0) {
                                    lac = Integer.parseInt(states[1], 16);
                                }
                                if (states[2] != null && states[2].length() > 0) {
                                    cid = Integer.parseInt(states[2], 16);
                                }

                                // states[3] (if present) is the current radio technology
                                if (states.length >= 4 && states[3] != null) {
                                    type = Integer.parseInt(states[3]);
                                }
                            }
                            if (states.length > 14) {
                                if (states[14] != null && states[14].length() > 0) {
                                    psc = Integer.parseInt(states[14], 16);
                                }
                            }
                        } catch (NumberFormatException ex) {
                            loge("error parsing RegistrationState: " + ex);
                        }
                    }

                    mGsmRoaming = regCodeIsRoaming(regState);
                    mNewSS.setState(regCodeToServiceState(regState));
                    mNewSS.setRilVoiceRadioTechnology(type);

                    boolean isVoiceCapable = mPhoneBase.getContext().getResources()
                            .getBoolean(com.android.internal.R.bool.config_voice_capable);
                    if ((regState == ServiceState.RIL_REG_STATE_DENIED_EMERGENCY_CALL_ENABLED
                         || regState == ServiceState.RIL_REG_STATE_NOT_REG_EMERGENCY_CALL_ENABLED
                         || regState == ServiceState.RIL_REG_STATE_SEARCHING_EMERGENCY_CALL_ENABLED
                         || regState == ServiceState.RIL_REG_STATE_UNKNOWN_EMERGENCY_CALL_ENABLED)
                         && isVoiceCapable) {
                        mEmergencyOnly = true;
                    } else {
                        mEmergencyOnly = false;
                    }

                    // LAC and CID are -1 if not avail
                    mNewCellLoc.setLacAndCid(lac, cid);
                    mNewCellLoc.setPsc(psc);
                    break;
                }

                case EVENT_POLL_STATE_GPRS: {
                    states = (String[])ar.result;

                    int type = 0;
                    int regState = ServiceState.RIL_REG_STATE_UNKNOWN;
                    mNewReasonDataDenied = -1;
                    mNewMaxDataCalls = 1;
                    if (states.length > 0) {
                        try {
                            regState = Integer.parseInt(states[0]);

                            // states[3] (if present) is the current radio technology
                            if (states.length >= 4 && states[3] != null) {
                                type = Integer.parseInt(states[3]);
                            }
                            if ((states.length >= 5 ) &&
                                    (regState == ServiceState.RIL_REG_STATE_DENIED)) {
                                mNewReasonDataDenied = Integer.parseInt(states[4]);
                            }
                            if (states.length >= 6) {
                                mNewMaxDataCalls = Integer.parseInt(states[5]);
                            }
                        } catch (NumberFormatException ex) {
                            loge("error parsing GprsRegistrationState: " + ex);
                        }
                    }
                    int dataRegState = regCodeToServiceState(regState);
                    mNewSS.setDataRegState(dataRegState);
                    mDataRoaming = regCodeIsRoaming(regState);
                    mNewSS.setRilDataRadioTechnology(type);
                    if (DBG) {
                        log("handlPollStateResultMessage: GsmSST setDataRegState=" + dataRegState
                                + " regState=" + regState
                                + " dataRadioTechnology=" + type);
                    }
                    break;
                }

                case EVENT_POLL_STATE_OPERATOR: {
                    String opNames[] = (String[])ar.result;

                    if (opNames != null && opNames.length >= 3) {
                        String brandOverride = mUiccController.getUiccCard() != null ?
                            mUiccController.getUiccCard().getOperatorBrandOverride() : null;
                        if (brandOverride != null) {
                            mNewSS.setOperatorName(brandOverride, brandOverride, opNames[2]);
                        } else {
                            mNewSS.setOperatorName(opNames[0], opNames[1], opNames[2]);
                        }
                    }
                    break;
                }

                case EVENT_POLL_STATE_NETWORK_SELECTION_MODE: {
                    ints = (int[])ar.result;
                    mNewSS.setIsManualSelection(ints[0] == 1);
                    break;
                }
            }

        } catch (RuntimeException ex) {
            loge("Exception while polling service state. Probably malformed RIL response." + ex);
        }

        mPollingContext[0]--;

        if (mPollingContext[0] == 0) {
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
            if (mGsmRoaming && !isOperatorConsideredRoaming(mNewSS) &&
                (isSameNamedOperators(mNewSS) || isOperatorConsideredNonRoaming(mNewSS))) {
                roaming = false;
            }
            mNewSS.setRoaming(roaming);
            mNewSS.setEmergencyOnly(mEmergencyOnly);
            pollStateDone();
        }
    }

    private void setSignalStrengthDefaultValues() {
        mSignalStrength = new SignalStrength(true);
    }

    /**
     * A complete "service state" from our perspective is
     * composed of a handful of separate requests to the radio.
     *
     * We make all of these requests at once, but then abandon them
     * and start over again if the radio notifies us that some
     * event has changed
     */
    @Override
    public void pollState() {
        mPollingContext = new int[1];
        mPollingContext[0] = 0;

        switch (mCi.getRadioState()) {
            case RADIO_UNAVAILABLE:
                mNewSS.setStateOutOfService();
                mNewCellLoc.setStateInvalid();
                setSignalStrengthDefaultValues();
                mGotCountryCode = false;
                mNitzUpdatedTime = false;
                pollStateDone();
            break;

            case RADIO_OFF:
                mNewSS.setStateOff();
                mNewCellLoc.setStateInvalid();
                setSignalStrengthDefaultValues();
                mGotCountryCode = false;
                mNitzUpdatedTime = false;
                pollStateDone();
            break;

            default:
                // Issue all poll-related commands at once
                // then count down the responses, which
                // are allowed to arrive out-of-order

                mPollingContext[0]++;
                mCi.getOperator(
                    obtainMessage(
                        EVENT_POLL_STATE_OPERATOR, mPollingContext));

                mPollingContext[0]++;
                mCi.getDataRegistrationState(
                    obtainMessage(
                        EVENT_POLL_STATE_GPRS, mPollingContext));

                mPollingContext[0]++;
                mCi.getVoiceRegistrationState(
                    obtainMessage(
                        EVENT_POLL_STATE_REGISTRATION, mPollingContext));

                mPollingContext[0]++;
                mCi.getNetworkSelectionMode(
                    obtainMessage(
                        EVENT_POLL_STATE_NETWORK_SELECTION_MODE, mPollingContext));
            break;
        }
    }

    private void pollStateDone() {
        if (DBG) {
            log("Poll ServiceState done: " +
                " oldSS=[" + mSS + "] newSS=[" + mNewSS + "]" +
                " oldMaxDataCalls=" + mMaxDataCalls +
                " mNewMaxDataCalls=" + mNewMaxDataCalls +
                " oldReasonDataDenied=" + mReasonDataDenied +
                " mNewReasonDataDenied=" + mNewReasonDataDenied);
        }

        if (Build.IS_DEBUGGABLE && SystemProperties.getBoolean(PROP_FORCE_ROAMING, false)) {
            mNewSS.setRoaming(true);
        }

        useDataRegStateForDataOnlyDevices();

        boolean hasRegistered =
            mSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE
            && mNewSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE;

        boolean hasDeregistered =
            mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE
            && mNewSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE;

        boolean hasGprsAttached =
                mSS.getDataRegState() != ServiceState.STATE_IN_SERVICE
                && mNewSS.getDataRegState() == ServiceState.STATE_IN_SERVICE;

        boolean hasGprsDetached =
                mSS.getDataRegState() == ServiceState.STATE_IN_SERVICE
                && mNewSS.getDataRegState() != ServiceState.STATE_IN_SERVICE;

        boolean hasDataRegStateChanged =
                mSS.getDataRegState() != mNewSS.getDataRegState();

        boolean hasVoiceRegStateChanged =
                mSS.getVoiceRegState() != mNewSS.getVoiceRegState();

        boolean hasRilVoiceRadioTechnologyChanged =
                mSS.getRilVoiceRadioTechnology() != mNewSS.getRilVoiceRadioTechnology();

        boolean hasRilDataRadioTechnologyChanged =
                mSS.getRilDataRadioTechnology() != mNewSS.getRilDataRadioTechnology();

        boolean hasChanged = !mNewSS.equals(mSS);

        boolean hasRoamingOn = !mSS.getRoaming() && mNewSS.getRoaming();

        boolean hasRoamingOff = mSS.getRoaming() && !mNewSS.getRoaming();

        boolean hasLocationChanged = !mNewCellLoc.equals(mCellLoc);

        // Add an event log when connection state changes
        if (hasVoiceRegStateChanged || hasDataRegStateChanged) {
            EventLog.writeEvent(EventLogTags.GSM_SERVICE_STATE_CHANGE,
                mSS.getVoiceRegState(), mSS.getDataRegState(),
                mNewSS.getVoiceRegState(), mNewSS.getDataRegState());
        }

        // Add an event log when network type switched
        // TODO: we may add filtering to reduce the event logged,
        // i.e. check preferred network setting, only switch to 2G, etc
        if (hasRilVoiceRadioTechnologyChanged) {
            int cid = -1;
            GsmCellLocation loc = mNewCellLoc;
            if (loc != null) cid = loc.getCid();
            // NOTE: this code was previously located after mSS and mNewSS are swapped, so
            // existing logs were incorrectly using the new state for "network_from"
            // and STATE_OUT_OF_SERVICE for "network_to". To avoid confusion, use a new log tag
            // to record the correct states.
            EventLog.writeEvent(EventLogTags.GSM_RAT_SWITCHED_NEW, cid,
                    mSS.getRilVoiceRadioTechnology(),
                    mNewSS.getRilVoiceRadioTechnology());
            if (DBG) {
                log("RAT switched "
                        + ServiceState.rilRadioTechnologyToString(mSS.getRilVoiceRadioTechnology())
                        + " -> "
                        + ServiceState.rilRadioTechnologyToString(
                                mNewSS.getRilVoiceRadioTechnology()) + " at cell " + cid);
            }
        }

        // swap mSS and mNewSS to put new state in mSS
        ServiceState tss = mSS;
        mSS = mNewSS;
        mNewSS = tss;
        // clean slate for next time
        mNewSS.setStateOutOfService();

        // swap mCellLoc and mNewCellLoc to put new state in mCellLoc
        GsmCellLocation tcl = mCellLoc;
        mCellLoc = mNewCellLoc;
        mNewCellLoc = tcl;

        mReasonDataDenied = mNewReasonDataDenied;
        mMaxDataCalls = mNewMaxDataCalls;

        if (hasRilVoiceRadioTechnologyChanged) {
            updatePhoneObject();
        }

        if (hasRilDataRadioTechnologyChanged) {
            mPhone.setSystemProperty(TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE,
                    ServiceState.rilRadioTechnologyToString(mSS.getRilVoiceRadioTechnology()));
        }

        if (hasRegistered) {
            mNetworkAttachedRegistrants.notifyRegistrants();

            if (DBG) {
                log("pollStateDone: registering current mNitzUpdatedTime=" +
                        mNitzUpdatedTime + " changing to false");
            }
            mNitzUpdatedTime = false;
        }

        if (hasChanged) {
            String operatorNumeric;

            updateSpnDisplay();

            mPhone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ALPHA,
                mSS.getOperatorAlphaLong());

            String prevOperatorNumeric =
                    SystemProperties.get(TelephonyProperties.PROPERTY_OPERATOR_NUMERIC, "");
            operatorNumeric = mSS.getOperatorNumeric();
            mPhone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_NUMERIC, operatorNumeric);
            updateCarrierMccMncConfiguration(operatorNumeric,
                    prevOperatorNumeric, mPhone.getContext());
            if (operatorNumeric == null) {
                if (DBG) log("operatorNumeric is null");
                mPhone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, "");
                mGotCountryCode = false;
                mNitzUpdatedTime = false;
            } else {
                String iso = "";
                String mcc = "";
                try{
                    mcc = operatorNumeric.substring(0, 3);
                    iso = MccTable.countryCodeForMcc(Integer.parseInt(mcc));
                } catch ( NumberFormatException ex){
                    loge("pollStateDone: countryCodeForMcc error" + ex);
                } catch ( StringIndexOutOfBoundsException ex) {
                    loge("pollStateDone: countryCodeForMcc error" + ex);
                }

                mPhone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, iso);
                mGotCountryCode = true;

                TimeZone zone = null;

                if (!mNitzUpdatedTime && !mcc.equals("000") && !TextUtils.isEmpty(iso) &&
                        getAutoTimeZone()) {

                    // Test both paths if ignore nitz is true
                    boolean testOneUniqueOffsetPath = SystemProperties.getBoolean(
                                TelephonyProperties.PROPERTY_IGNORE_NITZ, false) &&
                                    ((SystemClock.uptimeMillis() & 1) == 0);

                    ArrayList<TimeZone> uniqueZones = TimeUtils.getTimeZonesWithUniqueOffsets(iso);
                    if ((uniqueZones.size() == 1) || testOneUniqueOffsetPath) {
                        zone = uniqueZones.get(0);
                        if (DBG) {
                           log("pollStateDone: no nitz but one TZ for iso-cc=" + iso +
                                   " with zone.getID=" + zone.getID() +
                                   " testOneUniqueOffsetPath=" + testOneUniqueOffsetPath);
                        }
                        setAndBroadcastNetworkSetTimeZone(zone.getID());
                    } else {
                        if (DBG) {
                            log("pollStateDone: there are " + uniqueZones.size() +
                                " unique offsets for iso-cc='" + iso +
                                " testOneUniqueOffsetPath=" + testOneUniqueOffsetPath +
                                "', do nothing");
                        }
                    }
                }

                if (shouldFixTimeZoneNow(mPhone, operatorNumeric, prevOperatorNumeric,
                        mNeedFixZoneAfterNitz)) {
                    // If the offset is (0, false) and the timezone property
                    // is set, use the timezone property rather than
                    // GMT.
                    String zoneName = SystemProperties.get(TIMEZONE_PROPERTY);
                    if (DBG) {
                        log("pollStateDone: fix time zone zoneName='" + zoneName +
                            "' mZoneOffset=" + mZoneOffset + " mZoneDst=" + mZoneDst +
                            " iso-cc='" + iso +
                            "' iso-cc-idx=" + Arrays.binarySearch(GMT_COUNTRY_CODES, iso));
                    }

                    if ("".equals(iso) && mNeedFixZoneAfterNitz) {
                        // Country code not found.  This is likely a test network.
                        // Get a TimeZone based only on the NITZ parameters (best guess).
                        zone = getNitzTimeZone(mZoneOffset, mZoneDst, mZoneTime);
                        if (DBG) log("pollStateDone: using NITZ TimeZone");
                    } else
                    // "(mZoneOffset == 0) && (mZoneDst == false) &&
                    //  (Arrays.binarySearch(GMT_COUNTRY_CODES, iso) < 0)"
                    // means that we received a NITZ string telling
                    // it is in GMT+0 w/ DST time zone
                    // BUT iso tells is NOT, e.g, a wrong NITZ reporting
                    // local time w/ 0 offset.
                    if ((mZoneOffset == 0) && (mZoneDst == false) &&
                        (zoneName != null) && (zoneName.length() > 0) &&
                        (Arrays.binarySearch(GMT_COUNTRY_CODES, iso) < 0)) {
                        zone = TimeZone.getDefault();
                        if (mNeedFixZoneAfterNitz) {
                            // For wrong NITZ reporting local time w/ 0 offset,
                            // need adjust time to reflect default timezone setting
                            long ctm = System.currentTimeMillis();
                            long tzOffset = zone.getOffset(ctm);
                            if (DBG) {
                                log("pollStateDone: tzOffset=" + tzOffset + " ltod=" +
                                        TimeUtils.logTimeOfDay(ctm));
                            }
                            if (getAutoTime()) {
                                long adj = ctm - tzOffset;
                                if (DBG) log("pollStateDone: adj ltod=" +
                                        TimeUtils.logTimeOfDay(adj));
                                setAndBroadcastNetworkSetTime(adj);
                            } else {
                                // Adjust the saved NITZ time to account for tzOffset.
                                mSavedTime = mSavedTime - tzOffset;
                            }
                        }
                        if (DBG) log("pollStateDone: using default TimeZone");
                    } else {
                        zone = TimeUtils.getTimeZone(mZoneOffset, mZoneDst, mZoneTime, iso);
                        if (DBG) log("pollStateDone: using getTimeZone(off, dst, time, iso)");
                    }

                    mNeedFixZoneAfterNitz = false;

                    if (zone != null) {
                        log("pollStateDone: zone != null zone.getID=" + zone.getID());
                        if (getAutoTimeZone()) {
                            setAndBroadcastNetworkSetTimeZone(zone.getID());
                        }
                        saveNitzTimeZone(zone.getID());
                    } else {
                        log("pollStateDone: zone == null");
                    }
                }
            }

            mPhone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISROAMING,
                mSS.getRoaming() ? "true" : "false");

            mPhone.notifyServiceStateChanged(mSS);
        }

        if (hasGprsAttached) {
            mAttachedRegistrants.notifyRegistrants();
        }

        if (hasGprsDetached) {
            mDetachedRegistrants.notifyRegistrants();
        }

        if (hasDataRegStateChanged || hasRilDataRadioTechnologyChanged) {
            notifyDataRegStateRilRadioTechnologyChanged();
            mPhone.notifyDataConnection(null);
        }

        if (hasRoamingOn) {
            mRoamingOnRegistrants.notifyRegistrants();
        }

        if (hasRoamingOff) {
            mRoamingOffRegistrants.notifyRegistrants();
        }

        if (hasLocationChanged) {
            mPhone.notifyLocationChanged();
        }

        if (! isGprsConsistent(mSS.getDataRegState(), mSS.getVoiceRegState())) {
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
        // TODO: Add GsmCellIdenity updating, see CdmaLteServiceStateTracker.
    }

    /**
     * Check if GPRS got registered while voice is registered.
     *
     * @param dataRegState i.e. CGREG in GSM
     * @param voiceRegState i.e. CREG in GSM
     * @return false if device only register to voice but not gprs
     */
    private boolean isGprsConsistent(int dataRegState, int voiceRegState) {
        return !((voiceRegState == ServiceState.STATE_IN_SERVICE) &&
                (dataRegState != ServiceState.STATE_IN_SERVICE));
    }

    /**
     * Returns a TimeZone object based only on parameters from the NITZ string.
     */
    private TimeZone getNitzTimeZone(int offset, boolean dst, long when) {
        TimeZone guess = findTimeZone(offset, dst, when);
        if (guess == null) {
            // Couldn't find a proper timezone.  Perhaps the DST data is wrong.
            guess = findTimeZone(offset, !dst, when);
        }
        if (DBG) log("getNitzTimeZone returning " + (guess == null ? guess : guess.getID()));
        return guess;
    }

    private TimeZone findTimeZone(int offset, boolean dst, long when) {
        int rawOffset = offset;
        if (dst) {
            rawOffset -= 3600000;
        }
        String[] zones = TimeZone.getAvailableIDs(rawOffset);
        TimeZone guess = null;
        Date d = new Date(when);
        for (String zone : zones) {
            TimeZone tz = TimeZone.getTimeZone(zone);
            if (tz.getOffset(when) == offset &&
                tz.inDaylightTime(d) == dst) {
                guess = tz;
                break;
            }
        }

        return guess;
    }

    private void queueNextSignalStrengthPoll() {
        if (mDontPollSignalStrength) {
            // The radio is telling us about signal strength changes
            // we don't have to ask it
            return;
        }

        Message msg;

        msg = obtainMessage();
        msg.what = EVENT_POLL_SIGNAL_STRENGTH;

        long nextTime;

        // TODO Don't poll signal strength if screen is off
        sendMessageDelayed(msg, POLL_PERIOD_MILLIS);
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

        if (ar.exception == null) {
            int[] ints = (int[])ar.result;
            int state = ints[0];

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
                if (!newRs.isCsRestricted()) {
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
                if (!newRs.isCsRestricted()) {
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
                if (!newRs.isCsRestricted()) {
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

    /** code is registration state 0-5 from TS 27.007 7.2 */
    private int regCodeToServiceState(int code) {
        switch (code) {
            case 0:
            case 2: // 2 is "searching"
            case 3: // 3 is "registration denied"
            case 4: // 4 is "unknown" no vaild in current baseband
            case 10:// same as 0, but indicates that emergency call is possible.
            case 12:// same as 2, but indicates that emergency call is possible.
            case 13:// same as 3, but indicates that emergency call is possible.
            case 14:// same as 4, but indicates that emergency call is possible.
                return ServiceState.STATE_OUT_OF_SERVICE;

            case 1:
                return ServiceState.STATE_IN_SERVICE;

            case 5:
                // in service, roam
                return ServiceState.STATE_IN_SERVICE;

            default:
                loge("regCodeToServiceState: unexpected service state " + code);
                return ServiceState.STATE_OUT_OF_SERVICE;
        }
    }


    /**
     * code is registration state 0-5 from TS 27.007 7.2
     * returns true if registered roam, false otherwise
     */
    private boolean regCodeIsRoaming (int code) {
        return ServiceState.RIL_REG_STATE_ROAMING == code;
    }

    /**
     * Set roaming state if operator mcc is the same as sim mcc
     * and ons is different from spn
     *
     * @param s ServiceState hold current ons
     * @return true if same operator
     */
    private boolean isSameNamedOperators(ServiceState s) {
        String spn = getSystemProperty(TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA, "empty");

        String onsl = s.getOperatorAlphaLong();
        String onss = s.getOperatorAlphaShort();

        boolean equalsOnsl = onsl != null && spn.equals(onsl);
        boolean equalsOnss = onss != null && spn.equals(onss);

        return currentMccEqualsSimMcc(s) && (equalsOnsl || equalsOnss);
    }

    /**
     * Compare SIM MCC with Operator MCC
     *
     * @param s ServiceState hold current ons
     * @return true if both are same
     */
    private boolean currentMccEqualsSimMcc(ServiceState s) {
        String simNumeric = getSystemProperty(
                TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC, "");
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
     + Can use mcc or mcc+mnc as item of config_operatorConsideredNonRoaming.
     * For example, 302 or 21407. If mcc or mcc+mnc match with operator,
     * don't set roaming state.
     *
     * @param s ServiceState hold current ons
     * @return false for roaming state set
     */
    private boolean isOperatorConsideredNonRoaming(ServiceState s) {
        String operatorNumeric = s.getOperatorNumeric();
        String[] numericArray = mPhone.getContext().getResources().getStringArray(
                    com.android.internal.R.array.config_operatorConsideredNonRoaming);

        if (numericArray.length == 0 || operatorNumeric == null) {
            return false;
        }

        for (String numeric : numericArray) {
            if (operatorNumeric.startsWith(numeric)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOperatorConsideredRoaming(ServiceState s) {
        String operatorNumeric = s.getOperatorNumeric();
        String[] numericArray = mPhone.getContext().getResources().getStringArray(
                    com.android.internal.R.array.config_sameNamedOperatorConsideredRoaming);

        if (numericArray.length == 0 || operatorNumeric == null) {
            return false;
        }

        for (String numeric : numericArray) {
            if (operatorNumeric.startsWith(numeric)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return The current GPRS state. IN_SERVICE is the same as "attached"
     * and OUT_OF_SERVICE is the same as detached.
     */
    @Override
    public int getCurrentDataConnectionState() {
        return mSS.getDataRegState();
    }

    /**
     * @return true if phone is camping on a technology (eg UMTS)
     * that could support voice and data simultaneously.
     */
    @Override
    public boolean isConcurrentVoiceAndDataAllowed() {
        return (mSS.getRilVoiceRadioTechnology() >= ServiceState.RIL_RADIO_TECHNOLOGY_UMTS);
    }

    /**
     * @return the current cell location information. Prefer Gsm location
     * information if available otherwise return LTE location information
     */
    public CellLocation getCellLocation() {
        if ((mCellLoc.getLac() >= 0) && (mCellLoc.getCid() >= 0)) {
            if (DBG) log("getCellLocation(): X good mCellLoc=" + mCellLoc);
            return mCellLoc;
        } else {
            List<CellInfo> result = getAllCellInfo();
            if (result != null) {
                // A hack to allow tunneling of LTE information via GsmCellLocation
                // so that older Network Location Providers can return some information
                // on LTE only networks, see bug 9228974.
                //
                // We'll search the return CellInfo array preferring GSM/WCDMA
                // data, but if there is none we'll tunnel the first LTE information
                // in the list.
                //
                // The tunnel'd LTE information is returned as follows:
                //   LAC = TAC field
                //   CID = CI field
                //   PSC = 0.
                GsmCellLocation cellLocOther = new GsmCellLocation();
                for (CellInfo ci : result) {
                    if (ci instanceof CellInfoGsm) {
                        CellInfoGsm cellInfoGsm = (CellInfoGsm)ci;
                        CellIdentityGsm cellIdentityGsm = cellInfoGsm.getCellIdentity();
                        cellLocOther.setLacAndCid(cellIdentityGsm.getLac(),
                                cellIdentityGsm.getCid());
                        cellLocOther.setPsc(cellIdentityGsm.getPsc());
                        if (DBG) log("getCellLocation(): X ret GSM info=" + cellLocOther);
                        return cellLocOther;
                    } else if (ci instanceof CellInfoWcdma) {
                        CellInfoWcdma cellInfoWcdma = (CellInfoWcdma)ci;
                        CellIdentityWcdma cellIdentityWcdma = cellInfoWcdma.getCellIdentity();
                        cellLocOther.setLacAndCid(cellIdentityWcdma.getLac(),
                                cellIdentityWcdma.getCid());
                        cellLocOther.setPsc(cellIdentityWcdma.getPsc());
                        if (DBG) log("getCellLocation(): X ret WCDMA info=" + cellLocOther);
                        return cellLocOther;
                    } else if ((ci instanceof CellInfoLte) &&
                            ((cellLocOther.getLac() < 0) || (cellLocOther.getCid() < 0))) {
                        // We'll return the first good LTE info we get if there is no better answer
                        CellInfoLte cellInfoLte = (CellInfoLte)ci;
                        CellIdentityLte cellIdentityLte = cellInfoLte.getCellIdentity();
                        if ((cellIdentityLte.getTac() != Integer.MAX_VALUE)
                                && (cellIdentityLte.getCi() != Integer.MAX_VALUE)) {
                            cellLocOther.setLacAndCid(cellIdentityLte.getTac(),
                                    cellIdentityLte.getCi());
                            cellLocOther.setPsc(0);
                            if (DBG) {
                                log("getCellLocation(): possible LTE cellLocOther=" + cellLocOther);
                            }
                        }
                    }
                }
                if (DBG) {
                    log("getCellLocation(): X ret best answer cellLocOther=" + cellLocOther);
                }
                return cellLocOther;
            } else {
                if (DBG) {
                    log("getCellLocation(): X empty mCellLoc and CellInfo mCellLoc=" + mCellLoc);
                }
                return mCellLoc;
            }
        }
    }

    /**
     * nitzReceiveTime is time_t that the NITZ time was posted
     */
    private void setTimeFromNITZString (String nitz, long nitzReceiveTime) {
        // "yy/mm/dd,hh:mm:ss(+/-)tz"
        // tz is in number of quarter-hours

        long start = SystemClock.elapsedRealtime();
        if (DBG) {log("NITZ: " + nitz + "," + nitzReceiveTime +
                        " start=" + start + " delay=" + (start - nitzReceiveTime));
        }

        try {
            /* NITZ time (hour:min:sec) will be in UTC but it supplies the timezone
             * offset as well (which we won't worry about until later) */
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("GMT"));

            c.clear();
            c.set(Calendar.DST_OFFSET, 0);

            String[] nitzSubs = nitz.split("[/:,+-]");

            int year = 2000 + Integer.parseInt(nitzSubs[0]);
            c.set(Calendar.YEAR, year);

            // month is 0 based!
            int month = Integer.parseInt(nitzSubs[1]) - 1;
            c.set(Calendar.MONTH, month);

            int date = Integer.parseInt(nitzSubs[2]);
            c.set(Calendar.DATE, date);

            int hour = Integer.parseInt(nitzSubs[3]);
            c.set(Calendar.HOUR, hour);

            int minute = Integer.parseInt(nitzSubs[4]);
            c.set(Calendar.MINUTE, minute);

            int second = Integer.parseInt(nitzSubs[5]);
            c.set(Calendar.SECOND, second);

            boolean sign = (nitz.indexOf('-') == -1);

            int tzOffset = Integer.parseInt(nitzSubs[6]);

            int dst = (nitzSubs.length >= 8 ) ? Integer.parseInt(nitzSubs[7])
                                              : 0;

            // The zone offset received from NITZ is for current local time,
            // so DST correction is already applied.  Don't add it again.
            //
            // tzOffset += dst * 4;
            //
            // We could unapply it if we wanted the raw offset.

            tzOffset = (sign ? 1 : -1) * tzOffset * 15 * 60 * 1000;

            TimeZone    zone = null;

            // As a special extension, the Android emulator appends the name of
            // the host computer's timezone to the nitz string. this is zoneinfo
            // timezone name of the form Area!Location or Area!Location!SubLocation
            // so we need to convert the ! into /
            if (nitzSubs.length >= 9) {
                String  tzname = nitzSubs[8].replace('!','/');
                zone = TimeZone.getTimeZone( tzname );
            }

            String iso = getSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, "");

            if (zone == null) {

                if (mGotCountryCode) {
                    if (iso != null && iso.length() > 0) {
                        zone = TimeUtils.getTimeZone(tzOffset, dst != 0,
                                c.getTimeInMillis(),
                                iso);
                    } else {
                        // We don't have a valid iso country code.  This is
                        // most likely because we're on a test network that's
                        // using a bogus MCC (eg, "001"), so get a TimeZone
                        // based only on the NITZ parameters.
                        zone = getNitzTimeZone(tzOffset, (dst != 0), c.getTimeInMillis());
                    }
                }
            }

            if ((zone == null) || (mZoneOffset != tzOffset) || (mZoneDst != (dst != 0))){
                // We got the time before the country or the zone has changed
                // so we don't know how to identify the DST rules yet.  Save
                // the information and hope to fix it up later.

                mNeedFixZoneAfterNitz = true;
                mZoneOffset  = tzOffset;
                mZoneDst     = dst != 0;
                mZoneTime    = c.getTimeInMillis();
            }

            if (zone != null) {
                if (getAutoTimeZone()) {
                    setAndBroadcastNetworkSetTimeZone(zone.getID());
                }
                saveNitzTimeZone(zone.getID());
            }

            String ignore = SystemProperties.get("gsm.ignore-nitz");
            if (ignore != null && ignore.equals("yes")) {
                log("NITZ: Not setting clock because gsm.ignore-nitz is set");
                return;
            }

            try {
                mWakeLock.acquire();

                if (getAutoTime()) {
                    long millisSinceNitzReceived
                            = SystemClock.elapsedRealtime() - nitzReceiveTime;

                    if (millisSinceNitzReceived < 0) {
                        // Sanity check: something is wrong
                        if (DBG) {
                            log("NITZ: not setting time, clock has rolled "
                                            + "backwards since NITZ time was received, "
                                            + nitz);
                        }
                        return;
                    }

                    if (millisSinceNitzReceived > Integer.MAX_VALUE) {
                        // If the time is this far off, something is wrong > 24 days!
                        if (DBG) {
                            log("NITZ: not setting time, processing has taken "
                                        + (millisSinceNitzReceived / (1000 * 60 * 60 * 24))
                                        + " days");
                        }
                        return;
                    }

                    // Note: with range checks above, cast to int is safe
                    c.add(Calendar.MILLISECOND, (int)millisSinceNitzReceived);

                    if (DBG) {
                        log("NITZ: Setting time of day to " + c.getTime()
                            + " NITZ receive delay(ms): " + millisSinceNitzReceived
                            + " gained(ms): "
                            + (c.getTimeInMillis() - System.currentTimeMillis())
                            + " from " + nitz);
                    }

                    setAndBroadcastNetworkSetTime(c.getTimeInMillis());
                    Rlog.i(LOG_TAG, "NITZ: after Setting time of day");
                }
                SystemProperties.set("gsm.nitz.time", String.valueOf(c.getTimeInMillis()));
                saveNitzTime(c.getTimeInMillis());
                if (VDBG) {
                    long end = SystemClock.elapsedRealtime();
                    log("NITZ: end=" + end + " dur=" + (end - start));
                }
                mNitzUpdatedTime = true;
            } finally {
                mWakeLock.release();
            }
        } catch (RuntimeException ex) {
            loge("NITZ: Parsing NITZ time " + nitz + " ex=" + ex);
        }
    }

    private boolean getAutoTime() {
        try {
            return Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                    Settings.Global.AUTO_TIME) > 0;
        } catch (SettingNotFoundException snfe) {
            return true;
        }
    }

    private boolean getAutoTimeZone() {
        try {
            return Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                    Settings.Global.AUTO_TIME_ZONE) > 0;
        } catch (SettingNotFoundException snfe) {
            return true;
        }
    }

    private void saveNitzTimeZone(String zoneId) {
        mSavedTimeZone = zoneId;
    }

    private void saveNitzTime(long time) {
        mSavedTime = time;
        mSavedAtTime = SystemClock.elapsedRealtime();
    }

    /**
     * Set the timezone and send out a sticky broadcast so the system can
     * determine if the timezone was set by the carrier.
     *
     * @param zoneId timezone set by carrier
     */
    private void setAndBroadcastNetworkSetTimeZone(String zoneId) {
        if (DBG) log("setAndBroadcastNetworkSetTimeZone: setTimeZone=" + zoneId);
        AlarmManager alarm =
            (AlarmManager) mPhone.getContext().getSystemService(Context.ALARM_SERVICE);
        alarm.setTimeZone(zoneId);
        Intent intent = new Intent(TelephonyIntents.ACTION_NETWORK_SET_TIMEZONE);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("time-zone", zoneId);
        mPhone.getContext().sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        if (DBG) {
            log("setAndBroadcastNetworkSetTimeZone: call alarm.setTimeZone and broadcast zoneId=" +
                zoneId);
        }
    }

    /**
     * Set the time and Send out a sticky broadcast so the system can determine
     * if the time was set by the carrier.
     *
     * @param time time set by network
     */
    private void setAndBroadcastNetworkSetTime(long time) {
        if (DBG) log("setAndBroadcastNetworkSetTime: time=" + time + "ms");
        SystemClock.setCurrentTimeMillis(time);
        Intent intent = new Intent(TelephonyIntents.ACTION_NETWORK_SET_TIME);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("time", time);
        mPhone.getContext().sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void revertToNitzTime() {
        if (Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                Settings.Global.AUTO_TIME, 0) == 0) {
            return;
        }
        if (DBG) {
            log("Reverting to NITZ Time: mSavedTime=" + mSavedTime
                + " mSavedAtTime=" + mSavedAtTime);
        }
        if (mSavedTime != 0 && mSavedAtTime != 0) {
            setAndBroadcastNetworkSetTime(mSavedTime
                    + (SystemClock.elapsedRealtime() - mSavedAtTime));
        }
    }

    private void revertToNitzTimeZone() {
        if (Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                Settings.Global.AUTO_TIME_ZONE, 0) == 0) {
            return;
        }
        if (DBG) log("Reverting to NITZ TimeZone: tz='" + mSavedTimeZone);
        if (mSavedTimeZone != null) {
            setAndBroadcastNetworkSetTimeZone(mSavedTimeZone);
        }
    }

    /**
     * Post a notification to NotificationManager for restricted state
     *
     * @param notifyType is one state of PS/CS_*_ENABLE/DISABLE
     */
    private void setNotification(int notifyType) {

        if (DBG) log("setNotification: create notification " + notifyType);
        Context context = mPhone.getContext();

        mNotification = new Notification();
        mNotification.when = System.currentTimeMillis();
        mNotification.flags = Notification.FLAG_AUTO_CANCEL;
        mNotification.icon = com.android.internal.R.drawable.stat_sys_warning;
        Intent intent = new Intent();
        mNotification.contentIntent = PendingIntent
        .getActivity(context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);

        CharSequence details = "";
        CharSequence title = context.getText(com.android.internal.R.string.RestrictedChangedTitle);
        int notificationId = CS_NOTIFICATION;

        switch (notifyType) {
        case PS_ENABLED:
            notificationId = PS_NOTIFICATION;
            details = context.getText(com.android.internal.R.string.RestrictedOnData);
            break;
        case PS_DISABLED:
            notificationId = PS_NOTIFICATION;
            break;
        case CS_ENABLED:
            details = context.getText(com.android.internal.R.string.RestrictedOnAllVoice);
            break;
        case CS_NORMAL_ENABLED:
            details = context.getText(com.android.internal.R.string.RestrictedOnNormal);
            break;
        case CS_EMERGENCY_ENABLED:
            details = context.getText(com.android.internal.R.string.RestrictedOnEmergency);
            break;
        case CS_DISABLED:
            // do nothing and cancel the notification later
            break;
        }

        if (DBG) log("setNotification: put notification " + title + " / " +details);
        mNotification.tickerText = title;
        mNotification.color = context.getResources().getColor(
                com.android.internal.R.color.system_notification_accent_color);
        mNotification.setLatestEventInfo(context, title, details,
                mNotification.contentIntent);

        NotificationManager notificationManager = (NotificationManager)
            context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (notifyType == PS_DISABLED || notifyType == CS_DISABLED) {
            // cancel previous post notification
            notificationManager.cancel(notificationId);
        } else {
            // update restricted state notification
            notificationManager.notify(notificationId, mNotification);
        }
    }

    private UiccCardApplication getUiccCardApplication() {
            return  mUiccController.getUiccCardApplication(mPhone.getPhoneId(),
                    UiccController.APP_FAM_3GPP);
    }

    @Override
    protected void onUpdateIccAvailability() {
        if (mUiccController == null ) {
            return;
        }

        UiccCardApplication newUiccApplication = getUiccCardApplication();

        if (mUiccApplcation != newUiccApplication) {
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
                mUiccApplcation.registerForReady(this, EVENT_SIM_READY, null);
                if (mIccRecords != null) {
                    mIccRecords.registerForRecordsLoaded(this, EVENT_SIM_RECORDS_LOADED, null);
                }
            }
        }
    }
    @Override
    protected void log(String s) {
        Rlog.d(LOG_TAG, "[GsmSST] " + s);
    }

    @Override
    protected void loge(String s) {
        Rlog.e(LOG_TAG, "[GsmSST] " + s);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("GsmServiceStateTracker extends:");
        super.dump(fd, pw, args);
        pw.println(" mPhone=" + mPhone);
        pw.println(" mSS=" + mSS);
        pw.println(" mNewSS=" + mNewSS);
        pw.println(" mCellLoc=" + mCellLoc);
        pw.println(" mNewCellLoc=" + mNewCellLoc);
        pw.println(" mPreferredNetworkType=" + mPreferredNetworkType);
        pw.println(" mMaxDataCalls=" + mMaxDataCalls);
        pw.println(" mNewMaxDataCalls=" + mNewMaxDataCalls);
        pw.println(" mReasonDataDenied=" + mReasonDataDenied);
        pw.println(" mNewReasonDataDenied=" + mNewReasonDataDenied);
        pw.println(" mGsmRoaming=" + mGsmRoaming);
        pw.println(" mDataRoaming=" + mDataRoaming);
        pw.println(" mEmergencyOnly=" + mEmergencyOnly);
        pw.println(" mNeedFixZoneAfterNitz=" + mNeedFixZoneAfterNitz);
        pw.println(" mZoneOffset=" + mZoneOffset);
        pw.println(" mZoneDst=" + mZoneDst);
        pw.println(" mZoneTime=" + mZoneTime);
        pw.println(" mGotCountryCode=" + mGotCountryCode);
        pw.println(" mNitzUpdatedTime=" + mNitzUpdatedTime);
        pw.println(" mSavedTimeZone=" + mSavedTimeZone);
        pw.println(" mSavedTime=" + mSavedTime);
        pw.println(" mSavedAtTime=" + mSavedAtTime);
        pw.println(" mStartedGprsRegCheck=" + mStartedGprsRegCheck);
        pw.println(" mReportedGprsNoReg=" + mReportedGprsNoReg);
        pw.println(" mNotification=" + mNotification);
        pw.println(" mWakeLock=" + mWakeLock);
        pw.println(" mCurSpn=" + mCurSpn);
        pw.println(" mCurShowSpn=" + mCurShowSpn);
        pw.println(" mCurPlmn=" + mCurPlmn);
        pw.println(" mCurShowPlmn=" + mCurShowPlmn);
    }


    /**
     * Clean up existing voice and data connection then turn off radio power.
     *
     * Hang up the existing voice calls to decrease call drop rate.
     */
    @Override
    public void powerOffRadioSafely(DcTrackerBase dcTracker) {
        synchronized (this) {
            if (!mPendingRadioPowerOffAfterDataOff) {
                long dds = SubscriptionManager.getDefaultDataSubId();
                // To minimize race conditions we call cleanUpAllConnections on
                // both if else paths instead of before this isDisconnected test.
                if (dcTracker.isDisconnected()
                        && (dds == mPhone.getSubId()
                            || (dds != mPhone.getSubId()
                                && ProxyController.getInstance().isDataDisconnected(dds)))) {
                    // To minimize race conditions we do this after isDisconnected
                    dcTracker.cleanUpAllConnections(Phone.REASON_RADIO_TURNED_OFF);
                    if (DBG) log("Data disconnected, turn off radio right away.");
                    hangupAndPowerOff();
                } else {
                    dcTracker.cleanUpAllConnections(Phone.REASON_RADIO_TURNED_OFF);
                    if (dds != mPhone.getSubId()
                            && !ProxyController.getInstance().isDataDisconnected(dds)) {
                        if (DBG) log("Data is active on DDS.  Wait for all data disconnect");
                        // Data is not disconnected on DDS. Wait for the data disconnect complete
                        // before sending the RADIO_POWER off.
                        ProxyController.getInstance().registerForAllDataDisconnected(dds, this,
                                EVENT_ALL_DATA_DISCONNECTED, null);
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

    public void setImsRegistrationState(boolean registered){
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
}
