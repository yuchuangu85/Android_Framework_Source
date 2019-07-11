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
import com.android.internal.telephony.PhoneConstants;

import android.os.Message;
import android.util.Log;

public class DcSwitchAsyncChannel extends AsyncChannel {
    private static final boolean DBG = true;
    private static final boolean VDBG = false;
    private static final String LOG_TAG = "DcSwitchAsyncChannel";

    private int tagId = 0;
    private DcSwitchState mDcSwitchState;

    // ***** Event codes for driving the state machine
    private static final int BASE = Protocol.BASE_DATA_CONNECTION_TRACKER + 0x00002000;
    static final int REQ_CONNECT = BASE + 0;
    static final int RSP_CONNECT = BASE + 1;
    static final int REQ_DISCONNECT = BASE + 2;
    static final int RSP_DISCONNECT = BASE + 3;
    static final int REQ_IS_IDLE_STATE = BASE + 4;
    static final int RSP_IS_IDLE_STATE = BASE + 5;
    static final int REQ_IS_IDLE_OR_DEACTING_STATE = BASE + 6;
    static final int RSP_IS_IDLE_OR_DEACTING_STATE = BASE + 7;

    private static final int CMD_TO_STRING_COUNT = RSP_IS_IDLE_OR_DEACTING_STATE - BASE + 1;
    private static String[] sCmdToString = new String[CMD_TO_STRING_COUNT];
    static {
        sCmdToString[REQ_CONNECT - BASE] = "REQ_CONNECT";
        sCmdToString[RSP_CONNECT - BASE] = "RSP_CONNECT";
        sCmdToString[REQ_DISCONNECT - BASE] = "REQ_DISCONNECT";
        sCmdToString[RSP_DISCONNECT - BASE] = "RSP_DISCONNECT";
        sCmdToString[REQ_IS_IDLE_STATE - BASE] = "REQ_IS_IDLE_STATE";
        sCmdToString[RSP_IS_IDLE_STATE - BASE] = "RSP_IS_IDLE_STATE";
        sCmdToString[REQ_IS_IDLE_OR_DEACTING_STATE - BASE] = "REQ_IS_IDLE_OR_DEACTING_STATE";
        sCmdToString[RSP_IS_IDLE_OR_DEACTING_STATE - BASE] = "RSP_IS_IDLE_OR_DEACTING_STATE";
    }

    protected static String cmdToString(int cmd) {
        cmd -= BASE;
        if ((cmd >= 0) && (cmd < sCmdToString.length)) {
            return sCmdToString[cmd];
        } else {
            return AsyncChannel.cmdToString(cmd + BASE);
        }
    }

    public DcSwitchAsyncChannel(DcSwitchState dcSwitchState, int id) {
        mDcSwitchState = dcSwitchState;
        tagId = id;
    }

    public void reqConnect(String type) {
        sendMessage(REQ_CONNECT, type);
        if (DBG) log("reqConnect");
    }

    public int rspConnect(Message response) {
        int retVal = response.arg1;
        if (DBG) log("rspConnect=" + retVal);
        return retVal;
    }

    public int connectSync(String type) {
        Message response = sendMessageSynchronously(REQ_CONNECT, type);
        if ((response != null) && (response.what == RSP_CONNECT)) {
            return rspConnect(response);
        } else {
            log("rspConnect error response=" + response);
            return PhoneConstants.APN_REQUEST_FAILED;
        }
    }

    public void reqDisconnect(String type) {
        sendMessage(REQ_DISCONNECT, type);
        if (DBG) log("reqDisconnect");
    }

    public int rspDisconnect(Message response) {
        int retVal = response.arg1;
        if (DBG) log("rspDisconnect=" + retVal);
        return retVal;
    }

    public int disconnectSync(String type) {
        Message response = sendMessageSynchronously(REQ_DISCONNECT, type);
        if ((response != null) && (response.what == RSP_DISCONNECT)) {
            return rspDisconnect(response);
        } else {
            log("rspDisconnect error response=" + response);
            return PhoneConstants.APN_REQUEST_FAILED;
        }
    }

    public void reqIsIdle() {
        sendMessage(REQ_IS_IDLE_STATE);
        if (DBG) log("reqIsIdle");
    }

    public boolean rspIsIdle(Message response) {
        boolean retVal = response.arg1 == 1;
        if (DBG) log("rspIsIdle=" + retVal);
        return retVal;
    }

    public boolean isIdleSync() {
        Message response = sendMessageSynchronously(REQ_IS_IDLE_STATE);
        if ((response != null) && (response.what == RSP_IS_IDLE_STATE)) {
            return rspIsIdle(response);
        } else {
            log("rspIsIndle error response=" + response);
            return false;
        }
    }

    public void reqIsIdleOrDeacting() {
        sendMessage(REQ_IS_IDLE_OR_DEACTING_STATE);
        if (DBG) log("reqIsIdleOrDeacting");
    }

    public boolean rspIsIdleOrDeacting(Message response) {
        boolean retVal = response.arg1 == 1;
        if (DBG) log("rspIsIdleOrDeacting=" + retVal);
        return retVal;
    }

    public boolean isIdleOrDeactingSync() {
        Message response = sendMessageSynchronously(REQ_IS_IDLE_OR_DEACTING_STATE);
        if ((response != null) && (response.what == RSP_IS_IDLE_OR_DEACTING_STATE)) {
            return rspIsIdleOrDeacting(response);
        } else {
            log("rspIsIndleOrDeacting error response=" + response);
            return false;
        }
    }

    @Override
    public String toString() {
        return mDcSwitchState.getName();
    }

    private void log(String s) {
        Log.d(LOG_TAG, "[DcSwitchAsyncChannel-" + tagId + "]: " + s);
    }
}
