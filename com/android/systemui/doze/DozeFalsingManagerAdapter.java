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

package com.android.systemui.doze;

import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.doze.dagger.DozeScope;

import javax.inject.Inject;

/**
 * Notifies FalsingManager of whether or not AOD is showing.
 */
@DozeScope
public class DozeFalsingManagerAdapter implements DozeMachine.Part {

    private final FalsingCollector mFalsingCollector;

    @Inject
    public DozeFalsingManagerAdapter(FalsingCollector falsingCollector) {
        mFalsingCollector = falsingCollector;
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        mFalsingCollector.setShowingAod(isAodMode(newState));
    }

    private boolean isAodMode(DozeMachine.State state) {
        switch (state) {
            case DOZE_AOD:
            case DOZE_AOD_PAUSING:
            case DOZE_AOD_PAUSED:
                return true;
            default:
                return false;
        }
    }
}
