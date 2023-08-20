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
package com.android.vcard;

/**
 * Constants used in both exporter and importer code.
 */
public class VCardConstants {
    /* package */ static final String LOG_TAG = "vCard";
    public static final String VERSION_V21 = "2.1";
    public static final String VERSION_V30 = "3.0";
    public static final String VERSION_V40 = "4.0";

    // Valid and supported property names.
    public static final String PROPERTY_BEGIN = "BEGIN";
    public static final String PROPERTY_VERSION = "VERSION";
    public static final String PROPERTY_N = "N";
    public static final String PROPERTY_FN = "FN";
    public static final String PROPERTY_ADR = "ADR";
    public static final String PROPERTY_EMAIL = "EMAIL";
    public static final String PROPERTY_NOTE = "NOTE";
    public static final String PROPERTY_ORG = "ORG";
    public static final String PROPERTY_SOUND = "SOUND";  // Not fully supported.
    public static final String PROPERTY_TEL = "TEL";
    public static final String PROPERTY_TITLE = "TITLE";
    public static final String PROPERTY_ROLE = "ROLE";
    public static final String PROPERTY_PHOTO = "PHOTO";
    public static final String PROPERTY_LOGO = "LOGO";
    public static final String PROPERTY_URL = "URL";
    public static final String PROPERTY_BDAY = "BDAY";  // Birthday (3.0, 4.0)
    public static final String PROPERTY_ANNIVERSARY = "ANNIVERSARY";  // Date of marriage (4.0)
    public static final String PROPERTY_NAME = "NAME";  // (3.0)
    public static final String PROPERTY_NICKNAME = "NICKNAME";  // (3.0, 4.0)
    public static final String PROPERTY_SORT_STRING = "SORT-STRING";  // (3.0, 4.0)
    public static final String PROPERTY_IMPP = "IMPP";  // RFC 4770 (vCard 3.0) and vCard 4.0
    public static final String PROPERTY_END = "END";

    // Valid property names which are not supported (not appropriately handled) by
    // importer/exporter. Those properties will be read and discarded silently.
    public static final String PROPERTY_REV = "REV";
    public static final String PROPERTY_AGENT = "AGENT";  // (3.0)
    public static final String PROPERTY_GENDER = "GENDER";  // (4.0)
    public static final String PROPERTY_XML = "XML";  // (4.0)
    public static final String PROPERTY_FBURL = "FBURL";  // (4.0)
    public static final String PROPERTY_PRODID = "PRODID";  // (4.0)
    public static final String PROPERTY_RELATED = "RELATED";  // (4.0)
    public static final String PROPERTY_CATEGORIES = "CATEGORIES";  // (4.0)
    public static final String PROPERTY_CLIENTPIDMAP = "CLIENTPIDMAP";  // (4.0)
    public static final String PROPERTY_CALURI = "CALURI";  // (4.0)



    // defact SIP property which had been used till RFC 4770.
    public static final String PROPERTY_X_SIP = "X-SIP";

    // Available in vCard 3.0. Shoud not use when composing vCard 2.1 file.

    // De-fact property values expressing phonetic names.
    public static final String PROPERTY_X_PHONETIC_FIRST_NAME = "X-PHONETIC-FIRST-NAME";
    public static final String PROPERTY_X_PHONETIC_MIDDLE_NAME = "X-PHONETIC-MIDDLE-NAME";
    public static final String PROPERTY_X_PHONETIC_LAST_NAME = "X-PHONETIC-LAST-NAME";

    // Properties both ContactsStruct and de-fact vCard extensions
    // Shown in http://en.wikipedia.org/wiki/VCard support are defined here.
    public static final String PROPERTY_X_AIM = "X-AIM";
    public static final String PROPERTY_X_MSN = "X-MSN";
    public static final String PROPERTY_X_YAHOO = "X-YAHOO";
    public static final String PROPERTY_X_ICQ = "X-ICQ";
    public static final String PROPERTY_X_JABBER = "X-JABBER";
    public static final String PROPERTY_X_GOOGLE_TALK = "X-GOOGLE-TALK";
    public static final String PROPERTY_X_SKYPE_USERNAME = "X-SKYPE-USERNAME";
    // Properties only ContactsStruct has. We alse use this.
    public static final String PROPERTY_X_QQ = "X-QQ";
    public static final String PROPERTY_X_NETMEETING = "X-NETMEETING";

    // Phone number for Skype, available as usual phone.
    public static final String PROPERTY_X_SKYPE_PSTNNUMBER = "X-SKYPE-PSTNNUMBER";

    // Property for Android-specific fields.
    public static final String PROPERTY_X_ANDROID_CUSTOM = "X-ANDROID-CUSTOM";

    // Properties for DoCoMo vCard.
    public static final String PROPERTY_X_CLASS = "X-CLASS";
    public static final String PROPERTY_X_REDUCTION = "X-REDUCTION";
    public static final String PROPERTY_X_NO = "X-NO";
    public static final String PROPERTY_X_DCM_HMN_MODE = "X-DCM-HMN-MODE";

    public static final String PARAM_TYPE = "TYPE";

    public static final String PARAM_TYPE_HOME = "HOME";
    public static final String PARAM_TYPE_WORK = "WORK";
    public static final String PARAM_TYPE_FAX = "FAX";
    public static final String PARAM_TYPE_CELL = "CELL";
    public static final String PARAM_TYPE_VOICE = "VOICE";
    public static final String PARAM_TYPE_INTERNET = "INTERNET";

    public static final String PARAM_VALUE = "VALUE";
    public static final String PARAM_CHARSET = "CHARSET";
    public static final String PARAM_ENCODING = "ENCODING";

    // Abbreviation of "prefered" according to vCard 2.1 specification.
    // We interpret this value as "primary" property during import/export.
    //
    // Note: Both vCard specs does not mention anything about the requirement for this parameter,
    //       but there may be some vCard importer which will get confused with more than
    //       one "PREF"s in one property name, while Android accepts them.
    public static final String PARAM_TYPE_PREF = "PREF";

    // Phone type parameters valid in vCard and known to ContactsContract, but not so common.
    public static final String PARAM_TYPE_CAR = "CAR";
    public static final String PARAM_TYPE_ISDN = "ISDN";
    public static final String PARAM_TYPE_PAGER = "PAGER";
    public static final String PARAM_TYPE_TLX = "TLX";  // Telex

    // Phone types existing in vCard 2.1 but not known to ContactsContract.
    public static final String PARAM_TYPE_MODEM = "MODEM";
    public static final String PARAM_TYPE_MSG = "MSG";
    public static final String PARAM_TYPE_BBS = "BBS";
    public static final String PARAM_TYPE_VIDEO = "VIDEO";

    public static final String PARAM_ENCODING_7BIT = "7BIT";
    public static final String PARAM_ENCODING_8BIT = "8BIT";
    public static final String PARAM_ENCODING_QP = "QUOTED-PRINTABLE";
    public static final String PARAM_ENCODING_BASE64 = "BASE64";  // Available in vCard 2.1
    public static final String PARAM_ENCODING_B = "B";  // Available in vCard 3.0

    // TYPE parameters for Phones, which are not formally valid in vCard (at least 2.1).
    // These types are basically encoded to "X-" parameters when composing vCard.
    // Parser passes these when "X-" is added to the parameter or not.
    public static final String PARAM_PHONE_EXTRA_TYPE_CALLBACK = "CALLBACK";
    public static final String PARAM_PHONE_EXTRA_TYPE_RADIO = "RADIO";
    public static final String PARAM_PHONE_EXTRA_TYPE_TTY_TDD = "TTY-TDD";
    public static final String PARAM_PHONE_EXTRA_TYPE_ASSISTANT = "ASSISTANT";
    // vCard composer translates this type to "WORK" + "PREF". Just for parsing.
    public static final String PARAM_PHONE_EXTRA_TYPE_COMPANY_MAIN = "COMPANY-MAIN";
    // vCard composer translates this type to "VOICE" Just for parsing.
    public static final String PARAM_PHONE_EXTRA_TYPE_OTHER = "OTHER";

    // TYPE parameters for postal addresses.
    public static final String PARAM_ADR_TYPE_PARCEL = "PARCEL";
    public static final String PARAM_ADR_TYPE_DOM = "DOM";
    public static final String PARAM_ADR_TYPE_INTL = "INTL";
    // {@link VCardBuilder} translates this type to "X-OTHER".
    public static final String PARAM_ADR_EXTRA_TYPE_OTHER = "OTHER";

    public static final String PARAM_LANGUAGE = "LANGUAGE";

    // SORT-AS parameter introduced in vCard 4.0 (as of rev.13)
    public static final String PARAM_SORT_AS = "SORT-AS";

    // TYPE parameters not officially valid but used in some vCard exporter.
    // Do not use in composer side.
    public static final String PARAM_EXTRA_TYPE_COMPANY = "COMPANY";

    public interface ImportOnly {
        public static final String PROPERTY_X_NICKNAME = "X-NICKNAME";
        // Some device emits this "X-" parameter for expressing Google Talk,
        // which is specifically invalid but should be always properly accepted, and emitted
        // in some special case (for that device/application).
        public static final String PROPERTY_X_GOOGLE_TALK_WITH_SPACE = "X-GOOGLE TALK";
    }

    //// Mainly for package constants.

    // DoCoMo specific type parameter. Used with "SOUND" property, which is alternate of
    // SORT-STRING invCard 3.0.
    /* package */ static final String PARAM_TYPE_X_IRMC_N = "X-IRMC-N";

    // Used in unit test.
    public static final int MAX_DATA_COLUMN = 15;

    /* package */ static final int MAX_CHARACTER_NUMS_QP = 76;
    static final int MAX_CHARACTER_NUMS_BASE64_V30 = 75;

    private VCardConstants() {
    }
}