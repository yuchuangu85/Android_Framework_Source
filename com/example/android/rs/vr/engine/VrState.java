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

import android.graphics.SurfaceTexture;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.util.Log;
import android.view.Surface;

public class VrState {
    private static final String LOGTAG = "VrState";
    public RenderScript mRs;
    public Volume mVolume;
    public RsBrickedBitMask mRsMask;
    public Material mMaterial = new Material();
    public Cube mCubeVolume;
    public TriData mCubeScreen;
    public int mImgWidth;
    public int mImgHeight;
    Allocation mzRangeFullAllocation;
    public Allocation mScrAllocation; // the RGB data out
    public Transform mTransform = new Transform();

    public void clone(VrState src) {
        mRs = src.mRs;
        mVolume = src.mVolume;
        mRsMask = src.mRsMask;
        mMaterial = src.mMaterial;
        if (mCubeVolume == null) {
            mCubeVolume = new Cube();
        }
        mCubeVolume.clone(src.mCubeVolume);
        mCubeScreen = new TriData(src.mCubeScreen);
        mImgWidth = src.mImgWidth;
        mImgHeight = src.mImgHeight;
        mzRangeFullAllocation = src.mzRangeFullAllocation;
        mScrAllocation = src.mScrAllocation;
        mTransform.clone(src.mTransform);
    }

    public void createOutputAllocation(Surface surface, int w, int h) {

        if (mRs == null) {
            return;
        }

        if (mScrAllocation == null
                || mScrAllocation.getType().getX() != w
                || mScrAllocation.getType().getY() != h) {
            if (mScrAllocation != null) {
                mScrAllocation.destroy();
                mScrAllocation = null;
                Log.v(LOGTAG, " destroy mScrAllocation");
            }
            Type.Builder b = new Type.Builder(mRs, Element.RGBA_8888(mRs));
            b.setX(w);
            b.setY(h);

            mScrAllocation = Allocation.createTyped(mRs, b.create(),
                    Allocation.USAGE_IO_OUTPUT | Allocation.USAGE_SCRIPT);

        }
        mScrAllocation.setSurface(surface);

        if (mzRangeFullAllocation == null
                || mzRangeFullAllocation.getType().getX() != w
                || mzRangeFullAllocation.getType().getY() != h) {
            if (mzRangeFullAllocation != null) {
                mzRangeFullAllocation.destroy();
                mzRangeFullAllocation = null;
                Log.v(LOGTAG, " destroy mzRangeFullAllocation");

            }
            Type.Builder b = new Type.Builder(mRs, Element.F32_2(mRs));
            b.setX(w);
            b.setY(h);

            mzRangeFullAllocation = Allocation.createTyped(mRs, b.create());
        }

        mImgWidth = w;
        mImgHeight = h;
        mTransform.setScreenDim(w, h);
    }

    public void copyData(VrState src) {
        mRs = src.mRs;
        mVolume = src.mVolume;
        mRsMask = src.mRsMask;
        mMaterial = src.mMaterial;
        if (mCubeVolume == null) {
            mCubeVolume = new Cube();
        }
        mCubeVolume.clone(src.mCubeVolume);
        mCubeScreen = new TriData(src.mCubeScreen); // TODO I should not have to do new each time
        mImgWidth = src.mImgWidth;
        mImgHeight = src.mImgHeight;
        mTransform.clone(src.mTransform);

    }

    public void destroyScreenAllocation() {
        Log.v(LOGTAG, "destroyScreenAllocation");
        if (mScrAllocation != null) {
            mScrAllocation.destroy();
            mScrAllocation = null;
        }
    }
}
