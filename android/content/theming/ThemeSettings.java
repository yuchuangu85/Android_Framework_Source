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
import android.os.Parcel;
import android.os.Parcelable;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents the theme settings for the system.
 * This class holds the core properties that define a user's chosen theme, such as the color
 * source, theme style, and an optional preset color. It is designed to be immutable.
 *
 * @hide
 */
@FlaggedApi(android.server.Flags.FLAG_ENABLE_THEME_SERVICE)
public final class ThemeSettings implements Parcelable {
    private final Instant mAppliedTimestamp;
    @ThemeStyle.Type
    private final int mThemeStyle;
    @FieldColorSource.Type
    private final String mColorSource;
    @NonNull
    private final Color mSystemPalette;

    private ThemeSettings(Instant appliedTimestamp, @ThemeStyle.Type int themeStyle,
            @FieldColorSource.Type String colorSource, @NonNull Color systemPalette) {
        this.mAppliedTimestamp = appliedTimestamp;
        this.mThemeStyle = themeStyle;
        this.mColorSource = colorSource;
        this.mSystemPalette = systemPalette;
    }

    private ThemeSettings(Parcel in) {
        mAppliedTimestamp = Instant.ofEpochMilli(in.readLong());
        mThemeStyle = in.readInt();
        mColorSource = in.readString8();
        mSystemPalette = Color.valueOf(in.readInt());
    }

    /**
     * Returns the timestamp indicating when these theme settings were applied or generated.
     */
    public Instant timeStamp() {
        return mAppliedTimestamp;
    }

    /**
     * Returns the source of the theme's color (e.g., "preset" or "home_wallpaper").
     */
    @NonNull
    @FieldColorSource.Type
    public String colorSource() {
        return mColorSource;
    }

    /**
     * Returns the style of the theme (e.g., TONAL_SPOT, VIBRANT).
     *
     * @return The seed {@link ThemeStyle.Type}.
     */
    @ThemeStyle.Type
    public int themeStyle() {
        return mThemeStyle;
    }

    /**
     * Returns the system palette color used for Palette generation.
     *
     * @return The seed {@link Color}.
     */
    @NonNull
    public Color systemPalette() {
        return mSystemPalette;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ThemeSettings that = (ThemeSettings) o;
        return mThemeStyle == that.mThemeStyle
                && Objects.equals(mAppliedTimestamp, that.mAppliedTimestamp)
                && Objects.equals(mColorSource, that.mColorSource)
                && Objects.equals(mSystemPalette, that.mSystemPalette);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAppliedTimestamp, mThemeStyle, mColorSource, mSystemPalette);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mAppliedTimestamp.toEpochMilli());
        dest.writeInt(mThemeStyle);
        dest.writeString8(mColorSource);
        dest.writeInt(mSystemPalette.toArgb());
    }

    public static final Creator<ThemeSettings> CREATOR = new Creator<ThemeSettings>() {
        @Override
        public ThemeSettings createFromParcel(Parcel in) {
            return new ThemeSettings(in);
        }

        @Override
        public ThemeSettings[] newArray(int size) {
            return new ThemeSettings[size];
        }
    };

    @NonNull
    @Override
    public String toString() {
        return "ThemeSettings{"
                + "mAppliedTimestamp=" + mAppliedTimestamp
                + ", mThemeStyle=" + mThemeStyle
                + ", mColorSource='" + mColorSource + "'"
                + ", mSystemPalette=" + mSystemPalette
                + '}';
    }

    /**
     * Builder for creating {@link ThemeSettings} instances.
     */
    public static final class Builder {
        private Instant mAppliedTimestamp;
        private Integer mThemeStyle;
        private String mColorSource;
        private Color mSystemPalette;

        public Builder() {
        }

        /**
         * Sets the timestamp indicating when these theme settings were applied or generated.
         * If not set, {@link Instant#now()} will be used when building.
         *
         * @param appliedTimestamp The timestamp.
         * @return This Builder object to allow for chaining of calls.
         */
        public Builder setAppliedTimestamp(@NonNull Instant appliedTimestamp) {
            mAppliedTimestamp = appliedTimestamp;
            return this;
        }

        /**
         * Sets the style of the theme (e.g., TONAL_SPOT, VIBRANT).
         *
         * @param themeStyle The theme style.
         * @return This Builder object to allow for chaining of calls.
         */
        public Builder setThemeStyle(@ThemeStyle.Type int themeStyle) {
            mThemeStyle = themeStyle;
            return this;
        }

        /**
         * Sets the source of the theme's color (e.g., "preset" or "home_wallpaper").
         *
         * @param colorSource The color source.
         * @return This Builder object to allow for chaining of calls.
         */
        public Builder setColorSource(@FieldColorSource.Type String colorSource) {
            mColorSource = colorSource;
            return this;
        }

        /**
         * Sets the system palette color.
         *
         * @param systemPalette The system palette color.
         * @return This Builder object to allow for chaining of calls.
         */
        public Builder setSystemPalette(@NonNull Color systemPalette) {
            mSystemPalette = systemPalette;
            return this;
        }

        @NonNull
        public ThemeSettings build() {
            if (mThemeStyle == null) {
                throw new IllegalStateException("ThemeStyle must be set.");
            }
            if (mColorSource == null) {
                throw new IllegalStateException("ColorSource must be set.");
            }
            if (mSystemPalette == null) {
                throw new IllegalStateException("SystemPalette must be set.");
            }

            if (!new FieldThemeStyle().validate(mThemeStyle)) {
                throw new IllegalArgumentException("Invalid themeStyle: " + mThemeStyle);
            }

            if (!new FieldColor().validate(mSystemPalette)) {
                throw new IllegalArgumentException("Invalid systemPalette color.");
            }

            if (!new FieldColorSource().validate(mColorSource)) {
                throw new IllegalArgumentException("Invalid colorSource: " + mColorSource);
            }

            Instant timestamp = (mAppliedTimestamp != null) ? mAppliedTimestamp : Instant.now();
            return new ThemeSettings(timestamp, mThemeStyle, mColorSource, mSystemPalette);
        }
    }
}