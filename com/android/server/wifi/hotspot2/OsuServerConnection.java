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

import android.net.Network;
import android.util.Log;

import com.android.org.conscrypt.TrustManagerImpl;

import java.io.IOException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Provides methods to interface with the OSU server
 */
public class OsuServerConnection {
    private static final String TAG = "OsuServerConnection";

    private static final int DNS_NAME = 2;

    private SSLSocketFactory mSocketFactory;
    private URL mUrl;
    private Network mNetwork;
    private WFATrustManager mTrustManager;
    private HttpsURLConnection mUrlConnection = null;
    private PasspointProvisioner.OsuServerCallbacks mOsuServerCallbacks;
    private boolean mSetupComplete = false;
    private boolean mVerboseLoggingEnabled = false;
    /**
     * Sets up callback for event
     * @param callbacks OsuServerCallbacks to be invoked for server related events
     */
    public void setEventCallback(PasspointProvisioner.OsuServerCallbacks callbacks) {
        mOsuServerCallbacks = callbacks;
    }

    /**
     * Initialize socket factory for server connection using HTTPS
     * @param tlsContext SSLContext that will be used for HTTPS connection
     * @param trustManagerImpl TrustManagerImpl delegate to validate certs
     */
    public void init(SSLContext tlsContext, TrustManagerImpl trustManagerImpl) {
        if (tlsContext == null) {
            return;
        }
        try {
            mTrustManager = new WFATrustManager(trustManagerImpl);
            tlsContext.init(null, new TrustManager[] { mTrustManager }, null);
            mSocketFactory = tlsContext.getSocketFactory();
        } catch (KeyManagementException e) {
            Log.w(TAG, "Initialization failed");
            e.printStackTrace();
            return;
        }
        mSetupComplete = true;
    }

    /**
     * Provides the capability to run OSU server validation
     * @return boolean true if capability available
     */
    public boolean canValidateServer() {
        return mSetupComplete;
    }

    /**
     * Enables verbose logging
     * @param verbose a value greater than zero enables verbose logging
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = verbose > 0 ? true : false;
    }

    /**
     * Connect to the OSU server
     * @param url Osu Server's URL
     * @param network current network connection
     * @return boolean value, true if connection was successful
     *
     * Relies on the caller to ensure that the capability to validate the OSU
     * Server is available.
     */
    public boolean connect(URL url, Network network) {
        mNetwork = network;
        mUrl = url;
        HttpsURLConnection urlConnection;
        try {
            urlConnection = (HttpsURLConnection) mNetwork.openConnection(mUrl);
            urlConnection.setSSLSocketFactory(mSocketFactory);
            urlConnection.connect();
        } catch (IOException e) {
            Log.e(TAG, "Unable to establish a URL connection");
            e.printStackTrace();
            return false;
        }
        mUrlConnection = urlConnection;
        return true;
    }

    /**
     * Validate the OSU server
     */
    public boolean validateProvider(String friendlyName) {
        X509Certificate providerCert = mTrustManager.getProviderCert();
        // TODO : Validate friendly name
        if (providerCert == null) {
            Log.e(TAG, "Provider doesn't have valid certs");
            return false;
        }
        return true;
    }

    /**
     * Clean up
     */
    public void cleanup() {
        mUrlConnection.disconnect();
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
                mOsuServerCallbacks.onServerValidationStatus(
                        mOsuServerCallbacks.getSessionId(), certsValid);
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
         * @return X509Certificate OSU certificate matching FQDN of OSU server
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

