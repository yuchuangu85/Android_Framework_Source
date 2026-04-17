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

package android.location;

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.location.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * Represents an ionospheric model as a single-layer 2D grid with a constant height. This is also
 * referred to as a thin-shell model.
 *
 * <p>The format is a simplified adaptation of the <strong>IONEX</strong> (IONosphere Map EXchange)
 * specification.
 *
 * <p><strong>See Also:</strong>
 *
 * <ul>
 *   <li><a href="https://igs.org/formats-and-standards/">IONEX: The IONosphere Map EXchange Format
 *       Version 1</a>
 *   <li>For interpolation procedures, refer to the "Application of IONEX TEC Maps" section within
 *       the IONEX specification.
 * </ul>
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_SUPPORT_IONEX_ASSISTANCE)
@SystemApi
public final class IonexAssistance implements Parcelable {

    /**
     * Metadata describing the grid dimensions, height, and mapping function that applies to the
     * tecMapSnapshot in this model.
     */
    @NonNull private final Header mHeader;

    /** A TEC map at a moment in time. */
    @NonNull private final TecMapSnapshot mTecMapSnapshot;

    private IonexAssistance(Builder builder) {
        Preconditions.checkNotNull(builder.mHeader, "Header cannot be null");
        Preconditions.checkNotNull(builder.mTecMapSnapshot, "TecMapSnapshots cannot be null");
        // Extract nested objects for readability.
        final Header header = builder.mHeader;
        final TecMapSnapshot snapshot = builder.mTecMapSnapshot;
        final int numLatPoints = header.getAxesInfo().getLatitudeAxis().getNumPoints();
        final int numLonPoints = header.getAxesInfo().getLongitudeAxis().getNumPoints();
        final int expectedGridSize = numLatPoints * numLonPoints;

        // Check that the TEC map size matches the grid dimensions from the header.
        Preconditions.checkArgument(
                snapshot.getTecMap().length == expectedGridSize,
                "TEC map size (%s) must match the grid size defined in the header (%s x %s = %s).",
                snapshot.getTecMap().length,
                numLatPoints,
                numLonPoints,
                expectedGridSize);

        // If the optional RMS map is present, it must also have the correct size.
        if (snapshot.getRmsMap().length > 0) {
            Preconditions.checkArgument(
                    snapshot.getRmsMap().length == expectedGridSize,
                    "RMS map size (%s) must match the grid size defined in the header (%s x %s ="
                            + " %s).",
                    snapshot.getRmsMap().length,
                    numLatPoints,
                    numLonPoints,
                    expectedGridSize);
        }
        mHeader = builder.mHeader;
        mTecMapSnapshot = builder.mTecMapSnapshot;
    }

    /** Returns the header. */
    @NonNull
    public Header getHeader() {
        return mHeader;
    }

    /** Returns the tecMapSnapshot. */
    @NonNull
    public TecMapSnapshot getTecMapSnapshot() {
        return mTecMapSnapshot;
    }

    public static final @NonNull Creator<IonexAssistance> CREATOR =
            new Creator<IonexAssistance>() {
                @Override
                @NonNull
                public IonexAssistance createFromParcel(Parcel in) {
                    return new Builder()
                            .setHeader(in.readTypedObject(Header.CREATOR))
                            .setTecMapSnapshot(in.readTypedObject(TecMapSnapshot.CREATOR))
                            .build();
                }

                @Override
                public IonexAssistance[] newArray(int size) {
                    return new IonexAssistance[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeTypedObject(mHeader, flags);
        parcel.writeTypedObject(mTecMapSnapshot, flags);
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("IonexAssistance[");
        builder.append("header=").append(mHeader);
        builder.append(", tecMapSnapshot=").append(mTecMapSnapshot);
        builder.append("]");
        return builder.toString();
    }

    /** Builder for {@link IonexAssistance}. */
    public static final class Builder {
        private Header mHeader;
        private TecMapSnapshot mTecMapSnapshot;

        /** Sets the header. */
        @NonNull
        public Builder setHeader(@NonNull Header header) {
            mHeader = header;
            return this;
        }

        /** Sets the tecMapSnapshot. */
        @NonNull
        public Builder setTecMapSnapshot(@NonNull TecMapSnapshot tecMapSnapshot) {
            mTecMapSnapshot = tecMapSnapshot;
            return this;
        }

        /** Builds an {@link IonexAssistance} instance. */
        @NonNull
        public IonexAssistance build() {
            return new IonexAssistance(this);
        }
    }

    /** Defines the metadata for the ionospheric grid model. */
    public static final class Header implements Parcelable {

        /**
         * Mapping functions for TEC determination.
         *
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({MAPPING_FUNCTION_NONE, MAPPING_FUNCTION_COSZ, MAPPING_FUNCTION_QFAC})
        public @interface MappingFunction {}

        /**
         * The following enumerations must be in sync with the values declared in MappingFunction in
         * IonexAssistance.aidl.
         */
        /** No mapping function. */
        public static final int MAPPING_FUNCTION_NONE = 0;

        /** 1/cos(z) mapping function. */
        public static final int MAPPING_FUNCTION_COSZ = 1;

        /** Q-factor mapping function. */
        public static final int MAPPING_FUNCTION_QFAC = 2;

        /**
         * The mapping function adopted for TEC determination. e.g. MAPPING_FUNCTION_NONE (no
         * mapping function), MAPPING_FUNCTION_COSZ (1/cos(z)).
         */
        private final @MappingFunction int mMappingFunction;

        /** The mean earth radius in kilometers. */
        private final float mBaseRadiusKm;

        /**
         * The height of the ionospheric layer, measured from the surface of the earth in
         * kilometers.
         */
        private final float mHeightKm;

        /** The definition of the latitude and longitude axes for the grid. */
        @NonNull private final Axes mAxesInfo;

        private Header(Builder builder) {
            Preconditions.checkArgumentInRange(
                    builder.mMappingFunction,
                    MAPPING_FUNCTION_NONE,
                    MAPPING_FUNCTION_QFAC,
                    "MappingFunction");
            Preconditions.checkArgument(
                    builder.mBaseRadiusKm > 0, "Base radius should be positive");
            Preconditions.checkArgument(builder.mHeightKm > 0, "Height should be positive");
            Preconditions.checkNotNull(builder.mAxesInfo, "Axes info cannot be null");
            mMappingFunction = builder.mMappingFunction;
            mBaseRadiusKm = builder.mBaseRadiusKm;
            mHeightKm = builder.mHeightKm;
            mAxesInfo = builder.mAxesInfo;
        }

        /**
         * Returns the mapping function adopted for TEC determination. e.g. MAPPING_FUNCTION_NONE
         * (no mapping function), MAPPING_FUNCTION_COSZ (1/cos(z)).
         */
        @MappingFunction
        public int getMappingFunction() {
            return mMappingFunction;
        }

        /** Returns the mean earth radius in kilometers. */
        @FloatRange(from = 0.0, fromInclusive = false)
        public float getBaseRadiusKm() {
            return mBaseRadiusKm;
        }

        /**
         * Returns height of the ionospheric layer, measured from the surface of the earth in
         * kilometers.
         */
        @FloatRange(from = 0.0, fromInclusive = false)
        public float getHeightKm() {
            return mHeightKm;
        }

        /** Returns the definition of the latitude and longitude axes for the grid. */
        @NonNull
        public Axes getAxesInfo() {
            return mAxesInfo;
        }

        public static final @NonNull Creator<Header> CREATOR =
                new Creator<Header>() {
                    @Override
                    @NonNull
                    public Header createFromParcel(Parcel in) {
                        return new Builder()
                                .setMappingFunction(in.readInt())
                                .setBaseRadiusKm(in.readFloat())
                                .setHeightKm(in.readFloat())
                                .setAxesInfo(in.readTypedObject(Axes.CREATOR))
                                .build();
                    }

                    @Override
                    public Header[] newArray(int size) {
                        return new Header[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeInt(mMappingFunction);
            parcel.writeFloat(mBaseRadiusKm);
            parcel.writeFloat(mHeightKm);
            parcel.writeTypedObject(mAxesInfo, flags);
        }

        @Override
        @NonNull
        public String toString() {
            StringBuilder builder = new StringBuilder("Header[");
            builder.append("mappingFunction=").append(mMappingFunction);
            builder.append(", baseRadiusKm=").append(mBaseRadiusKm);
            builder.append(", heightKm=").append(mHeightKm);
            builder.append(", axesInfo=").append(mAxesInfo);
            builder.append("]");
            return builder.toString();
        }

        /** Builder for {@link Header}. */
        public static final class Builder {
            private int mMappingFunction;
            private float mBaseRadiusKm;
            private float mHeightKm;
            private Axes mAxesInfo;

            /** Sets the mapping function adopted for TEC determination. */
            @NonNull
            public Builder setMappingFunction(@MappingFunction int mappingFunction) {
                mMappingFunction = mappingFunction;
                return this;
            }

            /** Sets the mean earth radius in kilometers. */
            @NonNull
            public Builder setBaseRadiusKm(
                    @FloatRange(from = 0.0, fromInclusive = false) float baseRadiusKm) {
                mBaseRadiusKm = baseRadiusKm;
                return this;
            }

            /**
             * Sets the height of the ionospheric layer, measured from the surface of the earth in
             * kilometers.
             */
            @NonNull
            public Builder setHeightKm(
                    @FloatRange(from = 0.0, fromInclusive = false) float heightKm) {
                mHeightKm = heightKm;
                return this;
            }

            /** Sets the definition of the latitude and longitude axes for the grid. */
            @NonNull
            public Builder setAxesInfo(@NonNull Axes axesInfo) {
                mAxesInfo = axesInfo;
                return this;
            }

            /** Builds a {@link Header} instance as specified by this builder. */
            @NonNull
            public Header build() {
                return new Header(this);
            }
        }
    }

    /**
     * Defines the limits and resolution of a single grid axis (latitude or longitude).
     *
     * <p>For example, an axis with the sequence of points {@code [87.5, 85.0, ..., -87.5]} is
     * defined by:
     *
     * <ul>
     *   <li>startDeg: 87.5
     *   <li>deltaDeg: -2.5
     *   <li>numPoints: 71
     * </ul>
     */
    public static final class Axis implements Parcelable {
        /** The starting value of the axis, in degrees. */
        private final double mStartDeg;

        /** The step size between grid points, in degrees. May be negative. */
        private final double mDeltaDeg;

        /** The total number of grid points along this axis. */
        private final int mNumPoints;

        /**
         * Constructs an {@link Axis} instance.
         *
         * @param startDeg The starting value of the axis, in degrees.
         * @param deltaDeg The step size between grid points, in degrees. May be negative.
         * @param numPoints The total number of grid points along this axis. Must be positive.
         */
        public Axis(
                @FloatRange(from = -180.0f, to = 180.0f) double startDeg,
                @FloatRange(from = -180.0f, to = 180.0f) double deltaDeg,
                @IntRange(from = 0) int numPoints) {
            Preconditions.checkArgument(
                    startDeg >= -180.0f && startDeg <= 180.0f, "startDeg out of range");
            Preconditions.checkArgument(
                    deltaDeg >= -180.0f && deltaDeg <= 180.0f, "deltaDeg out of range");
            Preconditions.checkArgument(numPoints > 0, "Number of points should be positive");
            this.mStartDeg = startDeg;
            this.mDeltaDeg = deltaDeg;
            this.mNumPoints = numPoints;
        }

        /** Returns the starting value of the axis, in degrees. */
        @FloatRange(from = -180.0f, to = 180.0f)
        public double getStartDeg() {
            return mStartDeg;
        }

        /** Returns the step size between grid points, in degrees. This may be negative. */
        @FloatRange(from = -180.0f, to = 180.0f)
        public double getDeltaDeg() {
            return mDeltaDeg;
        }

        /** Returns the total number of grid points along this axis. */
        @IntRange(from = 1)
        public int getNumPoints() {
            return mNumPoints;
        }

        public static final @NonNull Creator<Axis> CREATOR =
                new Creator<Axis>() {
                    @Override
                    @NonNull
                    public Axis createFromParcel(Parcel in) {
                        double startDeg = in.readDouble();
                        double deltaDeg = in.readDouble();
                        int numPoints = in.readInt();
                        return new Axis(startDeg, deltaDeg, numPoints);
                    }

                    @Override
                    public Axis[] newArray(int size) {
                        return new Axis[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeDouble(mStartDeg);
            parcel.writeDouble(mDeltaDeg);
            parcel.writeInt(mNumPoints);
        }

        @Override
        @NonNull
        public String toString() {
            StringBuilder builder = new StringBuilder("Axis[");
            builder.append("startDeg=").append(mStartDeg);
            builder.append(", deltaDeg=").append(mDeltaDeg);
            builder.append(", numPoints=").append(mNumPoints);
            builder.append("]");
            return builder.toString();
        }
    }

    /** Defines the geographic latitude-longitude axes for the TEC maps. */
    public static final class Axes implements Parcelable {

        /** The definition for the latitude axis. */
        @NonNull private final Axis mLatitudeAxis;

        /** The definition for the longitude axis. */
        @NonNull private final Axis mLongitudeAxis;

        /**
         * Constructs an {@link Axes} instance.
         *
         * @param latitudeAxis An {@link Axis} object defining the latitude points. Must not be
         *     null.
         * @param longitudeAxis An {@link Axis} object defining the longitude points. Must not be
         *     null.
         */
        public Axes(@NonNull Axis latitudeAxis, @NonNull Axis longitudeAxis) {
            Preconditions.checkNotNull(latitudeAxis, "Latitude axis cannot be null.");
            Preconditions.checkNotNull(longitudeAxis, "Longitude axis cannot be null.");
            // Check if the corner points of the grid are within the valid range
            // of latitude [-90, 90] and longitude [-180, 180]
            double latStart = latitudeAxis.getStartDeg();
            double latEnd =
                    latStart + (latitudeAxis.getNumPoints() - 1) * latitudeAxis.getDeltaDeg();
            Preconditions.checkArgument(
                    latStart >= -90.0 && latStart <= 90.0,
                    "Latitude start %.1f out of range [-90, 90]",
                    latStart);
            Preconditions.checkArgument(
                    latEnd >= -90.0 && latEnd <= 90.0,
                    "Latitude end %.1f out of range [-90, 90]",
                    latEnd);
            double lonStart = longitudeAxis.getStartDeg();
            double lonEnd =
                    lonStart + (longitudeAxis.getNumPoints() - 1) * longitudeAxis.getDeltaDeg();
            // The Axis constructor already checks lonStart is within [-180, 180].
            Preconditions.checkArgument(
                    lonEnd >= -180.0 && lonEnd <= 180.0,
                    "Longitude end %.1f out of range [-180, 180]",
                    lonEnd);
            this.mLatitudeAxis = latitudeAxis;
            this.mLongitudeAxis = longitudeAxis;
        }

        /** Returns the definition for the latitude axis. */
        @NonNull
        public Axis getLatitudeAxis() {
            return mLatitudeAxis;
        }

        /** Returns the definition for the longitude axis. */
        @NonNull
        public Axis getLongitudeAxis() {
            return mLongitudeAxis;
        }

        public static final @NonNull Creator<Axes> CREATOR =
                new Creator<Axes>() {
                    @Override
                    @NonNull
                    public Axes createFromParcel(Parcel in) {
                        Axis latitudeAxis = in.readTypedObject(Axis.CREATOR);
                        Axis longitudeAxis = in.readTypedObject(Axis.CREATOR);
                        return new Axes(latitudeAxis, longitudeAxis);
                    }

                    @Override
                    public Axes[] newArray(int size) {
                        return new Axes[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeTypedObject(mLatitudeAxis, flags);
            parcel.writeTypedObject(mLongitudeAxis, flags);
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder("Axes[");
            builder.append("latitudeAxis=").append(mLatitudeAxis);
            builder.append(", longitudeAxis=").append(mLongitudeAxis);
            builder.append("]");
            return builder.toString();
        }
    }

    /** Represents a Total Electron Content (TEC) map at a specific moment in time. */
    public static final class TecMapSnapshot implements Parcelable {
        /**
         * Represents a non-available TEC value in the TEC map. This value is stored in the {@code
         * short[]} arrays.
         *
         * <p>The value 9999 is defined by the IONEX specification for non-available data.
         */
        @SuppressLint("NoByteOrShort")
        public static final short UNAVAILABLE_TEC = 9999;

        /** The epoch of the TEC map, in seconds since the Unix epoch (UTC) */
        private final long mEpochTimeSeconds;

        /**
         * A flattened representation of a 2D geographical map of Total Electron Content values.
         *
         * <p>The values in this array are stored as 16-bit signed integers (short). However, valid
         * TEC values are <strong>always non-negative</strong>.
         *
         * <p><strong>Value Range:</strong> 0 to {@link #UNAVAILABLE_TEC} (9999).
         *
         * <p><strong>Units:</strong> The values use a <strong>unit of 0.1 TECU</strong> (Total
         * Electron Content Unit). To obtain the value in TECU, divide the stored value by 10.
         *
         * <p>1 TECU = 10¹⁶ electrons/m².
         *
         * <p>The ionospheric delay, in meters, of a signal propagating from the zenith is given by
         * the following formula:
         *
         * <pre>
         * Delay = (40.3 / f²) * (VTEC * 10¹⁶)
         * </pre>
         *
         * Where:
         *
         * <ul>
         *   <li><i>f</i> is the signal frequency in Hz.
         *   <li><i>VTEC</i> is the Vertical Total Electron Content in TECU.
         *   <li>The constant 40.3 is in m³/s².
         * </ul>
         *
         * <p>See: "NTCM-G Ionospheric Model Description." (2022)
         *
         * <h3>Data Organization</h3>
         *
         * The data is organized in <strong>row-major order</strong> (latitudes are rows, longitudes
         * are columns): <br>
         * {@code (lat1, lon1), (lat1, lon2), ..., (lat1, lonN), (lat2, lon1), ...}
         *
         * <p>The total number of values in the list must be equal to: <br>
         * {@code latitudeAxis.numPoints * longitudeAxis.numPoints}
         *
         * <p>Non-available TEC values are represented as {@link #UNAVAILABLE_TEC}.
         *
         * <h3>Index Calculation</h3>
         *
         * To compute the latitude and longitude for a given zero-based index {@code i}:
         *
         * <pre>
         * n = longitudeAxis.numPoints;
         * latitude_deg  = latitudeAxis.startDeg + (i / n) * latitudeAxis.deltaDeg;
         * longitude_deg = longitudeAxis.startDeg + (i % n) * longitudeAxis.deltaDeg;
         * </pre>
         */
        @NonNull private final short[] mTecMap;

        /**
         * An optional flattened 2D list of TEC Root-Mean-Square (RMS) error values in 0.1 TECU.
         * Values are formatted exactly in the same way as TEC values.
         *
         * <p>If RMS data is not available, this array will be empty.
         */
        @NonNull private final short[] mRmsMap;

        private TecMapSnapshot(Builder builder) {
            Preconditions.checkArgument(
                    builder.mEpochTimeSeconds >= 0, "Epoch time must be non-negative");
            Preconditions.checkNotNull(builder.mTecMap, "TEC map cannot be null");
            mEpochTimeSeconds = builder.mEpochTimeSeconds;
            // Defensive copy to ensure immutability
            mTecMap = Arrays.copyOf(builder.mTecMap, builder.mTecMap.length);
            mRmsMap = Arrays.copyOf(builder.mRmsMap, builder.mRmsMap.length);
            // Validate the copied data
            validateValues(mTecMap, "mTecMap");
            validateValues(mRmsMap, "mRmsMap");
        }

        /** Helper to validate that all values are within [0, 9999] */
        private static void validateValues(short[] map, String fieldName) {
            for (int i = 0; i < map.length; i++) {
                short value = map[i];
                if (value < 0 || value > UNAVAILABLE_TEC) {
                    throw new IllegalArgumentException(
                            fieldName
                                    + " contains invalid value "
                                    + value
                                    + " at index "
                                    + i
                                    + ". Expected range: [0, "
                                    + UNAVAILABLE_TEC
                                    + "]");
                }
            }
        }

        /** Returns the epoch of the TEC map, in seconds since the Unix epoch (UTC). */
        @IntRange(from = 0)
        public long getEpochTimeSeconds() {
            return mEpochTimeSeconds;
        }

        /**
         * Returns the flattened array of values in 0.1 Total Electron Content units (TECU).
         *
         * <p>See {@link TecMapSnapshot#mTecMap} for details on data organization and
         * interpretation.
         */
        @NonNull
        public short[] getTecMap() {
            return mTecMap;
        }

        /**
         * Returns the array of TEC Root-Mean-Square (RMS) error values in 0.1 TECU.
         *
         * <p>If RMS data is not available, this array will be empty.
         */
        @NonNull
        public short[] getRmsMap() {
            return mRmsMap;
        }

        public static final @NonNull Creator<TecMapSnapshot> CREATOR =
                new Creator<TecMapSnapshot>() {
                    @Override
                    @NonNull
                    public TecMapSnapshot createFromParcel(Parcel in) {
                        return new Builder()
                                .setEpochTimeSeconds(in.readLong())
                                .setTecMap(in.createShortArray())
                                .setRmsMap(in.createShortArray())
                                .build();
                    }

                    @Override
                    public TecMapSnapshot[] newArray(int size) {
                        return new TecMapSnapshot[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeLong(mEpochTimeSeconds);
            parcel.writeShortArray(mTecMap);
            parcel.writeShortArray(mRmsMap);
        }

        @Override
        @NonNull
        public String toString() {
            StringBuilder builder = new StringBuilder("TecMapSnapshot[");
            builder.append("epochTimeSeconds=").append(mEpochTimeSeconds);
            builder.append(", tecMap.length=").append(mTecMap.length);
            builder.append(", rmsMap.length=").append(mRmsMap.length);
            builder.append("]");
            return builder.toString();
        }

        /** Builder for {@link TecMapSnapshot}. */
        public static final class Builder {
            private long mEpochTimeSeconds;
            private short[] mTecMap;
            private short[] mRmsMap = new short[0];

            /** Sets the epoch of the TEC map, in seconds since the Unix epoch (UTC). */
            @NonNull
            @IntRange(from = 0)
            public Builder setEpochTimeSeconds(@IntRange(from = 0) long epochTimeSeconds) {
                mEpochTimeSeconds = epochTimeSeconds;
                return this;
            }

            /**
             * A flattened representation of a 2D geographical map of Total Electron Content values.
             *
             * <p>The values in this array are stored as 16-bit signed integers (short). However,
             * valid TEC values are <strong>always non-negative</strong>.
             *
             * <p><strong>Value Range:</strong> 0 to {@link #UNAVAILABLE_TEC} (9999).
             *
             * <p><strong>Units:</strong> The values use a <strong>unit of 0.1 TECU</strong> (Total
             * Electron Content Unit). To obtain the value in TECU, divide the stored value by 10.
             *
             * <p>1 TECU = 10¹⁶ electrons/m².
             *
             * <p>The ionospheric delay, in meters, of a signal propagating from the zenith is given
             * by the following formula:
             *
             * <pre>
             * Delay = (40.3 / f²) * (VTEC * 10¹⁶)
             * </pre>
             *
             * Where:
             *
             * <ul>
             *   <li><i>f</i> is the signal frequency in Hz.
             *   <li><i>VTEC</i> is the Vertical Total Electron Content in TECU.
             *   <li>The constant 40.3 is in m³/s².
             * </ul>
             *
             * <p>See: "NTCM-G Ionospheric Model Description." (2022)
             *
             * <h3>Data Organization</h3>
             *
             * The data is organized in <strong>row-major order</strong> (latitudes are rows,
             * longitudes are columns): <br>
             * {@code (lat1, lon1), (lat1, lon2), ..., (lat1, lonN), (lat2, lon1), ...}
             *
             * <p>The total number of values in the list must be equal to: <br>
             * {@code latitudeAxis.numPoints * longitudeAxis.numPoints}
             *
             * <p>Non-available TEC values are represented as {@link #UNAVAILABLE_TEC}.
             *
             * <h3>Index Calculation</h3>
             *
             * To compute the latitude and longitude for a given zero-based index {@code i}:
             *
             * <pre>
             * n = longitudeAxis.numPoints;
             * latitude_deg  = latitudeAxis.startDeg + (i / n) * latitudeAxis.deltaDeg;
             * longitude_deg = longitudeAxis.startDeg + (i % n) * longitudeAxis.deltaDeg;
             * </pre>
             */
            @NonNull
            public Builder setTecMap(@NonNull short[] tecMap) {
                mTecMap = tecMap;
                return this;
            }

            /**
             * Sets the optional, flattened array of TEC Root-Mean-Square (RMS) error values in 0.1
             * TECU.
             */
            @NonNull
            public Builder setRmsMap(@NonNull short[] rmsMap) {
                mRmsMap = rmsMap;
                return this;
            }

            /** Builds a {@link TecMapSnapshot} instance as specified by this builder. */
            @NonNull
            public TecMapSnapshot build() {
                return new TecMapSnapshot(this);
            }
        }
    }
}
