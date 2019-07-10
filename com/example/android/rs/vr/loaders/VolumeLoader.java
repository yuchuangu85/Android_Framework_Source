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

import android.renderscript.RenderScript;
import android.util.Log;

import com.example.android.rs.vr.engine.Volume;

import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Properties;

public class VolumeLoader {
    private static final String LOGTAG = "VolumeLoader";
    HashMap<String, Properties> map = new HashMap<String, Properties>();
    File baseDir;
    ProgressListener mListener;

    public VolumeLoader(String dir) {
        map.put(Mandelbulb.NAME,null);
        map.put(Droid.NAME,null);
        baseDir = new File(dir);
        if (!baseDir.exists()) {
            Log.e(LOGTAG, "Directory: \""+dir+"\" does not exist ");
            return;
        }
        Properties[] prop = getPropertyFiles(baseDir);
        for (int i = 0; i < prop.length; i++) {
            map.put(prop[i].getProperty("name"), prop[i]);
        }

    }

    public String[] getNames() {
       String [] ret = map.keySet().toArray(new String[map.size()]);
        Arrays.sort(ret);
        return ret;
    }

    public Volume getVolume(RenderScript rs, String name) {
        if (name.equals(Mandelbulb.NAME)) {
            return  Mandelbulb.buildRSVolume(rs,mListener);
        }
        if (name.equals(Droid.NAME)) {
            return  Droid.buildRSVolume(rs,mListener);
        }
        Properties p = map.get(name);
        if (p == null) {
            Log.v(LOGTAG,"Could not find "+name);
            return null;
        }
        String dir = p.getProperty("dir");
        Log.v(LOGTAG,"dir ="+dir);

        if ("dicom".equalsIgnoreCase(p.getProperty("format"))) {
            Log.v(LOGTAG,"processing dicom");
            Volume v = LoaderDicom.buildRSVolume(rs, new File(baseDir, dir), mListener);
            String [] looks = p.getProperty("looks").split(",");
            for (int j = 0; j < looks.length; j++) {
                String look_color = p.getProperty(looks[j]+".color");
                String look_opacity = p.getProperty(looks[j]+".opacity");
                v.addLook(looks[j],look_color,look_opacity);
            }
            return v;
        } else if ("raw".equalsIgnoreCase(p.getProperty("format"))) {
            Log.v(LOGTAG,"processing dicom");
            Volume v = LoaderRaw.buildRSVolume(rs, new File(baseDir, dir), p, mListener);
            String [] looks = p.getProperty("looks").split(",");
            for (int j = 0; j < looks.length; j++) {
                String look_color = p.getProperty(looks[j]+".color");
                String look_opacity = p.getProperty(looks[j]+".opacity");
                v.addLook(looks[j],look_color,look_opacity);
            }
            return v;
        }
        Log.v(LOGTAG,"could recognize format");
        return null;
    }

    static Properties[] getPropertyFiles(File dir) {

        File[] f = dir.listFiles(new FilenameFilter() {

            @Override
            public boolean accept(File dir, String name) {
                Log.v(LOGTAG, name);
                return name.endsWith(".prop");
            }
        });
        Properties[]ret = new Properties[f.length];
        for (int i = 0; i < f.length; i++) {
            Properties prop = new Properties();
            ret[i] = prop;
            try {
                prop.load(new FileReader(f[i]));

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return ret;
    }

    public void setProgressListener(ProgressListener listener){
        mListener = listener;
    }

    public static interface ProgressListener {
        public void progress(int n, int total);
    }
}
