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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class that manages the status of Identity Check.
 * @hide
 */
@TestApi
@FlaggedApi(Flags.FLAG_IDENTITY_CHECK_TEST_API)
public final class IdentityCheckStatus implements Parcelable {
    private final boolean mIsIdentityCheckValueForTestAvailable;
    private final boolean mIsIdentityCheckActive;

    private IdentityCheckStatus(@Nullable Parcel in) {
        mIsIdentityCheckValueForTestAvailable = in.readByte() != 0;
        mIsIdentityCheckActive = in.readByte() != 0;
    }

    @NonNull
    public static final Creator<IdentityCheckStatus> CREATOR = new Creator<>() {
        @Override
        public IdentityCheckStatus createFromParcel(Parcel in) {
            return new IdentityCheckStatus(in);
        }

        @Override
        public IdentityCheckStatus[] newArray(int size) {
            return new IdentityCheckStatus[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mIsIdentityCheckValueForTestAvailable);
        dest.writeBoolean(mIsIdentityCheckActive);
    }

    /**
     * @return if Identity Check is being tested.
     */
    public boolean isIdentityCheckValueForTestAvailable() {
        return mIsIdentityCheckValueForTestAvailable;
    }

    /**
     * @return if Identity Check is active for testing.
     */
    public boolean isIdentityCheckActive() {
        return mIsIdentityCheckActive;
    }

    private IdentityCheckStatus(boolean isIdentityCheckValueForTestAvailable,
            boolean isIdentityCheckActive) {
        mIsIdentityCheckValueForTestAvailable = isIdentityCheckValueForTestAvailable;
        mIsIdentityCheckActive = isIdentityCheckActive;
    }

    public static final class Builder {
        private boolean mIsIdentityCheckValueForTestAvailable;
        private boolean mIsIdentityCheckActive;

        /**
         * Set if Identity Check is in effect or not. Default value is false.
         **/
        @NonNull
        public Builder setIdentityCheckActive(boolean identityCheckActive) {
            mIsIdentityCheckActive = identityCheckActive;
            return this;
        }

        /**
         * Set if the value set in {@link Builder#setIdentityCheckActive(boolean)}
         * is for testing purpose. Default value is false.
         * <p>
         * The value is reset in {@link BiometricTestSession#close()}.
         **/
        @NonNull
        public Builder setIdentityCheckValueForTestAvailable(
                boolean identityCheckValueForTestAvailable) {
            mIsIdentityCheckValueForTestAvailable = identityCheckValueForTestAvailable;
            return this;
        }

        /**
         * @return  an instance of {@link IdentityCheckStatus}.
         * Use {@link Builder#setIdentityCheckActive(boolean)}
         * and {@link Builder#setIdentityCheckValueForTestAvailable(boolean)}
         * to set values for the parameters.
         */
        @NonNull
        public IdentityCheckStatus build() {
            return new IdentityCheckStatus(mIsIdentityCheckValueForTestAvailable,
                    mIsIdentityCheckActive);
        }
    }
}
