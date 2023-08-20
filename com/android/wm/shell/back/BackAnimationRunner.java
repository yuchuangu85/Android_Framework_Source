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

package com.android.wm.shell.back;

import static android.view.WindowManager.TRANSIT_OLD_UNSET;

import android.annotation.NonNull;
import android.os.RemoteException;
import android.util.Log;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationTarget;
import android.window.IBackAnimationRunner;
import android.window.IOnBackInvokedCallback;

/**
 * Used to register the animation callback and runner, it will trigger result if gesture was finish
 * before it received IBackAnimationRunner#onAnimationStart, so the controller could continue
 * trigger the real back behavior.
 */
class BackAnimationRunner {
    private static final String TAG = "ShellBackPreview";

    private final IOnBackInvokedCallback mCallback;
    private final IRemoteAnimationRunner mRunner;

    // Whether we are waiting to receive onAnimationStart
    private boolean mWaitingAnimation;

    /** True when the back animation is cancelled */
    private boolean mAnimationCancelled;

    BackAnimationRunner(@NonNull IOnBackInvokedCallback callback,
            @NonNull IRemoteAnimationRunner runner) {
        mCallback = callback;
        mRunner = runner;
    }

    /** Returns the registered animation runner */
    IRemoteAnimationRunner getRunner() {
        return mRunner;
    }

    /** Returns the registered animation callback */
    IOnBackInvokedCallback getCallback() {
        return mCallback;
    }

    /**
     * Called from {@link IBackAnimationRunner}, it will deliver these
     * {@link RemoteAnimationTarget}s to the corresponding runner.
     */
    void startAnimation(RemoteAnimationTarget[] apps, RemoteAnimationTarget[] wallpapers,
            RemoteAnimationTarget[] nonApps, Runnable finishedCallback) {
        final IRemoteAnimationFinishedCallback callback =
                new IRemoteAnimationFinishedCallback.Stub() {
                    @Override
                    public void onAnimationFinished() {
                        finishedCallback.run();
                    }
                };
        mWaitingAnimation = false;
        try {
            getRunner().onAnimationStart(TRANSIT_OLD_UNSET, apps, wallpapers,
                    nonApps, callback);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed call onAnimationStart", e);
        }
    }

    void startGesture() {
        mWaitingAnimation = true;
        mAnimationCancelled = false;
    }

    boolean isWaitingAnimation() {
        return mWaitingAnimation;
    }

    void cancelAnimation() {
        mWaitingAnimation = false;
        mAnimationCancelled = true;
    }

    boolean isAnimationCancelled() {
        return mAnimationCancelled;
    }

    void resetWaitingAnimation() {
        mWaitingAnimation = false;
    }
}
