/*
 * Copyright (C) 2026 The Android Open Source Project
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
package android.os.multisensory;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** A token that identifies a pair of audio-haptic data in the Multi-sensory Design System (MSDS) */
@FlaggedApi(android.os.multisensory.Flags.FLAG_ENABLE_MULTISENSORY_FEEDBACK)
public final class MultisensoryToken {

    /** @hide */
    @IntDef(
            value = {
                DRAG_INDICATOR_CONTINUOUS,
                DRAG_INDICATOR_DISCRETE,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ContinuousEffectToken {}

    /** @hide */
    @IntDef(
            value = {
                FAILURE_HIGH_EMPHASIS,
                FAILURE,
                SUCCESS,
                START,
                PAUSE,
                STOP,
                CANCEL,
                SWITCH_ON,
                SWITCH_OFF,
                UNLOCK,
                LOCK,
                LONG_PRESS,
                SWIPE_INDICATOR_THRESHOLD_LIMIT,
                TAP_HIGH_EMPHASIS,
                TAP_MEDIUM_EMPHASIS,
                TAP_LOW_EMPHASIS,
                KEYPRESS_STANDARD,
                KEYPRESS_SPACEBAR,
                KEYPRESS_RETURN,
                KEYPRESS_DELETE,
                DRAG_INDICATOR_CONTINUOUS,
                DRAG_INDICATOR_DISCRETE,
                DRAG_INDICATOR_THRESHOLD_LIMIT,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Token {}

    /* Inform the user with emphasis that their current action FAILED to complete */
    public static final int FAILURE_HIGH_EMPHASIS = 0;

    /* Inform the user that their current action FAILED to complete */
    public static final int FAILURE = 1;

    /* Inform the user their current action was completed SUCCESSFULLY */
    public static final int SUCCESS = 2;

    /* Inform the user that an ongoing activity has started */
    public static final int START = 3;

    /* Inform the user that an ongoing activity has paused */
    public static final int PAUSE = 4;

    /* Inform the user that their previously started activity has stopped SUCCESSFULLY */
    public static final int STOP = 5;

    /* Inform the user that their previously started activity has canceled SUCCESSFULLY */
    public static final int CANCEL = 6;

    /**
     * Inform the user that the state of an interactive component has been switched to on
     * successfully
     */
    public static final int SWITCH_ON = 7;

    /**
     * Inform the user that the state of an interactive component has been switched to off
     * successfully
     */
    public static final int SWITCH_OFF = 8;

    /* Inform the user the state of their device changed to unlocked SUCCESSFULLY */
    public static final int UNLOCK = 9;

    /* Inform the user the state of their device changed to locked SUCCESSFULLY */
    public static final int LOCK = 10;

    /**
     * Inform the user that their long-press gesture has resulted in the revealing of more
     * contextual information
     */
    public static final int LONG_PRESS = 11;

    /**
     * Inform the user that their swipe gesture has reached a threshold that confirms navigation or
     * the reveal of additional information.
     */
    public static final int SWIPE_INDICATOR_THRESHOLD_LIMIT = 12;

    /* Played when the user taps on a high-emphasis UI element */
    public static final int TAP_HIGH_EMPHASIS = 13;

    /* Inform the user that their tap has resulted in a selection */
    public static final int TAP_MEDIUM_EMPHASIS = 14;

    /**
     * Played when a user taps on any UI element that can be interacted with but is not otherwise
     * defined.
     */
    public static final int TAP_LOW_EMPHASIS = 15;

    /* Played when a users drag gesture reaches the maximum or minimum value */
    public static final int DRAG_INDICATOR_THRESHOLD_LIMIT = 16;

    /**
     * Inform the user that their drag gesture has resulted in an incremental value change.
     *
     * <p>This token can be used to create a continuous effect affected by {@link
     * MultisensoryContinuousEffectModifier}
     */
    public static final int DRAG_INDICATOR_CONTINUOUS = 17;

    /**
     * Inform the user that their drag gesture has resulted in a stepped value change.
     *
     * <p>This token can be used to create a continuous effect affected by {@link
     * MultisensoryContinuousEffectModifier}
     */
    public static final int DRAG_INDICATOR_DISCRETE = 18;

    /* Played when the user touches a key on the keyboard that is otherwise undefined */
    public static final int KEYPRESS_STANDARD = 19;

    /* Played when the user touches the space key */
    public static final int KEYPRESS_SPACEBAR = 20;

    /* Played when the user touches the return key */
    public static final int KEYPRESS_RETURN = 21;

    /* Played when the user touches the delete key */
    public static final int KEYPRESS_DELETE = 22;

    private MultisensoryToken() {}
}
