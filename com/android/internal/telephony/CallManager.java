/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.flags.Flags;
import com.android.internal.telephony.imsphone.ImsPhoneConnection;
import com.android.telephony.Rlog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * @hide
 *
 * CallManager class provides an abstract layer for PhoneApp to access
 * and control calls. It implements Phone interface.
 *
 * CallManager provides call and connection control as well as
 * channel capability.
 *
 * There are three categories of APIs CallManager provided
 *
 *  1. Call control and operation, such as dial() and hangup()
 *  2. Channel capabilities, such as CanConference()
 *  3. Register notification
 *
 *
 */
public class CallManager {

    private static final String LOG_TAG ="CallManager";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private static final int EVENT_DISCONNECT = 100;
    @VisibleForTesting static final int EVENT_PRECISE_CALL_STATE_CHANGED = 101;
    private static final int EVENT_NEW_RINGING_CONNECTION = 102;
    private static final int EVENT_UNKNOWN_CONNECTION = 103;
    private static final int EVENT_INCOMING_RING = 104;
    @VisibleForTesting static final int EVENT_RINGBACK_TONE = 105;
    private static final int EVENT_IN_CALL_VOICE_PRIVACY_ON = 106;
    private static final int EVENT_IN_CALL_VOICE_PRIVACY_OFF = 107;
    @VisibleForTesting static final int EVENT_CALL_WAITING = 108;
    private static final int EVENT_DISPLAY_INFO = 109;
    private static final int EVENT_SIGNAL_INFO = 110;
    private static final int EVENT_CDMA_OTA_STATUS_CHANGE = 111;
    private static final int EVENT_RESEND_INCALL_MUTE = 112;
    private static final int EVENT_MMI_INITIATE = 113;
    private static final int EVENT_MMI_COMPLETE = 114;
    private static final int EVENT_ECM_TIMER_RESET = 115;
    private static final int EVENT_SUBSCRIPTION_INFO_READY = 116;
    private static final int EVENT_SUPP_SERVICE_FAILED = 117;
    private static final int EVENT_SERVICE_STATE_CHANGED = 118;
    private static final int EVENT_POST_DIAL_CHARACTER = 119;
    private static final int EVENT_ONHOLD_TONE = 120;
    private static final int EVENT_TTY_MODE_RECEIVED = 122;

    // Singleton instance
    private static CallManager INSTANCE = null;

    // list of registered phones, which are Phone objs
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private final ArrayList<Phone> mPhones;

    // list of supported ringing calls
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private final ArrayList<Call> mRingingCalls;

    // list of supported background calls
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private final ArrayList<Call> mBackgroundCalls;

    // list of supported foreground calls
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private final ArrayList<Call> mForegroundCalls;

    // mapping of phones to registered handler instances used for callbacks from RIL
    private final HashMap<Phone, CallManagerHandler> mHandlerMap = new HashMap<>();

    private final TelephonyManager mTelephonyManager;

    // default phone as the first phone registered, which is Phone obj
    private Phone mDefaultPhone;

    private Object mRegistrantidentifier = new Object();

    // state registrants
    protected final RegistrantList mPreciseCallStateRegistrants
    = new RegistrantList();

    protected final RegistrantList mNewRingingConnectionRegistrants
    = new RegistrantList();

    protected final RegistrantList mIncomingRingRegistrants
    = new RegistrantList();

    protected final RegistrantList mDisconnectRegistrants
    = new RegistrantList();

    protected final RegistrantList mUnknownConnectionRegistrants
    = new RegistrantList();

    protected final RegistrantList mRingbackToneRegistrants
    = new RegistrantList();

    protected final RegistrantList mOnHoldToneRegistrants
    = new RegistrantList();

    protected final RegistrantList mInCallVoicePrivacyOnRegistrants
    = new RegistrantList();

    protected final RegistrantList mInCallVoicePrivacyOffRegistrants
    = new RegistrantList();

    protected final RegistrantList mCallWaitingRegistrants
    = new RegistrantList();

    protected final RegistrantList mDisplayInfoRegistrants
    = new RegistrantList();

    protected final RegistrantList mSignalInfoRegistrants
    = new RegistrantList();

    protected final RegistrantList mCdmaOtaStatusChangeRegistrants
    = new RegistrantList();

    protected final RegistrantList mResendIncallMuteRegistrants
    = new RegistrantList();

    protected final RegistrantList mMmiInitiateRegistrants
    = new RegistrantList();

    protected final RegistrantList mMmiCompleteRegistrants
    = new RegistrantList();

    protected final RegistrantList mEcmTimerResetRegistrants
    = new RegistrantList();

    protected final RegistrantList mSubscriptionInfoReadyRegistrants
    = new RegistrantList();

    protected final RegistrantList mSuppServiceFailedRegistrants
    = new RegistrantList();

    protected final RegistrantList mServiceStateChangedRegistrants
    = new RegistrantList();

    protected final RegistrantList mPostDialCharacterRegistrants
    = new RegistrantList();

    protected final RegistrantList mTtyModeReceivedRegistrants
    = new RegistrantList();

    private CallManager(Context context) {
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mPhones = new ArrayList<Phone>();
        mRingingCalls = new ArrayList<Call>();
        mBackgroundCalls = new ArrayList<Call>();
        mForegroundCalls = new ArrayList<Call>();
        mDefaultPhone = null;
    }

    /**
     * get singleton instance of CallManager
     * @return CallManager
     */
    @UnsupportedAppUsage
    public static synchronized CallManager getInstance(Context context) {
        if (INSTANCE == null) {
            INSTANCE = new CallManager(context);
        }
        return INSTANCE;
    }

    /**
     * get Phone object corresponds to subId
     * @return Phone
     */
    public Phone getPhone(int subId) {
        Phone p = null;
        for (Phone phone : mPhones) {
            if (phone.getSubId() == subId &&
                    phone.getPhoneType() != PhoneConstants.PHONE_TYPE_IMS) {
                p = phone;
                break;
            }
        }
        return p;
    }

    /**
     * Get current coarse-grained voice call state on a subId.
     * If the Call Manager has an active call and call waiting occurs,
     * then the phone state is RINGING not OFFHOOK
     *
     */
    @UnsupportedAppUsage
    public PhoneConstants.State getState(int subId) {
        PhoneConstants.State s = PhoneConstants.State.IDLE;

        for (Phone phone : mPhones) {
            if (phone.getSubId() == subId) {
                if (phone.getState() == PhoneConstants.State.RINGING) {
                    s = PhoneConstants.State.RINGING;
                } else if (phone.getState() == PhoneConstants.State.OFFHOOK) {
                    if (s == PhoneConstants.State.IDLE) s = PhoneConstants.State.OFFHOOK;
                }
            }
        }
        return s;
    }

    /**
     * Register phone to CallManager
     * @param phone to be registered
     * @return true if register successfully
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean registerPhone(Phone phone) {
        if (phone != null && !mPhones.contains(phone)) {

            if (DBG) {
                Rlog.d(LOG_TAG, "registerPhone(" +
                        phone.getPhoneName() + " " + phone + ")");
            }

            if (mPhones.isEmpty()) {
                mDefaultPhone = phone;
            }
            mPhones.add(phone);
            mRingingCalls.add(phone.getRingingCall());
            mBackgroundCalls.add(phone.getBackgroundCall());
            mForegroundCalls.add(phone.getForegroundCall());
            registerForPhoneStates(phone);
            return true;
        }
        return false;
    }

    /**
     * unregister phone from CallManager
     * @param phone to be unregistered
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void unregisterPhone(Phone phone) {
        if (phone != null && mPhones.contains(phone)) {

            if (DBG) {
                Rlog.d(LOG_TAG, "unregisterPhone(" +
                        phone.getPhoneName() + " " + phone + ")");
            }

            Phone imsPhone = phone.getImsPhone();
            if (imsPhone != null) {
                unregisterPhone(imsPhone);
            }

            mPhones.remove(phone);
            mRingingCalls.remove(phone.getRingingCall());
            mBackgroundCalls.remove(phone.getBackgroundCall());
            mForegroundCalls.remove(phone.getForegroundCall());
            unregisterForPhoneStates(phone);
            if (phone == mDefaultPhone) {
                if (mPhones.isEmpty()) {
                    mDefaultPhone = null;
                } else {
                    mDefaultPhone = mPhones.get(0);
                }
            }
        }
    }

    /**
     * return the default phone or null if no phone available
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public Phone getDefaultPhone() {
        return mDefaultPhone;
    }

    /**
     * @return the phone associated with the foreground call
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public Phone getFgPhone() {
        return getActiveFgCall().getPhone();
    }

    public Object getRegistrantIdentifier() {
        return mRegistrantidentifier;
    }

    private void registerForPhoneStates(Phone phone) {
        // We need to keep a mapping of handler to Phone for proper unregistration.
        // TODO: Clean up this solution as it is just a work around for each Phone instance
        // using the same Handler to register with the RIL. When time permits, we should consider
        // moving the handler (or the reference ot the handler) into the Phone object.
        // See b/17414427.
        CallManagerHandler handler = mHandlerMap.get(phone);
        if (handler != null) {
            Rlog.d(LOG_TAG, "This phone has already been registered.");
            return;
        }

        // New registration, create a new handler instance and register the phone.
        handler = new CallManagerHandler();
        mHandlerMap.put(phone, handler);

        // for common events supported by all phones
        // The mRegistrantIdentifier passed here, is to identify in the Phone
        // that the registrants are coming from the CallManager.
        phone.registerForPreciseCallStateChanged(handler, EVENT_PRECISE_CALL_STATE_CHANGED,
                mRegistrantidentifier);
        phone.registerForDisconnect(handler, EVENT_DISCONNECT,
                mRegistrantidentifier);
        phone.registerForNewRingingConnection(handler, EVENT_NEW_RINGING_CONNECTION,
                mRegistrantidentifier);
        phone.registerForUnknownConnection(handler, EVENT_UNKNOWN_CONNECTION,
                mRegistrantidentifier);
        phone.registerForIncomingRing(handler, EVENT_INCOMING_RING,
                mRegistrantidentifier);
        phone.registerForRingbackTone(handler, EVENT_RINGBACK_TONE,
                mRegistrantidentifier);
        phone.registerForInCallVoicePrivacyOn(handler, EVENT_IN_CALL_VOICE_PRIVACY_ON,
                mRegistrantidentifier);
        phone.registerForInCallVoicePrivacyOff(handler, EVENT_IN_CALL_VOICE_PRIVACY_OFF,
                mRegistrantidentifier);
        phone.registerForDisplayInfo(handler, EVENT_DISPLAY_INFO,
                mRegistrantidentifier);
        phone.registerForSignalInfo(handler, EVENT_SIGNAL_INFO,
                mRegistrantidentifier);
        phone.registerForResendIncallMute(handler, EVENT_RESEND_INCALL_MUTE,
                mRegistrantidentifier);
        phone.registerForMmiInitiate(handler, EVENT_MMI_INITIATE,
                mRegistrantidentifier);
        phone.registerForMmiComplete(handler, EVENT_MMI_COMPLETE,
                mRegistrantidentifier);
        phone.registerForSuppServiceFailed(handler, EVENT_SUPP_SERVICE_FAILED,
                mRegistrantidentifier);
        phone.registerForServiceStateChanged(handler, EVENT_SERVICE_STATE_CHANGED,
                mRegistrantidentifier);

        // for events supported only by GSM, CDMA and IMS phone
        phone.setOnPostDialCharacter(handler, EVENT_POST_DIAL_CHARACTER, null);

        // for events supported only by CDMA phone
        if (!Flags.deleteCdma()) {
            phone.registerForCdmaOtaStatusChange(handler, EVENT_CDMA_OTA_STATUS_CHANGE, null);
            phone.registerForSubscriptionInfoReady(handler, EVENT_SUBSCRIPTION_INFO_READY, null);
            phone.registerForCallWaiting(handler, EVENT_CALL_WAITING, null);
            phone.registerForEcmTimerReset(handler, EVENT_ECM_TIMER_RESET, null);
        }
        // for events supported only by IMS phone
        phone.registerForOnHoldTone(handler, EVENT_ONHOLD_TONE, null);
        phone.registerForSuppServiceFailed(handler, EVENT_SUPP_SERVICE_FAILED, null);
        phone.registerForTtyModeReceived(handler, EVENT_TTY_MODE_RECEIVED, null);
    }

    private void unregisterForPhoneStates(Phone phone) {
        // Make sure that we clean up our map of handlers to Phones.
        CallManagerHandler handler = mHandlerMap.get(phone);
        if (handler == null) {
            Rlog.e(LOG_TAG, "Could not find Phone handler for unregistration");
            return;
        }
        mHandlerMap.remove(phone);

        //  for common events supported by all phones
        phone.unregisterForPreciseCallStateChanged(handler);
        phone.unregisterForDisconnect(handler);
        phone.unregisterForNewRingingConnection(handler);
        phone.unregisterForUnknownConnection(handler);
        phone.unregisterForIncomingRing(handler);
        phone.unregisterForRingbackTone(handler);
        phone.unregisterForInCallVoicePrivacyOn(handler);
        phone.unregisterForInCallVoicePrivacyOff(handler);
        phone.unregisterForDisplayInfo(handler);
        phone.unregisterForSignalInfo(handler);
        phone.unregisterForResendIncallMute(handler);
        phone.unregisterForMmiInitiate(handler);
        phone.unregisterForMmiComplete(handler);
        phone.unregisterForSuppServiceFailed(handler);
        phone.unregisterForServiceStateChanged(handler);
        phone.unregisterForTtyModeReceived(handler);

        // for events supported only by GSM, CDMA and IMS phone
        phone.setOnPostDialCharacter(null, EVENT_POST_DIAL_CHARACTER, null);

        // for events supported only by CDMA phone
        if (!Flags.deleteCdma()) {
            phone.unregisterForCdmaOtaStatusChange(handler);
            phone.unregisterForSubscriptionInfoReady(handler);
            phone.unregisterForCallWaiting(handler);
            phone.unregisterForEcmTimerReset(handler);
        }

        // for events supported only by IMS phone
        phone.unregisterForOnHoldTone(handler);
        phone.unregisterForSuppServiceFailed(handler);
    }

    /**
     * Initiate a new voice connection. This happens asynchronously, so you
     * cannot assume the audio path is connected (or a call index has been
     * assigned) until PhoneStateChanged notification has occurred.
     *
     * @exception CallStateException if a new outgoing call is not currently
     * possible because no more call slots exist or a call exists that is
     * dialing, alerting, ringing, or waiting.  Other errors are
     * handled asynchronously.
     */
    public Connection dial(Phone phone, String dialString, PhoneInternalInterface.DialArgs dialArgs)
            throws CallStateException {
        int subId = phone.getSubId();
        Connection result;

        if (VDBG) {
            Rlog.d(LOG_TAG, " dial(" + phone + ", "+ dialString + ")" +
                    " subId = " + subId);
            Rlog.d(LOG_TAG, toString());
        }

        if (!canDial(phone)) {
            /*
             * canDial function only checks whether the phone can make a new call.
             * InCall MMI commmands are basically supplementary services
             * within a call eg: call hold, call deflection, explicit call transfer etc.
             */
            String newDialString = PhoneNumberUtils.stripSeparators(dialString);
            if (phone.handleInCallMmiCommands(newDialString)) {
                return null;
            } else {
                throw new CallStateException("cannot dial in current state");
            }
        }

        if ( hasActiveFgCall(subId) ) {
            Phone activePhone = getActiveFgCall(subId).getPhone();
            boolean hasBgCall = !(activePhone.getBackgroundCall().isIdle());

            if (DBG) {
                Rlog.d(LOG_TAG, "hasBgCall: "+ hasBgCall + " sameChannel:" + (activePhone == phone));
            }

            // Manipulation between IMS phone and its owner
            // will be treated in GSM/CDMA phone.
            Phone imsPhone = phone.getImsPhone();
            if (activePhone != phone
                    && (imsPhone == null || imsPhone != activePhone)) {
                if (hasBgCall) {
                    Rlog.d(LOG_TAG, "Hangup");
                    getActiveFgCall(subId).hangup();
                } else {
                    Rlog.d(LOG_TAG, "Switch");
                    activePhone.switchHoldingAndActive();
                }
            }
        }

        result = phone.dial(dialString, dialArgs);

        if (VDBG) {
            Rlog.d(LOG_TAG, "End dial(" + phone + ", "+ dialString + ")");
            Rlog.d(LOG_TAG, toString());
        }

        return result;
    }

    /**
     * Phone can make a call only if ALL of the following are true:
     *        - Phone is not powered off
     *        - There's no incoming or waiting call
     *        - The foreground call is ACTIVE or IDLE or DISCONNECTED.
     *          (We mainly need to make sure it *isn't* DIALING or ALERTING.)
     * @param phone
     * @return true if the phone can make a new call
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private boolean canDial(Phone phone) {
        int serviceState = phone.getServiceState().getState();
        int subId = phone.getSubId();
        boolean hasRingingCall = hasActiveRingingCall();
        Call.State fgCallState = getActiveFgCallState(subId);

        boolean result = (serviceState != ServiceState.STATE_POWER_OFF
                && !hasRingingCall
                && ((fgCallState == Call.State.ACTIVE)
                    || (fgCallState == Call.State.IDLE)
                    || (fgCallState == Call.State.DISCONNECTED)
                    /*As per 3GPP TS 51.010-1 section 31.13.1.4
                    call should be alowed when the foreground
                    call is in ALERTING state*/
                    || (fgCallState == Call.State.ALERTING)));

        if (result == false) {
            Rlog.d(LOG_TAG, "canDial serviceState=" + serviceState
                            + " hasRingingCall=" + hasRingingCall
                            + " fgCallState=" + fgCallState);
        }
        return result;
    }

    /**
     * Notifies when a voice connection has disconnected, either due to local
     * or remote hangup or error.
     *
     *  Messages received from this will have the following members:<p>
     *  <ul><li>Message.obj will be an AsyncResult</li>
     *  <li>AsyncResult.userObj = obj</li>
     *  <li>AsyncResult.result = a Connection object that is
     *  no longer connected.</li></ul>
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void registerForDisconnect(Handler h, int what, Object obj) {
        mDisconnectRegistrants.addUnique(h, what, obj);
    }

    /**
     * Register for notifications that an MMI request has completed
     * its network activity and is in its final state. This may mean a state
     * of COMPLETE, FAILED, or CANCELLED.
     *
     * <code>Message.obj</code> will contain an AsyncResult.
     * <code>obj.result</code> will be an "MmiCode" object
     */
    public void registerForMmiComplete(Handler h, int what, Object obj){
        Rlog.d(LOG_TAG, "registerForMmiComplete");
        mMmiCompleteRegistrants.addUnique(h, what, obj);
    }

    /**
     * Unregisters for MMI complete notification.
     * Extraneous calls are tolerated silently
     */
    public void unregisterForMmiComplete(Handler h){
        mMmiCompleteRegistrants.remove(h);
    }

    /**
     * Register for notifications when a supplementary service attempt fails.
     * Message.obj will contain an AsyncResult.
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForSuppServiceFailed(Handler h, int what, Object obj){
        mSuppServiceFailedRegistrants.addUnique(h, what, obj);
    }

    /**
     * Register for notifications when a sInCall VoicePrivacy is enabled
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForInCallVoicePrivacyOn(Handler h, int what, Object obj){
        mInCallVoicePrivacyOnRegistrants.addUnique(h, what, obj);
    }

    /**
     * Register for notifications when a sInCall VoicePrivacy is disabled
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForInCallVoicePrivacyOff(Handler h, int what, Object obj){
        mInCallVoicePrivacyOffRegistrants.addUnique(h, what, obj);
    }

    /**
     * Register for signal information notifications from the network.
     * Message.obj will contain an AsyncResult.
     * AsyncResult.result will be a SuppServiceNotification instance.
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */

    public void registerForSignalInfo(Handler h, int what, Object obj){
        mSignalInfoRegistrants.addUnique(h, what, obj);
    }

    /**
     * Register for display information notifications from the network.
     * Message.obj will contain an AsyncResult.
     * AsyncResult.result will be a SuppServiceNotification instance.
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForDisplayInfo(Handler h, int what, Object obj){
        mDisplayInfoRegistrants.addUnique(h, what, obj);
    }

    /**
     * Register for TTY mode change notifications from the network.
     * Message.obj will contain an AsyncResult.
     * AsyncResult.result will be an Integer containing new mode.
     *
     * @param h Handler that receives the notification message.
     * @param what User-defined message code.
     * @param obj User object.
     */
    public void registerForTtyModeReceived(Handler h, int what, Object obj){
        mTtyModeReceivedRegistrants.addUnique(h, what, obj);
    }

    /* APIs to access foregroudCalls, backgroudCalls, and ringingCalls
     * 1. APIs to access list of calls
     * 2. APIs to check if any active call, which has connection other than
     * disconnected ones, pleaser refer to Call.isIdle()
     * 3. APIs to return first active call
     * 4. APIs to return the connections of first active call
     * 5. APIs to return other property of first active call
     */

    /**
     * @return list of all ringing calls
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public List<Call> getRingingCalls() {
        return Collections.unmodifiableList(mRingingCalls);
    }

    /**
     * @return list of all foreground calls
     */
    public List<Call> getForegroundCalls() {
        return Collections.unmodifiableList(mForegroundCalls);
    }

    /**
     * @return list of all background calls
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public List<Call> getBackgroundCalls() {
        return Collections.unmodifiableList(mBackgroundCalls);
    }

    /**
     * Return true if there is at least one active foreground call
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean hasActiveFgCall() {
        return (getFirstActiveCall(mForegroundCalls) != null);
    }

    /**
     * Return true if there is at least one active foreground call on a particular subId
     */
    @UnsupportedAppUsage
    public boolean hasActiveFgCall(int subId) {
        return (getFirstActiveCall(mForegroundCalls, subId) != null);
    }

    /**
     * Return true if there is at least one active background call
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean hasActiveBgCall() {
        // TODO since hasActiveBgCall may get called often
        // better to cache it to improve performance
        return (getFirstActiveCall(mBackgroundCalls) != null);
    }

    /**
     * Return true if there is at least one active ringing call
     *
     */
    public boolean hasActiveRingingCall() {
        return (getFirstActiveCall(mRingingCalls) != null);
    }

    /**
     * return the active foreground call from foreground calls
     *
     * Active call means the call is NOT in Call.State.IDLE
     *
     * 1. If there is active foreground call, return it
     * 2. If there is no active foreground call, return the
     *    foreground call associated with default phone, which state is IDLE.
     * 3. If there is no phone registered at all, return null.
     *
     */
    public Call getActiveFgCall() {
        Call call = getFirstNonIdleCall(mForegroundCalls);
        if (call == null) {
            call = (mDefaultPhone == null)
                    ? null
                    : mDefaultPhone.getForegroundCall();
        }
        return call;
    }

    @UnsupportedAppUsage
    public Call getActiveFgCall(int subId) {
        Call call = getFirstNonIdleCall(mForegroundCalls, subId);
        if (call == null) {
            Phone phone = getPhone(subId);
            call = (phone == null)
                    ? null
                    : phone.getForegroundCall();
        }
        return call;
    }

    // Returns the first call that is not in IDLE state. If both active calls
    // and disconnecting/disconnected calls exist, return the first active call.
    private Call getFirstNonIdleCall(List<Call> calls) {
        Call result = null;
        for (Call call : calls) {
            if (!call.isIdle()) {
                return call;
            } else if (call.getState() != Call.State.IDLE) {
                if (result == null) result = call;
            }
        }
        return result;
    }

    // Returns the first call that is not in IDLE state. If both active calls
    // and disconnecting/disconnected calls exist, return the first active call.
    private Call getFirstNonIdleCall(List<Call> calls, int subId) {
        Call result = null;
        for (Call call : calls) {
            if (call.getPhone().getSubId() == subId) {
                if (!call.isIdle()) {
                    return call;
                } else if (call.getState() != Call.State.IDLE) {
                    if (result == null) result = call;
                }
            }
        }
        return result;
    }

    /**
     * return one active ringing call from ringing calls
     *
     * Active call means the call is NOT idle defined by Call.isIdle()
     *
     * 1. If there is only one active ringing call, return it
     * 2. If there is more than one active ringing call, return the first one
     * 3. If there is no active ringing call, return the ringing call
     *    associated with default phone, which state is IDLE.
     * 4. If there is no ringing call at all, return null.
     *
     * Complete ringing calls list can be get by getRingingCalls()
     */
    @UnsupportedAppUsage
    public Call getFirstActiveRingingCall() {
        Call call = getFirstNonIdleCall(mRingingCalls);
        if (call == null) {
            call = (mDefaultPhone == null)
                    ? null
                    : mDefaultPhone.getRingingCall();
        }
        return call;
    }

    @UnsupportedAppUsage
    public Call.State getActiveFgCallState(int subId) {
        Call fgCall = getActiveFgCall(subId);

        if (fgCall != null) {
            return fgCall.getState();
        }

        return Call.State.IDLE;
    }

    /**
     * @return the first active call from a call list
     */
    private  Call getFirstActiveCall(ArrayList<Call> calls) {
        for (Call call : calls) {
            if (!call.isIdle()) {
                return call;
            }
        }
        return null;
    }

    /**
     * @return the first active call from a call list
     */
    private  Call getFirstActiveCall(ArrayList<Call> calls, int subId) {
        for (Call call : calls) {
            if ((!call.isIdle()) && (call.getPhone().getSubId() == subId)) {
                return call;
            }
        }
        return null;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private boolean hasMoreThanOneRingingCall() {
        int count = 0;
        for (Call call : mRingingCalls) {
            if (call.getState().isRinging()) {
                if (++count > 1) return true;
            }
        }
        return false;
    }

    private class CallManagerHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {

            switch (msg.what) {
                case EVENT_DISCONNECT:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_DISCONNECT)");
                    mDisconnectRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_PRECISE_CALL_STATE_CHANGED:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_PRECISE_CALL_STATE_CHANGED)");
                    mPreciseCallStateRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_NEW_RINGING_CONNECTION:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_NEW_RINGING_CONNECTION)");
                    Connection c = (Connection) ((AsyncResult) msg.obj).result;
                    int subId = c.getCall().getPhone().getSubId();
                    boolean incomingRejected = false;
                    if ((c.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS)
                            && ((ImsPhoneConnection) c).isIncomingCallAutoRejected()) {
                        incomingRejected = true;
                    }
                    boolean hasMoreThanOneRingingCall = hasMoreThanOneRingingCall();
                    if (Flags.allowMultipleIncomingForDsda() && hasMoreThanOneRingingCall
                            && isSimultaneousCallingEnabled()) {
                        if (VDBG) Rlog.d(LOG_TAG, "Allow ringing call on other sub.");
                        // Allow pass through for DSDA. Let this be caught by Telecom instead so
                        // that the call can be logged as a missed call.
                        hasMoreThanOneRingingCall = false;
                    }

                    if ((getActiveFgCallState(subId).isDialing() || hasMoreThanOneRingingCall)
                            && (!incomingRejected)) {
                        try {
                            Rlog.d(LOG_TAG, "silently drop incoming call: " + c.getCall());
                            c.getCall().hangup();
                        } catch (CallStateException e) {
                            Rlog.w(LOG_TAG, "new ringing connection", e);
                        }
                    } else {
                        mNewRingingConnectionRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    }
                    break;
                case EVENT_UNKNOWN_CONNECTION:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_UNKNOWN_CONNECTION)");
                    mUnknownConnectionRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_INCOMING_RING:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_INCOMING_RING)");
                    // The event may come from RIL who's not aware of an ongoing fg call
                    if (!hasActiveFgCall()) {
                        mIncomingRingRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    }
                    break;
                case EVENT_RINGBACK_TONE:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_RINGBACK_TONE)");
                    mRingbackToneRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_IN_CALL_VOICE_PRIVACY_ON:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_IN_CALL_VOICE_PRIVACY_ON)");
                    mInCallVoicePrivacyOnRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_IN_CALL_VOICE_PRIVACY_OFF:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_IN_CALL_VOICE_PRIVACY_OFF)");
                    mInCallVoicePrivacyOffRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_CALL_WAITING:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_CALL_WAITING)");
                    mCallWaitingRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_DISPLAY_INFO:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_DISPLAY_INFO)");
                    mDisplayInfoRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_SIGNAL_INFO:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_SIGNAL_INFO)");
                    mSignalInfoRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_CDMA_OTA_STATUS_CHANGE:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_CDMA_OTA_STATUS_CHANGE)");
                    mCdmaOtaStatusChangeRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_RESEND_INCALL_MUTE:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_RESEND_INCALL_MUTE)");
                    mResendIncallMuteRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_MMI_INITIATE:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_MMI_INITIATE)");
                    mMmiInitiateRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_MMI_COMPLETE:
                    Rlog.d(LOG_TAG, "CallManager: handleMessage (EVENT_MMI_COMPLETE)");
                    mMmiCompleteRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_ECM_TIMER_RESET:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_ECM_TIMER_RESET)");
                    mEcmTimerResetRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_SUBSCRIPTION_INFO_READY:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_SUBSCRIPTION_INFO_READY)");
                    mSubscriptionInfoReadyRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_SUPP_SERVICE_FAILED:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_SUPP_SERVICE_FAILED)");
                    mSuppServiceFailedRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_SERVICE_STATE_CHANGED:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_SERVICE_STATE_CHANGED)");
                    mServiceStateChangedRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_POST_DIAL_CHARACTER:
                    // we need send the character that is being processed in msg.arg1
                    // so can't use notifyRegistrants()
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_POST_DIAL_CHARACTER)");
                    for(int i=0; i < mPostDialCharacterRegistrants.size(); i++) {
                        Message notifyMsg;
                        notifyMsg = ((Registrant)mPostDialCharacterRegistrants.get(i)).messageForRegistrant();
                        notifyMsg.obj = msg.obj;
                        notifyMsg.arg1 = msg.arg1;
                        notifyMsg.sendToTarget();
                    }
                    break;
                case EVENT_ONHOLD_TONE:
                    if (Flags.deleteCdma()) return;
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_ONHOLD_TONE)");
                    mOnHoldToneRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
                case EVENT_TTY_MODE_RECEIVED:
                    if (VDBG) Rlog.d(LOG_TAG, " handleMessage (EVENT_TTY_MODE_RECEIVED)");
                    mTtyModeReceivedRegistrants.notifyRegistrants((AsyncResult) msg.obj);
                    break;
            }
        }
    };

    private boolean isSimultaneousCallingEnabled() {
        try {
            return mTelephonyManager.getMaxNumberOfSimultaneouslyActiveSims() > 1
                    || mTelephonyManager.getPhoneCapability().getMaxActiveVoiceSubscriptions() > 1;
        } catch (UnsupportedOperationException uoe) {
            Rlog.d(LOG_TAG, "Telephony not supported");
            return false;
        } catch (Exception e) {
            Rlog.d(LOG_TAG, "exception in isSimultaneousCallingEnabled(): ", e);
            return false;
        }
    }
}
