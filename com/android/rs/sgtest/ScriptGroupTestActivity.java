/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.rs.sgtest;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.SurfaceView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.view.View;
import android.util.Log;
import android.renderscript.ScriptC;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Script;

import android.os.Environment;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

public class ScriptGroupTestActivity extends Activity
    implements SeekBar.OnSeekBarChangeListener {
    private final String TAG = "Img";
    public final String RESULT_FILE = "image_processing_result.csv";

    RenderScript mRS;
    Allocation mInPixelsAllocation;
    Allocation mOutPixelsAllocation;

    Bitmap mBitmapOut;

    private Spinner mSpinner;

    private TextView mBenchmarkResult;
    private Spinner mModeSpinner;
    private Spinner mTestSpinner1;
    private Spinner mTestSpinner2;

    private ImageView mDisplayView;

    private boolean mDoingBenchmark;

    private TestBase mTest;
    private int mRunCount;

    public void updateDisplay() {
        mHandler.sendMessage(Message.obtain());
    }

    private Handler mHandler = new Handler() {
        // Allow the filter to complete without blocking the UI
        // thread.  When the message arrives that the op is complete
        // we will either mark completion or start a new filter if
        // more work is ready.  Either way, display the result.
        @Override
        public void handleMessage(Message msg) {
            boolean doTest = false;
            synchronized(this) {
                if (mRS == null) {
                    return;
                }
                mTest.updateBitmap(mBitmapOut);
                mDisplayView.invalidate();
                if (mRunCount > 0) {
                    mRunCount--;
                    if (mRunCount > 0) {
                        doTest = true;
                    }
                }

                if (doTest) {
                    mTest.runTestSendMessage();
                }
            }
        }

    };

    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
    }

    public void onStartTrackingTouch(SeekBar seekBar) {
    }

    public void onStopTrackingTouch(SeekBar seekBar) {
    }

  void changeTest(int pos1, int pos2, int mode) {
    if (mTest != null) {
      mTest.destroy();
    }

    final int[] index = new int[] { pos1, pos2 };
    mTest = new Filters(mode, index);

    mTest.createBaseTest(this);

    mTest.runTest();
    updateDisplay();
    mBenchmarkResult.setText("Result: not run");
  }

  String getFilterName(int pos) {
    return Filters.mFilterClasses[pos].getSimpleName();
  }

  String[] getFilterNames() {
    ArrayList<String> list = new ArrayList<String>();
    final int n = Filters.mFilterClasses.length;
    for (int i = 0; i < n; i++) {
      list.add(getFilterName(i));
    }
    return list.toArray(new String[0]);
  }

  void setupTests() {
    String[] names = getFilterNames();
    mModeSpinner.setAdapter(new ArrayAdapter<String>(
        this, R.layout.spinner_layout, new String[] {"emulated", "native"}));
    mTestSpinner1.setAdapter(new ArrayAdapter<String>(
        this, R.layout.spinner_layout, names));
    mTestSpinner2.setAdapter(new ArrayAdapter<String>(
        this, R.layout.spinner_layout, names));
  }

  private AdapterView.OnItemSelectedListener mModeSpinnerListener =
      new AdapterView.OnItemSelectedListener() {
        public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
          changeTest(
              mTestSpinner1.getSelectedItemPosition(),
              mTestSpinner2.getSelectedItemPosition(),
              pos);
        }

        public void onNothingSelected(AdapterView parent) {
        }
      };

  private AdapterView.OnItemSelectedListener mTestSpinner1Listener =
      new AdapterView.OnItemSelectedListener() {
        public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
          changeTest(pos, mTestSpinner2.getSelectedItemPosition(),
              mModeSpinner.getSelectedItemPosition());
        }

        public void onNothingSelected(AdapterView parent) {
        }
      };

  private AdapterView.OnItemSelectedListener mTestSpinner2Listener =
      new AdapterView.OnItemSelectedListener() {
        public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
          changeTest(mTestSpinner1.getSelectedItemPosition(), pos,
              mModeSpinner.getSelectedItemPosition());
        }

        public void onNothingSelected(AdapterView parent) {
        }
      };

    void init() {
        mRS = RenderScript.create(this);
        mInPixelsAllocation = Allocation.createFromBitmapResource(
                mRS, getResources(), R.drawable.img1600x1067);
        mBitmapOut = Bitmap.createBitmap(mInPixelsAllocation.getType().getX(),
                                         mInPixelsAllocation.getType().getY(),
                                         Bitmap.Config.ARGB_8888);
        mOutPixelsAllocation = Allocation.createFromBitmap(mRS, mBitmapOut);

        mDisplayView = (ImageView) findViewById(R.id.display);
        mDisplayView.setImageBitmap(mBitmapOut);

        mModeSpinner = (Spinner) findViewById(R.id.modeselection);
        mModeSpinner.setOnItemSelectedListener(mModeSpinnerListener);
        mTestSpinner1 = (Spinner) findViewById(R.id.filterselection);
        mTestSpinner1.setOnItemSelectedListener(mTestSpinner1Listener);
        mTestSpinner2 = (Spinner) findViewById(R.id.filter2selection);
        mTestSpinner2.setOnItemSelectedListener(mTestSpinner2Listener);

        mBenchmarkResult = (TextView) findViewById(R.id.benchmarkText);
        mBenchmarkResult.setText("Result: not run");

        setupTests();
        changeTest(0, 0, 0);
    }

    void cleanup() {
        synchronized(this) {
            RenderScript rs = mRS;
            mRS = null;
            while(mDoingBenchmark) {
                try {
                    Thread.sleep(1, 0);
                } catch(InterruptedException e) {
                }

            }
            rs.destroy();
        }

        mInPixelsAllocation = null;
        mOutPixelsAllocation = null;
        mBitmapOut = null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        init();
    }

    @Override
    protected void onPause() {
        super.onPause();

        cleanup();
    }


    @Override
    protected void onResume() {
        super.onResume();

        if (null == mRS) {
            init();
        }
    }

    // button hook
    public void benchmark(View v) {
        float t = getBenchmark();
        //long javaTime = javaFilter();
        //mBenchmarkResult.setText("RS: " + t + " ms  Java: " + javaTime + " ms");
        mBenchmarkResult.setText("Result: " + t + " ms");
        Log.v(TAG, "getBenchmark: Renderscript frame time core ms " + t);
    }

    public void benchmark_all(View v) {
        // write result into a file
        File externalStorage = Environment.getExternalStorageDirectory();
        if (!externalStorage.canWrite()) {
            Log.v(TAG, "sdcard is not writable");
            return;
        }
        File resultFile = new File(externalStorage, RESULT_FILE);
        resultFile.setWritable(true, false);
        try {
            BufferedWriter rsWriter = new BufferedWriter(new FileWriter(resultFile));
            Log.v(TAG, "Saved results in: " + resultFile.getAbsolutePath());
            final int n = Filters.mFilterClasses.length;
            for (int i = 0; i < n; i++) {
              for (int j = 0; j < n; j++) {
                for (int k = 0; k < 2; k++) {
                  changeTest(i, j, k);
                float t = getBenchmark();
                String tn = getFilterName(i) + "-" + getFilterName(j);
                if (k == 0) {
                  tn += " (emulated)";
                } else {
                  tn += " (native)";
                }
                String s = new String("" + tn.toString() + ", " + t);
                rsWriter.write(s + "\n");
                Log.v(TAG, "Test " + s + "ms\n");
                }
              }
            }
            rsWriter.close();
        } catch (IOException e) {
            Log.v(TAG, "Unable to write result file " + e.getMessage());
        }
            changeTest(0, 0, 0);
        Log.v(TAG, "result file:"+resultFile.getAbsolutePath());
    }



    // For benchmark test
    public float getBenchmark() {
        if (mRS == null) {
            return 0;
        }
        mDoingBenchmark = true;

        mTest.setupBenchmark();
        long result = 0;

        //Log.v(TAG, "Warming");
        long t = java.lang.System.currentTimeMillis() + 250;
        do {
            mTest.runTest();
            mTest.finish();
        } while (t > java.lang.System.currentTimeMillis());

        //Log.v(TAG, "Benchmarking");
        int ct = 0;
        t = java.lang.System.currentTimeMillis();
        do {
            mTest.runTest();
            mTest.finish();
            ct++;
        } while ((t+1000) > java.lang.System.currentTimeMillis());
        t = java.lang.System.currentTimeMillis() - t;
        float ft = (float)t;
        ft /= ct;

        mTest.exitBenchmark();
        mDoingBenchmark = false;

        return ft;
    }
}
