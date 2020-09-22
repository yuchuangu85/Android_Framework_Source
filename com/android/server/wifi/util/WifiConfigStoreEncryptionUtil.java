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
import android.annotation.Nullable;
import android.os.Process;
import android.security.keystore.AndroidKeyStoreProvider;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Log;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.ProviderException;
import java.security.UnrecoverableEntryException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Tools to help encrypt/decrypt
 */
public class WifiConfigStoreEncryptionUtil {
    private static final String TAG = "WifiConfigStoreEncryptionUtil";

    private static final String ALIAS_SUFFIX = ".data-encryption-key";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int KEY_LENGTH = 256;
    private static final String KEY_STORE = "AndroidKeyStore";

    private final String mDataFileName;

    /**
     * Construct a new util to help {@link com.android.server.wifi.WifiConfigStore.StoreData}
     * modules to encrypt/decrypt credential data written/read from this config store file.
     *
     * @param dataFileName The full path of the data file.
     * @throws NullPointerException When data file is empty string.
     */
    public WifiConfigStoreEncryptionUtil(@NonNull String dataFileName) {
        if (TextUtils.isEmpty(dataFileName)) {
            throw new NullPointerException("dataFileName must not be null or the empty "
                    + "string");
        }
        mDataFileName = dataFileName;
    }

    private String getKeyAlias() {
        return mDataFileName + ALIAS_SUFFIX;
    }

    /**
     * Encrypt the provided data blob.
     *
     * @param data Data blob to be encrypted.
     * @return Instance of {@link EncryptedData} containing the encrypted info.
     */
    public @Nullable EncryptedData encrypt(byte[] data) {
        EncryptedData encryptedData = null;
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            SecretKey secretKeyReference = getOrCreateSecretKey(getKeyAlias());
            if (secretKeyReference != null) {
                cipher.init(Cipher.ENCRYPT_MODE, secretKeyReference);
                encryptedData = new EncryptedData(cipher.doFinal(data), cipher.getIV());
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

    /**
     * Decrypt the original data blob from the provided {@link EncryptedData}.
     *
     * @param encryptedData Instance of {@link EncryptedData} containing the encrypted info.
     * @return Original data blob that was encrypted.
     */
    public @Nullable byte[] decrypt(@NonNull EncryptedData encryptedData) {
        byte[] decryptedData = null;
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, encryptedData.getIv());
            SecretKey secretKeyReference = getOrCreateSecretKey(getKeyAlias());
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
            KeyStore keyStore = AndroidKeyStoreProvider.getKeyStoreForUid(Process.WIFI_UID);
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
                        .setKeySize(KEY_LENGTH)
                        .setUid(Process.WIFI_UID)
                        .build();

                keyGenerator.init(keyGenParameterSpec);
                secretKey = keyGenerator.generateKey();
            }
        } catch (InvalidAlgorithmParameterException e) {
            reportException(e, "getOrCreateSecretKey had an invalid algorithm parameter");
        } catch (KeyStoreException e) {
            reportException(e, "getOrCreateSecretKey cannot find the keystore: " + KEY_STORE);
        } catch (NoSuchAlgorithmException e) {
            reportException(e, "getOrCreateSecretKey cannot find algorithm");
        } catch (NoSuchProviderException e) {
            reportException(e, "getOrCreateSecretKey cannot find crypto provider");
        } catch (UnrecoverableEntryException e) {
            reportException(e, "getOrCreateSecretKey had an unrecoverable entry exception.");
        } catch (ProviderException e) {
            reportException(e, "getOrCreateSecretKey had a provider exception.");
        }
        return secretKey;
    }

    private void reportException(Exception exception, String error) {
        Log.wtf(TAG, "An irrecoverable key store error was encountered: " + error, exception);
    }

}
