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
import java.util.ArrayList;

import javax.obex.HeaderSet;

final class BluetoothPbapRequestPullPhoneBook extends BluetoothPbapRequest {

    private static final String TAG = "BluetoothPbapRequestPullPhoneBook";

    private static final String TYPE = "x-bt/phonebook";

    private BluetoothPbapVcardList mResponse;

    private int mNewMissedCalls = -1;

    private final byte mFormat;

    public BluetoothPbapRequestPullPhoneBook(String pbName, long filter, byte format,
            int maxListCount, int listStartOffset) {

        if (maxListCount < 0 || maxListCount > 65535) {
            throw new IllegalArgumentException("maxListCount should be [0..65535]");
        }

        if (listStartOffset < 0 || listStartOffset > 65535) {
            throw new IllegalArgumentException("listStartOffset should be [0..65535]");
        }

        mHeaderSet.setHeader(HeaderSet.NAME, pbName);

        mHeaderSet.setHeader(HeaderSet.TYPE, TYPE);

        ObexAppParameters oap = new ObexAppParameters();

        /* make sure format is one of allowed values */
        if (format != BluetoothPbapClient.VCARD_TYPE_21
                && format != BluetoothPbapClient.VCARD_TYPE_30) {
            format = BluetoothPbapClient.VCARD_TYPE_21;
        }

        if (filter != 0) {
            oap.add(OAP_TAGID_FILTER, filter);
        }

        oap.add(OAP_TAGID_FORMAT, format);

        /*
         * maxListCount is a special case which is handled in
         * BluetoothPbapRequestPullPhoneBookSize
         */
        if (maxListCount > 0) {
            oap.add(OAP_TAGID_MAX_LIST_COUNT, (short) maxListCount);
        } else {
            oap.add(OAP_TAGID_MAX_LIST_COUNT, (short) 65535);
        }

        if (listStartOffset > 0) {
            oap.add(OAP_TAGID_LIST_START_OFFSET, (short) listStartOffset);
        }

        oap.addToHeaderSet(mHeaderSet);

        mFormat = format;
    }

    @Override
    protected void readResponse(InputStream stream) throws IOException {
        Log.v(TAG, "readResponse");

        mResponse = new BluetoothPbapVcardList(stream, mFormat);
    }

    @Override
    protected void readResponseHeaders(HeaderSet headerset) {
        Log.v(TAG, "readResponse");

        ObexAppParameters oap = ObexAppParameters.fromHeaderSet(headerset);

        if (oap.exists(OAP_TAGID_NEW_MISSED_CALLS)) {
            mNewMissedCalls = oap.getByte(OAP_TAGID_NEW_MISSED_CALLS);
        }
    }

    public ArrayList<VCardEntry> getList() {
        return mResponse.getList();
    }

    public int getNewMissedCalls() {
        return mNewMissedCalls;
    }
}
