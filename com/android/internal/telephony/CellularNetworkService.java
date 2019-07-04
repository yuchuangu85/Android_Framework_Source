/*
 * Copyright 2017 The Android Open Source Project
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

import android.annotation.CallSuper;
import android.hardware.radio.V1_0.CellInfoType;
import android.hardware.radio.V1_0.RegState;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.telephony.AccessNetworkConstants.TransportType;
import android.telephony.CellIdentity;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityTdscdma;
import android.telephony.CellIdentityWcdma;
import android.telephony.NetworkRegistrationState;
import android.telephony.NetworkService;
import android.telephony.NetworkServiceCallback;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of network services for Cellular. It's a service that handles network requests
 * for Cellular. It passes the requests to inner CellularNetworkServiceProvider which has a
 * handler thread for each slot.
 */
public class CellularNetworkService extends NetworkService {
    private static final boolean DBG = false;

    private static final String TAG = CellularNetworkService.class.getSimpleName();

    private static final int GET_CS_REGISTRATION_STATE_DONE = 1;
    private static final int GET_PS_REGISTRATION_STATE_DONE = 2;
    private static final int NETWORK_REGISTRATION_STATE_CHANGED = 3;

    private class CellularNetworkServiceProvider extends NetworkServiceProvider {

        private final ConcurrentHashMap<Message, NetworkServiceCallback> mCallbackMap =
                new ConcurrentHashMap<>();

        private final Looper mLooper;

        private final HandlerThread mHandlerThread;

        private final Handler mHandler;

        private final Phone mPhone;

        CellularNetworkServiceProvider(int slotId) {
            super(slotId);

            mPhone = PhoneFactory.getPhone(getSlotId());

            mHandlerThread = new HandlerThread(CellularNetworkService.class.getSimpleName());
            mHandlerThread.start();
            mLooper = mHandlerThread.getLooper();
            mHandler = new Handler(mLooper) {
                @Override
                public void handleMessage(Message message) {
                    NetworkServiceCallback callback = mCallbackMap.remove(message);

                    AsyncResult ar;
                    switch (message.what) {
                        case GET_CS_REGISTRATION_STATE_DONE:
                        case GET_PS_REGISTRATION_STATE_DONE:
                            if (callback == null) return;
                            ar = (AsyncResult) message.obj;
                            int domain = (message.what == GET_CS_REGISTRATION_STATE_DONE)
                                    ? NetworkRegistrationState.DOMAIN_CS
                                    : NetworkRegistrationState.DOMAIN_PS;
                            NetworkRegistrationState netState =
                                    getRegistrationStateFromResult(ar.result, domain);

                            int resultCode;
                            if (ar.exception != null || netState == null) {
                                resultCode = NetworkServiceCallback.RESULT_ERROR_FAILED;
                            } else {
                                resultCode = NetworkServiceCallback.RESULT_SUCCESS;
                            }

                            try {
                                if (DBG) {
                                    log("Calling callback.onGetNetworkRegistrationStateComplete."
                                            + "resultCode = " + resultCode
                                            + ", netState = " + netState);
                                }
                                callback.onGetNetworkRegistrationStateComplete(
                                         resultCode, netState);
                            } catch (Exception e) {
                                loge("Exception: " + e);
                            }
                            break;
                        case NETWORK_REGISTRATION_STATE_CHANGED:
                            notifyNetworkRegistrationStateChanged();
                            break;
                        default:
                            return;
                    }
                }
            };

            mPhone.mCi.registerForNetworkStateChanged(
                    mHandler, NETWORK_REGISTRATION_STATE_CHANGED, null);
        }

        private int getRegStateFromHalRegState(int halRegState) {
            switch (halRegState) {
                case RegState.NOT_REG_MT_NOT_SEARCHING_OP:
                case RegState.NOT_REG_MT_NOT_SEARCHING_OP_EM:
                    return NetworkRegistrationState.REG_STATE_NOT_REG_NOT_SEARCHING;
                case RegState.REG_HOME:
                    return NetworkRegistrationState.REG_STATE_HOME;
                case RegState.NOT_REG_MT_SEARCHING_OP:
                case RegState.NOT_REG_MT_SEARCHING_OP_EM:
                    return NetworkRegistrationState.REG_STATE_NOT_REG_SEARCHING;
                case RegState.REG_DENIED:
                case RegState.REG_DENIED_EM:
                    return NetworkRegistrationState.REG_STATE_DENIED;
                case RegState.UNKNOWN:
                case RegState.UNKNOWN_EM:
                    return NetworkRegistrationState.REG_STATE_UNKNOWN;
                case RegState.REG_ROAMING:
                    return NetworkRegistrationState.REG_STATE_ROAMING;
                default:
                    return NetworkRegistrationState.REG_STATE_NOT_REG_NOT_SEARCHING;
            }
        }

        private boolean isEmergencyOnly(int halRegState) {
            switch (halRegState) {
                case RegState.NOT_REG_MT_NOT_SEARCHING_OP_EM:
                case RegState.NOT_REG_MT_SEARCHING_OP_EM:
                case RegState.REG_DENIED_EM:
                case RegState.UNKNOWN_EM:
                    return true;
                case RegState.NOT_REG_MT_NOT_SEARCHING_OP:
                case RegState.REG_HOME:
                case RegState.NOT_REG_MT_SEARCHING_OP:
                case RegState.REG_DENIED:
                case RegState.UNKNOWN:
                case RegState.REG_ROAMING:
                default:
                    return false;
            }
        }

        private int[] getAvailableServices(int regState, int domain, boolean emergencyOnly) {
            int[] availableServices = null;

            // In emergency only states, only SERVICE_TYPE_EMERGENCY is available.
            // Otherwise, certain services are available only if it's registered on home or roaming
            // network.
            if (emergencyOnly) {
                availableServices = new int[] {NetworkRegistrationState.SERVICE_TYPE_EMERGENCY};
            } else if (regState == NetworkRegistrationState.REG_STATE_ROAMING
                    || regState == NetworkRegistrationState.REG_STATE_HOME) {
                if (domain == NetworkRegistrationState.DOMAIN_PS) {
                    availableServices = new int[] {NetworkRegistrationState.SERVICE_TYPE_DATA};
                } else if (domain == NetworkRegistrationState.DOMAIN_CS) {
                    availableServices = new int[] {
                            NetworkRegistrationState.SERVICE_TYPE_VOICE,
                            NetworkRegistrationState.SERVICE_TYPE_SMS,
                            NetworkRegistrationState.SERVICE_TYPE_VIDEO
                    };
                }
            }

            return availableServices;
        }

        private int getAccessNetworkTechnologyFromRat(int rilRat) {
            return ServiceState.rilRadioTechnologyToNetworkType(rilRat);
        }

        private NetworkRegistrationState getRegistrationStateFromResult(Object result, int domain) {
            if (result == null) {
                return null;
            }

            // TODO: unify when voiceRegStateResult and DataRegStateResult are unified.
            if (domain == NetworkRegistrationState.DOMAIN_CS) {
                return createRegistrationStateFromVoiceRegState(result);
            } else if (domain == NetworkRegistrationState.DOMAIN_PS) {
                return createRegistrationStateFromDataRegState(result);
            } else {
                return null;
            }
        }

        private NetworkRegistrationState createRegistrationStateFromVoiceRegState(Object result) {
            int transportType = TransportType.WWAN;
            int domain = NetworkRegistrationState.DOMAIN_CS;

            if (result instanceof android.hardware.radio.V1_0.VoiceRegStateResult) {
                android.hardware.radio.V1_0.VoiceRegStateResult voiceRegState =
                        (android.hardware.radio.V1_0.VoiceRegStateResult) result;
                int regState = getRegStateFromHalRegState(voiceRegState.regState);
                int accessNetworkTechnology = getAccessNetworkTechnologyFromRat(voiceRegState.rat);
                int reasonForDenial = voiceRegState.reasonForDenial;
                boolean emergencyOnly = isEmergencyOnly(voiceRegState.regState);
                boolean cssSupported = voiceRegState.cssSupported;
                int roamingIndicator = voiceRegState.roamingIndicator;
                int systemIsInPrl = voiceRegState.systemIsInPrl;
                int defaultRoamingIndicator = voiceRegState.defaultRoamingIndicator;
                int[] availableServices = getAvailableServices(
                        regState, domain, emergencyOnly);
                CellIdentity cellIdentity =
                        convertHalCellIdentityToCellIdentity(voiceRegState.cellIdentity);

                return new NetworkRegistrationState(transportType, domain, regState,
                        accessNetworkTechnology, reasonForDenial, emergencyOnly, availableServices,
                        cellIdentity, cssSupported, roamingIndicator, systemIsInPrl,
                        defaultRoamingIndicator);
            } else if (result instanceof android.hardware.radio.V1_2.VoiceRegStateResult) {
                android.hardware.radio.V1_2.VoiceRegStateResult voiceRegState =
                        (android.hardware.radio.V1_2.VoiceRegStateResult) result;
                int regState = getRegStateFromHalRegState(voiceRegState.regState);
                int accessNetworkTechnology = getAccessNetworkTechnologyFromRat(voiceRegState.rat);
                int reasonForDenial = voiceRegState.reasonForDenial;
                boolean emergencyOnly = isEmergencyOnly(voiceRegState.regState);
                boolean cssSupported = voiceRegState.cssSupported;
                int roamingIndicator = voiceRegState.roamingIndicator;
                int systemIsInPrl = voiceRegState.systemIsInPrl;
                int defaultRoamingIndicator = voiceRegState.defaultRoamingIndicator;
                int[] availableServices = getAvailableServices(
                        regState, domain, emergencyOnly);
                CellIdentity cellIdentity =
                        convertHalCellIdentityToCellIdentity(voiceRegState.cellIdentity);

                return new NetworkRegistrationState(transportType, domain, regState,
                        accessNetworkTechnology, reasonForDenial, emergencyOnly, availableServices,
                        cellIdentity, cssSupported, roamingIndicator, systemIsInPrl,
                        defaultRoamingIndicator);
            }

            return null;
        }

        private NetworkRegistrationState createRegistrationStateFromDataRegState(Object result) {
            int transportType = TransportType.WWAN;
            int domain = NetworkRegistrationState.DOMAIN_PS;

            if (result instanceof android.hardware.radio.V1_0.DataRegStateResult) {
                android.hardware.radio.V1_0.DataRegStateResult dataRegState =
                        (android.hardware.radio.V1_0.DataRegStateResult) result;
                int regState = getRegStateFromHalRegState(dataRegState.regState);
                int accessNetworkTechnology = getAccessNetworkTechnologyFromRat(dataRegState.rat);
                int reasonForDenial = dataRegState.reasonDataDenied;
                boolean emergencyOnly = isEmergencyOnly(dataRegState.regState);
                int maxDataCalls = dataRegState.maxDataCalls;
                int[] availableServices = getAvailableServices(regState, domain, emergencyOnly);
                CellIdentity cellIdentity =
                        convertHalCellIdentityToCellIdentity(dataRegState.cellIdentity);

                return new NetworkRegistrationState(transportType, domain, regState,
                        accessNetworkTechnology, reasonForDenial, emergencyOnly, availableServices,
                        cellIdentity, maxDataCalls);
            } else if (result instanceof android.hardware.radio.V1_2.DataRegStateResult) {
                android.hardware.radio.V1_2.DataRegStateResult dataRegState =
                        (android.hardware.radio.V1_2.DataRegStateResult) result;
                int regState = getRegStateFromHalRegState(dataRegState.regState);
                int accessNetworkTechnology = getAccessNetworkTechnologyFromRat(dataRegState.rat);
                int reasonForDenial = dataRegState.reasonDataDenied;
                boolean emergencyOnly = isEmergencyOnly(dataRegState.regState);
                int maxDataCalls = dataRegState.maxDataCalls;
                int[] availableServices = getAvailableServices(regState, domain, emergencyOnly);
                CellIdentity cellIdentity =
                        convertHalCellIdentityToCellIdentity(dataRegState.cellIdentity);

                return new NetworkRegistrationState(transportType, domain, regState,
                        accessNetworkTechnology, reasonForDenial, emergencyOnly, availableServices,
                        cellIdentity, maxDataCalls);
            }

            return null;
        }

        private CellIdentity convertHalCellIdentityToCellIdentity(
                android.hardware.radio.V1_0.CellIdentity cellIdentity) {
            if (cellIdentity == null) {
                return null;
            }

            CellIdentity result = null;
            switch(cellIdentity.cellInfoType) {
                case CellInfoType.GSM: {
                    if (cellIdentity.cellIdentityGsm.size() == 1) {
                        android.hardware.radio.V1_0.CellIdentityGsm cellIdentityGsm =
                                cellIdentity.cellIdentityGsm.get(0);
                        result = new CellIdentityGsm(cellIdentityGsm.lac, cellIdentityGsm.cid,
                                cellIdentityGsm.arfcn, cellIdentityGsm.bsic, cellIdentityGsm.mcc,
                                cellIdentityGsm.mnc, null, null);
                    }
                    break;
                }
                case CellInfoType.WCDMA: {
                    if (cellIdentity.cellIdentityWcdma.size() == 1) {
                        android.hardware.radio.V1_0.CellIdentityWcdma cellIdentityWcdma =
                                cellIdentity.cellIdentityWcdma.get(0);
                        result = new CellIdentityWcdma(cellIdentityWcdma.lac, cellIdentityWcdma.cid,
                                cellIdentityWcdma.psc, cellIdentityWcdma.uarfcn,
                                cellIdentityWcdma.mcc, cellIdentityWcdma.mnc, null, null);
                    }
                    break;
                }
                case CellInfoType.TD_SCDMA: {
                    if (cellIdentity.cellIdentityTdscdma.size() == 1) {
                        android.hardware.radio.V1_0.CellIdentityTdscdma cellIdentityTdscdma =
                                cellIdentity.cellIdentityTdscdma.get(0);
                        result = new  CellIdentityTdscdma(cellIdentityTdscdma.mcc,
                                cellIdentityTdscdma.mnc, cellIdentityTdscdma.lac,
                                cellIdentityTdscdma.cid, cellIdentityTdscdma.cpid);
                    }
                    break;
                }
                case CellInfoType.LTE: {
                    if (cellIdentity.cellIdentityLte.size() == 1) {
                        android.hardware.radio.V1_0.CellIdentityLte cellIdentityLte =
                                cellIdentity.cellIdentityLte.get(0);

                        result = new CellIdentityLte(cellIdentityLte.ci, cellIdentityLte.pci,
                                cellIdentityLte.tac, cellIdentityLte.earfcn, Integer.MAX_VALUE,
                                cellIdentityLte.mcc, cellIdentityLte.mnc, null, null);
                    }
                    break;
                }
                case CellInfoType.CDMA: {
                    if (cellIdentity.cellIdentityCdma.size() == 1) {
                        android.hardware.radio.V1_0.CellIdentityCdma cellIdentityCdma =
                                cellIdentity.cellIdentityCdma.get(0);

                        result = new CellIdentityCdma(cellIdentityCdma.networkId,
                                cellIdentityCdma.systemId, cellIdentityCdma.baseStationId,
                                cellIdentityCdma.longitude, cellIdentityCdma.latitude);
                    }
                    break;
                }
                case CellInfoType.NONE:
                default:
                    break;
            }

            return result;
        }

        private CellIdentity convertHalCellIdentityToCellIdentity(
                android.hardware.radio.V1_2.CellIdentity cellIdentity) {
            if (cellIdentity == null) {
                return null;
            }

            CellIdentity result = null;
            switch(cellIdentity.cellInfoType) {
                case CellInfoType.GSM: {
                    if (cellIdentity.cellIdentityGsm.size() == 1) {
                        android.hardware.radio.V1_2.CellIdentityGsm cellIdentityGsm =
                                cellIdentity.cellIdentityGsm.get(0);

                        result = new CellIdentityGsm(
                                cellIdentityGsm.base.lac,
                                cellIdentityGsm.base.cid,
                                cellIdentityGsm.base.arfcn,
                                cellIdentityGsm.base.bsic,
                                cellIdentityGsm.base.mcc,
                                cellIdentityGsm.base.mnc,
                                cellIdentityGsm.operatorNames.alphaLong,
                                cellIdentityGsm.operatorNames.alphaShort);
                    }
                    break;
                }
                case CellInfoType.WCDMA: {
                    if (cellIdentity.cellIdentityWcdma.size() == 1) {
                        android.hardware.radio.V1_2.CellIdentityWcdma cellIdentityWcdma =
                                cellIdentity.cellIdentityWcdma.get(0);

                        result = new CellIdentityWcdma(
                                cellIdentityWcdma.base.lac,
                                cellIdentityWcdma.base.cid,
                                cellIdentityWcdma.base.psc,
                                cellIdentityWcdma.base.uarfcn,
                                cellIdentityWcdma.base.mcc,
                                cellIdentityWcdma.base.mnc,
                                cellIdentityWcdma.operatorNames.alphaLong,
                                cellIdentityWcdma.operatorNames.alphaShort);
                    }
                    break;
                }
                case CellInfoType.TD_SCDMA: {
                    if (cellIdentity.cellIdentityTdscdma.size() == 1) {
                        android.hardware.radio.V1_2.CellIdentityTdscdma cellIdentityTdscdma =
                                cellIdentity.cellIdentityTdscdma.get(0);

                        result = new  CellIdentityTdscdma(
                                cellIdentityTdscdma.base.mcc,
                                cellIdentityTdscdma.base.mnc,
                                cellIdentityTdscdma.base.lac,
                                cellIdentityTdscdma.base.cid,
                                cellIdentityTdscdma.base.cpid,
                                cellIdentityTdscdma.operatorNames.alphaLong,
                                cellIdentityTdscdma.operatorNames.alphaShort);
                    }
                    break;
                }
                case CellInfoType.LTE: {
                    if (cellIdentity.cellIdentityLte.size() == 1) {
                        android.hardware.radio.V1_2.CellIdentityLte cellIdentityLte =
                                cellIdentity.cellIdentityLte.get(0);

                        result = new CellIdentityLte(
                                cellIdentityLte.base.ci,
                                cellIdentityLte.base.pci,
                                cellIdentityLte.base.tac,
                                cellIdentityLte.base.earfcn,
                                cellIdentityLte.bandwidth,
                                cellIdentityLte.base.mcc,
                                cellIdentityLte.base.mnc,
                                cellIdentityLte.operatorNames.alphaLong,
                                cellIdentityLte.operatorNames.alphaShort);
                    }
                    break;
                }
                case CellInfoType.CDMA: {
                    if (cellIdentity.cellIdentityCdma.size() == 1) {
                        android.hardware.radio.V1_2.CellIdentityCdma cellIdentityCdma =
                                cellIdentity.cellIdentityCdma.get(0);

                        result = new CellIdentityCdma(
                                cellIdentityCdma.base.networkId,
                                cellIdentityCdma.base.systemId,
                                cellIdentityCdma.base.baseStationId,
                                cellIdentityCdma.base.longitude,
                                cellIdentityCdma.base.latitude,
                                cellIdentityCdma.operatorNames.alphaLong,
                                cellIdentityCdma.operatorNames.alphaShort);
                    }
                    break;
                }
                case CellInfoType.NONE:
                default:
                    break;
            }

            return result;
        }

        @Override
        public void getNetworkRegistrationState(int domain, NetworkServiceCallback callback) {
            if (DBG) log("getNetworkRegistrationState for domain " + domain);
            Message message = null;

            if (domain == NetworkRegistrationState.DOMAIN_CS) {
                message = Message.obtain(mHandler, GET_CS_REGISTRATION_STATE_DONE);
                mCallbackMap.put(message, callback);
                mPhone.mCi.getVoiceRegistrationState(message);
            } else if (domain == NetworkRegistrationState.DOMAIN_PS) {
                message = Message.obtain(mHandler, GET_PS_REGISTRATION_STATE_DONE);
                mCallbackMap.put(message, callback);
                mPhone.mCi.getDataRegistrationState(message);
            } else {
                loge("getNetworkRegistrationState invalid domain " + domain);
                callback.onGetNetworkRegistrationStateComplete(
                        NetworkServiceCallback.RESULT_ERROR_INVALID_ARG, null);
            }
        }

        @CallSuper
        protected void onDestroy() {
            super.onDestroy();

            mCallbackMap.clear();
            mHandlerThread.quit();
            mPhone.mCi.unregisterForNetworkStateChanged(mHandler);
        }
    }

    @Override
    protected NetworkServiceProvider createNetworkServiceProvider(int slotId) {
        if (DBG) log("Cellular network service created for slot " + slotId);
        if (!SubscriptionManager.isValidSlotIndex(slotId)) {
            loge("Tried to Cellular network service with invalid slotId " + slotId);
            return null;
        }
        return new CellularNetworkServiceProvider(slotId);
    }

    private void log(String s) {
        Rlog.d(TAG, s);
    }

    private void loge(String s) {
        Rlog.e(TAG, s);
    }
}
