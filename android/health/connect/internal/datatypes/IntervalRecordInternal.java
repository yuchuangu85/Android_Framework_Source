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
import android.health.connect.datatypes.IntervalRecord;
import android.os.Parcel;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Base class for all health connect datatype records that require a start and end time.
 *
 * @hide
 */
public abstract class IntervalRecordInternal<T extends IntervalRecord> extends RecordInternal<T> {
    private long mStartTime;
    private int mStartZoneOffset;
    private long mEndTime;
    private int mEndZoneOffset;

    public IntervalRecordInternal() {}

    IntervalRecordInternal(Parcel parcel) {
        super(parcel);
        mStartTime = parcel.readLong();
        mStartZoneOffset = parcel.readInt();
        mEndTime = parcel.readLong();
        mEndZoneOffset = parcel.readInt();
    }

    public long getStartTimeInMillis() {
        return mStartTime;
    }

    public int getStartZoneOffsetInSeconds() {
        return mStartZoneOffset;
    }

    public long getEndTimeInMillis() {
        return mEndTime;
    }

    public int getEndZoneOffsetInSeconds() {
        return mEndZoneOffset;
    }

    @Override
    void populateRecordTo(@NonNull Parcel parcel) {
        parcel.writeLong(mStartTime);
        parcel.writeInt(mStartZoneOffset);
        parcel.writeLong(mEndTime);
        parcel.writeInt(mEndZoneOffset);

        populateIntervalRecordTo(parcel);
    }

    Instant getStartTime() {
        return Instant.ofEpochMilli(mStartTime);
    }

    @NonNull
    public IntervalRecordInternal<T> setStartTime(long startTime) {
        mStartTime = startTime;
        return this;
    }

    ZoneOffset getStartZoneOffset() {
        return ZoneOffset.ofTotalSeconds(mStartZoneOffset);
    }

    @NonNull
    public IntervalRecordInternal<T> setStartZoneOffset(int startZoneOffset) {
        mStartZoneOffset = startZoneOffset;
        return this;
    }

    Instant getEndTime() {
        return Instant.ofEpochMilli(mEndTime);
    }

    @NonNull
    public IntervalRecordInternal<T> setEndTime(long endTime) {
        mEndTime = endTime;
        return this;
    }

    /** Sets the time and zone offsets for interval internal records */
    @NonNull
    public IntervalRecordInternal<T> setTimeInterval(IntervalRecord record) {
        return this.setStartTime(record.getStartTime().toEpochMilli())
                .setEndTime(record.getEndTime().toEpochMilli())
                .setStartZoneOffset(record.getStartZoneOffset().getTotalSeconds())
                .setEndZoneOffset(record.getEndZoneOffset().getTotalSeconds());
    }

    ZoneOffset getEndZoneOffset() {
        return ZoneOffset.ofTotalSeconds(mEndZoneOffset);
    }

    @NonNull
    public IntervalRecordInternal<T> setEndZoneOffset(int endZoneOffset) {
        mEndZoneOffset = endZoneOffset;
        return this;
    }

    /**
     * @return the {@link LocalDate} object of this activity start time.
     */
    @Override
    @NonNull
    public LocalDate getLocalDate() {
        return LocalDate.ofInstant(this.getStartTime(), this.getStartZoneOffset());
    }

    @Override
    public long getRecordTime() {
        return getEndTimeInMillis();
    }

    /**
     * Populate {@param bundle} with the data required to un-bundle self. This is used during IPC
     * transmissions
     */
    abstract void populateIntervalRecordTo(@NonNull Parcel parcel);
}
