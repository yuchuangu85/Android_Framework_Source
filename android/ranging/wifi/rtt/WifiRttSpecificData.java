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

package android.ranging.wifi.rtt;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.net.wifi.WifiAnnotations;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.ranging.flags.Flags;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents the {@link android.ranging.RangingManager.RangingTechnology#WIFI_NAN_RTT} specific
 * data. This is generally used by algorithms for fine tuning ranging data.
 *
 */
@FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
public final class WifiRttSpecificData implements Parcelable {

    private final int mNumAttemptedMeasurements;
    private final int mNumSuccessfulMeasurements;
    private final @WifiAnnotations.ChannelWidth int mMeasurementBandwidth;
    private final int mMeasurementChannelFrequencyMHz;
    private final byte[] mLci;
    private final double mDistanceStdDevMeters;

    private WifiRttSpecificData(Builder builder) {
        mNumAttemptedMeasurements = builder.mNumAttemptedMeasurements;
        mNumSuccessfulMeasurements = builder.mNumSuccessfulMeasurements;
        mMeasurementBandwidth = builder.mMeasurementBandwidth;
        mMeasurementChannelFrequencyMHz = builder.mMeasurementChannelFrequencyMHz;
        mLci = builder.mLci;
        mDistanceStdDevMeters = builder.mDistanceStdDevMeters;
    }

    private WifiRttSpecificData(Parcel in) {
        mNumAttemptedMeasurements = in.readInt();
        mNumSuccessfulMeasurements = in.readInt();
        mMeasurementBandwidth = in.readInt();
        mMeasurementChannelFrequencyMHz = in.readInt();
        mLci = in.createByteArray();
        mDistanceStdDevMeters = in.readDouble();
    }

    @NonNull
    public static final Creator<WifiRttSpecificData> CREATOR =
            new Creator<>() {
                @Override
                public WifiRttSpecificData createFromParcel(Parcel in) {
                    return new WifiRttSpecificData(in);
                }

                @Override
                public WifiRttSpecificData[] newArray(int size) {
                    return new WifiRttSpecificData[size];
                }
            };

    /**
     * Gets the number of attempted measurements.
     *
     * @return the number attempted measurements
     */
    public int getNumAttemptedMeasurements() {
        return mNumAttemptedMeasurements;
    }

    /**
     * Gets the  number of successful measurements.
     *
     * @return the number successful measurements
     */
    public int getNumSuccessfulMeasurements() {
        return mNumSuccessfulMeasurements;
    }

    /**
     * Gets the measurement bandwidth.
     *
     * @return the measurement bandwidth
     */
    public @WifiAnnotations.ChannelWidth int getMeasurementBandwidth() {
        return mMeasurementBandwidth;
    }

    /**
     * Gets the measurement channel frequency in MHz.
     *
     * @return the measurement channel frequency in MHz
     */
    public int getMeasurementChannelFrequencyMHz() {
        return mMeasurementChannelFrequencyMHz;
    }

    /**
     * Gets the LCI.
     *
     * @return the LCI
     */
    @NonNull
    public byte[] getLci() {
        return mLci;
    }

    /**
     * Gets the distance standard deviation in meters.
     *
     * @return the distance standard deviation in meters
     */
    public double getDistanceStandardDeviationMeters() {
        return mDistanceStdDevMeters;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mNumAttemptedMeasurements);
        dest.writeInt(mNumSuccessfulMeasurements);
        dest.writeInt(mMeasurementBandwidth);
        dest.writeInt(mMeasurementChannelFrequencyMHz);
        dest.writeByteArray(mLci);
        dest.writeDouble(mDistanceStdDevMeters);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WifiRttSpecificData)) return false;
        WifiRttSpecificData that = (WifiRttSpecificData) o;
        return mNumAttemptedMeasurements == that.mNumAttemptedMeasurements
                && mNumSuccessfulMeasurements == that.mNumSuccessfulMeasurements
                && mMeasurementBandwidth == that.mMeasurementBandwidth
                && mMeasurementChannelFrequencyMHz == that.mMeasurementChannelFrequencyMHz
                && Arrays.equals(mLci, that.mLci)
                && Double.compare(that.mDistanceStdDevMeters, mDistanceStdDevMeters) == 0;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mNumAttemptedMeasurements, mNumSuccessfulMeasurements,
                mMeasurementBandwidth, mMeasurementChannelFrequencyMHz, mDistanceStdDevMeters);
        result = 31 * result + Arrays.hashCode(mLci);
        return result;
    }

    /**
     * Builder class for creating instances of {@link WifiRttSpecificData}.
     *
     * @hide
     */
    public static final class Builder {
        private int mNumAttemptedMeasurements = Integer.MIN_VALUE;
        private int mNumSuccessfulMeasurements = Integer.MIN_VALUE;
        private @WifiAnnotations.ChannelWidth int mMeasurementBandwidth = Integer.MIN_VALUE;
        private int mMeasurementChannelFrequencyMHz = Integer.MIN_VALUE;
        private byte[] mLci = null;
        private double mDistanceStdDevMeters = Double.NaN;

        /**
         * Sets num attempted measurements.
         *
         * @param numAttemptedMeasurements the num attempted measurements
         * @return the Builder instance
         */
        @NonNull
        public Builder setNumAttemptedMeasurements(int numAttemptedMeasurements) {
            mNumAttemptedMeasurements = numAttemptedMeasurements;
            return this;
        }

        /**
         * Sets num successful measurements.
         *
         * @param numSuccessfulMeasurements the num successful measurements
         * @return the Builder instance
         */
        @NonNull
        public Builder setNumSuccessfulMeasurements(int numSuccessfulMeasurements) {
            mNumSuccessfulMeasurements = numSuccessfulMeasurements;
            return this;
        }

        /**
         * Sets measurement bandwidth.
         *
         * @param measurementBandwidth the measurement bandwidth
         * @return the Builder instance
         */
        @NonNull
        public Builder setMeasurementBandwidth(
                @WifiAnnotations.ChannelWidth int measurementBandwidth) {
            mMeasurementBandwidth = measurementBandwidth;
            return this;
        }

        /**
         * Sets measurement channel frequency in MHz.
         *
         * @param measurementChannelFrequencyMHz the measurement channel frequency in MHz
         * @return the Builder instance
         */
        @NonNull
        public Builder setMeasurementChannelFrequencyMHz(int measurementChannelFrequencyMHz) {
            mMeasurementChannelFrequencyMHz = measurementChannelFrequencyMHz;
            return this;
        }

        /**
         * Sets LCI.
         *
         * @param lci the LCI
         * @return the Builder instance
         */
        @NonNull
        public Builder setLci(@NonNull byte[] lci) {
            mLci = lci;
            return this;
        }

        /**
         * Sets distance standard deviation in meters.
         *
         * @param distanceStdDevMeters the distance standard deviation in meters
         * @return the Builder instance
         */
        @NonNull
        public Builder setDistanceStandardDeviationMeters(double distanceStdDevMeters) {
            mDistanceStdDevMeters = distanceStdDevMeters;
            return this;
        }

        /**
         * Build additional ranging data.
         *
         * @return the additional ranging data
         */
        @NonNull
        public WifiRttSpecificData build() {
            return new WifiRttSpecificData(this);
        }
    }

    @Override
    public String toString() {
        return "WifiRttSpecificData{"
                + "mNumAttemptedMeasurements=" + mNumAttemptedMeasurements
                + ", mNumSuccessfulMeasurements=" + mNumSuccessfulMeasurements
                + ", mMeasurementBandwidth=" + mMeasurementBandwidth
                + ", mMeasurementChannelFrequencyMHz=" + mMeasurementChannelFrequencyMHz
                + ", mLci=" + Arrays.toString(mLci)
                + ", mDistanceStdDevMeters=" + mDistanceStdDevMeters
                + '}';
    }
}
