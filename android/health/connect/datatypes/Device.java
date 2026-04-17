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

package android.health.connect.datatypes;

import static android.health.connect.datatypes.validation.ValidationUtils.validateIntDefValue;

import static com.android.healthfitness.flags.Flags.FLAG_NEW_DEVICE_TYPES;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.healthfitness.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A physical device (such as phone, watch, scale, or chest strap) which captured associated health
 * data point.
 *
 * <p>Device needs to be populated by users of the API. Metadata fields not provided by clients will
 * remain absent.
 */
public final class Device {
    /**
     * @see Device
     */
    public static final class Builder {
        @Nullable private String mManufacturer;
        @Nullable private String mModel;
        @DeviceType private int mType = DEVICE_TYPE_UNKNOWN;
        @Nullable private String mDisplayName;
        @Nullable private String mUdi;

        /** Sets an optional client supplied manufacturer of the device */
        @NonNull
        public Builder setManufacturer(@Nullable String manufacturer) {
            mManufacturer = manufacturer;
            return this;
        }

        /** Sets an optional client supplied model of the device */
        @NonNull
        public Builder setModel(@Nullable String model) {
            mModel = model;
            return this;
        }

        /** Sets an optional client supplied type of the device */
        @NonNull
        public Builder setType(@DeviceType int type) {
            mType = type;
            return this;
        }

        /** Sets an optional display name for the device */
        @FlaggedApi(Flags.FLAG_DEVICE_DATA_PROVIDERS_API)
        @NonNull
        public Builder setDisplayName(@Nullable String displayName) {
            mDisplayName = displayName;
            return this;
        }

        /**
         * Sets an optional client supplied UDI (Unique Device Identifier), a unique numeric or
         * alphanumeric code assigned to a medical device, for this {@link Device} instance.
         *
         * <p>A device receives a UDI from an accredited issuing agency (e.g. from GS1) which is
         * then registered in multiple regulatory databases (e.g. FDA). The UDI is a unique,
         * globally recognized identifier assigned to a specific device model. An accredited issuing
         * agency, like GS1, provides the global standards used to create the UDI codes. The
         * manufacturers must submit the UDI provided by GS1 to the relevant regulatory databases
         * for each market they operate in.
         *
         * <ul>
         *   <li>In the US, this is the FDA’s Global Unique Device Identification Database (GUDID).
         *   <li>In the EU, it is the European Database on Medical Devices (EUDAMED).
         * </ul>
         *
         * <p>The calling package needs to declare {@link HealthPermissions#WRITE_DEVICE_UDI} in
         * manifest to be able to set UDI for any record. {@link SecurityException} is thrown when
         * upserting a record with UDI without the permission.
         */
        @FlaggedApi(Flags.FLAG_DEVICE_UDI)
        @NonNull
        public Builder setUdi(@Nullable String udi) {
            mUdi = udi;
            return this;
        }

        /** Build and return {@link Device} object */
        @NonNull
        public Device build() {
            return new Device(
                    mManufacturer,
                    mModel,
                    mType,
                    Flags.deviceDataProvidersApi() ? mDisplayName : null,
                    Flags.deviceUdi() ? mUdi : null);
        }
    }

    /**
     * A device whose specific type is not identified, not yet supported in the Health Connect list,
     * or where the originating device type could not be determined by the data writer.
     */
    public static final int DEVICE_TYPE_UNKNOWN = 0;

    /**
     * A wearable computing device designed to be worn on the wrist, typically featuring an
     * interactive display and offering a broad range of functionalities including, but not limited
     * to, health and fitness tracking, app integration, and communication.
     */
    public static final int DEVICE_TYPE_WATCH = 1;

    /**
     * A handheld mobile computing and communication device, equipped with various sensors (e.g.,
     * accelerometer, GPS) that can collect health and fitness data, or serve as a primary interface
     * for manual data entry or managing data from connected peripherals.
     */
    public static final int DEVICE_TYPE_PHONE = 2;

    /** Devices designed to measure body weight and often other body composition metrics. */
    public static final int DEVICE_TYPE_SCALE = 3;

    /**
     * Wearable devices worn on a finger, typically designed for discreet tracking of sleep,
     * activity, and potentially other physiological data.
     */
    public static final int DEVICE_TYPE_RING = 4;

    /**
     * Devices worn on the head, beyond glasses, designed for specific health or fitness
     * applications, often involving sensing or stimulation.
     *
     * <p>For example: VR/MR Headsets, ECG Head Bands
     */
    public static final int DEVICE_TYPE_HEAD_MOUNTED = 5;

    /**
     * Wearable devices, typically worn on the wrist or arm, primarily focused on tracking physical
     * activity and basic health metrics.
     */
    public static final int DEVICE_TYPE_FITNESS_BAND = 6;

    /**
     * Wearable straps worn around the chest, primarily used for highly accurate heart rate
     * monitoring during exercise.
     */
    public static final int DEVICE_TYPE_CHEST_STRAP = 7;

    /**
     * Stationary devices with a screen and connectivity, often used in homes or gyms to provide
     * guided workouts, track progress, and offer health-related information.
     *
     * <p>For example: Home Smart Displays, Interactive Fitness Mirrors
     */
    public static final int DEVICE_TYPE_SMART_DISPLAY = 8;

    /**
     * Over-the-counter Medical devices.
     *
     * <p>For example: CGM / Glucometers, Blood Pressure Cuff
     */
    @FlaggedApi(FLAG_NEW_DEVICE_TYPES)
    public static final int DEVICE_TYPE_CONSUMER_MEDICAL_DEVICE = 9;

    /** Wearable glasses with integrated technology and computing capabilities. */
    @FlaggedApi(FLAG_NEW_DEVICE_TYPES)
    public static final int DEVICE_TYPE_GLASSES = 10;

    /** Electronic devices worn in or around the ears, often with audio capabilities. */
    @FlaggedApi(FLAG_NEW_DEVICE_TYPES)
    public static final int DEVICE_TYPE_HEARABLE = 11;

    /**
     * Stationary or mobile equipment designed for physical exercise.
     *
     * <p>For example: Treadmill, Indoor Cycles, Rowing Machines, Outdoor Bicycle. Outdoor Scooter
     */
    @FlaggedApi(FLAG_NEW_DEVICE_TYPES)
    public static final int DEVICE_TYPE_FITNESS_MACHINE = 12;

    /**
     * Tools and accessories designed for use during physical exercise.
     *
     * <p>For example: Dumbbells, Jump Ropes
     */
    @FlaggedApi(FLAG_NEW_DEVICE_TYPES)
    public static final int DEVICE_TYPE_FITNESS_EQUIPMENT = 13;

    /**
     * A portable computer usually with GPS and performance tracking capabilities that is either
     * handheld or attached to a device.
     *
     * <p>For example: Handheld GPS, Cycling Computer, Rowing Computer
     */
    @FlaggedApi(FLAG_NEW_DEVICE_TYPES)
    public static final int DEVICE_TYPE_PORTABLE_COMPUTER = 14;

    /**
     * Equipment attachments that measure a specific metric.
     *
     * <p>For example: Pedal Meters, Insole Meters
     */
    @FlaggedApi(FLAG_NEW_DEVICE_TYPES)
    public static final int DEVICE_TYPE_METER = 15;

    // Instant records
    @Nullable private final String mManufacturer;
    @Nullable private final String mModel;
    @DeviceType private final int mType;
    @Nullable private final String mDisplayName;
    @Nullable private final String mUdi;

    /**
     * @param manufacturer An optional client supplied manufacturer of the device
     * @param model An optional client supplied model of the device
     * @param type An optional client supplied type of the device
     */
    private Device(
            @Nullable String manufacturer,
            @Nullable String model,
            @DeviceType int type,
            @Nullable String displayName,
            @Nullable String udi) {
        validateIntDefValue(type, Device.VALID_TYPES, DeviceType.class.getSimpleName());
        mManufacturer = manufacturer;
        mModel = model;
        mType = type;
        mDisplayName = displayName;
        mUdi = udi;
    }

    /**
     * @return The device manufacturer if set, null otherwise
     */
    @Nullable
    public String getManufacturer() {
        return mManufacturer;
    }

    /**
     * @return The device model if set, null otherwise
     */
    @Nullable
    public String getModel() {
        return mModel;
    }

    /**
     * @return The device type if set {@code DEVICE_TYPE_UNKNOWN} otherwise
     */
    @DeviceType
    public int getType() {
        return mType;
    }

    /**
     * @return The display name if set, null otherwise
     */
    @FlaggedApi(Flags.FLAG_DEVICE_DATA_PROVIDERS_API)
    @Nullable
    public String getDisplayName() {
        return mDisplayName;
    }

    /**
     * Returns the UDI (Unique Device Identifier), a unique numeric or alphanumeric code assigned to
     * a medical device, of this {@link Device} instance.
     *
     * <p>The identifier is entirely "self-declared" by the device or app writing the data. Health
     * Connect acts as a passive data store; it does not cross-reference this ID against the FDA's
     * GUDID database or any other regulatory registry to ensure the device is legitimate. The
     * presence of a UDI indicates that the data source is a Registered Medical Device. However, it
     * does not guarantee specific FDA Clearance or Approval. Data Readers should use the UDI to
     * query the GUDID database if they need to filter by Regulatory Class (I, II, or III) or
     * intended use.
     *
     * @return The device Unique Device Identifier (UDI) if set, null otherwise.
     */
    @FlaggedApi(Flags.FLAG_DEVICE_UDI)
    @Nullable
    public String getUdi() {
        return mUdi;
    }

    /**
     * Indicates whether some other object is "equal to" this one.
     *
     * @param object the reference object with which to compare.
     * @return {@code true} if this object is the same as the obj
     */
    @Override
    public boolean equals(@Nullable Object object) {
        if (this == object) return true;
        if (!(object instanceof Device other)) return false;

        if (Flags.deviceDataProvidersApi()
                && !Objects.equals(this.getDisplayName(), other.getDisplayName())) {
            return false;
        }

        if (Flags.deviceUdi() && !Objects.equals(this.getUdi(), other.getUdi())) {
            return false;
        }
        return this.getType() == other.getType()
                && Objects.equals(this.getManufacturer(), other.getManufacturer())
                && Objects.equals(this.getModel(), other.getModel());
    }

    /**
     * Returns a hash code value for the object.
     *
     * @return a hash code value for this object.
     */
    @Override
    public int hashCode() {
        return Objects.hash(
                this.getManufacturer(),
                this.getModel(),
                this.getType(),
                Flags.deviceDataProvidersApi() ? this.getDisplayName() : null,
                Flags.deviceUdi() ? this.getUdi() : null);
    }

    /**
     * Valid set of values for this IntDef. Update this set when add new type or deprecate existing
     * type.
     *
     * @hide
     */
    public static final Set<Integer> VALID_TYPES =
            Stream.concat(
                            Stream.of(
                                    DEVICE_TYPE_UNKNOWN,
                                    DEVICE_TYPE_WATCH,
                                    DEVICE_TYPE_PHONE,
                                    DEVICE_TYPE_SCALE,
                                    DEVICE_TYPE_RING,
                                    DEVICE_TYPE_HEAD_MOUNTED,
                                    DEVICE_TYPE_FITNESS_BAND,
                                    DEVICE_TYPE_CHEST_STRAP,
                                    DEVICE_TYPE_SMART_DISPLAY),
                            Flags.newDeviceTypes()
                                    ? Stream.of(
                                            DEVICE_TYPE_CONSUMER_MEDICAL_DEVICE,
                                            DEVICE_TYPE_GLASSES,
                                            DEVICE_TYPE_HEARABLE,
                                            DEVICE_TYPE_FITNESS_MACHINE,
                                            DEVICE_TYPE_FITNESS_EQUIPMENT,
                                            DEVICE_TYPE_PORTABLE_COMPUTER,
                                            DEVICE_TYPE_METER)
                                    : Stream.empty())
                    .collect(Collectors.toUnmodifiableSet());

    /** @hide */
    @IntDef({
        DEVICE_TYPE_UNKNOWN,
        DEVICE_TYPE_WATCH,
        DEVICE_TYPE_PHONE,
        DEVICE_TYPE_SCALE,
        DEVICE_TYPE_RING,
        DEVICE_TYPE_HEAD_MOUNTED,
        DEVICE_TYPE_FITNESS_BAND,
        DEVICE_TYPE_CHEST_STRAP,
        DEVICE_TYPE_SMART_DISPLAY,
        DEVICE_TYPE_CONSUMER_MEDICAL_DEVICE,
        DEVICE_TYPE_GLASSES,
        DEVICE_TYPE_HEARABLE,
        DEVICE_TYPE_FITNESS_MACHINE,
        DEVICE_TYPE_FITNESS_EQUIPMENT,
        DEVICE_TYPE_PORTABLE_COMPUTER,
        DEVICE_TYPE_METER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeviceType {}
}
