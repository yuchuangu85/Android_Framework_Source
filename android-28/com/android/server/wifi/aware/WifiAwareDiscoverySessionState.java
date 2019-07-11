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

import android.hardware.wifi.V1_0.NanStatusType;
import android.net.wifi.aware.IWifiAwareDiscoverySessionCallback;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.SubscribeConfig;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import libcore.util.HexEncoding;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Manages the state of a single Aware discovery session (publish or subscribe).
 * Primary state consists of a callback through which session callbacks are
 * executed as well as state related to currently active discovery sessions:
 * publish/subscribe ID, and MAC address caching (hiding) from clients.
 */
public class WifiAwareDiscoverySessionState {
    private static final String TAG = "WifiAwareDiscSessState";
    private static final boolean VDBG = false; // STOPSHIP if true
    /* package */ boolean mDbg = false;

    private static int sNextPeerIdToBeAllocated = 100; // used to create a unique peer ID

    private final WifiAwareNativeApi mWifiAwareNativeApi;
    private int mSessionId;
    private byte mPubSubId;
    private IWifiAwareDiscoverySessionCallback mCallback;
    private boolean mIsPublishSession;
    private boolean mIsRangingEnabled;
    private final long mCreationTime;

    static class PeerInfo {
        PeerInfo(int instanceId, byte[] mac) {
            mInstanceId = instanceId;
            mMac = mac;
        }

        int mInstanceId;
        byte[] mMac;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("instanceId [");
            sb.append(mInstanceId).append(", mac=").append(HexEncoding.encode(mMac)).append("]");
            return sb.toString();
        }
    }

    private final SparseArray<PeerInfo> mPeerInfoByRequestorInstanceId = new SparseArray<>();

    public WifiAwareDiscoverySessionState(WifiAwareNativeApi wifiAwareNativeApi, int sessionId,
            byte pubSubId, IWifiAwareDiscoverySessionCallback callback, boolean isPublishSession,
            boolean isRangingEnabled, long creationTime) {
        mWifiAwareNativeApi = wifiAwareNativeApi;
        mSessionId = sessionId;
        mPubSubId = pubSubId;
        mCallback = callback;
        mIsPublishSession = isPublishSession;
        mIsRangingEnabled = isRangingEnabled;
        mCreationTime = creationTime;
    }

    public int getSessionId() {
        return mSessionId;
    }

    public int getPubSubId() {
        return mPubSubId;
    }

    public boolean isPublishSession() {
        return mIsPublishSession;
    }

    public boolean isRangingEnabled() {
        return mIsRangingEnabled;
    }

    public long getCreationTime() {
        return mCreationTime;
    }

    public IWifiAwareDiscoverySessionCallback getCallback() {
        return mCallback;
    }

    /**
     * Return the peer information of the specified peer ID - or a null if no such peer ID is
     * registered.
     */
    public PeerInfo getPeerInfo(int peerId) {
        return mPeerInfoByRequestorInstanceId.get(peerId);
    }

    /**
     * Destroy the current discovery session - stops publishing or subscribing
     * if currently active.
     */
    public void terminate() {
        mCallback = null;

        if (mIsPublishSession) {
            mWifiAwareNativeApi.stopPublish((short) 0, mPubSubId);
        } else {
            mWifiAwareNativeApi.stopSubscribe((short) 0, mPubSubId);
        }
    }

    /**
     * Indicates whether the publish/subscribe ID (a HAL ID) corresponds to this
     * session.
     *
     * @param pubSubId The publish/subscribe HAL ID to be tested.
     * @return true if corresponds to this session, false otherwise.
     */
    public boolean isPubSubIdSession(int pubSubId) {
        return mPubSubId == pubSubId;
    }

    /**
     * Modify a publish discovery session.
     *
     * @param transactionId Transaction ID for the transaction - used in the
     *            async callback to match with the original request.
     * @param config Configuration of the publish session.
     */
    public boolean updatePublish(short transactionId, PublishConfig config) {
        if (!mIsPublishSession) {
            Log.e(TAG, "A SUBSCRIBE session is being used to publish");
            try {
                mCallback.onSessionConfigFail(NanStatusType.INTERNAL_FAILURE);
            } catch (RemoteException e) {
                Log.e(TAG, "updatePublish: RemoteException=" + e);
            }
            return false;
        }

        boolean success = mWifiAwareNativeApi.publish(transactionId, mPubSubId, config);
        if (!success) {
            try {
                mCallback.onSessionConfigFail(NanStatusType.INTERNAL_FAILURE);
            } catch (RemoteException e) {
                Log.w(TAG, "updatePublish onSessionConfigFail(): RemoteException (FYI): " + e);
            }
        }

        return success;
    }

    /**
     * Modify a subscribe discovery session.
     *
     * @param transactionId Transaction ID for the transaction - used in the
     *            async callback to match with the original request.
     * @param config Configuration of the subscribe session.
     */
    public boolean updateSubscribe(short transactionId, SubscribeConfig config) {
        if (mIsPublishSession) {
            Log.e(TAG, "A PUBLISH session is being used to subscribe");
            try {
                mCallback.onSessionConfigFail(NanStatusType.INTERNAL_FAILURE);
            } catch (RemoteException e) {
                Log.e(TAG, "updateSubscribe: RemoteException=" + e);
            }
            return false;
        }

        boolean success = mWifiAwareNativeApi.subscribe(transactionId, mPubSubId, config);
        if (!success) {
            try {
                mCallback.onSessionConfigFail(NanStatusType.INTERNAL_FAILURE);
            } catch (RemoteException e) {
                Log.w(TAG, "updateSubscribe onSessionConfigFail(): RemoteException (FYI): " + e);
            }
        }

        return success;
    }

    /**
     * Send a message to a peer which is part of a discovery session.
     *
     * @param transactionId Transaction ID for the transaction - used in the
     *            async callback to match with the original request.
     * @param peerId ID of the peer. Obtained through previous communication (a
     *            match indication).
     * @param message Message byte array to send to the peer.
     * @param messageId A message ID provided by caller to be used in any
     *            callbacks related to the message (success/failure).
     */
    public boolean sendMessage(short transactionId, int peerId, byte[] message, int messageId) {
        PeerInfo peerInfo = mPeerInfoByRequestorInstanceId.get(peerId);
        if (peerInfo == null) {
            Log.e(TAG, "sendMessage: attempting to send a message to an address which didn't "
                    + "match/contact us");
            try {
                mCallback.onMessageSendFail(messageId, NanStatusType.INTERNAL_FAILURE);
            } catch (RemoteException e) {
                Log.e(TAG, "sendMessage: RemoteException=" + e);
            }
            return false;
        }

        boolean success = mWifiAwareNativeApi.sendMessage(transactionId, mPubSubId,
                peerInfo.mInstanceId, peerInfo.mMac, message, messageId);
        if (!success) {
            try {
                mCallback.onMessageSendFail(messageId, NanStatusType.INTERNAL_FAILURE);
            } catch (RemoteException e) {
                Log.e(TAG, "sendMessage: RemoteException=" + e);
            }
            return false;
        }

        return success;
    }

    /**
     * Callback from HAL when a discovery occurs - i.e. when a match to an
     * active subscription request or to a solicited publish request occurs.
     * Propagates to client if registered.
     *
     * @param requestorInstanceId The ID used to identify the peer in this
     *            matched session.
     * @param peerMac The MAC address of the peer. Never propagated to client
     *            due to privacy concerns.
     * @param serviceSpecificInfo Information from the discovery advertisement
     *            (usually not used in the match decisions).
     * @param matchFilter The filter from the discovery advertisement (which was
     *            used in the match decision).
     * @param rangingIndication Bit mask indicating the type of ranging event triggered.
     * @param rangeMm The range to the peer in mm (valid if rangingIndication specifies ingress
     *                or egress events - i.e. non-zero).
     */
    public void onMatch(int requestorInstanceId, byte[] peerMac, byte[] serviceSpecificInfo,
            byte[] matchFilter, int rangingIndication, int rangeMm) {
        int peerId = getPeerIdOrAddIfNew(requestorInstanceId, peerMac);

        try {
            if (rangingIndication == 0) {
                mCallback.onMatch(peerId, serviceSpecificInfo, matchFilter);
            } else {
                mCallback.onMatchWithDistance(peerId, serviceSpecificInfo, matchFilter, rangeMm);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onMatch: RemoteException (FYI): " + e);
        }
    }

    /**
     * Callback from HAL when a message is received from a peer in a discovery
     * session. Propagated to client if registered.
     *
     * @param requestorInstanceId An ID used to identify the peer.
     * @param peerMac The MAC address of the peer sending the message. This
     *            information is never propagated to the client due to privacy
     *            concerns.
     * @param message The received message.
     */
    public void onMessageReceived(int requestorInstanceId, byte[] peerMac, byte[] message) {
        int peerId = getPeerIdOrAddIfNew(requestorInstanceId, peerMac);

        try {
            mCallback.onMessageReceived(peerId, message);
        } catch (RemoteException e) {
            Log.w(TAG, "onMessageReceived: RemoteException (FYI): " + e);
        }
    }

    private int getPeerIdOrAddIfNew(int requestorInstanceId, byte[] peerMac) {
        for (int i = 0; i < mPeerInfoByRequestorInstanceId.size(); ++i) {
            PeerInfo peerInfo = mPeerInfoByRequestorInstanceId.valueAt(i);
            if (peerInfo.mInstanceId == requestorInstanceId && Arrays.equals(peerMac,
                    peerInfo.mMac)) {
                return mPeerInfoByRequestorInstanceId.keyAt(i);
            }
        }

        int newPeerId = sNextPeerIdToBeAllocated++;
        PeerInfo newPeerInfo = new PeerInfo(requestorInstanceId, peerMac);
        mPeerInfoByRequestorInstanceId.put(newPeerId, newPeerInfo);

        if (VDBG) {
            Log.v(TAG, "New peer info: peerId=" + newPeerId + ", peerInfo=" + newPeerInfo);
        }

        return newPeerId;
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("AwareSessionState:");
        pw.println("  mSessionId: " + mSessionId);
        pw.println("  mIsPublishSession: " + mIsPublishSession);
        pw.println("  mPubSubId: " + mPubSubId);
        pw.println("  mPeerInfoByRequestorInstanceId: [" + mPeerInfoByRequestorInstanceId + "]");
    }
}
