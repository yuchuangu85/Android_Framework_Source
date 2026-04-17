/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.protolog.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a type of logged data encoded in the proto.
 */
public class LogDataType {
    // When updating this list make sure to update bitmask conversion methods accordingly.
    // STR type should be the first in the enum in order to be the default type.
    public static final int STRING = 0b00;
    public static final int LONG = 0b01;
    public static final int DOUBLE = 0b10;
    public static final int BOOLEAN = 0b11;

    private static final int TYPE_WIDTH = 2;
    private static final int TYPE_MASK = 0b11;

    /**
     * Creates a bitmask representing a list of data types.
     */
    public static long logDataTypesToBitMask(List<Integer> types) {
        if (types.size() > 32) {
            throw new BitmaskConversionException("Too many log call parameters "
                    + "- max 32 parameters supported");
        }
        long mask = 0L;
        for (int i = 0; i < types.size(); i++) {
            long x = types.get(i);
            mask = mask | (x << (i * TYPE_WIDTH));
        }
        return mask;
    }

    /**
     * Decodes a bitmask to a list of LogDataTypes of provided length.
     */
    public static int bitmaskToLogDataType(long bitmask, int index) {
        if (index < 0 || index > 32) {
            throw new BitmaskConversionException("Index out of bounds. Max 32 parameters allowed");
        }
        return (int) (bitmask >> (index * TYPE_WIDTH)) & TYPE_MASK;
    }

    /**
     * Creates a list of LogDataTypes from a message format string.
     */
    public static List<Integer> parseFormatString(String messageString) {
        ArrayList<Integer> types = new ArrayList<>();
        for (int i = 0; i < messageString.length(); ) {
            if (messageString.charAt(i) == '%') {
                if (i + 1 >= messageString.length()) {
                    throw new InvalidFormatStringException("Invalid format string in config");
                }
                switch (messageString.charAt(i + 1)) {
                    case 'b':
                        types.add(LogDataType.BOOLEAN);
                        break;
                    case 'd':
                    case 'x':
                        types.add(LogDataType.LONG);
                        break;
                    case 'f':
                        types.add(LogDataType.DOUBLE);
                        break;
                    case 's':
                        types.add(LogDataType.STRING);
                        break;
                    case '%':
                        break;
                    default:
                        throw new InvalidFormatStringException("Invalid Protolog message format in "
                                + "\"" + messageString + "\" at index " + i + ".");
                }
                i += 2;
            } else {
                i += 1;
            }
        }
        return types;
    }
}
