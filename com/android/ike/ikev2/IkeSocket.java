/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.ike.ikev2;

import static android.system.OsConstants.F_SETFL;
import static android.system.OsConstants.SOCK_DGRAM;
import static android.system.OsConstants.SOCK_NONBLOCK;

import android.net.IpSecManager.UdpEncapsulationSocket;
import android.net.util.PacketReader;
import android.os.Handler;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.util.LongSparseArray;

import com.android.ike.ikev2.exceptions.IkeException;
import com.android.ike.ikev2.message.IkeHeader;
import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * IkeSocket sends and receives IKE packets via the user provided {@link UdpEncapsulationSocket}.
 *
 * <p>One UdpEncapsulationSocket instance can only be bound to one IkeSocket instance. IkeSocket
 * maintains a static map to cache all bound UdpEncapsulationSockets and their IkeSocket instances.
 * It returns the existing IkeSocket when it has been bound with user provided {@link
 * UdpEncapsulationSocket}.
 *
 * <p>As a packet receiver, IkeSocket registers a file descriptor with a thread's Looper and handles
 * read events (and errors). Users can expect a call life-cycle like the following:
 *
 * <pre>
 * [1] when user gets a new initiated IkeSocket, start() is called and followed by createFd().
 * [2] yield, waiting for a read event which will invoke handlePacket()
 * [3] when user closes this IkeSocket, its reference count decreases. Then stop() is called when
 *     there is no reference of this instance.
 * </pre>
 *
 * <p>IkeSocket is constructed and called only on a single IKE working thread by {@link
 * IkeSessionStateMachine}. Since all {@link IkeSessionStateMachine}s run on the same working
 * thread, there will not be concurrent modification problems.
 */
public final class IkeSocket extends PacketReader implements AutoCloseable {
    private static final String TAG = "IkeSocket";

    // TODO: b/129358324 Consider supporting IKE exchange without UDP Encapsulation.
    // UDP-encapsulated IKE packets MUST be sent to 4500.
    @VisibleForTesting static final int IKE_SERVER_PORT = 4500;

    // A Non-ESP marker helps the recipient to distinguish IKE packets from ESP packets.
    @VisibleForTesting static final int NON_ESP_MARKER_LEN = 4;
    @VisibleForTesting static final byte[] NON_ESP_MARKER = new byte[NON_ESP_MARKER_LEN];

    // Map from UdpEncapsulationSocket to IkeSocket instances.
    private static Map<UdpEncapsulationSocket, IkeSocket> sFdToIkeSocketMap = new HashMap<>();

    private static IPacketReceiver sPacketReceiver = new PacketReceiver();

    // Package private map from locally generated IKE SPI to IkeSessionStateMachine instances.
    @VisibleForTesting
    final LongSparseArray<IkeSessionStateMachine> mSpiToIkeSession =
            new LongSparseArray<>();
    // UdpEncapsulationSocket for sending and receving IKE packet.
    private final UdpEncapsulationSocket mUdpEncapSocket;

    /** Package private */
    @VisibleForTesting
    int mRefCount;

    private IkeSocket(UdpEncapsulationSocket udpEncapSocket, Handler handler) {
        super(handler);
        mRefCount = 1;
        mUdpEncapSocket = udpEncapSocket;
    }

    /**
     * Get an IkeSocket instance.
     *
     * <p>Return the existing IkeSocket instance if it has been created for the input
     * udpEncapSocket. Otherwise, create and return a new IkeSocket instance.
     *
     * @param udpEncapSocket user provided UdpEncapsulationSocket
     * @return an IkSocket instance
     */
    public static IkeSocket getIkeSocket(UdpEncapsulationSocket udpEncapSocket)
            throws ErrnoException {
        FileDescriptor fd = udpEncapSocket.getFileDescriptor();
        // All created IkeSocket has modified its FileDescriptor to non-blocking type for handling
        // read events in a non-blocking way.
        Os.fcntlInt(fd, F_SETFL, SOCK_DGRAM | SOCK_NONBLOCK);

        if (sFdToIkeSocketMap.containsKey(udpEncapSocket)) {
            IkeSocket ikeSocket = sFdToIkeSocketMap.get(udpEncapSocket);
            ikeSocket.mRefCount++;
            return ikeSocket;
        } else {
            IkeSocket ikeSocket = new IkeSocket(udpEncapSocket, new Handler());
            // Create and register FileDescriptor for receiving IKE packet on current thread.
            ikeSocket.start();

            sFdToIkeSocketMap.put(udpEncapSocket, ikeSocket);
            return ikeSocket;
        }
    }

    /**
     * Get FileDecriptor of mUdpEncapSocket.
     *
     * <p>PacketReader registers a listener for this file descriptor on the thread where IkeSocket
     * is constructed. When there is a read event, this listener is invoked and then calls {@link
     * handlePacket} to handle the received packet.
     */
    @Override
    protected FileDescriptor createFd() {
        return mUdpEncapSocket.getFileDescriptor();
    }

    /**
     * IPacketReceiver provides a package private interface for handling received packet.
     *
     * <p>IPacketReceiver exists so that the interface is injectable for testing.
     */
    interface IPacketReceiver {
        void handlePacket(byte[] recvbuf, LongSparseArray<IkeSessionStateMachine> spiToIkeSession);
    }

    /** Package private */
    @VisibleForTesting
    static final class PacketReceiver implements IPacketReceiver {
        public void handlePacket(
                byte[] recvbuf, LongSparseArray<IkeSessionStateMachine> spiToIkeSession) {
            // TODO: b/129708574 Consider only logging the error some % of the time it happens, or
            // only logging the error the first time it happens and then keep a count to prevent
            // logspam.

            ByteBuffer byteBuffer = ByteBuffer.wrap(recvbuf);

            // Check the existence of the Non-ESP Marker. A received packet can be either an IKE
            // packet starts with 4 zero-valued bytes Non-ESP Marker or an ESP packet starts with 4
            // bytes ESP SPI. ESP SPI value can never be zero.
            byte[] espMarker = new byte[NON_ESP_MARKER_LEN];
            byteBuffer.get(espMarker);
            if (!Arrays.equals(NON_ESP_MARKER, espMarker)) {
                // Drop the received ESP packet.
                Log.e(TAG, "Receive an ESP packet.");
                return;
            }

            try {
                // Re-direct IKE packet to IkeSessionStateMachine according to the locally generated
                // IKE SPI.
                byte[] ikePacketBytes = new byte[byteBuffer.remaining()];
                byteBuffer.get(ikePacketBytes);
                IkeHeader ikeHeader = new IkeHeader(ikePacketBytes);

                long localGeneratedSpi =
                        ikeHeader.fromIkeInitiator
                                ? ikeHeader.ikeResponderSpi
                                : ikeHeader.ikeInitiatorSpi;

                IkeSessionStateMachine ikeStateMachine = spiToIkeSession.get(localGeneratedSpi);
                if (ikeStateMachine == null) {
                    Log.e(TAG, "Unrecognized IKE SPI.");
                    // TODO: Handle invalid IKE SPI error
                } else {
                    ikeStateMachine.receiveIkePacket(ikeHeader, ikePacketBytes);
                }
            } catch (IkeException e) {
                // Handle invalid IKE header
                Log.e(TAG, "Can't parse malformed IKE packet header.");
            }
        }
    }

    /** Package private */
    @VisibleForTesting
    static void setPacketReceiver(IPacketReceiver receiver) {
        sPacketReceiver = receiver;
    }

    /**
     * Handle received IKE packet. Invoked when there is a read event. Any desired copies of
     * |recvbuf| should be made in here, as the underlying byte array is reused across all reads.
     */
    @Override
    protected void handlePacket(byte[] recvbuf, int length) {
        sPacketReceiver.handlePacket(Arrays.copyOfRange(recvbuf, 0, length), mSpiToIkeSession);
    }

    /**
     * Send encoded IKE packet to destination address
     *
     * @param ikePacket encoded IKE packet
     * @param serverAddress IP address of remote server
     */
    public void sendIkePacket(byte[] ikePacket, InetAddress serverAddress) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(NON_ESP_MARKER_LEN + ikePacket.length);

            // Build outbound UDP Encapsulation packet body for sending IKE message.
            buffer.put(NON_ESP_MARKER).put(ikePacket);
            buffer.rewind();

            // Use unconnected UDP socket because one {@UdpEncapsulationSocket} may be shared by
            // multiple IKE sessions that send messages to different destinations.
            Os.sendto(
                    mUdpEncapSocket.getFileDescriptor(), buffer, 0, serverAddress, IKE_SERVER_PORT);
        } catch (ErrnoException | IOException e) {
            // TODO: Handle exception
        }
    }

    /**
     * Register new created IKE SA
     *
     * @param spi the locally generated IKE SPI
     * @param ikeSession the IKE session this IKE SA belongs to
     */
    public void registerIke(long spi, IkeSessionStateMachine ikeSession) {
        mSpiToIkeSession.put(spi, ikeSession);
    }

    /**
     * Unregister a deleted IKE SA
     *
     * @param spi the locally generated IKE SPI
     */
    public void unregisterIke(long spi) {
        mSpiToIkeSession.remove(spi);
    }

    /** Release reference of current IkeSocket when the IKE session is closed. */
    public void releaseReference() {
        mRefCount--;
        if (mRefCount == 0) close();
    }

    /** Implement {@link AutoCloseable#close()} */
    @Override
    public void close() {
        sFdToIkeSocketMap.remove(mUdpEncapSocket);
        // PackeReader unregisters file descriptor on thread with which the Handler constructor
        // argument is associated.
        stop();
    }
}
