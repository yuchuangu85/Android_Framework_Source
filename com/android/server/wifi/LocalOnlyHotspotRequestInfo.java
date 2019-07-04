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
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import com.android.internal.util.Preconditions;

/**
 * Tracks information about applications requesting use of the LocalOnlyHotspot.
 *
 * @hide
 */
public class LocalOnlyHotspotRequestInfo implements IBinder.DeathRecipient {
    static final int HOTSPOT_NO_ERROR = -1;

    private final int mPid;
    private final IBinder mBinder;
    private final RequestingApplicationDeathCallback mCallback;
    private final Messenger mMessenger;

    /**
     * Callback for use with LocalOnlyHotspot to unregister requesting applications upon death.
     */
    public interface RequestingApplicationDeathCallback {
        /**
         * Called when requesting app has died.
         */
        void onLocalOnlyHotspotRequestorDeath(LocalOnlyHotspotRequestInfo requestor);
    }

    LocalOnlyHotspotRequestInfo(@NonNull IBinder binder, @NonNull Messenger messenger,
            @NonNull RequestingApplicationDeathCallback callback) {
        mPid = Binder.getCallingPid();
        mBinder = Preconditions.checkNotNull(binder);
        mMessenger = Preconditions.checkNotNull(messenger);
        mCallback = Preconditions.checkNotNull(callback);

        try {
            mBinder.linkToDeath(this, 0);
        } catch (RemoteException e) {
            binderDied();
        }
    }

    /**
     * Allow caller to unlink this object from binder death.
     */
    public void unlinkDeathRecipient() {
        mBinder.unlinkToDeath(this, 0);
    }

    /**
     * Application requesting LocalOnlyHotspot died
     */
    @Override
    public void binderDied() {
        mCallback.onLocalOnlyHotspotRequestorDeath(this);
    }

    /**
     * Send a HOTSPOT_FAILED message to WifiManager for the calling application with the error code.
     *
     * @param reasonCode error code for the message
     *
     * @throws RemoteException
     */
    public void sendHotspotFailedMessage(int reasonCode) throws RemoteException {
        Message message = Message.obtain();
        message.what = WifiManager.HOTSPOT_FAILED;
        message.arg1 = reasonCode;
        mMessenger.send(message);
    }

    /**
     * Send a HOTSPOT_STARTED message to WifiManager for the calling application with the config.
     *
     * @param config WifiConfiguration for the callback
     *
     * @throws RemoteException
     */
    public void sendHotspotStartedMessage(WifiConfiguration config) throws RemoteException {
        Message message = Message.obtain();
        message.what = WifiManager.HOTSPOT_STARTED;
        message.obj = config;
        mMessenger.send(message);
    }

    /**
     * Send a HOTSPOT_STOPPED message to WifiManager for the calling application.
     *
     * @throws RemoteException
     */
    public void sendHotspotStoppedMessage() throws RemoteException {
        Message message = Message.obtain();
        message.what = WifiManager.HOTSPOT_STOPPED;
        mMessenger.send(message);
    }

    public int getPid() {
        return mPid;
    }
}
