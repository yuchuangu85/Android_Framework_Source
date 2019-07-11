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

import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.ByteBufferReader;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The Icons available OSU Providers sub field, as specified in
 * Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00,
 * section 4.8.1.4
 *
 * Format:
 *
 * | Width | Height | Language | Type Length | Type | Filename Length | Filename |
 *     2       2         3           1       variable        1          variable
 */
public class IconInfo {
    private static final int LANGUAGE_CODE_LENGTH = 3;

    private final int mWidth;
    private final int mHeight;
    private final String mLanguage;
    private final String mIconType;
    private final String mFileName;

    @VisibleForTesting
    public IconInfo(int width, int height, String language, String iconType, String fileName) {
        mWidth = width;
        mHeight = height;
        mLanguage = language;
        mIconType = iconType;
        mFileName = fileName;
    }

    /**
     * Parse a IconInfo from the given buffer.
     *
     * @param payload The buffer to read from
     * @return {@link IconInfo}
     * @throws BufferUnderflowException
     */
    public static IconInfo parse(ByteBuffer payload) {
        int width = (int) ByteBufferReader.readInteger(payload, ByteOrder.LITTLE_ENDIAN, 2)
                & 0xFFFF;
        int height = (int) ByteBufferReader.readInteger(payload, ByteOrder.LITTLE_ENDIAN, 2)
                & 0xFFFF;

        // Read the language string.
        String language = ByteBufferReader.readString(
                payload, LANGUAGE_CODE_LENGTH, StandardCharsets.US_ASCII).trim();

        String iconType =
                ByteBufferReader.readStringWithByteLength(payload, StandardCharsets.US_ASCII);
        String fileName =
                ByteBufferReader.readStringWithByteLength(payload, StandardCharsets.UTF_8);

        return new IconInfo(width, height, language, iconType, fileName);
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public String getLanguage() {
        return mLanguage;
    }

    public String getIconType() {
        return mIconType;
    }

    public String getFileName() {
        return mFileName;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof IconInfo)) {
            return false;
        }

        IconInfo that = (IconInfo) thatObject;
        return mWidth == that.mWidth
                && mHeight == that.mHeight
                && TextUtils.equals(mLanguage, that.mLanguage)
                && TextUtils.equals(mIconType, that.mIconType)
                && TextUtils.equals(mFileName, that.mFileName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mWidth, mHeight, mLanguage, mIconType, mFileName);
    }

    @Override
    public String toString() {
        return "IconInfo{"
                + "Width=" + mWidth
                + ", Height=" + mHeight
                + ", Language=" + mLanguage
                + ", IconType='" + mIconType + "\'"
                + ", FileName='" + mFileName + "\'"
                + "}";
    }
}
