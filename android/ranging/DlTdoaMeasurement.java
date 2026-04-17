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

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.ranging.uwb.DlTdoaRangingParams;
import android.ranging.uwb.DlTdoaRangingParams.MeasurementVersion;
import android.ranging.uwb.UwbSpecificData;
import android.ranging.uwb.UwbSpecificData.LineOfSight;
import android.util.Range;

import com.android.ranging.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.Math;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Represents a single measurement from a Downlink Time Difference of Arrival (DL-TDoA) ranging
 * session.
 *
 * <p>For more details, refer to the FiRa 2.0 and FiRa 4.0 specification.
 * @see <a href="https://groups.firaconsortium.org/wg/Technical/document/4590">FiRa_UCI_Technical_Specification_v2_0_0.docx</a>
 * @see <a href="https://groups.firaconsortium.org/wg/members/document/8696">FiRa_UCI_Technical_Specification_v4.0.0-pdf.zip</a>
 *
 * <p>This class encapsulates the data received in a DL-TDoA Message (DTM), including timestamps,
 * location data, and other metadata necessary for position calculation.
 */
@FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
public final class DlTdoaMeasurement implements Parcelable {
    /** DL-TDoA Message Type: Poll DTM */
    public static final int MESSAGE_TYPE_POLL_DTM = 0x00;
    /** DL-TDoA Message Type: Response DTM */
    public static final int MESSAGE_TYPE_RESPONSE_DTM = 0x01;
    /** DL-TDoA Message Type: Final DTM */
    public static final int MESSAGE_TYPE_FINAL_DTM = 0x02;
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        MESSAGE_TYPE_POLL_DTM,
        MESSAGE_TYPE_RESPONSE_DTM,
        MESSAGE_TYPE_FINAL_DTM
    })
    public @interface MessageType {}

    /**
     * Represents a location in the WGS-84 coordinate system.
     */
    @FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final class Wgs84Location implements Parcelable {
        private final double mLatitude;
        private final double mLongitude;
        private final double mAltitude;

        /** @hide */
        public Wgs84Location(double latitude, double longitude, double altitude) {
            mLatitude = latitude;
            mLongitude = longitude;
            mAltitude = altitude;
        }

        /**
         * Returns the latitude in degrees.
         *
         * <p>The ranging of this value is [-90, 90] with about 6.66 mm resolution.
         */
        @FloatRange(from = -90.0, to = 90.0)
        public double getLatitude() {
            return mLatitude;
        }

        /**
         * Returns the longitude in degrees.
         *
         * <p>The ranging of this value is [-180, 180] with about 6.66 mm resolution.
         */
        @FloatRange(from = -180.0, to = 180.0)
        public double getLongitude() {
            return mLongitude;
        }

        /**
         * Returns the altitude in meters.
         *
         * <p>The unit is kilometer and the resolution of this value is about 0.476 mm.
         */
        @FloatRange(from = -256.0, to = 256.0, fromInclusive = false, toInclusive = false)
        public double getAltitude() {
            return mAltitude;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            Objects.requireNonNull(dest);

            dest.writeDouble(mLatitude);
            dest.writeDouble(mLongitude);
            dest.writeDouble(mAltitude);
        }

        public static final @NonNull Creator<Wgs84Location> CREATOR =
                new Creator<Wgs84Location>() {
                    @Override
                    public Wgs84Location createFromParcel(Parcel in) {
                        return new Wgs84Location(
                                in.readDouble(),
                                in.readDouble(),
                                in.readDouble());
                    }

                    @Override
                    public Wgs84Location[] newArray(int size) {
                        return new Wgs84Location[size];
                    }
                };
        @Override
        public String toString() {
            return "Wgs84Location {"
                    + " Latitude=" + mLatitude
                    + ", Longitude=" + mLongitude
                    + ", Altitude=" + mAltitude
                    + " }";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Wgs84Location)) return false;
            Wgs84Location other = (Wgs84Location) o;
            return Double.valueOf(mLatitude).equals(other.mLatitude)
                    && Double.valueOf(mLongitude).equals(other.mLongitude)
                    && Double.valueOf(mAltitude).equals(other.mAltitude);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mLatitude, mLongitude, mAltitude);
        }
    }

    /**
     * Represents a location in a relative coordinate system.
     */
    @FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final class RelativeLocation implements Parcelable {
        private final int mX;
        private final int mY;
        private final int mZ;

        /** @hide */
        public RelativeLocation(int x, int y, int z) {
            mX = x;
            mY = y;
            mZ = z;
        }

        /**
         * Returns the X coordinate in millimeters.
         */
        @IntRange(from = -(1 << 27) + 1, to = (1 << 27) - 1)
        public int getX() {
            return mX;
        }

        /**
         * Returns the Y coordinate in millimeters.
         */
        @IntRange(from = -(1 << 27) + 1, to = (1 << 27) - 1)
        public int getY() {
            return mY;
        }

        /**
         * Returns the Z coordinate in millimeters.
         */
        @IntRange(from = -(1 << 23) + 1, to = (1 << 23) - 1)
        public int getZ() {
            return mZ;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            Objects.requireNonNull(dest);

            dest.writeInt(mX);
            dest.writeInt(mY);
            dest.writeInt(mZ);
        }

        public static final @NonNull Creator<RelativeLocation> CREATOR =
                new Creator<RelativeLocation>() {
                    @Override
                    public RelativeLocation createFromParcel(Parcel in) {
                        return new RelativeLocation(in.readInt(), in.readInt(), in.readInt());
                    }

                    @Override
                    public RelativeLocation[] newArray(int size) {
                        return new RelativeLocation[size];
                    }
                };

        @Override
        public String toString() {
            return "RelativeLocation {"
                    + " X=" + mX
                    + ", Y=" + mY
                    + ", Z=" + mZ
                    + " }";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RelativeLocation)) return false;
            RelativeLocation other = (RelativeLocation) o;
            return mX == other.mX
                    && mY == other.mY
                    && mZ == other.mZ;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mX, mY, mZ);
        }
    }

    /**
     * Represents a Z-element extension regarding the height of a anchor.
     */
    @FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final class ZElementExtension implements Parcelable {
        /** The anchor location is not expected to change. */
        public static final int MOVEMENT_EXPECTATION_STATIONARY = 0;
        /** The anchor location is expected to change. */
        public static final int MOVEMENT_EXPECTATION_MOVABLE = 1;
        /** The anchor's movement patterns are unknown. */
        public static final int MOVEMENT_EXPECTATION_UNKNOWN = 2;
        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({
                MOVEMENT_EXPECTATION_STATIONARY,
                MOVEMENT_EXPECTATION_MOVABLE,
                MOVEMENT_EXPECTATION_UNKNOWN
        })
        public @interface MovementExpectation {}

        private final double mAnchorFloorNumber;
        private final @MovementExpectation int mExpectedToMove;
        private final double mAnchorHeightAboveFloor;
        private final int mAnchorHeightAboveFloorUncertainty;
        private final boolean mIsAnchorFloorNumberOutOfRange;
        private final boolean mIsAnchorHeightAboveFloorOutOfRange;
        private final double mAnchorHeightAboveFloorRangeLower;
        private final double mAnchorHeightAboveFloorRangeUpper;

        /** @hide */
        public ZElementExtension(
                double anchorFloorNumber,
                @MovementExpectation int expectedToMove,
                double anchorHeightAboveFloor,
                int anchorHeightAboveFloorUncertainty,
                boolean isAnchorFloorNumberOutOfRange,
                boolean isAnchorHeightAboveFloorOutOfRange,
                double anchorHeightAboveFloorRangeLower,
                double anchorHeightAboveFloorRangeUpper) {
            mAnchorFloorNumber = anchorFloorNumber;
            mExpectedToMove = expectedToMove;
            mAnchorHeightAboveFloor = anchorHeightAboveFloor;
            mAnchorHeightAboveFloorUncertainty = anchorHeightAboveFloorUncertainty;
            mIsAnchorFloorNumberOutOfRange = isAnchorFloorNumberOutOfRange;
            mIsAnchorHeightAboveFloorOutOfRange = isAnchorHeightAboveFloorOutOfRange;
            mAnchorHeightAboveFloorRangeLower = anchorHeightAboveFloorRangeLower;
            mAnchorHeightAboveFloorRangeUpper = anchorHeightAboveFloorRangeUpper;
        }

        /**
         * Returns the anchor floor number.
         *
         * <p>The unit is floor and the resolution of this value is about 1/16 of a floor.
         * <p>The highest floor number which can be represented is 8191/16 = (roughly) 512 floors.
         * <p>The lowest floor number which can be represented is -8191/16 = (roughly) -512 floors.
         * <p>The anchor floor number is unknown if the value is Double.NaN.
         *
         * @return the anchor floor number or Double.NaN if the anchor floor number is unknown.
         */
        @FloatRange(from = -512.0, to = 512.0, fromInclusive = false, toInclusive = false)
        public double getAnchorFloorNumber() {
            return mAnchorFloorNumber;
        }

        /**
         * Returns whether the anchor floor number is out of range. If the value is out of range,
         * the anchor floor number field is set to the closest boundary value, and this method
         * returns true.
         */
        public boolean isAnchorFloorNumberOutOfRange() {
            return mIsAnchorFloorNumberOutOfRange;
        }

        /**
         * Returns the expected to move.
         */
        @MovementExpectation
        public int getExpectedToMove() {
            return mExpectedToMove;
        }

        /**
         * Returns the anchor height above floor.
         *
         * <p>The unit is meter and the resolution of this value is about 1/4096 meter (0.24mm).
         * <p>The highest anchor height above floor which can be represented is 8388607/4096 =
         * (roughly) 2048 meters.
         * <p>The lowest anchor height above floor which can be represented is -8388607/4096 =
         * (roughly) -2048 meters.
         * <p>The anchor height above floor is unknown if the value is Double.NaN.
         *
         * @return the anchor height above floor or Double.NaN if the anchor height above floor is
         *         unknown.
         */
        @FloatRange(from = -2048.0, to = 2048.0, fromInclusive = false, toInclusive = false)
        public double getAnchorHeightAboveFloor() {
            return mAnchorHeightAboveFloor;
        }

        /**
         * Returns whether the anchor height above floor is out of range. If the value is out of
         * range, the anchor height above floor field is set to the closest boundary value, and this
         * method returns true.
         */
        public boolean isAnchorHeightAboveFloorOutOfRange() {
            return mIsAnchorHeightAboveFloorOutOfRange;
        }

        /**
         * Returns the anchor height above floor uncertainty.
         *
         * <p>An Anchor Height Above Floor Uncertainty value of 0 indicates an unknown anchor height
         * above floor. Values 25 or higher are reserved. A value from 1 to 24 indicates that the
         * actual anchor height above floor, a, is bounded according to:
         * <p>h − 2 ^ (11 - u) <= a <= h + 2 ^ (11 - u)
         * <p>where
         * <p>h is the value in units of 1/4096 m of the anchor Height Above Floor field
         * <p>u is the value of the Anchors Height Above Floor Uncertainty field
         * <p>If the Anchor Height Above Floor field indicates an unknown anchor height above floor,
         * the Anchor Height Above Floor Uncertainty field is set to 0.
         */
        @IntRange(from = 0, to = 24)
        public int getAnchorHeightAboveFloorUncertainty() {
            return mAnchorHeightAboveFloorUncertainty;
        }

        /**
         * Returns the anchor height above floor range in meters with uncertainty.
         *
         * <p>If the anchor height above floor is unknown or the uncertainty is invalid, this value
         * will be set to null.
         */
        @Nullable
        public Range<Double> getAnchorHeightAboveFloorRange() {
            if (Double.isNaN(mAnchorHeightAboveFloorRangeLower)
                    || Double.isNaN(mAnchorHeightAboveFloorRangeUpper)) {
                return null;
            }
            return new Range<>(
                    mAnchorHeightAboveFloorRangeLower, mAnchorHeightAboveFloorRangeUpper);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            Objects.requireNonNull(dest);

            dest.writeDouble(mAnchorFloorNumber);
            dest.writeInt(mExpectedToMove);
            dest.writeDouble(mAnchorHeightAboveFloor);
            dest.writeInt(mAnchorHeightAboveFloorUncertainty);
            dest.writeBoolean(mIsAnchorFloorNumberOutOfRange);
            dest.writeBoolean(mIsAnchorHeightAboveFloorOutOfRange);
            dest.writeDouble(mAnchorHeightAboveFloorRangeLower);
            dest.writeDouble(mAnchorHeightAboveFloorRangeUpper);
        }

        public static final @NonNull Creator<ZElementExtension> CREATOR =
                new Creator<ZElementExtension>() {
                    @Override
                    public ZElementExtension createFromParcel(Parcel in) {
                        return new ZElementExtension(
                                in.readDouble(),
                                in.readInt(),
                                in.readDouble(),
                                in.readInt(),
                                in.readBoolean(),
                                in.readBoolean(),
                                in.readDouble(),
                                in.readDouble());
                    }

                    @Override
                    public ZElementExtension[] newArray(int size) {
                        return new ZElementExtension[size];
                    }
                };

        @Override
        public String toString() {
            return "ZElementExtension {"
                    + " AnchorFloorNumber=" + mAnchorFloorNumber
                    + ", ExpectedToMove=" + mExpectedToMove
                    + ", AnchorHeightAboveFloor=" + mAnchorHeightAboveFloor
                    + ", AnchorHeightAboveFloorUncertainty=" + mAnchorHeightAboveFloorUncertainty
                    + ", IsAnchorFloorNumberOutOfRange=" + mIsAnchorFloorNumberOutOfRange
                    + ", IsAnchorHeightAboveFloorOutOfRange=" + mIsAnchorHeightAboveFloorOutOfRange
                    + ", AnchorHeightAboveFloorRangeLower=" + mAnchorHeightAboveFloorRangeLower
                    + ", AnchorHeightAboveFloorRangeUpper=" + mAnchorHeightAboveFloorRangeUpper
                    + " }";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ZElementExtension)) return false;
            ZElementExtension other = (ZElementExtension) o;
            return Double.valueOf(mAnchorFloorNumber).equals(other.mAnchorFloorNumber)
                    && mExpectedToMove == other.mExpectedToMove
                    && Double.valueOf(mAnchorHeightAboveFloor)
                            .equals(other.mAnchorHeightAboveFloor)
                    && mAnchorHeightAboveFloorUncertainty ==
                            other.mAnchorHeightAboveFloorUncertainty
                    && mIsAnchorFloorNumberOutOfRange == other.mIsAnchorFloorNumberOutOfRange
                    && mIsAnchorHeightAboveFloorOutOfRange ==
                            other.mIsAnchorHeightAboveFloorOutOfRange
                    && Double.valueOf(mAnchorHeightAboveFloorRangeLower)
                            .equals(other.mAnchorHeightAboveFloorRangeLower)
                    && Double.valueOf(mAnchorHeightAboveFloorRangeUpper)
                            .equals(other.mAnchorHeightAboveFloorRangeUpper);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mAnchorFloorNumber, mExpectedToMove, mAnchorHeightAboveFloor,
                    mAnchorHeightAboveFloorUncertainty, mIsAnchorFloorNumberOutOfRange,
                    mIsAnchorHeightAboveFloorOutOfRange, mAnchorHeightAboveFloorRangeLower,
                    mAnchorHeightAboveFloorRangeUpper);
        }
    }

    /**
     * Class to represent the anchor location.
     *
     * <p>This class is used to represent the anchor location in the ranging message. The anchor
     * location can be either a WGS84 location, a relative location, or a combination of the two.
     *
     * <p>The anchor location can also have a Z element extension.
     */
    @FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
    public static final class AnchorLocation implements Parcelable {
        /** DT-Anchor location in WGS-84 coordinate system */
        public static final int COORDINATE_WGS84 = 0x00;
        /** DT-Anchor location in relative coordinate system */
        public static final int COORDINATE_RELATIVE = 0x01;
        /** DT-Anchor location in WGS-84 coordinate system plus Z-element */
        public static final int COORDINATE_WGS84_PLUS_Z_ELEMENT = 0x02;
        /** Relative coordinates plus Z-element */
        public static final int COORDINATE_RELATIVE_PLUS_Z_ELEMENT = 0x03;
        /** Relative coordinate system with Z-coordinate gravity aligned */
        public static final int COORDINATE_RELATIVE_WITH_Z_GRAVITY_ALIGNED = 0x04;
        /** Relative coordinate system with Z-coordinate gravity aligned plus Z-element */
        public static final int COORDINATE_RELATIVE_WITH_Z_GRAVITY_ALIGNED_PLUS_Z_ELEMENT = 0x05;
        /** Unknown coordinate type */
        public static final int COORDINATE_UNKNOWN = Integer.MAX_VALUE;
        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({
            COORDINATE_WGS84,
            COORDINATE_RELATIVE,
            COORDINATE_WGS84_PLUS_Z_ELEMENT,
            COORDINATE_RELATIVE_PLUS_Z_ELEMENT,
            COORDINATE_RELATIVE_WITH_Z_GRAVITY_ALIGNED,
            COORDINATE_RELATIVE_WITH_Z_GRAVITY_ALIGNED_PLUS_Z_ELEMENT,
            COORDINATE_UNKNOWN,
        })
        public @interface CoordinateType {}

        private final @CoordinateType int mCoordinateType;
        private final byte[] mRawBytes;
        private final @Nullable Wgs84Location mWgs84Location;
        private final @Nullable RelativeLocation mRelativeLocation;
        private final @Nullable ZElementExtension mZElementExtension;

        /** @hide */
        public AnchorLocation(
                @CoordinateType int coordinateType,
                @NonNull byte[] rawBytes,
                @Nullable Wgs84Location wgs84Location,
                @Nullable RelativeLocation relativeLocation,
                @Nullable ZElementExtension zElementExtension) {
            Objects.requireNonNull(rawBytes);

            mCoordinateType = coordinateType;
            mRawBytes = Arrays.copyOf(rawBytes, rawBytes.length);
            mWgs84Location = wgs84Location;
            mRelativeLocation = relativeLocation;
            mZElementExtension = zElementExtension;
        }

        @CoordinateType
        public int getCoordinateType() {
            return mCoordinateType;
        }

        @NonNull
        public byte[] getRawBytes() {
            return mRawBytes;
        }

        @Nullable
        public Wgs84Location getWgs84Location() {
            return mWgs84Location;
        }

        @Nullable
        public RelativeLocation getRelativeLocation() {
            return mRelativeLocation;
        }

        @Nullable
        public ZElementExtension getZElementExtension() {
            return mZElementExtension;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            Objects.requireNonNull(dest);

            dest.writeInt(mCoordinateType);
            dest.writeByteArray(mRawBytes);
            dest.writeParcelable(mWgs84Location, flags);
            dest.writeParcelable(mRelativeLocation, flags);
            dest.writeParcelable(mZElementExtension, flags);
        }

        public static final @NonNull Creator<AnchorLocation> CREATOR =
                new Creator<AnchorLocation>() {
                    @Override
                    public AnchorLocation createFromParcel(Parcel in) {
                        return new AnchorLocation(
                                in.readInt(),
                                in.createByteArray(),
                                in.readParcelable(Wgs84Location.class.getClassLoader()),
                                in.readParcelable(RelativeLocation.class.getClassLoader()),
                                in.readParcelable(ZElementExtension.class.getClassLoader()));
                    }

                    @Override
                    public AnchorLocation[] newArray(int size) {
                        return new AnchorLocation[size];
                    }
                };

        @Override
        public String toString() {
            return "AnchorLocation {"
                    + "CoordinateType=" + mCoordinateType
                    + ", RawBytes=" + Arrays.toString(mRawBytes)
                    + ", Wgs84Location=" + mWgs84Location
                    + ", RelativeLocation=" + mRelativeLocation
                    + ", ZElementExtension=" + mZElementExtension
                    + " }";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AnchorLocation)) return false;
            AnchorLocation other = (AnchorLocation) o;
            return mCoordinateType == other.mCoordinateType
                    && Arrays.equals(mRawBytes, other.mRawBytes)
                    && Objects.equals(mWgs84Location, other.mWgs84Location)
                    && Objects.equals(mRelativeLocation, other.mRelativeLocation)
                    && Objects.equals(mZElementExtension, other.mZElementExtension);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mCoordinateType, Arrays.hashCode(mRawBytes), mWgs84Location,
                    mRelativeLocation, mZElementExtension);
        }
    }

    /** An ad-hoc value for representing the absence of a supercluster ID. */
    private static final int SUPERCLUSTER_ID_ABSENT = Integer.MAX_VALUE;
    private static final int SUPERCLUSTER_ID_MAX_VALUE = 0xFF;
    private static final int SUPERCLUSTER_ID_MIN_VALUE = 0x00;

    // The extra indication for distinguishing between different measurement versions.
    private final @MeasurementVersion int mMeasurementVersion;

    // Original fields of DL-TDoA Ranging Measurement Result v1
    // private final UwbAddress mMacAddress;  // // this field is accessible from RangingDevice
    // private final int mStatus;  // only measurements with STATUS_OK are generated and callbacked
    private final @MessageType int mMessageType;
    private final int mMessageControl;
    private final int mBlockIndex;
    private final int mRoundIndex;
    private final @LineOfSight int mNlos;
    private final float mAoaAzimuth;
    private final int mAoaAzimuthFom;
    private final float mAoaElevation;
    private final int mAoaElevationFom;
    private final int mRssi;
    private final byte[] mTxTimestamp;
    private final byte[] mRxTimestamp;
    private final float mAnchorCfo;
    private final float mCfo;
    private final long mInitiatorReplyTime;
    private final long mResponderReplyTime;
    private final int mInitiatorResponderTof;
    private final AnchorLocation mAnchorLocation;
    private final byte[] mActiveRangingRoundIndexes;

    // Additional fields of DL-TDoA Ranging Measurement Result v2
    private final int mSuperclusterId;

    private DlTdoaMeasurement(Builder builder) {
        mMeasurementVersion = builder.mMeasurementVersion;
        mMessageType = builder.mMessageType;
        mMessageControl = builder.mMessageControl;
        mBlockIndex = builder.mBlockIndex;
        mRoundIndex = builder.mRoundIndex;
        mNlos = builder.mNlos;
        mAoaAzimuth = builder.mAoaAzimuth;
        mAoaAzimuthFom = builder.mAoaAzimuthFom;
        mAoaElevation = builder.mAoaElevation;
        mAoaElevationFom = builder.mAoaElevationFom;
        mRssi = builder.mRssi;
        mTxTimestamp = builder.mTxTimestamp;
        mRxTimestamp = builder.mRxTimestamp;
        mAnchorCfo = builder.mAnchorCfo;
        mCfo = builder.mCfo;
        mInitiatorReplyTime = builder.mInitiatorReplyTime;
        mResponderReplyTime = builder.mResponderReplyTime;
        mInitiatorResponderTof = builder.mInitiatorResponderTof;
        mAnchorLocation = builder.mAnchorLocation;
        mActiveRangingRoundIndexes = builder.mActiveRangingRoundIndexes;
        mSuperclusterId = builder.mSuperclusterId;
    }

    /**
     * Returns the measurement version.
     *
     * @return The measurement version,e.g., {@link DlTdoaRangingParams#MEASUREMENT_VERSION_1}.
     */
    @MeasurementVersion
    public int getMeasurementVersion() {
        return mMeasurementVersion;
    }

    /**
     * Returns the message type of the received DL-TDoA Message (DTM).
     *
     * @return The message type, e.g., {@link #MESSAGE_TYPE_POLL_DTM}.
     */
    @MessageType
    public int getMessageType() {
        return mMessageType;
    }

    /**
     * Returns the raw MessageControl field.
     *
     * <p><b>Note:</b> This is an advanced-use field. The value is a bitmask constructed according
     * to the layout described in the FiRa 4.0 UCI specification mentioned in the
     * {@link DlTdoaMeasurement} class documentation. The representation is version-specific and
     * depends on the measurement version (see Table 31 for v1, and Table 32 for v2).
     *
     * @return The integer bitmask representing message control flags.
     */
    @IntRange(from = 0, to = 0x07FF)
    public int getMessageControl() {
        return mMessageControl;
    }

    /**
     * Returns true if TX timestamp is based on a common time base.
     *
     * <p>This value is derived from bit 0 of the {@code MessageControl} field. If TX timestamp is
     * not based on a common time base, it is based on the local time base and cannot be used for
     * localization.
     *
     * @return true if the TX timestamp is in the common time base, false otherwise.
     */
    public boolean isTxTimestampInCommonTimeBase() {
        // Both v1 and v2 use bit 0 to indicate whether the TX timestamp is in the common time base.
        return (mMessageControl & 0x1) != 0;
    }

    /**
     * Returns the block index of the current ranging block.
     *
     * <p>This identifies the specific ranging block during which the measurement occurred.
     *
     * @return The block index as an integer.
     */
    @IntRange(from = 0, to = 0xFFFF)
    public int getBlockIndex() {
        return mBlockIndex;
    }

    /**
     * Returns the round index of the current ranging round within the current ranging block.
     *
     * <p>This specifies the active ranging round.
     *
     * @return The round index as an integer.
     */
    @IntRange(from = 0, to = 0xFF)
    public int getRoundIndex() {
        return mRoundIndex;
    }

    /**
     * Returns an indicator of the Line of Sight (LoS) status.
     *
     * @return The NLoS status as {@link UwbSpecificData#LineOfSight}.
     */
    @LineOfSight
    public int getNlos() {
        return mNlos;
    }

    /**
     * Returns the Angle of Arrival (AoA) azimuth in degrees.
     *
     * <p>This value ranges from -180 to 180 degrees. If the value is not available, it returns
     * {@code Float.NaN}.
     *
     * @return The AoA azimuth value in degrees, or {@code Float.NaN} if not available.
     */
    @FloatRange(from = -180.0, to = 180.0)
    public float getAoaAzimuth() {
        return mAoaAzimuth;
    }

    /**
     * Returns the Figure of Merit (FoM) of the AoA azimuth.
     *
     * <p>This value ranges from 0 to 100. If the value is not available, it returns 0.
     *
     * @return The AoA azimuth FoM value in percentage, or 0 if not available.
     */
    @IntRange(from = 0, to = 100)
    public int getAoaAzimuthFom() {
        return mAoaAzimuthFom;
    }

    /**
     * Returns the Angle of Arrival (AoA) elevation in degrees.
     *
     * <p>This value ranges from -90 to 90 degrees. If the value is not available, it returns
     * {@code Float.NaN}.
     *
     * @return The AoA elevation value in degrees, or {@code Float.NaN} if not available.
     */
    @FloatRange(from = -90.0, to = 90.0)
    public float getAoaElevation() {
        return mAoaElevation;
    }

    /**
     * Returns the Figure of Merit (FoM) of the AoA elevation.
     *
     * <p>This value ranges from 0 to 100. If the value is not available, it returns 0.
     *
     * @return The AoA elevation FoM value in percentage, or 0 if not available.
     */
    @IntRange(from = 0, to = 100)
    public int getAoaElevationFom() {
        return mAoaElevationFom;
    }

    /**
     * Returns the Received Signal Strength Indicator (RSSI) in dBm.
     *
     * <p>This value ranges from -127dBm to -1dBm. If the value is not available, it returns 0.
     *
     * @return The RSSI value in negative dBm, or 0 if not available.
     */
    @IntRange(from = -127, to = 0)
    public int getRssi() {
        return mRssi;
    }

    /**
     * Returns the transmission timestamp of the message reported by the DT-Anchor.
     *
     * <p>This timestamp is in Ranging Ticks (~15.65 ps).
     *
     * @return The TX timestamp integer value as a byte array in little endian byte order.
     */
    @NonNull
    public byte[] getTxTimestamp() {
        return mTxTimestamp;
    }

    /**
     * Returns the local reception timestamp of the message by the DT-Tag (Android device).
     *
     * <p> This timestamp is measured locally in Ranging Ticks (~15.65 ps).
     *
     * @return The RX timestamp integer value as a byte array in little endian byte order.
     */
    @NonNull
    public byte[] getRxTimestamp() {
        return mRxTimestamp;
    }

    /**
     * Returns the Clock Frequency Offset (CFO) of a Responder DT-Anchor.
     *
     * <p>This offset is relative to the Initiator DT-Anchor of the ranging round, in ppm units.
     *
     * @return The Anchor CFO as a float, or {@code Float.NaN} if not available.
     */
    @FloatRange(from = -32.0, to = 32.0, fromInclusive = false, toInclusive = false)
    public float getAnchorCfo() {
        return mAnchorCfo;
    }

    /**
     * Returns the locally measured Clock Frequency Offset (CFO) by the DT-Tag.
     *
     * <p>This offset is relative to the DT-Anchor that sent the message, in ppm units.
     *
     * @return The CFO as a float, or {@code Float.NaN} if not supported.
     */
    @FloatRange(from = -32.0, to = 32.0, fromInclusive = false, toInclusive = false)
    public float getCfo() {
        return mCfo;
    }

    /**
     * Returns the time difference measured by the Initiator DT-Anchor.
     *
     * <p>It's between the RX timestamp of a Responder's DTM and the TX timestamp of the Final DTM,
     * in Ranging Ticks (~15.65 ps). If the ranging method SS-TWR is used between DT-Anchors, then
     * there is no Final DTM at all and this value is 0.
     *
     * @return The Initiator Reply Time as a long.
     */
    @IntRange(from = 0, to = 0xFFFFFFFFL)
    public long getInitiatorReplyTime() {
        return mInitiatorReplyTime;
    }

    /**
     * Returns the time difference measured at the Responder DT-Anchor.
     *
     * <p>It's between the RX timestamp of the Poll DTM and the TX timestamp of its Response DTM,
     * in Ranging Ticks (~15.65 ps). For Poll DTM and Final DTM, this value is 0.
     *
     * @return The Responder Reply Time as a long.
     */
    @IntRange(from = 0, to = 0xFFFFFFFFL)
    public long getResponderReplyTime() {
        return mResponderReplyTime;
    }

    /**
     * Returns the Time of Flight (ToF) between the Responder and Initiator DT-Anchors.
     *
     * <p>This is measured by the Responder DT-Anchor, in Ranging Ticks (~15.65 ps). This field is
     * only valid for the Response DTM. For Poll DTM and Final DTM, this value is 0.
     *
     * @return The Initiator-Responder ToF as an integer.
     */
    @IntRange(from = 0, to = 0xFFFF)
    public int getInitiatorResponderTof() {
        return mInitiatorResponderTof;
    }

    /**
     * Returns the anchor location, if available.
     *
     * @return The anchor location, or {@code null} if not available.
     */
    @Nullable
    public AnchorLocation getAnchorLocation() {
        return mAnchorLocation;
    }

    /**
     * Returns a list of active ranging round indexes.
     *
     * <p>These are the rounds in which the DT-Anchor associated with this measurement result is
     * present.
     *
     * @return The list of active ranging round indexes. An empty list is returned if active ranging
     * round indexes are not present.
     */
    @NonNull
    public List<Integer> getActiveRangingRoundIndexes() {
        if (mActiveRangingRoundIndexes == null) {
            return new ArrayList<>();
        }
        List<Integer> activeRangingRoundIndexes = new ArrayList<>();
        for (byte index : mActiveRangingRoundIndexes) {
            activeRangingRoundIndexes.add((int) index);
        }
        return activeRangingRoundIndexes;
    }

    private static boolean isValidSuperclusterId(int superclusterId) {
        return superclusterId >= SUPERCLUSTER_ID_MIN_VALUE
                && superclusterId <= SUPERCLUSTER_ID_MAX_VALUE;
    }

    /**
     * Returns true if Supercluster ID is present.
     *
     * <p>If this value is available, Supercluster common timebase is valid for Initiator
     * DT-Anchors using the same Supercluster ID.
     *
     * @return true if Supercluster ID is present, false otherwise.
     */
    public boolean hasSuperclusterId() {
        return isValidSuperclusterId(mSuperclusterId);
    }

    /**
     * Returns the Supercluster ID, if available.
     *
     * <p>If this value is available, Supercluster common timebase is valid for Initiator
     * DT-Anchors using the same Supercluster ID.
     *
     * @return The Supercluster ID, or indeterminate value if not available. Check {@link
     * #hasSuperclusterId()} before using the return value of this method.
     */
    @IntRange(from = SUPERCLUSTER_ID_MIN_VALUE, to = SUPERCLUSTER_ID_MAX_VALUE)
    public int getSuperclusterId() {
        return mSuperclusterId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        Objects.requireNonNull(dest);

        dest.writeInt(mMeasurementVersion);
        dest.writeInt(mMessageType);
        dest.writeInt(mMessageControl);
        dest.writeInt(mBlockIndex);
        dest.writeInt(mRoundIndex);
        dest.writeInt(mNlos);
        dest.writeFloat(mAoaAzimuth);
        dest.writeInt(mAoaAzimuthFom);
        dest.writeFloat(mAoaElevation);
        dest.writeInt(mAoaElevationFom);
        dest.writeInt(mRssi);
        dest.writeByteArray(mTxTimestamp);
        dest.writeByteArray(mRxTimestamp);
        dest.writeFloat(mAnchorCfo);
        dest.writeFloat(mCfo);
        dest.writeLong(mInitiatorReplyTime);
        dest.writeLong(mResponderReplyTime);
        dest.writeInt(mInitiatorResponderTof);
        dest.writeParcelable(mAnchorLocation, flags);
        dest.writeByteArray(mActiveRangingRoundIndexes);
        dest.writeInt(mSuperclusterId);
    }

    private DlTdoaMeasurement(Parcel in) {
        mMeasurementVersion = in.readInt();
        mMessageType = in.readInt();
        mMessageControl = in.readInt();
        mBlockIndex = in.readInt();
        mRoundIndex = in.readInt();
        mNlos = in.readInt();
        mAoaAzimuth = in.readFloat();
        mAoaAzimuthFom = in.readInt();
        mAoaElevation = in.readFloat();
        mAoaElevationFom = in.readInt();
        mRssi = in.readInt();
        mTxTimestamp = in.createByteArray();
        mRxTimestamp = in.createByteArray();
        mAnchorCfo = in.readFloat();
        mCfo = in.readFloat();
        mInitiatorReplyTime = in.readLong();
        mResponderReplyTime = in.readLong();
        mInitiatorResponderTof = in.readInt();
        mAnchorLocation = in.readParcelable(AnchorLocation.class.getClassLoader());
        mActiveRangingRoundIndexes = in.createByteArray();
        mSuperclusterId = in.readInt();
    }

    public static final @NonNull Creator<DlTdoaMeasurement> CREATOR =
            new Creator<DlTdoaMeasurement>() {
                @Override
                public DlTdoaMeasurement createFromParcel(Parcel in) {
                    return new DlTdoaMeasurement(in);
                }

                @Override
                public DlTdoaMeasurement[] newArray(int size) {
                    return new DlTdoaMeasurement[size];
                }
            };

    @Override
    public String toString() {
        return "DlTdoaMeasurement {"
                + " MeasurementVersion=" + mMeasurementVersion
                + ", MessageType=" + mMessageType
                + ", MessageControl=" + mMessageControl
                + ", BlockIndex=" + mBlockIndex
                + ", RoundIndex=" + mRoundIndex
                + ", NLoS=" + mNlos
                + ", AoaAzimuth=" + mAoaAzimuth
                + ", AoaAzimuthFom=" + mAoaAzimuthFom
                + ", AoaElevation=" + mAoaElevation
                + ", AoaElevationFom=" + mAoaElevationFom
                + ", Rssi=" + mRssi
                + ", TxTimestamp=" + Arrays.toString(mTxTimestamp)
                + ", RxTimestamp=" + Arrays.toString(mRxTimestamp)
                + ", AnchorCfo=" + mAnchorCfo
                + ", Cfo=" + mCfo
                + ", InitiatorReplyTime=" + mInitiatorReplyTime
                + ", ResponderReplyTime=" + mResponderReplyTime
                + ", InitiatorResponderTof=" + mInitiatorResponderTof
                + ", AnchorLocation=" + mAnchorLocation
                + ", ActiveRangingRoundIndexes=" + Arrays.toString(mActiveRangingRoundIndexes)
                + ", SuperclusterId=" + mSuperclusterId
                + " }";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DlTdoaMeasurement)) return false;
        DlTdoaMeasurement other = (DlTdoaMeasurement) o;
        return mMeasurementVersion == other.mMeasurementVersion
                && mMessageType == other.mMessageType
                && mMessageControl == other.mMessageControl
                && mBlockIndex == other.mBlockIndex
                && mRoundIndex == other.mRoundIndex
                && mNlos == other.mNlos
                && Float.valueOf(mAoaAzimuth).equals(other.mAoaAzimuth)
                && mAoaAzimuthFom == other.mAoaAzimuthFom
                && Float.valueOf(mAoaElevation).equals(other.mAoaElevation)
                && mAoaElevationFom == other.mAoaElevationFom
                && mRssi == other.mRssi
                && Arrays.equals(mTxTimestamp, other.mTxTimestamp)
                && Arrays.equals(mRxTimestamp, other.mRxTimestamp)
                && Float.valueOf(mAnchorCfo).equals(other.mAnchorCfo)
                && Float.valueOf(mCfo).equals(other.mCfo)
                && mInitiatorReplyTime == other.mInitiatorReplyTime
                && mResponderReplyTime == other.mResponderReplyTime
                && mInitiatorResponderTof == other.mInitiatorResponderTof
                && Objects.equals(mAnchorLocation, other.mAnchorLocation)
                && Arrays.equals(mActiveRangingRoundIndexes, other.mActiveRangingRoundIndexes)
                && mSuperclusterId == other.mSuperclusterId;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mMeasurementVersion, mMessageType, mMessageControl, mBlockIndex,
                mRoundIndex, mNlos, mAoaAzimuth, mAoaAzimuthFom, mAoaElevation, mAoaElevationFom,
                mRssi, Arrays.hashCode(mTxTimestamp), Arrays.hashCode(mRxTimestamp),
                mAnchorCfo, mCfo, mInitiatorReplyTime, mResponderReplyTime, mInitiatorResponderTof,
                mAnchorLocation, Arrays.hashCode(mActiveRangingRoundIndexes), mSuperclusterId);
        return result;
    }

    /** @hide */
    public static final class Builder {
        // The extra indication for distinguishing between different measurement versions.
        private final @MeasurementVersion int mMeasurementVersion;

        // Original fields of DL-TDoA Ranging Measurement Result v1
        // private final UwbAddress mMacAddress;
        // private final int mStatus;
        private final @MessageType int mMessageType;
        private final int mMessageControl;
        private final int mBlockIndex;
        private final int mRoundIndex;
        private final @LineOfSight int mNlos;
        private float mAoaAzimuth = Float.NaN;
        private int mAoaAzimuthFom;
        private float mAoaElevation = Float.NaN;
        private int mAoaElevationFom;
        private int mRssi = 0;
        private final byte[] mTxTimestamp;
        private final byte[] mRxTimestamp;
        private final float mAnchorCfo;
        private final float mCfo;
        private final long mInitiatorReplyTime;
        private final long mResponderReplyTime;
        private final int mInitiatorResponderTof;
        private AnchorLocation mAnchorLocation = null;
        private byte[] mActiveRangingRoundIndexes = new byte[0];

        // Additional fields of DL-TDoA Ranging Measurement Result v2
        private int mSuperclusterId = SUPERCLUSTER_ID_ABSENT;

        public Builder(int measurementVersion, int messageType, int messageControl, int blockIndex,
                int roundIndex, int nlos, @NonNull byte[] txTimestamp,
                @NonNull byte[] rxTimestamp, float anchorCfo, float cfo,
                long initiatorReplyTime, long responderReplyTime, int initiatorResponderTof) {
            Objects.requireNonNull(txTimestamp);
            Objects.requireNonNull(rxTimestamp);

            mMeasurementVersion = measurementVersion;
            mMessageType = messageType;
            mMessageControl = messageControl;
            mBlockIndex = blockIndex;
            mRoundIndex = roundIndex;
            mNlos = nlos;
            mTxTimestamp = txTimestamp;
            mRxTimestamp = rxTimestamp;
            mAnchorCfo = anchorCfo;
            mCfo = cfo;
            mInitiatorReplyTime = initiatorReplyTime;
            mResponderReplyTime = responderReplyTime;
            mInitiatorResponderTof = initiatorResponderTof;
        }

        @NonNull
        public Builder setAoaAzimuth(float aoaAzimuth) {
            mAoaAzimuth = aoaAzimuth;
            return this;
        }

        @NonNull
        public Builder setAoaAzimuthFom(int aoaAzimuthFom) {
            mAoaAzimuthFom = aoaAzimuthFom;
            return this;
        }

        @NonNull
        public Builder setAoaElevation(float aoaElevation) {
            mAoaElevation = aoaElevation;
            return this;
        }

        @NonNull
        public Builder setAoaElevationFom(int aoaElevationFom) {
            mAoaElevationFom = aoaElevationFom;
            return this;
        }

        @NonNull
        public Builder setRssi(@IntRange(from = -127, to = -1) int rssi) {
            mRssi = rssi;
            return this;
        }

        @NonNull
        public Builder setAnchorLocation(@NonNull AnchorLocation anchorLocation) {
            Objects.requireNonNull(anchorLocation);

            mAnchorLocation = anchorLocation;
            return this;
        }

        @NonNull
        public Builder setActiveRangingRoundIndexes(@NonNull byte[] activeRangingRoundIndexes) {
            Objects.requireNonNull(activeRangingRoundIndexes);

            mActiveRangingRoundIndexes = activeRangingRoundIndexes;
            return this;
        }

        @NonNull
        public Builder setSuperclusterId(int superclusterId) {
            // b3: TX timestamp common time base type and Supercluster ID field presence obtained
            // from payload of the received DTM.
            boolean isSuperclusterIdSupported =
                    (mMeasurementVersion == DlTdoaRangingParams.MEASUREMENT_VERSION_2)
                            && ((mMessageControl & 0b0100) != 0);

            if (isSuperclusterIdSupported && isValidSuperclusterId(superclusterId)) {
                mSuperclusterId = superclusterId;
            }
            return this;
        }

        @NonNull
        public DlTdoaMeasurement build() {
            return new DlTdoaMeasurement(this);
        }
    }
}