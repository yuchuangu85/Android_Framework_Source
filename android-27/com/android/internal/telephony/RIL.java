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

import static com.android.internal.telephony.RILConstants.*;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.content.Context;
import android.hardware.radio.V1_0.Carrier;
import android.hardware.radio.V1_0.CarrierRestrictions;
import android.hardware.radio.V1_0.CdmaBroadcastSmsConfigInfo;
import android.hardware.radio.V1_0.CdmaSmsAck;
import android.hardware.radio.V1_0.CdmaSmsMessage;
import android.hardware.radio.V1_0.CdmaSmsWriteArgs;
import android.hardware.radio.V1_0.CellInfoCdma;
import android.hardware.radio.V1_0.CellInfoGsm;
import android.hardware.radio.V1_0.CellInfoLte;
import android.hardware.radio.V1_0.CellInfoType;
import android.hardware.radio.V1_0.CellInfoWcdma;
import android.hardware.radio.V1_0.DataProfileInfo;
import android.hardware.radio.V1_0.Dial;
import android.hardware.radio.V1_0.GsmBroadcastSmsConfigInfo;
import android.hardware.radio.V1_0.GsmSmsMessage;
import android.hardware.radio.V1_0.HardwareConfigModem;
import android.hardware.radio.V1_0.IRadio;
import android.hardware.radio.V1_0.IccIo;
import android.hardware.radio.V1_0.ImsSmsMessage;
import android.hardware.radio.V1_0.LceDataInfo;
import android.hardware.radio.V1_0.MvnoType;
import android.hardware.radio.V1_0.NvWriteItem;
import android.hardware.radio.V1_0.RadioError;
import android.hardware.radio.V1_0.RadioIndicationType;
import android.hardware.radio.V1_0.RadioResponseInfo;
import android.hardware.radio.V1_0.RadioResponseType;
import android.hardware.radio.V1_0.ResetNvType;
import android.hardware.radio.V1_0.SelectUiccSub;
import android.hardware.radio.V1_0.SetupDataCallResult;
import android.hardware.radio.V1_0.SimApdu;
import android.hardware.radio.V1_0.SmsWriteArgs;
import android.hardware.radio.V1_0.UusInfo;
import android.hardware.radio.deprecated.V1_0.IOemHook;
import android.net.ConnectivityManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HwBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.WorkSource;
import android.service.carrier.CarrierIdentifier;
import android.telephony.CellInfo;
import android.telephony.ClientRequestStats;
import android.telephony.ImsiEncryptionInfo;
import android.telephony.ModemActivityInfo;
import android.telephony.NeighboringCellInfo;
import android.telephony.NetworkScanRequest;
import android.telephony.PhoneNumberUtils;
import android.telephony.RadioAccessFamily;
import android.telephony.RadioAccessSpecifier;
import android.telephony.RadioNetworkConstants.RadioAccessNetworks;
import android.telephony.Rlog;
import android.telephony.SignalStrength;
import android.telephony.SmsManager;
import android.telephony.TelephonyHistogram;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.telephony.cdma.CdmaInformationRecords;
import com.android.internal.telephony.cdma.CdmaSmsBroadcastConfigInfo;
import com.android.internal.telephony.dataconnection.DataCallResponse;
import com.android.internal.telephony.dataconnection.DataProfile;
import com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.telephony.nano.TelephonyProto.SmsSession;
import com.android.internal.telephony.uicc.IccUtils;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@hide}
 */

class RILRequest {
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

    /**
     * Retrieves a new RILRequest instance from the pool.
     *
     * @param request RIL_REQUEST_*
     * @param result sent when operation completes
     * @return a RILRequest instance from the pool.
     */
    private static RILRequest obtain(int request, Message result) {
        RILRequest rr = null;

        synchronized(sPoolSync) {
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
    static RILRequest obtain(int request, Message result, WorkSource workSource) {
        RILRequest rr = null;

        rr = obtain(request, result);
        if(workSource != null) {
            rr.mWorkSource = workSource;
            rr.mClientId = String.valueOf(workSource.get(0)) + ":" + workSource.getName(0);
        } else {
            Rlog.e(LOG_TAG, "null workSource " + request);
        }

        return rr;
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
                if(mWakeLockType != RIL.INVALID_WAKELOCK) {
                    //This is OK for some wakelock types and not others
                    if(mWakeLockType == RIL.FOR_WAKELOCK) {
                        Rlog.e(LOG_TAG, "RILRequest releasing with held wake lock: "
                                + serialString());
                    }
                }
            }
        }
    }

    private RILRequest() {
    }

    static void
    resetSerial() {
        // use a random so that on recovery we probably don't mix old requests
        // with new.
        sNextSerial.set(sRandom.nextInt());
    }

    String
    serialString() {
        //Cheesy way to do %04d
        StringBuilder sb = new StringBuilder(8);
        String sn;

        long adjustedSerial = (((long)mSerial) - Integer.MIN_VALUE)%10000;

        sn = Long.toString(adjustedSerial);

        //sb.append("J[");
        sb.append('[');
        for (int i = 0, s = sn.length() ; i < 4 - s; i++) {
            sb.append('0');
        }

        sb.append(sn);
        sb.append(']');
        return sb.toString();
    }

    void
    onError(int error, Object ret) {
        CommandException ex;

        ex = CommandException.fromRilErrno(error);

        if (RIL.RILJ_LOGD) Rlog.d(LOG_TAG, serialString() + "< "
            + RIL.requestToString(mRequest)
            + " error: " + ex + " ret=" + RIL.retToString(mRequest, ret));

        if (mResult != null) {
            AsyncResult.forMessage(mResult, ret, ex);
            mResult.sendToTarget();
        }
    }
}


/**
 * RIL implementation of the CommandsInterface.
 *
 * {@hide}
 */
public final class RIL extends BaseCommands implements CommandsInterface {
    static final String RILJ_LOG_TAG = "RILJ";
    // Have a separate wakelock instance for Ack
    static final String RILJ_ACK_WAKELOCK_NAME = "RILJ_ACK_WL";
    static final boolean RILJ_LOGD = true;
    static final boolean RILJ_LOGV = false; // STOPSHIP if true
    static final int RIL_HISTOGRAM_BUCKET_COUNT = 5;

    /**
     * Wake lock timeout should be longer than the longest timeout in
     * the vendor ril.
     */
    private static final int DEFAULT_WAKE_LOCK_TIMEOUT_MS = 60000;

    // Wake lock default timeout associated with ack
    private static final int DEFAULT_ACK_WAKE_LOCK_TIMEOUT_MS = 200;

    private static final int DEFAULT_BLOCKING_MESSAGE_RESPONSE_TIMEOUT_MS = 2000;

    // Variables used to differentiate ack messages from request while calling clearWakeLock()
    public static final int INVALID_WAKELOCK = -1;
    public static final int FOR_WAKELOCK = 0;
    public static final int FOR_ACK_WAKELOCK = 1;
    private final ClientWakelockTracker mClientWakelockTracker = new ClientWakelockTracker();

    //***** Instance Variables

    final WakeLock mWakeLock;           // Wake lock associated with request/response
    final WakeLock mAckWakeLock;        // Wake lock associated with ack sent
    final int mWakeLockTimeout;         // Timeout associated with request/response
    final int mAckWakeLockTimeout;      // Timeout associated with ack sent
    // The number of wakelock requests currently active.  Don't release the lock
    // until dec'd to 0
    int mWakeLockCount;

    // Variables used to identify releasing of WL on wakelock timeouts
    volatile int mWlSequenceNum = 0;
    volatile int mAckWlSequenceNum = 0;

    SparseArray<RILRequest> mRequestList = new SparseArray<RILRequest>();
    static SparseArray<TelephonyHistogram> mRilTimeHistograms = new
            SparseArray<TelephonyHistogram>();

    Object[]     mLastNITZTimeInfo;

    // When we are testing emergency calls
    AtomicBoolean mTestingEmergencyCall = new AtomicBoolean(false);

    final Integer mPhoneId;

    /* default work source which will blame phone process */
    private WorkSource mRILDefaultWorkSource;

    /* Worksource containing all applications causing wakelock to be held */
    private WorkSource mActiveWakelockWorkSource;

    /** Telephony metrics instance for logging metrics event */
    private TelephonyMetrics mMetrics = TelephonyMetrics.getInstance();

    boolean mIsMobileNetworkSupported;
    RadioResponse mRadioResponse;
    RadioIndication mRadioIndication;
    volatile IRadio mRadioProxy = null;
    OemHookResponse mOemHookResponse;
    OemHookIndication mOemHookIndication;
    volatile IOemHook mOemHookProxy = null;
    final AtomicLong mRadioProxyCookie = new AtomicLong(0);
    final RadioProxyDeathRecipient mRadioProxyDeathRecipient;
    final RilHandler mRilHandler;

    //***** Events
    static final int EVENT_WAKE_LOCK_TIMEOUT    = 2;
    static final int EVENT_ACK_WAKE_LOCK_TIMEOUT    = 4;
    static final int EVENT_BLOCKING_RESPONSE_TIMEOUT = 5;
    static final int EVENT_RADIO_PROXY_DEAD     = 6;

    //***** Constants

    static final String[] HIDL_SERVICE_NAME = {"slot1", "slot2", "slot3"};

    static final int IRADIO_GET_SERVICE_DELAY_MILLIS = 4 * 1000;

    public static List<TelephonyHistogram> getTelephonyRILTimingHistograms() {
        List<TelephonyHistogram> list;
        synchronized (mRilTimeHistograms) {
            list = new ArrayList<>(mRilTimeHistograms.size());
            for (int i = 0; i < mRilTimeHistograms.size(); i++) {
                TelephonyHistogram entry = new TelephonyHistogram(mRilTimeHistograms.valueAt(i));
                list.add(entry);
            }
        }
        return list;
    }

    class RilHandler extends Handler {
        //***** Handler implementation
        @Override public void
        handleMessage(Message msg) {
            RILRequest rr;

            switch (msg.what) {
                case EVENT_WAKE_LOCK_TIMEOUT:
                    // Haven't heard back from the last request.  Assume we're
                    // not getting a response and  release the wake lock.

                    // The timer of WAKE_LOCK_TIMEOUT is reset with each
                    // new send request. So when WAKE_LOCK_TIMEOUT occurs
                    // all requests in mRequestList already waited at
                    // least DEFAULT_WAKE_LOCK_TIMEOUT_MS but no response.
                    //
                    // Note: Keep mRequestList so that delayed response
                    // can still be handled when response finally comes.

                    synchronized (mRequestList) {
                        if (msg.arg1 == mWlSequenceNum && clearWakeLock(FOR_WAKELOCK)) {
                            if (RILJ_LOGD) {
                                int count = mRequestList.size();
                                Rlog.d(RILJ_LOG_TAG, "WAKE_LOCK_TIMEOUT " +
                                        " mRequestList=" + count);
                                for (int i = 0; i < count; i++) {
                                    rr = mRequestList.valueAt(i);
                                    Rlog.d(RILJ_LOG_TAG, i + ": [" + rr.mSerial + "] "
                                            + requestToString(rr.mRequest));
                                }
                            }
                        }
                    }
                    break;

                case EVENT_ACK_WAKE_LOCK_TIMEOUT:
                    if (msg.arg1 == mAckWlSequenceNum && clearWakeLock(FOR_ACK_WAKELOCK)) {
                        if (RILJ_LOGV) {
                            Rlog.d(RILJ_LOG_TAG, "ACK_WAKE_LOCK_TIMEOUT");
                        }
                    }
                    break;

                case EVENT_BLOCKING_RESPONSE_TIMEOUT:
                    int serial = msg.arg1;
                    rr = findAndRemoveRequestFromList(serial);
                    // If the request has already been processed, do nothing
                    if(rr == null) {
                        break;
                    }

                    //build a response if expected
                    if (rr.mResult != null) {
                        Object timeoutResponse = getResponseForTimedOutRILRequest(rr);
                        AsyncResult.forMessage( rr.mResult, timeoutResponse, null);
                        rr.mResult.sendToTarget();
                        mMetrics.writeOnRilTimeoutResponse(mPhoneId, rr.mSerial, rr.mRequest);
                    }

                    decrementWakeLock(rr);
                    rr.release();
                    break;

                case EVENT_RADIO_PROXY_DEAD:
                    riljLog("handleMessage: EVENT_RADIO_PROXY_DEAD cookie = " + msg.obj +
                            " mRadioProxyCookie = " + mRadioProxyCookie.get());
                    if ((long) msg.obj == mRadioProxyCookie.get()) {
                        resetProxyAndRequestList();

                        // todo: rild should be back up since message was sent with a delay. this is
                        // a hack.
                        getRadioProxy(null);
                        getOemHookProxy(null);
                    }
                    break;
            }
        }
    }

    /**
     * In order to prevent calls to Telephony from waiting indefinitely
     * low-latency blocking calls will eventually time out. In the event of
     * a timeout, this function generates a response that is returned to the
     * higher layers to unblock the call. This is in lieu of a meaningful
     * response.
     * @param rr The RIL Request that has timed out.
     * @return A default object, such as the one generated by a normal response
     * that is returned to the higher layers.
     **/
    private static Object getResponseForTimedOutRILRequest(RILRequest rr) {
        if (rr == null ) return null;

        Object timeoutResponse = null;
        switch(rr.mRequest) {
            case RIL_REQUEST_GET_ACTIVITY_INFO:
                timeoutResponse = new ModemActivityInfo(
                        0, 0, 0, new int [ModemActivityInfo.TX_POWER_LEVELS], 0, 0);
                break;
        };
        return timeoutResponse;
    }

    final class RadioProxyDeathRecipient implements HwBinder.DeathRecipient {
        @Override
        public void serviceDied(long cookie) {
            // Deal with service going away
            riljLog("serviceDied");
            // todo: temp hack to send delayed message so that rild is back up by then
            //mRilHandler.sendMessage(mRilHandler.obtainMessage(EVENT_RADIO_PROXY_DEAD, cookie));
            mRilHandler.sendMessageDelayed(
                    mRilHandler.obtainMessage(EVENT_RADIO_PROXY_DEAD, cookie),
                    IRADIO_GET_SERVICE_DELAY_MILLIS);
        }
    }

    private void resetProxyAndRequestList() {
        mRadioProxy = null;
        mOemHookProxy = null;

        // increment the cookie so that death notification can be ignored
        mRadioProxyCookie.incrementAndGet();

        setRadioState(RadioState.RADIO_UNAVAILABLE);

        RILRequest.resetSerial();
        // Clear request list on close
        clearRequestList(RADIO_NOT_AVAILABLE, false);

        // todo: need to get service right away so setResponseFunctions() can be called for
        // unsolicited indications. getService() is not a blocking call, so it doesn't help to call
        // it here. Current hack is to call getService() on death notification after a delay.
    }

    private IRadio getRadioProxy(Message result) {
        if (!mIsMobileNetworkSupported) {
            if (RILJ_LOGV) riljLog("getRadioProxy: Not calling getService(): wifi-only");
            if (result != null) {
                AsyncResult.forMessage(result, null,
                        CommandException.fromRilErrno(RADIO_NOT_AVAILABLE));
                result.sendToTarget();
            }
            return null;
        }

        if (mRadioProxy != null) {
            return mRadioProxy;
        }

        try {
            mRadioProxy = IRadio.getService(HIDL_SERVICE_NAME[mPhoneId == null ? 0 : mPhoneId]);
            if (mRadioProxy != null) {
                mRadioProxy.linkToDeath(mRadioProxyDeathRecipient,
                        mRadioProxyCookie.incrementAndGet());
                mRadioProxy.setResponseFunctions(mRadioResponse, mRadioIndication);
            } else {
                riljLoge("getRadioProxy: mRadioProxy == null");
            }
        } catch (RemoteException | RuntimeException e) {
            mRadioProxy = null;
            riljLoge("RadioProxy getService/setResponseFunctions: " + e);
        }

        if (mRadioProxy == null) {
            if (result != null) {
                AsyncResult.forMessage(result, null,
                        CommandException.fromRilErrno(RADIO_NOT_AVAILABLE));
                result.sendToTarget();
            }

            // if service is not up, treat it like death notification to try to get service again
            mRilHandler.sendMessageDelayed(
                    mRilHandler.obtainMessage(EVENT_RADIO_PROXY_DEAD,
                            mRadioProxyCookie.incrementAndGet()),
                    IRADIO_GET_SERVICE_DELAY_MILLIS);
        }

        return mRadioProxy;
    }

    private IOemHook getOemHookProxy(Message result) {
        if (!mIsMobileNetworkSupported) {
            if (RILJ_LOGV) riljLog("getOemHookProxy: Not calling getService(): wifi-only");
            if (result != null) {
                AsyncResult.forMessage(result, null,
                        CommandException.fromRilErrno(RADIO_NOT_AVAILABLE));
                result.sendToTarget();
            }
            return null;
        }

        if (mOemHookProxy != null) {
            return mOemHookProxy;
        }

        try {
            mOemHookProxy = IOemHook.getService(
                    HIDL_SERVICE_NAME[mPhoneId == null ? 0 : mPhoneId]);
            if (mOemHookProxy != null) {
                // not calling linkToDeath() as ril service runs in the same process and death
                // notification for that should be sufficient
                mOemHookProxy.setResponseFunctions(mOemHookResponse, mOemHookIndication);
            } else {
                riljLoge("getOemHookProxy: mOemHookProxy == null");
            }
        } catch (RemoteException | RuntimeException e) {
            mOemHookProxy = null;
            riljLoge("OemHookProxy getService/setResponseFunctions: " + e);
        }

        if (mOemHookProxy == null) {
            if (result != null) {
                AsyncResult.forMessage(result, null,
                        CommandException.fromRilErrno(RADIO_NOT_AVAILABLE));
                result.sendToTarget();
            }

            // if service is not up, treat it like death notification to try to get service again
            mRilHandler.sendMessageDelayed(
                    mRilHandler.obtainMessage(EVENT_RADIO_PROXY_DEAD,
                            mRadioProxyCookie.incrementAndGet()),
                    IRADIO_GET_SERVICE_DELAY_MILLIS);
        }

        return mOemHookProxy;
    }

    //***** Constructors

    public RIL(Context context, int preferredNetworkType, int cdmaSubscription) {
        this(context, preferredNetworkType, cdmaSubscription, null);
    }

    public RIL(Context context, int preferredNetworkType,
            int cdmaSubscription, Integer instanceId) {
        super(context);
        if (RILJ_LOGD) {
            riljLog("RIL: init preferredNetworkType=" + preferredNetworkType
                    + " cdmaSubscription=" + cdmaSubscription + ")");
        }

        mContext = context;
        mCdmaSubscription  = cdmaSubscription;
        mPreferredNetworkType = preferredNetworkType;
        mPhoneType = RILConstants.NO_PHONE;
        mPhoneId = instanceId;

        ConnectivityManager cm = (ConnectivityManager)context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        mIsMobileNetworkSupported = cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);

        mRadioResponse = new RadioResponse(this);
        mRadioIndication = new RadioIndication(this);
        mOemHookResponse = new OemHookResponse(this);
        mOemHookIndication = new OemHookIndication(this);
        mRilHandler = new RilHandler();
        mRadioProxyDeathRecipient = new RadioProxyDeathRecipient();

        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, RILJ_LOG_TAG);
        mWakeLock.setReferenceCounted(false);
        mAckWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, RILJ_ACK_WAKELOCK_NAME);
        mAckWakeLock.setReferenceCounted(false);
        mWakeLockTimeout = SystemProperties.getInt(TelephonyProperties.PROPERTY_WAKE_LOCK_TIMEOUT,
                DEFAULT_WAKE_LOCK_TIMEOUT_MS);
        mAckWakeLockTimeout = SystemProperties.getInt(
                TelephonyProperties.PROPERTY_WAKE_LOCK_TIMEOUT, DEFAULT_ACK_WAKE_LOCK_TIMEOUT_MS);
        mWakeLockCount = 0;
        mRILDefaultWorkSource = new WorkSource(context.getApplicationInfo().uid,
                context.getPackageName());

        TelephonyDevController tdc = TelephonyDevController.getInstance();
        tdc.registerRIL(this);

        // set radio callback; needed to set RadioIndication callback (should be done after
        // wakelock stuff is initialized above as callbacks are received on separate binder threads)
        getRadioProxy(null);
        getOemHookProxy(null);
    }

    @Override public void
    setOnNITZTime(Handler h, int what, Object obj) {
        super.setOnNITZTime(h, what, obj);

        // Send the last NITZ time if we have it
        if (mLastNITZTimeInfo != null) {
            mNITZTimeRegistrant
                .notifyRegistrant(
                    new AsyncResult (null, mLastNITZTimeInfo, null));
        }
    }

    private void addRequest(RILRequest rr) {
        acquireWakeLock(rr, FOR_WAKELOCK);
        synchronized (mRequestList) {
            rr.mStartTimeMs = SystemClock.elapsedRealtime();
            mRequestList.append(rr.mSerial, rr);
        }
    }

    private RILRequest obtainRequest(int request, Message result, WorkSource workSource) {
        RILRequest rr = RILRequest.obtain(request, result, workSource);
        addRequest(rr);
        return rr;
    }

    private void handleRadioProxyExceptionForRR(RILRequest rr, String caller, Exception e) {
        riljLoge(caller + ": " + e);
        resetProxyAndRequestList();

        // service most likely died, handle exception like death notification to try to get service
        // again
        mRilHandler.sendMessageDelayed(
                mRilHandler.obtainMessage(EVENT_RADIO_PROXY_DEAD,
                        mRadioProxyCookie.incrementAndGet()),
                IRADIO_GET_SERVICE_DELAY_MILLIS);
    }

    private String convertNullToEmptyString(String string) {
        return string != null ? string : "";
    }

    @Override
    public void
    getIccCardStatus(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_SIM_STATUS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getIccCardStatus(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getIccCardStatus", e);
            }
        }
    }

    @Override public void
    supplyIccPin(String pin, Message result) {
        supplyIccPinForApp(pin, null, result);
    }

    @Override public void
    supplyIccPinForApp(String pin, String aid, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ENTER_SIM_PIN, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " aid = " + aid);
            }

            try {
                radioProxy.supplyIccPinForApp(rr.mSerial,
                        convertNullToEmptyString(pin),
                        convertNullToEmptyString(aid));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "supplyIccPinForApp", e);
            }
        }
    }

    @Override
    public void supplyIccPuk(String puk, String newPin, Message result) {
        supplyIccPukForApp(puk, newPin, null, result);
    }

    @Override
    public void supplyIccPukForApp(String puk, String newPin, String aid, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ENTER_SIM_PUK, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " aid = " + aid);
            }

            try {
                radioProxy.supplyIccPukForApp(rr.mSerial,
                        convertNullToEmptyString(puk),
                        convertNullToEmptyString(newPin),
                        convertNullToEmptyString(aid));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "supplyIccPukForApp", e);
            }
        }
    }

    @Override public void
    supplyIccPin2(String pin, Message result) {
        supplyIccPin2ForApp(pin, null, result);
    }

    @Override public void
    supplyIccPin2ForApp(String pin, String aid, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ENTER_SIM_PIN2, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " aid = " + aid);
            }

            try {
                radioProxy.supplyIccPin2ForApp(rr.mSerial,
                        convertNullToEmptyString(pin),
                        convertNullToEmptyString(aid));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "supplyIccPin2ForApp", e);
            }
        }
    }

    @Override public void
    supplyIccPuk2(String puk2, String newPin2, Message result) {
        supplyIccPuk2ForApp(puk2, newPin2, null, result);
    }

    @Override public void
    supplyIccPuk2ForApp(String puk, String newPin2, String aid, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ENTER_SIM_PUK2, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " aid = " + aid);
            }

            try {
                radioProxy.supplyIccPuk2ForApp(rr.mSerial,
                        convertNullToEmptyString(puk),
                        convertNullToEmptyString(newPin2),
                        convertNullToEmptyString(aid));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "supplyIccPuk2ForApp", e);
            }
        }
    }

    @Override public void
    changeIccPin(String oldPin, String newPin, Message result) {
        changeIccPinForApp(oldPin, newPin, null, result);
    }

    @Override public void
    changeIccPinForApp(String oldPin, String newPin, String aid, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CHANGE_SIM_PIN, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " oldPin = "
                        + oldPin + " newPin = " + newPin + " aid = " + aid);
            }

            try {
                radioProxy.changeIccPinForApp(rr.mSerial,
                        convertNullToEmptyString(oldPin),
                        convertNullToEmptyString(newPin),
                        convertNullToEmptyString(aid));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "changeIccPinForApp", e);
            }
        }
    }

    @Override public void
    changeIccPin2(String oldPin2, String newPin2, Message result) {
        changeIccPin2ForApp(oldPin2, newPin2, null, result);
    }

    @Override public void
    changeIccPin2ForApp(String oldPin2, String newPin2, String aid, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CHANGE_SIM_PIN2, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " oldPin = "
                        + oldPin2 + " newPin = " + newPin2 + " aid = " + aid);
            }

            try {
                radioProxy.changeIccPin2ForApp(rr.mSerial,
                        convertNullToEmptyString(oldPin2),
                        convertNullToEmptyString(newPin2),
                        convertNullToEmptyString(aid));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "changeIccPin2ForApp", e);
            }
        }
    }

    @Override
    public void supplyNetworkDepersonalization(String netpin, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ENTER_NETWORK_DEPERSONALIZATION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " netpin = "
                        + netpin);
            }

            try {
                radioProxy.supplyNetworkDepersonalization(rr.mSerial,
                        convertNullToEmptyString(netpin));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "supplyNetworkDepersonalization", e);
            }
        }
    }

    @Override
    public void getCurrentCalls(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_CURRENT_CALLS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.getCurrentCalls(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getCurrentCalls", e);
            }
        }
    }

    @Override
    public void dial(String address, int clirMode, Message result) {
        dial(address, clirMode, null, result);
    }

    @Override
    public void dial(String address, int clirMode, UUSInfo uusInfo, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DIAL, result,
                    mRILDefaultWorkSource);

            Dial dialInfo = new Dial();
            dialInfo.address = convertNullToEmptyString(address);
            dialInfo.clir = clirMode;
            if (uusInfo != null) {
                UusInfo info = new UusInfo();
                info.uusType = uusInfo.getType();
                info.uusDcs = uusInfo.getDcs();
                info.uusData = new String(uusInfo.getUserData());
                dialInfo.uusInfo.add(info);
            }

            if (RILJ_LOGD) {
                // Do not log function arg for privacy
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.dial(rr.mSerial, dialInfo);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "dial", e);
            }
        }
    }

    @Override
    public void getIMSI(Message result) {
        getIMSIForApp(null, result);
    }

    @Override
    public void getIMSIForApp(String aid, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_IMSI, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString()
                        + ">  " + requestToString(rr.mRequest) + " aid = " + aid);
            }
            try {
                radioProxy.getImsiForApp(rr.mSerial, convertNullToEmptyString(aid));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getIMSIForApp", e);
            }
        }
    }

    @Override
    public void hangupConnection(int gsmIndex, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_HANGUP, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " gsmIndex = "
                        + gsmIndex);
            }

            try {
                radioProxy.hangup(rr.mSerial, gsmIndex);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "hangupConnection", e);
            }
        }
    }

    @Override
    public void hangupWaitingOrBackground(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.hangupWaitingOrBackground(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "hangupWaitingOrBackground", e);
            }
        }
    }

    @Override
    public void hangupForegroundResumeBackground(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.hangupForegroundResumeBackground(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "hangupForegroundResumeBackground", e);
            }
        }
    }

    @Override
    public void switchWaitingOrHoldingAndActive(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.switchWaitingOrHoldingAndActive(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "switchWaitingOrHoldingAndActive", e);
            }
        }
    }

    @Override
    public void conference(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CONFERENCE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.conference(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "conference", e);
            }
        }
    }

    @Override
    public void rejectCall(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_UDUB, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.rejectCall(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "rejectCall", e);
            }
        }
    }

    @Override
    public void getLastCallFailCause(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_LAST_CALL_FAIL_CAUSE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getLastCallFailCause(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getLastCallFailCause", e);
            }
        }
    }

    @Override
    public void getSignalStrength(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SIGNAL_STRENGTH, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getSignalStrength(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getSignalStrength", e);
            }
        }
    }

    @Override
    public void getVoiceRegistrationState(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_VOICE_REGISTRATION_STATE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getVoiceRegistrationState(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getVoiceRegistrationState", e);
            }
        }
    }

    @Override
    public void getDataRegistrationState(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DATA_REGISTRATION_STATE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getDataRegistrationState(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getDataRegistrationState", e);
            }
        }
    }

    @Override
    public void getOperator(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_OPERATOR, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getOperator(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getOperator", e);
            }
        }
    }

    @Override
    public void setRadioPower(boolean on, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_RADIO_POWER, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " on = " + on);
            }

            try {
                radioProxy.setRadioPower(rr.mSerial, on);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setRadioPower", e);
            }
        }
    }

    @Override
    public void sendDtmf(char c, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DTMF, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                // Do not log function arg for privacy
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.sendDtmf(rr.mSerial, c + "");
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendDtmf", e);
            }
        }
    }

    private GsmSmsMessage constructGsmSendSmsRilRequest(String smscPdu, String pdu) {
        GsmSmsMessage msg = new GsmSmsMessage();
        msg.smscPdu = smscPdu == null ? "" : smscPdu;
        msg.pdu = pdu == null ? "" : pdu;
        return msg;
    }

    @Override
    public void sendSMS(String smscPdu, String pdu, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SEND_SMS, result,
                    mRILDefaultWorkSource);

            // Do not log function args for privacy
            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            GsmSmsMessage msg = constructGsmSendSmsRilRequest(smscPdu, pdu);

            try {
                radioProxy.sendSms(rr.mSerial, msg);
                mMetrics.writeRilSendSms(mPhoneId, rr.mSerial, SmsSession.Event.Tech.SMS_GSM,
                        SmsSession.Event.Format.SMS_FORMAT_3GPP);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendSMS", e);
            }
        }
    }

    @Override
    public void sendSMSExpectMore(String smscPdu, String pdu, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SEND_SMS_EXPECT_MORE, result,
                    mRILDefaultWorkSource);

            // Do not log function arg for privacy
            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            GsmSmsMessage msg = constructGsmSendSmsRilRequest(smscPdu, pdu);

            try {
                radioProxy.sendSMSExpectMore(rr.mSerial, msg);
                mMetrics.writeRilSendSms(mPhoneId, rr.mSerial, SmsSession.Event.Tech.SMS_GSM,
                        SmsSession.Event.Format.SMS_FORMAT_3GPP);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendSMSExpectMore", e);
            }
        }
    }

    /**
     * Convert MVNO type string into MvnoType defined in types.hal.
     * @param mvnoType MVNO type
     * @return MVNO type in integer
     */
    private static int convertToHalMvnoType(String mvnoType) {
        switch (mvnoType) {
            case "imsi" : return MvnoType.IMSI;
            case "gid" : return MvnoType.GID;
            case "spn" : return MvnoType.SPN;
            default: return MvnoType.NONE;
        }
    }

    /**
     * Convert to DataProfileInfo defined in types.hal
     * @param dp Data profile
     * @return A converted data profile
     */
    private static DataProfileInfo convertToHalDataProfile(DataProfile dp) {
        DataProfileInfo dpi = new DataProfileInfo();

        dpi.profileId = dp.profileId;
        dpi.apn = dp.apn;
        dpi.protocol = dp.protocol;
        dpi.roamingProtocol = dp.roamingProtocol;
        dpi.authType = dp.authType;
        dpi.user = dp.user;
        dpi.password = dp.password;
        dpi.type = dp.type;
        dpi.maxConnsTime = dp.maxConnsTime;
        dpi.maxConns = dp.maxConns;
        dpi.waitTime = dp.waitTime;
        dpi.enabled = dp.enabled;
        dpi.supportedApnTypesBitmap = dp.supportedApnTypesBitmap;
        dpi.bearerBitmap = dp.bearerBitmap;
        dpi.mtu = dp.mtu;
        dpi.mvnoType = convertToHalMvnoType(dp.mvnoType);
        dpi.mvnoMatchData = dp.mvnoMatchData;

        return dpi;
    }

    /**
     * Convert NV reset type into ResetNvType defined in types.hal.
     * @param resetType NV reset type.
     * @return Converted reset type in integer or -1 if param is invalid.
     */
    private static int convertToHalResetNvType(int resetType) {
        /**
         * resetType values
         * 1 - reload all NV items
         * 2 - erase NV reset (SCRTN)
         * 3 - factory reset (RTN)
         */
        switch (resetType) {
            case 1: return ResetNvType.RELOAD;
            case 2: return ResetNvType.ERASE;
            case 3: return ResetNvType.FACTORY_RESET;
        }
        return -1;
    }

    /**
     * Convert SetupDataCallResult defined in types.hal into DataCallResponse
     * @param dcResult setup data call result
     * @return converted DataCallResponse object
     */
    static DataCallResponse convertDataCallResult(SetupDataCallResult dcResult) {
        return new DataCallResponse(dcResult.status,
                dcResult.suggestedRetryTime,
                dcResult.cid,
                dcResult.active,
                dcResult.type,
                dcResult.ifname,
                dcResult.addresses,
                dcResult.dnses,
                dcResult.gateways,
                dcResult.pcscf,
                dcResult.mtu
        );
    }

    @Override
    public void setupDataCall(int radioTechnology, DataProfile dataProfile, boolean isRoaming,
                              boolean allowRoaming, Message result) {

        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {

            RILRequest rr = obtainRequest(RIL_REQUEST_SETUP_DATA_CALL, result,
                    mRILDefaultWorkSource);

            // Convert to HAL data profile
            DataProfileInfo dpi = convertToHalDataProfile(dataProfile);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + ",radioTechnology=" + radioTechnology + ",isRoaming="
                        + isRoaming + ",allowRoaming=" + allowRoaming + "," + dataProfile);
            }

            try {
                radioProxy.setupDataCall(rr.mSerial, radioTechnology, dpi,
                        dataProfile.modemCognitive, allowRoaming, isRoaming);
                mMetrics.writeRilSetupDataCall(mPhoneId, rr.mSerial, radioTechnology, dpi.profileId,
                        dpi.apn, dpi.authType, dpi.protocol);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setupDataCall", e);
            }
        }
    }

    @Override
    public void iccIO(int command, int fileId, String path, int p1, int p2, int p3,
                      String data, String pin2, Message result) {
        iccIOForApp(command, fileId, path, p1, p2, p3, data, pin2, null, result);
    }

    @Override
    public void iccIOForApp(int command, int fileId, String path, int p1, int p2, int p3,
                 String data, String pin2, String aid, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SIM_IO, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> iccIO: "
                        + requestToString(rr.mRequest) + " command = 0x"
                        + Integer.toHexString(command) + " fileId = 0x"
                        + Integer.toHexString(fileId) + " path = " + path + " p1 = "
                        + p1 + " p2 = " + p2 + " p3 = " + " data = " + data
                        + " aid = " + aid);
            }

            IccIo iccIo = new IccIo();
            iccIo.command = command;
            iccIo.fileId = fileId;
            iccIo.path = convertNullToEmptyString(path);
            iccIo.p1 = p1;
            iccIo.p2 = p2;
            iccIo.p3 = p3;
            iccIo.data = convertNullToEmptyString(data);
            iccIo.pin2 = convertNullToEmptyString(pin2);
            iccIo.aid = convertNullToEmptyString(aid);

            try {
                radioProxy.iccIOForApp(rr.mSerial, iccIo);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "iccIOForApp", e);
            }
        }
    }

    @Override
    public void sendUSSD(String ussd, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SEND_USSD, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                String logUssd = "*******";
                if (RILJ_LOGV) logUssd = ussd;
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " ussd = " + logUssd);
            }

            try {
                radioProxy.sendUssd(rr.mSerial, convertNullToEmptyString(ussd));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendUSSD", e);
            }
        }
    }

    @Override
    public void cancelPendingUssd(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CANCEL_USSD, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString()
                        + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.cancelPendingUssd(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "cancelPendingUssd", e);
            }
        }
    }

    @Override
    public void getCLIR(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_CLIR, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getClir(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getCLIR", e);
            }
        }
    }

    @Override
    public void setCLIR(int clirMode, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_CLIR, result, mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " clirMode = " + clirMode);
            }

            try {
                radioProxy.setClir(rr.mSerial, clirMode);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setCLIR", e);
            }
        }
    }

    @Override
    public void queryCallForwardStatus(int cfReason, int serviceClass,
                           String number, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_CALL_FORWARD_STATUS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " cfreason = " + cfReason + " serviceClass = " + serviceClass);
            }

            android.hardware.radio.V1_0.CallForwardInfo cfInfo =
                    new android.hardware.radio.V1_0.CallForwardInfo();
            cfInfo.reason = cfReason;
            cfInfo.serviceClass = serviceClass;
            cfInfo.toa = PhoneNumberUtils.toaFromString(number);
            cfInfo.number = convertNullToEmptyString(number);
            cfInfo.timeSeconds = 0;

            try {
                radioProxy.getCallForwardStatus(rr.mSerial, cfInfo);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "queryCallForwardStatus", e);
            }
        }
    }

    @Override
    public void setCallForward(int action, int cfReason, int serviceClass,
                   String number, int timeSeconds, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_CALL_FORWARD, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " action = " + action + " cfReason = " + cfReason + " serviceClass = "
                        + serviceClass + " timeSeconds = " + timeSeconds);
            }

            android.hardware.radio.V1_0.CallForwardInfo cfInfo =
                    new android.hardware.radio.V1_0.CallForwardInfo();
            cfInfo.status = action;
            cfInfo.reason = cfReason;
            cfInfo.serviceClass = serviceClass;
            cfInfo.toa = PhoneNumberUtils.toaFromString(number);
            cfInfo.number = convertNullToEmptyString(number);
            cfInfo.timeSeconds = timeSeconds;

            try {
                radioProxy.setCallForward(rr.mSerial, cfInfo);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setCallForward", e);

            }
        }
    }

    @Override
    public void queryCallWaiting(int serviceClass, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_CALL_WAITING, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " serviceClass = " + serviceClass);
            }

            try {
                radioProxy.getCallWaiting(rr.mSerial, serviceClass);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "queryCallWaiting", e);
            }
        }
    }

    @Override
    public void setCallWaiting(boolean enable, int serviceClass, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_CALL_WAITING, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " enable = " + enable + " serviceClass = " + serviceClass);
            }

            try {
                radioProxy.setCallWaiting(rr.mSerial, enable, serviceClass);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setCallWaiting", e);
            }
        }
    }

    @Override
    public void acknowledgeLastIncomingGsmSms(boolean success, int cause, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SMS_ACKNOWLEDGE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " success = " + success + " cause = " + cause);
            }

            try {
                radioProxy.acknowledgeLastIncomingGsmSms(rr.mSerial, success, cause);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "acknowledgeLastIncomingGsmSms", e);
            }
        }
    }

    @Override
    public void acceptCall(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ANSWER, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.acceptCall(rr.mSerial);
                mMetrics.writeRilAnswer(mPhoneId, rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "acceptCall", e);
            }
        }
    }

    @Override
    public void deactivateDataCall(int cid, int reason, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DEACTIVATE_DATA_CALL, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> "
                        + requestToString(rr.mRequest) + " cid = " + cid + " reason = " + reason);
            }

            try {
                radioProxy.deactivateDataCall(rr.mSerial, cid, (reason == 0) ? false : true);
                mMetrics.writeRilDeactivateDataCall(mPhoneId, rr.mSerial,
                        cid, reason);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "deactivateDataCall", e);
            }
        }
    }

    @Override
    public void queryFacilityLock(String facility, String password, int serviceClass,
                                  Message result) {
        queryFacilityLockForApp(facility, password, serviceClass, null, result);
    }

    @Override
    public void queryFacilityLockForApp(String facility, String password, int serviceClass,
                                        String appId, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_FACILITY_LOCK, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " facility = " + facility + " serviceClass = " + serviceClass
                        + " appId = " + appId);
            }

            try {
                radioProxy.getFacilityLockForApp(rr.mSerial,
                        convertNullToEmptyString(facility),
                        convertNullToEmptyString(password),
                        serviceClass,
                        convertNullToEmptyString(appId));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getFacilityLockForApp", e);
            }
        }
    }

    @Override
    public void setFacilityLock(String facility, boolean lockState, String password,
                                int serviceClass, Message result) {
        setFacilityLockForApp(facility, lockState, password, serviceClass, null, result);
    }

    @Override
    public void setFacilityLockForApp(String facility, boolean lockState, String password,
                                      int serviceClass, String appId, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_FACILITY_LOCK, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " facility = " + facility + " lockstate = " + lockState
                        + " serviceClass = " + serviceClass + " appId = " + appId);
            }

            try {
                radioProxy.setFacilityLockForApp(rr.mSerial,
                        convertNullToEmptyString(facility),
                        lockState,
                        convertNullToEmptyString(password),
                        serviceClass,
                        convertNullToEmptyString(appId));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setFacilityLockForApp", e);
            }
        }
    }

    @Override
    public void changeBarringPassword(String facility, String oldPwd, String newPwd,
                                      Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CHANGE_BARRING_PASSWORD, result,
                    mRILDefaultWorkSource);

            // Do not log all function args for privacy
            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + "facility = " + facility);
            }

            try {
                radioProxy.setBarringPassword(rr.mSerial,
                        convertNullToEmptyString(facility),
                        convertNullToEmptyString(oldPwd),
                        convertNullToEmptyString(newPwd));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "changeBarringPassword", e);
            }
        }
    }

    @Override
    public void getNetworkSelectionMode(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getNetworkSelectionMode(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getNetworkSelectionMode", e);
            }
        }
    }

    @Override
    public void setNetworkSelectionModeAutomatic(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.setNetworkSelectionModeAutomatic(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setNetworkSelectionModeAutomatic", e);
            }
        }
    }

    @Override
    public void setNetworkSelectionModeManual(String operatorNumeric, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " operatorNumeric = " + operatorNumeric);
            }

            try {
                radioProxy.setNetworkSelectionModeManual(rr.mSerial,
                        convertNullToEmptyString(operatorNumeric));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setNetworkSelectionModeManual", e);
            }
        }
    }

    @Override
    public void getAvailableNetworks(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_AVAILABLE_NETWORKS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getAvailableNetworks(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getAvailableNetworks", e);
            }
        }
    }

    @Override
    public void startNetworkScan(NetworkScanRequest nsr, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            android.hardware.radio.V1_1.IRadio radioProxy11 =
                    android.hardware.radio.V1_1.IRadio.castFrom(radioProxy);
            if (radioProxy11 == null) {
                if (result != null) {
                    AsyncResult.forMessage(result, null,
                            CommandException.fromRilErrno(REQUEST_NOT_SUPPORTED));
                    result.sendToTarget();
                }
            } else {
                android.hardware.radio.V1_1.NetworkScanRequest request =
                        new android.hardware.radio.V1_1.NetworkScanRequest();
                request.type = nsr.scanType;
                request.interval = 60;
                for (RadioAccessSpecifier ras : nsr.specifiers) {
                    android.hardware.radio.V1_1.RadioAccessSpecifier s =
                            new android.hardware.radio.V1_1.RadioAccessSpecifier();
                    s.radioAccessNetwork = ras.radioAccessNetwork;
                    List<Integer> bands = null;
                    switch (ras.radioAccessNetwork) {
                        case RadioAccessNetworks.GERAN:
                            bands = s.geranBands;
                            break;
                        case RadioAccessNetworks.UTRAN:
                            bands = s.utranBands;
                            break;
                        case RadioAccessNetworks.EUTRAN:
                            bands = s.eutranBands;
                            break;
                        default:
                            Log.wtf(RILJ_LOG_TAG, "radioAccessNetwork " + ras.radioAccessNetwork
                                    + " not supported!");
                            return;
                    }
                    if (ras.bands != null) {
                        for (int band : ras.bands) {
                            bands.add(band);
                        }
                    }
                    if (ras.channels != null) {
                        for (int channel : ras.channels) {
                            s.channels.add(channel);
                        }
                    }
                    request.specifiers.add(s);
                }

                RILRequest rr = obtainRequest(RIL_REQUEST_START_NETWORK_SCAN, result,
                        mRILDefaultWorkSource);

                if (RILJ_LOGD) {
                    riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
                }

                try {
                    radioProxy11.startNetworkScan(rr.mSerial, request);
                } catch (RemoteException | RuntimeException e) {
                    handleRadioProxyExceptionForRR(rr, "startNetworkScan", e);
                }
            }
        }
    }

    @Override
    public void stopNetworkScan(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            android.hardware.radio.V1_1.IRadio radioProxy11 =
                    android.hardware.radio.V1_1.IRadio.castFrom(radioProxy);
            if (radioProxy11 == null) {
                if (result != null) {
                    AsyncResult.forMessage(result, null,
                            CommandException.fromRilErrno(REQUEST_NOT_SUPPORTED));
                    result.sendToTarget();
                }
            } else {
                RILRequest rr = obtainRequest(RIL_REQUEST_STOP_NETWORK_SCAN, result,
                        mRILDefaultWorkSource);

                if (RILJ_LOGD) {
                    riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
                }

                try {
                    radioProxy11.stopNetworkScan(rr.mSerial);
                } catch (RemoteException | RuntimeException e) {
                    handleRadioProxyExceptionForRR(rr, "stopNetworkScan", e);
                }
            }
        }
    }

    @Override
    public void startDtmf(char c, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DTMF_START, result,
                    mRILDefaultWorkSource);

            // Do not log function arg for privacy
            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.startDtmf(rr.mSerial, c + "");
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "startDtmf", e);
            }
        }
    }

    @Override
    public void stopDtmf(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DTMF_STOP, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.stopDtmf(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "stopDtmf", e);
            }
        }
    }

    @Override
    public void separateConnection(int gsmIndex, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SEPARATE_CONNECTION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " gsmIndex = " + gsmIndex);
            }

            try {
                radioProxy.separateConnection(rr.mSerial, gsmIndex);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "separateConnection", e);
            }
        }
    }

    @Override
    public void getBasebandVersion(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_BASEBAND_VERSION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getBasebandVersion(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getBasebandVersion", e);
            }
        }
    }

    @Override
    public void setMute(boolean enableMute, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_MUTE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " enableMute = " + enableMute);
            }

            try {
                radioProxy.setMute(rr.mSerial, enableMute);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setMute", e);
            }
        }
    }

    @Override
    public void getMute(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_MUTE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getMute(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getMute", e);
            }
        }
    }

    @Override
    public void queryCLIP(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_CLIP, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getClip(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "queryCLIP", e);
            }
        }
    }

    /**
     * @deprecated
     */
    @Override
    @Deprecated
    public void getPDPContextList(Message result) {
        getDataCallList(result);
    }

    @Override
    public void getDataCallList(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DATA_CALL_LIST, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getDataCallList(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getDataCallList", e);
            }
        }
    }

    @Override
    public void invokeOemRilRequestRaw(byte[] data, Message response) {
        IOemHook oemHookProxy = getOemHookProxy(response);
        if (oemHookProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_OEM_HOOK_RAW, response,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + "[" + IccUtils.bytesToHexString(data) + "]");
            }

            try {
                oemHookProxy.sendRequestRaw(rr.mSerial, primitiveArrayToArrayList(data));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "invokeOemRilRequestStrings", e);
            }
        }
    }

    @Override
    public void invokeOemRilRequestStrings(String[] strings, Message result) {
        IOemHook oemHookProxy = getOemHookProxy(result);
        if (oemHookProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_OEM_HOOK_STRINGS, result,
                    mRILDefaultWorkSource);

            String logStr = "";
            for (int i = 0; i < strings.length; i++) {
                logStr = logStr + strings[i] + " ";
            }
            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " strings = "
                        + logStr);
            }

            try {
                oemHookProxy.sendRequestStrings(rr.mSerial,
                        new ArrayList<String>(Arrays.asList(strings)));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "invokeOemRilRequestStrings", e);
            }
        }
    }

    @Override
    public void setSuppServiceNotifications(boolean enable, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " enable = "
                        + enable);
            }

            try {
                radioProxy.setSuppServiceNotifications(rr.mSerial, enable);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setSuppServiceNotifications", e);
            }
        }
    }

    @Override
    public void writeSmsToSim(int status, String smsc, String pdu, Message result) {
        status = translateStatus(status);
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_WRITE_SMS_TO_SIM, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGV) {
                riljLog(rr.serialString() + "> "
                        + requestToString(rr.mRequest)
                        + " " + status);
            }

            SmsWriteArgs args = new SmsWriteArgs();
            args.status = status;
            args.smsc = convertNullToEmptyString(smsc);
            args.pdu = convertNullToEmptyString(pdu);

            try {
                radioProxy.writeSmsToSim(rr.mSerial, args);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "writeSmsToSim", e);
            }
        }
    }

    @Override
    public void deleteSmsOnSim(int index, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DELETE_SMS_ON_SIM, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGV) {
                riljLog(rr.serialString() + "> "
                        + requestToString(rr.mRequest) + " index = " + index);
            }

            try {
                radioProxy.deleteSmsOnSim(rr.mSerial, index);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "deleteSmsOnSim", e);
            }
        }
    }

    @Override
    public void setBandMode(int bandMode, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_BAND_MODE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " bandMode = " + bandMode);
            }

            try {
                radioProxy.setBandMode(rr.mSerial, bandMode);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setBandMode", e);
            }
        }
    }

    @Override
    public void queryAvailableBandMode(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getAvailableBandModes(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "queryAvailableBandMode", e);
            }
        }
    }

    @Override
    public void sendEnvelope(String contents, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " contents = "
                        + contents);
            }

            try {
                radioProxy.sendEnvelope(rr.mSerial, convertNullToEmptyString(contents));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendEnvelope", e);
            }
        }
    }

    @Override
    public void sendTerminalResponse(String contents, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " contents = "
                        + contents);
            }

            try {
                radioProxy.sendTerminalResponseToSim(rr.mSerial,
                        convertNullToEmptyString(contents));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendTerminalResponse", e);
            }
        }
    }

    @Override
    public void sendEnvelopeWithStatus(String contents, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " contents = "
                        + contents);
            }

            try {
                radioProxy.sendEnvelopeWithStatus(rr.mSerial, convertNullToEmptyString(contents));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendEnvelopeWithStatus", e);
            }
        }
    }

    @Override
    public void explicitCallTransfer(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_EXPLICIT_CALL_TRANSFER, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.explicitCallTransfer(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "explicitCallTransfer", e);
            }
        }
    }

    @Override
    public void setPreferredNetworkType(int networkType , Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " networkType = " + networkType);
            }
            mPreferredNetworkType = networkType;
            mMetrics.writeSetPreferredNetworkType(mPhoneId, networkType);

            try {
                radioProxy.setPreferredNetworkType(rr.mSerial, networkType);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setPreferredNetworkType", e);
            }
        }
    }

    @Override
    public void getPreferredNetworkType(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getPreferredNetworkType(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getPreferredNetworkType", e);
            }
        }
    }

    @Override
    public void getNeighboringCids(Message result, WorkSource workSource) {
        workSource = getDeafultWorkSourceIfInvalid(workSource);
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_NEIGHBORING_CELL_IDS, result,
                    workSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getNeighboringCids(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getNeighboringCids", e);
            }
        }
    }

    @Override
    public void setLocationUpdates(boolean enable, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_LOCATION_UPDATES, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> "
                        + requestToString(rr.mRequest) + " enable = " + enable);
            }

            try {
                radioProxy.setLocationUpdates(rr.mSerial, enable);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setLocationUpdates", e);
            }
        }
    }

    @Override
    public void setCdmaSubscriptionSource(int cdmaSubscription , Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " cdmaSubscription = " + cdmaSubscription);
            }

            try {
                radioProxy.setCdmaSubscriptionSource(rr.mSerial, cdmaSubscription);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setCdmaSubscriptionSource", e);
            }
        }
    }

    @Override
    public void queryCdmaRoamingPreference(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getCdmaRoamingPreference(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "queryCdmaRoamingPreference", e);
            }
        }
    }

    @Override
    public void setCdmaRoamingPreference(int cdmaRoamingType, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " cdmaRoamingType = " + cdmaRoamingType);
            }

            try {
                radioProxy.setCdmaRoamingPreference(rr.mSerial, cdmaRoamingType);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setCdmaRoamingPreference", e);
            }
        }
    }

    @Override
    public void queryTTYMode(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_QUERY_TTY_MODE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getTTYMode(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "queryTTYMode", e);
            }
        }
    }

    @Override
    public void setTTYMode(int ttyMode, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_TTY_MODE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " ttyMode = " + ttyMode);
            }

            try {
                radioProxy.setTTYMode(rr.mSerial, ttyMode);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setTTYMode", e);
            }
        }
    }

    @Override
    public void setPreferredVoicePrivacy(boolean enable, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " enable = " + enable);
            }

            try {
                radioProxy.setPreferredVoicePrivacy(rr.mSerial, enable);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setPreferredVoicePrivacy", e);
            }
        }
    }

    @Override
    public void getPreferredVoicePrivacy(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getPreferredVoicePrivacy(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getPreferredVoicePrivacy", e);
            }
        }
    }

    @Override
    public void sendCDMAFeatureCode(String featureCode, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_FLASH, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " featureCode = " + featureCode);
            }

            try {
                radioProxy.sendCDMAFeatureCode(rr.mSerial, convertNullToEmptyString(featureCode));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendCDMAFeatureCode", e);
            }
        }
    }

    @Override
    public void sendBurstDtmf(String dtmfString, int on, int off, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_BURST_DTMF, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " dtmfString = " + dtmfString + " on = " + on + " off = " + off);
            }

            try {
                radioProxy.sendBurstDtmf(rr.mSerial, convertNullToEmptyString(dtmfString), on, off);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendBurstDtmf", e);
            }
        }
    }

    private void constructCdmaSendSmsRilRequest(CdmaSmsMessage msg, byte[] pdu) {
        int addrNbrOfDigits;
        int subaddrNbrOfDigits;
        int bearerDataLength;
        ByteArrayInputStream bais = new ByteArrayInputStream(pdu);
        DataInputStream dis = new DataInputStream(bais);

        try {
            msg.teleserviceId = dis.readInt(); // teleServiceId
            msg.isServicePresent = (byte) dis.readInt() == 1 ? true : false; // servicePresent
            msg.serviceCategory = dis.readInt(); // serviceCategory
            msg.address.digitMode = dis.read();  // address digit mode
            msg.address.numberMode = dis.read(); // address number mode
            msg.address.numberType = dis.read(); // address number type
            msg.address.numberPlan = dis.read(); // address number plan
            addrNbrOfDigits = (byte) dis.read();
            for (int i = 0; i < addrNbrOfDigits; i++) {
                msg.address.digits.add(dis.readByte()); // address_orig_bytes[i]
            }
            msg.subAddress.subaddressType = dis.read(); //subaddressType
            msg.subAddress.odd = (byte) dis.read() == 1 ? true : false; //subaddr odd
            subaddrNbrOfDigits = (byte) dis.read();
            for (int i = 0; i < subaddrNbrOfDigits; i++) {
                msg.subAddress.digits.add(dis.readByte()); //subaddr_orig_bytes[i]
            }

            bearerDataLength = dis.read();
            for (int i = 0; i < bearerDataLength; i++) {
                msg.bearerData.add(dis.readByte()); //bearerData[i]
            }
        } catch (IOException ex) {
            if (RILJ_LOGD) {
                riljLog("sendSmsCdma: conversion from input stream to object failed: "
                        + ex);
            }
        }
    }

    @Override
    public void sendCdmaSms(byte[] pdu, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_SEND_SMS, result,
                    mRILDefaultWorkSource);

            // Do not log function arg for privacy
            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            CdmaSmsMessage msg = new CdmaSmsMessage();
            constructCdmaSendSmsRilRequest(msg, pdu);

            try {
                radioProxy.sendCdmaSms(rr.mSerial, msg);
                mMetrics.writeRilSendSms(mPhoneId, rr.mSerial, SmsSession.Event.Tech.SMS_CDMA,
                        SmsSession.Event.Format.SMS_FORMAT_3GPP2);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendCdmaSms", e);
            }
        }
    }

    @Override
    public void acknowledgeLastIncomingCdmaSms(boolean success, int cause, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " success = " + success + " cause = " + cause);
            }

            CdmaSmsAck msg = new CdmaSmsAck();
            msg.errorClass = success ? 0 : 1;
            msg.smsCauseCode = cause;

            try {
                radioProxy.acknowledgeLastIncomingCdmaSms(rr.mSerial, msg);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "acknowledgeLastIncomingCdmaSms", e);
            }
        }
    }

    @Override
    public void getGsmBroadcastConfig(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GSM_GET_BROADCAST_CONFIG, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getGsmBroadcastConfig(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getGsmBroadcastConfig", e);
            }
        }
    }

    @Override
    public void setGsmBroadcastConfig(SmsBroadcastConfigInfo[] config, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GSM_SET_BROADCAST_CONFIG, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " with " + config.length + " configs : ");
                for (int i = 0; i < config.length; i++) {
                    riljLog(config[i].toString());
                }
            }

            ArrayList<GsmBroadcastSmsConfigInfo> configs = new ArrayList<>();

            int numOfConfig = config.length;
            GsmBroadcastSmsConfigInfo info;

            for (int i = 0; i < numOfConfig; i++) {
                info = new GsmBroadcastSmsConfigInfo();
                info.fromServiceId = config[i].getFromServiceId();
                info.toServiceId = config[i].getToServiceId();
                info.fromCodeScheme = config[i].getFromCodeScheme();
                info.toCodeScheme = config[i].getToCodeScheme();
                info.selected = config[i].isSelected();
                configs.add(info);
            }

            try {
                radioProxy.setGsmBroadcastConfig(rr.mSerial, configs);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setGsmBroadcastConfig", e);
            }
        }
    }

    @Override
    public void setGsmBroadcastActivation(boolean activate, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GSM_BROADCAST_ACTIVATION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " activate = " + activate);
            }

            try {
                radioProxy.setGsmBroadcastActivation(rr.mSerial, activate);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setGsmBroadcastActivation", e);
            }
        }
    }

    @Override
    public void getCdmaBroadcastConfig(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getCdmaBroadcastConfig(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getCdmaBroadcastConfig", e);
            }
        }
    }

    @Override
    public void setCdmaBroadcastConfig(CdmaSmsBroadcastConfigInfo[] configs, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG, result,
                    mRILDefaultWorkSource);

            ArrayList<CdmaBroadcastSmsConfigInfo> halConfigs = new ArrayList<>();

            for (CdmaSmsBroadcastConfigInfo config: configs) {
                for (int i = config.getFromServiceCategory();
                        i <= config.getToServiceCategory();
                        i++) {
                    CdmaBroadcastSmsConfigInfo info = new CdmaBroadcastSmsConfigInfo();
                    info.serviceCategory = i;
                    info.language = config.getLanguage();
                    info.selected = config.isSelected();
                    halConfigs.add(info);
                }
            }

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " with " + halConfigs.size() + " configs : ");
                for (CdmaBroadcastSmsConfigInfo config : halConfigs) {
                    riljLog(config.toString());
                }
            }

            try {
                radioProxy.setCdmaBroadcastConfig(rr.mSerial, halConfigs);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setCdmaBroadcastConfig", e);
            }
        }
    }

    @Override
    public void setCdmaBroadcastActivation(boolean activate, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_BROADCAST_ACTIVATION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " activate = " + activate);
            }

            try {
                radioProxy.setCdmaBroadcastActivation(rr.mSerial, activate);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setCdmaBroadcastActivation", e);
            }
        }
    }

    @Override
    public void getCDMASubscription(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_SUBSCRIPTION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getCDMASubscription(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getCDMASubscription", e);
            }
        }
    }

    @Override
    public void writeSmsToRuim(int status, String pdu, Message result) {
        status = translateStatus(status);
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGV) {
                riljLog(rr.serialString() + "> "
                        + requestToString(rr.mRequest)
                        + " status = " + status);
            }

            CdmaSmsWriteArgs args = new CdmaSmsWriteArgs();
            args.status = status;
            constructCdmaSendSmsRilRequest(args.message, pdu.getBytes());

            try {
                radioProxy.writeSmsToRuim(rr.mSerial, args);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "writeSmsToRuim", e);
            }
        }
    }

    @Override
    public void deleteSmsOnRuim(int index, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGV) {
                riljLog(rr.serialString() + "> "
                        + requestToString(rr.mRequest)
                        + " index = " + index);
            }

            try {
                radioProxy.deleteSmsOnRuim(rr.mSerial, index);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "deleteSmsOnRuim", e);
            }
        }
    }

    @Override
    public void getDeviceIdentity(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_DEVICE_IDENTITY, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getDeviceIdentity(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getDeviceIdentity", e);
            }
        }
    }

    @Override
    public void exitEmergencyCallbackMode(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.exitEmergencyCallbackMode(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "exitEmergencyCallbackMode", e);
            }
        }
    }

    @Override
    public void getSmscAddress(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_SMSC_ADDRESS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getSmscAddress(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getSmscAddress", e);
            }
        }
    }

    @Override
    public void setSmscAddress(String address, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_SMSC_ADDRESS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " address = " + address);
            }

            try {
                radioProxy.setSmscAddress(rr.mSerial, convertNullToEmptyString(address));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setSmscAddress", e);
            }
        }
    }

    @Override
    public void reportSmsMemoryStatus(boolean available, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_REPORT_SMS_MEMORY_STATUS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> "
                        + requestToString(rr.mRequest) + " available = " + available);
            }

            try {
                radioProxy.reportSmsMemoryStatus(rr.mSerial, available);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "reportSmsMemoryStatus", e);
            }
        }
    }

    @Override
    public void reportStkServiceIsRunning(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.reportStkServiceIsRunning(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "reportStkServiceIsRunning", e);
            }
        }
    }

    @Override
    public void getCdmaSubscriptionSource(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getCdmaSubscriptionSource(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getCdmaSubscriptionSource", e);
            }
        }
    }

    @Override
    public void requestIsimAuthentication(String nonce, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ISIM_AUTHENTICATION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " nonce = " + nonce);
            }

            try {
                radioProxy.requestIsimAuthentication(rr.mSerial, convertNullToEmptyString(nonce));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "requestIsimAuthentication", e);
            }
        }
    }

    @Override
    public void acknowledgeIncomingGsmSmsWithPdu(boolean success, String ackPdu, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " success = " + success);
            }

            try {
                radioProxy.acknowledgeIncomingGsmSmsWithPdu(rr.mSerial, success,
                        convertNullToEmptyString(ackPdu));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "acknowledgeIncomingGsmSmsWithPdu", e);
            }
        }
    }

    @Override
    public void getVoiceRadioTechnology(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_VOICE_RADIO_TECH, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getVoiceRadioTechnology(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getVoiceRadioTechnology", e);
            }
        }
    }

    @Override
    public void getCellInfoList(Message result, WorkSource workSource) {
        workSource = getDeafultWorkSourceIfInvalid(workSource);
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_CELL_INFO_LIST, result,
                    workSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.getCellInfoList(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getCellInfoList", e);
            }
        }
    }

    @Override
    public void setCellInfoListRate(int rateInMillis, Message result, WorkSource workSource) {
        workSource = getDeafultWorkSourceIfInvalid(workSource);
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_UNSOL_CELL_INFO_LIST_RATE, result,
                    workSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " rateInMillis = " + rateInMillis);
            }

            try {
                radioProxy.setCellInfoListRate(rr.mSerial, rateInMillis);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setCellInfoListRate", e);
            }
        }
    }

    void setCellInfoListRate() {
        setCellInfoListRate(Integer.MAX_VALUE, null, mRILDefaultWorkSource);
    }

    @Override
    public void setInitialAttachApn(DataProfile dataProfile, boolean isRoaming, Message result) {

        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_INITIAL_ATTACH_APN, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + dataProfile);
            }

            try {
                radioProxy.setInitialAttachApn(rr.mSerial, convertToHalDataProfile(dataProfile),
                        dataProfile.modemCognitive, isRoaming);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setInitialAttachApn", e);
            }
        }
    }

    @Override
    public void getImsRegistrationState(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_IMS_REGISTRATION_STATE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.getImsRegistrationState(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getImsRegistrationState", e);
            }
        }
    }

    @Override
    public void sendImsGsmSms(String smscPdu, String pdu, int retry, int messageRef,
                   Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_IMS_SEND_SMS, result,
                    mRILDefaultWorkSource);

            // Do not log function args for privacy
            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            ImsSmsMessage msg = new ImsSmsMessage();
            msg.tech = RILConstants.GSM_PHONE;
            msg.retry = (byte) retry == 1 ? true : false;
            msg.messageRef = messageRef;

            GsmSmsMessage gsmMsg = constructGsmSendSmsRilRequest(smscPdu, pdu);
            msg.gsmMessage.add(gsmMsg);
            try {
                radioProxy.sendImsSms(rr.mSerial, msg);
                mMetrics.writeRilSendSms(mPhoneId, rr.mSerial, SmsSession.Event.Tech.SMS_IMS,
                        SmsSession.Event.Format.SMS_FORMAT_3GPP);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendImsGsmSms", e);
            }
        }
    }

    @Override
    public void sendImsCdmaSms(byte[] pdu, int retry, int messageRef, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_IMS_SEND_SMS, result,
                    mRILDefaultWorkSource);

            // Do not log function args for privacy
            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            ImsSmsMessage msg = new ImsSmsMessage();
            msg.tech = RILConstants.CDMA_PHONE;
            msg.retry = (byte) retry == 1 ? true : false;
            msg.messageRef = messageRef;

            CdmaSmsMessage cdmaMsg = new CdmaSmsMessage();
            constructCdmaSendSmsRilRequest(cdmaMsg, pdu);
            msg.cdmaMessage.add(cdmaMsg);

            try {
                radioProxy.sendImsSms(rr.mSerial, msg);
                mMetrics.writeRilSendSms(mPhoneId, rr.mSerial, SmsSession.Event.Tech.SMS_IMS,
                        SmsSession.Event.Format.SMS_FORMAT_3GPP);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendImsCdmaSms", e);
            }
        }
    }

    private SimApdu createSimApdu(int channel, int cla, int instruction, int p1, int p2, int p3,
                                  String data) {
        SimApdu msg = new SimApdu();
        msg.sessionId = channel;
        msg.cla = cla;
        msg.instruction = instruction;
        msg.p1 = p1;
        msg.p2 = p2;
        msg.p3 = p3;
        msg.data = convertNullToEmptyString(data);
        return msg;
    }

    @Override
    public void iccTransmitApduBasicChannel(int cla, int instruction, int p1, int p2,
                                            int p3, String data, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SIM_TRANSMIT_APDU_BASIC, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " cla = " + cla + " instruction = " + instruction
                        + " p1 = " + p1 + " p2 = " + " p3 = " + p3 + " data = " + data);
            }

            SimApdu msg = createSimApdu(0, cla, instruction, p1, p2, p3, data);
            try {
                radioProxy.iccTransmitApduBasicChannel(rr.mSerial, msg);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "iccTransmitApduBasicChannel", e);
            }
        }
    }

    @Override
    public void iccOpenLogicalChannel(String aid, int p2, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SIM_OPEN_CHANNEL, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " aid = " + aid
                        + " p2 = " + p2);
            }

            try {
                radioProxy.iccOpenLogicalChannel(rr.mSerial, convertNullToEmptyString(aid), p2);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "iccOpenLogicalChannel", e);
            }
        }
    }

    @Override
    public void iccCloseLogicalChannel(int channel, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SIM_CLOSE_CHANNEL, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " channel = "
                        + channel);
            }

            try {
                radioProxy.iccCloseLogicalChannel(rr.mSerial, channel);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "iccCloseLogicalChannel", e);
            }
        }
    }

    @Override
    public void iccTransmitApduLogicalChannel(int channel, int cla, int instruction,
                                              int p1, int p2, int p3, String data,
                                              Message result) {
        if (channel <= 0) {
            throw new RuntimeException(
                    "Invalid channel in iccTransmitApduLogicalChannel: " + channel);
        }

        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " channel = "
                        + channel + " cla = " + cla + " instruction = " + instruction
                        + " p1 = " + p1 + " p2 = " + " p3 = " + p3 + " data = " + data);
            }

            SimApdu msg = createSimApdu(channel, cla, instruction, p1, p2, p3, data);

            try {
                radioProxy.iccTransmitApduLogicalChannel(rr.mSerial, msg);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "iccTransmitApduLogicalChannel", e);
            }
        }
    }

    @Override
    public void nvReadItem(int itemID, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_NV_READ_ITEM, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " itemId = " + itemID);
            }

            try {
                radioProxy.nvReadItem(rr.mSerial, itemID);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "nvReadItem", e);
            }
        }
    }

    @Override
    public void nvWriteItem(int itemId, String itemValue, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_NV_WRITE_ITEM, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " itemId = " + itemId + " itemValue = " + itemValue);
            }

            NvWriteItem item = new NvWriteItem();
            item.itemId = itemId;
            item.value = convertNullToEmptyString(itemValue);

            try {
                radioProxy.nvWriteItem(rr.mSerial, item);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "nvWriteItem", e);
            }
        }
    }

    @Override
    public void nvWriteCdmaPrl(byte[] preferredRoamingList, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_NV_WRITE_CDMA_PRL, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " PreferredRoamingList = 0x"
                        + IccUtils.bytesToHexString(preferredRoamingList));
            }

            ArrayList<Byte> arrList = new ArrayList<>();
            for (int i = 0; i < preferredRoamingList.length; i++) {
                arrList.add(preferredRoamingList[i]);
            }

            try {
                radioProxy.nvWriteCdmaPrl(rr.mSerial, arrList);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "nvWriteCdmaPrl", e);
            }
        }
    }

    @Override
    public void nvResetConfig(int resetType, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_NV_RESET_CONFIG, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " resetType = " + resetType);
            }

            try {
                radioProxy.nvResetConfig(rr.mSerial, convertToHalResetNvType(resetType));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "nvResetConfig", e);
            }
        }
    }

    @Override
    public void setUiccSubscription(int slotId, int appIndex, int subId,
                                    int subStatus, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_UICC_SUBSCRIPTION, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " slot = " + slotId + " appIndex = " + appIndex
                        + " subId = " + subId + " subStatus = " + subStatus);
            }

            SelectUiccSub info = new SelectUiccSub();
            info.slot = slotId;
            info.appIndex = appIndex;
            info.subType = subId;
            info.actStatus = subStatus;

            try {
                radioProxy.setUiccSubscription(rr.mSerial, info);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setUiccSubscription", e);
            }
        }
    }

    @Override
    public void setDataAllowed(boolean allowed, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_ALLOW_DATA, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " allowed = " + allowed);
            }

            try {
                radioProxy.setDataAllowed(rr.mSerial, allowed);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setDataAllowed", e);
            }
        }
    }

    @Override
    public void
    getHardwareConfig (Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_HARDWARE_CONFIG, result,
                    mRILDefaultWorkSource);

            // Do not log function args for privacy
            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.getHardwareConfig(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getHardwareConfig", e);
            }
        }
    }

    @Override
    public void requestIccSimAuthentication(int authContext, String data, String aid,
                                            Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SIM_AUTHENTICATION, result,
                    mRILDefaultWorkSource);

            // Do not log function args for privacy
            if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

            try {
                radioProxy.requestIccSimAuthentication(rr.mSerial,
                        authContext,
                        convertNullToEmptyString(data),
                        convertNullToEmptyString(aid));
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "requestIccSimAuthentication", e);
            }
        }
    }

    @Override
    public void setDataProfile(DataProfile[] dps, boolean isRoaming, Message result) {

        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_DATA_PROFILE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " with data profiles : ");
                for (DataProfile profile : dps) {
                    riljLog(profile.toString());
                }
            }

            ArrayList<DataProfileInfo> dpis = new ArrayList<>();
            for (DataProfile dp : dps) {
                dpis.add(convertToHalDataProfile(dp));
            }

            try {
                radioProxy.setDataProfile(rr.mSerial, dpis, isRoaming);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setDataProfile", e);
            }
        }
    }

    @Override
    public void requestShutdown(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SHUTDOWN, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.requestShutdown(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "requestShutdown", e);
            }
        }
    }

    @Override
    public void getRadioCapability(Message response) {
        IRadio radioProxy = getRadioProxy(response);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_RADIO_CAPABILITY, response,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.getRadioCapability(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getRadioCapability", e);
            }
        }
    }

    @Override
    public void setRadioCapability(RadioCapability rc, Message response) {
        IRadio radioProxy = getRadioProxy(response);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_RADIO_CAPABILITY, response,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " RadioCapability = " + rc.toString());
            }

            android.hardware.radio.V1_0.RadioCapability halRc =
                    new android.hardware.radio.V1_0.RadioCapability();

            halRc.session = rc.getSession();
            halRc.phase = rc.getPhase();
            halRc.raf = rc.getRadioAccessFamily();
            halRc.logicalModemUuid = convertNullToEmptyString(rc.getLogicalModemUuid());
            halRc.status = rc.getStatus();

            try {
                radioProxy.setRadioCapability(rr.mSerial, halRc);
            } catch (Exception e) {
                handleRadioProxyExceptionForRR(rr, "setRadioCapability", e);
            }
        }
    }

    @Override
    public void startLceService(int reportIntervalMs, boolean pullMode, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_START_LCE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " reportIntervalMs = " + reportIntervalMs + " pullMode = " + pullMode);
            }

            try {
                radioProxy.startLceService(rr.mSerial, reportIntervalMs, pullMode);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "startLceService", e);
            }
        }
    }

    @Override
    public void stopLceService(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_STOP_LCE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.stopLceService(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "stopLceService", e);
            }
        }
    }

    @Override
    public void pullLceData(Message response) {
        IRadio radioProxy = getRadioProxy(response);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_PULL_LCEDATA, response,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.pullLceData(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "pullLceData", e);
            }
        }
    }

    @Override
    public void getModemActivityInfo(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_ACTIVITY_INFO, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.getModemActivityInfo(rr.mSerial);

                Message msg = mRilHandler.obtainMessage(EVENT_BLOCKING_RESPONSE_TIMEOUT);
                msg.obj = null;
                msg.arg1 = rr.mSerial;
                mRilHandler.sendMessageDelayed(msg, DEFAULT_BLOCKING_MESSAGE_RESPONSE_TIMEOUT_MS);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getModemActivityInfo", e);
            }
        }


    }

    @Override
    public void setAllowedCarriers(List<CarrierIdentifier> carriers, Message result) {
        checkNotNull(carriers, "Allowed carriers list cannot be null.");
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_ALLOWED_CARRIERS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                String logStr = "";
                for (int i = 0; i < carriers.size(); i++) {
                    logStr = logStr + carriers.get(i) + " ";
                }
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " carriers = "
                        + logStr);
            }

            boolean allAllowed;
            if (carriers.size() == 0) {
                allAllowed = true;
            } else {
                allAllowed = false;
            }
            CarrierRestrictions carrierList = new CarrierRestrictions();

            for (CarrierIdentifier ci : carriers) { /* allowed carriers */
                Carrier c = new Carrier();
                c.mcc = convertNullToEmptyString(ci.getMcc());
                c.mnc = convertNullToEmptyString(ci.getMnc());
                int matchType = CarrierIdentifier.MatchType.ALL;
                String matchData = null;
                if (!TextUtils.isEmpty(ci.getSpn())) {
                    matchType = CarrierIdentifier.MatchType.SPN;
                    matchData = ci.getSpn();
                } else if (!TextUtils.isEmpty(ci.getImsi())) {
                    matchType = CarrierIdentifier.MatchType.IMSI_PREFIX;
                    matchData = ci.getImsi();
                } else if (!TextUtils.isEmpty(ci.getGid1())) {
                    matchType = CarrierIdentifier.MatchType.GID1;
                    matchData = ci.getGid1();
                } else if (!TextUtils.isEmpty(ci.getGid2())) {
                    matchType = CarrierIdentifier.MatchType.GID2;
                    matchData = ci.getGid2();
                }
                c.matchType = matchType;
                c.matchData = convertNullToEmptyString(matchData);
                carrierList.allowedCarriers.add(c);
            }

            /* TODO: add excluded carriers */

            try {
                radioProxy.setAllowedCarriers(rr.mSerial, allAllowed, carrierList);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setAllowedCarriers", e);
            }
        }
    }

    @Override
    public void getAllowedCarriers(Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_ALLOWED_CARRIERS, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.getAllowedCarriers(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getAllowedCarriers", e);
            }
        }
    }

    @Override
    public void sendDeviceState(int stateType, boolean state,
                                Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SEND_DEVICE_STATE, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " "
                        + stateType + ":" + state);
            }

            try {
                radioProxy.sendDeviceState(rr.mSerial, stateType, state);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendDeviceState", e);
            }
        }
    }

    @Override
    public void setUnsolResponseFilter(int filter, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_UNSOLICITED_RESPONSE_FILTER, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " + filter);
            }

            try {
                radioProxy.setIndicationFilter(rr.mSerial, filter);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "setIndicationFilter", e);
            }
        }
    }

    @Override
    public void setSimCardPower(int state, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_SIM_CARD_POWER, result,
                    mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest) + " " + state);
            }
            android.hardware.radio.V1_1.IRadio radioProxy11 =
                    android.hardware.radio.V1_1.IRadio.castFrom(radioProxy);
            if (radioProxy11 == null) {
                try {
                    switch (state) {
                        case TelephonyManager.CARD_POWER_DOWN: {
                            radioProxy.setSimCardPower(rr.mSerial, false);
                            break;
                        }
                        case TelephonyManager.CARD_POWER_UP: {
                            radioProxy.setSimCardPower(rr.mSerial, true);
                            break;
                        }
                        default: {
                            if (result != null) {
                                AsyncResult.forMessage(result, null,
                                        CommandException.fromRilErrno(REQUEST_NOT_SUPPORTED));
                                result.sendToTarget();
                            }
                        }
                    }
                } catch (RemoteException | RuntimeException e) {
                    handleRadioProxyExceptionForRR(rr, "setSimCardPower", e);
                }
            } else {
                try {
                    radioProxy11.setSimCardPower_1_1(rr.mSerial, state);
                } catch (RemoteException | RuntimeException e) {
                    handleRadioProxyExceptionForRR(rr, "setSimCardPower", e);
                }
            }
        }
    }

    @Override
    public void setCarrierInfoForImsiEncryption(ImsiEncryptionInfo imsiEncryptionInfo,
                                                Message result) {
        checkNotNull(imsiEncryptionInfo, "ImsiEncryptionInfo cannot be null.");
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            android.hardware.radio.V1_1.IRadio radioProxy11 =
                    android.hardware.radio.V1_1.IRadio.castFrom(radioProxy);
            if (radioProxy11 == null) {
                if (result != null) {
                    AsyncResult.forMessage(result, null,
                            CommandException.fromRilErrno(REQUEST_NOT_SUPPORTED));
                    result.sendToTarget();
                }
            } else {
                RILRequest rr = obtainRequest(RIL_REQUEST_SET_CARRIER_INFO_IMSI_ENCRYPTION, result,
                        mRILDefaultWorkSource);
                if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

                try {
                    android.hardware.radio.V1_1.ImsiEncryptionInfo halImsiInfo =
                            new android.hardware.radio.V1_1.ImsiEncryptionInfo();
                    halImsiInfo.mnc = imsiEncryptionInfo.getMnc();
                    halImsiInfo.mcc = imsiEncryptionInfo.getMcc();
                    halImsiInfo.keyIdentifier = imsiEncryptionInfo.getKeyIdentifier();
                    if (imsiEncryptionInfo.getExpirationTime() != null) {
                        halImsiInfo.expirationTime =
                                imsiEncryptionInfo.getExpirationTime().getTime();
                    }
                    for (byte b : imsiEncryptionInfo.getPublicKey().getEncoded()) {
                        halImsiInfo.carrierKey.add(new Byte(b));
                    }

                    radioProxy11.setCarrierInfoForImsiEncryption(
                            rr.mSerial, halImsiInfo);
                } catch (RemoteException | RuntimeException e) {
                    handleRadioProxyExceptionForRR(rr, "setCarrierInfoForImsiEncryption", e);
                }
            }
        }
    }

    @Override
    public void getIMEI(Message result) {
        throw new RuntimeException("getIMEI not expected to be called");
    }

    @Override
    public void getIMEISV(Message result) {
        throw new RuntimeException("getIMEISV not expected to be called");
    }

    /**
     * @deprecated
     */
    @Deprecated
    @Override
    public void getLastPdpFailCause(Message result) {
        throw new RuntimeException("getLastPdpFailCause not expected to be called");
    }

    /**
     * The preferred new alternative to getLastPdpFailCause
     */
    @Override
    public void getLastDataCallFailCause(Message result) {
        throw new RuntimeException("getLastDataCallFailCause not expected to be called");
    }

    /**
     *  Translates EF_SMS status bits to a status value compatible with
     *  SMS AT commands.  See TS 27.005 3.1.
     */
    private int translateStatus(int status) {
        switch(status & 0x7) {
            case SmsManager.STATUS_ON_ICC_READ:
                return 1;
            case SmsManager.STATUS_ON_ICC_UNREAD:
                return 0;
            case SmsManager.STATUS_ON_ICC_SENT:
                return 3;
            case SmsManager.STATUS_ON_ICC_UNSENT:
                return 2;
        }

        // Default to READ.
        return 1;
    }

    @Override
    public void resetRadio(Message result) {
        throw new RuntimeException("resetRadio not expected to be called");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handleCallSetupRequestFromSim(boolean accept, Message result) {
        IRadio radioProxy = getRadioProxy(result);
        if (radioProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM,
                    result, mRILDefaultWorkSource);

            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioProxy.handleStkCallSetupRequestFromSim(rr.mSerial, accept);
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "getAllowedCarriers", e);
            }
        }
    }

    //***** Private Methods

    /**
     * This is a helper function to be called when a RadioIndication callback is called.
     * It takes care of acquiring wakelock and sending ack if needed.
     * @param indicationType RadioIndicationType received
     */
    void processIndication(int indicationType) {
        if (indicationType == RadioIndicationType.UNSOLICITED_ACK_EXP) {
            sendAck();
            if (RILJ_LOGD) riljLog("Unsol response received; Sending ack to ril.cpp");
        } else {
            // ack is not expected to be sent back. Nothing is required to be done here.
        }
    }

    void processRequestAck(int serial) {
        RILRequest rr;
        synchronized (mRequestList) {
            rr = mRequestList.get(serial);
        }
        if (rr == null) {
            Rlog.w(RIL.RILJ_LOG_TAG, "processRequestAck: Unexpected solicited ack response! "
                    + "serial: " + serial);
        } else {
            decrementWakeLock(rr);
            if (RIL.RILJ_LOGD) {
                riljLog(rr.serialString() + " Ack < " + RIL.requestToString(rr.mRequest));
            }
        }
    }

    /**
     * This is a helper function to be called when a RadioResponse callback is called.
     * It takes care of acks, wakelocks, and finds and returns RILRequest corresponding to the
     * response if one is found.
     * @param responseInfo RadioResponseInfo received in response callback
     * @return RILRequest corresponding to the response
     */
    RILRequest processResponse(RadioResponseInfo responseInfo) {
        int serial = responseInfo.serial;
        int error = responseInfo.error;
        int type = responseInfo.type;

        RILRequest rr = null;

        if (type == RadioResponseType.SOLICITED_ACK) {
            synchronized (mRequestList) {
                rr = mRequestList.get(serial);
            }
            if (rr == null) {
                Rlog.w(RILJ_LOG_TAG, "Unexpected solicited ack response! sn: " + serial);
            } else {
                decrementWakeLock(rr);
                if (RILJ_LOGD) {
                    riljLog(rr.serialString() + " Ack < " + requestToString(rr.mRequest));
                }
            }
            return rr;
        }

        rr = findAndRemoveRequestFromList(serial);
        if (rr == null) {
            Rlog.e(RIL.RILJ_LOG_TAG, "processResponse: Unexpected response! serial: " + serial
                    + " error: " + error);
            return null;
        }

        // Time logging for RIL command and storing it in TelephonyHistogram.
        addToRilHistogram(rr);

        if (type == RadioResponseType.SOLICITED_ACK_EXP) {
            sendAck();
            if (RIL.RILJ_LOGD) {
                riljLog("Response received for " + rr.serialString() + " "
                        + RIL.requestToString(rr.mRequest) + " Sending ack to ril.cpp");
            }
        } else {
            // ack sent for SOLICITED_ACK_EXP above; nothing to do for SOLICITED response
        }

        // Here and below fake RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED, see b/7255789.
        // This is needed otherwise we don't automatically transition to the main lock
        // screen when the pin or puk is entered incorrectly.
        switch (rr.mRequest) {
            case RIL_REQUEST_ENTER_SIM_PUK:
            case RIL_REQUEST_ENTER_SIM_PUK2:
                if (mIccStatusChangedRegistrants != null) {
                    if (RILJ_LOGD) {
                        riljLog("ON enter sim puk fakeSimStatusChanged: reg count="
                                + mIccStatusChangedRegistrants.size());
                    }
                    mIccStatusChangedRegistrants.notifyRegistrants();
                }
                break;
            case RIL_REQUEST_SHUTDOWN:
                setRadioState(RadioState.RADIO_UNAVAILABLE);
                break;
        }

        if (error != RadioError.NONE) {
            switch (rr.mRequest) {
                case RIL_REQUEST_ENTER_SIM_PIN:
                case RIL_REQUEST_ENTER_SIM_PIN2:
                case RIL_REQUEST_CHANGE_SIM_PIN:
                case RIL_REQUEST_CHANGE_SIM_PIN2:
                case RIL_REQUEST_SET_FACILITY_LOCK:
                    if (mIccStatusChangedRegistrants != null) {
                        if (RILJ_LOGD) {
                            riljLog("ON some errors fakeSimStatusChanged: reg count="
                                    + mIccStatusChangedRegistrants.size());
                        }
                        mIccStatusChangedRegistrants.notifyRegistrants();
                    }
                    break;

            }
        } else {
            switch (rr.mRequest) {
                case RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND:
                if (mTestingEmergencyCall.getAndSet(false)) {
                    if (mEmergencyCallbackModeRegistrant != null) {
                        riljLog("testing emergency call, notify ECM Registrants");
                        mEmergencyCallbackModeRegistrant.notifyRegistrant();
                    }
                }
            }
        }
        return rr;
    }

    /**
     * This is a helper function to be called at the end of all RadioResponse callbacks.
     * It takes care of sending error response, logging, decrementing wakelock if needed, and
     * releases the request from memory pool.
     * @param rr RILRequest for which response callback was called
     * @param responseInfo RadioResponseInfo received in the callback
     * @param ret object to be returned to request sender
     */
    void processResponseDone(RILRequest rr, RadioResponseInfo responseInfo, Object ret) {
        if (responseInfo.error == 0) {
            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "< " + requestToString(rr.mRequest)
                        + " " + retToString(rr.mRequest, ret));
            }
        } else {
            if (RILJ_LOGD) {
                riljLog(rr.serialString() + "< " + requestToString(rr.mRequest)
                        + " error " + responseInfo.error);
            }
            rr.onError(responseInfo.error, ret);
        }
        mMetrics.writeOnRilSolicitedResponse(mPhoneId, rr.mSerial, responseInfo.error,
                rr.mRequest, ret);
        if (rr != null) {
            if (responseInfo.type == RadioResponseType.SOLICITED) {
                decrementWakeLock(rr);
            }
            rr.release();
        }
    }

    /**
     * Function to send ack and acquire related wakelock
     */
    private void sendAck() {
        // TODO: Remove rr and clean up acquireWakelock for response and ack
        RILRequest rr = RILRequest.obtain(RIL_RESPONSE_ACKNOWLEDGEMENT, null,
                mRILDefaultWorkSource);
        acquireWakeLock(rr, RIL.FOR_ACK_WAKELOCK);
        IRadio radioProxy = getRadioProxy(null);
        if (radioProxy != null) {
            try {
                radioProxy.responseAcknowledgement();
            } catch (RemoteException | RuntimeException e) {
                handleRadioProxyExceptionForRR(rr, "sendAck", e);
                riljLoge("sendAck: " + e);
            }
        } else {
            Rlog.e(RILJ_LOG_TAG, "Error trying to send ack, radioProxy = null");
        }
        rr.release();
    }

    private WorkSource getDeafultWorkSourceIfInvalid(WorkSource workSource) {
        if (workSource == null) {
            workSource = mRILDefaultWorkSource;
        }

        return workSource;
    }

    private String getWorkSourceClientId(WorkSource workSource) {
        if (workSource != null) {
            return String.valueOf(workSource.get(0)) + ":" + workSource.getName(0);
        }

        return null;
    }

    /**
     * Holds a PARTIAL_WAKE_LOCK whenever
     * a) There is outstanding RIL request sent to RIL deamon and no replied
     * b) There is a request pending to be sent out.
     *
     * There is a WAKE_LOCK_TIMEOUT to release the lock, though it shouldn't
     * happen often.
     */

    private void acquireWakeLock(RILRequest rr, int wakeLockType) {
        synchronized (rr) {
            if (rr.mWakeLockType != INVALID_WAKELOCK) {
                Rlog.d(RILJ_LOG_TAG, "Failed to aquire wakelock for " + rr.serialString());
                return;
            }

            switch(wakeLockType) {
                case FOR_WAKELOCK:
                    synchronized (mWakeLock) {
                        mWakeLock.acquire();
                        mWakeLockCount++;
                        mWlSequenceNum++;

                        String clientId = getWorkSourceClientId(rr.mWorkSource);
                        if (!mClientWakelockTracker.isClientActive(clientId)) {
                            if (mActiveWakelockWorkSource != null) {
                                mActiveWakelockWorkSource.add(rr.mWorkSource);
                            } else {
                                mActiveWakelockWorkSource = rr.mWorkSource;
                            }
                            mWakeLock.setWorkSource(mActiveWakelockWorkSource);
                        }

                        mClientWakelockTracker.startTracking(rr.mClientId,
                                rr.mRequest, rr.mSerial, mWakeLockCount);

                        Message msg = mRilHandler.obtainMessage(EVENT_WAKE_LOCK_TIMEOUT);
                        msg.arg1 = mWlSequenceNum;
                        mRilHandler.sendMessageDelayed(msg, mWakeLockTimeout);
                    }
                    break;
                case FOR_ACK_WAKELOCK:
                    synchronized (mAckWakeLock) {
                        mAckWakeLock.acquire();
                        mAckWlSequenceNum++;

                        Message msg = mRilHandler.obtainMessage(EVENT_ACK_WAKE_LOCK_TIMEOUT);
                        msg.arg1 = mAckWlSequenceNum;
                        mRilHandler.sendMessageDelayed(msg, mAckWakeLockTimeout);
                    }
                    break;
                default: //WTF
                    Rlog.w(RILJ_LOG_TAG, "Acquiring Invalid Wakelock type " + wakeLockType);
                    return;
            }
            rr.mWakeLockType = wakeLockType;
        }
    }

    private void decrementWakeLock(RILRequest rr) {
        synchronized (rr) {
            switch(rr.mWakeLockType) {
                case FOR_WAKELOCK:
                    synchronized (mWakeLock) {
                        mClientWakelockTracker.stopTracking(rr.mClientId,
                                rr.mRequest, rr.mSerial,
                                (mWakeLockCount > 1) ? mWakeLockCount - 1 : 0);
                        String clientId = getWorkSourceClientId(rr.mWorkSource);;
                        if (!mClientWakelockTracker.isClientActive(clientId)
                                && (mActiveWakelockWorkSource != null)) {
                            mActiveWakelockWorkSource.remove(rr.mWorkSource);
                            if (mActiveWakelockWorkSource.size() == 0) {
                                mActiveWakelockWorkSource = null;
                            }
                            mWakeLock.setWorkSource(mActiveWakelockWorkSource);
                        }

                        if (mWakeLockCount > 1) {
                            mWakeLockCount--;
                        } else {
                            mWakeLockCount = 0;
                            mWakeLock.release();
                        }
                    }
                    break;
                case FOR_ACK_WAKELOCK:
                    //We do not decrement the ACK wakelock
                    break;
                case INVALID_WAKELOCK:
                    break;
                default:
                    Rlog.w(RILJ_LOG_TAG, "Decrementing Invalid Wakelock type " + rr.mWakeLockType);
            }
            rr.mWakeLockType = INVALID_WAKELOCK;
        }
    }

    private boolean clearWakeLock(int wakeLockType) {
        if (wakeLockType == FOR_WAKELOCK) {
            synchronized (mWakeLock) {
                if (mWakeLockCount == 0 && !mWakeLock.isHeld()) return false;
                Rlog.d(RILJ_LOG_TAG, "NOTE: mWakeLockCount is " + mWakeLockCount
                        + "at time of clearing");
                mWakeLockCount = 0;
                mWakeLock.release();
                mClientWakelockTracker.stopTrackingAll();
                mActiveWakelockWorkSource = null;
                return true;
            }
        } else {
            synchronized (mAckWakeLock) {
                if (!mAckWakeLock.isHeld()) return false;
                mAckWakeLock.release();
                return true;
            }
        }
    }

    /**
     * Release each request in mRequestList then clear the list
     * @param error is the RIL_Errno sent back
     * @param loggable true means to print all requests in mRequestList
     */
    private void clearRequestList(int error, boolean loggable) {
        RILRequest rr;
        synchronized (mRequestList) {
            int count = mRequestList.size();
            if (RILJ_LOGD && loggable) {
                Rlog.d(RILJ_LOG_TAG, "clearRequestList " + " mWakeLockCount="
                        + mWakeLockCount + " mRequestList=" + count);
            }

            for (int i = 0; i < count; i++) {
                rr = mRequestList.valueAt(i);
                if (RILJ_LOGD && loggable) {
                    Rlog.d(RILJ_LOG_TAG, i + ": [" + rr.mSerial + "] "
                            + requestToString(rr.mRequest));
                }
                rr.onError(error, null);
                decrementWakeLock(rr);
                rr.release();
            }
            mRequestList.clear();
        }
    }

    private RILRequest findAndRemoveRequestFromList(int serial) {
        RILRequest rr = null;
        synchronized (mRequestList) {
            rr = mRequestList.get(serial);
            if (rr != null) {
                mRequestList.remove(serial);
            }
        }

        return rr;
    }

    private void addToRilHistogram(RILRequest rr) {
        long endTime = SystemClock.elapsedRealtime();
        int totalTime = (int) (endTime - rr.mStartTimeMs);

        synchronized (mRilTimeHistograms) {
            TelephonyHistogram entry = mRilTimeHistograms.get(rr.mRequest);
            if (entry == null) {
                // We would have total #RIL_HISTOGRAM_BUCKET_COUNT range buckets for RIL commands
                entry = new TelephonyHistogram(TelephonyHistogram.TELEPHONY_CATEGORY_RIL,
                        rr.mRequest, RIL_HISTOGRAM_BUCKET_COUNT);
                mRilTimeHistograms.put(rr.mRequest, entry);
            }
            entry.addTimeTaken(totalTime);
        }
    }

    RadioCapability makeStaticRadioCapability() {
        // default to UNKNOWN so we fail fast.
        int raf = RadioAccessFamily.RAF_UNKNOWN;

        String rafString = mContext.getResources().getString(
                com.android.internal.R.string.config_radio_access_family);
        if (!TextUtils.isEmpty(rafString)) {
            raf = RadioAccessFamily.rafTypeFromString(rafString);
        }
        RadioCapability rc = new RadioCapability(mPhoneId.intValue(), 0, 0, raf,
                "", RadioCapability.RC_STATUS_SUCCESS);
        if (RILJ_LOGD) riljLog("Faking RIL_REQUEST_GET_RADIO_CAPABILITY response using " + raf);
        return rc;
    }

    static String retToString(int req, Object ret) {
        if (ret == null) return "";
        switch (req) {
            // Don't log these return values, for privacy's sake.
            case RIL_REQUEST_GET_IMSI:
            case RIL_REQUEST_GET_IMEI:
            case RIL_REQUEST_GET_IMEISV:
            case RIL_REQUEST_SIM_OPEN_CHANNEL:
            case RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL:

                if (!RILJ_LOGV) {
                    // If not versbose logging just return and don't display IMSI and IMEI, IMEISV
                    return "";
                }
        }

        StringBuilder sb;
        String s;
        int length;
        if (ret instanceof int[]) {
            int[] intArray = (int[]) ret;
            length = intArray.length;
            sb = new StringBuilder("{");
            if (length > 0) {
                int i = 0;
                sb.append(intArray[i++]);
                while (i < length) {
                    sb.append(", ").append(intArray[i++]);
                }
            }
            sb.append("}");
            s = sb.toString();
        } else if (ret instanceof String[]) {
            String[] strings = (String[]) ret;
            length = strings.length;
            sb = new StringBuilder("{");
            if (length > 0) {
                int i = 0;
                sb.append(strings[i++]);
                while (i < length) {
                    sb.append(", ").append(strings[i++]);
                }
            }
            sb.append("}");
            s = sb.toString();
        } else if (req == RIL_REQUEST_GET_CURRENT_CALLS) {
            ArrayList<DriverCall> calls = (ArrayList<DriverCall>) ret;
            sb = new StringBuilder("{");
            for (DriverCall dc : calls) {
                sb.append("[").append(dc).append("] ");
            }
            sb.append("}");
            s = sb.toString();
        } else if (req == RIL_REQUEST_GET_NEIGHBORING_CELL_IDS) {
            ArrayList<NeighboringCellInfo> cells = (ArrayList<NeighboringCellInfo>) ret;
            sb = new StringBuilder("{");
            for (NeighboringCellInfo cell : cells) {
                sb.append("[").append(cell).append("] ");
            }
            sb.append("}");
            s = sb.toString();
        } else if (req == RIL_REQUEST_QUERY_CALL_FORWARD_STATUS) {
            CallForwardInfo[] cinfo = (CallForwardInfo[]) ret;
            length = cinfo.length;
            sb = new StringBuilder("{");
            for (int i = 0; i < length; i++) {
                sb.append("[").append(cinfo[i]).append("] ");
            }
            sb.append("}");
            s = sb.toString();
        } else if (req == RIL_REQUEST_GET_HARDWARE_CONFIG) {
            ArrayList<HardwareConfig> hwcfgs = (ArrayList<HardwareConfig>) ret;
            sb = new StringBuilder(" ");
            for (HardwareConfig hwcfg : hwcfgs) {
                sb.append("[").append(hwcfg).append("] ");
            }
            s = sb.toString();
        } else {
            s = ret.toString();
        }
        return s;
    }

    void writeMetricsNewSms(int tech, int format) {
        mMetrics.writeRilNewSms(mPhoneId, tech, format);
    }

    void writeMetricsCallRing(char[] response) {
        mMetrics.writeRilCallRing(mPhoneId, response);
    }

    void writeMetricsSrvcc(int state) {
        mMetrics.writeRilSrvcc(mPhoneId, state);
    }

    void writeMetricsModemRestartEvent(String reason) {
        mMetrics.writeModemRestartEvent(mPhoneId, reason);
    }

    /**
     * Notify all registrants that the ril has connected or disconnected.
     *
     * @param rilVer is the version of the ril or -1 if disconnected.
     */
    void notifyRegistrantsRilConnectionChanged(int rilVer) {
        mRilVersion = rilVer;
        if (mRilConnectedRegistrants != null) {
            mRilConnectedRegistrants.notifyRegistrants(
                    new AsyncResult(null, new Integer(rilVer), null));
        }
    }

    void
    notifyRegistrantsCdmaInfoRec(CdmaInformationRecords infoRec) {
        int response = RIL_UNSOL_CDMA_INFO_REC;
        if (infoRec.record instanceof CdmaInformationRecords.CdmaDisplayInfoRec) {
            if (mDisplayInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mDisplayInfoRegistrants.notifyRegistrants(
                        new AsyncResult(null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaSignalInfoRec) {
            if (mSignalInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mSignalInfoRegistrants.notifyRegistrants(
                        new AsyncResult(null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaNumberInfoRec) {
            if (mNumberInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mNumberInfoRegistrants.notifyRegistrants(
                        new AsyncResult(null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaRedirectingNumberInfoRec) {
            if (mRedirNumInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mRedirNumInfoRegistrants.notifyRegistrants(
                        new AsyncResult(null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaLineControlInfoRec) {
            if (mLineControlInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mLineControlInfoRegistrants.notifyRegistrants(
                        new AsyncResult(null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaT53ClirInfoRec) {
            if (mT53ClirInfoRegistrants != null) {
                if (RILJ_LOGD) unsljLogRet(response, infoRec.record);
                mT53ClirInfoRegistrants.notifyRegistrants(
                        new AsyncResult(null, infoRec.record, null));
            }
        } else if (infoRec.record instanceof CdmaInformationRecords.CdmaT53AudioControlInfoRec) {
            if (mT53AudCntrlInfoRegistrants != null) {
                if (RILJ_LOGD) {
                    unsljLogRet(response, infoRec.record);
                }
                mT53AudCntrlInfoRegistrants.notifyRegistrants(
                        new AsyncResult(null, infoRec.record, null));
            }
        }
    }

    static String requestToString(int request) {
        switch(request) {
            case RIL_REQUEST_GET_SIM_STATUS:
                return "GET_SIM_STATUS";
            case RIL_REQUEST_ENTER_SIM_PIN:
                return "ENTER_SIM_PIN";
            case RIL_REQUEST_ENTER_SIM_PUK:
                return "ENTER_SIM_PUK";
            case RIL_REQUEST_ENTER_SIM_PIN2:
                return "ENTER_SIM_PIN2";
            case RIL_REQUEST_ENTER_SIM_PUK2:
                return "ENTER_SIM_PUK2";
            case RIL_REQUEST_CHANGE_SIM_PIN:
                return "CHANGE_SIM_PIN";
            case RIL_REQUEST_CHANGE_SIM_PIN2:
                return "CHANGE_SIM_PIN2";
            case RIL_REQUEST_ENTER_NETWORK_DEPERSONALIZATION:
                return "ENTER_NETWORK_DEPERSONALIZATION";
            case RIL_REQUEST_GET_CURRENT_CALLS:
                return "GET_CURRENT_CALLS";
            case RIL_REQUEST_DIAL:
                return "DIAL";
            case RIL_REQUEST_GET_IMSI:
                return "GET_IMSI";
            case RIL_REQUEST_HANGUP:
                return "HANGUP";
            case RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND:
                return "HANGUP_WAITING_OR_BACKGROUND";
            case RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND:
                return "HANGUP_FOREGROUND_RESUME_BACKGROUND";
            case RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE:
                return "REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE";
            case RIL_REQUEST_CONFERENCE:
                return "CONFERENCE";
            case RIL_REQUEST_UDUB:
                return "UDUB";
            case RIL_REQUEST_LAST_CALL_FAIL_CAUSE:
                return "LAST_CALL_FAIL_CAUSE";
            case RIL_REQUEST_SIGNAL_STRENGTH:
                return "SIGNAL_STRENGTH";
            case RIL_REQUEST_VOICE_REGISTRATION_STATE:
                return "VOICE_REGISTRATION_STATE";
            case RIL_REQUEST_DATA_REGISTRATION_STATE:
                return "DATA_REGISTRATION_STATE";
            case RIL_REQUEST_OPERATOR:
                return "OPERATOR";
            case RIL_REQUEST_RADIO_POWER:
                return "RADIO_POWER";
            case RIL_REQUEST_DTMF:
                return "DTMF";
            case RIL_REQUEST_SEND_SMS:
                return "SEND_SMS";
            case RIL_REQUEST_SEND_SMS_EXPECT_MORE:
                return "SEND_SMS_EXPECT_MORE";
            case RIL_REQUEST_SETUP_DATA_CALL:
                return "SETUP_DATA_CALL";
            case RIL_REQUEST_SIM_IO:
                return "SIM_IO";
            case RIL_REQUEST_SEND_USSD:
                return "SEND_USSD";
            case RIL_REQUEST_CANCEL_USSD:
                return "CANCEL_USSD";
            case RIL_REQUEST_GET_CLIR:
                return "GET_CLIR";
            case RIL_REQUEST_SET_CLIR:
                return "SET_CLIR";
            case RIL_REQUEST_QUERY_CALL_FORWARD_STATUS:
                return "QUERY_CALL_FORWARD_STATUS";
            case RIL_REQUEST_SET_CALL_FORWARD:
                return "SET_CALL_FORWARD";
            case RIL_REQUEST_QUERY_CALL_WAITING:
                return "QUERY_CALL_WAITING";
            case RIL_REQUEST_SET_CALL_WAITING:
                return "SET_CALL_WAITING";
            case RIL_REQUEST_SMS_ACKNOWLEDGE:
                return "SMS_ACKNOWLEDGE";
            case RIL_REQUEST_GET_IMEI:
                return "GET_IMEI";
            case RIL_REQUEST_GET_IMEISV:
                return "GET_IMEISV";
            case RIL_REQUEST_ANSWER:
                return "ANSWER";
            case RIL_REQUEST_DEACTIVATE_DATA_CALL:
                return "DEACTIVATE_DATA_CALL";
            case RIL_REQUEST_QUERY_FACILITY_LOCK:
                return "QUERY_FACILITY_LOCK";
            case RIL_REQUEST_SET_FACILITY_LOCK:
                return "SET_FACILITY_LOCK";
            case RIL_REQUEST_CHANGE_BARRING_PASSWORD:
                return "CHANGE_BARRING_PASSWORD";
            case RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE:
                return "QUERY_NETWORK_SELECTION_MODE";
            case RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC:
                return "SET_NETWORK_SELECTION_AUTOMATIC";
            case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL:
                return "SET_NETWORK_SELECTION_MANUAL";
            case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS :
                return "QUERY_AVAILABLE_NETWORKS ";
            case RIL_REQUEST_DTMF_START:
                return "DTMF_START";
            case RIL_REQUEST_DTMF_STOP:
                return "DTMF_STOP";
            case RIL_REQUEST_BASEBAND_VERSION:
                return "BASEBAND_VERSION";
            case RIL_REQUEST_SEPARATE_CONNECTION:
                return "SEPARATE_CONNECTION";
            case RIL_REQUEST_SET_MUTE:
                return "SET_MUTE";
            case RIL_REQUEST_GET_MUTE:
                return "GET_MUTE";
            case RIL_REQUEST_QUERY_CLIP:
                return "QUERY_CLIP";
            case RIL_REQUEST_LAST_DATA_CALL_FAIL_CAUSE:
                return "LAST_DATA_CALL_FAIL_CAUSE";
            case RIL_REQUEST_DATA_CALL_LIST:
                return "DATA_CALL_LIST";
            case RIL_REQUEST_RESET_RADIO:
                return "RESET_RADIO";
            case RIL_REQUEST_OEM_HOOK_RAW:
                return "OEM_HOOK_RAW";
            case RIL_REQUEST_OEM_HOOK_STRINGS:
                return "OEM_HOOK_STRINGS";
            case RIL_REQUEST_SCREEN_STATE:
                return "SCREEN_STATE";
            case RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION:
                return "SET_SUPP_SVC_NOTIFICATION";
            case RIL_REQUEST_WRITE_SMS_TO_SIM:
                return "WRITE_SMS_TO_SIM";
            case RIL_REQUEST_DELETE_SMS_ON_SIM:
                return "DELETE_SMS_ON_SIM";
            case RIL_REQUEST_SET_BAND_MODE:
                return "SET_BAND_MODE";
            case RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE:
                return "QUERY_AVAILABLE_BAND_MODE";
            case RIL_REQUEST_STK_GET_PROFILE:
                return "REQUEST_STK_GET_PROFILE";
            case RIL_REQUEST_STK_SET_PROFILE:
                return "REQUEST_STK_SET_PROFILE";
            case RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND:
                return "REQUEST_STK_SEND_ENVELOPE_COMMAND";
            case RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE:
                return "REQUEST_STK_SEND_TERMINAL_RESPONSE";
            case RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM:
                return "REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM";
            case RIL_REQUEST_EXPLICIT_CALL_TRANSFER: return "REQUEST_EXPLICIT_CALL_TRANSFER";
            case RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE:
                return "REQUEST_SET_PREFERRED_NETWORK_TYPE";
            case RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE:
                return "REQUEST_GET_PREFERRED_NETWORK_TYPE";
            case RIL_REQUEST_GET_NEIGHBORING_CELL_IDS:
                return "REQUEST_GET_NEIGHBORING_CELL_IDS";
            case RIL_REQUEST_SET_LOCATION_UPDATES:
                return "REQUEST_SET_LOCATION_UPDATES";
            case RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE:
                return "RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE";
            case RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE:
                return "RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE";
            case RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE:
                return "RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE";
            case RIL_REQUEST_SET_TTY_MODE:
                return "RIL_REQUEST_SET_TTY_MODE";
            case RIL_REQUEST_QUERY_TTY_MODE:
                return "RIL_REQUEST_QUERY_TTY_MODE";
            case RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE:
                return "RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE";
            case RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE:
                return "RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE";
            case RIL_REQUEST_CDMA_FLASH:
                return "RIL_REQUEST_CDMA_FLASH";
            case RIL_REQUEST_CDMA_BURST_DTMF:
                return "RIL_REQUEST_CDMA_BURST_DTMF";
            case RIL_REQUEST_CDMA_SEND_SMS:
                return "RIL_REQUEST_CDMA_SEND_SMS";
            case RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE:
                return "RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE";
            case RIL_REQUEST_GSM_GET_BROADCAST_CONFIG:
                return "RIL_REQUEST_GSM_GET_BROADCAST_CONFIG";
            case RIL_REQUEST_GSM_SET_BROADCAST_CONFIG:
                return "RIL_REQUEST_GSM_SET_BROADCAST_CONFIG";
            case RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG:
                return "RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG";
            case RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG:
                return "RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG";
            case RIL_REQUEST_GSM_BROADCAST_ACTIVATION:
                return "RIL_REQUEST_GSM_BROADCAST_ACTIVATION";
            case RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY:
                return "RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY";
            case RIL_REQUEST_CDMA_BROADCAST_ACTIVATION:
                return "RIL_REQUEST_CDMA_BROADCAST_ACTIVATION";
            case RIL_REQUEST_CDMA_SUBSCRIPTION:
                return "RIL_REQUEST_CDMA_SUBSCRIPTION";
            case RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM:
                return "RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM";
            case RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM:
                return "RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM";
            case RIL_REQUEST_DEVICE_IDENTITY:
                return "RIL_REQUEST_DEVICE_IDENTITY";
            case RIL_REQUEST_GET_SMSC_ADDRESS:
                return "RIL_REQUEST_GET_SMSC_ADDRESS";
            case RIL_REQUEST_SET_SMSC_ADDRESS:
                return "RIL_REQUEST_SET_SMSC_ADDRESS";
            case RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE:
                return "REQUEST_EXIT_EMERGENCY_CALLBACK_MODE";
            case RIL_REQUEST_REPORT_SMS_MEMORY_STATUS:
                return "RIL_REQUEST_REPORT_SMS_MEMORY_STATUS";
            case RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING:
                return "RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING";
            case RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE:
                return "RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE";
            case RIL_REQUEST_ISIM_AUTHENTICATION:
                return "RIL_REQUEST_ISIM_AUTHENTICATION";
            case RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU:
                return "RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU";
            case RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS:
                return "RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS";
            case RIL_REQUEST_VOICE_RADIO_TECH:
                return "RIL_REQUEST_VOICE_RADIO_TECH";
            case RIL_REQUEST_GET_CELL_INFO_LIST:
                return "RIL_REQUEST_GET_CELL_INFO_LIST";
            case RIL_REQUEST_SET_UNSOL_CELL_INFO_LIST_RATE:
                return "RIL_REQUEST_SET_CELL_INFO_LIST_RATE";
            case RIL_REQUEST_SET_INITIAL_ATTACH_APN:
                return "RIL_REQUEST_SET_INITIAL_ATTACH_APN";
            case RIL_REQUEST_SET_DATA_PROFILE:
                return "RIL_REQUEST_SET_DATA_PROFILE";
            case RIL_REQUEST_IMS_REGISTRATION_STATE:
                return "RIL_REQUEST_IMS_REGISTRATION_STATE";
            case RIL_REQUEST_IMS_SEND_SMS:
                return "RIL_REQUEST_IMS_SEND_SMS";
            case RIL_REQUEST_SIM_TRANSMIT_APDU_BASIC:
                return "RIL_REQUEST_SIM_TRANSMIT_APDU_BASIC";
            case RIL_REQUEST_SIM_OPEN_CHANNEL:
                return "RIL_REQUEST_SIM_OPEN_CHANNEL";
            case RIL_REQUEST_SIM_CLOSE_CHANNEL:
                return "RIL_REQUEST_SIM_CLOSE_CHANNEL";
            case RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL:
                return "RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL";
            case RIL_REQUEST_NV_READ_ITEM:
                return "RIL_REQUEST_NV_READ_ITEM";
            case RIL_REQUEST_NV_WRITE_ITEM:
                return "RIL_REQUEST_NV_WRITE_ITEM";
            case RIL_REQUEST_NV_WRITE_CDMA_PRL:
                return "RIL_REQUEST_NV_WRITE_CDMA_PRL";
            case RIL_REQUEST_NV_RESET_CONFIG:
                return "RIL_REQUEST_NV_RESET_CONFIG";
            case RIL_REQUEST_SET_UICC_SUBSCRIPTION:
                return "RIL_REQUEST_SET_UICC_SUBSCRIPTION";
            case RIL_REQUEST_ALLOW_DATA:
                return "RIL_REQUEST_ALLOW_DATA";
            case RIL_REQUEST_GET_HARDWARE_CONFIG:
                return "GET_HARDWARE_CONFIG";
            case RIL_REQUEST_SIM_AUTHENTICATION:
                return "RIL_REQUEST_SIM_AUTHENTICATION";
            case RIL_REQUEST_SHUTDOWN:
                return "RIL_REQUEST_SHUTDOWN";
            case RIL_REQUEST_SET_RADIO_CAPABILITY:
                return "RIL_REQUEST_SET_RADIO_CAPABILITY";
            case RIL_REQUEST_GET_RADIO_CAPABILITY:
                return "RIL_REQUEST_GET_RADIO_CAPABILITY";
            case RIL_REQUEST_START_LCE:
                return "RIL_REQUEST_START_LCE";
            case RIL_REQUEST_STOP_LCE:
                return "RIL_REQUEST_STOP_LCE";
            case RIL_REQUEST_PULL_LCEDATA:
                return "RIL_REQUEST_PULL_LCEDATA";
            case RIL_REQUEST_GET_ACTIVITY_INFO:
                return "RIL_REQUEST_GET_ACTIVITY_INFO";
            case RIL_REQUEST_SET_ALLOWED_CARRIERS:
                return "RIL_REQUEST_SET_ALLOWED_CARRIERS";
            case RIL_REQUEST_GET_ALLOWED_CARRIERS:
                return "RIL_REQUEST_GET_ALLOWED_CARRIERS";
            case RIL_REQUEST_SET_SIM_CARD_POWER:
                return "RIL_REQUEST_SET_SIM_CARD_POWER";
            case RIL_REQUEST_SEND_DEVICE_STATE:
                return "RIL_REQUEST_SEND_DEVICE_STATE";
            case RIL_REQUEST_SET_UNSOLICITED_RESPONSE_FILTER:
                return "RIL_REQUEST_SET_UNSOLICITED_RESPONSE_FILTER";
            case RIL_RESPONSE_ACKNOWLEDGEMENT:
                return "RIL_RESPONSE_ACKNOWLEDGEMENT";
            case RIL_REQUEST_SET_CARRIER_INFO_IMSI_ENCRYPTION:
                return "RIL_REQUEST_SET_CARRIER_INFO_IMSI_ENCRYPTION";
            case RIL_REQUEST_START_NETWORK_SCAN:
                return "RIL_REQUEST_START_NETWORK_SCAN";
            case RIL_REQUEST_STOP_NETWORK_SCAN:
                return "RIL_REQUEST_STOP_NETWORK_SCAN";
            default: return "<unknown request>";
        }
    }

    static String responseToString(int request) {
        switch(request) {
            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED:
                return "UNSOL_RESPONSE_RADIO_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED:
                return "UNSOL_RESPONSE_CALL_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_NETWORK_STATE_CHANGED:
                return "UNSOL_RESPONSE_NETWORK_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_NEW_SMS:
                return "UNSOL_RESPONSE_NEW_SMS";
            case RIL_UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT:
                return "UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT";
            case RIL_UNSOL_RESPONSE_NEW_SMS_ON_SIM:
                return "UNSOL_RESPONSE_NEW_SMS_ON_SIM";
            case RIL_UNSOL_ON_USSD:
                return "UNSOL_ON_USSD";
            case RIL_UNSOL_ON_USSD_REQUEST:
                return "UNSOL_ON_USSD_REQUEST";
            case RIL_UNSOL_NITZ_TIME_RECEIVED:
                return "UNSOL_NITZ_TIME_RECEIVED";
            case RIL_UNSOL_SIGNAL_STRENGTH:
                return "UNSOL_SIGNAL_STRENGTH";
            case RIL_UNSOL_DATA_CALL_LIST_CHANGED:
                return "UNSOL_DATA_CALL_LIST_CHANGED";
            case RIL_UNSOL_SUPP_SVC_NOTIFICATION:
                return "UNSOL_SUPP_SVC_NOTIFICATION";
            case RIL_UNSOL_STK_SESSION_END:
                return "UNSOL_STK_SESSION_END";
            case RIL_UNSOL_STK_PROACTIVE_COMMAND:
                return "UNSOL_STK_PROACTIVE_COMMAND";
            case RIL_UNSOL_STK_EVENT_NOTIFY:
                return "UNSOL_STK_EVENT_NOTIFY";
            case RIL_UNSOL_STK_CALL_SETUP:
                return "UNSOL_STK_CALL_SETUP";
            case RIL_UNSOL_SIM_SMS_STORAGE_FULL:
                return "UNSOL_SIM_SMS_STORAGE_FULL";
            case RIL_UNSOL_SIM_REFRESH:
                return "UNSOL_SIM_REFRESH";
            case RIL_UNSOL_CALL_RING:
                return "UNSOL_CALL_RING";
            case RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED:
                return "UNSOL_RESPONSE_SIM_STATUS_CHANGED";
            case RIL_UNSOL_RESPONSE_CDMA_NEW_SMS:
                return "UNSOL_RESPONSE_CDMA_NEW_SMS";
            case RIL_UNSOL_RESPONSE_NEW_BROADCAST_SMS:
                return "UNSOL_RESPONSE_NEW_BROADCAST_SMS";
            case RIL_UNSOL_CDMA_RUIM_SMS_STORAGE_FULL:
                return "UNSOL_CDMA_RUIM_SMS_STORAGE_FULL";
            case RIL_UNSOL_RESTRICTED_STATE_CHANGED:
                return "UNSOL_RESTRICTED_STATE_CHANGED";
            case RIL_UNSOL_ENTER_EMERGENCY_CALLBACK_MODE:
                return "UNSOL_ENTER_EMERGENCY_CALLBACK_MODE";
            case RIL_UNSOL_CDMA_CALL_WAITING:
                return "UNSOL_CDMA_CALL_WAITING";
            case RIL_UNSOL_CDMA_OTA_PROVISION_STATUS:
                return "UNSOL_CDMA_OTA_PROVISION_STATUS";
            case RIL_UNSOL_CDMA_INFO_REC:
                return "UNSOL_CDMA_INFO_REC";
            case RIL_UNSOL_OEM_HOOK_RAW:
                return "UNSOL_OEM_HOOK_RAW";
            case RIL_UNSOL_RINGBACK_TONE:
                return "UNSOL_RINGBACK_TONE";
            case RIL_UNSOL_RESEND_INCALL_MUTE:
                return "UNSOL_RESEND_INCALL_MUTE";
            case RIL_UNSOL_CDMA_SUBSCRIPTION_SOURCE_CHANGED:
                return "CDMA_SUBSCRIPTION_SOURCE_CHANGED";
            case RIL_UNSOl_CDMA_PRL_CHANGED:
                return "UNSOL_CDMA_PRL_CHANGED";
            case RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE:
                return "UNSOL_EXIT_EMERGENCY_CALLBACK_MODE";
            case RIL_UNSOL_RIL_CONNECTED:
                return "UNSOL_RIL_CONNECTED";
            case RIL_UNSOL_VOICE_RADIO_TECH_CHANGED:
                return "UNSOL_VOICE_RADIO_TECH_CHANGED";
            case RIL_UNSOL_CELL_INFO_LIST:
                return "UNSOL_CELL_INFO_LIST";
            case RIL_UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED:
                return "UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED";
            case RIL_UNSOL_UICC_SUBSCRIPTION_STATUS_CHANGED:
                return "RIL_UNSOL_UICC_SUBSCRIPTION_STATUS_CHANGED";
            case RIL_UNSOL_SRVCC_STATE_NOTIFY:
                return "UNSOL_SRVCC_STATE_NOTIFY";
            case RIL_UNSOL_HARDWARE_CONFIG_CHANGED:
                return "RIL_UNSOL_HARDWARE_CONFIG_CHANGED";
            case RIL_UNSOL_RADIO_CAPABILITY:
                return "RIL_UNSOL_RADIO_CAPABILITY";
            case RIL_UNSOL_ON_SS:
                return "UNSOL_ON_SS";
            case RIL_UNSOL_STK_CC_ALPHA_NOTIFY:
                return "UNSOL_STK_CC_ALPHA_NOTIFY";
            case RIL_UNSOL_LCEDATA_RECV:
                return "UNSOL_LCE_INFO_RECV";
            case RIL_UNSOL_PCO_DATA:
                return "UNSOL_PCO_DATA";
            case RIL_UNSOL_MODEM_RESTART:
                return "UNSOL_MODEM_RESTART";
            case RIL_UNSOL_CARRIER_INFO_IMSI_ENCRYPTION:
                return "RIL_UNSOL_CARRIER_INFO_IMSI_ENCRYPTION";
            case RIL_UNSOL_NETWORK_SCAN_RESULT:
                return "RIL_UNSOL_NETWORK_SCAN_RESULT";
            default:
                return "<unknown response>";
        }
    }

    void riljLog(String msg) {
        Rlog.d(RILJ_LOG_TAG, msg
                + (mPhoneId != null ? (" [SUB" + mPhoneId + "]") : ""));
    }

    void riljLoge(String msg) {
        Rlog.e(RILJ_LOG_TAG, msg
                + (mPhoneId != null ? (" [SUB" + mPhoneId + "]") : ""));
    }

    void riljLoge(String msg, Exception e) {
        Rlog.e(RILJ_LOG_TAG, msg
                + (mPhoneId != null ? (" [SUB" + mPhoneId + "]") : ""), e);
    }

    void riljLogv(String msg) {
        Rlog.v(RILJ_LOG_TAG, msg
                + (mPhoneId != null ? (" [SUB" + mPhoneId + "]") : ""));
    }

    void unsljLog(int response) {
        riljLog("[UNSL]< " + responseToString(response));
    }

    void unsljLogMore(int response, String more) {
        riljLog("[UNSL]< " + responseToString(response) + " " + more);
    }

    void unsljLogRet(int response, Object ret) {
        riljLog("[UNSL]< " + responseToString(response) + " " + retToString(response, ret));
    }

    void unsljLogvRet(int response, Object ret) {
        riljLogv("[UNSL]< " + responseToString(response) + " " + retToString(response, ret));
    }

    @Override
    public void setPhoneType(int phoneType) { // Called by GsmCdmaPhone
        if (RILJ_LOGD) riljLog("setPhoneType=" + phoneType + " old value=" + mPhoneType);
        mPhoneType = phoneType;
    }

    /* (non-Javadoc)
     * @see com.android.internal.telephony.BaseCommands#testingEmergencyCall()
     */
    @Override
    public void testingEmergencyCall() {
        if (RILJ_LOGD) riljLog("testingEmergencyCall");
        mTestingEmergencyCall.set(true);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("RIL: " + this);
        pw.println(" mWakeLock=" + mWakeLock);
        pw.println(" mWakeLockTimeout=" + mWakeLockTimeout);
        synchronized (mRequestList) {
            synchronized (mWakeLock) {
                pw.println(" mWakeLockCount=" + mWakeLockCount);
            }
            int count = mRequestList.size();
            pw.println(" mRequestList count=" + count);
            for (int i = 0; i < count; i++) {
                RILRequest rr = mRequestList.valueAt(i);
                pw.println("  [" + rr.mSerial + "] " + requestToString(rr.mRequest));
            }
        }
        pw.println(" mLastNITZTimeInfo=" + Arrays.toString(mLastNITZTimeInfo));
        pw.println(" mTestingEmergencyCall=" + mTestingEmergencyCall.get());
        mClientWakelockTracker.dumpClientRequestTracker(pw);
    }

    public List<ClientRequestStats> getClientRequestStats() {
        return mClientWakelockTracker.getClientRequestStats();
    }

    public static ArrayList<Byte> primitiveArrayToArrayList(byte[] arr) {
        ArrayList<Byte> arrayList = new ArrayList<>(arr.length);
        for (byte b : arr) {
            arrayList.add(b);
        }
        return arrayList;
    }

    public static byte[] arrayListToPrimitiveArray(ArrayList<Byte> bytes) {
        byte[] ret = new byte[bytes.size()];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = bytes.get(i);
        }
        return ret;
    }

    static ArrayList<HardwareConfig> convertHalHwConfigList(
            ArrayList<android.hardware.radio.V1_0.HardwareConfig> hwListRil,
            RIL ril) {
        int num;
        ArrayList<HardwareConfig> response;
        HardwareConfig hw;

        num = hwListRil.size();
        response = new ArrayList<HardwareConfig>(num);

        if (RILJ_LOGV) {
            ril.riljLog("convertHalHwConfigList: num=" + num);
        }
        for (android.hardware.radio.V1_0.HardwareConfig hwRil : hwListRil) {
            int type = hwRil.type;
            switch(type) {
                case HardwareConfig.DEV_HARDWARE_TYPE_MODEM: {
                    hw = new HardwareConfig(type);
                    HardwareConfigModem hwModem = hwRil.modem.get(0);
                    hw.assignModem(hwRil.uuid, hwRil.state, hwModem.rilModel, hwModem.rat,
                            hwModem.maxVoice, hwModem.maxData, hwModem.maxStandby);
                    break;
                }
                case HardwareConfig.DEV_HARDWARE_TYPE_SIM: {
                    hw = new HardwareConfig(type);
                    hw.assignSim(hwRil.uuid, hwRil.state, hwRil.sim.get(0).modemUuid);
                    break;
                }
                default: {
                    throw new RuntimeException(
                            "RIL_REQUEST_GET_HARDWARE_CONFIG invalid hardward type:" + type);
                }
            }

            response.add(hw);
        }

        return response;
    }

    static RadioCapability convertHalRadioCapability(
            android.hardware.radio.V1_0.RadioCapability rcRil, RIL ril) {
        int session = rcRil.session;
        int phase = rcRil.phase;
        int rat = rcRil.raf;
        String logicModemUuid = rcRil.logicalModemUuid;
        int status = rcRil.status;

        ril.riljLog("convertHalRadioCapability: session=" + session +
                ", phase=" + phase +
                ", rat=" + rat +
                ", logicModemUuid=" + logicModemUuid +
                ", status=" + status);
        RadioCapability rc = new RadioCapability(
                ril.mPhoneId, session, phase, rat, logicModemUuid, status);
        return rc;
    }

    static ArrayList<Integer> convertHalLceData(LceDataInfo lce, RIL ril) {
        final ArrayList<Integer> capacityResponse = new ArrayList<Integer>();
        final int capacityDownKbps = lce.lastHopCapacityKbps;
        final int confidenceLevel = Byte.toUnsignedInt(lce.confidenceLevel);
        final int lceSuspended = lce.lceSuspended ? 1 : 0;

        ril.riljLog("LCE capacity information received:" +
                " capacity=" + capacityDownKbps +
                " confidence=" + confidenceLevel +
                " lceSuspended=" + lceSuspended);

        capacityResponse.add(capacityDownKbps);
        capacityResponse.add(confidenceLevel);
        capacityResponse.add(lceSuspended);
        return capacityResponse;
    }

    static ArrayList<CellInfo> convertHalCellInfoList(
            ArrayList<android.hardware.radio.V1_0.CellInfo> records) {
        ArrayList<CellInfo> response = new ArrayList<CellInfo>(records.size());

        for (android.hardware.radio.V1_0.CellInfo record : records) {
            // first convert RIL CellInfo to Parcel
            Parcel p = Parcel.obtain();
            p.writeInt(record.cellInfoType);
            p.writeInt(record.registered ? 1 : 0);
            p.writeInt(record.timeStampType);
            p.writeLong(record.timeStamp);
            switch (record.cellInfoType) {
                case CellInfoType.GSM: {
                    CellInfoGsm cellInfoGsm = record.gsm.get(0);
                    p.writeInt(Integer.parseInt(cellInfoGsm.cellIdentityGsm.mcc));
                    p.writeInt(Integer.parseInt(cellInfoGsm.cellIdentityGsm.mnc));
                    p.writeInt(cellInfoGsm.cellIdentityGsm.lac);
                    p.writeInt(cellInfoGsm.cellIdentityGsm.cid);
                    p.writeInt(cellInfoGsm.cellIdentityGsm.arfcn);
                    p.writeInt(Byte.toUnsignedInt(cellInfoGsm.cellIdentityGsm.bsic));
                    p.writeInt(cellInfoGsm.signalStrengthGsm.signalStrength);
                    p.writeInt(cellInfoGsm.signalStrengthGsm.bitErrorRate);
                    p.writeInt(cellInfoGsm.signalStrengthGsm.timingAdvance);
                    break;
                }

                case CellInfoType.CDMA: {
                    CellInfoCdma cellInfoCdma = record.cdma.get(0);
                    p.writeInt(cellInfoCdma.cellIdentityCdma.networkId);
                    p.writeInt(cellInfoCdma.cellIdentityCdma.systemId);
                    p.writeInt(cellInfoCdma.cellIdentityCdma.baseStationId);
                    p.writeInt(cellInfoCdma.cellIdentityCdma.longitude);
                    p.writeInt(cellInfoCdma.cellIdentityCdma.latitude);
                    p.writeInt(cellInfoCdma.signalStrengthCdma.dbm);
                    p.writeInt(cellInfoCdma.signalStrengthCdma.ecio);
                    p.writeInt(cellInfoCdma.signalStrengthEvdo.dbm);
                    p.writeInt(cellInfoCdma.signalStrengthEvdo.ecio);
                    p.writeInt(cellInfoCdma.signalStrengthEvdo.signalNoiseRatio);
                    break;
                }

                case CellInfoType.LTE: {
                    CellInfoLte cellInfoLte = record.lte.get(0);
                    p.writeInt(Integer.parseInt(cellInfoLte.cellIdentityLte.mcc));
                    p.writeInt(Integer.parseInt(cellInfoLte.cellIdentityLte.mnc));
                    p.writeInt(cellInfoLte.cellIdentityLte.ci);
                    p.writeInt(cellInfoLte.cellIdentityLte.pci);
                    p.writeInt(cellInfoLte.cellIdentityLte.tac);
                    p.writeInt(cellInfoLte.cellIdentityLte.earfcn);
                    p.writeInt(cellInfoLte.signalStrengthLte.signalStrength);
                    p.writeInt(cellInfoLte.signalStrengthLte.rsrp);
                    p.writeInt(cellInfoLte.signalStrengthLte.rsrq);
                    p.writeInt(cellInfoLte.signalStrengthLte.rssnr);
                    p.writeInt(cellInfoLte.signalStrengthLte.cqi);
                    p.writeInt(cellInfoLte.signalStrengthLte.timingAdvance);
                    break;
                }

                case CellInfoType.WCDMA: {
                    CellInfoWcdma cellInfoWcdma = record.wcdma.get(0);
                    p.writeInt(Integer.parseInt(cellInfoWcdma.cellIdentityWcdma.mcc));
                    p.writeInt(Integer.parseInt(cellInfoWcdma.cellIdentityWcdma.mnc));
                    p.writeInt(cellInfoWcdma.cellIdentityWcdma.lac);
                    p.writeInt(cellInfoWcdma.cellIdentityWcdma.cid);
                    p.writeInt(cellInfoWcdma.cellIdentityWcdma.psc);
                    p.writeInt(cellInfoWcdma.cellIdentityWcdma.uarfcn);
                    p.writeInt(cellInfoWcdma.signalStrengthWcdma.signalStrength);
                    p.writeInt(cellInfoWcdma.signalStrengthWcdma.bitErrorRate);
                    break;
                }

                default:
                    throw new RuntimeException("unexpected cellinfotype: " + record.cellInfoType);
            }

            p.setDataPosition(0);
            CellInfo InfoRec = CellInfo.CREATOR.createFromParcel(p);
            p.recycle();
            response.add(InfoRec);
        }

        return response;
    }

    static SignalStrength convertHalSignalStrength(
            android.hardware.radio.V1_0.SignalStrength signalStrength) {
        return new SignalStrength(signalStrength.gw.signalStrength,
                signalStrength.gw.bitErrorRate,
                signalStrength.cdma.dbm,
                signalStrength.cdma.ecio,
                signalStrength.evdo.dbm,
                signalStrength.evdo.ecio,
                signalStrength.evdo.signalNoiseRatio,
                signalStrength.lte.signalStrength,
                signalStrength.lte.rsrp,
                signalStrength.lte.rsrq,
                signalStrength.lte.rssnr,
                signalStrength.lte.cqi,
                signalStrength.tdScdma.rscp,
                false /* gsmFlag - don't care; will be changed by SST */);
    }
}
