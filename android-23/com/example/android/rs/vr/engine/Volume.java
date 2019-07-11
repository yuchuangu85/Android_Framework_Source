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
import android.util.Log;

import java.util.Arrays;
import java.util.HashMap;

/**
 * Defines a simple volume to be used in the volume renderer
 */
public class Volume {
    private static final String LOGTAG = "Volume";
    public short[][] mData;
    public Allocation mVolumeAllocation; // one big volume
    public int mDimz = -1;
    public int mDimy = -1;
    public int mDimx = -1;
    public float[] mVoxelDim = new float[]{1f, 1f, 1f};
    private HashMap<String, Look> mLooks = new HashMap<String, Look>();

    @Override
    public String toString() {
        String ret = "Volume[" + mDimx + "," + mDimy + "," + mDimz + "]";
        ret += "(" + mVoxelDim[0] + ", " + mVoxelDim[1] + ", " + mVoxelDim[2] + ")";

        return ret;
    }

    public String[] getLookNames() {
        return mLooks.keySet().toArray(new String[mLooks.size()]);
    }

    public int[][] getLookColor(String name) {
        return mLooks.get(name).mColor;
    }

    public int[][] getLookOpactiy(String name) {
        return mLooks.get(name).mOpacity;
    }

    public void addLook(String name, int[][] color,  int[][] opacity) {
        mLooks.put(name, new Look(name, color, opacity));
    }

    public void addLook(String name, String color_string, String opacity_string) {
        mLooks.put(name, new Look(name, color_string, opacity_string));
        Look l = mLooks.get(name);
        Log.v(LOGTAG, " ========================== " + name + " =============================");
        Log.v(LOGTAG, "mColor "+l.dblArrayToString(l.mColor));
        Log.v(LOGTAG, "mOpacity "+l.dblArrayToString(l.mOpacity));
    }

    class Look {
        int[][] mColor;
        int[][] mOpacity;
        String mName;

        public Look(String name, String color_string, String opacity_string) {
            mName = name;
            String[] colorSplit = color_string.split("\\}\\s*\\,\\s*\\{");
            String[] opacitySplit = opacity_string.split("\\}\\s*\\,\\s*\\{");
            mColor = new int[colorSplit.length][];
            for (int i = 0; i < colorSplit.length; i++) {

                mColor[i] = readNumbers(colorSplit[i]);
            }
            mOpacity = new int[opacitySplit.length][];
            for (int i = 0; i < opacitySplit.length; i++) {

                mOpacity[i] = readNumbers(opacitySplit[i]);
            }
        }

        public Look(String name, int[][] color, int[][] opacity) {
            mColor = color;
            mOpacity = opacity;
            mName =name;
        }

        private int[] readNumbers(String numList) {
            numList = numList.replace('{', ' ');
            numList = numList.replace('}', ' ');
            numList = numList.replace(';', ' ');
            String[] split = numList.split(",");
            int[] ret = new int[split.length];
            for (int i = 0; i < ret.length; i++) {
                ret[i] = Integer.decode(split[i].trim());
            }
            return ret;
        }

        private String dblArrayToString(int[][] v) {
            String s = "";
            for (int i = 0; i < v.length; i++) {
                if (i > 0) {
                    s += ",";
                }
                s += Arrays.toString(v[i]);
            }
            return s;
        }

        public String toString() {
            return "mColor=" + dblArrayToString(mColor) + "\nmOpacity=" + dblArrayToString(mOpacity);
        }
    }
}
