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

package android.health.connect.internal.datatypes;

import static android.health.connect.Constants.DEFAULT_FLOAT;
import static android.health.connect.Constants.DEFAULT_INT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.health.connect.datatypes.ExerciseSegment;
import android.health.connect.datatypes.ExerciseSegmentType;
import android.health.connect.datatypes.units.Mass;
import android.os.Parcel;

import com.android.healthfitness.flags.AconfigFlagHelper;
import com.android.healthfitness.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Internal ExerciseSegment. Part of {@link ExerciseSessionRecordInternal}
 *
 * @hide
 */
public class ExerciseSegmentInternal {

    private long mStartTime;
    private long mEndTime;

    @ExerciseSegmentType.ExerciseSegmentTypes private int mSegmentType;

    private int mRepetitionsCount;

    @Nullable private Double mWeightGrams;

    private int mSetIndex = DEFAULT_INT;

    private float mRateOfPerceivedExertion = DEFAULT_FLOAT;

    /** Reads record from parcel. */
    @VisibleForTesting
    public static ExerciseSegmentInternal readFromParcel(Parcel parcel) {
        ExerciseSegmentInternal segment =
                new ExerciseSegmentInternal()
                        .setStartTime(parcel.readLong())
                        .setEndTime(parcel.readLong())
                        .setRepetitionsCount(parcel.readInt())
                        .setSegmentType(parcel.readInt());
        if (AconfigFlagHelper.isExerciseSegmentImprovementsEnabled()) {
            boolean weightIsSet = parcel.readBoolean();
            if (weightIsSet) {
                segment.setWeightGrams(parcel.readDouble());
            }
            segment.setSetIndex(parcel.readInt());
            segment.setRateOfPerceivedExertion(parcel.readFloat());
        }
        return segment;
    }

    @Nullable
    static List<ExerciseSegmentInternal> populateSegmentsFromParcel(Parcel parcel) {
        int size = parcel.readInt();
        if (size == 0) {
            return null;
        }
        ArrayList<ExerciseSegmentInternal> segments = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            segments.add(ExerciseSegmentInternal.readFromParcel(parcel));
        }
        return segments;
    }

    static List<ExerciseSegment> getExternalSegments(
            @NonNull List<ExerciseSegmentInternal> internalSegments) {
        List<ExerciseSegment> externalSegments = new ArrayList<>(internalSegments.size());
        internalSegments.forEach((segment) -> externalSegments.add(segment.toExternalRecord()));
        return externalSegments;
    }

    static void writeSegmentsToParcel(
            @Nullable List<ExerciseSegmentInternal> segments, Parcel parcel) {
        if (segments == null) {
            parcel.writeInt(0);
            return;
        }
        parcel.writeInt(segments.size());
        segments.forEach((segment) -> segment.writeToParcel(parcel));
    }

    /** Writes record to parcel. */
    @VisibleForTesting
    public void writeToParcel(Parcel parcel) {
        parcel.writeLong(mStartTime);
        parcel.writeLong(mEndTime);
        parcel.writeInt(mRepetitionsCount);
        parcel.writeInt(mSegmentType);
        if (AconfigFlagHelper.isExerciseSegmentImprovementsEnabled()) {
            boolean weightIsSet = mWeightGrams != null;
            parcel.writeBoolean(weightIsSet);
            if (weightIsSet) {
                parcel.writeDouble(mWeightGrams);
            }
            parcel.writeInt(mSetIndex);
            parcel.writeFloat(mRateOfPerceivedExertion);
        }
    }

    /** Sets segment type. Returns record with type set. */
    @VisibleForTesting
    public ExerciseSegment toExternalRecord() {
        ExerciseSegment.Builder builder =
                new ExerciseSegment.Builder(
                        Instant.ofEpochMilli(mStartTime),
                        Instant.ofEpochMilli(mEndTime),
                        getSegmentType());
        builder.setRepetitionsCount(mRepetitionsCount);
        // Check flag directly as well as the helper method to remove lint check errors that aren't
        // able to detect that this flag is checked in the helper method.
        if (Flags.exerciseSegmentImprovements()
                && AconfigFlagHelper.isExerciseSegmentImprovementsEnabled()) {
            if (mWeightGrams != null) {
                builder.setWeight(Mass.fromGrams(mWeightGrams));
            }
            if (mSetIndex != DEFAULT_INT) {
                builder.setSetIndex(mSetIndex);
            }
            if (mRateOfPerceivedExertion != DEFAULT_FLOAT) {
                builder.setRateOfPerceivedExertion(mRateOfPerceivedExertion);
            }
        }
        return builder.buildWithoutValidation();
    }

    /** Sets segment start time. Returns record with start time set. */
    public ExerciseSegmentInternal setStartTime(long startTime) {
        mStartTime = startTime;
        return this;
    }

    /** Sets segment end time. Returns record with end time set. */
    public ExerciseSegmentInternal setEndTime(long endTime) {
        mEndTime = endTime;
        return this;
    }

    /** Returns segment start time. */
    public long getStartTime() {
        return mStartTime;
    }

    /** Returns segments end time. */
    public long getEndTime() {
        return mEndTime;
    }

    /** Returns segments repetitions count. */
    public int getRepetitionsCount() {
        return mRepetitionsCount;
    }

    /** Returns weight used during the exercise segment. */
    @Nullable
    public Double getWeightGrams() {
        return mWeightGrams;
    }

    /** Returns set index for the exercise segment. */
    public int getSetIndex() {
        return mSetIndex;
    }

    /** Returns rate of perceived exertion for the exercise segment. */
    public float getRateOfPerceivedExertion() {
        return mRateOfPerceivedExertion;
    }

    /** Sets segment repetitions count. Return record with repetitions set. */
    public ExerciseSegmentInternal setRepetitionsCount(int repetitionsCount) {
        mRepetitionsCount = repetitionsCount;
        return this;
    }

    /** Sets weight used during the exercise segment. Returns record with weight. */
    public ExerciseSegmentInternal setWeightGrams(double weightGrams) {
        mWeightGrams = weightGrams;
        return this;
    }

    /** Sets set index for the exercise segment. Returns record with set index. */
    public ExerciseSegmentInternal setSetIndex(int setIndex) {
        mSetIndex = setIndex;
        return this;
    }

    /**
     * Sets rate of perceived exertion for the exercise segment. Returns record with rate of
     * perceived exertion.
     */
    public ExerciseSegmentInternal setRateOfPerceivedExertion(float rateOfPerceivedExertion) {
        mRateOfPerceivedExertion = rateOfPerceivedExertion;
        return this;
    }

    /** Returns segment type. */
    public int getSegmentType() {
        return mSegmentType;
    }

    /** Sets segment type. Returns record with type set. */
    public ExerciseSegmentInternal setSegmentType(int segmentType) {
        mSegmentType = segmentType;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExerciseSegmentInternal)) return false;
        ExerciseSegmentInternal that = (ExerciseSegmentInternal) o;
        return mSegmentType == that.mSegmentType
                && mRepetitionsCount == that.mRepetitionsCount
                && mStartTime == that.mStartTime
                && mEndTime == that.mEndTime
                && Objects.equals(mWeightGrams, that.mWeightGrams)
                && mSetIndex == that.mSetIndex
                && mRateOfPerceivedExertion == that.mRateOfPerceivedExertion;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mStartTime,
                mEndTime,
                mSegmentType,
                mRepetitionsCount,
                mWeightGrams,
                mSetIndex,
                mRateOfPerceivedExertion);
    }
}
