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

package com.android.wm.shell.pip;

import android.annotation.IntDef;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Used to keep track of PiP leash state as it appears and animates by {@link PipTaskOrganizer} and
 * {@link PipTransition}.
 */
public class PipTransitionState {

    public static final int UNDEFINED = 0;
    public static final int TASK_APPEARED = 1;
    public static final int ENTRY_SCHEDULED = 2;
    public static final int ENTERING_PIP = 3;
    public static final int ENTERED_PIP = 4;
    public static final int EXITING_PIP = 5;

    /**
     * If set to {@code true}, no entering PiP transition would be kicked off and most likely
     * it's due to the fact that Launcher is handling the transition directly when swiping
     * auto PiP-able Activity to home.
     * See also {@link PipTaskOrganizer#startSwipePipToHome(ComponentName, ActivityInfo,
     * PictureInPictureParams)}.
     */
    private boolean mInSwipePipToHomeTransition;

    // Not a complete set of states but serves what we want right now.
    @IntDef(prefix = { "TRANSITION_STATE_" }, value =  {
            UNDEFINED,
            TASK_APPEARED,
            ENTRY_SCHEDULED,
            ENTERING_PIP,
            ENTERED_PIP,
            EXITING_PIP
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TransitionState {}

    private @TransitionState int mState;

    public PipTransitionState() {
        mState = UNDEFINED;
    }

    public void setTransitionState(@TransitionState int state) {
        mState = state;
    }

    public @TransitionState int getTransitionState() {
        return mState;
    }

    public boolean isInPip() {
        return mState >= TASK_APPEARED
                && mState != EXITING_PIP;
    }

    public void setInSwipePipToHomeTransition(boolean inSwipePipToHomeTransition) {
        mInSwipePipToHomeTransition = inSwipePipToHomeTransition;
    }

    public boolean getInSwipePipToHomeTransition() {
        return mInSwipePipToHomeTransition;
    }
    /**
     * Resize request can be initiated in other component, ignore if we are no longer in PIP,
     * still waiting for animation or we're exiting from it.
     *
     * @return {@code true} if the resize request should be blocked/ignored.
     */
    public boolean shouldBlockResizeRequest() {
        return mState < ENTERING_PIP
                || mState == EXITING_PIP;
    }
}
