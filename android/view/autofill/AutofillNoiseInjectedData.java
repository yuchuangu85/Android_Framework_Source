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

package android.view.autofill;

import static android.service.autofill.Flags.FLAG_STRING_REBUILD_API;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents the noise-injected version of a text field's content, generated using a differential
 * privacy algorithm. While the Autofill providers won't be able to recover the actual text from the
 * noise-injected data from one device, with uploads from sufficient number of devices, the Autofill
 * providers are able to rebuild the constant, non-private parts of the text to help them improve
 * Autofill detection's quality.
 *
 * <p>The noise injection process involves the following steps: 1. The original string is converted
 * to a byte array using UTF-8 Encoding. The actual length of the string is hidden by padding
 * with 0x0000 or truncating as needed. 2. Then for each bit in the byte array, random noise is
 * introduced by "coin flipping": first we flip a coin to decide whether to change this bit. The
 * probability is 50%. If we decide to change this bit, then we flip another coin to decide its new
 * value. The probability is: 50% to be 1, 50% to be 0. 3. To further enhance privacy, each device
 * may drop some of the bits after noise injection. {@link #getRetainedBitMask()} tells you which
 * bits are retained for the current payload.
 *
 * <p>With the coin flipping mechanism described above, each bit has 75% chance to report the real
 * value, and 25% chance to report the opposite value(e.g. real value is 1, reported value is 0).
 * This means if a bit remains the same across all devices(i.e. it's a part of a non-personalized
 * string such as "Country"), then with a large enough amount of reports collected from different
 * devices, then the ratio of real value among all reports should be close to 75%. In another word,
 * if we see close to 75% of the reports show 1 then the real value of this bit should be a constant
 * 1; if we see close to 75% of the reports show 0 then the real value of this bit should be a
 * constant 0; all other distributions indicate this bit is not same across different devices(hence
 * very likely to be personalized/private information), which should be discarded as noise.
 */
@FlaggedApi(FLAG_STRING_REBUILD_API)
public final class AutofillNoiseInjectedData implements Parcelable {

    private final byte mRetainedBitMask;
    private final @NonNull byte[] mNoiseInjectedPayload;

    /** @hide */
    public AutofillNoiseInjectedData(byte retainedBitMask, @NonNull byte[] noiseInjectedPayload) {
        mRetainedBitMask = retainedBitMask;
        mNoiseInjectedPayload = noiseInjectedPayload;
    }

    private AutofillNoiseInjectedData(Parcel in) {
        mRetainedBitMask = in.readByte();
        mNoiseInjectedPayload = in.createByteArray();
    }

    /**
     * Returns a bit mask indicating which bits were retained for *each byte* in the payload
     * returned by {@link #getNoiseInjectedPayload()}. Bits not retained are marked as 0 in the
     * returned payload. The same bit mask is applied to every byte in the payload.
     *
     * @return A byte where each bit corresponds to a bit position in a byte. If the n-th bit
     *     (0 <= n <= 7) is set, it means the n-th bit of each byte in the payload is retained.
     */
    @SuppressLint("NoByteOrShort")
    @FlaggedApi(FLAG_STRING_REBUILD_API)
    public byte getRetainedBitMask() {
        return mRetainedBitMask;
    }

    /**
     * Gets the noise-injected and bit-reduced data. The original text has been transformed by a
     * differential privacy algorithm, including random bit flips and removing some of the bits in
     * every byte({@link #getRetainedBitMask()} tells you which bits are retained).
     *
     * @return A byte array representing the obfuscated text.
     */
    @FlaggedApi(FLAG_STRING_REBUILD_API)
    @NonNull
    public byte[] getNoiseInjectedPayload() {
        return mNoiseInjectedPayload;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeByte(mRetainedBitMask);
        dest.writeByteArray(mNoiseInjectedPayload);
    }

    @NonNull
    public static final Creator<AutofillNoiseInjectedData> CREATOR =
            new Creator<AutofillNoiseInjectedData>() {
                @Override
                public AutofillNoiseInjectedData createFromParcel(Parcel in) {
                    return new AutofillNoiseInjectedData(in);
                }

                @Override
                public AutofillNoiseInjectedData[] newArray(int size) {
                    return new AutofillNoiseInjectedData[size];
                }
            };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AutofillNoiseInjectedData that = (AutofillNoiseInjectedData) o;
        return mRetainedBitMask == that.mRetainedBitMask
                && Arrays.equals(mNoiseInjectedPayload, that.mNoiseInjectedPayload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mRetainedBitMask);
        result = 31 * result + Arrays.hashCode(mNoiseInjectedPayload);
        return result;
    }
}
