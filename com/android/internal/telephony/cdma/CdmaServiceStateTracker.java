/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.telephony.cdma;

import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionManager;
import android.telephony.cdma.CdmaCellLocation;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.TimeUtils;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.CommandsInterface.RadioState;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.dataconnection.DcTrackerBase;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.HbpcdUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * {@hide}
 */
public class CdmaServiceStateTracker extends ServiceStateTracker {
    static final String LOG_TAG = "CdmaSST";

    CDMAPhone mPhone;
    CdmaCellLocation mCellLoc;
    CdmaCellLocation mNewCellLoc;

    // Min values used to by getOtasp()
    private static final String UNACTIVATED_MIN2_VALUE = "000000";
    private static final String UNACTIVATED_MIN_VALUE = "1111110111";

    private static final int MS_PER_HOUR = 60 * 60 * 1000;

    // Current Otasp value
    int mCurrentOtaspMode = OTASP_UNINITIALIZED;

     /** if time between NITZ updates is less than mNitzUpdateSpacing the update may be ignored. */
    private static final int NITZ_UPDATE_SPACING_DEFAULT = 1000 * 60 * 10;
    private int mNitzUpdateSpacing = SystemProperties.getInt("ro.nitz_update_spacing",
            NITZ_UPDATE_SPACING_DEFAULT);

    /** If mNitzUpdateSpacing hasn't been exceeded but update is > mNitzUpdate do the update */
    private static final int NITZ_UPDATE_DIFF_DEFAULT = 2000;
    private int mNitzUpdateDiff = SystemProperties.getInt("ro.nitz_update_diff",
            NITZ_UPDATE_DIFF_DEFAULT);

    private int mRoamingIndicator;
    private boolean mIsInPrl;
    private int mDefaultRoamingIndicator;

    /**
     * Initially assume no data connection.
     */
    protected int mRegistrationState = -1;
    protected RegistrantList mCdmaForSubscriptionInfoReadyRegistrants = new RegistrantList();

    /**
     * Sometimes we get the NITZ time before we know what country we
     * are in. Keep the time zone information from the NITZ string so
     * we can fix the time zone once know the country.
     */
    protected boolean mNeedFixZone = false;
    private int mZoneOffset;
    private boolean mZoneDst;
    private long mZoneTime;
    protected boolean mGotCountryCode = false;
    String mSavedTimeZone;
    long mSavedTime;
    long mSavedAtTime;

    /** Wake lock used while setting time of day. */
    private PowerManager.WakeLock mWakeLock;
    private static final String WAKELOCK_TAG = "ServiceStateTracker";

    protected String mMdn;
    protected int mHomeSystemId[] = null;
    protected int mHomeNetworkId[] = null;
    protected String mMin;
    protected String mPrlVersion;
    protected boolean mIsMinInfoReady = false;

    private boolean mIsEriTextLoaded = false;
    protected boolean mIsSubscriptionFromRuim = false;
    private CdmaSubscriptionSourceManager mCdmaSSM;

    protected static final String INVALID_MCC = "000";
    protected static final String DEFAULT_MNC = "00";

    protected HbpcdUtils mHbpcdUtils = null;

    /* Used only for debugging purposes. */
    private String mRegistrationDeniedReason;

    private ContentResolver mCr;
    private String mCurrentCarrier = null;

    private ContentObserver mAutoTimeObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            if (DBG) log("Auto time state changed");
            revertToNitzTime();
        }
    };

    private ContentObserver mAutoTimeZoneObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            if (DBG) log("Auto time zone state changed");
            revertToNitzTimeZone();
        }
    };

    public CdmaServiceStateTracker(CDMAPhone phone) {
        this(phone, new CellInfoCdma());
    }

    protected CdmaServiceStateTracker(CDMAPhone phone, CellInfo cellInfo) {
        super(phone, phone.mCi, cellInfo);

        mPhone = phone;
        mCr = phone.getContext().getContentResolver();
        mCellLoc = new CdmaCellLocation();
        mNewCellLoc = new CdmaCellLocation();

        mCdmaSSM = CdmaSubscriptionSourceManager.getInstance(phone.getContext(), mCi, this,
                EVENT_CDMA_SUBSCRIPTION_SOURCE_CHANGED, null);
        mIsSubscriptionFromRuim = (mCdmaSSM.getCdmaSubscriptionSource() ==
                          CdmaSubscriptionSourceManager.SUBSCRIPTION_FROM_RUIM);

        PowerManager powerManager =
                (PowerManager)phone.getContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);

        mCi.registerForRadioStateChanged(this, EVENT_RADIO_STATE_CHANGED, null);

        mCi.registerForVoiceNetworkStateChanged(this, EVENT_NETWORK_STATE_CHANGED_CDMA, null);
        mCi.setOnNITZTime(this, EVENT_NITZ_TIME, null);

        mCi.registerForCdmaPrlChanged(this, EVENT_CDMA_PRL_VERSION_CHANGED, null);
        phone.registerForEriFileLoaded(this, EVENT_ERI_FILE_LOADED, null);
        mCi.registerForCdmaOtaProvision(this,EVENT_OTA_PROVISION_STATUS_CHANGE, null);

        // System setting property AIRPLANE_MODE_ON is set in Settings.
        int airplaneMode = Settings.Global.getInt(mCr, Settings.Global.AIRPLANE_MODE_ON, 0);
        mDesiredPowerState = ! (airplaneMode > 0);

        mCr.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AUTO_TIME), true,
                mAutoTimeObserver);
        mCr.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.AUTO_TIME_ZONE), true,
            mAutoTimeZoneObserver);
        setSignalStrengthDefaultValues();

        mHbpcdUtils = new HbpcdUtils(phone.getContext());

        // Reset OTASP state in case previously set by another service
        phone.notifyOtaspChanged(OTASP_UNINITIALIZED);
    }

    @Override
    public void dispose() {
        checkCorrectThread();
        log("ServiceStateTracker dispose");

        // Unregister for all events.
        mCi.unregisterForRadioStateChanged(this);
        mCi.unregisterForVoiceNetworkStateChanged(this);
        mCi.unregisterForCdmaOtaProvision(this);
        mPhone.unregisterForEriFileLoaded(this);
        if (mUiccApplcation != null) {mUiccApplcation.unregisterForReady(this);}
        if (mIccRecords != null) {mIccRecords.unregisterForRecordsLoaded(this);}
        mCi.unSetOnNITZTime(this);
        mCr.unregisterContentObserver(mAutoTimeObserver);
        mCr.unregisterContentObserver(mAutoTimeZoneObserver);
        mCdmaSSM.dispose(this);
        mCi.unregisterForCdmaPrlChanged(this);
        super.dispose();
    }

    @Override
    protected void finalize() {
        if (DBG) log("CdmaServiceStateTracker finalized");
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
                source );
        log("Read from settings: " + Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                    Settings.Global.CDMA_SUBSCRIPTION_MODE, -1));
    }

    private void getSubscriptionInfoAndStartPollingThreads() {
        mCi.getCDMASubscription(obtainMessage(EVENT_POLL_STATE_CDMA_SUBSCRIPTION));

        // Get Registration Information
        pollState();
    }

    @Override
    public void handleMessage (Message msg) {
        AsyncResult ar;
        int[] ints;
        String[] strings;

        if (!mPhone.mIsTheCurrentActivePhone) {
            loge("Received message " + msg + "[" + msg.what + "]" +
                    " while being destroyed. Ignoring.");
            return;
        }

        switch (msg.what) {
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

            mPhone.prepareEri();
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

        case EVENT_RADIO_STATE_CHANGED:
            if(mCi.getRadioState() == RadioState.RADIO_ON) {
                handleCdmaSubscriptionSource(mCdmaSSM.getCdmaSubscriptionSource());

                // Signal strength polling stops when radio is off.
                queueNextSignalStrengthPoll();
            }
            // This will do nothing in the 'radio not available' case.
            setPowerStateToDesired();
            pollState();
            break;

        case EVENT_NETWORK_STATE_CHANGED_CDMA:
            pollState();
            break;

        case EVENT_GET_SIGNAL_STRENGTH:
            // This callback is called when signal strength is polled
            // all by itself.

            if (!(mCi.getRadioState().isOn())) {
                // Polling will continue when radio turns back on.
                return;
            }
            ar = (AsyncResult) msg.obj;
            onSignalStrengthResult(ar, false);
            queueNextSignalStrengthPoll();

            break;

        case EVENT_GET_LOC_DONE_CDMA:
            ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                String states[] = (String[])ar.result;
                int baseStationId = -1;
                int baseStationLatitude = CdmaCellLocation.INVALID_LAT_LONG;
                int baseStationLongitude = CdmaCellLocation.INVALID_LAT_LONG;
                int systemId = -1;
                int networkId = -1;

                if (states.length > 9) {
                    try {
                        if (states[4] != null) {
                            baseStationId = Integer.parseInt(states[4]);
                        }
                        if (states[5] != null) {
                            baseStationLatitude = Integer.parseInt(states[5]);
                        }
                        if (states[6] != null) {
                            baseStationLongitude = Integer.parseInt(states[6]);
                        }
                        // Some carriers only return lat-lngs of 0,0
                        if (baseStationLatitude == 0 && baseStationLongitude == 0) {
                            baseStationLatitude  = CdmaCellLocation.INVALID_LAT_LONG;
                            baseStationLongitude = CdmaCellLocation.INVALID_LAT_LONG;
                        }
                        if (states[8] != null) {
                            systemId = Integer.parseInt(states[8]);
                        }
                        if (states[9] != null) {
                            networkId = Integer.parseInt(states[9]);
                        }
                    } catch (NumberFormatException ex) {
                        loge("error parsing cell location data: " + ex);
                    }
                }

                mCellLoc.setCellLocationData(baseStationId, baseStationLatitude,
                        baseStationLongitude, systemId, networkId);
                mPhone.notifyLocationChanged();
            }

            // Release any temporary cell lock, which could have been
            // acquired to allow a single-shot location update.
            disableSingleLocationUpdate();
            break;

        case EVENT_POLL_STATE_REGISTRATION_CDMA:
        case EVENT_POLL_STATE_OPERATOR_CDMA:
        case EVENT_POLL_STATE_GPRS:
            ar = (AsyncResult) msg.obj;
            handlePollStateResult(msg.what, ar);
            break;

        case EVENT_POLL_STATE_CDMA_SUBSCRIPTION: // Handle RIL_CDMA_SUBSCRIPTION
            ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                String cdmaSubscription[] = (String[])ar.result;
                if (cdmaSubscription != null && cdmaSubscription.length >= 5) {
                    mMdn = cdmaSubscription[0];
                    parseSidNid(cdmaSubscription[1], cdmaSubscription[2]);

                    mMin = cdmaSubscription[3];
                    mPrlVersion = cdmaSubscription[4];
                    if (DBG) log("GET_CDMA_SUBSCRIPTION: MDN=" + mMdn);

                    mIsMinInfoReady = true;

                    updateOtaspState();
                    if (!mIsSubscriptionFromRuim && mIccRecords != null) {
                        if (DBG) {
                            log("GET_CDMA_SUBSCRIPTION set imsi in mIccRecords");
                        }
                        mIccRecords.setImsi(getImsi());
                    } else {
                        if (DBG) {
                            log("GET_CDMA_SUBSCRIPTION either mIccRecords is null  or NV type device" +
                                    " - not setting Imsi in mIccRecords");
                        }
                    }
                } else {
                    if (DBG) {
                        log("GET_CDMA_SUBSCRIPTION: error parsing cdmaSubscription params num="
                            + cdmaSubscription.length);
                    }
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
            // This is a notification from CommandsInterface.setOnSignalStrengthUpdate.

            ar = (AsyncResult) msg.obj;

            // The radio is telling us about signal strength changes,
            // so we don't have to ask it.
            mDontPollSignalStrength = true;

            onSignalStrengthResult(ar, false);
            break;

        case EVENT_RUIM_RECORDS_LOADED:
            log("EVENT_RUIM_RECORDS_LOADED: what=" + msg.what);
            updatePhoneObject();
            updateSpnDisplay();
            break;

        case EVENT_LOCATION_UPDATES_ENABLED:
            ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                mCi.getVoiceRegistrationState(obtainMessage(EVENT_GET_LOC_DONE_CDMA, null));
            }
            break;

        case EVENT_ERI_FILE_LOADED:
            // Repoll the state once the ERI file has been loaded.
            if (DBG) log("[CdmaServiceStateTracker] ERI file has been loaded, repolling.");
            pollState();
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

        case EVENT_CHANGE_IMS_STATE:
            if (DBG) log("EVENT_CHANGE_IMS_STATE");
            setPowerStateToDesired();
            break;

        case EVENT_POLL_STATE_NETWORK_SELECTION_MODE:
            if (DBG) log("EVENT_POLL_STATE_NETWORK_SELECTION_MODE");
            ar = (AsyncResult) msg.obj;
            if (ar.exception == null && ar.result != null) {
                ints = (int[])ar.result;
                if (ints[0] == 1) {  // Manual selection.
                    mPhone.setNetworkSelectionModeAutomatic(null);
                }
            } else {
                log("Unable to getNetworkSelectionMode");
            }
            break;

        default:
            super.handleMessage(msg);
        break;
        }
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

    @Override
    protected void setPowerStateToDesired() {
        // If we want it on and it's off, turn it on
        if (mDesiredPowerState
            && mCi.getRadioState() == CommandsInterface.RadioState.RADIO_OFF) {
            mCi.setRadioPower(true, null);
        } else if (!mDesiredPowerState && mCi.getRadioState().isOn()) {
            DcTrackerBase dcTracker = mPhone.mDcTracker;

            // If it's on and available and we want it off gracefully
            powerOffRadioSafely(dcTracker);
        } else if (mDeviceShuttingDown && mCi.getRadioState().isAvailable()) {
            mCi.requestShutdown(null);
        }
    }

    @Override
    protected void updateSpnDisplay() {
        // mOperatorAlphaLong contains the ERI text
        String plmn = mSS.getOperatorAlphaLong();
        boolean showPlmn = false;

        if (!TextUtils.equals(plmn, mCurPlmn)) {
            // Allow A blank plmn, "" to set showPlmn to true. Previously, we
            // would set showPlmn to true only if plmn was not empty, i.e. was not
            // null and not blank. But this would cause us to incorrectly display
            // "No Service". Now showPlmn is set to true for any non null string.
            showPlmn = plmn != null;
            if (DBG) {
                log(String.format("updateSpnDisplay: changed sending intent" +
                            " showPlmn='%b' plmn='%s'", showPlmn, plmn));
            }
            Intent intent = new Intent(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            intent.putExtra(TelephonyIntents.EXTRA_SHOW_SPN, false);
            intent.putExtra(TelephonyIntents.EXTRA_SPN, "");
            intent.putExtra(TelephonyIntents.EXTRA_SHOW_PLMN, showPlmn);
            intent.putExtra(TelephonyIntents.EXTRA_PLMN, plmn);
            SubscriptionManager.putPhoneIdAndSubIdExtra(intent, mPhone.getPhoneId());
            mPhone.getContext().sendStickyBroadcastAsUser(intent, UserHandle.ALL);

            if (!mSubscriptionController.setPlmnSpn(mPhone.getPhoneId(),
                    showPlmn, plmn, false, "")) {
                mSpnUpdatePending = true;
            }
        }

        mCurShowSpn = false;
        mCurShowPlmn = showPlmn;
        mCurSpn = "";
        mCurPlmn = plmn;
    }

    @Override
    protected Phone getPhone() {
        return mPhone;
    }

    /**
    * Hanlde the PollStateResult message
    */
    protected void handlePollStateResultMessage(int what, AsyncResult ar){
        int ints[];
        String states[];
        switch (what) {
            case EVENT_POLL_STATE_GPRS: {
                states = (String[])ar.result;
                if (DBG) {
                    log("handlePollStateResultMessage: EVENT_POLL_STATE_GPRS states.length=" +
                            states.length + " states=" + states);
                }

                int regState = ServiceState.RIL_REG_STATE_UNKNOWN;
                int dataRadioTechnology = 0;

                if (states.length > 0) {
                    try {
                        regState = Integer.parseInt(states[0]);
    
                        // states[3] (if present) is the current radio technology
                        if (states.length >= 4 && states[3] != null) {
                            dataRadioTechnology = Integer.parseInt(states[3]);
                        }
                    } catch (NumberFormatException ex) {
                        loge("handlePollStateResultMessage: error parsing GprsRegistrationState: "
                                        + ex);
                    }
                }

                int dataRegState = regCodeToServiceState(regState);
                mNewSS.setDataRegState(dataRegState);
                mNewSS.setRilDataRadioTechnology(dataRadioTechnology);
                mNewSS.setDataRoaming(regCodeIsRoaming(regState));
                if (DBG) {
                    log("handlPollStateResultMessage: cdma setDataRegState=" + dataRegState
                            + " regState=" + regState
                            + " dataRadioTechnology=" + dataRadioTechnology);
                }
                break;
            }

            case EVENT_POLL_STATE_REGISTRATION_CDMA: // Handle RIL_REQUEST_REGISTRATION_STATE.
                states = (String[])ar.result;

                int registrationState = 4;     //[0] registrationState
                int radioTechnology = -1;      //[3] radioTechnology
                int baseStationId = -1;        //[4] baseStationId
                //[5] baseStationLatitude
                int baseStationLatitude = CdmaCellLocation.INVALID_LAT_LONG;
                //[6] baseStationLongitude
                int baseStationLongitude = CdmaCellLocation.INVALID_LAT_LONG;
                int cssIndicator = 0;          //[7] init with 0, because it is treated as a boolean
                int systemId = 0;              //[8] systemId
                int networkId = 0;             //[9] networkId
                int roamingIndicator = -1;     //[10] Roaming indicator
                int systemIsInPrl = 0;         //[11] Indicates if current system is in PRL
                int defaultRoamingIndicator = 0;  //[12] Is default roaming indicator from PRL
                int reasonForDenial = 0;       //[13] Denial reason if registrationState = 3

                if (states.length >= 14) {
                    try {
                        if (states[0] != null) {
                            registrationState = Integer.parseInt(states[0]);
                        }
                        if (states[3] != null) {
                            radioTechnology = Integer.parseInt(states[3]);
                        }
                        if (states[4] != null) {
                            baseStationId = Integer.parseInt(states[4]);
                        }
                        if (states[5] != null) {
                            baseStationLatitude = Integer.parseInt(states[5]);
                        }
                        if (states[6] != null) {
                            baseStationLongitude = Integer.parseInt(states[6]);
                        }
                        // Some carriers only return lat-lngs of 0,0
                        if (baseStationLatitude == 0 && baseStationLongitude == 0) {
                            baseStationLatitude  = CdmaCellLocation.INVALID_LAT_LONG;
                            baseStationLongitude = CdmaCellLocation.INVALID_LAT_LONG;
                        }
                        if (states[7] != null) {
                            cssIndicator = Integer.parseInt(states[7]);
                        }
                        if (states[8] != null) {
                            systemId = Integer.parseInt(states[8]);
                        }
                        if (states[9] != null) {
                            networkId = Integer.parseInt(states[9]);
                        }
                        if (states[10] != null) {
                            roamingIndicator = Integer.parseInt(states[10]);
                        }
                        if (states[11] != null) {
                            systemIsInPrl = Integer.parseInt(states[11]);
                        }
                        if (states[12] != null) {
                            defaultRoamingIndicator = Integer.parseInt(states[12]);
                        }
                        if (states[13] != null) {
                            reasonForDenial = Integer.parseInt(states[13]);
                        }
                    } catch (NumberFormatException ex) {
                        loge("EVENT_POLL_STATE_REGISTRATION_CDMA: error parsing: " + ex);
                    }
                } else {
                    throw new RuntimeException("Warning! Wrong number of parameters returned from "
                                         + "RIL_REQUEST_REGISTRATION_STATE: expected 14 or more "
                                         + "strings and got " + states.length + " strings");
                }

                mRegistrationState = registrationState;
                // When registration state is roaming and TSB58
                // roaming indicator is not in the carrier-specified
                // list of ERIs for home system, mCdmaRoaming is true.
                boolean cdmaRoaming =
                        regCodeIsRoaming(registrationState) && !isRoamIndForHomeSystem(states[10]);
                mNewSS.setVoiceRoaming(cdmaRoaming);
                mNewSS.setState (regCodeToServiceState(registrationState));

                mNewSS.setRilVoiceRadioTechnology(radioTechnology);

                mNewSS.setCssIndicator(cssIndicator);
                mNewSS.setSystemAndNetworkId(systemId, networkId);
                mRoamingIndicator = roamingIndicator;
                mIsInPrl = (systemIsInPrl == 0) ? false : true;
                mDefaultRoamingIndicator = defaultRoamingIndicator;


                // Values are -1 if not available.
                mNewCellLoc.setCellLocationData(baseStationId, baseStationLatitude,
                        baseStationLongitude, systemId, networkId);

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
                break;

            case EVENT_POLL_STATE_OPERATOR_CDMA: // Handle RIL_REQUEST_OPERATOR
                String opNames[] = (String[])ar.result;

                if (opNames != null && opNames.length >= 3) {
                    // TODO: Do we care about overriding in this case.
                    // If the NUMERIC field isn't valid use PROPERTY_CDMA_HOME_OPERATOR_NUMERIC
                    if ((opNames[2] == null) || (opNames[2].length() < 5)
                            || ("00000".equals(opNames[2]))) {
                        opNames[2] = SystemProperties.get(
                                CDMAPhone.PROPERTY_CDMA_HOME_OPERATOR_NUMERIC, "00000");
                        if (DBG) {
                            log("RIL_REQUEST_OPERATOR.response[2], the numeric, " +
                                    " is bad. Using SystemProperties '" +
                                            CDMAPhone.PROPERTY_CDMA_HOME_OPERATOR_NUMERIC +
                                    "'= " + opNames[2]);
                        }
                    }

                    if (!mIsSubscriptionFromRuim) {
                        // NV device (as opposed to CSIM)
                        mNewSS.setOperatorName(opNames[0], opNames[1], opNames[2]);
                    } else {
                        String brandOverride = mUiccController.getUiccCard(getPhoneId()) != null ?
                            mUiccController.getUiccCard(getPhoneId()).getOperatorBrandOverride() : null;
                        if (brandOverride != null) {
                            mNewSS.setOperatorName(brandOverride, brandOverride, opNames[2]);
                        } else {
                            mNewSS.setOperatorName(opNames[0], opNames[1], opNames[2]);
                        }
                    }
                } else {
                    if (DBG) log("EVENT_POLL_STATE_OPERATOR_CDMA: error parsing opNames");
                }
                break;

            default:
                loge("handlePollStateResultMessage: RIL response handle in wrong phone!"
                        + " Expected CDMA RIL request and get GSM RIL request.");
                break;
        }
    }

    /**
     * Handle the result of one of the pollState() - related requests
     */
    @Override
    protected void handlePollStateResult(int what, AsyncResult ar) {
        // Ignore stale requests from last poll.
        if (ar.userObj != mPollingContext) return;

        if (ar.exception != null) {
            CommandException.Error err=null;

            if (ar.exception instanceof CommandException) {
                err = ((CommandException)(ar.exception)).getCommandError();
            }

            if (err == CommandException.Error.RADIO_NOT_AVAILABLE) {
                // Radio has crashed or turned off.
                cancelPollState();
                return;
            }

            if (!mCi.getRadioState().isOn()) {
                // Radio has crashed or turned off.
                cancelPollState();
                return;
            }

            if (err != CommandException.Error.OP_NOT_ALLOWED_BEFORE_REG_NW) {
                loge("handlePollStateResult: RIL returned an error where it must succeed"
                        + ar.exception);
            }
        } else try {
            handlePollStateResultMessage(what, ar);
        } catch (RuntimeException ex) {
            loge("handlePollStateResult: Exception while polling service state. "
                    + "Probably malformed RIL response." + ex);
        }

        mPollingContext[0]--;

        if (mPollingContext[0] == 0) {
            boolean namMatch = false;
            if (!isSidsAllZeros() && isHomeSid(mNewSS.getSystemId())) {
                namMatch = true;
            }

            // Setting SS Roaming (general)
            if (mIsSubscriptionFromRuim) {
                mNewSS.setVoiceRoaming(isRoamingBetweenOperators(mNewSS.getVoiceRoaming(), mNewSS));
            }
            // For CDMA, voice and data should have the same roaming status
            final boolean isVoiceInService =
                    (mNewSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE);
            final int dataRegType = mNewSS.getRilDataRadioTechnology();
            if (isVoiceInService && ServiceState.isCdma(dataRegType)) {
                mNewSS.setDataRoaming(mNewSS.getVoiceRoaming());
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
                    if (mNewSS.getRilVoiceRadioTechnology()
                            == ServiceState.RIL_RADIO_TECHNOLOGY_LTE) {
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
            mNewSS.setCdmaEriIconIndex(mPhone.mEriManager.getCdmaEriIconIndex(roamingIndicator,
                    mDefaultRoamingIndicator));
            mNewSS.setCdmaEriIconMode(mPhone.mEriManager.getCdmaEriIconMode(roamingIndicator,
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
            pollStateDone();
        }

    }

    /**
     * Set both voice and data roaming type,
     * judging from the roaming indicator
     * or ISO country of SIM VS network.
     */
    protected void setRoamingType(ServiceState currentServiceState) {
        final boolean isVoiceInService =
                (currentServiceState.getVoiceRegState() == ServiceState.STATE_IN_SERVICE);
        if (isVoiceInService) {
            if (currentServiceState.getVoiceRoaming()) {
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
            } else if (ServiceState.isCdma(dataRegType)) {
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

    protected String getHomeOperatorNumeric() {
        String numeric = ((TelephonyManager) mPhone.getContext().
                getSystemService(Context.TELEPHONY_SERVICE)).
                getSimOperatorNumericForPhone(mPhoneBase.getPhoneId());
        if (TextUtils.isEmpty(numeric)) {
            numeric = SystemProperties.get(CDMAPhone.PROPERTY_CDMA_HOME_OPERATOR_NUMERIC, "");
        }
        return numeric;
    }

    protected void setSignalStrengthDefaultValues() {
        mSignalStrength = new SignalStrength( false);
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

            pollStateDone();
            break;

        case RADIO_OFF:
            mNewSS.setStateOff();
            mNewCellLoc.setStateInvalid();
            setSignalStrengthDefaultValues();
            mGotCountryCode = false;

            pollStateDone();
            break;

        default:
            // Issue all poll-related commands at once, then count
            // down the responses which are allowed to arrive
            // out-of-order.

            mPollingContext[0]++;
            // RIL_REQUEST_OPERATOR is necessary for CDMA
            mCi.getOperator(
                    obtainMessage(EVENT_POLL_STATE_OPERATOR_CDMA, mPollingContext));

            mPollingContext[0]++;
            // RIL_REQUEST_VOICE_REGISTRATION_STATE is necessary for CDMA
            mCi.getVoiceRegistrationState(
                    obtainMessage(EVENT_POLL_STATE_REGISTRATION_CDMA, mPollingContext));

            mPollingContext[0]++;
            // RIL_REQUEST_DATA_REGISTRATION_STATE
            mCi.getDataRegistrationState(obtainMessage(EVENT_POLL_STATE_GPRS,
                                        mPollingContext));
            break;
        }
    }

    protected void fixTimeZone(String isoCountryCode) {
        TimeZone zone = null;
        // If the offset is (0, false) and the time zone property
        // is set, use the time zone property rather than GMT.
        String zoneName = SystemProperties.get(TIMEZONE_PROPERTY);
        if (DBG) {
            log("fixTimeZone zoneName='" + zoneName +
                "' mZoneOffset=" + mZoneOffset + " mZoneDst=" + mZoneDst +
                " iso-cc='" + isoCountryCode +
                "' iso-cc-idx=" + Arrays.binarySearch(GMT_COUNTRY_CODES, isoCountryCode));
        }
        if ((mZoneOffset == 0) && (mZoneDst == false) && (zoneName != null)
                && (zoneName.length() > 0)
                && (Arrays.binarySearch(GMT_COUNTRY_CODES, isoCountryCode) < 0)) {
            // For NITZ string without time zone,
            // need adjust time to reflect default time zone setting
            zone = TimeZone.getDefault();
            if (mNeedFixZone) {
                long ctm = System.currentTimeMillis();
                long tzOffset = zone.getOffset(ctm);
                if (DBG) {
                    log("fixTimeZone: tzOffset=" + tzOffset +
                            " ltod=" + TimeUtils.logTimeOfDay(ctm));
                }
                if (getAutoTime()) {
                    long adj = ctm - tzOffset;
                    if (DBG) log("fixTimeZone: adj ltod=" + TimeUtils.logTimeOfDay(adj));
                    setAndBroadcastNetworkSetTime(adj);
                } else {
                    // Adjust the saved NITZ time to account for tzOffset.
                    mSavedTime = mSavedTime - tzOffset;
                    if (DBG) log("fixTimeZone: adj mSavedTime=" + mSavedTime);
                }
            }
            if (DBG) log("fixTimeZone: using default TimeZone");
        } else if (isoCountryCode.equals("")) {
            // Country code not found. This is likely a test network.
            // Get a TimeZone based only on the NITZ parameters (best guess).
            zone = getNitzTimeZone(mZoneOffset, mZoneDst, mZoneTime);
            if (DBG) log("fixTimeZone: using NITZ TimeZone");
        } else {
            zone = TimeUtils.getTimeZone(mZoneOffset, mZoneDst, mZoneTime, isoCountryCode);
            if (DBG) log("fixTimeZone: using getTimeZone(off, dst, time, iso)");
        }

        mNeedFixZone = false;

        if (zone != null) {
            log("fixTimeZone: zone != null zone.getID=" + zone.getID());
            if (getAutoTimeZone()) {
                setAndBroadcastNetworkSetTimeZone(zone.getID());
            } else {
                log("fixTimeZone: skip changing zone as getAutoTimeZone was false");
            }
            saveNitzTimeZone(zone.getID());
        } else {
            log("fixTimeZone: zone == null, do nothing for zone");
        }
    }

    protected void pollStateDone() {
        if (DBG) log("pollStateDone: cdma oldSS=[" + mSS + "] newSS=[" + mNewSS + "]");

        if (mPhone.isMccMncMarkedAsNonRoaming(mNewSS.getOperatorNumeric()) ||
                mPhone.isSidMarkedAsNonRoaming(mNewSS.getSystemId())) {
            log("pollStateDone: override - marked as non-roaming.");
            mNewSS.setVoiceRoaming(false);
            mNewSS.setDataRoaming(false);
            mNewSS.setCdmaEriIconIndex(EriInfo.ROAMING_INDICATOR_OFF);
        } else if (mPhone.isMccMncMarkedAsRoaming(mNewSS.getOperatorNumeric()) ||
                mPhone.isSidMarkedAsRoaming(mNewSS.getSystemId())) {
            log("pollStateDone: override - marked as roaming.");
            mNewSS.setVoiceRoaming(true);
            mNewSS.setDataRoaming(true);
            mNewSS.setCdmaEriIconIndex(EriInfo.ROAMING_INDICATOR_ON);
            mNewSS.setCdmaEriIconMode(EriInfo.ROAMING_ICON_MODE_NORMAL);
        }

        if (Build.IS_DEBUGGABLE && SystemProperties.getBoolean(PROP_FORCE_ROAMING, false)) {
            mNewSS.setVoiceRoaming(true);
            mNewSS.setDataRoaming(true);
        }

        useDataRegStateForDataOnlyDevices();

        boolean hasRegistered =
            mSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE
            && mNewSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE;

        boolean hasDeregistered =
            mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE
            && mNewSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionAttached =
            mSS.getDataRegState() != ServiceState.STATE_IN_SERVICE
            && mNewSS.getDataRegState() == ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionDetached =
            mSS.getDataRegState() == ServiceState.STATE_IN_SERVICE
            && mNewSS.getDataRegState() != ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionChanged =
                       mSS.getDataRegState() != mNewSS.getDataRegState();

        boolean hasRilVoiceRadioTechnologyChanged =
                mSS.getRilVoiceRadioTechnology() != mNewSS.getRilVoiceRadioTechnology();

        boolean hasRilDataRadioTechnologyChanged =
                mSS.getRilDataRadioTechnology() != mNewSS.getRilDataRadioTechnology();

        boolean hasChanged = !mNewSS.equals(mSS);

        boolean hasVoiceRoamingOn = !mSS.getVoiceRoaming() && mNewSS.getVoiceRoaming();

        boolean hasVoiceRoamingOff = mSS.getVoiceRoaming() && !mNewSS.getVoiceRoaming();

        boolean hasDataRoamingOn = !mSS.getDataRoaming() && mNewSS.getDataRoaming();

        boolean hasDataRoamingOff = mSS.getDataRoaming() && !mNewSS.getDataRoaming();

        boolean hasLocationChanged = !mNewCellLoc.equals(mCellLoc);

        TelephonyManager tm =
                (TelephonyManager) mPhone.getContext().getSystemService(Context.TELEPHONY_SERVICE);

        // Add an event log when connection state changes
        if (mSS.getVoiceRegState() != mNewSS.getVoiceRegState() ||
                mSS.getDataRegState() != mNewSS.getDataRegState()) {
            EventLog.writeEvent(EventLogTags.CDMA_SERVICE_STATE_CHANGE,
                    mSS.getVoiceRegState(), mSS.getDataRegState(),
                    mNewSS.getVoiceRegState(), mNewSS.getDataRegState());
        }

        ServiceState tss;
        tss = mSS;
        mSS = mNewSS;
        mNewSS = tss;
        // clean slate for next time
        mNewSS.setStateOutOfService();

        CdmaCellLocation tcl = mCellLoc;
        mCellLoc = mNewCellLoc;
        mNewCellLoc = tcl;

        if (hasRilVoiceRadioTechnologyChanged) {
            updatePhoneObject();
        }

        if (hasRilDataRadioTechnologyChanged) {
            tm.setDataNetworkTypeForPhone(mPhone.getPhoneId(), mSS.getRilDataRadioTechnology());
        }

        if (hasRegistered) {
            mNetworkAttachedRegistrants.notifyRegistrants();
        }

        if (hasChanged) {
            if ((mCi.getRadioState().isOn()) && (!mIsSubscriptionFromRuim)) {
                String eriText;
                // Now the CDMAPhone sees the new ServiceState so it can get the new ERI text
                if (mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE) {
                    eriText = mPhone.getCdmaEriText();
                } else {
                    // Note that ServiceState.STATE_OUT_OF_SERVICE is valid used for
                    // mRegistrationState 0,2,3 and 4
                    eriText = mPhone.getContext().getText(
                            com.android.internal.R.string.roamingTextSearching).toString();
                }
                mSS.setOperatorAlphaLong(eriText);
            }

            String operatorNumeric;

            tm.setNetworkOperatorNameForPhone(mPhone.getPhoneId(), mSS.getOperatorAlphaLong());

            String prevOperatorNumeric = tm.getNetworkOperatorForPhone(mPhone.getPhoneId());
            operatorNumeric = mSS.getOperatorNumeric();

            // try to fix the invalid Operator Numeric
            if (isInvalidOperatorNumeric(operatorNumeric)) {
                int sid = mSS.getSystemId();
                operatorNumeric = fixUnknownMcc(operatorNumeric, sid);
            }

            tm.setNetworkOperatorNumericForPhone(mPhone.getPhoneId(), operatorNumeric);
            updateCarrierMccMncConfiguration(operatorNumeric,
                    prevOperatorNumeric, mPhone.getContext());

            if (isInvalidOperatorNumeric(operatorNumeric)) {
                if (DBG) log("operatorNumeric "+ operatorNumeric +"is invalid");
                tm.setNetworkCountryIsoForPhone(mPhone.getPhoneId(), "");
                mGotCountryCode = false;
            } else {
                String isoCountryCode = "";
                String mcc = operatorNumeric.substring(0, 3);
                try{
                    isoCountryCode = MccTable.countryCodeForMcc(Integer.parseInt(
                            operatorNumeric.substring(0,3)));
                } catch ( NumberFormatException ex){
                    loge("pollStateDone: countryCodeForMcc error" + ex);
                } catch ( StringIndexOutOfBoundsException ex) {
                    loge("pollStateDone: countryCodeForMcc error" + ex);
                }

                tm.setNetworkCountryIsoForPhone(mPhone.getPhoneId(), isoCountryCode);
                mGotCountryCode = true;

                setOperatorIdd(operatorNumeric);

                if (shouldFixTimeZoneNow(mPhone, operatorNumeric, prevOperatorNumeric,
                        mNeedFixZone)) {
                    fixTimeZone(isoCountryCode);
                }
            }

            tm.setNetworkRoamingForPhone(mPhone.getPhoneId(),
                    (mSS.getVoiceRoaming() || mSS.getDataRoaming()));

            updateSpnDisplay();
            // set roaming type
            setRoamingType(mSS);
            log("Broadcasting ServiceState : " + mSS);
            mPhone.notifyServiceStateChanged(mSS);
        }

        if (hasCdmaDataConnectionAttached) {
            mAttachedRegistrants.notifyRegistrants();
        }

        if (hasCdmaDataConnectionDetached) {
            mDetachedRegistrants.notifyRegistrants();
        }

        if (hasCdmaDataConnectionChanged || hasRilDataRadioTechnologyChanged) {
            notifyDataRegStateRilRadioTechnologyChanged();
            mPhone.notifyDataConnection(null);
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
            mPhone.notifyLocationChanged();
        }
        // TODO: Add CdmaCellIdenity updating, see CdmaLteServiceStateTracker.
    }

    protected boolean isInvalidOperatorNumeric(String operatorNumeric) {
        return operatorNumeric == null || operatorNumeric.length() < 5 ||
                    operatorNumeric.startsWith(INVALID_MCC);
    }

    protected String fixUnknownMcc(String operatorNumeric, int sid) {
        if (sid <= 0) {
            // no cdma information is available, do nothing
            return operatorNumeric;
        }

        // resolve the mcc from sid;
        // if mSavedTimeZone is null, TimeZone would get the default timeZone,
        // and the fixTimeZone couldn't help, because it depends on operator Numeric;
        // if the sid is conflict and timezone is unavailable, the mcc may be not right.
        boolean isNitzTimeZone = false;
        int timeZone = 0;
        TimeZone tzone = null;
        if (mSavedTimeZone != null) {
             timeZone =
                     TimeZone.getTimeZone(mSavedTimeZone).getRawOffset()/MS_PER_HOUR;
             isNitzTimeZone = true;
        } else {
             tzone = getNitzTimeZone(mZoneOffset, mZoneDst, mZoneTime);
             if (tzone != null)
                     timeZone = tzone.getRawOffset()/MS_PER_HOUR;
        }

        int mcc = mHbpcdUtils.getMcc(sid,
                timeZone, (mZoneDst ? 1 : 0), isNitzTimeZone);
        if (mcc > 0) {
            operatorNumeric = Integer.toString(mcc) + DEFAULT_MNC;
        }
        return operatorNumeric;
    }

    protected void setOperatorIdd(String operatorNumeric) {
        // Retrieve the current country information
        // with the MCC got from opeatorNumeric.
        String idd = mHbpcdUtils.getIddByMcc(
                Integer.parseInt(operatorNumeric.substring(0,3)));
        if (idd != null && !idd.isEmpty()) {
            mPhone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_IDP_STRING,
                     idd);
        } else {
            // use default "+", since we don't know the current IDP
            mPhone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_IDP_STRING, "+");
        }
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
            rawOffset -= MS_PER_HOUR;
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

    /**
     * TODO: This code is exactly the same as in GsmServiceStateTracker
     * and has a TODO to not poll signal strength if screen is off.
     * This code should probably be hoisted to the base class so
     * the fix, when added, works for both.
     */
    private void
    queueNextSignalStrengthPoll() {
        if (mDontPollSignalStrength) {
            // The radio is telling us about signal strength changes
            // we don't have to ask it
            return;
        }

        Message msg;

        msg = obtainMessage();
        msg.what = EVENT_POLL_SIGNAL_STRENGTH;

        // TODO Don't poll signal strength if screen is off
        sendMessageDelayed(msg, POLL_PERIOD_MILLIS);
    }

    protected int radioTechnologyToDataServiceState(int code) {
        int retVal = ServiceState.STATE_OUT_OF_SERVICE;
        switch(code) {
        case 0:
        case 1:
        case 2:
        case 3:
        case 4:
        case 5:
            break;
        case 6: // RADIO_TECHNOLOGY_1xRTT
        case 7: // RADIO_TECHNOLOGY_EVDO_0
        case 8: // RADIO_TECHNOLOGY_EVDO_A
        case 12: // RADIO_TECHNOLOGY_EVDO_B
        case 13: // RADIO_TECHNOLOGY_EHRPD
            retVal = ServiceState.STATE_IN_SERVICE;
            break;
        default:
            loge("radioTechnologyToDataServiceState: Wrong radioTechnology code.");
        break;
        }
        return(retVal);
    }

    /** code is registration state 0-5 from TS 27.007 7.2 */
    protected int
    regCodeToServiceState(int code) {
        switch (code) {
        case 0: // Not searching and not registered
            return ServiceState.STATE_OUT_OF_SERVICE;
        case 1:
            return ServiceState.STATE_IN_SERVICE;
        case 2: // 2 is "searching", fall through
        case 3: // 3 is "registration denied", fall through
        case 4: // 4 is "unknown", not valid in current baseband
            return ServiceState.STATE_OUT_OF_SERVICE;
        case 5:// 5 is "Registered, roaming"
            return ServiceState.STATE_IN_SERVICE;

        default:
            loge("regCodeToServiceState: unexpected service state " + code);
        return ServiceState.STATE_OUT_OF_SERVICE;
        }
    }

    @Override
    public int getCurrentDataConnectionState() {
        return mSS.getDataRegState();
    }

    /**
     * code is registration state 0-5 from TS 27.007 7.2
     * returns true if registered roam, false otherwise
     */
    protected boolean
    regCodeIsRoaming (int code) {
        // 5 is  "in service -- roam"
        return 5 == code;
    }

    /**
     * Determine whether a roaming indicator is in the carrier-specified list of ERIs for
     * home system
     *
     * @param roamInd roaming indicator in String
     * @return true if the roamInd is in the carrier-specified list of ERIs for home network
     */
    private boolean isRoamIndForHomeSystem(String roamInd) {
        // retrieve the carrier-specified list of ERIs for home system
        String[] homeRoamIndicators = mPhone.getContext().getResources()
                .getStringArray(com.android.internal.R.array.config_cdma_home_system);

        if (homeRoamIndicators != null) {
            // searches through the comma-separated list for a match,
            // return true if one is found.
            for (String homeRoamInd : homeRoamIndicators) {
                if (homeRoamInd.equals(roamInd)) {
                    return true;
                }
            }
            // no matches found against the list!
            return false;
        }

        // no system property found for the roaming indicators for home system
        return false;
    }

    /**
     * Set roaming state when cdmaRoaming is true and ons is different from spn
     * @param cdmaRoaming TS 27.007 7.2 CREG registered roaming
     * @param s ServiceState hold current ons
     * @return true for roaming state set
     */
    private
    boolean isRoamingBetweenOperators(boolean cdmaRoaming, ServiceState s) {
        String spn = ((TelephonyManager) mPhone.getContext().
                getSystemService(Context.TELEPHONY_SERVICE)).
                getSimOperatorNameForPhone(mPhoneBase.getPhoneId());

        // NOTE: in case of RUIM we should completely ignore the ERI data file and
        // mOperatorAlphaLong is set from RIL_REQUEST_OPERATOR response 0 (alpha ONS)
        String onsl = s.getVoiceOperatorAlphaLong();
        String onss = s.getVoiceOperatorAlphaShort();

        boolean equalsOnsl = onsl != null && spn.equals(onsl);
        boolean equalsOnss = onss != null && spn.equals(onss);

        return cdmaRoaming && !(equalsOnsl || equalsOnss);
    }


    /**
     * nitzReceiveTime is time_t that the NITZ time was posted
     */

    private
    void setTimeFromNITZString (String nitz, long nitzReceiveTime)
    {
        // "yy/mm/dd,hh:mm:ss(+/-)tz"
        // tz is in number of quarter-hours

        long start = SystemClock.elapsedRealtime();
        if (DBG) {
            log("NITZ: " + nitz + "," + nitzReceiveTime +
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

            String iso = ((TelephonyManager) mPhone.getContext().
                    getSystemService(Context.TELEPHONY_SERVICE)).
                    getNetworkCountryIsoForPhone(mPhone.getPhoneId());

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

                mNeedFixZone = true;
                mZoneOffset  = tzOffset;
                mZoneDst     = dst != 0;
                mZoneTime    = c.getTimeInMillis();
            }
            if (DBG) {
                log("NITZ: tzOffset=" + tzOffset + " dst=" + dst + " zone=" +
                        (zone!=null ? zone.getID() : "NULL") +
                        " iso=" + iso + " mGotCountryCode=" + mGotCountryCode +
                        " mNeedFixZone=" + mNeedFixZone);
            }

            if (zone != null) {
                if (getAutoTimeZone()) {
                    setAndBroadcastNetworkSetTimeZone(zone.getID());
                }
                saveNitzTimeZone(zone.getID());
            }

            String ignore = SystemProperties.get("gsm.ignore-nitz");
            if (ignore != null && ignore.equals("yes")) {
                if (DBG) log("NITZ: Not setting clock because gsm.ignore-nitz is set");
                return;
            }

            try {
                mWakeLock.acquire();

                /**
                 * Correct the NITZ time by how long its taken to get here.
                 */
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

                if (getAutoTime()) {
                    /**
                     * Update system time automatically
                     */
                    long gained = c.getTimeInMillis() - System.currentTimeMillis();
                    long timeSinceLastUpdate = SystemClock.elapsedRealtime() - mSavedAtTime;
                    int nitzUpdateSpacing = Settings.Global.getInt(mCr,
                            Settings.Global.NITZ_UPDATE_SPACING, mNitzUpdateSpacing);
                    int nitzUpdateDiff = Settings.Global.getInt(mCr,
                            Settings.Global.NITZ_UPDATE_DIFF, mNitzUpdateDiff);

                    if ((mSavedAtTime == 0) || (timeSinceLastUpdate > nitzUpdateSpacing)
                            || (Math.abs(gained) > nitzUpdateDiff)) {
                        if (DBG) {
                            log("NITZ: Auto updating time of day to " + c.getTime()
                                + " NITZ receive delay=" + millisSinceNitzReceived
                                + "ms gained=" + gained + "ms from " + nitz);
                        }

                        setAndBroadcastNetworkSetTime(c.getTimeInMillis());
                    } else {
                        if (DBG) {
                            log("NITZ: ignore, a previous update was "
                                + timeSinceLastUpdate + "ms ago and gained=" + gained + "ms");
                        }
                        return;
                    }
                }

                /**
                 * Update properties and save the time we did the update
                 */
                if (DBG) log("NITZ: update nitz time property");
                SystemProperties.set("gsm.nitz.time", String.valueOf(c.getTimeInMillis()));
                mSavedTime = c.getTimeInMillis();
                mSavedAtTime = SystemClock.elapsedRealtime();
            } finally {
                long end = SystemClock.elapsedRealtime();
                if (DBG) log("NITZ: end=" + end + " dur=" + (end - start));
                mWakeLock.release();
            }
        } catch (RuntimeException ex) {
            loge("NITZ: Parsing NITZ time " + nitz + " ex=" + ex);
        }
    }

    private boolean getAutoTime() {
        try {
            return Settings.Global.getInt(mCr, Settings.Global.AUTO_TIME) > 0;
        } catch (SettingNotFoundException snfe) {
            return true;
        }
    }

    private boolean getAutoTimeZone() {
        try {
            return Settings.Global.getInt(mCr, Settings.Global.AUTO_TIME_ZONE) > 0;
        } catch (SettingNotFoundException snfe) {
            return true;
        }
    }

    private void saveNitzTimeZone(String zoneId) {
        mSavedTimeZone = zoneId;
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
        if (Settings.Global.getInt(mCr, Settings.Global.AUTO_TIME, 0) == 0) {
            return;
        }
        if (DBG) {
            log("revertToNitzTime: mSavedTime=" + mSavedTime + " mSavedAtTime=" + mSavedAtTime);
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
        if (DBG) log("revertToNitzTimeZone: tz='" + mSavedTimeZone);
        if (mSavedTimeZone != null) {
            setAndBroadcastNetworkSetTimeZone(mSavedTimeZone);
        }
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

    /**
     * @return true if phone is camping on a technology
     * that could support voice and data simultaneously.
     */
    @Override
    public boolean isConcurrentVoiceAndDataAllowed() {
        // Note: it needs to be confirmed which CDMA network types
        // can support voice and data calls concurrently.
        // For the time-being, the return value will be false.
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
    String getImsi() {
        // TODO: When RUIM is enabled, IMSI will come from RUIM not build-time props.
        String operatorNumeric = ((TelephonyManager) mPhone.getContext().
                getSystemService(Context.TELEPHONY_SERVICE)).
                getSimOperatorNumericForPhone(mPhoneBase.getPhoneId());

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
     * Returns OTASP_UNKNOWN, OTASP_NEEDED or OTASP_NOT_NEEDED
     */
    int getOtasp() {
        int provisioningState;
        // for ruim, min is null means require otasp.
        if (mIsSubscriptionFromRuim && mMin == null) {
            return OTASP_NEEDED;
        }
        if (mMin == null || (mMin.length() < 6)) {
            if (DBG) log("getOtasp: bad mMin='" + mMin + "'");
            provisioningState = OTASP_UNKNOWN;
        } else {
            if ((mMin.equals(UNACTIVATED_MIN_VALUE)
                    || mMin.substring(0,6).equals(UNACTIVATED_MIN2_VALUE))
                    || SystemProperties.getBoolean("test_cdma_setup", false)) {
                provisioningState = OTASP_NEEDED;
            } else {
                provisioningState = OTASP_NOT_NEEDED;
            }
        }
        if (DBG) log("getOtasp: state=" + provisioningState);
        return provisioningState;
    }

    @Override
    protected void hangupAndPowerOff() {
        // hang up all active voice calls
        mPhone.mCT.mRingingCall.hangupIfAlive();
        mPhone.mCT.mBackgroundCall.hangupIfAlive();
        mPhone.mCT.mForegroundCall.hangupIfAlive();
        mCi.setRadioPower(false, null);
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

    protected void updateOtaspState() {
        int otaspMode = getOtasp();
        int oldOtaspMode = mCurrentOtaspMode;
        mCurrentOtaspMode = otaspMode;

        // Notify apps subscription info is ready
        if (mCdmaForSubscriptionInfoReadyRegistrants != null) {
            if (DBG) log("CDMA_SUBSCRIPTION: call notifyRegistrants()");
            mCdmaForSubscriptionInfoReadyRegistrants.notifyRegistrants();
        }
        if (oldOtaspMode != mCurrentOtaspMode) {
            if (DBG) {
                log("CDMA_SUBSCRIPTION: call notifyOtaspChanged old otaspMode=" +
                    oldOtaspMode + " new otaspMode=" + mCurrentOtaspMode);
            }
            mPhone.notifyOtaspChanged(mCurrentOtaspMode);
        }
    }

    protected UiccCardApplication getUiccCardApplication() {
            return  mUiccController.getUiccCardApplication(mPhone.getPhoneId(),
                    UiccController.APP_FAM_3GPP2);
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
                if (mIsSubscriptionFromRuim) {
                    mUiccApplcation.registerForReady(this, EVENT_RUIM_READY, null);
                    if (mIccRecords != null) {
                        mIccRecords.registerForRecordsLoaded(this, EVENT_RUIM_RECORDS_LOADED, null);
                    }
                }
            }
        }
    }

    @Override
    protected void log(String s) {
        Rlog.d(LOG_TAG, "[CdmaSST] " + s);
    }

    @Override
    protected void loge(String s) {
        Rlog.e(LOG_TAG, "[CdmaSST] " + s);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("CdmaServiceStateTracker extends:");
        super.dump(fd, pw, args);
        pw.flush();
        pw.println(" mPhone=" + mPhone);
        pw.println(" mSS=" + mSS);
        pw.println(" mNewSS=" + mNewSS);
        pw.println(" mCellLoc=" + mCellLoc);
        pw.println(" mNewCellLoc=" + mNewCellLoc);
        pw.println(" mCurrentOtaspMode=" + mCurrentOtaspMode);
        pw.println(" mRoamingIndicator=" + mRoamingIndicator);
        pw.println(" mIsInPrl=" + mIsInPrl);
        pw.println(" mDefaultRoamingIndicator=" + mDefaultRoamingIndicator);
        pw.println(" mRegistrationState=" + mRegistrationState);
        pw.println(" mNeedFixZone=" + mNeedFixZone);
        pw.flush();
        pw.println(" mZoneOffset=" + mZoneOffset);
        pw.println(" mZoneDst=" + mZoneDst);
        pw.println(" mZoneTime=" + mZoneTime);
        pw.println(" mGotCountryCode=" + mGotCountryCode);
        pw.println(" mSavedTimeZone=" + mSavedTimeZone);
        pw.println(" mSavedTime=" + mSavedTime);
        pw.println(" mSavedAtTime=" + mSavedAtTime);
        pw.println(" mWakeLock=" + mWakeLock);
        pw.println(" mCurPlmn=" + mCurPlmn);
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
    }

    @Override
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
}
