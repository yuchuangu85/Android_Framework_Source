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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.health.connect.HealthConnectManager;
import android.health.connect.datatypes.validation.ValidationUtils;
import android.health.connect.internal.datatypes.StepsCadenceRecordInternal;

import com.android.healthfitness.flags.Flags;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Captures the user's steps cadence. */
@Identifier(recordIdentifier = RecordTypeIdentifier.RECORD_TYPE_STEPS_CADENCE)
public final class StepsCadenceRecord extends IntervalRecord {

    /**
     * Metric identifier to retrieve average Steps cadence rate using aggregate APIs in {@link
     * HealthConnectManager}
     */
    @NonNull
    public static final AggregationType<Double> STEPS_CADENCE_RATE_AVG =
            new AggregationType<>(
                    AggregationType.AggregationTypeIdentifier.STEPS_CADENCE_RECORD_RATE_AVG,
                    AggregationType.AVG,
                    RecordTypeIdentifier.RECORD_TYPE_STEPS_CADENCE,
                    Double.class);

    /**
     * Metric identifier to retrieve minimum Steps cadence rate using aggregate APIs in {@link
     * HealthConnectManager}
     */
    @NonNull
    public static final AggregationType<Double> STEPS_CADENCE_RATE_MIN =
            new AggregationType<>(
                    AggregationType.AggregationTypeIdentifier.STEPS_CADENCE_RECORD_RATE_MIN,
                    AggregationType.MIN,
                    RecordTypeIdentifier.RECORD_TYPE_STEPS_CADENCE,
                    Double.class);

    /**
     * Metric identifier to retrieve maximum Steps cadence rate using aggregate APIs in {@link
     * HealthConnectManager}
     */
    @NonNull
    public static final AggregationType<Double> STEPS_CADENCE_RATE_MAX =
            new AggregationType<>(
                    AggregationType.AggregationTypeIdentifier.STEPS_CADENCE_RECORD_RATE_MAX,
                    AggregationType.MAX,
                    RecordTypeIdentifier.RECORD_TYPE_STEPS_CADENCE,
                    Double.class);

    private final List<StepsCadenceRecordSample> mSamples;

    /**
     * @param metadata Metadata to be associated with the record. See {@link Metadata}.
     * @param startTime Start time of this activity
     * @param startZoneOffset Zone offset of the user when the activity started
     * @param endTime End time of this activity
     * @param endZoneOffset Zone offset of the user when the activity finished
     * @param samples Samples of recorded StepsCadenceRecord, sorted by time
     * @param skipValidation Boolean flag to skip validation of record values.
     */
    private StepsCadenceRecord(
            @NonNull Metadata metadata,
            @NonNull Instant startTime,
            @NonNull ZoneOffset startZoneOffset,
            @NonNull Instant endTime,
            @NonNull ZoneOffset endZoneOffset,
            @NonNull List<StepsCadenceRecordSample> samples,
            boolean skipValidation) {
        super(
                metadata,
                startTime,
                startZoneOffset,
                endTime,
                endZoneOffset,
                skipValidation,
                /* enforceFutureTimeRestrictions= */ true);
        Objects.requireNonNull(samples);
        if (!skipValidation) {
            ValidationUtils.validateSampleStartAndEndTime(
                    startTime,
                    endTime,
                    samples.stream().map(StepsCadenceRecordSample::getTime).toList());
        }
        mSamples = samples;
    }

    /**
     * @return StepsCadenceRecord samples corresponding to this record, in ascending time order
     */
    @NonNull
    public List<StepsCadenceRecordSample> getSamples() {
        return mSamples;
    }

    /** Represents a single measurement of the steps cadence. */
    public static final class StepsCadenceRecordSample {
        private final double mRate;
        private final Instant mTime;

        /**
         * StepsCadenceRecord sample for entries of {@link StepsCadenceRecord}
         *
         * @param rate Rate in steps per minute.
         * @param time The point in time when the measurement was taken.
         */
        public StepsCadenceRecordSample(double rate, @NonNull Instant time) {
            this(rate, time, false);
        }

        /**
         * StepsCadenceRecord sample for entries of {@link StepsCadenceRecord}
         *
         * @param rate Rate in steps per minute.
         * @param time The point in time when the measurement was taken.
         * @param skipValidation Boolean flag to skip validation of record values.
         * @hide
         */
        public StepsCadenceRecordSample(
                double rate, @NonNull Instant time, boolean skipValidation) {
            Objects.requireNonNull(time);
            if (!skipValidation) {
                ValidationUtils.requireInRange(rate, 0.0, 10000.0, "rate");
            }
            mTime = time;
            mRate = rate;
        }

        /**
         * @return Rate for this sample
         */
        public double getRate() {
            return mRate;
        }

        /**
         * @return time at which this sample was recorded
         */
        @NonNull
        public Instant getTime() {
            return mTime;
        }

        /**
         * Indicates whether some other object is "equal to" this one.
         *
         * @param object the reference object with which to compare.
         * @return {@code true} if this object is the same as the obj
         */
        @Override
        public boolean equals(@Nullable Object object) {
            if (object instanceof StepsCadenceRecordSample other) {
                return getRate() == other.getRate()
                        && getTime().toEpochMilli() == other.getTime().toEpochMilli();
            }
            return false;
        }

        /**
         * Returns a hash code value for the object.
         *
         * @return a hash code value for this object.
         */
        @Override
        public int hashCode() {
            return Objects.hash(getRate(), getTime());
        }
    }

    /** Builder class for {@link StepsCadenceRecord} */
    public static final class Builder {
        private final Metadata mMetadata;
        private final Instant mStartTime;
        private final Instant mEndTime;
        private final List<StepsCadenceRecordSample> mSamples;
        private ZoneOffset mStartZoneOffset;
        private ZoneOffset mEndZoneOffset;

        /**
         * @param metadata Metadata to be associated with the record. See {@link Metadata}.
         * @param startTime Start time of this activity
         * @param endTime End time of this activity
         * @param samples Samples of recorded StepsCadenceRecord. Only a single sample with a given
         *     time is accepted and samples with duplicate times will be silently dropped.
         */
        public Builder(
                @NonNull Metadata metadata,
                @NonNull Instant startTime,
                @NonNull Instant endTime,
                @NonNull List<StepsCadenceRecordSample> samples) {
            Objects.requireNonNull(metadata);
            Objects.requireNonNull(startTime);
            Objects.requireNonNull(endTime);
            Objects.requireNonNull(samples);
            mMetadata = metadata;
            mStartTime = startTime;
            mEndTime = endTime;
            if (Flags.sampleTimeOrdering()) {
                TreeSet<StepsCadenceRecordSample> sampleSet =
                        new TreeSet<>(Comparator.comparing(StepsCadenceRecordSample::getTime));
                sampleSet.addAll(samples);
                mSamples = sampleSet.stream().toList();
            } else {
                mSamples = samples;
            }
            mStartZoneOffset = ZoneOffset.systemDefault().getRules().getOffset(startTime);
            mEndZoneOffset = ZoneOffset.systemDefault().getRules().getOffset(endTime);
        }

        /** Sets the zone offset of the user when the activity started */
        @NonNull
        public Builder setStartZoneOffset(@NonNull ZoneOffset startZoneOffset) {
            Objects.requireNonNull(startZoneOffset);
            mStartZoneOffset = startZoneOffset;
            return this;
        }

        /** Sets the zone offset of the user when the activity ended */
        @NonNull
        public Builder setEndZoneOffset(@NonNull ZoneOffset endZoneOffset) {
            Objects.requireNonNull(endZoneOffset);
            mEndZoneOffset = endZoneOffset;
            return this;
        }

        /** Sets the start zone offset of this record to system default. */
        @NonNull
        public Builder clearStartZoneOffset() {
            mStartZoneOffset = RecordUtils.getDefaultZoneOffset();
            return this;
        }

        /** Sets the start zone offset of this record to system default. */
        @NonNull
        public Builder clearEndZoneOffset() {
            mEndZoneOffset = RecordUtils.getDefaultZoneOffset();
            return this;
        }

        /**
         * @return Object of {@link StepsCadenceRecord} without validating the values.
         * @hide
         */
        @NonNull
        public StepsCadenceRecord buildWithoutValidation() {
            return new StepsCadenceRecord(
                    mMetadata,
                    mStartTime,
                    mStartZoneOffset,
                    mEndTime,
                    mEndZoneOffset,
                    mSamples,
                    true);
        }

        /**
         * @return Object of {@link StepsCadenceRecord}
         */
        @NonNull
        public StepsCadenceRecord build() {
            return new StepsCadenceRecord(
                    mMetadata,
                    mStartTime,
                    mStartZoneOffset,
                    mEndTime,
                    mEndZoneOffset,
                    mSamples,
                    false);
        }
    }

    /**
     * Indicates whether some other object is "equal to" this one.
     *
     * @param object the reference object with which to compare.
     * @return {@code true} if this object is the same as the obj
     */
    @Override
    public boolean equals(@Nullable Object object) {
        if (super.equals(object) && object instanceof StepsCadenceRecord other) {
            return Objects.equals(getSamples(), other.getSamples());
        }
        return false;
    }

    /** Returns a hash code value for the object. */
    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), getSamples());
    }

    /** @hide */
    @Override
    public StepsCadenceRecordInternal toRecordInternal() {
        Set<StepsCadenceRecordInternal.StepsCadenceRecordSample> samples =
                new HashSet<>(getSamples().size());

        for (StepsCadenceRecord.StepsCadenceRecordSample stepsCadenceRecordSample : getSamples()) {
            samples.add(
                    new StepsCadenceRecordInternal.StepsCadenceRecordSample(
                            stepsCadenceRecordSample.getRate(),
                            stepsCadenceRecordSample.getTime().toEpochMilli()));
        }
        StepsCadenceRecordInternal recordInternal =
                (StepsCadenceRecordInternal)
                        new StepsCadenceRecordInternal(samples).setMetaData(getMetadata());
        recordInternal.setTimeInterval(this);

        return recordInternal;
    }
}
