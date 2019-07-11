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

package com.example.android.rs.vr.engine;

import android.renderscript.Matrix3f;
import android.renderscript.Matrix4f;
import android.renderscript.Script;
import android.renderscript.ScriptIntrinsicResize;
import android.util.Log;

import java.text.DecimalFormat;

public class VrPipline1 extends BasicPipeline {
    private static final String LOGTAG = "VrPipline1";

    float[] mMatrixBuffer = new float[16];
    ScriptC_vr scriptC_vr;
    ScriptIntrinsicResize script_resize;
    Script.LaunchOptions options = new Script.LaunchOptions();

    @Override
    public void initBuffers(VrState state) {
        super.initBuffers(state);
    }

    static DecimalFormat df = new DecimalFormat("      ##0.000");

    private static String trim(double d) {
        String s = df.format(d);
        return s.substring(s.length() - 6);
    }

    private static String trim(float[] d) {
        String ret = "";
        for (int i = 0; i < d.length; i++) {
            ret += ((i == 0) ? "[ " : " , ") + trim(d[i]);
        }
        return ret + ("]");
    }

    private void creatOpacityAllocation(VrState state) {
        scriptC_vr.set_opacity(state.mMaterial.getOpacityAllocation(state.mRs));
    }

    private void creatColorMapAllocation(VrState state) {
        scriptC_vr.set_color_map(state.mMaterial.getColorMapAllocation(state.mRs));
    }

    @Override
    public void setupTriangles(VrState state) {
        super.setupTriangles(state);
        if (mCancel){
            return;
        }
        Matrix m = state.mTransform.getMatrix(Transform.SCREEN_SPACE, Transform.VOLUME_SPACE);
        m.getAsFloats(mMatrixBuffer);
        Matrix4f matrix4f = new Matrix4f(mMatrixBuffer);
        if (scriptC_vr == null) {
            scriptC_vr = new ScriptC_vr(state.mRs);
        }
        if (script_resize == null) {
            script_resize = ScriptIntrinsicResize.create(state.mRs);
        }
        scriptC_vr.set_matrix4(matrix4f);
        for (int i = 0; i < 9; i++) {
            int x = i % 3;
            int y = i / 3;
            mMatrixBuffer[i] = mMatrixBuffer[x + y * 4];
        }
        Matrix3f matrix3f = new Matrix3f(mMatrixBuffer);
        scriptC_vr.set_matrix3(matrix3f);
        creatColorMapAllocation(state);
        creatOpacityAllocation(state);
        scriptC_vr.invoke_setup_vectors();
    }

    @Override
    public void raycast(VrState state) {
        if (mCancel){
            return;
        }
        scriptC_vr.set_volume(state.mVolume.mVolumeAllocation);
        scriptC_vr.set_bricks(state.mRsMask.mBrick_allocation);
        scriptC_vr.set_brick_dimx(state.mRsMask.m_bricks_dimx);
        scriptC_vr.set_brick_dimy(state.mRsMask.m_bricks_dimy);
        if (mCancel){
            return;
        }
        scriptC_vr.set_zbuff(state.mzRangeFullAllocation);
        if (mCancel){
            return;
        }
        if (state.mImgWidth*state.mImgHeight < 512*512) {
            scriptC_vr.forEach_draw_z_buffer(state.mzRangeFullAllocation, state.mScrAllocation);
        } else {
            int blocks = state.mImgWidth*state.mImgHeight/(256*256);
            for (int i = 0; i < blocks; i++) {
                options.setX(0,state.mImgWidth);
                options.setY(i*state.mImgHeight/blocks, (i+1)*state.mImgHeight/blocks);
                scriptC_vr.forEach_draw_z_buffer(state.mzRangeFullAllocation, state.mScrAllocation, options);
                state.mRs.finish();
                if (mCancel){
                    Log.v(LOGTAG, "cancel");
                    return;
                }
            }


        }

    }

}
