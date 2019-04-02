/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi.hotspot2;

import android.net.wifi.EAPConstants;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.Credential.SimCredential;
import android.net.wifi.hotspot2.pps.Credential.UserCredential;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.security.Credentials;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.android.server.wifi.IMSIParameter;
import com.android.server.wifi.SIMAccessor;
import com.android.server.wifi.WifiKeyStore;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType;
import com.android.server.wifi.hotspot2.anqp.DomainNameElement;
import com.android.server.wifi.hotspot2.anqp.NAIRealmElement;
import com.android.server.wifi.hotspot2.anqp.RoamingConsortiumElement;
import com.android.server.wifi.hotspot2.anqp.ThreeGPPNetworkElement;
import com.android.server.wifi.hotspot2.anqp.eap.AuthParam;
import com.android.server.wifi.hotspot2.anqp.eap.NonEAPInnerAuth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Abstraction for Passpoint service provider.  This class contains the both static
 * Passpoint configuration data and the runtime data (e.g. blacklisted SSIDs, statistics).
 */
public class PasspointProvider {
    private static final String TAG = "PasspointProvider";

    /**
     * Used as part of alias string for certificates and keys.  The alias string is in the format
     * of: [KEY_TYPE]_HS2_[ProviderID]
     * For example: "CACERT_HS2_0", "USRCERT_HS2_0", "USRPKEY_HS2_0"
     */
    private static final String ALIAS_HS_TYPE = "HS2_";

    private final PasspointConfiguration mConfig;
    private final WifiKeyStore mKeyStore;

    /**
     * Aliases for the private keys and certificates installed in the keystore.  Each alias
     * is a suffix of the actual certificate or key name installed in the keystore.  The
     * certificate or key name in the keystore is consist of |Type|_|alias|.
     * This will be consistent with the usage of the term "alias" in {@link WifiEnterpriseConfig}.
     */
    private String mCaCertificateAlias;
    private String mClientPrivateKeyAlias;
    private String mClientCertificateAlias;

    private final long mProviderId;
    private final int mCreatorUid;

    private final IMSIParameter mImsiParameter;
    private final List<String> mMatchingSIMImsiList;

    private final int mEAPMethodID;
    private final AuthParam mAuthParam;

    private boolean mHasEverConnected;

    public PasspointProvider(PasspointConfiguration config, WifiKeyStore keyStore,
            SIMAccessor simAccessor, long providerId, int creatorUid) {
        this(config, keyStore, simAccessor, providerId, creatorUid, null, null, null, false);
    }

    public PasspointProvider(PasspointConfiguration config, WifiKeyStore keyStore,
            SIMAccessor simAccessor, long providerId, int creatorUid, String caCertificateAlias,
            String clientCertificateAlias, String clientPrivateKeyAlias,
            boolean hasEverConnected) {
        // Maintain a copy of the configuration to avoid it being updated by others.
        mConfig = new PasspointConfiguration(config);
        mKeyStore = keyStore;
        mProviderId = providerId;
        mCreatorUid = creatorUid;
        mCaCertificateAlias = caCertificateAlias;
        mClientCertificateAlias = clientCertificateAlias;
        mClientPrivateKeyAlias = clientPrivateKeyAlias;
        mHasEverConnected = hasEverConnected;

        // Setup EAP method and authentication parameter based on the credential.
        if (mConfig.getCredential().getUserCredential() != null) {
            mEAPMethodID = EAPConstants.EAP_TTLS;
            mAuthParam = new NonEAPInnerAuth(NonEAPInnerAuth.getAuthTypeID(
                    mConfig.getCredential().getUserCredential().getNonEapInnerMethod()));
            mImsiParameter = null;
            mMatchingSIMImsiList = null;
        } else if (mConfig.getCredential().getCertCredential() != null) {
            mEAPMethodID = EAPConstants.EAP_TLS;
            mAuthParam = null;
            mImsiParameter = null;
            mMatchingSIMImsiList = null;
        } else {
            mEAPMethodID = mConfig.getCredential().getSimCredential().getEapType();
            mAuthParam = null;
            mImsiParameter = IMSIParameter.build(
                    mConfig.getCredential().getSimCredential().getImsi());
            mMatchingSIMImsiList = simAccessor.getMatchingImsis(mImsiParameter);
        }
    }

    public PasspointConfiguration getConfig() {
        // Return a copy of the configuration to avoid it being updated by others.
        return new PasspointConfiguration(mConfig);
    }

    public String getCaCertificateAlias() {
        return mCaCertificateAlias;
    }

    public String getClientPrivateKeyAlias() {
        return mClientPrivateKeyAlias;
    }

    public String getClientCertificateAlias() {
        return mClientCertificateAlias;
    }

    public long getProviderId() {
        return mProviderId;
    }

    public int getCreatorUid() {
        return mCreatorUid;
    }

    public boolean getHasEverConnected() {
        return mHasEverConnected;
    }

    public void setHasEverConnected(boolean hasEverConnected) {
        mHasEverConnected = hasEverConnected;
    }

    /**
     * Install certificates and key based on current configuration.
     * Note: the certificates and keys in the configuration will get cleared once
     * they're installed in the keystore.
     *
     * @return true on success
     */
    public boolean installCertsAndKeys() {
        // Install CA certificate.
        if (mConfig.getCredential().getCaCertificate() != null) {
            String certName = Credentials.CA_CERTIFICATE + ALIAS_HS_TYPE + mProviderId;
            if (!mKeyStore.putCertInKeyStore(certName,
                    mConfig.getCredential().getCaCertificate())) {
                Log.e(TAG, "Failed to install CA Certificate");
                uninstallCertsAndKeys();
                return false;
            }
            mCaCertificateAlias = ALIAS_HS_TYPE + mProviderId;
        }

        // Install the client private key.
        if (mConfig.getCredential().getClientPrivateKey() != null) {
            String keyName = Credentials.USER_PRIVATE_KEY + ALIAS_HS_TYPE + mProviderId;
            if (!mKeyStore.putKeyInKeyStore(keyName,
                    mConfig.getCredential().getClientPrivateKey())) {
                Log.e(TAG, "Failed to install client private key");
                uninstallCertsAndKeys();
                return false;
            }
            mClientPrivateKeyAlias = ALIAS_HS_TYPE + mProviderId;
        }

        // Install the client certificate.
        if (mConfig.getCredential().getClientCertificateChain() != null) {
            X509Certificate clientCert = getClientCertificate(
                    mConfig.getCredential().getClientCertificateChain(),
                    mConfig.getCredential().getCertCredential().getCertSha256Fingerprint());
            if (clientCert == null) {
                Log.e(TAG, "Failed to locate client certificate");
                uninstallCertsAndKeys();
                return false;
            }
            String certName = Credentials.USER_CERTIFICATE + ALIAS_HS_TYPE + mProviderId;
            if (!mKeyStore.putCertInKeyStore(certName, clientCert)) {
                Log.e(TAG, "Failed to install client certificate");
                uninstallCertsAndKeys();
                return false;
            }
            mClientCertificateAlias = ALIAS_HS_TYPE + mProviderId;
        }

        // Clear the keys and certificates in the configuration.
        mConfig.getCredential().setCaCertificate(null);
        mConfig.getCredential().setClientPrivateKey(null);
        mConfig.getCredential().setClientCertificateChain(null);
        return true;
    }

    /**
     * Remove any installed certificates and key.
     */
    public void uninstallCertsAndKeys() {
        if (mCaCertificateAlias != null) {
            if (!mKeyStore.removeEntryFromKeyStore(
                    Credentials.CA_CERTIFICATE + mCaCertificateAlias)) {
                Log.e(TAG, "Failed to remove entry: " + mCaCertificateAlias);
            }
            mCaCertificateAlias = null;
        }
        if (mClientPrivateKeyAlias != null) {
            if (!mKeyStore.removeEntryFromKeyStore(
                    Credentials.USER_PRIVATE_KEY + mClientPrivateKeyAlias)) {
                Log.e(TAG, "Failed to remove entry: " + mClientPrivateKeyAlias);
            }
            mClientPrivateKeyAlias = null;
        }
        if (mClientCertificateAlias != null) {
            if (!mKeyStore.removeEntryFromKeyStore(
                    Credentials.USER_CERTIFICATE + mClientCertificateAlias)) {
                Log.e(TAG, "Failed to remove entry: " + mClientCertificateAlias);
            }
            mClientCertificateAlias = null;
        }
    }

    /**
     * Return the matching status with the given AP, based on the ANQP elements from the AP.
     *
     * @param anqpElements ANQP elements from the AP
     * @return {@link PasspointMatch}
     */
    public PasspointMatch match(Map<ANQPElementType, ANQPElement> anqpElements) {
        PasspointMatch providerMatch = matchProvider(anqpElements);

        // Perform authentication match against the NAI Realm.
        int authMatch = ANQPMatcher.matchNAIRealm(
                (NAIRealmElement) anqpElements.get(ANQPElementType.ANQPNAIRealm),
                mConfig.getCredential().getRealm(), mEAPMethodID, mAuthParam);

        // Auth mismatch, demote provider match.
        if (authMatch == AuthMatch.NONE) {
            return PasspointMatch.None;
        }

        // No realm match, return provider match as is.
        if ((authMatch & AuthMatch.REALM) == 0) {
            return providerMatch;
        }

        // Realm match, promote provider match to roaming if no other provider match is found.
        return providerMatch == PasspointMatch.None ? PasspointMatch.RoamingProvider
                : providerMatch;
    }

    /**
     * Generate a WifiConfiguration based on the provider's configuration.  The generated
     * WifiConfiguration will include all the necessary credentials for network connection except
     * the SSID, which should be added by the caller when the config is being used for network
     * connection.
     *
     * @return {@link WifiConfiguration}
     */
    public WifiConfiguration getWifiConfig() {
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = mConfig.getHomeSp().getFqdn();
        if (mConfig.getHomeSp().getRoamingConsortiumOis() != null) {
            wifiConfig.roamingConsortiumIds = Arrays.copyOf(
                    mConfig.getHomeSp().getRoamingConsortiumOis(),
                    mConfig.getHomeSp().getRoamingConsortiumOis().length);
        }
        wifiConfig.providerFriendlyName = mConfig.getHomeSp().getFriendlyName();
        wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
        wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X);

        WifiEnterpriseConfig enterpriseConfig = new WifiEnterpriseConfig();
        enterpriseConfig.setRealm(mConfig.getCredential().getRealm());
        enterpriseConfig.setDomainSuffixMatch(mConfig.getHomeSp().getFqdn());
        if (mConfig.getCredential().getUserCredential() != null) {
            buildEnterpriseConfigForUserCredential(enterpriseConfig,
                    mConfig.getCredential().getUserCredential());
            setAnonymousIdentityToNaiRealm(enterpriseConfig, mConfig.getCredential().getRealm());
        } else if (mConfig.getCredential().getCertCredential() != null) {
            buildEnterpriseConfigForCertCredential(enterpriseConfig);
            setAnonymousIdentityToNaiRealm(enterpriseConfig, mConfig.getCredential().getRealm());
        } else {
            buildEnterpriseConfigForSimCredential(enterpriseConfig,
                    mConfig.getCredential().getSimCredential());
        }
        wifiConfig.enterpriseConfig = enterpriseConfig;
        return wifiConfig;
    }

    /**
     * @return true if provider is backed by a SIM credential.
     */
    public boolean isSimCredential() {
        return mConfig.getCredential().getSimCredential() != null;
    }

    /**
     * Convert a legacy {@link WifiConfiguration} representation of a Passpoint configuration to
     * a {@link PasspointConfiguration}.  This is used for migrating legacy Passpoint
     * configuration (release N and older).
     *
     * @param wifiConfig The {@link WifiConfiguration} to convert
     * @return {@link PasspointConfiguration}
     */
    public static PasspointConfiguration convertFromWifiConfig(WifiConfiguration wifiConfig) {
        PasspointConfiguration passpointConfig = new PasspointConfiguration();

        // Setup HomeSP.
        HomeSp homeSp = new HomeSp();
        if (TextUtils.isEmpty(wifiConfig.FQDN)) {
            Log.e(TAG, "Missing FQDN");
            return null;
        }
        homeSp.setFqdn(wifiConfig.FQDN);
        homeSp.setFriendlyName(wifiConfig.providerFriendlyName);
        if (wifiConfig.roamingConsortiumIds != null) {
            homeSp.setRoamingConsortiumOis(Arrays.copyOf(
                    wifiConfig.roamingConsortiumIds, wifiConfig.roamingConsortiumIds.length));
        }
        passpointConfig.setHomeSp(homeSp);

        // Setup Credential.
        Credential credential = new Credential();
        credential.setRealm(wifiConfig.enterpriseConfig.getRealm());
        switch (wifiConfig.enterpriseConfig.getEapMethod()) {
            case WifiEnterpriseConfig.Eap.TTLS:
                credential.setUserCredential(buildUserCredentialFromEnterpriseConfig(
                        wifiConfig.enterpriseConfig));
                break;
            case WifiEnterpriseConfig.Eap.TLS:
                Credential.CertificateCredential certCred = new Credential.CertificateCredential();
                certCred.setCertType(Credential.CertificateCredential.CERT_TYPE_X509V3);
                credential.setCertCredential(certCred);
                break;
            case WifiEnterpriseConfig.Eap.SIM:
                credential.setSimCredential(buildSimCredentialFromEnterpriseConfig(
                        EAPConstants.EAP_SIM, wifiConfig.enterpriseConfig));
                break;
            case WifiEnterpriseConfig.Eap.AKA:
                credential.setSimCredential(buildSimCredentialFromEnterpriseConfig(
                        EAPConstants.EAP_AKA, wifiConfig.enterpriseConfig));
                break;
            case WifiEnterpriseConfig.Eap.AKA_PRIME:
                credential.setSimCredential(buildSimCredentialFromEnterpriseConfig(
                        EAPConstants.EAP_AKA_PRIME, wifiConfig.enterpriseConfig));
                break;
            default:
                Log.e(TAG, "Unsupport EAP method: " + wifiConfig.enterpriseConfig.getEapMethod());
                return null;
        }
        if (credential.getUserCredential() == null && credential.getCertCredential() == null
                && credential.getSimCredential() == null) {
            Log.e(TAG, "Missing credential");
            return null;
        }
        passpointConfig.setCredential(credential);

        return passpointConfig;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof PasspointProvider)) {
            return false;
        }
        PasspointProvider that = (PasspointProvider) thatObject;
        return mProviderId == that.mProviderId
                && TextUtils.equals(mCaCertificateAlias, that.mCaCertificateAlias)
                && TextUtils.equals(mClientCertificateAlias, that.mClientCertificateAlias)
                && TextUtils.equals(mClientPrivateKeyAlias, that.mClientPrivateKeyAlias)
                && (mConfig == null ? that.mConfig == null : mConfig.equals(that.mConfig));
    }

    @Override
    public int hashCode() {
        return Objects.hash(mProviderId, mCaCertificateAlias, mClientCertificateAlias,
                mClientPrivateKeyAlias, mConfig);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("ProviderId: ").append(mProviderId).append("\n");
        builder.append("CreatorUID: ").append(mCreatorUid).append("\n");
        builder.append("Configuration Begin ---\n");
        builder.append(mConfig);
        builder.append("Configuration End ---\n");
        return builder.toString();
    }

    /**
     * Retrieve the client certificate from the certificates chain.  The certificate
     * with the matching SHA256 digest is the client certificate.
     *
     * @param certChain The client certificates chain
     * @param expectedSha256Fingerprint The expected SHA256 digest of the client certificate
     * @return {@link java.security.cert.X509Certificate}
     */
    private static X509Certificate getClientCertificate(X509Certificate[] certChain,
            byte[] expectedSha256Fingerprint) {
        if (certChain == null) {
            return null;
        }
        try {
            MessageDigest digester = MessageDigest.getInstance("SHA-256");
            for (X509Certificate certificate : certChain) {
                digester.reset();
                byte[] fingerprint = digester.digest(certificate.getEncoded());
                if (Arrays.equals(expectedSha256Fingerprint, fingerprint)) {
                    return certificate;
                }
            }
        } catch (CertificateEncodingException | NoSuchAlgorithmException e) {
            return null;
        }

        return null;
    }

    /**
     * Perform a provider match based on the given ANQP elements.
     *
     * @param anqpElements List of ANQP elements
     * @return {@link PasspointMatch}
     */
    private PasspointMatch matchProvider(Map<ANQPElementType, ANQPElement> anqpElements) {
        // Domain name matching.
        if (ANQPMatcher.matchDomainName(
                (DomainNameElement) anqpElements.get(ANQPElementType.ANQPDomName),
                mConfig.getHomeSp().getFqdn(), mImsiParameter, mMatchingSIMImsiList)) {
            return PasspointMatch.HomeProvider;
        }

        // Roaming Consortium OI matching.
        if (ANQPMatcher.matchRoamingConsortium(
                (RoamingConsortiumElement) anqpElements.get(ANQPElementType.ANQPRoamingConsortium),
                mConfig.getHomeSp().getRoamingConsortiumOis())) {
            return PasspointMatch.RoamingProvider;
        }

        // 3GPP Network matching.
        if (ANQPMatcher.matchThreeGPPNetwork(
                (ThreeGPPNetworkElement) anqpElements.get(ANQPElementType.ANQP3GPPNetwork),
                mImsiParameter, mMatchingSIMImsiList)) {
            return PasspointMatch.RoamingProvider;
        }
        return PasspointMatch.None;
    }

    /**
     * Fill in WifiEnterpriseConfig with information from an user credential.
     *
     * @param config Instance of {@link WifiEnterpriseConfig}
     * @param credential Instance of {@link UserCredential}
     */
    private void buildEnterpriseConfigForUserCredential(WifiEnterpriseConfig config,
            Credential.UserCredential credential) {
        byte[] pwOctets = Base64.decode(credential.getPassword(), Base64.DEFAULT);
        String decodedPassword = new String(pwOctets, StandardCharsets.UTF_8);
        config.setEapMethod(WifiEnterpriseConfig.Eap.TTLS);
        config.setIdentity(credential.getUsername());
        config.setPassword(decodedPassword);
        config.setCaCertificateAlias(mCaCertificateAlias);
        int phase2Method = WifiEnterpriseConfig.Phase2.NONE;
        switch (credential.getNonEapInnerMethod()) {
            case Credential.UserCredential.AUTH_METHOD_PAP:
                phase2Method = WifiEnterpriseConfig.Phase2.PAP;
                break;
            case Credential.UserCredential.AUTH_METHOD_MSCHAP:
                phase2Method = WifiEnterpriseConfig.Phase2.MSCHAP;
                break;
            case Credential.UserCredential.AUTH_METHOD_MSCHAPV2:
                phase2Method = WifiEnterpriseConfig.Phase2.MSCHAPV2;
                break;
            default:
                // Should never happen since this is already validated when the provider is
                // added.
                Log.wtf(TAG, "Unsupported Auth: " + credential.getNonEapInnerMethod());
                break;
        }
        config.setPhase2Method(phase2Method);
    }

    /**
     * Fill in WifiEnterpriseConfig with information from a certificate credential.
     *
     * @param config Instance of {@link WifiEnterpriseConfig}
     */
    private void buildEnterpriseConfigForCertCredential(WifiEnterpriseConfig config) {
        config.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        config.setClientCertificateAlias(mClientCertificateAlias);
        config.setCaCertificateAlias(mCaCertificateAlias);
    }

    /**
     * Fill in WifiEnterpriseConfig with information from a SIM credential.
     *
     * @param config Instance of {@link WifiEnterpriseConfig}
     * @param credential Instance of {@link SimCredential}
     */
    private void buildEnterpriseConfigForSimCredential(WifiEnterpriseConfig config,
            Credential.SimCredential credential) {
        int eapMethod = WifiEnterpriseConfig.Eap.NONE;
        switch(credential.getEapType()) {
            case EAPConstants.EAP_SIM:
                eapMethod = WifiEnterpriseConfig.Eap.SIM;
                break;
            case EAPConstants.EAP_AKA:
                eapMethod = WifiEnterpriseConfig.Eap.AKA;
                break;
            case EAPConstants.EAP_AKA_PRIME:
                eapMethod = WifiEnterpriseConfig.Eap.AKA_PRIME;
                break;
            default:
                // Should never happen since this is already validated when the provider is
                // added.
                Log.wtf(TAG, "Unsupported EAP Method: " + credential.getEapType());
                break;
        }
        config.setEapMethod(eapMethod);
        config.setPlmn(credential.getImsi());
    }

    private static void setAnonymousIdentityToNaiRealm(WifiEnterpriseConfig config, String realm) {
        /**
         * Set WPA supplicant's anonymous identity field to a string containing the NAI realm, so
         * that this value will be sent to the EAP server as part of the EAP-Response/ Identity
         * packet. WPA supplicant will reset this field after using it for the EAP-Response/Identity
         * packet, and revert to using the (real) identity field for subsequent transactions that
         * request an identity (e.g. in EAP-TTLS).
         *
         * This NAI realm value (the portion of the identity after the '@') is used to tell the
         * AAA server which AAA/H to forward packets to. The hardcoded username, "anonymous", is a
         * placeholder that is not used--it is set to this value by convention. See Section 5.1 of
         * RFC3748 for more details.
         *
         * NOTE: we do not set this value for EAP-SIM/AKA/AKA', since the EAP server expects the
         * EAP-Response/Identity packet to contain an actual, IMSI-based identity, in order to
         * identify the device.
         */
        config.setAnonymousIdentity("anonymous@" + realm);
    }

    /**
     * Helper function for creating a
     * {@link android.net.wifi.hotspot2.pps.Credential.UserCredential} from the given
     * {@link WifiEnterpriseConfig}
     *
     * @param config The enterprise configuration containing the credential
     * @return {@link android.net.wifi.hotspot2.pps.Credential.UserCredential}
     */
    private static Credential.UserCredential buildUserCredentialFromEnterpriseConfig(
            WifiEnterpriseConfig config) {
        Credential.UserCredential userCredential = new Credential.UserCredential();
        userCredential.setEapType(EAPConstants.EAP_TTLS);

        if (TextUtils.isEmpty(config.getIdentity())) {
            Log.e(TAG, "Missing username for user credential");
            return null;
        }
        userCredential.setUsername(config.getIdentity());

        if (TextUtils.isEmpty(config.getPassword())) {
            Log.e(TAG, "Missing password for user credential");
            return null;
        }
        String encodedPassword =
                new String(Base64.encode(config.getPassword().getBytes(StandardCharsets.UTF_8),
                        Base64.DEFAULT), StandardCharsets.UTF_8);
        userCredential.setPassword(encodedPassword);

        switch(config.getPhase2Method()) {
            case WifiEnterpriseConfig.Phase2.PAP:
                userCredential.setNonEapInnerMethod(Credential.UserCredential.AUTH_METHOD_PAP);
                break;
            case WifiEnterpriseConfig.Phase2.MSCHAP:
                userCredential.setNonEapInnerMethod(Credential.UserCredential.AUTH_METHOD_MSCHAP);
                break;
            case WifiEnterpriseConfig.Phase2.MSCHAPV2:
                userCredential.setNonEapInnerMethod(Credential.UserCredential.AUTH_METHOD_MSCHAPV2);
                break;
            default:
                Log.e(TAG, "Unsupported phase2 method for TTLS: " + config.getPhase2Method());
                return null;
        }
        return userCredential;
    }

    /**
     * Helper function for creating a
     * {@link android.net.wifi.hotspot2.pps.Credential.SimCredential} from the given
     * {@link WifiEnterpriseConfig}
     *
     * @param eapType The EAP type of the SIM credential
     * @param config The enterprise configuration containing the credential
     * @return {@link android.net.wifi.hotspot2.pps.Credential.SimCredential}
     */
    private static Credential.SimCredential buildSimCredentialFromEnterpriseConfig(
            int eapType, WifiEnterpriseConfig config) {
        Credential.SimCredential simCredential = new Credential.SimCredential();
        if (TextUtils.isEmpty(config.getPlmn())) {
            Log.e(TAG, "Missing IMSI for SIM credential");
            return null;
        }
        simCredential.setImsi(config.getPlmn());
        simCredential.setEapType(eapType);
        return simCredential;
    }
}
