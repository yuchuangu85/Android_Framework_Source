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

import com.android.ike.ikev2.message.IkeMessage;
import com.android.ike.ikev2.message.IkePayload;
import com.android.internal.annotations.VisibleForTesting;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SaRecord represents common information of an IKE SA and a Child SA.
 *
 * <p>When doing rekey, there can be multiple SAs in the same IkeSessionStateMachine or
 * ChildSessionStateMachine, where they use same cryptographic algorithms but with different keys.
 * We store cryptographic algorithms and unchanged SA configurations in IkeSessionOptions or
 * ChildSessionOptions and store changed information including keys, SPIs, and nonces in SaRecord.
 */
public abstract class SaRecord {

    private static ISaRecordHelper sSaRecordHelper = new SaRecordHelper();

    public final byte[] nonceInitiator;
    public final byte[] nonceResponder;

    /** Package private */
    SaRecord(byte[] nonceInit, byte[] nonceResp) {
        nonceInitiator = nonceInit;
        nonceResponder = nonceResp;
    }

    /**
     * SaRecordHelper implements methods for constructing SaRecord.
     *
     * <p>Package private
     */
    static class SaRecordHelper implements ISaRecordHelper {
        @Override
        public IkeSaRecord makeFirstIkeSaRecord(IkeMessage initRequest, IkeMessage initResponse) {
            // TODO: Generate keying materials
            return null;
        }

        @Override
        public IkeSaRecord makeNewIkeSaRecord(
                IkeSaRecord oldSaRecord, IkeMessage rekeyRequest, IkeMessage rekeyResponse) {
            // TODO: Generate keying materials based on old SK_d
            return null;
        }

        @Override
        public ChildSaRecord makeChildSaRecord(
                List<IkePayload> reqPayloads, List<IkePayload> respPayloads) {
            // TODO: Calculate keys and build IpSecTransform.
            return null;
        }
    }

    /** Package private */
    static void setSaRecordHelper(ISaRecordHelper helper) {
        sSaRecordHelper = helper;
    }

    /** IkeSaRecord represents an IKE SA. */
    public static class IkeSaRecord extends SaRecord implements Comparable<IkeSaRecord> {

        /** SPI of IKE SA initiator */
        public final long initiatorSpi;
        /** SPI of IKE SA responder */
        public final long responderSpi;
        /** Flag indicates if this IKE SA is locally initiated */
        public final boolean isLocalInit;

        /** Package private */
        IkeSaRecord(
                long initSpi, long respSpi, boolean localInit, byte[] nonceInit, byte[] nonceResp) {
            super(nonceInit, nonceResp);
            initiatorSpi = initSpi;
            responderSpi = respSpi;
            isLocalInit = localInit;
            // TODO: Impement constructor. There will be more input parameters.
        }

        /** Package private */
        static IkeSaRecord makeFirstIkeSaRecord(IkeMessage initRequest, IkeMessage initResponse) {
            return sSaRecordHelper.makeFirstIkeSaRecord(initRequest, initResponse);
        }

        /** Package private */
        static IkeSaRecord makeNewIkeSaRecord(
                IkeSaRecord oldSaRecord, IkeMessage rekeyRequest, IkeMessage rekeyResponse) {
            return sSaRecordHelper.makeNewIkeSaRecord(oldSaRecord, rekeyRequest, rekeyResponse);
        }

        /** Package private */
        long getRemoteSpi() {
            if (isLocalInit) {
                return responderSpi;
            } else {
                return initiatorSpi;
            }
        }

        /**
         * Compare with a specific IkeSaRecord
         *
         * @param record IkeSaRecord to be compared.
         * @return a negative integer if input IkeSaRecord contains lowest nonce; a positive integer
         *     if this IkeSaRecord has lowest nonce; return zero if lowest nonces of two
         *     IkeSaRecords match.
         */
        public int compareTo(IkeSaRecord record) {
            // TODO: Implement it b/122924815.
            return 1;
        }
    }

    /** ChildSaRecord represents an Child SA. */
    public static class ChildSaRecord extends SaRecord implements Comparable<ChildSaRecord> {

        /** Locally generated SPI for receiving IPsec Packet. */
        public final int inboundSpi;
        /** Remotely generated SPI for sending IPsec Packet. */
        public final int outboundSpi;

        /** Package private */
        ChildSaRecord(int inSpi, int outSpi, byte[] nonceInit, byte[] nonceResp) {
            super(nonceInit, nonceResp);
            inboundSpi = inSpi;
            outboundSpi = outSpi;
            // TODO: Impement constructor. Will be more input parameters.
        }

        /** Package private */
        static ChildSaRecord makeChildSaRecord(
                List<IkePayload> reqPayloads, List<IkePayload> respPayloads) {
            return sSaRecordHelper.makeChildSaRecord(reqPayloads, respPayloads);
        }

        /**
         * Compare with a specific ChildSaRecord
         *
         * @param record ChildSaRecord to be compared.
         * @return a negative integer if input ChildSaRecord contains lowest nonce; a positive
         *     integer if this ChildSaRecord has lowest nonce; return zero if lowest nonces of two
         *     ChildSaRecord match.
         */
        public int compareTo(ChildSaRecord record) {
            // TODO: Implement it b/122924815
            return 1;
        }
    }

    /**
     * ISaRecordHelper provides a package private interface for constructing SaRecord.
     *
     * <p>ISaRecordHelper exists so that the interface is injectable for testing.
     */
    interface ISaRecordHelper {
        /**
         * Construct IkeSaRecord as results of IKE initial exchange.
         *
         * @param initRequest IKE_INIT request.
         * @param initResponse IKE_INIT request.
         * @return ikeSaRecord for initial IKE SA.
         */
        IkeSaRecord makeFirstIkeSaRecord(IkeMessage initRequest, IkeMessage initResponse);

        /**
         * Construct new IkeSaRecord when doing rekey.
         *
         * @param oldSaRecord old IKE SA
         * @param rekeyRequest Rekey IKE request.
         * @param rekeyResponse Rekey IKE response.
         * @return ikeSaRecord for new IKE SA.
         */
        IkeSaRecord makeNewIkeSaRecord(
                IkeSaRecord oldSaRecord, IkeMessage rekeyRequest, IkeMessage rekeyResponse);

        /**
         * Construct ChildSaRecord and generate IpSecTransform pairs.
         *
         * @param reqPayloads payload list in request.
         * @param respPayloads payload list in response.
         * @return new Child SA.
         */
        ChildSaRecord makeChildSaRecord(
                List<IkePayload> reqPayloads, List<IkePayload> respPayloads);
    }

    /** Generate SKEYSEED using negotiated PRF. */
    @VisibleForTesting
    static byte[] generateSKeySeed(
            String prfAlgorithm, byte[] nonceInit, byte[] nonceResp, byte[] sharedDhKey) {
        try {
            ByteBuffer keyBuffer = ByteBuffer.allocate(nonceInit.length + nonceResp.length);
            keyBuffer.put(nonceInit).put(nonceResp);
            SecretKeySpec prfKeySpec = new SecretKeySpec(keyBuffer.array(), prfAlgorithm);

            Mac prfMac = Mac.getInstance(prfAlgorithm, IkeMessage.getSecurityProvider());
            prfMac.init(prfKeySpec);

            ByteBuffer sharedKeyBuffer = ByteBuffer.wrap(sharedDhKey);
            prfMac.update(sharedKeyBuffer);

            return prfMac.doFinal();
        } catch (InvalidKeyException | NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Failed to generate SKEYSEED", e);
        }
    }

    /**
     * Derives key materials using negotiated PRF.
     *
     * <p>prf+(K, S) outputs a pseudorandom stream by using negotiated PRF iteratively. In this way
     * it can generate long enough keying material containing all the keys for this IKE/Child SA.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7296#section-2.13">RFC 7296 nternet Key Exchange
     *     Protocol Version 2 (IKEv2) 2.13. Generating Keying Material </a>
     */
    @VisibleForTesting
    static byte[] generateKeyMat(
            String prfAlgorithm, byte[] prfKey, byte[] dataToSign, int keyMaterialLen)
            throws InvalidKeyException {
        try {
            SecretKeySpec prfKeySpec = new SecretKeySpec(prfKey, prfAlgorithm);
            Mac prfMac = Mac.getInstance(prfAlgorithm, IkeMessage.getSecurityProvider());

            ByteBuffer keyMatBuffer = ByteBuffer.allocate(keyMaterialLen);

            byte[] previousMac = new byte[0];
            final int padLen = 1;
            byte padValue = 1;

            while (keyMatBuffer.remaining() > 0) {
                prfMac.init(prfKeySpec);

                ByteBuffer dataToSignBuffer =
                        ByteBuffer.allocate(previousMac.length + dataToSign.length + padLen);
                dataToSignBuffer.put(previousMac).put(dataToSign).put(padValue);
                dataToSignBuffer.rewind();

                prfMac.update(dataToSignBuffer);

                previousMac = prfMac.doFinal();
                keyMatBuffer.put(
                        previousMac, 0, Math.min(previousMac.length, keyMatBuffer.remaining()));

                padValue++;
            }

            return keyMatBuffer.array();
        } catch (InvalidKeyException | NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Failed to generate keying material", e);
        }
    }
}
