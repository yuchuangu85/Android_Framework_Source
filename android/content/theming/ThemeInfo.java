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
import android.annotation.Nullable;
import android.graphics.Color;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Represents the core information of a user's theme, including the seed color,
 * style, and contrast level.
 *
 * @hide
 */
@FlaggedApi(android.server.Flags.FLAG_ENABLE_THEME_SERVICE)
public final class ThemeInfo implements Parcelable {
    @Nullable
    public final Color seedColor;
    @Nullable
    @ThemeStyle.Type
    public final Integer style;
    @Nullable
    public final Float contrast;
    @Nullable
    public final String specVersion;
    @Nullable
    public final String platform;

    private ThemeInfo(@Nullable Color seedColor, @Nullable Integer style,
            @Nullable Float contrast) {
        this.seedColor = seedColor;
        this.style = style;
        this.contrast = contrast;
        this.specVersion = null;
        this.platform = null;
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public ThemeInfo(@NonNull Color seedColor, @NonNull Integer style, @NonNull Float contrast,
            @NonNull String specVersion, @NonNull String platform) {
        this.seedColor = seedColor;
        this.style = style;
        this.contrast = contrast;
        this.specVersion = specVersion;
        this.platform = platform;
    }

    private ThemeInfo(Parcel in) {
        Integer seedArgb = (Integer) in.readValue(Integer.class.getClassLoader());
        this.seedColor = (seedArgb == null) ? null : Color.valueOf(seedArgb);
        this.style = (Integer) in.readValue(Integer.class.getClassLoader());
        this.contrast = (Float) in.readValue(Float.class.getClassLoader());
        this.specVersion = in.readString8();
        this.platform = in.readString8();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeValue(seedColor != null ? seedColor.toArgb() : null);
        dest.writeValue(style);
        dest.writeValue(contrast);
        dest.writeString8(specVersion);
        dest.writeString8(platform);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Parcelable.Creator<ThemeInfo> CREATOR =
            new Parcelable.Creator<ThemeInfo>() {
                @Override
                public ThemeInfo createFromParcel(Parcel in) {
                    return new ThemeInfo(in);
                }

                @Override
                public ThemeInfo[] newArray(int size) {
                    return new ThemeInfo[size];
                }
            };

    /**
     * A builder for creating {@link ThemeInfo} instances.  Any missing parameter indicates that
     * the current system value for that attribute should be used.
     */
    public static class Builder {
        private Color mSeedColor;
        private Integer mStyle;
        private Float mContrast;

        public Builder() {
        }

        /**
         * Sets the seed color for the theme.
         */
        public Builder setSeedColor(@Nullable Color seedColor) {
            mSeedColor = seedColor;
            return this;
        }

        /**
         * Sets the theme style.
         */
        public Builder setStyle(@Nullable Integer style) {
            mStyle = style;
            return this;
        }

        /**
         * Sets the theme contrast.
         */
        public Builder setContrast(@Nullable Float contrast) {
            mContrast = contrast;
            return this;
        }

        /**
         * Builds the {@link ThemeInfo} instance.
         */
        public ThemeInfo build() {
            return new ThemeInfo(mSeedColor, mStyle, mContrast);
        }
    }
}
