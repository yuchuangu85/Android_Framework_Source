/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.internal.telephony.ims;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.ims.internal.IImsFeatureStatusCallback;
import com.android.ims.internal.IImsService;

/**
 * A compat layer for communicating with older devices that still used the ServiceManager to get
 * the ImsService.
 */

public class ImsServiceControllerStaticCompat extends ImsServiceControllerCompat {

    private static final String TAG = "ImsSCStaticCompat";

    private static final String IMS_SERVICE_NAME = "ims";

    private class ImsDeathRecipient implements IBinder.DeathRecipient {

        private ComponentName mComponentName;
        private ServiceConnection mServiceConnection;

        ImsDeathRecipient(ComponentName name, ServiceConnection conn) {
            mComponentName = name;
            mServiceConnection = conn;
        }

        @Override
        public void binderDied() {
            Log.e(TAG, "ImsService(" + mComponentName + ") died. Restarting...");
            // This is hacky... ImsServiceController uses the traditional service binding procedure,
            // so we have to emulate it when using a persistent service.
            mServiceConnection.onBindingDied(mComponentName);
        }
    }

    private IImsService mImsServiceCompat = null;
    private ImsDeathRecipient mImsDeathRecipient = null;

    public ImsServiceControllerStaticCompat(Context context, ComponentName componentName,
            ImsServiceController.ImsServiceControllerCallbacks callbacks) {
        super(context, componentName, callbacks);
    }

    @Override
    public boolean startBindToService(Intent intent, ImsServiceConnection connection, int flags) {
        IBinder binder = ServiceManager.checkService(IMS_SERVICE_NAME);

        if (binder == null) {
            return false;
        }
        // This is a little hacky, but we are going to call the onServiceConnected to "pretend" like
        // bindService has completed here, which will pass the binder to setServiceController and
        // set up all supporting structures.
        ComponentName name = new ComponentName(mContext, ImsServiceControllerStaticCompat.class);
        connection.onServiceConnected(name, binder);
        try {
            mImsDeathRecipient = new ImsDeathRecipient(name, connection);
            binder.linkToDeath(mImsDeathRecipient, 0);
        } catch (RemoteException e) {
            // The binder connection is already dead.. signal to the ImsServiceController to retry.
            mImsDeathRecipient.binderDied();
            mImsDeathRecipient = null;
        }
        return true;
    }

    @Override
    protected void setServiceController(IBinder serviceController) {
        if (serviceController == null) {
            // The service controller has been set to null, meaning it has been unbound or died.
            // Unlink if needed.
            if (mImsServiceCompat != null) {
                mImsServiceCompat.asBinder().unlinkToDeath(mImsDeathRecipient, 0);
            }
            mImsDeathRecipient = null;
        }
        mImsServiceCompat = IImsService.Stub.asInterface(serviceController);
    }

    @Override
    // used for add/remove features and cleanup in ImsServiceController.
    protected boolean isServiceControllerAvailable() {
        return mImsServiceCompat != null;
    }

    @Override
    protected MmTelInterfaceAdapter getInterface(int slotId, IImsFeatureStatusCallback c) {
        if (mImsServiceCompat == null) {
            Log.w(TAG, "getInterface: IImsService returned null.");
            return null;
        }
        return new ImsServiceInterfaceAdapter(slotId, mImsServiceCompat.asBinder());
    }
}
