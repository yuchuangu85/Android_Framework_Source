/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.ike.ikev2.message;

import static com.android.ike.ikev2.SaProposal.DhGroup;
import static com.android.ike.ikev2.SaProposal.EncryptionAlgorithm;
import static com.android.ike.ikev2.SaProposal.IntegrityAlgorithm;
import static com.android.ike.ikev2.SaProposal.PseudorandomFunction;

import android.annotation.IntDef;
import android.util.ArraySet;
import android.util.Pair;

import com.android.ike.ikev2.SaProposal;
import com.android.ike.ikev2.exceptions.IkeException;
import com.android.ike.ikev2.exceptions.InvalidSyntaxException;
import com.android.ike.ikev2.exceptions.NoValidProposalChosenException;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * IkeSaPayload represents a Security Association payload. It contains one or more {@link Proposal}.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.3">RFC 7296, Internet Key Exchange
 *     Protocol Version 2 (IKEv2)</a>
 */
public final class IkeSaPayload extends IkePayload {
    public final boolean isSaResponse;
    public final List<Proposal> proposalList;
    /**
     * Construct an instance of IkeSaPayload for decoding an inbound packet.
     *
     * @param critical indicates if this payload is critical. Ignored in supported payload as
     *     instructed by the RFC 7296.
     * @param isResp indicates if this payload is in a response message.
     * @param payloadBody the encoded payload body in byte array.
     */
    IkeSaPayload(boolean critical, boolean isResp, byte[] payloadBody) throws IkeException {
        super(IkePayload.PAYLOAD_TYPE_SA, critical);

        ByteBuffer inputBuffer = ByteBuffer.wrap(payloadBody);
        proposalList = new LinkedList<>();
        while (inputBuffer.hasRemaining()) {
            Proposal proposal = Proposal.readFrom(inputBuffer);
            proposalList.add(proposal);
        }

        // An SA response must have exactly one SA proposal.
        if (isResp && proposalList.size() != 1) {
            throw new InvalidSyntaxException(
                    "Expected only one negotiated proposal from SA response: "
                            + "Multiple negotiated proposals found.");
        }
        isSaResponse = isResp;
    }

    /**
     * Construct an instance of IkeSaPayload for building outbound packet.
     *
     * <p>The length of spis must be the same as saProposals.
     *
     * @param isResp indicates if this payload is in a response message.
     * @param isIkeSa indicates if this payload is for IKE SA or Child SA
     * @param spiSize the size of attached SPIs.
     * @param spis the array of all attached SPIs.
     * @param saProposals the array of all SA Proposals.
     */
    public IkeSaPayload(
            boolean isResp, boolean isIkeSa, byte spiSize, long[] spis, SaProposal[] saProposals) {
        super(IkePayload.PAYLOAD_TYPE_SA, false);

        if (saProposals.length < 1
                || isResp && (saProposals.length > 1)
                || saProposals.length != spis.length) {
            throw new IllegalArgumentException("Invalid SA payload.");
        }

        // TODO: Check that saProposals.length <= 255 in IkeSessionOptions and ChildSessionOptions
        isSaResponse = isResp;

        proposalList = new ArrayList<Proposal>(saProposals.length);
        int protocolId = isIkeSa ? PROTOCOL_ID_IKE : PROTOCOL_ID_ESP;
        for (int i = 0; i < saProposals.length; i++) {
            // Proposal number must start from 1.
            Proposal proposal =
                    new Proposal(
                            (byte) (i + 1) /*proposal number*/,
                            protocolId,
                            spiSize,
                            spis[i],
                            saProposals[i],
                            false /*does not have unrecognized Transform*/);
            proposalList.add(proposal);
        }
    }

    /**
     * Construct an instance of IkeSaPayload for building outbound IKE initial setup request.
     *
     * <p>According to RFC 7296, for an initial IKE SA negotiation, no SPI is included in SA
     * Proposal. IKE library, as a client, only supports requesting this initial negotiation.
     *
     * @param saProposals the array of all SA Proposals.
     */
    public IkeSaPayload(SaProposal[] saProposals) {
        this(
                false /*is request*/,
                true /*is IKE SA*/,
                (byte) 0,
                new long[saProposals.length],
                saProposals);
    }

    /**
     * Validate and return the negotiated SA proposal from the received SA payload.
     *
     * @param reqSaPayload SA payload from SA initiator to validate against.
     * @return the validated negotiated SA proposal.
     * @throws NoValidProposalChosenException if received SA proposal is invalid.
     */
    public SaProposal getVerifiedNegotiatedProposal(IkeSaPayload reqSaPayload)
            throws NoValidProposalChosenException {
        if (!isSaResponse) {
            throw new UnsupportedOperationException(
                    "Cannot get negotiated SA proposal from a request message.");
        }

        // If negotiated proposal has an unrecognized Transform, throw an exception.
        Proposal respProposal = proposalList.get(0);
        if (respProposal.hasUnrecognizedTransform) {
            throw new NoValidProposalChosenException(
                    "Negotiated proposal has unrecognized Transform.");
        }

        // In SA request payload, the first proposal MUST be 1, and subsequent proposals MUST be one
        // more than the previous proposal. In SA response payload, the negotiated proposal number
        // MUST match the selected proposal number in SA request Payload.
        int negotiatedProposalNum = respProposal.number;
        List<Proposal> reqProposalList = reqSaPayload.proposalList;
        if (negotiatedProposalNum < 1 || negotiatedProposalNum > reqProposalList.size()) {
            throw new NoValidProposalChosenException(
                    "Negotiated proposal has invalid proposal number.");
        }

        Proposal reqProposal = reqProposalList.get(negotiatedProposalNum - 1);
        if (!respProposal.isNegotiatedFrom(reqProposal)) {
            throw new NoValidProposalChosenException("Invalid negotiated proposal.");
        }
        return respProposal.saProposal;
    }

    @VisibleForTesting
    interface TransformDecoder {
        Transform[] decodeTransforms(int count, ByteBuffer inputBuffer) throws IkeException;
    }

    // TODO: Add another constructor for building outbound message.

    /**
     * Proposal represents a set contains cryptographic algorithms and key generating materials. It
     * contains multiple {@link Transform}.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.3.1">RFC 7296, Internet Key
     *     Exchange Protocol Version 2 (IKEv2)</a>
     *     <p>Proposals with an unrecognized Protocol ID, containing an unrecognized Transform Type
     *     or lacking a necessary Transform Type shall be ignored when processing a received SA
     *     Payload.
     */
    public static final class Proposal {
        private static final byte LAST_PROPOSAL = 0;
        private static final byte NOT_LAST_PROPOSAL = 2;

        private static final int PROPOSAL_RESERVED_FIELD_LEN = 1;
        private static final int PROPOSAL_HEADER_LEN = 8;

        @VisibleForTesting
        static TransformDecoder sTransformDecoder =
                new TransformDecoder() {
                    @Override
                    public Transform[] decodeTransforms(int count, ByteBuffer inputBuffer)
                            throws IkeException {
                        Transform[] transformArray = new Transform[count];
                        for (int i = 0; i < count; i++) {
                            Transform transform = Transform.readFrom(inputBuffer);
                            if (transform.isSupported) {
                                transformArray[i] = transform;
                            }
                        }
                        return transformArray;
                    }
                };

        public final byte number;
        /** All supported protocol will fall into {@link ProtocolId} */
        public final int protocolId;

        public final byte spiSize;
        public final long spi;

        public final SaProposal saProposal;

        public final boolean hasUnrecognizedTransform;

        // TODO: Validate this proposal

        @VisibleForTesting
        Proposal(
                byte number,
                int protocolId,
                byte spiSize,
                long spi,
                SaProposal saProposal,
                boolean hasUnrecognizedTransform) {
            this.number = number;
            this.protocolId = protocolId;
            this.spiSize = spiSize;
            this.spi = spi;
            this.saProposal = saProposal;
            this.hasUnrecognizedTransform = hasUnrecognizedTransform;
        }

        @VisibleForTesting
        static Proposal readFrom(ByteBuffer inputBuffer) throws IkeException {
            byte isLast = inputBuffer.get();
            if (isLast != LAST_PROPOSAL && isLast != NOT_LAST_PROPOSAL) {
                throw new InvalidSyntaxException(
                        "Invalid value of Last Proposal Substructure: " + isLast);
            }
            // Skip RESERVED byte
            inputBuffer.get(new byte[PROPOSAL_RESERVED_FIELD_LEN]);

            int length = Short.toUnsignedInt(inputBuffer.getShort());
            byte number = inputBuffer.get();
            int protocolId = Byte.toUnsignedInt(inputBuffer.get());

            byte spiSize = inputBuffer.get();
            int transformCount = Byte.toUnsignedInt(inputBuffer.get());

            // TODO: Add check: spiSize must be 0 in initial IKE SA negotiation
            // spiSize should be either 8 for IKE or 4 for IPsec.
            long spi = SPI_NOT_INCLUDED;
            switch (spiSize) {
                case SPI_LEN_NOT_INCLUDED:
                    // No SPI attached for IKE initial exchange.
                    break;
                case SPI_LEN_IPSEC:
                    spi = Integer.toUnsignedLong(inputBuffer.getInt());
                    break;
                case SPI_LEN_IKE:
                    spi = inputBuffer.getLong();
                    break;
                default:
                    throw new InvalidSyntaxException(
                            "Invalid value of spiSize in Proposal Substructure: " + spiSize);
            }

            Transform[] transformArray =
                    sTransformDecoder.decodeTransforms(transformCount, inputBuffer);
            // TODO: Validate that sum of all Transforms' lengths plus Proposal header length equals
            // to Proposal's length.

            List<EncryptionTransform> encryptAlgoList = new LinkedList<>();
            List<PrfTransform> prfList = new LinkedList<>();
            List<IntegrityTransform> integAlgoList = new LinkedList<>();
            List<DhGroupTransform> dhGroupList = new LinkedList<>();
            List<EsnTransform> esnList = new LinkedList<>();

            boolean hasUnrecognizedTransform = false;

            for (Transform transform : transformArray) {
                switch (transform.type) {
                    case Transform.TRANSFORM_TYPE_ENCR:
                        encryptAlgoList.add((EncryptionTransform) transform);
                        break;
                    case Transform.TRANSFORM_TYPE_PRF:
                        prfList.add((PrfTransform) transform);
                        break;
                    case Transform.TRANSFORM_TYPE_INTEG:
                        integAlgoList.add((IntegrityTransform) transform);
                        break;
                    case Transform.TRANSFORM_TYPE_DH:
                        dhGroupList.add((DhGroupTransform) transform);
                        break;
                    case Transform.TRANSFORM_TYPE_ESN:
                        esnList.add((EsnTransform) transform);
                        break;
                    default:
                        hasUnrecognizedTransform = true;
                }
            }

            SaProposal saProposal =
                    new SaProposal(
                            protocolId,
                            encryptAlgoList.toArray(
                                    new EncryptionTransform[encryptAlgoList.size()]),
                            prfList.toArray(new PrfTransform[prfList.size()]),
                            integAlgoList.toArray(new IntegrityTransform[integAlgoList.size()]),
                            dhGroupList.toArray(new DhGroupTransform[dhGroupList.size()]),
                            esnList.toArray(new EsnTransform[esnList.size()]));

            return new Proposal(
                    number, protocolId, spiSize, spi, saProposal, hasUnrecognizedTransform);
        }
        // TODO: Add another contructor for encoding.

        /** Package private */
        boolean isNegotiatedFrom(Proposal reqProposal) {
            if (protocolId != reqProposal.protocolId || number != reqProposal.number) {
                return false;
            }
            return saProposal.isNegotiatedFrom(reqProposal.saProposal);
        }

        protected void encodeToByteBuffer(boolean isLast, ByteBuffer byteBuffer) {
            Transform[] allTransforms = saProposal.getAllTransforms();
            byte isLastIndicator = isLast ? LAST_PROPOSAL : NOT_LAST_PROPOSAL;

            byteBuffer
                    .put(isLastIndicator)
                    .put(new byte[PROPOSAL_RESERVED_FIELD_LEN])
                    .putShort((short) getProposalLength())
                    .put(number)
                    .put((byte) protocolId)
                    .put(spiSize)
                    .put((byte) allTransforms.length);

            switch (spiSize) {
                case SPI_LEN_NOT_INCLUDED:
                    // No SPI attached for IKE initial exchange.
                    break;
                case SPI_LEN_IPSEC:
                    byteBuffer.putInt((int) spi);
                    break;
                case SPI_LEN_IKE:
                    byteBuffer.putLong((long) spi);
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Invalid value of spiSize in Proposal Substructure: " + spiSize);
            }

            // Encode all Transform.
            for (int i = 0; i < allTransforms.length; i++) {
                // The last transform has the isLast flag set to true.
                allTransforms[i].encodeToByteBuffer(i == allTransforms.length - 1, byteBuffer);
            }
        }

        protected int getProposalLength() {
            int len = PROPOSAL_HEADER_LEN + spiSize;

            Transform[] allTransforms = saProposal.getAllTransforms();
            for (Transform t : allTransforms) len += t.getTransformLength();
            return len;
        }
    }

    @VisibleForTesting
    interface AttributeDecoder {
        List<Attribute> decodeAttributes(int length, ByteBuffer inputBuffer) throws IkeException;
    }

    /**
     * Transform is an abstract base class that represents the common information for all Transform
     * types. It may contain one or more {@link Attribute}.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.3.2">RFC 7296, Internet Key
     *     Exchange Protocol Version 2 (IKEv2)</a>
     *     <p>Transforms with unrecognized Transform ID or containing unrecognized Attribute Type
     *     shall be ignored when processing received SA payload.
     */
    public abstract static class Transform {

        @Retention(RetentionPolicy.SOURCE)
        @IntDef({
            TRANSFORM_TYPE_ENCR,
            TRANSFORM_TYPE_PRF,
            TRANSFORM_TYPE_INTEG,
            TRANSFORM_TYPE_DH,
            TRANSFORM_TYPE_ESN
        })
        public @interface TransformType {}

        public static final int TRANSFORM_TYPE_ENCR = 1;
        public static final int TRANSFORM_TYPE_PRF = 2;
        public static final int TRANSFORM_TYPE_INTEG = 3;
        public static final int TRANSFORM_TYPE_DH = 4;
        public static final int TRANSFORM_TYPE_ESN = 5;

        private static final byte LAST_TRANSFORM = 0;
        private static final byte NOT_LAST_TRANSFORM = 3;

        // Length of reserved field of a Transform.
        private static final int TRANSFORM_RESERVED_FIELD_LEN = 1;

        // Length of the Transform that with no Attribute.
        protected static final int BASIC_TRANSFORM_LEN = 8;

        // TODO: Add constants for supported algorithms

        @VisibleForTesting
        static AttributeDecoder sAttributeDecoder =
                new AttributeDecoder() {
                    public List<Attribute> decodeAttributes(int length, ByteBuffer inputBuffer)
                            throws IkeException {
                        List<Attribute> list = new LinkedList<>();
                        int parsedLength = BASIC_TRANSFORM_LEN;
                        while (parsedLength < length) {
                            Pair<Attribute, Integer> pair = Attribute.readFrom(inputBuffer);
                            parsedLength += pair.second;
                            list.add(pair.first);
                        }
                        // TODO: Validate that parsedLength equals to length.
                        return list;
                    }
                };

        // Only supported type falls into {@link TransformType}
        public final int type;
        public final int id;
        public final boolean isSupported;

        /** Construct an instance of Transform for building an outbound packet. */
        protected Transform(int type, int id) {
            this.type = type;
            this.id = id;
            if (!isSupportedTransformId(id)) {
                throw new IllegalArgumentException(
                        "Unsupported " + getTransformTypeString() + " Algorithm ID: " + id);
            }
            this.isSupported = true;
        }

        /** Construct an instance of Transform for decoding an inbound packet. */
        protected Transform(int type, int id, List<Attribute> attributeList) {
            this.type = type;
            this.id = id;
            this.isSupported =
                    isSupportedTransformId(id) && !hasUnrecognizedAttribute(attributeList);
        }

        @VisibleForTesting
        static Transform readFrom(ByteBuffer inputBuffer) throws IkeException {
            byte isLast = inputBuffer.get();
            if (isLast != LAST_TRANSFORM && isLast != NOT_LAST_TRANSFORM) {
                throw new InvalidSyntaxException(
                        "Invalid value of Last Transform Substructure: " + isLast);
            }

            // Skip RESERVED byte
            inputBuffer.get(new byte[TRANSFORM_RESERVED_FIELD_LEN]);

            int length = Short.toUnsignedInt(inputBuffer.getShort());
            int type = Byte.toUnsignedInt(inputBuffer.get());

            // Skip RESERVED byte
            inputBuffer.get(new byte[TRANSFORM_RESERVED_FIELD_LEN]);

            int id = Short.toUnsignedInt(inputBuffer.getShort());

            // Decode attributes
            List<Attribute> attributeList = sAttributeDecoder.decodeAttributes(length, inputBuffer);

            validateAttributeUniqueness(attributeList);

            switch (type) {
                case TRANSFORM_TYPE_ENCR:
                    return new EncryptionTransform(id, attributeList);
                case TRANSFORM_TYPE_PRF:
                    return new PrfTransform(id, attributeList);
                case TRANSFORM_TYPE_INTEG:
                    return new IntegrityTransform(id, attributeList);
                case TRANSFORM_TYPE_DH:
                    return new DhGroupTransform(id, attributeList);
                case TRANSFORM_TYPE_ESN:
                    return new EsnTransform(id, attributeList);
                default:
                    return new UnrecognizedTransform(type, id, attributeList);
            }
        }

        // Throw InvalidSyntaxException if there are multiple Attributes of the same type
        private static void validateAttributeUniqueness(List<Attribute> attributeList)
                throws IkeException {
            Set<Integer> foundTypes = new ArraySet<>();
            for (Attribute attr : attributeList) {
                if (!foundTypes.add(attr.type)) {
                    throw new InvalidSyntaxException(
                            "There are multiple Attributes of the same type. ");
                }
            }
        }

        // Check if there is Attribute with unrecognized type.
        protected abstract boolean hasUnrecognizedAttribute(List<Attribute> attributeList);

        // Check if this Transform ID is supported.
        protected abstract boolean isSupportedTransformId(int id);

        // Encode Transform to a ByteBuffer.
        protected abstract void encodeToByteBuffer(boolean isLast, ByteBuffer byteBuffer);

        // Get entire Transform length.
        protected abstract int getTransformLength();

        protected void encodeBasicTransformToByteBuffer(boolean isLast, ByteBuffer byteBuffer) {
            byte isLastIndicator = isLast ? LAST_TRANSFORM : NOT_LAST_TRANSFORM;
            byteBuffer
                    .put(isLastIndicator)
                    .put(new byte[TRANSFORM_RESERVED_FIELD_LEN])
                    .putShort((short) getTransformLength())
                    .put((byte) type)
                    .put(new byte[TRANSFORM_RESERVED_FIELD_LEN])
                    .putShort((short) id);
        }

        /**
         * Get Tranform Type as a String.
         *
         * @return Tranform Type as a String.
         */
        public abstract String getTransformTypeString();

        // TODO: Add abstract getTransformIdString() to return specific algorithm/dhGroup name
    }

    /**
     * EncryptionTransform represents an encryption algorithm. It may contain an Atrribute
     * specifying the key length.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.3.2">RFC 7296, Internet Key
     *     Exchange Protocol Version 2 (IKEv2)</a>
     */
    public static final class EncryptionTransform extends Transform {
        private static final int KEY_LEN_UNSPECIFIED = 0;

        // When using encryption algorithm with variable-length keys, mSpecifiedKeyLength MUST be
        // set and a KeyLengthAttribute MUST be attached. Otherwise, mSpecifiedKeyLength MUST NOT be
        // set and KeyLengthAttribute MUST NOT be attached.
        private final int mSpecifiedKeyLength;

        /**
         * Contruct an instance of EncryptionTransform with fixed key length for building an
         * outbound packet.
         *
         * @param id the IKE standard Transform ID.
         */
        public EncryptionTransform(@EncryptionAlgorithm int id) {
            this(id, KEY_LEN_UNSPECIFIED);
        }

        /**
         * Contruct an instance of EncryptionTransform with variable key length for building an
         * outbound packet.
         *
         * @param id the IKE standard Transform ID.
         * @param specifiedKeyLength the specified key length of this encryption algorithm.
         */
        public EncryptionTransform(@EncryptionAlgorithm int id, int specifiedKeyLength) {
            super(Transform.TRANSFORM_TYPE_ENCR, id);

            mSpecifiedKeyLength = specifiedKeyLength;
            try {
                validateKeyLength();
            } catch (InvalidSyntaxException e) {
                throw new IllegalArgumentException(e);
            }
        }

        /**
         * Contruct an instance of EncryptionTransform for decoding an inbound packet.
         *
         * @param id the IKE standard Transform ID.
         * @param attributeList the decoded list of Attribute.
         * @throws InvalidSyntaxException for syntax error.
         */
        protected EncryptionTransform(int id, List<Attribute> attributeList)
                throws InvalidSyntaxException {
            super(Transform.TRANSFORM_TYPE_ENCR, id, attributeList);
            if (!isSupported) {
                mSpecifiedKeyLength = KEY_LEN_UNSPECIFIED;
            } else {
                if (attributeList.size() == 0) {
                    mSpecifiedKeyLength = KEY_LEN_UNSPECIFIED;
                } else {
                    KeyLengthAttribute attr = getKeyLengthAttribute(attributeList);
                    mSpecifiedKeyLength = attr.keyLength;
                }
                validateKeyLength();
            }
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, id, mSpecifiedKeyLength);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof EncryptionTransform)) return false;

            EncryptionTransform other = (EncryptionTransform) o;
            return (type == other.type
                    && id == other.id
                    && mSpecifiedKeyLength == other.mSpecifiedKeyLength);
        }

        @Override
        protected boolean isSupportedTransformId(int id) {
            return SaProposal.isSupportedEncryptionAlgorithm(id);
        }

        @Override
        protected boolean hasUnrecognizedAttribute(List<Attribute> attributeList) {
            for (Attribute attr : attributeList) {
                if (attr instanceof UnrecognizedAttribute) {
                    return true;
                }
            }
            return false;
        }

        private KeyLengthAttribute getKeyLengthAttribute(List<Attribute> attributeList) {
            for (Attribute attr : attributeList) {
                if (attr.type == Attribute.ATTRIBUTE_TYPE_KEY_LENGTH) {
                    return (KeyLengthAttribute) attr;
                }
            }
            throw new IllegalArgumentException("Cannot find Attribute with Key Length type");
        }

        private void validateKeyLength() throws InvalidSyntaxException {
            switch (id) {
                case SaProposal.ENCRYPTION_ALGORITHM_3DES:
                    if (mSpecifiedKeyLength != KEY_LEN_UNSPECIFIED) {
                        throw new InvalidSyntaxException(
                                "Must not set Key Length value for this "
                                        + getTransformTypeString()
                                        + " Algorithm ID: "
                                        + id);
                    }
                    return;
                case SaProposal.ENCRYPTION_ALGORITHM_AES_CBC:
                    /* fall through */
                case SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_8:
                    /* fall through */
                case SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_12:
                    /* fall through */
                case SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16:
                    if (mSpecifiedKeyLength == KEY_LEN_UNSPECIFIED) {
                        throw new InvalidSyntaxException(
                                "Must set Key Length value for this "
                                        + getTransformTypeString()
                                        + " Algorithm ID: "
                                        + id);
                    }
                    if (mSpecifiedKeyLength != SaProposal.KEY_LEN_AES_128
                            && mSpecifiedKeyLength != SaProposal.KEY_LEN_AES_192
                            && mSpecifiedKeyLength != SaProposal.KEY_LEN_AES_256) {
                        throw new InvalidSyntaxException(
                                "Invalid key length for this "
                                        + getTransformTypeString()
                                        + " Algorithm ID: "
                                        + id);
                    }
                    return;
                default:
                    // Won't hit here.
                    throw new IllegalArgumentException(
                            "Unrecognized Encryption Algorithm ID: " + id);
            }
        }

        @Override
        protected void encodeToByteBuffer(boolean isLast, ByteBuffer byteBuffer) {
            encodeBasicTransformToByteBuffer(isLast, byteBuffer);

            if (mSpecifiedKeyLength != KEY_LEN_UNSPECIFIED) {
                new KeyLengthAttribute(mSpecifiedKeyLength).encodeToByteBuffer(byteBuffer);
            }
        }

        @Override
        protected int getTransformLength() {
            int len = BASIC_TRANSFORM_LEN;

            if (mSpecifiedKeyLength != KEY_LEN_UNSPECIFIED) {
                len += new KeyLengthAttribute(mSpecifiedKeyLength).getAttributeLength();
            }

            return len;
        }

        @Override
        public String getTransformTypeString() {
            return "Encryption Algorithm";
        }
    }

    /**
     * PrfTransform represents an pseudorandom function.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.3.2">RFC 7296, Internet Key
     *     Exchange Protocol Version 2 (IKEv2)</a>
     */
    public static final class PrfTransform extends Transform {
        /**
         * Contruct an instance of PrfTransform for building an outbound packet.
         *
         * @param id the IKE standard Transform ID.
         */
        public PrfTransform(@PseudorandomFunction int id) {
            super(Transform.TRANSFORM_TYPE_PRF, id);
        }

        /**
         * Contruct an instance of PrfTransform for decoding an inbound packet.
         *
         * @param id the IKE standard Transform ID.
         * @param attributeList the decoded list of Attribute.
         * @throws InvalidSyntaxException for syntax error.
         */
        protected PrfTransform(int id, List<Attribute> attributeList)
                throws InvalidSyntaxException {
            super(Transform.TRANSFORM_TYPE_PRF, id, attributeList);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, id);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PrfTransform)) return false;

            PrfTransform other = (PrfTransform) o;
            return (type == other.type && id == other.id);
        }

        @Override
        protected boolean isSupportedTransformId(int id) {
            return SaProposal.isSupportedPseudorandomFunction(id);
        }

        @Override
        protected boolean hasUnrecognizedAttribute(List<Attribute> attributeList) {
            return !attributeList.isEmpty();
        }

        @Override
        protected void encodeToByteBuffer(boolean isLast, ByteBuffer byteBuffer) {
            encodeBasicTransformToByteBuffer(isLast, byteBuffer);
        }

        @Override
        protected int getTransformLength() {
            return BASIC_TRANSFORM_LEN;
        }

        @Override
        public String getTransformTypeString() {
            return "Pseudorandom Function";
        }
    }

    /**
     * IntegrityTransform represents an integrity algorithm.
     *
     * <p>Proposing integrity algorithm for ESP SA is optional. Omitting the IntegrityTransform is
     * equivalent to including it with a value of NONE. When multiple integrity algorithms are
     * provided, choosing any of them are acceptable.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.3.2">RFC 7296, Internet Key
     *     Exchange Protocol Version 2 (IKEv2)</a>
     */
    public static final class IntegrityTransform extends Transform {
        /**
         * Contruct an instance of IntegrityTransform for building an outbound packet.
         *
         * @param id the IKE standard Transform ID.
         */
        public IntegrityTransform(@IntegrityAlgorithm int id) {
            super(Transform.TRANSFORM_TYPE_INTEG, id);
        }

        /**
         * Contruct an instance of IntegrityTransform for decoding an inbound packet.
         *
         * @param id the IKE standard Transform ID.
         * @param attributeList the decoded list of Attribute.
         * @throws InvalidSyntaxException for syntax error.
         */
        protected IntegrityTransform(int id, List<Attribute> attributeList)
                throws InvalidSyntaxException {
            super(Transform.TRANSFORM_TYPE_INTEG, id, attributeList);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, id);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof IntegrityTransform)) return false;

            IntegrityTransform other = (IntegrityTransform) o;
            return (type == other.type && id == other.id);
        }

        @Override
        protected boolean isSupportedTransformId(int id) {
            return SaProposal.isSupportedIntegrityAlgorithm(id);
        }

        @Override
        protected boolean hasUnrecognizedAttribute(List<Attribute> attributeList) {
            return !attributeList.isEmpty();
        }

        @Override
        protected void encodeToByteBuffer(boolean isLast, ByteBuffer byteBuffer) {
            encodeBasicTransformToByteBuffer(isLast, byteBuffer);
        }

        @Override
        protected int getTransformLength() {
            return BASIC_TRANSFORM_LEN;
        }

        @Override
        public String getTransformTypeString() {
            return "Integrity Algorithm";
        }
    }

    /**
     * DhGroupTransform represents a Diffie-Hellman Group
     *
     * <p>Proposing DH group for non-first Child SA is optional. Omitting the DhGroupTransform is
     * equivalent to including it with a value of NONE. When multiple DH groups are provided,
     * choosing any of them are acceptable.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.3.2">RFC 7296, Internet Key
     *     Exchange Protocol Version 2 (IKEv2)</a>
     */
    public static final class DhGroupTransform extends Transform {
        /**
         * Contruct an instance of DhGroupTransform for building an outbound packet.
         *
         * @param id the IKE standard Transform ID.
         */
        public DhGroupTransform(@DhGroup int id) {
            super(Transform.TRANSFORM_TYPE_DH, id);
        }

        /**
         * Contruct an instance of DhGroupTransform for decoding an inbound packet.
         *
         * @param id the IKE standard Transform ID.
         * @param attributeList the decoded list of Attribute.
         * @throws InvalidSyntaxException for syntax error.
         */
        protected DhGroupTransform(int id, List<Attribute> attributeList)
                throws InvalidSyntaxException {
            super(Transform.TRANSFORM_TYPE_DH, id, attributeList);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, id);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof DhGroupTransform)) return false;

            DhGroupTransform other = (DhGroupTransform) o;
            return (type == other.type && id == other.id);
        }

        @Override
        protected boolean isSupportedTransformId(int id) {
            return SaProposal.isSupportedDhGroup(id);
        }

        @Override
        protected boolean hasUnrecognizedAttribute(List<Attribute> attributeList) {
            return !attributeList.isEmpty();
        }

        @Override
        protected void encodeToByteBuffer(boolean isLast, ByteBuffer byteBuffer) {
            encodeBasicTransformToByteBuffer(isLast, byteBuffer);
        }

        @Override
        protected int getTransformLength() {
            return BASIC_TRANSFORM_LEN;
        }

        @Override
        public String getTransformTypeString() {
            return "Diffie-Hellman Group";
        }
    }

    /**
     * EsnTransform represents ESN policy that indicates if IPsec SA uses tranditional 32-bit
     * sequence numbers or extended(64-bit) sequence numbers.
     *
     * <p>Currently IKE library only supports negotiating IPsec SA that do not use extended sequence
     * numbers. The Transform ID of EsnTransform in outbound packets is not user configurable.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.3.2">RFC 7296, Internet Key
     *     Exchange Protocol Version 2 (IKEv2)</a>
     */
    public static final class EsnTransform extends Transform {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({ESN_POLICY_NO_EXTENDED, ESN_POLICY_EXTENDED})
        public @interface EsnPolicy {}

        public static final int ESN_POLICY_NO_EXTENDED = 0;
        public static final int ESN_POLICY_EXTENDED = 1;

        /**
         * Construct an instance of EsnTransform indicates using no-extended sequence numbers for
         * building an outbound packet.
         */
        public EsnTransform() {
            super(Transform.TRANSFORM_TYPE_ESN, ESN_POLICY_NO_EXTENDED);
        }

        /**
         * Contruct an instance of EsnTransform for decoding an inbound packet.
         *
         * @param id the IKE standard Transform ID.
         * @param attributeList the decoded list of Attribute.
         * @throws InvalidSyntaxException for syntax error.
         */
        protected EsnTransform(int id, List<Attribute> attributeList)
                throws InvalidSyntaxException {
            super(Transform.TRANSFORM_TYPE_ESN, id, attributeList);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, id);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof EsnTransform)) return false;

            EsnTransform other = (EsnTransform) o;
            return (type == other.type && id == other.id);
        }

        @Override
        protected boolean isSupportedTransformId(int id) {
            return (id == ESN_POLICY_NO_EXTENDED || id == ESN_POLICY_EXTENDED);
        }

        @Override
        protected boolean hasUnrecognizedAttribute(List<Attribute> attributeList) {
            return !attributeList.isEmpty();
        }

        @Override
        protected void encodeToByteBuffer(boolean isLast, ByteBuffer byteBuffer) {
            encodeBasicTransformToByteBuffer(isLast, byteBuffer);
        }

        @Override
        protected int getTransformLength() {
            return BASIC_TRANSFORM_LEN;
        }

        @Override
        public String getTransformTypeString() {
            return "Extended Sequence Numbers";
        }
    }

    /**
     * UnrecognizedTransform represents a Transform with unrecognized Transform Type.
     *
     * <p>Proposals containing an UnrecognizedTransform should be ignored.
     */
    protected static final class UnrecognizedTransform extends Transform {
        protected UnrecognizedTransform(int type, int id, List<Attribute> attributeList) {
            super(type, id, attributeList);
        }

        @Override
        protected boolean isSupportedTransformId(int id) {
            return false;
        }

        @Override
        protected boolean hasUnrecognizedAttribute(List<Attribute> attributeList) {
            return !attributeList.isEmpty();
        }

        @Override
        protected void encodeToByteBuffer(boolean isLast, ByteBuffer byteBuffer) {
            throw new UnsupportedOperationException(
                    "It is not supported to encode a Transform with" + getTransformTypeString());
        }

        @Override
        protected int getTransformLength() {
            throw new UnsupportedOperationException(
                    "It is not supported to get length of a Transform with "
                            + getTransformTypeString());
        }

        /**
         * Return Tranform Type of Unrecognized Transform as a String.
         *
         * @return Tranform Type of Unrecognized Transform as a String.
         */
        @Override
        public String getTransformTypeString() {
            return "Unrecognized Transform Type.";
        }
    }

    /**
     * Attribute is an abtract base class for completing the specification of some {@link
     * Transform}.
     *
     * <p>Attribute is either in Type/Value format or Type/Length/Value format. For TV format,
     * Attribute length is always 4 bytes containing value for 2 bytes. While for TLV format,
     * Attribute length is determined by length field.
     *
     * <p>Currently only Key Length type is supported
     *
     * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.3.5">RFC 7296, Internet Key
     *     Exchange Protocol Version 2 (IKEv2)</a>
     */
    public abstract static class Attribute {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({ATTRIBUTE_TYPE_KEY_LENGTH})
        public @interface AttributeType {}

        // Support only one Attribute type: Key Length. Should use Type/Value format.
        public static final int ATTRIBUTE_TYPE_KEY_LENGTH = 14;

        // Mask to extract the left most AF bit to indicate Attribute Format.
        private static final int ATTRIBUTE_FORMAT_MASK = 0x8000;
        // Mask to extract 15 bits after the AF bit to indicate Attribute Type.
        private static final int ATTRIBUTE_TYPE_MASK = 0x7fff;

        // Package private mask to indicate that Type-Value (TV) Attribute Format is used.
        static final int ATTRIBUTE_FORMAT_TV = ATTRIBUTE_FORMAT_MASK;

        // Package private
        static final int TV_ATTRIBUTE_VALUE_LEN = 2;
        static final int TV_ATTRIBUTE_TOTAL_LEN = 4;
        static final int TVL_ATTRIBUTE_HEADER_LEN = TV_ATTRIBUTE_TOTAL_LEN;

        // Only Key Length type belongs to AttributeType
        public final int type;

        /** Construct an instance of an Attribute when decoding message. */
        protected Attribute(int type) {
            this.type = type;
        }

        @VisibleForTesting
        static Pair<Attribute, Integer> readFrom(ByteBuffer inputBuffer) throws IkeException {
            short formatAndType = inputBuffer.getShort();
            int format = formatAndType & ATTRIBUTE_FORMAT_MASK;
            int type = formatAndType & ATTRIBUTE_TYPE_MASK;

            int length = 0;
            byte[] value = new byte[0];
            if (format == ATTRIBUTE_FORMAT_TV) {
                // Type/Value format
                length = TV_ATTRIBUTE_TOTAL_LEN;
                value = new byte[TV_ATTRIBUTE_VALUE_LEN];
            } else {
                // Type/Length/Value format
                if (type == ATTRIBUTE_TYPE_KEY_LENGTH) {
                    throw new InvalidSyntaxException("Wrong format in Transform Attribute");
                }

                length = Short.toUnsignedInt(inputBuffer.getShort());
                int valueLen = length - TVL_ATTRIBUTE_HEADER_LEN;
                // IkeMessage will catch exception if valueLen is negative.
                value = new byte[valueLen];
            }

            inputBuffer.get(value);

            switch (type) {
                case ATTRIBUTE_TYPE_KEY_LENGTH:
                    return new Pair(new KeyLengthAttribute(value), length);
                default:
                    return new Pair(new UnrecognizedAttribute(type, value), length);
            }
        }

        // Encode Attribute to a ByteBuffer.
        protected abstract void encodeToByteBuffer(ByteBuffer byteBuffer);

        // Get entire Attribute length.
        protected abstract int getAttributeLength();
    }

    /** KeyLengthAttribute represents a Key Length type Attribute */
    public static final class KeyLengthAttribute extends Attribute {
        public final int keyLength;

        protected KeyLengthAttribute(byte[] value) {
            this(Short.toUnsignedInt(ByteBuffer.wrap(value).getShort()));
        }

        protected KeyLengthAttribute(int keyLength) {
            super(ATTRIBUTE_TYPE_KEY_LENGTH);
            this.keyLength = keyLength;
        }

        @Override
        protected void encodeToByteBuffer(ByteBuffer byteBuffer) {
            byteBuffer
                    .putShort((short) (ATTRIBUTE_FORMAT_TV | ATTRIBUTE_TYPE_KEY_LENGTH))
                    .putShort((short) keyLength);
        }

        @Override
        protected int getAttributeLength() {
            return TV_ATTRIBUTE_TOTAL_LEN;
        }
    }

    /**
     * UnrecognizedAttribute represents a Attribute with unrecoginzed Attribute Type.
     *
     * <p>Transforms containing UnrecognizedAttribute should be ignored.
     */
    protected static final class UnrecognizedAttribute extends Attribute {
        protected UnrecognizedAttribute(int type, byte[] value) {
            super(type);
        }

        @Override
        protected void encodeToByteBuffer(ByteBuffer byteBuffer) {
            throw new UnsupportedOperationException(
                    "It is not supported to encode an unrecognized Attribute.");
        }

        @Override
        protected int getAttributeLength() {
            throw new UnsupportedOperationException(
                    "It is not supported to get length of an unrecognized Attribute.");
        }
    }

    /**
     * Encode SA payload to ByteBUffer.
     *
     * @param nextPayload type of payload that follows this payload.
     * @param byteBuffer destination ByteBuffer that stores encoded payload.
     */
    @Override
    protected void encodeToByteBuffer(@PayloadType int nextPayload, ByteBuffer byteBuffer) {
        encodePayloadHeaderToByteBuffer(nextPayload, getPayloadLength(), byteBuffer);

        for (int i = 0; i < proposalList.size(); i++) {
            // The last proposal has the isLast flag set to true.
            proposalList.get(i).encodeToByteBuffer(i == proposalList.size() - 1, byteBuffer);
        }
    }

    /**
     * Get entire payload length.
     *
     * @return entire payload length.
     */
    @Override
    protected int getPayloadLength() {
        int len = GENERIC_HEADER_LENGTH;

        for (Proposal p : proposalList) len += p.getProposalLength();

        return len;
    }

    /**
     * Return the payload type as a String.
     *
     * @return the payload type as a String.
     */
    @Override
    public String getTypeString() {
        return "SA Payload";
    }
}
