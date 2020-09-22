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

import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.PackageManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.android.internal.telephony.uicc.AdnRecord;
import com.android.internal.telephony.uicc.AdnRecordCache;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.uicc.IccConstants;
import com.android.internal.telephony.uicc.IccFileHandler;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.telephony.Rlog;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SimPhoneBookInterfaceManager to provide an inter-process communication to
 * access ADN-like SIM records.
 */
public class IccPhoneBookInterfaceManager {
    static final String LOG_TAG = "IccPhoneBookIM";
    @UnsupportedAppUsage
    protected static final boolean DBG = true;

    @UnsupportedAppUsage
    protected Phone mPhone;
    @UnsupportedAppUsage
    protected AdnRecordCache mAdnCache;

    protected static final int EVENT_GET_SIZE_DONE = 1;
    protected static final int EVENT_LOAD_DONE = 2;
    protected static final int EVENT_UPDATE_DONE = 3;

    private static final class Request {
        AtomicBoolean mStatus = new AtomicBoolean(false);
        Object mResult = null;
    }

    @UnsupportedAppUsage
    protected Handler mBaseHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            Request request = (Request) ar.userObj;

            switch (msg.what) {
                case EVENT_GET_SIZE_DONE:
                    int[] recordSize = null;
                    if (ar.exception == null) {
                        recordSize = (int[]) ar.result;
                        // recordSize[0]  is the record length
                        // recordSize[1]  is the total length of the EF file
                        // recordSize[2]  is the number of records in the EF file
                        logd("GET_RECORD_SIZE Size " + recordSize[0]
                                + " total " + recordSize[1]
                                + " #record " + recordSize[2]);
                    } else {
                        loge("EVENT_GET_SIZE_DONE: failed; ex=" + ar.exception);
                    }
                    notifyPending(request, recordSize);
                    break;
                case EVENT_UPDATE_DONE:
                    boolean success = (ar.exception == null);
                    if (!success) {
                        loge("EVENT_UPDATE_DONE - failed; ex=" + ar.exception);
                    }
                    notifyPending(request, success);
                    break;
                case EVENT_LOAD_DONE:
                    List<AdnRecord> records = null;
                    if (ar.exception == null) {
                        records = (List<AdnRecord>) ar.result;
                    } else {
                        loge("EVENT_LOAD_DONE: Cannot load ADN records; ex="
                                + ar.exception);
                    }
                    notifyPending(request, records);
                    break;
            }
        }

        private void notifyPending(Request request, Object result) {
            if (request != null) {
                synchronized (request) {
                    request.mResult = result;
                    request.mStatus.set(true);
                    request.notifyAll();
                }
            }
        }
    };

    public IccPhoneBookInterfaceManager(Phone phone) {
        this.mPhone = phone;
        IccRecords r = phone.getIccRecords();
        if (r != null) {
            mAdnCache = r.getAdnCache();
        }
    }

    public void dispose() {
    }

    public void updateIccRecords(IccRecords iccRecords) {
        if (iccRecords != null) {
            mAdnCache = iccRecords.getAdnCache();
        } else {
            mAdnCache = null;
        }
    }

    @UnsupportedAppUsage
    protected void logd(String msg) {
        Rlog.d(LOG_TAG, "[IccPbInterfaceManager] " + msg);
    }

    @UnsupportedAppUsage
    protected void loge(String msg) {
        Rlog.e(LOG_TAG, "[IccPbInterfaceManager] " + msg);
    }

    /**
     * Replace oldAdn with newAdn in ADN-like record in EF
     *
     * getAdnRecordsInEf must be called at least once before this function,
     * otherwise an error will be returned. Currently the email field
     * if set in the ADN record is ignored.
     * throws SecurityException if no WRITE_CONTACTS permission
     *
     * @param efid must be one among EF_ADN, EF_FDN, and EF_SDN
     * @param oldTag adn tag to be replaced
     * @param oldPhoneNumber adn number to be replaced
     *        Set both oldTag and oldPhoneNubmer to "" means to replace an
     *        empty record, aka, insert new record
     * @param newTag adn tag to be stored
     * @param newPhoneNumber adn number ot be stored
     *        Set both newTag and newPhoneNubmer to "" means to replace the old
     *        record with empty one, aka, delete old record
     * @param pin2 required to update EF_FDN, otherwise must be null
     * @return true for success
     */
    public boolean
    updateAdnRecordsInEfBySearch (int efid,
            String oldTag, String oldPhoneNumber,
            String newTag, String newPhoneNumber, String pin2) {


        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.WRITE_CONTACTS permission");
        }


        if (DBG) logd("updateAdnRecordsInEfBySearch: efid=0x" +
                Integer.toHexString(efid).toUpperCase() + " ("+ Rlog.pii(LOG_TAG, oldTag) + "," +
                Rlog.pii(LOG_TAG, oldPhoneNumber) + ")" + "==>" + " ("+ Rlog.pii(LOG_TAG, newTag) +
                "," + Rlog.pii(LOG_TAG, newPhoneNumber) + ")"+ " pin2=" + Rlog.pii(LOG_TAG, pin2));

        efid = updateEfForIccType(efid);

        checkThread();
        Request updateRequest = new Request();
        synchronized (updateRequest) {
            Message response = mBaseHandler.obtainMessage(EVENT_UPDATE_DONE, updateRequest);
            AdnRecord oldAdn = new AdnRecord(oldTag, oldPhoneNumber);
            AdnRecord newAdn = new AdnRecord(newTag, newPhoneNumber);
            if (mAdnCache != null) {
                mAdnCache.updateAdnBySearch(efid, oldAdn, newAdn, pin2, response);
                waitForResult(updateRequest);
                return (boolean) updateRequest.mResult;
            } else {
                loge("Failure while trying to update by search due to uninitialised adncache");
                return false;
            }
        }
    }

    /**
     * Update an ADN-like EF record by record index
     *
     * This is useful for iteration the whole ADN file, such as write the whole
     * phone book or erase/format the whole phonebook. Currently the email field
     * if set in the ADN record is ignored.
     * throws SecurityException if no WRITE_CONTACTS permission
     *
     * @param efid must be one among EF_ADN, EF_FDN, and EF_SDN
     * @param newTag adn tag to be stored
     * @param newPhoneNumber adn number to be stored
     *        Set both newTag and newPhoneNubmer to "" means to replace the old
     *        record with empty one, aka, delete old record
     * @param index is 1-based adn record index to be updated
     * @param pin2 required to update EF_FDN, otherwise must be null
     * @return true for success
     */
    public boolean
    updateAdnRecordsInEfByIndex(int efid, String newTag,
            String newPhoneNumber, int index, String pin2) {

        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.WRITE_CONTACTS permission");
        }

        if (DBG) logd("updateAdnRecordsInEfByIndex: efid=0x" +
                Integer.toHexString(efid).toUpperCase() + " Index=" + index + " ==> " + "(" +
                Rlog.pii(LOG_TAG, newTag) + "," + Rlog.pii(LOG_TAG, newPhoneNumber) + ")" +
                " pin2=" + Rlog.pii(LOG_TAG, pin2));


        checkThread();
        Request updateRequest = new Request();
        synchronized (updateRequest) {
            Message response = mBaseHandler.obtainMessage(EVENT_UPDATE_DONE, updateRequest);
            AdnRecord newAdn = new AdnRecord(newTag, newPhoneNumber);
            if (mAdnCache != null) {
                mAdnCache.updateAdnByIndex(efid, newAdn, index, pin2, response);
                waitForResult(updateRequest);
                return (boolean) updateRequest.mResult;
            } else {
                loge("Failure while trying to update by index due to uninitialised adncache");
                return false;
            }
        }
    }

    /**
     * Get the capacity of records in efid
     *
     * @param efid the EF id of a ADN-like ICC
     * @return  int[3] array
     *            recordSizes[0]  is the single record length
     *            recordSizes[1]  is the total length of the EF file
     *            recordSizes[2]  is the number of records in the EF file
     */
    public int[] getAdnRecordsSize(int efid) {
        if (DBG) logd("getAdnRecordsSize: efid=" + efid);
        checkThread();
        Request getSizeRequest = new Request();
        synchronized (getSizeRequest) {
            //Using mBaseHandler, no difference in EVENT_GET_SIZE_DONE handling
            Message response = mBaseHandler.obtainMessage(EVENT_GET_SIZE_DONE, getSizeRequest);
            IccFileHandler fh = mPhone.getIccFileHandler();
            if (fh != null) {
                fh.getEFLinearRecordSize(efid, response);
                waitForResult(getSizeRequest);
            }
        }

        return getSizeRequest.mResult == null ? new int[3] : (int[]) getSizeRequest.mResult;
    }


    /**
     * Loads the AdnRecords in efid and returns them as a
     * List of AdnRecords
     *
     * throws SecurityException if no READ_CONTACTS permission
     *
     * @param efid the EF id of a ADN-like ICC
     * @return List of AdnRecord
     */
    public List<AdnRecord> getAdnRecordsInEf(int efid) {

        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.READ_CONTACTS permission");
        }

        efid = updateEfForIccType(efid);
        if (DBG) logd("getAdnRecordsInEF: efid=0x" + Integer.toHexString(efid).toUpperCase());

        checkThread();
        Request loadRequest = new Request();
        synchronized (loadRequest) {
            Message response = mBaseHandler.obtainMessage(EVENT_LOAD_DONE, loadRequest);
            if (mAdnCache != null) {
                mAdnCache.requestLoadAllAdnLike(efid, mAdnCache.extensionEfForEf(efid), response);
                waitForResult(loadRequest);
                return (List<AdnRecord>) loadRequest.mResult;
            } else {
                loge("Failure while trying to load from SIM due to uninitialised adncache");
                return null;
            }
        }
    }

    @UnsupportedAppUsage
    protected void checkThread() {
        // Make sure this isn't the UI thread, since it will block
        if (mBaseHandler.getLooper().equals(Looper.myLooper())) {
            loge("query() called on the main UI thread!");
            throw new IllegalStateException(
                    "You cannot call query on this provder from the main UI thread.");
        }
    }

    protected void waitForResult(Request request) {
        synchronized (request) {
            while (!request.mStatus.get()) {
                try {
                    request.wait();
                } catch (InterruptedException e) {
                    logd("interrupted while trying to update by search");
                }
            }
        }
    }

    @UnsupportedAppUsage
    private int updateEfForIccType(int efid) {
        // Check if we are trying to read ADN records
        if (efid == IccConstants.EF_ADN) {
            if (mPhone.getCurrentUiccAppType() == AppType.APPTYPE_USIM) {
                return IccConstants.EF_PBR;
            }
        }
        return efid;
    }
}

