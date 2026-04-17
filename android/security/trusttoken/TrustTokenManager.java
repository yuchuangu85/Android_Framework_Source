/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.security.trusttoken;

import android.annotation.CheckResult;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages trust tokens, which provide a secure and private way for devices to establish mutual
 * trust offline.
 *
 * <p>A trust token is a cryptographically-verifiable claim (a "policy") about a device or an
 * identity. These claims are verified and signed by a "validation service" trusted by the device
 * and packaged into a CBOR Web Token (CWT). The system is designed to be privacy-preserving: trust
 * tokens prove properties without revealing personally- or device-identifying information. To
 * prevent tracking, the underlying cryptographic keys are rotated periodically.
 *
 * <p>This class allows applications to obtain trust tokens, use them to prove identity to a peer,
 * and verify trust tokens received from a peer.
 *
 * <h3>TrustToken Types</h3>
 *
 * There are two types of trust tokens:
 *
 * <ul>
 *   <li><b>Verified Device Trust Tokens:</b> These trust tokens attest to properties of the device
 *       itself, for example, that it is a genuine Android device running trusted software. They are
 *       anonymous and can be shared with untrusted parties without revealing sensitive information.
 *       Obtained via {@link #acquireVerifiedDeviceToken()}.
 *   <li><b>Identity Trust Tokens:</b> These trust tokens prove ownership of a specific identity
 *       (like a phone number or an account) without revealing the identity itself. They are always
 *       issued in a {@link TrustTokenIdentitySet}, which cryptographically binds them to a Verified
 *       Device Trust Token through a shared public key. The set also includes the secret key that
 *       was used to create the encrypted identity hash present in the identity trust tokens.
 *       Obtained via {@link #acquirePreparedIdentitySet()} after declaring needs with {@link
 *       #updatePreparedIdentities(List)}.
 * </ul>
 *
 * <h3>Common Usage Flow: Verified Device</h3>
 *
 * <p>A simple challenge-response flow is used to prove device integrity.
 *
 * <h4>On the Prover Device:</h4>
 *
 * <ol>
 *   <li><b>Receive a challenge:</b> The verifier will send a unique, random byte array (a "nonce"
 *       or "challenge"). One may also use a challenge derived from the connection.
 *   <li><b>Obtain a trust token:</b> Get a {@link TrustToken} and the challenge response using
 *       {@link #acquireVerifiedDeviceToken()}
 *   <li><b>Send response to verifier:</b> Send the encoded trust token (from {@link
 *       TrustToken#encoded()}) and the signature back to the verifier.
 * </ol>
 *
 * <h4>On the Verifier Device:</h4>
 *
 * <ol>
 *   <li><b>Generate and send a challenge:</b> Create a unique, random byte array and send it to the
 *       prover.
 *   <li><b>Receive the response:</b> Get the encoded trust token (as a byte array) and the
 *       signature from the prover.
 *   <li><b>Verify the response:</b> Construct a {@link TrustToken} from the encoded bytes and then
 *       use {@link #verifyTrustToken(TrustToken, byte[], byte[])} with the new object, the
 *       signature, and the original challenge you sent. A successful verification ({@link
 *       #VERIFICATION_SUCCESS}) proves that:
 *       <ul>
 *         <li>The prover possesses the private key for the trust token.
 *         <li>The trust token was signed by a party that this device trusts.
 *       </ul>
 * </ol>
 *
 * <h3>Common Usage Flow: Identity Verification</h3>
 *
 * <p>Verifying an identity is a more complex flow that uses a {@link TrustTokenIdentitySet} and a
 * protocol to match identities without revealing them.
 *
 * <h4>On the Prover Device:</h4>
 *
 * <ol>
 *   <li><b>Declare needs:</b>As they change, call {@link #updatePreparedIdentities(List)} with all
 *       identities that might be needed. This allows the system to fetch the necessary trust tokens
 *       in the background.
 *   <li><b>Get the set:</b> When ready to connect, call {@link #acquirePreparedIdentitySet()} to
 *       retrieve the pre-cached set.
 *   <li><b>Authenticate the channel:</b> Use the {@link TrustToken} from {@link
 *       TrustTokenIdentitySet#getVerifiedDeviceToken()} to perform the challenge-response flow
 *       described above, establishing a trusted channel with the verifier.
 *   <li><b>Perform identity matching:</b> Use the key from {@link
 *       TrustTokenIdentitySet#getSecretKey()} with the identity trust tokens in a client-side
 *       protocol to securely determine if the verifier is a known contact.
 *   <li><b>Present proof:</b> If the PSI protocol finds a match, send the corresponding {@link
 *       TrustToken} for that identity to the verifier over the secure channel.
 * </ol>
 *
 * <h4>On the Verifier Device:</h4>
 *
 * <ol>
 *   <li><b>Authenticate the channel:</b> Perform the verifier role in the challenge-response flow
 *       to establish a trusted channel and get the prover's verified device trust token.
 *   <li><b>Perform identity matching:</b> Participate in the identity matching protocol.
 *   <li><b>Receive proof:</b> After a successful match, receive the prover's identity trust token.
 *   <li><b>Verify the identity trust token:</b> Call {@link #verifyIdentityTokens(TrustToken,
 *       TrustToken...)} with the verified device trust token from step 1 and the received identity
 *       trust token. A successful result confirms the identity is cryptographically bound to the
 *       prover's device.
 * </ol>
 *
 * <h3>Resource Management</h3>
 *
 * <p>The system maintains a limited cache of trust tokens, which are fetched by a {@link
 * android.security.trusttoken.TrustTokenService}. Fetching new trust tokens can consume network and
 * battery resources.
 *
 * <p>For {@link TrustTokenIdentitySet}s, clients <b>should</b> declare their needs ahead of time
 * using {@link #updatePreparedIdentities(List)}. This allows the system to efficiently manage the
 * cache and ensure trust tokens are available when requested via {@link
 * #acquirePreparedIdentitySet()}.
 *
 * <p>Anonymous "verified device" trust tokens are managed separately by the system, and {@link
 * #acquireVerifiedDeviceToken()} can be called as needed without prior declaration.
 *
 * @hide
 */
@SystemService(Context.TRUST_TOKEN_SERVICE)
public class TrustTokenManager {
    private final ITrustTokenManager mService;

    /** Verification was successful. */
    public static final int VERIFICATION_SUCCESS = 0;

    /** Verification failed because the signature was invalid. */
    public static final int VERIFICATION_FAILURE_SIGNATURE_INVALID = 1;

    /** Verification failed because the challenge was incorrect. */
    public static final int VERIFICATION_FAILURE_CHALLENGE_INCORRECT = 2;

    /** Verification failed for an unknown reason. */
    public static final int VERIFICATION_FAILURE_UNKNOWN = -1;

    /** @hide */
    @IntDef(
            prefix = {"VERIFICATION_"},
            value = {
                VERIFICATION_SUCCESS,
                VERIFICATION_FAILURE_SIGNATURE_INVALID,
                VERIFICATION_FAILURE_CHALLENGE_INCORRECT,
                VERIFICATION_FAILURE_UNKNOWN
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface VerificationResult {}

    /** @hide */
    public TrustTokenManager(ITrustTokenManager service) {
        mService = service;
    }

    /**
     * Consumes and returns a verified device trust token from the system's cache.
     *
     * <p>This trust token is anonymous and can be treated with the same care as a public key.
     *
     * <p>The system maintains a limited number of trust tokens and must use the internet & system
     * battery to refresh the cache. Therefore, clients should only request trust tokens that they
     * will use imminently.
     *
     * <p>This operation requires the {@link android.Manifest.permission#SIGN_WITH_TRUST_TOKEN} and
     * the {@link android.Manifest.permission#ACQUIRE_VERIFIED_DEVICE_TOKEN} permissions.
     *
     * @param challenge the challenge from the verifier.
     * @throws TrustTokenUnavailableException if there's no token.
     * @throws TrustAnchorUnavailableException if there's no trust anchor.
     * @return a token with the challenge response.
     */
    // TODO(b/418280383): Add @RequiresPermission
    public TrustTokenWithChallenge acquireVerifiedDeviceToken(byte[] challenge)
            throws TrustTokenUnavailableException, TrustAnchorUnavailableException {
        try {
            return mService.acquireVerifiedDeviceToken(challenge);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Consumes and returns a {@link TrustTokenIdentitySet} from the system's cache.
     *
     * <p>The system maintains a limited number of trust tokens and must use the internet & system
     * battery to refresh the cache. Therefore, clients should only request trust tokens that they
     * will use imminently.
     *
     * @param challenge the challenge from the verifier.
     */
    // TODO(b/418280383): Add @RequiresPermission
    public TrustTokenIdentitySet acquirePreparedIdentitySet(byte[] challenge) {
        try {
            return mService.acquirePreparedIdentitySet(challenge);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the set of identities for which trust tokens should be kept ready.
     *
     * <p>The system uses this list to continuously and proactively fetch and cache {@link
     * TrustTokenIdentitySet}s in the background. This helps ensure that when {@link
     * #acquirePreparedIdentitySet()} is called, the required trust tokens are already available
     * without a long wait.
     *
     * <p>Clients only need to call this method when the set of required identities changes. Each
     * call replaces the previous list. The list of identities should be comprehensive, as any
     * identities not in the last-provided list may not have trust tokens cached. Calling this
     * method with an empty list will clear the set and stop background preparation.
     *
     * @param identities A list of identity strings (e.g., phone numbers, email addresses) that the
     *     client will need trust tokens for.
     */
    public void updatePreparedIdentities(@NonNull List<String> identities) {
        try {
            mService.updatePreparedIdentities(identities);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Verifies the trust token is trusted by this device & that the remote response is a signature
     * of expected challenge by the trust token's secret key.
     *
     * <p>The {@code expectedChallenge} should be a nonce generated by this device and sent to the
     * remote party, or a value derived from the secure channel's connection parameters (e.g., a
     * hash of the session key). The remote party must sign this challenge with the secret key
     * corresponding to the trust token's public key. That signature is provided as the {@code
     * remoteResponse}.
     *
     * @throw TrustAnchorUnavailableException if there's no trust anchor.
     * @return a {@link VerificationResult} code.
     */
    @CheckResult
    @VerificationResult
    public int verifyTrustToken(
            @NonNull TrustToken token,
            @NonNull byte[] remoteResponse,
            @NonNull byte[] expectedChallenge)
            throws TrustTokenUnavailableException {
        try {
            return mService.verifyTrustTokenAndChallenge(token, remoteResponse, expectedChallenge);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Verifies identity trust tokens against a previously-verified device trust token.
     *
     * <p>This method:
     *
     * <ol>
     *   <li>Verifies that the identity trust token itself is trusted by this device.
     *   <li>Verifies that the public key of the identity trust token matches the public key of the
     *       already-verified device trust token.
     * </ol>
     *
     * <p>This does <b>not</b> verify that the issuer of an identity trust token is the same as the
     * issuer of the verified device trust token or the other identity trust tokens.
     *
     * <p>This method MUST only be called after the caller has already verified the {@code
     * verifiedDeviceToken} using {@link #verifyTrustToken(TrustToken, byte[], byte[])}.
     *
     * @param verifiedDeviceToken The device trust token that has already been verified.
     * @param identityTokens A list of identity trust tokens to verify against the device trust
     *     token.
     * @return a list of {@link VerificationResult} codes, one for each identity trust token
     *     provided.
     */
    @CheckResult
    @NonNull
    public List<Integer> verifyIdentityTokens(
            @NonNull TrustToken verifiedDeviceToken, @NonNull TrustToken... identityTokens) {
        try {
            int[] results = mService.verifyIdentityTokens(verifiedDeviceToken, identityTokens);
            List<Integer> resultList = new ArrayList<>(results.length);
            for (int result : results) {
                resultList.add(result);
            }
            return resultList;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
