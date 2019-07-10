package com.android.server.wifi.anqp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Test ANQP code by talking to an ANQP server of a socket.
 */
public class TestDriver {

    private static final Constants.ANQPElementType[] QueryElements = {
            Constants.ANQPElementType.ANQPCapabilityList,
            Constants.ANQPElementType.ANQPVenueName,
            Constants.ANQPElementType.ANQPEmergencyNumber,
            Constants.ANQPElementType.ANQPNwkAuthType,
            Constants.ANQPElementType.ANQPRoamingConsortium,
            Constants.ANQPElementType.ANQPIPAddrAvailability,
            Constants.ANQPElementType.ANQPNAIRealm,
            Constants.ANQPElementType.ANQP3GPPNetwork,
            Constants.ANQPElementType.ANQPGeoLoc,
            Constants.ANQPElementType.ANQPCivicLoc,
            Constants.ANQPElementType.ANQPLocURI,
            Constants.ANQPElementType.ANQPDomName,
            Constants.ANQPElementType.ANQPEmergencyAlert,
            Constants.ANQPElementType.ANQPTDLSCap,
            Constants.ANQPElementType.ANQPEmergencyNAI,
            Constants.ANQPElementType.ANQPNeighborReport,

            Constants.ANQPElementType.HSCapabilityList,
            Constants.ANQPElementType.HSFriendlyName,
            Constants.ANQPElementType.HSWANMetrics,
            Constants.ANQPElementType.HSConnCapability,
            Constants.ANQPElementType.HSNAIHomeRealmQuery,
            Constants.ANQPElementType.HSOperatingclass,
            Constants.ANQPElementType.HSOSUProviders
    };

    public static void runTest() throws IOException {

        Set<Constants.ANQPElementType> elements =
                new HashSet<Constants.ANQPElementType>(QueryElements.length);
        elements.addAll(Arrays.asList(QueryElements));

        ByteBuffer request = ByteBuffer.allocate(8192);
        request.order(ByteOrder.LITTLE_ENDIAN);
        int lenPos = request.position();
        request.putShort((short) 0);
        ANQPFactory.buildQueryRequest(elements, request);

        byte[] requestBytes = prepRequest(lenPos, request);

        System.out.println( "Connecting...");
        Socket sock = new Socket(InetAddress.getLoopbackAddress(), 6104);
        BufferedOutputStream out = new BufferedOutputStream(sock.getOutputStream());
        System.out.println(" ### Querying for " + Arrays.toString(QueryElements));
        out.write(requestBytes);
        out.flush();

        BufferedInputStream in = new BufferedInputStream(sock.getInputStream());
        ByteBuffer payload = getResponse(in);

        HSOsuProvidersElement osuProvidersElement = null;
        List<ANQPElement> anqpResult = ANQPFactory.parsePayload(payload);
        for ( ANQPElement element : anqpResult ) {
            System.out.println( element );
            if (element.getID() == Constants.ANQPElementType.HSOSUProviders) {
                osuProvidersElement = (HSOsuProvidersElement)element;
            }
        }

        if ( osuProvidersElement != null ) {
            for (OSUProvider provider : osuProvidersElement.getProviders()) {
                for (IconInfo iconInfo : provider.getIcons()) {
                    sendIconRequest(iconInfo.getFileName());
                }
            }
        }
        sendIconRequest("doesNotExist.noimg");

        sendHomeRealmQuery("nxdomain.abc", "jan.com");
    }

    private static void sendIconRequest(String fileName) throws IOException {
        ByteBuffer iconRequest = ByteBuffer.allocate(fileName.length()*2)
                .order(ByteOrder.LITTLE_ENDIAN);
        int iconPos = iconRequest.position();
        iconRequest.putShort((short) 0);
        ANQPFactory.buildIconRequest(fileName, iconRequest);
        byte[] iconBytes = prepRequest(iconPos, iconRequest);

        System.out.println( "Connecting...");
        Socket sock = new Socket(InetAddress.getLoopbackAddress(), 6104);
        BufferedOutputStream out = new BufferedOutputStream(sock.getOutputStream());

        System.out.println(" ### Requesting icon '" + fileName + "'");
        out.write(iconBytes);
        out.flush();

        BufferedInputStream in = new BufferedInputStream(sock.getInputStream());
        ByteBuffer payload = getResponse(in);
        List<ANQPElement> anqpResult = ANQPFactory.parsePayload(payload);
        System.out.println("Icon: " + anqpResult );
    }

    private static void sendHomeRealmQuery(String ... realms) throws IOException{
        ByteBuffer request = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        int iconPos = request.position();
        request.putShort((short) 0);
        ANQPFactory.buildHomeRealmRequest(Arrays.asList(realms), request);
        byte[] iconBytes = prepRequest(iconPos, request);

        System.out.println( "Connecting...");
        Socket sock = new Socket(InetAddress.getLoopbackAddress(), 6104);
        BufferedOutputStream out = new BufferedOutputStream(sock.getOutputStream());

        System.out.println(" ### Home realm query for " + Arrays.toString(realms));
        out.write(iconBytes);
        out.flush();

        BufferedInputStream in = new BufferedInputStream(sock.getInputStream());
        ByteBuffer payload = getResponse(in);
        List<ANQPElement> anqpResult = ANQPFactory.parsePayload(payload);
        System.out.println("Home realm query: " + anqpResult );
    }

    private static ByteBuffer getResponse(InputStream in) throws IOException {
        ByteBuffer lengthBuffer = read( in, 2 );
        int length = lengthBuffer.getShort() & Constants.SHORT_MASK;
        System.out.println("Length " + length);

        return read(in, length);
    }

    private static byte[] prepRequest(int pos0, ByteBuffer request) {
        request.putShort(pos0, (short)( request.limit() - pos0 - Constants.BYTES_IN_SHORT ));
        byte[] octets = new byte[request.remaining()];
        request.get(octets);
        return octets;
    }

    private static ByteBuffer read(InputStream in, int length) throws IOException {
        byte[] payload = new byte[length];
        int position = 0;
        while ( position < length ) {
            int amount = in.read(payload, position, length - position);
            if ( amount <= 0 ) {
                throw new EOFException("Got " + amount);
            }
            position += amount;
        }
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static void main(String[] args) throws IOException {
        runTest();
    }
}
