/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi.aware;

import android.content.Context;
import android.net.wifi.IRttManager;
import android.net.wifi.RttManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.AsyncChannel;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;


/**
 * Manages interactions between the Aware and the RTT service. Duplicates some of the functionality
 * of the RttManager.
 */
public class WifiAwareRttStateManager {
    private static final String TAG = "WifiAwareRttStateMgr";

    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    private final SparseArray<WifiAwareClientState> mPendingOperations = new SparseArray<>();
    private AsyncChannel mAsyncChannel;
    private Context mContext;

    /**
     * Initializes the connection to the RTT service.
     */
    public void start(Context context, Looper looper) {
        if (VDBG) Log.v(TAG, "start()");

        IBinder b = ServiceManager.getService(Context.WIFI_RTT_SERVICE);
        IRttManager service = IRttManager.Stub.asInterface(b);
        if (service == null) {
            Log.e(TAG, "start(): not able to get WIFI_RTT_SERVICE");
            return;
        }

        startWithRttService(context, looper, service);
    }

    /**
     * Initializes the connection to the RTT service.
     */
    @VisibleForTesting
    public void startWithRttService(Context context, Looper looper, IRttManager service) {
        Messenger messenger;
        try {
            messenger = service.getMessenger(null, new int[1]);
        } catch (RemoteException e) {
            Log.e(TAG, "start(): not able to getMessenger() of WIFI_RTT_SERVICE");
            return;
        }

        mAsyncChannel = new AsyncChannel();
        mAsyncChannel.connect(context, new AwareRttHandler(looper), messenger);
        mContext = context;
    }

    private WifiAwareClientState getAndRemovePendingOperationClient(int rangingId) {
        WifiAwareClientState client = mPendingOperations.get(rangingId);
        mPendingOperations.delete(rangingId);
        return client;
    }

    /**
     * Start a ranging operation for the client + peer MAC.
     */
    public void startRanging(int rangingId, WifiAwareClientState client,
                             RttManager.RttParams[] params) {
        if (VDBG) {
            Log.v(TAG, "startRanging: rangingId=" + rangingId + ", parms="
                    + Arrays.toString(params));
        }

        if (mAsyncChannel == null) {
            Log.d(TAG, "startRanging(): AsyncChannel to RTT service not configured - failing");
            client.onRangingFailure(rangingId, RttManager.REASON_NOT_AVAILABLE,
                    "Aware service not able to configure connection to RTT service");
            return;
        }

        mPendingOperations.put(rangingId, client);
        RttManager.ParcelableRttParams pparams = new RttManager.ParcelableRttParams(params);
        mAsyncChannel.sendMessage(RttManager.CMD_OP_START_RANGING, 0, rangingId, pparams);
    }

    private class AwareRttHandler extends Handler {
        AwareRttHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (VDBG) Log.v(TAG, "handleMessage(): " + msg.what);

            // channel configuration messages
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        mAsyncChannel.sendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION,
                                new RttManager.RttClient(mContext.getPackageName()));
                    } else {
                        Log.e(TAG, "Failed to set up channel connection to RTT service");
                        mAsyncChannel = null;
                    }
                    return;
                case AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED:
                    /* NOP */
                    return;
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    Log.e(TAG, "Channel connection to RTT service lost");
                    mAsyncChannel = null;
                    return;
            }

            // RTT-specific messages
            WifiAwareClientState client = getAndRemovePendingOperationClient(msg.arg2);
            if (client == null) {
                Log.e(TAG, "handleMessage(): RTT message (" + msg.what
                        + ") -- cannot find registered pending operation client for ID "
                        + msg.arg2);
                return;
            }

            switch (msg.what) {
                case RttManager.CMD_OP_SUCCEEDED: {
                    int rangingId = msg.arg2;
                    RttManager.ParcelableRttResults results = (RttManager.ParcelableRttResults)
                            msg.obj;
                    if (VDBG) {
                        Log.v(TAG, "CMD_OP_SUCCEEDED: rangingId=" + rangingId + ", results="
                                + results);
                    }
                    for (int i = 0; i < results.mResults.length; ++i) {
                        /*
                         * TODO: store peer ID rather than null in the return result.
                         */
                        results.mResults[i].bssid = null;
                    }
                    client.onRangingSuccess(rangingId, results);
                    break;
                }
                case RttManager.CMD_OP_FAILED: {
                    int rangingId = msg.arg2;
                    int reason = msg.arg1;
                    String description = ((Bundle) msg.obj).getString(RttManager.DESCRIPTION_KEY);
                    if (VDBG) {
                        Log.v(TAG, "CMD_OP_FAILED: rangingId=" + rangingId + ", reason=" + reason
                                + ", description=" + description);
                    }
                    client.onRangingFailure(rangingId, reason, description);
                    break;
                }
                case RttManager.CMD_OP_ABORTED: {
                    int rangingId = msg.arg2;
                    if (VDBG) {
                        Log.v(TAG, "CMD_OP_ABORTED: rangingId=" + rangingId);
                    }
                    client.onRangingAborted(rangingId);
                    break;
                }
                default:
                    Log.e(TAG, "handleMessage(): ignoring message " + msg.what);
                    break;
            }
        }
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("WifiAwareRttStateManager:");
        pw.println("  mPendingOperations: [" + mPendingOperations + "]");
    }
}
