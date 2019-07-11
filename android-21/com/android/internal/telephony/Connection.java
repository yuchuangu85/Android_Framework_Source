/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import android.os.SystemClock;
import android.telephony.Rlog;
import android.util.Log;

import java.lang.Override;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * {@hide}
 */
public abstract class Connection {
    public interface PostDialListener {
        void onPostDialWait();
    }

    /**
     * Listener interface for events related to the connection which should be reported to the
     * {@link android.telecom.Connection}.
     */
    public interface Listener {
        public void onVideoStateChanged(int videoState);
        public void onLocalVideoCapabilityChanged(boolean capable);
        public void onRemoteVideoCapabilityChanged(boolean capable);
        public void onVideoProviderChanged(
                android.telecom.Connection.VideoProvider videoProvider);
        public void onAudioQualityChanged(int audioQuality);
    }

    /**
     * Base listener implementation.
     */
    public abstract static class ListenerBase implements Listener {
        @Override
        public void onVideoStateChanged(int videoState) {}
        @Override
        public void onLocalVideoCapabilityChanged(boolean capable) {}
        @Override
        public void onRemoteVideoCapabilityChanged(boolean capable) {}
        @Override
        public void onVideoProviderChanged(
                android.telecom.Connection.VideoProvider videoProvider) {}
        @Override
        public void onAudioQualityChanged(int audioQuality) {}
    }

    public static final int AUDIO_QUALITY_STANDARD = 1;
    public static final int AUDIO_QUALITY_HIGH_DEFINITION = 2;

    //Caller Name Display
    protected String mCnapName;
    protected int mCnapNamePresentation  = PhoneConstants.PRESENTATION_ALLOWED;
    protected String mAddress;     // MAY BE NULL!!!
    protected String mDialString;          // outgoing calls only
    protected int mNumberPresentation = PhoneConstants.PRESENTATION_ALLOWED;
    protected boolean mIsIncoming;
    /*
     * These time/timespan values are based on System.currentTimeMillis(),
     * i.e., "wall clock" time.
     */
    protected long mCreateTime;
    protected long mConnectTime;
    /*
     * These time/timespan values are based on SystemClock.elapsedRealTime(),
     * i.e., time since boot.  They are appropriate for comparison and
     * calculating deltas.
     */
    protected long mConnectTimeReal;
    protected long mDuration;
    protected long mHoldingStartTime;  // The time when the Connection last transitioned
                            // into HOLDING
    protected Connection mOrigConnection;
    private List<PostDialListener> mPostDialListeners = new ArrayList<>();
    public Set<Listener> mListeners = new CopyOnWriteArraySet<>();

    protected boolean mNumberConverted = false;
    protected String mConvertedNumber;

    private static String LOG_TAG = "Connection";

    Object mUserData;
    private int mVideoState;
    private boolean mLocalVideoCapable;
    private boolean mRemoteVideoCapable;
    private int mAudioQuality;
    private android.telecom.Connection.VideoProvider mVideoProvider;

    /* Instance Methods */

    /**
     * Gets address (e.g. phone number) associated with connection.
     * TODO: distinguish reasons for unavailability
     *
     * @return address or null if unavailable
     */

    public String getAddress() {
        return mAddress;
    }

    /**
     * Gets CNAP name associated with connection.
     * @return cnap name or null if unavailable
     */
    public String getCnapName() {
        return mCnapName;
    }

    /**
     * Get original dial string.
     * @return original dial string or null if unavailable
     */
    public String getOrigDialString(){
        return null;
    }

    /**
     * Gets CNAP presentation associated with connection.
     * @return cnap name or null if unavailable
     */

    public int getCnapNamePresentation() {
       return mCnapNamePresentation;
    }

    /**
     * @return Call that owns this Connection, or null if none
     */
    public abstract Call getCall();

    /**
     * Connection create time in currentTimeMillis() format
     * Basically, set when object is created.
     * Effectively, when an incoming call starts ringing or an
     * outgoing call starts dialing
     */
    public long getCreateTime() {
        return mCreateTime;
    }

    /**
     * Connection connect time in currentTimeMillis() format.
     * For outgoing calls: Begins at (DIALING|ALERTING) -> ACTIVE transition.
     * For incoming calls: Begins at (INCOMING|WAITING) -> ACTIVE transition.
     * Returns 0 before then.
     */
    public long getConnectTime() {
        return mConnectTime;
    }

    /**
     * Connection connect time in elapsedRealtime() format.
     * For outgoing calls: Begins at (DIALING|ALERTING) -> ACTIVE transition.
     * For incoming calls: Begins at (INCOMING|WAITING) -> ACTIVE transition.
     * Returns 0 before then.
     */
    public long getConnectTimeReal() {
        return mConnectTimeReal;
    }

    /**
     * Disconnect time in currentTimeMillis() format.
     * The time when this Connection makes a transition into ENDED or FAIL.
     * Returns 0 before then.
     */
    public abstract long getDisconnectTime();

    /**
     * Returns the number of milliseconds the call has been connected,
     * or 0 if the call has never connected.
     * If the call is still connected, then returns the elapsed
     * time since connect.
     */
    public long getDurationMillis() {
        if (mConnectTimeReal == 0) {
            return 0;
        } else if (mDuration == 0) {
            return SystemClock.elapsedRealtime() - mConnectTimeReal;
        } else {
            return mDuration;
        }
    }

    /**
     * The time when this Connection last transitioned into HOLDING
     * in elapsedRealtime() format.
     * Returns 0, if it has never made a transition into HOLDING.
     */
    public long getHoldingStartTime() {
        return mHoldingStartTime;
    }

    /**
     * If this connection is HOLDING, return the number of milliseconds
     * that it has been on hold for (approximately).
     * If this connection is in any other state, return 0.
     */

    public abstract long getHoldDurationMillis();

    /**
     * Returns call disconnect cause. Values are defined in
     * {@link android.telephony.DisconnectCause}. If the call is not yet
     * disconnected, NOT_DISCONNECTED is returned.
     */
    public abstract int getDisconnectCause();

    /**
     * Returns true of this connection originated elsewhere
     * ("MT" or mobile terminated; another party called this terminal)
     * or false if this call originated here (MO or mobile originated).
     */
    public boolean isIncoming() {
        return mIsIncoming;
    }

    /**
     * If this Connection is connected, then it is associated with
     * a Call.
     *
     * Returns getCall().getState() or Call.State.IDLE if not
     * connected
     */
    public Call.State getState() {
        Call c;

        c = getCall();

        if (c == null) {
            return Call.State.IDLE;
        } else {
            return c.getState();
        }
    }

    /**
     * isAlive()
     *
     * @return true if the connection isn't disconnected
     * (could be active, holding, ringing, dialing, etc)
     */
    public boolean
    isAlive() {
        return getState().isAlive();
    }

    /**
     * Returns true if Connection is connected and is INCOMING or WAITING
     */
    public boolean
    isRinging() {
        return getState().isRinging();
    }

    /**
     *
     * @return the userdata set in setUserData()
     */
    public Object getUserData() {
        return mUserData;
    }

    /**
     *
     * @param userdata user can store an any userdata in the Connection object.
     */
    public void setUserData(Object userdata) {
        mUserData = userdata;
    }

    /**
     * Hangup individual Connection
     */
    public abstract void hangup() throws CallStateException;

    /**
     * Separate this call from its owner Call and assigns it to a new Call
     * (eg if it is currently part of a Conference call
     * TODO: Throw exception? Does GSM require error display on failure here?
     */
    public abstract void separate() throws CallStateException;

    public enum PostDialState {
        NOT_STARTED,    /* The post dial string playback hasn't
                           been started, or this call is not yet
                           connected, or this is an incoming call */
        STARTED,        /* The post dial string playback has begun */
        WAIT,           /* The post dial string playback is waiting for a
                           call to proceedAfterWaitChar() */
        WILD,           /* The post dial string playback is waiting for a
                           call to proceedAfterWildChar() */
        COMPLETE,       /* The post dial string playback is complete */
        CANCELLED,       /* The post dial string playback was cancelled
                           with cancelPostDial() */
        PAUSE           /* The post dial string playback is pausing for a
                           call to processNextPostDialChar*/
    }

    public void clearUserData(){
        mUserData = null;
    }

    public final void addPostDialListener(PostDialListener listener) {
        if (!mPostDialListeners.contains(listener)) {
            mPostDialListeners.add(listener);
        }
    }

    protected final void clearPostDialListeners() {
        mPostDialListeners.clear();
    }

    protected final void notifyPostDialListeners() {
        if (getPostDialState() == PostDialState.WAIT) {
            for (PostDialListener listener : new ArrayList<>(mPostDialListeners)) {
                listener.onPostDialWait();
            }
        }
    }

    public abstract PostDialState getPostDialState();

    /**
     * Returns the portion of the post dial string that has not
     * yet been dialed, or "" if none
     */
    public abstract String getRemainingPostDialString();

    /**
     * See Phone.setOnPostDialWaitCharacter()
     */

    public abstract void proceedAfterWaitChar();

    /**
     * See Phone.setOnPostDialWildCharacter()
     */
    public abstract void proceedAfterWildChar(String str);
    /**
     * Cancel any post
     */
    public abstract void cancelPostDial();

    /**
     * Returns the caller id presentation type for incoming and waiting calls
     * @return one of PRESENTATION_*
     */
    public abstract int getNumberPresentation();

    /**
     * Returns the User to User Signaling (UUS) information associated with
     * incoming and waiting calls
     * @return UUSInfo containing the UUS userdata.
     */
    public abstract UUSInfo getUUSInfo();

    /**
     * Returns the CallFail reason provided by the RIL with the result of
     * RIL_REQUEST_LAST_CALL_FAIL_CAUSE
     */
    public abstract int getPreciseDisconnectCause();

    /**
     * Returns the original Connection instance associated with
     * this Connection
     */
    public Connection getOrigConnection() {
        return mOrigConnection;
    }

    /**
     * Returns whether the original ImsPhoneConnection was a member
     * of a conference call
     * @return valid only when getOrigConnection() is not null
     */
    public abstract boolean isMultiparty();

    public void migrateFrom(Connection c) {
        if (c == null) return;
        mListeners = c.mListeners;
        mAddress = c.getAddress();
        mNumberPresentation = c.getNumberPresentation();
        mDialString = c.getOrigDialString();
        mCnapName = c.getCnapName();
        mCnapNamePresentation = c.getCnapNamePresentation();
        mIsIncoming = c.isIncoming();
        mCreateTime = c.getCreateTime();
        mConnectTime = c.getConnectTime();
        mConnectTimeReal = c.getConnectTimeReal();
        mHoldingStartTime = c.getHoldingStartTime();
        mOrigConnection = c.getOrigConnection();
    }

    /**
     * Assign a listener to be notified of state changes.
     *
     * @param listener A listener.
     */
    public final void addListener(Listener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a listener.
     *
     * @param listener A listener.
     */
    public final void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    /**
     * Returns the current video state of the connection.
     *
     * @return The video state of the connection.
     */
    public int getVideoState() {
        return mVideoState;
    }

    /**
     * Returns the local video capability state for the connection.
     *
     * @return {@code True} if the connection has local video capabilities.
     */
    public boolean isLocalVideoCapable() {
        return mLocalVideoCapable;
    }

    /**
     * Returns the remote video capability state for the connection.
     *
     * @return {@code True} if the connection has remote video capabilities.
     */
    public boolean isRemoteVideoCapable() {
        return mRemoteVideoCapable;
    }

    /**
     * Returns the {@link android.telecom.Connection.VideoProvider} for the connection.
     *
     * @return The {@link android.telecom.Connection.VideoProvider}.
     */
    public android.telecom.Connection.VideoProvider getVideoProvider() {
        return mVideoProvider;
    }

    /**
     * Returns the audio-quality for the connection.
     *
     * @return The audio quality for the connection.
     */
    public int getAudioQuality() {
        return mAudioQuality;
    }

    /**
     * Sets the videoState for the current connection and reports the changes to all listeners.
     * Valid video states are defined in {@link android.telecom.VideoProfile}.
     *
     * @return The video state.
     */
    public void setVideoState(int videoState) {
        mVideoState = videoState;
        for (Listener l : mListeners) {
            l.onVideoStateChanged(mVideoState);
        }
    }

    /**
     * Sets whether video capability is present locally.
     *
     * @param capable {@code True} if video capable.
     */
    public void setLocalVideoCapable(boolean capable) {
        mLocalVideoCapable = capable;
        for (Listener l : mListeners) {
            l.onLocalVideoCapabilityChanged(mLocalVideoCapable);
        }
    }

    /**
     * Sets whether video capability is present remotely.
     *
     * @param capable {@code True} if video capable.
     */
    public void setRemoteVideoCapable(boolean capable) {
        mRemoteVideoCapable = capable;
        for (Listener l : mListeners) {
            l.onRemoteVideoCapabilityChanged(mRemoteVideoCapable);
        }
    }

    /**
     * Set the audio quality for the connection.
     *
     * @param audioQuality The audio quality.
     */
    public void setAudioQuality(int audioQuality) {
        mAudioQuality = audioQuality;
        for (Listener l : mListeners) {
            l.onAudioQualityChanged(mAudioQuality);
        }
    }

    /**
     * Sets the {@link android.telecom.Connection.VideoProvider} for the connection.
     *
     * @param videoProvider The video call provider.
     */
    public void setVideoProvider(android.telecom.Connection.VideoProvider videoProvider) {
        mVideoProvider = videoProvider;
        for (Listener l : mListeners) {
            l.onVideoProviderChanged(mVideoProvider);
        }
    }

    public void setConverted(String oriNumber) {
        mNumberConverted = true;
        mConvertedNumber = mAddress;
        mAddress = oriNumber;
        mDialString = oriNumber;
    }

    /**
     * Build a human representation of a connection instance, suitable for debugging.
     * Don't log personal stuff unless in debug mode.
     * @return a string representing the internal state of this connection.
     */
    public String toString() {
        StringBuilder str = new StringBuilder(128);

        if (Rlog.isLoggable(LOG_TAG, Log.DEBUG)) {
            str.append("addr: " + getAddress())
                    .append(" pres.: " + getNumberPresentation())
                    .append(" dial: " + getOrigDialString())
                    .append(" postdial: " + getRemainingPostDialString())
                    .append(" cnap name: " + getCnapName())
                    .append("(" + getCnapNamePresentation() + ")");
        }
        str.append(" incoming: " + isIncoming())
                .append(" state: " + getState())
                .append(" post dial state: " + getPostDialState());
        return str.toString();
    }
}
