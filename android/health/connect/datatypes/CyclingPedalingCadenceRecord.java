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
import android.health.connect.internal.datatypes.CyclingPedalingCadenceRecordInternal;

import com.android.healthfitness.flags.Flags;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Captures the user's cycling pedaling cadence. */
@Identifier(recordIdentifier = RecordTypeIdentifier.RECORD_TYPE_CYCLING_PEDALING_CADENCE)
public final class CyclingPedalingCadenceRecord extends IntervalRecord {

    /**
     * Metric identifier to retrieve average cycling pedaling cadence using aggregate APIs in {@link
     * HealthConnectManager}
     */
    @NonNull
    public static final AggregationType<Double> RPM_AVG =
            new AggregationType<>(
                    AggregationType.AggregationTypeIdentifier
                            .CYCLING_PEDALING_CADENCE_RECORD_RPM_AVG,
                    AggregationType.AVG,
                    RecordTypeIdentifier.RECORD_TYPE_CYCLING_PEDALING_CADENCE,
                    Double.class);

    /**
     * Metric identifier to retrieve minimum cycling pedaling cadence using aggregate APIs in {@link
     * HealthConnectManager}
     */
    @NonNull
    public static final AggregationType<Double> RPM_MIN =
            new AggregationType<>(
                    AggregationType.AggregationTypeIdentifier
                            .CYCLING_PEDALING_CADENCE_RECORD_RPM_MIN,
                    AggregationType.MIN,
                    RecordTypeIdentifier.RECORD_TYPE_CYCLING_PEDALING_CADENCE,
                    Double.class);

    /**
     * Metric identifier to retrieve maximum cycling pedaling cadence using aggregate APIs in {@link
     * HealthConnectManager}
     */
    @NonNull
    public static final AggregationType<Double> RPM_MAX =
            new AggregationType<>(
                    AggregationType.AggregationTypeIdentifier
                            .CYCLING_PEDALING_CADENCE_RECORD_RPM_MAX,
                    AggregationType.MAX,
                    RecordTypeIdentifier.RECORD_TYPE_CYCLING_PEDALING_CADENCE,
                    Double.class);

    private final List<CyclingPedalingCadenceRecordSample> mCyclingPedalingCadenceRecordSamples;

    /**
     * @param metadata Metadata to be associated with the record. See {@link Metadata}.
     * @param startTime Start time of this activity
     * @param startZoneOffset Zone offset of the user when the activity started
     * @param endTime End time of this activity
     * @param endZoneOffset Zone offset of the user when the activity finished
     * @param cyclingPedalingCadenceRecordSamples Samples of recorded CyclingPedalingCadenceRecord
     * @param skipValidation Boolean flag to skip validation of record values.
     */
    private CyclingPedalingCadenceRecord(
            @NonNull Metadata metadata,
            @NonNull Instant startTime,
            @NonNull ZoneOffset startZoneOffset,
            @NonNull Instant endTime,
            @NonNull ZoneOffset endZoneOffset,
            @NonNull List<CyclingPedalingCadenceRecordSample> cyclingPedalingCadenceRecordSamples,
            boolean skipValidation) {
        super(
                metadata,
                startTime,
                startZoneOffset,
                endTime,
                endZoneOffset,
                skipValidation,
                /* enforceFutureTimeRestrictions= */ true);
        Objects.requireNonNull(cyclingPedalingCadenceRecordSamples);
        if (!skipValidation) {
            ValidationUtils.validateSampleStartAndEndTime(
                    startTime,
                    endTime,
                    cyclingPedalingCadenceRecordSamples.stream()
                            .map(
                                    CyclingPedalingCadenceRecord.CyclingPedalingCadenceRecordSample
                                            ::getTime)
                            .toList());
        }
        mCyclingPedalingCadenceRecordSamples = cyclingPedalingCadenceRecordSamples;
    }

    /**
     * {@return CyclingPedalingCadenceRecord samples corresponding to this record, in ascending time
     * order}
     */
    @NonNull
    public List<CyclingPedalingCadenceRecordSample> getSamples() {
        return mCyclingPedalingCadenceRecordSamples;
    }

    /** Represents a single measurement of the cycling pedaling cadence. */
    public static final class CyclingPedalingCadenceRecordSample {
        private final double mRevolutionsPerMinute;
        private final Instant mTime;

        /**
         * CyclingPedalingCadenceRecord sample for entries of {@link CyclingPedalingCadenceRecord}
         *
         * @param revolutionsPerMinute Cycling revolutions per minute.
         * @param time The point in time when the measurement was taken.
         */
        public CyclingPedalingCadenceRecordSample(
                double revolutionsPerMinute, @NonNull Instant time) {
            this(revolutionsPerMinute, time, false);
        }

        /**
         * CyclingPedalingCadenceRecord sample for entries of {@link CyclingPedalingCadenceRecord}
         *
         * @param revolutionsPerMinute Cycling revolutions per minute.
         * @param time The point in time when the measurement was taken.
         * @param skipValidation Boolean flag to skip validation of record values.
         * @hide
         */
        public CyclingPedalingCadenceRecordSample(
                double revolutionsPerMinute, @NonNull Instant time, boolean skipValidation) {
            Objects.requireNonNull(time);
            if (!skipValidation) {
                ValidationUtils.requireInRange(
                        revolutionsPerMinute, 0.0, 10000.0, "revolutionsPerMinute");
            }
            mTime = time;
            mRevolutionsPerMinute = revolutionsPerMinute;
        }

        /**
         * @return RevolutionsPerMinute for this sample
         */
        public double getRevolutionsPerMinute() {
            return mRevolutionsPerMinute;
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
            if (Flags.sampleTimeOrdering()) {
                if (object instanceof CyclingPedalingCadenceRecordSample other) {
                    return getRevolutionsPerMinute() == other.getRevolutionsPerMinute()
                            && getTime().toEpochMilli() == other.getTime().toEpochMilli();
                }
            } else {
                if (super.equals(object) && object instanceof CyclingPedalingCadenceRecordSample) {
                    CyclingPedalingCadenceRecordSample other =
                            (CyclingPedalingCadenceRecordSample) object;
                    return getRevolutionsPerMinute() == other.getRevolutionsPerMinute()
                            && getTime().toEpochMilli() == other.getTime().toEpochMilli();
                }
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
            if (Flags.sampleTimeOrdering()) {
                return Objects.hash(getRevolutionsPerMinute(), getTime());
            } else {
                return Objects.hash(super.hashCode(), getRevolutionsPerMinute(), getTime());
            }
        }
    }

    /** Builder class for {@link CyclingPedalingCadenceRecord} */
    public static final class Builder {
        private final Metadata mMetadata;
        private final Instant mStartTime;
        private final Instant mEndTime;
        private final List<CyclingPedalingCadenceRecordSample> mSamples;
        private ZoneOffset mStartZoneOffset;
        private ZoneOffset mEndZoneOffset;

        /**
         * @param metadata Metadata to be associated with the record. See {@link Metadata}.
         * @param startTime Start time of this activity
         * @param endTime End time of this activity
         * @param samples Samples of recorded CyclingPedalingCadenceRecord. Only a single sample
         *     with a given time is accepted and samples with duplicate times will be silently
         *     dropped.
         */
        public Builder(
                @NonNull Metadata metadata,
                @NonNull Instant startTime,
                @NonNull Instant endTime,
                @NonNull List<CyclingPedalingCadenceRecordSample> samples) {
            Objects.requireNonNull(metadata);
            Objects.requireNonNull(startTime);
            Objects.requireNonNull(endTime);
            Objects.requireNonNull(samples);
            mMetadata = metadata;
            mStartTime = startTime;
            mEndTime = endTime;
            if (Flags.sampleTimeOrdering()) {
                TreeSet<CyclingPedalingCadenceRecordSample> sampleSet =
                        new TreeSet<>(
                                Comparator.comparing(CyclingPedalingCadenceRecordSample::getTime));
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
         * @return Object of {@link CyclingPedalingCadenceRecord} without validating the values.
         * @hide
         */
        @NonNull
        public CyclingPedalingCadenceRecord buildWithoutValidation() {
            return new CyclingPedalingCadenceRecord(
                    mMetadata,
                    mStartTime,
                    mStartZoneOffset,
                    mEndTime,
                    mEndZoneOffset,
                    mSamples,
                    true);
        }

        /**
         * @return Object of {@link CyclingPedalingCadenceRecord}
         */
        @NonNull
        public CyclingPedalingCadenceRecord build() {
            return new CyclingPedalingCadenceRecord(
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
        if (super.equals(object)) {
            if (!(object instanceof CyclingPedalingCadenceRecord other)) return false;
            if (Flags.sampleTimeOrdering()) {
                return getSamples().equals(other.getSamples());
            } else {
                if (getSamples().size() != other.getSamples().size()) return false;
                for (int idx = 0; idx < getSamples().size(); idx++) {
                    if (getSamples().get(idx).getRevolutionsPerMinute()
                                    != other.getSamples().get(idx).getRevolutionsPerMinute()
                            || getSamples().get(idx).getTime().toEpochMilli()
                                    != other.getSamples().get(idx).getTime().toEpochMilli()) {
                        return false;
                    }
                }
            }
            return true;
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
    public CyclingPedalingCadenceRecordInternal toRecordInternal() {
        Set<CyclingPedalingCadenceRecordInternal.CyclingPedalingCadenceRecordSample> samples =
                new HashSet<>(getSamples().size());
        for (CyclingPedalingCadenceRecord.CyclingPedalingCadenceRecordSample
                cyclingPedalingCadenceRecordSample : getSamples()) {
            samples.add(
                    new CyclingPedalingCadenceRecordInternal.CyclingPedalingCadenceRecordSample(
                            cyclingPedalingCadenceRecordSample.getRevolutionsPerMinute(),
                            cyclingPedalingCadenceRecordSample.getTime().toEpochMilli()));
        }

        CyclingPedalingCadenceRecordInternal recordInternal =
                new CyclingPedalingCadenceRecordInternal(samples);
        recordInternal.setTimeInterval(this);
        recordInternal.setMetaData(getMetadata());
        return recordInternal;
    }
}
