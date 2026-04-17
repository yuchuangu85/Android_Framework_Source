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

/**
 * ML-DSA key backed by Android Keystore.
 *
 * <p>This interface contains methods that are not part of the Java Cryptography Architecture (JCA)
 * but are needed to implement <a href="https://openjdk.org/jeps/497">JEP 497</a>.
 *
 * @hide
 */
public interface AndroidKeyStoreMlDsaKey {
    /**
     * Returns the Java Security Standard Algorithm Name for the ML-DSA parameter set (e.g.
     * "ML-DSA-65") associated with this key. The family name ("ML-DSA") must not be returned.
     *
     * <p>This method is needed since <a href="https://openjdk.org/jeps/497">JEP 497</a> specifies
     * that {@link java.security.Key#getAlgorithm()} must return "ML-DSA" for all ML-DSA keys,
     * regardless of the parameter set. It also specifies that ML-DSA {@link
     * java.security.Signature} and {@link java.security.KeyFactory} instances initialized with
     * "ML-DSA" must accept keys of any parameter set, whereas instances initialized with a
     * parameter set name must only accept keys with that parameter set.
     *
     * <p>This method enables implementations of {@link java.security.Signature} and {@link
     * java.security.KeyFactory} to determine the parameter set associated with a given key since
     * they can't use {@link java.security.Key#getAlgorithm()} to do so.
     *
     * @return Java Security Standard Algorithm Name for the ML-DSA parameter set associated with
     *     this key.
     */
    String getMlDsaAlgorithm();
}
