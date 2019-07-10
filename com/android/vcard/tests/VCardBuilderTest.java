/*
 * Copyright (C) 2012 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.vcard.tests;

import android.content.ContentValues;
import android.provider.ContactsContract;

import com.android.vcard.VCardBuilder;
import com.android.vcard.VCardConfig;
import com.google.android.collect.Lists;

import junit.framework.TestCase;

import java.util.ArrayList;

/**
 * Unit test for VCardBuilder.
 */
public class VCardBuilderTest extends TestCase {

    public void testVCardNameFieldFromDisplayName() {
        final ArrayList<ContentValues> contentList = Lists.newArrayList();

        final ContentValues values = new ContentValues();
        values.put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "ने");
        contentList.add(values);

        final VCardBuilder builder = new VCardBuilder(VCardConfig.VCARD_TYPE_DEFAULT);
        builder.appendNameProperties(contentList);
        final String actual = builder.toString();

        final String expectedCommon = ";CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:" +
                "=E0=A4=A8=E0=A5=87";

        final String expectedName = "N" + expectedCommon + ";;;;";
        final String expectedFullName = "FN" + expectedCommon;

        assertTrue("Actual value:\n" + actual + " expected to contain\n" + expectedName +
                "\nbut does not.", actual.contains(expectedName));
        assertTrue("Actual value:\n" + actual + " expected to contain\n" + expectedFullName +
                "\nbut does not.", actual.contains(expectedFullName));
    }
}

