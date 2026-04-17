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

package android.telephony.satellite;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.flags.Flags;

import java.util.Objects;

/**
 * PointingUiAppLaunchIntentAttributes is used to provide information to create the PendingIntent
 * for launching the PointingUI app.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_SYSTEM_SELECTION_SPECIFIER_ENHANCEMENT)
public final class PointingUiAppLaunchIntentAttributes implements Parcelable {
    /**
     * Whether the PointingUI app should be launched in full screen mode.
     */
    private boolean mNeedFullScreen;
    /**
     * Whether the PointingUI app should be launched in demo mode.
     */
    private boolean mIsDemoMode;
    /**
     * Whether the PointingUI app should be launched in emergency mode.
     */
    private boolean mIsEmergencyMode;

    /**
     * Constructor for PointingUiAppLaunchIntentAttributes.
     *
     * @param needFullScreen Whether the PointingUI app should be launched in full screen mode.
     * @param isDemoMode Whether the PointingUI app should be launched in demo mode.
     * @param isEmergencyMode Whether the PointingUI app should be launched in emergency mode.
     */
    public PointingUiAppLaunchIntentAttributes(boolean needFullScreen, boolean isDemoMode,
            boolean isEmergencyMode) {
        mNeedFullScreen = needFullScreen;
        mIsDemoMode = isDemoMode;
        mIsEmergencyMode = isEmergencyMode;
    }

    private PointingUiAppLaunchIntentAttributes(Parcel in) {
        mNeedFullScreen = in.readBoolean();
        mIsDemoMode = in.readBoolean();
        mIsEmergencyMode = in.readBoolean();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeBoolean(mNeedFullScreen);
        out.writeBoolean(mIsDemoMode);
        out.writeBoolean(mIsEmergencyMode);
    }

    @NonNull public static final Creator<PointingUiAppLaunchIntentAttributes> CREATOR =
            new Creator<>() {
                @Override
                public PointingUiAppLaunchIntentAttributes createFromParcel(Parcel in) {
                    return new PointingUiAppLaunchIntentAttributes(in);
                }

                @Override
                public PointingUiAppLaunchIntentAttributes[] newArray(int size) {
                    return new PointingUiAppLaunchIntentAttributes[size];
                }
            };

    @Override
    @NonNull public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("mNeedFullScreen:");
        sb.append(mNeedFullScreen);
        sb.append(",");

        sb.append("mIsDemoMode:");
        sb.append(mIsDemoMode);
        sb.append(",");

        sb.append("mIsEmergencyMode:");
        sb.append(mIsEmergencyMode);
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PointingUiAppLaunchIntentAttributes that = (PointingUiAppLaunchIntentAttributes) o;
        return mNeedFullScreen == that.mNeedFullScreen
                && mIsDemoMode == that.mIsDemoMode
                && mIsEmergencyMode == that.mIsEmergencyMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNeedFullScreen, mIsDemoMode, mIsEmergencyMode);
    }

    /**
     * Returns whether the PointingUI app should be launched in full screen mode.
     */
    public boolean isFullScreen() {
        return mNeedFullScreen;
    }

    /**
     * Returns whether the PointingUI app should be launched in demo mode.
     */
    public boolean isDemoMode() {
        return mIsDemoMode;
    }

    /**
     * Returns whether the PointingUI app should be launched in emergency mode.
     */
    public boolean isEmergencyMode() {
        return mIsEmergencyMode;
    }

    /**
     * Builder for {@link PointingUiAppLaunchIntentAttributes}.
     */
    @FlaggedApi(Flags.FLAG_SYSTEM_SELECTION_SPECIFIER_ENHANCEMENT)
    public static final class Builder {
        private boolean mNeedFullScreen;
        private boolean mIsDemoMode;
        private boolean mIsEmergencyMode;

        /**
         * Sets whether the PointingUI app should be launched in full screen mode.
         *
         * @param isFullScreen Whether the PointingUI app should be launched in full screen mode.
         * @return The Builder to allow chaining.
         */
        @NonNull
        public Builder setFullScreen(boolean isFullScreen) {
            mNeedFullScreen = isFullScreen;
            return this;
        }

        /**
         * Sets whether the PointingUI app should be launched in demo mode.
         *
         * @param isDemoMode Whether the PointingUI app should be launched in demo mode.
         * @return The Builder to allow chaining.
         */
        @NonNull
        public Builder setDemoMode(boolean isDemoMode) {
            mIsDemoMode = isDemoMode;
            return this;
        }

        /**
         * Sets whether the PointingUI app should be launched in emergency mode.
         *
         * @param isEmergencyMode Whether the PointingUI app should be launched in emergency mode.
         * @return The Builder to allow chaining.
         */
        @NonNull
        public Builder setEmergencyMode(boolean isEmergencyMode) {
            mIsEmergencyMode = isEmergencyMode;
            return this;
        }

        /**
         * Builds the {@link PointingUiAppLaunchIntentAttributes} instance.
         *
         * @return The built {@link PointingUiAppLaunchIntentAttributes} instance.
         */
        @NonNull
        public PointingUiAppLaunchIntentAttributes build() {
            return new PointingUiAppLaunchIntentAttributes(
                    mNeedFullScreen, mIsDemoMode, mIsEmergencyMode);
        }
    }
}
