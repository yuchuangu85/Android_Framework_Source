/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.keystore2;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactorySpi;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * {@link KeyFactorySpi} backed by Android Keystore.
 *
 * <p>The {@link #engineGetKeySpec(Key, Class)} method does not encode private keys in PKCS#8 format
 * (when the provided class is {@link PKCS8EncodedKeySpec}. This is because the AndroidKeyStore
 * provider does not export private key material in order to uphold its security guarantees.
 *
 * <p>Concrete subclasses specific to ML-DSA are partially compliant with the {@link
 * java.security.KeyFactory} specification given in <a href="https://openjdk.org/jeps/497">JEP
 * 497</a> <b>for the {@link java.security.KeyFactory} methods supported by Android Keystore</b>.
 * There is one deviation, which is the lack of support for {@link PKCS8EncodedKeySpec} in {@link
 * #engineGetKeySpec(Key, Class)} as described above.
 *
 * @hide
 */
public abstract class AndroidKeyStoreKeyFactorySpi extends KeyFactorySpi {
    /**
     * Whether the given Java Security Standard Algorithm Name is supported.
     *
     * <p>ML-DSA subclasses must override this method since <a
     * href="https://openjdk.org/jeps/497">JEP 497</a> prescribes that {@link
     * java.security.KeyFactory} instances should selectively accept keys depending on how they were
     * initialized:
     *
     * <ul>
     *   <li>If initialized with the family name ("ML-DSA"), accept keys with any parameter set (in
     *       our case ML-DSA-65 and ML-DSA-87 since they are the only two supported by Keystore and
     *       KeyMint).
     *   <li>If initialized with a parameter set name ("ML-DSA-65" or "ML-DSA-87"), only accept keys
     *       with that parameter set.
     * </ul>
     *
     * @param algorithm Java Security Standard Algorithm Name.
     * @return {@code true} if the given algorithm is supported, {@code false} otherwise.
     */
    boolean supportsAlgorithm(String algorithm) {
        return true;
    }

    public static class RSA extends AndroidKeyStoreKeyFactorySpi {}
    public static class EC extends AndroidKeyStoreKeyFactorySpi {}
    public static class XDH extends AndroidKeyStoreKeyFactorySpi {}
    public static class ED25519 extends AndroidKeyStoreKeyFactorySpi {}

    public static class MLDSA extends AndroidKeyStoreKeyFactorySpi {
        @Override
        public boolean supportsAlgorithm(String algorithm) {
            // The family name "ML-DSA" is not included since all KeyFactory methods take a key or
            // key spec as a parameter, which are associated with a specific parameter set.
            return algorithm.equalsIgnoreCase(KeyProperties.KEY_ALGORITHM_ML_DSA_65)
                    || algorithm.equalsIgnoreCase(KeyProperties.KEY_ALGORITHM_ML_DSA_87);
        }
    }

    public static class MLDSA65 extends AndroidKeyStoreKeyFactorySpi {
        @Override
        public boolean supportsAlgorithm(String algorithm) {
            return algorithm.equalsIgnoreCase(KeyProperties.KEY_ALGORITHM_ML_DSA_65);
        }
    }

    public static class MLDSA87 extends AndroidKeyStoreKeyFactorySpi {
        @Override
        public boolean supportsAlgorithm(String algorithm) {
            return algorithm.equalsIgnoreCase(KeyProperties.KEY_ALGORITHM_ML_DSA_87);
        }
    }

    @Override
    protected <T extends KeySpec> T engineGetKeySpec(Key key, Class<T> keySpecClass)
            throws InvalidKeySpecException {
        if (key == null) {
            throw new InvalidKeySpecException("key == null");
        } else if ((!(key instanceof AndroidKeyStorePrivateKey))
            && (!(key instanceof AndroidKeyStorePublicKey))) {
            throw new InvalidKeySpecException(
                    "Unsupported key type: " + key.getClass().getName()
                    + ". This KeyFactory supports only Android Keystore asymmetric keys");
        }

        // key is an Android Keystore private or public key

        if (keySpecClass == null) {
            throw new InvalidKeySpecException("keySpecClass == null");
        } else if (KeyInfo.class.equals(keySpecClass)) {
            if (!(key instanceof AndroidKeyStorePrivateKey)) {
                throw new InvalidKeySpecException(
                        "Unsupported key type: " + key.getClass().getName()
                        + ". KeyInfo can be obtained only for Android Keystore private keys");
            }
            checkKeyAlgorithm(key);
            AndroidKeyStorePrivateKey keystorePrivateKey = (AndroidKeyStorePrivateKey) key;
            @SuppressWarnings("unchecked")
            T result = (T) AndroidKeyStoreSecretKeyFactorySpi.getKeyInfo(keystorePrivateKey);
            return result;
        } else if (X509EncodedKeySpec.class.equals(keySpecClass)) {
            if (!(key instanceof AndroidKeyStorePublicKey)) {
                throw new InvalidKeySpecException(
                        "Unsupported key type: " + key.getClass().getName()
                        + ". X509EncodedKeySpec can be obtained only for Android Keystore public"
                        + " keys");
            }
            checkKeyAlgorithm(key);
            @SuppressWarnings("unchecked")
            T result = (T) new X509EncodedKeySpec(((AndroidKeyStorePublicKey) key).getEncoded());
            return result;
        } else if (PKCS8EncodedKeySpec.class.equals(keySpecClass)) {
            if (key instanceof AndroidKeyStorePrivateKey) {
                throw new InvalidKeySpecException(
                        "Key material export of Android Keystore private keys is not supported");
            } else {
                throw new InvalidKeySpecException(
                        "Cannot export key material of public key in PKCS#8 format."
                        + " Only X.509 format (X509EncodedKeySpec) supported for public keys.");
            }
        } else if (RSAPublicKeySpec.class.equals(keySpecClass)) {
            if (key instanceof AndroidKeyStoreRSAPublicKey) {
                checkKeyAlgorithm(key);
                AndroidKeyStoreRSAPublicKey rsaKey = (AndroidKeyStoreRSAPublicKey) key;
                @SuppressWarnings("unchecked")
                T result =
                        (T) new RSAPublicKeySpec(rsaKey.getModulus(), rsaKey.getPublicExponent());
                return result;
            } else {
                throw new InvalidKeySpecException(
                        "Obtaining RSAPublicKeySpec not supported for " + key.getAlgorithm() + " "
                        + ((key instanceof AndroidKeyStorePrivateKey) ? "private" : "public")
                        + " key");
            }
        } else if (ECPublicKeySpec.class.equals(keySpecClass)) {
            if (key instanceof AndroidKeyStoreECPublicKey) {
                checkKeyAlgorithm(key);
                AndroidKeyStoreECPublicKey ecKey = (AndroidKeyStoreECPublicKey) key;
                @SuppressWarnings("unchecked")
                T result = (T) new ECPublicKeySpec(ecKey.getW(), ecKey.getParams());
                return result;
            } else {
                throw new InvalidKeySpecException(
                        "Obtaining ECPublicKeySpec not supported for " + key.getAlgorithm() + " "
                        + ((key instanceof AndroidKeyStorePrivateKey) ? "private" : "public")
                        + " key");
            }
        } else {
            throw new InvalidKeySpecException("Unsupported key spec: " + keySpecClass.getName());
        }
    }

    @Override
    protected PrivateKey engineGeneratePrivate(KeySpec spec) throws InvalidKeySpecException {
        throw new InvalidKeySpecException(
                "To generate a key pair in Android Keystore, use KeyPairGenerator initialized with"
                + " " + KeyGenParameterSpec.class.getName());
    }

    @Override
    protected PublicKey engineGeneratePublic(KeySpec spec) throws InvalidKeySpecException {
        throw new InvalidKeySpecException(
                "To generate a key pair in Android Keystore, use KeyPairGenerator initialized with"
                + " " + KeyGenParameterSpec.class.getName());
    }

    @Override
    protected Key engineTranslateKey(Key key) throws InvalidKeyException {
        if (key == null) {
            throw new InvalidKeyException("key == null");
        } else if ((!(key instanceof AndroidKeyStorePrivateKey))
                && (!(key instanceof AndroidKeyStorePublicKey))) {
            throw new InvalidKeyException(
                    "To import a key into Android Keystore, use KeyStore.setEntry");
        }
        try {
            checkKeyAlgorithm(key);
        } catch (InvalidKeySpecException e) {
            throw new InvalidKeyException(e);
        }
        return key;
    }

    private void checkKeyAlgorithm(Key key) throws InvalidKeySpecException {
        String algorithm = key.getAlgorithm();

        // ML-DSA keys need special handling to get their parameter set name since JEP 497
        // requires their "getAlgorithm()" method to always return the family name "ML-DSA".
        if (algorithm.equalsIgnoreCase(KeyProperties.KEY_ALGORITHM_ML_DSA)) {
            if (key instanceof AndroidKeyStoreMlDsaKey) {
                algorithm = ((AndroidKeyStoreMlDsaKey) key).getMlDsaAlgorithm();
            } else {
                throw new InvalidKeySpecException(
                        "ML-DSA key must be an instance of AndroidKeyStoreMlDsaKey");
            }
        }
        if (!supportsAlgorithm(algorithm)) {
            throw new InvalidKeySpecException("Unsupported key algorithm: " + algorithm);
        }
    }
}
