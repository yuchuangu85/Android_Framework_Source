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

package com.android.server.wifi.util;

import android.text.TextUtils;

import com.android.server.wifi.ByteBufferReader;

import libcore.util.HexEncoding;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Provide utility functions for native interfacing modules.
 */
public class NativeUtil {
    private static final String ANY_MAC_STR = "any";
    public static final byte[] ANY_MAC_BYTES = {0, 0, 0, 0, 0, 0};
    private static final int MAC_LENGTH = 6;
    private static final int MAC_OUI_LENGTH = 3;
    private static final int MAC_STR_LENGTH = MAC_LENGTH * 2 + 5;
    private static final int SSID_BYTES_MAX_LEN = 32;

    /**
     * Convert the string to byte array list.
     *
     * @return the UTF_8 char byte values of str, as an ArrayList.
     * @throws IllegalArgumentException if a null or unencodable string is sent.
     */
    public static ArrayList<Byte> stringToByteArrayList(String str) {
        if (str == null) {
            throw new IllegalArgumentException("null string");
        }
        // Ensure that the provided string is UTF_8 encoded.
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        try {
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(str));
            byte[] byteArray = new byte[encoded.remaining()];
            encoded.get(byteArray);
            return byteArrayToArrayList(byteArray);
        } catch (CharacterCodingException cce) {
            throw new IllegalArgumentException("cannot be utf-8 encoded", cce);
        }
    }

    /**
     * Convert the byte array list to string.
     *
     * @return the string decoded from UTF_8 byte values in byteArrayList.
     * @throws IllegalArgumentException if a null byte array list is sent.
     */
    public static String stringFromByteArrayList(ArrayList<Byte> byteArrayList) {
        if (byteArrayList == null) {
            throw new IllegalArgumentException("null byte array list");
        }
        byte[] byteArray = new byte[byteArrayList.size()];
        int i = 0;
        for (Byte b : byteArrayList) {
            byteArray[i] = b;
            i++;
        }
        return new String(byteArray, StandardCharsets.UTF_8);
    }

    /**
     * Convert the string to byte array.
     *
     * @return the UTF_8 char byte values of str, as an Array.
     * @throws IllegalArgumentException if a null string is sent.
     */
    public static byte[] stringToByteArray(String str) {
        if (str == null) {
            throw new IllegalArgumentException("null string");
        }
        return str.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Convert the byte array list to string.
     *
     * @return the string decoded from UTF_8 byte values in byteArray.
     * @throws IllegalArgumentException if a null byte array is sent.
     */
    public static String stringFromByteArray(byte[] byteArray) {
        if (byteArray == null) {
            throw new IllegalArgumentException("null byte array");
        }
        return new String(byteArray);
    }

    /**
     * Converts a mac address string to an array of Bytes.
     *
     * @param macStr string of format: "XX:XX:XX:XX:XX:XX" or "XXXXXXXXXXXX", where X is any
     *        hexadecimal digit.
     *        Passing null, empty string or "any" is the same as 00:00:00:00:00:00
     * @throws IllegalArgumentException for various malformed inputs.
     */
    public static byte[] macAddressToByteArray(String macStr) {
        if (TextUtils.isEmpty(macStr) || ANY_MAC_STR.equals(macStr)) return ANY_MAC_BYTES;
        String cleanMac = macStr.replace(":", "");
        if (cleanMac.length() != MAC_LENGTH * 2) {
            throw new IllegalArgumentException("invalid mac string length: " + cleanMac);
        }
        return HexEncoding.decode(cleanMac.toCharArray(), false);
    }

    /**
     * Converts an array of 6 bytes to a HexEncoded String with format: "XX:XX:XX:XX:XX:XX", where X
     * is any hexadecimal digit.
     *
     * @param macArray byte array of mac values, must have length 6
     * @throws IllegalArgumentException for malformed inputs.
     */
    public static String macAddressFromByteArray(byte[] macArray) {
        if (macArray == null) {
            throw new IllegalArgumentException("null mac bytes");
        }
        if (macArray.length != MAC_LENGTH) {
            throw new IllegalArgumentException("invalid macArray length: " + macArray.length);
        }
        StringBuilder sb = new StringBuilder(MAC_STR_LENGTH);
        for (int i = 0; i < macArray.length; i++) {
            if (i != 0) sb.append(":");
            sb.append(new String(HexEncoding.encode(macArray, i, 1)));
        }
        return sb.toString().toLowerCase();
    }

    /**
     * Converts a mac address OUI string to an array of Bytes.
     *
     * @param macStr string of format: "XX:XX:XX" or "XXXXXX", where X is any hexadecimal digit.
     * @throws IllegalArgumentException for various malformed inputs.
     */
    public static byte[] macAddressOuiToByteArray(String macStr) {
        if (macStr == null) {
            throw new IllegalArgumentException("null mac string");
        }
        String cleanMac = macStr.replace(":", "");
        if (cleanMac.length() != MAC_OUI_LENGTH * 2) {
            throw new IllegalArgumentException("invalid mac oui string length: " + cleanMac);
        }
        return HexEncoding.decode(cleanMac.toCharArray(), false);
    }

    /**
     * Converts an array of 6 bytes to a long representing the MAC address.
     *
     * @param macArray byte array of mac values, must have length 6
     * @return Long value of the mac address.
     * @throws IllegalArgumentException for malformed inputs.
     */
    public static Long macAddressToLong(byte[] macArray) {
        if (macArray == null) {
            throw new IllegalArgumentException("null mac bytes");
        }
        if (macArray.length != MAC_LENGTH) {
            throw new IllegalArgumentException("invalid macArray length: " + macArray.length);
        }
        try {
            return ByteBufferReader.readInteger(
                    ByteBuffer.wrap(macArray), ByteOrder.BIG_ENDIAN, macArray.length);
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid macArray");
        }
    }

    /**
     * Remove enclosing quotes from the provided string.
     *
     * @param quotedStr String to be unquoted.
     * @return String without the enclosing quotes.
     */
    public static String removeEnclosingQuotes(String quotedStr) {
        int length = quotedStr.length();
        if ((length >= 2)
                && (quotedStr.charAt(0) == '"') && (quotedStr.charAt(length - 1) == '"')) {
            return quotedStr.substring(1, length - 1);
        }
        return quotedStr;
    }

    /**
     * Add enclosing quotes to the provided string.
     *
     * @param str String to be quoted.
     * @return String with the enclosing quotes.
     */
    public static String addEnclosingQuotes(String str) {
        return "\"" + str + "\"";
    }

    /**
     * Converts an string to an arraylist of UTF_8 byte values.
     * These forms are acceptable:
     * a) UTF-8 String encapsulated in quotes, or
     * b) Hex string with no delimiters.
     *
     * @param str String to be converted.
     * @throws IllegalArgumentException for null string.
     */
    public static ArrayList<Byte> hexOrQuotedStringToBytes(String str) {
        if (str == null) {
            throw new IllegalArgumentException("null string");
        }
        int length = str.length();
        if ((length > 1) && (str.charAt(0) == '"') && (str.charAt(length - 1) == '"')) {
            str = str.substring(1, str.length() - 1);
            return stringToByteArrayList(str);
        } else {
            return byteArrayToArrayList(hexStringToByteArray(str));
        }
    }

    /**
     * Converts an ArrayList<Byte> of UTF_8 byte values to string.
     * The string will either be:
     * a) UTF-8 String encapsulated in quotes (if all the bytes are UTF-8 encodeable and non null),
     * or
     * b) Hex string with no delimiters.
     *
     * @param bytes List of bytes for ssid.
     * @throws IllegalArgumentException for null bytes.
     */
    public static String bytesToHexOrQuotedString(ArrayList<Byte> bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("null ssid bytes");
        }
        byte[] byteArray = byteArrayFromArrayList(bytes);
        // Check for 0's in the byte stream in which case we cannot convert this into a string.
        if (!bytes.contains(Byte.valueOf((byte) 0))) {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            try {
                CharBuffer decoded = decoder.decode(ByteBuffer.wrap(byteArray));
                return "\"" + decoded.toString() + "\"";
            } catch (CharacterCodingException cce) {
            }
        }
        return hexStringFromByteArray(byteArray);
    }

    /**
     * Converts an ssid string to an arraylist of UTF_8 byte values.
     * These forms are acceptable:
     * a) UTF-8 String encapsulated in quotes, or
     * b) Hex string with no delimiters.
     *
     * @param ssidStr String to be converted.
     * @throws IllegalArgumentException for null string.
     */
    public static ArrayList<Byte> decodeSsid(String ssidStr) {
        ArrayList<Byte> ssidBytes = hexOrQuotedStringToBytes(ssidStr);
        if (ssidBytes.size() > SSID_BYTES_MAX_LEN) {
            throw new IllegalArgumentException("ssid bytes size out of range: " + ssidBytes.size());
        }
        return ssidBytes;
    }

    /**
     * Converts an ArrayList<Byte> of UTF_8 byte values to ssid string.
     * The string will either be:
     * a) UTF-8 String encapsulated in quotes (if all the bytes are UTF-8 encodeable and non null),
     * or
     * b) Hex string with no delimiters.
     *
     * @param ssidBytes List of bytes for ssid.
     * @throws IllegalArgumentException for null bytes.
     */
    public static String encodeSsid(ArrayList<Byte> ssidBytes) {
        if (ssidBytes.size() > SSID_BYTES_MAX_LEN) {
            throw new IllegalArgumentException("ssid bytes size out of range: " + ssidBytes.size());
        }
        return bytesToHexOrQuotedString(ssidBytes);
    }

    /**
     * Convert from an array of primitive bytes to an array list of Byte.
     */
    public static ArrayList<Byte> byteArrayToArrayList(byte[] bytes) {
        ArrayList<Byte> byteList = new ArrayList<>();
        for (Byte b : bytes) {
            byteList.add(b);
        }
        return byteList;
    }

    /**
     * Convert from an array list of Byte to an array of primitive bytes.
     */
    public static byte[] byteArrayFromArrayList(ArrayList<Byte> bytes) {
        byte[] byteArray = new byte[bytes.size()];
        int i = 0;
        for (Byte b : bytes) {
            byteArray[i++] = b;
        }
        return byteArray;
    }

    /**
     * Converts a hex string to byte array.
     *
     * @param hexStr String to be converted.
     * @throws IllegalArgumentException for null string.
     */
    public static byte[] hexStringToByteArray(String hexStr) {
        if (hexStr == null) {
            throw new IllegalArgumentException("null hex string");
        }
        return HexEncoding.decode(hexStr.toCharArray(), false);
    }

    /**
     * Converts a byte array to hex string.
     *
     * @param bytes List of bytes for ssid.
     * @throws IllegalArgumentException for null bytes.
     */
    public static String hexStringFromByteArray(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("null hex bytes");
        }
        return new String(HexEncoding.encode(bytes)).toLowerCase();
    }

    /**
     * Converts an 8 byte array to a WPS device type string
     * { 0, 1, 2, -1, 4, 5, 6, 7 } --> "1-02FF0405-1543";
     */
    public static String wpsDevTypeStringFromByteArray(byte[] devType) {
        byte[] a = devType;
        int x = ((a[0] & 0xFF) << 8) | (a[1] & 0xFF);
        String y = new String(HexEncoding.encode(Arrays.copyOfRange(devType, 2, 6)));
        int z = ((a[6] & 0xFF) << 8) | (a[7] & 0xFF);
        return String.format("%d-%s-%d", x, y, z);
    }
}
