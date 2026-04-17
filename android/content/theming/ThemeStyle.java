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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.TestApi;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * A class defining the different styles available for theming.
 * This class replaces the previous enum implementation for improved performance and compatibility.
 *
 * @hide
 */
@TestApi
@FlaggedApi(android.server.Flags.FLAG_ENABLE_THEME_SERVICE)
public final class ThemeStyle {
    private ThemeStyle() {
        // Utility class
    }

    /**
     * @hide
     */
    @IntDef({
            SPRITZ,
            TONAL_SPOT,
            VIBRANT,
            EXPRESSIVE,
            RAINBOW,
            FRUIT_SALAD,
            CONTENT,
            MONOCHROMATIC,
            CLOCK,
            CLOCK_VIBRANT
    })
    @Retention(SOURCE)
    @Target({PARAMETER, METHOD, LOCAL_VARIABLE, FIELD})
    public @interface Type {
    }

    /**
     * Represents the SPRITZ style.
     */
    public static final int SPRITZ = 0;
    /**
     * Represents the TONAL_SPOT style.
     */
    public static final int TONAL_SPOT = 1;
    /**
     * Represents the VIBRANT style.
     */
    public static final int VIBRANT = 2;
    /**
     * Represents the EXPRESSIVE style.
     */
    public static final int EXPRESSIVE = 3;
    /**
     * Represents the RAINBOW style.
     */
    public static final int RAINBOW = 4;
    /**
     * Represents the FRUIT_SALAD style.
     */
    public static final int FRUIT_SALAD = 5;
    /**
     * Represents the CONTENT style.
     */
    public static final int CONTENT = 6;
    /**
     * Represents the MONOCHROMATIC style.
     */
    public static final int MONOCHROMATIC = 7;
    /**
     * Represents the CLOCK style.
     */
    public static final int CLOCK = 8;
    /**
     * Represents the CLOCK_VIBRANT style.
     */
    public static final int CLOCK_VIBRANT = 9;


    /**
     * Returns the string representation of the given style.
     *
     * @param style The style value.
     * @return The string representation of the style.
     * @throws IllegalArgumentException if the style value is invalid.
     * @throws NullPointerException if the style value is null.
     */
    @NonNull
    public static String toString(@Type int style) {
        return switch (style) {
            case SPRITZ -> "SPRITZ";
            case TONAL_SPOT -> "TONAL_SPOT";
            case VIBRANT -> "VIBRANT";
            case EXPRESSIVE -> "EXPRESSIVE";
            case RAINBOW -> "RAINBOW";
            case FRUIT_SALAD -> "FRUIT_SALAD";
            case CONTENT -> "CONTENT";
            case MONOCHROMATIC -> "MONOCHROMATIC";
            case CLOCK -> "CLOCK";
            case CLOCK_VIBRANT -> "CLOCK_VIBRANT";
            default -> throw new IllegalArgumentException("Invalid style value: " + style);
        };
    }

    /**
     * Returns the style value corresponding to the given style name.
     *
     * @param styleName The name of the style.
     * @return The style value.
     * @throws IllegalArgumentException if the style name is invalid.
     * @throws NullPointerException if the style name is null.
     */
    @Type
    public static  int valueOf(@NonNull String styleName) {
        return switch (styleName) {
            case "SPRITZ" -> SPRITZ;
            case "TONAL_SPOT" -> TONAL_SPOT;
            case "VIBRANT" -> VIBRANT;
            case "EXPRESSIVE" -> EXPRESSIVE;
            case "RAINBOW" -> RAINBOW;
            case "FRUIT_SALAD" -> FRUIT_SALAD;
            case "CONTENT" -> CONTENT;
            case "MONOCHROMATIC" -> MONOCHROMATIC;
            case "CLOCK" -> CLOCK;
            case "CLOCK_VIBRANT" -> CLOCK_VIBRANT;
            default -> throw new IllegalArgumentException("Invalid style name: " + styleName);
        };
    }

    /**
     * Returns the name of the given style. This method is equivalent to {@link #toString(Integer)}.
     *
     * @param style The style value.
     * @return The name of the style.
     */
    @NonNull
    public static String name(@Type int style) {
        return toString(style);
    }

    /**
     * Returns an array containing all the style values.
     *
     * @return An array of all style values.
     */
    @NonNull
    public static int[] values() {
        return new int[]{
                SPRITZ,
                TONAL_SPOT,
                VIBRANT,
                EXPRESSIVE,
                RAINBOW,
                FRUIT_SALAD,
                CONTENT,
                MONOCHROMATIC,
                CLOCK,
                CLOCK_VIBRANT
        };
    }
}
