/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.util;

import static androidx.lifecycle.Lifecycle.State.DESTROYED;
import static androidx.lifecycle.Lifecycle.State.RESUMED;

import android.view.View;
import android.view.View.OnAttachStateChangeListener;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

/**
 * Tools for generating lifecycle from sysui objects.
 */
public class SysuiLifecycle {

    private SysuiLifecycle() {
    }

    /**
     * Get a lifecycle that will be put into the resumed state when the view is attached
     * and goes to the destroyed state when the view is detached.
     */
    public static LifecycleOwner viewAttachLifecycle(View v) {
        return new ViewLifecycle(v);
    }

    private static class ViewLifecycle implements LifecycleOwner, OnAttachStateChangeListener {
        private final LifecycleRegistry mLifecycle = new LifecycleRegistry(this);

        ViewLifecycle(View v) {
            v.addOnAttachStateChangeListener(this);
            if (v.isAttachedToWindow()) {
                mLifecycle.markState(RESUMED);
            }
        }

        @NonNull
        @Override
        public Lifecycle getLifecycle() {
            return mLifecycle;
        }

        @Override
        public void onViewAttachedToWindow(View v) {
            mLifecycle.markState(RESUMED);
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            mLifecycle.markState(DESTROYED);
        }
    }
}
