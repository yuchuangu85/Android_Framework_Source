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

import static android.health.connect.datatypes.validation.ValidationUtils.validateIntDefValue;

import static com.android.healthfitness.flags.Flags.FLAG_SMOKING;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.health.connect.datatypes.units.Mass;
import android.health.connect.datatypes.validation.ValidationUtils;
import android.health.connect.internal.datatypes.NicotineIntakeRecordInternal;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;

/** Captures the nicotine intake. Each record represents an interval of nicotine intake */
@FlaggedApi(FLAG_SMOKING)
@Identifier(recordIdentifier = RecordTypeIdentifier.RECORD_TYPE_NICOTINE_INTAKE)
public final class NicotineIntakeRecord extends IntervalRecord {

    /** Vape nicotine intake. */
    public static final int NICOTINE_INTAKE_TYPE_VAPE = 0;

    /** Cigarette nicotine intake. */
    public static final int NICOTINE_INTAKE_TYPE_CIGARETTE = 1;

    /**
     * Valid set of values for {@link NicotineIntakeType}. Update this set when adding a new type or
     * deprecating an existing type.
     */
    private static final Set<Integer> VALID_NICOTINE_INTAKE_TYPES =
            Set.of(NICOTINE_INTAKE_TYPE_VAPE, NICOTINE_INTAKE_TYPE_CIGARETTE);

    @Nullable private final Mass mNicotineIntake;
    private final int mQuantity;
    private final int mNicotineIntakeType;

    /**
     * Builds {@link NicotineIntakeRecord} instance
     *
     * @param metadata Metadata to be associated with the record. See {@link Metadata}
     * @param startTime Start time of this record
     * @param startZoneOffset Zone offset of the user when the session started
     * @param endTime End time of this record
     * @param endZoneOffset Zone offset of the user when the session ended
     * @param nicotineIntake Mass of nicotine intake in this session
     * @param quantity Number of puffs or cigarettes in this session
     * @param nicotineIntakeType Nicotine intake type of this session
     * @param skipValidation Boolean flag to skip validation of record values
     */
    private NicotineIntakeRecord(
            @NonNull Metadata metadata,
            @NonNull Instant startTime,
            @NonNull ZoneOffset startZoneOffset,
            @NonNull Instant endTime,
            @NonNull ZoneOffset endZoneOffset,
            @Nullable Mass nicotineIntake,
            int quantity,
            @NicotineIntakeType int nicotineIntakeType,
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
            ValidationUtils.requireInRange(quantity, 1, 100, "quantity");
            validateIntDefValue(
                    nicotineIntakeType,
                    VALID_NICOTINE_INTAKE_TYPES,
                    NicotineIntakeType.class.getSimpleName());
        }
        mNicotineIntake = nicotineIntake;
        mQuantity = quantity;
        mNicotineIntakeType = nicotineIntakeType;
    }

    /**
     * @return Mass of nicotine intake
     */
    @Nullable
    public Mass getNicotineIntake() {
        return mNicotineIntake;
    }

    /**
     * @return Number of puffs or cigarettes smoked
     */
    public int getQuantity() {
        return mQuantity;
    }

    /**
     * @return Type of nicotine intake
     */
    @NicotineIntakeType
    public int getNicotineIntakeType() {
        return mNicotineIntakeType;
    }

    /**
     * Indicates whether some other object is "equal to" this one.
     *
     * @param o the reference object with which to compare.
     * @return {@code true} if this object is the same as the other object
     */
    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof NicotineIntakeRecord)) return false;
        if (!super.equals(o)) return false;
        NicotineIntakeRecord that = (NicotineIntakeRecord) o;
        return Objects.equals(mNicotineIntake, that.mNicotineIntake)
                && mQuantity == that.mQuantity
                && mNicotineIntakeType == that.mNicotineIntakeType;
    }

    /**
     * @return a hash code value for this object.
     */
    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mNicotineIntake, mQuantity, mNicotineIntakeType);
    }

    /** @hide */
    @IntDef({NICOTINE_INTAKE_TYPE_VAPE, NICOTINE_INTAKE_TYPE_CIGARETTE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface NicotineIntakeType {}

    /** Builder class for {@link NicotineIntakeRecord} */
    public static final class Builder {
        private final Metadata mMetadata;
        private final Instant mStartTime;
        private final Instant mEndTime;
        private ZoneOffset mStartZoneOffset;
        private ZoneOffset mEndZoneOffset;
        @Nullable private Mass mNicotineIntake;
        private final int mQuantity;
        private final int mNicotineIntakeType;

        /**
         * @param metadata Metadata to be associated with the record. See {@link Metadata}
         * @param startTime Start time of this record
         * @param endTime End time of this record
         * @param quantity number of puffs or cigarettes in smoked in this record
         * @param nicotineIntakeType nicotine intake type of this record
         */
        public Builder(
                @NonNull Metadata metadata,
                @NonNull Instant startTime,
                @NonNull Instant endTime,
                int quantity,
                @NicotineIntakeType int nicotineIntakeType) {
            mMetadata = Objects.requireNonNull(metadata);
            mStartTime = Objects.requireNonNull(startTime);
            mEndTime = Objects.requireNonNull(endTime);
            mStartZoneOffset = ZoneOffset.systemDefault().getRules().getOffset(startTime);
            mEndZoneOffset = ZoneOffset.systemDefault().getRules().getOffset(startTime);
            mQuantity = quantity;
            mNicotineIntakeType = nicotineIntakeType;
        }

        /**
         * Sets the {@link ZoneOffset} of the user when the activity started.
         *
         * <p>Defaults to the system zone offset if not set.
         */
        @NonNull
        public Builder setStartZoneOffset(@NonNull ZoneOffset startZoneOffset) {
            mStartZoneOffset = Objects.requireNonNull(startZoneOffset);
            return this;
        }

        /**
         * Sets the {@link ZoneOffset} of the user when the activity ended.
         *
         * <p>Defaults to the system zone offset if not set.
         */
        @NonNull
        public Builder setEndZoneOffset(@NonNull ZoneOffset endZoneOffset) {
            mEndZoneOffset = Objects.requireNonNull(endZoneOffset);
            return this;
        }

        /**
         * Sets the nicotine intake in this activity
         *
         * @param nicotineIntake mass of nicotine intake in this session
         */
        @NonNull
        public Builder setNicotineIntake(@Nullable Mass nicotineIntake) {
            mNicotineIntake = nicotineIntake;
            return this;
        }

        /** @hide */
        @NonNull
        public NicotineIntakeRecord build(boolean skipValidation) {
            return new NicotineIntakeRecord(
                    mMetadata,
                    mStartTime,
                    mStartZoneOffset,
                    mEndTime,
                    mEndZoneOffset,
                    mNicotineIntake,
                    mQuantity,
                    mNicotineIntakeType,
                    skipValidation);
        }

        /** Returns {@link NicotineIntakeRecord} */
        @NonNull
        public NicotineIntakeRecord build() {
            return build(false);
        }
    }

    /** @hide */
    @Override
    public NicotineIntakeRecordInternal toRecordInternal() {
        NicotineIntakeRecordInternal recordInternal =
                (NicotineIntakeRecordInternal)
                        new NicotineIntakeRecordInternal().setMetaData(getMetadata());

        recordInternal.setTimeInterval(this);
        recordInternal.setQuantity(mQuantity);
        recordInternal.setNicotineIntakeType(mNicotineIntakeType);

        if (mNicotineIntake != null) {
            recordInternal.setNicotineIntakeGrams(mNicotineIntake.getInGrams());
        }

        return recordInternal;
    }
}
