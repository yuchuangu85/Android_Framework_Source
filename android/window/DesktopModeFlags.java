/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.window;

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.util.Log;

import com.android.window.flags.Flags;

import java.util.function.BooleanSupplier;

/**
 * Checks desktop mode flag state.
 *
 * <p>This enum provides a centralized way to control the behavior of flags related to desktop
 * windowing features which are aiming for developer preview before their release. It allows
 * developer option to override the default behavior of these flags.
 *
 * <p> Note: No new flag should be added to this class. For new features, please add the flags to
 * {@link DesktopExperienceFlags}.
 *
 * @hide
 */
@RavenwoodKeepWholeClass
public enum DesktopModeFlags {
    // All desktop mode related flags to be overridden by developer option toggle will be added here
    // go/keep-sorted start
    ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION_ALWAYS(
            Flags::enableCaptionCompatInsetForceConsumptionAlways, true),
    ENABLE_DESKTOP_APP_HANDLE_ANIMATION(Flags::enableDesktopAppHandleAnimation, true),
    ENABLE_DESKTOP_WALLPAPER_ACTIVITY_FOR_SYSTEM_USER(
            Flags::enableDesktopWallpaperActivityForSystemUser, true),
    ENABLE_DESKTOP_WINDOWING_APP_TO_WEB_EDUCATION(Flags::enableDesktopWindowingAppToWebEducation,
            true),
    ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION(Flags::enableDesktopWindowingBackNavigation, true),
    ENABLE_DESKTOP_WINDOWING_EXIT_TRANSITIONS_BUGFIX(
            Flags::enableDesktopWindowingExitTransitionsBugfix, true),
    ENABLE_DESKTOP_WINDOWING_MODE(Flags::enableDesktopWindowingMode, true),
    ENABLE_DESKTOP_WINDOWING_PERSISTENCE(Flags::enableDesktopWindowingPersistence, true),
    ENABLE_DESKTOP_WINDOWING_SIZE_CONSTRAINTS(Flags::enableDesktopWindowingSizeConstraints, true),
    ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY(Flags::enableDesktopWindowingWallpaperActivity,
            true),
    ENABLE_HANDLE_INPUT_FIX(Flags::enableHandleInputFix, true),
    ENABLE_RESIZING_METRICS(Flags::enableResizingMetrics, true),
    ENABLE_TASKBAR_OVERFLOW(Flags::enableTaskbarOverflow, false),
    ENABLE_TASK_STACK_OBSERVER_IN_SHELL(Flags::enableTaskStackObserverInShell, true),
    ENABLE_WINDOWING_EDGE_DRAG_RESIZE(Flags::enableWindowingEdgeDragResize, true),
    // go/keep-sorted end
    ;

    /**
     * Flag class, to be used in case the enum cannot be used because the flag is not accessible.
     *
     * <p> This class will still use the process-wide cache.
     */
    public static class DesktopModeFlag {
        // Function called to obtain aconfig flag value.
        private final BooleanSupplier mFlagFunction;
        // Whether the flag state should be affected by developer option.
        private final boolean mShouldOverrideByDevOption;

        public DesktopModeFlag(BooleanSupplier flagFunction, boolean shouldOverrideByDevOption) {
            this.mFlagFunction = flagFunction;
            this.mShouldOverrideByDevOption = shouldOverrideByDevOption;
        }

        /**
         * Determines state of flag based on the actual flag and desktop mode developer option
         * overrides.
         */
        public boolean isTrue() {
            return isFlagTrue(mFlagFunction, mShouldOverrideByDevOption);
        }

    }

    private static final String TAG = "DesktopModeFlags";
    // Function called to obtain aconfig flag value.
    private final BooleanSupplier mFlagFunction;
    // Whether the flag state should be affected by developer option.
    private final boolean mShouldOverrideByDevOption;

    // Local cache for toggle override, which is initialized once on its first access. It needs to
    // be refreshed only on reboots as overridden state is expected to take effect on reboots.
    private static ToggleOverride sCachedToggleOverride;
    private static ToggleOverride sCachedRawToggleOverride;

    DesktopModeFlags(BooleanSupplier flagFunction, boolean shouldOverrideByDevOption) {
        this.mFlagFunction = flagFunction;
        this.mShouldOverrideByDevOption = shouldOverrideByDevOption;
    }

    /**
     * Determines state of flag based on the actual flag and desktop mode developer option
     * overrides.
     */
    public boolean isTrue() {
        return isFlagTrue(mFlagFunction, mShouldOverrideByDevOption);
    }

    public static boolean isDesktopModeForcedEnabled() {
        return getRawToggleOverride() == ToggleOverride.OVERRIDE_ON;
    }

    private static boolean isFlagTrue(BooleanSupplier flagFunction,
            boolean shouldOverrideByDevOption) {
        if (!shouldOverrideByDevOption) return flagFunction.getAsBoolean();
        return switch (getToggleOverride()) {
            case OVERRIDE_UNSET -> flagFunction.getAsBoolean();
            case OVERRIDE_OFF -> false;
            case OVERRIDE_ON -> true;
        };
    }

    private static ToggleOverride getToggleOverride() {
        // If cached, return it
        if (sCachedToggleOverride != null) {
            return sCachedToggleOverride;
        }
        ToggleOverride override;
        // Otherwise, fetch and cache it
        if (sCachedRawToggleOverride == null) {
            override = getToggleOverrideFromSystem();
            sCachedRawToggleOverride = override;
        } else {
            override = sCachedRawToggleOverride;
        }
        // Override if the feature (i.e. enableDesktopWindowingMode) and the toggle are opposite.
        // That is, if desktop windowing mode is enabled but the toggle is disabled, we disable all
        // the flags while if desktop windowing mode is disabled but the toggle is enabled, we
        // enable all the flags.
        // If desktop windowing mode and the toggle agree or if the toggle is not set, we don't
        // touch the flags.
        ToggleOverride resolvedOverride;
        if (Flags.enableDesktopWindowingMode() && override == ToggleOverride.OVERRIDE_OFF) {
            resolvedOverride = ToggleOverride.OVERRIDE_OFF;
        } else if (!Flags.enableDesktopWindowingMode() && override == ToggleOverride.OVERRIDE_ON) {
            resolvedOverride = ToggleOverride.OVERRIDE_ON;
        } else {
            resolvedOverride = ToggleOverride.OVERRIDE_UNSET;
        }
        sCachedToggleOverride = resolvedOverride;
        Log.d(TAG, "Toggle override initialized to: " + override);
        return resolvedOverride;
    }

    private static ToggleOverride getRawToggleOverride() {
        if (sCachedRawToggleOverride != null) {
            return sCachedRawToggleOverride;
        }
        getToggleOverride();
        return sCachedRawToggleOverride;
    }

    /**
     *  Returns {@link ToggleOverride} from Settings.Global set by toggle.
     */
    private static ToggleOverride getToggleOverrideFromSystem() {
        if (Flags.showDesktopExperienceDevOption()) {
            if (DesktopExperienceFlags.getToggleOverride()) {
                return ToggleOverride.OVERRIDE_ON;
            }
            return ToggleOverride.OVERRIDE_UNSET;
        }

        if (Flags.showDesktopWindowingDevOption()) {
            final Context context = DesktopExperienceFlags.getApplicationContext();
            if (context == null) {
                Log.w(TAG, "Could not get the current application.");
                return ToggleOverride.OVERRIDE_UNSET;
            }
            final ContentResolver contentResolver = context.getContentResolver();
            if (contentResolver == null) {
                Log.w(TAG, "Could not get the content resolver for the application.");
                return ToggleOverride.OVERRIDE_UNSET;
            }
            int settingValue = Settings.Global.getInt(
                    contentResolver,
                    Settings.Global.DEVELOPMENT_OVERRIDE_DESKTOP_MODE_FEATURES,
                    ToggleOverride.OVERRIDE_UNSET.getSetting()
            );
            return ToggleOverride.fromSetting(settingValue, ToggleOverride.OVERRIDE_UNSET);
        }

        return ToggleOverride.OVERRIDE_UNSET;
    }

    /** Override state of desktop mode developer option toggle. */
    public enum ToggleOverride {
        OVERRIDE_UNSET,
        OVERRIDE_OFF,
        OVERRIDE_ON;

        /** Returns the integer representation of this {@code ToggleOverride}. */
        public int getSetting() {
            return switch (this) {
                case OVERRIDE_ON -> 1;
                case OVERRIDE_OFF -> 0;
                case OVERRIDE_UNSET -> -1;
            };
        }

        /** Returns the {@code ToggleOverride} corresponding to a given integer setting. */
        public static ToggleOverride fromSetting(int setting, @Nullable ToggleOverride fallback) {
            return switch (setting) {
                case 1 -> OVERRIDE_ON;
                case 0 -> OVERRIDE_OFF;
                case -1 -> OVERRIDE_UNSET;
                default -> fallback;
            };
        }
    }
}
