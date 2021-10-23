/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TASK_POSITIONING;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.Nullable;
import android.app.IActivityTaskManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Slog;
import android.view.Display;
import android.view.IWindow;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;

import com.android.internal.annotations.GuardedBy;
import com.android.server.input.InputManagerService;

/**
 * Controller for task positioning by drag.
 */
class TaskPositioningController {
    private final WindowManagerService mService;
    private final InputManagerService mInputManager;
    private final IActivityTaskManager mActivityManager;
    private final Handler mHandler;
    private SurfaceControl mInputSurface;
    private DisplayContent mPositioningDisplay;

    @GuardedBy("WindowManagerSerivce.mWindowMap")
    private @Nullable TaskPositioner mTaskPositioner;

    private final Rect mTmpClipRect = new Rect();

    boolean isPositioningLocked() {
        return mTaskPositioner != null;
    }

    final SurfaceControl.Transaction mTransaction;

    InputWindowHandle getDragWindowHandleLocked() {
        return mTaskPositioner != null ? mTaskPositioner.mDragWindowHandle : null;
    }

    TaskPositioningController(WindowManagerService service, InputManagerService inputManager,
            IActivityTaskManager activityManager, Looper looper) {
        mService = service;
        mInputManager = inputManager;
        mActivityManager = activityManager;
        mHandler = new Handler(looper);
        mTransaction = service.mTransactionFactory.get();
    }

    void hideInputSurface(int displayId) {
        if (mPositioningDisplay != null && mPositioningDisplay.getDisplayId() == displayId
                && mInputSurface != null) {
            mTransaction.hide(mInputSurface);
            mTransaction.syncInputWindows().apply();
        }
    }

    void showInputSurface(int displayId) {
        if (mPositioningDisplay == null || mPositioningDisplay.getDisplayId() != displayId) {
            return;
        }
        final DisplayContent dc = mService.mRoot.getDisplayContent(displayId);
        if (mInputSurface == null) {
            mInputSurface = mService.makeSurfaceBuilder(dc.getSession())
                    .setContainerLayer()
                    .setName("Drag and Drop Input Consumer")
                    .setCallsite("TaskPositioningController.showInputSurface")
                    .build();
        }

        final InputWindowHandle h = getDragWindowHandleLocked();
        if (h == null) {
            Slog.w(TAG_WM, "Drag is in progress but there is no "
                    + "drag window handle.");
            return;
        }

        mTransaction.show(mInputSurface);
        mTransaction.setInputWindowInfo(mInputSurface, h);
        mTransaction.setLayer(mInputSurface, Integer.MAX_VALUE);

        final Display display = dc.getDisplay();
        final Point p = new Point();
        display.getRealSize(p);

        mTmpClipRect.set(0, 0, p.x, p.y);
        mTransaction.setWindowCrop(mInputSurface, mTmpClipRect);
        mTransaction.syncInputWindows().apply();
    }

    boolean startMovingTask(IWindow window, float startX, float startY) {
        WindowState win = null;
        synchronized (mService.mGlobalLock) {
            win = mService.windowForClientLocked(null, window, false);
            // win shouldn't be null here, pass it down to startPositioningLocked
            // to get warning if it's null.
            if (!startPositioningLocked(
                    win, false /*resize*/, false /*preserveOrientation*/, startX, startY)) {
                return false;
            }
        }
        try {
            mActivityManager.setFocusedTask(win.getTask().mTaskId);
        } catch(RemoteException e) {}
        return true;
    }

    void handleTapOutsideTask(DisplayContent displayContent, int x, int y) {
        mHandler.post(() -> {
            synchronized (mService.mGlobalLock) {
                final Task task = displayContent.findTaskForResizePoint(x, y);
                if (task != null) {
                    if (!task.isResizeable()) {
                        // The task is not resizable, so don't do anything when the user drags the
                        // the resize handles.
                        return;
                    }
                    if (!startPositioningLocked(task.getTopVisibleAppMainWindow(), true /*resize*/,
                            task.preserveOrientationOnResize(), x, y)) {
                        return;
                    }
                    try {
                        mActivityManager.setFocusedTask(task.mTaskId);
                    } catch (RemoteException e) {
                    }
                }
            }
        });
    }

    private boolean startPositioningLocked(WindowState win, boolean resize,
            boolean preserveOrientation, float startX, float startY) {
        if (DEBUG_TASK_POSITIONING)
            Slog.d(TAG_WM, "startPositioningLocked: "
                    + "win=" + win + ", resize=" + resize + ", preserveOrientation="
                    + preserveOrientation + ", {" + startX + ", " + startY + "}");

        if (win == null || win.getAppToken() == null) {
            Slog.w(TAG_WM, "startPositioningLocked: Bad window " + win);
            return false;
        }
        if (win.mInputChannel == null) {
            Slog.wtf(TAG_WM, "startPositioningLocked: " + win + " has no input channel, "
                    + " probably being removed");
            return false;
        }

        final DisplayContent displayContent = win.getDisplayContent();
        if (displayContent == null) {
            Slog.w(TAG_WM, "startPositioningLocked: Invalid display content " + win);
            return false;
        }
        mPositioningDisplay = displayContent;

        mTaskPositioner = TaskPositioner.create(mService);
        mTaskPositioner.register(displayContent, win);

        // We need to grab the touch focus so that the touch events during the
        // resizing/scrolling are not sent to the app. 'win' is the main window
        // of the app, it may not have focus since there might be other windows
        // on top (eg. a dialog window).
        WindowState transferFocusFromWin = win;
        if (displayContent.mCurrentFocus != null && displayContent.mCurrentFocus != win
                && displayContent.mCurrentFocus.mActivityRecord == win.mActivityRecord) {
            transferFocusFromWin = displayContent.mCurrentFocus;
        }
        if (!mInputManager.transferTouchFocus(
                transferFocusFromWin.mInputChannel, mTaskPositioner.mClientChannel,
                false /* isDragDrop */)) {
            Slog.e(TAG_WM, "startPositioningLocked: Unable to transfer touch focus");
            cleanUpTaskPositioner();
            return false;
        }

        mTaskPositioner.startDrag(resize, preserveOrientation, startX, startY);
        return true;
    }

    public void finishTaskPositioning(IWindow window) {
        if (mTaskPositioner != null && mTaskPositioner.mClientCallback == window.asBinder()) {
            finishTaskPositioning();
        }
    }

    void finishTaskPositioning() {
        // TaskPositioner attaches the InputEventReceiver to the animation thread. We need to
        // dispose the receiver on the same thread to avoid race conditions.
        mService.mAnimationHandler.post(() -> {
            if (DEBUG_TASK_POSITIONING) Slog.d(TAG_WM, "finishPositioning");

            synchronized (mService.mGlobalLock) {
                cleanUpTaskPositioner();
                mPositioningDisplay = null;
            }
        });
    }

    private void cleanUpTaskPositioner() {
        final TaskPositioner positioner = mTaskPositioner;
        if (positioner == null) {
            return;
        }

        // We need to assign task positioner to null first to indicate that we're finishing task
        // positioning.
        mTaskPositioner = null;
        positioner.unregister();
    }
}
