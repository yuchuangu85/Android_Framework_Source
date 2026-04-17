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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.IntArray;

import com.android.internal.util.Preconditions;
import com.android.server.lights.feature.flags.Flags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Models an effect that will be shown in one or more lights.
 * <p>
 * Effects are comprised of [light, ColorSequence] pairs defining how each light should change over
 * time along with some extra configuration values:
 * <ul>
 *   <li>Iterations: the number of times the effect will be played back to back.
 *   <li>Preemptive: whether this effect should preempt any previously playing state or if it should
 *       be played after the current effect finishes.
 * </ul>
 * <p>
 * A MultiLightEffect is treated as a unit with a given start time and an implicit duration:
 * <ul>
 *   <li>Start time: all lights sequences in the effect start at the same time, regardless of having
 *       a initial state or not.
 *   <li>Duration: the duration of the effect is the duration of the longest sequence it contains.
 *       If a light sequence in the effect is shorter than the effect duration, the light will
 *       remain as a solid color until the end of the effect.
 * </ul>
 * <p>
 * {@link ColorSequence}s in the effect could have initial states ({@code delay=0}) for their
 * corresponding lights, which has special implications when the effect will be played as a
 * continuation of another effect (effects with {@code preemptive=false} and/or effects with initial
 * states and more than one iteration).
 * <p>
 * The initial state will be applied only if the effect is set to preemptive and it is the first
 * iteration of the effect. The system ignores the initial state in subsequent iterations or in
 * effects with {@code preemptive=false} to allow the light to transition smoothly and avoid flicks.
 */
@FlaggedApi(Flags.FLAG_ENABLE_LIGHT_ANIMATIONS)
public final class MultiLightEffect implements Parcelable {
    private final int mIterations;
    private final boolean mPreemptive;
    private final int[] mLightIds;
    private final ColorSequence[] mColorSequences;

    /**
     * Creates a MultiLightEffect from a parcelable.
     */
    private MultiLightEffect(@NonNull Parcel in) {
        this(
                in.readInt(),
                in.readBoolean(),
                Objects.requireNonNull(in.createIntArray()),
                Objects.requireNonNull(
                        in.readParcelableArray(
                                ColorSequence.class.getClassLoader(),
                                ColorSequence.class)));
    }

    /**
     * Can only be constructed via {@link MultiLightEffect.Builder#build()}.
     */
    private MultiLightEffect(
            int iterations, boolean preemptive,
            @NonNull int[] lightIds, @NonNull ColorSequence[] colorSequences) {
        this.mIterations = iterations;
        this.mPreemptive = preemptive;
        this.mLightIds = lightIds;
        this.mColorSequences = colorSequences;
    }

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    @Override
    @FlaggedApi(Flags.FLAG_ENABLE_LIGHT_ANIMATIONS)
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mIterations);
        dest.writeBoolean(mPreemptive);
        dest.writeIntArray(mLightIds);
        dest.writeParcelableArray(mColorSequences, 0);
    }

    /** Implement the Parcelable interface */
    public static final @NonNull Parcelable.Creator<MultiLightEffect> CREATOR =
            new Parcelable.Creator<>() {
                public MultiLightEffect createFromParcel(Parcel in) {
                    return new MultiLightEffect(in);
                }

                public MultiLightEffect[] newArray(int size) {
                    return new MultiLightEffect[size];
                }
            };

    /**
     * Builder for {@link MultiLightEffect} objects.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_LIGHT_ANIMATIONS)
    public static final class Builder {
        private IntArray mLightIds = new IntArray();
        private ArrayList<ColorSequence> mColorSequences = new ArrayList<>();
        private int mIterations = 1;
        private boolean mPreemptive = true;

        public Builder() {
        }

        /**
         * Adds a new light and its corresponding sequence to the effect.
         *
         * @param light the light that will play the sequence.
         * @param colorSequence the sequence of colors and their timing.
         *
         * @throws IllegalArgumentException if the light does not support animations.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder addLightSequence(
                @NonNull Light light,
                @NonNull ColorSequence colorSequence) {
            Preconditions.checkNotNull(light);
            Preconditions.checkNotNull(colorSequence);
            Preconditions.checkArgument(light.hasAnimationControl());

            mLightIds.add(light.getId());
            mColorSequences.add(colorSequence);
            return this;
        }

        /**
         * Number of times that the effect should be played in a loop.
         * <p>
         * By default, the builder configures this value to 1 for a single run of the effect.
         * <p>
         * A value of 0 indicates the effect should repeat indefinitely until a new effect or state
         * is provided for the light or until the light is turned off.
         * <p>
         * If the effect has an initial state (delay = 0 for the first control point) the initial
         * state will only be applied during the first iteration and all other iterations will
         * ignore the initial value.
         *
         * @param iterations number of times the effect should be played.
         */
        @NonNull
        public Builder setIterations(int iterations) {
            mIterations = iterations;
            return this;
        }

        /**
         * Sets whether this effect will preempt any previous effect / state currently active on the
         * light.
         * <p>
         * By default, the builder sets this value to true and the effect is preemptive.
         * <p>
         * When preemptive=false AND the previous light state is a:
         *  <li>Permanent color: the playback will start immediately. Preemptive has no effect.
         *  <li>Non-infinite effect: the new effect will start after the previous effect ends all of
         *     its iterations.
         *  <li>Infinite effect: the playback will start after the current iteration of the effect
         *     finishes.
         * <p>
         * When preemptive=true the effect is applied immediately regardless of previous state.
         * <p>
         * Even when the effect preempts an existing state, the effect may not necessarily provide
         * an initial state and the first control point may be relatively far in the future.
         * Applications can make use of these conditions to create a fading effect between the last
         * known state of the light and the new effect.
         * <p>
         * When creating fading effects, the following applies:
         *  <li>If the last known state is not an effect: last color becomes the starting color for
         *     the interpolator.
         *  <li>If the last known state is an effect: the last interpolated value becomes the
         *     starting color for the interpolator.
         * <p>
         * The system has a maximum of one non-preemptive event in the queue per light session. If
         * an effect A is playing and the application requests two non-preemptive events (B and C)
         * before A is done playing, only C will be kept and played after A, and B will be
         * discarded.
         *
         * @param preemptive true to play immediately or false wait for the previous effect to
         *                   finish.
         */
        @NonNull
        public Builder setPreemptive(boolean preemptive) {
            mPreemptive = preemptive;
            return this;
        }

        /**
         * Builds the light effect.
         *
         * @throws IllegalStateException if no light-sequence pair has been provided.
         *
         * @return A MultiLightEffect with the configuration and sequences configured.
         */
        @NonNull
        public MultiLightEffect build() {
            Preconditions.checkArgument(mLightIds.size() > 0);

            return new MultiLightEffect(
                    mIterations,
                    mPreemptive,
                    mLightIds.toArray(),
                    mColorSequences.toArray(new ColorSequence[0]));
        }
    }

    /**
     * Returns the list of lights animated by this effect.
     *
     * @return Array of lightIds that will be animated in this effect.
     * @hide
     */
    @NonNull
    public int[] getLights() {
        return mLightIds;
    }

    /**
     * Returns all the color sequences in this effect.
     *
     * @return Array of color sequences in the same order as the lightIds that use them.
     * @hide
     */
    @NonNull
    public ColorSequence[] getColorSequences() {
        return mColorSequences;
    }

    /**
     * Returns the color sequences defined in the light effect for each of the lights.
     * <p>
     * The key for the returned map corresponds to the lightId (see {@link Light#getId()}) of the
     * light that will play the color sequence represented by the value.
     *
     * @return the Map of lightIds to the color sequences that will be played for the effect.
     */
    @NonNull
    public Map<Integer, ColorSequence> getSequences() {
        Map sequences = new HashMap<Integer, ColorSequence>();
        for (int i = 0; i < mLightIds.length; i++) {
            sequences.put(mLightIds[i], mColorSequences[i]);
        }
        return sequences;
    }

    /**
     * Returns the number of times this effect will be played in a loop. Defaults to 1.
     *
     * @return the number of iterations to play.
     */
    public int getIterations() {
        return mIterations;
    }

    /**
     * Returns whether this effect will cancel any previously playing effect.
     *
     * @return true if this effect applies immediately, false otherwise.
     */
    public boolean isPreemptive() {
        return mPreemptive;
    }

    /**
     * Calculates the estimated duration of a single iteration of the light effect.
     *
     * @return the estimated duration of one iteration of this light effect in milliseconds.
     * @hide
     */
    public long getIterationDurationMillis() {
        long duration = 0;
        for (ColorSequence sequence : mColorSequences) {
            duration = Math.max(duration, sequence.getDurationMillis());
        }
        return duration;
    }

    /**
     * Calculates the total estimated duration of the effect.
     * <p>
     * If this effect repeats indefinitely, this method returns 0.
     *
     * @return the estimated duration of this light effect.
     * @hide
     */
    public long getTotalDurationMillis() {
        return getIterationDurationMillis() * mIterations;
    }

    @Override
    public String toString() {
        return "LightEffect{"
                + "Iterations=" + mIterations
                + ", Preemptive=" + mPreemptive
                + ", Lights=" + Arrays.toString(mLightIds)
                + ", Sequences=" + Arrays.toString(mColorSequences)
                + "}";
    }
}
