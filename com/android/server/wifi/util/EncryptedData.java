/*
 * Copyright (C) 2019 The Android Open Source Project
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

import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.Objects;

/**
 * A class to store data created by {@link WifiConfigStoreEncryptionUtil}.
 */
public class EncryptedData {
    private final byte[] mEncryptedData;
    private final byte[] mIv;

    public EncryptedData(byte[] encryptedData, byte[] iv) {
        Preconditions.checkNotNull(encryptedData);
        Preconditions.checkNotNull(iv);
        mEncryptedData = encryptedData;
        mIv = iv;
    }

    public byte[] getEncryptedData() {
        return mEncryptedData;
    }

    public byte[] getIv() {
        return mIv;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof EncryptedData)) return false;
        EncryptedData otherEncryptedData = (EncryptedData) other;
        return Arrays.equals(this.mEncryptedData, otherEncryptedData.mEncryptedData)
                && Arrays.equals(this.mIv, otherEncryptedData.mIv);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(mEncryptedData), Arrays.hashCode(mIv));
    }
}
