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

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.app.ActivityManager;
import android.app.UserSwitchObserver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.IPackageManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.provider.Settings.SettingNotFoundException;
import android.service.carrier.CarrierIdentifier;
import android.service.carrier.CarrierService;
import android.service.euicc.EuiccProfileInfo;
import android.service.euicc.EuiccService;
import android.service.euicc.GetEuiccProfileInfoListResult;
import android.telephony.CarrierConfigManager;
import android.telephony.Rlog;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.UiccAccessRule;
import android.telephony.euicc.EuiccManager;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.euicc.EuiccController;
import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.UiccSlot;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 *@hide
 */
public class SubscriptionInfoUpdater extends Handler {
    private static final String LOG_TAG = "SubscriptionInfoUpdater";
    @UnsupportedAppUsage
    private static final int PROJECT_SIM_NUM = TelephonyManager.getDefault().getPhoneCount();

    private static final boolean DBG = true;

    private static final int EVENT_INVALID = -1;
    private static final int EVENT_GET_NETWORK_SELECTION_MODE_DONE = 2;
    private static final int EVENT_SIM_LOADED = 3;
    private static final int EVENT_SIM_ABSENT = 4;
    private static final int EVENT_SIM_LOCKED = 5;
    private static final int EVENT_SIM_IO_ERROR = 6;
    private static final int EVENT_SIM_UNKNOWN = 7;
    private static final int EVENT_SIM_RESTRICTED = 8;
    private static final int EVENT_SIM_NOT_READY = 9;
    private static final int EVENT_SIM_READY = 10;
    private static final int EVENT_SIM_IMSI = 11;
    private static final int EVENT_REFRESH_EMBEDDED_SUBSCRIPTIONS = 12;

    private static final String ICCID_STRING_FOR_NO_SIM = "";

    private static final ParcelUuid REMOVE_GROUP_UUID =
            ParcelUuid.fromString(CarrierConfigManager.REMOVE_GROUP_UUID_STRING);

    // Key used to read/write the current IMSI. Updated on SIM_STATE_CHANGED - LOADED.
    public static final String CURR_SUBID = "curr_subid";

    @UnsupportedAppUsage
    private static Phone[] mPhone;
    @UnsupportedAppUsage
    private static Context mContext = null;
    @UnsupportedAppUsage
    private static String mIccId[] = new String[PROJECT_SIM_NUM];
    private static int[] sSimCardState = new int[PROJECT_SIM_NUM];
    private static int[] sSimApplicationState = new int[PROJECT_SIM_NUM];
    private static boolean sIsSubInfoInitialized = false;
    private SubscriptionManager mSubscriptionManager = null;
    private EuiccManager mEuiccManager;
    @UnsupportedAppUsage
    private IPackageManager mPackageManager;
    private Handler mBackgroundHandler;

    // The current foreground user ID.
    @UnsupportedAppUsage
    private int mCurrentlyActiveUserId;
    private CarrierServiceBindHelper mCarrierServiceBindHelper;

    /**
     * Runnable with a boolean parameter. This is used in
     * updateEmbeddedSubscriptions(List<Integer> cardIds, @Nullable UpdateEmbeddedSubsCallback).
     */
    private interface UpdateEmbeddedSubsCallback {
        /**
         * Callback of the Runnable.
         * @param hasChanges Whether there is any subscription info change. If yes, we need to
         * notify the listeners.
         */
        void run(boolean hasChanges);
    }

    // TODO: The SubscriptionController instance should be passed in here from PhoneFactory
    // rather than invoking the static getter all over the place.
    public SubscriptionInfoUpdater(
            Looper looper, Context context, Phone[] phone, CommandsInterface[] ci) {
        this(looper, context, phone, ci,
                IPackageManager.Stub.asInterface(ServiceManager.getService("package")));
    }

    @VisibleForTesting public SubscriptionInfoUpdater(
            Looper looper, Context context, Phone[] phone,
            CommandsInterface[] ci, IPackageManager packageMgr) {
        logd("Constructor invoked");
        mBackgroundHandler = new Handler(looper);

        mContext = context;
        mPhone = phone;
        mSubscriptionManager = SubscriptionManager.from(mContext);
        mEuiccManager = (EuiccManager) mContext.getSystemService(Context.EUICC_SERVICE);
        mPackageManager = packageMgr;

        mCarrierServiceBindHelper = new CarrierServiceBindHelper(mContext);
        initializeCarrierApps();
    }

    private void initializeCarrierApps() {
        // Initialize carrier apps:
        // -Now (on system startup)
        // -Whenever new carrier privilege rules might change (new SIM is loaded)
        // -Whenever we switch to a new user
        mCurrentlyActiveUserId = 0;
        try {
            ActivityManager.getService().registerUserSwitchObserver(new UserSwitchObserver() {
                @Override
                public void onUserSwitching(int newUserId, IRemoteCallback reply)
                        throws RemoteException {
                    mCurrentlyActiveUserId = newUserId;
                    CarrierAppUtils.disableCarrierAppsUntilPrivileged(mContext.getOpPackageName(),
                            mPackageManager, TelephonyManager.getDefault(),
                            mContext.getContentResolver(), mCurrentlyActiveUserId);

                    if (reply != null) {
                        try {
                            reply.sendResult(null);
                        } catch (RemoteException e) {
                        }
                    }
                }
            }, LOG_TAG);
            mCurrentlyActiveUserId = ActivityManager.getService().getCurrentUser().id;
        } catch (RemoteException e) {
            logd("Couldn't get current user ID; guessing it's 0: " + e.getMessage());
        }
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(mContext.getOpPackageName(),
                mPackageManager, TelephonyManager.getDefault(), mContext.getContentResolver(),
                mCurrentlyActiveUserId);
    }

    /**
     * Update subscriptions when given a new ICC state.
     */
    public void updateInternalIccState(String simStatus, String reason, int slotId,
            boolean absentAndInactive) {
        logd("updateInternalIccState to simStatus " + simStatus + " reason " + reason
                + " slotId " + slotId);
        int message = internalIccStateToMessage(simStatus);
        if (message != EVENT_INVALID) {
            sendMessage(obtainMessage(message, slotId, absentAndInactive ? 1 : 0, reason));
        }
    }

    private int internalIccStateToMessage(String simStatus) {
        switch(simStatus) {
            case IccCardConstants.INTENT_VALUE_ICC_ABSENT: return EVENT_SIM_ABSENT;
            case IccCardConstants.INTENT_VALUE_ICC_UNKNOWN: return EVENT_SIM_UNKNOWN;
            case IccCardConstants.INTENT_VALUE_ICC_CARD_IO_ERROR: return EVENT_SIM_IO_ERROR;
            case IccCardConstants.INTENT_VALUE_ICC_CARD_RESTRICTED: return EVENT_SIM_RESTRICTED;
            case IccCardConstants.INTENT_VALUE_ICC_NOT_READY: return EVENT_SIM_NOT_READY;
            case IccCardConstants.INTENT_VALUE_ICC_LOCKED: return EVENT_SIM_LOCKED;
            case IccCardConstants.INTENT_VALUE_ICC_LOADED: return EVENT_SIM_LOADED;
            case IccCardConstants.INTENT_VALUE_ICC_READY: return EVENT_SIM_READY;
            case IccCardConstants.INTENT_VALUE_ICC_IMSI: return EVENT_SIM_IMSI;
            default:
                logd("Ignoring simStatus: " + simStatus);
                return EVENT_INVALID;
        }
    }

    @UnsupportedAppUsage
    private boolean isAllIccIdQueryDone() {
        for (int i = 0; i < PROJECT_SIM_NUM; i++) {
            UiccSlot slot = UiccController.getInstance().getUiccSlotForPhone(i);
            int slotId = UiccController.getInstance().getSlotIdFromPhoneId(i);
            if  (mIccId[i] == null || slot == null || !slot.isActive()) {
                if (mIccId[i] == null) {
                    logd("Wait for SIM " + i + " Iccid");
                } else {
                    logd(String.format("Wait for slot corresponding to phone %d to be active, "
                            + "slotId is %d", i, slotId));
                }
                return false;
            }
        }
        logd("All IccIds query complete");

        return true;
    }

    @Override
    public void handleMessage(Message msg) {
        List<Integer> cardIds = new ArrayList<>();
        switch (msg.what) {
            case EVENT_GET_NETWORK_SELECTION_MODE_DONE: {
                AsyncResult ar = (AsyncResult)msg.obj;
                Integer slotId = (Integer)ar.userObj;
                if (ar.exception == null && ar.result != null) {
                    int[] modes = (int[])ar.result;
                    if (modes[0] == 1) {  // Manual mode.
                        mPhone[slotId].setNetworkSelectionModeAutomatic(null);
                    }
                } else {
                    logd("EVENT_GET_NETWORK_SELECTION_MODE_DONE: error getting network mode.");
                }
                break;
            }

            case EVENT_SIM_LOADED:
                handleSimLoaded(msg.arg1);
                break;

            case EVENT_SIM_ABSENT:
                handleSimAbsent(msg.arg1, msg.arg2);
                break;

            case EVENT_SIM_LOCKED:
                handleSimLocked(msg.arg1, (String) msg.obj);
                break;

            case EVENT_SIM_UNKNOWN:
                broadcastSimStateChanged(msg.arg1, IccCardConstants.INTENT_VALUE_ICC_UNKNOWN, null);
                broadcastSimCardStateChanged(msg.arg1, TelephonyManager.SIM_STATE_UNKNOWN);
                broadcastSimApplicationStateChanged(msg.arg1, TelephonyManager.SIM_STATE_UNKNOWN);
                updateSubscriptionCarrierId(msg.arg1, IccCardConstants.INTENT_VALUE_ICC_UNKNOWN);
                updateCarrierServices(msg.arg1, IccCardConstants.INTENT_VALUE_ICC_UNKNOWN);
                break;

            case EVENT_SIM_IO_ERROR:
                handleSimError(msg.arg1);
                break;

            case EVENT_SIM_RESTRICTED:
                broadcastSimStateChanged(msg.arg1,
                        IccCardConstants.INTENT_VALUE_ICC_CARD_RESTRICTED,
                        IccCardConstants.INTENT_VALUE_ICC_CARD_RESTRICTED);
                broadcastSimCardStateChanged(msg.arg1, TelephonyManager.SIM_STATE_CARD_RESTRICTED);
                broadcastSimApplicationStateChanged(msg.arg1, TelephonyManager.SIM_STATE_NOT_READY);
                updateSubscriptionCarrierId(msg.arg1,
                        IccCardConstants.INTENT_VALUE_ICC_CARD_RESTRICTED);
                updateCarrierServices(msg.arg1, IccCardConstants.INTENT_VALUE_ICC_CARD_RESTRICTED);
                break;

            case EVENT_SIM_READY:
                cardIds.add(getCardIdFromPhoneId(msg.arg1));
                updateEmbeddedSubscriptions(cardIds, (hasChanges) -> {
                    if (hasChanges) {
                        SubscriptionController.getInstance().notifySubscriptionInfoChanged();
                    }
                });
                broadcastSimStateChanged(msg.arg1, IccCardConstants.INTENT_VALUE_ICC_READY, null);
                broadcastSimCardStateChanged(msg.arg1, TelephonyManager.SIM_STATE_PRESENT);
                broadcastSimApplicationStateChanged(msg.arg1, TelephonyManager.SIM_STATE_NOT_READY);
                break;

            case EVENT_SIM_IMSI:
                broadcastSimStateChanged(msg.arg1, IccCardConstants.INTENT_VALUE_ICC_IMSI, null);
                break;

            case EVENT_SIM_NOT_READY:
                // an eUICC with no active subscriptions never becomes ready, so we need to trigger
                // the embedded subscriptions update here
                cardIds.add(getCardIdFromPhoneId(msg.arg1));
                updateEmbeddedSubscriptions(cardIds, (hasChanges) -> {
                    if (hasChanges) {
                        SubscriptionController.getInstance().notifySubscriptionInfoChanged();
                    }
                });
                handleSimNotReady(msg.arg1);
                break;

            case EVENT_REFRESH_EMBEDDED_SUBSCRIPTIONS:
                cardIds.add(msg.arg1);
                Runnable r = (Runnable) msg.obj;
                updateEmbeddedSubscriptions(cardIds, (hasChanges) -> {
                    if (hasChanges) {
                        SubscriptionController.getInstance().notifySubscriptionInfoChanged();
                    }
                    if (r != null) {
                        r.run();
                    }
                });
                break;

            default:
                logd("Unknown msg:" + msg.what);
        }
    }

    private int getCardIdFromPhoneId(int phoneId) {
        UiccController uiccController = UiccController.getInstance();
        UiccCard card = uiccController.getUiccCardForPhone(phoneId);
        if (card != null) {
            return uiccController.convertToPublicCardId(card.getCardId());
        }
        return TelephonyManager.UNINITIALIZED_CARD_ID;
    }

    void requestEmbeddedSubscriptionInfoListRefresh(int cardId, @Nullable Runnable callback) {
        sendMessage(obtainMessage(
                EVENT_REFRESH_EMBEDDED_SUBSCRIPTIONS, cardId, 0 /* arg2 */, callback));
    }

    private void handleSimLocked(int slotId, String reason) {
        if (mIccId[slotId] != null && mIccId[slotId].equals(ICCID_STRING_FOR_NO_SIM)) {
            logd("SIM" + (slotId + 1) + " hot plug in");
            mIccId[slotId] = null;
        }

        String iccId = mIccId[slotId];
        if (iccId == null) {
            IccCard iccCard = mPhone[slotId].getIccCard();
            if (iccCard == null) {
                logd("handleSimLocked: IccCard null");
                return;
            }
            IccRecords records = iccCard.getIccRecords();
            if (records == null) {
                logd("handleSimLocked: IccRecords null");
                return;
            }
            if (IccUtils.stripTrailingFs(records.getFullIccId()) == null) {
                logd("handleSimLocked: IccID null");
                return;
            }
            mIccId[slotId] = IccUtils.stripTrailingFs(records.getFullIccId());
        } else {
            logd("NOT Querying IccId its already set sIccid[" + slotId + "]=" + iccId);
        }

        updateSubscriptionInfoByIccId(slotId, true /* updateEmbeddedSubs */);

        broadcastSimStateChanged(slotId, IccCardConstants.INTENT_VALUE_ICC_LOCKED, reason);
        broadcastSimCardStateChanged(slotId, TelephonyManager.SIM_STATE_PRESENT);
        broadcastSimApplicationStateChanged(slotId, getSimStateFromLockedReason(reason));
        updateSubscriptionCarrierId(slotId, IccCardConstants.INTENT_VALUE_ICC_LOCKED);
        updateCarrierServices(slotId, IccCardConstants.INTENT_VALUE_ICC_LOCKED);
    }

    private static int getSimStateFromLockedReason(String lockedReason) {
        switch (lockedReason) {
            case IccCardConstants.INTENT_VALUE_LOCKED_ON_PIN:
                return TelephonyManager.SIM_STATE_PIN_REQUIRED;
            case IccCardConstants.INTENT_VALUE_LOCKED_ON_PUK:
                return TelephonyManager.SIM_STATE_PUK_REQUIRED;
            case IccCardConstants.INTENT_VALUE_LOCKED_NETWORK:
                return TelephonyManager.SIM_STATE_NETWORK_LOCKED;
            case IccCardConstants.INTENT_VALUE_ABSENT_ON_PERM_DISABLED:
                return TelephonyManager.SIM_STATE_PERM_DISABLED;
            default:
                Rlog.e(LOG_TAG, "Unexpected SIM locked reason " + lockedReason);
                return TelephonyManager.SIM_STATE_UNKNOWN;
        }
    }

    private void handleSimNotReady(int slotId) {
        logd("handleSimNotReady: slotId: " + slotId);

        IccCard iccCard = mPhone[slotId].getIccCard();
        if (iccCard.isEmptyProfile()) {
            // ICC_NOT_READY is a terminal state for an eSIM on the boot profile. At this
            // phase, the subscription list is accessible. Treating NOT_READY
            // as equivalent to ABSENT, once the rest of the system can handle it.
            mIccId[slotId] = ICCID_STRING_FOR_NO_SIM;
            updateSubscriptionInfoByIccId(slotId, false /* updateEmbeddedSubs */);
        }

        broadcastSimStateChanged(slotId, IccCardConstants.INTENT_VALUE_ICC_NOT_READY,
                null);
        broadcastSimCardStateChanged(slotId, TelephonyManager.SIM_STATE_PRESENT);
        broadcastSimApplicationStateChanged(slotId, TelephonyManager.SIM_STATE_NOT_READY);
    }

    private void handleSimLoaded(int slotId) {
        logd("handleSimLoaded: slotId: " + slotId);

        // The SIM should be loaded at this state, but it is possible in cases such as SIM being
        // removed or a refresh RESET that the IccRecords could be null. The right behavior is to
        // not broadcast the SIM loaded.
        int loadedSlotId = slotId;
        IccCard iccCard = mPhone[slotId].getIccCard();
        if (iccCard == null) {  // Possibly a race condition.
            logd("handleSimLoaded: IccCard null");
            return;
        }
        IccRecords records = iccCard.getIccRecords();
        if (records == null) {  // Possibly a race condition.
            logd("handleSimLoaded: IccRecords null");
            return;
        }
        if (IccUtils.stripTrailingFs(records.getFullIccId()) == null) {
            logd("handleSimLoaded: IccID null");
            return;
        }
        mIccId[slotId] = IccUtils.stripTrailingFs(records.getFullIccId());

        updateSubscriptionInfoByIccId(slotId, true /* updateEmbeddedSubs */);
        List<SubscriptionInfo> subscriptionInfos = SubscriptionController.getInstance()
                .getSubInfoUsingSlotIndexPrivileged(slotId);
        if (subscriptionInfos == null || subscriptionInfos.isEmpty()) {
            loge("empty subinfo for slotId: " + slotId + "could not update ContentResolver");
        } else {
            for (SubscriptionInfo sub : subscriptionInfos) {
                int subId = sub.getSubscriptionId();
                TelephonyManager tm = (TelephonyManager)
                        mContext.getSystemService(Context.TELEPHONY_SERVICE);
                String operator = tm.getSimOperatorNumeric(subId);

                if (!TextUtils.isEmpty(operator)) {
                    if (subId == SubscriptionController.getInstance().getDefaultSubId()) {
                        MccTable.updateMccMncConfiguration(mContext, operator);
                    }
                    SubscriptionController.getInstance().setMccMnc(operator, subId);
                } else {
                    logd("EVENT_RECORDS_LOADED Operator name is null");
                }

                String iso = tm.getSimCountryIsoForPhone(slotId);

                if (!TextUtils.isEmpty(iso)) {
                    SubscriptionController.getInstance().setCountryIso(iso, subId);
                } else {
                    logd("EVENT_RECORDS_LOADED sim country iso is null");
                }

                String msisdn = tm.getLine1Number(subId);
                if (msisdn != null) {
                    SubscriptionController.getInstance().setDisplayNumber(msisdn, subId);
                }

                String imsi = tm.createForSubscriptionId(subId).getSubscriberId();
                if (imsi != null) {
                    SubscriptionController.getInstance().setImsi(imsi, subId);
                }

                String[] ehplmns = records.getEhplmns();
                String[] hplmns = records.getPlmnsFromHplmnActRecord();
                if (ehplmns != null || hplmns != null) {
                    SubscriptionController.getInstance().setAssociatedPlmns(ehplmns, hplmns, subId);
                }

                /* Update preferred network type and network selection mode on SIM change.
                 * Storing last subId in SharedPreference for now to detect SIM change.
                 */
                SharedPreferences sp =
                        PreferenceManager.getDefaultSharedPreferences(mContext);
                int storedSubId = sp.getInt(CURR_SUBID + slotId, -1);

                if (storedSubId != subId) {
                    int networkType = Settings.Global.getInt(
                            mPhone[slotId].getContext().getContentResolver(),
                            Settings.Global.PREFERRED_NETWORK_MODE + subId,
                            -1 /* invalid network mode */);

                    if (networkType == -1) {
                        networkType = RILConstants.PREFERRED_NETWORK_MODE;
                        try {
                            networkType = TelephonyManager.getIntAtIndex(
                                    mContext.getContentResolver(),
                                    Settings.Global.PREFERRED_NETWORK_MODE, slotId);
                        } catch (SettingNotFoundException retrySnfe) {
                            Rlog.e(LOG_TAG, "Settings Exception Reading Value At Index for "
                                    + "Settings.Global.PREFERRED_NETWORK_MODE");
                        }
                        Settings.Global.putInt(
                                mPhone[slotId].getContext().getContentResolver(),
                                Global.PREFERRED_NETWORK_MODE + subId,
                                networkType);
                    }

                    // Set the modem network mode
                    mPhone[slotId].setPreferredNetworkType(networkType, null);

                    // Only support automatic selection mode on SIM change.
                    mPhone[slotId].getNetworkSelectionMode(
                            obtainMessage(EVENT_GET_NETWORK_SELECTION_MODE_DONE,
                                    new Integer(slotId)));

                    // Update stored subId
                    SharedPreferences.Editor editor = sp.edit();
                    editor.putInt(CURR_SUBID + slotId, subId);
                    editor.apply();
                }
            }
        }

        // Update set of enabled carrier apps now that the privilege rules may have changed.
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(mContext.getOpPackageName(),
                mPackageManager, TelephonyManager.getDefault(),
                mContext.getContentResolver(), mCurrentlyActiveUserId);

        /**
         * The sim loading sequence will be
         *  1. ACTION_SUBINFO_CONTENT_CHANGE happens through updateSubscriptionInfoByIccId() above.
         *  2. ACTION_SIM_STATE_CHANGED/ACTION_SIM_CARD_STATE_CHANGED
         *  /ACTION_SIM_APPLICATION_STATE_CHANGED
         *  3. ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED
         *  4. ACTION_CARRIER_CONFIG_CHANGED
         */
        broadcastSimStateChanged(loadedSlotId, IccCardConstants.INTENT_VALUE_ICC_LOADED, null);
        broadcastSimCardStateChanged(loadedSlotId, TelephonyManager.SIM_STATE_PRESENT);
        broadcastSimApplicationStateChanged(loadedSlotId, TelephonyManager.SIM_STATE_LOADED);
        updateSubscriptionCarrierId(loadedSlotId, IccCardConstants.INTENT_VALUE_ICC_LOADED);
        updateCarrierServices(loadedSlotId, IccCardConstants.INTENT_VALUE_ICC_LOADED);
    }

    private void updateCarrierServices(int slotId, String simState) {
        CarrierConfigManager configManager =
                (CarrierConfigManager) mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        configManager.updateConfigForPhoneId(slotId, simState);
        mCarrierServiceBindHelper.updateForPhoneId(slotId, simState);
    }

    private void updateSubscriptionCarrierId(int slotId, String simState) {
        if (mPhone != null && mPhone[slotId] != null) {
            mPhone[slotId].resolveSubscriptionCarrierId(simState);
        }
    }

    private void handleSimAbsent(int slotId, int absentAndInactive) {
        if (mIccId[slotId] != null && !mIccId[slotId].equals(ICCID_STRING_FOR_NO_SIM)) {
            logd("SIM" + (slotId + 1) + " hot plug out, absentAndInactive=" + absentAndInactive);
        }
        mIccId[slotId] = ICCID_STRING_FOR_NO_SIM;
        updateSubscriptionInfoByIccId(slotId, true /* updateEmbeddedSubs */);
        // Do not broadcast if the SIM is absent and inactive, because the logical slotId here is
        // no longer correct
        if (absentAndInactive == 0) {
            broadcastSimStateChanged(slotId, IccCardConstants.INTENT_VALUE_ICC_ABSENT, null);
            broadcastSimCardStateChanged(slotId, TelephonyManager.SIM_STATE_ABSENT);
            broadcastSimApplicationStateChanged(slotId, TelephonyManager.SIM_STATE_UNKNOWN);
            updateSubscriptionCarrierId(slotId, IccCardConstants.INTENT_VALUE_ICC_ABSENT);
            updateCarrierServices(slotId, IccCardConstants.INTENT_VALUE_ICC_ABSENT);
        }
    }

    private void handleSimError(int slotId) {
        if (mIccId[slotId] != null && !mIccId[slotId].equals(ICCID_STRING_FOR_NO_SIM)) {
            logd("SIM" + (slotId + 1) + " Error ");
        }
        mIccId[slotId] = ICCID_STRING_FOR_NO_SIM;
        updateSubscriptionInfoByIccId(slotId, true /* updateEmbeddedSubs */);
        broadcastSimStateChanged(slotId, IccCardConstants.INTENT_VALUE_ICC_CARD_IO_ERROR,
                IccCardConstants.INTENT_VALUE_ICC_CARD_IO_ERROR);
        broadcastSimCardStateChanged(slotId, TelephonyManager.SIM_STATE_CARD_IO_ERROR);
        broadcastSimApplicationStateChanged(slotId, TelephonyManager.SIM_STATE_NOT_READY);
        updateSubscriptionCarrierId(slotId, IccCardConstants.INTENT_VALUE_ICC_CARD_IO_ERROR);
        updateCarrierServices(slotId, IccCardConstants.INTENT_VALUE_ICC_CARD_IO_ERROR);
    }

    private synchronized void updateSubscriptionInfoByIccId(int slotIndex,
            boolean updateEmbeddedSubs) {
        logd("updateSubscriptionInfoByIccId:+ Start");
        if (!SubscriptionManager.isValidSlotIndex(slotIndex)) {
            loge("[updateSubscriptionInfoByIccId]- invalid slotIndex=" + slotIndex);
            return;
        }
        logd("updateSubscriptionInfoByIccId: removing subscription info record: slotIndex "
                + slotIndex);
        // Clear slotIndex only when sim absent is not enough. It's possible to switch SIM profile
        // within the same slot. Need to clear the slot index of the previous sub. Thus always clear
        // for the changing slot first.
        SubscriptionController.getInstance().clearSubInfoRecord(slotIndex);

        // If SIM is not absent, insert new record or update existing record.
        if (!ICCID_STRING_FOR_NO_SIM.equals(mIccId[slotIndex])) {
           logd("updateSubscriptionInfoByIccId: adding subscription info record: iccid: "
                    + mIccId[slotIndex] + "slot: " + slotIndex);
           mSubscriptionManager.addSubscriptionInfoRecord(mIccId[slotIndex], slotIndex);
        }

        List<SubscriptionInfo> subInfos = SubscriptionController.getInstance()
                .getSubInfoUsingSlotIndexPrivileged(slotIndex);
        if (subInfos != null) {
            boolean changed = false;
            for (int i = 0; i < subInfos.size(); i++) {
                SubscriptionInfo temp = subInfos.get(i);
                ContentValues value = new ContentValues(1);

                String msisdn = TelephonyManager.getDefault().getLine1Number(
                        temp.getSubscriptionId());

                if (!TextUtils.equals(msisdn, temp.getNumber())) {
                    value.put(SubscriptionManager.NUMBER, msisdn);
                    mContext.getContentResolver().update(SubscriptionManager.getUriForSubscriptionId(
                            temp.getSubscriptionId()), value, null, null);
                    changed = true;
                }
            }
            if (changed) {
                // refresh Cached Active Subscription Info List
                SubscriptionController.getInstance().refreshCachedActiveSubscriptionInfoList();
            }
        }

        // TODO investigate if we can update for each slot separately.
        if (isAllIccIdQueryDone()) {
            // Ensure the modems are mapped correctly
            if (mSubscriptionManager.isActiveSubId(
                    mSubscriptionManager.getDefaultDataSubscriptionId())) {
                mSubscriptionManager.setDefaultDataSubId(
                        mSubscriptionManager.getDefaultDataSubscriptionId());
            } else {
                logd("bypass reset default data sub if inactive");
            }
            setSubInfoInitialized();
        }

        UiccController uiccController = UiccController.getInstance();
        UiccSlot[] uiccSlots = uiccController.getUiccSlots();
        if (uiccSlots != null && updateEmbeddedSubs) {
            List<Integer> cardIds = new ArrayList<>();
            for (UiccSlot uiccSlot : uiccSlots) {
                if (uiccSlot != null && uiccSlot.getUiccCard() != null) {
                    int cardId = uiccController.convertToPublicCardId(
                            uiccSlot.getUiccCard().getCardId());
                    cardIds.add(cardId);
                }
            }
            updateEmbeddedSubscriptions(cardIds, (hasChanges) -> {
                if (hasChanges) {
                    SubscriptionController.getInstance().notifySubscriptionInfoChanged();
                }
                if (DBG) logd("updateSubscriptionInfoByIccId: SubscriptionInfo update complete");
            });
        }

        SubscriptionController.getInstance().notifySubscriptionInfoChanged();
        if (DBG) logd("updateSubscriptionInfoByIccId: SubscriptionInfo update complete");
    }

    private static void setSubInfoInitialized() {
        // Should only be triggered once.
        if (!sIsSubInfoInitialized) {
            if (DBG) logd("SubInfo Initialized");
            sIsSubInfoInitialized = true;
            SubscriptionController.getInstance().notifySubInfoReady();
            MultiSimSettingController.getInstance().notifyAllSubscriptionLoaded();
        }
    }

    /**
     * Whether subscriptions of all SIMs are initialized.
     */
    public static boolean isSubInfoInitialized() {
        return sIsSubInfoInitialized;
    }

    /**
     * Updates the cached list of embedded subscription for the eUICC with the given list of card
     * IDs {@code cardIds}. The step of reading the embedded subscription list from eUICC card is
     * executed in background thread. The callback {@code callback} is executed after the cache is
     * refreshed. The callback is executed in main thread.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public void updateEmbeddedSubscriptions(List<Integer> cardIds,
            @Nullable UpdateEmbeddedSubsCallback callback) {
        // Do nothing if eUICCs are disabled. (Previous entries may remain in the cache, but they
        // are filtered out of list calls as long as EuiccManager.isEnabled returns false).
        if (!mEuiccManager.isEnabled()) {
            callback.run(false /* hasChanges */);
            return;
        }

        mBackgroundHandler.post(() -> {
            List<GetEuiccProfileInfoListResult> results = new ArrayList<>();
            for (int cardId : cardIds) {
                GetEuiccProfileInfoListResult result =
                        EuiccController.get().blockingGetEuiccProfileInfoList(cardId);
                if (DBG) logd("blockingGetEuiccProfileInfoList cardId " + cardId);
                results.add(result);
            }

            // The runnable will be executed in the main thread.
            this.post(() -> {
                boolean hasChanges = false;
                for (GetEuiccProfileInfoListResult result : results) {
                    if (updateEmbeddedSubscriptionsCache(result)) {
                        hasChanges = true;
                    }
                }
                // The latest state in the main thread may be changed when the callback is
                // triggered.
                if (callback != null) {
                    callback.run(hasChanges);
                }
            });
        });
    }

    /**
     * Update the cached list of embedded subscription based on the passed in
     * GetEuiccProfileInfoListResult {@code result}.
     *
     * @return true if changes may have been made. This is not a guarantee that changes were made,
     * but notifications about subscription changes may be skipped if this returns false as an
     * optimization to avoid spurious notifications.
     */
    private boolean updateEmbeddedSubscriptionsCache(GetEuiccProfileInfoListResult result) {
        if (DBG) logd("updateEmbeddedSubscriptionsCache");

        if (result == null) {
            // IPC to the eUICC controller failed.
            return false;
        }

        // If the returned result is not RESULT_OK or the profile list is null, don't update cache.
        // Otherwise, update the cache.
        final EuiccProfileInfo[] embeddedProfiles;
        List<EuiccProfileInfo> list = result.getProfiles();
        if (result.getResult() == EuiccService.RESULT_OK && list != null) {
            embeddedProfiles = list.toArray(new EuiccProfileInfo[list.size()]);
            if (DBG) {
                logd("blockingGetEuiccProfileInfoList: got " + result.getProfiles().size()
                        + " profiles");
            }
        } else {
            if (DBG) {
                logd("blockingGetEuiccProfileInfoList returns an error. "
                        + "Result code=" + result.getResult()
                        + ". Null profile list=" + (result.getProfiles() == null));
            }
            return false;
        }

        final boolean isRemovable = result.getIsRemovable();

        final String[] embeddedIccids = new String[embeddedProfiles.length];
        for (int i = 0; i < embeddedProfiles.length; i++) {
            embeddedIccids[i] = embeddedProfiles[i].getIccid();
        }

        if (DBG) logd("Get eUICC profile list of size " + embeddedProfiles.length);

        // Note that this only tracks whether we make any writes to the DB. It's possible this will
        // be set to true for an update even when the row contents remain exactly unchanged from
        // before, since we don't compare against the previous value. Since this is only intended to
        // avoid some spurious broadcasts (particularly for users who don't use eSIM at all), this
        // is fine.
        boolean hasChanges = false;

        // Update or insert records for all embedded subscriptions (except non-removable ones if the
        // current eUICC is non-removable, since we assume these are still accessible though not
        // returned by the eUICC controller).
        List<SubscriptionInfo> existingSubscriptions = SubscriptionController.getInstance()
                .getSubscriptionInfoListForEmbeddedSubscriptionUpdate(embeddedIccids, isRemovable);
        ContentResolver contentResolver = mContext.getContentResolver();
        for (EuiccProfileInfo embeddedProfile : embeddedProfiles) {
            int index =
                    findSubscriptionInfoForIccid(existingSubscriptions, embeddedProfile.getIccid());
            int prevCarrierId = TelephonyManager.UNKNOWN_CARRIER_ID;
            int nameSource = SubscriptionManager.NAME_SOURCE_DEFAULT_SOURCE;
            if (index < 0) {
                // No existing entry for this ICCID; create an empty one.
                SubscriptionController.getInstance().insertEmptySubInfoRecord(
                        embeddedProfile.getIccid(), SubscriptionManager.SIM_NOT_INSERTED);
            } else {
                nameSource = existingSubscriptions.get(index).getNameSource();
                prevCarrierId = existingSubscriptions.get(index).getCarrierId();
                existingSubscriptions.remove(index);
            }

            if (DBG) {
                logd("embeddedProfile " + embeddedProfile + " existing record "
                        + (index < 0 ? "not found" : "found"));
            }

            ContentValues values = new ContentValues();
            values.put(SubscriptionManager.IS_EMBEDDED, 1);
            List<UiccAccessRule> ruleList = embeddedProfile.getUiccAccessRules();
            boolean isRuleListEmpty = false;
            if (ruleList == null || ruleList.size() == 0) {
                isRuleListEmpty = true;
            }
            values.put(SubscriptionManager.ACCESS_RULES,
                    isRuleListEmpty ? null : UiccAccessRule.encodeRules(
                            ruleList.toArray(new UiccAccessRule[ruleList.size()])));
            values.put(SubscriptionManager.IS_REMOVABLE, isRemovable);
            // override DISPLAY_NAME if the priority of existing nameSource is <= carrier
            if (SubscriptionController.getNameSourcePriority(nameSource)
                    <= SubscriptionController.getNameSourcePriority(
                            SubscriptionManager.NAME_SOURCE_CARRIER)) {
                values.put(SubscriptionManager.DISPLAY_NAME, embeddedProfile.getNickname());
                values.put(SubscriptionManager.NAME_SOURCE,
                        SubscriptionManager.NAME_SOURCE_CARRIER);
            }
            values.put(SubscriptionManager.PROFILE_CLASS, embeddedProfile.getProfileClass());
            CarrierIdentifier cid = embeddedProfile.getCarrierIdentifier();
            if (cid != null) {
                // Due to the limited subscription information, carrier id identified here might
                // not be accurate compared with CarrierResolver. Only update carrier id if there
                // is no valid carrier id present.
                if (prevCarrierId == TelephonyManager.UNKNOWN_CARRIER_ID) {
                    values.put(SubscriptionManager.CARRIER_ID,
                            CarrierResolver.getCarrierIdFromIdentifier(mContext, cid));
                }
                String mcc = cid.getMcc();
                String mnc = cid.getMnc();
                values.put(SubscriptionManager.MCC_STRING, mcc);
                values.put(SubscriptionManager.MCC, mcc);
                values.put(SubscriptionManager.MNC_STRING, mnc);
                values.put(SubscriptionManager.MNC, mnc);
            }
            hasChanges = true;
            contentResolver.update(SubscriptionManager.CONTENT_URI, values,
                    SubscriptionManager.ICC_ID + "=\"" + embeddedProfile.getIccid() + "\"", null);

            // refresh Cached Active Subscription Info List
            SubscriptionController.getInstance().refreshCachedActiveSubscriptionInfoList();
        }

        // Remove all remaining subscriptions which have embedded = true. We set embedded to false
        // to ensure they are not returned in the list of embedded subscriptions (but keep them
        // around in case the subscription is added back later, which is equivalent to a removable
        // SIM being removed and reinserted).
        if (!existingSubscriptions.isEmpty()) {
            if (DBG) {
                logd("Removing existing embedded subscriptions of size"
                        + existingSubscriptions.size());
            }
            List<String> iccidsToRemove = new ArrayList<>();
            for (int i = 0; i < existingSubscriptions.size(); i++) {
                SubscriptionInfo info = existingSubscriptions.get(i);
                if (info.isEmbedded()) {
                    if (DBG) logd("Removing embedded subscription of IccId " + info.getIccId());
                    iccidsToRemove.add("\"" + info.getIccId() + "\"");
                }
            }
            String whereClause = SubscriptionManager.ICC_ID + " IN ("
                    + TextUtils.join(",", iccidsToRemove) + ")";
            ContentValues values = new ContentValues();
            values.put(SubscriptionManager.IS_EMBEDDED, 0);
            hasChanges = true;
            contentResolver.update(SubscriptionManager.CONTENT_URI, values, whereClause, null);

            // refresh Cached Active Subscription Info List
            SubscriptionController.getInstance().refreshCachedActiveSubscriptionInfoList();
        }

        if (DBG) logd("updateEmbeddedSubscriptions done hasChanges=" + hasChanges);
        return hasChanges;
    }

    /**
     * Called by CarrierConfigLoader to update the subscription before sending a broadcast.
     */
    public void updateSubscriptionByCarrierConfigAndNotifyComplete(int phoneId,
            String configPackageName, PersistableBundle config, Message onComplete) {
        post(() -> {
            updateSubscriptionByCarrierConfig(phoneId, configPackageName, config);
            onComplete.sendToTarget();
        });
    }

    private String getDefaultCarrierServicePackageName() {
        CarrierConfigManager configManager =
                (CarrierConfigManager) mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        return configManager.getDefaultCarrierServicePackageName();
    }

    private boolean isCarrierServicePackage(int phoneId, String pkgName) {
        if (pkgName.equals(getDefaultCarrierServicePackageName())) return false;

        List<String> carrierPackageNames = TelephonyManager.from(mContext)
                .getCarrierPackageNamesForIntentAndPhone(
                        new Intent(CarrierService.CARRIER_SERVICE_INTERFACE), phoneId);
        if (DBG) logd("Carrier Packages For Subscription = " + carrierPackageNames);
        return carrierPackageNames != null && carrierPackageNames.contains(pkgName);
    }

    /**
     * Update the currently active Subscription based on information from CarrierConfig
     */
    @VisibleForTesting
    public void updateSubscriptionByCarrierConfig(
            int phoneId, String configPackageName, PersistableBundle config) {
        if (!SubscriptionManager.isValidPhoneId(phoneId)
                || TextUtils.isEmpty(configPackageName) || config == null) {
            if (DBG) {
                logd("In updateSubscriptionByCarrierConfig(): phoneId=" + phoneId
                        + " configPackageName=" + configPackageName + " config="
                        + ((config == null) ? "null" : config.hashCode()));
            }
            return;
        }

        SubscriptionController sc = SubscriptionController.getInstance();
        if (sc == null) {
            loge("SubscriptionController was null");
            return;
        }

        int currentSubId = sc.getSubIdUsingPhoneId(phoneId);
        if (!SubscriptionManager.isValidSubscriptionId(currentSubId)
                || currentSubId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) {
            if (DBG) logd("No subscription is active for phone being updated");
            return;
        }

        SubscriptionInfo currentSubInfo = sc.getSubscriptionInfo(currentSubId);
        if (currentSubInfo == null) {
            loge("Couldn't retrieve subscription info for current subscription");
            return;
        }

        if (!isCarrierServicePackage(phoneId, configPackageName)) {
            loge("Cannot manage subId=" + currentSubId + ", carrierPackage=" + configPackageName);
            return;
        }

        ContentValues cv = new ContentValues();
        boolean isOpportunistic = config.getBoolean(
                CarrierConfigManager.KEY_IS_OPPORTUNISTIC_SUBSCRIPTION_BOOL, false);
        if (currentSubInfo.isOpportunistic() != isOpportunistic) {
            if (DBG) logd("Set SubId=" + currentSubId + " isOpportunistic=" + isOpportunistic);
            cv.put(SubscriptionManager.IS_OPPORTUNISTIC, isOpportunistic ? "1" : "0");
        }

        String groupUuidString =
                config.getString(CarrierConfigManager.KEY_SUBSCRIPTION_GROUP_UUID_STRING, "");
        ParcelUuid groupUuid = null;
        if (!TextUtils.isEmpty(groupUuidString)) {
            try {
                // Update via a UUID Structure to ensure consistent formatting
                groupUuid = ParcelUuid.fromString(groupUuidString);
                if (groupUuid.equals(REMOVE_GROUP_UUID)
                            && currentSubInfo.getGroupUuid() != null) {
                    cv.put(SubscriptionManager.GROUP_UUID, (String) null);
                    if (DBG) logd("Group Removed for" + currentSubId);
                } else if (SubscriptionController.getInstance().canPackageManageGroup(groupUuid,
                        configPackageName)) {
                    cv.put(SubscriptionManager.GROUP_UUID, groupUuid.toString());
                    cv.put(SubscriptionManager.GROUP_OWNER, configPackageName);
                    if (DBG) logd("Group Added for" + currentSubId);
                } else {
                    loge("configPackageName " + configPackageName + " doesn't own grouUuid "
                            + groupUuid);
                }
            } catch (IllegalArgumentException e) {
                loge("Invalid Group UUID=" + groupUuidString);
            }
        }
        if (cv.size() > 0 && mContext.getContentResolver().update(SubscriptionManager
                    .getUriForSubscriptionId(currentSubId), cv, null, null) > 0) {
            sc.refreshCachedActiveSubscriptionInfoList();
            sc.notifySubscriptionInfoChanged();
            MultiSimSettingController.getInstance().notifySubscriptionGroupChanged(groupUuid);
        }
    }

    private static int findSubscriptionInfoForIccid(List<SubscriptionInfo> list, String iccid) {
        for (int i = 0; i < list.size(); i++) {
            if (TextUtils.equals(iccid, list.get(i).getIccId())) {
                return i;
            }
        }
        return -1;
    }

    private boolean isNewSim(String iccId, String decIccId, String[] oldIccId) {
        boolean newSim = true;
        for(int i = 0; i < PROJECT_SIM_NUM; i++) {
            if(iccId.equals(oldIccId[i])) {
                newSim = false;
                break;
            } else if (decIccId != null && decIccId.equals(oldIccId[i])) {
                newSim = false;
                break;
            }
        }
        logd("newSim = " + newSim);

        return newSim;
    }

    @UnsupportedAppUsage
    private void broadcastSimStateChanged(int slotId, String state, String reason) {
        Intent i = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        // TODO - we'd like this intent to have a single snapshot of all sim state,
        // but until then this should not use REPLACE_PENDING or we may lose
        // information
        // i.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
        //         | Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        i.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        i.putExtra(PhoneConstants.PHONE_NAME_KEY, "Phone");
        i.putExtra(IccCardConstants.INTENT_KEY_ICC_STATE, state);
        i.putExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON, reason);
        SubscriptionManager.putPhoneIdAndSubIdExtra(i, slotId);
        logd("Broadcasting intent ACTION_SIM_STATE_CHANGED " + state + " reason " + reason +
                " for phone: " + slotId);
        IntentBroadcaster.getInstance().broadcastStickyIntent(i, slotId);
    }

    private void broadcastSimCardStateChanged(int phoneId, int state) {
        if (state != sSimCardState[phoneId]) {
            sSimCardState[phoneId] = state;
            Intent i = new Intent(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED);
            i.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            i.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            i.putExtra(TelephonyManager.EXTRA_SIM_STATE, state);
            SubscriptionManager.putPhoneIdAndSubIdExtra(i, phoneId);
            // TODO(b/130664115) we manually populate this intent with the slotId. In the future we
            // should do a review of whether to make this public
            int slotId = UiccController.getInstance().getSlotIdFromPhoneId(phoneId);
            i.putExtra(PhoneConstants.SLOT_KEY, slotId);
            logd("Broadcasting intent ACTION_SIM_CARD_STATE_CHANGED " + simStateString(state)
                    + " for phone: " + phoneId + " slot: " + slotId);
            mContext.sendBroadcast(i, Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
            TelephonyMetrics.getInstance().updateSimState(phoneId, state);
        }
    }

    private void broadcastSimApplicationStateChanged(int phoneId, int state) {
        // Broadcast if the state has changed, except if old state was UNKNOWN and new is NOT_READY,
        // because that's the initial state and a broadcast should be sent only on a transition
        // after SIM is PRESENT
        if (!(state == sSimApplicationState[phoneId]
                || (state == TelephonyManager.SIM_STATE_NOT_READY
                && sSimApplicationState[phoneId] == TelephonyManager.SIM_STATE_UNKNOWN))) {
            sSimApplicationState[phoneId] = state;
            Intent i = new Intent(TelephonyManager.ACTION_SIM_APPLICATION_STATE_CHANGED);
            i.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            i.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            i.putExtra(TelephonyManager.EXTRA_SIM_STATE, state);
            SubscriptionManager.putPhoneIdAndSubIdExtra(i, phoneId);
            // TODO(b/130664115) we populate this intent with the actual slotId. In the future we
            // should do a review of whether to make this public
            int slotId = UiccController.getInstance().getSlotIdFromPhoneId(phoneId);
            i.putExtra(PhoneConstants.SLOT_KEY, slotId);
            logd("Broadcasting intent ACTION_SIM_APPLICATION_STATE_CHANGED " + simStateString(state)
                    + " for phone: " + phoneId + " slot: " + slotId);
            mContext.sendBroadcast(i, Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
            TelephonyMetrics.getInstance().updateSimState(phoneId, state);
        }
    }

    private static String simStateString(int state) {
        switch (state) {
            case TelephonyManager.SIM_STATE_UNKNOWN:
                return "UNKNOWN";
            case TelephonyManager.SIM_STATE_ABSENT:
                return "ABSENT";
            case TelephonyManager.SIM_STATE_PIN_REQUIRED:
                return "PIN_REQUIRED";
            case TelephonyManager.SIM_STATE_PUK_REQUIRED:
                return "PUK_REQUIRED";
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED:
                return "NETWORK_LOCKED";
            case TelephonyManager.SIM_STATE_READY:
                return "READY";
            case TelephonyManager.SIM_STATE_NOT_READY:
                return "NOT_READY";
            case TelephonyManager.SIM_STATE_PERM_DISABLED:
                return "PERM_DISABLED";
            case TelephonyManager.SIM_STATE_CARD_IO_ERROR:
                return "CARD_IO_ERROR";
            case TelephonyManager.SIM_STATE_CARD_RESTRICTED:
                return "CARD_RESTRICTED";
            case TelephonyManager.SIM_STATE_LOADED:
                return "LOADED";
            case TelephonyManager.SIM_STATE_PRESENT:
                return "PRESENT";
            default:
                return "INVALID";
        }
    }

    @UnsupportedAppUsage
    private static void logd(String message) {
        Rlog.d(LOG_TAG, message);
    }

    private static void loge(String message) {
        Rlog.e(LOG_TAG, message);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("SubscriptionInfoUpdater:");
        mCarrierServiceBindHelper.dump(fd, pw, args);
    }
}
