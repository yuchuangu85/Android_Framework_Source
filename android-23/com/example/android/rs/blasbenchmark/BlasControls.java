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

package com.example.android.rs.blasbenchmark;

import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuInflater;
import android.text.method.ScrollingMovementMethod;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.graphics.Point;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
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

public class BlasControls extends Activity {
    private final String TAG = "BLAS";
    public final String RESULT_FILE = "blas_benchmark_result.csv";

    private ListView mTestListView;
    private TextView mResultView;

    private ArrayAdapter<String> mTestListAdapter;
    private ArrayList<String> mTestList = new ArrayList<String>();

    private boolean mSettings[] = {false, false};
    private static final int SETTING_LONG_RUN = 0;
    private static final int SETTING_PAUSE = 1;

    private float mResults[];
    private String mInfo[];

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu items for use in the action bar
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_activity_actions, menu);

        return super.onCreateOptionsMenu(menu);
    }

    void init() {

        for (int i=0; i < BlasTestList.TestName.values().length; i++) {
            mTestList.add(BlasTestList.TestName.values()[i].toString());
        }

        mTestListView = (ListView) findViewById(R.id.test_list);
        mTestListAdapter = new ArrayAdapter(this,
                android.R.layout.simple_list_item_activated_1,
                mTestList);

        mTestListView.setAdapter(mTestListAdapter);
        mTestListView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        mTestListAdapter.notifyDataSetChanged();

        mResultView = (TextView) findViewById(R.id.results);
        mResultView.setMovementMethod(new ScrollingMovementMethod());
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

    Intent makeBasicLaunchIntent() {
        Intent intent = new Intent(this, BlasBenchmark.class);
        intent.putExtra("enable long", mSettings[SETTING_LONG_RUN]);
        intent.putExtra("enable pause", mSettings[SETTING_PAUSE]);
        return intent;
    }

    public void btnRun(View v) {
        BlasTestList.TestName t[] = BlasTestList.TestName.values();
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
            java.text.DecimalFormat df = new java.text.DecimalFormat("######.####");

            for (int ct=0; ct < BlasTestList.TestName.values().length; ct++) {
                String t = BlasTestList.TestName.values()[ct].toString();
                final float r = mResults[ct];
                String s = new String("" + t + ", " + df.format(r));
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
                java.text.DecimalFormat df = new java.text.DecimalFormat("######.####");
                mResults = new float[BlasTestList.TestName.values().length];
                mInfo = new String[BlasTestList.TestName.values().length];

                float r[] = data.getFloatArrayExtra("results");
                String inf[] = data.getStringArrayExtra("testinfo");
                int id[] = data.getIntArrayExtra("tests");

                String mOutResult = "";
                for (int ct=0; ct < id.length; ct++) {
                    String t = inf[ct];
                    String sl = BlasTestList.TestName.values()[id[ct]].toString() + ":   " + df.format(r[ct]) + "ms";
                    String s = t + ":   " + df.format(r[ct]) + "ms";
                    mTestList.set(id[ct], sl);
                    mTestListAdapter.notifyDataSetChanged();
                    mOutResult += s + '\n';
                    mResults[id[ct]] = r[ct];
                }

                mResultView.setText(mOutResult);
                writeResults();
            }
        }
    }

    public void btnSelAll(View v) {
        BlasTestList.TestName t[] = BlasTestList.TestName.values();
        for (int i=0; i < t.length; i++) {
            mTestListView.setItemChecked(i, true);
        }
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle presses on the action bar items
        switch(item.getItemId()) {
            case R.id.action_settings:
                BlasSettings newFragment = new BlasSettings(mSettings);
                newFragment.show(getFragmentManager(), "settings");
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public void btnSelNone(View v) {
        BlasTestList.TestName t[] = BlasTestList.TestName.values();
        for (int i=0; i < t.length; i++) {
            mTestListView.setItemChecked(i, false);
        }
    }

    public void btnSettings(View v) {
        BlasSettings newFragment = new BlasSettings(mSettings);
        newFragment.show(getFragmentManager(), "settings");
    }
}
