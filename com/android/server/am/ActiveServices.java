/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.am;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static com.android.server.am.ActivityManagerDebugConfig.*;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import android.app.ActivityThread;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.ServiceStartArgs;
import android.content.ComponentName.WithComponentName;
import android.content.IIntentSender;
import android.content.IntentSender;
import android.content.pm.ParceledListSlice;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteCallback;
import android.os.SystemProperties;
import android.os.TransactionTooLargeException;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.R;
import com.android.internal.app.procstats.ServiceState;
import com.android.internal.messages.nano.SystemMessageProto;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.TransferPipe;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.server.AppStateTracker;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.am.ActivityManagerService.ItemMatcher;
import com.android.server.am.ActivityManagerService.NeededUriGrants;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.EventLog;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.StatsLog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.webkit.WebViewZygote;

public final class ActiveServices {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActiveServices" : TAG_AM;
    private static final String TAG_MU = TAG + POSTFIX_MU;
    private static final String TAG_SERVICE = TAG + POSTFIX_SERVICE;
    private static final String TAG_SERVICE_EXECUTING = TAG + POSTFIX_SERVICE_EXECUTING;

    private static final boolean DEBUG_DELAYED_SERVICE = DEBUG_SERVICE;
    private static final boolean DEBUG_DELAYED_STARTS = DEBUG_DELAYED_SERVICE;

    private static final boolean LOG_SERVICE_START_STOP = false;

    private static final boolean SHOW_DUNGEON_NOTIFICATION = false;

    // How long we wait for a service to finish executing.
    static final int SERVICE_TIMEOUT = 20*1000;

    // How long we wait for a service to finish executing.
    static final int SERVICE_BACKGROUND_TIMEOUT = SERVICE_TIMEOUT * 10;

    // How long the startForegroundService() grace period is to get around to
    // calling startForeground() before we ANR + stop it.
    static final int SERVICE_START_FOREGROUND_TIMEOUT = 10*1000;

    final ActivityManagerService mAm;

    // Maximum number of services that we allow to start in the background
    // at the same time.
    final int mMaxStartingBackground;

    final SparseArray<ServiceMap> mServiceMap = new SparseArray<>();

    /**
     * All currently bound service connections.  Keys are the IBinder of
     * the client's IServiceConnection.
     */
    final ArrayMap<IBinder, ArrayList<ConnectionRecord>> mServiceConnections = new ArrayMap<>();

    /**
     * List of services that we have been asked to start,
     * but haven't yet been able to.  It is used to hold start requests
     * while waiting for their corresponding application thread to get
     * going.
     */
    final ArrayList<ServiceRecord> mPendingServices = new ArrayList<>();

    /**
     * List of services that are scheduled to restart following a crash.
     */
    final ArrayList<ServiceRecord> mRestartingServices = new ArrayList<>();

    /**
     * List of services that are in the process of being destroyed.
     */
    final ArrayList<ServiceRecord> mDestroyingServices = new ArrayList<>();

    /** Temporary list for holding the results of calls to {@link #collectPackageServicesLocked} */
    private ArrayList<ServiceRecord> mTmpCollectionResults = null;

    /**
     * For keeping ActiveForegroundApps retaining state while the screen is off.
     */
    boolean mScreenOn = true;

    /** Amount of time to allow a last ANR message to exist before freeing the memory. */
    static final int LAST_ANR_LIFETIME_DURATION_MSECS = 2 * 60 * 60 * 1000; // Two hours

    String mLastAnrDump;

    final Runnable mLastAnrDumpClearer = new Runnable() {
        @Override public void run() {
            synchronized (mAm) {
                mLastAnrDump = null;
            }
        }
    };

    /**
     * Watch for apps being put into forced app standby, so we can step their fg
     * services down.
     */
    class ForcedStandbyListener extends AppStateTracker.Listener {
        @Override
        public void stopForegroundServicesForUidPackage(final int uid, final String packageName) {
            synchronized (mAm) {
                final ServiceMap smap = getServiceMapLocked(UserHandle.getUserId(uid));
                final int N = smap.mServicesByName.size();
                final ArrayList<ServiceRecord> toStop = new ArrayList<>(N);
                for (int i = 0; i < N; i++) {
                    final ServiceRecord r = smap.mServicesByName.valueAt(i);
                    if (uid == r.serviceInfo.applicationInfo.uid
                            || packageName.equals(r.serviceInfo.packageName)) {
                        if (r.isForeground) {
                            toStop.add(r);
                        }
                    }
                }

                // Now stop them all
                final int numToStop = toStop.size();
                if (numToStop > 0 && DEBUG_FOREGROUND_SERVICE) {
                    Slog.i(TAG, "Package " + packageName + "/" + uid
                            + " entering FAS with foreground services");
                }
                for (int i = 0; i < numToStop; i++) {
                    final ServiceRecord r = toStop.get(i);
                    if (DEBUG_FOREGROUND_SERVICE) {
                        Slog.i(TAG, "  Stopping fg for service " + r);
                    }
                    setServiceForegroundInnerLocked(r, 0, null, 0);
                }
            }
        }
    }

    /**
     * Information about an app that is currently running one or more foreground services.
     * (This maps directly to the running apps we show in the notification.)
     */
    static final class ActiveForegroundApp {
        String mPackageName;
        int mUid;
        CharSequence mLabel;
        boolean mShownWhileScreenOn;
        boolean mAppOnTop;
        boolean mShownWhileTop;
        long mStartTime;
        long mStartVisibleTime;
        long mEndTime;
        int mNumActive;

        // Temp output of foregroundAppShownEnoughLocked
        long mHideTime;
    }

    /**
     * Information about services for a single user.
     */
    final class ServiceMap extends Handler {
        final int mUserId;
        final ArrayMap<ComponentName, ServiceRecord> mServicesByName = new ArrayMap<>();
        final ArrayMap<Intent.FilterComparison, ServiceRecord> mServicesByIntent = new ArrayMap<>();

        final ArrayList<ServiceRecord> mDelayedStartList = new ArrayList<>();
        /* XXX eventually I'd like to have this based on processes instead of services.
         * That is, if we try to start two services in a row both running in the same
         * process, this should be one entry in mStartingBackground for that one process
         * that remains until all services in it are done.
        final ArrayMap<ProcessRecord, DelayingProcess> mStartingBackgroundMap
                = new ArrayMap<ProcessRecord, DelayingProcess>();
        final ArrayList<DelayingProcess> mStartingProcessList
                = new ArrayList<DelayingProcess>();
        */

        final ArrayList<ServiceRecord> mStartingBackground = new ArrayList<>();

        final ArrayMap<String, ActiveForegroundApp> mActiveForegroundApps = new ArrayMap<>();
        boolean mActiveForegroundAppsChanged;

        static final int MSG_BG_START_TIMEOUT = 1;
        static final int MSG_UPDATE_FOREGROUND_APPS = 2;

        ServiceMap(Looper looper, int userId) {
            super(looper);
            mUserId = userId;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_BG_START_TIMEOUT: {
                    synchronized (mAm) {
                        rescheduleDelayedStartsLocked();
                    }
                } break;
                case MSG_UPDATE_FOREGROUND_APPS: {
                    updateForegroundApps(this);
                } break;
            }
        }

        void ensureNotStartingBackgroundLocked(ServiceRecord r) {
            if (mStartingBackground.remove(r)) {
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE,
                        "No longer background starting: " + r);
                rescheduleDelayedStartsLocked();
            }
            if (mDelayedStartList.remove(r)) {
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "No longer delaying start: " + r);
            }
        }

        void rescheduleDelayedStartsLocked() {
            removeMessages(MSG_BG_START_TIMEOUT);
            final long now = SystemClock.uptimeMillis();
            for (int i=0, N=mStartingBackground.size(); i<N; i++) {
                ServiceRecord r = mStartingBackground.get(i);
                if (r.startingBgTimeout <= now) {
                    Slog.i(TAG, "Waited long enough for: " + r);
                    mStartingBackground.remove(i);
                    N--;
                    i--;
                }
            }
            while (mDelayedStartList.size() > 0
                    && mStartingBackground.size() < mMaxStartingBackground) {
                ServiceRecord r = mDelayedStartList.remove(0);
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE,
                        "REM FR DELAY LIST (exec next): " + r);
                if (r.pendingStarts.size() <= 0) {
                    Slog.w(TAG, "**** NO PENDING STARTS! " + r + " startReq=" + r.startRequested
                            + " delayedStop=" + r.delayedStop);
                }
                if (DEBUG_DELAYED_SERVICE) {
                    if (mDelayedStartList.size() > 0) {
                        Slog.v(TAG_SERVICE, "Remaining delayed list:");
                        for (int i=0; i<mDelayedStartList.size(); i++) {
                            Slog.v(TAG_SERVICE, "  #" + i + ": " + mDelayedStartList.get(i));
                        }
                    }
                }
                r.delayed = false;
                try {
                    startServiceInnerLocked(this, r.pendingStarts.get(0).intent, r, false, true);
                } catch (TransactionTooLargeException e) {
                    // Ignore, nobody upstack cares.
                }
            }
            if (mStartingBackground.size() > 0) {
                ServiceRecord next = mStartingBackground.get(0);
                long when = next.startingBgTimeout > now ? next.startingBgTimeout : now;
                if (DEBUG_DELAYED_SERVICE) Slog.v(TAG_SERVICE, "Top bg start is " + next
                        + ", can delay others up to " + when);
                Message msg = obtainMessage(MSG_BG_START_TIMEOUT);
                sendMessageAtTime(msg, when);
            }
            if (mStartingBackground.size() < mMaxStartingBackground) {
                mAm.backgroundServicesFinishedLocked(mUserId);
            }
        }
    }

    public ActiveServices(ActivityManagerService service) {
        mAm = service;
        int maxBg = 0;
        try {
            maxBg = Integer.parseInt(SystemProperties.get("ro.config.max_starting_bg", "0"));
        } catch(RuntimeException e) {
        }
        mMaxStartingBackground = maxBg > 0
                ? maxBg : ActivityManager.isLowRamDeviceStatic() ? 1 : 8;
    }

    void systemServicesReady() {
        AppStateTracker ast = LocalServices.getService(AppStateTracker.class);
        ast.addListener(new ForcedStandbyListener());
    }

    ServiceRecord getServiceByNameLocked(ComponentName name, int callingUser) {
        // TODO: Deal with global services
        if (DEBUG_MU)
            Slog.v(TAG_MU, "getServiceByNameLocked(" + name + "), callingUser = " + callingUser);
        return getServiceMapLocked(callingUser).mServicesByName.get(name);
    }

    boolean hasBackgroundServicesLocked(int callingUser) {
        ServiceMap smap = mServiceMap.get(callingUser);
        return smap != null ? smap.mStartingBackground.size() >= mMaxStartingBackground : false;
    }

    private ServiceMap getServiceMapLocked(int callingUser) {
        ServiceMap smap = mServiceMap.get(callingUser);
        if (smap == null) {
            smap = new ServiceMap(mAm.mHandler.getLooper(), callingUser);
            mServiceMap.put(callingUser, smap);
        }
        return smap;
    }

    ArrayMap<ComponentName, ServiceRecord> getServicesLocked(int callingUser) {
        return getServiceMapLocked(callingUser).mServicesByName;
    }

    private boolean appRestrictedAnyInBackground(final int uid, final String packageName) {
        final int mode = mAm.mAppOpsService.checkOperation(
                AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, uid, packageName);
        return (mode != AppOpsManager.MODE_ALLOWED);
    }

    ComponentName startServiceLocked(IApplicationThread caller, Intent service, String resolvedType,
            int callingPid, int callingUid, boolean fgRequired, String callingPackage, final int userId)
            throws TransactionTooLargeException {
        if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "startService: " + service
                + " type=" + resolvedType + " args=" + service.getExtras());

        final boolean callerFg;
        if (caller != null) {
            final ProcessRecord callerApp = mAm.getRecordForAppLocked(caller);
            if (callerApp == null) {
                throw new SecurityException(
                        "Unable to find app for caller " + caller
                        + " (pid=" + callingPid
                        + ") when starting service " + service);
            }
            callerFg = callerApp.setSchedGroup != ProcessList.SCHED_GROUP_BACKGROUND;
        } else {
            callerFg = true;
        }

        // 解析service这个Intent，就是解析在AndroidManifest.xml定义的Service标签的intent-filter相关内容
        // 并将其内容保存在Service的record（ServiceRecord）中
        ServiceLookupResult res =
            retrieveServiceLocked(service, resolvedType, callingPackage,
                    callingPid, callingUid, userId, true, callerFg, false, false);
        if (res == null) {// 解析失败或者不存在
            return null;
        }
        if (res.record == null) {// 解析失败或者服务描述对象ServiceRecord不存在
            return new ComponentName("!", res.permission != null
                    ? res.permission : "private to package");
        }

        // 每一个Service组件都使用一个ServiceRecord对象来描述，就像每一个Activity都是用一个ActivityRecord
        // 对象来描述一样
        ServiceRecord r = res.record;
        // 正在启动服务的userId不存在
        if (!mAm.mUserController.exists(r.userId)) {
            Slog.w(TAG, "Trying to start service with non-existent user! " + r.userId);
            return null;
        }

        // If we're starting indirectly (e.g. from PendingIntent), figure out whether
        // we're launching into an app in a background state.  This keys off of the same
        // idleness state tracking as e.g. O+ background service start policy.
        final boolean bgLaunch = !mAm.isUidActiveLocked(r.appInfo.uid);

        // If the app has strict background restrictions, we treat any bg service
        // start analogously to the legacy-app forced-restrictions case, regardless
        // of its target SDK version.
        boolean forcedStandby = false;
        if (bgLaunch && appRestrictedAnyInBackground(r.appInfo.uid, r.packageName)) {
            if (DEBUG_FOREGROUND_SERVICE) {
                Slog.d(TAG, "Forcing bg-only service start only for " + r.shortName
                        + " : bgLaunch=" + bgLaunch + " callerFg=" + callerFg);
            }
            forcedStandby = true;
        }

        // If this is a direct-to-foreground start, make sure it is allowed as per the app op.
        boolean forceSilentAbort = false;
        if (fgRequired) {
            final int mode = mAm.mAppOpsService.checkOperation(
                    AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName);
            switch (mode) {
                case AppOpsManager.MODE_ALLOWED:
                case AppOpsManager.MODE_DEFAULT:
                    // All okay.
                    break;
                case AppOpsManager.MODE_IGNORED:
                    // Not allowed, fall back to normal start service, failing siliently
                    // if background check restricts that.
                    Slog.w(TAG, "startForegroundService not allowed due to app op: service "
                            + service + " to " + r.name.flattenToShortString()
                            + " from pid=" + callingPid + " uid=" + callingUid
                            + " pkg=" + callingPackage);
                    fgRequired = false;
                    forceSilentAbort = true;
                    break;
                default:
                    return new ComponentName("!!", "foreground not allowed as per app op");
            }
        }

        // If this isn't a direct-to-foreground start, check our ability to kick off an
        // arbitrary service
        if (forcedStandby || (!r.startRequested && !fgRequired)) {
            // Before going further -- if this app is not allowed to start services in the
            // background, then at this point we aren't going to let it period.
            final int allowed = mAm.getAppStartModeLocked(r.appInfo.uid, r.packageName,
                    r.appInfo.targetSdkVersion, callingPid, false, false, forcedStandby);
            if (allowed != ActivityManager.APP_START_MODE_NORMAL) {
                Slog.w(TAG, "Background start not allowed: service "
                        + service + " to " + r.name.flattenToShortString()
                        + " from pid=" + callingPid + " uid=" + callingUid
                        + " pkg=" + callingPackage + " startFg?=" + fgRequired);
                if (allowed == ActivityManager.APP_START_MODE_DELAYED || forceSilentAbort) {
                    // In this case we are silently disabling the app, to disrupt as
                    // little as possible existing apps.
                    return null;
                }
                if (forcedStandby) {
                    // This is an O+ app, but we might be here because the user has placed
                    // it under strict background restrictions.  Don't punish the app if it's
                    // trying to do the right thing but we're denying it for that reason.
                    if (fgRequired) {
                        if (DEBUG_BACKGROUND_CHECK) {
                            Slog.v(TAG, "Silently dropping foreground service launch due to FAS");
                        }
                        return null;
                    }
                }
                // This app knows it is in the new model where this operation is not
                // allowed, so tell it what has happened.
                UidRecord uidRec = mAm.mActiveUids.get(r.appInfo.uid);
                return new ComponentName("?", "app is in background uid " + uidRec);
            }
        }

        // At this point we've applied allowed-to-start policy based on whether this was
        // an ordinary startService() or a startForegroundService().  Now, only require that
        // the app follow through on the startForegroundService() -> startForeground()
        // contract if it actually targets O+.
        if (r.appInfo.targetSdkVersion < Build.VERSION_CODES.O && fgRequired) {
            if (DEBUG_BACKGROUND_CHECK || DEBUG_FOREGROUND_SERVICE) {
                Slog.i(TAG, "startForegroundService() but host targets "
                        + r.appInfo.targetSdkVersion + " - not requiring startForeground()");
            }
            fgRequired = false;
        }

        NeededUriGrants neededGrants = mAm.checkGrantUriPermissionFromIntentLocked(
                callingUid, r.packageName, service, service.getFlags(), null, r.userId);

        // If permissions need a review before any of the app components can run,
        // we do not start the service and launch a review activity if the calling app
        // is in the foreground passing it a pending intent to start the service when
        // review is completed.
        if (mAm.mPermissionReviewRequired) {
            // XXX This is not dealing with fgRequired!
            if (!requestStartTargetPermissionsReviewIfNeededLocked(r, callingPackage,
                    callingUid, service, callerFg, userId)) {
                return null;
            }
        }

        if (unscheduleServiceRestartLocked(r, callingUid, false)) {
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "START SERVICE WHILE RESTART PENDING: " + r);
        }
        r.lastActivity = SystemClock.uptimeMillis();
        r.startRequested = true;// 显示启动
        r.delayedStop = false;
        r.fgRequired = fgRequired;
		 // 加入启动服务列表
        r.pendingStarts.add(new ServiceRecord.StartItem(r, false, r.makeNextStartId(),
                service, neededGrants, callingUid));

        if (fgRequired) {
            // We are now effectively running a foreground service.
            mAm.mAppOpsService.startOperation(AppOpsManager.getToken(mAm.mAppOpsService),
                    AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName, true);
        }

        final ServiceMap smap = getServiceMapLocked(r.userId);
        boolean addToStarting = false;
        // 如果是非前台（后台）进程调用
        if (!callerFg && r.app == null
                && mAm.mUserController.hasStartedUserState(r.userId)) {
            // 获取启动服务所在进程
            ProcessRecord proc = mAm.getProcessRecordLocked(r.processName, r.appInfo.uid, false);
            if (proc == null || proc.curProcState > ActivityManager.PROCESS_STATE_RECEIVER) {
                // If this is not coming from a foreground caller, then we may want
                // to delay the start if there are already other background services
                // that are starting.  This is to avoid process start spam when lots
                // of applications are all handling things like connectivity broadcasts.
                // We only do this for cached processes, because otherwise an application
                // can have assumptions about calling startService() for a service to run
                // in its own process, and for that process to not be killed before the
                // service is started.  This is especially the case for receivers, which
                // may start a service in onReceive() to do some additional work and have
                // initialized some global state as part of that.
                if (DEBUG_DELAYED_SERVICE) Slog.v(TAG_SERVICE, "Potential start delay of "
                        + r + " in " + proc);
                if (r.delayed) {// 延迟启动，等待
                    // This service is already scheduled for a delayed start; just leave
                    // it still waiting.
                    if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "Continuing to delay: " + r);
                    return r.name;
                }
                // 如果正在启动的后台服务数量超过允许的最大值，则该服务要加入延迟启动服务队列
                if (smap.mStartingBackground.size() >= mMaxStartingBackground) {
                    // Something else is starting, delay!
                    Slog.i(TAG_SERVICE, "Delaying start of: " + r);
                    smap.mDelayedStartList.add(r);
                    r.delayed = true;
                    return r.name;
                }
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "Not delaying: " + r);
                addToStarting = true;
            } else if (proc.curProcState >= ActivityManager.PROCESS_STATE_SERVICE) {
                // We slightly loosen when we will enqueue this new service as a background
                // starting service we are waiting for, to also include processes that are
                // currently running other services or receivers.
                addToStarting = true;
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE,
                        "Not delaying, but counting as bg: " + r);
            } else if (DEBUG_DELAYED_STARTS) {
                StringBuilder sb = new StringBuilder(128);
                sb.append("Not potential delay (state=").append(proc.curProcState)
                        .append(' ').append(proc.adjType);
                String reason = proc.makeAdjReason();
                if (reason != null) {
                    sb.append(' ');
                    sb.append(reason);
                }
                sb.append("): ");
                sb.append(r.toString());
                Slog.v(TAG_SERVICE, sb.toString());
            }
        } else if (DEBUG_DELAYED_STARTS) {
            if (callerFg || fgRequired) {
                Slog.v(TAG_SERVICE, "Not potential delay (callerFg=" + callerFg + " uid="
                        + callingUid + " pid=" + callingPid + " fgRequired=" + fgRequired + "): " + r);
            } else if (r.app != null) {
                Slog.v(TAG_SERVICE, "Not potential delay (cur app=" + r.app + "): " + r);
            } else {
                Slog.v(TAG_SERVICE,
                        "Not potential delay (user " + r.userId + " not started): " + r);
            }
        }

        ComponentName cmp = startServiceInnerLocked(smap, service, r, callerFg, addToStarting);
        return cmp;
    }

    private boolean requestStartTargetPermissionsReviewIfNeededLocked(ServiceRecord r,
            String callingPackage, int callingUid, Intent service, boolean callerFg,
            final int userId) {
        if (mAm.getPackageManagerInternalLocked().isPermissionsReviewRequired(
                r.packageName, r.userId)) {

            // Show a permission review UI only for starting from a foreground app
            if (!callerFg) {
                Slog.w(TAG, "u" + r.userId + " Starting a service in package"
                        + r.packageName + " requires a permissions review");
                return false;
            }

            IIntentSender target = mAm.getIntentSenderLocked(
                    ActivityManager.INTENT_SENDER_SERVICE, callingPackage,
                    callingUid, userId, null, null, 0, new Intent[]{service},
                    new String[]{service.resolveType(mAm.mContext.getContentResolver())},
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
                            | PendingIntent.FLAG_IMMUTABLE, null);

            final Intent intent = new Intent(Intent.ACTION_REVIEW_PERMISSIONS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, r.packageName);
            intent.putExtra(Intent.EXTRA_INTENT, new IntentSender(target));

            if (DEBUG_PERMISSIONS_REVIEW) {
                Slog.i(TAG, "u" + r.userId + " Launching permission review for package "
                        + r.packageName);
            }

            mAm.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mAm.mContext.startActivityAsUser(intent, new UserHandle(userId));
                }
            });

            return false;
        }

        return  true;
    }

    ComponentName startServiceInnerLocked(ServiceMap smap, Intent service, ServiceRecord r,
                                          boolean callerFg, boolean addToStarting) throws TransactionTooLargeException {
        ServiceState stracker = r.getTracker();// 获取服务状态
        if (stracker != null) {
            stracker.setStarted(true, mAm.mProcessStats.getMemFactorLocked(), r.lastActivity);
        }
        r.callStart = false;// 是否调用onStart方法
        synchronized (r.stats.getBatteryStats()) {
            r.stats.startRunningLocked();// 耗电统计，开始运行
        }

        // 启动ServiceRecord对象r所描述的一个Service组件，即Server组件
        String error = bringUpServiceLocked(r, service.getFlags(), callerFg, false, false);
        if (error != null) {
            return new ComponentName("!!", error);
        }

        if (r.startRequested && addToStarting) {
            boolean first = smap.mStartingBackground.size() == 0;
            smap.mStartingBackground.add(r);
            r.startingBgTimeout = SystemClock.uptimeMillis() + mAm.mConstants.BG_START_TIMEOUT;
            if (DEBUG_DELAYED_SERVICE) {
                RuntimeException here = new RuntimeException("here");
                here.fillInStackTrace();
                Slog.v(TAG_SERVICE, "Starting background (first=" + first + "): " + r, here);
            } else if (DEBUG_DELAYED_STARTS) {
                Slog.v(TAG_SERVICE, "Starting background (first=" + first + "): " + r);
            }
            if (first) {
                smap.rescheduleDelayedStartsLocked();
            }
        } else if (callerFg || r.fgRequired) {
            smap.ensureNotStartingBackgroundLocked(r);
        }

        return r.name;
    }

    private void stopServiceLocked(ServiceRecord service) {
        if (service.delayed) {// 服务还在延迟启动
            // If service isn't actually running, but is is being held in the
            // delayed list, then we need to keep it started but note that it
            // should be stopped once no longer delayed.
            if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "Delaying stop of pending: " + service);
            service.delayedStop = true;// 设置为延迟停止
            return;
        }
        synchronized (service.stats.getBatteryStats()) {
            service.stats.stopRunningLocked();// 停止统计电量
        }
        service.startRequested = false;
        if (service.tracker != null) {
            service.tracker.setStarted(false, mAm.mProcessStats.getMemFactorLocked(),
                    SystemClock.uptimeMillis());
        }
        service.callStart = false;
        bringDownServiceIfNeededLocked(service, false, false);
    }

    int stopServiceLocked(IApplicationThread caller, Intent service,
            String resolvedType, int userId) {
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "stopService: " + service
                + " type=" + resolvedType);

        // 根据caller获取调用者进程
        final ProcessRecord callerApp = mAm.getRecordForAppLocked(caller);
        if (caller != null && callerApp == null) {
            throw new SecurityException(
                    "Unable to find app for caller " + caller
                    + " (pid=" + Binder.getCallingPid()
                    + ") when stopping service " + service);
        }

        // If this service is active, make sure it is stopped.
        ServiceLookupResult r = retrieveServiceLocked(service, resolvedType, null,
                Binder.getCallingPid(), Binder.getCallingUid(), userId, false, false, false, false);
        if (r != null) {
            if (r.record != null) {
                final long origId = Binder.clearCallingIdentity();
                try {
                    stopServiceLocked(r.record);
                } finally {
                    Binder.restoreCallingIdentity(origId);
                }
                return 1;
            }
            return -1;
        }

        return 0;
    }

    void stopInBackgroundLocked(int uid) {
        // Stop all services associated with this uid due to it going to the background
        // stopped state.
        ServiceMap services = mServiceMap.get(UserHandle.getUserId(uid));
        ArrayList<ServiceRecord> stopping = null;
        if (services != null) {
            for (int i=services.mServicesByName.size()-1; i>=0; i--) {
                ServiceRecord service = services.mServicesByName.valueAt(i);
                if (service.appInfo.uid == uid && service.startRequested) {
                    if (mAm.getAppStartModeLocked(service.appInfo.uid, service.packageName,
                            service.appInfo.targetSdkVersion, -1, false, false, false)
                            != ActivityManager.APP_START_MODE_NORMAL) {
                        if (stopping == null) {
                            stopping = new ArrayList<>();
                        }
                        String compName = service.name.flattenToShortString();
                        EventLogTags.writeAmStopIdleService(service.appInfo.uid, compName);
                        StringBuilder sb = new StringBuilder(64);
                        sb.append("Stopping service due to app idle: ");
                        UserHandle.formatUid(sb, service.appInfo.uid);
                        sb.append(" ");
                        TimeUtils.formatDuration(service.createRealTime
                                - SystemClock.elapsedRealtime(), sb);
                        sb.append(" ");
                        sb.append(compName);
                        Slog.w(TAG, sb.toString());
                        stopping.add(service);
                    }
                }
            }
            if (stopping != null) {
                for (int i=stopping.size()-1; i>=0; i--) {
                    ServiceRecord service = stopping.get(i);
                    service.delayed = false;
                    services.ensureNotStartingBackgroundLocked(service);
                    stopServiceLocked(service);
                }
            }
        }
    }

    IBinder peekServiceLocked(Intent service, String resolvedType, String callingPackage) {
        ServiceLookupResult r = retrieveServiceLocked(service, resolvedType, callingPackage,
                Binder.getCallingPid(), Binder.getCallingUid(),
                UserHandle.getCallingUserId(), false, false, false, false);

        IBinder ret = null;
        if (r != null) {
            // r.record is null if findServiceLocked() failed the caller permission check
            if (r.record == null) {
                throw new SecurityException(
                        "Permission Denial: Accessing service"
                        + " from pid=" + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " requires " + r.permission);
            }
            IntentBindRecord ib = r.record.bindings.get(r.record.intent);
            if (ib != null) {
                ret = ib.binder;
            }
        }

        return ret;
    }

    boolean stopServiceTokenLocked(ComponentName className, IBinder token,
            int startId) {
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "stopServiceToken: " + className
                + " " + token + " startId=" + startId);
        ServiceRecord r = findServiceLocked(className, token, UserHandle.getCallingUserId());
        if (r != null) {
            if (startId >= 0) {
                // Asked to only stop if done with all work.  Note that
                // to avoid leaks, we will take this as dropping all
                // start items up to and including this one.
                ServiceRecord.StartItem si = r.findDeliveredStart(startId, false, false);
                if (si != null) {
                    while (r.deliveredStarts.size() > 0) {
                        ServiceRecord.StartItem cur = r.deliveredStarts.remove(0);
                        cur.removeUriPermissionsLocked();
                        if (cur == si) {
                            break;
                        }
                    }
                }

                if (r.getLastStartId() != startId) {
                    return false;
                }

                if (r.deliveredStarts.size() > 0) {
                    Slog.w(TAG, "stopServiceToken startId " + startId
                            + " is last, but have " + r.deliveredStarts.size()
                            + " remaining args");
                }
            }

            synchronized (r.stats.getBatteryStats()) {
                r.stats.stopRunningLocked();
            }
            r.startRequested = false;
            if (r.tracker != null) {
                r.tracker.setStarted(false, mAm.mProcessStats.getMemFactorLocked(),
                        SystemClock.uptimeMillis());
            }
            r.callStart = false;
            final long origId = Binder.clearCallingIdentity();
            bringDownServiceIfNeededLocked(r, false, false);
            Binder.restoreCallingIdentity(origId);
            return true;
        }
        return false;
    }

    public void setServiceForegroundLocked(ComponentName className, IBinder token,
            int id, Notification notification, int flags) {
        final int userId = UserHandle.getCallingUserId();
        final long origId = Binder.clearCallingIdentity();
        try {
            ServiceRecord r = findServiceLocked(className, token, userId);
            if (r != null) {
                setServiceForegroundInnerLocked(r, id, notification, flags);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    boolean foregroundAppShownEnoughLocked(ActiveForegroundApp aa, long nowElapsed) {
        if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "Shown enough: pkg=" + aa.mPackageName + ", uid="
                + aa.mUid);
        boolean canRemove = false;
        aa.mHideTime = Long.MAX_VALUE;
        if (aa.mShownWhileTop) {
            // If the app was ever at the top of the screen while the foreground
            // service was running, then we can always just immediately remove it.
            canRemove = true;
            if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "YES - shown while on top");
        } else if (mScreenOn || aa.mShownWhileScreenOn) {
            final long minTime = aa.mStartVisibleTime
                    + (aa.mStartTime != aa.mStartVisibleTime
                            ? mAm.mConstants.FGSERVICE_SCREEN_ON_AFTER_TIME
                            : mAm.mConstants.FGSERVICE_MIN_SHOWN_TIME);
            if (nowElapsed >= minTime) {
                // If shown while the screen is on, and it has been shown for
                // at least the minimum show time, then we can now remove it.
                if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "YES - shown long enough with screen on");
                canRemove = true;
            } else {
                // This is when we will be okay to stop telling the user.
                long reportTime = nowElapsed + mAm.mConstants.FGSERVICE_MIN_REPORT_TIME;
                aa.mHideTime = reportTime > minTime ? reportTime : minTime;
                if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "NO -- wait " + (aa.mHideTime-nowElapsed)
                        + " with screen on");
            }
        } else {
            final long minTime = aa.mEndTime
                    + mAm.mConstants.FGSERVICE_SCREEN_ON_BEFORE_TIME;
            if (nowElapsed >= minTime) {
                // If the foreground service has only run while the screen is
                // off, but it has been gone now for long enough that we won't
                // care to tell the user about it when the screen comes back on,
                // then we can remove it now.
                if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "YES - gone long enough with screen off");
                canRemove = true;
            } else {
                // This is when we won't care about this old fg service.
                aa.mHideTime = minTime;
                if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "NO -- wait " + (aa.mHideTime-nowElapsed)
                        + " with screen off");
            }
        }
        return canRemove;
    }

    void updateForegroundApps(ServiceMap smap) {
        // This is called from the handler without the lock held.
        ArrayList<ActiveForegroundApp> active = null;
        synchronized (mAm) {
            final long now = SystemClock.elapsedRealtime();
            long nextUpdateTime = Long.MAX_VALUE;
            if (smap != null) {
                if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "Updating foreground apps for user "
                        + smap.mUserId);
                for (int i = smap.mActiveForegroundApps.size()-1; i >= 0; i--) {
                    ActiveForegroundApp aa = smap.mActiveForegroundApps.valueAt(i);
                    if (aa.mEndTime != 0) {
                        boolean canRemove = foregroundAppShownEnoughLocked(aa, now);
                        if (canRemove) {
                            // This was up for longer than the timeout, so just remove immediately.
                            smap.mActiveForegroundApps.removeAt(i);
                            smap.mActiveForegroundAppsChanged = true;
                            continue;
                        }
                        if (aa.mHideTime < nextUpdateTime) {
                            nextUpdateTime = aa.mHideTime;
                        }
                    }
                    if (!aa.mAppOnTop) {
                        if (active == null) {
                            active = new ArrayList<>();
                        }
                        if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "Adding active: pkg="
                                + aa.mPackageName + ", uid=" + aa.mUid);
                        active.add(aa);
                    }
                }
                smap.removeMessages(ServiceMap.MSG_UPDATE_FOREGROUND_APPS);
                if (nextUpdateTime < Long.MAX_VALUE) {
                    if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "Next update time in: "
                            + (nextUpdateTime-now));
                    Message msg = smap.obtainMessage(ServiceMap.MSG_UPDATE_FOREGROUND_APPS);
                    smap.sendMessageAtTime(msg, nextUpdateTime
                            + SystemClock.uptimeMillis() - SystemClock.elapsedRealtime());
                }
            }
            if (!smap.mActiveForegroundAppsChanged) {
                return;
            }
            smap.mActiveForegroundAppsChanged = false;
        }

        if (!SHOW_DUNGEON_NOTIFICATION) {
            return;
        }

        final NotificationManager nm = (NotificationManager) mAm.mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);
        final Context context = mAm.mContext;

        if (active != null) {
            for (int i = 0; i < active.size(); i++) {
                ActiveForegroundApp aa = active.get(i);
                if (aa.mLabel == null) {
                    PackageManager pm = context.getPackageManager();
                    try {
                        ApplicationInfo ai = pm.getApplicationInfoAsUser(aa.mPackageName,
                                PackageManager.MATCH_KNOWN_PACKAGES, smap.mUserId);
                        aa.mLabel = ai.loadLabel(pm);
                    } catch (PackageManager.NameNotFoundException e) {
                        aa.mLabel = aa.mPackageName;
                    }
                }
            }

            Intent intent;
            String title;
            String msg;
            String[] pkgs;
            final long nowElapsed = SystemClock.elapsedRealtime();
            long oldestStartTime = nowElapsed;
            if (active.size() == 1) {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.fromParts("package", active.get(0).mPackageName, null));
                title = context.getString(
                        R.string.foreground_service_app_in_background, active.get(0).mLabel);
                msg = context.getString(R.string.foreground_service_tap_for_details);
                pkgs = new String[] { active.get(0).mPackageName };
                oldestStartTime = active.get(0).mStartTime;
            } else {
                intent = new Intent(Settings.ACTION_FOREGROUND_SERVICES_SETTINGS);
                pkgs = new String[active.size()];
                for (int i = 0; i < active.size(); i++) {
                    pkgs[i] = active.get(i).mPackageName;
                    oldestStartTime = Math.min(oldestStartTime, active.get(i).mStartTime);
                }
                intent.putExtra("packages", pkgs);
                title = context.getString(
                        R.string.foreground_service_apps_in_background, active.size());
                msg = active.get(0).mLabel.toString();
                for (int i = 1; i < active.size(); i++) {
                    msg = context.getString(R.string.foreground_service_multiple_separator,
                            msg, active.get(i).mLabel);
                }
            }
            Bundle notificationBundle = new Bundle();
            notificationBundle.putStringArray(Notification.EXTRA_FOREGROUND_APPS, pkgs);
            Notification.Builder n =
                    new Notification.Builder(context,
                            SystemNotificationChannels.FOREGROUND_SERVICE)
                            .addExtras(notificationBundle)
                            .setSmallIcon(R.drawable.stat_sys_vitals)
                            .setOngoing(true)
                            .setShowWhen(oldestStartTime < nowElapsed)
                            .setWhen(System.currentTimeMillis() - (nowElapsed - oldestStartTime))
                            .setColor(context.getColor(
                                    com.android.internal.R.color.system_notification_accent_color))
                            .setContentTitle(title)
                            .setContentText(msg)
                            .setContentIntent(
                                    PendingIntent.getActivityAsUser(context, 0, intent,
                                            PendingIntent.FLAG_UPDATE_CURRENT,
                                            null, new UserHandle(smap.mUserId)));
            nm.notifyAsUser(null, SystemMessageProto.SystemMessage.NOTE_FOREGROUND_SERVICES,
                    n.build(), new UserHandle(smap.mUserId));
        } else {
            nm.cancelAsUser(null, SystemMessageProto.SystemMessage.NOTE_FOREGROUND_SERVICES,
                    new UserHandle(smap.mUserId));
        }
    }

    private void requestUpdateActiveForegroundAppsLocked(ServiceMap smap, long timeElapsed) {
        Message msg = smap.obtainMessage(ServiceMap.MSG_UPDATE_FOREGROUND_APPS);
        if (timeElapsed != 0) {
            smap.sendMessageAtTime(msg,
                    timeElapsed + SystemClock.uptimeMillis() - SystemClock.elapsedRealtime());
        } else {
            smap.mActiveForegroundAppsChanged = true;
            smap.sendMessage(msg);
        }
    }

    private void decActiveForegroundAppLocked(ServiceMap smap, ServiceRecord r) {
        ActiveForegroundApp active = smap.mActiveForegroundApps.get(r.packageName);
        if (active != null) {
            active.mNumActive--;
            if (active.mNumActive <= 0) {
                active.mEndTime = SystemClock.elapsedRealtime();
                if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "Ended running of service");
                if (foregroundAppShownEnoughLocked(active, active.mEndTime)) {
                    // Have been active for long enough that we will remove it immediately.
                    smap.mActiveForegroundApps.remove(r.packageName);
                    smap.mActiveForegroundAppsChanged = true;
                    requestUpdateActiveForegroundAppsLocked(smap, 0);
                } else if (active.mHideTime < Long.MAX_VALUE){
                    requestUpdateActiveForegroundAppsLocked(smap, active.mHideTime);
                }
            }
        }
    }

    void updateScreenStateLocked(boolean screenOn) {
        if (mScreenOn != screenOn) {
            mScreenOn = screenOn;

            // If screen is turning on, then we now reset the start time of any foreground
            // services that were started while the screen was off.
            if (screenOn) {
                final long nowElapsed = SystemClock.elapsedRealtime();
                if (DEBUG_FOREGROUND_SERVICE) Slog.d(TAG, "Screen turned on");
                for (int i = mServiceMap.size()-1; i >= 0; i--) {
                    ServiceMap smap = mServiceMap.valueAt(i);
                    long nextUpdateTime = Long.MAX_VALUE;
                    boolean changed = false;
                    for (int j = smap.mActiveForegroundApps.size()-1; j >= 0; j--) {
                        ActiveForegroundApp active = smap.mActiveForegroundApps.valueAt(j);
                        if (active.mEndTime == 0) {
                            if (!active.mShownWhileScreenOn) {
                                active.mShownWhileScreenOn = true;
                                active.mStartVisibleTime = nowElapsed;
                            }
                        } else {
                            if (!active.mShownWhileScreenOn
                                    && active.mStartVisibleTime == active.mStartTime) {
                                // If this was never shown while the screen was on, then we will
                                // count the time it started being visible as now, to tell the user
                                // about it now that they have a screen to look at.
                                active.mEndTime = active.mStartVisibleTime = nowElapsed;
                            }
                            if (foregroundAppShownEnoughLocked(active, nowElapsed)) {
                                // Have been active for long enough that we will remove it
                                // immediately.
                                smap.mActiveForegroundApps.remove(active.mPackageName);
                                smap.mActiveForegroundAppsChanged = true;
                                changed = true;
                            } else {
                                if (active.mHideTime < nextUpdateTime) {
                                    nextUpdateTime = active.mHideTime;
                                }
                            }
                        }
                    }
                    if (changed) {
                        // Need to immediately update.
                        requestUpdateActiveForegroundAppsLocked(smap, 0);
                    } else if (nextUpdateTime < Long.MAX_VALUE) {
                        requestUpdateActiveForegroundAppsLocked(smap, nextUpdateTime);
                    }
                }
            }
        }
    }

    void foregroundServiceProcStateChangedLocked(UidRecord uidRec) {
        ServiceMap smap = mServiceMap.get(UserHandle.getUserId(uidRec.uid));
        if (smap != null) {
            boolean changed = false;
            for (int j = smap.mActiveForegroundApps.size()-1; j >= 0; j--) {
                ActiveForegroundApp active = smap.mActiveForegroundApps.valueAt(j);
                if (active.mUid == uidRec.uid) {
                    if (uidRec.curProcState <= ActivityManager.PROCESS_STATE_TOP) {
                        if (!active.mAppOnTop) {
                            active.mAppOnTop = true;
                            changed = true;
                        }
                        active.mShownWhileTop = true;
                    } else if (active.mAppOnTop) {
                        active.mAppOnTop = false;
                        changed = true;
                    }
                }
            }
            if (changed) {
                requestUpdateActiveForegroundAppsLocked(smap, 0);
            }
        }
    }

    /**
     * @param id Notification ID.  Zero === exit foreground state for the given service.
     */
    private void setServiceForegroundInnerLocked(final ServiceRecord r, int id,
            Notification notification, int flags) {
        if (id != 0) {
            if (notification == null) {
                throw new IllegalArgumentException("null notification");
            }
            // Instant apps need permission to create foreground services.
            if (r.appInfo.isInstantApp()) {
                final int mode = mAm.mAppOpsService.checkOperation(
                        AppOpsManager.OP_INSTANT_APP_START_FOREGROUND,
                        r.appInfo.uid,
                        r.appInfo.packageName);
                switch (mode) {
                    case AppOpsManager.MODE_ALLOWED:
                        break;
                    case AppOpsManager.MODE_IGNORED:
                        Slog.w(TAG, "Instant app " + r.appInfo.packageName
                                + " does not have permission to create foreground services"
                                + ", ignoring.");
                        return;
                    case AppOpsManager.MODE_ERRORED:
                        throw new SecurityException("Instant app " + r.appInfo.packageName
                                + " does not have permission to create foreground services");
                    default:
                        mAm.enforcePermission(
                                android.Manifest.permission.INSTANT_APP_FOREGROUND_SERVICE,
                                r.app.pid, r.appInfo.uid, "startForeground");
                }
            } else if (r.appInfo.targetSdkVersion >= Build.VERSION_CODES.P) {
                mAm.enforcePermission(
                        android.Manifest.permission.FOREGROUND_SERVICE,
                        r.app.pid, r.appInfo.uid, "startForeground");
            }
            boolean alreadyStartedOp = false;
            if (r.fgRequired) {
                if (DEBUG_SERVICE || DEBUG_BACKGROUND_CHECK) {
                    Slog.i(TAG, "Service called startForeground() as required: " + r);
                }
                r.fgRequired = false;
                r.fgWaiting = false;
                alreadyStartedOp = true;
                mAm.mHandler.removeMessages(
                        ActivityManagerService.SERVICE_FOREGROUND_TIMEOUT_MSG, r);
            }

            try {
                boolean ignoreForeground = false;
                final int mode = mAm.mAppOpsService.checkOperation(
                        AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName);
                switch (mode) {
                    case AppOpsManager.MODE_ALLOWED:
                    case AppOpsManager.MODE_DEFAULT:
                        // All okay.
                        break;
                    case AppOpsManager.MODE_IGNORED:
                        // Whoops, silently ignore this.
                        Slog.w(TAG, "Service.startForeground() not allowed due to app op: service "
                                + r.shortName);
                        ignoreForeground = true;
                        break;
                    default:
                        throw new SecurityException("Foreground not allowed as per app op");
                }

                if (!ignoreForeground &&
                        appRestrictedAnyInBackground(r.appInfo.uid, r.packageName)) {
                    Slog.w(TAG,
                            "Service.startForeground() not allowed due to bg restriction: service "
                            + r.shortName);
                    // Back off of any foreground expectations around this service, since we've
                    // just turned down its fg request.
                    updateServiceForegroundLocked(r.app, false);
                    ignoreForeground = true;
                }

                // Apps under strict background restrictions simply don't get to have foreground
                // services, so now that we've enforced the startForegroundService() contract
                // we only do the machinery of making the service foreground when the app
                // is not restricted.
                if (!ignoreForeground) {
                    if (r.foregroundId != id) {
                        cancelForegroundNotificationLocked(r);
                        r.foregroundId = id;
                    }
                    notification.flags |= Notification.FLAG_FOREGROUND_SERVICE;
                    r.foregroundNoti = notification;
                    if (!r.isForeground) {
                        final ServiceMap smap = getServiceMapLocked(r.userId);
                        if (smap != null) {
                            ActiveForegroundApp active = smap.mActiveForegroundApps.get(r.packageName);
                            if (active == null) {
                                active = new ActiveForegroundApp();
                                active.mPackageName = r.packageName;
                                active.mUid = r.appInfo.uid;
                                active.mShownWhileScreenOn = mScreenOn;
                                if (r.app != null) {
                                    active.mAppOnTop = active.mShownWhileTop =
                                            r.app.uidRecord.curProcState
                                                    <= ActivityManager.PROCESS_STATE_TOP;
                                }
                                active.mStartTime = active.mStartVisibleTime
                                        = SystemClock.elapsedRealtime();
                                smap.mActiveForegroundApps.put(r.packageName, active);
                                requestUpdateActiveForegroundAppsLocked(smap, 0);
                            }
                            active.mNumActive++;
                        }
                        r.isForeground = true;
                        mAm.mAppOpsService.startOperation(
                                AppOpsManager.getToken(mAm.mAppOpsService),
                                AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName,
                                true);
                        StatsLog.write(StatsLog.FOREGROUND_SERVICE_STATE_CHANGED,
                                r.appInfo.uid, r.shortName,
                                StatsLog.FOREGROUND_SERVICE_STATE_CHANGED__STATE__ENTER);
                    }
                    r.postNotification();
                    if (r.app != null) {
                        updateServiceForegroundLocked(r.app, true);
                    }
                    getServiceMapLocked(r.userId).ensureNotStartingBackgroundLocked(r);
                    mAm.notifyPackageUse(r.serviceInfo.packageName,
                            PackageManager.NOTIFY_PACKAGE_USE_FOREGROUND_SERVICE);
                } else {
                    if (DEBUG_FOREGROUND_SERVICE) {
                        Slog.d(TAG, "Suppressing startForeground() for FAS " + r);
                    }
                }
            } finally {
                if (alreadyStartedOp) {
                    // If we had previously done a start op for direct foreground start,
                    // we have cleared the flag so can now drop it.
                    mAm.mAppOpsService.finishOperation(
                            AppOpsManager.getToken(mAm.mAppOpsService),
                            AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName);
                }
            }
        } else {
            if (r.isForeground) {
                final ServiceMap smap = getServiceMapLocked(r.userId);
                if (smap != null) {
                    decActiveForegroundAppLocked(smap, r);
                }
                r.isForeground = false;
                mAm.mAppOpsService.finishOperation(
                        AppOpsManager.getToken(mAm.mAppOpsService),
                        AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName);
                StatsLog.write(StatsLog.FOREGROUND_SERVICE_STATE_CHANGED,
                        r.appInfo.uid, r.shortName,
                        StatsLog.FOREGROUND_SERVICE_STATE_CHANGED__STATE__EXIT);
                if (r.app != null) {
                    mAm.updateLruProcessLocked(r.app, false, null);
                    updateServiceForegroundLocked(r.app, true);
                }
            }
            if ((flags & Service.STOP_FOREGROUND_REMOVE) != 0) {
                cancelForegroundNotificationLocked(r);
                r.foregroundId = 0;
                r.foregroundNoti = null;
            } else if (r.appInfo.targetSdkVersion >= Build.VERSION_CODES.LOLLIPOP) {
                r.stripForegroundServiceFlagFromNotification();
                if ((flags & Service.STOP_FOREGROUND_DETACH) != 0) {
                    r.foregroundId = 0;
                    r.foregroundNoti = null;
                }
            }
        }
    }

    private void cancelForegroundNotificationLocked(ServiceRecord r) {
        if (r.foregroundId != 0) {
            // First check to see if this app has any other active foreground services
            // with the same notification ID.  If so, we shouldn't actually cancel it,
            // because that would wipe away the notification that still needs to be shown
            // due the other service.
            ServiceMap sm = getServiceMapLocked(r.userId);
            if (sm != null) {
                for (int i = sm.mServicesByName.size()-1; i >= 0; i--) {
                    ServiceRecord other = sm.mServicesByName.valueAt(i);
                    if (other != r && other.foregroundId == r.foregroundId
                            && other.packageName.equals(r.packageName)) {
                        // Found one!  Abort the cancel.
                        return;
                    }
                }
            }
            r.cancelNotification();
        }
    }

    private void updateServiceForegroundLocked(ProcessRecord proc, boolean oomAdj) {
        boolean anyForeground = false;
        for (int i=proc.services.size()-1; i>=0; i--) {
            ServiceRecord sr = proc.services.valueAt(i);
            if (sr.isForeground || sr.fgRequired) {
                anyForeground = true;
                break;
            }
        }
        mAm.updateProcessForegroundLocked(proc, anyForeground, oomAdj);
    }

    private void updateWhitelistManagerLocked(ProcessRecord proc) {
        proc.whitelistManager = false;
        for (int i=proc.services.size()-1; i>=0; i--) {
            ServiceRecord sr = proc.services.valueAt(i);
            if (sr.whitelistManager) {
                proc.whitelistManager = true;
                break;
            }
        }
    }

    public void updateServiceConnectionActivitiesLocked(ProcessRecord clientProc) {
        ArraySet<ProcessRecord> updatedProcesses = null;
        for (int i = 0; i < clientProc.connections.size(); i++) {
            final ConnectionRecord conn = clientProc.connections.valueAt(i);
            final ProcessRecord proc = conn.binding.service.app;
            if (proc == null || proc == clientProc) {
                continue;
            } else if (updatedProcesses == null) {
                updatedProcesses = new ArraySet<>();
            } else if (updatedProcesses.contains(proc)) {
                continue;
            }
            updatedProcesses.add(proc);
            updateServiceClientActivitiesLocked(proc, null, false);
        }
    }

    private boolean updateServiceClientActivitiesLocked(ProcessRecord proc,
            ConnectionRecord modCr, boolean updateLru) {
        if (modCr != null && modCr.binding.client != null) {
            if (modCr.binding.client.activities.size() <= 0) {
                // This connection is from a client without activities, so adding
                // and removing is not interesting.
                return false;
            }
        }

        boolean anyClientActivities = false;
        for (int i=proc.services.size()-1; i>=0 && !anyClientActivities; i--) {
            ServiceRecord sr = proc.services.valueAt(i);
            for (int conni=sr.connections.size()-1; conni>=0 && !anyClientActivities; conni--) {
                ArrayList<ConnectionRecord> clist = sr.connections.valueAt(conni);
                for (int cri=clist.size()-1; cri>=0; cri--) {
                    ConnectionRecord cr = clist.get(cri);
                    if (cr.binding.client == null || cr.binding.client == proc) {
                        // Binding to ourself is not interesting.
                        continue;
                    }
                    if (cr.binding.client.activities.size() > 0) {
                        anyClientActivities = true;
                        break;
                    }
                }
            }
        }
        if (anyClientActivities != proc.hasClientActivities) {
            proc.hasClientActivities = anyClientActivities;
            if (updateLru) {
                mAm.updateLruProcessLocked(proc, anyClientActivities, null);
            }
            return true;
        }
        return false;
    }

    int bindServiceLocked(IApplicationThread caller, IBinder token, Intent service,
            String resolvedType, final IServiceConnection connection, int flags,
            String callingPackage, final int userId) throws TransactionTooLargeException {
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "bindService: " + service
                + " type=" + resolvedType + " conn=" + connection.asBinder()
                + " flags=0x" + Integer.toHexString(flags));
        // 根据caller来获取一个ProcessRecord对象callerApp用来描述AMS执行绑定Service组件操作的一个Activity
        // 组件所运行在的应用程序进程
        final ProcessRecord callerApp = mAm.getRecordForAppLocked(caller);
        if (callerApp == null) {
            throw new SecurityException(
                    "Unable to find app for caller " + caller
                    + " (pid=" + Binder.getCallingPid()
                    + ") when binding service " + service);
        }

        ActivityRecord activity = null;
        if (token != null) {
            // 通过token来获得一个ActivityRecord对象activity，用来描述正在请求AMS执行绑定Service组件
            // 操作的一个Activity组件
            activity = ActivityRecord.isInStackLocked(token);
            if (activity == null) {
                Slog.w(TAG, "Binding with unknown activity: " + token);
                return 0;
            }
        }

        int clientLabel = 0;
        PendingIntent clientIntent = null;
        final boolean isCallerSystem = callerApp.info.uid == Process.SYSTEM_UID;

        if (isCallerSystem) {// 系统启动的服务
            // Hacky kind of thing -- allow system stuff to tell us
            // what they are, so we can report this elsewhere for
            // others to know why certain services are running.
            service.setDefusable(true);
            clientIntent = service.getParcelableExtra(Intent.EXTRA_CLIENT_INTENT);
            if (clientIntent != null) {
                clientLabel = service.getIntExtra(Intent.EXTRA_CLIENT_LABEL, 0);
                if (clientLabel != 0) {
                    // There are no useful extras in the intent, trash them.
                    // System code calling with this stuff just needs to know
                    // this will happen.
                    service = service.cloneFilter();
                }
            }
        }

        if ((flags&Context.BIND_TREAT_LIKE_ACTIVITY) != 0) {
            mAm.enforceCallingPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS,
                    "BIND_TREAT_LIKE_ACTIVITY");
        }

        if ((flags & Context.BIND_ALLOW_WHITELIST_MANAGEMENT) != 0 && !isCallerSystem) {
            throw new SecurityException(
                    "Non-system caller " + caller + " (pid=" + Binder.getCallingPid()
                    + ") set BIND_ALLOW_WHITELIST_MANAGEMENT when binding service " + service);
        }

        if ((flags & Context.BIND_ALLOW_INSTANT) != 0 && !isCallerSystem) {
            throw new SecurityException(
                    "Non-system caller " + caller + " (pid=" + Binder.getCallingPid()
                            + ") set BIND_ALLOW_INSTANT when binding service " + service);
        }

        final boolean callerFg = callerApp.setSchedGroup != ProcessList.SCHED_GROUP_BACKGROUND;
        final boolean isBindExternal = (flags & Context.BIND_EXTERNAL_SERVICE) != 0;
        final boolean allowInstant = (flags & Context.BIND_ALLOW_INSTANT) != 0;
        // 根据参数service来得到一个ServiceRecord对象s，用来描述即将被绑定的Service组件
        ServiceLookupResult res =
            retrieveServiceLocked(service, resolvedType, callingPackage, Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, true, callerFg, isBindExternal, allowInstant);
        // 没有对应的服务
        if (res == null) {
            return 0;
        }
        if (res.record == null) {
            return -1;
        }
        ServiceRecord s = res.record;

        boolean permissionsReviewRequired = false;

        // If permissions need a review before any of the app components can run,
        // we schedule binding to the service but do not start its process, then
        // we launch a review activity to which is passed a callback to invoke
        // when done to start the bound service's process to completing the binding.
        if (mAm.mPermissionReviewRequired) {
            if (mAm.getPackageManagerInternalLocked().isPermissionsReviewRequired(
                    s.packageName, s.userId)) {

                permissionsReviewRequired = true;

                // Show a permission review UI only for binding from a foreground app
                if (!callerFg) {
                    Slog.w(TAG, "u" + s.userId + " Binding to a service in package"
                            + s.packageName + " requires a permissions review");
                    return 0;
                }

                final ServiceRecord serviceRecord = s;
                final Intent serviceIntent = service;

                RemoteCallback callback = new RemoteCallback(
                        new RemoteCallback.OnResultListener() {
                    @Override
                    public void onResult(Bundle result) {
                        synchronized(mAm) {
                            final long identity = Binder.clearCallingIdentity();
                            try {
                                if (!mPendingServices.contains(serviceRecord)) {
                                    return;
                                }
                                // If there is still a pending record, then the service
                                // binding request is still valid, so hook them up. We
                                // proceed only if the caller cleared the review requirement
                                // otherwise we unbind because the user didn't approve.
                                if (!mAm.getPackageManagerInternalLocked()
                                        .isPermissionsReviewRequired(
                                                serviceRecord.packageName,
                                                serviceRecord.userId)) {
                                    try {
                                        bringUpServiceLocked(serviceRecord,
                                                serviceIntent.getFlags(),
                                                callerFg, false, false);
                                    } catch (RemoteException e) {
                                        /* ignore - local call */
                                    }
                                } else {
                                    unbindServiceLocked(connection);
                                }
                            } finally {
                                Binder.restoreCallingIdentity(identity);
                            }
                        }
                    }
                });

                final Intent intent = new Intent(Intent.ACTION_REVIEW_PERMISSIONS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                intent.putExtra(Intent.EXTRA_PACKAGE_NAME, s.packageName);
                intent.putExtra(Intent.EXTRA_REMOTE_CALLBACK, callback);

                if (DEBUG_PERMISSIONS_REVIEW) {
                    Slog.i(TAG, "u" + s.userId + " Launching permission review for package "
                            + s.packageName);
                }

                mAm.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mAm.mContext.startActivityAsUser(intent, new UserHandle(userId));
                    }
                });
            }
        }

        final long origId = Binder.clearCallingIdentity();

        try {
            if (unscheduleServiceRestartLocked(s, callerApp.info.uid, false)) {
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "BIND SERVICE WHILE RESTART PENDING: "
                        + s);
            }

            if ((flags&Context.BIND_AUTO_CREATE) != 0) {
                s.lastActivity = SystemClock.uptimeMillis();
                if (!s.hasAutoCreateConnections()) {
                    // This is the first binding, let the tracker know.
                    ServiceState stracker = s.getTracker();
                    if (stracker != null) {
                        stracker.setBound(true, mAm.mProcessStats.getMemFactorLocked(),
                                s.lastActivity);
                    }
                }
            }

            mAm.startAssociationLocked(callerApp.uid, callerApp.processName, callerApp.curProcState,
                    s.appInfo.uid, s.name, s.processName);
            // Once the apps have become associated, if one of them is caller is ephemeral
            
            mAm.grantEphemeralAccessLocked(callerApp.userId, service,
                    s.appInfo.uid, UserHandle.getAppId(callerApp.uid));

			// 调用ServiceRecord对象s的成员函数retrieveAppBindingLocked来得到一个AppBindRecord对象b，
			// 表示ServiceRecord对象s所描述的Service组件是绑定在ProcessRecord对象callerApp所描述的一个
            // 应用程序进程中的
            AppBindRecord b = s.retrieveAppBindingLocked(service, callerApp);
            // 将前面获得的APPBindRecord对象、ActivityRecord对象Activity以及参数connection封装成一个
            // ConnectionRecord对象s所描述的一个Service组件，并且这个Activity组件是运行在
            // ProcessRecord对象callerApp所描述的一个应用进程中的
            ConnectionRecord c = new ConnectionRecord(b, activity,
                    connection, flags, clientLabel, clientIntent);

            // 由于一个Service组件可能会被同一个应用程序进程中的多个Activity组件使用同一个InnerConnection
            // 对象来绑定，因此，在AMS中，用来描述该Service组件的ServiceRecord对象就有可能会对应有多个
            // ConnectionRecord对象。在这种情况下，这些ConnectionRecord对象就会被保存在一个列表中。
            // 这个列表最终会保存在对应的ServiceRecord对象的成员变量Connection所描述的HashMap中，并且以
            // 它里面的ConnectionRecord对象共同使用的一个InnerConnection代理对象的IBinder接口为关键字

            // 参数connection是一个InnerConnection代理对象，因此可以获取它的一个IBinder接口binder
            IBinder binder = connection.asBinder();
            // 检测在ServiceRecord对象s中是否存在一个以IBinder接口binder为关键字的列表clist，如果不存在
            // 创建一个，并且将clist以binder为关键字放到ServiceRecord对象成员变量connections中
            ArrayList<ConnectionRecord> clist = s.connections.get(binder);
            if (clist == null) {// 没有绑定过
                clist = new ArrayList<ConnectionRecord>();
                s.connections.put(binder, clist);
            }
            clist.add(c);// 添加绑定列表
            b.connections.add(c);// 添加到关联应用和服务的对象AppBindRecord中记录绑定列表中
            if (activity != null) {
                if (activity.connections == null) {// 这里判断Activity里面是否绑定过服务
                    activity.connections = new HashSet<ConnectionRecord>();
                }
                // 添加到描述Activity的ActivityRecord对象中记录绑定服务的列表中
                activity.connections.add(c);
            }
            b.client.connections.add(c);
            if ((c.flags&Context.BIND_ABOVE_CLIENT) != 0) {
                b.client.hasAboveClient = true;
            }
            if ((c.flags&Context.BIND_ALLOW_WHITELIST_MANAGEMENT) != 0) {
                s.whitelistManager = true;
            }
            if (s.app != null) {
                updateServiceClientActivitiesLocked(s.app, c, true);
            }
            // 从记录该服务所有绑定列表中获取是否存在绑定列表
            clist = mServiceConnections.get(binder);
            if (clist == null) {
                clist = new ArrayList<ConnectionRecord>();
                mServiceConnections.put(binder, clist);
            }
            clist.add(c);// 添加都所有列表中

            // 从前面可知flags的Context.BIND_AUTO_CREATE位等于1，因此会调用bringUpServiceLocked来启动
            // ServiceRecord对象s所描述的一个Service组件，等到这个Service组件启动以后，AMS再将它与
            // ActivityRecord对象Activity所描述的一个Activity绑定自来
            if ((flags & Context.BIND_AUTO_CREATE) != 0) {
                s.lastActivity = SystemClock.uptimeMillis();
                if (bringUpServiceLocked(s, service.getFlags(), callerFg, false,
                        permissionsReviewRequired) != null) {
                    return 0;
                }
            }

            if (s.app != null) {
                if ((flags&Context.BIND_TREAT_LIKE_ACTIVITY) != 0) {
                    s.app.treatLikeActivity = true;
                }
                if (s.whitelistManager) {
                    s.app.whitelistManager = true;
                }
                // This could have made the service more important.
                mAm.updateLruProcessLocked(s.app, s.app.hasClientActivities
                        || s.app.treatLikeActivity, b.client);
                mAm.updateOomAdjLocked(s.app, true);
            }

            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Bind " + s + " with " + b
                    + ": received=" + b.intent.received
                    + " apps=" + b.intent.apps.size()
                    + " doRebind=" + b.intent.doRebind);

            if (s.app != null && b.intent.received) {
                // Service is already running, so we can immediately
                // publish the connection.
                try {
                    // 这里的c.conn是ServiceDispatcher.InnerConnection对象，这里最终调用
                    // ServiceDispatcher中的doConnected方法
                    c.conn.connected(s.name, b.intent.binder, false);
                } catch (Exception e) {
                    Slog.w(TAG, "Failure sending service " + s.shortName
                            + " to connection " + c.conn.asBinder()
                            + " (in " + c.binding.client.processName + ")", e);
                }

                // If this is the first app connected back to this binding,
                // and the service had previously asked to be told when
                // rebound, then do so.
                if (b.intent.apps.size() == 1 && b.intent.doRebind) {// 重新绑定
                    requestServiceBindingLocked(s, b.intent, callerFg, true);
                }
            } else if (!b.intent.requested) {// 首次绑定
                requestServiceBindingLocked(s, b.intent, callerFg, false);
            }

            getServiceMapLocked(s.userId).ensureNotStartingBackgroundLocked(s);

        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        return 1;
    }

    void publishServiceLocked(ServiceRecord r, Intent intent, IBinder service) {
        final long origId = Binder.clearCallingIdentity();
        try {
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "PUBLISHING " + r
                    + " " + intent + ": " + service);
            if (r != null) {
                Intent.FilterComparison filter
                        = new Intent.FilterComparison(intent);
                // 根据Intent在ServiceRecord对象r的成员变量bindings中获得一个IntentBindRecord对象b，
                // 它的成员变量received用来描述ServiceRecord对象r所描述的Service组件是否已经将其内部的
                // 一个Binder本地对象传递给了AMS，如果没有传递它的值为false
                IntentBindRecord b = r.bindings.get(filter);
                if (b != null && !b.received) {
                    // 将这个本地Binder对象即参数service指向的一个Binder本地对象保存在IntentBindRecord
                    // 对象b的成员变量binder中
                    b.binder = service;
                    b.requested = true;// 避免重复请求
                    b.received = true;// 避免重复请求
                    // 每一个需要绑定ServiceRecord对象r所描述的Service组件的Activity组件都使用一个
                    // ConnectionRecord对象来绑定ServiceRecord对象r所描述的Service组件，因此AMS就会把
                    // 这些使用了同一个InnerConnection对象的ConnectionRecord对象放在同一个列表中。
                    // 这样AMS就会得到与ServiceRecord对象r相关的一系列ConnectionRecord对象列表，它们
                    // 最终保存在ServiceRecord对象r的成员变量connection所描述的一个HashMap中并且以它们
                    // 所使用的InnerConnection对象为关键字
                    for (int conni = r.connections.size() - 1; conni >= 0; conni--) {
                        ArrayList<ConnectionRecord> clist = r.connections.valueAt(conni);
                        // 循环遍历clist列表
                        for (int i = 0; i < clist.size(); i++) {
                            ConnectionRecord c = clist.get(i);
                            if (!filter.equals(c.binding.intent.intent)) {
                                if (DEBUG_SERVICE) Slog.v(
                                        TAG_SERVICE, "Not publishing to: " + c);
                                if (DEBUG_SERVICE) Slog.v(
                                        TAG_SERVICE, "Bound intent: " + c.binding.intent.intent);
                                if (DEBUG_SERVICE) Slog.v(
                                        TAG_SERVICE, "Published intent: " + intent);
                                continue;
                            }
                            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Publishing to: " + c);
                            try {
                                // 将参数service描述的一个Binder本地对象传递给与它们关联的Activity组件，
                                // 以便这些Activity组件可以通过这个Binder本地对象来获得它们索要绑定的
                                // Service组件的访问接口
                                c.conn.connected(r.name, service, false);
                                // ConnectionRecord类的成员变量conn是一个类型为IServiceConnection的
                                // Binder代理对象，它引用了一个类型为InnerConnection的Binder本地对象。
                                // 这个Binder本地对象是用来连接一个Service组件和一个Activity组件的，并
                                // 且与这个Activity组件运行在同一个应用程序进程中。
                            } catch (Exception e) {
                                Slog.w(TAG, "Failure sending service " + r.name +
                                      " to connection " + c.conn.asBinder() +
                                      " (in " + c.binding.client.processName + ")", e);
                            }
                        }
                    }
                }

                serviceDoneExecutingLocked(r, mDestroyingServices.contains(r), false);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    boolean unbindServiceLocked(IServiceConnection connection) {
        IBinder binder = connection.asBinder();
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "unbindService: conn=" + binder);
        // 获取缓存中绑定列表
        ArrayList<ConnectionRecord> clist = mServiceConnections.get(binder);
        if (clist == null) {
            Slog.w(TAG, "Unbind failed: could not find connection for "
                  + connection.asBinder());
            return false;
        }

        final long origId = Binder.clearCallingIdentity();
        try {
            while (clist.size() > 0) {
                ConnectionRecord r = clist.get(0);
                removeConnectionLocked(r, null, null);
                if (clist.size() > 0 && clist.get(0) == r) {
                    // In case it didn't get removed above, do it now.
                    Slog.wtf(TAG, "Connection " + r + " not removed for binder " + binder);
                    clist.remove(0);
                }

                if (r.binding.service.app != null) {
                    if (r.binding.service.app.whitelistManager) {
                        updateWhitelistManagerLocked(r.binding.service.app);
                    }
                    // This could have made the service less important.
                    if ((r.flags&Context.BIND_TREAT_LIKE_ACTIVITY) != 0) {
                        r.binding.service.app.treatLikeActivity = true;
                        mAm.updateLruProcessLocked(r.binding.service.app,
                                r.binding.service.app.hasClientActivities
                                || r.binding.service.app.treatLikeActivity, null);
                    }
                    mAm.updateOomAdjLocked(r.binding.service.app, false);
                }
            }

            mAm.updateOomAdjLocked();

        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        return true;
    }

    // 解绑完成
    void unbindFinishedLocked(ServiceRecord r, Intent intent, boolean doRebind) {
        final long origId = Binder.clearCallingIdentity();
        try {
            if (r != null) {
                Intent.FilterComparison filter
                        = new Intent.FilterComparison(intent);
                IntentBindRecord b = r.bindings.get(filter);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "unbindFinished in " + r
                        + " at " + b + ": apps="
                        + (b != null ? b.apps.size() : 0));

                boolean inDestroying = mDestroyingServices.contains(r);
                if (b != null) {
                    if (b.apps.size() > 0 && !inDestroying) {
                        // Applications have already bound since the last
                        // unbind, so just rebind right here.
                        boolean inFg = false;
                        for (int i=b.apps.size()-1; i>=0; i--) {
                            ProcessRecord client = b.apps.valueAt(i).client;
                            if (client != null && client.setSchedGroup
                                    != ProcessList.SCHED_GROUP_BACKGROUND) {
                                inFg = true;
                                break;
                            }
                        }
                        try {
                            // 请求绑定操作
                            requestServiceBindingLocked(r, b, inFg, true);
                        } catch (TransactionTooLargeException e) {
                            // Don't pass this back to ActivityThread, it's unrelated.
                        }
                    } else {
                        // Note to tell the service the next time there is
                        // a new client.
                        b.doRebind = true;
                    }
                }

                // 执行完成操作
                serviceDoneExecutingLocked(r, inDestroying, false);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    private final ServiceRecord findServiceLocked(ComponentName name,
            IBinder token, int userId) {
        ServiceRecord r = getServiceByNameLocked(name, userId);
        return r == token ? r : null;
    }

    private final class ServiceLookupResult {
        final ServiceRecord record;
        final String permission;

        ServiceLookupResult(ServiceRecord _record, String _permission) {
            record = _record;
            permission = _permission;
        }
    }

    private class ServiceRestarter implements Runnable {
        private ServiceRecord mService;

        void setService(ServiceRecord service) {
            mService = service;
        }

        public void run() {
            synchronized(mAm) {
                performServiceRestartLocked(mService);
            }
        }
    }

    // 在AMS中查找是否存在参数为service对应的ServiceRecord对象，如果不存在，那么AMS就会到
    // PackageManagerService中去获取与这个参数service对应的一个Service组件的信息，然后将这些信息封装成
    // 一个ServiceRecord对象，最后将这个ServiceRecord对象封装成ServiceLookupResult对象返回给调用者
    private ServiceLookupResult retrieveServiceLocked(Intent service,
            String resolvedType, String callingPackage, int callingPid, int callingUid, int userId,
            boolean createIfNeeded, boolean callingFromFg, boolean isBindExternal,
            boolean allowInstant) {
        ServiceRecord r = null;
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "retrieveServiceLocked: " + service
                + " type=" + resolvedType + " callingUid=" + callingUid);

        // 获取调用者的userId
        userId = mAm.mUserController.handleIncomingUser(callingPid, callingUid, userId, false,
                ActivityManagerService.ALLOW_NON_FULL_IN_PROFILE, "service", null);

        // 根据userId获取ServiceMap（Handler）
        ServiceMap smap = getServiceMapLocked(userId);
        // 被启动服务的组件名
        final ComponentName comp = service.getComponent();
        if (comp != null) {
            // 根据组件获取ServiceRecord
            r = smap.mServicesByName.get(comp);
            if (DEBUG_SERVICE && r != null) Slog.v(TAG_SERVICE, "Retrieved by component: " + r);
        }
        if (r == null && !isBindExternal) {// 没有要启动服务的描述对象，并且不是绑定的外部服务
            Intent.FilterComparison filter = new Intent.FilterComparison(service);
            r = smap.mServicesByIntent.get(filter);
            if (DEBUG_SERVICE && r != null) Slog.v(TAG_SERVICE, "Retrieved by intent: " + r);
        }
        // FLAG_EXTERNAL_SERVICE:表示服务绑定并运行在调用者的包内，而不是声明该服务的包内
        if (r != null && (r.serviceInfo.flags & ServiceInfo.FLAG_EXTERNAL_SERVICE) != 0
                && !callingPackage.equals(r.packageName)) {// 调用者包名和服务包名不同
            // If an external service is running within its own package, other packages
            // should not bind to that instance.
            r = null;
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Whoops, can't use existing external service");
        }
        if (r == null) {
            try {
                int flags = ActivityManagerService.STOCK_PM_FLAGS
                        | PackageManager.MATCH_DEBUG_TRIAGED_MISSING;
                if (allowInstant) {
                    flags |= PackageManager.MATCH_INSTANT;
                }
                // TODO: come back and remove this assumption to triage all services
                ResolveInfo rInfo = mAm.getPackageManagerInternalLocked().resolveService(service,
                        resolvedType, flags, userId, callingUid);
				// 服务信息（AndroidManifest.xml中的Service信息收集）
                ServiceInfo sInfo =
                    rInfo != null ? rInfo.serviceInfo : null;
                if (sInfo == null) {
                    Slog.w(TAG_SERVICE, "Unable to start service " + service + " U=" + userId +
                          ": not found");
                    return null;
                }
                ComponentName name = new ComponentName(
                        sInfo.applicationInfo.packageName, sInfo.name);
                // 如果是允许外部运行的服务
                if ((sInfo.flags & ServiceInfo.FLAG_EXTERNAL_SERVICE) != 0) {
                    if (isBindExternal) {// 如果是允许外部绑定的服务
                        if (!sInfo.exported) {// 如果服务不允许被其他应用使用，抛出异常
                            throw new SecurityException("BIND_EXTERNAL_SERVICE failed, " + name +
                                    " is not exported");
                        }
                        // 如果服务不是独立进程，抛出异常
                        if ((sInfo.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) == 0) {
                            throw new SecurityException("BIND_EXTERNAL_SERVICE failed, " + name +
                                    " is not an isolatedProcess");
                        }
                        // Run the service under the calling package's application.
                        // 在调用者应用下运行该服务
                        ApplicationInfo aInfo = AppGlobals.getPackageManager().getApplicationInfo(
                                callingPackage, ActivityManagerService.STOCK_PM_FLAGS, userId);
                        if (aInfo == null) {// 调用者应用不存在
                            throw new SecurityException("BIND_EXTERNAL_SERVICE failed, " +
                                    "could not resolve client package " + callingPackage);
                        }
                        // 初始化数据
                        sInfo = new ServiceInfo(sInfo);
                        sInfo.applicationInfo = new ApplicationInfo(sInfo.applicationInfo);
                        sInfo.applicationInfo.packageName = aInfo.packageName;
                        sInfo.applicationInfo.uid = aInfo.uid;
                        name = new ComponentName(aInfo.packageName, name.getClassName());
                        service.setComponent(name);
                    } else {
                        throw new SecurityException("BIND_EXTERNAL_SERVICE required for " +
                                name);
                    }
                } else if (isBindExternal) {// 如果是不允许外部绑定的服务，那么要外部绑定就要抛出异常
                    throw new SecurityException("BIND_EXTERNAL_SERVICE failed, " + name +
                            " is not an externalService");
                }
                if (userId > 0) {
                    // 单例模式
                    if (mAm.isSingleton(sInfo.processName, sInfo.applicationInfo,
                            sInfo.name, sInfo.flags)
                            && mAm.isValidSingletonCall(callingUid, sInfo.applicationInfo.uid)) {
                        userId = 0;
                        smap = getServiceMapLocked(0);
                    }
                    sInfo = new ServiceInfo(sInfo);
                    sInfo.applicationInfo = mAm.getAppInfoForUser(sInfo.applicationInfo, userId);
                }
                r = smap.mServicesByName.get(name);
                if (DEBUG_SERVICE && r != null) Slog.v(TAG_SERVICE,
                        "Retrieved via pm by intent: " + r);
                if (r == null && createIfNeeded) {// ServiceRecord是空的并且需要创建新的
                    final Intent.FilterComparison filter
                            = new Intent.FilterComparison(service.cloneFilter());
                    final ServiceRestarter res = new ServiceRestarter();
                    final BatteryStatsImpl.Uid.Pkg.Serv ss;
                    final BatteryStatsImpl stats = mAm.mBatteryStatsService.getActiveStatistics();
                    synchronized (stats) {
                        ss = stats.getServiceStatsLocked(
                                sInfo.applicationInfo.uid, sInfo.packageName,
                                sInfo.name);
                    }
                    r = new ServiceRecord(mAm, ss, name, filter, sInfo, callingFromFg, res);
                    res.setService(r);
                    // 保存服务描述对象到列表中
                    smap.mServicesByName.put(name, r);
                    smap.mServicesByIntent.put(filter, r);

                    // Make sure this component isn't in the pending list.
                    // 如果要启动的服务在等待列表中，将其从等待列表中移除
                    for (int i=mPendingServices.size()-1; i>=0; i--) {
                        final ServiceRecord pr = mPendingServices.get(i);
                        if (pr.serviceInfo.applicationInfo.uid == sInfo.applicationInfo.uid
                                && pr.name.equals(name)) {
                            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Remove pending: " + pr);
                            mPendingServices.remove(i);
                        }
                    }
                    if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Retrieve created new service: " + r);
                }
            } catch (RemoteException ex) {
                // pm is in same process, this will never happen.
            }
        }
        if (r != null) {
            if (mAm.checkComponentPermission(r.permission,
                    callingPid, callingUid, r.appInfo.uid, r.exported) != PERMISSION_GRANTED) {
                if (!r.exported) {
                    Slog.w(TAG, "Permission Denial: Accessing service " + r.name
                            + " from pid=" + callingPid
                            + ", uid=" + callingUid
                            + " that is not exported from uid " + r.appInfo.uid);
                    return new ServiceLookupResult(null, "not exported from uid "
                            + r.appInfo.uid);
                }
                Slog.w(TAG, "Permission Denial: Accessing service " + r.name
                        + " from pid=" + callingPid
                        + ", uid=" + callingUid
                        + " requires " + r.permission);
                return new ServiceLookupResult(null, r.permission);
            } else if (r.permission != null && callingPackage != null) {
                final int opCode = AppOpsManager.permissionToOpCode(r.permission);
                if (opCode != AppOpsManager.OP_NONE && mAm.mAppOpsService.noteOperation(
                        opCode, callingUid, callingPackage) != AppOpsManager.MODE_ALLOWED) {
                    Slog.w(TAG, "Appop Denial: Accessing service " + r.name
                            + " from pid=" + callingPid
                            + ", uid=" + callingUid
                            + " requires appop " + AppOpsManager.opToName(opCode));
                    return null;
                }
            }

            if (!mAm.mIntentFirewall.checkService(r.name, service, callingUid, callingPid,
                    resolvedType, r.appInfo)) {
                return null;
            }
            return new ServiceLookupResult(r, null);
        }
        return null;
    }

    private final void bumpServiceExecutingLocked(ServiceRecord r, boolean fg, String why) {
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, ">>> EXECUTING "
                + why + " of " + r + " in app " + r.app);
        else if (DEBUG_SERVICE_EXECUTING) Slog.v(TAG_SERVICE_EXECUTING, ">>> EXECUTING "
                + why + " of " + r.shortName);

        // For b/34123235: Services within the system server won't start until SystemServer
        // does Looper.loop(), so we shouldn't try to start/bind to them too early in the boot
        // process. However, since there's a little point of showing the ANR dialog in that case,
        // let's suppress the timeout until PHASE_THIRD_PARTY_APPS_CAN_START.
        //
        // (Note there are multiple services start at PHASE_THIRD_PARTY_APPS_CAN_START too,
        // which technically could also trigger this timeout if there's a system server
        // that takes a long time to handle PHASE_THIRD_PARTY_APPS_CAN_START, but that shouldn't
        // happen.)
        boolean timeoutNeeded = true;
        if ((mAm.mBootPhase < SystemService.PHASE_THIRD_PARTY_APPS_CAN_START)
                && (r.app != null) && (r.app.pid == android.os.Process.myPid())) {

            Slog.w(TAG, "Too early to start/bind service in system_server: Phase=" + mAm.mBootPhase
                    + " " + r.getComponentName());
            timeoutNeeded = false;
        }

        long now = SystemClock.uptimeMillis();// 获取并记录当前时间，用来判断超时
        if (r.executeNesting == 0) {
            r.executeFg = fg;
            ServiceState stracker = r.getTracker();
            if (stracker != null) {
                stracker.setExecuting(true, mAm.mProcessStats.getMemFactorLocked(), now);
            }
            if (r.app != null) {// 服务进程存在
                r.app.executingServices.add(r);
                r.app.execServicesFg |= fg;
                if (timeoutNeeded && r.app.executingServices.size() == 1) {
                    // 执行服务超时
                    scheduleServiceTimeoutLocked(r.app);
                }
            }
        } else if (r.app != null && fg && !r.app.execServicesFg) {
            r.app.execServicesFg = true;
            if (timeoutNeeded) {
                scheduleServiceTimeoutLocked(r.app);
            }
        }
        r.executeFg |= fg;
        r.executeNesting++;
        r.executingStart = now;
    }

    // 参数rebind表示是否需要将ServiceRecord对象r所描述的Service组件重新绑定到IntentBindRecord对象i
    // 所描述的应用程序进程中如果为false，则说明为第一绑定
    private final boolean requestServiceBindingLocked(ServiceRecord r, IntentBindRecord i,
            boolean execInFg, boolean rebind) throws TransactionTooLargeException {
        if (r.app == null || r.app.thread == null) {
            // If service is not currently running, can't yet bind.
            return false;
        }
        if (DEBUG_SERVICE) Slog.d(TAG_SERVICE, "requestBind " + i + ": requested=" + i.requested
                + " rebind=" + rebind);
		// 检查AMS是否已经为IntentBindRecord对象i所描述的应用程序进程请求过ServiceRecord对象r所描述的
        // Service组件返回其内部的一个Binder本地对象。如果还没有请求requested为false并且apps的数量大于0
        if ((!i.requested || rebind) && i.apps.size() > 0) {
            try {
                bumpServiceExecutingLocked(r, execInFg, "bind");
                r.app.forceProcessStateUpTo(ActivityManager.PROCESS_STATE_SERVICE);
                // 会执行到Service的onBind方法
                r.app.thread.scheduleBindService(r, i.intent.getIntent(), rebind,
                        r.app.repProcState);
                if (!rebind) {
                    // 设置为true，防止重复请求
                    i.requested = true;
                }
                i.hasBound = true;
                i.doRebind = false;
            } catch (TransactionTooLargeException e) {
                // Keep the executeNesting count accurate.
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Crashed while binding " + r, e);
                final boolean inDestroying = mDestroyingServices.contains(r);
                serviceDoneExecutingLocked(r, inDestroying, inDestroying);
                throw e;
            } catch (RemoteException e) {
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Crashed while binding " + r);
                // Keep the executeNesting count accurate.
                final boolean inDestroying = mDestroyingServices.contains(r);
                serviceDoneExecutingLocked(r, inDestroying, inDestroying);
                return false;
            }
        }
        return true;
    }

    private final boolean scheduleServiceRestartLocked(ServiceRecord r, boolean allowCancel) {
        boolean canceled = false;

        if (mAm.isShuttingDownLocked()) {
            Slog.w(TAG, "Not scheduling restart of crashed service " + r.shortName
                    + " - system is shutting down");
            return false;
        }

        ServiceMap smap = getServiceMapLocked(r.userId);
        if (smap.mServicesByName.get(r.name) != r) {
            ServiceRecord cur = smap.mServicesByName.get(r.name);
            Slog.wtf(TAG, "Attempting to schedule restart of " + r
                    + " when found in map: " + cur);
            return false;
        }

        final long now = SystemClock.uptimeMillis();

        if ((r.serviceInfo.applicationInfo.flags
                &ApplicationInfo.FLAG_PERSISTENT) == 0) {
            long minDuration = mAm.mConstants.SERVICE_RESTART_DURATION;
            long resetTime = mAm.mConstants.SERVICE_RESET_RUN_DURATION;

            // Any delivered but not yet finished starts should be put back
            // on the pending list.
            final int N = r.deliveredStarts.size();
            if (N > 0) {
                for (int i=N-1; i>=0; i--) {
                    ServiceRecord.StartItem si = r.deliveredStarts.get(i);
                    si.removeUriPermissionsLocked();
                    if (si.intent == null) {
                        // We'll generate this again if needed.
                    } else if (!allowCancel || (si.deliveryCount < ServiceRecord.MAX_DELIVERY_COUNT
                            && si.doneExecutingCount < ServiceRecord.MAX_DONE_EXECUTING_COUNT)) {
                        r.pendingStarts.add(0, si);
                        long dur = SystemClock.uptimeMillis() - si.deliveredTime;
                        dur *= 2;
                        if (minDuration < dur) minDuration = dur;
                        if (resetTime < dur) resetTime = dur;
                    } else {
                        Slog.w(TAG, "Canceling start item " + si.intent + " in service "
                                + r.name);
                        canceled = true;
                    }
                }
                r.deliveredStarts.clear();
            }

            r.totalRestartCount++;
            if (r.restartDelay == 0) {
                r.restartCount++;
                r.restartDelay = minDuration;
            } else if (r.crashCount > 1) {
                r.restartDelay = mAm.mConstants.BOUND_SERVICE_CRASH_RESTART_DURATION
                        * (r.crashCount - 1);
            } else {
                // If it has been a "reasonably long time" since the service
                // was started, then reset our restart duration back to
                // the beginning, so we don't infinitely increase the duration
                // on a service that just occasionally gets killed (which is
                // a normal case, due to process being killed to reclaim memory).
                if (now > (r.restartTime+resetTime)) {
                    r.restartCount = 1;
                    r.restartDelay = minDuration;
                } else {
                    r.restartDelay *= mAm.mConstants.SERVICE_RESTART_DURATION_FACTOR;
                    if (r.restartDelay < minDuration) {
                        r.restartDelay = minDuration;
                    }
                }
            }

            r.nextRestartTime = now + r.restartDelay;

            // Make sure that we don't end up restarting a bunch of services
            // all at the same time.
            boolean repeat;
            do {
                repeat = false;
                final long restartTimeBetween = mAm.mConstants.SERVICE_MIN_RESTART_TIME_BETWEEN;
                for (int i=mRestartingServices.size()-1; i>=0; i--) {
                    ServiceRecord r2 = mRestartingServices.get(i);
                    if (r2 != r && r.nextRestartTime >= (r2.nextRestartTime-restartTimeBetween)
                            && r.nextRestartTime < (r2.nextRestartTime+restartTimeBetween)) {
                        r.nextRestartTime = r2.nextRestartTime + restartTimeBetween;
                        r.restartDelay = r.nextRestartTime - now;
                        repeat = true;
                        break;
                    }
                }
            } while (repeat);

        } else {
            // Persistent processes are immediately restarted, so there is no
            // reason to hold of on restarting their services.
            r.totalRestartCount++;
            r.restartCount = 0;
            r.restartDelay = 0;
            r.nextRestartTime = now;
        }

        if (!mRestartingServices.contains(r)) {
            r.createdFromFg = false;
            mRestartingServices.add(r);
            r.makeRestarting(mAm.mProcessStats.getMemFactorLocked(), now);
        }

        cancelForegroundNotificationLocked(r);

        mAm.mHandler.removeCallbacks(r.restarter);
        mAm.mHandler.postAtTime(r.restarter, r.nextRestartTime);
        r.nextRestartTime = SystemClock.uptimeMillis() + r.restartDelay;
        Slog.w(TAG, "Scheduling restart of crashed service "
                + r.shortName + " in " + r.restartDelay + "ms");
        EventLog.writeEvent(EventLogTags.AM_SCHEDULE_SERVICE_RESTART,
                r.userId, r.shortName, r.restartDelay);

        return canceled;
    }

    final void performServiceRestartLocked(ServiceRecord r) {
        if (!mRestartingServices.contains(r)) {
            return;
        }
        if (!isServiceNeededLocked(r, false, false)) {
            // Paranoia: is this service actually needed?  In theory a service that is not
            // needed should never remain on the restart list.  In practice...  well, there
            // have been bugs where this happens, and bad things happen because the process
            // ends up just being cached, so quickly killed, then restarted again and again.
            // Let's not let that happen.
            Slog.wtf(TAG, "Restarting service that is not needed: " + r);
            return;
        }
        try {
            bringUpServiceLocked(r, r.intent.getIntent().getFlags(), r.createdFromFg, true, false);
        } catch (TransactionTooLargeException e) {
            // Ignore, it's been logged and nothing upstack cares.
        }
    }

    // 取消重启服务
    private final boolean unscheduleServiceRestartLocked(ServiceRecord r, int callingUid,
            boolean force) {
        if (!force && r.restartDelay == 0) {
            return false;
        }
        // Remove from the restarting list; if the service is currently on the
        // restarting list, or the call is coming from another app, then this
        // service has become of much more interest so we reset the restart interval.
        boolean removed = mRestartingServices.remove(r);// 等待重启列表中存在则移除
        if (removed || callingUid != r.appInfo.uid) {
            r.resetRestartCounter();
        }
        if (removed) {
            clearRestartingIfNeededLocked(r);
        }
        mAm.mHandler.removeCallbacks(r.restarter);
        return true;
    }

    private void clearRestartingIfNeededLocked(ServiceRecord r) {
        if (r.restartTracker != null) {
            // If this is the last restarting record with this tracker, then clear
            // the tracker's restarting state.
            boolean stillTracking = false;
            for (int i=mRestartingServices.size()-1; i>=0; i--) {
                if (mRestartingServices.get(i).restartTracker == r.restartTracker) {
                    stillTracking = true;
                    break;
                }
            }
            if (!stillTracking) {
                r.restartTracker.setRestarting(false, mAm.mProcessStats.getMemFactorLocked(),
                        SystemClock.uptimeMillis());
                r.restartTracker = null;
            }
        }
    }

    private String bringUpServiceLocked(ServiceRecord r, int intentFlags, boolean execInFg,
            boolean whileRestarting, boolean permissionsReviewRequired)
            throws TransactionTooLargeException {
        //Slog.i(TAG, "Bring up service:");
        //r.dump("  ");

        // 这里的r.app.thread是一个ApplicationThread对象，ApplicationThread是用来AMS和应用进程通信的工具，
        // 如果服务中的这个thread不为空说明已经和该Service存在通信了，也就是说已经启动了该服务了。
        // 如果服务已经存在，调用startService的时候会执行Service.onStartCommand，
        // 只有首次启动服务才会调用onCreate方法
        if (r.app != null && r.app.thread != null) {
            // 执行Service.onStartCommand方法过程
            sendServiceArgsLocked(r, execInFg, false);
            return null;
        }

        if (!whileRestarting && mRestartingServices.contains(r)) {// 需要延迟重启并且还没有执行重启，等待
            // If waiting for a restart, then do nothing.
            return null;
        }

        if (DEBUG_SERVICE) {
            Slog.v(TAG_SERVICE, "Bringing up " + r + " " + r.intent + " fg=" + r.fgRequired);
        }

        // We are now bringing the service up, so no longer in the
        // restarting state.
        if (mRestartingServices.remove(r)) {// 从重启服务列表中移除正在启动的服务
            clearRestartingIfNeededLocked(r);
        }

        // Make sure this service is no longer considered delayed, we are starting it now.
        // 如果正在启动的服务是延迟服务，需要将延迟服务启动，并且将延迟设置为false
        if (r.delayed) {
            if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "REM FR DELAY LIST (bring up): " + r);
            getServiceMapLocked(r.userId).mDelayedStartList.remove(r);
            r.delayed = false;
        }

        // Make sure that the user who owns this service is started.  If not,
        // we don't want to allow it to run.
        // 确保正在启动服务的用户已经启动，否则不允许执行
        if (!mAm.mUserController.hasStartedUserState(r.userId)) {
            String msg = "Unable to launch app "
                    + r.appInfo.packageName + "/"
                    + r.appInfo.uid + " for service "
                    + r.intent.getIntent() + ": user " + r.userId + " is stopped";
            Slog.w(TAG, msg);
            bringDownServiceLocked(r);
            return msg;
        }

        // Service is now being launched, its package can't be stopped.
        // 如果服务正在被启动，应用不能被停止
        try {
            AppGlobals.getPackageManager().setPackageStoppedState(
                    r.packageName, false, r.userId);
        } catch (RemoteException e) {
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Failed trying to unstop package "
                    + r.packageName + ": " + e);
        }

        // 是不是独立进程
        final boolean isolated = (r.serviceInfo.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0;
        // 首先获取ServiceRecord对象r所描述的Service组件的android:process属性，并保存在procName中
        final String procName = r.processName;
        String hostingType = "service";
        ProcessRecord app;

        if (!isolated) {// 要启动的服务不是独立进程
            // 如果不是独立进程，通过进程名称和uid查找是否已经存在一个对应的ProcessRecord对象app，如果存在，
            // 说明用来运行这个Service组件的应用进程已经存在了，因此下面的realStartServiceLocked函数在
            // ProcessRecord对象app所描述的应用程序进程中启动这个Service组件。
            app = mAm.getProcessRecordLocked(procName, r.appInfo.uid, false);
            if (DEBUG_MU) Slog.v(TAG_MU, "bringUpServiceLocked: appInfo.uid=" + r.appInfo.uid
                        + " app=" + app);
            if (app != null && app.thread != null) {// 进程存在，并且该进程已经与AMS通信过，那么直接启动服务
                try {
                    app.addPackage(r.appInfo.packageName, r.appInfo.longVersionCode, mAm.mProcessStats);
                    // 启动服务
                    realStartServiceLocked(r, app, execInFg);
                    return null;
                } catch (TransactionTooLargeException e) {
                    throw e;
                } catch (RemoteException e) {
                    Slog.w(TAG, "Exception when starting service " + r.shortName, e);
                }

                // If a dead object exception was thrown -- fall through to
                // restart the application.
            }
        } else {// 如果要启动的进程是独立进程
            // If this service runs in an isolated process, then each time
            // we call startProcessLocked() we will get a new isolated
            // process, starting another process if we are currently waiting
            // for a previous process to come up.  To deal with this, we store
            // in the service any current isolated process it is running in or
            // waiting to have come up.
            app = r.isolatedProc;
            if (WebViewZygote.isMultiprocessEnabled()
                    && r.serviceInfo.packageName.equals(WebViewZygote.getPackageName())) {
                hostingType = "webview_service";
            }
        }

        // Not running -- get it started, and enqueue this service record
        // to be executed when the app comes up.
        // 如果要启动的Service所在进程没有启动
        if (app == null && !permissionsReviewRequired) {
            // 启动Service所需要的进程
            if ((app=mAm.startProcessLocked(procName, r.appInfo, true, intentFlags,
                    hostingType, r.name, false, isolated, false)) == null) {
                String msg = "Unable to launch app "
                        + r.appInfo.packageName + "/"
                        + r.appInfo.uid + " for service "
                        + r.intent.getIntent() + ": process is bad";
                Slog.w(TAG, msg);
                // 启动失败
                bringDownServiceLocked(r);
                return msg;
            }
            if (isolated) {
                r.isolatedProc = app;
            }
        }

        if (r.fgRequired) {
            if (DEBUG_FOREGROUND_SERVICE) {
                Slog.v(TAG, "Whitelisting " + UserHandle.formatUid(r.appInfo.uid)
                        + " for fg-service launch");
            }
            mAm.tempWhitelistUidLocked(r.appInfo.uid,
                    SERVICE_START_FOREGROUND_TIMEOUT, "fg-service-launch");
        }

        if (!mPendingServices.contains(r)) {
            // 将ServiceRecord对象保存在mPendingServices列表中，表示它描述的一个Service组件正等待启动
            mPendingServices.add(r);
        }

        if (r.delayedStop) {// 如果是延迟停止的服务
            // Oh and hey we've already been asked to stop!
            r.delayedStop = false;
            if (r.startRequested) {
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE,
                        "Applying delayed stop (in bring up): " + r);
                // 停止服务
                stopServiceLocked(r);
            }
        }

        return null;
    }

    // 参数r指向一个ServiceRecord，表示一个已经启动的Service组件
    private final void requestServiceBindingsLocked(ServiceRecord r, boolean execInFg)
            throws TransactionTooLargeException {
        for (int i=r.bindings.size()-1; i>=0; i--) {
            // 每一个IntentBindRecord对象都用来描述若干个需要将ServiceRecord对象r所描述的Service组件
            // 绑定到它们里面的应用程序进程
            IntentBindRecord ibr = r.bindings.valueAt(i);
            if (!requestServiceBindingLocked(r, ibr, execInFg, false)) {
                break;
            }
        }
    }

    private final void realStartServiceLocked(ServiceRecord r,
            ProcessRecord app, boolean execInFg) throws RemoteException {
        if (app.thread == null) {
            throw new RemoteException();
        }
        if (DEBUG_MU)
            Slog.v(TAG_MU, "realStartServiceLocked, ServiceRecord.uid = " + r.appInfo.uid
                    + ", ProcessRecord.uid = " + app.uid);
        // 将ServiceRecord对象r的成员变量app设置为ProcessRecord对象app，表示ServiceRecord对象r所描述的
        // Service组件是在ProcessRecord对象app所描述的应用程序进程中启动的
        r.app = app;
        r.restartTime = r.lastActivity = SystemClock.uptimeMillis();

        // 将ServiceRecord对象r添加到ProcessRecord对象app的成员变量services中，表示ServiceRecord对象r
        // 所描述的Service组件是在ProcessRecord独享app所描述的应用进程中启动的。ProcessRecord对象
        // app的成员变量thread是一个类型为ApplicationThreadProxy的Binder代理对象，它指向了ProcessRecord
        // 对象app所描述的应用程序进程中的一个ApplicationThread对象
        final boolean newService = app.services.add(r);
        // 发送delay消息
        bumpServiceExecutingLocked(r, execInFg, "create");
        mAm.updateLruProcessLocked(app, false, null);
        updateServiceForegroundLocked(r.app, /* oomAdj= */ false);
        mAm.updateOomAdjLocked();

        boolean created = false;
        try {
            if (LOG_SERVICE_START_STOP) {
                String nameTerm;
                int lastPeriod = r.shortName.lastIndexOf('.');
                nameTerm = lastPeriod >= 0 ? r.shortName.substring(lastPeriod) : r.shortName;
                EventLogTags.writeAmCreateService(
                        r.userId, System.identityHashCode(r), nameTerm, r.app.uid, r.app.pid);
            }
            synchronized (r.stats.getBatteryStats()) {
                r.stats.startLaunchedLocked();
            }
            mAm.notifyPackageUse(r.serviceInfo.packageName,
                    PackageManager.NOTIFY_PACKAGE_USE_SERVICE);
            app.forceProcessStateUpTo(ActivityManager.PROCESS_STATE_SERVICE);
            // 发送信息到主线程，准备调用Service.onCreate方法
            // 请求ProcessRecord对象app描述的应用程序进程将ServiceRecord独享r所描述的Service组件启动起来。
            // ServiceRecord对象r所描述的Service组件启动完成之后，AMS就需要将它连接到一个请求绑定它的一个
            // Activity组件中，这是通过调用AMS类的另一个成员函数requestServiceBindingLocked来实现的
            app.thread.scheduleCreateService(r, r.serviceInfo,
                    mAm.compatibilityInfoForPackageLocked(r.serviceInfo.applicationInfo),
                    app.repProcState);
            r.postNotification();
            created = true;
        } catch (DeadObjectException e) {
            Slog.w(TAG, "Application dead when creating service " + r);
            mAm.appDiedLocked(app);// 应用死亡
            throw e;
        } finally {
            if (!created) {// 没有创建
                // Keep the executeNesting count accurate.
                final boolean inDestroying = mDestroyingServices.contains(r);
                serviceDoneExecutingLocked(r, inDestroying, inDestroying);

                // Cleanup.
                if (newService) {
                    app.services.remove(r);
                    r.app = null;
                }

                // Retry.
                if (!inDestroying) {// 没有正在销毁则尝试重新启动
                    scheduleServiceRestartLocked(r, false);
                }
            }
        }

        if (r.whitelistManager) {
            app.whitelistManager = true;
        }

        // 准备调用Service.onBind方法
        requestServiceBindingsLocked(r, execInFg);

        // 服务已启动，更新服务端进程信息
        updateServiceClientActivitiesLocked(app, null, true);

        // If the service is in the started state, and there are no
        // pending arguments, then fake up one so its onStartCommand() will
        // be called.
        if (r.startRequested && r.callStart && r.pendingStarts.size() == 0) {
            r.pendingStarts.add(new ServiceRecord.StartItem(r, false, r.makeNextStartId(),
                    null, null, 0));
        }

        // 准备调用Service.onStartCommand方法
        sendServiceArgsLocked(r, execInFg, true);

        if (r.delayed) {// 如果正在启动的服务是延迟的，改变其标签，不在延迟
            if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE, "REM FR DELAY LIST (new proc): " + r);
            getServiceMapLocked(r.userId).mDelayedStartList.remove(r);
            r.delayed = false;
        }

        if (r.delayedStop) {// 如果正在启动的是延迟停止的，改变标签，并且如果正在请求停止的话，停止服务
            // Oh and hey we've already been asked to stop!
            r.delayedStop = false;
            if (r.startRequested) {
                if (DEBUG_DELAYED_STARTS) Slog.v(TAG_SERVICE,
                        "Applying delayed stop (from start): " + r);
                stopServiceLocked(r);
            }
        }
    }

    private final void sendServiceArgsLocked(ServiceRecord r, boolean execInFg,
            boolean oomAdjusted) throws TransactionTooLargeException {
        // 等待启动服务个数
        final int N = r.pendingStarts.size();
        if (N == 0) {
            return;
        }

        ArrayList<ServiceStartArgs> args = new ArrayList<>();

        while (r.pendingStarts.size() > 0) {
                // 取出等待启动服务列表中的第一个
            ServiceRecord.StartItem si = r.pendingStarts.remove(0);
            if (DEBUG_SERVICE) {
                Slog.v(TAG_SERVICE, "Sending arguments to: "
                        + r + " " + r.intent + " args=" + si.intent);
            }
            if (si.intent == null && N > 1) {
                // If somehow we got a dummy null intent in the middle,
                // then skip it.  DO NOT skip a null intent when it is
                // the only one in the list -- this is to support the
                // onStartCommand(null) case.
                continue;
            }
            si.deliveredTime = SystemClock.uptimeMillis();
            r.deliveredStarts.add(si);
            si.deliveryCount++;
            if (si.neededGrants != null) {
                mAm.grantUriPermissionUncheckedFromIntentLocked(si.neededGrants,
                        si.getUriPermissionsLocked());
            }
            mAm.grantEphemeralAccessLocked(r.userId, si.intent,
                    r.appInfo.uid, UserHandle.getAppId(si.callingId));
			// 标记启动服务开始
            bumpServiceExecutingLocked(r, execInFg, "start");
            if (!oomAdjusted) {
                oomAdjusted = true;
                mAm.updateOomAdjLocked(r.app, true);
            }
            if (r.fgRequired && !r.fgWaiting) {
                if (!r.isForeground) {
                    if (DEBUG_BACKGROUND_CHECK) {
                        Slog.i(TAG, "Launched service must call startForeground() within timeout: " + r);
                    }
                    scheduleServiceForegroundTransitionTimeoutLocked(r);
                } else {
                    if (DEBUG_BACKGROUND_CHECK) {
                        Slog.i(TAG, "Service already foreground; no new timeout: " + r);
                    }
                    r.fgRequired = false;
                }
            }
            int flags = 0;
            if (si.deliveryCount > 1) {
                flags |= Service.START_FLAG_RETRY;
            }
            if (si.doneExecutingCount > 0) {
                flags |= Service.START_FLAG_REDELIVERY;
            }
            args.add(new ServiceStartArgs(si.taskRemoved, si.id, flags, si.intent));
        }

        ParceledListSlice<ServiceStartArgs> slice = new ParceledListSlice<>(args);
        slice.setInlineCountLimit(4);
        Exception caughtException = null;
        try {
		// 发送消息，传动到ApplicationThread中的scheduleServiceArgs方法，最终会调用onStartCommand
            r.app.thread.scheduleServiceArgs(r, slice);
        } catch (TransactionTooLargeException e) {
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Transaction too large for " + args.size()
                    + " args, first: " + args.get(0).args);
            Slog.w(TAG, "Failed delivering service starts", e);
            caughtException = e;
        } catch (RemoteException e) {
            // Remote process gone...  we'll let the normal cleanup take care of this.
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Crashed while sending args: " + r);
            Slog.w(TAG, "Failed delivering service starts", e);
            caughtException = e;
        } catch (Exception e) {
            Slog.w(TAG, "Unexpected exception", e);
            caughtException = e;
        }

        if (caughtException != null) {
            // Keep nesting count correct
            final boolean inDestroying = mDestroyingServices.contains(r);
            for (int i = 0; i < args.size(); i++) {
                serviceDoneExecutingLocked(r, inDestroying, inDestroying);
            }
            if (caughtException instanceof TransactionTooLargeException) {
                throw (TransactionTooLargeException)caughtException;
            }
        }
    }

    private final boolean isServiceNeededLocked(ServiceRecord r, boolean knowConn,
            boolean hasConn) {
        // Are we still explicitly being asked to run?
        if (r.startRequested) {
            return true;
        }

        // Is someone still bound to us keeping us running?
        if (!knowConn) {
            hasConn = r.hasAutoCreateConnections();
        }
        if (hasConn) {
            return true;
        }

        return false;
    }

    // 停止服务
    private final void bringDownServiceIfNeededLocked(ServiceRecord r, boolean knowConn,
            boolean hasConn) {
        //Slog.i(TAG, "Bring down service:");
        //r.dump("  ");

        if (isServiceNeededLocked(r, knowConn, hasConn)) {
            return;
        }

        // Are we in the process of launching?
        if (mPendingServices.contains(r)) {
            return;
        }

        bringDownServiceLocked(r);
    }

    private final void bringDownServiceLocked(ServiceRecord r) {
        //Slog.i(TAG, "Bring down service:");
        //r.dump("  ");

        // Report to all of the connections that the service is no longer
        // available.
        for (int conni=r.connections.size()-1; conni>=0; conni--) {
            ArrayList<ConnectionRecord> c = r.connections.valueAt(conni);
            for (int i=0; i<c.size(); i++) {
                ConnectionRecord cr = c.get(i);
                // There is still a connection to the service that is
                // being brought down.  Mark it as dead.
                cr.serviceDead = true;
                try {
                    cr.conn.connected(r.name, null, true);
                } catch (Exception e) {
                    Slog.w(TAG, "Failure disconnecting service " + r.name +
                          " to connection " + c.get(i).conn.asBinder() +
                          " (in " + c.get(i).binding.client.processName + ")", e);
                }
            }
        }

        // Tell the service that it has been unbound.
        if (r.app != null && r.app.thread != null) {
            for (int i=r.bindings.size()-1; i>=0; i--) {
                IntentBindRecord ibr = r.bindings.valueAt(i);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Bringing down binding " + ibr
                        + ": hasBound=" + ibr.hasBound);
                if (ibr.hasBound) {// 所有服务与客户端已经解绑
                    try {
                        bumpServiceExecutingLocked(r, false, "bring down unbind");
                        mAm.updateOomAdjLocked(r.app, true);
                        ibr.hasBound = false;
                        ibr.requested = false;
						// 调用Service的onUnbind方法
                        r.app.thread.scheduleUnbindService(r,
                                ibr.intent.getIntent());
                    } catch (Exception e) {
                        Slog.w(TAG, "Exception when unbinding service "
                                + r.shortName, e);
                        serviceProcessGoneLocked(r);
                    }
                }
            }
        }

        // Check to see if the service had been started as foreground, but being
        // brought down before actually showing a notification.  That is not allowed.
        if (r.fgRequired) {
            Slog.w(TAG_SERVICE, "Bringing down service while still waiting for start foreground: "
                    + r);
            r.fgRequired = false;
            r.fgWaiting = false;
            mAm.mAppOpsService.finishOperation(AppOpsManager.getToken(mAm.mAppOpsService),
                    AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName);
            mAm.mHandler.removeMessages(
                    ActivityManagerService.SERVICE_FOREGROUND_TIMEOUT_MSG, r);
            if (r.app != null) {
                Message msg = mAm.mHandler.obtainMessage(
                        ActivityManagerService.SERVICE_FOREGROUND_CRASH_MSG);
                msg.obj = r.app;
                msg.getData().putCharSequence(
                    ActivityManagerService.SERVICE_RECORD_KEY, r.toString());
                mAm.mHandler.sendMessage(msg);
            }
        }

        if (DEBUG_SERVICE) {
            RuntimeException here = new RuntimeException();
            here.fillInStackTrace();
            Slog.v(TAG_SERVICE, "Bringing down " + r + " " + r.intent, here);
        }
        r.destroyTime = SystemClock.uptimeMillis();
        if (LOG_SERVICE_START_STOP) {
            EventLogTags.writeAmDestroyService(
                    r.userId, System.identityHashCode(r), (r.app != null) ? r.app.pid : -1);
        }

        final ServiceMap smap = getServiceMapLocked(r.userId);
        ServiceRecord found = smap.mServicesByName.remove(r.name);

        // Note when this method is called by bringUpServiceLocked(), the service is not found
        // in mServicesByName and found will be null.
        if (found != null && found != r) {
            // This is not actually the service we think is running...  this should not happen,
            // but if it does, fail hard.
            smap.mServicesByName.put(r.name, found);
            throw new IllegalStateException("Bringing down " + r + " but actually running "
                    + found);
        }
        smap.mServicesByIntent.remove(r.intent);
        r.totalRestartCount = 0;
        unscheduleServiceRestartLocked(r, 0, true);

        // Also make sure it is not on the pending list.
        for (int i=mPendingServices.size()-1; i>=0; i--) {
            if (mPendingServices.get(i) == r) {
                mPendingServices.remove(i);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Removed pending: " + r);
            }
        }

        cancelForegroundNotificationLocked(r);
        if (r.isForeground) {
            decActiveForegroundAppLocked(smap, r);
            mAm.mAppOpsService.finishOperation(
                    AppOpsManager.getToken(mAm.mAppOpsService),
                    AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName);
            StatsLog.write(StatsLog.FOREGROUND_SERVICE_STATE_CHANGED, r.appInfo.uid, r.shortName,
                    StatsLog.FOREGROUND_SERVICE_STATE_CHANGED__STATE__EXIT);
        }

        r.isForeground = false;
        r.foregroundId = 0;
        r.foregroundNoti = null;

        // Clear start entries.
        r.clearDeliveredStartsLocked();
        r.pendingStarts.clear();

        if (r.app != null) {// 服务进程存在
            synchronized (r.stats.getBatteryStats()) {
                r.stats.stopLaunchedLocked();
            }
            r.app.services.remove(r);
            if (r.whitelistManager) {// 白名单（绑定了该服务）
                updateWhitelistManagerLocked(r.app);
            }
            if (r.app.thread != null) {
                updateServiceForegroundLocked(r.app, false);
                try {
                    bumpServiceExecutingLocked(r, false, "destroy");
                    mDestroyingServices.add(r);
                    r.destroying = true;
                    mAm.updateOomAdjLocked(r.app, true);
                    // 停止服务，调用onDestroy方法
                    r.app.thread.scheduleStopService(r);
                } catch (Exception e) {
                    Slog.w(TAG, "Exception when destroying service "
                            + r.shortName, e);
                    serviceProcessGoneLocked(r);
                }
            } else {
                if (DEBUG_SERVICE) Slog.v(
                    TAG_SERVICE, "Removed service that has no process: " + r);
            }
        } else {
            if (DEBUG_SERVICE) Slog.v(
                TAG_SERVICE, "Removed service that is not running: " + r);
        }

        if (r.bindings.size() > 0) {
            r.bindings.clear();
        }

        if (r.restarter instanceof ServiceRestarter) {
           ((ServiceRestarter)r.restarter).setService(null);
        }

        int memFactor = mAm.mProcessStats.getMemFactorLocked();
        long now = SystemClock.uptimeMillis();
        if (r.tracker != null) {
            r.tracker.setStarted(false, memFactor, now);
            r.tracker.setBound(false, memFactor, now);
            if (r.executeNesting == 0) {
                r.tracker.clearCurrentOwner(r, false);
                r.tracker = null;
            }
        }

        smap.ensureNotStartingBackgroundLocked(r);
    }

    void removeConnectionLocked(
            ConnectionRecord c, ProcessRecord skipApp, ActivityRecord skipAct) {
        // 获取连接中的Binder对象
        IBinder binder = c.conn.asBinder();
        // 获取连接应用、服务和连接列表的客户端
        AppBindRecord b = c.binding;
        // 获取描述服务的对象
        ServiceRecord s = b.service;
        // 根据Binder对象获取服务绑定的连接列表
        ArrayList<ConnectionRecord> clist = s.connections.get(binder);
        if (clist != null) {
            clist.remove(c);// 移除该连接对象
            if (clist.size() == 0) {// 如果列表为空，移除该列表
                s.connections.remove(binder);
            }
        }
        // 从连接应用、服务和连接列表的客户端中的连接列表中移除
        b.connections.remove(c);
        if (c.activity != null && c.activity != skipAct) {
            if (c.activity.connections != null) {
                c.activity.connections.remove(c);
            }
        }
        if (b.client != skipApp) {
            b.client.connections.remove(c);
            if ((c.flags&Context.BIND_ABOVE_CLIENT) != 0) {
                b.client.updateHasAboveClientLocked();
            }
            // If this connection requested whitelist management, see if we should
            // now clear that state.
            if ((c.flags&Context.BIND_ALLOW_WHITELIST_MANAGEMENT) != 0) {
                s.updateWhitelistManager();
                if (!s.whitelistManager && s.app != null) {
                    updateWhitelistManagerLocked(s.app);
                }
            }
            if (s.app != null) {
                updateServiceClientActivitiesLocked(s.app, c, true);
            }
        }
        // 从总的缓存列表中移除
        clist = mServiceConnections.get(binder);
        if (clist != null) {
            clist.remove(c);
            if (clist.size() == 0) {
                mServiceConnections.remove(binder);
            }
        }

        mAm.stopAssociationLocked(b.client.uid, b.client.processName, s.appInfo.uid, s.name);

        // 如果连接应用、服务和连接列表的客户端中的连接列表为空了，说明没有绑定了，那么移除该客户端
        if (b.connections.size() == 0) {
            b.intent.apps.remove(b.client);
        }

        if (!c.serviceDead) {// 服务还存在
            if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Disconnecting binding " + b.intent
                    + ": shouldUnbind=" + b.intent.hasBound);
            if (s.app != null && s.app.thread != null && b.intent.apps.size() == 0
                    && b.intent.hasBound) {
                try {
                    bumpServiceExecutingLocked(s, false, "unbind");
                    if (b.client != s.app && (c.flags&Context.BIND_WAIVE_PRIORITY) == 0
                            && s.app.setProcState <= ActivityManager.PROCESS_STATE_HEAVY_WEIGHT) {
                        // If this service's process is not already in the cached list,
                        // then update it in the LRU list here because this may be causing
                        // it to go down there and we want it to start out near the top.
                        mAm.updateLruProcessLocked(s.app, false, null);
                    }
                    mAm.updateOomAdjLocked(s.app, true);
                    b.intent.hasBound = false;
                    // Assume the client doesn't want to know about a rebind;
                    // we will deal with that later if it asks for one.
                    b.intent.doRebind = false;
                    s.app.thread.scheduleUnbindService(s, b.intent.intent.getIntent());
                } catch (Exception e) {
                    Slog.w(TAG, "Exception when unbinding service " + s.shortName, e);
                    serviceProcessGoneLocked(s);
                }
            }

            // If unbound while waiting to start, remove the pending service
            mPendingServices.remove(s);

            if ((c.flags&Context.BIND_AUTO_CREATE) != 0) {
                boolean hasAutoCreate = s.hasAutoCreateConnections();
                if (!hasAutoCreate) {
                    if (s.tracker != null) {
                        s.tracker.setBound(false, mAm.mProcessStats.getMemFactorLocked(),
                                SystemClock.uptimeMillis());
                    }
                }
                bringDownServiceIfNeededLocked(s, true, hasAutoCreate);
            }
        }
    }

    void serviceDoneExecutingLocked(ServiceRecord r, int type, int startId, int res) {
        boolean inDestroying = mDestroyingServices.contains(r);
        if (r != null) {
            if (type == ActivityThread.SERVICE_DONE_EXECUTING_START) {// 启动服务
                // This is a call from a service start...  take care of
                // book-keeping.
                r.callStart = true;
                switch (res) {
                    case Service.START_STICKY_COMPATIBILITY:
                    case Service.START_STICKY: {
                        // We are done with the associated start arguments.
                        r.findDeliveredStart(startId, false, true);
                        // Don't stop if killed.
                        r.stopIfKilled = false;
                        break;
                    }
                    case Service.START_NOT_STICKY: {
                        // We are done with the associated start arguments.
                        r.findDeliveredStart(startId, false, true);
                        if (r.getLastStartId() == startId) {
                            // There is no more work, and this service
                            // doesn't want to hang around if killed.
                            r.stopIfKilled = true;
                        }
                        break;
                    }
                    case Service.START_REDELIVER_INTENT: {
                        // We'll keep this item until they explicitly
                        // call stop for it, but keep track of the fact
                        // that it was delivered.
                        ServiceRecord.StartItem si = r.findDeliveredStart(startId, false, false);
                        if (si != null) {
                            si.deliveryCount = 0;
                            si.doneExecutingCount++;
                            // Don't stop if killed.
                            r.stopIfKilled = true;
                        }
                        break;
                    }
                    case Service.START_TASK_REMOVED_COMPLETE: {
                        // Special processing for onTaskRemoved().  Don't
                        // impact normal onStartCommand() processing.
                        r.findDeliveredStart(startId, true, true);
                        break;
                    }
                    default:
                        throw new IllegalArgumentException(
                                "Unknown service start result: " + res);
                }
                if (res == Service.START_STICKY_COMPATIBILITY) {
                    r.callStart = false;
                }
            } else if (type == ActivityThread.SERVICE_DONE_EXECUTING_STOP) {// 停止服务
                // This is the final call from destroying the service...  we should
                // actually be getting rid of the service at this point.  Do some
                // validation of its state, and ensure it will be fully removed.
                if (!inDestroying) {
                    // Not sure what else to do with this...  if it is not actually in the
                    // destroying list, we don't need to make sure to remove it from it.
                    // If the app is null, then it was probably removed because the process died,
                    // otherwise wtf
                    if (r.app != null) {
                        Slog.w(TAG, "Service done with onDestroy, but not inDestroying: "
                                + r + ", app=" + r.app);
                    }
                } else if (r.executeNesting != 1) {
                    Slog.w(TAG, "Service done with onDestroy, but executeNesting="
                            + r.executeNesting + ": " + r);
                    // Fake it to keep from ANR due to orphaned entry.
                    r.executeNesting = 1;
                }
            }
            final long origId = Binder.clearCallingIdentity();
            serviceDoneExecutingLocked(r, inDestroying, inDestroying);
            Binder.restoreCallingIdentity(origId);
        } else {
            Slog.w(TAG, "Done executing unknown service from pid "
                    + Binder.getCallingPid());
        }
    }

    private void serviceProcessGoneLocked(ServiceRecord r) {
        if (r.tracker != null) {
            int memFactor = mAm.mProcessStats.getMemFactorLocked();
            long now = SystemClock.uptimeMillis();
            r.tracker.setExecuting(false, memFactor, now);
            r.tracker.setBound(false, memFactor, now);
            r.tracker.setStarted(false, memFactor, now);
        }
        serviceDoneExecutingLocked(r, true, true);
    }

    private void serviceDoneExecutingLocked(ServiceRecord r, boolean inDestroying,
            boolean finishing) {
        if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "<<< DONE EXECUTING " + r
                + ": nesting=" + r.executeNesting
                + ", inDestroying=" + inDestroying + ", app=" + r.app);
        else if (DEBUG_SERVICE_EXECUTING) Slog.v(TAG_SERVICE_EXECUTING,
                "<<< DONE EXECUTING " + r.shortName);
        r.executeNesting--;
        if (r.executeNesting <= 0) {
            if (r.app != null) {
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE,
                        "Nesting at 0 of " + r.shortName);
                r.app.execServicesFg = false;
                r.app.executingServices.remove(r);
                if (r.app.executingServices.size() == 0) {
                    if (DEBUG_SERVICE || DEBUG_SERVICE_EXECUTING) Slog.v(TAG_SERVICE_EXECUTING,
                            "No more executingServices of " + r.shortName);
                    mAm.mHandler.removeMessages(ActivityManagerService.SERVICE_TIMEOUT_MSG, r.app);
                } else if (r.executeFg) {
                    // Need to re-evaluate whether the app still needs to be in the foreground.
                    for (int i=r.app.executingServices.size()-1; i>=0; i--) {
                        if (r.app.executingServices.valueAt(i).executeFg) {
                            r.app.execServicesFg = true;
                            break;
                        }
                    }
                }
                if (inDestroying) {
                    if (DEBUG_SERVICE) Slog.v(TAG_SERVICE,
                            "doneExecuting remove destroying " + r);
                    mDestroyingServices.remove(r);
                    r.bindings.clear();
                }
                mAm.updateOomAdjLocked(r.app, true);
            }
            r.executeFg = false;
            if (r.tracker != null) {
                r.tracker.setExecuting(false, mAm.mProcessStats.getMemFactorLocked(),
                        SystemClock.uptimeMillis());
                if (finishing) {
                    r.tracker.clearCurrentOwner(r, false);
                    r.tracker = null;
                }
            }
            if (finishing) {
                if (r.app != null && !r.app.persistent) {
                    r.app.services.remove(r);
                    if (r.whitelistManager) {
                        updateWhitelistManagerLocked(r.app);
                    }
                }
                r.app = null;
            }
        }
    }

    // 检查正在等待启动的服务列表中是否存在该服务，如果存在则开始启动服务，并从等待列表中删除
    boolean attachApplicationLocked(ProcessRecord proc, String processName)
            throws RemoteException {
        boolean didSomething = false;
        // Collect any services that are waiting for this process to come up.
        if (mPendingServices.size() > 0) {
            ServiceRecord sr = null;
            try {
                for (int i=0; i<mPendingServices.size(); i++) {
                    sr = mPendingServices.get(i);
                    if (proc != sr.isolatedProc && (proc.uid != sr.appInfo.uid
                            || !processName.equals(sr.processName))) {
                        continue;
                    }

                    mPendingServices.remove(i);
                    i--;
                    // 添加相关信息
                    proc.addPackage(sr.appInfo.packageName, sr.appInfo.longVersionCode,
                            mAm.mProcessStats);
                    // 启动服务，将进入服务生命周期
                    realStartServiceLocked(sr, proc, sr.createdFromFg);
                    didSomething = true;
                    if (!isServiceNeededLocked(sr, false, false)) {
                        // We were waiting for this service to start, but it is actually no
                        // longer needed.  This could happen because bringDownServiceIfNeeded
                        // won't bring down a service that is pending...  so now the pending
                        // is done, so let's drop it.
                        bringDownServiceLocked(sr);
                    }
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception in new application when starting service "
                        + sr.shortName, e);
                throw e;
            }
        }
        // Also, if there are any services that are waiting to restart and
        // would run in this process, now is a good time to start them.  It would
        // be weird to bring up the process but arbitrarily not let the services
        // run at this point just because their restart time hasn't come up.
        if (mRestartingServices.size() > 0) {
            ServiceRecord sr;
            for (int i=0; i<mRestartingServices.size(); i++) {
                sr = mRestartingServices.get(i);
                if (proc != sr.isolatedProc && (proc.uid != sr.appInfo.uid
                        || !processName.equals(sr.processName))) {
                    continue;
                }
                mAm.mHandler.removeCallbacks(sr.restarter);
                mAm.mHandler.post(sr.restarter);
            }
        }
        return didSomething;
    }

    void processStartTimedOutLocked(ProcessRecord proc) {
        for (int i=0; i<mPendingServices.size(); i++) {
            ServiceRecord sr = mPendingServices.get(i);
            if ((proc.uid == sr.appInfo.uid
                    && proc.processName.equals(sr.processName))
                    || sr.isolatedProc == proc) {
                Slog.w(TAG, "Forcing bringing down service: " + sr);
                sr.isolatedProc = null;
                mPendingServices.remove(i);
                i--;
                bringDownServiceLocked(sr);
            }
        }
    }

    private boolean collectPackageServicesLocked(String packageName, Set<String> filterByClasses,
            boolean evenPersistent, boolean doit, boolean killProcess,
            ArrayMap<ComponentName, ServiceRecord> services) {
        boolean didSomething = false;
        for (int i = services.size() - 1; i >= 0; i--) {
            ServiceRecord service = services.valueAt(i);
            final boolean sameComponent = packageName == null
                    || (service.packageName.equals(packageName)
                        && (filterByClasses == null
                            || filterByClasses.contains(service.name.getClassName())));
            if (sameComponent
                    && (service.app == null || evenPersistent || !service.app.persistent)) {
                if (!doit) {
                    return true;
                }
                didSomething = true;
                Slog.i(TAG, "  Force stopping service " + service);
                if (service.app != null) {
                    service.app.removed = killProcess;
                    if (!service.app.persistent) {
                        service.app.services.remove(service);
                        if (service.whitelistManager) {
                            updateWhitelistManagerLocked(service.app);
                        }
                    }
                }
                service.app = null;
                service.isolatedProc = null;
                if (mTmpCollectionResults == null) {
                    mTmpCollectionResults = new ArrayList<>();
                }
                mTmpCollectionResults.add(service);
            }
        }
        return didSomething;
    }

    boolean bringDownDisabledPackageServicesLocked(String packageName, Set<String> filterByClasses,
            int userId, boolean evenPersistent, boolean killProcess, boolean doit) {
        boolean didSomething = false;

        if (mTmpCollectionResults != null) {
            mTmpCollectionResults.clear();
        }

        if (userId == UserHandle.USER_ALL) {
            for (int i = mServiceMap.size() - 1; i >= 0; i--) {
                didSomething |= collectPackageServicesLocked(packageName, filterByClasses,
                        evenPersistent, doit, killProcess, mServiceMap.valueAt(i).mServicesByName);
                if (!doit && didSomething) {
                    return true;
                }
                if (doit && filterByClasses == null) {
                    forceStopPackageLocked(packageName, mServiceMap.valueAt(i).mUserId);
                }
            }
        } else {
            ServiceMap smap = mServiceMap.get(userId);
            if (smap != null) {
                ArrayMap<ComponentName, ServiceRecord> items = smap.mServicesByName;
                didSomething = collectPackageServicesLocked(packageName, filterByClasses,
                        evenPersistent, doit, killProcess, items);
            }
            if (doit && filterByClasses == null) {
                forceStopPackageLocked(packageName, userId);
            }
        }

        if (mTmpCollectionResults != null) {
            for (int i = mTmpCollectionResults.size() - 1; i >= 0; i--) {
                bringDownServiceLocked(mTmpCollectionResults.get(i));
            }
            mTmpCollectionResults.clear();
        }

        return didSomething;
    }

    void forceStopPackageLocked(String packageName, int userId) {
        ServiceMap smap = mServiceMap.get(userId);
        if (smap != null && smap.mActiveForegroundApps.size() > 0) {
            for (int i = smap.mActiveForegroundApps.size()-1; i >= 0; i--) {
                ActiveForegroundApp aa = smap.mActiveForegroundApps.valueAt(i);
                if (aa.mPackageName.equals(packageName)) {
                    smap.mActiveForegroundApps.removeAt(i);
                    smap.mActiveForegroundAppsChanged = true;
                }
            }
            if (smap.mActiveForegroundAppsChanged) {
                requestUpdateActiveForegroundAppsLocked(smap, 0);
            }
        }
    }

    void cleanUpRemovedTaskLocked(TaskRecord tr, ComponentName component, Intent baseIntent) {
        ArrayList<ServiceRecord> services = new ArrayList<>();
        ArrayMap<ComponentName, ServiceRecord> alls = getServicesLocked(tr.userId);
        for (int i = alls.size() - 1; i >= 0; i--) {
            ServiceRecord sr = alls.valueAt(i);
            if (sr.packageName.equals(component.getPackageName())) {
                services.add(sr);
            }
        }

        // Take care of any running services associated with the app.
        for (int i = services.size() - 1; i >= 0; i--) {
            ServiceRecord sr = services.get(i);
            if (sr.startRequested) {
                if ((sr.serviceInfo.flags&ServiceInfo.FLAG_STOP_WITH_TASK) != 0) {
                    Slog.i(TAG, "Stopping service " + sr.shortName + ": remove task");
                    stopServiceLocked(sr);
                } else {
                    sr.pendingStarts.add(new ServiceRecord.StartItem(sr, true,
                            sr.getLastStartId(), baseIntent, null, 0));
                    if (sr.app != null && sr.app.thread != null) {
                        // We always run in the foreground, since this is called as
                        // part of the "remove task" UI operation.
                        try {
                            sendServiceArgsLocked(sr, true, false);
                        } catch (TransactionTooLargeException e) {
                            // Ignore, keep going.
                        }
                    }
                }
            }
        }
    }

    final void killServicesLocked(ProcessRecord app, boolean allowRestart) {
        // Report disconnected services.
        if (false) {
            // XXX we are letting the client link to the service for
            // death notifications.
            if (app.services.size() > 0) {
                Iterator<ServiceRecord> it = app.services.iterator();
                while (it.hasNext()) {
                    ServiceRecord r = it.next();
                    for (int conni=r.connections.size()-1; conni>=0; conni--) {
                        ArrayList<ConnectionRecord> cl = r.connections.valueAt(conni);
                        for (int i=0; i<cl.size(); i++) {
                            ConnectionRecord c = cl.get(i);
                            if (c.binding.client != app) {
                                try {
                                    //c.conn.connected(r.className, null);
                                } catch (Exception e) {
                                    // todo: this should be asynchronous!
                                    Slog.w(TAG, "Exception thrown disconnected servce "
                                          + r.shortName
                                          + " from app " + app.processName, e);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Clean up any connections this application has to other services.
        for (int i = app.connections.size() - 1; i >= 0; i--) {
            ConnectionRecord r = app.connections.valueAt(i);
            removeConnectionLocked(r, app, null);
        }
        updateServiceConnectionActivitiesLocked(app);
        app.connections.clear();

        app.whitelistManager = false;

        // Clear app state from services.
        for (int i = app.services.size() - 1; i >= 0; i--) {
            ServiceRecord sr = app.services.valueAt(i);
            synchronized (sr.stats.getBatteryStats()) {
                sr.stats.stopLaunchedLocked();
            }
            if (sr.app != app && sr.app != null && !sr.app.persistent) {
                sr.app.services.remove(sr);
            }
            sr.app = null;
            sr.isolatedProc = null;
            sr.executeNesting = 0;
            sr.forceClearTracker();
            if (mDestroyingServices.remove(sr)) {
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "killServices remove destroying " + sr);
            }

            final int numClients = sr.bindings.size();
            for (int bindingi=numClients-1; bindingi>=0; bindingi--) {
                IntentBindRecord b = sr.bindings.valueAt(bindingi);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "Killing binding " + b
                        + ": shouldUnbind=" + b.hasBound);
                b.binder = null;
                b.requested = b.received = b.hasBound = false;
                // If this binding is coming from a cached process and is asking to keep
                // the service created, then we'll kill the cached process as well -- we
                // don't want to be thrashing around restarting processes that are only
                // there to be cached.
                for (int appi=b.apps.size()-1; appi>=0; appi--) {
                    final ProcessRecord proc = b.apps.keyAt(appi);
                    // If the process is already gone, skip it.
                    if (proc.killedByAm || proc.thread == null) {
                        continue;
                    }
                    // Only do this for processes that have an auto-create binding;
                    // otherwise the binding can be left, because it won't cause the
                    // service to restart.
                    final AppBindRecord abind = b.apps.valueAt(appi);
                    boolean hasCreate = false;
                    for (int conni=abind.connections.size()-1; conni>=0; conni--) {
                        ConnectionRecord conn = abind.connections.valueAt(conni);
                        if ((conn.flags&(Context.BIND_AUTO_CREATE|Context.BIND_ALLOW_OOM_MANAGEMENT
                                |Context.BIND_WAIVE_PRIORITY)) == Context.BIND_AUTO_CREATE) {
                            hasCreate = true;
                            break;
                        }
                    }
                    if (!hasCreate) {
                        continue;
                    }
                    // XXX turned off for now until we have more time to get a better policy.
                    if (false && proc != null && !proc.persistent && proc.thread != null
                            && proc.pid != 0 && proc.pid != ActivityManagerService.MY_PID
                            && proc.setProcState >= ActivityManager.PROCESS_STATE_LAST_ACTIVITY) {
                        proc.kill("bound to service " + sr.name.flattenToShortString()
                                + " in dying proc " + (app != null ? app.processName : "??"), true);
                    }
                }
            }
        }

        ServiceMap smap = getServiceMapLocked(app.userId);

        // Now do remaining service cleanup.
        for (int i=app.services.size()-1; i>=0; i--) {
            ServiceRecord sr = app.services.valueAt(i);

            // Unless the process is persistent, this process record is going away,
            // so make sure the service is cleaned out of it.
            if (!app.persistent) {
                app.services.removeAt(i);
            }

            // Sanity check: if the service listed for the app is not one
            // we actually are maintaining, just let it drop.
            final ServiceRecord curRec = smap.mServicesByName.get(sr.name);
            if (curRec != sr) {
                if (curRec != null) {
                    Slog.wtf(TAG, "Service " + sr + " in process " + app
                            + " not same as in map: " + curRec);
                }
                continue;
            }

            // Any services running in the application may need to be placed
            // back in the pending list.
            if (allowRestart && sr.crashCount >= mAm.mConstants.BOUND_SERVICE_MAX_CRASH_RETRY
                    && (sr.serviceInfo.applicationInfo.flags
                        &ApplicationInfo.FLAG_PERSISTENT) == 0) {
                Slog.w(TAG, "Service crashed " + sr.crashCount
                        + " times, stopping: " + sr);
                EventLog.writeEvent(EventLogTags.AM_SERVICE_CRASHED_TOO_MUCH,
                        sr.userId, sr.crashCount, sr.shortName, app.pid);
                bringDownServiceLocked(sr);
            } else if (!allowRestart
                    || !mAm.mUserController.isUserRunning(sr.userId, 0)) {
                bringDownServiceLocked(sr);
            } else {
                boolean canceled = scheduleServiceRestartLocked(sr, true);

                // Should the service remain running?  Note that in the
                // extreme case of so many attempts to deliver a command
                // that it failed we also will stop it here.
                if (sr.startRequested && (sr.stopIfKilled || canceled)) {
                    if (sr.pendingStarts.size() == 0) {
                        sr.startRequested = false;
                        if (sr.tracker != null) {
                            sr.tracker.setStarted(false, mAm.mProcessStats.getMemFactorLocked(),
                                    SystemClock.uptimeMillis());
                        }
                        if (!sr.hasAutoCreateConnections()) {
                            // Whoops, no reason to restart!
                            bringDownServiceLocked(sr);
                        }
                    }
                }
            }
        }

        if (!allowRestart) {
            app.services.clear();

            // Make sure there are no more restarting services for this process.
            for (int i=mRestartingServices.size()-1; i>=0; i--) {
                ServiceRecord r = mRestartingServices.get(i);
                if (r.processName.equals(app.processName) &&
                        r.serviceInfo.applicationInfo.uid == app.info.uid) {
                    mRestartingServices.remove(i);
                    clearRestartingIfNeededLocked(r);
                }
            }
            for (int i=mPendingServices.size()-1; i>=0; i--) {
                ServiceRecord r = mPendingServices.get(i);
                if (r.processName.equals(app.processName) &&
                        r.serviceInfo.applicationInfo.uid == app.info.uid) {
                    mPendingServices.remove(i);
                }
            }
        }

        // Make sure we have no more records on the stopping list.
        int i = mDestroyingServices.size();
        while (i > 0) {
            i--;
            ServiceRecord sr = mDestroyingServices.get(i);
            if (sr.app == app) {
                sr.forceClearTracker();
                mDestroyingServices.remove(i);
                if (DEBUG_SERVICE) Slog.v(TAG_SERVICE, "killServices remove destroying " + sr);
            }
        }

        app.executingServices.clear();
    }

    ActivityManager.RunningServiceInfo makeRunningServiceInfoLocked(ServiceRecord r) {
        ActivityManager.RunningServiceInfo info =
            new ActivityManager.RunningServiceInfo();
        info.service = r.name;
        if (r.app != null) {
            info.pid = r.app.pid;
        }
        info.uid = r.appInfo.uid;
        info.process = r.processName;
        info.foreground = r.isForeground;
        info.activeSince = r.createRealTime;
        info.started = r.startRequested;
        info.clientCount = r.connections.size();
        info.crashCount = r.crashCount;
        info.lastActivityTime = r.lastActivity;
        if (r.isForeground) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_FOREGROUND;
        }
        if (r.startRequested) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_STARTED;
        }
        if (r.app != null && r.app.pid == ActivityManagerService.MY_PID) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_SYSTEM_PROCESS;
        }
        if (r.app != null && r.app.persistent) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_PERSISTENT_PROCESS;
        }

        for (int conni=r.connections.size()-1; conni>=0; conni--) {
            ArrayList<ConnectionRecord> connl = r.connections.valueAt(conni);
            for (int i=0; i<connl.size(); i++) {
                ConnectionRecord conn = connl.get(i);
                if (conn.clientLabel != 0) {
                    info.clientPackage = conn.binding.client.info.packageName;
                    info.clientLabel = conn.clientLabel;
                    return info;
                }
            }
        }
        return info;
    }

    List<ActivityManager.RunningServiceInfo> getRunningServiceInfoLocked(int maxNum, int flags,
        int callingUid, boolean allowed, boolean canInteractAcrossUsers) {
        ArrayList<ActivityManager.RunningServiceInfo> res
                = new ArrayList<ActivityManager.RunningServiceInfo>();

        final long ident = Binder.clearCallingIdentity();
        try {
            if (canInteractAcrossUsers) {
                int[] users = mAm.mUserController.getUsers();
                for (int ui=0; ui<users.length && res.size() < maxNum; ui++) {
                    ArrayMap<ComponentName, ServiceRecord> alls = getServicesLocked(users[ui]);
                    for (int i=0; i<alls.size() && res.size() < maxNum; i++) {
                        ServiceRecord sr = alls.valueAt(i);
                        res.add(makeRunningServiceInfoLocked(sr));
                    }
                }

                for (int i=0; i<mRestartingServices.size() && res.size() < maxNum; i++) {
                    ServiceRecord r = mRestartingServices.get(i);
                    ActivityManager.RunningServiceInfo info =
                            makeRunningServiceInfoLocked(r);
                    info.restarting = r.nextRestartTime;
                    res.add(info);
                }
            } else {
                int userId = UserHandle.getUserId(callingUid);
                ArrayMap<ComponentName, ServiceRecord> alls = getServicesLocked(userId);
                for (int i=0; i<alls.size() && res.size() < maxNum; i++) {
                    ServiceRecord sr = alls.valueAt(i);

                    if (allowed || (sr.app != null && sr.app.uid == callingUid)) {
                        res.add(makeRunningServiceInfoLocked(sr));
                    }
                }

                for (int i=0; i<mRestartingServices.size() && res.size() < maxNum; i++) {
                    ServiceRecord r = mRestartingServices.get(i);
                    if (r.userId == userId
                        && (allowed || (r.app != null && r.app.uid == callingUid))) {
                        ActivityManager.RunningServiceInfo info =
                                makeRunningServiceInfoLocked(r);
                        info.restarting = r.nextRestartTime;
                        res.add(info);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return res;
    }

    public PendingIntent getRunningServiceControlPanelLocked(ComponentName name) {
        int userId = UserHandle.getUserId(Binder.getCallingUid());
        ServiceRecord r = getServiceByNameLocked(name, userId);
        if (r != null) {
            for (int conni=r.connections.size()-1; conni>=0; conni--) {
                ArrayList<ConnectionRecord> conn = r.connections.valueAt(conni);
                for (int i=0; i<conn.size(); i++) {
                    if (conn.get(i).clientIntent != null) {
                        return conn.get(i).clientIntent;
                    }
                }
            }
        }
        return null;
    }

    void serviceTimeout(ProcessRecord proc) {
        String anrMessage = null;

        synchronized(mAm) {
            if (proc.executingServices.size() == 0 || proc.thread == null) {
                return;
            }
            final long now = SystemClock.uptimeMillis();
            final long maxTime =  now -
                    (proc.execServicesFg ? SERVICE_TIMEOUT : SERVICE_BACKGROUND_TIMEOUT);
            ServiceRecord timeout = null;
            long nextTime = 0;
            for (int i=proc.executingServices.size()-1; i>=0; i--) {
                ServiceRecord sr = proc.executingServices.valueAt(i);
                if (sr.executingStart < maxTime) {
                    timeout = sr;
                    break;
                }
                if (sr.executingStart > nextTime) {
                    nextTime = sr.executingStart;
                }
            }
            if (timeout != null && mAm.mLruProcesses.contains(proc)) {
                Slog.w(TAG, "Timeout executing service: " + timeout);
                StringWriter sw = new StringWriter();
                PrintWriter pw = new FastPrintWriter(sw, false, 1024);
                pw.println(timeout);
                timeout.dump(pw, "    ");
                pw.close();
                mLastAnrDump = sw.toString();
                mAm.mHandler.removeCallbacks(mLastAnrDumpClearer);
                mAm.mHandler.postDelayed(mLastAnrDumpClearer, LAST_ANR_LIFETIME_DURATION_MSECS);
                anrMessage = "executing service " + timeout.shortName;
            } else {
                Message msg = mAm.mHandler.obtainMessage(
                        ActivityManagerService.SERVICE_TIMEOUT_MSG);
                msg.obj = proc;
                mAm.mHandler.sendMessageAtTime(msg, proc.execServicesFg
                        ? (nextTime+SERVICE_TIMEOUT) : (nextTime + SERVICE_BACKGROUND_TIMEOUT));
            }
        }

        if (anrMessage != null) {
            mAm.mAppErrors.appNotResponding(proc, null, null, false, anrMessage);
        }
    }

    void serviceForegroundTimeout(ServiceRecord r) {
        ProcessRecord app;
        synchronized (mAm) {
            if (!r.fgRequired || r.destroying) {
                return;
            }

            app = r.app;
            if (app != null && app.debugging) {
                // The app's being debugged; let it ride
                return;
            }

            if (DEBUG_BACKGROUND_CHECK) {
                Slog.i(TAG, "Service foreground-required timeout for " + r);
            }
            r.fgWaiting = false;
            stopServiceLocked(r);
        }

        if (app != null) {
            mAm.mAppErrors.appNotResponding(app, null, null, false,
                    "Context.startForegroundService() did not then call Service.startForeground(): "
                        + r);
        }
    }

    public void updateServiceApplicationInfoLocked(ApplicationInfo applicationInfo) {
        final int userId = UserHandle.getUserId(applicationInfo.uid);
        ServiceMap serviceMap = mServiceMap.get(userId);
        if (serviceMap != null) {
            ArrayMap<ComponentName, ServiceRecord> servicesByName = serviceMap.mServicesByName;
            for (int j = servicesByName.size() - 1; j >= 0; j--) {
                ServiceRecord serviceRecord = servicesByName.valueAt(j);
                if (applicationInfo.packageName.equals(serviceRecord.appInfo.packageName)) {
                    serviceRecord.appInfo = applicationInfo;
                    serviceRecord.serviceInfo.applicationInfo = applicationInfo;
                }
            }
        }
    }

    void serviceForegroundCrash(ProcessRecord app, CharSequence serviceRecord) {
        mAm.crashApplication(app.uid, app.pid, app.info.packageName, app.userId,
                "Context.startForegroundService() did not then call Service.startForeground(): "
                    + serviceRecord);
    }

    void scheduleServiceTimeoutLocked(ProcessRecord proc) {
        if (proc.executingServices.size() == 0 || proc.thread == null) {
            return;
        }
        Message msg = mAm.mHandler.obtainMessage(
                ActivityManagerService.SERVICE_TIMEOUT_MSG);
        msg.obj = proc;
        // 当超时后仍没有remove该SERVICE_TIMEOUT_MSG消息，则执行service Timeout流程
        mAm.mHandler.sendMessageDelayed(msg,
                proc.execServicesFg ? SERVICE_TIMEOUT : SERVICE_BACKGROUND_TIMEOUT);
    }

    void scheduleServiceForegroundTransitionTimeoutLocked(ServiceRecord r) {
        if (r.app.executingServices.size() == 0 || r.app.thread == null) {
            return;
        }
        Message msg = mAm.mHandler.obtainMessage(
                ActivityManagerService.SERVICE_FOREGROUND_TIMEOUT_MSG);
        msg.obj = r;
        r.fgWaiting = true;
        mAm.mHandler.sendMessageDelayed(msg, SERVICE_START_FOREGROUND_TIMEOUT);
    }

    final class ServiceDumper {
        private final FileDescriptor fd;
        private final PrintWriter pw;
        private final String[] args;
        private final boolean dumpAll;
        private final String dumpPackage;
        private final ItemMatcher matcher;
        private final ArrayList<ServiceRecord> services = new ArrayList<>();

        private final long nowReal = SystemClock.elapsedRealtime();

        private boolean needSep = false;
        private boolean printedAnything = false;
        private boolean printed = false;

        /**
         * Note: do not call directly, use {@link #newServiceDumperLocked} instead (this
         * must be called with the lock held).
         */
        ServiceDumper(FileDescriptor fd, PrintWriter pw, String[] args,
                int opti, boolean dumpAll, String dumpPackage) {
            this.fd = fd;
            this.pw = pw;
            this.args = args;
            this.dumpAll = dumpAll;
            this.dumpPackage = dumpPackage;
            matcher = new ItemMatcher();
            matcher.build(args, opti);

            final int[] users = mAm.mUserController.getUsers();
            for (int user : users) {
                ServiceMap smap = getServiceMapLocked(user);
                if (smap.mServicesByName.size() > 0) {
                    for (int si=0; si<smap.mServicesByName.size(); si++) {
                        ServiceRecord r = smap.mServicesByName.valueAt(si);
                        if (!matcher.match(r, r.name)) {
                            continue;
                        }
                        if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                            continue;
                        }
                        services.add(r);
                    }
                }
            }
        }

        private void dumpHeaderLocked() {
            pw.println("ACTIVITY MANAGER SERVICES (dumpsys activity services)");
            if (mLastAnrDump != null) {
                pw.println("  Last ANR service:");
                pw.print(mLastAnrDump);
                pw.println();
            }
        }

        void dumpLocked() {
            dumpHeaderLocked();

            try {
                int[] users = mAm.mUserController.getUsers();
                for (int user : users) {
                    // Find the first service for this user.
                    int serviceIdx = 0;
                    while (serviceIdx < services.size() && services.get(serviceIdx).userId != user) {
                        serviceIdx++;
                    }
                    printed = false;
                    if (serviceIdx < services.size()) {
                        needSep = false;
                        while (serviceIdx < services.size()) {
                            ServiceRecord r = services.get(serviceIdx);
                            serviceIdx++;
                            if (r.userId != user) {
                                break;
                            }
                            dumpServiceLocalLocked(r);
                        }
                        needSep |= printed;
                    }

                    dumpUserRemainsLocked(user);
                }
            } catch (Exception e) {
                Slog.w(TAG, "Exception in dumpServicesLocked", e);
            }

            dumpRemainsLocked();
        }

        void dumpWithClient() {
            synchronized(mAm) {
                dumpHeaderLocked();
            }

            try {
                int[] users = mAm.mUserController.getUsers();
                for (int user : users) {
                    // Find the first service for this user.
                    int serviceIdx = 0;
                    while (serviceIdx < services.size() && services.get(serviceIdx).userId != user) {
                        serviceIdx++;
                    }
                    printed = false;
                    if (serviceIdx < services.size()) {
                        needSep = false;
                        while (serviceIdx < services.size()) {
                            ServiceRecord r = services.get(serviceIdx);
                            serviceIdx++;
                            if (r.userId != user) {
                                break;
                            }
                            synchronized(mAm) {
                                dumpServiceLocalLocked(r);
                            }
                            dumpServiceClient(r);
                        }
                        needSep |= printed;
                    }

                    synchronized(mAm) {
                        dumpUserRemainsLocked(user);
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "Exception in dumpServicesLocked", e);
            }

            synchronized(mAm) {
                dumpRemainsLocked();
            }
        }

        private void dumpUserHeaderLocked(int user) {
            if (!printed) {
                if (printedAnything) {
                    pw.println();
                }
                pw.println("  User " + user + " active services:");
                printed = true;
            }
            printedAnything = true;
            if (needSep) {
                pw.println();
            }
        }

        private void dumpServiceLocalLocked(ServiceRecord r) {
            dumpUserHeaderLocked(r.userId);
            pw.print("  * ");
            pw.println(r);
            if (dumpAll) {
                r.dump(pw, "    ");
                needSep = true;
            } else {
                pw.print("    app=");
                pw.println(r.app);
                pw.print("    created=");
                TimeUtils.formatDuration(r.createRealTime, nowReal, pw);
                pw.print(" started=");
                pw.print(r.startRequested);
                pw.print(" connections=");
                pw.println(r.connections.size());
                if (r.connections.size() > 0) {
                    pw.println("    Connections:");
                    for (int conni=0; conni<r.connections.size(); conni++) {
                        ArrayList<ConnectionRecord> clist = r.connections.valueAt(conni);
                        for (int i = 0; i < clist.size(); i++) {
                            ConnectionRecord conn = clist.get(i);
                            pw.print("      ");
                            pw.print(conn.binding.intent.intent.getIntent()
                                    .toShortString(false, false, false, false));
                            pw.print(" -> ");
                            ProcessRecord proc = conn.binding.client;
                            pw.println(proc != null ? proc.toShortString() : "null");
                        }
                    }
                }
            }
        }

        private void dumpServiceClient(ServiceRecord r) {
            final ProcessRecord proc = r.app;
            if (proc == null) {
                return;
            }
            final IApplicationThread thread = proc.thread;
            if (thread == null) {
                return;
            }
            pw.println("    Client:");
            pw.flush();
            try {
                TransferPipe tp = new TransferPipe();
                try {
                    thread.dumpService(tp.getWriteFd(), r, args);
                    tp.setBufferPrefix("      ");
                    // Short timeout, since blocking here can
                    // deadlock with the application.
                    tp.go(fd, 2000);
                } finally {
                    tp.kill();
                }
            } catch (IOException e) {
                pw.println("      Failure while dumping the service: " + e);
            } catch (RemoteException e) {
                pw.println("      Got a RemoteException while dumping the service");
            }
            needSep = true;
        }

        private void dumpUserRemainsLocked(int user) {
            ServiceMap smap = getServiceMapLocked(user);
            printed = false;
            for (int si=0, SN=smap.mDelayedStartList.size(); si<SN; si++) {
                ServiceRecord r = smap.mDelayedStartList.get(si);
                if (!matcher.match(r, r.name)) {
                    continue;
                }
                if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                    continue;
                }
                if (!printed) {
                    if (printedAnything) {
                        pw.println();
                    }
                    pw.println("  User " + user + " delayed start services:");
                    printed = true;
                }
                printedAnything = true;
                pw.print("  * Delayed start "); pw.println(r);
            }
            printed = false;
            for (int si=0, SN=smap.mStartingBackground.size(); si<SN; si++) {
                ServiceRecord r = smap.mStartingBackground.get(si);
                if (!matcher.match(r, r.name)) {
                    continue;
                }
                if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                    continue;
                }
                if (!printed) {
                    if (printedAnything) {
                        pw.println();
                    }
                    pw.println("  User " + user + " starting in background:");
                    printed = true;
                }
                printedAnything = true;
                pw.print("  * Starting bg "); pw.println(r);
            }
        }

        private void dumpRemainsLocked() {
            if (mPendingServices.size() > 0) {
                printed = false;
                for (int i=0; i<mPendingServices.size(); i++) {
                    ServiceRecord r = mPendingServices.get(i);
                    if (!matcher.match(r, r.name)) {
                        continue;
                    }
                    if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                        continue;
                    }
                    printedAnything = true;
                    if (!printed) {
                        if (needSep) pw.println();
                        needSep = true;
                        pw.println("  Pending services:");
                        printed = true;
                    }
                    pw.print("  * Pending "); pw.println(r);
                    r.dump(pw, "    ");
                }
                needSep = true;
            }

            if (mRestartingServices.size() > 0) {
                printed = false;
                for (int i=0; i<mRestartingServices.size(); i++) {
                    ServiceRecord r = mRestartingServices.get(i);
                    if (!matcher.match(r, r.name)) {
                        continue;
                    }
                    if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                        continue;
                    }
                    printedAnything = true;
                    if (!printed) {
                        if (needSep) pw.println();
                        needSep = true;
                        pw.println("  Restarting services:");
                        printed = true;
                    }
                    pw.print("  * Restarting "); pw.println(r);
                    r.dump(pw, "    ");
                }
                needSep = true;
            }

            if (mDestroyingServices.size() > 0) {
                printed = false;
                for (int i=0; i< mDestroyingServices.size(); i++) {
                    ServiceRecord r = mDestroyingServices.get(i);
                    if (!matcher.match(r, r.name)) {
                        continue;
                    }
                    if (dumpPackage != null && !dumpPackage.equals(r.appInfo.packageName)) {
                        continue;
                    }
                    printedAnything = true;
                    if (!printed) {
                        if (needSep) pw.println();
                        needSep = true;
                        pw.println("  Destroying services:");
                        printed = true;
                    }
                    pw.print("  * Destroy "); pw.println(r);
                    r.dump(pw, "    ");
                }
                needSep = true;
            }

            if (dumpAll) {
                printed = false;
                for (int ic=0; ic<mServiceConnections.size(); ic++) {
                    ArrayList<ConnectionRecord> r = mServiceConnections.valueAt(ic);
                    for (int i=0; i<r.size(); i++) {
                        ConnectionRecord cr = r.get(i);
                        if (!matcher.match(cr.binding.service, cr.binding.service.name)) {
                            continue;
                        }
                        if (dumpPackage != null && (cr.binding.client == null
                                || !dumpPackage.equals(cr.binding.client.info.packageName))) {
                            continue;
                        }
                        printedAnything = true;
                        if (!printed) {
                            if (needSep) pw.println();
                            needSep = true;
                            pw.println("  Connection bindings to services:");
                            printed = true;
                        }
                        pw.print("  * "); pw.println(cr);
                        cr.dump(pw, "    ");
                    }
                }
            }

            if (matcher.all) {
                final long nowElapsed = SystemClock.elapsedRealtime();
                final int[] users = mAm.mUserController.getUsers();
                for (int user : users) {
                    boolean printedUser = false;
                    ServiceMap smap = mServiceMap.get(user);
                    if (smap == null) {
                        continue;
                    }
                    for (int i = smap.mActiveForegroundApps.size() - 1; i >= 0; i--) {
                        ActiveForegroundApp aa = smap.mActiveForegroundApps.valueAt(i);
                        if (dumpPackage != null && !dumpPackage.equals(aa.mPackageName)) {
                            continue;
                        }
                        if (!printedUser) {
                            printedUser = true;
                            printedAnything = true;
                            if (needSep) pw.println();
                            needSep = true;
                            pw.print("Active foreground apps - user ");
                            pw.print(user);
                            pw.println(":");
                        }
                        pw.print("  #");
                        pw.print(i);
                        pw.print(": ");
                        pw.println(aa.mPackageName);
                        if (aa.mLabel != null) {
                            pw.print("    mLabel=");
                            pw.println(aa.mLabel);
                        }
                        pw.print("    mNumActive=");
                        pw.print(aa.mNumActive);
                        pw.print(" mAppOnTop=");
                        pw.print(aa.mAppOnTop);
                        pw.print(" mShownWhileTop=");
                        pw.print(aa.mShownWhileTop);
                        pw.print(" mShownWhileScreenOn=");
                        pw.println(aa.mShownWhileScreenOn);
                        pw.print("    mStartTime=");
                        TimeUtils.formatDuration(aa.mStartTime - nowElapsed, pw);
                        pw.print(" mStartVisibleTime=");
                        TimeUtils.formatDuration(aa.mStartVisibleTime - nowElapsed, pw);
                        pw.println();
                        if (aa.mEndTime != 0) {
                            pw.print("    mEndTime=");
                            TimeUtils.formatDuration(aa.mEndTime - nowElapsed, pw);
                            pw.println();
                        }
                    }
                    if (smap.hasMessagesOrCallbacks()) {
                        if (needSep) {
                            pw.println();
                        }
                        printedAnything = true;
                        needSep = true;
                        pw.print("  Handler - user ");
                        pw.print(user);
                        pw.println(":");
                        smap.dumpMine(new PrintWriterPrinter(pw), "    ");
                    }
                }
            }

            if (!printedAnything) {
                pw.println("  (nothing)");
            }
        }
    }

    ServiceDumper newServiceDumperLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, String dumpPackage) {
        return new ServiceDumper(fd, pw, args, opti, dumpAll, dumpPackage);
    }

    protected void writeToProto(ProtoOutputStream proto, long fieldId) {
        synchronized (mAm) {
            final long outterToken = proto.start(fieldId);
            int[] users = mAm.mUserController.getUsers();
            for (int user : users) {
                ServiceMap smap = mServiceMap.get(user);
                if (smap == null) {
                    continue;
                }
                long token = proto.start(ActiveServicesProto.SERVICES_BY_USERS);
                proto.write(ActiveServicesProto.ServicesByUser.USER_ID, user);
                ArrayMap<ComponentName, ServiceRecord> alls = smap.mServicesByName;
                for (int i=0; i<alls.size(); i++) {
                    alls.valueAt(i).writeToProto(proto,
                            ActiveServicesProto.ServicesByUser.SERVICE_RECORDS);
                }
                proto.end(token);
            }
            proto.end(outterToken);
        }
    }

    /**
     * There are three ways to call this:
     *  - no service specified: dump all the services
     *  - a flattened component name that matched an existing service was specified as the
     *    first arg: dump that one service
     *  - the first arg isn't the flattened component name of an existing service:
     *    dump all services whose component contains the first arg as a substring
     */
    protected boolean dumpService(FileDescriptor fd, PrintWriter pw, final String name,
            String[] args, int opti, boolean dumpAll) {
        final ArrayList<ServiceRecord> services = new ArrayList<>();

        final Predicate<ServiceRecord> filter = DumpUtils.filterRecord(name);

        synchronized (mAm) {
            int[] users = mAm.mUserController.getUsers();

            for (int user : users) {
                ServiceMap smap = mServiceMap.get(user);
                if (smap == null) {
                    continue;
                }
                ArrayMap<ComponentName, ServiceRecord> alls = smap.mServicesByName;
                for (int i=0; i<alls.size(); i++) {
                    ServiceRecord r1 = alls.valueAt(i);

                    if (filter.test(r1)) {
                        services.add(r1);
                    }
                }
            }
        }

        if (services.size() <= 0) {
            return false;
        }

        // Sort by component name.
        services.sort(Comparator.comparing(WithComponentName::getComponentName));

        boolean needSep = false;
        for (int i=0; i<services.size(); i++) {
            if (needSep) {
                pw.println();
            }
            needSep = true;
            dumpService("", fd, pw, services.get(i), args, dumpAll);
        }
        return true;
    }

    /**
     * Invokes IApplicationThread.dumpService() on the thread of the specified service if
     * there is a thread associated with the service.
     */
    private void dumpService(String prefix, FileDescriptor fd, PrintWriter pw,
            final ServiceRecord r, String[] args, boolean dumpAll) {
        String innerPrefix = prefix + "  ";
        synchronized (mAm) {
            pw.print(prefix); pw.print("SERVICE ");
                    pw.print(r.shortName); pw.print(" ");
                    pw.print(Integer.toHexString(System.identityHashCode(r)));
                    pw.print(" pid=");
                    if (r.app != null) pw.println(r.app.pid);
                    else pw.println("(not running)");
            if (dumpAll) {
                r.dump(pw, innerPrefix);
            }
        }
        if (r.app != null && r.app.thread != null) {
            pw.print(prefix); pw.println("  Client:");
            pw.flush();
            try {
                TransferPipe tp = new TransferPipe();
                try {
                    r.app.thread.dumpService(tp.getWriteFd(), r, args);
                    tp.setBufferPrefix(prefix + "    ");
                    tp.go(fd);
                } finally {
                    tp.kill();
                }
            } catch (IOException e) {
                pw.println(prefix + "    Failure while dumping the service: " + e);
            } catch (RemoteException e) {
                pw.println(prefix + "    Got a RemoteException while dumping the service");
            }
        }
    }
}
