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

package com.android.internal.telephony.metrics;

import android.annotation.Nullable;
import android.content.Context;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.nano.PersistAtomsProto.PersistAtoms;
import com.android.internal.telephony.nano.PersistAtomsProto.RawVoiceCallRatUsage;
import com.android.internal.telephony.nano.PersistAtomsProto.VoiceCallSession;
import com.android.telephony.Rlog;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Stores and aggregates metrics that should not be pulled at arbitrary frequency.
 *
 * <p>NOTE: while this class checks timestamp against {@code minIntervalMillis}, it is {@link
 * MetricsCollector}'s responsibility to ensure {@code minIntervalMillis} is set correctly.
 */
public class PersistAtomsStorage {
    private static final String TAG = PersistAtomsStorage.class.getSimpleName();

    /** Name of the file where cached statistics are saved to. */
    private static final String FILENAME = "persist_atoms.pb";

    /** Maximum number of call sessions to store during pulls. */
    private static final int MAX_NUM_CALL_SESSIONS = 50;

    /** Stores persist atoms and persist states of the puller. */
    @VisibleForTesting protected final PersistAtoms mAtoms;

    /** Aggregates RAT duration and call count. */
    private final VoiceCallRatTracker mVoiceCallRatTracker;

    private final Context mContext;
    private static final SecureRandom sRandom = new SecureRandom();

    public PersistAtomsStorage(Context context) {
        mContext = context;
        mAtoms = loadAtomsFromFile();
        mVoiceCallRatTracker = VoiceCallRatTracker.fromProto(mAtoms.rawVoiceCallRatUsage);
    }

    /** Adds a call to the storage. */
    public synchronized void addVoiceCallSession(VoiceCallSession call) {
        int newLength = mAtoms.voiceCallSession.length + 1;
        if (newLength > MAX_NUM_CALL_SESSIONS) {
            // will evict one previous call randomly instead of making the array larger
            newLength = MAX_NUM_CALL_SESSIONS;
        } else {
            mAtoms.voiceCallSession = Arrays.copyOf(mAtoms.voiceCallSession, newLength);
        }
        int insertAt = 0;
        if (newLength > 1) {
            // shuffle when each call is added, or randomly replace a previous call instead if
            // MAX_NUM_CALL_SESSIONS is reached (call at the last index is evicted).
            insertAt = sRandom.nextInt(newLength);
            mAtoms.voiceCallSession[newLength - 1] = mAtoms.voiceCallSession[insertAt];
        }
        mAtoms.voiceCallSession[insertAt] = call;
        saveAtomsToFile();
    }

    /** Adds RAT usages to the storage when a call session ends. */
    public synchronized void addVoiceCallRatUsage(VoiceCallRatTracker ratUsages) {
        mVoiceCallRatTracker.mergeWith(ratUsages);
        mAtoms.rawVoiceCallRatUsage = mVoiceCallRatTracker.toProto();
        saveAtomsToFile();
    }

    /**
     * Returns and clears the voice call sessions if last pulled longer than {@code
     * minIntervalMillis} ago, otherwise returns {@code null}.
     */
    @Nullable
    public synchronized VoiceCallSession[] getVoiceCallSessions(long minIntervalMillis) {
        if (getWallTimeMillis() - mAtoms.voiceCallSessionPullTimestampMillis > minIntervalMillis) {
            mAtoms.voiceCallSessionPullTimestampMillis = getWallTimeMillis();
            VoiceCallSession[] previousCalls = mAtoms.voiceCallSession;
            mAtoms.voiceCallSession = new VoiceCallSession[0];
            saveAtomsToFile();
            return previousCalls;
        } else {
            return null;
        }
    }

    /**
     * Returns and clears the voice call RAT usages if last pulled longer than {@code
     * minIntervalMillis} ago, otherwise returns {@code null}.
     */
    @Nullable
    public synchronized RawVoiceCallRatUsage[] getVoiceCallRatUsages(long minIntervalMillis) {
        if (getWallTimeMillis() - mAtoms.rawVoiceCallRatUsagePullTimestampMillis
                > minIntervalMillis) {
            mAtoms.rawVoiceCallRatUsagePullTimestampMillis = getWallTimeMillis();
            RawVoiceCallRatUsage[] previousUsages = mAtoms.rawVoiceCallRatUsage;
            mVoiceCallRatTracker.clear();
            mAtoms.rawVoiceCallRatUsage = new RawVoiceCallRatUsage[0];
            saveAtomsToFile();
            return previousUsages;
        } else {
            return null;
        }
    }

    /** Loads {@link PersistAtoms} from a file in private storage. */
    private PersistAtoms loadAtomsFromFile() {
        try {
            PersistAtoms atomsFromFile =
                    PersistAtoms.parseFrom(
                            Files.readAllBytes(mContext.getFileStreamPath(FILENAME).toPath()));
            // check all the fields in case of situations such as OTA or crash during saving
            if (atomsFromFile.rawVoiceCallRatUsage == null) {
                atomsFromFile.rawVoiceCallRatUsage = new RawVoiceCallRatUsage[0];
            }
            if (atomsFromFile.voiceCallSession == null) {
                atomsFromFile.voiceCallSession = new VoiceCallSession[0];
            }
            if (atomsFromFile.voiceCallSession.length > MAX_NUM_CALL_SESSIONS) {
                atomsFromFile.voiceCallSession =
                        Arrays.copyOf(atomsFromFile.voiceCallSession, MAX_NUM_CALL_SESSIONS);
            }
            // out of caution, set timestamps to now if they are missing
            if (atomsFromFile.rawVoiceCallRatUsagePullTimestampMillis == 0L) {
                atomsFromFile.rawVoiceCallRatUsagePullTimestampMillis = getWallTimeMillis();
            }
            if (atomsFromFile.voiceCallSessionPullTimestampMillis == 0L) {
                atomsFromFile.voiceCallSessionPullTimestampMillis = getWallTimeMillis();
            }
            return atomsFromFile;
        } catch (IOException | NullPointerException e) {
            Rlog.e(TAG, "cannot load/parse PersistAtoms", e);
            return makeNewPersistAtoms();
        }
    }

    /** Saves a copy of {@link PersistAtoms} to a file in private storage. */
    private void saveAtomsToFile() {
        try (FileOutputStream stream = mContext.openFileOutput(FILENAME, Context.MODE_PRIVATE)) {
            stream.write(PersistAtoms.toByteArray(mAtoms));
        } catch (IOException e) {
            Rlog.e(TAG, "cannot save PersistAtoms", e);
        }
    }

    /** Returns an empty PersistAtoms with pull timestamp set to current time. */
    private PersistAtoms makeNewPersistAtoms() {
        PersistAtoms atoms = new PersistAtoms();
        // allow pulling only after some time so data are sufficiently aggregated
        atoms.rawVoiceCallRatUsagePullTimestampMillis = getWallTimeMillis();
        atoms.voiceCallSessionPullTimestampMillis = getWallTimeMillis();
        Rlog.d(TAG, "created new PersistAtoms");
        return atoms;
    }

    @VisibleForTesting
    protected long getWallTimeMillis() {
        // epoch time in UTC, preserved across reboots, but can be adjusted e.g. by the user or NTP
        return System.currentTimeMillis();
    }
}
