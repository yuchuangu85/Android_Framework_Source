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

package com.android.layoutlib.bridge.android.support;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.util.ReflectionUtils.ReflectionException;

import android.content.Context;
import android.widget.TabHost;

import static com.android.layoutlib.bridge.util.ReflectionUtils.getCause;
import static com.android.layoutlib.bridge.util.ReflectionUtils.getMethod;
import static com.android.layoutlib.bridge.util.ReflectionUtils.invoke;

/**
 * Utility class for working with android.support.v4.app.FragmentTabHost
 */
public class FragmentTabHostUtil {

    public static final String CN_FRAGMENT_TAB_HOST = "android.support.v4.app.FragmentTabHost";

    /**
     * Calls the setup method for the FragmentTabHost tabHost
     */
    public static void setup(TabHost tabHost, Context context) {
        try {
            invoke(getMethod(tabHost.getClass(), "setup", Context.class,
                    Class.forName("android.support.v4.app.FragmentManager", true,
                            tabHost.getClass().getClassLoader()), int.class), tabHost, context, null,
                    android.R.id.tabcontent);
        } catch (ReflectionException | ClassNotFoundException e) {
            Throwable cause = getCause(e);
            Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                    "Error occurred while trying to setup FragmentTabHost.", cause, null);
        }
    }
}
