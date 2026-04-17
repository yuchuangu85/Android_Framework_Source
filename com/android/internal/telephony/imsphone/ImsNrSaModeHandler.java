/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.telephony.CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA;
import static android.telephony.CarrierConfigManager.Ims.KEY_NR_SA_DISABLE_POLICY_FOR_EMERGENCY_INT;
import static android.telephony.CarrierConfigManager.Ims.KEY_NR_SA_DISABLE_POLICY_INT;
import static android.telephony.CarrierConfigManager.Ims.NR_SA_DISABLE_POLICY_NONE;
import static android.telephony.CarrierConfigManager.Ims.NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED;
import static android.telephony.CarrierConfigManager.Ims.NR_SA_DISABLE_POLICY_WFC_ESTABLISHED;
import static android.telephony.CarrierConfigManager.Ims.NR_SA_DISABLE_POLICY_WFC_ESTABLISHED_WHEN_VONR_DISABLED;
import static android.telephony.CarrierConfigManager.Ims.NrSaDisablePolicy;
import static android.telephony.CarrierConfigManager.ImsWfc.KEY_EMERGENCY_CALL_OVER_EMERGENCY_PDN_BOOL;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY;
import static android.telephony.CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL;
import static android.telephony.ims.stub.ImsRegistrationImplBase.ImsRegistrationTech;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;

import static com.android.internal.telephony.CommandsInterface.IMS_MMTEL_CAPABILITY_VOICE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.Call;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Enables or Disables NR-SA mode temporarily under certain conditions where WFC is established or
 * IMS is registered over WiFi in order to improve the delay or voice mute issue when the handover
 * from ePDG to NR is not supported in UE or network.
 */
public class ImsNrSaModeHandler extends Handler {

    public static final String TAG = "ImsNrSaModeHandler";

    private static final int MSG_PRECISE_CALL_STATE_CHANGED = 101;
    private static final int MSG_RESULT_IS_VONR_ENABLED = 102;
    private static final int MSG_RESULT_SET_N1_MODE_ENABLED = 103;

    /**
     * Pure data class holding policy and state for a specific call category.
     */
    private static class NrSaDisableCriteria {
        private @NrSaDisablePolicy int mPolicy;
        private final boolean mIsEmergency;
        private boolean mIsWifiRegistered = false;
        private boolean mHasActiveImsCall = false;

        /** Constructor requested by user */
        NrSaDisableCriteria(@NrSaDisablePolicy int policy, boolean isEmergency) {
            mPolicy = policy;
            mIsEmergency = isEmergency;
        }

        /** Getters and Setters */
        public void setPolicy(int policy) {
            mPolicy = policy;
        }

        public int getPolicy() {
            return mPolicy;
        }

        public boolean isEmergency() {
            return mIsEmergency;
        }

        public void setWifiRegistered(boolean registered) {
            mIsWifiRegistered = registered;
        }

        public boolean isWifiRegistered() {
            return mIsWifiRegistered;
        }

        public void setActiveImsCall(boolean active) {
            mHasActiveImsCall = active;
        }

        public boolean hasActiveImsCall() {
            return mHasActiveImsCall;
        }

        @Override
        public String toString() {
            return "{policy=" + mPolicy + ", emergency=" + mIsEmergency + ", wifi="
                    + mIsWifiRegistered + ", active=" + mHasActiveImsCall + "}";
        }
    }

    private final @NonNull ImsPhone mPhone;
    private @Nullable CarrierConfigManager mCarrierConfigManager;

    private NrSaDisableCriteria mNormalCriteria = null;
    private NrSaDisableCriteria mEmergencyCriteria = null;

    // TODO(b/479137418): Consider state machine because there are too many boolean variables.
    private boolean mIsNrSaDisabledForWfc;
    private boolean mIsVoiceCapable;
    private boolean mIsWfcEmergencyOverEpdn;

    /** Flag indicating an asynchronous operation is in progress with the modem. */
    private boolean mIsWaitingResponseFromModem = false;
    /** Flag indicating a re-evaluation is needed after the current async operation completes. */
    private boolean mPendingReevaluationForModemResponse = false;

    private final CarrierConfigManager.CarrierConfigChangeListener mCarrierConfigChangeListener =
            (slotIndex, subId, carrierId, specificCarrierId) -> setNrSaDisablePolicy(subId);

    public ImsNrSaModeHandler(@NonNull ImsPhone phone, Looper looper) {
        super(looper);

        mPhone = phone;

        mCarrierConfigManager = (CarrierConfigManager) mPhone.getContext()
                .getSystemService(Context.CARRIER_CONFIG_SERVICE);
        registerForCarrierConfigChanges();
    }

    /**
     * Performs any cleanup required before the ImsNrSaModeHandler is destroyed.
     */
    public void tearDown() {
        unregisterForCarrierConfigChanges();
        unregisterForPreciseCallStateChanges();

        mNormalCriteria = null;
        mEmergencyCriteria = null;
        if (isNrSaDisabledForWfc()) {
            setNrSaMode(true);
        }
    }

    /**
     * Based on changed VoWiFi reg state and call state, handles NR SA mode if needed.
     * It is including handover case.
     *
     * @param imsRadioTech The current registered RAT.
     */
    public void onImsRegistered(@ImsRegistrationTech int imsRadioTech) {
        if (mNormalCriteria == null) {
            return;
        }

        Log.d(TAG, "onImsRegistered: ImsRegistrationTech = " + imsRadioTech);

        final boolean isNewWifiRegistered = imsRadioTech == REGISTRATION_TECH_IWLAN;
        if (mNormalCriteria.isWifiRegistered() != isNewWifiRegistered) {
            mNormalCriteria.setWifiRegistered(isNewWifiRegistered);

            if (!mIsWfcEmergencyOverEpdn && mEmergencyCriteria != null) {
                mEmergencyCriteria.setWifiRegistered(isNewWifiRegistered);
            }

            calculateAndControlNrSa();
        }
    }

    /**
     * Based on changed VoWiFi reg state and call state, handles NR SA mode if needed.
     *
     * @param imsRadioTech The current un-registered RAT.
     */
    public void onImsUnregistered(@ImsRegistrationTech int imsRadioTech) {
        if (mNormalCriteria == null
                || imsRadioTech != REGISTRATION_TECH_IWLAN || !mNormalCriteria.isWifiRegistered()) {
            return;
        }

        Log.d(TAG, "onImsUnregistered : ImsRegistrationTech = " + imsRadioTech);

        mNormalCriteria.setWifiRegistered(false);

        if (!mIsWfcEmergencyOverEpdn && mEmergencyCriteria != null) {
            mEmergencyCriteria.setWifiRegistered(false);
        }

        calculateAndControlNrSa();
    }

    /**
     * Based on changed VoWiFi reg state and call state for emergency, handles NR SA mode if needed.
     * It is including handover case.
     *
     * @param imsRadioTech The current registered RAT.
     */
    public void onImsEmergencyRegistered(@ImsRegistrationTech int imsRadioTech) {
        if (!mIsWfcEmergencyOverEpdn || mEmergencyCriteria == null) {
            return;
        }

        Log.d(TAG, "onImsEmergencyRegistered: ImsRegistrationTech = " + imsRadioTech);

        final boolean isNewWifiRegistered = imsRadioTech == REGISTRATION_TECH_IWLAN;
        if (mEmergencyCriteria.isWifiRegistered() != isNewWifiRegistered) {
            mEmergencyCriteria.setWifiRegistered(isNewWifiRegistered);
            calculateAndControlNrSa();
        }
    }

    /**
     * Based on changed VoWiFi reg state and call state for emergency, handles NR SA mode if needed.
     *
     * @param imsRadioTech The current un-registered RAT.
     */
    public void onImsEmergencyUnregistered(@ImsRegistrationTech int imsRadioTech) {
        if (!mIsWfcEmergencyOverEpdn || mEmergencyCriteria == null
                || imsRadioTech != REGISTRATION_TECH_IWLAN
                || !mEmergencyCriteria.isWifiRegistered()) {
            return;
        }

        Log.d(TAG, "onImsEmergencyUnregistered : ImsRegistrationTech = " + imsRadioTech);

        mEmergencyCriteria.setWifiRegistered(false);
        calculateAndControlNrSa();
    }

    /**
     * Based on changed precise call state and VoWiFi reg state, handles NR SA mode if needed.
     */
    public void onPreciseCallStateChanged() {
        ImsPhoneConnection fgConn = mPhone.getForegroundCall().getFirstConnection();
        ImsPhoneConnection bgConn = mPhone.getBackgroundCall().getFirstConnection();
        Log.d(TAG, "onPreciseCallStateChanged :  foreground state = "
                + mPhone.getForegroundCall().getState() + ", emergency = "
                + (fgConn != null ? fgConn.isEmergencyCall() : "NA")
                + ", background state = "
                + mPhone.getBackgroundCall().getState() + ", emergency = "
                + (bgConn != null ? bgConn.isEmergencyCall() : "NA"));

        boolean normalUpdated = isCallStateRequired(mNormalCriteria)
                && updateCallState(mNormalCriteria);
        boolean emergencyUpdated = isCallStateRequired(mEmergencyCriteria)
                && updateCallState(mEmergencyCriteria);

        if (normalUpdated || emergencyUpdated) {
            calculateAndControlNrSa();
        }
    }

    /**
     * Updates Capability.
     */
    public void updateImsCapability(int capabilities) {
        if (mNormalCriteria == null
                || mNormalCriteria.getPolicy() == NR_SA_DISABLE_POLICY_NONE) {
            return;
        }

        boolean isVoiceCapable = (IMS_MMTEL_CAPABILITY_VOICE & capabilities) != 0;
        if (mIsVoiceCapable != isVoiceCapable) {
            mIsVoiceCapable = isVoiceCapable;
            calculateAndControlNrSa();
        }
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_PRECISE_CALL_STATE_CHANGED :
                onPreciseCallStateChanged();
                break;
            case MSG_RESULT_IS_VONR_ENABLED :
                mIsWaitingResponseFromModem = false;

                try {
                    AsyncResult ar = (AsyncResult) msg.obj;
                    if (ar != null && ar.result != null && ar.result instanceof Boolean) {
                        boolean vonrEnabled = (Boolean) ar.result;
                        Log.d(TAG, "result of isVoNrEnabled = " + vonrEnabled);

                        if (!vonrEnabled) {
                            setNrSaMode(false);
                        }
                    } else {
                        Log.e(TAG, "isVoNrEnabled query failed : " + ar.exception);
                    }
                } finally {
                    handlePendingRequest();
                }

                break;
            case MSG_RESULT_SET_N1_MODE_ENABLED:
                mIsWaitingResponseFromModem = false;

                try {
                    AsyncResult ar = (AsyncResult) msg.obj;
                    if (ar != null && ar.userObj != null && ar.userObj instanceof Boolean
                            && ar.exception == null) {
                        boolean requestedOn = (Boolean) ar.userObj;
                        mIsNrSaDisabledForWfc = !requestedOn;
                        Log.d(TAG, "result of setN1ModeEnabled = " + requestedOn);
                    } else {
                        Log.e(TAG, "setN1ModeEnabled request failed : " + ar.exception);
                    }
                } finally {
                    handlePendingRequest();
                }

                break;
            default :
                break;
        }
    }

    @VisibleForTesting
    public boolean isNrSaDisabledForWfc() {
        return mIsNrSaDisabledForWfc;
    }

    /**
     * Registers for precise call state changes.
     */
    private void registerForPreciseCallStateChanges() {
        mPhone.registerForPreciseCallStateChanged(this, MSG_PRECISE_CALL_STATE_CHANGED, null);
    }

    /**
     * Unregisters for precise call state changes.
     */
    private void unregisterForPreciseCallStateChanges() {
        mPhone.unregisterForPreciseCallStateChanged(this);
    }

    /**
     * Registers for carrier config changes.
     */
    private void registerForCarrierConfigChanges() {
        if (mCarrierConfigManager != null) {
            mCarrierConfigManager.registerCarrierConfigChangeListener(
                    this::post, mCarrierConfigChangeListener);
        }
    }

    /**
     * Unregisters for carrier config changes.
     */
    private void unregisterForCarrierConfigChanges() {
        if (mCarrierConfigManager != null) {
            mCarrierConfigManager.unregisterCarrierConfigChangeListener(
                    mCarrierConfigChangeListener);
        }
    }

    private void setNrSaDisablePolicy(int subId) {
        if (mPhone.getSubId() == subId && mCarrierConfigManager != null) {
            PersistableBundle bundle = mCarrierConfigManager.getConfigForSubId(mPhone.getSubId(),
                    KEY_NR_SA_DISABLE_POLICY_INT, KEY_NR_SA_DISABLE_POLICY_FOR_EMERGENCY_INT,
                    KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY,
                    KEY_EMERGENCY_CALL_OVER_EMERGENCY_PDN_BOOL,
                    KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL);
            int[] nrAvailabilities = bundle.getIntArray(KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY);
            boolean isNrSaSupported = nrAvailabilities != null
                    && Arrays.stream(nrAvailabilities).anyMatch(
                            value -> value == CARRIER_NR_AVAILABILITY_SA);
            boolean isWfcAvailable = bundle.getBoolean(KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL);

            if (!isNrSaSupported || !isWfcAvailable) {
                mNormalCriteria = null;
                mEmergencyCriteria = null;
                unregisterForPreciseCallStateChanges();
                return;
            }

            int normalPolicy = bundle.getInt(KEY_NR_SA_DISABLE_POLICY_INT,
                    NR_SA_DISABLE_POLICY_NONE);
            int emergencyPolicy = bundle.getInt(KEY_NR_SA_DISABLE_POLICY_FOR_EMERGENCY_INT,
                    NR_SA_DISABLE_POLICY_NONE);
            mIsWfcEmergencyOverEpdn = bundle.getBoolean(KEY_EMERGENCY_CALL_OVER_EMERGENCY_PDN_BOOL);

            Log.d(TAG, "setNrSaDisablePolicy : normalPolicy = " + normalPolicy
                    + ", emergencyPolicy = " + emergencyPolicy
                    + ", mIsWfcEmergencyOverEpdn = " + mIsWfcEmergencyOverEpdn);

            if (normalPolicy != NR_SA_DISABLE_POLICY_NONE) {
                if (mNormalCriteria == null) {
                    mNormalCriteria = new NrSaDisableCriteria(normalPolicy, false);
                } else {
                    mNormalCriteria.setPolicy(normalPolicy);
                }
            } else {
                mNormalCriteria = null;
            }

            if (emergencyPolicy != NR_SA_DISABLE_POLICY_NONE) {
                if (mEmergencyCriteria == null) {
                    mEmergencyCriteria = new NrSaDisableCriteria(emergencyPolicy, true);
                } else {
                    mEmergencyCriteria.setPolicy(emergencyPolicy);
                }

                if (!mIsWfcEmergencyOverEpdn && mNormalCriteria == null) {
                    mNormalCriteria = new NrSaDisableCriteria(NR_SA_DISABLE_POLICY_NONE, false);
                }
            } else {
                mEmergencyCriteria = null;
            }


            if (isCallStateRequired(mNormalCriteria) || isCallStateRequired(mEmergencyCriteria)) {
                registerForPreciseCallStateChanges();
            } else {
                unregisterForPreciseCallStateChanges();
            }
        }
    }

    private void setNrSaMode(boolean enable) {
        if (!enable == isNrSaDisabledForWfc()) {
            Log.i(TAG, "It is already in that state [" + enable + "]");
            return;
        }

        mIsWaitingResponseFromModem = true;
        mPhone.getDefaultPhone().setN1ModeEnabled(
                enable, obtainMessage(MSG_RESULT_SET_N1_MODE_ENABLED, enable));
        Log.i(TAG, "try setNrSaMode : " + enable);
    }

    private boolean updateCallState(@NonNull NrSaDisableCriteria criteria) {
        boolean updated = false;
        ImsPhoneCall[] calls = {mPhone.getForegroundCall(), mPhone.getBackgroundCall()};

        if (criteria.hasActiveImsCall()) {
            boolean anyRelevantCallActive = false;
            for (ImsPhoneCall call : calls) {
                ImsPhoneConnection conn = call.getFirstConnection();
                if (conn != null && (conn.getState() == Call.State.ACTIVE
                        || conn.getState() == Call.State.HOLDING)) {
                    if (criteria.isEmergency() == conn.isEmergencyCall()) {
                        anyRelevantCallActive = true;
                    }
                }
            }

            if (!anyRelevantCallActive) {
                criteria.setActiveImsCall(false);
                updated = true;
            }
        } else {
            for (ImsPhoneCall call : calls) {
                ImsPhoneConnection conn = call.getFirstConnection();
                if (conn != null && conn.getState() == Call.State.ACTIVE) {
                    if (criteria.isEmergency() == conn.isEmergencyCall()) {
                        criteria.setActiveImsCall(true);
                        updated = true;
                        break;
                    }
                }
            }
        }

        return updated;
    }

    /**
     * Evaluates the current state of both normal and emergency criteria and
     * controls NR SA mode based on carrier-defined policies.
     */
    private void calculateAndControlNrSa() {
        if (mIsWaitingResponseFromModem) {
            mPendingReevaluationForModemResponse = true;
            Log.d(TAG, "calculateAndControlNrSa: waiting for async result, set pending flag");
            return;
        }

        List<Integer> policiesToDisable = new ArrayList<>();

        // 1. Check if NR SA needs to be disabled for Normal criteria
        if (mNormalCriteria != null && isNrSaDisableNeeded(mNormalCriteria)) {
            policiesToDisable.add(mNormalCriteria.getPolicy());
        }

        // 2. Check if NR SA needs to be disabled for Emergency criteria
        if (mEmergencyCriteria != null && isNrSaDisableNeeded(mEmergencyCriteria)) {
            policiesToDisable.add(mEmergencyCriteria.getPolicy());
        }

        // 3. If there are policies requiring NR SA to be disabled
        if (!policiesToDisable.isEmpty()) {
            // Sort policies by priority:
            // VOWIFI_REGISTERED (Highest) > WFC_ESTABLISHED > VONR_DISABLED (Lowest)
            policiesToDisable.sort(Comparator.comparingInt(ImsNrSaModeHandler::getPolicyPriority));

            // Select the policy with the highest priority (lowest integer from getPolicyPriority)
            int selectedPolicy = policiesToDisable.get(0);
            Log.d(TAG, "calculateAndControlNrSa: selectedPolicy = " + selectedPolicy);

            if (selectedPolicy == NR_SA_DISABLE_POLICY_WFC_ESTABLISHED_WHEN_VONR_DISABLED) {
                mIsWaitingResponseFromModem = true;
                // For policies dependent on VoNR status, perform an asynchronous query to the modem
                mPhone.getDefaultPhone().isVoNrEnabled(
                        obtainMessage(MSG_RESULT_IS_VONR_ENABLED), null);
            } else {
                // For other policies, disable NR SA immediately
                setNrSaMode(false); // Trigger NR SA disabl
            }
        } else {
            // 4. If no policies require disabling, attempt to re-enable NR SA
            setNrSaMode(true); // Trigger NR SA enable
        }
    }

    private void handlePendingRequest() {
        if (mPendingReevaluationForModemResponse) {
            mPendingReevaluationForModemResponse = false;
            Log.d(TAG, "handlePendingRequest: processing deferred re-evaluation");
            calculateAndControlNrSa();
        }
    }

    private static boolean isCallStateRequired(@NonNull NrSaDisableCriteria criteria) {
        if (criteria != null && (criteria.getPolicy() == NR_SA_DISABLE_POLICY_WFC_ESTABLISHED
                || criteria.getPolicy()
                == NR_SA_DISABLE_POLICY_WFC_ESTABLISHED_WHEN_VONR_DISABLED)) {
            return true;
        }

        return false;
    }

    private static int getPolicyPriority(int policy) {
        switch (policy) {
            case NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED: return 1;
            case NR_SA_DISABLE_POLICY_WFC_ESTABLISHED: return 2;
            case NR_SA_DISABLE_POLICY_WFC_ESTABLISHED_WHEN_VONR_DISABLED: return 3;
            default: return 999;
        }
    }

    /**
     * Determines if the specific criteria currently meets the conditions
     * required by its assigned policy to disable NR SA.
     */
    private boolean isNrSaDisableNeeded(@NonNull NrSaDisableCriteria criteria) {
        // If the criteria is for emergency purposes, skip checking mIsVoiceCapable.
        // For normal criteria, mIsVoiceCapable must be true to trigger the disable logic.
        boolean isVoiceCapable = criteria.isEmergency() || mIsVoiceCapable;

        switch (criteria.getPolicy()) {
            case NR_SA_DISABLE_POLICY_VOWIFI_REGISTERED:
                return criteria.isWifiRegistered() && isVoiceCapable;
            case NR_SA_DISABLE_POLICY_WFC_ESTABLISHED:
            case NR_SA_DISABLE_POLICY_WFC_ESTABLISHED_WHEN_VONR_DISABLED:
                return criteria.isWifiRegistered() && isVoiceCapable && criteria.hasActiveImsCall();
            default:
                return false;
        }
    }
}
