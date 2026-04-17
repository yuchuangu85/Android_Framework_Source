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

package android.os.storage.operations.sources;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Abstract base class for the source of a file operation. */
@FlaggedApi(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
@RavenwoodKeepWholeClass
public abstract class OperationSource {
    private static final String TAG = "OperationSource";

    /** @hide */
    static final String KEY_SOURCE_TYPE = "key_source_type";

    /**
     * Singleton representing an error in unparceling.
     *
     * @hide
     */
    static final @NonNull OperationSource INVALID_SOURCE =
            new OperationSource() {
                @Override
                public int getSourceType() {
                    return TYPE_INVALID;
                }

                @Override
                public String toString() {
                    return "INVALID_SOURCE";
                }

                @Override
                public boolean isValid() {
                    return false;
                }
            };

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TYPE_INVALID, TYPE_APP_DATA_FILE})
    public @interface SourceType {}

    /**
     * Type identifier for {@link #getInvalidSource()}.
     *
     * @hide
     */
    public static final @SourceType int TYPE_INVALID = -1;

    /**
     * Type identifier of {@link AppDataFileSource}.
     *
     * @hide
     */
    public static final @SourceType int TYPE_APP_DATA_FILE = 1;

    /**
     * Default constructor.
     *
     * @hide
     */
    OperationSource() {}

    /**
     * Reconstructs an {@link OperationSource} from a Bundle.
     *
     * @param b The Bundle containing the source data.
     * @hide
     */
    OperationSource(@NonNull Bundle b) {}

    /**
     * Returns the source type associated with this class.
     *
     * @hide
     */
    public abstract @SourceType int getSourceType();

    /**
     * Obtain a {@link Bundle} describing this object populated with data.
     *
     * @return a {@link Bundle} containing the data that represents this object.
     * @hide
     */
    @NonNull
    Bundle getDataBundle() {
        Bundle b = new Bundle();
        b.putInt(KEY_SOURCE_TYPE, getSourceType());
        return b;
    }

    /**
     * Returns a human-readable representation of this source.
     *
     * <p>Concrete implementations should return a string that describes the source in a way that is
     * useful for debugging and logging (e.g., including key paths or identifiers).
     *
     * <p><b>Note:</b> This string is intended for diagnostic purposes only and should not be parsed
     * or relied upon as a stable identifier for the files or the source configuration.
     *
     * @return A debugging string representation of the source.
     */
    @Override
    @NonNull
    public abstract String toString();

    /**
     * Performs robust syntactic validation of the source to ensure it is well-formed.
     *
     * <p>This method is intended as a relatively "cheap" fail-fast mechanism to identify invalid or
     * malicious operations (such as path traversal attempts) before they are queued for processing.
     *
     * <p><b>Implementation Requirements:</b>
     *
     * <ul>
     *   <li><b>No Disk I/O:</b> Implementations must rely on purely syntactic checks (e.g., using
     *       the {@link java.nio.file.Path} API) and must not perform blocking filesystem queries.
     *   <li><b>Security Constraints:</b> Subclasses should validate that paths are absolute (or
     *       relative, as required), resolve all redundant segments like ".." or "." to prevent
     *       traversal, and check for unsafe control characters.
     * </ul>
     *
     * <p><b>Note:</b> This is not an authoritative security check. Because the system server may
     * not have visibility into the caller's private files, the actual ownership and permission
     * validation occurs later within the specific Processor handling the operation.
     *
     * @return {@code true} if the source is syntactically valid and safe to queue; {@code false}
     *     otherwise.
     * @hide
     */
    public abstract boolean isValid();

    /**
     * Creates an {@link OperationSource} instance from a {@link Bundle}.
     *
     * <p>This method is used during unparceling to reconstruct the appropriate subclass of {@code
     * OperationSource} based on the type information stored in the bundle.
     *
     * @param bundle The bundle containing the source data.
     * @return The reconstructed {@link OperationSource}, or {@link #INVALID_SOURCE} if the bundle
     *     is null or invalid.
     * @hide
     */
    @NonNull
    public static OperationSource createSourceFromBundle(@Nullable Bundle bundle) {
        if (bundle == null) {
            Log.e(TAG, "Null bundle");
            return INVALID_SOURCE;
        }
        int type = bundle.getInt(KEY_SOURCE_TYPE, TYPE_INVALID);
        try {
            switch (type) {
                case TYPE_APP_DATA_FILE:
                    return new AppDataFileSource(bundle);
                case TYPE_INVALID:
                default:
                    return INVALID_SOURCE;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating source", e);
            return INVALID_SOURCE;
        }
    }

    /**
     * @return a singleton {@link OperationSource} used for indicating an error in unparceling.
     * @hide
     */
    @NonNull
    public static OperationSource getInvalidSource() {
        return INVALID_SOURCE;
    }
}
