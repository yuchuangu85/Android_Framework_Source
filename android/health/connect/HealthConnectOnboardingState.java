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

package android.health.connect;

import static com.android.healthfitness.flags.Flags.FLAG_ONBOARDING;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents the onboarding state of HealthConnect user.
 *
 * @hide
 */
@FlaggedApi(FLAG_ONBOARDING)
public class HealthConnectOnboardingState implements Parcelable {

    /**
     * State indicating that the user has *no apps connected* to Health Connect, but there are *two
     * or more compatible apps installed* on the device that are available to connect.
     *
     * <p>This state suggests that an initial onboarding banner or prompt should be shown to
     * encourage the user to connect their first compatible apps.
     *
     * @hide
     */
    public static final int ONBOARDING_BANNER_STATE_ZERO_APPS_CONNECTED = 0;

    /**
     * State indicating that the user has *exactly one app connected* to Health Connect, and there
     * is *at least one other compatible app installed* on the device that is available to connect.
     *
     * @hide
     */
    public static final int ONBOARDING_BANNER_STATE_ONE_APP_CONNECTED = 1;

    /**
     * State indicating that the onboarding banner or notification should *not* be shown.
     *
     * <p>This state is reached under several conditions:
     *
     * <ul>
     *   <li>The user has already connected *two or more apps* to Health Connect.
     *   <li>A relevant onboarding notification or banner has already been shown according to
     *       internal logic and frequency limits.
     * </ul>
     *
     * In this state, further onboarding prompts are suppressed.
     *
     * @hide
     */
    public static final int ONBOARDING_BANNER_STATE_HIDE = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        ONBOARDING_BANNER_STATE_ZERO_APPS_CONNECTED,
        ONBOARDING_BANNER_STATE_ONE_APP_CONNECTED,
        ONBOARDING_BANNER_STATE_HIDE,
    })
    public @interface OnboardingState {}

    private final @OnboardingState int mOnboardingState;

    /**
     * The current onboarding state of HealthConnect user.
     *
     * <p>See also {@link OnboardingState}
     */
    public @OnboardingState int getOnboardingState() {
        return mOnboardingState;
    }

    /** @hide */
    public HealthConnectOnboardingState(@OnboardingState int onboardingState) {
        this.mOnboardingState = onboardingState;
    }

    @NonNull
    public static final Parcelable.Creator<HealthConnectOnboardingState> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public HealthConnectOnboardingState createFromParcel(Parcel in) {
                    return new HealthConnectOnboardingState(in);
                }

                @Override
                public HealthConnectOnboardingState[] newArray(int size) {
                    return new HealthConnectOnboardingState[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mOnboardingState);
    }

    private HealthConnectOnboardingState(Parcel in) {
        mOnboardingState = in.readInt();
    }
}
