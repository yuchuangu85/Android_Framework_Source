/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.bluetooth.client.pbap;

import android.util.Log;

import com.android.vcard.VCardEntry;
import android.bluetooth.client.pbap.utils.ObexAppParameters;

import java.io.IOException;
import java.io.InputStream;

import javax.obex.HeaderSet;
import javax.obex.ResponseCodes;

final class BluetoothPbapRequestPullVcardEntry extends BluetoothPbapRequest {

    private static final String TAG = "BluetoothPbapRequestPullVcardEntry";

    private static final String TYPE = "x-bt/vcard";

    private BluetoothPbapVcardList mResponse;

    private final byte mFormat;

    public BluetoothPbapRequestPullVcardEntry(String handle, long filter, byte format) {
        mHeaderSet.setHeader(HeaderSet.NAME, handle);

        mHeaderSet.setHeader(HeaderSet.TYPE, TYPE);

        /* make sure format is one of allowed values */
        if (format != BluetoothPbapClient.VCARD_TYPE_21
                && format != BluetoothPbapClient.VCARD_TYPE_30) {
            format = BluetoothPbapClient.VCARD_TYPE_21;
        }

        ObexAppParameters oap = new ObexAppParameters();

        if (filter != 0) {
            oap.add(OAP_TAGID_FILTER, filter);
        }

        oap.add(OAP_TAGID_FORMAT, format);
        oap.addToHeaderSet(mHeaderSet);

        mFormat = format;
    }

    @Override
    protected void readResponse(InputStream stream) throws IOException {
        Log.v(TAG, "readResponse");

        mResponse = new BluetoothPbapVcardList(stream, mFormat);
    }
    @Override
    protected void checkResponseCode(int responseCode) throws IOException {
        Log.v(TAG, "checkResponseCode");

        if (mResponse.getCount() == 0) {
            if (responseCode != ResponseCodes.OBEX_HTTP_NOT_FOUND &&
                responseCode != ResponseCodes.OBEX_HTTP_NOT_ACCEPTABLE) {
                throw new IOException("Invalid response received");
            } else {
                Log.v(TAG, "Vcard Entry not found");
            }
        }
    }

    public VCardEntry getVcard() {
        return mResponse.getFirst();
    }
}
