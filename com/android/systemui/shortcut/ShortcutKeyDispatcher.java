/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.shortcut;

import android.content.Context;
import android.content.res.Configuration;
import android.os.RemoteException;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.WindowManagerGlobal;

import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.systemui.SystemUI;
import com.android.systemui.dagger.SysUISingleton;
import com.android.wm.shell.legacysplitscreen.DividerView;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;

import java.util.Optional;

import javax.inject.Inject;

/**
 * Dispatches shortcut to System UI components
 */
@SysUISingleton
public class ShortcutKeyDispatcher extends SystemUI
        implements ShortcutKeyServiceProxy.Callbacks {

    private static final String TAG = "ShortcutKeyDispatcher";
    private final Optional<LegacySplitScreen> mSplitScreenOptional;

    private ShortcutKeyServiceProxy mShortcutKeyServiceProxy = new ShortcutKeyServiceProxy(this);
    private IWindowManager mWindowManagerService = WindowManagerGlobal.getWindowManagerService();

    protected final long META_MASK = ((long) KeyEvent.META_META_ON) << Integer.SIZE;
    protected final long ALT_MASK = ((long) KeyEvent.META_ALT_ON) << Integer.SIZE;
    protected final long CTRL_MASK = ((long) KeyEvent.META_CTRL_ON) << Integer.SIZE;
    protected final long SHIFT_MASK = ((long) KeyEvent.META_SHIFT_ON) << Integer.SIZE;

    protected final long SC_DOCK_LEFT = META_MASK | KeyEvent.KEYCODE_LEFT_BRACKET;
    protected final long SC_DOCK_RIGHT = META_MASK | KeyEvent.KEYCODE_RIGHT_BRACKET;

    @Inject
    public ShortcutKeyDispatcher(Context context, Optional<LegacySplitScreen> splitScreenOptional) {
        super(context);
        mSplitScreenOptional = splitScreenOptional;
    }

    /**
     * Registers a shortcut key to window manager.
     *
     * @param shortcutCode packed representation of shortcut key code and meta information
     */
    public void registerShortcutKey(long shortcutCode) {
        try {
            mWindowManagerService.registerShortcutKey(shortcutCode, mShortcutKeyServiceProxy);
        } catch (RemoteException e) {
            // Do nothing
        }
    }

    @Override
    public void onShortcutKeyPressed(long shortcutCode) {
        int orientation = mContext.getResources().getConfiguration().orientation;
        if ((shortcutCode == SC_DOCK_LEFT || shortcutCode == SC_DOCK_RIGHT)
                && orientation == Configuration.ORIENTATION_LANDSCAPE) {
            handleDockKey(shortcutCode);
        }
    }

    @Override
    public void start() {
        registerShortcutKey(SC_DOCK_LEFT);
        registerShortcutKey(SC_DOCK_RIGHT);
    }

    private void handleDockKey(long shortcutCode) {
        mSplitScreenOptional.ifPresent(splitScreen -> {
            if (splitScreen.isDividerVisible()) {
                // If there is already a docked window, we respond by resizing the docking pane.
                DividerView dividerView = splitScreen.getDividerView();
                DividerSnapAlgorithm snapAlgorithm = dividerView.getSnapAlgorithm();
                int dividerPosition = dividerView.getCurrentPosition();
                DividerSnapAlgorithm.SnapTarget currentTarget =
                        snapAlgorithm.calculateNonDismissingSnapTarget(dividerPosition);
                DividerSnapAlgorithm.SnapTarget target = (shortcutCode == SC_DOCK_LEFT)
                        ? snapAlgorithm.getPreviousTarget(currentTarget)
                        : snapAlgorithm.getNextTarget(currentTarget);
                dividerView.startDragging(true /* animate */, false /* touching */);
                dividerView.stopDragging(target.position, 0f, false /* avoidDismissStart */,
                        true /* logMetrics */);
                return;
            } else {
                splitScreen.splitPrimaryTask();
            }
        });
    }
}
