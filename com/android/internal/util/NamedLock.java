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
package com.android.internal.util;

import java.util.Objects;

/**
 * A lock with a name!
 *
 * <p>This class should be used as a synchronization lock (i.e., instead of
 * {@code mLock = new Object()}, so stack traces show exactly what the lock's for (other than just
 * it's internal address). In other words, it solves the "A Lock has no Name!" issue).
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class NamedLock {

    private final String mName;

    private NamedLock(String name) {
        mName = Objects.requireNonNull(name, "name cannot be null");
        String stripped = name.strip();
        Preconditions.checkArgument(name.equals(stripped),
                "name (%s) cannot start or end with blank characters", name);
        Preconditions.checkArgument(!name.isEmpty(), "name cannot be empty");
    }

    /**
     * Creates a lock with the given name.
     *
     * @throws IllegalArgumentException if the name is empty, starts with a blank character, or ends
     *             with a blank character.
     */
    public static Object create(String name) {
        return new NamedLock(name);
    }

    @Override
    public String toString() {
        return mName;
    }
}
