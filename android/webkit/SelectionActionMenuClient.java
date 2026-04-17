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

package android.webkit;

import static java.lang.annotation.ElementType.TYPE_USE;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.view.MenuItem;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/**
 * Client implemented by an OEM to support customising WebView's selection menu. This class allows
 * specifying the order of the default items, adding custom items to the menu, filtering which text
 * processing activities are included in the menu and the handling of menu item clicks. This object
 * is requested by WebView through the WebViewDelegate. The object is app process global and the
 * same instance may be used across multiple WebView instances.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_SELECTION_ACTION_MENU_CLIENT)
public class SelectionActionMenuClient {
    /**
     * IntDef representing whether the selection menu is shown as a floating action menu or as a
     * dropdown menu anchored at the selected text.
     *
     * @hide
     */
    @IntDef({MENU_TYPE_FLOATING, MENU_TYPE_DROPDOWN})
    @Retention(RetentionPolicy.SOURCE)
    public @interface MenuType {}

    /**
     * Menu is shown above or below the selected text as a floating action menu.
     *
     * @hide
     */
    @SystemApi public static final int MENU_TYPE_FLOATING = 0;

    /**
     * Menu is shown as a dropdown window anchored to the selected text position.
     *
     * @hide
     */
    @SystemApi public static final int MENU_TYPE_DROPDOWN = 1;

    /**
     * IntDef representing each of the default items that may appear in a selection menu. These
     * items may not necessarily appear in all selection menus but are added if they are applicable
     * to the selected text.
     *
     * @hide
     */
    @IntDef({
        DEFAULT_ITEM_CUT,
        DEFAULT_ITEM_COPY,
        DEFAULT_ITEM_PASTE,
        DEFAULT_ITEM_PASTE_AS_PLAIN_TEXT,
        DEFAULT_ITEM_SHARE,
        DEFAULT_ITEM_SELECT_ALL,
        DEFAULT_ITEM_WEB_SEARCH
    })
    @Target(TYPE_USE)
    @Retention(RetentionPolicy.SOURCE)
    public @interface DefaultItem {}

    /**
     * Item which, when clicked, cuts the text and stores it in the clipboard.
     *
     * @hide
     */
    @SystemApi public static final int DEFAULT_ITEM_CUT = 1;

    /**
     * Item which, when clicked, copies the text into the clipboard.
     *
     * @hide
     */
    @SystemApi public static final int DEFAULT_ITEM_COPY = 2;

    /**
     * Item which, when clicked, inserts text from the clipboard at the selection position.
     *
     * @hide
     */
    @SystemApi public static final int DEFAULT_ITEM_PASTE = 3;

    /**
     * Item which, when clicked, inserts unstyled text from the clipboard at the selection position.
     *
     * @hide
     */
    @SystemApi public static final int DEFAULT_ITEM_PASTE_AS_PLAIN_TEXT = 4;

    /**
     * Item which, when clicked, opens a share dialog to send the text using one of the installed
     * apps.
     *
     * @hide
     */
    @SystemApi public static final int DEFAULT_ITEM_SHARE = 5;

    /**
     * Item which, when clicked, selects all of the text in the currently focused element.
     *
     * @hide
     */
    @SystemApi public static final int DEFAULT_ITEM_SELECT_ALL = 6;

    /**
     * Item which, when clicked, searches the web for the selected text.
     *
     * @hide
     */
    @SystemApi public static final int DEFAULT_ITEM_WEB_SEARCH = 7;

    /**
     * Return the order in which the default items should appear in the menu. The returned array
     * should contain all of the items in the DefaultItem IntDef exactly once. If the returned array
     * is malformed, the default order based on the IntDef values will be used.
     *
     * @param menuType Whether the menu is a floating action menu or a dropdown menu.
     * @return The order in which the menu's default items should be displayed.
     */
    public @NonNull @DefaultItem int[] getDefaultMenuItemOrder(@MenuType int menuType) {
        return new @DefaultItem int[] {
            DEFAULT_ITEM_CUT,
            DEFAULT_ITEM_COPY,
            DEFAULT_ITEM_PASTE,
            DEFAULT_ITEM_PASTE_AS_PLAIN_TEXT,
            DEFAULT_ITEM_SHARE,
            DEFAULT_ITEM_SELECT_ALL,
            DEFAULT_ITEM_WEB_SEARCH
        };
    }

    /**
     * Gets platform-specific additional menu items for text selection. MenuItems added here should
     * have unique IDs so that they can be associated with their corresponding handleMenuItemClick
     * call. To create an ID that does not collide with one of WebView's IDs, the implementer should
     * use {@code View.generateViewId()}. Some of the MenuItem's data will be overridden such as its
     * group ID and showAsActionFlags however order is guaranteed to be maintained.
     *
     * @param context The application context
     * @param menuType Whether the menu is a floating action menu or a dropdown menu.
     * @param isSelectionPassword Whether the selected text is from a password field
     * @param isSelectionReadOnly Whether the selected text is from a read-only field
     * @param selectedText The currently selected text
     * @return List of MenuItem objects representing additional menu items
     */
    public @NonNull List<MenuItem> getAdditionalMenuItems(
            @NonNull Context context,
            @MenuType int menuType,
            boolean isSelectionPassword,
            boolean isSelectionReadOnly,
            @NonNull String selectedText) {
        return List.of();
    }

    /**
     * Filters supported text processing activities (for example based on the user's settings
     * preferences).
     *
     * @param context The application context
     * @param menuType Whether the menu is a floating action menu or a dropdown menu.
     * @param activities The list of ResolveInfo objects representing available text processing
     *     activities
     * @return Filtered list containing only the supported activities. By default, this method
     *     should return the passed activities list.
     */
    public @NonNull List<ResolveInfo> filterTextProcessingActivities(
            @NonNull Context context,
            @MenuType int menuType,
            @NonNull List<ResolveInfo> activities) {
        return activities;
    }

    /**
     * Handles click events for platform-specific menu items. If you add items using
     * getAdditionalMenuItems, you must implement logic to handle their clicks here. The MenuItem's
     * click handler will not be invoked as it is assumed that the click will be handled here.
     *
     * @param context The application context
     * @param item The menu item that was clicked
     * @return true if the click was handled, false otherwise
     */
    public boolean handleMenuItemClick(@NonNull Context context, @NonNull MenuItem item) {
        return false;
    }
}
