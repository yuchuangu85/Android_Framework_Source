/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.layoutlib.bridge.bars;

import android.os._Original_Build.VERSION_CODES;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static android.os._Original_Build.VERSION_CODES.*;

/**
 * Various helper methods to simulate older versions of platform.
 */
public class Config {

    // each of these resource dirs must end in '/'
    private static final String GINGERBREAD_DIR      = "/bars/v9/";
    private static final String JELLYBEAN_DIR        = "/bars/v18/";
    private static final String KITKAT_DIR           = "/bars/v19/";
    private static final String LOLLIPOP_DIR         = "/bars/v21/";
    private static final String PI_DIR = "/bars/v28/";


    private static final List<String> sDefaultResourceDir;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;

    static {
        sDefaultResourceDir = new ArrayList<>(6);
        sDefaultResourceDir.add(PI_DIR);
        sDefaultResourceDir.add("/bars/");
        // If something is not found in the default directories, we fall back to search in the
        // old versions
        sDefaultResourceDir.add(LOLLIPOP_DIR);
        sDefaultResourceDir.add(KITKAT_DIR);
        sDefaultResourceDir.add(JELLYBEAN_DIR);
        sDefaultResourceDir.add(GINGERBREAD_DIR);
    }

    public static boolean showOnScreenNavBar(int platformVersion) {
        return isGreaterOrEqual(platformVersion, ICE_CREAM_SANDWICH);
    }

    public static int getStatusBarColor(int platformVersion) {
        // return white for froyo and earlier; black otherwise.
        return isGreaterOrEqual(platformVersion, GINGERBREAD) ? BLACK : WHITE;
    }

    public static List<String> getResourceDirs(int platformVersion) {
        // Special case the most used scenario.
        if (platformVersion == 0) {
            return sDefaultResourceDir;
        }
        List<String> list = new ArrayList<String>(10);
        // Gingerbread - uses custom battery and wifi icons.
        if (platformVersion <= GINGERBREAD) {
            list.add(GINGERBREAD_DIR);
        }
        // ICS - JellyBean uses custom battery, wifi.
        if (platformVersion <= JELLY_BEAN_MR2) {
            list.add(JELLYBEAN_DIR);
        }
        // KitKat - uses custom wifi and nav icons.
        if (platformVersion <= KITKAT) {
            list.add(KITKAT_DIR);
        }
        // Lollipop - Custom for sysbar and battery
        if (platformVersion <= LOLLIPOP) {
            list.add(LOLLIPOP_DIR);
        }

        list.addAll(sDefaultResourceDir);

        return Collections.unmodifiableList(list);
    }

    public static String getTime(int platformVersion) {
        if (isGreaterOrEqual(platformVersion, R)) {
            return "11:00";
        }
        if (platformVersion < GINGERBREAD) {
            return "2:20";
        }
        if (platformVersion < ICE_CREAM_SANDWICH) {
            return "2:30";
        }
        if (platformVersion < JELLY_BEAN) {
            return "4:00";
        }
        if (platformVersion < KITKAT) {
            return "4:30";
        }
        if (platformVersion < LOLLIPOP) {
            return "4:40";
        }
        if (platformVersion < LOLLIPOP_MR1) {
            return "5:00";
        }
        if (platformVersion < M) {
            return "5:10";
        }
        if (platformVersion < N) {
            return "6:00";
        }
        if (platformVersion < N_MR1) {
            return "7:00";
        }
        if (platformVersion < O) {
            return "7:10";
        }
        if (platformVersion < O_MR1) {
            return "8:00";
        }
        if (platformVersion < P) {
            return "8:10";
        }
        if (platformVersion < Q) {
            return "9:00";
        }
        if (platformVersion < R) {
            return "10:00";
        }
        // Should never happen.
        return "4:04";
    }

    public static int getTimeColor(int platformVersion) {
        if (isGreaterOrEqual(platformVersion, KITKAT) ||
                platformVersion > FROYO && platformVersion < HONEYCOMB) {
            // Gingerbread and KitKat onwards.
            return WHITE;
        }
        // Black for froyo.
        if (platformVersion < GINGERBREAD) {
            return BLACK;
        } else if (platformVersion < KITKAT) {
            // Honeycomb to JB-mr2: Holo blue light.
            return 0xff33b5e5;
        }
        // Should never happen.
        return WHITE;
    }

    public static String getWifiIconType(int platformVersion) {
        return isGreaterOrEqual(platformVersion, LOLLIPOP) ? "xml" : "png";
    }

    /**
     * Compare simulated platform version and code from {@link VERSION_CODES} to check if
     * the simulated platform is greater than or equal to the version code.
     */
    public static boolean isGreaterOrEqual(int platformVersion, int code) {
        // simulated platform version = 0 means that we use the latest.
        return platformVersion == 0 || platformVersion >= code;
    }
}
