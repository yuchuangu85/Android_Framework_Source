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

package android.os.vibrator;

import static android.os.VibrationAttributes.USAGE_UNKNOWN;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.VibrationAttributes;
import android.view.HapticFeedbackConstants;

import java.util.Objects;

/**
 * Encapsulates a request to perform a haptic feedback.
 *
 * <p>Use {@link Builder} to create a new instance of this class.
 */
@FlaggedApi(Flags.FLAG_HAPTIC_FEEDBACK_WITH_CUSTOM_USAGE)
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class HapticFeedbackRequest {
    private final int mFeedbackConstant;
    private final int mUsage;
    private final int mFlags;

    private HapticFeedbackRequest(
            @HapticFeedbackConstants.FeedbackConstant int feedbackConstant,
            @VibrationAttributes.Usage int usage,
            @HapticFeedbackConstants.Flags int flags) {
        mFeedbackConstant = feedbackConstant;
        mUsage = usage;
        mFlags = flags;
    }

    /**
     * Returns the haptic feedback constant used to define the vibration effect to be played by this
     * request.
     *
     * @see HapticFeedbackConstants
     */
    @HapticFeedbackConstants.FeedbackConstant
    public int getFeedbackConstant() {
        return mFeedbackConstant;
    }

    /**
     * Returns the {@link VibrationAttributes} usage for the haptic feedback request.
     *
     * @see VibrationAttributes#getUsage
     */
    @VibrationAttributes.Usage
    public int getUsage() {
        return mUsage;
    }

    /** Returns the {@link HapticFeedbackConstants} flags for the haptic feedback request. */
    @HapticFeedbackConstants.Flags
    public int getFlags() {
        return mFlags;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        HapticFeedbackRequest rhs = (HapticFeedbackRequest) o;
        return mFeedbackConstant == rhs.mFeedbackConstant
                && mUsage == rhs.mUsage
                && mFlags == rhs.mFlags;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFeedbackConstant, mUsage, mFlags);
    }

    /** Builder for {@link HapticFeedbackRequest}. */
    public static final class Builder {
        private final int mFeedbackConstant;

        private int mUsage = USAGE_UNKNOWN;
        private int mFlags = 0x0;

        /**
         * Constructs a new builder for {@link HapticFeedbackRequest}.
         *
         * @param constant the haptic feedback constant for {@link HapticFeedbackRequest} that
         *      will be constructed from the builder. This needs to be one of the constants
         *      defined in {@link HapticFeedbackConstants}.
         */
        public Builder(@HapticFeedbackConstants.FeedbackConstant int constant) {
            mFeedbackConstant = constant;
        }

        /**
         * Constructs a builder that is already populated with the fields from a given
         * {@link HapticFeedbackRequest}.
         *
         * @param request the request to create a new builder from.
         */
        public Builder(@NonNull HapticFeedbackRequest request) {
            Objects.requireNonNull(request);
            mFeedbackConstant = request.mFeedbackConstant;
            mUsage = request.mUsage;
            mFlags = request.mFlags;
        }

        /**
         * Sets the {@link VibrationAttributes} usage for the haptic feedback request.
         *
         * @see VibrationAttributes#getUsage
         */
        @NonNull
        public Builder setUsage(@VibrationAttributes.Usage int usage) {
            mUsage = usage;
            return this;
        }

        /** Sets the {@link HapticFeedbackConstants} flags for the haptic feedback request. */
        @NonNull
        public Builder setFlags(@HapticFeedbackConstants.Flags int flags) {
            mFlags = flags;
            return this;
        }

        /** Builds a new {@link HapticFeedbackRequest} from this builder object. */
        @NonNull
        public HapticFeedbackRequest build() {
            return new HapticFeedbackRequest(mFeedbackConstant, mUsage, mFlags);
        }
    }
}

