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

package com.android.setupwizardlib.test;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.InputQueue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceHolder.Callback2;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowManager;

import com.android.setupwizardlib.util.SystemBarHelper;

public class SystemBarHelperTest extends AndroidTestCase {

    private static final int STATUS_BAR_DISABLE_BACK = 0x00400000;

    @SuppressLint("InlinedApi")
    private static final int DEFAULT_IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;

    @SuppressLint("InlinedApi")
    private static final int DIALOG_IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

    @SmallTest
    public void testAddVisibilityFlagView() {
        final View view = createViewWithSystemUiVisibility(0x456);
        SystemBarHelper.addVisibilityFlag(view, 0x1400);
        if (VERSION.SDK_INT >= VERSION_CODES.HONEYCOMB) {
            // Check that result is 0x1456, because 0x1400 | 0x456 = 0x1456.
            assertEquals("View visibility should be 0x1456", 0x1456, view.getSystemUiVisibility());
        }
    }

    @SmallTest
    public void testRemoveVisibilityFlagView() {
        final View view = createViewWithSystemUiVisibility(0x456);
        SystemBarHelper.removeVisibilityFlag(view, 0x1400);
        if (VERSION.SDK_INT >= VERSION_CODES.HONEYCOMB) {
            // Check that result is 0x56, because 0x456 & ~0x1400 = 0x56.
            assertEquals("View visibility should be 0x56", 0x56, view.getSystemUiVisibility());
        }
    }

    @SmallTest
    public void testAddVisibilityFlagWindow() {
        final Window window = createWindowWithSystemUiVisibility(0x456);
        SystemBarHelper.addVisibilityFlag(window, 0x1400);
        if (VERSION.SDK_INT >= VERSION_CODES.HONEYCOMB) {
            // Check that result is 0x1456 = 0x1400 | 0x456.
            assertEquals("View visibility should be 0x1456", 0x1456,
                    window.getAttributes().systemUiVisibility);
        }
    }

    @SmallTest
    public void testRemoveVisibilityFlagWindow() {
        final Window window = createWindowWithSystemUiVisibility(0x456);
        SystemBarHelper.removeVisibilityFlag(window, 0x1400);
        if (VERSION.SDK_INT >= VERSION_CODES.HONEYCOMB) {
            // Check that result is 0x56 = 0x456 & ~0x1400.
            assertEquals("View visibility should be 0x56", 0x56,
                    window.getAttributes().systemUiVisibility);
        }
    }

    @SmallTest
    public void testHideSystemBarsWindow() {
        final Window window = createWindowWithSystemUiVisibility(0x456);
        SystemBarHelper.hideSystemBars(window);
        if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
            assertEquals("DEFAULT_IMMERSIVE_FLAGS should be added to window's systemUiVisibility",
                    DEFAULT_IMMERSIVE_FLAGS | 0x456,
                    window.getAttributes().systemUiVisibility);
            assertEquals(
                    "DEFAULT_IMMERSIVE_FLAGS should be added to decorView's systemUiVisibility",
                    DEFAULT_IMMERSIVE_FLAGS | 0x456,
                    window.getDecorView().getSystemUiVisibility());
        }
    }

    @SmallTest
    public void testHideSystemBarsDialog() {
        final Dialog dialog = new Dialog(mContext);
        if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
            final WindowManager.LayoutParams attrs = dialog.getWindow().getAttributes();
            attrs.systemUiVisibility = 0x456;
            dialog.getWindow().setAttributes(attrs);
        }

        SystemBarHelper.hideSystemBars(dialog);
        if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
            assertEquals("DIALOG_IMMERSIVE_FLAGS should be added to window's systemUiVisibility",
                    DIALOG_IMMERSIVE_FLAGS | 0x456,
                    dialog.getWindow().getAttributes().systemUiVisibility);
        }
    }

    @SmallTest
    public void testSetBackButtonVisibleTrue() {
        final Window window = createWindowWithSystemUiVisibility(0x456);
        SystemBarHelper.setBackButtonVisible(window, true);
        if (VERSION.SDK_INT >= VERSION_CODES.HONEYCOMB) {
            assertEquals("View visibility should be 0x456", 0x456,
                    window.getAttributes().systemUiVisibility);
        }
    }

    @SmallTest
    public void testSetBackButtonVisibleFalse() {
        final Window window = createWindowWithSystemUiVisibility(0x456);
        SystemBarHelper.setBackButtonVisible(window, false);
        if (VERSION.SDK_INT >= VERSION_CODES.HONEYCOMB) {
            assertEquals("STATUS_BAR_DISABLE_BACK should be added to systemUiVisibility",
                    0x456 | STATUS_BAR_DISABLE_BACK, window.getAttributes().systemUiVisibility);
        }
    }

    private View createViewWithSystemUiVisibility(int vis) {
        final View view = new View(getContext());
        if (VERSION.SDK_INT >= VERSION_CODES.HONEYCOMB) {
            view.setSystemUiVisibility(vis);
        }
        return view;
    }

    private Window createWindowWithSystemUiVisibility(int vis) {
        final Window window = new TestWindow(getContext(), createViewWithSystemUiVisibility(vis));
        if (VERSION.SDK_INT >= VERSION_CODES.HONEYCOMB) {
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.systemUiVisibility = vis;
            window.setAttributes(attrs);
        }
        return window;
    }

    private static class TestWindow extends Window {

        private View mDecorView;

        public TestWindow(Context context, View decorView) {
            super(context);
            mDecorView = decorView;
        }

        @Override
        public void takeSurface(Callback2 callback2) {

        }

        @Override
        public void takeInputQueue(InputQueue.Callback callback) {

        }

        @Override
        public boolean isFloating() {
            return false;
        }

        @Override
        public void setContentView(int i) {

        }

        @Override
        public void setContentView(View view) {

        }

        @Override
        public void setContentView(View view, LayoutParams layoutParams) {

        }

        @Override
        public void addContentView(View view, LayoutParams layoutParams) {

        }

        @Override
        public View getCurrentFocus() {
            return null;
        }

        @Override
        public LayoutInflater getLayoutInflater() {
            return LayoutInflater.from(getContext());
        }

        @Override
        public void setTitle(CharSequence charSequence) {

        }

        @Override
        public void setTitleColor(int i) {

        }

        @Override
        public void openPanel(int i, KeyEvent keyEvent) {

        }

        @Override
        public void closePanel(int i) {

        }

        @Override
        public void togglePanel(int i, KeyEvent keyEvent) {

        }

        @Override
        public void invalidatePanelMenu(int i) {

        }

        @Override
        public boolean performPanelShortcut(int i, int i2, KeyEvent keyEvent, int i3) {
            return false;
        }

        @Override
        public boolean performPanelIdentifierAction(int i, int i2, int i3) {
            return false;
        }

        @Override
        public void closeAllPanels() {

        }

        @Override
        public boolean performContextMenuIdentifierAction(int i, int i2) {
            return false;
        }

        @Override
        public void onConfigurationChanged(Configuration configuration) {

        }

        @Override
        public void setBackgroundDrawable(Drawable drawable) {

        }

        @Override
        public void setFeatureDrawableResource(int i, int i2) {

        }

        @Override
        public void setFeatureDrawableUri(int i, Uri uri) {

        }

        @Override
        public void setFeatureDrawable(int i, Drawable drawable) {

        }

        @Override
        public void setFeatureDrawableAlpha(int i, int i2) {

        }

        @Override
        public void setFeatureInt(int i, int i2) {

        }

        @Override
        public void takeKeyEvents(boolean b) {

        }

        @Override
        public boolean superDispatchKeyEvent(KeyEvent keyEvent) {
            return false;
        }

        @Override
        public boolean superDispatchKeyShortcutEvent(KeyEvent keyEvent) {
            return false;
        }

        @Override
        public boolean superDispatchTouchEvent(MotionEvent motionEvent) {
            return false;
        }

        @Override
        public boolean superDispatchTrackballEvent(MotionEvent motionEvent) {
            return false;
        }

        @Override
        public boolean superDispatchGenericMotionEvent(MotionEvent motionEvent) {
            return false;
        }

        @Override
        public View getDecorView() {
            return mDecorView;
        }

        @Override
        public View peekDecorView() {
            return mDecorView;
        }

        @Override
        public Bundle saveHierarchyState() {
            return null;
        }

        @Override
        public void restoreHierarchyState(Bundle bundle) {

        }

        @Override
        protected void onActive() {

        }

        @Override
        public void setChildDrawable(int i, Drawable drawable) {

        }

        @Override
        public void setChildInt(int i, int i2) {

        }

        @Override
        public boolean isShortcutKey(int i, KeyEvent keyEvent) {
            return false;
        }

        @Override
        public void setVolumeControlStream(int i) {

        }

        @Override
        public int getVolumeControlStream() {
            return 0;
        }

        @Override
        public int getStatusBarColor() {
            return 0;
        }

        @Override
        public void setStatusBarColor(int i) {

        }

        @Override
        public int getNavigationBarColor() {
            return 0;
        }

        @Override
        public void setNavigationBarColor(int i) {

        }
    }
}
