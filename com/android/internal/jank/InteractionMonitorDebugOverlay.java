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

package com.android.internal.jank;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Gravity.CENTER;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;

import android.annotation.AnyThread;
import android.annotation.ColorInt;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.UiThread;
import android.app.Application;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Trace;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import com.android.internal.util.LatencyTracker;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

/**
 * An overlay that uses WindowCallbacks to draw the names of all running CUJs to the window
 * associated with one of the CUJs being tracked. There's no guarantee which window it will
 * draw to. Traces that use the debug overlay should not be used for performance analysis.
 * <p>
 * To enable the overlay, run the following: <code>adb shell device_config put
 * interaction_jank_monitor debug_overlay_enabled true</code>
 * <p>
 * CUJ names will be drawn as follows:
 * <ul>
 * <li> Normal text indicates the CUJ is currently running
 * <li> Grey text indicates the CUJ ended normally and is no longer running
 * <li> Red text with a strikethrough indicates the CUJ was canceled or ended abnormally
 * </ul>
 *
 * @hide
 */
public class InteractionMonitorDebugOverlay {
    private static final String TAG = "InteractionMonitorDebug";
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_ENDED = 1;
    private static final int STATUS_CANCELLED = 2;

    @IntDef({
            STATUS_RUNNING,
            STATUS_ENDED,
            STATUS_CANCELLED
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface TrackerStatus {
    }

    private static final long HIDE_OVERLAY_DELAY = 2000L;
    // Sparse array where the key in the CUJ and the value is the session status, or null if
    // it's currently running
    private final Application mCurrentApplication;
    private final Handler mUiThread;
    private final DebugOverlayView mDebugOverlayView;
    private final WindowManager mWindowManager;
    private final LatencyTracker mLatencyTracker;
    private final ArrayList<TrackerState> mRunningCujs = new ArrayList<>();

    InteractionMonitorDebugOverlay(@NonNull Application currentApplication,
            @NonNull @UiThread Handler uiThread, @ColorInt int bgColor, double yOffset) {
        mCurrentApplication = currentApplication;
        mUiThread = uiThread;
        final Display display = mCurrentApplication.getSystemService(
                DisplayManager.class).getDisplay(DEFAULT_DISPLAY);
        final Context windowContext = mCurrentApplication.createDisplayContext(
                display).createWindowContext(TYPE_SYSTEM_OVERLAY, null /* options */);
        mWindowManager = windowContext.getSystemService(WindowManager.class);
        mLatencyTracker = LatencyTracker.getInstance(windowContext);
        mLatencyTracker.setDebugOverlay(this);

        final Rect size = mWindowManager.getCurrentWindowMetrics().getBounds();

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                | WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;

        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0 /* types */);
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;

        lp.width = size.width();
        lp.height = size.height();
        lp.gravity = CENTER;
        lp.setTitle("InteractionMonitorDebugOverlay");

        if (!mUiThread.getLooper().isCurrentThread()) {
            Log.e(TAG, "InteractionMonitorDebugOverlay must be constructed on "
                    + "InteractionJankMonitor's worker thread");
        }
        mDebugOverlayView = new DebugOverlayView(mCurrentApplication, bgColor, yOffset);
        mWindowManager.addView(mDebugOverlayView, lp);
    }

    private final Runnable mHideOverlayRunnable = new Runnable() {
        @Override
        public void run() {
            mRunningCujs.clear();
            mDebugOverlayView.setVisibility(INVISIBLE);
        }
    };

    @AnyThread
    public void onTrackerAdded(String name, int cookie) {
        mUiThread.removeCallbacks(mHideOverlayRunnable);
        mUiThread.post(() -> {
            Log.i(TAG, name + " started (cookie=" + cookie + ")");
            mRunningCujs.add(new TrackerState(name, cookie));
            mDebugOverlayView.setVisibility(VISIBLE);
            mDebugOverlayView.invalidate();
        });
    }

    @AnyThread
    public void onTrackerRemoved(boolean cancelled, int cookie) {
        mUiThread.post(() -> {
            boolean allTrackersEnded = true;
            for (int i = 0; i < mRunningCujs.size(); i++) {
                TrackerState tracker = mRunningCujs.get(i);
                if (tracker.mCookie == cookie) {
                    tracker.mState = cancelled ? STATUS_CANCELLED : STATUS_ENDED;
                    Log.i(TAG, tracker.mName + (cancelled ? " cancelled" : " ended")
                            + " (cookie=" + cookie + ")");
                } else {
                    // If none of the trackers are STATUS_RUNNING, then all CUJs have ended
                    allTrackersEnded = allTrackersEnded && tracker.mState != STATUS_RUNNING;
                }
            }
            if (allTrackersEnded) {
                Log.i(TAG, "All CUJs ended");
                mUiThread.postDelayed(mHideOverlayRunnable, HIDE_OVERLAY_DELAY);
            }
            mDebugOverlayView.invalidate();
        });
    }

    @AnyThread
    void dispose() {
        mLatencyTracker.setDebugOverlay(null);
        mUiThread.post(() -> {
            mWindowManager.removeView(mDebugOverlayView);
        });
    }

    @AnyThread
    private static class TrackerState {
        final int mCookie;
        final String mName;
        @TrackerStatus int mState;

        private TrackerState(String name, int cookie) {
            mName = name;
            mCookie = cookie;
            mState = STATUS_RUNNING;
        }
    }

    @UiThread
    private class DebugOverlayView extends View {
        private static final String TRACK_NAME = "InteractionJankMonitor";

        // Used to display the overlay in a different color and position for different processes.
        // Otherwise, two overlays will overlap and be difficult to read.
        private final int mBgColor;
        private final double mYOffset;

        private final float mDensity;
        private final Paint mDebugPaint;
        private final Paint.FontMetrics mDebugFontMetrics;
        private final String mPackageNameText;

        final int mPadding;
        final int mPackageNameFontSize;
        final int mCujFontSize;
        final float mCujNameTextHeight;
        final float mCujStatusWidth;
        final float mPackageNameTextHeight;
        final float mPackageNameWidth;

        private DebugOverlayView(Context context, @ColorInt int bgColor, double yOffset) {
            super(context);
            setVisibility(INVISIBLE);
            mBgColor = bgColor;
            mYOffset = yOffset;
            final DisplayMetrics displayMetrics = getContext().getResources().getDisplayMetrics();
            mDensity = displayMetrics.density;
            mDebugPaint = new Paint();
            mDebugPaint.setAntiAlias(false);
            mDebugFontMetrics = new Paint.FontMetrics();
            mPackageNameText = "package:" + mCurrentApplication.getPackageName();
            mPadding = dipToPx(5);
            mPackageNameFontSize = dipToPx(12);
            mCujFontSize = dipToPx(18);
            mCujNameTextHeight = getTextHeight(mCujFontSize);
            mCujStatusWidth = mCujNameTextHeight * 1.2f;
            mPackageNameTextHeight = getTextHeight(mPackageNameFontSize);
            mPackageNameWidth = getWidthOfText(mPackageNameText, mPackageNameFontSize);
        }

        private int dipToPx(int dip) {
            return (int) (mDensity * dip + 0.5f);
        }

        private float getTextHeight(int textSize) {
            mDebugPaint.setTextSize(textSize);
            mDebugPaint.getFontMetrics(mDebugFontMetrics);
            return mDebugFontMetrics.descent - mDebugFontMetrics.ascent;
        }

        private float getWidthOfText(String text, int fontSize) {
            mDebugPaint.setTextSize(fontSize);
            return mDebugPaint.measureText(text);
        }

        private float getWidthOfLongestCujName(int cujFontSize) {
            mDebugPaint.setTextSize(cujFontSize);
            float maxLength = 0;
            for (int i = 0; i < mRunningCujs.size(); i++) {
                String trackerName = mRunningCujs.get(i).mName;
                float textLength = mDebugPaint.measureText(trackerName);
                if (textLength > maxLength) {
                    maxLength = textLength;
                }
            }
            return maxLength;
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);

            // Add a trace marker so we can identify traces that were captured while the debug
            // overlay was enabled. Traces that use the debug overlay should NOT be used for
            // performance analysis.
            Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_APP, TRACK_NAME, "DEBUG_OVERLAY_DRAW", 0);

            final int h = getHeight();
            final int w = getWidth();
            final int dy = (int) (h * mYOffset);

            float maxLength = Math.max(mPackageNameWidth, getWidthOfLongestCujName(mCujFontSize))
                    + mCujStatusWidth;

            final int dx = (int) ((w - maxLength) / 2f);
            canvas.translate(dx, dy);
            // Draw background rectangle for displaying the text showing the CUJ name
            mDebugPaint.setColor(mBgColor);
            canvas.drawRect(-mPadding * 2, // more padding on top so we can draw the package name
                    -mPadding, mPadding * 2 + maxLength, mPadding * 2 + mPackageNameTextHeight
                            + mCujNameTextHeight * mRunningCujs.size(), mDebugPaint);
            mDebugPaint.setTextSize(mPackageNameFontSize);
            mDebugPaint.setColor(Color.BLACK);
            mDebugPaint.setStrikeThruText(false);
            canvas.translate(0, mPackageNameTextHeight);
            canvas.drawText(mPackageNameText, 0, 0, mDebugPaint);
            mDebugPaint.setTextSize(mCujFontSize);
            // Draw text for CUJ names
            for (int i = 0; i < mRunningCujs.size(); i++) {
                TrackerState tracker = mRunningCujs.get(i);
                @TrackerStatus int status = tracker.mState;
                String statusText = switch (status) {
                    case STATUS_RUNNING -> {
                        mDebugPaint.setColor(Color.BLACK);
                        mDebugPaint.setStrikeThruText(false);
                        yield "☐"; // BALLOT BOX
                    }
                    case STATUS_ENDED -> {
                        mDebugPaint.setColor(Color.GRAY);
                        mDebugPaint.setStrikeThruText(false);
                        yield "✅"; // WHITE HEAVY CHECK MARK
                    }
                    case STATUS_CANCELLED -> {
                        // Cancelled, or otherwise ended for a bad reason
                        mDebugPaint.setColor(Color.RED);
                        mDebugPaint.setStrikeThruText(true);
                        yield "❌"; // CROSS MARK
                    }
                    default -> {
                        Log.w(TAG, "Unexpected tracker status value: " + status);
                        yield "?";
                    }
                };
                String trackerName = tracker.mName;
                canvas.translate(0, mCujNameTextHeight);
                canvas.drawText(statusText, 0, 0, mDebugPaint);
                canvas.drawText(trackerName, mCujStatusWidth, 0, mDebugPaint);
            }
            Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_APP, TRACK_NAME, 0);
        }
    }
}
