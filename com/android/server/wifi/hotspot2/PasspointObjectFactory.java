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

import android.content.Context;
import android.net.wifi.hotspot2.PasspointConfiguration;

import com.android.org.conscrypt.TrustManagerImpl;
import com.android.server.wifi.Clock;
import com.android.server.wifi.SIMAccessor;
import com.android.server.wifi.WifiKeyStore;
import com.android.server.wifi.WifiNative;

import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.SSLContext;

/**
 * Factory class for creating Passpoint related objects. Useful for mocking object creations
 * in the unit tests.
 */
public class PasspointObjectFactory{
    /**
     * Create a PasspointEventHandler instance.
     *
     * @param wifiNative Instance of {@link WifiNative}
     * @param callbacks Instance of {@link PasspointEventHandler.Callbacks}
     * @return {@link PasspointEventHandler}
     */
    public PasspointEventHandler makePasspointEventHandler(WifiNative wifiNative,
            PasspointEventHandler.Callbacks callbacks) {
        return new PasspointEventHandler(wifiNative, callbacks);
    }

    /**
     * Create a PasspointProvider instance.
     *
     * @param keyStore Instance of {@link WifiKeyStore}
     * @param config Configuration for the provider
     * @param providerId Unique identifier for the provider
     * @return {@link PasspointProvider}
     */
    public PasspointProvider makePasspointProvider(PasspointConfiguration config,
            WifiKeyStore keyStore, SIMAccessor simAccessor, long providerId, int creatorUid) {
        return new PasspointProvider(config, keyStore, simAccessor, providerId, creatorUid);
    }

    /**
     * Create a {@link PasspointConfigStoreData} instance.
     *
     * @param keyStore Instance of {@link WifiKeyStore}
     * @param simAccessor Instance of {@link SIMAccessor}
     * @param dataSource Passpoint configuration data source
     * @return {@link PasspointConfigStoreData}
     */
    public PasspointConfigStoreData makePasspointConfigStoreData(WifiKeyStore keyStore,
            SIMAccessor simAccessor, PasspointConfigStoreData.DataSource dataSource) {
        return new PasspointConfigStoreData(keyStore, simAccessor, dataSource);
    }

    /**
     * Create a AnqpCache instance.
     *
     * @param clock Instance of {@link Clock}
     * @return {@link AnqpCache}
     */
    public AnqpCache makeAnqpCache(Clock clock) {
        return new AnqpCache(clock);
    }

    /**
     * Create an instance of {@link ANQPRequestManager}.
     *
     * @param handler Instance of {@link PasspointEventHandler}
     * @param clock Instance of {@link Clock}
     * @return {@link ANQPRequestManager}
     */
    public ANQPRequestManager makeANQPRequestManager(PasspointEventHandler handler, Clock clock) {
        return new ANQPRequestManager(handler, clock);
    }

    /**
     * Create an instance of {@link CertificateVerifier}.
     *
     * @return {@link CertificateVerifier}
     */
    public CertificateVerifier makeCertificateVerifier() {
        return new CertificateVerifier();
    }

    /**
     * Create an instance of {@link PasspointProvisioner}.
     *
     * @param context
     * @return {@link PasspointProvisioner}
     */
    public PasspointProvisioner makePasspointProvisioner(Context context) {
        return new PasspointProvisioner(context, this);
    }

    /**
     * Create an instance of {@link OsuNetworkConnection}.
     *
     * @param context
     * @return {@link OsuNetworkConnection}
     */
    public OsuNetworkConnection makeOsuNetworkConnection(Context context) {
        return new OsuNetworkConnection(context);
    }

    /**
     * Create an instance of {@link OsuServerConnection}.
     *
     * @return {@link OsuServerConnection}
     */
    public OsuServerConnection makeOsuServerConnection() {
        return new OsuServerConnection();
    }


    /**
     * Create an instance of {@link WfaKeyStore}.
     *
     * @return WfaKeyStore {@link WfaKeyStore}
     */
    public WfaKeyStore makeWfaKeyStore() {
        return new WfaKeyStore();
    }

    /**
     * Create an instance of {@link SSLContext}.
     *
     * @param tlsVersion String indicate TLS version
     * @return SSLContext an instance, corresponding to the TLS version
     */
    public SSLContext getSSLContext(String tlsVersion) {
        SSLContext tlsContext = null;
        try {
            tlsContext = SSLContext.getInstance(tlsVersion);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return tlsContext;
    }

    /**
     * Create an instance of {@link TrustManagerImpl}.
     *
     * @param ks KeyStore used to get root certs
     * @return TrustManagerImpl an instance for delegating root cert validation
     */
    public TrustManagerImpl getTrustManagerImpl(KeyStore ks) {
        return new TrustManagerImpl(ks);
    }
}
