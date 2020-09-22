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

package com.android.server.wifi.hotspot2.soap.command;

import android.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import org.ksoap2.serialization.PropertyInfo;
import org.ksoap2.serialization.SoapPrimitive;

import java.util.Objects;

/**
 * Represents PPS (PerProviderSubscription) MO (Management Object) defined by SPP (Subscription
 * Provisioning Protocol).
 */
public class PpsMoData implements SppCommand.SppCommandData {
    @VisibleForTesting
    public static final String ADD_MO_COMMAND = "addMO";
    @VisibleForTesting
    public static final String ATTRIBUTE_MANAGEMENT_TREE_URI = "managementTreeURI";
    @VisibleForTesting
    public static final String ATTRIBUTE_MO_URN = "moURN";

    private static final String TAG = "PasspointPpsMoData";
    private final String mBaseUri;
    private final String mUrn;
    private final String mPpsMoTree;

    private PpsMoData(String baseUri, String urn, String ppsMoTree) {
        mBaseUri = baseUri;
        mUrn = urn;
        mPpsMoTree = ppsMoTree;
    }

    /**
     * Create an instance of {@link PpsMoData}
     *
     * @param command command message embedded in SOAP sppPostDevDataResponse.
     * @return instance of {@link PpsMoData}, {@code null} in any failure.
     */
    public static PpsMoData createInstance(@NonNull PropertyInfo command) {
        if (command == null || command.getValue() == null) {
            Log.e(TAG, "command message is null");
            return null;
        }

        if (!TextUtils.equals(command.getName(), ADD_MO_COMMAND)) {
            Log.e(TAG, "the response is not for addMO command");
            return null;
        }

        if (!(command.getValue() instanceof SoapPrimitive)) {
            Log.e(TAG, "the addMO element is not valid format");
            return null;
        }

        SoapPrimitive soapObject = (SoapPrimitive) command.getValue();
        if (!soapObject.hasAttribute(ATTRIBUTE_MANAGEMENT_TREE_URI)) {
            Log.e(TAG, "managementTreeURI Attribute is missing");
            return null;
        }

        if (!soapObject.hasAttribute(ATTRIBUTE_MO_URN)) {
            Log.e(TAG, "moURN Attribute is missing");
            return null;
        }

        if (soapObject.getValue() == null) {
            Log.e(TAG, "PPSMO Tree is missing");
            return null;
        }

        return new PpsMoData(
                (String) soapObject.getAttributeSafelyAsString(ATTRIBUTE_MANAGEMENT_TREE_URI),
                (String) soapObject.getAttributeSafelyAsString(ATTRIBUTE_MO_URN),
                soapObject.getValue().toString());
    }

    /**
     * Get PPS (PerProviderSubscription) MO (Management Object) with XML format.
     *
     * @return PPS MO Tree
     */
    public String getPpsMoTree() {
        return mPpsMoTree;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) return true;
        if (thatObject == null) return false;
        if (!(thatObject instanceof PpsMoData)) return false;
        PpsMoData ppsMoData = (PpsMoData) thatObject;
        return TextUtils.equals(mBaseUri, ppsMoData.mBaseUri)
                && TextUtils.equals(mUrn, ppsMoData.mUrn)
                && TextUtils.equals(mPpsMoTree, ppsMoData.mPpsMoTree);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mBaseUri, mUrn, mPpsMoTree);
    }

    @Override
    public String toString() {
        return "PpsMoData{Base URI: " + mBaseUri + ", MOURN: " + mUrn + ", PPS MO: " + mPpsMoTree
                + "}";
    }
}
