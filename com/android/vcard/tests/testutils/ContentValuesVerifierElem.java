/*
 * Copyright (C) 2009 The Android Open Source Project
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
package com.android.vcard.tests.testutils;

import android.content.ContentValues;
import android.provider.ContactsContract.Data;
import android.test.AndroidTestCase;

import com.android.vcard.VCardEntry;
import com.android.vcard.VCardEntryCommitter;
import com.android.vcard.VCardEntryHandler;

public class ContentValuesVerifierElem {
    private final ImportTestResolver mResolver;
    private final VCardEntryHandler mHandler;

    public ContentValuesVerifierElem(AndroidTestCase androidTestCase) {
        mResolver = new ImportTestResolver(androidTestCase);
        mHandler = new VCardEntryCommitter(mResolver);
    }

    public ContentValuesBuilder addExpected(String mimeType) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(Data.MIMETYPE, mimeType);
        mResolver.addExpectedContentValues(contentValues);
        return new ContentValuesBuilder(contentValues);
    }

    public void verifyResolver() {
        mResolver.verify();
    }

    public void onParsingStart() {
        mHandler.onStart();
    }

    public void onEntryCreated(VCardEntry entry) {
        mHandler.onEntryCreated(entry);
    }

    public void onParsingEnd() {
        mHandler.onEnd();
    }
}
