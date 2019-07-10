package com.android.server.wifi.hotspot2;

import android.util.Log;

import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.WifiConfigStore;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.ANQPFactory;
import com.android.server.wifi.anqp.Constants;
import com.android.server.wifi.anqp.eap.AuthParam;
import com.android.server.wifi.anqp.eap.EAP;
import com.android.server.wifi.anqp.eap.EAPMethod;
import com.android.server.wifi.hotspot2.pps.Credential;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SupplicantBridge {
    private final WifiNative mSupplicantHook;
    private final WifiConfigStore mConfigStore;
    private final Map<Long, ScanDetail> mRequestMap = new HashMap<>();

    private static final Map<String, Constants.ANQPElementType> sWpsNames = new HashMap<>();

    static {
        sWpsNames.put("anqp_venue_name", Constants.ANQPElementType.ANQPVenueName);
        sWpsNames.put("anqp_network_auth_type", Constants.ANQPElementType.ANQPNwkAuthType);
        sWpsNames.put("anqp_roaming_consortium", Constants.ANQPElementType.ANQPRoamingConsortium);
        sWpsNames.put("anqp_ip_addr_type_availability",
                Constants.ANQPElementType.ANQPIPAddrAvailability);
        sWpsNames.put("anqp_nai_realm", Constants.ANQPElementType.ANQPNAIRealm);
        sWpsNames.put("anqp_3gpp", Constants.ANQPElementType.ANQP3GPPNetwork);
        sWpsNames.put("anqp_domain_name", Constants.ANQPElementType.ANQPDomName);
        sWpsNames.put("hs20_operator_friendly_name", Constants.ANQPElementType.HSFriendlyName);
        sWpsNames.put("hs20_wan_metrics", Constants.ANQPElementType.HSWANMetrics);
        sWpsNames.put("hs20_connection_capability", Constants.ANQPElementType.HSConnCapability);
        sWpsNames.put("hs20_operating_class", Constants.ANQPElementType.HSOperatingclass);
        sWpsNames.put("hs20_osu_providers_list", Constants.ANQPElementType.HSOSUProviders);
    }

    public static boolean isAnqpAttribute(String line) {
        int split = line.indexOf('=');
        return split >= 0 && sWpsNames.containsKey(line.substring(0, split));
    }

    public SupplicantBridge(WifiNative supplicantHook, WifiConfigStore configStore) {
        mSupplicantHook = supplicantHook;
        mConfigStore = configStore;
    }

    public static Map<Constants.ANQPElementType, ANQPElement> parseANQPLines(List<String> lines) {
        if (lines == null) {
            return null;
        }
        Map<Constants.ANQPElementType, ANQPElement> elements = new HashMap<>(lines.size());
        for (String line : lines) {
            try {
                ANQPElement element = buildElement(line);
                if (element != null) {
                    elements.put(element.getID(), element);
                }
            }
            catch (ProtocolException pe) {
                Log.e(Utils.hs2LogTag(SupplicantBridge.class), "Failed to parse ANQP: " + pe);
            }
        }
        return elements;
    }

    public void startANQP(ScanDetail scanDetail) {
        String anqpGet = buildWPSQueryRequest(scanDetail.getNetworkDetail());
        synchronized (mRequestMap) {
            mRequestMap.put(scanDetail.getNetworkDetail().getBSSID(), scanDetail);
        }
        String result = mSupplicantHook.doCustomCommand(anqpGet);
        if (result != null && result.startsWith("OK")) {
            Log.d(Utils.hs2LogTag(getClass()), "ANQP initiated on " + scanDetail);
        }
        else {
            Log.d(Utils.hs2LogTag(getClass()), "ANQP failed on " +
                    scanDetail + ": " + result);
        }
    }

    public void notifyANQPDone(Long bssid, boolean success) {
        ScanDetail scanDetail;
        synchronized (mRequestMap) {
            scanDetail = mRequestMap.remove(bssid);
        }
        if (scanDetail == null) {
            Log.d(Utils.hs2LogTag(getClass()), String.format("Spurious %s ANQP response for %012x",
                            success ? "successful" : "failed", bssid));
            return;
        }

        String bssData = mSupplicantHook.scanResult(scanDetail.getBSSIDString());
        try {
            Map<Constants.ANQPElementType, ANQPElement> elements = parseWPSData(bssData);
            Log.d(Utils.hs2LogTag(getClass()), String.format("%s ANQP response for %012x: %s",
                    success ? "successful" : "failed", bssid, elements));
            mConfigStore.notifyANQPResponse(scanDetail, success ? elements : null);
        }
        catch (IOException ioe) {
            Log.e(Utils.hs2LogTag(getClass()), "Failed to parse ANQP: " +
                    ioe.toString() + ": " + bssData);
        }
        catch (RuntimeException rte) {
            Log.e(Utils.hs2LogTag(getClass()), "Failed to parse ANQP: " +
                    rte.toString() + ": " + bssData, rte);
        }
        mConfigStore.notifyANQPResponse(scanDetail, null);
    }

    /*
    public boolean addCredential(HomeSP homeSP, NetworkDetail networkDetail) {
        Credential credential = homeSP.getCredential();
        if (credential == null)
            return false;

        String nwkID = null;
        if (mLastSSID != null) {
            String nwkList = mSupplicantHook.doCustomCommand("LIST_NETWORKS");

            BufferedReader reader = new BufferedReader(new StringReader(nwkList));
            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    String[] tokens = line.split("\\t");
                    if (tokens.length < 2 || ! Utils.isDecimal(tokens[0])) {
                        continue;
                    }
                    if (unescapeSSID(tokens[1]).equals(mLastSSID)) {
                        nwkID = tokens[0];
                        Log.d("HS2J", "Network " + tokens[0] +
                                " matches last SSID '" + mLastSSID + "'");
                        break;
                    }
                }
            }
            catch (IOException ioe) {
                //
            }
        }

        if (nwkID == null) {
            nwkID = mSupplicantHook.doCustomCommand("ADD_NETWORK");
            Log.d("HS2J", "add_network: '" + nwkID + "'");
            if (! Utils.isDecimal(nwkID)) {
                return false;
            }
        }

        List<String> credCommand = getWPSNetCommands(nwkID, networkDetail, credential);
        for (String command : credCommand) {
            String status = mSupplicantHook.doCustomCommand(command);
            Log.d("HS2J", "Status of '" + command + "': '" + status + "'");
        }

        if (! networkDetail.getSSID().equals(mLastSSID)) {
            mLastSSID = networkDetail.getSSID();
            PrintWriter out = null;
            try {
                out = new PrintWriter(new OutputStreamWriter(
                        new FileOutputStream(mLastSSIDFile, false), StandardCharsets.UTF_8));
                out.println(mLastSSID);
            } catch (IOException ioe) {
            //
            } finally {
                if (out != null) {
                    out.close();
                }
            }
        }

        return true;
    }
    */

    private static String escapeSSID(NetworkDetail networkDetail) {
        return escapeString(networkDetail.getSSID(), networkDetail.isSSID_UTF8());
    }

    private static String escapeString(String s, boolean utf8) {
        boolean asciiOnly = true;
        for (int n = 0; n < s.length(); n++) {
            char ch = s.charAt(n);
            if (ch > 127) {
                asciiOnly = false;
                break;
            }
        }

        if (asciiOnly) {
            return '"' + s + '"';
        }
        else {
            byte[] octets = s.getBytes(utf8 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);

            StringBuilder sb = new StringBuilder();
            for (byte octet : octets) {
                sb.append(String.format("%02x", octet & Constants.BYTE_MASK));
            }
            return sb.toString();
        }
    }

    private static String buildWPSQueryRequest(NetworkDetail networkDetail) {
        StringBuilder sb = new StringBuilder();
        sb.append("ANQP_GET ").append(networkDetail.getBSSIDString()).append(' ');

        boolean first = true;
        for (Constants.ANQPElementType elementType : ANQPFactory.getBaseANQPSet()) {
            if (networkDetail.getAnqpOICount() == 0 &&
                    elementType == Constants.ANQPElementType.ANQPRoamingConsortium) {
                continue;
            }
            if (first) {
                first = false;
            }
            else {
                sb.append(',');
            }
            sb.append(Constants.getANQPElementID(elementType));
        }
        if (networkDetail.getHSRelease() != null) {
            for (Constants.ANQPElementType elementType : ANQPFactory.getHS20ANQPSet()) {
                sb.append(",hs20:").append(Constants.getHS20ElementID(elementType));
            }
        }
        return sb.toString();
    }

    private static List<String> getWPSNetCommands(String netID, NetworkDetail networkDetail,
                                                 Credential credential) {

        List<String> commands = new ArrayList<String>();

        EAPMethod eapMethod = credential.getEAPMethod();
        commands.add(String.format("SET_NETWORK %s key_mgmt WPA-EAP", netID));
        commands.add(String.format("SET_NETWORK %s ssid %s", netID, escapeSSID(networkDetail)));
        commands.add(String.format("SET_NETWORK %s bssid %s",
                netID, networkDetail.getBSSIDString()));
        commands.add(String.format("SET_NETWORK %s eap %s",
                netID, mapEAPMethodName(eapMethod.getEAPMethodID())));

        AuthParam authParam = credential.getEAPMethod().getAuthParam();
        if (authParam == null) {
            return null;            // TLS or SIM/AKA
        }
        switch (authParam.getAuthInfoID()) {
            case NonEAPInnerAuthType:
            case InnerAuthEAPMethodType:
                commands.add(String.format("SET_NETWORK %s identity %s",
                        netID, escapeString(credential.getUserName(), true)));
                commands.add(String.format("SET_NETWORK %s password %s",
                        netID, escapeString(credential.getPassword(), true)));
                commands.add(String.format("SET_NETWORK %s anonymous_identity \"anonymous\"",
                        netID));
                break;
            default:                // !!! Needs work.
                return null;
        }
        commands.add(String.format("SET_NETWORK %s priority 0", netID));
        commands.add(String.format("ENABLE_NETWORK %s", netID));
        commands.add(String.format("SAVE_CONFIG"));
        return commands;
    }

    private static Map<Constants.ANQPElementType, ANQPElement> parseWPSData(String bssInfo)
            throws IOException {
        Map<Constants.ANQPElementType, ANQPElement> elements = new HashMap<>();
        if (bssInfo == null) {
            return elements;
        }
        BufferedReader lineReader = new BufferedReader(new StringReader(bssInfo));
        String line;
        while ((line=lineReader.readLine()) != null) {
            ANQPElement element = buildElement(line);
            if (element != null) {
                elements.put(element.getID(), element);
            }
        }
        return elements;
    }

    private static ANQPElement buildElement(String text) throws ProtocolException {
        int separator = text.indexOf('=');
        if (separator < 0) {
            return null;
        }

        String elementName = text.substring(0, separator);
        Constants.ANQPElementType elementType = sWpsNames.get(elementName);
        if (elementType == null) {
            return null;
        }

        byte[] payload;
        try {
            payload = Utils.hexToBytes(text.substring(separator + 1));
        }
        catch (NumberFormatException nfe) {
            Log.e(Utils.hs2LogTag(SupplicantBridge.class), "Failed to parse hex string");
            return null;
        }
        return Constants.getANQPElementID(elementType) != null ?
                ANQPFactory.buildElement(ByteBuffer.wrap(payload), elementType, payload.length) :
                ANQPFactory.buildHS20Element(elementType,
                        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN));
    }

    private static String mapEAPMethodName(EAP.EAPMethodID eapMethodID) {
        switch (eapMethodID) {
            case EAP_AKA:
                return "AKA";
            case EAP_AKAPrim:
                return "AKA'";  // eap.c:1514
            case EAP_SIM:
                return "SIM";
            case EAP_TLS:
                return "TLS";
            case EAP_TTLS:
                return "TTLS";
            default:
                throw new IllegalArgumentException("No mapping for " + eapMethodID);
        }
    }

    private static final Map<Character,Integer> sMappings = new HashMap<Character, Integer>();

    static {
        sMappings.put('\\', (int)'\\');
        sMappings.put('"', (int)'"');
        sMappings.put('e', 0x1b);
        sMappings.put('n', (int)'\n');
        sMappings.put('r', (int)'\n');
        sMappings.put('t', (int)'\t');
    }

    public static String unescapeSSID(String ssid) {

        CharIterator chars = new CharIterator(ssid);
        byte[] octets = new byte[ssid.length()];
        int bo = 0;

        while (chars.hasNext()) {
            char ch = chars.next();
            if (ch != '\\' || ! chars.hasNext()) {
                octets[bo++] = (byte)ch;
            }
            else {
                char suffix = chars.next();
                Integer mapped = sMappings.get(suffix);
                if (mapped != null) {
                    octets[bo++] = mapped.byteValue();
                }
                else if (suffix == 'x' && chars.hasDoubleHex()) {
                    octets[bo++] = (byte)chars.nextDoubleHex();
                }
                else {
                    octets[bo++] = '\\';
                    octets[bo++] = (byte)suffix;
                }
            }
        }

        boolean asciiOnly = true;
        for (byte b : octets) {
            if ((b&0x80) != 0) {
                asciiOnly = false;
                break;
            }
        }
        if (asciiOnly) {
            return new String(octets, 0, bo, StandardCharsets.UTF_8);
        } else {
            try {
                // If UTF-8 decoding is successful it is almost certainly UTF-8
                CharBuffer cb = StandardCharsets.UTF_8.newDecoder().decode(
                        ByteBuffer.wrap(octets, 0, bo));
                return cb.toString();
            } catch (CharacterCodingException cce) {
                return new String(octets, 0, bo, StandardCharsets.ISO_8859_1);
            }
        }
    }

    private static class CharIterator {
        private final String mString;
        private int mPosition;
        private int mHex;

        private CharIterator(String s) {
            mString = s;
        }

        private boolean hasNext() {
            return mPosition < mString.length();
        }

        private char next() {
            return mString.charAt(mPosition++);
        }

        private boolean hasDoubleHex() {
            if (mString.length() - mPosition < 2) {
                return false;
            }
            int nh = Utils.fromHex(mString.charAt(mPosition), true);
            if (nh < 0) {
                return false;
            }
            int nl = Utils.fromHex(mString.charAt(mPosition + 1), true);
            if (nl < 0) {
                return false;
            }
            mPosition += 2;
            mHex = (nh << 4) | nl;
            return true;
        }

        private int nextDoubleHex() {
            return mHex;
        }
    }

    private static final String[] TestStrings = {
            "test-ssid",
            "test\\nss\\tid",
            "test\\x2d\\x5f\\nss\\tid",
            "test\\x2d\\x5f\\nss\\tid\\\\",
            "test\\x2d\\x5f\\nss\\tid\\n",
            "test\\x2d\\x5f\\nss\\tid\\x4a",
            "another\\",
            "an\\other",
            "another\\x2"
    };

    public static void main(String[] args) {
        for (String string : TestStrings) {
            System.out.println(unescapeSSID(string));
        }
    }
}
