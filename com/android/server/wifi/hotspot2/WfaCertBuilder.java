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

package com.android.server.wifi.hotspot2;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Set;

/**
 * Provides static method to build certificate set from cert files
 */
public class WfaCertBuilder {

    private static final String TAG = "PasspointWfaCertBuilder";

    /**
     * Returns a set of X509 Certificates from a set of WFA cert files
     * @param directory the location where the cert files are stored
     * @return Set<X509Certificate> certificates obtained from the files
     */
    public static Set<X509Certificate> loadCertsFromDisk(String directory) {
        Set<X509Certificate> certs = new HashSet<>();
        try {
            File certDir = new File(directory);
            File[] certFiles = certDir.listFiles();
            if (certFiles == null || certFiles.length <= 0) {
                return certs;
            }
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
            for (File certFile : certFiles) {
                FileInputStream fis = new FileInputStream(certFile);
                Certificate cert = certFactory.generateCertificate(fis);
                if (cert instanceof X509Certificate) {
                    certs.add((X509Certificate) cert);
                }
                fis.close();
            }
        } catch (CertificateException | IOException | SecurityException e) {
            Log.e(TAG, "Unable to read cert " + e.getMessage());
        }
        return certs;
    }
}
