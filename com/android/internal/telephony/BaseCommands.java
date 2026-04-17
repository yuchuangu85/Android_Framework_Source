
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

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.telephony.Annotation.RadioPowerState;
import android.telephony.BarringInfo;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;

import com.android.internal.telephony.uicc.SimPhonebookRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * @hide
 */
public abstract class BaseCommands implements CommandsInterface {
    //***** Instance Variables
    @UnsupportedAppUsage
    protected Context mContext;
    protected int mState = TelephonyManager.RADIO_POWER_UNAVAILABLE;
    @UnsupportedAppUsage
    protected Object mStateMonitor = new Object();

    protected RegistrantList mRadioStateChangedRegistrants = new RegistrantList();
    protected RegistrantList mOnRegistrants = new RegistrantList();
    protected RegistrantList mAvailRegistrants = new RegistrantList();
    protected RegistrantList mOffOrNotAvailRegistrants = new RegistrantList();
    protected RegistrantList mNotAvailRegistrants = new RegistrantList();
    @UnsupportedAppUsage
    protected RegistrantList mCallStateRegistrants = new RegistrantList();
    protected RegistrantList mNetworkStateRegistrants = new RegistrantList();
    protected RegistrantList mDataCallListChangedRegistrants = new RegistrantList();
    protected RegistrantList mDataCallListUpdatedRegistrants = new RegistrantList();
    protected RegistrantList mApnUnthrottledRegistrants = new RegistrantList();
    protected RegistrantList mSlicingConfigChangedRegistrants = new RegistrantList();
    @UnsupportedAppUsage
    protected RegistrantList mVoiceRadioTechChangedRegistrants = new RegistrantList();
    @UnsupportedAppUsage
    protected RegistrantList mImsNetworkStateChangedRegistrants = new RegistrantList();
    @UnsupportedAppUsage
    protected RegistrantList mIccStatusChangedRegistrants = new RegistrantList();
    protected RegistrantList mVoicePrivacyOnRegistrants = new RegistrantList();
    protected RegistrantList mVoicePrivacyOffRegistrants = new RegistrantList();
    protected RegistrantList mDisplayInfoRegistrants = new RegistrantList();
    protected RegistrantList mSignalInfoRegistrants = new RegistrantList();
    protected RegistrantList mRingbackToneRegistrants = new RegistrantList();
    @UnsupportedAppUsage
    protected RegistrantList mResendIncallMuteRegistrants = new RegistrantList();
    @UnsupportedAppUsage
    protected RegistrantList mExitEmergencyCallbackModeRegistrants = new RegistrantList();
    protected RegistrantList mRilConnectedRegistrants = new RegistrantList();
    @UnsupportedAppUsage
    protected RegistrantList mIccRefreshRegistrants = new RegistrantList();
    @UnsupportedAppUsage
    protected RegistrantList mRilCellInfoListRegistrants = new RegistrantList();
    @UnsupportedAppUsage
    protected RegistrantList mSubscriptionStatusRegistrants = new RegistrantList();
    @UnsupportedAppUsage
    protected RegistrantList mSrvccStateRegistrants = new RegistrantList();
    @UnsupportedAppUsage
    protected RegistrantList mHardwareConfigChangeRegistrants = new RegistrantList();
    @UnsupportedAppUsage
    protected RegistrantList mPhoneRadioCapabilityChangedRegistrants =
            new RegistrantList();
    protected RegistrantList mPcoDataRegistrants = new RegistrantList();
    protected RegistrantList mCarrierInfoForImsiEncryptionRegistrants = new RegistrantList();
    protected RegistrantList mRilNetworkScanResultRegistrants = new RegistrantList();
    protected RegistrantList mModemResetRegistrants = new RegistrantList();
    protected RegistrantList mNattKeepaliveStatusRegistrants = new RegistrantList();
    protected RegistrantList mPhysicalChannelConfigurationRegistrants = new RegistrantList();
    protected RegistrantList mLceInfoRegistrants = new RegistrantList();
    protected RegistrantList mEmergencyNumberListRegistrants = new RegistrantList();
    protected RegistrantList mUiccApplicationsEnablementRegistrants = new RegistrantList();
    protected RegistrantList mBarringInfoChangedRegistrants = new RegistrantList();
    protected RegistrantList mSimPhonebookChangedRegistrants = new RegistrantList();
    protected RegistrantList mSimPhonebookRecordsReceivedRegistrants = new RegistrantList();
    protected RegistrantList mEmergencyNetworkScanRegistrants = new RegistrantList();
    protected RegistrantList mConnectionSetupFailureRegistrants = new RegistrantList();
    protected RegistrantList mNotifyAnbrRegistrants = new RegistrantList();
    protected RegistrantList mTriggerImsDeregistrationRegistrants = new RegistrantList();
    protected RegistrantList mImeiInfoRegistrants = new RegistrantList();
    protected RegistrantList mCellularIdentifierDisclosedRegistrants = new RegistrantList();
    protected RegistrantList mSecurityAlgorithmUpdatedRegistrants = new RegistrantList();
    protected RegistrantList mDisplayNetworkTypeChangedRegistrants = new RegistrantList();
    protected RegistrantList mPrioritizedScanModeChangedRegistrants = new RegistrantList();
    protected RegistrantList mNetworkSecurityEventsRegistrants = new RegistrantList();

    @UnsupportedAppUsage
    protected Registrant mGsmSmsRegistrant;
    @UnsupportedAppUsage
    protected Registrant mCdmaSmsRegistrant;
    @UnsupportedAppUsage
    protected Registrant mNITZTimeRegistrant;
    @UnsupportedAppUsage
    protected Registrant mSignalStrengthRegistrant;
    @UnsupportedAppUsage
    protected Registrant mUSSDRegistrant;
    @UnsupportedAppUsage
    protected Registrant mSmsOnSimRegistrant;
    @UnsupportedAppUsage
    protected Registrant mSmsStatusRegistrant;
    @UnsupportedAppUsage
    protected Registrant mSsnRegistrant;
    @UnsupportedAppUsage
    protected Registrant mCatSessionEndRegistrant;
    @UnsupportedAppUsage
    protected Registrant mCatProCmdRegistrant;
    @UnsupportedAppUsage
    protected Registrant mCatEventRegistrant;
    @UnsupportedAppUsage
    protected Registrant mCatCallSetUpRegistrant;
    @UnsupportedAppUsage
    protected Registrant mIccSmsFullRegistrant;
    @UnsupportedAppUsage
    protected Registrant mEmergencyCallbackModeRegistrant;
    @UnsupportedAppUsage
    protected Registrant mRingRegistrant;
    @UnsupportedAppUsage
    protected Registrant mRestrictedStateRegistrant;
    @UnsupportedAppUsage
    protected Registrant mGsmBroadcastSmsRegistrant;
    @UnsupportedAppUsage
    protected Registrant mCatCcAlphaRegistrant;
    @UnsupportedAppUsage
    protected Registrant mSsRegistrant;
    protected Registrant mRegistrationFailedRegistrant;

    // Lock that mLastEmergencyNumberListIndication uses.
    private Object mLastEmergencyNumberListIndicationLock = new Object();
    // Cache last emergency number list indication from radio
    private final List<EmergencyNumber> mLastEmergencyNumberListIndication = new ArrayList<>();

    // The last barring information received
    protected BarringInfo mLastBarringInfo = new BarringInfo();
    // Preferred network type received from PhoneFactory.
    // This is used when establishing a connection to the
    // vendor ril so it starts up in the correct mode.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    protected int mAllowedNetworkTypesBitmask;
    // Type of Phone, GSM or CDMA. Set by GsmCdmaPhone.
    @UnsupportedAppUsage
    protected int mPhoneType;
    // RIL Version
    protected int mRilVersion = -1;

    public BaseCommands(Context context) {
        mContext = context;  // May be null (if so we won't log statistics)
    }

    //***** CommandsInterface implementation

    @Override
    public @RadioPowerState int getRadioState() {
        return mState;
    }

    @Override
    public void registerForRadioStateChanged(Handler h, int what, Object obj) {
        synchronized (mStateMonitor) {
            mRadioStateChangedRegistrants.addUnique(h, what, obj);
            Message.obtain(h, what, new AsyncResult(obj, null, null)).sendToTarget();
        }
    }

    @Override
    public void unregisterForRadioStateChanged(Handler h) {
        synchronized (mStateMonitor) {
            mRadioStateChangedRegistrants.remove(h);
        }
    }

    public void registerForImsNetworkStateChanged(Handler h, int what, Object obj) {
        mImsNetworkStateChangedRegistrants.addUnique(h, what, obj);
    }

    public void unregisterForImsNetworkStateChanged(Handler h) {
        mImsNetworkStateChangedRegistrants.remove(h);
    }

    @Override
    public void registerForOn(Handler h, int what, Object obj) {
        synchronized (mStateMonitor) {
            mOnRegistrants.addUnique(h, what, obj);

            if (mState == TelephonyManager.RADIO_POWER_ON) {
                Message.obtain(h, what, new AsyncResult(obj, null, null)).sendToTarget();
            }
        }
    }
    @Override
    public void unregisterForOn(Handler h) {
        synchronized (mStateMonitor) {
            mOnRegistrants.remove(h);
        }
    }


    @Override
    public void registerForAvailable(Handler h, int what, Object obj) {
        synchronized (mStateMonitor) {
            mAvailRegistrants.addUnique(h, what, obj);

            if (mState != TelephonyManager.RADIO_POWER_UNAVAILABLE) {
                Message.obtain(h, what, new AsyncResult(obj, null, null)).sendToTarget();
            }
        }
    }

    @Override
    public void unregisterForAvailable(Handler h) {
        synchronized(mStateMonitor) {
            mAvailRegistrants.remove(h);
        }
    }

    @Override
    public void registerForNotAvailable(Handler h, int what, Object obj) {
        synchronized (mStateMonitor) {
            mNotAvailRegistrants.addUnique(h, what, obj);

            if (mState == TelephonyManager.RADIO_POWER_UNAVAILABLE) {
                Message.obtain(h, what, new AsyncResult(obj, null, null)).sendToTarget();
            }
        }
    }

    @Override
    public void unregisterForNotAvailable(Handler h) {
        synchronized (mStateMonitor) {
            mNotAvailRegistrants.remove(h);
        }
    }

    @Override
    public void registerForOffOrNotAvailable(Handler h, int what, Object obj) {
        synchronized (mStateMonitor) {
            mOffOrNotAvailRegistrants.addUnique(h, what, obj);

            if (mState == TelephonyManager.RADIO_POWER_OFF
                    || mState == TelephonyManager.RADIO_POWER_UNAVAILABLE) {
                Message.obtain(h, what, new AsyncResult(obj, null, null)).sendToTarget();
            }
        }
    }
    @Override
    public void unregisterForOffOrNotAvailable(Handler h) {
        synchronized(mStateMonitor) {
            mOffOrNotAvailRegistrants.remove(h);
        }
    }

    @Override
    public void registerForCallStateChanged(Handler h, int what, Object obj) {
        mCallStateRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void registerForNetworkStateChanged(Handler h, int what, Object obj) {
        mNetworkStateRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForNetworkStateChanged(Handler h) {
        mNetworkStateRegistrants.remove(h);
    }

    @Override
    public void registerForDataCallListChanged(Handler h, int what, Object obj) {
        mDataCallListChangedRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForDataCallListChanged(Handler h) {
        mDataCallListChangedRegistrants.remove(h);
    }

    @Override
    public void registerForDataCallListUpdated(Handler h, int what, Object obj) {
        mDataCallListUpdatedRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForDataCallListUpdated(Handler h) {
        mDataCallListUpdatedRegistrants.remove(h);
    }

    @Override
    public void registerForApnUnthrottled(Handler h, int what, Object obj) {
        mApnUnthrottledRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void registerForSlicingConfigChanged(Handler h, int what, Object obj) {
        mSlicingConfigChangedRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void registerForVoiceRadioTechChanged(Handler h, int what, Object obj) {
        mVoiceRadioTechChangedRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void registerForIccStatusChanged(Handler h, int what, Object obj) {
        mIccStatusChangedRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void setOnNewGsmSms(Handler h, int what, Object obj) {
        mGsmSmsRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void unSetOnNewGsmSms(Handler h) {
        if (mGsmSmsRegistrant != null && mGsmSmsRegistrant.getHandler() == h) {
            mGsmSmsRegistrant.clear();
            mGsmSmsRegistrant = null;
        }
    }

    @Override
    public void setOnNewCdmaSms(Handler h, int what, Object obj) {
        mCdmaSmsRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void unSetOnNewCdmaSms(Handler h) {
        if (mCdmaSmsRegistrant != null && mCdmaSmsRegistrant.getHandler() == h) {
            mCdmaSmsRegistrant.clear();
            mCdmaSmsRegistrant = null;
        }
    }

    @Override
    public void setOnNewGsmBroadcastSms(Handler h, int what, Object obj) {
        mGsmBroadcastSmsRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void unSetOnNewGsmBroadcastSms(Handler h) {
        if (mGsmBroadcastSmsRegistrant != null && mGsmBroadcastSmsRegistrant.getHandler() == h) {
            mGsmBroadcastSmsRegistrant.clear();
            mGsmBroadcastSmsRegistrant = null;
        }
    }

    @Override
    public void setOnSmsOnSim(Handler h, int what, Object obj) {
        mSmsOnSimRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void unSetOnSmsOnSim(Handler h) {
        if (mSmsOnSimRegistrant != null && mSmsOnSimRegistrant.getHandler() == h) {
            mSmsOnSimRegistrant.clear();
            mSmsOnSimRegistrant = null;
        }
    }

    @Override
    public void setOnSmsStatus(Handler h, int what, Object obj) {
        mSmsStatusRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void unSetOnSmsStatus(Handler h) {
        if (mSmsStatusRegistrant != null && mSmsStatusRegistrant.getHandler() == h) {
            mSmsStatusRegistrant.clear();
            mSmsStatusRegistrant = null;
        }
    }

    @Override
    public void setOnSignalStrengthUpdate(Handler h, int what, Object obj) {
        mSignalStrengthRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void unSetOnSignalStrengthUpdate(Handler h) {
        if (mSignalStrengthRegistrant != null && mSignalStrengthRegistrant.getHandler() == h) {
            mSignalStrengthRegistrant.clear();
            mSignalStrengthRegistrant = null;
        }
    }

    @Override
    public void setOnNITZTime(Handler h, int what, Object obj) {
        mNITZTimeRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void unSetOnNITZTime(Handler h) {
        if (mNITZTimeRegistrant != null && mNITZTimeRegistrant.getHandler() == h) {
            mNITZTimeRegistrant.clear();
            mNITZTimeRegistrant = null;
        }
    }

    @Override
    public void setOnUSSD(Handler h, int what, Object obj) {
        mUSSDRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void setOnSuppServiceNotification(Handler h, int what, Object obj) {
        mSsnRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void setOnCatSessionEnd(Handler h, int what, Object obj) {
        mCatSessionEndRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void unSetOnCatSessionEnd(Handler h) {
        if (mCatSessionEndRegistrant != null && mCatSessionEndRegistrant.getHandler() == h) {
            mCatSessionEndRegistrant.clear();
            mCatSessionEndRegistrant = null;
        }
    }

    @Override
    public void setOnCatProactiveCmd(Handler h, int what, Object obj) {
        mCatProCmdRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void unSetOnCatProactiveCmd(Handler h) {
        if (mCatProCmdRegistrant != null && mCatProCmdRegistrant.getHandler() == h) {
            mCatProCmdRegistrant.clear();
            mCatProCmdRegistrant = null;
        }
    }

    @Override
    public void setOnCatEvent(Handler h, int what, Object obj) {
        mCatEventRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void unSetOnCatEvent(Handler h) {
        if (mCatEventRegistrant != null && mCatEventRegistrant.getHandler() == h) {
            mCatEventRegistrant.clear();
            mCatEventRegistrant = null;
        }
    }

    @Override
    public void setOnCatCallSetUp(Handler h, int what, Object obj) {
        mCatCallSetUpRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void unSetOnCatCallSetUp(Handler h) {
        if (mCatCallSetUpRegistrant != null && mCatCallSetUpRegistrant.getHandler() == h) {
            mCatCallSetUpRegistrant.clear();
            mCatCallSetUpRegistrant = null;
        }
    }

    @Override
    public void setOnIccSmsFull(Handler h, int what, Object obj) {
        mIccSmsFullRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void unSetOnIccSmsFull(Handler h) {
        if (mIccSmsFullRegistrant != null && mIccSmsFullRegistrant.getHandler() == h) {
            mIccSmsFullRegistrant.clear();
            mIccSmsFullRegistrant = null;
        }
    }

    @Override
    public void registerForIccRefresh(Handler h, int what, Object obj) {
        mIccRefreshRegistrants.addUnique(h, what, obj);
    }
    @Override
    public void setEmergencyCallbackMode(Handler h, int what, Object obj) {
        mEmergencyCallbackModeRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void unregisterForIccRefresh(Handler h) {
        mIccRefreshRegistrants.remove(h);
    }
    @Override
    public void setOnCallRing(Handler h, int what, Object obj) {
        mRingRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void setOnSs(Handler h, int what, Object obj) {
        mSsRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void setOnCatCcAlphaNotify(Handler h, int what, Object obj) {
        mCatCcAlphaRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void unSetOnCatCcAlphaNotify(Handler h) {
        mCatCcAlphaRegistrant.clear();
    }

    @Override
    public void setOnRegistrationFailed(Handler h, int what, Object obj) {
        mRegistrationFailedRegistrant = new Registrant(h, what, obj);
    }

    @Override
    public void registerForInCallVoicePrivacyOn(Handler h, int what, Object obj) {
        mVoicePrivacyOnRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForInCallVoicePrivacyOn(Handler h){
        mVoicePrivacyOnRegistrants.remove(h);
    }

    @Override
    public void registerForInCallVoicePrivacyOff(Handler h, int what, Object obj) {
        mVoicePrivacyOffRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForInCallVoicePrivacyOff(Handler h){
        mVoicePrivacyOffRegistrants.remove(h);
    }

    @Override
    public void setOnRestrictedStateChanged(Handler h, int what, Object obj) {
        mRestrictedStateRegistrant = new Registrant (h, what, obj);
    }

    @Override
    public void registerForDisplayInfo(Handler h, int what, Object obj) {
        mDisplayInfoRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForDisplayInfo(Handler h) {
        mDisplayInfoRegistrants.remove(h);
    }

    @Override
    public void registerForSignalInfo(Handler h, int what, Object obj) {
        mSignalInfoRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForSignalInfo(Handler h) {
        mSignalInfoRegistrants.remove(h);
    }

    @Override
    public void registerForRingbackTone(Handler h, int what, Object obj) {
        mRingbackToneRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForRingbackTone(Handler h) {
        mRingbackToneRegistrants.remove(h);
    }

    @Override
    public void registerForResendIncallMute(Handler h, int what, Object obj) {
        mResendIncallMuteRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForResendIncallMute(Handler h) {
        mResendIncallMuteRegistrants.remove(h);
    }

    @Override
    public void registerForExitEmergencyCallbackMode(Handler h, int what, Object obj) {
        mExitEmergencyCallbackModeRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void registerForHardwareConfigChanged(Handler h, int what, Object obj) {
        mHardwareConfigChangeRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForHardwareConfigChanged(Handler h) {
        mHardwareConfigChangeRegistrants.remove(h);
    }

    @Override
    public void registerForNetworkScanResult(Handler h, int what, Object obj) {
        mRilNetworkScanResultRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForNetworkScanResult(Handler h) {
        mRilNetworkScanResultRegistrants.remove(h);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerForRilConnected(Handler h, int what, Object obj) {
        mRilConnectedRegistrants.addUnique(h, what, obj);
        if (mRilVersion != -1) {
            Message.obtain(h, what, new AsyncResult(obj, new Integer(mRilVersion), null))
                    .sendToTarget();
        }
    }

    @Override
    public void registerForEmergencyNumberList(Handler h, int what, Object obj) {
        mEmergencyNumberListRegistrants.addUnique(h, what, obj);
        // Notify the last emergency number list from radio to new registrants because they may
        // miss the latest indication (e.g. constructed in a delay after HAL is registrated).
        List<EmergencyNumber> lastEmergencyNumberListIndication =
                getLastEmergencyNumberListIndication();
        if (lastEmergencyNumberListIndication != null) {
            mEmergencyNumberListRegistrants.notifyRegistrants(new AsyncResult(
                    null, lastEmergencyNumberListIndication, null));
        }
    }

    //***** Protected Methods
    /**
     * Store new RadioState and send notification based on the changes
     *
     * This function is called only by RIL.java when receiving unsolicited
     * RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED
     *
     * RadioState has 3 values : RADIO_OFF, RADIO_UNAVAILABLE, RADIO_ON.
     *
     * @param newState new RadioState decoded from RIL_UNSOL_RADIO_STATE_CHANGED
     * @param forceNotifyRegistrants boolean indicating if registrants should be notified even if
     * there is no change in state
     */
    protected void setRadioState(int newState, boolean forceNotifyRegistrants) {
        int oldState;

        synchronized (mStateMonitor) {
            oldState = mState;
            mState = newState;

            if (oldState == mState && !forceNotifyRegistrants) {
                // no state transition
                return;
            }

            mRadioStateChangedRegistrants.notifyRegistrants();

            if (mState != TelephonyManager.RADIO_POWER_UNAVAILABLE
                    && oldState == TelephonyManager.RADIO_POWER_UNAVAILABLE) {
                mAvailRegistrants.notifyRegistrants();
            }

            if (mState == TelephonyManager.RADIO_POWER_UNAVAILABLE
                    && oldState != TelephonyManager.RADIO_POWER_UNAVAILABLE) {
                mNotAvailRegistrants.notifyRegistrants();
            }

            if (mState == TelephonyManager.RADIO_POWER_ON
                    && oldState != TelephonyManager.RADIO_POWER_ON) {
                mOnRegistrants.notifyRegistrants();
            }

            if ((mState == TelephonyManager.RADIO_POWER_OFF
                    || mState == TelephonyManager.RADIO_POWER_UNAVAILABLE)
                    && (oldState == TelephonyManager.RADIO_POWER_ON)) {
                mOffOrNotAvailRegistrants.notifyRegistrants();
                mLastBarringInfo = new BarringInfo();
            }
        }
    }

    protected void cacheEmergencyNumberListIndication(
            List<EmergencyNumber> emergencyNumberListIndication) {
        synchronized (mLastEmergencyNumberListIndicationLock) {
            mLastEmergencyNumberListIndication.clear();
            mLastEmergencyNumberListIndication.addAll(emergencyNumberListIndication);
        }
    }

    private List<EmergencyNumber> getLastEmergencyNumberListIndication() {
        synchronized (mLastEmergencyNumberListIndicationLock) {
            return new ArrayList<>(mLastEmergencyNumberListIndication);
        }
    }

    /** {@inheritDoc} */
    @Override
    public @NonNull BarringInfo getLastBarringInfo() {
        return mLastBarringInfo;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerForCellInfoList(Handler h, int what, Object obj) {
        mRilCellInfoListRegistrants.addUnique(h, what, obj);
    }
    @Override
    public void unregisterForCellInfoList(Handler h) {
        mRilCellInfoListRegistrants.remove(h);
    }

    @Override
    public void registerForPhysicalChannelConfiguration(Handler h, int what, Object obj) {
        mPhysicalChannelConfigurationRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForPhysicalChannelConfiguration(Handler h) {
        mPhysicalChannelConfigurationRegistrants.remove(h);
    }

    @Override
    public void registerForSrvccStateChanged(Handler h, int what, Object obj) {
        mSrvccStateRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForDisplayNetworkTypeChanged(Handler h) {
        mDisplayNetworkTypeChangedRegistrants.remove(h);
    }

    @Override
    public void registerForDisplayNetworkTypeChanged(Handler h, int what, Object obj) {
        mDisplayNetworkTypeChangedRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForPrioritizedScanModeChanged(Handler h) {
        mPrioritizedScanModeChangedRegistrants.remove(h);
    }

    @Override
    public void registerForPrioritizedScanModeChanged(Handler h, int what, Object obj) {
        mPrioritizedScanModeChangedRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void testingEmergencyCall() {}

    @Override
    public int getRilVersion() {
        return mRilVersion;
    }

    public void setUiccSubscription(int slotId, int appIndex, int subId, int subStatus,
            Message response) {
    }

    public void setDataAllowed(boolean allowed, Message response) {
    }

    @Override
    public void requestShutdown(Message result) {
    }

    @Override
    public void getRadioCapability(Message result) {
    }

    @Override
    public void setRadioCapability(RadioCapability rc, Message response) {
    }

    @Override
    public void registerForRadioCapabilityChanged(Handler h, int what, Object obj) {
        mPhoneRadioCapabilityChangedRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForRadioCapabilityChanged(Handler h) {
        mPhoneRadioCapabilityChangedRegistrants.remove(h);
    }

    @Override
    public void registerForLceInfo(Handler h, int what, Object obj) {
        synchronized (mStateMonitor) {
            mLceInfoRegistrants.addUnique(h, what, obj);
        }
    }

    @Override
    public void unregisterForLceInfo(Handler h) {
        synchronized (mStateMonitor) {
            mLceInfoRegistrants.remove(h);
        }
    }

    @Override
    public void registerForModemReset(Handler h, int what, Object obj) {
        mModemResetRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForModemReset(Handler h) {
        mModemResetRegistrants.remove(h);
    }

    @Override
    public void registerForPcoData(Handler h, int what, Object obj) {
        mPcoDataRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForPcoData(Handler h) {
        mPcoDataRegistrants.remove(h);
    }

    @Override
    public void registerForCarrierInfoForImsiEncryption(Handler h, int what, Object obj) {
        mCarrierInfoForImsiEncryptionRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void registerForNattKeepaliveStatus(Handler h, int what, Object obj) {
        synchronized (mStateMonitor) {
            mNattKeepaliveStatusRegistrants.addUnique(h, what, obj);
        }
    }

    @Override
    public void unregisterForNattKeepaliveStatus(Handler h) {
        synchronized (mStateMonitor) {
            mNattKeepaliveStatusRegistrants.remove(h);
        }
    }

    /**
     * Registers the handler for RIL_UNSOL_UICC_APPLICATIONS_ENABLEMENT_CHANGED events.
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    @Override
    public void registerUiccApplicationEnablementChanged(Handler h, int what, Object obj) {
        mUiccApplicationsEnablementRegistrants.addUnique(h, what, obj);
    }

    /**
     * Registers the handler for RIL_UNSOL_BARRING_INFO_CHANGED events.
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    @Override
    public void registerForBarringInfoChanged(Handler h, int what, Object obj) {
        mBarringInfoChangedRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregisters the handler for RIL_UNSOL_BARRING_INFO_CHANGED events.
     *
     * @param h Handler for notification message.
     */
    @Override
    public void unregisterForBarringInfoChanged(Handler h) {
        mBarringInfoChangedRegistrants.remove(h);
    }

    @Override
    public void registerForSimPhonebookChanged(Handler h, int what, Object obj) {
        mSimPhonebookChangedRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForSimPhonebookChanged(Handler h) {
        mSimPhonebookChangedRegistrants.remove(h);
    }

    @Override
    public void registerForSimPhonebookRecordsReceived(Handler h, int what, Object obj) {
        mSimPhonebookRecordsReceivedRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForSimPhonebookRecordsReceived(Handler h) {
        mSimPhonebookRecordsReceivedRegistrants.remove(h);
    }

    @Override
    public void getSimPhonebookRecords(Message result) {
    }

    @Override
    public void getSimPhonebookCapacity(Message result) {
    }

    @Override
    public void updateSimPhonebookRecord(SimPhonebookRecord phonebookRecord, Message result) {
    }

    /**
     * Register for Emergency network scan result.
     *
     * @param h Handler for notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    @Override
    public void registerForEmergencyNetworkScan(Handler h, int what, Object obj) {
        mEmergencyNetworkScanRegistrants.add(h, what, obj);
    }

    /**
     * Unregister for Emergency network scan result.
     *
     * @param h Handler to be removed from the registrant list.
     */
    @Override
    public void unregisterForEmergencyNetworkScan(Handler h) {
        mEmergencyNetworkScanRegistrants.remove(h);
    }

    @Override
    public void registerForConnectionSetupFailure(Handler h, int what, Object obj) {
        mConnectionSetupFailureRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void unregisterForConnectionSetupFailure(Handler h) {
        mConnectionSetupFailureRegistrants.remove(h);
    }

    @Override
    public void registerForNotifyAnbr(Handler h, int what, Object obj) {
        mNotifyAnbrRegistrants.addUnique(h, what, obj);
    }

    @Override
    public void registerForTriggerImsDeregistration(Handler h, int what, Object obj) {
        mTriggerImsDeregistrationRegistrants.add(h, what, obj);
    }

    /**
     * Register to listen for the changes in the primary IMEI with respect to the sim slot.
     */
    @Override
    public void registerForImeiMappingChanged(Handler h, int what, Object obj) {
        mImeiInfoRegistrants.add(h, what, obj);
    }

    @Override
    public void registerForCellularIdentifierDisclosures(Handler h, int what, Object obj) {
        mCellularIdentifierDisclosedRegistrants.add(h, what, obj);
    }

    @Override
    public void registerForSecurityAlgorithmUpdates(Handler h, int what, Object obj) {
        mSecurityAlgorithmUpdatedRegistrants.add(h, what, obj);
    }

    @Override
    public void registerForNetworkSecurityEvents(Handler h, int what, Object obj) {
        mNetworkSecurityEventsRegistrants.add(h, what, obj);
    }
}
