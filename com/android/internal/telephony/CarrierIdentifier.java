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

import static android.provider.Telephony.CarrierId;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.provider.Telephony;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;

import com.android.internal.telephony.metrics.TelephonyMetrics;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.UiccProfile;
import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CarrierIdentifier identifies the subscription carrier and returns a canonical carrier Id
 * and a user friendly carrier name. CarrierIdentifier reads subscription info and check against
 * all carrier matching rules stored in CarrierIdProvider. It is msim aware, each phone has a
 * dedicated CarrierIdentifier.
 */
public class CarrierIdentifier extends Handler {
    private static final String LOG_TAG = CarrierIdentifier.class.getSimpleName();
    private static final boolean DBG = true;
    private static final boolean VDBG = Rlog.isLoggable(LOG_TAG, Log.VERBOSE);

    // events to trigger carrier identification
    private static final int SIM_LOAD_EVENT             = 1;
    private static final int SIM_ABSENT_EVENT           = 2;
    private static final int SPN_OVERRIDE_EVENT         = 3;
    private static final int ICC_CHANGED_EVENT          = 4;
    private static final int PREFER_APN_UPDATE_EVENT    = 5;
    private static final int CARRIER_ID_DB_UPDATE_EVENT = 6;

    private static final Uri CONTENT_URL_PREFER_APN = Uri.withAppendedPath(
            Telephony.Carriers.CONTENT_URI, "preferapn");
    private static final String OPERATOR_BRAND_OVERRIDE_PREFIX = "operator_branding_";

    // cached matching rules based mccmnc to speed up resolution
    private List<CarrierMatchingRule> mCarrierMatchingRulesOnMccMnc = new ArrayList<>();
    // cached carrier Id
    private int mCarrierId = TelephonyManager.UNKNOWN_CARRIER_ID;
    // cached carrier name
    private String mCarrierName;
    // cached preferapn name
    private String mPreferApn;
    // cached service provider name. telephonyManager API returns empty string as default value.
    // some carriers need to target devices with Empty SPN. In that case, carrier matching rule
    // should specify "" spn explicitly.
    private String mSpn = "";

    private Context mContext;
    private Phone mPhone;
    private IccRecords mIccRecords;
    private UiccProfile mUiccProfile;
    private final LocalLog mCarrierIdLocalLog = new LocalLog(20);
    private final TelephonyManager mTelephonyMgr;
    private final SubscriptionsChangedListener mOnSubscriptionsChangedListener =
            new SubscriptionsChangedListener();

    private final ContentObserver mContentObserver = new ContentObserver(this) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (CONTENT_URL_PREFER_APN.equals(uri.getLastPathSegment())) {
                logd("onChange URI: " + uri);
                sendEmptyMessage(PREFER_APN_UPDATE_EVENT);
            } else if (CarrierId.All.CONTENT_URI.equals(uri)) {
                logd("onChange URI: " + uri);
                sendEmptyMessage(CARRIER_ID_DB_UPDATE_EVENT);
            }
        }
    };

    private class SubscriptionsChangedListener
            extends SubscriptionManager.OnSubscriptionsChangedListener {
        final AtomicInteger mPreviousSubId =
                new AtomicInteger(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        /**
         * Callback invoked when there is any change to any SubscriptionInfo. Typically
         * this method would invoke {@link SubscriptionManager#getActiveSubscriptionInfoList}
         */
        @Override
        public void onSubscriptionsChanged() {
            int subId = mPhone.getSubId();
            if (mPreviousSubId.getAndSet(subId) != subId) {
                if (DBG) {
                    logd("SubscriptionListener.onSubscriptionInfoChanged subId: "
                            + mPreviousSubId);
                }
                if (SubscriptionManager.isValidSubscriptionId(subId)) {
                    sendEmptyMessage(SIM_LOAD_EVENT);
                } else {
                    sendEmptyMessage(SIM_ABSENT_EVENT);
                }
            }
        }
    }

    public CarrierIdentifier(Phone phone) {
        logd("Creating CarrierIdentifier[" + phone.getPhoneId() + "]");
        mContext = phone.getContext();
        mPhone = phone;
        mTelephonyMgr = TelephonyManager.from(mContext);

        // register events
        mContext.getContentResolver().registerContentObserver(CONTENT_URL_PREFER_APN, false,
                mContentObserver);
        mContext.getContentResolver().registerContentObserver(
                CarrierId.All.CONTENT_URI, false, mContentObserver);
        SubscriptionManager.from(mContext).addOnSubscriptionsChangedListener(
                mOnSubscriptionsChangedListener);
        UiccController.getInstance().registerForIccChanged(this, ICC_CHANGED_EVENT, null);
    }

    /**
     * Entry point for the carrier identification.
     *
     *    1. SIM_LOAD_EVENT
     *        This indicates that all SIM records has been loaded and its first entry point for the
     *        carrier identification. Note, there are other attributes could be changed on the fly
     *        like APN and SPN. We cached all carrier matching rules based on MCCMNC to speed
     *        up carrier resolution on following trigger events.
     *
     *    2. PREFER_APN_UPDATE_EVENT
     *        This indicates prefer apn has been changed. It could be triggered when user modified
     *        APN settings or when default data connection first establishes on the current carrier.
     *        We follow up on this by querying prefer apn sqlite and re-issue carrier identification
     *        with the updated prefer apn name.
     *
     *    3. SPN_OVERRIDE_EVENT
     *        This indicates that SPN value as been changed. It could be triggered from EF_SPN
     *        record loading, carrier config override
     *        {@link android.telephony.CarrierConfigManager#KEY_CARRIER_NAME_STRING}
     *        or carrier app override {@link TelephonyManager#setOperatorBrandOverride(String)}.
     *        we follow up this by checking the cached mSPN against the latest value and issue
     *        carrier identification only if spn changes.
     *
     *    4. CARRIER_ID_DB_UPDATE_EVENT
     *        This indicates that carrierIdentification database which stores all matching rules
     *        has been updated. It could be triggered from OTA or assets update.
     */
    @Override
    public void handleMessage(Message msg) {
        if (VDBG) logd("handleMessage: " + msg.what);
        switch (msg.what) {
            case SIM_LOAD_EVENT:
            case CARRIER_ID_DB_UPDATE_EVENT:
                mSpn = mTelephonyMgr.getSimOperatorNameForPhone(mPhone.getPhoneId());
                mPreferApn = getPreferApn();
                loadCarrierMatchingRulesOnMccMnc();
                break;
            case SIM_ABSENT_EVENT:
                mCarrierMatchingRulesOnMccMnc.clear();
                mSpn = null;
                mPreferApn = null;
                updateCarrierIdAndName(TelephonyManager.UNKNOWN_CARRIER_ID, null);
                break;
            case PREFER_APN_UPDATE_EVENT:
                String preferApn = getPreferApn();
                if (!equals(mPreferApn, preferApn, true)) {
                    logd("[updatePreferApn] from:" + mPreferApn + " to:" + preferApn);
                    mPreferApn = preferApn;
                    matchCarrier();
                }
                break;
            case SPN_OVERRIDE_EVENT:
                String spn = mTelephonyMgr.getSimOperatorNameForPhone(mPhone.getPhoneId());
                if (!equals(mSpn, spn, true)) {
                    logd("[updateSpn] from:" + mSpn + " to:" + spn);
                    mSpn = spn;
                    matchCarrier();
                }
                break;
            case ICC_CHANGED_EVENT:
                // all records used for carrier identification are from SimRecord
                final IccRecords newIccRecords = UiccController.getInstance().getIccRecords(
                        mPhone.getPhoneId(), UiccController.APP_FAM_3GPP);
                if (mIccRecords != newIccRecords) {
                    if (mIccRecords != null) {
                        logd("Removing stale icc objects.");
                        mIccRecords.unregisterForRecordsLoaded(this);
                        mIccRecords.unregisterForRecordsOverride(this);
                        mIccRecords = null;
                    }
                    if (newIccRecords != null) {
                        logd("new Icc object");
                        newIccRecords.registerForRecordsLoaded(this, SIM_LOAD_EVENT, null);
                        newIccRecords.registerForRecordsOverride(this, SIM_LOAD_EVENT, null);
                        mIccRecords = newIccRecords;
                    }
                }
                // check UICC profile
                final UiccProfile uiccProfile = UiccController.getInstance()
                        .getUiccProfileForPhone(mPhone.getPhoneId());
                if (mUiccProfile != uiccProfile) {
                    if (mUiccProfile != null) {
                        logd("unregister operatorBrandOverride");
                        mUiccProfile.unregisterForOperatorBrandOverride(this);
                        mUiccProfile = null;
                    }
                    if (uiccProfile != null) {
                        logd("register operatorBrandOverride");
                        uiccProfile.registerForOpertorBrandOverride(this, SPN_OVERRIDE_EVENT, null);
                        mUiccProfile = uiccProfile;
                    }
                }
                break;
            default:
                loge("invalid msg: " + msg.what);
                break;
        }
    }

    private void loadCarrierMatchingRulesOnMccMnc() {
        try {
            String mccmnc = mTelephonyMgr.getSimOperatorNumericForPhone(mPhone.getPhoneId());
            Cursor cursor = mContext.getContentResolver().query(
                    CarrierId.All.CONTENT_URI,
                    /* projection */ null,
                    /* selection */ CarrierId.All.MCCMNC + "=?",
                    /* selectionArgs */ new String[]{mccmnc}, null);
            try {
                if (cursor != null) {
                    if (VDBG) {
                        logd("[loadCarrierMatchingRules]- " + cursor.getCount()
                                + " Records(s) in DB" + " mccmnc: " + mccmnc);
                    }
                    mCarrierMatchingRulesOnMccMnc.clear();
                    while (cursor.moveToNext()) {
                        mCarrierMatchingRulesOnMccMnc.add(makeCarrierMatchingRule(cursor));
                    }
                    matchCarrier();
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        } catch (Exception ex) {
            loge("[loadCarrierMatchingRules]- ex: " + ex);
        }
    }

    private String getPreferApn() {
        Cursor cursor = mContext.getContentResolver().query(
                Uri.withAppendedPath(Telephony.Carriers.CONTENT_URI, "preferapn/subId/"
                + mPhone.getSubId()), /* projection */ new String[]{Telephony.Carriers.APN},
                /* selection */ null, /* selectionArgs */ null, /* sortOrder */ null);
        try {
            if (cursor != null) {
                if (VDBG) {
                    logd("[getPreferApn]- " + cursor.getCount() + " Records(s) in DB");
                }
                while (cursor.moveToNext()) {
                    String apn = cursor.getString(cursor.getColumnIndexOrThrow(
                            Telephony.Carriers.APN));
                    logd("[getPreferApn]- " + apn);
                    return apn;
                }
            }
        } catch (Exception ex) {
            loge("[getPreferApn]- exception: " + ex);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private void updateCarrierIdAndName(int cid, String name) {
        boolean update = false;
        if (!equals(name, mCarrierName, true)) {
            logd("[updateCarrierName] from:" + mCarrierName + " to:" + name);
            mCarrierName = name;
            update = true;
        }
        if (cid != mCarrierId) {
            logd("[updateCarrierId] from:" + mCarrierId + " to:" + cid);
            mCarrierId = cid;
            update = true;
        }
        if (update) {
            mCarrierIdLocalLog.log("[updateCarrierIdAndName] cid:" + mCarrierId + " name:"
                    + mCarrierName);
            final Intent intent = new Intent(TelephonyManager
                    .ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED);
            intent.putExtra(TelephonyManager.EXTRA_CARRIER_ID, mCarrierId);
            intent.putExtra(TelephonyManager.EXTRA_CARRIER_NAME, mCarrierName);
            intent.putExtra(TelephonyManager.EXTRA_SUBSCRIPTION_ID, mPhone.getSubId());
            mContext.sendBroadcast(intent);

            // update current subscriptions
            ContentValues cv = new ContentValues();
            cv.put(CarrierId.CARRIER_ID, mCarrierId);
            cv.put(CarrierId.CARRIER_NAME, mCarrierName);
            mContext.getContentResolver().update(
                    Uri.withAppendedPath(CarrierId.CONTENT_URI,
                    Integer.toString(mPhone.getSubId())), cv, null, null);
        }
    }

    private CarrierMatchingRule makeCarrierMatchingRule(Cursor cursor) {
        return new CarrierMatchingRule(
                cursor.getString(cursor.getColumnIndexOrThrow(CarrierId.All.MCCMNC)),
                cursor.getString(cursor.getColumnIndexOrThrow(
                        CarrierId.All.IMSI_PREFIX_XPATTERN)),
                cursor.getString(cursor.getColumnIndexOrThrow(
                        CarrierId.All.ICCID_PREFIX)),
                cursor.getString(cursor.getColumnIndexOrThrow(CarrierId.All.GID1)),
                cursor.getString(cursor.getColumnIndexOrThrow(CarrierId.All.GID2)),
                cursor.getString(cursor.getColumnIndexOrThrow(CarrierId.All.PLMN)),
                cursor.getString(cursor.getColumnIndexOrThrow(CarrierId.All.SPN)),
                cursor.getString(cursor.getColumnIndexOrThrow(CarrierId.All.APN)),
                cursor.getInt(cursor.getColumnIndexOrThrow(CarrierId.CARRIER_ID)),
                cursor.getString(cursor.getColumnIndexOrThrow(CarrierId.CARRIER_NAME)));
    }

    /**
     * carrier matching attributes with corresponding cid
     */
    private static class CarrierMatchingRule {
        /**
         * These scores provide the hierarchical relationship between the attributes, intended to
         * resolve conflicts in a deterministic way. The scores are constructed such that a match
         * from a higher tier will beat any subsequent match which does not match at that tier,
         * so MCCMNC beats everything else. This avoids problems when two (or more) carriers rule
         * matches as the score helps to find the best match uniquely. e.g.,
         * rule 1 {mccmnc, imsi} rule 2 {mccmnc, imsi, gid1} and rule 3 {mccmnc, imsi, gid2} all
         * matches with subscription data. rule 2 wins with the highest matching score.
         */
        private static final int SCORE_MCCMNC          = 1 << 7;
        private static final int SCORE_IMSI_PREFIX     = 1 << 6;
        private static final int SCORE_ICCID_PREFIX    = 1 << 5;
        private static final int SCORE_GID1            = 1 << 4;
        private static final int SCORE_GID2            = 1 << 3;
        private static final int SCORE_PLMN            = 1 << 2;
        private static final int SCORE_SPN             = 1 << 1;
        private static final int SCORE_APN             = 1 << 0;

        private static final int SCORE_INVALID         = -1;

        // carrier matching attributes
        private String mMccMnc;
        private String mImsiPrefixPattern;
        private String mIccidPrefix;
        private String mGid1;
        private String mGid2;
        private String mPlmn;
        private String mSpn;
        private String mApn;

        // user-facing carrier name
        private String mName;
        // unique carrier id
        private int mCid;

        private int mScore = 0;

        CarrierMatchingRule(String mccmnc, String imsiPrefixPattern, String iccidPrefix,
                String gid1, String gid2, String plmn, String spn, String apn, int cid,
                String name) {
            mMccMnc = mccmnc;
            mImsiPrefixPattern = imsiPrefixPattern;
            mIccidPrefix = iccidPrefix;
            mGid1 = gid1;
            mGid2 = gid2;
            mPlmn = plmn;
            mSpn = spn;
            mApn = apn;
            mCid = cid;
            mName = name;
        }

        // Calculate matching score. Values which aren't set in the rule are considered "wild".
        // All values in the rule must match in order for the subscription to be considered part of
        // the carrier. Otherwise, a invalid score -1 will be assigned. A match from a higher tier
        // will beat any subsequent match which does not match at that tier. When there are multiple
        // matches at the same tier, the match with highest score will be used.
        public void match(CarrierMatchingRule subscriptionRule) {
            mScore = 0;
            if (mMccMnc != null) {
                if (!CarrierIdentifier.equals(subscriptionRule.mMccMnc, mMccMnc, false)) {
                    mScore = SCORE_INVALID;
                    return;
                }
                mScore += SCORE_MCCMNC;
            }
            if (mImsiPrefixPattern != null) {
                if (!imsiPrefixMatch(subscriptionRule.mImsiPrefixPattern, mImsiPrefixPattern)) {
                    mScore = SCORE_INVALID;
                    return;
                }
                mScore += SCORE_IMSI_PREFIX;
            }
            if (mIccidPrefix != null) {
                if (!iccidPrefixMatch(subscriptionRule.mIccidPrefix, mIccidPrefix)) {
                    mScore = SCORE_INVALID;
                    return;
                }
                mScore += SCORE_ICCID_PREFIX;
            }
            if (mGid1 != null) {
                // full string match. carrier matching should cover the corner case that gid1
                // with garbage tail due to SIM manufacture issues.
                if (!CarrierIdentifier.equals(subscriptionRule.mGid1, mGid1, true)) {
                    mScore = SCORE_INVALID;
                    return;
                }
                mScore += SCORE_GID1;
            }
            if (mGid2 != null) {
                // full string match. carrier matching should cover the corner case that gid2
                // with garbage tail due to SIM manufacture issues.
                if (!CarrierIdentifier.equals(subscriptionRule.mGid2, mGid2, true)) {
                    mScore = SCORE_INVALID;
                    return;
                }
                mScore += SCORE_GID2;
            }
            if (mPlmn != null) {
                if (!CarrierIdentifier.equals(subscriptionRule.mPlmn, mPlmn, true)) {
                    mScore = SCORE_INVALID;
                    return;
                }
                mScore += SCORE_PLMN;
            }
            if (mSpn != null) {
                if (!CarrierIdentifier.equals(subscriptionRule.mSpn, mSpn, true)) {
                    mScore = SCORE_INVALID;
                    return;
                }
                mScore += SCORE_SPN;
            }
            if (mApn != null) {
                if (!CarrierIdentifier.equals(subscriptionRule.mApn, mApn, true)) {
                    mScore = SCORE_INVALID;
                    return;
                }
                mScore += SCORE_APN;
            }
        }

        private boolean imsiPrefixMatch(String imsi, String prefixXPattern) {
            if (TextUtils.isEmpty(prefixXPattern)) return true;
            if (TextUtils.isEmpty(imsi)) return false;
            if (imsi.length() < prefixXPattern.length()) {
                return false;
            }
            for (int i = 0; i < prefixXPattern.length(); i++) {
                if ((prefixXPattern.charAt(i) != 'x') && (prefixXPattern.charAt(i) != 'X')
                        && (prefixXPattern.charAt(i) != imsi.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        private boolean iccidPrefixMatch(String iccid, String prefix) {
            if (iccid == null || prefix == null) {
                return false;
            }
            return iccid.startsWith(prefix);
        }

        public String toString() {
            return "[CarrierMatchingRule] -"
                    + " mccmnc: " + mMccMnc
                    + " gid1: " + mGid1
                    + " gid2: " + mGid2
                    + " plmn: " + mPlmn
                    + " imsi_prefix: " + mImsiPrefixPattern
                    + " iccid_prefix" + mIccidPrefix
                    + " spn: " + mSpn
                    + " apn: " + mApn
                    + " name: " + mName
                    + " cid: " + mCid
                    + " score: " + mScore;
        }
    }

    /**
     * find the best matching carrier from candidates with matched MCCMNC and notify
     * all interested parties on carrier id change.
     */
    private void matchCarrier() {
        if (!SubscriptionManager.isValidSubscriptionId(mPhone.getSubId())) {
            logd("[matchCarrier]" + "skip before sim records loaded");
            return;
        }
        final String mccmnc = mTelephonyMgr.getSimOperatorNumericForPhone(mPhone.getPhoneId());
        final String iccid = mPhone.getIccSerialNumber();
        final String gid1 = mPhone.getGroupIdLevel1();
        final String gid2 = mPhone.getGroupIdLevel2();
        final String imsi = mPhone.getSubscriberId();
        final String plmn = mPhone.getPlmn();
        final String spn = mSpn;
        final String apn = mPreferApn;

        if (VDBG) {
            logd("[matchCarrier]"
                    + " mnnmnc:" + mccmnc
                    + " gid1: " + gid1
                    + " gid2: " + gid2
                    + " imsi: " + Rlog.pii(LOG_TAG, imsi)
                    + " iccid: " + Rlog.pii(LOG_TAG, iccid)
                    + " plmn: " + plmn
                    + " spn: " + spn
                    + " apn: " + apn);
        }

        CarrierMatchingRule subscriptionRule = new CarrierMatchingRule(
                mccmnc, imsi, iccid, gid1, gid2, plmn,  spn, apn,
                TelephonyManager.UNKNOWN_CARRIER_ID, null);

        int maxScore = CarrierMatchingRule.SCORE_INVALID;
        CarrierMatchingRule maxRule = null;

        for (CarrierMatchingRule rule : mCarrierMatchingRulesOnMccMnc) {
            rule.match(subscriptionRule);
            if (rule.mScore > maxScore) {
                maxScore = rule.mScore;
                maxRule = rule;
            }
        }

        if (maxScore == CarrierMatchingRule.SCORE_INVALID) {
            logd("[matchCarrier - no match] cid: " + TelephonyManager.UNKNOWN_CARRIER_ID
                    + " name: " + null);
            updateCarrierIdAndName(TelephonyManager.UNKNOWN_CARRIER_ID, null);
        } else {
            logd("[matchCarrier] cid: " + maxRule.mCid + " name: " + maxRule.mName);
            updateCarrierIdAndName(maxRule.mCid, maxRule.mName);
        }

        /*
         * Write Carrier Identification Matching event, logging with the
         * carrierId, mccmnc, gid1 and carrier list version to differentiate below cases of metrics:
         * 1) unknown mccmnc - the Carrier Id provider contains no rule that matches the
         * read mccmnc.
         * 2) the Carrier Id provider contains some rule(s) that match the read mccmnc,
         * but the read gid1 is not matched within the highest-scored rule.
         * 3) successfully found a matched carrier id in the provider.
         * 4) use carrier list version to compare the unknown carrier ratio between each version.
         */
        String unknownGid1ToLog = ((maxScore & CarrierMatchingRule.SCORE_GID1) == 0
                && !TextUtils.isEmpty(subscriptionRule.mGid1)) ? subscriptionRule.mGid1 : null;
        String unknownMccmncToLog = ((maxScore == CarrierMatchingRule.SCORE_INVALID
                || (maxScore & CarrierMatchingRule.SCORE_GID1) == 0)
                && !TextUtils.isEmpty(subscriptionRule.mMccMnc)) ? subscriptionRule.mMccMnc : null;
        TelephonyMetrics.getInstance().writeCarrierIdMatchingEvent(
                mPhone.getPhoneId(), getCarrierListVersion(), mCarrierId,
                unknownMccmncToLog, unknownGid1ToLog);
    }

    public int getCarrierListVersion() {
        final Cursor cursor = mContext.getContentResolver().query(
                Uri.withAppendedPath(CarrierId.All.CONTENT_URI,
                "get_version"), null, null, null);
        cursor.moveToFirst();
        return cursor.getInt(0);
    }

    public int getCarrierId() {
        return mCarrierId;
    }

    public String getCarrierName() {
        return mCarrierName;
    }

    private static boolean equals(String a, String b, boolean ignoreCase) {
        if (a == null && b == null) return true;
        if (a != null && b != null) {
            return (ignoreCase) ? a.equalsIgnoreCase(b) : a.equals(b);
        }
        return false;
    }

    private static void logd(String str) {
        Rlog.d(LOG_TAG, str);
    }
    private static void loge(String str) {
        Rlog.e(LOG_TAG, str);
    }
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        ipw.println("mCarrierIdLocalLogs:");
        ipw.increaseIndent();
        mCarrierIdLocalLog.dump(fd, pw, args);
        ipw.decreaseIndent();

        ipw.println("mCarrierId: " + mCarrierId);
        ipw.println("mCarrierName: " + mCarrierName);
        ipw.println("version: " + getCarrierListVersion());

        ipw.println("mCarrierMatchingRules on mccmnc: "
                + mTelephonyMgr.getSimOperatorNumericForPhone(mPhone.getPhoneId()));
        ipw.increaseIndent();
        for (CarrierMatchingRule rule : mCarrierMatchingRulesOnMccMnc) {
            ipw.println(rule.toString());
        }
        ipw.decreaseIndent();

        ipw.println("mSpn: " + mSpn);
        ipw.println("mPreferApn: " + mPreferApn);
        ipw.flush();
    }
}
