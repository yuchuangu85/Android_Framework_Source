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

package com.android.internal.telephony.data;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;

import com.android.internal.telephony.TelephonyConfigData;
import com.android.internal.telephony.configupdate.ConfigParser;
import com.android.internal.telephony.configupdate.TelephonyConfigUpdateInstallReceiver;
import com.android.internal.telephony.protobuf.InvalidProtocolBufferException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * DataConfigParser parses the config data and create DataConfig.
 * It is obtained through the getConfigParser() at the TelephonyConfigUpdateInstallReceiver.
 */
public class DataConfigParser extends ConfigParser<DataConfig> {
    private static final String TAG = "DataConfigParser";

    /**
     * Create an instance of DataConfigParser with byte array data.
     *
     * @param data byte array type of config data.
     */
    public DataConfigParser(@Nullable byte[] data) {
        super(data);
    }

    /**
     * Create an instance of DataConfigParser with InputStream data.
     *
     * @param input InputStream type of config data.
     */
    public DataConfigParser(@NonNull InputStream input) throws IOException {
        super(input);
    }

    /**
     * Create an instance of DataConfigParser with File data.
     *
     * @param file File type of config data.
     */
    public DataConfigParser(@NonNull File file) throws IOException {
        super(new FileInputStream(file));
    }

    @Override
    public void parseData(@Nullable byte[] data) {
        Log.d(TAG, "parseData");
        try {
            TelephonyConfigData.TelephonyConfigProto telephonyConfigData =
                    TelephonyConfigData.TelephonyConfigProto.parseFrom(data);
            if (telephonyConfigData.hasData()) {
                mVersion = telephonyConfigData.getData().getVersion();
                mConfig = new DataConfig(telephonyConfigData.getData());
                Log.d(TAG, "DataConfig is created");
            }
        } catch (InvalidProtocolBufferException e) {
            Log.e(TAG, "InvalidProtocolBufferException : " + e);
        } catch (Exception e) {
            Log.e(TAG, "Exception : " + e);
        }
    }

    @Override
    public String getDomain() {
        return TelephonyConfigUpdateInstallReceiver.DOMAIN_DATA;
    }
}
