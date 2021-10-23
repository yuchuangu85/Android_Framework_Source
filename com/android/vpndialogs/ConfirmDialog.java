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

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.VpnManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.Html;
import android.text.Html.ImageGetter;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.android.internal.app.AlertActivity;
import com.android.internal.net.VpnConfig;

public class ConfirmDialog extends AlertActivity
        implements DialogInterface.OnClickListener, ImageGetter {
    private static final String TAG = "VpnConfirm";

    @VpnManager.VpnType private final int mVpnType;

    private String mPackage;

    private VpnManager mVm;

    public ConfirmDialog() {
        this(VpnManager.TYPE_VPN_SERVICE);
    }

    public ConfirmDialog(@VpnManager.VpnType int vpnType) {
        mVpnType = vpnType;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPackage = getCallingPackage();
        mVm = getSystemService(VpnManager.class);

        if (mVm.prepareVpn(mPackage, null, UserHandle.myUserId())) {
            setResult(RESULT_OK);
            finish();
            return;
        }
        if (UserManager.get(this).hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN)) {
            finish();
            return;
        }
        final String alwaysOnVpnPackage = mVm.getAlwaysOnVpnPackageForUser(UserHandle.myUserId());
        // Can't prepare new vpn app when another vpn is always-on
        if (alwaysOnVpnPackage != null && !alwaysOnVpnPackage.equals(mPackage)) {
            finish();
            return;
        }
        View view = View.inflate(this, R.layout.confirm, null);
        ((TextView) view.findViewById(R.id.warning)).setText(
                Html.fromHtml(getString(R.string.warning, getVpnLabel()),
                        this, null /* tagHandler */));
        mAlertParams.mTitle = getText(R.string.prompt);
        mAlertParams.mPositiveButtonText = getText(android.R.string.ok);
        mAlertParams.mPositiveButtonListener = this;
        mAlertParams.mNegativeButtonText = getText(android.R.string.cancel);
        mAlertParams.mView = view;
        setupAlert();

        getWindow().setCloseOnTouchOutside(false);
        getWindow().addPrivateFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        Button button = mAlert.getButton(DialogInterface.BUTTON_POSITIVE);
        button.setFilterTouchesWhenObscured(true);
    }

    private CharSequence getVpnLabel() {
        try {
            return VpnConfig.getVpnLabel(this, mPackage);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Drawable getDrawable(String source) {
        // Should only reach this when fetching the VPN icon for the warning string.
        final Drawable icon = getDrawable(R.drawable.ic_vpn_dialog);
        icon.setBounds(0, 0, icon.getIntrinsicWidth(), icon.getIntrinsicHeight());

        final TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true)) {
            icon.setTint(getColor(tv.resourceId));
        } else {
            Log.w(TAG, "Unable to resolve theme color");
        }

        return icon;
    }

    @Override
    public void onBackPressed() {
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        try {
            if (mVm.prepareVpn(null, mPackage, UserHandle.myUserId())) {
                // Authorize this app to initiate VPN connections in the future without user
                // intervention.
                mVm.setVpnPackageAuthorization(mPackage, UserHandle.myUserId(), mVpnType);
                setResult(RESULT_OK);
            }
        } catch (Exception e) {
            Log.e(TAG, "onClick", e);
        }
    }
}
