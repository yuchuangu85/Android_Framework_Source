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
package com.android.internal.telephony;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.Uri;
import android.os.TelephonyServiceManager;
import android.os.TelephonyServiceManager.ServiceRegisterer;
import android.telephony.ParsedPhoneNumber;
import android.telephony.TelephonyFrameworkInitializer;

import com.android.i18n.phonenumbers.NumberParseException;
import com.android.i18n.phonenumbers.PhoneNumberUtil;
import com.android.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.android.i18n.phonenumbers.Phonenumber.PhoneNumber;

import java.util.List;
import java.util.Locale;

/**
 * Provides a system service for parsing and extracting phone numbers from IMS registration
 * information.
 *
 * @hide
 */
public final class PhoneNumberManagerService extends IPhoneNumber.Stub {
    private static final String TAG = "PhoneNumberManagerService";
    private static final String ANONYMIZATION_CHAR = "X";

    private Ts43PhoneNumberController mTs43PhoneNumberController;

    /**
     * Constructor for PhoneNumberManagerService.
     * This delegates to the context-aware constructor with a null context.
     *
     * @hide
     */
    public PhoneNumberManagerService() {
        this(null);
    }

    /**
     * Constructor for PhoneNumberManagerService.
     * This registers the service with the TelephonyFrameworkInitializer.
     *
     * @hide
     */
    public PhoneNumberManagerService(Context context) {
        logd("Attempting to register PhoneNumberManagerService.");
        TelephonyServiceManager telephonyServiceManager = TelephonyFrameworkInitializer
                .getTelephonyServiceManager();
        if (telephonyServiceManager == null) {
            loge("TelephonyServiceManager is null. Skipping registration.");
            return;
        }

        ServiceRegisterer phoneNumberServiceRegisterer =
                telephonyServiceManager.getPhoneNumberServiceRegisterer();
        if (phoneNumberServiceRegisterer.get() == null) {
            try {
                phoneNumberServiceRegisterer.publishBinderService(this);
                logd("PhoneNumberManagerService registered successfully.");
            } catch (Throwable t) {
                loge("Failed to register PhoneNumberManagerService: " + t);
            }
        } else {
            logd("PhoneNumberManagerService already registered. Skipping re-registration.");
        }
        if (context != null) {
            logd("PhoneNumberManagerService creating and initializing with context.");
            mTs43PhoneNumberController = new Ts43PhoneNumberController(context);
        }
    }

    /**
     * Returns the phone number parsed from the IMS registration info which
     * includes all of the raw IMS registration headers.
     *
     * @param associatedUris list of uris to parse which contain phone number.
     * @param countryIso country the uri belongs to.
     *
     * @return ParsePhoneNumber returns first valid parsed phone number or an error code
     * explaining what issue was seen.
     * @throws IllegalArgumentException if called with null associatedUris or
     * countryIso.
     *
     * @hide
     */
    @Override
    @NonNull
    public ParsedPhoneNumber parsePhoneNumber(@Nullable List<Uri> associatedUris,
            @Nullable String countryIso) {
        if (associatedUris == null || associatedUris.isEmpty()) {
            loge("IMS Registration header is invalid. Either null or empty.");
            throw new IllegalArgumentException(
                    "Unable to parse number as the associatedUris received is invalid.");
        }
        if (countryIso == null || countryIso.isEmpty()) {
            loge("Country code is invalid. Either null or empty.");
            throw new IllegalArgumentException(
                    "Unable to parse number as the countryIso received is invalid.");
        }

        return extractPhoneNumber(associatedUris, countryIso);
    }

    private ParsedPhoneNumber extractPhoneNumber(List<Uri> associatedUris, String countryIso) {
        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        int firstErrorType = ParsedPhoneNumber.ERROR_TYPE_NONE;

        for (Uri uri : associatedUris) {
            if (uri != null && uri.isOpaque()) {
                String phoneNumberCandidate = uri.getSchemeSpecificPart().split("@")[0];
                try {
                    // Converts countryIso to uppercase as required by libphonenumber.
                    PhoneNumber phoneNumber = util.parse(phoneNumberCandidate,
                            countryIso.toUpperCase(Locale.ENGLISH));

                    if (util.isValidNumber(phoneNumber)) {
                        // If a valid number is found, return it immediately.
                        return new ParsedPhoneNumber(
                                util.format(phoneNumber, PhoneNumberFormat.E164),
                                ParsedPhoneNumber.ERROR_TYPE_NONE, true);
                    } else {
                        logd("Failed to validate the following number: {"
                                + anonymizePhoneNumberSimple(phoneNumberCandidate)
                                + "} for country: {"
                                + countryIso + "}");
                        firstErrorType = updateFirstErrorType(firstErrorType,
                                ParsedPhoneNumber
                                        .ERROR_TYPE_FAILED_TO_VALIDATE_EXTRACTED_PHONE_NUMER);
                    }
                } catch (NumberParseException e) {
                    logd("NumberParseException for number: {"
                            + anonymizePhoneNumberSimple(phoneNumberCandidate) + "} - {"
                            + e.getMessage() + "}");
                    firstErrorType = updateFirstErrorType(firstErrorType,
                            ParsedPhoneNumber.ERROR_TYPE_NUMBER_PARSE_EXCEPTION);
                }
            }
            // TODO(b/434607712): add else statement that catches error if uri is null
            // and not opaque.
        }

        // If the loop completes and no valid phone number was found, return an empty string
        // with the first error encountered (or NONE if no specific error).
        return new ParsedPhoneNumber("", firstErrorType, false);
    }



    private static String anonymizePhoneNumberSimple(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            return phoneNumber;
        }

        StringBuilder anonymized = new StringBuilder();
        for (int i = 0; i < phoneNumber.length(); i++) {
            char c = phoneNumber.charAt(i);
            if (Character.isDigit(c)) {
                anonymized.append(ANONYMIZATION_CHAR);
            } else {
                anonymized.append(c);
            }
        }
        return anonymized.toString();
    }

    private int updateFirstErrorType(int currentFirstErrorType, int newErrorType) {
        if (currentFirstErrorType == ParsedPhoneNumber.ERROR_TYPE_NONE) {
            return newErrorType;
        }
        return currentFirstErrorType;
    }

    private void loge(String message) {
        Rlogger.e(TAG, message);
    }

    private void logd(String message) {
        Rlogger.d(TAG, message);
    }

}