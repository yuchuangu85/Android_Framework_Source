/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.tare;

import static android.text.format.DateUtils.HOUR_IN_MILLIS;

import static com.android.server.tare.TareUtils.appToString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageInfo;
import android.os.Environment;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseArrayMap;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Maintains the current TARE state and handles writing it to disk and reading it back from disk.
 */
public class Scribe {
    private static final String TAG = "TARE-" + Scribe.class.getSimpleName();
    private static final boolean DEBUG = InternalResourceService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    /** The maximum number of transactions to dump per ledger. */
    private static final int MAX_NUM_TRANSACTION_DUMP = 25;
    /**
     * The maximum amount of time we'll keep a transaction around for.
     * For now, only keep transactions we actually have a use for. We can increase it if we want
     * to use older transactions or provide older transactions to apps.
     */
    private static final long MAX_TRANSACTION_AGE_MS = 24 * HOUR_IN_MILLIS;

    private static final String XML_TAG_HIGH_LEVEL_STATE = "irs-state";
    private static final String XML_TAG_LEDGER = "ledger";
    private static final String XML_TAG_TARE = "tare";
    private static final String XML_TAG_TRANSACTION = "transaction";
    private static final String XML_TAG_USER = "user";
    private static final String XML_TAG_PERIOD_REPORT = "report";

    private static final String XML_ATTR_CTP = "ctp";
    private static final String XML_ATTR_DELTA = "delta";
    private static final String XML_ATTR_EVENT_ID = "eventId";
    private static final String XML_ATTR_TAG = "tag";
    private static final String XML_ATTR_START_TIME = "startTime";
    private static final String XML_ATTR_END_TIME = "endTime";
    private static final String XML_ATTR_PACKAGE_NAME = "pkgName";
    private static final String XML_ATTR_CURRENT_BALANCE = "currentBalance";
    private static final String XML_ATTR_USER_ID = "userId";
    private static final String XML_ATTR_VERSION = "version";
    private static final String XML_ATTR_LAST_RECLAMATION_TIME = "lastReclamationTime";
    private static final String XML_ATTR_REMAINING_CONSUMABLE_CAKES = "remainingConsumableCakes";
    private static final String XML_ATTR_CONSUMPTION_LIMIT = "consumptionLimit";
    private static final String XML_ATTR_PR_DISCHARGE = "discharge";
    private static final String XML_ATTR_PR_BATTERY_LEVEL = "batteryLevel";
    private static final String XML_ATTR_PR_PROFIT = "profit";
    private static final String XML_ATTR_PR_NUM_PROFIT = "numProfits";
    private static final String XML_ATTR_PR_LOSS = "loss";
    private static final String XML_ATTR_PR_NUM_LOSS = "numLoss";
    private static final String XML_ATTR_PR_REWARDS = "rewards";
    private static final String XML_ATTR_PR_NUM_REWARDS = "numRewards";
    private static final String XML_ATTR_PR_POS_REGULATIONS = "posRegulations";
    private static final String XML_ATTR_PR_NUM_POS_REGULATIONS = "numPosRegulations";
    private static final String XML_ATTR_PR_NEG_REGULATIONS = "negRegulations";
    private static final String XML_ATTR_PR_NUM_NEG_REGULATIONS = "numNegRegulations";

    /** Version of the file schema. */
    private static final int STATE_FILE_VERSION = 0;
    /** Minimum amount of time between consecutive writes. */
    private static final long WRITE_DELAY = 30_000L;

    private final AtomicFile mStateFile;
    private final InternalResourceService mIrs;
    private final Analyst mAnalyst;

    @GuardedBy("mIrs.getLock()")
    private long mLastReclamationTime;
    @GuardedBy("mIrs.getLock()")
    private long mSatiatedConsumptionLimit;
    @GuardedBy("mIrs.getLock()")
    private long mRemainingConsumableCakes;
    @GuardedBy("mIrs.getLock()")
    private final SparseArrayMap<String, Ledger> mLedgers = new SparseArrayMap<>();

    private final Runnable mCleanRunnable = this::cleanupLedgers;
    private final Runnable mWriteRunnable = this::writeState;

    Scribe(InternalResourceService irs, Analyst analyst) {
        this(irs, analyst, Environment.getDataSystemDirectory());
    }

    @VisibleForTesting
    Scribe(InternalResourceService irs, Analyst analyst, File dataDir) {
        mIrs = irs;
        mAnalyst = analyst;

        final File tareDir = new File(dataDir, "tare");
        //noinspection ResultOfMethodCallIgnored
        tareDir.mkdirs();
        mStateFile = new AtomicFile(new File(tareDir, "state.xml"), "tare");
    }

    @GuardedBy("mIrs.getLock()")
    void adjustRemainingConsumableCakesLocked(long delta) {
        if (delta != 0) {
            // No point doing any work if the change is 0.
            mRemainingConsumableCakes += delta;
            postWrite();
        }
    }

    @GuardedBy("mIrs.getLock()")
    void discardLedgerLocked(final int userId, @NonNull final String pkgName) {
        mLedgers.delete(userId, pkgName);
        postWrite();
    }

    @GuardedBy("mIrs.getLock()")
    long getSatiatedConsumptionLimitLocked() {
        return mSatiatedConsumptionLimit;
    }

    @GuardedBy("mIrs.getLock()")
    long getLastReclamationTimeLocked() {
        return mLastReclamationTime;
    }

    @GuardedBy("mIrs.getLock()")
    @NonNull
    Ledger getLedgerLocked(final int userId, @NonNull final String pkgName) {
        Ledger ledger = mLedgers.get(userId, pkgName);
        if (ledger == null) {
            ledger = new Ledger();
            mLedgers.add(userId, pkgName, ledger);
        }
        return ledger;
    }

    @GuardedBy("mIrs.getLock()")
    @NonNull
    SparseArrayMap<String, Ledger> getLedgersLocked() {
        return mLedgers;
    }

    /**
     * Returns the sum of credits granted to all apps on the system. This is expensive so don't
     * call it for normal operation.
     */
    @GuardedBy("mIrs.getLock()")
    long getCakesInCirculationForLoggingLocked() {
        long sum = 0;
        for (int uIdx = mLedgers.numMaps() - 1; uIdx >= 0; --uIdx) {
            for (int pIdx = mLedgers.numElementsForKeyAt(uIdx) - 1; pIdx >= 0; --pIdx) {
                sum += mLedgers.valueAt(uIdx, pIdx).getCurrentBalance();
            }
        }
        return sum;
    }

    /** Returns the total amount of cakes that remain to be consumed. */
    @GuardedBy("mIrs.getLock()")
    long getRemainingConsumableCakesLocked() {
        return mRemainingConsumableCakes;
    }

    @GuardedBy("mIrs.getLock()")
    void loadFromDiskLocked() {
        mLedgers.clear();
        if (!recordExists()) {
            mSatiatedConsumptionLimit = mIrs.getInitialSatiatedConsumptionLimitLocked();
            mRemainingConsumableCakes = mIrs.getConsumptionLimitLocked();
            return;
        }
        mSatiatedConsumptionLimit = 0;
        mRemainingConsumableCakes = 0;

        final SparseArray<ArraySet<String>> installedPackagesPerUser = new SparseArray<>();
        final List<PackageInfo> installedPackages = mIrs.getInstalledPackages();
        for (int i = 0; i < installedPackages.size(); ++i) {
            final PackageInfo packageInfo = installedPackages.get(i);
            if (packageInfo.applicationInfo != null) {
                final int userId = UserHandle.getUserId(packageInfo.applicationInfo.uid);
                ArraySet<String> pkgsForUser = installedPackagesPerUser.get(userId);
                if (pkgsForUser == null) {
                    pkgsForUser = new ArraySet<>();
                    installedPackagesPerUser.put(userId, pkgsForUser);
                }
                pkgsForUser.add(packageInfo.packageName);
            }
        }

        final List<Analyst.Report> reports = new ArrayList<>();
        try (FileInputStream fis = mStateFile.openRead()) {
            TypedXmlPullParser parser = Xml.resolvePullParser(fis);

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG
                    && eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next();
            }
            if (eventType == XmlPullParser.END_DOCUMENT) {
                if (DEBUG) {
                    Slog.w(TAG, "No persisted state.");
                }
                return;
            }

            String tagName = parser.getName();
            if (XML_TAG_TARE.equals(tagName)) {
                final int version = parser.getAttributeInt(null, XML_ATTR_VERSION);
                if (version < 0 || version > STATE_FILE_VERSION) {
                    Slog.e(TAG, "Invalid version number (" + version + "), aborting file read");
                    return;
                }
            }

            final long endTimeCutoff = System.currentTimeMillis() - MAX_TRANSACTION_AGE_MS;
            long earliestEndTime = Long.MAX_VALUE;
            for (eventType = parser.next(); eventType != XmlPullParser.END_DOCUMENT;
                    eventType = parser.next()) {
                if (eventType != XmlPullParser.START_TAG) {
                    continue;
                }
                tagName = parser.getName();
                if (tagName == null) {
                    continue;
                }

                switch (tagName) {
                    case XML_TAG_HIGH_LEVEL_STATE:
                        mLastReclamationTime =
                                parser.getAttributeLong(null, XML_ATTR_LAST_RECLAMATION_TIME);
                        mSatiatedConsumptionLimit =
                                parser.getAttributeLong(null, XML_ATTR_CONSUMPTION_LIMIT,
                                        mIrs.getInitialSatiatedConsumptionLimitLocked());
                        final long consumptionLimit = mIrs.getConsumptionLimitLocked();
                        mRemainingConsumableCakes = Math.min(consumptionLimit,
                                parser.getAttributeLong(null, XML_ATTR_REMAINING_CONSUMABLE_CAKES,
                                        consumptionLimit));
                        break;
                    case XML_TAG_USER:
                        earliestEndTime = Math.min(earliestEndTime,
                                readUserFromXmlLocked(
                                        parser, installedPackagesPerUser, endTimeCutoff));
                        break;
                    case XML_TAG_PERIOD_REPORT:
                        reports.add(readReportFromXml(parser));
                        break;
                    default:
                        Slog.e(TAG, "Unexpected tag: " + tagName);
                        break;
                }
            }
            mAnalyst.loadReports(reports);
            scheduleCleanup(earliestEndTime);
        } catch (IOException | XmlPullParserException e) {
            Slog.wtf(TAG, "Error reading state from disk", e);
        }
    }

    @VisibleForTesting
    void postWrite() {
        TareHandlerThread.getHandler().postDelayed(mWriteRunnable, WRITE_DELAY);
    }

    boolean recordExists() {
        return mStateFile.exists();
    }

    @GuardedBy("mIrs.getLock()")
    void setConsumptionLimitLocked(long limit) {
        if (mRemainingConsumableCakes > limit) {
            mRemainingConsumableCakes = limit;
        } else if (limit > mSatiatedConsumptionLimit) {
            final long diff = mSatiatedConsumptionLimit - mRemainingConsumableCakes;
            mRemainingConsumableCakes = (limit - diff);
        }
        mSatiatedConsumptionLimit = limit;
        postWrite();
    }

    @GuardedBy("mIrs.getLock()")
    void setLastReclamationTimeLocked(long time) {
        mLastReclamationTime = time;
        postWrite();
    }

    @GuardedBy("mIrs.getLock()")
    void tearDownLocked() {
        TareHandlerThread.getHandler().removeCallbacks(mCleanRunnable);
        TareHandlerThread.getHandler().removeCallbacks(mWriteRunnable);
        mLedgers.clear();
        mRemainingConsumableCakes = 0;
        mSatiatedConsumptionLimit = 0;
        mLastReclamationTime = 0;
    }

    @VisibleForTesting
    void writeImmediatelyForTesting() {
        mWriteRunnable.run();
    }

    private void cleanupLedgers() {
        synchronized (mIrs.getLock()) {
            TareHandlerThread.getHandler().removeCallbacks(mCleanRunnable);
            long earliestEndTime = Long.MAX_VALUE;
            for (int uIdx = mLedgers.numMaps() - 1; uIdx >= 0; --uIdx) {
                final int userId = mLedgers.keyAt(uIdx);

                for (int pIdx = mLedgers.numElementsForKey(userId) - 1; pIdx >= 0; --pIdx) {
                    final String pkgName = mLedgers.keyAt(uIdx, pIdx);
                    final Ledger ledger = mLedgers.get(userId, pkgName);
                    ledger.removeOldTransactions(MAX_TRANSACTION_AGE_MS);
                    Ledger.Transaction transaction = ledger.getEarliestTransaction();
                    if (transaction != null) {
                        earliestEndTime = Math.min(earliestEndTime, transaction.endTimeMs);
                    }
                }
            }
            scheduleCleanup(earliestEndTime);
        }
    }

    /**
     * @param parser Xml parser at the beginning of a "<ledger/>" tag. The next "parser.next()" call
     *               will take the parser into the body of the ledger tag.
     * @return Newly instantiated ledger holding all the information we just read out of the xml
     * tag, and the package name associated with the ledger.
     */
    @Nullable
    private static Pair<String, Ledger> readLedgerFromXml(TypedXmlPullParser parser,
            ArraySet<String> validPackages, long endTimeCutoff)
            throws XmlPullParserException, IOException {
        final String pkgName;
        final long curBalance;
        final List<Ledger.Transaction> transactions = new ArrayList<>();

        pkgName = parser.getAttributeValue(null, XML_ATTR_PACKAGE_NAME);
        curBalance = parser.getAttributeLong(null, XML_ATTR_CURRENT_BALANCE);

        final boolean isInstalled = validPackages.contains(pkgName);
        if (!isInstalled) {
            // Don't return early since we need to go through all the transaction tags and get
            // to the end of the ledger tag.
            Slog.w(TAG, "Invalid pkg " + pkgName + " is saved to disk");
        }

        for (int eventType = parser.next(); eventType != XmlPullParser.END_DOCUMENT;
                eventType = parser.next()) {
            final String tagName = parser.getName();
            if (eventType == XmlPullParser.END_TAG) {
                if (XML_TAG_LEDGER.equals(tagName)) {
                    // We've reached the end of the ledger tag.
                    break;
                }
                continue;
            }
            if (eventType != XmlPullParser.START_TAG || !XML_TAG_TRANSACTION.equals(tagName)) {
                // Expecting only "transaction" tags.
                Slog.e(TAG, "Unexpected event: (" + eventType + ") " + tagName);
                return null;
            }
            if (!isInstalled) {
                continue;
            }
            if (DEBUG) {
                Slog.d(TAG, "Starting ledger tag: " + tagName);
            }
            final String tag = parser.getAttributeValue(null, XML_ATTR_TAG);
            final long startTime = parser.getAttributeLong(null, XML_ATTR_START_TIME);
            final long endTime = parser.getAttributeLong(null, XML_ATTR_END_TIME);
            final int eventId = parser.getAttributeInt(null, XML_ATTR_EVENT_ID);
            final long delta = parser.getAttributeLong(null, XML_ATTR_DELTA);
            final long ctp = parser.getAttributeLong(null, XML_ATTR_CTP);
            if (endTime <= endTimeCutoff) {
                if (DEBUG) {
                    Slog.d(TAG, "Skipping event because it's too old.");
                }
                continue;
            }
            transactions.add(new Ledger.Transaction(startTime, endTime, eventId, tag, delta, ctp));
        }

        if (!isInstalled) {
            return null;
        }
        return Pair.create(pkgName, new Ledger(curBalance, transactions));
    }

    /**
     * @param parser Xml parser at the beginning of a "<user>" tag. The next "parser.next()" call
     *               will take the parser into the body of the user tag.
     * @return The earliest valid transaction end time found for the user.
     */
    @GuardedBy("mIrs.getLock()")
    private long readUserFromXmlLocked(TypedXmlPullParser parser,
            SparseArray<ArraySet<String>> installedPackagesPerUser,
            long endTimeCutoff) throws XmlPullParserException, IOException {
        int curUser = parser.getAttributeInt(null, XML_ATTR_USER_ID);
        final ArraySet<String> installedPackages = installedPackagesPerUser.get(curUser);
        if (installedPackages == null) {
            Slog.w(TAG, "Invalid user " + curUser + " is saved to disk");
            curUser = UserHandle.USER_NULL;
            // Don't return early since we need to go through all the ledger tags and get to the end
            // of the user tag.
        }
        long earliestEndTime = Long.MAX_VALUE;

        for (int eventType = parser.next(); eventType != XmlPullParser.END_DOCUMENT;
                eventType = parser.next()) {
            final String tagName = parser.getName();
            if (eventType == XmlPullParser.END_TAG) {
                if (XML_TAG_USER.equals(tagName)) {
                    // We've reached the end of the user tag.
                    break;
                }
                continue;
            }
            if (XML_TAG_LEDGER.equals(tagName)) {
                if (curUser == UserHandle.USER_NULL) {
                    continue;
                }
                final Pair<String, Ledger> ledgerData =
                        readLedgerFromXml(parser, installedPackages, endTimeCutoff);
                if (ledgerData == null) {
                    continue;
                }
                final Ledger ledger = ledgerData.second;
                if (ledger != null) {
                    mLedgers.add(curUser, ledgerData.first, ledger);
                    final Ledger.Transaction transaction = ledger.getEarliestTransaction();
                    if (transaction != null) {
                        earliestEndTime = Math.min(earliestEndTime, transaction.endTimeMs);
                    }
                }
            } else {
                Slog.e(TAG, "Unknown tag: " + tagName);
            }
        }

        return earliestEndTime;
    }


    /**
     * @param parser Xml parser at the beginning of a {@link #XML_TAG_PERIOD_REPORT} tag. The next
     *               "parser.next()" call will take the parser into the body of the report tag.
     * @return Newly instantiated Report holding all the information we just read out of the xml tag
     */
    @NonNull
    private static Analyst.Report readReportFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        final Analyst.Report report = new Analyst.Report();

        report.cumulativeBatteryDischarge = parser.getAttributeInt(null, XML_ATTR_PR_DISCHARGE);
        report.currentBatteryLevel = parser.getAttributeInt(null, XML_ATTR_PR_BATTERY_LEVEL);
        report.cumulativeProfit = parser.getAttributeLong(null, XML_ATTR_PR_PROFIT);
        report.numProfitableActions = parser.getAttributeInt(null, XML_ATTR_PR_NUM_PROFIT);
        report.cumulativeLoss = parser.getAttributeLong(null, XML_ATTR_PR_LOSS);
        report.numUnprofitableActions = parser.getAttributeInt(null, XML_ATTR_PR_NUM_LOSS);
        report.cumulativeRewards = parser.getAttributeLong(null, XML_ATTR_PR_REWARDS);
        report.numRewards = parser.getAttributeInt(null, XML_ATTR_PR_NUM_REWARDS);
        report.cumulativePositiveRegulations =
                parser.getAttributeLong(null, XML_ATTR_PR_POS_REGULATIONS);
        report.numPositiveRegulations =
                parser.getAttributeInt(null, XML_ATTR_PR_NUM_POS_REGULATIONS);
        report.cumulativeNegativeRegulations =
                parser.getAttributeLong(null, XML_ATTR_PR_NEG_REGULATIONS);
        report.numNegativeRegulations =
                parser.getAttributeInt(null, XML_ATTR_PR_NUM_NEG_REGULATIONS);

        return report;
    }

    private void scheduleCleanup(long earliestEndTime) {
        if (earliestEndTime == Long.MAX_VALUE) {
            return;
        }
        // This is just cleanup to manage memory. We don't need to do it too often or at the exact
        // intended real time, so the delay that comes from using the Handler (and is limited
        // to uptime) should be fine.
        final long delayMs = Math.max(HOUR_IN_MILLIS,
                earliestEndTime + MAX_TRANSACTION_AGE_MS - System.currentTimeMillis());
        TareHandlerThread.getHandler().postDelayed(mCleanRunnable, delayMs);
    }

    private void writeState() {
        synchronized (mIrs.getLock()) {
            TareHandlerThread.getHandler().removeCallbacks(mWriteRunnable);
            // Remove mCleanRunnable callbacks since we're going to clean up the ledgers before
            // writing anyway.
            TareHandlerThread.getHandler().removeCallbacks(mCleanRunnable);
            if (!mIrs.isEnabled()) {
                // If it's no longer enabled, we would have cleared all the data in memory and would
                // accidentally write an empty file, thus deleting all the history.
                return;
            }
            long earliestStoredEndTime = Long.MAX_VALUE;
            try (FileOutputStream fos = mStateFile.startWrite()) {
                TypedXmlSerializer out = Xml.resolveSerializer(fos);
                out.startDocument(null, true);

                out.startTag(null, XML_TAG_TARE);
                out.attributeInt(null, XML_ATTR_VERSION, STATE_FILE_VERSION);

                out.startTag(null, XML_TAG_HIGH_LEVEL_STATE);
                out.attributeLong(null, XML_ATTR_LAST_RECLAMATION_TIME, mLastReclamationTime);
                out.attributeLong(null, XML_ATTR_CONSUMPTION_LIMIT, mSatiatedConsumptionLimit);
                out.attributeLong(null, XML_ATTR_REMAINING_CONSUMABLE_CAKES,
                        mRemainingConsumableCakes);
                out.endTag(null, XML_TAG_HIGH_LEVEL_STATE);

                for (int uIdx = mLedgers.numMaps() - 1; uIdx >= 0; --uIdx) {
                    final int userId = mLedgers.keyAt(uIdx);
                    earliestStoredEndTime = Math.min(earliestStoredEndTime,
                            writeUserLocked(out, userId));
                }

                List<Analyst.Report> reports = mAnalyst.getReports();
                for (int i = 0, size = reports.size(); i < size; ++i) {
                    writeReport(out, reports.get(i));
                }

                out.endTag(null, XML_TAG_TARE);

                out.endDocument();
                mStateFile.finishWrite(fos);
            } catch (IOException e) {
                Slog.e(TAG, "Error writing state to disk", e);
            }
            scheduleCleanup(earliestStoredEndTime);
        }
    }

    @GuardedBy("mIrs.getLock()")
    private long writeUserLocked(@NonNull TypedXmlSerializer out, final int userId)
            throws IOException {
        final int uIdx = mLedgers.indexOfKey(userId);
        long earliestStoredEndTime = Long.MAX_VALUE;

        out.startTag(null, XML_TAG_USER);
        out.attributeInt(null, XML_ATTR_USER_ID, userId);
        for (int pIdx = mLedgers.numElementsForKey(userId) - 1; pIdx >= 0; --pIdx) {
            final String pkgName = mLedgers.keyAt(uIdx, pIdx);
            final Ledger ledger = mLedgers.get(userId, pkgName);
            // Remove old transactions so we don't waste space storing them.
            ledger.removeOldTransactions(MAX_TRANSACTION_AGE_MS);

            out.startTag(null, XML_TAG_LEDGER);
            out.attribute(null, XML_ATTR_PACKAGE_NAME, pkgName);
            out.attributeLong(null,
                    XML_ATTR_CURRENT_BALANCE, ledger.getCurrentBalance());

            final List<Ledger.Transaction> transactions = ledger.getTransactions();
            for (int t = 0; t < transactions.size(); ++t) {
                Ledger.Transaction transaction = transactions.get(t);
                if (t == 0) {
                    earliestStoredEndTime = Math.min(earliestStoredEndTime, transaction.endTimeMs);
                }
                writeTransaction(out, transaction);
            }
            out.endTag(null, XML_TAG_LEDGER);
        }
        out.endTag(null, XML_TAG_USER);

        return earliestStoredEndTime;
    }

    private static void writeTransaction(@NonNull TypedXmlSerializer out,
            @NonNull Ledger.Transaction transaction) throws IOException {
        out.startTag(null, XML_TAG_TRANSACTION);
        out.attributeLong(null, XML_ATTR_START_TIME, transaction.startTimeMs);
        out.attributeLong(null, XML_ATTR_END_TIME, transaction.endTimeMs);
        out.attributeInt(null, XML_ATTR_EVENT_ID, transaction.eventId);
        if (transaction.tag != null) {
            out.attribute(null, XML_ATTR_TAG, transaction.tag);
        }
        out.attributeLong(null, XML_ATTR_DELTA, transaction.delta);
        out.attributeLong(null, XML_ATTR_CTP, transaction.ctp);
        out.endTag(null, XML_TAG_TRANSACTION);
    }

    private static void writeReport(@NonNull TypedXmlSerializer out,
            @NonNull Analyst.Report report) throws IOException {
        out.startTag(null, XML_TAG_PERIOD_REPORT);
        out.attributeInt(null, XML_ATTR_PR_DISCHARGE, report.cumulativeBatteryDischarge);
        out.attributeInt(null, XML_ATTR_PR_BATTERY_LEVEL, report.currentBatteryLevel);
        out.attributeLong(null, XML_ATTR_PR_PROFIT, report.cumulativeProfit);
        out.attributeInt(null, XML_ATTR_PR_NUM_PROFIT, report.numProfitableActions);
        out.attributeLong(null, XML_ATTR_PR_LOSS, report.cumulativeLoss);
        out.attributeInt(null, XML_ATTR_PR_NUM_LOSS, report.numUnprofitableActions);
        out.attributeLong(null, XML_ATTR_PR_REWARDS, report.cumulativeRewards);
        out.attributeInt(null, XML_ATTR_PR_NUM_REWARDS, report.numRewards);
        out.attributeLong(null, XML_ATTR_PR_POS_REGULATIONS, report.cumulativePositiveRegulations);
        out.attributeInt(null, XML_ATTR_PR_NUM_POS_REGULATIONS, report.numPositiveRegulations);
        out.attributeLong(null, XML_ATTR_PR_NEG_REGULATIONS, report.cumulativeNegativeRegulations);
        out.attributeInt(null, XML_ATTR_PR_NUM_NEG_REGULATIONS, report.numNegativeRegulations);
        out.endTag(null, XML_TAG_PERIOD_REPORT);
    }

    @GuardedBy("mIrs.getLock()")
    void dumpLocked(IndentingPrintWriter pw, boolean dumpAll) {
        pw.println("Ledgers:");
        pw.increaseIndent();
        mLedgers.forEach((userId, pkgName, ledger) -> {
            pw.print(appToString(userId, pkgName));
            if (mIrs.isSystem(userId, pkgName)) {
                pw.print(" (system)");
            }
            pw.println();
            pw.increaseIndent();
            ledger.dump(pw, dumpAll ? Integer.MAX_VALUE : MAX_NUM_TRANSACTION_DUMP);
            pw.decreaseIndent();
        });
        pw.decreaseIndent();
    }
}
