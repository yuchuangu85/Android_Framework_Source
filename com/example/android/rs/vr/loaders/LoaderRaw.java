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

import com.example.android.rs.vr.engine.ScriptC_bricked;
import com.example.android.rs.vr.engine.Volume;

import java.io.File;
import java.io.FileInputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Properties;
import java.util.Vector;

/**
 * Created by hoford on 2/2/15.
 */
public class LoaderRaw {
    private static final String LOGTAG = "RawLoader";

    /**
     * This builds the volume based on a collection of raw image files
     * @param rs The Renderscript context
     * @param dir The directory containing the raw images
     * @param prop property object containing information about the files
     * @param listener To provide feedback
     * @return The created volume
     */
    public static Volume buildRSVolume(final RenderScript rs, File dir, Properties prop,
                                       final VolumeLoader.ProgressListener listener) {
        String[] dim = prop.getProperty("dim").split("x");
        Volume v = new Volume();
        v.mDimx = Integer.parseInt(dim[0]);
        v.mDimy = Integer.parseInt(dim[1]);
        v.mDimz = Integer.parseInt(dim[2]);
        String[] voxeldim = prop.getProperty("voxeldim").split(",");
        v.mVoxelDim[0] = Float.parseFloat(voxeldim[0]);
        v.mVoxelDim[1] = Float.parseFloat(voxeldim[1]);
        v.mVoxelDim[2] = Float.parseFloat(voxeldim[2]);
        Float min = Math.min(v.mVoxelDim[0], Math.min(v.mVoxelDim[1], v.mVoxelDim[2]));
        v.mVoxelDim[0] /= min;
        v.mVoxelDim[1] /= min;
        v.mVoxelDim[2] /= min;
        listener.progress(0, v.mDimz);
        if (v.mDimz < 20) {
            return null;
        }
        Log.v(LOGTAG, "Loading " + dir.getPath());
        File[] f = dir.listFiles();
        Log.v(LOGTAG, "dir contains " + f.length + " files");
        Arrays.sort(f, new Comparator<File>() {

            @Override
            public int compare(File o1, File o2) {

                return Integer.decode(o1.getName()).compareTo(Integer.decode(o2.getName()));
            }
        });

        int count = 0;


        final Vector<File> toRun = new Vector<File>();
        final HashMap<File, Integer> fileMap = new HashMap<File, Integer>();
        for (int i = 0; i < f.length; i++) {
            if (f[i].isDirectory()) {
                continue;
            }

            toRun.add(f[i]);
            fileMap.put(f[i], count);
            count++;
        }

        v.mDimz = count;
        if (listener != null) {
            listener.progress(0, v.mDimz);
        }

        v.mVolumeAllocation = null;
        Allocation alloc_slice = null;
        ScriptC_bricked scriptC_bricked = new ScriptC_bricked(rs);
        FileInputStream inputStream;
        String pixel_spacing = null;
        String slice1_pos = null;
        String slice2_pos = null;
        boolean slice_spacing_set = false;
        int z = 0;
        for (File file : toRun) {
            try {
                inputStream = new FileInputStream(file);
                MappedByteBuffer mbb = inputStream.getChannel().map(FileChannel.MapMode.READ_ONLY,
                        0, v.mDimy * v.mDimx * 2);
                short[] slice = new short[v.mDimy * v.mDimx];
                mbb.asShortBuffer().get(slice);
                inputStream.close();
                mbb = null;
                if (v.mVolumeAllocation == null) {
                    Log.v(LOGTAG, "make Volume " + z);
                    Type.Builder b = new Type.Builder(rs, android.renderscript.Element.I16(rs));
                    b.setX(v.mDimx).setY(v.mDimy);
                    alloc_slice = Allocation.createTyped(rs, b.create(), Allocation.USAGE_SCRIPT);
                    b.setZ(v.mDimz);
                    v.mVolumeAllocation = Allocation.createTyped(rs,
                            b.create(), Allocation.USAGE_SCRIPT);
                    scriptC_bricked.set_volume(v.mVolumeAllocation);

                }
                Log.v(LOGTAG, "LOAD SLICE " + z);
                int size = v.mDimy * v.mDimx;
                alloc_slice.copyFromUnchecked(slice);
                scriptC_bricked.set_z(z);
                scriptC_bricked.forEach_copy(alloc_slice);
                z++;
                if (listener != null) {
                    listener.progress(z, v.mDimz);
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        rs.finish();
        alloc_slice.destroy();
        Log.v(LOGTAG,"LOADING DONE ....");

        scriptC_bricked.destroy();
        return v;
    }
}
