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

package com.android.net.module.util;

import java.nio.ByteOrder;

/**
 * LegacyStruct is solely used for providing access to the deprecated writeToBytes(ByteOrder)
 * method.
 *
 * The goal is to slowly move all Struct classes to defining the ByteOrder directly on the field (by
 * virtue of using the BE int types) after which LegacyStruct can be deleted.
 */
public class LegacyStruct extends Struct {
    /** A wrapper around {@code Struct#legacyWriteToBytes} */
    public final byte[] writeToBytes(final ByteOrder order) {
        return legacyWriteToBytes(order);

    }
}
