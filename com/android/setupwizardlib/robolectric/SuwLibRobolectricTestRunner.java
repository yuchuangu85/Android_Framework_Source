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

package com.android.setupwizardlib.robolectric;

import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.internal.ManifestFactory;

public class SuwLibRobolectricTestRunner extends RobolectricTestRunner {

    private String mModuleRootPath;

    public SuwLibRobolectricTestRunner(Class<?> testClass) throws InitializationError {
        super(testClass);
    }

    // Hack to determine the module root path in the build folder (e.g. out/gradle/setup-wizard-lib)
    private void updateModuleRootPath(Config config) {
        String moduleRoot = config.constants().getResource("").toString()
                .replace("file:", "").replace("jar:", "");
        mModuleRootPath =
                moduleRoot.substring(0, moduleRoot.lastIndexOf("/build")) + "/setup-wizard-lib";
    }

    /**
     * Return the default config used to run Robolectric tests.
     */
    @Override
    protected Config buildGlobalConfig() {
        Config parent = super.buildGlobalConfig();
        updateModuleRootPath(parent);
        return new Config.Builder(parent)
                .setBuildDir(mModuleRootPath + "/build")
                .build();
    }

    @Override
    protected ManifestFactory getManifestFactory(Config config) {
        return new PatchedGradleManifestFactory();
    }

    @Override
    protected void runChild(FrameworkMethod method, RunNotifier notifier) {
        System.out.println("===== Running " + method + " =====");
        super.runChild(method, notifier);
    }
}
