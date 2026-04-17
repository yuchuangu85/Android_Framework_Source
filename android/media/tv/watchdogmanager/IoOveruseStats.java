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

package android.media.tv.watchdogmanager;

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.media.tv.flags.Flags;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

/**
 * Disk I/O overuse stats for a package.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_TV_WATCHDOG_EMMC_PROTECTION)
public final class IoOveruseStats implements Parcelable {
    /** Start time of the time period, in epoch seconds. */
    private long mStartTime;

    /** Duration of the time period, in seconds. */
    private long mDurationInSeconds;

    /**
     * Total times the package has written to disk beyond the allowed write bytes during the given
     * period.
     */
    private long mTotalOveruses = 0;

    /** Total times the package was killed during the given period due to disk I/O overuse. */
    private long mTotalTimesKilled = 0;

    /** Aggregated number of bytes written to disk by the package during the given period. */
    private long mTotalBytesWritten = 0;

    /**
     * Package may be killed on disk I/O overuse.
     *
     * <p>Disk I/O overuse is triggered on exceeding {@link #mRemainingWriteBytes}.
     */
    private boolean mKillableOnOveruse = false;

    /**
     * Number of write bytes remaining in each application or system state.
     *
     * <p>On exceeding these limit in at least one system or application state, the package may be
     * killed if {@link #mKillableOnOveruse} is {@code true}.
     *
     * <p>The {@link #mDurationInSeconds} does not apply to this field.
     */
    private @NonNull PerStateBytes mRemainingWriteBytes = new PerStateBytes(0L, 0L, 0L);

    /* package-private */ IoOveruseStats(
            long startTime,
            long durationInSeconds,
            long totalOveruses,
            long totalTimesKilled,
            long totalBytesWritten,
            boolean killableOnOveruse,
            @NonNull PerStateBytes remainingWriteBytes) {
        this.mStartTime = startTime;
        this.mDurationInSeconds = durationInSeconds;
        this.mTotalOveruses = totalOveruses;
        this.mTotalTimesKilled = totalTimesKilled;
        this.mTotalBytesWritten = totalBytesWritten;
        this.mKillableOnOveruse = killableOnOveruse;
        this.mRemainingWriteBytes = remainingWriteBytes;
        AnnotationValidations.validate(NonNull.class, null, mRemainingWriteBytes);
    }

    /** Start time of the time period, in epoch seconds. */
    @IntRange(from = 0)
    public long getStartTime() {
        return mStartTime;
    }

    /** Duration of the time period, in seconds. */
    public long getDurationInSeconds() {
        return mDurationInSeconds;
    }

    /**
     * Total times the package has written to disk beyond the allowed write bytes during the given
     * period.
     */
    public long getTotalOveruses() {
        return mTotalOveruses;
    }

    /** Total times the package was killed during the given period due to disk I/O overuse. */
    public long getTotalTimesKilled() {
        return mTotalTimesKilled;
    }

    /** Aggregated number of bytes written to disk by the package during the given period. */
    public long getTotalBytesWritten() {
        return mTotalBytesWritten;
    }

    /**
     * Package may be killed on disk I/O overuse.
     *
     * <p>Disk I/O overuse is triggered on exceeding {@link #mRemainingWriteBytes}.
     */
    public boolean isKillableOnOveruse() {
        return mKillableOnOveruse;
    }

    /**
     * Number of write bytes remaining in each application or system state.
     *
     * <p>On exceeding these limit in at least one system or application state, the package may be
     * killed if {@link #mKillableOnOveruse} is {@code true}.
     *
     * <p>The {@link #mDurationInSeconds} does not apply to this field.
     */
    public @NonNull PerStateBytes getRemainingWriteBytes() {
        return mRemainingWriteBytes;
    }

    @Override
    public String toString() {
        return "IoOveruseStats { "
                + "startTime = "
                + mStartTime
                + ", "
                + "durationInSeconds = "
                + mDurationInSeconds
                + ", "
                + "totalOveruses = "
                + mTotalOveruses
                + ", "
                + "totalTimesKilled = "
                + mTotalTimesKilled
                + ", "
                + "totalBytesWritten = "
                + mTotalBytesWritten
                + ", "
                + "killableOnOveruse = "
                + mKillableOnOveruse
                + ", "
                + "remainingWriteBytes = "
                + mRemainingWriteBytes
                + " }";
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        byte flg = 0;
        if (mKillableOnOveruse) flg |= 0x20;
        dest.writeByte(flg);
        dest.writeLong(mStartTime);
        dest.writeLong(mDurationInSeconds);
        dest.writeLong(mTotalOveruses);
        dest.writeLong(mTotalTimesKilled);
        dest.writeLong(mTotalBytesWritten);
        dest.writeTypedObject(mRemainingWriteBytes, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    /* package-private */ IoOveruseStats(@NonNull android.os.Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        byte flg = in.readByte();
        boolean killableOnOveruse = (flg & 0x20) != 0;
        long startTime = in.readLong();
        long durationInSeconds = in.readLong();
        long totalOveruses = in.readLong();
        long totalTimesKilled = in.readLong();
        long totalBytesWritten = in.readLong();
        PerStateBytes remainingWriteBytes =
                (PerStateBytes) in.readTypedObject(PerStateBytes.CREATOR);

        this.mStartTime = startTime;
        this.mDurationInSeconds = durationInSeconds;
        this.mTotalOveruses = totalOveruses;
        this.mTotalTimesKilled = totalTimesKilled;
        this.mTotalBytesWritten = totalBytesWritten;
        this.mKillableOnOveruse = killableOnOveruse;
        this.mRemainingWriteBytes = remainingWriteBytes;
        AnnotationValidations.validate(NonNull.class, null, mRemainingWriteBytes);
    }

    public static final @NonNull Parcelable.Creator<IoOveruseStats> CREATOR =
            new Parcelable.Creator<IoOveruseStats>() {
                @Override
                public IoOveruseStats[] newArray(int size) {
                    return new IoOveruseStats[size];
                }

                @Override
                public IoOveruseStats createFromParcel(@NonNull android.os.Parcel in) {
                    return new IoOveruseStats(in);
                }
            };

    /**
     * A builder for {@link IoOveruseStats}
     *
     * @hide
     */
    @SuppressWarnings("WeakerAccess")
    public static final class Builder {

        private long mStartTime;
        private long mDurationInSeconds;
        private long mTotalOveruses;
        private long mTotalTimesKilled;
        private long mTotalBytesWritten;
        private boolean mKillableOnOveruse;
        private @NonNull PerStateBytes mRemainingWriteBytes;

        private long mBuilderFieldsSet = 0L;

        /**
         * Creates a new Builder.
         *
         * @param startTime Start time of the time period, in epoch seconds.
         * @param durationInSeconds Duration of the time period, in seconds.
         */
        public Builder(long startTime, long durationInSeconds) {
            mStartTime = startTime;
            mDurationInSeconds = durationInSeconds;
        }

        /** Start time of the time period, in epoch seconds. */
        public @NonNull Builder setStartTime(long value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mStartTime = value;
            return this;
        }

        /** Duration of the time period, in seconds. */
        public @NonNull Builder setDurationInSeconds(long value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mDurationInSeconds = value;
            return this;
        }

        /**
         * Total times the package has written to disk beyond the allowed write bytes during the
         * given period.
         */
        public @NonNull Builder setTotalOveruses(long value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mTotalOveruses = value;
            return this;
        }

        /** Total times the package was killed during the given period due to disk I/O overuse. */
        public @NonNull Builder setTotalTimesKilled(long value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mTotalTimesKilled = value;
            return this;
        }

        /** Aggregated number of bytes written to disk by the package during the given period. */
        public @NonNull Builder setTotalBytesWritten(long value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10;
            mTotalBytesWritten = value;
            return this;
        }

        /**
         * Package may be killed on disk I/O overuse.
         *
         * <p>Disk I/O overuse is triggered on exceeding {@link #mRemainingWriteBytes}.
         */
        public @NonNull Builder setKillableOnOveruse(boolean value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x20;
            mKillableOnOveruse = value;
            return this;
        }

        /**
         * Number of write bytes remaining in each application or system state.
         *
         * <p>On exceeding these limit in at least one system or application state, the package may
         * be killed if {@link #mKillableOnOveruse} is {@code true}.
         *
         * <p>The {@link #mDurationInSeconds} does not apply to this field.
         */
        public @NonNull Builder setRemainingWriteBytes(@NonNull PerStateBytes value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x40;
            mRemainingWriteBytes = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull IoOveruseStats build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x80; // Mark builder used

            if ((mBuilderFieldsSet & 0x4) == 0) {
                mTotalOveruses = 0;
            }
            if ((mBuilderFieldsSet & 0x8) == 0) {
                mTotalTimesKilled = 0;
            }
            if ((mBuilderFieldsSet & 0x10) == 0) {
                mTotalBytesWritten = 0;
            }
            if ((mBuilderFieldsSet & 0x20) == 0) {
                mKillableOnOveruse = false;
            }
            if ((mBuilderFieldsSet & 0x40) == 0) {
                mRemainingWriteBytes = new PerStateBytes(0L, 0L, 0L);
            }
            IoOveruseStats o =
                    new IoOveruseStats(
                            mStartTime,
                            mDurationInSeconds,
                            mTotalOveruses,
                            mTotalTimesKilled,
                            mTotalBytesWritten,
                            mKillableOnOveruse,
                            mRemainingWriteBytes);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x80) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
