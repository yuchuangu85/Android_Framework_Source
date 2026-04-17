/*
 * Copyright 2025 The Android Open Source Project
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
package android.app.timezonedetector;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A data class that captures information about a telephony signal. This information can be used by
 * the device's time zone detector to infer the device's time zone.
 *
 * @hide
 */
public final class TelephonySignal implements Parcelable {
    /** The Mobile Country Code (MCC) of the registered network. */
    private final String mMcc;

    /** The Mobile Network Code (MNC) of the registered network. Can be {@code null}. */
    @Nullable private final String mMnc;

    /** The default ISO 3166-1 alpha-2 country code derived from the network information. */
    private final String mDefaultCountryIsoCode;

    /** A set of all possible ISO 3166-1 alpha-2 country codes associated with the network. */
    private final Set<String> mCountryIsoCodes;

    /**
     * The NITZ (Network Identity and Timezone) signal information received, if available. Can be
     * {@code null}.
     */
    @Nullable private final NitzSignal mNitzSignal;

    /**
     * Creates a new {@link TelephonySignal} instance.
     *
     * @param mcc The Mobile Country Code (MCC) of the registered network.
     * @param mnc The Mobile Network Code (MNC) of the registered network. Can be {@code null}.
     * @param defaultCountryIsoCode The default ISO 3166-1 alpha-2 country code.
     * @param countryIsoCodes A set of all possible ISO 3166-1 alpha-2 country codes.
     * @param nitzSignal The NITZ signal information, or {@code null}.
     */
    public TelephonySignal(
            String mcc,
            @Nullable String mnc,
            String defaultCountryIsoCode,
            Set<String> countryIsoCodes,
            @Nullable NitzSignal nitzSignal) {
        this.mMcc = Objects.requireNonNull(mcc);
        this.mMnc = mnc;
        this.mDefaultCountryIsoCode = Objects.requireNonNull(defaultCountryIsoCode);
        this.mCountryIsoCodes = Set.copyOf(Objects.requireNonNull(countryIsoCodes));
        this.mNitzSignal = nitzSignal;
    }

    /** Returns the Mobile Country Code (MCC). */
    @NonNull
    public String getMcc() {
        return mMcc;
    }

    /** Returns the Mobile Network Code (MNC), or {@code null}. */
    @Nullable
    public String getMnc() {
        return mMnc;
    }

    /** Returns the default country ISO code. */
    @NonNull
    public String getDefaultCountryIsoCode() {
        return mDefaultCountryIsoCode;
    }

    /** Returns an unmodifiable set of associated country ISO codes. */
    @NonNull
    public Set<String> getCountryIsoCodes() {
        return mCountryIsoCodes;
    }

    /** Returns the NITZ signal information, or {@code null}. */
    @Nullable
    public NitzSignal getNitzSignal() {
        return mNitzSignal;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other instanceof TelephonySignal that) {
            return Objects.equals(mMcc, that.mMcc)
                    && Objects.equals(mMnc, that.mMnc)
                    && Objects.equals(mDefaultCountryIsoCode, that.mDefaultCountryIsoCode)
                    && Objects.equals(mCountryIsoCodes, that.mCountryIsoCodes)
                    && Objects.equals(mNitzSignal, that.mNitzSignal);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMcc, mMnc, mDefaultCountryIsoCode, mCountryIsoCodes, mNitzSignal);
    }

    @Override
    public String toString() {
        return "TelephonySignal{"
                + "mcc='"
                + mMcc
                + '\''
                + ", mnc='"
                + mMnc
                + '\''
                + ", defaultCountryIsoCode='"
                + mDefaultCountryIsoCode
                + '\''
                + ", countryIsoCodes="
                + mCountryIsoCodes
                + ", mNitzSignal="
                + mNitzSignal
                + '}';
    }

    /** Implement the {@link Parcelable} interface. */
    @Override
    public int describeContents() {
        return 0; // No special object types
    }

    /**
     * Writes the object's data to the parcel.
     *
     * @param dest The parcel to which the object's data is written.
     * @param flags Additional flags about how the object should be written. May be 0 or {@link
     *     #PARCELABLE_WRITE_RETURN_VALUE}.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString8(mMcc);
        dest.writeString8(mMnc);
        dest.writeString8(mDefaultCountryIsoCode);
        // Convert Set to List for parceling
        dest.writeStringList(new ArrayList<>(mCountryIsoCodes));
        dest.writeParcelable(mNitzSignal, flags);
    }

    /** Helper to create {@link TelephonySignal} objects from a {@link Parcel}. */
    public static final Creator<TelephonySignal> CREATOR =
            new Creator<>() {
                @Override
                public TelephonySignal createFromParcel(Parcel in) {
                    String mcc = in.readString8();
                    String mnc = in.readString8();
                    String defaultCountryIsoCode = in.readString8();
                    // Read List and convert back to Set
                    List<String> tempCountryIsoCodes = new ArrayList<>();
                    in.readStringList(tempCountryIsoCodes);
                    Set<String> countryIsoCodes = new HashSet<>(tempCountryIsoCodes);
                    NitzSignal nitzSignal =
                            in.readParcelable(NitzSignal.class.getClassLoader(), NitzSignal.class);
                    return new TelephonySignal(
                            mcc, mnc, defaultCountryIsoCode, countryIsoCodes, nitzSignal);
                }

                @Override
                public TelephonySignal[] newArray(int size) {
                    return new TelephonySignal[size];
                }
            };
}
