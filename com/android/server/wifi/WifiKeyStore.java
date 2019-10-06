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

package com.android.server.wifi;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.os.Process;
import android.security.Credentials;
import android.security.KeyChain;
import android.security.KeyStore;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.Key;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * This class provides the methods to access keystore for certificate management.
 *
 * NOTE: This class should only be used from WifiConfigManager!
 */
public class WifiKeyStore {
    private static final String TAG = "WifiKeyStore";

    private boolean mVerboseLoggingEnabled = false;

    private final KeyStore mKeyStore;

    WifiKeyStore(KeyStore keyStore) {
        mKeyStore = keyStore;
    }

    /**
     * Enable verbose logging.
     */
    void enableVerboseLogging(boolean verbose) {
        mVerboseLoggingEnabled = verbose;
    }

    // Certificate and private key management for EnterpriseConfig
    private static boolean needsKeyStore(WifiEnterpriseConfig config) {
        return (config.getClientCertificate() != null || config.getCaCertificate() != null);
    }

    private static boolean isHardwareBackedKey(Key key) {
        return KeyChain.isBoundKeyAlgorithm(key.getAlgorithm());
    }

    private static boolean hasHardwareBackedKey(Certificate certificate) {
        return isHardwareBackedKey(certificate.getPublicKey());
    }

    /**
     * Install keys for given enterprise network.
     *
     * @param existingConfig Existing config corresponding to the network already stored in our
     *                       database. This maybe null if it's a new network.
     * @param config         Config corresponding to the network.
     * @return true if successful, false otherwise.
     */
    private boolean installKeys(WifiEnterpriseConfig existingConfig, WifiEnterpriseConfig config,
            String name) {
        boolean ret = true;
        String privKeyName = Credentials.USER_PRIVATE_KEY + name;
        String userCertName = Credentials.USER_CERTIFICATE + name;
        Certificate[] clientCertificateChain = config.getClientCertificateChain();
        if (clientCertificateChain != null && clientCertificateChain.length != 0) {
            byte[] privKeyData = config.getClientPrivateKey().getEncoded();
            if (mVerboseLoggingEnabled) {
                if (isHardwareBackedKey(config.getClientPrivateKey())) {
                    Log.d(TAG, "importing keys " + name + " in hardware backed store");
                } else {
                    Log.d(TAG, "importing keys " + name + " in software backed store");
                }
            }
            ret = mKeyStore.importKey(privKeyName, privKeyData, Process.WIFI_UID,
                    KeyStore.FLAG_NONE);

            if (!ret) {
                return ret;
            }

            ret = putCertsInKeyStore(userCertName, clientCertificateChain);
            if (!ret) {
                // Remove private key installed
                mKeyStore.delete(privKeyName, Process.WIFI_UID);
                return ret;
            }
        }

        X509Certificate[] caCertificates = config.getCaCertificates();
        Set<String> oldCaCertificatesToRemove = new ArraySet<>();
        if (existingConfig != null && existingConfig.getCaCertificateAliases() != null) {
            oldCaCertificatesToRemove.addAll(
                    Arrays.asList(existingConfig.getCaCertificateAliases()));
        }
        List<String> caCertificateAliases = null;
        if (caCertificates != null) {
            caCertificateAliases = new ArrayList<>();
            for (int i = 0; i < caCertificates.length; i++) {
                String alias = caCertificates.length == 1 ? name
                        : String.format("%s_%d", name, i);

                oldCaCertificatesToRemove.remove(alias);
                ret = putCertInKeyStore(Credentials.CA_CERTIFICATE + alias, caCertificates[i]);
                if (!ret) {
                    // Remove client key+cert
                    if (config.getClientCertificate() != null) {
                        mKeyStore.delete(privKeyName, Process.WIFI_UID);
                        mKeyStore.delete(userCertName, Process.WIFI_UID);
                    }
                    // Remove added CA certs.
                    for (String addedAlias : caCertificateAliases) {
                        mKeyStore.delete(Credentials.CA_CERTIFICATE + addedAlias, Process.WIFI_UID);
                    }
                    return ret;
                } else {
                    caCertificateAliases.add(alias);
                }
            }
        }
        // Remove old CA certs.
        for (String oldAlias : oldCaCertificatesToRemove) {
            mKeyStore.delete(Credentials.CA_CERTIFICATE + oldAlias, Process.WIFI_UID);
        }
        // Set alias names
        if (config.getClientCertificate() != null) {
            config.setClientCertificateAlias(name);
            config.resetClientKeyEntry();
        }

        if (caCertificates != null) {
            config.setCaCertificateAliases(
                    caCertificateAliases.toArray(new String[caCertificateAliases.size()]));
            config.resetCaCertificate();
        }
        return ret;
    }

    /**
     * Install a certificate into the keystore.
     *
     * @param name The alias name of the certificate to be installed
     * @param cert The certificate to be installed
     * @return true on success
     */
    public boolean putCertInKeyStore(String name, Certificate cert) {
        return putCertsInKeyStore(name, new Certificate[] {cert});
    }

    /**
     * Install a client certificate chain into the keystore.
     *
     * @param name The alias name of the certificate to be installed
     * @param certs The certificate chain to be installed
     * @return true on success
     */
    public boolean putCertsInKeyStore(String name, Certificate[] certs) {
        try {
            byte[] certData = Credentials.convertToPem(certs);
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "putting " + certs.length + " certificate(s) "
                        + name + " in keystore");
            }
            return mKeyStore.put(name, certData, Process.WIFI_UID, KeyStore.FLAG_NONE);
        } catch (IOException e1) {
            return false;
        } catch (CertificateException e2) {
            return false;
        }
    }

    /**
     * Install a key into the keystore.
     *
     * @param name The alias name of the key to be installed
     * @param key The key to be installed
     * @return true on success
     */
    public boolean putKeyInKeyStore(String name, Key key) {
        byte[] privKeyData = key.getEncoded();
        return mKeyStore.importKey(name, privKeyData, Process.WIFI_UID, KeyStore.FLAG_NONE);
    }

    /**
     * Remove a certificate or key entry specified by the alias name from the keystore.
     *
     * @param name The alias name of the entry to be removed
     * @return true on success
     */
    public boolean removeEntryFromKeyStore(String name) {
        return mKeyStore.delete(name, Process.WIFI_UID);
    }

    /**
     * Remove enterprise keys from the network config.
     *
     * @param config Config corresponding to the network.
     */
    public void removeKeys(WifiEnterpriseConfig config) {
        // Do not remove keys that were manually installed by the user
        if (config.isAppInstalledDeviceKeyAndCert()) {
            String client = config.getClientCertificateAlias();
            // a valid client certificate is configured
            if (!TextUtils.isEmpty(client)) {
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "removing client private key and user cert");
                }
                mKeyStore.delete(Credentials.USER_PRIVATE_KEY + client, Process.WIFI_UID);
                mKeyStore.delete(Credentials.USER_CERTIFICATE + client, Process.WIFI_UID);
            }
        }

        // Do not remove CA certs that were manually installed by the user
        if (config.isAppInstalledCaCert()) {
            String[] aliases = config.getCaCertificateAliases();
            // a valid ca certificate is configured
            if (aliases != null) {
                for (String ca : aliases) {
                    if (!TextUtils.isEmpty(ca)) {
                        if (mVerboseLoggingEnabled) {
                            Log.d(TAG, "removing CA cert: " + ca);
                        }
                        mKeyStore.delete(Credentials.CA_CERTIFICATE + ca, Process.WIFI_UID);
                    }
                }
            }
        }
    }


    /**
     * @param certData byte array of the certificate
     */
    private X509Certificate buildCACertificate(byte[] certData) {
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            InputStream inputStream = new ByteArrayInputStream(certData);
            X509Certificate caCertificateX509 = (X509Certificate) certificateFactory
                    .generateCertificate(inputStream);
            return caCertificateX509;
        } catch (CertificateException e) {
            return null;
        }
    }
    /**
     * Update/Install keys for given enterprise network.
     *
     * @param config         Config corresponding to the network.
     * @param existingConfig Existing config corresponding to the network already stored in our
     *                       database. This maybe null if it's a new network.
     * @return true if successful, false otherwise.
     */
    public boolean updateNetworkKeys(WifiConfiguration config, WifiConfiguration existingConfig) {
        WifiEnterpriseConfig enterpriseConfig = config.enterpriseConfig;
        if (!needsKeyStore(enterpriseConfig)) {
            return true;
        }

        try {
            /* config passed may include only fields being updated.
             * In order to generate the key id, fetch uninitialized
             * fields from the currently tracked configuration
             */
            String keyId = config.getKeyIdForCredentials(existingConfig);
            if (!installKeys(existingConfig != null
                    ? existingConfig.enterpriseConfig : null, enterpriseConfig, keyId)) {
                Log.e(TAG, config.SSID + ": failed to install keys");
                return false;
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, config.SSID + " invalid config for key installation: " + e.getMessage());
            return false;
        }

        // For WPA3-Enterprise 192-bit networks, set the SuiteBCipher field based on the
        // CA certificate type. Suite-B requires SHA384, reject other certs.
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SUITE_B_192)) {
            // Read the first CA certificate, and initialize
            byte[] certData = mKeyStore.get(
                    Credentials.CA_CERTIFICATE + config.enterpriseConfig.getCaCertificateAlias(),
                    android.os.Process.WIFI_UID);

            if (certData == null) {
                Log.e(TAG, "Failed reading CA certificate for Suite-B");
                return false;
            }

            X509Certificate x509CaCert = buildCACertificate(certData);

            if (x509CaCert != null) {
                String sigAlgOid = x509CaCert.getSigAlgOID();
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "Signature algorithm: " + sigAlgOid);
                }
                config.allowedSuiteBCiphers.clear();

                // Wi-Fi alliance requires the use of both ECDSA secp384r1 and RSA 3072 certificates
                // in WPA3-Enterprise 192-bit security networks, which are also known as Suite-B-192
                // networks, even though NSA Suite-B-192 mandates ECDSA only. The use of the term
                // Suite-B was already coined in the IEEE 802.11-2016 specification for
                // AKM 00-0F-AC but the test plan for WPA3-Enterprise 192-bit for APs mandates
                // support for both RSA and ECDSA, and for STAs it mandates ECDSA and optionally
                // RSA. In order to be compatible with all WPA3-Enterprise 192-bit deployments,
                // we are supporting both types here.
                if (sigAlgOid.equals("1.2.840.113549.1.1.12")) {
                    // sha384WithRSAEncryption
                    config.allowedSuiteBCiphers.set(
                            WifiConfiguration.SuiteBCipher.ECDHE_RSA);
                    if (mVerboseLoggingEnabled) {
                        Log.d(TAG, "Selecting Suite-B RSA");
                    }
                } else if (sigAlgOid.equals("1.2.840.10045.4.3.3")) {
                    // ecdsa-with-SHA384
                    config.allowedSuiteBCiphers.set(
                            WifiConfiguration.SuiteBCipher.ECDHE_ECDSA);
                    if (mVerboseLoggingEnabled) {
                        Log.d(TAG, "Selecting Suite-B ECDSA");
                    }
                } else {
                    Log.e(TAG, "Invalid CA certificate type for Suite-B: "
                            + sigAlgOid);
                    return false;
                }
            } else {
                Log.e(TAG, "Invalid CA certificate for Suite-B");
                return false;
            }
        }
        return true;
    }
}
