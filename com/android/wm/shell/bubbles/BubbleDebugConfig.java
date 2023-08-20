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

package com.android.wm.shell.bubbles;

import android.content.Context;
import android.provider.Settings;

import java.util.List;

/**
 * Common class for the various debug {@link android.util.Log} output configuration in the Bubbles
 * package.
 */
public class BubbleDebugConfig {

    // All output logs in the Bubbles package use the {@link #TAG_BUBBLES} string for tagging their
    // log output. This makes it easy to identify the origin of the log message when sifting
    // through a large amount of log output from multiple sources. However, it also makes trying
    // to figure-out the origin of a log message while debugging the Bubbles a little painful. By
    // setting this constant to true, log messages from the Bubbles package will be tagged with
    // their class names instead fot the generic tag.
    public static final boolean TAG_WITH_CLASS_NAME = false;

    // Default log tag for the Bubbles package.
    public static final String TAG_BUBBLES = "Bubbles";

    static final boolean DEBUG_BUBBLE_CONTROLLER = false;
    static final boolean DEBUG_BUBBLE_DATA = false;
    static final boolean DEBUG_BUBBLE_STACK_VIEW = false;
    static final boolean DEBUG_BUBBLE_EXPANDED_VIEW = false;
    static final boolean DEBUG_EXPERIMENTS = true;
    static final boolean DEBUG_OVERFLOW = false;
    static final boolean DEBUG_USER_EDUCATION = false;
    static final boolean DEBUG_POSITIONER = false;
    public static final boolean DEBUG_COLLAPSE_ANIMATOR = false;
    static final boolean DEBUG_BUBBLE_GESTURE = false;
    public static boolean DEBUG_EXPANDED_VIEW_DRAGGING = false;

    private static final boolean FORCE_SHOW_USER_EDUCATION = false;
    private static final String FORCE_SHOW_USER_EDUCATION_SETTING =
            "force_show_bubbles_user_education";

    /**
     * @return whether we should force show user education for bubbles. Used for debugging & demos.
     */
    static boolean forceShowUserEducation(Context context) {
        boolean forceShow = Settings.Secure.getInt(context.getContentResolver(),
                FORCE_SHOW_USER_EDUCATION_SETTING, 0) != 0;
        return FORCE_SHOW_USER_EDUCATION || forceShow;
    }

    static String formatBubblesString(List<Bubble> bubbles, BubbleViewProvider selected) {
        StringBuilder sb = new StringBuilder();
        for (Bubble bubble : bubbles) {
            if (bubble == null) {
                sb.append("   <null> !!!!!\n");
            } else {
                boolean isSelected = (selected != null
                        && selected.getKey() != BubbleOverflow.KEY
                        && bubble == selected);
                String arrow = isSelected ? "=>" : "  ";
                sb.append(String.format("%s Bubble{act=%12d, showInShade=%d, key=%s}\n",
                        arrow,
                        bubble.getLastActivity(),
                        (bubble.showInShade() ? 1 : 0),
                        bubble.getKey()));
            }
        }
        return sb.toString();
    }
}
