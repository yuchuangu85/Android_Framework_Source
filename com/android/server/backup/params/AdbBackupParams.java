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
 * limitations under the License
 */

package com.android.server.backup.params;

import android.os.ParcelFileDescriptor;

import com.android.server.backup.utils.BackupEligibilityRules;

public class AdbBackupParams extends AdbParams {

    public boolean includeApks;
    public boolean includeObbs;
    public boolean includeShared;
    public boolean doWidgets;
    public boolean allApps;
    public boolean includeSystem;
    public boolean doCompress;
    public boolean includeKeyValue;
    public String[] packages;
    public BackupEligibilityRules backupEligibilityRules;

    public AdbBackupParams(ParcelFileDescriptor output, boolean saveApks, boolean saveObbs,
            boolean saveShared, boolean alsoWidgets, boolean doAllApps, boolean doSystem,
            boolean compress, boolean doKeyValue, String[] pkgList,
            BackupEligibilityRules eligibilityRules) {
        fd = output;
        includeApks = saveApks;
        includeObbs = saveObbs;
        includeShared = saveShared;
        doWidgets = alsoWidgets;
        allApps = doAllApps;
        includeSystem = doSystem;
        doCompress = compress;
        includeKeyValue = doKeyValue;
        packages = pkgList;
        backupEligibilityRules = eligibilityRules;
    }
}
