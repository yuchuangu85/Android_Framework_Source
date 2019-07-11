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

import android.hardware.radio.deprecated.V1_0.IOemHookIndication;
import android.os.AsyncResult;

import java.util.ArrayList;

import static com.android.internal.telephony.RILConstants.RIL_UNSOL_OEM_HOOK_RAW;

/**
 * Class containing oem hook indication callbacks
 */
public class OemHookIndication extends IOemHookIndication.Stub {
    RIL mRil;

    public OemHookIndication(RIL ril) {
        mRil = ril;
    }

    /**
     * @param indicationType RadioIndicationType
     * @param data Data sent by oem
     */
    public void oemHookRaw(int indicationType, ArrayList<Byte> data) {
        mRil.processIndication(indicationType);

        byte[] response = RIL.arrayListToPrimitiveArray(data);
        if (RIL.RILJ_LOGD) {
            mRil.unsljLogvRet(RIL_UNSOL_OEM_HOOK_RAW,
                    com.android.internal.telephony.uicc.IccUtils.bytesToHexString(response));
        }

        if (mRil.mUnsolOemHookRawRegistrant != null) {
            mRil.mUnsolOemHookRawRegistrant.notifyRegistrant(new AsyncResult(null, response, null));
        }
    }
}
