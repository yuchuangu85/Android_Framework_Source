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

import android.app.PendingIntent;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.compat.feature.ImsFeature;

import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsConfig;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.ims.internal.IImsRegistrationListener;
import com.android.ims.internal.IImsService;
import com.android.ims.internal.IImsUt;

/**
 * Compatibility layer for IImsService implementations of IMS. Converts "generic" MMTel commands
 * to implementation.
 */

public class ImsServiceInterfaceAdapter extends MmTelInterfaceAdapter {

    private static final int SERVICE_ID = ImsFeature.MMTEL;

    public ImsServiceInterfaceAdapter(int slotId, IBinder binder) {
        super(slotId, binder);
    }

    public int startSession(PendingIntent incomingCallIntent, IImsRegistrationListener listener)
            throws RemoteException {
        return getInterface().open(mSlotId, ImsFeature.MMTEL, incomingCallIntent, listener);
    }

    public void endSession(int sessionId) throws RemoteException {
        getInterface().close(sessionId);
    }

    public boolean isConnected(int callSessionType, int callType) throws RemoteException {
        return getInterface().isConnected(SERVICE_ID, callSessionType, callType);
    }

    public boolean isOpened() throws RemoteException {
        return getInterface().isOpened(SERVICE_ID);
    }

    public int getFeatureState() throws RemoteException {
        return ImsFeature.STATE_READY;
    }

    public void addRegistrationListener(IImsRegistrationListener listener) throws RemoteException {
        getInterface().addRegistrationListener(mSlotId, ImsFeature.MMTEL, listener);
    }

    public void removeRegistrationListener(IImsRegistrationListener listener)
            throws RemoteException {
        // Not Implemented in the old ImsService. If the registration listener becomes invalid, the
        // ImsService will remove it.
    }

    public ImsCallProfile createCallProfile(int sessionId, int callSessionType, int callType)
            throws RemoteException {
        return getInterface().createCallProfile(sessionId, callSessionType, callType);
    }

    public IImsCallSession createCallSession(int sessionId, ImsCallProfile profile)
            throws RemoteException {
        return getInterface().createCallSession(sessionId, profile, null);
    }

    public IImsCallSession getPendingCallSession(int sessionId, String callId)
            throws RemoteException {
        return getInterface().getPendingCallSession(sessionId, callId);
    }

    public IImsUt getUtInterface() throws RemoteException {
        return getInterface().getUtInterface(SERVICE_ID);
    }

    public IImsConfig getConfigInterface() throws RemoteException {
        return getInterface().getConfigInterface(mSlotId);
    }

    public void turnOnIms() throws RemoteException {
        getInterface().turnOnIms(mSlotId);
    }

    public void turnOffIms() throws RemoteException {
        getInterface().turnOffIms(mSlotId);
    }

    public IImsEcbm getEcbmInterface() throws RemoteException {
        return getInterface().getEcbmInterface(SERVICE_ID);
    }

    public void setUiTTYMode(int uiTtyMode, Message onComplete) throws RemoteException {
        getInterface().setUiTTYMode(SERVICE_ID, uiTtyMode, onComplete);
    }

    public IImsMultiEndpoint getMultiEndpointInterface() throws RemoteException {
        return getInterface().getMultiEndpointInterface(SERVICE_ID);
    }

    private IImsService getInterface() throws RemoteException {
        IImsService feature = IImsService.Stub.asInterface(mBinder);
        if (feature == null) {
            throw new RemoteException("Binder not Available");
        }
        return feature;
    }
}
