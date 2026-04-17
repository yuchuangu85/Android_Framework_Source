/*
 * Copyright (C) 2006 The Android Open Source Project
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

import static android.view.WindowManager.LayoutParams.INVALID_WINDOW_TYPE;
import static android.view.WindowManager.LayoutParams.isSubWindowType;
import static android.window.WindowProviderService.isWindowProviderService;

import static com.android.window.flags.Flags.screenRecordingCallbacks;

import android.annotation.CallbackExecutor;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiContext;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Region;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.StrictMode;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseIntArray;
import android.window.IDisplayEngagementModeCallback;
import android.window.ITaskFpsCallback;
import android.window.InputTransferToken;
import android.window.TaskFpsCallback;
import android.window.TrustedPresentationThresholds;
import android.window.WindowContext;
import android.window.WindowMetricsController;
import android.window.WindowProvider;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.IResultReceiver;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Provides low-level communication with the system window manager for
 * operations that are bound to a particular context, display or parent window.
 * Instances of this object are sensitive to the compatibility info associated
 * with the running application.
 *
 * This object implements the {@link ViewManager} interface,
 * allowing you to add any View subclass as a top-level window on the screen.
 * Additional window manager specific layout parameters are defined for
 * control over how windows are displayed.  It also implements the {@link WindowManager}
 * interface, allowing you to control the displays attached to the device.
 *
 * <p>Applications will not normally use WindowManager directly, instead relying
 * on the higher-level facilities in {@link android.app.Activity} and
 * {@link android.app.Dialog}.
 *
 * <p>Even for low-level window manager access, it is almost never correct to use
 * this class.  For example, {@link android.app.Activity#getWindowManager}
 * provides a window manager for adding windows that are associated with that
 * activity -- the window manager will not normally allow you to add arbitrary
 * windows that are not associated with an activity.
 * <p>
 * Note that extending {@code WindowManagerImpl} for {@link WindowManager} customization may lead to
 * crashes since {@link Window} and {@link WindowContext} may also customize
 * {@code WindowManagerImpl}, such as providing {@link #mParentWindow}
 * or {@link #mWindowContextToken}. Users should customize {@link WindowManager} via
 * {@link WindowManagerWrapper}.
 *
 * @see WindowManager
 * @see WindowManagerGlobal
 * @hide
 */
public final class WindowManagerImpl implements WindowManager {
    private static final String TAG = "WindowManager";

    @UnsupportedAppUsage
    private final WindowManagerGlobal mGlobal = WindowManagerGlobal.getInstance();
    @UiContext
    @VisibleForTesting
    public final Context mContext;
    private Window mParentWindow;

    /**
     * If {@link LayoutParams#token} is {@code null} and no parent window is specified, the value
     * of {@link LayoutParams#token} will be overridden to {@code mDefaultToken}.
     */
    private IBinder mDefaultToken;

    /**
     * This token will be set to {@link LayoutParams#mWindowContextToken} and used to receive
     * configuration changes from the server side.
     */
    @Nullable
    private final IBinder mWindowContextToken;

    @GuardedBy("mOnFpsCallbackListenerProxies")
    private final ArrayList<OnFpsCallbackListenerProxy> mOnFpsCallbackListenerProxies =
            new ArrayList<>();

    private final Object mDisplayEngagementModeLock = new Object();
    @GuardedBy("mDisplayEngagementModeLock")
    private DisplayEngagementModeCallbackImpl mDisplayEngagementModeCallback;
    @GuardedBy("mDisplayEngagementModeLock")
    private final ArrayMap<Consumer<DisplayEngagementModeState>, Executor>
            mDisplayEngagementModeCallbacks = new ArrayMap<>();
    @GuardedBy("mDisplayEngagementModeLock")
    private final SparseIntArray mLastReportedEngagementModes = new SparseIntArray();

    /** A controller to handle {@link WindowMetrics} related APIs */
    @NonNull
    private final WindowMetricsController mWindowMetricsController;

    public WindowManagerImpl(Context context) {
        this(context, null /* parentWindow */, null /* clientToken */);
    }

    public WindowManagerImpl(Context context, Window parentWindow,
            @Nullable IBinder windowContextToken) {
        mContext = context;
        mParentWindow = parentWindow;
        mWindowContextToken = windowContextToken;
        mWindowMetricsController = new WindowMetricsController(mContext);
    }

    @Override
    public WindowManager createLocalWindowManager(Window parentWindow) {
        return new WindowManagerImpl(mContext, parentWindow, mWindowContextToken);
    }

    /** Creates a {@link WindowManager} for a {@link WindowContext}. */
    public static WindowManager createWindowContextWindowManager(Context context) {
        final IBinder clientToken = context.getWindowContextToken();
        return new WindowManagerImpl(context, null /* parentWindow */, clientToken);
    }

    /**
     * Sets the window token to assign when none is specified by the client or
     * available from the parent window.
     *
     * @param token The default token to assign.
     */
    public void setDefaultToken(IBinder token) {
        mDefaultToken = token;
    }

    @Override
    public void setParentWindow(@NonNull Window parentWindow) {
        mParentWindow = parentWindow;
    }

    @Override
    public void addView(@NonNull View view, @NonNull ViewGroup.LayoutParams params) {
        fallbackWindowTypeIfNeeded(params, view);
        applyTokens(params);
        mGlobal.addView(view, params, mContext.getDisplayNoVerify(), mParentWindow,
                mContext.getUserId());
    }

    @Override
    public void updateViewLayout(@NonNull View view, @NonNull ViewGroup.LayoutParams params) {
        fallbackWindowTypeIfNeeded(params, view);
        applyTokens(params);
        mGlobal.updateViewLayout(view, params);
    }

    private void applyTokens(@NonNull ViewGroup.LayoutParams params) {
        if (!(params instanceof LayoutParams wparams)) {
            throw new IllegalArgumentException("Params must be WindowManager.LayoutParams");
        }
        assertWindowContextTypeMatches(wparams.type);
        // Only use the default token if we don't have a parent window and a token.
        if (mDefaultToken != null && mParentWindow == null && wparams.token == null) {
            wparams.token = mDefaultToken;
        }
        wparams.mWindowContextToken = mWindowContextToken;
    }

    private void assertWindowContextTypeMatches(@LayoutParams.WindowType int windowType) {
        if (!(mContext instanceof WindowProvider windowProvider)) {
            return;
        }
        if (windowProvider.isSelfOrSubWindowType(windowType)) {
            return;
        }
        IllegalArgumentException exception = new IllegalArgumentException("Window type mismatch."
                + " Window Context's window type is " + windowProvider.getWindowType()
                + ", while LayoutParams' type is set to " + windowType + "."
                + " Please create another Window Context via"
                + " createWindowContext(getDisplay(), " + windowType + ", null)"
                + " to add window with type:" + windowType);
        if (!isWindowProviderService(windowProvider.getWindowContextOptions())) {
            throw exception;
        }
        // Throw IncorrectCorrectViolation if the Window Context is allowed to provide multiple
        // window types. Usually it's because the Window Context is a WindowProviderService.
        StrictMode.onIncorrectContextUsed("WindowContext's window type must"
                + " match type in WindowManager.LayoutParams", exception);
    }

    /**
     * Fallbacks to {@link WindowContext#getFallbackWindowType()} if the type of the window context
     * associated window is not {@link WindowContext#isSelfOrSubWindowType}.
     *
     * @param params the passed {@link android.view.WindowManager.LayoutParams}
     * @param view   the window that are going to be attached or relayout
     */
    private void fallbackWindowTypeIfNeeded(
            @NonNull ViewGroup.LayoutParams params,
            @NonNull View view) {
        if (!(params instanceof WindowManager.LayoutParams wparams)) {
            throw new IllegalArgumentException("Params must be WindowManager.LayoutParams");
        }
        if (!(mContext instanceof WindowProvider windowProvider)) {
            return;
        }
        final int windowTypeOverride = windowProvider.getFallbackWindowType();
        if (windowTypeOverride == INVALID_WINDOW_TYPE) {
            return;
        }
        if (windowProvider.isSelfOrSubWindowType(wparams.type)) {
            // Don't need to override the type if the type is valid for this WindowContext.
            return;
        }
        if (!mGlobal.canApplyFallbackWindowType(windowTypeOverride, view)) {
            return;
        }
        if (isSubWindowType(windowTypeOverride) && mParentWindow == null) {
            throw new IllegalArgumentException("Sub-window must be attached to the parent window."
                    + " Please try to obtain WindowManager from a window class, call "
                    + "WindowContext#attachWindow before adding any sub-windows.");
        }
        wparams.type = windowTypeOverride;
    }

    @Override
    public void removeView(View view) {
        mGlobal.removeView(view, false);
    }

    @Override
    public void removeViewImmediate(View view) {
        mGlobal.removeView(view, true);
    }

    @Override
    public void requestAppKeyboardShortcuts(
            final KeyboardShortcutsReceiver receiver, int deviceId) {
        IResultReceiver resultReceiver = new IResultReceiver.Stub() {
            @Override
            public void send(int resultCode, Bundle resultData) throws RemoteException {
                List<KeyboardShortcutGroup> result =
                        resultData.getParcelableArrayList(PARCEL_KEY_SHORTCUTS_ARRAY,
                                android.view.KeyboardShortcutGroup.class);
                receiver.onKeyboardShortcutsReceived(result);
            }
        };
        try {
            WindowManagerGlobal.getWindowManagerService()
                    .requestAppKeyboardShortcuts(resultReceiver, deviceId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public KeyboardShortcutGroup getApplicationLaunchKeyboardShortcuts(int deviceId) {
        try {
            return WindowManagerGlobal.getWindowManagerService()
                    .getApplicationLaunchKeyboardShortcuts(deviceId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void requestImeKeyboardShortcuts(
            final KeyboardShortcutsReceiver receiver, int deviceId) {
        IResultReceiver resultReceiver = new IResultReceiver.Stub() {
            @Override
            public void send(int resultCode, Bundle resultData) throws RemoteException {
                List<KeyboardShortcutGroup> result =
                        resultData.getParcelableArrayList(PARCEL_KEY_SHORTCUTS_ARRAY,
                                android.view.KeyboardShortcutGroup.class);
                receiver.onKeyboardShortcutsReceived(result);
            }
        };
        try {
            WindowManagerGlobal.getWindowManagerService()
                    .requestImeKeyboardShortcuts(resultReceiver, deviceId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public Display getDefaultDisplay() {
        return mContext.getDisplayNoVerify();
    }

    @Override
    public Region getCurrentImeTouchRegion() {
        try {
            return WindowManagerGlobal.getWindowManagerService().getCurrentImeTouchRegion();
        } catch (RemoteException e) {
        }
        return null;
    }

    @Override
    public void setShouldShowWithInsecureKeyguard(int displayId, boolean shouldShow) {
        try {
            WindowManagerGlobal.getWindowManagerService()
                    .setShouldShowWithInsecureKeyguard(displayId, shouldShow);
        } catch (RemoteException e) {
        }
    }

    @Override
    public boolean shouldShowSystemDecors(int displayId) {
        try {
            return WindowManagerGlobal.getWindowManagerService().shouldShowSystemDecors(displayId);
        } catch (RemoteException e) {
        }
        return false;
    }

    @Override
    public boolean isEligibleForDesktopMode(int displayId) {
        try {
            return WindowManagerGlobal.getWindowManagerService()
                    .isEligibleForDesktopMode(displayId);
        } catch (RemoteException e) {
        }
        return false;
    }

    @Override
    public void setDisplayImePolicy(int displayId, @DisplayImePolicy int imePolicy) {
        try {
            WindowManagerGlobal.getWindowManagerService().setDisplayImePolicy(displayId, imePolicy);
        } catch (RemoteException e) {
        }
    }

    @Override
    public @DisplayImePolicy int getDisplayImePolicy(int displayId) {
        try {
            return WindowManagerGlobal.getWindowManagerService().getDisplayImePolicy(displayId);
        } catch (RemoteException e) {
        }
        return DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
    }

    @Override
    public boolean isGlobalKey(int keyCode) {
        try {
            return WindowManagerGlobal.getWindowManagerService().isGlobalKey(keyCode);
        } catch (RemoteException e) {
        }
        return false;
    }

    @Override
    public WindowMetrics getCurrentWindowMetrics() {
        return mWindowMetricsController.getCurrentWindowMetrics();
    }

    @Override
    public WindowMetrics getMaximumWindowMetrics() {
        return mWindowMetricsController.getMaximumWindowMetrics();
    }

    @Override
    @NonNull
    public Set<WindowMetrics> getPossibleMaximumWindowMetrics(int displayId) {
        return mWindowMetricsController.getPossibleMaximumWindowMetrics(displayId);
    }

    @Override
    public void holdLock(IBinder token, int durationMs) {
        try {
            WindowManagerGlobal.getWindowManagerService().holdLock(token, durationMs);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean isCrossWindowBlurEnabled() {
        return CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled();
    }

    @Override
    public void addCrossWindowBlurEnabledListener(@NonNull Consumer<Boolean> listener) {
        addCrossWindowBlurEnabledListener(mContext.getMainExecutor(), listener);
    }

    @Override
    public void addCrossWindowBlurEnabledListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Boolean> listener) {
        CrossWindowBlurListeners.getInstance().addListener(executor, listener);
    }

    @Override
    public void removeCrossWindowBlurEnabledListener(@NonNull Consumer<Boolean> listener) {
        CrossWindowBlurListeners.getInstance().removeListener(listener);
    }

    @Override
    public void addProposedRotationListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull IntConsumer listener) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        final IBinder contextToken = Context.getToken(mContext);
        if (contextToken == null) {
            throw new UnsupportedOperationException("The context of this window manager instance "
                    + "must be a UI context, e.g. an Activity or a Context created by "
                    + "Context#createWindowContext()");
        }
        mGlobal.registerProposedRotationListener(contextToken, executor, listener);
    }

    @Override
    public void removeProposedRotationListener(@NonNull IntConsumer listener) {
        mGlobal.unregisterProposedRotationListener(Context.getToken(mContext), listener);
    }

    @Override
    public boolean isTaskSnapshotSupported() {
        try {
            return WindowManagerGlobal.getWindowManagerService().isTaskSnapshotSupported();
        } catch (RemoteException e) {
        }
        return false;
    }

    @Override
    public void registerTaskFpsCallback(@IntRange(from = 0) int taskId, @NonNull Executor executor,
            TaskFpsCallback callback) {
        final OnFpsCallbackListenerProxy onFpsCallbackListenerProxy =
                new OnFpsCallbackListenerProxy(executor, callback);
        try {
            WindowManagerGlobal.getWindowManagerService().registerTaskFpsCallback(
                    taskId, onFpsCallbackListenerProxy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        synchronized (mOnFpsCallbackListenerProxies) {
            mOnFpsCallbackListenerProxies.add(onFpsCallbackListenerProxy);
        }
    }

    @Override
    public void unregisterTaskFpsCallback(TaskFpsCallback callback) {
        synchronized (mOnFpsCallbackListenerProxies) {
            final Iterator<OnFpsCallbackListenerProxy> iterator =
                    mOnFpsCallbackListenerProxies.iterator();
            while (iterator.hasNext()) {
                final OnFpsCallbackListenerProxy proxy = iterator.next();
                if (proxy.mCallback == callback) {
                    try {
                        WindowManagerGlobal.getWindowManagerService()
                                .unregisterTaskFpsCallback(proxy);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                    iterator.remove();
                }
            }
        }
    }

    private static class OnFpsCallbackListenerProxy
            extends ITaskFpsCallback.Stub {
        private final Executor mExecutor;
        private final TaskFpsCallback mCallback;

        private OnFpsCallbackListenerProxy(Executor executor, TaskFpsCallback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onFpsReported(float fps) {
            mExecutor.execute(() -> {
                mCallback.onFpsReported(fps);
            });
        }
    }

    private class DisplayEngagementModeCallbackImpl extends IDisplayEngagementModeCallback.Stub {
        @Override
        public void onEngagementModeChanged(
                int displayId, @EngagementModeFlags int engagementMode) {
            Map<Consumer<DisplayEngagementModeState>, Executor> callbacks;
            synchronized (mDisplayEngagementModeLock) {
                mLastReportedEngagementModes.put(displayId, engagementMode);
                callbacks = new ArrayMap<>(mDisplayEngagementModeCallbacks);
            }

            for (Map.Entry<Consumer<DisplayEngagementModeState>, Executor> entry
                    : callbacks.entrySet()) {
                Executor executor = entry.getValue();
                Consumer<DisplayEngagementModeState> callback = entry.getKey();
                executor.execute(() -> {
                    callback.accept(new DisplayEngagementModeState(displayId, engagementMode));
                });
            }
        }
    }

    @Override
    public Bitmap snapshotTaskForRecents(int taskId) {
        try {
            return WindowManagerGlobal.getWindowManagerService().snapshotTaskForRecents(taskId);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
        return null;
    }

    @Override
    @NonNull
    public IBinder getDefaultToken() {
        return mDefaultToken;
    }

    @Override
    @NonNull
    public List<ComponentName> notifyScreenshotListeners(int displayId) {
        try {
            return List.copyOf(WindowManagerGlobal.getWindowManagerService()
                    .notifyScreenshotListeners(displayId));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean replaceContentOnDisplayWithMirror(int displayId, @NonNull Window window) {
        View decorView = window.peekDecorView();
        if (decorView == null) {
            Log.e(TAG, "replaceContentOnDisplayWithMirror: Window's decorView was null.");
            return false;
        }

        ViewRootImpl viewRoot = decorView.getViewRootImpl();
        if (viewRoot == null) {
            Log.e(TAG, "replaceContentOnDisplayWithMirror: Window's viewRootImpl was null.");
            return false;
        }

        SurfaceControl sc = viewRoot.getSurfaceControl();
        if (!sc.isValid()) {
            Log.e(TAG, "replaceContentOnDisplayWithMirror: Window's SC is invalid.");
            return false;
        }
        return replaceContentOnDisplayWithSc(displayId, SurfaceControl.mirrorSurface(sc));
    }

    @Override
    public boolean replaceContentOnDisplayWithSc(int displayId, @NonNull SurfaceControl sc) {
        if (!sc.isValid()) {
            Log.e(TAG, "replaceContentOnDisplayWithSc: Invalid SC.");
            return false;
        }

        try {
            return WindowManagerGlobal.getWindowManagerService()
                    .replaceContentOnDisplay(displayId, sc);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
        return false;
    }

    @Override
    public void registerTrustedPresentationListener(@NonNull IBinder window,
            @NonNull TrustedPresentationThresholds thresholds, @NonNull Executor executor,
            @NonNull Consumer<Boolean> listener) {
        Objects.requireNonNull(window, "window must not be null");
        Objects.requireNonNull(thresholds, "thresholds must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        mGlobal.registerTrustedPresentationListener(window, thresholds, executor, listener);
    }

    @Override
    public void unregisterTrustedPresentationListener(@NonNull Consumer<Boolean> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        mGlobal.unregisterTrustedPresentationListener(listener);
    }

    @NonNull
    @Override
    public InputTransferToken registerBatchedSurfaceControlInputReceiver(
            @NonNull InputTransferToken hostInputTransferToken,
            @NonNull SurfaceControl surfaceControl, @NonNull Choreographer choreographer,
            @NonNull SurfaceControlInputReceiver receiver) {
        Objects.requireNonNull(hostInputTransferToken);
        Objects.requireNonNull(surfaceControl);
        Objects.requireNonNull(choreographer);
        Objects.requireNonNull(receiver);
        return mGlobal.registerBatchedSurfaceControlInputReceiver(hostInputTransferToken,
                surfaceControl, choreographer, receiver);
    }

    @NonNull
    @Override
    public InputTransferToken registerUnbatchedSurfaceControlInputReceiver(
            @NonNull InputTransferToken hostInputTransferToken,
            @NonNull SurfaceControl surfaceControl, @NonNull Looper looper,
            @NonNull SurfaceControlInputReceiver receiver) {
        Objects.requireNonNull(hostInputTransferToken);
        Objects.requireNonNull(surfaceControl);
        Objects.requireNonNull(looper);
        Objects.requireNonNull(receiver);
        return mGlobal.registerUnbatchedSurfaceControlInputReceiver(
                hostInputTransferToken, surfaceControl, looper, receiver);
    }

    @Override
    public void unregisterSurfaceControlInputReceiver(@NonNull SurfaceControl surfaceControl) {
        Objects.requireNonNull(surfaceControl);
        mGlobal.unregisterSurfaceControlInputReceiver(surfaceControl);
    }

    @Override
    @Nullable
    public IBinder getSurfaceControlInputClientToken(@NonNull SurfaceControl surfaceControl) {
        Objects.requireNonNull(surfaceControl);
        return mGlobal.getSurfaceControlInputClientToken(surfaceControl);
    }

    @Override
    public boolean transferTouchGesture(@NonNull InputTransferToken transferFromToken,
            @NonNull InputTransferToken transferToToken) {
        Objects.requireNonNull(transferFromToken);
        Objects.requireNonNull(transferToToken);
        return mGlobal.transferTouchGesture(transferFromToken, transferToToken);
    }

    @Override
    public @ScreenRecordingState int addScreenRecordingCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<@ScreenRecordingState Integer> callback) {
        if (screenRecordingCallbacks()) {
            Objects.requireNonNull(executor, "executor must not be null");
            Objects.requireNonNull(callback, "callback must not be null");
            return ScreenRecordingCallbacks.getInstance().addCallback(executor, callback);
        }
        return SCREEN_RECORDING_STATE_NOT_VISIBLE;
    }

    @Override
    public void removeScreenRecordingCallback(
            @NonNull Consumer<@ScreenRecordingState Integer> callback) {
        if (screenRecordingCallbacks()) {
            Objects.requireNonNull(callback, "callback must not be null");
            ScreenRecordingCallbacks.getInstance().removeCallback(callback);
        }
    }

    @Override
    public void setDisplayEngagementMode(
            int displayId, @EngagementModeFlags int engagementModeFlags) {
        try {
            WindowManagerGlobal.getWindowManagerService().setDisplayEngagementMode(
                    displayId, engagementModeFlags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public @EngagementModeFlags int getDisplayEngagementMode(int displayId) {
        try {
            return WindowManagerGlobal.getWindowManagerService().getDisplayEngagementMode(
                    displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void registerDisplayEngagementModeCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<DisplayEngagementModeState> callback) {
        synchronized (mDisplayEngagementModeLock) {
            // Ignore registration if this exact callback instance is already registered.
            if (mDisplayEngagementModeCallbacks.containsKey(callback)) {
                Log.w(TAG, "Attempted to register DisplayEngagementModeState callback"
                        + " that is already registered: " + callback);
                return;
            }

            if (mDisplayEngagementModeCallbacks.isEmpty()) {
                // First listener, register the single proxy with WMS.
                if (mDisplayEngagementModeCallback == null) {
                    mDisplayEngagementModeCallback = new DisplayEngagementModeCallbackImpl();
                }
                // Clear cache before registering, as registration will send initial state.
                mLastReportedEngagementModes.clear();
                try {
                    WindowManagerGlobal.getWindowManagerService()
                            .registerDisplayEngagementModeCallback(mDisplayEngagementModeCallback);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }

            // Add the local callback and its executor to internal map.
            mDisplayEngagementModeCallbacks.put(callback, executor);

            // Immediately notify the new callback of all current states. For the first listener,
            // this will be empty. For subsequent listeners, this provides the current state.
            for (int i = 0; i < mLastReportedEngagementModes.size(); i++) {
                final int displayId = mLastReportedEngagementModes.keyAt(i);
                final int engagementMode = mLastReportedEngagementModes.valueAt(i);
                executor.execute(() -> {
                    callback.accept(new DisplayEngagementModeState(displayId, engagementMode));
                });
            }
        }
    }

    @Override
    public void unregisterDisplayEngagementModeCallback(
            @NonNull Consumer<DisplayEngagementModeState> callback) {
        synchronized (mDisplayEngagementModeLock) {
            // Remove the local callback from internal map
            mDisplayEngagementModeCallbacks.remove(callback);

            // Last listener removed, unregister the single proxy from WMS.
            if (mDisplayEngagementModeCallbacks.isEmpty()
                    && mDisplayEngagementModeCallback != null) {
                try {
                    WindowManagerGlobal
                            .getWindowManagerService().unregisterDisplayEngagementModeCallback(
                                    mDisplayEngagementModeCallback);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
                // Clear cache now that we are no longer listening for updates.
                mLastReportedEngagementModes.clear();
            }
        }
    }
}
