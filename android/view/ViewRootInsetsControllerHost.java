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

package android.view;

import static android.view.InsetsController.DEBUG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.CompatibilityInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowInsetsController.Behavior;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputMethodManager;

import java.util.List;

/**
 * Implements {@link InsetsController.Host} for {@link ViewRootImpl}s.
 * @hide
 */
public class ViewRootInsetsControllerHost implements InsetsController.Host {

    private final String TAG = "VRInsetsControllerHost";

    @NonNull
    private final ViewRootImpl mViewRoot;

    private SyncRtSurfaceTransactionApplier mApplier;

    public ViewRootInsetsControllerHost(@NonNull ViewRootImpl viewRoot) {
        mViewRoot = viewRoot;
    }

    @NonNull
    @Override
    public Handler getHandler() {
        return mViewRoot.mHandler;
    }

    @Override
    public void notifyInsetsChanged() {
        mViewRoot.notifyInsetsChanged();
    }

    @Override
    public void addOnPreDrawRunnable(@NonNull Runnable r) {
        if (mViewRoot.mView == null) {
            return;
        }
        mViewRoot.mView.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        mViewRoot.mView.getViewTreeObserver().removeOnPreDrawListener(this);
                        r.run();
                        return true;
                    }
                });
        mViewRoot.mView.invalidate();
    }

    @Override
    public void dispatchWindowInsetsAnimationPrepare(@NonNull WindowInsetsAnimation animation,
            boolean isUserAnimation, boolean isResizeAnimation, boolean hasAnimationCallback) {
        if (mViewRoot.mView == null) {
            return;
        }
        if (com.android.window.flags.Flags.syncedInsetsAnimation()
                && !isUserAnimation && !isResizeAnimation && !hasAnimationCallback) {
            return;
        }
        mViewRoot.mView.dispatchWindowInsetsAnimationPrepare(animation);
    }

    @Nullable
    @Override
    public WindowInsetsAnimation.Bounds dispatchWindowInsetsAnimationStart(
            @NonNull WindowInsetsAnimation animation,
            @NonNull WindowInsetsAnimation.Bounds bounds, boolean isUserAnimation,
            boolean isResizeAnimation, boolean hasAnimationCallback) {
        if (mViewRoot.mView == null) {
            return null;
        }
        if (DEBUG) Log.d(TAG, "windowInsetsAnimation started");
        if (com.android.window.flags.Flags.syncedInsetsAnimation()
                && !isUserAnimation && !isResizeAnimation && !hasAnimationCallback) {
            return null;
        }
        return mViewRoot.mView.dispatchWindowInsetsAnimationStart(animation, bounds);
    }

    @Nullable
    @Override
    public WindowInsets dispatchWindowInsetsAnimationProgress(@NonNull WindowInsets insets,
            @NonNull InsetsState state, @NonNull List<WindowInsetsAnimation> runningAnimations,
            boolean hasUserAnimation, boolean hasResizeAnimation, boolean hasAnimationCallback,
            @WindowInsets.Type.InsetsType int hidingTypes) {
        return mViewRoot.dispatchWindowInsetsAnimationProgress(insets, state, runningAnimations,
                hasUserAnimation, hasResizeAnimation, hasAnimationCallback, hidingTypes);
    }

    @Override
    public void dispatchWindowInsetsAnimationEnd(@NonNull WindowInsetsAnimation animation,
            boolean isUserAnimation, boolean isResizeAnimation, boolean hasAnimationCallback) {
        if (DEBUG) Log.d(TAG, "windowInsetsAnimation ended");
        if (mViewRoot.mView == null) {
            // The view has already detached from window.
            return;
        }
        if (com.android.window.flags.Flags.syncedInsetsAnimation()
                && !isUserAnimation && !isResizeAnimation && !hasAnimationCallback) {
            return;
        }
        mViewRoot.mView.dispatchWindowInsetsAnimationEnd(animation);
    }

    @Override
    public void applySurfaceParams(
            @NonNull SyncRtSurfaceTransactionApplier.SurfaceParams... params) {
        if (mViewRoot.mView == null) {
            throw new IllegalStateException("View of the ViewRootImpl is not initiated.");
        }
        if (mApplier == null) {
            mApplier = new SyncRtSurfaceTransactionApplier(mViewRoot.mView);
        }
        if (mViewRoot.mView.isHardwareAccelerated() && isVisibleToUser()) {
            mApplier.scheduleApply(params);
        } else {
            // Synchronization requires hardware acceleration for now.
            // If the window isn't visible, drawing is paused and the applier won't run.
            // TODO(b/149342281): use mViewRoot.mSurface.getNextFrameNumber() to sync on every
            //  frame instead.
            final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            mApplier.applyParams(t, params);
            t.apply();
        }
    }

    @Override
    public void postInsetsAnimationCallback(@NonNull Runnable r) {
        mViewRoot.mChoreographer.postCallback(Choreographer.CALLBACK_INSETS_ANIMATION, r,
                null /* token */);
    }

    @Override
    public void updateCompatSysUiVisibility(int visibleTypes, int requestedVisibleTypes,
            int controllableTypes) {
        mViewRoot.updateCompatSysUiVisibility(visibleTypes, requestedVisibleTypes,
                controllableTypes);
    }

    @Override
    public void updateRequestedVisibleTypes(@WindowInsets.Type.InsetsType int types,
            @Nullable ImeTracker.Token statsToken) {
        try {
            if (mViewRoot.mAdded) {
                ImeTracker.forLogging().onProgress(statsToken,
                        ImeTracker.PHASE_CLIENT_UPDATE_REQUESTED_VISIBLE_TYPES);
                mViewRoot.mWindowSession.updateRequestedVisibleTypes(mViewRoot.mWindow, types,
                        statsToken);
            } else {
                ImeTracker.forLogging().onFailed(statsToken,
                        ImeTracker.PHASE_CLIENT_UPDATE_REQUESTED_VISIBLE_TYPES);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to call insetsModified", e);
        }
    }

    @Override
    public void updateAnimatingTypes(@WindowInsets.Type.InsetsType int animatingTypes,
            @Nullable ImeTracker.Token statsToken) {
        ImeTracker.forLogging().onProgress(statsToken,
                ImeTracker.PHASE_CLIENT_UPDATE_ANIMATING_TYPES);
        mViewRoot.updateAnimatingTypes(animatingTypes, statsToken);
    }

    @Override
    public boolean allowsSyncedInsetsAnimation() {
        return true;
    }

    @Override
    public boolean hasAnimationCallbacks() {
        if (mViewRoot.mView == null) {
            return false;
        }
        return mViewRoot.mView.hasWindowInsetsAnimationCallback();
    }

    @Override
    public void setSystemBarsAppearance(@Appearance int appearance, @Appearance int mask) {
        final InsetsFlags insetsFlags = mViewRoot.mWindowAttributes.insetsFlags;
        final int newAppearance = (insetsFlags.appearance & ~mask) | (appearance & mask);
        if (insetsFlags.appearance != newAppearance) {
            insetsFlags.appearance = newAppearance;
            mViewRoot.mWindowAttributesChanged = true;
            mViewRoot.scheduleTraversals();
        }
    }

    @Appearance
    @Override
    public int getSystemBarsAppearance() {
        return mViewRoot.mWindowAttributes.insetsFlags.appearance;
    }

    @Override
    public void setSystemBarsBehavior(@Behavior int behavior) {
        if (mViewRoot.mWindowAttributes.insetsFlags.behavior != behavior) {
            mViewRoot.mWindowAttributes.insetsFlags.behavior = behavior;
            mViewRoot.mWindowAttributesChanged = true;
            mViewRoot.scheduleTraversals();
        }
    }

    @Behavior
    @Override
    public int getSystemBarsBehavior() {
        return mViewRoot.mWindowAttributes.insetsFlags.behavior;
    }

    @Override
    public void releaseSurfaceControlFromRt(@NonNull SurfaceControl surfaceControl) {

         // At the time we receive new leashes (e.g. InsetsSourceConsumer is processing
         // setControl) we need to release the old leash. But we may have already scheduled
         // a SyncRtSurfaceTransaction applier to use it from the RenderThread. To avoid
         // synchronization issues we also release from the RenderThread so this release
         // happens after any existing items on the work queue.

        if (mViewRoot.mView != null && mViewRoot.mView.isHardwareAccelerated()) {
            mViewRoot.registerRtFrameCallback(frame -> {
                surfaceControl.release();
            });
            // Make sure a frame gets scheduled.
            mViewRoot.mView.invalidate();
        } else {
            surfaceControl.release();
        }
    }

    @NonNull
    @Override
    public InputMethodManager getInputMethodManager() {
        return mViewRoot.mContext.getSystemService(InputMethodManager.class);
    }

    @Nullable
    @Override
    public String getRootViewTitle() {
        return mViewRoot.getTitle().toString();
    }

    @Nullable
    @Override
    public Context getRootViewContext() {
        return mViewRoot.mContext;
    }

    @Override
    public int dipToPx(int dips) {
        return mViewRoot.dipToPx(dips);
    }

    @Nullable
    @Override
    public IBinder getWindowToken() {
        final View view = mViewRoot.getView();
        if (view == null) {
            return null;
        }
        return view.getWindowToken();
    }

    @Nullable
    @Override
    public CompatibilityInfo.Translator getTranslator() {
        return mViewRoot.mTranslator;
    }

    @Override
    public boolean isHandlingPointerEvent() {
        return mViewRoot.isHandlingPointerEvent();
    }

    private boolean isVisibleToUser() {
        return mViewRoot.getHostVisibility() == View.VISIBLE;
    }
}
