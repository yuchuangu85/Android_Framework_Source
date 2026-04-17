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
package android.window;

import static android.view.WindowManager.LayoutParams.INVALID_WINDOW_TYPE;
import static android.view.WindowManager.LayoutParams.isSubWindowType;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.IBinder;
import android.view.WindowManager.LayoutParams.WindowType;

/**
 * An interface to provide a non-activity window.
 * Examples are {@link WindowContext} and {@link WindowProviderService}.
 *
 * @hide
 */
public interface WindowProvider {
    /** @hide */
    String KEY_IS_WINDOW_PROVIDER_SERVICE = "android.window.WindowProvider.isWindowProviderService";

    /**
     * The key to indicate whether the WindowContext should be reparented to the default display
     * when the currently attached display is removed.
     * <p>
     * By default, the value is {@code false}, which means the WindowContext is removed with
     * the display removal. If the value is {@code true}, the WindowContext will be reparented to
     * the default display instead.
     * <p>
     * Type: Boolean
     *
     * @hide
     */
    String KEY_REPARENT_TO_DEFAULT_DISPLAY_WITH_DISPLAY_REMOVAL =
            "android.window.WindowProvider.reparentToDefaultDisplayWithDisplayRemoval";

    /** Gets the window type of this provider */
    @WindowType
    int getWindowType();

    /** Gets the launch options of this provider */
    @Nullable
    Bundle getWindowContextOptions();

    /**
     * Gets the WindowContextToken of this provider.
     * @see android.content.Context#getWindowContextToken
     */
    @NonNull
    IBinder getWindowContextToken();

    /**
     * Gets the window type to be overridden when {@link android.view.WindowManager#addView}
     * and {@link android.view.WindowManager#updateViewLayout} if the added window is not
     * {@link #isSelfOrSubWindowType(int)}.
     */
    @WindowType
    default int getFallbackWindowType() {
        return INVALID_WINDOW_TYPE;
    }

    /**
     * Returns {@code true} if the given type is {@link #getWindowType()} or a sub-window type.
     *
     * @param type the requested window type
     */
    default boolean isSelfOrSubWindowType(@WindowType int type) {
        return getWindowType() == type || isSubWindowType(type);
    }
}
