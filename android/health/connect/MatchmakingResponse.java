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

import static com.android.healthfitness.flags.Flags.FLAG_MATCHMAKING;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.OutcomeReceiver;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.concurrent.Executor;

/**
 * A response for {@link HealthConnectManager#isMatchmakingPossible(MatchmakingRequest, Executor,
 * OutcomeReceiver)}.
 */
@FlaggedApi(FLAG_MATCHMAKING)
public final class MatchmakingResponse implements Parcelable {
    private final boolean mIsMatchmakingPossible;

    /**
     * Creates an instance of {@link MatchmakingResponse}.
     *
     * @param isMatchmakingPossible {@code true} if matchmaking is possible, {@code false}
     *     otherwise.
     */
    private MatchmakingResponse(boolean isMatchmakingPossible) {
        mIsMatchmakingPossible = isMatchmakingPossible;
    }

    /** Returns {@code true} if matchmaking is possible, {@code false} otherwise. */
    public boolean isMatchmakingPossible() {
        return mIsMatchmakingPossible;
    }

    private MatchmakingResponse(Parcel in) {
        this(in.readInt() != 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mIsMatchmakingPossible ? 1 : 0);
    }

    @NonNull
    public static final Creator<MatchmakingResponse> CREATOR =
            new Creator<MatchmakingResponse>() {
                @Override
                public MatchmakingResponse createFromParcel(Parcel in) {
                    return new MatchmakingResponse(in);
                }

                @Override
                public MatchmakingResponse[] newArray(int size) {
                    return new MatchmakingResponse[size];
                }
            };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MatchmakingResponse that = (MatchmakingResponse) o;
        return mIsMatchmakingPossible == that.mIsMatchmakingPossible;
    }

    @Override
    public int hashCode() {
        return Boolean.hashCode(mIsMatchmakingPossible);
    }

    /** Builder for {@link MatchmakingResponse}. */
    public static final class Builder {
        private boolean mIsMatchmakingPossible;

        /**
         * Creates an instance of {@link Builder}.
         *
         * @param isMatchmakingPossible {@code true} if matchmaking is possible, {@code false}
         *     otherwise.
         */
        public Builder(boolean isMatchmakingPossible) {
            mIsMatchmakingPossible = isMatchmakingPossible;
        }

        /** Builds the {@link MatchmakingResponse} instance. */
        @NonNull
        public MatchmakingResponse build() {
            return new MatchmakingResponse(mIsMatchmakingPossible);
        }

        /** Sets whether matchmaking is possible. */
        @NonNull
        public Builder setMatchmakingPossible(boolean isMatchmakingPossible) {
            mIsMatchmakingPossible = isMatchmakingPossible;
            return this;
        }
    }
}
