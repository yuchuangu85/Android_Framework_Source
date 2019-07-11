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
package com.android.example.rscamera;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.ViewFlipper;

import com.android.example.rscamera.rscamera.R;

import java.text.DecimalFormat;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Main Activity for this app
 * It presents a ui for setting ISO, Shutter speed, and focus
 */
public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final long ONE_SECOND = 1000000000;
    private CameraView mPreviewView;
    private ViewFlipper mViewFlipper;
    private Button mSpeedButton;
    private Button mISOButton;
    private Button mFocusButton;
    private Timer mTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mSpeedButton = (Button) findViewById(R.id.speed);
        mISOButton = (Button) findViewById(R.id.iso);
        mFocusButton = (Button) findViewById(R.id.focus);
        mPreviewView = (CameraView) findViewById(R.id.preview);
        mViewFlipper = (ViewFlipper) findViewById(R.id.viewFlipper);
        SeekBar seekBar = (SeekBar) findViewById(R.id.focusbar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                mPreviewView.setFocusInMeters(seekBar.getProgress() / 10.f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
        mPreviewView.setParametersChangedCallback(new CameraView.ParametersChangedCallback() {
            @Override
            public void parametersChanged() {
                update_buttons();
            }
        });
        mTimer = new Timer();

        mTimer.scheduleAtFixedRate(new TimerTask() {

            @Override
            public void run() {
                // TODO Auto-generated method stub
                runOnUiThread(new Runnable() {
                    public void run() {
                        setTitle("RS Camera (" + mPreviewView.getFps() + "fps)");
                    }
                });

            }
        }, 250, 250);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mPreviewView.resume(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPreviewView.pause();
    }

    public void setShutterSpeed(View v) {
        if (mPreviewView.isAutoExposure()) {
            mPreviewView.setAutoExposure(false);
            mPreviewView.setMode(CameraView.MODE_SPEED);
        } else {
            mPreviewView.setMode(CameraView.MODE_NONE);
            mPreviewView.setAutoExposure(true);
        }
        update_buttons();
    }

    public void setISO(View v) {
        if (mPreviewView.isAutoExposure()) {
            mPreviewView.setAutoExposure(false);
            mPreviewView.setMode(CameraView.MODE_ISO);
        } else {
            mPreviewView.setMode(CameraView.MODE_NONE);
            mPreviewView.setAutoExposure(true);
        }
        update_buttons();
    }

    public void setFocus(View v) {
        if (mPreviewView.isAutofocus()) {
            mPreviewView.setAutofocus(false);
            mPreviewView.setMode(CameraView.MODE_FOCUS);
            mViewFlipper.setInAnimation(this, R.anim.slide_in_from_left);
            mViewFlipper.setOutAnimation(this, R.anim.slide_out_to_right);
            mViewFlipper.showNext();
        } else {
            mPreviewView.setMode(CameraView.MODE_NONE);
            mPreviewView.setAutofocus(true);
        }
        update_buttons();
    }

    public void back(View v) {
        mViewFlipper.setInAnimation(this, R.anim.slide_in_from_left);
        mViewFlipper.setOutAnimation(this, R.anim.slide_out_to_right);
        mViewFlipper.showNext();
    }

    public void capture(View v) {
        mPreviewView.takePicture();
    }

    private void update_buttons() {
        byte mode = mPreviewView.getMode();
        mSpeedButton.setElevation(mode == CameraView.MODE_SPEED ? 20 : 0);
        mFocusButton.setElevation(mode == CameraView.MODE_FOCUS ? 20 : 0);
        mISOButton.setElevation(mode == CameraView.MODE_ISO ? 20 : 0);

        String a;
        a = (mPreviewView.isAutoExposure()) ? "A " : "  ";
        if (ONE_SECOND > mPreviewView.getExposure()) {
            mSpeedButton.setText(a + 1 + "/" + (ONE_SECOND / mPreviewView.getExposure()) + "s");
        } else {
            mSpeedButton.setText(a + (mPreviewView.getExposure() / ONE_SECOND) + "\"s");

        }
        a = (mPreviewView.isAutofocus()) ? "A " : "  ";
        DecimalFormat df = new DecimalFormat("#.###");
        float d = mPreviewView.getFocusDist();
        if (d < 0.01) {
            d = 0;
        }
        mFocusButton.setText(a + df.format(0.1 / d) + " m");
        a = (mPreviewView.isAutoExposure()) ? "A ISO " : "  ISO ";
        mISOButton.setText(a + mPreviewView.getIso() + " M");
    }
}
