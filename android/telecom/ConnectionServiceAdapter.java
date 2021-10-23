/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.telecom;

import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;

import com.android.internal.telecom.IConnectionServiceAdapter;
import com.android.internal.telecom.RemoteServiceCallback;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides methods for IConnectionService implementations to interact with the system phone app.
 *
 * @hide
 */
final class ConnectionServiceAdapter implements DeathRecipient {
    /**
     * ConcurrentHashMap constructor params: 8 is initial table size, 0.9f is
     * load factor before resizing, 1 means we only expect a single thread to
     * access the map so make only a single shard
     */
    private final Set<IConnectionServiceAdapter> mAdapters = Collections.newSetFromMap(
            new ConcurrentHashMap<IConnectionServiceAdapter, Boolean>(8, 0.9f, 1));

    ConnectionServiceAdapter() {
    }

    void addAdapter(IConnectionServiceAdapter adapter) {
        for (IConnectionServiceAdapter it : mAdapters) {
            if (it.asBinder() == adapter.asBinder()) {
                Log.w(this, "Ignoring duplicate adapter addition.");
                return;
            }
        }
        if (mAdapters.add(adapter)) {
            try {
                adapter.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                mAdapters.remove(adapter);
            }
        }
    }

    void removeAdapter(IConnectionServiceAdapter adapter) {
        if (adapter != null) {
            for (IConnectionServiceAdapter it : mAdapters) {
                if (it.asBinder() == adapter.asBinder() && mAdapters.remove(it)) {
                    adapter.asBinder().unlinkToDeath(this, 0);
                    break;
                }
            }
        }
    }

    /** ${inheritDoc} */
    @Override
    public void binderDied() {
        Iterator<IConnectionServiceAdapter> it = mAdapters.iterator();
        while (it.hasNext()) {
            IConnectionServiceAdapter adapter = it.next();
            if (!adapter.asBinder().isBinderAlive()) {
                it.remove();
                adapter.asBinder().unlinkToDeath(this, 0);
            }
        }
    }

    void handleCreateConnectionComplete(
            String id,
            ConnectionRequest request,
            ParcelableConnection connection) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.handleCreateConnectionComplete(id, request, connection,
                        Log.getExternalSession());
            } catch (RemoteException e) {
            }
        }
    }

    void handleCreateConferenceComplete(
            String id,
            ConnectionRequest request,
            ParcelableConference conference) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.handleCreateConferenceComplete(id, request, conference,
                        Log.getExternalSession());
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Sets a call's state to active (e.g., an ongoing call where two parties can actively
     * communicate).
     *
     * @param callId The unique ID of the call whose state is changing to active.
     */
    void setActive(String callId) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.setActive(callId, Log.getExternalSession());
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Sets a call's state to ringing (e.g., an inbound ringing call).
     *
     * @param callId The unique ID of the call whose state is changing to ringing.
     */
    void setRinging(String callId) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.setRinging(callId, Log.getExternalSession());
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Sets a call's state to dialing (e.g., dialing an outbound call).
     *
     * @param callId The unique ID of the call whose state is changing to dialing.
     */
    void setDialing(String callId) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.setDialing(callId, Log.getExternalSession());
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Sets a call's state to pulling (e.g. a call with {@link Connection#PROPERTY_IS_EXTERNAL_CALL}
     * is being pulled to the local device.
     *
     * @param callId The unique ID of the call whose state is changing to dialing.
     */
    void setPulling(String callId) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.setPulling(callId, Log.getExternalSession());
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Sets a call's state to disconnected.
     *
     * @param callId The unique ID of the call whose state is changing to disconnected.
     * @param disconnectCause The reason for the disconnection, as described by
     *            {@link android.telecomm.DisconnectCause}.
     */
    void setDisconnected(String callId, DisconnectCause disconnectCause) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.setDisconnected(callId, disconnectCause, Log.getExternalSession());
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Sets a call's state to be on hold.
     *
     * @param callId - The unique ID of the call whose state is changing to be on hold.
     */
    void setOnHold(String callId) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.setOnHold(callId, Log.getExternalSession());
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Asks Telecom to start or stop a ringback tone for a call.
     *
     * @param callId The unique ID of the call whose ringback is being changed.
     * @param ringback Whether Telecom should start playing a ringback tone.
     */
    void setRingbackRequested(String callId, boolean ringback) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.setRingbackRequested(callId, ringback, Log.getExternalSession());
            } catch (RemoteException e) {
            }
        }
    }

    void setConnectionCapabilities(String callId, int capabilities) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.setConnectionCapabilities(callId, capabilities, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    void setConnectionProperties(String callId, int properties) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.setConnectionProperties(callId, properties, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Indicates whether or not the specified call is currently conferenced into the specified
     * conference call.
     *
     * @param callId The unique ID of the call being conferenced.
     * @param conferenceCallId The unique ID of the conference call. Null if call is not
     *            conferenced.
     */
    void setIsConferenced(String callId, String conferenceCallId) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                Log.d(this, "sending connection %s with conference %s", callId, conferenceCallId);
                adapter.setIsConferenced(callId, conferenceCallId, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Indicates that the merge request on this call has failed.
     *
     * @param callId The unique ID of the call being conferenced.
     */
    void onConferenceMergeFailed(String callId) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                Log.d(this, "merge failed for call %s", callId);
                adapter.setConferenceMergeFailed(callId, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
        * Resets the cdma connection time.
        */
    void resetConnectionTime(String callId) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.resetConnectionTime(callId, Log.getExternalSession());
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Indicates that the call no longer exists. Can be used with either a call or a conference
     * call.
     *
     * @param callId The unique ID of the call.
     */
    void removeCall(String callId) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.removeCall(callId, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    void onPostDialWait(String callId, String remaining) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.onPostDialWait(callId, remaining, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    void onPostDialChar(String callId, char nextChar) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.onPostDialChar(callId, nextChar, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Indicates that a new conference call has been created.
     *
     * @param callId The unique ID of the conference call.
     */
    void addConferenceCall(String callId, ParcelableConference parcelableConference) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.addConferenceCall(callId, parcelableConference, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Retrieves a list of remote connection services usable to place calls.
     */
    void queryRemoteConnectionServices(RemoteServiceCallback callback, String callingPackage) {
        // Only supported when there is only one adapter.
        if (mAdapters.size() == 1) {
            try {
                mAdapters.iterator().next().queryRemoteConnectionServices(callback, callingPackage,
                        Log.getExternalSession());
            } catch (RemoteException e) {
                Log.e(this, e, "Exception trying to query for remote CSs");
            }
        } else {
            try {
                // This is not an error condition, so just pass back an empty list.
                // This happens when querying from a remote connection service, not the connection
                // manager itself.
                callback.onResult(Collections.EMPTY_LIST, Collections.EMPTY_LIST);
            } catch (RemoteException e) {
                Log.e(this, e, "Exception trying to query for remote CSs");
            }
        }
    }

    /**
     * Sets the call video provider for a call.
     *
     * @param callId The unique ID of the call to set with the given call video provider.
     * @param videoProvider The call video provider instance to set on the call.
     */
    void setVideoProvider(
            String callId, Connection.VideoProvider videoProvider) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.setVideoProvider(
                        callId,
                        videoProvider == null ? null : videoProvider.getInterface(),
                        Log.getExternalSession());
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Requests that the framework use VOIP audio mode for this connection.
     *
     * @param callId The unique ID of the call to set with the given call video provider.
     * @param isVoip True if the audio mode is VOIP.
     */
    void setIsVoipAudioMode(String callId, boolean isVoip) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.setIsVoipAudioMode(callId, isVoip, Log.getExternalSession());
            } catch (RemoteException e) {
            }
        }
    }

    void setStatusHints(String callId, StatusHints statusHints) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.setStatusHints(callId, statusHints, Log.getExternalSession());
            } catch (RemoteException e) {
            }
        }
    }

    void setAddress(String callId, Uri address, int presentation) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.setAddress(callId, address, presentation, Log.getExternalSession());
            } catch (RemoteException e) {
            }
        }
    }

    void setCallerDisplayName(String callId, String callerDisplayName, int presentation) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.setCallerDisplayName(callId, callerDisplayName, presentation,
                        Log.getExternalSession());
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Sets the video state associated with a call.
     *
     * Valid values: {@link VideoProfile#STATE_BIDIRECTIONAL},
     * {@link VideoProfile#STATE_AUDIO_ONLY},
     * {@link VideoProfile#STATE_TX_ENABLED},
     * {@link VideoProfile#STATE_RX_ENABLED}.
     *
     * @param callId The unique ID of the call to set the video state for.
     * @param videoState The video state.
     */
    void setVideoState(String callId, int videoState) {
        Log.v(this, "setVideoState: %d", videoState);
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.setVideoState(callId, videoState, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    void setConferenceableConnections(String callId, List<String> conferenceableCallIds) {
        Log.v(this, "setConferenceableConnections: %s, %s", callId, conferenceableCallIds);
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.setConferenceableConnections(callId, conferenceableCallIds,
                        Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Informs telecom of an existing connection which was added by the {@link ConnectionService}.
     *
     * @param callId The unique ID of the call being added.
     * @param connection The connection.
     */
    void addExistingConnection(String callId, ParcelableConnection connection) {
        Log.v(this, "addExistingConnection: %s", callId);
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.addExistingConnection(callId, connection, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Adds some extras associated with a {@code Connection}.
     *
     * @param callId The unique ID of the call.
     * @param extras The extras to add.
     */
    void putExtras(String callId, Bundle extras) {
        Log.v(this, "putExtras: %s", callId);
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.putExtras(callId, extras, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Adds an extra associated with a {@code Connection}.
     *
     * @param callId The unique ID of the call.
     * @param key The extra key.
     * @param value The extra value.
     */
    void putExtra(String callId, String key, boolean value) {
        Log.v(this, "putExtra: %s %s=%b", callId, key, value);
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                Bundle bundle = new Bundle();
                bundle.putBoolean(key, value);
                adapter.putExtras(callId, bundle, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Adds an extra associated with a {@code Connection}.
     *
     * @param callId The unique ID of the call.
     * @param key The extra key.
     * @param value The extra value.
     */
    void putExtra(String callId, String key, int value) {
        Log.v(this, "putExtra: %s %s=%d", callId, key, value);
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                Bundle bundle = new Bundle();
                bundle.putInt(key, value);
                adapter.putExtras(callId, bundle, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Adds an extra associated with a {@code Connection}.
     *
     * @param callId The unique ID of the call.
     * @param key The extra key.
     * @param value The extra value.
     */
    void putExtra(String callId, String key, String value) {
        Log.v(this, "putExtra: %s %s=%s", callId, key, value);
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                Bundle bundle = new Bundle();
                bundle.putString(key, value);
                adapter.putExtras(callId, bundle, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Removes extras associated with a {@code Connection}.
     *  @param callId The unique ID of the call.
     * @param keys The extra keys to remove.
     */
    void removeExtras(String callId, List<String> keys) {
        Log.v(this, "removeExtras: %s %s", callId, keys);
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.removeExtras(callId, keys, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Sets the audio route associated with a {@link Connection}.
     *
     * @param callId The unique ID of the call.
     * @param audioRoute The new audio route (see {@code CallAudioState#ROUTE_*}).
     */
    void setAudioRoute(String callId, int audioRoute, String bluetoothAddress) {
        Log.v(this, "setAudioRoute: %s %s %s", callId,
                CallAudioState.audioRouteToString(audioRoute),
                bluetoothAddress);
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.setAudioRoute(callId, audioRoute,
                        bluetoothAddress, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }


    /**
     * Informs Telecom of a connection level event.
     *
     * @param callId The unique ID of the call.
     * @param event The event.
     * @param extras Extras associated with the event.
     */
    void onConnectionEvent(String callId, String event, Bundle extras) {
        Log.v(this, "onConnectionEvent: %s", event);
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.onConnectionEvent(callId, event, extras, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Notifies Telecom that an RTT session was successfully established.
     *
     * @param callId The unique ID of the call.
     */
    void onRttInitiationSuccess(String callId) {
        Log.v(this, "onRttInitiationSuccess: %s", callId);
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.onRttInitiationSuccess(callId, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Notifies Telecom that a requested RTT session failed to be established.
     *
     * @param callId The unique ID of the call.
     */
    void onRttInitiationFailure(String callId, int reason) {
        Log.v(this, "onRttInitiationFailure: %s", callId);
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.onRttInitiationFailure(callId, reason, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Notifies Telecom that an established RTT session was terminated by the remote user on
     * the call.
     *
     * @param callId The unique ID of the call.
     */
    void onRttSessionRemotelyTerminated(String callId) {
        Log.v(this, "onRttSessionRemotelyTerminated: %s", callId);
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.onRttSessionRemotelyTerminated(callId, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Notifies Telecom that the remote user on the call has requested an upgrade to an RTT
     * session for this call.
     *
     * @param callId The unique ID of the call.
     */
    void onRemoteRttRequest(String callId) {
        Log.v(this, "onRemoteRttRequest: %s", callId);
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.onRemoteRttRequest(callId, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Notifies Telecom that a call's PhoneAccountHandle has changed.
     *
     * @param callId The unique ID of the call.
     * @param pHandle The new PhoneAccountHandle associated with the call.
     */
    void onPhoneAccountChanged(String callId, PhoneAccountHandle pHandle) {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                Log.d(this, "onPhoneAccountChanged %s", callId);
                adapter.onPhoneAccountChanged(callId, pHandle, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Notifies Telecom that the {@link ConnectionService} has released the call resource.
     */
    void onConnectionServiceFocusReleased() {
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                Log.d(this, "onConnectionServiceFocusReleased");
                adapter.onConnectionServiceFocusReleased(Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Sets whether a conference is treated as a conference or a single party call.
     * See {@link Conference#setConferenceState(boolean)} for more information.
     *
     * @param callId The ID of the telecom call.
     * @param isConference {@code true} if this call should be treated as a conference,
     * {@code false} otherwise.
     */
    void setConferenceState(String callId, boolean isConference) {
        Log.v(this, "setConferenceState: %s %b", callId, isConference);
        for (IConnectionServiceAdapter adapter : mAdapters) {
            try {
                adapter.setConferenceState(callId, isConference, Log.getExternalSession());
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Sets the direction of a call. Setting a new direction of an existing call is usually only
     * applicable during single caller emulation during conferencing, see
     * {@link Conference#setConferenceState(boolean)} for more information.
     * @param callId The identifier of the call.
     * @param direction The new direction of the call.
     */
    void setCallDirection(String callId, @Call.Details.CallDirection int direction) {
        for (IConnectionServiceAdapter a : mAdapters) {
            try {
                a.setCallDirection(callId, direction, Log.getExternalSession());
            } catch (RemoteException e) {
            }
        }
    }
}
