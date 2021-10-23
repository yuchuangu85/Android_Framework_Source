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

package com.android.wm.shell.pip;

import android.content.res.Configuration;
import android.graphics.Rect;

import com.android.wm.shell.common.annotations.ExternalThread;

import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * Interface to engage picture in picture feature.
 */
@ExternalThread
public interface Pip {

    /**
     * Returns a binder that can be passed to an external process to manipulate PIP.
     */
    default IPip createExternalInterface() {
        return null;
    }

    /**
     * Expand PIP, it's possible that specific request to activate the window via Alt-tab.
     */
    default void expandPip() {
    }

    /**
     * Hides the PIP menu.
     */
    default void hidePipMenu(Runnable onStartCallback, Runnable onEndCallback) {}

    /**
     * Called when configuration is changed.
     */
    default void onConfigurationChanged(Configuration newConfig) {
    }

    /**
     * Called when display size or font size of settings changed
     */
    default void onDensityOrFontScaleChanged() {
    }

    /**
     * Called when overlay package change invoked.
     */
    default void onOverlayChanged() {
    }

    /**
     * Called when SysUI state changed.
     *
     * @param isSysUiStateValid Is SysUI state valid or not.
     * @param flag Current SysUI state.
     */
    default void onSystemUiStateChanged(boolean isSysUiStateValid, int flag) {
    }

    /**
     * Registers the session listener for the current user.
     */
    default void registerSessionListenerForCurrentUser() {
    }

    /**
     * Sets both shelf visibility and its height.
     *
     * @param visible visibility of shelf.
     * @param height  to specify the height for shelf.
     */
    default void setShelfHeight(boolean visible, int height) {
    }

    /**
     * Registers the pinned stack animation listener.
     *
     * @param callback The callback of pinned stack animation.
     */
    default void setPinnedStackAnimationListener(Consumer<Boolean> callback) {
    }

    /**
     * Set the pinned stack with {@link PipAnimationController.AnimationType}
     *
     * @param animationType The pre-defined {@link PipAnimationController.AnimationType}
     */
    default void setPinnedStackAnimationType(int animationType) {
    }

    /**
     * Called when showing Pip menu.
     */
    default void showPictureInPictureMenu() {}

    /**
     * Called by NavigationBar in order to listen in for PiP bounds change. This is mostly used
     * for times where the PiP bounds could conflict with SystemUI elements, such as a stashed
     * PiP and the Back-from-Edge gesture.
     */
    default void setPipExclusionBoundsChangeListener(Consumer<Rect> listener) { }

    /**
     * Dump the current state and information if need.
     *
     * @param pw The stream to dump information to.
     */
    default void dump(PrintWriter pw) {
    }
}
