/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static android.Manifest.permission.HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
import static android.Manifest.permission.HIDE_OVERLAY_WINDOWS;
import static android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
import static android.Manifest.permission.SET_UNRESTRICTED_GESTURE_EXCLUSION;
import static android.Manifest.permission.SET_UNRESTRICTED_KEEP_CLEAR_AREAS;
import static android.Manifest.permission.START_TASKS_FROM_RECENTS;
import static android.Manifest.permission.SYSTEM_APPLICATION_OVERLAY;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_ACTIVITY;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_SHORTCUT;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_TASK;
import static android.content.Intent.EXTRA_PACKAGE_NAME;
import static android.content.Intent.EXTRA_SHORTCUT_ID;
import static android.content.Intent.EXTRA_TASK_ID;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.isSystemAlertWindowType;

import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TASK_POSITIONING;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ShortcutServiceInternal;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.view.IWindow;
import android.view.IWindowId;
import android.view.IWindowSession;
import android.view.IWindowSessionCallback;
import android.view.InputChannel;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowManager;
import android.window.ClientWindowFrames;
import android.window.OnBackInvokedCallbackInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.logging.MetricsLoggerWrapper;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.LocalServices;
import com.android.server.wm.WindowManagerService.H;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * This class represents an active client session.  There is generally one
 * Session object per process that is interacting with the window manager.
 */
class Session extends IWindowSession.Stub implements IBinder.DeathRecipient {
    final WindowManagerService mService;
    final IWindowSessionCallback mCallback;
    final int mUid;
    final int mPid;
    private final String mStringName;
    SurfaceSession mSurfaceSession;
    private int mNumWindow = 0;
    // Set of visible application overlay window surfaces connected to this session.
    private final ArraySet<WindowSurfaceController> mAppOverlaySurfaces = new ArraySet<>();
    // Set of visible alert window surfaces connected to this session.
    private final ArraySet<WindowSurfaceController> mAlertWindowSurfaces = new ArraySet<>();
    private final DragDropController mDragDropController;
    final boolean mCanAddInternalSystemWindow;
    private final boolean mCanStartTasksFromRecents;

    final boolean mCanCreateSystemApplicationOverlay;
    final boolean mCanHideNonSystemOverlayWindows;
    final boolean mCanSetUnrestrictedGestureExclusion;
    private AlertWindowNotification mAlertWindowNotification;
    private boolean mShowingAlertWindowNotificationAllowed;
    private boolean mClientDead = false;
    private float mLastReportedAnimatorScale;
    protected String mPackageName;
    private String mRelayoutTag;
    private final InsetsSourceControl.Array mDummyControls =  new InsetsSourceControl.Array();
    final boolean mSetsUnrestrictedKeepClearAreas;

    public Session(WindowManagerService service, IWindowSessionCallback callback) {
        mService = service;
        mCallback = callback;
        mUid = Binder.getCallingUid();
        mPid = Binder.getCallingPid();
        mLastReportedAnimatorScale = service.getCurrentAnimatorScale();
        mCanAddInternalSystemWindow = service.mContext.checkCallingOrSelfPermission(
                INTERNAL_SYSTEM_WINDOW) == PERMISSION_GRANTED;
        mCanHideNonSystemOverlayWindows = service.mContext.checkCallingOrSelfPermission(
                HIDE_NON_SYSTEM_OVERLAY_WINDOWS) == PERMISSION_GRANTED
                || service.mContext.checkCallingOrSelfPermission(HIDE_OVERLAY_WINDOWS)
                == PERMISSION_GRANTED;
        mCanCreateSystemApplicationOverlay =
                service.mContext.checkCallingOrSelfPermission(SYSTEM_APPLICATION_OVERLAY)
                        == PERMISSION_GRANTED;
        mCanStartTasksFromRecents = service.mContext.checkCallingOrSelfPermission(
                START_TASKS_FROM_RECENTS) == PERMISSION_GRANTED;
        mSetsUnrestrictedKeepClearAreas =
                service.mContext.checkCallingOrSelfPermission(SET_UNRESTRICTED_KEEP_CLEAR_AREAS)
                        == PERMISSION_GRANTED;
        mCanSetUnrestrictedGestureExclusion =
                service.mContext.checkCallingOrSelfPermission(SET_UNRESTRICTED_GESTURE_EXCLUSION)
                        == PERMISSION_GRANTED;
        mShowingAlertWindowNotificationAllowed = mService.mShowAlertWindowNotifications;
        mDragDropController = mService.mDragDropController;
        StringBuilder sb = new StringBuilder();
        sb.append("Session{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" ");
        sb.append(mPid);
        if (mUid < Process.FIRST_APPLICATION_UID) {
            sb.append(":");
            sb.append(mUid);
        } else {
            sb.append(":u");
            sb.append(UserHandle.getUserId(mUid));
            sb.append('a');
            sb.append(UserHandle.getAppId(mUid));
        }
        sb.append("}");
        mStringName = sb.toString();

        try {
            mCallback.asBinder().linkToDeath(this, 0);
        } catch (RemoteException e) {
            // The caller has died, so we can just forget about this.
            // Hmmm, should we call killSessionLocked()??
        }
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            // Log all 'real' exceptions thrown to the caller
            if (!(e instanceof SecurityException)) {
                Slog.wtf(TAG_WM, "Window Session Crash", e);
            }
            throw e;
        }
    }

    @Override
    public void binderDied() {
        synchronized (mService.mGlobalLock) {
            mCallback.asBinder().unlinkToDeath(this, 0);
            mClientDead = true;
            killSessionLocked();
        }
    }

    @Override
    public int addToDisplay(IWindow window, WindowManager.LayoutParams attrs,
            int viewVisibility, int displayId, @InsetsType int requestedVisibleTypes,
            InputChannel outInputChannel, InsetsState outInsetsState,
            InsetsSourceControl.Array outActiveControls, Rect outAttachedFrame,
            float[] outSizeCompatScale) {
        return mService.addWindow(this, window, attrs, viewVisibility, displayId,
                UserHandle.getUserId(mUid), requestedVisibleTypes, outInputChannel, outInsetsState,
                outActiveControls, outAttachedFrame, outSizeCompatScale);
    }

    @Override
    public int addToDisplayAsUser(IWindow window, WindowManager.LayoutParams attrs,
            int viewVisibility, int displayId, int userId, @InsetsType int requestedVisibleTypes,
            InputChannel outInputChannel, InsetsState outInsetsState,
            InsetsSourceControl.Array outActiveControls, Rect outAttachedFrame,
            float[] outSizeCompatScale) {
        return mService.addWindow(this, window, attrs, viewVisibility, displayId, userId,
                requestedVisibleTypes, outInputChannel, outInsetsState, outActiveControls,
                outAttachedFrame, outSizeCompatScale);
    }

    @Override
    public int addToDisplayWithoutInputChannel(IWindow window, WindowManager.LayoutParams attrs,
            int viewVisibility, int displayId, InsetsState outInsetsState, Rect outAttachedFrame,
            float[] outSizeCompatScale) {
        return mService.addWindow(this, window, attrs, viewVisibility, displayId,
                UserHandle.getUserId(mUid), WindowInsets.Type.defaultVisible(),
                null /* outInputChannel */, outInsetsState, mDummyControls, outAttachedFrame,
                outSizeCompatScale);
    }

    @Override
    public void remove(IWindow window) {
        mService.removeWindow(this, window);
    }

    @Override
    public boolean cancelDraw(IWindow window) {
        return mService.cancelDraw(this, window);
    }

    @Override
    public int relayout(IWindow window, WindowManager.LayoutParams attrs,
            int requestedWidth, int requestedHeight, int viewFlags, int flags, int seq,
            int lastSyncSeqId, ClientWindowFrames outFrames,
            MergedConfiguration mergedConfiguration, SurfaceControl outSurfaceControl,
            InsetsState outInsetsState, InsetsSourceControl.Array outActiveControls,
            Bundle outSyncSeqIdBundle) {
        if (false) Slog.d(TAG_WM, ">>>>>> ENTERED relayout from "
                + Binder.getCallingPid());
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, mRelayoutTag);
        int res = mService.relayoutWindow(this, window, attrs,
                requestedWidth, requestedHeight, viewFlags, flags, seq,
                lastSyncSeqId, outFrames, mergedConfiguration, outSurfaceControl, outInsetsState,
                outActiveControls, outSyncSeqIdBundle);
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        if (false) Slog.d(TAG_WM, "<<<<<< EXITING relayout to "
                + Binder.getCallingPid());
        return res;
    }

    @Override
    public void relayoutAsync(IWindow window, WindowManager.LayoutParams attrs,
            int requestedWidth, int requestedHeight, int viewFlags, int flags, int seq,
            int lastSyncSeqId) {
        relayout(window, attrs, requestedWidth, requestedHeight, viewFlags, flags, seq,
                lastSyncSeqId, null /* outFrames */, null /* mergedConfiguration */,
                null /* outSurfaceControl */, null /* outInsetsState */,
                null /* outActiveControls */, null /* outSyncIdBundle */);
    }

    @Override
    public boolean outOfMemory(IWindow window) {
        return mService.outOfMemoryWindow(this, window);
    }

    @Override
    public void setInsets(IWindow window, int touchableInsets,
            Rect contentInsets, Rect visibleInsets, Region touchableArea) {
        mService.setInsetsWindow(this, window, touchableInsets, contentInsets,
                visibleInsets, touchableArea);
    }

    @Override
    public void clearTouchableRegion(IWindow window) {
        mService.clearTouchableRegion(this, window);
    }

    @Override
    public void finishDrawing(IWindow window,
            @Nullable SurfaceControl.Transaction postDrawTransaction, int seqId) {
        if (DEBUG) Slog.v(TAG_WM, "IWindow finishDrawing called for " + window);
        if (Trace.isTagEnabled(TRACE_TAG_WINDOW_MANAGER)) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "finishDrawing: " + mPackageName);
        }
        mService.finishDrawingWindow(this, window, postDrawTransaction, seqId);
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    @Override
    public boolean performHapticFeedback(int effectId, boolean always) {
        final long ident = Binder.clearCallingIdentity();
        try {
            return mService.mPolicy.performHapticFeedback(mUid, mPackageName,
                        effectId, always, null);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void performHapticFeedbackAsync(int effectId, boolean always) {
        performHapticFeedback(effectId, always);
    }

    /* Drag/drop */

    @Override
    public IBinder performDrag(IWindow window, int flags, SurfaceControl surface, int touchSource,
            float touchX, float touchY, float thumbCenterX, float thumbCenterY, ClipData data) {
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        // Validate and resolve ClipDescription data before clearing the calling identity
        validateAndResolveDragMimeTypeExtras(data, callingUid, callingPid, mPackageName);
        validateDragFlags(flags);
        final long ident = Binder.clearCallingIdentity();
        try {
            return mDragDropController.performDrag(mPid, mUid, window, flags, surface, touchSource,
                    touchX, touchY, thumbCenterX, thumbCenterY, data);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }


    @Override
    public boolean dropForAccessibility(IWindow window, int x, int y) {
        final long ident = Binder.clearCallingIdentity();
        try {
            return mDragDropController.dropForAccessibility(window, x, y);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Validates the given drag flags.
     */
    @VisibleForTesting
    void validateDragFlags(int flags) {
        if ((flags & View.DRAG_FLAG_REQUEST_SURFACE_FOR_RETURN_ANIMATION) != 0) {
            if (!mCanStartTasksFromRecents) {
                throw new SecurityException("Requires START_TASKS_FROM_RECENTS permission");
            }
        }
    }

    /**
     * Validates the given drag data.
     */
    @VisibleForTesting
    void validateAndResolveDragMimeTypeExtras(ClipData data, int callingUid, int callingPid,
            String callingPackage) {
        final ClipDescription desc = data != null ? data.getDescription() : null;
        if (desc == null) {
            return;
        }
        // Ensure that only one of the app mime types are set
        final boolean hasActivity = desc.hasMimeType(MIMETYPE_APPLICATION_ACTIVITY);
        final boolean hasShortcut = desc.hasMimeType(MIMETYPE_APPLICATION_SHORTCUT);
        final boolean hasTask = desc.hasMimeType(MIMETYPE_APPLICATION_TASK);
        int appMimeTypeCount = (hasActivity ? 1 : 0)
                + (hasShortcut ? 1 : 0)
                + (hasTask ? 1 : 0);
        if (appMimeTypeCount == 0) {
            return;
        } else if (appMimeTypeCount > 1) {
            throw new IllegalArgumentException("Can not specify more than one of activity, "
                    + "shortcut, or task mime types");
        }
        // Ensure that data is provided and that they are intents
        if (data.getItemCount() == 0) {
            throw new IllegalArgumentException("Unexpected number of items (none)");
        }
        for (int i = 0; i < data.getItemCount(); i++) {
            if (data.getItemAt(i).getIntent() == null) {
                throw new IllegalArgumentException("Unexpected item, expected an intent");
            }
        }

        if (hasActivity) {
            long origId = Binder.clearCallingIdentity();
            try {
                // Resolve the activity info for each intent
                for (int i = 0; i < data.getItemCount(); i++) {
                    final ClipData.Item item = data.getItemAt(i);
                    final Intent intent = item.getIntent();
                    final PendingIntent pi = intent.getParcelableExtra(
                            ClipDescription.EXTRA_PENDING_INTENT);
                    final UserHandle user = intent.getParcelableExtra(Intent.EXTRA_USER);
                    if (pi == null || user == null) {
                        throw new IllegalArgumentException("Clip data must include the pending "
                                + "intent to launch and its associated user to launch for.");
                    }
                    final Intent launchIntent = mService.mAmInternal.getIntentForIntentSender(
                            pi.getIntentSender().getTarget());
                    final ActivityInfo info = mService.mAtmService.resolveActivityInfoForIntent(
                            launchIntent, null /* resolvedType */, user.getIdentifier(),
                            callingUid, callingPid);
                    item.setActivityInfo(info);
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        } else if (hasShortcut) {
            // Restrict who can start a shortcut drag since it will start the shortcut as the
            // target shortcut package
            if (!mCanStartTasksFromRecents) {
                throw new SecurityException("Requires START_TASKS_FROM_RECENTS permission");
            }
            for (int i = 0; i < data.getItemCount(); i++) {
                final ClipData.Item item = data.getItemAt(i);
                final Intent intent = item.getIntent();
                final String shortcutId = intent.getStringExtra(EXTRA_SHORTCUT_ID);
                final String packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);
                final UserHandle user = intent.getParcelableExtra(Intent.EXTRA_USER);
                if (TextUtils.isEmpty(shortcutId)
                        || TextUtils.isEmpty(packageName)
                        || user == null) {
                    throw new IllegalArgumentException("Clip item must include the package name, "
                            + "shortcut id, and the user to launch for.");
                }
                final ShortcutServiceInternal shortcutService =
                        LocalServices.getService(ShortcutServiceInternal.class);
                final Intent[] shortcutIntents = shortcutService.createShortcutIntents(
                        UserHandle.getUserId(callingUid), callingPackage, packageName, shortcutId,
                        user.getIdentifier(), callingPid, callingUid);
                if (shortcutIntents == null || shortcutIntents.length == 0) {
                    throw new IllegalArgumentException("Invalid shortcut id");
                }
                final ActivityInfo info = mService.mAtmService.resolveActivityInfoForIntent(
                        shortcutIntents[0], null /* resolvedType */, user.getIdentifier(),
                        callingUid, callingPid);
                item.setActivityInfo(info);
            }
        } else if (hasTask) {
            // TODO(b/169894807): Consider opening this up for tasks from the same app as the caller
            if (!mCanStartTasksFromRecents) {
                throw new SecurityException("Requires START_TASKS_FROM_RECENTS permission");
            }
            for (int i = 0; i < data.getItemCount(); i++) {
                final ClipData.Item item = data.getItemAt(i);
                final Intent intent = item.getIntent();
                final int taskId = intent.getIntExtra(EXTRA_TASK_ID, INVALID_TASK_ID);
                if (taskId == INVALID_TASK_ID) {
                    throw new IllegalArgumentException("Clip item must include the task id.");
                }
                final Task task = mService.mRoot.anyTaskForId(taskId);
                if (task == null) {
                    throw new IllegalArgumentException("Invalid task id.");
                }
                if (task.getRootActivity() != null) {
                    item.setActivityInfo(task.getRootActivity().info);
                } else {
                    // Resolve the activity info manually if the task was restored after reboot
                    final ActivityInfo info = mService.mAtmService.resolveActivityInfoForIntent(
                            task.intent, null /* resolvedType */, task.mUserId, callingUid,
                            callingPid);
                    item.setActivityInfo(info);
                }
            }
        }
    }

    @Override
    public void reportDropResult(IWindow window, boolean consumed) {
        final long ident = Binder.clearCallingIdentity();
        try {
            mDragDropController.reportDropResult(window, consumed);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void cancelDragAndDrop(IBinder dragToken, boolean skipAnimation) {
        final long ident = Binder.clearCallingIdentity();
        try {
            mDragDropController.cancelDragAndDrop(dragToken, skipAnimation);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void dragRecipientEntered(IWindow window) {
        mDragDropController.dragRecipientEntered(window);
    }

    @Override
    public void dragRecipientExited(IWindow window) {
        mDragDropController.dragRecipientExited(window);
    }

    @Override
    public boolean startMovingTask(IWindow window, float startX, float startY) {
        if (DEBUG_TASK_POSITIONING) Slog.d(
                TAG_WM, "startMovingTask: {" + startX + "," + startY + "}");

        final long ident = Binder.clearCallingIdentity();
        try {
            return mService.mTaskPositioningController.startMovingTask(window, startX, startY);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void finishMovingTask(IWindow window) {
        if (DEBUG_TASK_POSITIONING) Slog.d(TAG_WM, "finishMovingTask");

        final long ident = Binder.clearCallingIdentity();
        try {
            mService.mTaskPositioningController.finishTaskPositioning(window);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void reportSystemGestureExclusionChanged(IWindow window, List<Rect> exclusionRects) {
        final long ident = Binder.clearCallingIdentity();
        try {
            mService.reportSystemGestureExclusionChanged(this, window, exclusionRects);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void reportKeepClearAreasChanged(IWindow window, List<Rect> restricted,
            List<Rect> unrestricted) {
        if (!mSetsUnrestrictedKeepClearAreas && !unrestricted.isEmpty()) {
            unrestricted = Collections.emptyList();
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            mService.reportKeepClearAreasChanged(this, window, restricted, unrestricted);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void actionOnWallpaper(IBinder window,
            BiConsumer<WallpaperController, WindowState> action) {
        final WindowState windowState = mService.windowForClientLocked(this, window, true);
        action.accept(windowState.getDisplayContent().mWallpaperController, windowState);
    }

    @Override
    public void setWallpaperPosition(IBinder window, float x, float y, float xStep, float yStep) {
        synchronized (mService.mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                actionOnWallpaper(window, (wpController, windowState) ->
                        wpController.setWindowWallpaperPosition(windowState, x, y, xStep, yStep));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void setWallpaperZoomOut(IBinder window, float zoom) {
        if (Float.compare(0f, zoom) > 0 || Float.compare(1f, zoom) < 0 || Float.isNaN(zoom)) {
            throw new IllegalArgumentException("Zoom must be a valid float between 0 and 1: "
                    + zoom);
        }
        synchronized (mService.mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                actionOnWallpaper(window, (wpController, windowState) ->
                        wpController.setWallpaperZoomOut(windowState, zoom));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void setShouldZoomOutWallpaper(IBinder window, boolean shouldZoom) {
        synchronized (mService.mGlobalLock) {
            actionOnWallpaper(window, (wpController, windowState) ->
                    wpController.setShouldZoomOutWallpaper(windowState, shouldZoom));
        }
    }

    @Override
    public void wallpaperOffsetsComplete(IBinder window) {
        synchronized (mService.mGlobalLock) {
            actionOnWallpaper(window, (wpController, windowState) ->
                    wpController.wallpaperOffsetsComplete(window));
        }
    }

    @Override
    public void setWallpaperDisplayOffset(IBinder window, int x, int y) {
        synchronized (mService.mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                actionOnWallpaper(window, (wpController, windowState) ->
                        wpController.setWindowWallpaperDisplayOffset(windowState, x, y));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public Bundle sendWallpaperCommand(IBinder window, String action, int x, int y,
            int z, Bundle extras, boolean sync) {
        synchronized (mService.mGlobalLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                final WindowState windowState = mService.windowForClientLocked(this, window, true);
                return windowState.getDisplayContent().mWallpaperController
                        .sendWindowWallpaperCommand(windowState, action, x, y, z, extras, sync);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void wallpaperCommandComplete(IBinder window, Bundle result) {
        synchronized (mService.mGlobalLock) {
            actionOnWallpaper(window, (wpController, windowState) ->
                    wpController.wallpaperCommandComplete(window));
        }
    }

    @Override
    public void onRectangleOnScreenRequested(IBinder token, Rect rectangle) {
        synchronized (mService.mGlobalLock) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mService.onRectangleOnScreenRequested(token, rectangle);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public IWindowId getWindowId(IBinder window) {
        return mService.getWindowId(window);
    }

    @Override
    public void pokeDrawLock(IBinder window) {
        final long identity = Binder.clearCallingIdentity();
        try {
            mService.pokeDrawLock(this, window);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void updatePointerIcon(IWindow window) {
        final long identity = Binder.clearCallingIdentity();
        try {
            mService.updatePointerIcon(window);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void updateTapExcludeRegion(IWindow window, Region region) {
        final long identity = Binder.clearCallingIdentity();
        try {
            mService.updateTapExcludeRegion(window, region);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void updateRequestedVisibleTypes(IWindow window, @InsetsType int requestedVisibleTypes) {
        synchronized (mService.mGlobalLock) {
            final WindowState windowState = mService.windowForClientLocked(this, window,
                    false /* throwOnError */);
            if (windowState != null) {
                windowState.setRequestedVisibleTypes(requestedVisibleTypes);
                windowState.getDisplayContent().getInsetsPolicy().onInsetsModified(windowState);
            }
        }
    }

    void windowAddedLocked() {
        if (mPackageName == null) {
            final WindowProcessController wpc = mService.mAtmService.mProcessMap.getProcess(mPid);
            if (wpc != null) {
                mPackageName = wpc.mInfo.packageName;
                mRelayoutTag = "relayoutWindow: " + mPackageName;
            } else {
                Slog.e(TAG_WM, "Unknown process pid=" + mPid);
            }
        }
        if (mSurfaceSession == null) {
            if (DEBUG) {
                Slog.v(TAG_WM, "First window added to " + this + ", creating SurfaceSession");
            }
            mSurfaceSession = new SurfaceSession();
            ProtoLog.i(WM_SHOW_TRANSACTIONS, "  NEW SURFACE SESSION %s", mSurfaceSession);
            mService.mSessions.add(this);
            if (mLastReportedAnimatorScale != mService.getCurrentAnimatorScale()) {
                mService.dispatchNewAnimatorScaleLocked(this);
            }
        }
        mNumWindow++;
    }

    void windowRemovedLocked() {
        mNumWindow--;
        killSessionLocked();
    }


    void onWindowSurfaceVisibilityChanged(WindowSurfaceController surfaceController,
            boolean visible, int type) {

        if (!isSystemAlertWindowType(type)) {
            return;
        }

        boolean changed;

        if (!mCanAddInternalSystemWindow && !mCanCreateSystemApplicationOverlay) {
            // We want to track non-system apps adding alert windows so we can post an
            // on-going notification for the user to control their visibility.
            if (visible) {
                changed = mAlertWindowSurfaces.add(surfaceController);
                MetricsLoggerWrapper.logAppOverlayEnter(mUid, mPackageName, changed, type, true);
            } else {
                changed = mAlertWindowSurfaces.remove(surfaceController);
                MetricsLoggerWrapper.logAppOverlayExit(mUid, mPackageName, changed, type, true);
            }

            if (changed) {
                if (mAlertWindowSurfaces.isEmpty()) {
                    cancelAlertWindowNotification();
                } else if (mAlertWindowNotification == null){
                    mAlertWindowNotification = new AlertWindowNotification(mService, mPackageName);
                    if (mShowingAlertWindowNotificationAllowed) {
                        mAlertWindowNotification.post();
                    }
                }
            }
        }

        if (type != TYPE_APPLICATION_OVERLAY) {
            return;
        }

        if (visible) {
            changed = mAppOverlaySurfaces.add(surfaceController);
            MetricsLoggerWrapper.logAppOverlayEnter(mUid, mPackageName, changed, type, false);
        } else {
            changed = mAppOverlaySurfaces.remove(surfaceController);
            MetricsLoggerWrapper.logAppOverlayExit(mUid, mPackageName, changed, type, false);
        }

        if (changed) {
            // Notify activity manager of changes to app overlay windows so it can adjust the
            // importance score for the process.
            setHasOverlayUi(!mAppOverlaySurfaces.isEmpty());
        }
    }

    void setShowingAlertWindowNotificationAllowed(boolean allowed) {
        mShowingAlertWindowNotificationAllowed = allowed;
        if (mAlertWindowNotification != null) {
            if (allowed) {
                mAlertWindowNotification.post();
            } else {
                mAlertWindowNotification.cancel(false /* deleteChannel */);
            }
        }
    }

    private void killSessionLocked() {
        if (mNumWindow > 0 || !mClientDead) {
            return;
        }

        mService.mSessions.remove(this);
        if (mSurfaceSession == null) {
            return;
        }

        if (DEBUG) {
            Slog.v(TAG_WM, "Last window removed from " + this
                    + ", destroying " + mSurfaceSession);
        }
        ProtoLog.i(WM_SHOW_TRANSACTIONS, "  KILL SURFACE SESSION %s", mSurfaceSession);
        try {
            mSurfaceSession.kill();
        } catch (Exception e) {
            Slog.w(TAG_WM, "Exception thrown when killing surface session " + mSurfaceSession
                    + " in session " + this + ": " + e.toString());
        }
        mSurfaceSession = null;
        mAlertWindowSurfaces.clear();
        mAppOverlaySurfaces.clear();
        setHasOverlayUi(false);
        cancelAlertWindowNotification();
    }

    private void setHasOverlayUi(boolean hasOverlayUi) {
        mService.mH.obtainMessage(H.SET_HAS_OVERLAY_UI, mPid, hasOverlayUi ? 1 : 0).sendToTarget();
    }

    private void cancelAlertWindowNotification() {
        if (mAlertWindowNotification == null) {
            return;
        }
        mAlertWindowNotification.cancel(true /* deleteChannel */);
        mAlertWindowNotification = null;
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mNumWindow="); pw.print(mNumWindow);
                pw.print(" mCanAddInternalSystemWindow="); pw.print(mCanAddInternalSystemWindow);
                pw.print(" mAppOverlaySurfaces="); pw.print(mAppOverlaySurfaces);
                pw.print(" mAlertWindowSurfaces="); pw.print(mAlertWindowSurfaces);
                pw.print(" mClientDead="); pw.print(mClientDead);
                pw.print(" mSurfaceSession="); pw.println(mSurfaceSession);
        pw.print(prefix); pw.print("mPackageName="); pw.println(mPackageName);
    }

    @Override
    public String toString() {
        return mStringName;
    }

    /** @return {@code true} if there is an alert window surface on the given display. */
    boolean hasAlertWindowSurfaces(DisplayContent displayContent) {
        for (int i = mAlertWindowSurfaces.size() - 1; i >= 0; i--) {
            final WindowSurfaceController surfaceController = mAlertWindowSurfaces.valueAt(i);
            if (surfaceController.mAnimator.mWin.getDisplayContent() == displayContent) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void grantInputChannel(int displayId, SurfaceControl surface,
            IWindow window, IBinder hostInputToken, int flags, int privateFlags, int type,
            int inputFeatures, IBinder windowToken, IBinder focusGrantToken, String inputHandleName,
            InputChannel outInputChannel) {
        if (hostInputToken == null && !mCanAddInternalSystemWindow) {
            // Callers without INTERNAL_SYSTEM_WINDOW permission cannot grant input channel to
            // embedded windows without providing a host window input token
            throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            mService.grantInputChannel(this, mUid, mPid, displayId, surface, window, hostInputToken,
                    flags, mCanAddInternalSystemWindow ? privateFlags : 0,
                    type, inputFeatures, windowToken, focusGrantToken, inputHandleName,
                    outInputChannel);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void updateInputChannel(IBinder channelToken, int displayId, SurfaceControl surface,
            int flags, int privateFlags, int inputFeatures, Region region) {
        final long identity = Binder.clearCallingIdentity();
        try {
            mService.updateInputChannel(channelToken, displayId, surface, flags,
                    mCanAddInternalSystemWindow ? privateFlags : 0, inputFeatures, region);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void grantEmbeddedWindowFocus(IWindow callingWindow, IBinder targetInputToken,
                                         boolean grantFocus) {
        final long identity = Binder.clearCallingIdentity();
        try {
            if (callingWindow == null) {
                if (!mCanAddInternalSystemWindow) {
                    // Callers without INTERNAL_SYSTEM_WINDOW permission cannot request focus on
                    // embedded windows without providing the calling window
                    throw new SecurityException("Requires INTERNAL_SYSTEM_WINDOW permission");
                }
                mService.grantEmbeddedWindowFocus(this, targetInputToken, grantFocus);
            } else {
                mService.grantEmbeddedWindowFocus(this, callingWindow, targetInputToken,
                        grantFocus);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public boolean transferEmbeddedTouchFocusToHost(IWindow embeddedWindow) {
        if (embeddedWindow == null) {
            return false;
        }

        final long identity = Binder.clearCallingIdentity();
        boolean didTransfer = false;
        try {
            didTransfer = mService.transferEmbeddedTouchFocusToHost(embeddedWindow);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return didTransfer;
    }

    @Override
    public void generateDisplayHash(IWindow window, Rect boundsInWindow, String hashAlgorithm,
            RemoteCallback callback) {
        final long origId = Binder.clearCallingIdentity();
        try {
            mService.generateDisplayHash(this, window, boundsInWindow, hashAlgorithm, callback);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void setOnBackInvokedCallbackInfo(
            IWindow window,
            OnBackInvokedCallbackInfo callbackInfo) {
        synchronized (mService.mGlobalLock) {
            WindowState windowState = mService.windowForClientLocked(this, window, false);
            if (windowState == null) {
                Slog.i(TAG_WM,
                        "setOnBackInvokedCallback(): No window state for package:" + mPackageName);
            } else {
                windowState.setOnBackInvokedCallbackInfo(callbackInfo);
            }
        }
    }
}
