/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.telephony.imsphone;

import android.telephony.Rlog;
import android.telephony.DisconnectCause;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.ims.ImsCall;
import com.android.ims.ImsException;
import com.android.ims.ImsStreamMediaProfile;

import java.util.List;

/**
 * {@hide}
 */
public class ImsPhoneCall extends Call {
    /*************************** Instance Variables **************************/

    private static final String LOG_TAG = "ImsPhoneCall";

    /*package*/ ImsPhoneCallTracker mOwner;

    private boolean mRingbackTonePlayed = false;

    /****************************** Constructors *****************************/
    /*package*/
    ImsPhoneCall() {
    }

    /*package*/
    ImsPhoneCall(ImsPhoneCallTracker owner) {
        mOwner = owner;
    }

    public void dispose() {
        try {
            mOwner.hangup(this);
        } catch (CallStateException ex) {
            //Rlog.e(LOG_TAG, "dispose: unexpected error on hangup", ex);
            //while disposing, ignore the exception and clean the connections
        } finally {
            for(int i = 0, s = mConnections.size(); i < s; i++) {
                ImsPhoneConnection c = (ImsPhoneConnection) mConnections.get(i);
                c.onDisconnect(DisconnectCause.LOST_SIGNAL);
            }
        }
    }

    /************************** Overridden from Call *************************/

    @Override
    public List<Connection>
    getConnections() {
        return mConnections;
    }

    @Override
    public Phone
    getPhone() {
        return mOwner.mPhone;
    }

    @Override
    public boolean
    isMultiparty() {
        return mConnections.size() > 1;
    }

    /** Please note: if this is the foreground call and a
     *  background call exists, the background call will be resumed.
     */
    @Override
    public void
    hangup() throws CallStateException {
        mOwner.hangup(this);
    }

    @Override
    public String
    toString() {
        return mState.toString();
    }

    //***** Called from ImsPhoneConnection

    /*package*/ void
    attach(Connection conn) {
        clearDisconnected();
        mConnections.add(conn);
    }

    /*package*/ void
    attach(Connection conn, State state) {
        this.attach(conn);
        mState = state;
    }

    /*package*/ void
    attachFake(Connection conn, State state) {
        attach(conn, state);
    }

    /**
     * Called by ImsPhoneConnection when it has disconnected
     */
    boolean
    connectionDisconnected(ImsPhoneConnection conn) {
        if (mState != State.DISCONNECTED) {
            /* If only disconnected connections remain, we are disconnected*/

            boolean hasOnlyDisconnectedConnections = true;

            for (int i = 0, s = mConnections.size()  ; i < s; i ++) {
                if (mConnections.get(i).getState() != State.DISCONNECTED) {
                    hasOnlyDisconnectedConnections = false;
                    break;
                }
            }

            if (hasOnlyDisconnectedConnections) {
                mState = State.DISCONNECTED;
                return true;
            }
        }

        return false;
    }

    /*package*/ void
    detach(ImsPhoneConnection conn) {
        mConnections.remove(conn);

        if (mConnections.size() == 0) {
            mState = State.IDLE;
        }
    }

    /**
     * @return true if there's no space in this call for additional
     * connections to be added via "conference"
     */
    /*package*/ boolean
    isFull() {
        return mConnections.size() == ImsPhoneCallTracker.MAX_CONNECTIONS_PER_CALL;
    }

    //***** Called from ImsPhoneCallTracker
    /**
     * Called when this Call is being hung up locally (eg, user pressed "end")
     */
    void
    onHangupLocal() {
        for (int i = 0, s = mConnections.size(); i < s; i++) {
            ImsPhoneConnection cn = (ImsPhoneConnection)mConnections.get(i);
            cn.onHangupLocal();
        }
        mState = State.DISCONNECTING;
    }

    /**
     * Called when it's time to clean up disconnected Connection objects
     */
    void
    clearDisconnected() {
        for (int i = mConnections.size() - 1 ; i >= 0 ; i--) {
            ImsPhoneConnection cn = (ImsPhoneConnection)mConnections.get(i);

            if (cn.getState() == State.DISCONNECTED) {
                mConnections.remove(i);
            }
        }

        if (mConnections.size() == 0) {
            mState = State.IDLE;
        }
    }

    /*package*/ ImsPhoneConnection
    getFirstConnection() {
        if (mConnections.size() == 0) return null;

        return (ImsPhoneConnection) mConnections.get(0);
    }

    /*package*/ void
    setMute(boolean mute) {
        ImsCall imsCall = getFirstConnection() == null ?
                null : getFirstConnection().getImsCall();
        if (imsCall != null) {
            try {
                imsCall.setMute(mute);
            } catch (ImsException e) {
                Rlog.e(LOG_TAG, "setMute failed : " + e.getMessage());
            }
        }
    }

    /* package */ void
    merge(ImsPhoneCall that, State state) {
        ImsPhoneConnection[] cc = that.mConnections.toArray(
                new ImsPhoneConnection[that.mConnections.size()]);
        for (ImsPhoneConnection c : cc) {
            c.update(null, state);
        }
    }

    /*package*/ ImsCall
    getImsCall() {
        return (getFirstConnection() == null) ? null : getFirstConnection().getImsCall();
    }

    /*package*/ static boolean isLocalTone(ImsCall imsCall) {
        if ((imsCall == null) || (imsCall.getCallProfile() == null)
                || (imsCall.getCallProfile().mMediaProfile == null)) {
            return false;
        }

        ImsStreamMediaProfile mediaProfile = imsCall.getCallProfile().mMediaProfile;

        return (mediaProfile.mAudioDirection == ImsStreamMediaProfile.DIRECTION_INACTIVE)
                ? true : false;
    }

    /*package*/ boolean
    update (ImsPhoneConnection conn, ImsCall imsCall, State state) {
        State newState = state;
        boolean changed = false;

        //ImsCall.Listener.onCallProgressing can be invoked several times
        //and ringback tone mode can be changed during the call setup procedure
        if (state == State.ALERTING) {
            if (mRingbackTonePlayed && !isLocalTone(imsCall)) {
                mOwner.mPhone.stopRingbackTone();
                mRingbackTonePlayed = false;
            } else if (!mRingbackTonePlayed && isLocalTone(imsCall)) {
                mOwner.mPhone.startRingbackTone();
                mRingbackTonePlayed = true;
            }
        } else {
            if (mRingbackTonePlayed) {
                mOwner.mPhone.stopRingbackTone();
                mRingbackTonePlayed = false;
            }
        }

        if ((newState != mState) && (state != State.DISCONNECTED)) {
            mState = newState;
            changed = true;
        } else if (state == State.DISCONNECTED) {
            changed = true;
        }

        return changed;
    }

    /* package */ ImsPhoneConnection
    getHandoverConnection() {
        ImsPhoneConnection conn = (ImsPhoneConnection) getEarliestConnection();
        if (conn != null) {
            conn.setMultiparty(isMultiparty());
        }
        return conn;
    }

    void switchWith(ImsPhoneCall that) {
        synchronized (ImsPhoneCall.class) {
            ImsPhoneCall tmp = new ImsPhoneCall();
            tmp.takeOver(this);
            this.takeOver(that);
            that.takeOver(tmp);
        }
    }

    private void takeOver(ImsPhoneCall that) {
        mConnections = that.mConnections;
        mState = that.mState;
        for (Connection c : mConnections) {
            ((ImsPhoneConnection) c).changeParent(this);
        }
    }
}
