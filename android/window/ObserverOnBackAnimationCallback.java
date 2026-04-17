/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.window;

import android.annotation.NonNull;

/**
 * Privileged marker interface for {@link OnBackAnimationCallback} that's only available to system
 * components. When registered with
 * {@link OnBackInvokedDispatcher#PRIORITY_SYSTEM_NAVIGATION_OBSERVER}, this callback gets
 * {@link OnBackAnimationCallback#onBackStarted}, {@link OnBackAnimationCallback#onBackInvoked()}
 * and {@link OnBackAnimationCallback#onBackCancelled()} callbacks whenever ANY back navigation
 * happens, including any non-system back navigation.
 * @hide
 */
public interface ObserverOnBackAnimationCallback extends OnBackAnimationCallback {

    @Override
    void onBackStarted(@NonNull BackEvent backEvent);

    @Override
    void onBackInvoked();

    @Override
    void onBackCancelled();

}
