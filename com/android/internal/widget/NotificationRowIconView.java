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

package com.android.internal.widget;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.util.AttributeSet;
import android.util.Log;
import android.view.RemotableViewMethod;
import android.widget.RemoteViews;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An image view that holds the icon displayed at the start of a notification row.
 * This can generally either display the "small icon" of a notification set via
 * {@link this#setImageIcon(Icon)}, or an app icon controlled and fetched by the provider set
 * through {@link this#setIconProvider(NotificationIconProvider)}.
 */
@RemoteViews.RemoteView
public class NotificationRowIconView extends CachingIconView {
    private static final String TAG = "NotificationRowIconView";

    private NotificationIconProvider mIconProvider;

    private Drawable mAppIcon = null;

    // Padding, background and colors set on the view prior to being overridden when showing the app
    // icon, to be restored if we're showing the small icon again.
    private Rect mOriginalPadding = null;
    private Drawable mOriginalBackground = null;
    private int mOriginalBackgroundColor = ColoredIconHelper.COLOR_INVALID;
    private int mOriginalIconColor = ColoredIconHelper.COLOR_INVALID;

    /**
     * Represents that Icon type has not been set.
     */
    public static final int ICON_TYPE_INVALID = -1;
    /**
     * Represents the "small icon" provided by the developer when posting the notification.
     */
    public static final int ICON_TYPE_SMALL_ICON = 0;

    /**
     * Represents the icon the way the app that posted the notification is displayed in the
     * launcher.
     */
    public static final int ICON_TYPE_LAUNCHER_ICON = 1;

    /**
     * Represents the icon of the app the notification originates from, which differs from the one
     * that actually posted the notification on the device, and also displays the device type as a
     * bottom plate.
     */
    public static final int ICON_TYPE_BRIDGED_ICON = 2;

    @IconType int mIconTypeOverride = ICON_TYPE_INVALID;

    /** @hide */
    @IntDef(prefix = { "ICON_TYPE_" }, value = {
            ICON_TYPE_INVALID,
            ICON_TYPE_SMALL_ICON,
            ICON_TYPE_LAUNCHER_ICON,
            ICON_TYPE_BRIDGED_ICON,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface IconType {}

    public NotificationRowIconView(Context context) {
        super(context);
    }

    public NotificationRowIconView(Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public NotificationRowIconView(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public NotificationRowIconView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /**
     * Sets the icon provider for this view. This is used to determine whether we should show the
     * app icon instead of the small icon, and to fetch the app icon if needed.
     */
    public void setIconProvider(NotificationIconProvider iconProvider) {
        mIconProvider = iconProvider;
    }

    private Drawable loadAppIcon() {
        if (android.app.Flags.bridgedNotifications() && mIconProvider != null) {
            int effectiveIconType = mIconTypeOverride != ICON_TYPE_INVALID
                    ? mIconTypeOverride : mIconProvider.getIconType();
            return switch (effectiveIconType) {
                case ICON_TYPE_SMALL_ICON -> null;
                case ICON_TYPE_BRIDGED_ICON -> {
                    Drawable bridgedIcon = mIconProvider.getBridgedIcon();
                    if (bridgedIcon == null) {
                        Log.w(TAG, "Bridged notification metadata missing icon, falling back to"
                                + " launcher icon");
                        yield mIconProvider.getLauncherIcon();
                    }
                    yield bridgedIcon;
                }
                case ICON_TYPE_LAUNCHER_ICON -> mIconProvider.getLauncherIcon();
                default -> null;
            };
        } else {
            if (mIconProvider != null && mIconProvider.getIconType() == ICON_TYPE_LAUNCHER_ICON) {
                return mIconProvider.getLauncherIcon();
            }
            return null;
        }
    }

    @RemotableViewMethod(asyncImpl = "setImageIconAsync")
    @Override
    public void setImageIcon(Icon icon) {
        if (mAppIcon != null) {
            // We already know that we should be using the app icon, and we already loaded it.
            // We assume that cannot change throughout the lifetime of a notification, so
            // there's nothing to do here.
            return;
        }
        mAppIcon = loadAppIcon();
        if (mAppIcon != null) {
            setImageDrawable(mAppIcon);
            adjustViewForAppIcon();
        } else {
            super.setImageIcon(icon);
            restoreViewForSmallIcon();
        }
    }

    @RemotableViewMethod
    @Override
    public Runnable setImageIconAsync(Icon icon) {
        if (mAppIcon != null) {
            // We already know that we should be using the app icon, and we already loaded it.
            // We assume that cannot change throughout the lifetime of a notification, so
            // there's nothing to do here.
            return () -> {
            };
        }
        mAppIcon = loadAppIcon();
        if (mAppIcon != null) {
            return () -> {
                setImageDrawable(mAppIcon);
                adjustViewForAppIcon();
            };
        } else {
            return () -> {
                super.setImageIcon(icon);
                restoreViewForSmallIcon();
            };
        }
    }

    /**
     * Override padding and background from the view to display the app icon.
     */
    private void adjustViewForAppIcon() {
        removePadding();
        removeBackground();
    }

    /**
     * Restore padding and background overridden by {@link this#adjustViewForAppIcon}.
     * Does nothing if they were not overridden.
     */
    private void restoreViewForSmallIcon() {
        restorePadding();
        restoreBackground();
        restoreColors();
    }

    private void removePadding() {
        if (mOriginalPadding == null) {
            mOriginalPadding = new Rect(getPaddingLeft(), getPaddingTop(),
                    getPaddingRight(), getPaddingBottom());
        }
        setPadding(0, 0, 0, 0);
    }

    private void restorePadding() {
        if (mOriginalPadding != null) {
            setPadding(mOriginalPadding.left, mOriginalPadding.top,
                    mOriginalPadding.right,
                    mOriginalPadding.bottom);
            mOriginalPadding = null;
        }
    }

    private void removeBackground() {
        if (mOriginalBackground == null) {
            mOriginalBackground = getBackground();
        }

        setBackground(null);
    }

    private void restoreBackground() {
        // NOTE: This will not work if the original background was null, but that's better than
        //  accidentally clearing the background. We expect that there's generally going to be one
        //  anyway unless we manually clear it.
        if (mOriginalBackground != null) {
            setBackground(mOriginalBackground);
            mOriginalBackground = null;
        }
    }

    private void restoreColors() {
        if (mOriginalBackgroundColor != ColoredIconHelper.COLOR_INVALID) {
            super.setBackgroundColor(mOriginalBackgroundColor);
            mOriginalBackgroundColor = ColoredIconHelper.COLOR_INVALID;
        }
        if (mOriginalIconColor != ColoredIconHelper.COLOR_INVALID) {
            super.setOriginalIconColor(mOriginalIconColor);
            mOriginalIconColor = ColoredIconHelper.COLOR_INVALID;
        }
    }

    @RemotableViewMethod
    @Override
    public void setBackgroundColor(int color) {
        // Ignore color overrides if we're showing the app icon.
        if (mAppIcon == null) {
            super.setBackgroundColor(color);
        } else {
            mOriginalBackgroundColor = color;
        }
    }

    @RemotableViewMethod
    @Override
    public void setOriginalIconColor(int color) {
        // Ignore color overrides if we're showing the app icon.
        if (mAppIcon == null) {
            super.setOriginalIconColor(color);
        } else {
            mOriginalIconColor = color;
        }
    }

    /**
     * Manually set which type of icon should be used for the View. See AppIconType for possible
     * icon types.
     * Important: When setting the override to {@link ICON_TYPE_SMALL_ICON}, this call must be
     * followed by a {@link setImageIcon} call to actually set the drawable.
     */
    @RemotableViewMethod
    public void setIconTypeOverride(@IconType int iconType) {
        mIconTypeOverride = iconType;
        mAppIcon = loadAppIcon();
        if (mAppIcon != null) {
            setImageDrawable(mAppIcon);
            adjustViewForAppIcon();
        }
    }


    /**
     * A provider that allows this view to verify whether it should use the app icon instead of the
     * icon provided to it via setImageIcon, as well as actually fetching the app icon. It should
     * primarily be called on the background thread.
     */
    public interface NotificationIconProvider {

        /** Whether this notification should use the app icon, the small icon, or a bridged icon. */
        @IconType int getIconType();

        /**
         * If this is a bridged notification, this is the icon of the app that the notification
         * originates from, including a bottom plate representing the origin device type.
         * Otherwise, this is null.
         */
        @Nullable Drawable getBridgedIcon();

        /**
         * Get the icon associated with the app that posted this notification.
         * This may include the profile (e.g. work) badge, but no other decorations.
         */
        @Nullable Drawable getLauncherIcon();
    }
}
