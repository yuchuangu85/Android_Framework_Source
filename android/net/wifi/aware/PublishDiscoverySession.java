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

package android.net.wifi.aware;

import static com.android.wifi.flags.Flags.FLAG_MULTI_PEER_AWARE_DATAPATH;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.util.Log;

/**
 * A class representing a Aware publish session. Created when
 * {@link WifiAwareSession#publish(PublishConfig, DiscoverySessionCallback,
 * android.os.Handler)} is called and a discovery session is created and returned in
 * {@link DiscoverySessionCallback#onPublishStarted(PublishDiscoverySession)}. See
 * baseline functionality of all discovery sessions in {@link DiscoverySession}. This
 * object allows updating an existing/running publish discovery session using
 * {@link #updatePublish(PublishConfig)}.
 */
public class PublishDiscoverySession extends DiscoverySession {
    private static final String TAG = "PublishDiscoverySession";

    /** @hide */
    public PublishDiscoverySession(WifiAwareManager manager, int clientId, int sessionId) {
        super(manager, clientId, sessionId);
    }

    /**
     * Re-configure the currently active publish session. The
     * {@link DiscoverySessionCallback} is not replaced - the same listener used
     * at creation is still used. The results of the configuration are returned using
     * {@link DiscoverySessionCallback}:
     * <ul>
     *     <li>{@link DiscoverySessionCallback#onSessionConfigUpdated()}: configuration
     *     update succeeded.
     *     <li>{@link DiscoverySessionCallback#onSessionConfigFailed()}: configuration
     *     update failed. The publish discovery session is still running using its previous
     *     configuration (i.e. update failure does not terminate the session).
     * </ul>
     *
     * @param publishConfig The new discovery publish session configuration ({@link PublishConfig}).
     */
    public void updatePublish(@NonNull PublishConfig publishConfig) {
        if (mTerminated) {
            Log.w(TAG, "updatePublish: called on terminated session");
            return;
        } else {
            WifiAwareManager mgr = mMgr.get();
            if (mgr == null) {
                Log.w(TAG, "updatePublish: called post GC on WifiAwareManager");
                return;
            }

            mgr.updatePublish(mClientId, mSessionId, publishConfig);
        }
    }

    /**
     * Accept a Wi-Fi Aware data path request to create a connection with the target peer.
     * The Aware data path request should be done in the context of a discovery session -
     * after a {@link DiscoverySessionCallback#onDataPathRequestReceived(PeerHandle)} event
     * is received.
     * When the Aware data path setup succeeds, both side will receive
     * {@link DiscoverySessionCallback#onDataPathConnected(PeerHandle, WifiAwareNetworkInfo)}.
     * Otherwise {@link DiscoverySessionCallback#onDataPathRequestFailed(PeerHandle, int)} will be
     * called.
     * @param peerHandle The peer's handle for the data path request.
     * @param request The data path request.
     * @return true if framework accept the data path request successfully and start negotiation,
     *         false otherwise.
     */
    @FlaggedApi(FLAG_MULTI_PEER_AWARE_DATAPATH)
    public boolean acceptDataPathRequest(@NonNull PeerHandle peerHandle,
            @NonNull AwareDataPathRequest request) {
        if (mTerminated) {
            return false;
        }

        WifiAwareManager mgr = mMgr.get();
        if (mgr == null) {
            return false;
        }
        mgr.respondToDataPath(mClientId, mSessionId, peerHandle, request, true);
        return true;
    }

    /**
     * Reject a Wi-Fi Aware data path request received from peer. This is the
     * response to the
     * {@link DiscoverySessionCallback#onDataPathRequestReceived(PeerHandle)}
     *
     * @param peerHandle The peer's handle for the data path request.
     * @return true if the data path is rejected successfully, false otherwise.
     */
    @FlaggedApi(FLAG_MULTI_PEER_AWARE_DATAPATH)
    public boolean rejectDataPathRequest(@NonNull PeerHandle peerHandle) {
        if (mTerminated) {
            return false;
        }
        WifiAwareManager mgr = mMgr.get();
        if (mgr == null) {
            return false;
        }
        mgr.respondToDataPath(mClientId, mSessionId, peerHandle, null, false);
        return true;
    }

}
