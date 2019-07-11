package com.android.server.wifi.hotspot2.omadm;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class XMLNode {
    private final String mTag;
    private final Map<String, NodeAttribute> mAttributes;
    private final List<XMLNode> mChildren;
    private final XMLNode mParent;
    private MOTree mMO;
    private StringBuilder mTextBuilder;
    private String mText;

    public XMLNode(XMLNode parent, String tag, Attributes attributes) throws SAXException {
        mTag = tag;

        mAttributes = new HashMap<String, NodeAttribute>();

        if (attributes.getLength() > 0) {
            for (int n = 0; n < attributes.getLength(); n++)
                mAttributes.put(attributes.getQName(n), new NodeAttribute(attributes.getQName(n),
                        attributes.getType(n), attributes.getValue(n)));
        }

        mParent = parent;
        mChildren = new ArrayList<XMLNode>();

        mTextBuilder = new StringBuilder();
    }

    public void addText(char[] chs, int start, int length) {
        String s = new String(chs, start, length);
        String trimmed = s.trim();
        if (trimmed.isEmpty())
            return;

        if (s.charAt(0) != trimmed.charAt(0))
            mTextBuilder.append(' ');
        mTextBuilder.append(trimmed);
        if (s.charAt(s.length() - 1) != trimmed.charAt(trimmed.length() - 1))
            mTextBuilder.append(' ');
    }

    public void addChild(XMLNode child) {
        mChildren.add(child);
    }

    public void close() throws IOException, SAXException {
        String text = mTextBuilder.toString().trim();
        StringBuilder filtered = new StringBuilder(text.length());
        for (int n = 0; n < text.length(); n++) {
            char ch = text.charAt(n);
            if (ch >= ' ')
                filtered.append(ch);
        }

        mText = filtered.toString();
        mTextBuilder = null;

        if (OMAConstants.isMOContainer(mTag)) {
            NodeAttribute urn = mAttributes.get(OMAConstants.ATTR_URN);
            OMAParser omaParser = new OMAParser();
            mMO = omaParser.parse(mText, urn.getValue());
        }
    }

    public String getTag() {
        return mTag;
    }

    public XMLNode getParent() {
        return mParent;
    }

    public String getText() {
        return mText;
    }

    public Map<String, NodeAttribute> getAttributes() {
        return Collections.unmodifiableMap(mAttributes);
    }

    public String getAttributeValue(String name) {
        NodeAttribute nodeAttribute = mAttributes.get(name);
        return nodeAttribute != null ? nodeAttribute.getValue() : null;
    }

    public List<XMLNode> getChildren() {
        return mChildren;
    }

    public MOTree getMOTree() {
        return mMO;
    }

    private void toString(char[] indent, StringBuilder sb) {
        Arrays.fill(indent, ' ');

        sb.append(indent).append('<').append(mTag).append("> ").append(mAttributes.values());

        if (mMO != null)
            sb.append('\n').append(mMO);
        else if (!mText.isEmpty())
            sb.append(", text: ").append(mText);

        sb.append('\n');

        char[] subIndent = Arrays.copyOf(indent, indent.length + 2);
        for (XMLNode child : mChildren)
            child.toString(subIndent, sb);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        toString(new char[0], sb);
        return sb.toString();
    }
}
