/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.input;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.input.InputSettings;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.Log;
import android.view.PointerIcon;
import android.view.ViewConfiguration;

import java.util.Map;
import java.util.function.Consumer;

/** Observes settings changes and propagates them to the native side. */
class InputSettingsObserver extends ContentObserver {
    static final String TAG = "InputManager";

    /** Feature flag name for the deep press feature */
    private static final String DEEP_PRESS_ENABLED = "deep_press_enabled";

    private final Context mContext;
    private final Handler mHandler;
    private final InputManagerService mService;
    private final NativeInputManagerService mNative;
    private final Map<Uri, Consumer<String /* reason*/>> mObservers;

    InputSettingsObserver(Context context, Handler handler, InputManagerService service,
            NativeInputManagerService nativeIms) {
        super(handler);
        mContext = context;
        mHandler = handler;
        mService = service;
        mNative = nativeIms;
        mObservers = Map.ofEntries(
                Map.entry(Settings.System.getUriFor(Settings.System.POINTER_SPEED),
                        (reason) -> updateMousePointerSpeed()),
                Map.entry(Settings.System.getUriFor(Settings.System.TOUCHPAD_POINTER_SPEED),
                        (reason) -> updateTouchpadPointerSpeed()),
                Map.entry(Settings.System.getUriFor(Settings.System.TOUCHPAD_NATURAL_SCROLLING),
                        (reason) -> updateTouchpadNaturalScrollingEnabled()),
                Map.entry(Settings.System.getUriFor(Settings.System.TOUCHPAD_TAP_TO_CLICK),
                        (reason) -> updateTouchpadTapToClickEnabled()),
                Map.entry(Settings.System.getUriFor(Settings.System.TOUCHPAD_RIGHT_CLICK_ZONE),
                        (reason) -> updateTouchpadRightClickZoneEnabled()),
                Map.entry(Settings.System.getUriFor(Settings.System.SHOW_TOUCHES),
                        (reason) -> updateShowTouches()),
                Map.entry(
                        Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_LARGE_POINTER_ICON),
                        (reason) -> updateAccessibilityLargePointer()),
                Map.entry(Settings.Secure.getUriFor(Settings.Secure.LONG_PRESS_TIMEOUT),
                        (reason) -> updateLongPressTimeout(reason)),
                Map.entry(
                        Settings.Global.getUriFor(
                                Settings.Global.MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH),
                        (reason) -> updateMaximumObscuringOpacityForTouch()),
                Map.entry(Settings.System.getUriFor(Settings.System.SHOW_KEY_PRESSES),
                        (reason) -> updateShowKeyPresses()));
    }

    /**
     * Registers observers for input-related settings and updates the input subsystem with their
     * current values.
     */
    public void registerAndUpdate() {
        for (Uri uri : mObservers.keySet()) {
            mContext.getContentResolver().registerContentObserver(
                    uri, true /* notifyForDescendants */, this, UserHandle.USER_ALL);
        }

        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                for (Consumer<String> observer : mObservers.values()) {
                    observer.accept("user switched");
                }
            }
        }, new IntentFilter(Intent.ACTION_USER_SWITCHED), null, mHandler);

        for (Consumer<String> observer : mObservers.values()) {
            observer.accept("just booted");
        }
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        mObservers.get(uri).accept("setting changed");
    }

    private boolean getBoolean(String settingName, boolean defaultValue) {
        final int setting = Settings.System.getIntForUser(mContext.getContentResolver(),
                settingName, defaultValue ? 1 : 0, UserHandle.USER_CURRENT);
        return setting != 0;
    }

    private int getPointerSpeedValue(String settingName) {
        int speed = Settings.System.getIntForUser(mContext.getContentResolver(),
                settingName, InputSettings.DEFAULT_POINTER_SPEED, UserHandle.USER_CURRENT);
        return Math.min(Math.max(speed, InputSettings.MIN_POINTER_SPEED),
                InputSettings.MAX_POINTER_SPEED);
    }

    private void updateMousePointerSpeed() {
        mNative.setPointerSpeed(getPointerSpeedValue(Settings.System.POINTER_SPEED));
    }

    private void updateTouchpadPointerSpeed() {
        mNative.setTouchpadPointerSpeed(
                getPointerSpeedValue(Settings.System.TOUCHPAD_POINTER_SPEED));
    }

    private void updateTouchpadNaturalScrollingEnabled() {
        mNative.setTouchpadNaturalScrollingEnabled(
                getBoolean(Settings.System.TOUCHPAD_NATURAL_SCROLLING, true));
    }

    private void updateTouchpadTapToClickEnabled() {
        mNative.setTouchpadTapToClickEnabled(
                getBoolean(Settings.System.TOUCHPAD_TAP_TO_CLICK, true));
    }

    private void updateTouchpadRightClickZoneEnabled() {
        mNative.setTouchpadRightClickZoneEnabled(
                getBoolean(Settings.System.TOUCHPAD_RIGHT_CLICK_ZONE, false));
    }

    private void updateShowTouches() {
        mNative.setShowTouches(getBoolean(Settings.System.SHOW_TOUCHES, false));
    }

    private void updateShowKeyPresses() {
        mService.updateFocusEventDebugViewEnabled(
                getBoolean(Settings.System.SHOW_KEY_PRESSES, false));
    }

    private void updateAccessibilityLargePointer() {
        final int accessibilityConfig = Settings.Secure.getIntForUser(
                mContext.getContentResolver(), Settings.Secure.ACCESSIBILITY_LARGE_POINTER_ICON,
                0, UserHandle.USER_CURRENT);
        PointerIcon.setUseLargeIcons(accessibilityConfig == 1);
        mNative.reloadPointerIcons();
    }

    private void updateLongPressTimeout(String reason) {
        // Some key gesture timeouts are based on the long press timeout, so update key gesture
        // timeouts when the value changes. See ViewConfiguration#getKeyRepeatTimeout().
        mNative.notifyKeyGestureTimeoutsChanged();

        // Update the deep press status.
        // Not using ViewConfiguration.getLongPressTimeout here because it may return a stale value.
        final int timeout = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.LONG_PRESS_TIMEOUT, ViewConfiguration.DEFAULT_LONG_PRESS_TIMEOUT,
                UserHandle.USER_CURRENT);
        final boolean featureEnabledFlag =
                DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_INPUT_NATIVE_BOOT,
                        DEEP_PRESS_ENABLED, true /* default */);
        final boolean enabled =
                featureEnabledFlag && timeout <= ViewConfiguration.DEFAULT_LONG_PRESS_TIMEOUT;
        Log.i(TAG,
                (enabled ? "Enabling" : "Disabling") + " motion classifier because " + reason
                + ": feature " + (featureEnabledFlag ? "enabled" : "disabled")
                + ", long press timeout = " + timeout);
        mNative.setMotionClassifierEnabled(enabled);
    }

    private void updateMaximumObscuringOpacityForTouch() {
        final float opacity = InputSettings.getMaximumObscuringOpacityForTouch(mContext);
        if (opacity < 0 || opacity > 1) {
            Log.e(TAG, "Invalid maximum obscuring opacity " + opacity
                    + ", it should be >= 0 and <= 1, rejecting update.");
            return;
        }
        mNative.setMaximumObscuringOpacityForTouch(opacity);
    }
}
