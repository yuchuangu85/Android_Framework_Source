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

package com.android.ike.ikev2;

import android.annotation.IntDef;
import android.util.ArraySet;

import com.android.ike.ikev2.message.IkePayload;
import com.android.ike.ikev2.message.IkeSaPayload.DhGroupTransform;
import com.android.ike.ikev2.message.IkeSaPayload.EncryptionTransform;
import com.android.ike.ikev2.message.IkeSaPayload.EsnTransform;
import com.android.ike.ikev2.message.IkeSaPayload.IntegrityTransform;
import com.android.ike.ikev2.message.IkeSaPayload.PrfTransform;
import com.android.ike.ikev2.message.IkeSaPayload.Transform;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * SaProposal represents a user configured set contains cryptograhic algorithms and key generating
 * materials for negotiating an IKE or Child SA.
 *
 * <p>User must provide at least a valid SaProposal when they are creating a new IKE SA or Child SA.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.3">RFC 7296, Internet Key Exchange
 *     Protocol Version 2 (IKEv2)</a>
 */
public final class SaProposal {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        ENCRYPTION_ALGORITHM_3DES,
        ENCRYPTION_ALGORITHM_AES_CBC,
        ENCRYPTION_ALGORITHM_AES_GCM_8,
        ENCRYPTION_ALGORITHM_AES_GCM_12,
        ENCRYPTION_ALGORITHM_AES_GCM_16
    })
    public @interface EncryptionAlgorithm {}

    public static final int ENCRYPTION_ALGORITHM_3DES = 3;
    public static final int ENCRYPTION_ALGORITHM_AES_CBC = 12;
    public static final int ENCRYPTION_ALGORITHM_AES_GCM_8 = 18;
    public static final int ENCRYPTION_ALGORITHM_AES_GCM_12 = 19;
    public static final int ENCRYPTION_ALGORITHM_AES_GCM_16 = 20;

    private static final Set<Integer> SUPPORTED_ENCRYPTION_ALGORITHM;

    static {
        SUPPORTED_ENCRYPTION_ALGORITHM = new ArraySet<>();
        SUPPORTED_ENCRYPTION_ALGORITHM.add(ENCRYPTION_ALGORITHM_3DES);
        SUPPORTED_ENCRYPTION_ALGORITHM.add(ENCRYPTION_ALGORITHM_AES_CBC);
        SUPPORTED_ENCRYPTION_ALGORITHM.add(ENCRYPTION_ALGORITHM_AES_GCM_8);
        SUPPORTED_ENCRYPTION_ALGORITHM.add(ENCRYPTION_ALGORITHM_AES_GCM_12);
        SUPPORTED_ENCRYPTION_ALGORITHM.add(ENCRYPTION_ALGORITHM_AES_GCM_16);
    }

    public static final int KEY_LEN_AES_128 = 128;
    public static final int KEY_LEN_AES_192 = 192;
    public static final int KEY_LEN_AES_256 = 256;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({PSEUDORANDOM_FUNCTION_HMAC_SHA1, PSEUDORANDOM_FUNCTION_AES128_XCBC})
    public @interface PseudorandomFunction {}

    public static final int PSEUDORANDOM_FUNCTION_HMAC_SHA1 = 2;
    public static final int PSEUDORANDOM_FUNCTION_AES128_XCBC = 4;

    private static final Set<Integer> SUPPORTED_PSEUDORANDOM_FUNCTION;

    static {
        SUPPORTED_PSEUDORANDOM_FUNCTION = new ArraySet<>();
        SUPPORTED_PSEUDORANDOM_FUNCTION.add(PSEUDORANDOM_FUNCTION_HMAC_SHA1);
        SUPPORTED_PSEUDORANDOM_FUNCTION.add(PSEUDORANDOM_FUNCTION_AES128_XCBC);
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        INTEGRITY_ALGORITHM_NONE,
        INTEGRITY_ALGORITHM_HMAC_SHA1_96,
        INTEGRITY_ALGORITHM_AES_XCBC_96,
        INTEGRITY_ALGORITHM_HMAC_SHA2_256_128,
        INTEGRITY_ALGORITHM_HMAC_SHA2_384_192,
        INTEGRITY_ALGORITHM_HMAC_SHA2_512_256
    })
    public @interface IntegrityAlgorithm {}

    public static final int INTEGRITY_ALGORITHM_NONE = 0;
    public static final int INTEGRITY_ALGORITHM_HMAC_SHA1_96 = 2;
    public static final int INTEGRITY_ALGORITHM_AES_XCBC_96 = 5;
    public static final int INTEGRITY_ALGORITHM_HMAC_SHA2_256_128 = 12;
    public static final int INTEGRITY_ALGORITHM_HMAC_SHA2_384_192 = 13;
    public static final int INTEGRITY_ALGORITHM_HMAC_SHA2_512_256 = 14;

    private static final Set<Integer> SUPPORTED_INTEGRITY_ALGORITHM;

    static {
        SUPPORTED_INTEGRITY_ALGORITHM = new ArraySet<>();
        SUPPORTED_INTEGRITY_ALGORITHM.add(INTEGRITY_ALGORITHM_NONE);
        SUPPORTED_INTEGRITY_ALGORITHM.add(INTEGRITY_ALGORITHM_HMAC_SHA1_96);
        SUPPORTED_INTEGRITY_ALGORITHM.add(INTEGRITY_ALGORITHM_AES_XCBC_96);
        SUPPORTED_INTEGRITY_ALGORITHM.add(INTEGRITY_ALGORITHM_HMAC_SHA2_256_128);
        SUPPORTED_INTEGRITY_ALGORITHM.add(INTEGRITY_ALGORITHM_HMAC_SHA2_384_192);
        SUPPORTED_INTEGRITY_ALGORITHM.add(INTEGRITY_ALGORITHM_HMAC_SHA2_512_256);
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({DH_GROUP_NONE, DH_GROUP_1024_BIT_MODP, DH_GROUP_2048_BIT_MODP})
    public @interface DhGroup {}

    public static final int DH_GROUP_NONE = 0;
    public static final int DH_GROUP_1024_BIT_MODP = 2;
    public static final int DH_GROUP_2048_BIT_MODP = 14;

    private static final Set<Integer> SUPPORTED_DH_GROUP;

    static {
        SUPPORTED_DH_GROUP = new ArraySet<>();
        SUPPORTED_DH_GROUP.add(DH_GROUP_NONE);
        SUPPORTED_DH_GROUP.add(DH_GROUP_1024_BIT_MODP);
        SUPPORTED_DH_GROUP.add(DH_GROUP_2048_BIT_MODP);
    }

    /** Package private */
    @IkePayload.ProtocolId private final int mProtocolId;
    /** Package private */
    private final EncryptionTransform[] mEncryptionAlgorithms;
    /** Package private */
    private final PrfTransform[] mPseudorandomFunctions;
    /** Package private */
    private final IntegrityTransform[] mIntegrityAlgorithms;
    /** Package private */
    private final DhGroupTransform[] mDhGroups;
    /** Package private */
    private final EsnTransform[] mEsns;

    private SaProposal(
            @IkePayload.ProtocolId int protocol,
            EncryptionTransform[] encryptionAlgos,
            PrfTransform[] prfs,
            IntegrityTransform[] integrityAlgos,
            DhGroupTransform[] dhGroups) {
        mProtocolId = protocol;
        mEncryptionAlgorithms = encryptionAlgos;
        mPseudorandomFunctions = prfs;
        mIntegrityAlgorithms = integrityAlgos;
        mDhGroups = dhGroups;

        if (protocol == IkePayload.PROTOCOL_ID_IKE) {
            // Do not negotiate ESN for IKE SA proposal
            mEsns = new EsnTransform[0];
        } else {
            // Do not support negotiating Child SAs using extended sequence numbers.
            mEsns = new EsnTransform[] {new EsnTransform()};
        }
    }

    /**
     * Construct SaProposal from a decoded inbound IKE packet, only called by IkeSaPayload.
     *
     * @param protocol IP protocol ID
     * @param encryptionAlgos encryption algorithms decoded from inbound IKE packet.
     * @param prfs pseudorandom functions decoded from inbound IKE packet.
     * @param integrityAlgos integrity algorithms decoded from inbound IKE packet.
     * @param dhGroups Dh groups decoded from inbound IKE packet.
     * @param esns ESN policies decoded from IKE packet.
     */
    public SaProposal(
            @IkePayload.ProtocolId int protocol,
            EncryptionTransform[] encryptionAlgos,
            PrfTransform[] prfs,
            IntegrityTransform[] integrityAlgos,
            DhGroupTransform[] dhGroups,
            EsnTransform[] esns) {
        mProtocolId = protocol;
        mEncryptionAlgorithms = encryptionAlgos;
        mPseudorandomFunctions = prfs;
        mIntegrityAlgorithms = integrityAlgos;
        mDhGroups = dhGroups;
        mEsns = esns;
    }

    /**
     * Check if the current SaProposal from the SA responder is consistent with the selected
     * reqProposal from the SA initiator.
     *
     * @param reqProposal selected SaProposal from SA initiator
     * @return if current SaProposal from SA responder is consistent with the selected reqProposal
     *     from SA initiator.
     */
    public boolean isNegotiatedFrom(SaProposal reqProposal) {
        return isTransformSelectedFrom(mEncryptionAlgorithms, reqProposal.mEncryptionAlgorithms)
                && isTransformSelectedFrom(
                        mPseudorandomFunctions, reqProposal.mPseudorandomFunctions)
                && isTransformSelectedFrom(mIntegrityAlgorithms, reqProposal.mIntegrityAlgorithms)
                && isTransformSelectedFrom(mDhGroups, reqProposal.mDhGroups)
                && isTransformSelectedFrom(mEsns, reqProposal.mEsns);
    }

    /** Package private */
    static boolean isTransformSelectedFrom(Transform[] selected, Transform[] selectFrom) {
        // If the selected proposal has multiple transforms with the same type, the responder MUST
        // choose a single one.
        if ((selected.length > 1) || (selected.length == 0) != (selectFrom.length == 0)) {
            return false;
        }

        if (selected.length == 0) return true;

        return Arrays.asList(selectFrom).contains(selected[0]);
    }

    /*Package private*/
    @IkePayload.ProtocolId
    int getProtocolId() {
        return mProtocolId;
    }

    /*Package private*/
    EncryptionTransform[] getEncryptionTransforms() {
        return mEncryptionAlgorithms;
    }

    /*Package private*/
    PrfTransform[] getPrfTransforms() {
        return mPseudorandomFunctions;
    }

    /*Package private*/
    IntegrityTransform[] getIntegrityTransforms() {
        return mIntegrityAlgorithms;
    }

    /*Package private*/
    DhGroupTransform[] getDhGroupTransforms() {
        return mDhGroups;
    }

    /*Package private*/
    EsnTransform[] getEsnTransforms() {
        return mEsns;
    }

    /**
     * Return all SA Transforms in this SaProposal to be encoded for building an outbound IKE
     * message.
     *
     * <p>This method can be called by only IKE library.
     *
     * @return Array of Transforms to be encoded.
     */
    public Transform[] getAllTransforms() {
        int encodedNumTransforms =
                mEncryptionAlgorithms.length
                        + mPseudorandomFunctions.length
                        + mIntegrityAlgorithms.length
                        + mDhGroups.length
                        + mEsns.length;

        List<Transform> transformList = new ArrayList<Transform>(encodedNumTransforms);
        transformList.addAll(Arrays.asList(mEncryptionAlgorithms));
        transformList.addAll(Arrays.asList(mPseudorandomFunctions));
        transformList.addAll(Arrays.asList(mIntegrityAlgorithms));
        transformList.addAll(Arrays.asList(mDhGroups));
        transformList.addAll(Arrays.asList(mEsns));

        return transformList.toArray(new Transform[encodedNumTransforms]);
    }

    /**
     * This class can be used to incrementally construct a SaProposal. SaProposal instances are
     * immutable once built.
     *
     * <p>TODO: Support users to add algorithms from most preferred to least preferred.
     */
    public static final class Builder {
        private static final String ERROR_TAG = "Invalid SA Proposal: ";

        /** Indicate if Builder is for building IKE SA proposal or Child SA proposal. */
        private final boolean mIsIkeProposal;
        /**
         * Indicate if Builder is for building first Child SA proposal or addtional Child SA
         * proposal. Only valid if mIsIkeProposal is false.
         */
        private final boolean mIsFirstChild;

        // Use set to avoid adding repeated algorithms.
        private final Set<EncryptionTransform> mProposedEncryptAlgos = new ArraySet<>();
        private final Set<PrfTransform> mProposedPrfs = new ArraySet<>();
        private final Set<IntegrityTransform> mProposedIntegrityAlgos = new ArraySet<>();
        private final Set<DhGroupTransform> mProposedDhGroups = new ArraySet<>();

        private boolean mHasAead = false;

        private Builder(boolean isIke, boolean isFirstChild) {
            mIsIkeProposal = isIke;
            mIsFirstChild = isFirstChild;
        }

        private static boolean isAead(@EncryptionAlgorithm int algorithm) {
            switch (algorithm) {
                case ENCRYPTION_ALGORITHM_3DES:
                    // Fall through
                case ENCRYPTION_ALGORITHM_AES_CBC:
                    return false;
                case ENCRYPTION_ALGORITHM_AES_GCM_8:
                    // Fall through
                case ENCRYPTION_ALGORITHM_AES_GCM_12:
                    // Fall through
                case ENCRYPTION_ALGORITHM_AES_GCM_16:
                    return true;
                default:
                    // Won't hit here.
                    throw new IllegalArgumentException("Unsupported Encryption Algorithm.");
            }
        }

        private EncryptionTransform[] buildEncryptAlgosOrThrow() {
            if (mProposedEncryptAlgos.isEmpty()) {
                throw new IllegalArgumentException(
                        ERROR_TAG + "Encryption algorithm must be proposed.");
            }

            return mProposedEncryptAlgos.toArray(
                    new EncryptionTransform[mProposedEncryptAlgos.size()]);
        }

        private PrfTransform[] buildPrfsOrThrow() {
            if (mIsIkeProposal == mProposedPrfs.isEmpty()) {
                throw new IllegalArgumentException(
                        ERROR_TAG + "Invalid PRF configuration for this SA Proposal.");
            }

            return mProposedPrfs.toArray(new PrfTransform[mProposedPrfs.size()]);
        }

        private IntegrityTransform[] buildIntegAlgosForIkeOrThrow() {
            // When building IKE SA Proposal with normal-mode ciphers, mProposedIntegrityAlgos must
            // not be empty and must not have INTEGRITY_ALGORITHM_NONE. When building IKE SA
            // Proposal with combined-mode ciphers, mProposedIntegrityAlgos must be either empty or
            // only have INTEGRITY_ALGORITHM_NONE.
            if (mProposedIntegrityAlgos.isEmpty() && !mHasAead) {
                throw new IllegalArgumentException(
                        ERROR_TAG
                                + "Integrity algorithm "
                                + "must be proposed with normal ciphers in IKE proposal.");
            }

            for (IntegrityTransform transform : mProposedIntegrityAlgos) {
                if ((transform.id == INTEGRITY_ALGORITHM_NONE) != mHasAead) {
                    throw new IllegalArgumentException(
                            ERROR_TAG
                                    + "Invalid integrity algorithm configuration"
                                    + " for this SA Proposal");
                }
            }

            return mProposedIntegrityAlgos.toArray(
                    new IntegrityTransform[mProposedIntegrityAlgos.size()]);
        }

        private IntegrityTransform[] buildIntegAlgosForChildOrThrow() {
            // When building Child SA Proposal with normal-mode ciphers, there is no contraint on
            // integrity algorithm. When building Child SA Proposal with combined-mode ciphers,
            // mProposedIntegrityAlgos must be either empty or only have INTEGRITY_ALGORITHM_NONE.
            for (IntegrityTransform transform : mProposedIntegrityAlgos) {
                if (transform.id != INTEGRITY_ALGORITHM_NONE && mHasAead) {
                    throw new IllegalArgumentException(
                            ERROR_TAG
                                    + "Only INTEGRITY_ALGORITHM_NONE can be"
                                    + " proposed with combined-mode ciphers in any proposal.");
                }
            }

            return mProposedIntegrityAlgos.toArray(
                    new IntegrityTransform[mProposedIntegrityAlgos.size()]);
        }

        private DhGroupTransform[] buildDhGroupsForIkeOrThrow() {
            if (mProposedDhGroups.isEmpty()) {
                throw new IllegalArgumentException(
                        ERROR_TAG + "DH group must be proposed in IKE SA proposal.");
            }

            for (DhGroupTransform transform : mProposedDhGroups) {
                if (transform.id == DH_GROUP_NONE) {
                    throw new IllegalArgumentException(
                            ERROR_TAG
                                    + "None-value DH group must not"
                                    + " be proposed in IKE SA proposal");
                }
            }

            return mProposedDhGroups.toArray(new DhGroupTransform[mProposedDhGroups.size()]);
        }

        private DhGroupTransform[] buildDhGroupsForChildOrThrow() {
            for (DhGroupTransform transform : mProposedDhGroups) {
                if (transform.id != DH_GROUP_NONE && mIsFirstChild) {
                    throw new IllegalArgumentException(
                            ERROR_TAG
                                    + "Only DH_GROUP_NONE can be"
                                    + " proposed in first Child SA proposal.");
                }
            }
            return mProposedDhGroups.toArray(new DhGroupTransform[mProposedDhGroups.size()]);
        }

        /** Returns a new Builder for a IKE SA Proposal. */
        public static Builder newIkeSaProposalBuilder() {
            return new Builder(true, false);
        }

        /**
         * Returns a new Builder for a Child SA Proposal.
         *
         * @param isFirstChildSaProposal indicates if this SA proposal for first Child SA.
         * @return Builder for a Child SA Proposal.
         */
        public static Builder newChildSaProposalBuilder(boolean isFirstChildSaProposal) {
            return new Builder(false, isFirstChildSaProposal);
        }

        /**
         * Adds an encryption algorithm to SA proposal being built.
         *
         * @param algorithm encryption algorithm to add to SaProposal.
         * @return Builder of SaProposal.
         */
        public Builder addEncryptionAlgorithm(@EncryptionAlgorithm int algorithm) {
            // Construct EncryptionTransform and validate proposed algorithm during
            // construction.
            EncryptionTransform encryptionTransform = new EncryptionTransform(algorithm);

            validateOnlyOneModeEncryptAlgoProposedOrThrow(algorithm);

            mProposedEncryptAlgos.add(encryptionTransform);
            return this;
        }

        /**
         * Adds an encryption algorithm with specific key length to SA proposal being built.
         *
         * @param algorithm encryption algorithm to add to SaProposal.
         * @param keyLength key length of algorithm.
         * @return Builder of SaProposal.
         * @throws IllegalArgumentException if AEAD and non-combined mode algorithms are mixed.
         */
        public Builder addEncryptionAlgorithm(@EncryptionAlgorithm int algorithm, int keyLength) {
            // Construct EncryptionTransform and validate proposed algorithm during
            // construction.
            EncryptionTransform encryptionTransform = new EncryptionTransform(algorithm, keyLength);

            validateOnlyOneModeEncryptAlgoProposedOrThrow(algorithm);

            mProposedEncryptAlgos.add(encryptionTransform);
            return this;
        }

        private void validateOnlyOneModeEncryptAlgoProposedOrThrow(
                @EncryptionAlgorithm int algorithm) {
            boolean isCurrentAead = isAead(algorithm);

            if (!mProposedEncryptAlgos.isEmpty() && (mHasAead ^ isCurrentAead)) {
                throw new IllegalArgumentException(
                        ERROR_TAG
                                + "Proposal cannot has both normal ciphers "
                                + "and combined-mode ciphers.");
            }

            if (isCurrentAead) mHasAead = true;
        }

        /**
         * Adds a pseudorandom function to SA proposal being built.
         *
         * @param algorithm pseudorandom function to add to SaProposal.
         * @return Builder of SaProposal.
         */
        public Builder addPseudorandomFunction(@PseudorandomFunction int algorithm) {
            // Construct PrfTransform and validate proposed algorithm during
            // construction.
            mProposedPrfs.add(new PrfTransform(algorithm));
            return this;
        }

        /**
         * Adds an integrity algorithm to SA proposal being built.
         *
         * @param algorithm integrity algorithm to add to SaProposal.
         * @return Builder of SaProposal.
         */
        public Builder addIntegrityAlgorithm(@IntegrityAlgorithm int algorithm) {
            // Construct IntegrityTransform and validate proposed algorithm during
            // construction.
            mProposedIntegrityAlgos.add(new IntegrityTransform(algorithm));
            return this;
        }

        /**
         * Adds a Diffie-Hellman Group to SA proposal being built.
         *
         * @param dhGroup to add to SaProposal.
         * @return Builder of SaProposal.
         */
        public Builder addDhGroup(@DhGroup int dhGroup) {
            // Construct DhGroupTransform and validate proposed dhGroup during
            // construction.
            mProposedDhGroups.add(new DhGroupTransform(dhGroup));
            return this;
        }

        /**
         * Validates, builds and returns the SaProposal
         *
         * @return SaProposal the validated SaProposal.
         * @throws IllegalArgumentException if SaProposal is invalid.
         */
        public SaProposal build() {
            EncryptionTransform[] encryptionTransforms = buildEncryptAlgosOrThrow();
            PrfTransform[] prfTransforms = buildPrfsOrThrow();
            IntegrityTransform[] integrityTransforms =
                    mIsIkeProposal
                            ? buildIntegAlgosForIkeOrThrow()
                            : buildIntegAlgosForChildOrThrow();

            DhGroupTransform[] dhGroupTransforms =
                    mIsIkeProposal ? buildDhGroupsForIkeOrThrow() : buildDhGroupsForChildOrThrow();
            // IKE library only supports negotiating ESP Child SA.
            int protocol = mIsIkeProposal ? IkePayload.PROTOCOL_ID_IKE : IkePayload.PROTOCOL_ID_ESP;

            return new SaProposal(
                    protocol,
                    encryptionTransforms,
                    prfTransforms,
                    integrityTransforms,
                    dhGroupTransforms);
        }
    }

    /**
     * Check if the provided algorithm is a supported encryption algorithm.
     *
     * @param algorithm IKE standard encryption algorithm id.
     * @return true if the provided algorithm is a supported encryption algorithm.
     */
    public static boolean isSupportedEncryptionAlgorithm(@EncryptionAlgorithm int algorithm) {
        return SUPPORTED_ENCRYPTION_ALGORITHM.contains(algorithm);
    }

    /**
     * Check if the provided algorithm is a supported pseudorandom function.
     *
     * @param algorithm IKE standard pseudorandom function id.
     * @return true if the provided algorithm is a supported pseudorandom function.
     */
    public static boolean isSupportedPseudorandomFunction(@PseudorandomFunction int algorithm) {
        return SUPPORTED_PSEUDORANDOM_FUNCTION.contains(algorithm);
    }

    /**
     * Check if the provided algorithm is a supported integrity algorithm.
     *
     * @param algorithm IKE standard integrity algorithm id.
     * @return true if the provided algorithm is a supported integrity algorithm.
     */
    public static boolean isSupportedIntegrityAlgorithm(@IntegrityAlgorithm int algorithm) {
        return SUPPORTED_INTEGRITY_ALGORITHM.contains(algorithm);
    }

    /**
     * Check if the provided group number is for a supported Diffie-Hellman Group.
     *
     * @param dhGroup IKE standard DH Group id.
     * @return true if the provided number is for a supported Diffie-Hellman Group.
     */
    public static boolean isSupportedDhGroup(@DhGroup int dhGroup) {
        return SUPPORTED_DH_GROUP.contains(dhGroup);
    }
}
