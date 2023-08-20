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

package com.android.server.security;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.security.IFileIntegrityService;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.security.VerityUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;

/**
 * A {@link SystemService} that provides file integrity related operations.
 * @hide
 */
public class FileIntegrityService extends SystemService {
    private static final String TAG = "FileIntegrityService";

    /** The maximum size of signature file.  This is just to avoid potential abuse. */
    private static final int MAX_SIGNATURE_FILE_SIZE_BYTES = 8192;

    private static CertificateFactory sCertFactory;

    @GuardedBy("mTrustedCertificates")
    private final ArrayList<X509Certificate> mTrustedCertificates =
            new ArrayList<X509Certificate>();

    /** Gets the instance of the service */
    public static FileIntegrityService getService() {
        return LocalServices.getService(FileIntegrityService.class);
    }

    private final IBinder mService = new IFileIntegrityService.Stub() {
        @Override
        public boolean isApkVeritySupported() {
            return VerityUtils.isFsVeritySupported();
        }

        @Override
        public boolean isAppSourceCertificateTrusted(@Nullable byte[] certificateBytes,
                @NonNull String packageName) {
            checkCallerPermission(packageName);

            try {
                if (!VerityUtils.isFsVeritySupported()) {
                    return false;
                }
                if (certificateBytes == null) {
                    Slog.w(TAG, "Received a null certificate");
                    return false;
                }
                synchronized (mTrustedCertificates) {
                    return mTrustedCertificates.contains(toCertificate(certificateBytes));
                }
            } catch (CertificateException e) {
                Slog.e(TAG, "Failed to convert the certificate: " + e);
                return false;
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            new FileIntegrityServiceShellCommand()
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

        private void checkCallerPermission(String packageName) {
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(callingUid);
            final PackageManagerInternal packageManager =
                    LocalServices.getService(PackageManagerInternal.class);
            final int packageUid = packageManager.getPackageUid(
                    packageName, 0 /*flag*/, callingUserId);
            if (callingUid != packageUid) {
                throw new SecurityException(
                        "Calling uid " + callingUid + " does not own package " + packageName);
            }

            if (getContext().checkCallingPermission(android.Manifest.permission.INSTALL_PACKAGES)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }

            final AppOpsManager appOpsManager = getContext().getSystemService(AppOpsManager.class);
            final int mode = appOpsManager.checkOpNoThrow(
                    AppOpsManager.OP_REQUEST_INSTALL_PACKAGES, callingUid, packageName);
            if (mode != AppOpsManager.MODE_ALLOWED) {
                throw new SecurityException(
                        "Caller should have INSTALL_PACKAGES or REQUEST_INSTALL_PACKAGES");
            }
        }
    };

    public FileIntegrityService(final Context context) {
        super(context);
        try {
            sCertFactory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            Slog.wtf(TAG, "Cannot get an instance of X.509 certificate factory");
        }
        LocalServices.addService(FileIntegrityService.class, this);
    }

    @Override
    public void onStart() {
        loadAllCertificates();
        publishBinderService(Context.FILE_INTEGRITY_SERVICE, mService);
    }

    /**
     * Returns whether the signature over the file's fs-verity digest can be verified by one of the
     * known certiticates.
     */
    public boolean verifyPkcs7DetachedSignature(String signaturePath, String filePath)
            throws IOException {
        if (Files.size(Paths.get(signaturePath)) > MAX_SIGNATURE_FILE_SIZE_BYTES) {
            throw new SecurityException("Signature file is unexpectedly large: "
                    + signaturePath);
        }
        byte[] signatureBytes = Files.readAllBytes(Paths.get(signaturePath));
        byte[] digest = VerityUtils.getFsverityDigest(filePath);
        synchronized (mTrustedCertificates) {
            for (var cert : mTrustedCertificates) {
                try {
                    byte[] derEncoded = cert.getEncoded();
                    if (VerityUtils.verifyPkcs7DetachedSignature(signatureBytes, digest,
                            new ByteArrayInputStream(derEncoded))) {
                        return true;
                    }
                } catch (CertificateEncodingException e) {
                    Slog.w(TAG, "Ignoring ill-formed certificate: " + e);
                }
            }
        }
        return false;
    }

    private void loadAllCertificates() {
        // A better alternative to load certificates would be to read from .fs-verity kernel
        // keyring, which fsverity_init loads to during earlier boot time from the same sources
        // below. But since the read operation from keyring is not provided in kernel, we need to
        // duplicate the same loading logic here.

        // Load certificates trusted by the device manufacturer.
        // NB: Directories need to be synced with system/security/fsverity_init/fsverity_init.cpp.
        final String relativeDir = "etc/security/fsverity";
        loadCertificatesFromDirectory(Environment.getRootDirectory().toPath()
                .resolve(relativeDir));
        loadCertificatesFromDirectory(Environment.getProductDirectory().toPath()
                .resolve(relativeDir));
    }

    private void loadCertificatesFromDirectory(Path path) {
        try {
            File[] files = path.toFile().listFiles();
            if (files == null) {
                return;
            }

            for (File cert : files) {
                byte[] certificateBytes = Files.readAllBytes(cert.toPath());
                collectCertificate(certificateBytes);
            }
        } catch (IOException e) {
            Slog.wtf(TAG, "Failed to load fs-verity certificate from " + path, e);
        }
    }

    /**
     * Tries to convert {@code bytes} into an X.509 certificate and store in memory.
     * Errors need to be surpressed in order fo the next certificates to still be collected.
     */
    private void collectCertificate(@NonNull byte[] bytes) {
        try {
            synchronized (mTrustedCertificates) {
                mTrustedCertificates.add(toCertificate(bytes));
            }
        } catch (CertificateException e) {
            Slog.e(TAG, "Invalid certificate, ignored: " + e);
        }
    }

    /**
     * Converts byte array into one X.509 certificate. If multiple certificate is defined, ignore
     * the rest. The rational is to make it harder to smuggle.
     */
    @NonNull
    private static X509Certificate toCertificate(@NonNull byte[] bytes)
            throws CertificateException {
        Certificate certificate = sCertFactory.generateCertificate(new ByteArrayInputStream(bytes));
        if (!(certificate instanceof X509Certificate)) {
            throw new CertificateException("Expected to contain an X.509 certificate");
        }
        return (X509Certificate) certificate;
    }


    private class FileIntegrityServiceShellCommand extends ShellCommand {
        @Override
        public int onCommand(String cmd) {
            if (!Build.IS_DEBUGGABLE) {
                return -1;
            }
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }
            final PrintWriter pw = getOutPrintWriter();
            switch (cmd) {
                case "append-cert":
                    String nextArg = getNextArg();
                    if (nextArg == null) {
                        pw.println("Invalid argument");
                        pw.println("");
                        onHelp();
                        return -1;
                    }
                    ParcelFileDescriptor pfd = openFileForSystem(nextArg, "r");
                    if (pfd == null) {
                        pw.println("Cannot open the file");
                        return -1;
                    }
                    InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                    try {
                        collectCertificate(is.readAllBytes());
                    } catch (IOException e) {
                        pw.println("Failed to add certificate: " + e);
                        return -1;
                    }
                    pw.println("Certificate is added successfully");
                    return 0;

                case "remove-last-cert":
                    synchronized (mTrustedCertificates) {
                        if (mTrustedCertificates.size() == 0) {
                            pw.println("Certificate list is already empty");
                            return -1;
                        }
                        mTrustedCertificates.remove(mTrustedCertificates.size() - 1);
                    }
                    pw.println("Certificate is removed successfully");
                    return 0;
                default:
                    pw.println("Unknown action");
                    pw.println("");
                    onHelp();
            }
            return -1;
        }

        @Override
        public void onHelp() {
            final PrintWriter pw = getOutPrintWriter();
            pw.println("File integrity service commands:");
            pw.println("  help");
            pw.println("    Print this help text.");
            pw.println("  append-cert path/to/cert.der");
            pw.println("    Add the DER-encoded certificate (only in debug builds)");
            pw.println("  remove-last-cert");
            pw.println("    Remove the last certificate in the key list (only in debug builds)");
            pw.println("");
        }
    }
}
