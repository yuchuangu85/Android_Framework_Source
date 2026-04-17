/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.internal.telephony.cat;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.DisconnectCause;
import android.telephony.PreciseCallState;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsReasonInfo;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.PhoneInternalInterface.DialArgs;

/**
 * Class for handling SET UP CALL proactive UICC commands.
 */
public class SetUpCallCommandHandler extends Handler {
    private static final int WAIT_OTHER_CALLS_DISCONNECT_MILLIS = 2000;

    private final Runnable mDurationExceededRunnable = () -> onDurationExceeded();
    private final Handler mCaller;
    private final Context mContext;
    private final int mSubId;

    private CallStateCallback mCallback;
    private CatCmdMessage.CallSettings mSettings;
    private boolean mNeedRedial = false;

    public SetUpCallCommandHandler(
            Looper looper, Handler caller, Context context, int subId) {
        super(looper);

        mCaller = caller;
        mContext = context;
        mSubId = subId;
    }

    /**
     * Starts to make a call based on the given command settings.
     * Caller is notified with MSG_ID_NOTIFY_COMMAND_RESULT with the result code
     * when the command is terminated.
     *
     * @param settings Contains command parameters e.g. address, redial, duration, type.
     */
    public void start(CatCmdMessage.CallSettings settings) {
        if (!canDial(settings.setUpCallType)) {
            finishAndNotifyResult(ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS);
            return;
        }

        mSettings = settings;
        mCallback = new CallStateCallback();
        try {
            TelephonyManager.from(mContext).registerTelephonyCallback(super::post, mCallback);
        } catch (IllegalStateException e) {
            CatLog.e(this, "Failed to register TelephonyCallback: " + e.getMessage());
            mCallback = null;
            finishAndNotifyResult(ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS);
            return;
        }
        if (settings.redial) {
            startRedialTimer(settings.duration);
        }
        if (settings.setUpCallType == CatCmdMessage.SetUpCallType.DISCONNECT_ALL_OTHERS
                && TelephonyManager.from(mContext).getCallStateForSubscription()
                        != TelephonyManager.CALL_STATE_IDLE) {
            CatLog.d(this, "Wait for disconnecting other calls");
            disconnectAll();
            postDelayed(() -> dial(settings), WAIT_OTHER_CALLS_DISCONNECT_MILLIS);
        } else {
            dial(settings);
        }
    }

    private void dial(CatCmdMessage.CallSettings settings) {
        final PhoneAccountHandle handle = getPhoneAccountHandle();
        if (handle == null) {
            CatLog.e(this, "No PhoneAccountHandle found for subId: " + mSubId);
            finishAndNotifyResult(ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS);
            return;
        }

        final CallManager callManager = CallManager.getInstance(mContext);
        try {
            callManager.dial(callManager.getPhone(mSubId), settings.address,
                    new DialArgs.Builder<>()
                            // FDN check is skipped for STK initiated calls.
                            .setSkipFdnCheck(true)
                            .build());
        } catch (CallStateException e) {
            CatLog.e(this, e.getMessage());
            finishAndNotifyResult(ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS);
            return;
        }

        Bundle extras = new Bundle();
        extras.putString(TelecomManager.EXTRA_CALL_SUBJECT, settings.callMsg.text);
        mContext.getSystemService(TelecomManager.class).addNewUnknownCall(handle, extras);
    }

    private boolean canDial(CatCmdMessage.SetUpCallType cmdType) {
        final TelecomManager telecomManager = mContext.getSystemService(TelecomManager.class);
        final CallManager callManager = CallManager.getInstance(mContext);

        if (telecomManager.isInEmergencyCall()) {
            return false;
        }
        if (cmdType == CatCmdMessage.SetUpCallType.IF_NOT_BUSY && telecomManager.isInCall()) {
            return false;
        }
        if (cmdType == CatCmdMessage.SetUpCallType.PUT_OTHERS_HOLD
                && callManager.hasActiveFgCall() && callManager.hasActiveBgCall()) {
            return false;
        }
        return true;
    }

    private void disconnectAll() {
        final CallManager callManager = CallManager.getInstance(mContext);
        callManager.getForegroundCalls().stream().forEach(Call::hangupIfAlive);
        callManager.getBackgroundCalls().stream().forEach(Call::hangupIfAlive);
        callManager.getRingingCalls().stream().forEach(Call::hangupIfAlive);
    }

    private void startRedialTimer(Duration duration) {
        if (duration == null || duration.timeInterval <= 0) {
            return;
        }

        mNeedRedial = true;
        postDelayed(mDurationExceededRunnable, duration.getIntervalInMillis());
        CatLog.d(this, "Redial timer started for " + duration.getIntervalInMillis());
    }

    private void stopRedialTimer() {
        removeCallbacks(mDurationExceededRunnable);
        mNeedRedial = false;
    }

    private void onDurationExceeded() {
        CatLog.d(this, "Redial duration exceeded, stop redial attempts");
        mNeedRedial = false;
    }

    private void finishAndNotifyResult(ResultCode resultCode) {
        CatLog.d(this, "Finishing with result: " + resultCode);
        stopRedialTimer();
        if (mCallback != null) {
            TelephonyManager.from(mContext).unregisterTelephonyCallback(mCallback);
            mCallback = null;
        }

        mCaller.obtainMessage(CatService.MSG_ID_NOTIFY_COMMAND_RESULT, resultCode)
                .sendToTarget();
    }

    private PhoneAccountHandle getPhoneAccountHandle() {
        return TelephonyManager.from(mContext).getPhoneAccountHandleForSubscriptionId(mSubId);
    }

    private class CallStateCallback extends TelephonyCallback implements
            TelephonyCallback.PreciseCallStateListener,
            TelephonyCallback.CallDisconnectCauseListener,
            TelephonyCallback.ImsCallDisconnectCauseListener {
        private boolean mIsNewCallPlaced = false;

        @Override
        public void onPreciseCallStateChanged(PreciseCallState callState) {
            if (callState.getForegroundCallState() == PreciseCallState.PRECISE_CALL_STATE_DIALING) {
                mIsNewCallPlaced = true;
            }
            if (!mIsNewCallPlaced) {
                return;  // Ignore the existing call we haven't made.
            }

            if (callState.getForegroundCallState() == PreciseCallState.PRECISE_CALL_STATE_ACTIVE) {
                finishAndNotifyResult(ResultCode.OK);
            }
        }

        @Override
        public void onCallDisconnectCauseChanged(int disconnectCause, int preciseDisconnectCause) {
            if (!mIsNewCallPlaced
                    || disconnectCause == DisconnectCause.NOT_VALID
                    || disconnectCause == DisconnectCause.NOT_DISCONNECTED) {
                return;
            }

            CatLog.d(this, "onCallDisconnectCauseChanged: "
                    + disconnectCause + "/" + preciseDisconnectCause);
            handleDisconnect(disconnectCause == DisconnectCause.LOCAL);
        }

        @Override
        public void onImsCallDisconnectCauseChanged(ImsReasonInfo imsReasonInfo) {
            if (!mIsNewCallPlaced || imsReasonInfo == null) {
                return;
            }

            CatLog.d(this, "onImsCallDisconnectCauseChanged: " + imsReasonInfo);
            handleDisconnect(imsReasonInfo.getCode() == ImsReasonInfo.CODE_USER_TERMINATED);
        }

        private void redial() {
            CatLog.d(this, "Attempting redial");

            mIsNewCallPlaced = false;
            dial(mSettings);
        }

        private void handleDisconnect(boolean byUser) {
            if (byUser) {
                finishAndNotifyResult(ResultCode.USER_CLEAR_DOWN_CALL);
            } else if (mNeedRedial) {
                redial();
            } else {
                finishAndNotifyResult(ResultCode.NETWORK_CRNTLY_UNABLE_TO_PROCESS);
            }
        }
    };
}
