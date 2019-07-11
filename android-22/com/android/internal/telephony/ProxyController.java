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

package com.android.internal.telephony;

import java.util.ArrayList;
import java.util.Random;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.telephony.RadioAccessFamily;
import android.telephony.Rlog;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.dataconnection.DctController;
import com.android.internal.telephony.RadioCapability;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.TelephonyIntents;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicInteger;

public class ProxyController {
    static final String LOG_TAG = "ProxyController";

    private static final int EVENT_NOTIFICATION_RC_CHANGED        = 1;
    private static final int EVENT_START_RC_RESPONSE        = 2;
    private static final int EVENT_APPLY_RC_RESPONSE        = 3;
    private static final int EVENT_FINISH_RC_RESPONSE       = 4;

    private static final int SET_RC_STATUS_IDLE             = 0;
    private static final int SET_RC_STATUS_STARTING         = 1;
    private static final int SET_RC_STATUS_STARTED          = 2;
    private static final int SET_RC_STATUS_APPLYING         = 3;
    private static final int SET_RC_STATUS_SUCCESS          = 4;
    private static final int SET_RC_STATUS_FAIL             = 5;

    // The entire transaction must complete within this amount of time
    // or a FINISH will be issued to each Logical Modem with the old
    // Radio Access Family.
    private static final int SET_RC_TIMEOUT_WAITING_MSEC    = (45 * 1000);

    //***** Class Variables
    private static ProxyController sProxyController;

    private PhoneProxy[] mProxyPhones;

    private UiccController mUiccController;

    private CommandsInterface[] mCi;

    private Context mContext;

    private DctController mDctController;

    //UiccPhoneBookController to use proper IccPhoneBookInterfaceManagerProxy object
    private UiccPhoneBookController mUiccPhoneBookController;

    //PhoneSubInfoController to use proper PhoneSubInfoProxy object
    private PhoneSubInfoController mPhoneSubInfoController;

    //UiccSmsController to use proper IccSmsInterfaceManager object
    private UiccSmsController mUiccSmsController;

    WakeLock mWakeLock;

    // record each phone's set radio capability status
    private int[] mSetRadioAccessFamilyStatus;
    private int mRadioAccessFamilyStatusCounter;

    private String[] mLogicalModemIds;

    // Allows the generation of unique Id's for radio capability request session  id
    private AtomicInteger mUniqueIdGenerator = new AtomicInteger(new Random().nextInt());

    // on-going radio capability request session id
    private int mRadioCapabilitySessionId;

    // Record new and old Radio Access Family (raf) configuration.
    // The old raf configuration is used to restore each logical modem raf when FINISH is
    // issued if any requests fail.
    private int[] mNewRadioAccessFamily;
    private int[] mOldRadioAccessFamily;

    // runnable for radio capability request timeout handling
    RadioCapabilityRunnable mSetRadioCapabilityRunnable;

    //***** Class Methods
    public static ProxyController getInstance(Context context, PhoneProxy[] phoneProxy,
            UiccController uiccController, CommandsInterface[] ci) {
        if (sProxyController == null) {
            sProxyController = new ProxyController(context, phoneProxy, uiccController, ci);
        }
        return sProxyController;
    }

    public static ProxyController getInstance() {
        return sProxyController;
    }

    private ProxyController(Context context, PhoneProxy[] phoneProxy, UiccController uiccController,
            CommandsInterface[] ci) {
        logd("Constructor - Enter");

        mContext = context;
        mProxyPhones = phoneProxy;
        mUiccController = uiccController;
        mCi = ci;

        mDctController = DctController.makeDctController(phoneProxy);
        mUiccPhoneBookController = new UiccPhoneBookController(mProxyPhones);
        mPhoneSubInfoController = new PhoneSubInfoController(mProxyPhones);
        mUiccSmsController = new UiccSmsController(mProxyPhones);
        mSetRadioAccessFamilyStatus = new int[mProxyPhones.length];
        mNewRadioAccessFamily = new int[mProxyPhones.length];
        mOldRadioAccessFamily = new int[mProxyPhones.length];
        mLogicalModemIds = new String[mProxyPhones.length];

        // TODO Get logical modem ids assume its just the phoneId as a string for now
        for (int i = 0; i < mProxyPhones.length; i++) {
            mLogicalModemIds[i] = Integer.toString(i);
        }

        mSetRadioCapabilityRunnable = new RadioCapabilityRunnable();

        // wake lock for set radio capability
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOG_TAG);
        mWakeLock.setReferenceCounted(false);

        // Clear to be sure we're in the initial state
        clearTransaction();
        for (int i = 0; i < mProxyPhones.length; i++) {
            mProxyPhones[i].registerForRadioCapabilityChanged(
                    mHandler, EVENT_NOTIFICATION_RC_CHANGED, null);
        }
        logd("Constructor - Exit");
    }

    public void updateDataConnectionTracker(int sub) {
        mProxyPhones[sub].updateDataConnectionTracker();
    }

    public void enableDataConnectivity(int sub) {
        mProxyPhones[sub].setInternalDataEnabled(true);
    }

    public void disableDataConnectivity(int sub,
            Message dataCleanedUpMsg) {
        mProxyPhones[sub].setInternalDataEnabled(false, dataCleanedUpMsg);
    }

    public void updateCurrentCarrierInProvider(int sub) {
        mProxyPhones[sub].updateCurrentCarrierInProvider();
    }

    public void registerForAllDataDisconnected(int subId, Handler h, int what, Object obj) {
        int phoneId = SubscriptionController.getInstance().getPhoneId(subId);

        if (phoneId >= 0 && phoneId < TelephonyManager.getDefault().getPhoneCount()) {
            mProxyPhones[phoneId].registerForAllDataDisconnected(h, what, obj);
        }
    }

    public void unregisterForAllDataDisconnected(int subId, Handler h) {
        int phoneId = SubscriptionController.getInstance().getPhoneId(subId);

        if (phoneId >= 0 && phoneId < TelephonyManager.getDefault().getPhoneCount()) {
            mProxyPhones[phoneId].unregisterForAllDataDisconnected(h);
        }
    }

    public boolean isDataDisconnected(int subId) {
        int phoneId = SubscriptionController.getInstance().getPhoneId(subId);

        if (phoneId >= 0 && phoneId < TelephonyManager.getDefault().getPhoneCount()) {
            Phone activePhone = mProxyPhones[phoneId].getActivePhone();
            return ((PhoneBase) activePhone).mDcTracker.isDisconnected();
        } else {
            return false;
        }
    }

    /**
     * Get phone radio type and access technology.
     *
     * @param phoneId which phone you want to get
     * @return phone radio type and access technology for input phone ID
     */
    public int getRadioAccessFamily(int phoneId) {
        if (phoneId >= mProxyPhones.length) {
            return RadioAccessFamily.RAF_UNKNOWN;
        } else {
            return mProxyPhones[phoneId].getRadioAccessFamily();
        }
    }

    /**
     * Set phone radio type and access technology for each phone.
     *
     * @param rafs an RadioAccessFamily array to indicate all phone's
     *        new radio access family. The length of RadioAccessFamily
     *        must equal to phone count.
     * @return false if another session is already active and the request is rejected.
     */
    public boolean setRadioCapability(RadioAccessFamily[] rafs) {
        if (rafs.length != mProxyPhones.length) {
            throw new RuntimeException("Length of input rafs must equal to total phone count");
        }

        // Check if there is any ongoing transaction and throw an exception if there
        // is one as this is a programming error.
        synchronized (mSetRadioAccessFamilyStatus) {
            for (int i = 0; i < mProxyPhones.length; i++) {
                logd("setRadioCapability: mSetRadioAccessFamilyStatus[" + i + "]="
                        + mSetRadioAccessFamilyStatus[i]);
                if (mSetRadioAccessFamilyStatus[i] != SET_RC_STATUS_IDLE) {
                    // TODO: The right behaviour is to cancel previous request and send this.
                    loge("setRadioCapability: Phone[" + i + "] is not idle. Rejecting request.");
                    return false;
                }
            }
        }

        // Clear to be sure we're in the initial state
        clearTransaction();

        // A new sessionId for this transaction
        mRadioCapabilitySessionId = mUniqueIdGenerator.getAndIncrement();

        // Keep a wake lock until we finish radio capability changed
        mWakeLock.acquire();

        // Start timer to make sure all phones respond within a specific time interval.
        // Will send FINISH if a timeout occurs.
        mSetRadioCapabilityRunnable.setTimeoutState(mRadioCapabilitySessionId);
        mHandler.postDelayed(mSetRadioCapabilityRunnable, SET_RC_TIMEOUT_WAITING_MSEC);

        synchronized (mSetRadioAccessFamilyStatus) {
            logd("setRadioCapability: new request session id=" + mRadioCapabilitySessionId);
            mRadioAccessFamilyStatusCounter = rafs.length;
            for (int i = 0; i < rafs.length; i++) {
                int phoneId = rafs[i].getPhoneId();
                logd("setRadioCapability: phoneId=" + phoneId + " status=STARTING");
                mSetRadioAccessFamilyStatus[phoneId] = SET_RC_STATUS_STARTING;
                mOldRadioAccessFamily[phoneId] = mProxyPhones[phoneId].getRadioAccessFamily();
                int requestedRaf = rafs[i].getRadioAccessFamily();
                // TODO Set the new radio access family to the maximum of the requested & supported
                // int supportedRaf = mProxyPhones[i].getSupportedRadioAccessFamily();
                // mNewRadioAccessFamily[phoneId] = requestedRaf & supportedRaf;
                mNewRadioAccessFamily[phoneId] = requestedRaf;
                logd("setRadioCapability: mOldRadioAccessFamily[" + phoneId + "]="
                        + mOldRadioAccessFamily[phoneId]);
                logd("setRadioCapability: mNewRadioAccessFamily[" + phoneId + "]="
                        + mNewRadioAccessFamily[phoneId]);
                sendRadioCapabilityRequest(
                        phoneId,
                        mRadioCapabilitySessionId,
                        RadioCapability.RC_PHASE_START,
                        mOldRadioAccessFamily[phoneId],
                        mLogicalModemIds[phoneId],
                        RadioCapability.RC_STATUS_NONE,
                        EVENT_START_RC_RESPONSE);
            }
        }

        return true;
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            logd("handleMessage msg.what=" + msg.what);
            switch (msg.what) {
                case EVENT_START_RC_RESPONSE:
                    onStartRadioCapabilityResponse(msg);
                    break;

                case EVENT_APPLY_RC_RESPONSE:
                    onApplyRadioCapabilityResponse(msg);
                    break;

                case EVENT_NOTIFICATION_RC_CHANGED:
                    onNotificationRadioCapabilityChanged(msg);
                    break;

                case EVENT_FINISH_RC_RESPONSE:
                    onFinishRadioCapabilityResponse(msg);
                    break;

                default:
                    break;
            }
        }
    };

    /**
     * Handle START response
     * @param msg obj field isa RadioCapability
     */
    private void onStartRadioCapabilityResponse(Message msg) {
        synchronized (mSetRadioAccessFamilyStatus) {
            RadioCapability rc = (RadioCapability) ((AsyncResult) msg.obj).result;
            if ((rc == null) || (rc.getSession() != mRadioCapabilitySessionId)) {
                logd("onStartRadioCapabilityResponse: Ignore session=" + mRadioCapabilitySessionId
                        + " rc=" + rc);
                return;
            }
            mRadioAccessFamilyStatusCounter--;
            int id = rc.getPhoneId();
            if (((AsyncResult) msg.obj).exception != null) {
                logd("onStartRadioCapabilityResponse: Error response session=" + rc.getSession());
                logd("onStartRadioCapabilityResponse: phoneId=" + id + " status=FAIL");
                mSetRadioAccessFamilyStatus[id] = SET_RC_STATUS_FAIL;
            } else {
                logd("onStartRadioCapabilityResponse: phoneId=" + id + " status=STARTED");
                mSetRadioAccessFamilyStatus[id] = SET_RC_STATUS_STARTED;
            }

            if (mRadioAccessFamilyStatusCounter == 0) {
                resetRadioAccessFamilyStatusCounter();
                boolean success = checkAllRadioCapabilitySuccess();
                logd("onStartRadioCapabilityResponse: success=" + success);
                if (!success) {
                    issueFinish(RadioCapability.RC_STATUS_FAIL,
                            mRadioCapabilitySessionId);
                } else {
                    // All logical modem accepted the new radio access family, issue the APPLY
                    for (int i = 0; i < mProxyPhones.length; i++) {
                        sendRadioCapabilityRequest(
                            i,
                            mRadioCapabilitySessionId,
                            RadioCapability.RC_PHASE_APPLY,
                            mNewRadioAccessFamily[i],
                            mLogicalModemIds[i],
                            RadioCapability.RC_STATUS_NONE,
                            EVENT_APPLY_RC_RESPONSE);

                        logd("onStartRadioCapabilityResponse: phoneId=" + i + " status=APPLYING");
                        mSetRadioAccessFamilyStatus[i] = SET_RC_STATUS_APPLYING;
                    }
                }
            }
        }
    }

    /**
     * Handle APPLY response
     * @param msg obj field isa RadioCapability
     */
    private void onApplyRadioCapabilityResponse(Message msg) {
        RadioCapability rc = (RadioCapability) ((AsyncResult) msg.obj).result;
        if ((rc == null) || (rc.getSession() != mRadioCapabilitySessionId)) {
            logd("onApplyRadioCapabilityResponse: Ignore session=" + mRadioCapabilitySessionId
                    + " rc=" + rc);
            return;
        }
        logd("onApplyRadioCapabilityResponse: rc=" + rc);
        if (((AsyncResult) msg.obj).exception != null) {
            synchronized (mSetRadioAccessFamilyStatus) {
                logd("onApplyRadioCapabilityResponse: Error response session=" + rc.getSession());
                int id = rc.getPhoneId();
                logd("onApplyRadioCapabilityResponse: phoneId=" + id + " status=FAIL");
                mSetRadioAccessFamilyStatus[id] = SET_RC_STATUS_FAIL;
            }
        } else {
            logd("onApplyRadioCapabilityResponse: Valid start expecting notification rc=" + rc);
        }
    }

    /**
     * Handle the notification unsolicited response associated with the APPLY
     * @param msg obj field isa RadioCapability
     */
    private void onNotificationRadioCapabilityChanged(Message msg) {
        RadioCapability rc = (RadioCapability) ((AsyncResult) msg.obj).result;
        if ((rc == null) || (rc.getSession() != mRadioCapabilitySessionId)) {
            logd("onNotificationRadioCapabilityChanged: Ignore session=" + mRadioCapabilitySessionId
                    + " rc=" + rc);
            return;
        }
        synchronized (mSetRadioAccessFamilyStatus) {
            logd("onNotificationRadioCapabilityChanged: rc=" + rc);
            // skip the overdue response by checking sessionId
            if (rc.getSession() != mRadioCapabilitySessionId) {
                logd("onNotificationRadioCapabilityChanged: Ignore session="
                        + mRadioCapabilitySessionId + " rc=" + rc);
                return;
            }

            int id = rc.getPhoneId();
            if ((((AsyncResult) msg.obj).exception != null) ||
                    (rc.getStatus() == RadioCapability.RC_STATUS_FAIL)) {
                logd("onNotificationRadioCapabilityChanged: phoneId=" + id + " status=FAIL");
                mSetRadioAccessFamilyStatus[id] = SET_RC_STATUS_FAIL;
            } else {
                logd("onNotificationRadioCapabilityChanged: phoneId=" + id + " status=SUCCESS");
                mSetRadioAccessFamilyStatus[id] = SET_RC_STATUS_SUCCESS;
            }

            mRadioAccessFamilyStatusCounter--;
            if (mRadioAccessFamilyStatusCounter == 0) {
                logd("onNotificationRadioCapabilityChanged: removing callback from handler");
                mHandler.removeCallbacks(mSetRadioCapabilityRunnable);
                resetRadioAccessFamilyStatusCounter();
                boolean success = checkAllRadioCapabilitySuccess();
                logd("onNotificationRadioCapabilityChanged: APPLY URC success=" + success);
                int status;
                if (success) {
                    status = RadioCapability.RC_STATUS_SUCCESS;
                } else {
                    status = RadioCapability.RC_STATUS_FAIL;
                }
                issueFinish(status, mRadioCapabilitySessionId);
            }
        }
    }

    /**
     * Handle the FINISH Phase response
     * @param msg obj field isa RadioCapability
     */
    void onFinishRadioCapabilityResponse(Message msg) {
        RadioCapability rc = (RadioCapability) ((AsyncResult) msg.obj).result;
        if ((rc == null) || (rc.getSession() != mRadioCapabilitySessionId)) {
            logd("onFinishRadioCapabilityResponse: Ignore session=" + mRadioCapabilitySessionId
                    + " rc=" + rc);
            return;
        }
        synchronized (mSetRadioAccessFamilyStatus) {
            logd(" onFinishRadioCapabilityResponse mRadioAccessFamilyStatusCounter="
                    + mRadioAccessFamilyStatusCounter);
            mRadioAccessFamilyStatusCounter--;
            if (mRadioAccessFamilyStatusCounter == 0) {
                completeRadioCapabilityTransaction();
            }
        }
    }

    private void issueFinish(int status, int sessionId) {
        // Issue FINISH
        synchronized(mSetRadioAccessFamilyStatus) {
            for (int i = 0; i < mProxyPhones.length; i++) {
                if (mSetRadioAccessFamilyStatus[i] != SET_RC_STATUS_FAIL) {
                    logd("issueFinish: phoneId=" + i + " sessionId=" + sessionId
                            + " status=" + status);
                    sendRadioCapabilityRequest(
                        i,
                        sessionId,
                        RadioCapability.RC_PHASE_FINISH,
                        mOldRadioAccessFamily[i],
                        mLogicalModemIds[i],
                        status,
                        EVENT_FINISH_RC_RESPONSE);
                    if (status == RadioCapability.RC_STATUS_FAIL) {
                        logd("issueFinish: phoneId: " + i + " status: FAIL");
                        // At least one failed, mark them all failed.
                        mSetRadioAccessFamilyStatus[i] = SET_RC_STATUS_FAIL;
                    }
                } else {
                    logd("issueFinish: Ignore already FAIL, Phone" + i + " sessionId=" + sessionId
                            + " status=" + status);
                }
            }
        }
    }

    private void completeRadioCapabilityTransaction() {
        // Create the intent to broadcast
        Intent intent;
        boolean success = checkAllRadioCapabilitySuccess();
        logd("onFinishRadioCapabilityResponse: success=" + success);
        if (success) {
            ArrayList<RadioAccessFamily> phoneRAFList = new ArrayList<RadioAccessFamily>();
            for (int i = 0; i < mProxyPhones.length; i++) {
                int raf = mProxyPhones[i].getRadioAccessFamily();
                logd("radioAccessFamily[" + i + "]=" + raf);
                RadioAccessFamily phoneRC = new RadioAccessFamily(i, raf);
                phoneRAFList.add(phoneRC);
            }
            intent = new Intent(TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_DONE);
            intent.putParcelableArrayListExtra(TelephonyIntents.EXTRA_RADIO_ACCESS_FAMILY,
                    phoneRAFList);
        } else {
            intent = new Intent(TelephonyIntents.ACTION_SET_RADIO_CAPABILITY_FAILED);
        }

        // Reinitialize
        clearTransaction();

        // Broadcast that we're done
        mContext.sendBroadcast(intent);
    }

    // Clear this transaction
    private void clearTransaction() {
        logd("clearTransaction");
        synchronized(mSetRadioAccessFamilyStatus) {
            for (int i = 0; i < mProxyPhones.length; i++) {
                logd("clearTransaction: phoneId=" + i + " status=IDLE");
                mSetRadioAccessFamilyStatus[i] = SET_RC_STATUS_IDLE;
                mOldRadioAccessFamily[i] = 0;
                mNewRadioAccessFamily[i] = 0;
            }

            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }
    }

    private boolean checkAllRadioCapabilitySuccess() {
        synchronized(mSetRadioAccessFamilyStatus) {
            for (int i = 0; i < mProxyPhones.length; i++) {
                if (mSetRadioAccessFamilyStatus[i] == SET_RC_STATUS_FAIL) {
                    return false;
                }
            }
            return true;
        }
    }

    private void resetRadioAccessFamilyStatusCounter() {
        mRadioAccessFamilyStatusCounter = mProxyPhones.length;
    }

    private void sendRadioCapabilityRequest(int phoneId, int sessionId, int rcPhase,
            int radioFamily, String logicalModemId, int status, int eventId) {
        RadioCapability requestRC = new RadioCapability(
                phoneId, sessionId, rcPhase, radioFamily, logicalModemId, status);
        mProxyPhones[phoneId].setRadioCapability(
                requestRC, mHandler.obtainMessage(eventId));
    }

    /**
     * RadioCapabilityRunnable is used to check
     * if radio capability request's response is out of date.
     * <p>
     * Note that the setRadioCapability will be stopped directly and send FINISH
     * with fail status to all logical modems. and send out fail intent
     *
     */
    private class RadioCapabilityRunnable implements Runnable {
        private int mSessionId;
        public  RadioCapabilityRunnable() {
        }

        public void setTimeoutState(int sessionId) {
            mSessionId = sessionId;
        }

        @Override
        public void run() {
            if (mSessionId != mRadioCapabilitySessionId) {
                logd("RadioCapability timeout: Ignore mSessionId=" + mSessionId
                       + "!= mRadioCapabilitySessionId=" + mRadioCapabilitySessionId);
                return;
            }

            synchronized(mSetRadioAccessFamilyStatus) {
                for (int i = 0; i < mProxyPhones.length; i++) {
                    logd("RadioCapability timeout: mSetRadioAccessFamilyStatus[" + i + "]=" +
                            mSetRadioAccessFamilyStatus[i]);
                }

                // Increment the sessionId as we are completing the transaction below
                // so we don't want it completed when the FINISH phase is done.
                int uniqueDifferentId = mUniqueIdGenerator.getAndIncrement();

                // send FINISH request with fail status and then uniqueDifferentId
                issueFinish(RadioCapability.RC_STATUS_FAIL,
                        uniqueDifferentId);
                completeRadioCapabilityTransaction();
            }
        }
    }

    private void logd(String string) {
        Rlog.d(LOG_TAG, string);
    }

    private void loge(String string) {
        Rlog.e(LOG_TAG, string);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        try {
            mDctController.dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
