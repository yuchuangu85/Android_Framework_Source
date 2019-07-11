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

import android.renderscript.Allocation;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.util.Log;

/**
 * create bricked binary representation of the non transparent voxels
 */
public class RsBrickedBitMask {
    private static final String LOGTAG = "BrickedBitMask";
    ScriptC_bricked scriptC_bricked;

    int mDimX;
    int mDimY;
    int mDimZ;
    int m_bricks_dimx;
    int m_bricks_dimy;
    int m_bricks_dimz;
    Volume mVolume;
    int mBrickCnt = 0;

    Allocation mBrick_allocation;

    public final static int BSIZE = 32;

    public static final byte TYPE_BYTE = 1;
    public static final byte TYPE_SHORT = 1;
    public static final byte TYPE_INT = 2;

    public RsBrickedBitMask(VrState state) {

        mVolume = state.mVolume;
        mDimX = mVolume.mDimx;
        mDimY = mVolume.mDimy;
        mDimZ = mVolume.mDimz;
        m_bricks_dimx = (mDimX + 31) / 32;
        m_bricks_dimy = (mDimY + 31) / 32;
        m_bricks_dimz = (mDimZ + 31) / 32;
        int maxBrick = m_bricks_dimx * m_bricks_dimy * m_bricks_dimz;
        int size = maxBrick * 32 * 32; // divide by 4 because we will try U32_4

        Type.Builder b = new Type.Builder(state.mRs, android.renderscript.Element.U32(state.mRs));
        b.setX(size);
        mBrick_allocation = Allocation.createTyped(state.mRs, b.create(), Allocation.USAGE_SCRIPT);

        scriptC_bricked = new ScriptC_bricked(state.mRs);

        scriptC_bricked.set_volume(mVolume.mVolumeAllocation);
        scriptC_bricked.set_brick_dimx(m_bricks_dimx);
        scriptC_bricked.set_brick_dimy(m_bricks_dimy);
        scriptC_bricked.set_brick_dimz(m_bricks_dimz);
        scriptC_bricked.set_opacity(state.mMaterial.getOpacityAllocation(state.mRs));
        state.mRs.finish();

        scriptC_bricked.forEach_pack_chunk(mBrick_allocation);

        Allocation tmp = Allocation.createTyped(state.mRs, b.create(), Allocation.USAGE_SCRIPT);
        scriptC_bricked.set_bricks(mBrick_allocation);
        scriptC_bricked.forEach_dilate(mBrick_allocation, tmp);

        mBrick_allocation.destroy();
        mBrick_allocation = tmp;
    }

    Allocation createChunkAllocation(RenderScript rs) {
        Type.Builder b = new Type.Builder(rs, android.renderscript.Element.U32(rs));
        b.setX(mDimX / 8);
        b.setY(mDimY);
        b.setZ(32);
        return Allocation.createTyped(rs, b.create(), Allocation.USAGE_SCRIPT);
    }

    Allocation createBitChunkAllocation(RenderScript rs) {
        Type.Builder b = new Type.Builder(rs, android.renderscript.Element.U8(rs));
        b.setX(mDimX / 8);
        b.setY(mDimY);
        b.setZ(32);
        return Allocation.createTyped(rs, b.create(), Allocation.USAGE_SCRIPT);
    }
}
