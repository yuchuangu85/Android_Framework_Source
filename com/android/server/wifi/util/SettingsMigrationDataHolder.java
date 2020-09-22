/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.Nullable;
import android.content.Context;
import android.net.wifi.WifiMigration;

/**
 * Holder for storing the migration settings data retrieved from
 * {@link android.net.wifi.WifiMigration#loadFromSettings(Context)} to avoid invoking the method
 * multiple times.
 */
public class SettingsMigrationDataHolder {
    private final Context mContext;
    private WifiMigration.SettingsMigrationData mData = null;
    private boolean mRetrieved = false;

    public SettingsMigrationDataHolder(Context context) {
        mContext = context;
    }

    private void retrieveDataIfNecessary() {
        if (mRetrieved) return;
        mData = WifiMigration.loadFromSettings(mContext);
        mRetrieved = true;
    }

    /**
     * Retrieve the cached data returned from {@link WifiMigration#loadFromSettings(Context)}.
     */
    @Nullable
    public WifiMigration.SettingsMigrationData retrieveData() {
        retrieveDataIfNecessary();
        return mData;
    }
}
