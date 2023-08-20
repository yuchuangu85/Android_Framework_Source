/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.server.wm.SnapshotController.ACTIVITY_CLOSE;
import static com.android.server.wm.SnapshotController.ACTIVITY_OPEN;
import static com.android.server.wm.SnapshotController.TASK_CLOSE;
import static com.android.server.wm.SnapshotController.TASK_OPEN;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.os.Environment;
import android.os.SystemProperties;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.window.TaskSnapshot;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.BaseAppSnapshotPersister.PersistInfoProvider;
import com.android.server.wm.SnapshotController.TransitionState;

import java.io.File;
import java.util.ArrayList;

/**
 * When an app token becomes invisible, we take a snapshot (bitmap) and put it into our cache.
 * Internally we use gralloc buffers to be able to draw them wherever we like without any copying.
 * <p>
 * System applications may retrieve a snapshot to represent the current state of an activity, and
 * draw them in their own process.
 * <p>
 * Unlike TaskSnapshotController, we only keep one activity snapshot for a visible task in the
 * cache. Which should largely reduce the memory usage.
 * <p>
 * To access this class, acquire the global window manager lock.
 */
class ActivitySnapshotController extends AbsAppSnapshotController<ActivityRecord,
        ActivitySnapshotCache> {
    private static final boolean DEBUG = false;
    private static final String TAG = AbsAppSnapshotController.TAG;
    // Maximum persisted snapshot count on disk.
    private static final int MAX_PERSIST_SNAPSHOT_COUNT = 20;

    static final String SNAPSHOTS_DIRNAME = "activity_snapshots";

    /**
     * The pending activities which should capture snapshot when process transition finish.
     */
    @VisibleForTesting
    final ArraySet<ActivityRecord> mPendingCaptureActivity = new ArraySet<>();

    /**
     * The pending activities which should remove snapshot from memory when process transition
     * finish.
     */
    @VisibleForTesting
    final ArraySet<ActivityRecord> mPendingRemoveActivity = new ArraySet<>();

    /**
     * The pending activities which should delete snapshot files when process transition finish.
     */
    @VisibleForTesting
    final ArraySet<ActivityRecord> mPendingDeleteActivity = new ArraySet<>();

    /**
     * The pending activities which should load snapshot from disk when process transition finish.
     */
    @VisibleForTesting
    final ArraySet<ActivityRecord> mPendingLoadActivity = new ArraySet<>();

    private final SnapshotPersistQueue mSnapshotPersistQueue;
    private final PersistInfoProvider mPersistInfoProvider;
    private final AppSnapshotLoader mSnapshotLoader;

    /**
     * File information holders, to make the sequence align, always update status of
     * mUserSavedFiles/mSavedFilesInOrder before persist file from mPersister.
     */
    private final SparseArray<SparseArray<UserSavedFile>> mUserSavedFiles = new SparseArray<>();
    // Keep sorted with create timeline.
    private final ArrayList<UserSavedFile> mSavedFilesInOrder = new ArrayList<>();
    private final TaskSnapshotPersister mPersister;

    ActivitySnapshotController(WindowManagerService service, SnapshotPersistQueue persistQueue) {
        super(service);
        mSnapshotPersistQueue = persistQueue;
        mPersistInfoProvider = createPersistInfoProvider(service,
                Environment::getDataSystemCeDirectory);
        mPersister = new TaskSnapshotPersister(persistQueue, mPersistInfoProvider);
        mSnapshotLoader = new AppSnapshotLoader(mPersistInfoProvider);
        initialize(new ActivitySnapshotCache(service));

        final boolean snapshotEnabled =
                !service.mContext
                        .getResources()
                        .getBoolean(com.android.internal.R.bool.config_disableTaskSnapshots)
                && isSnapshotEnabled()
                && !ActivityManager.isLowRamDeviceStatic(); // Don't support Android Go
        setSnapshotEnabled(snapshotEnabled);
    }

    void systemReady() {
        if (shouldDisableSnapshots()) {
            return;
        }
        mService.mSnapshotController.registerTransitionStateConsumer(
                ACTIVITY_OPEN, this::handleOpenActivityTransition);
        mService.mSnapshotController.registerTransitionStateConsumer(
                ACTIVITY_CLOSE, this::handleCloseActivityTransition);
        mService.mSnapshotController.registerTransitionStateConsumer(
                TASK_OPEN, this::handleOpenTaskTransition);
        mService.mSnapshotController.registerTransitionStateConsumer(
                TASK_CLOSE, this::handleCloseTaskTransition);
    }

    @Override
    protected float initSnapshotScale() {
        final float config = mService.mContext.getResources().getFloat(
                com.android.internal.R.dimen.config_resActivitySnapshotScale);
        return Math.max(Math.min(config, 1f), 0.1f);
    }

    // TODO remove when enabled
    static boolean isSnapshotEnabled() {
        return SystemProperties.getInt("persist.wm.debug.activity_screenshot", 0) != 0;
    }

    static PersistInfoProvider createPersistInfoProvider(
            WindowManagerService service, BaseAppSnapshotPersister.DirectoryResolver resolver) {
        // Don't persist reduced file, instead we only persist the "HighRes" bitmap which has
        // already scaled with #initSnapshotScale
        final boolean use16BitFormat = service.mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_use16BitTaskSnapshotPixelFormat);
        return new PersistInfoProvider(resolver, SNAPSHOTS_DIRNAME,
                false /* enableLowResSnapshots */, 0 /* lowResScaleFactor */, use16BitFormat);
    }

    /** Retrieves a snapshot for an activity from cache. */
    @Nullable
    TaskSnapshot getSnapshot(ActivityRecord ar) {
        final int code = getSystemHashCode(ar);
        return mCache.getSnapshot(code);
    }

    private void cleanUpUserFiles(int userId) {
        synchronized (mSnapshotPersistQueue.getLock()) {
            mSnapshotPersistQueue.sendToQueueLocked(
                    new SnapshotPersistQueue.WriteQueueItem(mPersistInfoProvider) {
                        @Override
                        boolean isReady() {
                            final UserManagerInternal mUserManagerInternal =
                                    LocalServices.getService(UserManagerInternal.class);
                            return mUserManagerInternal.isUserUnlocked(userId);
                        }

                        @Override
                        void write() {
                            final File file = mPersistInfoProvider.getDirectory(userId);
                            if (file.exists()) {
                                final File[] contents = file.listFiles();
                                if (contents != null) {
                                    for (int i = contents.length - 1; i >= 0; i--) {
                                        contents[i].delete();
                                    }
                                }
                            }
                        }
                    });
        }
    }

    /**
     * Prepare to handle on transition start. Clear all temporary fields.
     */
    void preTransitionStart() {
        if (shouldDisableSnapshots()) {
            return;
        }
        resetTmpFields();
    }

    /**
     * on transition start has notified, start process data.
     */
    void postTransitionStart() {
        if (shouldDisableSnapshots()) {
            return;
        }
        onCommitTransition();
    }

    @VisibleForTesting
    void resetTmpFields() {
        mPendingCaptureActivity.clear();
        mPendingRemoveActivity.clear();
        mPendingDeleteActivity.clear();
        mPendingLoadActivity.clear();
    }

    /**
     * Start process all pending activities for a transition.
     */
    private void onCommitTransition() {
        if (DEBUG) {
            Slog.d(TAG, "ActivitySnapshotController#onCommitTransition result:"
                    + " capture " + mPendingCaptureActivity
                    + " remove " + mPendingRemoveActivity
                    + " delete " + mPendingDeleteActivity
                    + " load " + mPendingLoadActivity);
        }
        // task snapshots
        for (int i = mPendingCaptureActivity.size() - 1; i >= 0; i--) {
            recordSnapshot(mPendingCaptureActivity.valueAt(i));
        }
        // clear mTmpRemoveActivity from cache
        for (int i = mPendingRemoveActivity.size() - 1; i >= 0; i--) {
            final ActivityRecord ar = mPendingRemoveActivity.valueAt(i);
            final int code = getSystemHashCode(ar);
            mCache.onIdRemoved(code);
        }
        // clear snapshot on cache and delete files
        for (int i = mPendingDeleteActivity.size() - 1; i >= 0; i--) {
            final ActivityRecord ar = mPendingDeleteActivity.valueAt(i);
            final int code = getSystemHashCode(ar);
            mCache.onIdRemoved(code);
            removeIfUserSavedFileExist(code, ar.mUserId);
        }
        // load snapshot to cache
        for (int i = mPendingLoadActivity.size() - 1; i >= 0; i--) {
            final ActivityRecord ar = mPendingLoadActivity.valueAt(i);
            final int code = getSystemHashCode(ar);
            final int userId = ar.mUserId;
            if (mCache.getSnapshot(code) != null) {
                // already in cache, skip
                continue;
            }
            if (containsFile(code, userId)) {
                synchronized (mSnapshotPersistQueue.getLock()) {
                    mSnapshotPersistQueue.sendToQueueLocked(
                            new SnapshotPersistQueue.WriteQueueItem(mPersistInfoProvider) {
                                @Override
                                void write() {
                                    final TaskSnapshot snapshot = mSnapshotLoader.loadTask(code,
                                            userId, false /* loadLowResolutionBitmap */);
                                    synchronized (mService.getWindowManagerLock()) {
                                        if (snapshot != null && !ar.finishing) {
                                            mCache.putSnapshot(ar, snapshot);
                                        }
                                    }
                                }
                            });
                }
            }
        }
        // don't keep any reference
        resetTmpFields();
    }

    private void recordSnapshot(ActivityRecord activity) {
        final TaskSnapshot snapshot = recordSnapshotInner(activity, false /* allowSnapshotHome */);
        if (snapshot != null) {
            final int code = getSystemHashCode(activity);
            addUserSavedFile(code, activity.mUserId, snapshot);
        }
    }

    /**
     * Called when the visibility of an app changes outside the regular app transition flow.
     */
    void notifyAppVisibilityChanged(ActivityRecord appWindowToken, boolean visible) {
        if (shouldDisableSnapshots()) {
            return;
        }
        if (!visible) {
            resetTmpFields();
            addBelowTopActivityIfExist(appWindowToken.getTask(), mPendingRemoveActivity,
                    "remove-snapshot");
            onCommitTransition();
        }
    }

    private static int getSystemHashCode(ActivityRecord activity) {
        return System.identityHashCode(activity);
    }

    void handleOpenActivityTransition(TransitionState<ActivityRecord> transitionState) {
        ArraySet<ActivityRecord> participant = transitionState.getParticipant(false /* open */);
        for (ActivityRecord ar : participant) {
            mPendingCaptureActivity.add(ar);
            // remove the snapshot for the one below close
            final ActivityRecord below = ar.getTask().getActivityBelow(ar);
            if (below != null) {
                mPendingRemoveActivity.add(below);
            }
        }
    }

    void handleCloseActivityTransition(TransitionState<ActivityRecord> transitionState) {
        ArraySet<ActivityRecord> participant = transitionState.getParticipant(true /* open */);
        for (ActivityRecord ar : participant) {
            mPendingDeleteActivity.add(ar);
            // load next one if exists.
            final ActivityRecord below = ar.getTask().getActivityBelow(ar);
            if (below != null) {
                mPendingLoadActivity.add(below);
            }
        }
    }

    void handleCloseTaskTransition(TransitionState<Task> closeTaskTransitionRecord) {
        ArraySet<Task> participant = closeTaskTransitionRecord.getParticipant(false /* open */);
        for (Task close : participant) {
            // this is close task transition
            // remove the N - 1 from cache
            addBelowTopActivityIfExist(close, mPendingRemoveActivity, "remove-snapshot");
        }
    }

    void handleOpenTaskTransition(TransitionState<Task> openTaskTransitionRecord) {
        ArraySet<Task> participant = openTaskTransitionRecord.getParticipant(true /* open */);
        for (Task open : participant) {
            // this is close task transition
            // remove the N - 1 from cache
            addBelowTopActivityIfExist(open, mPendingLoadActivity, "load-snapshot");
            // Move the activities to top of mSavedFilesInOrder, so when purge happen, there
            // will trim the persisted files from the most non-accessed.
            adjustSavedFileOrder(open);
        }
    }

    // Add the top -1 activity to a set if it exists.
    private void addBelowTopActivityIfExist(Task task, ArraySet<ActivityRecord> set,
            String debugMessage) {
        final ActivityRecord topActivity = task.getTopMostActivity();
        if (topActivity != null) {
            final ActivityRecord below = task.getActivityBelow(topActivity);
            if (below != null) {
                set.add(below);
                if (DEBUG) {
                    Slog.d(TAG, "ActivitySnapshotController#addBelowTopActivityIfExist "
                            + below + " from " + debugMessage);
                }
            }
        }
    }

    private void adjustSavedFileOrder(Task nextTopTask) {
        final int userId = nextTopTask.mUserId;
        nextTopTask.forAllActivities(ar -> {
            final int code = getSystemHashCode(ar);
            final UserSavedFile usf = getUserFiles(userId).get(code);
            if (usf != null) {
                mSavedFilesInOrder.remove(usf);
                mSavedFilesInOrder.add(usf);
            }
        }, false /* traverseTopToBottom */);
    }

    @Override
    void onAppRemoved(ActivityRecord activity) {
        super.onAppRemoved(activity);
        final int code = getSystemHashCode(activity);
        removeIfUserSavedFileExist(code, activity.mUserId);
        if (DEBUG) {
            Slog.d(TAG, "ActivitySnapshotController#onAppRemoved delete snapshot " + activity);
        }
    }

    @Override
    void onAppDied(ActivityRecord activity) {
        super.onAppDied(activity);
        final int code = getSystemHashCode(activity);
        removeIfUserSavedFileExist(code, activity.mUserId);
        if (DEBUG) {
            Slog.d(TAG, "ActivitySnapshotController#onAppDied delete snapshot " + activity);
        }
    }

    @Override
    ActivityRecord getTopActivity(ActivityRecord activity) {
        return activity;
    }

    @Override
    ActivityRecord getTopFullscreenActivity(ActivityRecord activity) {
        final WindowState win = activity.findMainWindow();
        return (win != null && win.mAttrs.isFullscreen()) ? activity : null;
    }

    @Override
    ActivityManager.TaskDescription getTaskDescription(ActivityRecord object) {
        return object.taskDescription;
    }

    /**
     * Find the window for a given activity to take a snapshot. During app transitions, trampoline
     * activities can appear in the children, but should be ignored.
     */
    @Override
    protected ActivityRecord findAppTokenForSnapshot(ActivityRecord activity) {
        if (activity == null) {
            return null;
        }
        return activity.canCaptureSnapshot() ? activity : null;
    }

    @Override
    protected boolean use16BitFormat() {
        return mPersistInfoProvider.use16BitFormat();
    }

    @NonNull
    private SparseArray<UserSavedFile> getUserFiles(int userId) {
        if (mUserSavedFiles.get(userId) == null) {
            mUserSavedFiles.put(userId, new SparseArray<>());
            // This is the first time this user attempt to access snapshot, clear up the disk.
            cleanUpUserFiles(userId);
        }
        return mUserSavedFiles.get(userId);
    }

    private void removeIfUserSavedFileExist(int code, int userId) {
        final UserSavedFile usf = getUserFiles(userId).get(code);
        if (usf != null) {
            mUserSavedFiles.remove(code);
            mSavedFilesInOrder.remove(usf);
            mPersister.removeSnap(code, userId);
        }
    }

    private boolean containsFile(int code, int userId) {
        return getUserFiles(userId).get(code) != null;
    }

    private void addUserSavedFile(int code, int userId, TaskSnapshot snapshot) {
        final SparseArray<UserSavedFile> savedFiles = getUserFiles(userId);
        final UserSavedFile savedFile = savedFiles.get(code);
        if (savedFile == null) {
            final UserSavedFile usf = new UserSavedFile(code, userId);
            savedFiles.put(code, usf);
            mSavedFilesInOrder.add(usf);
            mPersister.persistSnapshot(code, userId, snapshot);

            if (mSavedFilesInOrder.size() > MAX_PERSIST_SNAPSHOT_COUNT * 2) {
                purgeSavedFile();
            }
        }
    }

    private void purgeSavedFile() {
        final int savedFileCount = mSavedFilesInOrder.size();
        final int removeCount = savedFileCount - MAX_PERSIST_SNAPSHOT_COUNT;
        final ArrayList<UserSavedFile> usfs = new ArrayList<>();
        if (removeCount > 0) {
            final int removeTillIndex = savedFileCount - removeCount;
            for (int i = savedFileCount - 1; i > removeTillIndex; --i) {
                final UserSavedFile usf = mSavedFilesInOrder.remove(i);
                if (usf != null) {
                    mUserSavedFiles.remove(usf.mFileId);
                    usfs.add(usf);
                }
            }
        }
        if (usfs.size() > 0) {
            removeSnapshotFiles(usfs);
        }
    }

    private void removeSnapshotFiles(ArrayList<UserSavedFile> files) {
        synchronized (mSnapshotPersistQueue.getLock()) {
            mSnapshotPersistQueue.sendToQueueLocked(
                    new SnapshotPersistQueue.WriteQueueItem(mPersistInfoProvider) {
                        @Override
                        void write() {
                            for (int i = files.size() - 1; i >= 0; --i) {
                                final UserSavedFile usf = files.get(i);
                                mSnapshotPersistQueue.deleteSnapshot(
                                        usf.mFileId, usf.mUserId, mPersistInfoProvider);
                            }
                        }
                    });
        }
    }

    static class UserSavedFile {
        int mFileId;
        int mUserId;
        UserSavedFile(int fileId, int userId) {
            mFileId = fileId;
            mUserId = userId;
        }
    }
}
