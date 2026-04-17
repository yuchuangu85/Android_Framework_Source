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
package android.app.modes;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.app.AutomaticZenRule;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.notification.Flags;
import android.view.WindowInsetsController;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A data class representing the details of a contextual mode.
 *
 * <p>A mode is a system-managed state that can influence device behavior, such as Do Not Disturb,
 * Bedtime, or Driving modes. This class encapsulates the unique identifier, type, and current state
 * of a specific mode.
 *
 * @see ContextualModeManager
 * @hide
 */
@TestApi
@FlaggedApi(Flags.FLAG_ENABLE_DND_SYNC)
public final class ContextualMode implements Parcelable {
    /**
     * Constant representing an unknown state for a mode. This value is typically used as a default
     * or when the actual state cannot be determined.
     */
    public static final int STATE_UNKNOWN = 0;

    /**
     * Constant representing an inactive state for a mode. When a mode is inactive, its associated
     * behaviors or policies are not currently enforced.
     */
    public static final int STATE_INACTIVE = 1;

    /**
     * Constant representing an active state for a mode. When a mode is active, its associated
     * behaviors or policies are currently enforced.
     */
    public static final int STATE_ACTIVE = 2;

    /** @hide */
    @IntDef({
        STATE_UNKNOWN,
        STATE_INACTIVE,
        STATE_ACTIVE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ModeState {}

    /**
     * Constant representing an unknown type for a mode. This value is typically used as a default
     * or when the actual type cannot be determined.
     */
    public static final int TYPE_UNKNOWN = AutomaticZenRule.TYPE_UNKNOWN;

    /**
     * Constant representing a generic mode type that does not fall into other specific categories.
     */
    public static final int TYPE_OTHER = AutomaticZenRule.TYPE_OTHER;

    /**
     * Constant representing a mode type for manual Do Not Disturb. This mode is typically activated
     * directly by the user to silence notifications and alerts.
     */
    public static final int TYPE_MANUAL_DO_NOT_DISTURB = 999;

    /**
     * Constant representing a mode type for modes triggered according to a time-based schedule. For
     * example, a mode that activates every night at a specific time.
     */
    public static final int TYPE_SCHEDULE_TIME = AutomaticZenRule.TYPE_SCHEDULE_TIME;

    /**
     * Constant representing a mode type for modes triggered by calendar events. For example, a mode
     * that activates during scheduled meetings.
     */
    public static final int TYPE_SCHEDULE_CALENDAR = AutomaticZenRule.TYPE_SCHEDULE_CALENDAR;

    /**
     * Constant representing a mode type for modes triggered by bedtime or sleeping activities. This
     * can include modes activated by time of day, sleep tracking, or snore detection.
     */
    public static final int TYPE_BEDTIME = AutomaticZenRule.TYPE_BEDTIME;

    /**
     * Constant representing a mode type for modes triggered by driving detection. This can include
     * modes activated by Bluetooth connections to a vehicle or detection of vehicle sounds.
     */
    public static final int TYPE_DRIVING = AutomaticZenRule.TYPE_DRIVING;

    /**
     * Constant representing a mode type for modes triggered by the user entering an immersive
     * activity. For example, a mode that activates when opening an app that uses {@link
     * WindowInsetsController#hide(int)} to go full screen.
     */
    public static final int TYPE_IMMERSIVE = AutomaticZenRule.TYPE_IMMERSIVE;

    /**
     * Constant representing a mode type for modes that imply the device should not make sound and
     * potentially hide some visual effects. This mode may be triggered when entering a location
     * where silence is requested, such as a theater.
     */
    public static final int TYPE_THEATER = AutomaticZenRule.TYPE_THEATER;

    /**
     * Constant representing a mode type for modes created and managed by a device owner. These
     * modes may have restricted editing capabilities for the device user.
     */
    public static final int TYPE_MANAGED = AutomaticZenRule.TYPE_MANAGED;

    /**
     * Constant representing a mode type for modes triggered during the user's transit. This could
     * include modes activated when the user is commuting or traveling.
     */
    @FlaggedApi(android.app.Flags.FLAG_MODES_UI_TRANSIT)
    public static final int TYPE_TRANSIT = AutomaticZenRule.TYPE_TRANSIT;

    /** @hide */
    @IntDef({
        TYPE_UNKNOWN,
        TYPE_OTHER,
        TYPE_MANUAL_DO_NOT_DISTURB,
        TYPE_SCHEDULE_TIME,
        TYPE_SCHEDULE_CALENDAR,
        TYPE_BEDTIME,
        TYPE_DRIVING,
        TYPE_IMMERSIVE,
        TYPE_THEATER,
        TYPE_MANAGED,
        TYPE_TRANSIT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ModeType {}

    /** A {@link Parcelable.Creator} that can unparcel {@link ContextualMode} objects. */
    @NonNull
    public static final Creator<ContextualMode> CREATOR =
            new Creator<>() {
                @Override
                public ContextualMode createFromParcel(Parcel in) {
                    return new ContextualMode(in);
                }

                @Override
                public ContextualMode[] newArray(int size) {
                    return new ContextualMode[size];
                }
            };

    private final String mId;
    private final int mType;
    private final int mState;

    /**
     * Creates a new {@link ContextualMode} instance.
     *
     * @param id the unique identifier for this mode, should be unique per-user
     * @param type the type of the mode, one of {@code TYPE_*} constants
     * @param state the current state of the mode, one of {@code STATE_*} constants
     */
    private ContextualMode(@NonNull String id, @ModeType int type, @ModeState int state) {
        mId = Objects.requireNonNull(id);
        mType = validateModeType(type);
        mState = validateModeState(state);
    }

    private ContextualMode(Parcel in) {
        mId = Objects.requireNonNull(in.readString8());
        mType = validateModeType(in.readInt());
        mState = validateModeState(in.readInt());
    }

    private int validateModeType(@ModeType int type) {
        return switch (type) {
            case TYPE_UNKNOWN,
                    TYPE_OTHER,
                    TYPE_MANUAL_DO_NOT_DISTURB,
                    TYPE_SCHEDULE_TIME,
                    TYPE_SCHEDULE_CALENDAR,
                    TYPE_BEDTIME,
                    TYPE_DRIVING,
                    TYPE_IMMERSIVE,
                    TYPE_THEATER,
                    TYPE_MANAGED,
                    TYPE_TRANSIT ->
                    type;
            default -> throw new IllegalArgumentException("Unknown mode type: " + type);
        };
    }

    private int validateModeState(@ModeState int state) {
        return switch (state) {
            case STATE_ACTIVE, STATE_INACTIVE, STATE_UNKNOWN -> state;
            default -> throw new IllegalArgumentException("Unknown mode state: " + state);
        };
    }

    /**
     * Returns the identifier of this mode. This is unique per-user.
     *
     * @return the ID string
     */
    @NonNull
    public String getId() {
        return mId;
    }

    /**
     * Returns the type of this mode.
     *
     * @return the mode type
     */
    @ModeType
    public int getType() {
        return mType;
    }

    /**
     * Returns the current state of this mode.
     *
     * @return the mode state
     */
    @ModeState
    public int getState() {
        return mState;
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mId);
        dest.writeInt(mType);
        dest.writeInt(mState);
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ContextualMode other)) {
            return false;
        }
        return mId.equals(other.mId) && mType == other.mType && mState == other.mState;
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return Objects.hash(mId, mType, mState);
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "ContextualMode{id="
                + getId()
                + ", type="
                + modeTypeToString(getType())
                + ", state="
                + modeStateToString(getState())
                + "}";
    }

    /**
     * Convert a mode type to string.
     *
     * @hide
     */
    public static String modeTypeToString(@ModeType int type) {
        return switch (type) {
            case TYPE_UNKNOWN -> "UNKNOWN";
            case TYPE_OTHER -> "OTHER";
            case TYPE_MANUAL_DO_NOT_DISTURB -> "MANUAL_DO_NOT_DISTURB";
            case TYPE_BEDTIME -> "BEDTIME";
            case TYPE_DRIVING -> "DRIVING";
            case TYPE_IMMERSIVE -> "IMMERSIVE";
            case TYPE_MANAGED -> "MANAGED";
            case TYPE_SCHEDULE_CALENDAR -> "SCHEDULE_CALENDAR";
            case TYPE_SCHEDULE_TIME -> "SCHEDULE_TIME";
            case TYPE_THEATER -> "THEATER";
            case TYPE_TRANSIT -> "TRANSIT";
            default -> Integer.toString(type);
        };
    }

    /**
     * Convert a mode state to string.
     *
     * @hide
     */
    public static String modeStateToString(@ModeState int state) {
        return switch (state) {
            case STATE_ACTIVE -> "ACTIVE";
            case STATE_INACTIVE -> "INACTIVE";
            case STATE_UNKNOWN -> "UNKNOWN";
            default -> Integer.toString(state);
        };
    }

    /** A builder to construct a {@link ContextualMode} instance. */
    public static final class Builder {
        private String mId;
        @ModeType private int mType = TYPE_UNKNOWN;
        @ModeState private int mState = STATE_UNKNOWN;

        /**
         * Create a builder from the given id.
         * @param id the id of mode
         */
        public Builder(@NonNull String id) {
            mId = id;
        }

        /**
         * Create a builder from a {@link ContextualMode}.
         * @param mode the mode to copy from
         */
        public Builder(@NonNull ContextualMode mode) {
            this.mId = mode.getId();
            this.mType = mode.getType();
            this.mState = mode.getState();
        }

        /**
         * Set the type of this {@link ContextualMode}.
         *
         * @param type the type to set
         * @return same instance for chaining
         */
        @NonNull
        public Builder setType(@ModeType int type) {
            mType = type;
            return this;
        }

        /**
         * Set the state of this {@link ContextualMode}.
         *
         * @param state the state to set
         * @return same instance for chaining
         */
        @NonNull
        public Builder setState(@ModeState int state) {
            mState = state;
            return this;
        }

        /**
         * Build a {@link ContextualMode} instance.
         *
         * @return the {@link ContextualMode} instance
         */
        @NonNull
        public ContextualMode build() {
            return new ContextualMode(mId, mType, mState);
        }
    }
}
