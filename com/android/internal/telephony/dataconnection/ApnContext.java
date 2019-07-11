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

package com.android.internal.telephony.dataconnection;

import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Resources;
import android.net.NetworkConfig;
import android.telephony.Rlog;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.SparseIntArray;

import com.android.internal.R;
import com.android.internal.telephony.DctConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maintain the Apn context
 */
public class ApnContext {

    public final String LOG_TAG;

    protected static final boolean DBG = false;

    private final Context mContext;

    private final String mApnType;

    private DctConstants.State mState;

    private ArrayList<ApnSetting> mWaitingApns = null;

    /**
     * Used to check if conditions (new RAT) are resulting in a new list which warrants a retry.
     * Set in the last trySetupData call.
     */
    private ArrayList<ApnSetting> mOriginalWaitingApns = null;

    public final int priority;

    /** A zero indicates that all waiting APNs had a permanent error */
    private AtomicInteger mWaitingApnsPermanentFailureCountDown;

    private ApnSetting mApnSetting;

    DcAsyncChannel mDcAc;

    String mReason;

    PendingIntent mReconnectAlarmIntent;

    /**
     * user/app requested connection on this APN
     */
    AtomicBoolean mDataEnabled;

    private final Object mRefCountLock = new Object();
    private int mRefCount = 0;

    /**
     * carrier requirements met
     */
    AtomicBoolean mDependencyMet;

    private final DcTrackerBase mDcTracker;

    /**
     * Remember this as a change in this value to a more permissive state
     * should cause us to retry even permanent failures
     */
    private boolean mConcurrentVoiceAndDataAllowed;

    public ApnContext(Context context, String apnType, String logTag, NetworkConfig config,
            DcTrackerBase tracker) {
        mContext = context;
        mApnType = apnType;
        mState = DctConstants.State.IDLE;
        setReason(Phone.REASON_DATA_ENABLED);
        mDataEnabled = new AtomicBoolean(false);
        mDependencyMet = new AtomicBoolean(config.dependencyMet);
        mWaitingApnsPermanentFailureCountDown = new AtomicInteger(0);
        priority = config.priority;
        LOG_TAG = logTag;
        mDcTracker = tracker;
    }

    public String getApnType() {
        return mApnType;
    }

    public synchronized DcAsyncChannel getDcAc() {
        return mDcAc;
    }

    public synchronized void setDataConnectionAc(DcAsyncChannel dcac) {
        if (DBG) {
            log("setDataConnectionAc: old dcac=" + mDcAc + " new dcac=" + dcac
                    + " this=" + this);
        }
        mDcAc = dcac;
    }

    public synchronized void releaseDataConnection(String reason) {
        if (mDcAc != null) {
            mDcAc.tearDown(this, reason, null);
            mDcAc = null;
        }
        setState(DctConstants.State.IDLE);
    }

    public synchronized PendingIntent getReconnectIntent() {
        return mReconnectAlarmIntent;
    }

    public synchronized void setReconnectIntent(PendingIntent intent) {
        mReconnectAlarmIntent = intent;
    }

    public synchronized ApnSetting getApnSetting() {
        if (DBG) log("getApnSetting: apnSetting=" + mApnSetting);
        return mApnSetting;
    }

    public synchronized void setApnSetting(ApnSetting apnSetting) {
        if (DBG) log("setApnSetting: apnSetting=" + apnSetting);
        mApnSetting = apnSetting;
    }

    public synchronized void setWaitingApns(ArrayList<ApnSetting> waitingApns) {
        mWaitingApns = waitingApns;
        mOriginalWaitingApns = new ArrayList<ApnSetting>(waitingApns);
        mWaitingApnsPermanentFailureCountDown.set(mWaitingApns.size());
    }

    public int getWaitingApnsPermFailCount() {
        return mWaitingApnsPermanentFailureCountDown.get();
    }

    public void decWaitingApnsPermFailCount() {
        mWaitingApnsPermanentFailureCountDown.decrementAndGet();
    }

    public synchronized ApnSetting getNextWaitingApn() {
        ArrayList<ApnSetting> list = mWaitingApns;
        ApnSetting apn = null;

        if (list != null) {
            if (!list.isEmpty()) {
                apn = list.get(0);
            }
        }
        return apn;
    }

    public synchronized void removeWaitingApn(ApnSetting apn) {
        if (mWaitingApns != null) {
            mWaitingApns.remove(apn);
        }
    }

    public synchronized ArrayList<ApnSetting> getOriginalWaitingApns() {
        return mOriginalWaitingApns;
    }

    public synchronized ArrayList<ApnSetting> getWaitingApns() {
        return mWaitingApns;
    }

    public synchronized void setConcurrentVoiceAndDataAllowed(boolean allowed) {
        mConcurrentVoiceAndDataAllowed = allowed;
    }

    public synchronized boolean isConcurrentVoiceAndDataAllowed() {
        return mConcurrentVoiceAndDataAllowed;
    }

    public synchronized void setState(DctConstants.State s) {
        if (DBG) {
            log("setState: " + s + ", previous state:" + mState);
        }

        mState = s;

        if (mState == DctConstants.State.FAILED) {
            if (mWaitingApns != null) {
                mWaitingApns.clear(); // when teardown the connection and set to IDLE
            }
        }
    }

    public synchronized DctConstants.State getState() {
        return mState;
    }

    public boolean isDisconnected() {
        DctConstants.State currentState = getState();
        return ((currentState == DctConstants.State.IDLE) ||
                    currentState == DctConstants.State.FAILED);
    }

    public synchronized void setReason(String reason) {
        if (DBG) {
            log("set reason as " + reason + ",current state " + mState);
        }
        mReason = reason;
    }

    public synchronized String getReason() {
        return mReason;
    }

    public boolean isReady() {
        return mDataEnabled.get() && mDependencyMet.get();
    }

    public boolean isConnectable() {
        return isReady() && ((mState == DctConstants.State.IDLE)
                                || (mState == DctConstants.State.SCANNING)
                                || (mState == DctConstants.State.RETRYING)
                                || (mState == DctConstants.State.FAILED));
    }

    public boolean isConnectedOrConnecting() {
        return isReady() && ((mState == DctConstants.State.CONNECTED)
                                || (mState == DctConstants.State.CONNECTING)
                                || (mState == DctConstants.State.SCANNING)
                                || (mState == DctConstants.State.RETRYING));
    }

    public void setEnabled(boolean enabled) {
        if (DBG) {
            log("set enabled as " + enabled + ", current state is " + mDataEnabled.get());
        }
        mDataEnabled.set(enabled);
    }

    public boolean isEnabled() {
        return mDataEnabled.get();
    }

    public void setDependencyMet(boolean met) {
        if (DBG) {
            log("set mDependencyMet as " + met + " current state is " + mDependencyMet.get());
        }
        mDependencyMet.set(met);
    }

    public boolean getDependencyMet() {
       return mDependencyMet.get();
    }

    public boolean isProvisioningApn() {
        String provisioningApn = mContext.getResources()
                .getString(R.string.mobile_provisioning_apn);
        if (!TextUtils.isEmpty(provisioningApn) &&
                (mApnSetting != null) && (mApnSetting.apn != null)) {
            return (mApnSetting.apn.equals(provisioningApn));
        } else {
            return false;
        }
    }

    private final ArrayList<LocalLog> mLocalLogs = new ArrayList<LocalLog>();

    public void requestLog(String str) {
        synchronized (mRefCountLock) {
            for (LocalLog l : mLocalLogs) {
                l.log(str);
            }
        }
    }

    public void incRefCount(LocalLog log) {
        synchronized (mRefCountLock) {
            if (mRefCount == 0) {
               // we wanted to leave the last in so it could actually capture the tear down
               // of the network
               requestLog("clearing log with size=" + mLocalLogs.size());
               mLocalLogs.clear();
            }
            if (mLocalLogs.contains(log)) {
                log.log("ApnContext.incRefCount has duplicate add - " + mRefCount);
            } else {
                mLocalLogs.add(log);
                log.log("ApnContext.incRefCount - " + mRefCount);
            }
            if (mRefCount++ == 0) {
                mDcTracker.setEnabled(mDcTracker.apnTypeToId(mApnType), true);
            }
        }
    }

    public void decRefCount(LocalLog log) {
        synchronized (mRefCountLock) {
            // leave the last log alive to capture the actual tear down
            if (mRefCount != 1) {
                if (mLocalLogs.remove(log)) {
                    log.log("ApnContext.decRefCount - " + mRefCount);
                } else {
                    log.log("ApnContext.decRefCount didn't find log - " + mRefCount);
                }
            } else {
                log.log("ApnContext.decRefCount - 1");
            }
            if (mRefCount-- == 1) {
                mDcTracker.setEnabled(mDcTracker.apnTypeToId(mApnType), false);
            }
        }
    }

    private final SparseIntArray mRetriesLeftPerErrorCode = new SparseIntArray();

    public void resetErrorCodeRetries() {
        requestLog("ApnContext.resetErrorCodeRetries");
        if (DBG) log("ApnContext.resetErrorCodeRetries");

        String[] config = Resources.getSystem().getStringArray(
                com.android.internal.R.array.config_cell_retries_per_error_code);
        synchronized (mRetriesLeftPerErrorCode) {
            mRetriesLeftPerErrorCode.clear();

            for (String c : config) {
                String errorValue[] = c.split(",");
                if (errorValue != null && errorValue.length == 2) {
                    int count = 0;
                    int errorCode = 0;
                    try {
                        errorCode = Integer.parseInt(errorValue[0]);
                        count = Integer.parseInt(errorValue[1]);
                    } catch (NumberFormatException e) {
                        log("Exception parsing config_retries_per_error_code: " + e);
                        continue;
                    }
                    if (count > 0 && errorCode > 0) {
                        mRetriesLeftPerErrorCode.put(errorCode, count);
                    }
                } else {
                    log("Exception parsing config_retries_per_error_code: " + c);
                }
            }
        }
    }

    public boolean restartOnError(int errorCode) {
        boolean result = false;
        int retriesLeft = 0;
        synchronized(mRetriesLeftPerErrorCode) {
            retriesLeft = mRetriesLeftPerErrorCode.get(errorCode);
            switch (retriesLeft) {
                case 0: {
                    // not set, never restart modem
                    break;
                }
                case 1: {
                    resetErrorCodeRetries();
                    result = true;
                    break;
                }
                default: {
                    mRetriesLeftPerErrorCode.put(errorCode, retriesLeft - 1);
                    result = false;
                }
            }
        }
        String str = "ApnContext.restartOnError(" + errorCode + ") found " + retriesLeft +
                " and returned " + result;
        if (DBG) log(str);
        requestLog(str);
        return result;
    }

    @Override
    public synchronized String toString() {
        // We don't print mDataConnection because its recursive.
        return "{mApnType=" + mApnType + " mState=" + getState() + " mWaitingApns={" +
                mWaitingApns + "} mWaitingApnsPermanentFailureCountDown=" +
                mWaitingApnsPermanentFailureCountDown + " mApnSetting={" + mApnSetting +
                "} mReason=" + mReason + " mDataEnabled=" + mDataEnabled + " mDependencyMet=" +
                mDependencyMet + "}";
    }

    private void log(String s) {
        Rlog.d(LOG_TAG, "[ApnContext:" + mApnType + "] " + s);
    }

    public void dump(FileDescriptor fd, PrintWriter printWriter, String[] args) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(printWriter, "  ");
        synchronized (mRefCountLock) {
            pw.println(toString());
            if (mRefCount > 0) {
                pw.increaseIndent();
                for (LocalLog l : mLocalLogs) {
                    l.dump(fd, pw, args);
                }
                pw.decreaseIndent();
            }
        }
    }
}
