/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.wifi;

import android.annotation.NonNull;
import android.net.wifi.ITrafficStateCallback;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.wifi.util.ExternalCallbackTracker;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Polls for traffic stats and notifies the clients
 */
public class WifiTrafficPoller {

    private static final String TAG = "WifiTrafficPoller";

    private long mTxPkts;
    private long mRxPkts;
    /* Tracks last reported data activity */
    private int mDataActivity;

    private final ExternalCallbackTracker<ITrafficStateCallback> mRegisteredCallbacks;

    WifiTrafficPoller(@NonNull Looper looper) {
        mRegisteredCallbacks = new ExternalCallbackTracker<ITrafficStateCallback>(
                new Handler(looper));
    }

    /**
     * Add a new callback to the traffic poller.
     */
    public void addCallback(IBinder binder, ITrafficStateCallback callback,
                            int callbackIdentifier) {
        if (!mRegisteredCallbacks.add(binder, callback, callbackIdentifier)) {
            Log.e(TAG, "Failed to add callback");
            return;
        }
    }

    /**
     * Remove an existing callback from the traffic poller.
     */
    public void removeCallback(int callbackIdentifier) {
        mRegisteredCallbacks.remove(callbackIdentifier);
    }

    void notifyOnDataActivity(long txPkts, long rxPkts) {
        long sent, received;
        long preTxPkts = mTxPkts, preRxPkts = mRxPkts;
        int dataActivity = WifiManager.TrafficStateCallback.DATA_ACTIVITY_NONE;
        mTxPkts = txPkts;
        mRxPkts = rxPkts;

        if (preTxPkts > 0 || preRxPkts > 0) {
            sent = mTxPkts - preTxPkts;
            received = mRxPkts - preRxPkts;
            if (sent > 0) {
                dataActivity |= WifiManager.TrafficStateCallback.DATA_ACTIVITY_OUT;
            }
            if (received > 0) {
                dataActivity |= WifiManager.TrafficStateCallback.DATA_ACTIVITY_IN;
            }

            if (dataActivity != mDataActivity) {
                mDataActivity = dataActivity;
                for (ITrafficStateCallback callback : mRegisteredCallbacks.getCallbacks()) {
                    try {
                        callback.onStateChanged(mDataActivity);
                    } catch (RemoteException e) {
                        // Failed to reach, skip
                        // Client removal is handled in WifiService
                    }
                }
            }
        }
    }

    /**
     * Dump method for traffic poller.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("mTxPkts " + mTxPkts);
        pw.println("mRxPkts " + mRxPkts);
        pw.println("mDataActivity " + mDataActivity);
        pw.println("mRegisteredCallbacks " + mRegisteredCallbacks.getNumCallbacks());
    }

}
