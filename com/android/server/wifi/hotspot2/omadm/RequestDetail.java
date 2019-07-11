package com.android.server.wifi.hotspot2.omadm;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

public class RequestDetail {
    private final String mSppversion;
    private final String mRedirectURI;
    private final String mRequestReason;
    private final String mSessionID;
    private final String[] mSupportedVersions;
    private final String[] mSupportedMOs;
    private final Collection<MOTree> m_MOs;

    public enum RequestFields {
        SPPVersion,
        RedirectURI,
        RequestReason,
        SessionID,
        SupportedVersions,
        SupportedMOs
    }

    public RequestDetail(Map<RequestFields, String> values, Collection<MOTree> mos) {
        mSppversion = values.get(RequestFields.SPPVersion);
        mRedirectURI = values.get(RequestFields.RedirectURI);
        mRequestReason = values.get(RequestFields.RequestReason);
        mSessionID = values.get(RequestFields.SessionID);
        mSupportedVersions = split(values.get(RequestFields.SupportedVersions));
        mSupportedMOs = split(values.get(RequestFields.SupportedMOs));
        m_MOs = mos;
    }

    public Collection<MOTree> getMOs() {
        return m_MOs;
    }

    private static String[] split(String list) {
        return list != null ? list.split("[ \n\r]+") : null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("SPPVersion").append(" = '").append(mSppversion).append("'\n");
        sb.append("RedirectURI").append(" = '").append(mRedirectURI).append("'\n");
        sb.append("RequestReason").append(" = '").append(mRequestReason).append("'\n");
        sb.append("SessionID").append(" = '").append(mSessionID).append("'\n");
        sb.append("SupportedVersions").append(" = ").append(Arrays.toString(mSupportedVersions))
                .append('\n');
        sb.append("SupportedMOs").append(" = ").append(Arrays.toString(mSupportedMOs)).append('\n');
        sb.append("MOs:\n");
        for (MOTree mo : m_MOs)
            sb.append(mo);

        return sb.toString();
    }
}
