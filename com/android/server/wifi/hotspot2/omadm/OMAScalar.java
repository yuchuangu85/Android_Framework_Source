package com.android.server.wifi.hotspot2.omadm;

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Iterator;

public class OMAScalar extends OMANode {
    private final String mValue;

    public OMAScalar(OMANode parent, String name, String context, String value) {
        super(parent, name, context);
        mValue = value;
    }

    public String getScalarValue(Iterator<String> path) throws OMAException {
        return mValue;
    }

    @Override
    public OMAConstructed getListValue(Iterator<String> path) throws OMAException {
        throw new OMAException("Scalar encountered in list path: " + getPathString());
    }

    @Override
    public boolean isLeaf() {
        return true;
    }

    @Override
    public Collection<OMANode> getChildren() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getValue() {
        return mValue;
    }

    @Override
    public OMANode getChild(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public OMANode addChild(String name, String context, String value, String path)
            throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void toString(StringBuilder sb, int level) {
        sb.append(getPathString()).append('=').append(mValue);
        if (getContext() != null) {
            sb.append(" (").append(getContext()).append(')');
        }
        sb.append('\n');
    }

    @Override
    public void marshal(OutputStream out, int level) throws IOException {
        OMAConstants.indent(level, out);
        OMAConstants.serializeString(getName(), out);
        out.write((byte) '=');
        OMAConstants.serializeString(getValue(), out);
        out.write((byte) '\n');
    }
}
