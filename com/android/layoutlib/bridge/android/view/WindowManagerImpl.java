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
package com.android.layoutlib.bridge.android.view;

import android.app.ResourcesManager;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.Display;
import android.view.Display.Mode;
import android.view.DisplayAdjustments;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.InsetsState;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.WindowMetrics;

public class WindowManagerImpl implements WindowManager {

    private final Context mContext;
    private final DisplayMetrics mMetrics;
    private final Display mDisplay;

    public WindowManagerImpl(Context context, DisplayMetrics metrics) {
        mContext = context;
        mMetrics = metrics;

        DisplayInfo info = new DisplayInfo();
        info.logicalHeight = mMetrics.heightPixels;
        info.logicalWidth = mMetrics.widthPixels;
        info.supportedModes = new Mode[] {
                new Mode(0, mMetrics.widthPixels, mMetrics.heightPixels, 60f)
        };
        info.logicalDensityDpi = mMetrics.densityDpi;
        mDisplay = new Display(null, Display.DEFAULT_DISPLAY, info,
                DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
    }

    @Override
    public Display getDefaultDisplay() {
        return mDisplay;
    }


    @Override
    public void addView(View arg0, android.view.ViewGroup.LayoutParams arg1) {
        // pass
    }

    @Override
    public void removeView(View arg0) {
        // pass
    }

    @Override
    public void updateViewLayout(View arg0, android.view.ViewGroup.LayoutParams arg1) {
        // pass
    }


    @Override
    public void removeViewImmediate(View arg0) {
        // pass
    }

    @Override
    public void requestAppKeyboardShortcuts(
            KeyboardShortcutsReceiver receiver, int deviceId) {
    }

    @Override
    public Region getCurrentImeTouchRegion() {
        return null;
    }

    @Override
    public void setShouldShowWithInsecureKeyguard(int displayId, boolean shouldShow) {
        // pass
    }

    @Override
    public void setShouldShowSystemDecors(int displayId, boolean shouldShow) {
        // pass
    }

    @Override
    public void setShouldShowIme(int displayId, boolean shouldShow) {
        // pass
    }

    @Override
    public WindowMetrics getCurrentWindowMetrics() {
        final Rect bound = getCurrentBounds(mContext);

        return new WindowMetrics(bound, computeWindowInsets());
    }

    private static Rect getCurrentBounds(Context context) {
        synchronized (ResourcesManager.getInstance()) {
            return context.getResources().getConfiguration().windowConfiguration.getBounds();
        }
    }

    @Override
    public WindowMetrics getMaximumWindowMetrics() {
        return new WindowMetrics(getMaximumBounds(), computeWindowInsets());
    }

    private Rect getMaximumBounds() {
        final Point displaySize = new Point();
        mDisplay.getRealSize(displaySize);
        return new Rect(0, 0, displaySize.x, displaySize.y);
    }

    private WindowInsets computeWindowInsets() {
        try {
            final Rect systemWindowInsets = new Rect();
            final Rect stableInsets = new Rect();
            final DisplayCutout.ParcelableWrapper displayCutout =
                    new DisplayCutout.ParcelableWrapper();
            final InsetsState insetsState = new InsetsState();
            WindowManagerGlobal.getWindowManagerService().getWindowInsets(
                    new WindowManager.LayoutParams(), mContext.getDisplayId(), systemWindowInsets,
                    stableInsets, displayCutout, insetsState);
            return new WindowInsets.Builder()
                    .setSystemWindowInsets(Insets.of(systemWindowInsets))
                    .setStableInsets(Insets.of(stableInsets))
                    .setDisplayCutout(displayCutout.get()).build();
        } catch (RemoteException ignore) {
        }
        return null;
    }
}
