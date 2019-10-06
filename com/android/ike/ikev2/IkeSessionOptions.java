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

import android.net.IpSecManager.UdpEncapsulationSocket;

import com.android.ike.ikev2.message.IkePayload;

import java.net.InetAddress;
import java.util.LinkedList;
import java.util.List;

/**
 * IkeSessionOptions contains all user provided configurations for negotiating an IKE SA.
 *
 * <p>TODO: Make this doc more user-friendly.
 */
public final class IkeSessionOptions {
    private final InetAddress mServerAddress;
    private final UdpEncapsulationSocket mUdpEncapSocket;
    private final SaProposal[] mSaProposals;
    private final boolean mIsIkeFragmentationSupported;

    private IkeSessionOptions(
            InetAddress serverAddress,
            UdpEncapsulationSocket udpEncapsulationSocket,
            SaProposal[] proposals,
            boolean isIkeFragmentationSupported) {
        mServerAddress = serverAddress;
        mUdpEncapSocket = udpEncapsulationSocket;
        mSaProposals = proposals;
        mIsIkeFragmentationSupported = isIkeFragmentationSupported;
    }

    /** Package private */
    InetAddress getServerAddress() {
        return mServerAddress;
    }
    /** Package private */
    UdpEncapsulationSocket getUdpEncapsulationSocket() {
        return mUdpEncapSocket;
    }
    /** Package private */
    SaProposal[] getSaProposals() {
        return mSaProposals;
    }
    /** Package private */
    boolean isIkeFragmentationSupported() {
        return mIsIkeFragmentationSupported;
    }

    /** This class can be used to incrementally construct a IkeSessionOptions. */
    public static final class Builder {
        private final InetAddress mServerAddress;
        private final UdpEncapsulationSocket mUdpEncapSocket;
        private final List<SaProposal> mSaProposalList = new LinkedList<>();

        private boolean mIsIkeFragmentationSupported = false;

        /**
         * Returns a new Builder for an IkeSessionOptions.
         *
         * @param serverAddress IP address of remote IKE server.
         * @param udpEncapsulationSocket {@link IpSecManager.UdpEncapsulationSocket} for sending and
         *     receiving IKE message.
         * @return Builder for an IkeSessionOptions.
         */
        public Builder(InetAddress serverAddress, UdpEncapsulationSocket udpEncapsulationSocket) {
            mServerAddress = serverAddress;
            mUdpEncapSocket = udpEncapsulationSocket;
        }

        /**
         * Adds an IKE SA proposal to IkeSessionOptions being built.
         *
         * @param proposal IKE SA proposal.
         * @return Builder for an IkeSessionOptions.
         * @throws IllegalArgumentException if input proposal is not IKE SA proposal.
         */
        public Builder addSaProposal(SaProposal proposal) {
            if (proposal.getProtocolId() != IkePayload.PROTOCOL_ID_IKE) {
                throw new IllegalArgumentException(
                        "Expected IKE SA Proposal but received Child SA proposal");
            }
            mSaProposalList.add(proposal);
            return this;
        }

        /**
         * Validates, builds and returns the IkeSessionOptions
         *
         * @return IkeSessionOptions the validated IkeSessionOptions
         * @throws IllegalStateException if no IKE SA proposal is provided
         */
        public IkeSessionOptions build() {
            if (mSaProposalList.isEmpty()) {
                throw new IllegalArgumentException("IKE SA proposal not found");
            }
            return new IkeSessionOptions(
                    mServerAddress,
                    mUdpEncapSocket,
                    mSaProposalList.toArray(new SaProposal[mSaProposalList.size()]),
                    mIsIkeFragmentationSupported);
        }

        // TODO: add methods for supporting IKE fragmentation.
    }
}
