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
package android.telephony;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Handles the results from PhoneNumberManager by providing Phone number, error code, and is valid
 * number.
 */
@FlaggedApi(com.android.telephony.flags.Flags.FLAG_PHONE_NUMBER_PARSING_API)
public final class ParsedPhoneNumber implements Parcelable {
    @NonNull
    private final String mPhoneNumber;
    @ErrorType
    private final int mErrorCode;
    private final boolean mIsValidNumber;

    @NonNull
    public static final Creator<ParsedPhoneNumber> CREATOR =
            new Creator<>() {
                @Override
                public ParsedPhoneNumber createFromParcel(Parcel in) {
                    return new ParsedPhoneNumber(in.readString(), in.readInt(),
                            in.readBoolean());
                }

                @Override
                public ParsedPhoneNumber[] newArray(int size) {
                    return new ParsedPhoneNumber[size];
                }
            };

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mPhoneNumber);
        dest.writeInt(mErrorCode);
        dest.writeBoolean(mIsValidNumber);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ParsedPhoneNumber that)) return false;
        return Objects.equals(mPhoneNumber, that.mPhoneNumber)
            && Objects.equals(mErrorCode, that.mErrorCode)
            && Objects.equals(mIsValidNumber, that.mIsValidNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPhoneNumber, mErrorCode, mIsValidNumber);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "ParsedPhoneNumber{"
            + "mPhoneNumber="
            + anonymizePhoneNumber(mPhoneNumber)
            + ", mErrorCode="
            + mErrorCode
            + ", mIsValidNumber="
            + mIsValidNumber
            + '}';
    }

    /**
     * @hide
     */
    private static String anonymizePhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            return phoneNumber;
        }

        StringBuilder anonymized = new StringBuilder();
        for (int i = 0; i < phoneNumber.length(); i++) {
            char c = phoneNumber.charAt(i);
            if (Character.isDigit(c)) {
                anonymized.append("X");
            } else {
                anonymized.append(c);
            }
        }
        return anonymized.toString();
    }

    /**
     * Parsed phone number.
     *
     * @throws IllegalStateException if isValidPhoneNumber is false.
     */
    @NonNull
    public String getParsedPhoneNumber() {
        if (!mIsValidNumber) {
            throw new IllegalStateException("Cannot retrieve phone number if not valid");
        }
        return mPhoneNumber;
    }

    /**
     * Whether or not we were able to extract a valid phone number
     */
    public boolean isValidPhoneNumber() {
        return mIsValidNumber;
    }

    /**
     * If no phone number was able to be extracted this will report the
     * failure reason to the user.
     */
    @ErrorType
    public int getErrorCode() {
        return mErrorCode;
    }

    /**
     * @param phoneNumber the phone number extracted. Empty if we weren't successful in parsing.
     * @param errorCode ErrorType seen when trying to extract phone number.
     * @param isValidPhoneNumber whether or not we succeeded in retrieving the number.
     */
    public ParsedPhoneNumber(@NonNull String phoneNumber, @ErrorType int errorCode,
            boolean isValidPhoneNumber) {
        this.mPhoneNumber = phoneNumber;
        this.mErrorCode = errorCode;
        this.mIsValidNumber = isValidPhoneNumber;
    }

    /**
     * Failed to extract phone number for unknown reason.
     */
    public static final int ERROR_TYPE_UNKNOWN = -1;

    /**
     * No error seen.
     */
    public static final int ERROR_TYPE_NONE = 0;

    /**
     * The phone number failed to be validated.
     */
    public static final int ERROR_TYPE_FAILED_TO_VALIDATE_EXTRACTED_PHONE_NUMER = 1;

    /**
     * There was an error when trying to extract the phone number.
     */
    public static final int ERROR_TYPE_NUMBER_PARSE_EXCEPTION = 2;

    /**
     * Defines error types encountered during phone number parsing and validation.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
        ERROR_TYPE_UNKNOWN,
        ERROR_TYPE_NONE,
        ERROR_TYPE_FAILED_TO_VALIDATE_EXTRACTED_PHONE_NUMER,
        ERROR_TYPE_NUMBER_PARSE_EXCEPTION,
    })
    private @interface ErrorType { }
}
