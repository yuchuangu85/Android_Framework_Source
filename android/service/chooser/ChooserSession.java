/*
 * Copyright 2025 The Android Open Source Project
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

package android.service.chooser;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A class that represents an interactive Chooser session.
 * <p>A {@link StateListener} callback can be used to receive updates about the
 * session and communication from Chooser.</p>
 *
 * @see ChooserManager
 */
@FlaggedApi(Flags.FLAG_INTERACTIVE_CHOOSER)
public final class ChooserSession {
    /**
     * @hide
     */
    public static final String EXTRA_CHOOSER_SESSION =
            "com.android.extra.EXTRA_CHOOSER_INTERACTIVE_CALLBACK";

    private static final String TAG = "ChooserSession";

    /**
     * The initial state: the session is initialized but the Chooser has not yet connected.
     */
    public static final int STATE_INITIALIZED = 0;
    /**
     * The chooser is connected and can be updated.
     */
    public static final int STATE_STARTED = 1;
    /**
     * The session is closed by either side.
     */
    public static final int STATE_CLOSED = 2;

    /** @hide */
    @IntDef(prefix = { "STATE_" }, value = {
            STATE_INITIALIZED,
            STATE_STARTED,
            STATE_CLOSED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    /**
     * Holds information about the Chooser's bounds.
     */
    private static class BoundsInfo {
        private final Rect mBounds;
        private final Rect mDefaultBounds;

        private BoundsInfo(@NonNull Rect bounds, @NonNull Rect defaultBounds) {
            mBounds = bounds;
            mDefaultBounds = defaultBounds;
        }

        @NonNull
        Rect getBounds() {
            return mBounds;
        }

        @NonNull
        Rect getDefaultBounds() {
            return mDefaultBounds;
        }
    }

    /**
     * A callback interface for Chooser session state updates.
     */
    public interface StateListener {
        /**
         * Gets invoked when ChooserManager lifecycle state has changed.
         */
        void onStateChanged(@State int state);

        /**
         * Gets invoked when the Chooser bounds are changed. The rect parameter represents Chooser
         * window bounds in pixels.
         */
        void onBoundsChanged(@NonNull Rect bounds);
    }

    private final ChooserSessionImpl mChooserSession = new ChooserSessionImpl();
    private final ChooserSessionToken mToken = new ChooserSessionToken(mChooserSession);

    /*package*/ ChooserSession() {}

    /** @hide */
    /*package*/ IBinder getBinder() {
        return mChooserSession;
    }

    /**
     * Returns this session's token. A token serves as a session identifier and can be used to
     * retrieve an active session from the {@link ChooserManager}.
     */
    @NonNull
    public ChooserSessionToken getToken() {
        return mToken;
    }

    /**
     * @return current session state.
     *
     * @see ChooserSession#STATE_INITIALIZED
     * @see ChooserSession#STATE_STARTED
     * @see ChooserSession#STATE_CLOSED
     */
    @State
    public int getState() {
        return mChooserSession.getState();
    }

    /**
     * Terminates the session and closes Chooser.
     */
    public void endSession() {
        mChooserSession.close();
    }

    /**
     * Updates the chooser intent in an active Chooser session, causing Chooser to refresh its state
     * and targets.
     * <p>
     * Only updates to the following extras in the provided intent are respected:
     * <ul>
     * <li>{@link Intent#EXTRA_INTENT}</li>
     * <li>{@link Intent#EXTRA_EXCLUDE_COMPONENTS}</li>
     * <li>{@link Intent#EXTRA_CHOOSER_TARGETS}</li>
     * <li>{@link Intent#EXTRA_ALTERNATE_INTENTS}</li>
     * <li>{@link Intent#EXTRA_REPLACEMENT_EXTRAS}</li>
     * <li>{@link Intent#EXTRA_INITIAL_INTENTS}</li>
     * <li>{@link Intent#EXTRA_CHOOSER_RESULT_INTENT_SENDER}</li>
     * <li>{@link Intent#EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER}</li>
     * </ul>
     * This method is a no-op if the session is not in the {@link #STATE_STARTED} state.
     *
     * @param intent The new intent to apply to the session.
     */
    public void updateIntent(@NonNull Intent intent) {
        Objects.requireNonNull(intent, "intent should not be null");
        IChooserController controller = mChooserSession.getChooserController();
        if (controller != null) {
            try {
                controller.updateIntent(intent);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Sets the minimized state of the Chooser UI.
     * <p>
     * Passing {@code true} requests that the Chooser minimize to its smallest footprint
     * to yield screen space for the calling application. This state is temporary and can be
     * overridden by any direct user interaction with the Chooser (e.g., dragging the share sheet).
     *
     * @param isMinimized {@code true} to request that the Chooser be minimized;
     * {@code false} to restore it to its standard layout.
     *
     * @hide
     */
    public void setMinimized(boolean isMinimized) {
        mChooserSession.setMinimized(isMinimized);
    }

    /**
     * Sets whether the targets in the chooser UI are enabled. By default targets are enabled.
     * <p>
     * This method is primarily intended to allow for managing a transient state,
     * particularly useful during long-running operations. By disabling targets,
     * launching application can prevent unintended interactions.
     * <p>A no-op when the session is not in the {@link #STATE_STARTED}.</p>
     */
    public void setTargetsEnabled(boolean isEnabled) {
        mChooserSession.setTargetsEnabled(isEnabled);
    }

    /**
     * Gets the last bounds reported by the Chooser.
     *
     * @return the most recently reported Chooser bounds, or {@code null} if bounds have not yet
     * been received via {@link ChooserSession.StateListener#onBoundsChanged(Rect)}.
     */
    @Nullable
    public Rect getBounds() {
        BoundsInfo boundsInfo = mChooserSession.mBoundsInfo.get();
        return boundsInfo == null ? null : boundsInfo.getBounds();
    }

    /**
     * Returns the initial resting bound for the Chooser, or {@code null} if bounds have not yet
     * been received. This is the target position of the Chooser after any animation and data
     * loading are complete.
     *
     * <p>This value is intended to help applications make an informed decision on how to react to
     * Chooser bounds changes. For example, if the Chooser is a bottom sheet launched into a compact
     * representation (but whose bounds can change later), this value can provide the dimensions for
     * that initial compact representation. An application that wishes to limit its UI changes only
     * to that specific size can use this value.</p>
     *
     * <p>It is possible for this value to change (e.g., after a configuration change or
     * after a target intent update) and the updated value should be available to the clients at
     * the time of {@link ChooserSession.StateListener#onBoundsChanged(Rect)} invocation.</p>
     */
    @FlaggedApi(Flags.FLAG_INTERACTIVE_CHOOSER_DEFAULT_BOUNDS)
    @Nullable
    public Rect getInitialRestingBounds() {
        BoundsInfo boundsInfo = mChooserSession.mBoundsInfo.get();
        return boundsInfo == null ? null : boundsInfo.getDefaultBounds();
    }

    /**
     * @param listener make sure that the callback is cleared at the end of a component's lifecycle
     * (e.g. Activity) or provide a properly maintained WeakReference wrapper to avoid memory leaks.
     */
    public void addStateListener(
            @NonNull Executor executor, @NonNull StateListener listener) {
        Objects.requireNonNull(executor, "executor should not be null");
        Objects.requireNonNull(listener, "listener should not be null");
        mChooserSession.addStateListener(executor, listener);
    }

    /**
     * Removes a previously added UpdateListener callback.
     */
    public void removeStateListener(@NonNull StateListener listener) {
        Objects.requireNonNull(listener, "listener should not be null");
        mChooserSession.removeStateListener(listener);
    }

    /*package*/ static boolean isSessionBinder(IBinder binder) {
        return binder instanceof ChooserSessionImpl;
    }

    private static class ChooserSessionImpl extends IChooserControllerCallback.Stub {
        private final Object mListenerLock = new Object();
        @GuardedBy("mListenerLock")
        private Map<StateListener, UpdateListenerWrapper> mListenerMap = new HashMap<>();

        private final Object mStateLock = new Object();
        @GuardedBy("mStateLock")
        @Nullable
        private IChooserController mChooserController;
        @GuardedBy("mStateLock")
        @Nullable
        private IBinder.DeathRecipient mChooserControllerLinkToDeath;
        @GuardedBy("mStateLock")
        @ChooserSession.State
        private int mState = STATE_INITIALIZED;

        private final AtomicReference<BoundsInfo> mBoundsInfo = new AtomicReference<>();

        @Override
        public void registerChooserController(
                @Nullable final IChooserController chooserController) {
            if (chooserController == null) {
                Log.d(TAG, "Received null controller, closing the session");
                onClosed();
                return;
            }
            Log.d(
                    TAG,
                    "setIntentUpdater; state: " + mState
                            + ", chooserController: " + chooserController);
            boolean updateState = false;
            @State final int newState;
            synchronized (mStateLock) {
                if (mState == STATE_CLOSED) {
                    safeUpdateChooserIntent(chooserController, null);
                    return;
                }
                if (areEqual(mChooserController, chooserController)) {
                    return;
                }
                disconnectCurrentController();
                connectController(chooserController);
                if (mChooserController == null) {
                    mState = STATE_CLOSED;
                    updateState = true;
                } else if (mState == STATE_INITIALIZED) {
                    mState = STATE_STARTED;
                    updateState = true;
                }
                newState = mState;
            }
            if (updateState) {
                notifyListeners((listener -> {
                    if (isActive()) {
                        listener.onStateChanged(newState);
                    }
                }));
            }
        }

        @Override
        public void onBoundsChanged(@NonNull Rect bounds, @NonNull Rect defaultBounds) {
            BoundsInfo boundsInfo = new BoundsInfo(bounds, defaultBounds);
            mBoundsInfo.set(boundsInfo);
            notifyListeners((listener) -> {
                if (isActive()) {
                    listener.onBoundsChanged(bounds);
                }
            });
        }

        @Override
        public void onClosed() {
            doClose(true);
        }

        public void close() {
            doClose(false);
        }

        public void setMinimized(boolean isMinimized) {
            IChooserController controller = getChooserController();
            if (controller != null) {
                try {
                    controller.setMinimized(isMinimized);
                } catch (DeadObjectException e) {
                    doClose(true);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        public void setTargetsEnabled(boolean isEnabled) {
            IChooserController controller = getChooserController();
            if (controller != null) {
                try {
                    controller.setTargetsEnabled(isEnabled);
                } catch (DeadObjectException e) {
                    doClose(true);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        @State
        public int getState() {
            synchronized (mStateLock) {
                return mState;
            }
        }

        @Nullable
        public IChooserController getChooserController() {
            synchronized (mStateLock) {
                return mChooserController;
            }
        }

        public void addStateListener(Executor executor, StateListener listener) {
            synchronized (mListenerLock) {
                if (!mListenerMap.containsKey(listener)) {
                    mListenerMap = new HashMap<>(mListenerMap);
                    mListenerMap.put(listener, new UpdateListenerWrapper(listener, executor));
                }
            }
        }

        public void removeStateListener(StateListener listener) {
            synchronized (mListenerLock) {
                if (mListenerMap.containsKey(listener)) {
                    mListenerMap = new HashMap<>(mListenerMap);
                    UpdateListenerWrapper lw = mListenerMap.remove(listener);
                    lw.isSubscribed.set(false);
                }
            }
        }

        private boolean isActive() {
            synchronized (mStateLock) {
                return mState != STATE_CLOSED;
            }
        }

        private void notifyListeners(Consumer<StateListener> block) {
            Collection<UpdateListenerWrapper> listeners;
            synchronized (mListenerLock) {
                listeners = mListenerMap.values();
            }
            final long token = Binder.clearCallingIdentity();
            try {
                for (UpdateListenerWrapper lw : listeners) {
                    lw.executor.execute(() -> {
                        if (lw.isSubscribed.get()) {
                            block.accept(lw.listener);
                        }
                    });
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private void doClose(boolean isClosedByChooser) {
            boolean wasActive;
            synchronized (mStateLock) {
                wasActive = mState != STATE_CLOSED;
                mState = STATE_CLOSED;
                if (!isClosedByChooser && mChooserController != null) {
                    safeUpdateChooserIntent(mChooserController, null);
                }
                disconnectCurrentController();
            }
            if (wasActive && isClosedByChooser) {
                notifyListeners((listener) -> listener.onStateChanged(STATE_CLOSED));
            }
            synchronized (mListenerLock) {
                mListenerMap = Collections.emptyMap();
            }
        }

        @GuardedBy("mStateLock")
        private void disconnectCurrentController() {
            if (mChooserController != null && mChooserControllerLinkToDeath != null) {
                safeUnlinkToDeath(
                        mChooserController.asBinder(), mChooserControllerLinkToDeath);
            }
            mChooserController = null;
            mChooserControllerLinkToDeath = null;
        }

        @GuardedBy("mStateLock")
        private void connectController(IChooserController chooserController) {
            mChooserController = chooserController;
            mChooserControllerLinkToDeath = createDeathRecipient(chooserController);
            try {
                chooserController.asBinder().linkToDeath(mChooserControllerLinkToDeath, 0);
            } catch (RemoteException e) {
                // binder has already died
                mChooserController = null;
                mChooserControllerLinkToDeath = null;
            }
        }

        private IBinder.DeathRecipient createDeathRecipient(IChooserController chooserController) {
            return () -> {
                Log.d(TAG, "chooser died");
                boolean shouldClose = false;
                synchronized (mStateLock) {
                    if (areEqual(mChooserController, chooserController)) {
                        // is it ever true?
                        mChooserController = null;
                        mChooserControllerLinkToDeath = null;
                        shouldClose = true;
                    }
                }
                if (shouldClose) {
                    doClose(true);
                }
            };
        }

        private static void safeUpdateChooserIntent(
                IChooserController chooserController, @Nullable Intent chooserIntent) {
            try {
                chooserController.updateIntent(chooserIntent);
            } catch (RemoteException ignored) {
                // ignored
            }
        }

        private static void safeUnlinkToDeath(IBinder binder, IBinder.DeathRecipient linkToDeath) {
            try {
                binder.unlinkToDeath(linkToDeath, 0);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unlink to death", e);
            }
        }

        private static boolean areEqual(
                @Nullable IChooserController left, @Nullable IChooserController right) {
            if (left == null && right == null) {
                return true;
            }
            if (left == null || right == null) {
                return false;
            }
            return left.asBinder().equals(right.asBinder());
        }
    }

    private static class UpdateListenerWrapper {
        public final StateListener listener;
        public final Executor executor;
        public final AtomicBoolean isSubscribed = new AtomicBoolean(true);

        UpdateListenerWrapper(StateListener listener, Executor executor) {
            this.listener = listener;
            this.executor = executor;
        }
    }
}
