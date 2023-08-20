/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.activityembedding;

import static android.window.TransitionInfo.FLAG_FILLS_TASK;
import static android.window.TransitionInfo.FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY;

import static java.util.Objects.requireNonNull;

import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.ArrayMap;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.util.TransitionUtil;

import java.util.List;

/**
 * Responsible for handling ActivityEmbedding related transitions.
 */
public class ActivityEmbeddingController implements Transitions.TransitionHandler {

    private final Context mContext;
    @VisibleForTesting
    final Transitions mTransitions;
    @VisibleForTesting
    final ActivityEmbeddingAnimationRunner mAnimationRunner;

    /**
     * Keeps track of the currently-running transition callback associated with each transition
     * token.
     */
    private final ArrayMap<IBinder, Transitions.TransitionFinishCallback> mTransitionCallbacks =
            new ArrayMap<>();

    private ActivityEmbeddingController(@NonNull Context context, @NonNull ShellInit shellInit,
            @NonNull Transitions transitions) {
        mContext = requireNonNull(context);
        mTransitions = requireNonNull(transitions);
        mAnimationRunner = new ActivityEmbeddingAnimationRunner(context, this);

        shellInit.addInitCallback(this::onInit, this);
    }

    /**
     * Creates {@link ActivityEmbeddingController}, returns {@code null} if the feature is not
     * supported.
     */
    @Nullable
    public static ActivityEmbeddingController create(@NonNull Context context,
            @NonNull ShellInit shellInit, @NonNull Transitions transitions) {
        return Transitions.ENABLE_SHELL_TRANSITIONS
                ? new ActivityEmbeddingController(context, shellInit, transitions)
                : null;
    }

    /** Registers to handle transitions. */
    public void onInit() {
        mTransitions.addHandler(this);
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        boolean containsEmbeddingSplit = false;
        boolean containsNonEmbeddedChange = false;
        final List<TransitionInfo.Change> changes = info.getChanges();
        for (int i = changes.size() - 1; i >= 0; i--) {
            final TransitionInfo.Change change = changes.get(i);
            if (!change.hasFlags(FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY)) {
                containsNonEmbeddedChange = true;
            } else if (!change.hasFlags(FLAG_FILLS_TASK)) {
                // Whether the Task contains any ActivityEmbedding split before or after the
                // transition.
                containsEmbeddingSplit = true;
            }
        }
        if (!containsEmbeddingSplit) {
            // Let the system to play the default animation if there is no ActivityEmbedding split
            // window. This allows to play the app customized animation when there is no embedding,
            // such as the device is in a folded state.
            return false;
        }
        if (containsNonEmbeddedChange && !handleNonEmbeddedChanges(changes)) {
            return false;
        }

        // Start ActivityEmbedding animation.
        mTransitionCallbacks.put(transition, finishCallback);
        mAnimationRunner.startAnimation(transition, info, startTransaction, finishTransaction);
        return true;
    }

    @Override
    public void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull IBinder mergeTarget,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        mAnimationRunner.cancelAnimationFromMerge();
    }

    private boolean handleNonEmbeddedChanges(List<TransitionInfo.Change> changes) {
        final Rect nonClosingEmbeddedArea = new Rect();
        for (int i = changes.size() - 1; i >= 0; i--) {
            final TransitionInfo.Change change = changes.get(i);
            if (!TransitionUtil.isClosingType(change.getMode())) {
                if (change.hasFlags(FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY)) {
                    nonClosingEmbeddedArea.union(change.getEndAbsBounds());
                    continue;
                }
                // Not able to handle non-embedded container if it is not closing.
                return false;
            }
        }
        for (int i = changes.size() - 1; i >= 0; i--) {
            final TransitionInfo.Change change = changes.get(i);
            if (!change.hasFlags(FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY)
                    && !nonClosingEmbeddedArea.contains(change.getEndAbsBounds())) {
                // Unknown to animate containers outside the area of embedded activities.
                return false;
            }
        }
        // Drop the non-embedded closing change because it is occluded by embedded activities.
        for (int i = changes.size() - 1; i >= 0; i--) {
            final TransitionInfo.Change change = changes.get(i);
            if (!change.hasFlags(FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY)) {
                changes.remove(i);
            }
        }
        return true;
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        return null;
    }

    @Override
    public void setAnimScaleSetting(float scale) {
        mAnimationRunner.setAnimScaleSetting(scale);
    }

    /** Called when the animation is finished. */
    void onAnimationFinished(@NonNull IBinder transition) {
        final Transitions.TransitionFinishCallback callback =
                mTransitionCallbacks.remove(transition);
        if (callback == null) {
            throw new IllegalStateException("No finish callback found");
        }
        callback.onTransitionFinished(null /* wct */, null /* wctCB */);
    }
}
