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

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManagerGlobal;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.SurfaceControl;
import android.view.accessibility.AccessibilityDisplayProxy;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputConnection;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * A session for automated control of applications.
 *
 * <p>A session is associated with a single trusted virtual display, capable of hosting activities,
 * along with the input devices that allow input injection.</p>
 *
 * @hide
 */
public final class ComputerControlSession implements AutoCloseable {

    private static final String TAG = ComputerControlSession.class.getSimpleName();
    private static final int TRACE_COOKIE_REQUEST_SCREENSHOT = 0;

    /** Overall timeout for a screenshot request to complete. */
    private static final int SCREENSHOT_TIMEOUT_MS = 5000;

    /** @hide */
    public static final String ACTION_REQUEST_ACCESS =
            "android.companion.virtual.computercontrol.action.REQUEST_ACCESS";

    /** @hide */
    public static final String EXTRA_AUTOMATING_PACKAGE_NAME =
            "android.companion.virtual.computercontrol.extra.AUTOMATING_PACKAGE_NAME";

    /** @hide */
    public static final int RESULT_STOP_AUTOMATION = Activity.RESULT_FIRST_USER;

    /**
     * Unknown session creation error.
     */
    public static final int ERROR_UNKNOWN = 0;

    /**
     * Error code indicating that a new session cannot be created because the maximum number of
     * allowed concurrent sessions has been reached.
     *
     * <p>This is a transient error and the session creation request can be retried later.</p>
     */
    public static final int ERROR_SESSION_LIMIT_REACHED = 1;

    /**
     * Error code indicating that a new session cannot be created because the device is currently
     * locked.
     *
     * <p>This is a transient error and the session creation request can be retried later.</p>
     *
     * @see android.app.KeyguardManager#isDeviceLocked()
     */
    public static final int ERROR_DEVICE_LOCKED = 2;

    /**
     * Error code indicating that the caller does not have permission to create a session, which is
     * possible if the user did not approve the creation of a new session, or if the caller is not
     * in foreground.
     */
    public static final int ERROR_PERMISSION_DENIED = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "ERROR_", value = {
            // Keep in sync with computercontrol_extension_atoms.proto
            ERROR_UNKNOWN,
            ERROR_SESSION_LIMIT_REACHED,
            ERROR_DEVICE_LOCKED,
            ERROR_PERMISSION_DENIED})
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface SessionCreationError {
    }

    /**
     * Unknown session close reason.
     */
    public static final int CLOSE_REASON_UNKNOWN = 0;

    /**
     * Close reason indicating the session was closed by the caller.
     *
     * @see ComputerControlSession#close()
     */
    public static final int CLOSE_REASON_CALLER_INITIATED = 1;

    /**
     * Close reason indicating the session was closed by the user during an auth flow or to take
     * control of the app under automation, etc.
     */
    public static final int CLOSE_REASON_USER_INITIATED = 2;

    /**
     * Close reason indicating the session timed out.
     */
    public static final int CLOSE_REASON_SESSION_TIMED_OUT = 3;

    /**
     * Close reason indicating that the session became empty.
     */
    public static final int CLOSE_REASON_SESSION_EMPTY = 4;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "CLOSE_REASON_", value = {
            // Keep in sync with computercontrol_extension_atoms.proto
            CLOSE_REASON_UNKNOWN,
            CLOSE_REASON_CALLER_INITIATED,
            CLOSE_REASON_USER_INITIATED,
            CLOSE_REASON_SESSION_TIMED_OUT,
            CLOSE_REASON_SESSION_EMPTY,
    })
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface SessionCloseReason {
    }

    /**
     * Unknown session block reason.
     */
    public static final int BLOCK_REASON_UNKNOWN = 0;

    /**
     * Reason indicating that the session was blocked due to secure content being present.
     */
    public static final int BLOCK_REASON_SECURE_CONTENT = 1;

    /**
     * Reason indicating that the session was blocked due to a disallowed activity being launched
     * in the session.
     */
    public static final int BLOCK_REASON_DISALLOWED_ACTIVITY_LAUNCH = 2;

    /**
     * Reason indicating that the session was blocked due to a {@link #notifyBlocked()} request from
     * the caller.
     */
    public static final int BLOCK_REASON_CALLER_INITIATED = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "BLOCK_REASON_", value = {
            // Keep in sync with computercontrol_extension_atoms.proto
            BLOCK_REASON_UNKNOWN,
            BLOCK_REASON_SECURE_CONTENT,
            BLOCK_REASON_DISALLOWED_ACTIVITY_LAUNCH,
            BLOCK_REASON_CALLER_INITIATED,
    })
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface SessionBlockReason {
    }

    /**
     * Computer control action that performs back navigation.
     */
    public static final int ACTION_GO_BACK = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "ACTION_", value = {
            // Keep in sync with computercontrol_extension_atoms.proto
            ACTION_GO_BACK,
    })
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface Action {
    }

    private final String mTraceTrack = "ComputerControlSession#" + System.identityHashCode(this);

    /** Auxiliary thread for any client-side work related to the computer control session. */
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    @NonNull
    private final IComputerControlSession mSession;
    @NonNull
    private final Size mDisplaySize;

    private final Object mImageReaderLock = new Object();
    @GuardedBy("mImageReaderLock")
    @Nullable
    private ImageReader mImageReader;
    @GuardedBy("mImageReaderLock")
    @Nullable
    private ScreenshotCallbackRecord mOneShotPendingScreenshotCallback;
    @GuardedBy("mImageReaderLock")
    @Nullable
    private Runnable mScreenshotTimeoutRunnable;

    @GuardedBy("mLifecycle")
    private final LifecycleStateTracker mLifecycle = new LifecycleStateTracker();
    @GuardedBy("mLifecycle")
    private LifecycleCallback mRegisteredLifecycleCallback = null;

    // TODO(b/419460558): Added to temporarily link {@link LifecycleCallback#onClosed(int)} with the
    //  existing {@link Callback#onSessionClosed()}. Remove once its migrated.
    @NonNull
    private final Runnable mOnClosedRunnable;

    private final ComputerControlAccessibilityProxy mAccessibilityProxy;

    private final IComputerControlLifecycleCallback mRemoteLifecycleCallback =
            new IComputerControlLifecycleCallback.Stub() {
                @Override
                public void onActive() {
                    synchronized (mLifecycle) {
                        mLifecycle.onActive();
                    }
                }

                @Override
                public void onBlocked(@SessionBlockReason int reason,
                        @Nullable String blockingPackage) {
                    synchronized (mLifecycle) {
                        mLifecycle.onBlocked(reason, blockingPackage);
                    }
                }

                @Override
                public void onClosed(@SessionCloseReason int closeReason) {
                    releaseResources();
                    synchronized (mLifecycle) {
                        mLifecycle.onClosed(closeReason);
                    }
                    mOnClosedRunnable.run();
                    mHandlerThread.quitSafely();
                }
            };

    /** @hide */
    public ComputerControlSession(int displayId,
            @NonNull IComputerControlSession session,
            @NonNull Consumer<AccessibilityDisplayProxy> registerA11yDisplayProxy,
            @NonNull Runnable onClosedRunnable) {
        this(displayId, session, registerA11yDisplayProxy, onClosedRunnable,
                DisplayManagerGlobal.getInstance());
    }

    /** @hide */
    @VisibleForTesting
    public ComputerControlSession(int displayId,
            @NonNull IComputerControlSession session,
            @NonNull Consumer<AccessibilityDisplayProxy> registerA11yDisplayProxy,
            @NonNull Runnable onClosedRunnable,
            @NonNull DisplayManagerGlobal displayManagerGlobal) {
        mHandlerThread = new HandlerThread("ComputerControlSession");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mSession = Objects.requireNonNull(session);
        mOnClosedRunnable = onClosedRunnable;

        final Display display = displayManagerGlobal.getRealDisplay(displayId);
        Objects.requireNonNull(display);
        final DisplayInfo displayInfo = new DisplayInfo();
        display.getDisplayInfo(displayInfo);
        mDisplaySize = new Size(displayInfo.logicalWidth, displayInfo.logicalHeight);

        // ImageReader requires maxImages to be at least 2 for acquireLatestImage() to work.
        mImageReader = ImageReader.newInstance(displayInfo.logicalWidth,
                displayInfo.logicalHeight,
                PixelFormat.RGBA_8888, /* maxImages= */ 2);
        mImageReader.setOnImageAvailableListener(reader -> onImageAvailable(), mHandler);
        try {
            mSession.initialize(mRemoteLifecycleCallback, mImageReader.getSurface());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        mAccessibilityProxy = new ComputerControlAccessibilityProxy(displayId, mHandler);
        registerA11yDisplayProxy.accept(mAccessibilityProxy);
    }

    /**
     * Launches an application's launcher activity in the computer control session.
     *
     * @throws IllegalArgumentException if the package does not have a launcher activity.
     * @see ComputerControlSessionParams#getTargetPackageNames()
     */
    public void launchApplication(@NonNull String packageName) {
        try {
            mSession.launchApplication(packageName, /* className= */ null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        mAccessibilityProxy.resetStabilityState();
    }

    /**
     * Launches an application's launcher activity in the computer control session.
     *
     * @throws IllegalArgumentException if the component is not a launcher activity.
     * @see ComputerControlSessionParams#getTargetPackageNames()
     */
    public void launchApplication(@NonNull ComponentName component) {
        try {
            mSession.launchApplication(component.getPackageName(), component.getClassName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        mAccessibilityProxy.resetStabilityState();
    }

    /**
     * Hand over full control of the automation session to the user.
     *
     * <p>All of the applications currently automated in the session are moved from the session's
     * display to the user's default display. No further automation is possible on these tasks,
     * although the session remains active and new applications may be launched via
     * {@link #launchApplication(String)}</p>
     */
    public void handOverApplications() {
        try {
            mSession.handOverApplications();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Screenshot the current display content synchronously.
     *
     * <p>The behavior is similar to {@link ImageReader#acquireLatestImage}, meaning that any
     * previously acquired images should be released before attempting to acquire new ones.</p>
     *
     * NOTE: This is a blocking call! If a screenshot is not immediately available, this method
     * will block until the next frame is produced, or until the method times out. A successful
     * screenshot acquisition could take in the order of 10s of milliseconds if one is not
     * immediately available.
     *
     * @return A screenshot of the current display content, or {@code null} if no screenshot is
     *   currently available.
     * @deprecated Use {@link #requestScreenshot(Executor, OutcomeReceiver, CancellationSignal)}
     *   instead.
     */
    @Nullable
    public Image getScreenshot() {
        return requestScreenshotSync();
    }

    /**
     * Exception used to indicate how a screenshot request failed.
     */
    public static class ScreenshotException extends Exception {

        /** An unknown error occurred when processing this screenshot request. */
        public static final int ERROR_UNKNOWN = 0;

        /** The request to receive a screenshot timed out. */
        public static final int ERROR_TIMEOUT = 1;

        /** The request to receive a screenshot was cancelled. */
        public static final int ERROR_CANCELED = 2;

        /** The session is in a state where taking screenshots is prohibited. */
        public static final int ERROR_PROHIBITED = 3;

        /** The session encountered an internal error, and the client may try again later. */
        public static final int ERROR_INTERNAL = 4;

        /** The session encountered a remote error. */
        public static final int ERROR_REMOTE = 5;

        /** A previous request to receive a screenshot is still active. */
        public static final int ERROR_DUPLICATE_REQUEST = 6;

        /** The screenshot request was successful, but the screenshot is unchanged. */
        public static final int ERROR_SCREEN_UNCHANGED = 7;

        @ErrorCode
        private final int mErrorCode;

        /**
         * Get the error code that describes the failure mode.
         */
        @ErrorCode
        public int getErrorCode() {
            return mErrorCode;
        }

        /** @hide */
        @IntDef(value = {
                ERROR_UNKNOWN,
                ERROR_TIMEOUT,
                ERROR_CANCELED,
                ERROR_PROHIBITED,
                ERROR_INTERNAL,
                ERROR_REMOTE,
                ERROR_DUPLICATE_REQUEST,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface ErrorCode {
        }

        /** @hide */
        public ScreenshotException(@ErrorCode int errorCode) {
            super("errorCode=" + errorCode);
            mErrorCode = errorCode;
        }
    }

    /**
     * Requests a screenshot of the current display content asynchronously.
     *
     * <p>This is a one-shot operation. A new screenshot request must be made for each screenshot.
     *
     * <p>The behavior is similar to {@link ImageReader#acquireLatestImage}, meaning that any
     * previously acquired images should be released before attempting to acquire new ones.
     *
     * <p>Only one screenshot request can be active at a time. If a new screenshot is requested
     * while a previous one is still pending, the new request will be rejected.
     *
     * @param executor The executor on which the callback will be invoked.
     * @param receiver The outcome receiver callback to be invoked when the screenshot is available
     *                 or encounters any issues.
     * @see ScreenshotException
     */
    @SuppressWarnings("EmptyTryBlock")
    public void requestScreenshot(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Image, ScreenshotException> receiver,
            @Nullable CancellationSignal cancellationSignal) {
        Objects.requireNonNull(executor, "Executor must not be null");
        Objects.requireNonNull(receiver, "OutcomeReceiver must not be null");
        final var callback =
                new ScreenshotCallbackRecord(mTraceTrack, executor, receiver, cancellationSignal);

        synchronized (mLifecycle) {
            if (mLifecycle.getCurrentState() instanceof LifecycleState.Closed) {
                Log.e(TAG, "Cannot request screenshot: Session is closed");
                callback.fire(it -> it.onError(
                        new ScreenshotException(ScreenshotException.ERROR_PROHIBITED)));
                return;
            }
        }
        synchronized (mImageReaderLock) {
            if (mImageReader == null) {
                Log.w(TAG, "Cannot request screenshot: Image reader resources are closed");
                callback.fire(it -> it.onError(
                        new ScreenshotException(ScreenshotException.ERROR_PROHIBITED)));
                return;
            }
            if (mOneShotPendingScreenshotCallback != null) {
                // The screenshot pipeline cannot be fully synchronized as we don't know the exact
                // vsync ID of the frame rendered by this request when consuming from the
                // ImageReader, so we assume it's the first consumed frame. To avoid overlapping
                // requests, prefer older requests and deny duplicate ones, unless the request is
                // manually cancelled by the client.
                Log.w(TAG, "Cannot request screenshot: Existing screenshot request still pending");
                callback.fire(it -> it.onError(
                        new ScreenshotException(ScreenshotException.ERROR_DUPLICATE_REQUEST)));
                return;
            }

            // Install the callback.
            mOneShotPendingScreenshotCallback = callback;
            mScreenshotTimeoutRunnable = () -> onScreenshotError(
                    callback, ScreenshotException.ERROR_TIMEOUT, "Timeout");
            mHandler.postDelayed(mScreenshotTimeoutRunnable, SCREENSHOT_TIMEOUT_MS);
            if (cancellationSignal != null) {
                cancellationSignal.setOnCancelListener(
                        () -> mHandler.post(() -> onScreenshotError(
                                callback, ScreenshotException.ERROR_CANCELED, "Cancelled")));
            }
            try (var image = mImageReader.acquireLatestImage()) {
                // Flush the image queue.
            }
        }

        try {
            // This remote call will trigger a new frame to be drawn, which will in turn trigger
            // onImageAvailable(). We assume that the next image that becomes available was the
            // result of this request.
            if (!mSession.requestScreenshot()) {
                onScreenshotError(callback, ScreenshotException.ERROR_INTERNAL, "Server error");
                return;
            }

        } catch (RemoteException e) {
            onScreenshotError(callback, ScreenshotException.ERROR_REMOTE, "Remote exception");
            return;
        }
    }

    /** Local adapter for making screenshot requests synchronous. */
    private Image requestScreenshotSync() {
        if (Thread.currentThread().threadId() == mHandlerThread.getThreadId()) {
            throw new IllegalStateException("getScreenshot must be called from a different thread");
        }

        final var future = new CompletableFuture<Image>();
        requestScreenshot(Runnable::run, new OutcomeReceiver<>() {
                    @Override
                    public void onResult(Image result) {
                        future.complete(result);
                    }

                    @Override
                    public void onError(@NonNull ScreenshotException error) {
                        future.complete(null);
                    }
                },
                /* cancellationSignal= */ null);

        try {
            return future.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Sends a tap event to the computer control session at the given location.
     *
     * <p>The coordinates are in relative display space, e.g. (0.5, 0.5) is the center of the
     * display.</p>
     */
    public void tap(@IntRange(from = 0) int x, @IntRange(from = 0) int y) {
        validateTouchCoordinates(x, y);
        try {
            mSession.tap(x, y);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        mAccessibilityProxy.resetStabilityState();
    }

    /**
     * Sends a swipe event to the computer control session for the given coordinates.
     *
     * <p>To avoid misinterpreting the swipe as a fling, the individual touches are throttled, so
     * the entire action will take ~500ms. However, this is done in the background and this method
     * returns immediately. Any ongoing swipe will be canceled if a new swipe is requested.</p>
     *
     * <p>The coordinates are in relative display space, e.g. (0.5, 0.5) is the center of the
     * display.</p>
     */
    public void swipe(
            @IntRange(from = 0) int fromX, @IntRange(from = 0) int fromY,
            @IntRange(from = 0) int toX, @IntRange(from = 0) int toY) {
        validateTouchCoordinates(fromX, fromY);
        validateTouchCoordinates(toX, toY);
        try {
            mSession.swipe(fromX, fromY, toX, toY);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        mAccessibilityProxy.resetStabilityState();
    }

    /**
     * Sends a long press event to the computer control session for the given coordinates.
     *
     * <p>The coordinates are in relative display space, e.g. (0.5, 0.5) is the center of the
     * display.</p>
     */
    public void longPress(@IntRange(from = 0) int x, @IntRange(from = 0) int y) {
        validateTouchCoordinates(x, y);
        try {
            mSession.longPress(x, y);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        mAccessibilityProxy.resetStabilityState();
    }

    /**
     * Inserts provided text into the currently active text field on the display associated with
     * the {@link ComputerControlSession}.
     *
     * <p> This method expects a text field to be in focus with an active {@link InputConnection}.
     * It inserts text at the current cursor position in the text field and moves the cursor to
     * the end of inserted text. </p>
     *
     * @param text to be inserted
     * @param replaceExisting whether the current text in the text field needs to be overwritten
     * @param commit whether the text should be submitted after insertion
     */
    public void insertText(@NonNull String text, boolean replaceExisting, boolean commit) {
        try {
            mSession.insertText(text, replaceExisting, commit);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        mAccessibilityProxy.resetStabilityState();
    }

    /** Perform provided action on the trusted virtual display. */
    public void performAction(@Action int actionCode) {
        try {
            mSession.performAction(actionCode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        mAccessibilityProxy.resetStabilityState();
    }

    /** Creates an interactive virtual display, mirroring the trusted one. */
    @Nullable
    public InteractiveMirror createInteractiveMirror() {
        try {
            SurfaceControl mirrorSurface = new SurfaceControl();
            IInteractiveMirror mirror = mSession.createInteractiveMirror(mirrorSurface);
            if (mirror == null) {
                return null;
            }
            return new InteractiveMirror(mirror, mirrorSurface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Get the size of the session's display. */
    @NonNull
    public Size getDisplaySize() {
        return mDisplaySize;
    }

    /**
     * Sets a {@link StabilityListener} to be notified when the computer control session is
     * potentially stable.
     *
     * @param duration {@link Duration} after which the session would be considered stable, upon any
     *                                 action or any framework event
     * @param executor {@link Executor} on which this listener would be invoked
     * @param listener {@link StabilityListener} which would be invoked
     *
     * @throws IllegalStateException if a listener was previously set.
     */
    public void setStabilityListener(@NonNull Duration duration,
            @NonNull @CallbackExecutor Executor executor, @NonNull StabilityListener listener) {
        Objects.requireNonNull(duration);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        mAccessibilityProxy.setStabilityListener(duration.toMillis(), executor, listener);
    }

    /**
     * Clears any {@link StabilityListener} that was previously set using
     * {@link #setStabilityListener(Duration, Executor, StabilityListener)}.
     *
     * @throws IllegalStateException if a listener was not previously set.
     */
    public void clearStabilityListener() {
        mAccessibilityProxy.clearStabilityListener();
    }

    /**
     * Sets a {@link LifecycleCallback} to be notified about the computer control session lifecycle
     * changes.
     *
     * @throws IllegalStateException if a callback was previously set.
     */
    public void setLifecycleCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull LifecycleCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        synchronized (mLifecycle) {
            if (mRegisteredLifecycleCallback != null) {
                throw new IllegalStateException("Lifecycle callback was already registered!");
            }
            mRegisteredLifecycleCallback = callback;
            mLifecycle.addCallback(
                    (cmd) -> Binder.withCleanCallingIdentity(() -> executor.execute(cmd)),
                    mRegisteredLifecycleCallback);
        }
    }

    /**
     * Clears any {@link LifecycleCallback} that was previously set using
     * {@link #setLifecycleCallback(Executor, LifecycleCallback)}.
     *
     * @throws IllegalStateException if a callback was not previously set.
     */
    public void clearLifecycleCallback() {
        synchronized (mLifecycle) {
            if (mRegisteredLifecycleCallback == null) {
                throw new IllegalStateException("Lifecycle callback was never registered!");
            }
            mLifecycle.removeCallback(mRegisteredLifecycleCallback);
            mRegisteredLifecycleCallback = null;
        }
    }

    /**
     * Returns A11y information for all windows on the display associated with the
     * {@link ComputerControlSession}, or an empty list if no information is currently available.
     */
    @NonNull
    public List<AccessibilityWindowInfo> getAccessibilityWindows() {
        // TODO: b/452703212: Implement this inside system_server instead of the client.
        synchronized (mLifecycle) {
            if (!(mLifecycle.getCurrentState() instanceof LifecycleState.Active)) {
                return Collections.emptyList();
            }
        }
        return mAccessibilityProxy.getWindows();
    }

    /**
     * Attaches notification information to the session, to make the notification non-dismissible.
     *
     * <p>This must be called before posting the notification.</p>
     *
     * <p>The caller must still call {@link Notification.Builder#setOngoing(boolean)}
     * with {@code true}, to make the notification non-dismissible.</p>
     *
     * @param notificationId id of the notification, as per
     * {@link android.app.NotificationManager#notify(String, int, Notification)}
     * @param notificationTag tag of the notification, as per
     * {@link android.app.NotificationManager#notify(String, int, Notification)}
     *
     * @throws IllegalStateException if a notification was already attached.
     */
    public void attachNotificationInfo(int notificationId, @Nullable String notificationTag) {
        try {
            mSession.attachNotificationInfo(notificationId, notificationTag);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the intent launched when the user wants to preview the automation, or null if none.
     *
     * <p>This overrides the intent set in {@link
     * ComputerControlSessionParams.Builder#setPreviewIntent}.
     */
    public void setPreviewIntent(@Nullable PendingIntent previewIntent) {
        try {
            mSession.setPreviewIntent(previewIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies the system that the caller is blocked and unable to perform any further
     * interactions in the session, and needs user intervention to unblock the session. If the
     * request is successful, the session will enter the blocked lifecycle state
     * ({@link LifecycleCallback#onBlocked(int, String)}), with
     * {@link #BLOCK_REASON_CALLER_INITIATED}.
     */
    public void notifyBlocked() {
        try {
            mSession.notifyBlocked();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Attempts to exit the blocked state when the session is blocked for any reason. This should
     * be called when the user explicitly chooses to end their control of the session.
     */
    public void requestUnblock() {
        try {
            mSession.requestUnblock();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    @Override
    public void close() {
        try {
            mSession.close();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void releaseResources() {
        synchronized (mImageReaderLock) {
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
        }
    }

    /** Record of a single screenshot request callback. */
    private record ScreenshotCallbackRecord(
            String traceTrack,
            @CallbackExecutor Executor executor,
            OutcomeReceiver<Image, ScreenshotException> receiver,
            @Nullable CancellationSignal cancellationSignal) {
        ScreenshotCallbackRecord {
            Trace.asyncTraceForTrackBegin(traceTrack, "ScreenshotCallbackRecord",
                    TRACE_COOKIE_REQUEST_SCREENSHOT);
        }

        /** Convenience method used to fire the callback on its executor. */
        void fire(Consumer<OutcomeReceiver<Image, ScreenshotException>> consumer) {
            executor.execute(() -> consumer.accept(receiver));
            Trace.asyncTraceForTrackEnd(traceTrack, TRACE_COOKIE_REQUEST_SCREENSHOT);
        }
    }

    private void onImageAvailable() {
        mAccessibilityProxy.onImageAvailable();

        synchronized (mImageReaderLock) {
            if (mImageReader == null) {
                return;
            }
            if (mOneShotPendingScreenshotCallback == null) {
                return;
            }
            final var image = mImageReader.acquireLatestImage();
            if (image == null) {
                // The image was already consumed by an earlier callback.
                return;
            }
            fireScreenshotCallback(it -> it.onResult(image));
        }
    }

    private void onScreenshotError(ScreenshotCallbackRecord callback,
            @ScreenshotException.ErrorCode int error, String message) {
        synchronized (mImageReaderLock) {
            if (mOneShotPendingScreenshotCallback != callback) {
                return;
            }
            Log.d(TAG, "onScreenshotError: code=" + error + ": " + message);
            fireScreenshotCallback(it -> it.onError(new ScreenshotException(error)));
        }
    }

    private void validateTouchCoordinates(int x, int y) {
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("Touch coordinates must be non-negative");
        }
        if (x >= mDisplaySize.getWidth() || y >= mDisplaySize.getHeight()) {
            throw new IllegalArgumentException(
                    "Touch coordinates must be within the display bounds");
        }
    }

    @GuardedBy("mImageReaderLock")
    private void fireScreenshotCallback(
            Consumer<OutcomeReceiver<Image, ScreenshotException>> consumer) {
        if (mOneShotPendingScreenshotCallback != null) {
            mOneShotPendingScreenshotCallback.fire(consumer);
            mHandler.removeCallbacks(Objects.requireNonNull(mScreenshotTimeoutRunnable));
            if (mOneShotPendingScreenshotCallback.cancellationSignal != null) {
                mOneShotPendingScreenshotCallback.cancellationSignal.setOnCancelListener(null);
            }
        }
        mOneShotPendingScreenshotCallback = null;
        mScreenshotTimeoutRunnable = null;
    }

    /** Callback for computer control session events. */
    public interface Callback {

        /**
         * Called when the session request needs to approved by the user.
         *
         * <p>Applications should launch the {@link Activity} "encapsulated" in {@code intentSender}
         * {@link IntentSender} object by calling
         * {@link Activity#startIntentSenderForResult(IntentSender, int, Intent, int, int, int)} or
         * {@link Context#startIntentSender(IntentSender, Intent, int, int, int)}
         *
         * @param intentSender an {@link IntentSender} which applications should use to launch
         *   the UI for the user to allow the creation of the session.
         */
        void onSessionPending(@NonNull IntentSender intentSender);

        /** Called when the session has been successfully created. */
        void onSessionCreated(@NonNull ComputerControlSession session);

        /**
         * Called when the session failed to be created.
         *
         * @param errorCode The reason for failure.
         */
        void onSessionCreationFailed(@SessionCreationError int errorCode);

        /**
         * Called when the session has been closed, either via an explicit call to {@link #close()},
         * or due to an automatic closure event, triggered by the framework.
         *
         * @deprecated use {@link LifecycleCallback}
         * @see LifecycleCallback#setLifecycleCallback(Executor, LifecycleCallback)
         */
        @Deprecated
        void onSessionClosed();
    }

    /**
     * Listener to be notified of signals indicating that the computer control session is
     * potentially stable.
     *
     * <p>These signals indicate that the session's display content is currently stable, such as the
     * app under automation being idle and no UI animations being under progress. These are useful
     * for tasks that should only run on a static UI, such as taking screenshots to determine the
     * next step.
     */
    public interface StabilityListener {
        /** Called when the computer control session is considered stable. */
        void onSessionStable();
    }

    /**
     * Callback to be notified about the computer control session lifecycle changes.
     *
     * <p>When the callback is first added, implementers of the callback should not make any
     * assumptions about the starting state of the session. The callback will be notified of the
     * starting state after being added.
     *
     * @see #setLifecycleCallback(Executor, LifecycleCallback)
     */
    public interface LifecycleCallback {

        /**
         * Called when the computer control session enters the active state.
         *
         * <p>When the session is active, the following will apply:
         *
         * <ul>
         *   <li>Interactions with the session (e.g. {@link #tap(int, int)}) are allowed.
         *   <li>Taking screenshots of the content using {@link #getScreenshot()} is allowed.
         *   <li>Getting Accessibility windows for the session using
         *     {@link #getAccessibilityWindows()} is allowed.
         * </ul>
         */
        void onActive();

        /**
         * Called when the computer control session enters the blocked state.
         *
         * <p>When the session is blocked, the application will generally not be able to interact
         * with or access the content of the session:
         *
         * <ul>
         *   <li>Interactions with the session (e.g. {@link #tap(int, int)}) are NOT allowed.
         *   <li>Taking screenshots of the content using {@link #getScreenshot()} is NOT allowed.
         *   <li>Getting Accessibility windows for the session using
         *     {@link #getAccessibilityWindows()} is NOT allowed.
         * </ul>
         *
         * <p>However, users can still interact with the contents of the session using the
         * interactive mirror. The application may choose to guide users to take over the session
         * using either the {@link #handOverApplications()} API or the interactive mirror.
         *
         * @param reason the reason that the session initially entered the blocked
         *               state.
         * @param blockingPackage the package name of the application that blocked the session,
         *                        or null if the blocking package is not known.
         */
        void onBlocked(@SessionBlockReason int reason, @Nullable String blockingPackage);

        /**
         * Called when the computer control session is closed. This marks the end of the session's
         * lifecycle, and no further lifecycle updates will take place.
         */
        void onClosed(@SessionCloseReason int reason);
    }

    /** @hide */
    public static class CallbackProxy extends IComputerControlSessionCallback.Stub {

        private final Context mContext;
        private final Callback mCallback;
        private final Executor mExecutor;
        private ComputerControlSession mSession;

        public CallbackProxy(
                @NonNull Context context, @NonNull Executor executor, @NonNull Callback callback) {
            mContext = context;
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onSessionPending(@NonNull PendingIntent pendingIntent) {
            Binder.withCleanCallingIdentity(() ->
                    mExecutor.execute(() ->
                            mCallback.onSessionPending(pendingIntent.getIntentSender())));
        }

        @Override
        public void onSessionCreated(int displayId, IComputerControlSession session) {
            final var a11yManager = Objects.requireNonNull(
                    mContext.getSystemService(AccessibilityManager.class));
            mSession = new ComputerControlSession(displayId, session,
                    a11yManager::registerDisplayProxy, this::onSessionClosed);
            Binder.withCleanCallingIdentity(() ->
                    mExecutor.execute(() -> mCallback.onSessionCreated(mSession)));
        }

        @Override
        public void onSessionCreationFailed(@SessionCreationError int errorCode) {
            Binder.withCleanCallingIdentity(() ->
                    mExecutor.execute(() -> mCallback.onSessionCreationFailed(errorCode)));
        }

        private void onSessionClosed() {
            Binder.withCleanCallingIdentity(() ->
                    mExecutor.execute(mCallback::onSessionClosed));
        }
    }
}
