/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.accessibility.dialog;

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;

import com.android.internal.R;
import com.android.internal.accessibility.common.ShortcutConstants.AccessibilityFragmentType;
import com.android.internal.accessibility.common.ShortcutConstants.ShortcutMenuMode;
import com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType;
import com.android.internal.accessibility.dialog.TargetAdapter.ViewHolder;

/** Extension for {@link AccessibilityTarget} with {@link AccessibilityFragmentType#TOGGLE} type. */
class ToggleAllowListingFeatureTarget extends AccessibilityTarget {

    ToggleAllowListingFeatureTarget(
            Context context,
            @UserShortcutType int shortcutType,
            boolean isShortcutSwitched,
            String id,
            int uid,
            CharSequence label,
            Drawable icon,
            String key) {
        super(
                context,
                shortcutType,
                AccessibilityFragmentType.TOGGLE,
                isShortcutSwitched,
                id,
                uid,
                label,
                icon,
                key);

        final boolean isStateOn = isFeatureEnabled();
        final int statusResId =
                isStateOn
                        ? R.string.accessibility_shortcut_menu_item_status_on
                        : R.string.accessibility_shortcut_menu_item_status_off;
        setIsStateOn(isStateOn);
        setStateDescription(getContext().getString(statusResId));
    }

    @Override
    public void updateActionItem(
            @NonNull ViewHolder holder, @ShortcutMenuMode int shortcutMenuMode) {
        super.updateActionItem(holder, shortcutMenuMode);

        final boolean isEditMenuMode = shortcutMenuMode == ShortcutMenuMode.EDIT;
        holder.mStatusView.setVisibility(isEditMenuMode ? View.GONE : View.VISIBLE);
        holder.mStatusView.setText(getStateDescription());
    }

    @Override
    public boolean isToggleable() {
        return true;
    }

    private boolean isFeatureEnabled() {
        // Check for value greater than 0 to handle settings like bounce keys and slow keys, where
        // the setting value also represents the time delay.
        return Settings.Secure.getIntForUser(
                        getContext().getContentResolver(),
                        getKey(),
                        /* settingsValueOff */ 0,
                        UserHandle.USER_CURRENT)
                > 0;
    }
}
