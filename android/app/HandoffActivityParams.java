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

package android.app;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.companion.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Represents options specifying how an activity will be handed off to other devices.
 *
 * <p>This object is passed as an optional argument to {@link Activity#enableHandoff()}, and will
 * dictate when the platform calls {@link Activity#onHandoffActivityDataRequested()} on the
 * specified activity.
 */
@FlaggedApi(android.companion.Flags.FLAG_TASK_CONTINUITY)
public final class HandoffActivityParams implements Parcelable {

    private final boolean mAllowHandoffWithoutPackageInstalled;

    private HandoffActivityParams(@NonNull Builder builder) {
        Objects.requireNonNull(builder);
        mAllowHandoffWithoutPackageInstalled = builder.mAllowHandoffWithoutPackageInstalled;
    }

    private HandoffActivityParams(Parcel in) {
        mAllowHandoffWithoutPackageInstalled = in.readBoolean();
    }

    public static final @NonNull Creator<HandoffActivityParams> CREATOR =
            new Creator<>() {
                @Override
                public HandoffActivityParams createFromParcel(Parcel in) {
                    return new HandoffActivityParams(in);
                }

                @Override
                public HandoffActivityParams[] newArray(int size) {
                    return new HandoffActivityParams[size];
                }
            };

    @Override
    public int hashCode() {
        return Objects.hash(mAllowHandoffWithoutPackageInstalled);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof HandoffActivityParams) {
            final HandoffActivityParams other =
                    (HandoffActivityParams) obj;
            return mAllowHandoffWithoutPackageInstalled
                            == other.mAllowHandoffWithoutPackageInstalled;
        }
        return false;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mAllowHandoffWithoutPackageInstalled);
    }

    /**
     * Returns whether the activity should be handed off even if the package is not installed on the
     * remote device. If this is set to {@code false}, this activity will not appear as a Handoff
     * suggestion on devices which do not have the package installed.
     *
     * If this is set to {@code true}, the activity must provide a URL to perform web handoff. If
     * one is not provided, the user will be shown an error message.
     *
     * @return whether the activity should be handed off even if the package is not installed on the
     *     remote device.
     */
    public boolean isAllowHandoffWithoutPackageInstalled() {
        return mAllowHandoffWithoutPackageInstalled;
    }

    /** Builder for {@link HandoffActivityParams}. */
    @FlaggedApi(android.companion.Flags.FLAG_TASK_CONTINUITY)
    public static final class Builder {
        private boolean mAllowHandoffWithoutPackageInstalled = false;

        /**
         * Creates a new builder for {@link HandoffActivityParams}.
         *
         * @return the builder.
         */
        public Builder() {
        }

        /**
         * Sets whether the activity should be handed off even if the package is not installed on
         * the remote device. If this is set to {@code false}, this activity will not appear as a
         * Handoff suggestion on devices which do not have the package installed.
         *
         * <p>If set to {@code true}, {@link Activity#onHandoffActivityDataRequested()} will be
         * called even if the package is not installed on the remote device. The returned {@link
         * HandoffActivityData} must contain a web URL - either created via {@link
         * HandoffActivityData#createWebHandoffData(String)} or by setting a fallback URL via {@link
         * HandoffActivityData.Builder#setFallbackUrl(URL)}. If one is not present, the user will
         * be shown an error message.
         *
         * @param allowHandoffWithoutPackageInstalled whether the activity should be handed off even
         *     if the package is not installed on the remote device.
         * @return the builder.
         */
        @NonNull
        public Builder setAllowHandoffWithoutPackageInstalled(
                boolean allowHandoffWithoutPackageInstalled) {
            mAllowHandoffWithoutPackageInstalled = allowHandoffWithoutPackageInstalled;
            return this;
        }

        /**
         * Builds the {@link HandoffActivityParams} object.
         *
         * @return the {@link HandoffActivityParams} object.
         */
        @NonNull
        public HandoffActivityParams build() {
            return new HandoffActivityParams(this);
        }
    }
}
