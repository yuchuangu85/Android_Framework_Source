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
package com.android.internal.telephony.dataconnection;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.SystemClock;
import android.telephony.Rlog;
import android.text.TextUtils;

import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.RILConstants;

/**
 * The Data Connection Retry Alarm Controller.
 */
public class DcRetryAlarmController {
    private String mLogTag = "DcRac";
    private static final boolean DBG = true;

    private PhoneBase mPhone;
    private DataConnection mDc;
    private AlarmManager mAlarmManager;

    // The Intent action for retrying and its two extra's
    private String mActionRetry;
    private static final String INTENT_RETRY_ALARM_WHAT = "what";
    private static final String INTENT_RETRY_ALARM_TAG = "tag";

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
            @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TextUtils.isEmpty(action)) {
                // Our mActionXxxx's could be null when disposed this could match an empty action.
                log("onReceive: ignore empty action='" + action + "'");
                return;
            }
            if (TextUtils.equals(action, mActionRetry)) {
                if (!intent.hasExtra(INTENT_RETRY_ALARM_WHAT)) {
                    throw new RuntimeException(mActionRetry + " has no INTENT_RETRY_ALRAM_WHAT");
                }
                if (!intent.hasExtra(INTENT_RETRY_ALARM_TAG)) {
                    throw new RuntimeException(mActionRetry + " has no INTENT_RETRY_ALRAM_TAG");
                }
                int what = intent.getIntExtra(INTENT_RETRY_ALARM_WHAT, Integer.MAX_VALUE);
                int tag = intent.getIntExtra(INTENT_RETRY_ALARM_TAG, Integer.MAX_VALUE);
                if (DBG) {
                    log("onReceive: action=" + action
                            + " sendMessage(what:" + mDc.getWhatToString(what)
                            + ", tag:" + tag + ")");
                }
                mDc.sendMessage(mDc.obtainMessage(what, tag, 0));
            } else {
                if (DBG) log("onReceive: unknown action=" + action);
            }
        }
    };

    DcRetryAlarmController(PhoneBase phone, DataConnection dc) {
        mLogTag = dc.getName();
        mPhone = phone;
        mDc = dc;
        mAlarmManager = (AlarmManager) mPhone.getContext().getSystemService(Context.ALARM_SERVICE);
        mActionRetry = mDc.getClass().getCanonicalName() + "." + mDc.getName() + ".action_retry";

        IntentFilter filter = new IntentFilter();
        filter.addAction(mActionRetry);
        log("DcRetryAlarmController: register for intent action=" + mActionRetry);

        mPhone.getContext().registerReceiver(mIntentReceiver, filter, null, mDc.getHandler());
    }

    /**
     * Dispose of resources when shutting down
     */
    void dispose() {
        if (DBG) log("dispose");
        mPhone.getContext().unregisterReceiver(mIntentReceiver);
        mPhone = null;
        mDc = null;
        mAlarmManager = null;
        mActionRetry = null;
    }

    /**
     * Using dc.mRetryManager and the result of the SETUP_DATA_CALL determine
     * the retry delay.
     *
     * @param dc is the DataConnection
     * @param ar is the result from SETUP_DATA_CALL
     * @return < 0 if no retry is needed otherwise the delay to the next SETUP_DATA_CALL
     */
    int getSuggestedRetryTime(DataConnection dc, AsyncResult ar) {
        int retryDelay;

        DataCallResponse response = (DataCallResponse) ar.result;
        retryDelay = response.suggestedRetryTime;
        if (retryDelay == RILConstants.MAX_INT) {
            if (DBG) log("getSuggestedRetryTime: suggestedRetryTime is MAX_INT, retry NOT needed");
            retryDelay = -1;
        } else if (retryDelay >= 0) {
            if (DBG) log("getSuggestedRetryTime: suggestedRetryTime is >= 0 use it");
        } else if (dc.mRetryManager.isRetryNeeded()) {
            retryDelay = dc.mRetryManager.getRetryTimer();
            if (retryDelay < 0) {
                retryDelay = 0;
            }
            if (DBG) log("getSuggestedRetryTime: retry is needed");
        } else {
            if (DBG) log("getSuggestedRetryTime: retry is NOT needed");
            retryDelay = -1;
        }

        if (DBG) {
            log("getSuggestedRetryTime: " + retryDelay + " response=" + response + " dc=" + dc);
        }
        return retryDelay;
    }

    public void startRetryAlarm(int what, int tag, int delay) {
        Intent intent = new Intent(mActionRetry);
        intent.putExtra(INTENT_RETRY_ALARM_WHAT, what);
        intent.putExtra(INTENT_RETRY_ALARM_TAG, tag);

        if (DBG) {
            log("startRetryAlarm: next attempt in " + (delay / 1000) + "s" +
                    " what=" + what + " tag=" + tag);
        }

        PendingIntent retryIntent = PendingIntent.getBroadcast (mPhone.getContext(), 0,
                                        intent, PendingIntent.FLAG_UPDATE_CURRENT);
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delay, retryIntent);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(mLogTag).append(" [dcRac] ");
        sb.append(" mPhone=").append(mPhone);
        sb.append(" mDc=").append(mDc);
        sb.append(" mAlaramManager=").append(mAlarmManager);
        sb.append(" mActionRetry=").append(mActionRetry);
        return sb.toString();
    }

    private void log(String s) {
        Rlog.d(mLogTag, "[dcRac] " + s);
    }
}
