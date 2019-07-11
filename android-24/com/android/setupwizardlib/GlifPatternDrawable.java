/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.setupwizardlib;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;

import com.android.setupwizardlib.annotations.VisibleForTesting;

public class GlifPatternDrawable extends Drawable {

    /* static section */

    @SuppressLint("InlinedApi")
    private static final int[] ATTRS_PRIMARY_COLOR = new int[]{ android.R.attr.colorPrimary };

    private static final float VIEWBOX_HEIGHT = 768f;
    private static final float VIEWBOX_WIDTH = 1366f;
    private static final float SCALE_FOCUS_X = 200f;
    private static final float SCALE_FOCUS_Y = 175f;

    public static GlifPatternDrawable getDefault(Context context) {
        int colorPrimary = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            final TypedArray a = context.obtainStyledAttributes(ATTRS_PRIMARY_COLOR);
            colorPrimary = a.getColor(0, Color.BLACK);
            a.recycle();
        }
        return new GlifPatternDrawable(colorPrimary);
    }

    /* non-static section */

    private int mColor;
    private Paint mPaint;
    private float[] mTempHsv = new float[3];
    private Path mTempPath = new Path();
    private Bitmap mBitmap;

    public GlifPatternDrawable(int color) {
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        setColor(color);
    }

    @Override
    public void draw(Canvas canvas) {
        if (mBitmap == null) {
            mBitmap = Bitmap.createBitmap(canvas.getWidth(), canvas.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas bitmapCanvas = new Canvas(mBitmap);
            renderOnCanvas(bitmapCanvas);
        }
        canvas.drawBitmap(mBitmap, 0, 0, null);
    }

    private void renderOnCanvas(Canvas canvas) {
        canvas.save();
        canvas.clipRect(getBounds());

        Color.colorToHSV(mColor, mTempHsv);
        scaleCanvasToBounds(canvas);

        // Draw the pattern by creating the paths, adjusting the colors and drawing them. The path
        // values are extracted from the SVG of the pattern file.

        mTempHsv[2] = 0.753f;
        Path p = mTempPath;
        p.reset();
        p.moveTo(1029.4f, 357.5f);
        p.lineTo(1366f, 759.1f);
        p.lineTo(1366f, 0f);
        p.lineTo(1137.7f, 0f);
        p.close();
        drawPath(canvas, p, mTempHsv);

        mTempHsv[2] = 0.78f;
        p.reset();
        p.moveTo(1138.1f, 0f);
        p.rLineTo(-144.8f, 768f);
        p.rLineTo(372.7f, 0f);
        p.rLineTo(0f, -524f);
        p.cubicTo(1290.7f, 121.6f, 1219.2f, 41.1f, 1178.7f, 0f);
        p.close();
        drawPath(canvas, p, mTempHsv);

        mTempHsv[2] = 0.804f;
        p.reset();
        p.moveTo(949.8f, 768f);
        p.rCubicTo(92.6f, -170.6f, 213f, -440.3f, 269.4f, -768f);
        p.lineTo(585f, 0f);
        p.rLineTo(2.1f, 766f);
        p.close();
        drawPath(canvas, p, mTempHsv);

        mTempHsv[2] = 0.827f;
        p.reset();
        p.moveTo(471.1f, 768f);
        p.rMoveTo(704.5f, 0f);
        p.cubicTo(1123.6f, 563.3f, 1027.4f, 275.2f, 856.2f, 0f);
        p.lineTo(476.4f, 0f);
        p.rLineTo(-5.3f, 768f);
        p.close();
        drawPath(canvas, p, mTempHsv);

        mTempHsv[2] = 0.867f;
        p.reset();
        p.moveTo(323.1f, 768f);
        p.moveTo(777.5f, 768f);
        p.cubicTo(661.9f, 348.8f, 427.2f, 21.4f, 401.2f, 25.4f);
        p.lineTo(323.1f, 768f);
        p.close();
        drawPath(canvas, p, mTempHsv);

        mTempHsv[2] = 0.894f;
        p.reset();
        p.moveTo(178.44286f, 766.85714f);
        p.lineTo(308.7f, 768f);
        p.cubicTo(381.7f, 604.6f, 481.6f, 344.3f, 562.2f, 0f);
        p.lineTo(0f, 0f);
        p.close();
        drawPath(canvas, p, mTempHsv);

        mTempHsv[2] = 0.929f;
        p.reset();
        p.moveTo(146f, 0f);
        p.lineTo(0f, 0f);
        p.lineTo(0f, 768f);
        p.lineTo(394.2f, 768f);
        p.cubicTo(327.7f, 475.3f, 228.5f, 201f, 146f, 0f);
        p.close();
        drawPath(canvas, p, mTempHsv);

        canvas.restore();
    }

    @VisibleForTesting
    public void scaleCanvasToBounds(Canvas canvas) {
        final Rect bounds = getBounds();
        final int height = bounds.height();
        final int width = bounds.width();

        float scaleY = height / VIEWBOX_HEIGHT;
        float scaleX = width / VIEWBOX_WIDTH;
        // First scale both sides to fit independently.
        canvas.scale(scaleX, scaleY);
        if (scaleY > scaleX) {
            // Adjust x-scale to maintain aspect ratio, but using (200, 0) as the pivot instead,
            // so that more of the texture and less of the blank space on the left edge is seen.
            canvas.scale(scaleY / scaleX, 1f, SCALE_FOCUS_X, 0f);
        } else {
            // Adjust y-scale to maintain aspect ratio, but using (0, 175) as the pivot instead,
            // so that an intersection of two "circles" can always be seen.
            canvas.scale(1f, scaleX / scaleY, 0f, SCALE_FOCUS_Y);
        }
    }

    private void drawPath(Canvas canvas, Path path, float[] hsv) {
        mPaint.setColor(Color.HSVToColor(hsv));
        canvas.drawPath(path, mPaint);
    }

    @Override
    public void setAlpha(int i) {
        // Ignore
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // Ignore
    }

    @Override
    public int getOpacity() {
        return 0;
    }

    public void setColor(int color) {
        mColor = color;
        mPaint.setColor(color);
        mBitmap = null;
        invalidateSelf();
    }

    public int getColor() {
        return mColor;
    }
}
