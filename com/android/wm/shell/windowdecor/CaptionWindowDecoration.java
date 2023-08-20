/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.windowdecor;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.WindowConfiguration;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.VectorDrawable;
import android.os.Handler;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewConfiguration;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SyncTransactionQueue;

/**
 * Defines visuals and behaviors of a window decoration of a caption bar and shadows. It works with
 * {@link CaptionWindowDecorViewModel}. The caption bar contains a back button, minimize button,
 * maximize button and close button.
 */
public class CaptionWindowDecoration extends WindowDecoration<WindowDecorLinearLayout> {
    private final Handler mHandler;
    private final Choreographer mChoreographer;
    private final SyncTransactionQueue mSyncQueue;

    private View.OnClickListener mOnCaptionButtonClickListener;
    private View.OnTouchListener mOnCaptionTouchListener;
    private DragPositioningCallback mDragPositioningCallback;
    private DragResizeInputListener mDragResizeListener;
    private DragDetector mDragDetector;

    private RelayoutParams mRelayoutParams = new RelayoutParams();
    private final RelayoutResult<WindowDecorLinearLayout> mResult =
            new RelayoutResult<>();

    CaptionWindowDecoration(
            Context context,
            DisplayController displayController,
            ShellTaskOrganizer taskOrganizer,
            RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            Handler handler,
            Choreographer choreographer,
            SyncTransactionQueue syncQueue) {
        super(context, displayController, taskOrganizer, taskInfo, taskSurface);

        mHandler = handler;
        mChoreographer = choreographer;
        mSyncQueue = syncQueue;
    }

    void setCaptionListeners(
            View.OnClickListener onCaptionButtonClickListener,
            View.OnTouchListener onCaptionTouchListener) {
        mOnCaptionButtonClickListener = onCaptionButtonClickListener;
        mOnCaptionTouchListener = onCaptionTouchListener;
    }

    void setDragPositioningCallback(DragPositioningCallback dragPositioningCallback) {
        mDragPositioningCallback = dragPositioningCallback;
    }

    void setDragDetector(DragDetector dragDetector) {
        mDragDetector = dragDetector;
        mDragDetector.setTouchSlop(ViewConfiguration.get(mContext).getScaledTouchSlop());
    }

    @Override
    void relayout(RunningTaskInfo taskInfo) {
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        // Use |applyStartTransactionOnDraw| so that the transaction (that applies task crop) is
        // synced with the buffer transaction (that draws the View). Both will be shown on screen
        // at the same, whereas applying them independently causes flickering. See b/270202228.
        relayout(taskInfo, t, t, true /* applyStartTransactionOnDraw */);
    }

    void relayout(RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT, SurfaceControl.Transaction finishT,
            boolean applyStartTransactionOnDraw) {
        final int shadowRadiusID = taskInfo.isFocused
                ? R.dimen.freeform_decor_shadow_focused_thickness
                : R.dimen.freeform_decor_shadow_unfocused_thickness;
        final boolean isFreeform =
                taskInfo.getWindowingMode() == WindowConfiguration.WINDOWING_MODE_FREEFORM;
        final boolean isDragResizeable = isFreeform && taskInfo.isResizeable;

        final WindowDecorLinearLayout oldRootView = mResult.mRootView;
        final SurfaceControl oldDecorationSurface = mDecorationContainerSurface;
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        mRelayoutParams.reset();
        mRelayoutParams.mRunningTaskInfo = taskInfo;
        mRelayoutParams.mLayoutResId = R.layout.caption_window_decor;
        mRelayoutParams.mCaptionHeightId = R.dimen.freeform_decor_caption_height;
        mRelayoutParams.mShadowRadiusId = shadowRadiusID;
        mRelayoutParams.mApplyStartTransactionOnDraw = applyStartTransactionOnDraw;

        relayout(mRelayoutParams, startT, finishT, wct, oldRootView, mResult);
        // After this line, mTaskInfo is up-to-date and should be used instead of taskInfo

        mTaskOrganizer.applyTransaction(wct);

        if (mResult.mRootView == null) {
            // This means something blocks the window decor from showing, e.g. the task is hidden.
            // Nothing is set up in this case including the decoration surface.
            return;
        }
        if (oldRootView != mResult.mRootView) {
            setupRootView();
        }

        if (!isDragResizeable) {
            closeDragResizeListener();
            return;
        }

        if (oldDecorationSurface != mDecorationContainerSurface || mDragResizeListener == null) {
            closeDragResizeListener();
            mDragResizeListener = new DragResizeInputListener(
                    mContext,
                    mHandler,
                    mChoreographer,
                    mDisplay.getDisplayId(),
                    mDecorationContainerSurface,
                    mDragPositioningCallback);
        }

        final int touchSlop = ViewConfiguration.get(mResult.mRootView.getContext())
                .getScaledTouchSlop();
        mDragDetector.setTouchSlop(touchSlop);

        final int resize_handle = mResult.mRootView.getResources()
                .getDimensionPixelSize(R.dimen.freeform_resize_handle);
        final int resize_corner = mResult.mRootView.getResources()
                .getDimensionPixelSize(R.dimen.freeform_resize_corner);
        mDragResizeListener.setGeometry(
                mResult.mWidth, mResult.mHeight, resize_handle, resize_corner, touchSlop);
    }

    /**
     * Sets up listeners when a new root view is created.
     */
    private void setupRootView() {
        final View caption = mResult.mRootView.findViewById(R.id.caption);
        caption.setOnTouchListener(mOnCaptionTouchListener);
        final View close = caption.findViewById(R.id.close_window);
        close.setOnClickListener(mOnCaptionButtonClickListener);
        final View back = caption.findViewById(R.id.back_button);
        back.setOnClickListener(mOnCaptionButtonClickListener);
        final View minimize = caption.findViewById(R.id.minimize_window);
        minimize.setOnClickListener(mOnCaptionButtonClickListener);
        final View maximize = caption.findViewById(R.id.maximize_window);
        maximize.setOnClickListener(mOnCaptionButtonClickListener);
    }

    void setCaptionColor(int captionColor) {
        if (mResult.mRootView == null) {
            return;
        }

        final View caption = mResult.mRootView.findViewById(R.id.caption);
        final GradientDrawable captionDrawable = (GradientDrawable) caption.getBackground();
        captionDrawable.setColor(captionColor);

        final int buttonTintColorRes =
                Color.valueOf(captionColor).luminance() < 0.5
                        ? R.color.decor_button_light_color
                        : R.color.decor_button_dark_color;
        final ColorStateList buttonTintColor =
                caption.getResources().getColorStateList(buttonTintColorRes, null /* theme */);

        final View back = caption.findViewById(R.id.back_button);
        final VectorDrawable backBackground = (VectorDrawable) back.getBackground();
        backBackground.setTintList(buttonTintColor);

        final View minimize = caption.findViewById(R.id.minimize_window);
        final VectorDrawable minimizeBackground = (VectorDrawable) minimize.getBackground();
        minimizeBackground.setTintList(buttonTintColor);

        final View maximize = caption.findViewById(R.id.maximize_window);
        final VectorDrawable maximizeBackground = (VectorDrawable) maximize.getBackground();
        maximizeBackground.setTintList(buttonTintColor);

        final View close = caption.findViewById(R.id.close_window);
        final VectorDrawable closeBackground = (VectorDrawable) close.getBackground();
        closeBackground.setTintList(buttonTintColor);
    }

    private void closeDragResizeListener() {
        if (mDragResizeListener == null) {
            return;
        }
        mDragResizeListener.close();
        mDragResizeListener = null;
    }

    @Override
    public void close() {
        closeDragResizeListener();
        super.close();
    }
}
