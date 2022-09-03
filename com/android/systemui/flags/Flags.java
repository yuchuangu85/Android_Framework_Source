/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.flags;

import com.android.systemui.R;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * List of {@link Flag} objects for use in SystemUI.
 *
 * Flag Ids are integers.
 * Ids must be unique. This is enforced in a unit test.
 * Ids need not be sequential. Flags can "claim" a chunk of ids for flags in related featurs with
 * a comment. This is purely for organizational purposes.
 *
 * On public release builds, flags will always return their default value. There is no way to
 * change their value on release builds.
 *
 * See {@link FeatureFlagManager} for instructions on flipping the flags via adb.
 */
public class Flags {
    public static final BooleanFlag TEAMFOOD = new BooleanFlag(1, false);

    /***************************************/
    // 100 - notification
    public static final BooleanFlag NEW_NOTIFICATION_PIPELINE =
            new BooleanFlag(100, true);

    public static final BooleanFlag NEW_NOTIFICATION_PIPELINE_RENDERING =
            new BooleanFlag(101, false);

    public static final BooleanFlag NOTIFICATION_UPDATES =
            new BooleanFlag(102, true);

    /***************************************/
    // 200 - keyguard/lockscreen
    public static final BooleanFlag KEYGUARD_LAYOUT =
            new BooleanFlag(200, true);

    public static final BooleanFlag LOCKSCREEN_ANIMATIONS =
            new BooleanFlag(201, true);

    public static final BooleanFlag NEW_UNLOCK_SWIPE_ANIMATION =
            new BooleanFlag(202, true);

    public static final BooleanFlag CHARGING_RIPPLE =
            new BooleanFlag(203, false, R.bool.flag_charging_ripple);

    /***************************************/
    // 300 - power menu
    public static final BooleanFlag POWER_MENU_LITE =
            new BooleanFlag(300, true);

    /***************************************/
    // 400 - smartspace
    public static final BooleanFlag SMARTSPACE_DEDUPING =
            new BooleanFlag(400, true);

    public static final BooleanFlag SMARTSPACE_SHARED_ELEMENT_TRANSITION_ENABLED =
            new BooleanFlag(401, false);

    public static final BooleanFlag SMARTSPACE =
            new BooleanFlag(402, false, R.bool.flag_smartspace);

    /***************************************/
    // 500 - quick settings
    public static final BooleanFlag NEW_USER_SWITCHER =
            new BooleanFlag(500, true);

    public static final BooleanFlag COMBINED_QS_HEADERS =
            new BooleanFlag(501, false);

    public static final BooleanFlag PEOPLE_TILE =
            new BooleanFlag(502, false, R.bool.flag_conversations);

    /***************************************/
    // 600- status bar
    public static final BooleanFlag COMBINED_STATUS_BAR_SIGNAL_ICONS =
            new BooleanFlag(601, false);

    /***************************************/
    // 700 - dialer/calls
    public static final BooleanFlag ONGOING_CALL_STATUS_BAR_CHIP =
            new BooleanFlag(700, true);

    public static final BooleanFlag ONGOING_CALL_IN_IMMERSIVE =
            new BooleanFlag(701, true);

    public static final BooleanFlag ONGOING_CALL_IN_IMMERSIVE_CHIP_TAP =
            new BooleanFlag(702, true);

    /***************************************/
    // 800 - general visual/theme
    public static final BooleanFlag MONET =
            new BooleanFlag(800, true, R.bool.flag_monet);

    // Pay no attention to the reflection behind the curtain.
    // ========================== Curtain ==========================
    // |                                                           |
    // |  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  |
    private static Map<Integer, Flag<?>> sFlagMap;
    static Map<Integer, Flag<?>> collectFlags() {
        if (sFlagMap != null) {
            return sFlagMap;
        }
        Map<Integer, Flag<?>> flags = new HashMap<>();

        Field[] fields = Flags.class.getFields();

        for (Field field : fields) {
            Class<?> t = field.getType();
            if (Flag.class.isAssignableFrom(t)) {
                try {
                    Flag<?> flag = (Flag<?>) field.get(null);
                    flags.put(flag.getId(), flag);
                } catch (IllegalAccessException e) {
                    // no-op
                }
            }
        }

        sFlagMap = flags;

        return sFlagMap;
    }
    // |  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  |
    // |                                                           |
    // \_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/

}
