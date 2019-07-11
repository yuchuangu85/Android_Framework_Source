/**
 * Copyright (C) 2017 The Android Open Source Project
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

import android.hardware.radio.deprecated.V1_0.IOemHookResponse;
import android.hardware.radio.V1_0.RadioError;
import android.hardware.radio.V1_0.RadioResponseInfo;

import java.util.ArrayList;

/**
 * Class containing oem hook response callbacks
 */
public class OemHookResponse extends IOemHookResponse.Stub {
    RIL mRil;

    public OemHookResponse(RIL ril) {
        mRil = ril;
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param data Data returned by oem
     */
    public void sendRequestRawResponse(RadioResponseInfo responseInfo, ArrayList<Byte> data) {
        RILRequest rr = mRil.processResponse(responseInfo);

        if (rr != null) {
            byte[] ret = null;
            if (responseInfo.error == RadioError.NONE) {
                ret = RIL.arrayListToPrimitiveArray(data);
                RadioResponse.sendMessageResponse(rr.mResult, ret);
            }
            mRil.processResponseDone(rr, responseInfo, ret);
        }
    }

    /**
     * @param responseInfo Response info struct containing response type, serial no. and error
     * @param data Data returned by oem
     */
    public void sendRequestStringsResponse(RadioResponseInfo responseInfo, ArrayList<String> data) {
        RadioResponse.responseStringArrayList(mRil, responseInfo, data);
    }
}
