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

package android.app.contextualsearch;

import static android.Manifest.permission.ACCESS_CONTEXTUAL_SEARCH;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.SuppressLint;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.app.Activity;
import android.app.contextualsearch.flags.Flags;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * {@link ContextualSearchManager} is a system service to facilitate contextual search experience on
 * configured Android devices. This involves capturing screenshots that the Contextual Search system
 * app presents to the user for interaction, such as selecting content on the screenshot to get a
 * search result or take an action such as calling a phone number or translating text.
 *
 * @hide
 */
@SystemApi
public final class ContextualSearchManager {

    /**
     * Key to get the entrypoint from the extras of the activity launched by contextual search.
     * Only supposed to be used with ACTION_LAUNCH_CONTEXTUAL_SEARCH.
     *
     * @see #ACTION_LAUNCH_CONTEXTUAL_SEARCH
     * @hide
     */
    @SystemApi
    public static final String EXTRA_ENTRYPOINT =
            "android.app.contextualsearch.extra.ENTRYPOINT";

    /**
     * Key to get the flag_secure value from the extras of the activity launched by contextual
     * search. The value will be true if flag_secure is found in any of the visible activities.
     * Only supposed to be used with ACTION_LAUNCH_CONTEXTUAL_SEARCH.
     *
     * @see #ACTION_LAUNCH_CONTEXTUAL_SEARCH
     * @hide
     */
    @SystemApi
    public static final String EXTRA_FLAG_SECURE_FOUND =
            "android.app.contextualsearch.extra.FLAG_SECURE_FOUND";

    /**
     * Key to get the screenshot from the extras of the activity launched by contextual search.
     * Only supposed to be used with ACTION_LAUNCH_CONTEXTUAL_SEARCH.
     *
     * @see #ACTION_LAUNCH_CONTEXTUAL_SEARCH
     * @hide
     */
    @SystemApi
    public static final String EXTRA_SCREENSHOT =
            "android.app.contextualsearch.extra.SCREENSHOT";

    /**
     * Key to check whether managed profile is visible from the extras of the activity launched by
     * contextual search. The value will be true if any one of the visible apps is managed.
     * Only supposed to be used with ACTION_LAUNCH_CONTEXTUAL_SEARCH.
     *
     * @see #ACTION_LAUNCH_CONTEXTUAL_SEARCH
     * @hide
     */
    @SystemApi
    public static final String EXTRA_IS_MANAGED_PROFILE_VISIBLE =
            "android.app.contextualsearch.extra.IS_MANAGED_PROFILE_VISIBLE";

    /**
     * Key to get the list of visible packages from the extras of the activity launched by
     * contextual search.
     * Only supposed to be used with ACTION_LAUNCH_CONTEXTUAL_SEARCH.
     *
     * @see #ACTION_LAUNCH_CONTEXTUAL_SEARCH
     * @hide
     */
    @SystemApi
    public static final String EXTRA_VISIBLE_PACKAGE_NAMES =
            "android.app.contextualsearch.extra.VISIBLE_PACKAGE_NAMES";

    /**
     * Key to get the time the user made the invocation request, based on
     * {@link SystemClock#uptimeMillis()}.
     * Only supposed to be used with ACTION_LAUNCH_CONTEXTUAL_SEARCH.
     *
     * TODO: un-hide in W
     *
     * @see #ACTION_LAUNCH_CONTEXTUAL_SEARCH
     * @hide
     */
    public static final String EXTRA_INVOCATION_TIME_MS =
            "android.app.contextualsearch.extra.INVOCATION_TIME_MS";

    /**
     * Key to get the binder token from the extras of the activity launched by contextual search.
     * This token is needed to invoke {@link CallbackToken#getContextualSearchState} method.
     * Only supposed to be used with ACTION_LAUNCH_CONTEXTUAL_SEARCH.
     *
     * @see #ACTION_LAUNCH_CONTEXTUAL_SEARCH
     * @hide
     */
    @SystemApi
    public static final String EXTRA_TOKEN = "android.app.contextualsearch.extra.TOKEN";

    /**
     * Key to check whether audio is playing when contextual search is invoked.
     * Only supposed to be used with ACTION_LAUNCH_CONTEXTUAL_SEARCH.
     *
     * @see #ACTION_LAUNCH_CONTEXTUAL_SEARCH
     *
     * @hide
     */
    public static final String EXTRA_IS_AUDIO_PLAYING =
            "android.app.contextualsearch.extra.IS_AUDIO_PLAYING";

    /**
     * Intent action for contextual search invocation. The app providing the contextual search
     * experience must add this intent filter action to the activity it wants to be launched.
     * <br>
     * <b>Note</b> This activity must not be exported.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_LAUNCH_CONTEXTUAL_SEARCH =
            "android.app.contextualsearch.action.LAUNCH_CONTEXTUAL_SEARCH";

    /**
     * System feature declaring that the device supports Contextual Search.
     *
     * @hide
     */
    public static final String FEATURE_CONTEXTUAL_SEARCH =
            "com.google.android.feature.CONTEXTUAL_SEARCH";

    /**
     * Entrypoint to be used when a user long presses on the nav handle.
     *
     * @hide
     */
    @SystemApi
    public static final int ENTRYPOINT_LONG_PRESS_NAV_HANDLE = 1;

    /** Entrypoint to be used when a user long presses on the home button.
     *
     * @hide
     */
    @SystemApi
    public static final int ENTRYPOINT_LONG_PRESS_HOME = 2;

    /** Entrypoint to be used when a user long presses on the overview button.
     *
     * @hide
     */
    @SystemApi
    public static final int ENTRYPOINT_LONG_PRESS_OVERVIEW = 3;

    /**
     * Entrypoint to be used when a user presses the action button in overview.
     *
     * @hide
     */
    @SystemApi
    public static final int ENTRYPOINT_OVERVIEW_ACTION = 4;

    /**
     * Entrypoint to be used when a user presses the context menu button in overview.
     *
     * @hide
     */
    @SystemApi
    public static final int ENTRYPOINT_OVERVIEW_MENU = 5;

    /**
     * Entrypoint to be used by system actions like TalkBack, Accessibility etc.
     *
     * @hide
     */
    @SystemApi
    public static final int ENTRYPOINT_SYSTEM_ACTION = 9;

    /**
     * Entrypoint to be used when a user long presses on the meta key.
     *
     * @hide
     */
    @SystemApi
    public static final int ENTRYPOINT_LONG_PRESS_META = 10;

    /**
     * Entrypoint to be used by keyboard shortcuts.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(com.android.hardware.input.Flags.FLAG_ENABLE_CONTEXTUAL_SEARCH_DESKTOP_ENTRYPOINTS)
    public static final int ENTRYPOINT_KEYBOARD_SHORTCUT = 11;

    /**
     * The {@link Entrypoint} annotation is used to standardize the entrypoints supported by
     * {@link #startContextualSearch(int entrypoint)} method.
     *
     * @hide
     */
    @IntDef(prefix = {"ENTRYPOINT_"}, value = {
            ENTRYPOINT_LONG_PRESS_NAV_HANDLE,
            ENTRYPOINT_LONG_PRESS_HOME,
            ENTRYPOINT_LONG_PRESS_OVERVIEW,
            ENTRYPOINT_OVERVIEW_ACTION,
            ENTRYPOINT_OVERVIEW_MENU,
            ENTRYPOINT_SYSTEM_ACTION,
            ENTRYPOINT_LONG_PRESS_META,
            ENTRYPOINT_KEYBOARD_SHORTCUT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Entrypoint {}

    private static final Set<Integer> VALID_ENTRYPOINT_VALUES = new HashSet<>(Arrays.asList(
            ENTRYPOINT_LONG_PRESS_NAV_HANDLE,
            ENTRYPOINT_LONG_PRESS_HOME,
            ENTRYPOINT_LONG_PRESS_OVERVIEW,
            ENTRYPOINT_OVERVIEW_ACTION,
            ENTRYPOINT_OVERVIEW_MENU,
            ENTRYPOINT_SYSTEM_ACTION,
            ENTRYPOINT_LONG_PRESS_META,
            ENTRYPOINT_KEYBOARD_SHORTCUT
    ));

    private static final String TAG = ContextualSearchManager.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final IContextualSearchManager mService;

    /** @hide */
    public ContextualSearchManager() {
        if (DEBUG) Log.d(TAG, "ContextualSearchManager created");
        IBinder b = ServiceManager.getService(Context.CONTEXTUAL_SEARCH_SERVICE);
        mService = IContextualSearchManager.Stub.asInterface(b);
    }

    /**
     * Used to check whether contextual search is available on the device. If this method returns
     * {code false}, you should not add any UI related to this feature, nor call
     * {@link #startContextualSearch(Activity, ContextualSearchConfig)}. It's rare but possible that
     * the return value of this method will change in subsequent calls, e.g. if the Contextual
     * Search app is disabled or enabled by the user.
     *
     * @see #startContextualSearch(Activity, ContextualSearchConfig)
     * @return {@code true} if contextual search is currently available, {@code false} otherwise
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CONFIG_PARAMETERS)
    @SystemApi
    public boolean isContextualSearchAvailable() {
        if (DEBUG) Log.d(TAG, "isContextualSearchAvailable");
        try {
            return mService.isContextualSearchAvailable();
        } catch (RemoteException e) {
            if (DEBUG) Log.e(TAG, "Failed to determine isContextualSearchAvailable", e);
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * Used to start contextual search for a given system entrypoint.
     * <p>
     *     When {@link #startContextualSearch} is called, the system server does the following:
     *     <ul>
     *         <li>Resolves the activity using the package name and intent filter. The package name
     *             is fetched from the config specified in ContextualSearchManagerService.
     *             The activity must have ACTION_LAUNCH_CONTEXTUAL_SEARCH specified in its manifest.
     *         <li>Puts the required extras in the launch intent, which may include a
     *         {@link android.media.projection.MediaProjection} session.
     *         <li>Launches the activity.
     *     </ul>
     * </p>
     *
     * <p>This method will fail silently if Contextual Search is not available on the device.
     *
     * @param entrypoint the invocation entrypoint
     *
     * @hide
     */
    @RequiresPermission(ACCESS_CONTEXTUAL_SEARCH)
    @SystemApi
    public void startContextualSearch(@Entrypoint int entrypoint) {
        if (DEBUG) Log.d(TAG, "startContextualSearch; entrypoint: " + entrypoint);
        startContextualSearchInternal(entrypoint, null);
    }

    /**
     * Used to start contextual search for a given system entrypoint.
     * <p>
     *     When {@link #startContextualSearch} is called, the system server does the following:
     *     <ul>
     *         <li>Resolves the activity using the package name and intent filter. The package name
     *             is fetched from the config specified in ContextualSearchManagerService.
     *             The activity must have ACTION_LAUNCH_CONTEXTUAL_SEARCH specified in its manifest.
     *         <li>Puts the required extras in the launch intent, which may include a
     *         {@link android.media.projection.MediaProjection} session.
     *         <li>Launches the activity.
     *     </ul>
     * </p>
     *
     * <p>This method will fail silently if Contextual Search is not available on the device.
     *
     * @param entrypoint the invocation entrypoint
     * @param config the invocation configuration parameters. If {@code null}, default configuration
     *               will be applied, including launching on {@link Display#DEFAULT_DISPLAY}.
     *
     * @hide
     */
    @RequiresPermission(ACCESS_CONTEXTUAL_SEARCH)
    @FlaggedApi(Flags.FLAG_CONFIG_PARAMETERS)
    @SystemApi
    public void startContextualSearch(@Entrypoint int entrypoint,
            @Nullable ContextualSearchConfig config) {
        startContextualSearchInternal(entrypoint,
                config != null ? config : ContextualSearchConfig.DEFAULT_CONFIG);
    }

    /**
     * Internal method to start contextual search with an entrypoint and optional config.
     */
    @RequiresPermission(ACCESS_CONTEXTUAL_SEARCH)
    private void startContextualSearchInternal(@Entrypoint int entrypoint,
            @Nullable ContextualSearchConfig config) {
        if (!VALID_ENTRYPOINT_VALUES.contains(entrypoint)) {
            throw new IllegalArgumentException("Invalid entrypoint: " + entrypoint);
        }
        if (DEBUG) {
            Log.d(TAG, "startContextualSearch; entrypoint: " + entrypoint + "; config: " + config);
        }
        try {
            mService.startContextualSearch(entrypoint, config);
        } catch (RemoteException e) {
            if (DEBUG) Log.d(TAG, "Failed to startContextualSearch", e);
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Used to start Contextual Search from within an app. This will send a screenshot to the
     * Contextual Search app designated by the device manufacturer. The user can then select content
     * on the screenshot to get a search result or take an action such as calling a phone number or
     * translating the text. Note that the screenshot will capture the full display and may include
     * content outside of your Activity, e.g. in split screen mode.
     *
     * <p>Prior to calling this method or showing any UI related to it, you should verify that
     * Contextual Search is available on the device by using {@link #isContextualSearchAvailable()}.
     * Otherwise, this method will fail silently.
     *
     * <p>Note: The system will use the display ID of your activity unless a displayId is specified
     * in the config. This is strongly discouraged unless you have a specific reason to specify a
     * different display.
     *
     * @see #isContextualSearchAvailable()
     * @param activity your foreground Activity from which the search is started
     * @param config the invocation configuration parameters. If {@code null}, default configuration
     *               will be applied, including launching the search on the same display as your
     *               activity.
     * @throws SecurityException if the caller does not have a foreground Activity
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CONFIG_PARAMETERS)
    @SystemApi
    public void startContextualSearch(@NonNull Activity activity,
            @Nullable ContextualSearchConfig config) {
        Objects.requireNonNull(activity);
        ContextualSearchConfig.Builder builder = new ContextualSearchConfig.Builder(
                config != null ? config : ContextualSearchConfig.DEFAULT_CONFIG);
        if (config == null || config.getDisplayId() == Display.INVALID_DISPLAY) {
            int displayId = activity.getDisplayId();
            if (displayId == Display.INVALID_DISPLAY) {
                displayId = Display.DEFAULT_DISPLAY;
            }
            builder.setDisplayId(displayId);
        }
        startContextualSearch(builder.build());
    }

    /**
     * Used to start Contextual Search from within an app that has a foreground window (e.g. an
     * Activity or a System Alert Window). This will send a screenshot of the specified display to
     * the Contextual Search app.
     *
     * <p>Prior to calling this method or showing any UI related to it, you should verify that
     * Contextual Search is available on the device by using {@link #isContextualSearchAvailable()}.
     * Otherwise, this method will fail silently.
     *
     * <p>Note: A valid display ID must be specified in the config. Use
     * {@link ContextualSearchConfig.Builder#setDisplayId(int)}.
     *
     * @see #isContextualSearchAvailable()
     * @param config the invocation configuration parameters. Must not be null and must have a
     *               valid display ID.
     * @throws IllegalArgumentException if config is null or has an invalid display ID.
     * @throws SecurityException if the caller is not in the foreground.
     *
     * @hide
     */
    @SuppressLint("UnflaggedApi")
    @SystemApi
    public void startContextualSearch(@NonNull ContextualSearchConfig config) {
        Objects.requireNonNull(config);
        if (config.getDisplayId() == Display.INVALID_DISPLAY) {
            throw new IllegalArgumentException("A valid display ID must be specified in config.");
        }
        if (DEBUG) Log.d(TAG, "startContextualSearch(" + config + ")");
        try {
            mService.startContextualSearchForApp(config);
        } catch (RemoteException e) {
            if (DEBUG) Log.d(TAG, "Failed to startContextualSearchForApp", e);
            e.rethrowFromSystemServer();
        }
    }
}
