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

    private long mTxPkts = 0;
    private long mRxPkts = 0;

    private int mLastActivity = -1;

    private static class CallbackWrapper {
        public final ITrafficStateCallback callback;
        /**
         * On the first invocation, the callback is invoked no matter if the data activity changed
         * or not.
         */
        public boolean isFirstInvocation = true;

        CallbackWrapper(ITrafficStateCallback callback) {
            this.callback = callback;
        }
    }

    private final ExternalCallbackTracker<CallbackWrapper> mRegisteredCallbacks;

    public WifiTrafficPoller(@NonNull Handler handler) {
        mRegisteredCallbacks = new ExternalCallbackTracker<>(handler);
    }

    /**
     * Add a new callback to the traffic poller.
     */
    public void addCallback(IBinder binder, ITrafficStateCallback callback, int callbackId) {
        if (!mRegisteredCallbacks.add(binder, new CallbackWrapper(callback), callbackId)) {
            Log.e(TAG, "Failed to add callback");
        }
    }

    /**
     * Remove an existing callback from the traffic poller.
     */
    public void removeCallback(int callbackId) {
        mRegisteredCallbacks.remove(callbackId);
    }

    /**
     * Notifies clients of data activity if the activity changed since the last update.
     */
    public void notifyOnDataActivity(long newTxPkts, long newRxPkts) {
        if (newTxPkts <= 0 && newRxPkts <= 0) {
            return;
        }

        long sent = newTxPkts - mTxPkts;
        long received = newRxPkts - mRxPkts;
        int dataActivity = WifiManager.TrafficStateCallback.DATA_ACTIVITY_NONE;
        if (sent > 0) {
            dataActivity |= WifiManager.TrafficStateCallback.DATA_ACTIVITY_OUT;
        }
        if (received > 0) {
            dataActivity |= WifiManager.TrafficStateCallback.DATA_ACTIVITY_IN;
        }

        for (CallbackWrapper wrapper : mRegisteredCallbacks.getCallbacks()) {
            // if this callback hasn't been triggered before, or the data activity changed,
            // notify the callback
            if (wrapper.isFirstInvocation || dataActivity != mLastActivity) {
                wrapper.isFirstInvocation = false;
                try {
                    wrapper.callback.onStateChanged(dataActivity);
                } catch (RemoteException e) {
                    // Failed to reach, skip
                    // Client removal is handled in WifiService
                }
            }
        }

        mTxPkts = newTxPkts;
        mRxPkts = newRxPkts;
        mLastActivity = dataActivity;
    }

    /**
     * Dump method for traffic poller.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("mTxPkts " + mTxPkts);
        pw.println("mRxPkts " + mRxPkts);
        pw.println("mLastActivity " + mLastActivity);
        pw.println("mRegisteredCallbacks " + mRegisteredCallbacks.getNumCallbacks());
    }
}
