/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.telephony;

import android.annotation.NonNull;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Request used to register {@link SignalThresholdInfo} to be notified when the signal strength
 * breach the specified thresholds.
 */
public final class SignalStrengthUpdateRequest implements Parcelable {
    /**
     * List of SignalThresholdInfo for the request.
     */
    private final List<SignalThresholdInfo> mSignalThresholdInfos;

    /**
     * Whether the reporting is required for thresholds in the request while device is idle.
     */
    private final boolean mIsReportingRequestedWhileIdle;

    /**
     * Whether the reporting requested for system thresholds while device is idle.
     *
     * System signal thresholds are loaded from carrier config items and mainly used for UI
     * displaying. By default, they are ignored when device is idle. When setting the value to true,
     * modem will continue reporting signal strength changes over the system signal thresholds even
     * device is idle.
     *
     * This should only set to true by the system caller.
     */
    private final boolean mIsSystemThresholdReportingRequestedWhileIdle;

    /**
     * A IBinder object as a token for server side to check if the request client is still living.
     */
    private final IBinder mLiveToken;

    private SignalStrengthUpdateRequest(
            @NonNull List<SignalThresholdInfo> signalThresholdInfos,
            boolean isReportingRequestedWhileIdle,
            boolean isSystemThresholdReportingRequestedWhileIdle) {
        validate(signalThresholdInfos);

        mSignalThresholdInfos = signalThresholdInfos;
        mIsReportingRequestedWhileIdle = isReportingRequestedWhileIdle;
        mIsSystemThresholdReportingRequestedWhileIdle =
                isSystemThresholdReportingRequestedWhileIdle;
        mLiveToken = new Binder();
    }

    /**
     * Builder class to create {@link SignalStrengthUpdateRequest} object.
     */
    public static final class Builder {
        private List<SignalThresholdInfo> mSignalThresholdInfos = null;
        private boolean mIsReportingRequestedWhileIdle = false;
        private boolean mIsSystemThresholdReportingRequestedWhileIdle = false;

        /**
         * Set the collection of SignalThresholdInfo for the builder object
         *
         * @param signalThresholdInfos the collection of SignalThresholdInfo
         *
         * @return the builder to facilitate the chaining
         */
        public @NonNull Builder setSignalThresholdInfos(
                @NonNull Collection<SignalThresholdInfo> signalThresholdInfos) {
            Objects.requireNonNull(signalThresholdInfos,
                    "SignalThresholdInfo collection must not be null");
            for (SignalThresholdInfo info : signalThresholdInfos) {
                Objects.requireNonNull(info,
                        "SignalThresholdInfo in the collection must not be null");
            }

            mSignalThresholdInfos = new ArrayList<>(signalThresholdInfos);
            // Sort the collection with RAN ascending order, make the ordering not matter for equals
            mSignalThresholdInfos.sort(
                    Comparator.comparingInt(SignalThresholdInfo::getRadioAccessNetworkType));
            return this;
        }

        /**
         * Set the builder object if require reporting on thresholds in this request when device is
         * idle.
         *
         * @param isReportingRequestedWhileIdle true if request reporting when device is idle
         *
         * @return the builder to facilitate the chaining
         */
        public @NonNull Builder setReportingRequestedWhileIdle(
                boolean isReportingRequestedWhileIdle) {
            mIsReportingRequestedWhileIdle = isReportingRequestedWhileIdle;
            return this;
        }

        /**
         * Set the builder object if require reporting on the system thresholds when device is idle.
         *
         * This can only used by the system caller.
         *
         * @param isSystemThresholdReportingRequestedWhileIdle true if request reporting on the
         *                                                     system thresholds when device is idle
         * @return the builder to facilitate the chaining
         * @hide
         */
        public @NonNull Builder setSystemThresholdReportingRequestedWhileIdle(
                boolean isSystemThresholdReportingRequestedWhileIdle) {
            mIsSystemThresholdReportingRequestedWhileIdle =
                    isSystemThresholdReportingRequestedWhileIdle;
            return this;
        }

        /**
         * Build a {@link SignalStrengthUpdateRequest} object.
         *
         * @return the SignalStrengthUpdateRequest object
         *
         * @throws IllegalArgumentException if the SignalThresholdInfo collection is empty size, the
         * radio access network type in the collection is not unique
         */
        public @NonNull SignalStrengthUpdateRequest build() {
            return new SignalStrengthUpdateRequest(mSignalThresholdInfos,
                    mIsReportingRequestedWhileIdle, mIsSystemThresholdReportingRequestedWhileIdle);
        }
    }

    private SignalStrengthUpdateRequest(Parcel in) {
        mSignalThresholdInfos = in.createTypedArrayList(SignalThresholdInfo.CREATOR);
        mIsReportingRequestedWhileIdle = in.readBoolean();
        mIsSystemThresholdReportingRequestedWhileIdle = in.readBoolean();
        mLiveToken = in.readStrongBinder();
    }

    /**
     * Get the collection of SignalThresholdInfo in the request.
     *
     * @return the collection of SignalThresholdInfo
     */
    @NonNull
    public Collection<SignalThresholdInfo> getSignalThresholdInfos() {
        return Collections.unmodifiableList(mSignalThresholdInfos);
    }

    /**
     * Get whether reporting is requested for the threshold in the request while device is idle.
     *
     * @return true if reporting requested while device is idle
     */
    public boolean isReportingRequestedWhileIdle() {
        return mIsReportingRequestedWhileIdle;
    }

    /**
     * @return true if reporting requested for system thresholds while device is idle
     *
     * @hide
     */
    public boolean isSystemThresholdReportingRequestedWhileIdle() {
        return mIsSystemThresholdReportingRequestedWhileIdle;
    }

    /**
     * @return the live token of the request
     *
     * @hide
     */
    public @NonNull IBinder getLiveToken() {
        return mLiveToken;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mSignalThresholdInfos);
        dest.writeBoolean(mIsReportingRequestedWhileIdle);
        dest.writeBoolean(mIsSystemThresholdReportingRequestedWhileIdle);
        dest.writeStrongBinder(mLiveToken);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;

        if (!(other instanceof SignalStrengthUpdateRequest)) {
            return false;
        }

        SignalStrengthUpdateRequest request = (SignalStrengthUpdateRequest) other;
        return mSignalThresholdInfos.equals(request.mSignalThresholdInfos)
                && mIsReportingRequestedWhileIdle == request.mIsReportingRequestedWhileIdle
                && mIsSystemThresholdReportingRequestedWhileIdle
                    == request.mIsSystemThresholdReportingRequestedWhileIdle;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSignalThresholdInfos, mIsReportingRequestedWhileIdle,
                mIsSystemThresholdReportingRequestedWhileIdle);
    }

    public static final @NonNull Parcelable.Creator<SignalStrengthUpdateRequest> CREATOR =
            new Parcelable.Creator<SignalStrengthUpdateRequest>() {
                @Override
                public SignalStrengthUpdateRequest createFromParcel(Parcel source) {
                    return new SignalStrengthUpdateRequest(source);
                }

                @Override
                public SignalStrengthUpdateRequest[] newArray(int size) {
                    return new SignalStrengthUpdateRequest[size];
                }
            };

    @Override
    public String toString() {
        return new StringBuilder("SignalStrengthUpdateRequest{")
                .append("mSignalThresholdInfos=")
                .append(mSignalThresholdInfos)
                .append(" mIsReportingRequestedWhileIdle=")
                .append(mIsReportingRequestedWhileIdle)
                .append(" mIsSystemThresholdReportingRequestedWhileIdle=")
                .append(mIsSystemThresholdReportingRequestedWhileIdle)
                .append(" mLiveToken")
                .append(mLiveToken)
                .append("}").toString();
    }

    /**
     * Throw IAE when the RAN in the collection is not unique.
     */
    private static void validate(Collection<SignalThresholdInfo> infos) {
        Set<Integer> uniqueRan = new HashSet<>(infos.size());
        for (SignalThresholdInfo info : infos) {
            final int ran = info.getRadioAccessNetworkType();
            if (!uniqueRan.add(ran)) {
                throw new IllegalArgumentException("RAN: " + ran + " is not unique");
            }
        }
    }
}
