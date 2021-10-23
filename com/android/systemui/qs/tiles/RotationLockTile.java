/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.qs.tiles;

import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.view.View;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockController.RotationLockControllerCallback;

import javax.inject.Inject;

/** Quick settings tile: Rotation **/
public class RotationLockTile extends QSTileImpl<BooleanState> {

    private final Icon mIcon = ResourceIcon.get(com.android.internal.R.drawable.ic_qs_auto_rotate);
    private final RotationLockController mController;

    @Inject
    public RotationLockTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            RotationLockController rotationLockController
    ) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mController = rotationLockController;
        mController.observe(this, mCallback);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_AUTO_ROTATE_SETTINGS);
    }

    @Override
    protected void handleClick(@Nullable View view) {
        final boolean newState = !mState.value;
        mController.setRotationLocked(!newState);
        refreshState(newState);
    }

    @Override
    public CharSequence getTileLabel() {
        return getState().label;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean rotationLocked = mController.isRotationLocked();

        state.value = !rotationLocked;
        state.label = mContext.getString(R.string.quick_settings_rotation_unlocked_label);
        state.icon = mIcon;
        state.contentDescription = getAccessibilityString(rotationLocked);
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }

    public static boolean isCurrentOrientationLockPortrait(RotationLockController controller,
            Resources resources) {
        int lockOrientation = controller.getRotationLockOrientation();
        if (lockOrientation == Configuration.ORIENTATION_UNDEFINED) {
            // Freely rotating device; use current rotation
            return resources.getConfiguration().orientation
                    != Configuration.ORIENTATION_LANDSCAPE;
        } else {
            return lockOrientation != Configuration.ORIENTATION_LANDSCAPE;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_ROTATIONLOCK;
    }

    /**
     * Get the correct accessibility string based on the state
     *
     * @param locked Whether or not rotation is locked.
     */
    private String getAccessibilityString(boolean locked) {
        return mContext.getString(R.string.accessibility_quick_settings_rotation);
    }

    @Override
    protected String composeChangeAnnouncement() {
        return getAccessibilityString(mState.value);
    }

    private final RotationLockControllerCallback mCallback = new RotationLockControllerCallback() {
        @Override
        public void onRotationLockStateChanged(boolean rotationLocked, boolean affordanceVisible) {
            refreshState(rotationLocked);
        }
    };
}
