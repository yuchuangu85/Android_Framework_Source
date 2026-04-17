/**
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

package android.hardware.lights;

import android.annotation.ColorInt;
import android.annotation.DurationMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.IntArray;
import android.util.LongArray;

import com.android.internal.util.Preconditions;
import com.android.server.lights.feature.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Models a sequence of colors and their relative timing to create a light effect.
 * <p>
 * The control points in this object represent specific points in time when the light will be set
 * to the specified color. Control points can be arbitrarily distant in time and the light will be
 * interpolated each frame to smoothly transition between control points.
 * <p>
 * Control points have three main components:
 * <ul>
 *   <li>color: the target color for the light.
 *   <li>delay: how long the light takes to transition from color[n-1] to color[n], expressed in
 *       milliseconds relative to the last control point (or permanent state if the light is not
 *       playing an effect).
 *   <li>interpolation mode: the interpolation algorithm to use to transition between colors. It is
 *       important to note that the interpolation is done per color channel on every frame (see
 *       {@link Light#getMinUpdatePeriodMillis()}).
 * </ul>
 *
 */
@FlaggedApi(Flags.FLAG_ENABLE_LIGHT_ANIMATIONS)
public final class ColorSequence implements Parcelable {
    /**
     * No interpolation between control points.
     * <p>
     * With this interpolation mode, {@code color[n-1]} will be held for the corresponding duration
     * specified in delayMillis. After that, {@code color[n]} will be applied without any
     * intermediate steps.
     */
    public static final int INTERPOLATION_MODE_NONE = 0;

    /**
     * Linear interpolation between control points.
     * <p>
     * With this interpolation mode, the transition from {@code color[n-1]} to {@code color[n]}
     * happens linearly over {@code delayMillis[n]} milliseconds, with color updates every frame.
     * <p>
     * Each color channel is interpolated independently.
     */
    public static final int INTERPOLATION_MODE_LINEAR = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"INTERPOLATION_MODE_"},
            value = {
                    INTERPOLATION_MODE_NONE,
                    INTERPOLATION_MODE_LINEAR,
            })
    public @interface InterpolationMode {}

    private final @InterpolationMode int mInterpolationMode;
    private final long[] mDelaysMillis;
    private final @ColorInt int[] mColors;

    /**
     * Creates a ColorSequence from a parcelable.
     */
    private ColorSequence(@NonNull Parcel in) {
        this.mInterpolationMode = in.readInt();
        this.mDelaysMillis = Objects.requireNonNull(in.createLongArray());
        this.mColors = Objects.requireNonNull(in.createIntArray());
    }

    /**
     * Can only be constructed via {@link ColorSequence.Builder#build()} or MultiLightEffect.
     *
     * @hide
     */
    ColorSequence(
            @InterpolationMode int interpolationMode,
            @NonNull long[] delaysMillis,
            @NonNull @ColorInt int[] colors) {
        this.mInterpolationMode = interpolationMode;
        this.mDelaysMillis = delaysMillis;
        this.mColors = colors;
    }

    /** Implements the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implements the Parcelable interface */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mInterpolationMode);
        dest.writeLongArray(mDelaysMillis);
        dest.writeIntArray(mColors);
    }

    /** Implements the Parcelable interface */
    public static final @NonNull Parcelable.Creator<ColorSequence> CREATOR =
            new Parcelable.Creator<>() {
                public ColorSequence createFromParcel(Parcel in) {
                    return new ColorSequence(in);
                }

                public ColorSequence[] newArray(int size) {
                    return new ColorSequence[size];
                }
            };

    /**
     * Builder for {@link ColorSequence} objects.
     */
    public static final class Builder {
        private @InterpolationMode int mInterpolationMode = INTERPOLATION_MODE_LINEAR;
        private IntArray mColors = new IntArray();
        private LongArray mDelaysMillis = new LongArray();

        public Builder() {
        }

        /**
         * Sets the interpolation mode to use to transition between control point colors.
         * <p>
         * By default, the builder configures the sequence with {@link #INTERPOLATION_MODE_LINEAR}.
         *
         * @param interpolationMode specifying how to ramp the light between control points.
         */
        @NonNull
        public Builder setInterpolationMode(@InterpolationMode int interpolationMode) {
            mInterpolationMode = interpolationMode;
            return this;
        }

        /**
         * Adds the next control point for the effect.
         *
         * The transition between control point colors takes {@code delayMillis} milliseconds.
         * During that time, the light colors will be smoothly interpolated from the start to the
         * end value according to the interpolation algorithm and the light's update interval.
         * <p>
         * When specifying a delay:
         * <ul>
         *   <li>Any negative value is considered an error and will cause an
         *       {@code IllegalArgumentException}
         *   <li>Any positive value corresponds to the transition time in milliseconds, relative
         *       to the end of the previous control point.
         *   <li>A value of 0 can only be specified for the very first control point in the sequence
         *       as a way to provide an initial state for the effect. It will cause an
         *       IllegalArgumentException if found in any other index.
         *   <li>All delays MUST be whole multiples of the light's update period (frame period)
         *       ({@link Light#getMinUpdatePeriodMillis()}). If any specified delay is a fractional
         *       number of frames, the system will truncate the delay potentially altering initially
         *       expected interpolation values and relative timing with other lights.
         * </ul>
         * <p>
         * Providing an initial state is not required and the effect will take the last known state
         * as the starting point for the interpolation, creating smooth transitions between effects.
         *
         * @throws IllegalArgumentException if delaysMillis is 0 for a non initial condition, or
         *         negative.
         *
         * @param delayMillis transition time for the light to change to the target color.
         * @param color the target color for the light at the end of the transition time.
         *
         * @return a reference to this builder.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder addControlPoint(long delayMillis, @ColorInt int color) {
            // Validate that initial delays only happen at index 0.
            Preconditions.checkArgument(
                    delayMillis > 0 || (delayMillis == 0  && mColors.size() == 0));

            mColors.add(color);
            mDelaysMillis.add(delayMillis);
            return this;
        }

        /**
         * Adds multiple control points to the sequence.
         * <p>
         * Both arrays provided MUST have the same size and be in the order in which they should
         * be inserted.
         *
         * @see #addControlPoint(long, int) for details.
         *
         * @throws IllegalArgumentException if the arrays have different sizes or the sequence
         *         contains invalid values.
         *
         * @param delaysMillis array with the delays for the colors in the colors parameter.
         * @param colors array with the control point color targets.
         *
         * @return a reference to this builder.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder addControlPoints(
                @NonNull long[] delaysMillis, @NonNull @ColorInt int[] colors) {
            Preconditions.checkNotNull(delaysMillis);
            Preconditions.checkNotNull(colors);
            Preconditions.checkArgument(delaysMillis.length == colors.length, "Uneven arrays");

            for (int i = 0; i < delaysMillis.length; i++) {
                addControlPoint(delaysMillis[i], colors[i]);
            }
            return this;
        }

        /**
         * Appends the provided ColorSequence to this sequence.
         * <p>
         * If the two color sequences have different interpolation modes, the interpolation mode of
         * the builder takes precedence over the interpolation mode from the argument.
         *
         * @see #addControlPoint(long, int) for details.
         *
         * @throws IllegalArgumentException if the sequence would cause the final sequence to be
         *         invalid.
         *
         * @param sequence An existing ColorSequence to append to this builder.
         *
         * @return a reference to this builder.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder addControlPoints(@NonNull ColorSequence sequence) {
            Preconditions.checkNotNull(sequence);

            addControlPoints(sequence.mDelaysMillis, sequence.mColors);

            return this;
        }

        /**
         * Builds the light effect.
         *
         * @return the color sequence with the configuration provided.
         */
        @NonNull
        public ColorSequence build() {
            Preconditions.checkState(mDelaysMillis.size() > 0);
            Preconditions.checkState(mColors.size() > 0);
            return new ColorSequence(
                    mInterpolationMode,
                    mDelaysMillis.toArray(),
                    mColors.toArray());
        }
    }

    /**
     * Returns the type of interpolation that will be used to ramp the color to the final value.
     *
     * @return Interpolation mode to be used in the animation.
     */
    public @InterpolationMode int getInterpolationMode() {
        return mInterpolationMode;
    }

    /**
     * Returns the sequence of delays configured for the effect.
     *
     * @return Array of delays configured for this effect.
     */
    @NonNull
    @DurationMillisLong
    public long[] getDelaysMillis() {
        return mDelaysMillis;
    }

    /**
     * Returns the sequence of colors defined in this light effect.
     *
     * @return Array of colors that will be played for this effect.
     */
    @NonNull
    @ColorInt
    public int[] getColors() {
        return mColors;
    }


    /**
     * Calculates the estimated duration of a single iteration of the light effect.
     *
     * @return the estimated duration of one iteration of this light effect in number of frames.
     */
    @DurationMillisLong
    public long getDurationMillis() {
        long duration = 0;
        for (long delay : mDelaysMillis) {
            duration += delay;
        }
        return duration;
    }

    @Override
    public String toString() {
        return "ColorSequence{"
                + "interpolationMode=" + mInterpolationMode
                + "controlPointCount=" + mDelaysMillis.length
                + "duration=" + getDurationMillis() + "ms"
                + "}";
    }
}
