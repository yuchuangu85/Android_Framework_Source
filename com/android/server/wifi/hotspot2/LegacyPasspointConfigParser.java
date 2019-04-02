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

package com.android.server.wifi.hotspot2;

import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for parsing legacy (N and older) Passpoint configuration file content
 * (/data/misc/wifi/PerProviderSubscription.conf).  In N and older, only Release 1 is supported.
 *
 * This class only retrieve the relevant Release 1 configuration fields that are not backed
 * elsewhere.  Below are relevant fields:
 * - FQDN (used for linking with configuration data stored elsewhere)
 * - Friendly Name
 * - Roaming Consortium
 * - Realm
 * - IMSI (for SIM credential)
 *
 * Below is an example content of a Passpoint configuration file:
 *
 * tree 3:1.2(urn:wfa:mo:hotspot2dot0-perprovidersubscription:1.0)
 * 8:MgmtTree+
 * 17:PerProviderSubscription+
 * 4:r1i1+
 * 6:HomeSP+
 * c:FriendlyName=d:Test Provider
 * 4:FQDN=8:test.net
 * 13:RoamingConsortiumOI=9:1234,5678
 * .
 * a:Credential+
 * 10:UsernamePassword+
 * 8:Username=4:user
 * 8:Password=4:pass
 *
 * 9:EAPMethod+
 * 7:EAPType=2:21
 * b:InnerMethod=3:PAP
 * .
 * .
 * 5:Realm=a:boingo.com
 * .
 * .
 * .
 * .
 *
 * Each string is prefixed with a "|StringBytesInHex|:".
 * '+' indicates start of a new internal node.
 * '.' indicates end of the current internal node.
 * '=' indicates "value" of a leaf node.
 *
 */
public class LegacyPasspointConfigParser {
    private static final String TAG = "LegacyPasspointConfigParser";

    private static final String TAG_MANAGEMENT_TREE = "MgmtTree";
    private static final String TAG_PER_PROVIDER_SUBSCRIPTION = "PerProviderSubscription";
    private static final String TAG_HOMESP = "HomeSP";
    private static final String TAG_FQDN = "FQDN";
    private static final String TAG_FRIENDLY_NAME = "FriendlyName";
    private static final String TAG_ROAMING_CONSORTIUM_OI = "RoamingConsortiumOI";
    private static final String TAG_CREDENTIAL = "Credential";
    private static final String TAG_REALM = "Realm";
    private static final String TAG_SIM = "SIM";
    private static final String TAG_IMSI = "IMSI";

    private static final String LONG_ARRAY_SEPARATOR = ",";
    private static final String END_OF_INTERNAL_NODE_INDICATOR = ".";
    private static final char START_OF_INTERNAL_NODE_INDICATOR = '+';
    private static final char STRING_PREFIX_INDICATOR = ':';
    private static final char STRING_VALUE_INDICATOR = '=';

    /**
     * An abstraction for a node within a tree.  A node can be an internal node (contained
     * children nodes) or a leaf node (contained a String value).
     */
    private abstract static class Node {
        private final String mName;
        Node(String name) {
            mName = name;
        }

        /**
         * @return the name of the node
         */
        public String getName() {
            return mName;
        }

        /**
         * Applies for internal node only.
         *
         * @return the list of children nodes.
         */
        public abstract List<Node> getChildren();

        /**
         * Applies for leaf node only.
         *
         * @return the string value of the node
         */
        public abstract String getValue();
    }

    /**
     * Class representing an internal node of a tree.  It contained a list of child nodes.
     */
    private static class InternalNode extends Node {
        private final List<Node> mChildren;
        InternalNode(String name, List<Node> children) {
            super(name);
            mChildren = children;
        }

        @Override
        public List<Node> getChildren() {
            return mChildren;
        }

        @Override
        public String getValue() {
            return null;
        }
    }

    /**
     * Class representing a leaf node of a tree.  It contained a String type value.
     */
    private static class LeafNode extends Node {
        private final String mValue;
        LeafNode(String name, String value) {
            super(name);
            mValue = value;
        }

        @Override
        public List<Node> getChildren() {
            return null;
        }

        @Override
        public String getValue() {
            return mValue;
        }
    }

    public LegacyPasspointConfigParser() {}

    /**
     * Parse the legacy Passpoint configuration file content, only retrieve the relevant
     * configurations that are not saved elsewhere.
     *
     * For both N and M, only Release 1 is supported. Most of the configurations are saved
     * elsewhere as part of the {@link android.net.wifi.WifiConfiguration} data.
     * The configurations needed from the legacy Passpoint configuration file are:
     *
     * - FQDN - needed to be able to link to the associated {@link WifiConfiguration} data
     * - Friendly Name
     * - Roaming Consortium OIs
     * - Realm
     * - IMSI (for SIM credential)
     *
     * Make this function non-static so that it can be mocked during unit test.
     *
     * @param fileName The file name of the configuration file
     * @return Map of FQDN to {@link LegacyPasspointConfig}
     * @throws IOException
     */
    public Map<String, LegacyPasspointConfig> parseConfig(String fileName)
            throws IOException {
        Map<String, LegacyPasspointConfig> configs = new HashMap<>();
        BufferedReader in = new BufferedReader(new FileReader(fileName));
        in.readLine();      // Ignore the first line which contained the header.

        // Convert the configuration data to a management tree represented by a root {@link Node}.
        Node root = buildNode(in);

        if (root == null || root.getChildren() == null) {
            Log.d(TAG, "Empty configuration data");
            return configs;
        }

        // Verify root node name.
        if (!TextUtils.equals(TAG_MANAGEMENT_TREE, root.getName())) {
            throw new IOException("Unexpected root node: " + root.getName());
        }

        // Process and retrieve the configuration from each PPS (PerProviderSubscription) node.
        List<Node> ppsNodes = root.getChildren();
        for (Node ppsNode : ppsNodes) {
            LegacyPasspointConfig config = processPpsNode(ppsNode);
            configs.put(config.mFqdn, config);
        }
        return configs;
    }

    /**
     * Build a {@link Node} from the current line in the buffer.  A node can be an internal
     * node (ends with '+') or a leaf node.
     *
     * @param in Input buffer to read data from
     * @return {@link Node} representing the current line
     * @throws IOException
     */
    private static Node buildNode(BufferedReader in) throws IOException {
        // Read until non-empty line.
        String currentLine = null;
        while ((currentLine = in.readLine()) != null) {
            if (!currentLine.isEmpty()) {
                break;
            }
        }

        // Return null if EOF is reached.
        if (currentLine == null) {
            return null;
        }

        // Remove the leading and the trailing whitespaces.
        currentLine = currentLine.trim();

        // Check for the internal node terminator.
        if (TextUtils.equals(END_OF_INTERNAL_NODE_INDICATOR, currentLine)) {
            return null;
        }

        // Parse the name-value of the current line.  The value will be null if the current line
        // is not a leaf node (e.g. line ends with a '+').
        // Each line is encoded in UTF-8.
        Pair<String, String> nameValuePair =
                parseLine(currentLine.getBytes(StandardCharsets.UTF_8));
        if (nameValuePair.second != null) {
            return new LeafNode(nameValuePair.first, nameValuePair.second);
        }

        // Parse the children contained under this internal node.
        List<Node> children = new ArrayList<>();
        Node child = null;
        while ((child = buildNode(in)) != null) {
            children.add(child);
        }
        return new InternalNode(nameValuePair.first, children);
    }

    /**
     * Process a PPS (PerProviderSubscription) node to retrieve Passpoint configuration data.
     *
     * @param ppsNode The PPS node to process
     * @return {@link LegacyPasspointConfig}
     * @throws IOException
     */
    private static LegacyPasspointConfig processPpsNode(Node ppsNode) throws IOException {
        if (ppsNode.getChildren() == null || ppsNode.getChildren().size() != 1) {
            throw new IOException("PerProviderSubscription node should contain "
                    + "one instance node");
        }

        if (!TextUtils.equals(TAG_PER_PROVIDER_SUBSCRIPTION, ppsNode.getName())) {
            throw new IOException("Unexpected name for PPS node: " + ppsNode.getName());
        }

        // Retrieve the PPS instance node.
        Node instanceNode = ppsNode.getChildren().get(0);
        if (instanceNode.getChildren() == null) {
            throw new IOException("PPS instance node doesn't contained any children");
        }

        // Process and retrieve the relevant configurations under the PPS instance node.
        LegacyPasspointConfig config = new LegacyPasspointConfig();
        for (Node node : instanceNode.getChildren()) {
            switch (node.getName()) {
                case TAG_HOMESP:
                    processHomeSPNode(node, config);
                    break;
                case TAG_CREDENTIAL:
                    processCredentialNode(node, config);
                    break;
                default:
                    Log.d(TAG, "Ignore uninterested field under PPS instance: " + node.getName());
                    break;
            }
        }
        if (config.mFqdn == null) {
            throw new IOException("PPS instance missing FQDN");
        }
        return config;
    }

    /**
     * Process a HomeSP node to retrieve configuration data into the given |config|.
     *
     * @param homeSpNode The HomeSP node to process
     * @param config The config object to fill in the data
     * @throws IOException
     */
    private static void processHomeSPNode(Node homeSpNode, LegacyPasspointConfig config)
            throws IOException {
        if (homeSpNode.getChildren() == null) {
            throw new IOException("HomeSP node should contain at least one child node");
        }

        for (Node node : homeSpNode.getChildren()) {
            switch (node.getName()) {
                case TAG_FQDN:
                    config.mFqdn = getValue(node);
                    break;
                case TAG_FRIENDLY_NAME:
                    config.mFriendlyName = getValue(node);
                    break;
                case TAG_ROAMING_CONSORTIUM_OI:
                    config.mRoamingConsortiumOis = parseLongArray(getValue(node));
                    break;
                default:
                    Log.d(TAG, "Ignore uninterested field under HomeSP: " + node.getName());
                    break;
            }
        }
    }

    /**
     * Process a Credential node to retrieve configuration data into the given |config|.
     *
     * @param credentialNode The Credential node to process
     * @param config The config object to fill in the data
     * @throws IOException
     */
    private static void processCredentialNode(Node credentialNode,
            LegacyPasspointConfig config)
            throws IOException {
        if (credentialNode.getChildren() == null) {
            throw new IOException("Credential node should contain at least one child node");
        }

        for (Node node : credentialNode.getChildren()) {
            switch (node.getName()) {
                case TAG_REALM:
                    config.mRealm = getValue(node);
                    break;
                case TAG_SIM:
                    processSimNode(node, config);
                    break;
                default:
                    Log.d(TAG, "Ignore uninterested field under Credential: " + node.getName());
                    break;
            }
        }
    }

    /**
     * Process a SIM node to retrieve configuration data into the given |config|.
     *
     * @param simNode The SIM node to process
     * @param config The config object to fill in the data
     * @throws IOException
     */
    private static void processSimNode(Node simNode, LegacyPasspointConfig config)
            throws IOException {
        if (simNode.getChildren() == null) {
            throw new IOException("SIM node should contain at least one child node");
        }

        for (Node node : simNode.getChildren()) {
            switch (node.getName()) {
                case TAG_IMSI:
                    config.mImsi = getValue(node);
                    break;
                default:
                    Log.d(TAG, "Ignore uninterested field under SIM: " + node.getName());
                    break;
            }
        }
    }

    /**
     * Parse the given line in the legacy Passpoint configuration file.
     * A line can be in the following formats:
     * 2:ab+         // internal node
     * 2:ab=2:bc     // leaf node
     * .             // end of internal node
     *
     * @param line The line to parse
     * @return name-value pair, a value of null indicates internal node
     * @throws IOException
     */
    private static Pair<String, String> parseLine(byte[] lineBytes) throws IOException {
        Pair<String, Integer> nameIndexPair = parseString(lineBytes, 0);
        int currentIndex = nameIndexPair.second;
        try {
            if (lineBytes[currentIndex] == START_OF_INTERNAL_NODE_INDICATOR) {
                return Pair.create(nameIndexPair.first, null);
            }

            if (lineBytes[currentIndex] != STRING_VALUE_INDICATOR) {
                throw new IOException("Invalid line - missing both node and value indicator: "
                        + new String(lineBytes, StandardCharsets.UTF_8));
            }
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("Invalid line - " + e.getMessage() + ": "
                    + new String(lineBytes, StandardCharsets.UTF_8));
        }
        Pair<String, Integer> valueIndexPair = parseString(lineBytes, currentIndex + 1);
        return Pair.create(nameIndexPair.first, valueIndexPair.first);
    }

    /**
     * Parse a string value in the given line from the given start index.
     * A string value is in the following format:
     * |HexByteLength|:|String|
     *
     * The length value indicates the number of UTF-8 bytes in hex for the given string.
     *
     * For example: 3:abc
     *
     * @param lineBytes The UTF-8 bytes of the line to parse
     * @param startIndex The start index from the given line to parse from
     * @return Pair of a string value and an index pointed to character after the string value
     * @throws IOException
     */
    private static Pair<String, Integer> parseString(byte[] lineBytes, int startIndex)
            throws IOException {
        // Locate the index that separate length and the string value.
        int prefixIndex = -1;
        for (int i = startIndex; i < lineBytes.length; i++) {
            if (lineBytes[i] == STRING_PREFIX_INDICATOR) {
                prefixIndex = i;
                break;
            }
        }
        if (prefixIndex == -1) {
            throw new IOException("Invalid line - missing string prefix: "
                    + new String(lineBytes, StandardCharsets.UTF_8));
        }

        try {
            String lengthStr = new String(lineBytes, startIndex, prefixIndex - startIndex,
                    StandardCharsets.UTF_8);
            int length = Integer.parseInt(lengthStr, 16);
            int strStartIndex = prefixIndex + 1;
            // The length might account for bytes for the whitespaces, since the whitespaces are
            // already trimmed, ignore them.
            if ((strStartIndex + length) > lineBytes.length) {
                length = lineBytes.length - strStartIndex;
            }
            return Pair.create(
                    new String(lineBytes, strStartIndex, length, StandardCharsets.UTF_8),
                    strStartIndex + length);
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw new IOException("Invalid line - " + e.getMessage() + ": "
                    + new String(lineBytes, StandardCharsets.UTF_8));
        }
    }

    /**
     * Parse a long array from the given string.
     *
     * @param str The string to parse
     * @return long[]
     * @throws IOException
     */
    private static long[] parseLongArray(String str)
            throws IOException {
        String[] strArray = str.split(LONG_ARRAY_SEPARATOR);
        long[] longArray = new long[strArray.length];
        for (int i = 0; i < longArray.length; i++) {
            try {
                longArray[i] = Long.parseLong(strArray[i], 16);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid long integer value: " + strArray[i]);
            }
        }
        return longArray;
    }

    /**
     * Get the String value of the given node.  An IOException will be thrown if the given
     * node doesn't contain a String value (internal node).
     *
     * @param node The node to get the value from
     * @return String
     * @throws IOException
     */
    private static String getValue(Node node) throws IOException {
        if (node.getValue() == null) {
            throw new IOException("Attempt to retreive value from non-leaf node: "
                    + node.getName());
        }
        return node.getValue();
    }
}
