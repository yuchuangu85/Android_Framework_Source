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

import android.annotation.UnsupportedAppUsage;
import android.telephony.CallQuality;
import android.telephony.CellInfo;
import android.telephony.CellLocation;
import android.telephony.DataFailCause;
import android.telephony.PhoneCapability;
import android.telephony.PhysicalChannelConfig;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsReasonInfo;

import java.util.List;

/**
 * {@hide}
 */
public interface PhoneNotifier {

    void notifyPhoneState(Phone sender);

    void notifyServiceState(Phone sender);

    /** Notify registrants of the current CellLocation */
    void notifyCellLocation(Phone sender, CellLocation cl);

    @UnsupportedAppUsage
    void notifySignalStrength(Phone sender);

    @UnsupportedAppUsage
    void notifyMessageWaitingChanged(Phone sender);

    void notifyCallForwardingChanged(Phone sender);

    void notifyDataConnection(Phone sender, String apnType, PhoneConstants.DataState state);

    void notifyDataConnectionFailed(Phone sender, String apnType);

    void notifyDataActivity(Phone sender);

    void notifyOtaspChanged(Phone sender, int otaspMode);

    void notifyCellInfo(Phone sender, List<CellInfo> cellInfo);

    /** Notify of change to PhysicalChannelConfiguration. */
    void notifyPhysicalChannelConfiguration(Phone sender, List<PhysicalChannelConfig> configs);

    void notifyPreciseCallState(Phone sender);

    void notifyDisconnectCause(Phone sender, int cause, int preciseCause);

    void notifyImsDisconnectCause(Phone sender, ImsReasonInfo imsReasonInfo);

    public void notifyPreciseDataConnectionFailed(Phone sender, String apnType, String apn,
                                                  @DataFailCause.FailCause int failCause);

    /** send a notification that the SRVCC state has changed.*/
    void notifySrvccStateChanged(Phone sender, @TelephonyManager.SrvccState int state);

    public void notifyVoiceActivationStateChanged(Phone sender, int activationState);

    public void notifyDataActivationStateChanged(Phone sender, int activationState);

    public void notifyUserMobileDataStateChanged(Phone sender, boolean state);

    public void notifyOemHookRawEventForSubscriber(Phone sender, byte[] rawData);

    public void notifyPhoneCapabilityChanged(PhoneCapability capability);

    void notifyRadioPowerStateChanged(Phone sender, @TelephonyManager.RadioPowerState int state);

    /** Notify of change to EmergencyNumberList. */
    void notifyEmergencyNumberList(Phone sender);

    /** Notify of a change to the call quality of an active foreground call. */
    void notifyCallQualityChanged(Phone sender, CallQuality callQuality, int callNetworkType);
}
