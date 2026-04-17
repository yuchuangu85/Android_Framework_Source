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

import static android.health.connect.datatypes.validation.ValidationUtils.requireInRange;
import static android.health.connect.datatypes.validation.ValidationUtils.requirePositive;
import static android.health.connect.datatypes.validation.ValidationUtils.validateIntDefValue;

import static com.android.healthfitness.flags.Flags.FLAG_ALCOHOL_CONSUMPTION;
import static com.android.healthfitness.flags.Flags.FLAG_TEMPORAL_FIELD_API;

import static java.util.Objects.requireNonNull;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.health.connect.datatypes.units.Percentage;
import android.health.connect.datatypes.units.Volume;
import android.health.connect.internal.datatypes.AlcoholConsumptionRecordInternal;
import android.text.TextUtils;

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
 * Captures a user's alcohol consumption. Each record represents one or more servings of an
 * alcoholic beverage, consumed either at an instant, over an interval of time, or on a specific
 * day.
 */
@FlaggedApi(FLAG_ALCOHOL_CONSUMPTION)
@Identifier(recordIdentifier = RecordTypeIdentifier.RECORD_TYPE_ALCOHOL_CONSUMPTION)
public class AlcoholConsumptionRecord extends IntervalRecord {

    /** Use this type for other beverage types. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_OTHER = 0;

    /** Use this type for beer. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_BEER = 1;

    /** Use this type for wine. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_WINE = 2;

    /** Use this type for vodka. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_VODKA = 3;

    /** Use this type for gin. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_GIN = 4;

    /** Use this type for whiskey. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_WHISKEY = 5;

    /** Use this type for rum. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_RUM = 6;

    /** Use this type for tequila. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_TEQUILA = 7;

    /** Use this type for lager. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_LAGER = 8;

    /** Use this type for cider. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_CIDER = 9;

    /** Use this type for sake. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_SAKE = 10;

    /** Use this type for shochu. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_SHOCHU = 11;

    /** Use this type for soju. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_SOJU = 12;

    /** Use this type for mead. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_MEAD = 13;

    /** Use this type for absinthe. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_ABSINTHE = 14;

    /** Use this type for brandy. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_BRANDY = 15;

    /** Use this type for cocktail. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_COCKTAIL = 16;

    /** Use this type for chuhai. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_CHUHAI = 17;

    /** Use this type for highball. */
    public static final int ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_HIGHBALL = 18;

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
    public @interface AlcoholConsumptionTemporalType {}

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

    private static final Set<Integer> VALID_ALCOHOL_CONSUMPTION_BEVERAGE_TYPES =
            Set.of(
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_OTHER,
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_BEER,
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_WINE,
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_VODKA,
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_GIN,
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_WHISKEY,
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_RUM,
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_TEQUILA,
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_LAGER,
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_CIDER,
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_SAKE,
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_SHOCHU,
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_SOJU,
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_MEAD,
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_ABSINTHE,
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_BRANDY,
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_COCKTAIL,
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_CHUHAI,
                    ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_HIGHBALL);
    @AlcoholConsumptionTemporalType private final int mTemporalType;
    private final int mBeverageType;
    @Nullable private final Volume mServingVolume;
    @Nullable private final Percentage mAlcoholByVolume;
    @Nullable private final CharSequence mNotes;

    private AlcoholConsumptionRecord(
            @NonNull Metadata metadata,
            @NonNull Instant startTime,
            @NonNull ZoneOffset startZoneOffset,
            @NonNull Instant endTime,
            @NonNull ZoneOffset endZoneOffset,
            @AlcoholConsumptionTemporalType int temporalType,
            @AlcoholConsumptionBeverageType int beverageType,
            @Nullable Volume servingVolume,
            @Nullable Percentage alcoholByVolume,
            @Nullable CharSequence notes,
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

            if (servingVolume != null) {
                requirePositive(servingVolume.getInLiters(), "servingVolume");
                requireInRange(servingVolume.getInLiters(), 0, 255, "servingVolume");
            }
            if (alcoholByVolume != null) {
                requireInRange(alcoholByVolume.getValue(), 0.0, 100.0, "alcoholByVolume");
            }
            validateIntDefValue(
                    beverageType,
                    VALID_ALCOHOL_CONSUMPTION_BEVERAGE_TYPES,
                    AlcoholConsumptionBeverageType.class.getSimpleName());
        }
        mTemporalType = temporalType;
        mBeverageType = beverageType;
        mServingVolume = servingVolume;
        mAlcoholByVolume = alcoholByVolume;
        mNotes = notes;
    }

    /**
     * Returns the temporal type of this record, indicating whether it represents an instant, an
     * interval, or a local date.
     *
     * @return The temporal type, as one of {@link #RECORD_TEMPORAL_TYPE_INSTANT}, {@link
     *     #RECORD_TEMPORAL_TYPE_INTERVAL}, or {@link #RECORD_TEMPORAL_TYPE_LOCAL_DATE}.
     */
    @AlcoholConsumptionTemporalType
    public int getTemporalType() {
        return mTemporalType;
    }

    /** Returns the date of the record, or {@code null} if the record is not a local date record. */
    @Nullable
    public LocalDate getDate() {
        if (getTemporalType() == RECORD_TEMPORAL_TYPE_LOCAL_DATE) {
            return getStartTime().atOffset(getStartZoneOffset()).toLocalDate();
        }
        return null;
    }

    /** Returns the type of beverage the user consumed. */
    @AlcoholConsumptionBeverageType
    public int getBeverageType() {
        return mBeverageType;
    }

    /**
     * Returns the volume of each serving consumed. Returns {@code null} if no serving volume was
     * specified.
     */
    @Nullable
    public Volume getServingVolume() {
        return mServingVolume;
    }

    /**
     * Returns the alcohol by volume of the beverage or {@code null} if alcohol by volume is null.
     */
    @Nullable
    public Percentage getAlcoholByVolume() {
        return mAlcoholByVolume;
    }

    /** Returns the notes for this record. Returns {@code null} if no notes were specified. */
    @Nullable
    public CharSequence getNotes() {
        return mNotes;
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
        if (!(o instanceof AlcoholConsumptionRecord that)) return false;
        if (!super.equals(o)) return false;
        return mTemporalType == that.mTemporalType
                && mBeverageType == that.mBeverageType
                && Objects.equals(mServingVolume, that.mServingVolume)
                && Objects.equals(mAlcoholByVolume, that.mAlcoholByVolume)
                && TextUtils.equals(mNotes, that.mNotes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                super.hashCode(),
                mTemporalType,
                mBeverageType,
                mServingVolume,
                mAlcoholByVolume,
                mNotes);
    }

    /** @hide */
    @IntDef({
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_OTHER,
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_BEER,
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_WINE,
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_VODKA,
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_GIN,
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_WHISKEY,
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_RUM,
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_TEQUILA,
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_LAGER,
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_CIDER,
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_SAKE,
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_SHOCHU,
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_SOJU,
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_MEAD,
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_ABSINTHE,
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_BRANDY,
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_COCKTAIL,
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_CHUHAI,
        ALCOHOL_CONSUMPTION_BEVERAGE_TYPE_HIGHBALL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AlcoholConsumptionBeverageType {}

    /** Builder class for {@link AlcoholConsumptionRecord} */
    public static final class Builder {
        private final Metadata mMetadata;
        private final Instant mStartTime;
        private final Instant mEndTime;
        private ZoneOffset mStartZoneOffset;
        private ZoneOffset mEndZoneOffset;
        @AlcoholConsumptionTemporalType private final int mTemporalType;
        private int mBeverageType;
        @Nullable private Volume mServingVolume;
        @Nullable private Percentage mAlcoholByVolume;
        @Nullable private CharSequence mNotes;

        /**
         * Builder for an alcohol consumption record that occurs at a specific instant in time.
         *
         * @param metadata The metadata of the record.
         * @param time The time of the record.
         * @param beverageType The type of beverage consumed. See {@link
         *     AlcoholConsumptionBeverageType} for valid values.
         */
        public Builder(
                @NonNull Metadata metadata,
                @NonNull Instant time,
                @AlcoholConsumptionBeverageType int beverageType) {
            mMetadata = metadata;
            mStartTime = time;
            mEndTime = time;
            mStartZoneOffset = ZoneOffset.systemDefault().getRules().getOffset(time);
            mEndZoneOffset = ZoneOffset.systemDefault().getRules().getOffset(time);
            mTemporalType = RECORD_TEMPORAL_TYPE_INSTANT;
            mBeverageType = beverageType;
        }

        /**
         * Builder for an alcohol consumption record that occurs over a time interval.
         *
         * @param metadata The metadata of the record.
         * @param startTime The start time of the record.
         * @param endTime The end time of the record.
         * @param beverageType The type of beverage consumed. See {@link
         *     AlcoholConsumptionBeverageType} for valid values.
         */
        public Builder(
                @NonNull Metadata metadata,
                @NonNull Instant startTime,
                @NonNull Instant endTime,
                @AlcoholConsumptionBeverageType int beverageType) {
            mMetadata = metadata;
            mStartTime = startTime;
            mEndTime = endTime;
            mStartZoneOffset = ZoneOffset.systemDefault().getRules().getOffset(startTime);
            mEndZoneOffset = ZoneOffset.systemDefault().getRules().getOffset(endTime);
            mTemporalType = RECORD_TEMPORAL_TYPE_INTERVAL;
            mBeverageType = beverageType;
        }

        /**
         * Builder for an alcohol consumption record that occurs on a specific date.
         *
         * @param metadata The metadata of the record.
         * @param date The date of the record.
         * @param beverageType The type of beverage consumed. See {@link
         *     AlcoholConsumptionBeverageType} for valid values.
         */
        public Builder(
                @NonNull Metadata metadata,
                @NonNull LocalDate date,
                @AlcoholConsumptionBeverageType int beverageType) {
            mMetadata = metadata;
            mStartZoneOffset = ZoneId.systemDefault().getRules().getOffset(date.atStartOfDay());
            mEndZoneOffset =
                    ZoneId.systemDefault().getRules().getOffset(LocalTime.MAX.atDate(date));
            mStartTime = date.atStartOfDay().toInstant(mStartZoneOffset);
            mEndTime = LocalTime.MAX.atDate(date).toInstant(mEndZoneOffset);
            mTemporalType = RECORD_TEMPORAL_TYPE_LOCAL_DATE;
            mBeverageType = beverageType;
        }

        /** Sets the zone offset of the user when the activity was logged. */
        @NonNull
        public Builder setStartZoneOffset(@NonNull ZoneOffset startZoneOffset) {
            requireNonNull(startZoneOffset);
            mStartZoneOffset = startZoneOffset;
            return this;
        }

        /** Sets the zone offset of the user when the activity was logged. */
        @NonNull
        public Builder setEndZoneOffset(@NonNull ZoneOffset endZoneOffset) {
            requireNonNull(endZoneOffset);
            mEndZoneOffset = endZoneOffset;
            return this;
        }

        /** Clears the zone offset of the user when the activity was logged. */
        @NonNull
        public Builder clearStartZoneOffset() {
            mStartZoneOffset = RecordUtils.getDefaultZoneOffset();
            return this;
        }

        /** Clears the zone offset of the user when the activity was logged. */
        @NonNull
        public Builder clearEndZoneOffset() {
            mEndZoneOffset = RecordUtils.getDefaultZoneOffset();
            return this;
        }

        /**
         * Sets the type of beverage the user consumed.
         *
         * @param beverageType The type of beverage consumed. See {@link
         *     AlcoholConsumptionBeverageType} for valid values.
         */
        @NonNull
        public Builder setBeverageType(@AlcoholConsumptionBeverageType int beverageType) {
            mBeverageType = beverageType;
            return this;
        }

        /**
         * Sets the volume of a single serving consumed.
         *
         * @param servingVolume The volume of a single serving. If provided, must be positive and
         *     less than or equal to 255 liters.
         */
        @NonNull
        public Builder setServingVolume(@Nullable Volume servingVolume) {
            mServingVolume = servingVolume;
            return this;
        }

        /**
         * Sets the alcohol by volume of the beverage.
         *
         * @param alcoholByVolume The alcohol by volume. If provided, must be in the range 0.0 to
         *     100.0.
         */
        @NonNull
        public Builder setAlcoholByVolume(@Nullable Percentage alcoholByVolume) {
            mAlcoholByVolume = alcoholByVolume;
            return this;
        }

        /** Sets the notes for this record. */
        @NonNull
        public Builder setNotes(@Nullable CharSequence notes) {
            mNotes = notes;
            return this;
        }

        /**
         * @return Object of {@link AlcoholConsumptionRecord} without validating the values.
         * @hide
         */
        @NonNull
        public AlcoholConsumptionRecord buildWithoutValidation() {
            return new AlcoholConsumptionRecord(
                    mMetadata,
                    mStartTime,
                    mStartZoneOffset,
                    mEndTime,
                    mEndZoneOffset,
                    mTemporalType,
                    mBeverageType,
                    mServingVolume,
                    mAlcoholByVolume,
                    mNotes,
                    true);
        }

        /**
         * @return Object of {@link AlcoholConsumptionRecord}
         */
        @NonNull
        public AlcoholConsumptionRecord build() {
            return new AlcoholConsumptionRecord(
                    mMetadata,
                    mStartTime,
                    mStartZoneOffset,
                    mEndTime,
                    mEndZoneOffset,
                    mTemporalType,
                    mBeverageType,
                    mServingVolume,
                    mAlcoholByVolume,
                    mNotes,
                    false);
        }
    }

    /** @hide */
    @Override
    public AlcoholConsumptionRecordInternal toRecordInternal() {
        AlcoholConsumptionRecordInternal recordInternal =
                (AlcoholConsumptionRecordInternal)
                        new AlcoholConsumptionRecordInternal().setMetaData(getMetadata());

        recordInternal.setTimeInterval(this);
        recordInternal.setTemporalType(mTemporalType);
        recordInternal.setBeverageType(mBeverageType);
        if (mServingVolume != null) {
            recordInternal.setServingVolumeLiters(mServingVolume.getInLiters());
        }
        if (mAlcoholByVolume != null) {
            recordInternal.setAlcoholByVolume(mAlcoholByVolume.getValue());
        }
        if (mNotes != null) {
            recordInternal.setNotes(mNotes);
        }
        return recordInternal;
    }
}
