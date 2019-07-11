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

import android.content.Context;
import android.content.Intent;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.uicc.RuimRecords;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppState;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellIdentityLte;
import android.telephony.SignalStrength;
import android.telephony.ServiceState;
import android.telephony.cdma.CdmaCellLocation;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Message;
import android.os.UserHandle;
import android.os.SystemClock;
import android.os.SystemProperties;

import android.telephony.Rlog;
import android.util.EventLog;

import com.android.internal.telephony.dataconnection.DcTrackerBase;
import com.android.internal.telephony.ProxyController;
import android.telephony.SubscriptionManager;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class CdmaLteServiceStateTracker extends CdmaServiceStateTracker {
    private CDMALTEPhone mCdmaLtePhone;
    private final CellInfoLte mCellInfoLte;
    private static final int EVENT_ALL_DATA_DISCONNECTED = 1001;

    private CellIdentityLte mNewCellIdentityLte = new CellIdentityLte();
    private CellIdentityLte mLasteCellIdentityLte = new CellIdentityLte();

    public CdmaLteServiceStateTracker(CDMALTEPhone phone) {
        super(phone, new CellInfoLte());
        mCdmaLtePhone = phone;
        mCdmaLtePhone.registerForSimRecordsLoaded(this, EVENT_SIM_RECORDS_LOADED, null);
        mCellInfoLte = (CellInfoLte) mCellInfo;

        ((CellInfoLte)mCellInfo).setCellSignalStrength(new CellSignalStrengthLte());
        ((CellInfoLte)mCellInfo).setCellIdentity(new CellIdentityLte());

        if (DBG) log("CdmaLteServiceStateTracker Constructors");
    }

    @Override
    public void dispose() {
        mPhone.unregisterForSimRecordsLoaded(this);
        super.dispose();
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        int[] ints;
        String[] strings;

        if (!mPhone.mIsTheCurrentActivePhone) {
            loge("Received message " + msg + "[" + msg.what + "]" +
                    " while being destroyed. Ignoring.");
            return;
        }

        if (DBG) log("handleMessage: " + msg.what);
        switch (msg.what) {
        case EVENT_POLL_STATE_GPRS:
            if (DBG) log("handleMessage EVENT_POLL_STATE_GPRS");
            ar = (AsyncResult)msg.obj;
            handlePollStateResult(msg.what, ar);
            break;
        case EVENT_RUIM_RECORDS_LOADED:
            updatePhoneObject();
            RuimRecords ruim = (RuimRecords)mIccRecords;
            if (ruim != null) {
                if (ruim.isProvisioned()) {
                    mMdn = ruim.getMdn();
                    mMin = ruim.getMin();
                    parseSidNid(ruim.getSid(), ruim.getNid());
                    mPrlVersion = ruim.getPrlVersion();
                    mIsMinInfoReady = true;
                }
                updateOtaspState();
            }
            // reload eri in case of IMSI changed
            // eri.xml can be defined by mcc mnc
            mPhone.prepareEri();
            // SID/NID/PRL is loaded. Poll service state
            // again to update to the roaming state with
            // the latest variables.
            pollState();
            break;
        case EVENT_SIM_RECORDS_LOADED:
            updatePhoneObject();
            break;
        case EVENT_ALL_DATA_DISCONNECTED:
            int dds = SubscriptionManager.getDefaultDataSubId();
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
        default:
            super.handleMessage(msg);
        }
    }

    /**
     * Handle the result of one of the pollState()-related requests
     */
    @Override
    protected void handlePollStateResultMessage(int what, AsyncResult ar) {
        if (what == EVENT_POLL_STATE_GPRS) {
            String states[] = (String[])ar.result;
            if (DBG) {
                log("handlePollStateResultMessage: EVENT_POLL_STATE_GPRS states.length=" +
                        states.length + " states=" + states);
            }

            int type = 0;
            int regState = -1;
            if (states.length > 0) {
                try {
                    regState = Integer.parseInt(states[0]);

                    // states[3] (if present) is the current radio technology
                    if (states.length >= 4 && states[3] != null) {
                        type = Integer.parseInt(states[3]);
                    }
                } catch (NumberFormatException ex) {
                    loge("handlePollStateResultMessage: error parsing GprsRegistrationState: "
                                    + ex);
                }
                if (states.length >= 10) {
                    int mcc;
                    int mnc;
                    int tac;
                    int pci;
                    int eci;
                    int csgid;
                    String operatorNumeric = null;

                    try {
                        operatorNumeric = mNewSS.getOperatorNumeric();
                        mcc = Integer.parseInt(operatorNumeric.substring(0,3));
                    } catch (Exception e) {
                        try {
                            operatorNumeric = mSS.getOperatorNumeric();
                            mcc = Integer.parseInt(operatorNumeric.substring(0,3));
                        } catch (Exception ex) {
                            loge("handlePollStateResultMessage: bad mcc operatorNumeric=" +
                                    operatorNumeric + " ex=" + ex);
                            operatorNumeric = "";
                            mcc = Integer.MAX_VALUE;
                        }
                    }
                    try {
                        mnc = Integer.parseInt(operatorNumeric.substring(3));
                    } catch (Exception e) {
                        loge("handlePollStateResultMessage: bad mnc operatorNumeric=" +
                                operatorNumeric + " e=" + e);
                        mnc = Integer.MAX_VALUE;
                    }

                    // Use Integer#decode to be generous in what we receive and allow
                    // decimal, hex or octal values.
                    try {
                        tac = Integer.decode(states[6]);
                    } catch (Exception e) {
                        loge("handlePollStateResultMessage: bad tac states[6]=" +
                                states[6] + " e=" + e);
                        tac = Integer.MAX_VALUE;
                    }
                    try {
                        pci = Integer.decode(states[7]);
                    } catch (Exception e) {
                        loge("handlePollStateResultMessage: bad pci states[7]=" +
                                states[7] + " e=" + e);
                        pci = Integer.MAX_VALUE;
                    }
                    try {
                        eci = Integer.decode(states[8]);
                    } catch (Exception e) {
                        loge("handlePollStateResultMessage: bad eci states[8]=" +
                                states[8] + " e=" + e);
                        eci = Integer.MAX_VALUE;
                    }
                    try {
                        csgid = Integer.decode(states[9]);
                    } catch (Exception e) {
                        // FIX: Always bad so don't pollute the logs
                        // loge("handlePollStateResultMessage: bad csgid states[9]=" +
                        //        states[9] + " e=" + e);
                        csgid = Integer.MAX_VALUE;
                    }
                    mNewCellIdentityLte = new CellIdentityLte(mcc, mnc, eci, pci, tac);
                    if (DBG) {
                        log("handlePollStateResultMessage: mNewLteCellIdentity=" +
                                mNewCellIdentityLte);
                    }
                }
            }

            mNewSS.setRilDataRadioTechnology(type);
            int dataRegState = regCodeToServiceState(regState);
            mNewSS.setDataRegState(dataRegState);
            // voice roaming state in done while handling EVENT_POLL_STATE_REGISTRATION_CDMA
            mNewSS.setDataRoaming(regCodeIsRoaming(regState));
            if (DBG) {
                log("handlPollStateResultMessage: CdmaLteSST setDataRegState=" + dataRegState
                        + " regState=" + regState
                        + " dataRadioTechnology=" + type);
            }
        } else {
            super.handlePollStateResultMessage(what, ar);
        }
    }

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
                mCi.getOperator(obtainMessage(EVENT_POLL_STATE_OPERATOR_CDMA, mPollingContext));

                mPollingContext[0]++;
                // RIL_REQUEST_VOICE_REGISTRATION_STATE is necessary for CDMA
                mCi.getVoiceRegistrationState(obtainMessage(EVENT_POLL_STATE_REGISTRATION_CDMA,
                        mPollingContext));

                mPollingContext[0]++;
                // RIL_REQUEST_DATA_REGISTRATION_STATE
                mCi.getDataRegistrationState(obtainMessage(EVENT_POLL_STATE_GPRS,
                                            mPollingContext));
                break;
        }
    }

    @Override
    protected void pollStateDone() {
        log("pollStateDone: lte 1 ss=[" + mSS + "] newSS=[" + mNewSS + "]");

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

        boolean hasRegistered = mSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE
                && mNewSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE;

        boolean hasDeregistered = mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE
                && mNewSS.getVoiceRegState() != ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionAttached =
            mSS.getDataRegState() != ServiceState.STATE_IN_SERVICE
                && mNewSS.getDataRegState() == ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionDetached =
                mSS.getDataRegState() == ServiceState.STATE_IN_SERVICE
                && mNewSS.getDataRegState() != ServiceState.STATE_IN_SERVICE;

        boolean hasCdmaDataConnectionChanged =
            mSS.getDataRegState() != mNewSS.getDataRegState();

        boolean hasVoiceRadioTechnologyChanged = mSS.getRilVoiceRadioTechnology()
                != mNewSS.getRilVoiceRadioTechnology();

        boolean hasDataRadioTechnologyChanged = mSS.getRilDataRadioTechnology()
                != mNewSS.getRilDataRadioTechnology();

        boolean hasChanged = !mNewSS.equals(mSS);

        boolean hasVoiceRoamingOn = !mSS.getVoiceRoaming() && mNewSS.getVoiceRoaming();

        boolean hasVoiceRoamingOff = mSS.getVoiceRoaming() && !mNewSS.getVoiceRoaming();

        boolean hasDataRoamingOn = !mSS.getDataRoaming() && mNewSS.getDataRoaming();

        boolean hasDataRoamingOff = mSS.getDataRoaming() && !mNewSS.getDataRoaming();

        boolean hasLocationChanged = !mNewCellLoc.equals(mCellLoc);

        boolean has4gHandoff =
                mNewSS.getDataRegState() == ServiceState.STATE_IN_SERVICE &&
                (((mSS.getRilDataRadioTechnology() == ServiceState.RIL_RADIO_TECHNOLOGY_LTE) &&
                  (mNewSS.getRilDataRadioTechnology() == ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD)) ||
                 ((mSS.getRilDataRadioTechnology() == ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD) &&
                  (mNewSS.getRilDataRadioTechnology() == ServiceState.RIL_RADIO_TECHNOLOGY_LTE)));

        boolean hasMultiApnSupport =
                (((mNewSS.getRilDataRadioTechnology() == ServiceState.RIL_RADIO_TECHNOLOGY_LTE) ||
                  (mNewSS.getRilDataRadioTechnology() == ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD)) &&
                 ((mSS.getRilDataRadioTechnology() != ServiceState.RIL_RADIO_TECHNOLOGY_LTE) &&
                  (mSS.getRilDataRadioTechnology() != ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD)));

        boolean hasLostMultiApnSupport =
            ((mNewSS.getRilDataRadioTechnology() >= ServiceState.RIL_RADIO_TECHNOLOGY_IS95A) &&
             (mNewSS.getRilDataRadioTechnology() <= ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A));

        TelephonyManager tm =
                (TelephonyManager) mPhone.getContext().getSystemService(Context.TELEPHONY_SERVICE);

        if (DBG) {
            log("pollStateDone:"
                + " hasRegistered=" + hasRegistered
                + " hasDeegistered=" + hasDeregistered
                + " hasCdmaDataConnectionAttached=" + hasCdmaDataConnectionAttached
                + " hasCdmaDataConnectionDetached=" + hasCdmaDataConnectionDetached
                + " hasCdmaDataConnectionChanged=" + hasCdmaDataConnectionChanged
                + " hasVoiceRadioTechnologyChanged= " + hasVoiceRadioTechnologyChanged
                + " hasDataRadioTechnologyChanged=" + hasDataRadioTechnologyChanged
                + " hasChanged=" + hasChanged
                + " hasVoiceRoamingOn=" + hasVoiceRoamingOn
                + " hasVoiceRoamingOff=" + hasVoiceRoamingOff
                + " hasDataRoamingOn=" + hasDataRoamingOn
                + " hasDataRoamingOff=" + hasDataRoamingOff
                + " hasLocationChanged=" + hasLocationChanged
                + " has4gHandoff = " + has4gHandoff
                + " hasMultiApnSupport=" + hasMultiApnSupport
                + " hasLostMultiApnSupport=" + hasLostMultiApnSupport);
        }
        // Add an event log when connection state changes
        if (mSS.getVoiceRegState() != mNewSS.getVoiceRegState()
                || mSS.getDataRegState() != mNewSS.getDataRegState()) {
            EventLog.writeEvent(EventLogTags.CDMA_SERVICE_STATE_CHANGE, mSS.getVoiceRegState(),
                    mSS.getDataRegState(), mNewSS.getVoiceRegState(), mNewSS.getDataRegState());
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

        mNewSS.setStateOutOfService(); // clean slate for next time

        if (hasVoiceRadioTechnologyChanged) {
            updatePhoneObject();
        }

        if (hasDataRadioTechnologyChanged) {
            tm.setDataNetworkTypeForPhone(mPhone.getPhoneId(), mSS.getRilDataRadioTechnology());
        }

        if (hasRegistered) {
            mNetworkAttachedRegistrants.notifyRegistrants();
        }

        if (hasChanged) {
            boolean hasBrandOverride = mUiccController.getUiccCard(getPhoneId()) == null ? false :
                    (mUiccController.getUiccCard(getPhoneId()).getOperatorBrandOverride() != null);
            if (!hasBrandOverride && (mCi.getRadioState().isOn()) && (mPhone.isEriFileLoaded()) &&
                    (mSS.getRilVoiceRadioTechnology() != ServiceState.RIL_RADIO_TECHNOLOGY_LTE ||
                            mPhone.getContext().getResources().getBoolean(com.android.internal.R.
                                    bool.config_LTE_eri_for_network_name))) {
                // Only when CDMA is in service, ERI will take effect
                String eriText = mSS.getOperatorAlphaLong();
                // Now the CDMAPhone sees the new ServiceState so it can get the
                // new ERI text
                if (mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE) {
                    eriText = mPhone.getCdmaEriText();
                } else if (mSS.getVoiceRegState() == ServiceState.STATE_POWER_OFF) {
                    eriText = (mIccRecords != null) ? mIccRecords.getServiceProviderName() : null;
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
                mSS.setOperatorAlphaLong(eriText);
            }

            if (mUiccApplcation != null && mUiccApplcation.getState() == AppState.APPSTATE_READY &&
                    mIccRecords != null && (mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE)) {
                // SIM is found on the device. If ERI roaming is OFF, and SID/NID matches
                // one configured in SIM, use operator name from CSIM record.
                boolean showSpn =
                    ((RuimRecords)mIccRecords).getCsimSpnDisplayCondition();
                int iconIndex = mSS.getCdmaEriIconIndex();

                if (showSpn && (iconIndex == EriInfo.ROAMING_INDICATOR_OFF) &&
                    isInHomeSidNid(mSS.getSystemId(), mSS.getNetworkId()) &&
                    mIccRecords != null) {
                    mSS.setOperatorAlphaLong(mIccRecords.getServiceProviderName());
                }
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
                if (DBG) log("operatorNumeric is null");
                tm.setNetworkCountryIsoForPhone(mPhone.getPhoneId(), "");
                mGotCountryCode = false;
            } else {
                String isoCountryCode = "";
                String mcc = operatorNumeric.substring(0, 3);
                try {
                    isoCountryCode = MccTable.countryCodeForMcc(Integer.parseInt(operatorNumeric
                            .substring(0, 3)));
                } catch (NumberFormatException ex) {
                    loge("countryCodeForMcc error" + ex);
                } catch (StringIndexOutOfBoundsException ex) {
                    loge("countryCodeForMcc error" + ex);
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
            setRoamingType(mSS);
            log("Broadcasting ServiceState : " + mSS);
            mPhone.notifyServiceStateChanged(mSS);
        }

        if (hasCdmaDataConnectionAttached || has4gHandoff) {
            mAttachedRegistrants.notifyRegistrants();
        }

        if (hasCdmaDataConnectionDetached) {
            mDetachedRegistrants.notifyRegistrants();
        }

        if ((hasCdmaDataConnectionChanged || hasDataRadioTechnologyChanged)) {
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

        ArrayList<CellInfo> arrayCi = new ArrayList<CellInfo>();
        synchronized(mCellInfo) {
            CellInfoLte cil = (CellInfoLte)mCellInfo;

            boolean cidChanged = ! mNewCellIdentityLte.equals(mLasteCellIdentityLte);
            if (hasRegistered || hasDeregistered || cidChanged) {
                // TODO: Handle the absence of LteCellIdentity
                long timeStamp = SystemClock.elapsedRealtime() * 1000;
                boolean registered = mSS.getVoiceRegState() == ServiceState.STATE_IN_SERVICE;
                mLasteCellIdentityLte = mNewCellIdentityLte;

                cil.setRegistered(registered);
                cil.setCellIdentity(mLasteCellIdentityLte);
                if (DBG) {
                    log("pollStateDone: hasRegistered=" + hasRegistered +
                            " hasDeregistered=" + hasDeregistered +
                            " cidChanged=" + cidChanged +
                            " mCellInfo=" + mCellInfo);
                }
                arrayCi.add(mCellInfo);
            }
            mPhoneBase.notifyCellInfo(arrayCi);
        }
    }

    @Override
    protected boolean onSignalStrengthResult(AsyncResult ar, boolean isGsm) {
        if (mSS.getRilDataRadioTechnology() == ServiceState.RIL_RADIO_TECHNOLOGY_LTE) {
            isGsm = true;
        }
        boolean ssChanged = super.onSignalStrengthResult(ar, isGsm);

        synchronized (mCellInfo) {
            if (mSS.getRilDataRadioTechnology() == ServiceState.RIL_RADIO_TECHNOLOGY_LTE) {
                mCellInfoLte.setTimeStamp(SystemClock.elapsedRealtime() * 1000);
                mCellInfoLte.setTimeStampType(CellInfo.TIMESTAMP_TYPE_JAVA_RIL);
                mCellInfoLte.getCellSignalStrength()
                                .initialize(mSignalStrength,SignalStrength.INVALID);
            }
            if (mCellInfoLte.getCellIdentity() != null) {
                ArrayList<CellInfo> arrayCi = new ArrayList<CellInfo>();
                arrayCi.add(mCellInfoLte);
                mPhoneBase.notifyCellInfo(arrayCi);
            }
        }
        return ssChanged;
    }

    @Override
    public boolean isConcurrentVoiceAndDataAllowed() {
        // Using the Conncurrent Service Supported flag for CdmaLte devices.
        return mSS.getCssIndicator() == 1;
    }

    /**
     * Check whether the specified SID and NID pair appears in the HOME SID/NID list
     * read from NV or SIM.
     *
     * @return true if provided sid/nid pair belongs to operator's home network.
     */
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

    /**
     * TODO: Remove when we get new ril/modem for Galaxy Nexus.
     *
     * @return all available cell information, the returned List maybe empty but never null.
     */
    @Override
    public List<CellInfo> getAllCellInfo() {
        if (mCi.getRilVersion() >= 8) {
            return super.getAllCellInfo();
        } else {
            ArrayList<CellInfo> arrayList = new ArrayList<CellInfo>();
            CellInfo ci;
            synchronized(mCellInfo) {
                arrayList.add(mCellInfoLte);
            }
            if (DBG) log ("getAllCellInfo: arrayList=" + arrayList);
            return arrayList;
        }
    }

    @Override
    protected UiccCardApplication getUiccCardApplication() {
            return  mUiccController.getUiccCardApplication(((CDMALTEPhone)mPhone).
                    getPhoneId(), UiccController.APP_FAM_3GPP2);
    }

    protected void updateCdmaSubscription() {
        mCi.getCDMASubscription(obtainMessage(EVENT_POLL_STATE_CDMA_SUBSCRIPTION));
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
                int dds = SubscriptionManager.getDefaultDataSubId();
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

    @Override
    protected void log(String s) {
        Rlog.d(LOG_TAG, "[CdmaLteSST] " + s);
    }

    @Override
    protected void loge(String s) {
        Rlog.e(LOG_TAG, "[CdmaLteSST] " + s);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("CdmaLteServiceStateTracker extends:");
        super.dump(fd, pw, args);
        pw.println(" mCdmaLtePhone=" + mCdmaLtePhone);
    }
}
