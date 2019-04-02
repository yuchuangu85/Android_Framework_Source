/*
 * Copyright 2018 The Android Open Source Project
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

import android.hardware.radio.V1_0.SetupDataCallResult;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkUtils;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.data.DataCallResponse;
import android.telephony.data.DataProfile;
import android.telephony.data.DataService;
import android.telephony.data.DataServiceCallback;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class represents cellular data service which handles telephony data requests and response
 * from the cellular modem.
 */
public class CellularDataService extends DataService {
    private static final String TAG = CellularDataService.class.getSimpleName();

    private static final boolean DBG = false;

    private static final int SETUP_DATA_CALL_COMPLETE               = 1;
    private static final int DEACTIVATE_DATA_ALL_COMPLETE           = 2;
    private static final int SET_INITIAL_ATTACH_APN_COMPLETE        = 3;
    private static final int SET_DATA_PROFILE_COMPLETE              = 4;
    private static final int GET_DATA_CALL_LIST_COMPLETE            = 5;
    private static final int DATA_CALL_LIST_CHANGED                 = 6;

    private class CellularDataServiceProvider extends DataService.DataServiceProvider {

        private final Map<Message, DataServiceCallback> mCallbackMap = new HashMap<>();

        private final Looper mLooper;

        private final Handler mHandler;

        private final Phone mPhone;

        private CellularDataServiceProvider(int slotId) {
            super(slotId);

            mPhone = PhoneFactory.getPhone(getSlotId());

            HandlerThread thread = new HandlerThread(CellularDataService.class.getSimpleName());
            thread.start();
            mLooper = thread.getLooper();
            mHandler = new Handler(mLooper) {
                @Override
                public void handleMessage(Message message) {
                    DataServiceCallback callback = mCallbackMap.remove(message);

                    AsyncResult ar = (AsyncResult) message.obj;
                    switch (message.what) {
                        case SETUP_DATA_CALL_COMPLETE:
                            SetupDataCallResult result = (SetupDataCallResult) ar.result;
                            callback.onSetupDataCallComplete(ar.exception != null
                                    ? DataServiceCallback.RESULT_ERROR_ILLEGAL_STATE
                                    : DataServiceCallback.RESULT_SUCCESS,
                                    convertDataCallResult(result));
                            break;
                        case DEACTIVATE_DATA_ALL_COMPLETE:
                            callback.onDeactivateDataCallComplete(ar.exception != null
                                    ? DataServiceCallback.RESULT_ERROR_ILLEGAL_STATE
                                    : DataServiceCallback.RESULT_SUCCESS);
                            break;
                        case SET_INITIAL_ATTACH_APN_COMPLETE:
                            callback.onSetInitialAttachApnComplete(ar.exception != null
                                    ? DataServiceCallback.RESULT_ERROR_ILLEGAL_STATE
                                    : DataServiceCallback.RESULT_SUCCESS);
                            break;
                        case SET_DATA_PROFILE_COMPLETE:
                            callback.onSetDataProfileComplete(ar.exception != null
                                    ? DataServiceCallback.RESULT_ERROR_ILLEGAL_STATE
                                    : DataServiceCallback.RESULT_SUCCESS);
                            break;
                        case GET_DATA_CALL_LIST_COMPLETE:
                            callback.onGetDataCallListComplete(
                                    ar.exception != null
                                            ? DataServiceCallback.RESULT_ERROR_ILLEGAL_STATE
                                            : DataServiceCallback.RESULT_SUCCESS,
                                    ar.exception != null
                                            ? null
                                            : getDataCallList((List<SetupDataCallResult>) ar.result)
                                    );
                            break;
                        case DATA_CALL_LIST_CHANGED:
                            notifyDataCallListChanged(getDataCallList(
                                    (List<SetupDataCallResult>) ar.result));
                            break;
                        default:
                            loge("Unexpected event: " + message.what);
                            return;
                    }
                }
            };

            if (DBG) log("Register for data call list changed.");
            mPhone.mCi.registerForDataCallListChanged(mHandler, DATA_CALL_LIST_CHANGED, null);
        }

        private List<DataCallResponse> getDataCallList(List<SetupDataCallResult> dcList) {
            List<DataCallResponse> dcResponseList = new ArrayList<>();
            for (SetupDataCallResult dcResult : dcList) {
                dcResponseList.add(convertDataCallResult(dcResult));
            }
            return dcResponseList;
        }

        @Override
        public void setupDataCall(int radioTechnology, DataProfile dataProfile, boolean isRoaming,
                                  boolean allowRoaming, int reason, LinkProperties linkProperties,
                                  DataServiceCallback callback) {
            if (DBG) log("setupDataCall " + getSlotId());

            Message message = null;
            // Only obtain the message when the caller wants a callback. If the caller doesn't care
            // the request completed or results, then no need to pass the message down.
            if (callback != null) {
                message = Message.obtain(mHandler, SETUP_DATA_CALL_COMPLETE);
                mCallbackMap.put(message, callback);
            }

            mPhone.mCi.setupDataCall(radioTechnology, dataProfile, isRoaming, allowRoaming, reason,
                    linkProperties, message);
        }

        @Override
        public void deactivateDataCall(int cid, int reason, DataServiceCallback callback) {
            if (DBG) log("deactivateDataCall " + getSlotId());

            Message message = null;
            // Only obtain the message when the caller wants a callback. If the caller doesn't care
            // the request completed or results, then no need to pass the message down.
            if (callback != null) {
                message = Message.obtain(mHandler, DEACTIVATE_DATA_ALL_COMPLETE);
                mCallbackMap.put(message, callback);
            }

            mPhone.mCi.deactivateDataCall(cid, reason, message);
        }

        @Override
        public void setInitialAttachApn(DataProfile dataProfile, boolean isRoaming,
                                        DataServiceCallback callback) {
            if (DBG) log("setInitialAttachApn " + getSlotId());

            Message message = null;
            // Only obtain the message when the caller wants a callback. If the caller doesn't care
            // the request completed or results, then no need to pass the message down.
            if (callback != null) {
                message = Message.obtain(mHandler, SET_INITIAL_ATTACH_APN_COMPLETE);
                mCallbackMap.put(message, callback);
            }

            mPhone.mCi.setInitialAttachApn(dataProfile, isRoaming, message);
        }

        @Override
        public void setDataProfile(List<DataProfile> dps, boolean isRoaming,
                                   DataServiceCallback callback) {
            if (DBG) log("setDataProfile " + getSlotId());

            Message message = null;
            // Only obtain the message when the caller wants a callback. If the caller doesn't care
            // the request completed or results, then no need to pass the message down.
            if (callback != null) {
                message = Message.obtain(mHandler, SET_DATA_PROFILE_COMPLETE);
                mCallbackMap.put(message, callback);
            }

            mPhone.mCi.setDataProfile(dps.toArray(new DataProfile[dps.size()]), isRoaming, message);
        }

        @Override
        public void getDataCallList(DataServiceCallback callback) {
            if (DBG) log("getDataCallList " + getSlotId());

            Message message = null;
            // Only obtain the message when the caller wants a callback. If the caller doesn't care
            // the request completed or results, then no need to pass the message down.
            if (callback != null) {
                message = Message.obtain(mHandler, GET_DATA_CALL_LIST_COMPLETE);
                mCallbackMap.put(message, callback);
            }
            mPhone.mCi.getDataCallList(message);
        }
    }

    @Override
    public DataServiceProvider createDataServiceProvider(int slotId) {
        log("Cellular data service created for slot " + slotId);
        if (!SubscriptionManager.isValidSlotIndex(slotId)) {
            loge("Tried to cellular data service with invalid slotId " + slotId);
            return null;
        }
        return new CellularDataServiceProvider(slotId);
    }

    /**
     * Convert SetupDataCallResult defined in types.hal into DataCallResponse
     * @param dcResult setup data call result
     * @return converted DataCallResponse object
     */
    @VisibleForTesting
    public DataCallResponse convertDataCallResult(SetupDataCallResult dcResult) {
        if (dcResult == null) return null;

        // Process address
        String[] addresses = null;
        if (!TextUtils.isEmpty(dcResult.addresses)) {
            addresses = dcResult.addresses.split("\\s+");
        }

        List<LinkAddress> laList = new ArrayList<>();
        if (addresses != null) {
            for (String address : addresses) {
                address = address.trim();
                if (address.isEmpty()) continue;

                try {
                    LinkAddress la;
                    // Check if the address contains prefix length. If yes, LinkAddress
                    // can parse that.
                    if (address.split("/").length == 2) {
                        la = new LinkAddress(address);
                    } else {
                        InetAddress ia = NetworkUtils.numericToInetAddress(address);
                        la = new LinkAddress(ia, (ia instanceof Inet4Address) ? 32 : 128);
                    }

                    laList.add(la);
                } catch (IllegalArgumentException e) {
                    loge("Unknown address: " + address + ", exception = " + e);
                }
            }
        }

        // Process dns
        String[] dnses = null;
        if (!TextUtils.isEmpty(dcResult.dnses)) {
            dnses = dcResult.dnses.split("\\s+");
        }

        List<InetAddress> dnsList = new ArrayList<>();
        if (dnses != null) {
            for (String dns : dnses) {
                dns = dns.trim();
                InetAddress ia;
                try {
                    ia = NetworkUtils.numericToInetAddress(dns);
                    dnsList.add(ia);
                } catch (IllegalArgumentException e) {
                    loge("Unknown dns: " + dns + ", exception = " + e);
                }
            }
        }

        // Process gateway
        String[] gateways = null;
        if (!TextUtils.isEmpty(dcResult.gateways)) {
            gateways = dcResult.gateways.split("\\s+");
        }

        List<InetAddress> gatewayList = new ArrayList<>();
        if (gateways != null) {
            for (String gateway : gateways) {
                gateway = gateway.trim();
                InetAddress ia;
                try {
                    ia = NetworkUtils.numericToInetAddress(gateway);
                    gatewayList.add(ia);
                } catch (IllegalArgumentException e) {
                    loge("Unknown gateway: " + gateway + ", exception = " + e);
                }
            }
        }

        return new DataCallResponse(dcResult.status,
                dcResult.suggestedRetryTime,
                dcResult.cid,
                dcResult.active,
                dcResult.type,
                dcResult.ifname,
                laList,
                dnsList,
                gatewayList,
                new ArrayList<>(Arrays.asList(dcResult.pcscf.trim().split("\\s+"))),
                dcResult.mtu
        );
    }

    private void log(String s) {
        Rlog.d(TAG, s);
    }

    private void loge(String s) {
        Rlog.e(TAG, s);
    }
}
