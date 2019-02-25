/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.IActivityContainer;
import android.app.IActivityManager;
import android.app.IApplicationThread;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.ProfilerInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.voice.IVoiceInteractionSession;
import android.util.EventLog;
import android.util.EventLogTags;
import android.util.Slog;
import android.view.Display;

import com.android.internal.app.HeavyWeightSwitcherActivity;
import com.android.internal.app.IVoiceInteractor;
import com.android.server.am.ActivityStackSupervisor.PendingActivityLaunch;
import com.android.server.wm.WindowManagerService;

import java.util.ArrayList;

import static android.app.Activity.RESULT_CANCELED;
import static android.app.ActivityManager.START_CLASS_NOT_FOUND;
import static android.app.ActivityManager.START_DELIVERED_TO_TOP;
import static android.app.ActivityManager.START_FLAG_ONLY_IF_NEEDED;
import static android.app.ActivityManager.START_RETURN_INTENT_TO_CALLER;
import static android.app.ActivityManager.START_RETURN_LOCK_TASK_MODE_VIOLATION;
import static android.app.ActivityManager.START_SUCCESS;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.ActivityManager.StackId;
import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.HOME_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION;
import static android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION;
import static android.content.Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP;
import static android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT;
import static android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED;
import static android.content.Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP;
import static android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME;
import static android.content.pm.ActivityInfo.DOCUMENT_LAUNCH_ALWAYS;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_CONFIGURATION;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_FOCUS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PERMISSIONS_REVIEW;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_RECENTS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_RESULTS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_STACK;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_TASKS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_USER_LEAVING;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_CONFIGURATION;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_FOCUS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_RESULTS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_USER_LEAVING;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.ANIMATE;
import static com.android.server.am.ActivityRecord.APPLICATION_ACTIVITY_TYPE;
import static com.android.server.am.ActivityRecord.HOME_ACTIVITY_TYPE;
import static com.android.server.am.ActivityRecord.RECENTS_ACTIVITY_TYPE;
import static com.android.server.am.ActivityStack.ActivityState.RESUMED;
import static com.android.server.am.ActivityStack.STACK_INVISIBLE;
import static com.android.server.am.ActivityStackSupervisor.CREATE_IF_NEEDED;
import static com.android.server.am.ActivityStackSupervisor.FORCE_FOCUS;
import static com.android.server.am.ActivityStackSupervisor.ON_TOP;
import static com.android.server.am.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.am.ActivityStackSupervisor.TAG_TASKS;
import static com.android.server.am.EventLogTags.AM_NEW_INTENT;

/**
 * Controller for interpreting how and then launching activities.
 * <p>
 * This class collects all the logic for determining how an intent and flags should be turned into
 * an activity and associated task and stack.
 */
class ActivityStarter {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityStarter" : TAG_AM;
    private static final String TAG_RESULTS = TAG + POSTFIX_RESULTS;
    private static final String TAG_FOCUS = TAG + POSTFIX_FOCUS;
    private static final String TAG_CONFIGURATION = TAG + POSTFIX_CONFIGURATION;
    private static final String TAG_USER_LEAVING = TAG + POSTFIX_USER_LEAVING;

    // TODO b/30204367 remove when the platform fully supports ephemeral applications
    private static final boolean USE_DEFAULT_EPHEMERAL_LAUNCHER = false;

    private final ActivityManagerService mService;
    private final ActivityStackSupervisor mSupervisor;
    private ActivityStartInterceptor mInterceptor;
    private WindowManagerService mWindowManager;

    final ArrayList<PendingActivityLaunch> mPendingActivityLaunches = new ArrayList<>();

    // Share state variable among methods when starting an activity.
    private ActivityRecord mStartActivity;// 正在启动的Activity
    private ActivityRecord mReusedActivity;
    private Intent mIntent;
    private int mCallingUid;
    private ActivityOptions mOptions;

    private boolean mLaunchSingleTop;
    private boolean mLaunchSingleInstance;
    private boolean mLaunchSingleTask;
    private boolean mLaunchTaskBehind;
    private int mLaunchFlags;

    private Rect mLaunchBounds;

    private ActivityRecord mNotTop;
    private boolean mDoResume;
    private int mStartFlags;
    private ActivityRecord mSourceRecord;

    private TaskRecord mInTask;
    private boolean mAddingToTask;
    private TaskRecord mReuseTask;

    private ActivityInfo mNewTaskInfo;
    private Intent mNewTaskIntent;
    private ActivityStack mSourceStack;
    private ActivityStack mTargetStack;
    // Indicates that we moved other task and are going to put something on top soon, so
    // we don't want to show it redundantly or accidentally change what's shown below.
    private boolean mMovedOtherTask;
    private boolean mMovedToFront;
    private boolean mNoAnimation;
    private boolean mKeepCurTransition;
    private boolean mAvoidMoveToFront;
    private boolean mPowerHintSent;

    private IVoiceInteractionSession mVoiceSession;
    private IVoiceInteractor mVoiceInteractor;

    private void reset() {
        mStartActivity = null;
        mIntent = null;
        mCallingUid = -1;
        mOptions = null;

        mLaunchSingleTop = false;
        mLaunchSingleInstance = false;
        mLaunchSingleTask = false;
        mLaunchTaskBehind = false;
        mLaunchFlags = 0;

        mLaunchBounds = null;

        mNotTop = null;
        mDoResume = false;
        mStartFlags = 0;
        mSourceRecord = null;

        mInTask = null;
        mAddingToTask = false;
        mReuseTask = null;

        mNewTaskInfo = null;
        mNewTaskIntent = null;
        mSourceStack = null;

        mTargetStack = null;
        mMovedOtherTask = false;
        mMovedToFront = false;
        mNoAnimation = false;
        mKeepCurTransition = false;
        mAvoidMoveToFront = false;

        mVoiceSession = null;
        mVoiceInteractor = null;
    }

    ActivityStarter(ActivityManagerService service, ActivityStackSupervisor supervisor) {
        mService = service;
        mSupervisor = supervisor;
        mInterceptor = new ActivityStartInterceptor(mService, mSupervisor);
    }

    // Locked 代表非线程安全的，提醒我们必须保证这些函数是线程安全的，（因为他们涉及不可重入资源的处理）
    final int startActivityLocked(IApplicationThread caller, Intent intent, Intent ephemeralIntent,
                                  String resolvedType, ActivityInfo aInfo, ResolveInfo rInfo,
                                  IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
                                  IBinder resultTo, String resultWho, int requestCode, int callingPid, int callingUid,
                                  String callingPackage, int realCallingPid, int realCallingUid, int startFlags,
                                  ActivityOptions options, boolean ignoreTargetSecurity, boolean componentSpecified,
                                  ActivityRecord[] outActivity, ActivityStackSupervisor.ActivityContainer container,
                                  TaskRecord inTask) {
        int err = ActivityManager.START_SUCCESS;

        // 获取调用者进程记录对象，每一个进程都使用一个ProcessRecord对象来描述，并且会保存起来
        ProcessRecord callerApp = null;
        if (caller != null) {
            // mService指向AMS，通过caller来获取对应的ProcessRecord对象callerApp，参数caller指向
            // 启动Activity的组件所运行在的应用程序进程的一个ApplicationThread对象，因此ProcessRecord
            // 对象callerApp指向了启动者所在的应用程序进程（正在启动的为被调用者）。
            callerApp = mService.getRecordForAppLocked(caller);
            if (callerApp != null) {// 调用者进程存在
                callingPid = callerApp.pid;
                callingUid = callerApp.info.uid;
            } else {// 调用者被系统杀死或者意外退出
                Slog.w(TAG, "Unable to find app for caller " + caller
                        + " (pid=" + callingPid + ") when starting: "
                        + intent.toString());
                err = ActivityManager.START_PERMISSION_DENIED;
            }
        }

        final int userId = aInfo != null ? UserHandle.getUserId(aInfo.applicationInfo.uid) : 0;

        if (err == ActivityManager.START_SUCCESS) {
            Slog.i(TAG, "START u" + userId + " {" + intent.toShortString(true, true, true, false)
                    + "} from uid " + callingUid
                    + " on display " + (container == null ? (mSupervisor.mFocusedStack == null ?
                    Display.DEFAULT_DISPLAY : mSupervisor.mFocusedStack.mDisplayId) :
                    (container.mActivityDisplay == null ? Display.DEFAULT_DISPLAY :
                            container.mActivityDisplay.mDisplayId)));
        }

        ActivityRecord sourceRecord = null;// 调用者Activity封装
        ActivityRecord resultRecord = null;// 需要接受返回结果的Activity对象封装
        if (resultTo != null) {// 需要返回结果
            // 查找所有栈中是否存在对应resultTo（调用者）的ActivityRecord
            sourceRecord = mSupervisor.isInAnyStackLocked(resultTo);
            if (DEBUG_RESULTS) Slog.v(TAG_RESULTS,
                    "Will send result to " + resultTo + " " + sourceRecord);
            if (sourceRecord != null) {
                // 如果requestCode大于等于0（也就是需要返回结果的启动Activity），
                // 并且请求者的Activity没有在等待finish队列中
                if (requestCode >= 0 && !sourceRecord.finishing) {
                    // 将启动者的Activity作为接受结果的Activity
                    resultRecord = sourceRecord;
                }
            }
        }

        final int launchFlags = intent.getFlags();

        // 直接启动Activity时requestCode为-1，因此sourceRecord为空，resultRecord为空
        // 这里对标志位Intent.FLAG_ACTIVITY_FORWARD_RESULT进行了判断，我们先解释一下这个标志位：如果A启动了B
        // 并且需要返回结果，而B需要启动了C从C返回结果给A，那么B需要设置Intent.FLAG_ACTIVITY_FORWARD_RESULT标志
        // 位，并且为了避免冲突B在启动C时不需要再设置requestCode，而此时sourceRecord是B，resultRecord是A，
        // 就是下面代码的解释，设置该标志后，C调用setResult时结果不会传递给B而是传递给A。
        if ((launchFlags & Intent.FLAG_ACTIVITY_FORWARD_RESULT) != 0 && sourceRecord != null) {
            // Transfer the result target from the source activity to the new
            // one being started, including any failures.
            // 这里requestCode是B传过来的，如果设置了上面的标签，B就不能再设置requestCode，因此，如果
            // requestCode>=0就会产生冲突，因此B不能再设置requestCode
            if (requestCode >= 0) {
                ActivityOptions.abort(options);
                return ActivityManager.START_FORWARD_AND_REQUEST_CONFLICT;
            }
            // 如果设置了上面的标签，最终的接受结果的Activity就是B（sourceRecord）中resultTo指向的Activity而不是B
            resultRecord = sourceRecord.resultTo;
            // 如果启动者resultRecord不在栈中，赋值为空
            if (resultRecord != null && !resultRecord.isInStackLocked()) {
                resultRecord = null;
            }
            resultWho = sourceRecord.resultWho;
            requestCode = sourceRecord.requestCode;// 传递requestCode
            sourceRecord.resultTo = null;
            if (resultRecord != null) {// 还在栈中
                resultRecord.removeResultsLocked(sourceRecord, resultWho, requestCode);
            }
            if (sourceRecord.launchedFromUid == callingUid) {
                // The new activity is being launched from the same uid as the previous
                // activity in the flow, and asking to forward its result back to the
                // previous.  In this case the activity is serving as a trampoline（蹦床） between
                // the two, so we also want to update its launchedFromPackage to be the
                // same as the previous activity.  Note that this is safe, since we know
                // these two packages come from the same uid; the caller could just as
                // well have supplied that same package name itself.  This specifially
                // deals with the case of an intent picker/chooser being launched in the app
                // flow to redirect to an activity picked by the user, where we want the final
                // activity to consider it to have been launched by the previous app activity.
                callingPackage = sourceRecord.launchedFromPackage;
            }
        }

        // 找不到Component
        if (err == ActivityManager.START_SUCCESS && intent.getComponent() == null) {
            // We couldn't find a class that can handle the given Intent.
            // That's the end of that!
            err = ActivityManager.START_INTENT_NOT_RESOLVED;
        }

        // 从Intent中无法找到相应的ActivityInfo
        if (err == ActivityManager.START_SUCCESS && aInfo == null) {
            // We couldn't find the specific class specified in the Intent.
            // Also the end of the line.
            err = ActivityManager.START_CLASS_NOT_FOUND;
        }

        if (err == ActivityManager.START_SUCCESS && sourceRecord != null
                && sourceRecord.task.voiceSession != null) {
            // If this activity is being launched as part of a voice session, we need
            // to ensure that it is safe to do so.  If the upcoming activity will also
            // be part of the voice session, we can only launch it if it has explicitly
            // said it supports the VOICE category, or it is a part of the calling app.
            if ((launchFlags & FLAG_ACTIVITY_NEW_TASK) == 0
                    && sourceRecord.info.applicationInfo.uid != aInfo.applicationInfo.uid) {
                try {
                    intent.addCategory(Intent.CATEGORY_VOICE);
                    if (!AppGlobals.getPackageManager().activitySupportsIntent(
                            intent.getComponent(), intent, resolvedType)) {
                        Slog.w(TAG,
                                "Activity being started in current voice task does not support voice: "
                                        + intent);
                        err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
                    }
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failure checking voice capabilities", e);
                    err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
                }
            }
        }

        if (err == ActivityManager.START_SUCCESS && voiceSession != null) {
            // If the caller is starting a new voice session, just make sure the target
            // is actually allowing it to run this way.
            try {
                if (!AppGlobals.getPackageManager().activitySupportsIntent(intent.getComponent(),
                        intent, resolvedType)) {
                    Slog.w(TAG,
                            "Activity being started in new voice task does not support: "
                                    + intent);
                    err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Failure checking voice capabilities", e);
                err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
            }
        }

        // 不需要返回结果直接启动Activity时resultRecord为空，否则不为空
        final ActivityStack resultStack = resultRecord == null ? null : resultRecord.task.stack;

        if (err != START_SUCCESS) {// 失败
            if (resultRecord != null) {
                // 需要返回结果的启动Activity，调用Activity.onActivityResult，返回操作取消的结果
                resultStack.sendActivityResultLocked(
                        -1, resultRecord, resultWho, requestCode, RESULT_CANCELED, null);
            }
            ActivityOptions.abort(options);
            return err;
        }

        // 检测权限
        boolean abort = !mSupervisor.checkStartAnyActivityPermission(intent, aInfo, resultWho,
                requestCode, callingPid, callingUid, callingPackage, ignoreTargetSecurity, callerApp,
                resultRecord, resultStack, options);
        abort |= !mService.mIntentFirewall.checkStartActivity(intent, callingUid,
                callingPid, resolvedType, aInfo.applicationInfo);

        if (mService.mController != null) {
            try {
                // The Intent we give to the watcher has the extra data
                // stripped off, since it can contain private information.
                Intent watchIntent = intent.cloneFilter();
                abort |= !mService.mController.activityStarting(watchIntent,
                        aInfo.applicationInfo.packageName);
            } catch (RemoteException e) {
                mService.mController = null;
            }
        }

        mInterceptor.setStates(userId, realCallingPid, realCallingUid, startFlags, callingPackage);
        mInterceptor.intercept(intent, rInfo, aInfo, resolvedType, inTask, callingPid, callingUid,
                options);
        intent = mInterceptor.mIntent;
        rInfo = mInterceptor.mRInfo;
        aInfo = mInterceptor.mAInfo;
        resolvedType = mInterceptor.mResolvedType;
        inTask = mInterceptor.mInTask;
        callingPid = mInterceptor.mCallingPid;
        callingUid = mInterceptor.mCallingUid;
        options = mInterceptor.mActivityOptions;
        if (abort) {// 如果终止
            if (resultRecord != null) {
                // 等待返回结果的Activity，调用Activity.onActivityResult方法，返回取消操作的结果
                resultStack.sendActivityResultLocked(-1, resultRecord, resultWho, requestCode,
                        RESULT_CANCELED, null);
            }
            // We pretend to the caller that it was really started, but
            // they will just get a cancel result.
            ActivityOptions.abort(options);
            return START_SUCCESS;
        }

        // If permissions need a review before any of the app components can run, we
        // launch the review activity and pass a pending intent to start the activity
        // we are to launching now after the review is completed.
        if (Build.PERMISSIONS_REVIEW_REQUIRED && aInfo != null) {
            if (mService.getPackageManagerInternalLocked().isPermissionsReviewRequired(
                    aInfo.packageName, userId)) {
                IIntentSender target = mService.getIntentSenderLocked(
                        ActivityManager.INTENT_SENDER_ACTIVITY, callingPackage,
                        callingUid, userId, null, null, 0, new Intent[]{intent},
                        new String[]{resolvedType}, PendingIntent.FLAG_CANCEL_CURRENT
                                | PendingIntent.FLAG_ONE_SHOT, null);

                final int flags = intent.getFlags();
                Intent newIntent = new Intent(Intent.ACTION_REVIEW_PERMISSIONS);
                newIntent.setFlags(flags
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                newIntent.putExtra(Intent.EXTRA_PACKAGE_NAME, aInfo.packageName);
                newIntent.putExtra(Intent.EXTRA_INTENT, new IntentSender(target));
                if (resultRecord != null) {
                    newIntent.putExtra(Intent.EXTRA_RESULT_NEEDED, true);
                }
                intent = newIntent;

                resolvedType = null;
                callingUid = realCallingUid;
                callingPid = realCallingPid;

                rInfo = mSupervisor.resolveIntent(intent, resolvedType, userId);
                aInfo = mSupervisor.resolveActivity(intent, rInfo, startFlags,
                        null /*profilerInfo*/);

                if (DEBUG_PERMISSIONS_REVIEW) {
                    Slog.i(TAG, "START u" + userId + " {" + intent.toShortString(true, true,
                            true, false) + "} from uid " + callingUid + " on display "
                            + (container == null ? (mSupervisor.mFocusedStack == null ?
                            Display.DEFAULT_DISPLAY : mSupervisor.mFocusedStack.mDisplayId) :
                            (container.mActivityDisplay == null ? Display.DEFAULT_DISPLAY :
                                    container.mActivityDisplay.mDisplayId)));
                }
            }
        }

        // If we have an ephemeral（短暂的） app, abort the process of launching the resolved intent.
        // Instead, launch the ephemeral installer. Once the installer is finished, it
        // starts either the intent we resolved here [on install error] or the ephemeral
        // app [on install success].
        if (rInfo != null && rInfo.ephemeralResolveInfo != null) {
            intent = buildEphemeralInstallerIntent(intent, ephemeralIntent,
                    rInfo.ephemeralResolveInfo.getPackageName(), callingPackage, resolvedType,
                    userId);
            resolvedType = null;
            callingUid = realCallingUid;
            callingPid = realCallingPid;

            aInfo = mSupervisor.resolveActivity(intent, rInfo, startFlags, null /*profilerInfo*/);
        }

        // 创建一个ActivityRecord对象r来描述即将被启动的Activity组件
        ActivityRecord r = new ActivityRecord(mService, callerApp, callingUid, callingPackage,
                intent, resolvedType, aInfo, mService.mConfiguration, resultRecord, resultWho,
                requestCode, componentSpecified, voiceSession != null, mSupervisor, container,
                options, sourceRecord);
        if (outActivity != null) {
            outActivity[0] = r;
        }

        if (r.appTimeTracker == null && sourceRecord != null) {
            // If the caller didn't specify an explicit time tracker, we want to continue
            // tracking under any it has.
            r.appTimeTracker = sourceRecord.appTimeTracker;
        }

        // 获取当前有焦点的栈
        final ActivityStack stack = mSupervisor.mFocusedStack;
        // 前面voiceSession传入为空，并且没有可恢复的Activity或者可恢复的Activity不是当前调用者
        if (voiceSession == null && (stack.mResumedActivity == null
                || stack.mResumedActivity.info.applicationInfo.uid != callingUid)) {
            // 前台栈(stack)还没有resume状态的Activity时, 则检查app切换是否允许，不允许切换则要放入等待列表
            if (!mService.checkAppSwitchAllowedLocked(callingPid, callingUid,
                    realCallingPid, realCallingUid, "Activity start")) {
                // 不许切换时，获取启动新Activity请求的描述，放到等待列表
                PendingActivityLaunch pal = new PendingActivityLaunch(r,
                        sourceRecord, startFlags, stack, callerApp);
                // 添加PendingActivityLaunch
                mPendingActivityLaunches.add(pal);
                ActivityOptions.abort(options);
                return ActivityManager.START_SWITCHES_CANCELED;
            }
        }

        // 允许切换
        if (mService.mDidAppSwitch) {
            // This is the second allowed switch since we stopped switches,
            // so now just generally allow switches.  Use case: user presses
            // home (switches disabled, switch to home, mDidAppSwitch now true);
            // user taps a home icon (coming from home so allowed, we hit here
            // and now allow anyone to switch again).
            mService.mAppSwitchesAllowedTime = 0;// 将切换时间设置为0
        } else {
            mService.mDidAppSwitch = true;
        }

        // 处理等待启动的Activity
        doPendingActivityLaunchesLocked(false);

        try {
            mService.mWindowManager.deferSurfaceLayout();
            // 启动目标Activity操作
            err = startActivityUnchecked(r, sourceRecord, voiceSession, voiceInteractor, startFlags,
                    true, options, inTask);
        } finally {
            mService.mWindowManager.continueSurfaceLayout();
        }
        postStartActivityUncheckedProcessing(r, err, stack.mStackId, mSourceRecord, mTargetStack);
        return err;
    }

    /**
     * Builds and returns an intent to launch the ephemeral installer.
     */
    private Intent buildEphemeralInstallerIntent(Intent launchIntent, Intent origIntent,
                                                 String ephemeralPackage, String callingPackage, String resolvedType, int userId) {
        final Intent nonEphemeralIntent = new Intent(origIntent);
        nonEphemeralIntent.setFlags(nonEphemeralIntent.getFlags() | Intent.FLAG_IGNORE_EPHEMERAL);
        // Intent that is launched if the ephemeral package couldn't be installed
        // for any reason.
        final IIntentSender failureIntentTarget = mService.getIntentSenderLocked(
                ActivityManager.INTENT_SENDER_ACTIVITY, callingPackage,
                Binder.getCallingUid(), userId, null /*token*/, null /*resultWho*/, 1,
                new Intent[]{nonEphemeralIntent}, new String[]{resolvedType},
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
                        | PendingIntent.FLAG_IMMUTABLE, null /*bOptions*/);

        final Intent ephemeralIntent;
        if (USE_DEFAULT_EPHEMERAL_LAUNCHER) {
            // Force the intent to be directed to the ephemeral package
            ephemeralIntent = new Intent(origIntent);
            ephemeralIntent.setPackage(ephemeralPackage);
        } else {
            // Success intent goes back to the installer
            ephemeralIntent = new Intent(launchIntent);
        }

        // Intent that is eventually launched if the ephemeral package was
        // installed successfully. This will actually be launched by a platform
        // broadcast receiver.
        final IIntentSender successIntentTarget = mService.getIntentSenderLocked(
                ActivityManager.INTENT_SENDER_ACTIVITY, callingPackage,
                Binder.getCallingUid(), userId, null /*token*/, null /*resultWho*/, 0,
                new Intent[]{ephemeralIntent}, new String[]{resolvedType},
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
                        | PendingIntent.FLAG_IMMUTABLE, null /*bOptions*/);

        // Finally build the actual intent to launch the ephemeral installer
        int flags = launchIntent.getFlags();
        final Intent intent = new Intent();
        intent.setFlags(flags
                | Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_NO_HISTORY
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, ephemeralPackage);
        intent.putExtra(Intent.EXTRA_EPHEMERAL_FAILURE, new IntentSender(failureIntentTarget));
        intent.putExtra(Intent.EXTRA_EPHEMERAL_SUCCESS, new IntentSender(successIntentTarget));
        // TODO: Remove when the platform has fully implemented ephemeral apps
        intent.setData(origIntent.getData().buildUpon().clearQuery().build());
        return intent;
    }

    void postStartActivityUncheckedProcessing(
            ActivityRecord r, int result, int prevFocusedStackId, ActivityRecord sourceRecord,
            ActivityStack targetStack) {

        if (result < START_SUCCESS) {
            // If someone asked to have the keyguard dismissed on the next activity start,
            // but we are not actually doing an activity switch...  just dismiss the keyguard now,
            // because we probably want to see whatever is behind it.
            mSupervisor.notifyActivityDrawnForKeyguard();
            return;
        }

        // We're waiting for an activity launch to finish, but that activity simply
        // brought another activity to front. Let startActivityMayWait() know about
        // this, so it waits for the new activity to become visible instead.
        if (result == START_TASK_TO_FRONT && !mSupervisor.mWaitingActivityLaunched.isEmpty()) {
            mSupervisor.reportTaskToFrontNoLaunch(mStartActivity);
        }

        int startedActivityStackId = INVALID_STACK_ID;
        if (r.task != null && r.task.stack != null) {
            startedActivityStackId = r.task.stack.mStackId;
        } else if (mTargetStack != null) {
            startedActivityStackId = targetStack.mStackId;
        }

        // If we launched the activity from a no display activity that was launched from the home
        // screen, we also need to start recents to un-minimize the docked stack, since the
        // noDisplay activity will be finished shortly after.
        // Note that some apps have trampoline activities without noDisplay being set. In that case,
        // we have another heuristic in DockedStackDividerController.notifyAppTransitionStarting
        // that tries to detect that case.
        // TODO: We should prevent noDisplay activities from affecting task/stack ordering and
        // visibility instead of using this flag.
        final boolean noDisplayActivityOverHome = sourceRecord != null
                && sourceRecord.noDisplay
                && sourceRecord.task.getTaskToReturnTo() == HOME_ACTIVITY_TYPE;
        if (startedActivityStackId == DOCKED_STACK_ID
                && (prevFocusedStackId == HOME_STACK_ID || noDisplayActivityOverHome)) {
            final ActivityStack homeStack = mSupervisor.getStack(HOME_STACK_ID);
            final ActivityRecord topActivityHomeStack = homeStack != null
                    ? homeStack.topRunningActivityLocked() : null;
            if (topActivityHomeStack == null
                    || topActivityHomeStack.mActivityType != RECENTS_ACTIVITY_TYPE) {
                // We launch an activity while being in home stack, which means either launcher or
                // recents into docked stack. We don't want the launched activity to be alone in a
                // docked stack, so we want to immediately launch recents too.
                if (DEBUG_RECENTS) Slog.d(TAG, "Scheduling recents launch.");
                mWindowManager.showRecentApps(true /* fromHome */);
                return;
            }
        }

        if (startedActivityStackId == PINNED_STACK_ID
                && (result == START_TASK_TO_FRONT || result == START_DELIVERED_TO_TOP)) {
            // The activity was already running in the pinned stack so it wasn't started, but either
            // brought to the front or the new intent was delivered to it since it was already in
            // front. Notify anyone interested in this piece of information.
            mService.notifyPinnedActivityRestartAttemptLocked();
            return;
        }
    }

    void startHomeActivityLocked(Intent intent, ActivityInfo aInfo, String reason) {
        mSupervisor.moveHomeStackTaskToTop(HOME_ACTIVITY_TYPE, reason);
        startActivityLocked(null /*caller*/, intent, null /*ephemeralIntent*/,
                null /*resolvedType*/, aInfo, null /*rInfo*/, null /*voiceSession*/,
                null /*voiceInteractor*/, null /*resultTo*/, null /*resultWho*/,
                0 /*requestCode*/, 0 /*callingPid*/, 0 /*callingUid*/, null /*callingPackage*/,
                0 /*realCallingPid*/, 0 /*realCallingUid*/, 0 /*startFlags*/, null /*options*/,
                false /*ignoreTargetSecurity*/, false /*componentSpecified*/, null /*outActivity*/,
                null /*container*/, null /*inTask*/);
        if (mSupervisor.inResumeTopActivity) {
            // If we are in resume section already, home activity will be initialized, but not
            // resumed (to avoid recursive resume) and will stay that way until something pokes it
            // again. We need to schedule another resume.
            mSupervisor.scheduleResumeTopActivities();
        }
    }

    void showConfirmDeviceCredential(int userId) {
        // First, retrieve the stack that we want to resume after credential is confirmed.
        ActivityStack targetStack;
        ActivityStack fullscreenStack =
                mSupervisor.getStack(FULLSCREEN_WORKSPACE_STACK_ID);
        ActivityStack freeformStack =
                mSupervisor.getStack(FREEFORM_WORKSPACE_STACK_ID);
        if (fullscreenStack != null &&
                fullscreenStack.getStackVisibilityLocked(null) != ActivityStack.STACK_INVISIBLE) {
            // Single window case and the case that the docked stack is shown with fullscreen stack.
            targetStack = fullscreenStack;
        } else if (freeformStack != null &&
                freeformStack.getStackVisibilityLocked(null) != ActivityStack.STACK_INVISIBLE) {
            targetStack = freeformStack;
        } else {
            // The case that the docked stack is shown with recent.
            targetStack = mSupervisor.getStack(HOME_STACK_ID);
        }
        if (targetStack == null) {
            return;
        }
        final KeyguardManager km = (KeyguardManager) mService.mContext
                .getSystemService(Context.KEYGUARD_SERVICE);
        final Intent credential =
                km.createConfirmDeviceCredentialIntent(null, null, userId);
        // For safety, check null here in case users changed the setting after the checking.
        if (credential == null) {
            return;
        }
        final ActivityRecord activityRecord = targetStack.topRunningActivityLocked();
        if (activityRecord != null) {
            final IIntentSender target = mService.getIntentSenderLocked(
                    ActivityManager.INTENT_SENDER_ACTIVITY,
                    activityRecord.launchedFromPackage,
                    activityRecord.launchedFromUid,
                    activityRecord.userId,
                    null, null, 0,
                    new Intent[]{activityRecord.intent},
                    new String[]{activityRecord.resolvedType},
                    PendingIntent.FLAG_CANCEL_CURRENT |
                            PendingIntent.FLAG_ONE_SHOT |
                            PendingIntent.FLAG_IMMUTABLE,
                    null);
            credential.putExtra(Intent.EXTRA_INTENT, new IntentSender(target));
            // Show confirm credentials activity.
            startConfirmCredentialIntent(credential);
        }
    }

    void startConfirmCredentialIntent(Intent intent) {
        intent.addFlags(FLAG_ACTIVITY_NEW_TASK |
                FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS |
                FLAG_ACTIVITY_TASK_ON_HOME);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchTaskId(mSupervisor.getHomeActivity().task.taskId);
        mService.mContext.startActivityAsUser(intent, options.toBundle(),
                UserHandle.CURRENT);
    }

    // 该函数的Wait表示对于outResult的处理上，我们启动Activity时inTask为空
    final int startActivityMayWait(IApplicationThread caller, int callingUid,
                                   String callingPackage, Intent intent, String resolvedType,
                                   IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
                                   IBinder resultTo, String resultWho, int requestCode, int startFlags,
                                   ProfilerInfo profilerInfo, IActivityManager.WaitResult outResult, Configuration config,
                                   Bundle bOptions, boolean ignoreTargetSecurity, int userId,
                                   IActivityContainer iContainer, TaskRecord inTask) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        // 通知ActivityMetricsLogger记录时间
        mSupervisor.mActivityMetricsLogger.notifyActivityLaunching();
        // 是否指定了要启动Activity相关的组件名，如果指定了组件名则为显式启动，否则为隐式启动
        boolean componentSpecified = intent.getComponent() != null;

        // Save a copy in case ephemeral（短暂的） needs it
        final Intent ephemeralIntent = new Intent(intent);
        // Don't modify the client's object!
        intent = new Intent(intent);// 创建新的Intent

        // 收集Intent中的信息，如果启动Activity，会收集到Activity的信息，启动服务就会收集到服务的信息，
        // 还可能包括广播，ContentProvider等，收集到的信息时被调用者的信息。
        ResolveInfo rInfo = mSupervisor.resolveIntent(intent, resolvedType, userId);
        if (rInfo == null) {// 获取失败，说明没有对应的信息
            // 根据调用者用户id，获取用户信息
            UserInfo userInfo = mSupervisor.getUserInfo(userId);
            if (userInfo != null && userInfo.isManagedProfile()) {
                // Special case for managed profiles, if attempting to launch non-cryto aware
                // app in a locked managed profile from an unlocked parent allow it to resolve
                // as user will be sent via confirm credentials to unlock the profile.
                UserManager userManager = UserManager.get(mService.mContext);
                boolean profileLockedAndParentUnlockingOrUnlocked = false;
                long token = Binder.clearCallingIdentity();
                try {
                    UserInfo parent = userManager.getProfileParent(userId);
                    profileLockedAndParentUnlockingOrUnlocked = (parent != null)
                            && userManager.isUserUnlockingOrUnlocked(parent.id)
                            && !userManager.isUserUnlockingOrUnlocked(userId);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
                if (profileLockedAndParentUnlockingOrUnlocked) {
                    // 再次获取ResolveInfo
                    rInfo = mSupervisor.resolveIntent(intent, resolvedType, userId,
                            PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
                }
            }
        }
        // Collect information about the target of the Intent.
        // 收集Intent中所有指向Activity的信息，
        ActivityInfo aInfo = mSupervisor.resolveActivity(intent, rInfo, startFlags, profilerInfo);

        ActivityOptions options = ActivityOptions.fromBundle(bOptions);
        ActivityStackSupervisor.ActivityContainer container =
                (ActivityStackSupervisor.ActivityContainer) iContainer;
        synchronized (mService) {
            if (container != null && container.mParentActivity != null &&
                    container.mParentActivity.state != RESUMED) {
                // Cannot start a child activity if the parent is not resumed.
                return ActivityManager.START_CANCELED;
            }
            final int realCallingPid = Binder.getCallingPid();
            final int realCallingUid = Binder.getCallingUid();
            int callingPid;
            if (callingUid >= 0) {
                callingPid = -1;
            } else if (caller == null) {
                callingPid = realCallingPid;
                callingUid = realCallingUid;
            } else {
                callingPid = callingUid = -1;
            }

            final ActivityStack stack;// 获取Activity栈管理
            if (container == null || container.mStack.isOnHomeDisplay()) {// Activity还没有被启动时container是空
                stack = mSupervisor.mFocusedStack;// 获取当前有焦点的栈（有焦点的栈就是正在接受输入事件或者正在启动另外一个Activity的栈）
            } else {
                stack = container.mStack;
            }
            stack.mConfigWillChange = config != null && mService.mConfiguration.diff(config) != 0;
            if (DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                    "Starting activity when config will change = " + stack.mConfigWillChange);

            final long origId = Binder.clearCallingIdentity();

            // 被启动的Activity是第一次启动时，是没有设置标签的，所以下面不会走，如果被启动的Activity已经存在了，
            // 并且被设置了下面标签就会执行。PRIVATE_FLAG_CANT_SAVE_STATE是用于声明App是否享受系统提供的
            // Activity状态保存/恢复功能的。但是似乎没有App能成为heavy-weight process，因为PackageParser的
            // parseApplication方法并不会解析该标签。设置这个属性主要是为了软件的流程性
            if (aInfo != null &&
                    (aInfo.applicationInfo.privateFlags
                            & ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE) != 0) {
                // This may be a heavy-weight process!  Check to see if we already
                // have another, different heavy-weight process running.
                // 判断进程名和该应用包名是否一样（manifest中没有设置进程名则一样）
                if (aInfo.processName.equals(aInfo.applicationInfo.packageName)) {
                    // 获取heavy-weight进程，一个进程必须被设置PRIVATE_FLAG_CANT_SAVE_STATE标签才能作为
                    // heavy-weight进程，判断是不是heavy-weight进程是在
                    // ActivityStackSupervisor.realStartActivityLocked方法中
                    final ProcessRecord heavy = mService.mHeavyWeightProcess;
                    // 要启动的Activity的进程和当前进程是否是同一个进程
                    if (heavy != null && (heavy.info.uid != aInfo.applicationInfo.uid
                            || !heavy.processName.equals(aInfo.processName))) {// 如果不是同一个进程，需要给Intent重新赋值
                        int appCallingUid = callingUid;
                        if (caller != null) {// 调用者不为空
                            // 根据caller从缓存中查找对应的进程(正在启动另个一Activity的源Activity所在进程)
                            ProcessRecord callerApp = mService.getRecordForAppLocked(caller);
                            if (callerApp != null) {
                                appCallingUid = callerApp.info.uid;
                            } else {// 如果不存在说明进程被结束掉了，则需要中断启动。
                                Slog.w(TAG, "Unable to find app for caller " + caller
                                        + " (pid=" + callingPid + ") when starting: "
                                        + intent.toString());
                                ActivityOptions.abort(options);
                                return ActivityManager.START_PERMISSION_DENIED;
                            }
                        }

                        IIntentSender target = mService.getIntentSenderLocked(
                                ActivityManager.INTENT_SENDER_ACTIVITY, "android",
                                appCallingUid, userId, null, null, 0, new Intent[]{intent},
                                new String[]{resolvedType}, PendingIntent.FLAG_CANCEL_CURRENT
                                        | PendingIntent.FLAG_ONE_SHOT, null);

                        Intent newIntent = new Intent();
                        if (requestCode >= 0) {// 需要返回结果
                            // Caller is requesting a result.
                            newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_HAS_RESULT, true);
                        }
                        newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_INTENT,
                                new IntentSender(target));
                        if (heavy.activities.size() > 0) {// 当前进程中的Activity数量大于0
                            // 将第一个Activity放到启动Intent中
                            ActivityRecord hist = heavy.activities.get(0);
                            newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_CUR_APP,
                                    hist.packageName);
                            newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_CUR_TASK,
                                    hist.task.taskId);
                        }
                        newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_NEW_APP,
                                aInfo.packageName);
                        newIntent.setFlags(intent.getFlags());
                        newIntent.setClassName("android",
                                HeavyWeightSwitcherActivity.class.getName());
                        intent = newIntent;
                        resolvedType = null;
                        caller = null;
                        callingUid = Binder.getCallingUid();
                        callingPid = Binder.getCallingPid();
                        // 表示指定了组件，也就是显式启动
                        componentSpecified = true;
                        rInfo = mSupervisor.resolveIntent(intent, null /*resolvedType*/, userId);
                        aInfo = rInfo != null ? rInfo.activityInfo : null;
                        if (aInfo != null) {
                            aInfo = mService.getActivityInfoForUser(aInfo, userId);
                        }
                    }
                }
            }

            final ActivityRecord[] outRecord = new ActivityRecord[1];
            int res = startActivityLocked(caller, intent, ephemeralIntent, resolvedType,
                    aInfo, rInfo, voiceSession, voiceInteractor,
                    resultTo, resultWho, requestCode, callingPid,
                    callingUid, callingPackage, realCallingPid, realCallingUid, startFlags,
                    options, ignoreTargetSecurity, componentSpecified, outRecord, container,
                    inTask);

            Binder.restoreCallingIdentity(origId);

            if (stack.mConfigWillChange) {
                // If the caller also wants to switch to a new configuration,
                // do so now.  This allows a clean switch, as we are waiting
                // for the current activity to pause (so we will not destroy
                // it), and have not yet started the next activity.
                mService.enforceCallingPermission(android.Manifest.permission.CHANGE_CONFIGURATION,
                        "updateConfiguration()");
                stack.mConfigWillChange = false;
                if (DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                        "Updating to new configuration after starting activity.");
                mService.updateConfigurationLocked(config, null, false);
            }

            if (outResult != null) {
                outResult.result = res;
                if (res == ActivityManager.START_SUCCESS) {
                    mSupervisor.mWaitingActivityLaunched.add(outResult);
                    do {
                        try {
                            mService.wait();
                        } catch (InterruptedException e) {
                        }
                    } while (outResult.result != START_TASK_TO_FRONT
                            && !outResult.timeout && outResult.who == null);
                    if (outResult.result == START_TASK_TO_FRONT) {
                        res = START_TASK_TO_FRONT;
                    }
                }
                if (res == START_TASK_TO_FRONT) {
                    ActivityRecord r = stack.topRunningActivityLocked();
                    if (r.nowVisible && r.state == RESUMED) {
                        outResult.timeout = false;
                        outResult.who = new ComponentName(r.info.packageName, r.info.name);
                        outResult.totalTime = 0;
                        outResult.thisTime = 0;
                    } else {
                        outResult.thisTime = SystemClock.uptimeMillis();
                        mSupervisor.mWaitingActivityVisible.add(outResult);
                        do {
                            try {
                                mService.wait();
                            } catch (InterruptedException e) {
                            }
                        } while (!outResult.timeout && outResult.who == null);
                    }
                }
            }

            final ActivityRecord launchedActivity = mReusedActivity != null
                    ? mReusedActivity : outRecord[0];
            mSupervisor.mActivityMetricsLogger.notifyActivityLaunched(res, launchedActivity);
            return res;
        }
    }

    final int startActivities(IApplicationThread caller, int callingUid, String callingPackage,
                              Intent[] intents, String[] resolvedTypes, IBinder resultTo,
                              Bundle bOptions, int userId) {
        if (intents == null) {
            throw new NullPointerException("intents is null");
        }
        if (resolvedTypes == null) {
            throw new NullPointerException("resolvedTypes is null");
        }
        if (intents.length != resolvedTypes.length) {
            throw new IllegalArgumentException("intents are length different than resolvedTypes");
        }

        final int realCallingPid = Binder.getCallingPid();
        final int realCallingUid = Binder.getCallingUid();

        int callingPid;
        if (callingUid >= 0) {
            callingPid = -1;
        } else if (caller == null) {
            callingPid = realCallingPid;
            callingUid = realCallingUid;
        } else {
            callingPid = callingUid = -1;
        }
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mService) {
                ActivityRecord[] outActivity = new ActivityRecord[1];
                for (int i = 0; i < intents.length; i++) {
                    Intent intent = intents[i];
                    if (intent == null) {
                        continue;
                    }

                    // Refuse possible leaked file descriptors
                    if (intent != null && intent.hasFileDescriptors()) {
                        throw new IllegalArgumentException("File descriptors passed in Intent");
                    }

                    boolean componentSpecified = intent.getComponent() != null;

                    // Don't modify the client's object!
                    intent = new Intent(intent);

                    // Collect information about the target of the Intent.
                    ActivityInfo aInfo = mSupervisor.resolveActivity(intent, resolvedTypes[i], 0,
                            null, userId);
                    // TODO: New, check if this is correct
                    aInfo = mService.getActivityInfoForUser(aInfo, userId);

                    if (aInfo != null &&
                            (aInfo.applicationInfo.privateFlags
                                    & ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE) != 0) {
                        throw new IllegalArgumentException(
                                "FLAG_CANT_SAVE_STATE not supported here");
                    }

                    ActivityOptions options = ActivityOptions.fromBundle(
                            i == intents.length - 1 ? bOptions : null);
                    int res = startActivityLocked(caller, intent, null /*ephemeralIntent*/,
                            resolvedTypes[i], aInfo, null /*rInfo*/, null, null, resultTo, null, -1,
                            callingPid, callingUid, callingPackage,
                            realCallingPid, realCallingUid, 0,
                            options, false, componentSpecified, outActivity, null, null);
                    if (res < 0) {
                        return res;
                    }

                    resultTo = outActivity[0] != null ? outActivity[0].appToken : null;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        return START_SUCCESS;
    }

    void sendPowerHintForLaunchStartIfNeeded(boolean forceSend) {
        // Trigger launch power hint if activity being launched is not in the current task
        final ActivityStack focusStack = mSupervisor.getFocusedStack();
        final ActivityRecord curTop = (focusStack == null)
                ? null : focusStack.topRunningNonDelayedActivityLocked(mNotTop);
        if ((forceSend || (!mPowerHintSent && curTop != null &&
                curTop.task != null && mStartActivity != null &&
                curTop.task != mStartActivity.task)) &&
                mService.mLocalPowerManager != null) {
            mService.mLocalPowerManager.powerHint(PowerManagerInternal.POWER_HINT_LAUNCH, 1);
            mPowerHintSent = true;
        }
    }

    void sendPowerHintForLaunchEndIfNeeded() {
        // Trigger launch power hint if activity is launched
        if (mPowerHintSent && mService.mLocalPowerManager != null) {
            mService.mLocalPowerManager.powerHint(PowerManagerInternal.POWER_HINT_LAUNCH, 0);
            mPowerHintSent = false;
        }
    }

    /**
     * 这个方法调用有两个地方，一个是处理等待的Activity，一个是正常启动Activity
     *
     * @param r               描述需要被启动的Activity的对象
     * @param sourceRecord    调用者的Activity的封装
     * @param voiceSession    等待的为空，否则不为空
     * @param voiceInteractor 等待的为空，否则不为空
     * @param startFlags      0
     * @param doResume        是否需要复用，等待的不一定需要，直接启动的需要复用
     * @param options         启动Activity需要的数据对象，启动等待的为空，否则不为空
     * @param inTask          等待的为空，否则不为空
     *
     * @return
     */
    private int startActivityUnchecked(final ActivityRecord r, ActivityRecord sourceRecord,
                                       IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
                                       int startFlags, boolean doResume, ActivityOptions options, TaskRecord inTask) {

        // 设置初始状态
        setInitialState(r, options, inTask, doResume, startFlags, sourceRecord, voiceSession,
                voiceInteractor);

        computeLaunchingTaskFlags();

        computeSourceStack();

        // 添加启动模式
        mIntent.setFlags(mLaunchFlags);

        // 查找有没有可复用（与要启动的Activity一样的Activity）的Activity
        mReusedActivity = getReusableIntentActivity();

        final int preferredLaunchStackId =
                (mOptions != null) ? mOptions.getLaunchStackId() : INVALID_STACK_ID;

        // 如果有可复用的Activity，也就是说不需要重新创建新的Activity
        if (mReusedActivity != null) {
            // When the flags NEW_TASK and CLEAR_TASK are set, then the task gets reused but
            // still needs to be a lock task mode violation since the task gets cleared out and
            // the device would otherwise leave the locked task.
            if (mSupervisor.isLockTaskModeViolation(mReusedActivity.task,
                    (mLaunchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
                            == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))) {
                mSupervisor.showLockTaskToast();
                Slog.e(TAG, "startActivityUnchecked: Attempt to violate Lock Task Mode");
                return START_RETURN_LOCK_TASK_MODE_VIOLATION;
            }

            // mStartActivity是在setInitialState方法中赋值的，指向的是被启动Activity对象封装，
            // 如果是第一次启动那么它的任务栈就不存在，此时先默认设置成可复用的Activity的任务栈。
            if (mStartActivity.task == null) {
                mStartActivity.task = mReusedActivity.task;
            }
            // 如果可复用Activity中的任务栈中的Intent为空，那么我们就要将被启动Activity的Intent设置给可复用的Activity
            if (mReusedActivity.task.intent == null) {
                // This task was started because of movement of the activity based on affinity...
                // Now that we are actually launching it, we can assign the base intent.
                mReusedActivity.task.setIntent(mStartActivity);
            }

            // This code path leads to delivering a new intent, we want to make sure we schedule it
            // as the first operation, in case the activity will be resumed as a result of later
            // operations.
            // 这里有三个条件，只需要满足一个就执行if语句中的操作
            // 1.需要清理能复用的Activity所在栈中该Activity上面的其他Activity，
            // 2.该Activity是SingleInstance模式
            // 3.该Activity是SingleTask模式
            if ((mLaunchFlags & FLAG_ACTIVITY_CLEAR_TOP) != 0
                    || mLaunchSingleInstance || mLaunchSingleTask) {
                // In this situation we want to remove all activities from the task up to the one
                // being started. In most cases this means we are resetting the task to its initial
                // state.
                // 获取要启动Activity一样的可复用的ActivityRecord，同时要清理该复用Activity顶部的Activity
                final ActivityRecord top = mReusedActivity.task.performClearTaskForReuseLocked(
                        mStartActivity, mLaunchFlags);
                if (top != null) {
                    if (top.frontOfTask) {
                        // Activity aliases may mean we use different intents for the top activity,
                        // so make sure the task now has the identity of the new intent.
                        top.task.setIntent(mStartActivity);
                    }
                    ActivityStack.logStartActivity(AM_NEW_INTENT, mStartActivity, top.task);
                    // 将Intent通过调用onNewIntent方法分发给已经存在Activity
                    top.deliverNewIntentLocked(mCallingUid, mStartActivity.intent,
                            mStartActivity.launchedFromPackage);
                }
            }

            sendPowerHintForLaunchStartIfNeeded(false /* forceSend */);

            // 设置目标栈并且将可复用的Activity（正在启动的）移到栈顶
            mReusedActivity = setTargetStackAndMoveToFrontIfNeeded(mReusedActivity);

            if ((mStartFlags & START_FLAG_ONLY_IF_NEEDED) != 0) {
                // We don't need to start a new activity, and the client said not to do anything
                // if that is the case, so this is it!  And for paranoia, make sure we have
                // correctly resumed the top activity.
                resumeTargetStackIfNeeded();
                return START_RETURN_INTENT_TO_CALLER;
            }
            setTaskFromIntentActivity(mReusedActivity);

            // 如果不需要添加到任务栈，并且复用TaskRecord为空，说明没有启动一个新的Activity
            // mAddingToTask为false表示要为目标Activity组件创建一个专属任务，事实上函数会检查这个专属
            // 任务是否存在，如果已存在，那么就会将变量mAddingToTask的值设置为true，
            if (!mAddingToTask && mReuseTask == null) {
                // We didn't do anything...  but it was needed (a.k.a., client don't use that
                // intent!)  And for paranoia, make sure we have correctly resumed the top activity.
                resumeTargetStackIfNeeded();
                // 需要启动的Activity不需要重启启动，只需要将其放到栈顶即可
                return START_TASK_TO_FRONT;
            }
        }

        // 正在启动的Activity的包名为空，启动失败
        if (mStartActivity.packageName == null) {
            if (mStartActivity.resultTo != null && mStartActivity.resultTo.task.stack != null) {
                mStartActivity.resultTo.task.stack.sendActivityResultLocked(
                        -1, mStartActivity.resultTo, mStartActivity.resultWho,
                        mStartActivity.requestCode, RESULT_CANCELED, null);
            }
            ActivityOptions.abort(mOptions);
            return START_CLASS_NOT_FOUND;
        }

        // If the activity being launched is the same as the one currently at the top, then
        // we need to check if it should only be launched once.
        final ActivityStack topStack = mSupervisor.mFocusedStack;
        final ActivityRecord top = topStack.topRunningNonDelayedActivityLocked(mNotTop);
        final boolean dontStart = top != null && mStartActivity.resultTo == null
                && top.realActivity.equals(mStartActivity.realActivity)
                && top.userId == mStartActivity.userId
                && top.app != null && top.app.thread != null
                && ((mLaunchFlags & FLAG_ACTIVITY_SINGLE_TOP) != 0
                || mLaunchSingleTop || mLaunchSingleTask);
        if (dontStart) {// 不启动
            ActivityStack.logStartActivity(AM_NEW_INTENT, top, top.task);
            // For paranoia, make sure we have correctly resumed the top activity.
            topStack.mLastPausedActivity = null;
            if (mDoResume) {
                // 恢复聚焦的栈
                mSupervisor.resumeFocusedStackTopActivityLocked();
            }
            ActivityOptions.abort(mOptions);
            if ((mStartFlags & START_FLAG_ONLY_IF_NEEDED) != 0) {
                // We don't need to start a new activity, and the client said not to do
                // anything if that is the case, so this is it!
                return START_RETURN_INTENT_TO_CALLER;
            }
            // 如果不需要重启，那么通过调用onNewIntent来发送一个Intent
            top.deliverNewIntentLocked(
                    mCallingUid, mStartActivity.intent, mStartActivity.launchedFromPackage);

            // Don't use mStartActivity.task to show the toast. We're not starting a new activity
            // but reusing 'top'. Fields in mStartActivity may not be fully initialized.
            mSupervisor.handleNonResizableTaskIfNeeded(
                    top.task, preferredLaunchStackId, topStack.mStackId);
            // 需要启动的Activity在栈顶
            return START_DELIVERED_TO_TOP;
        }

        boolean newTask = false;// 是否会新创建一个任务
        // Activity组件中有一个android:taskAffinity属性，用来描述它的一个专属任务，当AMS决定要将目标
        // Activity运行在一个不同的任务中时，AMS就会检查目标Activity组件的专属任务是否已经存在，如果存
        // 在，那么AMS就会直接将目标Activity组件添加到它里面运行，否则，就会先创建这个专属任务，然后将目
        // 标Activity组件添加到它里面去运行
        final TaskRecord taskToAffiliate = (mLaunchTaskBehind && mSourceRecord != null)
                ? mSourceRecord.task : null;

        // Should this be considered a new task?
        if (mStartActivity.resultTo == null && mInTask == null && !mAddingToTask
                && (mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) != 0) {
            newTask = true;// 需要创建新的任务
            setTaskFromReuseOrCreateNewTask(taskToAffiliate);

            if (mSupervisor.isLockTaskModeViolation(mStartActivity.task)) {
                Slog.e(TAG, "Attempted Lock Task Mode violation mStartActivity=" + mStartActivity);
                return START_RETURN_LOCK_TASK_MODE_VIOLATION;
            }
            if (!mMovedOtherTask) {
                // If stack id is specified in activity options, usually it means that activity is
                // launched not from currently focused stack (e.g. from SysUI or from shell) - in
                // that case we check the target stack.
                updateTaskReturnToType(mStartActivity.task, mLaunchFlags,
                        preferredLaunchStackId != INVALID_STACK_ID ? mTargetStack : topStack);
            }
        } else if (mSourceRecord != null) {
            if (mSupervisor.isLockTaskModeViolation(mSourceRecord.task)) {
                Slog.e(TAG, "Attempted Lock Task Mode violation mStartActivity=" + mStartActivity);
                return START_RETURN_LOCK_TASK_MODE_VIOLATION;
            }

            final int result = setTaskFromSourceRecord();
            if (result != START_SUCCESS) {
                return result;
            }
        } else if (mInTask != null) {
            // The caller is asking that the new activity be started in an explicit
            // task it has provided to us.
            if (mSupervisor.isLockTaskModeViolation(mInTask)) {
                Slog.e(TAG, "Attempted Lock Task Mode violation mStartActivity=" + mStartActivity);
                return START_RETURN_LOCK_TASK_MODE_VIOLATION;
            }

            final int result = setTaskFromInTask();
            if (result != START_SUCCESS) {
                return result;
            }
        } else {
            // This not being started from an existing activity, and not part of a new task...
            // just put it in the top task, though these days this case should never happen.
            setTaskToCurrentTopOrCreateNewTask();
        }

        mService.grantUriPermissionFromIntentLocked(mCallingUid, mStartActivity.packageName,
                mIntent, mStartActivity.getUriPermissionsLocked(), mStartActivity.userId);

        if (mSourceRecord != null && mSourceRecord.isRecentsActivity()) {
            mStartActivity.task.setTaskToReturnTo(RECENTS_ACTIVITY_TYPE);
        }
        if (newTask) {
            EventLog.writeEvent(
                    EventLogTags.AM_CREATE_TASK, mStartActivity.userId, mStartActivity.task.taskId);
        }
        ActivityStack.logStartActivity(
                EventLogTags.AM_CREATE_ACTIVITY, mStartActivity, mStartActivity.task);
        mTargetStack.mLastPausedActivity = null;

        sendPowerHintForLaunchStartIfNeeded(false /* forceSend */);

        mTargetStack.startActivityLocked(mStartActivity, newTask, mKeepCurTransition, mOptions);
        if (mDoResume) {// 如果复用Activity
            if (!mLaunchTaskBehind) {
                // TODO(b/26381750): Remove this code after verification that all the decision
                // points above moved targetStack to the front which will also set the focus
                // activity.
                mService.setFocusedActivityLocked(mStartActivity, "startedActivity");
            }
            // 获取顶部ActivityRecord
            final ActivityRecord topTaskActivity = mStartActivity.task.topRunningActivityLocked();
            if (!mTargetStack.isFocusable()
                    || (topTaskActivity != null && topTaskActivity.mTaskOverlay
                    && mStartActivity != topTaskActivity)) {// 不能复用
                // If the activity is not focusable, we can't resume it, but still would like to
                // make sure it becomes visible as it starts (this will also trigger（触发） entry
                // animation). An example of this are PIP activities.
                // Also, we don't want to resume activities in a task that currently has an overlay
                // as the starting activity just needs to be in the visible paused state until the
                // over is removed.
                // 确认显示
                mTargetStack.ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
                // Go ahead and tell window manager to execute app transition for this activity
                // since the app transition will not be triggered through the resume channel.
                mWindowManager.executeAppTransition();// 执行过度
            } else {
                // 恢复聚焦栈顶Activity
                mSupervisor.resumeFocusedStackTopActivityLocked(mTargetStack, mStartActivity,
                        mOptions);
            }
        } else {// 新创建的
            // 添加启动的Activity到最近任务
            mTargetStack.addRecentActivityLocked(mStartActivity);
        }
        mSupervisor.updateUserStackLocked(mStartActivity.userId, mTargetStack);

        mSupervisor.handleNonResizableTaskIfNeeded(
                mStartActivity.task, preferredLaunchStackId, mTargetStack.mStackId);

        return START_SUCCESS;
    }

    private void setInitialState(ActivityRecord r, ActivityOptions options, TaskRecord inTask,
                                 boolean doResume, int startFlags, ActivityRecord sourceRecord,
                                 IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor) {
        reset();// 初始化参数

        mStartActivity = r;// 对mStartActivity赋值，也就是被启动的Activity的对象封装
        mIntent = r.intent;
        mOptions = options;
        mCallingUid = r.launchedFromUid;
        mSourceRecord = sourceRecord;
        mVoiceSession = voiceSession;
        mVoiceInteractor = voiceInteractor;

        mLaunchBounds = getOverrideBounds(r, options, inTask);

        // 判断启动模式，因为默认是standard模式，所以只需要与其他三个对比就可以了
        mLaunchSingleTop = r.launchMode == LAUNCH_SINGLE_TOP;
        mLaunchSingleInstance = r.launchMode == LAUNCH_SINGLE_INSTANCE;
        mLaunchSingleTask = r.launchMode == LAUNCH_SINGLE_TASK;
        mLaunchFlags = adjustLaunchFlagsToDocumentMode(
                r, mLaunchSingleInstance, mLaunchSingleTask, mIntent.getFlags());
        // 通过ActivityOptions.setLaunchTaskBehind方法被激活，并且被启动完成后就会被清理
        mLaunchTaskBehind = r.mLaunchTaskBehind
                && !mLaunchSingleTask && !mLaunchSingleInstance
                && (mLaunchFlags & FLAG_ACTIVITY_NEW_DOCUMENT) != 0;

        sendNewTaskResultRequestIfNeeded();

        if ((mLaunchFlags & FLAG_ACTIVITY_NEW_DOCUMENT) != 0 && r.resultTo == null) {
            mLaunchFlags |= FLAG_ACTIVITY_NEW_TASK;
        }

        // If we are actually going to launch in to a new task, there are some cases where
        // we further want to do multiple task.
        if ((mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) != 0) {
            if (mLaunchTaskBehind
                    || r.info.documentLaunchMode == DOCUMENT_LAUNCH_ALWAYS) {
                mLaunchFlags |= FLAG_ACTIVITY_MULTIPLE_TASK;
            }
        }

        // We'll invoke onUserLeaving before onPause only if the launching
        // activity did not explicitly state that this is an automated launch.
        // 检查变量mLaunchFlags的Intent.FLAG_ACTIVITY_NO_USER_ACTION位是否为1，如果等于1，那么就
        // 表示目标Activity组件不是由用户手动启动的，如果目标Activity组件是由用户手动启动的，那么用来
        // 启动它的源Activity组件就会获得一个用户离开时间通知，由于目标Activity组件使用户在应用程序启
        // 动器的界面上点击启动的，即变量mLaunchFlags的Intent.FLAG_ACTIVITY_NO_USER_ACTION位等于0，
        // 因此，成员变量mUserLeaving的值为true。
        mSupervisor.mUserLeaving = (mLaunchFlags & FLAG_ACTIVITY_NO_USER_ACTION) == 0;
        if (DEBUG_USER_LEAVING) Slog.v(TAG_USER_LEAVING,
                "startActivity() => mUserLeaving=" + mSupervisor.mUserLeaving);

        // If the caller has asked not to resume at this point, we make note
        // of this in the record so that we can skip it when trying to find
        // the top running activity.
        mDoResume = doResume;// 启动Activity时如果直接启动则为true，如果是处理等待启动的Activity则为false
        if (!doResume || !mSupervisor.okToShowLocked(r)) {
            r.delayedResume = true;
            mDoResume = false;
        }

        if (mOptions != null && mOptions.getLaunchTaskId() != -1 && mOptions.getTaskOverlay()) {
            r.mTaskOverlay = true;
            // 查找所有的栈是否存在对应的TaskRecord
            final TaskRecord task = mSupervisor.anyTaskForIdLocked(mOptions.getLaunchTaskId());
            // 如果存在TaskRecord，那么获取顶部Activity的ActivityRecord对象
            final ActivityRecord top = task != null ? task.getTopActivity() : null;
            if (top != null && !top.visible) {

                // The caller specifies that we'd like to be avoided to be moved to the front, so be
                // it!
                mDoResume = false;
                mAvoidMoveToFront = true;
            }
        }

        // intent的标志值的位Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP也没有置位，因此，变量notTop的值为null。
        mNotTop = (mLaunchFlags & FLAG_ACTIVITY_PREVIOUS_IS_TOP) != 0 ? r : null;

        mInTask = inTask;
        // In some flows in to this function, we retrieve the task record and hold on to it
        // without a lock before calling back in to here...  so the task at this point may
        // not actually be in recents.  Check for that, and if it isn't in recents just
        // consider it invalid.
        if (inTask != null && !inTask.inRecents) {
            Slog.w(TAG, "Starting activity in task not in recents: " + inTask);
            mInTask = null;
        }

        mStartFlags = startFlags;
        // If the onlyIfNeeded flag is set, then we can do this if the activity being launched
        // is the same as the one making the call...  or, as a special case, if we do not know
        // the caller then we count the current top activity as the caller.
        if ((startFlags & START_FLAG_ONLY_IF_NEEDED) != 0) {
            ActivityRecord checkedCaller = sourceRecord;
            if (checkedCaller == null) {
                checkedCaller = mSupervisor.mFocusedStack.topRunningNonDelayedActivityLocked(
                        mNotTop);
            }
            if (!checkedCaller.realActivity.equals(r.realActivity)) {
                // Caller is not the same as launcher, so always needed.
                mStartFlags &= ~START_FLAG_ONLY_IF_NEEDED;
            }
        }

        mNoAnimation = (mLaunchFlags & FLAG_ACTIVITY_NO_ANIMATION) != 0;
    }

    private void sendNewTaskResultRequestIfNeeded() {
        // 发送条件：接收结果的Activity存在，并且mLaunchFlags是FLAG_ACTIVITY_NEW_TASK，并且接收结果的任务栈存在
        if (mStartActivity.resultTo != null && (mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) != 0
                && mStartActivity.resultTo.task.stack != null) {
            // For whatever reason this activity is being launched into a new task...
            // yet the caller has requested a result back.  Well, that is pretty messed up（混乱）,
            // so instead immediately send back a cancel and let the new task continue launched
            // as normal without a dependency on its originator.
            Slog.w(TAG, "Activity is launching as a new task, so cancelling activity result.");
            mStartActivity.resultTo.task.stack.sendActivityResultLocked(-1, mStartActivity.resultTo,
                    mStartActivity.resultWho, mStartActivity.requestCode, RESULT_CANCELED, null);
            mStartActivity.resultTo = null;
        }
    }

    private void computeLaunchingTaskFlags() {
        // If the caller is not coming from another activity, but has given us an explicit task into
        // which they would like us to launch the new activity, then let's see about doing that.
        if (mSourceRecord == null && mInTask != null && mInTask.stack != null) {
            final Intent baseIntent = mInTask.getBaseIntent();
            final ActivityRecord root = mInTask.getRootActivity();
            if (baseIntent == null) {
                ActivityOptions.abort(mOptions);
                throw new IllegalArgumentException("Launching into task without base intent: "
                        + mInTask);
            }

            // If this task is empty, then we are adding the first activity -- it
            // determines the root, and must be launching as a NEW_TASK.
            if (mLaunchSingleInstance || mLaunchSingleTask) {
                if (!baseIntent.getComponent().equals(mStartActivity.intent.getComponent())) {
                    ActivityOptions.abort(mOptions);
                    throw new IllegalArgumentException("Trying to launch singleInstance/Task "
                            + mStartActivity + " into different task " + mInTask);
                }
                if (root != null) {
                    ActivityOptions.abort(mOptions);
                    throw new IllegalArgumentException("Caller with mInTask " + mInTask
                            + " has root " + root + " but target is singleInstance/Task");
                }
            }

            // If task is empty, then adopt the interesting intent launch flags in to the
            // activity being started.
            if (root == null) {
                final int flagsOfInterest = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK
                        | FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_RETAIN_IN_RECENTS;
                mLaunchFlags = (mLaunchFlags & ~flagsOfInterest)
                        | (baseIntent.getFlags() & flagsOfInterest);
                mIntent.setFlags(mLaunchFlags);
                mInTask.setIntent(mStartActivity);
                // mAddingToTask为false表示要为目标Activity组件创建一个专属任务，事实上函数会检查这个专属
                // 任务是否存在，如果已存在，那么就会将变量mAddingToTask的值设置为true，
                mAddingToTask = true;

                // If the task is not empty and the caller is asking to start it as the root of
                // a new task, then we don't actually want to start this on the task. We will
                // bring the task to the front, and possibly give it a new intent.
            } else if ((mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) != 0) {
                mAddingToTask = false;

            } else {
                mAddingToTask = true;
            }

            mReuseTask = mInTask;
        } else {
            mInTask = null;
            // Launch ResolverActivity in the source task, so that it stays in the task bounds
            // when in freeform workspace.
            // Also put noDisplay activities in the source task. These by itself can be placed
            // in any task/stack, however it could launch other activities like ResolverActivity,
            // and we want those to stay in the original task.
            if ((mStartActivity.isResolverActivity() || mStartActivity.noDisplay) && mSourceRecord != null
                    && mSourceRecord.isFreeform()) {
                mAddingToTask = true;
            }
        }

        if (mInTask == null) {
            if (mSourceRecord == null) {
                // This activity is not being started from another...  in this
                // case we -always- start a new task.
                if ((mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) == 0 && mInTask == null) {
                    Slog.w(TAG, "startActivity called from non-Activity context; forcing " +
                            "Intent.FLAG_ACTIVITY_NEW_TASK for: " + mIntent);
                    mLaunchFlags |= FLAG_ACTIVITY_NEW_TASK;
                }
            } else if (mSourceRecord.launchMode == LAUNCH_SINGLE_INSTANCE) {
                // The original activity who is starting us is running as a single
                // instance...  this new activity it is starting must go on its
                // own task.
                mLaunchFlags |= FLAG_ACTIVITY_NEW_TASK;
            } else if (mLaunchSingleInstance || mLaunchSingleTask) {
                // The activity being started is a single instance...  it always
                // gets launched into its own task.
                mLaunchFlags |= FLAG_ACTIVITY_NEW_TASK;
            }
        }
    }

    private void computeSourceStack() {
        // 当前Activity不存在了
        if (mSourceRecord == null) {
            mSourceStack = null;
            return;
        }
        // 如果当前Activity没有被finish
        if (!mSourceRecord.finishing) {
            mSourceStack = mSourceRecord.task.stack;
            return;
        }

        // If the source is finishing, we can't further count it as our source. This is because the
        // task it is associated with may now be empty and on its way out, so we don't want to
        // blindly throw it in to that task.  Instead we will take the NEW_TASK flow and try to find
        // a task for it. But save the task information so it can be used when creating the new task.
        // 如果当前Activity正在被finish，并且启动模式不是启动一个新的任务，那么我们要添加启动新任务的标签。
        if ((mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) == 0) {
            Slog.w(TAG, "startActivity called from finishing " + mSourceRecord
                    + "; forcing " + "Intent.FLAG_ACTIVITY_NEW_TASK for: " + mIntent);
            mLaunchFlags |= FLAG_ACTIVITY_NEW_TASK;
            mNewTaskInfo = mSourceRecord.info;
            mNewTaskIntent = mSourceRecord.task.intent;
        }
        mSourceRecord = null;
        mSourceStack = null;
    }

    /**
     * Decide whether the new activity should be inserted into an existing task. Returns null
     * if not or an ActivityRecord with the task into which the new activity should be added.
     * <p>
     * 决定新的Activity是否应该被插入到已经存在的任务栈中。如果不插入，返回null，
     * 否则返回一个带有新Activity加入的任务栈的ActivityRecord
     */
    private ActivityRecord getReusableIntentActivity() {
        // We may want to try to place the new activity in to an existing task.  We always
        // do this if the target activity is singleTask or singleInstance; we will also do
        // this if NEW_TASK has been requested, and there is not an additional qualifier telling
        // us to still place it in a new task: multi task, always doc mode, or being asked to
        // launch this as a new task behind the current one.
        // 是否放置到已经存在的任务栈中
        boolean putIntoExistingTask = ((mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) != 0 &&
                (mLaunchFlags & FLAG_ACTIVITY_MULTIPLE_TASK) == 0)
                || mLaunchSingleInstance || mLaunchSingleTask;
        // If bring to front is requested, and no result is requested and we have not been given
        // an explicit task to launch in to, and we can find a task that was started with this
        // same component, then instead of launching bring that one to the front.
        putIntoExistingTask &= mInTask == null && mStartActivity.resultTo == null;
        ActivityRecord intentActivity = null;
        if (mOptions != null && mOptions.getLaunchTaskId() != -1) {
            // 根据Id获取任务
            final TaskRecord task = mSupervisor.anyTaskForIdLocked(mOptions.getLaunchTaskId());
            // 如果任务存在启动的Activity就是任务中顶部的Activity
            intentActivity = task != null ? task.getTopActivity() : null;
        } else if (putIntoExistingTask) {// 如果需要放到已存在任务中
            if (mLaunchSingleInstance) {// SingleInstance模式下，在历史记录中只有一个Activity的实例，并且它一直在它独有的任务栈中。
                // There can be one and only one instance of single instance activity in the
                // history, and it is always in its own unique task, so we do a special search.
                intentActivity = mSupervisor.findActivityLocked(mIntent, mStartActivity.info, false);
            } else if ((mLaunchFlags & FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0) {// 是不是分屏模式
                // For the launch adjacent（相邻） case we only want to put the activity in an existing
                // task if the activity already exists in the history.
                intentActivity = mSupervisor.findActivityLocked(mIntent, mStartActivity.info,
                        !mLaunchSingleTask);
            } else {
                // Otherwise find the best task to put the activity in.
                intentActivity = mSupervisor.findTaskLocked(mStartActivity);
            }
        }
        return intentActivity;
    }

    private ActivityRecord setTargetStackAndMoveToFrontIfNeeded(ActivityRecord intentActivity) {
        // 将要启动的Activity所在任务所在的栈作为目标栈
        mTargetStack = intentActivity.task.stack;

        mTargetStack.mLastPausedActivity = null;
        // If the target task is not in the front, then we need to bring it to the front...
        // except...  well, with SINGLE_TASK_LAUNCH it's not entirely clear. We'd like to have
        // the same behavior as if a new instance was being started, which means not bringing it
        // to the front if the caller is not itself in the front.
        // 获取当前焦点栈
        final ActivityStack focusStack = mSupervisor.getFocusedStack();
        // 获取焦点栈中最上面正在运行的非延迟Activity
        ActivityRecord curTop = (focusStack == null)
                ? null : focusStack.topRunningNonDelayedActivityLocked(mNotTop);

        // 这里三个条件：
        // 1.存在上面获取的Activity，
        // 2.该Activity和要启动Activity不在同一个任务，或者该Activity不是最顶部的任务（也就是不是第一个任务）
        // 3.需要移动到顶部
        if (curTop != null
                && (curTop.task != intentActivity.task || curTop.task != focusStack.topTask())
                && !mAvoidMoveToFront) {
            mStartActivity.intent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
            // 源Activity不存在或者源Activity所在栈顶部Activity存在并且顶部Activity和源Activity不在同一个任务中
            if (mSourceRecord == null || (mSourceStack.topActivity() != null &&
                    mSourceStack.topActivity().task == mSourceRecord.task)) {
                // We really do want to push this one into the user's face, right now.
                if (mLaunchTaskBehind && mSourceRecord != null) {
                    intentActivity.setTaskToAffiliateWith(mSourceRecord.task);
                }
                mMovedOtherTask = true;

                // If the launch flags carry both NEW_TASK and CLEAR_TASK, the task's activities
                // will be cleared soon by ActivityStarter in setTaskFromIntentActivity().
                // So no point resuming any of the activities here, it just wastes one extra
                // resuming, plus enter AND exit transitions.
                // Here we only want to bring the target stack forward. Transition will be applied
                // to the new activity that's started after the old ones are gone.
                // 是否创建新任务并且清理该任务中的其他Activity
                final boolean willClearTask =
                        (mLaunchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
                                == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);
                if (!willClearTask) {// 不清理
                    // 获取启动栈
                    final ActivityStack launchStack = getLaunchStack(
                            mStartActivity, mLaunchFlags, mStartActivity.task, mOptions);
                    // 如果启动栈不存在，或者启动栈不是目标栈
                    if (launchStack == null || launchStack == mTargetStack) {
                        // We only want to move to the front, if we aren't going to launch on a
                        // different stack. If we launch on a different stack, we will put the
                        // task on top there.
                        // 把目标栈移动到前台
                        mTargetStack.moveTaskToFrontLocked(
                                intentActivity.task, mNoAnimation, mOptions,
                                mStartActivity.appTimeTracker, "bringingFoundTaskToFront");
                        mMovedToFront = true;// 标记
                    } else if (launchStack.mStackId == DOCKED_STACK_ID
                            || launchStack.mStackId == FULLSCREEN_WORKSPACE_STACK_ID) {
                        // 如果是分屏模式，被启动的Activity会显示到启动它的Activity所在屏幕上
                        if ((mLaunchFlags & FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0) {
                            // If we want to launch adjacent（相邻的） and mTargetStack is not the computed
                            // launch stack - move task to top of computed stack.
                            mSupervisor.moveTaskToStackLocked(intentActivity.task.taskId,
                                    launchStack.mStackId, ON_TOP, FORCE_FOCUS, "launchToSide",
                                    ANIMATE);
                        } else {// 不是分屏模式
                            // TODO: This should be reevaluated（重新评估） in MW v2.
                            // We choose to move task to front instead of launching it adjacent
                            // when specific stack was requested explicitly and it appeared to be
                            // adjacent stack, but FLAG_ACTIVITY_LAUNCH_ADJACENT was not set.
                            mTargetStack.moveTaskToFrontLocked(intentActivity.task, mNoAnimation,
                                    mOptions, mStartActivity.appTimeTracker,
                                    "bringToFrontInsteadOfAdjacentLaunch");
                        }
                        mMovedToFront = true;
                    }
                    mOptions = null;
                }
                updateTaskReturnToType(intentActivity.task, mLaunchFlags, focusStack);
            }
        }
        if (!mMovedToFront && mDoResume) {
            if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Bring to front target: " + mTargetStack
                    + " from " + intentActivity);
            mTargetStack.moveToFront("intentActivityFound");
        }

        mSupervisor.handleNonResizableTaskIfNeeded(intentActivity.task, INVALID_STACK_ID,
                mTargetStack.mStackId);

        // If the caller has requested that the target task be reset, then do so.
        if ((mLaunchFlags & FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
            return mTargetStack.resetTaskIfNeededLocked(intentActivity, mStartActivity);
        }
        return intentActivity;
    }

    private void updateTaskReturnToType(
            TaskRecord task, int launchFlags, ActivityStack focusedStack) {
        if ((launchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME))
                == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME)) {
            // Caller wants to appear on home activity.
            task.setTaskToReturnTo(HOME_ACTIVITY_TYPE);
            return;
        } else if (focusedStack == null || focusedStack.mStackId == HOME_STACK_ID) {
            // Task will be launched over the home stack, so return home.
            task.setTaskToReturnTo(HOME_ACTIVITY_TYPE);
            return;
        }

        // Else we are coming from an application stack so return to an application.
        task.setTaskToReturnTo(APPLICATION_ACTIVITY_TYPE);
    }

    private void setTaskFromIntentActivity(ActivityRecord intentActivity) {
        if ((mLaunchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
                == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK)) {
            // The caller has requested to completely replace any existing task with its new
            // activity. Well that should not be too hard...
            mReuseTask = intentActivity.task;
            mReuseTask.performClearTaskLocked();
            mReuseTask.setIntent(mStartActivity);
            // When we clear the task - focus will be adjusted, which will bring another task
            // to top before we launch the activity we need. This will temporary swap their
            // mTaskToReturnTo values and we don't want to overwrite them accidentally.
            mMovedOtherTask = true;
        } else if ((mLaunchFlags & FLAG_ACTIVITY_CLEAR_TOP) != 0
                || mLaunchSingleInstance || mLaunchSingleTask) {
            // 启动模式是清理顶部Activity或者是SingleInstance或者是SingleTask
            // 执行清理操作并且获取老的可用的ActivityRecord
            ActivityRecord top = intentActivity.task.performClearTaskLocked(mStartActivity,
                    mLaunchFlags);
            if (top == null) {
                // A special case: we need to start the activity because it is not currently
                // running, and the caller has asked to clear the current task to have this
                // activity at the top.
                mAddingToTask = true;
                // Now pretend like this activity is being started by the top of its task, so it
                // is put in the right place.
                mSourceRecord = intentActivity;
                final TaskRecord task = mSourceRecord.task;
                if (task != null && task.stack == null) {
                    // Target stack got cleared when we all activities were removed above.
                    // Go ahead and reset it.
                    mTargetStack = computeStackFocus(mSourceRecord, false /* newTask */,
                            null /* bounds */, mLaunchFlags, mOptions);
                    mTargetStack.addTask(task,
                            !mLaunchTaskBehind /* toTop */, "startActivityUnchecked");
                }
            }
        } else if (mStartActivity.realActivity.equals(intentActivity.task.realActivity)) {
            // In this case the top activity on the task is the same as the one being launched,
            // so we take that as a request to bring the task to the foreground. If the top
            // activity in the task is the root activity, deliver this new intent to it if it
            // desires.
            if (((mLaunchFlags & FLAG_ACTIVITY_SINGLE_TOP) != 0 || mLaunchSingleTop)
                    && intentActivity.realActivity.equals(mStartActivity.realActivity)) {
                ActivityStack.logStartActivity(AM_NEW_INTENT, mStartActivity,
                        intentActivity.task);
                if (intentActivity.frontOfTask) {
                    intentActivity.task.setIntent(mStartActivity);
                }
                intentActivity.deliverNewIntentLocked(mCallingUid, mStartActivity.intent,
                        mStartActivity.launchedFromPackage);
            } else if (!intentActivity.task.isSameIntentFilter(mStartActivity)) {
                // In this case we are launching the root activity of the task, but with a
                // different intent. We should start a new instance on top.
                mAddingToTask = true;
                mSourceRecord = intentActivity;
            }
        } else if ((mLaunchFlags & FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) == 0) {
            // In this case an activity is being launched in to an existing task, without
            // resetting that task. This is typically the situation of launching an activity
            // from a notification or shortcut. We want to place the new activity on top of the
            // current task.
            mAddingToTask = true;
            mSourceRecord = intentActivity;
        } else if (!intentActivity.task.rootWasReset) {
            // In this case we are launching into an existing task that has not yet been started
            // from its front door. The current task has been brought to the front. Ideally,
            // we'd probably like to place this new task at the bottom of its stack, but that's
            // a little hard to do with the current organization of the code so for now we'll
            // just drop it.
            intentActivity.task.setIntent(mStartActivity);
        }
    }

    // 恢复目标栈
    private void resumeTargetStackIfNeeded() {
        if (mDoResume) {
            mSupervisor.resumeFocusedStackTopActivityLocked(mTargetStack, null, mOptions);
            if (!mMovedToFront) {
                // Make sure to notify Keyguard as well if we are not running an app transition
                // later.
                mSupervisor.notifyActivityDrawnForKeyguard();
            }
        } else {
            ActivityOptions.abort(mOptions);
        }
        mSupervisor.updateUserStackLocked(mStartActivity.userId, mTargetStack);
    }

    private void setTaskFromReuseOrCreateNewTask(TaskRecord taskToAffiliate) {
        mTargetStack = computeStackFocus(mStartActivity, true, mLaunchBounds, mLaunchFlags,
                mOptions);

        if (mReuseTask == null) {// 没有可用任务
            // 创建新的任务并插入栈中
            final TaskRecord task = mTargetStack.createTaskRecord(
                    mSupervisor.getNextTaskIdForUserLocked(mStartActivity.userId),
                    mNewTaskInfo != null ? mNewTaskInfo : mStartActivity.info,
                    mNewTaskIntent != null ? mNewTaskIntent : mIntent,
                    mVoiceSession, mVoiceInteractor, !mLaunchTaskBehind /* toTop */);
            mStartActivity.setTask(task, taskToAffiliate);
            if (mLaunchBounds != null) {
                final int stackId = mTargetStack.mStackId;
                if (StackId.resizeStackWithLaunchBounds(stackId)) {
                    mService.resizeStack(
                            stackId, mLaunchBounds, true, !PRESERVE_WINDOWS, ANIMATE, -1);
                } else {
                    mStartActivity.task.updateOverrideConfiguration(mLaunchBounds);
                }
            }
            if (DEBUG_TASKS) Slog.v(TAG_TASKS,
                    "Starting new activity " +
                            mStartActivity + " in new task " + mStartActivity.task);
        } else {
            mStartActivity.setTask(mReuseTask, taskToAffiliate);
        }
    }

    private int setTaskFromSourceRecord() {
        final TaskRecord sourceTask = mSourceRecord.task;
        // We only want to allow changing stack if the target task is not the top one,
        // otherwise we would move the launching task to the other side, rather than show
        // two side by side.
        final boolean moveStackAllowed = sourceTask.stack.topTask() != sourceTask;
        if (moveStackAllowed) {
            mTargetStack = getLaunchStack(mStartActivity, mLaunchFlags, mStartActivity.task,
                    mOptions);
        }

        if (mTargetStack == null) {
            mTargetStack = sourceTask.stack;
        } else if (mTargetStack != sourceTask.stack) {
            mSupervisor.moveTaskToStackLocked(sourceTask.taskId, mTargetStack.mStackId,
                    ON_TOP, FORCE_FOCUS, "launchToSide", !ANIMATE);
        }
        if (mDoResume) {
            mTargetStack.moveToFront("sourceStackToFront");
        }
        final TaskRecord topTask = mTargetStack.topTask();
        if (topTask != sourceTask && !mAvoidMoveToFront) {
            mTargetStack.moveTaskToFrontLocked(sourceTask, mNoAnimation, mOptions,
                    mStartActivity.appTimeTracker, "sourceTaskToFront");
        }
        if (!mAddingToTask && (mLaunchFlags & FLAG_ACTIVITY_CLEAR_TOP) != 0) {
            // In this case, we are adding the activity to an existing task, but the caller has
            // asked to clear that task if the activity is already running.
            ActivityRecord top = sourceTask.performClearTaskLocked(mStartActivity, mLaunchFlags);
            mKeepCurTransition = true;
            if (top != null) {
                ActivityStack.logStartActivity(AM_NEW_INTENT, mStartActivity, top.task);
                top.deliverNewIntentLocked(mCallingUid, mStartActivity.intent, mStartActivity.launchedFromPackage);
                // For paranoia, make sure we have correctly resumed the top activity.
                mTargetStack.mLastPausedActivity = null;
                if (mDoResume) {
                    mSupervisor.resumeFocusedStackTopActivityLocked();
                }
                ActivityOptions.abort(mOptions);
                return START_DELIVERED_TO_TOP;
            }
        } else if (!mAddingToTask && (mLaunchFlags & FLAG_ACTIVITY_REORDER_TO_FRONT) != 0) {
            // In this case, we are launching an activity in our own task that may already be
            // running somewhere in the history, and we want to shuffle it to the front of the
            // stack if so.
            final ActivityRecord top = sourceTask.findActivityInHistoryLocked(mStartActivity);
            if (top != null) {
                final TaskRecord task = top.task;
                task.moveActivityToFrontLocked(top);
                top.updateOptionsLocked(mOptions);
                ActivityStack.logStartActivity(AM_NEW_INTENT, mStartActivity, task);
                top.deliverNewIntentLocked(mCallingUid, mStartActivity.intent, mStartActivity.launchedFromPackage);
                mTargetStack.mLastPausedActivity = null;
                if (mDoResume) {
                    mSupervisor.resumeFocusedStackTopActivityLocked();
                }
                return START_DELIVERED_TO_TOP;
            }
        }

        // An existing activity is starting this new activity, so we want to keep the new one in
        // the same task as the one that is starting it.
        mStartActivity.setTask(sourceTask, null);
        if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Starting new activity " + mStartActivity
                + " in existing task " + mStartActivity.task + " from source " + mSourceRecord);
        return START_SUCCESS;
    }

    private int setTaskFromInTask() {
        if (mLaunchBounds != null) {
            mInTask.updateOverrideConfiguration(mLaunchBounds);
            int stackId = mInTask.getLaunchStackId();
            if (stackId != mInTask.stack.mStackId) {
                final ActivityStack stack = mSupervisor.moveTaskToStackUncheckedLocked(
                        mInTask, stackId, ON_TOP, !FORCE_FOCUS, "inTaskToFront");
                stackId = stack.mStackId;
            }
            if (StackId.resizeStackWithLaunchBounds(stackId)) {
                mService.resizeStack(stackId, mLaunchBounds, true, !PRESERVE_WINDOWS, ANIMATE, -1);
            }
        }
        mTargetStack = mInTask.stack;
        mTargetStack.moveTaskToFrontLocked(
                mInTask, mNoAnimation, mOptions, mStartActivity.appTimeTracker, "inTaskToFront");

        // Check whether we should actually launch the new activity in to the task,
        // or just reuse the current activity on top.
        ActivityRecord top = mInTask.getTopActivity();
        if (top != null && top.realActivity.equals(mStartActivity.realActivity) && top.userId == mStartActivity.userId) {
            if ((mLaunchFlags & FLAG_ACTIVITY_SINGLE_TOP) != 0
                    || mLaunchSingleTop || mLaunchSingleTask) {
                ActivityStack.logStartActivity(AM_NEW_INTENT, top, top.task);
                if ((mStartFlags & START_FLAG_ONLY_IF_NEEDED) != 0) {
                    // We don't need to start a new activity, and the client said not to do
                    // anything if that is the case, so this is it!
                    return START_RETURN_INTENT_TO_CALLER;
                }
                top.deliverNewIntentLocked(mCallingUid, mStartActivity.intent, mStartActivity.launchedFromPackage);
                return START_DELIVERED_TO_TOP;
            }
        }

        if (!mAddingToTask) {
            // We don't actually want to have this activity added to the task, so just
            // stop here but still tell the caller that we consumed the intent.
            ActivityOptions.abort(mOptions);
            return START_TASK_TO_FRONT;
        }

        mStartActivity.setTask(mInTask, null);
        if (DEBUG_TASKS) Slog.v(TAG_TASKS,
                "Starting new activity " + mStartActivity + " in explicit task " + mStartActivity.task);

        return START_SUCCESS;
    }

    private void setTaskToCurrentTopOrCreateNewTask() {
        mTargetStack = computeStackFocus(mStartActivity, false, null /* bounds */, mLaunchFlags,
                mOptions);
        if (mDoResume) {
            mTargetStack.moveToFront("addingToTopTask");
        }
        final ActivityRecord prev = mTargetStack.topActivity();
        final TaskRecord task = (prev != null) ? prev.task : mTargetStack.createTaskRecord(
                mSupervisor.getNextTaskIdForUserLocked(mStartActivity.userId),
                mStartActivity.info, mIntent, null, null, true);
        mStartActivity.setTask(task, null);
        mWindowManager.moveTaskToTop(mStartActivity.task.taskId);
        if (DEBUG_TASKS) Slog.v(TAG_TASKS,
                "Starting new activity " + mStartActivity + " in new guessed " + mStartActivity.task);
    }

    /**
     * 从前面调用可知，launchFlags是从Intent中获取的，是目标Activity组件启动的标志位，在launchFlags中，
     * 只有Intent.FLAG_ACTIVITY_NEW_TASK位被标记为1，其它位都为0.
     *
     * @param r                    被启动Activity对象封装
     * @param launchSingleInstance 是不是SingleInstance模式
     * @param launchSingleTask     是不是SingleTask模式
     * @param launchFlags          启动标记
     *
     * @return
     */
    private int adjustLaunchFlagsToDocumentMode(ActivityRecord r, boolean launchSingleInstance,
                                                boolean launchSingleTask, int launchFlags) {
        // 如果launchFlags是FLAG_ACTIVITY_NEW_DOCUMENT模式并且启动模式是singleInstance或者singleTask
        if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0 &&
                (launchSingleInstance || launchSingleTask)) {
            // We have a conflict between the Intent and the Activity manifest, manifest wins.
            // 如果Intent中的标记为和manifest中的有冲突，则以manifest中的为主
            Slog.i(TAG, "Ignoring FLAG_ACTIVITY_NEW_DOCUMENT, launchMode is " +
                    "\"singleInstance\" or \"singleTask\"");
            launchFlags &=
                    ~(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_MULTIPLE_TASK);
        } else {
            // 如果不满足上面条件，就会走这里，根据documentLaunchMode来设置启动标签
            switch (r.info.documentLaunchMode) {
                case ActivityInfo.DOCUMENT_LAUNCH_NONE:
                    break;
                case ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING:
                    launchFlags |= Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
                    break;
                case ActivityInfo.DOCUMENT_LAUNCH_ALWAYS:
                    launchFlags |= Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
                    break;
                case ActivityInfo.DOCUMENT_LAUNCH_NEVER:
                    launchFlags &= ~FLAG_ACTIVITY_MULTIPLE_TASK;
                    break;
            }
        }
        return launchFlags;
    }

    // 启动等待列表中的Activity，参数doResume传递过来是false
    final void doPendingActivityLaunchesLocked(boolean doResume) {
        while (!mPendingActivityLaunches.isEmpty()) {
            final PendingActivityLaunch pal = mPendingActivityLaunches.remove(0);
            // 如果mPendingActivityLaunches列表中没有了并且需要复用时设置为复用，这里是false
            // (等待列表中最后一个是最后加进来的，所以需要复用时选择最近的一个进行复用)
            final boolean resume = doResume && mPendingActivityLaunches.isEmpty();
            try {
                final int result = startActivityUnchecked(
                        pal.r, pal.sourceRecord, null, null, pal.startFlags, resume, null, null);
                postStartActivityUncheckedProcessing(
                        pal.r, result, mSupervisor.mFocusedStack.mStackId, mSourceRecord,
                        mTargetStack);
            } catch (Exception e) {
                Slog.e(TAG, "Exception during pending activity launch pal=" + pal, e);
                pal.sendErrorResult(e.getMessage());
            }
        }
    }

    private ActivityStack computeStackFocus(ActivityRecord r, boolean newTask, Rect bounds,
                                            int launchFlags, ActivityOptions aOptions) {
        final TaskRecord task = r.task;
        if (!(r.isApplicationActivity() || (task != null && task.isApplicationTask()))) {
            return mSupervisor.mHomeStack;
        }

        ActivityStack stack = getLaunchStack(r, launchFlags, task, aOptions);
        if (stack != null) {
            return stack;
        }

        if (task != null && task.stack != null) {
            stack = task.stack;
            if (stack.isOnHomeDisplay()) {
                if (mSupervisor.mFocusedStack != stack) {
                    if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                            "computeStackFocus: Setting " + "focused stack to r=" + r
                                    + " task=" + task);
                } else {
                    if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                            "computeStackFocus: Focused stack already="
                                    + mSupervisor.mFocusedStack);
                }
            }
            return stack;
        }

        final ActivityStackSupervisor.ActivityContainer container = r.mInitialActivityContainer;
        if (container != null) {
            // The first time put it on the desired stack, after this put on task stack.
            r.mInitialActivityContainer = null;
            return container.mStack;
        }

        // The fullscreen stack can contain any task regardless of if the task is resizeable
        // or not. So, we let the task go in the fullscreen task if it is the focus stack.
        // If the freeform or docked stack has focus, and the activity to be launched is resizeable,
        // we can also put it in the focused stack.
        final int focusedStackId = mSupervisor.mFocusedStack.mStackId;
        final boolean canUseFocusedStack = focusedStackId == FULLSCREEN_WORKSPACE_STACK_ID
                || (focusedStackId == DOCKED_STACK_ID && r.canGoInDockedStack())
                || (focusedStackId == FREEFORM_WORKSPACE_STACK_ID && r.isResizeableOrForced());
        if (canUseFocusedStack && (!newTask
                || mSupervisor.mFocusedStack.mActivityContainer.isEligibleForNewTasks())) {
            if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                    "computeStackFocus: Have a focused stack=" + mSupervisor.mFocusedStack);
            return mSupervisor.mFocusedStack;
        }

        // We first try to put the task in the first dynamic stack.
        final ArrayList<ActivityStack> homeDisplayStacks = mSupervisor.mHomeStack.mStacks;
        for (int stackNdx = homeDisplayStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            stack = homeDisplayStacks.get(stackNdx);
            if (!ActivityManager.StackId.isStaticStack(stack.mStackId)) {
                if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                        "computeStackFocus: Setting focused stack=" + stack);
                return stack;
            }
        }

        // If there is no suitable dynamic stack then we figure out which static stack to use.
        final int stackId = task != null ? task.getLaunchStackId() :
                bounds != null ? FREEFORM_WORKSPACE_STACK_ID :
                        FULLSCREEN_WORKSPACE_STACK_ID;
        stack = mSupervisor.getStack(stackId, CREATE_IF_NEEDED, ON_TOP);
        if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS, "computeStackFocus: New stack r="
                + r + " stackId=" + stack.mStackId);
        return stack;
    }

    private ActivityStack getLaunchStack(ActivityRecord r, int launchFlags, TaskRecord task,
                                         ActivityOptions aOptions) {

        // We are reusing a task, keep the stack!
        if (mReuseTask != null) {
            return mReuseTask.stack;
        }

        final int launchStackId =
                (aOptions != null) ? aOptions.getLaunchStackId() : INVALID_STACK_ID;

        if (isValidLaunchStackId(launchStackId, r)) {
            return mSupervisor.getStack(launchStackId, CREATE_IF_NEEDED, ON_TOP);
        } else if (launchStackId == DOCKED_STACK_ID) {
            // The preferred launch stack is the docked stack, but it isn't a valid launch stack
            // for this activity, so we put the activity in the fullscreen stack.
            return mSupervisor.getStack(FULLSCREEN_WORKSPACE_STACK_ID, CREATE_IF_NEEDED, ON_TOP);
        }

        if ((launchFlags & FLAG_ACTIVITY_LAUNCH_ADJACENT) == 0) {
            return null;
        }
        // Otherwise handle adjacent launch.

        // The parent activity doesn't want to launch the activity on top of itself, but
        // instead tries to put it onto other side in side-by-side mode.
        final ActivityStack parentStack = task != null ? task.stack
                : r.mInitialActivityContainer != null ? r.mInitialActivityContainer.mStack
                : mSupervisor.mFocusedStack;

        if (parentStack != mSupervisor.mFocusedStack) {
            // If task's parent stack is not focused - use it during adjacent launch.
            return parentStack;
        } else {
            if (mSupervisor.mFocusedStack != null && task == mSupervisor.mFocusedStack.topTask()) {
                // If task is already on top of focused stack - use it. We don't want to move the
                // existing focused task to adjacent stack, just deliver new intent in this case.
                return mSupervisor.mFocusedStack;
            }

            if (parentStack != null && parentStack.mStackId == DOCKED_STACK_ID) {
                // If parent was in docked stack, the natural place to launch another activity
                // will be fullscreen, so it can appear alongside the docked window.
                return mSupervisor.getStack(FULLSCREEN_WORKSPACE_STACK_ID, CREATE_IF_NEEDED,
                        ON_TOP);
            } else {
                // If the parent is not in the docked stack, we check if there is docked window
                // and if yes, we will launch into that stack. If not, we just put the new
                // activity into parent's stack, because we can't find a better place.
                final ActivityStack dockedStack = mSupervisor.getStack(DOCKED_STACK_ID);
                if (dockedStack != null
                        && dockedStack.getStackVisibilityLocked(r) == STACK_INVISIBLE) {
                    // There is a docked stack, but it isn't visible, so we can't launch into that.
                    return null;
                } else {
                    return dockedStack;
                }
            }
        }
    }

    private boolean isValidLaunchStackId(int stackId, ActivityRecord r) {
        if (stackId == INVALID_STACK_ID || stackId == HOME_STACK_ID
                || !StackId.isStaticStack(stackId)) {
            return false;
        }

        if (stackId != FULLSCREEN_WORKSPACE_STACK_ID
                && (!mService.mSupportsMultiWindow || !r.isResizeableOrForced())) {
            return false;
        }

        if (stackId == DOCKED_STACK_ID && r.canGoInDockedStack()) {
            return true;
        }

        if (stackId == FREEFORM_WORKSPACE_STACK_ID && !mService.mSupportsFreeformWindowManagement) {
            return false;
        }

        final boolean supportsPip = mService.mSupportsPictureInPicture
                && (r.supportsPictureInPicture() || mService.mForceResizableActivities);
        if (stackId == PINNED_STACK_ID && !supportsPip) {
            return false;
        }
        return true;
    }

    Rect getOverrideBounds(ActivityRecord r, ActivityOptions options, TaskRecord inTask) {
        Rect newBounds = null;
        if (options != null && (r.isResizeable() || (inTask != null && inTask.isResizeable()))) {
            if (mSupervisor.canUseActivityOptionsLaunchBounds(
                    options, options.getLaunchStackId())) {
                newBounds = TaskRecord.validateBounds(options.getLaunchBounds());
            }
        }
        return newBounds;
    }

    void setWindowManager(WindowManagerService wm) {
        mWindowManager = wm;
    }

    void removePendingActivityLaunchesLocked(ActivityStack stack) {
        for (int palNdx = mPendingActivityLaunches.size() - 1; palNdx >= 0; --palNdx) {
            PendingActivityLaunch pal = mPendingActivityLaunches.get(palNdx);
            if (pal.stack == stack) {
                mPendingActivityLaunches.remove(palNdx);
            }
        }
    }
}
