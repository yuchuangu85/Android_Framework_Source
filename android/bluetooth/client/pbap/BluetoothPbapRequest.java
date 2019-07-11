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

import java.io.IOException;
import java.io.InputStream;

import javax.obex.ClientOperation;
import javax.obex.ClientSession;
import javax.obex.HeaderSet;
import javax.obex.ResponseCodes;

abstract class BluetoothPbapRequest {

    private static final String TAG = "BluetoothPbapRequest";

    protected static final byte OAP_TAGID_ORDER = 0x01;
    protected static final byte OAP_TAGID_SEARCH_VALUE = 0x02;
    protected static final byte OAP_TAGID_SEARCH_ATTRIBUTE = 0x03;
    protected static final byte OAP_TAGID_MAX_LIST_COUNT = 0x04;
    protected static final byte OAP_TAGID_LIST_START_OFFSET = 0x05;
    protected static final byte OAP_TAGID_FILTER = 0x06;
    protected static final byte OAP_TAGID_FORMAT = 0x07;
    protected static final byte OAP_TAGID_PHONEBOOK_SIZE = 0x08;
    protected static final byte OAP_TAGID_NEW_MISSED_CALLS = 0x09;

    protected HeaderSet mHeaderSet;

    protected int mResponseCode;

    private boolean mAborted = false;

    private ClientOperation mOp = null;

    public BluetoothPbapRequest() {
        mHeaderSet = new HeaderSet();
    }

    final public boolean isSuccess() {
        return (mResponseCode == ResponseCodes.OBEX_HTTP_OK);
    }

    public void execute(ClientSession session) throws IOException {
        Log.v(TAG, "execute");

        /* in case request is aborted before can be executed */
        if (mAborted) {
            mResponseCode = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
            return;
        }

        try {
            mOp = (ClientOperation) session.get(mHeaderSet);

            /* make sure final flag for GET is used (PBAP spec 6.2.2) */
            mOp.setGetFinalFlag(true);

            /*
             * this will trigger ClientOperation to use non-buffered stream so
             * we can abort operation
             */
            mOp.continueOperation(true, false);

            readResponseHeaders(mOp.getReceivedHeader());

            InputStream is = mOp.openInputStream();
            readResponse(is);
            is.close();

            mOp.close();

            mResponseCode = mOp.getResponseCode();

            Log.d(TAG, "mResponseCode=" + mResponseCode);

            checkResponseCode(mResponseCode);
        } catch (IOException e) {
            Log.e(TAG, "IOException occured when processing request", e);
            mResponseCode = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;

            throw e;
        }
    }

    public void abort() {
        mAborted = true;

        if (mOp != null) {
            try {
                mOp.abort();
            } catch (IOException e) {
                Log.e(TAG, "Exception occured when trying to abort", e);
            }
        }
    }

    protected void readResponse(InputStream stream) throws IOException {
        Log.v(TAG, "readResponse");

        /* nothing here by default */
    }

    protected void readResponseHeaders(HeaderSet headerset) {
        Log.v(TAG, "readResponseHeaders");

        /* nothing here by dafault */
    }

    protected void checkResponseCode(int responseCode) throws IOException {
        Log.v(TAG, "checkResponseCode");

        /* nothing here by dafault */
    }
}
