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

import static com.android.healthfitness.flags.Flags.FLAG_SMOKING;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.health.connect.datatypes.Identifier;
import android.health.connect.datatypes.NicotineIntakeRecord;
import android.health.connect.datatypes.RecordTypeIdentifier;
import android.health.connect.datatypes.units.Mass;
import android.os.Parcel;

/**
 * @see android.health.connect.datatypes.NicotineIntakeRecord
 * @hide
 */
@FlaggedApi(FLAG_SMOKING)
@Identifier(recordIdentifier = RecordTypeIdentifier.RECORD_TYPE_NICOTINE_INTAKE)
public class NicotineIntakeRecordInternal extends IntervalRecordInternal<NicotineIntakeRecord> {

    private double mNicotineIntakeGrams = DEFAULT_DOUBLE;
    private int mQuantity;
    private int mNicotineIntakeType;

    public NicotineIntakeRecordInternal() {
        super();
    }

    public NicotineIntakeRecordInternal(Parcel parcel) {
        super(parcel);
        mNicotineIntakeGrams = parcel.readDouble();
        mQuantity = parcel.readInt();
        mNicotineIntakeType = parcel.readInt();
    }

    @Override
    void populateIntervalRecordTo(@NonNull Parcel parcel) {
        parcel.writeDouble(mNicotineIntakeGrams);
        parcel.writeInt(mQuantity);
        parcel.writeInt(mNicotineIntakeType);
    }

    @Override
    public NicotineIntakeRecord toExternalRecord() {
        NicotineIntakeRecord.Builder builder =
                new NicotineIntakeRecord.Builder(
                        buildMetaData(),
                        getStartTime(),
                        getEndTime(),
                        mQuantity,
                        mNicotineIntakeType);

        if (mNicotineIntakeGrams != DEFAULT_DOUBLE) {
            builder.setNicotineIntake(Mass.fromGrams(mNicotineIntakeGrams));
        }

        if (getStartZoneOffset() != null) {
            builder.setStartZoneOffset(getStartZoneOffset());
        }

        if (getEndZoneOffset() != null) {
            builder.setEndZoneOffset(getEndZoneOffset());
        }

        return builder.build(true);
    }

    public double getNicotineIntakeGrams() {
        return mNicotineIntakeGrams;
    }

    public int getQuantity() {
        return mQuantity;
    }

    @NicotineIntakeRecord.NicotineIntakeType
    public int getNicotineIntakeType() {
        return mNicotineIntakeType;
    }

    /** Returns this object with the specified quantity. */
    @NonNull
    public NicotineIntakeRecordInternal setQuantity(int quantity) {
        mQuantity = quantity;
        return this;
    }

    /** Returns this object with the specified nicotine intake. */
    @NonNull
    public NicotineIntakeRecordInternal setNicotineIntakeGrams(double nicotineIntakeGrams) {
        mNicotineIntakeGrams = nicotineIntakeGrams;
        return this;
    }

    /** Returns this object with the specified nicotine intake type. */
    @NonNull
    public NicotineIntakeRecordInternal setNicotineIntakeType(int nicotineIntakeType) {
        mNicotineIntakeType = nicotineIntakeType;
        return this;
    }
}
