/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static android.security.keystore2.AndroidKeyStoreCipherSpiBase.DEFAULT_MGF1_DIGEST;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.hardware.security.keymint.EcCurve;
import android.hardware.security.keymint.KeyParameter;
import android.hardware.security.keymint.KeyPurpose;
import android.hardware.security.keymint.SecurityLevel;
import android.hardware.security.keymint.Tag;
import android.os.Build;
import android.os.StrictMode;
import android.security.Flags;
import android.security.KeyPairGeneratorSpec;
import android.security.KeyStore2;
import android.security.KeyStoreException;
import android.security.KeyStoreSecurityLevel;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterDefs;
import android.security.keystore.ArrayUtils;
import android.security.keystore.AttestationUtils;
import android.security.keystore.DeviceIdAttestationException;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.SecureKeyImportUnavailableException;
import android.security.keystore.StrongBoxUnavailableException;
import android.system.keystore2.Authorization;
import android.system.keystore2.Domain;
import android.system.keystore2.IKeystoreSecurityLevel;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyEntryResponse;
import android.system.keystore2.KeyMetadata;
import android.system.keystore2.ResponseCode;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import libcore.util.EmptyArray;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyPairGeneratorSpi;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * {@link KeyPairGeneratorSpi} backed by Android Keystore.
 *
 * <p>Initialization is only supported via an instance of {@link
 * android.security.keystore.KeyGenParameterSpec} or {@link android.security.KeyPairGeneratorSpec}.
 *
 * <p>Concrete subclasses specific to ML-DSA are partially compliant with the {@link
 * java.security.KeyPairGenerator} specification given in <a href="https://openjdk.org/jeps/497">JEP
 * 497</a>. There is one deviation:
 *
 * <ul>
 *   <li>Initialization with the relevant instance of {@link java.security.spec.NamedParameterSpec}
 *       is not supported, despite being prescribed by JEP 497. Instead, callers can provide an
 *       instance of {@link java.security.spec.NamedParameterSpec} as the {@code spec} argument in
 *       the constructor for {@link android.security.keystore.KeyGenParameterSpec} or {@link
 *       android.security.KeyPairGeneratorSpec}.
 * </ul>
 *
 * @hide
 */
public abstract class AndroidKeyStoreKeyPairGeneratorSpi extends KeyPairGeneratorSpi {
    private static final String TAG = "AndroidKeyStoreKeyPairGeneratorSpi";

    public static class RSA extends AndroidKeyStoreKeyPairGeneratorSpi {
        public RSA() {
            super(KeymasterDefs.KM_ALGORITHM_RSA);
        }
    }

    public static class EC extends AndroidKeyStoreKeyPairGeneratorSpi {
        public EC() {
            super(KeymasterDefs.KM_ALGORITHM_EC);
        }
    }

    // KeyMint uses the KM_ALGORITHM_EC constant defined in
    // frameworks/base/core/java/android/security/keymaster/KeymasterDefs.java for Curve25519.
    // However, the Java layer needs to distinguish between Curve25519 and other EC algorithms, so
    // it uses new constants with values outside the range of the KeyMint enum.
    private static final int ALGORITHM_XDH = KeymasterDefs.KM_ALGORITHM_EC + 1200;
    private static final int ALGORITHM_ED25519 = ALGORITHM_XDH + 1;

    /**
     * XDH represents Curve 25519 agreement key provider.
     */
    public static class XDH extends AndroidKeyStoreKeyPairGeneratorSpi {
        // XDH is treated as EC.
        public XDH() {
            super(ALGORITHM_XDH);
        }
    }

    /**
     * ED25519 represents Curve 25519 signing key provider.
     */
    public static class ED25519 extends AndroidKeyStoreKeyPairGeneratorSpi {
        // ED25519 is treated as EC.
        public ED25519() {
            super(ALGORITHM_ED25519);
        }
    }

    public static class MLDSA extends AndroidKeyStoreKeyPairGeneratorSpi {
        public MLDSA() {
            super(
                    /* keymasterAlgorithm= */ KeyProperties.KM_ALGORITHM_ML_DSA,
                    /* mlDsaAlgorithmName= */ KeyProperties.KEY_ALGORITHM_ML_DSA);
        }
    }

    public static class MLDSA65 extends AndroidKeyStoreKeyPairGeneratorSpi {
        public MLDSA65() {
            super(
                    /* keymasterAlgorithm= */ KeyProperties.KM_ALGORITHM_ML_DSA,
                    /* mlDsaAlgorithmName= */ KeyProperties.KEY_ALGORITHM_ML_DSA_65);
        }
    }

    public static class MLDSA87 extends AndroidKeyStoreKeyPairGeneratorSpi {
        public MLDSA87() {
            super(
                    /* keymasterAlgorithm= */ KeyProperties.KM_ALGORITHM_ML_DSA,
                    /* mlDsaAlgorithmName= */ KeyProperties.KEY_ALGORITHM_ML_DSA_87);
        }
    }

    private static final int NO_KEY_SIZE = -1;

    /* EC */
    private static final int EC_DEFAULT_KEY_SIZE = 256;

    /* RSA */
    private static final int RSA_DEFAULT_KEY_SIZE = 2048;
    private static final int RSA_MIN_KEY_SIZE = 512;
    private static final int RSA_MAX_KEY_SIZE = 8192;

    private static final Map<String, Integer> SUPPORTED_EC_CURVE_NAME_TO_SIZE =
            new HashMap<String, Integer>();
    private static final List<String> SUPPORTED_EC_CURVE_NAMES = new ArrayList<String>();
    private static final List<Integer> SUPPORTED_EC_CURVE_SIZES = new ArrayList<Integer>();
    private static final String CURVE_X25519 = NamedParameterSpec.X25519.getName();
    private static final String CURVE_ED25519 = NamedParameterSpec.ED25519.getName();

    static {
        // Aliases for NIST P-224
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("p-224", 224);
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("secp224r1", 224);

        // Aliases for NIST P-256
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("p-256", 256);
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("secp256r1", 256);
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("prime256v1", 256);

        // Aliases for Curve 25519
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put(CURVE_X25519.toLowerCase(Locale.US), 256);
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put(CURVE_ED25519.toLowerCase(Locale.US), 256);

        // Aliases for NIST P-384
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("p-384", 384);
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("secp384r1", 384);

        // Aliases for NIST P-521
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("p-521", 521);
        SUPPORTED_EC_CURVE_NAME_TO_SIZE.put("secp521r1", 521);

        SUPPORTED_EC_CURVE_NAMES.addAll(SUPPORTED_EC_CURVE_NAME_TO_SIZE.keySet());
        Collections.sort(SUPPORTED_EC_CURVE_NAMES);

        SUPPORTED_EC_CURVE_SIZES.addAll(
                new HashSet<Integer>(SUPPORTED_EC_CURVE_NAME_TO_SIZE.values()));
        Collections.sort(SUPPORTED_EC_CURVE_SIZES);
    }

    // Algorithm from the relevant KM_ALGORITHM_.* constant defined in
    // frameworks/base/core/java/android/security/keymaster/KeymasterDefs.java, or one of the
    // special values for Curve25519 ({@link #ALGORITHM_XDH}, {@link #ALGORITHM_ED25519}).
    // This variable is only used for EC in order to set the correct algorithm for XDH and Ed25519.
    private final int mOriginalKeymasterAlgorithm;

    // KeyMint Tag::ML_DSA_VARIANT AIDL enum value to use for this key pair. This variable is only
    // populated and used for ML-DSA.
    private int mMlDsaVariantTag;

    // Algorithm name used to initialize this KeyPairGenerator. This variable is only populated and
    // used for ML-DSA.
    // Implementation note: Technically, KeyPairGenerator only needs to keep track of the ML-DSA
    // parameter set in order to function correctly. However, the original algorithm name is also
    // stored in order to provide more useful exception messages. Since JEP 497 requires that
    // KeyPairGenerator instances initialized with the family name "ML-DSA" generate ML-DSA-65
    // keys, it's not always possible to map the parameter set name back to the algorithm name
    // provided at initialization time.
    private String mMlDsaAlgorithmName;

    private KeyStore2 mKeyStore;
    private KeyGenParameterSpec mSpec;
    private String mEntryAlias;
    private int mEntryNamespace;
    private @KeyProperties.KeyAlgorithmEnum String mJcaKeyAlgorithm;
    private int mKeymasterAlgorithm = -1;
    private int mKeySizeBits;
    private SecureRandom mRng;
    private KeyDescriptor mAttestKeyDescriptor;
    private String mEcCurveName;
    private int[] mKeymasterPurposes;
    private int[] mKeymasterBlockModes;
    private int[] mKeymasterEncryptionPaddings;
    private int[] mKeymasterSignaturePaddings;
    private int[] mKeymasterDigests;
    private int[] mKeymasterMgf1Digests;
    private Long mRSAPublicExponent;

    protected AndroidKeyStoreKeyPairGeneratorSpi(int keymasterAlgorithm) {
        mOriginalKeymasterAlgorithm = keymasterAlgorithm;
    }

    protected AndroidKeyStoreKeyPairGeneratorSpi(
            int keymasterAlgorithm, @NonNull String mlDsaAlgorithmName) {
        mOriginalKeymasterAlgorithm = keymasterAlgorithm;
        mMlDsaAlgorithmName = mlDsaAlgorithmName;
        mMlDsaVariantTag =
                switch (mlDsaAlgorithmName) {
                    case KeyProperties.KEY_ALGORITHM_ML_DSA_65 ->
                            KeyProperties.KM_ML_DSA_VARIANT_65;
                    case KeyProperties.KEY_ALGORITHM_ML_DSA_87 ->
                            KeyProperties.KM_ML_DSA_VARIANT_87;
                    // JEP 497 requires that KeyPairGenerator instances initialized with the family
                    // name "ML-DSA" generate ML-DSA-65 keys.
                    case KeyProperties.KEY_ALGORITHM_ML_DSA -> KeyProperties.KM_ML_DSA_VARIANT_65;
                    default ->
                            throw new IllegalArgumentException(
                                    "Unsupported ML-DSA algorithm: " + mlDsaAlgorithmName);
                };
    }

    private static @EcCurve int keySizeAndNameToEcCurve(int keySizeBits, String ecCurveName)
            throws InvalidAlgorithmParameterException {
        switch (keySizeBits) {
            case 224:
                return EcCurve.P_224;
            case 256:
                if (isCurve25519(ecCurveName)) {
                    return EcCurve.CURVE_25519;
                }
                return EcCurve.P_256;
            case 384:
                return EcCurve.P_384;
            case 521:
                return EcCurve.P_521;
            default:
                throw new InvalidAlgorithmParameterException(
                        "Unsupported EC curve keysize: " + keySizeBits);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void initialize(int keysize, SecureRandom random) throws InvalidParameterException {
        String message =
                KeyGenParameterSpec.class.getName()
                        + " or "
                        + KeyPairGeneratorSpec.class.getName()
                        + " required to initialize this KeyPairGenerator";

        // JEP 497 requires ML-DSA KeyPairGenerator implementations to throw an
        // InvalidParameterException when initialized with a key size. The JEPs for other
        // algorithms do not prescribe an exception type for unsupported key sizes and
        // IllegalArgumentException was chosen when the provider was first implemented. This
        // probably wasn't the correct choice given that the "KeyPairGenerator.initialize" methods
        // that take a key size argument declare that they throw an InvalidParameterException "if
        // the keysize is not supported by this KeyPairGenerator object". It would be nice to
        // simplify this and always throw an InvalidParameterException, but we don't want to break
        // users who expect an IllegalArgumentException (Hyrum's Law).
        // Implementation note: We can check for non-nullness of mMlDsaAlgorithmName since
        // the AndroidKeyStoreKeyPairGeneratorSpi constructor sets this variable iff the algorithm
        // is ML-DSA.
        if (mMlDsaAlgorithmName != null) {
            throw new InvalidParameterException(message);
        } else {
            throw new IllegalArgumentException(message);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void initialize(AlgorithmParameterSpec params, SecureRandom random)
            throws InvalidAlgorithmParameterException {
        resetAll();

        boolean success = false;
        try {
            if (params == null) {
                throw new InvalidAlgorithmParameterException(
                        "Must supply params of type " + KeyGenParameterSpec.class.getName()
                                + " or " + KeyPairGeneratorSpec.class.getName());
            }

            KeyGenParameterSpec spec;
            int keymasterAlgorithm = (mOriginalKeymasterAlgorithm == ALGORITHM_XDH
                    || mOriginalKeymasterAlgorithm == ALGORITHM_ED25519)
                    ? KeymasterDefs.KM_ALGORITHM_EC : mOriginalKeymasterAlgorithm;
            if (params instanceof KeyGenParameterSpec) {
                spec = (KeyGenParameterSpec) params;
            } else if (params instanceof KeyPairGeneratorSpec) {
                // Deprecated legacy spec
                KeyPairGeneratorSpec legacySpec = (KeyPairGeneratorSpec) params;
                try {
                    keymasterAlgorithm = getKeymasterAlgorithmFromLegacy(keymasterAlgorithm,
                            legacySpec);
                    spec = buildKeyGenParameterSpecFromLegacy(legacySpec, keymasterAlgorithm);
                } catch (NullPointerException | IllegalArgumentException e) {
                    throw new InvalidAlgorithmParameterException(e);
                }
            } else if (params instanceof NamedParameterSpec) {
                NamedParameterSpec namedSpec = (NamedParameterSpec) params;
                // Android Keystore cannot support initialization from a NamedParameterSpec
                // because an alias for the key is needed (a KeyGenParameterSpec cannot be
                // constructed).
                if (namedSpec.getName().equalsIgnoreCase(NamedParameterSpec.X25519.getName())
                        || namedSpec.getName().equalsIgnoreCase(
                        NamedParameterSpec.ED25519.getName())) {
                    throw new IllegalArgumentException(
                            "This KeyPairGenerator cannot be initialized using NamedParameterSpec."
                                    + " use " + KeyGenParameterSpec.class.getName() + " or "
                                    + KeyPairGeneratorSpec.class.getName());
                } else {
                    throw new InvalidAlgorithmParameterException(
                            "Unsupported algorithm specified via NamedParameterSpec: "
                            + namedSpec.getName());
                }
            } else {
                throw new InvalidAlgorithmParameterException(
                        "Unsupported params class: " + params.getClass().getName()
                                + ". Supported: " + KeyGenParameterSpec.class.getName()
                                + ", " + KeyPairGeneratorSpec.class.getName());
            }

            mSpec = spec;
            mEntryNamespace = spec.getNamespace();
            mKeymasterAlgorithm = keymasterAlgorithm;
            mEntryAlias = spec.getKeystoreAlias();

            mKeySizeBits = spec.getKeySize();
            initAlgorithmSpecificParameters();
            if (mKeySizeBits == NO_KEY_SIZE) {
                mKeySizeBits = getDefaultKeySize(keymasterAlgorithm);
            }
            checkValidKeySize(keymasterAlgorithm, mKeySizeBits, mSpec.isStrongBoxBacked(),
                    mEcCurveName);

            String jcaKeyAlgorithm;
            try {
                jcaKeyAlgorithm = KeyProperties.KeyAlgorithm.fromKeymasterAsymmetricKeyAlgorithm(
                        keymasterAlgorithm);
                mKeymasterPurposes = KeyProperties.Purpose.allToKeymaster(spec.getPurposes());
                mKeymasterBlockModes = KeyProperties.BlockMode.allToKeymaster(spec.getBlockModes());
                mKeymasterEncryptionPaddings = KeyProperties.EncryptionPadding.allToKeymaster(
                        spec.getEncryptionPaddings());
                if (((spec.getPurposes() & KeyProperties.PURPOSE_ENCRYPT) != 0)
                        && (spec.isRandomizedEncryptionRequired())) {
                    for (int keymasterPadding : mKeymasterEncryptionPaddings) {
                        if (!KeymasterUtils
                                .isKeymasterPaddingSchemeIndCpaCompatibleWithAsymmetricCrypto(
                                        keymasterPadding)) {
                            throw new InvalidAlgorithmParameterException(
                                    "Randomized encryption (IND-CPA) required but may be violated"
                                            + " by padding scheme: "
                                            + KeyProperties.EncryptionPadding.fromKeymaster(
                                            keymasterPadding)
                                            + ". See " + KeyGenParameterSpec.class.getName()
                                            + " documentation.");
                        }
                    }
                }
                mKeymasterSignaturePaddings = KeyProperties.SignaturePadding.allToKeymaster(
                        spec.getSignaturePaddings());

                validateDigests();
                if (spec.isDigestsSpecified()) {
                    mKeymasterDigests = KeyProperties.Digest.allToKeymaster(spec.getDigests());
                } else if (mKeymasterAlgorithm == KeyProperties.KM_ALGORITHM_ML_DSA) {
                    mKeymasterDigests =
                            KeyProperties.Digest.allToKeymaster(
                                    new String[] {KeyProperties.DIGEST_NONE});
                } else {
                    mKeymasterDigests = EmptyArray.INT;
                }
                if (spec.isMgf1DigestsSpecified()) {
                    // User-specified digests: Add all of them and do _not_ add the SHA-1
                    // digest by default (stick to what the user provided).
                    Set<String> mgfDigests = spec.getMgf1Digests();
                    mKeymasterMgf1Digests = new int[mgfDigests.size()];
                    int offset = 0;
                    for (String digest : mgfDigests) {
                        mKeymasterMgf1Digests[offset] = KeyProperties.Digest.toKeymaster(digest);
                        offset++;
                    }
                } else {
                    // No user-specified digests: Add the SHA-1 default.
                    mKeymasterMgf1Digests = new int[]{
                            KeyProperties.Digest.toKeymaster(DEFAULT_MGF1_DIGEST)};
                }

                // Check that user authentication related parameters are acceptable. This method
                // will throw an IllegalStateException if there are issues (e.g., secure lock screen
                // not set up).
                KeyStore2ParameterUtils.addUserAuthArgs(new ArrayList<>(), mSpec);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new InvalidAlgorithmParameterException(e);
            }

            mJcaKeyAlgorithm = jcaKeyAlgorithm;
            mRng = random;
            mKeyStore = KeyStore2.getInstance();

            mAttestKeyDescriptor = buildAndCheckAttestKeyDescriptor(spec);
            checkAttestKeyPurpose(spec);
            checkCorrectKeyPurposeIfCurve25519(spec);

            success = true;
        } finally {
            if (!success) {
                resetAll();
            }
        }
    }

    private void checkAttestKeyPurpose(KeyGenParameterSpec spec)
            throws InvalidAlgorithmParameterException {
        if ((spec.getPurposes() & KeyProperties.PURPOSE_ATTEST_KEY) != 0
                && spec.getPurposes() != KeyProperties.PURPOSE_ATTEST_KEY) {
            throw new InvalidAlgorithmParameterException(
                    "PURPOSE_ATTEST_KEY may not be specified with any other purposes");
        }
    }

    private void checkCorrectKeyPurposeIfCurve25519(KeyGenParameterSpec spec)
            throws InvalidAlgorithmParameterException {
        // Validate the key usage purposes against the curve. x25519 should be
        // key exchange only, ed25519 signing and attesting.

        if (!isCurve25519(mEcCurveName)) {
            return;
        }

        if (mEcCurveName.equalsIgnoreCase(CURVE_X25519)
                && spec.getPurposes() != KeyProperties.PURPOSE_AGREE_KEY) {
            throw new InvalidAlgorithmParameterException(
                    "x25519 may only be used for key agreement.");
        } else if (mEcCurveName.equalsIgnoreCase(CURVE_ED25519)
                && !hasOnlyAllowedPurposeForEd25519(spec.getPurposes())) {
            throw new InvalidAlgorithmParameterException(
                    "ed25519 may not be used for key agreement.");
        }
    }

    private static boolean isCurve25519(String ecCurveName) {
        if (ecCurveName == null) {
            return false;
        }
        return ecCurveName.equalsIgnoreCase(CURVE_X25519)
                || ecCurveName.equalsIgnoreCase(CURVE_ED25519);
    }

    private static boolean hasOnlyAllowedPurposeForEd25519(@KeyProperties.PurposeEnum int purpose) {
        final int allowedPurposes = KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY
                | KeyProperties.PURPOSE_ATTEST_KEY;
        boolean hasAllowedPurpose = (purpose & allowedPurposes) != 0;
        boolean hasDisallowedPurpose = (purpose & ~allowedPurposes) != 0;
        return hasAllowedPurpose && !hasDisallowedPurpose;
    }

    private KeyDescriptor buildAndCheckAttestKeyDescriptor(KeyGenParameterSpec spec)
            throws InvalidAlgorithmParameterException {
        if (spec.getAttestKeyAlias() != null) {
            KeyDescriptor attestKeyDescriptor = new KeyDescriptor();
            attestKeyDescriptor.domain = Domain.APP;
            attestKeyDescriptor.alias = spec.getAttestKeyAlias();
            try {
                KeyEntryResponse attestKey = mKeyStore.getKeyEntry(attestKeyDescriptor);
                checkAttestKeyChallenge(spec);
                checkAttestKeyPurpose(attestKey.metadata.authorizations);
                checkAttestKeySecurityLevel(spec, attestKey);
            } catch (KeyStoreException e) {
                throw new InvalidAlgorithmParameterException("Invalid attestKeyAlias", e);
            }
            return attestKeyDescriptor;
        }
        return null;
    }

    private void checkAttestKeyChallenge(KeyGenParameterSpec spec)
            throws InvalidAlgorithmParameterException {
        if (spec.getAttestationChallenge() == null) {
            throw new InvalidAlgorithmParameterException(
                    "AttestKey specified but no attestation challenge provided");
        }
    }

    private void checkAttestKeyPurpose(Authorization[] keyAuths)
            throws InvalidAlgorithmParameterException {
        Predicate<Authorization> isAttestKeyPurpose = x -> x.keyParameter.tag == Tag.PURPOSE
                && x.keyParameter.value.getKeyPurpose() == KeyPurpose.ATTEST_KEY;

        if (Arrays.stream(keyAuths).noneMatch(isAttestKeyPurpose)) {
            throw new InvalidAlgorithmParameterException(
                    ("Invalid attestKey, does not have PURPOSE_ATTEST_KEY"));
        }
    }

    private void checkAttestKeySecurityLevel(KeyGenParameterSpec spec, KeyEntryResponse key)
            throws InvalidAlgorithmParameterException {
        boolean attestKeyInStrongBox = key.metadata.keySecurityLevel == SecurityLevel.STRONGBOX;
        if (spec.isStrongBoxBacked() != attestKeyInStrongBox) {
            if (attestKeyInStrongBox) {
                throw new InvalidAlgorithmParameterException(
                        "Invalid security level: Cannot sign non-StrongBox key with "
                                + "StrongBox attestKey");

            } else {
                throw new InvalidAlgorithmParameterException(
                        "Invalid security level: Cannot sign StrongBox key with "
                                + "non-StrongBox attestKey");
            }
        }
    }

    private int getKeymasterAlgorithmFromLegacy(int keymasterAlgorithm,
            KeyPairGeneratorSpec legacySpec) throws InvalidAlgorithmParameterException {
        String specKeyAlgorithm = legacySpec.getKeyType();
        if (specKeyAlgorithm != null) {
            // Spec overrides the generator's default key algorithm
            try {
                keymasterAlgorithm =
                        KeyProperties.KeyAlgorithm.toKeymasterAsymmetricKeyAlgorithm(
                                specKeyAlgorithm);
            } catch (IllegalArgumentException e) {
                throw new InvalidAlgorithmParameterException(
                        "Invalid key type in parameters", e);
            }
        }
        return keymasterAlgorithm;
    }

    private KeyGenParameterSpec buildKeyGenParameterSpecFromLegacy(KeyPairGeneratorSpec legacySpec,
            int keymasterAlgorithm) {
        KeyGenParameterSpec.Builder specBuilder;
        switch (keymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_EC:
                specBuilder = new KeyGenParameterSpec.Builder(
                        legacySpec.getKeystoreAlias(),
                        KeyProperties.PURPOSE_SIGN
                                | KeyProperties.PURPOSE_VERIFY);
                // Authorized to be used with any digest (including no digest).
                // MD5 was never offered for Android Keystore for ECDSA.
                specBuilder.setDigests(
                        KeyProperties.DIGEST_NONE,
                        KeyProperties.DIGEST_SHA1,
                        KeyProperties.DIGEST_SHA224,
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512);
                break;
            case KeymasterDefs.KM_ALGORITHM_RSA:
                specBuilder = new KeyGenParameterSpec.Builder(
                        legacySpec.getKeystoreAlias(),
                        KeyProperties.PURPOSE_ENCRYPT
                                | KeyProperties.PURPOSE_DECRYPT
                                | KeyProperties.PURPOSE_SIGN
                                | KeyProperties.PURPOSE_VERIFY);
                // Authorized to be used with any digest (including no digest).
                specBuilder.setDigests(
                        KeyProperties.DIGEST_NONE,
                        KeyProperties.DIGEST_MD5,
                        KeyProperties.DIGEST_SHA1,
                        KeyProperties.DIGEST_SHA224,
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512);
                // Authorized to be used with any encryption and signature padding
                // schemes (including no padding).
                specBuilder.setEncryptionPaddings(
                        KeyProperties.ENCRYPTION_PADDING_NONE,
                        KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1,
                        KeyProperties.ENCRYPTION_PADDING_RSA_OAEP);
                specBuilder.setSignaturePaddings(
                        KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                        KeyProperties.SIGNATURE_PADDING_RSA_PSS);
                // Disable randomized encryption requirement to support encryption
                // padding NONE above.
                specBuilder.setRandomizedEncryptionRequired(false);
                break;
            // TODO(b/395069350): Use KeymasterDefs constant when KeyMint V5 is frozen.
            case KeyProperties.KM_ALGORITHM_ML_DSA:
                specBuilder =
                        new KeyGenParameterSpec.Builder(
                                legacySpec.getKeystoreAlias(),
                                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY);
                break;
            default:
                throw new ProviderException(
                        "Unsupported algorithm: " + mKeymasterAlgorithm);
        }

        if (legacySpec.getKeySize() != NO_KEY_SIZE) {
            specBuilder.setKeySize(legacySpec.getKeySize());
        }
        if (legacySpec.getAlgorithmParameterSpec() != null) {
            specBuilder.setAlgorithmParameterSpec(
                    legacySpec.getAlgorithmParameterSpec());
        }
        specBuilder.setCertificateSubject(legacySpec.getSubjectDN());
        specBuilder.setCertificateSerialNumber(legacySpec.getSerialNumber());
        specBuilder.setCertificateNotBefore(legacySpec.getStartDate());
        specBuilder.setCertificateNotAfter(legacySpec.getEndDate());
        specBuilder.setUserAuthenticationRequired(false);

        return specBuilder.build();
    }

    private void resetAll() {
        mEntryAlias = null;
        mEntryNamespace = KeyProperties.NAMESPACE_APPLICATION;
        mJcaKeyAlgorithm = null;
        mKeymasterAlgorithm = -1;
        mKeymasterPurposes = null;
        mKeymasterBlockModes = null;
        mKeymasterEncryptionPaddings = null;
        mKeymasterSignaturePaddings = null;
        mKeymasterDigests = null;
        mKeymasterMgf1Digests = null;
        mKeySizeBits = 0;
        mSpec = null;
        mRSAPublicExponent = null;
        mRng = null;
        mKeyStore = null;
        mEcCurveName = null;
    }

    private void initAlgorithmSpecificParameters() throws InvalidAlgorithmParameterException {
        AlgorithmParameterSpec algSpecificSpec = mSpec.getAlgorithmParameterSpec();
        switch (mKeymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_RSA: {
                BigInteger publicExponent = null;
                if (algSpecificSpec instanceof RSAKeyGenParameterSpec) {
                    RSAKeyGenParameterSpec rsaSpec = (RSAKeyGenParameterSpec) algSpecificSpec;
                    if (mKeySizeBits == NO_KEY_SIZE) {
                        mKeySizeBits = rsaSpec.getKeysize();
                    } else if (mKeySizeBits != rsaSpec.getKeysize()) {
                        throw new InvalidAlgorithmParameterException("RSA key size must match "
                                + " between " + mSpec + " and " + algSpecificSpec
                                + ": " + mKeySizeBits + " vs " + rsaSpec.getKeysize());
                    }
                    publicExponent = rsaSpec.getPublicExponent();
                } else if (algSpecificSpec != null) {
                    throw new InvalidAlgorithmParameterException(
                            "RSA may only use RSAKeyGenParameterSpec");
                }
                if (publicExponent == null) {
                    publicExponent = RSAKeyGenParameterSpec.F4;
                }
                if (publicExponent.compareTo(BigInteger.ZERO) < 1) {
                    throw new InvalidAlgorithmParameterException(
                            "RSA public exponent must be positive: " + publicExponent);
                }
                if ((publicExponent.signum() == -1)
                        || (publicExponent.compareTo(KeymasterArguments.UINT64_MAX_VALUE) > 0)) {
                    throw new InvalidAlgorithmParameterException(
                            "Unsupported RSA public exponent: " + publicExponent
                                    + ". Maximum supported value: "
                                    + KeymasterArguments.UINT64_MAX_VALUE);
                }
                mRSAPublicExponent = publicExponent.longValue();
                break;
            }
            case KeymasterDefs.KM_ALGORITHM_EC:
                if (algSpecificSpec instanceof ECGenParameterSpec) {
                    ECGenParameterSpec ecSpec = (ECGenParameterSpec) algSpecificSpec;
                    mEcCurveName = ecSpec.getName();
                    if (mOriginalKeymasterAlgorithm == ALGORITHM_XDH
                            && !mEcCurveName.equalsIgnoreCase("x25519")) {
                        throw new InvalidAlgorithmParameterException("XDH algorithm only supports"
                                + " x25519 curve.");
                    } else if (mOriginalKeymasterAlgorithm == ALGORITHM_ED25519
                            && !mEcCurveName.equalsIgnoreCase("ed25519")) {
                        throw new InvalidAlgorithmParameterException("Ed25519 algorithm only"
                                + " supports ed25519 curve.");
                    }
                    final Integer ecSpecKeySizeBits = SUPPORTED_EC_CURVE_NAME_TO_SIZE.get(
                            mEcCurveName.toLowerCase(Locale.US));
                    if (ecSpecKeySizeBits == null) {
                        throw new InvalidAlgorithmParameterException(
                                "Unsupported EC curve name: " + mEcCurveName
                                        + ". Supported: " + SUPPORTED_EC_CURVE_NAMES);
                    }
                    if (mKeySizeBits == NO_KEY_SIZE) {
                        mKeySizeBits = ecSpecKeySizeBits;
                    } else if (mKeySizeBits != ecSpecKeySizeBits) {
                        throw new InvalidAlgorithmParameterException("EC key size must match "
                                + " between " + mSpec + " and " + algSpecificSpec
                                + ": " + mKeySizeBits + " vs " + ecSpecKeySizeBits);
                    }
                } else if (algSpecificSpec != null) {
                    throw new InvalidAlgorithmParameterException(
                            "EC may only use ECGenParameterSpec");
                }
                break;
            // TODO(b/462036047): Use KeymasterDefs constant when KeyMint V5 is frozen.
            case KeyProperties.KM_ALGORITHM_ML_DSA:
                // An AlgorithmParameterSpec is not required for ML-DSA. However, if one is
                // provided, it must be a NamedParameterSpec and it must be consistent with the
                // algorithm name provided at initialization time.
                if (algSpecificSpec instanceof NamedParameterSpec) {
                    String algorithmName = ((NamedParameterSpec) algSpecificSpec).getName();
                    if (!mMlDsaAlgorithmName.equalsIgnoreCase(algorithmName)) {
                        throw new InvalidAlgorithmParameterException(
                                "NamedParameterSpec ("
                                        + algorithmName
                                        + ") does not match algorithm name used to initialize"
                                        + " KeyPairGenerator ("
                                        + mMlDsaAlgorithmName
                                        + ")");
                    }
                } else if (algSpecificSpec != null) {
                    throw new InvalidAlgorithmParameterException(
                            "Unsupported AlgorithmParameterSpec: "
                                    + algSpecificSpec.getClass().getName()
                                    + ". ML-DSA only supports "
                                    + NamedParameterSpec.class.getName());
                }
                break;
            default:
                throw new ProviderException("Unsupported algorithm: " + mKeymasterAlgorithm);
        }
    }

    @Override
    public KeyPair generateKeyPair() {
        StrictMode.noteSlowCall("generateKeyPair");
        if (mKeyStore == null || mSpec == null) {
            throw new IllegalStateException("Not initialized");
        }

        final @SecurityLevel int securityLevel =
                mSpec.isStrongBoxBacked()
                        ? SecurityLevel.STRONGBOX
                        : SecurityLevel.TRUSTED_ENVIRONMENT;

        final int flags =
                mSpec.isCriticalToDeviceEncryption()
                        ? IKeystoreSecurityLevel
                        .KEY_FLAG_AUTH_BOUND_WITHOUT_CRYPTOGRAPHIC_LSKF_BINDING
                        : 0;

        byte[] additionalEntropy =
                KeyStoreCryptoOperationUtils.getRandomBytesToMixIntoKeystoreRng(
                        mRng, (mKeySizeBits + 7) / 8);

        KeyDescriptor descriptor = new KeyDescriptor();
        descriptor.alias = mEntryAlias;
        descriptor.domain = mEntryNamespace == KeyProperties.NAMESPACE_APPLICATION
                ? Domain.APP
                : Domain.SELINUX;
        descriptor.nspace = mEntryNamespace;
        descriptor.blob = null;

        boolean success = false;
        try {
            KeyStoreSecurityLevel iSecurityLevel = mKeyStore.getSecurityLevel(securityLevel);

            KeyMetadata metadata = iSecurityLevel.generateKey(descriptor, mAttestKeyDescriptor,
                    constructKeyGenerationArguments(), flags, additionalEntropy);

            AndroidKeyStorePublicKey publicKey =
                    AndroidKeyStoreProvider.makeAndroidKeyStorePublicKeyFromKeyEntryResponse(
                            descriptor, metadata, iSecurityLevel, mKeymasterAlgorithm);
            success = true;
            return new KeyPair(publicKey, publicKey.getPrivateKey());
        } catch (KeyStoreException e) {
            switch (e.getErrorCode()) {
                case KeymasterDefs.KM_ERROR_HARDWARE_TYPE_UNAVAILABLE:
                    throw new StrongBoxUnavailableException("Failed to generated key pair.", e);
                default:
                    ProviderException p = new ProviderException("Failed to generate key pair.", e);
                    if ((mSpec.getPurposes() & KeyProperties.PURPOSE_WRAP_KEY) != 0) {
                        throw new SecureKeyImportUnavailableException(p);
                    }
                    throw p;
            }
        } catch (UnrecoverableKeyException | IllegalArgumentException
                | DeviceIdAttestationException | InvalidAlgorithmParameterException e) {
            throw new ProviderException(
                    "Failed to construct key object from newly generated key pair.", e);
        } finally {
            if (!success) {
                try {
                    mKeyStore.deleteKey(descriptor);
                } catch (KeyStoreException e) {
                    if (e.getErrorCode() != ResponseCode.KEY_NOT_FOUND) {
                        Log.e(TAG, "Failed to delete newly generated key after "
                                + "generation failed unexpectedly.", e);
                    }
                }
            }
        }
    }

    @RequiresPermission(value = android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            conditional = true)
    private void addAttestationParameters(@NonNull List<KeyParameter> params)
            throws ProviderException, IllegalArgumentException, DeviceIdAttestationException {
        byte[] challenge = mSpec.getAttestationChallenge();

        if (challenge != null) {
            params.add(KeyStore2ParameterUtils.makeBytes(
                    KeymasterDefs.KM_TAG_ATTESTATION_CHALLENGE, challenge
            ));

            if (mSpec.isDevicePropertiesAttestationIncluded()) {
                final String platformReportedBrand =
                        isPropertyEmptyOrUnknown(Build.BRAND_FOR_ATTESTATION)
                        ? Build.BRAND : Build.BRAND_FOR_ATTESTATION;
                params.add(KeyStore2ParameterUtils.makeBytes(
                        KeymasterDefs.KM_TAG_ATTESTATION_ID_BRAND,
                        platformReportedBrand.getBytes(StandardCharsets.UTF_8)
                ));
                final String platformReportedDevice =
                        isPropertyEmptyOrUnknown(Build.DEVICE_FOR_ATTESTATION)
                                ? Build.DEVICE : Build.DEVICE_FOR_ATTESTATION;
                params.add(KeyStore2ParameterUtils.makeBytes(
                        KeymasterDefs.KM_TAG_ATTESTATION_ID_DEVICE,
                        platformReportedDevice.getBytes(StandardCharsets.UTF_8)
                ));
                final String platformReportedProduct =
                        isPropertyEmptyOrUnknown(Build.PRODUCT_FOR_ATTESTATION)
                        ? Build.PRODUCT : Build.PRODUCT_FOR_ATTESTATION;
                params.add(KeyStore2ParameterUtils.makeBytes(
                        KeymasterDefs.KM_TAG_ATTESTATION_ID_PRODUCT,
                        platformReportedProduct.getBytes(StandardCharsets.UTF_8)
                ));
                final String platformReportedManufacturer =
                        isPropertyEmptyOrUnknown(Build.MANUFACTURER_FOR_ATTESTATION)
                                ? Build.MANUFACTURER : Build.MANUFACTURER_FOR_ATTESTATION;
                params.add(KeyStore2ParameterUtils.makeBytes(
                        KeymasterDefs.KM_TAG_ATTESTATION_ID_MANUFACTURER,
                        platformReportedManufacturer.getBytes(StandardCharsets.UTF_8)
                ));
                final String platformReportedModel =
                        isPropertyEmptyOrUnknown(Build.MODEL_FOR_ATTESTATION)
                        ? Build.MODEL : Build.MODEL_FOR_ATTESTATION;
                params.add(KeyStore2ParameterUtils.makeBytes(
                        KeymasterDefs.KM_TAG_ATTESTATION_ID_MODEL,
                        platformReportedModel.getBytes(StandardCharsets.UTF_8)
                ));
            }

            int[] idTypes = mSpec.getAttestationIds();
            if (idTypes.length == 0) {
                return;
            }
            final Set<Integer> idTypesSet = new ArraySet<>(idTypes.length);
            for (int idType : idTypes) {
                idTypesSet.add(idType);
            }
            TelephonyManager telephonyService = null;
            if (idTypesSet.contains(AttestationUtils.ID_TYPE_IMEI)
                    || idTypesSet.contains(AttestationUtils.ID_TYPE_MEID)) {
                telephonyService =
                        (TelephonyManager) android.app.AppGlobals.getInitialApplication()
                                .getSystemService(Context.TELEPHONY_SERVICE);
                if (telephonyService == null) {
                    throw new DeviceIdAttestationException("Unable to access telephony service");
                }
            }
            for (final Integer idType : idTypesSet) {
                switch (idType) {
                    case AttestationUtils.ID_TYPE_SERIAL:
                        params.add(KeyStore2ParameterUtils.makeBytes(
                                KeymasterDefs.KM_TAG_ATTESTATION_ID_SERIAL,
                                Build.getSerial().getBytes(StandardCharsets.UTF_8)
                        ));
                        break;
                    case AttestationUtils.ID_TYPE_IMEI: {
                        final String imei = telephonyService.getImei(0);
                        if (imei == null) {
                            throw new DeviceIdAttestationException("Unable to retrieve IMEI");
                        }
                        params.add(KeyStore2ParameterUtils.makeBytes(
                                KeymasterDefs.KM_TAG_ATTESTATION_ID_IMEI,
                                imei.getBytes(StandardCharsets.UTF_8)
                        ));
                        final String secondImei = telephonyService.getImei(1);
                        if (!TextUtils.isEmpty(secondImei)) {
                            params.add(KeyStore2ParameterUtils.makeBytes(
                                    KeymasterDefs.KM_TAG_ATTESTATION_ID_SECOND_IMEI,
                                    secondImei.getBytes(StandardCharsets.UTF_8)
                            ));
                        }
                        break;
                    }
                    case AttestationUtils.ID_TYPE_MEID: {
                        String meid;
                        try {
                            meid = telephonyService.getMeid(0);
                        } catch (UnsupportedOperationException e) {
                            Log.e(TAG, "Unable to retrieve MEID", e);
                            meid = null;
                        }
                        if (meid == null) {
                            throw new DeviceIdAttestationException("Unable to retrieve MEID");
                        }
                        params.add(KeyStore2ParameterUtils.makeBytes(
                                KeymasterDefs.KM_TAG_ATTESTATION_ID_MEID,
                                meid.getBytes(StandardCharsets.UTF_8)
                        ));
                        break;
                    }
                    case AttestationUtils.USE_INDIVIDUAL_ATTESTATION: {
                        params.add(KeyStore2ParameterUtils.makeBool(
                                KeymasterDefs.KM_TAG_DEVICE_UNIQUE_ATTESTATION));
                        break;
                    }
                    default:
                        throw new IllegalArgumentException("Unknown device ID type " + idType);
                }
            }
        }
    }

    private Collection<KeyParameter> constructKeyGenerationArguments()
            throws DeviceIdAttestationException, IllegalArgumentException,
            InvalidAlgorithmParameterException {
        List<KeyParameter> params = new ArrayList<>();
        // Ignore the key size since it has no meaning for ML-DSA and we don't
        // want it to appear as a key authorization, even if the caller set a
        // non-sentinel value.
        if (mKeymasterAlgorithm != KeyProperties.KM_ALGORITHM_ML_DSA) {
            params.add(
                    KeyStore2ParameterUtils.makeInt(KeymasterDefs.KM_TAG_KEY_SIZE, mKeySizeBits));
        }
        params.add(KeyStore2ParameterUtils.makeEnum(
                KeymasterDefs.KM_TAG_ALGORITHM, mKeymasterAlgorithm
        ));

        if (mKeymasterAlgorithm == KeymasterDefs.KM_ALGORITHM_EC) {
            params.add(KeyStore2ParameterUtils.makeEnum(
                    Tag.EC_CURVE, keySizeAndNameToEcCurve(mKeySizeBits, mEcCurveName)
            ));
        }

        ArrayUtils.forEach(mKeymasterPurposes, (purpose) -> {
            params.add(KeyStore2ParameterUtils.makeEnum(
                    KeymasterDefs.KM_TAG_PURPOSE, purpose
            ));
        });
        ArrayUtils.forEach(mKeymasterBlockModes, (blockMode) -> {
            params.add(KeyStore2ParameterUtils.makeEnum(
                    KeymasterDefs.KM_TAG_BLOCK_MODE, blockMode
            ));
        });
        ArrayUtils.forEach(mKeymasterEncryptionPaddings, (padding) -> {
            params.add(KeyStore2ParameterUtils.makeEnum(
                    KeymasterDefs.KM_TAG_PADDING, padding
            ));
            if (padding == KeymasterDefs.KM_PAD_RSA_OAEP) {
                ArrayUtils.forEach(mKeymasterMgf1Digests, (mgf1Digest) -> {
                    params.add(KeyStore2ParameterUtils.makeEnum(
                            KeymasterDefs.KM_TAG_RSA_OAEP_MGF_DIGEST, mgf1Digest
                    ));
                });

                // If the MGF1 digest setter flag isn't set (i.e. the caller can't specify a custom
                // set of MGF1 digests), fall back to the previous behaviour: add all "primary"
                // digests as MGF1 digests, except the default MGF1 digest (since it was already
                // added during initialization).
                if (!getMgf1DigestSetterFlag()) {
                    final int defaultMgf1Digest = KeyProperties.Digest.toKeymaster(
                            DEFAULT_MGF1_DIGEST);
                    ArrayUtils.forEach(mKeymasterDigests, (digest) -> {
                        if (digest != defaultMgf1Digest) {
                            params.add(KeyStore2ParameterUtils.makeEnum(
                                    KeymasterDefs.KM_TAG_RSA_OAEP_MGF_DIGEST, digest));
                        }
                    });
                }
            }
        });
        ArrayUtils.forEach(mKeymasterSignaturePaddings, (padding) -> {
            params.add(KeyStore2ParameterUtils.makeEnum(
                    KeymasterDefs.KM_TAG_PADDING, padding
            ));
        });
        ArrayUtils.forEach(mKeymasterDigests, (digest) -> {
            params.add(KeyStore2ParameterUtils.makeEnum(
                    KeymasterDefs.KM_TAG_DIGEST, digest
            ));
        });

        KeyStore2ParameterUtils.addUserAuthArgs(params, mSpec);

        if (mSpec.getKeyValidityStart() != null) {
            params.add(KeyStore2ParameterUtils.makeDate(
                    KeymasterDefs.KM_TAG_ACTIVE_DATETIME, mSpec.getKeyValidityStart()
            ));
        }
        if (mSpec.getKeyValidityForOriginationEnd() != null) {
            params.add(KeyStore2ParameterUtils.makeDate(
                    KeymasterDefs.KM_TAG_ORIGINATION_EXPIRE_DATETIME,
                    mSpec.getKeyValidityForOriginationEnd()
            ));
        }
        if (mSpec.getKeyValidityForConsumptionEnd() != null) {
            params.add(KeyStore2ParameterUtils.makeDate(
                    KeymasterDefs.KM_TAG_USAGE_EXPIRE_DATETIME,
                    mSpec.getKeyValidityForConsumptionEnd()
            ));
        }
        if (mSpec.getCertificateNotAfter() != null) {
            params.add(KeyStore2ParameterUtils.makeDate(
                    KeymasterDefs.KM_TAG_CERTIFICATE_NOT_AFTER,
                    mSpec.getCertificateNotAfter()
            ));
        }
        if (mSpec.getCertificateNotBefore() != null) {
            params.add(KeyStore2ParameterUtils.makeDate(
                    KeymasterDefs.KM_TAG_CERTIFICATE_NOT_BEFORE,
                    mSpec.getCertificateNotBefore()
            ));
        }
        if (mSpec.getCertificateSerialNumber() != null) {
            params.add(KeyStore2ParameterUtils.makeBignum(
                    KeymasterDefs.KM_TAG_CERTIFICATE_SERIAL,
                    mSpec.getCertificateSerialNumber()
            ));
        }
        if (mSpec.getCertificateSubject() != null) {
            params.add(KeyStore2ParameterUtils.makeBytes(
                    KeymasterDefs.KM_TAG_CERTIFICATE_SUBJECT,
                    mSpec.getCertificateSubject().getEncoded()
            ));
        }
        if (mSpec.getMaxUsageCount() != KeyProperties.UNRESTRICTED_USAGE_COUNT) {
            params.add(KeyStore2ParameterUtils.makeInt(
                    KeymasterDefs.KM_TAG_USAGE_COUNT_LIMIT,
                    mSpec.getMaxUsageCount()
            ));
        }

        addAlgorithmSpecificParameters(params);

        if (mSpec.isUniqueIdIncluded()) {
            params.add(KeyStore2ParameterUtils.makeBool(KeymasterDefs.KM_TAG_INCLUDE_UNIQUE_ID));
        }

        addAttestationParameters(params);

        return params;
    }

    private static boolean getMgf1DigestSetterFlag() {
        try {
            return Flags.mgf1DigestSetterV2();
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot read MGF1 Digest setter flag value", e);
            return false;
        }
    }

    private void addAlgorithmSpecificParameters(List<KeyParameter> params) {
        switch (mKeymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_RSA:
                params.add(KeyStore2ParameterUtils.makeLong(
                        KeymasterDefs.KM_TAG_RSA_PUBLIC_EXPONENT, mRSAPublicExponent
                ));
                break;
            case KeymasterDefs.KM_ALGORITHM_EC:
                break;
            case KeyProperties.KM_ALGORITHM_ML_DSA:
                // TODO(b/462036047): Use KeymasterDefs constants when KeyMint V5 is frozen.
                params.add(
                        KeyStore2ParameterUtils.makeEnum(
                                KeyProperties.KM_TAG_ML_DSA_VARIANT, mMlDsaVariantTag));
                break;
            default:
                throw new ProviderException("Unsupported algorithm: " + mKeymasterAlgorithm);
        }
    }

    private static int getDefaultKeySize(int keymasterAlgorithm) {
        switch (keymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_EC:
                return EC_DEFAULT_KEY_SIZE;
            case KeymasterDefs.KM_ALGORITHM_RSA:
                return RSA_DEFAULT_KEY_SIZE;
            // TODO(b/462036047): Use KeymasterDefs constant when KeyMint V5 is frozen.
            case KeyProperties.KM_ALGORITHM_ML_DSA:
                // Android Keystore and KeyMint do not use a key size for ML-DSA, so return an
                // sentinel value.
                return NO_KEY_SIZE;
            default:
                throw new ProviderException("Unsupported algorithm: " + keymasterAlgorithm);
        }
    }

    private static void checkValidKeySize(
            int keymasterAlgorithm,
            int keySize,
            boolean isStrongBoxBacked,
            String mEcCurveName)
            throws InvalidAlgorithmParameterException {
        switch (keymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_EC:
                if (isStrongBoxBacked && keySize != 256) {
                    throw new InvalidAlgorithmParameterException(
                            "Unsupported StrongBox EC key size: "
                                    + keySize + " bits. Supported: 256");
                }
                if (isStrongBoxBacked && isCurve25519(mEcCurveName)) {
                    throw new InvalidAlgorithmParameterException(
                            "Unsupported StrongBox EC: " + mEcCurveName);
                }
                if (!SUPPORTED_EC_CURVE_SIZES.contains(keySize)) {
                    throw new InvalidAlgorithmParameterException("Unsupported EC key size: "
                            + keySize + " bits. Supported: " + SUPPORTED_EC_CURVE_SIZES);
                }
                break;
            case KeymasterDefs.KM_ALGORITHM_RSA:
                if (keySize < RSA_MIN_KEY_SIZE || keySize > RSA_MAX_KEY_SIZE) {
                    throw new InvalidAlgorithmParameterException("RSA key size must be >= "
                            + RSA_MIN_KEY_SIZE + " and <= " + RSA_MAX_KEY_SIZE);
                }
                break;
            // TODO(b/462036047): Use KeymasterDefs constant when KeyMint V5 is frozen.
            case KeyProperties.KM_ALGORITHM_ML_DSA:
                // Key size is not needed for ML-DSA, so the provided key size is ignored.
                break;
            default:
                throw new ProviderException("Unsupported algorithm: " + keymasterAlgorithm);
        }
    }

    private void validateDigests() throws InvalidAlgorithmParameterException {
        switch (mKeymasterAlgorithm) {
            case KeymasterDefs.KM_ALGORITHM_EC, KeymasterDefs.KM_ALGORITHM_RSA -> {}
            case KeyProperties.KM_ALGORITHM_ML_DSA -> {
                // TODO(b/462036047): Use KeymasterDefs constant when KeyMint V5 is frozen.
                if (mSpec.isDigestsSpecified()) {
                    // Digests don't need to be explicitly specified for ML-DSA. If digests are
                    // specified, check that there is exactly one and it's DIGEST_NONE.
                    if (mSpec.getDigests().length != 1
                            || mSpec.getDigests()[0] != KeyProperties.DIGEST_NONE) {
                        throw new InvalidAlgorithmParameterException(
                                "Unsupported digest(s): "
                                        + Arrays.asList(mSpec.getDigests())
                                        + ". For ML-DSA, exactly one digest must be specified and"
                                        + " it must be DIGEST_NONE.");
                    }
                }
            }
            default -> throw new ProviderException("Unsupported algorithm: " + mKeymasterAlgorithm);
        }
    }

    private boolean isPropertyEmptyOrUnknown(String property) {
        return TextUtils.isEmpty(property) || property.equals(Build.UNKNOWN);
    }
}
