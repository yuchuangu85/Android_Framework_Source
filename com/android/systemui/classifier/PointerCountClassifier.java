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

package com.android.systemui.classifier;

import static com.android.systemui.classifier.Classifier.NOTIFICATION_DRAG_DOWN;
import static com.android.systemui.classifier.Classifier.QUICK_SETTINGS;

import android.view.MotionEvent;

import java.util.Locale;

import javax.inject.Inject;

/**
 * False touch if more than one finger touches the screen.
 *
 * IMPORTANT: This should not be used for certain cases (i.e. a11y) as we expect multiple fingers
 * for them.
 */
class PointerCountClassifier extends FalsingClassifier {

    private static final int MAX_ALLOWED_POINTERS = 1;
    private static final int MAX_ALLOWED_POINTERS_SWIPE_DOWN = 2;
    private int mMaxPointerCount;

    @Inject
    PointerCountClassifier(FalsingDataProvider dataProvider) {
        super(dataProvider);
    }

    @Override
    public void onTouchEvent(MotionEvent motionEvent) {
        int pCount = mMaxPointerCount;
        if (motionEvent.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mMaxPointerCount = motionEvent.getPointerCount();
        } else {
            mMaxPointerCount = Math.max(mMaxPointerCount, motionEvent.getPointerCount());
        }
        if (pCount != mMaxPointerCount) {
            logDebug("Pointers observed:" + mMaxPointerCount);
        }
    }

    @Override
    Result calculateFalsingResult(
            @Classifier.InteractionType int interactionType,
            double historyBelief, double historyConfidence) {
        int allowedPointerCount =
                (interactionType == QUICK_SETTINGS || interactionType == NOTIFICATION_DRAG_DOWN)
                ? MAX_ALLOWED_POINTERS_SWIPE_DOWN : MAX_ALLOWED_POINTERS;

        return mMaxPointerCount > allowedPointerCount
                ? falsed(1, getReason(allowedPointerCount)) : Result.passed(0);
    }

    private String getReason(int allowedPointerCount) {
        return String.format(
                (Locale) null,
                "{pointersObserved=%d, threshold=%d}",
                mMaxPointerCount,
                allowedPointerCount);
    }
}
