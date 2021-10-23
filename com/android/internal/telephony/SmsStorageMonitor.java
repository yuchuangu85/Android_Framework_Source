/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.compat.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.provider.Telephony.Sms.Intents;
import android.telephony.SubscriptionManager;

import com.android.telephony.Rlog;

/**
 * Monitors the device and ICC storage, and sends the appropriate events.
 *
 * This code was formerly part of {@link SMSDispatcher}, and has been moved
 * into a separate class to support instantiation of multiple SMSDispatchers on
 * dual-mode devices that require support for both 3GPP and 3GPP2 format messages.
 */
public class SmsStorageMonitor extends Handler {
    private static final String TAG = "SmsStorageMonitor";

    /** SIM/RUIM storage is full */
    private static final int EVENT_ICC_FULL = 1;

    /** Memory status reporting is acknowledged by RIL */
    private static final int EVENT_REPORT_MEMORY_STATUS_DONE = 2;

    /** Radio is ON */
    private static final int EVENT_RADIO_ON = 3;

    /** Context from phone object passed to constructor. */
    private final Context mContext;

    /** Wake lock to ensure device stays awake while dispatching the SMS intent. */
    private PowerManager.WakeLock mWakeLock;

    private boolean mReportMemoryStatusPending;

    /** it is use to put in to extra value for SIM_FULL_ACTION and SMS_REJECTED_ACTION */
    Phone mPhone;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    final CommandsInterface mCi;                            // accessed from inner class
    boolean mStorageAvailable = true;                       // accessed from inner class

    /**
     * Hold the wake lock for 5 seconds, which should be enough time for
     * any receiver(s) to grab its own wake lock.
     */
    private static final int WAKE_LOCK_TIMEOUT = 5000;

    /**
     * Creates an SmsStorageMonitor and registers for events.
     * @param phone the Phone to use
     */
    public SmsStorageMonitor(Phone phone) {
        mPhone = phone;
        mContext = phone.getContext();
        mCi = phone.mCi;

        createWakelock();

        mCi.setOnIccSmsFull(this, EVENT_ICC_FULL, null);
        mCi.registerForOn(this, EVENT_RADIO_ON, null);

        // Register for device storage intents.  Use these to notify the RIL
        // that storage for SMS is or is not available.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_DEVICE_STORAGE_FULL);
        filter.addAction(Intent.ACTION_DEVICE_STORAGE_NOT_FULL);
        mContext.registerReceiver(mResultReceiver, filter);
    }

    public void dispose() {
        mCi.unSetOnIccSmsFull(this);
        mCi.unregisterForOn(this);
        mContext.unregisterReceiver(mResultReceiver);
    }

    /**
     * Handles events coming from the phone stack. Overridden from handler.
     * @param msg the message to handle
     */
    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;

        switch (msg.what) {
            case EVENT_ICC_FULL:
                handleIccFull();
                break;

            case EVENT_REPORT_MEMORY_STATUS_DONE:
                ar = (AsyncResult) msg.obj;
                if (ar.exception != null) {
                    mReportMemoryStatusPending = true;
                    Rlog.v(TAG, "Memory status report to modem pending : mStorageAvailable = "
                            + mStorageAvailable);
                } else {
                    mReportMemoryStatusPending = false;
                }
                break;

            case EVENT_RADIO_ON:
                if (mReportMemoryStatusPending) {
                    Rlog.v(TAG, "Sending pending memory status report : mStorageAvailable = "
                            + mStorageAvailable);
                    mCi.reportSmsMemoryStatus(mStorageAvailable,
                            obtainMessage(EVENT_REPORT_MEMORY_STATUS_DONE));
                }
                break;
        }
    }

    private void createWakelock() {
        PowerManager pm = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsStorageMonitor");
        mWakeLock.setReferenceCounted(true);
    }

    /**
     * Called when SIM_FULL message is received from the RIL. Notifies the default SMS application
     * that SIM storage for SMS messages is full.
     */
    private void handleIccFull() {
        // broadcast SIM_FULL intent
        Intent intent = new Intent(Intents.SIM_FULL_ACTION);
        intent.setComponent(SmsApplication.getDefaultSimFullApplication(mContext, false));
        mWakeLock.acquire(WAKE_LOCK_TIMEOUT);
        SubscriptionManager.putPhoneIdAndSubIdExtra(intent, mPhone.getPhoneId());
        mContext.sendBroadcast(intent, android.Manifest.permission.RECEIVE_SMS);
    }

    /** Returns whether or not there is storage available for an incoming SMS. */
    public boolean isStorageAvailable() {
        return mStorageAvailable;
    }

    private final BroadcastReceiver mResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_DEVICE_STORAGE_FULL)) {
                mStorageAvailable = false;
                mCi.reportSmsMemoryStatus(false, obtainMessage(EVENT_REPORT_MEMORY_STATUS_DONE));
            } else if (intent.getAction().equals(Intent.ACTION_DEVICE_STORAGE_NOT_FULL)) {
                mStorageAvailable = true;
                mCi.reportSmsMemoryStatus(true, obtainMessage(EVENT_REPORT_MEMORY_STATUS_DONE));
            }
        }
    };
}
