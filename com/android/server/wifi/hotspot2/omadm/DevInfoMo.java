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

package com.android.server.wifi.hotspot2.omadm;

import android.util.Log;

import com.android.server.wifi.hotspot2.SystemInfo;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.ParserConfigurationException;

import android.annotation.NonNull;

/**
 * Provides serialization API for DevInfo MO (Management Object).
 *
 * Devinfo---|- DevId
 *           |- Man
 *           |- Mod
 *           |- Dmv
 *           |- Lang
 */
public class DevInfoMo {
    public static final String TAG = "DevInfoMo";
    public static final String URN = "urn:oma:mo:oma-dm-devinfo:1.0";

    private static final String MO_NAME = "DevInfo";
    private static final String TAG_DEVID = "DevID";
    private static final String TAG_MANUFACTURE = "Man";
    private static final String TAG_MODEL = "Mod";
    private static final String TAG_DM_VERSION = "DmV";
    private static final String TAG_LANGUAGE = "Lang";

    /**
     * Make a format of XML based on the DDF(Data Definition Format) of DevInfo MO.
     *
     * @return the XML that has format of OMA DM DevInfo Management Object, <code>null</code> in
     * case of any failure.
     */
    public static String serializeToXml(@NonNull SystemInfo systemInfo) {
        MoSerializer moSerializer;
        try {
            moSerializer = new MoSerializer();
        } catch (ParserConfigurationException e) {
            Log.e(TAG, "failed to create the MoSerializer: " + e);
            return null;
        }
        // Create the XML document for DevInfoMo
        Document doc = moSerializer.createNewDocument();
        Element rootElement = moSerializer.createMgmtTree(doc);
        rootElement.appendChild(moSerializer.writeVersion(doc));
        Element moNode = moSerializer.createNode(doc, MO_NAME);
        moNode.appendChild(moSerializer.createNodeForUrn(doc, URN));
        rootElement.appendChild(moNode);
        rootElement.appendChild(
                moSerializer.createNodeForValue(doc, TAG_DEVID, systemInfo.getDeviceId()));

        rootElement.appendChild(moSerializer.createNodeForValue(doc, TAG_MANUFACTURE,
                systemInfo.getDeviceManufacturer()));
        rootElement.appendChild(
                moSerializer.createNodeForValue(doc, TAG_MODEL, systemInfo.getDeviceModel()));
        rootElement.appendChild(
                moSerializer.createNodeForValue(doc, TAG_DM_VERSION, MoSerializer.DM_VERSION));
        rootElement.appendChild(
                moSerializer.createNodeForValue(doc, TAG_LANGUAGE, systemInfo.getLanguage()));

        return moSerializer.serialize(doc);
    }
}