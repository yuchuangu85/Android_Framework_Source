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

package com.android.server.wifi.hotspot2.anqp.eap;

import com.android.internal.annotations.VisibleForTesting;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An EAP Method part of the NAI Realm ANQP element, specified in
 * IEEE802.11-2012 section 8.4.4.10, figure 8-420
 *
 * Format:
 * | Length | EAP Method | Auth Param Count | Auth Param #1 (optional) | ....
 *     1          1               1                 variable
 */
public class EAPMethod {
    private final int mEAPMethodID;
    private final Map<Integer, Set<AuthParam>> mAuthParams;

    @VisibleForTesting
    public EAPMethod(int methodID, Map<Integer, Set<AuthParam>> authParams) {
        mEAPMethodID = methodID;
        mAuthParams = authParams;
    }

    /**
     * Parse a EAPMethod from the given buffer.
     *
     * @param payload The byte buffer to read from
     * @return {@link EAPMethod}
     * @throws ProtocolException
     * @throws BufferUnderflowException
     */
    public static EAPMethod parse(ByteBuffer payload) throws ProtocolException {
        // Read and verify the length field.
        int length = payload.get() & 0xFF;
        if (length > payload.remaining()) {
            throw new ProtocolException("Invalid data length: " + length);
        }

        int methodID = payload.get() & 0xFF;
        int authCount = payload.get() & 0xFF;
        Map<Integer, Set<AuthParam>> authParams = new HashMap<>();
        while (authCount > 0) {
            addAuthParam(authParams, parseAuthParam(payload));
            authCount--;
        }
        return new EAPMethod(methodID, authParams);
    }

    /**
     * Parse a AuthParam from the given buffer.
     *
     * Format:
     * | Auth ID | Length | Value |
     *      1         1    variable
     *
     * @param payload The byte buffer to read from
     * @return {@link AuthParam}
     * @throws BufferUnderflowException
     * @throws ProtocolException
     */
    private static AuthParam parseAuthParam(ByteBuffer payload) throws ProtocolException {
        int authID = payload.get() & 0xFF;
        int length = payload.get() & 0xFF;
        switch (authID) {
            case AuthParam.PARAM_TYPE_EXPANDED_EAP_METHOD:
                return ExpandedEAPMethod.parse(payload, length, false);
            case AuthParam.PARAM_TYPE_NON_EAP_INNER_AUTH_TYPE:
                return NonEAPInnerAuth.parse(payload, length);
            case AuthParam.PARAM_TYPE_INNER_AUTH_EAP_METHOD_TYPE:
                return InnerAuthEAP.parse(payload, length);
            case AuthParam.PARAM_TYPE_EXPANDED_INNER_EAP_METHOD:
                return ExpandedEAPMethod.parse(payload, length, true);
            case AuthParam.PARAM_TYPE_CREDENTIAL_TYPE:
                return CredentialType.parse(payload, length, false);
            case AuthParam.PARAM_TYPE_TUNNELED_EAP_METHOD_CREDENTIAL_TYPE:
                return CredentialType.parse(payload, length, true);
            case AuthParam.PARAM_TYPE_VENDOR_SPECIFIC:
                return VendorSpecificAuth.parse(payload, length);
            default:
                throw new ProtocolException("Unknow Auth Type ID: " + authID);
        }
    }

    /**
     * Add an AuthParam to a map of authentication parameters.  It is possible to have
     * multiple authentication parameters for the same type.
     *
     * @param paramsMap The authentication parameter map to add the new parameter to
     * @param authParam The authentication parameter to add
     */
    private static void addAuthParam(Map<Integer, Set<AuthParam>> paramsMap,
            AuthParam authParam) {
        Set<AuthParam> authParams = paramsMap.get(authParam.getAuthTypeID());
        if (authParams == null) {
            authParams = new HashSet<>();
            paramsMap.put(authParam.getAuthTypeID(), authParams);
        }
        authParams.add(authParam);
    }

    public Map<Integer, Set<AuthParam>> getAuthParams() {
        return Collections.unmodifiableMap(mAuthParams);
    }

    public int getEAPMethodID() {
        return mEAPMethodID;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (thatObject == this) {
            return true;
        }
        if (!(thatObject instanceof EAPMethod)) {
            return false;
        }
        EAPMethod that = (EAPMethod) thatObject;
        return mEAPMethodID == that.mEAPMethodID && mAuthParams.equals(that.mAuthParams);
    }

    @Override
    public int hashCode() {
        return mEAPMethodID * 31 + mAuthParams.hashCode();
    }

    @Override
    public String toString() {
        return "EAPMethod{mEAPMethodID=" + mEAPMethodID + " mAuthParams=" + mAuthParams + "}";
    }
}
