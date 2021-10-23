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

package com.android.keyguard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.android.internal.jank.InteractionJankMonitor;

/**
 * A Base class for all Keyguard password/pattern/pin related inputs.
 */
public abstract class KeyguardInputView extends LinearLayout {
    private Runnable mOnFinishImeAnimationRunnable;

    public KeyguardInputView(Context context) {
        super(context);
    }

    public KeyguardInputView(Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public KeyguardInputView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    abstract CharSequence getTitle();

    void animateForIme(float interpolatedFraction, boolean appearingAnim) {
        return;
    }

    boolean disallowInterceptTouch(MotionEvent event) {
        return false;
    }

    void startAppearAnimation() {}

    boolean startDisappearAnimation(Runnable finishRunnable) {
        return false;
    }

    protected AnimatorListenerAdapter getAnimationListener(int cuj) {
        return new AnimatorListenerAdapter() {
            private boolean mIsCancel;

            @Override
            public void onAnimationCancel(Animator animation) {
                mIsCancel = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mIsCancel) {
                    InteractionJankMonitor.getInstance().cancel(cuj);
                } else {
                    InteractionJankMonitor.getInstance().end(cuj);
                }
            }

            @Override
            public void onAnimationStart(Animator animation) {
                InteractionJankMonitor.getInstance().begin(KeyguardInputView.this, cuj);
            }
        };
    }

    public void setOnFinishImeAnimationRunnable(Runnable onFinishImeAnimationRunnable) {
        mOnFinishImeAnimationRunnable = onFinishImeAnimationRunnable;
    }

    public void runOnFinishImeAnimationRunnable() {
        if (mOnFinishImeAnimationRunnable != null) {
            mOnFinishImeAnimationRunnable.run();
            mOnFinishImeAnimationRunnable = null;
        }
    }
}
