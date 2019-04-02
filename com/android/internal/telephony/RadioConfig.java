/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.internal.telephony.RILConstants.RADIO_NOT_AVAILABLE;
import static com.android.internal.telephony.RILConstants.RIL_REQUEST_GET_SLOT_STATUS;
import static com.android.internal.telephony.RILConstants.RIL_REQUEST_SET_LOGICAL_TO_PHYSICAL_SLOT_MAPPING;

import android.content.Context;
import android.hardware.radio.V1_0.RadioResponseInfo;
import android.hardware.radio.V1_0.RadioResponseType;
import android.hardware.radio.config.V1_0.IRadioConfig;
import android.hardware.radio.config.V1_0.SimSlotStatus;
import android.net.ConnectivityManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HwBinder;
import android.os.Message;
import android.os.Registrant;
import android.os.RemoteException;
import android.os.WorkSource;
import android.telephony.Rlog;
import android.util.SparseArray;

import com.android.internal.telephony.uicc.IccSlotStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This class provides wrapper APIs for IRadioConfig interface.
 */
public class RadioConfig extends Handler {
    private static final String TAG = "RadioConfig";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;   //STOPSHIP if true

    private static final int EVENT_SERVICE_DEAD = 1;

    private final boolean mIsMobileNetworkSupported;
    private volatile IRadioConfig mRadioConfigProxy = null;
    private final ServiceDeathRecipient mServiceDeathRecipient;
    private final AtomicLong mRadioConfigProxyCookie = new AtomicLong(0);
    private final RadioConfigResponse mRadioConfigResponse;
    private final RadioConfigIndication mRadioConfigIndication;
    private final SparseArray<RILRequest> mRequestList = new SparseArray<RILRequest>();
    /* default work source which will blame phone process */
    private final WorkSource mDefaultWorkSource;
    private static RadioConfig sRadioConfig;

    protected Registrant mSimSlotStatusRegistrant;

    final class ServiceDeathRecipient implements HwBinder.DeathRecipient {
        @Override
        public void serviceDied(long cookie) {
            // Deal with service going away
            logd("serviceDied");
            sendMessage(obtainMessage(EVENT_SERVICE_DEAD, cookie));
        }
    }

    private RadioConfig(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        mIsMobileNetworkSupported = cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);

        mRadioConfigResponse = new RadioConfigResponse(this);
        mRadioConfigIndication = new RadioConfigIndication(this);
        mServiceDeathRecipient = new ServiceDeathRecipient();

        mDefaultWorkSource = new WorkSource(context.getApplicationInfo().uid,
                context.getPackageName());
    }

    /**
     * Returns the singleton static instance of RadioConfig
     */
    public static RadioConfig getInstance(Context context) {
        if (sRadioConfig == null) {
            sRadioConfig = new RadioConfig(context);
        }
        return sRadioConfig;
    }

    @Override
    public void handleMessage(Message message) {
        switch (message.what) {
            case EVENT_SERVICE_DEAD:
                logd("handleMessage: EVENT_SERVICE_DEAD cookie = " + message.obj
                        + " mRadioConfigProxyCookie = " + mRadioConfigProxyCookie.get());
                if ((long) message.obj == mRadioConfigProxyCookie.get()) {
                    resetProxyAndRequestList("EVENT_SERVICE_DEAD", null);
                }
                break;
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
            if (DBG && loggable) {
                logd("clearRequestList: mRequestList=" + count);
            }

            for (int i = 0; i < count; i++) {
                rr = mRequestList.valueAt(i);
                if (DBG && loggable) {
                    logd(i + ": [" + rr.mSerial + "] " + requestToString(rr.mRequest));
                }
                rr.onError(error, null);
                rr.release();
            }
            mRequestList.clear();
        }
    }

    private void resetProxyAndRequestList(String caller, Exception e) {
        loge(caller + ": " + e);
        mRadioConfigProxy = null;

        // increment the cookie so that death notification can be ignored
        mRadioConfigProxyCookie.incrementAndGet();

        RILRequest.resetSerial();
        // Clear request list on close
        clearRequestList(RADIO_NOT_AVAILABLE, false);

        getRadioConfigProxy(null);
    }

    /** Returns a {@link IRadioConfig} instance or null if the service is not available. */
    public IRadioConfig getRadioConfigProxy(Message result) {
        if (!mIsMobileNetworkSupported) {
            if (VDBG) logd("getRadioConfigProxy: Not calling getService(): wifi-only");
            if (result != null) {
                AsyncResult.forMessage(result, null,
                        CommandException.fromRilErrno(RADIO_NOT_AVAILABLE));
                result.sendToTarget();
            }
            return null;
        }

        if (mRadioConfigProxy != null) {
            return mRadioConfigProxy;
        }

        try {
            mRadioConfigProxy = IRadioConfig.getService(true);
            if (mRadioConfigProxy != null) {
                mRadioConfigProxy.linkToDeath(mServiceDeathRecipient,
                        mRadioConfigProxyCookie.incrementAndGet());
                mRadioConfigProxy.setResponseFunctions(mRadioConfigResponse,
                        mRadioConfigIndication);
            } else {
                loge("getRadioConfigProxy: mRadioConfigProxy == null");
            }
        } catch (RemoteException | RuntimeException e) {
            mRadioConfigProxy = null;
            loge("getRadioConfigProxy: RadioConfigProxy getService/setResponseFunctions: " + e);
        }

        if (mRadioConfigProxy == null) {
            // getService() is a blocking call, so this should never happen
            loge("getRadioConfigProxy: mRadioConfigProxy == null");
            if (result != null) {
                AsyncResult.forMessage(result, null,
                        CommandException.fromRilErrno(RADIO_NOT_AVAILABLE));
                result.sendToTarget();
            }
        }

        return mRadioConfigProxy;
    }

    private RILRequest obtainRequest(int request, Message result, WorkSource workSource) {
        RILRequest rr = RILRequest.obtain(request, result, workSource);
        synchronized (mRequestList) {
            mRequestList.append(rr.mSerial, rr);
        }
        return rr;
    }

    private RILRequest findAndRemoveRequestFromList(int serial) {
        RILRequest rr;
        synchronized (mRequestList) {
            rr = mRequestList.get(serial);
            if (rr != null) {
                mRequestList.remove(serial);
            }
        }

        return rr;
    }

    /**
     * This is a helper function to be called when a RadioConfigResponse callback is called.
     * It finds and returns RILRequest corresponding to the response if one is found.
     * @param responseInfo RadioResponseInfo received in response callback
     * @return RILRequest corresponding to the response
     */
    public RILRequest processResponse(RadioResponseInfo responseInfo) {
        int serial = responseInfo.serial;
        int error = responseInfo.error;
        int type = responseInfo.type;

        if (type != RadioResponseType.SOLICITED) {
            loge("processResponse: Unexpected response type " + type);
        }

        RILRequest rr = findAndRemoveRequestFromList(serial);
        if (rr == null) {
            loge("processResponse: Unexpected response! serial: " + serial + " error: " + error);
            return null;
        }

        return rr;
    }

    /**
     * Wrapper function for IRadioConfig.getSimSlotsStatus().
     */
    public void getSimSlotsStatus(Message result) {
        IRadioConfig radioConfigProxy = getRadioConfigProxy(result);
        if (radioConfigProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_GET_SLOT_STATUS, result, mDefaultWorkSource);

            if (DBG) {
                logd(rr.serialString() + "> " + requestToString(rr.mRequest));
            }

            try {
                radioConfigProxy.getSimSlotsStatus(rr.mSerial);
            } catch (RemoteException | RuntimeException e) {
                resetProxyAndRequestList("getSimSlotsStatus", e);
            }
        }
    }

    /**
     * Wrapper function for IRadioConfig.getSimSlotsStatus().
     */
    public void setSimSlotsMapping(int[] physicalSlots, Message result) {
        IRadioConfig radioConfigProxy = getRadioConfigProxy(result);
        if (radioConfigProxy != null) {
            RILRequest rr = obtainRequest(RIL_REQUEST_SET_LOGICAL_TO_PHYSICAL_SLOT_MAPPING, result,
                    mDefaultWorkSource);

            if (DBG) {
                logd(rr.serialString() + "> " + requestToString(rr.mRequest)
                        + " " + Arrays.toString(physicalSlots));
            }

            try {
                radioConfigProxy.setSimSlotsMapping(rr.mSerial,
                        primitiveArrayToArrayList(physicalSlots));
            } catch (RemoteException | RuntimeException e) {
                resetProxyAndRequestList("setSimSlotsMapping", e);
            }
        }
    }

    private static ArrayList<Integer> primitiveArrayToArrayList(int[] arr) {
        ArrayList<Integer> arrayList = new ArrayList<>(arr.length);
        for (int i : arr) {
            arrayList.add(i);
        }
        return arrayList;
    }

    static String requestToString(int request) {
        switch (request) {
            case RIL_REQUEST_GET_SLOT_STATUS:
                return "GET_SLOT_STATUS";
            case RIL_REQUEST_SET_LOGICAL_TO_PHYSICAL_SLOT_MAPPING:
                return "SET_LOGICAL_TO_PHYSICAL_SLOT_MAPPING";
            default:
                return "<unknown request>";
        }
    }

    /**
     * Register a handler to get SIM slot status changed notifications.
     */
    public void registerForSimSlotStatusChanged(Handler h, int what, Object obj) {
        mSimSlotStatusRegistrant = new Registrant(h, what, obj);
    }

    /**
     * Unregister corresponding to registerForSimSlotStatusChanged().
     */
    public void unregisterForSimSlotStatusChanged(Handler h) {
        if (mSimSlotStatusRegistrant != null && mSimSlotStatusRegistrant.getHandler() == h) {
            mSimSlotStatusRegistrant.clear();
            mSimSlotStatusRegistrant = null;
        }
    }

    static ArrayList<IccSlotStatus> convertHalSlotStatus(
            ArrayList<SimSlotStatus> halSlotStatusList) {
        ArrayList<IccSlotStatus> response = new ArrayList<IccSlotStatus>(halSlotStatusList.size());
        for (SimSlotStatus slotStatus : halSlotStatusList) {
            IccSlotStatus iccSlotStatus = new IccSlotStatus();
            iccSlotStatus.setCardState(slotStatus.cardState);
            iccSlotStatus.setSlotState(slotStatus.slotState);
            iccSlotStatus.logicalSlotIndex = slotStatus.logicalSlotId;
            iccSlotStatus.atr = slotStatus.atr;
            iccSlotStatus.iccid = slotStatus.iccid;
            response.add(iccSlotStatus);
        }
        return response;
    }

    private static void logd(String log) {
        Rlog.d(TAG, log);
    }

    private static void loge(String log) {
        Rlog.e(TAG, log);
    }
}
