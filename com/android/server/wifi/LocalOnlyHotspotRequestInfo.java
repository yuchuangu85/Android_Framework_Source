/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.annotation.Nullable;
import android.net.wifi.ILocalOnlyHotspotCallback;
import android.net.wifi.SoftApConfiguration;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.util.Preconditions;

/**
 * Tracks information about applications requesting use of the LocalOnlyHotspot.
 *
 * @hide
 */
class LocalOnlyHotspotRequestInfo implements IBinder.DeathRecipient {
    static final int HOTSPOT_NO_ERROR = -1;

    private final int mPid;
    private final ILocalOnlyHotspotCallback mCallback;
    private final RequestingApplicationDeathCallback mDeathCallback;
    private final SoftApConfiguration mCustomConfig;

    /**
     * Callback for use with LocalOnlyHotspot to unregister requesting applications upon death.
     */
    interface RequestingApplicationDeathCallback {
        /**
         * Called when requesting app has died.
         */
        void onLocalOnlyHotspotRequestorDeath(LocalOnlyHotspotRequestInfo requestor);
    }

    LocalOnlyHotspotRequestInfo(@NonNull ILocalOnlyHotspotCallback callback,
            @NonNull RequestingApplicationDeathCallback deathCallback,
            @Nullable SoftApConfiguration customConfig) {
        mPid = Binder.getCallingPid();
        mCallback = Preconditions.checkNotNull(callback);
        mDeathCallback = Preconditions.checkNotNull(deathCallback);
        mCustomConfig = customConfig;

        try {
            mCallback.asBinder().linkToDeath(this, 0);
        } catch (RemoteException e) {
            binderDied();
        }
    }

    /**
     * Allow caller to unlink this object from binder death.
     */
    public void unlinkDeathRecipient() {
        mCallback.asBinder().unlinkToDeath(this, 0);
    }

    /**
     * Application requesting LocalOnlyHotspot died
     */
    @Override
    public void binderDied() {
        mDeathCallback.onLocalOnlyHotspotRequestorDeath(this);
    }

    /**
     * Send a HOTSPOT_FAILED message to WifiManager for the calling application with the error code.
     *
     * @param reasonCode error code for the message
     *
     * @throws RemoteException
     */
    public void sendHotspotFailedMessage(int reasonCode) throws RemoteException {
        mCallback.onHotspotFailed(reasonCode);
    }

    /**
     * Send a HOTSPOT_STARTED message to WifiManager for the calling application with the config.
     *
     * @param config SoftApConfiguration for the callback
     *
     * @throws RemoteException
     */
    public void sendHotspotStartedMessage(SoftApConfiguration config) throws RemoteException {
        mCallback.onHotspotStarted(config);
    }

    /**
     * Send a HOTSPOT_STOPPED message to WifiManager for the calling application.
     *
     * @throws RemoteException
     */
    public void sendHotspotStoppedMessage() throws RemoteException {
        mCallback.onHotspotStopped();
    }

    public int getPid() {
        return mPid;
    }

    public SoftApConfiguration getCustomConfig() {
        return mCustomConfig;
    }
}
