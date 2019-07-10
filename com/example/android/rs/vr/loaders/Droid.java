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

package com.example.android.rs.vr.loaders;

import android.renderscript.Allocation;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.util.Log;

import com.example.android.rs.vr.engine.ScriptC_bugdroid;
import com.example.android.rs.vr.engine.Volume;

/**
 * Provides a simple an example of a computed data set and allows the application to
 * be run without any data sets.
 */
public class Droid {
    private static final String LOGTAG = "RawLoader";
    private static final String simpleLook = "simple";
    private static final int[][] simpleOpacity = {{120, 0x0}, {150, 0xFF}};
    private static final int[][] simpleColor = {
            {144, 0xA4C639, 10, 80, 0},
            {155, 0xA4C639, 10, 80, 0},
            {200, 0x5555CC, 10, 80, 0},
            {300, 0xAA5555, 40, 60, 0},
            {255, 0xAAAAAA, 10, 80, 0}};

    private static final String internalLook = "internal";
    private static final int[][] internalOpacity = {{300, 0x0}, {400, 0xFF}};
    private static final int[][] internalColor = {
            {200, 0x44AA44, 70, 30, 30},
            {230, 0xAA44AA, 70, 30, 20},
            {300, 0xAA5555, 70, 30, 20},
            {400, 0xAAAAAA, 70, 30, 20}};
    private static final String tranlLook = "translucent";
    private static final int[][] tranOpacity = {{110, 0x0},{140, 0x13},{143, 0x0}, {400, 0xFF}};
    private static final int[][] tranColor = {
            {144, 0xA4C639, 70, 30, 0},
            {230, 0xAA44AA, 70, 30, 0},
            {300, 0xAA5555, 70, 30, 20},
            {400, 0xAAAAAA, 70, 30, 20}};

    private static final int SIZE = 256;
    public static final String NAME = "A Droid";

    public static Volume buildRSVolume(RenderScript rs,
                                       final VolumeLoader.ProgressListener listener) {
        ScriptC_bugdroid scriptC_bricked = new ScriptC_bugdroid(rs);

        Volume v = new Volume();
        v.mDimx = v.mDimy = v.mDimz = SIZE;
        v.mVoxelDim[0] = v.mVoxelDim[1] = v.mVoxelDim[2] = 1.f;

        v.addLook(internalLook, internalColor, internalOpacity);
        v.addLook(tranlLook, tranColor, tranOpacity);
        v.addLook(simpleLook, simpleColor, simpleOpacity);

        Type.Builder b = new Type.Builder(rs, android.renderscript.Element.I16(rs));
        b.setX(v.mDimx).setY(v.mDimy);
        Allocation tmp = Allocation.createTyped(rs, b.create(), Allocation.USAGE_SCRIPT);
        b.setZ(v.mDimz);
        b.setX(v.mDimx).setY(v.mDimy).setZ(v.mDimz);
        v.mVolumeAllocation = Allocation.createTyped(rs, b.create(), Allocation.USAGE_SCRIPT);

        scriptC_bricked.set_volume(v.mVolumeAllocation);
        scriptC_bricked.set_size(SIZE);
        long time = System.nanoTime();
        for (int z = 0; z < v.mDimz; z++) {
            scriptC_bricked.set_z(z);
            scriptC_bricked.forEach_andy(tmp);
            scriptC_bricked.forEach_copy(tmp);
            rs.finish();
            listener.progress(z, v.mDimz);
        }

        Log.v(LOGTAG, "compute Droid in" + ((System.nanoTime() - time) / 1E9f) + "seconds");
        tmp.destroy();
        scriptC_bricked.destroy();
        return v;
    }

}
