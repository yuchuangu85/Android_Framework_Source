/*
 * Copyright (C) 2026 The Android Open Source Project
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

package android.util.apk;

import static android.util.apk.ApkSignatureSchemeV3Verifier.APK_SIGNATURE_SCHEME_V32_BLOCK_ID;
import static android.util.apk.ApkSignatureSchemeV3Verifier.APK_SIGNATURE_SCHEME_V3_BLOCK_ID;

import android.annotation.IntDef;
import android.content.pm.SigningDetails.SignatureSchemeMinorVersion;
import android.content.pm.SigningDetails.SignatureSchemeVersion;
import android.security.apksigverify.ApkSigVerifyProtoEnums;

import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Logs metrics for APK signature verification.
 *
 * @hide
 */
public class ApkSignatureVerifierMetrics {
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"VERIFICATION_"}, value = {
            VerificationResult.VERIFICATION_SUCCESS,
            VerificationResult.VERIFICATION_MALFORMED_BLOCK,
            VerificationResult.VERIFICATION_MALFORMED_SIGNER,
            VerificationResult.VERIFICATION_TOO_MANY_SIGNERS,
            VerificationResult.VERIFICATION_NO_CONTENT_DIGESTS,
            VerificationResult.VERIFICATION_INTEGRITY_MISMATCH,
            VerificationResult.VERIFICATION_NO_SIGNATURES,
            VerificationResult.VERIFICATION_NO_SUPPORTED_SIGNATURES,
            VerificationResult.VERIFICATION_MISSING_SIGNATURE_ALGORITHM,
            VerificationResult.VERIFICATION_MALFORMED_SIGNATURE,
            VerificationResult.VERIFICATION_SIGNATURE_VERIFICATION_FAILED,
            VerificationResult.VERIFICATION_SIGNATURE_ALGORITHM_MISMATCH,
            VerificationResult.VERIFICATION_INCONSISTENT_DIGESTS,
            VerificationResult.VERIFICATION_MALFORMED_CERTIFICATE,
            VerificationResult.VERIFICATION_NO_CERTIFICATES,
            VerificationResult.VERIFICATION_PUBLIC_KEY_MISMATCH,
            VerificationResult.VERIFICATION_SIGNER_SDK_MISMATCH,
            VerificationResult.VERIFICATION_MALFORMED_ATTRIBUTES,
            VerificationResult.VERIFICATION_DUPLICATE_POR,
            VerificationResult.VERIFICATION_MALFORMED_POR,
            VerificationResult.VERIFICATION_POR_INTEGRITY_FAILURE,
            VerificationResult.VERIFICATION_POR_CERT_MISMATCH,
            VerificationResult.VERIFICATION_PQC_SINGLE_SIGNER,
            VerificationResult.VERIFICATION_V32_INVALID_SIGNER_COUNT,
            VerificationResult.VERIFICATION_V32_ALGO_TYPE_COLLISION,
            VerificationResult.VERIFICATION_V32_POR_MISSING,
            VerificationResult.VERIFICATION_V32_POR_MISMATCH,
            VerificationResult.VERIFICATION_V32_POR_CAPABILITY_MISMATCH,
            VerificationResult.VERIFICATION_V32_SDK_RANGE_MISMATCH,
            VerificationResult.VERIFICATION_V32_BLOCK_STRIPPED,
            VerificationResult.VERIFICATION_V32_SDK_ATTR_MISMATCH,
            VerificationResult.VERIFICATION_V32_KEY_REUSE_INSTALLED_DATA,
            VerificationResult.VERIFICATION_V32_KEY_REUSE_ROLLBACK,
            VerificationResult.VERIFICATION_V32_KEY_REUSE,
            VerificationResult.VERIFICATION_V32_MISSING_CLASSICAL_INSTALLED_DATA,
            VerificationResult.VERIFICATION_V32_MISSING_CLASSICAL_ROLLBACK,
            VerificationResult.VERIFICATION_V32_MISSING_CLASSICAL,
            VerificationResult.VERIFICATION_V32_MAX_ATTR_WITHOUT_MIN_ATTR
    })
    public @interface VerificationResult {
        int VERIFICATION_SUCCESS = ApkSigVerifyProtoEnums.VERIFICATION_SUCCESS;
        int VERIFICATION_MALFORMED_BLOCK = ApkSigVerifyProtoEnums.VERIFICATION_MALFORMED_BLOCK;
        int VERIFICATION_MALFORMED_SIGNER = ApkSigVerifyProtoEnums.VERIFICATION_MALFORMED_SIGNER;
        int VERIFICATION_TOO_MANY_SIGNERS = ApkSigVerifyProtoEnums.VERIFICATION_TOO_MANY_SIGNERS;
        int VERIFICATION_NO_CONTENT_DIGESTS =
                ApkSigVerifyProtoEnums.VERIFICATION_NO_CONTENT_DIGESTS;
        int VERIFICATION_INTEGRITY_MISMATCH =
                ApkSigVerifyProtoEnums.VERIFICATION_INTEGRITY_MISMATCH;
        int VERIFICATION_NO_SIGNATURES = ApkSigVerifyProtoEnums.VERIFICATION_NO_SIGNATURES;
        int VERIFICATION_NO_SUPPORTED_SIGNATURES =
                ApkSigVerifyProtoEnums.VERIFICATION_NO_SUPPORTED_SIGNATURES;
        int VERIFICATION_MISSING_SIGNATURE_ALGORITHM =
                ApkSigVerifyProtoEnums.VERIFICATION_MISSING_SIGNATURE_ALGORITHM;
        int VERIFICATION_MALFORMED_SIGNATURE =
                ApkSigVerifyProtoEnums.VERIFICATION_MALFORMED_SIGNATURE;
        int VERIFICATION_SIGNATURE_VERIFICATION_FAILED =
                ApkSigVerifyProtoEnums.VERIFICATION_SIGNATURE_VERIFICATION_FAILED;
        int VERIFICATION_SIGNATURE_ALGORITHM_MISMATCH =
                ApkSigVerifyProtoEnums.VERIFICATION_SIGNATURE_ALGORITHM_MISMATCH;
        int VERIFICATION_INCONSISTENT_DIGESTS =
                ApkSigVerifyProtoEnums.VERIFICATION_INCONSISTENT_DIGESTS;
        int VERIFICATION_MALFORMED_CERTIFICATE =
                ApkSigVerifyProtoEnums.VERIFICATION_MALFORMED_CERTIFICATE;
        int VERIFICATION_NO_CERTIFICATES = ApkSigVerifyProtoEnums.VERIFICATION_NO_CERTIFICATES;
        int VERIFICATION_PUBLIC_KEY_MISMATCH =
                ApkSigVerifyProtoEnums.VERIFICATION_PUBLIC_KEY_MISMATCH;
        int VERIFICATION_SIGNER_SDK_MISMATCH =
                ApkSigVerifyProtoEnums.VERIFICATION_SIGNER_SDK_MISMATCH;
        int VERIFICATION_MALFORMED_ATTRIBUTES =
                ApkSigVerifyProtoEnums.VERIFICATION_MALFORMED_ATTRIBUTES;
        int VERIFICATION_DUPLICATE_POR = ApkSigVerifyProtoEnums.VERIFICATION_DUPLICATE_POR;
        int VERIFICATION_MALFORMED_POR = ApkSigVerifyProtoEnums.VERIFICATION_MALFORMED_POR;
        int VERIFICATION_POR_INTEGRITY_FAILURE =
                ApkSigVerifyProtoEnums.VERIFICATION_POR_INTEGRITY_FAILURE;
        int VERIFICATION_POR_CERT_MISMATCH = ApkSigVerifyProtoEnums.VERIFICATION_POR_CERT_MISMATCH;
        int VERIFICATION_PQC_SINGLE_SIGNER = ApkSigVerifyProtoEnums.VERIFICATION_PQC_SINGLE_SIGNER;
        int VERIFICATION_V32_INVALID_SIGNER_COUNT =
                ApkSigVerifyProtoEnums.VERIFICATION_V32_INVALID_SIGNER_COUNT;
        int VERIFICATION_V32_ALGO_TYPE_COLLISION =
                ApkSigVerifyProtoEnums.VERIFICATION_V32_ALGO_TYPE_COLLISION;
        int VERIFICATION_V32_POR_MISSING = ApkSigVerifyProtoEnums.VERIFICATION_V32_POR_MISSING;
        int VERIFICATION_V32_POR_MISMATCH = ApkSigVerifyProtoEnums.VERIFICATION_V32_POR_MISMATCH;
        int VERIFICATION_V32_POR_CAPABILITY_MISMATCH =
                ApkSigVerifyProtoEnums.VERIFICATION_V32_POR_CAPABILITY_MISMATCH;
        int VERIFICATION_V32_SDK_RANGE_MISMATCH =
                ApkSigVerifyProtoEnums.VERIFICATION_V32_SDK_RANGE_MISMATCH;
        int VERIFICATION_V32_BLOCK_STRIPPED =
                ApkSigVerifyProtoEnums.VERIFICATION_V32_BLOCK_STRIPPED;
        int VERIFICATION_V32_SDK_ATTR_MISMATCH =
                ApkSigVerifyProtoEnums.VERIFICATION_V32_SDK_ATTR_MISMATCH;
        int VERIFICATION_V32_KEY_REUSE_INSTALLED_DATA =
                ApkSigVerifyProtoEnums.VERIFICATION_V32_KEY_REUSE_INSTALLED_DATA;
        int VERIFICATION_V32_KEY_REUSE_ROLLBACK =
                ApkSigVerifyProtoEnums.VERIFICATION_V32_KEY_REUSE_ROLLBACK;
        int VERIFICATION_V32_KEY_REUSE = ApkSigVerifyProtoEnums.VERIFICATION_V32_KEY_REUSE;
        int VERIFICATION_V32_MISSING_CLASSICAL_INSTALLED_DATA =
                ApkSigVerifyProtoEnums.VERIFICATION_V32_MISSING_CLASSICAL_INSTALLED_DATA;
        int VERIFICATION_V32_MISSING_CLASSICAL_ROLLBACK =
                ApkSigVerifyProtoEnums.VERIFICATION_V32_MISSING_CLASSICAL_ROLLBACK;
        int VERIFICATION_V32_MISSING_CLASSICAL =
                ApkSigVerifyProtoEnums.VERIFICATION_V32_MISSING_CLASSICAL;
        int VERIFICATION_V32_MAX_ATTR_WITHOUT_MIN_ATTR =
                ApkSigVerifyProtoEnums.VERIFICATION_V32_MAX_ATTR_WITHOUT_MIN_ATTR;
    }

    /**
     * Logs a successful APK signature verification.
     *
     * @param blockId            the ID of the block that was successfully verified
     * @param blockMinSdkVersion the minimum SDK version targeted by the block
     * @param blockMaxSdkVersion the maximum SDK version targeted by the block
     * @param sigAlgorithmId     the signature algorithm ID used to sign the block
     */
    public static void logSignatureVerificationSuccess(int blockId, int blockMinSdkVersion,
            int blockMaxSdkVersion, int sigAlgorithmId) {
        logApkSignatureVerificationReported(blockId, blockMinSdkVersion, blockMaxSdkVersion,
                sigAlgorithmId, false,
                false, ApkSigVerifyProtoEnums.VERIFICATION_SUCCESS);
    }

    /**
     * Logs a failure verifying the APK signature at the block level.
     *
     * <p>This method should be used when the failure prevents the verifier from parsing the
     * signer(s) within the block.
     *
     * @param blockId            the ID of the block that failed verification
     * @param verificationResult the {@link VerificationResult} expressing the cause of the failure
     */
    public static void logSignatureVerificationBlockFailure(int blockId,
            @VerificationResult int verificationResult) {
        logApkSignatureVerificationReported(blockId, 0, 0, 0, false, false, verificationResult);
    }

    /**
     * Logs a failure verifying the APK signature at the signer level.
     *
     * <p>This method should be used when the failure occurs after at least a portion of the signer
     * within the signature block was parsed.
     *
     * @param blockId            the ID of the block that failed verification
     * @param blockMinSdkVersion the minimum SDK version targeted by the signer, or 0 if it could
     *                           not be determined
     * @param blockMaxSdkVersion the maximum SDK version targeted by the signer, or 0 if it could
     *                           not be determined
     * @param sigAlgorithmId     the signature algorithm ID used by the signer, or 0 if it could not
     *                           be determined
     * @param verificationResult the {@link VerificationResult} expressing the cause of the failure
     */
    public static void logSignatureVerificationSignerFailure(int blockId, int blockMinSdkVersion,
            int blockMaxSdkVersion, int sigAlgorithmId,
            @VerificationResult int verificationResult) {
        logApkSignatureVerificationReported(blockId, blockMinSdkVersion, blockMaxSdkVersion,
                sigAlgorithmId, false, false, verificationResult);
    }

    /**
     * Logs a successful key change event; this can occur either if the signing key has been rotated
     * forward or has been rolled back as indicated by the provided booleans.
     *
     * <p>If the same value is provided for both {@code isRotation} and {@code isRollback}, this
     * method will immediately return without logging an event.
     *
     * @param majorSchemeVersion the major signing scheme version of the update package
     * @param minorSchemeVersion the minor signing scheme version of the update package
     * @param isRotation whether this signing key change event is a successful rotation
     * @param isRollback whether this signing key change event is a successful rollback
     */
    public static void logSigningKeyChangeSuccess(@SignatureSchemeVersion int majorSchemeVersion,
            @SignatureSchemeMinorVersion int minorSchemeVersion, boolean isRotation,
            boolean isRollback) {
        if (isRotation == isRollback) {
            return;
        }
        int blockId = getBlockIdFromSchemeVersion(majorSchemeVersion, minorSchemeVersion);
        logApkSignatureVerificationReported(blockId, 0, 0, 0, isRollback, isRotation,
                VerificationResult.VERIFICATION_SUCCESS);
    }

    /**
     * Logs a signing key policy failure where a signature based capability is requested, but the
     * requesting app's signing identity does not fully meet the requirements to be granted the
     * capability.
     *
     * <p>This method is primarily intended for cases where either the declaring or requesting app
     * is signed by a hybrid signing config. For instance, if an app is installed on device with
     * a hybrid signing identity, an update for that app must either maintain the same signing
     * identity or be signed with a new identity that has both hybrid signers in its lineage; if the
     * update is only signed by the PQC key from the hybrid config, then this method should be
     * called to log the update policy failure.
     *
     * @param majorSchemeVersion the major signing scheme version of the update package
     * @param minorSchemeVersion the minor signing scheme version of the update package
     * @param verificationResult the {@link VerificationResult} expressing the result of the event
     */
    public static void logSigningKeyPolicyFailure(@SignatureSchemeVersion int majorSchemeVersion,
            @SignatureSchemeMinorVersion int minorSchemeVersion,
            @VerificationResult int verificationResult) {
        int blockId = getBlockIdFromSchemeVersion(majorSchemeVersion, minorSchemeVersion);
        logApkSignatureVerificationReported(blockId, 0, 0, 0, false, false,
                verificationResult);
    }

    /**
     * Returns the signature block ID for the provided {@code majorSchemeVersion} and {@code
     * minorSchemeVersion}.
     */
    private static int getBlockIdFromSchemeVersion(@SignatureSchemeVersion int majorSchemeVersion,
            @SignatureSchemeMinorVersion int minorSchemeVersion) {
        switch (majorSchemeVersion) {
            case SignatureSchemeVersion.SIGNING_BLOCK_V3: {
                return switch (minorSchemeVersion) {
                    case SignatureSchemeMinorVersion.MINOR_VERSION_DEFAULT ->
                            APK_SIGNATURE_SCHEME_V3_BLOCK_ID;
                    case SignatureSchemeMinorVersion.MINOR_VERSION_32_HYBRID ->
                            APK_SIGNATURE_SCHEME_V32_BLOCK_ID;
                    default -> 0;
                };
            }
            default:
                return 0;
        }
    }

    /**
     * Logs a new {@code ApkSignatureVerificationReported} metric with the {@link
     * FrameworkStatsLog}.
     *
     * @param blockId            the ID of the block to log
     * @param blockMinSdkVersion the minimum SDK version targeted by the block, or 0 if it could not
     *                           be determined
     * @param blockMaxSdkVersion the maximum SDK version targeted by the block, or 0 if it could not
     *                           be determined
     * @param sigAlgorithmId     the signature algorithm ID used by th signer for this block, or 0
     *                           if it could not be determined
     * @param isRollback         whether the current event reflects a rollback of the APK's signing
     *                           key
     * @param isRotation         whether the current event reflects a rotation of the APK's signing
     *                           key
     * @param verificationResult the {@link VerificationResult} expressing the result of the event
     */
    private static void logApkSignatureVerificationReported(int blockId, int blockMinSdkVersion,
            int blockMaxSdkVersion, int sigAlgorithmId, boolean isRollback, boolean isRotation,
            @VerificationResult int verificationResult) {
        FrameworkStatsLog.write(FrameworkStatsLog.APK_SIGNATURE_VERIFICATION_REPORTED,
                blockId, blockMinSdkVersion, blockMaxSdkVersion, sigAlgorithmId, isRollback,
                isRotation, verificationResult);
    }
}
