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

import android.os.Environment;
import android.util.Log;

import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Set;

/**
 * WFA Keystore
 */
public class WfaKeyStore {
    private static final String TAG = "WfaKeyStore";
    // The WFA Root certs are checked in to /system/ca-certificates/cacerts_wfa
    // The location on device is configured in the corresponding Android.mk
    private static final String DEFAULT_WFA_CERT_DIR =
            Environment.getRootDirectory() + "/etc/security/cacerts_wfa";

    private boolean mVerboseLoggingEnabled = false;
    private KeyStore mKeyStore = null;

    /**
     * Loads the keystore with root certificates
     */
    public void load() {
        if (mKeyStore != null) {
            return;
        }
        int index = 0;
        try {
            mKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            mKeyStore.load(null, null);
            Set<X509Certificate> certs = WfaCertBuilder.loadCertsFromDisk(DEFAULT_WFA_CERT_DIR);
            for (X509Certificate cert : certs) {
                mKeyStore.setCertificateEntry(String.format("%d", index), cert);
                index++;
            }
            if (index <= 0) {
                Log.wtf(TAG, "No certs loaded");
            }
        } catch (KeyStoreException | CertificateException | NoSuchAlgorithmException
                | IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Returns the underlying keystore object
     * @return KeyStore Underlying keystore object created
     */
    public KeyStore get() {
        return mKeyStore;
    }
}
