/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.hardware.radio.V1_0.RadioError;
import android.hardware.radio.V1_0.RadioResponseInfo;
import android.hardware.radio.config.V1_1.ModemsConfig;
import android.hardware.radio.config.V1_2.IRadioConfigResponse;
import android.telephony.ModemInfo;
import android.telephony.PhoneCapability;
import com.android.telephony.Rlog;

import com.android.internal.telephony.uicc.IccSlotStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * This class is the implementation of IRadioConfigResponse interface.
 */
public class RadioConfigResponse extends IRadioConfigResponse.Stub {
    private static final String TAG = "RadioConfigResponse";

    private final RadioConfig mRadioConfig;

    public RadioConfigResponse(RadioConfig radioConfig) {
        mRadioConfig = radioConfig;
    }

    /**
     * Response function for IRadioConfig.getSimSlotsStatus().
     */
    public void getSimSlotsStatusResponse(RadioResponseInfo responseInfo,
            ArrayList<android.hardware.radio.config.V1_0.SimSlotStatus> slotStatus) {
        RILRequest rr = mRadioConfig.processResponse(responseInfo);

        if (rr != null) {
            ArrayList<IccSlotStatus> ret = RadioConfig.convertHalSlotStatus(slotStatus);
            if (responseInfo.error == RadioError.NONE) {
                // send response
                RadioResponse.sendMessageResponse(rr.mResult, ret);
                Rlog.d(TAG, rr.serialString() + "< "
                        + mRadioConfig.requestToString(rr.mRequest) + " " + ret.toString());
            } else {
                rr.onError(responseInfo.error, ret);
                Rlog.e(TAG, rr.serialString() + "< "
                        + mRadioConfig.requestToString(rr.mRequest) + " error "
                        + responseInfo.error);
            }

        } else {
            Rlog.e(TAG, "getSimSlotsStatusResponse: Error " + responseInfo.toString());
        }
    }

    /**
     * Response function for IRadioConfig.getSimSlotsStatus().
     */
    public void getSimSlotsStatusResponse_1_2(RadioResponseInfo responseInfo,
            ArrayList<android.hardware.radio.config.V1_2.SimSlotStatus> slotStatus) {
        RILRequest rr = mRadioConfig.processResponse(responseInfo);

        if (rr != null) {
            ArrayList<IccSlotStatus> ret = RadioConfig.convertHalSlotStatus_1_2(slotStatus);
            if (responseInfo.error == RadioError.NONE) {
                // send response
                RadioResponse.sendMessageResponse(rr.mResult, ret);
                Rlog.d(TAG, rr.serialString() + "< "
                        + mRadioConfig.requestToString(rr.mRequest) + " " + ret.toString());
            } else {
                rr.onError(responseInfo.error, ret);
                Rlog.e(TAG, rr.serialString() + "< "
                        + mRadioConfig.requestToString(rr.mRequest) + " error "
                        + responseInfo.error);
            }
        } else {
            Rlog.e(TAG, "getSimSlotsStatusResponse_1_2: Error " + responseInfo.toString());
        }
    }

    /**
     * Response function for IRadioConfig.setSimSlotsMapping().
     */
    public void setSimSlotsMappingResponse(RadioResponseInfo responseInfo) {
        RILRequest rr = mRadioConfig.processResponse(responseInfo);

        if (rr != null) {
            if (responseInfo.error == RadioError.NONE) {
                // send response
                RadioResponse.sendMessageResponse(rr.mResult, null);
                Rlog.d(TAG, rr.serialString() + "< "
                        + mRadioConfig.requestToString(rr.mRequest));
            } else {
                rr.onError(responseInfo.error, null);
                Rlog.e(TAG, rr.serialString() + "< "
                        + mRadioConfig.requestToString(rr.mRequest) + " error "
                        + responseInfo.error);
            }
        } else {
            Rlog.e(TAG, "setSimSlotsMappingResponse: Error " + responseInfo.toString());
        }
    }

    private PhoneCapability convertHalPhoneCapability(
            android.hardware.radio.config.V1_1.PhoneCapability phoneCapability) {
        // TODO b/121394331: clean up V1_1.PhoneCapability fields.
        int maxActiveVoiceCalls = 0;
        int maxActiveData = phoneCapability.maxActiveData;
        int max5G = 0;
        boolean validationBeforeSwitchSupported = phoneCapability.isInternetLingeringSupported;
        List<ModemInfo> logicalModemList = new ArrayList();

        for (android.hardware.radio.config.V1_1.ModemInfo
                modemInfo : phoneCapability.logicalModemList) {
            logicalModemList.add(new ModemInfo(modemInfo.modemId));
        }

        return new PhoneCapability(maxActiveVoiceCalls, maxActiveData, max5G, logicalModemList,
                validationBeforeSwitchSupported);
    }
    /**
     * Response function for IRadioConfig.getPhoneCapability().
     */
    public void getPhoneCapabilityResponse(RadioResponseInfo responseInfo,
            android.hardware.radio.config.V1_1.PhoneCapability phoneCapability) {
        RILRequest rr = mRadioConfig.processResponse(responseInfo);

        if (rr != null) {
            PhoneCapability ret = convertHalPhoneCapability(phoneCapability);
            if (responseInfo.error == RadioError.NONE) {
                // send response
                RadioResponse.sendMessageResponse(rr.mResult, ret);
                Rlog.d(TAG, rr.serialString() + "< "
                        + mRadioConfig.requestToString(rr.mRequest) + " " + ret.toString());
            } else {
                rr.onError(responseInfo.error, ret);
                Rlog.e(TAG, rr.serialString() + "< "
                        + mRadioConfig.requestToString(rr.mRequest) + " error "
                        + responseInfo.error);
            }
        } else {
            Rlog.e(TAG, "getPhoneCapabilityResponse: Error " + responseInfo.toString());
        }
    }

    /**
     * Response function for IRadioConfig.setPreferredDataModem().
     */
    public void setPreferredDataModemResponse(RadioResponseInfo responseInfo) {
        RILRequest rr = mRadioConfig.processResponse(responseInfo);

        if (rr != null) {
            if (responseInfo.error == RadioError.NONE) {
                // send response
                RadioResponse.sendMessageResponse(rr.mResult, null);
                Rlog.d(TAG, rr.serialString() + "< "
                        + mRadioConfig.requestToString(rr.mRequest));
            } else {
                rr.onError(responseInfo.error, null);
                Rlog.e(TAG, rr.serialString() + "< "
                        + mRadioConfig.requestToString(rr.mRequest) + " error "
                        + responseInfo.error);
            }
        } else {
            Rlog.e(TAG, "setPreferredDataModemResponse: Error " + responseInfo.toString());
        }
    }

    /**
     * Response function for IRadioConfig.setModemsConfigResponse()
     * Currently this is being used as the callback for RadioConfig.setModemsConfig() method
     */
    public void setModemsConfigResponse(RadioResponseInfo responseInfo) {
        RILRequest rr = mRadioConfig.processResponse(responseInfo);

        if (rr != null) {
            if (responseInfo.error == RadioError.NONE) {
                // send response
                RadioResponse.sendMessageResponse(rr.mResult, rr.mRequest);
                Rlog.d(TAG, rr.serialString() + "< "
                        + mRadioConfig.requestToString(rr.mRequest));
            } else {
                rr.onError(responseInfo.error, null);
                Rlog.e(TAG, rr.serialString() + "< "
                        + mRadioConfig.requestToString(rr.mRequest) + " error "
                        + responseInfo.error);
            }
        } else {
            Rlog.e(TAG, "setModemsConfigResponse: Error " + responseInfo.toString());
        }
    }

    /**
     * Response function for IRadioConfig.getModemsConfigResponse()
     */
    public void getModemsConfigResponse(RadioResponseInfo responseInfo, ModemsConfig modemsConfig) {
        RILRequest rr = mRadioConfig.processResponse(responseInfo);

        if (rr != null) {
            if (responseInfo.error == RadioError.NONE) {
                // send response
                RadioResponse.sendMessageResponse(rr.mResult, modemsConfig);
                Rlog.d(TAG, rr.serialString() + "< "
                        + mRadioConfig.requestToString(rr.mRequest));
            } else {
                rr.onError(responseInfo.error, modemsConfig);
                Rlog.e(TAG, rr.serialString() + "< "
                        + mRadioConfig.requestToString(rr.mRequest) + " error "
                        + responseInfo.error);
            }
        } else {
            Rlog.e(TAG, "getModemsConfigResponse: Error " + responseInfo.toString());
        }
    }
}
