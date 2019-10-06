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

import java.io.Serializable;

/**
 * A class to store data created by {@link DataIntegrityChecker}.
 */
public class EncryptedData implements Serializable {
    private static final long serialVersionUID = 1337L;

    private byte[] mEncryptedData;
    private byte[] mIv;
    private String mKeyAlias;

    public EncryptedData(byte[] encryptedData, byte[] iv, String keyAlias) {
        mEncryptedData = encryptedData;
        mIv = iv;
        mKeyAlias = keyAlias;
    }

    public byte[] getEncryptedData() {
        return mEncryptedData;
    }

    public byte[] getIv() {
        return mIv;
    }

    public String getKeyAlias() {
        return mKeyAlias;
    }
}
