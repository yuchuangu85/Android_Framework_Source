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

import android.bluetooth.client.pbap.utils.ObexAppParameters;
import android.bluetooth.client.pbap.BluetoothPbapVcardListing;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import javax.obex.HeaderSet;

final class BluetoothPbapRequestPullVcardListing extends BluetoothPbapRequest {

    private static final String TAG = "BluetoothPbapRequestPullVcardListing";

    private static final String TYPE = "x-bt/vcard-listing";

    private BluetoothPbapVcardListing mResponse = null;

    private int mNewMissedCalls = -1;

    public BluetoothPbapRequestPullVcardListing(String folder, byte order, byte searchAttr,
            String searchVal, int maxListCount, int listStartOffset) {

        if (maxListCount < 0 || maxListCount > 65535) {
            throw new IllegalArgumentException("maxListCount should be [0..65535]");
        }

        if (listStartOffset < 0 || listStartOffset > 65535) {
            throw new IllegalArgumentException("listStartOffset should be [0..65535]");
        }

        if (folder == null) {
            folder = "";
        }

        mHeaderSet.setHeader(HeaderSet.NAME, folder);

        mHeaderSet.setHeader(HeaderSet.TYPE, TYPE);

        ObexAppParameters oap = new ObexAppParameters();

        if (order >= 0) {
            oap.add(OAP_TAGID_ORDER, order);
        }

        if (searchVal != null) {
            oap.add(OAP_TAGID_SEARCH_ATTRIBUTE, searchAttr);
            oap.add(OAP_TAGID_SEARCH_VALUE, searchVal);
        }

        /*
         * maxListCount is a special case which is handled in
         * BluetoothPbapRequestPullVcardListingSize
         */
        if (maxListCount > 0) {
            oap.add(OAP_TAGID_MAX_LIST_COUNT, (short) maxListCount);
        }

        if (listStartOffset > 0) {
            oap.add(OAP_TAGID_LIST_START_OFFSET, (short) listStartOffset);
        }

        oap.addToHeaderSet(mHeaderSet);
    }

    @Override
    protected void readResponse(InputStream stream) throws IOException {
        Log.v(TAG, "readResponse");

        mResponse = new BluetoothPbapVcardListing(stream);
    }

    @Override
    protected void readResponseHeaders(HeaderSet headerset) {
        Log.v(TAG, "readResponseHeaders");

        ObexAppParameters oap = ObexAppParameters.fromHeaderSet(headerset);

        if (oap.exists(OAP_TAGID_NEW_MISSED_CALLS)) {
            mNewMissedCalls = oap.getByte(OAP_TAGID_NEW_MISSED_CALLS);
        }
    }

    public ArrayList<BluetoothPbapCard> getList() {
        return mResponse.getList();
    }

    public int getNewMissedCalls() {
        return mNewMissedCalls;
    }
}
