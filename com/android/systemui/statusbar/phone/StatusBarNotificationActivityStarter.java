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

package com.android.systemui.statusbar.phone;

import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED;
import static android.service.notification.NotificationListenerService.REASON_CLICK;

import static com.android.systemui.statusbar.phone.CentralSurfaces.getActivityOptions;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.IDreamManager;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.EventLog;
import android.view.View;

import androidx.annotation.VisibleForTesting;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.ActivityIntentHelper;
import com.android.systemui.EventLogTags;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.statusbar.NotificationClickNotifier;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationLaunchAnimatorControllerProvider;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.provider.LaunchFullScreenIntentProvider;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRowDragController;
import com.android.systemui.statusbar.notification.row.OnUserInteractionCallback;
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent;
import com.android.systemui.statusbar.policy.HeadsUpUtil;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.wmshell.BubblesManager;

import dagger.Lazy;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Inject;


/**
 * Status bar implementation of {@link NotificationActivityStarter}.
 */
@CentralSurfacesComponent.CentralSurfacesScope
class StatusBarNotificationActivityStarter implements NotificationActivityStarter {

    private final Context mContext;

    private final Handler mMainThreadHandler;
    private final Executor mUiBgExecutor;

    private final NotificationVisibilityProvider mVisibilityProvider;
    private final HeadsUpManagerPhone mHeadsUpManager;
    private final ActivityStarter mActivityStarter;
    private final NotificationClickNotifier mClickNotifier;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final KeyguardManager mKeyguardManager;
    private final IDreamManager mDreamManager;
    private final Optional<BubblesManager> mBubblesManagerOptional;
    private final Lazy<AssistManager> mAssistManagerLazy;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private final com.android.systemui.shade.ShadeController mShadeController;
    private final KeyguardStateController mKeyguardStateController;
    private final NotificationInterruptStateProvider mNotificationInterruptStateProvider;
    private final LockPatternUtils mLockPatternUtils;
    private final StatusBarRemoteInputCallback mStatusBarRemoteInputCallback;
    private final ActivityIntentHelper mActivityIntentHelper;
    private final FeatureFlags mFeatureFlags;

    private final MetricsLogger mMetricsLogger;
    private final StatusBarNotificationActivityStarterLogger mLogger;

    private final CentralSurfaces mCentralSurfaces;
    private final NotificationPresenter mPresenter;
    private final ShadeViewController mNotificationPanel;
    private final ActivityLaunchAnimator mActivityLaunchAnimator;
    private final NotificationLaunchAnimatorControllerProvider mNotificationAnimationProvider;
    private final UserTracker mUserTracker;
    private final OnUserInteractionCallback mOnUserInteractionCallback;

    private boolean mIsCollapsingToShowActivityOverLockscreen;

    @Inject
    StatusBarNotificationActivityStarter(
            Context context,
            Handler mainThreadHandler,
            Executor uiBgExecutor,
            NotificationVisibilityProvider visibilityProvider,
            HeadsUpManagerPhone headsUpManager,
            ActivityStarter activityStarter,
            NotificationClickNotifier clickNotifier,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            KeyguardManager keyguardManager,
            IDreamManager dreamManager,
            Optional<BubblesManager> bubblesManagerOptional,
            Lazy<AssistManager> assistManagerLazy,
            NotificationRemoteInputManager remoteInputManager,
            NotificationLockscreenUserManager lockscreenUserManager,
            ShadeController shadeController,
            KeyguardStateController keyguardStateController,
            NotificationInterruptStateProvider notificationInterruptStateProvider,
            LockPatternUtils lockPatternUtils,
            StatusBarRemoteInputCallback remoteInputCallback,
            ActivityIntentHelper activityIntentHelper,
            MetricsLogger metricsLogger,
            StatusBarNotificationActivityStarterLogger logger,
            OnUserInteractionCallback onUserInteractionCallback,
            CentralSurfaces centralSurfaces,
            NotificationPresenter presenter,
            ShadeViewController panel,
            ActivityLaunchAnimator activityLaunchAnimator,
            NotificationLaunchAnimatorControllerProvider notificationAnimationProvider,
            LaunchFullScreenIntentProvider launchFullScreenIntentProvider,
            FeatureFlags featureFlags,
            UserTracker userTracker) {
        mContext = context;
        mMainThreadHandler = mainThreadHandler;
        mUiBgExecutor = uiBgExecutor;
        mVisibilityProvider = visibilityProvider;
        mHeadsUpManager = headsUpManager;
        mActivityStarter = activityStarter;
        mClickNotifier = clickNotifier;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mKeyguardManager = keyguardManager;
        mDreamManager = dreamManager;
        mBubblesManagerOptional = bubblesManagerOptional;
        mAssistManagerLazy = assistManagerLazy;
        mRemoteInputManager = remoteInputManager;
        mLockscreenUserManager = lockscreenUserManager;
        mShadeController = shadeController;
        mKeyguardStateController = keyguardStateController;
        mNotificationInterruptStateProvider = notificationInterruptStateProvider;
        mLockPatternUtils = lockPatternUtils;
        mStatusBarRemoteInputCallback = remoteInputCallback;
        mActivityIntentHelper = activityIntentHelper;
        mFeatureFlags = featureFlags;
        mMetricsLogger = metricsLogger;
        mLogger = logger;
        mOnUserInteractionCallback = onUserInteractionCallback;
        // TODO: use KeyguardStateController#isOccluded to remove this dependency
        mCentralSurfaces = centralSurfaces;
        mPresenter = presenter;
        mNotificationPanel = panel;
        mActivityLaunchAnimator = activityLaunchAnimator;
        mNotificationAnimationProvider = notificationAnimationProvider;
        mUserTracker = userTracker;

        launchFullScreenIntentProvider.registerListener(entry -> launchFullScreenIntent(entry));
    }

    /**
     * Called when a notification is clicked.
     *
     * @param entry notification that was clicked
     * @param row row for that notification
     */
    @Override
    public void onNotificationClicked(NotificationEntry entry, ExpandableNotificationRow row) {
        mLogger.logStartingActivityFromClick(entry);

        if (mRemoteInputManager.isRemoteInputActive(entry)
                && !TextUtils.isEmpty(row.getActiveRemoteInputText())) {
            // We have an active remote input typed and the user clicked on the notification.
            // this was probably unintentional, so we're closing the edit text instead.
            mRemoteInputManager.closeRemoteInputs();
            return;
        }
        Notification notification = entry.getSbn().getNotification();
        final PendingIntent intent = notification.contentIntent != null
                ? notification.contentIntent
                : notification.fullScreenIntent;
        final boolean isBubble = entry.isBubble();

        // This code path is now executed for notification without a contentIntent.
        // The only valid case is Bubble notifications. Guard against other cases
        // entering here.
        if (intent == null && !isBubble) {
            mLogger.logNonClickableNotification(entry);
            return;
        }

        boolean isActivityIntent = intent != null && intent.isActivity() && !isBubble;
        final boolean willLaunchResolverActivity = isActivityIntent
                && mActivityIntentHelper.wouldPendingLaunchResolverActivity(intent,
                mLockscreenUserManager.getCurrentUserId());
        final boolean animate = !willLaunchResolverActivity
                && mCentralSurfaces.shouldAnimateLaunch(isActivityIntent);
        boolean showOverLockscreen = mKeyguardStateController.isShowing() && intent != null
                && mActivityIntentHelper.wouldPendingShowOverLockscreen(intent,
                mLockscreenUserManager.getCurrentUserId());
        ActivityStarter.OnDismissAction postKeyguardAction = new ActivityStarter.OnDismissAction() {
            @Override
            public boolean onDismiss() {
                return handleNotificationClickAfterKeyguardDismissed(
                        entry, row, intent, isActivityIntent, animate, showOverLockscreen);
            }

            @Override
            public boolean willRunAnimationOnKeyguard() {
                return animate;
            }
        };
        if (showOverLockscreen) {
            mIsCollapsingToShowActivityOverLockscreen = true;
            postKeyguardAction.onDismiss();
        } else {
            mActivityStarter.dismissKeyguardThenExecute(
                    postKeyguardAction,
                    null,
                    willLaunchResolverActivity);
        }
    }

    private boolean handleNotificationClickAfterKeyguardDismissed(
            NotificationEntry entry,
            ExpandableNotificationRow row,
            PendingIntent intent,
            boolean isActivityIntent,
            boolean animate,
            boolean showOverLockscreen) {
        mLogger.logHandleClickAfterKeyguardDismissed(entry);

        final Runnable runnable = () -> handleNotificationClickAfterPanelCollapsed(
                entry, row, intent, isActivityIntent, animate);

        if (showOverLockscreen) {
            mShadeController.addPostCollapseAction(runnable);
            mShadeController.collapseShade(true /* animate */);
        } else if (mKeyguardStateController.isShowing()
                && mCentralSurfaces.isOccluded()) {
            mStatusBarKeyguardViewManager.addAfterKeyguardGoneRunnable(runnable);
            mShadeController.collapseShade();
        } else {
            runnable.run();
        }

        // Always defer the keyguard dismiss when animating.
        return animate || !mNotificationPanel.isFullyCollapsed();
    }

    private void handleNotificationClickAfterPanelCollapsed(
            NotificationEntry entry,
            ExpandableNotificationRow row,
            PendingIntent intent,
            boolean isActivityIntent,
            boolean animate) {
        String notificationKey = entry.getKey();
        mLogger.logHandleClickAfterPanelCollapsed(entry);

        try {
            // The intent we are sending is for the application, which
            // won't have permission to immediately start an activity after
            // the user switches to home.  We know it is safe to do at this
            // point, so make sure new activity switches are now allowed.
            ActivityManager.getService().resumeAppSwitches();
        } catch (RemoteException e) {
        }
        // If the notification should be cancelled on click and we are launching a work activity in
        // a locked profile with separate challenge, we defer the activity action and cancelling of
        // the notification until work challenge is unlocked. If the notification shouldn't be
        // cancelled, the work challenge will be shown by ActivityManager if necessary anyway.
        if (isActivityIntent && shouldAutoCancel(entry.getSbn())) {
            final int userId = intent.getCreatorUserHandle().getIdentifier();
            if (mLockPatternUtils.isSeparateProfileChallengeEnabled(userId)
                    && mKeyguardManager.isDeviceLocked(userId)) {
                // TODO(b/28935539): should allow certain activities to
                // bypass work challenge
                if (mStatusBarRemoteInputCallback.startWorkChallengeIfNecessary(userId,
                        intent.getIntentSender(), notificationKey)) {
                    removeHunAfterClick(row);
                    // Show work challenge, do not run PendingIntent and
                    // remove notification
                    collapseOnMainThread();
                    return;
                }
            }
        }
        Intent fillInIntent = null;
        CharSequence remoteInputText = null;
        if (!TextUtils.isEmpty(entry.remoteInputText)) {
            remoteInputText = entry.remoteInputText;
        }
        if (!TextUtils.isEmpty(remoteInputText)
                && !mRemoteInputManager.isSpinning(notificationKey)) {
            fillInIntent = new Intent().putExtra(Notification.EXTRA_REMOTE_INPUT_DRAFT,
                    remoteInputText.toString());
        }
        final boolean canBubble = entry.canBubble();
        if (canBubble) {
            mLogger.logExpandingBubble(entry);
            removeHunAfterClick(row);
            expandBubbleStackOnMainThread(entry);
        } else {
            startNotificationIntent(intent, fillInIntent, entry, row, animate, isActivityIntent);
        }
        if (isActivityIntent || canBubble) {
            mAssistManagerLazy.get().hideAssist();
        }

        final NotificationVisibility nv = mVisibilityProvider.obtain(entry, true);

        if (!canBubble && (shouldAutoCancel(entry.getSbn())
                || mRemoteInputManager.isNotificationKeptForRemoteInputHistory(notificationKey))) {
            final Runnable removeNotification =
                    mOnUserInteractionCallback.registerFutureDismissal(entry, REASON_CLICK);
            // Immediately remove notification from visually showing.
            // We have to post the removal to the UI thread for synchronization.
            mMainThreadHandler.post(() -> {
                if (mPresenter.isCollapsing()) {
                    // To avoid lags we're only performing the remove after the shade is collapsed
                    mShadeController.addPostCollapseAction(removeNotification);
                } else {
                    removeNotification.run();
                }
            });
        }

        // inform NMS that the notification was clicked
        mClickNotifier.onNotificationClick(notificationKey, nv);

        mIsCollapsingToShowActivityOverLockscreen = false;
    }

    /**
     * Called when a notification is dropped on proper target window.
     * Intent that is included in this entry notification,
     * will be sent by {@link ExpandableNotificationRowDragController}
     *
     * @param entry notification entry that is dropped.
     */
    @Override
    public void onDragSuccess(NotificationEntry entry) {
        // this method is not responsible for intent sending.
        // will focus follow operation only after drag-and-drop that notification.
        final NotificationVisibility nv = mVisibilityProvider.obtain(entry, true);

        String notificationKey = entry.getKey();
        if (shouldAutoCancel(entry.getSbn())
                || mRemoteInputManager.isNotificationKeptForRemoteInputHistory(notificationKey)) {
            final Runnable removeNotification =
                    mOnUserInteractionCallback.registerFutureDismissal(entry, REASON_CLICK);
            // Immediately remove notification from visually showing.
            // We have to post the removal to the UI thread for synchronization.
            mMainThreadHandler.post(() -> {
                if (mPresenter.isCollapsing()) {
                    // To avoid lags we're only performing the remove
                    // after the shade is collapsed
                    mShadeController.addPostCollapseAction(removeNotification);
                } else {
                    removeNotification.run();
                }
            });
        }

        // inform NMS that the notification was clicked
        mClickNotifier.onNotificationClick(notificationKey, nv);

        mIsCollapsingToShowActivityOverLockscreen = false;
    }

    private void expandBubbleStackOnMainThread(NotificationEntry entry) {
        if (!mBubblesManagerOptional.isPresent()) {
            return;
        }

        if (Looper.getMainLooper().isCurrentThread()) {
            expandBubbleStack(entry);
        } else {
            mMainThreadHandler.post(() -> expandBubbleStack(entry));
        }
    }

    private void expandBubbleStack(NotificationEntry entry) {
        mBubblesManagerOptional.get().expandStackAndSelectBubble(entry);
        mShadeController.collapseShade();
    }

    private void startNotificationIntent(
            PendingIntent intent,
            Intent fillInIntent,
            NotificationEntry entry,
            ExpandableNotificationRow row,
            boolean animate,
            boolean isActivityIntent) {
        mLogger.logStartNotificationIntent(entry);
        try {
            ActivityLaunchAnimator.Controller animationController =
                    new StatusBarLaunchAnimatorController(
                            mNotificationAnimationProvider.getAnimatorController(row, null),
                            mCentralSurfaces,
                            isActivityIntent);
            mActivityLaunchAnimator.startPendingIntentWithAnimation(
                    animationController,
                    animate,
                    intent.getCreatorPackage(),
                    (adapter) -> {
                        long eventTime = row.getAndResetLastActionUpTime();
                        Bundle options = eventTime > 0
                                ? getActivityOptions(
                                mCentralSurfaces.getDisplayId(),
                                adapter,
                                mKeyguardStateController.isShowing(),
                                eventTime)
                                : getActivityOptions(mCentralSurfaces.getDisplayId(), adapter);
                        int result = intent.sendAndReturnResult(mContext, 0, fillInIntent, null,
                                null, null, options);
                        mLogger.logSendPendingIntent(entry, intent, result);
                        return result;
                    });
        } catch (PendingIntent.CanceledException e) {
            // the stack trace isn't very helpful here.
            // Just log the exception message.
            mLogger.logSendingIntentFailed(e);
            // TODO: Dismiss Keyguard.
        }
    }

    @Override
    public void startNotificationGutsIntent(final Intent intent, final int appUid,
            ExpandableNotificationRow row) {
        boolean animate = mCentralSurfaces.shouldAnimateLaunch(true /* isActivityIntent */);
        ActivityStarter.OnDismissAction onDismissAction = new ActivityStarter.OnDismissAction() {
            @Override
            public boolean onDismiss() {
                AsyncTask.execute(() -> {
                    ActivityLaunchAnimator.Controller animationController =
                            new StatusBarLaunchAnimatorController(
                                    mNotificationAnimationProvider.getAnimatorController(row),
                                    mCentralSurfaces, true /* isActivityIntent */);

                    mActivityLaunchAnimator.startIntentWithAnimation(
                            animationController, animate, intent.getPackage(),
                            (adapter) -> TaskStackBuilder.create(mContext)
                                    .addNextIntentWithParentStack(intent)
                                    .startActivities(getActivityOptions(
                                            mCentralSurfaces.getDisplayId(),
                                            adapter),
                                            new UserHandle(UserHandle.getUserId(appUid))));
                });
                return true;
            }

            @Override
            public boolean willRunAnimationOnKeyguard() {
                return animate;
            }
        };
        mActivityStarter.dismissKeyguardThenExecute(onDismissAction, null,
                false /* afterKeyguardGone */);
    }

    @Override
    public void startHistoryIntent(View view, boolean showHistory) {
        boolean animate = mCentralSurfaces.shouldAnimateLaunch(true /* isActivityIntent */);
        ActivityStarter.OnDismissAction onDismissAction = new ActivityStarter.OnDismissAction() {
            @Override
            public boolean onDismiss() {
                AsyncTask.execute(() -> {
                    Intent intent = showHistory ? new Intent(
                            Settings.ACTION_NOTIFICATION_HISTORY) : new Intent(
                            Settings.ACTION_NOTIFICATION_SETTINGS);
                    TaskStackBuilder tsb = TaskStackBuilder.create(mContext)
                            .addNextIntent(new Intent(Settings.ACTION_NOTIFICATION_SETTINGS));
                    if (showHistory) {
                        tsb.addNextIntent(intent);
                    }

                    ActivityLaunchAnimator.Controller viewController =
                            ActivityLaunchAnimator.Controller.fromView(view,
                                    InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON
                            );
                    ActivityLaunchAnimator.Controller animationController =
                            viewController == null ? null
                                : new StatusBarLaunchAnimatorController(viewController,
                                        mCentralSurfaces,
                                    true /* isActivityIntent */);

                    mActivityLaunchAnimator.startIntentWithAnimation(animationController, animate,
                            intent.getPackage(),
                            (adapter) -> tsb.startActivities(
                                    getActivityOptions(mCentralSurfaces.getDisplayId(), adapter),
                                    mUserTracker.getUserHandle()));
                });
                return true;
            }

            @Override
            public boolean willRunAnimationOnKeyguard() {
                return animate;
            }
        };
        mActivityStarter.dismissKeyguardThenExecute(onDismissAction, null,
                false /* afterKeyguardGone */);
    }

    private void removeHunAfterClick(ExpandableNotificationRow row) {
        String key = row.getEntry().getSbn().getKey();
        if (mHeadsUpManager != null && mHeadsUpManager.isAlerting(key)) {
            // Release the HUN notification to the shade.
            if (mPresenter.isPresenterFullyCollapsed()) {
                HeadsUpUtil.setNeedsHeadsUpDisappearAnimationAfterClick(row, true);
            }

            // In most cases, when FLAG_AUTO_CANCEL is set, the notification will
            // become canceled shortly by NoMan, but we can't assume that.
            mHeadsUpManager.removeNotification(key, true /* releaseImmediately */);
        }
    }

    @VisibleForTesting
    void launchFullScreenIntent(NotificationEntry entry) {
        // Skip if device is in VR mode.
        if (mPresenter.isDeviceInVrMode()) {
            mLogger.logFullScreenIntentSuppressedByVR(entry);
            return;
        }
        // Stop screensaver if the notification has a fullscreen intent.
        // (like an incoming phone call)
        mUiBgExecutor.execute(() -> {
            try {
                mDreamManager.awaken();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        });

        // not immersive & a fullscreen alert should be shown
        final PendingIntent fullScreenIntent =
                entry.getSbn().getNotification().fullScreenIntent;
        mLogger.logSendingFullScreenIntent(entry, fullScreenIntent);
        try {
            EventLog.writeEvent(EventLogTags.SYSUI_FULLSCREEN_NOTIFICATION,
                    entry.getKey());
            mCentralSurfaces.wakeUpForFullScreenIntent();

            ActivityOptions options = ActivityOptions.makeBasic();
            options.setPendingIntentBackgroundActivityStartMode(
                    MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            fullScreenIntent.sendAndReturnResult(null, 0, null, null, null, null,
                    options.toBundle());
            entry.notifyFullScreenIntentLaunched();

            mMetricsLogger.count("note_fullscreen", 1);

            String activityName;
            List<ResolveInfo> resolveInfos = fullScreenIntent.queryIntentComponents(0);
            if (resolveInfos.size() > 0 && resolveInfos.get(0) != null
                    && resolveInfos.get(0).activityInfo != null
                    && resolveInfos.get(0).activityInfo.name != null) {
                activityName = resolveInfos.get(0).activityInfo.name;
            } else {
                activityName = "";
            }
            FrameworkStatsLog.write(FrameworkStatsLog.FULL_SCREEN_INTENT_LAUNCHED,
                    fullScreenIntent.getCreatorUid(),
                    activityName);
        } catch (PendingIntent.CanceledException e) {
            // ignore
        }
    }

    @Override
    public boolean isCollapsingToShowActivityOverLockscreen() {
        return mIsCollapsingToShowActivityOverLockscreen;
    }

    private static boolean shouldAutoCancel(StatusBarNotification sbn) {
        int flags = sbn.getNotification().flags;
        if ((flags & Notification.FLAG_AUTO_CANCEL) != Notification.FLAG_AUTO_CANCEL) {
            return false;
        }
        return true;
    }

    private void collapseOnMainThread() {
        if (Looper.getMainLooper().isCurrentThread()) {
            mShadeController.collapseShade();
        } else {
            mMainThreadHandler.post(mShadeController::collapseShade);
        }
    }
}
