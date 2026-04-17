/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.ranging;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.ranging.RangingManager.RangingTechnology;

import com.android.ranging.flags.Flags;

import java.util.Objects;

/**
 * Represents ranging data, including distance, azimuth, elevation, and RSSI measurements,
 * along with the ranging technology used and a timestamp.
 *
 */
@FlaggedApi(Flags.FLAG_RANGING_STACK_ENABLED)
public final class RangingData implements Parcelable {

    @RangingManager.RangingTechnology
    private final int mRangingTechnology;
    private final RangingMeasurement mDistance;
    private final double mDistanceStdDevMeters;
    @Nullable private final RangingMeasurement mAzimuth;
    @Nullable private final RangingMeasurement mElevation;
    private final int mRssi;
    private final long mTimestamp;
    private final double mDelaySpreadMeters;
    private final /* @android.bluetooth.le.Nadm */ byte mDetectedAttackLevel;
    private final double mVelocityMetersPerSec;
    private final RangingDataExtras mRangingDataExtras;


    private RangingData(Builder builder) {
        if (builder.mDistance == null) {
            throw new IllegalArgumentException("Missing required parameter: distance");
        }
        if (builder.mTimestamp == Long.MIN_VALUE) {
            throw new IllegalArgumentException("Missing required parameter: timestamp");
        }
        if (builder.mRangingTechnology == Integer.MIN_VALUE) {
            throw new IllegalArgumentException("Missing required parameter: rangingTechnology");
        }
        mRangingTechnology = (int) builder.mRangingTechnology;
        mDistance = builder.mDistance;
        mDistanceStdDevMeters = builder.mDistanceStdDevMeters;
        mAzimuth = builder.mAzimuth;
        mElevation = builder.mElevation;
        mRssi = builder.mRssi;
        mTimestamp = builder.mTimestamp;
        mDelaySpreadMeters = builder.mDelaySpreadMeters;
        mDetectedAttackLevel = builder.mDetectedAttackLevel;
        mVelocityMetersPerSec = builder.mVelocityMetersPerSec;
        mRangingDataExtras = builder.mRangingDataExtras;
    }

    private RangingData(Parcel in) {
        mRangingTechnology = in.readInt();
        mDistance = Objects.requireNonNull(
                in.readParcelable(RangingMeasurement.class.getClassLoader(),
                        RangingMeasurement.class));
        mDistanceStdDevMeters = in.readDouble();
        mAzimuth = in.readParcelable(
                RangingMeasurement.class.getClassLoader(), RangingMeasurement.class);
        mElevation = in.readParcelable(
                RangingMeasurement.class.getClassLoader(), RangingMeasurement.class);
        mRssi = in.readInt();
        mTimestamp = in.readLong();
        mDelaySpreadMeters = in.readDouble();
        mDetectedAttackLevel = in.readByte();
        mVelocityMetersPerSec = in.readDouble();
        mRangingDataExtras = in.readParcelable(RangingDataExtras.class.getClassLoader(),
                RangingDataExtras.class);
    }

    public static final @NonNull Creator<RangingData> CREATOR = new Creator<>() {
        @Override
        public RangingData createFromParcel(Parcel in) {
            return new RangingData(in);
        }

        @Override
        public RangingData[] newArray(int size) {
            return new RangingData[size];
        }
    };

    /**
     * Returns the ranging technology used.
     *
     * @return The ranging technology as an integer.
     */
    public @RangingTechnology int getRangingTechnology() {
        return mRangingTechnology;
    }

    /**
     * Returns the distance measurement.
     *
     * @return The {@link RangingMeasurement} representing the distance.
     */
    @Nullable
    public RangingMeasurement getDistance() {
        return mDistance;
    }

    /**
     * Returns the azimuth measurement, or {@code null} if not available.
     *
     * @return The {@link RangingMeasurement} representing the azimuth, or {@code null}.
     */
    @Nullable
    public RangingMeasurement getAzimuth() {
        return mAzimuth;
    }

    /**
     * Returns the elevation measurement, or {@code null} if not available.
     *
     * @return The {@link RangingMeasurement} representing the elevation, or {@code null}.
     */
    @Nullable
    public RangingMeasurement getElevation() {
        return mElevation;
    }

    /**
     * Returns whether an RSSI measurement is included with the data.
     *
     * @return The RSSI value as an integer.
     */
    public boolean hasRssi() {
        return mRssi != Integer.MIN_VALUE;
    }

    /**
     * Returns the RSSI (Received Signal Strength Indicator) value.
     *
     * @return The RSSI value as an integer.
     * @throws IllegalStateException if rssi is not set.
     */
    public int getRssi() {
        if (!hasRssi()) {
            throw new IllegalStateException("rssi is not set");
        }
        return mRssi;
    }

    /**
     * Check if the standard deviation of distance is set.
     *
     * @return True if a standard deviation value is set, false if it isn't.
     */
    @FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_25Q4)
    public boolean hasDistanceStandardDeviation() {
        return !Double.isNaN(mDistanceStdDevMeters);
    }

    /**
     * Get the standard deviation of distance in meters .
     *
     * <p>Returned value will be positive if it exists</p>
     *
     * @return The standard deviation of distance in meters.
     * @throws IllegalStateException if distance standard deviation is not set.
     */
    @FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_25Q4)
    public double getDistanceStandardDeviationMeters() {
        if (!hasDistanceStandardDeviation()) {
            throw new IllegalStateException("Distance Standard Deviation is not set.");
        }
        return mDistanceStdDevMeters;
    }

    /**
     * Check if the delay spread was set.
     *
     * @return True if a delay spread value was set, false if it was not.
     * @hide
     */
    public boolean hasDelaySpread() {
        return !Double.isNaN(mDelaySpreadMeters);
    }

    /**
     * Get estimated delay spread in meters of the measured channel. This is a measure of the
     * multipath richness of the channel.
     *
     * <p>Returned value will be positive if it exists</p>
     *
     * @return The delay spread in meters, or NaN if it was not set.
     * @hide
     */
    public double getDelaySpreadMeters() {
        return mDelaySpreadMeters;
    }

    /**
     * Check if the NADM value for the ranging data has been set.
     *
     * @return True if a NADM value was set, false if not.
     * @hide
     */
    public boolean hasDetectedAttackLevel() {
        return Byte.compareUnsigned(mDetectedAttackLevel, (byte) 0xff) != 0;
    }

    /**
     * Get the detected Normalized Attack Detector (NADM) value for the ranging data. This
     * represents the probability of a malicious attack being underway to the application layer.
     *
     * @return The NADM value, or 0xFF if the value is unknown.
     * @hide
     */
    public /* @android.bluetooth.le.Nadm */ byte getDetectedAttackLevel() {
        return mDetectedAttackLevel;
    }

    /**
     * Check if a velocity value is included in this ranging data.
     *
     * @return True if a velocity was set, false if not.
     * @hide
     */
    public boolean hasVelocity() {
        return !Double.isNaN(mVelocityMetersPerSec);
    }

    /**
     * Get the estimated relative velocity along the direction of shortest distance between the
     * devices in meters/second.
     *
     * @return the velocity in meters/second, or NaN if it was not set.
     * @hide
     */
    public double getVelocityMetersPerSec() {
        return mVelocityMetersPerSec;
    }

    /**
     * Returns the timestamp of when the ranging data was collected.
     *
     * @return The timestamp in milliseconds.
     */
    @ElapsedRealtimeLong
    public long getTimestampMillis() {
        return mTimestamp;
    }

    /**
     * Gets the extra ranging data associated with this ranging data.
     *
     * @return {@link RangingDataExtras}
     */
    @Nullable
    @FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public RangingDataExtras getRangingDataExtras() {
        return mRangingDataExtras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mRangingTechnology);
        dest.writeParcelable(mDistance, flags);
        dest.writeDouble(mDistanceStdDevMeters);
        dest.writeParcelable(mAzimuth, flags);
        dest.writeParcelable(mElevation, flags);
        dest.writeInt(mRssi);
        dest.writeLong(mTimestamp);
        dest.writeDouble(mDelaySpreadMeters);
        dest.writeByte(mDetectedAttackLevel);
        dest.writeDouble(mVelocityMetersPerSec);
        dest.writeParcelable(mRangingDataExtras, flags);
    }

    @Override
    public String toString() {
        return "RangingData{"
                + "mRangingTechnology=" + mRangingTechnology
                + ", mDistance=" + mDistance
                + ", mDistanceStdDevMeters=" + mDistanceStdDevMeters
                + ", mAzimuth=" + mAzimuth
                + ", mElevation=" + mElevation
                + ", mRssi=" + mRssi
                + ", mTimestamp=" + mTimestamp
                + ", mDelaySpreadMeters=" + mDelaySpreadMeters
                + ", mDetectedAttackLevel=" + mDetectedAttackLevel
                + ", mVelocityMetersPerSec=" + mVelocityMetersPerSec
                + ", mRangingDataExtras=" + mRangingDataExtras
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RangingData that = (RangingData) o;
        return mRangingTechnology == that.mRangingTechnology
                && Double.compare(that.mDistanceStdDevMeters, mDistanceStdDevMeters) == 0
                && mRssi == that.mRssi
                && mTimestamp == that.mTimestamp
                && Double.compare(that.mDelaySpreadMeters, mDelaySpreadMeters) == 0
                && mDetectedAttackLevel == that.mDetectedAttackLevel
                && Double.compare(that.mVelocityMetersPerSec, mVelocityMetersPerSec) == 0
                && Objects.equals(mDistance, that.mDistance)
                && Objects.equals(mAzimuth, that.mAzimuth)
                && Objects.equals(mElevation, that.mElevation)
                && Objects.equals(mRangingDataExtras, that.mRangingDataExtras);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRangingTechnology, mDistance, mDistanceStdDevMeters, mAzimuth,
                mElevation, mRssi, mTimestamp, mDelaySpreadMeters, mDetectedAttackLevel,
                mVelocityMetersPerSec, mRangingDataExtras);
    }

    /**
     * Builder class for creating instances of {@link RangingData}.
     *
     * @hide
     */
    public static final class Builder {
        private int mRangingTechnology = Integer.MIN_VALUE;
        private RangingMeasurement mDistance = null;
        private double mDistanceStdDevMeters = Double.NaN;
        private RangingMeasurement mAzimuth = null;
        private RangingMeasurement mElevation = null;
        private int mRssi = Integer.MIN_VALUE;
        private long mTimestamp = Long.MIN_VALUE;
        private double mDelaySpreadMeters = Double.NaN;
        private /* @android.bluetooth.le.Nadm */ byte mDetectedAttackLevel = (byte) 0xff;
        private double mVelocityMetersPerSec = Double.NaN;
        private RangingDataExtras mRangingDataExtras = null;

        /**
         * Sets the ranging technology.
         *
         * @param rangingTechnology The ranging technology used.
         * @return This {@link Builder} instance.
         */
        @NonNull
        public Builder setRangingTechnology(@RangingTechnology int rangingTechnology) {
            mRangingTechnology = rangingTechnology;
            return this;
        }

        /**
         * Sets the distance measurement.
         *
         * @param distance The {@link RangingMeasurement} representing the distance.
         * @return This {@link Builder} instance.
         *
         * @throws IllegalArgumentException if the provided measurement is null.
         */
        @NonNull
        public Builder setDistance(@NonNull RangingMeasurement distance) {
            mDistance = distance;
            return this;
        }

        /**
         * Sets the azimuth measurement.
         *
         * @param azimuth The {@link RangingMeasurement} representing the azimuth.
         * @return This {@link Builder} instance.
         *
         * @throws IllegalArgumentException if the provided measurement is null.
         */
        @NonNull
        public Builder setAzimuth(@NonNull RangingMeasurement azimuth) {
            mAzimuth = azimuth;
            return this;
        }

        /**
         * Sets the elevation measurement.
         *
         * @param elevation The {@link RangingMeasurement} representing the elevation.
         * @return This {@link Builder} instance.
         *
         * @throws IllegalArgumentException if the provided measurement is null.
         */
        @NonNull
        public Builder setElevation(@NonNull RangingMeasurement elevation) {
            mElevation = elevation;
            return this;
        }

        /**
         * Sets the RSSI value.
         *
         * @param rssi The RSSI value as an integer.
         * @return This {@link Builder} instance.
         */
        @NonNull
        public Builder setRssi(int rssi) {
            mRssi = rssi;
            return this;
        }

        /**
         * Sets the timestamp of the ranging data.
         *
         * @param timestamp The timestamp in milliseconds.
         * @return This {@link Builder} instance.
         */
        @NonNull
        public Builder setTimestampMillis(long timestamp) {
            mTimestamp = timestamp;
            return this;
        }

        /**
         * Sets the standard deviation of distance in meters.
         *
         * <p>Must be positive.</p>
         *
         * @param stdDevMeters The standard deviation of distance in meters.
         * @return This {@link Builder} instance.
         * @throws IllegalArgumentException if the provided value is < 0.
         */
        @NonNull
        public Builder setDistanceStandardDeviation(double stdDevMeters) {
            if (stdDevMeters < 0) {
                throw new IllegalArgumentException("Std Dev meters must be >= 0");
            }
            mDistanceStdDevMeters = stdDevMeters;
            return this;
        }

        /**
         * Sets the estimated delay spread in meters. This is a measure of the multipath richness
         * of the channel.
         *
         * <p>Must be positive.</p>
         *
         * @param delaySpread The estimated delay spread in meters.
         * @return This {@link Builder} instance.
         * @throws IllegalArgumentException if the provided value is < 0.
         * @hide
         */
        @NonNull
        public Builder setDelaySpreadMeters(double delaySpread) {
            if (delaySpread < 0) {
                throw new IllegalArgumentException("Delay spread meters must be >= 0");
            }
            mDelaySpreadMeters = delaySpread;
            return this;
        }

        /**
         * Set the detected Normalized Attack Detector (NADM) value for the ranging data. This
         * represents the probability of a malicious attack being underway to the application layer.
         *
         * @param detectedAttackLevel The NADM value for this measurement.
         * @return This {@link Builder} instance.
         * @throws IllegalArgumentException If the provided NADM value is invalid.
         * @hide
         */
        @NonNull
        public Builder setDetectedAttackLevel(
                /* @android.bluetooth.le.Nadm */ byte detectedAttackLevel) {
            mDetectedAttackLevel = detectedAttackLevel;
            return this;
        }

        /**
         * Set the estimated relative velocity along the direction of shortest distance between the
         * devices in meters/second.
         *
         * @param velocity The estimated velocity in meters.
         * @return This {@link Builder} instance.
         * @hide
         */
        @NonNull
        public Builder setVelocityMetersPerSec(double velocity) {
            mVelocityMetersPerSec = velocity;
            return this;
        }

        /**
         * Set additional ranging data.
         *
         * @param rangingDataExtras the additional ranging data
         * @return the additional ranging data
         */
        @NonNull
        public Builder setRangingDataExtras(RangingDataExtras rangingDataExtras) {
            mRangingDataExtras = rangingDataExtras;
            return this;
        }

        /**
         * Builds and returns a new instance of {@link RangingData}.
         *
         * @return A new {@link RangingData} instance.
         */
        @NonNull
        public RangingData build() {
            return new RangingData(this);
        }
    }
}
