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

package android.app;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.content.Context;
import android.content.om.FabricatedOverlay;
import android.content.theming.IThemeChangedCallback;
import android.content.theming.IThemeManager;
import android.content.theming.IThemeSettingsCallback;
import android.content.theming.ThemeInfo;
import android.content.theming.ThemeSettings;
import android.graphics.Color;
import android.os.FabricatedOverlayInternalEntry;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.TypedValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Provides access to the system Theme Service.
 *
 * <p>This class allows applications to interact with the system's Theme Service, enabling them to
 * register for theme settings change notifications, update theme settings, and retrieve the
 * current theme settings.
 *
 * <p>Theme Settings are managed on a per-user basis. This means that all operations performed
 * through this class are scoped to the user associated with the context from which this
 * {@link ThemeManager} instance was obtained.
 *
 * <p>To obtain an instance of this class, use {@link Context#getSystemService(Class)} with
 * {@link ThemeManager}.
 *
 * @hide
 */
@SystemService(Context.THEME_SERVICE)
@FlaggedApi(android.server.Flags.FLAG_ENABLE_THEME_SERVICE)
public class ThemeManager {
    private final IThemeManager mService;

    /**
     * @hide
     */
    public ThemeManager() {
        try {
            mService = IThemeManager.Stub.asInterface(
                    ServiceManager.getServiceOrThrow(Context.THEME_SERVICE));
        } catch (ServiceManager.ServiceNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Generates a {@link FabricatedOverlay} of dynamic color resources based on the current
     * user's theme, with optional overrides.
     *
     * <p>This method uses the current user's theme settings as a baseline. Any non-null
     * properties in the provided {@code options} object will override the corresponding values
     * from the user's theme.
     *
     * <p>The returned overlay contains dynamic color pairs (e.g.,
     * {@code system_primary_dark} and {@code system_primary_light}) that enable the use of
     * dynamic colors like {@code ?attr/materialColorPrimary}.
     *
     * @param options A {@link ThemeInfo} object containing properties to override the current
     *                user's theme. If a property is {@code null}, the value from the user's
     *                current theme settings is used.
     * @return A {@link FabricatedOverlay} object representing the generated color resources.
     * @hide
     */
    public FabricatedOverlay generateDynamicColorOverlay(ThemeInfo options) {
        try {
            return new FabricatedOverlay(mService.generateDynamicColorOverlay(options));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the current theme information for the calling user.
     *
     * @return A {@link ThemeInfo} object with the current theme settings, or {@code null} if the
     * theming service is not yet fully initialized (e.g., during early boot).
     * @hide
     */
    @Nullable
    public ThemeInfo getUserThemeInfo() {
        try {
            return mService.getUserThemeInfo();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a callback to receive notifications of theme settings changes.
     *
     * <p>This method allows clients to register an {@link IThemeSettingsCallback}
     * to be notified whenever the theme settings for the current user are changed.
     *
     * <p>If the service is not yet fully initialized (e.g., during early boot), the callback
     * will be registered and will start receiving events once initialization is complete.
     *
     * @param callback The {@link IThemeSettingsCallback} to register.
     * @return {@code true} if the callback was successfully registered, {@code false} otherwise.
     * @hide
     */
    public boolean registerThemeSettingsCallback(@NonNull IThemeSettingsCallback callback) {
        try {
            return mService.registerThemeSettingsCallback(callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters a previously registered theme settings change callback.
     *
     * <p>This method allows clients to unregister an {@link IThemeSettingsCallback}
     * that was previously registered using
     * {@link #registerThemeSettingsCallback(IThemeSettingsCallback)}.
     *
     * @param callback The {@link IThemeSettingsCallback} to unregister.
     * @return {@code true} if the callback was successfully unregistered, {@code false} otherwise.
     */
    public boolean unregisterThemeSettingsCallback(@NonNull IThemeSettingsCallback callback) {
        try {
            return mService.unregisterThemeSettingsCallback(callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a callback for theme changed events.
     *
     * <p>If the service is not yet fully initialized (e.g., during early boot), the callback
     * will be registered and will start receiving events once initialization is complete.
     *
     * @param callback The callback to add.
     */
    public void registerThemeChangedCallback(@NonNull IThemeChangedCallback callback) {
        try {
            mService.registerThemeChangedCallback(callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters a callback for theme changed events.
     *
     * @param callback The callback to remove.
     */
    public void unregisterThemeChangedCallback(@NonNull IThemeChangedCallback callback) {
        try {
            mService.unregisterThemeChangedCallback(callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the theme settings for the current user.
     *
     * <p>This method allows clients to update the theme settings for the current user.
     * The provided {@link ThemeSettings} object should contain the new theme settings.
     * Any settings not explicitly set in the {@link ThemeSettings} object will remain unchanged.
     * Specifically, any null properties within the provided {@link ThemeSettings} object
     * will be skipped, and the corresponding existing theme settings will be preserved.
     *
     * <p>It is recommended to use the {@link android.content.theming.ThemeSettings} to
     * construct
     * {@link ThemeSettings} objects, especially when only updating a subset of theme properties.
     * This ensures that only the intended properties are modified, and avoids accidentally
     * resetting other settings to default or null values.
     *
     * @param newSettings The {@link ThemeSettings} object containing the new theme settings.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.UPDATE_THEME_SETTINGS)
    public boolean updateThemeSettings(@NonNull ThemeSettings newSettings) {
        try {
            return mService.updateThemeSettings(newSettings);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the theme settings for the current user.
     *
     * <p>This method allows clients to retrieve the current theme settings the calling user.
     *
     * @return The {@link ThemeSettings} object containing the current theme settings,
     * or {@code null} if an error occurs, no settings are found, or the service is not yet ready.
     * @hide
     */
    @Nullable
    public ThemeSettings getThemeSettings() {
        try {
            return mService.getThemeSettings();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the theme settings for the current user, or the system default if none is set.
     *
     * <p>This method allows clients to retrieve the current theme settings for the calling user.
     * If no theme has been explicitly set, it returns a system-generated default. This method
     * will never return {@code null}.
     *
     * @return The non-null {@link ThemeSettings} object containing the current or default theme
     * settings.
     * @hide
     */
    @NonNull
    public ThemeSettings getThemeSettingsOrDefault() {
        try {
            return mService.getThemeSettingsOrDefault();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Generates a map of color resources based on the provided FabricatedOverlay.
     *
     * @param fabricatedOverlay A {@link FabricatedOverlay} to extract color names and values from
     * @return A map where keys are color resource names (e.g., "android:color/system_accent1_100")
     * and values are the corresponding {@link Color} objects.
     * @hide
     */
    public static Map<String, Color> extractColorPairs(FabricatedOverlay fabricatedOverlay) {
        final List<FabricatedOverlayInternalEntry> colorEntries = fabricatedOverlay.getEntries(
                TypedValue.TYPE_INT_COLOR_ARGB8,
                TypedValue.TYPE_INT_COLOR_RGB8,
                TypedValue.TYPE_INT_COLOR_ARGB4,
                TypedValue.TYPE_INT_COLOR_RGB4);

        final Map<String, Color> colorMap = new HashMap<>();
        for (FabricatedOverlayInternalEntry entry : colorEntries) {
            colorMap.put(entry.resourceName, Color.valueOf(entry.data));
        }
        return colorMap;
    }
}