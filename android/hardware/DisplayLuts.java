/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.hardware.flags.Flags;
import android.util.IntArray;

import java.util.ArrayList;

/**
 * DisplayLuts provides the developers to apply Lookup Tables (Luts) to a
 * {@link android.view.SurfaceControl}. Luts provides ways to control tonemapping
 * for specific content.
 *
 * The general flow for how DisplayLuts fits into a display pipeline is as follows:
 * <p>
 *      <img src="{@docRoot}reference/android/images/graphics/DisplayLuts.png" />
 *      <figcaption style="text-align: center;">DisplayLuts flow</figcaption>
 * </p>
 *
 * Or in list form:
 *
 * <ol>
 * <li> A {@link android.view.SurfaceControl}'s content is converted to RGB colors, if the source
 * is YUV.
 * <li> The RGB colors are de-gammed into linear values, normalized to the range of [0, 1].
 * <li> The linear values are passed through a 1D LUT (if set). The 1D LUT looks up and applies
 * gain multipliers.
 * <li> The linear values are passed through a 3D LUT (if set). The 3D LUT maps input colors to a
 * final linear color.
 * <li> The output values are transformed to the panel color gamut, and then gammed to arrive at
 * the final color.
 * </ol>
 *
 * <p>Importantly: the LUT is applied on colors in linear space, in the source content's color
 * gamut.
 *
 * @see LutProperties
 */
@FlaggedApi(Flags.FLAG_LUTS_API)
public final class DisplayLuts {
    private ArrayList<Entry> mEntries;
    private IntArray mOffsets;
    private int mTotalLength;

    /**
     * Create a {@link DisplayLuts} instance.
     */
    @FlaggedApi(Flags.FLAG_LUTS_API)
    public DisplayLuts() {
        mEntries = new ArrayList<>();
        mOffsets = new IntArray();
        mTotalLength = 0;
    }

    @FlaggedApi(Flags.FLAG_LUTS_API)
    public static class Entry {
        private float[] mBuffer;
        private @LutProperties.Dimension int mDimension;
        private int mSize;
        private @LutProperties.SamplingKey int mSamplingKey;

        private static final int LUT_LENGTH_LIMIT = 100000;

        /**
         * Create a Lut entry.
         *
         * <p> 1D Lut(s) are treated as gain curves. </p>
         * <p> 3D Lut(s) are used for direct color manipulations. </p>
         * <p> For 3D Lut(s), the values should be normalized to the range {@code 0.0}
         * to {@code 1.0}, inclusive. And {@code 1.0} is the maximum panel luminance.
         * And If N is the size of each dimension, the data is arranged in RGB order:
         * <pre>
         * R(0, 0, 0), R(0, 0, 1), ..., R(0, 0, N - 1),
         * R(0, 1, 0), ..., R(0, 1, N - 1), ..., R(0, N - 1, N - 1),
         * R(1, 0, 0), ..., R(1, 0, N - 1), ..., R(1, N - 1, N - 1), ..., R(N - 1, N - 1, N - 1),
         * G(0, 0, 0), ..., G(N - 1, N - 1, N - 1),
         * B(0, 0, 0), ..., B(N - 1, N - 1, N - 1)</pre>
         * When a GPU shader samples 3D Lut data, it's accessed in a flat,
         * one-dimensional arrangement. Assuming that we have a 3D array
         * {@code ORIGINAL[N][N][N]}, then
         * <pre>
         * FLAT[z + N * (y + N * x)] = ORIGINAL[x][y][z]</pre>
         * </p>
         *
         * @param buffer The raw lut data
         * @param dimension Either 1D or 3D
         * @param samplingKey The sampling kay used for the Lut
         */
        @FlaggedApi(Flags.FLAG_LUTS_API)
        public Entry(@NonNull float[] buffer,
                    @LutProperties.Dimension int dimension,
                    @LutProperties.SamplingKey int samplingKey) {
            if (buffer == null || buffer.length < 1) {
                throw new IllegalArgumentException("The buffer cannot be empty!");
            }

            if (buffer.length >= LUT_LENGTH_LIMIT) {
                throw new IllegalArgumentException("The lut length is too big to handle!");
            }

            if (dimension != LutProperties.ONE_DIMENSION
                    && dimension != LutProperties.THREE_DIMENSION) {
                throw new IllegalArgumentException("The dimension should be either 1D or 3D!");
            }

            if (dimension == LutProperties.THREE_DIMENSION) {
                if (buffer.length <= 3) {
                    throw new IllegalArgumentException(
                            "The 3d lut size of each dimension should be over 1!");
                }
                int lengthPerChannel = buffer.length;
                if (lengthPerChannel % 3 != 0) {
                    throw new IllegalArgumentException(
                            "The lut buffer of 3dlut should have 3 channels!");
                }
                lengthPerChannel /= 3;

                double size = Math.cbrt(lengthPerChannel);
                if (size == (int) size) {
                    mSize = (int) size;
                } else {
                    throw new IllegalArgumentException(
                            "Cannot get the cube root of the 3d lut buffer!");
                }
            } else {
                mSize = buffer.length;
            }

            mBuffer = buffer;
            mDimension = dimension;
            mSamplingKey = samplingKey;
        }

        /**
         * @return the dimension of the lut entry
         */
        @FlaggedApi(Flags.FLAG_LUTS_API)
        public int getDimension() {
            return mDimension;
        }

        /**
         * @return the size of the lut for each dimension
         * @hide
         */
        public int getSize() {
            return mSize;
        }

        /**
         * @return the lut raw data of the lut
         */
        @FlaggedApi(Flags.FLAG_LUTS_API)
        public @NonNull float[] getBuffer() {
            return mBuffer;
        }

        /**
         * @return the sampling key used by the lut
         */
        @FlaggedApi(Flags.FLAG_LUTS_API)
        public int getSamplingKey() {
            return mSamplingKey;
        }

        @Override
        public String toString() {
            return "Entry{"
                    + "dimension=" + DisplayLuts.Entry.dimensionToString(getDimension())
                    + ", size(each dimension)=" + getSize()
                    + ", samplingKey=" + samplingKeyToString(getSamplingKey()) + "}";
        }

        private static String dimensionToString(int dimension) {
            switch(dimension) {
                case LutProperties.ONE_DIMENSION:
                    return "ONE_DIMENSION";
                case LutProperties.THREE_DIMENSION:
                    return "THREE_DIMENSION";
                default:
                    return "";
            }
        }

        private static String samplingKeyToString(int key) {
            switch(key) {
                case LutProperties.SAMPLING_KEY_RGB:
                    return "SAMPLING_KEY_RGB";
                case LutProperties.SAMPLING_KEY_MAX_RGB:
                    return "SAMPLING_KEY_MAX_RGB";
                case LutProperties.SAMPLING_KEY_CIE_Y:
                    return "SAMPLING_KEY_CIE_Y";
                default:
                    return "";
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DisplayLuts{");
        sb.append("\n");
        for (DisplayLuts.Entry entry: mEntries) {
            sb.append(entry.toString());
            sb.append("\n");
        }
        sb.append("}");
        return sb.toString();
    }

    private void addEntry(Entry entry) {
        mEntries.add(entry);
        mOffsets.add(mTotalLength);
        mTotalLength += entry.getBuffer().length;
    }

    private void clear() {
        mOffsets.clear();
        mTotalLength = 0;
        mEntries.clear();
    }

    /**
     * Set a Lut to be applied.
     *
     * <p>Use either this or {@link #set(Entry, Entry)}. The function will
     * replace any previously set lut(s).</p>
     *
     * @param entry Either an 1D Lut or a 3D Lut
     */
    @FlaggedApi(Flags.FLAG_LUTS_API)
    public void set(@NonNull Entry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("The entry is null!");
        }
        clear();
        addEntry(entry);
    }

    /**
     * Set Luts in order to be applied.
     *
     * <p> An 1D Lut and 3D Lut will be applied in order. Use either this or
     * {@link #set(Entry)}. The function will replace any previously set lut(s)</p>
     *
     * @param first An 1D Lut
     * @param second A 3D Lut
     */
    @FlaggedApi(Flags.FLAG_LUTS_API)
    public void set(@NonNull Entry first, @NonNull Entry second) {
        if (first == null || second == null) {
            throw new IllegalArgumentException("The entry is null!");
        }
        if (first.getDimension() != LutProperties.ONE_DIMENSION
                || second.getDimension() != LutProperties.THREE_DIMENSION) {
            throw new IllegalArgumentException("The entries should be 1D and 3D in order!");
        }
        clear();
        addEntry(first);
        addEntry(second);
    }

    /**
     * @hide
     */
    public boolean valid() {
        return mEntries.size() > 0;
    }

    /**
     * @hide
     */
    public float[] getLutBuffers() {
        float[] buffer = new float[mTotalLength];

        for (int i = 0; i < mEntries.size(); i++) {
            float[] lutBuffer = mEntries.get(i).getBuffer();
            System.arraycopy(lutBuffer, 0, buffer, mOffsets.get(i), lutBuffer.length);
        }
        return buffer;
    }

    /**
     * @hide
     */
    public int[] getOffsets() {
        return mOffsets.toArray();
    }

    /**
     * @hide
     */
    public int[] getLutSizes() {
        int[] sizes = new int[mEntries.size()];
        for (int i = 0; i < mEntries.size(); i++) {
            sizes[i] = mEntries.get(i).getSize();
        }
        return sizes;
    }

    /**
     * @hide
     */
    public int[] getLutDimensions() {
        int[] dimensions = new int[mEntries.size()];
        for (int i = 0; i < mEntries.size(); i++) {
            dimensions[i] = mEntries.get(i).getDimension();
        }
        return dimensions;
    }

    /**
     * @hide
     */
    public int[] getLutSamplingKeys() {
        int[] samplingKeys = new int[mEntries.size()];
        for (int i = 0; i < mEntries.size(); i++) {
            samplingKeys[i] = mEntries.get(i).getSamplingKey();
        }
        return samplingKeys;
    }
}
