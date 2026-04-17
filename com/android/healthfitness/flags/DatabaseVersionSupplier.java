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

package com.android.healthfitness.flags;

import java.util.Map;
import java.util.SortedMap;
import java.util.function.BooleanSupplier;

/** @hide */
public final class DatabaseVersionSupplier {

    private final int mLastRolledOutVersion;
    private final SortedMap<Integer, BooleanSupplier> mDbVersionToDbFlagMap;

    public DatabaseVersionSupplier(
            int lastRolledOutVersion, SortedMap<Integer, BooleanSupplier> dbVersionToDbFlagMap) {
        mLastRolledOutVersion = lastRolledOutVersion;
        mDbVersionToDbFlagMap = dbVersionToDbFlagMap;
    }

    /** Returns the DB version based on DB flag values. */
    public int get() {
        int dbVersion = mLastRolledOutVersion;
        for (Map.Entry<Integer, BooleanSupplier> entry : mDbVersionToDbFlagMap.entrySet()) {
            if (!entry.getValue().getAsBoolean()) {
                break;
            }
            dbVersion = entry.getKey();
        }
        return dbVersion;
    }
}
