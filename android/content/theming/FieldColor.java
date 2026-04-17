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
import android.annotation.NonNull;
import android.graphics.Color;

import androidx.annotation.Nullable;

import java.util.Objects;
import java.util.regex.Pattern;

/** @hide */
@FlaggedApi(android.server.Flags.FLAG_ENABLE_THEME_SERVICE)
public final class FieldColor extends ThemeSettingsField<Color, String> {
    private static final Pattern COLOR_PATTERN = Pattern.compile("[0-9a-fA-F]{6,8}");

    @Override
    @Nullable
    public Color parse(String primitive) {
        if (primitive == null) {
            return null;
        }
        if (!COLOR_PATTERN.matcher(primitive).matches()) {
            return null;
        }

        try {
            return Color.valueOf(Color.parseColor("#" + primitive));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String serialize(Color value) {
        return Integer.toHexString(value.toArgb()).toUpperCase();
    }

    @Override
    public boolean validate(@NonNull Color value) {
        Objects.requireNonNull(value);
        return value.toArgb() != Color.TRANSPARENT;
    }

    @Override
    public Class<Color> getFieldType() {
        return Color.class;
    }

    @Override
    public Class<String> getJsonType() {
        return String.class;
    }
}
