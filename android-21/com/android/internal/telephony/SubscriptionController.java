/*
* Copyright (C) 2011-2014 MediaTek Inc.
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

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.telephony.Rlog;
import android.util.Log;
import android.net.Uri;
import android.database.Cursor;
import android.content.Intent;
import android.provider.BaseColumns;
import android.provider.Settings;
import android.content.ContentResolver;
import android.content.ContentValues;

import com.android.internal.telephony.ISub;
import com.android.internal.telephony.uicc.SpnOverride;

import android.telephony.SubscriptionManager;
import android.telephony.SubInfoRecord;
import android.telephony.TelephonyManager;
import android.text.format.Time;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Set;

/**
 * SubscriptionController to provide an inter-process communication to
 * access Sms in Icc.
 *
 * Any setters which take subId, slotId or phoneId as a parameter will throw an exception if the
 * parameter equals the corresponding INVALID_XXX_ID or DEFAULT_XXX_ID.
 *
 * All getters will lookup the corresponding default if the parameter is DEFAULT_XXX_ID. Ie calling
 * getPhoneId(DEFAULT_SUB_ID) will return the same as getPhoneId(getDefaultSubId()).
 *
 * Finally, any getters which perform the mapping between subscriptions, slots and phones will
 * return the corresponding INVALID_XXX_ID if the parameter is INVALID_XXX_ID. All other getters
 * will fail and return the appropriate error value. Ie calling getSlotId(INVALID_SUB_ID) will
 * return INVALID_SLOT_ID and calling getSubInfoForSubscriber(INVALID_SUB_ID) will return null.
 *
 */
public class SubscriptionController extends ISub.Stub {
    static final String LOG_TAG = "SubController";
    static final boolean DBG = true;
    static final boolean VDBG = false;
    static final int MAX_LOCAL_LOG_LINES = 500; // TODO: Reduce to 100 when 17678050 is fixed
    private ScLocalLog mLocalLog = new ScLocalLog(MAX_LOCAL_LOG_LINES);

    /**
     * Copied from android.util.LocalLog with flush() adding flush and line number
     * TODO: Update LocalLog
     */
    static class ScLocalLog {

        private LinkedList<String> mLog;
        private int mMaxLines;
        private Time mNow;

        public ScLocalLog(int maxLines) {
            mLog = new LinkedList<String>();
            mMaxLines = maxLines;
            mNow = new Time();
        }

        public synchronized void log(String msg) {
            if (mMaxLines > 0) {
                int pid = android.os.Process.myPid();
                int tid = android.os.Process.myTid();
                mNow.setToNow();
                mLog.add(mNow.format("%m-%d %H:%M:%S") + " pid=" + pid + " tid=" + tid + " " + msg);
                while (mLog.size() > mMaxLines) mLog.remove();
            }
        }

        public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            final int LOOPS_PER_FLUSH = 10; // Flush every N loops.
            Iterator<String> itr = mLog.listIterator(0);
            int i = 0;
            while (itr.hasNext()) {
                pw.println(Integer.toString(i++) + ": " + itr.next());
                // Flush periodically so we don't drop lines
                if ((i % LOOPS_PER_FLUSH) == 0) pw.flush();
            }
        }
    }

    protected final Object mLock = new Object();
    protected boolean mSuccess;

    /** The singleton instance. */
    private static SubscriptionController sInstance = null;
    protected static PhoneProxy[] sProxyPhones;
    protected Context mContext;
    protected CallManager mCM;

    private static final int RES_TYPE_BACKGROUND_DARK = 0;
    private static final int RES_TYPE_BACKGROUND_LIGHT = 1;

    private static final int[] sSimBackgroundDarkRes = setSimResource(RES_TYPE_BACKGROUND_DARK);
    private static final int[] sSimBackgroundLightRes = setSimResource(RES_TYPE_BACKGROUND_LIGHT);

    //FIXME this does not allow for multiple subs in a slot
    private static HashMap<Integer, Long> mSimInfo = new HashMap<Integer, Long>();
    private static long mDefaultVoiceSubId = SubscriptionManager.INVALID_SUB_ID;
    private static int mDefaultPhoneId = SubscriptionManager.DEFAULT_PHONE_ID;

    private static final int EVENT_WRITE_MSISDN_DONE = 1;

    protected Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar;

            switch (msg.what) {
                case EVENT_WRITE_MSISDN_DONE:
                    ar = (AsyncResult) msg.obj;
                    synchronized (mLock) {
                        mSuccess = (ar.exception == null);
                        logd("EVENT_WRITE_MSISDN_DONE, mSuccess = "+mSuccess);
                        mLock.notifyAll();
                    }
                    break;
            }
        }
    };


    public static SubscriptionController init(Phone phone) {
        synchronized (SubscriptionController.class) {
            if (sInstance == null) {
                sInstance = new SubscriptionController(phone);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    public static SubscriptionController init(Context c, CommandsInterface[] ci) {
        synchronized (SubscriptionController.class) {
            if (sInstance == null) {
                sInstance = new SubscriptionController(c);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    public static SubscriptionController getInstance() {
        if (sInstance == null)
        {
           Log.wtf(LOG_TAG, "getInstance null");
        }

        return sInstance;
    }

    private SubscriptionController(Context c) {
        mContext = c;
        mCM = CallManager.getInstance();

        if(ServiceManager.getService("isub") == null) {
                ServiceManager.addService("isub", this);
        }

        logdl("[SubscriptionController] init by Context");
    }

    private boolean isSubInfoReady() {
        return mSimInfo.size() > 0;
    }

    private SubscriptionController(Phone phone) {
        mContext = phone.getContext();
        mCM = CallManager.getInstance();

        if(ServiceManager.getService("isub") == null) {
                ServiceManager.addService("isub", this);
        }

        logdl("[SubscriptionController] init by Phone");
    }

    /**
     * Make sure the caller has the READ_PHONE_STATE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceSubscriptionPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.READ_PHONE_STATE,
                "Requires READ_PHONE_STATE");
    }

    /**
     * Broadcast when subinfo settings has chanded
     * @SubId The unique SubInfoRecord index in database
     * @param columnName The column that is updated
     * @param intContent The updated integer value
     * @param stringContent The updated string value
     */
     private void broadcastSimInfoContentChanged(long subId,
            String columnName, int intContent, String stringContent) {

        Intent intent = new Intent(TelephonyIntents.ACTION_SUBINFO_CONTENT_CHANGE);
        intent.putExtra(BaseColumns._ID, subId);
        intent.putExtra(TelephonyIntents.EXTRA_COLUMN_NAME, columnName);
        intent.putExtra(TelephonyIntents.EXTRA_INT_CONTENT, intContent);
        intent.putExtra(TelephonyIntents.EXTRA_STRING_CONTENT, stringContent);
        if (intContent != SubscriptionManager.DEFAULT_INT_VALUE) {
            logd("[broadcastSimInfoContentChanged] subId" + subId
                    + " changed, " + columnName + " -> " +  intContent);
        } else {
            logd("[broadcastSimInfoContentChanged] subId" + subId
                    + " changed, " + columnName + " -> " +  stringContent);
        }
        mContext.sendBroadcast(intent);
    }


    /**
     * New SubInfoRecord instance and fill in detail info
     * @param cursor
     * @return the query result of desired SubInfoRecord
     */
    private SubInfoRecord getSubInfoRecord(Cursor cursor) {
            SubInfoRecord info = new SubInfoRecord();
            info.subId = cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID));
            info.iccId = cursor.getString(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.ICC_ID));
            info.slotId = cursor.getInt(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.SIM_ID));
            info.displayName = cursor.getString(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.DISPLAY_NAME));
            info.nameSource = cursor.getInt(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.NAME_SOURCE));
            info.color = cursor.getInt(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.COLOR));
            info.number = cursor.getString(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.NUMBER));
            info.displayNumberFormat = cursor.getInt(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.DISPLAY_NUMBER_FORMAT));
            info.dataRoaming = cursor.getInt(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.DATA_ROAMING));

            int size = sSimBackgroundDarkRes.length;
            if (info.color >= 0 && info.color < size) {
                info.simIconRes[RES_TYPE_BACKGROUND_DARK] = sSimBackgroundDarkRes[info.color];
                info.simIconRes[RES_TYPE_BACKGROUND_LIGHT] = sSimBackgroundLightRes[info.color];
            }
            info.mcc = cursor.getInt(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.MCC));
            info.mnc = cursor.getInt(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.MNC));

            logd("[getSubInfoRecord] SubId:" + info.subId + " iccid:" + info.iccId + " slotId:" +
                    info.slotId + " displayName:" + info.displayName + " color:" + info.color +
                    " mcc/mnc:" + info.mcc + "/" + info.mnc);

            return info;
    }

    /**
     * Query SubInfoRecord(s) from subinfo database
     * @param selection A filter declaring which rows to return
     * @param queryKey query key content
     * @return Array list of queried result from database
     */
     private List<SubInfoRecord> getSubInfo(String selection, Object queryKey) {
        logd("selection:" + selection + " " + queryKey);
        String[] selectionArgs = null;
        if (queryKey != null) {
            selectionArgs = new String[] {queryKey.toString()};
        }
        ArrayList<SubInfoRecord> subList = null;
        Cursor cursor = mContext.getContentResolver().query(SubscriptionManager.CONTENT_URI,
                null, selection, selectionArgs, null);
        try {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    SubInfoRecord subInfo = getSubInfoRecord(cursor);
                    if (subInfo != null)
                    {
                        if (subList == null)
                        {
                            subList = new ArrayList<SubInfoRecord>();
                        }
                        subList.add(subInfo);
                }
                }
            } else {
                logd("Query fail");
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return subList;
    }



    /**
     * Get the SubInfoRecord according to an index
     * @param subId The unique SubInfoRecord index in database
     * @return SubInfoRecord, maybe null
     */
    @Override
    public SubInfoRecord getSubInfoForSubscriber(long subId) {
        logd("[getSubInfoForSubscriberx]+ subId:" + subId);
        enforceSubscriptionPermission();

        if (subId == SubscriptionManager.DEFAULT_SUB_ID) {
            subId = getDefaultSubId();
        }
        if (!SubscriptionManager.isValidSubId(subId) || !isSubInfoReady()) {
            logd("[getSubInfoForSubscriberx]- invalid subId or not ready");
            return null;
        }
        Cursor cursor = mContext.getContentResolver().query(SubscriptionManager.CONTENT_URI,
                null, BaseColumns._ID + "=?", new String[] {Long.toString(subId)}, null);
        try {
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    logd("[getSubInfoForSubscriberx]- Info detail:");
                    return getSubInfoRecord(cursor);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        logd("[getSubInfoForSubscriber]- null info return");

        return null;
    }

    /**
     * Get the SubInfoRecord according to an IccId
     * @param iccId the IccId of SIM card
     * @return SubInfoRecord, maybe null
     */
    @Override
    public List<SubInfoRecord> getSubInfoUsingIccId(String iccId) {
        logd("[getSubInfoUsingIccId]+ iccId:" + iccId);
        enforceSubscriptionPermission();

        if (iccId == null || !isSubInfoReady()) {
            logd("[getSubInfoUsingIccId]- null iccid or not ready");
            return null;
        }
        Cursor cursor = mContext.getContentResolver().query(SubscriptionManager.CONTENT_URI,
                null, SubscriptionManager.ICC_ID + "=?", new String[] {iccId}, null);
        ArrayList<SubInfoRecord> subList = null;
        try {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    SubInfoRecord subInfo = getSubInfoRecord(cursor);
                    if (subInfo != null)
                    {
                        if (subList == null)
                        {
                            subList = new ArrayList<SubInfoRecord>();
                        }
                        subList.add(subInfo);
                }
                }
            } else {
                logd("Query fail");
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return subList;
    }

    /**
     * Get the SubInfoRecord according to slotId
     * @param slotId the slot which the SIM is inserted
     * @return SubInfoRecord, maybe null
     */
    @Override
    public List<SubInfoRecord> getSubInfoUsingSlotId(int slotId) {
        return getSubInfoUsingSlotIdWithCheck(slotId, true);
    }

    /**
     * Get all the SubInfoRecord(s) in subinfo database
     * @return Array list of all SubInfoRecords in database, include thsoe that were inserted before
     */
    @Override
    public List<SubInfoRecord> getAllSubInfoList() {
        logd("[getAllSubInfoList]+");
        enforceSubscriptionPermission();

        List<SubInfoRecord> subList = null;
        subList = getSubInfo(null, null);
        if (subList != null) {
            logd("[getAllSubInfoList]- " + subList.size() + " infos return");
        } else {
            logd("[getAllSubInfoList]- no info return");
        }

        return subList;
    }

    /**
     * Get the SubInfoRecord(s) of the currently inserted SIM(s)
     * @return Array list of currently inserted SubInfoRecord(s)
     */
    @Override
    public List<SubInfoRecord> getActiveSubInfoList() {
        enforceSubscriptionPermission();
        logdl("[getActiveSubInfoList]+");

        List<SubInfoRecord> subList = null;

        if (!isSubInfoReady()) {
            logdl("[getActiveSubInfoList] Sub Controller not ready");
            return subList;
        }

        subList = getSubInfo(SubscriptionManager.SIM_ID
                + "!=" + SubscriptionManager.INVALID_SLOT_ID, null);
        if (subList != null) {
            logdl("[getActiveSubInfoList]- " + subList.size() + " infos return");
        } else {
            logdl("[getActiveSubInfoList]- no info return");
        }

        return subList;
    }

    /**
     * Get the SUB count of active SUB(s)
     * @return active SIM count
     */
    @Override
    public int getActiveSubInfoCount() {
        logd("[getActiveSubInfoCount]+");
        List<SubInfoRecord> records = getActiveSubInfoList();
        if (records == null) {
            logd("[getActiveSubInfoCount] records null");
            return 0;
        }
        logd("[getActiveSubInfoCount]- count: " + records.size());
        return records.size();
    }

    /**
     * Get the SUB count of all SUB(s) in subinfo database
     * @return all SIM count in database, include what was inserted before
     */
    @Override
    public int getAllSubInfoCount() {
        logd("[getAllSubInfoCount]+");
        enforceSubscriptionPermission();

        Cursor cursor = mContext.getContentResolver().query(SubscriptionManager.CONTENT_URI,
                null, null, null, null);
        try {
            if (cursor != null) {
                int count = cursor.getCount();
                logd("[getAllSubInfoCount]- " + count + " SUB(s) in DB");
                return count;
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        logd("[getAllSubInfoCount]- no SUB in DB");

        return 0;
    }

    /**
     * Add a new SubInfoRecord to subinfo database if needed
     * @param iccId the IccId of the SIM card
     * @param slotId the slot which the SIM is inserted
     * @return the URL of the newly created row or the updated row
     */
    @Override
    public int addSubInfoRecord(String iccId, int slotId) {
        logdl("[addSubInfoRecord]+ iccId:" + iccId + " slotId:" + slotId);
        enforceSubscriptionPermission();

        if (iccId == null) {
            logdl("[addSubInfoRecord]- null iccId");
        }

        long[] subIds = getSubId(slotId);
        if (subIds == null || subIds.length == 0) {
            logdl("[addSubInfoRecord]- getSubId fail");
            return 0;
        }

        String nameToSet;
        SpnOverride mSpnOverride = new SpnOverride();

        String CarrierName = TelephonyManager.getDefault().getSimOperator(subIds[0]);
        logdl("[addSubInfoRecord] CarrierName = " + CarrierName);

        if (mSpnOverride.containsCarrier(CarrierName)) {
            nameToSet = mSpnOverride.getSpn(CarrierName) + " 0" + Integer.toString(slotId + 1);
            logdl("[addSubInfoRecord] Found, name = " + nameToSet);
        } else {
            nameToSet = "SUB 0" + Integer.toString(slotId + 1);
            logdl("[addSubInfoRecord] Not found, name = " + nameToSet);
        }

        ContentResolver resolver = mContext.getContentResolver();
        Cursor cursor = resolver.query(SubscriptionManager.CONTENT_URI,
                new String[] {BaseColumns._ID, SubscriptionManager.SIM_ID,
                SubscriptionManager.NAME_SOURCE}, SubscriptionManager.ICC_ID + "=?",
                new String[] {iccId}, null);

        try {
            if (cursor == null || !cursor.moveToFirst()) {
                ContentValues value = new ContentValues();
                value.put(SubscriptionManager.ICC_ID, iccId);
                // default SIM color differs between slots
                value.put(SubscriptionManager.COLOR, slotId);
                value.put(SubscriptionManager.SIM_ID, slotId);
                value.put(SubscriptionManager.DISPLAY_NAME, nameToSet);
                Uri uri = resolver.insert(SubscriptionManager.CONTENT_URI, value);
                logdl("[addSubInfoRecord]- New record created: " + uri);
            } else {
                long subId = cursor.getLong(0);
                int oldSimInfoId = cursor.getInt(1);
                int nameSource = cursor.getInt(2);
                ContentValues value = new ContentValues();

                if (slotId != oldSimInfoId) {
                    value.put(SubscriptionManager.SIM_ID, slotId);
                }

                if (nameSource != SubscriptionManager.NAME_SOURCE_USER_INPUT) {
                    value.put(SubscriptionManager.DISPLAY_NAME, nameToSet);
                }

                if (value.size() > 0) {
                    resolver.update(SubscriptionManager.CONTENT_URI, value,
                            BaseColumns._ID + "=" + Long.toString(subId), null);
                }

                logdl("[addSubInfoRecord]- Record already exist");
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        cursor = resolver.query(SubscriptionManager.CONTENT_URI, null,
                SubscriptionManager.SIM_ID + "=?", new String[] {String.valueOf(slotId)}, null);

        try {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    long subId = cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID));
                    // If mSimInfo already has a valid subId for a slotId/phoneId,
                    // do not add another subId for same slotId/phoneId.
                    Long currentSubId = mSimInfo.get(slotId);
                    if (currentSubId == null || !SubscriptionManager.isValidSubId(currentSubId)) {
                        // TODO While two subs active, if user deactivats first
                        // one, need to update the default subId with second one.

                        // FIXME: Currently we assume phoneId and slotId may not be true
                        // when we cross map modem or when multiple subs per slot.
                        // But is true at the moment.
                        mSimInfo.put(slotId, subId);
                        int simCount = TelephonyManager.getDefault().getSimCount();
                        long defaultSubId = getDefaultSubId();
                        logdl("[addSubInfoRecord] mSimInfo.size=" + mSimInfo.size()
                                + " slotId=" + slotId + " subId=" + subId
                                + " defaultSubId=" + defaultSubId + " simCount=" + simCount);

                        // Set the default sub if not set or if single sim device
                        if (!SubscriptionManager.isValidSubId(defaultSubId) || simCount == 1) {
                            setDefaultSubId(subId);
                        }
                        // If single sim device, set this subscription as the default for everything
                        if (simCount == 1) {
                            logdl("[addSubInfoRecord] one sim set defaults to subId=" + subId);
                            setDefaultDataSubId(subId);
                            setDefaultSmsSubId(subId);
                            setDefaultVoiceSubId(subId);
                        }
                    } else {
                        logdl("[addSubInfoRecord] currentSubId != null && currentSubId is valid, IGNORE");
                    }
                    logdl("[addSubInfoRecord]- hashmap("+slotId+","+subId+")");
                } while (cursor.moveToNext());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        int size = mSimInfo.size();
        logdl("[addSubInfoRecord]- info size="+size);

        // Once the records are loaded, notify DcTracker
        updateAllDataConnectionTrackers();

        // FIXME this does not match the javadoc
        return 1;
    }

    /**
     * Set SIM color by simInfo index
     * @param color the color of the SIM
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    @Override
    public int setColor(int color, long subId) {
        logd("[setColor]+ color:" + color + " subId:" + subId);
        enforceSubscriptionPermission();

        validateSubId(subId);
        int size = sSimBackgroundDarkRes.length;
        if (color < 0 || color >= size) {
            logd("[setColor]- fail");
            return -1;
        }
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.COLOR, color);
        logd("[setColor]- color:" + color + " set");

        int result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value,
                BaseColumns._ID + "=" + Long.toString(subId), null);
        broadcastSimInfoContentChanged(subId, SubscriptionManager.COLOR,
                color, SubscriptionManager.DEFAULT_STRING_VALUE);

        return result;
    }

    /**
     * Set display name by simInfo index
     * @param displayName the display name of SIM card
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    @Override
    public int setDisplayName(String displayName, long subId) {
        return setDisplayNameUsingSrc(displayName, subId, -1);
    }

    /**
     * Set display name by simInfo index with name source
     * @param displayName the display name of SIM card
     * @param subId the unique SubInfoRecord index in database
     * @param nameSource 0: NAME_SOURCE_DEFAULT_SOURCE, 1: NAME_SOURCE_SIM_SOURCE,
     *                   2: NAME_SOURCE_USER_INPUT, -1 NAME_SOURCE_UNDEFINED
     * @return the number of records updated
     */
    @Override
    public int setDisplayNameUsingSrc(String displayName, long subId, long nameSource) {
        logd("[setDisplayName]+  displayName:" + displayName + " subId:" + subId
                + " nameSource:" + nameSource);
        enforceSubscriptionPermission();

        validateSubId(subId);
        String nameToSet;
        if (displayName == null) {
            nameToSet = mContext.getString(SubscriptionManager.DEFAULT_NAME_RES);
        } else {
            nameToSet = displayName;
        }
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.DISPLAY_NAME, nameToSet);
        if (nameSource >= SubscriptionManager.NAME_SOURCE_DEFAULT_SOURCE) {
            logd("Set nameSource=" + nameSource);
            value.put(SubscriptionManager.NAME_SOURCE, nameSource);
        }
        logd("[setDisplayName]- mDisplayName:" + nameToSet + " set");

        int result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value,
                BaseColumns._ID + "=" + Long.toString(subId), null);
        broadcastSimInfoContentChanged(subId, SubscriptionManager.DISPLAY_NAME,
                SubscriptionManager.DEFAULT_INT_VALUE, nameToSet);

        return result;
    }

    /**
     * Set phone number by subId
     * @param number the phone number of the SIM
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    @Override
    public int setDisplayNumber(String number, long subId) {
        logd("[setDisplayNumber]+ number:" + number + " subId:" + subId);
        enforceSubscriptionPermission();

        validateSubId(subId);
        int result = 0;
        int phoneId = getPhoneId(subId);

        if (number == null || phoneId < 0 ||
                phoneId >= TelephonyManager.getDefault().getPhoneCount()) {
            logd("[setDispalyNumber]- fail");
            return -1;
        }
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.NUMBER, number);
        logd("[setDisplayNumber]- number:" + number + " set");

        Phone phone = sProxyPhones[phoneId];
        String alphaTag = TelephonyManager.getDefault().getLine1AlphaTagForSubscriber(subId);

        synchronized(mLock) {
            mSuccess = false;
            Message response = mHandler.obtainMessage(EVENT_WRITE_MSISDN_DONE);

            phone.setLine1Number(alphaTag, number, response);

            try {
                mLock.wait();
            } catch (InterruptedException e) {
                loge("interrupted while trying to write MSISDN");
            }
        }

        if (mSuccess) {
            result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value,
                    BaseColumns._ID + "=" + Long.toString(subId), null);
            logd("[setDisplayNumber]- update result :" + result);
            broadcastSimInfoContentChanged(subId, SubscriptionManager.NUMBER,
                    SubscriptionManager.DEFAULT_INT_VALUE, number);
        }

        return result;
    }

    /**
     * Set number display format. 0: none, 1: the first four digits, 2: the last four digits
     * @param format the display format of phone number
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    @Override
    public int setDisplayNumberFormat(int format, long subId) {
        logd("[setDisplayNumberFormat]+ format:" + format + " subId:" + subId);
        enforceSubscriptionPermission();

        validateSubId(subId);
        if (format < 0) {
            logd("[setDisplayNumberFormat]- fail, return -1");
            return -1;
        }
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.DISPLAY_NUMBER_FORMAT, format);
        logd("[setDisplayNumberFormat]- format:" + format + " set");

        int result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value,
                BaseColumns._ID + "=" + Long.toString(subId), null);
        broadcastSimInfoContentChanged(subId, SubscriptionManager.DISPLAY_NUMBER_FORMAT,
                format, SubscriptionManager.DEFAULT_STRING_VALUE);

        return result;
    }

    /**
     * Set data roaming by simInfo index
     * @param roaming 0:Don't allow data when roaming, 1:Allow data when roaming
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    @Override
    public int setDataRoaming(int roaming, long subId) {
        logd("[setDataRoaming]+ roaming:" + roaming + " subId:" + subId);
        enforceSubscriptionPermission();

        validateSubId(subId);
        if (roaming < 0) {
            logd("[setDataRoaming]- fail");
            return -1;
        }
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.DATA_ROAMING, roaming);
        logd("[setDataRoaming]- roaming:" + roaming + " set");

        int result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value,
                BaseColumns._ID + "=" + Long.toString(subId), null);
        broadcastSimInfoContentChanged(subId, SubscriptionManager.DATA_ROAMING,
                roaming, SubscriptionManager.DEFAULT_STRING_VALUE);

        return result;
    }

    /**
     * Set MCC/MNC by subscription ID
     * @param mccMnc MCC/MNC associated with the subscription
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    public int setMccMnc(String mccMnc, long subId) {
        int mcc = 0;
        int mnc = 0;
        try {
            mcc = Integer.parseInt(mccMnc.substring(0,3));
            mnc = Integer.parseInt(mccMnc.substring(3));
        } catch (NumberFormatException e) {
            logd("[setMccMnc] - couldn't parse mcc/mnc: " + mccMnc);
        }
        logd("[setMccMnc]+ mcc/mnc:" + mcc + "/" + mnc + " subId:" + subId);
        ContentValues value = new ContentValues(2);
        value.put(SubscriptionManager.MCC, mcc);
        value.put(SubscriptionManager.MNC, mnc);

        int result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value,
                BaseColumns._ID + "=" + Long.toString(subId), null);
        broadcastSimInfoContentChanged(subId, SubscriptionManager.MCC, mcc, null);

        return result;
    }


    @Override
    public int getSlotId(long subId) {
        if (VDBG) printStackTrace("[getSlotId] subId=" + subId);

        if (subId == SubscriptionManager.DEFAULT_SUB_ID) {
            subId = getDefaultSubId();
        }
        if (!SubscriptionManager.isValidSubId(subId)) {
            logd("[getSlotId]- subId invalid");
            return SubscriptionManager.INVALID_SLOT_ID;
        }

        int size = mSimInfo.size();

        if (size == 0)
        {
            logd("[getSlotId]- size == 0, return SIM_NOT_INSERTED instead");
            return SubscriptionManager.SIM_NOT_INSERTED;
        }

        for (Entry<Integer, Long> entry: mSimInfo.entrySet()) {
            int sim = entry.getKey();
            long sub = entry.getValue();

            if (subId == sub)
            {
                if (VDBG) logv("[getSlotId]- return = " + sim);
                return sim;
            }
        }

        logd("[getSlotId]- return fail");
        return SubscriptionManager.INVALID_SLOT_ID;
    }

    /**
     * Return the subId for specified slot Id.
     * @deprecated
     */
    @Override
    @Deprecated
    public long[] getSubId(int slotId) {
        if (VDBG) printStackTrace("[getSubId] slotId=" + slotId);

        if (slotId == SubscriptionManager.DEFAULT_SLOT_ID) {
            logd("[getSubId]- default slotId");
            slotId = getSlotId(getDefaultSubId());
        }

        //FIXME remove this
        final long[] DUMMY_VALUES = {-1 - slotId, -1 - slotId};

        if (!SubscriptionManager.isValidSlotId(slotId)) {
            logd("[getSubId]- invalid slotId");
            return null;
        }

        //FIXME remove this
        if (slotId < 0) {
            logd("[getSubId]- slotId < 0, return dummy instead");
            return DUMMY_VALUES;
        }

        int size = mSimInfo.size();

        if (size == 0) {
            logd("[getSubId]- size == 0, return dummy instead");
            //FIXME return null
            return DUMMY_VALUES;
        }

        ArrayList<Long> subIds = new ArrayList<Long>();
        for (Entry<Integer, Long> entry: mSimInfo.entrySet()) {
            int slot = entry.getKey();
            long sub = entry.getValue();
            if (slotId == slot) {
                subIds.add(sub);
            }
        }

        if (VDBG) logd("[getSubId]-, subIds = " + subIds);
        int numSubIds = subIds.size();

        if (numSubIds == 0) {
            logd("[getSubId]- numSubIds == 0, return dummy instead");
            return DUMMY_VALUES;
        }

        long[] subIdArr = new long[numSubIds];
        for (int i = 0; i < numSubIds; i++) {
            subIdArr[i] = subIds.get(i);
        }

        return subIdArr;
    }

    @Override
    public int getPhoneId(long subId) {
        if (VDBG) printStackTrace("[getPhoneId] subId=" + subId);
        int phoneId;

        if (subId == SubscriptionManager.DEFAULT_SUB_ID) {
            subId = getDefaultSubId();
            logdl("[getPhoneId] asked for default subId=" + subId);
        }

        if (!SubscriptionManager.isValidSubId(subId)) {
            logdl("[getPhoneId]- invalid subId return=" + SubscriptionManager.INVALID_PHONE_ID);
            return SubscriptionManager.INVALID_PHONE_ID;
        }

        //FIXME remove this
        if (subId < 0) {
            phoneId = (int) (-1 - subId);
            if (VDBG) logdl("[getPhoneId]- map subId=" + subId + " phoneId=" + phoneId);
            return phoneId;
        }

        int size = mSimInfo.size();

        if (size == 0) {
            phoneId = mDefaultPhoneId;
            logdl("[getPhoneId]- no sims, returning default phoneId=" + phoneId);
            return phoneId;
        }

        // FIXME: Assumes phoneId == slotId
        for (Entry<Integer, Long> entry: mSimInfo.entrySet()) {
            int sim = entry.getKey();
            long sub = entry.getValue();

            if (subId == sub) {
                if (VDBG) logdl("[getPhoneId]- found subId=" + subId + " phoneId=" + sim);
                return sim;
            }
        }

        phoneId = mDefaultPhoneId;
        logdl("[getPhoneId]- subId=" + subId + " not found return default phoneId=" + phoneId);
        return phoneId;

    }

    /**
     * @return the number of records cleared
     */
    @Override
    public int clearSubInfo() {
        enforceSubscriptionPermission();
        logd("[clearSubInfo]+");

        int size = mSimInfo.size();

        if (size == 0) {
            logdl("[clearSubInfo]- no simInfo size=" + size);
            return 0;
        }

        mSimInfo.clear();
        logdl("[clearSubInfo]- clear size=" + size);
        return size;
    }

    private static int[] setSimResource(int type) {
        int[] simResource = null;

        switch (type) {
            case RES_TYPE_BACKGROUND_DARK:
                simResource = new int[] {
                    com.android.internal.R.drawable.sim_dark_blue,
                    com.android.internal.R.drawable.sim_dark_orange,
                    com.android.internal.R.drawable.sim_dark_green,
                    com.android.internal.R.drawable.sim_dark_purple
                };
                break;
            case RES_TYPE_BACKGROUND_LIGHT:
                simResource = new int[] {
                    com.android.internal.R.drawable.sim_light_blue,
                    com.android.internal.R.drawable.sim_light_orange,
                    com.android.internal.R.drawable.sim_light_green,
                    com.android.internal.R.drawable.sim_light_purple
                };
                break;
        }

        return simResource;
    }

    private void logvl(String msg) {
        logv(msg);
        mLocalLog.log(msg);
    }

    private void logv(String msg) {
        Rlog.v(LOG_TAG, msg);
    }

    private void logdl(String msg) {
        logd(msg);
        mLocalLog.log(msg);
    }

    private static void slogd(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    private void logd(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    private void logel(String msg) {
        loge(msg);
        mLocalLog.log(msg);
    }

    private void loge(String msg) {
        Rlog.e(LOG_TAG, msg);
    }

    @Override
    @Deprecated
    public long getDefaultSubId() {
        //FIXME: Make this smarter, need to handle data only and voice devices
        long subId = mDefaultVoiceSubId;
        if (VDBG) logv("[getDefaultSubId] value = " + subId);
        return subId;
    }

    @Override
    public void setDefaultSmsSubId(long subId) {
        if (subId == SubscriptionManager.DEFAULT_SUB_ID) {
            throw new RuntimeException("setDefaultSmsSubId called with DEFAULT_SUB_ID");
        }
        logdl("[setDefaultSmsSubId] subId=" + subId);
        Settings.Global.putLong(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_SMS_SUBSCRIPTION, subId);
        broadcastDefaultSmsSubIdChanged(subId);
    }

    private void broadcastDefaultSmsSubIdChanged(long subId) {
        // Broadcast an Intent for default sms sub change
        logdl("[broadcastDefaultSmsSubIdChanged] subId=" + subId);
        Intent intent = new Intent(TelephonyIntents.ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    @Override
    public long getDefaultSmsSubId() {
        long subId = Settings.Global.getLong(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_SMS_SUBSCRIPTION,
                SubscriptionManager.INVALID_SUB_ID);
        if (VDBG) logd("[getDefaultSmsSubId] subId=" + subId);
        return subId;
    }

    @Override
    public void setDefaultVoiceSubId(long subId) {
        if (subId == SubscriptionManager.DEFAULT_SUB_ID) {
            throw new RuntimeException("setDefaultVoiceSubId called with DEFAULT_SUB_ID");
        }
        logdl("[setDefaultVoiceSubId] subId=" + subId);
        Settings.Global.putLong(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_VOICE_CALL_SUBSCRIPTION, subId);
        broadcastDefaultVoiceSubIdChanged(subId);
    }

    private void broadcastDefaultVoiceSubIdChanged(long subId) {
        // Broadcast an Intent for default voice sub change
        logdl("[broadcastDefaultVoiceSubIdChanged] subId=" + subId);
        Intent intent = new Intent(TelephonyIntents.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    @Override
    public long getDefaultVoiceSubId() {
        long subId = Settings.Global.getLong(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_VOICE_CALL_SUBSCRIPTION,
                SubscriptionManager.INVALID_SUB_ID);
        if (VDBG) logd("[getDefaultVoiceSubId] subId=" + subId);
        return subId;
    }

    @Override
    public long getDefaultDataSubId() {
        long subId = Settings.Global.getLong(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION,
                SubscriptionManager.INVALID_SUB_ID);
        if (VDBG) logd("[getDefaultDataSubId] subId= " + subId);
        return subId;
    }

    @Override
    public void setDefaultDataSubId(long subId) {
        if (subId == SubscriptionManager.DEFAULT_SUB_ID) {
            throw new RuntimeException("setDefaultDataSubId called with DEFAULT_SUB_ID");
        }
        logdl("[setDefaultDataSubId] subId=" + subId);

        Settings.Global.putLong(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION, subId);
        broadcastDefaultDataSubIdChanged(subId);

        // FIXME is this still needed?
        updateAllDataConnectionTrackers();
    }

    private void updateAllDataConnectionTrackers() {
        // Tell Phone Proxies to update data connection tracker
        int len = sProxyPhones.length;
        logdl("[updateAllDataConnectionTrackers] sProxyPhones.length=" + len);
        for (int phoneId = 0; phoneId < len; phoneId++) {
            logdl("[updateAllDataConnectionTrackers] phoneId=" + phoneId);
            sProxyPhones[phoneId].updateDataConnectionTracker();
        }
    }

    private void broadcastDefaultDataSubIdChanged(long subId) {
        // Broadcast an Intent for default data sub change
        logdl("[broadcastDefaultDataSubIdChanged] subId=" + subId);
        Intent intent = new Intent(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    /* Sets the default subscription. If only one sub is active that
     * sub is set as default subId. If two or more  sub's are active
     * the first sub is set as default subscription
     */
    // FIXME
    public void setDefaultSubId(long subId) {
        if (subId == SubscriptionManager.DEFAULT_SUB_ID) {
            throw new RuntimeException("setDefaultSubId called with DEFAULT_SUB_ID");
        }
        logdl("[setDefaultSubId] subId=" + subId);
        if (SubscriptionManager.isValidSubId(subId)) {
            int phoneId = getPhoneId(subId);
            if (phoneId >= 0 && (phoneId < TelephonyManager.getDefault().getPhoneCount()
                    || TelephonyManager.getDefault().getSimCount() == 1)) {
                logdl("[setDefaultSubId] set mDefaultVoiceSubId=" + subId);
                mDefaultVoiceSubId = subId;
                // Update MCC MNC device configuration information
                String defaultMccMnc = TelephonyManager.getDefault().getSimOperator(phoneId);
                MccTable.updateMccMncConfiguration(mContext, defaultMccMnc, false);

                // Broadcast an Intent for default sub change
                Intent intent = new Intent(TelephonyIntents.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
                intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                SubscriptionManager.putPhoneIdAndSubIdExtra(intent, phoneId, subId);
                if (VDBG) {
                    logdl("[setDefaultSubId] broadcast default subId changed phoneId=" + phoneId
                            + " subId=" + subId);
                }
                mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
            } else {
                if (VDBG) {
                    logdl("[setDefaultSubId] not set invalid phoneId=" + phoneId + " subId=" + subId);
                }
            }
        }
    }

    @Override
    public void clearDefaultsForInactiveSubIds() {
        final List<SubInfoRecord> records = getActiveSubInfoList();
        logdl("[clearDefaultsForInactiveSubIds] records: " + records);
        if (shouldDefaultBeCleared(records, getDefaultDataSubId())) {
            logd("[clearDefaultsForInactiveSubIds] clearing default data sub id");
            setDefaultDataSubId(SubscriptionManager.INVALID_SUB_ID);
        }
        if (shouldDefaultBeCleared(records, getDefaultSmsSubId())) {
            logdl("[clearDefaultsForInactiveSubIds] clearing default sms sub id");
            setDefaultSmsSubId(SubscriptionManager.INVALID_SUB_ID);
        }
        if (shouldDefaultBeCleared(records, getDefaultVoiceSubId())) {
            logdl("[clearDefaultsForInactiveSubIds] clearing default voice sub id");
            setDefaultVoiceSubId(SubscriptionManager.INVALID_SUB_ID);
        }
    }

    private boolean shouldDefaultBeCleared(List<SubInfoRecord> records, long subId) {
        logdl("[shouldDefaultBeCleared: subId] " + subId);
        if (records == null) {
            logdl("[shouldDefaultBeCleared] return true no records subId=" + subId);
            return true;
        }
        if (subId == SubscriptionManager.ASK_USER_SUB_ID && records.size() > 1) {
            // Only allow ASK_USER_SUB_ID if there is more than 1 subscription.
            logdl("[shouldDefaultBeCleared] return false only one subId, subId=" + subId);
            return false;
        }
        for (SubInfoRecord record : records) {
            logdl("[shouldDefaultBeCleared] Record.subId: " + record.subId);
            if (record.subId == subId) {
                logdl("[shouldDefaultBeCleared] return false subId is active, subId=" + subId);
                return false;
            }
        }
        logdl("[shouldDefaultBeCleared] return true not active subId=" + subId);
        return true;
    }

    /* This should return long and not long [] since each phone has
     * exactly 1 sub id for now, it could return the 0th element
     * returned from getSubId()
     */
    // FIXME will design a mechanism to manage the relationship between PhoneId/SlotId/SubId
    // since phoneId = SlotId is not always true
    public long getSubIdUsingPhoneId(int phoneId) {
        long[] subIds = getSubId(phoneId);
        if (subIds == null || subIds.length == 0) {
            return SubscriptionManager.INVALID_SUB_ID;
        }
        return subIds[0];
    }

    public long[] getSubIdUsingSlotId(int slotId) {
        return getSubId(slotId);
    }

    public List<SubInfoRecord> getSubInfoUsingSlotIdWithCheck(int slotId, boolean needCheck) {
        logd("[getSubInfoUsingSlotIdWithCheck]+ slotId:" + slotId);
        enforceSubscriptionPermission();

        if (slotId == SubscriptionManager.DEFAULT_SLOT_ID) {
            slotId = getSlotId(getDefaultSubId());
        }
        if (!SubscriptionManager.isValidSlotId(slotId)) {
            logd("[getSubInfoUsingSlotIdWithCheck]- invalid slotId");
            return null;
        }

        if (needCheck && !isSubInfoReady()) {
            logd("[getSubInfoUsingSlotIdWithCheck]- not ready");
            return null;
        }

        Cursor cursor = mContext.getContentResolver().query(SubscriptionManager.CONTENT_URI,
                null, SubscriptionManager.SIM_ID + "=?", new String[] {String.valueOf(slotId)}, null);
        ArrayList<SubInfoRecord> subList = null;
        try {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    SubInfoRecord subInfo = getSubInfoRecord(cursor);
                    if (subInfo != null)
                    {
                        if (subList == null)
                        {
                            subList = new ArrayList<SubInfoRecord>();
                        }
                        subList.add(subInfo);
                    }
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        logd("[getSubInfoUsingSlotId]- null info return");

        return subList;
    }

    private void validateSubId(long subId) {
        logd("validateSubId subId: " + subId);
        if (!SubscriptionManager.isValidSubId(subId)) {
            throw new RuntimeException("Invalid sub id passed as parameter");
        } else if (subId == SubscriptionManager.DEFAULT_SUB_ID) {
            throw new RuntimeException("Default sub id passed as parameter");
        }
    }

    public void updatePhonesAvailability(PhoneProxy[] phones) {
        sProxyPhones = phones;
    }

    /**
     * @return the list of subId's that are active, is never null but the length maybe 0.
     */
    @Override
    public long[] getActiveSubIdList() {
        Set<Entry<Integer, Long>> simInfoSet = mSimInfo.entrySet();
        logdl("[getActiveSubIdList] simInfoSet=" + simInfoSet);

        long[] subIdArr = new long[simInfoSet.size()];
        int i = 0;
        for (Entry<Integer, Long> entry: simInfoSet) {
            long sub = entry.getValue();
            subIdArr[i] = sub;
            i++;
        }

        logdl("[getActiveSubIdList] X subIdArr.length=" + subIdArr.length);
        return subIdArr;
    }

    private static void printStackTrace(String msg) {
        RuntimeException re = new RuntimeException();
        slogd("StackTrace - " + msg);
        StackTraceElement[] st = re.getStackTrace();
        boolean first = true;
        for (StackTraceElement ste : st) {
            if (first) {
                first = false;
            } else {
                slogd(ste.toString());
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("SubscriptionController:");
        pw.println(" defaultSubId=" + getDefaultSubId());
        pw.println(" defaultDataSubId=" + getDefaultDataSubId());
        pw.println(" defaultVoiceSubId=" + getDefaultVoiceSubId());
        pw.println(" defaultSmsSubId=" + getDefaultSmsSubId());

        pw.println(" defaultDataPhoneId=" + SubscriptionManager.getDefaultDataPhoneId());
        pw.println(" defaultVoicePhoneId=" + SubscriptionManager.getDefaultVoicePhoneId());
        pw.println(" defaultSmsPhoneId=" + SubscriptionManager.getDefaultSmsPhoneId());
        pw.flush();

        for (Entry<Integer, Long> entry : mSimInfo.entrySet()) {
            pw.println(" mSimInfo[" + entry.getKey() + "]: subId=" + entry.getValue());
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");

        List<SubInfoRecord> sirl = getActiveSubInfoList();
        if (sirl != null) {
            pw.println(" ActiveSubInfoList:");
            for (SubInfoRecord entry : sirl) {
                pw.println("  " + entry.toString());
            }
        } else {
            pw.println(" ActiveSubInfoList: is null");
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");

        sirl = getAllSubInfoList();
        if (sirl != null) {
            pw.println(" AllSubInfoList:");
            for (SubInfoRecord entry : sirl) {
                pw.println("  " + entry.toString());
            }
        } else {
            pw.println(" AllSubInfoList: is null");
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");

        mLocalLog.dump(fd, pw, args);
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");
        pw.flush();
    }
}
