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
package android.app;

import android.annotation.NonNull;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.ClientTransactionItem;
import android.app.servertransaction.PendingTransactionActions;
import android.app.servertransaction.TransactionExecutor;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.IBinder;
import android.util.MergedConfiguration;
import android.view.DisplayAdjustments.FixedRotationAdjustments;
import android.window.SplashScreenView.SplashScreenViewParcelable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.ReferrerIntent;

import java.util.List;
import java.util.Map;

/**
 * Defines operations that a {@link android.app.servertransaction.ClientTransaction} or its items
 * can perform on client.
 * @hide
 */
public abstract class ClientTransactionHandler {

    // Schedule phase related logic and handlers.

    /** Prepare and schedule transaction for execution. */
    void scheduleTransaction(ClientTransaction transaction) {
        transaction.preExecute(this);
        sendMessage(ActivityThread.H.EXECUTE_TRANSACTION, transaction);
    }

    /**
     * Execute transaction immediately without scheduling it. This is used for local requests, so
     * it will also recycle the transaction.
     */
    @VisibleForTesting
    public void executeTransaction(ClientTransaction transaction) {
        transaction.preExecute(this);
        getTransactionExecutor().execute(transaction);
        transaction.recycle();
    }

    /**
     * Get the {@link TransactionExecutor} that will be performing lifecycle transitions and
     * callbacks for activities.
     */
    abstract TransactionExecutor getTransactionExecutor();

    abstract void sendMessage(int what, Object obj);

    /** Get activity instance for the token. */
    public abstract Activity getActivity(IBinder token);

    // Prepare phase related logic and handlers. Methods that inform about about pending changes or
    // do other internal bookkeeping.

    /** Set pending config in case it will be updated by other transaction item. */
    public abstract void updatePendingConfiguration(Configuration config);

    /** Set current process state. */
    public abstract void updateProcessState(int processState, boolean fromIpc);

    // Execute phase related logic and handlers. Methods here execute actual lifecycle transactions
    // and deliver callbacks.

    /** Get activity and its corresponding transaction item which are going to destroy. */
    public abstract Map<IBinder, ClientTransactionItem> getActivitiesToBeDestroyed();

    /** Destroy the activity. */
    public abstract void handleDestroyActivity(@NonNull ActivityClientRecord r, boolean finishing,
            int configChanges, boolean getNonConfigInstance, String reason);

    /** Pause the activity. */
    public abstract void handlePauseActivity(@NonNull ActivityClientRecord r, boolean finished,
            boolean userLeaving, int configChanges, PendingTransactionActions pendingActions,
            String reason);

    /**
     * Resume the activity.
     * @param r Target activity record.
     * @param finalStateRequest Flag indicating if this call is handling final lifecycle state
     *                          request for a transaction.
     * @param isForward Flag indicating if next transition is forward.
     * @param reason Reason for performing this operation.
     */
    public abstract void handleResumeActivity(@NonNull ActivityClientRecord r,
            boolean finalStateRequest, boolean isForward, String reason);

    /**
     * Notify the activity about top resumed state change.
     * @param r Target activity record.
     * @param isTopResumedActivity Current state of the activity, {@code true} if it's the
     *                             topmost resumed activity in the system, {@code false} otherwise.
     * @param reason Reason for performing this operation.
     */
    public abstract void handleTopResumedActivityChanged(@NonNull ActivityClientRecord r,
            boolean isTopResumedActivity, String reason);

    /**
     * Stop the activity.
     * @param r Target activity record.
     * @param configChanges Activity configuration changes.
     * @param pendingActions Pending actions to be used on this or later stages of activity
     *                       transaction.
     * @param finalStateRequest Flag indicating if this call is handling final lifecycle state
     *                          request for a transaction.
     * @param reason Reason for performing this operation.
     */
    public abstract void handleStopActivity(@NonNull ActivityClientRecord r, int configChanges,
            PendingTransactionActions pendingActions, boolean finalStateRequest, String reason);

    /** Report that activity was stopped to server. */
    public abstract void reportStop(PendingTransactionActions pendingActions);

    /** Restart the activity after it was stopped. */
    public abstract void performRestartActivity(@NonNull ActivityClientRecord r, boolean start);

    /** Set pending activity configuration in case it will be updated by other transaction item. */
    public abstract void updatePendingActivityConfiguration(@NonNull ActivityClientRecord r,
            Configuration overrideConfig);

    /** Deliver activity (override) configuration change. */
    public abstract void handleActivityConfigurationChanged(@NonNull ActivityClientRecord r,
            Configuration overrideConfig, int displayId);

    /** Deliver result from another activity. */
    public abstract void handleSendResult(
            @NonNull ActivityClientRecord r, List<ResultInfo> results, String reason);

    /** Deliver new intent. */
    public abstract void handleNewIntent(
            @NonNull ActivityClientRecord r, List<ReferrerIntent> intents);

    /** Request that an activity enter picture-in-picture. */
    public abstract void handlePictureInPictureRequested(@NonNull ActivityClientRecord r);

    /** Signal to an activity (that is currently in PiP) of PiP state changes. */
    public abstract void handlePictureInPictureStateChanged(@NonNull ActivityClientRecord r,
            PictureInPictureUiState pipState);

    /** Whether the activity want to handle splash screen exit animation */
    public abstract boolean isHandleSplashScreenExit(@NonNull IBinder token);

    /** Attach a splash screen window view to the top of the activity */
    public abstract void handleAttachSplashScreenView(@NonNull ActivityClientRecord r,
            @NonNull SplashScreenViewParcelable parcelable);

    /** Hand over the splash screen window view to the activity */
    public abstract void handOverSplashScreenView(@NonNull ActivityClientRecord r);

    /** Perform activity launch. */
    public abstract Activity handleLaunchActivity(@NonNull ActivityClientRecord r,
            PendingTransactionActions pendingActions, Intent customIntent);

    /** Perform activity start. */
    public abstract void handleStartActivity(@NonNull ActivityClientRecord r,
            PendingTransactionActions pendingActions, ActivityOptions activityOptions);

    /** Get package info. */
    public abstract LoadedApk getPackageInfoNoCheck(ApplicationInfo ai,
            CompatibilityInfo compatInfo);

    /** Deliver app configuration change notification. */
    public abstract void handleConfigurationChanged(Configuration config);

    /** Apply addition adjustments to override display information. */
    public abstract void handleFixedRotationAdjustments(IBinder token,
            FixedRotationAdjustments fixedRotationAdjustments);

    /**
     * Add {@link ActivityClientRecord} that is preparing to be launched.
     * @param token Activity token.
     * @param activity An initialized instance of {@link ActivityClientRecord} to use during launch.
     */
    public abstract void addLaunchingActivity(IBinder token, ActivityClientRecord activity);

    /**
     * Get {@link ActivityClientRecord} that is preparing to be launched.
     * @param token Activity token.
     * @return An initialized instance of {@link ActivityClientRecord} to use during launch.
     */
    public abstract ActivityClientRecord getLaunchingActivity(IBinder token);

    /**
     * Remove {@link ActivityClientRecord} from the launching activity list.
     * @param token Activity token.
     */
    public abstract void removeLaunchingActivity(IBinder token);

    /**
     * Get {@link android.app.ActivityThread.ActivityClientRecord} instance that corresponds to the
     * provided token.
     */
    public abstract ActivityClientRecord getActivityClient(IBinder token);

    /**
     * Prepare activity relaunch to update internal bookkeeping. This is used to track multiple
     * relaunch and config update requests.
     * @param token Activity token.
     * @param pendingResults Activity results to be delivered.
     * @param pendingNewIntents New intent messages to be delivered.
     * @param configChanges Mask of configuration changes that have occurred.
     * @param config New configuration applied to the activity.
     * @param preserveWindow Whether the activity should try to reuse the window it created,
     *                        including the decor view after the relaunch.
     * @return An initialized instance of {@link ActivityThread.ActivityClientRecord} to use during
     *         relaunch, or {@code null} if relaunch cancelled.
     */
    public abstract ActivityClientRecord prepareRelaunchActivity(IBinder token,
            List<ResultInfo> pendingResults, List<ReferrerIntent> pendingNewIntents,
            int configChanges, MergedConfiguration config, boolean preserveWindow);

    /**
     * Perform activity relaunch.
     * @param r Activity client record prepared for relaunch.
     * @param pendingActions Pending actions to be used on later stages of activity transaction.
     * */
    public abstract void handleRelaunchActivity(@NonNull ActivityClientRecord r,
            PendingTransactionActions pendingActions);

    /**
     * Report that relaunch request was handled.
     * @param r Target activity record.
     * @param pendingActions Pending actions initialized on earlier stages of activity transaction.
     *                       Used to check if we should report relaunch to WM.
     * */
    public abstract void reportRelaunch(@NonNull ActivityClientRecord r,
            PendingTransactionActions pendingActions);
}
