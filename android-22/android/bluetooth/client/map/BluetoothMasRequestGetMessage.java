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

package android.bluetooth.client.map;

import android.util.Log;


import android.bluetooth.client.map.BluetoothMasClient.CharsetType;
import android.bluetooth.client.map.utils.ObexAppParameters;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.obex.ClientSession;
import javax.obex.HeaderSet;
import javax.obex.ResponseCodes;

final class BluetoothMasRequestGetMessage extends BluetoothMasRequest {

    private static final String TAG = "BluetoothMasRequestGetMessage";

    private static final String TYPE = "x-bt/message";

    private BluetoothMapBmessage mBmessage;

    public BluetoothMasRequestGetMessage(String handle, CharsetType charset, boolean attachment) {

        mHeaderSet.setHeader(HeaderSet.NAME, handle);

        mHeaderSet.setHeader(HeaderSet.TYPE, TYPE);

        ObexAppParameters oap = new ObexAppParameters();

        oap.add(OAP_TAGID_CHARSET, CharsetType.UTF_8.equals(charset) ? CHARSET_UTF8
                : CHARSET_NATIVE);

        oap.add(OAP_TAGID_ATTACHMENT, attachment ? ATTACHMENT_ON : ATTACHMENT_OFF);

        oap.addToHeaderSet(mHeaderSet);
    }

    @Override
    protected void readResponse(InputStream stream) {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];

        try {
            int len;
            while ((len = stream.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
        } catch (IOException e) {
            Log.e(TAG, "I/O exception while reading response", e);
        }

        String bmsg = baos.toString();

        mBmessage = BluetoothMapBmessageParser.createBmessage(bmsg);

        if (mBmessage == null) {
            mResponseCode = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
    }

    public BluetoothMapBmessage getMessage() {
        return mBmessage;
    }

    @Override
    public void execute(ClientSession session) throws IOException {
        executeGet(session);
    }
}
