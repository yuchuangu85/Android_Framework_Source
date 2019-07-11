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

import javax.obex.ClientSession;
import javax.obex.HeaderSet;
import javax.obex.ResponseCodes;

final class BluetoothPbapRequestSetPath extends BluetoothPbapRequest {

    private final static String TAG = "BluetoothPbapRequestSetPath";

    private enum SetPathDir {
        ROOT, UP, DOWN
    };

    private SetPathDir mDir;

    public BluetoothPbapRequestSetPath(String name) {
        mDir = SetPathDir.DOWN;
        mHeaderSet.setHeader(HeaderSet.NAME, name);
    }

    public BluetoothPbapRequestSetPath(boolean goUp) {
        mHeaderSet.setEmptyNameHeader();
        if (goUp) {
            mDir = SetPathDir.UP;
        } else {
            mDir = SetPathDir.ROOT;
        }
    }

    @Override
    public void execute(ClientSession session) {
        Log.v(TAG, "execute");

        HeaderSet hs = null;

        try {
            switch (mDir) {
                case ROOT:
                case DOWN:
                    hs = session.setPath(mHeaderSet, false, false);
                    break;
                case UP:
                    hs = session.setPath(mHeaderSet, true, false);
                    break;
            }

            mResponseCode = hs.getResponseCode();
        } catch (IOException e) {
            mResponseCode = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
        }
    }
}
