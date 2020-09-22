/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.telephony.vendor;

import android.content.Context;
import android.os.Looper;
import android.telephony.Rlog;
import android.telephony.TelephonyManager;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.SubscriptionInfoUpdater;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.UiccSlot;
import com.android.internal.telephony.uicc.IccUtils;


/**
 * To reduce delay in SubInfo records availability to clients, add subInfo record
 * to table without waiting for SIM state moves to LOADED.
 */
public class VendorSubscriptionInfoUpdater extends SubscriptionInfoUpdater {
    private static final String LOG_TAG = "VendorSubscriptionInfoUpdater";

    private static final String ICCID_STRING_FOR_NO_SIM = "";
    private static Context sContext = null;

    protected boolean[] mIsRecordUpdateRequired;
    protected static VendorSubscriptionInfoUpdater sInstance = null;
    private static final int SUPPORTED_MODEM_COUNT = TelephonyManager.getDefault()
            .getSupportedModemCount();

    static VendorSubscriptionInfoUpdater init(Looper looper, Context context,
            CommandsInterface[] ci) {
        synchronized (VendorSubscriptionInfoUpdater.class) {
            if (sInstance == null) {
                sInstance = new VendorSubscriptionInfoUpdater(looper, context, ci);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    public static VendorSubscriptionInfoUpdater getInstance() {
        if (sInstance == null) {
            Log.wtf(LOG_TAG, "getInstance null");
        }
        return sInstance;
    }

    protected VendorSubscriptionInfoUpdater(Looper looper, Context context,
            CommandsInterface[] ci) {
        super(looper, context, ci);
        sContext = context;
        mIsRecordUpdateRequired = new boolean[SUPPORTED_MODEM_COUNT];

        for (int index = 0; index < SUPPORTED_MODEM_COUNT; index++) {
            mIsRecordUpdateRequired[index] = true;
        }
    }

    @Override
    protected void handleSimReady(int phoneId) {
        List<Integer> cardIds = new ArrayList<>();
        Rlog.d(LOG_TAG, "handleSimReady: phoneId: " + phoneId);

        if (sIccId[phoneId] != null && sIccId[phoneId].equals(ICCID_STRING_FOR_NO_SIM)) {
            Rlog.d(LOG_TAG, " SIM" + (phoneId + 1) + " hot plug in");
            sIccId[phoneId] = null;
        }
        UiccSlot uiccSlot = UiccController.getInstance().getUiccSlotForPhone(phoneId);
        if (uiccSlot == null) {
            Rlog.d(LOG_TAG, "handleSimReady: uiccSlot null");
            return;
        }

        String iccId = uiccSlot.getIccId();
        if (IccUtils.stripTrailingFs(iccId) == null) {
            Rlog.d(LOG_TAG, "handleSimReady: IccID null");
            return;
        }
        sIccId[phoneId] = IccUtils.stripTrailingFs(iccId);

        updateSubscriptionInfoByIccId(phoneId, true /* updateEmbeddedSubs */);

        cardIds.add(getCardIdFromPhoneId(phoneId));
        updateEmbeddedSubscriptions(cardIds, (hasChanges) -> {
            if (hasChanges) {
                SubscriptionController.getInstance().notifySubscriptionInfoChanged();
            }
        });
        broadcastSimStateChanged(phoneId, IccCardConstants.INTENT_VALUE_ICC_READY, null);
        broadcastSimCardStateChanged(phoneId, TelephonyManager.SIM_STATE_PRESENT);
        broadcastSimApplicationStateChanged(phoneId, TelephonyManager.SIM_STATE_NOT_READY);
    }

    @Override
    protected void handleSimLoaded(int phoneId) {
        // mIsRecordUpdateRequired set to false if sIccId has a valid Iccid to skip
        // adding subId once again from here.
        if ((sIccId[phoneId] != null) && (sIccId[phoneId] != ICCID_STRING_FOR_NO_SIM)) {
            mIsRecordUpdateRequired[phoneId] = false;
        }
        Rlog.d(LOG_TAG, "handleSimLoaded: phoneId: " + phoneId);
        super.handleSimLoaded(phoneId);
    }

    @Override
    synchronized protected void updateSubscriptionInfoByIccId(int phoneId,
        boolean updateEmbeddedSubs) {

        if (mIsRecordUpdateRequired[phoneId] == true) {
            super.updateSubscriptionInfoByIccId(phoneId, updateEmbeddedSubs);
        } else {
            Rlog.d(LOG_TAG, "Ignoring subscription update event " + phoneId);
            mIsRecordUpdateRequired[phoneId] = true;
        }
    }
}
