/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.telephony.configupdate;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public abstract class ConfigParser<T> {
    private static final String TAG = "ConfigParser";

    public static final int VERSION_UNKNOWN = -1;

    protected int mVersion = VERSION_UNKNOWN;

    protected T mConfig;

    /**
     * Constructs a parser from the raw data
     *
     * @param data the config data
     */
    public ConfigParser(@Nullable byte[] data) {
        parseData(data);
    }

    /**
     * Constructs a parser from the input stream
     *
     * @param input the input stream of the config
     * @return the instance of the ConfigParser
     */
    public ConfigParser(@NonNull InputStream input) throws IOException {
        parseData(input.readAllBytes());
    }

    /**
     * Constructs a parser from the input stream
     *
     * @param file the input file of the config
     * @return the instance of the ConfigParser
     */
    public ConfigParser(@NonNull File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            parseData(fis.readAllBytes());
        }
    }

    /**
     * Get the version of the config
     *
     * @return the version of the config if it is defined, or VERSION_UNKNOWN
     */
    public int getVersion() {
        return mVersion;
    }

    /**
     * Get the config
     *
     * @return the config
     */
    public @Nullable T getConfig() {
        return mConfig;
    }

    /**
     * Get the sub config raw data by id
     *
     * @param id the identifier of the sub config
     * @return the raw data of the sub config
     */
    public @Nullable byte[] getData(String id) {
        return null;
    }

    /**
     * Parse the config data
     *
     * @param data the config data
     */
    protected abstract void parseData(@Nullable byte[] data);

    /**
     * Get the domain of the config
     *
     * @return the domain of the config
     */
    public abstract String getDomain();

    /**
     * This API is used by CTS to override the version
     */
    protected void overrideVersion(int version) {
        mVersion = version;
        Log.d(TAG, "overrideVersion: mVersion=" + mVersion);
    }

    /**
     * Overrides the config
     *
     * @param config the config that should be overrided with
     */
    public void overrideConfig(T config) {
        Log.d(TAG, "overrideConfig: overriding config to " + config);
        mConfig = config;
    }
}
