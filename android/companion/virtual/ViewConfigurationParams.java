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

package android.companion.virtual;

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.companion.virtualdevice.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.ViewConfiguration;

import java.time.Duration;
import java.util.Objects;

/**
 * Parameters related to {@link ViewConfiguration} that can be configured when creating a virtual
 * device. When these parameters are set, {@link ViewConfiguration} methods would return the
 * configured values for any context associated with the virtual device.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_VIEWCONFIGURATION_APIS)
public final class ViewConfigurationParams implements Parcelable {

    /**
     * @hide
     */
    public static final int INVALID_VALUE = -1;

    private final int mTouchSlopPixels;
    private final int mMinimumFlingVelocityPixelsPerSecond;
    private final int mMaximumFlingVelocityPixelsPerSecond;
    private final float mScrollFriction;
    private final int mTapTimeoutMillis;
    private final int mDoubleTapTimeoutMillis;
    private final int mDoubleTapMinTimeMillis;
    private final int mLongPressTimeoutMillis;
    private final int mMultiPressTimeoutMillis;

    private ViewConfigurationParams(int touchSlopPixels,
            int minimumFlingVelocityPixelsPerSecond, int maximumFlingVelocityPixelsPerSecond,
            float scrollFriction, int tapTimeoutMillis, int doubleTapTimeoutMillis,
            int doubleTapMinTimeMillis, int longPressTimeoutMillis, int multiPressTimeoutMillis) {
        mTouchSlopPixels = touchSlopPixels;
        mMinimumFlingVelocityPixelsPerSecond = minimumFlingVelocityPixelsPerSecond;
        mMaximumFlingVelocityPixelsPerSecond = maximumFlingVelocityPixelsPerSecond;
        mScrollFriction = scrollFriction;
        mTapTimeoutMillis = tapTimeoutMillis;
        mDoubleTapTimeoutMillis = doubleTapTimeoutMillis;
        mDoubleTapMinTimeMillis = doubleTapMinTimeMillis;
        mLongPressTimeoutMillis = longPressTimeoutMillis;
        mMultiPressTimeoutMillis = multiPressTimeoutMillis;
    }

    private ViewConfigurationParams(Parcel in) {
        mTouchSlopPixels = in.readInt();
        mMinimumFlingVelocityPixelsPerSecond = in.readInt();
        mMaximumFlingVelocityPixelsPerSecond = in.readInt();
        mScrollFriction = in.readFloat();
        mTapTimeoutMillis = in.readInt();
        mDoubleTapTimeoutMillis = in.readInt();
        mDoubleTapMinTimeMillis = in.readInt();
        mLongPressTimeoutMillis = in.readInt();
        mMultiPressTimeoutMillis = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mTouchSlopPixels);
        dest.writeInt(mMinimumFlingVelocityPixelsPerSecond);
        dest.writeInt(mMaximumFlingVelocityPixelsPerSecond);
        dest.writeFloat(mScrollFriction);
        dest.writeInt(mTapTimeoutMillis);
        dest.writeInt(mDoubleTapTimeoutMillis);
        dest.writeInt(mDoubleTapMinTimeMillis);
        dest.writeInt(mLongPressTimeoutMillis);
        dest.writeInt(mMultiPressTimeoutMillis);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ViewConfigurationParams that)) return false;
        return mTouchSlopPixels == that.mTouchSlopPixels
                && mMinimumFlingVelocityPixelsPerSecond == that.mMinimumFlingVelocityPixelsPerSecond
                && mMaximumFlingVelocityPixelsPerSecond == that.mMaximumFlingVelocityPixelsPerSecond
                && Float.compare(mScrollFriction, that.mScrollFriction) == 0
                && mTapTimeoutMillis == that.mTapTimeoutMillis
                && mDoubleTapTimeoutMillis == that.mDoubleTapTimeoutMillis
                && mDoubleTapMinTimeMillis == that.mDoubleTapMinTimeMillis
                && mLongPressTimeoutMillis == that.mLongPressTimeoutMillis
                && mMultiPressTimeoutMillis == that.mMultiPressTimeoutMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTouchSlopPixels, mMinimumFlingVelocityPixelsPerSecond,
                mMaximumFlingVelocityPixelsPerSecond, mScrollFriction, mTapTimeoutMillis,
                mDoubleTapTimeoutMillis, mDoubleTapMinTimeMillis, mLongPressTimeoutMillis,
                mMultiPressTimeoutMillis);
    }

    @Override
    @NonNull
    public String toString() {
        return "ViewConfigurationParams("
                + "mTouchSlopPixels=" + mTouchSlopPixels
                + ", mMinimumFlingVelocityPixelsPerSecond=" + mMinimumFlingVelocityPixelsPerSecond
                + ", mMaximumFlingVelocityPixelsPerSecond=" + mMaximumFlingVelocityPixelsPerSecond
                + ", mScrollFriction=" + mScrollFriction
                + ", mTapTimeoutMillis=" + mTapTimeoutMillis
                + ", mDoubleTapTimeoutMillis=" + mDoubleTapTimeoutMillis
                + ", mDoubleTapMinTimeMillis=" + mDoubleTapMinTimeMillis
                + ", mLongPressTimeoutMillis=" + mLongPressTimeoutMillis
                + ", mMultiPressTimeoutMillis=" + mMultiPressTimeoutMillis
                + ')';
    }

    /**
     * Returns the touch slop in pixels.
     *
     * @see ViewConfiguration#getScaledTouchSlop()
     */
    @IntRange(from = 0)
    public int getTouchSlopPixels() {
        return mTouchSlopPixels;
    }

    /**
     * Returns the minimum fling velocity in pixels per second.
     *
     * @see ViewConfiguration#getScaledMinimumFlingVelocity()
     */
    @IntRange(from = 0)
    public int getMinimumFlingVelocityPixelsPerSecond() {
        return mMinimumFlingVelocityPixelsPerSecond;
    }

    /**
     * Returns the maximum fling velocity in pixels per second.
     *
     * @see ViewConfiguration#getScaledMaximumFlingVelocity()
     */
    @IntRange(from = 0)
    public int getMaximumFlingVelocityPixelsPerSecond() {
        return mMaximumFlingVelocityPixelsPerSecond;
    }

    /**
     * Returns the scroll friction.
     *
     * @see ViewConfiguration#getScrollFrictionAmount()
     */
    @FloatRange(from = 0)
    public float getScrollFriction() {
        return mScrollFriction;
    }

    /**
     * Returns a {@link Duration} representing the tap timeout.
     *
     * @see ViewConfiguration#getTapTimeoutMillis()
     */
    @NonNull
    public Duration getTapTimeoutDuration() {
        return Duration.ofMillis(mTapTimeoutMillis);
    }

    /**
     * Returns a {@link Duration} representing the double tap timeout.
     *
     * @see ViewConfiguration#getDoubleTapTimeoutMillis()
     */
    @NonNull
    public Duration getDoubleTapTimeoutDuration() {
        return Duration.ofMillis(mDoubleTapTimeoutMillis);
    }

    /**
     * Returns a {@link Duration} representing the double tap minimum time.
     */
    @NonNull
    public Duration getDoubleTapMinTimeDuration() {
        return Duration.ofMillis(mDoubleTapMinTimeMillis);
    }

    /**
     * Returns a {@link Duration} representing the long press timeout.
     */
    @NonNull
    public Duration getLongPressTimeoutDuration() {
        return Duration.ofMillis(mLongPressTimeoutMillis);
    }

    /**
     * Returns a {@link Duration} representing the multi press timeout.
     */
    @NonNull
    public Duration getMultiPressTimeoutDuration() {
        return Duration.ofMillis(mMultiPressTimeoutMillis);
    }

    @NonNull
    public static final Creator<ViewConfigurationParams> CREATOR =
            new Creator<>() {
                @Override
                public ViewConfigurationParams createFromParcel(Parcel in) {
                    return new ViewConfigurationParams(in);
                }

                @Override
                public ViewConfigurationParams[] newArray(int size) {
                    return new ViewConfigurationParams[size];
                }
            };

    /**
     * Builder for {@link ViewConfigurationParams}.
     */
    @FlaggedApi(Flags.FLAG_VIEWCONFIGURATION_APIS)
    public static final class Builder {

        private int mTouchSlopPixels = INVALID_VALUE;
        private int mMinimumFlingVelocityPixelsPerSecond = INVALID_VALUE;
        private int mMaximumFlingVelocityPixelsPerSecond = INVALID_VALUE;
        private float mScrollFriction = INVALID_VALUE;
        private int mTapTimeoutMillis = INVALID_VALUE;
        private int mDoubleTapTimeoutMillis = INVALID_VALUE;
        private int mDoubleTapMinTimeMillis = INVALID_VALUE;
        private int mLongPressTimeoutMillis = INVALID_VALUE;
        private int mMultiPressTimeoutMillis = INVALID_VALUE;

        /**
         * Sets the touch slop in pixels. When this is set,
         * {@link ViewConfiguration#getScaledTouchSlop()} would return this value for any context
         * associated with the virtual device.
         *
         * @throws IllegalArgumentException if the value is negative.
         * @see ViewConfiguration#getScaledTouchSlop()
         */
        @NonNull
        public Builder setTouchSlopPixels(@IntRange(from = 0) int touchSlopPixels) {
            if (touchSlopPixels < 0) {
                throw new IllegalArgumentException("Touch slop cannot be negative");
            }
            mTouchSlopPixels = touchSlopPixels;
            return this;
        }

        /**
         * Sets the minimum fling velocity in pixels per second. When this is set,
         * {@link ViewConfiguration#getScaledMinimumFlingVelocity()} would return this value for
         * any context associated with the virtual device.
         *
         * @throws IllegalArgumentException if the value is negative.
         * @see ViewConfiguration#getScaledMinimumFlingVelocity()
         */
        @NonNull
        public Builder setMinimumFlingVelocityPixelsPerSecond(
                @IntRange(from = 0) int minimumFlingVelocityPixelsPerSecond) {
            if (minimumFlingVelocityPixelsPerSecond < 0) {
                throw new IllegalArgumentException("Minimum fling velocity cannot be negative");
            }
            mMinimumFlingVelocityPixelsPerSecond = minimumFlingVelocityPixelsPerSecond;
            return this;
        }

        /**
         * Sets the maximum fling velocity in pixels per second. When this is set,
         * {@link ViewConfiguration#getScaledMaximumFlingVelocity()} would return this value for
         * any context associated with the virtual device.
         *
         * @throws IllegalArgumentException if the value is negative.
         * @see ViewConfiguration#getScaledMaximumFlingVelocity()
         */
        @NonNull
        public Builder setMaximumFlingVelocityPixelsPerSecond(
                @IntRange(from = 0) int maximumFlingVelocityPixelsPerSecond) {
            if (maximumFlingVelocityPixelsPerSecond < 0) {
                throw new IllegalArgumentException("Maximum fling velocity cannot be negative");
            }
            mMaximumFlingVelocityPixelsPerSecond = maximumFlingVelocityPixelsPerSecond;
            return this;
        }

        /**
         * Sets the scroll friction.
         *
         * @throws IllegalArgumentException if the value is negative.
         * @see ViewConfiguration#getScrollFrictionAmount()
         */
        @NonNull
        public Builder setScrollFriction(@FloatRange(from = 0) float scrollFriction) {
            if (scrollFriction < 0) {
                throw new IllegalArgumentException("Scroll friction cannot be negative");
            }
            mScrollFriction = scrollFriction;
            return this;
        }

        /**
         * Sets the tap timeout as {@link Duration}.
         *
         * @throws IllegalArgumentException if the corresponding milliseconds value is negative, or
         *                                  greater than {@link Integer#MAX_VALUE}.
         * @see ViewConfiguration#getTapTimeoutMillis()
         */
        @NonNull
        public Builder setTapTimeoutDuration(@NonNull Duration duration) {
            Objects.requireNonNull(duration);
            if (duration.isNegative()) {
                throw new IllegalArgumentException("Tap timeout cannot be negative");
            }
            long millis = duration.toMillis();
            if (millis > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Tap timeout cannot be larger than " + Integer.MAX_VALUE);
            }
            mTapTimeoutMillis = (int) millis;
            return this;
        }

        /**
         * Sets the double tap timeout as {@link Duration}.
         *
         * @throws IllegalArgumentException if the corresponding milliseconds value is negative, or
         *                                  greater than {@link Integer#MAX_VALUE}.
         * @see ViewConfiguration#getDoubleTapTimeoutMillis()
         */
        @NonNull
        public Builder setDoubleTapTimeoutDuration(@NonNull Duration duration) {
            Objects.requireNonNull(duration);
            if (duration.isNegative()) {
                throw new IllegalArgumentException("Double tap timeout cannot be negative");
            }
            long millis = duration.toMillis();
            if (millis > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Double tap timeout cannot be larger than " + Integer.MAX_VALUE);
            }
            mDoubleTapTimeoutMillis = (int) millis;
            return this;
        }

        /**
         * Sets the double tap minimum time as {@link Duration}.
         *
         * @throws IllegalArgumentException if the corresponding milliseconds value is negative, or
         *                                  greater than {@link Integer#MAX_VALUE}.
         */
        @NonNull
        public Builder setDoubleTapMinTimeDuration(@NonNull Duration duration) {
            Objects.requireNonNull(duration);
            if (duration.isNegative()) {
                throw new IllegalArgumentException("Double tap min time cannot be negative");
            }
            long millis = duration.toMillis();
            if (millis > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Double tap min time cannot be larger than " + Integer.MAX_VALUE);
            }
            mDoubleTapMinTimeMillis = (int) millis;
            return this;
        }

        /**
         * Sets the long press timeout as {@link Duration}.
         *
         * @throws IllegalArgumentException if the corresponding milliseconds value is negative, or
         *                                  greater than {@link Integer#MAX_VALUE}.
         */
        @NonNull
        public Builder setLongPressTimeoutDuration(@NonNull Duration duration) {
            Objects.requireNonNull(duration);
            if (duration.isNegative()) {
                throw new IllegalArgumentException("Long press timeout cannot be negative");
            }
            long millis = duration.toMillis();
            if (millis > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Long press timeout cannot be larger than " + Integer.MAX_VALUE);
            }
            mLongPressTimeoutMillis = (int) millis;
            return this;
        }

        /**
         * Sets the multi press timeout as {@link Duration}.
         *
         * @throws IllegalArgumentException if the corresponding milliseconds value is negative, or
         *                                  greater than {@link Integer#MAX_VALUE}.
         */
        @NonNull
        public Builder setMultiPressTimeoutDuration(@NonNull Duration duration) {
            Objects.requireNonNull(duration);
            if (duration.isNegative()) {
                throw new IllegalArgumentException("Multi press timeout cannot be negative");
            }
            long millis = duration.toMillis();
            if (millis > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Multi press timeout cannot be larger than " + Integer.MAX_VALUE);
            }
            mMultiPressTimeoutMillis = (int) millis;
            return this;
        }

        /**
         * Builds the {@link ViewConfigurationParams} instance.
         *
         * @throws IllegalArgumentException if there's no parameter set, or if the minimum fling
         *                                  velocity is greater than the maximum fling velocity.
         */
        @NonNull
        public ViewConfigurationParams build() {
            if (mTouchSlopPixels == INVALID_VALUE
                    && mMinimumFlingVelocityPixelsPerSecond == INVALID_VALUE
                    && mMaximumFlingVelocityPixelsPerSecond == INVALID_VALUE
                    && mScrollFriction == INVALID_VALUE
                    && mTapTimeoutMillis == INVALID_VALUE
                    && mDoubleTapTimeoutMillis == INVALID_VALUE
                    && mDoubleTapMinTimeMillis == INVALID_VALUE
                    && mLongPressTimeoutMillis == INVALID_VALUE
                    && mMultiPressTimeoutMillis == INVALID_VALUE) {
                throw new IllegalArgumentException("None of the parameters are set");
            }
            if (mMinimumFlingVelocityPixelsPerSecond != INVALID_VALUE
                    && mMaximumFlingVelocityPixelsPerSecond != INVALID_VALUE
                    && mMinimumFlingVelocityPixelsPerSecond
                    > mMaximumFlingVelocityPixelsPerSecond) {
                throw new IllegalArgumentException(
                        "Minimum fling velocity cannot be greater than the maximum fling velocity");
            }
            return new ViewConfigurationParams(mTouchSlopPixels,
                    mMinimumFlingVelocityPixelsPerSecond,
                    mMaximumFlingVelocityPixelsPerSecond, mScrollFriction, mTapTimeoutMillis,
                    mDoubleTapTimeoutMillis, mDoubleTapMinTimeMillis, mLongPressTimeoutMillis,
                    mMultiPressTimeoutMillis);
        }
    }
}
