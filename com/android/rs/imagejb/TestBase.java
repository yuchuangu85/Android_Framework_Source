/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.rs.imagejb;

import android.app.Activity;
import android.content.Context;
import android.renderscript.RenderScript;
import android.renderscript.Allocation;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.view.View;
import android.util.Log;
import android.widget.Spinner;

public class TestBase  {
    protected final String TAG = "Img";

    protected RenderScript mRS;
    protected Allocation mInPixelsAllocation;
    protected Allocation mInPixelsAllocation2;
    protected Allocation mOutPixelsAllocation;
    protected ImageProcessingActivityJB act;

    // Override to use UI elements
    public void onBar1Changed(int progress) {
    }
    public void onBar2Changed(int progress) {
    }
    public void onBar3Changed(int progress) {
    }
    public void onBar4Changed(int progress) {
    }
    public void onBar5Changed(int progress) {
    }

    // Override to use UI elements
    // Unused bars will be hidden.
    public boolean onBar1Setup(SeekBar b, TextView t) {
        b.setVisibility(View.INVISIBLE);
        t.setVisibility(View.INVISIBLE);
        return false;
    }
    public boolean onBar2Setup(SeekBar b, TextView t) {
        b.setVisibility(View.INVISIBLE);
        t.setVisibility(View.INVISIBLE);
        return false;
    }
    public boolean onBar3Setup(SeekBar b, TextView t) {
        b.setVisibility(View.INVISIBLE);
        t.setVisibility(View.INVISIBLE);
        return false;
    }
    public boolean onBar4Setup(SeekBar b, TextView t) {
        b.setVisibility(View.INVISIBLE);
        t.setVisibility(View.INVISIBLE);
        return false;
    }
    public boolean onBar5Setup(SeekBar b, TextView t) {
        b.setVisibility(View.INVISIBLE);
        t.setVisibility(View.INVISIBLE);
        return false;
    }

    public void animateBars(float time) {
    }

    public boolean onSpinner1Setup(Spinner s) {
        s.setVisibility(View.INVISIBLE);
        return false;
    }

    public final void createBaseTest(ImageProcessingActivityJB ipact) {
        act = ipact;
        mRS = ipact.mProcessor.mRS;

        mInPixelsAllocation = ipact.mProcessor.mInPixelsAllocation;
        mInPixelsAllocation2 = ipact.mProcessor.mInPixelsAllocation2;
        mOutPixelsAllocation = ipact.mProcessor.mOutPixelsAllocation;

        createTest(act.getResources());
    }

    // Must override
    public void createTest(android.content.res.Resources res) {
    }

    // Must override
    public void runTest() {
    }

    public void destroy() {
    }
}
