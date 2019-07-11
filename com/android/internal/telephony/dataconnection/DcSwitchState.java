/*
* Copyright (C) 2011-2014 MediaTek Inc.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.internal.telephony.dataconnection;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneProxy;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.text.TextUtils;

import android.util.Log;

import java.util.HashSet;
import java.util.Iterator;

public class DcSwitchState extends StateMachine {
    private static final boolean DBG = true;
    private static final boolean VDBG = false;
    private static final String LOG_TAG = "DcSwitchState";

    // ***** Event codes for driving the state machine
    private static final int BASE = Protocol.BASE_DATA_CONNECTION_TRACKER + 0x00001000;
    private static final int EVENT_CONNECT = BASE + 0;
    private static final int EVENT_DISCONNECT = BASE + 1;
    private static final int EVENT_CLEANUP_ALL = BASE + 2;
    private static final int EVENT_CONNECTED = BASE + 3;
    private static final int EVENT_DETACH_DONE = BASE + 4;
    private static final int EVENT_TO_IDLE_DIRECTLY = BASE + 5;
    private static final int EVENT_TO_ACTING_DIRECTLY = BASE + 6;

    private int mId;
    private Phone mPhone;
    private AsyncChannel mAc;
    private RegistrantList mIdleRegistrants = new RegistrantList();
    private HashSet<String> mApnTypes = new HashSet<String>();

    private IdleState     mIdleState = new IdleState();
    private ActingState   mActingState = new ActingState();
    private ActedState    mActedState = new ActedState();
    private DeactingState mDeactingState = new DeactingState();
    private DefaultState  mDefaultState = new DefaultState();

    protected DcSwitchState(Phone phone, String name, int id) {
        super(name);
        if (DBG) log("DcSwitchState constructor E");
        mPhone = phone;
        mId = id;

        addState(mDefaultState);
        addState(mIdleState, mDefaultState);
        addState(mActingState, mDefaultState);
        addState(mActedState, mDefaultState);
        addState(mDeactingState, mDefaultState);
        setInitialState(mIdleState);

        if (DBG) log("DcSwitchState constructor X");
    }

    private int setupConnection(String type) {
        mApnTypes.add(type);
        log("DcSwitchState:setupConnection type = " + type);
//        return mPhone.enableApnType(type); TODO
        return PhoneConstants.APN_REQUEST_STARTED;
    }

    private int teardownConnection(String type) {
        mApnTypes.remove(type);
        if (mApnTypes.isEmpty()) {
            log("No APN is using, then clean up all");
            // Since last type is removed from mApnTypes and will not be disabled in requestDataIdle()
//            mPhone.disableApnType(type); TODO
            requestDataIdle();
            transitionTo(mDeactingState);
            return PhoneConstants.APN_REQUEST_STARTED;
        } else {
//            return mPhone.disableApnType(type); TODO
            return PhoneConstants.APN_REQUEST_STARTED;
        }
    }

    private void requestDataIdle() {
        if (DBG) log("requestDataIdle is triggered");
        Iterator<String> itrType = mApnTypes.iterator();
        while (itrType.hasNext()) {
//            mPhone.disableApnType(itrType.next()); TODO
        }
        mApnTypes.clear();
        PhoneBase pb = (PhoneBase)((PhoneProxy)mPhone).getActivePhone();
        pb.mCi.setDataAllowed(false, obtainMessage(EVENT_DETACH_DONE));
    }

    public void notifyDataConnection(int phoneId, String state, String reason,
            String apnName, String apnType, boolean unavailable) {
        if (phoneId == mId &&
                TextUtils.equals(state, PhoneConstants.DataState.CONNECTED.toString())) {
            sendMessage(obtainMessage(EVENT_CONNECTED));
        }
    }

    public void cleanupAllConnection() {
        sendMessage(obtainMessage(EVENT_CLEANUP_ALL));
    }

    public void registerForIdle(Handler h, int what, Object obj) {
        Registrant r = new Registrant(h, what, obj);
        mIdleRegistrants.add(r);
    }

    public void unregisterForIdle(Handler h) {
        mIdleRegistrants.remove(h);
    }

    public void transitToIdleState() {
        sendMessage(obtainMessage(EVENT_TO_IDLE_DIRECTLY));
    }

    public void transitToActingState() {
        sendMessage(obtainMessage(EVENT_TO_ACTING_DIRECTLY));
    }

    private class IdleState extends State {
        @Override
        public void enter() {
            mIdleRegistrants.notifyRegistrants();
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcSwitchAsyncChannel.REQ_CONNECT:
                case EVENT_CONNECT: {
                    String type = (String)msg.obj;
                    if (DBG) {
                        log("IdleState: REQ_CONNECT/EVENT_CONNECT(" +
                            msg.what + ") type=" + type);
                    }

                    PhoneBase pb = (PhoneBase)((PhoneProxy)mPhone).getActivePhone();
                    pb.mCi.setDataAllowed(true, null);

                    int result = setupConnection(type);
                    if (msg.what == DcSwitchAsyncChannel.REQ_CONNECT) {
                            mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_CONNECT, result);
                    }
                    transitionTo(mActingState);
                    retVal = HANDLED;
                    break;
                }
                case DcSwitchAsyncChannel.REQ_DISCONNECT: {
                    String type = (String)msg.obj;
                    if (DBG) {
                        log("IdleState: DcSwitchAsyncChannel.REQ_DISCONNECT type=" + type);
                    }
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_DISCONNECT,
                        PhoneConstants.APN_ALREADY_INACTIVE);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_CLEANUP_ALL: {
                    if (DBG) {
                        log("IdleState: EVENT_CLEANUP_ALL" );
                    }
                    requestDataIdle();
                    retVal = HANDLED;
                    break;
                }
                case EVENT_CONNECTED: {
                    if (DBG) {
                        log("IdleState: Receive invalid event EVENT_CONNECTED!");
                    }
                    retVal = HANDLED;
                    break;
                }
                default:
                    if (VDBG) {
                        log("IdleState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private class ActingState extends State {
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcSwitchAsyncChannel.REQ_CONNECT:
                case EVENT_CONNECT: {
                    String type = (String)msg.obj;
                    if (DBG) {
                        log("ActingState: REQ_CONNECT/EVENT_CONNECT(" + msg.what +
                            ") type=" + type);
                    }
                    int result = setupConnection(type);
                    if (msg.what == DcSwitchAsyncChannel.REQ_CONNECT) {
                        mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_CONNECT, result);
                    }
                    retVal = HANDLED;
                    break;
                }
                case DcSwitchAsyncChannel.REQ_DISCONNECT: {
                    String type = (String)msg.obj;
                    if (DBG) {
                        log("ActingState: DcSwitchAsyncChannel.REQ_DISCONNECT type=" + type);
                    }
                    int result = teardownConnection(type);
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_DISCONNECT, result);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_CONNECTED: {
                    if (DBG) {
                        log("ActingState: EVENT_CONNECTED");
                    }
                    transitionTo(mActedState);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_CLEANUP_ALL: {
                    if (DBG) {
                        log("ActingState: EVENT_CLEANUP_ALL" );
                    }
                    requestDataIdle();
                    transitionTo(mDeactingState);
                    retVal = HANDLED;
                    break;
                }
                default:
                    if (VDBG) {
                        log("ActingState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private class ActedState extends State {
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcSwitchAsyncChannel.REQ_CONNECT:
                case EVENT_CONNECT: {
                    String type = (String)msg.obj;
                    if (DBG) {
                        log("ActedState: REQ_CONNECT/EVENT_CONNECT(" + msg.what + ") type=" + type);
                    }
                    int result = setupConnection(type);
                    if (msg.what == DcSwitchAsyncChannel.REQ_CONNECT) {
                        mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_CONNECT, result);
                    }
                    retVal = HANDLED;
                    break;
                }
                case DcSwitchAsyncChannel.REQ_DISCONNECT: {
                    String type = (String)msg.obj;
                    if (DBG) {
                        log("ActedState: DcSwitchAsyncChannel.REQ_DISCONNECT type=" + type);
                    }
                    int result = teardownConnection(type);
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_DISCONNECT, result);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_CONNECTED: {
                    if (DBG) {
                        log("ActedState: EVENT_CONNECTED");
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_CLEANUP_ALL: {
                    if (DBG) {
                        log("ActedState: EVENT_CLEANUP_ALL" );
                    }
                    requestDataIdle();
                    transitionTo(mDeactingState);
                    retVal = HANDLED;
                    break;
                }
                default:
                    if (VDBG) {
                        log("ActingState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private class DeactingState extends State {
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcSwitchAsyncChannel.REQ_CONNECT:
                case EVENT_CONNECT: {
                    String type = (String)msg.obj;
                    if (DBG) {
                        log("DeactingState: REQ_CONNECT/EVENT_CONNECT(" +
                            msg.what + ") type=" + type + ", request is defered.");
                    }
                    deferMessage(obtainMessage(EVENT_CONNECT, type));
                    if (msg.what == DcSwitchAsyncChannel.REQ_CONNECT) {
                        mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_CONNECT,
                                PhoneConstants.APN_REQUEST_STARTED);
                    }
                    retVal = HANDLED;
                    break;
                }
                case DcSwitchAsyncChannel.REQ_DISCONNECT: {
                    String type = (String)msg.obj;
                    if (DBG) {
                        log("DeactingState: DcSwitchAsyncChannel.REQ_DISCONNECT type=" + type);
                    }
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_DISCONNECT,
                            PhoneConstants.APN_ALREADY_INACTIVE);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DETACH_DONE: {
                    if (DBG) {
                        log("DeactingState: EVENT_DETACH_DONE");
                    }
                    transitionTo(mIdleState);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_CONNECTED: {
                    if (DBG) {
                        log("DeactingState: Receive invalid event EVENT_CONNECTED!");
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_CLEANUP_ALL: {
                    if (DBG) {
                        log("DeactingState: EVENT_CLEANUP_ALL, already deacting." );
                    }
                    retVal = HANDLED;
                    break;
                }
                default:
                    if (VDBG) {
                        log("DeactingState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private class DefaultState extends State {
        @Override
        public boolean processMessage(Message msg) {
            AsyncResult ar;

            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                    if (mAc != null) {
                        if (VDBG) log("Disconnecting to previous connection mAc=" + mAc);
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_FULL_CONNECTION_REFUSED_ALREADY_CONNECTED);
                    } else {
                        mAc = new AsyncChannel();
                        mAc.connected(null, getHandler(), msg.replyTo);
                        if (VDBG) log("DcDefaultState: FULL_CONNECTION reply connected");
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_SUCCESSFUL, mId, "hi");
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECT: {
                    if (VDBG) log("CMD_CHANNEL_DISCONNECT");
                    mAc.disconnect();
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    if (VDBG) log("CMD_CHANNEL_DISCONNECTED");
                    mAc = null;
                    break;
                }
                case DcSwitchAsyncChannel.REQ_IS_IDLE_STATE: {
                    boolean val = getCurrentState() == mIdleState;
                    if (VDBG) log("REQ_IS_IDLE_STATE  isIdle=" + val);
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_IS_IDLE_STATE, val ? 1 : 0);
                    break;
                }
                case DcSwitchAsyncChannel.REQ_IS_IDLE_OR_DEACTING_STATE: {
                    boolean val = (getCurrentState() == mIdleState || getCurrentState() == mDeactingState);
                    if (VDBG) log("REQ_IS_IDLE_OR_DEACTING_STATE  isIdleDeacting=" + val);
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_IS_IDLE_OR_DEACTING_STATE, val ? 1 : 0);
                    break;
                }
                case EVENT_TO_ACTING_DIRECTLY: {
                    log("Just transit to Acting state");
                    transitionTo(mActingState);
                    break;
                }
                case EVENT_TO_IDLE_DIRECTLY: {
                    log("Just transit to Idle state");
                    Iterator<String> itrType = mApnTypes.iterator();
                    while (itrType.hasNext()) {
//                        mPhone.disableApnType(itrType.next()); TODO
                    }
                    mApnTypes.clear();
                    transitionTo(mIdleState);
                }
                default:
                    if (DBG) {
                        log("DefaultState: shouldn't happen but ignore msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    break;
            }
            return HANDLED;
        }
    }

    protected void log(String s) {
        Log.d(LOG_TAG, "[" + getName() + "] " + s);
    }
}
