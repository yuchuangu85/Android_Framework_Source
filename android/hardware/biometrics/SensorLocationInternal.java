/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.hardware.biometrics;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.hardware.biometrics.fingerprint.SensorLocationData;
import android.hardware.biometrics.fingerprint.location.PhysicalSensorLocation;
import android.hardware.biometrics.fingerprint.location.PowerButtonDisplayLocation;
import android.hardware.biometrics.fingerprint.location.UnderDisplayLocation;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * The location of a sensor relative to a physical display.
 *
 * Note that the location may change depending on other attributes of the device, such as
 * fold status, which are not yet included in this class.
 * @hide
 */
public class SensorLocationInternal implements Parcelable {

    /** Default value to use when the sensor's location is unknown or undefined. */
    public static final SensorLocationInternal DEFAULT = new SensorLocationInternal("", 0, 0, 0);

    /**
     * The stable display id.
     */
    @NonNull
    public final String displayId;

    /**
     * The location of the center of the sensor if applicable. For example, sensors of type
     * {@link FingerprintSensorProperties#TYPE_UDFPS_OPTICAL} would report this value as the
     * distance in pixels, measured from the left edge of the screen.
     */
    public final int sensorLocationX;

    /**
     * The location of the center of the sensor if applicable. For example, sensors of type
     * {@link FingerprintSensorProperties#TYPE_UDFPS_OPTICAL} would report this value as the
     * distance in pixels, measured from the top edge of the screen.
     *
     */
    public final int sensorLocationY;

    /**
     * The radius of the sensor if applicable. For example, sensors of type
     * {@link FingerprintSensorProperties#TYPE_UDFPS_OPTICAL} would report this value as the radius
     * of the sensor, in pixels.
     */
    public final int sensorRadius;

    /**
     * The specified location data for the sensor
     * May be null for legacy HALs.
     */
    @Nullable
    public final SensorLocationData sensorLocationData;

    public SensorLocationInternal(@Nullable String displayId,
            int sensorLocationX, int sensorLocationY, int sensorRadius) {
        this(displayId, sensorLocationX, sensorLocationY, sensorRadius, null);
    }

    public SensorLocationInternal(@Nullable String displayId,
            int sensorLocationX, int sensorLocationY, int sensorRadius,
            @Nullable SensorLocationData sensorLocationData) {
        this.displayId = displayId != null ? displayId : "";
        this.sensorLocationData = sensorLocationData;

        if (sensorLocationData != null) {
            switch (sensorLocationData.getTag()) {
                case SensorLocationData.underDisplayLocation:
                    UnderDisplayLocation udfpsLocation =
                            sensorLocationData.getUnderDisplayLocation();
                    this.sensorLocationX = udfpsLocation.sensorLocationXPixels;
                    this.sensorLocationY = udfpsLocation.sensorLocationYPixels;
                    this.sensorRadius = udfpsLocation.sensorRadiusPixels;
                    break;
                case SensorLocationData.powerButtonDisplayLocation:
                    PowerButtonDisplayLocation sfpsLocation =
                            sensorLocationData.getPowerButtonDisplayLocation();
                    this.sensorLocationX = sfpsLocation.sensorLocationXPixels;
                    this.sensorLocationY = sfpsLocation.sensorLocationYPixels;
                    this.sensorRadius = sfpsLocation.sensorRadiusPixels;
                    break;
                default:
                    this.sensorLocationX = 0;
                    this.sensorLocationY = 0;
                    this.sensorRadius = 0;
                    break;
            }
        } else {
            this.sensorLocationX = sensorLocationX;
            this.sensorLocationY = sensorLocationY;
            this.sensorRadius = sensorRadius;
        }
    }

    protected SensorLocationInternal(Parcel in) {
        displayId = in.readString16NoHelper();
        sensorLocationX = in.readInt();
        sensorLocationY = in.readInt();
        sensorRadius = in.readInt();
        sensorLocationData = in.readTypedObject(SensorLocationData.CREATOR);
    }

    /**
     * @return {@link PhysicalSensorLocation} when available or
     * {@link PhysicalSensorLocation#UNKNOWN}.
     */
    public byte getStandalonePhysicalLocation() {
        if (sensorLocationData != null) {
            return sensorLocationData.getStandaloneLocation().location;
        }
        return PhysicalSensorLocation.UNKNOWN;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(displayId);
        dest.writeInt(sensorLocationX);
        dest.writeInt(sensorLocationY);
        dest.writeInt(sensorRadius);
        dest.writeTypedObject(sensorLocationData, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<SensorLocationInternal> CREATOR =
            new Creator<SensorLocationInternal>() {
        @Override
        public SensorLocationInternal createFromParcel(Parcel in) {
            return new SensorLocationInternal(in);
        }

        @Override
        public SensorLocationInternal[] newArray(int size) {
            return new SensorLocationInternal[size];
        }
    };

    @Override
    public String toString() {
        return "[id: " + displayId
                + ", x: " + sensorLocationX
                + ", y: " + sensorLocationY
                + ", r: " + sensorRadius
                + ", locationData: " + sensorLocationData + "]";
    }

    /** Returns coordinates of a bounding box around the sensor. */
    public Rect getRect() {
        return new Rect(sensorLocationX - sensorRadius,
                sensorLocationY - sensorRadius,
                sensorLocationX + sensorRadius,
                sensorLocationY + sensorRadius);
    }
}
