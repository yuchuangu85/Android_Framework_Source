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
package android.media.tv.extension.teletext;

import android.annotation.StringDef;

/**
 * @hide
 */
public final class TeletextConstants {
    @StringDef({
            KEY_IS_TELETEXT_AVAILABLE,
            KEY_IS_TELETEXT_RUNNING,
    })
    public @interface DataServiceSignalInfoKey {
    }

    // Boolean indicating if Teletext is available.
    public static final String KEY_IS_TELETEXT_AVAILABLE = "KEY_IS_TELETEXT_AVAILABLE";
    // Boolean indicating if the Teletext module is running.
    public static final String KEY_IS_TELETEXT_RUNNING = "KEY_IS_TELETEXT_RUNNING";

    // ==========================================
    // TOP BLOCK KEYS
    // ==========================================
    /**
     * Key for the list of blocks.
     * Type: List of Bundle
     */
    public static final String KEY_TOP_BLOCK_LIST = "KEY_TOP_BLOCK_LIST";
    /**
     * Display name of the block.
     * Type: String
     */
    public static final String KEY_BLOCK_NAME = "KEY_BLOCK_NAME";
    /**
     * Unique index/ID of the block. Use this to query groups.
     * Type: int
     */
    public static final String KEY_BLOCK_INDEX = "KEY_BLOCK_INDEX";

    // ==========================================
    // TOP GROUP KEYS
    // ==========================================
    /**
     * Key for the list of groups.
     * Type: List of Bundle
     */
    public static final String KEY_TOP_GROUP_LIST = "KEY_TOP_GROUP_LIST";
    /**
     * Display name of the group.
     * Type: String
     */
    public static final String KEY_GROUP_NAME = "KEY_GROUP_NAME";
    /**
     * Unique index/ID of the group. Use this to query pages.
     * Type: int
     */
    public static final String KEY_GROUP_INDEX = "KEY_GROUP_INDEX";

    // ==========================================
    // TOP PAGE KEYS
    // ==========================================
    /**
     * Key for the list of pages.
     */
    public static final String KEY_TOP_PAGE_LIST = "KEY_TOP_PAGE_LIST";
    /**
     * The actual Teletext page number (e.g., 100, 301).
     * Type: int
     */
    public static final String KEY_PAGE_NUMBER = "KEY_PAGE_NUMBER";
    /**
     * The sub-page code (usually 0 unless specific deep-link).
     * Type: int
     */
    public static final String KEY_PAGE_SUBCODE = "KEY_PAGE_SUBCODE";
    /**
     * Display name of the page (Optional, often null).
     * Type: String
     */
    public static final String KEY_PAGE_NAME = "KEY_PAGE_NAME";
}
