/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.globalactions;

import static android.app.StatusBarManager.DISABLE2_GLOBAL_ACTIONS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import android.annotation.Nullable;
import android.annotation.StringRes;
import android.app.Dialog;
import android.content.Context;
import android.os.PowerManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.internal.R;
import com.android.settingslib.Utils;
import com.android.systemui.plugins.GlobalActions;
import com.android.systemui.scrim.ScrimDrawable;
import com.android.systemui.statusbar.BlurUtils;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;

import dagger.Lazy;

public class GlobalActionsImpl implements GlobalActions, CommandQueue.Callbacks {

    private final Context mContext;
    private final Lazy<GlobalActionsDialogLite> mGlobalActionsDialogLazy;
    private final KeyguardStateController mKeyguardStateController;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final BlurUtils mBlurUtils;
    private final CommandQueue mCommandQueue;
    private GlobalActionsDialogLite mGlobalActionsDialog;
    private boolean mDisabled;

    @Inject
    public GlobalActionsImpl(Context context, CommandQueue commandQueue,
            Lazy<GlobalActionsDialogLite> globalActionsDialogLazy, BlurUtils blurUtils,
            KeyguardStateController keyguardStateController,
            DeviceProvisionedController deviceProvisionedController) {
        mContext = context;
        mGlobalActionsDialogLazy = globalActionsDialogLazy;
        mKeyguardStateController = keyguardStateController;
        mDeviceProvisionedController = deviceProvisionedController;
        mCommandQueue = commandQueue;
        mBlurUtils = blurUtils;
        mCommandQueue.addCallback(this);
    }

    @Override
    public void destroy() {
        mCommandQueue.removeCallback(this);
        if (mGlobalActionsDialog != null) {
            mGlobalActionsDialog.destroy();
            mGlobalActionsDialog = null;
        }
    }

    @Override
    public void showGlobalActions(GlobalActionsManager manager) {
        if (mDisabled) return;
        mGlobalActionsDialog = mGlobalActionsDialogLazy.get();
        mGlobalActionsDialog.showOrHideDialog(mKeyguardStateController.isShowing(),
                mDeviceProvisionedController.isDeviceProvisioned());
    }

    @Override
    public void showShutdownUi(boolean isReboot, String reason) {
        ScrimDrawable background = new ScrimDrawable();

        final Dialog d = new Dialog(mContext,
                com.android.systemui.R.style.Theme_SystemUI_Dialog_GlobalActions);

        d.setOnShowListener(dialog -> {
            if (mBlurUtils.supportsBlursOnWindows()) {
                int backgroundAlpha = (int) (ScrimController.BUSY_SCRIM_ALPHA * 255);
                background.setAlpha(backgroundAlpha);
                mBlurUtils.applyBlur(d.getWindow().getDecorView().getViewRootImpl(),
                        mBlurUtils.blurRadiusOfRatio(1), backgroundAlpha == 255);
            } else {
                float backgroundAlpha = mContext.getResources().getFloat(
                        com.android.systemui.R.dimen.shutdown_scrim_behind_alpha);
                background.setAlpha((int) (backgroundAlpha * 255));
            }
        });

        // Window initialization
        Window window = d.getWindow();
        window.requestFeature(Window.FEATURE_NO_TITLE);
        window.getAttributes().systemUiVisibility |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        // Inflate the decor view, so the attributes below are not overwritten by the theme.
        window.getDecorView();
        window.getAttributes().width = ViewGroup.LayoutParams.MATCH_PARENT;
        window.getAttributes().height = ViewGroup.LayoutParams.MATCH_PARENT;
        window.getAttributes().layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        window.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
        window.getAttributes().setFitInsetsTypes(0 /* types */);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        window.setBackgroundDrawable(background);
        window.setWindowAnimations(com.android.systemui.R.style.Animation_ShutdownUi);

        d.setContentView(R.layout.shutdown_dialog);
        d.setCancelable(false);

        int color;
        if (mBlurUtils.supportsBlursOnWindows()) {
            color = Utils.getColorAttrDefaultColor(mContext,
                    com.android.systemui.R.attr.wallpaperTextColor);
        } else {
            color = mContext.getResources().getColor(
                    com.android.systemui.R.color.global_actions_shutdown_ui_text);
        }

        ProgressBar bar = d.findViewById(R.id.progress);
        bar.getIndeterminateDrawable().setTint(color);

        TextView reasonView = d.findViewById(R.id.text1);
        TextView messageView = d.findViewById(R.id.text2);

        reasonView.setTextColor(color);
        messageView.setTextColor(color);

        messageView.setText(getRebootMessage(isReboot, reason));
        String rebootReasonMessage = getReasonMessage(reason);
        if (rebootReasonMessage != null) {
            reasonView.setVisibility(View.VISIBLE);
            reasonView.setText(rebootReasonMessage);
        }

        d.show();
    }

    @StringRes
    private int getRebootMessage(boolean isReboot, @Nullable String reason) {
        if (reason != null && reason.startsWith(PowerManager.REBOOT_RECOVERY_UPDATE)) {
            return R.string.reboot_to_update_reboot;
        } else if (reason != null && reason.equals(PowerManager.REBOOT_RECOVERY)) {
            return R.string.reboot_to_reset_message;
        } else if (isReboot) {
            return R.string.reboot_to_reset_message;
        } else {
            return R.string.shutdown_progress;
        }
    }

    @Nullable
    private String getReasonMessage(@Nullable String reason) {
        if (reason != null && reason.startsWith(PowerManager.REBOOT_RECOVERY_UPDATE)) {
            return mContext.getString(R.string.reboot_to_update_title);
        } else if (reason != null && reason.equals(PowerManager.REBOOT_RECOVERY)) {
            return mContext.getString(R.string.reboot_to_reset_title);
        } else {
            return null;
        }
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_GLOBAL_ACTIONS) != 0;
        if (displayId != mContext.getDisplayId() || disabled == mDisabled) return;
        mDisabled = disabled;
        if (disabled && mGlobalActionsDialog != null) {
            mGlobalActionsDialog.dismissDialog();
        }
    }
}
