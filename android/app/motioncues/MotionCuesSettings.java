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

package android.app.motioncues;

import android.annotation.FlaggedApi;
import android.annotation.SystemApi;
import android.app.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;


/**
 * Represents a set of initial configuration options for motion cues.
 * These settings are typically applied once when a motion cues session is started.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
public final class MotionCuesSettings implements Parcelable {
    private final int mHorizontalSpacingDp;
    private final int mVerticalSpacingDp;
    private final int mMarginSizeDp;
    private final int mRadiusDp;

    private MotionCuesSettings(Builder builder) {
        mHorizontalSpacingDp = builder.mHorizontalSpacingDp;
        mVerticalSpacingDp = builder.mVerticalSpacingDp;
        mMarginSizeDp = builder.mMarginSizeDp;
        mRadiusDp = builder.mRadiusDp;
    }

    private MotionCuesSettings(Parcel in) {
        mHorizontalSpacingDp = in.readInt();
        mVerticalSpacingDp = in.readInt();
        mMarginSizeDp = in.readInt();
        mRadiusDp = in.readInt();
    }

    /**
     * Gets the desired horizontal spacing between the centers of adjacent motion cues
     * in dp.
     *
     * @return The horizontal spacing in dp.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
    public int getHorizontalSpacingDp() {
        return mHorizontalSpacingDp;
    }

    /**
     * Gets the desired vertical spacing between the centers of adjacent motion cues
     * in dp.
     *
     * @return The vertical spacing in dp.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
    public int getVerticalSpacingDp() {
        return mVerticalSpacingDp;
    }

    /**
     * Gets the margin size around the area where motion cues can be drawn, in dp.
     *
     * @return The margin size in dp.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
    public int getMarginSizeDp() {
        return mMarginSizeDp;
    }

    /**
     * Gets the base radius of the motion cues, in dp.
     *
     * @return The radius in dp.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
    public int getRadiusDp() {
        return mRadiusDp;
    }

    /**
     * Standard Parcelable CREATOR field.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
    public static final @NonNull Creator<MotionCuesSettings> CREATOR =
            new Creator<MotionCuesSettings>() {
                @Override
                public MotionCuesSettings createFromParcel(Parcel in) {
                    return new MotionCuesSettings(in);
                }

                @Override
                public MotionCuesSettings[] newArray(int size) {
                    return new MotionCuesSettings[size];
                }
            };

    /** @hide */
    @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mHorizontalSpacingDp);
        dest.writeInt(mVerticalSpacingDp);
        dest.writeInt(mMarginSizeDp);
        dest.writeInt(mRadiusDp);
    }

    /**
     * Builder class for creating {@link MotionCuesSettings} instances.
     *
     * <p>This builder allows for a flexible construction of the settings object.
     * @hide
     */
    @SystemApi
    @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
    public static final class Builder {
        private int mHorizontalSpacingDp;
        private int mVerticalSpacingDp;
        private int mMarginSizeDp;
        private int mRadiusDp;

        /**
         * Creates a new Builder with default values.
         *
         * @hide
         */
        @SystemApi
        @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
        public Builder() {}

        /**
         * Creates a new Builder initialized with values from an existing {@link
         * MotionCuesSettings}.
         *
         * @param original The {@link MotionCuesSettings} instance to copy values from.
         * @hide
         */
        @SystemApi
        @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
        public Builder(@NonNull MotionCuesSettings original) {
            mHorizontalSpacingDp = original.mHorizontalSpacingDp;
            mVerticalSpacingDp = original.mVerticalSpacingDp;
            mMarginSizeDp = original.mMarginSizeDp;
            mRadiusDp = original.mRadiusDp;
        }

        /**
         * Sets the horizontal spacing between bubbles in density-independent pixels (dp).
         *
         * @param horizontalSpacingDp The horizontal spacing in dp.
         * @return This Builder instance for chaining.
         * @hide
         */
        @SystemApi
        @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
        public @NonNull Builder setHorizontalSpacingDp(int horizontalSpacingDp) {
            mHorizontalSpacingDp = horizontalSpacingDp;
            return this;
        }

        /**
         * Sets the vertical spacing between bubbles in density-independent pixels (dp).
         *
         * @param verticalSpacingDp The vertical spacing in dp.
         * @return This Builder instance for chaining.
         * @hide
         */
        @SystemApi
        @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
        public @NonNull Builder setVerticalSpacingDp(int verticalSpacingDp) {
            mVerticalSpacingDp = verticalSpacingDp;
            return this;
        }

        /**
         * Sets the margin size for the drawing area of the bubbles on the screen edges.
         *
         * @param marginSize The margin size in dp.
         * @return This Builder instance for chaining.
         * @hide
         */
        @SystemApi
        @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
        public @NonNull Builder setMarginSizeDp(int marginSizeDp) {
            mMarginSizeDp = marginSizeDp;
            return this;
        }

        /**
         * Sets the base radius of the bubbles.
         *
         * @param radius The bubble radius in dp.
         * @return This Builder instance for chaining.
         * @hide
         */
        @SystemApi
        @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
        public @NonNull Builder setRadiusDp(int radiusDp) {
            mRadiusDp = radiusDp;
            return this;
        }

        /**
         * Builds and returns the {@link MotionCuesSettings} instance.
         *
         * @return A new {@link MotionCuesSettings} instance with the configured values.
         * @hide
         */
        @SystemApi
        @FlaggedApi(Flags.FLAG_ENABLE_MOTION_CUES)
        public @NonNull MotionCuesSettings build() {
            return new MotionCuesSettings(this);
        }
    }
}
