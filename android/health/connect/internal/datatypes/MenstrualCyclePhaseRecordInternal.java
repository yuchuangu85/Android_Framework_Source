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

import static android.health.connect.Constants.DEFAULT_INT;
import static android.health.connect.datatypes.MenstrualCyclePhaseRecord.PHASE_UNKNOWN;

import static com.android.healthfitness.flags.Flags.FLAG_CYCLE_PHASES_FLAG;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.health.connect.datatypes.Identifier;
import android.health.connect.datatypes.MenstrualCyclePhaseRecord;
import android.health.connect.datatypes.RecordTypeIdentifier;
import android.os.Parcel;

/**
 * @see MenstrualCyclePhaseRecord
 * @hide
 */
@FlaggedApi(FLAG_CYCLE_PHASES_FLAG)
@Identifier(recordIdentifier = RecordTypeIdentifier.RECORD_TYPE_MENSTRUAL_CYCLE_PHASE)
public final class MenstrualCyclePhaseRecordInternal
        extends IntervalRecordInternal<MenstrualCyclePhaseRecord> {
    @MenstrualCyclePhaseRecord.CyclePhase private int mPhase = PHASE_UNKNOWN;
    private int mDayOfCycle = DEFAULT_INT;

    public MenstrualCyclePhaseRecordInternal() {
        super();
    }

    public MenstrualCyclePhaseRecordInternal(Parcel parcel) {
        super(parcel);
        mPhase = parcel.readInt();
        mDayOfCycle = parcel.readInt();
    }

    @MenstrualCyclePhaseRecord.CyclePhase
    public int getPhase() {
        return mPhase;
    }

    public int getDayOfCycle() {
        return mDayOfCycle;
    }

    /** returns this object with the specified {@code phase} */
    public MenstrualCyclePhaseRecordInternal setPhase(
            @MenstrualCyclePhaseRecord.CyclePhase int phase) {
        mPhase = phase;
        return this;
    }

    /** returns this object with the specified {@code dayOfCycle} */
    public MenstrualCyclePhaseRecordInternal setDayOfCycle(int dayOfCycle) {
        mDayOfCycle = dayOfCycle;
        return this;
    }

    @Override
    void populateIntervalRecordTo(@NonNull Parcel parcel) {
        parcel.writeInt(mPhase);
        parcel.writeInt(mDayOfCycle);
    }

    @Override
    public MenstrualCyclePhaseRecord toExternalRecord() {
        MenstrualCyclePhaseRecord.Builder builder =
                new MenstrualCyclePhaseRecord.Builder(buildMetaData(), getLocalDate(), mPhase)
                        .setStartZoneOffset(getStartZoneOffset());
        if (mDayOfCycle != DEFAULT_INT) {
            builder.setDayOfCycle(mDayOfCycle);
        }
        return builder.buildWithoutValidation();
    }
}
