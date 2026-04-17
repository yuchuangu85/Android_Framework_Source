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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.Uri;
import android.os.RemoteException;

import com.android.internal.telephony.IPhoneNumber;

import java.util.List;

/**
 * PhoneNumberManager provides APIs for parsing phone numbers from various sources, such as URIs.
 */
@FlaggedApi(com.android.telephony.flags.Flags.FLAG_PHONE_NUMBER_PARSING_API)
public class PhoneNumberManager {
    /**
     * Creates a new instance of {@link PhoneNumberManager}.
     *
     * <p>Normally you should not create an instance of this class directly.
     * <p>Use {@link Context#getSystemService(Class) Context.getSystemService(
     * PhoneNumberManager.class)} to get a {@code PhoneNumberManager} instance.
     *
     * @param unused The context to be used for creating the PhoneNumberManager.
     *
     * @hide
     */
    public PhoneNumberManager(@NonNull Context unused) {}


    /**
     * Helper method to get the IPhoneNumber binder.
     *
     * @return The IPhoneNumber binder.
     */
    private static IPhoneNumber getIPhoneNumber() {
        return IPhoneNumber.Stub.asInterface(
            TelephonyFrameworkInitializer
                .getTelephonyServiceManager()
                .getPhoneNumberServiceRegisterer()
                .get());
    }

    /**
     * Parses the associated URIs into a {@link ParsedPhoneNumber} object.
     *
     * <p> This method attempts to parse the provided list of URIs into a valid phone number.
     * The order of the URIs in the list is significant; it is recommended to prioritize URIs
     * that are more likely to contain a valid phone number by placing them at the beginning of
     * the list. The method will iterate through the list and return the first URI that can be
     * successfully parsed into a valid phone number.
     *
     * @param associatedUris The list of URIs to be parsed. The order of the URIs in the list
     *                       matters. The method iterates through the list and stops at the first
     *                       URI that can be parsed into a valid phone number. It is recommended to
     *                       place the URIs that are most likely to contain a valid phone number at
     *                       the beginning of the list.
     * @param countryIso The country ISO code to be used for parsing.
     *
     * @return A {@link ParsedPhoneNumber} object representing the first successfully parsed
     * phone number from the provided list or an error if no numbers could be parsed.
     */
    @NonNull
    public ParsedPhoneNumber parsePhoneNumber(@Nullable List<Uri> associatedUris,
                @NonNull String countryIso) {
        try {
            return getIPhoneNumber().parsePhoneNumber(associatedUris, countryIso);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }
}
