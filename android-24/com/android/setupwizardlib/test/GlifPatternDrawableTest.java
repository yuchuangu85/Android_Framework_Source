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

package com.android.setupwizardlib.test;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.setupwizardlib.GlifPatternDrawable;

public class GlifPatternDrawableTest extends AndroidTestCase {

    @SmallTest
    public void testDraw() {
        final Bitmap bitmap = Bitmap.createBitmap(1366, 768, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);

        final GlifPatternDrawable drawable = new GlifPatternDrawable(Color.RED);
        drawable.setBounds(0, 0, 1366, 768);
        drawable.draw(canvas);

        assertEquals("Top left pixel should be #ed0000", 0xffed0000, bitmap.getPixel(0, 0));
        assertEquals("Center pixel should be #d30000", 0xffd30000, bitmap.getPixel(683, 384));
        assertEquals("Bottom right pixel should be #c70000", 0xffc70000,
                bitmap.getPixel(1365, 767));
    }

    @SmallTest
    public void testDrawTwice() {
        // Test that the second time the drawable is drawn is also correct, to make sure caching is
        // done correctly.

        final Bitmap bitmap = Bitmap.createBitmap(1366, 768, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);

        final GlifPatternDrawable drawable = new GlifPatternDrawable(Color.RED);
        drawable.setBounds(0, 0, 1366, 768);
        drawable.draw(canvas);

        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        canvas.drawRect(0, 0, 1366, 768, paint);  // Erase the entire canvas

        drawable.draw(canvas);

        assertEquals("Top left pixel should be #ed0000", 0xffed0000, bitmap.getPixel(0, 0));
        assertEquals("Center pixel should be #d30000", 0xffd30000, bitmap.getPixel(683, 384));
        assertEquals("Bottom right pixel should be #c70000", 0xffc70000,
                bitmap.getPixel(1365, 767));
    }

    @SmallTest
    public void testScaleToCanvasSquare() {
        final Canvas canvas = new Canvas();
        Matrix expected = new Matrix(canvas.getMatrix());

        final GlifPatternDrawable drawable = new GlifPatternDrawable(Color.RED);
        drawable.setBounds(0, 0, 683, 384);  // half each side of the view box
        drawable.scaleCanvasToBounds(canvas);

        expected.postScale(0.5f, 0.5f);

        assertEquals("Matrices should match", expected, canvas.getMatrix());
    }

    @SmallTest
    public void testScaleToCanvasTall() {
        final Canvas canvas = new Canvas();
        final Matrix expected = new Matrix(canvas.getMatrix());

        final GlifPatternDrawable drawable = new GlifPatternDrawable(Color.RED);
        drawable.setBounds(0, 0, 683, 768);  // half the width only
        drawable.scaleCanvasToBounds(canvas);

        expected.postScale(1f, 1f);
        expected.postTranslate(-100f, 0f);

        assertEquals("Matrices should match", expected, canvas.getMatrix());
    }

    @SmallTest
    public void testScaleToCanvasWide() {
        final Canvas canvas = new Canvas();
        final Matrix expected = new Matrix(canvas.getMatrix());

        final GlifPatternDrawable drawable = new GlifPatternDrawable(Color.RED);
        drawable.setBounds(0, 0, 1366, 384);  // half the height only
        drawable.scaleCanvasToBounds(canvas);

        expected.postScale(1f, 1f);
        expected.postTranslate(0f, -87.5f);

        assertEquals("Matrices should match", expected, canvas.getMatrix());
    }
}
