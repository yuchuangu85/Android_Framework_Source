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
package android.health.connect.device;

import static android.health.connect.datatypes.Device.DEVICE_TYPE_CHEST_STRAP;
import static android.health.connect.datatypes.Device.DEVICE_TYPE_CONSUMER_MEDICAL_DEVICE;
import static android.health.connect.datatypes.Device.DEVICE_TYPE_FITNESS_BAND;
import static android.health.connect.datatypes.Device.DEVICE_TYPE_FITNESS_EQUIPMENT;
import static android.health.connect.datatypes.Device.DEVICE_TYPE_FITNESS_MACHINE;
import static android.health.connect.datatypes.Device.DEVICE_TYPE_GLASSES;
import static android.health.connect.datatypes.Device.DEVICE_TYPE_HEAD_MOUNTED;
import static android.health.connect.datatypes.Device.DEVICE_TYPE_HEARABLE;
import static android.health.connect.datatypes.Device.DEVICE_TYPE_METER;
import static android.health.connect.datatypes.Device.DEVICE_TYPE_PHONE;
import static android.health.connect.datatypes.Device.DEVICE_TYPE_PORTABLE_COMPUTER;
import static android.health.connect.datatypes.Device.DEVICE_TYPE_RING;
import static android.health.connect.datatypes.Device.DEVICE_TYPE_SCALE;
import static android.health.connect.datatypes.Device.DEVICE_TYPE_SMART_DISPLAY;
import static android.health.connect.datatypes.Device.DEVICE_TYPE_UNKNOWN;
import static android.health.connect.datatypes.Device.DEVICE_TYPE_WATCH;

import static java.util.Map.entry;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A utility class for validating and matching Synthetic Package Names (SPNs). See {@link
 * com.android.server.healthconnect.common.metadata.SyntheticPackageNameCreator} for a detailed
 * explanation of the pattern.
 *
 * @hide
 */
public class SyntheticPackageNameMatcher {
    // The standard Android reverse-DNS prefix used for all SPNs.
    private static final String PACKAGE_PREFIX = "com.android.healthconnect";

    // A fixed prefix is prepended to the UUID segment to ensure the segment starts with a letter,
    // complying with Android package name rules. The two prefixes differentiate the types.
    private static final char CANONICAL_UUID_SEGMENT_PREFIX = 'd';
    private static final char MASKED_UUID_SEGMENT_PREFIX = 'j';

    /**
     * A fixed, immutable mapping from {@code @DeviceType} to their corresponding string
     * representations used in SPNs. This mapping MUST NOT change to guarantee the stability and
     * reproducibility of SPNs across versions.
     *
     * <p>On new device types, add them to {@link
     * android.healthconnect.testing.cts.TestUtils#DEVICE_TYPE_TO_DISPLAY_NAME} for cts tests as
     * well.
     */
    public static final Map<Integer, String> DEVICE_TYPE_TO_DISPLAY_NAME =
            Map.ofEntries(
                    entry(DEVICE_TYPE_UNKNOWN, "unknown"),
                    entry(DEVICE_TYPE_WATCH, "watch"),
                    entry(DEVICE_TYPE_PHONE, "phone"),
                    entry(DEVICE_TYPE_SCALE, "scale"),
                    entry(DEVICE_TYPE_RING, "ring"),
                    entry(DEVICE_TYPE_HEAD_MOUNTED, "head_mounted"),
                    entry(DEVICE_TYPE_FITNESS_BAND, "fitness_band"),
                    entry(DEVICE_TYPE_CHEST_STRAP, "chest_strap"),
                    entry(DEVICE_TYPE_SMART_DISPLAY, "smart_display"),
                    entry(DEVICE_TYPE_CONSUMER_MEDICAL_DEVICE, "consumer_medical_device"),
                    entry(DEVICE_TYPE_GLASSES, "glasses"),
                    entry(DEVICE_TYPE_HEARABLE, "hearable"),
                    entry(DEVICE_TYPE_FITNESS_MACHINE, "fitness_machine"),
                    entry(DEVICE_TYPE_FITNESS_EQUIPMENT, "fitness_equipment"),
                    entry(DEVICE_TYPE_PORTABLE_COMPUTER, "portable_computer"),
                    entry(DEVICE_TYPE_METER, "meter"));

    // A standard UUID (128 bits) represented as a hexadecimal string has 32 characters.
    public static final int UUID_HEX_LENGTH = 32;

    // A compiled regular expression pattern used to validate if a string is a SPN.
    private static final Pattern IS_SPN_PATTERN;

    // The original pattern validates if the entire input is a SPN - we need to adjust it
    // to be able to find SPNs in a broader message
    private static final Pattern CONTAINS_SPN_PATTERN;

    static {
        // 1. Build the allowed device types segment (e.g., "phone|watch|scale")
        // Note: This assumes the display names do not contain regex metacharacters.
        String typesRegexSegment = String.join("|", DEVICE_TYPE_TO_DISPLAY_NAME.values());

        // 2. Construct the full regex: ^PREFIX\.(type1|type2|...)\.(d|j)[0-9a-f]{32}$
        // We validate against lowercase hexadecimal characters, which matches the output
        // behavior of Java's UUID.toString().
        // ^           - Start of the string
        // PREFIX\.    - The package prefix followed by a literal dot
        // (...)\.     - The device type alternation group followed by a literal dot
        // (d|j)       - The prefix character (Canonical or Masked)
        // [0-9a-f]    - Hexadecimal characters (lowercase)
        // {32}        - Exactly 32 times
        // $           - End of the string
        String containsSpnRegex =
                String.format(
                        Locale.US,
                        "%s\\.(%s)\\.(%s|%s)[0-9a-f]{32}",
                        PACKAGE_PREFIX,
                        typesRegexSegment,
                        CANONICAL_UUID_SEGMENT_PREFIX,
                        MASKED_UUID_SEGMENT_PREFIX);
        CONTAINS_SPN_PATTERN = Pattern.compile(containsSpnRegex);

        String isSpnRegex = "^" + containsSpnRegex + "$";
        IS_SPN_PATTERN = Pattern.compile(isSpnRegex);
    }

    /**
     * Checks if the provided string is a structurally valid Synthetic Package Name (SPN), either
     * Canonical or Masked.
     *
     * @param candidate The string to check.
     * @return {@code true} if the string matches the SPN pattern, {@code false} otherwise.
     */
    public static boolean matches(@NonNull String candidate) {
        return IS_SPN_PATTERN.matcher(candidate).matches();
    }

    /**
     * Checks if the provided string is specifically a Masked SPN.
     *
     * @param candidate The string to check.
     * @return {@code true} if the string is a Masked SPN, {@code false} otherwise.
     */
    public static boolean matchesMasked(@NonNull String candidate) {
        return matchesSpnPrefix(candidate, MASKED_UUID_SEGMENT_PREFIX);
    }

    /**
     * Checks if the provided string is specifically a Canonical SPN.
     *
     * @param candidate The string to check.
     * @return {@code true} if the string is a Canonical SPN, {@code false} otherwise.
     */
    public static boolean matchesCanonical(@NonNull String candidate) {
        return matchesSpnPrefix(candidate, CANONICAL_UUID_SEGMENT_PREFIX);
    }

    /**
     * Finds any canonical SPNs in {@code message} and replaces them with a redacted version that
     * removes the hash.
     *
     * @param message The message to redact
     * @return A redacted version of {@code message}, or the original if it didn't contain any
     *     canonical SPNs
     */
    @Nullable
    public static String replaceAllCanonicalIn(@Nullable String message) {
        if (message == null) {
            return null;
        }

        Matcher spnMatcher = CONTAINS_SPN_PATTERN.matcher(message);
        StringBuilder builder = new StringBuilder();
        while (spnMatcher.find()) {
            String fullSpn = spnMatcher.group();
            if (matchesCanonical(fullSpn)) {
                String redactedSpn = fullSpn.substring(0, fullSpn.lastIndexOf('.'));
                spnMatcher.appendReplacement(builder, redactedSpn);
            }
        }
        spnMatcher.appendTail(builder);
        return builder.toString();
    }

    /**
     * Checks if the SPN candidate is valid and matches the expected prefix for the last segment.
     */
    private static boolean matchesSpnPrefix(@NonNull String candidate, char expectedPrefix) {
        if (!matches(candidate)) return false;

        int lastSegmentIndex = candidate.lastIndexOf(".");

        return candidate.charAt(lastSegmentIndex + 1) == expectedPrefix;
    }
}
