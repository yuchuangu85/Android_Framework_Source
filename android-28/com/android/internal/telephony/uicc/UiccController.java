/*
 * Copyright (C) 2011-2012 The Android Open Source Project
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

package com.android.internal.telephony.uicc;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.storage.StorageManager;
import android.telephony.CarrierConfigManager;
import android.telephony.Rlog;
import android.telephony.TelephonyManager;
import android.util.LocalLog;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.RadioConfig;
import com.android.internal.telephony.SubscriptionInfoUpdater;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * This class is responsible for keeping all knowledge about
 * Universal Integrated Circuit Card (UICC), also know as SIM's,
 * in the system. It is also used as API to get appropriate
 * applications to pass them to phone and service trackers.
 *
 * UiccController is created with the call to make() function.
 * UiccController is a singleton and make() must only be called once
 * and throws an exception if called multiple times.
 *
 * Once created UiccController registers with RIL for "on" and "unsol_sim_status_changed"
 * notifications. When such notification arrives UiccController will call
 * getIccCardStatus (GET_SIM_STATUS). Based on the response of GET_SIM_STATUS
 * request appropriate tree of uicc objects will be created.
 *
 * Following is class diagram for uicc classes:
 *
 *                       UiccController
 *                            #
 *                            |
 *                        UiccSlot[]
 *                            #
 *                            |
 *                        UiccCard
 *                            #
 *                            |
 *                       UiccProfile
 *                          #   #
 *                          |   ------------------
 *                    UiccCardApplication    CatService
 *                      #            #
 *                      |            |
 *                 IccRecords    IccFileHandler
 *                 ^ ^ ^           ^ ^ ^ ^ ^
 *    SIMRecords---- | |           | | | | ---SIMFileHandler
 *    RuimRecords----- |           | | | ----RuimFileHandler
 *    IsimUiccRecords---           | | -----UsimFileHandler
 *                                 | ------CsimFileHandler
 *                                 ----IsimFileHandler
 *
 * Legend: # stands for Composition
 *         ^ stands for Generalization
 *
 * See also {@link com.android.internal.telephony.IccCard}
 */
public class UiccController extends Handler {
    private static final boolean DBG = true;
    private static final boolean VDBG = false; //STOPSHIP if true
    private static final String LOG_TAG = "UiccController";

    public static final int INVALID_SLOT_ID = -1;

    public static final int APP_FAM_3GPP =  1;
    public static final int APP_FAM_3GPP2 = 2;
    public static final int APP_FAM_IMS   = 3;

    private static final int EVENT_ICC_STATUS_CHANGED = 1;
    private static final int EVENT_SLOT_STATUS_CHANGED = 2;
    private static final int EVENT_GET_ICC_STATUS_DONE = 3;
    private static final int EVENT_GET_SLOT_STATUS_DONE = 4;
    private static final int EVENT_RADIO_ON = 5;
    private static final int EVENT_RADIO_AVAILABLE = 6;
    private static final int EVENT_RADIO_UNAVAILABLE = 7;
    private static final int EVENT_SIM_REFRESH = 8;

    // this needs to be here, because on bootup we dont know which index maps to which UiccSlot
    private CommandsInterface[] mCis;
    private UiccSlot[] mUiccSlots;
    private int[] mPhoneIdToSlotId;
    private boolean mIsSlotStatusSupported = true;

    private static final Object mLock = new Object();
    private static UiccController mInstance;
    private static ArrayList<IccSlotStatus> sLastSlotStatus;

    private Context mContext;

    protected RegistrantList mIccChangedRegistrants = new RegistrantList();

    private UiccStateChangedLauncher mLauncher;
    private RadioConfig mRadioConfig;

    // LocalLog buffer to hold important SIM related events for debugging
    static LocalLog sLocalLog = new LocalLog(100);

    public static UiccController make(Context c, CommandsInterface[] ci) {
        synchronized (mLock) {
            if (mInstance != null) {
                throw new RuntimeException("UiccController.make() should only be called once");
            }
            mInstance = new UiccController(c, ci);
            return mInstance;
        }
    }

    private UiccController(Context c, CommandsInterface []ci) {
        if (DBG) log("Creating UiccController");
        mContext = c;
        mCis = ci;
        if (DBG) {
            String logStr = "config_num_physical_slots = " + c.getResources().getInteger(
                    com.android.internal.R.integer.config_num_physical_slots);
            log(logStr);
            sLocalLog.log(logStr);
        }
        int numPhysicalSlots = c.getResources().getInteger(
                com.android.internal.R.integer.config_num_physical_slots);
        // Minimum number of physical slot count should be equals to or greater than phone count,
        // if it is less than phone count use phone count as physical slot count.
        if (numPhysicalSlots < mCis.length) {
            numPhysicalSlots = mCis.length;
        }

        mUiccSlots = new UiccSlot[numPhysicalSlots];
        mPhoneIdToSlotId = new int[ci.length];
        Arrays.fill(mPhoneIdToSlotId, INVALID_SLOT_ID);
        if (VDBG) logPhoneIdToSlotIdMapping();
        mRadioConfig = RadioConfig.getInstance(mContext);
        mRadioConfig.registerForSimSlotStatusChanged(this, EVENT_SLOT_STATUS_CHANGED, null);
        for (int i = 0; i < mCis.length; i++) {
            mCis[i].registerForIccStatusChanged(this, EVENT_ICC_STATUS_CHANGED, i);

            // TODO remove this once modem correctly notifies the unsols
            // If the device is unencrypted or has been decrypted or FBE is supported,
            // i.e. not in CryptKeeper bounce, read SIM when radio state is available.
            // Else wait for radio to be on. This is needed for the scenario when SIM is locked --
            // to avoid overlap of CryptKeeper and SIM unlock screen.
            if (!StorageManager.inCryptKeeperBounce()) {
                mCis[i].registerForAvailable(this, EVENT_RADIO_AVAILABLE, i);
            } else {
                mCis[i].registerForOn(this, EVENT_RADIO_ON, i);
            }
            mCis[i].registerForNotAvailable(this, EVENT_RADIO_UNAVAILABLE, i);
            mCis[i].registerForIccRefresh(this, EVENT_SIM_REFRESH, i);
        }

        mLauncher = new UiccStateChangedLauncher(c, this);
    }

    private int getSlotIdFromPhoneId(int phoneId) {
        return mPhoneIdToSlotId[phoneId];
    }

    public static UiccController getInstance() {
        synchronized (mLock) {
            if (mInstance == null) {
                throw new RuntimeException(
                        "UiccController.getInstance can't be called before make()");
            }
            return mInstance;
        }
    }

    public UiccCard getUiccCard(int phoneId) {
        synchronized (mLock) {
            return getUiccCardForPhone(phoneId);
        }
    }

    /**
     * API to get UiccCard corresponding to given physical slot index
     * @param slotId index of physical slot on the device
     * @return UiccCard object corresponting to given physical slot index; null if card is
     * absent
     */
    public UiccCard getUiccCardForSlot(int slotId) {
        synchronized (mLock) {
            UiccSlot uiccSlot = getUiccSlot(slotId);
            if (uiccSlot != null) {
                return uiccSlot.getUiccCard();
            }
            return null;
        }
    }

    /**
     * API to get UiccCard corresponding to given phone id
     * @return UiccCard object corresponding to given phone id; null if there is no card present for
     * the phone id
     */
    public UiccCard getUiccCardForPhone(int phoneId) {
        synchronized (mLock) {
            if (isValidPhoneIndex(phoneId)) {
                UiccSlot uiccSlot = getUiccSlotForPhone(phoneId);
                if (uiccSlot != null) {
                    return uiccSlot.getUiccCard();
                }
            }
            return null;
        }
    }

    /**
     * API to get UiccProfile corresponding to given phone id
     * @return UiccProfile object corresponding to given phone id; null if there is no card/profile
     * present for the phone id
     */
    public UiccProfile getUiccProfileForPhone(int phoneId) {
        synchronized (mLock) {
            if (isValidPhoneIndex(phoneId)) {
                UiccCard uiccCard = getUiccCardForPhone(phoneId);
                return uiccCard != null ? uiccCard.getUiccProfile() : null;
            }
            return null;
        }
    }

    /**
     * API to get all the UICC slots.
     * @return UiccSlots array.
     */
    public UiccSlot[] getUiccSlots() {
        synchronized (mLock) {
            return mUiccSlots;
        }
    }

    /** Map logicalSlot to physicalSlot, and activate the physicalSlot if it is inactive. */
    public void switchSlots(int[] physicalSlots, Message response) {
        mRadioConfig.setSimSlotsMapping(physicalSlots, response);
    }

    /**
     * API to get UiccSlot object for a specific physical slot index on the device
     * @return UiccSlot object for the given physical slot index
     */
    public UiccSlot getUiccSlot(int slotId) {
        synchronized (mLock) {
            if (isValidSlotIndex(slotId)) {
                return mUiccSlots[slotId];
            }
            return null;
        }
    }

    /**
     * API to get UiccSlot object for a given phone id
     * @return UiccSlot object for the given phone id
     */
    public UiccSlot getUiccSlotForPhone(int phoneId) {
        synchronized (mLock) {
            if (isValidPhoneIndex(phoneId)) {
                int slotId = getSlotIdFromPhoneId(phoneId);
                if (isValidSlotIndex(slotId)) {
                    return mUiccSlots[slotId];
                }
            }
            return null;
        }
    }

    /**
     * API to get UiccSlot object for a given cardId
     * @param cardId Identifier for a SIM. This can be an ICCID, or an EID in case of an eSIM.
     * @return int Index of UiccSlot for the given cardId if one is found, {@link #INVALID_SLOT_ID}
     * otherwise
     */
    public int getUiccSlotForCardId(String cardId) {
        synchronized (mLock) {
            // first look up based on cardId
            for (int idx = 0; idx < mUiccSlots.length; idx++) {
                if (mUiccSlots[idx] != null) {
                    UiccCard uiccCard = mUiccSlots[idx].getUiccCard();
                    if (uiccCard != null && cardId.equals(uiccCard.getCardId())) {
                        return idx;
                    }
                }
            }
            // if a match is not found, do a lookup based on ICCID
            for (int idx = 0; idx < mUiccSlots.length; idx++) {
                if (mUiccSlots[idx] != null && cardId.equals(mUiccSlots[idx].getIccId())) {
                    return idx;
                }
            }
            return INVALID_SLOT_ID;
        }
    }

    // Easy to use API
    public IccRecords getIccRecords(int phoneId, int family) {
        synchronized (mLock) {
            UiccCardApplication app = getUiccCardApplication(phoneId, family);
            if (app != null) {
                return app.getIccRecords();
            }
            return null;
        }
    }

    // Easy to use API
    public IccFileHandler getIccFileHandler(int phoneId, int family) {
        synchronized (mLock) {
            UiccCardApplication app = getUiccCardApplication(phoneId, family);
            if (app != null) {
                return app.getIccFileHandler();
            }
            return null;
        }
    }


    //Notifies when card status changes
    public void registerForIccChanged(Handler h, int what, Object obj) {
        synchronized (mLock) {
            Registrant r = new Registrant (h, what, obj);
            mIccChangedRegistrants.add(r);
            //Notify registrant right after registering, so that it will get the latest ICC status,
            //otherwise which may not happen until there is an actual change in ICC status.
            r.notifyRegistrant();
        }
    }

    public void unregisterForIccChanged(Handler h) {
        synchronized (mLock) {
            mIccChangedRegistrants.remove(h);
        }
    }

    @Override
    public void handleMessage (Message msg) {
        synchronized (mLock) {
            Integer phoneId = getCiIndex(msg);

            if (phoneId < 0 || phoneId >= mCis.length) {
                Rlog.e(LOG_TAG, "Invalid phoneId : " + phoneId + " received with event "
                        + msg.what);
                return;
            }

            sLocalLog.log("handleMessage: Received " + msg.what + " for phoneId " + phoneId);

            AsyncResult ar = (AsyncResult)msg.obj;
            switch (msg.what) {
                case EVENT_ICC_STATUS_CHANGED:
                    if (DBG) log("Received EVENT_ICC_STATUS_CHANGED, calling getIccCardStatus");
                    mCis[phoneId].getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE,
                            phoneId));
                    break;
                case EVENT_RADIO_AVAILABLE:
                case EVENT_RADIO_ON:
                    if (DBG) {
                        log("Received EVENT_RADIO_AVAILABLE/EVENT_RADIO_ON, calling "
                                + "getIccCardStatus");
                    }
                    mCis[phoneId].getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE,
                            phoneId));
                    // slot status should be the same on all RILs; request it only for phoneId 0
                    if (phoneId == 0) {
                        if (DBG) {
                            log("Received EVENT_RADIO_AVAILABLE/EVENT_RADIO_ON for phoneId 0, "
                                    + "calling getIccSlotsStatus");
                        }
                        mRadioConfig.getSimSlotsStatus(obtainMessage(EVENT_GET_SLOT_STATUS_DONE,
                                phoneId));
                    }
                    break;
                case EVENT_GET_ICC_STATUS_DONE:
                    if (DBG) log("Received EVENT_GET_ICC_STATUS_DONE");
                    onGetIccCardStatusDone(ar, phoneId);
                    break;
                case EVENT_SLOT_STATUS_CHANGED:
                case EVENT_GET_SLOT_STATUS_DONE:
                    if (DBG) {
                        log("Received EVENT_SLOT_STATUS_CHANGED or EVENT_GET_SLOT_STATUS_DONE");
                    }
                    onGetSlotStatusDone(ar);
                    break;
                case EVENT_RADIO_UNAVAILABLE:
                    if (DBG) log("EVENT_RADIO_UNAVAILABLE, dispose card");
                    UiccSlot uiccSlot = getUiccSlotForPhone(phoneId);
                    if (uiccSlot != null) {
                        uiccSlot.onRadioStateUnavailable();
                    }
                    mIccChangedRegistrants.notifyRegistrants(new AsyncResult(null, phoneId, null));
                    break;
                case EVENT_SIM_REFRESH:
                    if (DBG) log("Received EVENT_SIM_REFRESH");
                    onSimRefresh(ar, phoneId);
                    break;
                default:
                    Rlog.e(LOG_TAG, " Unknown Event " + msg.what);
                    break;
            }
        }
    }

    private Integer getCiIndex(Message msg) {
        AsyncResult ar;
        Integer index = new Integer(PhoneConstants.DEFAULT_CARD_INDEX);

        /*
         * The events can be come in two ways. By explicitly sending it using
         * sendMessage, in this case the user object passed is msg.obj and from
         * the CommandsInterface, in this case the user object is msg.obj.userObj
         */
        if (msg != null) {
            if (msg.obj != null && msg.obj instanceof Integer) {
                index = (Integer)msg.obj;
            } else if(msg.obj != null && msg.obj instanceof AsyncResult) {
                ar = (AsyncResult)msg.obj;
                if (ar.userObj != null && ar.userObj instanceof Integer) {
                    index = (Integer)ar.userObj;
                }
            }
        }
        return index;
    }

    // Easy to use API
    public UiccCardApplication getUiccCardApplication(int phoneId, int family) {
        synchronized (mLock) {
            UiccCard uiccCard = getUiccCardForPhone(phoneId);
            if (uiccCard != null) {
                return uiccCard.getApplication(family);
            }
            return null;
        }
    }

    static void updateInternalIccState(String value, String reason, int phoneId) {
        SubscriptionInfoUpdater subInfoUpdator = PhoneFactory.getSubscriptionInfoUpdater();
        if (subInfoUpdator != null) {
            subInfoUpdator.updateInternalIccState(value, reason, phoneId);
        } else {
            Rlog.e(LOG_TAG, "subInfoUpdate is null.");
        }
    }

    private synchronized void onGetIccCardStatusDone(AsyncResult ar, Integer index) {
        if (ar.exception != null) {
            Rlog.e(LOG_TAG,"Error getting ICC status. "
                    + "RIL_REQUEST_GET_ICC_STATUS should "
                    + "never return an error", ar.exception);
            return;
        }
        if (!isValidPhoneIndex(index)) {
            Rlog.e(LOG_TAG,"onGetIccCardStatusDone: invalid index : " + index);
            return;
        }

        IccCardStatus status = (IccCardStatus)ar.result;

        sLocalLog.log("onGetIccCardStatusDone: phoneId " + index + " IccCardStatus: " + status);

        int slotId = status.physicalSlotIndex;
        if (VDBG) log("onGetIccCardStatusDone: phoneId " + index + " physicalSlotIndex " + slotId);
        if (slotId == INVALID_SLOT_ID) {
            slotId = index;
        }
        mPhoneIdToSlotId[index] = slotId;

        if (VDBG) logPhoneIdToSlotIdMapping();

        if (mUiccSlots[slotId] == null) {
            if (VDBG) {
                log("Creating mUiccSlots[" + slotId + "]; mUiccSlots.length = "
                        + mUiccSlots.length);
            }
            mUiccSlots[slotId] = new UiccSlot(mContext, true);
        }

        mUiccSlots[slotId].update(mCis[index], status, index);

        if (DBG) log("Notifying IccChangedRegistrants");
        mIccChangedRegistrants.notifyRegistrants(new AsyncResult(null, index, null));
    }

    private synchronized void onGetSlotStatusDone(AsyncResult ar) {
        if (!mIsSlotStatusSupported) {
            if (VDBG) log("onGetSlotStatusDone: ignoring since mIsSlotStatusSupported is false");
            return;
        }
        Throwable e = ar.exception;
        if (e != null) {
            String logStr;
            if (!(e instanceof CommandException) || ((CommandException) e).getCommandError()
                    != CommandException.Error.REQUEST_NOT_SUPPORTED) {
                // this is not expected; there should be no exception other than
                // REQUEST_NOT_SUPPORTED
                logStr = "Unexpected error getting slot status: " + ar.exception;
                Rlog.e(LOG_TAG, logStr);
                sLocalLog.log(logStr);
            } else {
                // REQUEST_NOT_SUPPORTED
                logStr = "onGetSlotStatusDone: request not supported; marking "
                        + "mIsSlotStatusSupported to false";
                log(logStr);
                sLocalLog.log(logStr);
                mIsSlotStatusSupported = false;
            }
            return;
        }

        ArrayList<IccSlotStatus> status = (ArrayList<IccSlotStatus>) ar.result;

        if (!slotStatusChanged(status)) {
            log("onGetSlotStatusDone: No change in slot status");
            return;
        }

        sLastSlotStatus = status;

        int numActiveSlots = 0;
        for (int i = 0; i < status.size(); i++) {
            IccSlotStatus iss = status.get(i);
            boolean isActive = (iss.slotState == IccSlotStatus.SlotState.SLOTSTATE_ACTIVE);
            if (isActive) {
                numActiveSlots++;

                // sanity check: logicalSlotIndex should be valid for an active slot
                if (!isValidPhoneIndex(iss.logicalSlotIndex)) {
                    throw new RuntimeException("Logical slot index " + iss.logicalSlotIndex
                            + " invalid for physical slot " + i);
                }
                mPhoneIdToSlotId[iss.logicalSlotIndex] = i;
            }

            if (mUiccSlots[i] == null) {
                if (VDBG) {
                    log("Creating mUiccSlot[" + i + "]; mUiccSlots.length = " + mUiccSlots.length);
                }
                mUiccSlots[i] = new UiccSlot(mContext, isActive);
            }

            mUiccSlots[i].update(isActive ? mCis[iss.logicalSlotIndex] : null, iss);
        }

        if (VDBG) logPhoneIdToSlotIdMapping();

        // sanity check: number of active slots should be valid
        if (numActiveSlots != mPhoneIdToSlotId.length) {
            throw new RuntimeException("Number of active slots " + numActiveSlots
                    + " does not match the expected value " + mPhoneIdToSlotId.length);
        }

        // sanity check: slotIds should be unique in mPhoneIdToSlotId
        Set<Integer> slotIds = new HashSet<>();
        for (int slotId : mPhoneIdToSlotId) {
            if (slotIds.contains(slotId)) {
                throw new RuntimeException("slotId " + slotId + " mapped to multiple phoneIds");
            }
            slotIds.add(slotId);
        }

        // broadcast slot status changed
        Intent intent = new Intent(TelephonyManager.ACTION_SIM_SLOT_STATUS_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        mContext.sendBroadcast(intent, android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
    }

    private boolean slotStatusChanged(ArrayList<IccSlotStatus> slotStatusList) {
        if (sLastSlotStatus == null || sLastSlotStatus.size() != slotStatusList.size()) {
            return true;
        }
        for (IccSlotStatus iccSlotStatus : slotStatusList) {
            if (!sLastSlotStatus.contains(iccSlotStatus)) {
                return true;
            }
        }
        return false;
    }

    private void logPhoneIdToSlotIdMapping() {
        log("mPhoneIdToSlotId mapping:");
        for (int i = 0; i < mPhoneIdToSlotId.length; i++) {
            log("    phoneId " + i + " slotId " + mPhoneIdToSlotId[i]);
        }
    }

    private void onSimRefresh(AsyncResult ar, Integer index) {
        if (ar.exception != null) {
            Rlog.e(LOG_TAG, "onSimRefresh: Sim REFRESH with exception: " + ar.exception);
            return;
        }

        if (!isValidPhoneIndex(index)) {
            Rlog.e(LOG_TAG,"onSimRefresh: invalid index : " + index);
            return;
        }

        IccRefreshResponse resp = (IccRefreshResponse) ar.result;
        log("onSimRefresh: " + resp);
        sLocalLog.log("onSimRefresh: " + resp);

        if (resp == null) {
            Rlog.e(LOG_TAG, "onSimRefresh: received without input");
            return;
        }

        UiccCard uiccCard = getUiccCardForPhone(index);
        if (uiccCard == null) {
            Rlog.e(LOG_TAG,"onSimRefresh: refresh on null card : " + index);
            return;
        }

        boolean changed = false;
        switch(resp.refreshResult) {
            case IccRefreshResponse.REFRESH_RESULT_RESET:
            case IccRefreshResponse.REFRESH_RESULT_INIT:
                 // Reset the required apps when we know about the refresh so that
                 // anyone interested does not get stale state.
                 changed = uiccCard.resetAppWithAid(resp.aid);
                 break;
            default:
                 return;
        }

        if (changed && resp.refreshResult == IccRefreshResponse.REFRESH_RESULT_RESET) {
            // If there is any change on RESET, reset carrier config as well. From carrier config
            // perspective, this is treated the same as sim state unknown
            CarrierConfigManager configManager = (CarrierConfigManager)
                    mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
            configManager.updateConfigForPhoneId(index, IccCardConstants.INTENT_VALUE_ICC_UNKNOWN);

            boolean requirePowerOffOnSimRefreshReset = mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_requireRadioPowerOffOnSimRefreshReset);
            if (requirePowerOffOnSimRefreshReset) {
                mCis[index].setRadioPower(false, null);
            }
        }

        // The card status could have changed. Get the latest state.
        mCis[index].getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE, index));
    }

    private boolean isValidPhoneIndex(int index) {
        return (index >= 0 && index < TelephonyManager.getDefault().getPhoneCount());
    }

    private boolean isValidSlotIndex(int index) {
        return (index >= 0 && index < mUiccSlots.length);
    }

    private void log(String string) {
        Rlog.d(LOG_TAG, string);
    }

    public void addCardLog(String data) {
        sLocalLog.log(data);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("UiccController: " + this);
        pw.println(" mContext=" + mContext);
        pw.println(" mInstance=" + mInstance);
        pw.println(" mIccChangedRegistrants: size=" + mIccChangedRegistrants.size());
        for (int i = 0; i < mIccChangedRegistrants.size(); i++) {
            pw.println("  mIccChangedRegistrants[" + i + "]="
                    + ((Registrant)mIccChangedRegistrants.get(i)).getHandler());
        }
        pw.println();
        pw.flush();
        pw.println(" mUiccSlots: size=" + mUiccSlots.length);
        for (int i = 0; i < mUiccSlots.length; i++) {
            if (mUiccSlots[i] == null) {
                pw.println("  mUiccSlots[" + i + "]=null");
            } else {
                pw.println("  mUiccSlots[" + i + "]=" + mUiccSlots[i]);
                mUiccSlots[i].dump(fd, pw, args);
            }
        }
        pw.println(" sLocalLog= ");
        sLocalLog.dump(fd, pw, args);
    }
}
