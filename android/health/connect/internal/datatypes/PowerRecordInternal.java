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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.health.connect.datatypes.Identifier;
import android.health.connect.datatypes.PowerRecord;
import android.health.connect.datatypes.RecordTypeIdentifier;
import android.health.connect.datatypes.units.Power;
import android.os.Parcel;

import com.android.healthfitness.flags.Flags;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * @see PowerRecord
 * @hide
 */
@Identifier(recordIdentifier = RecordTypeIdentifier.RECORD_TYPE_POWER)
public class PowerRecordInternal
        extends SeriesRecordInternal<PowerRecord, PowerRecordInternal.PowerRecordSample> {
    private Set<PowerRecordSample> mPowerRecordSamples =
            new TreeSet<>(Comparator.comparingLong(PowerRecordSample::getEpochMillis));

    public PowerRecordInternal(Set<PowerRecordSample> powerRecordSamples) {
        super();
        if (Flags.sampleTimeOrdering()) {
            mPowerRecordSamples.addAll(powerRecordSamples);
        } else {
            mPowerRecordSamples = powerRecordSamples;
        }
    }

    public PowerRecordInternal(Parcel parcel) {
        super(parcel);
        int size = parcel.readInt();
        if (!Flags.sampleTimeOrdering()) {
            mPowerRecordSamples = new HashSet<>(size);
        }
        for (int i = 0; i < size; i++) {
            mPowerRecordSamples.add(new PowerRecordSample(parcel.readDouble(), parcel.readLong()));
        }
    }

    @Override
    @NonNull
    public Set<PowerRecordSample> getSamples() {
        return mPowerRecordSamples;
    }

    @Override
    public void addSample(PowerRecordSample sample) {
        mPowerRecordSamples.add(sample);
    }

    @Override
    @NonNull
    public PowerRecord toExternalRecord() {
        return new PowerRecord.Builder(
                        buildMetaData(), getStartTime(), getEndTime(), getExternalSamples())
                .setStartZoneOffset(getStartZoneOffset())
                .setEndZoneOffset(getEndZoneOffset())
                .buildWithoutValidation();
    }

    @Override
    void populateIntervalRecordTo(@NonNull Parcel parcel) {
        parcel.writeInt(mPowerRecordSamples.size());
        for (PowerRecordSample powerRecordSample : mPowerRecordSamples) {
            parcel.writeDouble(powerRecordSample.getPower());
            parcel.writeLong(powerRecordSample.getEpochMillis());
        }
    }

    private List<PowerRecord.PowerRecordSample> getExternalSamples() {
        List<PowerRecord.PowerRecordSample> powerRecords =
                new ArrayList<>(mPowerRecordSamples.size());
        for (PowerRecordSample powerRecordSample : mPowerRecordSamples) {
            powerRecords.add(
                    new PowerRecord.PowerRecordSample(
                            Power.fromWatts(powerRecordSample.getPower()),
                            Instant.ofEpochMilli(powerRecordSample.getEpochMillis()),
                            true));
        }
        return powerRecords;
    }

    /**
     * @see PowerRecord.PowerRecordSample
     */
    public static final class PowerRecordSample implements Sample {
        private final double mPower;
        private final long mEpochMillis;

        public PowerRecordSample(double power, long epochMillis) {
            mPower = power;
            mEpochMillis = epochMillis;
        }

        public double getPower() {
            return mPower;
        }

        public long getEpochMillis() {
            return mEpochMillis;
        }

        @Override
        public boolean equals(@Nullable Object object) {
            if (object instanceof PowerRecordInternal.PowerRecordSample other) {
                if (Flags.sampleTimeOrdering()) {
                    return mPower == other.mPower && mEpochMillis == other.mEpochMillis;
                } else {
                    return super.equals(other) && getEpochMillis() == other.getEpochMillis();
                }
            }
            return false;
        }

        @Override
        public int hashCode() {
            if (Flags.sampleTimeOrdering()) {
                return Objects.hash(mEpochMillis, mPower);
            } else {
                return Objects.hash(getEpochMillis());
            }
        }
    }
}
