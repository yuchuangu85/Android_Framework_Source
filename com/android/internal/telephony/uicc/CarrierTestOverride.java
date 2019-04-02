/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.internal.telephony.uicc;

import android.os.Environment;
import android.telephony.Rlog;
import android.util.Xml;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;

/**
 * Provide a machanism to override MVNO paramteres under CarrierConfig through a config file.
 */
public class CarrierTestOverride {
    static final String LOG_TAG = "CarrierTestOverride";

    /**
     * Config file that can be created and adb-pushed by tester/developer
     *
     * Sample xml:
     * <carrierTestOverrides>
       <carrierTestOverride key="isInTestMode" value="true"/>
       <carrierTestOverride key="gid1" value="bae0000000000000"/>
       <carrierTestOverride key="gid2" value="ffffffffffffffff"/>
       <carrierTestOverride key="imsi" value="310010123456789"/>
       <carrierTestOverride key="spn" value="Verizon"/>
       </carrierTestOverrides>
     */
    static final String DATA_CARRIER_TEST_OVERRIDE_PATH =
            "/user_de/0/com.android.phone/files/carrier_test_conf.xml";
    static final String CARRIER_TEST_XML_HEADER = "carrierTestOverrides";
    static final String CARRIER_TEST_XML_SUBHEADER = "carrierTestOverride";
    static final String CARRIER_TEST_XML_ITEM_KEY = "key";
    static final String CARRIER_TEST_XML_ITEM_VALUE = "value";
    static final String CARRIER_TEST_XML_ITEM_KEY_STRING_ISINTESTMODE = "isInTestMode";
    static final String CARRIER_TEST_XML_ITEM_KEY_STRING_GID1 = "gid1";
    static final String CARRIER_TEST_XML_ITEM_KEY_STRING_GID2 = "gid2";
    static final String CARRIER_TEST_XML_ITEM_KEY_STRING_IMSI = "imsi";
    static final String CARRIER_TEST_XML_ITEM_KEY_STRING_SPN = "spn";

    private HashMap<String, String> mCarrierTestParamMap;

    CarrierTestOverride() {
        mCarrierTestParamMap = new HashMap<String, String>();
        loadCarrierTestOverrides();
    }

    boolean isInTestMode() {
        return mCarrierTestParamMap.containsKey(CARRIER_TEST_XML_ITEM_KEY_STRING_ISINTESTMODE)
                && mCarrierTestParamMap.get(CARRIER_TEST_XML_ITEM_KEY_STRING_ISINTESTMODE)
                .equals("true");
    }

    String getFakeSpn() {
        try {
            String spn = mCarrierTestParamMap.get(CARRIER_TEST_XML_ITEM_KEY_STRING_SPN);
            Rlog.d(LOG_TAG, "reading spn from CarrierTestConfig file: " + spn);
            return spn;
        } catch (NullPointerException e) {
            Rlog.w(LOG_TAG, "No spn in CarrierTestConfig file ");
            return null;
        }
    }

    String getFakeIMSI() {
        try {
            String imsi = mCarrierTestParamMap.get(CARRIER_TEST_XML_ITEM_KEY_STRING_IMSI);
            Rlog.d(LOG_TAG, "reading imsi from CarrierTestConfig file: " + imsi);
            return imsi;
        } catch (NullPointerException e) {
            Rlog.w(LOG_TAG, "No imsi in CarrierTestConfig file ");
            return null;
        }
    }

    String getFakeGid1() {
        try {
            String gid1 = mCarrierTestParamMap.get(CARRIER_TEST_XML_ITEM_KEY_STRING_GID1);
            Rlog.d(LOG_TAG, "reading gid1 from CarrierTestConfig file: " + gid1);
            return gid1;
        } catch (NullPointerException e) {
            Rlog.w(LOG_TAG, "No gid1 in CarrierTestConfig file ");
            return null;
        }
    }

    String getFakeGid2() {
        try {
            String gid2 = mCarrierTestParamMap.get(CARRIER_TEST_XML_ITEM_KEY_STRING_GID2);
            Rlog.d(LOG_TAG, "reading gid2 from CarrierTestConfig file: " + gid2);
            return gid2;
        } catch (NullPointerException e) {
            Rlog.w(LOG_TAG, "No gid2 in CarrierTestConfig file ");
            return null;
        }
    }

    private void loadCarrierTestOverrides() {

        FileReader carrierTestConfigReader;

        File carrierTestConfigFile = new File(Environment.getDataDirectory(),
                DATA_CARRIER_TEST_OVERRIDE_PATH);

        try {
            carrierTestConfigReader = new FileReader(carrierTestConfigFile);
            Rlog.d(LOG_TAG, "CarrierTestConfig file Modified Timestamp: "
                    + carrierTestConfigFile.lastModified());
        } catch (FileNotFoundException e) {
            Rlog.w(LOG_TAG, "Can not open " + carrierTestConfigFile.getAbsolutePath());
            return;
        }

        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(carrierTestConfigReader);

            XmlUtils.beginDocument(parser, CARRIER_TEST_XML_HEADER);

            while (true) {
                XmlUtils.nextElement(parser);

                String name = parser.getName();
                if (!CARRIER_TEST_XML_SUBHEADER.equals(name)) {
                    break;
                }

                String key = parser.getAttributeValue(null, CARRIER_TEST_XML_ITEM_KEY);
                String value = parser.getAttributeValue(null, CARRIER_TEST_XML_ITEM_VALUE);

                Rlog.d(LOG_TAG,
                        "extracting key-values from CarrierTestConfig file: " + key + "|" + value);
                mCarrierTestParamMap.put(key, value);
            }
            carrierTestConfigReader.close();
        } catch (XmlPullParserException e) {
            Rlog.w(LOG_TAG, "Exception in carrier_test_conf parser " + e);
        } catch (IOException e) {
            Rlog.w(LOG_TAG, "Exception in carrier_test_conf parser " + e);
        }
    }
}
