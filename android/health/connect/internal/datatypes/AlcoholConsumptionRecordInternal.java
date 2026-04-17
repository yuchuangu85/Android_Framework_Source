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
package android.health.connect.internal.datatypes;

import static android.health.connect.Constants.DEFAULT_DOUBLE;
import static android.health.connect.datatypes.AlcoholConsumptionRecord.RECORD_TEMPORAL_TYPE_INSTANT;

import static com.android.healthfitness.flags.Flags.FLAG_ALCOHOL_CONSUMPTION;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.health.connect.datatypes.AlcoholConsumptionRecord;
import android.health.connect.datatypes.Identifier;
import android.health.connect.datatypes.RecordTypeIdentifier;
import android.health.connect.datatypes.units.Percentage;
import android.health.connect.datatypes.units.Volume;
import android.os.Parcel;

/**
 * @hide
 * @see android.health.connect.datatypes.AlcoholConsumptionRecord
 */
@FlaggedApi(FLAG_ALCOHOL_CONSUMPTION)
@Identifier(recordIdentifier = RecordTypeIdentifier.RECORD_TYPE_ALCOHOL_CONSUMPTION)
public class AlcoholConsumptionRecordInternal
        extends IntervalRecordInternal<AlcoholConsumptionRecord> {

    private int mTemporalType;
    private int mBeverageType;
    private double mServingVolumeLiters = DEFAULT_DOUBLE;
    private double mAlcoholByVolume = DEFAULT_DOUBLE;
    @Nullable private String mNotes;

    public AlcoholConsumptionRecordInternal() {
        super();
    }

    public AlcoholConsumptionRecordInternal(Parcel parcel) {
        super(parcel);
        mTemporalType = parcel.readInt();
        mBeverageType = parcel.readInt();
        mServingVolumeLiters = parcel.readDouble();
        mAlcoholByVolume = parcel.readDouble();
        mNotes = parcel.readString();
    }

    @Override
    void populateIntervalRecordTo(@NonNull Parcel parcel) {
        parcel.writeInt(mTemporalType);
        parcel.writeInt(mBeverageType);
        parcel.writeDouble(mServingVolumeLiters);
        parcel.writeDouble(mAlcoholByVolume);
        parcel.writeString(mNotes);
    }

    @Override
    public AlcoholConsumptionRecord toExternalRecord() {
        AlcoholConsumptionRecord.Builder builder;
        if (mTemporalType == RECORD_TEMPORAL_TYPE_INSTANT) {
            builder =
                    new AlcoholConsumptionRecord.Builder(
                            buildMetaData(), getStartTime(), mBeverageType);
        } else if (mTemporalType == AlcoholConsumptionRecord.RECORD_TEMPORAL_TYPE_INTERVAL) {
            builder =
                    new AlcoholConsumptionRecord.Builder(
                            buildMetaData(),
                            getStartTime(),
                            getEndTime(),
                            mBeverageType);
        } else {
            builder =
                    new AlcoholConsumptionRecord.Builder(
                            buildMetaData(),
                            getStartTime().atOffset(getStartZoneOffset()).toLocalDate(),
                            mBeverageType);
        }

        if (getStartZoneOffset() != null) {
            builder.setStartZoneOffset(getStartZoneOffset());
        }
        if (getEndZoneOffset() != null) {
            builder.setEndZoneOffset(getEndZoneOffset());
        }
        if (mServingVolumeLiters != DEFAULT_DOUBLE) {
            builder.setServingVolume(Volume.fromLiters(getServingVolumeLiters()));
        }
        if (mAlcoholByVolume != DEFAULT_DOUBLE) {
            builder.setAlcoholByVolume(Percentage.fromValue(getAlcoholByVolume()));
        }
        if (mNotes != null) {
            builder.setNotes(mNotes);
        }

        return builder.buildWithoutValidation();
    }

    @AlcoholConsumptionRecord.AlcoholConsumptionBeverageType
    public int getBeverageType() {
        return mBeverageType;
    }

    public double getServingVolumeLiters() {
        return mServingVolumeLiters;
    }

    public double getAlcoholByVolume() {
        return mAlcoholByVolume;
    }

    @Nullable
    public CharSequence getNotes() {
        return mNotes;
    }

    /** Returns the temporal type of this record. */
    @AlcoholConsumptionRecord.AlcoholConsumptionTemporalType
    public int getTemporalType() {
        return mTemporalType;
    }

    /** Returns this object with the specified beverage type. */
    @NonNull
    public AlcoholConsumptionRecordInternal setBeverageType(
            @AlcoholConsumptionRecord.AlcoholConsumptionBeverageType int beverageType) {
        mBeverageType = beverageType;
        return this;
    }

    /** Returns this object with the specified volume per serving. */
    @NonNull
    public AlcoholConsumptionRecordInternal setServingVolumeLiters(double servingVolume) {
        mServingVolumeLiters = servingVolume;
        return this;
    }

    /** Returns this object with the specified alcohol by volume. */
    @NonNull
    public AlcoholConsumptionRecordInternal setAlcoholByVolume(double alcoholByVolume) {
        mAlcoholByVolume = alcoholByVolume;
        return this;
    }

    /** Returns this object with the specified note. */
    @NonNull
    public AlcoholConsumptionRecordInternal setNotes(CharSequence notes) {
        mNotes = (notes != null) ? notes.toString() : null;
        return this;
    }

    /** Returns this object with the specified temporal type. */
    @NonNull
    public AlcoholConsumptionRecordInternal setTemporalType(
            @AlcoholConsumptionRecord.AlcoholConsumptionTemporalType int temporalType) {
        mTemporalType = temporalType;
        return this;
    }
}
