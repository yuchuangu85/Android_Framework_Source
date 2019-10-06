/*
* Copyright (C) 2014 The Android Open Source Project
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

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.telephony.UiccSlotInfo.CARD_STATE_INFO_PRESENT;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.RadioAccessFamily;
import android.telephony.Rlog;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.UiccAccessRule;
import android.telephony.UiccSlotInfo;
import android.telephony.euicc.EuiccManager;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.IccCardConstants.State;
import com.android.internal.telephony.dataconnection.DataEnabledOverride;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.util.ArrayUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SubscriptionController to provide an inter-process communication to
 * access Sms in Icc.
 *
 * Any setters which take subId, slotIndex or phoneId as a parameter will throw an exception if the
 * parameter equals the corresponding INVALID_XXX_ID or DEFAULT_XXX_ID.
 *
 * All getters will lookup the corresponding default if the parameter is DEFAULT_XXX_ID. Ie calling
 * getPhoneId(DEFAULT_SUB_ID) will return the same as getPhoneId(getDefaultSubId()).
 *
 * Finally, any getters which perform the mapping between subscriptions, slots and phones will
 * return the corresponding INVALID_XXX_ID if the parameter is INVALID_XXX_ID. All other getters
 * will fail and return the appropriate error value. Ie calling
 * getSlotIndex(INVALID_SUBSCRIPTION_ID) will return INVALID_SIM_SLOT_INDEX and calling
 * getSubInfoForSubscriber(INVALID_SUBSCRIPTION_ID) will return null.
 *
 */
public class SubscriptionController extends ISub.Stub {
    private static final String LOG_TAG = "SubscriptionController";
    private static final boolean DBG = true;
    private static final boolean VDBG = Rlog.isLoggable(LOG_TAG, Log.VERBOSE);
    private static final boolean DBG_CACHE = false;
    private static final int DEPRECATED_SETTING = -1;
    private static final ParcelUuid INVALID_GROUP_UUID =
            ParcelUuid.fromString(CarrierConfigManager.REMOVE_GROUP_UUID_STRING);
    private final LocalLog mLocalLog = new LocalLog(200);

    // Lock that both mCacheActiveSubInfoList and mCacheOpportunisticSubInfoList use.
    private Object mSubInfoListLock = new Object();

    /* The Cache of Active SubInfoRecord(s) list of currently in use SubInfoRecord(s) */
    private final List<SubscriptionInfo> mCacheActiveSubInfoList = new ArrayList<>();

    /* Similar to mCacheActiveSubInfoList but only caching opportunistic subscriptions. */
    private List<SubscriptionInfo> mCacheOpportunisticSubInfoList = new ArrayList<>();

    private static final Comparator<SubscriptionInfo> SUBSCRIPTION_INFO_COMPARATOR =
            (arg0, arg1) -> {
                // Primary sort key on SimSlotIndex
                int flag = arg0.getSimSlotIndex() - arg1.getSimSlotIndex();
                if (flag == 0) {
                    // Secondary sort on SubscriptionId
                    return arg0.getSubscriptionId() - arg1.getSubscriptionId();
                }
                return flag;
            };

    @UnsupportedAppUsage
    protected final Object mLock = new Object();

    /** The singleton instance. */
    private static SubscriptionController sInstance = null;
    protected static Phone[] sPhones;
    @UnsupportedAppUsage
    protected Context mContext;
    protected TelephonyManager mTelephonyManager;
    protected UiccController mUiccController;

    private AppOpsManager mAppOps;

    // Each slot can have multiple subs.
    private static Map<Integer, ArrayList<Integer>> sSlotIndexToSubIds = new ConcurrentHashMap<>();
    private static int mDefaultFallbackSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    @UnsupportedAppUsage
    private static int mDefaultPhoneId = SubscriptionManager.DEFAULT_PHONE_INDEX;

    @UnsupportedAppUsage
    private int[] colorArr;
    private long mLastISubServiceRegTime;

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

    @UnsupportedAppUsage
    public static SubscriptionController getInstance() {
        if (sInstance == null)
        {
           Log.wtf(LOG_TAG, "getInstance null");
        }

        return sInstance;
    }

    protected SubscriptionController(Context c) {
        init(c);
        migrateImsSettings();
    }

    protected void init(Context c) {
        mContext = c;
        mTelephonyManager = TelephonyManager.from(mContext);

        try {
            mUiccController = UiccController.getInstance();
        } catch(RuntimeException ex) {
            throw new RuntimeException(
                    "UiccController has to be initialised before SubscriptionController init");
        }

        mAppOps = (AppOpsManager)mContext.getSystemService(Context.APP_OPS_SERVICE);

        if(ServiceManager.getService("isub") == null) {
            ServiceManager.addService("isub", this);
            mLastISubServiceRegTime = System.currentTimeMillis();
        }

        // clear SLOT_INDEX for all subs
        clearSlotIndexForSubInfoRecords();

        if (DBG) logdl("[SubscriptionController] init by Context");
    }

    /**
     * Should only be triggered once.
     */
    public void notifySubInfoReady() {
        // broadcast default subId.
        sendDefaultChangedBroadcast(SubscriptionManager.getDefaultSubscriptionId());
    }

    @UnsupportedAppUsage
    private boolean isSubInfoReady() {
        return SubscriptionInfoUpdater.isSubInfoInitialized();
    }

    /**
     * This function marks SIM_SLOT_INDEX as INVALID for all subscriptions in the database. This
     * should be done as part of initialization.
     *
     * TODO: SIM_SLOT_INDEX is based on current state and should not even be persisted in the
     * database.
     */
    private void clearSlotIndexForSubInfoRecords() {
        if (mContext == null) {
            logel("[clearSlotIndexForSubInfoRecords] TelephonyManager or mContext is null");
            return;
        }

        // Update all subscriptions in simInfo db with invalid slot index
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.SIM_SLOT_INDEX, SubscriptionManager.INVALID_SIM_SLOT_INDEX);
        mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value, null, null);
    }

    private SubscriptionController(Phone phone) {
        mContext = phone.getContext();
        mAppOps = mContext.getSystemService(AppOpsManager.class);

        if(ServiceManager.getService("isub") == null) {
                ServiceManager.addService("isub", this);
        }

        migrateImsSettings();

        // clear SLOT_INDEX for all subs
        clearSlotIndexForSubInfoRecords();

        if (DBG) logdl("[SubscriptionController] init by Phone");
    }

    @UnsupportedAppUsage
    private void enforceModifyPhoneState(String message) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MODIFY_PHONE_STATE, message);
    }

    private void enforceReadPrivilegedPhoneState(String message) {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.READ_PRIVILEGED_PHONE_STATE, message);
    }

    /**
     * Broadcast when SubscriptionInfo has changed
     * FIXME: Hopefully removed if the API council accepts SubscriptionInfoListener
     */
     private void broadcastSimInfoContentChanged() {
        Intent intent = new Intent(TelephonyIntents.ACTION_SUBINFO_CONTENT_CHANGE);
        mContext.sendBroadcast(intent);
        intent = new Intent(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);
        mContext.sendBroadcast(intent);
     }

    /**
     * Notify the changed of subscription info.
     */
    @UnsupportedAppUsage
    public void notifySubscriptionInfoChanged() {
        ITelephonyRegistry tr = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                "telephony.registry"));
        try {
            if (DBG) logd("notifySubscriptionInfoChanged:");
            tr.notifySubscriptionInfoChanged();
        } catch (RemoteException ex) {
            // Should never happen because its always available.
        }

        // FIXME: Remove if listener technique accepted.
        broadcastSimInfoContentChanged();

        MultiSimSettingController.getInstance().notifySubscriptionInfoChanged();
        TelephonyMetrics metrics = TelephonyMetrics.getInstance();
        List<SubscriptionInfo> subInfos;
        synchronized (mSubInfoListLock) {
            subInfos = new ArrayList<>(mCacheActiveSubInfoList);
        }
        metrics.updateActiveSubscriptionInfoList(subInfos);
    }

    /**
     * New SubInfoRecord instance and fill in detail info
     * @param cursor
     * @return the query result of desired SubInfoRecord
     */
    @UnsupportedAppUsage
    private SubscriptionInfo getSubInfoRecord(Cursor cursor) {
        int id = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID));
        String iccId = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.ICC_ID));
        int simSlotIndex = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.SIM_SLOT_INDEX));
        String displayName = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.DISPLAY_NAME));
        String carrierName = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.CARRIER_NAME));
        int nameSource = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.NAME_SOURCE));
        int iconTint = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.COLOR));
        String number = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.NUMBER));
        int dataRoaming = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.DATA_ROAMING));
        // Get the blank bitmap for this SubInfoRecord
        Bitmap iconBitmap = BitmapFactory.decodeResource(mContext.getResources(),
                com.android.internal.R.drawable.ic_sim_card_multi_24px_clr);
        String mcc = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.MCC_STRING));
        String mnc = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.MNC_STRING));
        String ehplmnsRaw = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.EHPLMNS));
        String hplmnsRaw = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.HPLMNS));
        String[] ehplmns = ehplmnsRaw == null ? null : ehplmnsRaw.split(",");
        String[] hplmns = hplmnsRaw == null ? null : hplmnsRaw.split(",");

        // cardId is the private ICCID/EID string, also known as the card string
        String cardId = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.CARD_ID));
        String countryIso = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.ISO_COUNTRY_CODE));
        // publicCardId is the publicly exposed int card ID
        int publicCardId = mUiccController.convertToPublicCardId(cardId);
        boolean isEmbedded = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.IS_EMBEDDED)) == 1;
        int carrierId = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.CARRIER_ID));
        UiccAccessRule[] accessRules;
        if (isEmbedded) {
            accessRules = UiccAccessRule.decodeRules(cursor.getBlob(
                    cursor.getColumnIndexOrThrow(SubscriptionManager.ACCESS_RULES)));
        } else {
            accessRules = null;
        }
        boolean isOpportunistic = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.IS_OPPORTUNISTIC)) == 1;
        String groupUUID = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.GROUP_UUID));
        int profileClass = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.PROFILE_CLASS));
        int subType = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.SUBSCRIPTION_TYPE));
        String groupOwner = getOptionalStringFromCursor(cursor, SubscriptionManager.GROUP_OWNER,
                /*defaultVal*/ null);

        if (VDBG) {
            String iccIdToPrint = SubscriptionInfo.givePrintableIccid(iccId);
            String cardIdToPrint = SubscriptionInfo.givePrintableIccid(cardId);
            logd("[getSubInfoRecord] id:" + id + " iccid:" + iccIdToPrint + " simSlotIndex:"
                    + simSlotIndex + " carrierid:" + carrierId + " displayName:" + displayName
                    + " nameSource:" + nameSource + " iconTint:" + iconTint
                    + " dataRoaming:" + dataRoaming + " mcc:" + mcc + " mnc:" + mnc
                    + " countIso:" + countryIso + " isEmbedded:"
                    + isEmbedded + " accessRules:" + Arrays.toString(accessRules)
                    + " cardId:" + cardIdToPrint + " publicCardId:" + publicCardId
                    + " isOpportunistic:" + isOpportunistic + " groupUUID:" + groupUUID
                    + " profileClass:" + profileClass + " subscriptionType: " + subType);
        }

        // If line1number has been set to a different number, use it instead.
        String line1Number = mTelephonyManager.getLine1Number(id);
        if (!TextUtils.isEmpty(line1Number) && !line1Number.equals(number)) {
            number = line1Number;
        }
        SubscriptionInfo info = new SubscriptionInfo(id, iccId, simSlotIndex, displayName,
                carrierName, nameSource, iconTint, number, dataRoaming, iconBitmap, mcc, mnc,
                countryIso, isEmbedded, accessRules, cardId, publicCardId, isOpportunistic,
                groupUUID, false /* isGroupDisabled */, carrierId, profileClass, subType,
                groupOwner);
        info.setAssociatedPlmns(ehplmns, hplmns);
        return info;
    }

    private String getOptionalStringFromCursor(Cursor cursor, String column, String defaultVal) {
        // Return defaultVal if the column doesn't exist.
        int columnIndex = cursor.getColumnIndex(column);
        return (columnIndex == -1) ? defaultVal : cursor.getString(columnIndex);
    }

    /**
     * Query SubInfoRecord(s) from subinfo database
     * @param selection A filter declaring which rows to return
     * @param queryKey query key content
     * @return Array list of queried result from database
     */
    @UnsupportedAppUsage
    public List<SubscriptionInfo> getSubInfo(String selection, Object queryKey) {
        if (VDBG) logd("selection:" + selection + ", querykey: " + queryKey);
        String[] selectionArgs = null;
        if (queryKey != null) {
            selectionArgs = new String[] {queryKey.toString()};
        }
        ArrayList<SubscriptionInfo> subList = null;
        Cursor cursor = mContext.getContentResolver().query(SubscriptionManager.CONTENT_URI,
                null, selection, selectionArgs, null);
        try {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    SubscriptionInfo subInfo = getSubInfoRecord(cursor);
                    if (subInfo != null)
                    {
                        if (subList == null)
                        {
                            subList = new ArrayList<SubscriptionInfo>();
                        }
                        subList.add(subInfo);
                    }
                }
            } else {
                if (DBG) logd("Query fail");
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return subList;
    }

    /**
     * Find unused color to be set for new SubInfoRecord
     * @param callingPackage The package making the IPC.
     * @return RGB integer value of color
     */
    private int getUnusedColor(String callingPackage) {
        List<SubscriptionInfo> availableSubInfos = getActiveSubscriptionInfoList(callingPackage);
        colorArr = mContext.getResources().getIntArray(com.android.internal.R.array.sim_colors);
        int colorIdx = 0;

        if (availableSubInfos != null) {
            for (int i = 0; i < colorArr.length; i++) {
                int j;
                for (j = 0; j < availableSubInfos.size(); j++) {
                    if (colorArr[i] == availableSubInfos.get(j).getIconTint()) {
                        break;
                    }
                }
                if (j == availableSubInfos.size()) {
                    return colorArr[i];
                }
            }
            colorIdx = availableSubInfos.size() % colorArr.length;
        }
        return colorArr[colorIdx];
    }

    /**
     * Get the active SubscriptionInfo with the subId key
     * @param subId The unique SubscriptionInfo key in database
     * @param callingPackage The package making the IPC.
     * @return SubscriptionInfo, maybe null if its not active
     */
    @UnsupportedAppUsage
    @Override
    public SubscriptionInfo getActiveSubscriptionInfo(int subId, String callingPackage) {
        if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(
                mContext, subId, callingPackage, "getActiveSubscriptionInfo")) {
            return null;
        }

        // Now that all security checks passes, perform the operation as ourselves.
        final long identity = Binder.clearCallingIdentity();
        try {
            List<SubscriptionInfo> subList = getActiveSubscriptionInfoList(
                    mContext.getOpPackageName());
            if (subList != null) {
                for (SubscriptionInfo si : subList) {
                    if (si.getSubscriptionId() == subId) {
                        if (DBG) {
                            logd("[getActiveSubscriptionInfo]+ subId=" + subId + " subInfo=" + si);
                        }

                        return si;
                    }
                }
            }
            if (DBG) {
                logd("[getActiveSubscriptionInfo]- subId=" + subId
                        + " subList=" + subList + " subInfo=null");
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        return null;
    }

    /**
     * Get a single subscription info record for a given subscription.
     *
     * @param subId the subId to query.
     *
     * @hide
     */
    public SubscriptionInfo getSubscriptionInfo(int subId) {
        List<SubscriptionInfo> subInfoList = getSubInfo(
                SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID + "=" + subId, null);
        if (subInfoList == null || subInfoList.isEmpty()) return null;
        return subInfoList.get(0);
    }

    /**
     * Get the active SubscriptionInfo associated with the iccId
     * @param iccId the IccId of SIM card
     * @param callingPackage The package making the IPC.
     * @return SubscriptionInfo, maybe null if its not active
     */
    @Override
    public SubscriptionInfo getActiveSubscriptionInfoForIccId(String iccId, String callingPackage) {
        // Query the subscriptions unconditionally, and then check whether the caller has access to
        // the given subscription.
        final SubscriptionInfo si = getActiveSubscriptionInfoForIccIdInternal(iccId);

        final int subId = si != null
                ? si.getSubscriptionId() : SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(
                mContext, subId, callingPackage, "getActiveSubscriptionInfoForIccId")) {
            return null;
        }

        return si;
    }

    /**
     * Get the active SubscriptionInfo associated with the given iccId. The caller *must* perform
     * permission checks when using this method.
     */
    private SubscriptionInfo getActiveSubscriptionInfoForIccIdInternal(String iccId) {
        if (iccId == null) {
            return null;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            List<SubscriptionInfo> subList = getActiveSubscriptionInfoList(
                    mContext.getOpPackageName());
            if (subList != null) {
                for (SubscriptionInfo si : subList) {
                    if (iccId.equals(si.getIccId())) {
                        if (DBG)
                            logd("[getActiveSubInfoUsingIccId]+ iccId=" + iccId + " subInfo=" + si);
                        return si;
                    }
                }
            }
            if (DBG) {
                logd("[getActiveSubInfoUsingIccId]+ iccId=" + iccId
                        + " subList=" + subList + " subInfo=null");
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        return null;
    }

    /**
     * Get the active SubscriptionInfo associated with the slotIndex.
     * This API does not return details on Remote-SIM subscriptions.
     * @param slotIndex the slot which the subscription is inserted
     * @param callingPackage The package making the IPC.
     * @return SubscriptionInfo, null for Remote-SIMs or non-active slotIndex.
     */
    @Override
    public SubscriptionInfo getActiveSubscriptionInfoForSimSlotIndex(int slotIndex,
            String callingPackage) {
        Phone phone = PhoneFactory.getPhone(slotIndex);
        if (phone == null) {
            if (DBG) {
                loge("[getActiveSubscriptionInfoForSimSlotIndex] no phone, slotIndex=" + slotIndex);
            }
            return null;
        }
        if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(
                mContext, phone.getSubId(), callingPackage,
                "getActiveSubscriptionInfoForSimSlotIndex")) {
            return null;
        }

        // Now that all security checks passes, perform the operation as ourselves.
        final long identity = Binder.clearCallingIdentity();
        try {
            List<SubscriptionInfo> subList = getActiveSubscriptionInfoList(
                    mContext.getOpPackageName());
            if (subList != null) {
                for (SubscriptionInfo si : subList) {
                    if (si.getSimSlotIndex() == slotIndex) {
                        if (DBG) {
                            logd("[getActiveSubscriptionInfoForSimSlotIndex]+ slotIndex="
                                    + slotIndex + " subId=" + si);
                        }
                        return si;
                    }
                }
                if (DBG) {
                    logd("[getActiveSubscriptionInfoForSimSlotIndex]+ slotIndex=" + slotIndex
                            + " subId=null");
                }
            } else {
                if (DBG) {
                    logd("[getActiveSubscriptionInfoForSimSlotIndex]+ subList=null");
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        return null;
    }

    /**
     * @param callingPackage The package making the IPC.
     * @return List of all SubscriptionInfo records in database,
     * include those that were inserted before, maybe empty but not null.
     * @hide
     */
    @Override
    public List<SubscriptionInfo> getAllSubInfoList(String callingPackage) {
        if (VDBG) logd("[getAllSubInfoList]+");

        // This API isn't public, so no need to provide a valid subscription ID - we're not worried
        // about carrier-privileged callers not having access.
        if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(
                mContext, SubscriptionManager.INVALID_SUBSCRIPTION_ID, callingPackage,
                "getAllSubInfoList")) {
            return null;
        }

        // Now that all security checks passes, perform the operation as ourselves.
        final long identity = Binder.clearCallingIdentity();
        try {
            List<SubscriptionInfo> subList = null;
            subList = getSubInfo(null, null);
            if (subList != null) {
                if (VDBG) logd("[getAllSubInfoList]- " + subList.size() + " infos return");
            } else {
                if (VDBG) logd("[getAllSubInfoList]- no info return");
            }
            return subList;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Get the SubInfoRecord(s) of the currently active SIM(s) - which include both local
     * and remote SIMs.
     * @param callingPackage The package making the IPC.
     * @return Array list of currently inserted SubInfoRecord(s)
     */
    @UnsupportedAppUsage
    @Override
    public List<SubscriptionInfo> getActiveSubscriptionInfoList(String callingPackage) {
        return getSubscriptionInfoListFromCacheHelper(callingPackage, mCacheActiveSubInfoList);
    }

    /**
     * Refresh the cache of SubInfoRecord(s) of the currently available SIM(s) - including
     * local & remote SIMs.
     */
    @VisibleForTesting  // For mockito to mock this method
    public void refreshCachedActiveSubscriptionInfoList() {
        boolean opptSubListChanged;

        synchronized (mSubInfoListLock) {
            List<SubscriptionInfo> activeSubscriptionInfoList = getSubInfo(
                    SubscriptionManager.SIM_SLOT_INDEX + ">=0 OR "
                    + SubscriptionManager.SUBSCRIPTION_TYPE + "="
                    + SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM,
                    null);

            if (activeSubscriptionInfoList != null) {
                // Log when active sub info changes.
                if (mCacheActiveSubInfoList.size() != activeSubscriptionInfoList.size()
                        || !mCacheActiveSubInfoList.containsAll(activeSubscriptionInfoList)) {
                    logdl("Active subscription info list changed. " + activeSubscriptionInfoList);
                }

                mCacheActiveSubInfoList.clear();
                activeSubscriptionInfoList.sort(SUBSCRIPTION_INFO_COMPARATOR);
                mCacheActiveSubInfoList.addAll(activeSubscriptionInfoList);
            } else {
                logd("activeSubscriptionInfoList is null.");
                mCacheActiveSubInfoList.clear();
            }

            // Refresh cached opportunistic sub list and detect whether it's changed.
            opptSubListChanged = refreshCachedOpportunisticSubscriptionInfoList();

            if (DBG_CACHE) {
                if (!mCacheActiveSubInfoList.isEmpty()) {
                    for (SubscriptionInfo si : mCacheActiveSubInfoList) {
                        logd("[refreshCachedActiveSubscriptionInfoList] Setting Cached info="
                                + si);
                    }
                } else {
                    logdl("[refreshCachedActiveSubscriptionInfoList]- no info return");
                }
            }
        }

        // Send notification outside synchronization.
        if (opptSubListChanged) {
            notifyOpportunisticSubscriptionInfoChanged();
        }
    }

    /**
     * Get the SUB count of active SUB(s)
     * @param callingPackage The package making the IPC.
     * @return active SIM count
     */
    @UnsupportedAppUsage
    @Override
    public int getActiveSubInfoCount(String callingPackage) {
        // Let getActiveSubscriptionInfoList perform permission checks / filtering.
        List<SubscriptionInfo> records = getActiveSubscriptionInfoList(callingPackage);
        if (records == null) {
            if (VDBG) logd("[getActiveSubInfoCount] records null");
            return 0;
        }
        if (VDBG) logd("[getActiveSubInfoCount]- count: " + records.size());
        return records.size();
    }

    /**
     * Get the SUB count of all SUB(s) in SubscriptoinInfo database
     * @param callingPackage The package making the IPC.
     * @return all SIM count in database, include what was inserted before
     */
    @Override
    public int getAllSubInfoCount(String callingPackage) {
        if (DBG) logd("[getAllSubInfoCount]+");

        // This API isn't public, so no need to provide a valid subscription ID - we're not worried
        // about carrier-privileged callers not having access.
        if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(
                mContext, SubscriptionManager.INVALID_SUBSCRIPTION_ID, callingPackage,
                "getAllSubInfoCount")) {
            return 0;
        }

        // Now that all security checks passes, perform the operation as ourselves.
        final long identity = Binder.clearCallingIdentity();
        try {
            Cursor cursor = mContext.getContentResolver().query(SubscriptionManager.CONTENT_URI,
                    null, null, null, null);
            try {
                if (cursor != null) {
                    int count = cursor.getCount();
                    if (DBG) logd("[getAllSubInfoCount]- " + count + " SUB(s) in DB");
                    return count;
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            if (DBG) logd("[getAllSubInfoCount]- no SUB in DB");

            return 0;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * @return the maximum number of local subscriptions this device will support at any one time.
     */
    @Override
    public int getActiveSubInfoCountMax() {
        // FIXME: This valid now but change to use TelephonyDevController in the future
        return mTelephonyManager.getSimCount();
    }

    @Override
    public List<SubscriptionInfo> getAvailableSubscriptionInfoList(String callingPackage) {
        // This API isn't public, so no need to provide a valid subscription ID - we're not worried
        // about carrier-privileged callers not having access.
        if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(
                mContext, SubscriptionManager.INVALID_SUBSCRIPTION_ID, callingPackage,
                "getAvailableSubscriptionInfoList")) {
            throw new SecurityException("Need READ_PHONE_STATE to call "
                    + " getAvailableSubscriptionInfoList");
        }

        // Now that all security checks pass, perform the operation as ourselves.
        final long identity = Binder.clearCallingIdentity();
        try {
            String selection = SubscriptionManager.SIM_SLOT_INDEX + ">=0 OR "
                    + SubscriptionManager.SUBSCRIPTION_TYPE + "="
                    + SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM;

            EuiccManager euiccManager =
                    (EuiccManager) mContext.getSystemService(Context.EUICC_SERVICE);
            if (euiccManager.isEnabled()) {
                selection += " OR " + SubscriptionManager.IS_EMBEDDED + "=1";
            }

            List<SubscriptionInfo> subList = getSubInfo(selection, null /* queryKey */);

            if (subList != null) {
                subList.sort(SUBSCRIPTION_INFO_COMPARATOR);

                if (VDBG) logdl("[getAvailableSubInfoList]- " + subList.size() + " infos return");
            } else {
                if (DBG) logdl("[getAvailableSubInfoList]- no info return");
            }

            return subList;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public List<SubscriptionInfo> getAccessibleSubscriptionInfoList(String callingPackage) {
        EuiccManager euiccManager = (EuiccManager) mContext.getSystemService(Context.EUICC_SERVICE);
        if (!euiccManager.isEnabled()) {
            if (DBG) {
                logdl("[getAccessibleSubInfoList] Embedded subscriptions are disabled");
            }
            return null;
        }

        // Verify that the given package belongs to the calling UID.
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);

        // Perform the operation as ourselves. If the caller cannot read phone state, they may still
        // have carrier privileges per the subscription metadata, so we always need to make the
        // query and then filter the results.
        final long identity = Binder.clearCallingIdentity();
        List<SubscriptionInfo> subList;
        try {
            subList = getSubInfo(SubscriptionManager.IS_EMBEDDED + "=1", null);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        if (subList == null) {
            if (DBG) logdl("[getAccessibleSubInfoList] No info returned");
            return null;
        }

        // Filter the list to only include subscriptions which the (restored) caller can manage.
        List<SubscriptionInfo> filteredList = subList.stream()
                .filter(subscriptionInfo ->
                        subscriptionInfo.canManageSubscription(mContext, callingPackage))
                .sorted(SUBSCRIPTION_INFO_COMPARATOR)
                .collect(Collectors.toList());
        if (VDBG) {
            logdl("[getAccessibleSubInfoList] " + filteredList.size() + " infos returned");
        }
        return filteredList;
    }

    /**
     * Return the list of subscriptions in the database which are either:
     * <ul>
     * <li>Embedded (but see note about {@code includeNonRemovableSubscriptions}, or
     * <li>In the given list of current embedded ICCIDs (which may not yet be in the database, or
     *     which may not currently be marked as embedded).
     * </ul>
     *
     * <p>NOTE: This is not accessible to external processes, so it does not need a permission
     * check. It is only intended for use by {@link SubscriptionInfoUpdater}.
     *
     * @param embeddedIccids all ICCIDs of available embedded subscriptions. This is used to surface
     *     entries for profiles which had been previously deleted.
     * @param isEuiccRemovable whether the current ICCID is removable. Non-removable subscriptions
     *     will only be returned if the current ICCID is not removable; otherwise, they are left
     *     alone (not returned here unless in the embeddedIccids list) under the assumption that
     *     they will still be accessible when the eUICC containing them is activated.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public List<SubscriptionInfo> getSubscriptionInfoListForEmbeddedSubscriptionUpdate(
            String[] embeddedIccids, boolean isEuiccRemovable) {
        StringBuilder whereClause = new StringBuilder();
        whereClause.append("(").append(SubscriptionManager.IS_EMBEDDED).append("=1");
        if (isEuiccRemovable) {
            // Current eUICC is removable, so don't return non-removable subscriptions (which would
            // be deleted), as these are expected to still be present on a different, non-removable
            // eUICC.
            whereClause.append(" AND ").append(SubscriptionManager.IS_REMOVABLE).append("=1");
        }
        // Else, return both removable and non-removable subscriptions. This is expected to delete
        // all removable subscriptions, which is desired as they may not be accessible.

        whereClause.append(") OR ").append(SubscriptionManager.ICC_ID).append(" IN (");
        // ICCIDs are validated to contain only numbers when passed in, and come from a trusted
        // app, so no need to escape.
        for (int i = 0; i < embeddedIccids.length; i++) {
            if (i > 0) {
                whereClause.append(",");
            }
            whereClause.append("\"").append(embeddedIccids[i]).append("\"");
        }
        whereClause.append(")");

        List<SubscriptionInfo> list = getSubInfo(whereClause.toString(), null);
        if (list == null) {
            return Collections.emptyList();
        }
        return list;
    }

    @Override
    public void requestEmbeddedSubscriptionInfoListRefresh(int cardId) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS,
                "requestEmbeddedSubscriptionInfoListRefresh");
        long token = Binder.clearCallingIdentity();
        try {
            PhoneFactory.requestEmbeddedSubscriptionInfoListRefresh(cardId, null /* callback */);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Asynchronously refresh the embedded subscription info list for the embedded card has the
     * given card id {@code cardId}.
     *
     * @param callback Optional callback to execute after the refresh completes. Must terminate
     *     quickly as it will be called from SubscriptionInfoUpdater's handler thread.
     */
    // No permission check needed as this is not exposed via AIDL.
    public void requestEmbeddedSubscriptionInfoListRefresh(
            int cardId, @Nullable Runnable callback) {
        PhoneFactory.requestEmbeddedSubscriptionInfoListRefresh(cardId, callback);
    }

    /**
     * Asynchronously refresh the embedded subscription info list for the embedded card has the
     * default card id return by {@link TelephonyManager#getCardIdForDefaultEuicc()}.
     *
     * @param callback Optional callback to execute after the refresh completes. Must terminate
     *     quickly as it will be called from SubscriptionInfoUpdater's handler thread.
     */
    // No permission check needed as this is not exposed via AIDL.
    public void requestEmbeddedSubscriptionInfoListRefresh(@Nullable Runnable callback) {
        PhoneFactory.requestEmbeddedSubscriptionInfoListRefresh(
                mTelephonyManager.getCardIdForDefaultEuicc(), callback);
    }

    /**
     * Add a new SubInfoRecord to subinfo database if needed
     * @param iccId the IccId of the SIM card
     * @param slotIndex the slot which the SIM is inserted
     * @return 0 if success, < 0 on error.
     */
    @Override
    public int addSubInfoRecord(String iccId, int slotIndex) {
        return addSubInfo(iccId, null, slotIndex, SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM);
    }

    /**
     * Add a new subscription info record, if needed.
     * @param uniqueId This is the unique identifier for the subscription within the specific
     *                 subscription type.
     * @param displayName human-readable name of the device the subscription corresponds to.
     * @param slotIndex value for {@link SubscriptionManager#SIM_SLOT_INDEX}
     * @param subscriptionType the type of subscription to be added.
     * @return 0 if success, < 0 on error.
     */
    @Override
    public int addSubInfo(String uniqueId, String displayName, int slotIndex,
            int subscriptionType) {
        if (DBG) {
            String iccIdStr = uniqueId;
            if (!isSubscriptionForRemoteSim(subscriptionType)) {
                iccIdStr = SubscriptionInfo.givePrintableIccid(uniqueId);
            }
            logdl("[addSubInfoRecord]+ iccid: " + iccIdStr
                    + ", slotIndex: " + slotIndex
                    + ", subscriptionType: " + subscriptionType);
        }

        enforceModifyPhoneState("addSubInfo");

        // Now that all security checks passes, perform the operation as ourselves.
        final long identity = Binder.clearCallingIdentity();
        try {
            if (uniqueId == null) {
                if (DBG) logdl("[addSubInfo]- null iccId");
                return -1;
            }

            ContentResolver resolver = mContext.getContentResolver();
            String selection = SubscriptionManager.ICC_ID + "=?";
            String[] args;
            if (isSubscriptionForRemoteSim(subscriptionType)) {
                selection += " AND " + SubscriptionManager.SUBSCRIPTION_TYPE + "=?";
                args = new String[]{uniqueId, Integer.toString(subscriptionType)};
            } else {
                selection += " OR " + SubscriptionManager.ICC_ID + "=?";
                args = new String[]{uniqueId, IccUtils.getDecimalSubstring(uniqueId)};
            }
            Cursor cursor = resolver.query(SubscriptionManager.CONTENT_URI,
                    new String[]{SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID,
                            SubscriptionManager.SIM_SLOT_INDEX, SubscriptionManager.NAME_SOURCE,
                            SubscriptionManager.ICC_ID, SubscriptionManager.CARD_ID},
                    selection, args, null);

            boolean setDisplayName = false;
            try {
                boolean recordsDoNotExist = (cursor == null || !cursor.moveToFirst());
                if (isSubscriptionForRemoteSim(subscriptionType)) {
                    if (recordsDoNotExist) {
                        // create a Subscription record
                        slotIndex = SubscriptionManager.SLOT_INDEX_FOR_REMOTE_SIM_SUB;
                        Uri uri = insertEmptySubInfoRecord(uniqueId, displayName,
                                slotIndex, subscriptionType);
                        if (DBG) logd("[addSubInfoRecord] New record created: " + uri);
                    } else {
                        if (DBG) logdl("[addSubInfoRecord] Record already exists");
                    }
                } else {  // Handle Local SIM devices
                    if (recordsDoNotExist) {
                        setDisplayName = true;
                        Uri uri = insertEmptySubInfoRecord(uniqueId, slotIndex);
                        if (DBG) logdl("[addSubInfoRecord] New record created: " + uri);
                    } else { // there are matching records in the database for the given ICC_ID
                        int subId = cursor.getInt(0);
                        int oldSimInfoId = cursor.getInt(1);
                        int nameSource = cursor.getInt(2);
                        String oldIccId = cursor.getString(3);
                        String oldCardId = cursor.getString(4);
                        ContentValues value = new ContentValues();

                        if (slotIndex != oldSimInfoId) {
                            value.put(SubscriptionManager.SIM_SLOT_INDEX, slotIndex);
                        }

                        if (oldIccId != null && oldIccId.length() < uniqueId.length()
                                && (oldIccId.equals(IccUtils.getDecimalSubstring(uniqueId)))) {
                            value.put(SubscriptionManager.ICC_ID, uniqueId);
                        }

                        UiccCard card = mUiccController.getUiccCardForPhone(slotIndex);
                        if (card != null) {
                            String cardId = card.getCardId();
                            if (cardId != null && cardId != oldCardId) {
                                value.put(SubscriptionManager.CARD_ID, cardId);
                            }
                        }

                        if (value.size() > 0) {
                            resolver.update(SubscriptionManager.getUriForSubscriptionId(subId),
                                    value, null, null);
                        }

                        if (DBG) logdl("[addSubInfoRecord] Record already exists");
                    }
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }

            selection = SubscriptionManager.SIM_SLOT_INDEX + "=?";
            args = new String[] {String.valueOf(slotIndex)};
            if (isSubscriptionForRemoteSim(subscriptionType)) {
                selection = SubscriptionManager.ICC_ID + "=? AND "
                        + SubscriptionManager.SUBSCRIPTION_TYPE + "=?";
                args = new String[]{uniqueId, Integer.toString(subscriptionType)};
            }
            cursor = resolver.query(SubscriptionManager.CONTENT_URI, null,
                    selection, args, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        int subId = cursor.getInt(cursor.getColumnIndexOrThrow(
                                SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID));
                        // If sSlotIndexToSubIds already has the same subId for a slotIndex/phoneId,
                        // do not add it.
                        if (addToSubIdList(slotIndex, subId, subscriptionType)) {
                            // TODO While two subs active, if user deactivats first
                            // one, need to update the default subId with second one.

                            // FIXME: Currently we assume phoneId == slotIndex which in the future
                            // may not be true, for instance with multiple subs per slot.
                            // But is true at the moment.
                            int subIdCountMax = getActiveSubInfoCountMax();
                            int defaultSubId = getDefaultSubId();
                            if (DBG) {
                                logdl("[addSubInfoRecord]"
                                        + " sSlotIndexToSubIds.size=" + sSlotIndexToSubIds.size()
                                        + " slotIndex=" + slotIndex + " subId=" + subId
                                        + " defaultSubId=" + defaultSubId
                                        + " simCount=" + subIdCountMax);
                            }

                            // Set the default sub if not set or if single sim device
                            if (!isSubscriptionForRemoteSim(subscriptionType)) {
                                if (!SubscriptionManager.isValidSubscriptionId(defaultSubId)
                                        || subIdCountMax == 1) {
                                    logdl("setting default fallback subid to " + subId);
                                    setDefaultFallbackSubId(subId, subscriptionType);
                                }
                                // If single sim device, set this subscription as the default for
                                // everything
                                if (subIdCountMax == 1) {
                                    if (DBG) {
                                        logdl("[addSubInfoRecord] one sim set defaults to subId="
                                                + subId);
                                    }
                                    setDefaultDataSubId(subId);
                                    setDefaultSmsSubId(subId);
                                    setDefaultVoiceSubId(subId);
                                }
                            } else {
                                updateDefaultSubIdsIfNeeded(subId, subscriptionType);
                            }
                        } else {
                            if (DBG) {
                                logdl("[addSubInfoRecord] current SubId is already known, "
                                        + "IGNORE");
                            }
                        }
                        if (DBG) {
                            logdl("[addSubInfoRecord] hashmap(" + slotIndex + "," + subId + ")");
                        }
                    } while (cursor.moveToNext());
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }

            // Refresh the Cache of Active Subscription Info List. This should be done after
            // updating sSlotIndexToSubIds which is done through addToSubIdList() above.
            refreshCachedActiveSubscriptionInfoList();

            if (isSubscriptionForRemoteSim(subscriptionType)) {
                notifySubscriptionInfoChanged();
            } else {  // Handle Local SIM devices
                // Set Display name after sub id is set above so as to get valid simCarrierName
                int subId = getSubIdUsingPhoneId(slotIndex);
                if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                    if (DBG) {
                        logdl("[addSubInfoRecord]- getSubId failed invalid subId = " + subId);
                    }
                    return -1;
                }
                if (setDisplayName) {
                    String simCarrierName = mTelephonyManager.getSimOperatorName(subId);
                    String nameToSet;

                    if (!TextUtils.isEmpty(simCarrierName)) {
                        nameToSet = simCarrierName;
                    } else {
                        nameToSet = "CARD " + Integer.toString(slotIndex + 1);
                    }

                    ContentValues value = new ContentValues();
                    value.put(SubscriptionManager.DISPLAY_NAME, nameToSet);
                    resolver.update(SubscriptionManager.getUriForSubscriptionId(subId), value,
                            null, null);

                    // Refresh the Cache of Active Subscription Info List
                    refreshCachedActiveSubscriptionInfoList();

                    if (DBG) logdl("[addSubInfoRecord] sim name = " + nameToSet);
                }

                // Once the records are loaded, notify DcTracker
                sPhones[slotIndex].updateDataConnectionTracker();

                if (DBG) logdl("[addSubInfoRecord]- info size=" + sSlotIndexToSubIds.size());
            }

        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return 0;
    }

    private void updateDefaultSubIdsIfNeeded(int newDefault, int subscriptionType) {
        if (DBG) {
            logdl("[updateDefaultSubIdsIfNeeded] newDefault=" + newDefault
                    + ", subscriptionType=" + subscriptionType);
        }
        // Set the default ot new value only if the current default is invalid.
        if (!isActiveSubscriptionId(getDefaultSubId())) {
            // current default is not valid anylonger. set a new default
            if (DBG) {
                logdl("[updateDefaultSubIdsIfNeeded] set mDefaultFallbackSubId=" + newDefault);
            }
            setDefaultFallbackSubId(newDefault, subscriptionType);
        }

        int value = getDefaultSmsSubId();
        if (!isActiveSubscriptionId(value)) {
            // current default is not valid. set it to the given newDefault value
            setDefaultSmsSubId(newDefault);
        }
        value = getDefaultDataSubId();
        if (!isActiveSubscriptionId(value)) {
            setDefaultDataSubId(newDefault);
        }
        value = getDefaultVoiceSubId();
        if (!isActiveSubscriptionId(value)) {
            setDefaultVoiceSubId(newDefault);
        }
    }

    /**
     * This method returns true if the given subId is among the list of currently active
     * subscriptions.
     */
    private boolean isActiveSubscriptionId(int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) return false;
        ArrayList<Integer> subIdList = getActiveSubIdArrayList();
        if (subIdList.isEmpty()) return false;
        return subIdList.contains(new Integer(subId));
    }

    /*
     * Delete subscription info record for the given device.
     * @param uniqueId This is the unique identifier for the subscription within the specific
     *                 subscription type.
     * @param subscriptionType the type of subscription to be removed
     * @return 0 if success, < 0 on error.
     */
    @Override
    public int removeSubInfo(String uniqueId, int subscriptionType) {
        enforceModifyPhoneState("removeSubInfo");
        if (DBG) {
            logd("[removeSubInfo] uniqueId: " + uniqueId
                    + ", subscriptionType: " + subscriptionType);
        }

        // validate the given info - does it exist in the active subscription list
        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        int slotIndex = SubscriptionManager.INVALID_SIM_SLOT_INDEX;
        for (SubscriptionInfo info : mCacheActiveSubInfoList) {
            if ((info.getSubscriptionType() == subscriptionType)
                    && info.getIccId().equalsIgnoreCase(uniqueId)) {
                subId = info.getSubscriptionId();
                slotIndex = info.getSimSlotIndex();
                break;
            }
        }
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            if (DBG) {
                logd("Invalid subscription details: subscriptionType = " + subscriptionType
                        + ", uniqueId = " + uniqueId);
            }
            return -1;
        }

        if (DBG) logd("removing the subid : " + subId);

        // Now that all security checks passes, perform the operation as ourselves.
        int result = 0;
        final long identity = Binder.clearCallingIdentity();
        try {
            ContentResolver resolver = mContext.getContentResolver();
            result = resolver.delete(SubscriptionManager.CONTENT_URI,
                    SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID + "=? AND "
                            + SubscriptionManager.SUBSCRIPTION_TYPE + "=?",
                    new String[]{Integer.toString(subId), Integer.toString(subscriptionType)});
            if (result != 1) {
                if (DBG) {
                    logd("found NO subscription to remove with subscriptionType = "
                            + subscriptionType + ", uniqueId = " + uniqueId);
                }
                return -1;
            }
            refreshCachedActiveSubscriptionInfoList();

            // update sSlotIndexToSubIds struct
            ArrayList<Integer> subIdsList = sSlotIndexToSubIds.get(slotIndex);
            if (subIdsList == null) {
                loge("sSlotIndexToSubIds has no entry for slotIndex = " + slotIndex);
            } else {
                if (subIdsList.contains(subId)) {
                    subIdsList.remove(new Integer(subId));
                    if (subIdsList.isEmpty()) {
                        sSlotIndexToSubIds.remove(slotIndex);
                    }
                } else {
                    loge("sSlotIndexToSubIds has no subid: " + subId
                            + ", in index: " + slotIndex);
                }
            }
            // Since a subscription is removed, if this one is set as default for any setting,
            // set some other subid as the default.
            int newDefault = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
            SubscriptionInfo info = null;
            final List<SubscriptionInfo> records = getActiveSubscriptionInfoList(
                    mContext.getOpPackageName());
            if (!records.isEmpty()) {
                // yes, we have more subscriptions. pick the first one.
                // FIXME do we need a policy to figure out which one is to be next default
                info = records.get(0);
            }
            updateDefaultSubIdsIfNeeded(info.getSubscriptionId(), info.getSubscriptionType());

            notifySubscriptionInfoChanged();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return result;
    }

    /**
     * Clear an subscriptionInfo to subinfo database if needed by updating slot index to invalid.
     * @param slotIndex the slot which the SIM is removed
     */
    public void clearSubInfoRecord(int slotIndex) {
        if (DBG) logdl("[clearSubInfoRecord]+ iccId:" + " slotIndex:" + slotIndex);

        // update simInfo db with invalid slot index
        List<SubscriptionInfo> oldSubInfo = getSubInfoUsingSlotIndexPrivileged(slotIndex);
        ContentResolver resolver = mContext.getContentResolver();
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.SIM_SLOT_INDEX,
                SubscriptionManager.INVALID_SIM_SLOT_INDEX);
        if (oldSubInfo != null) {
            for (int i = 0; i < oldSubInfo.size(); i++) {
                resolver.update(SubscriptionManager.getUriForSubscriptionId(
                        oldSubInfo.get(i).getSubscriptionId()), value, null, null);
            }
        }
        // Refresh the Cache of Active Subscription Info List
        refreshCachedActiveSubscriptionInfoList();

        sSlotIndexToSubIds.remove(slotIndex);
    }

    /**
     * Insert an empty SubInfo record into the database.
     *
     * <p>NOTE: This is not accessible to external processes, so it does not need a permission
     * check. It is only intended for use by {@link SubscriptionInfoUpdater}.
     *
     * <p>Precondition: No record exists with this iccId.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public Uri insertEmptySubInfoRecord(String iccId, int slotIndex) {
        return insertEmptySubInfoRecord(iccId, null, slotIndex,
                SubscriptionManager.SUBSCRIPTION_TYPE_LOCAL_SIM);
    }

    Uri insertEmptySubInfoRecord(String uniqueId, String displayName, int slotIndex,
            int subscriptionType) {
        ContentResolver resolver = mContext.getContentResolver();
        ContentValues value = new ContentValues();
        value.put(SubscriptionManager.ICC_ID, uniqueId);
        int color = getUnusedColor(mContext.getOpPackageName());
        // default SIM color differs between slots
        value.put(SubscriptionManager.COLOR, color);
        value.put(SubscriptionManager.SIM_SLOT_INDEX, slotIndex);
        value.put(SubscriptionManager.CARRIER_NAME, "");
        value.put(SubscriptionManager.CARD_ID, uniqueId);
        value.put(SubscriptionManager.SUBSCRIPTION_TYPE, subscriptionType);
        if (isSubscriptionForRemoteSim(subscriptionType)) {
            value.put(SubscriptionManager.DISPLAY_NAME, displayName);
        } else {
            UiccCard card = mUiccController.getUiccCardForPhone(slotIndex);
            if (card != null) {
                String cardId = card.getCardId();
                if (cardId != null) {
                    value.put(SubscriptionManager.CARD_ID, cardId);
                }
            }
        }

        Uri uri = resolver.insert(SubscriptionManager.CONTENT_URI, value);

        // Refresh the Cache of Active Subscription Info List
        refreshCachedActiveSubscriptionInfoList();

        return uri;
    }

    /**
     * Generate and set carrier text based on input parameters
     * @param showPlmn flag to indicate if plmn should be included in carrier text
     * @param plmn plmn to be included in carrier text
     * @param showSpn flag to indicate if spn should be included in carrier text
     * @param spn spn to be included in carrier text
     * @return true if carrier text is set, false otherwise
     */
    @UnsupportedAppUsage
    public boolean setPlmnSpn(int slotIndex, boolean showPlmn, String plmn, boolean showSpn,
                              String spn) {
        synchronized (mLock) {
            int subId = getSubIdUsingPhoneId(slotIndex);
            if (mContext.getPackageManager().resolveContentProvider(
                    SubscriptionManager.CONTENT_URI.getAuthority(), 0) == null ||
                    !SubscriptionManager.isValidSubscriptionId(subId)) {
                // No place to store this info. Notify registrants of the change anyway as they
                // might retrieve the SPN/PLMN text from the SST sticky broadcast.
                // TODO: This can be removed once SubscriptionController is not running on devices
                // that don't need it, such as TVs.
                if (DBG) logd("[setPlmnSpn] No valid subscription to store info");
                notifySubscriptionInfoChanged();
                return false;
            }
            String carrierText = "";
            if (showPlmn) {
                carrierText = plmn;
                if (showSpn) {
                    // Need to show both plmn and spn if both are not same.
                    if(!Objects.equals(spn, plmn)) {
                        String separator = mContext.getString(
                                com.android.internal.R.string.kg_text_message_separator).toString();
                        carrierText = new StringBuilder().append(carrierText).append(separator)
                                .append(spn).toString();
                    }
                }
            } else if (showSpn) {
                carrierText = spn;
            }
            setCarrierText(carrierText, subId);
            return true;
        }
    }

    /**
     * Set carrier text by simInfo index
     * @param text new carrier text
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    private int setCarrierText(String text, int subId) {
        if (DBG) logd("[setCarrierText]+ text:" + text + " subId:" + subId);

        enforceModifyPhoneState("setCarrierText");

        // Now that all security checks passes, perform the operation as ourselves.
        final long identity = Binder.clearCallingIdentity();
        try {
            ContentValues value = new ContentValues(1);
            value.put(SubscriptionManager.CARRIER_NAME, text);

            int result = mContext.getContentResolver().update(
                    SubscriptionManager.getUriForSubscriptionId(subId), value, null, null);

            // Refresh the Cache of Active Subscription Info List
            refreshCachedActiveSubscriptionInfoList();

            notifySubscriptionInfoChanged();

            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Set SIM color tint by simInfo index
     * @param tint the tint color of the SIM
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    @Override
    public int setIconTint(int tint, int subId) {
        if (DBG) logd("[setIconTint]+ tint:" + tint + " subId:" + subId);

        enforceModifyPhoneState("setIconTint");

        // Now that all security checks passes, perform the operation as ourselves.
        final long identity = Binder.clearCallingIdentity();
        try {
            validateSubId(subId);
            ContentValues value = new ContentValues(1);
            value.put(SubscriptionManager.COLOR, tint);
            if (DBG) logd("[setIconTint]- tint:" + tint + " set");

            int result = mContext.getContentResolver().update(
                    SubscriptionManager.getUriForSubscriptionId(subId), value, null, null);

            // Refresh the Cache of Active Subscription Info List
            refreshCachedActiveSubscriptionInfoList();

            notifySubscriptionInfoChanged();

            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * This is only for internal use and the returned priority is arbitrary. The idea is to give a
     * higher value to name source that has higher priority to override other name sources.
     * @param nameSource Source of display name
     * @return int representing the priority. Higher value means higher priority.
     */
    public static int getNameSourcePriority(int nameSource) {
        switch (nameSource) {
            case SubscriptionManager.NAME_SOURCE_USER_INPUT:
                return 3;
            case SubscriptionManager.NAME_SOURCE_CARRIER:
                return 2;
            case SubscriptionManager.NAME_SOURCE_SIM_SOURCE:
                return 1;
            case SubscriptionManager.NAME_SOURCE_DEFAULT_SOURCE:
            default:
                return 0;
        }
    }

    /**
     * Set display name by simInfo index with name source
     * @param displayName the display name of SIM card
     * @param subId the unique SubInfoRecord index in database
     * @param nameSource 0: NAME_SOURCE_DEFAULT_SOURCE, 1: NAME_SOURCE_SIM_SOURCE,
     *                   2: NAME_SOURCE_USER_INPUT, 3: NAME_SOURCE_CARRIER
     * @return the number of records updated
     */
    @Override
    public int setDisplayNameUsingSrc(String displayName, int subId, int nameSource) {
        if (DBG) {
            logd("[setDisplayName]+  displayName:" + displayName + " subId:" + subId
                + " nameSource:" + nameSource);
        }

        enforceModifyPhoneState("setDisplayNameUsingSrc");

        // Now that all security checks passes, perform the operation as ourselves.
        final long identity = Binder.clearCallingIdentity();
        try {
            validateSubId(subId);
            List<SubscriptionInfo> allSubInfo = getSubInfo(null, null);
            // if there is no sub in the db, return 0 since subId does not exist in db
            if (allSubInfo == null || allSubInfo.isEmpty()) return 0;
            for (SubscriptionInfo subInfo : allSubInfo) {
                if (subInfo.getSubscriptionId() == subId
                        && (getNameSourcePriority(subInfo.getNameSource())
                                > getNameSourcePriority(nameSource)
                        || (displayName != null && displayName.equals(subInfo.getDisplayName())))) {
                    return 0;
                }
            }
            String nameToSet;
            if (displayName == null) {
                nameToSet = mContext.getString(SubscriptionManager.DEFAULT_NAME_RES);
            } else {
                nameToSet = displayName;
            }
            ContentValues value = new ContentValues(1);
            value.put(SubscriptionManager.DISPLAY_NAME, nameToSet);
            if (nameSource >= SubscriptionManager.NAME_SOURCE_DEFAULT_SOURCE) {
                if (DBG) logd("Set nameSource=" + nameSource);
                value.put(SubscriptionManager.NAME_SOURCE, nameSource);
            }
            if (DBG) logd("[setDisplayName]- mDisplayName:" + nameToSet + " set");

            // Update the nickname on the eUICC chip if it's an embedded subscription.
            SubscriptionInfo sub = getSubscriptionInfo(subId);
            if (sub != null && sub.isEmbedded()) {
                // Ignore the result.
                int cardId = sub.getCardId();
                if (DBG) logd("Updating embedded sub nickname on cardId: " + cardId);
                EuiccManager euiccManager = ((EuiccManager)
                        mContext.getSystemService(Context.EUICC_SERVICE)).createForCardId(cardId);
                euiccManager.updateSubscriptionNickname(subId, displayName,
                        PendingIntent.getService(
                            mContext, 0 /* requestCode */, new Intent(), 0 /* flags */));
            }

            int result = mContext.getContentResolver().update(
                    SubscriptionManager.getUriForSubscriptionId(subId), value, null, null);

            // Refresh the Cache of Active Subscription Info List
            refreshCachedActiveSubscriptionInfoList();

            notifySubscriptionInfoChanged();

            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Set phone number by subId
     * @param number the phone number of the SIM
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    @Override
    public int setDisplayNumber(String number, int subId) {
        if (DBG) logd("[setDisplayNumber]+ subId:" + subId);

        enforceModifyPhoneState("setDisplayNumber");

        // Now that all security checks passes, perform the operation as ourselves.
        final long identity = Binder.clearCallingIdentity();
        try {
            validateSubId(subId);
            int result;
            int phoneId = getPhoneId(subId);

            if (number == null || phoneId < 0 ||
                    phoneId >= mTelephonyManager.getPhoneCount()) {
                if (DBG) logd("[setDispalyNumber]- fail");
                return -1;
            }
            ContentValues value = new ContentValues(1);
            value.put(SubscriptionManager.NUMBER, number);

            // This function had a call to update number on the SIM (Phone.setLine1Number()) but
            // that was removed as there doesn't seem to be a reason for that. If it is added
            // back, watch out for deadlocks.

            result = mContext.getContentResolver().update(
                    SubscriptionManager.getUriForSubscriptionId(subId), value, null, null);

            // Refresh the Cache of Active Subscription Info List
            refreshCachedActiveSubscriptionInfoList();

            if (DBG) logd("[setDisplayNumber]- update result :" + result);
            notifySubscriptionInfoChanged();

            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Set the EHPLMNs and HPLMNs associated with the subscription.
     */
    public void setAssociatedPlmns(String[] ehplmns, String[] hplmns, int subId) {
        if (DBG) logd("[setAssociatedPlmns]+ subId:" + subId);

        validateSubId(subId);
        int phoneId = getPhoneId(subId);

        if (phoneId < 0 || phoneId >= mTelephonyManager.getPhoneCount()) {
            if (DBG) logd("[setAssociatedPlmns]- fail");
            return;
        }

        String formattedEhplmns = ehplmns == null ? "" : String.join(",", ehplmns);
        String formattedHplmns = hplmns == null ? "" : String.join(",", hplmns);

        ContentValues value = new ContentValues(2);
        value.put(SubscriptionManager.EHPLMNS, formattedEhplmns);
        value.put(SubscriptionManager.HPLMNS, formattedHplmns);

        int count = mContext.getContentResolver().update(
                SubscriptionManager.getUriForSubscriptionId(subId), value, null, null);

        // Refresh the Cache of Active Subscription Info List
        refreshCachedActiveSubscriptionInfoList();

        if (DBG) logd("[setAssociatedPlmns]- update result :" + count);
        notifySubscriptionInfoChanged();
    }

    /**
     * Set data roaming by simInfo index
     * @param roaming 0:Don't allow data when roaming, 1:Allow data when roaming
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    @Override
    public int setDataRoaming(int roaming, int subId) {
        if (DBG) logd("[setDataRoaming]+ roaming:" + roaming + " subId:" + subId);

        enforceModifyPhoneState("setDataRoaming");

        // Now that all security checks passes, perform the operation as ourselves.
        final long identity = Binder.clearCallingIdentity();
        try {
            validateSubId(subId);
            if (roaming < 0) {
                if (DBG) logd("[setDataRoaming]- fail");
                return -1;
            }
            ContentValues value = new ContentValues(1);
            value.put(SubscriptionManager.DATA_ROAMING, roaming);
            if (DBG) logd("[setDataRoaming]- roaming:" + roaming + " set");

            int result = databaseUpdateHelper(value, subId, true);

            // Refresh the Cache of Active Subscription Info List
            refreshCachedActiveSubscriptionInfoList();

            notifySubscriptionInfoChanged();

            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }


    public void syncGroupedSetting(int refSubId) {
        // Currently it only syncs allow MMS. Sync other settings as well if needed.
        String dataEnabledOverrideRules = getSubscriptionProperty(
                refSubId, SubscriptionManager.DATA_ENABLED_OVERRIDE_RULES);

        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.DATA_ENABLED_OVERRIDE_RULES, dataEnabledOverrideRules);
        databaseUpdateHelper(value, refSubId, true);
    }

    private int databaseUpdateHelper(ContentValues value, int subId, boolean updateEntireGroup) {
        List<SubscriptionInfo> infoList = getSubscriptionsInGroup(getGroupUuid(subId),
                mContext.getOpPackageName());
        if (!updateEntireGroup || infoList == null || infoList.size() == 0) {
            // Only update specified subscriptions.
            return mContext.getContentResolver().update(
                    SubscriptionManager.getUriForSubscriptionId(subId), value, null, null);
        } else {
            // Update all subscriptions in the same group.
            int[] subIdList = new int[infoList.size()];
            for (int i = 0; i < infoList.size(); i++) {
                subIdList[i] = infoList.get(i).getSubscriptionId();
            }
            return mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI,
                    value, getSelectionForSubIdList(subIdList), null);
        }
    }

    /**
     * Set carrier id by subId
     * @param carrierId the subscription carrier id.
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     *
     * @see TelephonyManager#getSimCarrierId()
     */
    public int setCarrierId(int carrierId, int subId) {
        if (DBG) logd("[setCarrierId]+ carrierId:" + carrierId + " subId:" + subId);

        enforceModifyPhoneState("setCarrierId");

        // Now that all security checks passes, perform the operation as ourselves.
        final long identity = Binder.clearCallingIdentity();
        try {
            validateSubId(subId);
            ContentValues value = new ContentValues(1);
            value.put(SubscriptionManager.CARRIER_ID, carrierId);
            int result = mContext.getContentResolver().update(
                    SubscriptionManager.getUriForSubscriptionId(subId), value, null, null);

            // Refresh the Cache of Active Subscription Info List
            refreshCachedActiveSubscriptionInfoList();

            notifySubscriptionInfoChanged();

            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Set MCC/MNC by subscription ID
     * @param mccMnc MCC/MNC associated with the subscription
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    public int setMccMnc(String mccMnc, int subId) {
        String mccString = mccMnc.substring(0, 3);
        String mncString = mccMnc.substring(3);
        int mcc = 0;
        int mnc = 0;
        try {
            mcc = Integer.parseInt(mccString);
            mnc = Integer.parseInt(mncString);
        } catch (NumberFormatException e) {
            loge("[setMccMnc] - couldn't parse mcc/mnc: " + mccMnc);
        }
        if (DBG) logd("[setMccMnc]+ mcc/mnc:" + mcc + "/" + mnc + " subId:" + subId);
        ContentValues value = new ContentValues(4);
        value.put(SubscriptionManager.MCC, mcc);
        value.put(SubscriptionManager.MNC, mnc);
        value.put(SubscriptionManager.MCC_STRING, mccString);
        value.put(SubscriptionManager.MNC_STRING, mncString);

        int result = mContext.getContentResolver().update(
                SubscriptionManager.getUriForSubscriptionId(subId), value, null, null);

        // Refresh the Cache of Active Subscription Info List
        refreshCachedActiveSubscriptionInfoList();

        notifySubscriptionInfoChanged();

        return result;
    }

    /**
     * Set IMSI by subscription ID
     * @param imsi IMSI (International Mobile Subscriber Identity)
     * @return the number of records updated
     */
    public int setImsi(String imsi, int subId) {
        if (DBG) logd("[setImsi]+ imsi:" + imsi + " subId:" + subId);
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.IMSI, imsi);

        int result = mContext.getContentResolver().update(
                SubscriptionManager.getUriForSubscriptionId(subId), value, null, null);

        // Refresh the Cache of Active Subscription Info List
        refreshCachedActiveSubscriptionInfoList();

        notifySubscriptionInfoChanged();

        return result;
    }

    /**
     * Get IMSI by subscription ID
     * For active subIds, this will always return the corresponding imsi
     * For inactive subIds, once they are activated once, even if they are deactivated at the time
     *   of calling this function, the corresponding imsi will be returned
     * When calling this method, the permission check should have already been done to allow
     *   only privileged read
     *
     * @return imsi
     */
    public String getImsiPrivileged(int subId) {
        try (Cursor cursor = mContext.getContentResolver().query(
                SubscriptionManager.CONTENT_URI, null,
                SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID + "=?",
                new String[] {String.valueOf(subId)}, null)) {
            String imsi = null;
            if (cursor != null) {
                if (cursor.moveToNext()) {
                    imsi = getOptionalStringFromCursor(cursor, SubscriptionManager.IMSI,
                            /*defaultVal*/ null);
                }
            } else {
                logd("getImsiPrivileged: failed to retrieve imsi.");
            }

            return imsi;
        }
    }

    /**
     * Set ISO country code by subscription ID
     * @param iso iso country code associated with the subscription
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    public int setCountryIso(String iso, int subId) {
        if (DBG) logd("[setCountryIso]+ iso:" + iso + " subId:" + subId);
        ContentValues value = new ContentValues();
        value.put(SubscriptionManager.ISO_COUNTRY_CODE, iso);

        int result = mContext.getContentResolver().update(
                SubscriptionManager.getUriForSubscriptionId(subId), value, null, null);

        // Refresh the Cache of Active Subscription Info List
        refreshCachedActiveSubscriptionInfoList();

        notifySubscriptionInfoChanged();
        return result;
    }

    @Override
    public int getSlotIndex(int subId) {
        if (VDBG) printStackTrace("[getSlotIndex] subId=" + subId);

        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            subId = getDefaultSubId();
        }
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            if (DBG) logd("[getSlotIndex]- subId invalid");
            return SubscriptionManager.INVALID_SIM_SLOT_INDEX;
        }

        int size = sSlotIndexToSubIds.size();

        if (size == 0) {
            if (DBG) logd("[getSlotIndex]- size == 0, return SIM_NOT_INSERTED instead");
            return SubscriptionManager.SIM_NOT_INSERTED;
        }

        for (Entry<Integer, ArrayList<Integer>> entry : sSlotIndexToSubIds.entrySet()) {
            int sim = entry.getKey();
            ArrayList<Integer> subs = entry.getValue();

            if (subs != null && subs.contains(subId)) {
                if (VDBG) logv("[getSlotIndex]- return = " + sim);
                return sim;
            }
        }

        if (DBG) logd("[getSlotIndex]- return fail");
        return SubscriptionManager.INVALID_SIM_SLOT_INDEX;
    }

    /**
     * Return the subId for specified slot Id.
     * @deprecated
     */
    @UnsupportedAppUsage
    @Override
    @Deprecated
    public int[] getSubId(int slotIndex) {
        if (VDBG) printStackTrace("[getSubId]+ slotIndex=" + slotIndex);

        // Map default slotIndex to the current default subId.
        // TODO: Not used anywhere sp consider deleting as it's somewhat nebulous
        // as a slot maybe used for multiple different type of "connections"
        // such as: voice, data and sms. But we're doing the best we can and using
        // getDefaultSubId which makes a best guess.
        if (slotIndex == SubscriptionManager.DEFAULT_SIM_SLOT_INDEX) {
            slotIndex = getSlotIndex(getDefaultSubId());
            if (VDBG) logd("[getSubId] map default slotIndex=" + slotIndex);
        }

        // Check that we have a valid slotIndex or the slotIndex is for a remote SIM (remote SIM
        // uses special slot index that may be invalid otherwise)
        if (!SubscriptionManager.isValidSlotIndex(slotIndex)
                && slotIndex != SubscriptionManager.SLOT_INDEX_FOR_REMOTE_SIM_SUB) {
            if (DBG) logd("[getSubId]- invalid slotIndex=" + slotIndex);
            return null;
        }

        // Check if we've got any SubscriptionInfo records using slotIndexToSubId as a surrogate.
        int size = sSlotIndexToSubIds.size();
        if (size == 0) {
            if (VDBG) {
                logd("[getSubId]- sSlotIndexToSubIds.size == 0, return null slotIndex="
                        + slotIndex);
            }
            return null;
        }

        // Convert ArrayList to array
        ArrayList<Integer> subIds = sSlotIndexToSubIds.get(slotIndex);
        if (subIds != null && subIds.size() > 0) {
            int[] subIdArr = new int[subIds.size()];
            for (int i = 0; i < subIds.size(); i++) {
                subIdArr[i] = subIds.get(i);
            }
            if (VDBG) logd("[getSubId]- subIdArr=" + subIdArr);
            return subIdArr;
        } else {
            if (DBG) logd("[getSubId]- numSubIds == 0, return null slotIndex=" + slotIndex);
            return null;
        }
    }

    @UnsupportedAppUsage
    @Override
    public int getPhoneId(int subId) {
        if (VDBG) printStackTrace("[getPhoneId] subId=" + subId);
        int phoneId;

        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            subId = getDefaultSubId();
            if (DBG) logd("[getPhoneId] asked for default subId=" + subId);
        }

        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            if (VDBG) {
                logdl("[getPhoneId]- invalid subId return="
                        + SubscriptionManager.INVALID_PHONE_INDEX);
            }
            return SubscriptionManager.INVALID_PHONE_INDEX;
        }

        int size = sSlotIndexToSubIds.size();
        if (size == 0) {
            phoneId = mDefaultPhoneId;
            if (VDBG) logdl("[getPhoneId]- no sims, returning default phoneId=" + phoneId);
            return phoneId;
        }

        // FIXME: Assumes phoneId == slotIndex
        for (Entry<Integer, ArrayList<Integer>> entry: sSlotIndexToSubIds.entrySet()) {
            int sim = entry.getKey();
            ArrayList<Integer> subs = entry.getValue();

            if (subs != null && subs.contains(subId)) {
                if (VDBG) logdl("[getPhoneId]- found subId=" + subId + " phoneId=" + sim);
                return sim;
            }
        }

        phoneId = mDefaultPhoneId;
        if (VDBG) {
            logd("[getPhoneId]- subId=" + subId + " not found return default phoneId=" + phoneId);
        }
        return phoneId;

    }

    /**
     * @return the number of records cleared
     */
    @Override
    public int clearSubInfo() {
        enforceModifyPhoneState("clearSubInfo");

        // Now that all security checks passes, perform the operation as ourselves.
        final long identity = Binder.clearCallingIdentity();
        try {
            int size = sSlotIndexToSubIds.size();

            if (size == 0) {
                if (DBG) logdl("[clearSubInfo]- no simInfo size=" + size);
                return 0;
            }

            sSlotIndexToSubIds.clear();
            if (DBG) logdl("[clearSubInfo]- clear size=" + size);
            return size;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void logvl(String msg) {
        logv(msg);
        mLocalLog.log(msg);
    }

    private void logv(String msg) {
        Rlog.v(LOG_TAG, msg);
    }

    @UnsupportedAppUsage
    private void logdl(String msg) {
        logd(msg);
        mLocalLog.log(msg);
    }

    private static void slogd(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    @UnsupportedAppUsage
    private void logd(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    private void logel(String msg) {
        loge(msg);
        mLocalLog.log(msg);
    }

    @UnsupportedAppUsage
    private void loge(String msg) {
        Rlog.e(LOG_TAG, msg);
    }

    @UnsupportedAppUsage
    @Override
    public int getDefaultSubId() {
        int subId;
        boolean isVoiceCapable = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_voice_capable);
        if (isVoiceCapable) {
            subId = getDefaultVoiceSubId();
            if (VDBG) logdl("[getDefaultSubId] isVoiceCapable subId=" + subId);
        } else {
            subId = getDefaultDataSubId();
            if (VDBG) logdl("[getDefaultSubId] NOT VoiceCapable subId=" + subId);
        }
        if (!isActiveSubId(subId)) {
            subId = mDefaultFallbackSubId;
            if (VDBG) logdl("[getDefaultSubId] NOT active use fall back subId=" + subId);
        }
        if (VDBG) logv("[getDefaultSubId]- value = " + subId);
        return subId;
    }

    @UnsupportedAppUsage
    @Override
    public void setDefaultSmsSubId(int subId) {
        enforceModifyPhoneState("setDefaultSmsSubId");

        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            throw new RuntimeException("setDefaultSmsSubId called with DEFAULT_SUB_ID");
        }
        if (DBG) logdl("[setDefaultSmsSubId] subId=" + subId);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_SMS_SUBSCRIPTION, subId);
        broadcastDefaultSmsSubIdChanged(subId);
    }

    private void broadcastDefaultSmsSubIdChanged(int subId) {
        // Broadcast an Intent for default sms sub change
        if (DBG) logdl("[broadcastDefaultSmsSubIdChanged] subId=" + subId);
        Intent intent = new Intent(SubscriptionManager.ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    @UnsupportedAppUsage
    @Override
    public int getDefaultSmsSubId() {
        int subId = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_SMS_SUBSCRIPTION,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        if (VDBG) logd("[getDefaultSmsSubId] subId=" + subId);
        return subId;
    }

    @UnsupportedAppUsage
    @Override
    public void setDefaultVoiceSubId(int subId) {
        enforceModifyPhoneState("setDefaultVoiceSubId");

        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            throw new RuntimeException("setDefaultVoiceSubId called with DEFAULT_SUB_ID");
        }
        if (DBG) logdl("[setDefaultVoiceSubId] subId=" + subId);

        int previousDefaultSub = getDefaultSubId();

        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_VOICE_CALL_SUBSCRIPTION, subId);
        broadcastDefaultVoiceSubIdChanged(subId);

        PhoneAccountHandle newHandle =
                subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID
                        ? null : mTelephonyManager.getPhoneAccountHandleForSubscriptionId(
                        subId);

        TelecomManager telecomManager = mContext.getSystemService(TelecomManager.class);
        PhoneAccountHandle currentHandle = telecomManager.getUserSelectedOutgoingPhoneAccount();

        if (!Objects.equals(currentHandle, newHandle)) {
            telecomManager.setUserSelectedOutgoingPhoneAccount(newHandle);
            logd("[setDefaultVoiceSubId] change to phoneAccountHandle=" + newHandle);
        } else {
            logd("[setDefaultVoiceSubId] default phone account not changed");
        }

        if (previousDefaultSub != getDefaultSubId()) {
            sendDefaultChangedBroadcast(getDefaultSubId());
        }
    }

    /**
     * Broadcast intent of ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED.
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public void broadcastDefaultVoiceSubIdChanged(int subId) {
        // Broadcast an Intent for default voice sub change
        if (DBG) logdl("[broadcastDefaultVoiceSubIdChanged] subId=" + subId);
        Intent intent = new Intent(TelephonyIntents.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    @UnsupportedAppUsage
    @Override
    public int getDefaultVoiceSubId() {
        int subId = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_VOICE_CALL_SUBSCRIPTION,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        if (VDBG) slogd("[getDefaultVoiceSubId] subId=" + subId);
        return subId;
    }

    @UnsupportedAppUsage
    @Override
    public int getDefaultDataSubId() {
        int subId = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        if (VDBG) logd("[getDefaultDataSubId] subId= " + subId);
        return subId;
    }

    @UnsupportedAppUsage
    @Override
    public void setDefaultDataSubId(int subId) {
        enforceModifyPhoneState("setDefaultDataSubId");

        final long identity = Binder.clearCallingIdentity();
        try {
            if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
                throw new RuntimeException("setDefaultDataSubId called with DEFAULT_SUB_ID");
            }

            ProxyController proxyController = ProxyController.getInstance();
            int len = sPhones.length;
            logdl("[setDefaultDataSubId] num phones=" + len + ", subId=" + subId);

            if (SubscriptionManager.isValidSubscriptionId(subId)) {
                // Only re-map modems if the new default data sub is valid
                RadioAccessFamily[] rafs = new RadioAccessFamily[len];
                boolean atLeastOneMatch = false;
                for (int phoneId = 0; phoneId < len; phoneId++) {
                    Phone phone = sPhones[phoneId];
                    int raf;
                    int id = phone.getSubId();
                    if (id == subId) {
                        // TODO Handle the general case of N modems and M subscriptions.
                        raf = proxyController.getMaxRafSupported();
                        atLeastOneMatch = true;
                    } else {
                        // TODO Handle the general case of N modems and M subscriptions.
                        raf = proxyController.getMinRafSupported();
                    }
                    logdl("[setDefaultDataSubId] phoneId=" + phoneId + " subId=" + id + " RAF="
                            + raf);
                    rafs[phoneId] = new RadioAccessFamily(phoneId, raf);
                }
                if (atLeastOneMatch) {
                    proxyController.setRadioCapability(rafs);
                } else {
                    if (DBG) logdl("[setDefaultDataSubId] no valid subId's found - not updating.");
                }
            }

            // FIXME is this still needed?
            updateAllDataConnectionTrackers();

            int previousDefaultSub = getDefaultSubId();
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION, subId);
            MultiSimSettingController.getInstance().notifyDefaultDataSubChanged();
            broadcastDefaultDataSubIdChanged(subId);
            if (previousDefaultSub != getDefaultSubId()) {
                sendDefaultChangedBroadcast(getDefaultSubId());
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @UnsupportedAppUsage
    private void updateAllDataConnectionTrackers() {
        // Tell Phone Proxies to update data connection tracker
        int len = sPhones.length;
        if (DBG) logd("[updateAllDataConnectionTrackers] sPhones.length=" + len);
        for (int phoneId = 0; phoneId < len; phoneId++) {
            if (DBG) logd("[updateAllDataConnectionTrackers] phoneId=" + phoneId);
            sPhones[phoneId].updateDataConnectionTracker();
        }
    }

    @UnsupportedAppUsage
    private void broadcastDefaultDataSubIdChanged(int subId) {
        // Broadcast an Intent for default data sub change
        if (DBG) logdl("[broadcastDefaultDataSubIdChanged] subId=" + subId);
        Intent intent = new Intent(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    /* Sets the default subscription. If only one sub is active that
     * sub is set as default subId. If two or more  sub's are active
     * the first sub is set as default subscription
     */
    @UnsupportedAppUsage
    private void setDefaultFallbackSubId(int subId, int subscriptionType) {
        if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            throw new RuntimeException("setDefaultSubId called with DEFAULT_SUB_ID");
        }
        if (DBG) {
            logdl("[setDefaultFallbackSubId] subId=" + subId + ", subscriptionType="
                    + subscriptionType);
        }
        int previousDefaultSub = getDefaultSubId();
        if (isSubscriptionForRemoteSim(subscriptionType)) {
            mDefaultFallbackSubId = subId;
            return;
        }
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            int phoneId = getPhoneId(subId);
            if (phoneId >= 0 && (phoneId < mTelephonyManager.getPhoneCount()
                    || mTelephonyManager.getSimCount() == 1)) {
                if (DBG) logdl("[setDefaultFallbackSubId] set mDefaultFallbackSubId=" + subId);
                mDefaultFallbackSubId = subId;
                // Update MCC MNC device configuration information
                String defaultMccMnc = mTelephonyManager.getSimOperatorNumericForPhone(phoneId);
                MccTable.updateMccMncConfiguration(mContext, defaultMccMnc);
            } else {
                if (DBG) {
                    logdl("[setDefaultFallbackSubId] not set invalid phoneId=" + phoneId
                            + " subId=" + subId);
                }
            }
        }
        if (previousDefaultSub != getDefaultSubId()) {
            sendDefaultChangedBroadcast(getDefaultSubId());
        }
    }

    public void sendDefaultChangedBroadcast(int subId) {
        // Broadcast an Intent for default sub change
        int phoneId = SubscriptionManager.getPhoneId(subId);
        Intent intent = new Intent(TelephonyIntents.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        SubscriptionManager.putPhoneIdAndSubIdExtra(intent, phoneId, subId);
        if (DBG) {
            logdl("[sendDefaultChangedBroadcast] broadcast default subId changed phoneId="
                    + phoneId + " subId=" + subId);
        }
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Whether a subscription is opportunistic or not.
     */
    public boolean isOpportunistic(int subId) {
        SubscriptionInfo info = getActiveSubscriptionInfo(subId, mContext.getOpPackageName());
        return (info != null) && info.isOpportunistic();
    }

    // FIXME: We need we should not be assuming phoneId == slotIndex as it will not be true
    // when there are multiple subscriptions per sim and probably for other reasons.
    @UnsupportedAppUsage
    public int getSubIdUsingPhoneId(int phoneId) {
        int[] subIds = getSubId(phoneId);
        if (subIds == null || subIds.length == 0) {
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
        return subIds[0];
    }

    /** Must be public for access from instrumentation tests. */
    @VisibleForTesting
    public List<SubscriptionInfo> getSubInfoUsingSlotIndexPrivileged(int slotIndex) {
        if (DBG) logd("[getSubInfoUsingSlotIndexPrivileged]+ slotIndex:" + slotIndex);
        if (slotIndex == SubscriptionManager.DEFAULT_SIM_SLOT_INDEX) {
            slotIndex = getSlotIndex(getDefaultSubId());
        }
        if (!SubscriptionManager.isValidSlotIndex(slotIndex)) {
            if (DBG) logd("[getSubInfoUsingSlotIndexPrivileged]- invalid slotIndex");
            return null;
        }

        Cursor cursor = mContext.getContentResolver().query(SubscriptionManager.CONTENT_URI,
                null, SubscriptionManager.SIM_SLOT_INDEX + "=?",
                new String[]{String.valueOf(slotIndex)}, null);
        ArrayList<SubscriptionInfo> subList = null;
        try {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    SubscriptionInfo subInfo = getSubInfoRecord(cursor);
                    if (subInfo != null) {
                        if (subList == null) {
                            subList = new ArrayList<SubscriptionInfo>();
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
        if (DBG) logd("[getSubInfoUsingSlotIndex]- null info return");

        return subList;
    }

    @UnsupportedAppUsage
    private void validateSubId(int subId) {
        if (DBG) logd("validateSubId subId: " + subId);
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new RuntimeException("Invalid sub id passed as parameter");
        } else if (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            throw new RuntimeException("Default sub id passed as parameter");
        }
    }

    public void updatePhonesAvailability(Phone[] phones) {
        sPhones = phones;
    }

    private synchronized ArrayList<Integer> getActiveSubIdArrayList() {
        // Clone the sub id list so it can't change out from under us while iterating
        List<Entry<Integer, ArrayList<Integer>>> simInfoList =
                new ArrayList<>(sSlotIndexToSubIds.entrySet());

        // Put the set of sub ids in slot index order
        Collections.sort(simInfoList, (x, y) -> x.getKey().compareTo(y.getKey()));

        // Collect the sub ids for each slot in turn
        ArrayList<Integer> allSubs = new ArrayList<>();
        for (Entry<Integer, ArrayList<Integer>> slot : simInfoList) {
            allSubs.addAll(slot.getValue());
        }
        return allSubs;
    }

    private boolean isSubscriptionVisible(int subId) {
        for (SubscriptionInfo info : mCacheOpportunisticSubInfoList) {
            if (info.getSubscriptionId() == subId) {
                // If group UUID is null, it's stand alone opportunistic profile. So it's visible.
                // otherwise, it's bundled opportunistic profile, and is not visible.
                return info.getGroupUuid() == null;
            }
        }

        return true;
    }

    /**
     * @return the list of subId's that are active, is never null but the length maybe 0.
     */
    @Override
    public int[] getActiveSubIdList(boolean visibleOnly) {
        List<Integer> allSubs = getActiveSubIdArrayList();

        if (visibleOnly) {
            // Grouped opportunistic subscriptions should be hidden.
            allSubs = allSubs.stream().filter(subId -> isSubscriptionVisible(subId))
                    .collect(Collectors.toList());
        }

        int[] subIdArr = new int[allSubs.size()];
        int i = 0;
        for (int sub : allSubs) {
            subIdArr[i] = sub;
            i++;
        }

        if (VDBG) {
            logdl("[getActiveSubIdList] allSubs=" + allSubs + " subIdArr.length="
                    + subIdArr.length);
        }
        return subIdArr;
    }

    @Override
    public boolean isActiveSubId(int subId, String callingPackage) {
        if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(mContext, subId, callingPackage,
              "isActiveSubId")) {
            throw new SecurityException("Requires READ_PHONE_STATE permission.");
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            return isActiveSubId(subId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @UnsupportedAppUsage
    @Deprecated // This should be moved into isActiveSubId(int, String)
    public boolean isActiveSubId(int subId) {
        boolean retVal = SubscriptionManager.isValidSubscriptionId(subId)
                && getActiveSubIdArrayList().contains(subId);

        if (VDBG) logdl("[isActiveSubId]- " + retVal);
        return retVal;
    }

    /**
     * Get the SIM state for the slot index.
     * For Remote-SIMs, this method returns {@link #IccCardConstants.State.UNKNOWN}
     * @return SIM state as the ordinal of {@See IccCardConstants.State}
     */
    @Override
    public int getSimStateForSlotIndex(int slotIndex) {
        State simState;
        String err;
        if (slotIndex < 0) {
            simState = IccCardConstants.State.UNKNOWN;
            err = "invalid slotIndex";
        } else {
            Phone phone = null;
            try {
                phone = PhoneFactory.getPhone(slotIndex);
            } catch (IllegalStateException e) {
                // ignore
            }
            if (phone == null) {
                simState = IccCardConstants.State.UNKNOWN;
                err = "phone == null";
            } else {
                IccCard icc = phone.getIccCard();
                if (icc == null) {
                    simState = IccCardConstants.State.UNKNOWN;
                    err = "icc == null";
                } else {
                    simState = icc.getState();
                    err = "";
                }
            }
        }
        if (VDBG) {
            logd("getSimStateForSlotIndex: " + err + " simState=" + simState
                    + " ordinal=" + simState.ordinal() + " slotIndex=" + slotIndex);
        }
        return simState.ordinal();
    }

    /**
     * Store properties associated with SubscriptionInfo in database
     * @param subId Subscription Id of Subscription
     * @param propKey Column name in database associated with SubscriptionInfo
     * @param propValue Value to store in DB for particular subId & column name
     *
     * @return number of rows updated.
     * @hide
     */
    @Override
    public int setSubscriptionProperty(int subId, String propKey, String propValue) {
        enforceModifyPhoneState("setSubscriptionProperty");
        final long token = Binder.clearCallingIdentity();

        try {
            validateSubId(subId);
            ContentResolver resolver = mContext.getContentResolver();
            int result = setSubscriptionPropertyIntoContentResolver(
                    subId, propKey, propValue, resolver);
            // Refresh the Cache of Active Subscription Info List
            refreshCachedActiveSubscriptionInfoList();

            return result;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static int setSubscriptionPropertyIntoContentResolver(
            int subId, String propKey, String propValue, ContentResolver resolver) {
        ContentValues value = new ContentValues();
        switch (propKey) {
            case SubscriptionManager.CB_EXTREME_THREAT_ALERT:
            case SubscriptionManager.CB_SEVERE_THREAT_ALERT:
            case SubscriptionManager.CB_AMBER_ALERT:
            case SubscriptionManager.CB_EMERGENCY_ALERT:
            case SubscriptionManager.CB_ALERT_SOUND_DURATION:
            case SubscriptionManager.CB_ALERT_REMINDER_INTERVAL:
            case SubscriptionManager.CB_ALERT_VIBRATE:
            case SubscriptionManager.CB_ALERT_SPEECH:
            case SubscriptionManager.CB_ETWS_TEST_ALERT:
            case SubscriptionManager.CB_CHANNEL_50_ALERT:
            case SubscriptionManager.CB_CMAS_TEST_ALERT:
            case SubscriptionManager.CB_OPT_OUT_DIALOG:
            case SubscriptionManager.ENHANCED_4G_MODE_ENABLED:
            case SubscriptionManager.IS_OPPORTUNISTIC:
            case SubscriptionManager.VT_IMS_ENABLED:
            case SubscriptionManager.WFC_IMS_ENABLED:
            case SubscriptionManager.WFC_IMS_MODE:
            case SubscriptionManager.WFC_IMS_ROAMING_MODE:
            case SubscriptionManager.WFC_IMS_ROAMING_ENABLED:
                value.put(propKey, Integer.parseInt(propValue));
                break;
            default:
                if (DBG) slogd("Invalid column name");
                break;
        }

        return resolver.update(SubscriptionManager.getUriForSubscriptionId(subId),
                value, null, null);
    }

    /**
     * Get properties associated with SubscriptionInfo from database
     *
     * @param subId Subscription Id of Subscription
     * @param propKey Column name in SubscriptionInfo database
     * @return Value associated with subId and propKey column in database
     */
    @Override
    public String getSubscriptionProperty(int subId, String propKey, String callingPackage) {
        if (!TelephonyPermissions.checkCallingOrSelfReadPhoneState(
                mContext, subId, callingPackage, "getSubscriptionProperty")) {
            return null;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            return getSubscriptionProperty(subId, propKey);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Get properties associated with SubscriptionInfo from database. Note this is the version
     * without permission check for telephony internal use only.
     *
     * @param subId Subscription Id of Subscription
     * @param propKey Column name in SubscriptionInfo database
     * @return Value associated with subId and propKey column in database
     */
    public String getSubscriptionProperty(int subId, String propKey) {
        String resultValue = null;
        try (Cursor cursor = mContext.getContentResolver().query(SubscriptionManager.CONTENT_URI,
                new String[]{propKey},
                SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID + "=?",
                new String[]{subId + ""}, null)) {
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    switch (propKey) {
                        case SubscriptionManager.CB_EXTREME_THREAT_ALERT:
                        case SubscriptionManager.CB_SEVERE_THREAT_ALERT:
                        case SubscriptionManager.CB_AMBER_ALERT:
                        case SubscriptionManager.CB_EMERGENCY_ALERT:
                        case SubscriptionManager.CB_ALERT_SOUND_DURATION:
                        case SubscriptionManager.CB_ALERT_REMINDER_INTERVAL:
                        case SubscriptionManager.CB_ALERT_VIBRATE:
                        case SubscriptionManager.CB_ALERT_SPEECH:
                        case SubscriptionManager.CB_ETWS_TEST_ALERT:
                        case SubscriptionManager.CB_CHANNEL_50_ALERT:
                        case SubscriptionManager.CB_CMAS_TEST_ALERT:
                        case SubscriptionManager.CB_OPT_OUT_DIALOG:
                        case SubscriptionManager.ENHANCED_4G_MODE_ENABLED:
                        case SubscriptionManager.VT_IMS_ENABLED:
                        case SubscriptionManager.WFC_IMS_ENABLED:
                        case SubscriptionManager.WFC_IMS_MODE:
                        case SubscriptionManager.WFC_IMS_ROAMING_MODE:
                        case SubscriptionManager.WFC_IMS_ROAMING_ENABLED:
                        case SubscriptionManager.IS_OPPORTUNISTIC:
                        case SubscriptionManager.GROUP_UUID:
                        case SubscriptionManager.WHITE_LISTED_APN_DATA:
                            resultValue = cursor.getInt(0) + "";
                            break;
                        case SubscriptionManager.DATA_ENABLED_OVERRIDE_RULES:
                            resultValue = cursor.getString(0);
                            break;
                        default:
                            if(DBG) logd("Invalid column name");
                            break;
                    }
                } else {
                    if(DBG) logd("Valid row not present in db");
                }
            } else {
                if(DBG) logd("Query failed");
            }
        }

        if (DBG) logd("getSubscriptionProperty Query value = " + resultValue);
        return resultValue;
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
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP,
                "Requires DUMP");
        final long token = Binder.clearCallingIdentity();
        try {
            pw.println("SubscriptionController:");
            pw.println(" mLastISubServiceRegTime=" + mLastISubServiceRegTime);
            pw.println(" defaultSubId=" + getDefaultSubId());
            pw.println(" defaultDataSubId=" + getDefaultDataSubId());
            pw.println(" defaultVoiceSubId=" + getDefaultVoiceSubId());
            pw.println(" defaultSmsSubId=" + getDefaultSmsSubId());

            pw.println(" defaultDataPhoneId=" + SubscriptionManager
                    .from(mContext).getDefaultDataPhoneId());
            pw.println(" defaultVoicePhoneId=" + SubscriptionManager.getDefaultVoicePhoneId());
            pw.println(" defaultSmsPhoneId=" + SubscriptionManager
                    .from(mContext).getDefaultSmsPhoneId());
            pw.flush();

            for (Entry<Integer, ArrayList<Integer>> entry : sSlotIndexToSubIds.entrySet()) {
                pw.println(" sSlotIndexToSubId[" + entry.getKey() + "]: subIds=" + entry);
            }
            pw.flush();
            pw.println("++++++++++++++++++++++++++++++++");

            List<SubscriptionInfo> sirl = getActiveSubscriptionInfoList(
                    mContext.getOpPackageName());
            if (sirl != null) {
                pw.println(" ActiveSubInfoList:");
                for (SubscriptionInfo entry : sirl) {
                    pw.println("  " + entry.toString());
                }
            } else {
                pw.println(" ActiveSubInfoList: is null");
            }
            pw.flush();
            pw.println("++++++++++++++++++++++++++++++++");

            sirl = getAllSubInfoList(mContext.getOpPackageName());
            if (sirl != null) {
                pw.println(" AllSubInfoList:");
                for (SubscriptionInfo entry : sirl) {
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
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Migrating Ims settings from global setting to subscription DB, if not already done.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public void migrateImsSettings() {
        migrateImsSettingHelper(
                Settings.Global.ENHANCED_4G_MODE_ENABLED,
                SubscriptionManager.ENHANCED_4G_MODE_ENABLED);
        migrateImsSettingHelper(
                Settings.Global.VT_IMS_ENABLED,
                SubscriptionManager.VT_IMS_ENABLED);
        migrateImsSettingHelper(
                Settings.Global.WFC_IMS_ENABLED,
                SubscriptionManager.WFC_IMS_ENABLED);
        migrateImsSettingHelper(
                Settings.Global.WFC_IMS_MODE,
                SubscriptionManager.WFC_IMS_MODE);
        migrateImsSettingHelper(
                Settings.Global.WFC_IMS_ROAMING_MODE,
                SubscriptionManager.WFC_IMS_ROAMING_MODE);
        migrateImsSettingHelper(
                Settings.Global.WFC_IMS_ROAMING_ENABLED,
                SubscriptionManager.WFC_IMS_ROAMING_ENABLED);
    }

    private void migrateImsSettingHelper(String settingGlobal, String subscriptionProperty) {
        ContentResolver resolver = mContext.getContentResolver();
        int defaultSubId = getDefaultVoiceSubId();
        if (defaultSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return;
        }
        try {
            int prevSetting = Settings.Global.getInt(resolver, settingGlobal);

            if (prevSetting != DEPRECATED_SETTING) {
                // Write previous setting into Subscription DB.
                setSubscriptionPropertyIntoContentResolver(defaultSubId, subscriptionProperty,
                        Integer.toString(prevSetting), resolver);
                // Write global setting value with DEPRECATED_SETTING making sure
                // migration only happen once.
                Settings.Global.putInt(resolver, settingGlobal, DEPRECATED_SETTING);
            }
        } catch (Settings.SettingNotFoundException e) {
        }
    }

    /**
     * Set whether a subscription is opportunistic.
     *
     * Throws SecurityException if doesn't have required permission.
     *
     * @param opportunistic whether it’s opportunistic subscription.
     * @param subId the unique SubscriptionInfo index in database
     * @param callingPackage The package making the IPC.
     * @return the number of records updated
     */
    @Override
    public int setOpportunistic(boolean opportunistic, int subId, String callingPackage) {
        try {
            TelephonyPermissions.enforceCallingOrSelfModifyPermissionOrCarrierPrivilege(
                    mContext, subId, callingPackage);
        } catch (SecurityException e) {
            // The subscription may be inactive eSIM profile. If so, check the access rule in
            // database.
            enforceCarrierPrivilegeOnInactiveSub(subId, callingPackage,
                    "Caller requires permission on sub " + subId);
        }

        long token = Binder.clearCallingIdentity();
        try {
            int ret = setSubscriptionProperty(subId, SubscriptionManager.IS_OPPORTUNISTIC,
                    String.valueOf(opportunistic ? 1 : 0));

            if (ret != 0) notifySubscriptionInfoChanged();

            return ret;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Get subscription info from database, and check whether caller has carrier privilege
     * permission with it. If checking fails, throws SecurityException.
     */
    private void enforceCarrierPrivilegeOnInactiveSub(int subId, String callingPackage,
            String message) {
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);

        SubscriptionManager subManager = (SubscriptionManager)
                mContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        List<SubscriptionInfo> subInfo = getSubInfo(
                SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID + "=" + subId, null);

        try {
            if (!isActiveSubId(subId) && subInfo != null && subInfo.size() == 1
                    && subManager.canManageSubscription(subInfo.get(0), callingPackage)) {
                return;
            }
            throw new SecurityException(message);
        } catch (IllegalArgumentException e) {
            // canManageSubscription will throw IllegalArgumentException if sub is not embedded
            // or package name is unknown. In this case, we also see it as permission check failure
            // and throw a SecurityException.
            throw new SecurityException(message);
        }
    }

    @Override
    public void setPreferredDataSubscriptionId(int subId, boolean needValidation,
            ISetOpportunisticDataCallback callback) {
        enforceModifyPhoneState("setPreferredDataSubscriptionId");
        final long token = Binder.clearCallingIdentity();

        try {
            PhoneSwitcher.getInstance().trySetOpportunisticDataSubscription(
                    subId, needValidation, callback);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public int getPreferredDataSubscriptionId() {
        enforceReadPrivilegedPhoneState("getPreferredDataSubscriptionId");
        final long token = Binder.clearCallingIdentity();

        try {
            return PhoneSwitcher.getInstance().getOpportunisticDataSubscriptionId();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public List<SubscriptionInfo> getOpportunisticSubscriptions(String callingPackage) {
        return getSubscriptionInfoListFromCacheHelper(
                callingPackage, mCacheOpportunisticSubInfoList);
    }

    /**
     * Inform SubscriptionManager that subscriptions in the list are bundled
     * as a group. Typically it's a primary subscription and an opportunistic
     * subscription. It should only affect multi-SIM scenarios where primary
     * and opportunistic subscriptions can be activated together.
     * Being in the same group means they might be activated or deactivated
     * together, some of them may be invisible to the users, etc.
     *
     * Caller will either have {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     * permission or had carrier privilege permission on the subscriptions:
     * {@link TelephonyManager#hasCarrierPrivileges(int)} or
     * {@link SubscriptionManager#canManageSubscription(SubscriptionInfo)}
     *
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     * @throws IllegalArgumentException if the some subscriptions in the list doesn't exist.
     *
     * @param subIdList list of subId that will be in the same group
     * @return groupUUID a UUID assigned to the subscription group. It returns
     * null if fails.
     *
     */
    @Override
    public ParcelUuid createSubscriptionGroup(int[] subIdList, String callingPackage) {
        if (subIdList == null || subIdList.length == 0) {
            throw new IllegalArgumentException("Invalid subIdList " + subIdList);
        }

        // Makes sure calling package matches caller UID.
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        // If it doesn't have modify phone state permission, or carrier privilege permission,
        // a SecurityException will be thrown.
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
                != PERMISSION_GRANTED && !checkCarrierPrivilegeOnSubList(
                        subIdList, callingPackage)) {
            throw new SecurityException("CreateSubscriptionGroup needs MODIFY_PHONE_STATE or"
                    + " carrier privilege permission on all specified subscriptions");
        }

        long identity = Binder.clearCallingIdentity();

        try {
            // Generate a UUID.
            ParcelUuid groupUUID = new ParcelUuid(UUID.randomUUID());

            ContentValues value = new ContentValues();
            value.put(SubscriptionManager.GROUP_UUID, groupUUID.toString());
            value.put(SubscriptionManager.GROUP_OWNER, callingPackage);
            int result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI,
                    value, getSelectionForSubIdList(subIdList), null);

            if (DBG) logdl("createSubscriptionGroup update DB result: " + result);

            refreshCachedActiveSubscriptionInfoList();

            notifySubscriptionInfoChanged();

            MultiSimSettingController.getInstance().notifySubscriptionGroupChanged(groupUUID);

            return groupUUID;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private String getOwnerPackageOfSubGroup(ParcelUuid groupUuid) {
        if (groupUuid == null) return null;

        List<SubscriptionInfo> infoList = getSubInfo(SubscriptionManager.GROUP_UUID
                + "=\'" + groupUuid.toString() + "\'", null);

        return ArrayUtils.isEmpty(infoList) ? null : infoList.get(0).getGroupOwner();
    }

    /**
     * @param groupUuid a UUID assigned to the subscription group.
     * @param callingPackage the package making the IPC.
     * @return if callingPackage has carrier privilege on sublist.
     *
     */
    public boolean canPackageManageGroup(ParcelUuid groupUuid, String callingPackage) {
        if (groupUuid == null) {
            throw new IllegalArgumentException("Invalid groupUuid");
        }

        if (TextUtils.isEmpty(callingPackage)) {
            throw new IllegalArgumentException("Empty callingPackage");
        }

        List<SubscriptionInfo> infoList;

        // Getting all subscriptions in the group.
        long identity = Binder.clearCallingIdentity();
        try {
            infoList = getSubInfo(SubscriptionManager.GROUP_UUID
                    + "=\'" + groupUuid.toString() + "\'", null);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        // If the group does not exist, then by default the UUID is up for grabs so no need to
        // restrict management of a group (that someone may be attempting to create).
        if (ArrayUtils.isEmpty(infoList)) {
            return true;
        }

        // If the calling package is the group owner, skip carrier permission check and return
        // true as it was done before.
        if (callingPackage.equals(infoList.get(0).getGroupOwner())) return true;

        // Check carrier privilege for all subscriptions in the group.
        int[] subIdArray = infoList.stream().mapToInt(info -> info.getSubscriptionId())
                .toArray();
        return (checkCarrierPrivilegeOnSubList(subIdArray, callingPackage));
    }

    private int updateGroupOwner(ParcelUuid groupUuid, String groupOwner) {
        // If the existing group owner is different from current caller, make caller the new
        // owner of all subscriptions in group.
        // This is for use-case of:
        // 1) Both package1 and package2 has permission (MODIFY_PHONE_STATE or carrier
        // privilege permission) of all related subscriptions.
        // 2) Package 1 created a group.
        // 3) Package 2 wants to add a subscription into it.
        // Step 3 should be granted as all operations are permission based. Which means as
        // long as the package passes the permission check, it can modify the subscription
        // and the group. And package 2 becomes the new group owner as it's the last to pass
        // permission checks on all members.
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.GROUP_OWNER, groupOwner);
        return mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI,
                value, SubscriptionManager.GROUP_UUID + "=\"" + groupUuid + "\"", null);
    }

    @Override
    public void addSubscriptionsIntoGroup(int[] subIdList, ParcelUuid groupUuid,
            String callingPackage) {
        if (subIdList == null || subIdList.length == 0) {
            throw new IllegalArgumentException("Invalid subId list");
        }

        if (groupUuid == null || groupUuid.equals(INVALID_GROUP_UUID)) {
            throw new IllegalArgumentException("Invalid groupUuid");
        }

        // TODO: Revisit whether we need this restriction in R. There's no technical need for it,
        // but we don't want to change the API behavior at this time.
        if (getSubscriptionsInGroup(groupUuid, callingPackage).isEmpty()) {
            throw new IllegalArgumentException("Cannot add subscriptions to a non-existent group!");
        }

        // Makes sure calling package matches caller UID.
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        // If it doesn't have modify phone state permission, or carrier privilege permission,
        // a SecurityException will be thrown.
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
                != PERMISSION_GRANTED && !(checkCarrierPrivilegeOnSubList(subIdList, callingPackage)
                && canPackageManageGroup(groupUuid, callingPackage))) {
            throw new SecurityException("Requires MODIFY_PHONE_STATE or carrier privilege"
                    + " permissions on subscriptions and the group.");
        }

        long identity = Binder.clearCallingIdentity();

        try {
            if (DBG) {
                logdl("addSubscriptionsIntoGroup sub list "
                        + Arrays.toString(subIdList) + " into group " + groupUuid);
            }

            ContentValues value = new ContentValues();
            value.put(SubscriptionManager.GROUP_UUID, groupUuid.toString());
            int result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI,
                    value, getSelectionForSubIdList(subIdList), null);

            if (DBG) logdl("addSubscriptionsIntoGroup update DB result: " + result);

            if (result > 0) {
                updateGroupOwner(groupUuid, callingPackage);
                refreshCachedActiveSubscriptionInfoList();
                notifySubscriptionInfoChanged();
                MultiSimSettingController.getInstance().notifySubscriptionGroupChanged(groupUuid);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Remove a list of subscriptions from their subscription group.
     * See {@link SubscriptionManager#createSubscriptionGroup(List<Integer>)} for more details.
     *
     * Caller will either have {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     * permission or had carrier privilege permission on the subscriptions:
     * {@link TelephonyManager#hasCarrierPrivileges()} or
     * {@link SubscriptionManager#canManageSubscription(SubscriptionInfo)}
     *
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     * @throws IllegalArgumentException if the some subscriptions in the list doesn't belong
     *             the specified group.
     *
     * @param subIdList list of subId that need removing from their groups.
     *
     */
    public void removeSubscriptionsFromGroup(int[] subIdList, ParcelUuid groupUuid,
            String callingPackage) {
        if (subIdList == null || subIdList.length == 0) {
            return;
        }

        // Makes sure calling package matches caller UID.
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        // If it doesn't have modify phone state permission, or carrier privilege permission,
        // a SecurityException will be thrown. If it's due to invalid parameter or internal state,
        // it will return null.
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
                != PERMISSION_GRANTED && !(checkCarrierPrivilegeOnSubList(subIdList, callingPackage)
                && canPackageManageGroup(groupUuid, callingPackage))) {
            throw new SecurityException("removeSubscriptionsFromGroup needs MODIFY_PHONE_STATE or"
                    + " carrier privilege permission on all specified subscriptions");
        }

        long identity = Binder.clearCallingIdentity();

        try {
            List<SubscriptionInfo> subInfoList = getSubInfo(getSelectionForSubIdList(subIdList),
                    null);
            for (SubscriptionInfo info : subInfoList) {
                if (!groupUuid.equals(info.getGroupUuid())) {
                    throw new IllegalArgumentException("Subscription " + info.getSubscriptionId()
                        + " doesn't belong to group " + groupUuid);
                }
            }
            ContentValues value = new ContentValues();
            value.put(SubscriptionManager.GROUP_UUID, (String) null);
            value.put(SubscriptionManager.GROUP_OWNER, (String) null);
            int result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI,
                    value, getSelectionForSubIdList(subIdList), null);

            if (DBG) logdl("removeSubscriptionsFromGroup update DB result: " + result);

            if (result > 0) {
                updateGroupOwner(groupUuid, callingPackage);
                refreshCachedActiveSubscriptionInfoList();
                notifySubscriptionInfoChanged();
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     *  Helper function to check if the caller has carrier privilege permissions on a list of subId.
     *  The check can either be processed against access rules on currently active SIM cards, or
     *  the access rules we keep in our database for currently inactive eSIMs.
     *
     * @throws IllegalArgumentException if the some subId is invalid or doesn't exist.
     *
     *  @return true if checking passes on all subId, false otherwise.
     */
    private boolean checkCarrierPrivilegeOnSubList(int[] subIdList, String callingPackage) {
        // Check carrier privilege permission on active subscriptions first.
        // If it fails, they could be inactive. So keep them in a HashSet and later check
        // access rules in our database.
        Set<Integer> checkSubList = new HashSet<>();
        for (int subId : subIdList) {
            if (isActiveSubId(subId)) {
                if (!mTelephonyManager.hasCarrierPrivileges(subId)) {
                    return false;
                }
            } else {
                checkSubList.add(subId);
            }
        }

        if (checkSubList.isEmpty()) {
            return true;
        }

        long identity = Binder.clearCallingIdentity();

        try {
            // Check access rules for each sub info.
            SubscriptionManager subscriptionManager = (SubscriptionManager)
                    mContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            List<SubscriptionInfo> subInfoList = getSubInfo(
                    getSelectionForSubIdList(subIdList), null);

            // Didn't find all the subscriptions specified in subIdList.
            if (subInfoList == null || subInfoList.size() != subIdList.length) {
                throw new IllegalArgumentException("Invalid subInfoList.");
            }

            for (SubscriptionInfo subInfo : subInfoList) {
                if (checkSubList.contains(subInfo.getSubscriptionId())) {
                    if (subInfo.isEmbedded() && subscriptionManager.canManageSubscription(
                            subInfo, callingPackage)) {
                        checkSubList.remove(subInfo.getSubscriptionId());
                    } else {
                        return false;
                    }
                }
            }

            return checkSubList.isEmpty();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Helper function to create selection argument of a list of subId.
     * The result should be: "in (subId1, subId2, ...)".
     */
    private String getSelectionForSubIdList(int[] subId) {
        StringBuilder selection = new StringBuilder();
        selection.append(SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID);
        selection.append(" IN (");
        for (int i = 0; i < subId.length - 1; i++) {
            selection.append(subId[i] + ", ");
        }
        selection.append(subId[subId.length - 1]);
        selection.append(")");

        return selection.toString();
    }

    /**
     * Get subscriptionInfo list of subscriptions that are in the same group of given subId.
     * See {@link #createSubscriptionGroup(int[], String)} for more details.
     *
     * Caller will either have {@link android.Manifest.permission#READ_PHONE_STATE}
     * permission or had carrier privilege permission on the subscription.
     * {@link TelephonyManager#hasCarrierPrivileges(int)}
     *
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     *
     * @param groupUuid of which list of subInfo will be returned.
     * @return list of subscriptionInfo that belong to the same group, including the given
     * subscription itself. It will return an empty list if no subscription belongs to the group.
     *
     */
    @Override
    public List<SubscriptionInfo> getSubscriptionsInGroup(ParcelUuid groupUuid,
            String callingPackage) {
        long identity = Binder.clearCallingIdentity();
        List<SubscriptionInfo> subInfoList;

        try {
            subInfoList = getAllSubInfoList(mContext.getOpPackageName());
            if (groupUuid == null || subInfoList == null || subInfoList.isEmpty()) {
                return new ArrayList<>();
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        return subInfoList.stream().filter(info -> {
            if (!groupUuid.equals(info.getGroupUuid())) return false;
            int subId = info.getSubscriptionId();
            return TelephonyPermissions.checkCallingOrSelfReadPhoneState(mContext, subId,
                    callingPackage, "getSubscriptionsInGroup")
                    || (info.isEmbedded() && info.canManageSubscription(mContext, callingPackage));
        }).collect(Collectors.toList());
    }

    public ParcelUuid getGroupUuid(int subId) {
        ParcelUuid groupUuid;
        List<SubscriptionInfo> subInfo = getSubInfo(SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID
                        + "=" + subId, null);
        if (subInfo == null || subInfo.size() == 0) {
            groupUuid = null;
        } else {
            groupUuid = subInfo.get(0).getGroupUuid();
        }

        return groupUuid;
    }


    /**
     * Enable/Disable a subscription
     * @param enable true if enabling, false if disabling
     * @param subId the unique SubInfoRecord index in database
     *
     * @return true if success, false if fails or the further action is
     * needed hence it's redirected to Euicc.
     */
    @Override
    public boolean setSubscriptionEnabled(boolean enable, int subId) {
        enforceModifyPhoneState("setSubscriptionEnabled");

        final long identity = Binder.clearCallingIdentity();
        try {
            logd("setSubscriptionEnabled" + (enable ? " enable " : " disable ")
                    + " subId " + subId);

            // Error checking.
            if (!SubscriptionManager.isUsableSubscriptionId(subId)) {
                throw new IllegalArgumentException(
                        "setSubscriptionEnabled not usable subId " + subId);
            }

            SubscriptionInfo info = SubscriptionController.getInstance()
                    .getAllSubInfoList(mContext.getOpPackageName())
                    .stream()
                    .filter(subInfo -> subInfo.getSubscriptionId() == subId)
                    .findFirst()
                    .get();

            if (info == null) {
                logd("setSubscriptionEnabled subId " + subId + " doesn't exist.");
                return false;
            }

            if (info.isEmbedded()) {
                return enableEmbeddedSubscription(info, enable);
            } else {
                return enablePhysicalSubscription(info, enable);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean enableEmbeddedSubscription(SubscriptionInfo info, boolean enable) {
        // We need to send intents to Euicc for operations:

        // 1) In single SIM mode, turning on a eSIM subscription while pSIM is the active slot.
        //    Euicc will ask user to switch to DSDS if supported or to confirm SIM slot
        //    switching.
        // 2) In DSDS mode, turning on / off an eSIM profile. Euicc can ask user whether
        //    to turn on DSDS, or whether to switch from current active eSIM profile to it, or
        //    to simply show a progress dialog.
        // 3) In future, similar operations on triple SIM devices.
        enableSubscriptionOverEuiccManager(info.getSubscriptionId(), enable,
                SubscriptionManager.INVALID_SIM_SLOT_INDEX);
        // returning false to indicate state is not changed. If changed, a subscriptionInfo
        // change will be filed separately.
        return false;

        // TODO: uncomment or clean up if we decide whether to support standalone CBRS for Q.
        // subId = enable ? subId : SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        // updateEnabledSubscriptionGlobalSetting(subId, physicalSlotIndex);
    }

    private static boolean isInactiveInsertedPSim(UiccSlotInfo slotInfo, String cardId) {
        return !slotInfo.getIsEuicc() && !slotInfo.getIsActive()
                && slotInfo.getCardStateInfo() == CARD_STATE_INFO_PRESENT
                && TextUtils.equals(slotInfo.getCardId(), cardId);
    }

    private boolean enablePhysicalSubscription(SubscriptionInfo info, boolean enable) {
        if (enable && info.getSimSlotIndex() == SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
            UiccSlotInfo[] slotsInfo = mTelephonyManager.getUiccSlotsInfo();
            if (slotsInfo == null) return false;
            boolean foundMatch = false;
            for (int i = 0; i < slotsInfo.length; i++) {
                UiccSlotInfo slotInfo = slotsInfo[i];
                if (isInactiveInsertedPSim(slotInfo, info.getCardString())) {
                    // We need to send intents to Euicc if we are turning on an inactive pSIM.
                    // Euicc will decide whether to ask user to switch to DSDS, or change SIM
                    // slot mapping.
                    enableSubscriptionOverEuiccManager(info.getSubscriptionId(), enable, i);
                    foundMatch = true;
                    break;
                }
            }

            if (!foundMatch) {
                logdl("enablePhysicalSubscription subId " + info.getSubscriptionId()
                        + " is not inserted.");
            }
            // returning false to indicate state is not changed yet. If intent is sent to LPA and
            // user consents switching, caller needs to listen to subscription info change.
            return false;
        } else {
            return mTelephonyManager.enableModemForSlot(info.getSimSlotIndex(), enable);
        }

        // TODO: uncomment or clean up if we decide whether to support standalone CBRS for Q.
        // updateEnabledSubscriptionGlobalSetting(
        //        enable ? subId : SubscriptionManager.INVALID_SUBSCRIPTION_ID,
        //        physicalSlotIndex);
        // updateModemStackEnabledGlobalSetting(enable, physicalSlotIndex);
        // refreshCachedActiveSubscriptionInfoList();
    }

    private void enableSubscriptionOverEuiccManager(int subId, boolean enable,
            int physicalSlotIndex) {
        logdl("enableSubscriptionOverEuiccManager" + (enable ? " enable " : " disable ")
                + "subId " + subId + " on slotIndex " + physicalSlotIndex);
        Intent intent = new Intent(EuiccManager.ACTION_TOGGLE_SUBSCRIPTION_PRIVILEGED);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(EuiccManager.EXTRA_SUBSCRIPTION_ID, subId);
        intent.putExtra(EuiccManager.EXTRA_ENABLE_SUBSCRIPTION, enable);
        if (physicalSlotIndex != SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
            intent.putExtra(EuiccManager.EXTRA_PHYSICAL_SLOT_ID, physicalSlotIndex);
        }
        mContext.startActivity(intent);
    }

    private void updateEnabledSubscriptionGlobalSetting(int subId, int physicalSlotIndex) {
        // Write the value which subscription is enabled into global setting.
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.ENABLED_SUBSCRIPTION_FOR_SLOT + physicalSlotIndex, subId);
    }

    private void updateModemStackEnabledGlobalSetting(boolean enabled, int physicalSlotIndex) {
        // Write the whether a modem stack is disabled into global setting.
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.MODEM_STACK_ENABLED_FOR_SLOT
                        + physicalSlotIndex, enabled ? 1 : 0);
    }

    private int getPhysicalSlotIndex(boolean isEmbedded, int subId) {
        UiccSlotInfo[] slotInfos = mTelephonyManager.getUiccSlotsInfo();
        int logicalSlotIndex = getSlotIndex(subId);
        int physicalSlotIndex = SubscriptionManager.INVALID_SIM_SLOT_INDEX;
        boolean isLogicalSlotIndexValid = SubscriptionManager.isValidSlotIndex(logicalSlotIndex);

        for (int i = 0; i < slotInfos.length; i++) {
            // If we can know the logicalSlotIndex from subId, we should find the exact matching
            // physicalSlotIndex. However for some cases like inactive eSIM, the logicalSlotIndex
            // will be -1. In this case, we assume there's only one eSIM, and return the
            // physicalSlotIndex of that eSIM.
            if ((isLogicalSlotIndexValid && slotInfos[i].getLogicalSlotIdx() == logicalSlotIndex)
                    || (!isLogicalSlotIndexValid && slotInfos[i].getIsEuicc() && isEmbedded)) {
                physicalSlotIndex = i;
                break;
            }
        }

        return physicalSlotIndex;
    }

    private int getPhysicalSlotIndexFromLogicalSlotIndex(int logicalSlotIndex) {
        int physicalSlotIndex = SubscriptionManager.INVALID_SIM_SLOT_INDEX;
        UiccSlotInfo[] slotInfos = mTelephonyManager.getUiccSlotsInfo();
        for (int i = 0; i < slotInfos.length; i++) {
            if (slotInfos[i].getLogicalSlotIdx() == logicalSlotIndex) {
                physicalSlotIndex = i;
                break;
            }
        }

        return physicalSlotIndex;
    }

    @Override
    public boolean isSubscriptionEnabled(int subId) {
        // TODO: b/123314365 support multi-eSIM and removable eSIM.
        enforceReadPrivilegedPhoneState("isSubscriptionEnabled");

        long identity = Binder.clearCallingIdentity();
        try {
            // Error checking.
            if (!SubscriptionManager.isUsableSubscriptionId(subId)) {
                throw new IllegalArgumentException(
                        "isSubscriptionEnabled not usable subId " + subId);
            }

            List<SubscriptionInfo> infoList = getSubInfo(
                    SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID + "=" + subId, null);
            if (infoList == null || infoList.isEmpty()) {
                // Subscription doesn't exist.
                return false;
            }

            boolean isEmbedded = infoList.get(0).isEmbedded();

            if (isEmbedded) {
                return isActiveSubId(subId);
            } else {
                // For pSIM, we also need to check if modem is disabled or not.
                return isActiveSubId(subId) && PhoneConfigurationManager.getInstance()
                        .getPhoneStatus(PhoneFactory.getPhone(getPhoneId(subId)));
            }

        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public int getEnabledSubscriptionId(int logicalSlotIndex) {
        // TODO: b/123314365 support multi-eSIM and removable eSIM.
        enforceReadPrivilegedPhoneState("getEnabledSubscriptionId");

        long identity = Binder.clearCallingIdentity();
        try {
            if (!SubscriptionManager.isValidPhoneId(logicalSlotIndex)) {
                throw new IllegalArgumentException(
                        "getEnabledSubscriptionId with invalid logicalSlotIndex "
                                + logicalSlotIndex);
            }

            // Getting and validating the physicalSlotIndex.
            int physicalSlotIndex = getPhysicalSlotIndexFromLogicalSlotIndex(logicalSlotIndex);
            if (physicalSlotIndex == SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
                return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
            }

            // if modem stack is disabled, return INVALID_SUBSCRIPTION_ID without reading
            // Settings.Global.ENABLED_SUBSCRIPTION_FOR_SLOT.
            int modemStackEnabled = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.MODEM_STACK_ENABLED_FOR_SLOT + physicalSlotIndex, 1);
            if (modemStackEnabled != 1) {
                return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
            }

            int subId;
            try {
                subId = Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.ENABLED_SUBSCRIPTION_FOR_SLOT + physicalSlotIndex);
            } catch (Settings.SettingNotFoundException e) {
                // Value never set. Return whether it's currently active.
                subId = getSubIdUsingPhoneId(logicalSlotIndex);
            }

            return subId;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    // Helper function of getOpportunisticSubscriptions and getActiveSubscriptionInfoList.
    // They are doing similar things except operating on different cache.
    private List<SubscriptionInfo> getSubscriptionInfoListFromCacheHelper(
            String callingPackage, List<SubscriptionInfo> cacheSubList) {
        boolean canReadAllPhoneState;
        try {
            canReadAllPhoneState = TelephonyPermissions.checkReadPhoneState(mContext,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID, Binder.getCallingPid(),
                    Binder.getCallingUid(), callingPackage, "getSubscriptionInfoList");
        } catch (SecurityException e) {
            canReadAllPhoneState = false;
        }

        synchronized (mSubInfoListLock) {
            // If the caller can read all phone state, just return the full list.
            if (canReadAllPhoneState) {
                return new ArrayList<>(cacheSubList);
            }

            // Filter the list to only include subscriptions which the caller can manage.
            return cacheSubList.stream()
                    .filter(subscriptionInfo -> {
                        try {
                            return TelephonyPermissions.checkCallingOrSelfReadPhoneState(mContext,
                                    subscriptionInfo.getSubscriptionId(), callingPackage,
                                    "getSubscriptionInfoList");
                        } catch (SecurityException e) {
                            return false;
                        }
                    })
                    .collect(Collectors.toList());
        }
    }

    private synchronized boolean addToSubIdList(int slotIndex, int subId, int subscriptionType) {
        ArrayList<Integer> subIdsList = sSlotIndexToSubIds.get(slotIndex);
        if (subIdsList == null) {
            subIdsList = new ArrayList<>();
            sSlotIndexToSubIds.put(slotIndex, subIdsList);
        }

        // add the given subId unless it already exists
        if (subIdsList.contains(subId)) {
            logdl("slotIndex, subId combo already exists in the map. Not adding it again.");
            return false;
        }
        if (isSubscriptionForRemoteSim(subscriptionType)) {
            // For Remote SIM subscriptions, a slot can have multiple subscriptions.
            subIdsList.add(subId);
        } else {
            // for all other types of subscriptions, a slot can have only one subscription at a time
            subIdsList.clear();
            subIdsList.add(subId);
        }
        if (DBG) logdl("slotIndex, subId combo is added to the map.");
        return true;
    }

    private boolean isSubscriptionForRemoteSim(int subscriptionType) {
        return subscriptionType == SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM;
    }

    /**
     * This is only for testing
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public Map<Integer, ArrayList<Integer>> getSlotIndexToSubIdsMap() {
        return sSlotIndexToSubIds;
    }

    /**
     * This is only for testing
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public void resetStaticMembers() {
        mDefaultFallbackSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        mDefaultPhoneId = SubscriptionManager.DEFAULT_PHONE_INDEX;
    }

    private void notifyOpportunisticSubscriptionInfoChanged() {
        ITelephonyRegistry tr = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                "telephony.registry"));
        try {
            if (DBG) logd("notifyOpptSubscriptionInfoChanged:");
            tr.notifyOpportunisticSubscriptionInfoChanged();
        } catch (RemoteException ex) {
            // Should never happen because its always available.
        }
    }

    private boolean refreshCachedOpportunisticSubscriptionInfoList() {
        synchronized (mSubInfoListLock) {
            List<SubscriptionInfo> oldOpptCachedList = mCacheOpportunisticSubInfoList;

            List<SubscriptionInfo> subList = getSubInfo(
                    SubscriptionManager.IS_OPPORTUNISTIC + "=1 AND ("
                            + SubscriptionManager.SIM_SLOT_INDEX + ">=0 OR "
                            + SubscriptionManager.IS_EMBEDDED + "=1)", null);

            if (subList != null) {
                subList.sort(SUBSCRIPTION_INFO_COMPARATOR);
            } else {
                subList = new ArrayList<>();
            }

            mCacheOpportunisticSubInfoList = subList;

            for (SubscriptionInfo info : mCacheOpportunisticSubInfoList) {
                if (shouldDisableSubGroup(info.getGroupUuid())) {
                    info.setGroupDisabled(true);
                    // TODO: move it to ONS.
                    if (isActiveSubId(info.getSubscriptionId()) && isSubInfoReady()) {
                        logd("[refreshCachedOpportunisticSubscriptionInfoList] "
                                + "Deactivating grouped opportunistic subscription "
                                + info.getSubscriptionId());
                        deactivateSubscription(info);
                    }
                }
            }

            if (DBG_CACHE) {
                if (!mCacheOpportunisticSubInfoList.isEmpty()) {
                    for (SubscriptionInfo si : mCacheOpportunisticSubInfoList) {
                        logd("[refreshCachedOpptSubscriptionInfoList] Setting Cached info="
                                + si);
                    }
                } else {
                    logdl("[refreshCachedOpptSubscriptionInfoList]- no info return");
                }
            }

            return !oldOpptCachedList.equals(mCacheOpportunisticSubInfoList);
        }
    }

    private boolean shouldDisableSubGroup(ParcelUuid groupUuid) {
        if (groupUuid == null) return false;

        for (SubscriptionInfo activeInfo : mCacheActiveSubInfoList) {
            if (!activeInfo.isOpportunistic() && groupUuid.equals(activeInfo.getGroupUuid())) {
                return false;
            }
        }

        return true;
    }

    private void deactivateSubscription(SubscriptionInfo info) {
        // TODO: b/120439488 deactivate pSIM.
        if (info.isEmbedded()) {
            logd("[deactivateSubscription] eSIM profile " + info.getSubscriptionId());
            EuiccManager euiccManager = (EuiccManager)
                    mContext.getSystemService(Context.EUICC_SERVICE);
            euiccManager.switchToSubscription(SubscriptionManager.INVALID_SUBSCRIPTION_ID,
                    PendingIntent.getService(mContext, 0, new Intent(), 0));
        }
    }

    // TODO: This method should belong to Telephony manager like other data enabled settings and
    // override APIs. Remove this once TelephonyManager API is added.
    @Override
    public boolean setAlwaysAllowMmsData(int subId, boolean alwaysAllow) {
        if (DBG) logd("[setAlwaysAllowMmsData]+ alwaysAllow:" + alwaysAllow + " subId:" + subId);

        enforceModifyPhoneState("setAlwaysAllowMmsData");

        // Now that all security checks passes, perform the operation as ourselves.
        final long identity = Binder.clearCallingIdentity();
        try {
            validateSubId(subId);
            Phone phone = PhoneFactory.getPhone(getPhoneId(subId));
            if (phone == null) return false;
            return phone.getDataEnabledSettings().setAlwaysAllowMmsData(alwaysAllow);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Set allowing mobile data during voice call.
     *
     * @param subId Subscription index
     * @param rules Data enabled override rules in string format. See {@link DataEnabledOverride}
     * for details.
     * @return {@code true} if settings changed, otherwise {@code false}.
     */
    public boolean setDataEnabledOverrideRules(int subId, @NonNull String rules) {
        if (DBG) logd("[setDataEnabledOverrideRules]+ rules:" + rules + " subId:" + subId);

        validateSubId(subId);
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.DATA_ENABLED_OVERRIDE_RULES, rules);

        boolean result = databaseUpdateHelper(value, subId, true) > 0;

        if (result) {
            // Refresh the Cache of Active Subscription Info List
            refreshCachedActiveSubscriptionInfoList();
            notifySubscriptionInfoChanged();
        }

        return result;
    }

    /**
     * Get data enabled override rules.
     *
     * @param subId Subscription index
     * @return Data enabled override rules in string
     */
    @NonNull
    public String getDataEnabledOverrideRules(int subId) {
        return TextUtils.emptyIfNull(getSubscriptionProperty(subId,
                SubscriptionManager.DATA_ENABLED_OVERRIDE_RULES));
    }
}
