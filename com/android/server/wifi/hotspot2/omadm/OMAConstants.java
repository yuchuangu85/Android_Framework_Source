package com.android.server.wifi.hotspot2.omadm;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class OMAConstants {
    private OMAConstants() {
    }

    public static final String TAG_PostDevData = "spp:sppPostDevData";
    public static final String TAG_SupportedVersions = "spp:supportedSPPVersions";
    public static final String TAG_SupportedMOs = "spp:supportedMOList";

    public static final String TAG_MO_Add = "spp:addMO";
    public static final String TAG_MO_Container = "spp:moContainer";

    public static final String ATTR_URN = "spp:moURN";

    // Following strings excludes the trailing version number (e.g. :1.0)
    public static final String LOC_PPS = "urn:wfa:mo:hotspot2dot0-perprovidersubscription";
    public static final String LOC_DEVINFO =
            "urn:oma:mo:oma-dm-devinfo:1.0 urn:oma:mo:oma-dm-devdetail";
    public static final String LOC_DEVDETAIL = "urn:wfa:mo-ext:hotspot2dot0-devdetail-ext";

    public static final String SyncMLVersionTag = "VerDTD";
    public static final String RequiredSyncMLVersion = "1.2";

    private static final Set<String> sMOContainers = new HashSet<String>();

    static {
        sMOContainers.add(TAG_MO_Add);
        sMOContainers.add(TAG_MO_Container);
    }

    public static boolean isMOContainer(String tag) {
        return sMOContainers.contains(tag);
    }

    private static final byte[] INDENT = new byte[1024];

    static {
        Arrays.fill(INDENT, (byte) ' ');
    }

    public static void serializeString(String s, OutputStream out) throws IOException {
        byte[] octets = s.getBytes(StandardCharsets.UTF_8);
        byte[] prefix = String.format("%x:", octets.length).getBytes(StandardCharsets.UTF_8);
        out.write(prefix);
        out.write(octets);
    }

    public static void indent(int level, OutputStream out) throws IOException {
        out.write(INDENT, 0, level);
    }

    public static String deserializeString(InputStream in) throws IOException {
        StringBuilder prefix = new StringBuilder();
        for (; ; ) {
            byte b = (byte) in.read();
            if (b == '.')
                return null;
            else if (b == ':')
                break;
            else if (b > ' ')
                prefix.append((char) b);
        }
        int length = Integer.parseInt(prefix.toString(), 16);
        byte[] octets = new byte[length];
        int offset = 0;
        while (offset < octets.length) {
            int amount = in.read(octets, offset, octets.length - offset);
            if (amount <= 0)
                throw new EOFException();
            offset += amount;
        }
        return new String(octets, StandardCharsets.UTF_8);
    }

    public static String readURN(InputStream in) throws IOException {
        StringBuilder urn = new StringBuilder();

        for (; ; ) {
            byte b = (byte) in.read();
            if (b == ')')
                break;
            urn.append((char) b);
        }
        return urn.toString();
    }
}
