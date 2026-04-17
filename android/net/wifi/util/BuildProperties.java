/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi.util;

import android.os.Build;

/**
 * A proxy of android.os.Build for easy testing.
 * @hide
 */
public final class BuildProperties {
    /** Returns true iff this is an eng build. */
    public boolean isEngBuild() {
        return Build.TYPE.equals("eng");
    }

    /** Returns true iff this is a userdebug build. */
    public boolean isUserdebugBuild() {
        return Build.TYPE.equals("userdebug");
    }

    /** Returns true iff this a normal user build (not userdebug). */
    public boolean isUserBuild() {
        return Build.TYPE.equals("user");
    }

    private static final BuildProperties INSTANCE = new BuildProperties();

    // Get an instance of this class.
    public static BuildProperties getInstance() {
        return INSTANCE;
    }
}
