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
package android.app.permissionui;

import android.annotation.ColorInt;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.res.Configuration;
import android.permission.flags.Flags;
import android.view.SurfaceControlViewHost;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This interface defines location button session, and allows apps to change/customize
 * the appearance of the button.
 *
 * <p>A location button's UI is rendered by a trusted system process to ensure its integrity and
 * prevent tap jacking. An instance of this interface is returned via
 * {@link LocationButtonClient#onSessionOpened} after a session is requested through
 * {@link LocationButtonProvider#openSession}.
 *
 * <p>Once a session is opened, the client can get the button's UI by calling
 * {@link #getSurfacePackage()} and embedding it in its view hierarchy. The client is responsible
 * for notifying the session of any UI changes, such as size or configuration updates.
 *
 * <p>When the session is no longer needed, the client must call {@link #close()} to release all
 * associated system resources.
 *
 * @see LocationButtonProvider
 * @see LocationButtonClient
 */
@FlaggedApi(Flags.FLAG_LOCATION_BUTTON_ENABLED)
public interface LocationButtonSession extends AutoCloseable {
    /** The button displays no text. */
    int TEXT_TYPE_NONE = 0;
    /** The button displays the text as "Precise location". */
    int TEXT_TYPE_PRECISE_LOCATION = 1;
    /** The button displays the text as "Use precise location". */
    int TEXT_TYPE_USE_PRECISE_LOCATION = 2;
    /** The button displays the text as "Share precise location". */
    int TEXT_TYPE_SHARE_PRECISE_LOCATION = 3;
    /** The button displays the text as "Near my precise location". */
    int TEXT_TYPE_NEAR_MY_PRECISE_LOCATION = 4;
    /** The button displays the text as "Near your precise location". */
    int TEXT_TYPE_NEAR_YOUR_PRECISE_LOCATION = 5;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "TEXT_TYPE_", value = {
            TEXT_TYPE_NONE,
            TEXT_TYPE_PRECISE_LOCATION,
            TEXT_TYPE_USE_PRECISE_LOCATION,
            TEXT_TYPE_SHARE_PRECISE_LOCATION,
            TEXT_TYPE_NEAR_MY_PRECISE_LOCATION,
            TEXT_TYPE_NEAR_YOUR_PRECISE_LOCATION
    })
    @interface TextType {}

    /**
     * Returns the {@link SurfaceControlViewHost.SurfacePackage} containing the view for the
     * location button.
     *
     * <p>The client can attach surface package to a {@link android.view.SurfaceView} in its view
     * hierarchy to display the button. This allows the button's UI to be rendered by a trusted
     * system process while being seamlessly integrated into the application's layout.
     *
     * @return The surface package for the location button.
     */
    @NonNull
    SurfaceControlViewHost.SurfacePackage getSurfacePackage();

    /**
     * Notifies the remote service that the button's container view has been resized.
     *
     * <p>The remote service will re-layout the button to fit within the new dimensions.
     *
     * <p>See {@link LocationButtonRequest.Builder#Builder(int, int, Configuration)}
     * for width and height input constraints.
     *
     * @param width  The new width of the button's container, in pixels.
     * @param height The new height of the button's container, in pixels.
     */
    void resize(int width, int height);

    /**
     * Sets the padding for the location button within its container.
     *
     * <p>See the padding setters
     * (e.g., {@link LocationButtonRequest.Builder#setPaddingLeft(int)}) for input constraints.
     *
     * @param left   The left padding in pixels.
     * @param top    The top padding in pixels.
     * @param right  The right padding in pixels.
     * @param bottom The bottom padding in pixels.
     */
    void setPadding(int left, int top, int right, int bottom);

    /**
     * Notifies the remote service of a configuration change.
     *
     * <p>This should be called when the application's configuration changes, for example, due to a
     * device rotation or a theme change (e.g., light to dark mode).
     *
     * @param newConfig The new configuration.
     */
    void changeConfiguration(@NonNull Configuration newConfig);

    /**
     * Closes the session and releases all associated resources.
     *
     * <p>This releases the underlying surface and the connection to the remote service. Once
     * closed, the location button UI is removed, and the session is no longer usable.
     */
    void close();

    /**
     * Sets the corner radius for the location button.
     *
     * <p>See {@link LocationButtonRequest.Builder#setCornerRadius(float)} for input constraints.
     *
     * @param cornerRadius The corner radius in pixels.
     */
    void setCornerRadius(float cornerRadius);

    /**
     * Sets the corner radius for the location button pressed state.
     *
     * <p>See {@link LocationButtonRequest.Builder#setPressedCornerRadius(float)}
     * for input constraints.
     *
     * @param cornerRadius The corner radius in pixels.
     */
    void setPressedCornerRadius(float cornerRadius);

    /**
     * Sets the color of location button text.
     *
     * <p>See {@link LocationButtonRequest.Builder#setTextColor(int)} for input constraints.
     *
     * @param color The desired text color, as a {@link ColorInt}.
     */
    void setTextColor(@ColorInt int color);

    /**
     * Sets the background color of the location button.
     *
     * <p>See {@link LocationButtonRequest.Builder#setBackgroundColor(int)} for input constraints.
     *
     * @param color The desired background color, as a {@link ColorInt}.
     */
    void setBackgroundColor(@ColorInt int color);

    /**
     * Sets the tint color of the icon within the location button.
     *
     * <p>See {@link LocationButtonRequest.Builder#setIconTint(int)} for input constraints.
     *
     * @param color The desired icon tint color, as a {@link ColorInt}.
     */
    void setIconTint(@ColorInt int color);

    /**
     * Sets the text to be displayed on the button.
     *
     * <p>See {@link LocationButtonRequest.Builder#setTextType(int)} for input constraints.
     *
     * @param textType The text type for the button text.
     */
    void setTextType(@TextType int textType);

    /**
     * Sets the color of location button stroke/outline.
     *
     * <p>See {@link LocationButtonRequest.Builder#setStrokeColor(int)} for input constraints.
     *
     * @param color The desired outline color, as a {@link ColorInt}.
     */
    void setStrokeColor(@ColorInt int color);

    /**
     * Sets the width of location button stroke/outline.
     *
     * <p>See {@link LocationButtonRequest.Builder#setStrokeWidth(int)} for input constraints.
     *
     * @param width The desired stroke width.
     */
    void setStrokeWidth(int width);
}
