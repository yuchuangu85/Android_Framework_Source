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

package android.hardware.biometrics;

import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

/**
 * Contains information related to Identity Check.
 * @hide
 */
public class IdentityCheckInfo implements Parcelable {
    //Values must match SysUiStatsLog#BIOMETRIC_PROMPT_STARTED__IdentityCheckDisableReason
    public static final int IDENTITY_CHECK_AUTHENTICATORS_INVALID = 1;
    public static final int IDENTITY_CHECK_TOGGLE_DISABLED = 2;
    public static final int IDENTITY_CHECK_REQUIREMENTS_NOT_SATISFIED = 3;
    public static final int IDENTITY_CHECK_STRONG_BIOMETRICS_NOT_ENROLLED = 4;
    public static final int IDENTITY_CHECK_DEVICE_IN_TRUSTED_LOCATION = 5;

    @IntDef({IDENTITY_CHECK_AUTHENTICATORS_INVALID,
            IDENTITY_CHECK_TOGGLE_DISABLED,
            IDENTITY_CHECK_REQUIREMENTS_NOT_SATISFIED,
            IDENTITY_CHECK_STRONG_BIOMETRICS_NOT_ENROLLED,
            IDENTITY_CHECK_DEVICE_IN_TRUSTED_LOCATION})
    public @interface IdentityCheckInactiveReason {}

    private boolean mIdentityCheckActive = false;
    private int mIdentityCheckInactiveReason = 0;
    private boolean mClearIdentityCheckFallbackOption = false;
    private boolean mDeviceCredentialAndIdentityCheck = false;

    public IdentityCheckInfo() {}

    protected IdentityCheckInfo(Parcel in) {
        mIdentityCheckActive = in.readBoolean();
        mIdentityCheckInactiveReason = in.readInt();
        if (Flags.clearFallbackOption()) {
            mClearIdentityCheckFallbackOption = in.readBoolean();
        }
        if (Flags.doubleAuth()) {
            mDeviceCredentialAndIdentityCheck = in.readBoolean();
        }
    }

    public static final Creator<IdentityCheckInfo> CREATOR = new Creator<IdentityCheckInfo>() {
        @Override
        public IdentityCheckInfo createFromParcel(Parcel in) {
            return new IdentityCheckInfo(in);
        }

        @Override
        public IdentityCheckInfo[] newArray(int size) {
            return new IdentityCheckInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mIdentityCheckActive);
        dest.writeInt(mIdentityCheckInactiveReason);
        if (Flags.clearFallbackOption()) {
            dest.writeBoolean(mClearIdentityCheckFallbackOption);
        }
        if (Flags.doubleAuth()) {
            dest.writeBoolean(mDeviceCredentialAndIdentityCheck);
        }
    }

    public void setIdentityCheckActive(boolean identityCheckActive) {
        mIdentityCheckActive = identityCheckActive;
    }

    public void setIdentityCheckInactiveReason(
            @IdentityCheckInactiveReason int identityCheckDisabledReason) {
        mIdentityCheckInactiveReason = identityCheckDisabledReason;
    }

    public void clearIdentityCheckFallbackOption() {
        mClearIdentityCheckFallbackOption = true;
    }

    public void setDeviceCredentialAndIdentityCheck(boolean status) {
        mDeviceCredentialAndIdentityCheck = status;
    }

    public boolean isClearIdentityCheckFallbackOption() {
        return mClearIdentityCheckFallbackOption;
    }

    public boolean isIdentityCheckActive() {
        return mIdentityCheckActive;
    }

    public int getIdentityCheckInactiveReason() {
        return mIdentityCheckInactiveReason;
    }

    public boolean isDeviceCredentialAndIdentityCheckRequested() {
        return mDeviceCredentialAndIdentityCheck;
    }
}
