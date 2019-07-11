/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.bluetooth.client.pbap;

import com.android.vcard.VCardEntry;
import com.android.vcard.VCardEntry.EmailData;
import com.android.vcard.VCardEntry.NameData;
import com.android.vcard.VCardEntry.PhoneData;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Entry representation of folder listing
 */
public class BluetoothPbapCard {

    public final String handle;

    public final String N;
    public final String lastName;
    public final String firstName;
    public final String middleName;
    public final String prefix;
    public final String suffix;

    public BluetoothPbapCard(String handle, String name) {
        this.handle = handle;

        N = name;

        /*
         * format is as for vCard N field, so we have up to 5 tokens: LastName;
         * FirstName; MiddleName; Prefix; Suffix
         */
        String[] parsedName = name.split(";", 5);

        lastName = parsedName.length < 1 ? null : parsedName[0];
        firstName = parsedName.length < 2 ? null : parsedName[1];
        middleName = parsedName.length < 3 ? null : parsedName[2];
        prefix = parsedName.length < 4 ? null : parsedName[3];
        suffix = parsedName.length < 5 ? null : parsedName[4];
    }

    @Override
    public String toString() {
        JSONObject json = new JSONObject();

        try {
            json.put("handle", handle);
            json.put("N", N);
            json.put("lastName", lastName);
            json.put("firstName", firstName);
            json.put("middleName", middleName);
            json.put("prefix", prefix);
            json.put("suffix", suffix);
        } catch (JSONException e) {
            // do nothing
        }

        return json.toString();
    }

    static public String jsonifyVcardEntry(VCardEntry vcard) {
        JSONObject json = new JSONObject();

        try {
            NameData name = vcard.getNameData();
            json.put("formatted", name.getFormatted());
            json.put("family", name.getFamily());
            json.put("given", name.getGiven());
            json.put("middle", name.getMiddle());
            json.put("prefix", name.getPrefix());
            json.put("suffix", name.getSuffix());
        } catch (JSONException e) {
            // do nothing
        }

        try {
            JSONArray jsonPhones = new JSONArray();

            List<PhoneData> phones = vcard.getPhoneList();

            if (phones != null) {
                for (PhoneData phone : phones) {
                    JSONObject jsonPhone = new JSONObject();
                    jsonPhone.put("type", phone.getType());
                    jsonPhone.put("number", phone.getNumber());
                    jsonPhone.put("label", phone.getLabel());
                    jsonPhone.put("is_primary", phone.isPrimary());

                    jsonPhones.put(jsonPhone);
                }

                json.put("phones", jsonPhones);
            }
        } catch (JSONException e) {
            // do nothing
        }

        try {
            JSONArray jsonEmails = new JSONArray();

            List<EmailData> emails = vcard.getEmailList();

            if (emails != null) {
                for (EmailData email : emails) {
                    JSONObject jsonEmail = new JSONObject();
                    jsonEmail.put("type", email.getType());
                    jsonEmail.put("address", email.getAddress());
                    jsonEmail.put("label", email.getLabel());
                    jsonEmail.put("is_primary", email.isPrimary());

                    jsonEmails.put(jsonEmail);
                }

                json.put("emails", jsonEmails);
            }
        } catch (JSONException e) {
            // do nothing
        }

        return json.toString();
    }
}
