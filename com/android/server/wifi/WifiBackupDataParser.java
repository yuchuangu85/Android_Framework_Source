/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wifi;

import android.net.wifi.WifiConfiguration;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.List;

/**
 * Interface describing parser of WiFi backup data for each major version.
 * Note that implementations of this interface should be returned
 * from {@link WifiBackupRestore#getWifiBackupDataParser()} method based on major version they
 * belong to.
 */
interface WifiBackupDataParser {

    /**
     * Parses the list of configurations from the provided XML stream.
     *
     * @param in            XmlPullParser instance pointing to the XML stream.
     * @param outerTagDepth depth of the outer tag in the XML document.
     * @param minorVersion  minor version number parsed from incoming data.
     * @return List<WifiConfiguration> object if parsing is successful, null otherwise.
     */
    List<WifiConfiguration> parseNetworkConfigurationsFromXml(XmlPullParser in, int outerTagDepth,
            int minorVersion) throws XmlPullParserException, IOException;
}
