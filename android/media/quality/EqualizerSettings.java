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

package android.media.quality;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.media.tv.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A class to represent the current settings of an equalizer.
 */
@FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
public final class EqualizerSettings implements Parcelable {
    private final List<EqualizerBand> mBands;

    /**
     * Builder for {@link EqualizerSettings}.
     */
    public static final class Builder {
        private List<EqualizerBand> mBands = new ArrayList<>();

        public Builder() {}

        /**
         * Adds a list of bands to the equalizer settings.
         *
         * @param bands A list of {@link EqualizerBand} objects to be added.
         * @return This builder instance.
         */
        @NonNull
        public Builder addBands(@NonNull List<EqualizerBand> bands) {
            mBands.addAll(Objects.requireNonNull(bands));
            return this;
        }

        /**
         * Builds and returns a new {@link EqualizerSettings} instance.
         *
         * @return A new {@link EqualizerSettings} instance.
         */
        @NonNull
        public EqualizerSettings build() {
            return new EqualizerSettings(mBands);
        }
    }

    private EqualizerSettings(@NonNull List<EqualizerBand> bands) {
        mBands = List.copyOf(bands);
    }

    private EqualizerSettings(@NonNull Parcel in) {
        mBands = Collections.unmodifiableList(
                Objects.requireNonNull(in.createTypedArrayList(EqualizerBand.CREATOR)));
    }

    @NonNull
    public List<EqualizerBand> getBands() {
        return mBands;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mBands);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<EqualizerSettings> CREATOR = new Creator<EqualizerSettings>() {
        @Override
        public EqualizerSettings createFromParcel(Parcel in) {
            return new EqualizerSettings(in);
        }

        @Override
        public EqualizerSettings[] newArray(int size) {
            return new EqualizerSettings[size];
        }
    };
}
