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

import static android.telephony.TelephonyManager.UNINITIALIZED_CARD_ID;
import static android.telephony.TelephonyManager.UNSUPPORTED_CARD_ID;

import android.annotation.UnsupportedAppUsage;
import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.storage.StorageManager;
import android.preference.PreferenceManager;
import android.telephony.CarrierConfigManager;
import android.telephony.Rlog;
import android.telephony.TelephonyManager;
import android.telephony.UiccCardInfo;
import android.text.TextUtils;
import android.util.LocalLog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.RadioConfig;
import com.android.internal.telephony.SubscriptionInfoUpdater;
import com.android.internal.telephony.uicc.euicc.EuiccCard;

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
    private static final int EVENT_EID_READY = 9;

    // this needs to be here, because on bootup we dont know which index maps to which UiccSlot
    @UnsupportedAppUsage
    private CommandsInterface[] mCis;
    @VisibleForTesting
    public UiccSlot[] mUiccSlots;
    private int[] mPhoneIdToSlotId;
    private boolean mIsSlotStatusSupported = true;

    // This maps the externally exposed card ID (int) to the internal card ID string (ICCID/EID).
    // The array index is the card ID (int).
    // This mapping exists to expose card-based functionality without exposing the EID, which is
    // considered sensetive information.
    // mCardStrings is populated using values from the IccSlotStatus and IccCardStatus. For
    // HAL < 1.2, these do not contain the EID or the ICCID, so mCardStrings will be empty
    private ArrayList<String> mCardStrings;

    // This is the card ID of the default eUICC. It starts as UNINITIALIZED_CARD_ID.
    // When we load the EID (either with slot status or from the EuiccCard), we set it to the eUICC
    // with the lowest slot index.
    // If EID is not supported (e.g. on HAL version < 1.2), we set it to UNSUPPORTED_CARD_ID
    private int mDefaultEuiccCardId;

    // Default Euicc Card ID used when the device is temporarily unable to read the EID (e.g. on HAL
    // 1.2-1.3 if the eUICC is currently inactive). This value is only used within the
    // UiccController and should be converted to UNSUPPORTED_CARD_ID when others ask.
    // (This value is -3 because UNSUPPORTED_CARD_ID and UNINITIALIZED_CARD_ID are -1 and -2)
    private static final int TEMPORARILY_UNSUPPORTED_CARD_ID = -3;

    // GSM SGP.02 section 2.2.2 states that the EID is always 32 digits long
    private static final int EID_LENGTH = 32;

    // SharedPreference key for saving the known card strings (ICCIDs and EIDs) ordered by card ID
    private static final String CARD_STRINGS = "card_strings";

    // SharedPreferences key for saving the default euicc card ID
    private static final String DEFAULT_CARD = "default_card";

    @UnsupportedAppUsage
    private static final Object mLock = new Object();
    @UnsupportedAppUsage
    private static UiccController mInstance;
    private static ArrayList<IccSlotStatus> sLastSlotStatus;

    @UnsupportedAppUsage
    @VisibleForTesting
    public Context mContext;

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
        mCardStrings = loadCardStrings();
        mDefaultEuiccCardId = UNINITIALIZED_CARD_ID;
    }

    /**
     * Given the slot index, return the phone ID, or -1 if no phone is associated with the given
     * slot.
     * @param slotId the slot index to check
     * @return the associated phone ID or -1
     */
    public int getPhoneIdFromSlotId(int slotId) {
        for (int i = 0; i < mPhoneIdToSlotId.length; i++) {
            if (mPhoneIdToSlotId[i] == slotId) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Return the physical slot id associated with the given phoneId, or INVALID_SLOT_ID.
     * @param phoneId the phoneId to check
     */
    public int getSlotIdFromPhoneId(int phoneId) {
        try {
            return mPhoneIdToSlotId[phoneId];
        } catch (ArrayIndexOutOfBoundsException e) {
            return INVALID_SLOT_ID;
        }
    }

    @UnsupportedAppUsage
    public static UiccController getInstance() {
        synchronized (mLock) {
            if (mInstance == null) {
                throw new RuntimeException(
                        "UiccController.getInstance can't be called before make()");
            }
            return mInstance;
        }
    }

    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
                case EVENT_EID_READY:
                    if (DBG) log("Received EVENT_EID_READY");
                    onEidReady(ar, phoneId);
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
    @UnsupportedAppUsage
    public UiccCardApplication getUiccCardApplication(int phoneId, int family) {
        synchronized (mLock) {
            UiccCard uiccCard = getUiccCardForPhone(phoneId);
            if (uiccCard != null) {
                return uiccCard.getApplication(family);
            }
            return null;
        }
    }

    static String getIccStateIntentString(IccCardConstants.State state) {
        switch (state) {
            case ABSENT: return IccCardConstants.INTENT_VALUE_ICC_ABSENT;
            case PIN_REQUIRED: return IccCardConstants.INTENT_VALUE_ICC_LOCKED;
            case PUK_REQUIRED: return IccCardConstants.INTENT_VALUE_ICC_LOCKED;
            case NETWORK_LOCKED: return IccCardConstants.INTENT_VALUE_ICC_LOCKED;
            case READY: return IccCardConstants.INTENT_VALUE_ICC_READY;
            case NOT_READY: return IccCardConstants.INTENT_VALUE_ICC_NOT_READY;
            case PERM_DISABLED: return IccCardConstants.INTENT_VALUE_ICC_LOCKED;
            case CARD_IO_ERROR: return IccCardConstants.INTENT_VALUE_ICC_CARD_IO_ERROR;
            case CARD_RESTRICTED: return IccCardConstants.INTENT_VALUE_ICC_CARD_RESTRICTED;
            case LOADED: return IccCardConstants.INTENT_VALUE_ICC_LOADED;
            default: return IccCardConstants.INTENT_VALUE_ICC_UNKNOWN;
        }
    }

    static void updateInternalIccState(Context context, IccCardConstants.State state, String reason,
            int phoneId) {
        updateInternalIccState(context, state, reason, phoneId, false);
    }

    // absentAndInactive is a special case when we need to update subscriptions but don't want to
    // broadcast a state change
    static void updateInternalIccState(Context context, IccCardConstants.State state, String reason,
            int phoneId, boolean absentAndInactive) {
        TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(
                Context.TELEPHONY_SERVICE);
        telephonyManager.setSimStateForPhone(phoneId, state.toString());

        SubscriptionInfoUpdater subInfoUpdator = PhoneFactory.getSubscriptionInfoUpdater();
        if (subInfoUpdator != null) {
            subInfoUpdator.updateInternalIccState(getIccStateIntentString(state),
                    reason, phoneId, absentAndInactive);
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

        if (eidIsNotSupported(status)) {
            // we will never get EID from the HAL, so set mDefaultEuiccCardId to UNSUPPORTED_CARD_ID
            if (DBG) log("eid is not supported");
            mDefaultEuiccCardId = UNSUPPORTED_CARD_ID;
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

        mUiccSlots[slotId].update(mCis[index], status, index, slotId);

        UiccCard card = mUiccSlots[slotId].getUiccCard();
        if (card == null) {
            if (DBG) log("mUiccSlots[" + slotId + "] has no card. Notifying IccChangedRegistrants");
            mIccChangedRegistrants.notifyRegistrants(new AsyncResult(null, index, null));
            return;
        }

        String cardString = null;
        boolean isEuicc = mUiccSlots[slotId].isEuicc();
        if (isEuicc) {
            cardString = ((EuiccCard) card).getEid();
        } else {
            cardString = card.getIccId();
        }

        // EID may be unpopulated if RadioConfig<1.2
        // If so, just register for EID loaded and skip this stuff
        if (isEuicc && cardString == null
                && mDefaultEuiccCardId != UNSUPPORTED_CARD_ID) {
            ((EuiccCard) card).registerForEidReady(this, EVENT_EID_READY, index);
        }

        if (cardString != null) {
            addCardId(cardString);
        }

        if (DBG) log("Notifying IccChangedRegistrants");
        mIccChangedRegistrants.notifyRegistrants(new AsyncResult(null, index, null));
    }

    /**
     * Returns true if EID is not supproted.
     */
    private boolean eidIsNotSupported(IccCardStatus status) {
        // if card status does not contain slot ID, we know we are on HAL < 1.2, so EID will never
        // be available
        return status.physicalSlotIndex == INVALID_SLOT_ID;
    }

    /**
     * Add a cardString to mCardStrings. If this is an ICCID, trailing Fs will be automatically
     * stripped.
     */
    private void addCardId(String cardString) {
        if (TextUtils.isEmpty(cardString)) {
            return;
        }
        if (cardString.length() < EID_LENGTH) {
            cardString = IccUtils.stripTrailingFs(cardString);
        }
        if (!mCardStrings.contains(cardString)) {
            mCardStrings.add(cardString);
            saveCardStrings();
        }
    }

    /**
     * Converts the card string (the ICCID/EID, formerly named card ID) to the public int cardId.
     * If the given cardString is an ICCID, trailing Fs will be automatically stripped before trying
     * to match to a card ID.
     *
     * @return the matching cardId, or UNINITIALIZED_CARD_ID if the card string does not map to a
     * currently loaded cardId, or UNSUPPORTED_CARD_ID if the device does not support card IDs
     */
    public int convertToPublicCardId(String cardString) {
        if (mDefaultEuiccCardId == UNSUPPORTED_CARD_ID) {
            // even if cardString is not an EID, if EID is not supported (e.g. HAL < 1.2) we can't
            // guarentee a working card ID implementation, so return UNSUPPORTED_CARD_ID
            return UNSUPPORTED_CARD_ID;
        }
        if (TextUtils.isEmpty(cardString)) {
            return UNINITIALIZED_CARD_ID;
        }

        if (cardString.length() < EID_LENGTH) {
            cardString = IccUtils.stripTrailingFs(cardString);
        }
        int id = mCardStrings.indexOf(cardString);
        if (id == -1) {
            return UNINITIALIZED_CARD_ID;
        } else {
            return id;
        }
    }

    /**
     * Returns the UiccCardInfo of all currently inserted UICCs and embedded eUICCs.
     */
    public ArrayList<UiccCardInfo> getAllUiccCardInfos() {
        ArrayList<UiccCardInfo> infos = new ArrayList<>();
        for (int slotIndex = 0; slotIndex < mUiccSlots.length; slotIndex++) {
            final UiccSlot slot = mUiccSlots[slotIndex];
            if (slot == null) continue;
            boolean isEuicc = slot.isEuicc();
            String eid = null;
            UiccCard card = slot.getUiccCard();
            String iccid = null;
            int cardId = UNINITIALIZED_CARD_ID;
            boolean isRemovable = slot.isRemovable();

            // first we try to populate UiccCardInfo using the UiccCard, but if it doesn't exist
            // (e.g. the slot is for an inactive eUICC) then we try using the UiccSlot.
            if (card != null) {
                iccid = card.getIccId();
                if (isEuicc) {
                    eid = ((EuiccCard) card).getEid();
                    cardId = convertToPublicCardId(eid);
                } else {
                    // leave eid null if the UICC is not embedded
                    cardId = convertToPublicCardId(iccid);
                }
            } else {
                iccid = slot.getIccId();
                // Fill in the fields we can
                if (!isEuicc && !TextUtils.isEmpty(iccid)) {
                    cardId = convertToPublicCardId(iccid);
                }
            }
            UiccCardInfo info = new UiccCardInfo(isEuicc, cardId, eid,
                    IccUtils.stripTrailingFs(iccid), slotIndex, isRemovable);
            infos.add(info);
        }
        return infos;
    }

    /**
     * Get the card ID of the default eUICC.
     */
    public int getCardIdForDefaultEuicc() {
        if (mDefaultEuiccCardId == TEMPORARILY_UNSUPPORTED_CARD_ID) {
            return UNSUPPORTED_CARD_ID;
        }
        return mDefaultEuiccCardId;
    }

    private ArrayList<String> loadCardStrings() {
        String cardStrings =
                PreferenceManager.getDefaultSharedPreferences(mContext).getString(CARD_STRINGS, "");
        if (TextUtils.isEmpty(cardStrings)) {
            // just return an empty list, since String.split would return the list { "" }
            return new ArrayList<String>();
        }
        return new ArrayList<String>(Arrays.asList(cardStrings.split(",")));
    }

    private void saveCardStrings() {
        SharedPreferences.Editor editor =
                PreferenceManager.getDefaultSharedPreferences(mContext).edit();
        editor.putString(CARD_STRINGS, TextUtils.join(",", mCardStrings));
        editor.commit();
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
        boolean isDefaultEuiccCardIdSet = false;
        boolean anyEuiccIsActive = false;
        boolean hasEuicc = false;
        for (int i = 0; i < status.size(); i++) {
            IccSlotStatus iss = status.get(i);
            boolean isActive = (iss.slotState == IccSlotStatus.SlotState.SLOTSTATE_ACTIVE);
            if (isActive) {
                numActiveSlots++;

                // sanity check: logicalSlotIndex should be valid for an active slot
                if (!isValidPhoneIndex(iss.logicalSlotIndex)) {
                    Rlog.e(LOG_TAG, "Skipping slot " + i + " as phone " + iss.logicalSlotIndex
                               + " is not available to communicate with this slot");

                } else {
                    mPhoneIdToSlotId[iss.logicalSlotIndex] = i;
                }
            }

            if (mUiccSlots[i] == null) {
                if (VDBG) {
                    log("Creating mUiccSlot[" + i + "]; mUiccSlots.length = " + mUiccSlots.length);
                }
                mUiccSlots[i] = new UiccSlot(mContext, isActive);
            }

            if (!isValidPhoneIndex(iss.logicalSlotIndex)) {
                mUiccSlots[i].update(null, iss, i /* slotIndex */);
            } else {
                mUiccSlots[i].update(isActive ? mCis[iss.logicalSlotIndex] : null, iss,
                        i /* slotIndex */);
            }

            if (mUiccSlots[i].isEuicc()) {
                hasEuicc = true;
                if (isActive) {
                    anyEuiccIsActive = true;
                }
                String eid = iss.eid;
                if (TextUtils.isEmpty(eid)) {
                    // iss.eid is not populated on HAL<1.4
                    continue;
                }

                addCardId(eid);

                // whenever slot status is received, set default card to the eUICC with the
                // lowest slot index.
                if (!isDefaultEuiccCardIdSet) {
                    isDefaultEuiccCardIdSet = true;
                    // TODO(b/122738148) the default eUICC should not be removable
                    mDefaultEuiccCardId = convertToPublicCardId(eid);
                    log("Using eid=" + eid + " in slot=" + i + " to set mDefaultEuiccCardId="
                            + mDefaultEuiccCardId);
                }
            }
        }

        if (hasEuicc && !anyEuiccIsActive && !isDefaultEuiccCardIdSet) {
            log("onGetSlotStatusDone: setting TEMPORARILY_UNSUPPORTED_CARD_ID");
            mDefaultEuiccCardId = TEMPORARILY_UNSUPPORTED_CARD_ID;
        }

        if (VDBG) logPhoneIdToSlotIdMapping();

        // sanity check: number of active slots should be valid
        if (numActiveSlots != mPhoneIdToSlotId.length) {
            Rlog.e(LOG_TAG, "Number of active slots " + numActiveSlots
                       + " does not match the number of Phones" + mPhoneIdToSlotId.length);
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
        final BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setBackgroundActivityStartsAllowed(true);
        Intent intent = new Intent(TelephonyManager.ACTION_SIM_SLOT_STATUS_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        mContext.sendBroadcast(intent, android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                options.toBundle());
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
            // Reset the required apps when we know about the refresh so that
            // anyone interested does not get stale state.
            case IccRefreshResponse.REFRESH_RESULT_RESET:
                changed = uiccCard.resetAppWithAid(resp.aid, true /* reset */);
                break;
            case IccRefreshResponse.REFRESH_RESULT_INIT:
                // don't dispose CatService on SIM REFRESH of type INIT
                changed = uiccCard.resetAppWithAid(resp.aid, false /* initialize */);
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

    // for RadioConfig 1.2 or higher, the EID comes with the IccSlotStatus
    // for RadioConfig<1.2 we register for EID ready set mCardStrings and mDefaultEuiccCardId here
    private void onEidReady(AsyncResult ar, Integer index) {
        if (ar.exception != null) {
            Rlog.e(LOG_TAG, "onEidReady: exception: " + ar.exception);
            return;
        }

        if (!isValidPhoneIndex(index)) {
            Rlog.e(LOG_TAG, "onEidReady: invalid index: " + index);
            return;
        }
        int slotId = mPhoneIdToSlotId[index];
        UiccCard card = mUiccSlots[slotId].getUiccCard();
        if (card == null) {
            Rlog.e(LOG_TAG, "onEidReady: UiccCard in slot " + slotId + " is null");
            return;
        }

        // set mCardStrings and the defaultEuiccCardId using the now available EID
        String eid = ((EuiccCard) card).getEid();
        addCardId(eid);
        if (mDefaultEuiccCardId == UNINITIALIZED_CARD_ID
                || mDefaultEuiccCardId == TEMPORARILY_UNSUPPORTED_CARD_ID) {
            // TODO(b/122738148) the default eUICC should not be removable
            mDefaultEuiccCardId = convertToPublicCardId(eid);
            log("onEidReady: eid=" + eid + " slot=" + slotId + " mDefaultEuiccCardId="
                    + mDefaultEuiccCardId);
        }
        ((EuiccCard) card).unregisterForEidReady(this);
    }

    /**
     * static method to return whether CDMA is supported on the device
     * @param context object representative of the application that is calling this method
     * @return true if CDMA is supported by the device
     */
    public static boolean isCdmaSupported(Context context) {
        PackageManager packageManager = context.getPackageManager();
        boolean isCdmaSupported =
                packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CDMA);
        return isCdmaSupported;
    }

    private boolean isValidPhoneIndex(int index) {
        return (index >= 0 && index < TelephonyManager.getDefault().getPhoneCount());
    }

    private boolean isValidSlotIndex(int index) {
        return (index >= 0 && index < mUiccSlots.length);
    }

    @UnsupportedAppUsage
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
        pw.println(" mIsCdmaSupported=" + isCdmaSupported(mContext));
        pw.println(" mUiccSlots: size=" + mUiccSlots.length);
        pw.println(" mCardStrings=" + mCardStrings);
        pw.println(" mDefaultEuiccCardId=" + mDefaultEuiccCardId);
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
