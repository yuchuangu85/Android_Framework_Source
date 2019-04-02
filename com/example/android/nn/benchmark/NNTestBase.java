/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.example.android.nn.benchmark;

import android.content.res.AssetManager;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class NNTestBase  {
    protected final boolean USE_NNAPI = true;

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("nnbenchmark");
    }

    private synchronized native long initModel(String modelFileName);
    private synchronized native void destroyModel(long modelHandle);
    private synchronized native boolean resizeInputTensors(long modelHandle, int[] inputShape);
    private synchronized native boolean runBenchmark(long modelHandle, boolean useNNAPI);

    protected NNBenchmark mActivity;
    protected TextView mText;
    private String mModelName;
    private long mModelHandle;
    private int[] mInputShape;

    public NNTestBase(String modelName, int[] inputShape) {
        mModelName = modelName;
        mInputShape = inputShape;
        mModelHandle = 0;
    }

    public final void createBaseTest(NNBenchmark ipact) {
        mActivity = ipact;
        String modelFileName = copyAssetToFile();
        if (modelFileName != null) {
            mModelHandle = initModel(modelFileName);
            if (mModelHandle != 0) {
                resizeInputTensors(mModelHandle, mInputShape);
            } else {
                Log.e(NNBenchmark.TAG, "Failed to init the model");
            }
        }
    }

    public String getTestInfo() {
        return mModelName;
    }

    public void runTest() {
        if (mModelHandle != 0) {
            runBenchmark(mModelHandle, USE_NNAPI);
        }
    }

    public void destroy() {
        if (mModelHandle != 0) {
            destroyModel(mModelHandle);
            mModelHandle = 0;
        }
    }

    // We need to copy it to cache dir, so that TFlite can load it directly.
    private String copyAssetToFile() {
        String outFileName;
        String modelAssetName = mModelName + ".tflite";
        AssetManager assetManager = mActivity.getAssets();
        try {
            InputStream in = assetManager.open(modelAssetName);

            outFileName = mActivity.getCacheDir().getAbsolutePath() + "/" + modelAssetName;
            File outFile = new File(outFileName);
            OutputStream out = new FileOutputStream(outFile);

            byte[] buffer = new byte[1024];
            int read;
            while((read = in.read(buffer)) != -1){
                out.write(buffer, 0, read);
            }
            out.flush();

            in.close();
            out.close();
        } catch(IOException e) {
            Log.e(NNBenchmark.TAG, "Failed to copy asset file: " + modelAssetName, e);
            return null;
        }
        return outFileName;
    }
}
