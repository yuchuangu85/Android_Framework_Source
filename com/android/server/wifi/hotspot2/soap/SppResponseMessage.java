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

package com.android.server.wifi.hotspot2.soap;

import android.annotation.NonNull;
import android.text.TextUtils;

import org.ksoap2.serialization.AttributeInfo;
import org.ksoap2.serialization.SoapObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Base SOAP SPP (Subscription Provisioning Protocol) response message sent by server
 */
public class SppResponseMessage {
    static final String SPPVersionAttribute = "sppVersion";
    static final String SPPStatusAttribute = "sppStatus";
    static final String SPPSessionIDAttribute = "sessionID";
    static final String SPPErrorCodeAttribute = "errorCode";
    static final String SPPErrorProperty = "sppError";

    private final int mMessageType;
    private final String mVersion;
    private final String mSessionID;
    private int mStatus;
    private int mError = SppConstants.INVALID_SPP_CONSTANT;
    private Map<String, String> mAttributes;

    /**
     * Message types of SOAP SPP response.
     */
    public static class MessageType {
        /* SOAP method response from the subscription server */
        public static final int POST_DEV_DATA_RESPONSE = 0;

        /* Message exchange sequence has been completed and the TLS connection should be released */
        public static final int EXCHANGE_COMPLETE = 1;
    }

    protected SppResponseMessage(@NonNull SoapObject response, int messageType)
            throws IllegalArgumentException {
        if (!response.hasAttribute(SPPStatusAttribute)) {
            throw new IllegalArgumentException("Missing status");
        }

        mMessageType = messageType;
        mStatus = SppConstants.mapStatusStringToInt(
                response.getAttributeAsString(SPPStatusAttribute));
        if (!response.hasAttribute(SPPVersionAttribute) || !response.hasAttribute(
                SPPSessionIDAttribute) || mStatus == SppConstants.INVALID_SPP_CONSTANT) {
            throw new IllegalArgumentException("Incomplete request: " + messageType);
        }

        // Validation check for error status
        if (mStatus == SppConstants.SppStatus.ERROR) {
            if (!response.hasProperty(SPPErrorProperty)) {
                throw new IllegalArgumentException("Missing sppError");
            }
        }

        if (response.hasProperty(SPPErrorProperty)) {
            SoapObject errorInfo = (SoapObject) response.getProperty(SPPErrorProperty);
            if (!errorInfo.hasAttribute(SPPErrorCodeAttribute)) {
                throw new IllegalArgumentException("Missing errorCode");
            }
            mError = SppConstants.mapErrorStringToInt(
                    errorInfo.getAttributeAsString(SPPErrorCodeAttribute));
        }

        mSessionID = response.getAttributeAsString(SPPSessionIDAttribute);
        mVersion = response.getAttributeAsString(SPPVersionAttribute);
        if (response.getAttributeCount() > 0) {
            mAttributes = new HashMap<>();
            for (int i = 0; i < response.getAttributeCount(); i++) {
                AttributeInfo attributeInfo = new AttributeInfo();
                response.getAttributeInfo(i, attributeInfo);
                mAttributes.put(attributeInfo.getName(),
                        response.getAttributeAsString(attributeInfo.getName()));

            }
        }
    }

    public int getMessageType() {
        return mMessageType;
    }

    public String getVersion() {
        return mVersion;
    }

    public String getSessionID() {
        return mSessionID;
    }

    public int getStatus() {
        return mStatus;
    }

    public int getError() {
        return mError;
    }

    protected final Map<String, String> getAttributes() {
        return Collections.unmodifiableMap(mAttributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMessageType, mVersion, mSessionID, mStatus, mError, mAttributes);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) return true;
        if (!(thatObject instanceof SppResponseMessage)) return false;
        SppResponseMessage that = (SppResponseMessage) thatObject;
        return mMessageType == that.mMessageType
                && mStatus == that.mStatus
                && mError == that.mError
                && TextUtils.equals(mVersion, that.mVersion)
                && TextUtils.equals(mSessionID, that.mSessionID)
                && ((mAttributes == null) ? (that.mAttributes == null) : mAttributes.equals(
                that.mAttributes));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(mMessageType);
        sb.append(", version ").append(mVersion);
        sb.append(", status ").append(mStatus);
        sb.append(", session-id ").append(mSessionID);
        if (mError != SppConstants.INVALID_SPP_CONSTANT) {
            sb.append(", error ").append(mError);
        }
        return sb.toString();
    }
}

