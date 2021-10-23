/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package android.location;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.InvalidParameterException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

/**
 * A class implementing a container for data associated with a measurement event.
 * Events are delivered to registered instances of {@link Callback}.
 */
public final class GnssMeasurementsEvent implements Parcelable {
    private final GnssClock mClock;
    private final Collection<GnssMeasurement> mReadOnlyMeasurements;

    /**
     * Used for receiving GNSS satellite measurements from the GNSS engine.
     * Each measurement contains raw and computed data identifying a satellite.
     * You can implement this interface and call
     * {@link LocationManager#registerGnssMeasurementsCallback}.
     */
    public static abstract class Callback {
        /**
         * The status of the GNSS measurements event.
         * @deprecated Do not use.
         * @hide
         */
        @Deprecated
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({STATUS_NOT_SUPPORTED, STATUS_READY, STATUS_LOCATION_DISABLED, STATUS_NOT_ALLOWED})
        public @interface GnssMeasurementsStatus {}

        /**
         * The system does not support tracking of GNSS Measurements.
         *
         * <p>This status will not change in the future.
         *
         * @deprecated Do not use.
         */
        @Deprecated
        public static final int STATUS_NOT_SUPPORTED = 0;

        /**
         * GNSS Measurements are successfully being tracked, it will receive updates once they are
         * available.
         *
         * @deprecated Do not use.
         */
        @Deprecated
        public static final int STATUS_READY = 1;

        /**
         * GPS provider or Location is disabled, updates will not be received until they are
         * enabled.
         *
         * @deprecated Do not use.
         */
        @Deprecated
        public static final int STATUS_LOCATION_DISABLED = 2;

        /**
         * The client is not allowed to register for GNSS Measurements in general or in the
         * requested mode.
         *
         * <p>Such a status is returned when a client tries to request a functionality from the GNSS
         * chipset while another client has an ongoing request that does not allow such
         * functionality to be performed.
         *
         * <p>If such a status is received, one would try again at a later time point where no
         * other client is having a conflicting request.
         *
         * @deprecated Do not use.
         */
        @Deprecated
        public static final int STATUS_NOT_ALLOWED = 3;

        /**
         * Reports the latest collected GNSS Measurements.
         */
        public void onGnssMeasurementsReceived(GnssMeasurementsEvent eventArgs) {}

        /**
         * Reports the latest status of the GNSS Measurements sub-system.
         *
         * @deprecated Do not rely on this callback. From Android S onwards this callback will be
         * invoked once with {@link #STATUS_READY} in all cases for backwards compatibility, and
         * then never invoked again. Use LocationManager APIs if you need to determine if
         * GNSS measurements are supported or if location is off, etc...
         */
        @Deprecated
        public void onStatusChanged(@GnssMeasurementsStatus int status) {}
    }

    /**
     * @hide
     */
    @TestApi
    public GnssMeasurementsEvent(GnssClock clock, GnssMeasurement[] measurements) {
        if (clock == null) {
            throw new InvalidParameterException("Parameter 'clock' must not be null.");
        }
        if (measurements == null || measurements.length == 0) {
            mReadOnlyMeasurements = Collections.emptyList();
        } else {
            Collection<GnssMeasurement> measurementCollection = Arrays.asList(measurements);
            mReadOnlyMeasurements = Collections.unmodifiableCollection(measurementCollection);
        }

        mClock = clock;
    }

    /**
     * Gets the GNSS receiver clock information associated with the measurements for the current
     * event.
     */
    @NonNull
    public GnssClock getClock() {
        return mClock;
    }

    /**
     * Gets a read-only collection of measurements associated with the current event.
     */
    @NonNull
    public Collection<GnssMeasurement> getMeasurements() {
        return mReadOnlyMeasurements;
    }

    public static final @android.annotation.NonNull Creator<GnssMeasurementsEvent> CREATOR =
            new Creator<GnssMeasurementsEvent>() {
        @Override
        public GnssMeasurementsEvent createFromParcel(Parcel in) {
            ClassLoader classLoader = getClass().getClassLoader();

            GnssClock clock = in.readParcelable(classLoader);

            int measurementsLength = in.readInt();
            GnssMeasurement[] measurementsArray = new GnssMeasurement[measurementsLength];
            in.readTypedArray(measurementsArray, GnssMeasurement.CREATOR);

            return new GnssMeasurementsEvent(clock, measurementsArray);
        }

        @Override
        public GnssMeasurementsEvent[] newArray(int size) {
            return new GnssMeasurementsEvent[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeParcelable(mClock, flags);

        int measurementsCount = mReadOnlyMeasurements.size();
        GnssMeasurement[] measurementsArray =
                mReadOnlyMeasurements.toArray(new GnssMeasurement[measurementsCount]);
        parcel.writeInt(measurementsArray.length);
        parcel.writeTypedArray(measurementsArray, flags);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("[ GnssMeasurementsEvent:\n\n");

        builder.append(mClock.toString());
        builder.append("\n");

        for (GnssMeasurement measurement : mReadOnlyMeasurements) {
            builder.append(measurement.toString());
            builder.append("\n");
        }

        builder.append("]");

        return builder.toString();
    }
}
