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

import android.annotation.NonNull;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.StringWriter;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Provides serialization API for OMA DM Management Object.
 */
class MoSerializer {
    static final String TAG = "MoSerializable";
    static final String DM_VERSION = "1.2";
    static final String TAG_MGMT_TREE = "MgmtTree";
    static final String TAG_VERSION = "VerDTD";
    static final String TAG_NODE = "Node";
    static final String TAG_NODENAME = "NodeName";
    static final String TAG_PATH = "Path";
    static final String TAG_VALUE = "Value";
    static final String TAG_RTPROPERTIES = "RTProperties";
    static final String TAG_TYPE = "Type";
    static final String TAG_DDF_NAME = "DDFName";
    private DocumentBuilder mDbBuilder;

    public MoSerializer() throws ParserConfigurationException {
        mDbBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    }

    /**
     * Serializes the Management Object into a XML format.
     *
     * @return {@link String) that indicates the entire xml of {@link MoSerializer}},
     * <code>null<code/> in case of failure.
     */
    String serialize(@NonNull Document doc) {
        StringWriter writer = new StringWriter();
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
        } catch (TransformerException e) {
            e.printStackTrace();
            return null;
        }
        return writer.toString();
    }

    /**
     * Create new Document to make a XML document of OMA DM Management Object.
     *
     * @return new {@link Document}
     */
    Document createNewDocument() {
        return mDbBuilder.newDocument();
    }

    /**
     * Generates root Node starting a "MgmtTree".
     *
     * Expected output: <MgmtTree></MgmtTree>
     * @param doc {@link Document} that indicates an entire xml document.
     * @return {@link Element} for Root
     */
    Element createMgmtTree(@NonNull Document doc) {
        // root element
        Element rootElement = doc.createElement(TAG_MGMT_TREE);
        doc.appendChild(rootElement);
        return rootElement;
    }

    /**
     * Generates DTD version Node.
     *
     * Expected output: <VerDTD>[value]</VerDTD>
     * @param doc {@link Document} that indicates an entire xml document.
     * @return {@link Element} for new Node
     */
    Element writeVersion(@NonNull Document doc) {
        Element dtdElement = doc.createElement(TAG_VERSION);
        dtdElement.appendChild(doc.createTextNode(DM_VERSION));
        return dtdElement;
    }

    /**
     * Generates Node having {@param nodeName}
     *
     * Expected output: <Node><NodeName>[nodeName]</NodeName></Node>
     * @param doc {@link Document} that indicates an entire xml document.
     * @param nodeName node name for new node.
     * @return {@link Element} for new Node
     */
    Element createNode(@NonNull Document doc, @NonNull String nodeName) {
        Element node = doc.createElement(TAG_NODE);
        Element nameNode = doc.createElement(TAG_NODENAME);
        nameNode.appendChild(doc.createTextNode(nodeName));
        node.appendChild(nameNode);
        return node;
    }

    /** Generates Node with the Unique Resource Name (URN).
     *
     * URN is encapsulated inside RTProperties node.
     *
     * Expected output: <RTProperties><Type><DDFName>[urn]</DDFName></Type></RTProperites>
     * @param doc {@link Document} that indicates an entire xml document.
     * @param urn The unique resource name
     *
     * @return {@link Element} for new Node
     */
    Element createNodeForUrn(@NonNull Document doc, @NonNull String urn) {
        Element node = doc.createElement(TAG_RTPROPERTIES);
        Element type = doc.createElement(TAG_TYPE);
        Element ddfName = doc.createElement(TAG_DDF_NAME);
        ddfName.appendChild(doc.createTextNode(urn));
        type.appendChild(ddfName);
        node.appendChild(type);
        return node;
    }

    /**
     * Generates for a node with a value.
     *
     * Expected output:
     * <Node><NodeName>[name]</NodeName><Value>[value]</Value></Node>
     * @param doc {@link Document} that indicates an entire xml document.
     * @param name name of the node.
     * @param value value of the node
     *
     * @return {@link Element} for new Node
     */
    Element createNodeForValue(@NonNull Document doc, @NonNull String name, @NonNull String value) {
        Element node = doc.createElement(TAG_NODE);
        Element nameNode = doc.createElement(TAG_NODENAME);
        nameNode.appendChild(doc.createTextNode(name));
        node.appendChild(nameNode);

        Element valueNode = doc.createElement(TAG_VALUE);
        valueNode.appendChild(doc.createTextNode(value));

        node.appendChild(valueNode);
        return node;
    }
}