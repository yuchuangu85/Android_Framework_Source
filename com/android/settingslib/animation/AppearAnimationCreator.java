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

package com.android.settingslib.animation;

import android.animation.AnimatorListenerAdapter;
import android.view.animation.Interpolator;

/**
 * An interface which can create animations when starting an appear animation with
 * {@link AppearAnimationUtils}
 */
public interface AppearAnimationCreator<T> {
    /**
     * Create the appear / disappear animation.
     */
    void createAnimation(T animatedObject, long delay, long duration,
            float translationY, boolean appearing, Interpolator interpolator,
            Runnable endRunnable);

    /**
     * Create the animation with {@link AnimatorListenerAdapter}.
     */
    default void createAnimation(T animatedObject, long delay, long duration,
            float translationY, boolean appearing, Interpolator interpolator,
            Runnable endRunnable, AnimatorListenerAdapter animatorListener) {}
}
