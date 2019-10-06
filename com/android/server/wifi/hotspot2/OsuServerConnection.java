/*
 * Copyright 2017 The Android Open Source Project
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

import android.annotation.NonNull;
import android.net.Network;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.org.conscrypt.TrustManagerImpl;
import com.android.server.wifi.hotspot2.soap.HttpsServiceConnection;
import com.android.server.wifi.hotspot2.soap.HttpsTransport;
import com.android.server.wifi.hotspot2.soap.SoapParser;
import com.android.server.wifi.hotspot2.soap.SppResponseMessage;

import org.ksoap2.HeaderProperty;
import org.ksoap2.serialization.AttributeInfo;
import org.ksoap2.serialization.SoapObject;
import org.ksoap2.serialization.SoapSerializationEnvelope;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Provides methods to interface with the OSU server
 */
public class OsuServerConnection {
    private static final String TAG = "PasspointOsuServerConnection";

    private static final int DNS_NAME = 2;

    private SSLSocketFactory mSocketFactory;
    private URL mUrl;
    private Network mNetwork;
    private WFATrustManager mTrustManager;
    private HttpsTransport mHttpsTransport;
    private HttpsServiceConnection mServiceConnection = null;
    private HttpsURLConnection mUrlConnection = null;
    private HandlerThread mOsuServerHandlerThread;
    private Handler mHandler;
    private PasspointProvisioner.OsuServerCallbacks mOsuServerCallbacks;
    private boolean mSetupComplete = false;
    private boolean mVerboseLoggingEnabled = false;
    private Looper mLooper;

    public static final int TRUST_CERT_TYPE_AAA = 1;
    public static final int TRUST_CERT_TYPE_REMEDIATION = 2;
    public static final int TRUST_CERT_TYPE_POLICY = 3;

    @VisibleForTesting
    /* package */ OsuServerConnection(Looper looper) {
        mLooper = looper;
    }

    /**
     * Sets up callback for event
     *
     * @param callbacks OsuServerCallbacks to be invoked for server related events
     */
    public void setEventCallback(PasspointProvisioner.OsuServerCallbacks callbacks) {
        mOsuServerCallbacks = callbacks;
    }

    /**
     * Initializes socket factory for server connection using HTTPS
     *
     * @param tlsContext       SSLContext that will be used for HTTPS connection
     * @param trustManagerImpl TrustManagerImpl delegate to validate certs
     */
    public void init(SSLContext tlsContext, TrustManagerImpl trustManagerImpl) {
        if (tlsContext == null) {
            return;
        }
        try {
            mTrustManager = new WFATrustManager(trustManagerImpl);
            tlsContext.init(null, new TrustManager[]{mTrustManager}, null);
            mSocketFactory = tlsContext.getSocketFactory();
        } catch (KeyManagementException e) {
            Log.w(TAG, "Initialization failed");
            e.printStackTrace();
            return;
        }
        mSetupComplete = true;

        // If mLooper is already set by unit test, don't overwrite it.
        if (mLooper == null) {
            mOsuServerHandlerThread = new HandlerThread("OsuServerHandler");
            mOsuServerHandlerThread.start();
            mLooper = mOsuServerHandlerThread.getLooper();
        }
        mHandler = new Handler(mLooper);
    }

    /**
     * Provides the capability to run OSU server validation
     *
     * @return boolean true if capability available
     */
    public boolean canValidateServer() {
        return mSetupComplete;
    }

    /**
     * Enables verbose logging
     *
     * @param verbose a value greater than zero enables verbose logging
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = verbose > 0 ? true : false;
    }

    /**
     * Connects to the OSU server
     *
     * @param url     Osu Server's URL
     * @param network current network connection
     * @return {@code true} if {@code url} and {@code network} are not null
     *
     * Note: Relies on the caller to ensure that the capability to validate the OSU
     * Server is available.
     */
    public boolean connect(@NonNull URL url, @NonNull Network network) {
        if (url == null) {
            Log.e(TAG, "url is null");
            return false;
        }
        if (network == null) {
            Log.e(TAG, "network is null");
            return false;
        }

        mHandler.post(() -> performTlsConnection(url, network));
        return true;
    }

    /**
     * Validates the service provider by comparing its identities found in OSU Server cert
     * to the friendlyName obtained from ANQP exchange that is displayed to the user.
     *
     * @param locale       a {@link Locale} object used for matching the friendly name in
     *                     subjectAltName section of the certificate along with
     *                     {@param friendlyName}.
     * @param friendlyName a string of the friendly name used for finding the same name in
     *                     subjectAltName section of the certificate.
     * @return boolean true if friendlyName shows up as one of the identities in the cert
     */
    public boolean validateProvider(Locale locale,
            String friendlyName) {

        if (locale == null || TextUtils.isEmpty(friendlyName)) {
            return false;
        }

        for (Pair<Locale, String> identity : ServiceProviderVerifier.getProviderNames(
                mTrustManager.getProviderCert())) {
            if (identity.first == null) continue;

            // Compare the language code for ISO-639.
            if (identity.first.getISO3Language().equals(locale.getISO3Language()) &&
                    TextUtils.equals(identity.second, friendlyName)) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "OSU certificate is valid for "
                            + identity.first.getISO3Language() + "/" + identity.second);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * The helper method to exchange a SOAP message.
     *
     * @param soapEnvelope the soap message to be sent.
     * @return {@code true} if {@link Network} is valid and {@code soapEnvelope} is not {@code
     * null}, {@code false} otherwise.
     */
    public boolean exchangeSoapMessage(@NonNull SoapSerializationEnvelope soapEnvelope) {
        if (mNetwork == null) {
            Log.e(TAG, "Network is not established");
            return false;
        }

        if (mUrlConnection == null) {
            Log.e(TAG, "Server certificate is not validated");
            return false;
        }

        if (soapEnvelope == null) {
            Log.e(TAG, "soapEnvelope is null");
            return false;
        }

        mHandler.post(() -> performSoapMessageExchange(soapEnvelope));
        return true;
    }

    /**
     * Retrieves Trust Root CA certificates for AAA, Remediation, Policy Server
     *
     * @param trustCertsInfo trust cert information for each type (AAA,Remediation and Policy).
     *                       {@code Key} is the cert type.
     *                       {@code Value} is the map that has a key for certUrl and a value for
     *                       fingerprint of the certificate.
     * @return {@code true} if {@link Network} is valid and {@code trustCertsInfo} is not {@code
     * null}, {@code false} otherwise.
     */
    public boolean retrieveTrustRootCerts(
            @NonNull Map<Integer, Map<String, byte[]>> trustCertsInfo) {
        if (mNetwork == null) {
            Log.e(TAG, "Network is not established");
            return false;
        }

        if (mUrlConnection == null) {
            Log.e(TAG, "Server certificate is not validated");
            return false;
        }

        if (trustCertsInfo == null || trustCertsInfo.isEmpty()) {
            Log.e(TAG, "TrustCertsInfo is not valid");
            return false;
        }
        mHandler.post(() -> performRetrievingTrustRootCerts(trustCertsInfo));
        return true;
    }

    private void performTlsConnection(URL url, Network network) {
        mNetwork = network;
        mUrl = url;

        HttpsURLConnection urlConnection;
        try {
            urlConnection = (HttpsURLConnection) mNetwork.openConnection(mUrl);
            urlConnection.setSSLSocketFactory(mSocketFactory);
            urlConnection.setConnectTimeout(HttpsServiceConnection.DEFAULT_TIMEOUT_MS);
            urlConnection.setReadTimeout(HttpsServiceConnection.DEFAULT_TIMEOUT_MS);
            urlConnection.connect();
        } catch (IOException e) {
            Log.e(TAG, "Unable to establish a URL connection: " + e);
            if (mOsuServerCallbacks != null) {
                mOsuServerCallbacks.onServerConnectionStatus(mOsuServerCallbacks.getSessionId(),
                        false);
            }
            return;
        }
        mUrlConnection = urlConnection;
        if (mOsuServerCallbacks != null) {
            mOsuServerCallbacks.onServerConnectionStatus(mOsuServerCallbacks.getSessionId(), true);
        }
    }

    private void performSoapMessageExchange(@NonNull SoapSerializationEnvelope soapEnvelope) {
        if (mServiceConnection != null) {
            mServiceConnection.disconnect();
        }

        mServiceConnection = getServiceConnection(mUrl, mNetwork);
        if (mServiceConnection == null) {
            Log.e(TAG, "ServiceConnection for https is null");
            if (mOsuServerCallbacks != null) {
                mOsuServerCallbacks.onReceivedSoapMessage(mOsuServerCallbacks.getSessionId(), null);
            }
            return;
        }

        SppResponseMessage sppResponse;
        try {
            // Sending the SOAP message
            mHttpsTransport.call("", soapEnvelope);
            Object response = soapEnvelope.bodyIn;
            if (response == null) {
                Log.e(TAG, "SoapObject is null");
                if (mOsuServerCallbacks != null) {
                    mOsuServerCallbacks.onReceivedSoapMessage(mOsuServerCallbacks.getSessionId(),
                            null);
                }
                return;
            }
            if (!(response instanceof SoapObject)) {
                Log.e(TAG, "Not a SoapObject instance");
                if (mOsuServerCallbacks != null) {
                    mOsuServerCallbacks.onReceivedSoapMessage(mOsuServerCallbacks.getSessionId(),
                            null);
                }
                return;
            }
            SoapObject soapResponse = (SoapObject) response;
            if (mVerboseLoggingEnabled) {
                for (int i = 0; i < soapResponse.getAttributeCount(); i++) {
                    AttributeInfo attributeInfo = new AttributeInfo();
                    soapResponse.getAttributeInfo(i, attributeInfo);
                    Log.v(TAG, "Attribute : " + attributeInfo.toString());
                }
                Log.v(TAG, "response : " + soapResponse.toString());
            }

            // Get the parsed SOAP SPP Response message
            sppResponse = SoapParser.getResponse(soapResponse);
        } catch (Exception e) {
            if (e instanceof SSLHandshakeException) {
                Log.e(TAG, "Failed to make TLS connection: " + e);
            } else {
                Log.e(TAG, "Failed to exchange the SOAP message: " + e);
            }
            if (mOsuServerCallbacks != null) {
                mOsuServerCallbacks.onReceivedSoapMessage(mOsuServerCallbacks.getSessionId(), null);
            }
            return;
        } finally {
            mServiceConnection.disconnect();
            mServiceConnection = null;
        }
        if (mOsuServerCallbacks != null) {
            mOsuServerCallbacks.onReceivedSoapMessage(mOsuServerCallbacks.getSessionId(),
                    sppResponse);
        }
    }

    private void performRetrievingTrustRootCerts(
            @NonNull Map<Integer, Map<String, byte[]>> trustCertsInfo) {
        // Key: CERT_TYPE (AAA, REMEDIATION, POLICY), Value: a list of X509Certificate retrieved for
        // the type.
        Map<Integer, List<X509Certificate>> trustRootCertificates = new HashMap<>();

        for (Map.Entry<Integer, Map<String, byte[]>> certInfoPerType : trustCertsInfo.entrySet()) {
            List<X509Certificate> certificates = new ArrayList<>();

            // Iterates certInfo to get a cert with a url provided in certInfo.key().
            // Key: Cert url, Value: SHA-256 hash bytes to match the fingerprint of a
            // certificates retrieved from server.
            for (Map.Entry<String, byte[]> certInfo : certInfoPerType.getValue().entrySet()) {
                if (certInfo.getValue() == null) {
                    // clear all of retrieved CA certs so that PasspointProvisioner aborts
                    // current flow.
                    trustRootCertificates.clear();
                    break;
                }
                X509Certificate certificate = getCert(certInfo.getKey());

                if (certificate == null || !ServiceProviderVerifier.verifyCertFingerprint(
                        certificate, certInfo.getValue())) {
                    // If any failure happens, clear all of retrieved CA certs so that
                    // PasspointProvisioner aborts current flow.
                    trustRootCertificates.clear();
                    break;
                }
                certificates.add(certificate);
            }
            if (!certificates.isEmpty()) {
                trustRootCertificates.put(certInfoPerType.getKey(), certificates);
            }
        }

        if (mOsuServerCallbacks != null) {
            // If it passes empty trustRootCertificates here, PasspointProvisioner will abort
            // current flow because it indicates that client device doesn't get any trust root
            // certificates from server.
            mOsuServerCallbacks.onReceivedTrustRootCertificates(mOsuServerCallbacks.getSessionId(),
                    trustRootCertificates);
        }
    }

    /**
     * Retrieves a X.509 Certificate from server.
     *
     * @param certUrl url to retrieve a X.509 Certificate
     * @return {@link X509Certificate} in success, {@code null} otherwise.
     */
    private X509Certificate getCert(@NonNull String certUrl) {
        if (certUrl == null || !certUrl.toLowerCase(Locale.US).startsWith("https://")) {
            Log.e(TAG, "invalid certUrl provided");
            return null;
        }

        try {
            URL serverUrl = new URL(certUrl);
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            if (mServiceConnection != null) {
                mServiceConnection.disconnect();
            }
            mServiceConnection = getServiceConnection(serverUrl, mNetwork);
            if (mServiceConnection == null) {
                return null;
            }
            mServiceConnection.setRequestMethod("GET");
            mServiceConnection.setRequestProperty("Accept-Encoding", "gzip");

            if (mServiceConnection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "The response code of the HTTPS GET to " + certUrl
                        + " is not OK, but " + mServiceConnection.getResponseCode());
                return null;
            }
            boolean bPkcs7 = false;
            boolean bBase64 = false;
            List<HeaderProperty> properties = mServiceConnection.getResponseProperties();
            for (HeaderProperty property : properties) {
                if (property == null || property.getKey() == null || property.getValue() == null) {
                    continue;
                }
                if (property.getKey().equalsIgnoreCase("Content-Type")) {
                    if (property.getValue().equals("application/pkcs7-mime")
                            || property.getValue().equals("application/x-x509-ca-cert")) {
                        // application/x-x509-ca-cert : File content is a DER encoded X.509
                        // certificate
                        if (mVerboseLoggingEnabled) {
                            Log.v(TAG, "a certificate found in a HTTPS response from " + certUrl);
                        }

                        // ca cert
                        bPkcs7 = true;
                    }
                }
                if (property.getKey().equalsIgnoreCase("Content-Transfer-Encoding")
                        && property.getValue().equalsIgnoreCase("base64")) {
                    if (mVerboseLoggingEnabled) {
                        Log.v(TAG,
                                "base64 encoding content in a HTTP response from " + certUrl);
                    }
                    bBase64 = true;
                }
            }
            if (!bPkcs7) {
                Log.e(TAG, "no X509Certificate found in the HTTPS response");
                return null;
            }
            InputStream in = mServiceConnection.openInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (true) {
                int rd = in.read(buf, 0, 8192);
                if (rd == -1) {
                    break;
                }
                bos.write(buf, 0, rd);
            }
            in.close();
            bos.flush();
            byte[] byteArray = bos.toByteArray();
            if (bBase64) {
                String s = new String(byteArray);
                byteArray = android.util.Base64.decode(s, android.util.Base64.DEFAULT);
            }

            X509Certificate certificate = (X509Certificate) certFactory.generateCertificate(
                    new ByteArrayInputStream(byteArray));
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "cert : " + certificate.getSubjectDN());
            }
            return certificate;
        } catch (IOException e) {
            Log.e(TAG, "Failed to get the data from " + certUrl + ": " + e);
        } catch (CertificateException e) {
            Log.e(TAG, "Failed to get instance for CertificateFactory " + e);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to decode the data: " + e);
        } finally {
            mServiceConnection.disconnect();
            mServiceConnection = null;
        }
        return null;
    }

    /**
     * Gets the HTTPS service connection used for SOAP message exchange.
     *
     * @return {@link HttpsServiceConnection}
     */
    private HttpsServiceConnection getServiceConnection(@NonNull URL url,
            @NonNull Network network) {
        HttpsServiceConnection serviceConnection;
        try {
            // Creates new HTTPS connection.
            mHttpsTransport = HttpsTransport.createInstance(network, url);
            serviceConnection = (HttpsServiceConnection) mHttpsTransport.getServiceConnection();
            if (serviceConnection != null) {
                serviceConnection.setSSLSocketFactory(mSocketFactory);
            }
        } catch (IOException e) {
            Log.e(TAG, "Unable to establish a URL connection");
            return null;
        }
        return serviceConnection;
    }

    private void cleanupConnection() {
        if (mUrlConnection != null) {
            mUrlConnection.disconnect();
            mUrlConnection = null;
        }
        if (mServiceConnection != null) {
            mServiceConnection.disconnect();
            mServiceConnection = null;
        }
    }

    /**
     * Cleans up
     */
    public void cleanup() {
        mHandler.post(() -> cleanupConnection());
    }

    private class WFATrustManager implements X509TrustManager {
        private TrustManagerImpl mDelegate;
        private List<X509Certificate> mServerCerts;

        WFATrustManager(TrustManagerImpl trustManagerImpl) {
            mDelegate = trustManagerImpl;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "checkClientTrusted " + authType);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "checkServerTrusted " + authType);
            }
            boolean certsValid = false;
            try {
                // Perform certificate path validation and get validated certs
                mServerCerts = mDelegate.getTrustedChainForServer(chain, authType,
                        (SSLSocket) null);
                certsValid = true;
            } catch (CertificateException e) {
                Log.e(TAG, "Unable to validate certs " + e);
                if (mVerboseLoggingEnabled) {
                    e.printStackTrace();
                }
            }
            if (mOsuServerCallbacks != null) {
                mOsuServerCallbacks.onServerValidationStatus(mOsuServerCallbacks.getSessionId(),
                        certsValid);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "getAcceptedIssuers ");
            }
            return null;
        }

        /**
         * Returns the OSU certificate matching the FQDN of the OSU server
         *
         * @return {@link X509Certificate} OSU certificate matching FQDN of OSU server
         */
        public X509Certificate getProviderCert() {
            if (mServerCerts == null || mServerCerts.size() <= 0) {
                return null;
            }
            X509Certificate providerCert = null;
            String fqdn = mUrl.getHost();
            try {
                for (X509Certificate certificate : mServerCerts) {
                    Collection<List<?>> col = certificate.getSubjectAlternativeNames();
                    if (col == null) {
                        continue;
                    }
                    for (List<?> name : col) {
                        if (name == null) {
                            continue;
                        }
                        if (name.size() >= DNS_NAME
                                && name.get(0).getClass() == Integer.class
                                && name.get(1).toString().equals(fqdn)) {
                            providerCert = certificate;
                            if (mVerboseLoggingEnabled) {
                                Log.v(TAG, "OsuCert found");
                            }
                            break;
                        }
                    }
                }
            } catch (CertificateParsingException e) {
                Log.e(TAG, "Unable to match certificate to " + fqdn);
                if (mVerboseLoggingEnabled) {
                    e.printStackTrace();
                }
            }
            return providerCert;
        }
    }
}

