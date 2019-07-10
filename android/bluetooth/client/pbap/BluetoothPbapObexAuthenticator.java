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

import android.os.Handler;
import android.util.Log;

import javax.obex.Authenticator;
import javax.obex.PasswordAuthentication;

class BluetoothPbapObexAuthenticator implements Authenticator {

    private final static String TAG = "BluetoothPbapObexAuthenticator";

    private String mSessionKey;

    private boolean mReplied;

    private final Handler mCallback;

    public BluetoothPbapObexAuthenticator(Handler callback) {
        mCallback = callback;
    }

    public synchronized void setReply(String key) {
        Log.d(TAG, "setReply key=" + key);

        mSessionKey = key;
        mReplied = true;

        notify();
    }

    @Override
    public PasswordAuthentication onAuthenticationChallenge(String description,
            boolean isUserIdRequired, boolean isFullAccess) {
        PasswordAuthentication pa = null;

        mReplied = false;

        Log.d(TAG, "onAuthenticationChallenge: sending request");
        mCallback.obtainMessage(BluetoothPbapObexSession.OBEX_SESSION_AUTHENTICATION_REQUEST)
                .sendToTarget();

        synchronized (this) {
            while (!mReplied) {
                try {
                    Log.v(TAG, "onAuthenticationChallenge: waiting for response");
                    this.wait();
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted while waiting for challenge response");
                }
            }
        }

        if (mSessionKey != null && mSessionKey.length() != 0) {
            Log.v(TAG, "onAuthenticationChallenge: mSessionKey=" + mSessionKey);
            pa = new PasswordAuthentication(null, mSessionKey.getBytes());
        } else {
            Log.v(TAG, "onAuthenticationChallenge: mSessionKey is empty, timeout/cancel occured");
        }

        return pa;
    }

    @Override
    public byte[] onAuthenticationResponse(byte[] userName) {
        /* required only in case PCE challenges PSE which we don't do now */
        return null;
    }

}
