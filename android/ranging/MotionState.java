/*
 * Copyright 2025 The Android Open Source Project
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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.ranging.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents the motion state of a ranging device.
 */
@FlaggedApi(Flags.FLAG_RANGING_STACK_UPDATES_26_Q_2)
public final class MotionState implements Parcelable {

    @Motion private final int mMotionState;

    /** Motion was not detected (orientation change < 5°). */
    public static final int MOTION_NOT_DETECTED = 0;

    /** Slight motion was detected (orientation change between 5° and 7°). */
    public static final int MOTION_SLIGHT = 1;

    /** Moderate motion was detected (orientation change between 7° and 10°). */
    public static final int MOTION_MODERATE = 2;

    /** Large motion was detected (orientation change >= 10°). */
    public static final int MOTION_LARGE = 3;

    /** @hide */
    @IntDef({
            MOTION_NOT_DETECTED,
            MOTION_SLIGHT,
            MOTION_MODERATE,
            MOTION_LARGE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Motion {}

    /**
     * Constructs a new {@link MotionState}.
     *
     * @param motionState The motion state of the device. Must be one of {@link
     *     #MOTION_NOT_DETECTED}, {@link #MOTION_SLIGHT}, {@link #MOTION_MODERATE}, or {@link
     *     #MOTION_LARGE}.
     */
    public MotionState(@Motion int motionState) {
        mMotionState = motionState;
    }

    private MotionState(Parcel in) {
        mMotionState = in.readInt();
    }

    /**
     * Returns the motion state of the device.
     *
     * @return the motion state, which is one of {@link #MOTION_NOT_DETECTED}, {@link
     *     #MOTION_SLIGHT}, {@link #MOTION_MODERATE}, or {@link #MOTION_LARGE}.
     */
    @Motion
    public int getMotionState() {
        return mMotionState;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mMotionState);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MotionState)) {
            return false;
        }
        MotionState that = (MotionState) o;
        return mMotionState == that.mMotionState;
    }

    @Override
    public int hashCode() {
        return mMotionState;
    }

    private static String motionStateToString(@Motion int state) {
        return switch (state) {
            case MOTION_NOT_DETECTED -> "NOT_DETECTED";
            case MOTION_SLIGHT -> "SLIGHT";
            case MOTION_MODERATE -> "MODERATE";
            case MOTION_LARGE -> "LARGE";
            default -> "UNKNOWN(" + state + ")";
        };
    }

    @Override
    public String toString() {
        return "MotionState { Motion = " + motionStateToString(mMotionState) + " }";
    }

    public static final @NonNull Creator<MotionState> CREATOR = new Creator<MotionState>() {
        @Override
        public MotionState createFromParcel(Parcel in) {
            return new MotionState(in);
        }

        @Override
        public MotionState[] newArray(int size) {
            return new MotionState[size];
        }
    };
}
