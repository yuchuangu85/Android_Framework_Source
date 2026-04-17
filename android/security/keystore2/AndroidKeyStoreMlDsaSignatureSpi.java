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

package android.security.keystore2;

import android.annotation.NonNull;
import android.hardware.security.keymint.KeyParameter;
import android.security.keymaster.KeymasterDefs;
import android.security.keystore.KeyProperties;

import java.security.InvalidKeyException;
import java.security.Key;
import java.security.ProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.List;

/**
 * Base class for {@link java.security.SignatureSpi} implementations providing ML-DSA signatures
 * backed by Android Keystore.
 *
 * <p>Concrete subclasses are fully compliant with the {@link java.security.Signature} specification
 * given in <a href="https://openjdk.org/jeps/497">JEP 497</a> for the ML-DSA parameter sets
 * supported by Android Keystore (ML-DSA-65 and ML-DSA-87).
 *
 * @hide
 */
abstract class AndroidKeyStoreMlDsaSignatureSpi extends AndroidKeyStoreSignatureSpiBase {
    /**
     * Whether the given Java Security Standard Algorithm Name is supported.
     *
     * <p>Only ML-DSA subclasses need to override this method since <a
     * href="https://openjdk.org/jeps/497">JEP 497</a> prescribes that {@link
     * java.security.Signature} instances should selectively accept keys depending on how they were
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
    abstract boolean supportsAlgorithm(String algorithm);

    public static class MLDSA extends AndroidKeyStoreMlDsaSignatureSpi {
        @Override
        protected String getAlgorithm() {
            return KeyProperties.KEY_ALGORITHM_ML_DSA;
        }

        @Override
        public boolean supportsAlgorithm(String algorithm) {
            // The family name "ML-DSA" is not included since Signature instances are initialized
            // with a key (via "initSign"), which is associated with a specific parameter set.
            return algorithm.equalsIgnoreCase(KeyProperties.KEY_ALGORITHM_ML_DSA_65)
                    || algorithm.equalsIgnoreCase(KeyProperties.KEY_ALGORITHM_ML_DSA_87);
        }
    }

    public static class MLDSA65 extends AndroidKeyStoreMlDsaSignatureSpi {
        @Override
        protected String getAlgorithm() {
            return KeyProperties.KEY_ALGORITHM_ML_DSA_65;
        }

        @Override
        public boolean supportsAlgorithm(String algorithm) {
            return algorithm.equalsIgnoreCase(KeyProperties.KEY_ALGORITHM_ML_DSA_65);
        }
    }

    public static class MLDSA87 extends AndroidKeyStoreMlDsaSignatureSpi {
        @Override
        protected String getAlgorithm() {
            return KeyProperties.KEY_ALGORITHM_ML_DSA_87;
        }

        @Override
        public boolean supportsAlgorithm(String algorithm) {
            return algorithm.equalsIgnoreCase(KeyProperties.KEY_ALGORITHM_ML_DSA_87);
        }
    }

    @Override
    protected final void engineInitSign(PrivateKey key) throws InvalidKeyException {
        checkKey(key);
        super.engineInitSign(key);
    }

    @Override
    protected final void engineInitVerify(PublicKey key) throws InvalidKeyException {
        checkKey(key);
        super.engineInitVerify(key);
    }

    // TODO(b/462036047): Use KeymasterDefs constants when KeyMint V5 is frozen.
    @Override
    protected void addAlgorithmSpecificParametersToBegin(
            @NonNull AndroidKeyStoreKey key, @NonNull List<KeyParameter> parameters) {
        String algorithm = getMlDsaAlgorithm(key);
        int variantTag;
        if (algorithm.equalsIgnoreCase(KeyProperties.KEY_ALGORITHM_ML_DSA_65)) {
            variantTag = KeyProperties.KM_ML_DSA_VARIANT_65;
        } else if (algorithm.equalsIgnoreCase(KeyProperties.KEY_ALGORITHM_ML_DSA_87)) {
            variantTag = KeyProperties.KM_ML_DSA_VARIANT_87;
        } else {
            throw new ProviderException(
                    "Can't determine ML-DSA variant from algorithm name: " + algorithm);
        }

        parameters.add(
                KeyStore2ParameterUtils.makeEnum(
                        KeymasterDefs.KM_TAG_ALGORITHM, KeyProperties.KM_ALGORITHM_ML_DSA));
        parameters.add(
                KeyStore2ParameterUtils.makeEnum(KeyProperties.KM_TAG_ML_DSA_VARIANT, variantTag));
        parameters.add(
                KeyStore2ParameterUtils.makeEnum(
                        KeymasterDefs.KM_TAG_DIGEST, KeymasterDefs.KM_DIGEST_NONE));
    }

    private void checkKey(Key key) throws InvalidKeyException {
        if (!(key instanceof AndroidKeyStoreKey)) {
            throw new InvalidKeyException(
                    "Unsupported private key type: " + key.getClass().getName());
        }
        String algorithm = getMlDsaAlgorithm((AndroidKeyStoreKey) key);
        if (!supportsAlgorithm(algorithm)) {
            throw new InvalidKeyException("Unsupported key algorithm: " + algorithm);
        }
    }

    private String getMlDsaAlgorithm(AndroidKeyStoreKey key) {
        if (key instanceof AndroidKeyStoreMlDsaKey) {
            return ((AndroidKeyStoreMlDsaKey) key).getMlDsaAlgorithm();
        }
        throw new ProviderException(
                "Unsupported key type: "
                        + key.getClass().getName()
                        + ". Expected an instance of AndroidKeyStoreMlDsaKey.");
    }
}
