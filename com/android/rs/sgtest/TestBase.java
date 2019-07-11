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

package com.android.rs.sgtest;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.renderscript.ScriptC;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Script;
import android.view.SurfaceView;
import android.view.SurfaceHolder;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.view.View;
import android.util.Log;
import java.lang.Math;
import android.widget.Spinner;

public class TestBase  {
    protected final String TAG = "Img";

    protected RenderScript mRS;
    protected Allocation mInPixelsAllocation;
  // protected Allocation mInPixelsAllocation2;
    protected Allocation mOutPixelsAllocation;
    protected ScriptGroupTestActivity act;

    private class MessageProcessor extends RenderScript.RSMessageHandler {
        ScriptGroupTestActivity mAct;

        MessageProcessor(ScriptGroupTestActivity act) {
            mAct = act;
        }

        public void run() {
            mAct.updateDisplay();
        }
    }

    public boolean onSpinnerSetup(Spinner s) {
        s.setVisibility(View.INVISIBLE);
        return false;
    }

    public final void createBaseTest(ScriptGroupTestActivity ipact) {
        act = ipact;
        mRS = ipact.mRS;
        mRS.setMessageHandler(new MessageProcessor(act));

        mInPixelsAllocation = ipact.mInPixelsAllocation;
        // mInPixelsAllocation2 = ipact.mInPixelsAllocation2;
        mOutPixelsAllocation = ipact.mOutPixelsAllocation;

        createTest(act.getResources());
    }

    // Must override
    public void createTest(android.content.res.Resources res) {
    }

    // Must override
    public void runTest() {
    }

    final public void runTestSendMessage() {
        runTest();
        mRS.sendMessage(0, null);
    }

    public void finish() {
        mRS.finish();
    }

    public void destroy() {
        mRS.setMessageHandler(null);
    }

    public void updateBitmap(Bitmap b) {
        mOutPixelsAllocation.copyTo(b);
    }

    // Override to configure specific benchmark config.
    public void setupBenchmark() {
    }

    // Override to reset after benchmark.
    public void exitBenchmark() {
    }
}
