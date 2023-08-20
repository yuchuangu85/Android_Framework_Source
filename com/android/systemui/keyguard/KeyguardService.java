/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.keyguard;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.view.RemoteAnimationTarget.MODE_CLOSING;
import static android.view.RemoteAnimationTarget.MODE_OPENING;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_OCCLUDE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_OCCLUDE;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_OCCLUDE_BY_DREAM;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_OLD_NONE;
import static android.view.WindowManager.TransitionFlags;
import static android.view.WindowManager.TransitionOldType;
import static android.view.WindowManager.TransitionType;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.Service;
import android.app.WindowConfiguration;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.WindowManagerPolicyConstants;
import android.window.IRemoteTransition;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.TransitionInfo;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IKeyguardDrawnCallback;
import com.android.internal.policy.IKeyguardExitCallback;
import com.android.internal.policy.IKeyguardService;
import com.android.internal.policy.IKeyguardStateCallback;
import com.android.keyguard.mediator.ScreenOnCoordinator;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.settings.DisplayTracker;
import com.android.wm.shell.transition.ShellTransitions;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.util.TransitionUtil;

import java.util.ArrayList;

import javax.inject.Inject;

public class KeyguardService extends Service {
    static final String TAG = "KeyguardService";
    static final String PERMISSION = android.Manifest.permission.CONTROL_KEYGUARD;

    private final KeyguardViewMediator mKeyguardViewMediator;
    private final KeyguardLifecyclesDispatcher mKeyguardLifecyclesDispatcher;
    private final ScreenOnCoordinator mScreenOnCoordinator;
    private final ShellTransitions mShellTransitions;
    private final DisplayTracker mDisplayTracker;

    private static int newModeToLegacyMode(int newMode) {
        switch (newMode) {
            case WindowManager.TRANSIT_OPEN:
            case WindowManager.TRANSIT_TO_FRONT:
                return MODE_OPENING;
            case WindowManager.TRANSIT_CLOSE:
            case WindowManager.TRANSIT_TO_BACK:
                return MODE_CLOSING;
            default:
                return 2; // MODE_CHANGING
        }
    }

    private static RemoteAnimationTarget[] wrap(TransitionInfo info, boolean wallpapers,
            SurfaceControl.Transaction t, ArrayMap<SurfaceControl, SurfaceControl> leashMap) {
        final ArrayList<RemoteAnimationTarget> out = new ArrayList<>();
        for (int i = 0; i < info.getChanges().size(); i++) {
            boolean changeIsWallpaper =
                    (info.getChanges().get(i).getFlags() & TransitionInfo.FLAG_IS_WALLPAPER) != 0;
            if (wallpapers != changeIsWallpaper) continue;

            final TransitionInfo.Change change = info.getChanges().get(i);
            final ActivityManager.RunningTaskInfo taskInfo = change.getTaskInfo();
            final int taskId = taskInfo != null ? change.getTaskInfo().taskId : -1;

            if (taskId != -1 && change.getParent() != null) {
                final TransitionInfo.Change parentChange = info.getChange(change.getParent());
                if (parentChange != null && parentChange.getTaskInfo() != null) {
                    // Only adding the root task as the animation target.
                    continue;
                }
            }

            final RemoteAnimationTarget target = TransitionUtil.newTarget(change,
                    // wallpapers go into the "below" layer space
                    info.getChanges().size() - i,
                    // keyguard treats wallpaper as translucent
                    (change.getFlags() & TransitionInfo.FLAG_SHOW_WALLPAPER) != 0,
                    info, t, leashMap);

            out.add(target);
        }
        return out.toArray(new RemoteAnimationTarget[out.size()]);
    }

    private static @TransitionOldType int getTransitionOldType(@TransitionType int type,
            @TransitionFlags int flags, RemoteAnimationTarget[] apps) {
        if (type == TRANSIT_KEYGUARD_GOING_AWAY
                || (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY) != 0) {
            return apps.length == 0 ? TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER
                    : TRANSIT_OLD_KEYGUARD_GOING_AWAY;
        } else if (type == TRANSIT_KEYGUARD_OCCLUDE) {
            boolean isOccludeByDream = apps.length > 0 && apps[0].taskInfo.topActivityType
                    == WindowConfiguration.ACTIVITY_TYPE_DREAM;
            if (isOccludeByDream) return TRANSIT_OLD_KEYGUARD_OCCLUDE_BY_DREAM;
            return TRANSIT_OLD_KEYGUARD_OCCLUDE;
        } else if (type == TRANSIT_KEYGUARD_UNOCCLUDE) {
            return TRANSIT_OLD_KEYGUARD_UNOCCLUDE;
        } else {
            Slog.d(TAG, "Unexpected transit type: " + type);
            return TRANSIT_OLD_NONE;
        }
    }

    // Wrap Keyguard going away animation.
    // Note: Also used for wrapping occlude by Dream animation. It works (with some redundancy).
    public static IRemoteTransition wrap(IRemoteAnimationRunner runner) {
        return new IRemoteTransition.Stub() {

            private final ArrayMap<SurfaceControl, SurfaceControl> mLeashMap = new ArrayMap<>();

            @GuardedBy("mLeashMap")
            private IRemoteTransitionFinishedCallback mFinishCallback = null;

            @Override
            public void startAnimation(IBinder transition, TransitionInfo info,
                    SurfaceControl.Transaction t, IRemoteTransitionFinishedCallback finishCallback)
                    throws RemoteException {
                Slog.d(TAG, "Starts IRemoteAnimationRunner: info=" + info);

                synchronized (mLeashMap) {
                    final RemoteAnimationTarget[] apps =
                            wrap(info, false /* wallpapers */, t, mLeashMap);
                    final RemoteAnimationTarget[] wallpapers =
                            wrap(info, true /* wallpapers */, t, mLeashMap);
                    final RemoteAnimationTarget[] nonApps = new RemoteAnimationTarget[0];

                    // Set alpha back to 1 for the independent changes because we will be animating
                    // children instead.
                    for (TransitionInfo.Change chg : info.getChanges()) {
                        if (TransitionInfo.isIndependent(chg, info)) {
                            t.setAlpha(chg.getLeash(), 1.f);
                        }
                    }
                    initAlphaForAnimationTargets(t, apps);
                    initAlphaForAnimationTargets(t, wallpapers);
                    t.apply();
                    mFinishCallback = finishCallback;
                    runner.onAnimationStart(
                            getTransitionOldType(info.getType(), info.getFlags(), apps),
                            apps, wallpapers, nonApps,
                            new IRemoteAnimationFinishedCallback.Stub() {
                                @Override
                                public void onAnimationFinished() throws RemoteException {
                                    synchronized (mLeashMap) {
                                        Slog.d(TAG, "Finish IRemoteAnimationRunner.");
                                        finish();
                                    }
                                }
                            }
                    );
                }
            }

            public void mergeAnimation(IBinder candidateTransition, TransitionInfo candidateInfo,
                    SurfaceControl.Transaction candidateT, IBinder currentTransition,
                    IRemoteTransitionFinishedCallback candidateFinishCallback)
                    throws RemoteException {
                try {
                    synchronized (mLeashMap) {
                        runner.onAnimationCancelled();
                        finish();
                    }
                } catch (RemoteException e) {
                    // nothing, we'll just let it finish on its own I guess.
                }
            }

            private static void initAlphaForAnimationTargets(@NonNull SurfaceControl.Transaction t,
                    @NonNull RemoteAnimationTarget[] targets) {
                for (RemoteAnimationTarget target : targets) {
                    if (target.mode != MODE_OPENING) continue;
                    t.setAlpha(target.leash, 0.f);
                }
            }

            @GuardedBy("mLeashMap")
            private void finish() throws RemoteException {
                mLeashMap.clear();
                final IRemoteTransitionFinishedCallback finishCallback = mFinishCallback;
                if (finishCallback != null) {
                    mFinishCallback = null;
                    finishCallback.onTransitionFinished(null /* wct */, null /* t */);
                }
            }
        };
    }

    @Inject
    public KeyguardService(KeyguardViewMediator keyguardViewMediator,
                           KeyguardLifecyclesDispatcher keyguardLifecyclesDispatcher,
                           ScreenOnCoordinator screenOnCoordinator,
                           ShellTransitions shellTransitions,
                           DisplayTracker displayTracker) {
        super();
        mKeyguardViewMediator = keyguardViewMediator;
        mKeyguardLifecyclesDispatcher = keyguardLifecyclesDispatcher;
        mScreenOnCoordinator = screenOnCoordinator;
        mShellTransitions = shellTransitions;
        mDisplayTracker = displayTracker;
    }

    @Override
    public void onCreate() {
        ((SystemUIApplication) getApplication()).startServicesIfNeeded();

        if (mShellTransitions == null || !Transitions.ENABLE_SHELL_TRANSITIONS) {
            RemoteAnimationDefinition definition = new RemoteAnimationDefinition();
            final RemoteAnimationAdapter exitAnimationAdapter =
                    new RemoteAnimationAdapter(
                            mKeyguardViewMediator.getExitAnimationRunner(), 0, 0);
            definition.addRemoteAnimation(TRANSIT_OLD_KEYGUARD_GOING_AWAY,
                    exitAnimationAdapter);
            definition.addRemoteAnimation(TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER,
                    exitAnimationAdapter);
            final RemoteAnimationAdapter occludeAnimationAdapter =
                    new RemoteAnimationAdapter(
                            mKeyguardViewMediator.getOccludeAnimationRunner(), 0, 0);
            definition.addRemoteAnimation(TRANSIT_OLD_KEYGUARD_OCCLUDE,
                    occludeAnimationAdapter);

            final RemoteAnimationAdapter occludeByDreamAnimationAdapter =
                    new RemoteAnimationAdapter(
                            mKeyguardViewMediator.getOccludeByDreamAnimationRunner(), 0, 0);
            definition.addRemoteAnimation(TRANSIT_OLD_KEYGUARD_OCCLUDE_BY_DREAM,
                    occludeByDreamAnimationAdapter);

            final RemoteAnimationAdapter unoccludeAnimationAdapter =
                    new RemoteAnimationAdapter(
                            mKeyguardViewMediator.getUnoccludeAnimationRunner(), 0, 0);
            definition.addRemoteAnimation(TRANSIT_OLD_KEYGUARD_UNOCCLUDE,
                    unoccludeAnimationAdapter);
            ActivityTaskManager.getInstance().registerRemoteAnimationsForDisplay(
                    mDisplayTracker.getDefaultDisplayId(), definition);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    void checkPermission() {
        // Avoid deadlock by avoiding calling back into the system process.
        if (Binder.getCallingUid() == Process.SYSTEM_UID) return;

        // Otherwise,explicitly check for caller permission ...
        if (getBaseContext().checkCallingOrSelfPermission(PERMISSION) != PERMISSION_GRANTED) {
            Log.w(TAG, "Caller needs permission '" + PERMISSION + "' to call " + Debug.getCaller());
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + PERMISSION);
        }
    }

    final IRemoteTransition mOccludeAnimation = new IRemoteTransition.Stub() {
        @Override
        public void startAnimation(IBinder transition, TransitionInfo info,
                SurfaceControl.Transaction t, IRemoteTransitionFinishedCallback finishCallback)
                    throws RemoteException {
            t.apply();
            mBinder.setOccluded(true /* isOccluded */, true /* animate */);
            finishCallback.onTransitionFinished(null /* wct */, null /* wctCB */);
            info.releaseAllSurfaces();
        }

        @Override
        public void mergeAnimation(IBinder transition, TransitionInfo info,
                SurfaceControl.Transaction t, IBinder mergeTarget,
                IRemoteTransitionFinishedCallback finishCallback) {
            t.close();
            info.releaseAllSurfaces();
        }
    };

    final IRemoteTransition mUnoccludeAnimation = new IRemoteTransition.Stub() {
        @Override
        public void startAnimation(IBinder transition, TransitionInfo info,
                SurfaceControl.Transaction t, IRemoteTransitionFinishedCallback finishCallback)
                throws RemoteException {
            t.apply();
            mBinder.setOccluded(false /* isOccluded */, true /* animate */);
            finishCallback.onTransitionFinished(null /* wct */, null /* wctCB */);
            info.releaseAllSurfaces();
        }

        @Override
        public void mergeAnimation(IBinder transition, TransitionInfo info,
                SurfaceControl.Transaction t, IBinder mergeTarget,
                IRemoteTransitionFinishedCallback finishCallback) {
            t.close();
            info.releaseAllSurfaces();
        }
    };

    private final IKeyguardService.Stub mBinder = new IKeyguardService.Stub() {

        @Override // Binder interface
        public void addStateMonitorCallback(IKeyguardStateCallback callback) {
            checkPermission();
            mKeyguardViewMediator.addStateMonitorCallback(callback);
        }

        @Override // Binder interface
        public void verifyUnlock(IKeyguardExitCallback callback) {
            Trace.beginSection("KeyguardService.mBinder#verifyUnlock");
            checkPermission();
            mKeyguardViewMediator.verifyUnlock(callback);
            Trace.endSection();
        }

        @Override // Binder interface
        public void setOccluded(boolean isOccluded, boolean animate) {
            Log.d(TAG, "setOccluded(" + isOccluded + ")");

            Trace.beginSection("KeyguardService.mBinder#setOccluded");
            checkPermission();
            mKeyguardViewMediator.setOccluded(isOccluded, animate);
            Trace.endSection();
        }

        @Override // Binder interface
        public void dismiss(IKeyguardDismissCallback callback, CharSequence message) {
            checkPermission();
            mKeyguardViewMediator.dismiss(callback, message);
        }

        @Override // Binder interface
        public void onDreamingStarted() {
            checkPermission();
            mKeyguardViewMediator.onDreamingStarted();
        }

        @Override // Binder interface
        public void onDreamingStopped() {
            checkPermission();
            mKeyguardViewMediator.onDreamingStopped();
        }

        @Override // Binder interface
        public void onStartedGoingToSleep(@PowerManager.GoToSleepReason int pmSleepReason) {
            checkPermission();
            mKeyguardViewMediator.onStartedGoingToSleep(
                    WindowManagerPolicyConstants.translateSleepReasonToOffReason(pmSleepReason));
            mKeyguardLifecyclesDispatcher.dispatch(
                    KeyguardLifecyclesDispatcher.STARTED_GOING_TO_SLEEP, pmSleepReason);
        }

        @Override // Binder interface
        public void onFinishedGoingToSleep(
                @PowerManager.GoToSleepReason int pmSleepReason, boolean cameraGestureTriggered) {
            checkPermission();
            mKeyguardViewMediator.onFinishedGoingToSleep(
                    WindowManagerPolicyConstants.translateSleepReasonToOffReason(pmSleepReason),
                    cameraGestureTriggered);
            mKeyguardLifecyclesDispatcher.dispatch(
                    KeyguardLifecyclesDispatcher.FINISHED_GOING_TO_SLEEP);
        }

        @Override // Binder interface
        public void onStartedWakingUp(
                @PowerManager.WakeReason int pmWakeReason, boolean cameraGestureTriggered) {
            Trace.beginSection("KeyguardService.mBinder#onStartedWakingUp");
            checkPermission();
            mKeyguardViewMediator.onStartedWakingUp(pmWakeReason, cameraGestureTriggered);
            mKeyguardLifecyclesDispatcher.dispatch(
                    KeyguardLifecyclesDispatcher.STARTED_WAKING_UP, pmWakeReason);
            Trace.endSection();
        }

        @Override // Binder interface
        public void onFinishedWakingUp() {
            Trace.beginSection("KeyguardService.mBinder#onFinishedWakingUp");
            checkPermission();
            mKeyguardLifecyclesDispatcher.dispatch(KeyguardLifecyclesDispatcher.FINISHED_WAKING_UP);
            Trace.endSection();
        }

        @Override // Binder interface
        public void onScreenTurningOn(IKeyguardDrawnCallback callback) {
            Trace.beginSection("KeyguardService.mBinder#onScreenTurningOn");
            checkPermission();
            mKeyguardLifecyclesDispatcher.dispatch(KeyguardLifecyclesDispatcher.SCREEN_TURNING_ON,
                    callback);

            final String onDrawWaitingTraceTag = "Waiting for KeyguardDrawnCallback#onDrawn";
            final int traceCookie = System.identityHashCode(callback);
            Trace.beginAsyncSection(onDrawWaitingTraceTag, traceCookie);

            // Ensure the drawn callback is only ever called once
            mScreenOnCoordinator.onScreenTurningOn(new Runnable() {
                boolean mInvoked;
                @Override
                public void run() {
                    if (callback == null) return;
                    if (!mInvoked) {
                        mInvoked = true;
                        try {
                            Trace.endAsyncSection(onDrawWaitingTraceTag, traceCookie);
                            callback.onDrawn();
                        } catch (RemoteException e) {
                            Log.w(TAG, "Exception calling onDrawn():", e);
                        }
                    } else {
                        Log.w(TAG, "KeyguardDrawnCallback#onDrawn() invoked > 1 times");
                    }
                }
            });

            Trace.endSection();
        }

        @Override // Binder interface
        public void onScreenTurnedOn() {
            Trace.beginSection("KeyguardService.mBinder#onScreenTurnedOn");
            checkPermission();
            mKeyguardLifecyclesDispatcher.dispatch(KeyguardLifecyclesDispatcher.SCREEN_TURNED_ON);
            mScreenOnCoordinator.onScreenTurnedOn();
            Trace.endSection();
        }

        @Override // Binder interface
        public void onScreenTurningOff() {
            checkPermission();
            mKeyguardLifecyclesDispatcher.dispatch(KeyguardLifecyclesDispatcher.SCREEN_TURNING_OFF);
        }

        @Override // Binder interface
        public void onScreenTurnedOff() {
            checkPermission();
            mKeyguardViewMediator.onScreenTurnedOff();
            mKeyguardLifecyclesDispatcher.dispatch(KeyguardLifecyclesDispatcher.SCREEN_TURNED_OFF);
            mScreenOnCoordinator.onScreenTurnedOff();
        }

        @Override // Binder interface
        public void setKeyguardEnabled(boolean enabled) {
            checkPermission();
            mKeyguardViewMediator.setKeyguardEnabled(enabled);
        }

        @Override // Binder interface
        public void onSystemReady() {
            Trace.beginSection("KeyguardService.mBinder#onSystemReady");
            checkPermission();
            mKeyguardViewMediator.onSystemReady();
            Trace.endSection();
        }

        @Override // Binder interface
        public void doKeyguardTimeout(Bundle options) {
            checkPermission();
            mKeyguardViewMediator.doKeyguardTimeout(options);
        }

        @Override // Binder interface
        public void setSwitchingUser(boolean switching) {
            checkPermission();
            mKeyguardViewMediator.setSwitchingUser(switching);
        }

        @Override // Binder interface
        public void setCurrentUser(int userId) {
            checkPermission();
            mKeyguardViewMediator.setCurrentUser(userId);
        }

        @Override
        public void onBootCompleted() {
            checkPermission();
            mKeyguardViewMediator.onBootCompleted();
        }

        /**
         * @deprecated When remote animation is enabled, this won't be called anymore. Use
         * {@code IRemoteAnimationRunner#onAnimationStart} instead.
         */
        @Deprecated
        @Override
        public void startKeyguardExitAnimation(long startTime, long fadeoutDuration) {
            Trace.beginSection("KeyguardService.mBinder#startKeyguardExitAnimation");
            checkPermission();
            mKeyguardViewMediator.startKeyguardExitAnimation(startTime, fadeoutDuration);
            Trace.endSection();
        }

        @Override
        public void onShortPowerPressedGoHome() {
            checkPermission();
            mKeyguardViewMediator.onShortPowerPressedGoHome();
        }

        @Override
        public void dismissKeyguardToLaunch(Intent intentToLaunch) {
            checkPermission();
            mKeyguardViewMediator.dismissKeyguardToLaunch(intentToLaunch);
        }

        @Override
        public void onSystemKeyPressed(int keycode) {
            checkPermission();
            mKeyguardViewMediator.onSystemKeyPressed(keycode);
        }
    };
}

