/**
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

package android.hardware.lights;

import android.annotation.DurationMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.server.lights.feature.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents a logical light on the device.
 *
 */
public final class Light implements Parcelable {
    // These enum values copy the values from {@link com.android.server.lights.LightsManager}
    // and the light HAL. Since 0-7 are lights reserved for system use, 8 for microphone light is
    // defined in {@link android.hardware.lights.LightsManager}, following types are available
    // through this API.
    /** Type for lights that indicate microphone usage */
    public static final int LIGHT_TYPE_MICROPHONE = 8;

    /** Type for lights that indicate camera usage
     *
     * @hide
     */
    public static final int LIGHT_TYPE_CAMERA = 9;

    /**
     * Type for lights that indicate application driven use cases.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_LIGHT_ANIMATIONS)
    public static final int LIGHT_TYPE_APPLICATION = 10;

    // These enum values start from 10001 to avoid collision with expanding of HAL light types.
    /**
     * Type for lights that indicate a monochrome color LED light.
     */
    public static final int LIGHT_TYPE_INPUT = 10001;

    /**
     * Type for lights that indicate a group of LED lights representing player id.
     * Player id lights normally present on game controllers are lights that consist of a row of
     * LEDs.
     * During multi-player game, the player id for the current game controller is represented by
     * one of the LED that is lit according to its position in the row.
     */
    public static final int LIGHT_TYPE_PLAYER_ID = 10002;

    /**
     * Type for lights that illuminate keyboard keys.
     */
    public static final int LIGHT_TYPE_KEYBOARD_BACKLIGHT = 10003;

    /**
     * Type for keyboard microphone mute light.
     * @hide
     */
    public static final int LIGHT_TYPE_KEYBOARD_MIC_MUTE = 10004;

    /**
     * Type for keyboard volume mute light.
     * @hide
     */
    public static final int LIGHT_TYPE_KEYBOARD_VOLUME_MUTE = 10005;

    /**
     * Capability for lights that could adjust its LED brightness. If the capability is not present
     * the LED can only be turned either on or off.
     */
    public static final int LIGHT_CAPABILITY_BRIGHTNESS = 1 << 0;

    /**
     * Capability for lights that have red, green and blue LEDs to control the light's color.
     */
    public static final int LIGHT_CAPABILITY_COLOR_RGB = 1 << 1;

    /**
     * Capability for lights that support animations and fast transitions.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_LIGHT_ANIMATIONS)
    public static final int LIGHT_CAPABILITY_ANIMATION = 1 << 2;

      /**
     * Capability for lights that have red, green and blue LEDs to control the light's color.
     *
     * @deprecated Wrong int based flag with value 0. Use capability flag {@code
     * LIGHT_CAPABILITY_COLOR_RGB} instead.
     */
    @Deprecated
    public static final int LIGHT_CAPABILITY_RGB = 0;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"LIGHT_TYPE_"},
        value = {
            LIGHT_TYPE_MICROPHONE,
            LIGHT_TYPE_INPUT,
            LIGHT_TYPE_PLAYER_ID,
            LIGHT_TYPE_KEYBOARD_BACKLIGHT,
            LIGHT_TYPE_KEYBOARD_MIC_MUTE,
            LIGHT_TYPE_KEYBOARD_VOLUME_MUTE,
            LIGHT_TYPE_APPLICATION,
        })
    public @interface LightType {}

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = {"LIGHT_CAPABILITY_"},
        value = {
            LIGHT_CAPABILITY_BRIGHTNESS,
            LIGHT_CAPABILITY_COLOR_RGB,
            LIGHT_CAPABILITY_RGB,
            LIGHT_CAPABILITY_ANIMATION,
        })
    public @interface LightCapability {}

    private final int mId;
    private final String mName;
    private final int mOrdinal;
    private final int mType;
    private final int mCapabilities;
    @Nullable
    private final int[] mPreferredBrightnessLevels;
    private final long mMinUpdatePeriodMillis;

    /**
     * Creates a new light with the given data.
     *
     * @hide
     */
    public Light(int id, String name, int ordinal, int type, int capabilities,
            @Nullable int[] preferredBrightnessLevels) {
        this(id, name, ordinal, type, capabilities, preferredBrightnessLevels, 0);
    }

    private Light(@NonNull Parcel in) {
        mId = in.readInt();
        mName = in.readString();
        mOrdinal = in.readInt();
        mType = in.readInt();
        mCapabilities = in.readInt();
        mPreferredBrightnessLevels = in.createIntArray();
        if (Flags.enableLightAnimations()) {
            mMinUpdatePeriodMillis = in.readLong();
        } else {
            mMinUpdatePeriodMillis = 0;
        }
    }

    /**
     * Creates a new light with the given data.
     *
     * @hide
     */
    public Light(int id, String name, int ordinal, int type, int capabilities,
            @Nullable int[] preferredBrightnessLevels, long minUpdatePeriodMillis) {
        mId = id;
        mName = name;
        mOrdinal = ordinal;
        mType = type;
        mCapabilities = capabilities;
        mPreferredBrightnessLevels = preferredBrightnessLevels;
        if (Flags.enableLightAnimations()) {
            mMinUpdatePeriodMillis = minUpdatePeriodMillis;
        } else {
            mMinUpdatePeriodMillis = 0;
        }
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeString(mName);
        dest.writeInt(mOrdinal);
        dest.writeInt(mType);
        dest.writeInt(mCapabilities);
        dest.writeIntArray(mPreferredBrightnessLevels);
        if (Flags.enableLightAnimations()) {
            dest.writeLong(mMinUpdatePeriodMillis);
        }
    }

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    public static final @android.annotation.NonNull Parcelable.Creator<Light> CREATOR =
            new Parcelable.Creator<Light>() {
                public Light createFromParcel(Parcel in) {
                    return new Light(in);
                }

                public Light[] newArray(int size) {
                    return new Light[size];
                }
            };

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof Light) {
            Light light = (Light) obj;
            return mId == light.mId && mOrdinal == light.mOrdinal && mType == light.mType
                    && mCapabilities == light.mCapabilities;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return mId;
    }

    @Override
    public String toString() {
        if (Flags.enableLightAnimations()) {
            return "[Name=" + mName
                    + " Id=" + mId
                    + " Type=" + mType
                    + " Capabilities=" + mCapabilities
                    + " Ordinal=" + mOrdinal
                    + " MinUpdatePeriod=" + mMinUpdatePeriodMillis + "ms"
                    + "]";
        } else {
            return "[Name=" + mName + " Id=" + mId + " Type=" + mType + " Capabilities="
                    + mCapabilities + " Ordinal=" + mOrdinal + "]";
        }
    }

    /**
     * Returns the id of the light.
     *
     * <p>This is an opaque value used as a unique identifier for the light.
     */
    public int getId() {
        return mId;
    }

    /**
     * Returns the name of the light.
     */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Returns the ordinal of the light.
     *
     * <p>This is a sort key that represents the physical order of lights on the device with the
     * same type. In the case of multiple lights arranged in a line, for example, the ordinals
     * could be [1, 2, 3, 4], or [0, 10, 20, 30], or any other values that have the same sort order.
     */
    public int getOrdinal() {
        return mOrdinal;
    }

    /**
     * Returns the logical type of the light.
     */
    public @LightType int getType() {
        return mType;
    }

    /**
     * Returns the capabilities of the light.
     * @hide
     */
    @TestApi
    public @LightCapability int getCapabilities() {
        return mCapabilities;
    }

    /**
     * Check whether the light has led brightness control.
     *
     * @return True if the hardware can control the led brightness, otherwise false.
     */
    public boolean hasBrightnessControl() {
        return (mCapabilities & LIGHT_CAPABILITY_BRIGHTNESS) == LIGHT_CAPABILITY_BRIGHTNESS;
    }

    /**
     * Check whether the light has RGB led control.
     *
     * @return True if the hardware can control the RGB led, otherwise false.
     */
    public boolean hasRgbControl() {
        return (mCapabilities & LIGHT_CAPABILITY_COLOR_RGB) == LIGHT_CAPABILITY_COLOR_RGB;
    }

    /**
     * Check whether the light has animation capabilities.
     *
     * @see MultiLightEffect for details on how to create an animation/effect.
     * @return True if the hardware supports animations, otherwise false.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_LIGHT_ANIMATIONS)
    public boolean hasAnimationControl() {
        return (mCapabilities & LIGHT_CAPABILITY_ANIMATION) == LIGHT_CAPABILITY_ANIMATION;
    }

    /**
     * Returns preferred brightness levels for the light which will be used when user
     * increase/decrease brightness levels for the light (currently only used for Keyboard
     * backlight control using backlight up/down keys).
     *
     * The values in the preferred brightness level array are in the range [0, 255].
     *
     * @hide
     */
    @Nullable
    public int[] getPreferredBrightnessLevels() {
        return mPreferredBrightnessLevels;
    }

    /**
     * Returns the minimum update period supported by the light. This corresponds to the inverse of
     * the maximum supported fps for the light.
     * <p>
     * A value of 0  means that the light does not support animations and applications should not
     * use effects on the light.
     *
     * @return the minimum period, in milliseconds, or 0 if the light does not support effects.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_LIGHT_ANIMATIONS)
    @DurationMillisLong
    public long getMinUpdatePeriodMillis() {
        return mMinUpdatePeriodMillis;
    }

    /**
     * Builder for creating light objects.
     *
     * @hide
     */
    public static final class Builder {
        private int mId;
        private String mName;
        private int mOrdinal;
        private @LightType int mType;
        private @LightCapability int mCapabilities;
        @Nullable private int[] mPreferredBrightnessLevels;
        private long mMinUpdatePeriodMillis;

        /**
         * Creates a new {@link Light.Builder}.
         * @hide
         */
        public Builder(int id, int ordinal, @LightType int type) {
            mId = id;
            mOrdinal = ordinal;
            mType = type;
            mName = "Light";
            mCapabilities = 0;
            mPreferredBrightnessLevels = null;
            mMinUpdatePeriodMillis = 0;
        }

        /**
         * Set the name of the light;
         *
         * @return the updated instance of the {@link Builder}.
         * @hide
         */
        public Builder setName(String name) {
            mName = name;
            return this;
        }

        /**
         * Set the capabilities associated with this light;
         *
         * @return the updated instance of the {@link Builder}.
         * @hide
         */
        public Builder setCapabilities(@LightCapability int capabilities) {
            mCapabilities = capabilities;
            return this;
        }

        /**
         * Sets the preferred brightess levels for the light.
         *
         * @return the updated instance of the {@link Builder}.
         * @hide
         */
        public Builder setPreferredBrightnessLevels(@Nullable int[] preferredBrightnessLevels) {
            mPreferredBrightnessLevels = preferredBrightnessLevels;
            return this;
        }

        /**
         * Sets the min update period for the light.
         *
         * @return the updated instance of the {@link Builder}.
         * @hide
         */
        public Builder setMinUpdatePeriodMillis(long minUpdatePeriodMillis) {
            mMinUpdatePeriodMillis = minUpdatePeriodMillis;
            return this;
        }

        /**
         * Build the light from the provided parameters.
         *
         * @return Light object configured to the parameters provided.
         * @hide
         */
        public Light build() {
            return new Light(
                    mId,
                    mName,
                    mOrdinal,
                    mType,
                    mCapabilities,
                    mPreferredBrightnessLevels,
                    mMinUpdatePeriodMillis);
        }
    }
}
