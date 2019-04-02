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

package com.android.server.wifi.hotspot2.anqp;

import android.net.Uri;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.ByteBufferReader;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The OSU Provider subfield in the OSU Providers List ANQP Element,
 * Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00,
 * section 4.8.1
 *
 * Format:
 *
 * | Length | Friendly Name Length | Friendly Name #1 | ... | Friendly Name #n |
 *     2               2                variable                  variable
 * | Server URI length | Server URI | Method List Length | Method List |
 *          1             variable             1             variable
 * | Icon Available Length | Icon Available | NAI Length | NAI | Description Length |
 *            2                variable            1     variable      2
 * | Description #1 | ... | Description #n |
 *      variable               variable
 *
 * | Operator Name Duple #N (optional) |
 *             variable
 */
public class OsuProviderInfo {
    /**
     * The raw payload should minimum include the following fields:
     * - Friendly Name Length (2)
     * - Server URI Length (1)
     * - Method List Length (1)
     * - Icon Available Length (2)
     * - NAI Length (1)
     * - Description Length (2)
     */
    @VisibleForTesting
    public static final int MINIMUM_LENGTH = 9;

    /**
     * Maximum octets for a I18N string.
     */
    private static final int MAXIMUM_I18N_STRING_LENGTH = 252;

    private final List<I18Name> mFriendlyNames;
    private final Uri mServerUri;
    private final List<Integer> mMethodList;
    private final List<IconInfo> mIconInfoList;
    private final String mNetworkAccessIdentifier;
    private final List<I18Name> mServiceDescriptions;

    @VisibleForTesting
    public OsuProviderInfo(List<I18Name> friendlyNames, Uri serverUri, List<Integer> methodList,
            List<IconInfo> iconInfoList, String nai, List<I18Name> serviceDescriptions) {
        mFriendlyNames = friendlyNames;
        mServerUri = serverUri;
        mMethodList = methodList;
        mIconInfoList = iconInfoList;
        mNetworkAccessIdentifier = nai;
        mServiceDescriptions = serviceDescriptions;
    }

    /**
     * Parse a OsuProviderInfo from the given buffer.
     *
     * @param payload The buffer to read from
     * @return {@link OsuProviderInfo}
     * @throws BufferUnderflowException
     * @throws ProtocolException
     */
    public static OsuProviderInfo parse(ByteBuffer payload)
            throws ProtocolException {
        // Parse length field.
        int length = (int) ByteBufferReader.readInteger(payload, ByteOrder.LITTLE_ENDIAN, 2)
                & 0xFFFF;
        if (length < MINIMUM_LENGTH) {
            throw new ProtocolException("Invalid length value: " + length);
        }

        // Parse friendly names.
        int friendlyNameLength =
                (int) ByteBufferReader.readInteger(payload, ByteOrder.LITTLE_ENDIAN, 2) & 0xFFFF;
        ByteBuffer friendlyNameBuffer = getSubBuffer(payload, friendlyNameLength);
        List<I18Name> friendlyNameList = parseI18Names(friendlyNameBuffer);

        // Parse server URI.
        Uri serverUri = Uri.parse(
                ByteBufferReader.readStringWithByteLength(payload, StandardCharsets.UTF_8));

        // Parse method list.
        int methodListLength = payload.get() & 0xFF;
        List<Integer> methodList = new ArrayList<>();
        while (methodListLength > 0) {
            methodList.add(payload.get() & 0xFF);
            methodListLength--;
        }

        // Parse list of icon info.
        int availableIconLength =
                (int) ByteBufferReader.readInteger(payload, ByteOrder.LITTLE_ENDIAN, 2) & 0xFFFF;
        ByteBuffer iconBuffer = getSubBuffer(payload, availableIconLength);
        List<IconInfo> iconInfoList = new ArrayList<>();
        while (iconBuffer.hasRemaining()) {
            iconInfoList.add(IconInfo.parse(iconBuffer));
        }

        // Parse Network Access Identifier.
        String nai = ByteBufferReader.readStringWithByteLength(payload, StandardCharsets.UTF_8);

        // Parse service descriptions.
        int serviceDescriptionLength =
                (int) ByteBufferReader.readInteger(payload, ByteOrder.LITTLE_ENDIAN, 2) & 0xFFFF;
        ByteBuffer descriptionsBuffer = getSubBuffer(payload, serviceDescriptionLength);
        List<I18Name> serviceDescriptionList = parseI18Names(descriptionsBuffer);

        return new OsuProviderInfo(friendlyNameList, serverUri, methodList, iconInfoList, nai,
                serviceDescriptionList);
    }

    public List<I18Name> getFriendlyNames() {
        return Collections.unmodifiableList(mFriendlyNames);
    }

    public Uri getServerUri() {
        return mServerUri;
    }

    public List<Integer> getMethodList() {
        return Collections.unmodifiableList(mMethodList);
    }

    public List<IconInfo> getIconInfoList() {
        return Collections.unmodifiableList(mIconInfoList);
    }

    public String getNetworkAccessIdentifier() {
        return mNetworkAccessIdentifier;
    }

    public List<I18Name> getServiceDescriptions() {
        return Collections.unmodifiableList(mServiceDescriptions);
    }

    /**
     * Return the friendly name string from the friendly name list.  The string matching
     * the default locale will be returned if it is found, otherwise the first name in the list
     * will be returned.  A null will be returned if the list is empty.
     *
     * @return friendly name string
     */
    public String getFriendlyName() {
        return getI18String(mFriendlyNames);
    }

    /**
     * Return the service description string from the service description list.  The string
     * matching the default locale will be returned if it is found, otherwise the first element in
     * the list will be returned.  A null will be returned if the list is empty.
     *
     * @return service description string
     */
    public String getServiceDescription() {
        return getI18String(mServiceDescriptions);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof OsuProviderInfo)) {
            return false;
        }
        OsuProviderInfo that = (OsuProviderInfo) thatObject;
        return (mFriendlyNames == null ? that.mFriendlyNames == null
                        : mFriendlyNames.equals(that.mFriendlyNames))
                && (mServerUri == null ? that.mServerUri == null
                        : mServerUri.equals(that.mServerUri))
                && (mMethodList == null ? that.mMethodList == null
                        : mMethodList.equals(that.mMethodList))
                && (mIconInfoList == null ? that.mIconInfoList == null
                        : mIconInfoList.equals(that.mIconInfoList))
                && TextUtils.equals(mNetworkAccessIdentifier, that.mNetworkAccessIdentifier)
                && (mServiceDescriptions == null ? that.mServiceDescriptions == null
                        : mServiceDescriptions.equals(that.mServiceDescriptions));
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFriendlyNames, mServerUri, mMethodList, mIconInfoList,
                mNetworkAccessIdentifier, mServiceDescriptions);
    }

    @Override
    public String toString() {
        return "OsuProviderInfo{"
                + "mFriendlyNames=" + mFriendlyNames
                + ", mServerUri=" + mServerUri
                + ", mMethodList=" + mMethodList
                + ", mIconInfoList=" + mIconInfoList
                + ", mNetworkAccessIdentifier=" + mNetworkAccessIdentifier
                + ", mServiceDescriptions=" + mServiceDescriptions
                + "}";
    }

    /**
     * Parse list of I18N string from the given payload.
     *
     * @param payload The payload to parse from
     * @return List of {@link I18Name}
     * @throws ProtocolException
     */
    private static List<I18Name> parseI18Names(ByteBuffer payload) throws ProtocolException {
        List<I18Name> results = new ArrayList<>();
        while (payload.hasRemaining()) {
            I18Name name = I18Name.parse(payload);
            // Verify that the number of bytes for the operator name doesn't exceed the max
            // allowed.
            int textBytes = name.getText().getBytes(StandardCharsets.UTF_8).length;
            if (textBytes > MAXIMUM_I18N_STRING_LENGTH) {
                throw new ProtocolException("I18Name string exceeds the maximum allowed "
                        + textBytes);
            }
            results.add(name);
        }
        return results;
    }

    /**
     * Creates a new byte buffer whose content is a shared subsequence of
     * the given buffer's content.
     *
     * The sub buffer will starts from |payload|'s current position
     * and ends at |payload|'s current position plus |length|.  The |payload|'s current
     * position will advance pass |length| bytes.
     *
     * @param payload The original buffer
     * @param length The length of the new buffer
     * @return {@link ByteBuffer}
     * @throws BufferUnderflowException
     */
    private static ByteBuffer getSubBuffer(ByteBuffer payload, int length) {
        if (payload.remaining() < length) {
            throw new BufferUnderflowException();
        }
        // Set the subBuffer's starting and ending position.
        ByteBuffer subBuffer = payload.slice();
        subBuffer.limit(length);
        // Advance the original buffer's current position.
        payload.position(payload.position() + length);
        return subBuffer;
    }

    /**
     * Return the appropriate I18 string value from the list of I18 string values.
     * The string matching the default locale will be returned if it is found, otherwise the
     * first string in the list will be returned.  A null will be returned if the list is empty.
     *
     * @param i18Strings List of I18 string values
     * @return String matching the default locale, null otherwise
     */
    private static String getI18String(List<I18Name> i18Strings) {
        for (I18Name name : i18Strings) {
            if (name.getLanguage().equals(Locale.getDefault().getLanguage())) {
                return name.getText();
            }
        }
        if (i18Strings.size() > 0) {
            return i18Strings.get(0).getText();
        }
        return null;
    }
}
