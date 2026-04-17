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

package android.os.storage.operations.targets;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Abstract base class for the target of a file operation. */
@FlaggedApi(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
@RavenwoodKeepWholeClass
public abstract class OperationTarget {
    private static final String TAG = "OperationTarget";

    /** @hide */
    static final String KEY_TARGET_TYPE = "key_target_type";

    /**
     * Singleton representing an error in unparceling.
     *
     * @hide
     */
    static final @NonNull OperationTarget INVALID_TARGET =
            new OperationTarget() {
                @Override
                public int getTargetType() {
                    return TYPE_INVALID;
                }

                @Override
                public String toString() {
                    return "INVALID_TARGET";
                }

                @Override
                public boolean isValid() {
                    return false;
                }
            };

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TYPE_INVALID, TYPE_PCC})
    public @interface TargetType {}

    /**
     * Type identifier for {@link #getErrorTarget()}.
     *
     * @hide
     */
    public static final @TargetType int TYPE_INVALID = -1;

    /**
     * Type identifier of {@link PccTarget}.
     *
     * @hide
     */
    public static final @TargetType int TYPE_PCC = 1;

    /**
     * Default constructor.
     *
     * @hide
     */
    OperationTarget() {}

    /**
     * Reconstructs an {@link OperationTarget} from a Bundle.
     *
     * @param b The Bundle containing the target data.
     * @hide
     */
    OperationTarget(@NonNull Bundle b) {}

    /**
     * Returns the target type associated with this class.
     *
     * @hide
     */
    public abstract @TargetType int getTargetType();

    /**
     * Obtain a {@link Bundle} describing this object populated with data.
     *
     * @return a {@link Bundle} containing the data that represents this object.
     * @hide
     */
    @NonNull
    Bundle getDataBundle() {
        Bundle b = new Bundle();
        b.putInt(KEY_TARGET_TYPE, getTargetType());
        return b;
    }

    /**
     * Returns a human-readable representation of this target.
     *
     * <p>Concrete implementations should return a string that describes the target in a way that is
     * useful for debugging and logging (e.g., including key paths or identifiers).
     *
     * <p><b>Note:</b> This string is intended for diagnostic purposes only and should not be parsed
     * or relied upon as a stable identifier for the files or the target configuration.
     */
    @Override
    @NonNull
    public abstract String toString();

    /**
     * Performs a robust syntactic validation of the target to ensure it is well-formed.
     *
     * <p>This method acts as a "cheap" fail-fast mechanism to intercept invalid or malicious
     * requests—such as those attempting path traversal—before they are added to the operation
     * queue.
     *
     * <p><b>Implementation Requirements:</b>
     *
     * <ul>
     *   <li><b>Syntactic Only:</b> Validation must be performed purely on the path's syntax (e.g.,
     *       using {@link java.nio.file.Path} or regex) and must not perform any blocking disk I/O.
     *   <li><b>Path Traversal Protection:</b> Subclasses should use normalization to detect and
     *       reject traversal elements like ".." or "." that could allow escaping the intended
     *       directory.
     *   <li><b>Unsafe Character Filtering:</b> Implementations must check for and reject invisible
     *       or control characters that could be used for path manipulation or obfuscation.
     *   <li><b>Relativity:</b> Depending on the target type, implementations must enforce whether
     *       paths are relative or absolute to prevent unauthorized filesystem access.
     * </ul>
     *
     * <p><b>Security Note:</b> This check is not authoritative. Because the system server often
     * lacks visibility into specific application storage contexts, the final ownership and
     * permission verification is performed by the specific processor during execution.
     *
     * @return {@code true} if the target configuration is syntactically valid; {@code false}
     *     otherwise.
     * @hide
     */
    public abstract boolean isValid();

    /**
     * Creates an {@link OperationTarget} instance from a {@link Bundle}.
     *
     * <p>This method is used during unparceling to reconstruct the appropriate subclass of {@code
     * OperationTarget} based on the type information stored in the bundle.
     *
     * @param bundle The bundle containing the target data.
     * @return The reconstructed {@link OperationTarget}, or {@link #INVALID_TARGET} if the bundle
     *     is null or invalid.
     * @hide
     */
    @NonNull
    public static OperationTarget createTargetFromBundle(@Nullable Bundle bundle) {
        if (bundle == null) {
            Log.e(TAG, "Null bundle");
            return INVALID_TARGET;
        }
        int type = bundle.getInt(KEY_TARGET_TYPE, TYPE_INVALID);
        try {
            switch (type) {
                case TYPE_PCC:
                    return new PccTarget(bundle);
                case TYPE_INVALID:
                default:
                    return INVALID_TARGET;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating target", e);
            return INVALID_TARGET;
        }
    }

    /**
     * @return a singleton {@link OperationTarget} used for indicating an error in unparceling.
     * @hide
     */
    @NonNull
    public static OperationTarget getInvalidTarget() {
        return INVALID_TARGET;
    }
}
