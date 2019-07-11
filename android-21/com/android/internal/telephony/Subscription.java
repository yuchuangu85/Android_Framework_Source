/*
 * Copyright (c) 2010-2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.internal.telephony;

import android.text.TextUtils;

import android.telephony.Rlog;

/**
 * Class holding all the information of a subscription from UICC Card.
 */
public final class Subscription {
    private static final String LOG_TAG = "Subscription";

    public int slotId;                       // Slot id
    public int m3gppIndex;                   // Subscription index in the card for GSM
    public int m3gpp2Index;                  // Subscription index in the card for CDMA
    public int subId;                        // SUB 0 or SUB 1
    public SubscriptionStatus subStatus;      // DEACTIVATE = 0, ACTIVATE = 1,
                                             // ACTIVATED = 2, DEACTIVATED = 3, INVALID = 4;
    public String appId;
    public String appLabel;
    public String appType;
    public String iccId;

    private boolean DEBUG = false;

    /**
     * Subscription activation status
     */
    public enum SubscriptionStatus {
        SUB_DEACTIVATE,
            SUB_ACTIVATE,
            SUB_ACTIVATED,
            SUB_DEACTIVATED,
            SUB_INVALID
    }

    public static final int SUBSCRIPTION_INDEX_INVALID = -1;

    public Subscription() {
        clear();
    }

    public String toString() {
        return "Subscription = { "
            + "slotId = " + slotId
            + ", 3gppIndex = " + m3gppIndex
            + ", 3gpp2Index = " + m3gpp2Index
            + ", subId = " + subId
            + ", subStatus = " + subStatus
            + ", appId = " + appId
            + ", appLabel = " + appLabel
            + ", appType = " + appType
            + ", iccId = " + iccId + " }";
    }

    public boolean equals(Subscription sub) {
        if (sub != null) {
            if ((slotId == sub.slotId) && (m3gppIndex == sub.m3gppIndex)
                    && (m3gpp2Index == sub.m3gpp2Index) && (subId == sub.subId)
                    && (subStatus == sub.subStatus)
                    && ((TextUtils.isEmpty(appId) && TextUtils.isEmpty(sub.appId))
                            || TextUtils.equals(appId, sub.appId))
                    && ((TextUtils.isEmpty(appLabel) && TextUtils.isEmpty(sub.appLabel))
                            || TextUtils.equals(appLabel, sub.appLabel))
                    && ((TextUtils.isEmpty(appType) && TextUtils.isEmpty(sub.appType))
                            || TextUtils.equals(appType, sub.appType))
                    && ((TextUtils.isEmpty(iccId) && TextUtils.isEmpty(sub.iccId))
                            || TextUtils.equals(iccId, sub.iccId))) {
                return true;
            }
        } else {
            Rlog.d(LOG_TAG, "Subscription.equals: sub == null");
        }
        return false;
    }

    /**
     * Return true if the appIndex, appId, appLabel and iccId are matching.
     * @param sub
     * @return
     */
    public boolean isSame(Subscription sub) {
        // Not checking the subId, subStatus and slotId, which are related to the
        // activated status
        if (sub != null) {
            if (DEBUG) {
                Rlog.d(LOG_TAG, "isSame(): this = " + m3gppIndex
                        + ":" + m3gpp2Index
                        + ":" + appId
                        + ":" + appType
                        + ":" + iccId);
                Rlog.d(LOG_TAG, "compare with = " + sub.m3gppIndex
                        + ":" + sub.m3gpp2Index
                        + ":" + sub.appId
                        + ":" + sub.appType
                        + ":" + sub.iccId);
            }
            if ((m3gppIndex == sub.m3gppIndex)
                    && (m3gpp2Index == sub.m3gpp2Index)
                    && ((TextUtils.isEmpty(appId) && TextUtils.isEmpty(sub.appId))
                            || TextUtils.equals(appId, sub.appId))
                    && ((TextUtils.isEmpty(appType) && TextUtils.isEmpty(sub.appType))
                            || TextUtils.equals(appType, sub.appType))
                    && ((TextUtils.isEmpty(iccId) && TextUtils.isEmpty(sub.iccId))
                            || TextUtils.equals(iccId, sub.iccId))){
                return true;
            }
        }
        return false;
    }

    /**
     * Reset the subscription
     */
    public void clear() {
        slotId = SUBSCRIPTION_INDEX_INVALID;
        m3gppIndex = SUBSCRIPTION_INDEX_INVALID;
        m3gpp2Index = SUBSCRIPTION_INDEX_INVALID;
        subId = SUBSCRIPTION_INDEX_INVALID;
        subStatus = SubscriptionStatus.SUB_INVALID;
        appId = null;
        appLabel = null;
        appType = null;
        iccId = null;
    }

    /**
     * Copies the subscription parameters
     * @param from
     * @return
     */
    public Subscription copyFrom(Subscription from) {
        if (from != null) {
            slotId = from.slotId;
            m3gppIndex = from.m3gppIndex;
            m3gpp2Index = from.m3gpp2Index;
            subId = from.subId;
            subStatus = from.subStatus;
            if (from.appId != null) {
                appId = new String(from.appId);
            }
            if (from.appLabel != null) {
                appLabel = new String(from.appLabel);
            }
            if (from.appType != null) {
                appType = new String(from.appType);
            }
            if (from.iccId != null) {
                iccId = new String(from.iccId);
            }
        }

        return this;
    }

    /**
     * Return the valid app index (either 3gpp or 3gpp2 index)
     * @return
     */
    public int getAppIndex() {
        if (this.m3gppIndex != SUBSCRIPTION_INDEX_INVALID) {
            return this.m3gppIndex;
        } else {
            return this.m3gpp2Index;
        }
    }
}
