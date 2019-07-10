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

import android.util.Log;

/**
 * Base implementation of a rendering pipeline Simply renders a box
 */
public class BasicPipeline implements Pipeline {
    boolean mCancel = false;
    private static final String LOGTAG = "BasicPipeline";
    ScriptC_rasterize scriptC_rasterize;

    @Override
    public void cancel() {
        mCancel = true;
    }

    @Override
    public boolean isCancel() {
        return mCancel;
    }

    @Override
    public void initBuffers(VrState state) {
        mCancel = false;
    }

    @Override
    public void setupTriangles(VrState state) {
        Matrix m = state.mTransform.getMatrix(Transform.VOLUME_SPACE, Transform.SCREEN_SPACE);
        state.mCubeVolume.transform(m, state.mCubeScreen);
    }

    @Override
    public void rasterizeTriangles(VrState state) {
        if (scriptC_rasterize == null) {
            scriptC_rasterize = new ScriptC_rasterize(state.mRs);
        }
        scriptC_rasterize.set_index(state.mCubeScreen.mIndex);
        scriptC_rasterize.set_vert(state.mCubeScreen.mVert);
        long start = System.nanoTime();
        scriptC_rasterize.invoke_setup_triangles(state.mImgWidth, state.mImgHeight);
        if (mCancel) return;
        scriptC_rasterize.forEach_render_z(state.mzRangeFullAllocation);
        state.mRs.finish();
        Log.v(LOGTAG,"render triangles "+((System.nanoTime()-start)/1E6f)+" ms");
    }

    @Override
    public void raycast(VrState state) {
        scriptC_rasterize.set_z_range_buff(state.mzRangeFullAllocation);
        scriptC_rasterize.forEach_draw_z_buffer(state.mzRangeFullAllocation, state.mScrAllocation);
    }
}
