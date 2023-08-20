/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.vpndialogs;

import android.content.DialogInterface;
import android.net.VpnManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.android.internal.app.AlertActivity;
import com.android.internal.net.VpnConfig;

import java.io.DataInputStream;
import java.io.FileInputStream;

public class ManageDialog extends AlertActivity implements
        DialogInterface.OnClickListener, Handler.Callback {
    private static final String TAG = "VpnManage";

    private VpnConfig mConfig;

    private VpnManager mVm;

    private TextView mDuration;
    private TextView mDataTransmitted;
    private TextView mDataReceived;
    private boolean mDataRowsHidden;

    private Handler mHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            mVm = getSystemService(VpnManager.class);

            mConfig = mVm.getVpnConfig(UserHandle.myUserId());

            // mConfig can be null if we are a restricted user, in that case don't show this dialog
            if (mConfig == null) {
                finish();
                return;
            }

            View view = View.inflate(this, R.layout.manage, null);
            if (mConfig.session != null) {
                ((TextView) view.findViewById(R.id.session)).setText(mConfig.session);
            }
            mDuration = (TextView) view.findViewById(R.id.duration);
            mDataTransmitted = (TextView) view.findViewById(R.id.data_transmitted);
            mDataReceived = (TextView) view.findViewById(R.id.data_received);
            mDataRowsHidden = true;

            if (mConfig.legacy) {
                mAlertParams.mTitle = getText(R.string.legacy_title);
            } else {
                mAlertParams.mTitle = VpnConfig.getVpnLabel(this, mConfig.user);
            }
            if (mConfig.configureIntent != null) {
                mAlertParams.mPositiveButtonText = getText(R.string.configure);
                mAlertParams.mPositiveButtonListener = this;
            }
            mAlertParams.mNeutralButtonText = getText(R.string.disconnect);
            mAlertParams.mNeutralButtonListener = this;
            mAlertParams.mNegativeButtonText = getText(android.R.string.cancel);
            mAlertParams.mNegativeButtonListener = this;
            mAlertParams.mView = view;
            setupAlert();

            if (mHandler == null) {
                mHandler = new Handler(this);
            }
            mHandler.sendEmptyMessage(0);
        } catch (Exception e) {
            Log.e(TAG, "onResume", e);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (!isFinishing()) {
            finish();
        }
        super.onDestroy();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        try {
            if (which == DialogInterface.BUTTON_POSITIVE) {
                mConfig.configureIntent.send();
            } else if (which == DialogInterface.BUTTON_NEUTRAL) {
                final int myUserId = UserHandle.myUserId();
                if (mConfig.legacy) {
                    mVm.prepareVpn(VpnConfig.LEGACY_VPN, VpnConfig.LEGACY_VPN, myUserId);
                } else {
                    mVm.prepareVpn(mConfig.user, VpnConfig.LEGACY_VPN, myUserId);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "onClick", e);
            finish();
        }
    }

    @Override
    public boolean handleMessage(Message message) {
        mHandler.removeMessages(0);

        if (!isFinishing()) {
            if (mConfig.startTime != -1) {
                long seconds = (SystemClock.elapsedRealtime() - mConfig.startTime) / 1000;
                mDuration.setText(String.format("%02d:%02d:%02d",
                        seconds / 3600, seconds / 60 % 60, seconds % 60));
            }

            String[] numbers = getNumbers();
            if (numbers != null) {
                // First unhide the related data rows.
                if (mDataRowsHidden) {
                    findViewById(R.id.data_transmitted_row).setVisibility(View.VISIBLE);
                    findViewById(R.id.data_received_row).setVisibility(View.VISIBLE);
                    mDataRowsHidden = false;
                }

                // [1] and [2] are received data in bytes and packets.
                mDataReceived.setText(getString(R.string.data_value_format,
                        numbers[1], numbers[2]));

                // [9] and [10] are transmitted data in bytes and packets.
                mDataTransmitted.setText(getString(R.string.data_value_format,
                        numbers[9], numbers[10]));
            }
            mHandler.sendEmptyMessageDelayed(0, 1000);
        }
        return true;
    }

    private String[] getNumbers() {
        DataInputStream in = null;
        try {
            // See dev_seq_printf_stats() in net/core/dev.c.
            in = new DataInputStream(new FileInputStream("/proc/net/dev"));
            String prefix = mConfig.interfaze + ':';

            while (true) {
                String line = in.readLine().trim();
                if (line.startsWith(prefix)) {
                    String[] numbers = line.substring(prefix.length()).split(" +");
                    for (int i = 1; i < 17; ++i) {
                        if (!numbers[i].equals("0")) {
                            return numbers;
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) {
            // ignore
        } finally {
            try {
                in.close();
            } catch (Exception e) {
                // ignore
            }
        }
        return null;
    }
}
