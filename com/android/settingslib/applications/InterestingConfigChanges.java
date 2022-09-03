/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.settingslib.applications;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;

/**
 * A class for applying config changes and determing if doing so resulting in any "interesting"
 * changes.
 */
public class InterestingConfigChanges {
    private final Configuration mLastConfiguration = new Configuration();
    private final int mFlags;
    private int mLastDensity;

    public InterestingConfigChanges() {
        this(ActivityInfo.CONFIG_LOCALE | ActivityInfo.CONFIG_LAYOUT_DIRECTION
                | ActivityInfo.CONFIG_UI_MODE | ActivityInfo.CONFIG_ASSETS_PATHS);
    }

    public InterestingConfigChanges(int flags) {
        mFlags = flags;
    }

    /**
     * Applies the given config change and returns whether an "interesting" change happened.
     *
     * @param res The source of the new config to apply
     *
     * @return Whether interesting changes occurred
     */
    @SuppressLint("NewApi")
    public boolean applyNewConfig(Resources res) {
        int configChanges = mLastConfiguration.updateFrom(
                Configuration.generateDelta(mLastConfiguration, res.getConfiguration()));
        boolean densityChanged = mLastDensity != res.getDisplayMetrics().densityDpi;
        if (densityChanged || (configChanges & (mFlags)) != 0) {
            mLastDensity = res.getDisplayMetrics().densityDpi;
            return true;
        }
        return false;
    }
}
