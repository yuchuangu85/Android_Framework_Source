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

package com.example.android.rs.vr;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.AsyncTask;
import android.os.Bundle;
import android.renderscript.RenderScript;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.ToggleButton;

import java.io.File;

import rsexample.google.com.vrdemo.R;

import com.example.android.rs.vr.engine.Volume;
import com.example.android.rs.vr.engine.VrState;
import com.example.android.rs.vr.loaders.Droid;
import com.example.android.rs.vr.loaders.Mandelbulb;
import com.example.android.rs.vr.loaders.VolumeLoader;

/**
 * basic activity loads the volume and sets it on the VrView
 */
public class VrActivity extends Activity {
    private static final String LOGTAG = "VrActivity";
    VrState mState = new VrState();
    VrView mVrView;
    VolumeLoader mLoader;
    private RenderScript mRs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vr);

        mVrView = (VrView) findViewById(R.id.view);
        mRs = RenderScript.create(VrActivity.this);

        String dir = "/sdcard/Download/volumes";
        mLoader = new VolumeLoader(dir);
        VrSetupTask setup = new VrSetupTask();
        String [] names = mLoader.getNames();
        setup.execute(names[0]);
        TextView tv = (TextView) findViewById(R.id.title);
        tv.setText(names[0]);
    }

    class VrSetupTask extends AsyncTask<String, Integer, Volume> {
        ProgressDialog progressDialog;
        String message;
        protected void onPreExecute() {
            super.onPreExecute();
            progressDialog = new ProgressDialog(VrActivity.this);
            progressDialog.setMessage(message= "Loading Volume");
            progressDialog.setIndeterminate(true);
            progressDialog.setCancelable(false);
            progressDialog.setProgress(0);
            progressDialog.setMax(100);
            progressDialog.show();

            mLoader.setProgressListener(new VolumeLoader.ProgressListener() {
                @Override
                public void progress(int n, int total) {
                     publishProgress(n, total);
                }
            });
        }

        @Override
        protected Volume doInBackground(String... names) {
            if (names[0].equals(Droid.NAME) || names[0].equals(Mandelbulb.NAME)) {
                message = "Computing "+names[0]+": ";
            } else {
                message =" Loading " + names[0]+": ";
            }
            return  mLoader.getVolume(mRs, names[0]);
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            progressDialog.setMessage(message+progress[0]+"/"+progress[1]);
            progressDialog.setMax(progress[1]);
            progressDialog.setProgress(progress[0]);
            Log.v(LOGTAG,"Loading "+ progress[0]+"/"+progress[1]);
        }

        protected void onPostExecute(Volume v) {
            Log.v(LOGTAG,"done");
            mVrView.setVolume(mRs, v);
            progressDialog.dismiss();
            if(getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
        }
    }

    public void cutXClick(View v) {
        mVrView.setMode(VrView.CUT_X_MODE);
        uncheckOthers(v);
    }

    public void cutYClick(View v) {
        mVrView.setMode(VrView.CUT_Y_MODE);
        uncheckOthers(v);
    }

    public void cutZClick(View v) {
        mVrView.setMode(VrView.CUT_Z_MODE);
        uncheckOthers(v);
    }

    public void rotateClick(View v) {
        mVrView.setMode(VrView.ROTATE_MODE);
        uncheckOthers(v);
    }

    public void resetClick(View v) {
        mVrView.resetCut();
    }

    public void saveClick(View v) {
        // TODO should save and Image
    }

    public void looksClick(View v) {
        String[] looks = mVrView.getLooks();
        PopupMenu popup = new PopupMenu(this, v);
        Menu menu = popup.getMenu();

        for (int i = 0; i < looks.length; i++) {
            menu.add(0, Menu.FIRST + i, Menu.NONE, looks[i]);
        }

        //registering popup with OnMenuItemClickListener
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                mVrView.setLook(item.getTitle().toString());
                return true;
            }
        });
        popup.show();
        uncheckOthers(v);
    }

    public void dataClick(View v) {
        Log.v(LOGTAG, "dataClick");

        String[] volumes = mLoader.getNames();
        PopupMenu popup = new PopupMenu(this, v);
        Menu menu = popup.getMenu();

        for (int i = 0; i < volumes.length; i++) {
            menu.add(0, Menu.FIRST + i, Menu.NONE, volumes[i]);
        }

        //registering popup with OnMenuItemClickListener
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {

                VrSetupTask setup = new VrSetupTask();
                String title = item.getTitle().toString();
                TextView tv = (TextView) findViewById(R.id.title);
                tv.setText(title);
                setup.execute(title);
                return true;
            }
        });

        popup.show();
        uncheckOthers(v);
    }

    private void uncheckOthers(View v) {
        ViewGroup p = (ViewGroup) v.getParent().getParent();
        uncheckOthers(p, v);
    }

    private void uncheckOthers(ViewGroup p, View v) {
        int n = p.getChildCount();
        for (int i = 0; i < n; i++) {
            final View child = p.getChildAt(i);
            if (child instanceof ViewGroup) {
                uncheckOthers((ViewGroup) child, v);
            }
            if (v != child && child instanceof ToggleButton) {
                ToggleButton t = (ToggleButton) child;
                t.setChecked(false);

            }
        }
    }
}
