/*
 * Copyright (C) 2016 The Android Open Source Project
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

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A generic Internationalized name field used in the Operator Friendly Name ANQP element
 * (see HS2.0 R2 Spec 4.2) and the Venue Name ANQP element (see 802.11-2012 8.4.4.4).
 *
 * Format:
 *
 * | Length | Language Code |   Name   |
 *      1           3         variable
 */
public class I18Name {
    @VisibleForTesting
    public static final int LANGUAGE_CODE_LENGTH = 3;

    @VisibleForTesting
    public static final int MINIMUM_LENGTH = LANGUAGE_CODE_LENGTH;

    private final String mLanguage;
    private final Locale mLocale;
    private final String mText;

    @VisibleForTesting
    public I18Name(String language, Locale locale, String text) {
        mLanguage = language;
        mLocale = locale;
        mText = text;
    }

    /**
     * Parse a I18Name from the given buffer.
     *
     * @param payload The byte buffer to read from
     * @return {@link I18Name}
     * @throws BufferUnderflowException
     * @throws ProtocolException
     */
    public static I18Name parse(ByteBuffer payload) throws ProtocolException {
        // Retrieve the length field.
        int length = payload.get() & 0xFF;

        // Check for the minimum required length.
        if (length < MINIMUM_LENGTH) {
            throw new ProtocolException("Invalid length: " + length);
        }

        // Read the language string.
        String language = ByteBufferReader.readString(
                payload, LANGUAGE_CODE_LENGTH, StandardCharsets.US_ASCII).trim();
        Locale locale;
        try {
            // The language code is a two or three character language code defined in ISO-639.
            locale = new Locale.Builder().setLanguage(language).build();
        } catch (Exception e) {
            throw new ProtocolException("Invalid language: " + language);
        }
        // Read the text string.
        String text = ByteBufferReader.readString(payload, length - LANGUAGE_CODE_LENGTH,
                StandardCharsets.UTF_8);
        return new I18Name(language, locale, text);
    }

    public String getLanguage() {
        return mLanguage;
    }

    public Locale getLocale() {
        return mLocale;
    }

    public String getText() {
        return mText;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof I18Name)) {
            return false;
        }

        I18Name that = (I18Name) thatObject;
        return TextUtils.equals(mLanguage, that.mLanguage)
                && TextUtils.equals(mText, that.mText);
    }

    @Override
    public int hashCode() {
        int result = mLanguage.hashCode();
        result = 31 * result + mText.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return mText + ':' + mLocale.getLanguage();
    }
}
