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
 * limitations under the License
 */

package com.android.internal.telephony.imsphone;

import com.android.ims.ImsCallProfile;
import com.android.ims.ImsExternalCallState;
import com.android.ims.ImsExternalCallStateListener;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;

import android.os.Bundle;
import android.telecom.PhoneAccountHandle;
import android.util.ArrayMap;
import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Responsible for tracking external calls known to the system.
 */
public class ImsExternalCallTracker {

    /**
     * Implements the {@link ImsExternalCallStateListener}, which is responsible for receiving
     * external call state updates from the IMS framework.
     */
    public class ExternalCallStateListener extends ImsExternalCallStateListener {
        @Override
        public void onImsExternalCallStateUpdate(List<ImsExternalCallState> externalCallState) {
            refreshExternalCallState(externalCallState);
        }
    }

    /**
     * Receives callbacks from {@link ImsExternalConnection}s when a call pull has been initiated.
     */
    public class ExternalConnectionListener implements ImsExternalConnection.Listener {
        @Override
        public void onPullExternalCall(ImsExternalConnection connection) {
            Log.d(TAG, "onPullExternalCall: connection = " + connection);
            mCallPuller.pullExternalCall(connection.getAddress(), connection.getVideoState());
        }
    }

    public final static String TAG = "ImsExternalCallTracker";

    /**
     * Extra key used when informing telecom of a new external call using the
     * {@link android.telecom.TelecomManager#addNewUnknownCall(PhoneAccountHandle, Bundle)} API.
     * Used to ensure that when Telecom requests the {@link android.telecom.ConnectionService} to
     * create the connection for the unknown call that we can determine which
     * {@link ImsExternalConnection} in {@link #mExternalConnections} is the one being requested.
     */
    public final static String EXTRA_IMS_EXTERNAL_CALL_ID =
            "android.telephony.ImsExternalCallTracker.extra.EXTERNAL_CALL_ID";

    /**
     * Contains a list of the external connections known by the ImsPhoneCallTracker.  These are
     * connections which originated from a dialog event package and reside on another device.
     * Used in multi-endpoint (VoLTE for internet connected endpoints) scenarios.
     */
    private Map<Integer, ImsExternalConnection> mExternalConnections =
            new ArrayMap<>();
    private final ImsPhone mPhone;
    private final ExternalCallStateListener mExternalCallStateListener;
    private final ExternalConnectionListener mExternalConnectionListener =
            new ExternalConnectionListener();
    private final ImsPullCall mCallPuller;

    public ImsExternalCallTracker(ImsPhone phone, ImsPullCall callPuller) {
        mPhone = phone;
        mExternalCallStateListener = new ExternalCallStateListener();
        mCallPuller = callPuller;
    }

    public ExternalCallStateListener getExternalCallStateListener() {
        return mExternalCallStateListener;
    }

    /**
     * Called when the IMS stack receives a new dialog event package.  Triggers the creation and
     * update of {@link ImsExternalConnection}s to represent the dialogs in the dialog event
     * package data.
     *
     * @param externalCallStates the {@link ImsExternalCallState} information for the dialog event
     *                           package.
     */
    public void refreshExternalCallState(List<ImsExternalCallState> externalCallStates) {
        Log.d(TAG, "refreshExternalCallState: depSize = " + externalCallStates.size());

        // Check to see if any call Ids are no longer present in the external call state.  If they
        // are, the calls are terminated and should be removed.
        Iterator<Map.Entry<Integer, ImsExternalConnection>> connectionIterator =
                mExternalConnections.entrySet().iterator();
        boolean wasCallRemoved = false;
        while (connectionIterator.hasNext()) {
            Map.Entry<Integer, ImsExternalConnection> entry = connectionIterator.next();
            int callId = entry.getKey().intValue();

            if (!containsCallId(externalCallStates, callId)) {
                ImsExternalConnection externalConnection = entry.getValue();
                externalConnection.setTerminated();
                externalConnection.removeListener(mExternalConnectionListener);
                connectionIterator.remove();
                wasCallRemoved = true;
            }
        }
        // If one or more calls were removed, trigger a notification that will cause the
        // TelephonyConnection instancse to refresh their state with Telecom.
        if (wasCallRemoved) {
            mPhone.notifyPreciseCallStateChanged();
        }

        // Check for new calls, and updates to existing ones.
        for (ImsExternalCallState callState : externalCallStates) {
            if (!mExternalConnections.containsKey(callState.getCallId())) {
                Log.d(TAG, "refreshExternalCallState: got = " + callState);
                // If there is a new entry and it is already terminated, don't bother adding it to
                // telecom.
                if (callState.getCallState() != ImsExternalCallState.CALL_STATE_CONFIRMED) {
                    continue;
                }
                createExternalConnection(callState);
            } else{
                updateExistingConnection(mExternalConnections.get(callState.getCallId()),
                        callState);
            }
        }
    }

    /**
     * Finds an external connection given a call Id.
     *
     * @param callId The call Id.
     * @return The {@link Connection}, or {@code null} if no match found.
     */
    public Connection getConnectionById(int callId) {
        return mExternalConnections.get(callId);
    }

    /**
     * Given an {@link ImsExternalCallState} instance obtained from a dialog event package,
     * creates a new instance of {@link ImsExternalConnection} to represent the connection, and
     * initiates the addition of the new call to Telecom as an unknown call.
     *
     * @param state External call state from a dialog event package.
     */
    private void createExternalConnection(ImsExternalCallState state) {
        Log.i(TAG, "createExternalConnection");

        ImsExternalConnection connection = new ImsExternalConnection(mPhone,
                state.getCallId(), /* Dialog event package call id */
                state.getAddress().getSchemeSpecificPart() /* phone number */,
                state.isCallPullable());
        connection.setVideoState(ImsCallProfile.getVideoStateFromCallType(state.getCallType()));
        connection.addListener(mExternalConnectionListener);

        // Add to list of tracked connections.
        mExternalConnections.put(connection.getCallId(), connection);

        // Note: The notification of unknown connection is ultimately handled by
        // PstnIncomingCallNotifier#addNewUnknownCall.  That method will ensure that an extra is set
        // containing the ImsExternalConnection#mCallId so that we have a means of reconciling which
        // unknown call was added.
        mPhone.notifyUnknownConnection(connection);
    }

    /**
     * Given an existing {@link ImsExternalConnection}, applies any changes found found in a
     * {@link ImsExternalCallState} instance received from a dialog event package to the connection.
     *
     * @param connection The connection to apply changes to.
     * @param state The new dialog state for the connection.
     */
    private void updateExistingConnection(ImsExternalConnection connection,
            ImsExternalCallState state) {
        Call.State existingState = connection.getState();
        Call.State newState = state.getCallState() == ImsExternalCallState.CALL_STATE_CONFIRMED ?
                Call.State.ACTIVE : Call.State.DISCONNECTED;

        if (existingState != newState) {
            if (newState == Call.State.ACTIVE) {
                connection.setActive();
            } else {
                connection.setTerminated();
                connection.removeListener(mExternalConnectionListener);
                mExternalConnections.remove(connection);
                mPhone.notifyPreciseCallStateChanged();
            }
        }

        connection.setIsPullable(state.isCallPullable());

        int newVideoState = ImsCallProfile.getVideoStateFromCallType(state.getCallType());
        if (newVideoState != connection.getVideoState()) {
            connection.setVideoState(newVideoState);
        }
    }

    /**
     * Determines if a list of call states obtained from a dialog event package contacts an existing
     * call Id.
     *
     * @param externalCallStates The dialog event package state information.
     * @param callId The call Id.
     * @return {@code true} if the state information contains the call Id, {@code false} otherwise.
     */
    private boolean containsCallId(List<ImsExternalCallState> externalCallStates, int callId) {
        for (ImsExternalCallState state : externalCallStates) {
            if (state.getCallId() == callId) {
                return true;
            }
        }

        return false;
    }
}
