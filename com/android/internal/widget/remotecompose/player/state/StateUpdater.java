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
package com.android.internal.widget.remotecompose.player.state;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Bitmap;

/** Methods to update the state of a {@link RemoteContext}. */
public interface StateUpdater {

    /**
     * Calls {@link RemoteContext#setNamedLong(String, long)}.
     *
     * @param name the name of the float to override
     * @param value Override the default float
     */
    void setNamedLong(@NonNull String name, @Nullable Long value);

    /**
     * Calls {@link RemoteContext#setNamedFloatOverride(String, float)} adding {@link
     * RemoteDomains#USER} as a prefix to the floatName.
     *
     * @param floatName the original name of the float parameter.
     * @param value the float value to set.
     */
    void setUserLocalFloat(@NonNull String floatName, @Nullable Float value);

    /**
     * Calls {@link RemoteContext#setNamedIntegerOverride(String, int)} adding {@link
     * RemoteDomains#USER} as a prefix to the integerName.
     *
     * @param integerName the original name of the integer parameter.
     * @param value the integer value to set.
     */
    void setUserLocalInt(@NonNull String integerName, @Nullable Integer value);

    /**
     * Calls {@link RemoteContext#setNamedColorOverride(String, int)} adding {@link
     * RemoteDomains#USER} as a prefix to the name.
     *
     * @param name the original name of the color parameter.
     * @param value the color value to set (as an int).
     */
    void setUserLocalColor(@NonNull String name, @Nullable Integer value);

    /**
     * Calls {@link RemoteContext#setNamedDataOverride(String, Object)} adding {@link
     * RemoteDomains#USER} as a prefix to the name.
     *
     * @param name the original name of the data parameter.
     * @param content the {@link Bitmap} content to set.
     */
    void setUserLocalBitmap(@NonNull String name, @Nullable Bitmap content);

    /**
     * Calls {@link RemoteContext#setNamedStringOverride(String, String)} adding {@link
     * RemoteDomains#USER} as a prefix to the stringName.
     *
     * @param stringName the original name of the string parameter.
     * @param value the string value to set.
     */
    void setUserLocalString(@NonNull String stringName, @Nullable String value);

    /**
     * Returns the user domain string for the given parameter name.
     *
     * @param name the original name of the parameter.
     * @return the user domain string for the given parameter name.
     */
    static @NonNull String getUserDomainString(@NonNull String name) {
        return RemoteDomains.USER + ":" + name;
    }
}
