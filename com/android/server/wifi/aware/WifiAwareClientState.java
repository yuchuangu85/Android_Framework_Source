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

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.aware.ConfigRequest;
import android.net.wifi.aware.IWifiAwareEventCallback;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import libcore.util.HexEncoding;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Manages the service-side Aware state of an individual "client". A client
 * corresponds to a single instantiation of the WifiAwareManager - there could be
 * multiple ones per UID/process (each of which is a separate client with its
 * own session namespace). The client state is primarily: (1) callback (a
 * singleton per client) through which Aware-wide events are called, and (2) a set
 * of discovery sessions (publish and/or subscribe) which are created through
 * this client and whose lifetime is tied to the lifetime of the client.
 */
public class WifiAwareClientState {
    private static final String TAG = "WifiAwareClientState";
    private static final boolean VDBG = false; // STOPSHIP if true
    /* package */ boolean mDbg = false;

    /* package */ static final int CLUSTER_CHANGE_EVENT_STARTED = 0;
    /* package */ static final int CLUSTER_CHANGE_EVENT_JOINED = 1;

    private final Context mContext;
    private final IWifiAwareEventCallback mCallback;
    private final SparseArray<WifiAwareDiscoverySessionState> mSessions = new SparseArray<>();

    private final int mClientId;
    private ConfigRequest mConfigRequest;
    private final int mUid;
    private final int mPid;
    private final String mCallingPackage;
    private final boolean mNotifyIdentityChange;

    private final AppOpsManager mAppOps;
    private final long mCreationTime;

    private static final byte[] ALL_ZERO_MAC = new byte[] {0, 0, 0, 0, 0, 0};
    private byte[] mLastDiscoveryInterfaceMac = ALL_ZERO_MAC;

    public WifiAwareClientState(Context context, int clientId, int uid, int pid,
            String callingPackage, IWifiAwareEventCallback callback, ConfigRequest configRequest,
            boolean notifyIdentityChange, long creationTime) {
        mContext = context;
        mClientId = clientId;
        mUid = uid;
        mPid = pid;
        mCallingPackage = callingPackage;
        mCallback = callback;
        mConfigRequest = configRequest;
        mNotifyIdentityChange = notifyIdentityChange;

        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        mCreationTime = creationTime;
    }

    /**
     * Destroy the current client - corresponds to a disconnect() request from
     * the client. Destroys all discovery sessions belonging to this client.
     */
    public void destroy() {
        for (int i = 0; i < mSessions.size(); ++i) {
            mSessions.valueAt(i).terminate();
        }
        mSessions.clear();
        mConfigRequest = null;
    }

    public ConfigRequest getConfigRequest() {
        return mConfigRequest;
    }

    public int getClientId() {
        return mClientId;
    }

    public int getUid() {
        return mUid;
    }

    public String getCallingPackage() {
        return mCallingPackage;
    }

    public boolean getNotifyIdentityChange() {
        return mNotifyIdentityChange;
    }

    public long getCreationTime() {
        return mCreationTime;
    }

    public SparseArray<WifiAwareDiscoverySessionState> getSessions() {
        return mSessions;
    }

    /**
     * Searches the discovery sessions of this client and returns the one
     * corresponding to the publish/subscribe ID. Used on callbacks from HAL to
     * map callbacks to the correct discovery session.
     *
     * @param pubSubId The publish/subscribe match session ID.
     * @return Aware session corresponding to the requested ID.
     */
    public WifiAwareDiscoverySessionState getAwareSessionStateForPubSubId(int pubSubId) {
        for (int i = 0; i < mSessions.size(); ++i) {
            WifiAwareDiscoverySessionState session = mSessions.valueAt(i);
            if (session.isPubSubIdSession(pubSubId)) {
                return session;
            }
        }

        return null;
    }

    /**
     * Add the session to the client database.
     *
     * @param session Session to be added.
     */
    public void addSession(WifiAwareDiscoverySessionState session) {
        int sessionId = session.getSessionId();
        if (mSessions.get(sessionId) != null) {
            Log.w(TAG, "createSession: sessionId already exists (replaced) - " + sessionId);
        }

        mSessions.put(sessionId, session);
    }

    /**
     * Remove the specified session from the client database - without doing a
     * terminate on the session. The assumption is that it is already
     * terminated.
     *
     * @param sessionId The session ID of the session to be removed.
     */
    public void removeSession(int sessionId) {
        if (mSessions.get(sessionId) == null) {
            Log.e(TAG, "removeSession: sessionId doesn't exist - " + sessionId);
            return;
        }

        mSessions.delete(sessionId);
    }

    /**
     * Destroy the discovery session: terminates discovery and frees up
     * resources.
     *
     * @param sessionId The session ID of the session to be destroyed.
     */
    public WifiAwareDiscoverySessionState terminateSession(int sessionId) {
        WifiAwareDiscoverySessionState session = mSessions.get(sessionId);
        if (session == null) {
            Log.e(TAG, "terminateSession: sessionId doesn't exist - " + sessionId);
            return null;
        }

        session.terminate();
        mSessions.delete(sessionId);

        return session;
    }

    /**
     * Retrieve a session.
     *
     * @param sessionId Session ID of the session to be retrieved.
     * @return Session or null if there's no session corresponding to the
     *         sessionId.
     */
    public WifiAwareDiscoverySessionState getSession(int sessionId) {
        return mSessions.get(sessionId);
    }

    /**
     * Called to dispatch the Aware interface address change to the client - as an
     * identity change (interface address information not propagated to client -
     * privacy concerns).
     *
     * @param mac The new MAC address of the discovery interface - optionally propagated to the
     *            client.
     */
    public void onInterfaceAddressChange(byte[] mac) {
        if (VDBG) {
            Log.v(TAG,
                    "onInterfaceAddressChange: mClientId=" + mClientId + ", mNotifyIdentityChange="
                            + mNotifyIdentityChange + ", mac=" + String.valueOf(
                            HexEncoding.encode(mac)) + ", mLastDiscoveryInterfaceMac="
                            + String.valueOf(HexEncoding.encode(mLastDiscoveryInterfaceMac)));
        }
        if (mNotifyIdentityChange && !Arrays.equals(mac, mLastDiscoveryInterfaceMac)) {
            try {
                boolean hasPermission = hasLocationingPermission();
                if (VDBG) Log.v(TAG, "hasPermission=" + hasPermission);
                mCallback.onIdentityChanged(hasPermission ? mac : ALL_ZERO_MAC);
            } catch (RemoteException e) {
                Log.w(TAG, "onIdentityChanged: RemoteException - ignored: " + e);
            }
        }

        mLastDiscoveryInterfaceMac = mac;
    }

    /**
     * Called to dispatch the Aware cluster change (due to joining of a new
     * cluster or starting a cluster) to the client - as an identity change
     * (interface address information not propagated to client - privacy
     * concerns). Dispatched if the client registered for the identity changed
     * event.
     *
     * @param mac The cluster ID of the cluster started or joined.
     * @param currentDiscoveryInterfaceMac The MAC address of the discovery interface.
     */
    public void onClusterChange(int flag, byte[] mac, byte[] currentDiscoveryInterfaceMac) {
        if (VDBG) {
            Log.v(TAG,
                    "onClusterChange: mClientId=" + mClientId + ", mNotifyIdentityChange="
                            + mNotifyIdentityChange + ", mac=" + String.valueOf(
                            HexEncoding.encode(mac)) + ", currentDiscoveryInterfaceMac="
                            + String.valueOf(HexEncoding.encode(currentDiscoveryInterfaceMac))
                            + ", mLastDiscoveryInterfaceMac=" + String.valueOf(
                            HexEncoding.encode(mLastDiscoveryInterfaceMac)));
        }
        if (mNotifyIdentityChange && !Arrays.equals(currentDiscoveryInterfaceMac,
                mLastDiscoveryInterfaceMac)) {
            try {
                boolean hasPermission = hasLocationingPermission();
                if (VDBG) Log.v(TAG, "hasPermission=" + hasPermission);
                mCallback.onIdentityChanged(
                        hasPermission ? currentDiscoveryInterfaceMac : ALL_ZERO_MAC);
            } catch (RemoteException e) {
                Log.w(TAG, "onIdentityChanged: RemoteException - ignored: " + e);
            }
        }

        mLastDiscoveryInterfaceMac = currentDiscoveryInterfaceMac;
    }

    private boolean hasLocationingPermission() {
        // FINE provides COARSE, so only have to check for the latter
        return mContext.checkPermission(Manifest.permission.ACCESS_COARSE_LOCATION, mPid, mUid)
                == PackageManager.PERMISSION_GRANTED && mAppOps.noteOp(
                AppOpsManager.OP_COARSE_LOCATION, mUid, mCallingPackage)
                == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("AwareClientState:");
        pw.println("  mClientId: " + mClientId);
        pw.println("  mConfigRequest: " + mConfigRequest);
        pw.println("  mNotifyIdentityChange: " + mNotifyIdentityChange);
        pw.println("  mCallback: " + mCallback);
        pw.println("  mSessions: [" + mSessions + "]");
        for (int i = 0; i < mSessions.size(); ++i) {
            mSessions.valueAt(i).dump(fd, pw, args);
        }
    }
}
