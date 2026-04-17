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
package android.app.permissionui;

import android.annotation.ColorInt;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.content.res.Configuration;
import android.os.Parcel;
import android.os.Parcelable;
import android.permission.flags.Flags;

import java.util.Objects;

/**
 * Defines the requested visual properties for a location button.
 *
 * <p>An instance of this class is passed to {@link LocationButtonProvider#openSession} to
 * configure the button's appearance, such as its text, colors, and corner radius etc.
 * The appearance can be changed after the session has been opened.
 *
 * @see LocationButtonProvider#openSession
 * @see LocationButtonSession
 */
@FlaggedApi(Flags.FLAG_LOCATION_BUTTON_ENABLED)
public final class LocationButtonRequest implements Parcelable {
    // Bitmask flags for tracking which properties were explicitly set
    private static final int PROPERTY_PADDING_LEFT = 1 << 0;
    private static final int PROPERTY_PADDING_TOP = 1 << 1;
    private static final int PROPERTY_PADDING_RIGHT = 1 << 2;
    private static final int PROPERTY_PADDING_BOTTOM = 1 << 3;
    private static final int PROPERTY_BACKGROUND_COLOR = 1 << 4;
    private static final int PROPERTY_STROKE_COLOR = 1 << 5;
    private static final int PROPERTY_STROKE_WIDTH = 1 << 6;
    private static final int PROPERTY_CORNER_RADIUS = 1 << 7;
    private static final int PROPERTY_PRESSED_CORNER_RADIUS = 1 << 8;
    private static final int PROPERTY_ICON_TINT = 1 << 9;
    private static final int PROPERTY_TEXT_TYPE = 1 << 10;
    private static final int PROPERTY_TEXT_COLOR = 1 << 11;

    private final int mWidth;
    private final int mHeight;
    private final int mPaddingLeft;
    private final int mPaddingRight;
    private final int mPaddingTop;
    private final int mPaddingBottom;
    @ColorInt
    private final int mBackgroundColor;
    @ColorInt
    private final int mStrokeColor;
    private final int mStrokeWidth;
    private final float mCornerRadius;
    private final float mPressedCornerRadius;
    @ColorInt
    private final int mIconTint;
    private final @LocationButtonSession.TextType int mTextType;
    @ColorInt
    private final int mTextColor;
    private final Configuration mConfiguration;

    private final int mPropertiesSet;

    /**
     * Creates a new {@link LocationButtonRequest} instance.
     * Use {@link Builder} to create instances.
     */
    private LocationButtonRequest(int width, int height, int paddingLeft, int paddingTop,
            int paddingRight, int paddingBottom, @ColorInt int backgroundColor,
            @ColorInt int strokeColor, int strokeWidth, float cornerRadius,
            float pressedCornerRadius, @ColorInt int iconTint,
            @LocationButtonSession.TextType int textType,
            @ColorInt int textColor, @NonNull Configuration configuration,
            int propertiesSet) {
        this.mWidth = width;
        this.mHeight = height;
        this.mPaddingLeft = paddingLeft;
        this.mPaddingTop = paddingTop;
        this.mPaddingRight = paddingRight;
        this.mPaddingBottom = paddingBottom;
        this.mBackgroundColor = backgroundColor;
        this.mStrokeColor = strokeColor;
        this.mStrokeWidth = strokeWidth;
        this.mCornerRadius = cornerRadius;
        this.mPressedCornerRadius = pressedCornerRadius;
        this.mIconTint = iconTint;
        this.mTextType = textType;
        this.mTextColor = textColor;
        this.mConfiguration = configuration;
        this.mPropertiesSet = propertiesSet;
    }

    /** Returns the width of the button. */
    public int getWidth() {
        return mWidth;
    }

    /** Returns the height of the button. */
    public int getHeight() {
        return mHeight;
    }

    /** Returns the left padding of the button. */
    public int getPaddingLeft() {
        return mPaddingLeft;
    }

    /**
     * Returns true if the left padding was explicitly set.
     * @hide
     */
    public boolean hasPaddingLeft() {
        return (mPropertiesSet & PROPERTY_PADDING_LEFT) != 0;
    }

    /** Returns the right padding of the button. */
    public int getPaddingRight() {
        return mPaddingRight;
    }

    /**
     * Returns true if the right padding was explicitly set.
     * @hide
     */
    public boolean hasPaddingRight() {
        return (mPropertiesSet & PROPERTY_PADDING_RIGHT) != 0;
    }

    /** Returns the top padding of the button. */
    public int getPaddingTop() {
        return mPaddingTop;
    }

    /**
     * Returns true if the top padding was explicitly set.
     * @hide
     */
    public boolean hasPaddingTop() {
        return (mPropertiesSet & PROPERTY_PADDING_TOP) != 0;
    }

    /** Returns the bottom padding of the button. */
    public int getPaddingBottom() {
        return mPaddingBottom;
    }

    /**
     * Returns true if the bottom padding was explicitly set.
     * @hide
     */
    public boolean hasPaddingBottom() {
        return (mPropertiesSet & PROPERTY_PADDING_BOTTOM) != 0;
    }

    /** Returns the background color of the button as a {@link ColorInt}. */
    @ColorInt
    public int getBackgroundColor() {
        return mBackgroundColor;
    }

    /**
     * Returns true if the background color was explicitly set.
     * @hide
     */
    public boolean hasBackgroundColor() {
        return (mPropertiesSet & PROPERTY_BACKGROUND_COLOR) != 0;
    }

    /** Returns the button outline/border color as a {@link ColorInt}. */
    @ColorInt
    public int getStrokeColor() {
        return mStrokeColor;
    }

    /**
     * Returns true if the stroke color was explicitly set.
     * @hide
     */
    public boolean hasStrokeColor() {
        return (mPropertiesSet & PROPERTY_STROKE_COLOR) != 0;
    }

    /** Returns the button outline/border width. */
    public int getStrokeWidth() {
        return mStrokeWidth;
    }

    /**
     * Returns true if the stroke width was explicitly set.
     * @hide
     */
    public boolean hasStrokeWidth() {
        return (mPropertiesSet & PROPERTY_STROKE_WIDTH) != 0;
    }

    /** Returns the corner radius of the button in pixels. */
    public float getCornerRadius() {
        return mCornerRadius;
    }

    /**
     * Returns true if the corner radius was explicitly set.
     * @hide
     */
    public boolean hasCornerRadius() {
        return (mPropertiesSet & PROPERTY_CORNER_RADIUS) != 0;
    }

    /** Returns the corner radius of the button when pressed. */
    public float getPressedCornerRadius() {
        return mPressedCornerRadius;
    }

    /**
     * Returns true if the pressed corner radius was explicitly set.
     * @hide
     */
    public boolean hasPressedCornerRadius() {
        return (mPropertiesSet & PROPERTY_PRESSED_CORNER_RADIUS) != 0;
    }

    /** Returns the icon tint color as a {@link ColorInt}. */
    @ColorInt
    public int getIconTint() {
        return mIconTint;
    }

    /**
     * Returns true if the icon tint was explicitly set.
     * @hide
     */
    public boolean hasIconTint() {
        return (mPropertiesSet & PROPERTY_ICON_TINT) != 0;
    }

    /** Returns the text type displayed on the button. */
    public @LocationButtonSession.TextType int getTextType() {
        return mTextType;
    }

    /**
     * Returns true if the text type was explicitly set.
     * @hide
     */
    public boolean hasTextType() {
        return (mPropertiesSet & PROPERTY_TEXT_TYPE) != 0;
    }

    /** Returns the color of the button's text as a {@link ColorInt}. */
    @ColorInt
    public int getTextColor() {
        return mTextColor;
    }

    /**
     * Returns true if the text color was explicitly set.
     * @hide
     */
    public boolean hasTextColor() {
        return (mPropertiesSet & PROPERTY_TEXT_COLOR) != 0;
    }

    /** Returns the configuration of the button. */
    @NonNull
    public Configuration getConfiguration() {
        return mConfiguration;
    }

    @Override
    public String toString() {
        return "LocationButtonRequest{"
                + " mWidth=" + mWidth
                + ", mHeight=" + mHeight
                + ", mPaddingLeft=" + mPaddingLeft
                + ", mPaddingTop=" + mPaddingTop
                + ", mPaddingRight=" + mPaddingRight
                + ", mPaddingBottom=" + mPaddingBottom
                + ", mBackgroundColor=" + mBackgroundColor
                + ", mStrokeColor=" + mStrokeColor
                + ", mStrokeWidth=" + mStrokeWidth
                + ", mCornerRadius=" + mCornerRadius
                + ", mPressedCornerRadius=" + mPressedCornerRadius
                + ", mIconTint=" + mIconTint
                + ", mTextType=" + mTextType
                + ", mTextColor=" + mTextColor
                + ", mConfiguration=" + mConfiguration
                + ", mPropertiesSet=0x" + Integer.toHexString(mPropertiesSet)
                + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mWidth);
        dest.writeInt(mHeight);
        dest.writeInt(mPaddingLeft);
        dest.writeInt(mPaddingTop);
        dest.writeInt(mPaddingRight);
        dest.writeInt(mPaddingBottom);
        dest.writeInt(mBackgroundColor);
        dest.writeInt(mStrokeColor);
        dest.writeInt(mStrokeWidth);
        dest.writeFloat(mCornerRadius);
        dest.writeFloat(mPressedCornerRadius);
        dest.writeInt(mIconTint);
        dest.writeInt(mTextType);
        dest.writeInt(mTextColor);
        dest.writeTypedObject(mConfiguration, flags);
        dest.writeInt(mPropertiesSet);
    }

    public static final @NonNull Creator<LocationButtonRequest> CREATOR =
            new Creator<LocationButtonRequest>() {
                @Override
                public LocationButtonRequest createFromParcel(Parcel in) {
                    int width = in.readInt();
                    int height = in.readInt();
                    int paddingLeft = in.readInt();
                    int paddingTop = in.readInt();
                    int paddingRight = in.readInt();
                    int paddingBottom = in.readInt();
                    int backgroundColor = in.readInt();
                    int strokeColor = in.readInt();
                    int strokeWidth = in.readInt();
                    float cornerRadius = in.readFloat();
                    float pressedCornerRadius = in.readFloat();
                    int iconTint = in.readInt();
                    int textType = in.readInt();
                    int textColor = in.readInt();
                    Configuration configuration = Objects.requireNonNull(
                            in.readTypedObject(Configuration.CREATOR));
                    int propertiesSet = in.readInt();

                    return new LocationButtonRequest(width, height, paddingLeft, paddingTop,
                            paddingRight, paddingBottom, backgroundColor, strokeColor, strokeWidth,
                            cornerRadius, pressedCornerRadius, iconTint, textType, textColor,
                            configuration, propertiesSet);
                }

                @Override
                public LocationButtonRequest[] newArray(int size) {
                    return new LocationButtonRequest[size];
                }
            };

    /**
     * Builder for {@link LocationButtonRequest}.
     */
    public static final class Builder {
        private final int mWidth;
        private final int mHeight;
        private final Configuration mConfiguration;

        // Tracks which setters have been called
        private int mPropertiesSet = 0;

        private int mPaddingLeft;
        private int mPaddingRight;
        private int mPaddingTop;
        private int mPaddingBottom;

        @ColorInt
        private int mBackgroundColor;
        @ColorInt
        private int mStrokeColor;

        private int mStrokeWidth;
        private float mCornerRadius;
        private float mPressedCornerRadius;

        @ColorInt
        private int mIconTint;

        private @LocationButtonSession.TextType int mTextType;

        @ColorInt
        private int mTextColor;

        /**
         * Creates a new Builder with the required initial properties.
         *
         * <p>The provided {@code width} must be at least 48dp. The provided {@code height} must be
         * between 48dp and 136dp (inclusive). Values outside these ranges will be clamped by the
         * system during session creation and resizing.
         *
         * @param width The total width of the button in pixels.
         * @param height The total height of the button in pixels.
         * @param configuration The {@link Configuration} of the context hosting the button.
         */
        public Builder(int width, int height, @NonNull Configuration configuration) {
            mWidth = width;
            mHeight = height;
            mConfiguration = Objects.requireNonNull(configuration);
        }

        /**
         * Sets the left padding of the button.
         *
         * <p>The padding must be between 0 and 8dp (inclusive). Values outside this range will be
         * clamped. If not explicitly set, it defaults to 0.
         */
        @NonNull
        public Builder setPaddingLeft(int paddingLeft) {
            mPaddingLeft = paddingLeft;
            mPropertiesSet |= PROPERTY_PADDING_LEFT;
            return this;
        }

        /**
         * Sets the top padding of the button.
         *
         * <p>The padding must be between 0 and 8dp (inclusive). Values outside this range will be
         * clamped. If not explicitly set, it defaults to 0.
         */
        @NonNull
        public Builder setPaddingTop(int paddingTop) {
            mPaddingTop = paddingTop;
            mPropertiesSet |= PROPERTY_PADDING_TOP;
            return this;
        }

        /**
         * Sets the right padding of the button.
         *
         * <p>The padding must be between 0 and 8dp (inclusive). Values outside this range will be
         * clamped. If not explicitly set, it defaults to 0.
         */
        @NonNull
        public Builder setPaddingRight(int paddingRight) {
            mPaddingRight = paddingRight;
            mPropertiesSet |= PROPERTY_PADDING_RIGHT;
            return this;
        }

        /**
         * Sets the bottom padding of the button.
         *
         * <p>The padding must be between 0 and 8dp (inclusive). Values outside this range will be
         * clamped. If not explicitly set, it defaults to 0.
         */
        @NonNull
        public Builder setPaddingBottom(int paddingBottom) {
            mPaddingBottom = paddingBottom;
            mPropertiesSet |= PROPERTY_PADDING_BOTTOM;
            return this;
        }

        /**
         * Sets the background color of the button.
         *
         * <p>The system will ensure the background color is fully opaque by overriding its
         * alpha channel to 255. If not explicitly set, it defaults to a system default color.
         */
        @NonNull
        public Builder setBackgroundColor(@ColorInt int backgroundColor) {
            mBackgroundColor = backgroundColor;
            mPropertiesSet |= PROPERTY_BACKGROUND_COLOR;
            return this;
        }

        /**
         * Sets the stroke color of the button.
         *
         * <p>If not explicitly set, the stroke color will default to the button's background color.
         */
        @NonNull
        public Builder setStrokeColor(@ColorInt int strokeColor) {
            mStrokeColor = strokeColor;
            mPropertiesSet |= PROPERTY_STROKE_COLOR;
            return this;
        }

        /**
         * Sets the stroke width of the button.
         *
         * <p>The stroke width must be between 0 and 3dp (inclusive). Values outside this range will
         * be clamped. If not explicitly set, it defaults to 0.
         */
        @NonNull
        public Builder setStrokeWidth(int strokeWidth) {
            mStrokeWidth = strokeWidth;
            mPropertiesSet |= PROPERTY_STROKE_WIDTH;
            return this;
        }

        /**
         * Sets the corner radius of the button.
         *
         * <p>The corner radius must be non-negative. Negative values will be clamped to 0.
         */
        @NonNull
        public Builder setCornerRadius(float cornerRadius) {
            mCornerRadius = cornerRadius;
            mPropertiesSet |= PROPERTY_CORNER_RADIUS;
            return this;
        }

        /**
         * Sets the corner radius of the button when pressed.
         *
         * <p>The corner radius must be non-negative. Negative values will be clamped to 0.
         */
        @NonNull
        public Builder setPressedCornerRadius(float cornerRadius) {
            mPressedCornerRadius = cornerRadius;
            mPropertiesSet |= PROPERTY_PRESSED_CORNER_RADIUS;
            return this;
        }

        /**
         * Sets the icon tint color.
         *
         * <p>The system will automatically adjust the provided color if necessary to ensure it
         * meets a minimum contrast ratio of 4.5:1 against the button's background color. If not
         * explicitly set, it defaults to a system default color.
         */
        @NonNull
        public Builder setIconTint(@ColorInt int iconTint) {
            mIconTint = iconTint;
            mPropertiesSet |= PROPERTY_ICON_TINT;
            return this;
        }

        /**
         * Sets the text type of the button.
         *
         * <p>Unsupported text types will be treated as
         * {@link LocationButtonSession#TEXT_TYPE_NONE}. If not explicitly set, it defaults
         * to {@link LocationButtonSession#TEXT_TYPE_NONE}.
         */
        @NonNull
        public Builder setTextType(@LocationButtonSession.TextType int textType) {
            mTextType = textType;
            mPropertiesSet |= PROPERTY_TEXT_TYPE;
            return this;
        }

        /**
         * Sets the text color of the button.
         *
         * <p>The system will automatically adjust the provided color if necessary to ensure it
         * meets a minimum contrast ratio of 4.5:1 against the button's background color. If not
         * explicitly set, it defaults to a system default color.
         */
        @NonNull
        public Builder setTextColor(@ColorInt int textColor) {
            mTextColor = textColor;
            mPropertiesSet |= PROPERTY_TEXT_COLOR;
            return this;
        }

        /**
         * Builds a {@link LocationButtonRequest} instance.
         */
        @NonNull
        public LocationButtonRequest build() {
            return new LocationButtonRequest(mWidth, mHeight, mPaddingLeft, mPaddingTop,
                    mPaddingRight, mPaddingBottom, mBackgroundColor, mStrokeColor, mStrokeWidth,
                    mCornerRadius, mPressedCornerRadius, mIconTint, mTextType, mTextColor,
                    mConfiguration, mPropertiesSet);
        }
    }
}
