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
import android.annotation.UiThread;
import android.content.res.CompatibilityInfo;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Trace;
import android.util.Log;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.InsetsController.AnimationType;
import android.view.InsetsController.LayoutInsetsDuringAnimation;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsAnimation.Bounds;
import android.view.inputmethod.ImeTracker;

/**
 * Insets animation runner that uses {@link InsetsAnimationThread} to run the animation off from the
 * main thread.
 *
 * @hide
 */
public class InsetsAnimationThreadControlRunner implements InsetsAnimationControlRunner {

    private static final String TAG = "InsetsAnimThreadRunner";
    @NonNull
    private final InsetsAnimationControlImpl mControl;
    @NonNull
    private final InsetsAnimationControlCallbacks mOuterCallbacks;
    @NonNull
    private final Handler mMainThreadHandler;
    private final InsetsAnimationControlCallbacks mCallbacks =
            new InsetsAnimationControlCallbacks() {

        @Override
        @UiThread
        public <T extends InsetsAnimationControlRunner & InternalInsetsAnimationController>
        void startAnimation(@NonNull T runner,
                @NonNull WindowInsetsAnimationControlListener listener, @InsetsType int types,
                @NonNull WindowInsetsAnimation animation, @NonNull Bounds bounds) {
            // Animation will be started in constructor already.
        }

        @Override
        public void scheduleApplyChangeInsets(@NonNull InsetsAnimationControlRunner runner) {
            synchronized (mControl) {
                // This reads the surface position on the animation thread, but the surface position
                // would be updated on the UI thread, so we need this critical section.
                // See: updateSurfacePosition.
                mControl.applyChangeInsets(null /* outState */);
            }
        }

        @Override
        public void notifyFinished(@NonNull InsetsAnimationControlRunner runner, boolean shown) {
            Trace.asyncTraceEnd(Trace.TRACE_TAG_VIEW,
                    "InsetsAsyncAnimation: " + WindowInsets.Type.toString(runner.getTypes()),
                    runner.getTypes());
            InsetsController.releaseControls(mControl.getControls());
            mMainThreadHandler.post(() ->
                    mOuterCallbacks.notifyFinished(InsetsAnimationThreadControlRunner.this, shown));
        }

        @Override
        public void releaseSurfaceControlFromRt(@NonNull SurfaceControl sc) {
            if (DEBUG) Log.d(TAG, "releaseSurfaceControlFromRt");
            // Since we don't push the SurfaceParams to the RT we can release directly
            sc.release();
        }

        @Override
        public void reportPerceptible(@InsetsType int types, boolean perceptible) {
            mMainThreadHandler.post(() -> mOuterCallbacks.reportPerceptible(types, perceptible));
        }
    };

    private final SurfaceParamsApplier mSurfaceParamsApplier = new SurfaceParamsApplier() {

        private final float[] mTmpFloat9 = new float[9];

        @Override
        public void applySurfaceParams(
                @NonNull SyncRtSurfaceTransactionApplier.SurfaceParams... params) {
            final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            for (int i = params.length - 1; i >= 0; i--) {
                SyncRtSurfaceTransactionApplier.applyParams(t, params[i], mTmpFloat9);
            }
            t.setFrameTimelineVsync(Choreographer.getInstance().getVsyncId());
            t.apply();
            t.close();
        }
    };

    @UiThread
    public InsetsAnimationThreadControlRunner(@NonNull SparseArray<InsetsSourceControl> controls,
            @Nullable Rect frame, @Nullable Rect bounds, @NonNull InsetsState state,
            @NonNull WindowInsetsAnimationControlListener listener, @InsetsType int types,
            @NonNull InsetsAnimationControlCallbacks controller,
            @NonNull InsetsAnimationSpec insetsAnimationSpec, @AnimationType int animationType,
            @LayoutInsetsDuringAnimation int layoutInsetsDuringAnimation,
            @Nullable CompatibilityInfo.Translator translator, @NonNull Handler mainThreadHandler,
            @Nullable ImeTracker.Token statsToken) {
        mMainThreadHandler = mainThreadHandler;
        mOuterCallbacks = controller;
        mControl = new InsetsAnimationControlImpl(controls, frame, bounds, state, listener, types,
                mCallbacks, mSurfaceParamsApplier, insetsAnimationSpec, animationType,
                layoutInsetsDuringAnimation, translator, statsToken);
        InsetsAnimationThread.getHandler().post(() -> {
            if (mControl.isCancelled()) {
                return;
            }
            Trace.asyncTraceBegin(Trace.TRACE_TAG_VIEW,
                    "InsetsAsyncAnimation: " + WindowInsets.Type.toString(types), types);
            listener.onReady(mControl, types);
        });
    }

    @Override
    @UiThread
    public void dumpDebug(@NonNull ProtoOutputStream proto, long fieldId) {
        mControl.dumpDebug(proto, fieldId);
    }

    @Override
    @Nullable
    public ImeTracker.Token getStatsToken() {
        return mControl.getStatsToken();
    }

    @Override
    @UiThread
    public int getTypes() {
        return mControl.getTypes();
    }

    @Override
    @UiThread
    public int getControllingTypes() {
        return mControl.getControllingTypes();
    }

    @Override
    @UiThread
    public void notifyControlRevoked(@InsetsType int types) {
        mControl.notifyControlRevoked(types);
    }

    @Override
    @UiThread
    public void updateSurfacePosition(@NonNull SparseArray<InsetsSourceControl> controls) {
        synchronized (mControl) {
            // This is called from the UI thread, however, the surface position will be used on the
            // animation thread, so we need this critical section. See: scheduleApplyChangeInsets.
            mControl.updateSurfacePosition(controls);
        }
    }

    @Override
    @UiThread
    public boolean willUpdateSurface() {
        synchronized (mControl) {
            // This is called from the UI thread, however, applyChangeInsets would be called on the
            // animation thread, so we need this critical section to ensure this is not called
            // during applyChangeInsets. See: scheduleApplyChangeInsets.
            return mControl.willUpdateSurface();
        }
    }

    @Override
    @UiThread
    public void cancel() {
        InsetsAnimationThread.getHandler().post(mControl::cancel);
    }

    @NonNull
    @Override
    @UiThread
    public WindowInsetsAnimation getAnimation() {
        return mControl.getAnimation();
    }

    @AnimationType
    @Override
    public int getAnimationType() {
        return mControl.getAnimationType();
    }

    @Override
    public boolean hasAnimationCallback() {
        return false;
    }

    @NonNull
    @Override
    public SurfaceParamsApplier getSurfaceParamsApplier() {
        return mSurfaceParamsApplier;
    }

    @Override
    public void updateLayoutInsetsDuringAnimation(
            @LayoutInsetsDuringAnimation int layoutInsetsDuringAnimation) {
        InsetsAnimationThread.getHandler().post(
                () -> mControl.updateLayoutInsetsDuringAnimation(layoutInsetsDuringAnimation));
    }
}
