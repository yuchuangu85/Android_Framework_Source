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

package com.android.internal.accessibility.util;

import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_GESTURE;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR;

import static com.android.internal.accessibility.AccessibilityShortcutController.COLOR_INVERSION_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_CONTROLLER_NAME;
import static com.android.internal.accessibility.common.ShortcutConstants.AccessibilityFragmentType.INVISIBLE_TOGGLE;
import static com.android.internal.accessibility.common.ShortcutConstants.SERVICES_SEPARATOR;
import static com.android.internal.accessibility.common.ShortcutConstants.USER_SHORTCUT_TYPES;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.DEFAULT;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.GESTURE;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.HARDWARE;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.KEY_GESTURE;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.QUICK_ACCESS;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.QUICK_SETTINGS;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.SOFTWARE;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.TOP_ROW_KEY;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.TRIPLETAP;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.hardware.input.KeyGestureEvent;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.R;
import com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Collection of utilities for accessibility shortcut.
 */
public final class ShortcutUtils {
    private ShortcutUtils() {}

    private static final TextUtils.SimpleStringSplitter sStringColonSplitter =
            new TextUtils.SimpleStringSplitter(SERVICES_SEPARATOR);
    private static final String TAG = "AccessibilityShortcutUtils";

    /**
     * Opts in component id into colon-separated {@link UserShortcutType}
     * key's string from Settings.
     *
     * @param context      The current context.
     * @param shortcutType The preferred shortcut type user selected.
     * @param componentId  The component id that need to be opted in Settings.
     * @deprecated Use
     * {@link AccessibilityManager#enableShortcutsForTargets(boolean, int, Set, int)}
     */
    @Deprecated
    public static void optInValueToSettings(Context context, @UserShortcutType int shortcutType,
            @NonNull String componentId) {
        final StringJoiner joiner = new StringJoiner(String.valueOf(SERVICES_SEPARATOR));
        final String targetKey = convertToKey(shortcutType);
        final String targetString = Settings.Secure.getString(context.getContentResolver(),
                targetKey);

        if (isComponentIdExistingInSettings(context, shortcutType, componentId)) {
            return;
        }

        if (!TextUtils.isEmpty(targetString)) {
            joiner.add(targetString);
        }
        joiner.add(componentId);

        Settings.Secure.putString(context.getContentResolver(), targetKey, joiner.toString());
    }

    /**
     * Opts out of component id into colon-separated {@link UserShortcutType} key's string from
     * Settings.
     *
     * @param context The current context.
     * @param shortcutType The preferred shortcut type user selected.
     * @param componentId The component id that need to be opted out of Settings.
     *
     * @deprecated Use
     * {@link AccessibilityManager#enableShortcutForTargets(boolean, int, Set, int)}
     */
    @Deprecated
    public static void optOutValueFromSettings(
            Context context, @UserShortcutType int shortcutType, @NonNull String componentId) {
        final StringJoiner joiner = new StringJoiner(String.valueOf(SERVICES_SEPARATOR));
        final String targetsKey = convertToKey(shortcutType);
        final String targetsValue = Settings.Secure.getString(context.getContentResolver(),
                targetsKey);

        if (TextUtils.isEmpty(targetsValue)) {
            return;
        }

        sStringColonSplitter.setString(targetsValue);
        while (sStringColonSplitter.hasNext()) {
            final String id = sStringColonSplitter.next();
            if (TextUtils.isEmpty(id) || componentId.equals(id)) {
                continue;
            }
            joiner.add(id);
        }

        Settings.Secure.putString(context.getContentResolver(), targetsKey, joiner.toString());
    }

    /**
     * Returns if component id existed in Settings.
     *
     * @param context The current context.
     * @param shortcutType The preferred shortcut type user selected.
     * @param componentId The component id that need to be checked existed in Settings.
     * @return {@code true} if component id existed in Settings.
     */
    public static boolean isComponentIdExistingInSettings(Context context,
            @UserShortcutType int shortcutType, @NonNull String componentId) {
        final String targetKey = convertToKey(shortcutType);
        final String targetString = Settings.Secure.getString(context.getContentResolver(),
                targetKey);

        if (TextUtils.isEmpty(targetString)) {
            return false;
        }

        sStringColonSplitter.setString(targetString);
        while (sStringColonSplitter.hasNext()) {
            final String id = sStringColonSplitter.next();
            if (componentId.equals(id)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns if a {@code shortcutType} shortcut contains {@code componentName}.
     *
     * @param context The current context.
     * @param shortcutType The preferred shortcut type user selected.
     * @param componentName The component that need to be checked.
     * @return {@code true} if the shortcut contains {@code componentName}.
     */
    @SuppressLint("MissingPermission")
    public static boolean isShortcutContained(
            Context context, @UserShortcutType int shortcutType, @NonNull String componentName) {
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        if (manager != null) {
            return manager
                    .getAccessibilityShortcutTargets(shortcutType).contains(componentName);
        } else {
            return false;
        }
    }

    /**
     * Returns every shortcut type that currently has the provided componentName as a target.
     * Types are returned as a singular flag integer.
     * If none have the componentName, returns {@link UserShortcutType#DEFAULT}
     */
    public static int getEnabledShortcutTypes(
            Context context, String componentName) {
        final AccessibilityManager am = context.getSystemService(AccessibilityManager.class);
        if (am == null) return DEFAULT;

        int shortcutTypes = DEFAULT;
        for (int shortcutType : USER_SHORTCUT_TYPES) {
            if (am.getAccessibilityShortcutTargets(shortcutType).contains(componentName)) {
                shortcutTypes |= shortcutType;
            }
        }
        return shortcutTypes;
    }

    /**
     * Converts {@link UserShortcutType} to {@link Settings.Secure} key.
     *
     * @param type The shortcut type.
     * @return Mapping key in Settings.
     */
    @SuppressLint("SwitchIntDef")
    public static String convertToKey(@UserShortcutType int type) {
        return switch (type) {
            case SOFTWARE -> Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS;
            case GESTURE -> Settings.Secure.ACCESSIBILITY_GESTURE_TARGETS;
            case HARDWARE -> Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE;
            case TRIPLETAP -> Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED;
            case QUICK_SETTINGS -> Settings.Secure.ACCESSIBILITY_QS_TARGETS;
            case KEY_GESTURE -> Settings.Secure.ACCESSIBILITY_KEY_GESTURE_TARGETS;
            case TOP_ROW_KEY -> Settings.Secure.ACCESSIBILITY_TOP_ROW_KEY_TARGETS;
            case QUICK_ACCESS -> Settings.Secure.ACCESSIBILITY_QUICK_ACCESS_TARGETS;
            default ->
                    throw new IllegalArgumentException("Unsupported user shortcut type: " + type);
        };
    }

    /**
     * Converts {@link Settings.Secure} key to {@link UserShortcutType}.
     *
     * @param key The shortcut key in Settings.
     * @return The mapped type
     */
    @UserShortcutType
    public static int convertToType(String key) {
        return switch (key) {
            case Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS -> SOFTWARE;
            case Settings.Secure.ACCESSIBILITY_GESTURE_TARGETS -> GESTURE;
            case Settings.Secure.ACCESSIBILITY_QS_TARGETS -> QUICK_SETTINGS;
            case Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE -> HARDWARE;
            case Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED -> TRIPLETAP;
            case Settings.Secure.ACCESSIBILITY_KEY_GESTURE_TARGETS -> KEY_GESTURE;
            case Settings.Secure.ACCESSIBILITY_TOP_ROW_KEY_TARGETS -> TOP_ROW_KEY;
            case Settings.Secure.ACCESSIBILITY_QUICK_ACCESS_TARGETS -> QUICK_ACCESS;
            default -> throw new IllegalArgumentException("Unsupported user shortcut key: " + key);
        };
    }

    /**
     * Returns a string resource to label the given {@link UserShortcutType}.
     *
     * @param type The shortcut type.
     * @return An appropriate string resource for the type.
     */
    @SuppressLint("SwitchIntDef")
    public static int typeToString(@UserShortcutType int type) {
        return switch (type) {
            case SOFTWARE -> R.string.accessibility_shortcut_label_button;
            case GESTURE -> R.string.accessibility_shortcut_label_gesture;
            case HARDWARE -> R.string.accessibility_shortcut_label_volume_keys;
            case QUICK_SETTINGS -> R.string.accessibility_shortcut_label_quick_settings;
            case TRIPLETAP -> R.string.accessibility_shortcut_label_triple_tap;
            // TWOFINGER_DOUBLETAP is currently unsupported.
            // KEY_GESTURE is not user-facing, so it has no label.
            case TOP_ROW_KEY -> R.string.accessibility_shortcut_label_top_row_key;
            default -> throw new IllegalStateException("Unsupported user shortcut type " + type);
        };
    }

    /**
     * Updates an accessibility state if the accessibility service is a Always-On a11y service,
     * a.k.a. AccessibilityServices that has FLAG_REQUEST_ACCESSIBILITY_BUTTON
     * <p>
     * Turn on the accessibility service when there is any shortcut associated to it.
     * <p>
     * Turn off the accessibility service when there is no shortcut associated to it.
     *
     * @param componentNames the a11y shortcut target's component names
     */
    public static void updateInvisibleToggleAccessibilityServiceEnableState(
            Context context, Set<String> componentNames, int userId) {
        final AccessibilityManager am = context.getSystemService(AccessibilityManager.class);
        if (am == null) return;

        final List<AccessibilityServiceInfo> installedServices =
                am.getInstalledAccessibilityServiceList();

        final Set<String> invisibleToggleServices = new ArraySet<>();
        for (AccessibilityServiceInfo serviceInfo : installedServices) {
            if (AccessibilityUtils.getAccessibilityServiceFragmentType(serviceInfo)
                    == INVISIBLE_TOGGLE) {
                invisibleToggleServices.add(serviceInfo.getComponentName().flattenToString());
            }
        }

        final Set<String> servicesWithShortcuts = new ArraySet<>();
        for (int shortcutType: USER_SHORTCUT_TYPES) {
            // The call to update always-on service might modify the shortcut setting right before
            // calling #updateAccessibilityServiceStateIfNeeded in the same call.
            // To avoid getting the shortcut target from out-dated value, use values from Settings
            // instead.
            servicesWithShortcuts.addAll(
                    getShortcutTargetsFromSettings(context, shortcutType, userId));
        }

        for (String componentName : componentNames) {
            // Only needs to update the Always-On A11yService's state when the shortcut changes.
            if (invisibleToggleServices.contains(componentName)) {

                boolean enableA11yService = servicesWithShortcuts.contains(componentName);
                AccessibilityUtils.setAccessibilityServiceState(
                        context,
                        ComponentName.unflattenFromString(componentName),
                        enableA11yService,
                        userId);
            }
        }
    }

    /**
     * Returns the target component names of a given user shortcut type from Settings.
     *
     * <p>
     * Note: grab shortcut targets from Settings is only needed
     * if you depends on a value being set in the same call.
     * For example, you disable a single shortcut,
     * and you're checking if there is any shortcut remaining.
     *
     * <p>
     * If you just want to know the current state, you can use
     * {@link AccessibilityManager#getAccessibilityShortcutTargets(int)}
     */
    @NonNull
    public static Set<String> getShortcutTargetsFromSettings(
            Context context, @UserShortcutType int shortcutType, int userId) {
        final String targetKey = convertToKey(shortcutType);
        if (Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED.equals(targetKey)) {
            boolean magnificationEnabled = Settings.Secure.getIntForUser(
                    context.getContentResolver(), targetKey, /* def= */ 0, userId) == 1;
            return magnificationEnabled ? Set.of(MAGNIFICATION_CONTROLLER_NAME)
                    : Collections.emptySet();

        } else {
            final String targetString = Settings.Secure.getStringForUser(
                    context.getContentResolver(), targetKey, userId);

            if (TextUtils.isEmpty(targetString)) {
                return Collections.emptySet();
            }

            Set<String> targets = new ArraySet<>();
            sStringColonSplitter.setString(targetString);
            while (sStringColonSplitter.hasNext()) {
                targets.add(sStringColonSplitter.next());
            }
            return Collections.unmodifiableSet(targets);
        }
    }

    /**
     * Retrieves the button mode of the provided context.
     * Returns -1 if the button mode is undefined.
     * Valid button modes:
     * {@link Settings.Secure#ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR},
     * {@link Settings.Secure#ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU},
     * {@link Settings.Secure#ACCESSIBILITY_BUTTON_MODE_GESTURE}
     */
    public static int getButtonMode(Context context, @UserIdInt int userId) {
        return Settings.Secure.getIntForUser(context.getContentResolver(),
                ACCESSIBILITY_BUTTON_MODE, /* default value = */ -1, userId);
    }

    /**
     * Sets the button mode of the provided context.
     * Must be a valid button mode, or it will return false.
     * Returns true if the setting was changed, false otherwise.
     * Valid button modes:
     * {@link Settings.Secure#ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR},
     * {@link Settings.Secure#ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU},
     * {@link Settings.Secure#ACCESSIBILITY_BUTTON_MODE_GESTURE}
     */
    public static boolean setButtonMode(Context context, int mode, @UserIdInt int userId) {
        // Input validation
        if (getButtonMode(context, userId) == mode) {
            return false;
        }
        if ((mode
                & (ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR
                | ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU
                | ACCESSIBILITY_BUTTON_MODE_GESTURE)) != mode) {
            Slog.w(TAG, "Tried to set button mode to unexpected value " + mode);
            return false;
        }
        return Settings.Secure.putIntForUser(
                context.getContentResolver(), ACCESSIBILITY_BUTTON_MODE, mode, userId);
    }

    /**
     * Returns the target name for the default screen reader service.
     * @param context Context
     * @return the screen reader target name
     */
    public static String getScreenReaderTargetName(Context context) {
        return context.getString(R.string.config_defaultAccessibilityService);
    }

    /**
     * Returns the target name for the default select to speak service.
     * @param context Context
     * @return the select to speak target name
     */
    public static String getSelectToSpeakTargetName(Context context) {
        return context.getString(R.string.config_defaultSelectToSpeakService);
    }

    /**
     * Returns the target name for the default voice access service.
     * @param context Context
     * @return the voice access target name
     */
    public static String getVoiceAccessTargetName(Context context) {
        return context.getString(R.string.config_defaultVoiceAccessService);
    }

    /**
     * Returns the string label for the given key code. The key code must be for the single key
     * for one of the keyboard shortcuts that toggles an assistive technology, i.e.
     * one of: Magnification, Screen reader, Select to Speak, or Voice Access.
     * @param keyCode integer key code for a keyboard shortcut to toggle an assistive technology
     * @return the string label for the key code
     */
    public static String getLabelFromKeyCode(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.KEYCODE_I -> "I";
            case KeyEvent.KEYCODE_M -> "M";
            case KeyEvent.KEYCODE_S -> "S";
            case KeyEvent.KEYCODE_T -> "T";
            case KeyEvent.KEYCODE_V -> "V";
            default -> null;
        };
    }

    /**
     * Return the string key code label for the keyboard shortcut to toggle the assistive
     * technology as denoted by the target name.
     * @param context Context
     * @param targetName the target name for the assistive technology
     * @return the key code label as a string
     */
    public static String getKeyCodeLabelFromTarget(Context context, String targetName) {
        return getLabelFromKeyCode(getKeyCodeFromTarget(context, targetName));
    }

    /**
     * Get the target name as a string from a key gesture event associated with toggling an
     * assistive technology.
     * @param context Context
     * @param event the key gesture event for toggling an assistive technology.
     * @return the target name of the assistive technology associated with the key gesture event
     */
    public static String getTargetFromKeyGestureEvent(Context context, KeyGestureEvent event) {
        // For the supported key gesture types, there is one and only one keyCode.
        int[] keyCodes = event.getKeycodes();
        if (keyCodes.length != 1) {
            return null;
        }
        return getTargetFromKeyCode(context, keyCodes[0]);
    }


    private static int getKeyCodeFromTarget(Context context, String targetName) {
        // Magnification uses the package name rather than a component name.
        if (targetName.equals(COLOR_INVERSION_COMPONENT_NAME.flattenToString())) {
            return KeyEvent.KEYCODE_I;
        } else if (targetName.equals(MAGNIFICATION_CONTROLLER_NAME)) {
            return KeyEvent.KEYCODE_M;
        }

        final Map<String, Integer> serviceToKeyCodeMap = new LinkedHashMap<>();
        serviceToKeyCodeMap.put(getSelectToSpeakTargetName(context), KeyEvent.KEYCODE_S);
        serviceToKeyCodeMap.put(getScreenReaderTargetName(context), KeyEvent.KEYCODE_T);
        serviceToKeyCodeMap.put(getVoiceAccessTargetName(context), KeyEvent.KEYCODE_V);

        for (Map.Entry<String, Integer> entry : serviceToKeyCodeMap.entrySet()) {
            final String serviceName = entry.getKey();
            final int keyCode = entry.getValue();

            // Check if the input targetName directly matches the service name.
            if (targetName.equals(serviceName)) {
                return keyCode;
            }

            // If not, try to unflatten the service name and compare its flattened form
            // with the input targetName.
            if (!TextUtils.isEmpty(serviceName)) {
                final ComponentName componentName = ComponentName.unflattenFromString(serviceName);
                if (componentName != null && targetName.equals(componentName.flattenToString())) {
                    return keyCode;
                }
            }
        }

        return KeyEvent.KEYCODE_UNKNOWN;
    }

    private static String getTargetFromKeyCode(Context context, int keyCode) {
        // Magnification uses the package name rather than a component name.
        if (keyCode == KeyEvent.KEYCODE_I) {
            return COLOR_INVERSION_COMPONENT_NAME.flattenToString();
        } else if (keyCode == KeyEvent.KEYCODE_M) {
            return MAGNIFICATION_CONTROLLER_NAME;
        }

        String feature = switch (keyCode) {
            case KeyEvent.KEYCODE_S ->
                    ShortcutUtils.getSelectToSpeakTargetName(context);
            case KeyEvent.KEYCODE_T ->
                    ShortcutUtils.getScreenReaderTargetName(context);
            case KeyEvent.KEYCODE_V ->
                    ShortcutUtils.getVoiceAccessTargetName(context);
            default -> "";
        };

        ComponentName componentName = TextUtils.isEmpty(feature)
                ? null : ComponentName.unflattenFromString(feature);
        return componentName != null ? componentName.flattenToString() : feature;
    }
}
