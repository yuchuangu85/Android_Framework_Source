/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.os;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.BugreportManager.BugreportCallback;
import android.os.BugreportParams;
import android.os.IDumpstate;
import android.os.IDumpstateListener;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LocalLog;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemConfig;
import com.android.server.utils.Slogf;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Implementation of the service that provides a privileged API to capture and consume bugreports.
 *
 * <p>Delegates the actualy generation to a native implementation of {@code IDumpstate}.
 */
class BugreportManagerServiceImpl extends IDumpstate.Stub {

    private static final int LOCAL_LOG_SIZE = 20;
    private static final String TAG = "BugreportManagerService";
    private static final boolean DEBUG = false;

    private static final String BUGREPORT_SERVICE = "bugreportd";
    private static final long DEFAULT_BUGREPORT_SERVICE_TIMEOUT_MILLIS = 30 * 1000;

    private final Object mLock = new Object();
    private final Context mContext;
    private final AppOpsManager mAppOps;
    private final TelephonyManager mTelephonyManager;
    private final ArraySet<String> mBugreportAllowlistedPackages;
    private final BugreportFileManager mBugreportFileManager;


    @GuardedBy("mLock")
    private OptionalInt mPreDumpedDataUid = OptionalInt.empty();

    // Attributes below are just Used for dump() purposes
    @Nullable
    @GuardedBy("mLock")
    private DumpstateListener mCurrentDumpstateListener;
    @GuardedBy("mLock")
    private int mNumberFinishedBugreports;
    @GuardedBy("mLock")
    private final LocalLog mFinishedBugreports = new LocalLog(LOCAL_LOG_SIZE);

    /** Helper class for associating previously generated bugreports with their callers. */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    static class BugreportFileManager {

        private final Object mLock = new Object();

        @GuardedBy("mLock")
        private final ArrayMap<Pair<Integer, String>, ArraySet<String>> mBugreportFiles =
                new ArrayMap<>();

        /**
         * Checks that a given file was generated on behalf of the given caller. If the file was
         * generated on behalf of the caller, it is removed from the bugreport mapping so that it
         * may not be retrieved again. If the file was not generated on behalf of the caller, an
         * {@link IllegalArgumentException} is thrown.
         *
         * @param callingInfo a (uid, package name) pair identifying the caller
         * @param bugreportFile the file name which was previously given to the caller in the
         *                      {@link BugreportCallback#onFinished(String)} callback.
         *
         * @throws IllegalArgumentException if {@code bugreportFile} is not associated with
         *                                  {@code callingInfo}.
         */
        void ensureCallerPreviouslyGeneratedFile(
                Pair<Integer, String> callingInfo, String bugreportFile) {
            synchronized (mLock) {
                ArraySet<String> bugreportFilesForCaller = mBugreportFiles.get(callingInfo);
                if (bugreportFilesForCaller != null
                        && bugreportFilesForCaller.contains(bugreportFile)) {
                    bugreportFilesForCaller.remove(bugreportFile);
                    if (bugreportFilesForCaller.isEmpty()) {
                        mBugreportFiles.remove(callingInfo);
                    }
                } else {
                    throw new IllegalArgumentException(
                            "File " + bugreportFile + " was not generated"
                                    + " on behalf of calling package " + callingInfo.second);
                }
            }
        }

        /**
         * Associates a bugreport file with a caller, which is identified as a
         * (uid, package name) pair.
         */
        void addBugreportFileForCaller(Pair<Integer, String> caller, String bugreportFile) {
            synchronized (mLock) {
                if (!mBugreportFiles.containsKey(caller)) {
                    mBugreportFiles.put(caller, new ArraySet<>());
                }
                ArraySet<String> bugreportFilesForCaller = mBugreportFiles.get(caller);
                bugreportFilesForCaller.add(bugreportFile);
            }
        }
    }

    static class Injector {
        Context mContext;
        ArraySet<String> mAllowlistedPackages;

        Injector(Context context, ArraySet<String> allowlistedPackages) {
            mContext = context;
            mAllowlistedPackages = allowlistedPackages;
        }

        Context getContext() {
            return mContext;
        }

        ArraySet<String> getAllowlistedPackages() {
            return mAllowlistedPackages;
        }

    }

    BugreportManagerServiceImpl(Context context) {
        this(new Injector(context, SystemConfig.getInstance().getBugreportWhitelistedPackages()));

    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    BugreportManagerServiceImpl(Injector injector) {
        mContext = injector.getContext();
        mAppOps = mContext.getSystemService(AppOpsManager.class);
        mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
        mBugreportFileManager = new BugreportFileManager();
        mBugreportAllowlistedPackages = injector.getAllowlistedPackages();
    }

    @Override
    @RequiresPermission(android.Manifest.permission.DUMP)
    public void preDumpUiData(String callingPackage) {
        enforcePermission(callingPackage, Binder.getCallingUid(), true);

        synchronized (mLock) {
            preDumpUiDataLocked(callingPackage);
        }
    }

    @Override
    @RequiresPermission(android.Manifest.permission.DUMP)
    public void startBugreport(int callingUidUnused, String callingPackage,
            FileDescriptor bugreportFd, FileDescriptor screenshotFd,
            int bugreportMode, int bugreportFlags, IDumpstateListener listener,
            boolean isScreenshotRequested) {
        Objects.requireNonNull(callingPackage);
        Objects.requireNonNull(bugreportFd);
        Objects.requireNonNull(listener);
        validateBugreportMode(bugreportMode);
        validateBugreportFlags(bugreportFlags);

        int callingUid = Binder.getCallingUid();
        enforcePermission(callingPackage, callingUid, bugreportMode
                == BugreportParams.BUGREPORT_MODE_TELEPHONY /* checkCarrierPrivileges */);
        final long identity = Binder.clearCallingIdentity();
        try {
            ensureUserCanTakeBugReport(bugreportMode);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        Slogf.i(TAG, "Starting bugreport for %s / %d", callingPackage, callingUid);
        synchronized (mLock) {
            startBugreportLocked(callingUid, callingPackage, bugreportFd, screenshotFd,
                    bugreportMode, bugreportFlags, listener, isScreenshotRequested);
        }
    }

    @Override
    @RequiresPermission(android.Manifest.permission.DUMP) // or carrier privileges
    public void cancelBugreport(int callingUidUnused, String callingPackage) {
        int callingUid = Binder.getCallingUid();
        enforcePermission(callingPackage, callingUid, true /* checkCarrierPrivileges */);

        Slogf.i(TAG, "Cancelling bugreport for %s / %d", callingPackage, callingUid);
        synchronized (mLock) {
            IDumpstate ds = getDumpstateBinderServiceLocked();
            if (ds == null) {
                Slog.w(TAG, "cancelBugreport: Could not find native dumpstate service");
                return;
            }
            try {
                // Note: this may throw SecurityException back out to the caller if they aren't
                // allowed to cancel the report, in which case we should NOT stop the dumpstate
                // service, since that would unintentionally kill some other app's bugreport, which
                // we specifically disallow.
                ds.cancelBugreport(callingUid, callingPackage);
            } catch (RemoteException e) {
                Slog.e(TAG, "RemoteException in cancelBugreport", e);
            }
            stopDumpstateBinderServiceLocked();
        }
    }

    @Override
    @RequiresPermission(Manifest.permission.DUMP)
    public void retrieveBugreport(int callingUidUnused, String callingPackage,
            FileDescriptor bugreportFd, String bugreportFile, IDumpstateListener listener) {
        int callingUid = Binder.getCallingUid();
        enforcePermission(callingPackage, callingUid, false);

        Slogf.i(TAG, "Retrieving bugreport for %s / %d", callingPackage, callingUid);
        try {
            mBugreportFileManager.ensureCallerPreviouslyGeneratedFile(
                    new Pair<>(callingUid, callingPackage), bugreportFile);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, e.getMessage());
            reportError(listener, IDumpstateListener.BUGREPORT_ERROR_NO_BUGREPORT_TO_RETRIEVE);
            return;
        }

        synchronized (mLock) {
            if (isDumpstateBinderServiceRunningLocked()) {
                Slog.w(TAG, "'dumpstate' is already running. Cannot retrieve a bugreport"
                        + " while another one is currently in progress.");
                reportError(listener,
                        IDumpstateListener.BUGREPORT_ERROR_ANOTHER_REPORT_IN_PROGRESS);
                return;
            }

            IDumpstate ds = startAndGetDumpstateBinderServiceLocked();
            if (ds == null) {
                Slog.w(TAG, "Unable to get bugreport service");
                reportError(listener, IDumpstateListener.BUGREPORT_ERROR_RUNTIME_ERROR);
                return;
            }

            // Wrap the listener so we can intercept binder events directly.
            DumpstateListener myListener = new DumpstateListener(listener, ds,
                    new Pair<>(callingUid, callingPackage), /* reportFinishedFile= */ true);
            setCurrentDumpstateListenerLocked(myListener);
            try {
                ds.retrieveBugreport(callingUid, callingPackage, bugreportFd,
                        bugreportFile, myListener);
            } catch (RemoteException e) {
                Slog.e(TAG, "RemoteException in retrieveBugreport", e);
            }
        }
    }

    @GuardedBy("mLock")
    private void setCurrentDumpstateListenerLocked(DumpstateListener listener) {
        if (mCurrentDumpstateListener != null) {
            Slogf.w(TAG, "setCurrentDumpstateListenerLocked(%s): called when "
                    + "mCurrentDumpstateListener is already set (%s)", listener,
                    mCurrentDumpstateListener);
        }
        mCurrentDumpstateListener = listener;
    }

    private void validateBugreportMode(@BugreportParams.BugreportMode int mode) {
        if (mode != BugreportParams.BUGREPORT_MODE_FULL
                && mode != BugreportParams.BUGREPORT_MODE_INTERACTIVE
                && mode != BugreportParams.BUGREPORT_MODE_REMOTE
                && mode != BugreportParams.BUGREPORT_MODE_WEAR
                && mode != BugreportParams.BUGREPORT_MODE_TELEPHONY
                && mode != BugreportParams.BUGREPORT_MODE_WIFI) {
            Slog.w(TAG, "Unknown bugreport mode: " + mode);
            throw new IllegalArgumentException("Unknown bugreport mode: " + mode);
        }
    }

    private void validateBugreportFlags(int flags) {
        flags = clearBugreportFlag(flags,
                BugreportParams.BUGREPORT_FLAG_USE_PREDUMPED_UI_DATA
                        | BugreportParams.BUGREPORT_FLAG_DEFER_CONSENT);
        if (flags != 0) {
            Slog.w(TAG, "Unknown bugreport flags: " + flags);
            throw new IllegalArgumentException("Unknown bugreport flags: " + flags);
        }
    }

    private void enforcePermission(
            String callingPackage, int callingUid, boolean checkCarrierPrivileges) {
        mAppOps.checkPackage(callingUid, callingPackage);

        // To gain access through the DUMP permission, the OEM has to allow this package explicitly
        // via sysconfig and privileged permissions.
        if (mBugreportAllowlistedPackages.contains(callingPackage)
                && mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                        == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        // For carrier privileges, this can include user-installed apps. This is essentially a
        // function of the current active SIM(s) in the device to let carrier apps through.
        final long token = Binder.clearCallingIdentity();
        try {
            if (checkCarrierPrivileges
                    && mTelephonyManager.checkCarrierPrivilegesForPackageAnyPhone(callingPackage)
                            == TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
                return;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        String message =
                callingPackage
                        + " does not hold the DUMP permission or is not bugreport-whitelisted "
                        + (checkCarrierPrivileges ? "and does not have carrier privileges " : "")
                        + "to request a bugreport";
        Slog.w(TAG, message);
        throw new SecurityException(message);
    }

    /**
     * Validates that the current user is an admin user or, when bugreport is requested remotely
     * that the current user is an affiliated user.
     *
     * @throws IllegalArgumentException if the current user is not an admin user
     */
    private void ensureUserCanTakeBugReport(int bugreportMode) {
        UserInfo currentUser = null;
        try {
            currentUser = ActivityManager.getService().getCurrentUser();
        } catch (RemoteException e) {
            // Impossible to get RemoteException for an in-process call.
        }

        if (currentUser == null) {
            logAndThrow("There is no current user, so no bugreport can be requested.");
        }

        if (!currentUser.isAdmin()) {
            if (bugreportMode == BugreportParams.BUGREPORT_MODE_REMOTE
                    && isCurrentUserAffiliated(currentUser.id)) {
                return;
            }
            logAndThrow(TextUtils.formatSimple("Current user %s is not an admin user."
                    + " Only admin users are allowed to take bugreport.", currentUser.id));
        }
    }

    /**
     * Returns {@code true} if the device has device owner and the current user is affiliated
     * with the device owner.
     */
    private boolean isCurrentUserAffiliated(int currentUserId) {
        DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
        int deviceOwnerUid = dpm.getDeviceOwnerUserId();
        if (deviceOwnerUid == UserHandle.USER_NULL) {
            return false;
        }

        int callingUserId = UserHandle.getUserId(Binder.getCallingUid());

        Slog.i(TAG, "callingUid: " + callingUserId + " deviceOwnerUid: " + deviceOwnerUid
                + " currentUserId: " + currentUserId);

        if (callingUserId != deviceOwnerUid) {
            logAndThrow("Caller is not device owner on provisioned device.");
        }
        if (!dpm.isAffiliatedUser(currentUserId)) {
            logAndThrow("Current user is not affiliated to the device owner.");
        }
        return true;
    }

    @GuardedBy("mLock")
    private void preDumpUiDataLocked(String callingPackage) {
        mPreDumpedDataUid = OptionalInt.empty();

        if (isDumpstateBinderServiceRunningLocked()) {
            Slog.e(TAG, "'dumpstate' is already running. "
                    + "Cannot pre-dump data while another operation is currently in progress.");
            return;
        }

        IDumpstate ds = startAndGetDumpstateBinderServiceLocked();
        if (ds == null) {
            Slog.e(TAG, "Unable to get bugreport service");
            return;
        }

        try {
            ds.preDumpUiData(callingPackage);
        } catch (RemoteException e) {
            return;
        } finally {
            // dumpstate service is already started now. We need to kill it to manage the
            // lifecycle correctly. If we don't subsequent callers will get
            // BUGREPORT_ERROR_ANOTHER_REPORT_IN_PROGRESS error.
            stopDumpstateBinderServiceLocked();
        }


        mPreDumpedDataUid = OptionalInt.of(Binder.getCallingUid());
    }

    @GuardedBy("mLock")
    private void startBugreportLocked(int callingUid, String callingPackage,
            FileDescriptor bugreportFd, FileDescriptor screenshotFd,
            int bugreportMode, int bugreportFlags, IDumpstateListener listener,
            boolean isScreenshotRequested) {
        if (isDumpstateBinderServiceRunningLocked()) {
            Slog.w(TAG, "'dumpstate' is already running. Cannot start a new bugreport"
                    + " while another operation is currently in progress.");
            reportError(listener, IDumpstateListener.BUGREPORT_ERROR_ANOTHER_REPORT_IN_PROGRESS);
            return;
        }

        if ((bugreportFlags & BugreportParams.BUGREPORT_FLAG_USE_PREDUMPED_UI_DATA) != 0) {
            if (mPreDumpedDataUid.isEmpty()) {
                bugreportFlags = clearBugreportFlag(bugreportFlags,
                        BugreportParams.BUGREPORT_FLAG_USE_PREDUMPED_UI_DATA);
                Slog.w(TAG, "Ignoring BUGREPORT_FLAG_USE_PREDUMPED_UI_DATA."
                        + " No pre-dumped data is available.");
            } else if (mPreDumpedDataUid.getAsInt() != callingUid) {
                bugreportFlags = clearBugreportFlag(bugreportFlags,
                        BugreportParams.BUGREPORT_FLAG_USE_PREDUMPED_UI_DATA);
                Slog.w(TAG, "Ignoring BUGREPORT_FLAG_USE_PREDUMPED_UI_DATA."
                        + " Data was pre-dumped by a different UID.");
            }
        }

        boolean reportFinishedFile =
                (bugreportFlags & BugreportParams.BUGREPORT_FLAG_DEFER_CONSENT) != 0;

        IDumpstate ds = startAndGetDumpstateBinderServiceLocked();
        if (ds == null) {
            Slog.w(TAG, "Unable to get bugreport service");
            reportError(listener, IDumpstateListener.BUGREPORT_ERROR_RUNTIME_ERROR);
            return;
        }

        DumpstateListener myListener = new DumpstateListener(listener, ds,
                new Pair<>(callingUid, callingPackage), reportFinishedFile);
        setCurrentDumpstateListenerLocked(myListener);
        try {
            ds.startBugreport(callingUid, callingPackage, bugreportFd, screenshotFd, bugreportMode,
                    bugreportFlags, myListener, isScreenshotRequested);
        } catch (RemoteException e) {
            // dumpstate service is already started now. We need to kill it to manage the
            // lifecycle correctly. If we don't subsequent callers will get
            // BUGREPORT_ERROR_ANOTHER_REPORT_IN_PROGRESS error.
            // Note that listener will be notified by the death recipient below.
            cancelBugreport(callingUid, callingPackage);
        }
    }

    @GuardedBy("mLock")
    private boolean isDumpstateBinderServiceRunningLocked() {
        return getDumpstateBinderServiceLocked() != null;
    }

    @GuardedBy("mLock")
    @Nullable
    private IDumpstate getDumpstateBinderServiceLocked() {
        // Note that the binder service on the native side is "dumpstate".
        return IDumpstate.Stub.asInterface(ServiceManager.getService("dumpstate"));
    }

    /*
     * Start and get a handle to the native implementation of {@code IDumpstate} which does the
     * actual bugreport generation.
     *
     * <p>Generating bugreports requires root privileges. To limit the footprint
     * of the root access, the actual generation in Dumpstate binary is accessed as a
     * oneshot service 'bugreport'.
     *
     * <p>Note that starting the service is achieved through setting a system property, which is
     * not thread-safe. So the lock here offers thread-safety only among callers of the API.
     */
    @GuardedBy("mLock")
    private IDumpstate startAndGetDumpstateBinderServiceLocked() {
        // Start bugreport service.
        SystemProperties.set("ctl.start", BUGREPORT_SERVICE);

        IDumpstate ds = null;
        boolean timedOut = false;
        int totalTimeWaitedMillis = 0;
        int seedWaitTimeMillis = 500;
        while (!timedOut) {
            ds = getDumpstateBinderServiceLocked();
            if (ds != null) {
                Slog.i(TAG, "Got bugreport service handle.");
                break;
            }
            SystemClock.sleep(seedWaitTimeMillis);
            Slog.i(TAG,
                    "Waiting to get dumpstate service handle (" + totalTimeWaitedMillis + "ms)");
            totalTimeWaitedMillis += seedWaitTimeMillis;
            seedWaitTimeMillis *= 2;
            timedOut = totalTimeWaitedMillis > DEFAULT_BUGREPORT_SERVICE_TIMEOUT_MILLIS;
        }
        if (timedOut) {
            Slog.w(TAG,
                    "Timed out waiting to get dumpstate service handle ("
                    + totalTimeWaitedMillis + "ms)");
        }
        return ds;
    }

    @GuardedBy("mLock")
    private void stopDumpstateBinderServiceLocked() {
        // This tells init to cancel bugreportd service. Note that this is achieved through
        // setting a system property which is not thread-safe. So the lock here offers
        // thread-safety only among callers of the API.
        SystemProperties.set("ctl.stop", BUGREPORT_SERVICE);
    }

    @RequiresPermission(android.Manifest.permission.DUMP)
    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        pw.printf("Allow-listed packages: %s\n", mBugreportAllowlistedPackages);

        synchronized (mLock) {
            pw.print("Pre-dumped data UID: ");
            if (mPreDumpedDataUid.isEmpty()) {
                pw.println("none");
            } else {
                pw.println(mPreDumpedDataUid.getAsInt());
            }

            if (mCurrentDumpstateListener == null) {
                pw.println("Not taking a bug report");
            } else {
                mCurrentDumpstateListener.dump(pw);
            }

            if (mNumberFinishedBugreports == 0) {
                pw.println("No finished bugreports");
            } else {
                pw.printf("%d finished bugreport%s. Last %d:\n", mNumberFinishedBugreports,
                        (mNumberFinishedBugreports > 1 ? "s" : ""),
                        Math.min(mNumberFinishedBugreports, LOCAL_LOG_SIZE));
                mFinishedBugreports.dump("  ", pw);
            }
        }

        synchronized (mBugreportFileManager.mLock) {
            int numberFiles = mBugreportFileManager.mBugreportFiles.size();
            pw.printf("%d pending file%s", numberFiles, (numberFiles > 1 ? "s" : ""));
            if (numberFiles > 0) {
                for (int i = 0; i < numberFiles; i++) {
                    Pair<Integer, String> caller = mBugreportFileManager.mBugreportFiles.keyAt(i);
                    ArraySet<String> files = mBugreportFileManager.mBugreportFiles.valueAt(i);
                    pw.printf("  %s: %s\n", callerToString(caller), files);
                }
            } else {
                pw.println();
            }
        }
    }

    private static String callerToString(@Nullable Pair<Integer, String> caller) {
        return (caller == null) ? "N/A" : caller.second + "/" + caller.first;
    }

    private int clearBugreportFlag(int flags, @BugreportParams.BugreportFlag int flag) {
        flags &= ~flag;
        return flags;
    }

    private void reportError(IDumpstateListener listener, int errorCode) {
        try {
            listener.onError(errorCode);
        } catch (RemoteException e) {
            // Something went wrong in binder or app process. There's nothing to do here.
            Slog.w(TAG, "onError() transaction threw RemoteException: " + e.getMessage());
        }
    }

    private void logAndThrow(String message) {
        Slog.w(TAG, message);
        throw new IllegalArgumentException(message);
    }

    private final class DumpstateListener extends IDumpstateListener.Stub
            implements DeathRecipient {

        private static int sNextId;

        private final int mId = ++sNextId; // used for debugging purposes only
        private final IDumpstateListener mListener;
        private final IDumpstate mDs;
        private final Pair<Integer, String> mCaller;
        private final boolean mReportFinishedFile;
        private int mProgress; // used for debugging purposes only
        private boolean mDone;

        DumpstateListener(IDumpstateListener listener, IDumpstate ds,
                Pair<Integer, String> caller, boolean reportFinishedFile) {
            if (DEBUG) {
                Slogf.d(TAG, "Starting DumpstateListener(id=%d) for caller %s", mId, caller);
            }
            mListener = listener;
            mDs = ds;
            mCaller = caller;
            mReportFinishedFile = reportFinishedFile;
            try {
                mDs.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to register Death Recipient for IDumpstate", e);
            }
        }

        @Override
        public void onProgress(int progress) throws RemoteException {
            if (DEBUG) {
                Slogf.d(TAG, "onProgress: %d", progress);
            }
            mProgress = progress;
            mListener.onProgress(progress);
        }

        @Override
        public void onError(int errorCode) throws RemoteException {
            Slogf.e(TAG, "onError(): %d", errorCode);
            synchronized (mLock) {
                releaseItselfLocked();
                reportFinishedLocked("ErroCode: " + errorCode);
            }
            mListener.onError(errorCode);
        }

        @Override
        public void onFinished(String bugreportFile) throws RemoteException {
            Slogf.i(TAG, "onFinished(): %s", bugreportFile);
            synchronized (mLock) {
                releaseItselfLocked();
                reportFinishedLocked("File: " + bugreportFile);
            }
            if (mReportFinishedFile) {
                mBugreportFileManager.addBugreportFileForCaller(mCaller, bugreportFile);
            } else if (DEBUG) {
                Slog.d(TAG, "Not reporting finished file");
            }
            mListener.onFinished(bugreportFile);
        }

        @Override
        public void onScreenshotTaken(boolean success) throws RemoteException {
            if (DEBUG) {
                Slogf.d(TAG, "onScreenshotTaken(): %b", success);
            }
            mListener.onScreenshotTaken(success);
        }

        @Override
        public void onUiIntensiveBugreportDumpsFinished() throws RemoteException {
            if (DEBUG) {
                Slogf.d(TAG, "onUiIntensiveBugreportDumpsFinished()");
            }
            mListener.onUiIntensiveBugreportDumpsFinished();
        }

        @Override
        public void binderDied() {
            try {
                // Allow a small amount of time for any error or finished callbacks to be made.
                // This ensures that the listener does not receive an erroneous runtime error
                // callback.
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
            synchronized (mLock) {
                if (!mDone) {
                    // If we have not gotten a "done" callback this must be a crash.
                    Slog.e(TAG, "IDumpstate likely crashed. Notifying listener");
                    try {
                        mListener.onError(IDumpstateListener.BUGREPORT_ERROR_RUNTIME_ERROR);
                    } catch (RemoteException ignored) {
                        // If listener is not around, there isn't anything to do here.
                    }
                }
            }
            mDs.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public String toString() {
            return "DumpstateListener[id=" + mId + ", progress=" + mProgress + "]";
        }

        @GuardedBy("mLock")
        private void reportFinishedLocked(String message) {
            mNumberFinishedBugreports++;
            mFinishedBugreports.log("Caller: " + callerToString(mCaller) + " " + message);
        }

        private void dump(PrintWriter pw) {
            pw.println("DumpstateListener:");
            pw.printf("  id: %d\n", mId);
            pw.printf("  caller: %s\n", callerToString(mCaller));
            pw.printf("  reports finished file: %b\n", mReportFinishedFile);
            pw.printf("  progress: %d\n", mProgress);
            pw.printf("  done: %b\n", mDone);
        }

        @GuardedBy("mLock")
        private void releaseItselfLocked() {
            mDone = true;
            if (mCurrentDumpstateListener == this) {
                if (DEBUG) {
                    Slogf.d(TAG, "releaseItselfLocked(): releasing %s", this);
                }
                mCurrentDumpstateListener = null;
            } else {
                Slogf.w(TAG, "releaseItselfLocked(): " + this + " is finished, but current listener"
                        + " is " + mCurrentDumpstateListener);
            }
        }
    }
}
