/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.assist;

import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;

public class AssistOrbContainer extends FrameLayout {

    private static final long EXIT_START_DELAY = 150;

    private View mScrim;
    private View mNavbarScrim;
    private AssistOrbView mOrb;

    private boolean mAnimatingOut;

    public AssistOrbContainer(Context context) {
        this(context, null);
    }

    public AssistOrbContainer(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AssistOrbContainer(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mScrim = findViewById(R.id.assist_orb_scrim);
        mNavbarScrim = findViewById(R.id.assist_orb_navbar_scrim);
        mOrb = (AssistOrbView) findViewById(R.id.assist_orb);
    }

    public void show(final boolean show, boolean animate, Runnable onDone) {
        if (show) {
            if (getVisibility() != View.VISIBLE) {
                setVisibility(View.VISIBLE);
                if (animate) {
                    startEnterAnimation(onDone);
                } else {
                    reset();
                    if (onDone != null) {
                        onDone.run();
                    }
                }
            }
        } else {
            if (animate) {
                startExitAnimation(new Runnable() {
                    @Override
                    public void run() {
                        mAnimatingOut = false;
                        setVisibility(View.GONE);
                        if (onDone != null) {
                            onDone.run();
                        }
                    }
                });
            } else {
                setVisibility(View.GONE);
                if (onDone != null) {
                    onDone.run();
                }
            }
        }
    }

    private void reset() {
        mAnimatingOut = false;
        mOrb.reset();
        mScrim.setAlpha(1f);
        mNavbarScrim.setAlpha(1f);
    }

    private void startEnterAnimation(Runnable onDone) {
        if (mAnimatingOut) {
            return;
        }
        mOrb.startEnterAnimation();
        mScrim.setAlpha(0f);
        mNavbarScrim.setAlpha(0f);
        post(new Runnable() {
            @Override
            public void run() {
                mScrim.animate()
                        .alpha(1f)
                        .setDuration(300)
                        .setStartDelay(0)
                        .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
                mNavbarScrim.animate()
                        .alpha(1f)
                        .setDuration(300)
                        .setStartDelay(0)
                        .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                        .withEndAction(onDone);
            }
        });
    }

    private void startExitAnimation(final Runnable endRunnable) {
        if (mAnimatingOut) {
            if (endRunnable != null) {
                endRunnable.run();
            }
            return;
        }
        mAnimatingOut = true;
        mOrb.startExitAnimation(EXIT_START_DELAY);
        mScrim.animate()
                .alpha(0f)
                .setDuration(250)
                .setStartDelay(EXIT_START_DELAY)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mNavbarScrim.animate()
                .alpha(0f)
                .setDuration(250)
                .setStartDelay(EXIT_START_DELAY)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .withEndAction(endRunnable);
    }

    /**
     * Whether the panel is showing, or, if it's animating, whether it will be
     * when the animation is done.
     */
    public boolean isShowing() {
        return getVisibility() == View.VISIBLE && !mAnimatingOut;
    }

    public AssistOrbView getOrb() {
        return mOrb;
    }
}
