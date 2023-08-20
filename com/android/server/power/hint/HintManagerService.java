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

package com.android.server.power.hint;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.StatsManager;
import android.app.UidObserver;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.IHintManager;
import android.os.IHintSession;
import android.os.PerformanceHintManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.StatsEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.utils.Slogf;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** An hint service implementation that runs in System Server process. */
public final class HintManagerService extends SystemService {
    private static final String TAG = "HintManagerService";
    private static final boolean DEBUG = false;
    @VisibleForTesting final long mHintSessionPreferredRate;

    // Multi-level map storing all active AppHintSessions.
    // First level is keyed by the UID of the client process creating the session.
    // Second level is keyed by an IBinder passed from client process. This is used to observe
    // when the process exits. The client generally uses the same IBinder object across multiple
    // sessions, so the value is a set of AppHintSessions.
    @GuardedBy("mLock")
    private final ArrayMap<Integer, ArrayMap<IBinder, ArraySet<AppHintSession>>> mActiveSessions;

    /** Lock to protect HAL handles and listen list. */
    private final Object mLock = new Object();

    @VisibleForTesting final MyUidObserver mUidObserver;

    private final NativeWrapper mNativeWrapper;

    private final ActivityManagerInternal mAmInternal;

    private final Context mContext;

    private static final String PROPERTY_SF_ENABLE_CPU_HINT = "debug.sf.enable_adpf_cpu_hint";
    private static final String PROPERTY_HWUI_ENABLE_HINT_MANAGER = "debug.hwui.use_hint_manager";

    @VisibleForTesting final IHintManager.Stub mService = new BinderService();

    public HintManagerService(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    HintManagerService(Context context, Injector injector) {
        super(context);
        mContext = context;
        mActiveSessions = new ArrayMap<>();
        mNativeWrapper = injector.createNativeWrapper();
        mNativeWrapper.halInit();
        mHintSessionPreferredRate = mNativeWrapper.halGetHintSessionPreferredRate();
        mUidObserver = new MyUidObserver();
        mAmInternal = Objects.requireNonNull(
                LocalServices.getService(ActivityManagerInternal.class));
    }

    @VisibleForTesting
    static class Injector {
        NativeWrapper createNativeWrapper() {
            return new NativeWrapper();
        }
    }

    private boolean isHalSupported() {
        return mHintSessionPreferredRate != -1;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.PERFORMANCE_HINT_SERVICE, mService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            systemReady();
        }
        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            registerStatsCallbacks();
        }
    }

    private void systemReady() {
        Slogf.v(TAG, "Initializing HintManager service...");
        try {
            ActivityManager.getService().registerUidObserver(mUidObserver,
                    ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE,
                    ActivityManager.PROCESS_STATE_UNKNOWN, null);
        } catch (RemoteException e) {
            // ignored; both services live in system_server
        }

    }

    private void registerStatsCallbacks() {
        final StatsManager statsManager = mContext.getSystemService(StatsManager.class);
        statsManager.setPullAtomCallback(
                FrameworkStatsLog.ADPF_SYSTEM_COMPONENT_INFO,
                null, // use default PullAtomMetadata values
                BackgroundThread.getExecutor(),
                this::onPullAtom);
    }

    private int onPullAtom(int atomTag, @NonNull List<StatsEvent> data) {
        if (atomTag == FrameworkStatsLog.ADPF_SYSTEM_COMPONENT_INFO) {
            final boolean isSurfaceFlingerUsingCpuHint =
                    SystemProperties.getBoolean(PROPERTY_SF_ENABLE_CPU_HINT, false);
            final boolean isHwuiHintManagerEnabled =
                    SystemProperties.getBoolean(PROPERTY_HWUI_ENABLE_HINT_MANAGER, false);

            data.add(FrameworkStatsLog.buildStatsEvent(
                    FrameworkStatsLog.ADPF_SYSTEM_COMPONENT_INFO,
                    isSurfaceFlingerUsingCpuHint,
                    isHwuiHintManagerEnabled));
        }
        return android.app.StatsManager.PULL_SUCCESS;
    }

    /**
     * Wrapper around the static-native methods from native.
     *
     * This class exists to allow us to mock static native methods in our tests. If mocking static
     * methods becomes easier than this in the future, we can delete this class.
     */
    @VisibleForTesting
    public static class NativeWrapper {
        private native void nativeInit();

        private static native long nativeCreateHintSession(int tgid, int uid, int[] tids,
                long durationNanos);

        private static native void nativePauseHintSession(long halPtr);

        private static native void nativeResumeHintSession(long halPtr);

        private static native void nativeCloseHintSession(long halPtr);

        private static native void nativeUpdateTargetWorkDuration(
                long halPtr, long targetDurationNanos);

        private static native void nativeReportActualWorkDuration(
                long halPtr, long[] actualDurationNanos, long[] timeStampNanos);

        private static native void nativeSendHint(long halPtr, int hint);

        private static native void nativeSetThreads(long halPtr, int[] tids);

        private static native long nativeGetHintSessionPreferredRate();

        /** Wrapper for HintManager.nativeInit */
        public void halInit() {
            nativeInit();
        }

        /** Wrapper for HintManager.nativeCreateHintSession */
        public long halCreateHintSession(int tgid, int uid, int[] tids, long durationNanos) {
            return nativeCreateHintSession(tgid, uid, tids, durationNanos);
        }

        /** Wrapper for HintManager.nativePauseHintSession */
        public void halPauseHintSession(long halPtr) {
            nativePauseHintSession(halPtr);
        }

        /** Wrapper for HintManager.nativeResumeHintSession */
        public void halResumeHintSession(long halPtr) {
            nativeResumeHintSession(halPtr);
        }

        /** Wrapper for HintManager.nativeCloseHintSession */
        public void halCloseHintSession(long halPtr) {
            nativeCloseHintSession(halPtr);
        }

        /** Wrapper for HintManager.nativeUpdateTargetWorkDuration */
        public void halUpdateTargetWorkDuration(long halPtr, long targetDurationNanos) {
            nativeUpdateTargetWorkDuration(halPtr, targetDurationNanos);
        }

        /** Wrapper for HintManager.nativeReportActualWorkDuration */
        public void halReportActualWorkDuration(
                long halPtr, long[] actualDurationNanos, long[] timeStampNanos) {
            nativeReportActualWorkDuration(halPtr, actualDurationNanos,
                    timeStampNanos);
        }

        /** Wrapper for HintManager.sendHint */
        public void halSendHint(long halPtr, int hint) {
            nativeSendHint(halPtr, hint);
        }

        /** Wrapper for HintManager.nativeGetHintSessionPreferredRate */
        public long halGetHintSessionPreferredRate() {
            return nativeGetHintSessionPreferredRate();
        }

        /** Wrapper for HintManager.nativeSetThreads */
        public void halSetThreads(long halPtr, int[] tids) {
            nativeSetThreads(halPtr, tids);
        }
    }

    @VisibleForTesting
    final class MyUidObserver extends UidObserver {
        private final SparseArray<Integer> mProcStatesCache = new SparseArray<>();

        public boolean isUidForeground(int uid) {
            synchronized (mLock) {
                return mProcStatesCache.get(uid, ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND)
                        <= ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
            }
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            FgThread.getHandler().post(() -> {
                synchronized (mLock) {
                    ArrayMap<IBinder, ArraySet<AppHintSession>> tokenMap = mActiveSessions.get(uid);
                    if (tokenMap == null) {
                        return;
                    }
                    for (int i = tokenMap.size() - 1; i >= 0; i--) {
                        // Will remove the session from tokenMap
                        ArraySet<AppHintSession> sessionSet = tokenMap.valueAt(i);
                        for (int j = sessionSet.size() - 1; j >= 0; j--) {
                            sessionSet.valueAt(j).close();
                        }
                    }
                    mProcStatesCache.delete(uid);
                }
            });
        }

        /**
         * The IUidObserver callback is called from the system_server, so it'll be a direct function
         * call from ActivityManagerService. Do not do heavy logic here.
         */
        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
            FgThread.getHandler().post(() -> {
                synchronized (mLock) {
                    mProcStatesCache.put(uid, procState);
                    ArrayMap<IBinder, ArraySet<AppHintSession>> tokenMap = mActiveSessions.get(uid);
                    if (tokenMap == null) {
                        return;
                    }
                    for (ArraySet<AppHintSession> sessionSet : tokenMap.values()) {
                        for (AppHintSession s : sessionSet) {
                            s.onProcStateChanged();
                        }
                    }
                }
            });
        }
    }

    @VisibleForTesting
    IHintManager.Stub getBinderServiceInstance() {
        return mService;
    }

    private boolean checkTidValid(int uid, int tgid, int [] tids) {
        // Make sure all tids belongs to the same UID (including isolated UID),
        // tids can belong to different application processes.
        List<Integer> isolatedPids = null;
        for (int threadId : tids) {
            final String[] procStatusKeys = new String[] {
                    "Uid:",
                    "Tgid:"
            };
            long[] output = new long[procStatusKeys.length];
            Process.readProcLines("/proc/" + threadId + "/status", procStatusKeys, output);
            int uidOfThreadId = (int) output[0];
            int pidOfThreadId = (int) output[1];

            // use PID check for isolated processes, use UID check for non-isolated processes.
            if (pidOfThreadId == tgid || uidOfThreadId == uid) {
                continue;
            }
            // Only call into AM if the tid is either isolated or invalid
            if (isolatedPids == null) {
                // To avoid deadlock, do not call into AMS if the call is from system.
                if (uid == Process.SYSTEM_UID) {
                    return false;
                }
                isolatedPids = mAmInternal.getIsolatedProcesses(uid);
                if (isolatedPids == null) {
                    return false;
                }
            }
            if (isolatedPids.contains(pidOfThreadId)) {
                continue;
            }
            return false;
        }
        return true;
    }

    @VisibleForTesting
    final class BinderService extends IHintManager.Stub {
        @Override
        public IHintSession createHintSession(IBinder token, int[] tids, long durationNanos) {
            if (!isHalSupported()) return null;

            java.util.Objects.requireNonNull(token);
            java.util.Objects.requireNonNull(tids);
            Preconditions.checkArgument(tids.length != 0, "tids should"
                    + " not be empty.");

            final int callingUid = Binder.getCallingUid();
            final int callingTgid = Process.getThreadGroupLeader(Binder.getCallingPid());
            final long identity = Binder.clearCallingIdentity();
            try {
                if (!checkTidValid(callingUid, callingTgid, tids)) {
                    throw new SecurityException("Some tid doesn't belong to the application");
                }

                long halSessionPtr = mNativeWrapper.halCreateHintSession(callingTgid, callingUid,
                        tids, durationNanos);
                if (halSessionPtr == 0) {
                    return null;
                }

                AppHintSession hs = new AppHintSession(callingUid, callingTgid, tids, token,
                        halSessionPtr, durationNanos);
                logPerformanceHintSessionAtom(callingUid, halSessionPtr, durationNanos, tids);
                synchronized (mLock) {
                    ArrayMap<IBinder, ArraySet<AppHintSession>> tokenMap =
                            mActiveSessions.get(callingUid);
                    if (tokenMap == null) {
                        tokenMap = new ArrayMap<>(1);
                        mActiveSessions.put(callingUid, tokenMap);
                    }
                    ArraySet<AppHintSession> sessionSet = tokenMap.get(token);
                    if (sessionSet == null) {
                        sessionSet = new ArraySet<>(1);
                        tokenMap.put(token, sessionSet);
                    }
                    sessionSet.add(hs);
                    return hs;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public long getHintSessionPreferredRate() {
            return mHintSessionPreferredRate;
        }

        @Override
        public void setHintSessionThreads(@NonNull IHintSession hintSession, @NonNull int[] tids) {
            AppHintSession appHintSession = (AppHintSession) hintSession;
            appHintSession.setThreads(tids);
        }

        @Override
        public int[] getHintSessionThreadIds(@NonNull IHintSession hintSession) {
            AppHintSession appHintSession = (AppHintSession) hintSession;
            return appHintSession.getThreadIds();
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) {
                return;
            }
            synchronized (mLock) {
                pw.println("HintSessionPreferredRate: " + mHintSessionPreferredRate);
                pw.println("HAL Support: " + isHalSupported());
                pw.println("Active Sessions:");
                for (int i = 0; i < mActiveSessions.size(); i++) {
                    pw.println("Uid " + mActiveSessions.keyAt(i).toString() + ":");
                    ArrayMap<IBinder, ArraySet<AppHintSession>> tokenMap =
                            mActiveSessions.valueAt(i);
                    for (int j = 0; j < tokenMap.size(); j++) {
                        ArraySet<AppHintSession> sessionSet = tokenMap.valueAt(j);
                        for (int k = 0; k < sessionSet.size(); ++k) {
                            pw.println("  Session:");
                            sessionSet.valueAt(k).dump(pw, "    ");
                        }
                    }
                }
            }
        }

        private void logPerformanceHintSessionAtom(int uid, long sessionId,
                long targetDuration, int[] tids) {
            FrameworkStatsLog.write(FrameworkStatsLog.PERFORMANCE_HINT_SESSION_REPORTED, uid,
                    sessionId, targetDuration, tids.length);
        }
    }

    @VisibleForTesting
    final class AppHintSession extends IHintSession.Stub implements IBinder.DeathRecipient {
        protected final int mUid;
        protected final int mPid;
        protected int[] mThreadIds;
        protected final IBinder mToken;
        protected long mHalSessionPtr;
        protected long mTargetDurationNanos;
        protected boolean mUpdateAllowed;
        protected int[] mNewThreadIds;

        protected AppHintSession(
                int uid, int pid, int[] threadIds, IBinder token,
                long halSessionPtr, long durationNanos) {
            mUid = uid;
            mPid = pid;
            mToken = token;
            mThreadIds = threadIds;
            mHalSessionPtr = halSessionPtr;
            mTargetDurationNanos = durationNanos;
            mUpdateAllowed = true;
            updateHintAllowed();
            try {
                token.linkToDeath(this, 0);
            } catch (RemoteException e) {
                mNativeWrapper.halCloseHintSession(mHalSessionPtr);
                throw new IllegalStateException("Client already dead", e);
            }
        }

        @VisibleForTesting
        boolean updateHintAllowed() {
            synchronized (mLock) {
                final boolean allowed = mUidObserver.isUidForeground(mUid);
                if (allowed && !mUpdateAllowed) resume();
                if (!allowed && mUpdateAllowed) pause();
                mUpdateAllowed = allowed;
                return mUpdateAllowed;
            }
        }

        @Override
        public void updateTargetWorkDuration(long targetDurationNanos) {
            synchronized (mLock) {
                if (mHalSessionPtr == 0 || !updateHintAllowed()) {
                    return;
                }
                Preconditions.checkArgument(targetDurationNanos > 0, "Expected"
                        + " the target duration to be greater than 0.");
                mNativeWrapper.halUpdateTargetWorkDuration(mHalSessionPtr, targetDurationNanos);
                mTargetDurationNanos = targetDurationNanos;
            }
        }

        @Override
        public void reportActualWorkDuration(long[] actualDurationNanos, long[] timeStampNanos) {
            synchronized (mLock) {
                if (mHalSessionPtr == 0 || !updateHintAllowed()) {
                    return;
                }
                Preconditions.checkArgument(actualDurationNanos.length != 0, "the count"
                        + " of hint durations shouldn't be 0.");
                Preconditions.checkArgument(actualDurationNanos.length == timeStampNanos.length,
                        "The length of durations and timestamps should be the same.");
                for (int i = 0; i < actualDurationNanos.length; i++) {
                    if (actualDurationNanos[i] <= 0) {
                        throw new IllegalArgumentException(
                                String.format("durations[%d]=%d should be greater than 0",
                                        i, actualDurationNanos[i]));
                    }
                }
                mNativeWrapper.halReportActualWorkDuration(mHalSessionPtr, actualDurationNanos,
                        timeStampNanos);
            }
        }

        /** TODO: consider monitor session threads and close session if any thread is dead. */
        @Override
        public void close() {
            synchronized (mLock) {
                if (mHalSessionPtr == 0) return;
                mNativeWrapper.halCloseHintSession(mHalSessionPtr);
                mHalSessionPtr = 0;
                mToken.unlinkToDeath(this, 0);
                ArrayMap<IBinder, ArraySet<AppHintSession>> tokenMap = mActiveSessions.get(mUid);
                if (tokenMap == null) {
                    Slogf.w(TAG, "UID %d is not present in active session map", mUid);
                    return;
                }
                ArraySet<AppHintSession> sessionSet = tokenMap.get(mToken);
                if (sessionSet == null) {
                    Slogf.w(TAG, "Token %s is not present in token map", mToken.toString());
                    return;
                }
                sessionSet.remove(this);
                if (sessionSet.isEmpty()) tokenMap.remove(mToken);
                if (tokenMap.isEmpty()) mActiveSessions.remove(mUid);
            }
        }

        @Override
        public void sendHint(@PerformanceHintManager.Session.Hint int hint) {
            synchronized (mLock) {
                if (mHalSessionPtr == 0 || !updateHintAllowed()) {
                    return;
                }
                Preconditions.checkArgument(hint >= 0, "the hint ID the hint value should be"
                        + " greater than zero.");
                mNativeWrapper.halSendHint(mHalSessionPtr, hint);
            }
        }

        public void setThreads(@NonNull int[] tids) {
            synchronized (mLock) {
                if (mHalSessionPtr == 0) {
                    return;
                }
                if (tids.length == 0) {
                    throw new IllegalArgumentException("Thread id list can't be empty.");
                }
                final int callingUid = Binder.getCallingUid();
                final int callingTgid = Process.getThreadGroupLeader(Binder.getCallingPid());
                final long identity = Binder.clearCallingIdentity();
                try {
                    if (!checkTidValid(callingUid, callingTgid, tids)) {
                        throw new SecurityException("Some tid doesn't belong to the application.");
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
                if (!updateHintAllowed()) {
                    Slogf.v(TAG, "update hint not allowed, storing tids.");
                    mNewThreadIds = tids;
                    return;
                }
                mNativeWrapper.halSetThreads(mHalSessionPtr, tids);
                mThreadIds = tids;
            }
        }

        public int[] getThreadIds() {
            return mThreadIds;
        }

        private void onProcStateChanged() {
            updateHintAllowed();
        }

        private void pause() {
            synchronized (mLock) {
                if (mHalSessionPtr == 0) return;
                mNativeWrapper.halPauseHintSession(mHalSessionPtr);
            }
        }

        private void resume() {
            synchronized (mLock) {
                if (mHalSessionPtr == 0) return;
                mNativeWrapper.halResumeHintSession(mHalSessionPtr);
                if (mNewThreadIds != null) {
                    mNativeWrapper.halSetThreads(mHalSessionPtr, mNewThreadIds);
                    mThreadIds = mNewThreadIds;
                    mNewThreadIds = null;
                }
            }
        }

        private void dump(PrintWriter pw, String prefix) {
            synchronized (mLock) {
                pw.println(prefix + "SessionPID: " + mPid);
                pw.println(prefix + "SessionUID: " + mUid);
                pw.println(prefix + "SessionTIDs: " + Arrays.toString(mThreadIds));
                pw.println(prefix + "SessionTargetDurationNanos: " + mTargetDurationNanos);
                pw.println(prefix + "SessionAllowed: " + updateHintAllowed());
            }
        }

        @Override
        public void binderDied() {
            close();
        }

    }
}
