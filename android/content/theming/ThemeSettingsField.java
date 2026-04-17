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

package android.content.theming;


import android.annotation.FlaggedApi;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/**
 * @hide
 */
@FlaggedApi(android.server.Flags.FLAG_ENABLE_THEME_SERVICE)
public abstract class ThemeSettingsField<T, J> {

    /**
     * Parses a string representation into the field's value type.
     *
     * @param primitive The string representation to parse.
     * @return The parsed value, or null if parsing fails.
     */
    @VisibleForTesting
    @Nullable
    public abstract T parse(J primitive);

    /**
     * Serializes the field's value into a primitive type suitable for JSON.
     *
     * @param value The value to serialize.
     * @return The serialized value.
     */
    @VisibleForTesting
    public abstract J serialize(T value);

    /**
     * Validates the field's value.
     * This method can be overridden to perform custom validation logic and MUST NOT validate for
     * nullity.
     *
     * @param value The value to validate.
     * @return {@code true} if the value is valid, {@code false} otherwise.
     */
    @VisibleForTesting
    public abstract boolean validate(T value);

    /**
     * Returns the type of the field's value.
     *
     * @return The type of the field's value.
     */
    @VisibleForTesting
    public abstract Class<T> getFieldType();

    /**
     * Returns the type of the field's value stored in JSON.
     *
     * <p>This method is used to determine the expected type of the field's value when it is
     * stored in a JSON object.
     *
     * @return The type of the field's value stored in JSON.
     */
    @VisibleForTesting
    public abstract Class<J> getJsonType();
}
