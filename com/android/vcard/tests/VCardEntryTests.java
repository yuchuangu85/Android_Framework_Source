/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.android.vcard.tests;

import com.android.vcard.VCardConfig;
import com.android.vcard.VCardConstants;
import com.android.vcard.VCardEntry;
import com.android.vcard.VCardEntry.AndroidCustomData;
import com.android.vcard.VCardEntry.AnniversaryData;
import com.android.vcard.VCardEntry.BirthdayData;
import com.android.vcard.VCardEntry.EmailData;
import com.android.vcard.VCardEntry.EntryElement;
import com.android.vcard.VCardEntry.EntryLabel;
import com.android.vcard.VCardEntry.ImData;
import com.android.vcard.VCardEntry.NameData;
import com.android.vcard.VCardEntry.NicknameData;
import com.android.vcard.VCardEntry.NoteData;
import com.android.vcard.VCardEntry.OrganizationData;
import com.android.vcard.VCardEntry.PhoneData;
import com.android.vcard.VCardEntry.PhotoData;
import com.android.vcard.VCardEntry.PostalData;
import com.android.vcard.VCardEntry.SipData;
import com.android.vcard.VCardEntryConstructor;
import com.android.vcard.VCardEntryHandler;
import com.android.vcard.VCardInterpreter;
import com.android.vcard.VCardProperty;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Im;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.SipAddress;
import android.test.AndroidTestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VCardEntryTests extends AndroidTestCase {
    private class MockVCardEntryHandler implements VCardEntryHandler {
        private List<VCardEntry> mEntries = new ArrayList<VCardEntry>();
        private boolean mOnStartCalled;
        private boolean mOnEndCalled;

        @Override
        public void onStart() {
            assertFalse(mOnStartCalled);
            mOnStartCalled = true;
        }

        @Override
        public void onEntryCreated(VCardEntry entry) {
            assertTrue(mOnStartCalled);
            assertFalse(mOnEndCalled);
            mEntries.add(entry);
        }

        @Override
        public void onEnd() {
            assertTrue(mOnStartCalled);
            assertFalse(mOnEndCalled);
            mOnEndCalled = true;
        }

        public List<VCardEntry> getEntries() {
            return mEntries;
        }
    }

    /**
     * Tests VCardEntry and related clasess can handle nested classes given
     * {@link VCardInterpreter} is called appropriately.
     *
     * This test manually calls VCardInterpreter's callback mechanism and checks
     * {@link VCardEntryConstructor} constructs {@link VCardEntry} per given calls.
     *
     * Intended vCard is as follows:
     * <code>
     * BEGIN:VCARD
     * N:test1
     * BEGIN:VCARD
     * N:test2
     * END:VCARD
     * TEL:1
     * END:VCARD
     * </code>
     */
    public void testNestHandling() {
        VCardEntryConstructor entryConstructor = new VCardEntryConstructor();
        MockVCardEntryHandler entryHandler = new MockVCardEntryHandler();
        entryConstructor.addEntryHandler(entryHandler);

        entryConstructor.onVCardStarted();
        entryConstructor.onEntryStarted();
        VCardProperty property = new VCardProperty();
        property.setName(VCardConstants.PROPERTY_N);
        property.setValues("test1");
        entryConstructor.onPropertyCreated(property);

        entryConstructor.onEntryStarted();  // begin nest
        property = new VCardProperty();
        property.setName(VCardConstants.PROPERTY_N);
        property.setValues("test2");
        entryConstructor.onPropertyCreated(property);
        entryConstructor.onEntryEnded();  // end nest

        property = new VCardProperty();
        property.setName(VCardConstants.PROPERTY_TEL);
        property.setValues("1");
        entryConstructor.onPropertyCreated(property);
        entryConstructor.onEntryEnded();
        entryConstructor.onVCardEnded();

        List<VCardEntry> entries = entryHandler.getEntries();
        assertEquals(2, entries.size());
        VCardEntry parent = entries.get(1);
        VCardEntry child = entries.get(0);
        assertEquals("test1", parent.getDisplayName());
        assertEquals("test2", child.getDisplayName());
        List<VCardEntry.PhoneData> phoneList = parent.getPhoneList();
        assertNotNull(phoneList);
        assertEquals(1, phoneList.size());
        assertEquals("1", phoneList.get(0).getNumber());
    }

    private class MockEntryElementIterator implements VCardEntry.EntryElementIterator {
        private boolean mStartCalled;
        private boolean mEndCalled;
        private EntryLabel mLabel;
        private final Map<EntryLabel, EntryElement> mExpectedElements =
                new HashMap<EntryLabel, EntryElement>();

        public void addExpectedElement(EntryElement elem) {
            mExpectedElements.put(elem.getEntryLabel(), elem);
        }

        @Override
        public void onIterationStarted() {
            assertFalse(mStartCalled);
            assertFalse(mEndCalled);
            assertNull(mLabel);
            mStartCalled = true;
        }

        @Override
        public void onIterationEnded() {
            assertTrue(mStartCalled);
            assertFalse(mEndCalled);
            assertNull(mLabel);
            assertTrue("Expected Elements remaining: " +
                    Arrays.toString(mExpectedElements.values().toArray()),
                    mExpectedElements.isEmpty());
        }

        @Override
        public void onElementGroupStarted(EntryLabel label) {
            assertTrue(mStartCalled);
            assertFalse(mEndCalled);
            assertNull(mLabel);
            mLabel = label;
        }

        @Override
        public void onElementGroupEnded() {
            assertTrue(mStartCalled);
            assertFalse(mEndCalled);
            assertNotNull(mLabel);
            mLabel = null;
        }

        @Override
        public boolean onElement(EntryElement elem) {
            EntryElement expectedElem = mExpectedElements.remove(elem.getEntryLabel());
            assertNotNull("Unexpected elem: " + elem.toString(), expectedElem);
            assertEquals(expectedElem, elem);
            return true;
        }
    }

    /**
     * Tests every element in VCardEntry is iterated by
     * {@link VCardEntry#iterateAllData(com.android.vcard.VCardEntry.EntryElementIterator)}.
     */
    public void testEntryElementIterator() {
        VCardEntry entry = new VCardEntry();
        MockEntryElementIterator iterator = new MockEntryElementIterator();

        VCardProperty property = new VCardProperty();
        property.setName("N");
        property.setValues("family", "given", "middle", "prefix", "suffix");
        entry.addProperty(property);
        NameData nameData = new NameData();
        nameData.setFamily("family");
        nameData.setGiven("given");
        nameData.setMiddle("middle");
        nameData.setPrefix("prefix");
        nameData.setSuffix("suffix");
        iterator.addExpectedElement(nameData);

        property = new VCardProperty();
        property.setName("TEL");
        property.setParameter("TYPE", "HOME");
        property.setValues("1");
        entry.addProperty(property);
        PhoneData phoneData = new PhoneData("1", Phone.TYPE_HOME, null, false);
        iterator.addExpectedElement(phoneData);

        property = new VCardProperty();
        property.setName("EMAIL");
        property.setParameter("TYPE", "WORK");
        property.setValues("email");
        entry.addProperty(property);
        EmailData emailData = new EmailData("email", Email.TYPE_WORK, null, false);
        iterator.addExpectedElement(emailData);

        property = new VCardProperty();
        property.setName("ADR");
        property.setParameter("TYPE", "HOME");
        property.setValues(null, null, "street");
        entry.addProperty(property);
        PostalData postalData = new PostalData(null, null, "street", null, null, null,
                null, StructuredPostal.TYPE_HOME, null, false,
                VCardConfig.VCARD_TYPE_DEFAULT);
        iterator.addExpectedElement(postalData);

        property = new VCardProperty();
        property.setName("ORG");
        property.setValues("organization", "depertment");
        entry.addProperty(property);
        OrganizationData organizationData = new OrganizationData(
                "organization", "depertment", null, null, Organization.TYPE_WORK, false);
        iterator.addExpectedElement(organizationData);

        property = new VCardProperty();
        property.setName("X-GOOGLE-TALK");
        property.setParameter("TYPE", "WORK");
        property.setValues("googletalk");
        entry.addProperty(property);
        ImData imData = new ImData(
                Im.PROTOCOL_GOOGLE_TALK, null, "googletalk", Im.TYPE_WORK, false);
        iterator.addExpectedElement(imData);

        property = new VCardProperty();
        property.setName("PHOTO");
        property.setParameter("TYPE", "PNG");
        byte[] photoBytes = new byte[] {1};
        property.setByteValue(photoBytes);
        entry.addProperty(property);
        PhotoData photoData = new PhotoData("PNG", photoBytes, false);
        iterator.addExpectedElement(photoData);

        property = new VCardProperty();
        property.setName("X-SIP");
        property.setValues("sipdata");
        entry.addProperty(property);
        SipData sipData = new SipData("sip:sipdata", SipAddress.TYPE_OTHER, null, false);
        iterator.addExpectedElement(sipData);

        property = new VCardProperty();
        property.setName("NICKNAME");
        property.setValues("nickname");
        entry.addProperty(property);
        NicknameData nicknameData = new NicknameData("nickname");
        iterator.addExpectedElement(nicknameData);

        property = new VCardProperty();
        property.setName("NOTE");
        property.setValues("note");
        entry.addProperty(property);
        NoteData noteData = new NoteData("note");
        iterator.addExpectedElement(noteData);

        property = new VCardProperty();
        property.setName("BDAY");
        property.setValues("birthday");
        entry.addProperty(property);
        BirthdayData birthdayData = new BirthdayData("birthday");
        iterator.addExpectedElement(birthdayData);

        property = new VCardProperty();
        property.setName("ANNIVERSARY");
        property.setValues("anniversary");
        entry.addProperty(property);
        AnniversaryData anniversaryData = new AnniversaryData("anniversary");
        iterator.addExpectedElement(anniversaryData);

        property = new VCardProperty();
        property.setName("X-ANDROID-CUSTOM");
        property.setValues("mime;value");
        entry.addProperty(property);
        AndroidCustomData androidCustom = new AndroidCustomData("mime", Arrays.asList("value"));
        iterator.addExpectedElement(androidCustom);

        entry.iterateAllData(iterator);
    }

    public void testToString() {
        VCardEntry entry = new VCardEntry();
        VCardProperty property = new VCardProperty();
        property.setName("N");
        property.setValues("Family", "Given", "Middle", "Prefix", "Suffix");
        entry.addProperty(property);
        entry.consolidateFields();

        String result = entry.toString();
        assertNotNull(result);

        assertTrue(result.contains(String.valueOf(entry.hashCode())));
        assertTrue(result.contains(VCardEntry.EntryLabel.NAME.toString()));
        assertTrue(result.contains("Family"));
        assertTrue(result.contains("Given"));
        assertTrue(result.contains("Middle"));
        assertTrue(result.contains("Prefix"));
        assertTrue(result.contains("Suffix"));
    }

    /**
     * Tests that VCardEntry emits correct insert operation for name field.
     */
    public void testConstructInsertOperationsInsertName() {
        VCardEntry entry = new VCardEntry();
        VCardProperty property = new VCardProperty();
        property.setName("N");
        property.setValues("Family", "Given", "Middle", "Prefix", "Suffix");
        entry.addProperty(property);
        entry.consolidateFields();

        NameData nameData = entry.getNameData();
        assertEquals("Family", nameData.getFamily());
        assertEquals("Given", nameData.getGiven());
        assertEquals("Middle", nameData.getMiddle());
        assertEquals("Prefix", nameData.getPrefix());
        assertEquals("Suffix", nameData.getSuffix());

        ContentResolver resolver = getContext().getContentResolver();
        ArrayList<ContentProviderOperation> operationList =
                new ArrayList<ContentProviderOperation>();
        entry.constructInsertOperations(resolver, operationList);

        // Need too many details for testing these. Just check basics.
        // TODO: introduce nice-to-test mechanism here.
        assertEquals(2, operationList.size());
        assertEquals(ContentProviderOperation.TYPE_INSERT, operationList.get(0).getType());
        assertEquals(ContentProviderOperation.TYPE_INSERT, operationList.get(1).getType());
    }

    /**
     * Tests that VCardEntry refrains from emitting unnecessary insert operation.
     */
    public void testConstructInsertOperationsEmptyData() {
        VCardEntry entry = new VCardEntry();
        ContentResolver resolver = getContext().getContentResolver();
        ArrayList<ContentProviderOperation> operationList =
                new ArrayList<ContentProviderOperation>();
        entry.constructInsertOperations(resolver, operationList);
        assertEquals(0, operationList.size());
    }

    // TODO: add bunch of test for constructInsertOperations..
}