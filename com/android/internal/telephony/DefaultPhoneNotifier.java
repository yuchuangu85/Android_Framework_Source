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

import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.CellInfo;
import android.telephony.PhysicalChannelConfig;
import android.telephony.PreciseCallState;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.VoLteServiceState;

import java.util.List;

/**
 * broadcast intents
 */
public class DefaultPhoneNotifier implements PhoneNotifier {
    private static final String LOG_TAG = "DefaultPhoneNotifier";
    private static final boolean DBG = false; // STOPSHIP if true

    protected ITelephonyRegistry mRegistry;

    public DefaultPhoneNotifier() {
        mRegistry = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                    "telephony.registry"));
    }

    @Override
    public void notifyPhoneState(Phone sender) {
        Call ringingCall = sender.getRingingCall();
        int subId = sender.getSubId();
        int phoneId = sender.getPhoneId();
        String incomingNumber = "";
        if (ringingCall != null && ringingCall.getEarliestConnection() != null) {
            incomingNumber = ringingCall.getEarliestConnection().getAddress();
        }
        try {
            if (mRegistry != null) {
                  mRegistry.notifyCallStateForPhoneId(phoneId, subId,
                        PhoneConstantConversions.convertCallState(
                            sender.getState()), incomingNumber);
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    @Override
    public void notifyServiceState(Phone sender) {
        ServiceState ss = sender.getServiceState();
        int phoneId = sender.getPhoneId();
        int subId = sender.getSubId();

        Rlog.d(LOG_TAG, "nofityServiceState: mRegistry=" + mRegistry + " ss=" + ss
                + " sender=" + sender + " phondId=" + phoneId + " subId=" + subId);
        if (ss == null) {
            ss = new ServiceState();
            ss.setStateOutOfService();
        }
        try {
            if (mRegistry != null) {
                mRegistry.notifyServiceStateForPhoneId(phoneId, subId, ss);
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    @Override
    public void notifySignalStrength(Phone sender) {
        int phoneId = sender.getPhoneId();
        int subId = sender.getSubId();
        if (DBG) {
            // too chatty to log constantly
            Rlog.d(LOG_TAG, "notifySignalStrength: mRegistry=" + mRegistry
                    + " ss=" + sender.getSignalStrength() + " sender=" + sender);
        }
        try {
            if (mRegistry != null) {
                mRegistry.notifySignalStrengthForPhoneId(phoneId, subId,
                        sender.getSignalStrength());
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    @Override
    public void notifyMessageWaitingChanged(Phone sender) {
        int phoneId = sender.getPhoneId();
        int subId = sender.getSubId();

        try {
            if (mRegistry != null) {
                mRegistry.notifyMessageWaitingChangedForPhoneId(phoneId, subId,
                        sender.getMessageWaitingIndicator());
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    @Override
    public void notifyCallForwardingChanged(Phone sender) {
        int subId = sender.getSubId();
        try {
            if (mRegistry != null) {
                Rlog.d(LOG_TAG, "notifyCallForwardingChanged: subId=" + subId + ", isCFActive="
                        + sender.getCallForwardingIndicator());

                mRegistry.notifyCallForwardingChangedForSubscriber(subId,
                        sender.getCallForwardingIndicator());
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    @Override
    public void notifyDataActivity(Phone sender) {
        int subId = sender.getSubId();
        try {
            if (mRegistry != null) {
                mRegistry.notifyDataActivityForSubscriber(subId,
                        convertDataActivityState(sender.getDataActivityState()));
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    @Override
    public void notifyDataConnection(Phone sender, String reason, String apnType,
            PhoneConstants.DataState state) {
        doNotifyDataConnection(sender, reason, apnType, state);
    }

    private void doNotifyDataConnection(Phone sender, String reason, String apnType,
            PhoneConstants.DataState state) {
        int subId = sender.getSubId();
        long dds = SubscriptionManager.getDefaultDataSubscriptionId();
        if (DBG) log("subId = " + subId + ", DDS = " + dds);

        // TODO
        // use apnType as the key to which connection we're talking about.
        // pass apnType back up to fetch particular for this one.
        TelephonyManager telephony = TelephonyManager.getDefault();
        LinkProperties linkProperties = null;
        NetworkCapabilities networkCapabilities = null;
        boolean roaming = false;

        if (state == PhoneConstants.DataState.CONNECTED) {
            linkProperties = sender.getLinkProperties(apnType);
            networkCapabilities = sender.getNetworkCapabilities(apnType);
        }
        ServiceState ss = sender.getServiceState();
        if (ss != null) roaming = ss.getDataRoaming();

        try {
            if (mRegistry != null) {
                mRegistry.notifyDataConnectionForSubscriber(subId,
                    PhoneConstantConversions.convertDataState(state),
                        sender.isDataAllowed(), reason,
                        sender.getActiveApnHost(apnType),
                        apnType,
                        linkProperties,
                        networkCapabilities,
                        ((telephony != null) ? telephony.getDataNetworkType(subId) :
                                TelephonyManager.NETWORK_TYPE_UNKNOWN), roaming);
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    @Override
    public void notifyDataConnectionFailed(Phone sender, String reason, String apnType) {
        int subId = sender.getSubId();
        try {
            if (mRegistry != null) {
                mRegistry.notifyDataConnectionFailedForSubscriber(subId, reason, apnType);
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    @Override
    public void notifyCellLocation(Phone sender) {
        int subId = sender.getSubId();
        Bundle data = new Bundle();
        sender.getCellLocation().fillInNotifierBundle(data);
        try {
            if (mRegistry != null) {
                mRegistry.notifyCellLocationForSubscriber(subId, data);
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    @Override
    public void notifyCellInfo(Phone sender, List<CellInfo> cellInfo) {
        int subId = sender.getSubId();
        try {
            if (mRegistry != null) {
                mRegistry.notifyCellInfoForSubscriber(subId, cellInfo);
            }
        } catch (RemoteException ex) {

        }
    }

    @Override
    public void notifyPhysicalChannelConfiguration(Phone sender,
            List<PhysicalChannelConfig> configs) {
        int subId = sender.getSubId();
        try {
            if (mRegistry != null) {
                mRegistry.notifyPhysicalChannelConfigurationForSubscriber(subId, configs);
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    @Override
    public void notifyOtaspChanged(Phone sender, int otaspMode) {
        // FIXME: subId?
        try {
            if (mRegistry != null) {
                mRegistry.notifyOtaspChanged(otaspMode);
            }
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyPreciseCallState(Phone sender) {
        // FIXME: subId?
        Call ringingCall = sender.getRingingCall();
        Call foregroundCall = sender.getForegroundCall();
        Call backgroundCall = sender.getBackgroundCall();
        if (ringingCall != null && foregroundCall != null && backgroundCall != null) {
            try {
                mRegistry.notifyPreciseCallState(
                        convertPreciseCallState(ringingCall.getState()),
                        convertPreciseCallState(foregroundCall.getState()),
                        convertPreciseCallState(backgroundCall.getState()));
            } catch (RemoteException ex) {
                // system process is dead
            }
        }
    }

    public void notifyDisconnectCause(int cause, int preciseCause) {
        // FIXME: subId?
        try {
            mRegistry.notifyDisconnectCause(cause, preciseCause);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    public void notifyPreciseDataConnectionFailed(Phone sender, String reason, String apnType,
            String apn, String failCause) {
        // FIXME: subId?
        try {
            mRegistry.notifyPreciseDataConnectionFailed(reason, apnType, apn, failCause);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    @Override
    public void notifyVoLteServiceStateChanged(Phone sender, VoLteServiceState lteState) {
        // FIXME: subID
        try {
            mRegistry.notifyVoLteServiceStateChanged(lteState);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    @Override
    public void notifyDataActivationStateChanged(Phone sender, int activationState) {
        try {
            mRegistry.notifySimActivationStateChangedForPhoneId(sender.getPhoneId(),
                    sender.getSubId(), PhoneConstants.SIM_ACTIVATION_TYPE_DATA, activationState);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    @Override
    public void notifyVoiceActivationStateChanged(Phone sender, int activationState) {
        try {
            mRegistry.notifySimActivationStateChangedForPhoneId(sender.getPhoneId(),
                    sender.getSubId(), PhoneConstants.SIM_ACTIVATION_TYPE_VOICE, activationState);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    @Override
    public void notifyUserMobileDataStateChanged(Phone sender, boolean state) {
        try {
            mRegistry.notifyUserMobileDataStateChangedForPhoneId(
                    sender.getPhoneId(), sender.getSubId(), state);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    @Override
    public void notifyOemHookRawEventForSubscriber(int subId, byte[] rawData) {
        try {
            mRegistry.notifyOemHookRawEventForSubscriber(subId, rawData);
        } catch (RemoteException ex) {
            // system process is dead
        }
    }

    /**
     * Convert the {@link Phone.DataActivityState} enum into the TelephonyManager.DATA_* constants
     * for the public API.
     */
    public static int convertDataActivityState(Phone.DataActivityState state) {
        switch (state) {
            case DATAIN:
                return TelephonyManager.DATA_ACTIVITY_IN;
            case DATAOUT:
                return TelephonyManager.DATA_ACTIVITY_OUT;
            case DATAINANDOUT:
                return TelephonyManager.DATA_ACTIVITY_INOUT;
            case DORMANT:
                return TelephonyManager.DATA_ACTIVITY_DORMANT;
            default:
                return TelephonyManager.DATA_ACTIVITY_NONE;
        }
    }

    /**
     * Convert the {@link Call.State} enum into the PreciseCallState.PRECISE_CALL_STATE_* constants
     * for the public API.
     */
    public static int convertPreciseCallState(Call.State state) {
        switch (state) {
            case ACTIVE:
                return PreciseCallState.PRECISE_CALL_STATE_ACTIVE;
            case HOLDING:
                return PreciseCallState.PRECISE_CALL_STATE_HOLDING;
            case DIALING:
                return PreciseCallState.PRECISE_CALL_STATE_DIALING;
            case ALERTING:
                return PreciseCallState.PRECISE_CALL_STATE_ALERTING;
            case INCOMING:
                return PreciseCallState.PRECISE_CALL_STATE_INCOMING;
            case WAITING:
                return PreciseCallState.PRECISE_CALL_STATE_WAITING;
            case DISCONNECTED:
                return PreciseCallState.PRECISE_CALL_STATE_DISCONNECTED;
            case DISCONNECTING:
                return PreciseCallState.PRECISE_CALL_STATE_DISCONNECTING;
            default:
                return PreciseCallState.PRECISE_CALL_STATE_IDLE;
        }
    }

    private void log(String s) {
        Rlog.d(LOG_TAG, s);
    }
}
