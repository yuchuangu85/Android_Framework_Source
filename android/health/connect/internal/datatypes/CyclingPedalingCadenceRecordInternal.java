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
import android.health.connect.datatypes.CyclingPedalingCadenceRecord;
import android.health.connect.datatypes.Identifier;
import android.health.connect.datatypes.RecordTypeIdentifier;
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
 * @see CyclingPedalingCadenceRecord
 * @hide
 */
@Identifier(recordIdentifier = RecordTypeIdentifier.RECORD_TYPE_CYCLING_PEDALING_CADENCE)
public class CyclingPedalingCadenceRecordInternal
        extends SeriesRecordInternal<
                CyclingPedalingCadenceRecord,
                CyclingPedalingCadenceRecordInternal.CyclingPedalingCadenceRecordSample> {
    private Set<CyclingPedalingCadenceRecordSample> mSamples =
            new TreeSet<>(
                    Comparator.comparingLong(CyclingPedalingCadenceRecordSample::getEpochMillis));

    public CyclingPedalingCadenceRecordInternal(Set<CyclingPedalingCadenceRecordSample> samples) {
        super();
        if (Flags.sampleTimeOrdering()) {
            mSamples.addAll(samples);
        } else {
            mSamples = samples;
        }
    }

    @SuppressWarnings("unused") // used by reflection in parcel flow
    public CyclingPedalingCadenceRecordInternal(Parcel parcel) {
        super(parcel);
        int size = parcel.readInt();
        if (!Flags.sampleTimeOrdering()) {
            mSamples = new HashSet<>(size);
        }
        for (int i = 0; i < size; i++) {
            mSamples.add(
                    new CyclingPedalingCadenceRecordSample(parcel.readDouble(), parcel.readLong()));
        }
    }

    @Override
    @NonNull
    public Set<CyclingPedalingCadenceRecordSample> getSamples() {
        return mSamples;
    }

    @Override
    public void addSample(CyclingPedalingCadenceRecordSample sample) {
        mSamples.add(sample);
    }

    @Override
    @NonNull
    public CyclingPedalingCadenceRecord toExternalRecord() {
        return new CyclingPedalingCadenceRecord.Builder(
                        buildMetaData(), getStartTime(), getEndTime(), getExternalSamples())
                .setStartZoneOffset(getStartZoneOffset())
                .setEndZoneOffset(getEndZoneOffset())
                .buildWithoutValidation();
    }

    @Override
    void populateIntervalRecordTo(@NonNull Parcel parcel) {
        parcel.writeInt(mSamples.size());
        for (CyclingPedalingCadenceRecordSample cyclingPedalingCadenceRecordSample : mSamples) {
            parcel.writeDouble(cyclingPedalingCadenceRecordSample.getRevolutionsPerMinute());
            parcel.writeLong(cyclingPedalingCadenceRecordSample.getEpochMillis());
        }
    }

    private List<CyclingPedalingCadenceRecord.CyclingPedalingCadenceRecordSample>
            getExternalSamples() {
        List<CyclingPedalingCadenceRecord.CyclingPedalingCadenceRecordSample>
                cyclingPedalingCadenceRecords = new ArrayList<>(mSamples.size());
        for (CyclingPedalingCadenceRecordSample sample : mSamples) {
            cyclingPedalingCadenceRecords.add(
                    new CyclingPedalingCadenceRecord.CyclingPedalingCadenceRecordSample(
                            sample.getRevolutionsPerMinute(),
                            Instant.ofEpochMilli(sample.getEpochMillis()),
                            true));
        }
        return cyclingPedalingCadenceRecords;
    }

    /**
     * @see CyclingPedalingCadenceRecord.CyclingPedalingCadenceRecordSample
     */
    public static final class CyclingPedalingCadenceRecordSample implements Sample {
        private final double mRevolutionsPerMinute;
        private final long mEpochMillis;

        public CyclingPedalingCadenceRecordSample(double revolutionsPerMinute, long epochMillis) {
            mRevolutionsPerMinute = revolutionsPerMinute;
            mEpochMillis = epochMillis;
        }

        public double getRevolutionsPerMinute() {
            return mRevolutionsPerMinute;
        }

        public long getEpochMillis() {
            return mEpochMillis;
        }

        @Override
        public boolean equals(@Nullable Object object) {
            if (Flags.sampleTimeOrdering()) {
                if (object instanceof CyclingPedalingCadenceRecordSample other) {
                    return mEpochMillis == other.mEpochMillis
                            && mRevolutionsPerMinute == other.mRevolutionsPerMinute;
                }
                return false;
            } else {
                if (super.equals(object)
                        && object instanceof CyclingPedalingCadenceRecordSample other) {
                    return getEpochMillis() == other.getEpochMillis();
                }
                return false;
            }
        }

        @Override
        public int hashCode() {
            if (Flags.sampleTimeOrdering()) {
                return Objects.hash(mEpochMillis, mRevolutionsPerMinute);
            } else {
                return Objects.hash(mEpochMillis);
            }
        }
    }
}
