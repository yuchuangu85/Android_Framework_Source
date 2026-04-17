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

package android.view;

import static com.android.server.display.feature.flags.Flags.FLAG_FRAME_RATE_MAPPING_API;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Parcelable.Creator;

import java.util.Objects;

/**
 * Contains the frame rate / velocity values.
 * The velocity value can be used as a threshold value to determine a feasible frame rate
 * For example, if the velocity is greater than 300 dp (density-independent pixels) per second,
 * then we need frame rate to be 120 to ensure the smoothness.
 */
@FlaggedApi(FLAG_FRAME_RATE_MAPPING_API)
public final class FrameRateVelocityPoint implements Parcelable {
    private final float mFramePerSecond;
    private final float mDpPerSecond;

    public FrameRateVelocityPoint(float framePerSecond, float dpPerSecond) {
        mFramePerSecond = framePerSecond;
        mDpPerSecond = dpPerSecond;
    }

    private FrameRateVelocityPoint(@NonNull Parcel in) {
        mFramePerSecond = in.readFloat();
        mDpPerSecond = in.readFloat();
    }

    /**
     * Get the value of frame per second
     */
    public float getFramePerSecond() {
        return mFramePerSecond;
    }

    /**
     * Get the value of dp per second
     */
    public float getDpPerSecond() {
        return mDpPerSecond;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FrameRateVelocityPoint that = (FrameRateVelocityPoint) o;
        return Float.compare(that.getFramePerSecond(), mFramePerSecond) == 0
                && Float.compare(that.getDpPerSecond(), mDpPerSecond) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFramePerSecond, mDpPerSecond);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeFloat(mFramePerSecond);
        dest.writeFloat(mDpPerSecond);
    }

    @NonNull
    public static final Creator<FrameRateVelocityPoint> CREATOR =
            new Creator<FrameRateVelocityPoint>() {
                @Override
                public FrameRateVelocityPoint createFromParcel(Parcel in) {
                    return new FrameRateVelocityPoint(in);
                }

                @Override
                public FrameRateVelocityPoint[] newArray(int size) {
                    return new FrameRateVelocityPoint[size];
                }
            };
}
