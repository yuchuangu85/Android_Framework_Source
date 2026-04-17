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

package android.health.connect.datatypes;

import static android.health.connect.Constants.DEFAULT_INT;
import static android.health.connect.datatypes.validation.ValidationUtils.requireInRange;
import static android.health.connect.datatypes.validation.ValidationUtils.validateIntDefValue;

import static com.android.healthfitness.flags.Flags.FLAG_CYCLE_PHASES_FLAG;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.health.connect.internal.datatypes.MenstrualCyclePhaseRecordInternal;
import android.health.connect.internal.datatypes.RecordInternal;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a user's menstrual cycle phase for a specific day.
 *
 * <p>This record is designed to capture the current phase of the menstrual cycle (e.g., follicular,
 * luteal) for a given day. It is not intended for predictive use cases.
 */
@FlaggedApi(FLAG_CYCLE_PHASES_FLAG)
@Identifier(recordIdentifier = RecordTypeIdentifier.RECORD_TYPE_MENSTRUAL_CYCLE_PHASE)
public final class MenstrualCyclePhaseRecord extends IntervalRecord {
    /**
     * Represents an unknown menstrual cycle phase. Not available to developers.
     *
     * @hide
     */
    public static final int PHASE_UNKNOWN = 0;

    /**
     * Represents the follicular phase of the menstrual cycle. This phase begins with menstruation
     * and ends with ovulation.
     */
    public static final int PHASE_FOLLICULAR = 1;

    /**
     * Represents the luteal phase of the menstrual cycle. This phase begins after ovulation and
     * ends just before the next menstrual period.
     */
    public static final int PHASE_LUTEAL = 2;

    private static final int DAY_OF_CYCLE_LOWER_BOUND = 1;
    private static final int DAY_OF_CYCLE_UPPER_BOUND = 180;

    /**
     * The phase of the menstrual cycle.
     *
     * <p>Possible values include {@link #PHASE_UNKNOWN}, {@link #PHASE_FOLLICULAR}, and {@link
     * #PHASE_LUTEAL}.
     */
    @CyclePhase private final int mPhase;

    /**
     * The day within the menstrual cycle.
     *
     * <p>This field is optional. If not provided, a value of -1 is used.
     */
    private final int mDayOfCycle;

    /** @hide */
    @IntDef({PHASE_UNKNOWN, PHASE_FOLLICULAR, PHASE_LUTEAL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CyclePhase {}

    private static final Set<Integer> VALID_CYCLE_PHASES = Set.of(PHASE_FOLLICULAR, PHASE_LUTEAL);

    /** Returns the phase of the menstrual cycle. */
    @CyclePhase
    public int getPhase() {
        return mPhase;
    }

    /** Returns whether the day of cycle was set in this record. */
    public boolean isDayOfCycleSet() {
        return mDayOfCycle != DEFAULT_INT;
    }

    /**
     * Returns the day within the menstrual cycle.
     *
     * @throws IllegalStateException if the day of cycle was not set.
     */
    public int getDayOfCycle() {
        if (!isDayOfCycleSet()) {
            throw new IllegalStateException("Day of cycle was not set.");
        }
        return mDayOfCycle;
    }

    /** Returns the date of the record. */
    @NonNull
    public LocalDate getDate() {
        return getStartTime().atOffset(getStartZoneOffset()).toLocalDate();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        MenstrualCyclePhaseRecord that = (MenstrualCyclePhaseRecord) o;
        return mPhase == that.mPhase && mDayOfCycle == that.mDayOfCycle;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mPhase, mDayOfCycle);
    }

    /** Builder class for {@link MenstrualCyclePhaseRecord}. */
    @FlaggedApi(FLAG_CYCLE_PHASES_FLAG)
    public static final class Builder {
        private final Metadata mMetadata;
        private final LocalDateTime mStartOfDay;
        private final LocalDateTime mEndOfDay;
        private ZoneOffset mZoneOffset;
        @CyclePhase private final int mPhase;
        private int mDayOfCycle = DEFAULT_INT;

        /**
         * @param metadata Metadata to be associated with the record. See {@link Metadata}.
         * @param date The date of this record.
         * @param phase The cycle phase for this record.
         */
        public Builder(@NonNull Metadata metadata, @NonNull LocalDate date, @CyclePhase int phase) {
            Objects.requireNonNull(metadata);
            Objects.requireNonNull(date);

            mMetadata = metadata;
            mPhase = phase;

            mStartOfDay = date.atStartOfDay();
            mEndOfDay = LocalTime.MAX.atDate(date);
            mZoneOffset = ZoneId.systemDefault().getRules().getOffset(mStartOfDay);
        }

        /**
         * Sets the day of cycle for this data.
         *
         * @throws IllegalArgumentException if the provided {@code dayOfCycle} is less than 1 or
         *     more than 180.
         */
        @NonNull
        public Builder setDayOfCycle(@IntRange(from = 1, to = 180) int dayOfCycle) {
            mDayOfCycle = dayOfCycle;
            requireInRange(
                    dayOfCycle, DAY_OF_CYCLE_LOWER_BOUND, DAY_OF_CYCLE_UPPER_BOUND, "dayOfCycle");
            return this;
        }

        /** Clears the day of cycle for this data. */
        @NonNull
        public Builder clearDayOfCycle() {
            mDayOfCycle = DEFAULT_INT;
            return this;
        }

        /**
         * Sets the {@link ZoneOffset} of the user at the beginning of the measurement day.
         *
         * <p>If not set, the system default zone offset will be used.
         */
        @NonNull
        public Builder setStartZoneOffset(@NonNull ZoneOffset zoneOffset) {
            Objects.requireNonNull(zoneOffset);
            mZoneOffset = zoneOffset;
            return this;

        }

        /** Clears the {@link ZoneOffset} of the user at the beginning of the measurement day. */
        @NonNull
        public Builder clearStartZoneOffset() {
            mZoneOffset = ZoneId.systemDefault().getRules().getOffset(mStartOfDay);
            return this;
        }

        /**
         * @return Object of {@link MenstrualCyclePhaseRecord} without validating the values.
         * @hide
         */
        @NonNull
        public MenstrualCyclePhaseRecord buildWithoutValidation() {
            return new MenstrualCyclePhaseRecord(
                    mMetadata,
                    mStartOfDay.toInstant(mZoneOffset),
                    mZoneOffset,
                    mEndOfDay.toInstant(mZoneOffset),
                    mZoneOffset,
                    mPhase,
                    mDayOfCycle,
                    /* skipValidation= */ true);
        }

        /**
         * @return Object of {@link MenstrualCyclePhaseRecord}
         */
        @NonNull
        public MenstrualCyclePhaseRecord build() {
            return new MenstrualCyclePhaseRecord(
                    mMetadata,
                    mStartOfDay.toInstant(mZoneOffset),
                    mZoneOffset,
                    mEndOfDay.toInstant(mZoneOffset),
                    mZoneOffset,
                    mPhase,
                    mDayOfCycle,
                    /* skipValidation= */ false);
        }
    }

    /**
     * @param metadata Metadata to be associated with the record. See {@link Metadata}.
     * @param startTime Start time of this record.
     * @param startZoneOffset Zone offset of the user when the measurement was taken.
     * @param endTime End time of this record.
     * @param endZoneOffset Zone offset of the user when the measurement was taken.
     * @param phase The cycle phase for this record.
     * @param dayOfCycle The day of the cycle for this record.
     * @param skipValidation Boolean flag to skip validation of record values.
     */
    private MenstrualCyclePhaseRecord(
            @NonNull Metadata metadata,
            @NonNull Instant startTime,
            @NonNull ZoneOffset startZoneOffset,
            @NonNull Instant endTime,
            @NonNull ZoneOffset endZoneOffset,
            @CyclePhase int phase,
            int dayOfCycle,
            boolean skipValidation) {
        super(
                metadata,
                startTime,
                startZoneOffset,
                endTime,
                endZoneOffset,
                skipValidation,
                /* enforceFutureTimeRestrictions= */ true);

        if (!skipValidation) {
            validateIntDefValue(phase, VALID_CYCLE_PHASES, CyclePhase.class.getSimpleName());
            if (dayOfCycle != DEFAULT_INT) {
                requireInRange(
                        dayOfCycle,
                        DAY_OF_CYCLE_LOWER_BOUND,
                        DAY_OF_CYCLE_UPPER_BOUND,
                        "dayOfCycle");
            }
        }

        mPhase = phase;
        mDayOfCycle = dayOfCycle;
    }

    /** @hide */
    @Override
    public RecordInternal<?> toRecordInternal() {
        MenstrualCyclePhaseRecordInternal recordInternal =
                (MenstrualCyclePhaseRecordInternal)
                        new MenstrualCyclePhaseRecordInternal().setMetaData(getMetadata());
        recordInternal
                .setStartTime(getStartTime().toEpochMilli())
                .setStartZoneOffset(getStartZoneOffset().getTotalSeconds())
                .setEndTime(getEndTime().toEpochMilli())
                .setEndZoneOffset(getEndZoneOffset().getTotalSeconds());
        recordInternal.setPhase(mPhase).setDayOfCycle(mDayOfCycle);
        return recordInternal;
    }
}
