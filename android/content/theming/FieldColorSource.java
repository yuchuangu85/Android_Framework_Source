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

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.FlaggedApi;
import android.annotation.StringDef;

import androidx.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Objects;

/** @hide */
@FlaggedApi(android.server.Flags.FLAG_ENABLE_THEME_SERVICE)
public final class FieldColorSource extends ThemeSettingsField<String, String> {
    public static final String VALUE_PRESET = "preset";
    public static final String VALUE_HOME_WALLPAPER = "home_wallpaper";

    @Override
    @Nullable
    @Type
    public String parse(String primitive) {
        return primitive;
    }

    @Override
    public String serialize(@Type String typedValue) {
        return typedValue;
    }

    @Override
    public boolean validate(String value) {
        Objects.requireNonNull(value);
        return switch (value) {
            case VALUE_PRESET, VALUE_HOME_WALLPAPER -> true;
            default -> false;
        };
    }

    @Override
    public Class<String> getFieldType() {
        return String.class;
    }

    @Override
    public Class<String> getJsonType() {
        return String.class;
    }


    @StringDef({VALUE_PRESET, VALUE_HOME_WALLPAPER})
    @Target({PARAMETER, METHOD, LOCAL_VARIABLE, FIELD})
    @Retention(SOURCE)
    @interface Type {
    }
}
