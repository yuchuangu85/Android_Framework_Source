/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.rs.image2;

import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuInflater;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.graphics.Point;
import android.view.SurfaceView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ToggleButton;
import android.widget.TextView;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.view.View;
import java.util.ArrayList;
import java.util.ListIterator;
import android.util.Log;
import android.content.Intent;

import android.os.Environment;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class IPControls extends Activity {
    private final String TAG = "Img";
    public final String RESULT_FILE = "ip_compat_result.csv";

    private Spinner mResolutionSpinner;
    private ListView mTestListView;
    private TextView mResultView;

    private ArrayAdapter<String> mTestListAdapter;
    private ArrayList<String> mTestList = new ArrayList<String>();

    private boolean mSettings[] = {true, true, false, false, false};
    private static final int SETTING_ANIMATE = 0;
    private static final int SETTING_DISPLAY = 1;
    private static final int SETTING_LONG_RUN = 2;
    private static final int SETTING_PAUSE = 3;
    private static final int SETTING_USAGE_IO = 4;

    private float mResults[];

    public enum Resolutions {
        RES_1080P(1920, 1080, "1080p (1920x1080)"),
        RES_720P(1280, 720, "720p (1280x720)"),
        RES_WVGA(800, 480, "WVGA (800x480)");

        private final String name;
        public final int width;
        public final int height;

        private Resolutions(int w, int h, String s) {
            width = w;
            height = h;
            name = s;
        }

        // return quoted string as displayed test name
        public String toString() {
            return name;
        }
    }
    private Resolutions mResolution;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu items for use in the action bar
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_activity_actions, menu);

        MenuItem searchItem = menu.findItem(R.id.action_res);
        mResolutionSpinner = (Spinner) searchItem.getActionView();

        mResolutionSpinner.setOnItemSelectedListener(mResolutionSpinnerListener);
        mResolutionSpinner.setAdapter(new ArrayAdapter<Resolutions>(
            this, R.layout.spinner_layout, Resolutions.values()));

        // Choose one of the image sizes that is close to the resolution
        // of the screen.
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getSize(size);
        boolean didSet = false;
        int md = (size.x > size.y) ? size.x : size.y;
        for (int ct=0; ct < Resolutions.values().length; ct++) {
            if (Resolutions.values()[ct].width <= (int)(md * 1.2)) {
                mResolutionSpinner.setSelection(ct);
                didSet = true;
                break;
            }
        }
        if (!didSet) {
            // If no good resolution was found, pick the lowest one.
            mResolutionSpinner.setSelection(Resolutions.values().length - 1);
        }

        return super.onCreateOptionsMenu(menu);
    }


    private AdapterView.OnItemSelectedListener mResolutionSpinnerListener =
            new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    mResolution = Resolutions.values()[pos];
                }

                public void onNothingSelected(AdapterView parent) {
                }
            };

    void launchDemo(int id) {
        int testList[] = new int[1];
        testList[0] = id;

        Intent intent = makeBasicLaunchIntent();
        intent.putExtra("tests", testList);
        intent.putExtra("demo", true);
        startActivityForResult(intent, 0);
    }

    void init() {

        for (int i=0; i < IPTestList.TestName.values().length; i++) {
            mTestList.add(IPTestList.TestName.values()[i].toString());
        }

        mTestListView = (ListView) findViewById(R.id.test_list);
        mTestListAdapter = new ArrayAdapter(this,
                android.R.layout.simple_list_item_activated_1,
                mTestList);

        mTestListView.setAdapter(mTestListAdapter);
        mTestListView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        mTestListAdapter.notifyDataSetChanged();

        mResultView = (TextView) findViewById(R.id.results);

        mTestListView.setOnItemLongClickListener(new ListView.OnItemLongClickListener() {
                public boolean onItemLongClick(AdapterView<?> arg0, View arg1,
                        int pos, long id) {
                    launchDemo(pos);
                    return true;
                }
            });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.controls);
        init();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }


    @Override
    protected void onResume() {
        super.onResume();
    }

    private void checkGroup(int group) {
        IPTestList.TestName t[] = IPTestList.TestName.values();
        for (int i=0; i < t.length; i++) {
            mTestListView.setItemChecked(i, group == t[i].group);
        }
    }

    Intent makeBasicLaunchIntent() {
        Intent intent = new Intent(this, ImageProcessingActivity2.class);
        intent.putExtra("enable long", mSettings[SETTING_LONG_RUN]);
        intent.putExtra("enable pause", mSettings[SETTING_PAUSE]);
        intent.putExtra("enable animate", mSettings[SETTING_ANIMATE]);
        intent.putExtra("enable display", mSettings[SETTING_DISPLAY]);
        intent.putExtra("enable io", mSettings[SETTING_USAGE_IO]);
        intent.putExtra("resolution X", mResolution.width);
        intent.putExtra("resolution Y", mResolution.height);
        return intent;
    }

    public void btnRun(View v) {
        IPTestList.TestName t[] = IPTestList.TestName.values();

        int count = 0;
        for (int i = 0; i < t.length; i++) {
            if (mTestListView.isItemChecked(i)) {
                count++;
            }
        }
        if (count == 0) {
            return;
        }

        int testList[] = new int[count];
        count = 0;
        for (int i = 0; i < t.length; i++) {
            if (mTestListView.isItemChecked(i)) {
                testList[count++] = i;
            }
        }

        Intent intent = makeBasicLaunchIntent();
        intent.putExtra("tests", testList);
        startActivityForResult(intent, 0);
    }

    // Rebase normalizes the performance result for the resolution and
    // compares it to a baseline device.
    float rebase(float v, IPTestList.TestName t) {
        if (v > 0.001) {
            v = t.baseline / v;
        }
        float pr = (1920.f / mResolution.width) * (1080.f / mResolution.height);
        return v / pr;
    }

    private void writeResults() {
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
            java.text.DecimalFormat df = new java.text.DecimalFormat("######.##");

            for (int ct=0; ct < IPTestList.TestName.values().length; ct++) {
                IPTestList.TestName t = IPTestList.TestName.values()[ct];
                final float r = mResults[ct];
                float r2 = rebase(r, t);
                String s = new String("" + t.toString() + ", " + df.format(r) + ", " + df.format(r2));
                rsWriter.write(s + "\n");
            }
            rsWriter.close();
        } catch (IOException e) {
            Log.v(TAG, "Unable to write result file " + e.getMessage());
        }
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 0) {
            if (resultCode == RESULT_OK) {
                java.text.DecimalFormat df = new java.text.DecimalFormat("######.#");
                mResults = new float[IPTestList.TestName.values().length];

                float r[] = data.getFloatArrayExtra("results");
                int id[] = data.getIntArrayExtra("tests");

                for (int ct=0; ct < id.length; ct++) {
                    IPTestList.TestName t = IPTestList.TestName.values()[id[ct]];

                    String s = t.toString() + "   " + df.format(rebase(r[ct], t)) +
                            "X,   " + df.format(r[ct]) + "ms";
                    mTestList.set(id[ct], s);
                    mTestListAdapter.notifyDataSetChanged();
                    mResults[id[ct]] = r[ct];
                }

                double geometricMean[] = {1.0, 1.0, 1.0};
                double count[] = {0, 0, 0};
                for (int ct=0; ct < IPTestList.TestName.values().length; ct++) {
                    IPTestList.TestName t = IPTestList.TestName.values()[ct];
                    geometricMean[t.group] *= rebase(mResults[ct], t);
                    count[t.group] += 1.0;
                }
                geometricMean[0] = java.lang.Math.pow(geometricMean[0], 1.0 / count[0]);
                geometricMean[1] = java.lang.Math.pow(geometricMean[1], 1.0 / count[1]);
                geometricMean[2] = java.lang.Math.pow(geometricMean[2], 1.0 / count[2]);

                String s = "Results:  fp full=" + df.format(geometricMean[0]) +
                        ",  fp relaxed=" +df.format(geometricMean[1]) +
                        ",  intrinsics=" + df.format(geometricMean[2]);
                mResultView.setText(s);
                writeResults();
            }
        }
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle presses on the action bar items
        switch(item.getItemId()) {
            case R.id.action_settings:
                IPSettings newFragment = new IPSettings(mSettings);
                newFragment.show(getFragmentManager(), "settings");
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public void btnSelAll(View v) {
        IPTestList.TestName t[] = IPTestList.TestName.values();
        for (int i=0; i < t.length; i++) {
            mTestListView.setItemChecked(i, true);
        }
    }

    public void btnSelNone(View v) {
        checkGroup(-1);
    }

    public void btnSettings(View v) {
        IPSettings newFragment = new IPSettings(mSettings);
        newFragment.show(getFragmentManager(), "settings");
    }

    public void btnSelIntrinsic(View v) {
        checkGroup(IPTestList.INTRINSIC);
    }



}
