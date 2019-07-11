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
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Vector;

/**
 * The simplest possible DICOM Reader.
 * Will only read raw 16 bit dicom slices (the most common type)
 * If the volume is compressed (usually JPEG2000) you need a decompression tool
 *
 * Note: All constants 0xNNNN, 0xNNNN are DICOM TAGS
 * (see online documentation of DICOM standard)
 */
public class LoaderDicom {
    private static final String LOGTAG = "ReadDicom";
    String mName;
    final boolean dbg = false;
    private ByteOrder mByteOrder;
    boolean explicit = true;
    MappedByteBuffer mMappedByteBuffer;
    long mFileLen;
    private static final int MIN_VOLUME_SIZE = 20;
    class Element {
        int mGroup;
        int mElement;
        short mVR;
        long mLength;
        Object mValue;

        @Override
        public String toString() {
            byte[] vrs = new byte[]{(byte) (mVR & 0xFF), (byte) ((mVR >> 8) & 0xFF)};
            return Integer.toHexString(mGroup) + "," +
                    Integer.toHexString(mElement) + "(" +
                    new String(vrs) + ") [" + mLength + "] ";
        }
    }

    static short vr(String v) {
        byte[] b = v.getBytes();
        return (short) (((b[1] & 0xFF) << 8) | (b[0] & 0xFF));
    }

    static final short OB = vr("OB");
    static final short OW = vr("OW");
    static final short OF = vr("OF");
    static final short SQ = vr("SQ");
    static final short UT = vr("UT");
    static final short UN = vr("UN");
    static final short DS = vr("DS");
    static final short US = vr("US");
    static final short AS = vr("AS");
    static final short AT = vr("AT");
    static final short CS = vr("CS");
    static final short DA = vr("DA");
    static final short DT = vr("DT");
    static final short FL = vr("FL");
    static final short FD = vr("FD");
    static final short IS = vr("IS");
    static final short LO = vr("LO");
    static final short LT = vr("LT");
    static final short PN = vr("PN");
    static final short SH = vr("SH");
    static final short SL = vr("SL");
    static final short SS = vr("SS");
    static final short ST = vr("ST");
    static final short TM = vr("TM");
    static final short UI = vr("UI");
    static final short UL = vr("UL");
    static final short AE = vr("AE");

    static HashSet<Short> strVRs = new HashSet<Short>();

    static {
        short[] all = new short[]{
                AE, AS, CS, DA, DS, DT, IS, LO, LT, PN, SH, ST, TM, UT
        };
        for (short anAll : all) {
            strVRs.add(anAll);
        }
    }

    boolean str(short vr) {
        return strVRs.contains(vr);
    }

    boolean big(short vr) {
        return OB == vr || OW == vr || OF == vr || SQ == vr || UT == vr || UN == vr;
    }

    class TagSet extends HashMap<Integer, Element> {
        Element get(int group, int element) {
            return get(tagInt(group, element));
        }

        void put(Element e) {
            put(tagInt(e.mGroup, e.mElement), e);
        }
    }

    static int tagInt(int g, int e) {
        return (g << 16) | (e & 0xFFFF);
    }

    public static ByteOrder reverse(ByteOrder o) {
        return (o == ByteOrder.LITTLE_ENDIAN) ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    }

    public TagSet read(File file, int[] tags) throws Exception {
        mName = file.getName();
        TagSet set = new TagSet();
        HashSet<Integer> toAdd = new HashSet<Integer>();
        for (int n : tags) {
            toAdd.add(n);
        }
        RandomAccessFile f = new RandomAccessFile(file, "r");

        mMappedByteBuffer = f.getChannel().map(MapMode.READ_ONLY, 0, mFileLen = f.length());
        mMappedByteBuffer.position(132);
        setOrder(ByteOrder.LITTLE_ENDIAN);
        Element e = new Element();
        boolean early = true;

        while (mMappedByteBuffer.position() < mFileLen) {
            int pos = mMappedByteBuffer.position();
            int jump = (int) readTag(e);

            if (early) {
                if (e.mGroup > 255) {
                    setOrder((mByteOrder == ByteOrder.LITTLE_ENDIAN) ?
                            ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
                    mMappedByteBuffer.position(mMappedByteBuffer.position() - jump);
                    readTag(e);
                }
            }

            if (early && e.mGroup >= 8) {

                early = false;
            }
            if (toAdd.contains(tagInt(e.mGroup, e.mElement))) {
                readValue(e);
                set.put(e);
                if (e.mGroup == 0x7fe0 && e.mElement == 0x10) {
                    return set;
                }
                e = new Element();

            } else {
                if (e.mGroup == 0x7fe0 && e.mElement == 0x10) {
                    return set;
                }

                skipValue(e);
            }
        }
        return set;
    }

    private long readTag(Element e) {
        e.mGroup = mMappedByteBuffer.getShort() & 0xFFFF;
        e.mElement = mMappedByteBuffer.getShort() & 0xFFFF;

        if (e.mGroup == 0xFFFE && e.mElement == 0xE000) {
            e.mLength = mMappedByteBuffer.getInt();
            if (e.mLength == -1) {
                e.mLength = 0;
            }
            e.mVR = vr("s<");
            return 8;
        }

        if (explicit) {
            e.mVR = mMappedByteBuffer.getShort();

            if (big(e.mVR)) {
                mMappedByteBuffer.getShort();
                e.mLength = mMappedByteBuffer.getInt() & 0xFFFFFFFF;
            } else {
                e.mLength = mMappedByteBuffer.getShort() & 0xFFFF;
            }
        } else {
            e.mVR = 0;
            int len = mMappedByteBuffer.getInt();
            e.mLength = (len) & 0xFFFFFFFFL;
            if (0xFFFFFFFF == e.mLength) {
                Log.v(LOGTAG, "undefined");
                e.mLength = 0;
            }
        }
        if (e.mLength == -1 || e.mLength == 65535) {
            e.mLength = 0;
        }
        return 8;
    }

    private void skipValue(Element e) {
        if (e.mLength == 0) {
            return;
        }
        if (dbg && str(e.mVR)) {
            mMappedByteBuffer.get(readBuff, 0, (int) (e.mLength));
            e.mValue = new String(readBuff, 0, (int) (e.mLength));
            //    Log.v(LOGTAG, e + "  " + e.mValue);
        } else {
            mMappedByteBuffer.position((int) (mMappedByteBuffer.position() + e.mLength));
        }
    }

    byte[] readBuff = new byte[200];

    private void readValue(Element e) {
        if (str(e.mVR)) {
            mMappedByteBuffer.get(readBuff, 0, (int) (e.mLength));
            e.mValue = new String(readBuff, 0, (int) (e.mLength));
        } else if (e.mVR == US) {
            e.mValue = new Short(mMappedByteBuffer.getShort());
        } else if (e.mVR == OW) {
            if (e.mLength == -1) {
                e.mLength = mFileLen - mMappedByteBuffer.position();
            }
            short[] s = new short[(int) (e.mLength / 2)];
            mMappedByteBuffer.asShortBuffer().get(s);
            e.mValue = s;
        }

    }

    private void setOrder(ByteOrder order) {
        mByteOrder = order;
        mMappedByteBuffer.order(mByteOrder);
    }

    public static Volume buildVolume(String dirName) {
        return buildVolume(new File(dirName));
    }

    public static Volume buildVolume(File dir) {
        LoaderDicom d = new LoaderDicom();
        int[] tags = new int[]{
                tagInt(0x20, 0x32),
                tagInt(0x20, 0x37),
                tagInt(0x28, 0x10),
                tagInt(0x28, 0x11),
                tagInt(0x7fe0, 0x10)
        };

        File[] files = dir.listFiles();
        Arrays.sort(files, new Comparator<File>() {

            @Override
            public int compare(File o1, File o2) {

                return o1.getName().compareTo(o2.getName());
            }
        });
        Volume v = new Volume();
        int count = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                continue;
            }
            if (file.getName().equals(".DS_Store")) {
                continue;
            }
            count++;
        }
        if (count < MIN_VOLUME_SIZE) {
            return null;
        }
        v.mData = new short[count][];
        v.mDimz = count;
        count = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                continue;
            }
            if (file.getName().equals(".DS_Store")) {
                continue;
            }
            try {
                TagSet data = d.read(file, tags);
                v.mData[count] = (short[]) data.get(0x7fe0, 0x10).mValue;
                count++;
                v.mDimx = (Short) data.get(0x28, 0x10).mValue;
                v.mDimy = (Short) data.get(0x28, 0x11).mValue;
            } catch (Exception e) {
                Log.e(LOGTAG, "Failed to parse " + file.getPath());
                e.printStackTrace();
            }
        }
        return v;
    }

    /**
     * This is a multi threaded volume loaded
     * It creates 2xthe number of cores
     * @param rs The renderscript context
     * @param dir The directory containing the DICOM files
     * @param listener The Listener to provide feedback to the UI on loading
     * @return The Volume object loaded with the volume
     */
    public static Volume buildRSVolume(final RenderScript rs, File dir,
                                       final VolumeLoader.ProgressListener listener) {
        final int[] tags = new int[]{
                tagInt(0x20, 0x32),
                tagInt(0x20, 0x37),
                tagInt(0x28, 0x10),
                tagInt(0x28, 0x11),
                tagInt(0x28, 0x30),
                tagInt(0x7fe0, 0x10)
        };

        File[] files = dir.listFiles();
        Arrays.sort(files, new Comparator<File>() {

            @Override
            public int compare(File o1, File o2) {

                return o1.getName().compareTo(o2.getName());
            }
        });
        final Volume v = new Volume();
        int count = 0;


        final Vector<File> toRun = new Vector<File>();
        final HashMap<File, Integer> fileMap = new HashMap<File, Integer>();
        for (File file : files) {
            if (file.isDirectory()) {
                continue;
            }
            if (file.getName().equals(".DS_Store")) {
                continue;
            }
            toRun.add(file);
            fileMap.put(file, count);
            count++;
        }
        if (count < MIN_VOLUME_SIZE) {
            return null;
        }
        v.mDimz = count;
        if (listener != null) {
            listener.progress(0, v.mDimx);
        }
        v.mVolumeAllocation = null;
        final String []pixel_spacing = new String[count];
        final String []slice_pos = new String[count];

        final ScriptC_bricked scriptC_bricked = new ScriptC_bricked(rs);
        int number_of_threads = 2 * Runtime.getRuntime().availableProcessors();
        Thread[] t = new Thread[number_of_threads];
        for (int i = 0; i < number_of_threads; i++) {

            t[i] = new Thread() {
                LoaderDicom d = new LoaderDicom();


                private File getOne() {
                    synchronized (toRun) {
                        if (toRun.isEmpty()) {
                            return null;
                        }
                        return toRun.remove(0);
                    }
                }

                public void run() {
                    File file;

                    Allocation alloc_slice = null;

                    while ((file = getOne()) != null) {
                        int z = fileMap.get(file);
                        try {
                            TagSet data = d.read(file, tags);
                            short[] slice = (short[]) data.get(0x7fe0, 0x10).mValue;
                            short dimX = (Short) data.get(0x28, 0x10).mValue;
                            short dimY = (Short) data.get(0x28, 0x11).mValue;
                            String val;
                            val = (String) data.get(0x28,0x30).mValue;
                            pixel_spacing[z] = val;

                            val = (String) data.get(0x20,0x32).mValue;
                            slice_pos[z] = val;

                            if (v.mDimx == -1) {
                                v.mDimy = dimY;
                                v.mDimx = dimX;
                            }
                            synchronized (v) {
                                if (v.mVolumeAllocation == null) {
                                    Type.Builder b = new Type.Builder(rs,
                                            android.renderscript.Element.I16(rs));
                                    b.setX(v.mDimx).setY(v.mDimy);
                                    b.setZ(v.mDimz);
                                    v.mVolumeAllocation = Allocation.createTyped(rs, b.create(),
                                            Allocation.USAGE_SCRIPT);
                                    scriptC_bricked.set_volume(v.mVolumeAllocation);
                                }
                            }

                            if (alloc_slice == null) {
                                Type.Builder b = new Type.Builder(rs,
                                        android.renderscript.Element.I16(rs));
                                b.setX(v.mDimx).setY(v.mDimy);
                                alloc_slice = Allocation.createTyped(rs, b.create(),
                                        Allocation.USAGE_SCRIPT);
                            }
                            if (listener != null) {
                                listener.progress(z, v.mDimx);
                            }
                            int size = v.mDimy * v.mDimx;
                            alloc_slice.copyFromUnchecked(slice);
                            synchronized (v) {
                                scriptC_bricked.set_z(z);
                                scriptC_bricked.forEach_copy(alloc_slice);
                            }

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    alloc_slice.destroy();
                }
            };
            t[i].start();
        }

        for (int i = 0; i < number_of_threads; i++) {
            try {
                t[i].join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        String[]pss = pixel_spacing[0].split("\\\\");
        String[]s1ps = slice_pos[0].split("\\\\");
        String[]s2ps = slice_pos[1].split("\\\\");
        float sx = Float.parseFloat(pss[0]);
        float sy = Float.parseFloat(pss[1]);
        double dzx = Double.parseDouble(s1ps[0]) - Double.parseDouble(s2ps[0]);
        double dzy = Double.parseDouble(s1ps[1]) - Double.parseDouble(s2ps[1]);
        double dzz = Double.parseDouble(s1ps[2]) - Double.parseDouble(s2ps[2]);
        float sz = (float) Math.hypot(dzx,Math.hypot(dzy,dzz));
        float min = Math.min(sx,Math.min(sy,sz));
        v.mVoxelDim[0] = sx/min;
        v.mVoxelDim[1] = sy/min;
        v.mVoxelDim[2] = sz/min;
        Log.v(LOGTAG,"LOADING DONE ....");
        scriptC_bricked.destroy();
        return v;
    }

    /**
     * Single threaded version of the volume createor
     * @param rs the renderscript context
     * @param dir the directory containing the dicom files
     * @param listener used to feed back status to progress listeners
     * @return Built volume
     */
    public static Volume buildRSVolume2(final RenderScript rs, File dir,
                                        VolumeLoader.ProgressListener listener) {
        final int[] tags = new int[]{
                tagInt(0x20, 0x32),
                tagInt(0x20, 0x37),
                tagInt(0x28, 0x10),
                tagInt(0x28, 0x11),
                tagInt(0x28, 0x30),
                tagInt(0x7fe0, 0x10)
        };
        File[] files = dir.listFiles();
        Arrays.sort(files, new Comparator<File>() {

            @Override
            public int compare(File o1, File o2) {

                return o1.getName().compareTo(o2.getName());
            }
        });
        Volume v = new Volume();
        int count = 0;


        final Vector<File> toRun = new Vector<File>();
        final HashMap<File, Integer> fileMap = new HashMap<File, Integer>();
        for (File file1 : files) {
            if (file1.isDirectory()) {
                continue;
            }
            if (file1.getName().equals(".DS_Store")) {
                continue;
            }
            toRun.add(file1);
            fileMap.put(file1, count);
            count++;
        }
        if (count < 20) {
            return null;
        }
        v.mDimz = count;
        if (listener != null) {
            listener.progress(0, v.mDimz);
        }
        v.mVolumeAllocation = null;
        Allocation alloc_slice = null;
        ScriptC_bricked scriptC_bricked = new ScriptC_bricked(rs);
        LoaderDicom d = new LoaderDicom();
        String pixel_spacing = null;
        String slice1_pos = null;
        String slice2_pos = null;
        boolean slice_spacing_set = false;
        int z = 0;
        for (File file : toRun) {
            try {
                TagSet data = d.read(file, tags);
                short[] slice = (short[]) data.get(0x7fe0, 0x10).mValue;
                short mDimx = (Short) data.get(0x28, 0x10).mValue;
                short mDimy = (Short) data.get(0x28, 0x11).mValue;
                String val;
                val = (String) data.get(0x28,0x30).mValue;
                if (val != null && pixel_spacing==null) {
                    pixel_spacing = val;
                }
                val = (String) data.get(0x20,0x32).mValue;
                if (val != null) {
                    if (slice1_pos == null) {
                        slice1_pos = val;
                    } else if (slice2_pos == null) {
                        slice2_pos = val;
                    }
                }
                if (v.mDimx == -1) {
                    v.mDimy = mDimy;
                    v.mDimx = mDimx;
                }

                if (v.mVolumeAllocation == null) {
                    Type.Builder b = new Type.Builder(rs, android.renderscript.Element.I16(rs));
                    b.setX(v.mDimx).setY(v.mDimy);
                    alloc_slice = Allocation.createTyped(rs, b.create(), Allocation.USAGE_SCRIPT);
                    b.setZ(v.mDimz);
                    v.mVolumeAllocation = Allocation.createTyped(rs, b.create(),
                            Allocation.USAGE_SCRIPT);
                    scriptC_bricked.set_volume(v.mVolumeAllocation);

                }
                if (listener != null) {
                    listener.progress(z, v.mDimz);
                }

                int size = v.mDimy * v.mDimx;
                alloc_slice.copyFromUnchecked(slice);
                scriptC_bricked.set_z(z);
                scriptC_bricked.forEach_copy(alloc_slice);
                z++;
                if (!slice_spacing_set
                        && pixel_spacing!=null
                        && slice1_pos!=null
                        && slice2_pos != null) {
                    String[]pss = pixel_spacing.split("\\\\");
                    String[]s1ps = slice1_pos.split("\\\\");
                    String[]s2ps = slice2_pos.split("\\\\");
                    float sx = Float.parseFloat(pss[0]);
                    float sy = Float.parseFloat(pss[1]);
                    double dzx = Double.parseDouble(s1ps[0]) - Double.parseDouble(s2ps[0]);
                    double dzy = Double.parseDouble(s1ps[1]) - Double.parseDouble(s2ps[1]);
                    double dzz = Double.parseDouble(s1ps[2]) - Double.parseDouble(s2ps[2]);
                    float sz = (float) Math.hypot(dzx,Math.hypot(dzy,dzz));
                    float min = Math.min(sx,Math.min(sy,sz));
                    v.mVoxelDim[0] = sx/min;
                    v.mVoxelDim[1] = sy/min;
                    v.mVoxelDim[2] = sz/min;
                    slice_spacing_set = true;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        Log.v(LOGTAG,"LOADING DONE ....");

        alloc_slice.destroy();

        scriptC_bricked.destroy();
        return v;
    }
}
