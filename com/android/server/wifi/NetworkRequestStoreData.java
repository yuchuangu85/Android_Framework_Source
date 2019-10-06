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

import android.net.MacAddress;
import android.util.Log;

import com.android.server.wifi.WifiNetworkFactory.AccessPoint;
import com.android.server.wifi.util.XmlUtil;
import com.android.server.wifi.util.XmlUtil.WifiConfigurationXmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * This class performs serialization and parsing of XML data block that contain the list of WiFi
 * network request approvals.
 */
public class NetworkRequestStoreData implements WifiConfigStore.StoreData {
    private static final String TAG = "NetworkRequestStoreData";

    private static final String XML_TAG_SECTION_HEADER_NETWORK_REQUEST_MAP =
            "NetworkRequestMap";
    private static final String XML_TAG_SECTION_HEADER_APPROVED_ACCESS_POINTS_PER_APP =
            "ApprovedAccessPointsPerApp";
    private static final String XML_TAG_REQUESTOR_PACKAGE_NAME = "RequestorPackageName";
    private static final String XML_TAG_SECTION_HEADER_ACCESS_POINT = "AccessPoint";
    private static final String XML_TAG_ACCESS_POINT_SSID = WifiConfigurationXmlUtil.XML_TAG_SSID;
    private static final String XML_TAG_ACCESS_POINT_BSSID = WifiConfigurationXmlUtil.XML_TAG_BSSID;
    private static final String XML_TAG_ACCESS_POINT_NETWORK_TYPE = "NetworkType";

    /**
     * Interface define the data source for the network requests store data.
     */
    public interface DataSource {
        /**
         * Retrieve the approved access points from the data source to serialize them to disk.
         *
         * @return Map of package name to a set of {@link AccessPoint}
         */
        Map<String, Set<AccessPoint>> toSerialize();

        /**
         * Set the approved access points in the data source after serializing them from disk.
         *
         * @param approvedAccessPoints Map of package name to {@link AccessPoint}
         */
        void fromDeserialized(Map<String, Set<AccessPoint>> approvedAccessPoints);

        /**
         * Clear internal data structure in preparation for user switch or initial store read.
         */
        void reset();

        /**
         * Indicates whether there is new data to serialize.
         */
        boolean hasNewDataToSerialize();
    }

    private final DataSource mDataSource;

    public NetworkRequestStoreData(DataSource dataSource) {
        mDataSource = dataSource;
    }

    @Override
    public void serializeData(XmlSerializer out)
            throws XmlPullParserException, IOException {
        serializeApprovedAccessPointsMap(out, mDataSource.toSerialize());
    }

    @Override
    public void deserializeData(XmlPullParser in, int outerTagDepth)
            throws XmlPullParserException, IOException {
        // Ignore empty reads.
        if (in == null) {
            return;
        }
        mDataSource.fromDeserialized(parseApprovedAccessPointsMap(in, outerTagDepth));
    }

    @Override
    public void resetData() {
        mDataSource.reset();
    }

    @Override
    public boolean hasNewDataToSerialize() {
        return mDataSource.hasNewDataToSerialize();
    }

    @Override
    public String getName() {
        return XML_TAG_SECTION_HEADER_NETWORK_REQUEST_MAP;
    }

    @Override
    public @WifiConfigStore.StoreFileId int getStoreFileId() {
        return WifiConfigStore.STORE_FILE_USER_GENERAL;
    }

    /**
     * Serialize the map of package name to approved access points to an output stream in XML
     * format.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void serializeApprovedAccessPointsMap(
            XmlSerializer out, final Map<String, Set<AccessPoint>> approvedAccessPointsMap)
            throws XmlPullParserException, IOException {
        if (approvedAccessPointsMap == null) {
            return;
        }
        for (Entry<String, Set<AccessPoint>> entry : approvedAccessPointsMap.entrySet()) {
            String packageName = entry.getKey();
            XmlUtil.writeNextSectionStart(out,
                    XML_TAG_SECTION_HEADER_APPROVED_ACCESS_POINTS_PER_APP);
            XmlUtil.writeNextValue(out, XML_TAG_REQUESTOR_PACKAGE_NAME, packageName);
            serializeApprovedAccessPoints(out, entry.getValue());
            XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_APPROVED_ACCESS_POINTS_PER_APP);
        }
    }

    /**
     * Serialize the set of approved access points to an output stream in XML format.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void serializeApprovedAccessPoints(
            XmlSerializer out, final Set<AccessPoint> approvedAccessPoints)
            throws XmlPullParserException, IOException {
        for (AccessPoint approvedAccessPoint : approvedAccessPoints) {
            serializeApprovedAccessPoint(out, approvedAccessPoint);
        }
    }

    /**
     * Serialize a {@link AccessPoint} to an output stream in XML format.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void serializeApprovedAccessPoint(XmlSerializer out,
                                              AccessPoint approvedAccessPoint)
            throws XmlPullParserException, IOException {
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_ACCESS_POINT);
        XmlUtil.writeNextValue(out, XML_TAG_ACCESS_POINT_SSID, approvedAccessPoint.ssid);
        XmlUtil.writeNextValue(out, XML_TAG_ACCESS_POINT_BSSID,
                approvedAccessPoint.bssid.toString());
        XmlUtil.writeNextValue(out, XML_TAG_ACCESS_POINT_NETWORK_TYPE,
                approvedAccessPoint.networkType);
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_ACCESS_POINT);
    }

    /**
     * Parse a map of package name to approved access point from an input stream in XML format.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private Map<String, Set<AccessPoint>> parseApprovedAccessPointsMap(XmlPullParser in,
                                                                       int outerTagDepth)
            throws XmlPullParserException, IOException {
        Map<String, Set<AccessPoint>> approvedAccessPointsMap = new HashMap<>();
        while (XmlUtil.gotoNextSectionWithNameOrEnd(
                in, XML_TAG_SECTION_HEADER_APPROVED_ACCESS_POINTS_PER_APP, outerTagDepth)) {
            // Try/catch only runtime exceptions (like illegal args), any XML/IO exceptions are
            // fatal and should abort the entire loading process.
            try {
                String packageName =
                        (String) XmlUtil.readNextValueWithName(in, XML_TAG_REQUESTOR_PACKAGE_NAME);
                Set<AccessPoint> approvedAccessPoints =
                        parseApprovedAccessPoints(in, outerTagDepth + 1);
                approvedAccessPointsMap.put(packageName, approvedAccessPoints);
            } catch (RuntimeException e) {
                // Failed to parse this network, skip it.
                Log.e(TAG, "Failed to parse network suggestion. Skipping...", e);
            }
        }
        return approvedAccessPointsMap;
    }

    /**
     * Parse a set of approved access points from an input stream in XML format.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private Set<AccessPoint> parseApprovedAccessPoints(XmlPullParser in, int outerTagDepth)
            throws XmlPullParserException, IOException {
        Set<AccessPoint> approvedAccessPoints = new HashSet<>();
        while (XmlUtil.gotoNextSectionWithNameOrEnd(
                in, XML_TAG_SECTION_HEADER_ACCESS_POINT, outerTagDepth)) {
            // Try/catch only runtime exceptions (like illegal args), any XML/IO exceptions are
            // fatal and should abort the entire loading process.
            try {
                AccessPoint approvedAccessPoint =
                        parseApprovedAccessPoint(in, outerTagDepth + 1);
                approvedAccessPoints.add(approvedAccessPoint);
            } catch (RuntimeException e) {
                // Failed to parse this network, skip it.
                Log.e(TAG, "Failed to parse network suggestion. Skipping...", e);
            }
        }
        return approvedAccessPoints;
    }

    /**
     * Parse a {@link AccessPoint} from an input stream in XML format.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private AccessPoint parseApprovedAccessPoint(XmlPullParser in, int outerTagDepth)
            throws XmlPullParserException, IOException {
        String ssid = null;
        MacAddress bssid = null;
        int networkType = -1;

        // Loop through and parse out all the elements from the stream within this section.
        while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
            String[] valueName = new String[1];
            Object value = XmlUtil.readCurrentValue(in, valueName);
            if (valueName[0] == null) {
                throw new XmlPullParserException("Missing value name");
            }
            switch (valueName[0]) {
                case XML_TAG_ACCESS_POINT_SSID:
                    ssid = (String) value;
                    break;
                case XML_TAG_ACCESS_POINT_BSSID:
                    bssid = MacAddress.fromString((String) value);
                    break;
                case XML_TAG_ACCESS_POINT_NETWORK_TYPE:
                    networkType = (int) value;
                    break;
                default:
                    throw new XmlPullParserException(
                            "Unknown value name found: " + valueName[0]);
            }
        }
        if (ssid == null) {
            throw new XmlPullParserException("XML parsing of ssid failed");
        }
        if (bssid == null) {
            throw new XmlPullParserException("XML parsing of bssid failed");
        }
        if (networkType == -1) {
            throw new XmlPullParserException("XML parsing of network type failed");
        }
        return new AccessPoint(ssid, bssid, networkType);
    }
}

