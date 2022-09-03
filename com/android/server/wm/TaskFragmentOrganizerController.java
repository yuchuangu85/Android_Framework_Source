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

package com.android.server.wm;

import static android.window.TaskFragmentOrganizer.putExceptionInBundle;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WINDOW_ORGANIZER;
import static com.android.server.wm.WindowOrganizerController.configurationsAreEqualForOrganizer;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.RemoteAnimationDefinition;
import android.window.ITaskFragmentOrganizer;
import android.window.ITaskFragmentOrganizerController;
import android.window.TaskFragmentInfo;

import com.android.internal.protolog.common.ProtoLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Stores and manages the client {@link android.window.TaskFragmentOrganizer}.
 */
public class TaskFragmentOrganizerController extends ITaskFragmentOrganizerController.Stub {
    private static final String TAG = "TaskFragmentOrganizerController";

    private final ActivityTaskManagerService mAtmService;
    private final WindowManagerGlobalLock mGlobalLock;
    /**
     * A Map which manages the relationship between
     * {@link ITaskFragmentOrganizer} and {@link TaskFragmentOrganizerState}
     */
    private final ArrayMap<IBinder, TaskFragmentOrganizerState> mTaskFragmentOrganizerState =
            new ArrayMap<>();
    /**
     * A List which manages the TaskFragment pending event {@link PendingTaskFragmentEvent}
     */
    private final ArrayList<PendingTaskFragmentEvent> mPendingTaskFragmentEvents =
            new ArrayList<>();

    TaskFragmentOrganizerController(ActivityTaskManagerService atm) {
        mAtmService = atm;
        mGlobalLock = atm.mGlobalLock;
    }

    /**
     * A class to manage {@link ITaskFragmentOrganizer} and its organized
     * {@link TaskFragment TaskFragments}.
     */
    private class TaskFragmentOrganizerState implements IBinder.DeathRecipient {
        private final ArrayList<TaskFragment> mOrganizedTaskFragments = new ArrayList<>();
        private final ITaskFragmentOrganizer mOrganizer;
        private final Map<TaskFragment, TaskFragmentInfo> mLastSentTaskFragmentInfos =
                new WeakHashMap<>();
        private final Map<TaskFragment, Configuration> mLastSentTaskFragmentParentConfigs =
                new WeakHashMap<>();

        /**
         * @see android.window.TaskFragmentOrganizer#registerRemoteAnimations(
         * RemoteAnimationDefinition)
         */
        @Nullable
        private RemoteAnimationDefinition mRemoteAnimationDefinition;

        TaskFragmentOrganizerState(ITaskFragmentOrganizer organizer) {
            mOrganizer = organizer;
            try {
                mOrganizer.asBinder().linkToDeath(this, 0 /*flags*/);
            } catch (RemoteException e) {
                Slog.e(TAG, "TaskFragmentOrganizer failed to register death recipient");
            }
        }

        @Override
        public void binderDied() {
            synchronized (mGlobalLock) {
                removeOrganizer(mOrganizer);
            }
        }

        /**
         * @return {@code true} if taskFragment is organized and not sent the appeared event before.
         */
        boolean addTaskFragment(TaskFragment taskFragment) {
            if (taskFragment.mTaskFragmentAppearedSent) {
                return false;
            }
            if (mOrganizedTaskFragments.contains(taskFragment)) {
                return false;
            }
            mOrganizedTaskFragments.add(taskFragment);
            return true;
        }

        void removeTaskFragment(TaskFragment taskFragment) {
            mOrganizedTaskFragments.remove(taskFragment);
        }

        void dispose() {
            while (!mOrganizedTaskFragments.isEmpty()) {
                final TaskFragment taskFragment = mOrganizedTaskFragments.get(0);
                taskFragment.removeImmediately();
                mOrganizedTaskFragments.remove(taskFragment);
            }
            mOrganizer.asBinder().unlinkToDeath(this, 0 /*flags*/);
        }

        void onTaskFragmentAppeared(ITaskFragmentOrganizer organizer, TaskFragment tf) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "TaskFragment appeared name=%s", tf.getName());
            final TaskFragmentInfo info = tf.getTaskFragmentInfo();
            try {
                organizer.onTaskFragmentAppeared(info);
                mLastSentTaskFragmentInfos.put(tf, info);
                tf.mTaskFragmentAppearedSent = true;
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception sending onTaskFragmentAppeared callback", e);
            }
            onTaskFragmentParentInfoChanged(organizer, tf);
        }

        void onTaskFragmentVanished(ITaskFragmentOrganizer organizer, TaskFragment tf) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "TaskFragment vanished name=%s", tf.getName());
            try {
                organizer.onTaskFragmentVanished(tf.getTaskFragmentInfo());
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception sending onTaskFragmentVanished callback", e);
            }
            tf.mTaskFragmentAppearedSent = false;
            mLastSentTaskFragmentInfos.remove(tf);
            mLastSentTaskFragmentParentConfigs.remove(tf);
        }

        void onTaskFragmentInfoChanged(ITaskFragmentOrganizer organizer, TaskFragment tf) {
            // Parent config may have changed. The controller will check if there is any important
            // config change for the organizer.
            onTaskFragmentParentInfoChanged(organizer, tf);

            // Check if the info is different from the last reported info.
            final TaskFragmentInfo info = tf.getTaskFragmentInfo();
            final TaskFragmentInfo lastInfo = mLastSentTaskFragmentInfos.get(tf);
            if (info.equalsForTaskFragmentOrganizer(lastInfo) && configurationsAreEqualForOrganizer(
                    info.getConfiguration(), lastInfo.getConfiguration())) {
                return;
            }
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "TaskFragment info changed name=%s",
                    tf.getName());
            try {
                organizer.onTaskFragmentInfoChanged(tf.getTaskFragmentInfo());
                mLastSentTaskFragmentInfos.put(tf, info);
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception sending onTaskFragmentInfoChanged callback", e);
            }
        }

        void onTaskFragmentParentInfoChanged(ITaskFragmentOrganizer organizer, TaskFragment tf) {
            // Check if the parent info is different from the last reported parent info.
            if (tf.getParent() == null || tf.getParent().asTask() == null) {
                mLastSentTaskFragmentParentConfigs.remove(tf);
                return;
            }
            final Task parent = tf.getParent().asTask();
            final Configuration parentConfig = parent.getConfiguration();
            final Configuration lastParentConfig = mLastSentTaskFragmentParentConfigs.get(tf);
            if (configurationsAreEqualForOrganizer(parentConfig, lastParentConfig)) {
                return;
            }
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "TaskFragment parent info changed name=%s parentTaskId=%d",
                    tf.getName(), parent.mTaskId);
            try {
                organizer.onTaskFragmentParentInfoChanged(tf.getFragmentToken(), parentConfig);
                mLastSentTaskFragmentParentConfigs.put(tf, new Configuration(parentConfig));
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception sending onTaskFragmentParentInfoChanged callback", e);
            }
        }

        void onTaskFragmentError(ITaskFragmentOrganizer organizer, IBinder errorCallbackToken,
                Throwable exception) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "Sending TaskFragment error exception=%s", exception.toString());
            final Bundle exceptionBundle = putExceptionInBundle(exception);
            try {
                organizer.onTaskFragmentError(errorCallbackToken, exceptionBundle);
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception sending onTaskFragmentError callback", e);
            }
        }
    }

    @Override
    public void registerOrganizer(ITaskFragmentOrganizer organizer) {
        final int pid = Binder.getCallingPid();
        final long uid = Binder.getCallingUid();
        synchronized (mGlobalLock) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "Register task fragment organizer=%s uid=%d pid=%d",
                    organizer.asBinder(), uid, pid);
            if (mTaskFragmentOrganizerState.containsKey(organizer.asBinder())) {
                throw new IllegalStateException(
                        "Replacing existing organizer currently unsupported");
            }
            mTaskFragmentOrganizerState.put(organizer.asBinder(),
                    new TaskFragmentOrganizerState(organizer));
        }
    }

    @Override
    public void unregisterOrganizer(ITaskFragmentOrganizer organizer) {
        validateAndGetState(organizer);
        final int pid = Binder.getCallingPid();
        final long uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                        "Unregister task fragment organizer=%s uid=%d pid=%d",
                        organizer.asBinder(), uid, pid);
                removeOrganizer(organizer);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void registerRemoteAnimations(ITaskFragmentOrganizer organizer,
            RemoteAnimationDefinition definition) {
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        synchronized (mGlobalLock) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "Register remote animations for organizer=%s uid=%d pid=%d",
                    organizer.asBinder(), uid, pid);
            final TaskFragmentOrganizerState organizerState =
                    mTaskFragmentOrganizerState.get(organizer.asBinder());
            if (organizerState == null) {
                throw new IllegalStateException("The organizer hasn't been registered.");
            }
            if (organizerState.mRemoteAnimationDefinition != null) {
                throw new IllegalStateException(
                        "The organizer has already registered remote animations="
                                + organizerState.mRemoteAnimationDefinition);
            }

            definition.setCallingPidUid(pid, uid);
            organizerState.mRemoteAnimationDefinition = definition;
        }
    }

    @Override
    public void unregisterRemoteAnimations(ITaskFragmentOrganizer organizer) {
        final int pid = Binder.getCallingPid();
        final long uid = Binder.getCallingUid();
        synchronized (mGlobalLock) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "Unregister remote animations for organizer=%s uid=%d pid=%d",
                    organizer.asBinder(), uid, pid);
            final TaskFragmentOrganizerState organizerState =
                    mTaskFragmentOrganizerState.get(organizer.asBinder());
            if (organizerState == null) {
                Slog.e(TAG, "The organizer hasn't been registered.");
                return;
            }

            organizerState.mRemoteAnimationDefinition = null;
        }
    }

    /** Gets the {@link RemoteAnimationDefinition} set on the given organizer if exists. */
    @Nullable
    public RemoteAnimationDefinition getRemoteAnimationDefinition(
            ITaskFragmentOrganizer organizer) {
        synchronized (mGlobalLock) {
            final TaskFragmentOrganizerState organizerState =
                    mTaskFragmentOrganizerState.get(organizer.asBinder());
            return organizerState != null ? organizerState.mRemoteAnimationDefinition : null;
        }
    }

    void onTaskFragmentAppeared(ITaskFragmentOrganizer organizer, TaskFragment taskFragment) {
        final TaskFragmentOrganizerState state = validateAndGetState(organizer);
        if (!state.addTaskFragment(taskFragment)) {
            return;
        }
        PendingTaskFragmentEvent pendingEvent = getPendingTaskFragmentEvent(taskFragment,
                PendingTaskFragmentEvent.EVENT_APPEARED);
        if (pendingEvent == null) {
            pendingEvent = new PendingTaskFragmentEvent(taskFragment, organizer,
                    PendingTaskFragmentEvent.EVENT_APPEARED);
            mPendingTaskFragmentEvents.add(pendingEvent);
        }
    }

    void onTaskFragmentInfoChanged(ITaskFragmentOrganizer organizer, TaskFragment taskFragment) {
        handleTaskFragmentInfoChanged(organizer, taskFragment,
                PendingTaskFragmentEvent.EVENT_INFO_CHANGED);
    }

    void onTaskFragmentParentInfoChanged(ITaskFragmentOrganizer organizer,
            TaskFragment taskFragment) {
        handleTaskFragmentInfoChanged(organizer, taskFragment,
                PendingTaskFragmentEvent.EVENT_PARENT_INFO_CHANGED);
    }

    private void handleTaskFragmentInfoChanged(ITaskFragmentOrganizer organizer,
            TaskFragment taskFragment, int eventType) {
        validateAndGetState(organizer);
        if (!taskFragment.mTaskFragmentAppearedSent) {
            // Skip if TaskFragment still not appeared.
            return;
        }
        PendingTaskFragmentEvent pendingEvent = getLastPendingLifecycleEvent(taskFragment);
        if (pendingEvent == null) {
            pendingEvent = new PendingTaskFragmentEvent(taskFragment, organizer, eventType);
        } else {
            if (pendingEvent.mEventType == PendingTaskFragmentEvent.EVENT_VANISHED) {
                // Skipped the info changed event if vanished event is pending.
                return;
            }
            // Remove and add for re-ordering.
            mPendingTaskFragmentEvents.remove(pendingEvent);
        }
        mPendingTaskFragmentEvents.add(pendingEvent);
    }

    void onTaskFragmentVanished(ITaskFragmentOrganizer organizer, TaskFragment taskFragment) {
        final TaskFragmentOrganizerState state = validateAndGetState(organizer);
        for (int i = mPendingTaskFragmentEvents.size() - 1; i >= 0; i--) {
            PendingTaskFragmentEvent entry = mPendingTaskFragmentEvents.get(i);
            if (taskFragment == entry.mTaskFragment) {
                mPendingTaskFragmentEvents.remove(i);
                if (entry.mEventType == PendingTaskFragmentEvent.EVENT_APPEARED) {
                    // If taskFragment appeared callback is pending, ignore the vanished request.
                    return;
                }
            }
        }
        if (!taskFragment.mTaskFragmentAppearedSent) {
            return;
        }
        PendingTaskFragmentEvent pendingEvent = new PendingTaskFragmentEvent(taskFragment,
                organizer, PendingTaskFragmentEvent.EVENT_VANISHED);
        mPendingTaskFragmentEvents.add(pendingEvent);
        state.removeTaskFragment(taskFragment);
    }

    void onTaskFragmentError(ITaskFragmentOrganizer organizer, IBinder errorCallbackToken,
            Throwable exception) {
        validateAndGetState(organizer);
        Slog.w(TAG, "onTaskFragmentError ", exception);
        PendingTaskFragmentEvent pendingEvent = new PendingTaskFragmentEvent(organizer,
                errorCallbackToken, exception, PendingTaskFragmentEvent.EVENT_ERROR);
        mPendingTaskFragmentEvents.add(pendingEvent);
    }

    private void removeOrganizer(ITaskFragmentOrganizer organizer) {
        final TaskFragmentOrganizerState state = validateAndGetState(organizer);
        // remove all of the children of the organized TaskFragment
        state.dispose();
        mTaskFragmentOrganizerState.remove(organizer.asBinder());
    }

    /**
     * Makes sure that the organizer has been correctly registered to prevent any Sidecar
     * implementation from organizing {@link TaskFragment} without registering first. In such case,
     * we wouldn't register {@link DeathRecipient} for the organizer, and might not remove the
     * {@link TaskFragment} after the organizer process died.
     */
    private TaskFragmentOrganizerState validateAndGetState(ITaskFragmentOrganizer organizer) {
        final TaskFragmentOrganizerState state =
                mTaskFragmentOrganizerState.get(organizer.asBinder());
        if (state == null) {
            throw new IllegalArgumentException(
                    "TaskFragmentOrganizer has not been registered. Organizer=" + organizer);
        }
        return state;
    }

    /**
     * A class to store {@link ITaskFragmentOrganizer} and its organized
     * {@link TaskFragment TaskFragments} with different pending event request.
     */
    private static class PendingTaskFragmentEvent {
        static final int EVENT_APPEARED = 0;
        static final int EVENT_VANISHED = 1;
        static final int EVENT_INFO_CHANGED = 2;
        static final int EVENT_PARENT_INFO_CHANGED = 3;
        static final int EVENT_ERROR = 4;

        @IntDef(prefix = "EVENT_", value = {
                EVENT_APPEARED,
                EVENT_VANISHED,
                EVENT_INFO_CHANGED,
                EVENT_PARENT_INFO_CHANGED,
                EVENT_ERROR
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface EventType {}

        @EventType
        private final int mEventType;
        private final ITaskFragmentOrganizer mTaskFragmentOrg;
        private final TaskFragment mTaskFragment;
        private final IBinder mErrorCallback;
        private final Throwable mException;
        // Set when the event is deferred due to the host task is invisible. The defer time will
        // be the last active time of the host task.
        private long mDeferTime;

        private PendingTaskFragmentEvent(TaskFragment taskFragment,
                ITaskFragmentOrganizer taskFragmentOrg, @EventType int eventType) {
            this(taskFragment, taskFragmentOrg, null /* errorCallback */,
                    null /* exception */, eventType);

        }

        private PendingTaskFragmentEvent(ITaskFragmentOrganizer taskFragmentOrg,
                IBinder errorCallback, Throwable exception, @EventType int eventType) {
            this(null /* taskFragment */, taskFragmentOrg, errorCallback, exception,
                    eventType);
        }

        private PendingTaskFragmentEvent(TaskFragment taskFragment,
                ITaskFragmentOrganizer taskFragmentOrg, IBinder errorCallback, Throwable exception,
                @EventType int eventType) {
            mTaskFragment = taskFragment;
            mTaskFragmentOrg = taskFragmentOrg;
            mErrorCallback = errorCallback;
            mException = exception;
            mEventType = eventType;
        }

        /**
         * @return {@code true} if the pending event is related with taskFragment created, vanished
         * and information changed.
         */
        boolean isLifecycleEvent() {
            switch (mEventType) {
                case EVENT_APPEARED:
                case EVENT_VANISHED:
                case EVENT_INFO_CHANGED:
                case EVENT_PARENT_INFO_CHANGED:
                    return true;
                default:
                    return false;
            }
        }
    }

    @Nullable
    private PendingTaskFragmentEvent getLastPendingLifecycleEvent(TaskFragment tf) {
        for (int i = mPendingTaskFragmentEvents.size() - 1; i >= 0; i--) {
            PendingTaskFragmentEvent entry = mPendingTaskFragmentEvents.get(i);
            if (tf == entry.mTaskFragment && entry.isLifecycleEvent()) {
                return entry;
            }
        }
        return null;
    }

    @Nullable
    private PendingTaskFragmentEvent getPendingTaskFragmentEvent(TaskFragment taskFragment,
            int type) {
        for (int i = mPendingTaskFragmentEvents.size() - 1; i >= 0; i--) {
            PendingTaskFragmentEvent entry = mPendingTaskFragmentEvents.get(i);
            if (taskFragment == entry.mTaskFragment && type == entry.mEventType) {
                return entry;
            }
        }
        return null;
    }

    private boolean shouldSendEventWhenTaskInvisible(@NonNull Task task,
            @NonNull PendingTaskFragmentEvent event) {
        final TaskFragmentOrganizerState state =
                mTaskFragmentOrganizerState.get(event.mTaskFragmentOrg.asBinder());
        final TaskFragmentInfo lastInfo = state.mLastSentTaskFragmentInfos.get(event.mTaskFragment);
        final TaskFragmentInfo info = event.mTaskFragment.getTaskFragmentInfo();
        // Send an info changed callback if this event is for the last activities to finish in a
        // Task so that the {@link TaskFragmentOrganizer} can delete this TaskFragment. Otherwise,
        // the Task may be removed before it becomes visible again to send this event because it no
        // longer has activities. As a result, the organizer will never get this info changed event
        // and will not delete the TaskFragment because the organizer thinks the TaskFragment still
        // has running activities.
        return event.mEventType == PendingTaskFragmentEvent.EVENT_INFO_CHANGED
                && task.topRunningActivity() == null && lastInfo != null
                && lastInfo.getRunningActivityCount() > 0 && info.getRunningActivityCount() == 0;
    }

    void dispatchPendingEvents() {
        if (mAtmService.mWindowManager.mWindowPlacerLocked.isLayoutDeferred()
                || mPendingTaskFragmentEvents.isEmpty()) {
            return;
        }

        final ArrayList<Task> visibleTasks = new ArrayList<>();
        final ArrayList<Task> invisibleTasks = new ArrayList<>();
        final ArrayList<PendingTaskFragmentEvent> candidateEvents = new ArrayList<>();
        for (int i = 0, n = mPendingTaskFragmentEvents.size(); i < n; i++) {
            final PendingTaskFragmentEvent event = mPendingTaskFragmentEvents.get(i);
            final Task task = event.mTaskFragment != null ? event.mTaskFragment.getTask() : null;
            if (task != null && (task.lastActiveTime <= event.mDeferTime
                    || !(isTaskVisible(task, visibleTasks, invisibleTasks)
                    || shouldSendEventWhenTaskInvisible(task, event)))) {
                // Defer sending events to the TaskFragment until the host task is active again.
                event.mDeferTime = task.lastActiveTime;
                continue;
            }
            candidateEvents.add(event);
        }
        final int numEvents = candidateEvents.size();
        for (int i = 0; i < numEvents; i++) {
            dispatchEvent(candidateEvents.get(i));
        }
        if (numEvents > 0) {
            mPendingTaskFragmentEvents.removeAll(candidateEvents);
        }
    }

    private static boolean isTaskVisible(Task task, ArrayList<Task> knownVisibleTasks,
            ArrayList<Task> knownInvisibleTasks) {
        if (knownVisibleTasks.contains(task)) {
            return true;
        }
        if (knownInvisibleTasks.contains(task)) {
            return false;
        }
        if (task.shouldBeVisible(null /* starting */)) {
            knownVisibleTasks.add(task);
            return true;
        } else {
            knownInvisibleTasks.add(task);
            return false;
        }
    }

    void dispatchPendingInfoChangedEvent(TaskFragment taskFragment) {
        PendingTaskFragmentEvent event = getPendingTaskFragmentEvent(taskFragment,
                PendingTaskFragmentEvent.EVENT_INFO_CHANGED);
        if (event == null) {
            return;
        }

        dispatchEvent(event);
        mPendingTaskFragmentEvents.remove(event);
    }

    private void dispatchEvent(PendingTaskFragmentEvent event) {
        final ITaskFragmentOrganizer taskFragmentOrg = event.mTaskFragmentOrg;
        final TaskFragment taskFragment = event.mTaskFragment;
        final TaskFragmentOrganizerState state =
                mTaskFragmentOrganizerState.get(taskFragmentOrg.asBinder());
        if (state == null) {
            return;
        }
        switch (event.mEventType) {
            case PendingTaskFragmentEvent.EVENT_APPEARED:
                state.onTaskFragmentAppeared(taskFragmentOrg, taskFragment);
                break;
            case PendingTaskFragmentEvent.EVENT_VANISHED:
                state.onTaskFragmentVanished(taskFragmentOrg, taskFragment);
                break;
            case PendingTaskFragmentEvent.EVENT_INFO_CHANGED:
                state.onTaskFragmentInfoChanged(taskFragmentOrg, taskFragment);
                break;
            case PendingTaskFragmentEvent.EVENT_PARENT_INFO_CHANGED:
                state.onTaskFragmentParentInfoChanged(taskFragmentOrg, taskFragment);
                break;
            case PendingTaskFragmentEvent.EVENT_ERROR:
                state.onTaskFragmentError(taskFragmentOrg, event.mErrorCallback,
                        event.mException);
        }
    }

    // TODO(b/204399167): change to push the embedded state to the client side
    @Override
    public boolean isActivityEmbedded(IBinder activityToken) {
        synchronized (mGlobalLock) {
            final ActivityRecord activity = ActivityRecord.forTokenLocked(activityToken);
            if (activity == null) {
                return false;
            }
            final TaskFragment taskFragment = activity.getOrganizedTaskFragment();
            if (taskFragment == null) {
                return false;
            }
            final Task parentTask = taskFragment.getTask();
            if (parentTask != null) {
                final Rect taskBounds = parentTask.getBounds();
                final Rect taskFragBounds = taskFragment.getBounds();
                return !taskBounds.equals(taskFragBounds) && taskBounds.contains(taskFragBounds);
            }
            return false;
        }
    }
}
