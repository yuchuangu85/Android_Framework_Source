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

package com.android.wm.shell.legacysplitscreen;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SLIPPERY;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.SHELL_ROOT_LAYER_DIVIDER;

import android.graphics.PixelFormat;
import android.graphics.Region;
import android.os.Binder;
import android.view.View;
import android.view.WindowManager;

import com.android.wm.shell.common.SystemWindows;

/**
 * Manages the window parameters of the docked stack divider.
 */
final class DividerWindowManager {

    private static final String WINDOW_TITLE = "DockedStackDivider";

    final SystemWindows mSystemWindows;
    private WindowManager.LayoutParams mLp;
    private View mView;

    DividerWindowManager(SystemWindows systemWindows) {
        mSystemWindows = systemWindows;
    }

    /** Add a divider view */
    void add(View view, int width, int height, int displayId) {
        mLp = new WindowManager.LayoutParams(
                width, height, TYPE_DOCK_DIVIDER,
                FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL
                        | FLAG_WATCH_OUTSIDE_TOUCH | FLAG_SPLIT_TOUCH | FLAG_SLIPPERY,
                PixelFormat.TRANSLUCENT);
        mLp.token = new Binder();
        mLp.setTitle(WINDOW_TITLE);
        mLp.privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION | PRIVATE_FLAG_TRUSTED_OVERLAY;
        mLp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        mSystemWindows.addView(view, mLp, displayId, SHELL_ROOT_LAYER_DIVIDER);
        mView = view;
    }

    void remove() {
        if (mView != null) {
            mSystemWindows.removeView(mView);
        }
        mView = null;
    }

    void setSlippery(boolean slippery) {
        boolean changed = false;
        if (slippery && (mLp.flags & FLAG_SLIPPERY) == 0) {
            mLp.flags |= FLAG_SLIPPERY;
            changed = true;
        } else if (!slippery && (mLp.flags & FLAG_SLIPPERY) != 0) {
            mLp.flags &= ~FLAG_SLIPPERY;
            changed = true;
        }
        if (changed) {
            mSystemWindows.updateViewLayout(mView, mLp);
        }
    }

    void setTouchable(boolean touchable) {
        if (mView == null) {
            return;
        }
        boolean changed = false;
        if (!touchable && (mLp.flags & FLAG_NOT_TOUCHABLE) == 0) {
            mLp.flags |= FLAG_NOT_TOUCHABLE;
            changed = true;
        } else if (touchable && (mLp.flags & FLAG_NOT_TOUCHABLE) != 0) {
            mLp.flags &= ~FLAG_NOT_TOUCHABLE;
            changed = true;
        }
        if (changed) {
            mSystemWindows.updateViewLayout(mView, mLp);
        }
    }

    /** Sets the touch region to `touchRegion`. Use null to unset.*/
    void setTouchRegion(Region touchRegion) {
        if (mView == null) {
            return;
        }
        mSystemWindows.setTouchableRegion(mView, touchRegion);
    }
}
