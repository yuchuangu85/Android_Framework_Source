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
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.os.Bundle;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * A source representing a file within the calling application's private app data directory.
 *
 * <p>This source is restricted to files located inside the application's internal data directory
 * (as returned by {@link android.content.Context#getDataDir()}).
 *
 * <h3>Operation Rejection</h3>
 *
 * <p>To ensure system security and integrity, operations using an {@code AppDataFileSource} will be
 * <b>rejected</b> with {@link
 * android.os.storage.operations.FileOperationResult#ERROR_UNSUPPORTED_SOURCE} or {@link
 * android.os.storage.operations.FileOperationResult#ERROR_INVALID_REQUEST} if:
 *
 * <ul>
 *   <li>The provided path is <b>not absolute</b>.
 *   <li>The path contains <b>path traversal elements</b> (e.g., "..") or redundant segments.
 *   <li>The path contains <b>unsafe control characters</b> (e.g., null, newline).
 *   <li>The file is <b>outside</b> the application's internal data directory (CE or DE storage).
 *       Locations such as external storage or system directories are strictly prohibited.
 * </ul>
 *
 * <p><b>Example Usage:</b>
 *
 * <pre>{@code
 * // Obtain the application's data directory using Context
 * Context context = getApplicationContext();
 * File myDataFile = new File(context.getDataDir(), "subfolder/my_data.txt");
 *
 * // Construct the AppDataFileSource with the File object
 * AppDataFileSource source = new AppDataFileSource(myDataFile);
 *
 * // The source can now be used in storage operations
 * }</pre>
 *
 * @see android.content.Context#getDataDir()
 */
@FlaggedApi(android.app.privatecompute.flags.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
@RavenwoodKeepWholeClass
public final class AppDataFileSource extends OperationSource {
    private static final String KEY_PATH = "key_path";

    /**
     * Pattern to detect "suspicious" characters in file paths.
     *
     * <pre>
     * Matches Unicode categories:
     * - \p{C}: Other/Control characters (null, newline, surrogates, unassigned, etc.)
     * - \p{Cf}: Format characters (zero-width spaces, joiners, layout marks, etc.)
     * </pre>
     */
    private static final Pattern UNSAFE_PATH_PATTERN = Pattern.compile("[\\p{C}\\p{Cf}]");

    /**
     * Pattern to validate that the path points to an application's internal data directory.
     *
     * <pre>
     * Supports:
     * - /data/user/N/package/...
     * - /data/user_de/N/package/...
     *
     * Regex breakdown:
     * {@code ^/data/user(?:_de)?/} - Matches storage root for CE (/data/user/)
     *                                  or DE (/data/user_de/)
     * {@code \d+/} - Matches the user ID (e.g., "0/")
     * {@code [^/]+} - Matches the package name (e.g., "com.example.app")
     * {@code (/.*)?$} - Optional subpath within the package directory
     * </pre>
     *
     * <p>This allows the package root itself and any files or subdirectories within it, but rejects
     * the raw user root paths (e.g., /data/user/0/).
     */
    private static final Pattern DATA_DIR_PATTERN =
            Pattern.compile("^/data/user(?:_de)?/\\d+/[^/]+(/.*)?$");

    private final String mPath;

    /**
     * Creates a new source for the specified file.
     *
     * @param file The file to be used as a source. Must be within the application's data directory.
     */
    @SuppressLint("StreamFiles")
    public AppDataFileSource(@NonNull File file) {
        mPath = file.getAbsolutePath();
    }

    /**
     * Reconstructs an {@link AppDataFileSource} from a Bundle.
     *
     * @param b The Bundle containing the source data.
     * @hide
     */
    AppDataFileSource(@NonNull Bundle b) {
        super(b);
        mPath = b.getString(KEY_PATH);
    }

    /** @hide */
    @Override
    public int getSourceType() {
        return TYPE_APP_DATA_FILE;
    }

    /** @hide */
    @Override
    @NonNull
    Bundle getDataBundle() {
        Bundle b = super.getDataBundle();
        b.putString(KEY_PATH, mPath);
        return b;
    }

    /** Returns the file associated with this source. */
    @NonNull
    public File getFile() {
        return new File(mPath);
    }

    /**
     * Checks if the source path is valid and safe.
     *
     * <p>This performs a syntactic check to ensure the path is absolute, does not contain traversal
     * elements or unsafe characters, and resides within the application's internal data directory.
     *
     * @return {@code true} if the path is valid; {@code false} otherwise.
     * @hide
     */
    @Override
    public boolean isValid() {
        // Use Path API for robust syntactic validation (no disk I/O)
        final Path path = Paths.get(mPath);

        // 1. Enforce absolute path
        if (!path.isAbsolute()) {
            return false;
        }

        // 2. Detect Path Traversal (e.g. "/data/app/../other")
        // normalize() resolves ".." and ".". If the path changes, it contained
        // traversal/redundancy.
        // This is safer than mPath.contains("..") which incorrectly flags "file..name.txt".
        if (!path.normalize().equals(path)) {
            return false;
        }

        // 3. Check for invisible/control characters
        if (UNSAFE_PATH_PATTERN.matcher(mPath).find()) {
            return false;
        }

        // 4. Enforce that the path is within an application's internal data directory.
        if (!DATA_DIR_PATTERN.matcher(mPath).matches()) {
            return false;
        }

        return true;
    }

    /**
     * Returns a string representation of this source for debugging.
     *
     * @return A string containing the source type and path.
     */
    @Override
    @NonNull
    public String toString() {
        return "AppDataFileSource: " + mPath;
    }
}
