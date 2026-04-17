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
import java.util.List;

/**
 * A class to represent the static capabilities of an equalizer.
 */
@FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
public final class EqualizerCapabilities implements Parcelable {
    private final int mMinLevelDb;
    private final int mMaxLevelDb;
    private final List<Integer> mSupportedFrequenciesHz;
    private final boolean mHasAdjustableQ;

    /**
     * Constructs an EqualizerCapabilities.
     *
     * @param minLevelDb The minimum supported gain level in decibels (dB).
     * @param maxLevelDb The maximum supported gain level in decibels (dB).
     * @param supportedFrequenciesHz An array of all supported band center frequencies in Hertz.
     * @param hasAdjustableQ Indicates whether the equalizer supports an adjustable Q factor.
     *
     * @hide
     */
    public EqualizerCapabilities(int minLevelDb, int maxLevelDb,
            @NonNull List<Integer> supportedFrequenciesHz, boolean hasAdjustableQ) {
        if (minLevelDb > maxLevelDb) {
            throw new IllegalArgumentException("minLevelDb (" + minLevelDb
                    + ") must be less than or equal to maxLevelDb (" + maxLevelDb + ")");
        }
        mMinLevelDb = minLevelDb;
        mMaxLevelDb = maxLevelDb;
        mSupportedFrequenciesHz = List.copyOf(supportedFrequenciesHz);
        mHasAdjustableQ = hasAdjustableQ;
    }

    private EqualizerCapabilities(@NonNull Parcel in) {
        mMinLevelDb = in.readInt();
        mMaxLevelDb = in.readInt();
        List<Integer> frequencies = new ArrayList<>();
        in.readList(frequencies, Integer.class.getClassLoader(), Integer.class);
        mSupportedFrequenciesHz = List.copyOf(frequencies);
        mHasAdjustableQ = in.readBoolean();
    }

    /**
     * Gets the minimum supported gain level in decibels (dB).
     */
    public int getMinLevelDb() {
        return mMinLevelDb;
    }

    /**
     * Gets the maximum supported gain level in decibels (dB).
     */
    public int getMaxLevelDb() {
        return mMaxLevelDb;
    }

    /**
     * Gets an array of all supported band center frequencies in Hertz (Hz).
     */
    @NonNull
    public List<Integer> getSupportedFrequenciesHz() {
        return mSupportedFrequenciesHz;
    }

    /**
     * Returns true if the equalizer supports an adjustable Q factor.
     */
    public boolean hasAdjustableQ() {
        return mHasAdjustableQ;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mMinLevelDb);
        dest.writeInt(mMaxLevelDb);
        dest.writeList(mSupportedFrequenciesHz);
        dest.writeBoolean(mHasAdjustableQ);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<EqualizerCapabilities> CREATOR =
            new Creator<EqualizerCapabilities>() {
                @Override
                public EqualizerCapabilities createFromParcel(Parcel in) {
                    return new EqualizerCapabilities(in);
                }

                @Override
                public EqualizerCapabilities[] newArray(int size) {
                    return new EqualizerCapabilities[size];
                }
            };
}
