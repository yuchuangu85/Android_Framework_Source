/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.CancellationSignal;
import android.view.WindowInsets.Type.InsetsType;
import android.view.animation.Interpolator;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;

/**
 * An insets controller that keeps track of pending requests. This is such that an app can freely
 * use {@link WindowInsetsController} before the view root is attached during activity startup.
 *
 * @hide
 */
public class PendingInsetsController implements WindowInsetsController {

    private static final int KEEP_BEHAVIOR = -1;
    @NonNull
    private final ArrayList<PendingRequest> mRequests = new ArrayList<>();
    @Appearance
    private int mAppearance;
    @Appearance
    private int mAppearanceMask;
    @Appearance
    private int mAppearanceFromResource;
    @Appearance
    private int mAppearanceFromResourceMask;
    @Behavior
    private int mBehavior = KEEP_BEHAVIOR;
    private boolean mAnimationsDisabled;
    @NonNull
    private final InsetsState mDummyState = new InsetsState();
    @Nullable
    private InsetsController mReplayedInsetsController;
    @NonNull
    private final ArrayList<OnControllableInsetsChangedListener> mControllableInsetsChangedListeners
            = new ArrayList<>();
    private int mImeCaptionBarInsetsHeight = 0;
    @Nullable
    private WindowInsetsAnimationControlListener mLoggingListener;
    @InsetsType
    private int mRequestedVisibleTypes = WindowInsets.Type.defaultVisible();

    @Override
    public void show(@InsetsType int types) {
        if (mReplayedInsetsController != null) {
            mReplayedInsetsController.show(types);
        } else {
            mRequests.add(new ShowRequest(types));
            mRequestedVisibleTypes |= types;
        }
    }

    @Override
    public void hide(@InsetsType int types) {
        if (mReplayedInsetsController != null) {
            mReplayedInsetsController.hide(types);
        } else {
            mRequests.add(new HideRequest(types));
            mRequestedVisibleTypes &= ~types;
        }
    }

    @Override
    public void setSystemBarsAppearance(@Appearance int appearance, @Appearance int mask) {
        if (mReplayedInsetsController != null) {
            mReplayedInsetsController.setSystemBarsAppearance(appearance, mask);
        } else {
            mAppearance = (mAppearance & ~mask) | (appearance & mask);
            mAppearanceMask |= mask;
        }
    }

    @Override
    public void setSystemBarsAppearanceFromResource(@Appearance int appearance,
            @Appearance int mask) {
        if (mReplayedInsetsController != null) {
            mReplayedInsetsController.setSystemBarsAppearanceFromResource(appearance, mask);
        } else {
            mAppearanceFromResource = (mAppearanceFromResource & ~mask) | (appearance & mask);
            mAppearanceFromResourceMask |= mask;
        }
    }

    @Appearance
    @Override
    public int getSystemBarsAppearance() {
        if (mReplayedInsetsController != null) {
            return mReplayedInsetsController.getSystemBarsAppearance();
        }
        return mAppearance | (mAppearanceFromResource & ~mAppearanceMask);
    }

    @Override
    public void setImeCaptionBarInsetsHeight(int height) {
        mImeCaptionBarInsetsHeight = height;
    }

    @Override
    public void setSystemBarsBehavior(@Behavior int behavior) {
        if (mReplayedInsetsController != null) {
            mReplayedInsetsController.setSystemBarsBehavior(behavior);
        } else {
            mBehavior = behavior;
        }
    }

    @Behavior
    @Override
    public int getSystemBarsBehavior() {
        if (mReplayedInsetsController != null) {
            return mReplayedInsetsController.getSystemBarsBehavior();
        }
        if (mBehavior == KEEP_BEHAVIOR) {
            return BEHAVIOR_DEFAULT;
        }
        return mBehavior;
    }

    @Override
    public void setAnimationsDisabled(boolean disable) {
        if (mReplayedInsetsController != null) {
            mReplayedInsetsController.setAnimationsDisabled(disable);
        } else {
            mAnimationsDisabled = disable;
        }
    }

    @Override
    @NonNull
    public InsetsState getState() {
        return mDummyState;
    }

    @Override
    public @InsetsType int getRequestedVisibleTypes() {
        if (mReplayedInsetsController != null) {
            return mReplayedInsetsController.getRequestedVisibleTypes();
        }
        return mRequestedVisibleTypes;
    }

    @Override
    public void addOnControllableInsetsChangedListener(
            @NonNull OnControllableInsetsChangedListener listener) {
        if (mReplayedInsetsController != null) {
            mReplayedInsetsController.addOnControllableInsetsChangedListener(listener);
        } else {
            mControllableInsetsChangedListeners.add(listener);
            listener.onControllableInsetsChanged(this, 0);
        }
    }

    @Override
    public void removeOnControllableInsetsChangedListener(
            @NonNull OnControllableInsetsChangedListener listener) {
        if (mReplayedInsetsController != null) {
            mReplayedInsetsController.removeOnControllableInsetsChangedListener(listener);
        } else {
            mControllableInsetsChangedListeners.remove(listener);
        }
    }

    /**
     * Replays the commands on {@code controller} and attaches it to this instance such that any
     * calls will be forwarded to the real instance in the future.
     */
    @VisibleForTesting
    public void replayAndAttach(@NonNull InsetsController controller) {
        if (mBehavior != KEEP_BEHAVIOR) {
            controller.setSystemBarsBehavior(mBehavior);
        }
        if (mAppearanceMask != 0) {
            controller.setSystemBarsAppearance(mAppearance, mAppearanceMask);
        }
        if (mAppearanceFromResourceMask != 0) {
            controller.setSystemBarsAppearanceFromResource(
                    mAppearanceFromResource, mAppearanceFromResourceMask);
        }
        if (mImeCaptionBarInsetsHeight != 0) {
            controller.setImeCaptionBarInsetsHeight(mImeCaptionBarInsetsHeight);
        }
        if (mAnimationsDisabled) {
            controller.setAnimationsDisabled(true);
        }
        int size = mRequests.size();
        for (int i = 0; i < size; i++) {
            mRequests.get(i).replay(controller);
        }
        size = mControllableInsetsChangedListeners.size();
        for (int i = 0; i < size; i++) {
            controller.addOnControllableInsetsChangedListener(
                    mControllableInsetsChangedListeners.get(i));
        }
        if (mLoggingListener != null) {
            controller.setSystemDrivenInsetsAnimationLoggingListener(mLoggingListener);
        }

        // Reset all state so it doesn't get applied twice just in case
        mRequests.clear();
        mControllableInsetsChangedListeners.clear();
        mBehavior = KEEP_BEHAVIOR;
        mAppearance = 0;
        mAppearanceMask = 0;
        mAppearanceFromResource = 0;
        mAppearanceFromResourceMask = 0;
        mAnimationsDisabled = false;
        mLoggingListener = null;
        mRequestedVisibleTypes = WindowInsets.Type.defaultVisible();
        // After replaying, we forward everything directly to the replayed instance.
        mReplayedInsetsController = controller;
    }

    /**
     * Detaches the controller to no longer forward calls to the real instance.
     */
    @VisibleForTesting
    public void detach() {
        mReplayedInsetsController = null;
    }

    @Override
    public void setSystemDrivenInsetsAnimationLoggingListener(
            @Nullable WindowInsetsAnimationControlListener listener) {
        if (mReplayedInsetsController != null) {
            mReplayedInsetsController.setSystemDrivenInsetsAnimationLoggingListener(listener);
        } else {
            mLoggingListener = listener;
        }
    }

    @Override
    public void controlWindowInsetsAnimation(@InsetsType int types, long durationMillis,
            @Nullable Interpolator interpolator,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull WindowInsetsAnimationControlListener listener) {
        if (mReplayedInsetsController != null) {
            mReplayedInsetsController.controlWindowInsetsAnimation(types, durationMillis,
                    interpolator, cancellationSignal, listener);
        } else {
            listener.onCancelled(null);
        }
    }

    private interface PendingRequest {
        void replay(@NonNull InsetsController controller);
    }

    private static final class ShowRequest implements PendingRequest {

        @InsetsType
        private final int mTypes;

        ShowRequest(@InsetsType int types) {
            mTypes = types;
        }

        @Override
        public void replay(@NonNull InsetsController controller) {
            controller.show(mTypes);
        }
    }

    private static final class HideRequest implements PendingRequest {

        @InsetsType
        private final int mTypes;

        HideRequest(@InsetsType int types) {
            mTypes = types;
        }

        @Override
        public void replay(@NonNull InsetsController controller) {
            controller.hide(mTypes);
        }
    }
}
