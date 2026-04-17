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

package android.bluetooth.le;

import android.annotation.Hide;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresNoPermission;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/**
 * The {@link ChannelSoundingParams} provide a way to adjust distance measurement preferences for
 * {@link DISTANCE_MEASUREMENT_METHOD_CHANNEL_SOUNDING}. Use {@link ChannelSoundingParams.Builder}
 * to create an instance of this class.
 */
@Hide
@SystemApi
public final class ChannelSoundingParams implements Parcelable {

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            value = {
                SIGHT_TYPE_UNKNOWN,
                SIGHT_TYPE_LINE_OF_SIGHT,
                SIGHT_TYPE_NON_LINE_OF_SIGHT,
            })
    @interface SightType {}

    @Hide
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {LOCATION_TYPE_UNKNOWN, LOCATION_TYPE_INDOOR, LOCATION_TYPE_OUTDOOR})
    @interface LocationType {}

    @Hide
    @Target(ElementType.TYPE_USE)
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            value = {
                CS_SECURITY_LEVEL_UNKNOWN,
                CS_SECURITY_LEVEL_ONE,
                CS_SECURITY_LEVEL_TWO,
                CS_SECURITY_LEVEL_THREE,
                CS_SECURITY_LEVEL_FOUR
            })
    @interface CsSecurityLevel {}

    /** Sight type is unknown. */
    @Hide @SystemApi public static final int SIGHT_TYPE_UNKNOWN = 0;

    /** Remote device is in line of sight. */
    @Hide @SystemApi public static final int SIGHT_TYPE_LINE_OF_SIGHT = 1;

    /** Remote device is not in line of sight. */
    @Hide @SystemApi public static final int SIGHT_TYPE_NON_LINE_OF_SIGHT = 2;

    /** Location type is unknown. */
    @Hide @SystemApi public static final int LOCATION_TYPE_UNKNOWN = 0;

    /** The location of the usecase is indoor. */
    @Hide @SystemApi public static final int LOCATION_TYPE_INDOOR = 1;

    /** The location of the usecase is outdoor. */
    @Hide @SystemApi public static final int LOCATION_TYPE_OUTDOOR = 2;

    /**
     * Return value for {@link
     * DistanceMeasurementManager#getChannelSoundingMaxSupportedSecurityLevel(BluetoothDevice)} and
     * {@link DistanceMeasurementManager#getLocalChannelSoundingMaxSupportedSecurityLevel()} when
     * Channel Sounding is not supported, or encounters an internal error.
     */
    @Hide @SystemApi public static final int CS_SECURITY_LEVEL_UNKNOWN = 0;

    /** Either CS tone or CS RTT. */
    @Hide @SystemApi public static final int CS_SECURITY_LEVEL_ONE = 1;

    /** 150 ns CS RTT accuracy and CS tones. */
    @Hide @SystemApi public static final int CS_SECURITY_LEVEL_TWO = 2;

    /** 10 ns CS RTT accuracy and CS tones. */
    @Hide @SystemApi public static final int CS_SECURITY_LEVEL_THREE = 3;

    /**
     * Level 3 with the addition of CS RTT sounding sequence or random sequence payloads, and
     * support of the Normalized Attack Detector Metric requirements.
     */
    @Hide @SystemApi public static final int CS_SECURITY_LEVEL_FOUR = 4;

    private final int mSightType;
    private final int mLocationType;
    private final int mCsSecurityLevel;

    @Hide
    public ChannelSoundingParams(int sightType, int locationType, int csSecurityLevel) {
        mSightType = sightType;
        mLocationType = locationType;
        mCsSecurityLevel = csSecurityLevel;
    }

    /** Returns sight type of this ChannelSoundingParams. */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public @SightType int getSightType() {
        return mSightType;
    }

    /** Returns location type of this ChannelSoundingParams. */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public @LocationType int getLocationType() {
        return mLocationType;
    }

    /** Returns CS security level of this ChannelSoundingParams. */
    @Hide
    @SystemApi
    @RequiresNoPermission
    public @CsSecurityLevel int getCsSecurityLevel() {
        return mCsSecurityLevel;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mSightType);
        out.writeInt(mLocationType);
        out.writeInt(mCsSecurityLevel);
    }

    /** A {@link Parcelable.Creator} to create {@link ChannelSoundingParams} from parcel. */
    public static final @NonNull Parcelable.Creator<ChannelSoundingParams> CREATOR =
            new Parcelable.Creator<ChannelSoundingParams>() {
                @Override
                public @NonNull ChannelSoundingParams createFromParcel(@NonNull Parcel in) {
                    Builder builder = new Builder();
                    builder.setSightType(in.readInt());
                    builder.setLocationType(in.readInt());
                    builder.setCsSecurityLevel(in.readInt());
                    return builder.build();
                }

                @Override
                public @NonNull ChannelSoundingParams[] newArray(int size) {
                    return new ChannelSoundingParams[size];
                }
            };

    /** Builder for {@link ChannelSoundingParams}. */
    @Hide
    @SystemApi
    public static final class Builder {
        private int mSightType = SIGHT_TYPE_UNKNOWN;
        private int mLocationType = LOCATION_TYPE_UNKNOWN;
        private int mCsSecurityLevel = CS_SECURITY_LEVEL_ONE;

        /**
         * Set sight type for the ChannelSoundingParams.
         *
         * @param sightType sight type of this ChannelSoundingParams
         * @return the same Builder instance
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @NonNull Builder setSightType(@SightType int sightType) {
            if (!List.of(SIGHT_TYPE_UNKNOWN, SIGHT_TYPE_LINE_OF_SIGHT, SIGHT_TYPE_NON_LINE_OF_SIGHT)
                    .contains(sightType)) {
                throw new IllegalArgumentException("unknown sight type " + sightType);
            }
            mSightType = sightType;
            return this;
        }

        /**
         * Set location type for the ChannelSoundingParams.
         *
         * @param locationType location type of this ChannelSoundingParams
         * @return the same Builder instance
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @NonNull Builder setLocationType(@LocationType int locationType) {
            if (!List.of(LOCATION_TYPE_UNKNOWN, LOCATION_TYPE_INDOOR, LOCATION_TYPE_OUTDOOR)
                    .contains(locationType)) {
                throw new IllegalArgumentException("unknown location type " + locationType);
            }
            mLocationType = locationType;
            return this;
        }

        /**
         * Set CS security level for the ChannelSoundingParams.
         *
         * <p>See: https://bluetooth.com/specifications/specs/channel-sounding-cr-pr/
         *
         * @param csSecurityLevel cs security level of this ChannelSoundingParams
         * @return the same Builder instance
         */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @NonNull Builder setCsSecurityLevel(@CsSecurityLevel int csSecurityLevel) {
            if (!List.of(
                            CS_SECURITY_LEVEL_ONE,
                            CS_SECURITY_LEVEL_TWO,
                            CS_SECURITY_LEVEL_THREE,
                            CS_SECURITY_LEVEL_FOUR)
                    .contains(csSecurityLevel)) {
                throw new IllegalArgumentException("unknown CS security level " + csSecurityLevel);
            }
            mCsSecurityLevel = csSecurityLevel;
            return this;
        }

        /** Build the {@link ChannelSoundingParams} object. */
        @Hide
        @SystemApi
        @RequiresNoPermission
        public @NonNull ChannelSoundingParams build() {
            return new ChannelSoundingParams(mSightType, mLocationType, mCsSecurityLevel);
        }
    }
}
