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

import android.annotation.Nullable;
import android.health.connect.datatypes.Identifier;
import android.health.connect.datatypes.RecordTypeIdentifier;
import android.health.connect.datatypes.SymptomRecord;
import android.os.Parcel;

/** @hide */
@Identifier(recordIdentifier = RecordTypeIdentifier.RECORD_TYPE_SYMPTOM)
public class SymptomRecordInternal extends IntervalRecordInternal<SymptomRecord> {
    @SymptomRecord.SymptomType private int mSymptomType = SymptomRecord.SYMPTOM_TYPE_UNKNOWN;
    @Nullable private String mNotes = null;
    @SymptomRecord.SymptomSeverity private int mSeverity = SymptomRecord.SEVERITY_UNSPECIFIED;
    private int mCount = 1;

    @SymptomRecord.SymptomRecordTemporalType
    private int mTemporalType = SymptomRecord.RECORD_TEMPORAL_TYPE_INSTANT;

    public SymptomRecordInternal() {
        super();
    }

    public SymptomRecordInternal(Parcel parcel) {
        super(parcel);
        mSymptomType = parcel.readInt();
        mNotes = parcel.readString();
        mSeverity = parcel.readInt();
        mCount = parcel.readInt();
        mTemporalType = parcel.readInt();
    }

    /** Returns the symptom type. */
    @SymptomRecord.SymptomType
    public int getSymptomType() {
        return mSymptomType;
    }

    /** Sets the symptom type. */
    public SymptomRecordInternal setSymptomType(@SymptomRecord.SymptomType int symptomType) {
        this.mSymptomType = symptomType;
        return this;
    }

    /** Returns the notes. */
    @Nullable
    public String getNotes() {
        return mNotes;
    }

    /** Sets the notes of the symptom. */
    public SymptomRecordInternal setNotes(@Nullable String notes) {
        this.mNotes = notes;
        return this;
    }

    /** Returns the severity. */
    @SymptomRecord.SymptomSeverity
    public int getSeverity() {
        return mSeverity;
    }

    /** Sets the severity of the symptom. */
    public SymptomRecordInternal setSeverity(@SymptomRecord.SymptomSeverity int severity) {
        this.mSeverity = severity;
        return this;
    }

    /** Returns the count. */
    public int getCount() {
        return mCount;
    }

    /** Sets the count of the symptom. */
    public SymptomRecordInternal setCount(int count) {
        this.mCount = count;
        return this;
    }

    /** Returns the temporal type. */
    @SymptomRecord.SymptomRecordTemporalType
    public int getTemporalType() {
        return mTemporalType;
    }

    /** Sets the temporal type of the symptom. */
    public SymptomRecordInternal setTemporalType(
            @SymptomRecord.SymptomRecordTemporalType int temporalType) {
        this.mTemporalType = temporalType;
        return this;
    }

    /**
     * @return an external {@link SymptomRecord}
     * @hide
     */
    @Override
    public SymptomRecord toExternalRecord() {
        SymptomRecord.Builder builder =
                switch (getTemporalType()) {
                    case SymptomRecord.RECORD_TEMPORAL_TYPE_INSTANT ->
                            new SymptomRecord.Builder(
                                    mSymptomType, getStartTime(), buildMetaData());
                    case SymptomRecord.RECORD_TEMPORAL_TYPE_INTERVAL ->
                            new SymptomRecord.Builder(
                                    mSymptomType, getStartTime(), getEndTime(), buildMetaData());
                    case SymptomRecord.RECORD_TEMPORAL_TYPE_LOCAL_DATE ->
                            new SymptomRecord.Builder(
                                    mSymptomType,
                                    getStartTime().atZone(getStartZoneOffset()).toLocalDate(),
                                    buildMetaData());
                    default ->
                            throw new IllegalStateException("Unexpected value: " + mTemporalType);
                };
        if (getNotes() != null) {
            builder.setNotes(mNotes);
        }
        builder.setSeverity(mSeverity);
        if (mTemporalType != SymptomRecord.RECORD_TEMPORAL_TYPE_INSTANT) {
            builder.setCount(mCount);
        }
        if (mTemporalType != SymptomRecord.RECORD_TEMPORAL_TYPE_LOCAL_DATE) {
            builder.setStartZoneOffset(getStartZoneOffset());
            builder.setEndZoneOffset(getEndZoneOffset());
        }
        return builder.buildWithoutValidation();
    }

    @Override
    void populateIntervalRecordTo(Parcel dest) {
        dest.writeInt(mSymptomType);
        dest.writeString(mNotes);
        dest.writeInt(mSeverity);
        dest.writeInt(mCount);
        dest.writeInt(mTemporalType);
    }
}
