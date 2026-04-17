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

package android.companion.virtual.computercontrol;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.AnyThread;
import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityDisplayProxy;
import android.view.accessibility.AccessibilityEvent;

import com.android.internal.annotations.GuardedBy;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * {@link AccessibilityDisplayProxy} for gathering A11y signals for apps running on a
 * {@link ComputerControlSession}, and inferring when the session can be considered stable.
 */
final class ComputerControlAccessibilityProxy extends AccessibilityDisplayProxy {

    private final Handler mHandler;

    /**
     * Wraps the currently registered {@link ComputerControlSession.StabilityListener}. A null value
     * indicates no stability listener is set.
     */
    @Nullable
    @GuardedBy("this")
    private StabilitySignalTracker mStabilitySignalTracker;

    @GuardedBy("this")
    private boolean mIsFirstFrameReceived = false;

    ComputerControlAccessibilityProxy(int displayId, @NonNull Handler handler) {
        super(displayId, handler::post, getAccessibilityServiceInfos());

        mHandler = handler;
    }

    @Override
    public void onAccessibilityEvent(@NonNull AccessibilityEvent event) {
        synchronized (this) {
            if (mStabilitySignalTracker == null) {
                return;
            }
            mStabilitySignalTracker.onAccessibilityEvent();
        }
    }

    /**
     * Called when a frame is available for the display.
     */
    void onImageAvailable() {
        synchronized (this) {
            if (mIsFirstFrameReceived) {
                return;
            }
            mIsFirstFrameReceived = true;
            if (mStabilitySignalTracker != null) {
                mStabilitySignalTracker.onFirstFrameReceived();
            }
        }
    }

    /**
     * Called whenever something significant happens in the ComputerControl session (new input
     * events, new apps launched, etc.).
     */
    void resetStabilityState() {
        synchronized (this) {
            if (mStabilitySignalTracker != null) {
                mStabilitySignalTracker.resetStabilityState();
            }
        }
    }

    /**
     * Sets a {@link ComputerControlSession.StabilityListener} to be invoked on a given
     * {@link Executor} whenever a session is considered stable.
     */
    void setStabilityListener(long timeoutMillis, @CallbackExecutor @NonNull Executor executor,
            @NonNull ComputerControlSession.StabilityListener listener) {
        synchronized (this) {
            if (mStabilitySignalTracker != null) {
                throw new IllegalStateException("A stability listener is already set.");
            }
            final var callbackRecord = new ComputerControlSession.StabilityListener() {
                @Override
                public void onSessionStable() {
                    synchronized (ComputerControlAccessibilityProxy.this) {
                        // Ensure the listener does not fire after it was changed or removed.
                        if (mStabilitySignalTracker.mStabilityListener == this) {
                            executor.execute(listener::onSessionStable);
                        }
                    }
                }
            };
            mStabilitySignalTracker =
                    new StabilitySignalTracker(timeoutMillis, mHandler, callbackRecord,
                            mIsFirstFrameReceived);
        }
    }

    /**
     * Clears any {@link ComputerControlSession.StabilityListener} that was set previously.
     */
    void clearStabilityListener() {
        synchronized (this) {
            if (mStabilitySignalTracker == null) {
                throw new IllegalStateException("A stability listener is not set.");
            }
            mStabilitySignalTracker.close();
            mStabilitySignalTracker = null;
        }
    }

    private static List<AccessibilityServiceInfo> getAccessibilityServiceInfos() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        return List.of(info);
    }

    /**
     * Tracks the stability of a {@link ComputerControlSession}.
     *
     * Thread safe.
     */
    private static final class StabilitySignalTracker implements AutoCloseable,
            EventIdleTracker.Callback {

        final ComputerControlSession.StabilityListener mStabilityListener;

        private final Handler mHandler;
        private final EventIdleTracker mEventIdleTracker;
        private boolean mIsFirstFrameReceived;
        private boolean mIsUnstable;

        StabilitySignalTracker(long timeoutMillis, Handler handler,
                ComputerControlSession.StabilityListener listener, boolean isFirstFrameReceived) {
            mHandler = handler;
            mStabilityListener = listener;
            mEventIdleTracker = new EventIdleTracker(handler, timeoutMillis);
            mIsFirstFrameReceived = isFirstFrameReceived;
        }

        void onFirstFrameReceived() {
            mHandler.post(() -> {
                if (mIsFirstFrameReceived) {
                    throw new IllegalStateException("First frame was already received!");
                }
                mIsFirstFrameReceived = true;
                checkStability();
            });
        }

        void onAccessibilityEvent() {
            mHandler.post(mEventIdleTracker::onEvent);
        }

        void resetStabilityState() {
            mHandler.post(() -> {
                mIsUnstable = true;
                mEventIdleTracker.reset();
                mEventIdleTracker.registerOneShotIdleCallback(this);
            });
        }

        @Override
        public void close() {
            mHandler.post(mEventIdleTracker::reset);
        }

        @Override
        public void onEventIdle() {
            checkStability();
        }

        private void checkStability() {
            if (!mIsUnstable || mEventIdleTracker.hasPendingCallback() || !mIsFirstFrameReceived) {
                return;
            }
            mIsUnstable = false;
            mStabilityListener.onSessionStable();
        }
    }

    /**
     * Tracks the idle state of an event signal.
     *
     * NOTE: This class is NOT thread safe and all interactions must happen on the
     * {@code mHandler} thread, unless otherwise marked.
     */
    private static final class EventIdleTracker {
        private final Handler mHandler;
        private final long mEventIdleTimeoutMs;

        private Callback mPendingCallback;

        private final Runnable mCallbackExecutor = new Runnable() {
            @Override
            public void run() {
                if (mPendingCallback == null) {
                    return;
                }
                Callback pendingCallback = mPendingCallback;
                reset();
                pendingCallback.onEventIdle();
            }
        };

        @AnyThread
        EventIdleTracker(@NonNull Handler handler, long eventIdleTimeoutMs) {
            mHandler = handler;
            mEventIdleTimeoutMs = eventIdleTimeoutMs;
        }

        /** Called when an event is received, which resets the idle timer. */
        void onEvent() {
            if (mPendingCallback == null) {
                return;
            }

            mHandler.removeCallbacks(mCallbackExecutor);
            long idleTime = SystemClock.uptimeMillis() + mEventIdleTimeoutMs;
            mHandler.postAtTime(mCallbackExecutor, idleTime);
        }

        void registerOneShotIdleCallback(@NonNull Callback callback) {
            if (mPendingCallback != null) {
                throw new IllegalStateException("There is already a pending callback!");
            }

            mPendingCallback = callback;
            long now = SystemClock.uptimeMillis();
            mHandler.postAtTime(mCallbackExecutor, now + mEventIdleTimeoutMs);
        }

        boolean hasPendingCallback() {
            return mPendingCallback != null;
        }

        void reset() {
            mPendingCallback = null;
            mHandler.removeCallbacks(mCallbackExecutor);
        }

        interface Callback {
            void onEventIdle();
        }
    }
}
