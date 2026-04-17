/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.location.GnssMeasurement.CodeType;
import android.location.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/**
 * This class represents the current state of the GNSS engine and is used in conjunction with {@link
 * GnssStatus.Callback}.
 *
 * @see LocationManager#registerGnssStatusCallback
 * @see GnssStatus.Callback
 */
public final class GnssStatus implements Parcelable {

    // These must match the definitions in GNSS HAL.
    //
    // Note: these constants are also duplicated in GnssStatusCompat.java in the androidx support
    // library. if adding a constellation, please update that file as well.

    /** Unknown constellation type. */
    public static final int CONSTELLATION_UNKNOWN = 0;

    /** Constellation type constant for GPS. */
    public static final int CONSTELLATION_GPS = 1;

    /** Constellation type constant for SBAS. */
    public static final int CONSTELLATION_SBAS = 2;

    /** Constellation type constant for Glonass. */
    public static final int CONSTELLATION_GLONASS = 3;

    /** Constellation type constant for QZSS. */
    public static final int CONSTELLATION_QZSS = 4;

    /** Constellation type constant for Beidou. */
    public static final int CONSTELLATION_BEIDOU = 5;

    /** Constellation type constant for Galileo. */
    public static final int CONSTELLATION_GALILEO = 6;

    /** Constellation type constant for IRNSS. */
    public static final int CONSTELLATION_IRNSS = 7;

    /** @hide */
    public static final int CONSTELLATION_COUNT = 8;

    private static final int SVID_FLAGS_NONE = 0;
    private static final int SVID_FLAGS_HAS_EPHEMERIS_DATA = (1 << 0);
    private static final int SVID_FLAGS_HAS_ALMANAC_DATA = (1 << 1);
    private static final int SVID_FLAGS_USED_IN_FIX = (1 << 2);
    private static final int SVID_FLAGS_HAS_CARRIER_FREQUENCY = (1 << 3);
    private static final int SVID_FLAGS_HAS_BASEBAND_CN0 = (1 << 4);
    private static final int SVID_FLAGS_HAS_CODE_TYPE = (1 << 5);
    private static final int SVID_FLAGS_HAS_ELAPSED_REALTIME_NANOS = (1 << 6);
    private static final int SVID_FLAGS_HAS_ELAPSED_REALTIME_UNCERTAINTY_NANOS = (1 << 7);


    /**
     * Used for receiving notifications when GNSS events happen.
     *
     * @see LocationManager#registerGnssStatusCallback
     */
    public abstract static class Callback {
        /** Called when GNSS system has started. */
        public void onStarted() {}

        /** Called when GNSS system has stopped. */
        public void onStopped() {}

        /**
         * Called when the GNSS system has received its first fix since starting.
         *
         * @param ttffMillis the time from start to first fix in milliseconds.
         */
        public void onFirstFix(int ttffMillis) {}

        /**
         * Called periodically to report GNSS satellite status.
         *
         * @param status the current status of all satellites.
         */
        public void onSatelliteStatusChanged(@NonNull GnssStatus status) {}
    }

    /**
     * Constellation type.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        CONSTELLATION_UNKNOWN,
        CONSTELLATION_GPS,
        CONSTELLATION_SBAS,
        CONSTELLATION_GLONASS,
        CONSTELLATION_QZSS,
        CONSTELLATION_BEIDOU,
        CONSTELLATION_GALILEO,
        CONSTELLATION_IRNSS
    })
    public @interface ConstellationType {}

    /**
     * Create a GnssStatus that wraps the given arguments without any additional overhead. Callers
     * are responsible for guaranteeing that the arguments are never modified after calling this
     * method.
     *
     * @param svCount The total number of satellites in the list.
     * @param svFlags Array of boolean flags for each satellite.
     * @param svids Array of SVID.
     * @param constellationTypes Array of constellation type.
     * @param cn0DbHzs Array of carrier-to-noise density values at the antenna in dB-Hz for each
     *     satellite.
     * @param elevations Array of satellite elevation values in degrees for each satellite.
     * @param azimuths Array of satellite azimuth values in degrees for each satellite.
     * @param carrierFrequencies Array of satellite carrier frequency values in Hz for each
     *     satellite.
     * @param basebandCn0DbHzs Array of baseband carrier-to-noise density values in dB-Hz for each
     *     satellite.
     * @param codetypes Array of satellite code types (as defined in {@link
     *     GnssMeasurement#getCodeType()}) for each satellite.
     * @param elapsedRealtimeNanos Array of elapsed real-time values since system boot in
     *     nanoseconds for each satellite.
     * @param elapsedRealtimeUncertaintyNanos Array of elapsed real-time uncertainty values in
     *     nanoseconds for each satellite.
     * @return A new {@link GnssStatus} object wrapping the provided data.
     * @hide
     */
    @NonNull
    public static GnssStatus create(
            int svCount,
            int[] svFlags,
            int[] svids,
            int[] constellationTypes,
            float[] cn0DbHzs,
            float[] elevations,
            float[] azimuths,
            float[] carrierFrequencies,
            float[] basebandCn0DbHzs,
            String[] codetypes,
            long[] elapsedRealtimeNanos,
            double[] elapsedRealtimeUncertaintyNanos) {
        Preconditions.checkState(svCount >= 0);
        Preconditions.checkState(svFlags.length >= svCount);
        Preconditions.checkState(svids.length >= svCount);
        Preconditions.checkState(constellationTypes.length >= svCount);
        Preconditions.checkState(elevations.length >= svCount);
        Preconditions.checkState(azimuths.length >= svCount);
        Preconditions.checkState(carrierFrequencies.length >= svCount);
        Preconditions.checkState(basebandCn0DbHzs.length >= svCount);
        Preconditions.checkState(codetypes.length >= svCount);
        Preconditions.checkState(elapsedRealtimeNanos.length >= svCount);
        Preconditions.checkState(elapsedRealtimeUncertaintyNanos.length >= svCount);

        return new GnssStatus(
                svCount,
                svFlags,
                svids,
                constellationTypes,
                cn0DbHzs,
                elevations,
                azimuths,
                carrierFrequencies,
                basebandCn0DbHzs,
                codetypes,
                elapsedRealtimeNanos,
                elapsedRealtimeUncertaintyNanos);
    }

    private final int mSvCount;
    private final int[] mSvFlags;
    private final int[] mSvids;
    private final int[] mConstellationTypes;
    private final float[] mCn0DbHzs;
    private final float[] mElevations;
    private final float[] mAzimuths;
    private final float[] mCarrierFrequencies;
    private final float[] mBasebandCn0DbHzs;
    private final String[] mCodeTypes;
    private final long[] mElapsedRealtimeNanos;
    private final double[] mElapsedRealtimeUncertaintyNanos;

    private GnssStatus(
            int svCount,
            int[] svidFlags,
            int[] svids,
            int[] constellationTypes,
            float[] cn0DbHzs,
            float[] elevations,
            float[] azimuths,
            float[] carrierFrequencies,
            float[] basebandCn0DbHzs,
            String[] codetypes,
            long[] elapsedRealtimeNanos,
            double[] elapsedRealtimeUncertaintyNanos) {
        mSvCount = svCount;
        mSvFlags = svidFlags;
        mSvids = svids;
        mConstellationTypes = constellationTypes;
        mCn0DbHzs = cn0DbHzs;
        mElevations = elevations;
        mAzimuths = azimuths;
        mCarrierFrequencies = carrierFrequencies;
        mBasebandCn0DbHzs = basebandCn0DbHzs;
        mCodeTypes = codetypes;
        mElapsedRealtimeNanos = elapsedRealtimeNanos;
        mElapsedRealtimeUncertaintyNanos = elapsedRealtimeUncertaintyNanos;
    }

    /** Gets the total number of satellites in satellite list. */
    @IntRange(from = 0)
    public int getSatelliteCount() {
        return mSvCount;
    }

    /**
     * Retrieves the constellation type of the satellite at the specified index.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    @ConstellationType
    public int getConstellationType(@IntRange(from = 0) int satelliteIndex) {
        return mConstellationTypes[satelliteIndex];
    }

    /**
     * Gets the identification number for the satellite at the specific index.
     *
     * <p>This svid is pseudo-random number for most constellations. It is FCN &amp; OSN number for
     * Glonass.
     *
     * <p>The distinction is made by looking at constellation field {@link
     * #getConstellationType(int)} Expected values are in the range of:
     *
     * <ul>
     *   <li>GPS: 1-32
     *   <li>SBAS: 120-151, 183-192
     *   <li>GLONASS: One of: OSN, or FCN+100
     *       <ul>
     *         <li>1-25 as the orbital slot number (OSN) (preferred, if known)
     *         <li>93-106 as the frequency channel number (FCN) (-7 to +6) plus 100. i.e. encode FCN
     *             of -7 as 93, 0 as 100, and +6 as 106
     *       </ul>
     *   <li>QZSS: 183-212
     *   <li>Galileo: 1-36
     *   <li>Beidou: 1-63
     *   <li>IRNSS: 1-14
     * </ul>
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    @FlaggedApi(Flags.FLAG_GNSS_QZSS_SVID_RANGE_EXTENSION)
    @IntRange(from = 1)
    public int getSvid(@IntRange(from = 0) int satelliteIndex) {
        return mSvids[satelliteIndex];
    }

    /**
     * Retrieves the carrier-to-noise density at the antenna of the satellite at the specified index
     * in dB-Hz.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    @FloatRange(from = 0, to = 63)
    public float getCn0DbHz(@IntRange(from = 0) int satelliteIndex) {
        return mCn0DbHzs[satelliteIndex];
    }

    /**
     * Retrieves the elevation of the satellite at the specified index.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    @FloatRange(from = -90, to = 90)
    public float getElevationDegrees(@IntRange(from = 0) int satelliteIndex) {
        return mElevations[satelliteIndex];
    }

    /**
     * Retrieves the azimuth the satellite at the specified index.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    @FloatRange(from = 0, to = 360)
    public float getAzimuthDegrees(@IntRange(from = 0) int satelliteIndex) {
        return mAzimuths[satelliteIndex];
    }

    /**
     * Reports whether the satellite at the specified index has ephemeris data.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    public boolean hasEphemerisData(@IntRange(from = 0) int satelliteIndex) {
        return (mSvFlags[satelliteIndex] & SVID_FLAGS_HAS_EPHEMERIS_DATA) != 0;
    }

    /**
     * Reports whether the satellite at the specified index has almanac data.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    public boolean hasAlmanacData(@IntRange(from = 0) int satelliteIndex) {
        return (mSvFlags[satelliteIndex] & SVID_FLAGS_HAS_ALMANAC_DATA) != 0;
    }

    /**
     * Reports whether the satellite at the specified index was used in the calculation of the most
     * recent position fix.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    public boolean usedInFix(@IntRange(from = 0) int satelliteIndex) {
        return (mSvFlags[satelliteIndex] & SVID_FLAGS_USED_IN_FIX) != 0;
    }

    /**
     * Reports whether a valid {@link #getCarrierFrequencyHz(int satelliteIndex)} is available.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    public boolean hasCarrierFrequencyHz(@IntRange(from = 0) int satelliteIndex) {
        return (mSvFlags[satelliteIndex] & SVID_FLAGS_HAS_CARRIER_FREQUENCY) != 0;
    }

    /**
     * Gets the carrier frequency of the signal tracked.
     *
     * <p>For example it can be the GPS central frequency for L1 = 1575.45 MHz, or L2 = 1227.60 MHz,
     * L5 = 1176.45 MHz, varying GLO channels, etc.
     *
     * <p>The value is only available if {@link #hasCarrierFrequencyHz(int satelliteIndex)} is
     * {@code true}.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    @FloatRange(from = 0)
    public float getCarrierFrequencyHz(@IntRange(from = 0) int satelliteIndex) {
        return mCarrierFrequencies[satelliteIndex];
    }

    /**
     * Reports whether a valid {@link #getBasebandCn0DbHz(int satelliteIndex)} is available.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    public boolean hasBasebandCn0DbHz(@IntRange(from = 0) int satelliteIndex) {
        return (mSvFlags[satelliteIndex] & SVID_FLAGS_HAS_BASEBAND_CN0) != 0;
    }

    /**
     * Retrieves the baseband carrier-to-noise density of the satellite at the specified index in
     * dB-Hz.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    @FloatRange(from = 0, to = 63)
    public float getBasebandCn0DbHz(@IntRange(from = 0) int satelliteIndex) {
        return mBasebandCn0DbHzs[satelliteIndex];
    }

    /**
     * Reports whether a valid {@link #getCodeType()} is available.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    @FlaggedApi(Flags.FLAG_SUPPORT_CODETYPE_IN_GNSS_STATUS)
    public boolean hasCodeType(@IntRange(from = 0) int satelliteIndex) {
        return (mSvFlags[satelliteIndex] & SVID_FLAGS_HAS_CODE_TYPE) != 0;
    }

    /**
     * Gets the code type as defined in {@link GnssMeasurement#getCodeType()} of the satellite at
     * the specified index.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     */
    @NonNull
    @CodeType
    @FlaggedApi(Flags.FLAG_SUPPORT_CODETYPE_IN_GNSS_STATUS)
    public String getCodeType(@IntRange(from = 0) int satelliteIndex) {
        return hasCodeType(satelliteIndex)
                ? mCodeTypes[satelliteIndex]
                : GnssMeasurement.CODE_TYPE_UNKNOWN;
    }

    /**
     * Returns {@code true} if {@link #getElapsedRealtimeNanos()} is available, {@code false}
     * otherwise.
     */
    @FlaggedApi(Flags.FLAG_SUPPORT_CODETYPE_IN_GNSS_STATUS)
    public boolean hasElapsedRealtimeNanos(@IntRange(from = 0) int satelliteIndex) {
        return (mSvFlags[satelliteIndex] & SVID_FLAGS_HAS_ELAPSED_REALTIME_NANOS) != 0;
    }

    /**
     * Returns the elapsed real-time of this GnssStatus since system boot, in nanoseconds.
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     *     <p>The value is only available if {@link #hasElapsedRealtimeNanos()} is {@code true}.
     */
    @FlaggedApi(Flags.FLAG_SUPPORT_CODETYPE_IN_GNSS_STATUS)
    @IntRange(from = 0)
    public long getElapsedRealtimeNanos(@IntRange(from = 0) int satelliteIndex) {
        return mElapsedRealtimeNanos[satelliteIndex];
    }

    /**
     * Returns {@code true} if {@link #getElapsedRealtimeUncertaintyNanos()} is available, {@code
     * false} otherwise.
     */
    @FlaggedApi(Flags.FLAG_SUPPORT_CODETYPE_IN_GNSS_STATUS)
    public boolean hasElapsedRealtimeUncertaintyNanos(@IntRange(from = 0) int satelliteIndex) {
        return (mSvFlags[satelliteIndex] & SVID_FLAGS_HAS_ELAPSED_REALTIME_UNCERTAINTY_NANOS)
                != 0;
    }

    /**
     * Returns the estimate of the relative precision of the alignment of the {@link
     * #getElapsedRealtimeNanos()} timestamp, with the reported GnssStatus in nanoseconds (68%
     * confidence).
     *
     * @param satelliteIndex An index from zero to {@link #getSatelliteCount()} - 1
     *     <p>The value is only available if {@link #hasElapsedRealtimeUncertaintyNanos()} is {@code
     *     true}.
     */
    @FlaggedApi(Flags.FLAG_SUPPORT_CODETYPE_IN_GNSS_STATUS)
    @FloatRange(from = 0.0f)
    public double getElapsedRealtimeUncertaintyNanos(@IntRange(from = 0) int satelliteIndex) {
        return mElapsedRealtimeUncertaintyNanos[satelliteIndex];
    }

    /**
     * Returns the string representation of a constellation type.
     *
     * @param constellationType the constellation type.
     * @return the string representation.
     * @hide
     */
    @NonNull
    public static String constellationTypeToString(@ConstellationType int constellationType) {
        switch (constellationType) {
            case CONSTELLATION_UNKNOWN:
                return "UNKNOWN";
            case CONSTELLATION_GPS:
                return "GPS";
            case CONSTELLATION_SBAS:
                return "SBAS";
            case CONSTELLATION_GLONASS:
                return "GLONASS";
            case CONSTELLATION_QZSS:
                return "QZSS";
            case CONSTELLATION_BEIDOU:
                return "BEIDOU";
            case CONSTELLATION_GALILEO:
                return "GALILEO";
            case CONSTELLATION_IRNSS:
                return "IRNSS";
            default:
                return Integer.toString(constellationType);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GnssStatus)) {
            return false;
        }

        GnssStatus that = (GnssStatus) o;
        return mSvCount == that.mSvCount
                && Arrays.equals(mSvFlags, that.mSvFlags)
                && Arrays.equals(mSvids, that.mSvids)
                && Arrays.equals(mConstellationTypes, that.mConstellationTypes)
                && Arrays.equals(mCn0DbHzs, that.mCn0DbHzs)
                && Arrays.equals(mElevations, that.mElevations)
                && Arrays.equals(mAzimuths, that.mAzimuths)
                && Arrays.equals(mCarrierFrequencies, that.mCarrierFrequencies)
                && Arrays.equals(mBasebandCn0DbHzs, that.mBasebandCn0DbHzs)
                && Arrays.equals(mCodeTypes, that.mCodeTypes)
                && Arrays.equals(mElapsedRealtimeNanos, that.mElapsedRealtimeNanos)
                && Arrays.equals(
                        mElapsedRealtimeUncertaintyNanos, that.mElapsedRealtimeUncertaintyNanos);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mSvCount);
        result = 31 * result + Arrays.hashCode(mSvids);
        result = 31 * result + Arrays.hashCode(mCn0DbHzs);
        return result;
    }

    public static final @NonNull Creator<GnssStatus> CREATOR =
            new Creator<GnssStatus>() {
                @Override
                public GnssStatus createFromParcel(Parcel in) {
                    int svCount = in.readInt();
                    int[] svFlags = new int[svCount];
                    int[] svids = new int[svCount];
                    int[] constellationTypes = new int[svCount];
                    float[] cn0DbHzs = new float[svCount];
                    float[] elevations = new float[svCount];
                    float[] azimuths = new float[svCount];
                    float[] carrierFrequencies = new float[svCount];
                    float[] basebandCn0DbHzs = new float[svCount];
                    String[] codeTypes = new String[svCount];
                    long[] elapsedRealtimeNanos = new long[svCount];
                    double[] elapsedRealtimeUncertaintyNanos = new double[svCount];

                    for (int i = 0; i < svCount; i++) {
                        svFlags[i] = in.readInt();
                        svids[i] = in.readInt();
                        constellationTypes[i] = in.readInt();
                        cn0DbHzs[i] = in.readFloat();
                        elevations[i] = in.readFloat();
                        azimuths[i] = in.readFloat();
                        carrierFrequencies[i] = in.readFloat();
                        basebandCn0DbHzs[i] = in.readFloat();
                        codeTypes[i] = in.readString8();
                        elapsedRealtimeNanos[i] = in.readLong();
                        elapsedRealtimeUncertaintyNanos[i] = in.readDouble();
                    }

                    return new GnssStatus(
                            svCount,
                            svFlags,
                            svids,
                            constellationTypes,
                            cn0DbHzs,
                            elevations,
                            azimuths,
                            carrierFrequencies,
                            basebandCn0DbHzs,
                            codeTypes,
                            elapsedRealtimeNanos,
                            elapsedRealtimeUncertaintyNanos);
                }

                @Override
                public GnssStatus[] newArray(int size) {
                    return new GnssStatus[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mSvCount);
        for (int i = 0; i < mSvCount; i++) {
            parcel.writeInt(mSvFlags[i]);
            parcel.writeInt(mSvids[i]);
            parcel.writeInt(mConstellationTypes[i]);
            parcel.writeFloat(mCn0DbHzs[i]);
            parcel.writeFloat(mElevations[i]);
            parcel.writeFloat(mAzimuths[i]);
            parcel.writeFloat(mCarrierFrequencies[i]);
            parcel.writeFloat(mBasebandCn0DbHzs[i]);
            parcel.writeString8(mCodeTypes[i]);
            parcel.writeLong(mElapsedRealtimeNanos[i]);
            parcel.writeDouble(mElapsedRealtimeUncertaintyNanos[i]);
        }
    }

    /** Builder class to help create new GnssStatus instances. */
    public static final class Builder {

        private final ArrayList<GnssSvInfo> mSatellites = new ArrayList<>();

        /**
         * Adds a new satellite to the Builder.
         *
         * @param constellationType one of the CONSTELLATION_* constants
         * @param svid the space vehicle identifier
         * @param cn0DbHz carrier-to-noise density at the antenna in dB-Hz
         * @param elevation satellite elevation in degrees
         * @param azimuth satellite azimuth in degrees
         * @param hasEphemeris whether the satellite has ephemeris data
         * @param hasAlmanac whether the satellite has almanac data
         * @param usedInFix whether the satellite was used in the most recent location fix
         * @param hasCarrierFrequency whether carrier frequency data is available
         * @param carrierFrequency satellite carrier frequency in Hz
         * @param hasBasebandCn0DbHz whether baseband carrier-to-noise density is available
         * @param basebandCn0DbHz baseband carrier-to-noise density in dB-Hz
         */
        @NonNull
        public Builder addSatellite(
                @ConstellationType int constellationType,
                @IntRange(from = 1, to = 200) int svid,
                @FloatRange(from = 0, to = 63) float cn0DbHz,
                @FloatRange(from = -90, to = 90) float elevation,
                @FloatRange(from = 0, to = 360) float azimuth,
                boolean hasEphemeris,
                boolean hasAlmanac,
                boolean usedInFix,
                boolean hasCarrierFrequency,
                @FloatRange(from = 0) float carrierFrequency,
                boolean hasBasebandCn0DbHz,
                @FloatRange(from = 0, to = 63) float basebandCn0DbHz) {
            mSatellites.add(
                    new GnssSvInfo(
                            constellationType,
                            svid,
                            cn0DbHz,
                            elevation,
                            azimuth,
                            hasEphemeris,
                            hasAlmanac,
                            usedInFix,
                            hasCarrierFrequency,
                            carrierFrequency,
                            hasBasebandCn0DbHz,
                            basebandCn0DbHz,
                            /* hasCodeType= */ false,
                            /* codeType= */ GnssMeasurement.CODE_TYPE_UNKNOWN,
                            /* hasElapsedRealtimeNanos= */ false,
                            /* elapsedRealtimeNanos= */ 0,
                            /* hasElapsedRealtimeUncertaintyNanos= */ false,
                            /* elapsedRealtimeUncertaintyNanos= */ 0));
            return this;
        }

        /**
         * Adds a new satellite to the Builder.
         *
         * @param constellationType one of the CONSTELLATION_* constants
         * @param svid the space vehicle identifier
         * @param cn0DbHz carrier-to-noise density at the antenna in dB-Hz
         * @param elevation satellite elevation in degrees
         * @param azimuth satellite azimuth in degrees
         * @param hasEphemeris whether the satellite has ephemeris data
         * @param hasAlmanac whether the satellite has almanac data
         * @param usedInFix whether the satellite was used in the most recent location fix
         * @param hasCarrierFrequency whether carrier frequency data is available
         * @param carrierFrequency satellite carrier frequency in Hz
         * @param hasBasebandCn0DbHz whether baseband carrier-to-noise density is available
         * @param basebandCn0DbHz baseband carrier-to-noise density in dB-Hz
         * @param hasCodeType whether codetype is available
         * @param codeType the code type as defined in {@link GnssMeasurement#getCodeType()}
         * @param hasElapsedRealtimeNanos whether the elapsedRealtimeNanos is available
         * @param elapsedRealtimeNanos the elapsed real-time of this GnssStatus since system boot,
         *     in nanoseconds
         * @param hasElapsedRealtimeUncertaintyNanos whether the elapsedRealTimeUncertanityNanos is
         *     available
         * @param elapsedRealtimeUncertaintyNanos estimate of the relative precision of the
         *     alignment of this GnssStatus timestamp, with the reported measurements in nanoseconds
         *     (68% confidence)
         */
        @NonNull
        @FlaggedApi(Flags.FLAG_SUPPORT_CODETYPE_IN_GNSS_STATUS)
        public Builder addSatellite(
                @ConstellationType int constellationType,
                @IntRange(from = 1, to = 200) int svid,
                @FloatRange(from = 0, to = 63) float cn0DbHz,
                @FloatRange(from = -90, to = 90) float elevation,
                @FloatRange(from = 0, to = 360) float azimuth,
                boolean hasEphemeris,
                boolean hasAlmanac,
                boolean usedInFix,
                boolean hasCarrierFrequency,
                @FloatRange(from = 0) float carrierFrequency,
                boolean hasBasebandCn0DbHz,
                @FloatRange(from = 0, to = 63) float basebandCn0DbHz,
                boolean hasCodeType,
                @NonNull @CodeType String codeType,
                boolean hasElapsedRealtimeNanos,
                @IntRange(from = 0) long elapsedRealtimeNanos,
                boolean hasElapsedRealtimeUncertaintyNanos,
                @FloatRange(from = 0) double elapsedRealtimeUncertaintyNanos) {
            mSatellites.add(
                    new GnssSvInfo(
                            constellationType,
                            svid,
                            cn0DbHz,
                            elevation,
                            azimuth,
                            hasEphemeris,
                            hasAlmanac,
                            usedInFix,
                            hasCarrierFrequency,
                            carrierFrequency,
                            hasBasebandCn0DbHz,
                            basebandCn0DbHz,
                            hasCodeType,
                            codeType,
                            hasElapsedRealtimeNanos,
                            elapsedRealtimeNanos,
                            hasElapsedRealtimeUncertaintyNanos,
                            elapsedRealtimeUncertaintyNanos));
            return this;
        }

        /** Clears all satellites in the Builder. */
        @NonNull
        public Builder clearSatellites() {
            mSatellites.clear();
            return this;
        }

        /** Builds a new GnssStatus based on the satellite information in the Builder. */
        @NonNull
        public GnssStatus build() {
            int svCount = mSatellites.size();
            int[] svFlags = new int[svCount];
            int[] svids = new int[svCount];
            int[] constellationTypes = new int[svCount];
            float[] cn0DbHzs = new float[svCount];
            float[] elevations = new float[svCount];
            float[] azimuths = new float[svCount];
            float[] carrierFrequencies = new float[svCount];
            float[] basebandCn0DbHzs = new float[svCount];
            String[] codetypes = new String[svCount];
            long[] elapsedRealtimeNanos = new long[svCount];
            double[] elapsedRealtimeUncertaintyNanos = new double[svCount];

            for (int i = 0; i < svFlags.length; i++) {
                svFlags[i] = mSatellites.get(i).mSvFlags;
            }
            for (int i = 0; i < svids.length; i++) {
                svids[i] = mSatellites.get(i).mSvid;
            }
            for (int i = 0; i < constellationTypes.length; i++) {
                constellationTypes[i] = mSatellites.get(i).mConstellationType;
            }
            for (int i = 0; i < cn0DbHzs.length; i++) {
                cn0DbHzs[i] = mSatellites.get(i).mCn0DbHz;
            }
            for (int i = 0; i < elevations.length; i++) {
                elevations[i] = mSatellites.get(i).mElevation;
            }
            for (int i = 0; i < azimuths.length; i++) {
                azimuths[i] = mSatellites.get(i).mAzimuth;
            }
            for (int i = 0; i < carrierFrequencies.length; i++) {
                carrierFrequencies[i] = mSatellites.get(i).mCarrierFrequency;
            }
            for (int i = 0; i < basebandCn0DbHzs.length; i++) {
                basebandCn0DbHzs[i] = mSatellites.get(i).mBasebandCn0DbHz;
            }
            for (int i = 0; i < codetypes.length; i++) {
                codetypes[i] = mSatellites.get(i).mCodeType;
            }
            for (int i = 0; i < elapsedRealtimeNanos.length; i++) {
                elapsedRealtimeNanos[i] = mSatellites.get(i).mElapsedRealtimeNanos;
            }
            for (int i = 0; i < elapsedRealtimeUncertaintyNanos.length; i++) {
                elapsedRealtimeUncertaintyNanos[i] =
                        mSatellites.get(i).mElapsedRealtimeUncertaintyNanos;
            }

            return new GnssStatus(
                    svCount,
                    svFlags,
                    svids,
                    constellationTypes,
                    cn0DbHzs,
                    elevations,
                    azimuths,
                    carrierFrequencies,
                    basebandCn0DbHzs,
                    codetypes,
                    elapsedRealtimeNanos,
                    elapsedRealtimeUncertaintyNanos);
        }
    }

    private static class GnssSvInfo {
        private final int mSvFlags;
        private final int mSvid;
        @ConstellationType private final int mConstellationType;
        private final float mCn0DbHz;
        private final float mElevation;
        private final float mAzimuth;
        private final float mCarrierFrequency;
        private final float mBasebandCn0DbHz;
        @NonNull @CodeType private final String mCodeType;
        private final long mElapsedRealtimeNanos;
        private final double mElapsedRealtimeUncertaintyNanos;

        private GnssSvInfo(
                @ConstellationType int constellationType,
                int svid,
                float cn0DbHz,
                float elevation,
                float azimuth,
                boolean hasEphemeris,
                boolean hasAlmanac,
                boolean usedInFix,
                boolean hasCarrierFrequency,
                float carrierFrequency,
                boolean hasBasebandCn0DbHz,
                float basebandCn0DbHz,
                boolean hasCodeType,
                @NonNull @CodeType String codeType,
                boolean hasElapsedRealtimeNanos,
                long elapsedRealtimeNanos,
                boolean hasElapsedRealtimeUncertaintyNanos,
                double elapsedRealtimeUncertaintyNanos) {
            mSvFlags = (hasEphemeris ? SVID_FLAGS_HAS_EPHEMERIS_DATA : SVID_FLAGS_NONE)
                        | (hasAlmanac ? SVID_FLAGS_HAS_ALMANAC_DATA : SVID_FLAGS_NONE)
                        | (usedInFix ? SVID_FLAGS_USED_IN_FIX : SVID_FLAGS_NONE)
                        | (hasCarrierFrequency
                                ? SVID_FLAGS_HAS_CARRIER_FREQUENCY
                                : SVID_FLAGS_NONE)
                        | (hasBasebandCn0DbHz ? SVID_FLAGS_HAS_BASEBAND_CN0 : SVID_FLAGS_NONE)
                        | (hasCodeType ? SVID_FLAGS_HAS_CODE_TYPE : SVID_FLAGS_NONE)
                        | (hasElapsedRealtimeNanos
                                ? SVID_FLAGS_HAS_ELAPSED_REALTIME_NANOS
                                : SVID_FLAGS_NONE)
                        | (hasElapsedRealtimeUncertaintyNanos
                                ? SVID_FLAGS_HAS_ELAPSED_REALTIME_UNCERTAINTY_NANOS
                                : SVID_FLAGS_NONE);
            mSvid = svid;
            mConstellationType = constellationType;
            mCn0DbHz = cn0DbHz;
            mElevation = elevation;
            mAzimuth = azimuth;
            mCarrierFrequency = hasCarrierFrequency ? carrierFrequency : 0;
            mBasebandCn0DbHz = hasBasebandCn0DbHz ? basebandCn0DbHz : 0;
            mCodeType = hasCodeType ? codeType : GnssMeasurement.CODE_TYPE_UNKNOWN;
            mElapsedRealtimeNanos = hasElapsedRealtimeNanos ? elapsedRealtimeNanos : 0;
            mElapsedRealtimeUncertaintyNanos =
                    hasElapsedRealtimeUncertaintyNanos ? elapsedRealtimeUncertaintyNanos : 0;
        }
    }
}
