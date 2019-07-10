package com.android.server.wifi.hotspot2.omadm;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import java.io.*;
import java.util.*;

import static com.android.server.wifi.hotspot2.omadm.RequestDetail.RequestFields.*;

/**
 * This is an incomplete SOAP-XML parser for OSU data needing enhancements for r2.
 */

public class SOAPParser extends DefaultHandler {
    private XMLNode mRoot;
    private XMLNode mCurrent;

    private static String[] TagOnly = new String[0];
    private static final Map<RequestDetail.RequestFields, String> sSoapMappings =
            new EnumMap<RequestDetail.RequestFields, String>(RequestDetail.RequestFields.class);
    private static final Map<String, RequestDetail.RequestFields> sRevMappings =
            new HashMap<String, RequestDetail.RequestFields>();
    private static final Map<String, String[]> sSoapAttributes =
            new HashMap<String, String[]>();

    static {
        sSoapMappings.put(SPPVersion, "spp:sppVersion");
        sSoapMappings.put(RedirectURI, "redirectURI");
        sSoapMappings.put(RequestReason, "requestReason");
        sSoapMappings.put(SessionID, "spp:sessionID");
        sSoapMappings.put(SupportedVersions, "spp:supportedSPPVersions");
        sSoapMappings.put(SupportedMOs, "spp:supportedMOList");

        for (Map.Entry<RequestDetail.RequestFields, String> entry : sSoapMappings.entrySet()) {
            sRevMappings.put(entry.getValue(), entry.getKey());
        }

        // !!! Really: The first element inside the body
        sSoapAttributes.put("spp:sppPostDevDataResponse", new String[]{
                sSoapMappings.get(SPPVersion),
                sSoapMappings.get(RedirectURI),
                sSoapMappings.get(RequestReason),
                sSoapMappings.get(SessionID)});

        sSoapAttributes.put(sSoapMappings.get(SupportedVersions), TagOnly);
        sSoapAttributes.put(sSoapMappings.get(SupportedMOs), TagOnly);
    }

    public XMLNode parse(File file) throws IOException, ParserConfigurationException, SAXException {
        SAXParser parser = SAXParserFactory.newInstance().newSAXParser();

        BufferedInputStream in = new BufferedInputStream(new FileInputStream(file));
        try {
            parser.parse(in, this);
        } finally {
            in.close();
        }
        return mRoot;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes)
            throws SAXException {
        XMLNode parent = mCurrent;

        mCurrent = new XMLNode(mCurrent, qName, attributes);
        System.out.println("Added " + mCurrent.getTag() + ", atts " + mCurrent.getAttributes());

        if (mRoot == null)
            mRoot = mCurrent;
        else
            parent.addChild(mCurrent);
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        if (!qName.equals(mCurrent.getTag()))
            throw new SAXException("End tag '" + qName + "' doesn't match current node: " +
                    mCurrent);

        try {
            mCurrent.close();
        } catch (IOException ioe) {
            throw new SAXException("Failed to close element", ioe);
        }

        mCurrent = mCurrent.getParent();
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        mCurrent.addText(ch, start, length);
    }

    public RequestDetail getRequestDetail() {
        Map<RequestDetail.RequestFields, String> values =
                new EnumMap<RequestDetail.RequestFields, String>(RequestDetail.RequestFields.class);
        List<MOTree> mos = new ArrayList<MOTree>();
        extractFields(mRoot, values, mos);
        return new RequestDetail(values, mos);
    }

    private static void extractFields(XMLNode node, Map<RequestDetail.RequestFields,
            String> values, Collection<MOTree> mos) {
        String[] attributes = sSoapAttributes.get(node.getTag());

        if (attributes != null) {
            if (attributes.length == 0) {
                RequestDetail.RequestFields field = sRevMappings.get(node.getTag());
                values.put(field, node.getText());
            } else {
                for (String attribute : attributes) {
                    RequestDetail.RequestFields field = sRevMappings.get(attribute);
                    if (field != null) {
                        String value = node.getAttributeValue(attribute);

                        if (value != null)
                            values.put(field, value);
                    }
                }
            }
        }

        if (node.getMOTree() != null)
            mos.add(node.getMOTree());

        for (XMLNode child : node.getChildren()) {
            extractFields(child, values, mos);
        }
    }

    public static void main(String[] args) throws Exception {
        SOAPParser soapParser = new SOAPParser();
        XMLNode root = soapParser.parse(new File(args[0]));
        //System.out.println( root );
        System.out.println(soapParser.getRequestDetail());
        System.out.println("Marshalled: ");
        for (MOTree mo : soapParser.getRequestDetail().getMOs()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            mo.marshal(out);
            System.out.println(out.toString());
            MOTree back = MOTree.unmarshal(new ByteArrayInputStream(out.toByteArray()));
            System.out.println(back);
        }
        System.out.println("---");
    }
}
