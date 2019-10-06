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

import android.os.Looper;
import android.os.Message;
import android.system.ErrnoException;
import android.util.LongSparseArray;
import android.util.SparseArray;

import com.android.ike.ikev2.SaRecord.IkeSaRecord;
import com.android.ike.ikev2.exceptions.IkeException;
import com.android.ike.ikev2.message.IkeHeader;
import com.android.ike.ikev2.message.IkeKePayload;
import com.android.ike.ikev2.message.IkeMessage;
import com.android.ike.ikev2.message.IkeNoncePayload;
import com.android.ike.ikev2.message.IkeNotifyPayload;
import com.android.ike.ikev2.message.IkePayload;
import com.android.ike.ikev2.message.IkeSaPayload;
import com.android.ike.ikev2.message.IkeSaPayload.DhGroupTransform;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * IkeSessionStateMachine tracks states and manages exchanges of this IKE session.
 *
 * <p>IkeSessionStateMachine has two types of states. One type are states where there is no ongoing
 * procedure affecting IKE session (non-procedure state), including Initial, Closed, Idle and
 * Receiving. All other states are "procedure" states which are named as follows:
 *
 * <pre>
 * State Name = [Procedure Type] + [Exchange Initiator] + [Exchange Type].
 * - An IKE procedure consists of one or two IKE exchanges:
 *      Procedure Type = {CreateIke | DeleteIke | Info | RekeyIke | SimulRekeyIke}.
 * - Exchange Initiator indicates whether local or remote peer is the exchange initiator:
 *      Exchange Initiator = {Local | Remote}
 * - Exchange type defines the function of this exchange. To make it more descriptive, we separate
 *      Delete Exchange from generic Informational Exchange:
 *      Exchange Type = {IkeInit | IkeAuth | Create | Delete | Info}
 * </pre>
 */
public class IkeSessionStateMachine extends StateMachine {

    private static final String TAG = "IkeSessionStateMachine";

    /** Package private signals accessible for testing code. */
    private static final int CMD_GENERAL_BASE = 0;
    /** Receive encoded IKE packet on IkeSessionStateMachine. */
    static final int CMD_RECEIVE_IKE_PACKET = CMD_GENERAL_BASE + 1;
    /** Receive locally built payloads from Child Session for building outbound IKE message. */
    static final int CMD_RECEIVE_OUTBOUND_CHILD_PAYLOADS = CMD_GENERAL_BASE + 2;
    /** Receive encoded IKE packet with unrecognized IKE SPI on IkeSessionStateMachine. */
    static final int CMD_RECEIVE_PACKET_INVALID_IKE_SPI = CMD_GENERAL_BASE + 3;
    // TODO: Add signal for retransmission.

    private static final int CMD_LOCAL_REQUEST_BASE = CMD_GENERAL_BASE + 100;
    static final int CMD_LOCAL_REQUEST_CREATE_IKE = CMD_LOCAL_REQUEST_BASE + 1;
    static final int CMD_LOCAL_REQUEST_DELETE_IKE = CMD_LOCAL_REQUEST_BASE + 2;
    static final int CMD_LOCAL_REQUEST_REKEY_IKE = CMD_LOCAL_REQUEST_BASE + 3;
    static final int CMD_LOCAL_REQUEST_INFO = CMD_LOCAL_REQUEST_BASE + 4;
    static final int CMD_LOCAL_REQUEST_CREATE_CHILD = CMD_LOCAL_REQUEST_BASE + 5;
    static final int CMD_LOCAL_REQUEST_DELETE_CHILD = CMD_LOCAL_REQUEST_BASE + 6;
    static final int CMD_LOCAL_REQUEST_REKEY_CHILD = CMD_LOCAL_REQUEST_BASE + 7;
    // TODO: Add signals for other procedure types and notificaitons.

    // Remember locally assigned IKE SPIs to avoid SPI collision.
    private static final Set<Long> ASSIGNED_LOCAL_IKE_SPI_SET = new HashSet<>();
    private static final int MAX_ASSIGN_IKE_SPI_ATTEMPTS = 100;
    private static final SecureRandom IKE_SPI_RANDOM = new SecureRandom();

    private final IkeSessionOptions mIkeSessionOptions;
    private final ChildSessionOptions mFirstChildSessionOptions;
    /** Map that stores all IkeSaRecords, keyed by remotely generated IKE SPI. */
    private final LongSparseArray<IkeSaRecord> mSpiToSaRecordMap;
    /**
     * Map that stores all ChildSessionStateMachines, keyed by remotely generated Child SPI for
     * sending IPsec packet. Different SPIs may point to the same ChildSessionStateMachine if this
     * Child Session is doing Rekey.
     */
    private final SparseArray<ChildSessionStateMachine> mSpiToChildSessionMap;

    /**
     * Package private socket that sends and receives encoded IKE message. Initialized in Initial
     * State.
     */
    @VisibleForTesting IkeSocket mIkeSocket;

    /** Package */
    @VisibleForTesting IkeSaRecord mCurrentIkeSaRecord;
    /** Package */
    @VisibleForTesting IkeSaRecord mLocalInitNewIkeSaRecord;
    /** Package */
    @VisibleForTesting IkeSaRecord mRemoteInitNewIkeSaRecord;

    /** Package */
    @VisibleForTesting IkeSaRecord mIkeSaRecordSurviving;
    /** Package */
    @VisibleForTesting IkeSaRecord mIkeSaRecordAwaitingLocalDel;
    /** Package */
    @VisibleForTesting IkeSaRecord mIkeSaRecordAwaitingRemoteDel;

    // States
    private final State mInitial = new Initial();
    private final State mClosed = new Closed();
    private final State mIdle = new Idle();
    private final State mReceiving = new Receiving();
    private final State mCreateIkeLocalIkeInit = new CreateIkeLocalIkeInit();
    private final State mCreateIkeLocalIkeAuth = new CreateIkeLocalIkeAuth();
    private final State mRekeyIkeLocalCreate = new RekeyIkeLocalCreate();
    private final State mSimulRekeyIkeLocalCreate = new SimulRekeyIkeLocalCreate();
    private final State mSimulRekeyIkeLocalDeleteRemoteDelete =
            new SimulRekeyIkeLocalDeleteRemoteDelete();
    private final State mSimulRekeyIkeLocalDelete = new SimulRekeyIkeLocalDelete();
    private final State mSimulRekeyIkeRemoteDelete = new SimulRekeyIkeRemoteDelete();
    private final State mRekeyIkeLocalDelete = new RekeyIkeLocalDelete();
    private final State mRekeyIkeRemoteDelete = new RekeyIkeRemoteDelete();
    // TODO: Add InfoLocal and DeleteIkeLocal.

    /** Package private constructor */
    IkeSessionStateMachine(
            String name,
            Looper looper,
            IkeSessionOptions ikeOptions,
            ChildSessionOptions firstChildOptions) {
        super(name, looper);
        mIkeSessionOptions = ikeOptions;
        mFirstChildSessionOptions = firstChildOptions;
        // There are at most three IkeSaRecords co-existing during simultaneous rekeying.
        mSpiToSaRecordMap = new LongSparseArray<>(3);
        mSpiToChildSessionMap = new SparseArray<>();

        addState(mInitial);
        addState(mClosed);
        addState(mCreateIkeLocalIkeInit);
        addState(mCreateIkeLocalIkeAuth);
        addState(mIdle);
        addState(mReceiving);
        addState(mRekeyIkeLocalCreate);
        addState(mSimulRekeyIkeLocalCreate, mRekeyIkeLocalCreate);
        addState(mSimulRekeyIkeLocalDeleteRemoteDelete);
        addState(mSimulRekeyIkeLocalDelete, mSimulRekeyIkeLocalDeleteRemoteDelete);
        addState(mSimulRekeyIkeRemoteDelete, mSimulRekeyIkeLocalDeleteRemoteDelete);
        addState(mRekeyIkeLocalDelete);
        addState(mRekeyIkeRemoteDelete);

        setInitialState(mInitial);
    }

    // Generate IKE SPI. Throw an exception if it failed and handle this exception in current State.
    private static Long getIkeSpiOrThrow() {
        for (int i = 0; i < MAX_ASSIGN_IKE_SPI_ATTEMPTS; i++) {
            long spi = IKE_SPI_RANDOM.nextLong();
            if (ASSIGNED_LOCAL_IKE_SPI_SET.add(spi)) return spi;
        }
        throw new IllegalStateException("Failed to generate IKE SPI.");
    }

    private IkeMessage buildIkeInitReq() {
        // TODO: Handle IKE SPI assigning error in CreateIkeLocalIkeInit State.

        List<IkePayload> payloadList = new LinkedList<>();

        // Generate IKE SPI
        long initSpi = getIkeSpiOrThrow();
        long respSpi = 0;

        // It is validated in IkeSessionOptions.Builder to ensure IkeSessionOptions has at least one
        // SaProposal and all SaProposals are valid for IKE SA negotiation.
        SaProposal[] saProposals = mIkeSessionOptions.getSaProposals();

        // Build SA Payload
        IkeSaPayload saPayload = new IkeSaPayload(saProposals);
        payloadList.add(saPayload);

        // Build KE Payload using the first DH group number in the first SaProposal.
        DhGroupTransform dhGroupTransform = saProposals[0].getDhGroupTransforms()[0];
        IkeKePayload kePayload = new IkeKePayload(dhGroupTransform.id);
        payloadList.add(kePayload);

        // Build Nonce Payload
        IkeNoncePayload noncePayload = new IkeNoncePayload();
        payloadList.add(noncePayload);

        // TODO: Add Notification Payloads according to user configurations.

        // Build IKE header
        IkeHeader ikeHeader =
                new IkeHeader(
                        initSpi,
                        respSpi,
                        IkePayload.PAYLOAD_TYPE_SA,
                        IkeHeader.EXCHANGE_TYPE_IKE_SA_INIT,
                        false /*isResponseMsg*/,
                        true /*fromIkeInitiator*/,
                        0 /*messageId*/);

        return new IkeMessage(ikeHeader, payloadList);
    }

    private IkeMessage buildIkeAuthReq() {
        // TODO: Build IKE_AUTH request according to mIkeSessionOptions and
        // firstChildSessionOptions.
        return null;
    }

    private IkeMessage buildIkeDeleteReq(IkeSaRecord ikeSaRecord) {
        // TODO: Implement it.
        return null;
    }

    private IkeMessage buildIkeDeleteResp(IkeSaRecord ikeSaRecord) {
        // TODO: Implement it.
        return null;
    }

    private IkeMessage buildIkeRekeyReq() {
        // TODO: Implement it.
        return null;
    }

    private IkeMessage buildIkeRekeyResp(IkeMessage reqMsg) {
        // TODO: Implement it.
        return null;
    }

    private void validateIkeInitResp(IkeMessage reqMsg, IkeMessage respMsg) throws IkeException {
        // TODO: Validate ikeMessage against IKE_INIT request and set confiugration negotiation
        // results
        // in mIkeSessionOptions(e.g.NAT detecting result).
    }

    private void validateIkeAuthResp(IkeMessage reqMsg, IkeMessage respMsg) throws IkeException {
        // TODO: Validate ikeMessage against IKE_AUTH request and mIkeSessionOptions.
    }

    private void validateIkeDeleteReq(IkeMessage ikeMessage) throws IkeException {
        // TODO: Validate ikeMessage.
    }

    private void validateIkeDeleteResp(IkeMessage ikeMessage) throws IkeException {
        // TODO: Validate ikeMessage.
    }

    private void validateIkeRekeyReq(IkeMessage ikeMessage) throws IkeException {
        // TODO: Validate it againsr mIkeSessionOptions.
    }

    private void validateIkeRekeyResp(IkeMessage reqMsg, IkeMessage respMsg) throws IkeException {
        // TODO: Validate ikeMessage against Rekey request.
    }

    // TODO: Add methods for building and validating general Informational packet.

    private void addIkeSaRecord(IkeSaRecord record) {
        mSpiToSaRecordMap.put(record.getRemoteSpi(), record);
    }

    private void removeIkeSaRecord(IkeSaRecord record) {
        mSpiToSaRecordMap.remove(record.getRemoteSpi());
    }

    /**
     * Receive IKE packet from remote server.
     *
     * <p>This method is called synchronously from IkeSocket. It proxies the synchronous call as an
     * asynchronous job to the IkeSessionStateMachine handler.
     *
     * @param ikeHeader the decoded IKE header.
     * @param ikePacketBytes the byte array of the entire received IKE packet.
     */
    public void receiveIkePacket(IkeHeader ikeHeader, byte[] ikePacketBytes) {
        sendMessage(CMD_RECEIVE_IKE_PACKET, new ReceivedIkePacket(ikeHeader, ikePacketBytes));
    }

    /**
     * ReceivedIkePacket is a package private data container consists of decoded IkeHeader and
     * encoded IKE packet in a byte array.
     */
    static class ReceivedIkePacket {
        /** Decoded IKE header */
        public final IkeHeader ikeHeader;
        /** Entire encoded IKE message including IKE header */
        public final byte[] ikePacketBytes;

        ReceivedIkePacket(IkeHeader ikeHeader, byte[] ikePacketBytes) {
            this.ikeHeader = ikeHeader;
            this.ikePacketBytes = ikePacketBytes;
        }
    }

    /**
     * Interface for ChildSessionStateMachine to notify IkeSessionStateMachine.
     *
     * <p>Package private so as to be injectable for testing.
     */
    interface IChildSessionCallback {
        /** Notify that new Child SA is created. */
        void onCreateChildSa(int remoteSpi, ChildSessionStateMachine childSession);
        /** Notify that the Child SA is deleted. */
        void onDeleteChildSa(int remoteSpi);
        // TODO: Add methods for handling errors and sending out locally built payloads.
    }

    /**
     * Callback for ChildSessionStateMachine to notify IkeSessionStateMachine.
     *
     * <p>Package private for being passed to only ChildSessionStateMachine.
     */
    class ChildSessionCallback implements IChildSessionCallback {
        public void onCreateChildSa(int remoteSpi, ChildSessionStateMachine childSession) {
            mSpiToChildSessionMap.put(remoteSpi, childSession);
        }

        public void onDeleteChildSa(int remoteSpi) {
            mSpiToChildSessionMap.remove(remoteSpi);
        }
    }

    /** Initial state of IkeSessionStateMachine. */
    class Initial extends State {
        @Override
        public void enter() {
            try {
                mIkeSocket = IkeSocket.getIkeSocket(mIkeSessionOptions.getUdpEncapsulationSocket());
            } catch (ErrnoException e) {
                // TODO: handle exception and close IkeSession.
            }
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_LOCAL_REQUEST_CREATE_IKE:
                    transitionTo(mCreateIkeLocalIkeInit);
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }

    /**
     * Closed represents the state when this IkeSessionStateMachine is closed, and no further
     * actions can be performed on it.
     */
    class Closed extends State {
        // TODO:Implement it.
    }

    /**
     * Idle represents a state when there is no ongoing IKE exchange affecting established IKE SA.
     */
    class Idle extends State {
        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_RECEIVE_IKE_PACKET:
                    deferMessage(message);
                    transitionTo(mReceiving);
                    return HANDLED;
                case CMD_LOCAL_REQUEST_REKEY_IKE:
                    transitionTo(mRekeyIkeLocalCreate);
                    return HANDLED;
                default:
                    return NOT_HANDLED;
                    // TODO: Add more cases for supporting local request.
            }
        }
    }

    /** Base state defines common behaviours when receiving an IKE packet. */
    private abstract class BaseState extends State {
        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_RECEIVE_IKE_PACKET:
                    handleReceivedIkePacket(message);
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }

        protected void handleReceivedIkePacket(Message message) {
            ReceivedIkePacket receivedIkePacket = (ReceivedIkePacket) message.obj;
            IkeHeader ikeHeader = receivedIkePacket.ikeHeader;
            byte[] ikePacketBytes = receivedIkePacket.ikePacketBytes;
            IkeSaRecord ikeSaRecord = getIkeSaRecordForPacket(ikeHeader);
            try {
                IkeMessage ikeMessage =
                        IkeMessage.decode(
                                mIkeSessionOptions, ikeSaRecord, ikeHeader, ikePacketBytes);
                int messageType = ikeMessage.getMessageType();
                // TODO: Handle fatal error notifications.
                handleIkeMessage(ikeMessage, messageType, message);
            } catch (IkeException e) {

            } catch (GeneralSecurityException e) {
                // IKE library failed on intergity checksum validation or on message decryption.
                // TODO: Handle decrypting exception
            }
        }

        // Default handler for decode errors in encrypted request.
        protected void handleDecodingErrorInEncryptedRequest(
                IkeException exception, IkeSaRecord ikeSaRecord) {
            switch (exception.errorCode) {
                case IkeNotifyPayload.NOTIFY_TYPE_UNSUPPORTED_CRITICAL_PAYLOAD:
                    // TODO: Send encrypted error notification.
                    return;
                case IkeNotifyPayload.NOTIFY_TYPE_INVALID_MAJOR_VERSION:
                    // TODO: Send unencrypted error notification.
                    return;
                case IkeNotifyPayload.NOTIFY_TYPE_INVALID_SYNTAX:
                    // TODO: Send encrypted error notification and close IKE session if Message ID
                    // and cryptogtaphic checksum were invalid.
                    return;
                default:
                    // Won't hit this case.
                    throw new UnsupportedOperationException("Unknown error decoding IKE Message.");
            }
        }

        // Default handler for decode errors in encrypted responses.
        // NOTE: The DeleteIkeLocal state MUST override this state to avoid the possibility of an
        // infinite loop.
        protected void handleDecodingErrorInEncryptedResponse(
                IkeException exception, IkeSaRecord ikeSaRecord) {
            // All errors in parsing or processing reponse packets should cause the IKE library to
            // initiate a Delete IKE Exchange.

            // TODO: Initiate Delete IKE Exchange
        }

        protected IkeSaRecord getIkeSaRecordForPacket(IkeHeader ikeHeader) {
            if (ikeHeader.fromIkeInitiator) {
                return mSpiToSaRecordMap.get(ikeHeader.ikeInitiatorSpi);
            } else {
                return mSpiToSaRecordMap.get(ikeHeader.ikeResponderSpi);
            }
        }

        protected abstract void handleIkeMessage(
                IkeMessage ikeMessage, int messageType, Message message);
    }

    /**
     * Receiving represents a state when idle IkeSessionStateMachine receives an incoming packet.
     */
    class Receiving extends BaseState {
        @Override
        protected void handleIkeMessage(IkeMessage ikeMessage, int messageType, Message message) {
            switch (messageType) {
                case IkeMessage.MESSAGE_TYPE_REKEY_IKE_REQ:
                    try {
                        validateIkeRekeyReq(ikeMessage);
                        // Reply
                        IkeMessage responseIkeMessage = buildIkeRekeyResp(ikeMessage);
                        // TODO: Encode and send out responseIkeMessage

                        mRemoteInitNewIkeSaRecord =
                                IkeSaRecord.makeNewIkeSaRecord(
                                        mCurrentIkeSaRecord, ikeMessage, responseIkeMessage);
                        addIkeSaRecord(mRemoteInitNewIkeSaRecord);
                        transitionTo(mRekeyIkeRemoteDelete);
                    } catch (IkeException e) {
                        // TODO: Handle processing errors.
                    }
                    return;
                    // TODO: Add more cases for supporting local request.
                default:
            }
        }
    }

    /**
     * LocalNewExchangeBase represents the common behaviours when IKE library initiates a new
     * exchange.
     */
    private abstract class LocalNewExchangeBase extends BaseState {
        protected IkeMessage mRequestMsg;
        protected byte[] mRequestPacket;

        @Override
        public void enter() {
            mRequestMsg = buildRequest();
            mRequestPacket = encodeRequest();
            mIkeSocket.sendIkePacket(mRequestPacket, mIkeSessionOptions.getServerAddress());
            // TODO: Send out packet and start retransmission timer.
        }

        @Override
        public void exit() {
            // TODO: Stop retransmission
            mRequestMsg = null;
            mRequestPacket = null;
        }

        protected abstract IkeMessage buildRequest();

        // CreateIkeLocalInit should override encodeRequest() to encode unencrypted packet
        protected byte[] encodeRequest() {
            // TODO: encrypt and encode mRequestMsg
            return new byte[0];
        }
    }

    /** CreateIkeLocalIkeInit represents state when IKE library initiates IKE_INIT exchange. */
    class CreateIkeLocalIkeInit extends LocalNewExchangeBase {

        @Override
        public void enter() {
            super.enter();
            mIkeSocket.registerIke(
                    mRequestMsg.ikeHeader.ikeInitiatorSpi, IkeSessionStateMachine.this);
        }

        @Override
        protected IkeMessage buildRequest() {
            return buildIkeInitReq();
        }

        @Override
        protected byte[] encodeRequest() {
            return mRequestMsg.encode();
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_RECEIVE_IKE_PACKET:
                    handleReceivedIkePacket(message);
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }

        protected void handleReceivedIkePacket(Message message) {
            ReceivedIkePacket receivedIkePacket = (ReceivedIkePacket) message.obj;
            IkeHeader ikeHeader = receivedIkePacket.ikeHeader;
            byte[] ikePacketBytes = receivedIkePacket.ikePacketBytes;
            try {
                IkeMessage ikeMessage = IkeMessage.decode(ikeHeader, ikePacketBytes);
                int messageType = ikeMessage.getMessageType();
                // TODO: Handle fatal error notifications.
                handleIkeMessage(ikeMessage, messageType, message);
            } catch (IkeException e) {
                // TODO:Since IKE_INIT is not protected, log and ignore this message.
            }
        }

        @Override
        protected void handleIkeMessage(IkeMessage ikeMessage, int messageType, Message message) {
            switch (messageType) {
                case IkeMessage.MESSAGE_TYPE_IKE_INIT_RESP:
                    try {
                        validateIkeInitResp(mRequestMsg, ikeMessage);
                        mCurrentIkeSaRecord =
                                IkeSaRecord.makeFirstIkeSaRecord(mRequestMsg, ikeMessage);
                        addIkeSaRecord(mCurrentIkeSaRecord);
                        transitionTo(mCreateIkeLocalIkeAuth);
                    } catch (IkeException e) {
                        // TODO: Handle processing errors.
                    }
                    return;
                default:
                    // TODO: Handle unexpected message type.
            }
        }

        @Override
        public void exit() {
            super.exit();
            // TODO: Store IKE_INIT request and response in mIkeSessionOptions for IKE_AUTH
        }
    }

    /** CreateIkeLocalIkeAuth represents state when IKE library initiates IKE_AUTH exchange. */
    class CreateIkeLocalIkeAuth extends LocalNewExchangeBase {
        @Override
        protected IkeMessage buildRequest() {
            return buildIkeAuthReq();
        }

        @Override
        protected void handleIkeMessage(IkeMessage ikeMessage, int messageType, Message message) {
            switch (messageType) {
                    // TODO: Handle EAP Authentication.
                case IkeMessage.MESSAGE_TYPE_IKE_AUTH_RESP:
                    try {
                        validateIkeAuthResp(mRequestMsg, ikeMessage);

                        ChildSessionStateMachine firstChild =
                                ChildSessionStateMachineFactory.makeChildSessionStateMachine(
                                        "ChildSessionStateMachine",
                                        getHandler().getLooper(),
                                        mFirstChildSessionOptions);
                        // TODO: Replace null input params to payload lists in IKE_AUTH request and
                        // IKE_AUTH response for negotiating Child SA.
                        firstChild.handleFirstChildExchange(null, null, new ChildSessionCallback());

                        transitionTo(mIdle);
                    } catch (IkeException e) {
                        // TODO: Handle processing errors.
                    }
                    return;
                default:
                    // TODO: Add more cases for other packet types (e.g. for receiving and sending
                    // EAP).
            }
        }
    }

    /** RekeyIkeLocalCreate represents state when IKE library initiates Rekey IKE exchange. */
    class RekeyIkeLocalCreate extends LocalNewExchangeBase {
        @Override
        public IkeMessage buildRequest() {
            return buildIkeRekeyReq();
        }

        @Override
        protected void handleIkeMessage(IkeMessage ikeMessage, int messageType, Message message) {
            switch (messageType) {
                case IkeMessage.MESSAGE_TYPE_REKEY_IKE_RESP:
                    try {
                        handleRekeyResp(ikeMessage);
                        transitionTo(mRekeyIkeLocalDelete);
                    } catch (IkeException e) {
                        // TODO: Handle processing errors.
                    }
                    return;
                case IkeMessage.MESSAGE_TYPE_REKEY_IKE_REQ:
                    try {
                        validateIkeRekeyReq(ikeMessage);
                        // Reply
                        IkeMessage responseIkeMessage = buildIkeRekeyResp(ikeMessage);
                        mRemoteInitNewIkeSaRecord =
                                IkeSaRecord.makeNewIkeSaRecord(
                                        mCurrentIkeSaRecord, ikeMessage, responseIkeMessage);
                        addIkeSaRecord(mRemoteInitNewIkeSaRecord);
                        // TODO: Encode and send responseIkeMessage.

                        transitionTo(mSimulRekeyIkeLocalCreate);
                    } catch (IkeException e) {
                        // TODO: Handle processing errors.
                    }
                    return;
                default:
                    // TODO: Add more cases for other packet types.
            }
        }

        // Is also called by SimulRekeyIkeLocalCreate to handle incoming rekey response.
        protected void handleRekeyResp(IkeMessage ikeMessage) throws IkeException {
            validateIkeRekeyResp(mRequestMsg, ikeMessage);
            mLocalInitNewIkeSaRecord =
                    IkeSaRecord.makeNewIkeSaRecord(mCurrentIkeSaRecord, mRequestMsg, ikeMessage);
            addIkeSaRecord(mLocalInitNewIkeSaRecord);
            // TODO: Stop retransmission
        }
    }

    /**
     * SimulRekeyIkeLocalCreate represents the state where IKE library has replied to rekey request
     * sent from the remote and is waiting for a rekey response for a locally initiated rekey
     * request.
     *
     * <p>SimulRekeyIkeLocalCreate extends RekeyIkeLocalCreate so that it can call super class to
     * validate incoming rekey response against locally initiated rekey request.
     */
    class SimulRekeyIkeLocalCreate extends RekeyIkeLocalCreate {
        @Override
        public void enter() {
            // Do not send request.
        }

        @Override
        public IkeMessage buildRequest() {
            throw new UnsupportedOperationException(
                    "Do not support sending request in " + getCurrentState().getName());
        }

        @Override
        public void exit() {
            // Do nothing.
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_RECEIVE_IKE_PACKET:
                    ReceivedIkePacket receivedIkePacket = (ReceivedIkePacket) message.obj;
                    IkeHeader ikeHeader = receivedIkePacket.ikeHeader;

                    if (mRemoteInitNewIkeSaRecord == getIkeSaRecordForPacket(ikeHeader)) {
                        deferMessage(message);
                    } else {
                        handleReceivedIkePacket(message);
                    }
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }

        @Override
        protected void handleIkeMessage(IkeMessage ikeMessage, int messageType, Message message) {
            switch (messageType) {
                case IkeMessage.MESSAGE_TYPE_DELETE_IKE_REQ:
                    deferMessage(message);
                    return;
                case IkeMessage.MESSAGE_TYPE_REKEY_IKE_RESP:
                    try {
                        super.handleRekeyResp(ikeMessage);
                        transitionTo(mSimulRekeyIkeLocalDeleteRemoteDelete);
                    } catch (IkeException e) {
                        // TODO: Handle processing errors.
                    }
                    return;
                default:
                    // TODO: Add more cases for other packet types.
            }
        }
    }

    /** RekeyIkeDeleteBase represents common behaviours of deleting stage during rekeying IKE SA. */
    private abstract class RekeyIkeDeleteBase extends BaseState {
        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_RECEIVE_IKE_PACKET:
                    ReceivedIkePacket receivedIkePacket = (ReceivedIkePacket) message.obj;
                    IkeHeader ikeHeader = receivedIkePacket.ikeHeader;

                    // Request received on the new/surviving SA; treat it as acknowledgement that
                    // remote has successfully rekeyed.
                    if (mIkeSaRecordSurviving == getIkeSaRecordForPacket(ikeHeader)) {
                        deferMessage(message);
                        // TODO: Locally close old (and losing) IKE SAs.
                        finishRekey();
                    } else {
                        handleReceivedIkePacket(message);
                    }
                    return HANDLED;
                default:
                    return NOT_HANDLED;
                    // TODO: Add more cases for other packet types.
            }
        }

        protected void finishRekey() {
            mCurrentIkeSaRecord = mIkeSaRecordSurviving;
            mLocalInitNewIkeSaRecord = null;
            mRemoteInitNewIkeSaRecord = null;

            mIkeSaRecordSurviving = null;
            mIkeSaRecordAwaitingLocalDel = null;
            mIkeSaRecordAwaitingRemoteDel = null;
        }
    }

    /**
     * SimulRekeyIkeLocalDeleteRemoteDelete represents the deleting stage during simultaneous
     * rekeying when IKE library is waiting for both a Delete request and a Delete response.
     */
    class SimulRekeyIkeLocalDeleteRemoteDelete extends RekeyIkeDeleteBase {
        @Override
        public void enter() {
            // Detemine surviving IKE SA. According to RFC 7296: "The new IKE SA containing the
            // lowest nonce SHOULD be deleted by the node that created it, and the other surviving
            // new IKE SA MUST inherit all the Child SAs."
            if (mLocalInitNewIkeSaRecord.compareTo(mRemoteInitNewIkeSaRecord) > 0) {
                mIkeSaRecordSurviving = mLocalInitNewIkeSaRecord;
                mIkeSaRecordAwaitingLocalDel = mCurrentIkeSaRecord;
                mIkeSaRecordAwaitingRemoteDel = mRemoteInitNewIkeSaRecord;
            } else {
                mIkeSaRecordSurviving = mRemoteInitNewIkeSaRecord;
                mIkeSaRecordAwaitingLocalDel = mLocalInitNewIkeSaRecord;
                mIkeSaRecordAwaitingRemoteDel = mCurrentIkeSaRecord;
            }
            IkeMessage ikeMessage = buildIkeDeleteReq(mIkeSaRecordAwaitingLocalDel);
            // TODO: Encode and send out delete request and start retransmission timer.
            // TODO: Set timer awaiting for delete request.
        }

        @Override
        protected void handleIkeMessage(IkeMessage ikeMessage, int messageType, Message message) {
            IkeSaRecord ikeSaRecordForPacket = getIkeSaRecordForPacket(ikeMessage.ikeHeader);
            switch (messageType) {
                case IkeMessage.MESSAGE_TYPE_DELETE_IKE_REQ:
                    if (ikeSaRecordForPacket == mIkeSaRecordAwaitingRemoteDel) {
                        try {
                            validateIkeDeleteReq(ikeMessage);
                            IkeMessage respMsg = buildIkeDeleteResp(mIkeSaRecordAwaitingRemoteDel);
                            removeIkeSaRecord(mIkeSaRecordAwaitingRemoteDel);
                            // TODO: Encode and send response and close
                            // mIkeSaRecordAwaitingRemoteDel.
                            // TODO: Stop timer awating delete request.
                            transitionTo(mSimulRekeyIkeLocalDelete);
                        } catch (IkeException e) {
                            // TODO: Handle processing errors.
                        }
                    } else {
                        // TODO: The other side deletes wrong IKE SA and we should close whole IKE
                        // session.
                    }
                    return;
                case IkeMessage.MESSAGE_TYPE_DELETE_IKE_RESP:
                    if (ikeSaRecordForPacket == mIkeSaRecordAwaitingLocalDel) {
                        try {
                            validateIkeDeleteResp(ikeMessage);
                            transitionTo(mSimulRekeyIkeRemoteDelete);
                            removeIkeSaRecord(mIkeSaRecordAwaitingLocalDel);
                            // TODO: Close mIkeSaRecordAwaitingLocalDel
                            // TODO: Stop retransmission timer
                        } catch (IkeException e) {
                            // TODO: Handle processing errors.
                        }
                    } else {
                        // TODO: Close whole IKE session
                    }
                    return;
                default:
                    // TODO: Add more cases for other packet types.
            }
        }

        @Override
        public void exit() {
            finishRekey();
            // TODO: Stop retransmission timer and awaiting delete request timer.
        }
    }

    /**
     * SimulRekeyIkeLocalDelete represents the state when IKE library is waiting for a Delete
     * response during simultaneous rekeying.
     */
    class SimulRekeyIkeLocalDelete extends RekeyIkeDeleteBase {
        @Override
        protected void handleIkeMessage(IkeMessage ikeMessage, int messageType, Message message) {
            switch (messageType) {
                case IkeMessage.MESSAGE_TYPE_DELETE_IKE_RESP:
                    try {
                        validateIkeDeleteResp(ikeMessage);
                        removeIkeSaRecord(mIkeSaRecordAwaitingLocalDel);
                        // TODO: Close mIkeSaRecordAwaitingLocalDel.
                        transitionTo(mIdle);
                    } catch (IkeException e) {
                        // TODO: Handle processing errors.
                    }
                    return;
                default:
                    // TODO: Add more cases for other packet types.
            }
        }
    }

    /**
     * SimulRekeyIkeRemoteDelete represents the state that waiting for a Delete request during
     * simultaneous rekeying.
     */
    class SimulRekeyIkeRemoteDelete extends RekeyIkeDeleteBase {
        // TODO: Implement methods for processing Delete response
        @Override
        protected void handleIkeMessage(IkeMessage ikeMessage, int messageType, Message message) {
            switch (messageType) {
                case IkeMessage.MESSAGE_TYPE_DELETE_IKE_REQ:
                    try {
                        validateIkeDeleteReq(ikeMessage);
                        IkeMessage respMsg = buildIkeDeleteResp(mIkeSaRecordAwaitingRemoteDel);
                        // TODO: Encode and send response and close mIkeSaRecordAwaitingRemoteDel
                        removeIkeSaRecord(mIkeSaRecordAwaitingRemoteDel);
                        transitionTo(mIdle);
                    } catch (IkeException e) {
                        // TODO: Handle processing errors.
                    }
                    return;
                default:
                    // TODO: Add more cases for other packet types.
            }
        }
    }

    /**
     * RekeyIkeLocalDelete represents the deleting stage when IKE library is initiating a Rekey
     * procedure.
     *
     * <p>RekeyIkeLocalDelete and SimulRekeyIkeLocalDelete have same behaviours in processMessage().
     * While RekeyIkeLocalDelete overrides enter() and exit() methods for initiating and finishing
     * the deleting stage for IKE rekeying.
     */
    class RekeyIkeLocalDelete extends SimulRekeyIkeLocalDelete {
        @Override
        public void enter() {
            mIkeSaRecordSurviving = mLocalInitNewIkeSaRecord;
            mIkeSaRecordAwaitingLocalDel = mCurrentIkeSaRecord;
            IkeMessage ikeMessage = buildIkeDeleteReq(mIkeSaRecordAwaitingLocalDel);
            // TODO: Encode ikeMessage, send out packet and start retransmission timer.
        }

        @Override
        public void exit() {
            finishRekey();
            // TODO: Stop retransmission.
        }
    }

    /**
     * RekeyIkeRemoteDelete represents the deleting stage when responding to a Rekey procedure.
     *
     * <p>RekeyIkeRemoteDelete and SimulRekeyIkeRemoteDelete have same behaviours in
     * processMessage(). While RekeyIkeLocalDelete overrides enter() and exit() methods for waiting
     * incoming delete request and for finishing the deleting stage for IKE rekeying.
     */
    class RekeyIkeRemoteDelete extends SimulRekeyIkeRemoteDelete {
        @Override
        public void enter() {
            mIkeSaRecordSurviving = mRemoteInitNewIkeSaRecord;
            mIkeSaRecordAwaitingRemoteDel = mCurrentIkeSaRecord;
            // TODO: Set timer awaiting delete request.
        }

        @Override
        public void exit() {
            finishRekey();
            // TODO: Stop timer awaiting delete request.
        }
    }
}
