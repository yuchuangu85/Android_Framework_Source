/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.wifi;

import android.annotation.Nullable;
import android.os.SystemClock;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.proto.nano.WifiMetricsProto.WifiWakeStats;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds WifiWake metrics and converts them to a protobuf included in WifiLog.
 */
public class WifiWakeMetrics {

    /** Maximum number of sessions to store in WifiWakeStats proto. */
    @VisibleForTesting
    static final int MAX_RECORDED_SESSIONS = 10;

    @GuardedBy("mLock")
    private final List<Session> mSessions = new ArrayList<>();
    @GuardedBy("mLock")
    private Session mCurrentSession;

    private boolean mIsInSession = false;
    private int mTotalSessions = 0;
    private int mTotalWakeups = 0;
    private int mIgnoredStarts = 0;

    private final Object mLock = new Object();

    /**
     * Records the beginning of a Wifi Wake session.
     *
     * <p>Starts the session.
     *
     * @param numNetworks The total number of networks stored in the WakeupLock at start.
     */
    public void recordStartEvent(int numNetworks) {
        synchronized (mLock) {
            mCurrentSession = new Session(numNetworks, SystemClock.elapsedRealtime());
            mIsInSession = true;
        }
    }

    /**
     * Records the initialize event of the current Wifi Wake session.
     *
     * <p>Note: The start event must be recorded before this event, otherwise this call will be
     * ignored.
     *
     * @param numScans The total number of elapsed scans since start.
     * @param numNetworks The total number of networks in the lock.
     */
    public void recordInitializeEvent(int numScans, int numNetworks) {
        synchronized (mLock) {
            if (!mIsInSession) {
                return;
            }
            mCurrentSession.recordInitializeEvent(numScans, numNetworks,
                    SystemClock.elapsedRealtime());
        }
    }

    /**
     * Records the unlock event of the current Wifi Wake session.
     *
     * <p>The unlock event occurs when the WakeupLock has all of its networks removed. This event
     * will not be recorded if the initialize event recorded 0 locked networks.
     *
     * <p>Note: The start event must be recorded before this event, otherwise this call will be
     * ignored.
     *
     * @param numScans The total number of elapsed scans since start.
     */
    public void recordUnlockEvent(int numScans) {
        synchronized (mLock) {
            if (!mIsInSession) {
                return;
            }
            mCurrentSession.recordUnlockEvent(numScans, SystemClock.elapsedRealtime());
        }
    }

    /**
     * Records the wakeup event of the current Wifi Wake session.
     *
     * <p>The wakeup event occurs when Wifi is re-enabled by the WakeupController.
     *
     * <p>Note: The start event must be recorded before this event, otherwise this call will be
     * ignored.
     *
     * @param numScans The total number of elapsed scans since start.
     */
    public void recordWakeupEvent(int numScans) {
        synchronized (mLock) {
            if (!mIsInSession) {
                return;
            }
            mCurrentSession.recordWakeupEvent(numScans, SystemClock.elapsedRealtime());
        }
    }

    /**
     * Records the reset event of the current Wifi Wake session.
     *
     * <p>The reset event occurs when Wifi enters client mode. Stores the first
     * {@link #MAX_RECORDED_SESSIONS} in the session list.
     *
     * <p>Note: The start event must be recorded before this event, otherwise this call will be
     * ignored. This event ends the current session.
     *
     * @param numScans The total number of elapsed scans since start.
     */
    public void recordResetEvent(int numScans) {
        synchronized (mLock) {
            if (!mIsInSession) {
                return;
            }
            mCurrentSession.recordResetEvent(numScans, SystemClock.elapsedRealtime());

            // tally successful wakeups here since this is the actual point when wifi is turned on
            if (mCurrentSession.hasWakeupTriggered()) {
                mTotalWakeups++;
            }

            mTotalSessions++;
            if (mSessions.size() < MAX_RECORDED_SESSIONS) {
                mSessions.add(mCurrentSession);
            }
            mIsInSession = false;
        }
    }

    /**
     * Records instance of the start event being ignored due to the controller already being active.
     */
    public void recordIgnoredStart() {
        mIgnoredStarts++;
    }

    /**
     * Returns the consolidated WifiWakeStats proto for WifiMetrics.
     */
    public WifiWakeStats buildProto() {
        WifiWakeStats proto = new WifiWakeStats();

        proto.numSessions = mTotalSessions;
        proto.numWakeups = mTotalWakeups;
        proto.numIgnoredStarts = mIgnoredStarts;
        proto.sessions = new WifiWakeStats.Session[mSessions.size()];

        for (int i = 0; i < mSessions.size(); i++) {
            proto.sessions[i] = mSessions.get(i).buildProto();
        }

        return proto;
    }

    /**
     * Dump all WifiWake stats to console (pw)
     * @param pw
     */
    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("-------WifiWake metrics-------");
            pw.println("mTotalSessions: " + mTotalSessions);
            pw.println("mTotalWakeups: " + mTotalWakeups);
            pw.println("mIgnoredStarts: " + mIgnoredStarts);
            pw.println("mIsInSession: " + mIsInSession);
            pw.println("Stored Sessions: " + mSessions.size());
            for (Session session : mSessions) {
                session.dump(pw);
            }
            if (mCurrentSession != null) {
                pw.println("Current Session: ");
                mCurrentSession.dump(pw);
            }
            pw.println("----end of WifiWake metrics----");
        }
    }

    /**
     * Clears WifiWakeMetrics.
     *
     * <p>Keeps the current WifiWake session.
     */
    public void clear() {
        synchronized (mLock) {
            mSessions.clear();
            mTotalSessions = 0;
            mTotalWakeups = 0;
            mIgnoredStarts = 0;
        }
    }

    /** A single WifiWake session. */
    public static class Session {

        private final long mStartTimestamp;
        private final int mStartNetworks;
        private int mInitializeNetworks = 0;

        @VisibleForTesting
        @Nullable
        Event mUnlockEvent;
        @VisibleForTesting
        @Nullable
        Event mInitEvent;
        @VisibleForTesting
        @Nullable
        Event mWakeupEvent;
        @VisibleForTesting
        @Nullable
        Event mResetEvent;

        /** Creates a new WifiWake session. */
        public Session(int numNetworks, long timestamp) {
            mStartNetworks = numNetworks;
            mStartTimestamp = timestamp;
        }

        /**
         * Records an initialize event.
         *
         * <p>Ignores subsequent calls.
         *
         * @param numScans Total number of scans at the time of this event.
         * @param numNetworks Total number of networks in the lock.
         * @param timestamp The timestamp of the event.
         */
        public void recordInitializeEvent(int numScans, int numNetworks, long timestamp) {
            if (mInitEvent == null) {
                mInitializeNetworks = numNetworks;
                mInitEvent = new Event(numScans, timestamp - mStartTimestamp);
            }
        }

        /**
         * Records an unlock event.
         *
         * <p>Ignores subsequent calls.
         *
         * @param numScans Total number of scans at the time of this event.
         * @param timestamp The timestamp of the event.
         */
        public void recordUnlockEvent(int numScans, long timestamp) {
            if (mUnlockEvent == null) {
                mUnlockEvent = new Event(numScans, timestamp - mStartTimestamp);
            }
        }

        /**
         * Records a wakeup event.
         *
         * <p>Ignores subsequent calls.
         *
         * @param numScans Total number of scans at the time of this event.
         * @param timestamp The timestamp of the event.
         */
        public void recordWakeupEvent(int numScans, long timestamp) {
            if (mWakeupEvent == null) {
                mWakeupEvent = new Event(numScans, timestamp - mStartTimestamp);
            }
        }

        /**
         * Returns whether the current session has had its wakeup event triggered.
         */
        public boolean hasWakeupTriggered() {
            return mWakeupEvent != null;
        }

        /**
         * Records a reset event.
         *
         * <p>Ignores subsequent calls.
         *
         * @param numScans Total number of scans at the time of this event.
         * @param timestamp The timestamp of the event.
         */
        public void recordResetEvent(int numScans, long timestamp) {
            if (mResetEvent == null) {
                mResetEvent = new Event(numScans, timestamp - mStartTimestamp);
            }
        }

        /** Returns the proto representation of this session. */
        public WifiWakeStats.Session buildProto() {
            WifiWakeStats.Session sessionProto = new WifiWakeStats.Session();
            sessionProto.startTimeMillis = mStartTimestamp;
            sessionProto.lockedNetworksAtStart = mStartNetworks;

            if (mInitEvent != null) {
                sessionProto.lockedNetworksAtInitialize = mInitializeNetworks;
                sessionProto.initializeEvent = mInitEvent.buildProto();
            }
            if (mUnlockEvent != null) {
                sessionProto.unlockEvent = mUnlockEvent.buildProto();
            }
            if (mWakeupEvent != null) {
                sessionProto.wakeupEvent = mWakeupEvent.buildProto();
            }
            if (mResetEvent != null) {
                sessionProto.resetEvent = mResetEvent.buildProto();
            }

            return sessionProto;
        }

        /** Dumps the current state of the session. */
        public void dump(PrintWriter pw) {
            pw.println("WifiWakeMetrics.Session:");
            pw.println("mStartTimestamp: " + mStartTimestamp);
            pw.println("mStartNetworks: " + mStartNetworks);
            pw.println("mInitializeNetworks: " + mInitializeNetworks);
            pw.println("mInitEvent: " + (mInitEvent == null ? "{}" : mInitEvent.toString()));
            pw.println("mUnlockEvent: " + (mUnlockEvent == null ? "{}" : mUnlockEvent.toString()));
            pw.println("mWakeupEvent: " + (mWakeupEvent == null ? "{}" : mWakeupEvent.toString()));
            pw.println("mResetEvent: " + (mResetEvent == null ? "{}" : mResetEvent.toString()));
        }
    }

    /** An event in a WifiWake session. */
    public static class Event {

        /** Total number of scans that have elapsed prior to this event. */
        public final int mNumScans;
        /** Total elapsed time in milliseconds at the instant of this event. */
        public final long mElapsedTime;

        public Event(int numScans, long elapsedTime) {
            mNumScans = numScans;
            mElapsedTime = elapsedTime;
        }

        /** Returns the proto representation of this event. */
        public WifiWakeStats.Session.Event buildProto() {
            WifiWakeStats.Session.Event eventProto = new WifiWakeStats.Session.Event();
            eventProto.elapsedScans = mNumScans;
            eventProto.elapsedTimeMillis = mElapsedTime;
            return eventProto;
        }

        @Override
        public String toString() {
            return "{ mNumScans: " + mNumScans + ", elapsedTime: " + mElapsedTime + " }";
        }
    }
}
