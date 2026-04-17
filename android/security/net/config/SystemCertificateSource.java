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

package android.security.net.config;

import android.os.Environment;
import android.os.UserHandle;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link CertificateSource} based on the system trusted CA store.
 * @hide
 */
public final class SystemCertificateSource extends DirectoryCertificateSource {
    private static class NoPreloadHolder {
        private static final SystemCertificateSource INSTANCE = new SystemCertificateSource();
    }

    private final File mUserRemovedCaDir;

    private SystemCertificateSource() {
        super(getDirectory());
        // TODO(b/424086802): migrate to CE or DE directories.
        File configDir =
                new File(System.getenv("ANDROID_DATA") + "/misc/user/" + UserHandle.myUserId());
        mUserRemovedCaDir = new File(configDir, "cacerts-removed");
    }

    private static File getDirectory() {
        if ((System.getProperty("system.certs.enabled") != null)
            && (System.getProperty("system.certs.enabled")).equals("true")) {
            return new File(System.getenv("ANDROID_ROOT") + "/etc/security/cacerts");
        }
        File updatable_dir = new File("/apex/com.android.conscrypt/cacerts");
        if (updatable_dir.exists()) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(updatable_dir.toPath())) {
                if (stream.iterator().hasNext()) {
                    return updatable_dir;
                }
            } catch (IOException e) {
                // Fallthrough and use the old path.
            }
        }
        return new File(System.getenv("ANDROID_ROOT") + "/etc/security/cacerts");
    }

    public static SystemCertificateSource getInstance() {
        return NoPreloadHolder.INSTANCE;
    }

    @Override
    protected boolean isCertMarkedAsRemoved(String caFile) {
        return new File(mUserRemovedCaDir, caFile).exists();
    }
}
