/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemClock;
import android.os.WorkSource;
import android.os.WorkSource.WorkChain;
import android.telephony.Rlog;

import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@hide}
 */

public class RILRequest {
    static final String LOG_TAG = "RilRequest";

    //***** Class Variables
    static Random sRandom = new Random();
    static AtomicInteger sNextSerial = new AtomicInteger(0);
    private static Object sPoolSync = new Object();
    private static RILRequest sPool = null;
    private static int sPoolSize = 0;
    private static final int MAX_POOL_SIZE = 4;

    //***** Instance Variables
    int mSerial;
    int mRequest;
    Message mResult;
    RILRequest mNext;
    int mWakeLockType;
    WorkSource mWorkSource;
    String mClientId;
    // time in ms when RIL request was made
    long mStartTimeMs;

    public int getSerial() {
        return mSerial;
    }

    public int getRequest() {
        return mRequest;
    }

    public Message getResult() {
        return mResult;
    }

    /**
     * Retrieves a new RILRequest instance from the pool.
     *
     * @param request RIL_REQUEST_*
     * @param result sent when operation completes
     * @return a RILRequest instance from the pool.
     */
    private static RILRequest obtain(int request, Message result) {
        RILRequest rr = null;

        synchronized (sPoolSync) {
            if (sPool != null) {
                rr = sPool;
                sPool = rr.mNext;
                rr.mNext = null;
                sPoolSize--;
            }
        }

        if (rr == null) {
            rr = new RILRequest();
        }

        rr.mSerial = sNextSerial.getAndIncrement();

        rr.mRequest = request;
        rr.mResult = result;

        rr.mWakeLockType = RIL.INVALID_WAKELOCK;
        rr.mWorkSource = null;
        rr.mStartTimeMs = SystemClock.elapsedRealtime();
        if (result != null && result.getTarget() == null) {
            throw new NullPointerException("Message target must not be null");
        }

        return rr;
    }


    /**
     * Retrieves a new RILRequest instance from the pool and sets the clientId
     *
     * @param request RIL_REQUEST_*
     * @param result sent when operation completes
     * @param workSource WorkSource to track the client
     * @return a RILRequest instance from the pool.
     */
    // @VisibleForTesting
    public static RILRequest obtain(int request, Message result, WorkSource workSource) {
        RILRequest rr = null;

        rr = obtain(request, result);
        if (workSource != null) {
            rr.mWorkSource = workSource;
            rr.mClientId = rr.getWorkSourceClientId();
        } else {
            Rlog.e(LOG_TAG, "null workSource " + request);
        }

        return rr;
    }

    /**
     * Generate a String client ID from the WorkSource.
     */
    // @VisibleForTesting
    public String getWorkSourceClientId() {
        if (mWorkSource == null || mWorkSource.isEmpty()) {
            return null;
        }

        if (mWorkSource.size() > 0) {
            return mWorkSource.get(0) + ":" + mWorkSource.getName(0);
        }

        final ArrayList<WorkChain> workChains = mWorkSource.getWorkChains();
        if (workChains != null && !workChains.isEmpty()) {
            final WorkChain workChain = workChains.get(0);
            return workChain.getAttributionUid() + ":" + workChain.getTags()[0];
        }

        return null;
    }

    /**
     * Returns a RILRequest instance to the pool.
     *
     * Note: This should only be called once per use.
     */
    void release() {
        synchronized (sPoolSync) {
            if (sPoolSize < MAX_POOL_SIZE) {
                mNext = sPool;
                sPool = this;
                sPoolSize++;
                mResult = null;
                if (mWakeLockType != RIL.INVALID_WAKELOCK) {
                    //This is OK for some wakelock types and not others
                    if (mWakeLockType == RIL.FOR_WAKELOCK) {
                        Rlog.e(LOG_TAG, "RILRequest releasing with held wake lock: "
                                + serialString());
                    }
                }
            }
        }
    }

    private RILRequest() {
    }

    static void resetSerial() {
        // use a random so that on recovery we probably don't mix old requests
        // with new.
        sNextSerial.set(sRandom.nextInt());
    }

    String serialString() {
        //Cheesy way to do %04d
        StringBuilder sb = new StringBuilder(8);
        String sn;

        long adjustedSerial = (((long) mSerial) - Integer.MIN_VALUE) % 10000;

        sn = Long.toString(adjustedSerial);

        //sb.append("J[");
        sb.append('[');
        for (int i = 0, s = sn.length(); i < 4 - s; i++) {
            sb.append('0');
        }

        sb.append(sn);
        sb.append(']');
        return sb.toString();
    }

    void onError(int error, Object ret) {
        CommandException ex;

        ex = CommandException.fromRilErrno(error);

        if (RIL.RILJ_LOGD) {
            Rlog.d(LOG_TAG, serialString() + "< "
                    + RIL.requestToString(mRequest)
                    + " error: " + ex + " ret=" + RIL.retToString(mRequest, ret));
        }

        if (mResult != null) {
            AsyncResult.forMessage(mResult, ret, ex);
            mResult.sendToTarget();
        }
    }
}
