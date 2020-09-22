/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wifi.util;

/**
 * Helper for translating bit-flags packed into an int
 */
public class BitMask {
    public int value;

    public BitMask(int value) {
        this.value = value;
    }

    /**
     * Clears the specifed bit, returning true if it was set
     *
     * @param maskBit to test and clear
     * @return true if and only if the mask was originally set in value
     */
    public boolean testAndClear(int maskBit) {
        boolean ans = (value & maskBit) != 0;
        value &= ~maskBit;
        return ans;
    }
}
