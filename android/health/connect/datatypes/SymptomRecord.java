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

import static com.android.healthfitness.flags.Flags.FLAG_SYMPTOMS;
import static com.android.healthfitness.flags.Flags.FLAG_TEMPORAL_FIELD_API;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.health.connect.internal.datatypes.SymptomRecordInternal;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;

/**
 * Captures a description of a user's symptom. Each record represents a particular symptom
 * experienced one or more times over a time period.
 *
 * <p><b>Permission Handling:</b> Each symptom type is guarded by its own specific read and write
 * permissions (e.g., {@code android.permission.health.READ_SYMPTOM_COUGH}).
 *
 * <p>An app has read access to a symptom type if:
 *
 * <ul>
 *   <li>The app is the owner of the data (i.e., it wrote the data), and it holds <b>either</b> the
 *       specific READ or WRITE permission for that symptom type.
 *   <li>The app is not the owner of the data, but it has been granted the specific READ permission
 *       for that symptom type.
 * </ul>
 *
 * <p>Enforcement:
 *
 * <ul>
 *   <li>When reading symptoms by a time range, records for which the calling app does not have read
 *       access to the symptom type will be silently skipped.
 *   <li>When reading a single symptom record by its ID, if the calling app does not have read
 *       access to that record's symptom type, a {@link SecurityException} will be thrown.
 *   <li>When performing a batch read of multiple symptom records by their IDs, a {@link
 *       SecurityException} will be thrown if the calling app does not have read access to
 *       <i>any</i> of the symptom types included in the batch request.
 * </ul>
 */
// TODO(b/448882608): Add multi app API CTS tests for SymptomRecord.
// TODO(b/448836403): Add change log handling for symptoms permissions, so that each change log is
// filtered based on the correct symptom permission for the corresponding symptom type.
@FlaggedApi(FLAG_SYMPTOMS)
@Identifier(recordIdentifier = RecordTypeIdentifier.RECORD_TYPE_SYMPTOM)
public final class SymptomRecord extends IntervalRecord {

    @SymptomType private final int mSymptomType;
    @Nullable private final String mNotes;
    @SymptomSeverity private final int mSeverity;
    private final int mCount;
    @SymptomRecordTemporalType private final int mTemporalType;

    /**
     * @param symptomType The type of symptom, from {@link SymptomType}.
     * @param notes A description of the symptom.
     * @param severity The severity of the symptom.
     * @param count The number of occurrences of the symptom.
     * @param temporalType The temporal type of the record.
     * @param startTime The start time of the record.
     * @param startZoneOffset The start zone offset of the record.
     * @param endTime The end time of the record.
     * @param endZoneOffset The end zone offset of the record.
     * @param metadata The metadata of the record.
     * @param skipValidation Whether to skip validation of the record.
     * @hide
     */
    private SymptomRecord(
            @SymptomType int symptomType,
            @Nullable String notes,
            @SymptomSeverity int severity,
            int count,
            @SymptomRecordTemporalType int temporalType,
            @NonNull Instant startTime,
            @NonNull ZoneOffset startZoneOffset,
            @NonNull Instant endTime,
            @NonNull ZoneOffset endZoneOffset,
            @NonNull Metadata metadata,
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
            validateIntDefValue(
                    symptomType, VALID_SYMPTOM_TYPES, SymptomType.class.getSimpleName());
        }
        mSymptomType = symptomType;
        mNotes = notes;
        mSeverity = severity;
        mCount = count;
        mTemporalType = temporalType;
    }

    /** Returns the type of symptom for this record. */
    @SymptomType
    public int getSymptomType() {
        return mSymptomType;
    }

    /** Returns the notes for this record. */
    @Nullable
    public String getNotes() {
        return mNotes;
    }

    /** Returns the severity of the symptom for this record. */
    @SymptomSeverity
    public int getSeverity() {
        return mSeverity;
    }

    /** Returns the number of occurrences of the symptom for this record. */
    public int getCount() {
        return mCount;
    }

    /**
     * Returns the temporal type of this record, indicating whether it represents an instant, an
     * interval, or a local date.
     *
     * @return The temporal type, as one of {@link #RECORD_TEMPORAL_TYPE_INSTANT}, {@link
     *     #RECORD_TEMPORAL_TYPE_INTERVAL}, or {@link #RECORD_TEMPORAL_TYPE_LOCAL_DATE}.
     */
    @SymptomRecordTemporalType
    public int getTemporalType() {
        return mTemporalType;
    }

    /** Returns the date of the record, or null if the record is not a local date record. */
    @Nullable
    public LocalDate getDate() {
        if (getTemporalType() == RECORD_TEMPORAL_TYPE_LOCAL_DATE) {
            return getStartTime().atOffset(getStartZoneOffset()).toLocalDate();
        }
        return null;
    }

    /** @hide */
    @Override
    @NonNull
    public SymptomRecordInternal toRecordInternal() {
        SymptomRecordInternal recordInternal =
                (SymptomRecordInternal) new SymptomRecordInternal().setMetaData(getMetadata());
        recordInternal.setTimeInterval(this);
        recordInternal.setSymptomType(mSymptomType);
        if (mNotes != null) {
            recordInternal.setNotes(mNotes);
        }
        recordInternal.setSeverity(mSeverity);
        recordInternal.setCount(mCount);
        recordInternal.setTemporalType(mTemporalType);
        return recordInternal;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof SymptomRecord that)) return false;
        if (!super.equals(o)) return false;
        return mSymptomType == that.mSymptomType
                && mSeverity == that.mSeverity
                && mCount == that.mCount
                && mTemporalType == that.mTemporalType
                && Objects.equals(mNotes, that.mNotes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                super.hashCode(), mSymptomType, mNotes, mSeverity, mCount, mTemporalType);
    }

    /** @hide */
    @IntDef({
        SYMPTOM_TYPE_ABDOMINAL_PAIN,
        SYMPTOM_TYPE_ACNE,
        SYMPTOM_TYPE_BACK_PAIN,
        SYMPTOM_TYPE_BLOATING,
        SYMPTOM_TYPE_BRAIN_FOG,
        SYMPTOM_TYPE_BREAST_TENDERNESS,
        SYMPTOM_TYPE_BRITTLE_NAILS,
        SYMPTOM_TYPE_BURNING_MOUTH,
        SYMPTOM_TYPE_CHEST_PAIN,
        SYMPTOM_TYPE_CHEST_TIGHTNESS,
        SYMPTOM_TYPE_CHILLS,
        SYMPTOM_TYPE_CONSTIPATION,
        SYMPTOM_TYPE_COUGH,
        SYMPTOM_TYPE_CRAMPS,
        SYMPTOM_TYPE_CRAVINGS,
        SYMPTOM_TYPE_DEHYDRATION,
        SYMPTOM_TYPE_DIARRHEA,
        SYMPTOM_TYPE_DIFFICULTY_SWALLOWING,
        SYMPTOM_TYPE_DIZZINESS,
        SYMPTOM_TYPE_DRY_SKIN,
        SYMPTOM_TYPE_EARACHES,
        SYMPTOM_TYPE_FATIGUE,
        SYMPTOM_TYPE_FEVER,
        SYMPTOM_TYPE_GENERALIZED_BODY_ACHE,
        SYMPTOM_TYPE_HAIR_LOSS,
        SYMPTOM_TYPE_HEADACHE,
        SYMPTOM_TYPE_HEARTBURN,
        SYMPTOM_TYPE_HEART_PALPITATIONS,
        SYMPTOM_TYPE_HOT_FLASHES,
        SYMPTOM_TYPE_INSOMNIA,
        SYMPTOM_TYPE_JOINT_PAIN,
        SYMPTOM_TYPE_JOINT_STIFFNESS,
        SYMPTOM_TYPE_LOSS_OF_APPETITE,
        SYMPTOM_TYPE_LOSS_OF_CONSCIOUSNESS,
        SYMPTOM_TYPE_LOWER_BACK_PAIN,
        SYMPTOM_TYPE_MEMORY_LAPSE,
        SYMPTOM_TYPE_MOOD_CHANGE,
        SYMPTOM_TYPE_MUSCLE_PAIN,
        SYMPTOM_TYPE_NAUSEA,
        SYMPTOM_TYPE_NIGHT_SWEATS,
        SYMPTOM_TYPE_PELVIC_PAIN,
        SYMPTOM_TYPE_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT,
        SYMPTOM_TYPE_REDUCED_CAPACITY_FOR_EXERCISE,
        SYMPTOM_TYPE_RUNNY_NOSE,
        SYMPTOM_TYPE_SHORTNESS_OF_BREATH,
        SYMPTOM_TYPE_SKIPPED_HEARTBEAT,
        SYMPTOM_TYPE_SLEEPINESS,
        SYMPTOM_TYPE_SLEEP_CHANGES,
        SYMPTOM_TYPE_SNEEZING,
        SYMPTOM_TYPE_SNORE,
        SYMPTOM_TYPE_SORE_THROAT,
        SYMPTOM_TYPE_STOMACH_ACHE,
        SYMPTOM_TYPE_STUFFY_NOSE,
        SYMPTOM_TYPE_UNEXPLAINED_WEIGHT_CHANGES,
        SYMPTOM_TYPE_UNKNOWN,
        SYMPTOM_TYPE_VAGINAL_DRYNESS,
        SYMPTOM_TYPE_VAGINAL_ITCHINESS,
        SYMPTOM_TYPE_VOMITING,
        SYMPTOM_TYPE_WATER_RETENTION,
        SYMPTOM_TYPE_WHEEZING,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SymptomType {}

    /** Unknown symptom type. */
    public static final int SYMPTOM_TYPE_UNKNOWN = 0;

    /** Abdominal pain symptom. */
    public static final int SYMPTOM_TYPE_ABDOMINAL_PAIN = 1;

    /** Acne symptom. */
    public static final int SYMPTOM_TYPE_ACNE = 2;

    /** Back pain symptom. */
    public static final int SYMPTOM_TYPE_BACK_PAIN = 3;

    /** Bloating symptom. */
    public static final int SYMPTOM_TYPE_BLOATING = 4;

    /** Brain fog symptom. */
    public static final int SYMPTOM_TYPE_BRAIN_FOG = 5;

    /** Breast tenderness symptom. */
    public static final int SYMPTOM_TYPE_BREAST_TENDERNESS = 6;

    /** Brittle nails symptom. */
    public static final int SYMPTOM_TYPE_BRITTLE_NAILS = 7;

    /** Burning mouth symptom. */
    public static final int SYMPTOM_TYPE_BURNING_MOUTH = 8;

    /** Chest pain symptom. */
    public static final int SYMPTOM_TYPE_CHEST_PAIN = 9;

    /** Chest tightness symptom. */
    public static final int SYMPTOM_TYPE_CHEST_TIGHTNESS = 10;

    /** Chills symptom. */
    public static final int SYMPTOM_TYPE_CHILLS = 11;

    /** Constipation symptom. */
    public static final int SYMPTOM_TYPE_CONSTIPATION = 12;

    /** Cough symptom. */
    public static final int SYMPTOM_TYPE_COUGH = 13;

    /** Cramps symptom. */
    public static final int SYMPTOM_TYPE_CRAMPS = 14;

    /** Cravings symptom. */
    public static final int SYMPTOM_TYPE_CRAVINGS = 15;

    /** Dehydration symptom. */
    public static final int SYMPTOM_TYPE_DEHYDRATION = 16;

    /** Diarrhea symptom. */
    public static final int SYMPTOM_TYPE_DIARRHEA = 17;

    /** Difficulty swallowing symptom. */
    public static final int SYMPTOM_TYPE_DIFFICULTY_SWALLOWING = 18;

    /** Dizziness symptom. */
    public static final int SYMPTOM_TYPE_DIZZINESS = 19;

    /** Dry skin symptom. */
    public static final int SYMPTOM_TYPE_DRY_SKIN = 20;

    /** Earaches symptom. */
    public static final int SYMPTOM_TYPE_EARACHES = 21;

    /** Fatigue symptom. */
    public static final int SYMPTOM_TYPE_FATIGUE = 22;

    /** Fever symptom. */
    public static final int SYMPTOM_TYPE_FEVER = 23;

    /** Generalized body ache symptom. */
    public static final int SYMPTOM_TYPE_GENERALIZED_BODY_ACHE = 24;

    /** Hair loss symptom. */
    public static final int SYMPTOM_TYPE_HAIR_LOSS = 25;

    /** Headache symptom. */
    public static final int SYMPTOM_TYPE_HEADACHE = 26;

    /** Heartburn symptom. */
    public static final int SYMPTOM_TYPE_HEARTBURN = 27;

    /** Heart palpitations symptom. */
    public static final int SYMPTOM_TYPE_HEART_PALPITATIONS = 28;

    /** Hot flashes symptom. */
    public static final int SYMPTOM_TYPE_HOT_FLASHES = 29;

    /** Insomnia symptom. */
    public static final int SYMPTOM_TYPE_INSOMNIA = 30;

    /** Joint pain symptom. */
    public static final int SYMPTOM_TYPE_JOINT_PAIN = 31;

    /** Joint stiffness symptom. */
    public static final int SYMPTOM_TYPE_JOINT_STIFFNESS = 32;

    /** Loss of appetite symptom. */
    public static final int SYMPTOM_TYPE_LOSS_OF_APPETITE = 33;

    /** Loss of consciousness symptom. */
    public static final int SYMPTOM_TYPE_LOSS_OF_CONSCIOUSNESS = 34;

    /** Lower back pain symptom. */
    public static final int SYMPTOM_TYPE_LOWER_BACK_PAIN = 35;

    /** Memory lapse symptom. */
    public static final int SYMPTOM_TYPE_MEMORY_LAPSE = 36;

    /** Mood change symptom. */
    public static final int SYMPTOM_TYPE_MOOD_CHANGE = 37;

    /** Muscle pain symptom. */
    public static final int SYMPTOM_TYPE_MUSCLE_PAIN = 38;

    /** Nausea symptom. */
    public static final int SYMPTOM_TYPE_NAUSEA = 39;

    /** Night sweats symptom. */
    public static final int SYMPTOM_TYPE_NIGHT_SWEATS = 40;

    /** Pelvic pain symptom. */
    public static final int SYMPTOM_TYPE_PELVIC_PAIN = 41;

    /** Rapid pounding or fluttering heartbeat symptom. */
    public static final int SYMPTOM_TYPE_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT = 42;

    /** Reduced capacity for exercise symptom. */
    public static final int SYMPTOM_TYPE_REDUCED_CAPACITY_FOR_EXERCISE = 43;

    /** Runny nose symptom. */
    public static final int SYMPTOM_TYPE_RUNNY_NOSE = 44;

    /** Shortness of breath symptom. */
    public static final int SYMPTOM_TYPE_SHORTNESS_OF_BREATH = 45;

    /** Skipped heartbeat symptom. */
    public static final int SYMPTOM_TYPE_SKIPPED_HEARTBEAT = 46;

    /** Sleepiness symptom. */
    public static final int SYMPTOM_TYPE_SLEEPINESS = 47;

    /** Sleep changes symptom. */
    public static final int SYMPTOM_TYPE_SLEEP_CHANGES = 48;

    /** Sneezing symptom. */
    public static final int SYMPTOM_TYPE_SNEEZING = 49;

    /** Snore symptom. */
    public static final int SYMPTOM_TYPE_SNORE = 50;

    /** Sore throat symptom. */
    public static final int SYMPTOM_TYPE_SORE_THROAT = 51;

    /** Stomach ache symptom. */
    public static final int SYMPTOM_TYPE_STOMACH_ACHE = 52;

    /** Stuffy nose symptom. */
    public static final int SYMPTOM_TYPE_STUFFY_NOSE = 53;

    /** Unexplained weight changes symptom. */
    public static final int SYMPTOM_TYPE_UNEXPLAINED_WEIGHT_CHANGES = 54;

    /** Vaginal dryness symptom. */
    public static final int SYMPTOM_TYPE_VAGINAL_DRYNESS = 55;

    /** Vaginal itchiness symptom. */
    public static final int SYMPTOM_TYPE_VAGINAL_ITCHINESS = 56;

    /** Vomiting symptom. */
    public static final int SYMPTOM_TYPE_VOMITING = 57;

    /** Water retention symptom. */
    public static final int SYMPTOM_TYPE_WATER_RETENTION = 58;

    /** Wheezing symptom. */
    public static final int SYMPTOM_TYPE_WHEEZING = 59;

    /**
     * Valid set of values for {@link SymptomType}. Update this set when adding a new type or
     * deprecating an existing type.
     *
     * @hide
     */
    public static final Set<Integer> VALID_SYMPTOM_TYPES =
            Set.of(
                    SYMPTOM_TYPE_ABDOMINAL_PAIN,
                    SYMPTOM_TYPE_ACNE,
                    SYMPTOM_TYPE_BACK_PAIN,
                    SYMPTOM_TYPE_BLOATING,
                    SYMPTOM_TYPE_BRAIN_FOG,
                    SYMPTOM_TYPE_BREAST_TENDERNESS,
                    SYMPTOM_TYPE_BRITTLE_NAILS,
                    SYMPTOM_TYPE_BURNING_MOUTH,
                    SYMPTOM_TYPE_CHEST_PAIN,
                    SYMPTOM_TYPE_CHEST_TIGHTNESS,
                    SYMPTOM_TYPE_CHILLS,
                    SYMPTOM_TYPE_CONSTIPATION,
                    SYMPTOM_TYPE_COUGH,
                    SYMPTOM_TYPE_CRAMPS,
                    SYMPTOM_TYPE_CRAVINGS,
                    SYMPTOM_TYPE_DEHYDRATION,
                    SYMPTOM_TYPE_DIARRHEA,
                    SYMPTOM_TYPE_DIFFICULTY_SWALLOWING,
                    SYMPTOM_TYPE_DIZZINESS,
                    SYMPTOM_TYPE_DRY_SKIN,
                    SYMPTOM_TYPE_EARACHES,
                    SYMPTOM_TYPE_FATIGUE,
                    SYMPTOM_TYPE_FEVER,
                    SYMPTOM_TYPE_GENERALIZED_BODY_ACHE,
                    SYMPTOM_TYPE_HAIR_LOSS,
                    SYMPTOM_TYPE_HEADACHE,
                    SYMPTOM_TYPE_HEARTBURN,
                    SYMPTOM_TYPE_HEART_PALPITATIONS,
                    SYMPTOM_TYPE_HOT_FLASHES,
                    SYMPTOM_TYPE_INSOMNIA,
                    SYMPTOM_TYPE_JOINT_PAIN,
                    SYMPTOM_TYPE_JOINT_STIFFNESS,
                    SYMPTOM_TYPE_LOSS_OF_APPETITE,
                    SYMPTOM_TYPE_LOSS_OF_CONSCIOUSNESS,
                    SYMPTOM_TYPE_LOWER_BACK_PAIN,
                    SYMPTOM_TYPE_MEMORY_LAPSE,
                    SYMPTOM_TYPE_MOOD_CHANGE,
                    SYMPTOM_TYPE_MUSCLE_PAIN,
                    SYMPTOM_TYPE_NAUSEA,
                    SYMPTOM_TYPE_NIGHT_SWEATS,
                    SYMPTOM_TYPE_PELVIC_PAIN,
                    SYMPTOM_TYPE_RAPID_POUNDING_OR_FLUTTERING_HEARTBEAT,
                    SYMPTOM_TYPE_REDUCED_CAPACITY_FOR_EXERCISE,
                    SYMPTOM_TYPE_RUNNY_NOSE,
                    SYMPTOM_TYPE_SHORTNESS_OF_BREATH,
                    SYMPTOM_TYPE_SKIPPED_HEARTBEAT,
                    SYMPTOM_TYPE_SLEEPINESS,
                    SYMPTOM_TYPE_SLEEP_CHANGES,
                    SYMPTOM_TYPE_SNEEZING,
                    SYMPTOM_TYPE_SNORE,
                    SYMPTOM_TYPE_SORE_THROAT,
                    SYMPTOM_TYPE_STOMACH_ACHE,
                    SYMPTOM_TYPE_STUFFY_NOSE,
                    SYMPTOM_TYPE_UNEXPLAINED_WEIGHT_CHANGES,
                    SYMPTOM_TYPE_VAGINAL_DRYNESS,
                    SYMPTOM_TYPE_VAGINAL_ITCHINESS,
                    SYMPTOM_TYPE_VOMITING,
                    SYMPTOM_TYPE_WATER_RETENTION,
                    SYMPTOM_TYPE_WHEEZING);

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({SEVERITY_UNSPECIFIED, SEVERITY_MILD, SEVERITY_MODERATE, SEVERITY_SEVERE})
    public @interface SymptomSeverity {}

    /** Unspecified severity. */
    public static final int SEVERITY_UNSPECIFIED = 0;

    /** Mild severity. */
    public static final int SEVERITY_MILD = 1;

    /** Moderate severity. */
    public static final int SEVERITY_MODERATE = 2;

    /** Severe severity. */
    public static final int SEVERITY_SEVERE = 3;

    /**
     * @hide
     * @deprecated use {@link Record.RecordTemporalType} instead
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        RECORD_TEMPORAL_TYPE_INSTANT,
        RECORD_TEMPORAL_TYPE_INTERVAL,
        RECORD_TEMPORAL_TYPE_LOCAL_DATE
    })
    @Deprecated
    public @interface SymptomRecordTemporalType {}

    /**
     * The record represents an instantaneous event.
     *
     * @deprecated use {@link Record#RECORD_TEMPORAL_TYPE_INSTANT} instead
     */
    @FlaggedApi(FLAG_TEMPORAL_FIELD_API)
    @Deprecated
    public static final int RECORD_TEMPORAL_TYPE_INSTANT = 0;

    /**
     * The record represents an event over an interval.
     *
     * @deprecated use {@link Record#RECORD_TEMPORAL_TYPE_INTERVAL} instead
     */
    @FlaggedApi(FLAG_TEMPORAL_FIELD_API)
    @Deprecated
    public static final int RECORD_TEMPORAL_TYPE_INTERVAL = 1;

    /**
     * The record represents an event that occurred on a specific date.
     *
     * @deprecated use {@link Record#RECORD_TEMPORAL_TYPE_LOCAL_DATE} instead
     */
    @FlaggedApi(FLAG_TEMPORAL_FIELD_API)
    @Deprecated
    public static final int RECORD_TEMPORAL_TYPE_LOCAL_DATE = 2;

    /** Builder class for {@link SymptomRecord}. */
    public static final class Builder {
        @SymptomType private final int mSymptomType;
        @NonNull private final Metadata mMetadata;
        @NonNull private final Instant mStartTime;
        @NonNull private final Instant mEndTime;
        @Nullable private String mNotes;
        @NonNull private ZoneOffset mStartZoneOffset;
        @NonNull private ZoneOffset mEndZoneOffset;
        @SymptomSeverity private int mSeverity = SEVERITY_UNSPECIFIED;
        private int mCount = 1;
        @SymptomRecordTemporalType private final int mTemporalType;

        /**
         * Builder for a symptom that occurs at a specific instant time. The temporal type of the
         * record will be set to {@link #RECORD_TEMPORAL_TYPE_INSTANT}.
         *
         * @param symptomType The type of symptom.
         * @param time The time of the record.
         * @param metadata The metadata of the record.
         */
        public Builder(
                @SymptomType int symptomType, @NonNull Instant time, @NonNull Metadata metadata) {
            this.mSymptomType = symptomType;
            this.mStartTime = Objects.requireNonNull(time, "Time cannot be null");
            this.mEndTime = Objects.requireNonNull(time, "Time cannot be null");
            this.mMetadata = Objects.requireNonNull(metadata, "Metadata cannot be null");
            this.mStartZoneOffset = ZoneOffset.systemDefault().getRules().getOffset(time);
            this.mEndZoneOffset = ZoneOffset.systemDefault().getRules().getOffset(time);
            this.mTemporalType = RECORD_TEMPORAL_TYPE_INSTANT;
        }

        /**
         * Builder for a symptom that occurs over a time interval. The temporal type of the record
         * will be set to {@link #RECORD_TEMPORAL_TYPE_INTERVAL}.
         *
         * @param symptomType The type of symptom.
         * @param startTime The start time of the record.
         * @param endTime The end time of the record.
         * @param metadata The metadata of the record.
         */
        public Builder(
                @SymptomType int symptomType,
                @NonNull Instant startTime,
                @NonNull Instant endTime,
                @NonNull Metadata metadata) {
            if (!startTime.isBefore(endTime)) {
                throw new IllegalArgumentException("startTime must be before endTime");
            }
            this.mSymptomType = symptomType;
            this.mStartTime = Objects.requireNonNull(startTime, "StartTime cannot be null");
            this.mEndTime = Objects.requireNonNull(endTime, "EndTime cannot be null");
            this.mMetadata = Objects.requireNonNull(metadata, "Metadata cannot be null");
            this.mStartZoneOffset = ZoneOffset.systemDefault().getRules().getOffset(startTime);
            this.mEndZoneOffset = ZoneOffset.systemDefault().getRules().getOffset(endTime);
            this.mTemporalType = RECORD_TEMPORAL_TYPE_INTERVAL;
        }

        /**
         * Builder for a symptom that falls on a specific local date. The temporal type of the
         * record will be set to {@link #RECORD_TEMPORAL_TYPE_LOCAL_DATE}.
         *
         * @param symptomType The type of symptom.
         * @param date The date of the record.
         * @param metadata The metadata of the record.
         */
        public Builder(
                @SymptomType int symptomType, @NonNull LocalDate date, @NonNull Metadata metadata) {
            Objects.requireNonNull(date, "Date cannot be null");
            this.mSymptomType = symptomType;
            this.mMetadata = Objects.requireNonNull(metadata, "Metadata cannot be null");
            this.mStartZoneOffset =
                    ZoneId.systemDefault().getRules().getOffset(date.atStartOfDay());
            this.mEndZoneOffset =
                    ZoneId.systemDefault().getRules().getOffset(LocalTime.MAX.atDate(date));
            this.mStartTime = date.atStartOfDay().toInstant(mStartZoneOffset);
            this.mEndTime = LocalTime.MAX.atDate(date).toInstant(mEndZoneOffset);
            this.mTemporalType = RECORD_TEMPORAL_TYPE_LOCAL_DATE;
        }

        /**
         * Sets the notes for this record.
         *
         * @param notes A description of the symptom.
         * @return This builder.
         */
        @NonNull
        public Builder setNotes(@Nullable String notes) {
            this.mNotes = notes;
            return this;
        }

        /**
         * Sets the severity of the symptom for this record.
         *
         * @param severity The severity of the symptom.
         * @return This builder.
         */
        @NonNull
        public Builder setSeverity(@SymptomSeverity int severity) {
            this.mSeverity = severity;
            return this;
        }

        /**
         * Sets the number of occurrences of the symptom for this record.
         *
         * @param count The number of occurrences of the symptom.
         * @return This builder.
         * @throws IllegalStateException if the builder is for an instant record.
         */
        @NonNull
        public Builder setCount(int count) {
            if (mTemporalType == RECORD_TEMPORAL_TYPE_INSTANT) {
                throw new IllegalStateException(
                        "Count is not supported for instant symptom records.");
            }
            this.mCount = count;
            return this;
        }

        /**
         * Sets the start zone offset of this record.
         *
         * @param startZoneOffset The start zone offset of the record.
         * @return This builder.
         * @throws IllegalStateException if the builder is for a local date record.
         */
        @NonNull
        public Builder setStartZoneOffset(@NonNull ZoneOffset startZoneOffset) {
            if (mTemporalType == RECORD_TEMPORAL_TYPE_LOCAL_DATE) {
                throw new IllegalStateException(
                        "Zone offset is not supported for local date symptom records.");
            }
            this.mStartZoneOffset = startZoneOffset;
            return this;
        }

        /**
         * Sets the end zone offset of this record.
         *
         * @param endZoneOffset The end zone offset of the record.
         * @return This builder.
         * @throws IllegalStateException if the builder is for a local date record.
         */
        @NonNull
        public Builder setEndZoneOffset(@NonNull ZoneOffset endZoneOffset) {
            if (mTemporalType == RECORD_TEMPORAL_TYPE_LOCAL_DATE) {
                throw new IllegalStateException(
                        "Zone offset is not supported for local date symptom records.");
            }
            this.mEndZoneOffset = endZoneOffset;
            return this;
        }

        /**
         * @return A {@link SymptomRecord} with the specified parameters.
         */
        @NonNull
        public SymptomRecord build() {
            return build(false);
        }

        /**
         * @return Object of {@link SymptomRecord} without validating the values.
         * @hide
         */
        @NonNull
        public SymptomRecord buildWithoutValidation() {
            return build(true);
        }

        @NonNull
        private SymptomRecord build(boolean skipValidation) {
            return new SymptomRecord(
                    mSymptomType,
                    mNotes,
                    mSeverity,
                    mCount,
                    mTemporalType,
                    mStartTime,
                    mStartZoneOffset,
                    mEndTime,
                    mEndZoneOffset,
                    mMetadata,
                    skipValidation);
        }
    }
}
