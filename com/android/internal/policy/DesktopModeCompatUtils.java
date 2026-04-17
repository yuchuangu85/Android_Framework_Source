/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.internal.policy;

import static android.content.pm.ActivityInfo.INSETS_DECOUPLED_CONFIGURATION_ENFORCED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import android.annotation.NonNull;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.Gravity;
import android.window.DesktopModeFlags;

/**
 * Utility functions for app compat in desktop windowing used by both window manager and System UI.
 * @hide
 */
public final class DesktopModeCompatUtils {

    /**
     * Whether the caption insets should be excluded from configuration for system to handle.
     * The caller should ensure the activity is in or entering desktop view.
     *
     * <p> The treatment is enabled when one of the following is true:
     * <li> The per-app override
     * {@link ActivityInfo#OVERRIDE_EXCLUDE_CAPTION_INSETS_FROM_APP_BOUNDS} is enabled and
     * app has not opted out.
     * <li> The task is not resizable and the top activity has configuration coupled with insets.
     */
    public static boolean shouldExcludeCaptionFromAppBounds(@NonNull ActivityInfo info,
            boolean isResizeable, boolean optOutEdgeToEdge,
            boolean isExcludeCaptionInsetsOverrideEnabled) {
        if (isExcludeCaptionInsetsOverrideEnabled) {
            return true;
        }

        if (isConfigurationDecoupled(info, optOutEdgeToEdge)) {
            return false;
        }

        return !isResizeable;
    }

    /**
     * Applies a vertical and horizontal gravity on the inOutBounds in relation to the stableBounds.
     */
    public static void applyLayoutGravity(int verticalGravity, int horizontalGravity,
            @NonNull Rect inOutBounds, @NonNull Rect stableBounds) {
        final int width = inOutBounds.width();
        final int height = inOutBounds.height();

        final float fractionOfHorizontalOffset = switch (horizontalGravity) {
            case Gravity.LEFT -> 0f;
            case Gravity.RIGHT -> 1f;
            default -> 0.5f;
        };

        final float fractionOfVerticalOffset = switch (verticalGravity) {
            case Gravity.TOP -> 0f;
            case Gravity.BOTTOM -> 1f;
            default -> 0.5f;
        };

        inOutBounds.offsetTo(stableBounds.left, stableBounds.top);
        final int xOffset = (int) (fractionOfHorizontalOffset * (stableBounds.width() - width));
        final int yOffset = (int) (fractionOfVerticalOffset * (stableBounds.height() - height));
        inOutBounds.offset(xOffset, yOffset);
    }

    /**
     * Returns the orientation of the given {@code rect}.
     */
    public static @Configuration.Orientation int computeConfigOrientation(@NonNull Rect rect) {
        return rect.height() >= rect.width()
                ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;
    }

    private static boolean isConfigurationDecoupled(@NonNull ActivityInfo info,
            boolean optOutEdgeToEdge) {
        return info.isChangeEnabled(INSETS_DECOUPLED_CONFIGURATION_ENFORCED) && !optOutEdgeToEdge;
    }
}
