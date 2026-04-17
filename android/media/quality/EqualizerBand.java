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
import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.media.tv.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents a single band in an equalizer.
 *
 * <p>An equalizer is composed of multiple bands, each targeting a specific
 * frequency range in the audio spectrum. This parcelable defines the
 * properties of one such band.
 */
@FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW_C)
public final class EqualizerBand implements Parcelable {
    private final int mFrequencyHz;
    private final int mGainDb;
    private final float mQFactor;

    /**
     * Constructs an EqualizerBand.
     *
     * @param frequencyHz The center frequency of this band in Hertz (Hz).
     * @param gainDb The gain (level) for this band in decibels (dB).
     * @param qFactor The Quality Factor (Q).
     * @throws IllegalArgumentException if any parameter is out of its supported range.
     */
    public EqualizerBand(@IntRange(from = 1) int frequencyHz,
                         int gainDb,
                         @FloatRange(from = 0.0, fromInclusive = false) float qFactor) {
        if (frequencyHz < 1) {
            throw new IllegalArgumentException("frequencyHz must be at least 1Hz");
        }
        if (qFactor <= 0.0f) {
            throw new IllegalArgumentException("qFactor must be greater than 0");
        }
        mFrequencyHz = frequencyHz;
        mGainDb = gainDb;
        mQFactor = qFactor;
    }

    private EqualizerBand(@NonNull Parcel in) {
        mFrequencyHz = in.readInt();
        mGainDb = in.readInt();
        mQFactor = in.readFloat();
    }

    /**
     * Gets the center frequency of the band in Hertz.
     */
    @IntRange(from = 1)
    public int getFrequencyHz() {
        return mFrequencyHz;
    }

    /**
     * Gets the gain for the band in decibels (dB).
     * The range is determined by hardware capabilities. (e.g., -50 to 50 or -100 to 100)
     * <p>
     * Negative means the signal in this frequency band is reduced, so the output is quieter than
     * the input. (This is used to remove unwanted frequencies like "mud" or "hiss")
     * <p>
     * Positive means the signal in this frequency band is amplified, so the output is louder than
     * the input.
     * <p>
     * 0 means the signal is unchanged and this is the default state of an equalizer.
     */
    public int getGainDb() {
        return mGainDb;
    }

    /**
     * Gets the Quality Factor (Q).
     * <p>The Q factor controls the bandwidth (width) of the frequency range affected by this filter
     * It is inversely proportional to bandwidth:
     * <ul>
     * <li><b>High Q (e.g., > 2.0):</b> Creates a narrow, sharp curve. Used for precise adjustments
     * (e.g., removing a specific resonant frequency).</li>
     * <li><b>Low Q (e.g., < 0.7):</b> Creates a wide, broad curve. Used for general tonal shaping
     * (e.g., "boosting the bass").</li>
     * </ul>
     */
    @FloatRange(from = 0.0, fromInclusive = false)
    public float getQFactor() {
        return mQFactor;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mFrequencyHz);
        dest.writeInt(mGainDb);
        dest.writeFloat(mQFactor);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<EqualizerBand> CREATOR = new Creator<EqualizerBand>() {
        @Override
        public EqualizerBand createFromParcel(Parcel in) {
            return new EqualizerBand(in);
        }

        @Override
        public EqualizerBand[] newArray(int size) {
            return new EqualizerBand[size];
        }
    };
}
