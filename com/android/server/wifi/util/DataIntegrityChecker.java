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

package com.android.server.wifi.util;

import android.annotation.NonNull;
import android.os.SystemProperties;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.DigestException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Tools to provide integrity checking of byte arrays based on NIAP Common Criteria Protection
 * Profile <a href="https://www.niap-ccevs.org/MMO/PP/-417-/#FCS_STG_EXT.3.1">FCS_STG_EXT.3.1</a>.
 */
public class DataIntegrityChecker {
    private static final String TAG = "DataIntegrityChecker";

    private static final String FILE_SUFFIX = ".encrypted-checksum";
    private static final String ALIAS_SUFFIX = ".data-integrity-checker-key";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final int GCM_TAG_LENGTH = 128;
    private static final String KEY_STORE = "AndroidKeyStore";

    /**
     * When KEYSTORE_FAILURE_RETURN_VALUE is true, all cryptographic operation failures will not
     * enforce security and {@link #isOk(byte[])} always return true.
     */
    private static final boolean KEYSTORE_FAILURE_RETURN_VALUE = true;

    private final File mIntegrityFile;

    /**
     * Construct a new integrity checker to update and check if/when a data file was altered
     * outside expected conditions.
     *
     * @param integrityFilename The {@link File} path prefix for where the integrity data is stored.
     *                          A file will be created in the name of integrityFile with the suffix
     *                          {@link DataIntegrityChecker#FILE_SUFFIX} We recommend using the same
     *                          path as the file for which integrity is performed on.
     * @throws NullPointerException When integrity file is null or the empty string.
     */
    public DataIntegrityChecker(@NonNull String integrityFilename) {
        if (TextUtils.isEmpty(integrityFilename)) {
            throw new NullPointerException("integrityFilename must not be null or the empty "
                    + "string");
        }
        mIntegrityFile = new File(integrityFilename + FILE_SUFFIX);
    }

    /**
     * Computes a digest of a byte array, encrypt it, and store the result
     *
     * Call this method immediately before storing the byte array
     *
     * @param data The data desired to ensure integrity
     */
    public void update(byte[] data) {
        if (data == null || data.length < 1) {
            reportException(new Exception("No data to update"), "No data to update.");
            return;
        }
        byte[] digest = getDigest(data);
        if (digest == null || digest.length < 1) {
            return;
        }
        String alias = mIntegrityFile.getName() + ALIAS_SUFFIX;
        EncryptedData integrityData = encrypt(digest, alias);
        if (integrityData != null) {
            writeIntegrityData(integrityData, mIntegrityFile);
        } else {
            reportException(new Exception("integrityData null upon update"),
                    "integrityData null upon update");
        }
    }

    /**
     * Check the integrity of a given byte array
     *
     * Call this method immediately before trusting the byte array. This method will return false
     * when the byte array was altered since the last {@link #update(byte[])}
     * call, when {@link #update(byte[])} has never been called, or if there is
     * an underlying issue with the cryptographic functions or the key store.
     *
     * @param data The data to check if its been altered
     * @throws DigestException The integrity mIntegrityFile cannot be read. Ensure
     *      {@link #isOk(byte[])} is called after {@link #update(byte[])}. Otherwise, consider the
     *      result vacuously true and immediately call {@link #update(byte[])}.
     * @return true if the data was not altered since {@link #update(byte[])} was last called
     */
    public boolean isOk(byte[] data) throws DigestException {
        if (data == null || data.length < 1) {
            return KEYSTORE_FAILURE_RETURN_VALUE;
        }
        byte[] currentDigest = getDigest(data);
        if (currentDigest == null || currentDigest.length < 1) {
            return KEYSTORE_FAILURE_RETURN_VALUE;
        }

        EncryptedData encryptedData = null;

        try {
            encryptedData = readIntegrityData(mIntegrityFile);
        } catch (IOException e) {
            reportException(e, "readIntegrityData had an IO exception");
            return KEYSTORE_FAILURE_RETURN_VALUE;
        } catch (ClassNotFoundException e) {
            reportException(e, "readIntegrityData could not find the class EncryptedData");
            return KEYSTORE_FAILURE_RETURN_VALUE;
        }

        if (encryptedData == null) {
            // File not found is not considered to be an error.
            throw new DigestException("No stored digest is available to compare.");
        }
        byte[] storedDigest = decrypt(encryptedData);
        if (storedDigest == null) {
            return KEYSTORE_FAILURE_RETURN_VALUE;
        }
        return constantTimeEquals(storedDigest, currentDigest);
    }

    private byte[] getDigest(byte[] data) {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM).digest(data);
        } catch (NoSuchAlgorithmException e) {
            reportException(e, "getDigest could not find algorithm: " + DIGEST_ALGORITHM);
            return null;
        }
    }

    private EncryptedData encrypt(byte[] data, String keyAlias) {
        EncryptedData encryptedData = null;
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            SecretKey secretKeyReference = getOrCreateSecretKey(keyAlias);
            if (secretKeyReference != null) {
                cipher.init(Cipher.ENCRYPT_MODE, secretKeyReference);
                encryptedData = new EncryptedData(cipher.doFinal(data), cipher.getIV(), keyAlias);
            } else {
                reportException(new Exception("secretKeyReference is null."),
                        "secretKeyReference is null.");
            }
        } catch (NoSuchAlgorithmException e) {
            reportException(e, "encrypt could not find the algorithm: " + CIPHER_ALGORITHM);
        } catch (NoSuchPaddingException e) {
            reportException(e, "encrypt had a padding exception");
        } catch (InvalidKeyException e) {
            reportException(e, "encrypt received an invalid key");
        } catch (BadPaddingException e) {
            reportException(e, "encrypt had a padding problem");
        } catch (IllegalBlockSizeException e) {
            reportException(e, "encrypt had an illegal block size");
        }
        return encryptedData;
    }

    private byte[] decrypt(EncryptedData encryptedData) {
        byte[] decryptedData = null;
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, encryptedData.getIv());
            SecretKey secretKeyReference = getOrCreateSecretKey(encryptedData.getKeyAlias());
            if (secretKeyReference != null) {
                cipher.init(Cipher.DECRYPT_MODE, secretKeyReference, spec);
                decryptedData = cipher.doFinal(encryptedData.getEncryptedData());
            }
        } catch (NoSuchAlgorithmException e) {
            reportException(e, "decrypt could not find cipher algorithm " + CIPHER_ALGORITHM);
        } catch (NoSuchPaddingException e) {
            reportException(e, "decrypt could not find padding algorithm");
        } catch (IllegalBlockSizeException e) {
            reportException(e, "decrypt had a illegal block size");
        } catch (BadPaddingException e) {
            reportException(e, "decrypt had bad padding");
        } catch (InvalidKeyException e) {
            reportException(e, "decrypt had an invalid key");
        } catch (InvalidAlgorithmParameterException e) {
            reportException(e, "decrypt had an invalid algorithm parameter");
        }
        return decryptedData;
    }

    private SecretKey getOrCreateSecretKey(String keyAlias) {
        SecretKey secretKey = null;
        try {
            KeyStore keyStore = KeyStore.getInstance(KEY_STORE);
            keyStore.load(null);
            if (keyStore.containsAlias(keyAlias)) { // The key exists in key store. Get the key.
                KeyStore.SecretKeyEntry secretKeyEntry = (KeyStore.SecretKeyEntry) keyStore
                        .getEntry(keyAlias, null);
                if (secretKeyEntry != null) {
                    secretKey = secretKeyEntry.getSecretKey();
                } else {
                    reportException(new Exception("keystore contains the alias and the secret key "
                            + "entry was null"),
                            "keystore contains the alias and the secret key entry was null");
                }
            } else { // The key does not exist in key store. Create the key and store it.
                KeyGenerator keyGenerator = KeyGenerator
                        .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE);

                KeyGenParameterSpec keyGenParameterSpec = new KeyGenParameterSpec.Builder(keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build();

                keyGenerator.init(keyGenParameterSpec);
                secretKey = keyGenerator.generateKey();
            }
        } catch (CertificateException e) {
            reportException(e, "getOrCreateSecretKey had a certificate exception.");
        } catch (InvalidAlgorithmParameterException e) {
            reportException(e, "getOrCreateSecretKey had an invalid algorithm parameter");
        } catch (IOException e) {
            reportException(e, "getOrCreateSecretKey had an IO exception.");
        } catch (KeyStoreException e) {
            reportException(e, "getOrCreateSecretKey cannot find the keystore: " + KEY_STORE);
        } catch (NoSuchAlgorithmException e) {
            reportException(e, "getOrCreateSecretKey cannot find algorithm");
        } catch (NoSuchProviderException e) {
            reportException(e, "getOrCreateSecretKey cannot find crypto provider");
        } catch (UnrecoverableEntryException e) {
            reportException(e, "getOrCreateSecretKey had an unrecoverable entry exception.");
        }
        return secretKey;
    }

    private void writeIntegrityData(EncryptedData encryptedData, File file) {
        try (FileOutputStream fos = new FileOutputStream(file);
             ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(encryptedData);
        } catch (FileNotFoundException e) {
            reportException(e, "writeIntegrityData could not find the integrity file");
        } catch (IOException e) {
            reportException(e, "writeIntegrityData had an IO exception");
        }
    }

    private EncryptedData readIntegrityData(File file) throws IOException, ClassNotFoundException  {
        try (FileInputStream fis = new FileInputStream(file);
             ObjectInputStream ois = new ObjectInputStream(fis)) {
            return (EncryptedData) ois.readObject();
        } catch (FileNotFoundException e) {
            // File not found, this is not considered to be a real error. The file will be created
            // by the system next time the data file is written. Note that it is not possible for
            // non system user to delete or modify the file.
            Log.w(TAG, "readIntegrityData could not find integrity file");
        }
        return null;
    }

    private boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null && b == null) {
            return true;
        }

        if (a == null || b == null || a.length != b.length) {
            return false;
        }

        byte differenceAccumulator = 0;
        for (int i = 0; i < a.length; ++i) {
            differenceAccumulator |= a[i] ^ b[i];
        }
        return (differenceAccumulator == 0);
    }

    /* TODO(b/128526030): Remove this error reporting code upon resolving the bug. */
    private static final boolean REQUEST_BUG_REPORT = false;
    private void reportException(Exception exception, String error) {
        Log.wtf(TAG, "An irrecoverable key store error was encountered: " + error);
        if (REQUEST_BUG_REPORT) {
            SystemProperties.set("dumpstate.options", "bugreportwifi");
            SystemProperties.set("ctl.start", "bugreport");
        }
    }
}
