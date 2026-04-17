/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.health.connect.datatypes;

import static android.health.connect.Constants.DEFAULT_FLOAT;
import static android.health.connect.Constants.DEFAULT_INT;

import static com.android.healthfitness.flags.Flags.FLAG_EXERCISE_SEGMENT_IMPROVEMENTS;

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.health.connect.datatypes.units.Mass;
import android.health.connect.datatypes.validation.ValidationUtils;
import android.health.connect.internal.datatypes.ExerciseSegmentInternal;

import com.android.healthfitness.flags.AconfigFlagHelper;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents particular exercise within exercise session (see {@link ExerciseSessionRecord}).
 *
 * <p>Each record contains start and end time of the exercise, exercise type and optional number of
 * repetitions, weight, set index and rate of perceived exertion.
 */
public final class ExerciseSegment implements TimeInterval.TimeIntervalHolder {
    private final TimeInterval mInterval;

    @ExerciseSegmentType.ExerciseSegmentTypes private final int mSegmentType;

    private final int mRepetitionsCount;

    @Nullable private final Mass mWeight;

    private final int mSetIndex;

    private final float mRateOfPerceivedExertion;

    private ExerciseSegment(
            @NonNull TimeInterval interval,
            @ExerciseSegmentType.ExerciseSegmentTypes int segmentType,
            @IntRange(from = 0) int repetitionsCount,
            @Nullable Mass weight,
            int setIndex,
            float rateOfPerceivedExertion,
            boolean skipValidation) {
        Objects.requireNonNull(interval);
        mInterval = interval;

        mSegmentType = segmentType;

        if (!skipValidation) {
            ValidationUtils.requireNonNegative(repetitionsCount, "repetitionsCount");
        }
        mRepetitionsCount = repetitionsCount;
        mWeight = weight;
        mSetIndex = setIndex;
        mRateOfPerceivedExertion = rateOfPerceivedExertion;
    }

    /*
     * Returns type of the segment, one of {@link @ExerciseSegmentType.ExerciseSegmentTypes}.
     */
    @ExerciseSegmentType.ExerciseSegmentTypes
    public int getSegmentType() {
        return mSegmentType;
    }

    /*
     * Returns number of repetitions in the current segment. Positive value.
     */
    @IntRange(from = 0)
    public int getRepetitionsCount() {
        return mRepetitionsCount;
    }

    /**
     * Gets the weight associated with this exercise segment.
     *
     * <p>Returns {@code null} if weight is not set.
     */
    @Nullable
    @FlaggedApi(FLAG_EXERCISE_SEGMENT_IMPROVEMENTS)
    public Mass getWeight() {
        return mWeight;
    }

    /**
     * Gets the set index for this exercise segment.
     *
     * <p>The set index is a non-negative integer starting at zero.
     *
     * <p>A set is a group of consecutive repetitions (reps) of a specific exercise performed
     * without a break, e.g. 10 push-ups in a row without stopping.
     *
     * <p>A set index represents the position of this set relative to other sets in the session. For
     * instance, if an exercise has three sets, they will have setIndex values of 0, 1, and 2
     * respectively.
     *
     * <p>Multiple segments may be part of a single set, for example if a collection of activities
     * are considered to be a single set, in which case those segments would have the same set
     * index.
     *
     * <p>The set index is may also go back to zero in a single {@code ExerciseSession}. For
     * example, if three sets of one activity are completed followed by three sets of another,
     * setIndex values of 0, 1, 2, 0, 1, 2 would be expected for those segments.
     *
     * <p>Use {@link #hasSetIndex} to check whether a set index exists for this segment. Multiple
     * segments can share the same set index.
     *
     * @throws IllegalStateException if set index is not set.
     */
    @FlaggedApi(FLAG_EXERCISE_SEGMENT_IMPROVEMENTS)
    @IntRange(from = 0)
    public int getSetIndex() {
        if (mSetIndex == DEFAULT_INT) {
            throw new IllegalStateException(
                    "Set index is not set. Use `hasSetIndex` to check whether set index exists for"
                            + " this segment.");
        }
        return mSetIndex;
    }

    /**
     * Returns true if this segment has an associated set index.
     */
    @FlaggedApi(FLAG_EXERCISE_SEGMENT_IMPROVEMENTS)
    public boolean hasSetIndex() {
        return mSetIndex != DEFAULT_INT;
    }

    /**
     * Gets the rate of perceived exertion (RPE) for this exercise segment.
     *
     * <p>Values correspond to the Borg CR10 RPE scale and must be in the range 0 to 10 inclusive.
     * 0: No exertion (at rest) 1: Very light 2-3: Light 4-5: Moderate 6-7: Hard 8-9: Very hard 10:
     * Maximum effort
     *
     * <p>Use {@link #hasRateOfPerceivedExertion} to check whether RPE exists for this segment.
     *
     * @throws IllegalStateException if rate of perceived exertion is not set.
     */
    @FlaggedApi(FLAG_EXERCISE_SEGMENT_IMPROVEMENTS)
    @FloatRange(from = 0, to = 10, fromInclusive = true, toInclusive = true)
    public float getRateOfPerceivedExertion() {
        if (mRateOfPerceivedExertion == DEFAULT_FLOAT) {
            throw new IllegalStateException(
                    "Rate of perceived exertion is not set. Use `hasRateOfPerceivedExertion` to"
                            + " check whether RPE exists for this segment.");
        }
        return mRateOfPerceivedExertion;
    }

    /**
     * Returns true if this segment has an associated rate of perceived exertion.
     */
    @FlaggedApi(FLAG_EXERCISE_SEGMENT_IMPROVEMENTS)
    public boolean hasRateOfPerceivedExertion() {
        return mRateOfPerceivedExertion != DEFAULT_FLOAT;
    }

    /*
     * Returns start time of the segment.
     */
    @NonNull
    public Instant getStartTime() {
        return mInterval.getStartTime();
    }

    /*
     * Returns end time of the segment.
     */
    @NonNull
    public Instant getEndTime() {
        return mInterval.getEndTime();
    }

    /** @hide */
    @Override
    public TimeInterval getInterval() {
        return mInterval;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExerciseSegment)) return false;
        ExerciseSegment that = (ExerciseSegment) o;
        return mSegmentType == that.mSegmentType
                && mRepetitionsCount == that.mRepetitionsCount
                && Objects.equals(mWeight, that.mWeight)
                && mSetIndex == that.mSetIndex
                && mRateOfPerceivedExertion == that.mRateOfPerceivedExertion
                && Objects.equals(mInterval, that.mInterval);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mSegmentType,
                mRepetitionsCount,
                mWeight,
                mSetIndex,
                mRateOfPerceivedExertion,
                mInterval);
    }

    /** @hide */
    public ExerciseSegmentInternal toSegmentInternal() {
        ExerciseSegmentInternal segment =
                new ExerciseSegmentInternal()
                        .setStartTime(getStartTime().toEpochMilli())
                        .setEndTime(getEndTime().toEpochMilli())
                        .setSegmentType(getSegmentType())
                        .setRepetitionsCount(getRepetitionsCount());
        if (AconfigFlagHelper.isExerciseSegmentImprovementsEnabled()) {
            if (getWeight() != null) {
                segment.setWeightGrams(getWeight().getInGrams());
            }
            if (hasSetIndex()) {
                segment.setSetIndex(getSetIndex());
            }
            if (hasRateOfPerceivedExertion()) {
                segment.setRateOfPerceivedExertion(getRateOfPerceivedExertion());
            }
        }
        return segment;
    }

    /** Builder class for {@link ExerciseSegment} */
    public static final class Builder {
        private final TimeInterval mInterval;

        @ExerciseSegmentType.ExerciseSegmentTypes private final int mSegmentType;

        private int mRepetitionsCount = 0;

        @Nullable private Mass mWeight;

        private int mSetIndex = DEFAULT_INT;

        private float mRateOfPerceivedExertion = DEFAULT_FLOAT;

        public Builder(
                @NonNull Instant startTime,
                @NonNull Instant endTime,
                @ExerciseSegmentType.ExerciseSegmentTypes int segmentType) {
            Objects.requireNonNull(startTime);
            Objects.requireNonNull(endTime);
            mInterval = new TimeInterval(startTime, endTime);
            mSegmentType = segmentType;
        }

        /**
         * Sets the number of repetitions to the current segment. Returns builder instance with
         * repetitions count set.
         */
        @NonNull
        public Builder setRepetitionsCount(@IntRange(from = 0) int repetitionsCount) {
            if (repetitionsCount < 0) {
                throw new IllegalArgumentException("Number of repetitions must be non negative.");
            }
            mRepetitionsCount = repetitionsCount;
            return this;
        }

        /**
         * Sets the weight associated with this exercise segment.
         *
         * <p>Weight must be at least zero and not more than 2500kg.
         *
         * <p>Returns builder instance with weight set.
         */
        @FlaggedApi(FLAG_EXERCISE_SEGMENT_IMPROVEMENTS)
        @NonNull
        public Builder setWeight(@NonNull Mass weight) {
            if (weight.getInGrams() < 0) {
                throw new IllegalArgumentException("Weight must be non negative.");
            }
            if (weight.getInGrams() > 2_500_000) {
                throw new IllegalArgumentException("Weight must not exceed 2500kg.");
            }
            this.mWeight = weight;
            return this;
        }

        /**
         * Clears the weight for this exercise segment.
         *
         * <p>Returns builder instance with weight unset.
         */
        @FlaggedApi(FLAG_EXERCISE_SEGMENT_IMPROVEMENTS)
        @NonNull
        public Builder clearWeight() {
            this.mWeight = null;
            return this;
        }

        /**
         * Sets the set index for this exercise segment.
         *
         * <p>The set index must be a non-negative integer, and should start at zero.
         *
         * <p>Set index represents the position of this set relative to other sets in the session.
         * For instance, if an exercise has three sets, they will have setIndex values of 0, 1, and
         * 2 respectively.
         *
         * <p>Multiple segments may be part of a single set, for example if a collection of
         * activities are considered to be a single set, in which case those segments would have the
         * same set index.
         *
         * <p>The set index is may also go back to zero in a single {@code ExerciseSession}. For
         * example, if three sets of one activity are completed followed by three sets of another,
         * setIndex values of 0, 1, 2, 0, 1, 2 would be expected for those segments.
         *
         * <p>Returns builder instance with set index set.
         */
        @FlaggedApi(FLAG_EXERCISE_SEGMENT_IMPROVEMENTS)
        @NonNull
        public Builder setSetIndex(@IntRange(from = 0) int setIndex) {
            if (setIndex < 0) {
                throw new IllegalArgumentException("Set index must be non-negative");
            }
            this.mSetIndex = setIndex;
            return this;
        }

        /**
         * Clears the set index for this exercise segment.
         *
         * <p>Returns builder instance with set index unset.
         */
        @FlaggedApi(FLAG_EXERCISE_SEGMENT_IMPROVEMENTS)
        @NonNull
        public Builder clearSetIndex() {
            this.mSetIndex = DEFAULT_INT;
            return this;
        }

        /**
         * Sets rate of perceived exertion (RPE) used during the exercise segment.
         *
         * <p>Values correspond to the Borg CR10 RPE scale and must be in the range 0 to 10
         * inclusive. 0: No exertion (at rest) 1: Very light 2-3: Light 4-5: Moderate 6-7: Hard 8-9:
         * Very hard 10: Maximum effort
         *
         * <p>Returns builder instance with rate of perceived exertion set.
         */
        @FlaggedApi(FLAG_EXERCISE_SEGMENT_IMPROVEMENTS)
        @NonNull
        public Builder setRateOfPerceivedExertion(
                @FloatRange(from = 0, to = 10, fromInclusive = true, toInclusive = true)
                        float rateOfPerceivedExertion) {
            if ((rateOfPerceivedExertion < 0 || rateOfPerceivedExertion > 10)) {
                throw new IllegalArgumentException(
                        "Rate of perceived exertion must be in the range 0 to 10 inclusive");
            }
            this.mRateOfPerceivedExertion = rateOfPerceivedExertion;
            return this;
        }

        /**
         * Clears the rate of perceived exertion for this exercise segment.
         *
         * <p>Returns builder instance with rate of perceived exertion unset.
         */
        @FlaggedApi(FLAG_EXERCISE_SEGMENT_IMPROVEMENTS)
        @NonNull
        public Builder clearRateOfPerceivedExertion() {
            this.mRateOfPerceivedExertion = DEFAULT_FLOAT;
            return this;
        }

        /**
         * @return Object of {@link ExerciseSegment} without validating the values.
         * @hide
         */
        @NonNull
        public ExerciseSegment buildWithoutValidation() {
            return new ExerciseSegment(
                    mInterval,
                    mSegmentType,
                    mRepetitionsCount,
                    mWeight,
                    mSetIndex,
                    mRateOfPerceivedExertion,
                    true);
        }

        /**
         * Sets the number repetitions to the current segment. Returns {@link ExerciseSegment}
         * instance.
         */
        @NonNull
        public ExerciseSegment build() {
            return new ExerciseSegment(
                    mInterval,
                    mSegmentType,
                    mRepetitionsCount,
                    mWeight,
                    mSetIndex,
                    mRateOfPerceivedExertion,
                    false);
        }
    }
}
