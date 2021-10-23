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
 * limitations under the License
 */

package com.android.systemui.screenshot;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.function.Consumer;

/**
 * Draws a selection rectangle while taking screenshot
 */
public class ScreenshotSelectorView extends View {
    private Point mStartPoint;
    private Rect mSelectionRect;
    private final Paint mPaintSelection, mPaintBackground;

    private Consumer<Rect> mOnScreenshotSelected;

    public ScreenshotSelectorView(Context context) {
        this(context, null);
    }

    public ScreenshotSelectorView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mPaintBackground = new Paint(Color.BLACK);
        mPaintBackground.setAlpha(160);
        mPaintSelection = new Paint(Color.TRANSPARENT);
        mPaintSelection.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startSelection((int) event.getX(), (int) event.getY());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    updateSelection((int) event.getX(), (int) event.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                    setVisibility(View.GONE);
                    final Rect rect = getSelectionRect();
                    if (mOnScreenshotSelected != null
                            && rect != null
                            && rect.width() != 0 && rect.height() != 0) {
                        mOnScreenshotSelected.accept(rect);
                    }
                    stopSelection();
                    return true;
            }
            return false;
        });
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawRect(mLeft, mTop, mRight, mBottom, mPaintBackground);
        if (mSelectionRect != null) {
            canvas.drawRect(mSelectionRect, mPaintSelection);
        }
    }

    void setOnScreenshotSelected(Consumer<Rect> onScreenshotSelected) {
        mOnScreenshotSelected = onScreenshotSelected;
    }

    void stop() {
        if (getSelectionRect() != null) {
            stopSelection();
        }
    }

    private void startSelection(int x, int y) {
        mStartPoint = new Point(x, y);
        mSelectionRect = new Rect(x, y, x, y);
    }

    private void updateSelection(int x, int y) {
        if (mSelectionRect != null) {
            mSelectionRect.left = Math.min(mStartPoint.x, x);
            mSelectionRect.right = Math.max(mStartPoint.x, x);
            mSelectionRect.top = Math.min(mStartPoint.y, y);
            mSelectionRect.bottom = Math.max(mStartPoint.y, y);
            invalidate();
        }
    }

    private Rect getSelectionRect() {
        return mSelectionRect;
    }

    private void stopSelection() {
        mStartPoint = null;
        mSelectionRect = null;
    }
}
