/*
 * Copyright (C) 2007 The Android Open Source Project
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

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.Printer;
import android.util.TimeUtils;

import java.text.DecimalFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.StringTokenizer;

/**
 * A data class representing a geographic location.
 *
 * <p>A location may consist of a latitude, longitude, timestamp, and other information such as
 * bearing, altitude and velocity.
 *
 * <p>All locations generated through {@link LocationManager} are guaranteed to have a valid
 * latitude, longitude, and timestamp (both UTC time and elapsed real-time since boot). All other
 * parameters are optional.
 */
public class Location implements Parcelable {

    /**
     * Constant used to specify formatting of a latitude or longitude
     * in the form "[+-]DDD.DDDDD where D indicates degrees.
     */
    public static final int FORMAT_DEGREES = 0;

    /**
     * Constant used to specify formatting of a latitude or longitude
     * in the form "[+-]DDD:MM.MMMMM" where D indicates degrees and
     * M indicates minutes of arc (1 minute = 1/60th of a degree).
     */
    public static final int FORMAT_MINUTES = 1;

    /**
     * Constant used to specify formatting of a latitude or longitude
     * in the form "DDD:MM:SS.SSSSS" where D indicates degrees, M
     * indicates minutes of arc, and S indicates seconds of arc (1
     * minute = 1/60th of a degree, 1 second = 1/3600th of a degree).
     */
    public static final int FORMAT_SECONDS = 2;

    /**
     * Bundle key for a version of the location containing no GPS data.
     * Allows location providers to flag locations as being safe to
     * feed to LocationFudger.
     *
     * @hide
     * @deprecated As of Android R, this extra is longer in use, since it is not necessary to keep
     * gps locations separate from other locations for coarsening. Providers that do not need to
     * support platforms below Android R should not use this constant.
     */
    @SystemApi
    @Deprecated
    public static final String EXTRA_NO_GPS_LOCATION = "noGPSLocation";

    private static final int HAS_ALTITUDE_MASK = 1 << 0;
    private static final int HAS_SPEED_MASK = 1 << 1;
    private static final int HAS_BEARING_MASK = 1 << 2;
    private static final int HAS_HORIZONTAL_ACCURACY_MASK = 1 << 3;
    private static final int HAS_MOCK_PROVIDER_MASK = 1 << 4;
    private static final int HAS_VERTICAL_ACCURACY_MASK = 1 << 5;
    private static final int HAS_SPEED_ACCURACY_MASK = 1 << 6;
    private static final int HAS_BEARING_ACCURACY_MASK = 1 << 7;
    private static final int HAS_ELAPSED_REALTIME_UNCERTAINTY_MASK = 1 << 8;

    // Cached data to make bearing/distance computations more efficient for the case
    // where distanceTo and bearingTo are called in sequence.  Assume this typically happens
    // on the same thread for caching purposes.
    private static final ThreadLocal<BearingDistanceCache> sBearingDistanceCache =
            ThreadLocal.withInitial(BearingDistanceCache::new);

    private String mProvider;
    private long mTime = 0;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R,
            publicAlternatives = "{@link #getElapsedRealtimeNanos()}")
    private long mElapsedRealtimeNanos = 0;
    // Estimate of the relative precision of the alignment of this SystemClock
    // timestamp, with the reported measurements in nanoseconds (68% confidence).
    private double mElapsedRealtimeUncertaintyNanos = 0.0f;
    private double mLatitude = 0.0;
    private double mLongitude = 0.0;
    private double mAltitude = 0.0f;
    private float mSpeed = 0.0f;
    private float mBearing = 0.0f;
    private float mHorizontalAccuracyMeters = 0.0f;
    private float mVerticalAccuracyMeters = 0.0f;
    private float mSpeedAccuracyMetersPerSecond = 0.0f;
    private float mBearingAccuracyDegrees = 0.0f;

    private Bundle mExtras = null;

    // A bitmask of fields present in this object (see HAS_* constants defined above).
    private int mFieldsMask = 0;

    /**
     * Construct a new Location with a named provider.
     *
     * <p>By default time, latitude and longitude are 0, and the location
     * has no bearing, altitude, speed, accuracy or extras.
     *
     * @param provider the source that provides the location. It can be of type
     * {@link LocationManager#GPS_PROVIDER}, {@link LocationManager#NETWORK_PROVIDER},
     * or {@link LocationManager#PASSIVE_PROVIDER}. You can also define your own
     * provider string, in which case an empty string is a valid provider.
     */
    public Location(String provider) {
        mProvider = provider;
    }

    /**
     * Construct a new Location object that is copied from an existing one.
     */
    public Location(Location l) {
        set(l);
    }

    /**
     * Sets the contents of the location to the values from the given location.
     */
    public void set(Location l) {
        mProvider = l.mProvider;
        mTime = l.mTime;
        mElapsedRealtimeNanos = l.mElapsedRealtimeNanos;
        mElapsedRealtimeUncertaintyNanos = l.mElapsedRealtimeUncertaintyNanos;
        mFieldsMask = l.mFieldsMask;
        mLatitude = l.mLatitude;
        mLongitude = l.mLongitude;
        mAltitude = l.mAltitude;
        mSpeed = l.mSpeed;
        mBearing = l.mBearing;
        mHorizontalAccuracyMeters = l.mHorizontalAccuracyMeters;
        mVerticalAccuracyMeters = l.mVerticalAccuracyMeters;
        mSpeedAccuracyMetersPerSecond = l.mSpeedAccuracyMetersPerSecond;
        mBearingAccuracyDegrees = l.mBearingAccuracyDegrees;
        mExtras = (l.mExtras == null) ? null : new Bundle(l.mExtras);
    }

    /**
     * Clears the contents of the location.
     */
    public void reset() {
        mProvider = null;
        mTime = 0;
        mElapsedRealtimeNanos = 0;
        mElapsedRealtimeUncertaintyNanos = 0.0;
        mFieldsMask = 0;
        mLatitude = 0;
        mLongitude = 0;
        mAltitude = 0;
        mSpeed = 0;
        mBearing = 0;
        mHorizontalAccuracyMeters = 0;
        mVerticalAccuracyMeters = 0;
        mSpeedAccuracyMetersPerSecond = 0;
        mBearingAccuracyDegrees = 0;
        mExtras = null;
    }

    /**
     * Converts a coordinate to a String representation. The outputType
     * may be one of FORMAT_DEGREES, FORMAT_MINUTES, or FORMAT_SECONDS.
     * The coordinate must be a valid double between -180.0 and 180.0.
     * This conversion is performed in a method that is dependent on the
     * default locale, and so is not guaranteed to round-trip with
     * {@link #convert(String)}.
     *
     * @throws IllegalArgumentException if coordinate is less than
     * -180.0, greater than 180.0, or is not a number.
     * @throws IllegalArgumentException if outputType is not one of
     * FORMAT_DEGREES, FORMAT_MINUTES, or FORMAT_SECONDS.
     */
    public static String convert(double coordinate, int outputType) {
        if (coordinate < -180.0 || coordinate > 180.0 || Double.isNaN(coordinate)) {
            throw new IllegalArgumentException("coordinate=" + coordinate);
        }
        if ((outputType != FORMAT_DEGREES) &&
            (outputType != FORMAT_MINUTES) &&
            (outputType != FORMAT_SECONDS)) {
            throw new IllegalArgumentException("outputType=" + outputType);
        }

        StringBuilder sb = new StringBuilder();

        // Handle negative values
        if (coordinate < 0) {
            sb.append('-');
            coordinate = -coordinate;
        }

        DecimalFormat df = new DecimalFormat("###.#####");
        if (outputType == FORMAT_MINUTES || outputType == FORMAT_SECONDS) {
            int degrees = (int) Math.floor(coordinate);
            sb.append(degrees);
            sb.append(':');
            coordinate -= degrees;
            coordinate *= 60.0;
            if (outputType == FORMAT_SECONDS) {
                int minutes = (int) Math.floor(coordinate);
                sb.append(minutes);
                sb.append(':');
                coordinate -= minutes;
                coordinate *= 60.0;
            }
        }
        sb.append(df.format(coordinate));
        return sb.toString();
    }

    /**
     * Converts a String in one of the formats described by
     * FORMAT_DEGREES, FORMAT_MINUTES, or FORMAT_SECONDS into a
     * double. This conversion is performed in a locale agnostic
     * method, and so is not guaranteed to round-trip with
     * {@link #convert(double, int)}.
     *
     * @throws NullPointerException if coordinate is null
     * @throws IllegalArgumentException if the coordinate is not
     * in one of the valid formats.
     */
    public static double convert(String coordinate) {
        // IllegalArgumentException if bad syntax
        if (coordinate == null) {
            throw new NullPointerException("coordinate");
        }

        boolean negative = false;
        if (coordinate.charAt(0) == '-') {
            coordinate = coordinate.substring(1);
            negative = true;
        }

        StringTokenizer st = new StringTokenizer(coordinate, ":");
        int tokens = st.countTokens();
        if (tokens < 1) {
            throw new IllegalArgumentException("coordinate=" + coordinate);
        }
        try {
            String degrees = st.nextToken();
            double val;
            if (tokens == 1) {
                val = Double.parseDouble(degrees);
                return negative ? -val : val;
            }

            String minutes = st.nextToken();
            int deg = Integer.parseInt(degrees);
            double min;
            double sec = 0.0;
            boolean secPresent = false;

            if (st.hasMoreTokens()) {
                min = Integer.parseInt(minutes);
                String seconds = st.nextToken();
                sec = Double.parseDouble(seconds);
                secPresent = true;
            } else {
                min = Double.parseDouble(minutes);
            }

            boolean isNegative180 = negative && (deg == 180) &&
                (min == 0) && (sec == 0);

            // deg must be in [0, 179] except for the case of -180 degrees
            if ((deg < 0.0) || (deg > 179 && !isNegative180)) {
                throw new IllegalArgumentException("coordinate=" + coordinate);
            }

            // min must be in [0, 59] if seconds are present, otherwise [0.0, 60.0)
            if (min < 0 || min >= 60 || (secPresent && (min > 59))) {
                throw new IllegalArgumentException("coordinate=" +
                        coordinate);
            }

            // sec must be in [0.0, 60.0)
            if (sec < 0 || sec >= 60) {
                throw new IllegalArgumentException("coordinate=" +
                        coordinate);
            }

            val = deg*3600.0 + min*60.0 + sec;
            val /= 3600.0;
            return negative ? -val : val;
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("coordinate=" + coordinate);
        }
    }

    private static void computeDistanceAndBearing(double lat1, double lon1,
        double lat2, double lon2, BearingDistanceCache results) {
        // Based on http://www.ngs.noaa.gov/PUBS_LIB/inverse.pdf
        // using the "Inverse Formula" (section 4)

        int MAXITERS = 20;
        // Convert lat/long to radians
        lat1 *= Math.PI / 180.0;
        lat2 *= Math.PI / 180.0;
        lon1 *= Math.PI / 180.0;
        lon2 *= Math.PI / 180.0;

        double a = 6378137.0; // WGS84 major axis
        double b = 6356752.3142; // WGS84 semi-major axis
        double f = (a - b) / a;
        double aSqMinusBSqOverBSq = (a * a - b * b) / (b * b);

        double L = lon2 - lon1;
        double A = 0.0;
        double U1 = Math.atan((1.0 - f) * Math.tan(lat1));
        double U2 = Math.atan((1.0 - f) * Math.tan(lat2));

        double cosU1 = Math.cos(U1);
        double cosU2 = Math.cos(U2);
        double sinU1 = Math.sin(U1);
        double sinU2 = Math.sin(U2);
        double cosU1cosU2 = cosU1 * cosU2;
        double sinU1sinU2 = sinU1 * sinU2;

        double sigma = 0.0;
        double deltaSigma = 0.0;
        double cosSqAlpha;
        double cos2SM;
        double cosSigma;
        double sinSigma;
        double cosLambda = 0.0;
        double sinLambda = 0.0;

        double lambda = L; // initial guess
        for (int iter = 0; iter < MAXITERS; iter++) {
            double lambdaOrig = lambda;
            cosLambda = Math.cos(lambda);
            sinLambda = Math.sin(lambda);
            double t1 = cosU2 * sinLambda;
            double t2 = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda;
            double sinSqSigma = t1 * t1 + t2 * t2; // (14)
            sinSigma = Math.sqrt(sinSqSigma);
            cosSigma = sinU1sinU2 + cosU1cosU2 * cosLambda; // (15)
            sigma = Math.atan2(sinSigma, cosSigma); // (16)
            double sinAlpha = (sinSigma == 0) ? 0.0 :
                cosU1cosU2 * sinLambda / sinSigma; // (17)
            cosSqAlpha = 1.0 - sinAlpha * sinAlpha;
            cos2SM = (cosSqAlpha == 0) ? 0.0 :
                cosSigma - 2.0 * sinU1sinU2 / cosSqAlpha; // (18)

            double uSquared = cosSqAlpha * aSqMinusBSqOverBSq; // defn
            A = 1 + (uSquared / 16384.0) * // (3)
                (4096.0 + uSquared *
                 (-768 + uSquared * (320.0 - 175.0 * uSquared)));
            double B = (uSquared / 1024.0) * // (4)
                (256.0 + uSquared *
                 (-128.0 + uSquared * (74.0 - 47.0 * uSquared)));
            double C = (f / 16.0) *
                cosSqAlpha *
                (4.0 + f * (4.0 - 3.0 * cosSqAlpha)); // (10)
            double cos2SMSq = cos2SM * cos2SM;
            deltaSigma = B * sinSigma * // (6)
                (cos2SM + (B / 4.0) *
                 (cosSigma * (-1.0 + 2.0 * cos2SMSq) -
                  (B / 6.0) * cos2SM *
                  (-3.0 + 4.0 * sinSigma * sinSigma) *
                  (-3.0 + 4.0 * cos2SMSq)));

            lambda = L +
                (1.0 - C) * f * sinAlpha *
                (sigma + C * sinSigma *
                 (cos2SM + C * cosSigma *
                  (-1.0 + 2.0 * cos2SM * cos2SM))); // (11)

            double delta = (lambda - lambdaOrig) / lambda;
            if (Math.abs(delta) < 1.0e-12) {
                break;
            }
        }

        results.mDistance = (float) (b * A * (sigma - deltaSigma));
        float initialBearing = (float) Math.atan2(cosU2 * sinLambda,
            cosU1 * sinU2 - sinU1 * cosU2 * cosLambda);
        initialBearing *= 180.0 / Math.PI;
        results.mInitialBearing = initialBearing;
        float finalBearing = (float) Math.atan2(cosU1 * sinLambda,
                -sinU1 * cosU2 + cosU1 * sinU2 * cosLambda);
        finalBearing *= 180.0 / Math.PI;
        results.mFinalBearing = finalBearing;
        results.mLat1 = lat1;
        results.mLat2 = lat2;
        results.mLon1 = lon1;
        results.mLon2 = lon2;
    }

    /**
     * Computes the approximate distance in meters between two
     * locations, and optionally the initial and final bearings of the
     * shortest path between them.  Distance and bearing are defined using the
     * WGS84 ellipsoid.
     *
     * <p> The computed distance is stored in results[0].  If results has length
     * 2 or greater, the initial bearing is stored in results[1]. If results has
     * length 3 or greater, the final bearing is stored in results[2].
     *
     * @param startLatitude the starting latitude
     * @param startLongitude the starting longitude
     * @param endLatitude the ending latitude
     * @param endLongitude the ending longitude
     * @param results an array of floats to hold the results
     *
     * @throws IllegalArgumentException if results is null or has length < 1
     */
    public static void distanceBetween(double startLatitude, double startLongitude,
        double endLatitude, double endLongitude, float[] results) {
        if (results == null || results.length < 1) {
            throw new IllegalArgumentException("results is null or has length < 1");
        }
        BearingDistanceCache cache = sBearingDistanceCache.get();
        computeDistanceAndBearing(startLatitude, startLongitude,
                endLatitude, endLongitude, cache);
        results[0] = cache.mDistance;
        if (results.length > 1) {
            results[1] = cache.mInitialBearing;
            if (results.length > 2) {
                results[2] = cache.mFinalBearing;
            }
        }
    }

    /**
     * Returns the approximate distance in meters between this
     * location and the given location.  Distance is defined using
     * the WGS84 ellipsoid.
     *
     * @param dest the destination location
     * @return the approximate distance in meters
     */
    public float distanceTo(Location dest) {
        BearingDistanceCache cache = sBearingDistanceCache.get();
        // See if we already have the result
        if (mLatitude != cache.mLat1 || mLongitude != cache.mLon1 ||
            dest.mLatitude != cache.mLat2 || dest.mLongitude != cache.mLon2) {
            computeDistanceAndBearing(mLatitude, mLongitude,
                dest.mLatitude, dest.mLongitude, cache);
        }
        return cache.mDistance;
    }

    /**
     * Returns the approximate initial bearing in degrees East of true
     * North when traveling along the shortest path between this
     * location and the given location.  The shortest path is defined
     * using the WGS84 ellipsoid.  Locations that are (nearly)
     * antipodal may produce meaningless results.
     *
     * @param dest the destination location
     * @return the initial bearing in degrees
     */
    public float bearingTo(Location dest) {
        BearingDistanceCache cache = sBearingDistanceCache.get();
        // See if we already have the result
        if (mLatitude != cache.mLat1 || mLongitude != cache.mLon1 ||
                        dest.mLatitude != cache.mLat2 || dest.mLongitude != cache.mLon2) {
            computeDistanceAndBearing(mLatitude, mLongitude,
                dest.mLatitude, dest.mLongitude, cache);
        }
        return cache.mInitialBearing;
    }

    /**
     * Returns the name of the provider that generated this fix.
     *
     * @return the provider, or null if it has not been set
     */
    public String getProvider() {
        return mProvider;
    }

    /**
     * Sets the name of the provider that generated this fix.
     */
    public void setProvider(String provider) {
        mProvider = provider;
    }

    /**
     * Return the UTC time of this location fix, in milliseconds since epoch (January 1, 1970).
     *
     * <p>There is no guarantee that different locations have times set from the same clock.
     * Locations derived from the {@link LocationManager#GPS_PROVIDER} are guaranteed to have their
     * time set from the clock in use by the satellite constellation that provided the fix.
     * Locations derived from other providers may use any clock to set their time, though it is most
     * common to use the device clock (which may be incorrect).
     *
     * <p>Note that the device clock UTC time is not monotonic; it can jump forwards or backwards
     * unpredictably and may be changed at any time by the user, so this time should not be used to
     * order or compare locations. Prefer {@link #getElapsedRealtimeNanos} for that purpose, as this
     * clock is guaranteed to be monotonic.
     *
     * <p>On the other hand, this method may be useful for presenting a human readable time to the
     * user, or as a heuristic for comparing location fixes across reboot or across devices.
     *
     * <p>All locations generated by the {@link LocationManager} are guaranteed to have a UTC time
     * set, however remember that the device clock may have changed since the location was
     * generated.
     *
     * @return UTC time of fix, in milliseconds since January 1, 1970.
     */
    public long getTime() {
        return mTime;
    }

    /**
     * Set the UTC time of this fix, in milliseconds since epoch (January 1, 1970).
     *
     * @param time UTC time of this fix, in milliseconds since January 1, 1970
     */
    public void setTime(long time) {
        mTime = time;
    }

    /**
     * Return the time of this fix in nanoseconds of elapsed realtime since system boot.
     *
     * <p>This value can be compared with {@link android.os.SystemClock#elapsedRealtimeNanos}, to
     * reliably order or compare locations. This is reliable because elapsed realtime is guaranteed
     * to be monotonic and continues to increment even when the system is in deep sleep (unlike
     * {@link #getTime}). However, since elapsed realtime is with reference to system boot, it does
     * not make sense to use this value to order or compare locations across boot cycles.
     *
     * <p>All locations generated by the {@link LocationManager} are guaranteed to have a valid
     * elapsed realtime set.
     *
     * @return elapsed realtime of fix, in nanoseconds since system boot.
     */
    public long getElapsedRealtimeNanos() {
        return mElapsedRealtimeNanos;
    }

    /**
     * Return the time of this fix in milliseconds of elapsed realtime since system boot.
     *
     * @see #getElapsedRealtimeNanos()
     *
     * @hide
     */
    public long getElapsedRealtimeMillis() {
        return NANOSECONDS.toMillis(getElapsedRealtimeNanos());
    }

    /**
     * Returns the age of this fix with respect to the current elapsed realtime.
     *
     * @see #getElapsedRealtimeNanos()
     *
     * @hide
     */
    public long getElapsedRealtimeAgeMillis() {
        return getElapsedRealtimeAgeMillis(SystemClock.elapsedRealtime());
    }

    /**
     * Returns the age of this fix with respect to the given elapsed realtime.
     *
     * @see #getElapsedRealtimeNanos()
     *
     * @hide
     */
    public long getElapsedRealtimeAgeMillis(long referenceRealtimeMs) {
        return referenceRealtimeMs - NANOSECONDS.toMillis(mElapsedRealtimeNanos);
    }

    /**
     * Set the time of this fix, in elapsed realtime since system boot.
     *
     * @param time elapsed realtime of fix, in nanoseconds since system boot.
     */
    public void setElapsedRealtimeNanos(long time) {
        mElapsedRealtimeNanos = time;
    }

    /**
     * Get estimate of the relative precision of the alignment of the
     * ElapsedRealtimeNanos timestamp, with the reported measurements in
     * nanoseconds (68% confidence).
     *
     * This means that we have 68% confidence that the true timestamp of the
     * event is within ElapsedReatimeNanos +/- uncertainty.
     *
     * Example :
     *   - getElapsedRealtimeNanos() returns 10000000
     *   - getElapsedRealtimeUncertaintyNanos() returns 1000000 (equivalent to 1millisecond)
     *   This means that the event most likely happened between 9000000 and 11000000.
     *
     * @return uncertainty of elapsed real-time of fix, in nanoseconds.
     */
    public double getElapsedRealtimeUncertaintyNanos() {
        return mElapsedRealtimeUncertaintyNanos;
    }

    /**
     * Set estimate of the relative precision of the alignment of the
     * ElapsedRealtimeNanos timestamp, with the reported measurements in
     * nanoseconds (68% confidence).
     *
     * @param time uncertainty of the elapsed real-time of fix, in nanoseconds.
     */
    public void setElapsedRealtimeUncertaintyNanos(double time) {
        mElapsedRealtimeUncertaintyNanos = time;
        mFieldsMask |= HAS_ELAPSED_REALTIME_UNCERTAINTY_MASK;
    }

    /**
     * True if this location has a elapsed realtime accuracy.
     */
    public boolean hasElapsedRealtimeUncertaintyNanos() {
        return (mFieldsMask & HAS_ELAPSED_REALTIME_UNCERTAINTY_MASK) != 0;
    }


    /**
     * Get the latitude, in degrees.
     *
     * <p>All locations generated by the {@link LocationManager}
     * will have a valid latitude.
     */
    public double getLatitude() {
        return mLatitude;
    }

    /**
     * Set the latitude, in degrees.
     */
    public void setLatitude(double latitude) {
        mLatitude = latitude;
    }

    /**
     * Get the longitude, in degrees.
     *
     * <p>All locations generated by the {@link LocationManager}
     * will have a valid longitude.
     */
    public double getLongitude() {
        return mLongitude;
    }

    /**
     * Set the longitude, in degrees.
     */
    public void setLongitude(double longitude) {
        mLongitude = longitude;
    }

    /**
     * True if this location has an altitude.
     */
    public boolean hasAltitude() {
        return (mFieldsMask & HAS_ALTITUDE_MASK) != 0;
    }

    /**
     * Get the altitude if available, in meters above the WGS 84 reference
     * ellipsoid.
     *
     * <p>If this location does not have an altitude then 0.0 is returned.
     */
    public double getAltitude() {
        return mAltitude;
    }

    /**
     * Set the altitude, in meters above the WGS 84 reference ellipsoid.
     *
     * <p>Following this call {@link #hasAltitude} will return true.
     */
    public void setAltitude(double altitude) {
        mAltitude = altitude;
        mFieldsMask |= HAS_ALTITUDE_MASK;
    }

    /**
     * Remove the altitude from this location.
     *
     * <p>Following this call {@link #hasAltitude} will return false,
     * and {@link #getAltitude} will return 0.0.
     *
     * @deprecated use a new Location object for location updates.
     */
    @Deprecated
    public void removeAltitude() {
        mAltitude = 0.0f;
        mFieldsMask &= ~HAS_ALTITUDE_MASK;
    }

    /**
     * True if this location has a speed.
     */
    public boolean hasSpeed() {
        return (mFieldsMask & HAS_SPEED_MASK) != 0;
    }

    /**
     * Get the speed if it is available, in meters/second over ground.
     *
     * <p>If this location does not have a speed then 0.0 is returned.
     */
    public float getSpeed() {
        return mSpeed;
    }

    /**
     * Set the speed, in meters/second over ground.
     *
     * <p>Following this call {@link #hasSpeed} will return true.
     */
    public void setSpeed(float speed) {
        mSpeed = speed;
        mFieldsMask |= HAS_SPEED_MASK;
    }

    /**
     * Remove the speed from this location.
     *
     * <p>Following this call {@link #hasSpeed} will return false,
     * and {@link #getSpeed} will return 0.0.
     *
     * @deprecated use a new Location object for location updates.
     */
    @Deprecated
    public void removeSpeed() {
        mSpeed = 0.0f;
        mFieldsMask &= ~HAS_SPEED_MASK;
    }

    /**
     * True if this location has a bearing.
     */
    public boolean hasBearing() {
        return (mFieldsMask & HAS_BEARING_MASK) != 0;
    }

    /**
     * Get the bearing, in degrees.
     *
     * <p>Bearing is the horizontal direction of travel of this device,
     * and is not related to the device orientation. It is guaranteed to
     * be in the range (0.0, 360.0] if the device has a bearing.
     *
     * <p>If this location does not have a bearing then 0.0 is returned.
     */
    public float getBearing() {
        return mBearing;
    }

    /**
     * Set the bearing, in degrees.
     *
     * <p>Bearing is the horizontal direction of travel of this device,
     * and is not related to the device orientation.
     *
     * <p>The input will be wrapped into the range (0.0, 360.0].
     */
    public void setBearing(float bearing) {
        while (bearing < 0.0f) {
            bearing += 360.0f;
        }
        while (bearing >= 360.0f) {
            bearing -= 360.0f;
        }
        mBearing = bearing;
        mFieldsMask |= HAS_BEARING_MASK;
    }

    /**
     * Remove the bearing from this location.
     *
     * <p>Following this call {@link #hasBearing} will return false,
     * and {@link #getBearing} will return 0.0.
     *
     * @deprecated use a new Location object for location updates.
     */
    @Deprecated
    public void removeBearing() {
        mBearing = 0.0f;
        mFieldsMask &= ~HAS_BEARING_MASK;
    }

    /**
     * True if this location has a horizontal accuracy.
     *
     * <p>All locations generated by the {@link LocationManager} have an horizontal accuracy.
     */
    public boolean hasAccuracy() {
        return (mFieldsMask & HAS_HORIZONTAL_ACCURACY_MASK) != 0;
    }

    /**
     * Get the estimated horizontal accuracy of this location, radial, in meters.
     *
     * <p>We define horizontal accuracy as the radius of 68% confidence. In other
     * words, if you draw a circle centered at this location's
     * latitude and longitude, and with a radius equal to the accuracy,
     * then there is a 68% probability that the true location is inside
     * the circle.
     *
     * <p>This accuracy estimation is only concerned with horizontal
     * accuracy, and does not indicate the accuracy of bearing,
     * velocity or altitude if those are included in this Location.
     *
     * <p>If this location does not have a horizontal accuracy, then 0.0 is returned.
     * All locations generated by the {@link LocationManager} include horizontal accuracy.
     */
    public float getAccuracy() {
        return mHorizontalAccuracyMeters;
    }

    /**
     * Set the estimated horizontal accuracy of this location, meters.
     *
     * <p>See {@link #getAccuracy} for the definition of horizontal accuracy.
     *
     * <p>Following this call {@link #hasAccuracy} will return true.
     */
    public void setAccuracy(float horizontalAccuracy) {
        mHorizontalAccuracyMeters = horizontalAccuracy;
        mFieldsMask |= HAS_HORIZONTAL_ACCURACY_MASK;
    }

    /**
     * Remove the horizontal accuracy from this location.
     *
     * <p>Following this call {@link #hasAccuracy} will return false, and
     * {@link #getAccuracy} will return 0.0.
     *
     * @deprecated use a new Location object for location updates.
     */
    @Deprecated
    public void removeAccuracy() {
        mHorizontalAccuracyMeters = 0.0f;
        mFieldsMask &= ~HAS_HORIZONTAL_ACCURACY_MASK;
    }

    /**
     * True if this location has a vertical accuracy.
     */
    public boolean hasVerticalAccuracy() {
        return (mFieldsMask & HAS_VERTICAL_ACCURACY_MASK) != 0;
    }

    /**
     * Get the estimated vertical accuracy of this location, in meters.
     *
     * <p>We define vertical accuracy at 68% confidence. Specifically, as 1-side of the 2-sided
     * range above and below the estimated altitude reported by {@link #getAltitude()}, within which
     * there is a 68% probability of finding the true altitude.
     *
     * <p>In the case where the underlying distribution is assumed Gaussian normal, this would be
     * considered 1 standard deviation.
     *
     * <p>For example, if {@link #getAltitude()} returns 150m, and this method returns 20m then
     * there is a 68% probability of the true altitude being between 130m and 170m.
     */
    public float getVerticalAccuracyMeters() {
        return mVerticalAccuracyMeters;
    }

    /**
     * Set the estimated vertical accuracy of this location, meters.
     *
     * <p>See {@link #getVerticalAccuracyMeters} for the definition of vertical accuracy.
     *
     * <p>Following this call {@link #hasVerticalAccuracy} will return true.
     */
    public void setVerticalAccuracyMeters(float verticalAccuracyMeters) {
        mVerticalAccuracyMeters = verticalAccuracyMeters;
        mFieldsMask |= HAS_VERTICAL_ACCURACY_MASK;
    }

    /**
     * Remove the vertical accuracy from this location.
     *
     * <p>Following this call {@link #hasVerticalAccuracy} will return false, and
     * {@link #getVerticalAccuracyMeters} will return 0.0.
     *
     * @deprecated use a new Location object for location updates.
     * @removed
     */
    @Deprecated
    public void removeVerticalAccuracy() {
        mVerticalAccuracyMeters = 0.0f;
        mFieldsMask &= ~HAS_VERTICAL_ACCURACY_MASK;
    }

    /**
     * True if this location has a speed accuracy.
     */
    public boolean hasSpeedAccuracy() {
        return (mFieldsMask & HAS_SPEED_ACCURACY_MASK) != 0;
    }

    /**
     * Get the estimated speed accuracy of this location, in meters per second.
     *
     * <p>We define speed accuracy at 68% confidence. Specifically, as 1-side of the 2-sided range
     * above and below the estimated speed reported by {@link #getSpeed()}, within which there is a
     * 68% probability of finding the true speed.
     *
     * <p>In the case where the underlying distribution is assumed Gaussian normal, this would be
     * considered 1 standard deviation.
     *
     * <p>For example, if {@link #getSpeed()} returns 5m/s, and this method returns 1m/s, then there
     * is a 68% probability of the true speed being between 4m/s and 6m/s.
     *
     * <p>Note that the speed and speed accuracy may be more accurate than would be obtained simply
     * from differencing sequential positions, such as when the Doppler measurements from GNSS
     * satellites are taken into account.
     */
    public float getSpeedAccuracyMetersPerSecond() {
        return mSpeedAccuracyMetersPerSecond;
    }

    /**
     * Set the estimated speed accuracy of this location, meters per second.
     *
     * <p>See {@link #getSpeedAccuracyMetersPerSecond} for the definition of speed accuracy.
     *
     * <p>Following this call {@link #hasSpeedAccuracy} will return true.
     */
    public void setSpeedAccuracyMetersPerSecond(float speedAccuracyMeterPerSecond) {
        mSpeedAccuracyMetersPerSecond = speedAccuracyMeterPerSecond;
        mFieldsMask |= HAS_SPEED_ACCURACY_MASK;
    }

    /**
     * Remove the speed accuracy from this location.
     *
     * <p>Following this call {@link #hasSpeedAccuracy} will return false, and
     * {@link #getSpeedAccuracyMetersPerSecond} will return 0.0.
     *
     * @deprecated use a new Location object for location updates.
     * @removed
     */
    @Deprecated
    public void removeSpeedAccuracy() {
        mSpeedAccuracyMetersPerSecond = 0.0f;
        mFieldsMask &= ~HAS_SPEED_ACCURACY_MASK;
    }

    /**
     * True if this location has a bearing accuracy.
     */
    public boolean hasBearingAccuracy() {
        return (mFieldsMask & HAS_BEARING_ACCURACY_MASK) != 0;
    }

    /**
     * Get the estimated bearing accuracy of this location, in degrees.
     *
     * <p>We define bearing accuracy at 68% confidence. Specifically, as 1-side of the 2-sided range
     * on each side of the estimated bearing reported by {@link #getBearing()}, within which there
     * is a 68% probability of finding the true bearing.
     *
     * <p>In the case where the underlying distribution is assumed Gaussian normal, this would be
     * considered 1 standard deviation.
     *
     * <p>For example, if {@link #getBearing()} returns 60°, and this method returns 10°, then there
     * is a 68% probability of the true bearing being between 50° and 70°.
     */
    public float getBearingAccuracyDegrees() {
        return mBearingAccuracyDegrees;
    }

    /**
     * Set the estimated bearing accuracy of this location, degrees.
     *
     * <p>See {@link #getBearingAccuracyDegrees} for the definition of bearing accuracy.
     *
     * <p>Following this call {@link #hasBearingAccuracy} will return true.
     */
    public void setBearingAccuracyDegrees(float bearingAccuracyDegrees) {
        mBearingAccuracyDegrees = bearingAccuracyDegrees;
        mFieldsMask |= HAS_BEARING_ACCURACY_MASK;
    }

    /**
     * Remove the bearing accuracy from this location.
     *
     * <p>Following this call {@link #hasBearingAccuracy} will return false, and
     * {@link #getBearingAccuracyDegrees} will return 0.0.
     *
     * @deprecated use a new Location object for location updates.
     * @removed
     */
    @Deprecated
    public void removeBearingAccuracy() {
        mBearingAccuracyDegrees = 0.0f;
        mFieldsMask &= ~HAS_BEARING_ACCURACY_MASK;
    }

    /**
     * Return true if this Location object is complete.
     *
     * <p>A location object is currently considered complete if it has
     * a valid provider, accuracy, wall-clock time and elapsed real-time.
     *
     * <p>All locations supplied by the {@link LocationManager} to
     * applications must be complete.
     *
     * @see #makeComplete
     * @hide
     */
    @SystemApi
    public boolean isComplete() {
        return mProvider != null && hasAccuracy() && mTime != 0 && mElapsedRealtimeNanos != 0;
    }

    /**
     * Helper to fill incomplete fields.
     *
     * <p>Used to assist in backwards compatibility with
     * Location objects received from applications.
     *
     * @see #isComplete
     * @hide
     */
    @SystemApi
    public void makeComplete() {
        if (mProvider == null) mProvider = "?";
        if (!hasAccuracy()) {
            mFieldsMask |= HAS_HORIZONTAL_ACCURACY_MASK;
            mHorizontalAccuracyMeters = 100.0f;
        }
        if (mTime == 0) mTime = System.currentTimeMillis();
        if (mElapsedRealtimeNanos == 0) mElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
    }

    /**
     * Returns additional provider-specific information about the location fix as a Bundle. The keys
     * and values are determined by the provider.  If no additional information is available, null
     * is returned.
     *
     * <p> A number of common key/value pairs are listed
     * below. Providers that use any of the keys on this list must
     * provide the corresponding value as described below.
     *
     * <ul>
     * <li> satellites - the number of satellites used to derive the fix
     * </ul>
     */
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * Sets the extra information associated with this fix to the given Bundle.
     *
     * <p>Note this stores a copy of the given extras, so any changes to extras after calling this
     * method won't be reflected in the location bundle.
     */
    public void setExtras(@Nullable Bundle extras) {
        mExtras = (extras == null) ? null : new Bundle(extras);
    }

    /**
     * Location equality is provided primarily for test purposes. Comparing locations for equality
     * in production may indicate incorrect assumptions, and should be avoided whenever possible.
     *
     * <p>{@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Location location = (Location) o;
        return mTime == location.mTime
                && mElapsedRealtimeNanos == location.mElapsedRealtimeNanos
                && hasElapsedRealtimeUncertaintyNanos()
                == location.hasElapsedRealtimeUncertaintyNanos()
                && (!hasElapsedRealtimeUncertaintyNanos() || Double.compare(
                location.mElapsedRealtimeUncertaintyNanos, mElapsedRealtimeUncertaintyNanos) == 0)
                && Double.compare(location.mLatitude, mLatitude) == 0
                && Double.compare(location.mLongitude, mLongitude) == 0
                && hasAltitude() == location.hasAltitude()
                && (!hasAltitude() || Double.compare(location.mAltitude, mAltitude) == 0)
                && hasSpeed() == location.hasSpeed()
                && (!hasSpeed() || Float.compare(location.mSpeed, mSpeed) == 0)
                && hasBearing() == location.hasBearing()
                && (!hasBearing() || Float.compare(location.mBearing, mBearing) == 0)
                && hasAccuracy() == location.hasAccuracy()
                && (!hasAccuracy() || Float.compare(location.mHorizontalAccuracyMeters,
                mHorizontalAccuracyMeters) == 0)
                && hasVerticalAccuracy() == location.hasVerticalAccuracy()
                && (!hasVerticalAccuracy() || Float.compare(location.mVerticalAccuracyMeters,
                mVerticalAccuracyMeters) == 0)
                && hasSpeedAccuracy() == location.hasSpeedAccuracy()
                && (!hasSpeedAccuracy() || Float.compare(location.mSpeedAccuracyMetersPerSecond,
                mSpeedAccuracyMetersPerSecond) == 0)
                && hasBearingAccuracy() == location.hasBearingAccuracy()
                && (!hasBearingAccuracy() || Float.compare(location.mBearingAccuracyDegrees,
                mBearingAccuracyDegrees) == 0)
                && Objects.equals(mProvider, location.mProvider)
                && areExtrasEqual(mExtras, location.mExtras);
    }

    private static boolean areExtrasEqual(@Nullable Bundle extras1, @Nullable Bundle extras2) {
        if ((extras1 == null || extras1.isEmpty()) && (extras2 == null || extras2.isEmpty())) {
            return true;
        } else if (extras1 == null || extras2 == null) {
            return false;
        } else {
            return extras1.kindofEquals(extras2);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(mProvider, mElapsedRealtimeNanos, mLatitude, mLongitude);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("Location[");
        s.append(mProvider);
        s.append(" ").append(String.format(Locale.ROOT, "%.6f,%.6f", mLatitude, mLongitude));
        if (hasAccuracy()) {
            s.append(" hAcc=").append(mHorizontalAccuracyMeters);
        }
        s.append(" et=");
        TimeUtils.formatDuration(getElapsedRealtimeMillis(), s);
        if (hasAltitude()) {
            s.append(" alt=").append(mAltitude);
            if (hasVerticalAccuracy()) {
                s.append(" vAcc=").append(mVerticalAccuracyMeters);
            }
        }
        if (hasSpeed()) {
            s.append(" vel=").append(mSpeed);
            if (hasSpeedAccuracy()) {
                s.append(" sAcc=").append(mSpeedAccuracyMetersPerSecond);
            }
        }
        if (hasBearing()) {
            s.append(" bear=").append(mBearing);
            if (hasBearingAccuracy()) {
                s.append(" bAcc=").append(mBearingAccuracyDegrees);
            }
        }
        if (isMock()) {
            s.append(" mock");
        }

        if (mExtras != null) {
            s.append(" {").append(mExtras).append('}');
        }
        s.append(']');
        return s.toString();
    }

    public void dump(Printer pw, String prefix) {
        pw.println(prefix + toString());
    }

    public static final @NonNull Parcelable.Creator<Location> CREATOR =
        new Parcelable.Creator<Location>() {
        @Override
        public Location createFromParcel(Parcel in) {
            Location l = new Location(in.readString());
            l.mFieldsMask = in.readInt();
            l.mTime = in.readLong();
            l.mElapsedRealtimeNanos = in.readLong();
            if (l.hasElapsedRealtimeUncertaintyNanos()) {
                l.mElapsedRealtimeUncertaintyNanos = in.readDouble();
            }
            l.mLatitude = in.readDouble();
            l.mLongitude = in.readDouble();
            if (l.hasAltitude()) {
                l.mAltitude = in.readDouble();
            }
            if (l.hasSpeed()) {
                l.mSpeed = in.readFloat();
            }
            if (l.hasBearing()) {
                l.mBearing = in.readFloat();
            }
            if (l.hasAccuracy()) {
                l.mHorizontalAccuracyMeters = in.readFloat();
            }
            if (l.hasVerticalAccuracy()) {
                l.mVerticalAccuracyMeters = in.readFloat();
            }
            if (l.hasSpeedAccuracy()) {
                l.mSpeedAccuracyMetersPerSecond = in.readFloat();
            }
            if (l.hasBearingAccuracy()) {
                l.mBearingAccuracyDegrees = in.readFloat();
            }
            l.mExtras = Bundle.setDefusable(in.readBundle(), true);
            return l;
        }

        @Override
        public Location[] newArray(int size) {
            return new Location[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mProvider);
        parcel.writeInt(mFieldsMask);
        parcel.writeLong(mTime);
        parcel.writeLong(mElapsedRealtimeNanos);
        if (hasElapsedRealtimeUncertaintyNanos()) {
            parcel.writeDouble(mElapsedRealtimeUncertaintyNanos);
        }
        parcel.writeDouble(mLatitude);
        parcel.writeDouble(mLongitude);
        if (hasAltitude()) {
            parcel.writeDouble(mAltitude);
        }
        if (hasSpeed()) {
            parcel.writeFloat(mSpeed);
        }
        if (hasBearing()) {
            parcel.writeFloat(mBearing);
        }
        if (hasAccuracy()) {
            parcel.writeFloat(mHorizontalAccuracyMeters);
        }
        if (hasVerticalAccuracy()) {
            parcel.writeFloat(mVerticalAccuracyMeters);
        }
        if (hasSpeedAccuracy()) {
            parcel.writeFloat(mSpeedAccuracyMetersPerSecond);
        }
        if (hasBearingAccuracy()) {
            parcel.writeFloat(mBearingAccuracyDegrees);
        }
        parcel.writeBundle(mExtras);
    }

    /**
     * Returns true if the Location came from a mock provider.
     *
     * @return true if this Location came from a mock provider, false otherwise
     * @deprecated Prefer {@link #isMock()} instead.
     */
    @Deprecated
    public boolean isFromMockProvider() {
        return isMock();
    }

    /**
     * Flag this Location as having come from a mock provider or not.
     *
     * @param isFromMockProvider true if this Location came from a mock provider, false otherwise
     * @deprecated Prefer {@link #setMock(boolean)} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    public void setIsFromMockProvider(boolean isFromMockProvider) {
        setMock(isFromMockProvider);
    }

    /**
     * Returns true if this location is marked as a mock location. If this location comes from the
     * Android framework, this indicates that the location was provided by a test location provider,
     * and thus may not be related to the actual location of the device.
     *
     * @see LocationManager#addTestProvider
     */
    public boolean isMock() {
        return (mFieldsMask & HAS_MOCK_PROVIDER_MASK) != 0;
    }

    /**
     * Sets whether this location is marked as a mock location.
     */
    public void setMock(boolean mock) {
        if (mock) {
            mFieldsMask |= HAS_MOCK_PROVIDER_MASK;
        } else {
            mFieldsMask &= ~HAS_MOCK_PROVIDER_MASK;
        }
    }

    /**
     * Caches data used to compute distance and bearing (so successive calls to {@link #distanceTo}
     * and {@link #bearingTo} don't duplicate work.
     */
    private static class BearingDistanceCache {
        double mLat1 = 0.0;
        double mLon1 = 0.0;
        double mLat2 = 0.0;
        double mLon2 = 0.0;
        float mDistance = 0.0f;
        float mInitialBearing = 0.0f;
        float mFinalBearing = 0.0f;
    }
}
