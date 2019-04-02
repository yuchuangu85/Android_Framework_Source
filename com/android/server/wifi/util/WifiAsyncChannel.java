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
 * limitations under the License.
 */

package com.android.server.wifi.util;

import android.annotation.NonNull;
import android.os.Message;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.AsyncChannel;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.WifiLog;

/**
 * This class subclasses AsyncChannel and adds logging
 * to the sendMessage() API
 */
public class WifiAsyncChannel extends AsyncChannel {
    private static final String LOG_TAG = "WifiAsyncChannel";
    private WifiLog mLog;
    private String mTag;
    /**
     * AsyncChannelWithLogging constructor
     */
    public WifiAsyncChannel(String serviceTag) {
        mTag = LOG_TAG + "." + serviceTag;
    }

    @NonNull
    private WifiLog getOrInitLog() {
        // Lazy initization of mLog
        if (mLog == null) {
            mLog = WifiInjector.getInstance().makeLog(mTag);
        }
        return mLog;
    }

    /**
     * Send a message to the destination handler.
     *
     * @param msg
     */
    @Override
    public void sendMessage(Message msg) {
        getOrInitLog().trace("sendMessage message=%")
            .c(msg.what)
            .flush();
        super.sendMessage(msg);
    }

    /**
     * Reply to srcMsg
     *
     * @param srcMsg
     * @param dstMsg
     */
    @Override
    public void replyToMessage(Message srcMsg, Message dstMsg) {
        getOrInitLog()
                .trace("replyToMessage recvdMessage=% sendingUid=% sentMessage=%")
                .c(srcMsg.what)
                .c(srcMsg.sendingUid)
                .c(dstMsg.what)
                .flush();
        super.replyToMessage(srcMsg, dstMsg);
    }

    /**
     * Send the Message synchronously.
     *
     * @param msg to send
     * @return reply message or null if an error.
     */
    @Override
    public Message sendMessageSynchronously(Message msg) {
        getOrInitLog().trace("sendMessageSynchronously.send message=%")
            .c(msg.what)
            .flush();
        Message replyMessage = super.sendMessageSynchronously(msg);
        getOrInitLog().trace("sendMessageSynchronously.recv message=% sendingUid=%")
            .c(replyMessage.what)
            .c(replyMessage.sendingUid)
            .flush();
        return replyMessage;
    }

    @VisibleForTesting
    public void setWifiLog(WifiLog log) {
        mLog = log;
    }
}
