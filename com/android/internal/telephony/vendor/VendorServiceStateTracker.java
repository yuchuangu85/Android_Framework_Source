/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.telephony.vendor;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Message;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.GsmCdmaPhone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.ServiceStateTracker;

public class VendorServiceStateTracker extends ServiceStateTracker {
    private static final String LOG_TAG = "VendorServiceStateTracker";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;  // STOPSHIP if true
    private static final String ACTION_MANAGED_ROAMING_IND =
            "android.intent.action.ACTION_MANAGED_ROAMING_IND";

    public VendorServiceStateTracker(GsmCdmaPhone phone, CommandsInterface ci) {
        super(phone,ci);
    }

    @Override
    protected void handlePollStateResultMessage(int what, AsyncResult ar) {
        switch (what) {
            case EVENT_POLL_STATE_CS_CELLULAR_REGISTRATION: {
                super.handlePollStateResultMessage(what, ar);
                if (mPhone.isPhoneTypeGsm()) {
                    NetworkRegistrationInfo regStates = (NetworkRegistrationInfo) ar.result;
                    int regState = regStates.getRegistrationState();

                    if (regState == NetworkRegistrationInfo.REGISTRATION_STATE_DENIED) {
                        int rejCode = regStates.getRejectCause();
                        // Check if rejCode is "Persistent location update reject",
                        if (rejCode == 10) {
                            log(" Posting Managed roaming intent sub = "
                                    + mPhone.getSubId());
                            try {
                                Intent intent =
                                        new Intent(ACTION_MANAGED_ROAMING_IND);
                                // component would display Dialog to perform Manual scan
                                // if current Network selection Mode is Manual.
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY,
                                        mPhone.getSubId());
                                mPhone.getContext().startActivity(intent);
                            } catch (ActivityNotFoundException e) {
                                loge("unable to start activity: " + e);
                            }
                        }
                    }
                }
                break;
            }

            default:
                super.handlePollStateResultMessage(what, ar);
        }
    }

    @Override
    public void handleMessage(Message msg) {
        if (msg.what == EVENT_RADIO_STATE_CHANGED) {
            if (mPhone.mCi.getRadioState() == TelephonyManager.RADIO_POWER_OFF) {
                setPowerStateToDesired();
                log("Trigger as manual polling");
                pollState();
            } else {
                super.handleMessage(msg);
            }
        } else {
            super.handleMessage(msg);
        }
    }
}
