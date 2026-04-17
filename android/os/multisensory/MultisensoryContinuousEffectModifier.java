/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.os.multisensory;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.FloatRange;
import androidx.annotation.NonNull;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A class that encapsulates modifications to parameters of a continuous feedback effect in the
 * Multisensory Design System (MSDS).
 *
 * <p>The modifications can refer to the intensity of the effect ({@link
 * #TARGET_PARAMETER_INTENSITY}, or the "sharpness" of the effect ({@link
 * #TARGET_PARAMETER_SHARPNESS}).
 *
 * <p>These modifications only apply to effects created by a {@link MultisensoryToken} that can
 * generate a continuous feedback effect.
 */
@FlaggedApi(android.os.multisensory.Flags.FLAG_ENABLE_MULTISENSORY_FEEDBACK)
public final class MultisensoryContinuousEffectModifier implements Parcelable {

    /** @hide */
    @IntDef(
            prefix = {"TARGET_PARAMETER_"},
            value = {
                TARGET_PARAMETER_INTENSITY,
                TARGET_PARAMETER_SHARPNESS,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TargetParameter {}

    /**
     * The modifier refers to the intensity gain of an effect in a normalized scale from 0 to 1.
     *
     * <p>When related to vibration, this represents the strength of the vibration, where 1
     * represents the highest intensity at the current vibration frequency, and 0 represents no
     * intensity.
     */
    public static final int TARGET_PARAMETER_INTENSITY = 0;

    /**
     * The modifier refers to the perceived sharpness of an effect in a scale from 0 to 1.
     *
     * <p>When related to vibration, sharpness is related to vibration frequency, where higher
     * frequencies are considered sharper/crispier and lower frequencies are consider
     * smoother/heavier. A value of 1 represents the maximum sharpness achievable by the device, and
     * 0 is the lowest.
     */
    public static final int TARGET_PARAMETER_SHARPNESS = 1;

    /**
     * Return the string representation of the target parameter of a {@link
     * MultisensoryContinuousEffectModifier}.
     *
     * @param targetParameter one of TARGET_PARAMETER_ constants
     * @return string representation of the target parameter
     * @hide
     */
    @NonNull
    public static String targetParameterToString(@TargetParameter int targetParameter) {
        return switch (targetParameter) {
            case TARGET_PARAMETER_INTENSITY -> "Intensity modifier";
            case TARGET_PARAMETER_SHARPNESS -> "Sharpness modifier";
            default -> "Unknown modifier";
        };
    }

    @TargetParameter private final int mTargetParameter;
    private float mModifierValue;
    private long mRampDurationMillis;

    private MultisensoryContinuousEffectModifier(Parcel in) {
        mTargetParameter = in.readInt();
        mModifierValue = in.readFloat();
        mRampDurationMillis = in.readLong();
    }

    /**
     * Create a new {@link MultisensoryContinuousEffectModifier} for a target parameter.
     *
     * <p>By default, the modifier will have a value of 0 and a ramp duration of 100 milliseconds.
     * See {@link MultisensoryContinuousEffectModifier#setModifierValue(float)} and {@link
     * MultisensoryContinuousEffectModifier#setRampDuration(long)} to set custom values.
     *
     * @param targetParameter the target parameter of the modifier. Must be one of TARGET_PARAMETER_
     *     constants
     */
    public MultisensoryContinuousEffectModifier(@TargetParameter int targetParameter) {
        Preconditions.checkArgument(
                targetParameter == TARGET_PARAMETER_INTENSITY
                        || targetParameter == TARGET_PARAMETER_SHARPNESS,
                "The target parameter must be one of the TARGET_PARAMETER_ options but was %d",
                targetParameter);
        mTargetParameter = targetParameter;
        mModifierValue = 0;
        mRampDurationMillis = 100;
    }

    /**
     * Return the target parameter this modifier refers to. It is one of the TARGET_PARAMETER_
     * options.
     */
    @TargetParameter
    public int getTargetParameter() {
        return mTargetParameter;
    }

    /**
     * Return the value of this modifier.
     *
     * <p>The value ranges from 0 to 1 and its interpretation depends on the target parameter of
     * this modifier. See docs for the available TARGET_PARAMETER_ options for more.
     */
    public float getModifierValue() {
        return mModifierValue;
    }

    /**
     * Return the ramp duration of this modifier in milliseconds.
     *
     * <p>This is the time (in milliseconds) that the ongoing continuous effect will take to reach
     * the new target parameter value specified by this modifier.
     */
    public long getRampDurationMillis() {
        return mRampDurationMillis;
    }

    /**
     * Set the modifier value of this modifier.
     *
     * @param newValue The new modifier value in the range from 0 to 1. For the interpretation of
     *     the value, see the docs for the available TARGET_PARAMETER_ options.
     * @throws IllegalArgumentException If the new value is out of the allowed range from 0 to 1.
     */
    public void setModifierValue(@FloatRange(from = 0f, to = 1f) float newValue) {
        Preconditions.checkArgumentInRange(
                newValue, 0f, 1f, targetParameterToString(mTargetParameter));
        mModifierValue = newValue;
    }

    /**
     * Set the ramp duration of this modifier.
     *
     * @param newRampDurationMillis The new ramp duration in milliseconds.
     * @throws IllegalArgumentException If the ramp duration is negative or zero.
     */
    public void setRampDuration(long newRampDurationMillis) {
        Preconditions.checkArgument(
                newRampDurationMillis > 0,
                "The new ramp duration must be positive but was %d",
                newRampDurationMillis);
        mRampDurationMillis = newRampDurationMillis;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mTargetParameter);
        dest.writeFloat(mModifierValue);
        dest.writeLong(mRampDurationMillis);
    }

    @Override
    public String toString() {
        return "MultisensoryContinuousEffectModifier {"
                + " targetParameter="
                + targetParameterToString(mTargetParameter)
                + " value="
                + mModifierValue
                + " rampDurationMillis="
                + mRampDurationMillis
                + " }";
    }

    public static final @NonNull Creator<MultisensoryContinuousEffectModifier> CREATOR =
            new Creator<>() {
                @Override
                public MultisensoryContinuousEffectModifier createFromParcel(Parcel in) {
                    return new MultisensoryContinuousEffectModifier(in);
                }

                @Override
                public MultisensoryContinuousEffectModifier[] newArray(int size) {
                    return new MultisensoryContinuousEffectModifier[size];
                }
            };
}
