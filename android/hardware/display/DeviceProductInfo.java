/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.hardware.display;

import static com.android.graphics.surfaceflinger.flags.Flags.FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Product-specific information about the display or the directly connected device on the
 * display chain. For example, if the display is transitively connected, this field may contain
 * product information about the intermediate device.
 */
public final class DeviceProductInfo implements Parcelable {
    /** @hide */
    @IntDef(prefix = {"CONNECTION_TO_SINK_"}, value = {
            CONNECTION_TO_SINK_UNKNOWN,
            CONNECTION_TO_SINK_BUILT_IN,
            CONNECTION_TO_SINK_DIRECT,
            CONNECTION_TO_SINK_TRANSITIVE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ConnectionToSinkType { }

    /** @hide */
    @IntDef(prefix = {"VIDEO_INPUT_TYPE_"},
            value = {VIDEO_INPUT_TYPE_UNKNOWN, VIDEO_INPUT_TYPE_ANALOG, VIDEO_INPUT_TYPE_DIGITAL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface VideoInputType {}

    /** The device input type is unknown. */
    @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
    public static final int VIDEO_INPUT_TYPE_UNKNOWN = -1;

    /** The device has an analog input. */
    @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
    public static final int VIDEO_INPUT_TYPE_ANALOG = 0;

    /** The device has a digital input. */
    @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
    public static final int VIDEO_INPUT_TYPE_DIGITAL = 1;

    /** The device connection to the display sink is unknown. */
    public static final int CONNECTION_TO_SINK_UNKNOWN =
            IDeviceProductInfoConstants.CONNECTION_TO_SINK_UNKNOWN;

    /** The display sink is built-in to the device */
    public static final int CONNECTION_TO_SINK_BUILT_IN =
            IDeviceProductInfoConstants.CONNECTION_TO_SINK_BUILT_IN;

    /** The device is directly connected to the display sink. */
    public static final int CONNECTION_TO_SINK_DIRECT =
            IDeviceProductInfoConstants.CONNECTION_TO_SINK_DIRECT;

    /** The device is transitively connected to the display sink. */
    public static final int CONNECTION_TO_SINK_TRANSITIVE =
            IDeviceProductInfoConstants.CONNECTION_TO_SINK_TRANSITIVE;

    private final String mName;
    private final String mManufacturerPnpId;
    private final String mProductId;
    private final Integer mModelYear;
    private final ManufactureDate mManufactureDate;
    private final @ConnectionToSinkType int mConnectionToSinkType;
    private final EdidStructureMetadata mEdidStructureMetadata;
    private final @VideoInputType int mVideoInputType;

    /** @hide */
    public DeviceProductInfo(String name, String manufacturerPnpId, String productId,
            Integer modelYear, ManufactureDate manufactureDate, int connectionToSinkType,
            EdidStructureMetadata edidStructureMetadata, @VideoInputType int videoInputType) {
        mName = name;
        mManufacturerPnpId = manufacturerPnpId;
        mProductId = productId;
        mModelYear = modelYear;
        mManufactureDate = manufactureDate;
        mConnectionToSinkType = connectionToSinkType;
        mEdidStructureMetadata = edidStructureMetadata;
        mVideoInputType = videoInputType;
    }

    private DeviceProductInfo(@Nullable String name, @NonNull String manufacturerPnpId,
            @NonNull String productId, @IntRange(from = 1990) int modelYear,
            @Nullable ManufactureDate manufactureDate,
            @ConnectionToSinkType int connectionToSinkType,
            @Nullable EdidStructureMetadata edidStructureMetadata,
            @VideoInputType int videoInputType) {
        mName = name;
        mManufacturerPnpId = Objects.requireNonNull(manufacturerPnpId);
        mProductId = Objects.requireNonNull(productId);
        mModelYear = modelYear;
        mManufactureDate = manufactureDate;
        mConnectionToSinkType = connectionToSinkType;
        mEdidStructureMetadata = edidStructureMetadata;
        mVideoInputType = videoInputType;
    }

    public DeviceProductInfo(@Nullable String name, @NonNull String manufacturerPnpId,
            @NonNull String productId, @IntRange(from = 1990) int modelYear,
            @ConnectionToSinkType int connectionToSinkType) {
        mName = name;
        mManufacturerPnpId = Objects.requireNonNull(manufacturerPnpId);
        mProductId = Objects.requireNonNull(productId);
        mModelYear = modelYear;
        mManufactureDate = null;
        mConnectionToSinkType = connectionToSinkType;
        mEdidStructureMetadata = null;
        mVideoInputType = VIDEO_INPUT_TYPE_UNKNOWN;
    }

    private DeviceProductInfo(Parcel in) {
        mName = in.readString();
        mManufacturerPnpId = in.readString();
        mProductId = (String) in.readValue(null);
        mModelYear = (Integer) in.readValue(null);
        mManufactureDate = (ManufactureDate) in.readValue(null);
        mConnectionToSinkType = in.readInt();
        mEdidStructureMetadata = in.readTypedObject(EdidStructureMetadata.CREATOR);
        mVideoInputType = in.readInt();
    }

    /**
     * Builder for {@link DeviceProductInfo}.
     */
    @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
    public static final class Builder {
        private String mName;
        private String mManufacturerPnpId;
        private String mProductId;
        private int mModelYear = -1;
        private ManufactureDate mManufactureDate = new ManufactureDate(-1, -1);
        private @ConnectionToSinkType int mConnectionToSinkType = CONNECTION_TO_SINK_UNKNOWN;
        private EdidStructureMetadata mEdidStructureMetadata = null;
        private @VideoInputType int mVideoInputType = VIDEO_INPUT_TYPE_UNKNOWN;

        /**
         * Creates a new Builder.
         *
         * @param manufacturerPnpId The Manufacturer Plug and Play ID.
         * @param productId The product ID.
         */
        @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
        public Builder(@NonNull String manufacturerPnpId, @NonNull String productId) {
            mManufacturerPnpId = Objects.requireNonNull(manufacturerPnpId);
            mProductId = Objects.requireNonNull(productId);
        }

        /**
         * Sets the display name.
         *
         * @param name The display name.
         * @return This builder.
         */
        @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
        @NonNull
        public Builder setName(@Nullable String name) {
            mName = name;
            return this;
        }

        /**
         * Sets the Manufacturer Plug and Play ID.
         *
         * @param manufacturerPnpId The Manufacturer Plug and Play ID.
         * @return This builder.
         */
        @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
        @NonNull
        public Builder setManufacturerPnpId(@NonNull String manufacturerPnpId) {
            mManufacturerPnpId = Objects.requireNonNull(manufacturerPnpId);
            return this;
        }

        /**
         * Sets the product ID.
         *
         * @param productId The product ID.
         * @return This builder.
         */
        @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
        @NonNull
        public Builder setProductId(@NonNull String productId) {
            mProductId = Objects.requireNonNull(productId);
            return this;
        }

        /**
         * Sets the model year of the device.
         *
         * @param modelYear The model year, must be >= 1990, or -1 if not available.
         * @return This builder.
         */
        @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
        @NonNull
        public Builder setModelYear(@IntRange(from = -1) int modelYear) {
            if (modelYear != -1 && modelYear < 1990) {
                throw new IllegalArgumentException(
                        "modelYear must be >= 1990, or -1 if not available.");
            }
            mModelYear = modelYear;
            return this;
        }

        /**
         * Sets the manufacturing week and year of the device.
         *
         * @param manufactureWeek Week of manufacturing, must be >= 1 and <=53, or -1 if not
         *         available.
         * @param manufactureYear Year of manufacturing, must be >= 1990, or -1 if not available.
         */
        @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
        // Getters are already public and are split to getManufactureWeek and getManufactureYear
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setManufactureDate(@IntRange(from = -1, to = 53) int manufactureWeek,
                @IntRange(from = -1) int manufactureYear) {
            if (manufactureWeek < -1 || manufactureWeek > 53) {
                throw new IllegalArgumentException(
                        "manufactureWeek must be >= 1 and <= 53, or -1 if missing");
            }
            if (manufactureWeek == 0) {
                throw new IllegalArgumentException("manufactureWeek cannot be 0.");
            }
            if (manufactureYear != -1 && manufactureYear < 1990) {
                throw new IllegalArgumentException(
                        "manufactureYear must be >= 1990, or -1 if not available.");
            }

            mManufactureDate = new ManufactureDate(manufactureWeek, manufactureYear);
            return this;
        }

        /**
         * Sets how the current device is connected to the display sink.
         *
         * @param connectionToSinkType The connection type.
         * @see #CONNECTION_TO_SINK_UNKNOWN
         * @see #CONNECTION_TO_SINK_BUILT_IN
         * @see #CONNECTION_TO_SINK_DIRECT
         * @see #CONNECTION_TO_SINK_TRANSITIVE
         * @return This builder.
         */
        @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
        @NonNull
        public Builder setConnectionToSinkType(@ConnectionToSinkType int connectionToSinkType) {
            mConnectionToSinkType = connectionToSinkType;
            return this;
        }

        /**
         * Sets the EDID structure metadata. If left unset, {@link EdidStructureMetadata} will be
         * null.
         *
         * @param edidStructureVersion The EDID version, must be >= 0.
         * @param edidStructureRevision The EDID revision, must be >= 0.
         * @return This builder.
         */
        @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
        @NonNull
        public Builder setEdidStructureMetadata(@IntRange(from = 0) int edidStructureVersion,
                @IntRange(from = 0) int edidStructureRevision) {
            if (edidStructureVersion < 0) {
                throw new IllegalArgumentException("edidStructureVersion must be >= 0");
            }
            if (edidStructureRevision < 0) {
                throw new IllegalArgumentException("edidStructureRevision must be >= 0");
            }

            mEdidStructureMetadata =
                    new EdidStructureMetadata(edidStructureVersion, edidStructureRevision);
            return this;
        }

        /**
         * Sets the video input type of the device.
         *
         * @param videoInputType The video input type.
         * @see #VIDEO_INPUT_TYPE_UNKNOWN
         * @see #VIDEO_INPUT_TYPE_ANALOG
         * @see #VIDEO_INPUT_TYPE_DIGITAL
         * @return This builder.
         */
        @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
        @NonNull
        public Builder setVideoInputType(@VideoInputType int videoInputType) {
            mVideoInputType = videoInputType;
            return this;
        }

        /**
         * Builds a new {@link DeviceProductInfo} instance.
         *
         * @return A new {@link DeviceProductInfo}.
         */
        @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
        @NonNull
        public DeviceProductInfo build() {
            return new DeviceProductInfo(mName, mManufacturerPnpId, mProductId, mModelYear,
                    mManufactureDate, mConnectionToSinkType, mEdidStructureMetadata,
                    mVideoInputType);
        }
    }

    /**
     * @return Display name.
     */
    @Nullable
    public String getName() {
        return mName;
    }

    /**
     * Returns the Manufacturer Plug and Play ID. This ID identifies the manufacture according to
     * the list: https://uefi.org/PNP_ID_List. It consist of 3 characters, each character
     * is an uppercase letter (A-Z).
     * @return Manufacturer Plug and Play ID.
     */
    @NonNull
    public String getManufacturerPnpId() {
        return mManufacturerPnpId;
    }

    /**
     * @return Manufacturer product ID.
     */
    @NonNull
    public String getProductId() {
        return mProductId;
    }

    /**
     * @return Model year of the device. Return -1 if not available. Typically,
     * one of model year or manufacture year is available.
     */
    @IntRange(from = -1)
    public int getModelYear()  {
        return mModelYear != null ? mModelYear : -1;
    }

    /**
     * @return The year of manufacture, or -1 it is not available. Typically,
     * one of model year or manufacture year is available.
     */
    @IntRange(from = -1)
    public int getManufactureYear()  {
        if (mManufactureDate == null) {
            return -1;
        }
        return mManufactureDate.mYear != null ? mManufactureDate.mYear : -1;
    }

    /**
     * @return The week of manufacture which ranges from 1 to 53, or -1 it is not available.
     * Typically, it is not present if model year is available.
     */
    @IntRange(from = -1, to = 53)
    public int getManufactureWeek() {
        if (mManufactureDate == null) {
            return -1;
        }
        return mManufactureDate.mWeek != null ?  mManufactureDate.mWeek : -1;
    }

    /**
     * @return Manufacture date. Typically exactly one of model year or manufacture
     * date will be present.
     *
     * @hide
     */
    public ManufactureDate getManufactureDate() {
        return mManufactureDate;
    }

    /**
     * @return How the current device is connected to the display sink. For example, the display
     * can be connected immediately to the device or there can be a receiver in between.
     */
    @ConnectionToSinkType
    public int getConnectionToSinkType() {
        return mConnectionToSinkType;
    }

    /**
     * @return An {@link EdidStructureMetadata} containing the EDID major version and minor
     * revision (e.g., version 1, revision 4 for EDID 1.4), or null if unavailable.
     */
    @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
    @Nullable
    public EdidStructureMetadata getEdidStructureMetadata() {
        return mEdidStructureMetadata;
    }

    /**
     * @return The video input type of the device.
     * @see #VIDEO_INPUT_TYPE_UNKNOWN
     * @see #VIDEO_INPUT_TYPE_ANALOG
     * @see #VIDEO_INPUT_TYPE_DIGITAL
     */
    @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
    @VideoInputType
    public int getVideoInputType() {
        return mVideoInputType;
    }

    @Override
    public String toString() {
        return "DeviceProductInfo{"
                + "name=" + mName + ", manufacturerPnpId=" + mManufacturerPnpId + ", productId="
                + mProductId + ", modelYear=" + mModelYear + ", manufactureDate=" + mManufactureDate
                + ", connectionToSinkType=" + mConnectionToSinkType + ", edidStructureMetadata="
                + mEdidStructureMetadata + ", videoInputType=" + mVideoInputType + '}';
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeviceProductInfo that = (DeviceProductInfo) o;
        return Objects.equals(mName, that.mName)
                && Objects.equals(mManufacturerPnpId, that.mManufacturerPnpId)
                && Objects.equals(mProductId, that.mProductId)
                && Objects.equals(mModelYear, that.mModelYear)
                && Objects.equals(mManufactureDate, that.mManufactureDate)
                && mConnectionToSinkType == that.mConnectionToSinkType
                && Objects.equals(mEdidStructureMetadata, that.mEdidStructureMetadata)
                && mVideoInputType == that.mVideoInputType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mName, mManufacturerPnpId, mProductId, mModelYear, mManufactureDate,
                mConnectionToSinkType, mEdidStructureMetadata, mVideoInputType);
    }

    @NonNull public static final Creator<DeviceProductInfo> CREATOR =
            new Creator<DeviceProductInfo>() {
                @Override
                public DeviceProductInfo createFromParcel(Parcel in) {
                    return new DeviceProductInfo(in);
                }

                @Override
                public DeviceProductInfo[] newArray(int size) {
                    return new DeviceProductInfo[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeString(mManufacturerPnpId);
        dest.writeValue(mProductId);
        dest.writeValue(mModelYear);
        dest.writeValue(mManufactureDate);
        dest.writeInt(mConnectionToSinkType);
        dest.writeTypedObject(mEdidStructureMetadata, flags);
        dest.writeInt(mVideoInputType);
    }

    /**
     * Stores information about the date of manufacture.
     *
     * @hide
     */
    public static class ManufactureDate implements Parcelable {
        private final Integer mWeek;
        private final Integer mYear;

        public ManufactureDate(Integer week, Integer year) {
            mWeek = week;
            mYear = year;
        }

        protected ManufactureDate(Parcel in) {
            mWeek = (Integer) in.readValue(null);
            mYear = (Integer) in.readValue(null);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeValue(mWeek);
            dest.writeValue(mYear);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<ManufactureDate> CREATOR =
                new Creator<ManufactureDate>() {
                    @Override
                    public ManufactureDate createFromParcel(Parcel in) {
                        return new ManufactureDate(in);
                    }

                    @Override
                    public ManufactureDate[] newArray(int size) {
                        return new ManufactureDate[size];
                    }
                };

        public Integer getYear() {
            return mYear;
        }

        public Integer getWeek() {
            return mWeek;
        }

        @Override
        public String toString() {
            return "ManufactureDate{week=" + mWeek + ", year=" + mYear + '}';
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ManufactureDate that = (ManufactureDate) o;
            return Objects.equals(mWeek, that.mWeek) && Objects.equals(mYear, that.mYear);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mWeek, mYear);
        }
    }

    /**
     * Encapsulates the EDID version, parsed from bytes 18 (version) and 19 (revision).
     */
    @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
    public static final class EdidStructureMetadata implements Parcelable {
        private final int mVersion;
        private final int mRevision;

        /** @hide */
        @TestApi
        public EdidStructureMetadata(
                @IntRange(from = 0) int version, @IntRange(from = 0) int revision) {
            mVersion = version;
            mRevision = revision;
        }

        private EdidStructureMetadata(Parcel in) {
            mVersion = in.readInt();
            mRevision = in.readInt();
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mVersion);
            dest.writeInt(mRevision);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @NonNull
        public static final Creator<EdidStructureMetadata> CREATOR =
                new Creator<EdidStructureMetadata>() {
                    @Override
                    public EdidStructureMetadata createFromParcel(Parcel in) {
                        return new EdidStructureMetadata(in);
                    }

                    @Override
                    public EdidStructureMetadata[] newArray(int size) {
                        return new EdidStructureMetadata[size];
                    }
                };

        /**
         * @return The EDID version. For example, if the EDID version is 1.4, this will return 1.
         */
        @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
        @IntRange(from = 0)
        public int getVersion() {
            return mVersion;
        }

        /**
         * @return The EDID revision. For example, if the EDID version is 1.4, this will return 4.
         */
        @FlaggedApi(FLAG_PARSE_EDID_VERSION_AND_INPUT_TYPE_V2)
        @IntRange(from = 0)
        public int getRevision() {
            return mRevision;
        }

        @Override
        public String toString() {
            return "EdidStructureMetadata{version=" + mVersion + ", revision=" + mRevision + '}';
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (!(o instanceof EdidStructureMetadata)) return false;
            EdidStructureMetadata metadata = (EdidStructureMetadata) o;
            return mVersion == metadata.mVersion && mRevision == metadata.mRevision;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mVersion, mRevision);
        }
    }
}
